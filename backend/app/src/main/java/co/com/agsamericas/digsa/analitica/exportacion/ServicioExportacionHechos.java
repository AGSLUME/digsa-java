package co.com.agsamericas.digsa.analitica.exportacion;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.AnaliticaExportacionHechos;
import co.com.agsamericas.digsa.reglas.AnaliticaExportacionHechos.MesExportado;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Huellas;
import jakarta.annotation.PreDestroy;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exportación de los hechos de dispensación por periodo (duda AN.14, A7.2), diferida como la exportación completa: el Administrador pide
 * un periodo; se congela el corte (el instante de la solicitud) Y la lista de cargues visibles y CONFIRMADOS en ese momento, y se crea un
 * trabajo con un renglón por mes. Todos los meses filtran con esa misma lista ({@code cargue_id = ANY(cargues)}): un cargue que se
 * confirma mientras la exportación corre (su {@code aplicado_en} es anterior a su confirmación) no entra en ningún mes, y una reanudación
 * da el mismo resultado (revisión A7.2, P1).
 *
 * <p>Un ejecutor en segundo plano, uno a la vez en todo el despliegue (candado de PostgreSQL, distinto del de la exportación completa),
 * genera los meses en orden, CADA UNO en su propia transacción de solo lectura con su propio tope ({@code limite-minutos-mes}); un mes que
 * falla queda «fallido» y los demás siguen. Al terminar escribe el LEEME (si no se puede, se reintenta en la siguiente revisión). Los
 * archivos quedan hasta que vencen y se descargan en flujo.
 *
 * <p>Borrado (datos de salud, Ley 1581): un archivo solo deja de estar referenciado en la base cuando se comprobó que ya no está en el
 * almacén; si el borrado falla, la ruta se conserva y la revisión periódica lo vuelve a intentar (revisión A7.2, P2).
 *
 * <p>Si el proceso se cae a mitad, el trabajo se retoma con el mismo corte y la misma lista de cargues desde el mes que estaba generando:
 * los meses listos se conservan.
 */
