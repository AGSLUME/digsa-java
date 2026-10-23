package co.com.agsamericas.digsa.analitica.tableros;

import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Columna;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Fila;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Kpi;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Tabla;
import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.Ambito;
import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.Armado;
import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.Conjunto;
import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.Extra;
import co.com.agsamericas.digsa.analitica.tableros.SemanticaSql.Grupo;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.CampoOpcional;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.ConsultaAnalitica;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Dimension;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Metrica;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Nivel;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Tipo;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Arma el contenido de cada tablero (A5 4.7.1, tableros 1 a 9) con la capa semántica: un recorrido de los hechos por tablero con todos sus
 * conjuntos de agrupación (GROUPING SETS: pacientes distintos exactos por celda), las sumas del periodo de comparación (desde los agregados
 * si cubren meses completos), la distribución (percentile_cont sobre los hechos), Top 20 + «Resto» + «Sin homologar» y la fila total para
 * que cada tabla cuadre con los KPI. Una instancia por consulta, dentro de su transacción de solo lectura.
 */
final class ConstructorTableros {

    /** Columnas posibles de una tabla por dimensión. */
    enum Col {
        GASTO("gasto", "Gasto", Tipo.MONEDA), COMPARADO("comparado", "Gasto periodo de comparación", Tipo.MONEDA),
        VARIACION("variacion", "Variación", Tipo.MONEDA), VARIACION_PCT("variacionPct", "Variación %", Tipo.PORCENTAJE),
        PARTICIPACION("participacion", "Participación", Tipo.PORCENTAJE), ACUMULADA("acumulada", "Participación acumulada", Tipo.PORCENTAJE),
        UNIDADES("unidades", "Unidades", Tipo.UNIDADES), DISPENSACIONES("dispensaciones", "Dispensaciones", Tipo.ENTERO),
        PACIENTES("pacientes", "Pacientes distintos", Tipo.ENTERO), PROMEDIO_DISPENSACION("promedioDispensacion", "Promedio por dispensación",
                Tipo.MONEDA), PROMEDIO_PACIENTE("promedioPaciente", "Promedio por paciente", Tipo.MONEDA),
        VALOR_UNITARIO("valorUnitario", "Valor unitario", Tipo.MONEDA), NOMBRE_COMERCIAL("nombreComercial", "Nombre comercial", Tipo.TEXTO),
        DCI("dci", "Genérico (DCI)", Tipo.TEXTO), REGISTRO("registroSanitario", "Registro sanitario", Tipo.TEXTO),
        PRESENTACION("presentacion", "Presentación", Tipo.TEXTO), CONCENTRACION("concentracion", "Concentración", Tipo.TEXTO),
        FORMA("forma", "Forma farmacéutica", Tipo.TEXTO);

        final String clave;
        final String etiqueta;
        final Tipo tipo;

        Col(String clave, String etiqueta, Tipo tipo) {
            this.clave = clave;
            this.etiqueta = etiqueta;
            this.tipo = tipo;
        }

        Columna columna() {
            return new Columna(clave, etiqueta, tipo);
        }

        Extra extra() {
            return switch (this) {
                case NOMBRE_COMERCIAL -> Extra.NOMBRE_COMERCIAL;
                case DCI -> Extra.DCI;
                case REGISTRO -> Extra.REGISTRO_SANITARIO;
                case PRESENTACION -> Extra.PRESENTACION;
                case CONCENTRACION -> Extra.CONCENTRACION;
                case FORMA -> Extra.FORMA;
                default -> null;
            };
        }
    }

    /** Columnas máximas del mapa de calor (periodos). */
    static final int MAX_PERIODOS_CALOR = 60;
    static final String NOTA_PACIENTES = "Los pacientes no se suman entre filas: un paciente puede estar en varias filas.";

    private final JdbcClient jdbc;
    private final Ambito ambito;
    private final Ambito comparacion;
    private final ConsultaAnalitica consulta;
    private final Nivel nivel;
    private final PropiedadesTableros propiedades;
    private final Set<CampoOpcional> presentes;
    private final Etiquetas etiquetas;

    final List<Kpi> kpis = new ArrayList<>();
    final List<Tabla> tablas = new ArrayList<>();
    final List<String> observaciones = new ArrayList<>();
    final List<String> noDisponible = new ArrayList<>();
    long registros;

    ConstructorTableros(JdbcClient jdbc, Ambito ambito, Ambito comparacion, ConsultaAnalitica consulta, Nivel nivel, PropiedadesTableros propiedades,
                        Set<CampoOpcional> presentes) {
        this.jdbc = jdbc;
        this.ambito = ambito;
        this.comparacion = comparacion;
        this.consulta = consulta;
        this.nivel = nivel;
        this.propiedades = propiedades;
        this.presentes = presentes;
        this.etiquetas = new Etiquetas(jdbc);
    }

    // ---------- Ejecución ----------

    /**
     * Ejecuta cada conjunto con su propia sentencia (pacientes en dos niveles por hash, en paralelo); el índice del conjunto en la lista
     * queda en cada grupo. Con {@code formulas}, el total (conjunto sin dimensiones) lleva además las fórmulas distintas.
     */
    private List<Grupo> correr(Ambito a, List<Conjunto> cs, boolean pacientes, boolean formulas) {
        List<Grupo> r = new ArrayList<>();
        for (int i = 0; i < cs.size(); i++) {
            Armado ar = SemanticaSql.agrupar(a, cs.get(i), i, pacientes);
            List<Grupo> g = jdbc.sql(ar.sql()).params(ar.parametros()).query((rs, n) -> ar.leer(rs)).list();
            if (formulas && cs.get(i).dimensiones().isEmpty() && !g.isEmpty()) {
                Map<String, Object> p = new LinkedHashMap<>();
                String sql = SemanticaSql.formulas(a, p);
                g = List.of(g.getFirst().conFormulas(jdbc.sql(sql).params(p).query(Long.class).single()));
            }
            r.addAll(g);
        }
        return r;
    }

    /** Cuartiles del gasto por paciente que dejó {@link #enBase} con distribución (en pesos; nulo si no se calcularon ahí). */
    private BigDecimal[] porPaciente;

    /**
     * Total y conjuntos de una dimensión en una sola sentencia sobre una base (claves, paciente) ({@link SemanticaSql#agruparEnBase}).
     * Con {@code distribucion}, deja además los cuartiles del gasto por paciente.
     */
    private List<Grupo> enBase(List<Dimension> dims, boolean distribucion) {
        Armado ar = SemanticaSql.agruparEnBase(ambito, dims, distribucion);
        List<Grupo> r = new ArrayList<>();
        jdbc.sql(ar.sql()).params(ar.parametros()).query((rs, n) -> {
            Grupo g = SemanticaSql.leerEnBase(rs, dims);
            if (distribucion && g.conjunto() == 0) {
                porPaciente = new BigDecimal[5];
                for (int i = 0; i < 5; i++) {
                    Object v = rs.getObject("d_" + i);
                    if (v != null) {
                        BigDecimal c = new BigDecimal(v.toString());
                        porPaciente[i] = i == 0 || i == 4 ? c.movePointLeft(2) : c.movePointLeft(2).setScale(2, java.math.RoundingMode.HALF_UP);
                    }
                }
            }
            r.add(g);
            return null;
        }).list();
        return r;
    }

    private static List<Grupo> del(List<Grupo> g, int conjunto) {
        return g.stream().filter(x -> x.conjunto() == conjunto).toList();
    }

    private static Grupo totalDe(List<Grupo> g, int conjunto) {
        return g.stream().filter(x -> x.conjunto() == conjunto).findFirst()
                .orElse(new Grupo(conjunto, Map.of(), Map.of(), BigDecimal.ZERO, BigDecimal.ZERO, 0, 0L, 0L));
    }

