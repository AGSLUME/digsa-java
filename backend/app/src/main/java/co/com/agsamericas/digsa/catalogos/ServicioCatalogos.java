package co.com.agsamericas.digsa.catalogos;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Fechas;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catálogos editables en vivo por el coordinador (Anexo 5, prueba 4: fuerzas y tipos de documento se crean, modifican y eliminan
 * desde la pantalla, con validaciones), regionales de la interventoría y parámetros con rango.
 */
@Service
public class ServicioCatalogos {

    private static final Pattern CODIGO_FUERZA = Pattern.compile("^[A-Z]{2,5}$");
    static final int MAX_REGIONALES = 30;
    static final int MAX_CORREOS = 10;

    /** Rango de los parámetros enteros editables (spec catalogos-festivos-plazos). */
    static final Map<String, int[]> RANGOS = Map.of(
            "notif_habiles_modulo", new int[] {1, 30}, "aviso_umbral_opl", new int[] {0, 15}, "aviso_umbral_modulo", new int[] {0, 10},
            "max_seguimientos", new int[] {1, 5}, "recordatorio_seguimiento_habiles", new int[] {1, 20}, "visitas_por_dia", new int[] {1, 6},
            "km_mismo_dia", new int[] {1, 300});

    public record Fuerza(String codigo, String nombre, String sanidad, List<String> correos, Boolean activo) {
    }

    public record Valor(String codigo, String descripcion, Boolean activo) {
    }

    private final JdbcClient jdbc;
    private final RegistroBitacora bitacora;
    private final Clock reloj;

    public ServicioCatalogos(JdbcClient jdbc, RegistroBitacora bitacora, Clock reloj) {
        this.jdbc = jdbc;
        this.bitacora = bitacora;
        this.reloj = reloj;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> todo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fuerzas", fuerzas());
        List<Map<String, Object>> catalogos = jdbc.sql("SELECT codigo, nombre, descripcion, patron FROM catalogos ORDER BY orden, codigo")
                .query().listOfRows();
        List<Map<String, Object>> conValores = new ArrayList<>();
        for (Map<String, Object> c : catalogos) {
            Map<String, Object> x = new LinkedHashMap<>(c);
            x.put("valores", jdbc.sql("""
                    SELECT id, codigo, descripcion, activo FROM catalogo_valores WHERE catalogo = :c ORDER BY orden, codigo""")
                    .param("c", c.get("codigo")).query().listOfRows());
            conValores.add(x);
        }
        m.put("catalogos", conValores);
        m.put("regionales", regionales());
        m.put("parametros", jdbc.sql("SELECT clave, valor, descripcion, fuente, editable FROM par_parametros ORDER BY editable DESC, clave")
                .query().listOfRows());
        m.put("unidades", jdbc.sql("SELECT codigo, nombre FROM par_unidades ORDER BY orden").query().listOfRows());
        m.put("modalidades", jdbc.sql("SELECT nombre FROM par_modalidades ORDER BY orden").query(String.class).list());
        m.put("tiposVisita", jdbc.sql("SELECT codigo, nombre, unidad, modalidad, aviso FROM par_tipos_visita ORDER BY orden").query().listOfRows());
        m.put("departamentos", jdbc.sql("SELECT codigo, nombre FROM par_departamentos ORDER BY nombre").query().listOfRows());
        return m;
    }

    public List<Map<String, Object>> municipios(String departamento) {
        if (departamento == null || !departamento.matches("^[0-9]{2}$")) {
            throw ErrorNegocio.validacion("Elija el departamento.");
        }
        return jdbc.sql("SELECT codigo, nombre FROM par_municipios WHERE departamento = :d ORDER BY nombre").param("d", departamento)
                .query().listOfRows();
    }

    public List<Map<String, Object>> fuerzas() {
        return jdbc.sql("SELECT codigo, nombre, sanidad, correos, activo FROM par_fuerzas ORDER BY orden, codigo").query((rs, n) -> {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("codigo", rs.getString("codigo"));
            f.put("nombre", rs.getString("nombre"));
            f.put("sanidad", rs.getString("sanidad"));
            f.put("correos", List.of((String[]) rs.getArray("correos").getArray()));
            f.put("activo", rs.getBoolean("activo"));
            return f;
        }).list();
    }

