package co.com.agsamericas.digsa.identidad;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Entrega E2 de usuarios, roles y logs: roles adicionales parametrizables (Anexo 5, 1.12) con perfil base + subconjunto de pantallas,
 * y el Analista con consulta nacional (prueba en sitio, ítem 3: «Analista… a las propias de su rol»).
 */
class RolesApiTest extends PruebaIntegracion {

    private Navegador admin() {
        Navegador n = navegador();
        assertThat(n.ingresar(crearUsuario("admin", Perfil.ADMINISTRADOR), CLAVE).estado()).isEqualTo(200);
        return n;
    }

    private static Map<String, Object> rol(String nombre, int base, List<String> pantallas) {
        Map<String, Object> m = new HashMap<>();
        m.put("nombre", nombre);
        m.put("descripcion", "Rol de prueba");
        m.put("baseId", base);
        m.put("pantallas", pantallas);
        return m;
    }

    private int crearRol(Navegador adm, String nombre, int base, List<String> pantallas) {
        Respuesta r = adm.post("/api/usuarios/roles", rol(nombre, base, pantallas));
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        return r.json().path("id").asInt();
    }

    /** Usuario con contraseña lista que tiene solo el rol adicional (el perfil se asigna directo en la base de prueba). */
    private String usuarioConRol(int rolId) {
        String u = crearUsuario("conrol");
        jdbc.sql("INSERT INTO usuarios_perfiles (usuario_id, perfil_id) VALUES (:u, :p)").param("u", idDe(u)).param("p", rolId).update();
        return u;
    }

    private static List<String> rutas(JsonNode sesion, String campo) {
        List<String> r = new ArrayList<>();
        sesion.path(campo).forEach(x -> r.add(x.isTextual() ? x.asText() : x.path("ruta").asText()));
        return r;
    }

    @Test
    @DisplayName("rol con base Coordinador y solo Riesgo y Dashboard: menú y API limitados; alcance nacional; sin las acciones del coordinador")
    void rolLimitaPantallas() {
        Navegador adm = admin();
        int id = crearRol(adm, unico("Gestor del riesgo "), Perfil.COORDINADOR.id(), List.of("RIESGO", "DASHBOARD"));
        assertThat(id).isGreaterThanOrEqualTo(100);
        assertThat(jdbc.sql("SELECT count(*) FROM log_transacciones WHERE tipo = 'rol_creado' AND strpos(descripcion, :x) > 0")
                .param("x", "Rol " + id + " ").query(Long.class).single()).isEqualTo(1);

        String u = usuarioConRol(id);
        Navegador n = navegador();
        assertThat(n.ingresar(u, CLAVE).estado()).isEqualTo(200);
        JsonNode s = n.get("/api/sesion").json();
        assertThat(rutas(s, "menu")).containsExactlyInAnyOrder("inicio", "riesgo", "dashboard");
        assertThat(rutas(s, "pantallas")).containsExactlyInAnyOrder("inicio", "riesgo", "dashboard");
        assertThat(n.get("/api/agenda/opciones").estado()).as("agenda es del coordinador pero no del rol").isEqualTo(403);
        assertThat(n.get("/api/maestros/puntos").estado()).isEqualTo(403);
        assertThat(n.get("/api/usuarios").estado()).isEqualTo(403);
        assertThat(n.get("/api/riesgo").estado()).as("su pantalla").isEqualTo(200);
        assertThat(n.get("/api/dashboard/opciones").estado()).isEqualTo(200);
        assertThat(n.get("/api/riesgo/incidencias").estado()).isEqualTo(200);
        // Falla cerrado: el rol no da la identidad de su base (las autoridades llevan solo los perfiles directos)
        assertThat(n.post("/api/riesgo/incidencias", Map.of("descripcion", "x")).estado()).as("registrar incidencias es del coordinador")
                .isEqualTo(403);
        assertThat(n.put("/api/riesgo/pesos", Map.of()).estado()).isEqualTo(403);
        assertThat(n.get("/api/sesion").json().path("perfiles").toString()).as("muestra el rol, no la base").contains("Gestor del riesgo")
                .doesNotContain("Coordinador");
    }

