package co.com.agsamericas.digsa.analitica.prediccion;

import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Columna;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.FiltroAplicado;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Fila;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Metadatos;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Tabla;
import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.Corte;
import co.com.agsamericas.digsa.analitica.tableros.PropiedadesTableros;
import co.com.agsamericas.digsa.analitica.tableros.ServicioConsultaAnalitica;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Comparacion;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.ConsultaAnalitica;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Dimension;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Granularidad;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Intencion;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Metrica;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Nivel;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Tipo;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Variable;
import co.com.agsamericas.digsa.reglas.AnaliticaPrediccion;
import co.com.agsamericas.digsa.reglas.AnaliticaPrediccion.MesProyectado;
import co.com.agsamericas.digsa.reglas.AnaliticaPrediccion.Metodo;
import co.com.agsamericas.digsa.reglas.AnaliticaPrediccion.Resultado;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tablero 12 «Predictivo» (analitica-cohortes-alertas: «Proyección mensual a 6 meses, también con poca historia», «Métodos en Java puro…»
 * y «Metadatos, escenarios, observado frente a proyectado y bitácora de entrenamientos»). Sin IA: estadística determinista de
 * {@link AnaliticaPrediccion}.
 *
 * <p>La serie mensual sale por el camino único de consulta ({@link ServicioConsultaAnalitica#consultar}: {@code ana_hechos_tablero}, corte =
 * cargues visibles + tabla maestra vigente, alcance del perfil en el SQL, supresión k de los pacientes), con toda la historia visible al
 * corte; así las cifras observadas son las mismas de los tableros. El corte de la proyección es el último día con dispensaciones visible
 * (su mes, si está incompleto, no entra al entrenamiento: observado parcial).
 *
 * <p>Decisiones (fuera de la letra de la spec, documentadas en la spec y en AN.30):
 * <ul>
 * <li>Consultar una serie sin entrenamiento guardado para su corte y horizonte la proyecta al vuelo y NO la guarda (la spec solo pide
 * guardar los automáticos y los de «Reentrenar»); la respuesta lo dice ({@code registrado = false}). Es reproducible igual: misma serie +
 * misma versión = misma proyección.</li>
 * <li>Con un entrenamiento guardado se muestra lo guardado (método, parámetros, proyección); si la huella de la serie actual no coincide
 * (no debería: el corte fija cargues y tabla maestra) se advierte.</li>
 * <li>Pacientes con supresión k: si algún mes de la serie tiene menos de k pacientes, la serie no se muestra ni se entrena (se proyectaría
 * desde cifras ocultas).</li>
 * <li>«Proyectado vs observado»: por cada mes completo ya observado al corte, la proyección del entrenamiento guardado más reciente que lo
 * cubría (último mes de entrenamiento anterior al mes; a igualdad, el más nuevo) entre los de cortes no posteriores al consultado.</li>
 * <li>Dimensiones: ATC (por prefijo; el nivel lo da el largo del código), principio activo y CIE-10 (por prefijo), que el servicio de
 * consulta ya filtra; cohorte, GCF y GRC no (AN.22/AN.30). El Top 20 por gasto se ofrece como lista de valores.</li>
 * </ul>
 */
@Service
public class ServicioPrediccion {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioPrediccion.class);

    static final List<String> METRICAS = List.of("gasto", "dispensaciones", "pacientes");
    static final List<String> DIMENSIONES = List.of("atc", "principio", "cie10");
    static final String AUTOMATICO = "automatico";
    static final String MANUAL = "manual";
    static final String ERROR = "error";
    /** Alcance de los entrenamientos automáticos (código de ServicioConsultaAnalitica.alcanceCodigo). */
    static final String NACIONAL = "nacional";
    static final String SUPRIMIDA = "La serie tiene meses con menos de %d pacientes distintos (supresión de celdas pequeñas): no se muestra ni se proyecta.";

    /**
     * Serie pedida: métrica, nivel con su valor (nulo en el nacional) y dimensión opcional con su valor.
     */
    public record Clave(String metrica, Nivel nivel, String nivelValor, String dimension, String dimensionValor) {

        Metrica metricaConsulta() {
            return Metrica.deCodigo(metrica).orElseThrow();
        }

        /** «Gasto · Nacional · ATC C09». */
        public String texto() {
            String m = switch (metrica) {
                case "gasto" -> "Gasto";
                case "dispensaciones" -> "Dispensaciones";
                default -> "Pacientes distintos";
            };
            String n = switch (nivel) {
                case NACIONAL -> "Nacional";
                case FUERZA -> "Fuerza " + nivelValor;
                case ESM -> "ESM " + nivelValor;
            };
            String d = dimension == null ? "" : " · " + switch (dimension) {
                case "atc" -> "ATC";
                case "principio" -> "Principio activo";
                default -> "CIE-10";
            } + " " + dimensionValor;
            return m + " · " + n + d;
        }
    }

    /** Serie observada al corte (toda la historia visible). */
    record Serie(Map<YearMonth, BigDecimal> valores, boolean suprimida, long registros) {
    }

    /** Fila de «proyectado vs observado». */
    record Comparado(YearMonth mes, BigDecimal proyectado, BigDecimal inferior, BigDecimal superior, BigDecimal observado, BigDecimal error,
                     int h, long entrenamiento, OffsetDateTime fecha) {
    }

    /** Modelo que se muestra: el entrenamiento guardado del corte o el calculado al vuelo. */
    record Modelo(Long id, boolean registrado, String origen, String usuario, OffsetDateTime creado, String estado, String mensaje, String metodo,
                  Map<String, Object> parametros, List<Map<String, Object>> evaluados, YearMonth desde, YearMonth hasta, int meses, int backtest,
                  BigDecimal mape, BigDecimal wmape, BigDecimal sigma, List<String> notas, String version, String sha256, int horizonte,
                  List<MesProyectado> proyeccion, Long duracionMs) {
    }

    /** Todo lo que se muestra (pantalla) y se descarga. */
    record Vista(Clave clave, Corte corte, String alcance, Serie serie, Resultado calculado, Modelo modelo, BigDecimal ajuste, List<MesProyectado> ajustada,
                 List<Comparado> comparacion, List<String> advertencias) {
    }

    private final JdbcClient jdbc;
    private final ServicioConsultaAnalitica consultas;
    private final RegistroBitacora bitacora;
    private final ObjectMapper json;
    private final Clock reloj;
    private final PropiedadesPrediccion propiedades;
    private final PropiedadesTableros tableros;
    private final TransactionTemplate escritura;

    ServicioPrediccion(JdbcClient jdbc, ServicioConsultaAnalitica consultas, RegistroBitacora bitacora, ObjectMapper json, Clock reloj,
                       PropiedadesPrediccion propiedades, PropiedadesTableros tableros, PlatformTransactionManager transacciones) {
        this.jdbc = jdbc;
        this.consultas = consultas;
        this.bitacora = bitacora;
        this.json = json;
        this.reloj = reloj;
        this.propiedades = propiedades;
        this.tableros = tableros;
        this.escritura = new TransactionTemplate(transacciones);
        this.escritura.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---------- Clave y alcance ----------

    /** Vistas a las que llega el alcance (la primera es la de por defecto), como los tableros. */
    static List<Nivel> niveles(Alcance a) {
        return switch (a) {
            case Alcance.Todo t -> List.of(Nivel.NACIONAL, Nivel.FUERZA, Nivel.ESM);
            case Alcance.Fuerza f -> List.of(Nivel.FUERZA, Nivel.ESM);
            case Alcance.Esm e -> List.of(Nivel.ESM);
            default -> throw new ErrorNegocio(HttpStatus.FORBIDDEN, "Su perfil no tiene un alcance de datos para la analítica farmacéutica.");
        };
    }

    /**
     * Valida y normaliza la serie pedida en el alcance del usuario (422 con {@code campo}; el valor inexistente y el que está fuera del
     * alcance dan el mismo mensaje, como los tableros).
     */
    public Clave clave(String metrica, String nivel, String valor, String dimension, String valorDimension, Long cargue, Alcance a) {
        String m = vacio(metrica) ? "gasto" : metrica.strip();
        if (!METRICAS.contains(m)) {
            throw ErrorNegocio.validacion("La métrica «" + recortar(m) + "» no es válida: use gasto, dispensaciones o pacientes.", "metrica");
        }
        List<Nivel> niveles = niveles(a);
        Nivel n = vacio(nivel) ? niveles.getFirst() : Nivel.deCodigo(nivel.strip())
                .orElseThrow(() -> ErrorNegocio.validacion("La vista «" + recortar(nivel) + "» no es válida.", "nivel"));
        if (!niveles.contains(n)) {
            throw ErrorNegocio.validacion("La vista " + n.etiqueta() + " no está en su alcance.", "nivel");
        }
        String v = vacio(valor) ? null : valor.strip().toUpperCase(java.util.Locale.ROOT);
        switch (n) {
            case NACIONAL -> v = null;
            case FUERZA -> {
                if (a instanceof Alcance.Fuerza f) {
                    if (v != null && !v.equals(f.codigo())) {
                        throw ErrorNegocio.validacion(AnaliticaConsulta.mensajeInexistente(Variable.FUERZA, recortar(v)), "valor");
                    }
                    v = f.codigo();
                }
                if (v == null) {
                    throw ErrorNegocio.validacion("Elija una Fuerza para la vista por Fuerza.", "valor");
                }
                if (!Variable.FUERZA.formaValida(v) || !(a instanceof Alcance.Fuerza) && !existe(Variable.FUERZA, v, cargue, a)) {
                    throw ErrorNegocio.validacion(AnaliticaConsulta.mensajeInexistente(Variable.FUERZA, recortar(v)), "valor");
                }
            }
            case ESM -> {
                if (v == null && a instanceof Alcance.Esm) {
                    v = propioEsm(cargue, a);
                }
                if (v == null) {
                    throw ErrorNegocio.validacion("Elija un ESM para la vista por ESM.", "valor");
                }
                // Revisión A5: también en el alcance nacional (un ESM inexistente no se entrena ni se guarda)
                if (!Variable.ESM.formaValida(v) || !existe(Variable.ESM, v, cargue, a)) {
                    throw ErrorNegocio.validacion(AnaliticaConsulta.mensajeInexistente(Variable.ESM, recortar(v)), "valor");
                }
            }
        }
        String d = vacio(dimension) ? null : dimension.strip();
        String dv = null;
        if (d != null) {
            if (!DIMENSIONES.contains(d)) {
                throw ErrorNegocio.validacion("La dimensión «" + recortar(d) + "» no es válida: use atc, principio o cie10.", "dimension");
            }
            if (vacio(valorDimension)) {
                throw ErrorNegocio.validacion("Elija el valor de la dimensión.", "valorDimension");
            }
            Variable var = variable(d);
            dv = AnaliticaConsulta.normalizarValor(var, valorDimension);
            if (!var.formaValida(dv) || AnaliticaConsulta.SIN_HOMOLOGAR.equals(dv) || AnaliticaConsulta.SIN_DATO.equals(dv)) {
                throw ErrorNegocio.validacion(AnaliticaConsulta.mensajeInexistente(var, recortar(dv)), "valorDimension");
            }
        }
        return new Clave(m, n, v, d, dv);
    }

    private static Variable variable(String dimension) {
        return switch (dimension) {
            case "atc" -> Variable.ATC;
            case "principio" -> Variable.PRINCIPIO;
            default -> Variable.CIE10;
        };
    }

    @SuppressWarnings("unchecked")
    private List<String> opciones(Variable v, String texto, Long cargue, Alcance a) {
        List<Map<String, String>> l = (List<Map<String, String>>) consultas.opciones(v, texto, cargue, a).get("opciones");
        return l.stream().map(x -> x.get("valor")).toList();
    }

    private boolean existe(Variable v, String valor, Long cargue, Alcance a) {
        return opciones(v, valor, cargue, a).contains(valor);
    }

    /** Sigla del ESM del alcance (el que dan sus cargues visibles). */
    private String propioEsm(Long cargue, Alcance a) {
        List<String> l = opciones(Variable.ESM, null, cargue, a);
        return l.isEmpty() ? null : l.getFirst();
    }

    private static Map<Variable, List<String>> filtros(Clave k) {
        Map<Variable, List<String>> f = new EnumMap<>(Variable.class);
        if (k.nivel() == Nivel.FUERZA) {
            f.put(Variable.FUERZA, List.of(k.nivelValor()));
        } else if (k.nivel() == Nivel.ESM) {
            f.put(Variable.ESM, List.of(k.nivelValor()));
        }
        if (k.dimension() != null) {
            f.put(variable(k.dimension()), List.of(k.dimensionValor()));
        }
        return f;
    }

    // ---------- Corte y serie ----------

    /**
     * Corte de la predicción. Revisión A5: su fecha ({@code datosHasta}) es el fin del periodo cubierto por los cargues visibles, NACIONAL
     * (no el de la serie filtrada): el mayor entre la última fecha con dispensación y el fin del periodo DECLARADO al confirmar el cargue
     * (un mes declarado completo cuyo último día no tuvo dispensaciones es completo). El declarado se acota a la fecha (Bogotá) del instante
     * del corte: un periodo declarado que aún no termina no completa el mes. Sin periodo declarado, un mes sin dispensaciones en su último
     * día sigue como parcial (AN.30).
     */
    public Optional<Corte> corte(Long cargue) {
        return consultas.leer(j -> consultas.corte(j, cargue).map(c -> conFechaCorte(j, c)));
    }

    private static Corte conFechaCorte(JdbcClient j, Corte c) {
        LocalDate declarado = j.sql("SELECT max(declarado_hasta) FROM ana_cargues WHERE id = ANY(:ids)").param("ids", c.cargues().toArray(Long[]::new))
                .query((rs, n) -> rs.getDate(1) == null ? null : rs.getDate(1).toLocalDate()).optional().orElse(null);
        LocalDate f = fechaCorte(c.datosHasta(), declarado, c.instante() == null ? null
                : c.instante().atZoneSameInstant(co.com.agsamericas.digsa.reglas.Fechas.BOGOTA).toLocalDate());
        return f == null || f.equals(c.datosHasta()) ? c
                : new Corte(c.cargueId(), c.aplicadoEn(), c.instante(), c.homVersion(), c.homNumero(), c.cargues(), c.datosDesde(), f);
    }

    /** Fecha de corte: el mayor entre el último día con datos y el fin declarado (acotado al día del corte). */
    static LocalDate fechaCorte(LocalDate datosHasta, LocalDate declaradoHasta, LocalDate diaCorte) {
        LocalDate d = declaradoHasta == null || diaCorte == null ? declaradoHasta : declaradoHasta.isAfter(diaCorte) ? diaCorte : declaradoHasta;
        if (datosHasta == null) {
            return null;
        }
        return d != null && d.isAfter(datosHasta) ? d : datosHasta;
    }

    /** Último mes completo a la fecha de corte (el mes del corte si el corte es su último día). */
    static YearMonth ultimoCompleto(LocalDate fechaCorte) {
        YearMonth m = YearMonth.from(fechaCorte);
        return fechaCorte.equals(m.atEndOfMonth()) ? m : m.minusMonths(1);
    }

    /** Serie mensual de la métrica con toda la historia visible al corte, por el servicio de consulta (alcance y supresión incluidos). */
    Serie serie(Clave k, Corte c, Alcance a) {
        if (c.datosDesde() == null || c.datosHasta() == null) {
            return new Serie(Map.of(), false, 0);
        }
        ConsultaAnalitica q = new ConsultaAnalitica(Intencion.METRICAS, null, List.of(k.metricaConsulta()), List.of(Dimension.PERIODO), filtros(k),
                c.datosDesde(), c.datosHasta(), Granularidad.MES, Comparacion.NINGUNA, c.cargueId(), k.nivel(), null, null);
        ResultadoTablero r = consultas.consultar(q, a);
        TreeMap<YearMonth, BigDecimal> valores = new TreeMap<>();
        boolean suprimida = false;
        if (!r.tablas().isEmpty()) {
            for (Fila f : r.tablas().getFirst().filas()) {
                if (!"dato".equals(f.tipo())) {
                    continue;
                }
                Object v = f.valores().get(1);
                if (v == null) {
                    suprimida = true;
                    continue;
                }
                valores.put(YearMonth.parse(f.clave()), v instanceof BigDecimal b ? b : new BigDecimal(v.toString()));
            }
        }
        return new Serie(java.util.Collections.unmodifiableMap(valores), suprimida, r.metadatos() == null ? 0 : r.metadatos().registros());
    }

    // ---------- Entrenamiento ----------

    /** Resumen de un entrenamiento guardado. */
    public record Entrenado(long id, String estado, String metodo, String mensaje) {
    }

    /** «Reentrenar» (Administrador, Coordinador, Analista): la serie se valida en el alcance; un error inesperado queda como «error». */
    public Entrenado reentrenar(Clave k, Long cargue, Integer horizonte, Alcance a, long usuarioId, String usuario) {
        int h = horizonte(horizonte);
        Corte c = corte(cargue).orElseThrow(() -> ErrorNegocio.validacion("Aún no hay cargues aplicados: no hay serie que entrenar."));
        long t0 = System.nanoTime();
        Serie s = serie(k, c, a);
        if (s.suprimida()) {
            throw ErrorNegocio.validacion(SUPRIMIDA.formatted(tableros.supresion()));
        }
        Resultado r;
        try {
            r = AnaliticaPrediccion.proyectar(s.valores(), c.datosHasta(), h);
        } catch (RuntimeException e) {
            guardar(k, c, ServicioConsultaAnalitica.alcanceCodigo(a), MANUAL, usuarioId, usuario, h, null, mensajeError(e), ms(t0));
            throw e;
        }
        return guardar(k, c, ServicioConsultaAnalitica.alcanceCodigo(a), MANUAL, usuarioId, usuario, h, r, null, ms(t0));
    }

    /**
     * Entrenamiento automático del corte (después de aplicar un cargue): gasto, dispensaciones y pacientes, nacional y por cada Fuerza
     * visible, solo si el cargue completa un mes (su último mes completo es posterior al del último automático nacional). Devuelve cuántos
     * entrenamientos guardó.
     */
    public int entrenarAlCorte(long cargue) {
        Optional<Corte> oc = corte(cargue);
        if (oc.isEmpty() || oc.get().datosHasta() == null) {
            return 0;
        }
        Corte c = oc.get();
        YearMonth completo = ultimoCompleto(c.datosHasta());
        LocalDate previo = jdbc.sql("""
                SELECT max(fecha_corte) FROM ana_pred_entrenamientos
                WHERE origen = 'automatico' AND nivel = 'nacional' AND dimension IS NULL AND fecha_corte IS NOT NULL""").query(LocalDate.class).optional()
                .orElse(null);
        if (previo != null && !completo.isAfter(ultimoCompleto(previo))) {
            LOG.info("[analitica] Predicción: el cargue {} no completa un mes nuevo (último completo {}): sin entrenamiento automático", cargue, completo);
            return 0;
        }
        Alcance todo = new Alcance.Todo();
        List<Clave> claves = new ArrayList<>();
        for (String m : METRICAS) {
            claves.add(new Clave(m, Nivel.NACIONAL, null, null, null));
        }
        for (String f : opciones(Variable.FUERZA, null, cargue, todo)) {
            for (String m : METRICAS) {
                claves.add(new Clave(m, Nivel.FUERZA, f, null, null));
            }
        }
        int h = propiedades.horizontePorDefecto();
        int n = 0;
        for (Clave k : claves) {
            long t0 = System.nanoTime();
            try {
                Serie s = serie(k, c, todo);
                if (s.suprimida()) {
                    LOG.info("[analitica] Predicción: {} con celdas suprimidas: sin entrenamiento automático", k.texto());
                    continue;
                }
                guardar(k, c, NACIONAL, AUTOMATICO, null, null, h, AnaliticaPrediccion.proyectar(s.valores(), c.datosHasta(), h), null, ms(t0));
                n++;
            } catch (RuntimeException e) {
                LOG.error("[analitica] Falló el entrenamiento automático de {} al corte {}", k.texto(), cargue, e);
                try {
                    guardar(k, c, NACIONAL, AUTOMATICO, null, null, h, null, mensajeError(e), ms(t0));
                    n++;
                } catch (RuntimeException e2) {
                    LOG.error("[analitica] No se pudo registrar el error del entrenamiento de {}", k.texto(), e2);
                }
            }
        }
        return n;
    }

    private static String mensajeError(RuntimeException e) {
        String m = e instanceof ErrorNegocio ? e.getMessage() : e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        return m.length() > 1900 ? m.substring(0, 1900) : m;
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    /** Guarda el entrenamiento (fila y proyección en una transacción) y su evento de bitácora. Con {@code r} nulo, estado «error». */
    private Entrenado guardar(Clave k, Corte c, String alcance, String origen, Long usuarioId, String usuario, int horizonte, Resultado r, String error,
                              long duracion) {
        String estado = r == null ? ERROR : r.estado();
        long id = escritura.execute(s -> {
            long nuevo = jdbc.sql("""
                    INSERT INTO ana_pred_entrenamientos (metrica, nivel, nivel_valor, dimension, dimension_valor, alcance, corte_cargue_id, fecha_corte, origen,
                        usuario_id, horizonte, metodo, parametros, evaluados, historia_desde, historia_hasta, historia_meses, backtest_meses, mape, wmape,
                        sigma, notas, parcial_mes, parcial_valor, version_algoritmo, sha256, estado, mensaje, duracion_ms, creado)
                    VALUES (:metrica, :nivel, :nivelValor, :dimension, :dimensionValor, :alcance, :corte, :fechaCorte, :origen, :usuario, :horizonte, :metodo,
                        CAST(:parametros AS jsonb), CAST(:evaluados AS jsonb), :desde, :hasta, :meses, :backtest, :mape, :wmape, :sigma,
                        CAST(:notas AS text[]), :parcialMes, :parcialValor, :version, :sha, :estado, :mensaje, :duracion, :creado)
                    RETURNING id""")
                    .param("metrica", k.metrica()).param("nivel", k.nivel().codigo()).param("nivelValor", k.nivelValor()).param("dimension", k.dimension())
                    .param("dimensionValor", k.dimensionValor()).param("alcance", alcance).param("corte", c.cargueId()).param("fechaCorte", c.datosHasta()).param("origen", origen)
                    .param("usuario", usuarioId).param("horizonte", horizonte).param("metodo", r == null || r.metodo() == null ? null : r.metodo().codigo())
                    .param("parametros", aJson(r == null ? Map.of() : r.parametros())).param("evaluados", aJson(r == null ? List.of() : evaluados(r)))
                    .param("desde", r == null || r.desde() == null ? null : r.desde().atDay(1)).param("hasta", r == null || r.hasta() == null ? null
                            : r.hasta().atDay(1)).param("meses", r == null ? 0 : r.mesesHistoria()).param("backtest", r == null ? 0 : r.mesesBacktest())
                    .param("mape", r == null ? null : acotar(r.mape(), MAX_ERROR)).param("wmape", r == null ? null : acotar(r.wmape(), MAX_ERROR))
                    .param("sigma", r == null ? null : acotar(r.sigma(), MAX_VALOR))
                    .param("notas", (r == null ? List.<String>of() : r.notas()).toArray(String[]::new))
                    .param("parcialMes", r == null || r.parcial() == null ? null : r.parcial().mes().atDay(1))
                    .param("parcialValor", r == null || r.parcial() == null ? null : acotar(r.parcial().valor(), MAX_VALOR))
                    .param("version", AnaliticaPrediccion.VERSION).param("sha", r == null ? null : r.sha256()).param("estado", estado)
                    .param("mensaje", r == null ? error : r.mensaje()).param("duracion", (int) Math.min(Integer.MAX_VALUE, duracion))
                    .param("creado", OffsetDateTime.now(reloj)).query(Long.class).single();
            if (r != null) {
                for (MesProyectado p : r.proyeccion()) {
                    jdbc.sql("""
                            INSERT INTO ana_pred_proyecciones (entrenamiento_id, mes, h, valor, inferior, superior)
                            VALUES (:e, :mes, :h, :valor, :inferior, :superior)""").param("e", nuevo).param("mes", p.mes().atDay(1)).param("h", p.h())
                            .param("valor", acotar(p.valor(), MAX_VALOR)).param("inferior", acotar(p.inferior(), MAX_VALOR))
                            .param("superior", acotar(p.superior(), MAX_VALOR)).update();
                }
            }
            return nuevo;
        });
        String metodo = r == null || r.metodo() == null ? null : r.metodo().codigo();
        String detalle = switch (estado) {
            case AnaliticaPrediccion.OK -> "método " + r.metodo().nombre() + " (WMAPE " + (r.wmape() == null ? "—" : AnaliticaConsulta.decimal(r.wmape())) + " %)";
            case AnaliticaPrediccion.HISTORIA_INSUFICIENTE -> r.mensaje();
            default -> "error";
        };
        // El entrenamiento ya quedó guardado: si la bitácora falla, no se responde 500 por eso (queda en el registro de errores)
        try {
            bitacora.registrar("analitica_prediccion_entrenamiento", "Entrenamiento " + id + " de la predicción (" + origen + ") · " + k.texto()
                    + " · alcance " + alcance + " · corte " + c.cargueId() + " · " + detalle, usuarioId, usuario);
        } catch (RuntimeException e) {
            LOG.error("[analitica] No se pudo registrar en la bitácora el entrenamiento {} de la predicción", id, e);
        }
        return new Entrenado(id, estado, metodo, r == null ? error : r.mensaje());
    }

    /** Tope de MAPE/WMAPE guardados (%): con observados cercanos a 0 el error relativo se dispara (V64, numeric(14, 2)). */
    static final BigDecimal MAX_ERROR = new BigDecimal("999999999999.99");
    /** Tope de proyecciones, IC y σ guardados (V64, numeric(22, 2)). */
    static final BigDecimal MAX_VALOR = new BigDecimal("99999999999999999999.99");

    /** Acota un valor guardado a su tipo en la base (y nunca negativo); nulo sigue nulo. */
    static BigDecimal acotar(BigDecimal v, BigDecimal max) {
        if (v == null) {
            return null;
        }
        return v.signum() < 0 ? BigDecimal.ZERO.setScale(2) : v.compareTo(max) > 0 ? max : v;
    }

    private static List<Map<String, Object>> evaluados(Resultado r) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (AnaliticaPrediccion.Evaluado e : r.evaluados()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("metodo", e.metodo().codigo());
            m.put("parametros", e.parametros());
            m.put("wmape", e.wmape());
            m.put("mape", e.mape());
            l.add(m);
        }
        return l;
    }

    private String aJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- Consulta ----------

    int horizonte(Integer h) {
        int r = h == null ? propiedades.horizontePorDefecto() : h;
        if (r < AnaliticaPrediccion.HORIZONTE_DEFECTO || r > AnaliticaPrediccion.HORIZONTE_MAXIMO) {
            throw ErrorNegocio.validacion("El horizonte debe estar entre " + AnaliticaPrediccion.HORIZONTE_DEFECTO + " y " + AnaliticaPrediccion.HORIZONTE_MAXIMO
                    + " meses.", "horizonte");
        }
        return r;
    }

    /** Ajuste del escenario (%), de −100 a 1000; nulo si no se pidió. */
    static BigDecimal ajuste(String texto) {
        if (vacio(texto)) {
            return null;
        }
        BigDecimal p;
        try {
            p = new BigDecimal(texto.strip().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw ErrorNegocio.validacion("El ajuste debe ser un número entre -100 y 1000 (%).", "ajuste");
        }
        if (p.compareTo(BigDecimal.valueOf(-100)) < 0 || p.compareTo(BigDecimal.valueOf(1000)) > 0 || p.scale() > 2) {
            throw ErrorNegocio.validacion("El ajuste debe ser un número entre -100 y 1000 (%), con máximo dos decimales.", "ajuste");
        }
        return p;
    }

    /** Arma la vista de una serie al corte: observado, modelo (guardado o al vuelo), escenarios y proyectado vs observado. */
    Optional<Vista> vista(Clave k, Long cargue, Integer horizonte, BigDecimal ajuste, Alcance a) {
        int h = horizonte(horizonte);
        Optional<Corte> oc = corte(cargue);
        if (oc.isEmpty()) {
            return Optional.empty();
        }
        Corte c = oc.get();
        String alcance = consultas.leer(j -> consultas.alcanceTexto(j, a));
        Serie s = serie(k, c, a);
        List<String> advertencias = new ArrayList<>();
        if (s.suprimida() || c.datosHasta() == null) {
            if (s.suprimida()) {
                advertencias.add(SUPRIMIDA.formatted(tableros.supresion()));
            }
            return Optional.of(new Vista(k, c, alcance, new Serie(Map.of(), s.suprimida(), s.registros()), null, null, ajuste, null, List.of(),
                    advertencias));
        }
        Resultado calc = AnaliticaPrediccion.proyectar(s.valores(), c.datosHasta(), h);
        // Lo guardado solo si su huella es la de la serie calculada ahora en el alcance de quien consulta; si no, al vuelo
        Modelo m = guardado(k, c.cargueId(), h, calc.sha256(), a).orElseGet(() -> alVuelo(calc, h));
        List<MesProyectado> ajustada = ajuste == null || m.proyeccion().isEmpty() ? null : AnaliticaPrediccion.ajustar(m.proyeccion(), ajuste);
        List<Comparado> comp = comparacion(k, c, s, a);
        return Optional.of(new Vista(k, c, alcance, s, calc, m, ajuste, ajustada, comp, advertencias));
    }

    private static Modelo alVuelo(Resultado r, int h) {
        Map<String, Object> par = new LinkedHashMap<>(r.parametros());
        return new Modelo(null, false, null, null, null, r.estado(), r.mensaje(), r.metodo() == null ? null : r.metodo().codigo(), par, evaluados(r),
                r.desde(), r.hasta(), r.mesesHistoria(), r.mesesBacktest(), r.mape(), r.wmape(), r.sigma(), r.notas(), r.version(), r.sha256(), h,
                r.proyeccion(), null);
    }

    private static final String SERIE = " " + """
            e.metrica = :metrica AND e.nivel = :nivel AND coalesce(e.nivel_valor, '') = :nivelValor AND coalesce(e.dimension, '') = :dimension
            AND coalesce(e.dimension_valor, '') = :dimensionValor AND e.alcance = ANY(CAST(:alcances AS text[]))""";

    /**
     * Alcances de los entrenamientos que puede ver quien consulta (revisión A5): solo los entrenados en su mismo alcance (un ESM compartido
     * entre Fuerzas da series distintas por alcance). Para la vista de su propia Fuerza, un perfil de Fuerza ve también los nacionales: la
     * serie es idéntica por construcción (el alcance y el filtro de la vista son la misma condición x.fuerza = F). El ESM ve solo los suyos.
     */
    static List<String> alcancesVisibles(Clave k, Alcance a) {
        String propio = ServicioConsultaAnalitica.alcanceCodigo(a);
        if (a instanceof Alcance.Fuerza f && k.nivel() == Nivel.FUERZA && f.codigo().equals(k.nivelValor())) {
            return List.of(propio, NACIONAL);
        }
        return List.of(propio);
    }

    private static JdbcClient.StatementSpec deSerie(JdbcClient.StatementSpec s, Clave k, Alcance a) {
        return s.param("alcances", alcancesVisibles(k, a).toArray(String[]::new)).param("metrica", k.metrica()).param("nivel", k.nivel().codigo())
                .param("nivelValor", k.nivelValor() == null ? "" : k.nivelValor())
                .param("dimension", k.dimension() == null ? "" : k.dimension()).param("dimensionValor", k.dimensionValor() == null ? "" : k.dimensionValor());
    }

    /** Último entrenamiento guardado (ok o historia insuficiente) de la serie para el corte y el horizonte. */
    private Optional<Modelo> guardado(Clave k, long corte, int horizonte, String sha, Alcance a) {
        Optional<Modelo> m = deSerie(jdbc.sql("""
                SELECT e.*, u.usuario AS usuario_texto FROM ana_pred_entrenamientos e LEFT JOIN usuarios u ON u.id = e.usuario_id
                WHERE """ + SERIE + " AND e.corte_cargue_id = :corte AND e.horizonte = :h AND e.sha256 = :sha AND e.estado <> 'error'"
                + " ORDER BY e.id DESC LIMIT 1"), k, a)
                .param("sha", sha).param("corte", corte).param("h", horizonte).query((rs, n) -> modelo(rs)).optional();
        return m.map(x -> {
            List<MesProyectado> p = jdbc.sql("SELECT mes, h, valor, inferior, superior FROM ana_pred_proyecciones WHERE entrenamiento_id = :e ORDER BY h")
                    .param("e", x.id()).query((rs, n) -> new MesProyectado(YearMonth.from(rs.getDate("mes").toLocalDate()), rs.getInt("h"),
                            rs.getBigDecimal("valor"), rs.getBigDecimal("inferior"), rs.getBigDecimal("superior"))).list();
            return new Modelo(x.id(), true, x.origen(), x.usuario(), x.creado(), x.estado(), x.mensaje(), x.metodo(), x.parametros(), x.evaluados(),
                    x.desde(), x.hasta(), x.meses(), x.backtest(), x.mape(), x.wmape(), x.sigma(), x.notas(), x.version(), x.sha256(), x.horizonte(), p,
                    x.duracionMs());
        });
    }

    private Modelo modelo(ResultSet rs) throws SQLException {
        Array notas = rs.getArray("notas");
        return new Modelo(rs.getLong("id"), true, rs.getString("origen"), rs.getString("usuario_texto"), odt(rs.getTimestamp("creado")), rs.getString("estado"),
                rs.getString("mensaje"), rs.getString("metodo"), leer(rs.getString("parametros"), new TypeReference<Map<String, Object>>() {
                }), leer(rs.getString("evaluados"), new TypeReference<List<Map<String, Object>>>() {
                }), mes(rs.getDate("historia_desde")), mes(rs.getDate("historia_hasta")), rs.getInt("historia_meses"), rs.getInt("backtest_meses"),
                rs.getBigDecimal("mape"), rs.getBigDecimal("wmape"), rs.getBigDecimal("sigma"), notas == null ? List.of() : List.of((String[]) notas.getArray()),
                rs.getString("version_algoritmo"), rs.getString("sha256"), rs.getInt("horizonte"), List.of(), rs.getLong("duracion_ms"));
    }

    private <T> T leer(String texto, TypeReference<T> tipo) {
        try {
            return json.readValue(texto, tipo);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static YearMonth mes(java.sql.Date d) {
        return d == null ? null : YearMonth.from(d.toLocalDate());
    }

    private static OffsetDateTime odt(Timestamp t) {
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }

    /**
     * «Proyectado vs observado»: por cada mes completo ya observado al corte, lo que proyectó el entrenamiento guardado más reciente que lo
     * cubría (último mes de entrenamiento anterior al mes; a igualdad, el más nuevo), de un corte no posterior al consultado; con el
     * observado del corte y su error real.
     */
    private List<Comparado> comparacion(Clave k, Corte c, Serie s, Alcance a) {
        if (s.valores().isEmpty()) {
            return List.of();
        }
        YearMonth primero = s.valores().keySet().iterator().next();
        YearMonth ultimo = ultimoCompleto(c.datosHasta());
        if (ultimo.isBefore(primero)) {
            return List.of();
        }
        return deSerie(jdbc.sql("""
                SELECT DISTINCT ON (p.mes) p.mes, p.valor, p.inferior, p.superior, p.h, e.id, e.creado
                FROM ana_pred_proyecciones p
                JOIN ana_pred_entrenamientos e ON e.id = p.entrenamiento_id
                JOIN ana_cargues ca ON ca.id = e.corte_cargue_id
                WHERE """ + SERIE + """
                 AND e.estado = 'ok' AND p.mes > e.historia_hasta AND p.mes BETWEEN :primero AND :ultimo AND ca.aplicado_en <= :aplicado
                 AND e.creado <= :instante
                ORDER BY p.mes DESC, e.historia_hasta DESC, e.id DESC"""), k, a)
                .param("instante", c.instante()).param("primero", primero.atDay(1)).param("ultimo", ultimo.atDay(1)).param("aplicado", c.aplicadoEn())
                .query((rs, n) -> {
                    YearMonth m = YearMonth.from(rs.getDate("mes").toLocalDate());
                    BigDecimal obs = s.valores().getOrDefault(m, BigDecimal.ZERO).setScale(2, java.math.RoundingMode.HALF_UP);
                    BigDecimal proy = rs.getBigDecimal("valor");
                    return new Comparado(m, proy, rs.getBigDecimal("inferior"), rs.getBigDecimal("superior"), obs,
                            AnaliticaPrediccion.errorReal(proy, obs).orElse(null), rs.getInt("h"), rs.getLong("id"), odt(rs.getTimestamp("creado")));
                }).list();
    }

    /** Respuesta de la pantalla: cifras sin formato (la pantalla aplica el formato 2.4) y textos ya armados. */
    public Map<String, Object> consultar(Clave k, Long cargue, Integer horizonte, String ajusteTexto, Alcance a) {
        BigDecimal ajuste = ajuste(ajusteTexto);
        Optional<Vista> ov = vista(k, cargue, horizonte, ajuste, a);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("serie", claveMapa(k));
        r.put("serieTexto", k.texto());
        r.put("niveles", niveles(a).stream().map(Nivel::codigo).toList());
        if (ov.isEmpty()) {
            r.put("hayCargues", false);
            r.put("mensaje", "Aún no hay cargues aplicados");
            return r;
        }
        Vista v = ov.get();
        r.put("hayCargues", true);
        r.put("corte", corteMapa(v.corte()));
        r.put("alcance", v.alcance());
        r.put("suprimida", v.serie().suprimida());
        r.put("advertencias", v.advertencias());
        r.put("observado", observado(v));
        Modelo m = v.modelo();
        r.put("modelo", m == null ? null : modeloMapa(m));
        List<Map<String, Object>> proy = new ArrayList<>();
        if (m != null) {
            for (int i = 0; i < m.proyeccion().size(); i++) {
                MesProyectado p = m.proyeccion().get(i);
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("mes", p.mes().toString());
                x.put("h", p.h());
                x.put("valor", p.valor());
                x.put("inferior", p.inferior());
                x.put("superior", p.superior());
                x.put("ajustado", v.ajustada() == null ? null : v.ajustada().get(i).valor());
                proy.add(x);
            }
        }
        r.put("proyeccion", proy);
        r.put("ajuste", v.ajuste());
        r.put("escenarios", escenarios(v));
        List<Map<String, Object>> comp = new ArrayList<>();
        for (Comparado x : v.comparacion()) {
            Map<String, Object> y = new LinkedHashMap<>();
            y.put("mes", x.mes().toString());
            y.put("proyectado", x.proyectado());
            y.put("inferior", x.inferior());
            y.put("superior", x.superior());
            y.put("observado", x.observado());
            y.put("error", x.error());
            y.put("dentroIc", x.observado().compareTo(x.inferior()) >= 0 && x.observado().compareTo(x.superior()) <= 0);
            y.put("h", x.h());
            y.put("entrenamiento", x.entrenamiento());
            y.put("fechaEntrenamiento", x.fecha());
            comp.add(y);
        }
        r.put("comparacion", comp);
        r.put("supresion", consultas.textoSupresion());
        return r;
    }

    private static Map<String, Object> claveMapa(Clave k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metrica", k.metrica());
        m.put("nivel", k.nivel().codigo());
        m.put("valor", k.nivelValor());
        m.put("dimension", k.dimension());
        m.put("valorDimension", k.dimensionValor());
        return m;
    }

    private static Map<String, Object> corteMapa(Corte c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cargueId", c.cargueId());
        m.put("aplicadoEn", c.aplicadoEn());
        m.put("texto", "Corte " + AnaliticaConsulta.fechaHora(c.aplicadoEn()) + " (cargue " + c.cargueId() + ")");
        m.put("fechaCorte", c.datosHasta());
        m.put("tablaMaestra", c.homNumero());
        return m;
    }

    /** Observado: de su primer mes al último completo (huecos en 0), y el mes del corte incompleto como parcial. */
    private static List<Map<String, Object>> observado(Vista v) {
        List<Map<String, Object>> l = new ArrayList<>();
        Map<YearMonth, BigDecimal> s = v.serie().valores();
        if (s.isEmpty() || v.corte().datosHasta() == null) {
            return l;
        }
        YearMonth ultimo = ultimoCompleto(v.corte().datosHasta());
        YearMonth mesCorte = YearMonth.from(v.corte().datosHasta());
        for (YearMonth m = s.keySet().iterator().next(); !m.isAfter(mesCorte); m = m.plusMonths(1)) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("mes", m.toString());
            x.put("valor", s.getOrDefault(m, BigDecimal.ZERO));
            x.put("parcial", m.isAfter(ultimo));
            l.add(x);
        }
        return l;
    }

    private static Map<String, Object> modeloMapa(Modelo m) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("entrenamiento", m.id());
        r.put("registrado", m.registrado());
        r.put("origen", m.origen());
        r.put("usuario", m.usuario());
        r.put("fechaEntrenamiento", m.creado());
        r.put("estado", m.estado());
        r.put("mensaje", m.mensaje());
        r.put("metodo", m.metodo());
        r.put("metodoNombre", m.metodo() == null ? null : Metodo.deCodigo(m.metodo()).map(Metodo::nombre).orElse(m.metodo()));
        r.put("parametros", m.parametros());
        List<Map<String, Object>> ev = new ArrayList<>();
        for (Map<String, Object> e : m.evaluados()) {
            Map<String, Object> x = new LinkedHashMap<>(e);
            x.put("nombre", Metodo.deCodigo(String.valueOf(e.get("metodo"))).map(Metodo::nombre).orElse(String.valueOf(e.get("metodo"))));
            x.put("elegido", String.valueOf(e.get("metodo")).equals(m.metodo()));
            ev.add(x);
        }
        r.put("evaluados", ev);
        r.put("historiaDesde", m.desde() == null ? null : m.desde().toString());
        r.put("historiaHasta", m.hasta() == null ? null : m.hasta().toString());
        r.put("historiaMeses", m.meses());
        r.put("backtestMeses", m.backtest());
        r.put("mape", m.mape());
        r.put("wmape", m.wmape());
        r.put("sigma", m.sigma());
        r.put("notas", m.notas());
        r.put("version", m.version());
        r.put("sha256", m.sha256());
        r.put("horizonte", m.horizonte());
        r.put("duracionMs", m.duracionMs());
        return r;
    }

    /** Totales del horizonte por escenario (solo métricas que se suman: gasto y dispensaciones; los pacientes no se suman entre meses). */
    private static Map<String, Object> escenarios(Vista v) {
        Map<String, Object> e = new LinkedHashMap<>();
        if (v.modelo() == null || v.modelo().proyeccion().isEmpty() || "pacientes".equals(v.clave().metrica())) {
            return null;
        }
        e.put("base", suma(v.modelo().proyeccion(), MesProyectado::valor));
        e.put("inferior", suma(v.modelo().proyeccion(), MesProyectado::inferior));
        e.put("superior", suma(v.modelo().proyeccion(), MesProyectado::superior));
        e.put("ajustado", v.ajustada() == null ? null : suma(v.ajustada(), MesProyectado::valor));
        return e;
    }

    private static BigDecimal suma(List<MesProyectado> l, java.util.function.Function<MesProyectado, BigDecimal> f) {
        return l.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ---------- Bitácora de entrenamientos ----------

    /** Entrenamientos de la serie (la bitácora del tablero), del más reciente al más antiguo, paginados. */
    public Map<String, Object> entrenamientos(Clave k, int pagina, Alcance a) {
        int tam = propiedades.pagina();
        long total = deSerie(jdbc.sql("SELECT count(*) FROM ana_pred_entrenamientos e WHERE " + SERIE), k, a).query(Long.class).single();
        List<Map<String, Object>> l = deSerie(jdbc.sql("""
                SELECT e.id, e.creado, e.origen, u.usuario, e.corte_cargue_id, e.fecha_corte, e.horizonte, e.metodo, e.estado, e.mensaje, e.historia_desde,
                       e.historia_hasta, e.historia_meses, e.backtest_meses, e.mape, e.wmape, e.version_algoritmo, e.sha256, e.duracion_ms
                FROM ana_pred_entrenamientos e LEFT JOIN usuarios u ON u.id = e.usuario_id
                WHERE """ + SERIE + " ORDER BY e.id DESC LIMIT :tam OFFSET :desde"), k, a).param("tam", tam).param("desde", (long) (pagina - 1) * tam)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("fecha", odt(rs.getTimestamp("creado")));
                    m.put("origen", rs.getString("origen"));
                    m.put("usuario", rs.getString("usuario"));
                    m.put("corte", rs.getLong("corte_cargue_id"));
                    m.put("fechaCorte", rs.getDate("fecha_corte") == null ? null : rs.getDate("fecha_corte").toLocalDate());
                    m.put("horizonte", rs.getInt("horizonte"));
                    String metodo = rs.getString("metodo");
                    m.put("metodo", metodo);
                    m.put("metodoNombre", metodo == null ? null : Metodo.deCodigo(metodo).map(Metodo::nombre).orElse(metodo));
                    m.put("estado", rs.getString("estado"));
                    m.put("mensaje", rs.getString("mensaje"));
                    m.put("historiaDesde", mes(rs.getDate("historia_desde")) == null ? null : mes(rs.getDate("historia_desde")).toString());
                    m.put("historiaHasta", mes(rs.getDate("historia_hasta")) == null ? null : mes(rs.getDate("historia_hasta")).toString());
                    m.put("historiaMeses", rs.getInt("historia_meses"));
                    m.put("backtestMeses", rs.getInt("backtest_meses"));
                    m.put("mape", rs.getBigDecimal("mape"));
                    m.put("wmape", rs.getBigDecimal("wmape"));
                    m.put("version", rs.getString("version_algoritmo"));
                    m.put("sha256", rs.getString("sha256"));
                    m.put("duracionMs", rs.getLong("duracion_ms"));
                    return m;
                }).list();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("entrenamientos", l);
        r.put("total", total);
        r.put("pagina", pagina);
        r.put("porPagina", tam);
        return r;
    }

    // ---------- Top 20 por gasto de una dimensión ----------

    /**
     * Top {@value AnaliticaConsulta#TOP} por gasto de la dimensión (toda la historia visible al corte, en el nivel y alcance de la serie):
     * los valores que la pantalla ofrece para proyectar por ATC (nivel elegido), principio activo o CIE-10.
     */
    public List<Map<String, String>> top(Clave base, String dimension, Integer nivelAtc, Long cargue, Alcance a) {
        if (vacio(dimension) || !DIMENSIONES.contains(dimension.strip())) {
            throw ErrorNegocio.validacion("La dimensión debe ser atc, principio o cie10.", "dimension");
        }
        int nAtc = nivelAtc == null ? 1 : nivelAtc;
        if (nAtc < 1 || nAtc > 5) {
            throw ErrorNegocio.validacion("El nivel ATC va de 1 a 5.", "nivelAtc");
        }
        Dimension d = switch (dimension.strip()) {
            case "atc" -> Dimension.atc(nAtc);
            case "principio" -> Dimension.PRINCIPIO_ACTIVO;
            default -> Dimension.CIE10;
        };
        Optional<Corte> oc = corte(cargue);
        if (oc.isEmpty() || oc.get().datosDesde() == null) {
            return List.of();
        }
        Corte c = oc.get();
        Clave k = new Clave("gasto", base.nivel(), base.nivelValor(), null, null);
        ConsultaAnalitica q = new ConsultaAnalitica(Intencion.METRICAS, null, List.of(Metrica.GASTO), List.of(d), filtros(k), c.datosDesde(), c.datosHasta(),
                Granularidad.MES, Comparacion.NINGUNA, c.cargueId(), k.nivel(), null, null);
        ResultadoTablero r = consultas.consultar(q, a);
        List<Map<String, String>> l = new ArrayList<>();
        if (!r.tablas().isEmpty()) {
            for (Fila f : r.tablas().getFirst().filas()) {
                if (l.size() >= AnaliticaConsulta.TOP) {
                    break;
                }
                if (AnaliticaConsulta.SIN_HOMOLOGAR.equals(f.clave()) || AnaliticaConsulta.SIN_DATO.equals(f.clave()) || f.clave() == null) {
                    continue;
                }
                l.add(Map.of("valor", f.clave(), "etiqueta", f.textos().getFirst()));
            }
        }
        return l;
    }

    // ---------- Descarga ----------

    /** La proyección con sus metadatos como resultado exportable (Excel, CSV o PDF con los metadatos de 14.8). */
    public ResultadoTablero paraDescarga(Clave k, Long cargue, Integer horizonte, String ajusteTexto, Alcance a) {
        Vista v = vista(k, cargue, horizonte, ajuste(ajusteTexto), a)
                .orElseThrow(() -> ErrorNegocio.validacion("Aún no hay cargues aplicados: no hay nada que descargar."));
        if (v.modelo() == null) {
            throw ErrorNegocio.validacion(v.advertencias().isEmpty() ? "No hay serie para descargar." : v.advertencias().getFirst());
        }
        Modelo m = v.modelo();
        Tipo tipo = "gasto".equals(k.metrica()) ? Tipo.MONEDA : Tipo.DECIMAL;
        boolean conAjuste = v.ajustada() != null;
        List<Columna> cp = new ArrayList<>(List.of(new Columna("mes", "Mes", Tipo.TEXTO), new Columna("tipo", "Tipo", Tipo.TEXTO),
                new Columna("valor", "Valor (observado o escenario base)", tipo), new Columna("inferior", "Escenario inferior (IC 95 %)", tipo),
                new Columna("superior", "Escenario superior (IC 95 %)", tipo)));
        if (conAjuste) {
            cp.add(new Columna("ajustado", "Escenario ajustado (" + AnaliticaConsulta.decimal(v.ajuste()) + " %)", tipo));
        }
        List<Fila> fp = new ArrayList<>();
        List<String> lineas = new ArrayList<>();
        lineas.add("prediccion|" + k.metrica() + "|" + k.nivel().codigo() + "|" + k.nivelValor() + "|" + k.dimension() + "|" + k.dimensionValor());
        lineas.add("corte|" + v.corte().cargueId() + "|" + v.corte().homVersion() + "|" + v.corte().cargues());
        lineas.add("modelo|" + m.metodo() + "|" + m.version() + "|" + m.sha256() + "|" + m.horizonte() + "|" + v.ajuste());
        for (Map<String, Object> o : observado(v)) {
            List<Object> val = new ArrayList<>(java.util.Arrays.asList(o.get("mes"), Boolean.TRUE.equals(o.get("parcial")) ? "Observado parcial" : "Observado",
                    o.get("valor"), null, null));
            if (conAjuste) {
                val.add(null);
            }
            fp.add(ResultadoTablero.fila((String) o.get("mes"), null, cp, val, "dato"));
            lineas.add("obs|" + o.get("mes") + "|" + AnaliticaConsulta.canonico(o.get("valor")));
        }
        for (int i = 0; i < m.proyeccion().size(); i++) {
            MesProyectado p = m.proyeccion().get(i);
            List<Object> val = new ArrayList<>(java.util.Arrays.asList(p.mes().toString(), "Proyectado (h = " + p.h() + ")", p.valor(), p.inferior(),
                    p.superior()));
            if (conAjuste) {
                val.add(v.ajustada().get(i).valor());
            }
            fp.add(ResultadoTablero.fila("p" + p.mes(), null, cp, val, "dato"));
            lineas.add("proy|" + p.mes() + "|" + AnaliticaConsulta.canonico(p.valor()) + "|" + AnaliticaConsulta.canonico(p.inferior()) + "|"
                    + AnaliticaConsulta.canonico(p.superior()));
        }
        List<Tabla> tablas = new ArrayList<>();
        tablas.add(new Tabla("proyeccion", "Observado y proyección mensual con IC 95 %", "serie", "valor", null, List.copyOf(cp), fp,
                "Línea continua: observado; discontinua: proyectado; banda: IC 95 %. El mes del corte incompleto no entra al entrenamiento."));
        List<Columna> cm = List.of(new Columna("concepto", "Concepto", Tipo.TEXTO), new Columna("valor", "Valor", Tipo.TEXTO));
        List<Fila> fm = new ArrayList<>();
        for (String[] x : metadatosModelo(m)) {
            fm.add(ResultadoTablero.fila("m" + fm.size(), null, cm, java.util.Arrays.asList(x[0], x[1]), "dato"));
        }
        tablas.add(new Tabla("modelo", "Metadatos del modelo", "ninguno", null, null, cm, fm, null));
        if (!v.comparacion().isEmpty()) {
            List<Columna> cc = List.of(new Columna("mes", "Mes", Tipo.TEXTO), new Columna("proyectado", "Proyectado", tipo),
                    new Columna("observado", "Observado", tipo), new Columna("error", "Error real", Tipo.PORCENTAJE), new Columna("h", "Meses adelante", Tipo.ENTERO),
                    new Columna("entrenamiento", "Entrenamiento", Tipo.ENTERO), new Columna("fecha", "Fecha del entrenamiento", Tipo.TEXTO));
            List<Fila> fc = new ArrayList<>();
            for (Comparado x : v.comparacion()) {
                fc.add(ResultadoTablero.fila("c" + x.mes(), null, cc, java.util.Arrays.asList(x.mes().toString(), x.proyectado(), x.observado(), x.error(),
                        (long) x.h(), x.entrenamiento(), AnaliticaConsulta.fechaHora(x.fecha())), "dato"));
                lineas.add("comp|" + x.mes() + "|" + AnaliticaConsulta.canonico(x.proyectado()) + "|" + AnaliticaConsulta.canonico(x.observado()) + "|"
                        + x.entrenamiento());
            }
            tablas.add(new Tabla("comparacion", "Proyectado vs observado", "ninguno", null, null, cc, fc,
                    "Lo que proyectó el entrenamiento anterior que cubría cada mes, frente a lo observado al corte."));
        }
        Corte c = v.corte();
        List<FiltroAplicado> filtros = new ArrayList<>();
        filtros.add(new FiltroAplicado("metrica", "Serie", List.of(k.metrica()), List.of(k.texto())));
        filtros.add(new FiltroAplicado("horizonte", "Horizonte", List.of(String.valueOf(m.horizonte())), List.of(m.horizonte() + " meses")));
        String fuente = "Tabla maestra homologada " + (c.homNumero() == null ? "(sin versión publicada)" : "v" + c.homNumero()) + " · cargues "
                + String.join(", ", c.cargues().stream().map(String::valueOf).toList()) + " · insumo de dispensación del OPL · reglas/AnaliticaPrediccion "
                + m.version();
        String establecimiento = k.nivel() == Nivel.ESM ? "ESM " + k.nivelValor() : "Todos los del alcance";
        LocalDate desde = m.desde() == null ? c.datosDesde() : m.desde().atDay(1);
        Metadatos md = new Metadatos(c.cargueId(), c.aplicadoEn(), "Corte " + AnaliticaConsulta.fechaHora(c.aplicadoEn()) + " (cargue " + c.cargueId() + ")",
                c.homNumero(), c.cargues(), fuente, desde, c.datosHasta(), AnaliticaConsulta.fecha(desde) + " – " + AnaliticaConsulta.fecha(c.datosHasta())
                        + " (observado) y " + m.horizonte() + " meses proyectados", "Mes", "Sin comparación", null, v.alcance(), establecimiento,
                List.copyOf(filtros), v.serie().registros(), c.datosHasta(), consultas.textoSupresion());
        List<String> obs = new ArrayList<>(m.notas());
        if (m.mensaje() != null) {
            obs.add(m.mensaje());
        }
        if (!m.registrado()) {
            obs.add("Proyección calculada al consultar (no registrada como entrenamiento).");
        }
        return new ResultadoTablero("predictivo", "Predictivo · " + k.texto(), k.nivel().codigo(), niveles(a).stream().map(Nivel::codigo).toList(), true,
                null, List.of(), List.copyOf(tablas), List.copyOf(v.advertencias()), List.copyOf(obs), List.of(), md, AnaliticaConsulta.huella(lineas));
    }

    /** Filas «concepto / valor» de los metadatos del modelo (pantalla y descarga). */
    static List<String[]> metadatosModelo(Modelo m) {
        List<String[]> l = new ArrayList<>();
        l.add(new String[] {"Estado", m.mensaje() != null ? m.mensaje() : m.estado()});
        l.add(new String[] {"Método", m.metodo() == null ? "—" : Metodo.deCodigo(m.metodo()).map(Metodo::nombre).orElse(m.metodo())});
        l.add(new String[] {"Parámetros", parametros(m.parametros())});
        for (Map<String, Object> e : m.evaluados()) {
            String nombre = Metodo.deCodigo(String.valueOf(e.get("metodo"))).map(Metodo::nombre).orElse(String.valueOf(e.get("metodo")));
            l.add(new String[] {"Evaluado: " + nombre, "WMAPE " + pct(e.get("wmape")) + " · MAPE " + pct(e.get("mape")) + " · " + parametros(
                    e.get("parametros") instanceof Map<?, ?> p ? p : Map.of())});
        }
        l.add(new String[] {"Periodo histórico", m.desde() == null ? "—" : m.desde() + " a " + m.hasta() + " (" + m.meses() + " meses)"});
        l.add(new String[] {"Backtesting", m.backtest() + (m.backtest() == 1 ? " mes" : " meses")});
        l.add(new String[] {"MAPE", pct(m.mape())});
        l.add(new String[] {"WMAPE", pct(m.wmape())});
        l.add(new String[] {"Desviación de los residuos (σ)", m.sigma() == null ? "—" : AnaliticaConsulta.decimal(m.sigma())});
        l.add(new String[] {"Fecha de entrenamiento", m.creado() == null ? "Calculada al consultar (no registrada)" : AnaliticaConsulta.fechaHora(m.creado())});
        l.add(new String[] {"Origen", m.origen() == null ? "Al vuelo" : AUTOMATICO.equals(m.origen()) ? "Automático (cargue)"
                : "Reentrenar" + (m.usuario() == null ? "" : " · " + m.usuario())});
        l.add(new String[] {"Versión del algoritmo", m.version()});
        l.add(new String[] {"Huella SHA-256 de la serie", m.sha256() == null ? "—" : m.sha256()});
        for (String n : m.notas()) {
            l.add(new String[] {"Nota", n});
        }
        return l;
    }

    private static String parametros(Map<?, ?> p) {
        if (p == null || p.isEmpty()) {
            return "—";
        }
        List<String> l = new ArrayList<>();
        p.forEach((k, v) -> l.add(k + " = " + (v instanceof BigDecimal b ? AnaliticaConsulta.decimal(b) : v instanceof Number x
                ? AnaliticaConsulta.decimal(new BigDecimal(x.toString())) : String.valueOf(v))));
        return String.join(", ", l);
    }

    private static String pct(Object v) {
        if (v == null) {
            return "—";
        }
        return AnaliticaConsulta.decimal(v instanceof BigDecimal b ? b : new BigDecimal(v.toString())) + " %";
    }

    // ---------- Utilidades ----------

    private static boolean vacio(String s) {
        return s == null || s.isBlank();
    }

    private static String recortar(String s) {
        String x = s == null ? "" : s.strip();
        return x.length() > 60 ? x.substring(0, 60) : x;
    }
}