    /** Sumas por clave del periodo de comparación (solo gasto). */
    private Map<String, BigDecimal> comparados(Dimension d) {
        if (comparacion == null) {
            return null;
        }
        Map<String, BigDecimal> m = new HashMap<>();
        for (Grupo g : correr(comparacion, List.of(Conjunto.de(d)), false, false)) {
            m.put(g.clave(d), g.gasto());
        }
        return m;
    }

    private BigDecimal gastoComparado() {
        if (comparacion == null) {
            return null;
        }
        return totalDe(correr(comparacion, List.of(Conjunto.total()), false, false), 0).gasto();
    }

    // ---------- Tableros ----------

    void construir() {
        switch (consulta.tablero()) {
            case GENERAL -> general();
            case GASTO -> gasto();
            case ATC -> atc();
            case PRINCIPIO -> principio();
            case MEDICAMENTO -> medicamento();
            case DIAGNOSTICO -> diagnostico();
            case TERRITORIAL -> territorial();
            case ALTO_COSTO -> altoCosto();
            case POLIFORMULACION -> poliformulacion();
            default -> throw new IllegalStateException("Tablero sin construir: " + consulta.tablero());
        }
        for (AnaliticaConsulta.ReporteA4 r : AnaliticaConsulta.REPORTES_A4) {
            if (r.tablero() == consulta.tablero()) {
                List<CampoOpcional> faltan = AnaliticaConsulta.faltantes(r, presentes);
                if (!faltan.isEmpty()) {
                    noDisponible.add(r.codigo() + " " + r.nombre() + ": " + AnaliticaConsulta.noDisponible(faltan));
                }
            }
        }
    }

    /** Dimensión del desglose según la vista: nacional ⇒ por Fuerza; Fuerza ⇒ por ESM; ESM ⇒ por modalidad. */
    private Dimension desglose() {
        return switch (nivel) {
            case NACIONAL -> Dimension.FUERZA;
            case FUERZA -> Dimension.ESM;
            case ESM -> Dimension.MODALIDAD;
        };
    }

    private String tituloDesglose() {
        return switch (nivel) {
            case NACIONAL -> "Gasto por Fuerza";
            case FUERZA -> "Gasto por ESM";
            case ESM -> "Gasto por modalidad del ESM";
        };
    }

    /** 1 · General de dispensación. */
    private void general() {
        Dimension des = desglose();
        // Total, serie y desglose en una sola sentencia sobre una base (periodo, desglose, paciente), con los cuartiles por paciente
        List<Grupo> g = enBase(List.of(Dimension.PERIODO, des), true);
        Grupo t = totalDe(g, 0);
        BigDecimal comp = gastoComparado();
        kpisBase(t, comp, false);
        tablas.add(serie("serie", "Evolución por periodo", del(g, 1), true));
        tablas.add(porDimension("desglose", tituloDesglose(), des, del(g, 2), t, List.of(Col.GASTO, Col.PARTICIPACION, Col.DISPENSACIONES,
                Col.UNIDADES, Col.PACIENTES), false, null, "barras", "gasto", NOTA_PACIENTES));
        tablas.add(distribucion());
    }

    /** 2 · Gasto farmacéutico. */
    private void gasto() {
        Dimension des = desglose();
        boolean formulas = presentes.contains(CampoOpcional.NUMERO_FORMULA);
        boolean tipos = presentes.contains(CampoOpcional.TIPO_FORMULA);
        List<Conjunto> cs = new ArrayList<>(List.of(Conjunto.total(), Conjunto.de(Dimension.MODALIDAD), Conjunto.de(Dimension.PERIODO),
                Conjunto.de(des)));
        if (tipos) {
            cs.add(Conjunto.de(Dimension.TIPO_FORMULA));
        }
        List<Grupo> g = enBase(cs.subList(1, cs.size()).stream().map(c -> c.dimensiones().getFirst()).toList(), true);
        if (formulas) {
            Map<String, Object> pf = new LinkedHashMap<>();
            String sf = SemanticaSql.formulas(ambito, pf);
            Long nf = jdbc.sql(sf).params(pf).query(Long.class).single();
            g = g.stream().map(x -> x.conjunto() == 0 ? x.conFormulas(nf) : x).toList();
        }
        Grupo t = totalDe(g, 0);
        BigDecimal comp = gastoComparado();
        kpisBase(t, comp, true);
        if (formulas) {
            BigDecimal pf = AnaliticaConsulta.promedio(t.gasto(), t.formulas() == null ? 0 : t.formulas());
            kpis.add(kpi("promedioFormula", "Gasto promedio por fórmula", Tipo.MONEDA, pf, null, "Gasto ÷ fórmulas distintas", Map.of()));
        } else {
            kpis.add(kpi("promedioFormula", "Gasto promedio por fórmula", Tipo.MONEDA, null, null,
                    AnaliticaConsulta.noDisponible(List.of(CampoOpcional.NUMERO_FORMULA)), null));
        }
        List<Col> cols = new ArrayList<>(List.of(Col.GASTO));
        if (comparacion != null) {
            cols.addAll(List.of(Col.COMPARADO, Col.VARIACION, Col.VARIACION_PCT));
        }
        cols.addAll(List.of(Col.PARTICIPACION, Col.DISPENSACIONES, Col.PROMEDIO_DISPENSACION, Col.PACIENTES, Col.PROMEDIO_PACIENTE));
        tablas.add(porDimension("modalidad", "Gasto por modalidad de dispensación o suministro", Dimension.MODALIDAD, del(g, 1), t, cols, false,
                comparados(Dimension.MODALIDAD), "barras", "gasto", NOTA_PACIENTES));
        tablas.add(serie("serie", "Gasto por periodo", del(g, 2), true));
        tablas.add(porDimension("desglose", tituloDesglose(), des, del(g, 3), t, cols, false, comparados(des), "barras", "gasto", NOTA_PACIENTES));
        if (tipos) {
            tablas.add(porDimension("tipoFormula", "Gasto por tipo de fórmula", Dimension.TIPO_FORMULA, del(g, 4), t, List.of(Col.GASTO,
                    Col.PARTICIPACION, Col.DISPENSACIONES, Col.PACIENTES), false, null, "barras", "gasto", NOTA_PACIENTES));
        }
        tablas.add(distribucion());
    }

    /** 3 · Clasificación ATC (nivel 1 a 5 elegido). */
    private void atc() {
        Dimension d = Dimension.atc(consulta.nivelAtc());
        List<Grupo> g = correr(ambito, List.of(Conjunto.total(), Conjunto.de(d)), true, false);
        Grupo t = totalDe(g, 0);
        kpisBase(t, gastoComparado(), false);
        List<Col> cols = new ArrayList<>(List.of(Col.GASTO, Col.PARTICIPACION, Col.ACUMULADA));
        if (comparacion != null) {
            cols.addAll(List.of(Col.COMPARADO, Col.VARIACION_PCT));
        }
        cols.addAll(List.of(Col.UNIDADES, Col.DISPENSACIONES, Col.PACIENTES));
        tablas.add(porDimension("atc", "Top 20 por grupo ATC nivel " + consulta.nivelAtc() + " (" + consulta.orden().etiqueta().toLowerCase() + ")", d,
                del(g, 1), t, cols, true, comparados(d), "barras", consulta.orden().codigo(), NOTA_PACIENTES));
    }

    /** 4 · Principio activo. */
    private void principio() {
        Conjunto pa = new Conjunto(List.of(Dimension.PRINCIPIO_ACTIVO), List.of(Extra.CONCENTRACION, Extra.FORMA, Extra.PRESENTACION));
        List<Grupo> g = correr(ambito, List.of(Conjunto.total(), pa, Conjunto.de(Dimension.PERIODO)), true, false);
        Grupo t = totalDe(g, 0);
        kpisBase(t, gastoComparado(), false);
        tablas.add(porDimension("principio", "Top 20 por principio activo (" + consulta.orden().etiqueta().toLowerCase() + ")",
                Dimension.PRINCIPIO_ACTIVO, del(g, 1), t, List.of(Col.GASTO, Col.PARTICIPACION, Col.ACUMULADA, Col.CONCENTRACION, Col.FORMA,
                        Col.PRESENTACION, Col.UNIDADES, Col.DISPENSACIONES, Col.PACIENTES), true, null, "barras", consulta.orden().codigo(),
                NOTA_PACIENTES));
        tablas.add(serie("serie", "Tendencia temporal del gasto", del(g, 2), true));
    }

