package co.com.agsamericas.digsa.plataforma;

import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Bitacora.Anotacion;
import co.com.agsamericas.digsa.reglas.Bitacora.Operacion;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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

    public RegistroBitacora(JdbcClient jdbc, Clock reloj, PlatformTransactionManager transacciones) {
        this.jdbc = jdbc;
        this.reloj = reloj;
        this.propia = new TransactionTemplate(transacciones);
        this.propia.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Evento de un servicio. En una petición a /api se suma a su fila; si no, se escribe ya. */
    @SuppressWarnings("unchecked")
    public void registrar(String tipo, String descripcion, Long usuarioId, String usuario) {
        HttpServletRequest req = peticionActual();
        if (req != null && req.getAttribute(ATR_ANOTACIONES) instanceof List<?> lista) {
            ((List<Anotacion>) lista).add(new Anotacion(tipo, descripcion));
            if (usuarioId != null && req.getAttribute(ATR_USUARIO_ID) == null) {
                req.setAttribute(ATR_USUARIO_ID, usuarioId);
                req.setAttribute(ATR_USUARIO, usuario);
            }
            return;
        }
        escribir(Bitacora.operacionDeTipo(tipo), tipo, req == null ? "cli" : "sesion", descripcion, usuarioId, usuario,
                req == null ? null : req.getRemoteAddr());
    }

    /** Escribe una fila. Nunca lanza: si falla, el contenido queda en el registro de errores del servidor. */
    public void escribir(Operacion operacion, String tipo, String modulo, String descripcion, Long usuarioId, String usuario, String ip) {
        String desc = Bitacora.cortar(Bitacora.sinCorreos(descripcion == null ? "" : descripcion), Bitacora.MAX_DETALLE);
        try {
            propia.executeWithoutResult(t -> jdbc.sql("""
                    INSERT INTO log_transacciones (fecha, operacion, tipo, modulo, usuario_id, usuario, descripcion, ip)
                    VALUES (:fecha, :operacion, :tipo, :modulo, :usuarioId, :usuario, :descripcion, :ip)""")
                    .param("fecha", OffsetDateTime.now(reloj))
                    .param("operacion", operacion.valor())
                    .param("tipo", Bitacora.cortar(tipo, 50))
                    .param("modulo", modulo == null ? null : Bitacora.cortar(modulo, 40))
                    .param("usuarioId", usuarioId)
                    .param("usuario", usuario == null ? null : Bitacora.cortar(usuario, 60))
                    .param("descripcion", desc)
                    .param("ip", ip == null ? null : Bitacora.cortar(ip, 45))
                    .update());
        } catch (RuntimeException e) {
            LOG.error("[bitacora] No se pudo registrar {} {} {} usuario_id={} '{}'", operacion.valor(), tipo, modulo, usuarioId, desc, e);
        }
    }

    public static List<Anotacion> nuevaListaDeAnotaciones() {
        return new ArrayList<>();
    }

    private static HttpServletRequest peticionActual() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes s ? s.getRequest() : null;
    }
}
