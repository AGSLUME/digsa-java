package co.com.agsamericas.digsa.identidad;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Ingreso, segundo factor, bloqueo, sesión única, inactividad y CSRF (portado de tests/e2e_local.php y tests/ui/ui_sesion.js). */
class IngresoYSesionApiTest extends PruebaIntegracion {

    @Test
    @DisplayName("ingreso correcto en dos pasos: contraseña, código al correo y sesión con perfiles y menú")
    void ingresoCorrecto() {
        String u = crearUsuario("coord", Perfil.COORDINADOR);
        Navegador n = navegador();
        n.get("/api/auth/inicio");
        Respuesta p1 = n.post("/api/auth/ingreso", Map.of("usuario", u, "clave", CLAVE));
        assertThat(p1.estado()).isEqualTo(200);
        assertThat(p1.json().path("paso").asText()).isEqualTo("codigo");
        assertThat(p1.json().path("correo").asText()).doesNotContain(u).endsWith("@example.com");
        assertThat(ultimoCorreoA(u + "@example.com").asunto()).contains("Código de verificación");
        assertThat(n.get("/api/sesion").estado()).as("sin el código todavía no hay sesión").isEqualTo(401);

        Respuesta p2 = n.post("/api/auth/codigo", Map.of("codigo", Navegador.codigo(p1)));
        assertThat(p2.estado()).isEqualTo(200);
        Respuesta s = n.get("/api/sesion");
        assertThat(s.estado()).isEqualTo(200);
        assertThat(s.json().path("perfiles").toString()).contains("Coordinador");
        assertThat(s.json().path("minutosInactividad").asInt()).isEqualTo(10);
        assertThat(s.json().path("menu").toString()).contains("agenda").doesNotContain("\"usuarios\"");
        assertThat(bitacoraDe(idDe(u))).anyMatch(f -> f.startsWith("ingreso|login|Inicio de sesión"))
                .anyMatch(f -> f.startsWith("ingreso|mfa_enviado|"));
    }

