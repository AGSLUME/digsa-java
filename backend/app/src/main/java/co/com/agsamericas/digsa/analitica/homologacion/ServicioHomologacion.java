package co.com.agsamericas.digsa.analitica.homologacion;

import co.com.agsamericas.digsa.analitica.HomologacionCargue;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.DatoFinal;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.Estado;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.Origen;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.Paso;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.Referencias;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.Resultado;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.ResultadoCie10;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ejecución de la homologación (analitica-homologacion, «Tabla maestra versionada» y «Enlace con los hechos»), con versionado por
 * diferencia: cada versión guarda solo las filas que calculó y cambiaron (huella SHA-256 del contenido); la versión completa la arma la
 * función SQL hom_tabla_maestra_version con la capa de decisiones humanas de «Revisión» encima. Las versiones se publican de a una
 * (pg_advisory_xact_lock {@link #CANDADO}): el cargue, la revisión y la re-ejecución nunca se cruzan.
 *
 * <p>Como etapa del cargue ({@link HomologacionCargue}): lee los pares (CUM, medicamento) del staging en una sola pasada, registra los
 * datos originales nuevos como pendientes del cargue y homologa solo esos (más los que comparten su descripción, por R-040) en una
 * versión pendiente; al aplicar el cargue la publica y hace el seguimiento de las devoluciones; si el cargue no se aplica, la borra.
 *
 * <p>La re-ejecución completa (pantalla) es diferida: un ejecutor de un hilo con estado consultable ({@link #estadoReejecucion()}).
 */
@Service
class ServicioHomologacion implements HomologacionCargue {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioHomologacion.class);
    /** Candado de las versiones de la tabla maestra (pg_advisory_xact_lock). */
    static final long CANDADO = 0x484F4D5F56455253L;
    /** Espera máxima del candado en una petición de la pantalla (aprobar, devolver, catálogos): luego 409. */
    static final Duration ESPERA_PANTALLA = Duration.ofSeconds(5);
    /** Espera máxima del candado de la re-ejecución diferida. */
    static final Duration ESPERA_REEJECUCION = Duration.ofSeconds(60);

    /** Columnas calculadas de una fila de la tabla maestra (sin version_id, origen_id, devuelto ni revision_id), en el orden de {@link #fila}. */
    static final String COLUMNAS = "orig_cum, orig_descripcion, orig_principio, orig_concentracion, orig_forma, orig_presentacion, orig_unidad, "
            + "orig_ssfm, orig_atc, fuente_codigo, fuente_version_id, fuente_fecha_corte, regla, causa, reglas_aplicadas, fin_nombre_comercial, "
            + "fin_dci, fin_principio_activo, fin_concentracion, fin_forma, fin_presentacion, fin_unidad, fin_cum, fin_codigo_ssfm, fin_atc, "
            + "fin_grupo_farmacologico, fin_registro_sanitario, estado, diferencias, candidatos, huella";

    /** 2 llaves + 14 columnas + reglas_aplicadas (jsonb) + 16 columnas (la última, la huella). */
    private static final String INSERTAR_FILA = "INSERT INTO hom_tabla_maestra (version_id, origen_id, " + COLUMNAS + ") VALUES (?, ?, "
            + "?, ".repeat(14) + "CAST(? AS jsonb), " + "?, ".repeat(15) + "?)";

    /** Fila calculada vigente (sin decisiones) de los datos originales dados: la de la versión publicada de mayor número. */
    private static final String BASE = """
            SELECT DISTINCT ON (t.origen_id) t.origen_id, t.huella, t.estado FROM hom_tabla_maestra t JOIN hom_version hv ON hv.id = t.version_id
            WHERE hv.estado = 'publicada' AND (CAST(:todos AS boolean) OR t.origen_id = ANY(:ids)) ORDER BY t.origen_id, hv.numero DESC""";

    /** Resumen de una versión creada. */
    record Ejecucion(int versionId, int numero, int origenes, Map<String, Long> conteo, Map<String, Long> antes, int cambios, int filas,
                     long duracionMs) {
    }

    /** Estado de la re-ejecución diferida (en memoria de esta instancia; el candado de la base protege entre instancias). */
    record EstadoReejecucion(String estado, int porcentaje, String fase, OffsetDateTime inicio, OffsetDateTime fin, String responsable,
                             Ejecucion resultado, String error) {
        static final EstadoReejecucion NINGUNA = new EstadoReejecucion("ninguna", 0, null, null, null, null, null, null);

        boolean enCurso() {
            return "en_curso".equals(estado);
        }
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate plantilla;
    private final ObjectMapper json;
    private final RegistroBitacora bitacora;
    private final Clock reloj;
    private final TransactionTemplate tx;
    private final ExecutorService ejecutor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("digsa-homologacion").daemon(true).factory());
    private volatile EstadoReejecucion reejecucion = EstadoReejecucion.NINGUNA;

    ServicioHomologacion(JdbcClient jdbc, JdbcTemplate plantilla, ObjectMapper json, RegistroBitacora bitacora, Clock reloj,
                         PlatformTransactionManager transacciones) {
        this.jdbc = jdbc;
        this.plantilla = plantilla;
        this.json = json;
        this.bitacora = bitacora;
        this.reloj = reloj;
        this.tx = new TransactionTemplate(transacciones);
    }

    @PreDestroy
    void detener() {
        ejecutor.shutdownNow();
    }

    // ---------- Candado ----------

    /** Candado de las versiones sin límite de espera (procesos en segundo plano: cargue y re-ejecución ya en su hilo). */
    void candado() {
        jdbc.sql("SELECT pg_advisory_xact_lock(:k)").param("k", CANDADO).query().singleRow();
    }

    /**
     * Candado con espera máxima (lock_timeout): si otra homologación lo tiene más tiempo, 409 con un mensaje claro en vez de dejar la
     * petición colgada ocupando una conexión. Debe llamarse dentro de una transacción.
     */
    static void candado(JdbcClient jdbc, Duration espera) {
        jdbc.sql("SELECT set_config('lock_timeout', :t, true)").param("t", espera.toMillis() + "ms").query().singleRow();
        try {
            jdbc.sql("SELECT pg_advisory_xact_lock(:k)").param("k", CANDADO).query().singleRow();
        } catch (DataAccessException e) {
            if (esperaAgotada(e)) {
                throw ErrorNegocio.conflicto("Hay una homologación en curso (re-ejecución, cargue o decisión de otra persona); intente de nuevo en "
                        + "unos minutos.");
            }
            throw e;
        }
        jdbc.sql("SELECT set_config('lock_timeout', '0', true)").query().singleRow();
    }

    private static boolean esperaAgotada(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && "55P03".equals(s.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    // ---------- Etapa del cargue ----------

    /** Par crudo (CUM, medicamento) del staging. */
    private record Par(String cum, String medicamento) {
    }

    /** Lo que el cargue trae de un par: registros por mes, fecha más reciente y conteo de cada atributo. */
    private static final class DatosPar {
        final Map<LocalDate, Long> porMes = new TreeMap<>();
        final Map<String, Long> principios = new HashMap<>();
        final Map<String, Long> atcs = new HashMap<>();
        final Map<String, Long> ssfms = new HashMap<>();
        long registros;
        LocalDate ultima;
    }

    /** Pares del staging y códigos CIE-10 distintos, leídos en una sola pasada (GROUPING SETS). */
    private record Lectura(Map<Par, DatosPar> pares, List<String> cie10) {
    }

    private Lectura leerStaging(long cargueId) {
        Map<Par, DatosPar> pares = new LinkedHashMap<>();
        List<String> cie10 = new ArrayList<>();
        jdbc.sql("""
                SELECT GROUPING(x.mes) AS gm, GROUPING(x.p) AS gp, GROUPING(x.a) AS ga, GROUPING(x.ss) AS gs, GROUPING(x.c) AS gc,
                       x.cum, x.med, x.mes, x.p, x.a, x.ss, x.c, count(*) AS n, max(x.f) AS ultima
                FROM (SELECT y.cum, y.med, y.fecha::date AS f, date_trunc('month', y.fecha::date)::date AS mes, y.p, y.a, y.ss, y.c
                      -- OFFSET 0: la fecha se convierte solo en los registros válidos (los rechazados pueden traer «2025-13-45»)
                      FROM (SELECT coalesce(s.cum_original, '') AS cum, coalesce(s.medicamento, '') AS med, s.fecha, s.principio_activo AS p,
                                   s.atc AS a, s.codigo_ssfm AS ss, s.cie10 AS c
                            FROM ana_staging_dispensacion s
                            JOIN par_fuerzas pf ON pf.codigo = s.fuerza
                            WHERE s.cargue_id = :c AND NOT s.campos_distintos
                              AND NOT EXISTS (SELECT 1 FROM ana_cargue_rechazos r WHERE r.cargue_id = :c AND r.orden = s.linea)
                            OFFSET 0) y) x
                GROUP BY GROUPING SETS ((x.cum, x.med, x.mes), (x.cum, x.med, x.p), (x.cum, x.med, x.a), (x.cum, x.med, x.ss), (x.c))""")
                .param("c", cargueId).query(rs -> {
                    long n = rs.getLong("n");
                    if (rs.getInt("gc") == 0) {
                        String c = rs.getString("c");
                        if (c != null) {
                            cie10.add(c);
                        }
                        return;
                    }
                    DatosPar d = pares.computeIfAbsent(new Par(rs.getString("cum"), rs.getString("med")), k -> new DatosPar());
                    if (rs.getInt("gm") == 0) {
                        d.porMes.merge(rs.getDate("mes").toLocalDate(), n, Long::sum);
                        d.registros += n;
                        LocalDate u = rs.getDate("ultima").toLocalDate();
                        d.ultima = d.ultima == null || u.isAfter(d.ultima) ? u : d.ultima;
                    } else if (rs.getInt("gp") == 0) {
                        d.principios.merge(Objects.requireNonNullElse(rs.getString("p"), ""), n, Long::sum);
                    } else if (rs.getInt("ga") == 0) {
                        d.atcs.merge(Objects.requireNonNullElse(rs.getString("a"), ""), n, Long::sum);
                    } else if (rs.getInt("gs") == 0) {
                        d.ssfms.merge(Objects.requireNonNullElse(rs.getString("ss"), ""), n, Long::sum);
                    }
                });
        cie10.sort(null);
        return new Lectura(pares, cie10);
    }

    /** Dato original del cargue (una llave): sus pares y los atributos elegidos de forma determinista. */
    private record DatoCargue(String llave, String cum, String descripcion, String principio, String atc, String ssfm, LocalDate ultima,
                              List<Par> pares) {
    }

    /**
     * Agrupa los pares por llave del dato original. Los atributos (principio activo, ATC, código SSFM, y el CUM y la descripción tal como
     * llegaron) son el valor más frecuente en los registros del cargue, con empate el menor lexicográfico: no dependen del orden de las
     * filas (invariante 14.8).
     */
    private static List<DatoCargue> datos(Map<Par, DatosPar> pares) {
        Map<String, List<Par>> porLlave = new TreeMap<>();
        pares.keySet().forEach(p -> porLlave.computeIfAbsent(AnaliticaHomologacion.llaveOrigen(p.cum(), p.medicamento()), k -> new ArrayList<>()).add(p));
        List<DatoCargue> l = new ArrayList<>(porLlave.size());
        porLlave.forEach((llave, ps) -> {
            Map<String, Long> cums = new HashMap<>();
            Map<String, Long> descs = new HashMap<>();
            Map<String, Long> principios = new HashMap<>();
            Map<String, Long> atcs = new HashMap<>();
            Map<String, Long> ssfms = new HashMap<>();
            LocalDate ultima = null;
            for (Par p : ps) {
                DatosPar d = pares.get(p);
                cums.merge(p.cum(), d.registros, Long::sum);
                descs.merge(p.medicamento(), d.registros, Long::sum);
                d.principios.forEach((k, v) -> principios.merge(k, v, Long::sum));
                d.atcs.forEach((k, v) -> atcs.merge(k, v, Long::sum));
                d.ssfms.forEach((k, v) -> ssfms.merge(k, v, Long::sum));
                ultima = ultima == null || d.ultima != null && d.ultima.isAfter(ultima) ? d.ultima : ultima;
            }
            l.add(new DatoCargue(llave, AnaliticaHomologacion.masFrecuente(cums), AnaliticaHomologacion.masFrecuente(descs),
                    AnaliticaHomologacion.masFrecuente(principios), AnaliticaHomologacion.masFrecuente(atcs), AnaliticaHomologacion.masFrecuente(ssfms),
                    ultima, ps));
        });
        return l;
    }

    /** SHA-256 en hexadecimal del par crudo, como la carga de hechos: encode(sha256(convert_to(cum || chr(1) || medicamento, 'UTF8')), 'hex'). */
    static String hashPar(String cum, String medicamento) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((cum + "\u0001" + medicamento).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void homologar(long cargueId) {
        long inicio = System.nanoTime();
        Lectura lectura = leerStaging(cargueId);
        if (lectura.pares().isEmpty() && lectura.cie10().isEmpty()) {
            return;
        }
        List<DatoCargue> datos = datos(lectura.pares());
        tx.executeWithoutResult(t -> {
            // Datos originales: los nuevos quedan pendientes de este cargue (invisibles); uno pendiente de un cargue anterior que no se
            // limpió pasa a este (lo confirmará o lo borrará su aplicación)
            Map<String, Long> ids = new HashMap<>();
            Set<Long> pendientes = new LinkedHashSet<>();
            if (!datos.isEmpty()) {
                String[] llaves = datos.stream().map(DatoCargue::llave).toArray(String[]::new);
                jdbc.sql("""
                        INSERT INTO hom_medicamento_origen (llave, cum_original, cum_normalizado, descripcion, descripcion_norm, principio_activo, atc,
                                                            codigo_ssfm, fecha_referencia, primer_cargue_id, ultimo_cargue_id, pendiente_cargue_id)
                        SELECT u.llave, left(u.cum, 100), u.cumn, left(u.descr, 1000), u.descn, left(u.princ, 500), left(u.atc, 20), left(u.ssfm, 60),
                               u.fecha, :c, :c, :c
                        FROM unnest(CAST(:llaves AS text[]), CAST(:cums AS text[]), CAST(:cumsn AS text[]), CAST(:descs AS text[]),
                                    CAST(:descsn AS text[]), CAST(:princs AS text[]), CAST(:atcs AS text[]), CAST(:ssfms AS text[]),
                                    CAST(:fechas AS date[])) AS u(llave, cum, cumn, descr, descn, princ, atc, ssfm, fecha)
                        ON CONFLICT (llave) DO UPDATE SET pendiente_cargue_id = EXCLUDED.pendiente_cargue_id
                        WHERE hom_medicamento_origen.pendiente_cargue_id IS NOT NULL""")
                        .param("llaves", llaves).param("cums", datos.stream().map(d -> vacio(d.cum())).toArray(String[]::new))
                        .param("cumsn", datos.stream().map(d -> AnaliticaHomologacion.normalizarCum(d.cum()).orElse(null)).toArray(String[]::new))
                        .param("descs", datos.stream().map(d -> vacio(d.descripcion())).toArray(String[]::new))
                        .param("descsn", datos.stream().map(d -> AnaliticaHomologacion.normalizarTexto(d.descripcion())).toArray(String[]::new))
                        .param("princs", datos.stream().map(DatoCargue::principio).toArray(String[]::new))
                        .param("atcs", datos.stream().map(DatoCargue::atc).toArray(String[]::new))
                        .param("ssfms", datos.stream().map(DatoCargue::ssfm).toArray(String[]::new))
                        .param("fechas", datos.stream().map(d -> d.ultima() == null ? null : Date.valueOf(d.ultima())).toArray(Date[]::new))
                        .param("c", cargueId).update();
                jdbc.sql("SELECT llave, id, pendiente_cargue_id FROM hom_medicamento_origen WHERE llave = ANY(:llaves)").param("llaves", llaves)
                        .query(rs -> {
                            ids.put(rs.getString(1).strip(), rs.getLong(2));
                            if (rs.getObject(3) != null) {
                                pendientes.add(rs.getLong(2));
                            }
                        });
            }
            if (!lectura.cie10().isEmpty()) {
                jdbc.sql("""
                        INSERT INTO hom_cie10_origen (codigo_original, primer_cargue_id, pendiente_cargue_id)
                        SELECT left(u, 40), :c, :c FROM unnest(CAST(:codigos AS text[])) AS u
                        ON CONFLICT (codigo_original) DO UPDATE SET pendiente_cargue_id = EXCLUDED.pendiente_cargue_id
                        WHERE hom_cie10_origen.pendiente_cargue_id IS NOT NULL""")
                        .param("codigos", lectura.cie10().toArray(String[]::new)).param("c", cargueId).update();
            }
            boolean cie10Nuevos = jdbc.sql("SELECT EXISTS (SELECT 1 FROM hom_cie10_origen WHERE pendiente_cargue_id = :c)").param("c", cargueId)
                    .query(Boolean.class).single();
            Integer vigente = vigente();
            int v;
            if (!pendientes.isEmpty() || cie10Nuevos) {
                // Versión pendiente con las filas de los datos originales nuevos (y los que comparten su descripción, por R-040)
                Instantanea inst = Instantanea.cargar(jdbc, json);
                v = jdbc.sql("""
                        INSERT INTO hom_version (numero, estado, creada, motivo, cargue_id, anterior_id, responsable_texto, fuentes, reglas, catalogos)
                        VALUES (NULL, 'pendiente', :creada, 'cargue', :c, :anterior, :resp, CAST(:fuentes AS jsonb), CAST(:reglas AS jsonb),
                                CAST(:catalogos AS jsonb))
                        RETURNING id""")
                        .param("creada", OffsetDateTime.now(reloj)).param("c", cargueId).param("anterior", vigente)
                        .param("resp", "Proceso automático · cargue " + cargueId).param("fuentes", aJson(inst.fuentesJson()))
                        .param("reglas", aJson(inst.reglas())).param("catalogos", aJson(inst.catalogos())).query(Integer.class).single();
                calcularPendientes(v, cargueId, inst);
            } else if (vigente != null) {
                v = vigente;
            } else {
                throw new IllegalStateException("Datos originales confirmados sin ninguna versión publicada de la tabla maestra");
            }
            // Correspondencia del cargue por el hash del par (la carga de hechos se une por la llave primaria) y conteos por mes
            if (!lectura.pares().isEmpty()) {
                List<Object[]> co = new ArrayList<>();
                Map<String, Long> porMes = new TreeMap<>();
                lectura.pares().forEach((p, d) -> {
                    long id = ids.get(AnaliticaHomologacion.llaveOrigen(p.cum(), p.medicamento()));
                    co.add(new Object[] {cargueId, hashPar(p.cum(), p.medicamento()), id, v, d.registros, Date.valueOf(d.ultima)});
                    d.porMes.forEach((mes, n) -> porMes.merge(id + "|" + mes, n, Long::sum));
                });
                plantilla.batchUpdate("""
                        INSERT INTO hom_cargue_origen (cargue_id, par_llave, medicamento_id, version_id, registros, ultima_fecha)
                        VALUES (?, ?, ?, ?, ?, ?)""", co);
                List<Object[]> cm = new ArrayList<>();
                porMes.forEach((k, n) -> {
                    String[] partes = k.split("\\|");
                    cm.add(new Object[] {cargueId, Long.parseLong(partes[0]), Date.valueOf(partes[1]), n});
                });
                plantilla.batchUpdate("INSERT INTO hom_cargue_mes (cargue_id, medicamento_id, mes, registros) VALUES (?, ?, ?, ?)", cm);
            }
        });
        LOG.info("[homologacion] Cargue {}: {} datos originales y {} códigos CIE-10 en {} ms (pendientes hasta aplicar el cargue)", cargueId,
                datos.size(), lectura.cie10().size(), (System.nanoTime() - inicio) / 1_000_000);
    }

    /**
     * Calcula en la versión pendiente las filas de los datos originales pendientes del cargue y de los confirmados que comparten su
     * descripción normalizada (R-040 puede cambiarlos); de estos solo guarda los que cambiaron. CIE-10: solo los códigos pendientes.
     */
    private void calcularPendientes(int versionId, long cargueId, Instantanea inst) {
        List<Origen> origenes = jdbc.sql("""
                SELECT id, cum_original, descripcion, principio_activo, atc, codigo_ssfm, fecha_referencia FROM hom_medicamento_origen o
                WHERE o.pendiente_cargue_id = :c
                   OR o.pendiente_cargue_id IS NULL AND o.descripcion_norm <> ''
                      AND o.descripcion_norm IN (SELECT n.descripcion_norm FROM hom_medicamento_origen n WHERE n.pendiente_cargue_id = :c)
                ORDER BY id""").param("c", cargueId).query((rs, n) -> origen(rs)).list();
        guardarCalculadas(versionId, inst, origenes, false, null);
        List<Object[]> cie10 = jdbc.sql("SELECT id, codigo_original FROM hom_cie10_origen WHERE pendiente_cargue_id = :c ORDER BY id")
                .param("c", cargueId).query((rs, n) -> new Object[] {rs.getLong(1), rs.getString(2)}).list();
        guardarCie10(versionId, inst, cie10, false);
    }

    private static Origen origen(java.sql.ResultSet rs) throws SQLException {
        Date f = rs.getDate(7);
        return new Origen(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                f == null ? null : f.toLocalDate());
    }

    @Override
    public void alAplicar(long cargueId) {
        long inicio = System.nanoTime();
        candado();
        Integer vigente = vigente();
        Map<String, Object> p = jdbc.sql("SELECT id, anterior_id FROM hom_version WHERE cargue_id = :c AND estado = 'pendiente'").param("c", cargueId)
                .query().listOfRows().stream().findFirst().orElse(null);
        int version;
        Ejecucion e = null;
        if (p != null) {
            int pid = ((Number) p.get("id")).intValue();
            Instantanea inst = null;
            if (!Objects.equals(p.get("anterior_id") == null ? null : ((Number) p.get("anterior_id")).intValue(), vigente)) {
                // Se publicó otra versión (re-ejecución, otro cambio) desde la etapa: se recalculan las filas pendientes con lo de hoy
                jdbc.sql("DELETE FROM hom_tabla_maestra WHERE version_id = :v").param("v", pid).update();
                jdbc.sql("DELETE FROM hom_tabla_cie10 WHERE version_id = :v").param("v", pid).update();
                inst = Instantanea.cargar(jdbc, json);
                calcularPendientes(pid, cargueId, inst);
            }
            e = publicar(pid, vigente, cargueId, inst, inicio);
            version = pid;
        } else if (vigente != null) {
            version = vigente;
        } else {
            return; // cargue sin pares ni códigos (nada que homologar)
        }
        // Confirmar los datos originales y códigos del cargue; fecha de referencia y último cargue solo con cargues aplicados
        jdbc.sql("UPDATE hom_medicamento_origen SET pendiente_cargue_id = NULL WHERE pendiente_cargue_id = :c").param("c", cargueId).update();
        jdbc.sql("UPDATE hom_cie10_origen SET pendiente_cargue_id = NULL WHERE pendiente_cargue_id = :c").param("c", cargueId).update();
        jdbc.sql("""
                UPDATE hom_medicamento_origen o SET ultimo_cargue_id = :c, fecha_referencia = GREATEST(o.fecha_referencia, x.ultima)
                FROM (SELECT medicamento_id, max(ultima_fecha) AS ultima FROM hom_cargue_origen WHERE cargue_id = :c GROUP BY medicamento_id) x
                WHERE o.id = x.medicamento_id""").param("c", cargueId).update();
        seguimientoDevoluciones(cargueId, version);
        if (e != null) {
            Ejecucion fin = e;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    registrarEjecucion(fin, "cargue " + cargueId, null, null);
                }
            });
        }
    }

    /**
     * Publica la versión pendiente del cargue: número siguiente, vigente desde ahora y conteo de la versión completa (el de la anterior
     * más los datos originales de sus filas, con las decisiones humanas).
     */
    private Ejecucion publicar(int versionId, Integer anterior, long cargueId, Instantanea recalculada, long inicio) {
        Map<Long, String> base = new HashMap<>();
        jdbc.sql("SELECT origen_id, estado FROM hom_tabla_maestra WHERE version_id = :v").param("v", versionId)
                .query(rs -> {
                    base.put(rs.getLong(1), rs.getString(2));
                });
        Map<Long, String> antes = estadosResueltos(anterior, base.keySet());
        Map<Long, String> decision = decisiones(base.keySet());
        Map<String, Long> conteo = conteoDe(anterior);
        int origenes = anterior == null ? 0 : jdbc.sql("SELECT origenes FROM hom_version WHERE id = :v").param("v", anterior).query(Integer.class).single();
        int cambios = 0;
        for (var x : base.entrySet()) {
            String despues = estadoFinal(x.getValue(), decision.get(x.getKey()));
            String previo = antes.get(x.getKey());
            if (previo == null) {
                origenes++;
            } else {
                conteo.merge(previo, -1L, Long::sum);
            }
            conteo.merge(despues, 1L, Long::sum);
            if (!despues.equals(previo)) {
                cambios++;
            }
        }
        conteo.values().removeIf(n -> n <= 0);
        int numero = siguienteNumero();
        long duracion = (System.nanoTime() - inicio) / 1_000_000;
        int n = jdbc.sql("""
                UPDATE hom_version SET numero = :n, estado = 'publicada', creada = :ahora, anterior_id = :anterior, origenes = :origenes,
                       conteo = CAST(:conteo AS jsonb), cambios = :cambios, duracion_ms = :duracion, detalle = :detalle,
                       fuentes = coalesce(CAST(:fuentes AS jsonb), fuentes), reglas = coalesce(CAST(:reglas AS jsonb), reglas),
                       catalogos = coalesce(CAST(:catalogos AS jsonb), catalogos)
                WHERE id = :v AND estado = 'pendiente'""")
                .param("n", numero).param("ahora", OffsetDateTime.now(reloj)).param("anterior", anterior).param("origenes", origenes)
                .param("conteo", aJson(conteo)).param("cambios", cambios).param("duracion", duracion)
                .param("detalle", cortar("Datos originales nuevos del cargue " + cargueId + " · " + base.size() + " filas calculadas", 500))
                .param("fuentes", recalculada == null ? null : aJson(recalculada.fuentesJson()))
                .param("reglas", recalculada == null ? null : aJson(recalculada.reglas()))
                .param("catalogos", recalculada == null ? null : aJson(recalculada.catalogos())).param("v", versionId).update();
        if (n != 1) {
            throw new IllegalStateException("La versión pendiente " + versionId + " del cargue " + cargueId + " ya no estaba pendiente");
        }
        Map<String, Long> previo = conteoDe(anterior);
        LOG.info("[homologacion] Versión v{} publicada (cargue {}): {} filas, {} datos originales {}", numero, cargueId, base.size(), origenes, conteo);
        return new Ejecucion(versionId, numero, origenes, conteo, previo, cambios, base.size(), duracion);
    }

    /**
     * Seguimiento de las devoluciones al OPL con lo que trae el cargue aplicado (RESP L4361): si trae el dato devuelto, «corregida» si en
     * la versión del cargue quedó homologado y «persiste» si no; si no lo trae pero trae un dato original nuevo con el mismo CUM o la
     * misma descripción ya homologado, «corregida» (el OPL corrigió el dato y llegó como otro dato original).
     */
    private void seguimientoDevoluciones(long cargueId, int version) {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        Map<Long, Long> mismo = new HashMap<>(); // devolución ⇒ dato original
        jdbc.sql("""
                SELECT d.id, d.origen_id FROM hom_devolucion d
                WHERE d.estado IN ('devuelta', 'persiste') AND d.cargue_id IS DISTINCT FROM :c
                  AND d.origen_id IN (SELECT medicamento_id FROM hom_cargue_origen WHERE cargue_id = :c)""")
                .param("c", cargueId).query(rs -> {
                    mismo.put(rs.getLong(1), rs.getLong(2));
                });
        Map<Long, Long> corregidoPor = new HashMap<>(); // devolución ⇒ dato original nuevo que la corrige
        jdbc.sql("""
                SELECT d.id, min(y.id) FROM hom_devolucion d
                JOIN hom_medicamento_origen x ON x.id = d.origen_id
                JOIN hom_cargue_origen co ON co.cargue_id = :c
                JOIN hom_medicamento_origen y ON y.id = co.medicamento_id AND y.id <> x.id AND y.primer_cargue_id = :c
                     AND (x.cum_normalizado IS NOT NULL AND y.cum_normalizado = x.cum_normalizado
                          OR x.descripcion_norm <> '' AND y.descripcion_norm = x.descripcion_norm)
                WHERE d.estado IN ('devuelta', 'persiste') AND d.cargue_id IS DISTINCT FROM :c
                  AND d.origen_id NOT IN (SELECT medicamento_id FROM hom_cargue_origen WHERE cargue_id = :c)
                GROUP BY d.id""").param("c", cargueId).query(rs -> {
                    corregidoPor.put(rs.getLong(1), rs.getLong(2));
                });
        if (mismo.isEmpty() && corregidoPor.isEmpty()) {
            return;
        }
        Set<Long> consultar = new LinkedHashSet<>(mismo.values());
        consultar.addAll(corregidoPor.values());
        Map<Long, String> estados = estadosResueltos(version, consultar);
        List<Object[]> corregidas = new ArrayList<>();
        List<Object[]> persisten = new ArrayList<>();
        mismo.forEach((d, o) -> (("homologado".equals(estados.get(o))) ? corregidas : persisten).add(new Object[] {d}));
        corregidoPor.forEach((d, y) -> {
            if ("homologado".equals(estados.get(y))) {
                corregidas.add(new Object[] {d});
            }
        });
        for (Object[] d : corregidas) {
            jdbc.sql("UPDATE hom_devolucion SET estado = 'corregida', corregida_cargue_id = :c, actualizado = :a WHERE id = :d")
                    .param("c", cargueId).param("a", ahora).param("d", d[0]).update();
        }
        for (Object[] d : persisten) {
            jdbc.sql("UPDATE hom_devolucion SET estado = 'persiste', actualizado = :a WHERE id = :d").param("a", ahora).param("d", d[0]).update();
        }
    }

    @Override
    public void descartar(long cargueId) {
        try {
            tx.executeWithoutResult(t -> {
                boolean aplicado = jdbc.sql("SELECT estado = 'aplicado' FROM ana_cargues WHERE id = :c").param("c", cargueId).query(Boolean.class)
                        .optional().orElse(false);
                if (aplicado) {
                    return;
                }
                String pendiente = "SELECT id FROM hom_version WHERE cargue_id = :c AND estado = 'pendiente'";
                int filas = jdbc.sql("DELETE FROM hom_tabla_maestra WHERE version_id IN (" + pendiente + ")").param("c", cargueId).update();
                jdbc.sql("DELETE FROM hom_tabla_cie10 WHERE version_id IN (" + pendiente + ")").param("c", cargueId).update();
                jdbc.sql("DELETE FROM hom_cargue_mes WHERE cargue_id = :c").param("c", cargueId).update();
                int pares = jdbc.sql("DELETE FROM hom_cargue_origen WHERE cargue_id = :c").param("c", cargueId).update();
                jdbc.sql("DELETE FROM hom_version WHERE cargue_id = :c AND estado = 'pendiente'").param("c", cargueId).update();
                int origenes = jdbc.sql("""
                        DELETE FROM hom_medicamento_origen o WHERE o.pendiente_cargue_id = :c
                          AND NOT EXISTS (SELECT 1 FROM hom_cargue_origen x WHERE x.medicamento_id = o.id)
                          AND NOT EXISTS (SELECT 1 FROM hom_cargue_mes x WHERE x.medicamento_id = o.id)
                          AND NOT EXISTS (SELECT 1 FROM hom_tabla_maestra x WHERE x.origen_id = o.id)""").param("c", cargueId).update();
                jdbc.sql("""
                        DELETE FROM hom_cie10_origen o WHERE o.pendiente_cargue_id = :c
                          AND NOT EXISTS (SELECT 1 FROM hom_tabla_cie10 x WHERE x.origen_id = o.id)""").param("c", cargueId).update();
                if (filas + pares + origenes > 0) {
                    LOG.info("[homologacion] Cargue {} no aplicado: se borró lo pendiente ({} pares, {} datos originales, {} filas)", cargueId, pares,
                            origenes, filas);
                }
            });
        } catch (RuntimeException e) {
            LOG.error("[homologacion] No se pudo borrar lo pendiente del cargue {} no aplicado", cargueId, e);
        }
    }

    // ---------- Re-ejecución completa (diferida) ----------

    /** Estado de la re-ejecución en curso o de la última (pantalla). */
    EstadoReejecucion estadoReejecucion() {
        return reejecucion;
    }

    /** «Re-ejecutar homologación»: se encola (un hilo); 409 si ya hay una en curso. */
    synchronized EstadoReejecucion iniciarReejecucion(long usuarioId, String usuario, String responsable) {
        if (reejecucion.enCurso()) {
            throw ErrorNegocio.conflicto("Ya hay una re-ejecución en curso (" + reejecucion.responsable() + "); espere a que termine.");
        }
        OffsetDateTime inicio = OffsetDateTime.now(reloj);
        reejecucion = new EstadoReejecucion("en_curso", 0, "En cola", inicio, null, responsable, null, null);
        ejecutor.submit(() -> correrReejecucion(usuarioId, usuario, responsable, inicio));
        return reejecucion;
    }

    private void correrReejecucion(long usuarioId, String usuario, String responsable, OffsetDateTime inicio) {
        try {
            Ejecucion e = tx.execute(t -> {
                avance(1, "Esperando el candado de la homologación", inicio, responsable);
                candado(jdbc, ESPERA_REEJECUCION);
                return ejecutar(usuarioId, responsable, pct -> avance(pct, "Homologando los datos originales", inicio, responsable));
            });
            reejecucion = new EstadoReejecucion("terminada", 100, "Terminada", inicio, OffsetDateTime.now(reloj), responsable, e, null);
            registrarEjecucion(e, "re-ejecución", usuarioId, usuario);
        } catch (ErrorNegocio e) {
            reejecucion = new EstadoReejecucion("fallida", 0, "Fallida", inicio, OffsetDateTime.now(reloj), responsable, null, e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("[homologacion] La re-ejecución falló", e);
            reejecucion = new EstadoReejecucion("fallida", 0, "Fallida", inicio, OffsetDateTime.now(reloj), responsable, null,
                    "Error técnico: la re-ejecución no terminó y la tabla maestra no cambió.");
        }
    }

    private void avance(int porcentaje, String fase, OffsetDateTime inicio, String responsable) {
        reejecucion = new EstadoReejecucion("en_curso", Math.max(0, Math.min(99, porcentaje)), fase, inicio, null, responsable, null, null);
    }

    private void registrarEjecucion(Ejecucion e, String porque, Long usuarioId, String usuario) {
        try {
            bitacora.registrar("homologacion_ejecutada", cortar("Homologación v" + e.numero() + " (" + porque + ") · " + e.origenes() + " datos originales · "
                    + e.conteo() + " · " + e.cambios() + " cambiaron de estado · " + e.filas() + " filas nuevas o cambiadas · " + e.duracionMs() + " ms", 500),
                    usuarioId, usuario);
        } catch (RuntimeException ex) {
            LOG.error("[homologacion] No se pudo registrar la ejecución v{} en la bitácora", e.numero(), ex);
        }
    }

    /**
     * Re-ejecución completa sobre todos los datos originales confirmados (en la transacción y con el candado del que llama): versión
     * publicada nueva con solo las filas que cambiaron; el conteo y el antes y después por estado son de la versión completa.
     */
    Ejecucion ejecutar(Long usuarioId, String responsable, IntConsumer avance) {
        long inicio = System.nanoTime();
        Instantanea inst = Instantanea.cargar(jdbc, json);
        Integer anterior = vigente();
        List<Origen> origenes = jdbc.sql("""
                SELECT id, cum_original, descripcion, principio_activo, atc, codigo_ssfm, fecha_referencia FROM hom_medicamento_origen
                WHERE pendiente_cargue_id IS NULL ORDER BY id""").query((rs, n) -> origen(rs)).list();
        avance.accept(5);
        // Nace pendiente (sus filas necesitan la llave foránea) y se publica al final de la misma transacción con su conteo
        int versionId = jdbc.sql("""
                INSERT INTO hom_version (numero, estado, creada, motivo, anterior_id, usuario_id, responsable_texto, fuentes, reglas, catalogos)
                VALUES (NULL, 'pendiente', :creada, 'reejecucion', :anterior, :usuario, :resp, CAST(:fuentes AS jsonb), CAST(:reglas AS jsonb),
                        CAST(:catalogos AS jsonb))
                RETURNING id""")
                .param("creada", OffsetDateTime.now(reloj)).param("anterior", anterior).param("usuario", usuarioId)
                .param("resp", cortar(responsable, 120)).param("fuentes", aJson(inst.fuentesJson())).param("reglas", aJson(inst.reglas()))
                .param("catalogos", aJson(inst.catalogos())).query(Integer.class).single();
        Map<Long, String> calculados = guardarCalculadas(versionId, inst, origenes, true, avance);
        avance.accept(90);
        List<Object[]> cie10 = jdbc.sql("SELECT id, codigo_original FROM hom_cie10_origen WHERE pendiente_cargue_id IS NULL ORDER BY id")
                .query((rs, n) -> new Object[] {rs.getLong(1), rs.getString(2)}).list();
        guardarCie10(versionId, inst, cie10, true);
        // Antes y después por estado de la versión completa, con las decisiones humanas
        Map<Long, String> antesPorOrigen = anterior == null ? Map.of() : estadosResueltos(anterior, null);
        Map<Long, String> decision = decisiones(null);
        Map<String, Long> conteo = new TreeMap<>();
        Map<String, Long> antes = new TreeMap<>();
        antesPorOrigen.values().forEach(x -> antes.merge(x, 1L, Long::sum));
        int cambios = 0;
        for (var x : calculados.entrySet()) {
            String despues = estadoFinal(x.getValue(), decision.get(x.getKey()));
            conteo.merge(despues, 1L, Long::sum);
            if (!despues.equals(antesPorOrigen.get(x.getKey()))) {
                cambios++;
            }
        }
        int filas = jdbc.sql("SELECT count(*) FROM hom_tabla_maestra WHERE version_id = :v").param("v", versionId).query(Integer.class).single();
        int numero = siguienteNumero();
        long duracion = (System.nanoTime() - inicio) / 1_000_000;
        jdbc.sql("""
                UPDATE hom_version SET numero = :n, estado = 'publicada', creada = :ahora, origenes = :origenes, conteo = CAST(:conteo AS jsonb),
                       cambios = :cambios, duracion_ms = :duracion, detalle = :detalle
                WHERE id = :v AND estado = 'pendiente'""")
                .param("n", numero).param("ahora", OffsetDateTime.now(reloj)).param("origenes", calculados.size()).param("conteo", aJson(conteo))
                .param("cambios", cambios).param("duracion", duracion)
                .param("detalle", "Re-ejecución desde la pantalla · " + filas + " filas nuevas o cambiadas").param("v", versionId).update();
        LOG.info("[homologacion] Versión v{} (re-ejecución): {} datos originales, {} filas nuevas o cambiadas, {} en {} ms", numero, calculados.size(),
                filas, conteo, duracion);
        return new Ejecucion(versionId, numero, calculados.size(), conteo, antes, cambios, filas, duracion);
    }

    int siguienteNumero() {
        return jdbc.sql("SELECT coalesce(max(numero), 0) + 1 FROM hom_version").query(Integer.class).single();
    }

    /** Versión vigente (la publicada de mayor número), o nula si aún no hay. */
    Integer vigente() {
        return jdbc.sql("SELECT id FROM hom_version WHERE estado = 'publicada' ORDER BY numero DESC LIMIT 1").query(Integer.class).optional().orElse(null);
    }

    // ---------- Tabla maestra desactualizada (revisión A2 2026-10-23) ----------

    /** La tabla maestra vigente quedó atrás: desde qué versión, qué cambió (fuentes, reglas, catálogos) y el texto para la pantalla. */
    record Desactualizada(int desdeVersion, List<String> cambios, String motivo) {
    }

    /**
     * Si la tabla maestra vigente quedó desactualizada frente a lo de hoy. La homologación del cargue es incremental: solo calcula los
     * datos originales nuevos (con la instantánea de ese momento) y los que ya existían conservan su fila hasta una re-ejecución completa.
     * Por eso se comparan con la instantánea de hoy las versiones cuyas filas siguen vigentes con el cálculo de entonces: la última
     * re-ejecución completa y las de cargue posteriores (todas las de cargue si nunca hubo re-ejecución); las de revisión no calculan.
     * Fuentes por la versión publicada de cada una, reglas por su contenido (orden, activa, parámetros: cambiar y volver no cuenta) y
     * catálogos por su versión. Vacío si no hay versiones o si todas se calcularon con lo de hoy.
     */
    Optional<Desactualizada> desactualizada() {
        List<Map<String, Object>> versiones = jdbc.sql("""
                SELECT numero, fuentes::text AS fuentes, reglas::text AS reglas, catalogos::text AS catalogos FROM hom_version
                WHERE estado = 'publicada' AND motivo IN ('cargue', 'reejecucion')
                  AND numero >= coalesce((SELECT max(numero) FROM hom_version WHERE estado = 'publicada' AND motivo = 'reejecucion'), 0)
                ORDER BY numero""").query().listOfRows();
        if (versiones.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Instantanea.Fuente> fuentes = Instantanea.publicadas(jdbc);
        Map<String, Object> reglas = Instantanea.reglasActuales(jdbc, json);
        Map<String, Integer> catalogos = Instantanea.catalogosActuales(jdbc);
        Set<String> cambios = new LinkedHashSet<>();
        Integer desde = null;
        for (Map<String, Object> v : versiones) {
            List<String> c = cambiosDesde(Instantanea.leer(json, (String) v.get("fuentes")), Instantanea.leer(json, (String) v.get("reglas")),
                    Instantanea.leer(json, (String) v.get("catalogos")), fuentes, reglas, catalogos);
            if (!c.isEmpty() && desde == null) {
                desde = ((Number) v.get("numero")).intValue();
            }
            cambios.addAll(c);
        }
        if (desde == null) {
            return Optional.empty();
        }
        List<String> l = List.copyOf(cambios);
        String lista = l.size() == 1 ? l.getFirst() : String.join(", ", l.subList(0, l.size() - 1)) + " y " + l.getLast();
        String motivo = Character.toUpperCase(lista.charAt(0)) + lista.substring(1) + " después de la versión " + desde + " de la tabla maestra: "
                + "los datos originales que ya estaban conservan el resultado anterior hasta re-ejecutar la homologación.";
        return Optional.of(new Desactualizada(desde, l, cortar(motivo, 1000)));
    }

    /** Diferencias entre lo guardado en una versión (fuentes, reglas y catálogos en JSON) y lo de hoy, en texto para la pantalla. */
    static List<String> cambiosDesde(Map<String, Object> fuentesV, Map<String, Object> reglasV, Map<String, Object> catalogosV,
                                     Map<String, Instantanea.Fuente> fuentes, Map<String, Object> reglas, Map<String, Integer> catalogos) {
        List<String> c = new ArrayList<>();
        fuentes.forEach((codigo, f) -> {
            Object guardada = fuentesV.get(codigo) instanceof Map<?, ?> m ? m.get("id") : null;
            if (!(guardada instanceof Number n) || n.intValue() != f.versionId()) {
                c.add("se publicó " + codigo + " v" + f.numero());
            }
        });
        fuentesV.keySet().stream().filter(k -> !fuentes.containsKey(k)).forEach(k -> c.add("se retiró la versión publicada de " + k));
        Set<String> ids = new TreeSet<>(reglas.keySet());
        ids.addAll(reglasV.keySet());
        for (String id : ids) {
            if (!Objects.equals(contenidoRegla(reglas.get(id)), contenidoRegla(reglasV.get(id)))) {
                c.add("cambió la regla " + id);
            }
        }
        Set<String> tipos = new TreeSet<>(catalogos.keySet());
        tipos.addAll(catalogosV.keySet());
        for (String t : tipos) {
            Integer hoy = catalogos.get(t);
            if (hoy == null || !(catalogosV.get(t) instanceof Number n) || n.intValue() != hoy) {
                c.add("cambió el catálogo de " + ServicioReglas.NOMBRES_CATALOGO.getOrDefault(t, t) + (hoy == null ? "" : " (v" + hoy + ")"));
            }
        }
        return c;
    }

    /** Lo que cuenta de una regla para el cálculo: orden, activa y parámetros (sin su número de versión); nulo si no está. */
    private static List<String> contenidoRegla(Object regla) {
        if (!(regla instanceof Map<?, ?> m)) {
            return null;
        }
        Map<String, String> p = new TreeMap<>();
        if (m.get("parametros") instanceof Map<?, ?> pm) {
            pm.forEach((k, v) -> p.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        }
        return List.of(String.valueOf(m.get("orden")), String.valueOf(m.get("activa")), p.toString());
    }

    /**
     * Homologa los datos originales con la instantánea, marca los duplicados entre ellos y guarda en la versión las filas nuevas o cuya
     * huella cambió frente a la fila vigente. Devuelve el estado calculado (sin decisiones) de cada uno.
     */
    private Map<Long, String> guardarCalculadas(int versionId, Instantanea inst, List<Origen> origenes, boolean todos, IntConsumer avance) {
        Map<Long, String> estados = new LinkedHashMap<>();
        if (origenes.isEmpty()) {
            return estados;
        }
        Referencias refs = inst.referencias(jdbc, origenes);
        List<Resultado> resultados = new ArrayList<>(origenes.size());
        for (int i = 0; i < origenes.size(); i++) {
            resultados.add(AnaliticaHomologacion.homologar(origenes.get(i), inst.configuracion(), refs));
            if (avance != null && i % 500 == 0) {
                avance.accept(5 + (int) ((long) i * 75 / origenes.size()));
            }
        }
        resultados = AnaliticaHomologacion.marcarDuplicados(origenes, resultados, inst.configuracion());
        Map<Long, String> huellas = new HashMap<>();
        jdbc.sql(BASE).param("todos", todos).param("ids", todos ? new Long[0] : origenes.stream().map(Origen::id).toArray(Long[]::new))
                .query(rs -> {
                    huellas.put(rs.getLong(1), rs.getString(2).strip());
                });
        List<Object[]> lote = new ArrayList<>();
        for (Resultado r : resultados) {
            Object[] f = fila(versionId, inst, r);
            estados.put(r.origenId(), r.estado().codigo());
            if (!f[f.length - 1].equals(huellas.get(r.origenId()))) {
                lote.add(f);
            }
            if (lote.size() == 1000) {
                plantilla.batchUpdate(INSERTAR_FILA, lote);
                lote.clear();
            }
        }
        if (!lote.isEmpty()) {
            plantilla.batchUpdate(INSERTAR_FILA, lote);
        }
        return estados;
    }

    /** Fila calculada de la tabla maestra lista para insertar, con su huella al final. */
    private Object[] fila(int versionId, Instantanea inst, Resultado r) {
        String fuenteCodigo = null;
        Integer fuenteVersion = null;
        LocalDate fuenteFecha = null;
        if (r.fuente() != null) {
            var f = inst.cum(r.fuente().conjunto());
            if (f.isPresent()) {
                fuenteCodigo = f.get().codigo();
                fuenteVersion = f.get().versionId();
                fuenteFecha = f.get().fechaCorte();
            }
        }
        AnaliticaHomologacion.Original o = r.original();
        DatoFinal d = r.dato();
        List<Map<String, String>> pasos = new ArrayList<>();
        for (Paso p : r.pasos()) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("regla", p.regla());
            m.put("antes", p.antes());
            m.put("despues", p.despues());
            pasos.add(m);
        }
        List<String> diferencias = r.estado() == Estado.INCONSISTENTE || r.estado() == Estado.DUPLICADO ? r.diferencias() : List.of();
        if (r.estado() == Estado.INCONSISTENTE && diferencias.isEmpty()) {
            diferencias = List.of(Objects.requireNonNullElse(r.causa(), "diferencia con el INVIMA"));
        }
        Object[] v = new Object[] {versionId, r.origenId(), cortar(o.cum(), 100), cortar(o.descripcion(), 1000), cortar(o.principioActivo(), 500),
            o.concentracion(), o.forma(), o.presentacion(), o.unidad(), o.codigoSsfm(), o.atc(), fuenteCodigo, fuenteVersion,
            fuenteFecha == null ? null : Date.valueOf(fuenteFecha), r.regla(), cortar(r.causa(), 500), aJson(pasos),
            d == null ? null : d.nombreComercial(), d == null ? null : d.dci(), d == null ? null : d.principioActivo(), d == null ? null : d.concentracion(),
            d == null ? null : d.forma(), d == null ? null : d.presentacion(), d == null ? null : d.unidad(), d == null ? null : d.cum(),
            d == null ? null : d.codigoSsfm(), d == null ? null : d.atc(), d == null ? null : d.grupoFarmacologico(),
            d == null ? null : d.registroSanitario(), r.estado().codigo(), diferencias.toArray(String[]::new), r.candidatos().toArray(String[]::new), null};
        v[v.length - 1] = huella(v);
        return v;
    }

    /** SHA-256 del contenido calculado (sin la versión ni el id ni la propia huella). */
    static String huella(Object[] fila) {
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i < fila.length - 1; i++) {
            Object x = fila[i];
            sb.append(x == null ? "\u0000" : x instanceof Object[] a ? Arrays.toString(a) : x.toString()).append('\u0001');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** CIE-10 (R-060) de los códigos dados; guarda los nuevos o cambiados frente a la versión completa vigente. */
    private void guardarCie10(int versionId, Instantanea inst, List<Object[]> origenes, boolean comparar) {
        if (origenes.isEmpty()) {
            return;
        }
        Map<Long, String> vigentes = new HashMap<>();
        Integer v = vigente();
        if (comparar && v != null) {
            jdbc.sql("SELECT origen_id, concat_ws('|', codigo_final, estado, causa, fuente_version_id) FROM hom_tabla_cie10_version(:v)").param("v", v)
                    .query(rs -> {
                        vigentes.put(rs.getLong(1), rs.getString(2));
                    });
        }
        var ref = inst.cie10(jdbc, origenes.stream().map(x -> (String) x[1]).toList());
        Integer fuente = inst.deTipo("cie10").map(Instantanea.Fuente::versionId).orElse(null);
        List<Object[]> lote = new ArrayList<>();
        for (Object[] x : origenes) {
            ResultadoCie10 r = inst.configuracion().activa(AnaliticaHomologacion.R060) ? AnaliticaHomologacion.homologarCie10((String) x[1], ref)
                    : new ResultadoCie10((String) x[1], null, Estado.PENDIENTE, AnaliticaHomologacion.R060, "regla R-060 desactivada");
            String causa = cortar(r.causa(), 300);
            String clave = String.join("|", java.util.stream.Stream.of(r.codigo(), r.estado().codigo(), causa, fuente == null ? null : fuente.toString())
                    .filter(Objects::nonNull).toList());
            if (!clave.equals(vigentes.get((Long) x[0]))) {
                lote.add(new Object[] {versionId, x[0], r.codigo(), r.estado().codigo(), r.regla(), causa, fuente});
            }
        }
        if (!lote.isEmpty()) {
            plantilla.batchUpdate("""
                    INSERT INTO hom_tabla_cie10 (version_id, origen_id, codigo_final, estado, regla, causa, fuente_version_id) VALUES (?, ?, ?, ?, ?, ?, ?)""",
                    lote);
        }
    }

    /** Estado final (con decisiones) de los datos originales en una versión publicada; todos si {@code ids} es nulo. */
    Map<Long, String> estadosResueltos(Integer version, Collection<Long> ids) {
        Map<Long, String> m = new HashMap<>();
        if (version == null || ids != null && ids.isEmpty()) {
            return m;
        }
        jdbc.sql("SELECT origen_id, estado FROM hom_tabla_maestra_version(:v) t WHERE CAST(:todos AS boolean) OR t.origen_id = ANY(:ids)")
                .param("v", version).param("todos", ids == null).param("ids", ids == null ? new Long[0] : ids.toArray(Long[]::new))
                .query(rs -> {
                    m.put(rs.getLong(1), rs.getString(2));
                });
        return m;
    }

    /** Última decisión humana publicada (aprobar o devolver) por dato original; todos si {@code ids} es nulo. */
    private Map<Long, String> decisiones(Collection<Long> ids) {
        Map<Long, String> m = new HashMap<>();
        if (ids != null && ids.isEmpty()) {
            return m;
        }
        jdbc.sql("""
                SELECT DISTINCT ON (r.origen_id) r.origen_id, r.accion FROM hom_revision r JOIN hom_version rv ON rv.id = r.version_id
                WHERE rv.estado = 'publicada' AND (CAST(:todos AS boolean) OR r.origen_id = ANY(:ids))
                ORDER BY r.origen_id, rv.numero DESC, r.id DESC""")
                .param("todos", ids == null).param("ids", ids == null ? new Long[0] : ids.toArray(Long[]::new)).query(rs -> {
                    m.put(rs.getLong(1), rs.getString(2));
                });
        return m;
    }

    private static String estadoFinal(String reglas, String accion) {
        return AnaliticaHomologacion.estadoFinal(Estado.deCodigo(reglas).orElseThrow(), accion).codigo();
    }

    /** Conteo por estado de una versión (vacío si es nula). */
    Map<String, Long> conteoDe(Integer version) {
        Map<String, Long> m = new TreeMap<>();
        if (version != null) {
            String c = jdbc.sql("SELECT conteo::text FROM hom_version WHERE id = :v").param("v", version).query(String.class).single();
            Instantanea.leer(json, c).forEach((k, x) -> m.put(k, ((Number) x).longValue()));
        }
        return m;
    }

    // ---------- Apoyo ----------

    static Map<String, Object> mapaDeDato(DatoFinal d) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (d == null) {
            return m;
        }
        m.put("nombreComercial", d.nombreComercial());
        m.put("dci", d.dci());
        m.put("principioActivo", d.principioActivo());
        m.put("concentracion", d.concentracion());
        m.put("forma", d.forma());
        m.put("presentacion", d.presentacion());
        m.put("unidad", d.unidad());
        m.put("cum", d.cum());
        m.put("codigoSsfm", d.codigoSsfm());
        m.put("atc", d.atc());
        m.put("grupoFarmacologico", d.grupoFarmacologico());
        m.put("registroSanitario", d.registroSanitario());
        return m;
    }

    static String texto(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String vacio(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    String aJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String cortar(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** Conteo por estado en el orden del enum (para la pantalla). */
    static Map<String, Long> ordenado(Map<String, Long> conteo) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Estado e : Estado.values()) {
            m.put(e.codigo(), conteo.getOrDefault(e.codigo(), 0L));
        }
        return m;
    }

    static Map<Estado, Long> porEstado(Map<String, Long> conteo) {
        Map<Estado, Long> m = new EnumMap<>(Estado.class);
        conteo.forEach((k, v) -> Estado.deCodigo(k).ifPresent(e -> m.put(e, v)));
        return m;
    }
}
