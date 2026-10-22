package co.com.agsamericas.digsa.identidad;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Entrega E1 de usuarios, roles y logs: «Cambiar mi contraseña», contraseña vencida cambiada en la aplicación, TIME OUT editable por el
 * administrador (Anexo 5, 3.2), desbloqueo, token CSRF nuevo al ingresar y una sola fila de salida cuando el administrador cierra la
 * sesión (dudas 17.16 y 18.17).
 */
class CuentaYSeguridadApiTest extends PruebaIntegracion {

    private static final String NUEVA = "Cambiada.Por.Mi.2026";

    @Autowired
    private ParametrosSeguridad parametros;

    private Navegador admin() {
        Navegador n = navegador();
        assertThat(n.ingresar(crearUsuario("admin", Perfil.ADMINISTRADOR), CLAVE).estado()).isEqualTo(200);
        return n;
    }

    private long filasDe(String tipo, String texto) {
        return jdbc.sql("SELECT count(*) FROM log_transacciones WHERE tipo = :t AND strpos(descripcion, :x) > 0").param("t", tipo).param("x", texto)
                .query(Long.class).single();
    }

    @Test
    @DisplayName("cambiar mi contraseña: exige la actual, la sesión sigue abierta y el próximo ingreso es con la nueva")
    void cambiarMiClave() {
        String u = crearUsuario("micl", Perfil.AUDITOR);
        Navegador n = navegador();
        n.ingresar(u, CLAVE);

        Respuesta errada = n.post("/api/cuenta/clave", Map.of("actual", "No.Es.La.Mia.2026", "clave", NUEVA, "confirmacion", NUEVA));
        assertThat(errada.estado()).isEqualTo(422);
        assertThat(errada.mensaje()).isEqualTo("La contraseña actual no es correcta.");
        assertThat(bitacoraDe(idDe(u))).anyMatch(f -> f.startsWith("actualizacion|clave_cambio_fallido|") && f.contains("HTTP 422"));
        assertThat(n.post("/api/cuenta/clave", Map.of("actual", CLAVE, "clave", "corta", "confirmacion", "corta")).mensaje()).contains("12 caracteres");
        assertThat(n.post("/api/cuenta/clave", Map.of("actual", CLAVE, "clave", CLAVE, "confirmacion", CLAVE)).mensaje()).contains("diferente");

        Respuesta ok = n.post("/api/cuenta/clave", Map.of("actual", CLAVE, "clave", NUEVA, "confirmacion", NUEVA));
        assertThat(ok.estado()).as(ok.texto()).isEqualTo(200);
        assertThat(n.get("/api/sesion").estado()).as("la sesión en curso no se cierra").isEqualTo(200);
        assertThat(bitacoraDe(idDe(u))).anyMatch(f -> f.startsWith("actualizacion|clave_cambio|") && f.contains("desde su sesión"));
        assertThat(bitacoraDe(idDe(u))).noneMatch(f -> f.contains(NUEVA) || f.contains(CLAVE));
        Usuario despues = usuarios.porUsuario(u).orElseThrow();
        assertThat(despues.intentos()).as("el ingreso correcto reinicia los fallos").isZero();
        assertThat(despues.claveVence()).isAfter(Instant.now().plus(Duration.ofDays(29)));

        n.post("/api/auth/salir", null);
        assertThat(navegador().ingresar(u, CLAVE).estado()).isEqualTo(401);
        assertThat(navegador().ingresar(u, NUEVA).estado()).isEqualTo(200);
    }

