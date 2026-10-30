package co.com.agsamericas.digsa.analitica.tableros;

import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Dimension;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Granularidad;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Variable;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * Generador del SQL de la capa semántica (lista blanca de {@link AnaliticaConsulta}): el único camino de SQL hacia los hechos de los
 * tableros, las descargas, la trazabilidad y el asistente. Todo valor va como parámetro; las expresiones salen de esta tabla fija (nunca
 * de texto del usuario). Siempre lleva los cargues visibles al corte, el rango del periodo y el alcance del usuario.
 *
 * <p>Las cifras salen de la copia angosta de los hechos ({@code ana_hechos_tablero}, paciente como entero) y la trazabilidad lee los
 * hechos completos ({@code ana_dispensaciones}) con los mismos predicados: las mismas filas. Los pacientes distintos de cada conjunto se
 * cuentan en dos niveles (revisión A3 2026-10-23): primero se agrupa por (claves, paciente) por hash y en paralelo, luego se cuentan las
 * filas por clave. Es exacto (cada paciente cuenta una vez por celda; nunca se suman pacientes entre celdas) y evita el
 * count(DISTINCT) por orden con la intercalación, que tardaba minutos sobre 2 M filas.
 *
 * <p>La tabla maestra se lee solo con {@code hom_tabla_maestra_version(v)} y la CIE-10 con {@code hom_tabla_cie10_version(v)}, con la
 * versión publicada vigente al corte; todo estado distinto de «homologado» va a «Sin homologar». Filtro, dimensión y rastreo usan el
 * MISMO código: el final homologado (ATC y CIE-10 por prefijo), así la traza de una celda suma exactamente su cifra (14.9).
 */
public final class SemanticaSql {

    /** Tabla de las cifras de los tableros. */
    static final String TABLERO = "ana_hechos_tablero";
    /** Hechos completos (registros de origen de la trazabilidad). */
    static final String HECHOS = "ana_dispensaciones";

    private SemanticaSql() {
    }

    /** Fecha de corte resuelta: el cargue elegido, el instante que fija, los cargues visibles y la versión de la tabla maestra. */
    public record Corte(long cargueId, OffsetDateTime aplicadoEn, OffsetDateTime instante, Integer homVersion, Integer homNumero, List<Long> cargues,
                 LocalDate datosDesde, LocalDate datosHasta) {
    }

    /**
     * Cohorte resuelta para un filtro (A7.1): la versión de mayor número con resultado «evaluada» y membresía conservada en el corte (la
     * misma regla de las tarjetas del tablero 10), su criterio en texto y si es sensible (VIH, huérfanas: supresión k de AN.24).
     */
    public record CohorteResuelta(String codigo, String nombre, long versionId, int version, String criterio, boolean sensible) {

        /** «Alto costo (v1: ≥ p99 del gasto…)»: lo que sale en los metadatos de 14.8 y en las descargas. */
        public String texto() {
            return nombre + " (v" + version + ": " + criterio + ")";
        }
    }

    /**
     * Filtro de cohorte, GCF o GRC ya resuelto contra el corte: las versiones (O entre las cohortes de la variable) y, en las particiones,
     * los grupos pedidos. Entre variables, Y (como los demás filtros).
     */
    public record FiltroCohorte(Variable variable, List<CohorteResuelta> cohortes, List<String> grupos) {
    }

    /**
     * Qué se consulta: corte, alcance, filtros validados, periodo, los filtros de cohorte resueltos contra el corte (A7.1) y si algún filtro
     * es sensible (cohorte, CIE-10, ATC o grupo de GCF de VIH o huérfanas: supresión reforzada, AN.31).
     */
    record Ambito(Corte corte, Alcance alcance, Map<Variable, List<String>> filtros, LocalDate desde, LocalDate hasta, Granularidad granularidad,
                  Map<Variable, FiltroCohorte> cohortes, boolean sensible) {

        Ambito {
            cohortes = cohortes == null ? Map.of() : Map.copyOf(cohortes);
        }

        /** Ámbito sin filtros de cohorte (opciones de los filtros, umbral y miembros de alto costo). */
        Ambito(Corte corte, Alcance alcance, Map<Variable, List<String>> filtros, LocalDate desde, LocalDate hasta, Granularidad granularidad) {
            this(corte, alcance, filtros, desde, hasta, granularidad, Map.of(), false);
        }

        Ambito conPeriodo(LocalDate d, LocalDate h) {
            return new Ambito(corte, alcance, filtros, d, h, granularidad, cohortes, sensible);
        }

        Ambito conGranularidad(Granularidad g) {
            return new Ambito(corte, alcance, filtros, desde, hasta, g, cohortes, sensible);
        }

        Ambito conFiltros(Map<Variable, List<String>> f) {
            Map<Variable, FiltroCohorte> c = new EnumMap<>(Variable.class);
            cohortes.forEach((k, v) -> {
                if (f.containsKey(k)) {
                    c.put(k, v);
                }
            });
            return new Ambito(corte, alcance, f, desde, hasta, granularidad, c, sensible);
        }
    }

