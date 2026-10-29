package co.com.agsamericas.digsa.reglas;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Alertas tempranas de la analítica farmacéutica (docs/especificaciones/analitica-cohortes-alertas; A4 §11.1 d, A5 14.7 y tablero 11,
 * PLIEGO P4): los doce tipos con su regla por defecto, la parametrización (umbrales de las tres bandas de severidad, ventana, niveles,
 * exclusiones y plantilla de recomendación) con su validación, los detectores deterministas sobre series y conteos ya agregados o sobre
 * la secuencia de dispensaciones de un paciente, la severidad (la banda más alta alcanzada), el análisis técnico (causa, valoración y
 * recomendación, P4) y la llave natural que evita duplicar una alerta al reejecutar. La IA no decide si hay alerta. Sin Spring ni JDBC.
 */
public final class AnaliticaAlertas {

    private AnaliticaAlertas() {
    }

    /** Niveles de evaluación. */
    public static final List<String> NIVELES = List.of("nacional", "fuerza", "esm");
    /** Tope de cualquier umbral o parámetro numérico (evita desbordes al pasar a centavos o milésimas). */
    public static final BigDecimal TOPE = new BigDecimal("1000000000000");
    /** Nota mínima para validar o descartar (spec «Validación humana»). */
    public static final int NOTA_MINIMA = 10;
    public static final String NOTA_CORTA = "Escriba el motivo (mínimo " + NOTA_MINIMA + " caracteres)";
    public static final int MAX_PLANTILLA = 500;

    /**
     * Parámetro no válido con el campo del formulario al que se refiere (baja, media, alta, ventana, niveles o plantilla): la interfaz lo
     * marca junto al campo (Anexo 5 num. 2.4). Sigue siendo una {@link IllegalArgumentException} con el mismo mensaje.
     */
    public static final class ParametroNoValido extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;
        private final String campo;

        public ParametroNoValido(String campo, String mensaje) {
            super(mensaje);
            this.campo = campo;
        }

