package co.com.agsamericas.digsa.plataforma;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/** Elige el adaptador de correo según digsa.correo.modo. El correo a archivo fuera de local no arranca: falla cerrado. */
@Configuration
public class ConfiguracionCorreo {

    @Bean
    public Correo correo(ConfiguracionDigsa cfg, ObjectProvider<JavaMailSender> mailSender, Clock reloj) {
        ConfiguracionDigsa.Correo c = cfg.correo();
        String modo = c == null || c.modo() == null ? "smtp" : c.modo();
        if ("archivo".equalsIgnoreCase(modo)) {
            if (!cfg.esLocal()) {
                throw new IllegalStateException("digsa.correo.modo=archivo solo se permite con digsa.entorno=local: en este entorno los correos no saldrían.");
            }
            return new CorreoArchivo(Path.of(c.carpeta() == null ? "almacen/correos" : c.carpeta()), reloj);
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException("Falta la configuración SMTP (spring.mail.host) para enviar correos.");
        }
        return new CorreoSmtp(sender, c == null ? null : c.remitente(), c == null ? "Suite DIGSA" : c.nombreRemitente());
    }
}
