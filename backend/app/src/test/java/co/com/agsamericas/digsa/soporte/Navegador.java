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

    /** Varios archivos en una sola petición multipart (campo «archivos»), más campos de texto. */
    public Respuesta multipart(String ruta, java.util.Map<String, String> campos, java.util.Map<String, byte[]> archivos) {
        return multipart(ruta, campos, archivos, "archivos");
    }

    public Respuesta multipart(String ruta, java.util.Map<String, String> campos, java.util.Map<String, byte[]> archivos, String campoArchivos) {
        return multipart(ruta, campos, archivos, campoArchivos, "application/octet-stream");
    }

    /** @param tipoArchivo Content-Type que declara cada archivo (como lo pone el navegador); null o vacío lo omite */
    public Respuesta multipart(String ruta, java.util.Map<String, String> campos, java.util.Map<String, byte[]> archivos, String campoArchivos,
                               String tipoArchivo) {
        if (xsrf() == null) {
            get("/api/auth/inicio");
        }
        String limite = "----digsa" + System.nanoTime();
        java.io.ByteArrayOutputStream cuerpo = new java.io.ByteArrayOutputStream();
        String crlf = new String(new char[] {13, 10}); // CR LF del protocolo multipart
        try {
            for (var c : campos.entrySet()) {
                cuerpo.write(("--" + limite + crlf + "Content-Disposition: form-data; name=\"" + c.getKey() + "\"" + crlf + crlf + c.getValue() + crlf)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            for (var a : archivos.entrySet()) {
                cuerpo.write(("--" + limite + crlf + "Content-Disposition: form-data; name=\"" + campoArchivos + "\"; filename=\"" + a.getKey() + "\"" + crlf
                        + (tipoArchivo == null || tipoArchivo.isEmpty() ? "" : "Content-Type: " + tipoArchivo + crlf) + crlf).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                cuerpo.write(a.getValue());
                cuerpo.write(crlf.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            cuerpo.write(("--" + limite + "--" + crlf).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta)).header("Content-Type", "multipart/form-data; boundary=" + limite)
                .POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo.toByteArray()));
        if (xsrf() != null) {
            b.header("X-XSRF-TOKEN", xsrf());
        }
        return enviar(b);
    }

    // --- Analítica (rama analitica) ---
    /** PUT con cuerpo binario (application/octet-stream), como las partes de la subida de los cargues del OPL. */
    public Respuesta putBinario(String ruta, byte[] cuerpo) {
        if (xsrf() == null) {
            get("/api/auth/inicio");
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta)).header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(cuerpo));
        if (xsrf() != null) {
            b.header("X-XSRF-TOKEN", xsrf());
        }
        return enviar(b);
    }

    /** POST con un cuerpo JSON ya serializado (lotes del servicio web del OPL). */
    public Respuesta postJson(String ruta, byte[] json) {
        if (xsrf() == null) {
            get("/api/auth/inicio");
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json));
        if (xsrf() != null) {
            b.header("X-XSRF-TOKEN", xsrf());
        }
        return enviar(b);
    }

    /** Token CSRF vigente (cookie XSRF-TOKEN), o null. */
    public String xsrf() {
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
