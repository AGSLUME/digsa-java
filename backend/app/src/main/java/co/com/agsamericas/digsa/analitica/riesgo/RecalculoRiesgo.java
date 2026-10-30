package co.com.agsamericas.digsa.analitica.riesgo;

import co.com.agsamericas.digsa.alertas.ServicioAlertas;
import co.com.agsamericas.digsa.analitica.CargueAplicado;
import co.com.agsamericas.digsa.analitica.tableros.PropiedadesTableros;
import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.Corte;
import co.com.agsamericas.digsa.analitica.tableros.ServicioConsultaAnalitica;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.Contexto;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.Parametros;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.Severidad;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas.Tipo;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.Criterio;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.GastoMovil;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.Lista;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta;
import co.com.agsamericas.digsa.reglas.Bitacora;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recálculo de cohortes y motor de alertas tempranas (analitica-cohortes-alertas, «Recálculo al aplicar cada cargue y bajo demanda» y
 * «Motor de alertas tempranas determinista»). Al quedar «aplicado» un cargue (evento {@link CargueAplicado}, después de confirmar: el cargue
 * no espera ni falla por esto) y bajo demanda, un único hilo de fondo con candado de base (advisory lock: una corrida a la vez también con
 * varias instancias):
 * <ol>
 * <li>lee, en una transacción de solo lectura (una sola foto), el corte, las versiones vigentes de las cohortes activas, el catálogo
 * clínico, los parámetros de alertas y el umbral de alto costo por mes, y recorre UNA vez los hechos del corte (ana_hechos_tablero); cada
 * detector corre en un punto de guardado (una falla SQL deja solo su tipo en error);</li>
 * <li>escribe, en una transacción, la serie agregada de cada versión para ese corte (la de un corte anterior no se toca: reproducible), la
 * membresía del último corte, los conteos del periodo por defecto y, en un punto de guardado aparte, las alertas (llave natural: reejecutar
 * no duplica) con su aviso: las de severidad alta de los últimos meses del corte avisan por correo una vez (si el aviso falla queda
 * «pendiente» y se reintenta); las de periodos anteriores (el primer cargue histórico) van en un solo resumen por ejecución.</li>
 * </ol>
 * Robustez (revisión A4 2026-10-24): un Error del hilo deja la ejecución en «error» con mensaje genérico y en el registro; al arrancar, las
 * ejecuciones que quedaron «en curso» pasan a «interrumpida» y se re-encola el recálculo del último corte.
 */
