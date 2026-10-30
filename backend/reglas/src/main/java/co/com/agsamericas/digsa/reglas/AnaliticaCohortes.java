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
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntToLongFunction;
import java.util.regex.Pattern;

/**
 * Cohortes de la analítica farmacéutica (docs/especificaciones/analitica-cohortes-alertas; A5 14.6, A4 §11.1 e y 11.2.4; RESP L5143): la
 * definición persistente de una cohorte como árbol de criterios combinables con Y / O y negación, su validación, su texto legible (el
 * criterio que se ve en la tarjeta), las doce cohortes predefinidas con sus criterios por defecto y la evaluación determinista por
 * paciente y mes. Sin Spring ni JDBC: el servicio arma, por paciente, los meses con lo que dispensó y pide aquí el resultado.
 *
 * <p><b>Unidad de evaluación.</b> Un paciente es miembro de una cohorte en un mes calendario en que tuvo dispensaciones si cumple el
 * criterio con las ventanas que terminan en ese mes (A4 L787: periodicidad mensual). Los criterios de código (CIE-10, ATC, principio
 * activo, medicamento, lista de referencia, Fuerza, ESM, modalidad) se cumplen si alguna dispensación de la ventana los cumple; los de
 * patrón miran el mes (principios activos distintos), los meses consecutivos (polimedicado, A4 L275), el gasto en ventana móvil (alto costo,
 * con el umbral del corte) o los diagnósticos crónicos distintos de la ventana. GCF y GRC son particiones: cada paciente del mes cae en un
 * solo grupo.
 *
 * <p><b>Metodología de GCF y GRC</b> (RESP L2245–2249: la operacionalización es de la solución técnica; RESP L5361–5367: documentada):
 * <ul>
 * <li>GCF (grupo de consumo farmacéutico): el ATC de nivel 2 con mayor gasto del paciente en el mes; empate ⇒ el código menor en orden
 * alfabético; sin ATC conocido ⇒ «SIN_ATC»; sin gasto ⇒ «SIN_GASTO».</li>
 * <li>GRC (grupo de riesgo clínico): n.º de diagnósticos crónicos distintos (categoría CIE-10 de 3 caracteres de la lista de crónicos) en
 * la ventana: 0 ⇒ GRC1, 1 ⇒ GRC2, 2 o 3 ⇒ GRC3, 4 o más ⇒ GRC4; además, quien es de alto costo en el mes queda en GRC4.</li>
 * </ul>
 */
public final class AnaliticaCohortes {

    private AnaliticaCohortes() {
    }

    /** Máximo de criterios de código por cohorte (cada uno es un «átomo» de la evaluación). */
    public static final int MAX_ATOMOS = 64;
    public static final int MAX_VALORES = 200;
    public static final int MAX_PROFUNDIDAD = 4;
    public static final int VENTANA_MAXIMA = 60;
    public static final Pattern CODIGO = Pattern.compile("^[a-z_]{3,40}$");

    /**
     * Categorías CIE-10 de enfermedad crónica por defecto (pluripatológicos y GRC): hipertensión, diabetes, dislipidemia, EPOC, asma,
     * enfermedad renal crónica, cardiopatía isquémica, fibrilación auricular, falla cardiaca, hipotiroidismo, esquizofrenia, trastorno
     * bipolar, depresión, epilepsia, artritis reumatoide, artrosis, cáncer (C), VIH, cirrosis e hiperplasia prostática. Parametrizable en
     * cada cohorte (duda 4: la lista oficial de crónicos la define la DIGSA).
     */
    public static final List<String> CRONICOS_DEFECTO = List.of("I10", "I11", "I12", "I13", "I15", "E10", "E11", "E12", "E13", "E14", "E78", "J44",
            "J45", "N18", "I25", "I48", "I50", "E03", "F20", "F31", "F32", "F33", "G40", "M05", "M06", "M15", "M16", "M17", "C", "B20", "B21", "B22",
            "B23", "B24", "K74", "N40");
    /** ATC de los biológicos por defecto (spec): L04AB, L04AC y L01F. */
    public static final List<String> BIOLOGICOS_DEFECTO = List.of("L04AB", "L04AC", "L01F");

    // ---------- Criterios ----------

    /** Tipo de un criterio de código. */
    public enum TipoCodigo {
        CIE10("cie10", "CIE-10"), ATC("atc", "ATC"), PRINCIPIO("principio", "principio activo"), MEDICAMENTO("medicamento", "medicamento"),
        LISTA("lista", "lista"), FUERZA("fuerza", "Fuerza"), ESM("esm", "ESM"), MODALIDAD("modalidad", "modalidad");

        private final String codigo;
        private final String etiqueta;

        TipoCodigo(String codigo, String etiqueta) {
            this.codigo = codigo;
            this.etiqueta = etiqueta;
        }

        public String codigo() {
            return codigo;
        }

        public String etiqueta() {
            return etiqueta;
        }

        /** ¿Se decide por el medicamento de la dispensación (tabla maestra o insumo)? */
        public boolean deMedicamento() {
            return this == ATC || this == PRINCIPIO || this == MEDICAMENTO || this == LISTA;
        }

        static TipoCodigo de(String c) {
            for (TipoCodigo t : values()) {
                if (t.codigo.equals(c)) {
                    return t;
                }
            }
            throw new IllegalArgumentException("Tipo de criterio desconocido: «" + c + "».");
        }
    }

