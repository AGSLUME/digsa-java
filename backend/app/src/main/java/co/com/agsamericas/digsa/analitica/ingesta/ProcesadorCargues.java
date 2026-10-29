package co.com.agsamericas.digsa.analitica.ingesta;

import co.com.agsamericas.digsa.analitica.CargueAplicado;
import co.com.agsamericas.digsa.analitica.HomologacionCargue;
import co.com.agsamericas.digsa.analitica.PropiedadesAnalitica;
import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.AnaliticaIngesta;
import co.com.agsamericas.digsa.reglas.AnaliticaIngesta.Campo;
import co.com.agsamericas.digsa.reglas.AnaliticaIngesta.Causa;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Procesamiento diferido de los cargues (patrón de exportacion/): un ejecutor de un hilo toma el siguiente «pendiente» con FOR UPDATE
 * SKIP LOCKED bajo pg_try_advisory_lock, uno a la vez en todo el sistema; los demás esperan en cola. Etapas medidas: lectura y COPY,
 * validación, homologación de los datos originales nuevos (A2: su propia transacción, todo queda «pendiente del cargue»), carga de hechos
 * y agregados. Todo o nada: los hechos, los pasos de aplicación (agregados), la publicación de la homologación pendiente y el paso a
 * «aplicado» van en una sola transacción; si algo falla el cargue queda «fallido» con la etapa, lo pendiente de la homologación se borra y
 * nada se ve. El staging solo existe mientras se procesa (los lotes del servicio web esperan en el almacén, no en el staging UNLOGGED) y se
 * borra siempre al terminar.
 *
 * <p>Cada cambio de estado exige el estado anterior (UPDATE … WHERE estado = 'procesando'): lo que pase después del commit (bitácora,
 * estadísticas, evento) solo se registra si falla y jamás convierte un «aplicado» en «fallido» ni borra sus hechos. Un archivo cuyo
 * SHA-256 ya está aplicado (dos copias en cola) termina «duplicado» con la referencia al cargue existente.
 */
@Service
class ProcesadorCargues {

    private static final Logger LOG = LoggerFactory.getLogger(ProcesadorCargues.class);
    /** Llave del candado del ejecutor (pg_try_advisory_lock): un solo cargue en proceso entre réplicas. */
    static final long CANDADO = 0x414E414C49544943L;
    static final String MENSAJE_FALLA = "Error técnico; el cargue no se aplicó. Intente de nuevo o comuníquese con TI.";
    static final String INTERRUMPIDO = "Proceso interrumpido";
    private static final int CADA_FILAS = 50_000;

    /** Cargue que se procesa. */
    record Pedido(long id, String fuente, String nombre, String ruta, List<Campo> mapeo, String modo, LocalDate desde, LocalDate hasta,
                  Long usuarioId, String fuenteTexto, OffsetDateTime creado, OffsetDateTime recibido, long tamano, String sha256) {
    }

    /** El cargue terminó «duplicado» (ya se registró): el procesamiento se detiene sin más. */
    private static final class Duplicado extends RuntimeException {
        Duplicado() {
            super(null, null, false, false);
        }
    }