    /** Atributos de la fila que se agregan con min() (o «Varias» si hay más de uno). */
    enum Extra {
        NOMBRE_COMERCIAL("tm.fin_nombre_comercial", false), DCI("tm.fin_dci", false), REGISTRO_SANITARIO("tm.fin_registro_sanitario", false),
        PRESENTACION("tm.fin_presentacion", true), CONCENTRACION("tm.fin_concentracion", true), FORMA("tm.fin_forma", true);

        private final String columna;
        private final boolean varias;

        Extra(String columna, boolean varias) {
            this.columna = columna;
            this.varias = varias;
        }
    }

    /** Un conjunto de agrupación: sus dimensiones y los atributos que lleva. */
    record Conjunto(List<Dimension> dimensiones, List<Extra> extras) {

        static Conjunto de(Dimension... d) {
            return new Conjunto(List.of(d), List.of());
        }

        static Conjunto total() {
            return new Conjunto(List.of(), List.of());
        }
    }

    /** Fila agrupada: claves de sus dimensiones, atributos y medidas (pacientes y fórmulas solo si se piden). */
    record Grupo(int conjunto, Map<Dimension, String> claves, Map<Extra, String> extras, BigDecimal gasto, BigDecimal unidades, long dispensaciones,
                 Long pacientes, Long formulas) {

        String clave(Dimension d) {
            return claves.get(d);
        }

        Grupo conFormulas(Long f) {
            return new Grupo(conjunto, claves, extras, gasto, unidades, dispensaciones, pacientes, f);
        }
    }

    /** SQL armado de UN conjunto con sus parámetros y cómo leer cada fila. */
    record Armado(String sql, Map<String, Object> parametros, Conjunto conjunto, int indice, boolean pacientes) {

        Grupo leer(ResultSet rs) throws SQLException {
            Map<Dimension, String> claves = new EnumMap<>(Dimension.class);
            for (int i = 0; i < conjunto.dimensiones().size(); i++) {
                claves.put(conjunto.dimensiones().get(i), rs.getString("k_" + i));
            }
            Map<Extra, String> ex = new EnumMap<>(Extra.class);
            for (Extra e : conjunto.extras()) {
                ex.put(e, rs.getString("e_" + e.ordinal()));
            }
            Long pac = pacientes ? rs.getLong("pacientes") : null;
            // La tabla de los tableros guarda centavos y milésimas (enteros): aquí vuelven a pesos (2 decimales) y unidades (3 decimales)
            BigDecimal gasto = rs.getBigDecimal("gasto");
            BigDecimal unidades = rs.getBigDecimal("unidades");
            return new Grupo(indice, claves, ex, gasto == null ? new BigDecimal("0.00") : gasto.movePointLeft(2),
                    unidades == null ? new BigDecimal("0.000") : unidades.movePointLeft(3),
                    rs.getLong("dispensaciones"), pac, null);
        }
    }

    public static final String CTE_TM = """
            tm AS MATERIALIZED (
                SELECT origen_id, estado, fin_atc, fin_principio_activo, fin_cum, fin_nombre_comercial, fin_dci, fin_concentracion, fin_forma,
                       fin_presentacion, fin_registro_sanitario, fin_codigo_ssfm, regla, orig_cum, orig_descripcion, fuente_codigo
                FROM hom_tabla_maestra_version(CAST(:hv AS integer)))""";
    public static final String CTE_TC = """
            tc AS MATERIALIZED (
                SELECT o.codigo_original, t.codigo_final, t.estado
                FROM hom_tabla_cie10_version(CAST(:hv AS integer)) t JOIN hom_cie10_origen o ON o.id = t.origen_id)""";
    static final String VISIBLES = "x.cargue_id = ANY(CAST(:cargues AS bigint[]))";