    public List<Map<String, Object>> regionales() {
        return jdbc.sql("SELECT id, nombre, activo FROM par_regionales ORDER BY nombre").query().listOfRows();
    }

    // ---------- Fuerzas ----------

    @Transactional
    public void crearFuerza(Fuerza f, UsuarioSesion quien) {
        String codigo = texto(f.codigo()).toUpperCase();
        if (!CODIGO_FUERZA.matcher(codigo).matches()) {
            throw ErrorNegocio.validacion("El código de la fuerza debe tener de 2 a 5 letras mayúsculas (p. ej. EJC).");
        }
        Fuerza v = validarFuerza(f);
        Integer orden = jdbc.sql("SELECT coalesce(max(orden), 0) + 1 FROM par_fuerzas").query(Integer.class).single();
        try {
            jdbc.sql("""
                    INSERT INTO par_fuerzas (codigo, nombre, sanidad, correos, orden, activo, fuente)
                    VALUES (:c, :n, :s, :correos, :o, true, 'Creada en la aplicación')""")
                    .param("c", codigo).param("n", v.nombre()).param("s", v.sanidad()).param("correos", v.correos().toArray(String[]::new))
                    .param("o", orden).update();
        } catch (DuplicateKeyException e) {
            throw ErrorNegocio.validacion("Ya existe una fuerza con el código " + codigo + ".");
        }
        bitacora.registrar("fuerza_creada", "Fuerza " + codigo + " creada: «" + v.nombre() + "»", quien.id(), quien.usuario());
    }