    /** 5 · Medicamento. */
    private void medicamento() {
        Conjunto m = new Conjunto(List.of(Dimension.MEDICAMENTO), List.of(Extra.NOMBRE_COMERCIAL, Extra.DCI, Extra.REGISTRO_SANITARIO,
                Extra.PRESENTACION));
        List<Grupo> g = correr(ambito, List.of(Conjunto.total(), m), true, false);
        Grupo t = totalDe(g, 0);
        kpisBase(t, gastoComparado(), false);
        tablas.add(porDimension("medicamento", "Top 20 por medicamento (" + consulta.orden().etiqueta().toLowerCase() + ")", Dimension.MEDICAMENTO,
                del(g, 1), t, List.of(Col.NOMBRE_COMERCIAL, Col.DCI, Col.REGISTRO, Col.PRESENTACION, Col.UNIDADES, Col.VALOR_UNITARIO, Col.GASTO,
                        Col.PARTICIPACION, Col.ACUMULADA, Col.DISPENSACIONES, Col.PACIENTES), true, null, "barras", consulta.orden().codigo(),
                NOTA_PACIENTES));
    }

    /** 6 · Diagnóstico CIE-10 (y principios activos asociados, A4 11.1.11). */
    private void diagnostico() {
        List<Grupo> g = correr(ambito, List.of(Conjunto.total(), Conjunto.de(Dimension.CIE10),
                Conjunto.de(Dimension.CIE10, Dimension.PRINCIPIO_ACTIVO)), true, false);
        Grupo t = totalDe(g, 0);
        kpisBase(t, gastoComparado(), false);
        tablas.add(porDimension("cie10", "Top 20 por diagnóstico CIE-10 (" + consulta.orden().etiqueta().toLowerCase() + ")", Dimension.CIE10,
                del(g, 1), t, List.of(Col.GASTO, Col.PARTICIPACION, Col.ACUMULADA, Col.DISPENSACIONES, Col.UNIDADES, Col.PACIENTES), true, null,
                "barras", consulta.orden().codigo(), NOTA_PACIENTES));
        tablas.add(cruce("cie10Principio", "Principios activos asociados a cada diagnóstico (Top 20 combinaciones)", Dimension.CIE10,
                Dimension.PRINCIPIO_ACTIVO, del(g, 2), t));
    }

    /** 7 · Territorial y organizacional, con el mapa de calor ESM × periodo. */
    private void territorial() {
        List<Dimension> dims = switch (nivel) {
            case NACIONAL -> List.of(Dimension.FUERZA, Dimension.DISAN, Dimension.REGIONAL, Dimension.ESM, Dimension.MODALIDAD,
                    Dimension.DEPARTAMENTO, Dimension.MUNICIPIO);
            case FUERZA -> List.of(Dimension.DISAN, Dimension.REGIONAL, Dimension.ESM, Dimension.MODALIDAD, Dimension.DEPARTAMENTO,
                    Dimension.MUNICIPIO);
            case ESM -> List.of(Dimension.MODALIDAD);
        };
        // Una sola sentencia: base (claves territoriales, paciente) y de ella cada dimensión (el ESM fija las demás: la base es pequeña)
        List<Grupo> g = enBase(dims, false);
        Grupo t = totalDe(g, 0);
        kpisBase(t, gastoComparado(), false);
        for (int i = 0; i < dims.size(); i++) {
            Dimension d = dims.get(i);
            tablas.add(porDimension(d.codigo(), "Por " + d.etiqueta().toLowerCase().replace("esm", "ESM").replace("disan", "DISAN"), d,
                    del(g, i + 1), t, List.of(Col.GASTO, Col.PARTICIPACION, Col.DISPENSACIONES, Col.UNIDADES, Col.PACIENTES), false, null, "barras",
                    "gasto", NOTA_PACIENTES));
        }
        // Mapa de calor: solo sumas. Máximo MAX_PERIODOS_CALOR columnas: con más (día o semana sobre periodos largos) se agrupa por mes y se
        // explica en las observaciones (miles de columnas no caben en pantalla, Excel ni PDF)
        Ambito ac = ambito;
        if (AnaliticaConsulta.periodos(ambito.desde(), ambito.hasta(), ambito.granularidad()).size() > MAX_PERIODOS_CALOR
                && !ambito.granularidad().mensualOMayor()) {
            ac = new Ambito(ambito.corte(), ambito.alcance(), ambito.filtros(), ambito.desde(), ambito.hasta(), AnaliticaConsulta.Granularidad.MES);
            observaciones.add("El mapa de calor se muestra por mes: con la granularidad elegida tendría más de " + MAX_PERIODOS_CALOR + " columnas.");
        }
        if (AnaliticaConsulta.periodos(ac.desde(), ac.hasta(), ac.granularidad()).size() > MAX_PERIODOS_CALOR) {
            ac = new Ambito(ambito.corte(), ambito.alcance(), ambito.filtros(), ambito.desde(), ambito.hasta(), AnaliticaConsulta.Granularidad.ANIO);
            observaciones.add("El mapa de calor se muestra por año: por mes tendría más de " + MAX_PERIODOS_CALOR + " columnas.");
        }
        List<Grupo> calor = correr(ac, List.of(Conjunto.de(Dimension.ESM, Dimension.PERIODO)), false, false);
        tablas.add(mapaCalor(calor, ac));
    }

    /** 8 · Alto costo: mayor impacto presupuestal y cohorte de alto costo (p99 del gasto por paciente en 12 meses móviles). */
    private void altoCosto() {
        Conjunto m = new Conjunto(List.of(Dimension.MEDICAMENTO), List.of(Extra.NOMBRE_COMERCIAL, Extra.DCI));
        boolean esp = presentes.contains(CampoOpcional.ESPECIALIDAD);
        List<Conjunto> cs = new ArrayList<>(List.of(Conjunto.total(), m, Conjunto.de(Dimension.PRINCIPIO_ACTIVO), Conjunto.de(Dimension.PERIODO)));
        if (esp) {
            cs.add(Conjunto.de(Dimension.ESPECIALIDAD));
        }
        List<Grupo> g = correr(ambito, cs, true, false);
        Grupo t = totalDe(g, 0);
        BigDecimal comp = gastoComparado();
        kpis.add(kpi("gasto", "Gasto total", Tipo.MONEDA, t.gasto(), comp, null, Map.of()));
        cohorteAltoCosto(t);
        registros = t.dispensaciones();
        tablas.add(porDimension("medicamento", "Top 20 medicamentos de mayor impacto presupuestal", Dimension.MEDICAMENTO, del(g, 1), t,
                List.of(Col.NOMBRE_COMERCIAL, Col.DCI, Col.GASTO, Col.PARTICIPACION, Col.ACUMULADA, Col.DISPENSACIONES, Col.PACIENTES), true, null,
                "barras", "gasto", NOTA_PACIENTES));
        tablas.add(porDimension("principio", "Top 20 principios activos de mayor gasto", Dimension.PRINCIPIO_ACTIVO, del(g, 2), t,
                List.of(Col.GASTO, Col.PARTICIPACION, Col.ACUMULADA, Col.DISPENSACIONES, Col.PACIENTES), true, null, "barras", "gasto",
                NOTA_PACIENTES));
        tablas.add(serie("serie", "Variación del gasto por periodo", del(g, 3), true));
        if (esp) {
            tablas.add(porDimension("especialidad", "Gasto por especialidad", Dimension.ESPECIALIDAD, del(g, 4), t, List.of(Col.GASTO,
                    Col.PARTICIPACION, Col.DISPENSACIONES, Col.PACIENTES), false, null, "barras", "gasto", NOTA_PACIENTES));
        }
    }

