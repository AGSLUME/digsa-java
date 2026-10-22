package co.com.agsamericas.digsa.plataforma;

import co.com.agsamericas.digsa.reglas.Alertas;
import co.com.agsamericas.digsa.reglas.Bitacora;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Envía lo que hay en la bandeja de salida (correos y SMS) con la menor latencia posible (GMI P75: alertas «en tiempo real o cuasi
 * real»):
 * <ol>
 * <li><b>Inmediato:</b> al confirmarse la transacción que encoló ({@link SalidaEncolada}, AFTER_COMMIT) se despierta un hilo de un
 * ejecutor propio, pequeño y acotado, que envía en el acto. Los despertares se agrupan: una ráfaga de avisos no llena la cola.</li>
 * <li><b>Respaldo:</b> cada {@code digsa.salida.intervalo-ms} (15 s) toma lo vencido (reintentos, lo que encoló otra réplica o un
 * despertar que se perdió) y recupera los envíos colgados en «enviando» más de 2 minutos (réplica caída a mitad de un envío).</li>
 * </ol>
 * Cada fila se toma con {@code FOR UPDATE SKIP LOCKED} en una sentencia corta que la marca «enviando» y suma el intento: dos
 * despachadores (hilos o réplicas) nunca toman la misma. El envío ocurre FUERA de toda transacción (el SMTP no retiene una conexión
 * ni un candado) y el resultado se guarda solo si la fila sigue siendo de este intento. Reintentos a 1, 5, 30 min y 2 h; al quinto
 * intento fallido queda «fallido», con {@code correo_fallido} en la bitácora y, si era el aviso de una alerta crítica,
 * {@link CorreoAgotado}.
 */
@Component
public class DespachadorSalida {

    private static final Logger LOG = LoggerFactory.getLogger(DespachadorSalida.class);
    /** Un envío en «enviando» más de esto se da por colgado (el SMTP corta a los 30 s de lectura y 60 s de escritura). */
    static final Duration COLGADO = Duration.ofMinutes(2);
    /** Filas por toma: pocas, para que dos réplicas se repartan una ráfaga. */
    static final int LOTE = 10;
    /** Vueltas máximas de un vaciado (10 × 50 = 500 envíos); lo que quede lo toma el respaldo. */
    private static final int MAX_VUELTAS = 50;

    /** Resultado de una vuelta: cuántos se tomaron y cuántos salieron. */
    public record Vuelta(int tomados, int enviados) {
    }

    private final JdbcClient jdbc;
    private final Correo correo;
    private final Sms sms;
    private final Almacen almacen;
    private final Clock reloj;
    private final RegistroBitacora bitacora;
    private final ApplicationEventPublisher eventos;
    private final ObjectMapper json;
    /** Ejecutor propio (no el de la aplicación): 2 hilos y una cola corta; si se llena, el respaldo cubre lo que no entró. */
    private final ThreadPoolExecutor hilos;
    private final AtomicBoolean despertarPendiente = new AtomicBoolean();