    @Transactional
    public void editarFuerza(String codigo, Fuerza f, UsuarioSesion quien) {
        Map<String, Object> antes = jdbc.sql("SELECT nombre, sanidad, correos, activo FROM par_fuerzas WHERE codigo = :c FOR UPDATE")
                .param("c", codigo).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("nombre", rs.getString(1));
                    m.put("sanidad", rs.getString(2));
                    m.put("correos", List.of((String[]) rs.getArray(3).getArray()));
                    m.put("activo", rs.getBoolean(4));
                    return m;
                }).optional().orElseThrow(() -> ErrorNegocio.noEncontrado("La fuerza no existe."));
        Fuerza v = validarFuerza(f);
        boolean activo = f.activo() == null || f.activo(); // inactivar sí se puede aunque la usen puntos: deja de ofrecerse y los existentes la conservan
        jdbc.sql("UPDATE par_fuerzas SET nombre = :n, sanidad = :s, correos = :correos, activo = :a WHERE codigo = :c")
                .param("n", v.nombre()).param("s", v.sanidad()).param("correos", v.correos().toArray(String[]::new)).param("a", activo)
                .param("c", codigo).update();
        Map<String, Object> despues = new LinkedHashMap<>();
        despues.put("nombre", v.nombre());
        despues.put("sanidad", v.sanidad());
        despues.put("correos", v.correos());
        despues.put("activo", activo);
        String cambios = Bitacora.cambios(antes, despues, Set.of("correos"));
        bitacora.registrar("fuerza_editada", "Fuerza " + codigo + (cambios.isEmpty() ? " sin cambios" : ": " + cambios), quien.id(), quien.usuario());
    }

    /** Se elimina si nadie la usa; si la usan puntos o usuarios, se pide inactivarla (los históricos no pierden su fuerza). */
    @Transactional
    public void eliminarFuerza(String codigo, UsuarioSesion quien) {
        String nombre = jdbc.sql("SELECT nombre FROM par_fuerzas WHERE codigo = :c FOR UPDATE").param("c", codigo).query(String.class)
                .optional().orElseThrow(() -> ErrorNegocio.noEncontrado("La fuerza no existe."));
        long uso = enUso(codigo);
        if (uso > 0) {
            throw ErrorNegocio.validacion("La fuerza " + codigo + " está en uso (" + uso + " puntos o usuarios). Inactívela en lugar de eliminarla.");
        }
        try {
            jdbc.sql("DELETE FROM par_fuerzas WHERE codigo = :c").param("c", codigo).update();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw ErrorNegocio.validacion("La fuerza " + codigo + " quedó en uso mientras se eliminaba. Inactívela en lugar de eliminarla.");
        }
        bitacora.registrar("fuerza_eliminada", "Fuerza " + codigo + " eliminada: «" + nombre + "»", quien.id(), quien.usuario());
    }

    private long enUso(String codigo) {
        return jdbc.sql("""
                SELECT (SELECT count(*) FROM puntos WHERE fuerza = :c) + (SELECT count(*) FROM usuarios WHERE alcance_fuerza = :c)""")
                .param("c", codigo).query(Long.class).single();
    }

    private Fuerza validarFuerza(Fuerza f) {
        String nombre = texto(f.nombre());
        if (nombre.length() < 2 || nombre.length() > 120) {
            throw ErrorNegocio.validacion("El nombre de la fuerza debe tener de 2 a 120 caracteres.");
        }
        String sanidad = vacioANulo(f.sanidad());
        if (sanidad != null && sanidad.length() > 150) {
            throw ErrorNegocio.validacion("La sanidad (DISAN o JEFSA) tiene máximo 150 caracteres.");
        }
        return new Fuerza(null, nombre, sanidad, correos(f.correos()), f.activo());
    }

    static List<String> correos(List<String> entrada) {
        Set<String> r = new LinkedHashSet<>();
        if (entrada != null) {
            for (String c : entrada) {
                String n = Correos.normalizar(texto(c));
                if (n.isEmpty()) {
                    continue;
                }
                if (!Correos.valido(n)) {
                    throw ErrorNegocio.validacion("El correo «" + Bitacora.cortar(texto(c), 60) + "» no es válido.");
                }
                r.add(n);
            }
        }
        if (r.size() > MAX_CORREOS) {
            throw ErrorNegocio.validacion("Máximo " + MAX_CORREOS + " correos.");
        }
        return List.copyOf(r);
    }

    // ---------- Valores de los catálogos parametrizables ----------

    @Transactional
    public long crearValor(String catalogo, Valor v, UsuarioSesion quien) {
        Map<String, Object> c = catalogo(catalogo);
        String codigo = validarCodigo(c, v.codigo());
        String descripcion = validarDescripcion(v.descripcion());
        Integer orden = jdbc.sql("SELECT coalesce(max(orden), 0) + 1 FROM catalogo_valores WHERE catalogo = :c").param("c", catalogo)
                .query(Integer.class).single();
        try {
            long id = jdbc.sql("""
                    INSERT INTO catalogo_valores (catalogo, codigo, descripcion, orden) VALUES (:c, :cod, :d, :o) RETURNING id""")
                    .param("c", catalogo).param("cod", codigo).param("d", descripcion).param("o", orden).query(Long.class).single();
            bitacora.registrar("catalogo_creado", c.get("nombre") + ": valor " + codigo + " «" + descripcion + "» creado", quien.id(), quien.usuario());
            return id;
        } catch (DuplicateKeyException e) {
            throw ErrorNegocio.validacion("Ya existe el código " + codigo + " en " + c.get("nombre") + ".");
        }
    }

    @Transactional
    public void editarValor(long id, Valor v, UsuarioSesion quien) {
        Map<String, Object> antes = jdbc.sql("SELECT catalogo, codigo, descripcion, activo FROM catalogo_valores WHERE id = :id FOR UPDATE")
                .param("id", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("El valor no existe."));
        Map<String, Object> c = catalogo((String) antes.get("catalogo"));
        String codigo = validarCodigo(c, v.codigo());
        String descripcion = validarDescripcion(v.descripcion());
        boolean activo = v.activo() == null ? Boolean.TRUE.equals(antes.get("activo")) : v.activo();
        try {
            jdbc.sql("UPDATE catalogo_valores SET codigo = :cod, descripcion = :d, activo = :a, actualizado = :ahora WHERE id = :id")
                    .param("cod", codigo).param("d", descripcion).param("a", activo).param("ahora", OffsetDateTime.now(reloj)).param("id", id)
                    .update();
        } catch (DuplicateKeyException e) {
            throw ErrorNegocio.validacion("Ya existe el código " + codigo + " en " + c.get("nombre") + ".");
        }
        Map<String, Object> despues = new LinkedHashMap<>();
        despues.put("codigo", codigo);
        despues.put("descripcion", descripcion);
        despues.put("activo", activo);
        String cambios = Bitacora.cambios(antes, despues, Set.of());
        bitacora.registrar("catalogo_editado", c.get("nombre") + ": valor " + antes.get("codigo") + (cambios.isEmpty() ? " sin cambios" : ": " + cambios),
                quien.id(), quien.usuario());
    }

    @Transactional
    public void eliminarValor(long id, UsuarioSesion quien) {
        Map<String, Object> antes = jdbc.sql("SELECT catalogo, codigo, descripcion FROM catalogo_valores WHERE id = :id FOR UPDATE")
                .param("id", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("El valor no existe."));
        Map<String, Object> c = catalogo((String) antes.get("catalogo"));
        jdbc.sql("DELETE FROM catalogo_valores WHERE id = :id").param("id", id).update();
        bitacora.registrar("catalogo_eliminado", c.get("nombre") + ": valor " + antes.get("codigo") + " «" + antes.get("descripcion") + "» eliminado",
                quien.id(), quien.usuario());
    }

    private Map<String, Object> catalogo(String codigo) {
        return jdbc.sql("SELECT codigo, nombre, patron FROM catalogos WHERE codigo = :c").param("c", codigo).query().listOfRows().stream()
                .findFirst().orElseThrow(() -> ErrorNegocio.noEncontrado("El catálogo no existe."));
    }

    private static String validarCodigo(Map<String, Object> c, String entrada) {
        String codigo = texto(entrada).toUpperCase();
        Pattern patron;
        try {
            patron = Pattern.compile((String) c.get("patron"));
        } catch (PatternSyntaxException e) {
            patron = Pattern.compile("^[A-Z0-9]{1,10}$");
        }
        if (codigo.isEmpty() || codigo.length() > 10 || !patron.matcher(codigo).matches()) {
            throw ErrorNegocio.validacion("El código «" + Bitacora.cortar(codigo, 12) + "» no tiene el formato de " + c.get("nombre")
                    + ("tipo_documento".equals(c.get("codigo")) ? " (de 1 a 3 letras mayúsculas, p. ej. C)."
                    : "tipo_factura".equals(c.get("codigo")) ? " (de 1 a 3 dígitos, p. ej. 1)." : "."));
        }
        return codigo;
    }

    private static String validarDescripcion(String entrada) {
        String d = texto(entrada);
        if (d.length() < 2 || d.length() > 120) {
            throw ErrorNegocio.validacion("La descripción debe tener de 2 a 120 caracteres.");
        }
        return d;
    }

    // ---------- Regionales ----------

    @Transactional
    public long crearRegional(String nombre, UsuarioSesion quien) {
        String n = validarRegional(nombre);
        long total = jdbc.sql("SELECT count(*) FROM par_regionales").query(Long.class).single();
        if (total >= MAX_REGIONALES) {
            throw ErrorNegocio.validacion("Máximo " + MAX_REGIONALES + " regionales.");
        }
        try {
            long id = jdbc.sql("INSERT INTO par_regionales (nombre) VALUES (:n) RETURNING id").param("n", n).query(Long.class).single();
            bitacora.registrar("regional_creada", "Regional «" + n + "» creada", quien.id(), quien.usuario());
            return id;
        } catch (DuplicateKeyException e) {
            throw ErrorNegocio.validacion("Ya existe la regional «" + n + "».");
        }
    }

    /** Las regionales no se borran: se renombran o se inactivan. */
    @Transactional
    public void editarRegional(long id, String nombre, boolean activo, UsuarioSesion quien) {
        Map<String, Object> antes = jdbc.sql("SELECT nombre, activo FROM par_regionales WHERE id = :id FOR UPDATE").param("id", id).query()
                .listOfRows().stream().findFirst().orElseThrow(() -> ErrorNegocio.noEncontrado("La regional no existe."));
        String n = validarRegional(nombre);
        if (!activo) {
            long usuarios = jdbc.sql("SELECT count(*) FROM usuarios WHERE alcance_regional = :id AND activo").param("id", id).query(Long.class).single();
            if (usuarios > 0) {
                throw ErrorNegocio.validacion("La regional tiene " + usuarios + " usuarios OPL regional activos: cámbielos antes de inactivarla.");
            }
        }
        try {
            jdbc.sql("UPDATE par_regionales SET nombre = :n, activo = :a WHERE id = :id").param("n", n).param("a", activo).param("id", id).update();
        } catch (DuplicateKeyException e) {
            throw ErrorNegocio.validacion("Ya existe la regional «" + n + "».");
        }
        String cambios = Bitacora.cambios(antes, Map.of("nombre", n, "activo", activo), Set.of());
        bitacora.registrar("regional_editada", "Regional " + id + (cambios.isEmpty() ? " sin cambios" : ": " + cambios), quien.id(), quien.usuario());
    }

    private static String validarRegional(String nombre) {
        String n = texto(nombre);
        if (n.length() < 2 || n.length() > 80) {
            throw ErrorNegocio.validacion("El nombre de la regional debe tener de 2 a 80 caracteres.");
        }
        return n;
    }

    // ---------- Parámetros ----------

    @Transactional
    public void editarParametro(String clave, String valor, UsuarioSesion quien) {
        Map<String, Object> p = jdbc.sql("SELECT valor, editable FROM par_parametros WHERE clave = :c FOR UPDATE").param("c", clave).query()
                .listOfRows().stream().findFirst().orElseThrow(() -> ErrorNegocio.noEncontrado("El parámetro no existe."));
        if (!Boolean.TRUE.equals(p.get("editable"))) {
            throw ErrorNegocio.validacion("El parámetro «" + clave + "» es una regla fija del Anexo 3 y no se puede cambiar.");
        }
        String v = validarParametro(clave, valor);
        jdbc.sql("UPDATE par_parametros SET valor = :v, actualizado_por = :u, actualizado = :ahora WHERE clave = :c")
                .param("v", v).param("u", quien.usuario()).param("ahora", OffsetDateTime.now(reloj)).param("c", clave).update();
        String antes = p.get("valor") == null ? "" : String.valueOf(p.get("valor"));
        boolean conCorreos = "correo_digsa".equals(clave);
        bitacora.registrar("parametro_editado", "Parámetro " + clave + (antes.equals(v) ? " sin cambios"
                : conCorreos ? ": modificado" : ": «" + Bitacora.cortar(antes, 40) + "» → «" + Bitacora.cortar(v, 40) + "»"), quien.id(), quien.usuario());
    }

    static String validarParametro(String clave, String valor) {
        String v = texto(valor);
        int[] rango = RANGOS.get(clave);
        if (rango != null) {
            if (!v.matches("^-?[0-9]{1,4}$") || Integer.parseInt(v) < rango[0] || Integer.parseInt(v) > rango[1]) {
                throw ErrorNegocio.validacion("El valor de «" + clave + "» debe ser un número entre " + rango[0] + " y " + rango[1] + ".");
            }
            return String.valueOf(Integer.parseInt(v));
        }
        return switch (clave) {
            case "inicio_contrato" -> {
                if (!v.isEmpty() && Fechas.valida(v).isEmpty()) {
                    throw ErrorNegocio.validacion("La fecha de inicio del contrato no es válida (AAAA-MM-DD).");
                }
                yield v;
            }
            case "correo_digsa" -> {
                if (v.length() > 500) {
                    throw ErrorNegocio.validacion("Los correos de la DIGSA tienen máximo 500 caracteres.");
                }
                yield String.join(", ", correos(List.of(v.split("[,;\\s]+"))));
            }
            default -> {
                if (v.length() > 500) {
                    throw ErrorNegocio.validacion("El valor tiene máximo 500 caracteres.");
                }
                yield v;
            }
        };
    }

    /** Lectura para las reglas (plazos, propuesta de agenda): entero del parámetro o el valor por defecto. */
    public int entero(String clave, int defecto) {
        Optional<String> v = jdbc.sql("SELECT valor FROM par_parametros WHERE clave = :c").param("c", clave).query(String.class).optional();
        try {
            return v.filter(x -> x.matches("^-?[0-9]{1,6}$")).map(Integer::parseInt).orElse(defecto);
        } catch (NumberFormatException e) {
            return defecto;
        }
    }

    public String textoParametro(String clave, String defecto) {
        return jdbc.sql("SELECT valor FROM par_parametros WHERE clave = :c").param("c", clave).query(String.class).optional()
                .filter(x -> !x.isBlank()).orElse(defecto);
    }

    private static String texto(String s) {
        return s == null ? "" : s.strip();
    }

    private static String vacioANulo(String s) {
        String t = texto(s);
        return t.isEmpty() ? null : t;
    }
}