    private void cohorteAltoCosto(Grupo total) {
        LocalDate hasta = ambito.hasta();
        LocalDate desde12 = hasta.minusMonths(12).plusDays(1);
        Map<String, Object> p = new LinkedHashMap<>();
        // Umbral nacional (todos los cargues visibles, sin filtros ni alcance: la definición de la cohorte es una sola); pertenencia en el
        // alcance del usuario por su gasto de 12 meses; gasto de la cohorte en el periodo y con los filtros elegidos
        Ambito ventana = new Ambito(ambito.corte(), new co.com.agsamericas.digsa.reglas.Alcance.Todo(), Map.of(), desde12, hasta,
                ambito.granularidad());
        String desdeVentana = SemanticaSql.desde(ventana, Set.of(), List.of(), SemanticaSql.TABLERO, p);
        Map<String, Object> p2 = new LinkedHashMap<>();
        Ambito miembrosAmbito = new Ambito(ambito.corte(), ambito.alcance(), Map.of(), desde12, hasta, ambito.granularidad());
        String desdeMiembros = SemanticaSql.desde(miembrosAmbito, Set.of(), List.of(), SemanticaSql.TABLERO, p2);
        Map<String, Object> p3 = new LinkedHashMap<>();
        String desdePeriodo = SemanticaSql.desde(ambito, Set.of(), List.of(), SemanticaSql.TABLERO, p3);
        String cte = SemanticaSql.cte(Set.of(), ambito.filtros(), List.of());
        Map<String, Object> todos = new LinkedHashMap<>();
        // Todo por hash sobre el paciente entero (sin count(DISTINCT) ordenado)
        String sql = (cte.isEmpty() ? "WITH " : cte + ", ")
                + "ventana AS MATERIALIZED (SELECT x.paciente_id, sum(x.valor_centavos) AS g" + renombrar(p, "v_", desdeVentana, todos)
                + " GROUP BY x.paciente_id), umbral AS (SELECT percentile_cont(CAST(:pct AS double precision)) WITHIN GROUP (ORDER BY g) AS u "
                + "FROM ventana), miembros AS MATERIALIZED (SELECT x.paciente_id" + renombrar(p2, "m_", desdeMiembros, todos)
                + " GROUP BY x.paciente_id HAVING sum(x.valor_centavos) >= (SELECT u FROM umbral)) "
                + "SELECT (SELECT round(CAST(u AS numeric) / 100, 2) FROM umbral) AS umbral, (SELECT count(*) FROM miembros) AS miembros, "
                + "coalesce(sum(s.g), 0) AS gasto, count(*) AS activos FROM (SELECT x.paciente_id, sum(x.valor_centavos) AS g"
                + renombrar(p3, "p_", desdePeriodo, todos) + " AND x.paciente_id IN (SELECT paciente_id FROM miembros) GROUP BY x.paciente_id) s";
        todos.put("pct", propiedades.percentil() / 100.0);
        todos.put("hv", ambito.corte().homVersion());
        Object[] r = jdbc.sql(sql).params(todos).query((rs, n) -> new Object[] {rs.getBigDecimal(1), rs.getLong(2), rs.getBigDecimal(3),
                rs.getLong(4)}).single();
        BigDecimal umbral = (BigDecimal) r[0];
        long miembros = (Long) r[1];
        BigDecimal gastoCohorte = ((BigDecimal) r[2]).movePointLeft(2); // centavos a pesos
        String criterio = "≥ p" + propiedades.percentil() + " del gasto por paciente entre el " + AnaliticaConsulta.fecha(desde12) + " y el "
                + AnaliticaConsulta.fecha(hasta);
        kpis.add(kpi("umbralAltoCosto", "Umbral de alto costo (p" + propiedades.percentil() + ", 12 meses)", Tipo.MONEDA, umbral, null, criterio,
                null));
        kpis.add(kpi("pacientesAltoCosto", "Pacientes de la cohorte de alto costo", Tipo.ENTERO, BigDecimal.valueOf(miembros), null,
                "Conteo agregado · " + criterio, null));
        kpis.add(kpi("gastoAltoCosto", "Gasto de la cohorte en el periodo", Tipo.MONEDA, gastoCohorte, null, (Long) r[3]
                + " pacientes de la cohorte con dispensaciones en el periodo", null));
        kpis.add(kpi("participacionAltoCosto", "Participación de la cohorte en el gasto", Tipo.PORCENTAJE,
                AnaliticaConsulta.participacion(gastoCohorte, total.gasto()), null, null, null));
    }

    /** Copia los parámetros con un prefijo (cada subconsulta tiene los suyos) y devuelve el texto con los nombres cambiados. */
    private static String renombrar(Map<String, Object> p, String prefijo, String texto, Map<String, Object> destino) {
        String t = texto;
        // Los nombres más largos primero: «desde» no debe tocar a «desdeX»
        List<String> nombres = new ArrayList<>(p.keySet());
        nombres.sort(Comparator.comparingInt(String::length).reversed());
        for (String n : nombres) {
            t = t.replaceAll(":" + java.util.regex.Pattern.quote(n) + "\\b", ":" + prefijo + n);
            destino.put(prefijo + n, p.get(n));
        }
        return t;
    }