    @Test
    @DisplayName("cambiar mi contraseña con la actual errada 5 veces: el usuario se bloquea y la sesión se cierra")
    void cambiarMiClaveBloquea() {
        String u = crearUsuario("miblq", Perfil.AUDITOR);
        Navegador n = navegador();
        n.ingresar(u, CLAVE);
        Respuesta r = null;
        for (int i = 0; i < ServicioIngreso.INTENTOS_MAXIMOS; i++) {
            r = n.post("/api/cuenta/clave", Map.of("actual", "Errada.Numero." + i + "X", "clave", NUEVA, "confirmacion", NUEVA));
        }
        assertThat(r.estado()).isEqualTo(401);
        assertThat(r.json().path("motivo").asText()).isEqualTo("bloqueado");
        assertThat(usuarios.porUsuario(u).orElseThrow().bloqueado(Instant.now())).isTrue();
        assertThat(n.get("/api/sesion").estado()).isEqualTo(401);
        assertThat(bitacoraDe(idDe(u))).anyMatch(f -> f.startsWith("salida|sesion_cerrada|") && f.contains("contraseñas actuales erradas"));
    }

    @Test
    @DisplayName("contraseña vencida (30 días): se cambia en el ingreso, sin correo, y sigue el código")
    void claveVencidaEnElIngreso() {
        String u = crearUsuario("venc", Perfil.COORDINADOR);
        jdbc.sql("UPDATE usuarios SET clave_vence = :v WHERE id = :id").param("v", Instant.now().minusSeconds(60).atOffset(ZoneOffset.UTC))
                .param("id", idDe(u)).update();
        int correosAntes = correos().size();
        Navegador n = navegador();
        n.get("/api/auth/inicio");
        Respuesta p1 = n.post("/api/auth/ingreso", Map.of("usuario", u, "clave", CLAVE));
        assertThat(p1.json().path("paso").asText()).as(p1.texto()).isEqualTo("cambiar_clave");
        assertThat(p1.json().path("mensaje").asText()).contains("venció");
        assertThat(correos()).as("sin enlace al correo").hasSize(correosAntes);
        assertThat(n.post("/api/auth/cambiar-clave", Map.of("clave", CLAVE, "confirmacion", CLAVE)).mensaje()).contains("diferente a la actual");

        Respuesta p2 = n.post("/api/auth/cambiar-clave", Map.of("clave", NUEVA, "confirmacion", NUEVA));
        assertThat(p2.json().path("paso").asText()).as(p2.texto()).isEqualTo("codigo");
        assertThat(n.post("/api/auth/cambiar-clave", Map.of("clave", NUEVA + "x", "confirmacion", NUEVA + "x")).estado())
                .as("un segundo envío ya no tiene el paso de cambio pendiente").isEqualTo(401);
        assertThat(n.post("/api/auth/codigo", Map.of("codigo", Navegador.codigo(p2))).estado()).isEqualTo(200);
        assertThat(n.get("/api/sesion").estado()).isEqualTo(200);
        assertThat(bitacoraDe(idDe(u))).anyMatch(f -> f.startsWith("actualizacion|clave_cambio|Contraseña vencida cambiada"));
        assertThat(usuarios.porUsuario(u).orElseThrow().claveVence()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("la contraseña vence con la sesión abierta: se cierra con «clave_vencida», sin correo, y queda la salida")
    void claveVencidaConSesionAbierta() {
        String u = crearUsuario("vencses", Perfil.COORDINADOR);
        Navegador n = navegador();
        n.ingresar(u, CLAVE);
        jdbc.sql("UPDATE usuarios SET clave_vence = :v WHERE id = :id").param("v", Instant.now().minusSeconds(1).atOffset(ZoneOffset.UTC))
                .param("id", idDe(u)).update();
        int correosAntes = correos().size();
        Respuesta r = n.get("/api/sesion");
        assertThat(r.estado()).isEqualTo(401);
        assertThat(r.json().path("motivo").asText()).isEqualTo("clave_vencida");
        assertThat(correos()).hasSize(correosAntes);
        assertThat(bitacoraDe(idDe(u))).anyMatch(f -> f.startsWith("salida|sesion_cerrada|Sesión cerrada: la contraseña venció"));
        assertThat(usuarios.porUsuario(u).orElseThrow().sesionHuella()).as("libera el ingreso").isNull();
    }

    @Test
    @DisplayName("TIME OUT editable por el administrador (Anexo 5, 3.2): aplica en la siguiente petición y queda en la bitácora")
    void tiempoDeInactividadEditable() {
        Navegador adm = admin();
        int original = adm.get("/api/usuarios/seguridad").json().path("minutosSesion").asInt();
        String u = crearUsuario("timeout", Perfil.COORDINADOR);
        Navegador n = navegador();
        n.ingresar(u, CLAVE);
        try {
            assertThat(adm.put("/api/usuarios/seguridad", Map.of("minutosSesion", 0)).estado()).isEqualTo(422);
            assertThat(adm.put("/api/usuarios/seguridad", Map.of("minutosSesion", 481)).estado()).isEqualTo(422);
            assertThat(n.put("/api/usuarios/seguridad", Map.of("minutosSesion", 1)).estado()).as("solo el administrador").isEqualTo(403);
            assertThat(n.get("/api/usuarios/seguridad").estado()).isEqualTo(403);

            Respuesta r = adm.put("/api/usuarios/seguridad", Map.of("minutosSesion", 1));
            assertThat(r.estado()).as(r.texto()).isEqualTo(200);
            assertThat(adm.get("/api/usuarios/seguridad").json().path("minutosSesion").asInt()).isEqualTo(1);
            assertThat(filasDe("parametro_editado", "sesion_minutos: «" + original + "» → «1»")).isEqualTo(1);
            assertThat(n.get("/api/sesion").json().path("minutosInactividad").asInt()).isEqualTo(1);

            reloj.adelantar(Duration.ofSeconds(61));
            Respuesta vencida = n.get("/api/sesion");
            assertThat(vencida.estado()).isEqualTo(401);
            assertThat(vencida.json().path("motivo").asText()).isEqualTo("inactividad");
        } finally {
            reloj.reiniciar();
            parametros.cambiarMinutosSesion(original, new UsuarioSesion(0, "prueba", "Prueba"));
        }
    }

    @Test
    @DisplayName("el administrador desbloquea a un usuario bloqueado por intentos y este vuelve a ingresar")
    void desbloquear() {
        String u = crearUsuario("desbl", Perfil.AUDITOR);
        for (int i = 0; i < ServicioIngreso.INTENTOS_MAXIMOS; i++) {
            navegador().ingresar(u, "Errada.Clave." + i + "X");
        }
        assertThat(usuarios.porUsuario(u).orElseThrow().bloqueado(Instant.now())).isTrue();
        Navegador otro = navegador();
        otro.ingresar(crearUsuario("nodesbl", Perfil.COORDINADOR), CLAVE);
        assertThat(otro.post("/api/usuarios/" + idDe(u) + "/desbloquear", null).estado()).as("solo el administrador").isEqualTo(403);
        assertThat(usuarios.porUsuario(u).orElseThrow().bloqueado(Instant.now())).isTrue();
        Navegador adm = admin();
        assertThat(adm.post("/api/usuarios/" + idDe(u) + "/desbloquear", null).mensaje()).isEqualTo("Usuario desbloqueado.");
        assertThat(filasDe("usuario_desbloqueado", u)).isEqualTo(1);
        assertThat(navegador().ingresar(u, CLAVE).estado()).isEqualTo(200);
        assertThat(adm.post("/api/usuarios/" + idDe(u) + "/desbloquear", null).mensaje()).isEqualTo("El usuario no estaba bloqueado.");
        assertThat(navegador().post("/api/usuarios/" + idDe(u) + "/desbloquear", null).estado()).isEqualTo(401);
    }

    @Test
    @DisplayName("duda 17.16: el navegador recibe un token CSRF nuevo al iniciar sesión y al cambiar su contraseña (doble envío por cookie)")
    void csrfNuevoAlIngresar() {
        String u = crearUsuario("csrf", Perfil.AUDITOR);
        Navegador n = navegador();
        n.get("/api/auth/inicio");
        String antes = n.xsrf();
        assertThat(antes).isNotNull();
        assertThat(n.ingresar(u, CLAVE).estado()).isEqualTo(200);
        String despues = n.xsrf();
        assertThat(despues).isNotNull().isNotEqualTo(antes);
        assertThat(n.post("/api/cuenta/clave", Map.of("actual", "x", "clave", "y")).estado()).as("con el token nuevo pasa el CSRF").isEqualTo(422);
        assertThat(n.sinCsrfUnaVez().post("/api/cuenta/clave", Map.of("actual", "x", "clave", "y")).estado()).as("sin token: 419").isEqualTo(419);

        assertThat(n.post("/api/cuenta/clave", Map.of("actual", CLAVE, "clave", "Rotada.Por.Mi.2026", "confirmacion", "Rotada.Por.Mi.2026")).estado())
                .isEqualTo(200);
        assertThat(n.xsrf()).as("token nuevo al cambiar la contraseña").isNotEqualTo(despues);
        assertThat(n.get("/api/sesion").estado()).isEqualTo(200);
    }

    @Test
    @DisplayName("cambiar mi contraseña con el usuario ya bloqueado (p. ej. por ingresos fallidos desde otro lado): 401 y no cambia nada")
    void cambiarMiClaveConUsuarioBloqueado() {
        String u = crearUsuario("miyabl", Perfil.AUDITOR);
        Navegador n = navegador();
        n.ingresar(u, CLAVE);
        String hash = usuarios.porUsuario(u).orElseThrow().claveHash();
        jdbc.sql("UPDATE usuarios SET bloqueado_hasta = :h WHERE id = :id").param("h", Instant.now().plusSeconds(600).atOffset(ZoneOffset.UTC))
                .param("id", idDe(u)).update();
        Respuesta r = n.post("/api/cuenta/clave", Map.of("actual", CLAVE, "clave", NUEVA, "confirmacion", NUEVA));
        assertThat(r.estado()).isEqualTo(401);
        assertThat(r.json().path("motivo").asText()).isEqualTo("bloqueado");
        Usuario despues = usuarios.porUsuario(u).orElseThrow();
        assertThat(despues.claveHash()).as("no cambió la contraseña").isEqualTo(hash);
        assertThat(despues.bloqueado(Instant.now())).as("sigue bloqueado").isTrue();
        assertThat(n.get("/api/sesion").estado()).isEqualTo(401);
    }

    @Test
    @DisplayName("GET /api/sesion/estado no renueva la sesión: devuelve los minutos vigentes y los segundos que quedan")
    void estadoNoRenueva() {
        String u = crearUsuario("estado", Perfil.AUDITOR);
        Navegador n = navegador();
        n.ingresar(u, CLAVE);
        int minutos = n.get("/api/sesion/estado").json().path("minutos").asInt();
        reloj.adelantar(Duration.ofSeconds(minutos * 60L - 30));
        Respuesta e = n.get("/api/sesion/estado");
        assertThat(e.estado()).isEqualTo(200);
        assertThat(e.json().path("restantes").asLong()).isBetween(25L, 31L);
        reloj.adelantar(Duration.ofSeconds(31));
        Respuesta vencida = n.get("/api/sesion/estado");
        assertThat(vencida.estado()).as("consultar el estado no la mantuvo viva").isEqualTo(401);
        assertThat(vencida.json().path("motivo").asText()).isEqualTo("inactividad");
    }

    @Test
    @DisplayName("duda 18.17: el cierre de sesión por el administrador deja una sola fila de salida")
    void cierrePorAdministradorUnaFila() {
        String u = crearUsuario("unafila", Perfil.COORDINADOR);
        Navegador n = navegador();
        n.ingresar(u, CLAVE);
        admin().post("/api/usuarios/" + idDe(u) + "/cerrar-sesion", null);
        assertThat(n.get("/api/sesion").estado()).isEqualTo(401);
        assertThat(jdbc.sql("SELECT count(*) FROM log_transacciones WHERE tipo = 'sesion_cerrada' AND (usuario_id = :id OR strpos(descripcion, :u) > 0)")
                .param("id", idDe(u)).param("u", u).query(Long.class).single()).isEqualTo(1);
    }
}
