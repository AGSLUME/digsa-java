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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Envía lo que hay en la bandeja de salida (correos y SMS) con la menor latencia posible (GMI P75: alertas «en tiempo real o cuasi
 * real»):
 * <ol>
 * <li><b>Inmediato:</b> al confirmarse la transacción que encoló ({@link SalidaEncolada}, AFTER_COMMIT) se despierta el ejecutor de
 * su canal, que envía en el acto. Los SMS tienen su propio hilo: un SMTP lento nunca retrasa el SMS de una alerta crítica. Los
 * despertares se agrupan: una ráfaga de avisos no llena la cola.</li>
 * <li><b>Respaldo:</b> cada {@code digsa.salida.intervalo-ms} (15 s) recupera los envíos colgados en «enviando» (réplica caída a mitad
 * de un envío) y despierta los dos canales para lo vencido (reintentos, lo que encoló otra réplica o un despertar que se perdió).</li>
 * </ol>
 * Cada fila se toma DE A UNA, justo antes de enviarla, con {@code FOR UPDATE SKIP LOCKED} en una sentencia corta que la marca
 * «enviando», fija {@code tomado} y suma el intento: dos despachadores (hilos o réplicas) nunca toman la misma, y una fila no envejece
 * en «enviando» esperando a que salgan otras. Los correos de las alertas críticas se toman primero. El envío ocurre FUERA de toda
 * transacción y el resultado se guarda solo si la fila sigue siendo de este intento. Un envío se da por colgado solo pasado
 * {@link #colgado()}, por encima del peor tiempo de un envío según los tiempos máximos del SMTP; si ya agotó los intentos queda
 * «fallido» como cualquier agotado (nunca pasa de 5 intentos). Reintentos a 1, 5, 30 min y 2 h; al quinto intento fallido queda
 * «fallido», con {@code correo_fallido} en la bitácora y, si era el aviso de una alerta crítica, {@link CorreoAgotado}. Los envíos
 * terminados se purgan pasados {@code bandeja_retencion_dias} (90).
 */
@Component
public class DespachadorSalida {

    private static final Logger LOG = LoggerFactory.getLogger(DespachadorSalida.class);
    /** Mínimo para dar un envío por colgado. */
    static final Duration COLGADO_MINIMO = Duration.ofMinutes(2);
    /** Envíos máximos de un vaciado; lo que quede lo toma el respaldo. */
    private static final int MAX_ENVIOS = 500;
    static final int RETENCION_DEFECTO = 90;

    private final JdbcClient jdbc;
    private final Correo correo;
    private final Sms sms;
    private final Almacen almacen;
    private final Clock reloj;
    private final RegistroBitacora bitacora;
    private final ApplicationEventPublisher eventos;
    private final ObjectMapper json;
    /** Tiempo en «enviando» a partir del cual un envío se da por colgado. */
    private final Duration colgado;
    /** Ejecutores propios (no el de la aplicación), con colas cortas: si se llenan, el respaldo cubre lo que no entró. */
    private final ThreadPoolExecutor hilosCorreo;
    private final ThreadPoolExecutor hilosSms;
    private final AtomicBoolean despertarCorreos = new AtomicBoolean();
    private final AtomicBoolean despertarSms = new AtomicBoolean();

    @Autowired
    public DespachadorSalida(JdbcClient jdbc, Correo correo, Sms sms, Almacen almacen, Clock reloj, RegistroBitacora bitacora,
                             ApplicationEventPublisher eventos, ObjectMapper json, Environment entorno, @Value("${digsa.salida.hilos:2}") int hilos) {
        this(jdbc, correo, sms, almacen, reloj, bitacora, eventos, json, hilos, colgado(entorno));
    }

    public DespachadorSalida(JdbcClient jdbc, Correo correo, Sms sms, Almacen almacen, Clock reloj, RegistroBitacora bitacora,
                             ApplicationEventPublisher eventos, ObjectMapper json, int hilos, Duration colgado) {
        this.jdbc = jdbc;
        this.correo = correo;
        this.sms = sms;
        this.almacen = almacen;
        this.reloj = reloj;
        this.bitacora = bitacora;
        this.eventos = eventos;
        this.json = json;
        this.colgado = colgado;
        this.hilosCorreo = ejecutor("digsa-salida-correo-", Math.max(1, Math.min(hilos, 4)));
        this.hilosSms = ejecutor("digsa-salida-sms-", 1);
    }

    /**
     * Colgado: {@code digsa.salida.colgado-ms} si se fijó; si no, el doble del peor envío por SMTP (conexión + lectura + escritura de
     * spring.mail: 10 + 30 + 60 s → 200 s), nunca menos de 2 minutos.
     */
    static Duration colgado(Environment e) {
        Long fijo = e.getProperty("digsa.salida.colgado-ms", Long.class);
        if (fijo != null && fijo > 0) {
            return Duration.ofMillis(fijo);
        }
        long peor = e.getProperty("spring.mail.properties.mail.smtp.connectiontimeout", Long.class, 10_000L)
                + e.getProperty("spring.mail.properties.mail.smtp.timeout", Long.class, 30_000L)
                + e.getProperty("spring.mail.properties.mail.smtp.writetimeout", Long.class, 60_000L);
        Duration d = Duration.ofMillis(2 * peor);
        return d.compareTo(COLGADO_MINIMO) < 0 ? COLGADO_MINIMO : d;
    }

    public Duration colgado() {
        return colgado;
    }

    private static ThreadPoolExecutor ejecutor(String nombre, int n) {
        AtomicInteger secuencia = new AtomicInteger();
        ThreadPoolExecutor e = new ThreadPoolExecutor(n, n, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), r -> {
            Thread t = new Thread(r, nombre + secuencia.incrementAndGet());
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        e.allowCoreThreadTimeOut(true);
        return e;
    }

    @PreDestroy
    public void detener() {
        hilosCorreo.shutdown();
        hilosSms.shutdown();
    }

    /** Lo encolado ya está confirmado: a enviarlo por su canal. Sin transacción (autocommit) también se ejecuta, de inmediato. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void alEncolar(SalidaEncolada e) {
        if ("sms".equals(e.canal())) {
            despertarSms();
        } else {
            despertarCorreos();
        }
    }

    /** Despierta los dos canales. */
    public void despertar() {
        despertarSms();
        despertarCorreos();
    }

    /** Pide un vaciado de los correos en su ejecutor. Varios despertares seguidos se agrupan en uno; nunca bloquea a quien llama. */
    public void despertarCorreos() {
        despertar(despertarCorreos, hilosCorreo, this::vaciarCorreos);
    }

    /** Pide un vaciado de los SMS en su hilo propio. */
    public void despertarSms() {
        despertar(despertarSms, hilosSms, this::vaciarSms);
    }

    private static void despertar(AtomicBoolean pendiente, ThreadPoolExecutor hilos, Runnable vaciado) {
        if (!pendiente.compareAndSet(false, true)) {
            return; // ya hay un vaciado en cola que verá también estas filas
        }
        try {
            hilos.execute(() -> {
                pendiente.set(false); // lo que llegue desde aquí pide otro vaciado
                vaciado.run();
            });
        } catch (RejectedExecutionException e) {
            pendiente.set(false);
            LOG.debug("[salida] Ejecutor lleno: el respaldo enviará lo pendiente");
        }
    }

    /** Respaldo periódico: recupera los colgados y despierta los dos canales (seguro con varias réplicas). */
    @Scheduled(fixedDelayString = "${digsa.salida.intervalo-ms:15000}", initialDelayString = "${digsa.salida.inicio-ms:15000}")
    public void respaldo() {
        try {
            recuperarColgados();
            despertar(); // cada canal en su ejecutor: un SMTP lento no frena los SMS
        } catch (RuntimeException e) {
            LOG.error("[salida] Falló la vuelta de respaldo de la bandeja de salida", e);
        }
    }

    /** Purga diaria de los envíos terminados más viejos que {@code bandeja_retencion_dias}. */
    @Scheduled(cron = "${digsa.salida.purga-cron:0 40 3 * * *}", zone = "America/Bogota")
    public void purgaProgramada() {
        try {
            purgar();
        } catch (RuntimeException e) {
            LOG.error("[salida] Falló la purga de la bandeja de salida", e);
        }
    }

    /**
     * Borra los correos (enviados, parciales o fallidos) y los SMS (enviados, omitidos o fallidos) creados hace más de
     * {@code bandeja_retencion_dias} días (90; 0 = no purgar). Lo pendiente nunca se borra. @return filas borradas
     */
    public int purgar() {
        int dias = jdbc.sql("SELECT valor FROM par_parametros WHERE clave = 'bandeja_retencion_dias'").query(String.class).optional()
                .map(String::strip).filter(v -> v.matches("^[0-9]{1,5}$")).map(Integer::parseInt).orElse(RETENCION_DEFECTO);
        if (dias <= 0) {
            return 0;
        }
        OffsetDateTime limite = OffsetDateTime.now(reloj).minusDays(dias);
        int n = jdbc.sql("DELETE FROM correos_salida WHERE estado IN ('enviado', 'parcial', 'fallido') AND creado < :l").param("l", limite).update()
                + jdbc.sql("DELETE FROM sms_salida WHERE estado IN ('enviado', 'omitido', 'fallido') AND creado < :l").param("l", limite).update();
        if (n > 0) {
            LOG.info("[salida] Purgados {} envíos de más de {} días", n, dias);
        }
        return n;
    }

    /** Envía todo lo vencido de los dos canales (SMS primero). @return cuántos salieron */
    public int vaciar() {
        return vaciarSms() + vaciarCorreos();
    }

    /** Toma y envía correos de a uno hasta que no quede nada vencido. @return cuántos salieron */
    public int vaciarCorreos() {
        int enviados = 0;
        for (int i = 0; i < MAX_ENVIOS; i++) {
            Optional<FilaCorreo> f = tomarCorreo();
            if (f.isEmpty()) {
                break;
            }
            if (enviarCorreo(f.get())) {
                enviados++;
            }
        }
        return enviados;
    }

    /** Toma y envía SMS de a uno hasta que no quede nada vencido. @return cuántos salieron */
    public int vaciarSms() {
        int enviados = 0;
        for (int i = 0; i < MAX_ENVIOS; i++) {
            Optional<FilaSms> f = tomarSms();
            if (f.isEmpty()) {
                break;
            }
            if (enviarSms(f.get())) {
                enviados++;
            }
        }
        return enviados;
    }

    /**
     * Los «enviando» de un despachador que murió a mitad del envío: si aún tienen intentos vuelven a «pendiente» (el intento ya contó);
     * si los agotaron quedan «fallido» con su bitácora (y {@link CorreoAgotado} si el correo era crítico). @return filas tocadas
     */
    public int recuperarColgados() {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        OffsetDateTime limite = ahora.minus(colgado);
        String error = "Sin respuesta del envío en " + colgado.toSeconds() + " s (envío colgado)";
        int fallidos = 0;
        for (Map<String, Object> f : jdbc.sql("""
                UPDATE correos_salida SET estado = 'fallido', error = :e, proximo = :ahora
                WHERE estado = 'enviando' AND tomado < :limite AND intentos >= :max RETURNING id, origen, critico, intentos""")
                .param("e", error).param("ahora", ahora).param("limite", limite).param("max", Alertas.MAX_INTENTOS).query().listOfRows()) {
            fallidos++;
            correoAgotado(((Number) f.get("id")).longValue(), (String) f.get("origen"), Boolean.TRUE.equals(f.get("critico")),
                    ((Number) f.get("intentos")).intValue(), error);
        }
        for (Map<String, Object> f : jdbc.sql("""
                UPDATE sms_salida SET estado = 'fallido', error = :e, proximo = :ahora
                WHERE estado = 'enviando' AND tomado < :limite AND intentos >= :max RETURNING id, alerta_id, intentos""")
                .param("e", error).param("ahora", ahora).param("limite", limite).param("max", Alertas.MAX_INTENTOS).query().listOfRows()) {
            fallidos++;
            smsAgotado(((Number) f.get("id")).longValue(), ((Number) f.get("alerta_id")).longValue(), ((Number) f.get("intentos")).intValue(), error);
        }
        int devueltos = 0;
        for (String tabla : List.of("correos_salida", "sms_salida")) {
            devueltos += jdbc.sql("UPDATE " + tabla + " SET estado = 'pendiente', proximo = :ahora, error = :e WHERE estado = 'enviando' AND tomado < :limite AND intentos < :max")
                    .param("ahora", ahora).param("e", error).param("limite", limite).param("max", Alertas.MAX_INTENTOS).update();
        }
        if (fallidos + devueltos > 0) {
            LOG.warn("[salida] Envíos colgados en «enviando»: {} vuelven a la cola y {} quedan fallidos", devueltos, fallidos);
        }
        return fallidos + devueltos;
    }

    // ---------- Correos ----------

    private record FilaCorreo(long id, String origen, boolean critico, List<String> para, List<String> copia, String asunto, String html,
                              String adjuntos, int intentos) {
    }

    /** Toma UN correo vencido (los de alertas críticas primero), justo antes de enviarlo: una sentencia en autocommit. */
    private Optional<FilaCorreo> tomarCorreo() {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        return jdbc.sql("""
                UPDATE correos_salida c SET estado = 'enviando', intentos = c.intentos + 1, tomado = :ahora
                FROM (SELECT id FROM correos_salida WHERE estado = 'pendiente' AND proximo <= :ahora ORDER BY critico DESC, proximo, id LIMIT 1
                      FOR UPDATE SKIP LOCKED) x
                WHERE c.id = x.id
                RETURNING c.id, c.origen, c.critico, c.para, c.copia, c.asunto, c.html, c.adjuntos::text AS adjuntos, c.intentos""")
                .param("ahora", ahora)
                .query((rs, i) -> new FilaCorreo(rs.getLong("id"), rs.getString("origen"), rs.getBoolean("critico"), lista(rs, "para"), lista(rs, "copia"),
                        rs.getString("asunto"), rs.getString("html"), rs.getString("adjuntos"), rs.getInt("intentos")))
                .optional();
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
                correoAgotado(f.id(), f.origen(), f.critico(), f.intentos(), error);
            }
            return false;
        }
        jdbc.sql("UPDATE correos_salida SET estado = 'pendiente', proximo = :p, error = :e WHERE id = :id AND estado = 'enviando' AND intentos = :i")
                .param("p", ahora.plus(Alertas.esperaReintento(f.intentos()))).param("e", error).param("id", f.id()).param("i", f.intentos()).update();
        LOG.warn("[salida] Correo {} ({}) intento {} fallido: {}", f.id(), f.origen(), f.intentos(), error);
        return false;
    }

    /** Fallo definitivo de un correo: bitácora y, si era el aviso de una alerta crítica, {@link CorreoAgotado}. */
    private void correoAgotado(long id, String origen, boolean critico, int intentos, String error) {
        bitacora.escribir(Bitacora.Operacion.INSERCION, "correo_fallido", "salida", "Correo " + id + " (" + origen + ") sin enviar tras "
                + intentos + " intentos: " + error, null, "sistema", null);
        if (critico) {
            eventos.publishEvent(new CorreoAgotado(id, origen));
        }
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

    /** Toma UN SMS vencido con el número vigente del usuario activo (la fila solo guarda la máscara). */
    private Optional<FilaSms> tomarSms() {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        return jdbc.sql("""
                UPDATE sms_salida s SET estado = 'enviando', intentos = s.intentos + 1, tomado = :ahora
                FROM (SELECT p.id, CASE WHEN u.activo THEN u.telefono END AS telefono FROM sms_salida p JOIN usuarios u ON u.id = p.usuario_id
                      WHERE p.estado = 'pendiente' AND p.proximo <= :ahora ORDER BY p.proximo, p.id LIMIT 1 FOR UPDATE OF p SKIP LOCKED) x
                WHERE s.id = x.id
                RETURNING s.id, s.alerta_id, x.telefono, s.texto, s.intentos""")
                .param("ahora", ahora)
                .query((rs, i) -> new FilaSms(rs.getLong("id"), rs.getLong("alerta_id"), Alertas.normalizarTelefono(rs.getString("telefono")).orElse(null),
                        rs.getString("texto"), rs.getInt("intentos")))
                .optional();
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
                smsAgotado(f.id(), f.alertaId(), f.intentos(), error);
            }
            return false;
        }
        jdbc.sql("UPDATE sms_salida SET estado = 'pendiente', proximo = :p, error = :e WHERE id = :id AND estado = 'enviando' AND intentos = :i")
                .param("p", ahora.plus(Alertas.esperaReintento(f.intentos()))).param("e", error).param("id", f.id()).param("i", f.intentos()).update();
        return false;
    }

    private void smsAgotado(long id, long alertaId, int intentos, String error) {
        bitacora.escribir(Bitacora.Operacion.INSERCION, "sms_fallido", "salida", "SMS " + id + " de la alerta " + alertaId + " sin enviar tras "
                + intentos + " intentos: " + error, null, "sistema", null);
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