    private static final String SH = "'" + AnaliticaConsulta.SIN_HOMOLOGAR + "'";
    private static final String SD = "'" + AnaliticaConsulta.SIN_DATO + "'";
    private static final String HOMOLOGADO = "tm.estado = 'homologado'";
    private static final String CIE_HOMOLOGADO = "tc.estado = 'homologado'";

    /**
     * Expresión de la clave de una dimensión (nunca nula: lo no homologado y lo que falta van a sus valores especiales), con intercalación
     * «C»: agrupar u ordenar las claves compara bytes y no la intercalación de la base (las claves son códigos; el orden visible es de Java).
     */
    public static String expresion(Dimension d, Granularidad g) {
        return "(" + expresionBase(d, g) + ") COLLATE \"C\"";
    }

    private static String expresionBase(Dimension d, Granularidad g) {
        return switch (d) {
            case PERIODO -> periodo("x.fecha", g);
            case FUERZA -> "x.fuerza";
            case DISAN -> "coalesce(x.disan, " + SD + ")";
            case REGIONAL -> "coalesce(x.regional_id::text, " + SD + ")";
            case ESM -> "x.esm_codigo";
            case DEPARTAMENTO -> "coalesce(mu.departamento::text, " + SD + ")";
            case MUNICIPIO -> "coalesce(p.municipio::text, " + SD + ")";
            case MODALIDAD -> "x.modalidad";
            case ATC1, ATC2, ATC3, ATC4, ATC5 -> "CASE WHEN " + HOMOLOGADO + " AND tm.fin_atc IS NOT NULL THEN left(tm.fin_atc, " + d.largoAtc()
                    + ") ELSE " + SH + " END";
            case PRINCIPIO_ACTIVO -> "CASE WHEN " + HOMOLOGADO + " AND tm.fin_principio_activo IS NOT NULL THEN tm.fin_principio_activo ELSE " + SH
                    + " END";
            case MEDICAMENTO -> "CASE WHEN " + HOMOLOGADO + " THEN coalesce(tm.fin_cum, 'ORIGEN-' || x.medicamento_id) ELSE " + SH + " END";
            case CUM -> "coalesce(x.cum, " + SD + ")";
            case CIE10 -> "CASE WHEN x.cie10 IS NULL THEN " + SD + " WHEN " + CIE_HOMOLOGADO + " THEN tc.codigo_final::text ELSE " + SH + " END";
            case CIE10_GRUPO -> "CASE WHEN x.cie10 IS NULL THEN " + SD + " WHEN " + CIE_HOMOLOGADO + " THEN left(tc.codigo_final, 3) ELSE " + SH
                    + " END";
            case ESTADO_HOMOLOGACION -> "coalesce(tm.estado, 'pendiente')";
            case TIPO_FORMULA -> "coalesce(x.tipo_formula, " + SD + ")";
            case ESPECIALIDAD -> "coalesce(x.especialidad, " + SD + ")";
        };
    }

    /** Etiqueta del periodo en SQL: la misma de AnaliticaConsulta.etiquetaPeriodo y de ana_dim_tiempo. */
    static String periodo(String c, Granularidad g) {
        return switch (g) {
            case HORA, DIA -> "to_char(" + c + ", 'YYYY-MM-DD')";
            case SEMANA -> "to_char(" + c + ", 'IYYY-\"W\"IW')";
            case MES -> "to_char(" + c + ", 'YYYY-MM')";
            case BIMESTRE -> "to_char(" + c + ", 'YYYY') || '-B' || ((extract(month FROM " + c + ")::int + 1) / 2)";
            case TRIMESTRE -> "to_char(" + c + ", 'YYYY') || '-T' || extract(quarter FROM " + c + ")::int";
            case SEMESTRE -> "to_char(" + c + ", 'YYYY') || '-S' || CASE WHEN extract(month FROM " + c + ") <= 6 THEN 1 ELSE 2 END";
            case ANIO -> "to_char(" + c + ", 'YYYY')";
        };
    }