    /** Listas de referencia admitidas en un criterio «lista». */
    public enum Lista {
        ESTRECHO_MARGEN("estrecho_margen", "estrecho margen terapéutico", true), CONTROL_ESPECIAL("control_especial", "control especial", true),
        HUERFANAS("huerfanas", "enfermedades huérfanas", false);

        private final String codigo;
        private final String etiqueta;
        private final boolean deMedicamento;

        Lista(String codigo, String etiqueta, boolean deMedicamento) {
            this.codigo = codigo;
            this.etiqueta = etiqueta;
            this.deMedicamento = deMedicamento;
        }

        public String codigo() {
            return codigo;
        }

        public String etiqueta() {
            return etiqueta;
        }

        /** Por principio activo del medicamento (estrecho margen, control especial) o por CIE-10 (huérfanas). */
        public boolean deMedicamento() {
            return deMedicamento;
        }

        public static Lista de(String c) {
            for (Lista l : values()) {
                if (l.codigo.equals(c)) {
                    return l;
                }
            }
            throw new IllegalArgumentException("Lista de referencia desconocida: «" + c + "».");
        }
    }

    /** Un criterio de la cohorte (árbol). {@code negar} invierte el resultado. */
    public sealed interface Criterio permits Grupo, Codigos, PrincipiosMes, PrincipiosConsecutivos, GastoMovil, Cronicos, Particion {
        boolean negar();
    }

    /** Combinación Y / O de criterios. */
    public record Grupo(boolean todos, List<Criterio> criterios, boolean negar) implements Criterio {
    }

    /** Alguna dispensación de la ventana (meses que terminan en el mes evaluado) tiene uno de los valores (CIE-10 y ATC por prefijo). */
    public record Codigos(TipoCodigo tipo, List<String> valores, int ventanaMeses, boolean negar) implements Criterio {
    }

    /** n.º de principios activos distintos dispensados en el mes ≥ mínimo (poliformulado). */
    public record PrincipiosMes(int minimo, boolean negar) implements Criterio {
    }

    /** ≥ mínimo principios activos distintos en cada mes durante ≥ meses consecutivos que terminan en el mes (polimedicado, A4 L275). */
    public record PrincipiosConsecutivos(int minimo, int meses, boolean negar) implements Criterio {
    }

    /**
     * Gasto del paciente en la ventana móvil ≥ umbral. Modo «percentil»: el percentil (percentile_disc) del gasto en esa ventana de los
     * pacientes con dispensaciones en el mes; modo «absoluto»: el valor en pesos.
     */
    public record GastoMovil(int meses, boolean percentil, int valorPercentil, BigDecimal valor, boolean negar) implements Criterio {
    }

    /** n.º de diagnósticos crónicos distintos (categoría CIE-10 de 3 caracteres) en la ventana ≥ mínimo. */
    public record Cronicos(int minimo, List<String> prefijos, int ventanaMeses, boolean negar) implements Criterio {
    }

    /** Partición GCF o GRC (no se niega ni se combina). */
    public record Particion(String metodo, List<String> cronicos, int ventanaMeses, GastoMovil altoCosto) implements Criterio {
        @Override
        public boolean negar() {
            return false;
        }

        public boolean gcf() {
            return "gcf".equals(metodo);
        }
    }

    // ---------- Lectura y validación (JSON ⇄ criterio) ----------

    private static final Pattern ATC = Pattern.compile("^[A-Z](\\d{2}([A-Z]([A-Z](\\d{2})?)?)?)?$");
    private static final Pattern CIE = Pattern.compile("^[A-Z](\\d(\\d[0-9A-Z]?)?)?$");
    private static final Pattern CUM = Pattern.compile("^\\d{1,9}-\\d{1,3}$");
    private static final Pattern TEXTO = Pattern.compile("^[\\p{L}\\p{N} ,./()+-]{1,200}$");
    private static final Pattern FUERZA = Pattern.compile("^[A-Z]{2,5}$");
    private static final Pattern ESM = Pattern.compile("^[A-Z0-9][A-Z0-9_.-]{0,39}$");

    /**
     * Lee un criterio del JSON ya decodificado (mapas, listas, números y textos). Lo que no es válido lanza
     * {@link IllegalArgumentException} con un mensaje para el usuario.
     */
    public static Criterio leer(Object json) {
        Criterio c = leer(json, 0);
        int atomos = atomos(c).size();
        if (atomos > MAX_ATOMOS) {
            throw new IllegalArgumentException("La cohorte tiene " + atomos + " criterios de código; el máximo es " + MAX_ATOMOS + ".");
        }
        if (c instanceof Grupo g && contiene(g, Particion.class)) {
            throw new IllegalArgumentException("Una partición (GCF o GRC) no se combina con otros criterios.");
        }
        // El motor calcula un solo umbral de gasto y una sola lista de crónicos por cohorte: dos distintos se evaluarían mal en silencio
        Set<String> gastos = new java.util.HashSet<>();
        Set<List<String>> cron = new java.util.HashSet<>();
        recorrer(c, x -> {
            if (x instanceof GastoMovil g) {
                gastos.add(g.meses() + "|" + g.percentil() + "|" + g.valorPercentil() + "|" + g.valor());
            } else if (x instanceof Particion p && p.altoCosto() != null) {
                GastoMovil g = p.altoCosto();
                gastos.add(g.meses() + "|" + g.percentil() + "|" + g.valorPercentil() + "|" + g.valor());
            }
            if (x instanceof Cronicos k) {
                cron.add(k.prefijos());
            }
        });
        if (gastos.size() > 1) {
            throw new IllegalArgumentException("Una cohorte admite un solo umbral de gasto móvil (repita el mismo en cada criterio de gasto).");
        }
        if (cron.size() > 1) {
            throw new IllegalArgumentException("Una cohorte admite una sola lista de diagnósticos crónicos (repita la misma en cada criterio).");
        }
        return c;
    }