@Service
public class RecalculoRiesgo implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(RecalculoRiesgo.class);
    static final String TIPO_AVISO = "analitica_alerta_alta";
    private static final long CANDADO = 0x44494753415249L; // «DIGSARI»: una corrida de riesgo a la vez

    /** Corrida programada: ids de las ejecuciones (la de alertas es nula si solo se recalculan cohortes). */
    public record Programada(long ejecucionCohortes, Long ejecucionAlertas, long corte) {
    }

    /** Puntos de observación del recorrido y los detectores (las pruebas fuerzan fallas con ella; en producción no hace nada). */
    interface Sonda {
        Sonda NINGUNA = new Sonda() {
        };

        default void detector(Tipo t, JdbcClient j) {
        }

        default void secuencia(Tipo t) {
        }

        default void version(String codigoCohorte) {
        }
    }

    private final JdbcClient jdbc;
    private final DataSource ds;
    private final TransactionTemplate lectura;
    private final TransactionTemplate escritura;
    private final TransactionTemplate anidada;
    private final ServicioConsultaAnalitica consultas;
    private final ServicioAlertas avisos;
    private final RegistroBitacora bitacora;
    private final ObjectMapper json;
    private final Clock reloj;
    private final PropiedadesRiesgo propiedades;
    private final PropiedadesTableros tableros;
    volatile Sonda sonda = Sonda.NINGUNA;
    private final ExecutorService hilo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "digsa-analitica-riesgo");
        t.setDaemon(true);
        return t;
    });

    RecalculoRiesgo(JdbcClient jdbc, DataSource ds, PlatformTransactionManager transacciones, ServicioConsultaAnalitica consultas, ServicioAlertas avisos,
                    RegistroBitacora bitacora, ObjectMapper json, Clock reloj, PropiedadesRiesgo propiedades, PropiedadesTableros tableros) {
        this.jdbc = jdbc;
        this.ds = ds;
        this.consultas = consultas;
        this.avisos = avisos;
        this.bitacora = bitacora;
        this.json = json;
        this.reloj = reloj;
        this.propiedades = propiedades;
        this.tableros = tableros;
        this.lectura = new TransactionTemplate(transacciones);
        this.lectura.setReadOnly(true);
        this.lectura.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.lectura.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.escritura = new TransactionTemplate(transacciones);
        this.escritura.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.anidada = new TransactionTemplate(transacciones);
        this.anidada.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    // ---------- Siembra y recuperación al arrancar ----------

    /** Versión 1 de las cohortes predefinidas y parámetros por defecto de cada tipo de alerta; después, la recuperación de lo interrumpido. */
    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        sembrar();
        try {
            recuperar();
        } catch (RuntimeException e) {
            LOG.error("[analitica] No se pudieron recuperar las ejecuciones interrumpidas de cohortes y alertas", e);
        }
    }

    void sembrar() {
        try {
            escritura.executeWithoutResult(s -> {
                for (AnaliticaCohortes.Predefinida p : AnaliticaCohortes.PREDEFINIDAS) {
                    Criterio c = AnaliticaCohortes.leer(p.criterio());
                    jdbc.sql("""
                            INSERT INTO ana_cohortes_versiones (cohorte_id, version, criterios, criterio_texto, vigente)
                            SELECT c.id, 1, CAST(:crit AS jsonb), :texto, true FROM ana_cohortes c
                            WHERE c.codigo = :codigo AND NOT EXISTS (SELECT 1 FROM ana_cohortes_versiones v WHERE v.cohorte_id = c.id)
                            ON CONFLICT DO NOTHING""").param("crit", aJson(AnaliticaCohortes.aMapa(c))).param("texto", AnaliticaCohortes.texto(c))
                            .param("codigo", p.codigo()).update();
                }
                for (Tipo t : Tipo.values()) {
                    String p = aJson(AnaliticaAlertas.aMapa(AnaliticaAlertas.porDefecto(t)));
                    int n = jdbc.sql("INSERT INTO ana_alertas_parametros (tipo, version, parametros) VALUES (:t, 1, CAST(:p AS jsonb)) ON CONFLICT DO NOTHING")
                            .param("t", t.codigo()).param("p", p).update();
                    if (n == 1) {
                        jdbc.sql("""
                                INSERT INTO ana_alertas_parametros_historial (tipo, version, antes, despues, responsable_texto, fecha)
                                VALUES (:t, 1, NULL, CAST(:p AS jsonb), 'Sistema (valores por defecto de la especificación)', :f) ON CONFLICT DO NOTHING""")
                                .param("t", t.codigo()).param("p", p).param("f", OffsetDateTime.now(reloj)).update();
                    }
                }
            });
        } catch (RuntimeException e) {
            LOG.error("[analitica] No se pudieron sembrar las cohortes y los parámetros de alertas", e);
        }
    }

    /**
     * Ejecuciones huérfanas: si nadie tiene el candado de la corrida (ninguna instancia está recalculando), las que siguen «en curso» las
     * dejó un reinicio o una falla: pasan a «interrumpida» y, si alguna era del último corte, su recálculo se vuelve a encolar. Devuelve
     * la corrida re-encolada, si la hubo.
     */
    Optional<Programada> recuperar() {
        List<Long> cortes = new ArrayList<>();
        try (Connection c = ds.getConnection()) {
            boolean libre;
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, CANDADO);
                try (ResultSet rs = ps.executeQuery()) {
                    libre = rs.next() && rs.getBoolean(1);
                }
            }
            if (!libre) {
                return Optional.empty();
            }
            try {
                String error = "Interrumpida por un reinicio de la aplicación; el recálculo del último corte se vuelve a encolar";
                cortes.addAll(escritura.execute(s -> {
                    OffsetDateTime ahora = OffsetDateTime.now(reloj);
                    List<Long> l = new ArrayList<>(jdbc.sql("""
                            UPDATE ana_cohortes_ejecuciones SET fin = :f, estado = 'interrumpida', error = :e WHERE fin IS NULL RETURNING corte_cargue_id""")
                            .param("f", ahora).param("e", error).query(Long.class).list());
                    l.addAll(jdbc.sql("""
                            UPDATE ana_alertas_ejecuciones SET fin = :f, estado = 'interrumpida', error = :e WHERE fin IS NULL RETURNING corte_cargue_id""")
                            .param("f", ahora).param("e", error).query(Long.class).list());
                    return l;
                }));
            } finally {
                try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    ps.setLong(1, CANDADO);
                    ps.execute();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo revisar las ejecuciones interrumpidas: " + e.getMessage(), e);
        }
        if (cortes.isEmpty()) {
            return Optional.empty();
        }
        LOG.warn("[analitica] {} ejecuciones de cohortes y alertas quedaron interrumpidas (cortes {})", cortes.size(), cortes);
        Optional<Long> vigente = corteVigente();
        if (vigente.isPresent() && cortes.contains(vigente.get())) {
            return Optional.of(programar(vigente.get(), "reinicio", null, null, true));
        }
        return Optional.empty();
    }

    // ---------- Programación ----------

    @EventListener
    public void alAplicarCargue(CargueAplicado e) {
        if (propiedades.recalcular()) {
            programar(e.cargueId(), "cargue", null, null, true);
        }
    }

    /** Último cargue aplicado (el corte vigente), si hay. */
    public Optional<Long> corteVigente() {
        return jdbc.sql("SELECT id FROM ana_cargues WHERE estado = 'aplicado' ORDER BY aplicado_en DESC, id DESC LIMIT 1").query(Long.class).optional();
    }

    /**
     * Programa una corrida: crea las ejecuciones «en curso» (para consultarlas ya) y la deja en el hilo de fondo. Una petición de un usuario
     * con otra corrida pendiente del mismo corte responde 409 (cada clic encolaría otro recorrido completo).
     *
     * @param versiones solo estas versiones (nueva versión o recálculo de una cohorte); nulo = todas las vigentes de cohortes activas
     * @param alertas   también corre el motor de alertas
     */
    public Programada programar(long corte, String origen, Long usuarioId, List<Long> versiones, boolean alertas) {
        if ("usuario".equals(origen)) {
            Optional<Long> pendiente = jdbc.sql("SELECT id FROM ana_cohortes_ejecuciones WHERE fin IS NULL AND corte_cargue_id = :c ORDER BY id LIMIT 1")
                    .param("c", corte).query(Long.class).optional();
            if (pendiente.isPresent()) {
                throw ErrorNegocio.conflicto("Ya hay un recálculo en curso para este corte (ejecución " + pendiente.get() + "): espere a que termine.");
            }
        }
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        long ec = jdbc.sql("""
                INSERT INTO ana_cohortes_ejecuciones (origen, corte_cargue_id, usuario_id, inicio) VALUES (:o, :c, :u, :a) RETURNING id""")
                .param("o", origen).param("c", corte).param("u", usuarioId).param("a", ahora).query(Long.class).single();
        Long ea = alertas ? jdbc.sql("""
                INSERT INTO ana_alertas_ejecuciones (origen, corte_cargue_id, usuario_id, inicio) VALUES (:o, :c, :u, :a) RETURNING id""")
                .param("o", origen).param("c", corte).param("u", usuarioId).param("a", ahora).query(Long.class).single() : null;
        Programada p = new Programada(ec, ea, corte);
        hilo.execute(() -> correrConCandado(p, versiones, usuarioId));
        return p;
    }

    private void correrConCandado(Programada p, List<Long> versiones, Long usuarioId) {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_lock(?)")) {
                ps.setLong(1, CANDADO);
                ps.execute();
            }
            try {
                correr(p, versiones, usuarioId);
            } finally {
                try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    ps.setLong(1, CANDADO);
                    ps.execute();
                }
            }
        } catch (Throwable e) {
            // También los Error (memoria, pila): nada queda «en curso» sin rastro
            LOG.error("[analitica] Falló el recálculo de cohortes y alertas del corte {}", p.corte(), e);
            fallar(p, e);
            if (e instanceof VirtualMachineError v) {
                throw v;
            }
        }
    }

    // ---------- Corrida ----------

    /** Lo leído en la foto del corte. */
    private record Lectura(Corte corte, List<MotorRiesgo.VersionCohorte> versiones, CatalogoClinico cat, Map<Tipo, Parametros> parametros,
                           Map<Tipo, Integer> versionParametros, Map<String, Map<Integer, Long>> umbrales, MotorRiesgo motor, DetectoresAlertas detectores,
                           String errorAlertas, Map<String, long[]> cohortes, Map<String, Long> etapas) {
    }

    void correr(Programada p, List<Long> soloVersiones, Long usuarioId) {
        long t0 = System.nanoTime();
        boolean alertas = p.ejecucionAlertas() != null;
        Lectura l = lectura.execute(s -> leer(p, soloVersiones, alertas));
        long t1 = System.nanoTime();
        l.etapas().put("lectura", ms(t0, t1));
        try {
            escritura.executeWithoutResult(s -> {
                fijar(jdbc);
                escribirCohortes(p, l);
                int[] resumen = null;
                String errorAlertas = l.errorAlertas();
                if (alertas && errorAlertas == null) {
                    try {
                        resumen = anidada.execute(x -> escribirAlertas(p, l));
                    } catch (RuntimeException e) {
                        // La escritura de alertas falla sola: la serie de cohortes del corte se guarda igual
                        LOG.error("[analitica] No se pudieron escribir las alertas del corte {}", p.corte(), e);
                        errorAlertas = "Falla técnica al escribir las alertas (" + e.getClass().getSimpleName() + "); revise el registro del servidor";
                    }
                }
                l.etapas().put("escritura", ms(t1, System.nanoTime()));
                long total = ms(t0, System.nanoTime());
                boolean versionesFallidas = !l.motor().fallosVersion.isEmpty();
                jdbc.sql("""
                        UPDATE ana_cohortes_ejecuciones SET fin = :f, estado = :estado, versiones = :v, filas = :n, duracion_ms = :d, etapas_ms = CAST(:e AS jsonb),
                               error = :err WHERE id = :id""").param("f", OffsetDateTime.now(reloj)).param("estado", versionesFallidas ? "con_fallos" : "ok")
                        .param("v", l.versiones().size()).param("n", l.motor().filas).param("d", total).param("e", aJson(l.etapas()))
                        .param("err", versionesFallidas ? Bitacora.cortar(l.motor().fallosVersion.size() + " versiones no evaluadas por error; revise el registro", 500)
                                : null).param("id", p.ejecucionCohortes()).update();
                if (alertas) {
                    if (resumen == null) {
                        jdbc.sql("UPDATE ana_alertas_ejecuciones SET fin = :f, estado = 'error', error = :e, duracion_ms = :d WHERE id = :id")
                                .param("f", OffsetDateTime.now(reloj)).param("e", Bitacora.cortar(errorAlertas, 500)).param("d", total)
                                .param("id", p.ejecucionAlertas()).update();
                    } else {
                        boolean fallos = l.detectores().porTipo.values().stream().anyMatch(x -> "error".equals(x.get("estado"))) || resumen[3] > 0;
                        jdbc.sql("""
                                UPDATE ana_alertas_ejecuciones SET fin = :f, estado = :estado, nuevas = :nu, actualizadas = :ac, avisos = :av,
                                       por_tipo = CAST(:pt AS jsonb), duracion_ms = :d, error = :err WHERE id = :id""")
                                .param("f", OffsetDateTime.now(reloj)).param("estado", fallos ? "con_fallos" : "ok").param("nu", resumen[0]).param("ac", resumen[1])
                                .param("av", resumen[2]).param("pt", aJson(l.detectores().porTipo)).param("d", total)
                                .param("err", resumen[3] > 0 ? resumen[3] + " avisos de severidad alta pendientes (se reintentan)" : null)
                                .param("id", p.ejecucionAlertas()).update();
                    }
                }
                bitacora.registrar("analitica_cohorte_recalculo", "Recálculo " + p.ejecucionCohortes() + " · corte " + p.corte() + " · " + l.versiones().size()
                        + " versiones · " + l.motor().filas + " registros · " + total + " ms" + (versionesFallidas ? " · con fallos" : ""), usuarioId,
                        usuario(usuarioId));
                if (alertas) {
                    bitacora.registrar("analitica_alerta_generada", "Ejecución " + p.ejecucionAlertas() + " del motor de alertas · corte " + p.corte() + " · "
                            + (resumen == null ? "error" : resumen[0] + " nuevas · " + resumen[1] + " reevaluadas · " + resumen[2] + " avisos de severidad alta"
                                    + (resumen[4] > 0 ? " · " + resumen[4] + " de periodos anteriores en un resumen" : "")), usuarioId, usuario(usuarioId));
                }
            });
        } finally {
            // A7.1: los archivos temporales de la membresía (AN.32) se borran aunque la escritura falle
            l.motor().liberar();
        }
        LOG.info("[analitica] Cohortes y alertas del corte {}: {} registros, {} versiones en {} ms {}", p.corte(), l.motor().filas, l.versiones().size(),
                ms(t0, System.nanoTime()), l.etapas());
    }

    private void fijar(JdbcClient j) {
        j.sql("SET LOCAL statement_timeout = '" + propiedades.minutos() + "min'").update();
        j.sql("SET LOCAL work_mem = '" + propiedades.memoria() + "MB'").update();
    }

    private Lectura leer(Programada p, List<Long> soloVersiones, boolean alertas) {
        Map<String, Long> etapas = new LinkedHashMap<>();
        long t = System.nanoTime();
        fijar(jdbc);
        Corte corte = consultas.corte(jdbc, p.corte()).orElseThrow(() -> new IllegalStateException("El corte " + p.corte() + " no es un cargue aplicado"));
        CatalogoClinico cat = CatalogoClinico.cargar(jdbc, corte);
        // Versiones a calcular
        List<Object[]> filas = jdbc.sql("""
                SELECT v.id, v.cohorte_id, c.codigo, v.criterios::text FROM ana_cohortes_versiones v JOIN ana_cohortes c ON c.id = v.cohorte_id
                WHERE (CAST(:solo AS bigint[]) IS NULL AND v.vigente AND c.activa) OR v.id = ANY(CAST(:solo AS bigint[]))
                ORDER BY c.orden, c.id""").param("solo", soloVersiones == null ? null : soloVersiones.toArray(Long[]::new))
                .query((rs, n) -> new Object[] {rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4)}).list();
        List<MotorRiesgo.VersionCohorte> versiones = new ArrayList<>();
        Map<String, long[]> cohortes = new HashMap<>();
        for (Object[] f : filas) {
            Criterio c;
            String motivo = null;
            try {
                c = AnaliticaCohortes.leer(deJson((String) f[3]));
            } catch (RuntimeException e) {
                // Una versión guardada que ya no es válida (p. ej. reglas más estrictas): no evaluada, las demás siguen
                LOG.error("[analitica] La versión {} de la cohorte {} no se puede leer", f[0], f[2], e);
                c = AnaliticaCohortes.leer(Map.of("tipo", "principiosMes", "minimo", 2));
                motivo = Bitacora.cortar("no evaluada: error al leer el criterio (" + e.getMessage() + ")", 300);
            }
            List<String> fuentes = new ArrayList<>();
            for (Lista lista : AnaliticaCohortes.listas(c)) {
                String fuente = cat.fuenteLista(lista);
                if (fuente == null) {
                    motivo = motivo != null ? motivo : "no evaluado: sin lista de " + lista.etiqueta() + " cargada";
                } else {
                    fuentes.add("Lista de " + lista.etiqueta() + ": " + fuente);
                }
            }
            int sinHom = cat.delInsumo.size();
            fuentes.add("ATC y principio activo: tabla maestra " + (corte.homNumero() == null ? "(sin versión publicada)" : "v" + corte.homNumero()) + "; "
                    + sinHom + " de " + cat.meds.size() + " datos originales sin homologar se clasifican con el dato del insumo");
            int idx = versiones.size();
            versiones.add(new MotorRiesgo.VersionCohorte(idx, (Long) f[0], (Long) f[1], (String) f[2], c, AnaliticaCohortes.atomos(c), AnaliticaCohortes.cronicos(c),
                    c instanceof AnaliticaCohortes.Particion, motivo, Bitacora.cortar(String.join(" · ", fuentes), 500), AnaliticaCohortes.gastoMovil(c)));
            if (motivo == null) {
                cohortes.put((String) f[2], new long[] {(Long) f[1], idx});
            }
        }
        // Parámetros de alertas (versión vigente de cada tipo); uno ilegible deja su tipo sin parámetros (no evaluado), no la corrida
        Map<Tipo, Parametros> parametros = new EnumMap<>(Tipo.class);
        Map<Tipo, Integer> versionParametros = new EnumMap<>(Tipo.class);
        jdbc.sql("SELECT tipo, version, parametros::text FROM ana_alertas_parametros").query((rs, n) -> {
            Tipo tipo = Tipo.deCodigo(rs.getString(1)).orElseThrow();
            try {
                parametros.put(tipo, AnaliticaAlertas.leer(tipo, deJson(rs.getString(3))));
                versionParametros.put(tipo, rs.getInt(2));
            } catch (RuntimeException e) {
                LOG.error("[analitica] Los parámetros de {} no son válidos", tipo.codigo(), e);
            }
            return null;
        }).list();
        Set<Tipo> evaluables = EnumSet.noneOf(Tipo.class);
        if (alertas) {
            for (Tipo tipo : Tipo.values()) {
                Parametros pa = parametros.get(tipo);
                if (pa != null && pa.activo() && DetectoresAlertas.noEvaluable(tipo, cat) == null) {
                    evaluables.add(tipo);
                }
            }
        }
        etapas.put("catalogo", ms(t, System.nanoTime()));
        t = System.nanoTime();
        // Umbral del gasto móvil por mes (percentil de los pacientes con dispensaciones en el mes): una sentencia por (meses, percentil)
        Map<String, Map<Integer, Long>> umbrales = new HashMap<>();
        for (MotorRiesgo.VersionCohorte v : versiones) {
            GastoMovil g = v.gasto();
            if (g != null && g.percentil() && !umbrales.containsKey(MotorRiesgo.llaveUmbral(g))) {
                umbrales.put(MotorRiesgo.llaveUmbral(g), umbralesGasto(jdbc, corte.cargues(), g.meses(), g.valorPercentil()));
            }
        }
        etapas.put("umbrales", ms(t, System.nanoTime()));
        t = System.nanoTime();
        Sonda s = sonda;
        MotorRiesgo motor = new MotorRiesgo(cat, versiones, umbrales, parametros, evaluables, corte, propiedades.gracia(), s);
        JdbcTemplate plantilla = new JdbcTemplate(ds);
        plantilla.setFetchSize(MotorRiesgo.FETCH);
        motor.recorrer(plantilla, corte.cargues());
        etapas.put("recorrido", ms(t, System.nanoTime()));
        DetectoresAlertas detectores = null;
        String errorAlertas = null;
        if (alertas) {
            t = System.nanoTime();
            try {
                detectores = new DetectoresAlertas(jdbc, anidada, corte, cat, parametros, motor, json, s);
                detectores.detectar(evaluables, cohortes);
            } catch (RuntimeException e) {
                // Las cohortes ya recorridas se escriben igual
                LOG.error("[analitica] El motor de alertas falló en el corte {}", corte.cargueId(), e);
                errorAlertas = "Falla técnica del motor de alertas (" + e.getClass().getSimpleName() + "); revise el registro del servidor";
            }
            etapas.put("alertas", ms(t, System.nanoTime()));
        }
        return new Lectura(corte, versiones, cat, parametros, versionParametros, umbrales, motor, detectores, errorAlertas, cohortes, etapas);
    }

    /**
     * Umbral del gasto móvil por mes: percentile_disc del gasto en los {@code meses} que terminan en el mes, de los pacientes con dispensaciones
     * en ese mes (definición única del alto costo: la usan la cohorte, la partición GRC y el tablero 8). Mes ordinal ⇒ centavos.
     */
    static Map<Integer, Long> umbralesGasto(JdbcClient j, List<Long> cargues, int meses, int percentil) {
        Map<Integer, Long> r = new TreeMap<>();
        String fraccion = BigDecimal.valueOf(percentil).movePointLeft(2).toPlainString();
        j.sql("""
                WITH pm AS (SELECT x.paciente_id AS p, (extract(year FROM x.fecha)::int * 12 + extract(month FROM x.fecha)::int - 1) AS mi,
                                   sum(x.valor_centavos) AS g
                            FROM ana_hechos_tablero x WHERE x.cargue_id = ANY(:c) GROUP BY 1, 2),
                     r AS (SELECT mi, sum(g) OVER (PARTITION BY p ORDER BY mi RANGE BETWEEN %d PRECEDING AND CURRENT ROW) AS s FROM pm)
                SELECT mi, percentile_disc(%s) WITHIN GROUP (ORDER BY s) FROM r GROUP BY mi""".formatted(meses - 1, fraccion))
                .param("c", cargues.toArray(Long[]::new)).query((rs, n) -> r.put(rs.getInt(1), rs.getLong(2))).list();
        return r;
    }

    // ---------- Escritura de cohortes ----------

    private void escribirCohortes(Programada p, Lectura l) {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        MotorRiesgo m = l.motor();
        Long[] ids = l.versiones().stream().map(MotorRiesgo.VersionCohorte::id).toArray(Long[]::new);
        jdbc.sql("DELETE FROM ana_cohortes_serie WHERE version_id = ANY(:v) AND corte_cargue_id = :c").param("v", ids).param("c", p.corte()).update();
        jdbc.sql("DELETE FROM ana_cohortes_miembros WHERE version_id = ANY(:v) AND corte_cargue_id = :c").param("v", ids).param("c", p.corte()).update();
        jdbc.sql("DELETE FROM ana_cohortes_conteos WHERE version_id = ANY(:v) AND corte_cargue_id = :c").param("v", ids).param("c", p.corte()).update();
        StringBuilder serie = new StringBuilder();
        for (Map.Entry<MotorRiesgo.Llave, long[]> e : m.serie.entrySet()) {
            MotorRiesgo.Llave k = e.getKey();
            if (m.fallosVersion.containsKey(k.version())) {
                continue;
            }
            MotorRiesgo.VersionCohorte v = l.versiones().get(k.version());
            String[] celda = celda(k.celda());
            long[] s = e.getValue();
            serie.append(v.id()).append('\t').append(p.corte()).append('\t').append(AnaliticaCohortes.deOrdinal(k.mes()).atDay(1)).append('\t').append(celda[0])
                    .append('\t').append(limpio(celda[1])).append('\t').append(limpio(celda[2])).append('\t').append(limpio(k.grupo())).append('\t').append(s[0])
                    .append('\t').append(s[1]).append('\t').append(s[2]).append('\t').append(s[3]).append('\t').append(s[4]).append('\t').append(s[5]).append('\n');
        }
        copiar("COPY ana_cohortes_serie (version_id, corte_cargue_id, mes, nivel, fuerza, codigo, grupo, pacientes, ingresos, salidas, dispensaciones, "
                + "unidades_milesimas, gasto_centavos) FROM STDIN", serie);
        serie.setLength(0);
        StringBuilder conteos = new StringBuilder();
        LocalDate pd = AnaliticaCohortes.deOrdinal(m.periodoDesde).atDay(1);
        LocalDate ph = AnaliticaCohortes.deOrdinal(m.periodoHasta).atDay(1);
        for (MotorRiesgo.VersionCohorte v : l.versiones()) {
            if (m.fallosVersion.containsKey(v.indice())) {
                continue;
            }
            copiar("COPY ana_cohortes_miembros (version_id, corte_cargue_id, mes, paciente_id, fuerzas, esms, fuerzas_esms, grupo) FROM STDIN",
                    m.miembros.get(v.indice()));
            if (v.particion() || v.noEvaluado() != null) {
                continue;
            }
            // El nivel nacional siempre (0 si nadie): marca que el corte tiene sus conteos precalculados
            Map<String, long[]> c = new TreeMap<>(m.conteos.getOrDefault(v.indice(), Map.of()));
            c.putIfAbsent("N", new long[1]);
            c.forEach((cel, n) -> {
                String[] x = celda(cel);
                conteos.append(v.id()).append('\t').append(p.corte()).append('\t').append(pd).append('\t').append(ph).append('\t').append(x[0]).append('\t')
                        .append(limpio(x[1])).append('\t').append(limpio(x[2])).append('\t').append(n[0]).append('\n');
            });
        }
        copiar("COPY ana_cohortes_conteos (version_id, corte_cargue_id, desde, hasta, nivel, fuerza, codigo, pacientes) FROM STDIN", conteos);
        for (MotorRiesgo.VersionCohorte v : l.versiones()) {
            Map<String, String> umbral = new TreeMap<>();
            if (v.gasto() != null && v.gasto().percentil()) {
                l.umbrales().getOrDefault(MotorRiesgo.llaveUmbral(v.gasto()), Map.of()).forEach((mes, centavos) -> umbral.put(
                        AnaliticaCohortes.deOrdinal(mes).toString(), BigDecimal.valueOf(centavos, 2).toPlainString()));
            } else if (v.gasto() != null) {
                umbral.put("absoluto", v.gasto().valor().toPlainString());
            }
            String motivo = v.noEvaluado() != null ? v.noEvaluado() : m.fallosVersion.get(v.indice());
            jdbc.sql("""
                    INSERT INTO ana_cohortes_resultados (version_id, corte_cargue_id, ejecucion_id, estado, motivo, umbrales, fuentes, calculado)
                    VALUES (:v, :c, :e, :estado, :motivo, CAST(:u AS jsonb), :f, :a)
                    ON CONFLICT (version_id, corte_cargue_id) DO UPDATE SET ejecucion_id = EXCLUDED.ejecucion_id, estado = EXCLUDED.estado,
                        motivo = EXCLUDED.motivo, umbrales = EXCLUDED.umbrales, fuentes = EXCLUDED.fuentes, miembros = true, calculado = EXCLUDED.calculado""")
                    .param("v", v.id()).param("c", p.corte()).param("e", p.ejecucionCohortes()).param("estado", motivo == null ? "evaluada" : "no_evaluada")
                    .param("motivo", motivo).param("u", aJson(umbral)).param("f", v.fuentes()).param("a", ahora).update();
        }
        // La membresía se conserva para los últimos cortes de cada versión; los anteriores quedan solo con su serie (reproducible)
        List<Long> purgados = jdbc.sql("""
                UPDATE ana_cohortes_resultados r SET miembros = false
                WHERE r.version_id = ANY(:v) AND r.miembros AND r.corte_cargue_id NOT IN (
                    SELECT x.corte_cargue_id FROM ana_cohortes_resultados x WHERE x.version_id = r.version_id ORDER BY x.corte_cargue_id DESC LIMIT :k)
                RETURNING r.corte_cargue_id""").param("v", ids).param("k", propiedades.cortesMembresia()).query(Long.class).list();
        if (!purgados.isEmpty()) {
            jdbc.sql("""
                    DELETE FROM ana_cohortes_miembros m USING ana_cohortes_resultados r
                    WHERE r.version_id = m.version_id AND r.corte_cargue_id = m.corte_cargue_id AND NOT r.miembros AND m.version_id = ANY(:v)""")
                    .param("v", ids).update();
        }
    }

    /** Celda del motor ⇒ {nivel, Fuerza (solo ESM), código}. */
    static String[] celda(String c) {
        if (c.equals("N")) {
            return new String[] {"nacional", "", ""};
        }
        if (c.startsWith("F|")) {
            return new String[] {"fuerza", "", c.substring(2)};
        }
        String par = c.substring(2);
        int i = par.indexOf('|');
        return new String[] {"esm", par.substring(0, i), par.substring(i + 1)};
    }

    private static String limpio(String s) {
        return s == null ? "" : s.replace("\\", "").replace("\t", " ").replace("\n", " ");
    }

    /** COPY en la conexión de la transacción en curso, leyendo el búfer sin copiarlo a otro String (memoria del lado de la aplicación). */
    /** COPY de la membresía de una versión (A7.1: por lotes desde el archivo temporal y el búfer, AN.32). */
    private void copiar(String sql, Volcado datos) {
        if (datos.lineas() == 0) {
            return;
        }
        Connection c = DataSourceUtils.getConnection(ds);
        try (Reader r = datos.lector()) {
            c.unwrap(PGConnection.class).getCopyAPI().copyIn(sql, r);
        } catch (SQLException | java.io.IOException e) {
            throw new IllegalStateException("No se pudo escribir con COPY: " + e.getMessage(), e);
        } finally {
            DataSourceUtils.releaseConnection(c, ds);
            datos.close();
        }
    }

    private void copiar(String sql, StringBuilder datos) {
        if (datos.isEmpty()) {
            return;
        }
        Connection c = DataSourceUtils.getConnection(ds);
        try {
            c.unwrap(PGConnection.class).getCopyAPI().copyIn(sql, lector(datos));
        } catch (SQLException | java.io.IOException e) {
            throw new IllegalStateException("No se pudo escribir con COPY: " + e.getMessage(), e);
        } finally {
            DataSourceUtils.releaseConnection(c, ds);
        }
    }

    /** Lector sobre un CharSequence (no duplica el búfer como haría toString()). */
    private static Reader lector(CharSequence s) {
        return new Reader() {
            private int pos;

            @Override
            public int read(char[] b, int off, int len) {
                if (pos >= s.length()) {
                    return -1;
                }
                int n = Math.min(len, s.length() - pos);
                for (int i = 0; i < n; i++) {
                    b[off + i] = s.charAt(pos + i);
                }
                pos += n;
                return n;
            }

            @Override
            public void close() {
                // nada que cerrar
            }
        };
    }

    // ---------- Escritura de alertas ----------

    /** Alerta ya guardada con lo que la reevaluación necesita. */
    private record Previa(long id, Severidad severidad, BigDecimal umbral, String estado, String aviso) {
    }

    /**
     * Inserta o reevalúa cada candidata y avisa las de severidad alta. Devuelve {nuevas, reevaluadas, avisos enviados, avisos pendientes,
     * avisos agrupados (periodos anteriores)}. Las alertas validadas o descartadas conservan las cifras con que se validaron.
     */
    private int[] escribirAlertas(Programada p, Lectura l) {
        int nuevas = 0;
        int actualizadas = 0;
        int[] avisos = new int[3]; // enviados, pendientes, agrupados
        int k = tableros.supresion();
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        int ultimoMes = AnaliticaCohortes.ordinal(l.corte().datosHasta().getYear(), l.corte().datosHasta().getMonthValue());
        int desdeAviso = ultimoMes - propiedades.avisoMeses() + 1;
        List<DetectoresAlertas.Candidata> cand = l.detectores().candidatas;
        Map<DetectoresAlertas.Candidata, String> llaves = new LinkedHashMap<>();
        for (DetectoresAlertas.Candidata c : cand) {
            llaves.put(c, AnaliticaAlertas.llave(c.tipo, c.nivel, c.codigoNivel(), c.dimension, c.valor, AnaliticaCohortes.deOrdinal(c.mes).toString()));
        }
        // Las alertas existentes de todas las candidatas en lotes (no una consulta por candidata)
        Map<String, Previa> previas = new HashMap<>();
        List<String> todas = new ArrayList<>(llaves.values());
        for (int i = 0; i < todas.size(); i += 5000) {
            jdbc.sql("SELECT id, llave, severidad, umbral, estado, aviso FROM ana_alertas WHERE llave = ANY(:l) ORDER BY id FOR UPDATE")
                    .param("l", todas.subList(i, Math.min(todas.size(), i + 5000)).toArray(String[]::new)).query((rs, n) -> previas.put(rs.getString(2).strip(),
                            new Previa(rs.getLong(1), Severidad.deCodigo(rs.getString(3)), rs.getBigDecimal(4), rs.getString(5), rs.getString(6)))).list();
        }
        for (DetectoresAlertas.Candidata c : cand) {
            String llave = llaves.get(c);
            YearMonth desde = AnaliticaCohortes.deOrdinal(c.mesDesde);
            YearMonth hasta = AnaliticaCohortes.deOrdinal(c.mes);
            String periodo = desde.equals(hasta) ? c.periodoTexto() : desde + " a " + hasta;
            Parametros pa = l.parametros().get(c.tipo);
            Contexto ctx = new Contexto(c.tipo, c.nivelTexto(), c.dimensionTexto, c.valorTexto, periodo, c.observado, c.referencia, c.umbral, c.severidad,
                    c.registros, c.pacientes, c.gasto, c.detalle, c.fuente);
            Previa previa = previas.get(llave);
            long id;
            boolean avisar;
            if (previa == null) {
                id = jdbc.sql("""
                        INSERT INTO ana_alertas (tipo, parametros_version, llave, nivel, fuerza, esm, punto_id, dimension, dimension_texto, valor, valor_texto,
                                                 cohorte_id, periodo_desde, periodo_hasta, severidad, umbral, valor_observado, valor_referencia, unidad,
                                                 registros, pacientes, gasto, causa, valoracion, recomendacion, fuente_referencia, seleccion,
                                                 corte_cargue_id, ultima_evaluacion_cargue_id, ejecucion_id, generada, actualizada)
                        VALUES (:tipo, :pv, :llave, :nivel, :fuerza, :esm, :punto, :dim, :dimt, :valor, :valort, :cohorte, :desde, :hasta, :sev, :umbral,
                                :obs, :ref, :unidad, :reg, :pac, :gasto, :causa, :val, :rec, :fuente, CAST(:sel AS jsonb), :corte, :corte, :ej, :ahora, :ahora)
                        RETURNING id""")
                        .param("tipo", c.tipo.codigo()).param("pv", l.versionParametros().get(c.tipo)).param("llave", llave).param("nivel", c.nivel)
                        .param("fuerza", c.fuerza).param("esm", c.esm).param("punto", c.punto).param("dim", c.dimension).param("dimt", c.dimensionTexto)
                        .param("valor", Bitacora.cortar(c.valor, 200)).param("valort", Bitacora.cortar(c.valorTexto, 300)).param("cohorte", c.cohorteId)
                        .param("desde", desde.atDay(1)).param("hasta", hasta.atEndOfMonth()).param("sev", c.severidad.codigo()).param("umbral", c.umbral)
                        .param("obs", c.observado).param("ref", c.referencia).param("unidad", c.tipo.unidad()).param("reg", c.registros)
                        .param("pac", c.pacientes).param("gasto", c.gasto).param("causa", AnaliticaAlertas.causa(ctx)).param("val", AnaliticaAlertas.valoracion(ctx))
                        .param("rec", AnaliticaAlertas.recomendacion(pa.plantilla(), ctx)).param("fuente", c.fuente == null ? null : Bitacora.cortar(c.fuente, 300))
                        .param("sel", aJson(c.seleccion)).param("corte", p.corte()).param("ej", p.ejecucionAlertas()).param("ahora", ahora)
                        .query(Long.class).single();
                nuevas++;
                avisar = c.severidad == Severidad.ALTA;
            } else if ("validada".equals(previa.estado()) || "descartada".equals(previa.estado())) {
                // Validada o descartada: conserva las cifras y el análisis con que la persona la juzgó (soporte de un hallazgo)
                continue;
            } else {
                id = previa.id();
                Severidad antes = previa.severidad();
                boolean sube = c.severidad.ordinal() > antes.ordinal();
                Contexto vigente = sube ? ctx : new Contexto(c.tipo, ctx.nivel(), ctx.dimension(), ctx.valor(), ctx.periodo(), ctx.observado(), ctx.referencia(),
                        previa.umbral(), antes, ctx.registros(), ctx.pacientes(), ctx.gasto(), ctx.detalle(), ctx.fuente());
                // Reevaluación: cifras y análisis al corte nuevo; la versión de parámetros y el umbral con que nació solo cambian si sube de banda
                jdbc.sql("""
                        UPDATE ana_alertas SET valor_observado = :obs, valor_referencia = :ref, registros = :reg, pacientes = :pac, gasto = :gasto,
                               causa = :causa, valoracion = :val, seleccion = CAST(:sel AS jsonb), ultima_evaluacion_cargue_id = :corte, actualizada = :ahora,
                               severidad = CASE WHEN :sube THEN :sev ELSE severidad END, umbral = CASE WHEN :sube THEN :umbral ELSE umbral END,
                               parametros_version = CASE WHEN :sube THEN :pv ELSE parametros_version END,
                               recomendacion = CASE WHEN :sube THEN :rec ELSE recomendacion END
                        WHERE id = :id""")
                        .param("obs", c.observado).param("ref", c.referencia).param("reg", c.registros).param("pac", c.pacientes).param("gasto", c.gasto)
                        .param("causa", AnaliticaAlertas.causa(vigente)).param("val", AnaliticaAlertas.valoracion(vigente)).param("sel", aJson(c.seleccion))
                        .param("corte", p.corte()).param("ahora", ahora).param("sube", sube).param("sev", c.severidad.codigo()).param("umbral", c.umbral)
                        .param("pv", l.versionParametros().get(c.tipo)).param("rec", AnaliticaAlertas.recomendacion(pa.plantilla(), ctx)).param("id", id).update();
                actualizadas++;
                avisar = sube && c.severidad == Severidad.ALTA || "pendiente".equals(previa.aviso());
            }
            if (avisar) {
                if (c.mes >= desdeAviso) {
                    avisos[intentarAviso(id, ctx, k) ? 0 : 1]++;
                } else {
                    // Periodo anterior (primer cargue histórico o reglaje de umbrales): sin correo por alerta, va en el resumen de la ejecución
                    jdbc.sql("UPDATE ana_alertas SET aviso = 'agrupado' WHERE id = :id AND aviso <> 'enviado'").param("id", id).update();
                    avisos[2]++;
                }
            }
        }
        if (avisos[2] > 0) {
            avisosAgrupados(p, avisos[2]);
        }
        return new int[] {nuevas, actualizadas, avisos[0], avisos[1], avisos[2]};
    }

    /**
     * Dispara el aviso de una alerta (clave ana:&lt;id&gt;: idempotente, un solo correo). Si no queda evento, busca el de esa clave (ya avisada
     * antes) y si tampoco hay, la deja «pendiente» para reintentarla. Devuelve si quedó enviado.
     */
    private boolean intentarAviso(long id, Contexto ctx, int k) {
        ServicioAlertas.Disparo d = avisos.disparar(TIPO_AVISO, "ana:" + id, AnaliticaAlertas.detalleAviso(id, ctx, k));
        Long evento = d.eventoId() != null ? d.eventoId() : jdbc.sql("SELECT id FROM alertas_eventos WHERE tipo = :t AND clave = :c ORDER BY id LIMIT 1")
                .param("t", TIPO_AVISO).param("c", "ana:" + id).query(Long.class).optional().orElse(null);
        if (evento == null) {
            jdbc.sql("UPDATE ana_alertas SET aviso = 'pendiente' WHERE id = :id").param("id", id).update();
            LOG.warn("[analitica] El aviso de la alerta {} no quedó registrado: queda pendiente y se reintenta", id);
            return false;
        }
        jdbc.sql("UPDATE ana_alertas SET aviso = 'enviado', evento_alerta_id = :e WHERE id = :id").param("e", evento).param("id", id).update();
        return d.avisado() || d.eventoId() == null;
    }

    /** Un solo aviso por ejecución con las alertas altas de periodos anteriores (sin datos personales). */
    private void avisosAgrupados(Programada p, int n) {
        avisos.disparar(TIPO_AVISO, "ana_ej:" + p.ejecucionAlertas(), "Motor de alertas analíticas · ejecución " + p.ejecucionAlertas() + " · corte "
                + p.corte() + ": " + n + " alertas de severidad alta de periodos anteriores a los últimos " + propiedades.avisoMeses()
                + " meses del corte (sin aviso individual). Revise la consola de alertas de la Suite DIGSA.");
    }

    /** Reintenta los avisos pendientes (un aviso que falló no se pierde). Corre sola cada 15 minutos y al terminar cada corrida. */
    @Scheduled(fixedDelayString = "${digsa.analitica.riesgo.reintento-avisos-ms:900000}", initialDelayString = "${digsa.analitica.riesgo.reintento-avisos-ms:900000}")
    public void reintentarAvisos() {
        try {
            int k = tableros.supresion();
            escritura.executeWithoutResult(s -> jdbc.sql("""
                    SELECT a.id, a.tipo, a.nivel, a.fuerza, a.esm, a.dimension_texto, a.valor_texto, a.periodo_desde, a.periodo_hasta, a.valor_observado,
                           a.valor_referencia, a.umbral, a.severidad, a.registros, a.pacientes, a.gasto
                    FROM ana_alertas a WHERE a.aviso = 'pendiente' ORDER BY a.id LIMIT 200 FOR UPDATE SKIP LOCKED""").query((rs, n) -> {
                        Tipo t = Tipo.deCodigo(rs.getString("tipo")).orElseThrow();
                        String nivel = switch (rs.getString("nivel")) {
                            case "fuerza" -> "Fuerza " + rs.getString("fuerza");
                            case "esm" -> "ESM " + rs.getString("esm") + " (" + rs.getString("fuerza") + ")";
                            default -> "Nacional";
                        };
                        LocalDate d = rs.getDate("periodo_desde").toLocalDate();
                        LocalDate h = rs.getDate("periodo_hasta").toLocalDate();
                        String periodo = YearMonth.from(d).equals(YearMonth.from(h)) ? AnaliticaConsulta.etiquetaPeriodo(d, AnaliticaConsulta.Granularidad.MES)
                                : YearMonth.from(d) + " a " + YearMonth.from(h);
                        Contexto ctx = new Contexto(t, nivel, rs.getString("dimension_texto"), rs.getString("valor_texto"), periodo,
                                rs.getBigDecimal("valor_observado"), rs.getBigDecimal("valor_referencia"), rs.getBigDecimal("umbral"),
                                Severidad.deCodigo(rs.getString("severidad")), rs.getLong("registros"), rs.getLong("pacientes"), rs.getBigDecimal("gasto"), null, null);
                        intentarAviso(rs.getLong("id"), ctx, k);
                        return null;
                    }).list());
        } catch (RuntimeException e) {
            LOG.error("[analitica] No se pudieron reintentar los avisos pendientes de alertas analíticas", e);
        }
    }

    // ---------- Fallas ----------

    private void fallar(Programada p, Throwable e) {
        try {
            String error = Bitacora.cortar("Falla técnica (" + e.getClass().getSimpleName() + "); revise el registro del servidor", 500);
            escritura.executeWithoutResult(s -> {
                OffsetDateTime ahora = OffsetDateTime.now(reloj);
                jdbc.sql("UPDATE ana_cohortes_ejecuciones SET fin = :f, estado = 'error', error = :e WHERE id = :id AND fin IS NULL").param("f", ahora)
                        .param("e", error).param("id", p.ejecucionCohortes()).update();
                if (p.ejecucionAlertas() != null) {
                    jdbc.sql("UPDATE ana_alertas_ejecuciones SET fin = :f, estado = 'error', error = :e WHERE id = :id AND fin IS NULL").param("f", ahora)
                            .param("e", error).param("id", p.ejecucionAlertas()).update();
                }
            });
        } catch (RuntimeException x) {
            LOG.error("[analitica] Tampoco se pudo marcar la ejecución fallida", x);
        }
    }

    // ---------- Apoyo ----------

    private String usuario(Long id) {
        return id == null ? null : jdbc.sql("SELECT usuario FROM usuarios WHERE id = :id").param("id", id).query(String.class).optional().orElse(null);
    }

    String aJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    Map<String, Object> deJson(String s) {
        try {
            return json.readValue(s, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long ms(long a, long b) {
        return (b - a) / 1_000_000;
    }

    @Override
    public void destroy() {
        hilo.shutdownNow();
    }

    /** Para las pruebas: ¿hay corridas pendientes o en curso? */
    boolean ocupado() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM ana_cohortes_ejecuciones WHERE fin IS NULL)").query(Boolean.class).single();
    }
}
