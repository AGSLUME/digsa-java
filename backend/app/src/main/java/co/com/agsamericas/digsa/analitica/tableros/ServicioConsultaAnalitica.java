package co.com.agsamericas.digsa.analitica.tableros;

import co.com.agsamericas.digsa.analitica.riesgo.PropiedadesRiesgo;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Columna;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.FiltroAplicado;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Fila;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Kpi;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Metadatos;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Tabla;
import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.Ambito;
import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.Armado;
import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.Conjunto;
import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.Corte;
import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.Grupo;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.CampoOpcional;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.ConsultaAnalitica;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Dimension;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Intencion;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Metrica;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Nivel;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Tablero;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Tipo;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Variable;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Servicio de consulta común de la analítica (capa semántica, analitica-tableros): lo usan los tableros, las descargas, la trazabilidad y
 * el asistente de IA (A6, que le entrega una {@link ConsultaAnalitica} validada; ver la documentación de esa clase). Cada consulta:
 * <ol>
 * <li>valida la forma de la consulta estructurada (422 con el motivo);</li>
 * <li>resuelve la fecha de corte (el cargue elegido o el último aplicado: sus cargues visibles y la versión de la tabla maestra vigente);</li>
 * <li>resuelve cada valor de filtro contra los valores de los cargues visibles dentro del alcance del usuario (inexistente o fuera del
 * alcance: 422 con el mismo mensaje, contrato de reportes 2.11);</li>
 * <li>ejecuta el SQL parametrizado de {@link SemanticaSql} con el alcance en el SQL, en una transacción READ ONLY REPEATABLE READ (todas las
 * cifras salen de la misma foto) con un presupuesto de tiempo POR PETICIÓN ({@link Plazo});</li>
 * <li>devuelve el resultado con orden total, BigDecimal, metadatos de 14.8, supresión de celdas pequeñas (si está activa) y la huella
 * SHA-256 del contenido (códigos y cifras, no los nombres de los catálogos).</li>
 * </ol>
 * El resultado no depende del usuario ni de la hora (salvo los avisos): mismo alcance, filtros y corte ⇒ misma huella.
 */
@Service
public class ServicioConsultaAnalitica {

    /** Filas máximas de una consulta por dimensiones (asistente o consulta guiada): más responde 422 («acote»), nunca un resultado truncado. */
    static final int MAX_FILAS_CONSULTA = 5000;
    /** Opciones de filtro por respuesta; más llevan {@code truncado} («escriba más letras»). */
    static final int MAX_OPCIONES = 300;
    static final String TIEMPO_AGOTADO = "La consulta tardó demasiado; acote el periodo o los filtros.";
    static final String SIN_CARGUES = "Aún no hay cargues aplicados";

    private final JdbcClient jdbc;
    private final TransactionTemplate lectura;
    private final PropiedadesTableros propiedades;
    private final Clock reloj;
    private final CacheResultados cache;
    /** Cohortes sensibles y su k (A7.1: el filtro por una cohorte sensible usa la supresión de AN.24). */
    private final PropiedadesRiesgo riesgo;