    private final JdbcClient jdbc;
    private final DataSource ds;
    private final Almacen almacen;
    private final Seudonimizador seudo;
    private final ValidadorStaging validador;
    private final AplicadorHechos aplicador;
    private final MantenimientoCargues mantenimiento;
    private final RegistroBitacora bitacora;
    private final ApplicationEventPublisher eventos;
    private final PropiedadesAnalitica propiedades;
    private final ObjectMapper json;
    private final ObjectMapper lectorJson;
    private final Clock reloj;
    private final TransactionTemplate tx;
    /** Etapa de homologación (analitica.homologacion); vacía si el módulo no la trae. */
    private final List<HomologacionCargue> homologacion;
    private final ExecutorService ejecutor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("digsa-analitica-cargues").daemon(true).factory());

    ProcesadorCargues(JdbcClient jdbc, DataSource ds, Almacen almacen, Seudonimizador seudo, ValidadorStaging validador, AplicadorHechos aplicador,
                      MantenimientoCargues mantenimiento, RegistroBitacora bitacora, ApplicationEventPublisher eventos, PropiedadesAnalitica propiedades,
                      ObjectMapper json, Clock reloj, PlatformTransactionManager transacciones, List<HomologacionCargue> homologacion) {
        this.homologacion = List.copyOf(homologacion);
        this.jdbc = jdbc;
        this.ds = ds;
        this.almacen = almacen;
        this.seudo = seudo;
        this.validador = validador;
        this.aplicador = aplicador;
        this.mantenimiento = mantenimiento;
        this.bitacora = bitacora;
        this.eventos = eventos;
        this.propiedades = propiedades;
        this.json = json;
        // Valores con decimales exactos (pesos) de los lotes del servicio web: nunca pasan por double
        this.lectorJson = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.reloj = reloj;
        this.tx = new TransactionTemplate(transacciones);
    }

    @PreDestroy
    void detener() {
        ejecutor.shutdownNow();
    }

    /** Revisa la cola sin esperar (corre en el hilo del ejecutor). */
    void despertar() {
        try {
            ejecutor.execute(this::correr);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            LOG.warn("[analitica] El ejecutor de cargues está detenido");
        }
    }

    /** Cada minuto (y tras un reinicio): si quedó algo pendiente, despierta al ejecutor. */
    @Scheduled(fixedDelayString = "${digsa.analitica.revision-ms:60000}", initialDelayString = "${digsa.analitica.revision-inicial-ms:30000}")
    void revisar() {
        if (jdbc.sql("SELECT count(*) FROM ana_cargues WHERE estado IN ('pendiente', 'procesando')").query(Long.class).single() > 0) {
            despertar();
        }
    }

    void correr() {
        try (Connection c = ds.getConnection()) {
            if (!candado(c, "SELECT pg_try_advisory_lock(?)")) {
                return; // otra réplica está procesando: al terminar toma los pendientes
            }
            try {
                huerfanos();
                for (Optional<Pedido> p = siguiente(); p.isPresent(); p = siguiente()) {
                    procesar(p.get());
                    if (!c.isValid(5)) {
                        break;
                    }
                }
            } finally {
                candado(c, "SELECT pg_advisory_unlock(?)");
            }
        } catch (SQLException | RuntimeException e) {
            LOG.error("[analitica] El ejecutor de cargues falló", e);
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

    /** Con el candado tomado, un «procesando» es de un proceso que se cayó: queda fallido, sin hechos y sin staging. */
    private void huerfanos() {
        List<Long> ids = jdbc.sql("""
                UPDATE ana_cargues SET estado = 'fallido', error = :error, terminado = :ahora WHERE estado = 'procesando' RETURNING id""")
                .param("error", INTERRUMPIDO).param("ahora", OffsetDateTime.now(reloj)).query(Long.class).list();
        for (long id : ids) {
            limpiar(id);
            bitacora.registrar("analitica_cargue_fallido", "Cargue " + id + " · " + INTERRUMPIDO, null, null);
        }
    }

    private Optional<Pedido> siguiente() {
        return jdbc.sql("""
                UPDATE ana_cargues c SET estado = 'procesando', iniciado = :ahora, etapa = 'lectura', porcentaje = 0, filas_leidas = 0, error = NULL
                WHERE c.id = (SELECT id FROM ana_cargues WHERE estado = 'pendiente' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING c.id, c.fuente, c.nombre_archivo, c.almacen_ruta, c.mapeo::text, c.modo, c.declarado_desde, c.declarado_hasta,
                          c.usuario_id, c.fuente_texto, c.creado, c.recibido, coalesce(c.tamano_bytes, 0), c.sha256""")
                .param("ahora", OffsetDateTime.now(reloj))
                .query((rs, n) -> new Pedido(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), mapeo(rs.getString(5)),
                        rs.getString(6), fecha(rs.getDate(7)), fecha(rs.getDate(8)), (Long) rs.getObject(9), rs.getString(10),
                        rs.getObject(11, OffsetDateTime.class), rs.getObject(12, OffsetDateTime.class), rs.getLong(13),
                        rs.getString(14) == null ? null : rs.getString(14).strip()))
                .optional();
    }

    private List<Campo> mapeo(String texto) {
        try {
            String[] claves = json.readValue(texto == null ? "[]" : texto, String[].class);
            List<Campo> l = new ArrayList<>(claves.length);
            for (String k : claves) {
                l.add(k == null ? Campo.IGNORAR : Campo.deClave(k).orElse(Campo.IGNORAR));
            }
            return l;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Mapeo ilegible", e);
        }
    }

    private static LocalDate fecha(java.sql.Date d) {
        return d == null ? null : d.toLocalDate();
    }

    // ---------- Un cargue ----------

    private void procesar(Pedido p) {
        long inicio = System.nanoTime();
        Map<String, Long> etapas = new LinkedHashMap<>();
        if (p.creado() != null && p.recibido() != null) {
            etapas.put("subida", Math.max(0, Duration.between(p.creado(), p.recibido()).toMillis()));
        }
        String[] etapa = {"lectura"};
        long[] recibidos = {0};
        ValidadorStaging.Resultado r;
        List<Long> reemplaza;
        AplicadorHechos.Carga carga;
        OffsetDateTime ahora;
        try {
            if (!seudo.disponible()) {
                fallido(p, "lectura", ms(inicio), etapas, Seudonimizador.MENSAJE_SIN_CLAVE);
                return;
            }
            if (p.sha256() != null) {
                verificarDuplicado(p, p.sha256());
            }
            long t = System.nanoTime();
            if ("archivo".equals(p.fuente())) {
                leerArchivo(p, recibidos);
            } else {
                leerEnvio(p, recibidos);
            }
            if (recibidos[0] == 0) {
                throw new ErrorEstructura(Causa.ARCHIVO_VACIO, "El insumo no trae registros de datos", "1", 0);
            }
            jdbc.sql("SELECT ana_staging_analizar(:c)").param("c", p.id()).query().singleRow(); // la validación se planifica con datos reales
            etapas.put("lectura", ms(t));

            etapa[0] = "validacion";
            t = System.nanoTime();
            avance(p.id(), "validacion", 60, recibidos[0]);
            reemplaza = "reemplazar".equals(p.modo()) ? objetivosReemplazo(jdbc, p.fuente(), p.id(), p.desde(), p.hasta()) : List.of();
            r = validador.validar(p.id(), LocalDate.now(reloj), p.desde(), p.hasta(), AnaliticaIngesta.llavePorId(p.mapeo()), reemplaza,
                    propiedades.registrosPorLoteValidacion(), pct -> avance(p.id(), "validacion", 60 + pct / 5, recibidos[0]));
            etapas.put("validacion", ms(t));

            // Homologación de los datos originales nuevos (pares distintos del staging) antes de cargar los hechos (A2)
            etapa[0] = "homologacion";
            t = System.nanoTime();
            avance(p.id(), "homologacion", 81, recibidos[0]);
            for (HomologacionCargue h : homologacion) {
                h.homologar(p.id());
            }
            etapas.put("homologacion", ms(t));

            etapa[0] = "hechos";
            t = System.nanoTime();
            avance(p.id(), "hechos", 82, recibidos[0]);
            AplicadorHechos.asegurarParticiones(jdbc, AplicadorHechos.mesesValidos(jdbc, p.id()));
            ahora = OffsetDateTime.now(reloj);
            long[] finHechos = {0};
            long tHechos = t;
            ValidadorStaging.Resultado rv = r;
            List<Long> rem = reemplaza;
            OffsetDateTime corte = ahora;
            try {
                carga = tx.execute(s -> {
                    AplicadorHechos.Carga c = aplicador.cargar(jdbc, p.id(), rem, corte, () -> {
                        etapas.put("hechos", ms(tHechos));
                        finHechos[0] = System.nanoTime();
                        etapa[0] = "agregados";
                    });
                    etapas.put("agregados", ms(finHechos[0] == 0 ? tHechos : finHechos[0]));
                    if (recibidos[0] != c.procesados() + rv.rechazados()) {
                        throw new IllegalStateException("Los conteos no cuadran: " + recibidos[0] + " ≠ " + c.procesados() + " + " + rv.rechazados());
                    }
                    if (!homologacion.isEmpty() && c.sinMedicamento() > 0) {
                        // Contrato con la homologación: todo hecho queda enlazado a su dato original (cadena completa, A5 L691)
                        etapa[0] = "hechos";
                        throw new FallaVisible("Etapa hechos: " + c.sinMedicamento() + " registros quedaron sin dato original homologado; el cargue "
                                + "no se aplicó. Comuníquese con TI.");
                    }
                    // Homologación pendiente del cargue: se publica y confirma aquí, en la misma transacción que lo marca «aplicado»
                    etapa[0] = "homologacion";
                    long tPub = System.nanoTime();
                    for (HomologacionCargue h : homologacion) {
                        h.alAplicar(p.id());
                    }
                    etapas.merge("homologacion", ms(tPub), Long::sum);
                    etapa[0] = "agregados";
                    int n = jdbc.sql("""
                            UPDATE ana_cargues SET estado = 'aplicado', aplicado_en = :ahora, terminado = :ahora, etapa = 'agregados', porcentaje = 100,
                                   filas_leidas = :recibidos, recibidos = :recibidos, procesados = :procesados, rechazados = :rechazados,
                                   esm_sin_correspondencia = :sinEsm, periodo_desde = :desde, periodo_hasta = :hasta,
                                   motivos = CAST(:motivos AS jsonb), motivo_texto = :motivo, etapas_ms = CAST(:etapas AS jsonb), duracion_ms = :duracion
                            WHERE id = :id AND estado = 'procesando'""")
                            .param("ahora", corte).param("recibidos", recibidos[0]).param("procesados", c.procesados()).param("rechazados", rv.rechazados())
                            .param("sinEsm", c.esmSinCorrespondencia()).param("desde", c.desde()).param("hasta", c.hasta())
                            .param("motivos", aJson(rv.motivos())).param("motivo", cortar(AnaliticaIngesta.motivo(rv.motivos()), 2000))
                            .param("etapas", aJson(etapas)).param("duracion", ms(inicio)).param("id", p.id()).update();
                    if (n != 1) {
                        throw new IllegalStateException("El cargue " + p.id() + " ya no estaba en proceso");
                    }
                    return c;
                });
            } catch (DuplicateKeyException e) {
                // Otro cargue con el mismo SHA-256 quedó aplicado mientras este esperaba: la transacción revirtió, nada se aplicó
                verificarDuplicado(p, p.sha256());
                throw e;
            }
        } catch (Duplicado e) {
            return;
        } catch (ErrorEstructura e) {
            etapas.put(etapa[0], ms(inicio));
            rechazar(p, e, recibidos[0], etapas, ms(inicio));
            return;
        } catch (Exception e) {
            LOG.error("[analitica] El cargue {} falló en la etapa {}", p.id(), etapa[0], e);
            fallido(p, etapa[0], ms(inicio), etapas, e instanceof FallaVisible ? e.getMessage() : null);
            return;
        }
        // Ya confirmado: lo que sigue solo se registra si falla (nunca vuelve el cargue «fallido» ni borra sus hechos)
        despuesDeAplicar(p, recibidos[0], r, carga, ahora, reemplaza, etapas, inicio);
    }

    private void despuesDeAplicar(Pedido p, long recibidos, ValidadorStaging.Resultado r, AplicadorHechos.Carga carga, OffsetDateTime ahora,
                                  List<Long> reemplaza, Map<String, Long> etapas, long inicio) {
        limpiarStaging(p.id());
        try {
            jdbc.sql("SELECT ana_analizar_particiones(:d, :h)").param("d", carga.desde()).param("h", carga.hasta()).query(Integer.class).single();
        } catch (RuntimeException e) {
            LOG.warn("[analitica] No se pudieron actualizar las estadísticas de las particiones del cargue {}: {}", p.id(), e.getMessage());
        }
        try {
            bitacora.registrar("analitica_cargue_aplicado", "Cargue " + p.id() + " · " + p.fuenteTexto() + " · " + recibidos + " recibidos · "
                    + r.rechazados() + " rechazados", p.usuarioId(), usuario(p.usuarioId()));
        } catch (RuntimeException e) {
            LOG.error("[analitica] El cargue {} quedó aplicado pero no se pudo registrar en la bitácora", p.id(), e);
        }
        LOG.info("[analitica] Cargue {} aplicado: {} recibidos, {} procesados, {} rechazados en {} ms {}", p.id(), recibidos, carga.procesados(),
                r.rechazados(), ms(inicio), etapas);
        publicar(new CargueAplicado(p.id(), p.fuente(), ahora, carga.desde(), carga.hasta(), carga.procesados(), reemplaza));
    }

    /** Si el SHA-256 ya está en un cargue aplicado vigente, este termina «duplicado» con la referencia y se detiene el proceso. */
    private void verificarDuplicado(Pedido p, String sha) {
        if (sha == null) {
            return;
        }
        Long existente = jdbc.sql("""
                SELECT id FROM ana_cargues WHERE sha256 = :sha AND estado = 'aplicado' AND reemplazado_por IS NULL AND id <> :c""")
                .param("sha", sha).param("c", p.id()).query(Long.class).optional().orElse(null);
        if (existente == null) {
            return;
        }
        List<String> ruta = jdbc.sql("""
                UPDATE ana_cargues SET estado = 'duplicado', duplicado_de = :d, terminado = :ahora, etapa = NULL,
                       motivo_texto = :motivo WHERE id = :id AND estado = 'procesando' RETURNING almacen_ruta""")
                .param("d", existente).param("ahora", OffsetDateTime.now(reloj)).param("motivo", "Duplicado de #" + existente + ": el mismo archivo "
                        + "ya se aplicó en ese cargue").param("id", p.id()).query(String.class).list();
        if (!ruta.isEmpty()) {
            limpiarStaging(p.id());
            descartarHomologacion(p.id());
            mantenimiento.borrarOriginal(p.id(), ruta.getFirst(), "duplicado");
            bitacora.registrar("analitica_cargue_duplicado", "Cargue " + p.id() + " · duplicado de #" + existente, p.usuarioId(), usuario(p.usuarioId()));
        }
        throw new Duplicado();
    }

    /** Lee el archivo del almacén y lo copia al staging, normalizado y seudonimizado. */
    private void leerArchivo(Pedido p, long[] recibidos) throws IOException, SQLException {
        jdbc.sql("SELECT ana_staging_borrar(:c)").param("c", p.id()).query().singleRow();
        jdbc.sql("SELECT ana_staging_crear(:c)").param("c", p.id()).query().singleRow();
        long total = Math.max(1, p.tamano());
        ArchivoDispensacion.Limites limites = new ArchivoDispensacion.Limites(propiedades.celdaMax(), propiedades.registroMax(),
                propiedades.textosXlsxMaxBytes());
        try (ArchivoDispensacion a = ArchivoDispensacion.abrir(() -> almacen.leer(p.ruta()), p.nombre(), propiedades.archivoMaxBytes(), limites,
                bytes -> avance(p.id(), "lectura", (int) Math.min(58, 1 + bytes * 57 / total), recibidos[0]));
             Connection c = ds.getConnection()) {
            if (a.encabezado().size() != p.mapeo().size()) {
                throw new IllegalStateException("El encabezado cambió desde el mapeo");
            }
            CopiadorStaging cp = new CopiadorStaging(c, p.id(), p.mapeo(), a.comaDecimal(), "archivo", seudo);
            try {
                a.recorrer((orden, linea, celdas) -> {
                    cp.agregar(orden, linea, celdas);
                    recibidos[0] = cp.filas();
                    if (cp.filas() % CADA_FILAS == 0) {
                        jdbc.sql("UPDATE ana_cargues SET filas_leidas = :f WHERE id = :id").param("f", cp.filas()).param("id", p.id()).update();
                    }
                });
                cp.close();
            } catch (IOException | RuntimeException e) {
                cp.cancelar();
                throw e;
            }
            recibidos[0] = cp.filas();
            if (AnaliticaIngesta.excedeUmbral(cp.malFormadas(), cp.filas(), propiedades.umbral())) {
                throw new ErrorEstructura(Causa.CAMPOS_DISTINTOS, AnaliticaIngesta.motivoCamposDistintos(cp.malFormadas(), cp.primeraMalFormada()),
                        cp.primeraMalFormada(), cp.malFormadas());
            }
        }
    }

    /**
     * Servicio web: los lotes del envío completo (1..N, guardados en el almacén con su SHA-256) se serializan en un solo archivo (el
     * original del envío, con su SHA-256) y a la vez se copian al staging. Los registros copiados deben ser la suma de los registros de
     * los lotes; si no, el cargue falla con causa explícita. Luego los lotes sueltos se borran del almacén.
     */
    private void leerEnvio(Pedido p, long[] recibidos) throws IOException, SQLException {
        record LoteGuardado(int numero, int registros, String ruta) {
        }
        List<LoteGuardado> lotes = jdbc.sql("SELECT numero, registros, almacen_ruta FROM ana_ws_lotes WHERE cargue_id = :c ORDER BY numero")
                .param("c", p.id()).query((rs, n) -> new LoteGuardado(rs.getInt(1), rs.getInt(2), rs.getString(3))).list();
        long esperados = 0;
        for (int i = 0; i < lotes.size(); i++) {
            if (lotes.get(i).numero() != i + 1 || lotes.get(i).ruta() == null) {
                throw new IllegalStateException("Falta el lote " + (i + 1) + " del envío en el almacén");
            }
            esperados += lotes.get(i).registros();
        }
        jdbc.sql("SELECT ana_staging_borrar(:c)").param("c", p.id()).query().singleRow();
        jdbc.sql("SELECT ana_staging_crear(:c)").param("c", p.id()).query().singleRow();
        String ruta = "analitica/ws/" + p.id() + "/envio_" + co.com.agsamericas.digsa.reglas.Huellas.secreto(8) + ".json";
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        long tamano = 0;
        boolean listo = false;
        try (OutputStream out = almacen.escribir(ruta); DigestOutputStream d = new DigestOutputStream(out, md); Connection c = ds.getConnection()) {
            CopiadorStaging cp = new CopiadorStaging(c, p.id(), ServicioWebOpl.CAMPOS, false, "ws_opl", seudo);
            try {
                long orden = 0;
                for (LoteGuardado l : lotes) {
                    byte[] cuerpo;
                    try (InputStream in = almacen.leer(l.ruta())) {
                        cuerpo = in.readAllBytes();
                    }
                    d.write(cuerpo);
                    d.write('\n');
                    tamano += cuerpo.length + 1;
                    int i = 0;
                    for (JsonNode reg : lectorJson.readTree(cuerpo).path("registros")) {
                        i++;
                        cp.agregar(++orden, "lote " + l.numero() + " · registro " + i, ServicioWebOpl.celdas(reg));
                    }
                    recibidos[0] = cp.filas();
                }
                cp.close();
            } catch (IOException | RuntimeException e) {
                cp.cancelar();
                throw e;
            }
            recibidos[0] = cp.filas();
            listo = true;
        } finally {
            if (!listo) {
                almacen.descartar(ruta);
            }
        }
        if (recibidos[0] != esperados) {
            almacen.descartar(ruta);
            throw new IllegalStateException("Los registros de los lotes guardados (" + recibidos[0] + ") no cuadran con los recibidos (" + esperados + ")");
        }
        String sha = HexFormat.of().formatHex(md.digest());
        jdbc.sql("UPDATE ana_cargues SET sha256 = :sha, tamano_bytes = :t, almacen_ruta = :r WHERE id = :id AND estado = 'procesando'")
                .param("sha", sha).param("t", tamano).param("r", ruta).param("id", p.id()).update();
        mantenimiento.descartarLotes(p.id()); // ya están en el envío serializado
        verificarDuplicado(p, sha);
    }

    /**
     * «Reemplazar el periodo»: cargues aplicados vigentes de la misma fuente cuyo periodo queda contenido en el periodo DECLARADO (nunca
     * en el rango de fechas del archivo: una fecha mal digitada ocultaría años de cargues). Sin periodo declarado no se reemplaza nada.
     */
    static List<Long> objetivosReemplazo(JdbcClient jdbc, String fuente, long cargueId, LocalDate desde, LocalDate hasta) {
        if (desde == null || hasta == null) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT id FROM ana_cargues WHERE fuente = :fuente AND estado = 'aplicado' AND reemplazado_por IS NULL AND id <> :c
                  AND periodo_desde >= :desde AND periodo_hasta <= :hasta ORDER BY id""")
                .param("fuente", fuente).param("c", cargueId).param("desde", desde).param("hasta", hasta).query(Long.class).list();
    }

    private void avance(long id, String etapa, int porcentaje, long filas) {
        try {
            jdbc.sql("UPDATE ana_cargues SET etapa = :e, porcentaje = :p, filas_leidas = :f WHERE id = :id AND estado = 'procesando'")
                    .param("e", etapa).param("p", Math.max(0, Math.min(100, porcentaje))).param("f", filas).param("id", id).update();
        } catch (RuntimeException e) {
            LOG.warn("[analitica] No se pudo actualizar el avance del cargue {}: {}", id, e.getMessage());
        }
    }

    /** Rechazo estructural: el archivo completo, sin hechos; recibidos = leídos, procesados = 0, rechazados = recibidos. */
    private void rechazar(Pedido p, ErrorEstructura e, long recibidos, Map<String, Long> etapas, long duracion) {
        try {
            Map<String, Long> motivos = Map.of(e.causa().codigo(), Math.max(recibidos, e.cuantas()));
            // Estado y línea del rechazo en una sola transacción: quien ve «rechazado» ya ve la línea (antes se veía un instante sin ella)
            Integer n = tx.execute(s -> {
                int u = jdbc.sql("""
                        UPDATE ana_cargues SET estado = 'rechazado', terminado = :ahora, recibidos = :r, procesados = 0, rechazados = :r, filas_leidas = :r,
                           motivos = CAST(:motivos AS jsonb), motivo_texto = :motivo, etapas_ms = CAST(:etapas AS jsonb), duracion_ms = :d
                    WHERE id = :id AND estado = 'procesando'""")
                    .param("ahora", OffsetDateTime.now(reloj)).param("r", recibidos).param("motivos", aJson(motivos))
                    .param("motivo", cortar(e.getMessage(), 2000)).param("etapas", aJson(etapas)).param("d", duracion).param("id", p.id()).update();
                if (u == 1) {
                    jdbc.sql("DELETE FROM ana_cargue_rechazos WHERE cargue_id = :c").param("c", p.id()).update();
                    jdbc.sql("INSERT INTO ana_cargue_rechazos (cargue_id, orden, linea, causa, columna) VALUES (:c, 0, :l, :causa, NULL)")
                            .param("c", p.id()).param("l", cortar(e.linea() == null ? "1" : e.linea(), 40)).param("causa", e.causa().codigo()).update();
                }
                return u;
            });
            if (n == null || n != 1) {
                LOG.warn("[analitica] El cargue {} ya no estaba en proceso al rechazarlo", p.id());
                return;
            }
            limpiar(p.id());
            bitacora.registrar("analitica_cargue_rechazado", "Cargue " + p.id() + " · " + p.fuenteTexto() + " · " + recibidos + " recibidos · rechazo de estructura: "
                    + e.causa().texto(), p.usuarioId(), usuario(p.usuarioId()));
        } catch (RuntimeException ex) {
            LOG.error("[analitica] No se pudo registrar el rechazo del cargue {}", p.id(), ex);
        }
    }

    /**
     * Falla técnica: el cargue pasa a «fallido» solo si sigue «procesando» (nunca un aplicado) y solo entonces se limpian sus hechos y su
     * staging.
     *
     * @param mensaje mensaje para el usuario (o null: el genérico con la etapa)
     */
    private void fallido(Pedido p, String etapa, long duracion, Map<String, Long> etapas, String mensaje) {
        try {
            int n = jdbc.sql("""
                    UPDATE ana_cargues SET estado = 'fallido', etapa = :etapa, error = :error, terminado = :ahora, etapas_ms = CAST(:etapas AS jsonb),
                           duracion_ms = :d WHERE id = :id AND estado = 'procesando'""")
                    .param("etapa", etapa).param("error", cortar(mensaje != null ? mensaje : "Etapa " + etapa + ": " + MENSAJE_FALLA, 500))
                    .param("ahora", OffsetDateTime.now(reloj)).param("etapas", aJson(etapas)).param("d", duracion).param("id", p.id()).update();
            if (n != 1) {
                LOG.warn("[analitica] El cargue {} ya no estaba en proceso: no se marca fallido", p.id());
                return;
            }
            limpiar(p.id());
            bitacora.registrar("analitica_cargue_fallido", "Cargue " + p.id() + " · " + p.fuenteTexto() + " · etapa " + etapa, p.usuarioId(),
                    usuario(p.usuarioId()));
        } catch (RuntimeException e) {
            LOG.error("[analitica] No se pudo marcar fallido el cargue {}", p.id(), e);
        }
    }

    /** Hechos y llaves que hubieran quedado de un cargue que NO está aplicado (no debería haber: la carga es una transacción) y su staging. */
    private void limpiar(long id) {
        try {
            jdbc.sql("""
                    DELETE FROM ana_dispensaciones WHERE cargue_id = :c AND EXISTS (SELECT 1 FROM ana_cargues WHERE id = :c AND estado <> 'aplicado')""")
                    .param("c", id).update();
            jdbc.sql("DELETE FROM ana_llaves WHERE cargue_id = :c AND EXISTS (SELECT 1 FROM ana_cargues WHERE id = :c AND estado <> 'aplicado')")
                    .param("c", id).update();
        } catch (RuntimeException e) {
            LOG.error("[analitica] No se pudieron borrar los hechos del cargue {}", id, e);
        }
        descartarHomologacion(id);
        limpiarStaging(id);
    }

    /** Lo pendiente de la homologación (datos originales nuevos, versión sin publicar, pares y meses) de un cargue que no se aplicó. */
    private void descartarHomologacion(long id) {
        for (HomologacionCargue h : homologacion) {
            h.descartar(id);
        }
    }

    /** Falla con un mensaje propio para el usuario (en lugar del genérico de la etapa). */
    static final class FallaVisible extends IllegalStateException {
        FallaVisible(String mensaje) {
            super(mensaje);
        }
    }

    private void limpiarStaging(long id) {
        mantenimiento.borrarStaging(id);
    }

    private String usuario(Long id) {
        return id == null ? null : jdbc.sql("SELECT usuario FROM usuarios WHERE id = :id").param("id", id).query(String.class).optional().orElse(null);
    }

    private void publicar(CargueAplicado evento) {
        try {
            eventos.publishEvent(evento);
        } catch (RuntimeException e) {
            LOG.error("[analitica] Falló un oyente del cargue aplicado {}", evento.cargueId(), e);
        }
    }

    private String aJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long ms(long desdeNanos) {
        return (System.nanoTime() - desdeNanos) / 1_000_000;
    }

    static String cortar(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