    /** 9 · Poliformulación: principios activos distintos por paciente y mes, rangos y poliformulados (≥ umbral). */
    private void poliformulacion() {
        Grupo t = totalDe(correr(ambito, List.of(Conjunto.total()), true, false), 0);
        registros = t.dispensaciones();
        Map<String, Object> p = new LinkedHashMap<>();
        Set<Dimension> dims = Set.of(Dimension.PRINCIPIO_ACTIVO, Dimension.CIE10_GRUPO, Dimension.ATC3);
        String from = SemanticaSql.desde(ambito, dims, List.of(), SemanticaSql.TABLERO, p);
        StringBuilder rango = new StringBuilder("CASE");
        for (int[] r : AnaliticaConsulta.RANGOS_POLIFORMULACION) {
            rango.append(" WHEN n BETWEEN ").append(r[0]).append(" AND ").append(Math.min(r[1], 1_000_000)).append(" THEN '")
                    .append(AnaliticaConsulta.etiquetaRango(r)).append("'");
        }
        rango.append(" END");
        String cte = SemanticaSql.cte(dims, ambito.filtros(), List.of());
        // Paciente entero y agrupaciones por hash (DISTINCT en subconsultas, no count(DISTINCT texto) ordenado por la intercalación)
        String sql = cte + (cte.isEmpty() ? "WITH " : ", ") + """
                base AS MATERIALIZED (SELECT DISTINCT x.paciente_id AS pa, date_trunc('month', x.fecha)::date AS mes, x.fuerza, x.esm_codigo,
                       %s AS cie, %s AS atc3,
                       CASE WHEN tm.estado = 'homologado' AND tm.fin_principio_activo IS NOT NULL THEN tm.fin_principio_activo
                            ELSE 'ORIGEN-' || x.medicamento_id END COLLATE "C" AS principio %s),
                pm AS MATERIALIZED (SELECT pa, mes, count(*) AS n FROM (SELECT DISTINCT pa, mes, principio FROM base) s GROUP BY pa, mes),
                poli AS MATERIALIZED (SELECT pa, mes FROM pm WHERE n >= :umbral),
                bp AS MATERIALIZED (SELECT b.* FROM base b JOIN poli USING (pa, mes))
                SELECT 'rango' AS tipo, r AS clave, sum(c)::numeric AS n, count(*) AS pacientes FROM (SELECT %s AS r, pa, count(*) AS c FROM pm GROUP BY 1, 2) s GROUP BY r
                UNION ALL SELECT 'mes', to_char(mes, 'YYYY-MM'), count(*), count(*) FROM poli GROUP BY 2
                UNION ALL SELECT 'fuerza', k, sum(c), count(*) FROM (SELECT fuerza AS k, pa, count(DISTINCT mes) AS c FROM bp GROUP BY 1, 2) s GROUP BY k
                UNION ALL SELECT 'esm', k, sum(c), count(*) FROM (SELECT esm_codigo AS k, pa, count(DISTINCT mes) AS c FROM bp GROUP BY 1, 2) s GROUP BY k
                UNION ALL SELECT 'cie', k, sum(c), count(*) FROM (SELECT cie AS k, pa, count(DISTINCT mes) AS c FROM bp GROUP BY 1, 2) s GROUP BY k
                UNION ALL SELECT 'atc3', k, sum(c), count(*) FROM (SELECT atc3 AS k, pa, count(DISTINCT mes) AS c FROM bp GROUP BY 1, 2) s GROUP BY k
                UNION ALL SELECT 'poli', '', count(*), (SELECT count(*) FROM (SELECT DISTINCT pa FROM poli) s) FROM poli
                UNION ALL SELECT 'pm', '', count(*), (SELECT count(*) FROM (SELECT DISTINCT pa FROM pm) s) FROM pm
                UNION ALL SELECT 'stat', 'min', min(n), NULL FROM pm
                UNION ALL SELECT 'stat', 'p25', round(CAST(percentile_cont(0.25) WITHIN GROUP (ORDER BY n) AS numeric), 2), NULL FROM pm
                UNION ALL SELECT 'stat', 'p50', round(CAST(percentile_cont(0.5) WITHIN GROUP (ORDER BY n) AS numeric), 2), NULL FROM pm
                UNION ALL SELECT 'stat', 'p75', round(CAST(percentile_cont(0.75) WITHIN GROUP (ORDER BY n) AS numeric), 2), NULL FROM pm
                UNION ALL SELECT 'stat', 'max', max(n), NULL FROM pm
                UNION ALL SELECT 'stat', 'avg', round(avg(n), 2), NULL FROM pm""".formatted(SemanticaSql.expresion(Dimension.CIE10_GRUPO,
                ambito.granularidad()), SemanticaSql.expresion(Dimension.ATC3, ambito.granularidad()), from, rango);
        p.put("umbral", propiedades.poliformulado());
        List<Poli> rs = jdbc.sql(sql).params(p).query((r, i) -> new Poli(r.getString(1), r.getString(2), r.getBigDecimal(3),
                r.getObject(4) == null ? null : r.getLong(4))).list();
        Function<String, List<Poli>> de = tipo -> rs.stream().filter(x -> x.tipo().equals(tipo)).toList();
        Poli poli = de.apply("poli").stream().findFirst().orElse(new Poli("poli", "", BigDecimal.ZERO, 0L));
        Poli pm = de.apply("pm").stream().findFirst().orElse(new Poli("pm", "", BigDecimal.ZERO, 0L));
        Map<String, BigDecimal> stat = new HashMap<>();
        de.apply("stat").forEach(x -> stat.put(x.clave(), x.n()));
        String criterio = "≥ " + propiedades.poliformulado() + " principios activos distintos en el mes";
        kpis.add(kpi("pacientes", "Pacientes distintos", Tipo.ENTERO, BigDecimal.valueOf(pm.pacientes() == null ? 0 : pm.pacientes()), null,
                "Conteo agregado", Map.of()));
        kpis.add(kpi("poliformulados", "Pacientes poliformulados", Tipo.ENTERO, BigDecimal.valueOf(poli.pacientes() == null ? 0 : poli.pacientes()),
                null, "Conteo agregado · " + criterio, null));
        kpis.add(kpi("porcentajePoliformulados", "Pacientes poliformulados (%)", Tipo.PORCENTAJE, AnaliticaConsulta.participacion(
                BigDecimal.valueOf(poli.pacientes() == null ? 0 : poli.pacientes()), BigDecimal.valueOf(pm.pacientes() == null ? 0 : pm.pacientes())),
                null, null, null));
        kpis.add(kpi("principiosPromedio", "Principios activos por paciente en el mes (promedio)", Tipo.DECIMAL, stat.get("avg"), null, null, null));

        // Distribución por rangos (pacientes-mes)
        List<Columna> cr = List.of(new Columna("rango", "Principios activos distintos en el mes", Tipo.TEXTO),
                new Columna("pacientesMes", "Pacientes-mes", Tipo.ENTERO), new Columna("participacion", "Participación", Tipo.PORCENTAJE),
                new Columna("pacientes", "Pacientes distintos", Tipo.ENTERO));
        Map<String, Poli> porRango = new HashMap<>();
        de.apply("rango").forEach(x -> porRango.put(x.clave(), x));
        List<Fila> filas = new ArrayList<>();
        for (int[] r : AnaliticaConsulta.RANGOS_POLIFORMULACION) {
            String e = AnaliticaConsulta.etiquetaRango(r);
            Poli x = porRango.get(e);
            long n = x == null ? 0 : x.n().longValue();
            filas.add(ResultadoTablero.fila(e, null, cr, java.util.Arrays.asList(e, n, porcentajeONulo(BigDecimal.valueOf(n), pm.n()),
                    x == null || x.pacientes() == null ? 0L : x.pacientes()), "dato"));
        }
        filas.add(ResultadoTablero.fila("(total)", null, cr, java.util.Arrays.asList("Total", pm.n().longValue(), pm.n().signum() == 0 ? null : new BigDecimal("100.00"),
                pm.pacientes() == null ? 0L : pm.pacientes()), "total"));
        tablas.add(new Tabla("rangos", "Distribución de pacientes-mes por n.º de principios activos", "barras", "pacientesMes", null, cr, filas,
                "Un paciente cuenta una vez por cada mes con dispensaciones; " + NOTA_PACIENTES.toLowerCase()));

        // Serie mensual de poliformulados
        List<Columna> cs = List.of(new Columna("periodo", "Mes", Tipo.TEXTO), new Columna("poliformulados", "Pacientes poliformulados", Tipo.ENTERO));
        Map<String, Long> porMes = new HashMap<>();
        de.apply("mes").forEach(x -> porMes.put(x.clave(), x.n().longValue()));
        List<Fila> fm = new ArrayList<>();
        for (String mes : AnaliticaConsulta.periodos(ambito.desde(), ambito.hasta(), AnaliticaConsulta.Granularidad.MES)) {
            fm.add(ResultadoTablero.fila(mes, Map.of("periodo", mes), cs, List.of(mes, porMes.getOrDefault(mes, 0L)), "dato"));
        }
        tablas.add(new Tabla("serie", "Pacientes poliformulados por mes (" + criterio + ")", "serie", "poliformulados", null, cs, fm, null));

        // Por Fuerza, ESM, diagnóstico (grupo CIE-10) y grupo farmacológico (ATC 3)
        tablaPoli("fuerza", "Poliformulados por Fuerza", Dimension.FUERZA, de.apply("fuerza"));
        tablaPoli("esm", "Poliformulados por ESM", Dimension.ESM, de.apply("esm"));
        tablaPoli("cie", "Poliformulados por grupo de diagnóstico (CIE-10)", Dimension.CIE10_GRUPO, de.apply("cie"));
        tablaPoli("atc3", "Poliformulados por grupo farmacológico (ATC nivel 3)", Dimension.ATC3, de.apply("atc3"));

        // Distribución del n.º de principios por paciente-mes
        List<Columna> cd = List.of(new Columna("estadistico", "Estadístico", Tipo.TEXTO), new Columna("principios", "Principios activos por paciente-mes",
                Tipo.DECIMAL));
        List<Fila> fd = new ArrayList<>();
        for (String[] s : new String[][] {{"min", "Mínimo"}, {"p25", "P25"}, {"p50", "Mediana"}, {"p75", "P75"}, {"max", "Máximo"}}) {
            fd.add(ResultadoTablero.fila(s[0], null, cd, java.util.Arrays.asList(s[1], stat.get(s[0])), "dato"));
        }
        BigDecimal iqr = stat.get("p75") == null || stat.get("p25") == null ? null : stat.get("p75").subtract(stat.get("p25"));
        fd.add(ResultadoTablero.fila("iqr", null, cd, java.util.Arrays.asList("Rango intercuartílico", iqr), "dato"));
        tablas.add(new Tabla("distribucion", "Distribución del n.º de principios activos por paciente y mes", "ninguno", null, null, cd, fd, null));
    }