    private static void recorrer(Criterio c, java.util.function.Consumer<Criterio> f) {
        f.accept(c);
        if (c instanceof Grupo g) {
            g.criterios().forEach(x -> recorrer(x, f));
        }
    }

    /** Tope del umbral absoluto de gasto (pesos): más allá desbordaría al pasar a centavos. */
    public static final BigDecimal TOPE_GASTO = new BigDecimal("1000000000000");

    /**
     * Meses del inicio de la historia en que un miembro no puede contarse como «ingreso» porque la cohorte aún no se puede determinar
     * (censura por la izquierda; revisión A4 2026-10-24): los meses consecutivos que exige el criterio (polimedicados: 6), mínimo 1.
     */
    public static int mesesCalentamiento(Criterio c) {
        int[] r = {1};
        recorrer(c, x -> {
            if (x instanceof PrincipiosConsecutivos p) {
                r[0] = Math.max(r[0], p.meses());
            }
        });
        return r[0];
    }

    private static boolean contiene(Criterio c, Class<?> clase) {
        if (clase.isInstance(c)) {
            return true;
        }
        return c instanceof Grupo g && g.criterios().stream().anyMatch(x -> contiene(x, clase));
    }

    @SuppressWarnings("unchecked")
    private static Criterio leer(Object json, int profundidad) {
        if (!(json instanceof Map<?, ?> m0)) {
            throw new IllegalArgumentException("Cada criterio es un objeto con «tipo».");
        }
        if (profundidad > MAX_PROFUNDIDAD) {
            throw new IllegalArgumentException("Los criterios anidan más de " + MAX_PROFUNDIDAD + " niveles.");
        }
        Map<String, Object> m = (Map<String, Object>) m0;
        String tipo = texto(m.get("tipo"));
        boolean negar = Boolean.TRUE.equals(m.get("negar"));
        if (tipo == null) {
            throw new IllegalArgumentException("Falta el tipo del criterio.");
        }
        return switch (tipo) {
            case "grupo" -> {
                String op = texto(m.get("op"));
                if (!"Y".equals(op) && !"O".equals(op)) {
                    throw new IllegalArgumentException("El grupo de criterios combina con «Y» u «O».");
                }
                if (!(m.get("criterios") instanceof List<?> l) || l.isEmpty() || l.size() > 30) {
                    throw new IllegalArgumentException("Un grupo lleva de 1 a 30 criterios.");
                }
                List<Criterio> hijos = new ArrayList<>();
                for (Object o : l) {
                    hijos.add(leer(o, profundidad + 1));
                }
                yield new Grupo("Y".equals(op), List.copyOf(hijos), negar);
            }
            case "principiosMes" -> new PrincipiosMes(entero(m.get("minimo"), 2, 100, "El mínimo de principios activos"), negar);
            case "principiosConsecutivos" -> new PrincipiosConsecutivos(entero(m.get("minimo"), 2, 100, "El mínimo de principios activos"),
                    entero(m.get("meses"), 2, VENTANA_MAXIMA, "Los meses consecutivos"), negar);
            case "gastoMovil" -> gasto(m, negar);
            case "cronicos" -> new Cronicos(entero(m.get("minimo"), 1, 20, "El mínimo de diagnósticos crónicos"), prefijosCie(m.get("prefijos")),
                    ventana(m.get("ventanaMeses")), negar);
            case "particion" -> {
                String metodo = texto(m.get("metodo"));
                if (!"gcf".equals(metodo) && !"grc".equals(metodo)) {
                    throw new IllegalArgumentException("La partición es «gcf» o «grc».");
                }
                List<String> cron = "grc".equals(metodo) ? prefijosCie(m.get("cronicos")) : List.of();
                GastoMovil ac = "grc".equals(metodo) && m.get("altoCosto") instanceof Map<?, ?> a ? gasto((Map<String, Object>) a, false) : null;
                yield new Particion(metodo, cron, "grc".equals(metodo) ? ventana(m.get("ventanaMeses")) : 1, ac);
            }
            default -> {
                TipoCodigo t = TipoCodigo.de(tipo);
                if (!(m.get("valores") instanceof List<?> l) || l.isEmpty() || l.size() > MAX_VALORES) {
                    throw new IllegalArgumentException("El criterio de " + t.etiqueta() + " lleva de 1 a " + MAX_VALORES + " valores.");
                }
                TreeSet<String> vs = new TreeSet<>();
                for (Object o : l) {
                    String v = normalizarValor(t, texto(o));
                    if (v == null) {
                        throw new IllegalArgumentException("El valor «" + Bitacora.cortar(String.valueOf(o), 40) + "» no es válido para " + t.etiqueta() + ".");
                    }
                    vs.add(v);
                }
                yield new Codigos(t, List.copyOf(vs), ventana(m.get("ventanaMeses")), negar);
            }
        };
    }