    public ServicioConsultaAnalitica(JdbcClient jdbc, PlatformTransactionManager transacciones, PropiedadesTableros propiedades, Clock reloj,
                                     PropiedadesRiesgo riesgo) {
        this.jdbc = jdbc;
        this.propiedades = propiedades;
        this.riesgo = riesgo;
        this.reloj = reloj;
        this.cache = new CacheResultados(propiedades.tamanoCache());
        this.lectura = new TransactionTemplate(transacciones);
        this.lectura.setReadOnly(true);
        this.lectura.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.lectura.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---------- Transacción de lectura ----------

    /** Lectura con el presupuesto de una petición en pantalla (≤ 3 min, A5 2.7). */
    public <T> T leer(Function<JdbcClient, T> f) {
        return leer(propiedades.tiempoMaximo(), true, f);
    }

    /** Lectura con el presupuesto de una descarga (≤ 10 min, A5 2.7). */
    <T> T leerDescarga(Function<JdbcClient, T> f) {
        return leer(propiedades.tiempoMaximoDescarga(), false, f);
    }

    /**
     * Corre en una transacción READ ONLY REPEATABLE READ con un presupuesto de tiempo de TODA la petición: antes de cada sentencia se fija el
     * statement_timeout con lo que queda; al agotarse responde 422 «acote el periodo». work_mem y paralelismo propios de la analítica
     * (los agrupamientos por hash de 2 M filas no deben ir a disco).
     *
     * @param verificarAlFinal también rechaza el resultado si la petición completa pasó del presupuesto (no en el CSV en flujo: ya salió)
     */
    public <T> T leer(int segundos, boolean verificarAlFinal, Function<JdbcClient, T> f) {
        Plazo plazo = new Plazo(jdbc, segundos * 1000L);
        try {
            T r = lectura.execute(s -> {
                jdbc.sql("SET LOCAL work_mem = '" + propiedades.memoriaTrabajo() + "MB'").update();
                jdbc.sql("SET LOCAL max_parallel_workers_per_gather = " + propiedades.trabajadoresParalelos()).update();
                // Agrupar por hash (no ordenar): el planificador sobrestima los grupos (paciente × clave) y elige ordenar, que es 2–3 veces más lento
                jdbc.sql("SET LOCAL enable_sort = off").update();
                // El planificador subestima los grupos (paciente × clave) y no paraleliza el agrupamiento; con estos costos sí lo hace
                jdbc.sql("SET LOCAL parallel_setup_cost = 100").update();
                jdbc.sql("SET LOCAL parallel_tuple_cost = 0.01").update();
                return f.apply(plazo.cliente());
            });
            if (verificarAlFinal) {
                plazo.verificar();
            }
            return r;
        } catch (QueryTimeoutException e) {
            throw Plazo.agotado();
        } catch (DataAccessException e) {
            if (e.getMostSpecificCause() instanceof java.sql.SQLException q && "57014".equals(q.getSQLState())) {
                throw Plazo.agotado();
            }
            throw e;
        }
    }

    /** Plazo de la transacción en curso (para las sentencias que no pasan por el cliente, como el CSV en flujo). */
    static void fijarPlazo(JdbcClient j) {
        // El cliente de leer() fija el statement_timeout antes de cada sentencia: una sentencia vacía basta para fijarlo ahora
        j.sql("SELECT 1").query(Integer.class).single();
    }

    // ---------- Corte ----------

    /**
     * El cargue aplicado que fija el corte (nulo = el último). Vacío si no hay cargues aplicados; un id que no es un cargue aplicado: 422.
     *
     * <p>Decisión (revisión A3 2026-10-23, se mantiene la del diseño): el instante del corte es el mayor entre la aplicación del cargue y la
     * versión de la tabla maestra que publicó ese cargue. Las versiones que publica Revisión después (aprobaciones y devoluciones) no
     * cambian ningún corte existente (mismo corte ⇒ mismo resultado, 14.8) y se ven con el siguiente cargue; mientras tanto el resultado
     * lo advierte (ver {@link #tablaMaestraPosterior}).
     */
    public Optional<Corte> corte(JdbcClient j, Long cargue) {
        List<Object[]> c = j.sql("""
                SELECT c.id, c.aplicado_en,
                       greatest(c.aplicado_en, coalesce((SELECT max(v.creada) FROM hom_version v WHERE v.cargue_id = c.id AND v.estado = 'publicada'),
                                c.aplicado_en)) AS instante
                FROM ana_cargues c
                WHERE c.estado = 'aplicado' AND (CAST(:id AS bigint) IS NULL OR c.id = CAST(:id AS bigint))
                ORDER BY c.aplicado_en DESC, c.id DESC LIMIT 1""")
                .param("id", cargue).query((rs, n) -> new Object[] {rs.getLong(1), odt(rs.getTimestamp(2)), odt(rs.getTimestamp(3))}).list();
        if (c.isEmpty()) {
            if (cargue != null) {
                throw ErrorNegocio.validacion("La fecha de corte no es válida: elija un cargue aplicado.");
            }
            return Optional.empty();
        }
        long id = (Long) c.getFirst()[0];
        OffsetDateTime instante = (OffsetDateTime) c.getFirst()[2];
        List<Long> cargues = j.sql("SELECT cargue_id FROM ana_cargues_visibles(:t) ORDER BY cargue_id").param("t", instante).query(Long.class).list();
        Object[] hv = j.sql("SELECT v.id, v.numero FROM hom_version v WHERE v.id = hom_version_vigente(:t)").param("t", instante)
                .query((rs, n) -> new Object[] {rs.getInt(1), rs.getInt(2)}).optional().orElse(new Object[] {null, null});
        Object[] rango = j.sql("SELECT min(periodo_desde), max(periodo_hasta) FROM ana_cargues WHERE id = ANY(:ids)")
                .param("ids", cargues.toArray(Long[]::new)).query((rs, n) -> new Object[] {fecha(rs.getDate(1)), fecha(rs.getDate(2))}).single();
        return Optional.of(new Corte(id, (OffsetDateTime) c.getFirst()[1], instante, (Integer) hv[0], (Integer) hv[1], cargues,
                (LocalDate) rango[0], (LocalDate) rango[1]));
    }

    /** Aviso si hay una versión publicada de la tabla maestra posterior al corte (aprobaciones de Revisión que se verán con el próximo cargue). */
    Optional<String> tablaMaestraPosterior(JdbcClient j, Corte c) {
        return j.sql("""
                SELECT v.numero, v.creada FROM hom_version v
                WHERE v.estado = 'publicada' AND v.creada > :t ORDER BY v.numero DESC LIMIT 1""").param("t", c.instante())
                .query((rs, n) -> "Hay una tabla maestra más reciente (v" + rs.getInt(1) + ", publicada el "
                        + AnaliticaConsulta.fechaHora(odt(rs.getTimestamp(2))) + ") que este corte (v" + (c.homNumero() == null ? "—" : c.homNumero())
                        + "): sus aprobaciones de Revisión se verán con el próximo cargue.")
                .optional();
    }

    private static OffsetDateTime odt(Timestamp t) {
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static LocalDate fecha(java.sql.Date d) {
        return d == null ? null : d.toLocalDate();
    }

    /**
     * Cargues aplicados para elegir el corte (el más reciente primero). Con {@code detalle} (alcance nacional con la pantalla de cargues o
     * de trazabilidad) lleva el nombre del archivo y los registros procesados; los demás ven solo el id, la fecha, la fuente y el periodo
     * (sin volumen nacional ni nombres de archivo, que están fuera de su alcance).
     */
    public List<Map<String, Object>> cortes(boolean detalle) {
        return leer(j -> j.sql("""
                SELECT id, aplicado_en, fuente_texto, nombre_archivo, periodo_desde, periodo_hasta, procesados, reemplazado_en
                FROM ana_cargues WHERE estado = 'aplicado' ORDER BY aplicado_en DESC, id DESC LIMIT 200""").query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    OffsetDateTime a = odt(rs.getTimestamp("aplicado_en"));
                    m.put("aplicadoEn", a);
                    m.put("texto", "Corte " + AnaliticaConsulta.fechaHora(a) + " · cargue " + rs.getLong("id"));
                    m.put("fuente", rs.getString("fuente_texto"));
                    m.put("archivo", detalle ? rs.getString("nombre_archivo") : null);
                    m.put("periodoDesde", fecha(rs.getDate("periodo_desde")));
                    m.put("periodoHasta", fecha(rs.getDate("periodo_hasta")));
                    m.put("procesados", detalle ? rs.getLong("procesados") : null);
                    m.put("reemplazado", rs.getTimestamp("reemplazado_en") != null);
                    return m;
                }).list());
    }

    // ---------- Filtros ----------