    /** Predicado del alcance (en SQL, nunca después). Otro alcance que no sea nacional, Fuerza o ESM no entra a la analítica. */
    static String alcance(Alcance a, Map<String, Object> p) {
        return switch (a) {
            case Alcance.Todo t -> "TRUE";
            case Alcance.Fuerza f -> {
                p.put("alcFuerza", f.codigo());
                yield "x.fuerza = :alcFuerza";
            }
            case Alcance.Esm e -> {
                p.put("alcPunto", e.puntoId());
                yield "x.punto_id = :alcPunto";
            }
            default -> throw new ErrorNegocio(HttpStatus.FORBIDDEN, "Su perfil no tiene un alcance de datos para la analítica farmacéutica.");
        };
    }

    /** ¿Algún filtro o dimensión necesita la tabla maestra / la CIE-10 / el punto? */
    static boolean usaTm(Set<Dimension> dims, Map<Variable, List<String>> f, List<Extra> extras) {
        return !extras.isEmpty() || dims.stream().anyMatch(Dimension::deTablaMaestra) || f.containsKey(Variable.ATC) || f.containsKey(Variable.PRINCIPIO)
                || f.containsKey(Variable.MEDICAMENTO) || f.containsKey(Variable.SSFM) || f.containsKey(Variable.ESTADO);
    }

    /** La CIE-10 homologada se une siempre que haya dimensión o filtro CIE-10 (el filtro usa el código final, como la dimensión). */
    static boolean usaTc(Set<Dimension> dims, Map<Variable, List<String>> f) {
        return dims.contains(Dimension.CIE10) || dims.contains(Dimension.CIE10_GRUPO) || f.containsKey(Variable.CIE10);
    }

    static boolean usaPunto(Set<Dimension> dims) {
        return dims.contains(Dimension.DEPARTAMENTO) || dims.contains(Dimension.MUNICIPIO);
    }