    private static GastoMovil gasto(Map<String, Object> m, boolean negar) {
        int meses = m.get("meses") == null ? 12 : entero(m.get("meses"), 1, 36, "La ventana del gasto");
        String modo = texto(m.get("modo"));
        if (modo == null || "percentil".equals(modo)) {
            return new GastoMovil(meses, true, m.get("percentil") == null ? 99 : entero(m.get("percentil"), 50, 99, "El percentil"), null, negar);
        }
        if (!"absoluto".equals(modo)) {
            throw new IllegalArgumentException("El umbral del gasto es «percentil» o «absoluto».");
        }
        BigDecimal v;
        try {
            v = new BigDecimal(String.valueOf(m.get("valor")).strip().replace(',', '.')).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("El valor del umbral de gasto no es un número.");
        }
        if (v.signum() <= 0) {
            throw new IllegalArgumentException("El valor del umbral de gasto debe ser mayor que cero.");
        }
        if (v.compareTo(TOPE_GASTO) > 0) {
            throw new IllegalArgumentException("El valor del umbral de gasto va hasta $ 1000000000000.");
        }
        return new GastoMovil(meses, false, 0, v, negar);
    }

    private static int ventana(Object o) {
        return o == null ? 12 : entero(o, 1, VENTANA_MAXIMA, "La ventana de observación (meses)");
    }

    private static List<String> prefijosCie(Object o) {
        if (o == null) {
            return CRONICOS_DEFECTO;
        }
        if (!(o instanceof List<?> l) || l.isEmpty() || l.size() > MAX_VALORES) {
            throw new IllegalArgumentException("La lista de diagnósticos crónicos lleva de 1 a " + MAX_VALORES + " prefijos CIE-10.");
        }
        TreeSet<String> r = new TreeSet<>();
        for (Object x : l) {
            String v = normalizarValor(TipoCodigo.CIE10, texto(x));
            if (v == null) {
                throw new IllegalArgumentException("El prefijo CIE-10 «" + Bitacora.cortar(String.valueOf(x), 20) + "» no es válido.");
            }
            r.add(v);
        }
        return List.copyOf(r);
    }

    private static String texto(Object o) {
        return o == null ? null : String.valueOf(o).strip();
    }

    private static int entero(Object o, int min, int max, String que) {
        int v;
        try {
            v = o instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(o).strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(que + " debe ser un número entero.");
        }
        if (v < min || v > max) {
            throw new IllegalArgumentException(que + " va de " + min + " a " + max + ".");
        }
        return v;
    }

    /** Valor de un criterio normalizado (CIE-10 sin punto y en mayúsculas, ATC en mayúsculas, principio como texto comparable); nulo si no vale. */
    public static String normalizarValor(TipoCodigo t, String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String s = v.strip();
        return switch (t) {
            case CIE10 -> {
                String c = s.toUpperCase(Locale.ROOT).replace(".", "");
                yield CIE.matcher(c).matches() ? c : null;
            }
            case ATC -> {
                String c = s.toUpperCase(Locale.ROOT);
                yield ATC.matcher(c).matches() ? c : null;
            }
            case PRINCIPIO -> TEXTO.matcher(s).matches() ? AnaliticaHomologacion.normalizarTexto(s) : null;
            case MEDICAMENTO -> CUM.matcher(s).matches() ? s : null;
            case LISTA -> {
                try {
                    yield Lista.de(s).codigo();
                } catch (IllegalArgumentException e) {
                    yield null;
                }
            }
            case FUERZA -> FUERZA.matcher(s.toUpperCase(Locale.ROOT)).matches() ? s.toUpperCase(Locale.ROOT) : null;
            case ESM -> ESM.matcher(s.toUpperCase(Locale.ROOT)).matches() ? s.toUpperCase(Locale.ROOT) : null;
            case MODALIDAD -> s.matches("^[\\p{L} ]{2,40}$") ? s : null;
        };
    }

