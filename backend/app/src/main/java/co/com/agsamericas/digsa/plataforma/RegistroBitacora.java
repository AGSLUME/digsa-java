package co.com.agsamericas.digsa.plataforma;

import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Bitacora.Anotacion;
import co.com.agsamericas.digsa.reglas.Bitacora.Operacion;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Escribe la bitácora. Durante una petición a /api los eventos de los servicios se anotan y van en la misma fila de la petición
 * ({@link FiltroBitacora}); fuera de ella (tareas, línea de comandos) se escriben de inmediato. La bitácora nunca tumba la
 * operación del usuario: si no se puede escribir, la fila queda en el registro de errores.
 */
@Service
public class RegistroBitacora {

    /** Atributos de la petición que comparten el filtro de la bitácora y el de la sesión. */
    public static final String ATR_ANOTACIONES = "digsa.bitacora.anotaciones";
    public static final String ATR_USUARIO_ID = "digsa.usuario.id";
    public static final String ATR_USUARIO = "digsa.usuario.login";

    private static final Logger LOG = LoggerFactory.getLogger(RegistroBitacora.class);

    private final JdbcClient jdbc;
    private final Clock reloj;
    /** Transacción propia: una operación que se revierte no borra el registro de que se intentó. */
    private final TransactionTemplate propia;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public RegistroBitacora(JdbcClient jdbc, Clock reloj, PlatformTransactionManager transacciones, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.json = json;
        this.jdbc = jdbc;
        this.reloj = reloj;
        this.propia = new TransactionTemplate(transacciones);
        this.propia.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Evento de un servicio. En una petición a /api se suma a su fila; si no, se escribe ya. */
    public void registrar(String tipo, String descripcion, Long usuarioId, String usuario) {
        registrar(tipo, descripcion, List.of(), usuarioId, usuario);
    }

    /**
     * Modificación con sus valores de antes y después (Anexo 5, ítem 15 b): la descripción lleva el resumen («campo: «a» → «b»») y la
     * columna {@code cambios} los valores completos. Los campos de {@code sinValor} (datos personales, Ley 1581) quedan solo como modificados.
     */
    public void registrarCambios(String tipo, String descripcion, Map<String, ?> antes, Map<String, ?> despues, Set<String> sinValor,
                                 Long usuarioId, String usuario) {
        List<Bitacora.Cambio> cambios = Bitacora.cambiosDe(antes, despues, sinValor);
        registrar(tipo, descripcion + (cambios.isEmpty() ? " (sin cambios)" : ": " + Bitacora.resumen(cambios)), cambios, usuarioId, usuario);
    }

    @SuppressWarnings("unchecked")
    private void registrar(String tipo, String descripcion, List<Bitacora.Cambio> cambios, Long usuarioId, String usuario) {
        HttpServletRequest req = peticionActual();
        if (req != null && req.getAttribute(ATR_ANOTACIONES) instanceof List<?> lista) {
            ((List<Anotacion>) lista).add(new Anotacion(tipo, descripcion, cambios));
            if (usuarioId != null && req.getAttribute(ATR_USUARIO_ID) == null) {
                req.setAttribute(ATR_USUARIO_ID, usuarioId);
                req.setAttribute(ATR_USUARIO, usuario);
            }
            return;
        }
        escribir(Bitacora.operacionDeTipo(tipo), tipo, req == null ? "cli" : "sesion", descripcion, usuarioId, usuario,
                req == null ? null : req.getRemoteAddr(), cambios);
    }

    /** Escribe una fila. Nunca lanza: si falla, el contenido queda en el registro de errores del servidor. */
    public void escribir(Operacion operacion, String tipo, String modulo, String descripcion, Long usuarioId, String usuario, String ip) {
        escribir(operacion, tipo, modulo, descripcion, usuarioId, usuario, ip, List.of());
    }

    /** Escribe una fila con los valores de antes y después (V32). Nunca lanza: si falla, el contenido queda en el registro de errores. */
    public void escribir(Operacion operacion, String tipo, String modulo, String descripcion, Long usuarioId, String usuario, String ip,
                         List<Bitacora.Cambio> cambios) {
        String desc = Bitacora.cortar(Bitacora.sinCorreos(descripcion == null ? "" : descripcion), Bitacora.MAX_DETALLE);
        try {
            String cambiosJson = cambiosJson(tipo, cambios);
            if (cambiosJson == null && cambios != null && !cambios.isEmpty()) {
                // Nunca en silencio: la fila queda, y dice que sus cambios no se pudieron guardar
                desc = Bitacora.cortar(desc, Bitacora.MAX_DETALLE - 40) + " · (cambios no guardados por tamaño)";
            }
            String descripcionFinal = desc;
            propia.executeWithoutResult(t -> jdbc.sql("""
                    INSERT INTO log_transacciones (fecha, operacion, tipo, modulo, usuario_id, usuario, descripcion, ip, cambios)
                    VALUES (:fecha, :operacion, :tipo, :modulo, :usuarioId, :usuario, :descripcion, :ip, CAST(:cambios AS jsonb))""")
                    .param("fecha", OffsetDateTime.now(reloj))
                    .param("operacion", operacion.valor())
                    .param("tipo", Bitacora.cortar(tipo, 50))
                    .param("modulo", modulo == null ? null : Bitacora.cortar(modulo, 40))
                    .param("usuarioId", usuarioId)
                    .param("usuario", usuario == null ? null : Bitacora.cortar(usuario, 60))
                    .param("descripcion", descripcionFinal)
                    .param("ip", ip == null ? null : Bitacora.cortar(ip, 45))
                    .param("cambios", cambiosJson)
                    .update());
        } catch (RuntimeException e) {
            LOG.error("[bitacora] No se pudo registrar {} {} {} usuario_id={} '{}'", operacion.valor(), tipo, modulo, usuarioId, desc, e);
        }
    }

    /**
     * JSON de los cambios, o null. Si no cabe en los 20.000 bytes de la columna, se acortan los valores (la fila nunca se pierde por esto) y
     * se marca con «…».
     */
    String cambiosJson(String tipo, List<Bitacora.Cambio> cambios) {
        if (cambios == null || cambios.isEmpty()) {
            return null;
        }
        try {
            String s = json.writeValueAsString(cambios);
            for (int tope = Bitacora.MAX_VALOR_CAMBIO / 2; s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES_CAMBIOS && tope >= 20; tope /= 2) {
                int t = tope;
                s = json.writeValueAsString(cambios.stream().map(c -> new Bitacora.Cambio(c.campo(), corto(c.antes(), t), corto(c.despues(), t), c.oculto()))
                        .toList());
            }
            if (s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES_CAMBIOS) {
                LOG.error("[bitacora] Cambios de {} sin espacio aun acortados: {} campos", tipo, cambios.size());
                return null;
            }
            return s;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            LOG.error("[bitacora] No se pudieron serializar los cambios de {} ({} campos)", tipo, cambios.size(), e);
            return null;
        }
    }

    /**
     * Bytes del JSON compacto. El CHECK de V32 mide el texto de jsonb, que agrega un espacio tras cada «:» y «,» (unos 8 bytes por
     * cambio): con este margen caben más de 200 cambios.
     */
    static final int MAX_BYTES_CAMBIOS = 18_000;

    private static String corto(String s, int max) {
        return s == null || s.codePointCount(0, s.length()) <= max ? s : Bitacora.cortar(s, max - 1) + "…";
    }

    public static List<Anotacion> nuevaListaDeAnotaciones() {
        return new ArrayList<>();
    }

    private static HttpServletRequest peticionActual() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes s ? s.getRequest() : null;
    }
}