    /** Predicados de los filtros (Y entre variables, O dentro de una variable). Mismo código que la dimensión de cada variable. */
    static List<String> filtros(Map<Variable, List<String>> f, Map<String, Object> p) {
        List<String> w = new ArrayList<>();
        for (Map.Entry<Variable, List<String>> e : f.entrySet()) {
            if (e.getKey().deCohorte()) {
                // Cohorte, GCF y GRC: semijoin con la membresía del corte (ver cohortes()), que depende de la tabla de hechos
                continue;
            }
            List<String> normales = e.getValue().stream().filter(v -> !AnaliticaConsulta.SIN_HOMOLOGAR.equals(v) && !AnaliticaConsulta.SIN_DATO.equals(v))
                    .toList();
            boolean sinHom = e.getValue().contains(AnaliticaConsulta.SIN_HOMOLOGAR);
            boolean sinDato = e.getValue().contains(AnaliticaConsulta.SIN_DATO);
            String n = "f_" + e.getKey().parametro();
            String[] arr = normales.toArray(String[]::new);
            List<String> o = new ArrayList<>();
            switch (e.getKey()) {
                case FUERZA -> o.add("x.fuerza = ANY(CAST(:" + n + " AS text[]))");
                case DISAN -> {
                    if (arr.length > 0) {
                        o.add("x.disan = ANY(CAST(:" + n + " AS text[]))");
                    }
                    if (sinDato) {
                        o.add("x.disan IS NULL");
                    }
                }
                case ESM -> o.add("x.esm_codigo = ANY(CAST(:" + n + " AS text[]))");
                case MODALIDAD -> o.add("x.modalidad = ANY(CAST(:" + n + " AS text[]))");
                case CUM -> {
                    if (arr.length > 0) {
                        o.add("x.cum = ANY(CAST(:" + n + " AS text[]))");
                    }
                    if (sinDato) {
                        o.add("x.cum IS NULL");
                    }
                }
                case REGIONAL -> {
                    if (arr.length > 0) {
                        p.put(n + "_id", normales.stream().map(Long::valueOf).toArray(Long[]::new));
                        o.add("x.regional_id = ANY(CAST(:" + n + "_id AS bigint[]))");
                    }
                    if (sinDato) {
                        o.add("x.regional_id IS NULL");
                    }
                }
                case ATC -> {
                    if (arr.length > 0) {
                        p.put(n + "_like", normales.stream().map(v -> v + "%").toArray(String[]::new));
                        o.add("(" + HOMOLOGADO + " AND tm.fin_atc LIKE ANY(CAST(:" + n + "_like AS text[])))");
                    }
                    if (sinHom) {
                        o.add("NOT (" + HOMOLOGADO + " AND tm.fin_atc IS NOT NULL) OR tm.estado IS NULL");
                    }
                }
                case PRINCIPIO -> {
                    if (arr.length > 0) {
                        o.add("(" + HOMOLOGADO + " AND tm.fin_principio_activo = ANY(CAST(:" + n + " AS text[])))");
                    }
                    if (sinHom) {
                        o.add("NOT (" + HOMOLOGADO + " AND tm.fin_principio_activo IS NOT NULL) OR tm.estado IS NULL");
                    }
                }
                case MEDICAMENTO -> {
                    if (arr.length > 0) {
                        o.add("(" + HOMOLOGADO + " AND coalesce(tm.fin_cum, 'ORIGEN-' || x.medicamento_id) = ANY(CAST(:" + n + " AS text[])))");
                    }
                    if (sinHom) {
                        o.add("tm.estado IS DISTINCT FROM 'homologado'");
                    }
                }
                case SSFM -> {
                    if (arr.length > 0) {
                        o.add("(" + HOMOLOGADO + " AND tm.fin_codigo_ssfm = ANY(CAST(:" + n + " AS text[])))");
                    }
                    if (sinHom || sinDato) {
                        o.add("NOT (" + HOMOLOGADO + " AND tm.fin_codigo_ssfm IS NOT NULL) OR tm.estado IS NULL");
                    }
                }
                case CIE10 -> {
                    // El código FINAL homologado, como la dimensión: la fila «J45» de un tablero y el filtro cie10=J45 son el mismo conjunto
                    if (arr.length > 0) {
                        p.put(n + "_like", normales.stream().map(v -> v + "%").toArray(String[]::new));
                        o.add("(x.cie10 IS NOT NULL AND " + CIE_HOMOLOGADO + " AND tc.codigo_final LIKE ANY(CAST(:" + n + "_like AS text[])))");
                    }
                    if (sinDato) {
                        o.add("x.cie10 IS NULL");
                    }
                    if (sinHom) {
                        o.add("(x.cie10 IS NOT NULL AND tc.estado IS DISTINCT FROM 'homologado')");
                    }
                }
                case ESTADO -> o.add("coalesce(tm.estado, 'pendiente') = ANY(CAST(:" + n + " AS text[]))");
            }
            p.put(n, arr);
            if (!o.isEmpty()) {
                w.add(o.size() == 1 ? "(" + o.getFirst() + ")" : "(" + String.join(" OR ", o.stream().map(x -> "(" + x + ")").toList()) + ")");
            }
        }
        return w;
    }