    public DespachadorSalida(JdbcClient jdbc, Correo correo, Sms sms, Almacen almacen, Clock reloj, RegistroBitacora bitacora,
                             ApplicationEventPublisher eventos, ObjectMapper json, @Value("${digsa.salida.hilos:2}") int hilos) {
        this.jdbc = jdbc;
        this.correo = correo;
        this.sms = sms;
        this.almacen = almacen;
        this.reloj = reloj;
        this.bitacora = bitacora;
        this.eventos = eventos;
        this.json = json;
        int n = Math.max(1, Math.min(hilos, 4));
        AtomicInteger secuencia = new AtomicInteger();
        this.hilos = new ThreadPoolExecutor(n, n, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), r -> {
            Thread t = new Thread(r, "digsa-salida-" + secuencia.incrementAndGet());
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        this.hilos.allowCoreThreadTimeOut(true);
    }

    @PreDestroy
    void detener() {
        hilos.shutdown();
    }

    /** Lo encolado ya está confirmado: a enviarlo. Sin transacción (autocommit) también se ejecuta, de inmediato. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void alEncolar(SalidaEncolada e) {
        despertar();
    }

    /** Pide un vaciado en otro hilo. Varios despertares seguidos se agrupan en uno; nunca bloquea a quien llama. */
    public void despertar() {
        if (!despertarPendiente.compareAndSet(false, true)) {
            return; // ya hay un vaciado en cola que verá también estas filas
        }
        try {
            hilos.execute(() -> {
                despertarPendiente.set(false); // lo que llegue desde aquí pide otro vaciado
                vaciar();
            });
        } catch (RejectedExecutionException e) {
            despertarPendiente.set(false);
            LOG.debug("[salida] Ejecutor lleno: el respaldo enviará lo pendiente");
        }
    }

    /** Respaldo periódico: recupera los colgados y envía lo vencido (seguro con varias réplicas). */
    @Scheduled(fixedDelayString = "${digsa.salida.intervalo-ms:15000}", initialDelayString = "${digsa.salida.inicio-ms:15000}")
    public void respaldo() {
        try {
            recuperarColgados();
            vaciar();
        } catch (RuntimeException e) {
            LOG.error("[salida] Falló la vuelta de respaldo de la bandeja de salida", e);
        }
    }

    /** Envía hasta que no quede nada vencido (o hasta {@link #MAX_VUELTAS}). @return cuántos salieron */
    public int vaciar() {
        int enviados = 0;
        for (int i = 0; i < MAX_VUELTAS; i++) {
            Vuelta v = vuelta();
            enviados += v.enviados();
            if (v.tomados() == 0) {
                break;
            }
        }
        return enviados;
    }

    /** Una toma de SMS y otra de correos. Los SMS primero: son el canal inmediato de las críticas y no esperan a un SMTP lento. */
    public Vuelta vuelta() {
        Vuelta s = sms();
        Vuelta c = correos();
        return new Vuelta(c.tomados() + s.tomados(), c.enviados() + s.enviados());
    }

    /** Los «enviando» de un despachador que murió a mitad del envío vuelven a «pendiente» (el intento ya contó). */
    public int recuperarColgados() {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        int n = 0;
        for (String tabla : List.of("correos_salida", "sms_salida")) {
            n += jdbc.sql("UPDATE " + tabla + " SET estado = 'pendiente', proximo = :ahora WHERE estado = 'enviando' AND tomado < :limite")
                    .param("ahora", ahora).param("limite", ahora.minus(COLGADO)).update();
        }
        if (n > 0) {
            LOG.warn("[salida] {} envío(s) colgados en «enviando» vuelven a la cola", n);
        }
        return n;
    }

    // ---------- Correos ----------

    private record FilaCorreo(long id, String origen, boolean critico, List<String> para, List<String> copia, String asunto, String html,
                              String adjuntos, int intentos) {
    }

    private Vuelta correos() {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        // Una sola sentencia (autocommit): toma, marca y suma el intento; SKIP LOCKED salta lo que otro despachador está tomando
        List<FilaCorreo> filas = jdbc.sql("""
                UPDATE correos_salida c SET estado = 'enviando', intentos = c.intentos + 1, tomado = :ahora
                FROM (SELECT id FROM correos_salida WHERE estado = 'pendiente' AND proximo <= :ahora ORDER BY proximo, id LIMIT :n
                      FOR UPDATE SKIP LOCKED) x
                WHERE c.id = x.id
                RETURNING c.id, c.origen, c.critico, c.para, c.copia, c.asunto, c.html, c.adjuntos::text AS adjuntos, c.intentos""")
                .param("ahora", ahora).param("n", LOTE)
                .query((rs, i) -> new FilaCorreo(rs.getLong("id"), rs.getString("origen"), rs.getBoolean("critico"), lista(rs, "para"), lista(rs, "copia"),
                        rs.getString("asunto"), rs.getString("html"), rs.getString("adjuntos"), rs.getInt("intentos")))
                .list();
        int enviados = 0;
        for (FilaCorreo f : filas) {
            if (enviarCorreo(f)) {
                enviados++;
            }
        }
        return new Vuelta(filas.size(), enviados);
    }

    private boolean enviarCorreo(FilaCorreo f) {
        Correo.Resultado r;
        try {
            r = correo.enviar(new Correo.Mensaje(f.para(), f.copia(), f.asunto(), f.html(), adjuntos(f.adjuntos())));
        } catch (RuntimeException e) {
            LOG.error("[salida] Correo {} ({})", f.id(), f.origen(), e);
            r = Correo.Resultado.falla(f.para(), "Error al armar o enviar el correo");
        }
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        if (r.ok()) {
            boolean parcial = r.rechazados() != null && !r.rechazados().isEmpty();
            int total = f.para().size() + f.copia().size();
            guardar("correos_salida", f.id(), f.intentos(), parcial ? "parcial" : "enviado", ahora, ahora,
                    parcial ? "Rechazados " + r.rechazados().size() + " de " + total + " destinatarios" : null);
            return true;
        }
        String error = Bitacora.cortar(Bitacora.sinCorreos(r.error() == null ? "error desconocido" : r.error()), 500);
        if (Alertas.agotado(f.intentos())) {
            if (guardar("correos_salida", f.id(), f.intentos(), "fallido", null, ahora, error)) {
                bitacora.escribir(Bitacora.Operacion.INSERCION, "correo_fallido", "salida", "Correo " + f.id() + " (" + f.origen() + ") sin enviar tras "
                        + f.intentos() + " intentos: " + error, null, "sistema", null);
                if (f.critico()) {
                    eventos.publishEvent(new CorreoAgotado(f.id(), f.origen()));
                }
            }
            return false;
        }
        jdbc.sql("UPDATE correos_salida SET estado = 'pendiente', proximo = :p, error = :e WHERE id = :id AND estado = 'enviando' AND intentos = :i")
                .param("p", ahora.plus(Alertas.esperaReintento(f.intentos()))).param("e", error).param("id", f.id()).param("i", f.intentos()).update();
        LOG.warn("[salida] Correo {} ({}) intento {} fallido: {}", f.id(), f.origen(), f.intentos(), error);
        return false;
    }

    private List<Correo.Adjunto> adjuntos(String jsonAdjuntos) {
        if (jsonAdjuntos == null || jsonAdjuntos.isBlank() || "[]".equals(jsonAdjuntos.strip())) {
            return List.of();
        }
        try {
            List<BandejaSalida.Adjunto> lista = json.readValue(jsonAdjuntos, new TypeReference<List<BandejaSalida.Adjunto>>() {
            });
            List<Correo.Adjunto> r = new ArrayList<>();
            for (BandejaSalida.Adjunto a : lista) {
                try (InputStream in = almacen.leer(a.ruta())) {
                    r.add(new Correo.Adjunto(a.nombre(), a.tipo(), in.readAllBytes()));
                }
            }
            return r;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("No se pudo leer un adjunto del almacén", e);
        }
    }

    // ---------- SMS ----------

    private record FilaSms(long id, long alertaId, String telefono, String texto, int intentos) {
    }

    private Vuelta sms() {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        List<FilaSms> filas = jdbc.sql("""
                UPDATE sms_salida s SET estado = 'enviando', intentos = s.intentos + 1, tomado = :ahora
                FROM (SELECT p.id, CASE WHEN u.activo THEN u.telefono END AS telefono FROM sms_salida p JOIN usuarios u ON u.id = p.usuario_id
                      WHERE p.estado = 'pendiente' AND p.proximo <= :ahora ORDER BY p.proximo, p.id LIMIT :n FOR UPDATE OF p SKIP LOCKED) x
                WHERE s.id = x.id
                RETURNING s.id, s.alerta_id, x.telefono, s.texto, s.intentos""")
                .param("ahora", ahora).param("n", LOTE)
                // El número vigente del usuario activo en E.164 (la fila solo guarda la máscara); null si ya no tiene uno válido
                .query((rs, i) -> new FilaSms(rs.getLong("id"), rs.getLong("alerta_id"), Alertas.normalizarTelefono(rs.getString("telefono")).orElse(null),
                        rs.getString("texto"), rs.getInt("intentos")))
                .list();
        int enviados = 0;
        for (FilaSms f : filas) {
            if (enviarSms(f)) {
                enviados++;
            }
        }
        return new Vuelta(filas.size(), enviados);
    }

    private boolean enviarSms(FilaSms f) {
        Sms.Resultado r;
        if (f.telefono() == null) {
            r = Sms.Resultado.omitido("Sin teléfono móvil válido");
        } else {
            try {
                r = sms.enviar(f.telefono(), f.texto());
            } catch (RuntimeException e) {
                LOG.error("[salida] SMS {} de la alerta {}", f.id(), f.alertaId(), e);
                r = Sms.Resultado.falla("Error al enviar el SMS");
            }
        }
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        if (r.ok()) {
            guardar("sms_salida", f.id(), f.intentos(), "enviado", ahora, ahora, null);
            return true;
        }
        // El error nunca lleva el número (Ley 1581)
        String error = Bitacora.cortar(r.error() == null ? "error desconocido" : r.error().replaceAll("\\+?[0-9][0-9 ]{6,}", "[telefono]"), 500);
        if (r.definitivo() || Alertas.agotado(f.intentos())) {
            String estado = r.definitivo() ? "omitido" : "fallido";
            if (guardar("sms_salida", f.id(), f.intentos(), estado, null, ahora, error) && "fallido".equals(estado)) {
                bitacora.escribir(Bitacora.Operacion.INSERCION, "sms_fallido", "salida", "SMS " + f.id() + " de la alerta " + f.alertaId() + " sin enviar tras "
                        + f.intentos() + " intentos: " + error, null, "sistema", null);
            }
            return false;
        }
        jdbc.sql("UPDATE sms_salida SET estado = 'pendiente', proximo = :p, error = :e WHERE id = :id AND estado = 'enviando' AND intentos = :i")
                .param("p", ahora.plus(Alertas.esperaReintento(f.intentos()))).param("e", error).param("id", f.id()).param("i", f.intentos()).update();
        return false;
    }

    // ---------- Comunes ----------

    /** Guarda el resultado solo si la fila sigue siendo de este intento (no la recuperó otro despachador). @return si se guardó */
    private boolean guardar(String tabla, long id, int intentos, String estado, OffsetDateTime enviado, OffsetDateTime ahora, String error) {
        int n = jdbc.sql("UPDATE " + tabla + " SET estado = :e, enviado = :env, error = :err, proximo = :ahora WHERE id = :id AND estado = 'enviando' AND intentos = :i")
                .param("e", estado).param("env", enviado).param("err", error).param("ahora", ahora).param("id", id).param("i", intentos).update();
        if (n == 0) {
            LOG.warn("[salida] {} {}: el resultado del intento {} llegó tarde (la fila ya la retomó otro despachador)", tabla, id, intentos);
        }
        return n == 1;
    }

    private static List<String> lista(ResultSet rs, String columna) throws SQLException {
        Array a = rs.getArray(columna);
        return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
    }
}