    @Test
    @DisplayName("mismo mensaje para usuario inexistente o contraseña errada; la bitácora guarda el motivo y enmascara lo tecleado")
    void mensajeGenerico() {
        String u = crearUsuario("aud", Perfil.AUDITOR);
        Navegador n = navegador();
        Respuesta mala = n.post("/api/auth/ingreso", Map.of("usuario", u, "clave", "Otra.Clave.2026"));
        Respuesta inexistente = n.post("/api/auth/ingreso", Map.of("usuario", "nadie-" + u, "clave", CLAVE));
        assertThat(mala.estado()).isEqualTo(401);
        assertThat(inexistente.estado()).isEqualTo(401);
        assertThat(mala.mensaje()).isEqualTo(inexistente.mensaje()).isEqualTo(ServicioIngreso.MENSAJE_GENERICO);
        assertThat(bitacoraDe(idDe(u))).anyMatch(f -> f.equals("ingreso|login_fallido|Ingreso fallido: contraseña incorrecta"));
        assertThat(jdbc.sql("SELECT count(*) FROM log_transacciones WHERE tipo = 'login_fallido' AND usuario = 'nad***'").query(Long.class).single())
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("bloqueo: 5 contraseñas erradas bloquean 15 minutos aunque luego sea la correcta; al vencer vuelve a entrar")
    void bloqueo() {
        String u = crearUsuario("bloq", Perfil.AUDITOR);
        Navegador n = navegador();
        for (int i = 0; i < 5; i++) {
            n.post("/api/auth/ingreso", Map.of("usuario", u, "clave", "Errada.Clave." + i));
        }
        assertThat(usuarios.porUsuario(u).orElseThrow().bloqueadoHasta()).isNotNull();
        assertThat(n.post("/api/auth/ingreso", Map.of("usuario", u, "clave", CLAVE)).estado()).isEqualTo(401);
        assertThat(bitacoraDe(idDe(u)).getFirst()).contains("Intento con el usuario bloqueado");
        reloj.adelantar(Duration.ofMinutes(16));
        assertThat(n.ingresar(u, CLAVE).estado()).isEqualTo(200);
    }

    @Test
    @DisplayName("código del segundo factor: incorrecto 422; tras 5 intentos se agota aunque luego llegue el correcto")
    void codigoIncorrecto() {
        String u = crearUsuario("mfa", Perfil.AUDITOR);
        Navegador n = navegador();
        Respuesta p1 = n.post("/api/auth/ingreso", Map.of("usuario", u, "clave", CLAVE));
        String bueno = Navegador.codigo(p1);
        String malo = bueno.equals("000000") ? "111111" : "000000";
        Respuesta r = n.post("/api/auth/codigo", Map.of("codigo", malo));
        assertThat(r.estado()).isEqualTo(422);
        assertThat(r.mensaje()).contains("no es correcto");
        for (int i = 0; i < 4; i++) {
            n.post("/api/auth/codigo", Map.of("codigo", malo));
        }
        assertThat(n.post("/api/auth/codigo", Map.of("codigo", bueno)).mensaje()).contains("Pida un código nuevo");
        assertThat(bitacoraDe(idDe(u))).anyMatch(f -> f.startsWith("ingreso|mfa_fallido|"));
    }

    @Test
    @DisplayName("sesión única (decisión 7.1): con una sesión activa el segundo ingreso se rechaza con 409; tras «Salir» ya puede entrar")
    void sesionUnica() {
        String u = crearUsuario("unica", Perfil.COORDINADOR);
        Navegador equipoA = navegador();
        Navegador equipoB = navegador();
        assertThat(equipoA.ingresar(u, CLAVE).estado()).isEqualTo(200);
        Respuesta b = equipoB.post("/api/auth/ingreso", Map.of("usuario", u, "clave", CLAVE));
        assertThat(b.estado()).isEqualTo(409);
        assertThat(b.json().path("motivo").asText()).isEqualTo("sesion_abierta");
        assertThat(b.mensaje()).contains("ya tiene una sesión abierta");
        assertThat(bitacoraDe(idDe(u)).getFirst()).isEqualTo("ingreso|login_fallido|Ingreso rechazado: el usuario ya tiene una sesión abierta");
        assertThat(equipoA.get("/api/sesion").estado()).as("la sesión abierta sigue").isEqualTo(200);

        equipoA.post("/api/auth/salir", null);
        assertThat(bitacoraDe(idDe(u)).getFirst()).startsWith("salida|logout|");
        assertThat(usuarios.porUsuario(u).orElseThrow().sesionHuella()).isNull();
        assertThat(equipoB.ingresar(u, CLAVE).estado()).isEqualTo(200);
        assertThat(equipoA.get("/api/sesion").estado()).isEqualTo(401);
    }

    @Test
    @DisplayName("sesión única: el código pedido antes de que la otra sesión abriera también se rechaza (la verificación se repite en el paso 2)")
    void sesionUnicaEnElCodigo() {
        String u = crearUsuario("unica2", Perfil.COORDINADOR);
        Navegador equipoA = navegador();
        Navegador equipoB = navegador();
        Respuesta pB = equipoB.post("/api/auth/ingreso", Map.of("usuario", u, "clave", CLAVE));
        assertThat(equipoA.ingresar(u, CLAVE).estado()).isEqualTo(200);
        Respuesta r = equipoB.post("/api/auth/codigo", Map.of("codigo", Navegador.codigo(pB)));
        assertThat(r.estado()).isEqualTo(409);
        assertThat(equipoA.get("/api/sesion").estado()).isEqualTo(200);
    }

    @Test
    @DisplayName("sesión única: una sesión abandonada deja de bloquear al cumplir el plazo de inactividad")
    void sesionAbandonadaLibera() {
        String u = crearUsuario("aband", Perfil.COORDINADOR);
        Navegador equipoA = navegador();
        equipoA.ingresar(u, CLAVE);
        reloj.adelantar(Duration.ofMinutes(9));
        assertThat(navegador().post("/api/auth/ingreso", Map.of("usuario", u, "clave", CLAVE)).estado()).isEqualTo(409);
        reloj.adelantar(Duration.ofMinutes(2));
        assertThat(navegador().ingresar(u, CLAVE).estado()).isEqualTo(200);
        Respuesta a = equipoA.get("/api/sesion");
        assertThat(a.estado()).isEqualTo(401);
        assertThat(a.json().path("motivo").asText()).isEqualTo("inactividad");
    }

    @Test
    @DisplayName("inactividad: 9 min 59 s sigue; a los 10 min se cierra con el motivo y queda sesion_expirada")
    void inactividad() {
        String u = crearUsuario("inac", Perfil.COORDINADOR);
        Navegador n = navegador();
        n.ingresar(u, CLAVE);
        reloj.adelantar(Duration.ofSeconds(590));
        assertThat(n.get("/api/sesion/mantener").estado()).as("el latido renueva el último acceso").isEqualTo(200);
        reloj.adelantar(Duration.ofSeconds(590));
        assertThat(n.get("/api/sesion").estado()).isEqualTo(200);
        reloj.adelantar(Duration.ofMinutes(10));
        Respuesta r = n.get("/api/sesion");
        assertThat(r.estado()).isEqualTo(401);
        assertThat(r.json().path("motivo").asText()).isEqualTo("inactividad");
        assertThat(r.mensaje()).isEqualTo("La sesión se cerró por inactividad. Vuelva a iniciar sesión.");
        assertThat(bitacoraDe(idDe(u)).getFirst()).startsWith("salida|sesion_expirada|");
        assertThat(bitacoraDe(idDe(u))).noneMatch(f -> f.contains("sesion.mantener"));
    }

    @Test
    @DisplayName("CSRF: una escritura sin token responde 419 y queda como acceso denegado")
    void csrf() {
        String admin = crearUsuario("admcsrf", Perfil.ADMINISTRADOR);
        Navegador n = navegador();
        n.ingresar(admin, CLAVE);
        Respuesta r = n.sinCsrfUnaVez().post("/api/usuarios", Map.of("nombre", "X"));
        assertThat(r.estado()).isEqualTo(419);
        assertThat(r.mensaje()).isEqualTo("La página expiró. Recárguela e intente de nuevo.");
        assertThat(bitacoraDe(idDe(admin)).getFirst()).startsWith("consulta|acceso_denegado|").endsWith("HTTP 419");
    }

    @Test
    @DisplayName("primer ingreso sin contraseña definida: no hay código, se envía el enlace para definirla")
    void primerIngreso() {
        String u = crearUsuario("nuevo", Perfil.AUDITOR);
        jdbc.sql("UPDATE usuarios SET clave_definida = false WHERE usuario = :u").param("u", u).update();
        Respuesta r = navegador().post("/api/auth/ingreso", Map.of("usuario", u, "clave", CLAVE));
        assertThat(r.estado()).isEqualTo(200);
        assertThat(r.json().path("paso").asText()).isEqualTo("definir_clave");
        assertThat(r.mensaje()).contains("Es su primer ingreso");
        assertThat(ultimoCorreoA(u + "@example.com").html()).contains("definir-clave?tkn=");
    }

    @Test
    @DisplayName("usuario inactivado con sesión abierta: su siguiente petición recibe 401 «Su usuario está inactivo.»")
    void inactivado() {
        String u = crearUsuario("inactivo", Perfil.COORDINADOR);
        Navegador n = navegador();
        n.ingresar(u, CLAVE);
        jdbc.sql("UPDATE usuarios SET activo = false WHERE usuario = :u").param("u", u).update();
        Respuesta r = n.get("/api/sesion");
        assertThat(r.estado()).isEqualTo(401);
        assertThat(r.mensaje()).isEqualTo("Su usuario está inactivo.");
    }

    @Test
    @DisplayName("perfil quitado: deja de servir desde la siguiente petición")
    void perfilQuitado() {
        String u = crearUsuario("quitar", Perfil.ADMINISTRADOR);
        Navegador n = navegador();
        n.ingresar(u, CLAVE);
        assertThat(n.get("/api/usuarios").estado()).isEqualTo(200);
        usuarios.reemplazarPerfiles(idDe(u), java.util.List.of(Perfil.COORDINADOR));
        assertThat(n.get("/api/usuarios").estado()).isEqualTo(403);
    }

    @Test
    @DisplayName("contraseña cambiada en otra sesión: la sesión abierta se cierra con «La contraseña cambió…»; la nueva vence a los 30 días")
    void claveCambiadaCierraOtraSesion() {
        String u = crearUsuario("cambio", Perfil.COORDINADOR);
        Navegador a = navegador();
        a.ingresar(u, CLAVE);
        reloj.adelantar(Duration.ofSeconds(2));
        String token = co.com.agsamericas.digsa.reglas.Huellas.secreto(32);
        jdbc.sql("UPDATE usuarios SET token_hash = :h, token_vence = now() + interval '1 hour' WHERE usuario = :u")
                .param("h", co.com.agsamericas.digsa.reglas.Huellas.sha256(token)).param("u", u).update();
        assertThat(navegador().post("/api/auth/clave", Map.of("tkn", token, "clave", "Cambiada.Clave.2026", "confirmacion", "Cambiada.Clave.2026"))
                .estado()).isEqualTo(200);
        Respuesta r = a.get("/api/sesion");
        assertThat(r.estado()).isEqualTo(401);
        assertThat(r.json().path("motivo").asText()).isIn("clave", "reemplazada");
        Usuario despues = usuarios.porUsuario(u).orElseThrow();
        assertThat(Duration.between(java.time.Instant.now(), despues.claveVence()).toDays()).isBetween(29L, 30L);
    }

    @Test
    @DisplayName("contraseña vencida a los 30 días: el ingreso pide cambiarla ahí mismo, sin enlace al correo (condición 12)")
    void claveVencida() {
        String u = crearUsuario("vencida", Perfil.COORDINADOR);
        reloj.adelantar(Duration.ofDays(31));
        int correosAntes = correos().size();
        Respuesta r = navegador().post("/api/auth/ingreso", Map.of("usuario", u, "clave", CLAVE));
        assertThat(r.json().path("paso").asText()).isEqualTo("cambiar_clave");
        assertThat(r.mensaje()).contains("Su contraseña venció");
        assertThat(correos()).hasSize(correosAntes);
    }

    @Test
    @DisplayName("código MFA: ya usado no sirve otra vez; reenviar deja vigente solo el último; vencido a los 15 minutos")
    void mfaBordes() {
        String u = crearUsuario("mfab", Perfil.COORDINADOR);
        Navegador n = navegador();
        Respuesta p1 = n.post("/api/auth/ingreso", Map.of("usuario", u, "clave", CLAVE));
        String primero = Navegador.codigo(p1);
        String segundo = Navegador.codigo(n.post("/api/auth/codigo/reenviar", null));
        if (!primero.equals(segundo)) {
            assertThat(n.post("/api/auth/codigo", Map.of("codigo", primero)).estado()).as("el código anterior ya no es el vigente").isEqualTo(422);
        }
        assertThat(n.post("/api/auth/codigo", Map.of("codigo", segundo)).estado()).isEqualTo(200);
        n.post("/api/auth/salir", null);

        Navegador otro = navegador();
        String c = Navegador.codigo(otro.post("/api/auth/ingreso", Map.of("usuario", u, "clave", CLAVE)));
        reloj.adelantar(Duration.ofMinutes(15).plusSeconds(1));
        Respuesta vencido = otro.post("/api/auth/codigo", Map.of("codigo", c));
        assertThat(vencido.estado()).isIn(401, 422);
        assertThat(vencido.mensaje()).containsAnyOf("venció", "Ingrese de nuevo");
    }

    @Test
    @DisplayName("tope de códigos MFA por hora: el sexto da 429 (parámetro por defecto 5)")
    void topeCodigos() {
        String u = crearUsuario("tope", Perfil.COORDINADOR);
        Navegador n = navegador();
        for (int i = 0; i < 5; i++) {
            assertThat(n.post("/api/auth/ingreso", Map.of("usuario", u, "clave", CLAVE)).estado()).isEqualTo(200);
        }
        Respuesta sexto = n.post("/api/auth/ingreso", Map.of("usuario", u, "clave", CLAVE));
        assertThat(sexto.estado()).isEqualTo(429);
        assertThat(sexto.mensaje()).contains("códigos en la última hora");
    }

    @Test
    @DisplayName("latido: solo GET (otro método 405) y devuelve los minutos; tras la inactividad la huella queda en NULL")
    void latidoYHuella() {
        String u = crearUsuario("lat", Perfil.COORDINADOR);
        Navegador n = navegador();
        n.ingresar(u, CLAVE);
        assertThat(n.get("/api/sesion/mantener").json().path("minutos").asInt()).isEqualTo(10);
        assertThat(n.post("/api/sesion/mantener", null).estado()).isEqualTo(405);
        reloj.adelantar(Duration.ofMinutes(11));
        assertThat(n.get("/api/sesion").estado()).isEqualTo(401);
        assertThat(usuarios.porUsuario(u).orElseThrow().sesionHuella()).isNull();
    }

    @Test
    @DisplayName("bloqueo: intentos no suben mientras está bloqueado; 4 fallos y un ingreso correcto reinician el contador")
    void bloqueoBordes() {
        String u = crearUsuario("bord", Perfil.AUDITOR);
        Navegador n = navegador();
        for (int i = 0; i < 4; i++) {
            n.post("/api/auth/ingreso", Map.of("usuario", u, "clave", "Errada.Clave." + i));
        }
        assertThat(usuarios.porUsuario(u).orElseThrow().intentos()).isEqualTo(4);
        n.post("/api/auth/ingreso", Map.of("usuario", u, "clave", CLAVE));
        assertThat(usuarios.porUsuario(u).orElseThrow().intentos()).as("el ingreso correcto reinicia").isZero();
        for (int i = 0; i < 5; i++) {
            n.post("/api/auth/ingreso", Map.of("usuario", u, "clave", "Errada.Clave." + i));
        }
        int alBloquear = usuarios.porUsuario(u).orElseThrow().intentos();
        n.post("/api/auth/ingreso", Map.of("usuario", u, "clave", "Otra.Errada.2026"));
        assertThat(usuarios.porUsuario(u).orElseThrow().intentos()).as("bloqueado: no suma").isEqualTo(alBloquear);
        reloj.adelantar(Duration.ofMinutes(14)); // el tiempo real entre intentos (BCrypt) también corre
        assertThat(n.post("/api/auth/ingreso", Map.of("usuario", u, "clave", CLAVE)).estado()).as("aún bloqueado").isEqualTo(401);
    }

    @Test
    @DisplayName("cabeceras de seguridad: CSP, nosniff, frame-ancestors y cookie de sesión HttpOnly SameSite=Strict")
    void cabeceras() {
        String u = crearUsuario("cab", Perfil.COORDINADOR);
        Navegador n = navegador();
        Respuesta inicio = n.get("/api/auth/inicio");
        assertThat(inicio.cabecera("Content-Security-Policy")).contains("default-src 'self'").contains("frame-ancestors 'none'");
        assertThat(inicio.cabecera("X-Content-Type-Options")).isEqualTo("nosniff");
        Respuesta p1 = n.post("/api/auth/ingreso", Map.of("usuario", u, "clave", CLAVE));
        Respuesta p2 = n.post("/api/auth/codigo", Map.of("codigo", Navegador.codigo(p1)));
        String cookie = p2.original().headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("DIGSA_")).findFirst().orElseThrow();
        assertThat(cookie).contains("HttpOnly").contains("SameSite=Strict");
    }
}