    /** JSON canónico del criterio (lo que se guarda en la versión). */
    public static Map<String, Object> aMapa(Criterio c) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (c) {
            case Grupo g -> {
                m.put("tipo", "grupo");
                m.put("op", g.todos() ? "Y" : "O");
                m.put("criterios", g.criterios().stream().map(AnaliticaCohortes::aMapa).toList());
            }
            case Codigos k -> {
                m.put("tipo", k.tipo().codigo());
                m.put("valores", k.valores());
                m.put("ventanaMeses", k.ventanaMeses());
            }
            case PrincipiosMes p -> {
                m.put("tipo", "principiosMes");
                m.put("minimo", p.minimo());
            }
            case PrincipiosConsecutivos p -> {
                m.put("tipo", "principiosConsecutivos");
                m.put("minimo", p.minimo());
                m.put("meses", p.meses());
            }
            case GastoMovil g -> {
                m.put("tipo", "gastoMovil");
                m.putAll(mapaGasto(g));
            }
            case Cronicos k -> {
                m.put("tipo", "cronicos");
                m.put("minimo", k.minimo());
                m.put("prefijos", k.prefijos());
                m.put("ventanaMeses", k.ventanaMeses());
            }
            case Particion p -> {
                m.put("tipo", "particion");
                m.put("metodo", p.metodo());
                if (!p.gcf()) {
                    m.put("cronicos", p.cronicos());
                    m.put("ventanaMeses", p.ventanaMeses());
                    if (p.altoCosto() != null) {
                        m.put("altoCosto", mapaGasto(p.altoCosto()));
                    }
                }
            }
        }
        if (c.negar()) {
            m.put("negar", true);
        }
        return m;
    }

    private static Map<String, Object> mapaGasto(GastoMovil g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("meses", g.meses());
        m.put("modo", g.percentil() ? "percentil" : "absoluto");
        if (g.percentil()) {
            m.put("percentil", g.valorPercentil());
        } else {
            m.put("valor", g.valor().toPlainString());
        }
        return m;
    }

    // ---------- Texto del criterio (tarjeta) ----------

    /** Criterio en texto legible, generado desde la definición (14.6: «los criterios de cohorte son consultables»). */
    public static String texto(Criterio c) {
        String t = switch (c) {
            case Grupo g -> {
                List<String> partes = g.criterios().stream().map(x -> x instanceof Grupo ? "(" + texto(x) + ")" : texto(x)).toList();
                yield String.join(g.todos() ? " y " : " o ", partes);
            }
            case Codigos k -> {
                String vs = k.tipo() == TipoCodigo.LISTA ? String.join(" o ", k.valores().stream().map(x -> "lista de " + Lista.de(x).etiqueta()).toList())
                        : k.tipo().etiqueta() + " " + unir(k.valores().stream().map(v -> k.tipo() == TipoCodigo.CIE10 ? conPunto(v) : v).toList());
                yield vs + (k.ventanaMeses() == 1 ? " en el mes" : " en " + k.ventanaMeses() + " meses");
            }
            case PrincipiosMes p -> "≥ " + p.minimo() + " principios activos distintos en el mes";
            case PrincipiosConsecutivos p -> "≥ " + p.minimo() + " principios activos distintos en cada mes durante ≥ " + p.meses() + " meses consecutivos";
            case GastoMovil g -> textoGasto(g);
            case Cronicos k -> "≥ " + k.minimo() + " diagnósticos crónicos distintos en " + k.ventanaMeses() + " meses";
            case Particion p -> p.gcf() ? "Partición: ATC nivel 2 con mayor gasto del paciente en el mes (empate: el código menor)"
                    : "Partición: GRC1 sin crónicos, GRC2 un crónico, GRC3 dos o tres, GRC4 cuatro o más" + (p.altoCosto() == null ? ""
                            : " o alto costo (" + textoGasto(p.altoCosto()) + ")") + "; crónicos en " + p.ventanaMeses() + " meses";
        };
        return c.negar() ? "no (" + t + ")" : t;
    }

    private static String textoGasto(GastoMovil g) {
        return "gasto en " + g.meses() + " meses móviles ≥ " + (g.percentil() ? "percentil " + g.valorPercentil() + " del gasto por paciente"
                : AnaliticaConsulta.moneda(g.valor()));
    }

    private static String unir(List<String> v) {
        if (v.size() <= 1) {
            return String.join("", v);
        }
        if (v.size() > 8) {
            return String.join(", ", v.subList(0, 8)) + " y " + (v.size() - 8) + " más";
        }
        return String.join(", ", v.subList(0, v.size() - 1)) + " o " + v.getLast();
    }

    private static String conPunto(String c) {
        return c.length() == 4 && Character.isDigit(c.charAt(3)) ? c.substring(0, 3) + "." + c.substring(3) : c;
    }

    // ---------- Predefinidas ----------

    /** Cohorte predefinida del A4: código, nombre, descripción y criterio por defecto (parametrizable, no eliminable). */
    public record Predefinida(String codigo, String nombre, String descripcion, Map<String, Object> criterio) {
    }

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            r.put((String) kv[i], kv[i + 1]);
        }
        return r;
    }

    /** Las doce del A4 §11.1 e y 11.2.4, en el orden de la pantalla (las seis del mockup primero). */
    public static final List<Predefinida> PREDEFINIDAS = List.of(
            new Predefinida("alto_costo", "Alto costo", "Pacientes cuyo gasto en 12 meses móviles alcanza el percentil 99 del gasto por paciente (A4 L304)",
                    m("tipo", "gastoMovil", "meses", 12, "modo", "percentil", "percentil", 99)),
            new Predefinida("polimedicados", "Polimedicados", "Cinco o más medicamentos durante seis meses o más (A4 L275)",
                    m("tipo", "principiosConsecutivos", "minimo", 5, "meses", 6)),
            new Predefinida("renal", "Enfermedad renal", "Diagnóstico de enfermedad renal crónica, diálisis o trasplante renal",
                    m("tipo", "cie10", "valores", List.of("N18", "N19", "Z49", "Z992"), "ventanaMeses", 12)),
            new Predefinida("vih", "VIH", "Diagnóstico de VIH o antirretrovirales",
                    m("tipo", "grupo", "op", "O", "criterios", List.of(m("tipo", "cie10", "valores", List.of("B20", "B21", "B22", "B23", "B24", "Z21"),
                            "ventanaMeses", 12), m("tipo", "atc", "valores", List.of("J05AE", "J05AF", "J05AG", "J05AR"), "ventanaMeses", 12)))),
            new Predefinida("biologicos", "Biológicos", "Pacientes con medicamentos biológicos (por defecto ATC L04AB, L04AC y L01F)",
                    m("tipo", "atc", "valores", BIOLOGICOS_DEFECTO, "ventanaMeses", 1)),
            new Predefinida("huerfanas", "Enfermedades huérfanas", "Diagnóstico en la lista de enfermedades huérfanas cargada como referencia",
                    m("tipo", "lista", "valores", List.of("huerfanas"), "ventanaMeses", 12)),
            new Predefinida("poliformulados", "Poliformulados", "Cinco o más principios activos distintos en el mismo mes (A4 11.2.4)",
                    m("tipo", "principiosMes", "minimo", 5)),
            new Predefinida("pluripatologicos", "Pluripatológicos", "Dos o más diagnósticos crónicos distintos en 12 meses (A4 11.2.4)",
                    m("tipo", "cronicos", "minimo", 2, "prefijos", CRONICOS_DEFECTO, "ventanaMeses", 12)),
            new Predefinida("control_especial", "Control especial", "Medicamentos de la referencia de control especial (V61)",
                    m("tipo", "lista", "valores", List.of("control_especial"), "ventanaMeses", 1)),
            new Predefinida("estrecho_margen", "Estrecho margen terapéutico", "Principios activos de la referencia de estrecho margen (V61)",
                    m("tipo", "lista", "valores", List.of("estrecho_margen"), "ventanaMeses", 1)),
            new Predefinida("gcf", "Grupos de consumo farmacéutico (GCF)", "Partición por el ATC de nivel 2 de mayor gasto del paciente en el mes",
                    m("tipo", "particion", "metodo", "gcf")),
            new Predefinida("grc", "Grupos de riesgo clínico (GRC)", "Partición por diagnósticos crónicos y alto costo",
                    m("tipo", "particion", "metodo", "grc", "cronicos", CRONICOS_DEFECTO, "ventanaMeses", 12, "altoCosto",
                            m("meses", 12, "modo", "percentil", "percentil", 99))));

    /** Las seis del mockup, que van primero en las tarjetas. */
    public static final List<String> DEL_MOCKUP = List.of("alto_costo", "polimedicados", "renal", "vih", "biologicos", "huerfanas");

    // ---------- Sensibilidad (A7.1, AN.31) ----------

    /** ¿El código y alguno de los prefijos se solapan? (uno es prefijo del otro: «B2» cubre B20 y «B201» está dentro de B20) */
    public static boolean solapa(String valor, java.util.Collection<String> prefijos) {
        if (valor == null || valor.isEmpty()) {
            return false;
        }
        for (String p : prefijos) {
            if (!p.isEmpty() && (valor.startsWith(p) || p.startsWith(valor))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Códigos sensibles de la configuración (A7.1, AN.31): los CIE-10 y ATC de los criterios de las cohortes sensibles (VIH, huérfanas) y
     * sus listas de referencia (la de huérfanas aporta además sus CIE-10 cargados). Una cohorte es sensible si su criterio usa alguno (por
     * prefijo, en cualquier sentido, negado o no: prudente); un filtro de CIE-10, ATC o grupo de GCF, si su valor se solapa con ellos.
     */
    public record Sensibles(Set<String> cie10, Set<String> atc, Set<String> listas) {

        public static final Sensibles NINGUNO = new Sensibles(Set.of(), Set.of(), Set.of());

        public Sensibles {
            cie10 = Set.copyOf(cie10);
            atc = Set.copyOf(atc);
            listas = Set.copyOf(listas);
        }

        /** Desde los criterios de las cohortes sensibles y los CIE-10 de sus listas cargadas (p. ej. huérfanas). */
        public static Sensibles de(List<Criterio> criterios, java.util.Collection<String> cie10Listas) {
            Set<String> cie = new TreeSet<>(cie10Listas);
            Set<String> atc = new TreeSet<>();
            Set<String> listas = new TreeSet<>();
            for (Criterio c : criterios) {
                for (Codigos x : atomos(c)) {
                    switch (x.tipo()) {
                        case CIE10 -> cie.addAll(x.valores());
                        case ATC -> atc.addAll(x.valores());
                        case LISTA -> listas.addAll(x.valores());
                        default -> {
                        }
                    }
                }
            }
            return new Sensibles(cie, atc, listas);
        }

        public boolean cie10(String v) {
            return solapa(v, cie10);
        }

        public boolean atc(String v) {
            return solapa(v, atc);
        }

        /**
         * ¿El criterio usa un código o una lista sensible? Los conteos de crónicos (pluripatológicos) y las particiones no: su lista de crónicos
         * incluye B20–B24 entre ~35 categorías y pertenecer no revela el diagnóstico (decisión A7.1, AN.31).
         */
        public boolean criterio(Criterio c) {
            return switch (c) {
                case Grupo g -> g.criterios().stream().anyMatch(this::criterio);
                case Codigos x -> switch (x.tipo()) {
                    case CIE10 -> x.valores().stream().anyMatch(this::cie10);
                    case ATC -> x.valores().stream().anyMatch(this::atc);
                    case LISTA -> x.valores().stream().anyMatch(listas::contains);
                    default -> false;
                };
                default -> false;
            };
        }
    }

    // ---------- Átomos ----------

    /** Criterio de código del árbol (en el orden en que aparece): su bit en las máscaras del mes es su posición. */
    public static List<Codigos> atomos(Criterio c) {
        List<Codigos> r = new ArrayList<>();
        recolectar(c, r);
        return r;
    }

    private static void recolectar(Criterio c, List<Codigos> r) {
        if (c instanceof Codigos k) {
            r.add(k);
        } else if (c instanceof Grupo g) {
            g.criterios().forEach(x -> recolectar(x, r));
        }
    }

    /** Listas de referencia que usa el criterio (para decir «no evaluado» si falta alguna). */
    public static Set<Lista> listas(Criterio c) {
        Set<Lista> r = new java.util.TreeSet<>();
        for (Codigos k : atomos(c)) {
            if (k.tipo() == TipoCodigo.LISTA) {
                k.valores().forEach(v -> r.add(Lista.de(v)));
            }
        }
        return r;
    }

    /** Gasto móvil del criterio (alto costo; el de la partición GRC incluido), o nulo. */
    public static GastoMovil gastoMovil(Criterio c) {
        return switch (c) {
            case GastoMovil g -> g;
            case Particion p -> p.altoCosto();
            case Grupo g -> g.criterios().stream().map(AnaliticaCohortes::gastoMovil).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            default -> null;
        };
    }

    /** Prefijos de crónicos que usa el criterio (uno por cohorte), o vacío. */
    public static List<String> cronicos(Criterio c) {
        return switch (c) {
            case Cronicos k -> k.prefijos();
            case Particion p -> p.cronicos();
            case Grupo g -> g.criterios().stream().map(AnaliticaCohortes::cronicos).filter(l -> !l.isEmpty()).findFirst().orElse(List.of());
            default -> List.of();
        };
    }

    /** ¿El código empieza por alguno de los prefijos? */
    public static boolean conPrefijo(String codigo, List<String> prefijos) {
        if (codigo == null) {
            return false;
        }
        for (String p : prefijos) {
            if (codigo.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** Categoría crónica (3 caracteres) de un código CIE-10 si está en la lista; nulo si no. */
    public static String categoriaCronica(String cie, List<String> prefijos) {
        return cie != null && cie.length() >= 3 && conPrefijo(cie, prefijos) ? cie.substring(0, 3) : null;
    }

    // ---------- Evaluación por paciente ----------

    /**
     * Principios activos distintos de un mes: cuenta el principio (normalizado) y no el CUM, así un cambio de marca no suma medicamentos;
     * un medicamento sin principio conocido cuenta como uno por sí mismo (su llave).
     */
    public static int principiosDistintos(java.util.Collection<String> llaves) {
        return (int) llaves.stream().filter(java.util.Objects::nonNull).distinct().count();
    }

    /**
     * Lo que el paciente dispensó en un mes, ya reducido para UNA cohorte.
     *
     * @param mes        ordinal del mes (año × 12 + mes − 1)
     * @param atomos     bits de los criterios de código que cumplió alguna dispensación del mes (bit i = átomo i)
     * @param principios principios activos distintos del mes (sin principio conocido: cada medicamento cuenta como uno)
     * @param gasto      gasto del mes en centavos
     * @param cronicos   categorías crónicas (3 caracteres) diagnosticadas en el mes
     * @param gastoAtc2  gasto del mes por ATC de nivel 2 (GCF), en centavos
     */
    public record MesPaciente(int mes, long atomos, int principios, long gasto, Set<String> cronicos, Map<String, Long> gastoAtc2) {
    }

    /**
     * Resultado de un paciente: para cada mes de la entrada (mismo orden), si es miembro (cohortes) o su grupo (particiones). Determinista:
     * mismos meses y mismo umbral ⇒ mismo resultado.
     */
    public record Resultado(boolean[] miembro, String[] grupo) {
    }

    /**
     * Evalúa la cohorte para los meses de un paciente (en orden creciente, sin repetir).
     *
     * @param umbralGasto umbral del gasto móvil (centavos) por ordinal del mes; un valor negativo = no hay umbral ese mes (no cumple)
     */
    public static Resultado evaluar(Criterio c, List<MesPaciente> meses, IntToLongFunction umbralGasto) {
        int n = meses.size();
        boolean[] miembro = new boolean[n];
        String[] grupo = new String[n];
        List<Codigos> at = atomos(c);
        int[] ultimo = new int[at.size()];
        Arrays.fill(ultimo, Integer.MIN_VALUE / 2);
        Map<String, Integer> ultimoCronico = new HashMap<>();
        int racha = 0;
        int mesAnterior = Integer.MIN_VALUE / 2;
        long[] gastoPorMes = new long[n];
        Estado e = new Estado(at, ultimo, ultimoCronico);
        PrincipiosConsecutivos pc = consecutivos(c);
        for (int i = 0; i < n; i++) {
            MesPaciente mp = meses.get(i);
            for (int a = 0; a < at.size(); a++) {
                if ((mp.atomos() >>> a & 1L) != 0) {
                    ultimo[a] = mp.mes();
                }
            }
            for (String k : mp.cronicos()) {
                ultimoCronico.put(k, mp.mes());
            }
            gastoPorMes[i] = mp.gasto();
            e.i = i;
            e.mes = mp;
            e.meses = meses;
            e.gastos = gastoPorMes;
            e.umbral = umbralGasto;
            if (pc != null) {
                racha = mp.principios() >= pc.minimo() ? (mp.mes() == mesAnterior + 1 ? racha + 1 : 1) : 0;
            }
            e.racha = racha;
            mesAnterior = mp.mes();
            if (c instanceof Particion p) {
                grupo[i] = p.gcf() ? grupoGcf(mp) : grupoGrc(p, e);
                miembro[i] = true;
            } else {
                miembro[i] = cumple(c, e);
            }
        }
        return new Resultado(miembro, grupo);
    }

    private static PrincipiosConsecutivos consecutivos(Criterio c) {
        return switch (c) {
            case PrincipiosConsecutivos p -> p;
            case Grupo g -> g.criterios().stream().map(AnaliticaCohortes::consecutivos).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            default -> null;
        };
    }

    /** Estado de la evaluación en el mes i (último mes visto de cada átomo y de cada crónico, racha de meses consecutivos). */
    private static final class Estado {
        final List<Codigos> atomos;
        final int[] ultimo;
        final Map<String, Integer> ultimoCronico;
        int i;
        int racha;
        MesPaciente mes;
        List<MesPaciente> meses;
        long[] gastos;
        IntToLongFunction umbral;

        Estado(List<Codigos> atomos, int[] ultimo, Map<String, Integer> ultimoCronico) {
            this.atomos = atomos;
            this.ultimo = ultimo;
            this.ultimoCronico = ultimoCronico;
        }

        /** Gasto en la ventana de {@code meses} que termina en el mes actual. */
        long gastoVentana(int ventana) {
            long s = 0;
            int desde = mes.mes() - ventana + 1;
            for (int k = i; k >= 0 && meses.get(k).mes() >= desde; k--) {
                s += gastos[k];
            }
            return s;
        }

        int cronicosVentana(int ventana) {
            int desde = mes.mes() - ventana + 1;
            int r = 0;
            for (int u : ultimoCronico.values()) {
                if (u >= desde) {
                    r++;
                }
            }
            return r;
        }
    }

    private static boolean cumple(Criterio c, Estado e) {
        boolean r = switch (c) {
            case Grupo g -> g.todos() ? g.criterios().stream().allMatch(x -> cumple(x, e)) : g.criterios().stream().anyMatch(x -> cumple(x, e));
            case Codigos k -> {
                int idx = indice(e.atomos, k);
                yield e.ultimo[idx] >= e.mes.mes() - k.ventanaMeses() + 1;
            }
            case PrincipiosMes p -> e.mes.principios() >= p.minimo();
            case PrincipiosConsecutivos p -> e.racha >= p.meses();
            case GastoMovil g -> altoCosto(g, e);
            case Cronicos k -> e.cronicosVentana(k.ventanaMeses()) >= k.minimo();
            case Particion p -> true;
        };
        return c.negar() != r;
    }

    /** Posición del átomo por identidad (dos criterios iguales en el árbol son átomos distintos). */
    private static int indice(List<Codigos> atomos, Codigos k) {
        for (int i = 0; i < atomos.size(); i++) {
            if (atomos.get(i) == k) {
                return i;
            }
        }
        throw new IllegalStateException("Átomo desconocido");
    }

    private static boolean altoCosto(GastoMovil g, Estado e) {
        long gasto = e.gastoVentana(g.meses());
        long umbral = g.percentil() ? e.umbral.applyAsLong(e.mes.mes()) : g.valor().movePointRight(2).longValueExact();
        return umbral >= 0 && gasto > 0 && gasto >= umbral;
    }

    /** GCF: el ATC nivel 2 de mayor gasto del mes; empate ⇒ el menor en orden alfabético. */
    public static String grupoGcf(MesPaciente mp) {
        String mejor = null;
        long max = Long.MIN_VALUE;
        for (Map.Entry<String, Long> x : new java.util.TreeMap<>(mp.gastoAtc2()).entrySet()) {
            if (x.getValue() > max) {
                max = x.getValue();
                mejor = x.getKey();
            }
        }
        if (mp.gasto() <= 0) {
            return "SIN_GASTO";
        }
        return mejor == null || max <= 0 ? "SIN_ATC" : mejor;
    }

    private static String grupoGrc(Particion p, Estado e) {
        if (p.altoCosto() != null && altoCosto(p.altoCosto(), e)) {
            return "GRC4";
        }
        int n = e.cronicosVentana(p.ventanaMeses());
        return n == 0 ? "GRC1" : n == 1 ? "GRC2" : n <= 3 ? "GRC3" : "GRC4";
    }

    /** Nombre legible de un grupo de partición. */
    public static String etiquetaGrupo(String g) {
        return switch (g) {
            case "SIN_ATC" -> "Sin ATC conocido";
            case "SIN_GASTO" -> "Sin gasto";
            case "GRC1" -> "GRC1 · sin diagnóstico crónico";
            case "GRC2" -> "GRC2 · un diagnóstico crónico";
            case "GRC3" -> "GRC3 · dos o tres crónicos";
            case "GRC4" -> "GRC4 · cuatro o más crónicos o alto costo";
            default -> g;
        };
    }

    // ---------- Umbral de alto costo ----------

    /**
     * Percentil discreto como {@code percentile_disc} de PostgreSQL: el primer valor (ordenado) cuya posición alcanza la fracción; -1 si no
     * hay valores.
     */
    public static long percentilDiscreto(long[] ordenados, int percentil) {
        if (ordenados.length == 0) {
            return -1;
        }
        int pos = (int) Math.ceil(percentil / 100.0 * ordenados.length);
        return ordenados[Math.max(0, Math.min(ordenados.length, pos) - 1)];
    }

    /** Ordinal de un mes (año × 12 + mes − 1). */
    public static int ordinal(int anio, int mes) {
        return anio * 12 + mes - 1;
    }

    public static java.time.YearMonth deOrdinal(int o) {
        return java.time.YearMonth.of(Math.floorDiv(o, 12), Math.floorMod(o, 12) + 1);
    }
}
