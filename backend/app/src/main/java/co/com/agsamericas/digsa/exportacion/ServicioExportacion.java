package co.com.agsamericas.digsa.exportacion;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.plataforma.ArchivoIntegridadFallida;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Exportacion;
import co.com.agsamericas.digsa.reglas.Huellas;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Exportación completa, diferida (contradicción 6 de la especificación: con los soportes el ZIP puede pesar varios GB y no cabe en una
 * petición). El administrador la pide; un ejecutor en segundo plano, una a la vez en todo el despliegue (candado de PostgreSQL),
 * genera el ZIP en el almacén; queda «lista» hasta que vence y se descarga en flujo. Una falla deja «fallida» con el mensaje genérico,
 * sin archivo a medias. Una limpieza borra las vencidas (contienen datos personales, Ley 1581).
 */
@Service
public class ServicioExportacion {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioExportacion.class);
    /** Llave del candado del ejecutor (pg_try_advisory_lock): una sola exportación a la vez entre réplicas. */
    static final long CANDADO = 0x4558504f52544132L;

    /** Lo que se entrega al descargar. */
    public record Descarga(String nombre, long tamano, InputStream contenido) {
    }

    private record Pedido(long id, long usuarioId, String ruta) {
    }

    private final JdbcClient jdbc;
    private final DataSource ds;
    private final Almacen almacen;
    private final GeneradorExportacion generador;
    private final RegistroBitacora bitacora;
    private final ApplicationEventPublisher eventos;
    private final Clock reloj;
    private final Duration limite;
    private final Duration vigencia;
    private final ExecutorService ejecutor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("digsa-exportacion").daemon(true).factory());

    public ServicioExportacion(JdbcClient jdbc, DataSource ds, Almacen almacen, GeneradorExportacion generador, RegistroBitacora bitacora,
                               ApplicationEventPublisher eventos, Clock reloj,
                               @Value("${digsa.exportacion.limite-minutos:10}") long limiteMinutos,
                               @Value("${digsa.exportacion.vigencia-horas:24}") long vigenciaHoras) {
        this.jdbc = jdbc;
        this.ds = ds;
        this.almacen = almacen;
        this.generador = generador;
        this.bitacora = bitacora;
        this.eventos = eventos;
        this.reloj = reloj;
        this.limite = Duration.ofMinutes(Math.max(1, limiteMinutos));
        this.vigencia = Duration.ofHours(Math.max(1, vigenciaHoras));
    }

    @PreDestroy
    void detener() {
        ejecutor.shutdownNow();
    }

    // ---------- API ----------

    /** Pide una exportación. Si el usuario ya tiene una en curso, devuelve esa (una sola a la vez por usuario). */
    public Map<String, Object> solicitar(UsuarioSesion quien) {
        Optional<Long> enCurso = enCurso(quien.id());
        long id;
        if (enCurso.isPresent()) {
            id = enCurso.get();
        } else {
            try {
                id = jdbc.sql("INSERT INTO exportaciones (solicitada_por, creado) VALUES (:u, :ahora) RETURNING id")
                        .param("u", quien.id()).param("ahora", OffsetDateTime.now(reloj)).query(Long.class).single();
                bitacora.registrar("exportacion_solicitada", "exportacion id=" + id, quien.id(), quien.usuario());
            } catch (DuplicateKeyException e) {
                id = enCurso(quien.id()).orElseThrow(() -> e); // otra pestaña la pidió al mismo tiempo
            }
        }
        despertar();
        return estado(id);
    }

    private Optional<Long> enCurso(long usuarioId) {
        return jdbc.sql("SELECT id FROM exportaciones WHERE solicitada_por = :u AND estado IN ('pendiente', 'generando')")
                .param("u", usuarioId).query(Long.class).optional();
    }

    /** Las últimas exportaciones del usuario, de la más reciente a la más antigua. */
    public List<Map<String, Object>> listar(UsuarioSesion quien) {
        return jdbc.sql(SELECT + " WHERE solicitada_por = :u ORDER BY id DESC LIMIT 10").param("u", quien.id()).query(ServicioExportacion::fila).list();
    }

    Map<String, Object> estado(long id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(ServicioExportacion::fila).single();
    }

    private static final String SELECT = """
            SELECT id, estado, tamano, tablas, filas, soportes, huellas_distintas, no_encontrados, sha256, error, creado, iniciado, terminado, vence
            FROM exportaciones""";

    private static Map<String, Object> fila(ResultSet rs, int n) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("estado", rs.getString("estado"));
        m.put("tamano", rs.getObject("tamano"));
        m.put("tablas", rs.getObject("tablas"));
        m.put("filas", rs.getObject("filas"));
        m.put("soportes", rs.getObject("soportes"));
        m.put("huellasDistintas", rs.getObject("huellas_distintas"));
        m.put("noEncontrados", rs.getObject("no_encontrados"));
        m.put("sha256", rs.getString("sha256") == null ? null : rs.getString("sha256").strip());
        m.put("error", rs.getString("error"));
        m.put("creado", rs.getObject("creado", OffsetDateTime.class));
        m.put("iniciado", rs.getObject("iniciado", OffsetDateTime.class));
        m.put("terminado", rs.getObject("terminado", OffsetDateTime.class));
        m.put("vence", rs.getObject("vence", OffsetDateTime.class));
        return m;
    }

    /** Abre el ZIP de una exportación lista del usuario y lo deja en la bitácora (exportacion_completa). */
    public Descarga descargar(long id, UsuarioSesion quien) {
        record Lista(String estado, String ruta, Long tamano, Integer tablas, Long filas, Integer soportes, OffsetDateTime terminado, OffsetDateTime vence) {
        }
        Lista l = jdbc.sql("""
                SELECT estado, ruta, tamano, tablas, filas, soportes, terminado, vence FROM exportaciones WHERE id = :id AND solicitada_por = :u""")
                .param("id", id).param("u", quien.id()).query(Lista.class).optional()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("La exportación no existe."));
        if ("vencida".equals(l.estado()) || "lista".equals(l.estado()) && !l.vence().toInstant().isAfter(reloj.instant())) {
            throw new ErrorNegocio(HttpStatus.GONE, "La exportación venció y se borró. Pida una nueva.");
        }
        if (!"lista".equals(l.estado())) {
            throw ErrorNegocio.conflicto("La exportación aún no está lista.");
        }
        InputStream in;
        try {
            in = almacen.leer(l.ruta());
        } catch (IOException | IllegalArgumentException e) {
            LOG.error("[exportacion] El ZIP de la exportación {} no está en el almacén", id, e);
            throw new ErrorNegocio(HttpStatus.INTERNAL_SERVER_ERROR, Exportacion.MENSAJE_FALLA);
        }
        bitacora.registrar("exportacion_completa", Exportacion.resumen(l.tablas(), l.filas(), l.soportes()), quien.id(), quien.usuario());
        return new Descarga(Exportacion.nombreZip(l.terminado()), l.tamano(), in);
    }

    // ---------- Ejecutor en segundo plano ----------

    /** Revisa si hay exportaciones pendientes (sin esperar: corre en el hilo del ejecutor). */
    public void despertar() {
        try {
            ejecutor.execute(this::correr);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            LOG.warn("[exportacion] El ejecutor está detenido");
        }
    }

    /** Genera las pendientes, una tras otra, si esta instancia toma el candado. */
    void correr() {
        try (Connection c = ds.getConnection()) {
            if (!candado(c, "SELECT pg_try_advisory_lock(?)")) {
                return; // otra réplica está generando: al terminar toma las pendientes
            }
            try {
                huerfanas();
                for (Optional<Pedido> p = siguiente(); p.isPresent(); p = siguiente()) {
                    generar(c, p.get());
                    if (!c.isValid(5)) {
                        break; // la conexión del candado se perdió: las pendientes esperan a la próxima revisión
                    }
                }
            } finally {
                candado(c, "SELECT pg_advisory_unlock(?)");
            }
        } catch (SQLException | RuntimeException e) {
            LOG.error("[exportacion] El ejecutor falló", e);
        }
    }

    private static boolean candado(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, CANDADO);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /** Con el candado tomado, una «generando» es de un proceso que se cayó: queda fallida y se borra su archivo a medias. */
    private void huerfanas() {
        jdbc.sql("""
                UPDATE exportaciones SET estado = 'fallida', error = :error, terminado = :ahora WHERE estado = 'generando' RETURNING ruta""")
                .param("error", Exportacion.MENSAJE_FALLA).param("ahora", OffsetDateTime.now(reloj)).query(String.class).list()
                .forEach(r -> {
                    if (r != null) {
                        almacen.descartar(r);
                    }
                });
    }

    private Optional<Pedido> siguiente() {
        return jdbc.sql("""
                UPDATE exportaciones e SET estado = 'generando', iniciado = :ahora, ruta = 'exportaciones/' || e.id || '_' || :azar || '.zip'
                WHERE e.id = (SELECT id FROM exportaciones WHERE estado = 'pendiente' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING e.id, e.solicitada_por, e.ruta""")
                .param("ahora", OffsetDateTime.now(reloj)).param("azar", Huellas.secreto(12))
                .query((rs, n) -> new Pedido(rs.getLong(1), rs.getLong(2), rs.getString(3))).optional();
    }

    private void generar(Connection c, Pedido p) {
        Map<String, Object> u = jdbc.sql("SELECT nombre, usuario FROM usuarios WHERE id = :id").param("id", p.usuarioId()).query().singleRow();
        String usuario = (String) u.get("usuario");
        OffsetDateTime inicio = OffsetDateTime.now(reloj);
        try {
            GeneradorExportacion.Resultado r = enFoto(c, p, u.get("nombre") + " (" + usuario + ")", inicio);
            OffsetDateTime fin = OffsetDateTime.now(reloj);
            jdbc.sql("""
                    UPDATE exportaciones SET estado = 'lista', tamano = :tamano, tablas = :tablas, filas = :filas, soportes = :soportes,
                           huellas_distintas = :distintas, no_encontrados = :faltan, sha256 = :sha, terminado = :fin, vence = :vence
                    WHERE id = :id AND estado = 'generando'""")
                    .param("tamano", r.tamano()).param("tablas", r.tablas()).param("filas", r.filas()).param("soportes", (int) r.soportes())
                    .param("distintas", (int) r.distintas()).param("faltan", (int) r.noEncontrados()).param("sha", r.sha256())
                    .param("fin", fin).param("vence", fin.plus(vigencia)).param("id", p.id()).update();
            LOG.info("[exportacion] {} lista: {} tablas, {} filas, {} soportes, {} bytes en {} s", p.id(), r.tablas(), r.filas(), r.soportes(),
                    r.tamano(), Duration.between(inicio, fin).toSeconds());
            bitacora.registrar("exportacion_generada", "exportacion id=" + p.id() + " · " + Exportacion.resumen(r.tablas(), r.filas(), (int) r.soportes()),
                    p.usuarioId(), usuario);
            if (r.distintas() + r.noEncontrados() > 0) {
                publicar("Exportación " + p.id() + ": " + r.distintas() + " archivo(s) con huella distinta y " + r.noEncontrados()
                        + " no encontrado(s) en el almacén");
            }
        } catch (Exception e) {
            LOG.error("[exportacion] No se pudo generar la exportación {}", p.id(), e);
            almacen.descartar(p.ruta()); // nunca queda un ZIP a medias que parezca completo
            jdbc.sql("UPDATE exportaciones SET estado = 'fallida', error = :error, terminado = :fin WHERE id = :id")
                    .param("error", Exportacion.MENSAJE_FALLA).param("fin", OffsetDateTime.now(reloj)).param("id", p.id()).update();
            bitacora.registrar("exportacion_fallida", "exportacion id=" + p.id(), p.usuarioId(), usuario);
        }
    }

    /** La generación en una transacción READ ONLY y REPEATABLE READ sobre la conexión del candado (una sola conexión del pool). */
    private GeneradorExportacion.Resultado enFoto(Connection c, Pedido p, String generadoPor, OffsetDateTime inicio) throws Exception {
        c.setAutoCommit(false);
        c.setReadOnly(true);
        c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        try {
            GeneradorExportacion.Resultado r = generador.generar(c, p.ruta(), generadoPor, inicio, Instant.now().plus(limite));
            c.commit();
            return r;
        } catch (Exception e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
            c.setReadOnly(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        }
    }

    private void publicar(String detalle) {
        try {
            eventos.publishEvent(new ArchivoIntegridadFallida(detalle));
        } catch (RuntimeException e) {
            LOG.error("[exportacion] No se pudo publicar la alerta de integridad: {}", detalle, e);
        }
    }

    // ---------- Revisión periódica ----------

    /** Cada minuto: borra las vencidas y despierta al ejecutor si quedó algo pendiente (p. ej. tras un reinicio). */
    @Scheduled(fixedDelayString = "${digsa.exportacion.revision-ms:60000}", initialDelayString = "${digsa.exportacion.revision-inicial-ms:60000}")
    public void revisar() {
        limpiar();
        if (jdbc.sql("SELECT count(*) FROM exportaciones WHERE estado IN ('pendiente', 'generando')").query(Long.class).single() > 0) {
            despertar();
        }
    }

    /** Las listas vencidas quedan «vencidas» y se borra su ZIP. @return cuántas */
    public int limpiar() {
        record Vencida(long id, String ruta, long usuarioId) {
        }
        List<Vencida> vencidas = jdbc.sql("""
                UPDATE exportaciones SET estado = 'vencida' WHERE estado = 'lista' AND vence <= :ahora RETURNING id, ruta, solicitada_por""")
                .param("ahora", OffsetDateTime.now(reloj)).query((rs, n) -> new Vencida(rs.getLong(1), rs.getString(2), rs.getLong(3))).list();
        for (Vencida v : vencidas) {
            almacen.descartar(v.ruta());
            bitacora.registrar("exportacion_vencida", "exportacion id=" + v.id(), v.usuarioId(), null);
        }
        return vencidas.size();
    }
}
