package co.com.agsamericas.digsa.plataforma;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Falla al arrancar ante una configuración peligrosa, en lugar de funcionar mal en silencio:
 * <ul>
 *   <li>Fuera de local: la URL pública con https (cookies Secure y enlaces de los correos), SMTP y remitente configurados, y los
 *       dominios permitidos para enviar reportes.</li>
 *   <li>En local: la URL debe ser localhost. Las ayudas de desarrollo (código del segundo factor en pantalla, captcha desactivado,
 *       correo a archivo) no pueden quedar expuestas en un servidor real por una variable mal puesta.</li>
 * </ul>
 */
@Component
public class ValidacionArranque {

    private final ConfiguracionDigsa cfg;
    private final Environment entorno;

    public ValidacionArranque(ConfiguracionDigsa cfg, Environment entorno) {
        this.cfg = cfg;
        this.entorno = entorno;
    }

    @PostConstruct
    void validar() {
        List<String> problemas = problemas(cfg, entorno.getProperty("spring.mail.host"));
        if (!problemas.isEmpty()) {
            throw new IllegalStateException("Configuración no válida para arrancar: " + String.join(" ", problemas));
        }
    }

    static List<String> problemas(ConfiguracionDigsa cfg, String hostSmtp) {
        List<String> p = new ArrayList<>();
        String host = hostDe(cfg.url());
        if (cfg.esLocal()) {
            if (!"localhost".equals(host) && !"127.0.0.1".equals(host)) {
                p.add("Con digsa.entorno=local la URL debe ser localhost (es '" + cfg.url() + "').");
            }
            return p;
        }
        if (!cfg.urlSegura()) {
            p.add("Falta DIGSA_URL con https:// (cookies seguras y enlaces de los correos).");
        }
        boolean smtp = cfg.correo() == null || cfg.correo().modo() == null || "smtp".equalsIgnoreCase(cfg.correo().modo());
        if (smtp && (hostSmtp == null || hostSmtp.isBlank())) {
            p.add("Falta DIGSA_SMTP_HOST: sin correo nadie recibe el código de ingreso.");
        }
        if (smtp && (cfg.correo() == null || cfg.correo().remitente() == null || cfg.correo().remitente().isBlank())) {
            p.add("Falta DIGSA_CORREO_REMITENTE.");
        }
        if (cfg.dominiosReportes().isEmpty()) {
            p.add("Falta DIGSA_DOMINIOS_REPORTES (dominios a los que se pueden enviar reportes).");
        }
        return p;
    }

    private static String hostDe(String url) {
        try {
            return url == null ? "" : URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