    /**
     * SQL de UN conjunto de agrupación sobre la tabla de los tableros. Con {@code pacientes}, en dos niveles: (claves, paciente) por hash y
     * luego las filas por clave (pacientes distintos exactos de cada celda, nunca sumados entre celdas); sin pacientes, un solo nivel.
     */
    static Armado agrupar(Ambito a, Conjunto c, int indice, boolean pacientes) {
        LinkedHashSet<Dimension> dims = new LinkedHashSet<>(c.dimensiones());
        Map<String, Object> p = new LinkedHashMap<>();
        String from = desde(a, dims, c.extras(), TABLERO, p);
        List<String> claves = new ArrayList<>();
        List<String> exprs = new ArrayList<>();
        for (int i = 0; i < c.dimensiones().size(); i++) {
            String e = expresion(c.dimensiones().get(i), a.granularidad());
            exprs.add(e);
            claves.add(e + " AS k_" + i);
        }
        StringBuilder sql = new StringBuilder(cte(dims, a.filtros(), c.extras()));
        if (!pacientes) {
            List<String> sel = new ArrayList<>(claves);
            for (Extra e : c.extras()) {
                sel.add((e.varias ? "CASE WHEN min(" + e.columna + ") <> max(" + e.columna + ") THEN 'Varias' ELSE min(" + e.columna + ") END"
                        : "min(" + e.columna + ")") + " AS e_" + e.ordinal());
            }
            sel.add("sum(x.valor_centavos) AS gasto, sum(x.cantidad_milesimas) AS unidades, count(*) AS dispensaciones");
            sql.append("SELECT ").append(String.join(", ", sel)).append(from);
            if (!exprs.isEmpty()) {
                sql.append(" GROUP BY ").append(String.join(", ", exprs));
            }
            return new Armado(sql.toString(), p, c, indice, false);
        }
        List<String> interno = new ArrayList<>(claves);
        for (Extra e : c.extras()) {
            interno.add("min(" + e.columna + ") AS mn_" + e.ordinal());
            interno.add("max(" + e.columna + ") AS mx_" + e.ordinal());
        }
        interno.add("x.paciente_id, sum(x.valor_centavos) AS g, sum(x.cantidad_milesimas) AS u, count(*) AS n");
        List<String> externo = new ArrayList<>();
        List<String> ks = new ArrayList<>();
        for (int i = 0; i < c.dimensiones().size(); i++) {
            externo.add("k_" + i);
            ks.add("k_" + i);
        }
        for (Extra e : c.extras()) {
            int o = e.ordinal();
            externo.add((e.varias ? "CASE WHEN min(mn_" + o + ") <> max(mx_" + o + ") THEN 'Varias' ELSE min(mn_" + o + ") END" : "min(mn_" + o + ")")
                    + " AS e_" + o);
        }
        externo.add("count(*) AS pacientes, sum(g) AS gasto, sum(u) AS unidades, coalesce(sum(n), 0) AS dispensaciones");
        List<String> grupoInterno = new ArrayList<>(exprs);
        grupoInterno.add("x.paciente_id");
        sql.append("SELECT ").append(String.join(", ", externo)).append(" FROM (SELECT ").append(String.join(", ", interno)).append(from)
                .append(" GROUP BY ").append(String.join(", ", grupoInterno)).append(") s");
        if (!ks.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", ks));
        }
        return new Armado(sql.toString(), p, c, indice, true);
    }

    /**
     * Varios conjuntos de UNA dimensión (y el total) en una sola sentencia con un solo recorrido de la tabla: primero una base con
     * (claves de todas las dimensiones, paciente) y sus sumas, y de ella cada conjunto en dos niveles. Conviene cuando las dimensiones
     * dependen unas de otras (territorio: el ESM fija Fuerza, DISAN, regional, departamento y municipio), porque la base queda del tamaño
     * de los pares ESM × paciente y cada conjunto se cuenta sobre ella y no sobre todos los hechos. Exacto igual que {@link #agrupar}: la
     * base agrupa por todas las claves, no supone ninguna dependencia. Devuelve los grupos con el índice de su conjunto (0 = total).
     */
    static Armado agruparEnBase(Ambito a, List<Dimension> dims, boolean distribucion) {
        LinkedHashSet<Dimension> todas = new LinkedHashSet<>(dims);
        Map<String, Object> p = new LinkedHashMap<>();
        // La base no lleva las uniones a puntos y municipios (se hacen sobre ella, que es pequeña)
        LinkedHashSet<Dimension> sinPunto = new LinkedHashSet<>(todas);
        sinPunto.removeAll(Set.of(Dimension.DEPARTAMENTO, Dimension.MUNICIPIO));
        String from = desde(a, sinPunto, List.of(), TABLERO, p);
        // Claves de la base: las columnas crudas de la tabla (enteros y códigos: el agrupamiento por hash es más liviano) y, para el periodo
        // y lo que sale de la tabla maestra o la CIE-10, la expresión ya calculada (b_i)
        LinkedHashSet<String> crudas = new LinkedHashSet<>();
        List<String> claves = new ArrayList<>();
        List<String> grupo = new ArrayList<>();
        for (int i = 0; i < dims.size(); i++) {
            String c = columnaCruda(dims.get(i));
            if (c == null) {
                String e = expresion(dims.get(i), a.granularidad());
                claves.add(e + " AS b_" + i);
                grupo.add(e);
            } else if (crudas.add(c)) {
                claves.add("x." + c);
                grupo.add("x." + c);
            }
        }
        String cte = cte(todas, a.filtros(), List.of());
        StringBuilder sql = new StringBuilder(cte.isEmpty() ? "WITH " : cte.substring(0, cte.length() - 1) + ", ");
        sql.append("base AS MATERIALIZED (SELECT ").append(String.join(", ", claves))
                .append(", x.paciente_id, sum(x.valor_centavos) AS g, sum(x.cantidad_milesimas) AS u, count(*) AS n").append(from)
                .append(" GROUP BY ").append(String.join(", ", grupo)).append(", x.paciente_id) ");
        String medidas = "count(*) AS pacientes, sum(g) AS gasto, sum(u) AS unidades, coalesce(sum(n), 0) AS dispensaciones";
        // Con distribución, el total lleva además los cuartiles del gasto por paciente (en centavos): el mismo agrupamiento por paciente
        String pct = distribucion ? ", min(g) AS d_0, percentile_cont(0.25) WITHIN GROUP (ORDER BY g) AS d_1, percentile_cont(0.5) WITHIN GROUP "
                + "(ORDER BY g) AS d_2, percentile_cont(0.75) WITHIN GROUP (ORDER BY g) AS d_3, max(g) AS d_4" : "";
        String sinPct = distribucion ? ", NULL, NULL, NULL, NULL, NULL" : "";
        sql.append("SELECT 0 AS conjunto, CAST(NULL AS text) AS k_0, ").append(medidas).append(pct)
                .append(" FROM (SELECT paciente_id, sum(g) AS g, sum(u) AS u, sum(n) AS n FROM base GROUP BY paciente_id) s");
        for (int i = 0; i < dims.size(); i++) {
            Dimension d = dims.get(i);
            String k = columnaCruda(d) == null ? "x.b_" + i : expresion(d, a.granularidad());
            String punto = usaPunto(Set.of(d)) ? " LEFT JOIN puntos p ON p.id = x.punto_id LEFT JOIN par_municipios mu ON mu.codigo = p.municipio" : "";
            sql.append(" UNION ALL SELECT ").append(i + 1).append(", k, ").append(medidas).append(sinPct).append(" FROM (SELECT ").append(k)
                    .append(" AS k, x.paciente_id, sum(x.g) AS g, sum(x.u) AS u, sum(x.n) AS n FROM base x").append(punto).append(" GROUP BY ").append(k)
                    .append(", x.paciente_id) s GROUP BY k");
        }
        return new Armado(sql.toString(), p, new Conjunto(dims, List.of()), -1, true);
    }