@Service
public class ServicioExportacionHechos {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioExportacionHechos.class);
    /** Llave del candado del ejecutor (pg_try_advisory_lock): una sola exportación de hechos a la vez entre réplicas. */
    static final long CANDADO = 0x414e4148454348L;
    static final String MENSAJE_FALLA_TRABAJO = "Uno o más meses no se pudieron generar. Los demás se pueden descargar; pida de nuevo los que fallaron.";
    static final String MENSAJE_FALLA_LEEME = "Los meses están generados pero el LEEME no se pudo guardar; se reintenta en la próxima revisión.";
    static final String EN_CURSO = "Ya tiene una exportación de hechos en curso. Espere a que termine para pedir otra.";

    /** Lo que se entrega al descargar. */
    public record Descarga(String nombre, long tamano, InputStream contenido) {
    }

    private record Trabajo(long id, long usuarioId, OffsetDateTime corte, Long[] cargues) {
    }

    private record Mes(LocalDate mes, String ruta) {
    }

    /** Solo para pruebas: se llama antes de guardar el LEEME (la prueba de reintento lo hace fallar). */
    volatile Runnable antesDeLeeme = () -> { };

    private final JdbcClient jdbc;
    private final DataSource ds;
    private final Almacen almacen;
    private final GeneradorHechosMes generador;
    private final RegistroBitacora bitacora;
    private final PropiedadesExportacionHechos propiedades;
    private final TransactionTemplate transaccion;
    private final Clock reloj;
    private final ExecutorService ejecutor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("digsa-analitica-hechos").daemon(true).factory());
    /** Hay una corrida en la cola del ejecutor que aún no empezó: no se encola otra (la cola no crece con cada solicitud). */
    private final AtomicBoolean encolado = new AtomicBoolean();

    ServicioExportacionHechos(JdbcClient jdbc, DataSource ds, Almacen almacen, GeneradorHechosMes generador, RegistroBitacora bitacora,
                              PropiedadesExportacionHechos propiedades, PlatformTransactionManager transacciones, Clock reloj) {
        this.jdbc = jdbc;
        this.ds = ds;
        this.almacen = almacen;
        this.generador = generador;
        this.bitacora = bitacora;
        this.propiedades = propiedades;
        this.transaccion = new TransactionTemplate(transacciones);
        this.reloj = reloj;
    }

    @PreDestroy
    void detener() {
        ejecutor.shutdownNow();
    }

    // ---------- API ----------

    /** Pide la exportación de un periodo (meses aaaa-mm; sin «hasta», un solo mes). Una sola en curso por usuario (409). */
    public Map<String, Object> solicitar(String desde, String hasta, UsuarioSesion quien) {
        YearMonth actual = YearMonth.now(reloj.withZone(Fechas.BOGOTA));
        AnaliticaExportacionHechos.validar(desde, hasta, actual, propiedades.maximoMeses()).ifPresent(p -> {
            throw ErrorNegocio.validacion(p.mensaje(), p.campo());
        });
        YearMonth d = AnaliticaExportacionHechos.mes(desde).orElseThrow();
        YearMonth h = hasta == null || hasta.isBlank() ? d : AnaliticaExportacionHechos.mes(hasta).orElseThrow();
        if (enCurso(quien.id())) {
            throw ErrorNegocio.conflicto(EN_CURSO);
        }
        OffsetDateTime corte = OffsetDateTime.now(reloj);
        List<YearMonth> meses = AnaliticaExportacionHechos.meses(d, h);
        long id;
        try {
            id = transaccion.execute(t -> {
                // Una sola sentencia: la lista sale de una foto de los cargues CONFIRMADOS (un cargue que se está aplicando no se ve, ni el
                // reemplazo que hará), y es la que usan todos los meses
                long nuevo = jdbc.sql("""
                        INSERT INTO ana_exportaciones_hechos (solicitada_por, desde, hasta, corte, corte_cargue_id, cargues, meses, creado)
                        SELECT :u, :d, :h, :c, max(v.cargue_id), coalesce(array_agg(v.cargue_id ORDER BY v.cargue_id), '{}'::bigint[]), :m, :ahora
                        FROM ana_cargues_visibles(:c) v
                        RETURNING id""")
                        .param("u", quien.id()).param("d", d.atDay(1)).param("h", h.atDay(1)).param("c", corte)
                        .param("m", meses.size()).param("ahora", corte).query(Long.class).single();
                jdbc.sql("""
                        INSERT INTO ana_exportaciones_hechos_meses (exportacion_id, mes)
                        SELECT :id, g::date FROM generate_series(:d::date, :h::date, interval '1 month') g""")
                        .param("id", nuevo).param("d", d.atDay(1)).param("h", h.atDay(1)).update();
                return nuevo;
            });
        } catch (DuplicateKeyException e) {
            throw ErrorNegocio.conflicto(EN_CURSO); // otra pestaña la pidió al mismo tiempo (índice único parcial)
        }
        bitacora.registrar("analitica_hechos_exportacion_solicitada", AnaliticaExportacionHechos.resumen(id, d, h) + " · " + meses.size() + " meses",
                quien.id(), quien.usuario());
        despertar();
        return detalle(id, quien);
    }

    private boolean enCurso(long usuarioId) {
        return jdbc.sql("SELECT count(*) FROM ana_exportaciones_hechos WHERE solicitada_por = :u AND estado IN ('pendiente', 'generando')")
                .param("u", usuarioId).query(Long.class).single() > 0;
    }

    /** Las últimas exportaciones de hechos del usuario, de la más reciente a la más antigua, con sus meses. */
    public List<Map<String, Object>> listar(UsuarioSesion quien) {
        List<Map<String, Object>> l = jdbc.sql(SELECT + " WHERE solicitada_por = :u ORDER BY id DESC LIMIT 20").param("u", quien.id())
                .query(ServicioExportacionHechos::fila).list();
        if (l.isEmpty()) {
            return l;
        }
        Map<Long, List<Map<String, Object>>> meses = new LinkedHashMap<>();
        jdbc.sql(SELECT_MESES + " WHERE exportacion_id = ANY(:ids) ORDER BY exportacion_id, mes")
                .param("ids", l.stream().map(m -> (Long) m.get("id")).toArray(Long[]::new))
                .query(rs -> {
                    meses.computeIfAbsent(rs.getLong("exportacion_id"), k -> new ArrayList<>()).add(mes(rs));
                });
        l.forEach(m -> m.put("archivos", meses.getOrDefault((Long) m.get("id"), List.of())));
        return l;
    }

    /** Una exportación del usuario con sus meses (404 si no existe o es de otro). */
    public Map<String, Object> detalle(long id, UsuarioSesion quien) {
        Map<String, Object> m = jdbc.sql(SELECT + " WHERE id = :id AND solicitada_por = :u").param("id", id).param("u", quien.id())
                .query(ServicioExportacionHechos::fila).optional().orElseThrow(() -> ErrorNegocio.noEncontrado("La exportación de hechos no existe."));
        List<Map<String, Object>> meses = new ArrayList<>();
        jdbc.sql(SELECT_MESES + " WHERE exportacion_id = :id ORDER BY mes").param("id", id).query(rs -> {
            meses.add(mes(rs));
        });
        m.put("archivos", meses);
        return m;
    }

    private static final String SELECT = """
            SELECT id, desde, hasta, corte, corte_cargue_id, cardinality(cargues) AS cargues, estado, meses, meses_listos, meses_fallidos, filas, tamano,
                   leeme_ruta IS NOT NULL AS leeme, error, creado, iniciado, terminado, vence
            FROM ana_exportaciones_hechos""";

    private static final String SELECT_MESES = """
            SELECT exportacion_id, mes, estado, filas, valor_total, tamano, sha256, error, iniciado, terminado, duracion_ms
            FROM ana_exportaciones_hechos_meses""";

    private static Map<String, Object> fila(ResultSet rs, int n) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        long id = rs.getLong("id");
        m.put("id", id);
        m.put("desde", YearMonth.from(rs.getObject("desde", LocalDate.class)).toString());
        m.put("hasta", YearMonth.from(rs.getObject("hasta", LocalDate.class)).toString());
        m.put("corte", rs.getObject("corte", OffsetDateTime.class));
        m.put("corteCargueId", rs.getObject("corte_cargue_id"));
        m.put("cargues", rs.getInt("cargues"));
        m.put("estado", rs.getString("estado"));
        m.put("meses", rs.getInt("meses"));
        m.put("mesesListos", rs.getInt("meses_listos"));
        m.put("mesesFallidos", rs.getInt("meses_fallidos"));
        m.put("filas", rs.getLong("filas"));
        m.put("tamano", rs.getLong("tamano"));
        m.put("leeme", rs.getBoolean("leeme"));
        m.put("nombreLeeme", AnaliticaExportacionHechos.nombreLeeme(id));
        m.put("error", rs.getString("error"));
        m.put("creado", rs.getObject("creado", OffsetDateTime.class));
        m.put("iniciado", rs.getObject("iniciado", OffsetDateTime.class));
        m.put("terminado", rs.getObject("terminado", OffsetDateTime.class));
        m.put("vence", rs.getObject("vence", OffsetDateTime.class));
        return m;
    }

    private static Map<String, Object> mes(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        YearMonth mes = YearMonth.from(rs.getObject("mes", LocalDate.class));
        m.put("mes", mes.toString());
        m.put("nombre", AnaliticaExportacionHechos.nombreArchivo(rs.getLong("exportacion_id"), mes));
        m.put("estado", rs.getString("estado"));
        m.put("filas", rs.getObject("filas"));
        m.put("valorTotal", rs.getBigDecimal("valor_total"));
        m.put("tamano", rs.getObject("tamano"));
        m.put("sha256", rs.getString("sha256"));
        m.put("error", rs.getString("error"));
        m.put("iniciado", rs.getObject("iniciado", OffsetDateTime.class));
        m.put("terminado", rs.getObject("terminado", OffsetDateTime.class));
        m.put("duracionMs", rs.getObject("duracion_ms"));
        return m;
    }

    /** Abre el .csv.gz de un mes listo de una exportación del usuario y lo deja en la bitácora. */
    public Descarga descargarMes(long id, String mesTexto, UsuarioSesion quien) {
        YearMonth mes = AnaliticaExportacionHechos.mes(mesTexto).orElseThrow(() -> ErrorNegocio.validacion("Indique el mes (aaaa-mm).", "mes"));
        vigente(id, quien);
        record Archivo(String estado, String ruta, Long tamano, Long filas) {
        }
        Archivo a = jdbc.sql("SELECT estado, ruta, tamano, filas FROM ana_exportaciones_hechos_meses WHERE exportacion_id = :id AND mes = :m")
                .param("id", id).param("m", mes.atDay(1)).query(Archivo.class).optional()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("El mes no está en esta exportación."));
        if (!"lista".equals(a.estado())) {
            throw "fallida".equals(a.estado()) ? ErrorNegocio.conflicto("Ese mes no se pudo generar. Pida una nueva exportación de ese mes.")
                    : ErrorNegocio.conflicto("Ese mes aún no está listo.");
        }
        InputStream in = abrir(a.ruta(), id);
        try {
            bitacora.registrar("analitica_hechos_exportacion_descarga", AnaliticaExportacionHechos.resumen(id, mes, mes) + " · " + a.filas() + " filas",
                    quien.id(), quien.usuario());
        } catch (RuntimeException e) {
            cerrar(in); // sin bitácora no hay descarga, y el archivo no queda abierto
            throw e;
        }
        return new Descarga(AnaliticaExportacionHechos.nombreArchivo(id, mes), a.tamano(), in);
    }

    private static void cerrar(InputStream in) {
        try {
            in.close();
        } catch (IOException e) {
            LOG.warn("[analitica] No se pudo cerrar un archivo de la exportación de hechos: {}", e.getMessage());
        }
    }

    /** El LEEME de una exportación terminada del usuario (sin datos personales). */
    public Descarga leeme(long id, UsuarioSesion quien) {
        String ruta = vigente(id, quien);
        if (ruta == null) {
            throw ErrorNegocio.conflicto("La exportación aún no ha terminado.");
        }
        try (InputStream in = abrir(ruta, id)) {
            byte[] b = in.readAllBytes();
            return new Descarga(AnaliticaExportacionHechos.nombreLeeme(id), b.length, new java.io.ByteArrayInputStream(b));
        } catch (IOException e) {
            LOG.error("[analitica] No se pudo leer el LEEME de la exportación de hechos {}", id, e);
            throw new ErrorNegocio(HttpStatus.INTERNAL_SERVER_ERROR, AnaliticaExportacionHechos.MENSAJE_FALLA);
        }
    }

    /** Comprueba que la exportación es del usuario y no venció; devuelve la ruta del LEEME (nula si aún no termina). */
    private String vigente(long id, UsuarioSesion quien) {
        record Estado(String estado, OffsetDateTime vence, String leemeRuta) {
        }
        Estado e = jdbc.sql("SELECT estado, vence, leeme_ruta FROM ana_exportaciones_hechos WHERE id = :id AND solicitada_por = :u")
                .param("id", id).param("u", quien.id()).query(Estado.class).optional()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("La exportación de hechos no existe."));
        if ("vencida".equals(e.estado()) || e.vence() != null && !e.vence().toInstant().isAfter(reloj.instant())) {
            throw new ErrorNegocio(HttpStatus.GONE, "La exportación venció y sus archivos se borraron. Pida una nueva.");
        }
        return e.leemeRuta();
    }

    private InputStream abrir(String ruta, long id) {
        try {
            return almacen.leer(ruta);
        } catch (IOException | IllegalArgumentException e) {
            LOG.error("[analitica] Un archivo de la exportación de hechos {} no está en el almacén", id, e);
            throw new ErrorNegocio(HttpStatus.INTERNAL_SERVER_ERROR, AnaliticaExportacionHechos.MENSAJE_FALLA);
        }
    }

    // ---------- Borrado verificado ----------

    /**
     * Borra un archivo del almacén y comprueba que ya no está (el puerto {@link Almacen#descartar} no informa si falló). Solo se da por
     * borrado si leerlo responde «no existe»; cualquier otra respuesta (permiso, carpeta, almacén caído) se trata como «sigue ahí» y la
     * ruta se conserva en la base para reintentar.
     */
    boolean borrar(String ruta) {
        almacen.descartar(ruta);
        try (InputStream in = almacen.leer(ruta)) {
            LOG.error("[analitica] No se pudo borrar {} de la exportación de hechos: sigue en el almacén; se reintenta en la próxima revisión", ruta);
            return false;
        } catch (NoSuchFileException | FileNotFoundException e) {
            return true;
        } catch (IOException e) {
            LOG.error("[analitica] No se pudo comprobar el borrado de {}: {}; se reintenta en la próxima revisión", ruta, e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            return true; // ruta que el almacén no acepta: no puede haber nada en ella
        }
    }

    // ---------- Ejecutor en segundo plano ----------

    /** Revisa si hay exportaciones pendientes (sin esperar: corre en el hilo del ejecutor). Como mucho una corrida en cola. */
    public void despertar() {
        if (!encolado.compareAndSet(false, true)) {
            return; // ya hay una en cola: al correr toma todas las pendientes
        }
        try {
            ejecutor.execute(() -> {
                encolado.set(false); // lo que llegue desde aquí encola otra corrida, que verá lo nuevo
                correr();
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            encolado.set(false);
            LOG.warn("[analitica] El ejecutor de la exportación de hechos está detenido");
        }
    }

    /** Genera las pendientes, una tras otra, si esta instancia toma el candado (en su propia conexión, que no lee hechos). */
    void correr() {
        Connection c = null;
        boolean soltado = false;
        try {
            c = ds.getConnection();
            if (!candado(c, "SELECT pg_try_advisory_lock(?)")) {
                soltado = true; // no lo tomó: no hay nada que soltar
                return; // otra réplica está generando: al terminar toma las pendientes
            }
            try {
                Connection candado = c;
                huerfanas();
                for (Optional<Trabajo> t = siguiente(); t.isPresent(); t = siguiente()) {
                    if (!generar(t.get(), () -> vivo(candado))) {
                        break; // se perdió el candado o el LEEME se reintenta en la próxima revisión
                    }
                }
            } finally {
                soltado = vivo(c) && candado(c, "SELECT pg_advisory_unlock(?)");
            }
        } catch (SQLException | RuntimeException e) {
            LOG.error("[analitica] El ejecutor de la exportación de hechos falló", e);
        } finally {
            if (c != null) {
                if (!soltado) {
                    // No se pudo soltar el candado: la conexión física no vuelve al pool (lo retendría para siempre); al cerrarla
                    // PostgreSQL libera el candado
                    try {
                        c.abort(Runnable::run);
                    } catch (SQLException | RuntimeException e) {
                        LOG.error("[analitica] No se pudo abortar la conexión del candado de la exportación de hechos", e);
                    }
                }
                try {
                    c.close();
                } catch (SQLException e) {
                    LOG.warn("[analitica] No se pudo cerrar la conexión del candado: {}", e.getMessage());
                }
            }
        }
    }

    private static boolean vivo(Connection c) {
        try {
            return c.isValid(5);
        } catch (SQLException e) {
            return false;
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

    /**
     * Con el candado tomado, un trabajo «generando» es de un proceso que se cayó: su mes a medias se borra y vuelve a pendiente, y el
     * trabajo se retoma con el mismo corte y la misma lista de cargues (los meses listos se conservan). Si el archivo a medias no se pudo
     * borrar, el mes queda «fallido» con la ruta (la revisión reintenta el borrado) y el trabajo sigue con los demás.
     */
    void huerfanas() {
        record Medio(long exportacionId, LocalDate mes, String ruta) {
        }
        List<Medio> medios = jdbc.sql("SELECT exportacion_id, mes, ruta FROM ana_exportaciones_hechos_meses WHERE estado = 'generando'")
                .query((rs, n) -> new Medio(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getString(3))).list();
        for (Medio m : medios) {
            if (m.ruta() == null || borrar(m.ruta())) {
                jdbc.sql("""
                        UPDATE ana_exportaciones_hechos_meses SET estado = 'pendiente', ruta = NULL, iniciado = NULL
                        WHERE exportacion_id = :id AND mes = :m AND estado = 'generando'""").param("id", m.exportacionId()).param("m", m.mes()).update();
            } else {
                marcarFallido(m.exportacionId(), m.mes(), true);
            }
        }
        jdbc.sql("UPDATE ana_exportaciones_hechos SET estado = 'pendiente' WHERE estado = 'generando'").update();
    }

    private Optional<Trabajo> siguiente() {
        return jdbc.sql("""
                UPDATE ana_exportaciones_hechos e SET estado = 'generando', iniciado = coalesce(e.iniciado, :ahora)
                WHERE e.id = (SELECT id FROM ana_exportaciones_hechos WHERE estado = 'pendiente' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING e.id, e.solicitada_por, e.corte, e.cargues""")
                .param("ahora", OffsetDateTime.now(reloj))
                .query((rs, n) -> new Trabajo(rs.getLong(1), rs.getLong(2), rs.getObject(3, OffsetDateTime.class), cargues(rs.getArray(4)))).optional();
    }

    private static Long[] cargues(Array a) throws SQLException {
        if (a == null) {
            return new Long[0];
        }
        Object[] v = (Object[]) a.getArray();
        return Arrays.stream(v).map(x -> ((Number) x).longValue()).toArray(Long[]::new);
    }

    /** @return false si hay que parar el ejecutor (candado perdido o LEEME para reintentar) */
    private boolean generar(Trabajo t, java.util.function.BooleanSupplier candadoVivo) {
        String usuario = jdbc.sql("SELECT usuario FROM usuarios WHERE id = :id").param("id", t.usuarioId()).query(String.class).single();
        while (true) {
            if (!candadoVivo.getAsBoolean()) {
                return false; // otra réplica puede tomar el candado: no se empieza otro mes (el trabajo queda «generando» y se retoma)
            }
            Optional<Mes> m = siguienteMes(t.id());
            if (m.isEmpty()) {
                break;
            }
            generarMes(t, m.get(), candadoVivo);
        }
        return terminar(t, usuario);
    }

    private Optional<Mes> siguienteMes(long id) {
        return jdbc.sql("""
                UPDATE ana_exportaciones_hechos_meses m SET estado = 'generando', iniciado = :ahora,
                       ruta = 'analitica/hechos/' || m.exportacion_id || '_' || to_char(m.mes, 'YYYY-MM') || '_' || :azar || '_csv.gz'
                WHERE m.exportacion_id = :id AND m.mes = (SELECT min(mes) FROM ana_exportaciones_hechos_meses WHERE exportacion_id = :id AND estado = 'pendiente')
                RETURNING m.mes, m.ruta""")
                .param("ahora", OffsetDateTime.now(reloj)).param("azar", Huellas.secreto(12)).param("id", id)
                .query((rs, n) -> new Mes(rs.getObject(1, LocalDate.class), rs.getString(2))).optional();
    }

    /** Un mes en su propia conexión y transacción de solo lectura (REPEATABLE READ: una foto), con su propio tope. */
    private void generarMes(Trabajo t, Mes m, java.util.function.BooleanSupplier candadoVivo) {
        YearMonth mes = YearMonth.from(m.mes());
        long inicio = System.nanoTime();
        try {
            GeneradorHechosMes.Resultado r;
            try (Connection c = ds.getConnection()) {
                c.setAutoCommit(false);
                c.setReadOnly(true);
                c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                try {
                    r = generador.generar(c, mes, t.cargues(), m.ruta(), propiedades.filasPorLote(), Instant.now().plus(propiedades.limiteMes()));
                    c.commit();
                } catch (Exception e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                    c.setReadOnly(false);
                    c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                }
            }
            long ms = Duration.ofNanos(System.nanoTime() - inicio).toMillis();
            boolean ok = candadoVivo.getAsBoolean() && Boolean.TRUE.equals(transaccion.execute(x -> {
                // Solo si el mes sigue siendo nuestro: con el candado perdido, otra réplica pudo reiniciarlo (y borrar este archivo)
                int n = jdbc.sql("""
                        UPDATE ana_exportaciones_hechos_meses SET estado = 'lista', filas = :f, valor_total = :v, tamano = :t, sha256 = :s,
                               terminado = :ahora, duracion_ms = :ms
                        WHERE exportacion_id = :id AND mes = :m AND estado = 'generando' AND ruta = :ruta""")
                        .param("f", r.filas()).param("v", r.valorTotal()).param("t", r.tamano()).param("s", r.sha256())
                        .param("ahora", OffsetDateTime.now(reloj)).param("ms", ms).param("id", t.id()).param("m", m.mes()).param("ruta", m.ruta()).update();
                if (n == 1) {
                    jdbc.sql("UPDATE ana_exportaciones_hechos SET meses_listos = meses_listos + 1, filas = filas + :f, tamano = tamano + :t WHERE id = :id")
                            .param("f", r.filas()).param("t", r.tamano()).param("id", t.id()).update();
                }
                return n == 1;
            }));
            if (!ok) {
                LOG.warn("[analitica] Exportación de hechos {} · {}: el mes dejó de ser de este proceso; se descarta su archivo", t.id(), mes);
                borrar(m.ruta());
                return;
            }
            LOG.info("[analitica] Exportación de hechos {} · {}: {} filas, {} bytes en {} ms", t.id(), mes, r.filas(), r.tamano(), ms);
        } catch (Exception e) {
            LOG.error("[analitica] No se pudo generar el mes {} de la exportación de hechos {}", mes, t.id(), e);
            // Nunca queda un archivo a medias que parezca completo; si no se pudo borrar, la ruta queda para reintentarlo
            marcarFallido(t.id(), m.mes(), !borrar(m.ruta()));
        }
    }

    private void marcarFallido(long id, LocalDate mes, boolean conservarRuta) {
        transaccion.executeWithoutResult(x -> {
            int n = jdbc.sql("""
                    UPDATE ana_exportaciones_hechos_meses SET estado = 'fallida', ruta = CASE WHEN :conservar THEN ruta END, error = :e, terminado = :ahora
                    WHERE exportacion_id = :id AND mes = :m AND estado = 'generando'""")
                    .param("conservar", conservarRuta).param("e", AnaliticaExportacionHechos.MENSAJE_FALLA).param("ahora", OffsetDateTime.now(reloj))
                    .param("id", id).param("m", mes).update();
            if (n == 1) {
                jdbc.sql("UPDATE ana_exportaciones_hechos SET meses_fallidos = meses_fallidos + 1 WHERE id = :id").param("id", id).update();
            }
        });
    }

    /**
     * Escribe el LEEME, deja el trabajo lista (o fallida si algún mes falló) con su vencimiento y lo registra en la bitácora. Si el LEEME
     * no se puede guardar, el trabajo queda «pendiente» con su mensaje y la próxima revisión lo vuelve a intentar (los meses ya están).
     *
     * @return false si el LEEME queda para reintentar
     */
    private boolean terminar(Trabajo t, String usuario) {
        record Cabeza(OffsetDateTime creado, LocalDate desde, LocalDate hasta, Long corteCargueId, int fallidos, long filas) {
        }
        Cabeza k = jdbc.sql("SELECT creado, desde, hasta, corte_cargue_id, meses_fallidos AS fallidos, filas FROM ana_exportaciones_hechos WHERE id = :id")
                .param("id", t.id()).query(Cabeza.class).single();
        List<MesExportado> meses = jdbc.sql("""
                SELECT mes, estado, coalesce(filas, 0), coalesce(valor_total, 0), coalesce(tamano, 0), sha256
                FROM ana_exportaciones_hechos_meses WHERE exportacion_id = :id ORDER BY mes""").param("id", t.id())
                .query((rs, n) -> new MesExportado(YearMonth.from(rs.getObject(1, LocalDate.class)), rs.getString(2), rs.getLong(3), rs.getBigDecimal(4),
                        rs.getLong(5), rs.getString(6))).list();
        String nombre = jdbc.sql("SELECT nombre FROM usuarios WHERE id = :id").param("id", t.usuarioId()).query(String.class).single();
        String version = versionEsquema();
        OffsetDateTime fin = OffsetDateTime.now(reloj);
        YearMonth desde = YearMonth.from(k.desde());
        YearMonth hasta = YearMonth.from(k.hasta());
        String texto = AnaliticaExportacionHechos.leeme(new AnaliticaExportacionHechos.Leeme(t.id(), nombre + " (" + usuario + ")", k.creado(), fin,
                t.corte(), k.corteCargueId(), Arrays.asList(t.cargues()), desde, hasta, version, meses));
        String ruta = "analitica/hechos/" + t.id() + "_leeme_" + Huellas.secreto(12) + ".txt";
        try {
            antesDeLeeme.run();
            almacen.guardar(ruta, texto.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            LOG.error("[analitica] No se pudo guardar el LEEME de la exportación de hechos {}; se reintenta", t.id(), e);
            borrar(ruta); // pudo quedar a medias
            jdbc.sql("UPDATE ana_exportaciones_hechos SET estado = 'pendiente', error = :e WHERE id = :id AND estado = 'generando'")
                    .param("e", MENSAJE_FALLA_LEEME).param("id", t.id()).update();
            return false;
        }
        boolean ok = k.fallidos() == 0;
        jdbc.sql("""
                UPDATE ana_exportaciones_hechos SET estado = :estado, leeme_ruta = :leeme, version_esquema = :version, error = :error,
                       terminado = :fin, vence = :vence
                WHERE id = :id AND estado = 'generando'""")
                .param("estado", ok ? "lista" : "fallida").param("leeme", ruta).param("version", version)
                .param("error", ok ? null : MENSAJE_FALLA_TRABAJO).param("fin", fin).param("vence", fin.plus(propiedades.vigencia()))
                .param("id", t.id()).update();
        String resumen = AnaliticaExportacionHechos.resumen(t.id(), desde, hasta) + " · " + (meses.size() - k.fallidos()) + " de " + meses.size()
                + " meses · " + k.filas() + " filas";
        bitacora.registrar(ok ? "analitica_hechos_exportacion_generada" : "analitica_hechos_exportacion_fallida", resumen, t.usuarioId(), usuario);
        return true;
    }

    /** Versión de la última migración aplicada (LEEME); «desconocida» si no se puede leer. */
    private String versionEsquema() {
        try {
            return jdbc.sql("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")
                    .query(String.class).optional().orElse("desconocida");
        } catch (RuntimeException e) {
            LOG.warn("[analitica] No se pudo leer la versión del esquema: {}", e.getMessage());
            return "desconocida";
        }
    }

    // ---------- Revisión periódica ----------

    /** Cada minuto: borra las vencidas y despierta al ejecutor si quedó algo pendiente (tras un reinicio o un LEEME para reintentar). */
    @Scheduled(fixedDelayString = "${digsa.analitica.exportacion-hechos.revision-ms:60000}", initialDelayString = "${digsa.analitica.exportacion-hechos.revision-ms:60000}")
    public void revisar() {
        limpiar();
        if (jdbc.sql("SELECT count(*) FROM ana_exportaciones_hechos WHERE estado IN ('pendiente', 'generando')").query(Long.class).single() > 0) {
            despertar();
        }
    }

    /**
     * Vencimiento y borrado, idempotente (se puede caer en cualquier punto y la siguiente revisión termina el trabajo):
     * <ol>
     * <li>las listas o fallidas cuyo plazo pasó quedan «vencidas» (y se registra en la bitácora);</li>
     * <li>se borra cada archivo aún referenciado de una vencida (meses y LEEME) y de cada mes fallido, y su ruta se anula SOLO si se
     * comprobó que ya no está en el almacén.</li>
     * </ol>
     *
     * @return cuántas exportaciones vencieron en esta pasada
     */
    public int limpiar() {
        record Vencida(long id, long usuarioId, LocalDate desde, LocalDate hasta) {
        }
        List<Vencida> vencidas = jdbc.sql("""
                UPDATE ana_exportaciones_hechos SET estado = 'vencida'
                WHERE estado IN ('lista', 'fallida') AND vence <= :ahora
                RETURNING id, solicitada_por, desde, hasta""")
                .param("ahora", OffsetDateTime.now(reloj))
                .query((rs, n) -> new Vencida(rs.getLong(1), rs.getLong(2), rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class))).list();
        for (Vencida v : vencidas) {
            bitacora.registrar("analitica_hechos_exportacion_vencida",
                    AnaliticaExportacionHechos.resumen(v.id(), YearMonth.from(v.desde()), YearMonth.from(v.hasta())), v.usuarioId(), null);
        }
        // Meses: de exportaciones vencidas (cualquier estado del mes) y meses fallidos que conservaron la ruta por un borrado fallido
        record Archivo(long exportacionId, LocalDate mes, String ruta) {
        }
        List<Archivo> archivos = jdbc.sql("""
                SELECT m.exportacion_id, m.mes, m.ruta FROM ana_exportaciones_hechos_meses m JOIN ana_exportaciones_hechos e ON e.id = m.exportacion_id
                WHERE m.ruta IS NOT NULL AND (e.estado = 'vencida' OR m.estado = 'fallida')""")
                .query((rs, n) -> new Archivo(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getString(3))).list();
        for (Archivo a : archivos) {
            if (borrar(a.ruta())) {
                jdbc.sql("""
                        UPDATE ana_exportaciones_hechos_meses m SET ruta = NULL,
                               estado = CASE WHEN e.estado = 'vencida' AND m.estado = 'lista' THEN 'vencida' ELSE m.estado END
                        FROM ana_exportaciones_hechos e
                        WHERE e.id = m.exportacion_id AND m.exportacion_id = :id AND m.mes = :m AND m.ruta = :ruta""")
                        .param("id", a.exportacionId()).param("m", a.mes()).param("ruta", a.ruta()).update();
            }
        }
        record Leeme(long id, String ruta) {
        }
        for (Leeme l : jdbc.sql("SELECT id, leeme_ruta FROM ana_exportaciones_hechos WHERE estado = 'vencida' AND leeme_ruta IS NOT NULL")
                .query((rs, n) -> new Leeme(rs.getLong(1), rs.getString(2))).list()) {
            if (borrar(l.ruta())) {
                jdbc.sql("UPDATE ana_exportaciones_hechos SET leeme_ruta = NULL WHERE id = :id AND leeme_ruta = :ruta").param("id", l.id())
                        .param("ruta", l.ruta()).update();
            }
        }
        return vencidas.size();
    }
}