        public String campo() {
            return campo;
        }
    }

    /** Lee un campo y, si no es válido, deja dicho cuál es. */
    private static <T> T enCampo(String campo, java.util.function.Supplier<T> lectura) {
        try {
            return lectura.get();
        } catch (ParametroNoValido e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new ParametroNoValido(campo, e.getMessage());
        }
    }

    /** Tipos de alerta (A4 L650–682; A5 tablero 11). */
    public enum Tipo {
        INCREMENTO_GASTO("incremento_gasto", "Incremento inusual del gasto", "%"),
        INCREMENTO_CONSUMO("incremento_consumo", "Incremento abrupto del consumo", "%"),
        ALTO_COSTO("alto_costo", "Ingresos a la cohorte de alto costo", "pacientes"),
        POLIMEDICADOS("polimedicados", "Ingresos a la cohorte de polimedicados", "pacientes"),
        DUPLICIDAD("duplicidad_terapeutica", "Duplicidad terapéutica", "pacientes"),
        INTERACCION("interaccion", "Interacción entre medicamentos", "pacientes"),
        FUERA_INDICACION("fuera_indicacion", "Uso fuera de indicación", "pacientes"),
        MARCA_ESTRECHO_MARGEN("marca_estrecho_margen", "Cambio de marca en estrecho margen", "pacientes"),
        DESABASTECIMIENTO("desabastecimiento", "Riesgo de desabastecimiento (aproximado)", "%"),
        CONCENTRACION("concentracion_prescripciones", "Concentración de prescripciones", "%"),
        FORMULACION_INUSUAL("formulacion_inusual", "Formulación inusual", "registros"),
        PRESENTACION_INCONSISTENTE("presentacion_inconsistente", "Presentación inconsistente", "registros");

        private final String codigo;
        private final String nombre;
        private final String unidad;

        Tipo(String codigo, String nombre, String unidad) {
            this.codigo = codigo;
            this.nombre = nombre;
            this.unidad = unidad;
        }

        public String codigo() {
            return codigo;
        }

        public String nombre() {
            return nombre;
        }

        /** Unidad del valor observado y del umbral: «%», «pacientes» o «registros». */
        public String unidad() {
            return unidad;
        }

        public static Optional<Tipo> deCodigo(String c) {
            for (Tipo t : values()) {
                if (t.codigo.equals(c)) {
                    return Optional.of(t);
                }
            }
            return Optional.empty();
        }

        /**
         * Niveles que el detector del tipo sabe evaluar (decisión revisión A4 2026-10-24): configurar otro se rechaza (422) en lugar de
         * aceptarse y callar (falso negativo silencioso).
         */
        public List<String> nivelesAdmitidos() {
            return switch (this) {
                case INCREMENTO_GASTO, INCREMENTO_CONSUMO -> List.of("nacional", "fuerza");
                case DESABASTECIMIENTO, CONCENTRACION -> List.of("esm");
                case FORMULACION_INUSUAL, PRESENTACION_INCONSISTENTE -> List.of("nacional");
                default -> NIVELES;
            };
        }
    }

    public enum Severidad {
        BAJA("baja", "Baja"), MEDIA("media", "Media"), ALTA("alta", "Alta");

        private final String codigo;
        private final String etiqueta;

        Severidad(String codigo, String etiqueta) {
            this.codigo = codigo;
            this.etiqueta = etiqueta;
        }

        public String codigo() {
            return codigo;
        }

        public String etiqueta() {
            return etiqueta;
        }

        public static Severidad deCodigo(String c) {
            for (Severidad s : values()) {
                if (s.codigo.equals(c)) {
                    return s;
                }
            }
            throw new IllegalArgumentException("Severidad desconocida: " + c);
        }
    }

    /** Estados de la validación humana (RESP L5175). */
    public enum Estado {
        NUEVA("nueva"), EN_REVISION("en_revision"), VALIDADA("validada"), DESCARTADA("descartada");

        private final String codigo;

        Estado(String codigo) {
            this.codigo = codigo;
        }

        public String codigo() {
            return codigo;
        }

        public static Optional<Estado> deCodigo(String c) {
            for (Estado e : values()) {
                if (e.codigo.equals(c)) {
                    return Optional.of(e);
                }
            }
            return Optional.empty();
        }

        /** nueva → en_revision → validada | descartada; descartada → en_revision (reabrir). */
        public boolean puedePasarA(Estado a) {
            return switch (this) {
                case NUEVA -> a == EN_REVISION || a == VALIDADA || a == DESCARTADA;
                case EN_REVISION -> a == VALIDADA || a == DESCARTADA;
                case DESCARTADA -> a == EN_REVISION;
                case VALIDADA -> false;
            };
        }

        /** Validar o descartar exige nota. */
        public boolean exigeNota() {
            return this == VALIDADA || this == DESCARTADA;
        }
    }

    // ---------- Parámetros ----------

    /**
     * Parámetros de un tipo (una versión). {@code alta} nula = no hay banda alta. {@code extras}: claves propias del tipo (base mínima,
     * z robusta, meses de base, días de cobertura, nivel ATC, k, escala mínima, mínimo de fórmulas, ATC de alto costo).
     */
    public record Parametros(boolean activo, List<String> niveles, int ventana, BigDecimal baja, BigDecimal media, BigDecimal alta,
                             Map<String, Object> extras, List<String> exclusiones, String plantilla) {

        public BigDecimal extra(String clave, String defecto) {
            Object v = extras.get(clave);
            return new BigDecimal(v == null ? defecto : String.valueOf(v));
        }

        @SuppressWarnings("unchecked")
        public List<String> lista(String clave) {
            Object v = extras.get(clave);
            return v instanceof List<?> l ? (List<String>) l : List.of();
        }
    }

    private static BigDecimal n(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    private static Map<String, Object> ex(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Regla por defecto de cada tipo (spec, tabla «Tipos de alerta y regla por defecto»). */
    public static Parametros porDefecto(Tipo t) {
        return switch (t) {
            case INCREMENTO_GASTO -> new Parametros(true, List.of("nacional", "fuerza"), 3, n("30"), n("60"), n("100"),
                    ex("mesesBase", "6", "z", "3", "baseMinima", "1000000", "minRegistros", "30"), List.of(), RECOMENDACIONES.get(t));
            case INCREMENTO_CONSUMO -> new Parametros(true, List.of("nacional", "fuerza"), 3, n("30"), n("60"), n("100"),
                    ex("mesesBase", "6", "z", "3", "baseMinima", "100", "minRegistros", "30"), List.of(), RECOMENDACIONES.get(t));
            case ALTO_COSTO, POLIMEDICADOS -> new Parametros(true, List.of("fuerza", "esm"), 3, n("3"), n("5"), n("10"), ex("z", "3"), List.of(),
                    RECOMENDACIONES.get(t));
            case DUPLICIDAD -> new Parametros(true, List.of("nacional"), 1, n("1"), n("10"), n("30"), ex("dias", "30", "nivelAtc", "4"), List.of(),
                    RECOMENDACIONES.get(t));
            case INTERACCION -> new Parametros(true, List.of("nacional"), 1, n("1"), n("10"), n("30"), ex("dias", "30"), List.of(), RECOMENDACIONES.get(t));
            case FUERA_INDICACION -> new Parametros(true, List.of("nacional"), 1, n("1"), n("10"), n("30"), ex(), List.of(), RECOMENDACIONES.get(t));
            case MARCA_ESTRECHO_MARGEN -> new Parametros(true, List.of("esm"), 1, n("1"), n("10"), n("30"), ex(), List.of(), RECOMENDACIONES.get(t));
            case DESABASTECIMIENTO -> new Parametros(true, List.of("esm"), 3, n("50"), n("75"), n("100"), ex("baseMinima", "10"), List.of(),
                    RECOMENDACIONES.get(t));
            case CONCENTRACION -> new Parametros(true, List.of("esm"), 1, n("50"), n("70"), n("90"), ex("minFormulas", "20", "atcAltoCosto",
                    List.of("L01", "L04")), List.of(), RECOMENDACIONES.get(t));
            case FORMULACION_INUSUAL -> new Parametros(true, List.of("nacional"), 1, n("1"), n("5"), n("20"), ex("k", "5", "escalaMinima", "0.25",
                    "minRegistros", "30"), List.of(), RECOMENDACIONES.get(t));
            case PRESENTACION_INCONSISTENTE -> new Parametros(true, List.of("nacional"), 1, n("1"), n("100"), null, ex(), List.of(),
                    RECOMENDACIONES.get(t));
        };
    }

    /** Plantillas de recomendación por defecto (parametrizables). Admiten {dimension}, {valor}, {periodo} y {nivel}. */
    public static final Map<Tipo, String> RECOMENDACIONES = Map.ofEntries(
            Map.entry(Tipo.INCREMENTO_GASTO, "Revise con el OPL la causa del aumento del gasto de {dimension} {valor} en {nivel} ({periodo}): cambios de "
                    + "precio, nuevas indicaciones o pacientes; compare con la programación y el presupuesto."),
            Map.entry(Tipo.INCREMENTO_CONSUMO, "Verifique la formulación y la dispensación de {dimension} {valor} en {nivel} ({periodo}): nuevos pacientes, "
                    + "cambios de esquema o posibles errores de registro; confirme el inventario disponible."),
            Map.entry(Tipo.ALTO_COSTO, "Priorice la gestión del riesgo de los pacientes que ingresaron a alto costo en {valor} ({periodo}): adherencia, "
                    + "pertinencia y seguimiento por el programa correspondiente."),
            Map.entry(Tipo.POLIMEDICADOS, "Programe la conciliación y revisión de la farmacoterapia de los pacientes polimedicados de {valor} ({periodo})."),
            Map.entry(Tipo.DUPLICIDAD, "Revise con el servicio farmacéutico la duplicidad en {valor} ({periodo}): confirme si es un cambio de terapia o una "
                    + "duplicidad real y corrija la formulación."),
            Map.entry(Tipo.INTERACCION, "Evalúe clínicamente la combinación {valor} ({periodo}) y documente la decisión del prescriptor."),
            Map.entry(Tipo.FUERA_INDICACION, "Verifique la pertinencia de {valor} frente al diagnóstico registrado ({periodo}) y la calidad del CIE-10 del "
                    + "insumo."),
            Map.entry(Tipo.MARCA_ESTRECHO_MARGEN, "Haga seguimiento clínico y, si aplica, niveles séricos a los pacientes con cambio de marca de {valor} "
                    + "({periodo}); prefiera mantener el mismo titular."),
            Map.entry(Tipo.DESABASTECIMIENTO, "Confirme con el OPL el inventario y los pendientes de {valor} ({periodo}) y active el plan de contingencia."),
            Map.entry(Tipo.CONCENTRACION, "Audite la formulación del profesional que concentra las fórmulas en {valor} ({periodo}) frente a guías y "
                    + "protocolos."),
            Map.entry(Tipo.FORMULACION_INUSUAL, "Verifique las dispensaciones de {valor} con cantidades atípicas ({periodo}): posible error de "
                    + "digitación, fraccionamiento o tratamiento prolongado."),
            Map.entry(Tipo.PRESENTACION_INCONSISTENTE, "Solicite al OPL la corrección de la concentración, forma o presentación de {valor} en el insumo "
                    + "({periodo}) y revise la homologación."));

    /** JSON de los parámetros (lo que se guarda y lo que se edita). */
    public static Map<String, Object> aMapa(Parametros p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activo", p.activo());
        m.put("niveles", p.niveles());
        m.put("ventana", p.ventana());
        m.put("baja", p.baja().toPlainString());
        m.put("media", p.media().toPlainString());
        m.put("alta", p.alta() == null ? null : p.alta().toPlainString());
        m.put("extras", p.extras());
        m.put("exclusiones", p.exclusiones());
        m.put("plantilla", p.plantilla());
        return m;
    }

    /** Lee y valida parámetros editados (mensajes para el usuario en {@link IllegalArgumentException}). */
    @SuppressWarnings("unchecked")
    public static Parametros leer(Tipo t, Map<String, Object> m) {
        Parametros d = porDefecto(t);
        boolean activo = m.get("activo") == null ? d.activo() : Boolean.TRUE.equals(m.get("activo"));
        List<String> niveles = d.niveles();
        if (m.get("niveles") instanceof List<?> l) {
            TreeSet<String> s = new TreeSet<>();
            for (Object o : l) {
                String v = String.valueOf(o);
                if (!NIVELES.contains(v)) {
                    throw new ParametroNoValido("niveles", "Nivel no válido: «" + Bitacora.cortar(v, 20) + "» (nacional, fuerza o esm).");
                }
                if (!t.nivelesAdmitidos().contains(v)) {
                    throw new ParametroNoValido("niveles", "El nivel «" + v + "» no se evalúa en «" + t.nombre() + "» (niveles admitidos: "
                            + String.join(", ", t.nivelesAdmitidos()) + ").");
                }
                s.add(v);
            }
            if (s.isEmpty()) {
                throw new ParametroNoValido("niveles", "Elija al menos un nivel.");
            }
            niveles = NIVELES.stream().filter(s::contains).toList();
        }
        int ventana = m.get("ventana") == null ? d.ventana() : enCampo("ventana", () -> entero(m.get("ventana"), 1, 24, "La ventana (meses)"));
        BigDecimal baja = enCampo("baja", () -> numero(m.get("baja"), d.baja(), "El umbral bajo"));
        BigDecimal media = enCampo("media", () -> numero(m.get("media"), d.media(), "El umbral medio"));
        BigDecimal alta = m.containsKey("alta") && (m.get("alta") == null || String.valueOf(m.get("alta")).isBlank()) ? null
                : enCampo("alta", () -> numero(m.get("alta"), d.alta(), "El umbral alto"));
        if (baja.signum() <= 0 || media.compareTo(baja) < 0 || alta != null && alta.compareTo(media) < 0) {
            throw new ParametroNoValido(baja.signum() <= 0 ? "baja" : media.compareTo(baja) < 0 ? "media" : "alta",
                    "Los umbrales deben ser positivos y crecientes: baja ≤ media ≤ alta.");
        }
        Map<String, Object> extras = new LinkedHashMap<>(d.extras());
        if (m.get("extras") instanceof Map<?, ?> e) {
            for (Map.Entry<?, ?> x : e.entrySet()) {
                String k = String.valueOf(x.getKey());
                if (!d.extras().containsKey(k)) {
                    throw new IllegalArgumentException("Parámetro desconocido para " + t.nombre() + ": «" + Bitacora.cortar(k, 30) + "».");
                }
                if (d.extras().get(k) instanceof List<?>) {
                    if (!(x.getValue() instanceof List<?> lv) || lv.size() > 50) {
                        throw new IllegalArgumentException("«" + k + "» es una lista de códigos.");
                    }
                    List<String> codigos = new ArrayList<>();
                    for (Object o : lv) {
                        String c = AnaliticaCohortes.normalizarValor(AnaliticaCohortes.TipoCodigo.ATC, String.valueOf(o));
                        if (c == null) {
                            throw new IllegalArgumentException("Código ATC no válido en «" + k + "».");
                        }
                        codigos.add(c);
                    }
                    extras.put(k, List.copyOf(codigos));
                } else {
                    BigDecimal v = numero(x.getValue(), null, "«" + k + "»");
                    if (v.signum() < 0) {
                        throw new IllegalArgumentException("«" + k + "» no puede ser negativo.");
                    }
                    extras.put(k, v.stripTrailingZeros().toPlainString());
                }
            }
        }
        List<String> exclusiones = d.exclusiones();
        if (m.get("exclusiones") instanceof List<?> l) {
            TreeSet<String> s = new TreeSet<>();
            for (Object o : l) {
                String v = String.valueOf(o).strip().toUpperCase(Locale.ROOT);
                if (v.isEmpty() || v.length() > 60 || !v.matches("^[\\p{L}\\p{N} ,./()+-]+$")) {
                    throw new IllegalArgumentException("Exclusión no válida: «" + Bitacora.cortar(v, 30) + "».");
                }
                s.add(v);
            }
            if (s.size() > 100) {
                throw new IllegalArgumentException("Máximo 100 exclusiones.");
            }
            exclusiones = List.copyOf(s);
        }
        String plantilla = m.get("plantilla") == null ? d.plantilla() : String.valueOf(m.get("plantilla")).strip();
        if (plantilla.isEmpty() || plantilla.length() > MAX_PLANTILLA) {
            throw new ParametroNoValido("plantilla", "La plantilla de recomendación lleva de 1 a " + MAX_PLANTILLA + " caracteres.");
        }
        return new Parametros(activo, niveles, ventana, baja, media, alta, Map.copyOf(extras), exclusiones, plantilla);
    }

    private static int entero(Object o, int min, int max, String que) {
        int v;
        try {
            v = o instanceof Number x ? x.intValue() : Integer.parseInt(String.valueOf(o).strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(que + " debe ser un número entero.");
        }
        if (v < min || v > max) {
            throw new IllegalArgumentException(que + " va de " + min + " a " + max + ".");
        }
        return v;
    }

    private static BigDecimal numero(Object o, BigDecimal defecto, String que) {
        if (o == null) {
            if (defecto == null) {
                throw new IllegalArgumentException(que + " es obligatorio.");
            }
            return defecto;
        }
        BigDecimal v;
        try {
            v = new BigDecimal(String.valueOf(o).strip().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(que + " debe ser un número.");
        }
        if (v.abs().compareTo(TOPE) > 0 || v.scale() > 6) {
            throw new IllegalArgumentException(que + " va hasta 1000000000000 con máximo 6 decimales.");
        }
        return v;
    }

    // ---------- Evaluable ----------

    /** Fuentes y campos de que dependen algunos tipos (spec «Tipos que dependen de fuentes por definir o de campos opcionales»). */
    public record Fuentes(boolean interacciones, boolean indicaciones, boolean estrechoMargen, boolean vitales, boolean profesional) {
    }

    /** Motivo de «no evaluado» del tipo (falta su fuente o su campo) o vacío si se puede evaluar. Un tipo sin fuente nunca calla. */
    public static Optional<String> noEvaluable(Tipo t, Fuentes f) {
        String m = switch (t) {
            case INTERACCION -> f.interacciones() ? null : "no evaluado: sin fuente de interacciones cargada";
            case FUERA_INDICACION -> f.indicaciones() ? null : "no evaluado: sin fuente de indicaciones (pares ATC–CIE-10) cargada";
            case MARCA_ESTRECHO_MARGEN -> f.estrechoMargen() ? null : "no evaluado: sin lista de estrecho margen publicada";
            case DESABASTECIMIENTO -> f.vitales() ? null : "no evaluado: sin referencia de medicamentos vitales no disponibles publicada";
            case CONCENTRACION -> f.profesional() ? null : "no evaluado: el insumo no trae profesional";
            default -> null;
        };
        return Optional.ofNullable(m);
    }

    // ---------- Severidad ----------

    /** La banda más alta alcanzada (valor ≥ umbral de la banda); vacío si no alcanza la baja. */
    public static Optional<Severidad> severidad(BigDecimal valor, Parametros p) {
        if (valor == null) {
            return Optional.empty();
        }
        if (p.alta() != null && valor.compareTo(p.alta()) >= 0) {
            return Optional.of(Severidad.ALTA);
        }
        if (valor.compareTo(p.media()) >= 0) {
            return Optional.of(Severidad.MEDIA);
        }
        if (valor.compareTo(p.baja()) >= 0) {
            return Optional.of(Severidad.BAJA);
        }
        return Optional.empty();
    }

    /** Umbral de la banda (el que se muestra con la alerta). */
    public static BigDecimal umbral(Severidad s, Parametros p) {
        return switch (s) {
            case BAJA -> p.baja();
            case MEDIA -> p.media();
            case ALTA -> p.alta();
        };
    }

    // ---------- Estadística robusta ----------

    public static double mediana(long[] v) {
        if (v.length == 0) {
            return 0;
        }
        long[] s = v.clone();
        Arrays.sort(s);
        int m = s.length / 2;
        return s.length % 2 == 1 ? s[m] : (s[m - 1] + s[m]) / 2.0;
    }

    /** Desviación absoluta mediana. */
    public static double mad(long[] v) {
        double med = mediana(v);
        double[] d = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            d[i] = Math.abs(v[i] - med);
        }
        Arrays.sort(d);
        if (d.length == 0) {
            return 0;
        }
        int m = d.length / 2;
        return d.length % 2 == 1 ? d[m] : (d[m - 1] + d[m]) / 2.0;
    }

    /**
     * Incremento del mes {@code i} de una serie mensual consecutiva: observado, media de los {@code ventana} meses previos (referencia),
     * variación % y z robusta = (observado − mediana) / (1,4826 · MAD) de los {@code mesesBase} meses previos.
     */
    public record Incremento(BigDecimal observado, BigDecimal referencia, BigDecimal variacionPct, BigDecimal zRobusta) {
    }

    /**
     * Regla de incremento (gasto o consumo): con al menos {@code mesesBase} meses previos, referencia ≥ base mínima, variación ≥ el umbral
     * bajo y z robusta ≥ z. Con MAD 0 (historia constante) cualquier valor sobre la mediana cumple la z. La serie viene en la unidad mínima
     * (centavos o milésimas); {@code escala} la devuelve a pesos o unidades.
     */
    public static Optional<Incremento> incremento(long[] serie, int i, Parametros p, int escala) {
        int base = p.extra("mesesBase", "6").intValue();
        int ventana = p.ventana();
        if (i < Math.max(base, ventana)) {
            return Optional.empty();
        }
        long suma = 0;
        for (int k = i - ventana; k < i; k++) {
            suma += serie[k];
        }
        BigDecimal referencia = BigDecimal.valueOf(suma).divide(BigDecimal.valueOf(ventana), 6, RoundingMode.HALF_UP).movePointLeft(escala);
        BigDecimal observado = BigDecimal.valueOf(serie[i]).movePointLeft(escala);
        if (referencia.compareTo(p.extra("baseMinima", "0")) < 0 || referencia.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal var = observado.subtract(referencia).multiply(BigDecimal.valueOf(100)).divide(referencia, 2, RoundingMode.HALF_UP);
        if (var.compareTo(p.baja()) < 0) {
            return Optional.empty();
        }
        long[] previos = Arrays.copyOfRange(serie, i - base, i);
        double med = mediana(previos);
        double mad = mad(previos);
        double z = mad == 0 ? (serie[i] > med ? 99.99 : 0) : (serie[i] - med) / (1.4826 * mad);
        BigDecimal zr = BigDecimal.valueOf(Math.min(z, 99.99)).setScale(2, RoundingMode.HALF_UP);
        if (zr.compareTo(p.extra("z", "3")) < 0) {
            return Optional.empty();
        }
        return Optional.of(new Incremento(observado, referencia.setScale(2, RoundingMode.HALF_UP), var, zr));
    }

    /** Caída % del mes frente a la media de los meses previos (desabastecimiento aproximado); vacío si la base no alcanza. */
    public static Optional<Incremento> caida(long[] serie, int i, Parametros p) {
        int ventana = p.ventana();
        if (i < ventana) {
            return Optional.empty();
        }
        long suma = 0;
        for (int k = i - ventana; k < i; k++) {
            suma += serie[k];
        }
        BigDecimal referencia = BigDecimal.valueOf(suma).divide(BigDecimal.valueOf(ventana), 2, RoundingMode.HALF_UP);
        if (referencia.compareTo(p.extra("baseMinima", "0")) < 0 || referencia.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal observado = BigDecimal.valueOf(serie[i]);
        BigDecimal caida = referencia.subtract(observado).multiply(BigDecimal.valueOf(100)).divide(referencia, 2, RoundingMode.HALF_UP);
        return caida.compareTo(p.baja()) >= 0 ? Optional.of(new Incremento(observado, referencia, caida, BigDecimal.ZERO)) : Optional.empty();
    }

    /**
     * Ingresos atípicos a una cohorte (alto costo, polimedicados; decisión revisión A4 2026-10-24): además de alcanzar la banda baja, los
     * ingresos del mes deben superar lo esperado por azar frente a la media de los meses previos: (ingresos − referencia) / √max(referencia, 1)
     * ≥ z (aproximación de Poisson; z = 0 la apaga). Sin esto, una cohorte grande dispara alertas cada mes en cada ESM solo por su tamaño.
     */
    public static boolean ingresoAtipico(long ingresos, BigDecimal referencia, Parametros p) {
        BigDecimal z = p.extra("z", "3");
        if (z.signum() <= 0) {
            return true;
        }
        double ref = referencia == null ? 0 : referencia.doubleValue();
        return (ingresos - ref) / Math.sqrt(Math.max(ref, 1)) >= z.doubleValue();
    }

    /**
     * ¿La serie tiene volumen para juzgar un incremento? Media de dispensaciones de los meses de la ventana ≥ {@code minRegistros} (revisión
     * A4: con pocas dispensaciones —un biológico, dos pacientes— cualquier mes duplica la media y el ruido salía como alerta alta).
     */
    public static boolean conVolumen(long[] dispensaciones, int i, Parametros p) {
        int v = p.ventana();
        if (i < v) {
            return false;
        }
        long s = 0;
        for (int k = i - v; k < i; k++) {
            s += dispensaciones[k];
        }
        return s >= p.extra("minRegistros", "0").multiply(BigDecimal.valueOf(v)).longValue();
    }

    // ---------- Formulación inusual ----------

    /** Mediana, MAD y umbral (mediana + k · max(1,4826 · MAD, escala mínima · mediana)) de la cantidad por dispensación de un CUM. */
    public record UmbralCantidad(long mediana, double escala, long umbral, long registros) {
    }

    /**
     * Umbral de cantidad atípica a partir de la tabla de frecuencias (cantidad en milésimas ⇒ registros). Con menos de
     * {@code minRegistros} registros, vacío (no hay base). La escala mínima evita que una historia casi constante (MAD 0) marque como
     * atípico el doble de la cantidad habitual.
     */
    public static Optional<UmbralCantidad> umbralCantidad(NavigableMap<Long, Long> frecuencias, Parametros p) {
        long total = frecuencias.values().stream().mapToLong(Long::longValue).sum();
        if (total < p.extra("minRegistros", "30").longValue() || total == 0) {
            return Optional.empty();
        }
        long med = cuantil(frecuencias, total);
        NavigableMap<Long, Long> desv = new java.util.TreeMap<>();
        frecuencias.forEach((c, k) -> desv.merge(Math.abs(c - med), k, Long::sum));
        long mad = cuantil(desv, total);
        double escala = Math.max(1.4826 * mad, p.extra("escalaMinima", "0.25").doubleValue() * med);
        long umbral = med + (long) Math.ceil(p.extra("k", "5").doubleValue() * escala);
        return Optional.of(new UmbralCantidad(med, escala, umbral, total));
    }

    /** Mediana inferior de una tabla de frecuencias. */
    private static long cuantil(NavigableMap<Long, Long> f, long total) {
        long objetivo = (total + 1) / 2;
        long acum = 0;
        for (Map.Entry<Long, Long> x : f.entrySet()) {
            acum += x.getValue();
            if (acum >= objetivo) {
                return x.getKey();
            }
        }
        return f.isEmpty() ? 0 : f.lastKey();
    }

    // ---------- Secuencia de un paciente ----------

    /**
     * Dispensación de un paciente (ya ordenadas por día).
     *
     * @param dia       día (epochDay)
     * @param atc       ATC del medicamento (tabla maestra o insumo); nulo si no se conoce
     * @param principio principio activo normalizado; nulo si no se conoce
     * @param cum       CUM del insumo
     */
    public record Dispensa(int dia, String atc, String principio, String cum, String esm, String fuerza) {
    }

    /**
     * Duplicidad terapéutica: para cada dispensación j, ¿hay una anterior i del mismo grupo ATC (primeros {@code largoAtc} caracteres)
     * con otro principio activo y cobertura superpuesta (día_i + días &gt; día_j)? Devuelve los pares [i, j] (uno por j: la anterior más
     * reciente, y las demás de la ventana: todos los pares). Excluye los grupos o principios de {@code excluidos}.
     */
    public static List<int[]> duplicidades(List<Dispensa> d, int dias, int largoAtc, Set<String> excluidos) {
        List<int[]> r = new ArrayList<>();
        for (int j = 0; j < d.size(); j++) {
            Dispensa b = d.get(j);
            if (b.atc() == null || b.principio() == null || b.atc().length() < largoAtc) {
                continue;
            }
            String grupo = b.atc().substring(0, largoAtc);
            if (excluidos.contains(grupo) || excluidos.contains(b.principio())) {
                continue;
            }
            for (int i = j - 1; i >= 0; i--) {
                Dispensa a = d.get(i);
                if (a.dia() + dias <= b.dia()) {
                    break;
                }
                if (a.atc() != null && a.principio() != null && a.atc().startsWith(grupo) && !a.principio().equals(b.principio())
                        && !excluidos.contains(a.principio())) {
                    r.add(new int[] {i, j});
                }
            }
        }
        return r;
    }

    /** Llave de un par de principios (orden alfabético, separados por « + »). */
    public static String par(String a, String b) {
        return a.compareTo(b) <= 0 ? a + " + " + b : b + " + " + a;
    }

    /** Interacciones: todos los pares [i, j] cuyos principios forman un par de la tabla, con cobertura superpuesta. */
    public static List<int[]> interacciones(List<Dispensa> d, int dias, Set<String> pares) {
        List<int[]> r = new ArrayList<>();
        for (int j = 0; j < d.size(); j++) {
            Dispensa b = d.get(j);
            if (b.principio() == null) {
                continue;
            }
            for (int i = j - 1; i >= 0; i--) {
                Dispensa a = d.get(i);
                if (a.dia() + dias <= b.dia()) {
                    break;
                }
                if (a.principio() != null && !a.principio().equals(b.principio()) && pares.contains(par(a.principio(), b.principio()))) {
                    r.add(new int[] {i, j}); // todos los pares de la ventana: un tercer principio no oculta el segundo par
                }
            }
        }
        return r;
    }

    /**
     * Cambio de marca en estrecho margen: dispensaciones j de un principio de la lista cuyo CUM es distinto del de la dispensación anterior
     * del mismo principio (mismo paciente).
     */
    public static List<Integer> cambiosDeMarca(List<Dispensa> d, Set<String> principiosEstrecho) {
        List<Integer> r = new ArrayList<>();
        Map<String, String> ultimoCum = new HashMap<>();
        for (int j = 0; j < d.size(); j++) {
            Dispensa b = d.get(j);
            if (b.principio() == null || b.cum() == null || !principiosEstrecho.contains(b.principio())) {
                continue;
            }
            String antes = ultimoCum.put(b.principio(), b.cum());
            if (antes != null && !antes.equals(b.cum())) {
                r.add(j);
            }
        }
        return r;
    }

    /**
     * Uso fuera de indicación: la dispensación tiene ATC y CIE-10, algún prefijo ATC de la referencia lo cubre y el CIE-10 no empieza por
     * ninguno de los admitidos de esos prefijos. Sin ATC, sin CIE-10, excluido o con un ATC que la referencia no cubre: no se juzga.
     */
    public static boolean fueraDeIndicacion(String atc, String cie, Map<String, List<String>> indicaciones, java.util.Collection<String> excluidos) {
        if (atc == null || cie == null || cie.isBlank() || excluidos.contains(atc)) {
            return false;
        }
        List<String> admitidos = null;
        for (Map.Entry<String, List<String>> e : indicaciones.entrySet()) {
            if (atc.startsWith(e.getKey())) {
                admitidos = admitidos == null ? new ArrayList<>() : admitidos;
                admitidos.addAll(e.getValue());
            }
        }
        return admitidos != null && !AnaliticaCohortes.conPrefijo(cie, admitidos);
    }

    /** ¿El principio del medicamento está en la lista? (igual, o la lista es su nombre base: «CARBAMAZEPINA» en «CARBAMAZEPINA SODICA»). */
    public static boolean enLista(String principio, Set<String> lista) {
        if (principio == null) {
            return false;
        }
        if (lista.contains(principio)) {
            return true;
        }
        for (String l : lista) {
            if (principio.startsWith(l + " ")) {
                return true;
            }
        }
        return false;
    }

    // ---------- Concentración ----------

    /** Participación % del profesional con más fórmulas (vacío si el total no alcanza el mínimo). Devuelve [participación, total]. */
    public static Optional<BigDecimal[]> concentracion(Map<String, Long> porProfesional, long minimo) {
        long total = porProfesional.values().stream().mapToLong(Long::longValue).sum();
        if (total < minimo || total == 0) {
            return Optional.empty();
        }
        long max = porProfesional.values().stream().mapToLong(Long::longValue).max().orElse(0);
        return Optional.of(new BigDecimal[] {BigDecimal.valueOf(max * 100).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP),
            BigDecimal.valueOf(total), BigDecimal.valueOf(max)});
    }

    // ---------- Presentación ----------

    private static final Pattern CONCENTRACION = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(MCG|MG|G|UI|ML|%)(?![A-Z])");

    /** Firma de concentración de una descripción («LOSARTAN 50 MG TABLETA» ⇒ «50 MG»); vacía si no trae concentración. */
    public static String firmaConcentracion(String descripcion) {
        if (descripcion == null) {
            return "";
        }
        Matcher m = CONCENTRACION.matcher(AnaliticaHomologacion.normalizarTexto(descripcion));
        TreeSet<String> s = new TreeSet<>();
        while (m.find()) {
            s.add(new BigDecimal(m.group(1).replace(',', '.')).stripTrailingZeros().toPlainString() + " " + m.group(2));
        }
        return String.join("/", s);
    }

    // ---------- Análisis técnico (P4) ----------

    /**
     * Contexto con que se arma el análisis técnico.
     *
     * @param nivel     «Nacional», «Fuerza EJC» o «ESM BAS02»
     * @param dimension etiqueta de la dimensión («ATC nivel 2», «Principio activo»…)
     * @param valor     valor de la dimensión
     * @param detalle   frase propia del tipo con las cifras (ya armada por el servicio)
     * @param fuente    fuente de referencia usada (con versión) o nula
     */
    public record Contexto(Tipo tipo, String nivel, String dimension, String valor, String periodo, BigDecimal observado, BigDecimal referencia,
                           BigDecimal umbral, Severidad severidad, long registros, long pacientes, BigDecimal gasto, String detalle, String fuente) {
    }

    // ---------- Conteos de pacientes en los textos (supresión de celdas pequeñas al mostrar) ----------

    private static final Pattern MARCA = Pattern.compile("⟦(-?\\d+(?:\\.\\d+)?)⟧");

    /**
     * Conteo de pacientes dentro de un texto guardado (causa, valoración, detalle): va marcado y se escribe al MOSTRARLO con {@link #mostrar},
     * que aplica la supresión de celdas pequeñas vigente (revisión A4: el número exacto nunca sale por un texto si la celda se oculta).
     */
    public static String marca(BigDecimal n) {
        return n == null ? "—" : "⟦" + n.stripTrailingZeros().toPlainString() + "⟧";
    }

    public static String marca(long n) {
        return "⟦" + n + "⟧";
    }

    /** Texto con sus conteos marcados ya escritos: «<k» si 0 &lt; n &lt; k (k &gt; 0); si no, el número con el formato 2.4. */
    public static String mostrar(String texto, int k) {
        if (texto == null || texto.indexOf('⟦') < 0) {
            return texto;
        }
        Matcher m = MARCA.matcher(texto);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            BigDecimal v = new BigDecimal(m.group(1));
            String r = oculto(v, k) ? "<" + k : v.stripTrailingZeros().scale() > 0 ? AnaliticaConsulta.decimal(v) : AnaliticaConsulta.entero(v.longValue());
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** ¿Un conteo de pacientes se oculta con el umbral k? (0 &lt; n &lt; k). */
    public static boolean oculto(BigDecimal n, int k) {
        return k > 0 && n != null && n.signum() > 0 && n.compareTo(BigDecimal.valueOf(k)) < 0;
    }

    /** Valor con su unidad (2.4). */
    public static String conUnidad(BigDecimal v, String unidad) {
        if (v == null) {
            return "—";
        }
        return "%".equals(unidad) ? AnaliticaConsulta.porcentaje(v) : AnaliticaConsulta.decimal(v) + " " + unidad;
    }

    /** Causa: qué se observó y por qué es alerta, con las cifras y la fuente de referencia (conteos de pacientes marcados: {@link #mostrar}). */
    public static String causa(Contexto c) {
        boolean pac = "pacientes".equals(c.tipo().unidad());
        String base = c.tipo().nombre() + " en " + c.dimension().toLowerCase(Locale.ROOT) + " " + c.valor() + " (" + c.nivel() + ", " + c.periodo()
                + "): valor observado " + (pac ? marca(c.observado()) + " pacientes" : conUnidad(c.observado(), c.tipo().unidad()))
                + (c.referencia() == null ? "" : " frente a una referencia de " + (pac ? marca(c.referencia()) : AnaliticaConsulta.decimal(c.referencia())))
                + "; umbral de la banda "
                + c.severidad().etiqueta().toLowerCase(Locale.ROOT) + " " + conUnidad(c.umbral(), c.tipo().unidad()) + ".";
        String r = base + (c.detalle() == null || c.detalle().isBlank() ? "" : " " + c.detalle().strip())
                + (c.fuente() == null ? "" : " Fuente de referencia: " + c.fuente() + ".");
        return Bitacora.cortar(r, 1000);
    }

    /** Valoración del riesgo o impacto: severidad, pacientes, registros y gasto involucrados. */
    public static String valoracion(Contexto c) {
        return "Severidad " + c.severidad().etiqueta().toLowerCase(Locale.ROOT) + ": " + marca(c.pacientes()) + " pacientes (agregado), " + c.registros()
                + " registros y " + AnaliticaConsulta.moneda(c.gasto()) + " involucrados. " + switch (c.severidad()) {
                    case ALTA -> "Requiere revisión prioritaria por el equipo interdisciplinario.";
                    case MEDIA -> "Requiere revisión en el ciclo de seguimiento.";
                    case BAJA -> "Para seguimiento.";
                };
    }

    /** Recomendación: la plantilla del tipo con {dimension}, {valor}, {periodo} y {nivel}. */
    public static String recomendacion(String plantilla, Contexto c) {
        return Bitacora.cortar(plantilla.replace("{dimension}", c.dimension().toLowerCase(Locale.ROOT)).replace("{valor}", c.valor())
                .replace("{periodo}", c.periodo()).replace("{nivel}", c.nivel()), 1000);
    }

    /** Llave natural (tipo, nivel, código del nivel, dimensión, valor, periodo) en SHA-256: reejecutar no duplica. */
    public static String llave(Tipo t, String nivel, String codigoNivel, String dimension, String valor, String periodo) {
        return Huellas.sha256(String.join("|", t.codigo(), nivel, codigoNivel == null ? "" : codigoNivel, dimension, valor, periodo));
    }

    /**
     * Detalle del aviso por correo (≤ 500, sin datos personales): tipo, dimensión, periodo, observado, umbral, pacientes y número, con la
     * supresión de celdas pequeñas {@code k} ya aplicada (un conteo 0 &lt; n &lt; k sale «<k»).
     */
    public static String detalleAviso(long id, Contexto c, int k) {
        boolean pac = "pacientes".equals(c.tipo().unidad());
        String obs = pac ? marca(c.observado()) + " pacientes" : conUnidad(c.observado(), c.tipo().unidad());
        return Bitacora.cortar(mostrar("Alerta analítica " + id + " · " + c.tipo().nombre() + " · " + c.nivel() + " · " + c.dimension() + " " + c.valor()
                + " · " + c.periodo() + " · observado " + obs + " · umbral " + conUnidad(c.umbral(), c.tipo().unidad()) + " · " + marca(c.pacientes())
                + " pacientes. Revise la consola de alertas de la Suite DIGSA.", k), 500);
    }
}