    /** Columna de la tabla de los tableros de la que sale una dimensión sin la tabla maestra (nula: se calcula la expresión en la base). */
    private static String columnaCruda(Dimension d) {
        return switch (d) {
            case FUERZA -> "fuerza";
            case DISAN -> "disan";
            case REGIONAL -> "regional_id";
            case ESM -> "esm_codigo";
            case MODALIDAD -> "modalidad";
            case DEPARTAMENTO, MUNICIPIO -> "punto_id";
            case CUM -> "cum";
            case TIPO_FORMULA -> "tipo_formula";
            case ESPECIALIDAD -> "especialidad";
            default -> null;
        };
    }

    /** Lee una fila de {@link #agruparEnBase}: conjunto 0 = total; i ≥ 1 = la dimensión i − 1. */
    static Grupo leerEnBase(ResultSet rs, List<Dimension> dims) throws SQLException {
        int c = rs.getInt("conjunto");
        Map<Dimension, String> claves = new EnumMap<>(Dimension.class);
        if (c > 0) {
            claves.put(dims.get(c - 1), rs.getString("k_0"));
        }
        BigDecimal gasto = rs.getBigDecimal("gasto");
        BigDecimal unidades = rs.getBigDecimal("unidades");
        return new Grupo(c, claves, Map.of(), gasto == null ? new BigDecimal("0.00") : gasto.movePointLeft(2),
                unidades == null ? new BigDecimal("0.000") : unidades.movePointLeft(3), rs.getLong("dispensaciones"), rs.getLong("pacientes"), null);
    }

    /** Fórmulas distintas del ámbito (hash sobre el número de fórmula; solo si el insumo la trae). */
    static String formulas(Ambito a, Map<String, Object> p) {
        String from = desde(a, Set.of(), List.of(), TABLERO, p);
        return cte(Set.of(), a.filtros(), List.of()) + "SELECT count(*) FROM (SELECT x.numero_formula" + from
                + " AND x.numero_formula IS NOT NULL GROUP BY x.numero_formula) s";
    }