    private static BigDecimal porcentajeONulo(BigDecimal parte, BigDecimal total) {
        return AnaliticaConsulta.participacion(parte, total);
    }

    /** Fila de la consulta de poliformulación: tipo de agrupación, clave, pacientes-mes (o el estadístico) y pacientes distintos. */
    private record Poli(String tipo, String clave, BigDecimal n, Long pacientes) {
    }

    private void tablaPoli(String id, String titulo, Dimension d, List<Poli> crudas) {
        List<String> claves = new ArrayList<>();
        Map<String, long[]> v = new HashMap<>();
        for (Poli o : crudas) {
            claves.add(o.clave());
            v.put(o.clave(), new long[] {o.n().longValue(), o.pacientes() == null ? 0 : o.pacientes()});
        }
        etiquetas.cargar(d, claves);
        List<Columna> cols = List.of(new Columna("clave", d.etiqueta(), Tipo.TEXTO), new Columna("pacientesMes", "Pacientes-mes poliformulados",
                Tipo.ENTERO), new Columna("pacientes", "Pacientes poliformulados distintos", Tipo.ENTERO));
        List<String> orden = new ArrayList<>(claves);
        orden.sort(Comparator.<String>comparingLong(k -> v.get(k)[1]).reversed().thenComparing(Comparator.naturalOrder()));
        List<Fila> filas = new ArrayList<>();
        for (String k : orden) {
            // Sin «Rastrear»: la cifra es de la cohorte poliformulada, no una suma de registros que la traza pueda reproducir
            filas.add(ResultadoTablero.fila(k, null, cols, List.of(etiquetas.de(d, k), v.get(k)[0], v.get(k)[1]), especial(k) ? "sin" : "dato"));
        }
        tablas.add(new Tabla(id, titulo, "barras", "pacientes", null, cols, filas, NOTA_PACIENTES));
    }

    // ---------- Piezas comunes ----------

    /** KPI de gasto, promedios, dispensaciones, unidades y pacientes del total. */
    private void kpisBase(Grupo t, BigDecimal comparado, boolean soloGasto) {
        registros = t.dispensaciones();
        long pac = t.pacientes() == null ? 0 : t.pacientes();
        kpis.add(kpi("gasto", "Gasto total", Tipo.MONEDA, t.gasto(), comparado, null, Map.of()));
        kpis.add(kpi("promedioDispensacion", "Gasto promedio por dispensación", Tipo.MONEDA, AnaliticaConsulta.promedio(t.gasto(), t.dispensaciones()),
                null, null, null));
        kpis.add(kpi("promedioPaciente", "Gasto promedio por paciente", Tipo.MONEDA, AnaliticaConsulta.promedio(t.gasto(), pac), null, "Agregado",
                null));
        kpis.add(kpi("pacientes", "Pacientes atendidos", Tipo.ENTERO, BigDecimal.valueOf(pac), null, "Conteo agregado", Map.of()));
        if (!soloGasto) {
            kpis.add(kpi("dispensaciones", "Dispensaciones", Tipo.ENTERO, BigDecimal.valueOf(t.dispensaciones()), null, null, Map.of()));
            kpis.add(kpi("unidades", "Unidades dispensadas", Tipo.UNIDADES, t.unidades(), null, null, Map.of()));
        }
    }

    Kpi kpi(String clave, String etiqueta, Tipo tipo, BigDecimal valor, BigDecimal comparado, String nota, Map<String, String> rastreo) {
        BigDecimal var = null;
        BigDecimal pct = null;
        String textoVar = null;
        if (comparacion != null && comparado != null) {
            AnaliticaConsulta.Variacion v = AnaliticaConsulta.variacion(valor, comparado);
            var = v.absoluta();
            pct = v.porcentaje();
            textoVar = AnaliticaConsulta.conSigno(AnaliticaConsulta.porcentaje(pct), pct) + " frente a " + periodoComparacionTexto();
        }
        Object valorFormato = tipo == Tipo.ENTERO && valor != null ? (Object) valor.longValue() : valor;
        return new Kpi(clave, etiqueta, tipo, valor, AnaliticaConsulta.formato(valorFormato, tipo), comparacion == null ? null : comparado,
                comparacion == null || comparado == null ? null : AnaliticaConsulta.formato(comparado, tipo), var, pct, textoVar, nota, rastreo);
    }

    String periodoComparacionTexto() {
        return comparacion == null ? "" : AnaliticaConsulta.fecha(comparacion.desde()) + " – " + AnaliticaConsulta.fecha(comparacion.hasta());
    }

    private static boolean especial(String k) {
        return AnaliticaConsulta.SIN_HOMOLOGAR.equals(k) || AnaliticaConsulta.SIN_DATO.equals(k);
    }

    /** Filtros que identifican una fila (para rastrearla): la variable de la dimensión con la clave; el periodo por su etiqueta. */
    static Map<String, String> rastreo(Dimension d, String clave) {
        if (d == Dimension.PERIODO) {
            return Map.of("periodo", clave);
        }
        if (d.variable() == null || clave == null) {
            return null;
        }
        return Map.of(d.variable().parametro(), clave);
    }

    /** Valor de una métrica ordenable de un grupo. */
    private static BigDecimal metrica(Grupo g, Metrica m) {
        return switch (m) {
            case UNIDADES -> g.unidades();
            case DISPENSACIONES -> BigDecimal.valueOf(g.dispensaciones());
            case PACIENTES -> g.pacientes() == null ? null : BigDecimal.valueOf(g.pacientes());
            default -> g.gasto();
        };
    }

