package co.com.agsamericas.digsa.soporte;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Un navegador para las pruebas de API: guarda sus cookies (sesión y XSRF-TOKEN) y manda el token CSRF en la cabecera, como la SPA.
 * Dos navegadores = dos equipos distintos (sesión única).
 */
public class Navegador {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CODIGO_LOCAL = Pattern.compile("\\(local: (\\d{6})\\)");

    public record Respuesta(int estado, byte[] cuerpo, HttpResponse<byte[]> original) {
        public JsonNode json() {
            try {
                return JSON.readTree(cuerpo);
            } catch (IOException e) {
                throw new IllegalStateException("La respuesta no es JSON: " + texto(), e);
            }
        }

        public String texto() {
            return new String(cuerpo, java.nio.charset.StandardCharsets.UTF_8);
        }

        public String mensaje() {
            return json().path("mensaje").asText();
        }

        public String cabecera(String nombre) {
            return original.headers().firstValue(nombre).orElse(null);
        }
    }

    private final String base;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;
    private boolean sinCsrf;

    public Navegador(int puerto) {
        this.base = "http://127.0.0.1:" + puerto; // "localhost" en Windows resuelve lento
        this.http = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Para probar el rechazo: la siguiente escritura sale sin la cabecera CSRF. */
    public Navegador sinCsrfUnaVez() {
        this.sinCsrf = true;
        return this;
    }

    public Respuesta get(String ruta) {
        return enviar(HttpRequest.newBuilder(URI.create(base + ruta)).GET());
    }

    public Respuesta post(String ruta, Object cuerpo) {
        return conCuerpo("POST", ruta, cuerpo);
    }

    public Respuesta put(String ruta, Object cuerpo) {
        return conCuerpo("PUT", ruta, cuerpo);
    }

    public Respuesta delete(String ruta) {
        return conCuerpo("DELETE", ruta, null);
    }

    /** Ingreso completo en dos pasos con el código que el correo de desarrollo deja en el mensaje. */
    public Respuesta ingresar(String usuario, String clave) {
        get("/api/auth/inicio");
        Respuesta paso1 = post("/api/auth/ingreso", java.util.Map.of("usuario", usuario, "clave", clave));
        if (paso1.estado() != 200 || !"codigo".equals(paso1.json().path("paso").asText())) {
            return paso1;
        }
        return post("/api/auth/codigo", java.util.Map.of("codigo", codigo(paso1)));
    }

    public static String codigo(Respuesta paso1) {
        Matcher m = CODIGO_LOCAL.matcher(paso1.json().path("mensaje").asText());
        if (!m.find()) {
            throw new IllegalStateException("No llegó el código local: " + paso1.texto());
        }
        return m.group(1);
    }

    private Respuesta conCuerpo(String metodo, String ruta, Object cuerpo) {
        if (xsrf() == null) {
            get("/api/auth/inicio");
        }
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta))
                    .header("Content-Type", "application/json")
                    .method(metodo, HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(cuerpo == null ? java.util.Map.of() : cuerpo)));
            if (!sinCsrf && xsrf() != null) {
                b.header("X-XSRF-TOKEN", xsrf());
            }
            sinCsrf = false;
            return enviar(b);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String xsrf() {
        return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue)
                .findFirst().orElse(null);
    }

    private Respuesta enviar(HttpRequest.Builder b) {
        try {
            HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Respuesta(r.statusCode(), r.body(), r);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