    /**
     * Opciones de una variable en el alcance del usuario y visibles al corte (toda la historia), con su etiqueta; búsqueda por texto. Más
     * de {@value #MAX_OPCIONES}: las primeras y {@code truncado = true} (la pantalla pide escribir más letras).
     */
    public Map<String, Object> opciones(Variable v, String texto, Long cargue, Alcance alcance) {
        return leer(j -> {
            Optional<Corte> c = corte(j, cargue);
            List<Map<String, String>> r = new ArrayList<>();
            boolean truncado = false;
            if (c.isPresent() && v.deCohorte()) {
                // A7.1: cohortes usables en el corte o grupos de GCF / GRC del alcance (pocas: sin búsqueda en el servidor)
                Ambito a = new Ambito(c.get(), alcance, Map.of(), c.get().datosDesde(), c.get().datosHasta(), AnaliticaConsulta.Granularidad.MES);
                String t = texto == null ? "" : texto.strip().toLowerCase(java.util.Locale.ROOT);
                for (Map<String, String> o : FiltroCohortes.opciones(j, a, v, riesgo)) {
                    if (t.isEmpty() || o.get("valor").toLowerCase(java.util.Locale.ROOT).contains(t)
                            || o.get("etiqueta").toLowerCase(java.util.Locale.ROOT).contains(t)) {
                        r.add(o);
                    }
                }
            } else if (c.isPresent()) {
                Ambito a = new Ambito(c.get(), alcance, Map.of(), c.get().datosDesde(), c.get().datosHasta(), AnaliticaConsulta.Granularidad.MES);
                List<String> valores = existentes(j, a, v, null);
                Etiquetas et = new Etiquetas(j);
                Dimension d = dimensionDe(v, null);
                if (d != null) {
                    et.cargar(d, valores);
                }
                String t = texto == null ? "" : texto.strip().toLowerCase(java.util.Locale.ROOT);
                for (String x : valores) {
                    String etiqueta = etiquetaFiltro(et, v, x);
                    if (t.isEmpty() || x.toLowerCase(java.util.Locale.ROOT).contains(t) || etiqueta.toLowerCase(java.util.Locale.ROOT).contains(t)) {
                        if (r.size() >= MAX_OPCIONES) {
                            truncado = true;
                            break;
                        }
                        r.add(Map.of("valor", x, "etiqueta", etiqueta));
                    }
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("opciones", r);
            m.put("truncado", truncado);
            return m;
        });
    }

    private static String etiquetaFiltro(Etiquetas et, Variable v, String x) {
        Dimension d = dimensionDe(v, x);
        return d == null ? x : et.de(d, x);
    }

    /** Dimensión con que se nombra un valor de filtro (el ATC según su largo). */
    static Dimension dimensionDe(Variable v, String valor) {
        return switch (v) {
            case FUERZA -> Dimension.FUERZA;
            case DISAN -> Dimension.DISAN;
            case REGIONAL -> Dimension.REGIONAL;
            case ESM -> Dimension.ESM;
            case MODALIDAD -> Dimension.MODALIDAD;
            case ATC -> valor == null ? null : switch (valor.length()) {
                case 1 -> Dimension.ATC1;
                case 3 -> Dimension.ATC2;
                case 4 -> Dimension.ATC3;
                case 5 -> Dimension.ATC4;
                default -> Dimension.ATC5;
            };
            case PRINCIPIO -> Dimension.PRINCIPIO_ACTIVO;
            case MEDICAMENTO -> Dimension.MEDICAMENTO;
            case CUM -> Dimension.CUM;
            case CIE10 -> valor != null && valor.length() == 4 ? Dimension.CIE10 : null;
            case ESTADO -> Dimension.ESTADO_HOMOLOGACION;
            case SSFM, COHORTE, GCF, GRC -> null;
        };
    }

    /**
     * Valores de la variable que existen en los cargues visibles al corte y en el alcance (toda la historia, sin los demás filtros), leídos
     * de ana_cargue_valores (no de los hechos). Con {@code solo}, se limita a esos valores (ATC y CIE-10 por prefijo del código FINAL
     * homologado, el mismo que filtra y agrupa).
     */
    List<String> existentes(JdbcClient j, Ambito a, Variable v, List<String> solo) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("cargues", a.corte().cargues().toArray(Long[]::new));
        p.put("hv", a.corte().homVersion());
        String alc = SemanticaSql.alcance(a.alcance(), p);
        String base = " FROM ana_cargue_valores x%s WHERE " + SemanticaSql.VISIBLES + " AND " + alc + " AND x.variable = '%s'";
        String tm = " JOIN tm ON tm.origen_id = x.valor::bigint AND tm.estado = 'homologado'";
        String tc = " JOIN tc ON tc.codigo_original = x.valor AND tc.estado = 'homologado'";
        String conTm = "WITH " + SemanticaSql.CTE_TM + " ";
        String conTc = "WITH " + SemanticaSql.CTE_TC + " ";
        if (solo != null) {
            p.put("solo", solo.toArray(String[]::new));
        }
        String sql = switch (v) {
            case FUERZA, DISAN, REGIONAL, ESM, MODALIDAD, CUM -> "SELECT DISTINCT x.valor" + base.formatted("", v.parametro())
                    + (solo == null ? "" : " AND x.valor = ANY(CAST(:solo AS text[]))");
            case PRINCIPIO -> conTm + "SELECT DISTINCT tm.fin_principio_activo" + base.formatted(tm, "medicamento") + " AND tm.fin_principio_activo IS NOT NULL"
                    + (solo == null ? "" : " AND tm.fin_principio_activo = ANY(CAST(:solo AS text[]))");
            case MEDICAMENTO -> conTm + "SELECT DISTINCT coalesce(tm.fin_cum, 'ORIGEN-' || x.valor)" + base.formatted(tm, "medicamento")
                    + (solo == null ? "" : " AND coalesce(tm.fin_cum, 'ORIGEN-' || x.valor) = ANY(CAST(:solo AS text[]))");
            case SSFM -> conTm + "SELECT DISTINCT tm.fin_codigo_ssfm" + base.formatted(tm, "medicamento") + " AND tm.fin_codigo_ssfm IS NOT NULL"
                    + (solo == null ? "" : " AND tm.fin_codigo_ssfm = ANY(CAST(:solo AS text[]))");
            case ATC -> solo == null
                    ? conTm + "SELECT DISTINCT left(tm.fin_atc, n) FROM (SELECT DISTINCT tm.fin_atc" + base.formatted(tm, "medicamento")
                            + " AND tm.fin_atc IS NOT NULL) tm, unnest(ARRAY[1, 3, 4, 5, 7]) n WHERE length(tm.fin_atc) >= n"
                    : conTm + "SELECT s FROM unnest(CAST(:solo AS text[])) s WHERE EXISTS (SELECT 1" + base.formatted(tm, "medicamento")
                            + " AND tm.fin_atc LIKE s || '%')";
            case CIE10 -> solo == null
                    ? conTc + "SELECT DISTINCT tc.codigo_final::text" + base.formatted(tc, "cie10")
                    : conTc + "SELECT s FROM unnest(CAST(:solo AS text[])) s WHERE EXISTS (SELECT 1" + base.formatted(tc, "cie10")
                            + " AND tc.codigo_final LIKE s || '%')";
            case ESTADO -> null;
            case COHORTE, GCF, GRC -> throw new IllegalStateException("Cohorte, GCF y GRC se resuelven con FiltroCohortes");
        };
        if (sql == null) {
            List<String> todos = java.util.Arrays.stream(AnaliticaHomologacion.Estado.values()).map(AnaliticaHomologacion.Estado::codigo).toList();
            return solo == null ? todos : solo.stream().filter(todos::contains).toList();
        }
        return new ArrayList<>(new TreeSet<>(j.sql(sql).params(p).query(String.class).list()));
    }

    /** Cada valor pedido debe existir en el alcance: si no, 422 con el mismo mensaje para inexistente y fuera del alcance. */
    void validarFiltros(JdbcClient j, Ambito a) {
        for (Map.Entry<Variable, List<String>> e : a.filtros().entrySet()) {
            List<String> normales = e.getValue().stream().filter(x -> !AnaliticaConsulta.SIN_HOMOLOGAR.equals(x) && !AnaliticaConsulta.SIN_DATO.equals(x))
                    .toList();
            if (normales.isEmpty() || e.getKey().deCohorte()) {
                // Cohorte, GCF y GRC se validan al resolverlos contra el corte (FiltroCohortes)
                continue;
            }
            Set<String> hay = Set.copyOf(existentes(j, a, e.getKey(), normales));
            for (String x : normales) {
                if (!hay.contains(x)) {
                    throw new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, AnaliticaConsulta.mensajeInexistente(e.getKey(), x),
                            Map.of("variable", e.getKey().parametro()));
                }
            }
        }
    }

    /** Campos opcionales que traen los cargues visibles (calculados al aplicar cada cargue: ana_cargues.campos_presentes). */
    Set<CampoOpcional> presentes(JdbcClient j, Corte c) {
        EnumSet<CampoOpcional> r = EnumSet.noneOf(CampoOpcional.class);
        List<String> claves = j.sql("SELECT DISTINCT unnest(campos_presentes) FROM ana_cargues WHERE id = ANY(:ids)")
                .param("ids", c.cargues().toArray(Long[]::new)).query(String.class).list();
        for (CampoOpcional campo : CampoOpcional.values()) {
            if (claves.contains(campo.clave())) {
                r.add(campo);
            }
        }
        return r;
    }

    // ---------- Preparación común (tableros, descargas, trazabilidad y asistente) ----------

    /** Consulta resuelta: corte, ámbito validado, ámbito de comparación y vista. */
    record Preparada(ConsultaAnalitica consulta, Corte corte, Ambito ambito, Ambito comparacion, Nivel nivel, List<Nivel> niveles) {
    }