    @Test
    @DisplayName("un rol no se mezcla con perfiles directos ni con roles de otra base (el alcance no se amplía por mezcla)")
    void mezclas() {
        Navegador adm = admin();
        int gestor = crearRol(adm, unico("Gestor mezcla "), Perfil.COORDINADOR.id(), List.of("RIESGO"));
        int consulta = crearRol(adm, unico("Lectura mezcla "), Perfil.DIGSA.id(), List.of("CALENDARIO"));
        String usuario = unico("mezcla").toLowerCase();
        Map<String, Object> d = new HashMap<>();
        d.put("nombre", "Usuario de Mezcla");
        d.put("usuario", usuario);
        d.put("correo", usuario + "@example.com");
        d.put("documento", "80999888");
        d.put("perfiles", List.of(Perfil.AUDITOR.id(), gestor));
        assertThat(adm.post("/api/usuarios", d).mensaje()).contains("no tiene además perfiles");
        d.put("perfiles", List.of(gestor, consulta));
        assertThat(adm.post("/api/usuarios", d).mensaje()).contains("mismo perfil base");
        assertThat(adm.post("/api/usuarios/roles", rol(unico("Auditor parcial "), Perfil.AUDITOR.id(), List.of("MIS_VISITAS"))).estado())
                .as("el Auditor no es base").isEqualTo(422);
        assertThat(adm.post("/api/usuarios/roles", rol(unico("Visita sola "), Perfil.COORDINADOR.id(), List.of("VISITA"))).estado())
                .as("pantalla oculta").isEqualTo(422);
    }

    @Test
    @DisplayName("validaciones: base Administrador, pantalla ajena a la base, sin pantallas, nombre repetido; solo el administrador")
    void validaciones() {
        Navegador adm = admin();
        assertThat(adm.post("/api/usuarios/roles", rol(unico("Raro "), Perfil.ADMINISTRADOR.id(), List.of("USUARIOS"))).estado()).isEqualTo(422);
        assertThat(adm.post("/api/usuarios/roles", rol(unico("Raro "), Perfil.DIGSA.id(), List.of("AGENDA"))).mensaje())
                .contains("no es del perfil base");
        assertThat(adm.post("/api/usuarios/roles", rol(unico("Raro "), Perfil.DIGSA.id(), List.of())).mensaje()).contains("al menos una pantalla");
        assertThat(adm.post("/api/usuarios/roles", rol(unico("Raro "), Perfil.DIGSA.id(), List.of("NO_EXISTE"))).mensaje())
                .isEqualTo("Hay una pantalla no válida.");
        assertThat(adm.post("/api/usuarios/roles", rol("auditor", Perfil.DIGSA.id(), List.of("CALENDARIO"))).mensaje())
                .as("el nombre de un perfil base, con otra grafía").isEqualTo("Ya existe un perfil o rol con ese nombre.");

        Navegador coord = navegador();
        coord.ingresar(crearUsuario("coord", Perfil.COORDINADOR), CLAVE);
        assertThat(coord.get("/api/usuarios/roles").estado()).isEqualTo(403);
        assertThat(coord.post("/api/usuarios/roles", rol(unico("Raro "), Perfil.DIGSA.id(), List.of("CALENDARIO"))).estado()).isEqualTo(403);
    }

    @Test
    @DisplayName("editar el rol: cambia nombre y pantallas con antes y después en la bitácora; la base no se cambia")
    void editar() {
        Navegador adm = admin();
        String nombre = unico("Lectura fuerza ");
        int id = crearRol(adm, nombre, Perfil.DIGSA.id(), List.of("CALENDARIO"));
        Respuesta r = adm.put("/api/usuarios/roles/" + id, rol(nombre + " B", Perfil.DIGSA.id(), List.of("CALENDARIO", "REPORTES")));
        assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        String fila = jdbc.sql("SELECT descripcion FROM log_transacciones WHERE tipo = 'rol_editado' ORDER BY id DESC LIMIT 1").query(String.class).single();
        assertThat(fila).contains("nombre: «" + nombre + "» → «" + nombre + " B»").contains("pantallas: «CALENDARIO» → «CALENDARIO,REPORTES»");
        assertThat(adm.put("/api/usuarios/roles/" + id, rol(nombre, Perfil.SANIDAD.id(), List.of("CALENDARIO"))).mensaje())
                .contains("no se cambia");
        JsonNode cat = adm.get("/api/usuarios/roles").json();
        boolean visto = false;
        for (JsonNode x : cat.path("roles")) {
            if (x.path("id").asInt() == id) {
                visto = true;
                assertThat(x.path("pantallas").toString()).isEqualTo("[\"CALENDARIO\",\"REPORTES\"]");
            }
        }
        assertThat(visto).isTrue();
    }

