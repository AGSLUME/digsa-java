package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Captcha Cloudflare Turnstile en el ingreso y en "olvidé mi contraseña". Sin clave secreta solo se da por superado en el entorno
 * local; en cualquier otro entorno falla cerrado (nadie ingresa hasta configurarlo).
 */
@Component
public class Captcha {

    private static final Logger LOG = LoggerFactory.getLogger(Captcha.class);
    private static final String URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private final ConfiguracionDigsa cfg;
    private final RestClient http;

    public Captcha(ConfiguracionDigsa cfg) {
        this.cfg = cfg;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(10));
        f.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(f).build();
    }

    public String claveSitio() {
        return cfg.captcha() == null ? null : cfg.captcha().claveSitio();
    }

    /** ¿Está desactivado? Solo en local y sin claves. */
    public boolean desactivado() {
        return cfg.esLocal() && secreta() == null;
    }

    public boolean superado(String respuesta, String ip) {
        if (secreta() == null) {
            return cfg.esLocal();
        }
        if (respuesta == null || respuesta.isBlank()) {
            return false;
        }
        try {
            LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("secret", secreta());
            form.add("response", respuesta);
            if (ip != null) {
                form.add("remoteip", ip);
            }
            Map<?, ?> r = http.post().uri(URL).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);
            boolean ok = r != null && Boolean.TRUE.equals(r.get("success"));
            if (!ok && r != null && String.valueOf(r.get("error-codes")).matches(".*(invalid-input-secret|missing-input-secret|invalid-sitekey).*")) {
                // Error de configuración, no del usuario: sin esto nadie ingresa y nada explica por qué
                LOG.error("Captcha mal configurado (Cloudflare Turnstile): {}", r.get("error-codes"));
            }
            return ok;
        } catch (RuntimeException e) {
            LOG.warn("No se pudo verificar el captcha: {}", e.getMessage());
            return false;
        }
    }

    private String secreta() {
        String s = cfg.captcha() == null ? null : cfg.captcha().claveSecreta();
        return s == null || s.isBlank() ? null : s;
    }
}
