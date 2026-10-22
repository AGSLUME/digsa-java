package co.com.agsamericas.digsa.identidad;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Usuarios del administrador (Anexo 5, prueba 2) y contraseñas por enlace (portado de tests/e2e_local.php). */
class UsuariosYClavesApiTest extends PruebaIntegracion {

    private static final Pattern ENLACE = Pattern.compile("definir-clave\\?tkn=([a-f0-9]{64})");

    private static Map<String, Object> datos(String usuario, List<Integer> perfiles) {
        Map<String, Object> m = new HashMap<>();
        m.put("nombre", "Usuario de prueba " + usuario);
        m.put("usuario", usuario);
        m.put("correo", usuario + "@example.com");
        m.put("documento", "12345678");
        m.put("perfiles", perfiles);
        return m;
    }

    private Navegador admin() {
        Navegador n = navegador();
        assertThat(n.ingresar(crearUsuario("admin", Perfil.ADMINISTRADOR), CLAVE).estado()).isEqualTo(200);
        return n;
    }

    private String token(String correo) {
        Matcher m = ENLACE.matcher(ultimoCorreoA(correo).html());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    @DisplayName("prueba 2: el administrador crea Analista, Auditor, Administrador y OPL; la contraseña queda cifrada y llega el enlace")
    void crearLosCincoDeLaPrueba() {
        Navegador n = admin();
        Map<String, Integer> perfiles = Map.of("analista1", 10, "auditor1", 3, "auditor2", 3, "administrador", 1, "opl", 8);
        for (var e : perfiles.entrySet()) {
            String u = unico(e.getKey()).toLowerCase();
            Respuesta r = n.post("/api/usuarios", datos(u, List.of(e.getValue())));
            assertThat(r.estado()).as(r.texto()).isEqualTo(201);
            assertThat(r.json().path("correoOk").asBoolean()).isTrue();
            Usuario creado = usuarios.porUsuario(u).orElseThrow();
            assertThat(creado.claveHash()).startsWith("$2").doesNotContain(CLAVE);
            assertThat(creado.claveDefinida()).isFalse();
            assertThat(ultimoCorreoA(u + "@example.com").asunto()).isEqualTo("Bienvenido a la Suite DIGSA");
        }
    }

    @Test
    @DisplayName("enlace de un solo uso: define la contraseña, luego ingresa; el segundo uso ya no sirve")
    void enlaceUnSoloUso() {
        Navegador n = admin();
        String u = unico("enlace").toLowerCase();
        n.post("/api/usuarios", datos(u, List.of(3)));
        String tkn = token(u + "@example.com");
        Navegador nuevo = navegador();
        assertThat(nuevo.get("/api/auth/clave?tkn=" + tkn).json().path("valido").asBoolean()).isTrue();
        assertThat(nuevo.post("/api/auth/clave", Map.of("tkn", tkn, "clave", "Corta.1", "confirmacion", "Corta.1")).mensaje())
                .contains("mínimo 12");
        assertThat(nuevo.post("/api/auth/clave", Map.of("tkn", tkn, "clave", "Contraseña.2026X", "confirmacion", "Contraseña.2026X")).mensaje())
                .contains("ñ ni tildes");
        Respuesta ok = nuevo.post("/api/auth/clave", Map.of("tkn", tkn, "clave", "Nueva.Clave.2026", "confirmacion", "Nueva.Clave.2026"));
        assertThat(ok.estado()).isEqualTo(200);
        assertThat(ok.mensaje()).contains("se cambió correctamente");
        assertThat(nuevo.get("/api/auth/clave?tkn=" + tkn).json().path("valido").asBoolean()).isFalse();
        assertThat(nuevo.ingresar(u, "Nueva.Clave.2026").estado()).isEqualTo(200);
        assertThat(bitacoraDe(idDe(u))).anyMatch(f -> f.startsWith("actualizacion|clave_cambio|"));
    }

    @Test
    @DisplayName("validaciones: usuario o correo repetido, combinación de perfiles, alcance faltante, auditor sin documento, perfil inactivo")
    void validaciones() {
        Navegador n = admin();
        String u = unico("dup").toLowerCase();
        assertThat(n.post("/api/usuarios", datos(u, List.of(2))).estado()).isEqualTo(201);
        assertThat(n.post("/api/usuarios", datos(u, List.of(2))).mensaje()).isEqualTo("Ya existe un usuario con ese nombre de usuario.");
        Map<String, Object> mismoCorreo = datos(unico("otro").toLowerCase(), List.of(2));
        mismoCorreo.put("correo", u + "@example.com");
        assertThat(n.post("/api/usuarios", mismoCorreo).mensaje()).isEqualTo("Ya existe un usuario con ese correo.");
        assertThat(n.post("/api/usuarios", datos(unico("comb").toLowerCase(), List.of(5, 6))).mensaje()).contains("un solo perfil");
        assertThat(n.post("/api/usuarios", datos(unico("oplaud").toLowerCase(), List.of(9, 3))).mensaje()).contains("no se combinan");
        assertThat(n.post("/api/usuarios", datos(unico("san").toLowerCase(), List.of(6))).mensaje()).contains("necesita la fuerza");
        Map<String, Object> sinDoc = datos(unico("sindoc").toLowerCase(), List.of(3));
        sinDoc.remove("documento");
        assertThat(n.post("/api/usuarios", sinDoc).mensaje()).contains("documento es obligatorio");
        assertThat(n.post("/api/usuarios", datos(unico("cons").toLowerCase(), List.of(4))).mensaje()).isEqualTo("Hay un perfil no válido.");
        Map<String, Object> malUsuario = datos("Con Espacios", List.of(2));
        assertThat(n.post("/api/usuarios", malUsuario).estado()).isEqualTo(422);
        Map<String, Object> sanidad = datos(unico("sanok").toLowerCase(), List.of(6));
        sanidad.put("alcanceFuerza", "EJC");
        assertThat(n.post("/api/usuarios", sanidad).estado()).isEqualTo(201);
    }

    @Test
    @DisplayName("prueba 2 en dos equipos a la vez: el mismo usuario solo se crea una vez; el otro recibe 422, no 500")
    void simultaneoEnDosEquipos() throws Exception {
        Navegador a = admin();
        Navegador b = admin();
        String u = unico("doble").toLowerCase();
        CompletableFuture<Respuesta> ra = CompletableFuture.supplyAsync(() -> a.post("/api/usuarios", datos(u, List.of(3))));
        CompletableFuture<Respuesta> rb = CompletableFuture.supplyAsync(() -> b.post("/api/usuarios", datos(u, List.of(3))));
        List<Integer> estados = List.of(ra.get().estado(), rb.get().estado());
        assertThat(estados).containsExactlyInAnyOrder(201, 422);
        assertThat(jdbc.sql("SELECT count(*) FROM usuarios WHERE usuario = :u").param("u", u).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("solo el administrador: el coordinador recibe 403 y queda acceso_denegado")
    void soloAdministrador() {
        String coord = crearUsuario("coordusr", Perfil.COORDINADOR);
        Navegador n = navegador();
        n.ingresar(coord, CLAVE);
        assertThat(n.get("/api/usuarios").estado()).isEqualTo(403);
        assertThat(bitacoraDe(idDe(coord)).getFirst()).startsWith("consulta|acceso_denegado|").endsWith("HTTP 403");
    }

    @Test
    @DisplayName("inactivar y restablecer: no puede inactivarse a sí mismo; restablecer exige el cambio y envía el enlace")
    void estadoYRestablecer() {
        String adm = crearUsuario("admest", Perfil.ADMINISTRADOR);
        Navegador n = navegador();
        n.ingresar(adm, CLAVE);
        assertThat(n.put("/api/usuarios/" + idDe(adm) + "/estado", Map.of("activo", false)).mensaje()).isEqualTo("No puede inactivar su propio usuario.");
        String otro = crearUsuario("rest", Perfil.AUDITOR);
        Respuesta r = n.post("/api/usuarios/" + idDe(otro) + "/restablecer", null);
        assertThat(r.estado()).isEqualTo(200);
        assertThat(usuarios.porUsuario(otro).orElseThrow().debeCambiarClave()).isTrue();
        assertThat(ultimoCorreoA(otro + "@example.com").html()).contains("definir-clave?tkn=");
        assertThat(n.put("/api/usuarios/" + idDe(otro) + "/estado", Map.of("activo", false)).mensaje()).isEqualTo("Usuario inactivado.");
        Map<String, Object> sinAdmin = datos(adm, List.of(2));
        sinAdmin.put("correo", adm + "@example.com");
        assertThat(n.put("/api/usuarios/" + idDe(adm), sinAdmin).mensaje()).isEqualTo("No puede quitarse a sí mismo el perfil Administrador.");
    }

    @Test
    @DisplayName("olvidé mi contraseña: mismo mensaje exista o no; no cierra la sesión del usuario ni cambia su contraseña")
    void olvide() {
        String u = crearUsuario("olv", Perfil.COORDINADOR);
        Navegador suyo = navegador();
        suyo.ingresar(u, CLAVE);
        Navegador tercero = navegador();
        Respuesta existe = tercero.post("/api/auth/olvide", Map.of("valor", u));
        Respuesta noExiste = tercero.post("/api/auth/olvide", Map.of("valor", "nadie@example.com"));
        assertThat(existe.mensaje()).isEqualTo(noExiste.mensaje());
        // El correo sale en otro hilo (duda 17.16: la respuesta no espera la búsqueda ni el SMTP)
        esperarCorreoA(u + "@example.com", "Recuperación de contraseña");
        esperar(() -> bitacoraDe(idDe(u)).stream().anyMatch(f -> f.startsWith("actualizacion|clave_olvido|Enlace de recuperación")), "bitácora del olvido");
        assertThat(suyo.get("/api/sesion").estado()).isEqualTo(200);
        assertThat(usuarios.porUsuario(u).orElseThrow().debeCambiarClave()).isFalse();
    }

    @Test
    @DisplayName("olvidé mi contraseña dos veces seguidas: un solo enlace cada 5 minutos por usuario (duda 17.16)")
    void olvideConTope() {
        String u = crearUsuario("olvtope", Perfil.AUDITOR);
        String correo = u + "@example.com";
        Navegador tercero = navegador();
        tercero.post("/api/auth/olvide", Map.of("valor", u));
        esperarCorreoA(correo, "Recuperación de contraseña");
        String tokenAntes = usuarios.porUsuario(u).orElseThrow().tokenHash();
        tercero.post("/api/auth/olvide", Map.of("valor", correo));
        esperar(() -> bitacoraDe(idDe(u)).stream().anyMatch(f -> f.contains("no se envía otro enlace")), "segundo pedido en la bitácora");
        assertThat(correos().stream().filter(m -> m.para().contains(correo)).count()).isEqualTo(1);
        assertThat(usuarios.porUsuario(u).orElseThrow().tokenHash()).as("el primer enlace sigue sirviendo").isEqualTo(tokenAntes);

        reloj.adelantar(Duration.ofMinutes(ServicioClaves.MINUTOS_ENTRE_ENLACES).plusSeconds(1));
        tercero.post("/api/auth/olvide", Map.of("valor", u));
        esperar(() -> correos().stream().filter(m -> m.para().contains(correo)).count() == 2, "segundo enlace pasados 5 minutos");
    }

    @Test
    @DisplayName("ninguna contraseña queda en la bitácora: ni al definirla por enlace ni en un ingreso fallido (revisión de seguridad S1, P1)")
    void bitacoraSinContrasenas() {
        Navegador n = admin();
        String u = unico("secreto").toLowerCase();
        n.post("/api/usuarios", datos(u, List.of(3)));
        String tkn = token(u + "@example.com");
        Navegador nuevo = navegador();
        assertThat(nuevo.post("/api/auth/clave", Map.of("tkn", tkn, "clave", "Secreta.Unica.2026", "confirmacion", "Secreta.Unica.2026")).estado())
                .isEqualTo(200);
        nuevo.post("/api/auth/ingreso", Map.of("usuario", u, "clave", "Otra.Secreta.2026"));
        assertThat(jdbc.sql("""
                SELECT count(*) FROM log_transacciones
                WHERE descripcion LIKE '%Secreta.Unica.2026%' OR descripcion LIKE '%Otra.Secreta.2026%'
                   OR descripcion LIKE '%clave=%' OR descripcion LIKE '%' || :tkn || '%'""").param("tkn", tkn).query(Long.class).single())
                .isZero();
    }

    @Test
    @DisplayName("correo que no sale: crear usuario responde 201 con correoOk=false y la bitácora lo dice; restablecer responde 502")
    void correoFallido() {
        Navegador n = admin();
        String u = unico("rechazo").toLowerCase();
        Map<String, Object> d = datos(u, List.of(2));
        d.put("correo", u + co.com.agsamericas.digsa.plataforma.CorreoArchivo.DOMINIO_RECHAZO);
        Respuesta r = n.post("/api/usuarios", d);
        assertThat(r.estado()).isEqualTo(201);
        assertThat(r.json().path("correoOk").asBoolean()).isFalse();
        assertThat(r.mensaje()).contains("NO se pudo enviar el correo");
        assertThat(jdbc.sql("SELECT descripcion FROM log_transacciones WHERE tipo = 'usuario_creado' AND descripcion LIKE :d")
                .param("d", "%Usuario " + u + " creado%").query(String.class).single()).contains("NO salió");
        Respuesta rest = n.post("/api/usuarios/" + idDe(u) + "/restablecer", null);
        assertThat(rest.estado()).isEqualTo(502);
        assertThat(rest.mensaje()).startsWith("No se pudo enviar el correo");
    }

    @Test
    @DisplayName("perfiles disponibles: los activos más Analista, sin Consulta; ESM sin establecimiento y OPL regional sin regional: 422")
    void perfilesYAlcances() {
        Navegador n = admin();
        Respuesta lista = n.get("/api/usuarios");
        String perfiles = lista.json().path("perfiles").toString();
        assertThat(perfiles).contains("Administrador", "Coordinador", "Auditor", "DIGSA", "Sanidad", "ESM", "OPL nacional", "OPL regional", "Analista")
                .doesNotContain("Consulta");
        assertThat(n.post("/api/usuarios", datos(unico("esm").toLowerCase(), List.of(7))).mensaje()).contains("necesita el establecimiento");
        assertThat(n.post("/api/usuarios", datos(unico("oplr").toLowerCase(), List.of(9))).mensaje()).contains("necesita la regional");

        // Con los maestros cargados (S2): ESM con su establecimiento y OPL regional con su regional
        long punto = lista.json().path("puntos").get(0).path("id").asLong();
        long regional = lista.json().path("regionales").get(0).path("id").asLong();
        Map<String, Object> esm = datos(unico("esm").toLowerCase(), List.of(7));
        esm.put("alcancePunto", punto);
        assertThat(n.post("/api/usuarios", esm).estado()).isEqualTo(201);
        Map<String, Object> oplr = datos(unico("oplr").toLowerCase(), List.of(9));
        oplr.put("alcanceRegional", regional);
        Respuesta r = n.post("/api/usuarios", oplr);
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        assertThat(usuarios.porId(r.json().path("id").asLong()).orElseThrow().alcanceRegional()).isEqualTo(regional);
    }

    @Test
    @DisplayName("inactivar por el endpoint cierra la sesión abierta del otro y anula su enlace pendiente")
    void inactivarPorEndpoint() {
        Navegador n = admin();
        String otro = crearUsuario("cerrar", Perfil.COORDINADOR);
        Navegador suyo = navegador();
        suyo.ingresar(otro, CLAVE);
        n.post("/api/usuarios/" + idDe(otro) + "/restablecer", null);
        assertThat(usuarios.porUsuario(otro).orElseThrow().tokenHash()).isNotNull();
        n.put("/api/usuarios/" + idDe(otro) + "/estado", Map.of("activo", false));
        assertThat(suyo.get("/api/sesion").estado()).isEqualTo(401);
        assertThat(usuarios.porUsuario(otro).orElseThrow().tokenHash()).isNull();
    }

    @Test
    @DisplayName("la bitácora no guarda correos, nombres, documentos ni contraseñas (Ley 1581)")
    void bitacoraSinDatosPersonales() {
        Navegador n = admin();
        String u = unico("ley").toLowerCase();
        n.post("/api/usuarios", datos(u, List.of(3)));
        assertThat(jdbc.sql("SELECT count(*) FROM log_transacciones WHERE descripcion LIKE '%@%' OR descripcion LIKE '%12345678%'")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT descripcion FROM log_transacciones WHERE tipo = 'usuario_creado' ORDER BY fecha DESC LIMIT 1")
                .query(String.class).single()).startsWith("usuarios.crear").contains("Usuario " + u + " creado");
    }
}