    @Test
    @DisplayName("inactivar el rol: sus usuarios dejan de ver sus pantallas desde la siguiente petición")
    void inactivar() {
        Navegador adm = admin();
        int id = crearRol(adm, unico("Temporal "), Perfil.DIGSA.id(), List.of("CALENDARIO"));
        String u = usuarioConRol(id);
        Navegador n = navegador();
        n.ingresar(u, CLAVE);
        assertThat(n.get("/api/sesion").json().path("menu").toString()).contains("calendario");
        assertThat(n.get("/api/calendario").estado()).isEqualTo(200);
        Respuesta r = adm.put("/api/usuarios/roles/" + id + "/estado", Map.of("activo", false));
        assertThat(r.mensaje()).contains("1 usuarios");
        assertThat(n.get("/api/sesion").json().path("menu").toString()).doesNotContain("calendario");
        assertThat(n.get("/api/calendario").estado()).as("la API también se cierra").isEqualTo(403);

        // Editar al usuario con el rol inactivo no borra esa asignación (al reactivar el rol vuelve a contar)...
        Map<String, Object> d = new HashMap<>();
        d.put("nombre", "Persona " + u);
        d.put("usuario", u);
        d.put("correo", u + "@example.com");
        d.put("documento", "80777666");
        d.put("perfiles", List.of(Perfil.AUDITOR.id()));
        assertThat(adm.put("/api/usuarios/" + idDe(u), d).estado()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM usuarios_perfiles WHERE usuario_id = :u AND perfil_id = :p").param("u", idDe(u)).param("p", id)
                .query(Long.class).single()).as("se conserva la asignación al rol inactivo").isEqualTo(1);
        // ...pero si al reactivarlo queda una mezcla no válida (Auditor directo + rol), el rol no suma nada: falla cerrado
        assertThat(adm.put("/api/usuarios/roles/" + id + "/estado", Map.of("activo", true)).estado()).isEqualTo(200);
        n.post("/api/auth/salir", null);
        Navegador m = navegador();
        m.ingresar(u, CLAVE);
        assertThat(rutas(m.get("/api/sesion").json(), "menu")).contains("mis-visitas").doesNotContain("calendario");
        assertThat(m.get("/api/calendario").estado()).isEqualTo(403);
        assertThat(adm.put("/api/usuarios/roles/2/estado", Map.of("activo", false)).estado()).as("un perfil base no es un rol").isEqualTo(404);
    }

    @Test
    @DisplayName("el administrador asigna el rol en Usuarios; con base ESM exige el establecimiento; las combinaciones se validan con la base")
    void asignarEnUsuarios() {
        Navegador adm = admin();
        int esm = crearRol(adm, unico("Jefe de farmacia "), Perfil.ESM.id(), List.of("CALENDARIO", "DASHBOARD"));
        String usuario = unico("jefe").toLowerCase();
        Map<String, Object> d = new HashMap<>();
        d.put("nombre", "Jefe de Farmacia de Prueba");
        d.put("usuario", usuario);
        d.put("correo", usuario + "@example.com");
        d.put("perfiles", List.of(esm));
        assertThat(adm.post("/api/usuarios", d).mensaje()).isEqualTo("El perfil ESM necesita el establecimiento (ESM activo).");
        d.put("perfiles", List.of(esm, Perfil.DIGSA.id()));
        assertThat(adm.post("/api/usuarios", d).mensaje()).contains("no tiene además perfiles");
        d.put("perfiles", List.of(esm));
        d.put("alcancePunto", jdbc.sql("SELECT id FROM puntos WHERE activo AND unidad = 'ESM' ORDER BY id LIMIT 1").query(Long.class).single());
        Respuesta r = adm.post("/api/usuarios", d);
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        boolean visto = false;
        for (JsonNode x : adm.get("/api/usuarios").json().path("usuarios")) {
            if (x.path("usuario").asText().equals(usuario)) {
                visto = true;
                assertThat(x.path("perfiles").toString()).isEqualTo("[" + esm + "]");
            }
        }
        assertThat(visto).isTrue();
        assertThat(adm.get("/api/usuarios").json().path("perfiles").toString()).contains("\"id\":" + esm).contains("\"baseId\":" + Perfil.ESM.id());
    }

    @Test
    @DisplayName("Analista (ítem 3): consulta nacional como la DIGSA; no solicita visitas ni entra a la agenda")
    void analista() {
        String u = crearUsuario("analista", Perfil.ANALISTA);
        Navegador n = navegador();
        n.ingresar(u, CLAVE);
        assertThat(rutas(n.get("/api/sesion").json(), "menu")).contains("calendario", "dashboard", "riesgo", "reportes", "hallazgos")
                .doesNotContain("agenda", "solicitudes", "usuarios", "logs", "opl");
        assertThat(n.get("/api/agenda/opciones").estado()).isEqualTo(403);
        assertThat(n.get("/api/calendario").estado()).as("consulta nacional").isEqualTo(200);
        assertThat(n.get("/api/riesgo").estado()).isEqualTo(200);
        assertThat(n.post("/api/riesgo/incidencias", Map.of("descripcion", "x")).estado()).as("solo consulta").isEqualTo(403);
    }
}