    /**
     * Valida la forma, resuelve el corte, el periodo (sin fechas: los últimos 12 meses con datos), la vista y los filtros en el alcance.
     * Vacío si aún no hay cargues aplicados.
     */
    Optional<Preparada> preparar(JdbcClient j, ConsultaAnalitica q, Alcance alcance) {
        List<String> problemas = q.problemas();
        if (!problemas.isEmpty()) {
            throw ErrorNegocio.validacion(String.join(" ", problemas));
        }
        List<Nivel> niveles = niveles(alcance);
        Optional<Corte> oc = corte(j, q.corte());
        if (oc.isEmpty()) {
            return Optional.empty();
        }
        Corte c = oc.get();
        LocalDate[] defecto = AnaliticaConsulta.periodoPorDefecto(c.datosDesde(), c.datosHasta());
        LocalDate desde = q.desde() != null ? q.desde() : q.hasta() != null || defecto == null ? c.datosDesde() : defecto[0];
        LocalDate hasta = q.hasta() != null ? q.hasta() : c.datosHasta();
        if (desde == null || hasta == null) {
            return Optional.empty();
        }
        if (desde.isAfter(hasta)) {
            throw ErrorNegocio.validacion("La fecha «Desde» es posterior a «Hasta».");
        }
        Ambito a = new Ambito(c, alcance, q.filtros(), desde, hasta, q.granularidad());
        validarFiltros(j, a);
        // A7.1: cohorte, GCF y GRC contra la membresía del mismo corte (versión, criterio y si es sensible)
        FiltroCohortes.Resolucion res = FiltroCohortes.resolver(j, a, riesgo);
        a = new Ambito(c, alcance, q.filtros(), desde, hasta, q.granularidad(), res.cohortes(), res.sensible());
        Nivel nivel = q.nivel() == null ? niveles.getFirst() : q.nivel();
        if (!niveles.contains(nivel)) {
            throw ErrorNegocio.validacion("La vista " + nivel.etiqueta() + " no está en su alcance.");
        }
        if (nivel == Nivel.FUERZA && !(alcance instanceof Alcance.Fuerza) && q.valores(Variable.FUERZA).size() != 1) {
            throw ErrorNegocio.validacion("Elija una sola Fuerza para la vista por Fuerza.");
        }
        if (nivel == Nivel.ESM && !(alcance instanceof Alcance.Esm) && q.valores(Variable.ESM).size() != 1) {
            throw ErrorNegocio.validacion("Elija un solo ESM para la vista por ESM.");
        }
        LocalDate[] comp = AnaliticaConsulta.periodoComparacion(desde, hasta, q.comparacion());
        Ambito ac = comp == null ? null : a.conPeriodo(comp[0], comp[1]);
        return Optional.of(new Preparada(q, c, a, ac, nivel, niveles));
    }

    /** Vistas a las que llega el alcance; la primera es la de por defecto. */
    static List<Nivel> niveles(Alcance alcance) {
        return switch (alcance) {
            case Alcance.Todo t -> List.of(Nivel.NACIONAL, Nivel.FUERZA, Nivel.ESM);
            case Alcance.Fuerza f -> List.of(Nivel.FUERZA, Nivel.ESM);
            case Alcance.Esm e -> List.of(Nivel.ESM);
            default -> throw new ErrorNegocio(HttpStatus.FORBIDDEN, "Su perfil no tiene un alcance de datos para la analítica farmacéutica.");
        };
    }

    // ---------- Tableros ----------

    /** Un tablero completo (intención TABLERO) con el presupuesto de una consulta en pantalla. */
    public ResultadoTablero tablero(ConsultaAnalitica consulta, Alcance alcance) {
        return leer(j -> armarTablero(j, consulta, alcance));
    }

    /** El mismo tablero con el presupuesto de una descarga (≤ 10 min). */
    public ResultadoTablero tableroParaDescarga(ConsultaAnalitica consulta, Alcance alcance) {
        return leerDescarga(j -> armarTablero(j, consulta, alcance));
    }

    private ResultadoTablero armarTablero(JdbcClient j, ConsultaAnalitica consulta, Alcance alcance) {
        ConsultaAnalitica q = consulta.conIntencion(Intencion.TABLERO);
        Optional<Preparada> op = preparar(j, q, alcance);
        List<Nivel> niveles = niveles(alcance);
        if (op.isEmpty()) {
            return sinDatos(q, niveles);
        }
        Preparada p = op.get();
        Tablero t = q.tablero();
        // Mismo alcance, consulta resuelta y corte ⇒ mismo resultado (14.8): se reutiliza el ya calculado; solo los avisos se recalculan
        String llave = llaveCache(p);
        ResultadoTablero guardado = cache.obtener(llave);
        if (guardado != null) {
            return conAdvertencias(guardado, advertencias(j, p.corte()));
        }
        ResultadoTablero r = construirTablero(j, q, p, t);
        cache.guardar(llave, r);
        return r;
    }

    private ResultadoTablero construirTablero(JdbcClient j, ConsultaAnalitica q, Preparada p, Tablero t) {
        Set<CampoOpcional> presentes = presentes(j, p.corte());
        if (!t.disponible()) {
            return resultado(j, p, t.titulo(), false, AnaliticaConsulta.SIGUIENTE_FASE, List.of(), List.of(), List.of(), List.of(), 0);
        }
        ConstructorTableros c = new ConstructorTableros(j, p.ambito(), p.comparacion(), q, p.nivel(), propiedades, presentes);
        c.construir();
        List<String> obs = new ArrayList<>(c.observaciones);
        if (c.tablas.stream().anyMatch(x -> x.filas().stream().anyMatch(f -> AnaliticaConsulta.SIN_HOMOLOGAR.equals(f.clave())))) {
            obs.add("Los registros sin homologar cuentan en los totales bajo «Sin homologar» (tabla maestra v" + p.corte().homNumero() + ").");
        }
        if (c.registros == 0) {
            obs.add("Sin dispensaciones con estos filtros.");
        }
        return resultado(j, p, t.titulo(), true, null, c.kpis, c.tablas, obs, c.noDisponible, c.registros);
    }

    /**
     * Llave del resultado en caché: todo lo que lo determina (tablero, vista, periodo resuelto, filtros, granularidad, comparación, orden,
     * nivel ATC, alcance, corte con sus cargues y versión de la tabla maestra, y los parámetros que cambian las cifras).
     */
    private String llaveCache(Preparada p) {
        ConsultaAnalitica q = p.consulta();
        return String.join("|", q.tablero().codigo(), p.nivel().codigo(), String.valueOf(p.ambito().desde()), String.valueOf(p.ambito().hasta()),
                p.ambito().granularidad().codigo(), q.comparacion().codigo(), q.orden().codigo(), String.valueOf(q.nivelAtc()),
                alcanceCodigo(p.ambito().alcance()), String.valueOf(p.corte().cargueId()), String.valueOf(p.corte().cargues()),
                String.valueOf(p.corte().homVersion()), String.valueOf(p.ambito().filtros()), String.valueOf(propiedades.supresion()),
                String.valueOf(propiedades.percentil()), String.valueOf(propiedades.poliformulado()), versionesCohorte(p.ambito()),
                String.valueOf(k(p.ambito())));
    }

