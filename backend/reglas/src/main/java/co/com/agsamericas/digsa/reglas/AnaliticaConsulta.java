package co.com.agsamericas.digsa.reglas;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Capa semántica de la analítica farmacéutica (docs/especificaciones/analitica-tableros): la lista blanca de tableros, métricas,
 * dimensiones y variables de filtro, la consulta estructurada {@link ConsultaAnalitica} que comparten los tableros, las descargas, la
 * trazabilidad y el asistente de IA (A6), y las reglas puras de los resultados: etiquetas de periodo del contrato de reportes, periodos de
 * comparación, variación, participación, percentiles (como {@code percentile_cont}), Top 20 más «Resto», rangos de poliformulación,
 * huella canónica y formato del Anexo 5 2.4. Sin Spring ni JDBC.
 *
 * <p><b>Consulta estructurada (para el asistente, A6).</b> El modelo nunca escribe SQL: produce un objeto {@link ConsultaAnalitica} con la
 * intención ({@link Intencion}), el tablero o las métricas y dimensiones de la lista blanca, los filtros por variable (valores de los
 * catálogos; ATC y CIE-10 por prefijo), el periodo con su granularidad, la comparación, el nivel y la fecha de corte (id del cargue; nulo =
 * último aplicado). {@link ConsultaAnalitica#problemas()} lo valida sin tocar la base; el servicio de consulta resuelve después cada valor
 * de filtro contra los catálogos dentro del alcance del usuario (inexistente o fuera del alcance: 422 con el mismo mensaje) y ejecuta el
 * SQL parametrizado que arma desde esta lista blanca.
 *
 * <p>Ejemplo («gasto y pacientes por mes del ATC L04 en el Ejército en 2025, frente al año anterior»), tal como lo recibe
 * {@code POST /api/analitica/consulta} (enumeraciones por su nombre; filtros por variable; fechas ISO; corte nulo = último cargue):
 * <pre>
 * {"intencion": "METRICAS", "metricas": ["GASTO", "PACIENTES"], "dimensiones": ["PERIODO"],
 *  "filtros": {"FUERZA": ["EJC"], "ATC": ["L04"]}, "desde": "2025-01-01", "hasta": "2025-12-31",
 *  "granularidad": "MES", "comparacion": "ANIO_ANTERIOR", "corte": null}
 * </pre>
 * La respuesta es un resultado de tablero (KPI del total, una tabla por las dimensiones, metadatos de 14.8 con el corte, la fuente y los
 * registros procesados, y la huella SHA-256): las mismas cifras que el tablero con los mismos filtros.
 */
public final class AnaliticaConsulta {

    private AnaliticaConsulta() {
    }

    /** Valor especial de filtro y de fila: registros cuyo dato de la tabla maestra (o CIE-10) no está homologado. */
    public static final String SIN_HOMOLOGAR = "(sin homologar)";
    /** Valor especial de filtro y de fila: registros sin el dato (sin CIE-10, sin CUM, sin regional). */
    public static final String SIN_DATO = "(sin dato)";
    /** Máximo de valores por variable de filtro y de dimensiones por consulta. */
    public static final int MAX_VALORES = 50;
    public static final int MAX_DIMENSIONES = 3;
    /** Filas del ranking (A4 11.1.1 «ranking Top 20»). */
    public static final int TOP = 20;
    /** Umbral de poliformulado por defecto: ≥ 5 principios activos distintos en el mes (analitica-cohortes-alertas, duda 2). */
    public static final int POLIFORMULADO_MINIMO = 5;
    /** Percentil de alto costo por defecto: p99 del gasto por paciente en 12 meses móviles (analitica-cohortes-alertas, duda 1). */
    public static final int PERCENTIL_ALTO_COSTO = 99;

    // ---------- Lista blanca ----------

    /** Los 12 tableros de la lista mínima del A5 4.7.1, en el orden del mockup. Los tres últimos llegan con A4 y A5. */
    public enum Tablero {
        GENERAL("general", "General de dispensación", true),
        GASTO("gasto", "Gasto farmacéutico", true),
        ATC("atc", "Clasificación ATC", true),
        PRINCIPIO("principio", "Principio activo", true),
        MEDICAMENTO("medicamento", "Medicamento", true),
        DIAGNOSTICO("diagnostico", "Diagnóstico CIE-10", true),
        TERRITORIAL("territorial", "Territorial y organizacional", true),
        ALTO_COSTO("alto-costo", "Alto costo", true),
        POLIFORMULACION("poliformulacion", "Poliformulación", true),
        COHORTES("cohortes", "Cohortes y riesgo", false),
        ATIPICOS("atipicos", "Comportamientos atípicos", false),
        PREDICTIVO("predictivo", "Predictivo", false);

        private final String codigo;
        private final String titulo;
        private final boolean disponible;

        Tablero(String codigo, String titulo, boolean disponible) {
            this.codigo = codigo;
            this.titulo = titulo;
            this.disponible = disponible;
        }

        public String codigo() {
            return codigo;
        }

        public String titulo() {
            return titulo;
        }

        /** Falso en cohortes, atípicos y predictivo: muestran «Disponible en la siguiente fase» con la misma barra de filtros. */
        public boolean disponible() {
            return disponible;
        }

        public static Optional<Tablero> deCodigo(String codigo) {
            for (Tablero t : values()) {
                if (t.codigo.equals(codigo)) {
                    return Optional.of(t);
                }
            }
            return Optional.empty();
        }
    }

    /** Mensaje de los tableros que llegan en la siguiente fase. */
    public static final String SIGUIENTE_FASE = "Disponible en la siguiente fase";

    /** Tipo de un valor: cómo se formatea (2.4) y cómo se exporta. */
    public enum Tipo {
        TEXTO, MONEDA, ENTERO, UNIDADES, DECIMAL, PORCENTAJE
    }

    /** Métricas de la capa semántica. Gasto, unidades y dispensaciones se suman; pacientes es count(distinct) y nunca se suma entre celdas. */
    public enum Metrica {
        GASTO("gasto", "Gasto", Tipo.MONEDA),
        UNIDADES("unidades", "Unidades", Tipo.UNIDADES),
        DISPENSACIONES("dispensaciones", "Dispensaciones", Tipo.ENTERO),
        PACIENTES("pacientes", "Pacientes distintos", Tipo.ENTERO),
        PROMEDIO_DISPENSACION("promedioDispensacion", "Gasto promedio por dispensación", Tipo.MONEDA),
        PROMEDIO_PACIENTE("promedioPaciente", "Gasto promedio por paciente", Tipo.MONEDA),
        PARTICIPACION("participacion", "Participación en el gasto", Tipo.PORCENTAJE),
        VARIACION_ABSOLUTA("variacion", "Variación absoluta", Tipo.MONEDA),
        VARIACION_PORCENTUAL("variacionPct", "Variación %", Tipo.PORCENTAJE);

        private final String codigo;
        private final String etiqueta;
        private final Tipo tipo;

        Metrica(String codigo, String etiqueta, Tipo tipo) {
            this.codigo = codigo;
            this.etiqueta = etiqueta;
            this.tipo = tipo;
        }

        public String codigo() {
            return codigo;
        }

        public String etiqueta() {
            return etiqueta;
        }

        public Tipo tipo() {
            return tipo;
        }

        /** Las que se pueden ordenar en un ranking (alternar el Top 20). */
        public boolean ordenable() {
            return this == GASTO || this == UNIDADES || this == DISPENSACIONES || this == PACIENTES;
        }

        public static Optional<Metrica> deCodigo(String codigo) {
            for (Metrica m : values()) {
                if (m.codigo.equals(codigo)) {
                    return Optional.of(m);
                }
            }
            return Optional.empty();
        }
    }

    /** Dimensiones de agrupación. Las de la tabla maestra (ATC, principio, medicamento) llevan los no homologados a «Sin homologar». */
    public enum Dimension {
        PERIODO("periodo", "Periodo", null),
        FUERZA("fuerza", "Fuerza", Variable.FUERZA),
        DISAN("disan", "DISAN", Variable.DISAN),
        REGIONAL("regional", "Regional", Variable.REGIONAL),
        ESM("esm", "ESM", Variable.ESM),
        DEPARTAMENTO("departamento", "Departamento del ESM", null),
        MUNICIPIO("municipio", "Municipio del ESM", null),
        MODALIDAD("modalidad", "Modalidad", Variable.MODALIDAD),
        ATC1("atc1", "ATC nivel 1", Variable.ATC),
        ATC2("atc2", "ATC nivel 2", Variable.ATC),
        ATC3("atc3", "ATC nivel 3", Variable.ATC),
        ATC4("atc4", "ATC nivel 4", Variable.ATC),
        ATC5("atc5", "ATC nivel 5", Variable.ATC),
        PRINCIPIO_ACTIVO("principio", "Principio activo", Variable.PRINCIPIO),
        MEDICAMENTO("medicamento", "Medicamento", Variable.MEDICAMENTO),
        CUM("cum", "CUM", Variable.CUM),
        CIE10("cie10", "CIE-10", Variable.CIE10),
        CIE10_GRUPO("cie10Grupo", "Grupo CIE-10", Variable.CIE10),
        ESTADO_HOMOLOGACION("estado", "Estado de homologación", Variable.ESTADO),
        /** Campos opcionales del insumo (solo en los hechos): sin ellos, «No disponible en el insumo». */
        TIPO_FORMULA("tipoFormula", "Tipo de fórmula", null),
        ESPECIALIDAD("especialidad", "Especialidad", null);

        private final String codigo;
        private final String etiqueta;
        private final Variable variable;

        Dimension(String codigo, String etiqueta, Variable variable) {
            this.codigo = codigo;
            this.etiqueta = etiqueta;
            this.variable = variable;
        }

        public String codigo() {
            return codigo;
        }

        public String etiqueta() {
            return etiqueta;
        }

        /** Variable de filtro con que se rastrea una fila de esta dimensión (nula: departamento y municipio, que no son filtro). */
        public Variable variable() {
            return variable;
        }

        /** Sale de la tabla maestra homologada (versión vigente al corte). */
        public boolean deTablaMaestra() {
            return switch (this) {
                case ATC1, ATC2, ATC3, ATC4, ATC5, PRINCIPIO_ACTIVO, MEDICAMENTO, ESTADO_HOMOLOGACION -> true;
                default -> false;
            };
        }

        /** Solo está en los hechos (no en los agregados mensuales). */
        public boolean soloHechos() {
            return this == TIPO_FORMULA || this == ESPECIALIDAD;
        }

        /** Largo del código ATC de un nivel (1, 3, 4, 5 y 7 caracteres). */
        public int largoAtc() {
            return switch (this) {
                case ATC1 -> 1;
                case ATC2 -> 3;
                case ATC3 -> 4;
                case ATC4 -> 5;
                case ATC5 -> 7;
                default -> throw new IllegalStateException("No es un nivel ATC: " + this);
            };
        }

        public static Dimension atc(int nivel) {
            return switch (nivel) {
                case 1 -> ATC1;
                case 2 -> ATC2;
                case 3 -> ATC3;
                case 4 -> ATC4;
                case 5 -> ATC5;
                default -> throw new IllegalArgumentException("Nivel ATC de 1 a 5");
            };
        }

        public static Optional<Dimension> deCodigo(String codigo) {
            for (Dimension d : values()) {
                if (d.codigo.equals(codigo)) {
                    return Optional.of(d);
                }
            }
            return Optional.empty();
        }
    }

    /** Variables de filtro (A5 4.7.1, 14.5 y A4): selección múltiple; entre variables Y, dentro de una variable O. */
    public enum Variable {
        FUERZA("fuerza", "Fuerza", Pattern.compile("^[A-Z]{2,5}$"), false),
        DISAN("disan", "DISAN", Pattern.compile("^[\\p{L}\\p{N} .,()-]{1,80}$"), false),
        REGIONAL("regional", "Regional", Pattern.compile("^\\d{1,12}$"), false),
        ESM("esm", "ESM", Pattern.compile("^[A-Z0-9][A-Z0-9_.-]{0,39}$"), false),
        MODALIDAD("modalidad", "Modalidad", Pattern.compile("^[\\p{L} ]{2,40}$"), false),
        ATC("atc", "ATC", Pattern.compile("^[A-Z](\\d{2}([A-Z]([A-Z](\\d{2})?)?)?)?$"), true),
        PRINCIPIO("principio", "Principio activo", Pattern.compile("^[\\p{L}\\p{N} ,./()+-]{1,200}$"), false),
        MEDICAMENTO("medicamento", "Medicamento", Pattern.compile("^\\d{1,9}-\\d{1,3}$|^[A-Z0-9-]{1,40}$"), false),
        CUM("cum", "CUM", Pattern.compile("^\\d{1,9}-\\d{1,3}$"), false),
        SSFM("ssfm", "Código SSFM", Pattern.compile("^[A-Za-z0-9_.-]{1,40}$"), false),
        CIE10("cie10", "CIE-10", Pattern.compile("^[A-Z](\\d(\\d[0-9A-Z]?)?)?$"), true),
        ESTADO("estado", "Estado de homologación", Pattern.compile("^(homologado|pendiente|sin_correspondencia|inconsistente|duplicado|no_valido)$"),
                false),
        // A7.1 (AN.22): cohorte, GCF y GRC restringen los hechos a los pacientes miembros EN EL MES del hecho, con la membresía del mismo
        // corte (ana_cohortes_miembros); la cohorte por su código, GCF y GRC por el grupo de la partición.
        COHORTE("cohorte", "Cohorte", Pattern.compile("^[a-z_]{3,40}$"), false),
        GCF("gcf", "GCF", Pattern.compile("^([A-Z][0-9A-Z]{0,6}|SIN_ATC|SIN_GASTO)$"), false),
        GRC("grc", "GRC", Pattern.compile("^GRC[1-4]$"), false);

        private final String parametro;
        private final String etiqueta;
        private final Pattern forma;
        private final boolean prefijo;

        Variable(String parametro, String etiqueta, Pattern forma, boolean prefijo) {
            this.parametro = parametro;
            this.etiqueta = etiqueta;
            this.forma = forma;
            this.prefijo = prefijo;
        }

        /** Nombre del parámetro repetido en la URL ({@code fuerza=EJC&fuerza=ARC}, contrato de reportes 2.11). */
        public String parametro() {
            return parametro;
        }

        public String etiqueta() {
            return etiqueta;
        }

        /** ATC y CIE-10 se filtran por prefijo (cualquier nivel, capítulo, grupo o código). */
        public boolean prefijo() {
            return prefijo;
        }

        /**
         * Cohorte, GCF o GRC (A7.1): se resuelven contra la membresía mensual del corte (no contra los valores de los cargues) y filtran
         * por semijoin con (paciente, mes del hecho).
         */
        public boolean deCohorte() {
            return this == COHORTE || this == GCF || this == GRC;
        }

        /** Código de la cohorte predefinida cuya partición da los grupos (gcf o grc); nulo en las demás variables. */
        public String particion() {
            return this == GCF ? "gcf" : this == GRC ? "grc" : null;
        }

        /** Admite los valores especiales «Sin homologar» o «sin dato». */
        public boolean admiteEspeciales() {
            return this == ATC || this == PRINCIPIO || this == MEDICAMENTO || this == SSFM || this == CIE10 || this == CUM
                    || this == REGIONAL || this == DISAN;
        }

        /** ¿El valor tiene la forma de la variable? (No dice si existe: eso lo resuelve el servicio contra los catálogos.) */
        public boolean formaValida(String valor) {
            if (valor == null || valor.isBlank()) {
                return false;
            }
            if (SIN_HOMOLOGAR.equals(valor) || SIN_DATO.equals(valor)) {
                return admiteEspeciales();
            }
            return forma.matcher(valor).matches();
        }

        public static Optional<Variable> deParametro(String parametro) {
            for (Variable v : values()) {
                if (v.parametro.equals(parametro)) {
                    return Optional.of(v);
                }
            }
            return Optional.empty();
        }
    }

    /** Mensaje único para un valor de filtro inexistente o fuera del alcance (contrato de reportes 2.11: el mismo en ambos casos). */
    public static String mensajeInexistente(Variable v, String valor) {
        return v.etiqueta() + " «" + valor + "» no existe o está fuera de su alcance.";
    }

    /** Granularidades del periodo (A5 2.11). La de horas solo si el insumo trae la hora: hoy no la trae («No disponible en el insumo»). */
    public enum Granularidad {
        HORA("hora", "Hora"), DIA("dia", "Día"), SEMANA("semana", "Semana"), MES("mes", "Mes"), BIMESTRE("bimestre", "Bimestre"),
        TRIMESTRE("trimestre", "Trimestre"), SEMESTRE("semestre", "Semestre"), ANIO("anio", "Año");

        private final String codigo;
        private final String etiqueta;

        Granularidad(String codigo, String etiqueta) {
            this.codigo = codigo;
            this.etiqueta = etiqueta;
        }

        public String codigo() {
            return codigo;
        }

        public String etiqueta() {
            return etiqueta;
        }

        /** Mes o más grueso: los agregados mensuales alcanzan. */
        public boolean mensualOMayor() {
            return ordinal() >= MES.ordinal();
        }

        public static Optional<Granularidad> deCodigo(String codigo) {
            for (Granularidad g : values()) {
                if (g.codigo.equals(codigo)) {
                    return Optional.of(g);
                }
            }
            return Optional.empty();
        }
    }

    /** Periodo de comparación: ninguno, el anterior de igual duración o el mismo del año anterior. */
    public enum Comparacion {
        NINGUNA("ninguna", "Sin comparación"), ANTERIOR("anterior", "Periodo anterior de igual duración"),
        ANIO_ANTERIOR("anio-anterior", "Mismo periodo del año anterior");

        private final String codigo;
        private final String etiqueta;

        Comparacion(String codigo, String etiqueta) {
            this.codigo = codigo;
            this.etiqueta = etiqueta;
        }

        public String codigo() {
            return codigo;
        }

        public String etiqueta() {
            return etiqueta;
        }

        public static Optional<Comparacion> deCodigo(String codigo) {
            for (Comparacion c : values()) {
                if (c.codigo.equals(codigo)) {
                    return Optional.of(c);
                }
            }
            return Optional.empty();
        }
    }

    /** Vistas gerencial nacional, por Fuerza y por ESM (A4 11.1 a, b, c). */
    public enum Nivel {
        NACIONAL("nacional", "Nacional"), FUERZA("fuerza", "Fuerza"), ESM("esm", "ESM");

        private final String codigo;
        private final String etiqueta;

        Nivel(String codigo, String etiqueta) {
            this.codigo = codigo;
            this.etiqueta = etiqueta;
        }

        public String codigo() {
            return codigo;
        }

        public String etiqueta() {
            return etiqueta;
        }

        public static Optional<Nivel> deCodigo(String codigo) {
            for (Nivel n : values()) {
                if (n.codigo.equals(codigo)) {
                    return Optional.of(n);
                }
            }
            return Optional.empty();
        }
    }

    /** Qué pide la consulta: un tablero completo, métricas por dimensiones (el asistente y la consulta guiada) o el rastreo de una cifra. */
    public enum Intencion {
        TABLERO, METRICAS, TRAZA
    }

    /**
     * Consulta estructurada y validable (la misma para tableros, descargas, trazabilidad y asistente).
     *
     * @param intencion    tablero, métricas por dimensiones o rastreo
     * @param tablero      tablero (intención TABLERO y TRAZA; en METRICAS, opcional)
     * @param metricas     métricas pedidas (intención METRICAS; en TABLERO las fija el tablero)
     * @param dimensiones  dimensiones de agrupación (intención METRICAS, máximo 3)
     * @param filtros      valores por variable (ATC y CIE-10 por prefijo; vacío = todas)
     * @param desde        inicio del periodo (nulo = desde el primer día con datos visibles al corte)
     * @param hasta        fin del periodo (nulo = hasta el último día con datos visibles al corte)
     * @param granularidad granularidad de las series (por defecto mes)
     * @param comparacion  periodo de comparación (por defecto ninguno)
     * @param corte        id del cargue aplicado que fija la fecha de corte (nulo = último aplicado)
     * @param nivel        vista nacional, por Fuerza o por ESM (nulo = la que da el alcance)
     * @param nivelAtc     nivel ATC del tablero de clasificación ATC (1 a 5; por defecto 1)
     * @param orden        métrica del ranking Top 20 (gasto por defecto)
     */
    public record ConsultaAnalitica(Intencion intencion, Tablero tablero, List<Metrica> metricas, List<Dimension> dimensiones,
                                    Map<Variable, List<String>> filtros, LocalDate desde, LocalDate hasta, Granularidad granularidad,
                                    Comparacion comparacion, Long corte, Nivel nivel, Integer nivelAtc, Metrica orden) {

        public ConsultaAnalitica {
            intencion = intencion == null ? Intencion.TABLERO : intencion;
            metricas = metricas == null ? List.of() : List.copyOf(new java.util.LinkedHashSet<>(metricas));
            dimensiones = dimensiones == null ? List.of() : List.copyOf(new java.util.LinkedHashSet<>(dimensiones));
            filtros = normalizar(filtros);
            granularidad = granularidad == null ? Granularidad.MES : granularidad;
            comparacion = comparacion == null ? Comparacion.NINGUNA : comparacion;
            nivelAtc = nivelAtc == null ? 1 : nivelAtc;
            orden = orden == null ? Metrica.GASTO : orden;
        }

        /** Tablero con los valores por defecto. */
        public static ConsultaAnalitica tablero(Tablero t) {
            return new ConsultaAnalitica(Intencion.TABLERO, t, null, null, null, null, null, null, null, null, null, null, null);
        }

        public ConsultaAnalitica conFiltros(Map<Variable, List<String>> f) {
            return new ConsultaAnalitica(intencion, tablero, metricas, dimensiones, f, desde, hasta, granularidad, comparacion, corte, nivel,
                    nivelAtc, orden);
        }

        public ConsultaAnalitica conPeriodo(LocalDate d, LocalDate h) {
            return new ConsultaAnalitica(intencion, tablero, metricas, dimensiones, filtros, d, h, granularidad, comparacion, corte, nivel,
                    nivelAtc, orden);
        }

        public ConsultaAnalitica conCorte(Long c) {
            return new ConsultaAnalitica(intencion, tablero, metricas, dimensiones, filtros, desde, hasta, granularidad, comparacion, c, nivel,
                    nivelAtc, orden);
        }

        public ConsultaAnalitica conNivel(Nivel n) {
            return new ConsultaAnalitica(intencion, tablero, metricas, dimensiones, filtros, desde, hasta, granularidad, comparacion, corte, n,
                    nivelAtc, orden);
        }

        public ConsultaAnalitica conIntencion(Intencion i) {
            return new ConsultaAnalitica(i, tablero, metricas, dimensiones, filtros, desde, hasta, granularidad, comparacion, corte, nivel,
                    nivelAtc, orden);
        }

        /** Valores pedidos de una variable (vacío = todas). */
        public List<String> valores(Variable v) {
            return filtros.getOrDefault(v, List.of());
        }

        /**
         * Problemas de forma de la consulta (sin tocar la base). Vacío = válida. Los valores se validan por su forma; su existencia la
         * resuelve el servicio contra los catálogos dentro del alcance.
         */
        public List<String> problemas() {
            List<String> p = new ArrayList<>();
            if ((intencion == Intencion.TABLERO || intencion == Intencion.TRAZA) && tablero == null) {
                p.add("Indique el tablero.");
            }
            if (intencion == Intencion.METRICAS) {
                if (metricas.isEmpty()) {
                    p.add("Indique al menos una métrica.");
                }
                if (dimensiones.size() > MAX_DIMENSIONES) {
                    p.add("Máximo " + MAX_DIMENSIONES + " dimensiones por consulta.");
                }
            }
            if (desde != null && hasta != null && desde.isAfter(hasta)) {
                p.add("La fecha «Desde» es posterior a «Hasta».");
            }
            if (desde != null && hasta != null && desde.plusYears(30).isBefore(hasta)) {
                p.add("El periodo no puede superar 30 años.");
            }
            if (granularidad == Granularidad.HORA) {
                p.add("No disponible en el insumo: la granularidad por horas requiere la hora de dispensación.");
            }
            if (nivelAtc < 1 || nivelAtc > 5) {
                p.add("El nivel ATC va de 1 a 5.");
            }
            if (!orden.ordenable()) {
                p.add("El ranking se ordena por gasto, unidades, dispensaciones o pacientes.");
            }
            if (corte != null && corte < 1) {
                p.add("La fecha de corte no es válida.");
            }
            for (Map.Entry<Variable, List<String>> e : filtros.entrySet()) {
                if (e.getValue().size() > MAX_VALORES) {
                    p.add("Máximo " + MAX_VALORES + " valores en " + e.getKey().etiqueta() + ".");
                }
                for (String v : e.getValue()) {
                    if (!e.getKey().formaValida(v)) {
                        p.add(mensajeInexistente(e.getKey(), recortar(v, 60)));
                    }
                }
            }
            return p;
        }

        private static Map<Variable, List<String>> normalizar(Map<Variable, List<String>> f) {
            // Orden fijo (variable y valores ordenados y sin repetir): la huella y el SQL no dependen del orden en que llegaron
            EnumMap<Variable, List<String>> m = new EnumMap<>(Variable.class);
            if (f != null) {
                for (Map.Entry<Variable, List<String>> e : f.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) {
                        continue;
                    }
                    TreeSet<String> s = new TreeSet<>();
                    for (String v : e.getValue()) {
                        if (v != null && !v.isBlank()) {
                            s.add(normalizarValor(e.getKey(), v));
                        }
                    }
                    if (!s.isEmpty()) {
                        m.put(e.getKey(), List.copyOf(s));
                    }
                }
            }
            return java.util.Collections.unmodifiableMap(m);
        }
    }

    /** Valor de filtro normalizado: sin espacios alrededor; códigos (Fuerza, ESM, ATC, CIE-10) en mayúscula y CIE-10 sin punto. */
    public static String normalizarValor(Variable v, String valor) {
        String s = valor.strip();
        if (SIN_HOMOLOGAR.equals(s) || SIN_DATO.equals(s)) {
            return s;
        }
        return switch (v) {
            case FUERZA, ESM, ATC, MEDICAMENTO, GCF, GRC -> s.toUpperCase(Locale.ROOT);
            case COHORTE -> s.toLowerCase(Locale.ROOT);
            case CIE10 -> s.toUpperCase(Locale.ROOT).replace(".", "");
            default -> s;
        };
    }

    /**
     * Umbral k de la supresión de celdas pequeñas de una consulta (A7.1): el general de los tableros o, si el filtro incluye una cohorte
     * sensible (VIH, huérfanas) y el umbral de las sensibles está activo, el mayor de los dos (AN.24). 0 = desactivada.
     */
    public static int kConCohorte(int general, int sensibles, boolean conCohorteSensible) {
        int g = Math.max(0, general);
        return conCohorteSensible && sensibles > 0 ? Math.max(g, sensibles) : g;
    }

    private static String recortar(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ---------- Periodos ----------

    /** Etiqueta de periodo del contrato de reportes: {@code 2026-07-14}, {@code 2026-W29}, {@code 2026-07}, {@code 2026-B4}, … */
    public static String etiquetaPeriodo(LocalDate d, Granularidad g) {
        return switch (g) {
            case HORA, DIA -> d.toString();
            case SEMANA -> String.format(Locale.ROOT, "%d-W%02d", d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            case MES -> String.format(Locale.ROOT, "%d-%02d", d.getYear(), d.getMonthValue());
            case BIMESTRE -> d.getYear() + "-B" + ((d.getMonthValue() + 1) / 2);
            case TRIMESTRE -> d.getYear() + "-T" + ((d.getMonthValue() + 2) / 3);
            case SEMESTRE -> d.getYear() + "-S" + (d.getMonthValue() <= 6 ? 1 : 2);
            case ANIO -> Integer.toString(d.getYear());
        };
    }

    private static final Pattern P_DIA = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");
    private static final Pattern P_SEMANA = Pattern.compile("^(\\d{4})-W(\\d{2})$");
    private static final Pattern P_MES = Pattern.compile("^(\\d{4})-(\\d{2})$");
    private static final Pattern P_PARTE = Pattern.compile("^(\\d{4})-([BTS])(\\d)$");
    private static final Pattern P_ANIO = Pattern.compile("^(\\d{4})$");

    /** Primer y último día de un periodo por su etiqueta (para rastrear una cifra de la serie). Vacío si la etiqueta no es válida. */
    public static Optional<LocalDate[]> rangoPeriodo(String etiqueta) {
        if (etiqueta == null) {
            return Optional.empty();
        }
        try {
            java.util.regex.Matcher m;
            if ((m = P_DIA.matcher(etiqueta)).matches()) {
                LocalDate d = LocalDate.parse(etiqueta);
                return Optional.of(new LocalDate[] {d, d});
            }
            if ((m = P_SEMANA.matcher(etiqueta)).matches()) {
                int anio = Integer.parseInt(m.group(1));
                int semana = Integer.parseInt(m.group(2));
                LocalDate base = LocalDate.of(anio, 1, 4).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, 1).with(java.time.DayOfWeek.MONDAY);
                LocalDate d = base.plusWeeks(semana - 1L);
                if (d.get(IsoFields.WEEK_BASED_YEAR) != anio) {
                    return Optional.empty();
                }
                return Optional.of(new LocalDate[] {d, d.plusDays(6)});
            }
            if ((m = P_MES.matcher(etiqueta)).matches()) {
                LocalDate d = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), 1);
                return Optional.of(new LocalDate[] {d, d.with(TemporalAdjusters.lastDayOfMonth())});
            }
            if ((m = P_PARTE.matcher(etiqueta)).matches()) {
                int anio = Integer.parseInt(m.group(1));
                int n = Integer.parseInt(m.group(3));
                int meses = switch (m.group(2)) {
                    case "B" -> 2;
                    case "T" -> 3;
                    default -> 6;
                };
                if (n < 1 || n > 12 / meses) {
                    return Optional.empty();
                }
                LocalDate d = LocalDate.of(anio, (n - 1) * meses + 1, 1);
                return Optional.of(new LocalDate[] {d, d.plusMonths(meses).minusDays(1)});
            }
            if ((m = P_ANIO.matcher(etiqueta)).matches()) {
                int anio = Integer.parseInt(m.group(1));
                return Optional.of(new LocalDate[] {LocalDate.of(anio, 1, 1), LocalDate.of(anio, 12, 31)});
            }
        } catch (java.time.DateTimeException | NumberFormatException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** Etiquetas de todos los periodos entre dos fechas (inclusive), en orden: la serie se completa con ceros donde no hay datos. */
    public static List<String> periodos(LocalDate desde, LocalDate hasta, Granularidad g) {
        List<String> r = new ArrayList<>();
        if (desde == null || hasta == null || desde.isAfter(hasta)) {
            return r;
        }
        LocalDate d = desde;
        String ultima = null;
        while (!d.isAfter(hasta)) {
            String e = etiquetaPeriodo(d, g);
            if (!e.equals(ultima)) {
                r.add(e);
                ultima = e;
            }
            d = switch (g) {
                case HORA, DIA -> d.plusDays(1);
                case SEMANA -> d.plusWeeks(1).with(java.time.DayOfWeek.MONDAY);
                default -> d.plusMonths(1).withDayOfMonth(1);
            };
            if (r.size() > 20_000) {
                throw new IllegalArgumentException("Demasiados periodos: use una granularidad más gruesa.");
            }
        }
        // El último día puede abrir un periodo que el salto no visitó (semana o mes de «hasta»)
        String fin = etiquetaPeriodo(hasta, g);
        if (!r.isEmpty() && !r.getLast().equals(fin)) {
            r.add(fin);
        }
        return r;
    }

    /** ¿El rango cubre meses completos? (Entonces los agregados mensuales alcanzan para las sumas.) */
    public static boolean mesesCompletos(LocalDate desde, LocalDate hasta) {
        return desde != null && hasta != null && desde.getDayOfMonth() == 1 && hasta.equals(hasta.with(TemporalAdjusters.lastDayOfMonth()));
    }

    /** Periodo de comparación: el anterior de igual duración (en días) o el mismo del año anterior. Nulo sin comparación. */
    public static LocalDate[] periodoComparacion(LocalDate desde, LocalDate hasta, Comparacion c) {
        return switch (c) {
            case NINGUNA -> null;
            case ANIO_ANTERIOR -> new LocalDate[] {desde.minusYears(1), hasta.minusYears(1)};
            case ANTERIOR -> {
                long dias = java.time.temporal.ChronoUnit.DAYS.between(desde, hasta) + 1;
                yield new LocalDate[] {desde.minusDays(dias), desde.minusDays(1)};
            }
        };
    }

    // ---------- Cálculos ----------

    /** Variación absoluta y porcentual; con anterior 0 (o nulo) el porcentaje es nulo («—»), nunca infinito. */
    public record Variacion(BigDecimal absoluta, BigDecimal porcentaje) {
    }

    public static Variacion variacion(BigDecimal actual, BigDecimal anterior) {
        BigDecimal a = actual == null ? BigDecimal.ZERO : actual;
        BigDecimal b = anterior == null ? BigDecimal.ZERO : anterior;
        BigDecimal abs = a.subtract(b);
        BigDecimal pct = b.signum() == 0 ? null : abs.multiply(CIEN).divide(b, 2, RoundingMode.HALF_UP);
        return new Variacion(abs, pct);
    }

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    /** Participación de la parte en el total en %, con 2 decimales; con total 0, nula. */
    public static BigDecimal participacion(BigDecimal parte, BigDecimal total) {
        if (parte == null || total == null || total.signum() == 0) {
            return null;
        }
        return parte.multiply(CIEN).divide(total, 2, RoundingMode.HALF_UP);
    }

    /** Cociente con 2 decimales (promedios); con divisor 0, nulo. El redondeo se hace al final: las sumas llegan sin redondear. */
    public static BigDecimal promedio(BigDecimal suma, long n) {
        return n == 0 || suma == null ? null : suma.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
    }

    /** Percentil continuo con interpolación lineal (igual que {@code percentile_cont} de PostgreSQL) sobre valores ordenados. */
    public static BigDecimal percentil(List<BigDecimal> ordenados, double p) {
        if (ordenados.isEmpty()) {
            return null;
        }
        if (p < 0 || p > 1) {
            throw new IllegalArgumentException("Percentil entre 0 y 1");
        }
        double pos = p * (ordenados.size() - 1);
        int i = (int) Math.floor(pos);
        BigDecimal base = ordenados.get(i);
        if (i + 1 >= ordenados.size()) {
            return base;
        }
        BigDecimal frac = BigDecimal.valueOf(pos - i);
        return base.add(ordenados.get(i + 1).subtract(base).multiply(frac));
    }

    /**
     * Mínimo, P25, mediana, P75 y máximo a partir de una tabla de frecuencias {valor, veces} (sin repetir cada valor): el mismo percentil
     * continuo de {@code percentile_cont} sobre la lista expandida, con los cuartiles redondeados a 2 decimales (HALF_UP). Así el gasto por
     * dispensación de millones de registros se resume en SQL con un agrupamiento por hash de pocos valores, sin ordenar los millones.
     * Sin registros: cinco nulos.
     */
    public static BigDecimal[] cuartiles(List<BigDecimal[]> frecuencias) {
        List<BigDecimal[]> o = new ArrayList<>(frecuencias.stream().filter(f -> f[0] != null && f[1].signum() > 0).toList());
        o.sort(java.util.Comparator.comparing(f -> f[0]));
        long n = o.stream().mapToLong(f -> f[1].longValueExact()).sum();
        BigDecimal[] r = new BigDecimal[5];
        if (n == 0) {
            return r;
        }
        r[0] = o.getFirst()[0];
        r[4] = o.getLast()[0];
        String[] ps = {"0.25", "0.5", "0.75"};
        for (int k = 0; k < 3; k++) {
            BigDecimal pos = new BigDecimal(ps[k]).multiply(BigDecimal.valueOf(n - 1));
            long i = pos.setScale(0, RoundingMode.FLOOR).longValueExact();
            BigDecimal frac = pos.subtract(BigDecimal.valueOf(i));
            BigDecimal base = enRango(o, i);
            BigDecimal v = i + 1 >= n ? base : base.add(enRango(o, i + 1).subtract(base).multiply(frac));
            r[k + 1] = v.setScale(2, RoundingMode.HALF_UP);
        }
        return r;
    }

    /** Valor en la posición {@code i} (desde 0) de la lista expandida por frecuencias, ordenada. */
    private static BigDecimal enRango(List<BigDecimal[]> ordenadas, long i) {
        long acumulado = 0;
        for (BigDecimal[] f : ordenadas) {
            acumulado += f[1].longValueExact();
            if (i < acumulado) {
                return f[0];
            }
        }
        return ordenadas.getLast()[0];
    }

    /**
     * Periodo por defecto de un tablero sin fechas (revisión A3 2026-10-23): los últimos 12 meses hasta el último día con datos visibles
     * al corte (desde el primer día del mes, 11 meses antes del mes del último día), sin pasar del primer día con datos. La historia
     * completa es una elección explícita del usuario. Nulo si no hay datos.
     */
    public static LocalDate[] periodoPorDefecto(LocalDate datosDesde, LocalDate datosHasta) {
        if (datosDesde == null || datosHasta == null) {
            return null;
        }
        LocalDate d = datosHasta.withDayOfMonth(1).minusMonths(11);
        return new LocalDate[] {d.isBefore(datosDesde) ? datosDesde : d, datosHasta};
    }

    /** Mínimo, P25, mediana, P75, máximo y rango intercuartílico (A4 11.2.3 «medianas y rangos»). */
    public record Distribucion(BigDecimal minimo, BigDecimal p25, BigDecimal mediana, BigDecimal p75, BigDecimal maximo,
                               BigDecimal rangoIntercuartilico) {

        public static Distribucion de(Collection<BigDecimal> valores) {
            List<BigDecimal> o = valores.stream().sorted().toList();
            if (o.isEmpty()) {
                return new Distribucion(null, null, null, null, null, null);
            }
            BigDecimal p25 = percentil(o, 0.25);
            BigDecimal p75 = percentil(o, 0.75);
            return new Distribucion(o.getFirst(), p25, percentil(o, 0.5), p75, o.getLast(), p75.subtract(p25));
        }
    }

    /**
     * Top {@code n} más una fila «Resto» que agrega las demás (el ranking cuadra con el total). Las filas llegan ordenadas; si no sobran,
     * no hay «Resto».
     */
    public static <T> List<T> topMasResto(List<T> ordenadas, int n, Function<List<T>, T> resto) {
        if (ordenadas.size() <= n) {
            return List.copyOf(ordenadas);
        }
        List<T> r = new ArrayList<>(ordenadas.subList(0, n));
        r.add(resto.apply(ordenadas.subList(n, ordenadas.size())));
        return r;
    }

    /** Etiqueta de la fila «Resto» con cuántas agrupa. */
    public static String etiquetaResto(int cuantas) {
        return "Resto (" + cuantas + ")";
    }

    /** Orden del ranking: el valor de mayor a menor y, en empate, la clave ascendente (orden total y estable: la huella depende de él). */
    public static <T> Comparator<T> ordenRanking(Function<T, BigDecimal> valor, Function<T, String> clave) {
        Comparator<T> porValor = Comparator.comparing(valor, Comparator.nullsFirst(Comparator.naturalOrder()));
        return porValor.reversed().thenComparing(clave, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /** Rangos de la distribución de poliformulación (n.º de principios activos distintos del paciente en el mes; parametrizables). */
    public static final List<int[]> RANGOS_POLIFORMULACION = List.of(new int[] {1, 1}, new int[] {2, 4}, new int[] {5, 9},
            new int[] {10, Integer.MAX_VALUE});

    public static String etiquetaRango(int[] r) {
        if (r[1] == Integer.MAX_VALUE) {
            return "≥ " + r[0];
        }
        return r[0] == r[1] ? Integer.toString(r[0]) : r[0] + "–" + r[1];
    }

    /** Rango al que pertenece un n.º de principios activos (nulo si es menor que 1). */
    public static String rangoPoliformulacion(int principios) {
        for (int[] r : RANGOS_POLIFORMULACION) {
            if (principios >= r[0] && principios <= r[1]) {
                return etiquetaRango(r);
            }
        }
        return null;
    }

    /** Huella SHA-256 del resultado canónico (líneas en orden fijo; sin usuario ni hora). */
    public static String huella(List<String> lineasCanonicas) {
        return Huellas.sha256(String.join("\n", lineasCanonicas));
    }

    /** Texto canónico de un valor para la huella: decimales sin ceros de escala que dependan del origen. */
    public static String canonico(Object v) {
        return switch (v) {
            case null -> "";
            case BigDecimal b -> b.signum() == 0 ? "0" : b.stripTrailingZeros().toPlainString();
            default -> String.valueOf(v);
        };
    }

    // ---------- Formato del Anexo 5 2.4 ----------

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Dos decimales con redondeo HALF_UP, coma decimal y sin separador de miles. */
    public static String decimal(BigDecimal v) {
        return v == null ? "—" : v.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /** {@code $ 64310220400,00}. */
    public static String moneda(BigDecimal v) {
        return v == null ? "—" : "$ " + decimal(v);
    }

    /** {@code 6,20 %}. */
    public static String porcentaje(BigDecimal v) {
        return v == null ? "—" : decimal(v) + " %";
    }

    /** Conteo entero sin separador de miles ({@code 105218}). */
    public static String entero(long v) {
        return Long.toString(v);
    }

    /** Unidades: enteras sin decimales; con fracción, dos decimales. */
    public static String unidades(BigDecimal v) {
        if (v == null) {
            return "—";
        }
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() <= 0 ? s.toBigInteger().toString() : decimal(v);
    }

    /** Variación con signo: {@code +6,20 %} o {@code -$ 1500,00}. */
    public static String conSigno(String texto, BigDecimal v) {
        return v == null || v.signum() <= 0 ? texto : "+" + texto;
    }

    /** Texto de un valor según su tipo. */
    public static String formato(Object v, Tipo t) {
        if (v == null) {
            return "—";
        }
        return switch (t) {
            case TEXTO -> String.valueOf(v);
            case MONEDA -> moneda(aDecimal(v));
            case DECIMAL -> decimal(aDecimal(v));
            case PORCENTAJE -> porcentaje(aDecimal(v));
            case ENTERO -> v instanceof Number n ? entero(n.longValue()) : String.valueOf(v);
            case UNIDADES -> unidades(aDecimal(v));
        };
    }

    /** Valor para exportar (el formateador del Exportador pone la coma): moneda y porcentajes con escala 2; conteos como Long. */
    public static Object paraExportar(Object v, Tipo t) {
        if (v == null) {
            return null;
        }
        return switch (t) {
            case TEXTO -> String.valueOf(v);
            case MONEDA, DECIMAL, PORCENTAJE -> aDecimal(v).setScale(2, RoundingMode.HALF_UP);
            case ENTERO -> v instanceof Number n ? n.longValue() : v;
            case UNIDADES -> {
                BigDecimal s = aDecimal(v).stripTrailingZeros();
                yield s.scale() <= 0 ? (Object) s.toBigInteger().longValue() : aDecimal(v).setScale(2, RoundingMode.HALF_UP);
            }
        };
    }

    private static BigDecimal aDecimal(Object v) {
        return switch (v) {
            case BigDecimal b -> b;
            case Long l -> BigDecimal.valueOf(l);
            case Integer i -> BigDecimal.valueOf(i);
            case Number n -> new BigDecimal(n.toString());
            default -> new BigDecimal(String.valueOf(v));
        };
    }

    public static String fecha(LocalDate d) {
        return d == null ? "—" : d.format(FECHA);
    }

    /** Fecha y hora militar de Colombia. */
    public static String fechaHora(OffsetDateTime t) {
        return t == null ? "—" : t.atZoneSameInstant(Fechas.BOGOTA).format(FECHA_HORA);
    }

    // ---------- Campos opcionales del insumo y cobertura del A4 ----------

    /** Campos opcionales del insumo de los que dependen reportes del A4: sin ellos se muestra «No disponible en el insumo». */
    public enum CampoOpcional {
        PROFESIONAL("profesional", "profesional"), ESPECIALIDAD("especialidad", "especialidad"), TIPO_FORMULA("tipoFormula", "tipo de fórmula"),
        NUMERO_FORMULA("numeroFormula", "n.º de fórmula"), HOMIL("homil", "HOMIL");

        private final String clave;
        private final String etiqueta;

        CampoOpcional(String clave, String etiqueta) {
            this.clave = clave;
            this.etiqueta = etiqueta;
        }

        /** Clave del campo en el mapeo del cargue (AnaliticaIngesta.Campo); HOMIL aún no es un campo del insumo. */
        public String clave() {
            return clave;
        }

        public String etiqueta() {
            return etiqueta;
        }
    }

    /** «No disponible en el insumo: tipo de fórmula, profesional». */
    public static String noDisponible(Collection<CampoOpcional> faltan) {
        return "No disponible en el insumo: " + String.join(", ", faltan.stream().map(CampoOpcional::etiqueta).toList());
    }

    /** Reporte del A4 11.1.x: el tablero con filtros predefinidos que lo cubre y los campos opcionales de que depende. */
    public record ReporteA4(String codigo, String nombre, Tablero tablero, Map<String, String> parametros, List<CampoOpcional> dependeDe,
                            String nota) {

        /** 11.1.15 no se cubre aquí: va al módulo de reportes (duda 5). */
        public boolean cubierto() {
            return tablero != null;
        }
    }

    private static Map<String, String> p(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    /** Cobertura de los reportes 11.1.1 a 11.1.21 del Anexo 4 (A5 4.7.1; RESP ATI 3.11: mismos tableros para A4 y A5). */
    public static final List<ReporteA4> REPORTES_A4 = List.of(
            new ReporteA4("11.1.1", "Frecuencia, uso y gasto de medicamentos (Top 20, series y georreferenciación)", Tablero.MEDICAMENTO, p(),
                    List.of(), "Top 20, serie y gasto por departamento y municipio en el tablero territorial"),
            new ReporteA4("11.1.2", "Gasto promedio por fórmula", Tablero.GASTO, p(), List.of(CampoOpcional.NUMERO_FORMULA),
                    "Sin n.º de fórmula se muestra el promedio por dispensación"),
            new ReporteA4("11.1.3", "Gasto en el Hospital Militar (HOMIL)", Tablero.GASTO, p(), List.of(CampoOpcional.HOMIL), null),
            new ReporteA4("11.1.4", "Gasto promedio por afiliado, dispensación y suministro", Tablero.GASTO, p(), List.of(), "Por modalidad"),
            new ReporteA4("11.1.5", "Consumo por subgrupo terapéutico", Tablero.ATC, p("nivelAtc", "3", "orden", "unidades"), List.of(), null),
            new ReporteA4("11.1.6", "Consumo en el HOMIL", Tablero.ATC, p(), List.of(CampoOpcional.HOMIL), null),
            new ReporteA4("11.1.7", "Alto costo por afiliado", Tablero.ALTO_COSTO, p(), List.of(), "Cohorte de alto costo agregada"),
            new ReporteA4("11.1.8", "Alto costo por profesional", Tablero.ALTO_COSTO, p(), List.of(CampoOpcional.PROFESIONAL,
                    CampoOpcional.ESPECIALIDAD), "Por especialidad, ESM, regional y Fuerza"),
            new ReporteA4("11.1.9", "Medicamentos de mayor uso en el HOMIL", Tablero.MEDICAMENTO, p(), List.of(CampoOpcional.HOMIL), null),
            new ReporteA4("11.1.10", "Diagnósticos del HOMIL", Tablero.DIAGNOSTICO, p(), List.of(CampoOpcional.HOMIL), null),
            new ReporteA4("11.1.11", "Molécula por diagnóstico", Tablero.DIAGNOSTICO, p(), List.of(), "Matriz principio activo × CIE-10"),
            new ReporteA4("11.1.12", "Vitales no disponibles", Tablero.MEDICAMENTO, p(), List.of(),
                    "Filtrado por la referencia de vitales no disponibles; las causas de desabastecimiento no están en el insumo"),
            new ReporteA4("11.1.13", "Gasto por tipo de fórmula", Tablero.GASTO, p(), List.of(CampoOpcional.TIPO_FORMULA), null),
            new ReporteA4("11.1.14", "Alto costo en el HOMIL", Tablero.ALTO_COSTO, p(), List.of(CampoOpcional.HOMIL), null),
            new ReporteA4("11.1.15", "Indicadores del Anexo Técnico 1", null, p(), List.of(),
                    "No cubierto en analítica (módulo de reportes)"),
            new ReporteA4("11.1.16", "Alto costo consolidado", Tablero.ALTO_COSTO, p(), List.of(), "Profesionales solo si vienen en el insumo"),
            new ReporteA4("11.1.17", "Diagnóstico más costoso", Tablero.DIAGNOSTICO, p(), List.of(), "Top 20"),
            new ReporteA4("11.1.18", "Medicamento más costoso", Tablero.MEDICAMENTO, p(), List.of(), "Top 20"),
            new ReporteA4("11.1.19", "Gasto por profesional", Tablero.GASTO, p(), List.of(CampoOpcional.PROFESIONAL, CampoOpcional.ESPECIALIDAD),
                    "Por especialidad, ESM, regional y Fuerza"),
            new ReporteA4("11.1.20", "Profesional con más fórmulas manuales", Tablero.MEDICAMENTO, p(), List.of(CampoOpcional.TIPO_FORMULA,
                    CampoOpcional.PROFESIONAL), null),
            new ReporteA4("11.1.21", "Fórmulas manuales", Tablero.MEDICAMENTO, p(), List.of(CampoOpcional.TIPO_FORMULA), null));

    /** Campos opcionales que faltan para un reporte, dados los presentes en los cargues visibles. */
    public static List<CampoOpcional> faltantes(ReporteA4 r, Set<CampoOpcional> presentes) {
        return r.dependeDe().stream().filter(c -> !presentes.contains(c)).toList();
    }
}
