package co.com.agsamericas.digsa.plataforma;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.identidad.Captcha;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Configuración peligrosa: no arranca (revisiones de seguridad y fallas silenciosas de S1). Captcha que falla cerrado. */
class ValidacionArranqueYCaptchaTest {

    private static ConfiguracionDigsa cfg(String url, String entorno, String modo, String remitente, String dominios, String secreta) {
        // En producción, con un proveedor de SMS (sin él no arranca: ver sms())
        return cfg(url, entorno, modo, remitente, dominios, secreta, "local".equals(entorno) ? "archivo" : "proveedor");
    }

    private static ConfiguracionDigsa cfg(String url, String entorno, String modo, String remitente, String dominios, String secreta, String sms) {
        return new ConfiguracionDigsa(url, entorno, "10", 30, new ConfiguracionDigsa.Correo(modo, remitente, "Suite DIGSA", null),
                new ConfiguracionDigsa.Captcha("sitio", secreta), new ConfiguracionDigsa.Reportes(null, dominios), null, new ConfiguracionDigsa.Sms(sms));
    }

    @Test
    @DisplayName("producción bien configurada: sin problemas")
    void produccionBien() {
        assertThat(ValidacionArranque.problemas(cfg("https://digsa.example.com/", "produccion", "smtp", "no-responder@example.com",
                "sanidad.mil.co", "s"), "smtp.example.com")).isEmpty();
    }

    @Test
    @DisplayName("producción sin https, sin SMTP, sin remitente y sin dominios de reportes: no arranca y dice por qué")
    void produccionMal() {
        assertThat(ValidacionArranque.problemas(cfg("http://localhost:8080/", "produccion", "smtp", "", "", "s"), ""))
                .hasSize(4)
                .anyMatch(p -> p.contains("DIGSA_URL"))
                .anyMatch(p -> p.contains("DIGSA_SMTP_HOST"))
                .anyMatch(p -> p.contains("DIGSA_CORREO_REMITENTE"))
                .anyMatch(p -> p.contains("DIGSA_DOMINIOS_REPORTES"));
    }

    @Test
    @DisplayName("modo local con una URL que no es localhost: no arranca (las ayudas de desarrollo no se exponen)")
    void localExpuesto() {
        assertThat(ValidacionArranque.problemas(cfg("https://digsa.example.com/", "local", "archivo", null, null, null), null))
                .singleElement().asString().contains("localhost");
        assertThat(ValidacionArranque.problemas(cfg("http://127.0.0.1:8080/", "local", "archivo", null, null, null), null)).isEmpty();
    }

    @Test
    @DisplayName("SMS: a archivo solo en local; sin proveedor no arranca en producción; en pruebas se admite «ninguno»")
    void sms() {
        assertThat(ValidacionArranque.problemas(cfg("https://digsa.example.com/", "produccion", "smtp", "no-responder@example.com", "example.com", "s",
                "ninguno"), "smtp.example.com")).singleElement().asString().contains("proveedor de SMS");
        assertThat(ValidacionArranque.problemas(cfg("https://digsa.example.com/", "produccion", "smtp", "no-responder@example.com", "example.com", "s",
                null), "smtp.example.com")).singleElement().asString().contains("DIGSA_SMS_MODO");
        assertThat(ValidacionArranque.problemas(cfg("https://digsa.example.com/", "pruebas", "smtp", "no-responder@example.com", "example.com", "s",
                "archivo"), "smtp.example.com")).singleElement().asString().contains("solo se permite en local");
        assertThat(ValidacionArranque.problemas(cfg("https://digsa.example.com/", "pruebas", "smtp", "no-responder@example.com", "example.com", "s",
                "ninguno"), "smtp.example.com")).isEmpty();
        ConfiguracionSms config = new ConfiguracionSms();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> config.sms(cfg("https://digsa.example.com/", "pruebas", "smtp", "a@example.com",
                "example.com", "s", "archivo"), null, java.time.Clock.systemUTC())).hasMessageContaining("solo se permite con digsa.entorno=local");
        assertThat(config.sms(cfg("http://localhost/", "local", "archivo", null, null, null, "ninguno"), null, java.time.Clock.systemUTC()))
                .isInstanceOf(SmsNinguno.class);
    }

    @Test
    @DisplayName("captcha: sin clave secreta falla cerrado fuera de local; en local se da por superado y se avisa")
    void captcha() {
        Captcha produccion = new Captcha(cfg("https://x.example.com/", "produccion", "smtp", "a@b.co", "b.co", ""));
        assertThat(produccion.superado("cualquiera", "1.2.3.4")).isFalse();
        assertThat(produccion.desactivado()).isFalse();
        Captcha local = new Captcha(cfg("http://localhost/", "local", "archivo", null, null, ""));
        assertThat(local.superado(null, null)).isTrue();
        assertThat(local.desactivado()).isTrue();
        Captcha conClave = new Captcha(cfg("https://x.example.com/", "produccion", "smtp", "a@b.co", "b.co", "secreta"));
        assertThat(conClave.superado("", "1.2.3.4")).as("respuesta vacía").isFalse();
    }
}