    // ---------- Cohorte, GCF y GRC para la predicción (A7.1, AN.30) ----------


    /** Top 20 por gasto de las cohortes o de los grupos de GCF / GRC del nivel (valores para proyectar), en el alcance. */
    public List<Map<String, String>> topCohortes(Variable v, Nivel nivel, String nivelValor, Long cargue, Alcance alcance) {
        return leer(j -> corte(j, cargue).map(c -> FiltroCohortes.top(j, new Ambito(c, alcance, Map.of(), c.datosDesde(), c.datosHasta(),
                AnaliticaConsulta.Granularidad.MES), v, nivel, nivelValor, riesgo)).orElse(List.of()));
    }

    /**
     * ¿Es sensible el valor de la variable en el corte (cohorte por código o criterio; CIE-10, ATC o grupo de GCF por los códigos de VIH y
     * huérfanas)? La predicción por él usa la supresión reforzada (AN.31).
     */
    public boolean sensible(Variable v, String valor, Long cargue) {
        return leer(j -> corte(j, cargue).map(c -> FiltroCohortes.sensible(j, v, valor, c.cargueId(), riesgo)).orElse(false));
    }

    /** Pacientes distintos del ámbito (el mismo SQL de los tableros). */
    private static long pacientesDe(JdbcClient j, Ambito a) {
        Armado ar = SemanticaSql.agrupar(a, Conjunto.total(), 0, true);
        return j.sql(ar.sql()).params(ar.parametros()).query((rs, n) -> ar.leer(rs)).optional().map(g -> g.pacientes() == null ? 0L : g.pacientes())
                .orElse(0L);
    }

    /** k de la supresión con o sin una cohorte sensible (AN.24). */
    public int kSupresion(boolean cohorteSensible) {
        return AnaliticaConsulta.kConCohorte(propiedades.supresion(), riesgo.kSensibles(), cohorteSensible);
    }

