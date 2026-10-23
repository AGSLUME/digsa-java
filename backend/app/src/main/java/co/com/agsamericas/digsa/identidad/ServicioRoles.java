package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reglas.Roles;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roles adicionales parametrizables (Anexo 5, 1.12; V31): el administrador los crea con un perfil base y un subconjunto de sus
 * pantallas, los edita y los activa o inactiva. El perfil base no se cambia: cambiaría el alcance de los datos de quienes ya lo tienen.
 */
@Service
public class ServicioRoles {

    public record Datos(String nombre, String descripcion, Integer baseId, List<String> pantallas) {
    }

    private final JdbcClient jdbc;
    private final RepositorioUsuarios usuarios;
    private final RegistroBitacora bitacora;
    private final Clock reloj;

    public ServicioRoles(JdbcClient jdbc, RepositorioUsuarios usuarios, RegistroBitacora bitacora, Clock reloj) {
        this.jdbc = jdbc;
        this.usuarios = usuarios;
        this.bitacora = bitacora;
        this.reloj = reloj;
    }

    /** Perfiles que pueden ser base, con sus pantallas elegibles, y los roles creados con cuántos usuarios activos los tienen. */
    @Transactional(readOnly = true)
    public Map<String, Object> catalogo() {
        Map<Integer, String> nombres = usuarios.nombresPerfiles();
        List<Map<String, Object>> bases = new ArrayList<>();
        for (Perfil p : Perfil.values()) {
            if (!Roles.BASES.contains(p)) {
                continue;
            }
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("id", p.id());
            b.put("nombre", nombres.getOrDefault(p.id(), p.nombre()));
            b.put("pantallas", Roles.elegibles(p).stream().map(x -> Map.of("codigo", x.name(), "titulo", x.titulo())).toList());
            bases.add(b);
        }
        List<Map<String, Object>> roles = jdbc.sql("""
                SELECT p.id, p.nombre, p.descripcion, p.base_id, p.activo,
                       COALESCE((SELECT string_agg(pp.pantalla, ',' ORDER BY pp.pantalla) FROM perfiles_pantallas pp WHERE pp.perfil_id = p.id), '') AS pantallas,
                       (SELECT count(*) FROM usuarios_perfiles up JOIN usuarios u ON u.id = up.usuario_id WHERE up.perfil_id = p.id AND u.activo) AS usuarios
                FROM perfiles p WHERE p.base_id IS NOT NULL ORDER BY p.activo DESC, p.nombre""")
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("nombre", rs.getString("nombre"));
                    m.put("descripcion", rs.getString("descripcion"));
                    m.put("baseId", rs.getInt("base_id"));
                    m.put("activo", rs.getBoolean("activo"));
                    String ps = rs.getString("pantallas");
                    m.put("pantallas", ps.isEmpty() ? List.of() : List.of(ps.split(",")));
                    m.put("usuarios", rs.getLong("usuarios"));
                    return m;
                }).list();
        return Map.of("bases", bases, "roles", roles);
    }

    @Transactional
    public int crear(Datos d, UsuarioSesion quien) {
        String nombre = nombre(d.nombre(), 0);
        String descripcion = descripcion(d.descripcion());
        Perfil base = d.baseId() == null ? null : Perfil.deId(d.baseId()).orElse(null);
        Set<Pantallas> pantallas = pantallas(base, d.pantallas());
        int id;
        try {
            id = jdbc.sql("""
                    INSERT INTO perfiles (id, nombre, descripcion, activo, base_id, creado_por, creado)
                    VALUES (nextval('perfiles_adicionales_id_seq'), :n, :d, true, :b, :u, :ahora) RETURNING id""")
                    .param("n", nombre).param("d", descripcion).param("b", base.id()).param("u", quien.usuario())
                    .param("ahora", OffsetDateTime.now(reloj)).query(Integer.class).single();
        } catch (DuplicateKeyException e) {
            throw ErrorNegocio.validacion("Ya existe un perfil o rol con ese nombre.");
        }
        guardarPantallas(id, pantallas);
        bitacora.registrar("rol_creado", "Rol " + id + " «" + nombre + "» con base " + base.nombre() + "; pantallas: " + codigos(pantallas),
                quien.id(), quien.usuario());
        return id;
    }

    @Transactional
    public void editar(int id, Datos d, UsuarioSesion quien) {
        Map<String, Object> antes = jdbc.sql("SELECT nombre, descripcion, base_id FROM perfiles WHERE id = :id AND base_id IS NOT NULL FOR UPDATE")
                .param("id", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("El rol no existe."));
        Perfil base = Perfil.deId(((Number) antes.get("base_id")).intValue()).orElseThrow();
        if (d.baseId() != null && d.baseId() != base.id()) {
            throw ErrorNegocio.validacion("El perfil base de un rol no se cambia: cambiaría el alcance de sus usuarios. Cree otro rol.");
        }
        String nombre = nombre(d.nombre(), id);
        String descripcion = descripcion(d.descripcion());
        Set<Pantallas> pantallas = pantallas(base, d.pantallas());
        Set<String> pantallasAntes = new TreeSet<>(jdbc.sql("SELECT pantalla FROM perfiles_pantallas WHERE perfil_id = :id").param("id", id)
                .query(String.class).list());
        try {
            jdbc.sql("UPDATE perfiles SET nombre = :n, descripcion = :d WHERE id = :id").param("n", nombre).param("d", descripcion).param("id", id).update();
        } catch (DuplicateKeyException e) {
            throw ErrorNegocio.validacion("Ya existe un perfil o rol con ese nombre.");
        }
        jdbc.sql("DELETE FROM perfiles_pantallas WHERE perfil_id = :id").param("id", id).update();
        guardarPantallas(id, pantallas);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("nombre", antes.get("nombre"));
        a.put("descripcion", antes.get("descripcion"));
        a.put("pantallas", pantallasAntes);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("nombre", nombre);
        b.put("descripcion", descripcion);
        b.put("pantallas", new TreeSet<>(codigos(pantallas)));
        bitacora.registrarCambios("rol_editado", "Rol " + id, a, b, Set.of(), quien.id(), quien.usuario());
    }

    /** Activa o inactiva el rol. Inactivo, deja de dar acceso desde la siguiente petición de sus usuarios. */
    @Transactional
    public long cambiarEstado(int id, boolean activo, UsuarioSesion quien) {
        int n = jdbc.sql("UPDATE perfiles SET activo = :a WHERE id = :id AND base_id IS NOT NULL").param("a", activo).param("id", id).update();
        if (n == 0) {
            throw ErrorNegocio.noEncontrado("El rol no existe.");
        }
        long conRol = jdbc.sql("SELECT count(*) FROM usuarios_perfiles up JOIN usuarios u ON u.id = up.usuario_id WHERE up.perfil_id = :id AND u.activo")
                .param("id", id).query(Long.class).single();
        bitacora.registrar("rol_estado", "Rol " + id + (activo ? " activado" : " inactivado") + " (" + conRol + " usuarios activos lo tienen)",
                quien.id(), quien.usuario());
        return conRol;
    }

    private void guardarPantallas(int id, Set<Pantallas> pantallas) {
        for (Pantallas p : pantallas) {
            jdbc.sql("INSERT INTO perfiles_pantallas (perfil_id, pantalla) VALUES (:id, :p)").param("id", id).param("p", p.name()).update();
        }
    }

    private String nombre(String escrito, int excepto) {
        String n = escrito == null ? "" : escrito.strip();
        if (n.length() < 3 || n.length() > 60) {
            throw ErrorNegocio.validacion("El nombre del rol debe tener de 3 a 60 caracteres.");
        }
        if (usuarios.nombresPerfiles().entrySet().stream().anyMatch(e -> e.getKey() != excepto && e.getValue().equalsIgnoreCase(n))) {
            throw ErrorNegocio.validacion("Ya existe un perfil o rol con ese nombre.");
        }
        return n;
    }

    private static String descripcion(String escrita) {
        String d = escrita == null ? "" : escrita.strip();
        if (d.length() > 200) {
            throw ErrorNegocio.validacion("La descripción tiene máximo 200 caracteres.");
        }
        return d.isEmpty() ? null : d;
    }

    private static Set<Pantallas> pantallas(Perfil base, List<String> codigos) {
        EnumSet<Pantallas> r = EnumSet.noneOf(Pantallas.class);
        for (String c : codigos == null ? List.<String>of() : codigos) {
            try {
                r.add(Pantallas.valueOf(c));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw ErrorNegocio.validacion("Hay una pantalla no válida.");
            }
        }
        Roles.problema(base, r).ifPresent(m -> {
            throw ErrorNegocio.validacion(m);
        });
        return r;
    }

    private static List<String> codigos(Set<Pantallas> pantallas) {
        return pantallas.stream().map(Pantallas::name).toList();
    }
}