    /**
     * Tabla por una dimensión: Top 20 (ordenado por la métrica elegida, empates por clave) + «Resto» + filas sin homologar o sin dato + Total;
     * o todas las filas por gasto (o por periodo). Siempre cuadra con el total.
     */
    Tabla porDimension(String id, String titulo, Dimension d, List<Grupo> grupos, Grupo total, List<Col> cols, boolean top,
                       Map<String, BigDecimal> comparados, String grafico, String valorGrafico, String nota) {
        etiquetas.cargar(d, grupos.stream().map(g -> g.clave(d)).toList());
        Metrica orden = top ? consulta.orden() : Metrica.GASTO;
        List<Grupo> normales = new ArrayList<>(grupos.stream().filter(g -> !especial(g.clave(d))).toList());
        List<Grupo> especiales = new ArrayList<>(grupos.stream().filter(g -> especial(g.clave(d))).toList());
        especiales.sort(Comparator.comparing(g -> g.clave(d)));
        if (d == Dimension.PERIODO) {
            normales.sort(Comparator.comparing(g -> g.clave(d)));
        } else {
            normales.sort(AnaliticaConsulta.ordenRanking(g -> metrica(g, orden), g -> g.clave(d)));
        }
        List<Columna> columnas = new ArrayList<>();
        columnas.add(new Columna("clave", d.etiqueta(), Tipo.TEXTO));
        cols.forEach(c -> columnas.add(c.columna()));
        List<Fila> filas = new ArrayList<>();
        BigDecimal[] acumulado = {BigDecimal.ZERO};
        List<Grupo> mostrados = top ? normales.subList(0, Math.min(AnaliticaConsulta.TOP, normales.size())) : normales;
        for (Grupo g : mostrados) {
            filas.add(fila(d, g.clave(d), etiquetas.de(d, g.clave(d)), g, total, cols, comparados, acumulado, rastreo(d, g.clave(d)), "dato"));
        }
        if (top && normales.size() > AnaliticaConsulta.TOP) {
            List<Grupo> resto = normales.subList(AnaliticaConsulta.TOP, normales.size());
            Grupo r = new Grupo(-1, Map.of(), Map.of(), resto.stream().map(Grupo::gasto).reduce(BigDecimal.ZERO, BigDecimal::add),
                    resto.stream().map(Grupo::unidades).reduce(BigDecimal.ZERO, BigDecimal::add), resto.stream().mapToLong(Grupo::dispensaciones).sum(),
                    null, null);
            BigDecimal compResto = comparados == null ? null : resto.stream().map(g -> comparados.getOrDefault(g.clave(d), BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, BigDecimal> cr = comparados == null ? null : Map.of("(resto)", compResto);
            filas.add(fila(d, "(resto)", AnaliticaConsulta.etiquetaResto(resto.size()), r, total, cols, cr, acumulado, null, "resto"));
        }
        for (Grupo g : especiales) {
            filas.add(fila(d, g.clave(d), etiquetas.de(d, g.clave(d)), g, total, cols, comparados, acumulado, rastreo(d, g.clave(d)), "sin"));
        }
        BigDecimal compTotal = comparados == null ? null : comparados.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, BigDecimal> ct = comparados == null ? null : Map.of("(total)", compTotal);
        filas.add(fila(d, "(total)", "Total", total, total, cols, ct, new BigDecimal[] {null}, Map.of(), "total"));
        return new Tabla(id, titulo, grafico, valorGrafico, comparacion == null ? null : "comparado", List.copyOf(columnas), filas, nota);
    }

    private Fila fila(Dimension d, String clave, String etiqueta, Grupo g, Grupo total, List<Col> cols, Map<String, BigDecimal> comparados,
                      BigDecimal[] acumulado, Map<String, String> rastreo, String tipo) {
        List<Columna> columnas = new ArrayList<>();
        columnas.add(new Columna("clave", d.etiqueta(), Tipo.TEXTO));
        List<Object> v = new ArrayList<>();
        v.add(etiqueta);
        BigDecimal part = AnaliticaConsulta.participacion(g.gasto(), total.gasto());
        BigDecimal comp = comparados == null ? null : comparados.getOrDefault(clave, BigDecimal.ZERO);
        for (Col c : cols) {
            columnas.add(c.columna());
            v.add(switch (c) {
                case GASTO -> g.gasto();
                case COMPARADO -> comp;
                case VARIACION -> comp == null ? null : AnaliticaConsulta.variacion(g.gasto(), comp).absoluta();
                case VARIACION_PCT -> comp == null ? null : AnaliticaConsulta.variacion(g.gasto(), comp).porcentaje();
                case PARTICIPACION -> part;
                case ACUMULADA -> {
                    if (acumulado[0] == null) {
                        yield part;
                    }
                    acumulado[0] = acumulado[0].add(g.gasto());
                    yield AnaliticaConsulta.participacion(acumulado[0], total.gasto());
                }
                case UNIDADES -> g.unidades();
                case DISPENSACIONES -> g.dispensaciones();
                case PACIENTES -> g.pacientes();
                case PROMEDIO_DISPENSACION -> AnaliticaConsulta.promedio(g.gasto(), g.dispensaciones());
                case PROMEDIO_PACIENTE -> g.pacientes() == null ? null : AnaliticaConsulta.promedio(g.gasto(), g.pacientes());
                case VALOR_UNITARIO -> g.unidades().signum() == 0 ? null : g.gasto().divide(g.unidades(), 2, java.math.RoundingMode.HALF_UP);
                default -> g.extras().get(c.extra());
            });
        }
        return ResultadoTablero.fila(clave, rastreo, columnas, v, tipo);
    }

    /** Serie de tiempo completa (todos los periodos del rango, con ceros) y, si hay comparación, el periodo comparado alineado por posición. */
    Tabla serie(String id, String titulo, List<Grupo> porPeriodo, boolean conPacientes) {
        Map<String, Grupo> m = new HashMap<>();
        porPeriodo.forEach(g -> m.put(g.clave(Dimension.PERIODO), g));
        List<String> etiquetasPeriodo = AnaliticaConsulta.periodos(ambito.desde(), ambito.hasta(), ambito.granularidad());
        List<String> comparadas = List.of();
        Map<String, BigDecimal> comp = Map.of();
        if (comparacion != null) {
            comparadas = AnaliticaConsulta.periodos(comparacion.desde(), comparacion.hasta(), ambito.granularidad());
            Map<String, BigDecimal> c = new HashMap<>();
            for (Grupo g : correr(comparacion, List.of(Conjunto.de(Dimension.PERIODO)), false, false)) {
                c.put(g.clave(Dimension.PERIODO), g.gasto());
            }
            comp = c;
        }
        List<Columna> columnas = new ArrayList<>(List.of(new Columna("periodo", "Periodo", Tipo.TEXTO), new Columna("gasto", "Gasto", Tipo.MONEDA)));
        if (comparacion != null) {
            columnas.add(new Columna("periodoComparado", "Periodo de comparación", Tipo.TEXTO));
            columnas.add(new Columna("comparado", "Gasto periodo de comparación", Tipo.MONEDA));
            columnas.add(new Columna("variacionPct", "Variación %", Tipo.PORCENTAJE));
        }
        columnas.add(new Columna("dispensaciones", "Dispensaciones", Tipo.ENTERO));
        columnas.add(new Columna("unidades", "Unidades", Tipo.UNIDADES));
        if (conPacientes) {
            columnas.add(new Columna("pacientes", "Pacientes distintos", Tipo.ENTERO));
        }
        List<Fila> filas = new ArrayList<>();
        for (int i = 0; i < etiquetasPeriodo.size(); i++) {
            String e = etiquetasPeriodo.get(i);
            Grupo g = m.get(e);
            List<Object> v = new ArrayList<>();
            v.add(e);
            BigDecimal gasto = g == null ? BigDecimal.ZERO : g.gasto();
            v.add(gasto);
            if (comparacion != null) {
                String pc = i < comparadas.size() ? comparadas.get(i) : null;
                BigDecimal c = pc == null ? null : comp.getOrDefault(pc, BigDecimal.ZERO);
                v.add(pc);
                v.add(c);
                v.add(c == null ? null : AnaliticaConsulta.variacion(gasto, c).porcentaje());
            }
            v.add(g == null ? 0L : g.dispensaciones());
            v.add(g == null ? BigDecimal.ZERO : g.unidades());
            if (conPacientes) {
                v.add(g == null ? 0L : g.pacientes());
            }
            filas.add(ResultadoTablero.fila(e, Map.of("periodo", e), columnas, v, "dato"));
        }
        return new Tabla(id, titulo + " (" + ambito.granularidad().etiqueta().toLowerCase() + ")", "serie", "gasto",
                comparacion == null ? null : "comparado", List.copyOf(columnas), filas, conPacientes ? NOTA_PACIENTES : null);
    }

    /** Mapa de calor ESM × periodo (gasto), con su tabla: filas por gasto total descendente, columnas por periodo. */
    Tabla mapaCalor(List<Grupo> grupos, Ambito ac) {
        etiquetas.cargar(Dimension.ESM, grupos.stream().map(g -> g.clave(Dimension.ESM)).toList());
        List<String> periodos = AnaliticaConsulta.periodos(ac.desde(), ac.hasta(), ac.granularidad());
        Map<String, Map<String, BigDecimal>> m = new LinkedHashMap<>();
        Map<String, BigDecimal> totalEsm = new HashMap<>();
        for (Grupo g : grupos) {
            m.computeIfAbsent(g.clave(Dimension.ESM), k -> new HashMap<>()).put(g.clave(Dimension.PERIODO), g.gasto());
            totalEsm.merge(g.clave(Dimension.ESM), g.gasto(), BigDecimal::add);
        }
        List<String> esms = new ArrayList<>(m.keySet());
        esms.sort(AnaliticaConsulta.ordenRanking(totalEsm::get, Function.identity()));
        List<Columna> columnas = new ArrayList<>();
        columnas.add(new Columna("esm", "ESM", Tipo.TEXTO));
        periodos.forEach(p -> columnas.add(new Columna(p, p, Tipo.MONEDA)));
        columnas.add(new Columna("total", "Total", Tipo.MONEDA));
        List<Fila> filas = new ArrayList<>();
        for (String e : esms) {
            List<Object> v = new ArrayList<>();
            v.add(etiquetas.de(Dimension.ESM, e));
            for (String p : periodos) {
                v.add(m.get(e).getOrDefault(p, BigDecimal.ZERO));
            }
            v.add(totalEsm.get(e));
            filas.add(ResultadoTablero.fila(e, Map.of("esm", e), columnas, v, "dato"));
        }
        return new Tabla("calor", "Mapa de calor del gasto: ESM × periodo (" + ac.granularidad().etiqueta().toLowerCase() + ")", "calor", null,
                null, List.copyOf(columnas), filas, null);
    }

    /** Cruce de dos dimensiones (Top 20 combinaciones por la métrica del ranking + «Resto» + Total). */
    Tabla cruce(String id, String titulo, Dimension a, Dimension b, List<Grupo> grupos, Grupo total) {
        etiquetas.cargar(a, grupos.stream().map(g -> g.clave(a)).toList());
        etiquetas.cargar(b, grupos.stream().map(g -> g.clave(b)).toList());
        List<Grupo> o = new ArrayList<>(grupos);
        o.sort(AnaliticaConsulta.ordenRanking(g -> metrica(g, consulta.orden()), g -> g.clave(a) + "\u0001" + g.clave(b)));
        List<Columna> columnas = List.of(new Columna("a", a.etiqueta(), Tipo.TEXTO), new Columna("b", b.etiqueta(), Tipo.TEXTO),
                new Columna("gasto", "Gasto", Tipo.MONEDA), new Columna("participacion", "Participación", Tipo.PORCENTAJE),
                new Columna("dispensaciones", "Dispensaciones", Tipo.ENTERO), new Columna("pacientes", "Pacientes distintos", Tipo.ENTERO));
        List<Fila> filas = new ArrayList<>();
        for (Grupo g : o.subList(0, Math.min(AnaliticaConsulta.TOP, o.size()))) {
            Map<String, String> r = new LinkedHashMap<>();
            if (a.variable() != null) {
                r.put(a.variable().parametro(), g.clave(a));
            }
            if (b.variable() != null) {
                r.put(b.variable().parametro(), g.clave(b));
            }
            filas.add(ResultadoTablero.fila(g.clave(a) + "|" + g.clave(b), r, columnas, java.util.Arrays.asList(etiquetas.de(a, g.clave(a)),
                    etiquetas.de(b, g.clave(b)), g.gasto(), AnaliticaConsulta.participacion(g.gasto(), total.gasto()), g.dispensaciones(),
                    g.pacientes()), "dato"));
        }
        if (o.size() > AnaliticaConsulta.TOP) {
            List<Grupo> resto = o.subList(AnaliticaConsulta.TOP, o.size());
            BigDecimal gr = resto.stream().map(Grupo::gasto).reduce(BigDecimal.ZERO, BigDecimal::add);
            filas.add(ResultadoTablero.fila("(resto)", null, columnas, java.util.Arrays.asList(AnaliticaConsulta.etiquetaResto(resto.size()), "",
                    gr, AnaliticaConsulta.participacion(gr, total.gasto()), resto.stream().mapToLong(Grupo::dispensaciones).sum(), null), "resto"));
        }
        filas.add(ResultadoTablero.fila("(total)", Map.of(), columnas, java.util.Arrays.asList("Total", "", total.gasto(),
                AnaliticaConsulta.participacion(total.gasto(), total.gasto()), total.dispensaciones(), total.pacientes()), "total"));
        return new Tabla(id, titulo, "ninguno", null, null, columnas, filas, NOTA_PACIENTES);
    }

    /** Mínimo, P25, mediana, P75, máximo y rango intercuartílico del gasto por dispensación y por paciente (percentile_cont sobre los hechos). */
    Tabla distribucion() {
        // Por dispensación: tabla de frecuencias del valor (pocos valores distintos, agrupada por hash en paralelo) y percentil continuo
        // exacto en Java; por paciente: gasto de cada paciente (hash sobre el entero) y percentile_cont sobre esas sumas
        Map<String, Object> p = new LinkedHashMap<>();
        String from = SemanticaSql.desde(ambito, Set.of(), List.of(), SemanticaSql.TABLERO, p);
        String cte = SemanticaSql.cte(Set.of(), ambito.filtros(), List.of());
        List<BigDecimal[]> frecuencias = jdbc.sql(cte + "SELECT x.valor_centavos, count(*)" + from + " GROUP BY x.valor_centavos").params(p)
                .query((rs, n) -> new BigDecimal[] {BigDecimal.valueOf(rs.getLong(1), 2), BigDecimal.valueOf(rs.getLong(2))}).list();
        BigDecimal[] d = AnaliticaConsulta.cuartiles(frecuencias);
        Map<String, Object> p2 = new LinkedHashMap<>();
        String from2 = SemanticaSql.desde(ambito, Set.of(), List.of(), SemanticaSql.TABLERO, p2);
        String est = "min(v), round(CAST(percentile_cont(0.25) WITHIN GROUP (ORDER BY v) AS numeric), 2), "
                + "round(CAST(percentile_cont(0.5) WITHIN GROUP (ORDER BY v) AS numeric), 2), "
                + "round(CAST(percentile_cont(0.75) WITHIN GROUP (ORDER BY v) AS numeric), 2), max(v)";
        BigDecimal[] pa = porPaciente != null ? porPaciente.clone() : jdbc.sql(cte + "SELECT " + est + " FROM (SELECT round(CAST(sum(x.valor_centavos) AS numeric) / 100, 2) AS v" + from2
                + " GROUP BY x.paciente_id) s").params(p2)
                .query((rs, n) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                    rs.getBigDecimal(5)}).single();
        if (propiedades.supresion() > 0) {
            // Mínimo y máximo del gasto por paciente son el gasto de una persona concreta: con la supresión activa no se publican
            pa[0] = null;
            pa[4] = null;
            observaciones.add("Gasto por paciente: mínimo y máximo ocultos por la supresión de celdas pequeñas (son el gasto de un solo paciente).");
        }
        List<Columna> columnas = List.of(new Columna("estadistico", "Estadístico", Tipo.TEXTO), new Columna("dispensacion", "Gasto por dispensación",
                Tipo.MONEDA), new Columna("paciente", "Gasto por paciente", Tipo.MONEDA));
        String[] nombres = {"Mínimo", "P25", "Mediana", "P75", "Máximo"};
        List<Fila> filas = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            filas.add(ResultadoTablero.fila(nombres[i], null, columnas, java.util.Arrays.asList(nombres[i], d[i], pa[i]), "dato"));
        }
        filas.add(ResultadoTablero.fila("iqr", null, columnas, java.util.Arrays.asList("Rango intercuartílico", d[3] == null ? null
                : d[3].subtract(d[1]), pa[3] == null ? null : pa[3].subtract(pa[1])), "dato"));
        return new Tabla("distribucion", "Mediana y rangos del gasto", "ninguno", null, null, columnas, filas, null);
    }
}