    /** WITH de la tabla maestra y la CIE-10 vigentes al corte, solo si la consulta las usa. */
    static String cte(Set<Dimension> dims, Map<Variable, List<String>> f, List<Extra> extras) {
        List<String> c = new ArrayList<>();
        if (usaTm(dims, f, extras)) {
            c.add(CTE_TM);
        }
        if (usaTc(dims, f)) {
            c.add(CTE_TC);
        }
        return c.isEmpty() ? "" : "WITH " + String.join(", ", c) + " ";
    }

    /** FROM, uniones y WHERE comunes (cargues visibles, periodo, alcance y filtros) sobre {@code tabla}. Deja en {@code p} los parámetros. */
    static String desde(Ambito a, Set<Dimension> dims, List<Extra> extras, String tabla, Map<String, Object> p) {
        StringBuilder s = new StringBuilder(" FROM " + tabla + " x");
        if (usaTm(dims, a.filtros(), extras)) {
            s.append(" LEFT JOIN tm ON tm.origen_id = x.medicamento_id");
            p.put("hv", a.corte().homVersion());
        }
        if (usaTc(dims, a.filtros())) {
            s.append(" LEFT JOIN tc ON tc.codigo_original = x.cie10");
            p.put("hv", a.corte().homVersion());
        }
        if (usaPunto(dims)) {
            s.append(" LEFT JOIN puntos p ON p.id = x.punto_id LEFT JOIN par_municipios mu ON mu.codigo = p.municipio");
        }
        p.put("cargues", a.corte().cargues().toArray(Long[]::new));
        List<String> w = new ArrayList<>();
        w.add(VISIBLES);
        p.put("desde", a.desde());
        p.put("hasta", a.hasta());
        w.add("x.fecha BETWEEN :desde AND :hasta");
        w.add(alcance(a.alcance(), p));
        w.addAll(filtros(a.filtros(), p));
        w.addAll(cohortes(a, tabla, p));
        s.append(" WHERE ").append(String.join(" AND ", w));
        return s.toString();
    }

    /**
     * Predicados de cohorte, GCF y GRC (A7.1, AN.22): el hecho entra si su paciente es miembro de la cohorte (o está en el grupo pedido de
     * la partición) EN EL MES del hecho, con la membresía del MISMO corte (reproducible por corte). Semijoin por (mes, paciente) que el
     * planificador resuelve por hash sobre los miembros de la versión y el corte (rango de la llave primaria de ana_cohortes_miembros); en
     * los hechos completos de la trazabilidad, por el seudónimo a través de ana_pacientes. Solo restringe filas: nunca devuelve pacientes.
     */
    static List<String> cohortes(Ambito a, String tabla, Map<String, Object> p) {
        List<String> w = new ArrayList<>();
        for (Variable v : a.filtros().keySet()) {
            if (!v.deCohorte()) {
                continue;
            }
            FiltroCohorte f = a.cohortes().get(v);
            if (f == null || f.cohortes().isEmpty()) {
                throw new IllegalStateException("El filtro " + v.parametro() + " no se resolvió contra el corte");
            }
            String n = "fc_" + v.parametro();
            p.put(n + "_v", f.cohortes().stream().map(CohorteResuelta::versionId).toArray(Long[]::new));
            p.put("fc_corte", a.corte().cargueId());
            StringBuilder s = new StringBuilder("EXISTS (SELECT 1 FROM ana_cohortes_miembros m");
            if (HECHOS.equals(tabla)) {
                s.append(" JOIN ana_pacientes pa ON pa.id = m.paciente_id");
            }
            s.append(" WHERE m.version_id = ANY(CAST(:").append(n).append("_v AS bigint[])) AND m.corte_cargue_id = :fc_corte");
            if (v.particion() != null) {
                p.put(n + "_g", f.grupos().toArray(String[]::new));
                s.append(" AND m.grupo = ANY(CAST(:").append(n).append("_g AS text[]))");
            }
            s.append(" AND m.mes = CAST(date_trunc('month', x.fecha) AS date)");
            s.append(HECHOS.equals(tabla) ? " AND pa.seudo = x.paciente_seudo COLLATE \"C\")" : " AND m.paciente_id = x.paciente_id)");
            w.add(s.toString());
        }
        return w;
    }
}
