package co.com.agsamericas.digsa.plataforma;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** Módulo Correos, fase C4: el perfil «mailpit» manda a localhost:1025 sin usuario ni STARTTLS y solo arranca en local. */
class PerfilMailpitTest {

    @Test
    void elPerfilApuntaAMailpitLocalSinCifradoNiUsuario() throws IOException {
        List<PropertySource<?>> fuentes = new YamlPropertySourceLoader().load("mailpit", new ClassPathResource("application-mailpit.yml"));
        PropertySource<?> p = fuentes.get(0);
        assertThat(String.valueOf(p.getProperty("spring.mail.host"))).isEqualTo("${DIGSA_MAILPIT_HOST:localhost}");
        assertThat(String.valueOf(p.getProperty("spring.mail.port"))).isEqualTo("${DIGSA_MAILPIT_PUERTO:1025}");
        assertThat(String.valueOf(p.getProperty("spring.mail.properties.mail.smtp.auth"))).isEqualTo("false");
        assertThat(String.valueOf(p.getProperty("spring.mail.properties.mail.smtp.starttls.enable"))).isEqualTo("false");
        assertThat(String.valueOf(p.getProperty("digsa.correo.modo"))).isEqualTo("smtp");
        assertThat(String.valueOf(p.getProperty("digsa.correo.remitente"))).endsWith("@example.com");
    }

    @Test
    void soloEnLocalYConElSmtpEnEsteEquipo() {
        assertThat(ValidacionArranque.problemaMailpit(false, false, "smtp.example.com")).isNull();
        assertThat(ValidacionArranque.problemaMailpit(true, true, "localhost")).isNull();
        assertThat(ValidacionArranque.problemaMailpit(true, true, "127.0.0.1")).isNull();
        assertThat(ValidacionArranque.problemaMailpit(true, false, "localhost")).contains("digsa.entorno=local");
        assertThat(ValidacionArranque.problemaMailpit(true, true, "smtp.example.com")).contains("localhost o 127.0.0.1");
    }
}