    /** Versiones de cohorte con que se resolvieron los filtros (A7.1): «cohorte=12,15;gcf=3» (otra versión ⇒ otro resultado). */
    static String versionesCohorte(Ambito a) {
        StringBuilder sb = new StringBuilder();
        a.cohortes().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> sb.append(sb.isEmpty() ? "" : ";").append(e.getKey().parametro())
                .append('=').append(String.join(",", e.getValue().cohortes().stream().map(c -> String.valueOf(c.versionId())).toList())));
        return sb.toString();
    }

    /**
     * Umbral k de la supresión de celdas pequeñas del ámbito: el general de los tableros o, si un filtro es de una cohorte sensible (VIH,
     * huérfanas), al menos el de las sensibles (AN.24, A7.1). Con el de las sensibles se ocultan también las cifras de la celda.
     */
    int k(Ambito a) {
        return AnaliticaConsulta.kConCohorte(propiedades.supresion(), riesgo.kSensibles(), a.sensible());
    }

    /** ¿La supresión del ámbito es la reforzada de las cohortes sensibles? (oculta también gasto, unidades y dispensaciones de la celda) */
    boolean reforzada(Ambito a) {
        return a.sensible() && riesgo.kSensibles() > 0;
    }

    private static ResultadoTablero conAdvertencias(ResultadoTablero r, List<String> advertencias) {
        return new ResultadoTablero(r.tablero(), r.titulo(), r.nivel(), r.niveles(), r.disponible(), r.mensaje(), r.kpis(), r.tablas(),
                List.copyOf(advertencias), r.observaciones(), r.noDisponible(), r.metadatos(), r.huella());
    }

    /** Vacía la caché de resultados (pruebas). */
    void vaciarCache() {
        cache.vaciar();
    }

    /** Caché LRU de resultados de tableros (en memoria, por instancia). Un corte no cambia: no hay que invalidar, solo desalojar. */
    static final class CacheResultados {

        private final int maximo;
        private final java.util.LinkedHashMap<String, ResultadoTablero> m;

        CacheResultados(int maximo) {
            this.maximo = maximo;
            this.m = new java.util.LinkedHashMap<>(16, 0.75f, true);
        }

        synchronized ResultadoTablero obtener(String llave) {
            return maximo <= 0 ? null : m.get(llave);
        }

        synchronized void guardar(String llave, ResultadoTablero r) {
            if (maximo <= 0) {
                return;
            }
            m.put(llave, r);
            while (m.size() > maximo) {
                m.remove(m.keySet().iterator().next());
            }
        }

        synchronized void vaciar() {
            m.clear();
        }
    }

    /**
     * Métricas por dimensiones (intención METRICAS): la consulta del asistente y de la consulta guiada. Una tabla con una fila por
     * combinación (máximo {@value #MAX_FILAS_CONSULTA}; más responde 422) y los KPI del total, con los pacientes distintos exactos.
     */
    public ResultadoTablero consultar(ConsultaAnalitica consulta, Alcance alcance) {
        return consultar(consulta, alcance, null);
    }

    /** Resultado de una consulta con las versiones de cohorte con que se resolvieron sus filtros (A7.1: la predicción guarda la usada). */
    public record ConVersiones(ResultadoTablero resultado, Map<Variable, List<Long>> versiones) {
    }

    /** La consulta por dimensiones y las versiones de cohorte resueltas en la MISMA transacción (sin volver a resolverlas después). */
    public ConVersiones consultarConVersiones(ConsultaAnalitica consulta, Alcance alcance) {
        Map<Variable, List<Long>> v = new java.util.EnumMap<>(Variable.class);
        ResultadoTablero r = consultar(consulta, alcance, v);
        return new ConVersiones(r, java.util.Collections.unmodifiableMap(v));
    }

    private ResultadoTablero consultar(ConsultaAnalitica consulta, Alcance alcance, Map<Variable, List<Long>> versiones) {
        ConsultaAnalitica q0 = consulta.conIntencion(Intencion.METRICAS);
        return leer(j -> {
            Optional<Preparada> op = preparar(j, q0, alcance);
            if (op.isEmpty()) {
                return sinDatos(q0, niveles(alcance));
            }
            Preparada p = op.get();
            if (versiones != null) {
                p.ambito().cohortes().forEach((k, f) -> versiones.put(k, f.cohortes().stream().map(SemanticaSql.CohorteResuelta::versionId).toList()));
            }
            // A7.1 (AN.31): con un filtro sensible siempre se cuentan y se muestran los pacientes de cada fila (la supresión reforzada los necesita)
            ConsultaAnalitica q = reforzada(p.ambito()) && !q0.metricas().contains(Metrica.PACIENTES) ? new ConsultaAnalitica(q0.intencion(), q0.tablero(),
                    java.util.stream.Stream.concat(q0.metricas().stream(), java.util.stream.Stream.of(Metrica.PACIENTES)).toList(), q0.dimensiones(),
                    q0.filtros(), q0.desde(), q0.hasta(), q0.granularidad(), q0.comparacion(), q0.corte(), q0.nivel(), q0.nivelAtc(), q0.orden()) : q0;
            boolean pacientes = q.metricas().contains(Metrica.PACIENTES) || q.metricas().contains(Metrica.PROMEDIO_PACIENTE);
            Armado at = SemanticaSql.agrupar(p.ambito(), Conjunto.total(), 0, pacientes);
            Grupo total = j.sql(at.sql()).params(at.parametros()).query((rs, n) -> at.leer(rs)).optional()
                    .orElse(new Grupo(0, Map.of(), Map.of(), BigDecimal.ZERO, BigDecimal.ZERO, 0, pacientes ? 0L : null, null));
            List<Grupo> filas = List.of();
            if (!q.dimensiones().isEmpty()) {
                Armado ad = SemanticaSql.agrupar(p.ambito(), new Conjunto(q.dimensiones(), List.of()), 1, pacientes);
                filas = j.sql(ad.sql() + " LIMIT " + (MAX_FILAS_CONSULTA + 1)).params(ad.parametros()).query((rs, n) -> ad.leer(rs)).list();
            }
            if (filas.size() > MAX_FILAS_CONSULTA) {
                throw ErrorNegocio.validacion("La consulta da más de " + MAX_FILAS_CONSULTA + " filas; acote las dimensiones o los filtros.");
            }
            ConstructorTableros c = new ConstructorTableros(j, p.ambito(), p.comparacion(), q, p.nivel(), propiedades, Set.of());
            Grupo comparado = null;
            if (p.comparacion() != null) {
                Armado ac = SemanticaSql.agrupar(p.comparacion(), Conjunto.total(), 0, pacientes);
                comparado = j.sql(ac.sql()).params(ac.parametros()).query((rs, n) -> ac.leer(rs)).optional()
                        .orElse(new Grupo(0, Map.of(), Map.of(), BigDecimal.ZERO, BigDecimal.ZERO, 0, pacientes ? 0L : null, null));
            }
            List<Kpi> kpis = new ArrayList<>();
            for (Metrica m : q.metricas()) {
                if (m.ordenable() || m == Metrica.PROMEDIO_DISPENSACION || m == Metrica.PROMEDIO_PACIENTE) {
                    kpis.add(c.kpi(m.codigo(), m.etiqueta(), m.tipo(), valor(total, total, m), comparado == null ? null : valor(comparado, comparado, m),
                            null, rastreoKpi(m)));
                }
            }
            Etiquetas et = new Etiquetas(j);
            for (Dimension d : q.dimensiones()) {
                et.cargar(d, filas.stream().map(x -> x.clave(d)).toList());
            }
            List<Columna> columnas = new ArrayList<>();
            q.dimensiones().forEach(d -> columnas.add(new Columna(d.codigo(), d.etiqueta(), Tipo.TEXTO)));
            q.metricas().forEach(m -> columnas.add(new Columna(m.codigo(), m.etiqueta(), m.tipo())));
            List<Grupo> orden = new ArrayList<>(filas);
            Metrica primera = q.metricas().getFirst();
            if (q.dimensiones().contains(Dimension.PERIODO)) {
                orden.sort(java.util.Comparator.comparing((Grupo x) -> x.clave(Dimension.PERIODO)).thenComparing(x -> clave(x, q.dimensiones())));
            } else {
                orden.sort(AnaliticaConsulta.ordenRanking(x -> valor(x, total, primera), x -> clave(x, q.dimensiones())));
            }
            List<Fila> fs = new ArrayList<>();
            for (Grupo x : orden) {
                List<Object> v = new ArrayList<>();
                // Se rastrea solo si cada dimensión de la fila es un filtro (departamento, municipio, tipo de fórmula y especialidad no lo son)
                Map<String, String> r = new LinkedHashMap<>();
                for (Dimension d : q.dimensiones()) {
                    v.add(et.de(d, x.clave(d)));
                    Map<String, String> rd = ConstructorTableros.rastreo(d, x.clave(d));
                    if (rd == null || r == null) {
                        r = null;
                    } else {
                        r.putAll(rd);
                    }
                }
                for (Metrica m : q.metricas()) {
                    Object val = valor(x, total, m);
                    v.add(m.tipo() == Tipo.ENTERO && val instanceof BigDecimal b ? (Object) b.longValue() : val);
                }
                fs.add(ResultadoTablero.fila(clave(x, q.dimensiones()), r, columnas, v, "dato"));
            }
            Tabla tabla = new Tabla("consulta", "Consulta", q.dimensiones().contains(Dimension.PERIODO) && q.dimensiones().size() == 1 ? "serie"
                    : "barras", primera.codigo(), null, List.copyOf(columnas), fs, pacientes ? ConstructorTableros.NOTA_PACIENTES : null);
            String titulo = q.tablero() == null ? "Consulta de analítica" : q.tablero().titulo();
            return resultado(j, p, titulo, true, null, kpis, List.of(tabla), total.dispensaciones() == 0
                    ? List.of("Sin dispensaciones con estos filtros.") : List.of(), List.of(), total.dispensaciones());
        });
    }

    /** Las sumas se rastrean (la traza suma gasto, unidades y registros); los promedios, la participación y los pacientes no. */
    private static Map<String, String> rastreoKpi(Metrica m) {
        return m == Metrica.GASTO || m == Metrica.UNIDADES || m == Metrica.DISPENSACIONES ? Map.of() : null;
    }

    private static String clave(Grupo g, List<Dimension> dims) {
        return String.join("|", dims.stream().map(g::clave).toList());
    }

    private static BigDecimal valor(Grupo g, Grupo total, Metrica m) {
        return switch (m) {
            case GASTO -> g.gasto();
            case UNIDADES -> g.unidades();
            case DISPENSACIONES -> BigDecimal.valueOf(g.dispensaciones());
            case PACIENTES -> g.pacientes() == null ? null : BigDecimal.valueOf(g.pacientes());
            case PROMEDIO_DISPENSACION -> AnaliticaConsulta.promedio(g.gasto(), g.dispensaciones());
            case PROMEDIO_PACIENTE -> g.pacientes() == null ? null : AnaliticaConsulta.promedio(g.gasto(), g.pacientes());
            case PARTICIPACION -> AnaliticaConsulta.participacion(g.gasto(), total.gasto());
            case VARIACION_ABSOLUTA, VARIACION_PORCENTUAL -> null;
        };
    }

    private ResultadoTablero sinDatos(ConsultaAnalitica q, List<Nivel> niveles) {
        String titulo = q.tablero() == null ? "Consulta de analítica" : q.tablero().titulo();
        return new ResultadoTablero(q.tablero() == null ? null : q.tablero().codigo(), titulo, niveles.getFirst().codigo(),
                niveles.stream().map(Nivel::codigo).toList(), false, SIN_CARGUES, List.of(), List.of(), List.of(), List.of(), List.of(), null, null);
    }

    /** Texto de la regla de supresión para los metadatos. */
    public String textoSupresion() {
        int k = propiedades.supresion();
        return k <= 0 ? "Desactivada (todas las cifras se muestran; parámetro supresion-minima = 0)"
                : "Celdas con menos de " + k + " pacientes distintos: se ocultan sus pacientes y promedios por paciente («<" + k
                        + "»); el mínimo y el máximo del gasto por paciente no se publican. Las sumas no cambian (cuadran con el insumo).";
    }

    /** Texto de la regla de supresión de un ámbito: con una cohorte sensible en el filtro, la reforzada de AN.24 (A7.1). */
    String textoSupresion(Ambito a) {
        if (!reforzada(a)) {
            return textoSupresion();
        }
        int k = k(a);
        return "Reforzada por filtrar una cohorte sensible (VIH o huérfanas; parámetro supresion-sensibles = " + riesgo.kSensibles() + "): toda celda con "
                + "menos de " + k + " pacientes distintos oculta sus pacientes Y sus cifras (gasto, unidades, dispensaciones, promedios y participación, "
                + "«<" + k + "»); si en una tabla queda una sola celda oculta se oculta también la siguiente menor (no se deduce por diferencia); las "
                + "tablas sin conteo de pacientes por celda se ocultan en el nivel ESM (series por periodo) y siempre el mapa de calor ESM × periodo; el "
                + "mínimo y el máximo del gasto por paciente no se publican.";
    }

    /** Arma el resultado con los metadatos, la supresión de celdas pequeñas y la huella (sin usuario ni hora). */
    private ResultadoTablero resultado(JdbcClient j, Preparada p, String tituloBase, boolean disponible, String mensaje, List<Kpi> kpis,
                                       List<Tabla> tablas, List<String> observaciones, List<String> noDisponible, long registros) {
        Etiquetas et = new Etiquetas(j);
        List<FiltroAplicado> filtros = new ArrayList<>();
        for (Map.Entry<Variable, List<String>> e : p.ambito().filtros().entrySet()) {
            List<String> textos = new ArrayList<>();
            SemanticaSql.FiltroCohorte fc = p.ambito().cohortes().get(e.getKey());
            if (fc != null) {
                // A7.1: la cohorte con su versión y criterio (14.8: la descarga dice con qué definición se filtró)
                if (e.getKey() == Variable.GCF) {
                    et.cargar(Dimension.ATC2, e.getValue().stream().filter(x -> !x.startsWith("SIN_")).toList());
                }
                for (int i = 0; i < e.getValue().size(); i++) {
                    textos.add(e.getKey() == Variable.COHORTE ? fc.cohortes().get(i).texto()
                            : FiltroCohortes.etiqueta(et, e.getKey(), e.getValue().get(i)) + " — " + fc.cohortes().getFirst().texto());
                }
                filtros.add(new FiltroAplicado(e.getKey().parametro(), e.getKey().etiqueta(), e.getValue(), textos));
                continue;
            }
            for (String x : e.getValue()) {
                Dimension d = dimensionDe(e.getKey(), x);
                if (d != null) {
                    et.cargar(d, List.of(x));
                }
                textos.add(d == null ? (AnaliticaConsulta.SIN_HOMOLOGAR.equals(x) ? "Sin homologar" : AnaliticaConsulta.SIN_DATO.equals(x) ? "Sin dato" : x)
                        : et.de(d, x));
            }
            filtros.add(new FiltroAplicado(e.getKey().parametro(), e.getKey().etiqueta(), e.getValue(), textos));
        }
        String alcanceTexto = alcanceTexto(j, p.ambito().alcance());
        String establecimiento;
        if (p.ambito().alcance() instanceof Alcance.Esm) {
            establecimiento = alcanceTexto;
        } else if (!p.ambito().filtros().getOrDefault(Variable.ESM, List.of()).isEmpty()) {
            et.cargar(Dimension.ESM, p.ambito().filtros().get(Variable.ESM));
            establecimiento = String.join(", ", p.ambito().filtros().get(Variable.ESM).stream().map(x -> et.de(Dimension.ESM, x)).toList());
        } else {
            establecimiento = "Todos los del alcance";
        }
        Corte c = p.corte();
        String fuente = "Tabla maestra homologada " + (c.homNumero() == null ? "(sin versión publicada)" : "v" + c.homNumero()) + " · cargues "
                + String.join(", ", c.cargues().stream().map(String::valueOf).toList()) + " · insumo de dispensación del OPL";
        Metadatos m = new Metadatos(c.cargueId(), c.aplicadoEn(), "Corte " + AnaliticaConsulta.fechaHora(c.aplicadoEn()) + " (cargue " + c.cargueId() + ")",
                c.homNumero(), c.cargues(), fuente, p.ambito().desde(), p.ambito().hasta(),
                AnaliticaConsulta.fecha(p.ambito().desde()) + " – " + AnaliticaConsulta.fecha(p.ambito().hasta()), p.ambito().granularidad().etiqueta(),
                p.consulta().comparacion().etiqueta(), p.comparacion() == null ? null
                        : AnaliticaConsulta.fecha(p.comparacion().desde()) + " – " + AnaliticaConsulta.fecha(p.comparacion().hasta()),
                alcanceTexto, establecimiento, List.copyOf(filtros), registros, c.datosHasta(), textoSupresion(p.ambito()));
        String vista = switch (p.nivel()) {
            case NACIONAL -> "Nacional";
            case FUERZA -> "Fuerza " + (p.ambito().alcance() instanceof Alcance.Fuerza f ? f.codigo() : p.consulta().valores(Variable.FUERZA).getFirst());
            case ESM -> "ESM " + (p.ambito().alcance() instanceof Alcance.Esm ? alcanceTexto.replaceFirst("^ESM ", "")
                    : p.consulta().valores(Variable.ESM).getFirst());
        };
        List<String> advertencias = advertencias(j, c);
        // A7.1: con una cohorte sensible en el filtro, la supresión reforzada de AN.24; el nivel ESM es el del alcance, la vista o un filtro de ESM
        boolean nivelEsm = p.ambito().alcance() instanceof Alcance.Esm || p.nivel() == Nivel.ESM || p.ambito().filtros().containsKey(Variable.ESM);
        // Con la reforzada, los pacientes del total y del periodo de comparación se cuentan siempre aparte (no dependen de que el tablero los
        // muestre): con pocos se oculta todo o la comparación (AN.31)
        boolean ref = reforzada(p.ambito());
        Long totalPacientes = ref ? pacientesDe(j, p.ambito()) : null;
        Long comparadoPacientes = ref && p.comparacion() != null ? pacientesDe(j, p.comparacion()) : null;
        Supresion s = new Supresion(k(p.ambito()), ref, nivelEsm, totalPacientes, comparadoPacientes);
        List<Kpi> ks = s.kpis(kpis);
        List<Tabla> ts = s.tablas(tablas);
        List<String> obs = new ArrayList<>(observaciones);
        if (!p.ambito().cohortes().isEmpty()) {
            obs.add("Filtrado por cohorte: cuentan las dispensaciones de los pacientes que son miembros en el mes de cada dispensación (membresía del"
                    + " corte del cargue " + c.cargueId() + ").");
        }
        if (s.ocultoTodo()) {
            obs.add("Menos de " + s.k() + " pacientes distintos con estos filtros en una cohorte sensible: las cifras se ocultan (supresión reforzada).");
        }
        String titulo = tituloBase + " · " + vista;
        ResultadoTablero sinHuella = new ResultadoTablero(p.consulta().tablero() == null ? null : p.consulta().tablero().codigo(), titulo,
                p.nivel().codigo(), p.niveles().stream().map(Nivel::codigo).toList(), disponible, mensaje, List.copyOf(ks), List.copyOf(ts),
                List.copyOf(advertencias), List.copyOf(obs), List.copyOf(noDisponible), m, null);
        return new ResultadoTablero(sinHuella.tablero(), titulo, sinHuella.nivel(), sinHuella.niveles(), disponible, mensaje, sinHuella.kpis(),
                sinHuella.tablas(), sinHuella.advertencias(), sinHuella.observaciones(), sinHuella.noDisponible(), m, huella(sinHuella, p));
    }

    /** Avisos que dependen de la hora o de lo publicado después del corte (no entran en la huella ni en la caché). */
    private List<String> advertencias(JdbcClient j, Corte c) {
        List<String> advertencias = new ArrayList<>();
        if (c.datosHasta() != null && Duration.between(c.datosHasta().plusDays(1).atStartOfDay(co.com.agsamericas.digsa.reglas.Fechas.BOGOTA).toInstant(),
                reloj.instant()).toHours() > propiedades.rezago()) {
            advertencias.add("Datos con más de " + propiedades.rezago() + " h: el último día con dispensaciones es " + AnaliticaConsulta.fecha(c.datosHasta())
                    + ".");
        }
        tablaMaestraPosterior(j, c).ifPresent(advertencias::add);
        return advertencias;
    }

    /** Alcance legible: «Nacional», «Fuerza EJC», «ESM BAS02 · Nombre». */
    public String alcanceTexto(JdbcClient j, Alcance a) {
        return switch (a) {
            case Alcance.Todo t -> "Nacional";
            case Alcance.Fuerza f -> "Fuerza " + f.codigo();
            case Alcance.Esm e -> "ESM " + j.sql("SELECT coalesce(upper(sigla) || ' · ', '') || nombre FROM puntos WHERE id = :id").param("id", e.puntoId())
                    .query(String.class).optional().orElse(String.valueOf(e.puntoId()));
            default -> "Sin alcance";
        };
    }

    /** Código estable del alcance (para la huella: no depende del nombre del ESM en el catálogo). */
    public static String alcanceCodigo(Alcance a) {
        return switch (a) {
            case Alcance.Todo t -> "nacional";
            case Alcance.Fuerza f -> "fuerza:" + f.codigo();
            case Alcance.Esm e -> "esm:" + e.puntoId();
            default -> "ninguno";
        };
    }

    /**
     * Huella SHA-256 del contenido canónico: consulta resuelta, corte, KPI y tablas por sus CÓDIGOS y cifras (revisión A3 2026-10-23: sin
     * las columnas de texto, que salen de los catálogos actuales —nombres de ESM, regionales, ATC, CIE-10— y cambiarían la huella de un
     * corte anterior al renombrar un punto). Sin usuario, hora ni avisos.
     */
    static String huella(ResultadoTablero r, Preparada p) {
        List<String> l = new ArrayList<>();
        ConsultaAnalitica q = p.consulta();
        l.add("tablero|" + (q.tablero() == null ? "" : q.tablero().codigo()) + "|" + q.intencion() + "|" + r.nivel() + "|atc" + q.nivelAtc() + "|"
                + q.orden().codigo());
        l.add("periodo|" + p.ambito().desde() + "|" + p.ambito().hasta() + "|" + p.ambito().granularidad().codigo() + "|" + q.comparacion().codigo());
        l.add("corte|" + p.corte().cargueId() + "|" + p.corte().homVersion() + "|" + p.corte().cargues());
        l.add("alcance|" + alcanceCodigo(p.ambito().alcance()));
        p.ambito().filtros().forEach((k, v) -> l.add("filtro|" + k.parametro() + "=" + String.join(",", v)));
        if (!p.ambito().cohortes().isEmpty()) {
            // A7.1: la versión de cada cohorte del filtro (otra versión es otra consulta)
            l.add("cohortes|" + versionesCohorte(p.ambito()));
        }
        q.metricas().forEach(m -> l.add("metrica|" + m.codigo()));
        q.dimensiones().forEach(d -> l.add("dimension|" + d.codigo()));
        for (Kpi k : r.kpis()) {
            l.add("kpi|" + k.clave() + "|" + AnaliticaConsulta.canonico(k.valor()) + "|" + AnaliticaConsulta.canonico(k.comparado()));
        }
        for (Tabla t : r.tablas()) {
            l.add("tabla|" + t.id() + "|" + String.join(",", t.columnas().stream().map(Columna::clave).toList()));
            for (Fila f : t.filas()) {
                List<String> v = new ArrayList<>();
                for (int i = 0; i < f.valores().size(); i++) {
                    if (t.columnas().get(i).tipo() != Tipo.TEXTO) {
                        v.add(AnaliticaConsulta.canonico(f.valores().get(i)));
                    }
                }
                l.add("fila|" + f.clave() + "|" + String.join("|", v));
            }
        }
        return AnaliticaConsulta.huella(l);
    }

    /** Lo que la pantalla necesita para armarse: tableros, variables, granularidades, vistas del alcance y cobertura del A4. */
    public Map<String, Object> catalogo(Alcance alcance) {
        List<Nivel> niveles = niveles(alcance);
        return leer(j -> {
            Optional<Corte> c = corte(j, null);
            Set<CampoOpcional> presentes = c.isEmpty() ? Set.of() : presentes(j, c.get());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tableros", java.util.Arrays.stream(Tablero.values()).map(t -> Map.of("codigo", t.codigo(), "titulo", t.titulo(), "disponible",
                    t.disponible())).toList());
            m.put("variables", java.util.Arrays.stream(Variable.values()).map(v -> Map.of("parametro", v.parametro(), "etiqueta", v.etiqueta(),
                    "prefijo", v.prefijo())).toList());
            m.put("granularidades", java.util.Arrays.stream(AnaliticaConsulta.Granularidad.values()).map(g -> Map.of("codigo", g.codigo(), "etiqueta",
                    g.etiqueta(), "disponible", g != AnaliticaConsulta.Granularidad.HORA)).toList());
            m.put("comparaciones", java.util.Arrays.stream(AnaliticaConsulta.Comparacion.values()).map(x -> Map.of("codigo", x.codigo(), "etiqueta",
                    x.etiqueta())).toList());
            m.put("niveles", niveles.stream().map(n -> Map.of("codigo", n.codigo(), "etiqueta", n.etiqueta())).toList());
            m.put("alcance", alcanceTexto(j, alcance));
            m.put("hayCargues", c.isPresent());
            m.put("supresion", propiedades.supresion());
            List<Map<String, Object>> reportes = new ArrayList<>();
            for (AnaliticaConsulta.ReporteA4 r : AnaliticaConsulta.REPORTES_A4) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("codigo", r.codigo());
                x.put("nombre", r.nombre());
                x.put("tablero", r.tablero() == null ? null : r.tablero().codigo());
                x.put("parametros", r.parametros());
                List<CampoOpcional> faltan = AnaliticaConsulta.faltantes(r, presentes);
                x.put("disponible", r.cubierto() && faltan.isEmpty());
                x.put("nota", !r.cubierto() ? r.nota() : faltan.isEmpty() ? r.nota() : AnaliticaConsulta.noDisponible(faltan));
                reportes.add(x);
            }
            m.put("reportesA4", reportes);
            return m;
        });
    }
}
