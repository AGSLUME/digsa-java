package co.com.agsamericas.digsa.analitica;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.identidad.Usuario;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Paridad de {@link PerfilesOperacion} con identidad (revisión A3 2026-10-23). PerfilesOperacion replica las reglas de identidad para
 * saber la base de los roles adicionales (roles válidos: {@code Roles.asignacionInvalida}; vigentes: {@code Roles.vigente}; activos), porque
 * la sesión no la expone. Esta prueba existe para DETECTAR DIVERGENCIA: para cada caso compara «identidad concede la pantalla X con la
 * base B» (pantallas de {@code /api/sesion} y {@link Usuario#perfilesDeAlcance()}) con {@code PerfilesOperacion.opera(X, {B})}. Si
 * identidad cambia sus reglas (p. ej. admite la mezcla perfil directo + rol) y PerfilesOperacion no, falla. Se retira cuando la sesión 1
 * (identidad) exponga las bases de los roles en {@code UsuarioSesion.Acceso} y PerfilesOperacion las lea de ahí.
 *
 * <p>Identidad no tiene vigencia por fechas en las asignaciones: un rol «vencido» es el que {@code Roles.vigente} descarta (ninguna de sus
 * pantallas sigue siendo de su base).
 */
class PerfilesOperacionParidadTest extends PruebaIntegracion {

    private static final Set<Perfil> ANALISTA = Set.of(Perfil.ANALISTA);

    @Autowired
    private PerfilesOperacion operacion;

    /** Una sesión por usuario (un ingreso nuevo con actividad reciente se bloquea). */
    private final Map<String, Navegador> sesiones = new HashMap<>();

    private Navegador como(String usuario) {
        Navegador n = navegador();
        assertThat(n.ingresar(usuario, CLAVE).estado()).isEqualTo(200);
        return n;
    }

    private int crearRol(Navegador adm, int base, List<String> pantallas) {
        Map<String, Object> m = new HashMap<>();
        m.put("nombre", unico("Rol paridad "));
        m.put("descripcion", "Rol de prueba");
        m.put("baseId", base);
        m.put("pantallas", pantallas);
        Navegador.Respuesta r = adm.post("/api/usuarios/roles", m);
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        return r.json().path("id").asInt();
    }

    /** Asigna el rol directamente en la base, como RolesApiTest (permite dejar la mezcla que la API no crearía). */
    private void asignar(String usuario, int rol) {
        jdbc.sql("INSERT INTO usuarios_perfiles (usuario_id, perfil_id) VALUES (:u, :p)").param("u", idDe(usuario)).param("p", rol).update();
    }

    /** Pantallas que identidad concede en la sesión ({@code /api/sesion}, campo pantallas: rutas). */
    private Set<Pantallas> pantallasDeSesion(String usuario) {
        Set<Pantallas> r = EnumSet.noneOf(Pantallas.class);
        sesiones.computeIfAbsent(usuario, this::como).get("/api/sesion").json().path("pantallas").forEach(x -> {
            for (Pantallas p : Pantallas.values()) {
                if (p.ruta().equals(x.asText())) {
                    r.add(p);
                }
            }
        });
        return r;
    }

    /** Lo que identidad concede: la pantalla en la sesión y alguno de los perfiles entre los que fijan su alcance (directos + bases). */
    private boolean identidadConcede(String usuario, Pantallas pantalla, Set<Perfil> perfiles) {
        Usuario u = usuarios.porId(idDe(usuario)).orElseThrow();
        return pantallasDeSesion(usuario).contains(pantalla) && u.perfilesDeAlcance().stream().anyMatch(perfiles::contains);
    }

    /** PerfilesOperacion.opera con el contexto que arma el filtro de la sesión (perfiles directos como autoridades, pantallas de la sesión). */
    private boolean opera(String usuario, Pantallas pantalla, Set<Perfil> perfiles) {
        Usuario u = usuarios.porId(idDe(usuario)).orElseThrow();
        AbstractAuthenticationToken auth = (AbstractAuthenticationToken) UsuarioSesion.autenticacion(
                new UsuarioSesion(u.id(), u.usuario(), u.nombre()), u.perfiles());
        auth.setDetails(new UsuarioSesion.Acceso(u.alcance(), pantallasDeSesion(usuario), u.asignados()));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        try {
            return operacion.opera(pantalla, perfiles);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** Compara para cada pantalla lo que concede identidad con PerfilesOperacion, y el valor esperado hoy. */
    private void paridad(String caso, String usuario, Map<Pantallas, Boolean> esperado) {
        esperado.forEach((pantalla, valor) -> {
            boolean identidad = identidadConcede(usuario, pantalla, ANALISTA);
            boolean analitica = opera(usuario, pantalla, ANALISTA);
            assertThat(analitica).as("%s · %s: PerfilesOperacion diverge de identidad", caso, pantalla).isEqualTo(identidad);
            assertThat(analitica).as("%s · %s: valor esperado", caso, pantalla).isEqualTo(valor);
        });
    }

    @Test
    @DisplayName("paridad con identidad: rol vencido, rol inactivo, rol vigente (Analista + Homologación) y perfil directo + rol (mezcla)")
    void paridadConIdentidad() {
        Navegador adm = como(crearUsuario("admpar", Perfil.ADMINISTRADOR));

        // Rol VENCIDO: sus pantallas dejaron de ser de su base (Roles.vigente lo descarta)
        int vencido = crearRol(adm, Perfil.ANALISTA.id(), List.of("HOMOLOGACION"));
        jdbc.sql("DELETE FROM perfiles_pantallas WHERE perfil_id = :p").param("p", vencido).update();
        jdbc.sql("INSERT INTO perfiles_pantallas (perfil_id, pantalla) VALUES (:p, 'USUARIOS')").param("p", vencido).update();
        String conVencido = crearUsuario("vencpar");
        asignar(conVencido, vencido);
        paridad("rol vencido", conVencido, Map.of(Pantallas.HOMOLOGACION, false, Pantallas.USUARIOS, false));

        // Rol INACTIVO: el administrador lo inactivó (la asignación se conserva)
        int inactivo = crearRol(adm, Perfil.ANALISTA.id(), List.of("HOMOLOGACION", "ANALITICA_CARGUES"));
        String conInactivo = crearUsuario("inacpar");
        asignar(conInactivo, inactivo);
        assertThat(adm.put("/api/usuarios/roles/" + inactivo + "/estado", Map.of("activo", false)).estado()).isEqualTo(200);
        paridad("rol inactivo", conInactivo, Map.of(Pantallas.HOMOLOGACION, false, Pantallas.ANALITICA_CARGUES, false));

        // Rol VIGENTE con base Analista y solo Homologación: opera la homologación, no los cargues (no marcados)
        int vigente = crearRol(adm, Perfil.ANALISTA.id(), List.of("HOMOLOGACION"));
        String conVigente = crearUsuario("vigpar");
        asignar(conVigente, vigente);
        paridad("rol vigente", conVigente, Map.of(Pantallas.HOMOLOGACION, true, Pantallas.ANALITICA_CARGUES, false));

        // Perfil directo ESM + rol de base Analista (asignación inválida): identidad anula el rol, PerfilesOperacion no da su base
        String mezcla = crearUsuario("mezpar", Perfil.ESM);
        asignar(mezcla, vigente);
        paridad("perfil directo + rol", mezcla, Map.of(Pantallas.HOMOLOGACION, false, Pantallas.ANALITICA_CARGUES, false));
    }
}
