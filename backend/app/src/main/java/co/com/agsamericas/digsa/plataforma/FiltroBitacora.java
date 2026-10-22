package co.com.agsamericas.digsa.plataforma;

import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Bitacora.Anotacion;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import co.com.agsamericas.digsa.reglas.Bitacora.Peticion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

/**
 * Una fila de bitácora por petición a /api, escrita al terminar con el código HTTP final (equivalente de Auth::requerirAjax +
 * Bitacora::cerrarPeticion del PHP). Va antes de Spring Security para ver también los 403 y 419 que este produce.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 60) // dentro de Spring Session (+50) y antes de Spring Security (-100)
public class FiltroBitacora extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(FiltroBitacora.class);
    private static final int MAX_CUERPO = 64 * 1024;

    private final RegistroBitacora registro;
    private final ObjectMapper json;

    public FiltroBitacora(RegistroBitacora registro, ObjectMapper json) {
        this.registro = registro;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingRequestWrapper envuelta = new ContentCachingRequestWrapper(request, MAX_CUERPO);
        List<Anotacion> anotaciones = RegistroBitacora.nuevaListaDeAnotaciones();
        envuelta.setAttribute(RegistroBitacora.ATR_ANOTACIONES, anotaciones);
        int codigo = 500;
        try {
            chain.doFilter(envuelta, response);
            codigo = response.getStatus();
        } finally {
            try {
                cerrar(envuelta, codigo, anotaciones);
            } catch (RuntimeException e) {
                LOG.error("[bitacora] No se pudo armar la fila de {} {}", request.getMethod(), request.getRequestURI(), e);
            }
        }
    }

    private void cerrar(ContentCachingRequestWrapper req, int codigo, List<Anotacion> anotaciones) {
        Accion accion = (Accion) req.getAttribute(InterceptorAccion.ATR_ACCION);
        String[] partes = req.getRequestURI().substring(req.getContextPath().length() + "/api/".length()).split("/");
        String modulo = accion != null ? accion.modulo() : partes.length > 0 ? partes[0] : "api";
        String nombreAccion = accion != null ? accion.accion() : String.join(".", java.util.Arrays.copyOfRange(partes, Math.min(1, partes.length), partes.length));
        Declarada declarada = accion != null ? accion.operacion() : null;
        Peticion p = new Peticion(modulo, req.getMethod(), nombreAccion.isEmpty() ? req.getMethod().toLowerCase() : nombreAccion, declarada,
                datos(req), anotaciones);
        Long usuarioId = (Long) req.getAttribute(RegistroBitacora.ATR_USUARIO_ID);
        String usuario = (String) req.getAttribute(RegistroBitacora.ATR_USUARIO);
        if (usuarioId == null) {
            // Rechazos anteriores a la revalidación (p. ej. CSRF 419): el usuario sale del contexto guardado en la sesión
            Identificable i = deLaSesion(req);
            if (i != null) {
                usuarioId = i.id();
                usuario = i.usuario();
            }
        }
        Long id = usuarioId;
        String quien = usuario;
        Bitacora.fila(p, codigo, false).ifPresent(f -> registro.escribir(f.operacion(), f.tipo(), modulo, f.descripcion(), id, quien,
                req.getRemoteAddr()));
    }

    private static Identificable deLaSesion(HttpServletRequest req) {
        var s = req.getSession(false);
        Object ctx = s == null ? null : s.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (ctx instanceof SecurityContext c && c.getAuthentication() != null && c.getAuthentication().getPrincipal() instanceof Identificable i) {
            return i;
        }
        return null;
    }

    /** Variables de ruta, parámetros y los campos simples del cuerpo JSON, en ese orden. La lista blanca la aplica Bitacora. */
    private Map<String, Object> datos(ContentCachingRequestWrapper req) {
        Map<String, Object> datos = new LinkedHashMap<>();
        if (req.getAttribute(InterceptorAccion.ATR_VARIABLES) instanceof Map<?, ?> vars) {
            vars.forEach((k, v) -> datos.put(String.valueOf(k), v));
        }
        req.getParameterMap().forEach((k, v) -> {
            if (v.length == 1) {
                datos.putIfAbsent(k, v[0]);
            }
        });
        String tipo = req.getContentType();
        byte[] cuerpo = req.getContentAsByteArray();
        // El cuerpo del ingreso y de las contraseñas nunca se lee (secretos); los demás pasan por la lista blanca y la negra
        boolean rutaDeSecretos = req.getRequestURI().startsWith(req.getContextPath() + "/api/auth/")
                || req.getRequestURI().startsWith(req.getContextPath() + "/api/cuenta/"); // «Cambiar mi contraseña»
        if (!rutaDeSecretos && tipo != null && tipo.toLowerCase().startsWith("application/json") && cuerpo.length > 0) {
            try {
                JsonNode raiz = json.readTree(cuerpo);
                if (raiz != null && raiz.isObject()) {
                    raiz.properties().forEach(e -> {
                        JsonNode v = e.getValue();
                        if (v.isNumber()) {
                            datos.putIfAbsent(e.getKey(), v.numberValue());
                        } else if (v.isTextual()) {
                            datos.putIfAbsent(e.getKey(), v.textValue());
                        } else if (v.isBoolean()) {
                            datos.putIfAbsent(e.getKey(), v.booleanValue());
                        }
                    });
                }
            } catch (IOException e) {
                // cuerpo que no es JSON válido: la petición ya respondió 400; la bitácora no lo necesita
            }
        }
        return datos;
    }
}
