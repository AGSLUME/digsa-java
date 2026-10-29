package co.com.agsamericas.digsa.plataforma;

import co.com.agsamericas.digsa.reglas.Bitacora;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Envío directo de un correo con su registro en la bitácora de envíos (módulo Correos, fase C1; duda CO-2): los envíos cuyo
 * resultado se muestra o cambia un estado (MFA, enlaces de contraseña, código del QR del OPL, notificación previa, acta, reportes)
 * siguen saliendo en el acto, pero quedan en {@code correos_salida} con {@code sincrono = true}, igual que lo encolado.
 *
 * <p>El registro va en una transacción propia (REQUIRES_NEW): queda aunque la operación que envía se revierta. Si el registro
 * falla, el correo sale igual y la falla queda en el log de la aplicación: la bitácora nunca tumba un envío. Del html guardado se
 * ocultan los secretos ({@link Registro#secreto}) y de los adjuntos solo se guardan nombre, tipo y tamaño (nunca el contenido). Un
 * envío directo no se reintenta desde la bitácora: se reenvía desde su pantalla, que regenera el adjunto y actualiza su estado.
 */
@Service
public class EnvioRegistrado {

    private static final Logger LOG = LoggerFactory.getLogger(EnvioRegistrado.class);
    static final String SIN_REENVIO = "Envío directo: se reenvía desde su pantalla de origen.";
    static final String CODIGO_UN_USO = "Lleva un código o enlace de un solo uso: el usuario debe pedir uno nuevo.";

    /**
     * Qué se registra de un envío directo.
     *
     * @param origen        tipo de correo (mfa_codigo, acta…)
     * @param referencia    visita, acta o usuario (solo identificadores), o null
     * @param htmlGuardado  html con los secretos ocultos, o null para guardar el que sale
     * @param comoReenviar  por qué no se reintenta desde la bitácora y dónde se reenvía
     */
    public record Registro(String origen, String referencia, String htmlGuardado, String comoReenviar) {

        public static Registro de(String origen, String referencia) {
            return new Registro(origen, referencia, null, SIN_REENVIO);
        }

        /** Dónde se reenvía (p. ej. «Se reenvía desde la visita con «Enviar acta»»). */
        public Registro reenvio(String como) {
            return new Registro(origen, referencia, htmlGuardado, como);
        }

        /** Lleva un código o un enlace de un solo uso: se guarda este html (con el secreto oculto) y no se reintenta. */
        public Registro secreto(String htmlEnmascarado) {
            return new Registro(origen, referencia, htmlEnmascarado, CODIGO_UN_USO);
        }

        boolean enmascarado() {
            return htmlGuardado != null;
        }
    }

    private final JdbcClient jdbc;
    private final Correo correo;
    private final Clock reloj;
    private final ObjectMapper json;
    private final TransactionTemplate propia;

    public EnvioRegistrado(JdbcClient jdbc, Correo correo, Clock reloj, ObjectMapper json, PlatformTransactionManager transacciones) {
        this.jdbc = jdbc;
        this.correo = correo;
        this.reloj = reloj;
        this.json = json;
        this.propia = new TransactionTemplate(transacciones);
        this.propia.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Envía y registra. Mismo contrato que {@link Correo#enviar}: si el canal lanza, se registra como fallido y se relanza. */
    public Correo.Resultado enviar(Registro reg, Correo.Mensaje m) {
        Long id = registrar(reg, m);
        Correo.Resultado r;
        try {
            r = correo.enviar(m);
        } catch (RuntimeException e) {
            cerrar(id, reg, Correo.Resultado.falla(m.para(), "Error al armar o enviar el correo"), m);
            throw e;
        }
        cerrar(id, reg, r, m);
        return r;
    }

    /** ¿Es un envío real? (como {@link Correo#real}). */
    public boolean real() {
        return correo.real();
    }

    private Long registrar(Registro reg, Correo.Mensaje m) {
        try {
            OffsetDateTime ahora = OffsetDateTime.now(reloj);
            List<String> para = m.para().stream().distinct().limit(BandejaSalida.MAX_DESTINATARIOS).toList();
            String adjuntos = json.writeValueAsString(m.adjuntos().stream()
                    .map(a -> Map.of("nombre", a.nombre(), "tipo", a.tipo(), "tamano", a.contenido() == null ? 0 : a.contenido().length)).toList());
            return propia.execute(t -> jdbc.sql("""
                    INSERT INTO correos_salida (origen, referencia, para, copia, asunto, html, adjuntos, estado, intentos, proximo, creado, sincrono,
                      enmascarado, reintentable, motivo_no_reintentable)
                    VALUES (:o, :r, :para, :copia, :asunto, :html, CAST(:adj AS jsonb), 'enviando', 1, :ahora, :ahora, true, :enm, false, :motivo)
                    RETURNING id""")
                    .param("o", Bitacora.cortar(reg.origen(), 60)).param("r", reg.referencia() == null ? null : Bitacora.cortar(reg.referencia(), 120))
                    .param("para", para.toArray(String[]::new)).param("copia", m.copia().toArray(String[]::new))
                    .param("asunto", Bitacora.cortar(m.asunto(), 300)).param("html", reg.enmascarado() ? reg.htmlGuardado() : m.html() == null ? "" : m.html())
                    .param("adj", adjuntos).param("ahora", ahora).param("enm", reg.enmascarado())
                    .param("motivo", Bitacora.cortar(reg.comoReenviar() == null ? SIN_REENVIO : reg.comoReenviar(), 200))
                    .query(Long.class).single());
        } catch (RuntimeException | JsonProcessingException e) {
            LOG.error("[correos] No se pudo registrar el envío directo «{}» en la bitácora de envíos; el correo sale igual", reg.origen(), e);
            return null;
        }
    }

    private void cerrar(Long id, Registro reg, Correo.Resultado r, Correo.Mensaje m) {
        if (id == null) {
            return;
        }
        try {
            OffsetDateTime ahora = OffsetDateTime.now(reloj);
            boolean parcial = r.ok() && r.rechazados() != null && !r.rechazados().isEmpty();
            String estado = !r.ok() ? "fallido" : parcial ? "parcial" : "enviado";
            String error = !r.ok() ? Bitacora.cortar(Bitacora.sinCorreos(r.error() == null ? "error desconocido" : r.error()), 500)
                    : parcial ? "Rechazados " + r.rechazados().size() + " de " + (m.para().size() + m.copia().size()) + " destinatarios" : null;
            propia.executeWithoutResult(t -> jdbc.sql("UPDATE correos_salida SET estado = :e, enviado = :env, error = :err, proximo = :ahora WHERE id = :id AND estado = 'enviando'")
                    .param("e", estado).param("env", r.ok() ? ahora : null).param("err", error).param("ahora", ahora).param("id", id).update());
        } catch (RuntimeException e) {
            LOG.error("[correos] No se pudo guardar el resultado del envío directo {} («{}») en la bitácora de envíos", id, reg.origen(), e);
        }
    }
}
