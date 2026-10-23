package co.com.agsamericas.digsa.reglas;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Homologación de medicamentos y diagnósticos contra las referencias oficiales (docs/especificaciones/analitica-homologacion; A5 4.7.2;
 * PLIEGO P1–P3): normalización de texto, CUM, registro sanitario, CIE-10 y ATC; lectura de concentración, forma y presentación del
 * texto del insumo; reglas deterministas R-0xx en su orden con estado único por precedencia; duplicados; agrupación de las filas del
 * CUM del INVIMA (una por principio activo y rol) en presentación, principios y roles; y los porcentajes de calidad que suman 100.
 *
 * <p>Las reglas son genéricas (formatos, referencias oficiales, catálogos parametrizables y tolerancias): no contienen valores del
 * insumo de la prueba (RESP L3943: detectar sin conocer de antemano). Misma entrada, mismas versiones de referencias y de reglas dan el
 * mismo resultado, sin depender del orden, la hora ni la IA. Solo JDK.
 */
public final class AnaliticaHomologacion {

    // ---------- Estados y precedencia ----------

    /** Estado único de un dato homologado; el rango fija la precedencia (A5 L679–691). */
    public enum Estado {
        HOMOLOGADO("homologado", "Homologado", 0),
        PENDIENTE("pendiente", "Pendiente de revisión", 1),
        SIN_CORRESPONDENCIA("sin_correspondencia", "Sin correspondencia", 2),
        INCONSISTENTE("inconsistente", "Inconsistente", 3),
        DUPLICADO("duplicado", "Duplicado", 4),
        NO_VALIDO("no_valido", "CUM o registro no válido", 5);

        private final String codigo;
        private final String texto;
        private final int rango;

        Estado(String codigo, String texto, int rango) {
            this.codigo = codigo;
            this.texto = texto;
            this.rango = rango;
        }

        public String codigo() {
            return codigo;
        }

        public String texto() {
            return texto;
        }

        public static Optional<Estado> deCodigo(String codigo) {
            for (Estado e : values()) {
                if (e.codigo.equals(codigo)) {
                    return Optional.of(e);
                }
            }
            return Optional.empty();
        }
    }

    /** Precedencia cuando aplican varias: no válido > duplicado > inconsistente > sin correspondencia > pendiente > homologado. */
    public static Estado precedente(Collection<Estado> estados) {
        Estado r = Estado.HOMOLOGADO;
        for (Estado e : estados) {
            if (e.rango > r.rango) {
                r = e;
            }
        }
        return r;
    }

    // ---------- Reglas ----------

    public static final String R001 = "R-001";
    public static final String R002 = "R-002";
    public static final String R003 = "R-003";
    public static final String R004 = "R-004";
    public static final String R010 = "R-010";
    public static final String R011 = "R-011";
    public static final String R012 = "R-012";
    public static final String R013 = "R-013";
    public static final String R015 = "R-015";
    public static final String R016 = "R-016";
    public static final String R021 = "R-021";
    public static final String R030 = "R-030";
    public static final String R040 = "R-040";
    public static final String R041 = "R-041";
    public static final String R050 = "R-050";
    public static final String R060 = "R-060";
    /** Decisión humana en «Revisión» (no es una regla automática). */
    public static final String MANUAL = "MANUAL";

    /** Definición inicial de una regla (la misma que siembra V61 en hom_regla). Las obligatorias no se pueden desactivar. */
    public record DefinicionRegla(String id, String nombre, int orden, boolean obligatoria, Map<String, String> parametros) {
    }

    /** Reglas iniciales en su orden (los ids del mockup se conservan: R-004, R-012, R-015, R-021, R-030). */
    public static final List<DefinicionRegla> REGLAS = List.of(
            new DefinicionRegla(R002, "Formato del CUM", 10, true, Map.of()),
            new DefinicionRegla(R001, "CUM exacto en la versión publicada", 20, true, Map.of()),
            new DefinicionRegla(R021, "CUM vencido, en otros estados o inactivo", 30, false, Map.of()),
            new DefinicionRegla(R003, "Registro sanitario y su vigencia a la fecha de dispensación", 40, false, Map.of()),
            new DefinicionRegla(R010, "Texto normalizado y abreviaturas", 50, false, Map.of()),
            new DefinicionRegla(R011, "Unidades y conversión g ↔ mg ↔ mcg", 60, false, Map.of()),
            new DefinicionRegla(R004, "Sinónimos DCI", 70, false, Map.of()),
            new DefinicionRegla(R013, "Principio activo con sales toleradas", 80, false, Map.of()),
            new DefinicionRegla(R012, "Concentración", 90, false, Map.of("toleranciaPorcentaje", "0")),
            new DefinicionRegla(R015, "Forma farmacéutica con equivalencias y tolerancia", 100, false, Map.of("tolerancia", "0")),
            new DefinicionRegla(R016, "Presentación (cantidad del empaque)", 110, false, Map.of()),
            new DefinicionRegla(R041, "Corrección automática de incompletos", 120, false, Map.of()),
            new DefinicionRegla(R030, "Código interno SSFM", 130, false, Map.of()),
            new DefinicionRegla(R040, "Duplicados", 140, false, Map.of()),
            new DefinicionRegla(R050, "Sin correspondencia", 150, true, Map.of()),
            new DefinicionRegla(R060, "CIE-10", 160, false, Map.of()));

    /** Regla configurada (versión vigente de hom_regla). */
    public record Regla(String id, int orden, boolean activa, Map<String, String> parametros) {
        public Regla {
            parametros = parametros == null ? Map.of() : Map.copyOf(parametros);
        }
    }

    /** Forma farmacéutica canónica y su familia (sólida oral, parenteral…) para la tolerancia de R-015. */
    public record Forma(String canonica, String familia) {
    }

    /**
     * Reglas y catálogos vigentes.
     *
     * @param sinonimos     nombre normalizado ⇒ DCI (ACETAMINOFEN ⇒ PARACETAMOL)
     * @param sales         palabras de sal toleradas (POTASICO, CLORHIDRATO…)
     * @param abreviaturas  abreviatura ⇒ texto (TAB ⇒ TABLETA)
     * @param unidades      variante ⇒ unidad canónica (MILIGRAMOS ⇒ MG)
     * @param formas        variante normalizada ⇒ forma canónica y familia
     */
    public record Configuracion(Map<String, Regla> reglas, Map<String, String> sinonimos, Set<String> sales, Map<String, String> abreviaturas,
                                Map<String, String> unidades, Map<String, Forma> formas) {

        public Configuracion {
            reglas = Map.copyOf(reglas);
            sinonimos = Map.copyOf(sinonimos);
            sales = Set.copyOf(sales);
            abreviaturas = Map.copyOf(abreviaturas);
            unidades = Map.copyOf(unidades);
            formas = Map.copyOf(formas);
        }

        /** Una regla que no está configurada cuenta como activa con sus parámetros iniciales. */
        public boolean activa(String id) {
            Regla r = reglas.get(id);
            return r == null || r.activa();
        }

        public String parametro(String regla, String clave, String defecto) {
            Regla r = reglas.get(regla);
            String v = r == null ? null : r.parametros().get(clave);
            return v == null || v.isBlank() ? defecto : v.strip();
        }

        public int orden(String id) {
            Regla r = reglas.get(id);
            if (r != null) {
                return r.orden();
            }
            return REGLAS.stream().filter(d -> d.id().equals(id)).mapToInt(DefinicionRegla::orden).findFirst().orElse(999);
        }

        /** Con las reglas y catálogos iniciales (los mismos que siembra V61). */
        public static Configuracion porDefecto() {
            Map<String, Regla> r = new LinkedHashMap<>();
            for (DefinicionRegla d : REGLAS) {
                r.put(d.id(), new Regla(d.id(), d.orden(), true, d.parametros()));
            }
            return new Configuracion(r, SINONIMOS_INICIALES, SALES_INICIALES, ABREVIATURAS_INICIALES, UNIDADES_INICIALES, FORMAS_INICIALES);
        }

        /** Copia con una regla cambiada (pruebas y simulaciones). */
        public Configuracion con(Regla regla) {
            Map<String, Regla> r = new LinkedHashMap<>(reglas);
            r.put(regla.id(), regla);
            return new Configuracion(r, sinonimos, sales, abreviaturas, unidades, formas);
        }
    }

    // ---------- Catálogos iniciales (genéricos; parametrizables desde la pantalla) ----------

    /** Tipos de catálogo de hom_catalogo. */
    public static final List<String> TIPOS_CATALOGO = List.of("sinonimo_dci", "sal", "abreviatura", "unidad", "forma");

    public static final Map<String, String> SINONIMOS_INICIALES = Map.ofEntries(
            Map.entry("ACETAMINOFEN", "PARACETAMOL"), Map.entry("ACETAMINOFENO", "PARACETAMOL"), Map.entry("ALBUTEROL", "SALBUTAMOL"),
            Map.entry("ADRENALINA", "EPINEFRINA"), Map.entry("NORADRENALINA", "NOREPINEFRINA"), Map.entry("DIPIRONA", "METAMIZOL"),
            Map.entry("ACIDO ACETIL SALICILICO", "ACIDO ACETILSALICILICO"), Map.entry("ASA", "ACIDO ACETILSALICILICO"),
            Map.entry("PETIDINA", "MEPERIDINA"), Map.entry("XILOCAINA", "LIDOCAINA"));

    public static final Set<String> SALES_INICIALES = Set.of("POTASICO", "POTASICA", "SODICO", "SODICA", "CALCICO", "CALCICA", "MAGNESICO",
            "MAGNESICA", "CLORHIDRATO", "HIDROCLORURO", "HCL", "MALEATO", "BESILATO","MESILATO", "SUCCINATO", "TARTRATO",
            "FUMARATO", "SULFATO", "FOSFATO", "ACETATO", "BROMURO", "CITRATO", "LACTATO", "GLUCONATO", "DIHIDRATO", "TRIHIDRATO",
            "MONOHIDRATO", "HEMIHIDRATO", "SESQUIHIDRATO", "ANHIDRO", "ANHIDRA", "DIPROPIONATO", "PROPIONATO", "VALERATO", "XINAFOATO",
            "FUROATO", "TROMETAMOL", "DISODICO", "DISODICA", "BITARTRATO", "CLORURO", "NITRATO", "OXALATO", "PAMOATO", "ESTOLATO", "BASE");

    public static final Map<String, String> ABREVIATURAS_INICIALES = Map.ofEntries(
            Map.entry("TAB", "TABLETA"), Map.entry("TABS", "TABLETA"), Map.entry("TABLETAS", "TABLETA"), Map.entry("CAP", "CAPSULA"),
            Map.entry("CAPS", "CAPSULA"), Map.entry("CAPSULAS", "CAPSULA"), Map.entry("JER", "JERINGA"), Map.entry("JERINGAS", "JERINGA"),
            Map.entry("AMP", "AMPOLLA"), Map.entry("AMPOLLAS", "AMPOLLA"), Map.entry("SUSP", "SUSPENSION"), Map.entry("SOL", "SOLUCION"),
            Map.entry("INY", "INYECTABLE"), Map.entry("GTS", "GOTAS"), Map.entry("CR", "CREMA"), Map.entry("UNG", "UNGUENTO"),
            Map.entry("FCO", "FRASCO"), Map.entry("INH", "INHALADOR"), Map.entry("REC", "RECUBIERTA"), Map.entry("RECUB", "RECUBIERTA"),
            Map.entry("PLV", "POLVO"), Map.entry("JBE", "JARABE"), Map.entry("SUP", "SUPOSITORIO"), Map.entry("OVU", "OVULO"),
            Map.entry("VIALES", "VIAL"), Map.entry("PLUMAS", "PLUMA"), Map.entry("LIB", "LIBERACION"), Map.entry("PROL", "PROLONGADA"));

    public static final Map<String, String> UNIDADES_INICIALES = Map.ofEntries(
            Map.entry("MG", "MG"), Map.entry("MGS", "MG"), Map.entry("MILIGRAMO", "MG"), Map.entry("MILIGRAMOS", "MG"),
            Map.entry("G", "G"), Map.entry("GR", "G"), Map.entry("GRS", "G"), Map.entry("GRAMO", "G"), Map.entry("GRAMOS", "G"),
            Map.entry("MCG", "MCG"), Map.entry("UG", "MCG"), Map.entry("MICROGRAMO", "MCG"), Map.entry("MICROGRAMOS", "MCG"),
            Map.entry("UI", "UI"), Map.entry("U", "UI"), Map.entry("UNIDADES", "UI"), Map.entry("UNIDAD INTERNACIONAL", "UI"),
            Map.entry("ML", "ML"), Map.entry("MILILITRO", "ML"), Map.entry("MILILITROS", "ML"), Map.entry("CC", "ML"),
            Map.entry("%", "%"), Map.entry("MEQ", "MEQ"));

    public static final Map<String, Forma> FORMAS_INICIALES = Map.ofEntries(
            Map.entry("TABLETA", new Forma("TABLETA", "SOLIDA ORAL")),
            Map.entry("TABLETA RECUBIERTA", new Forma("TABLETA RECUBIERTA", "SOLIDA ORAL")),
            Map.entry("TABLETA CUBIERTA CON PELICULA", new Forma("TABLETA RECUBIERTA", "SOLIDA ORAL")),
            Map.entry("TABLETA CON CUBIERTA PELICULAR", new Forma("TABLETA RECUBIERTA", "SOLIDA ORAL")),
            Map.entry("TABLETA DE LIBERACION PROLONGADA", new Forma("TABLETA DE LIBERACION PROLONGADA", "SOLIDA ORAL")),
            Map.entry("TABLETA MASTICABLE", new Forma("TABLETA MASTICABLE", "SOLIDA ORAL")),
            Map.entry("GRAGEA", new Forma("TABLETA RECUBIERTA", "SOLIDA ORAL")),
            Map.entry("CAPSULA", new Forma("CAPSULA", "SOLIDA ORAL")), Map.entry("CAPSULA DURA", new Forma("CAPSULA", "SOLIDA ORAL")),
            Map.entry("CAPSULA BLANDA", new Forma("CAPSULA BLANDA", "SOLIDA ORAL")),
            Map.entry("SOLUCION INYECTABLE", new Forma("SOLUCION INYECTABLE", "PARENTERAL")),
            Map.entry("INYECTABLE", new Forma("SOLUCION INYECTABLE", "PARENTERAL")),
            Map.entry("JERINGA PRELLENADA", new Forma("SOLUCION INYECTABLE", "PARENTERAL")),
            Map.entry("JERINGA", new Forma("SOLUCION INYECTABLE", "PARENTERAL")),
            Map.entry("PLUMA", new Forma("SOLUCION INYECTABLE", "PARENTERAL")),
            Map.entry("AMPOLLA", new Forma("SOLUCION INYECTABLE", "PARENTERAL")),
            Map.entry("VIAL", new Forma("POLVO PARA SOLUCION INYECTABLE", "PARENTERAL")),
            Map.entry("POLVO PARA SOLUCION INYECTABLE", new Forma("POLVO PARA SOLUCION INYECTABLE", "PARENTERAL")),
            Map.entry("POLVO LIOFILIZADO PARA RECONSTITUIR A SOLUCION INYECTABLE", new Forma("POLVO PARA SOLUCION INYECTABLE", "PARENTERAL")),
            Map.entry("SUSPENSION INYECTABLE", new Forma("SUSPENSION INYECTABLE", "PARENTERAL")),
            Map.entry("INHALADOR", new Forma("AEROSOL PARA INHALACION", "INHALATORIA")),
            Map.entry("AEROSOL PARA INHALACION", new Forma("AEROSOL PARA INHALACION", "INHALATORIA")),
            Map.entry("SUSPENSION PARA INHALACION", new Forma("AEROSOL PARA INHALACION", "INHALATORIA")),
            Map.entry("POLVO PARA INHALACION", new Forma("POLVO PARA INHALACION", "INHALATORIA")),
            Map.entry("SUSPENSION", new Forma("SUSPENSION ORAL", "LIQUIDA ORAL")),
            Map.entry("SUSPENSION ORAL", new Forma("SUSPENSION ORAL", "LIQUIDA ORAL")),
            Map.entry("JARABE", new Forma("JARABE", "LIQUIDA ORAL")), Map.entry("SOLUCION ORAL", new Forma("SOLUCION ORAL", "LIQUIDA ORAL")),
            Map.entry("GOTAS", new Forma("SOLUCION ORAL", "LIQUIDA ORAL")), Map.entry("CREMA", new Forma("CREMA", "TOPICA")),
            Map.entry("UNGUENTO", new Forma("UNGUENTO", "TOPICA")), Map.entry("GEL", new Forma("GEL", "TOPICA")),
            Map.entry("SUPOSITORIO", new Forma("SUPOSITORIO", "RECTAL")), Map.entry("OVULO", new Forma("OVULO", "VAGINAL")),
            Map.entry("PARCHE", new Forma("PARCHE TRANSDERMICO", "TRANSDERMICA")));

    private AnaliticaHomologacion() {
    }

    // ---------- Texto (R-010) ----------

    private static final Pattern DIGITO_LETRA = Pattern.compile("(?<=\\d)(?=[A-Z%])|(?<=[A-Z])(?=\\d)");
    private static final Pattern ESPACIOS = Pattern.compile("\\s+");

    /**
     * Texto comparable (R-010): mayúsculas, sin tildes, «Ñ» como «N», signos como espacio salvo «/», «%» y la coma o el punto entre dígitos;
     * número y unidad separados («850MG» ⇒ «850 MG», «X30» ⇒ «X 30»), barras sin espacios («UI / ML» ⇒ «UI/ML») y espacios simples.
     * Nulo o en blanco ⇒ «».
     */
    public static String normalizarTexto(String t) {
        if (t == null || t.isBlank()) {
            return "";
        }
        String s = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT).replace('Ñ', 'N');
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean entreDigitos = i > 0 && i + 1 < s.length() && Character.isDigit(s.charAt(i - 1)) && Character.isDigit(s.charAt(i + 1));
            if (c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '/' || c == '%' || (c == ',' || c == '.') && entreDigitos) {
                sb.append(c);
            } else {
                sb.append(' ');
            }
        }
        String r = DIGITO_LETRA.matcher(sb).replaceAll(" ");
        r = r.replaceAll("\\s*/\\s*", "/");
        return ESPACIOS.matcher(r).replaceAll(" ").strip();
    }

    /** Reemplaza abreviaturas y unidades palabra por palabra (TAB ⇒ TABLETA; MILIGRAMOS ⇒ MG). */
    public static String expandir(String normalizado, Map<String, String> abreviaturas, Map<String, String> unidades) {
        if (normalizado.isEmpty()) {
            return normalizado;
        }
        StringBuilder sb = new StringBuilder();
        for (String tok : normalizado.split(" ")) {
            String v = tok;
            if (tok.contains("/")) {
                List<String> partes = new ArrayList<>();
                for (String p : tok.split("/", -1)) {
                    partes.add(unidades.getOrDefault(p, p));
                }
                v = String.join("/", partes);
            } else if (abreviaturas.containsKey(tok)) {
                v = abreviaturas.get(tok);
            } else if (unidades.containsKey(tok)) {
                v = unidades.get(tok);
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(v);
        }
        return sb.toString();
    }

    // ---------- CUM (R-002) y registro sanitario (R-003) ----------

    private static final Pattern CUM_PARTES = Pattern.compile("^(\\d+)\\s*[-/.]\\s*(\\d+)$");
    /** Formato del CUM tras normalizar: expediente de 1 a 8 dígitos y consecutivo de 1 a 3. */
    public static final Pattern CUM = Pattern.compile("^\\d{1,8}-\\d{1,3}$");

    /**
     * R-002: CUM normalizado «expediente-consecutivo» sin espacios ni ceros a la izquierda, aceptando «-», «/» o «.» como separador; vacío
     * si no cumple el patrón {@code ^\d{1,8}-\d{1,3}$} (o si viene vacío).
     */
    public static Optional<String> normalizarCum(String texto) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        Matcher m = CUM_PARTES.matcher(texto.strip().replace(" ", ""));
        if (!m.matches()) {
            return Optional.empty();
        }
        String exp = sinCeros(m.group(1));
        String cons = sinCeros(m.group(2));
        String cum = exp + "-" + cons;
        return CUM.matcher(cum).matches() ? Optional.of(cum) : Optional.empty();
    }

    /** CUM con expediente y consecutivo en columnas separadas. */
    public static Optional<String> cumDe(String expediente, String consecutivo) {
        if (expediente == null || consecutivo == null) {
            return Optional.empty();
        }
        String e = entero(expediente);
        String c = entero(consecutivo);
        return e == null || c == null ? Optional.empty() : normalizarCum(e + "-" + c);
    }

    /** Número entero escrito como «19911481», «19911481.0» o «1,0» (las exportaciones de Socrata a veces agregan «.0»). */
    private static String entero(String t) {
        String s = t.strip();
        if (s.matches("^\\d+([.,]0+)?$")) {
            return s.replaceAll("[.,]0+$", "");
        }
        return null;
    }

    private static String sinCeros(String d) {
        String s = d.replaceFirst("^0+", "");
        return s.isEmpty() ? "0" : s;
    }

    private static final Pattern REGISTRO = Pattern.compile("^(INVIMA\\s*)?(\\d{4}\\s*)?[A-Z]{1,4}\\s*-?\\s*\\d{3,8}(\\s*-\\s*R\\s*\\d{1,2})?$");

    /** R-003: formato del registro sanitario («INVIMA 2023M-0013598-R2», «INVIMA M-012345», «2009M-0009560-R1»). */
    public static boolean registroValido(String registro) {
        return registro != null && REGISTRO.matcher(registro.strip().toUpperCase(Locale.ROOT)).matches();
    }

    // ---------- Fechas y números de las fuentes ----------

    private static final DateTimeFormatter MDY = DateTimeFormatter.ofPattern("M/d/uuuu").withResolverStyle(ResolverStyle.STRICT);

    /**
     * Fecha del INVIMA en datos.gov.co: texto MM/DD/YYYY («08/23/2012» ⇒ 2012-08-23); también aaaa-mm-dd y la marca de tiempo de Socrata
     * («2012-08-23T00:00:00.000»). Lo que no se puede convertir queda vacío (y se cuenta en el informe de la versión).
     */
    public static Optional<LocalDate> fechaInvima(String texto) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        String s = texto.strip();
        try {
            if (s.matches("^\\d{1,2}/\\d{1,2}/\\d{4}$")) {
                return Optional.of(LocalDate.parse(s, MDY));
            }
            if (s.matches("^\\d{4}-\\d{2}-\\d{2}([T ].*)?$")) {
                return Optional.of(LocalDate.parse(s.substring(0, 10)));
            }
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** Precio o número en texto con coma o punto decimal («1234,50», «1234.5», «$ 1.234,50») a dos decimales; vacío si no es número. */
    public static Optional<BigDecimal> precio(String texto) {
        return numero(texto).map(b -> b.setScale(2, RoundingMode.HALF_UP));
    }

    /** Número en texto con coma o punto decimal; con los dos, el último es el decimal y el otro separa miles. */
    public static Optional<BigDecimal> numero(String texto) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        String s = texto.strip().replace("$", "").replace(" ", "");
        int coma = s.lastIndexOf(',');
        int punto = s.lastIndexOf('.');
        if (coma >= 0 && punto >= 0) {
            s = coma > punto ? s.replace(".", "").replace(',', '.') : s.replace(",", "");
        } else if (coma >= 0) {
            s = s.replace(',', '.');
        }
        if (!s.matches("^-?\\d+(\\.\\d+)?$")) {
            return Optional.empty();
        }
        return Optional.of(new BigDecimal(s));
    }

    // ---------- ATC y CIE-10 ----------

    private static final Pattern ATC = Pattern.compile("^[A-Z]\\d{2}[A-Z]{2}\\d{2}$");

    /** Niveles 1 a 5 del ATC de 7 caracteres («A10BA02» ⇒ A, A10, A10B, A10BA, A10BA02); vacío si no cumple el patrón (ATC no válido). */
    public static Optional<List<String>> nivelesAtc(String atc) {
        if (atc == null) {
            return Optional.empty();
        }
        String a = atc.strip().toUpperCase(Locale.ROOT);
        if (!ATC.matcher(a).matches()) {
            return Optional.empty();
        }
        return Optional.of(List.of(a.substring(0, 1), a.substring(0, 3), a.substring(0, 4), a.substring(0, 5), a));
    }

    /** Grupo farmacológico = ATC nivel 3 (A5 4.7.2). */
    public static String grupoFarmacologico(String atc) {
        return nivelesAtc(atc).map(l -> l.get(2)).orElse(null);
    }

    /** CIE-10 normalizado: mayúsculas, sin punto, guion ni espacios («e11.9» ⇒ «E119»). */
    public static String normalizarCie10(String texto) {
        if (texto == null) {
            return "";
        }
        return normalizarTexto(texto).replace(" ", "").replace(".", "").replace(",", "").replace("/", "");
    }

    /** Resultado de R-060 para un código CIE-10 del insumo. */
    public record ResultadoCie10(String original, String codigo, Estado estado, String regla, String causa) {
    }

    /** Consulta de la CIE-10 publicada (SISPRO, código de 4 caracteres y grupo de 3). */
    public interface ReferenciaCie10 {
        boolean hayReferencia();

        boolean existe(String codigo4);

        boolean existeGrupo(String grupo3);
    }

    /**
     * R-060: «e11.9» o «E119» ⇒ E119 homologado si existe en la versión publicada; «E11» (grupo de 3) ⇒ pendiente de revisión; un código
     * inexistente o con otro formato ⇒ sin correspondencia. Sin CIE-10 publicada ⇒ pendiente.
     */
    public static ResultadoCie10 homologarCie10(String original, ReferenciaCie10 ref) {
        String c = normalizarCie10(original);
        if (c.isEmpty()) {
            return new ResultadoCie10(original, null, Estado.SIN_CORRESPONDENCIA, R060, "sin código");
        }
        if (!ref.hayReferencia()) {
            return new ResultadoCie10(original, null, Estado.PENDIENTE, R060, "sin versión publicada de la CIE-10");
        }
        if (c.matches("^[A-Z]\\d{2}[0-9A-Z]$")) {
            return ref.existe(c) ? new ResultadoCie10(original, c, Estado.HOMOLOGADO, R060, null)
                    : new ResultadoCie10(original, null, Estado.SIN_CORRESPONDENCIA, R060, "el código no existe en la CIE-10 publicada");
        }
        if (c.matches("^[A-Z]\\d{2}$")) {
            return ref.existeGrupo(c) ? new ResultadoCie10(original, null, Estado.PENDIENTE, R060, "código de 3 caracteres (grupo " + c + ")")
                    : new ResultadoCie10(original, null, Estado.SIN_CORRESPONDENCIA, R060, "el grupo no existe en la CIE-10 publicada");
        }
        return new ResultadoCie10(original, null, Estado.SIN_CORRESPONDENCIA, R060, "formato de CIE-10 no válido");
    }

    // ---------- Concentración, presentación, forma y principio activo (R-011 a R-016) ----------

    /**
     * Concentración leída: valores en la unidad canónica (mg para masa; UI, ml, % y mEq tal cual), unidad canónica (nula si el número vino
     * sin unidad: se compara solo el valor) y unidad de referencia («/ML»). «50/250 MCG» ⇒ [0,05; 0,25] MG.
     */
    public record Concentracion(List<BigDecimal> valores, String unidad, String por, String texto) {
        public Concentracion {
            valores = List.copyOf(valores);
        }
    }

    private static final Pattern CONC = Pattern.compile(
            "(?<!\\bX )(?<![X\\d])(\\d+(?:[.,]\\d+)?(?:/\\d+(?:[.,]\\d+)?)*) ?(MG|G|MCG|UI|ML|%|MEQ)(?:/(ML|G|L|DOSIS|\\d+ ?ML))?(?![A-Z])");
    private static final Pattern NUMERO_SUELTO = Pattern.compile("(?<!\\bX )(?<![X/\\d.,])\\b(\\d+(?:[.,]\\d+)?)\\b(?![/\\d])");
    private static final Pattern PRESENTACION = Pattern.compile("\\bX ?(\\d{1,5})\\b");

    /** R-011/R-012: concentración del texto ya expandido («850 MG», «0,4 MG», «100 UI/ML», «50/250 MCG»; «500» sin unidad). */
    public static Optional<Concentracion> concentracion(String expandido) {
        if (expandido == null || expandido.isEmpty()) {
            return Optional.empty();
        }
        Matcher m = CONC.matcher(expandido);
        if (m.find()) {
            String unidad = m.group(2);
            List<BigDecimal> valores = new ArrayList<>();
            for (String v : m.group(1).split("/")) {
                valores.add(aCanonica(new BigDecimal(v.replace(',', '.')), unidad));
            }
            return Optional.of(new Concentracion(valores, canonica(unidad), m.group(3), m.group(0)));
        }
        // Un número suelto que no es la cantidad del empaque («ACETAMINOFEN 500 TABLETA»): se compara solo el valor
        Matcher s = NUMERO_SUELTO.matcher(expandido);
        if (s.find()) {
            return Optional.of(new Concentracion(List.of(new BigDecimal(s.group(1).replace(',', '.'))), null, null, s.group(1)));
        }
        return Optional.empty();
    }

    /** Concentración de un CUM a partir de la cantidad y la unidad de cada principio activo (en el orden de los principios). */
    public static Optional<Concentracion> concentracionCum(List<PrincipioCum> principios, Map<String, String> unidades) {
        List<BigDecimal> valores = new ArrayList<>();
        String unidad = null;
        StringBuilder texto = new StringBuilder();
        for (PrincipioCum p : principios) {
            if (p.cantidad() == null || p.unidad() == null) {
                return Optional.empty();
            }
            String u = normalizarTexto(p.unidad());
            u = unidades.getOrDefault(u, u);
            valores.add(aCanonica(p.cantidad(), u));
            unidad = unidad == null ? canonica(u) : unidad;
            if (!texto.isEmpty()) {
                texto.append(" + ");
            }
            texto.append(p.cantidad().stripTrailingZeros().toPlainString().replace('.', ',')).append(' ').append(p.unidad().strip().toLowerCase(Locale.ROOT));
        }
        return valores.isEmpty() ? Optional.empty() : Optional.of(new Concentracion(valores, unidad, null, texto.toString()));
    }

    private static String canonica(String u) {
        return switch (u) {
            case "G", "MCG" -> "MG";
            default -> u;
        };
    }

    private static BigDecimal aCanonica(BigDecimal v, String unidad) {
        return switch (unidad) {
            case "G" -> v.multiply(BigDecimal.valueOf(1000));
            case "MCG" -> v.divide(BigDecimal.valueOf(1000), 9, RoundingMode.HALF_UP);
            default -> v;
        };
    }

    /**
     * R-012: misma concentración (multiconjunto de valores, misma unidad canónica salvo que el texto no la traiga) con la tolerancia en
     * porcentaje.
     */
    public static boolean mismaConcentracion(Concentracion a, Concentracion b, BigDecimal toleranciaPorcentaje) {
        if (a.valores().size() != b.valores().size()) {
            return false;
        }
        if (a.unidad() != null && b.unidad() != null && !a.unidad().equals(b.unidad())) {
            return false;
        }
        List<BigDecimal> x = a.valores().stream().sorted().toList();
        List<BigDecimal> y = b.valores().stream().sorted().toList();
        for (int i = 0; i < x.size(); i++) {
            BigDecimal dif = x.get(i).subtract(y.get(i)).abs();
            BigDecimal max = y.get(i).abs().multiply(toleranciaPorcentaje).divide(BigDecimal.valueOf(100), 9, RoundingMode.HALF_UP);
            if (dif.compareTo(max) > 0) {
                return false;
            }
        }
        return true;
    }

    /** R-016: cantidad del empaque («X30», «X 30»). */
    public static Optional<Integer> presentacion(String expandido) {
        if (expandido == null) {
            return Optional.empty();
        }
        Matcher m = PRESENTACION.matcher(expandido);
        return m.find() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
    }

    /** Variante de forma más larga que aparece como palabras completas en el texto (TABLETA RECUBIERTA antes que TABLETA). */
    public static Optional<String> formaDeTexto(String expandido, Map<String, Forma> formas) {
        if (expandido == null || expandido.isEmpty()) {
            return Optional.empty();
        }
        String t = " " + expandido + " ";
        String mejor = null;
        for (String variante : formas.keySet()) {
            if (t.contains(" " + variante + " ") && (mejor == null || variante.length() > mejor.length()
                    || variante.length() == mejor.length() && variante.compareTo(mejor) < 0)) {
                mejor = variante;
            }
        }
        return Optional.ofNullable(mejor);
    }

    /** Forma canónica de un texto de forma (el de la referencia o una variante del catálogo); sin entrada, el texto mismo sin familia. */
    public static Forma forma(String texto, Map<String, Forma> formas) {
        String n = normalizarTexto(texto);
        Forma f = formas.get(n);
        if (f != null) {
            return f;
        }
        return formaDeTexto(n, formas).map(formas::get).orElse(new Forma(n, null));
    }

    /** R-015: 0 = misma forma canónica; 1 = misma familia (cápsula ≈ tableta); 2 o más = no se compara. */
    public static boolean formaCompatible(Forma original, Forma referencia, int tolerancia) {
        if (tolerancia >= 2) {
            return true;
        }
        if (original.canonica().equals(referencia.canonica())) {
            return true;
        }
        return tolerancia == 1 && original.familia() != null && original.familia().equals(referencia.familia());
    }

    /** Principio activo del texto de la descripción: las palabras antes del primer número, sin las de la forma farmacéutica. */
    public static String principioDeDescripcion(String expandido, Map<String, Forma> formas) {
        if (expandido == null || expandido.isEmpty()) {
            return "";
        }
        List<String> l = new ArrayList<>();
        for (String tok : expandido.split(" ")) {
            if (!tok.isEmpty() && Character.isDigit(tok.charAt(0))) {
                break;
            }
            l.add(tok);
        }
        String p = String.join(" ", l);
        Optional<String> f = formaDeTexto(p, formas);
        if (f.isPresent()) {
            p = (" " + p + " ").replace(" " + f.get() + " ", " ").strip();
        }
        return p;
    }

    private static final Pattern SEPARADOR_PRINCIPIOS = Pattern.compile("\\s*(?:/|\\+|,|;| Y | CON | E )\\s*");

    /** Nombres de los principios activos de un texto («SALMETEROL Y FLUTICASONA», «SALMETEROL/FLUTICASONA») sin aplicar sinónimos ni sales. */
    public static List<String> nombresPrincipios(String normalizado) {
        List<String> l = new ArrayList<>();
        if (normalizado == null || normalizado.isBlank()) {
            return l;
        }
        for (String p : SEPARADOR_PRINCIPIOS.split(" " + normalizado + " ")) {
            String s = p.strip();
            if (!s.isEmpty()) {
                l.add(s);
            }
        }
        return l;
    }

    /** R-004: DCI por sinónimo (nombre completo). */
    public static String sinonimo(String nombre, Map<String, String> sinonimos) {
        return sinonimos.getOrDefault(nombre, nombre);
    }

    /** R-013: nombre sin la sal («LOSARTAN POTASICO» ⇒ LOSARTAN; «CLORHIDRATO DE METFORMINA» ⇒ METFORMINA). */
    public static String sinSal(String nombre, Set<String> sales) {
        List<String> t = new ArrayList<>(List.of(nombre.split(" ")));
        if (t.size() > 2 && sales.contains(t.get(0)) && "DE".equals(t.get(1))) {
            t = new ArrayList<>(t.subList(2, t.size()));
        }
        while (t.size() > 1 && sales.contains(t.get(t.size() - 1))) {
            t.remove(t.size() - 1);
        }
        return String.join(" ", t);
    }

    /** Base de búsqueda de un principio de la referencia (sin la sal, con las sales iniciales más las del catálogo). */
    public static String principioBase(String principioNormalizado, Set<String> sales) {
        Set<String> s = new LinkedHashSet<>(SALES_INICIALES);
        s.addAll(sales);
        return sinSal(principioNormalizado, s);
    }

    /** Conjunto de DCI de un texto de principios con sinónimos (si R-004 está activa) y sin sales (si R-013 lo está). */
    public static Set<String> dcis(String normalizado, Configuracion c) {
        Set<String> r = new TreeSet<>();
        for (String n : nombresPrincipios(normalizado)) {
            String x = c.activa(R004) ? sinonimo(n, c.sinonimos()) : n;
            x = c.activa(R013) ? sinSal(x, c.sales()) : x;
            x = c.activa(R004) ? sinonimo(x, c.sinonimos()) : x;
            r.add(x);
        }
        return r;
    }

    // ---------- Referencias del CUM ----------

    /** Conjuntos de datos del CUM del INVIMA en orden de precedencia (vigentes, en trámite de renovación, vencidos, otros estados). */
    public static final List<String> CONJUNTOS = List.of("vigentes", "renovacion", "vencidos", "otros");

    /** Principio activo de una presentación del CUM. */
    public record PrincipioCum(String nombre, BigDecimal cantidad, String unidad, String unidadReferencia) {
    }

    /** Presentación del CUM (una por CUM y conjunto) con sus principios activos, tal como la guarda la versión publicada. */
    public record Presentacion(String cum, String conjunto, String producto, String titular, String registro, LocalDate vencimiento,
                               String estadoRegistro, String estadoCum, BigDecimal cantidadCum, String descripcionComercial, String unidad,
                               String atc, String descripcionAtc, String via, String forma, String ium, List<PrincipioCum> principios) {
        public Presentacion {
            principios = List.copyOf(principios);
        }

        /** Activo en vigentes o en trámite de renovación. */
        public boolean vigente() {
            return ("vigentes".equals(conjunto) || "renovacion".equals(conjunto)) && !"INACTIVO".equals(normalizarTexto(estadoCum));
        }
    }

    /** Código interno SSFM (catálogo de la DIGSA) por CUM o por DCI + concentración + forma. */
    public record CodigoSsfm(String codigo, String cum, String dci, String concentracion, String forma) {
    }

    /** Lo que la homologación consulta de las versiones publicadas (siempre las mismas durante una ejecución: publicación atómica). */
    public interface Referencias {
        /** ¿Hay alguna versión publicada del CUM? */
        boolean hayCum();

        /** Presentaciones con ese CUM en los conjuntos publicados (una por conjunto). */
        List<Presentacion> porCum(String cum);

        /** Presentaciones vigentes cuyo principio activo tiene esa base (sin sal). */
        List<Presentacion> porPrincipio(String base);

        /** ¿Hay una versión publicada del catálogo de códigos SSFM? (R-030 solo aplica con catálogo). */
        boolean haySsfm();

        List<CodigoSsfm> ssfm();

        /**
         * Catálogo SSFM indexado (R-030). Por defecto se arma en cada llamada; quien homologa muchos datos con las mismas referencias
         * debe devolver siempre el mismo índice (se calcula una vez por ejecución, no por dato original).
         */
        default IndiceSsfm indiceSsfm(Map<String, Forma> formas) {
            return IndiceSsfm.de(ssfm(), formas);
        }
    }

    /**
     * Catálogo SSFM normalizado una sola vez: por código (mayúsculas), por CUM y por DCI normalizada, con la forma canónica y la
     * concentración normalizada de cada entrada. R-030 pasa de recorrer el catálogo por cada dato original a una búsqueda por llave.
     */
    public static final class IndiceSsfm {
        /** Entrada genérica (sin CUM) por DCI, en el orden del catálogo (por código). */
        private record Generico(String codigo, String forma, String concentracion) {
        }

        private final Map<String, String> porCodigo = new HashMap<>();
        private final Map<String, String> porCum = new HashMap<>();
        private final Map<String, List<Generico>> porDci = new HashMap<>();

        private IndiceSsfm() {
        }

        /** Índice del catálogo con las formas del catálogo vigente (la primera entrada de cada llave gana, como el recorrido en orden). */
        public static IndiceSsfm de(List<CodigoSsfm> catalogo, Map<String, Forma> formas) {
            IndiceSsfm i = new IndiceSsfm();
            for (CodigoSsfm s : catalogo) {
                i.porCodigo.putIfAbsent(s.codigo().strip().toUpperCase(Locale.ROOT), s.codigo());
                if (s.cum() != null) {
                    i.porCum.putIfAbsent(s.cum(), s.codigo());
                } else if (s.dci() != null) {
                    i.porDci.computeIfAbsent(normalizarTexto(s.dci()), k -> new ArrayList<>()).add(new Generico(s.codigo(),
                            s.forma() == null ? null : forma(s.forma(), formas).canonica(),
                            s.concentracion() == null ? null : normalizarTexto(s.concentracion())));
                }
            }
            return i;
        }

        /** Código del catálogo igual (sin distinguir mayúsculas) al del insumo. */
        public Optional<String> porCodigo(String codigo) {
            return codigo == null ? Optional.empty() : Optional.ofNullable(porCodigo.get(codigo.strip().toUpperCase(Locale.ROOT)));
        }

        public Optional<String> porCum(String cum) {
            return cum == null ? Optional.empty() : Optional.ofNullable(porCum.get(cum));
        }

        /** Primer código genérico con esa DCI cuya forma y concentración (si las tiene) coinciden; nulas en un lado no restringen. */
        public Optional<String> porDci(String dciNormalizada, String formaCanonica, String concentracionNormalizada) {
            if (dciNormalizada == null) {
                return Optional.empty();
            }
            for (Generico g : porDci.getOrDefault(dciNormalizada, List.of())) {
                if ((g.forma() == null || formaCanonica == null || g.forma().equals(formaCanonica))
                        && (g.concentracion() == null || concentracionNormalizada == null || g.concentracion().equals(concentracionNormalizada))) {
                    return Optional.of(g.codigo());
                }
            }
            return Optional.empty();
        }
    }

    /** Dato original (llave estable: CUM normalizado + descripción normalizada). */
    public record Origen(long id, String cumOriginal, String descripcion, String principioActivo, String atc, String codigoSsfm,
                         LocalDate fechaReferencia) {
    }

    /** Paso de una regla que transformó el dato: valor antes y después. */
    public record Paso(String regla, String antes, String despues) {
    }

    /** Dato original tal como se leyó (para la tabla maestra). */
    public record Original(String cum, String descripcion, String principioActivo, String concentracion, String forma, String presentacion,
                           String unidad, String codigoSsfm, String atc) {
    }

    /** Dato homologado final (las tablas paramétricas del A5 4.7.2 que aplican a un medicamento). */
    public record DatoFinal(String nombreComercial, String dci, String principioActivo, String concentracion, String forma, String presentacion,
                            String unidad, String cum, String codigoSsfm, String atc, String grupoFarmacologico, String registroSanitario) {
    }

    /**
     * Resultado de la homologación de un dato original.
     *
     * @param regla       regla que decidió el estado
     * @param causa       explicación corta (sin datos personales)
     * @param diferencias diferencias encontradas (obligan «inconsistente»)
     * @param pasos       reglas que transformaron el dato, en orden
     * @param dato        dato final (nulo si es no válido o sin correspondencia)
     * @param fuente      presentación del CUM consultada (nula si no hubo)
     * @param candidatos  CUM candidatos para la revisión
     */
    public record Resultado(long origenId, Estado estado, String regla, String causa, List<String> diferencias, List<Paso> pasos, DatoFinal dato,
                            Presentacion fuente, List<String> candidatos, Original original) {
        public Resultado {
            diferencias = List.copyOf(diferencias);
            pasos = List.copyOf(pasos);
            candidatos = List.copyOf(candidatos);
        }

        Resultado conEstado(Estado e, String r, String c) {
            return new Resultado(origenId, e, r, c, diferencias, pasos, dato, fuente, candidatos, original);
        }
    }

    // ---------- Homologación de un dato original ----------

    /** Diferencia hallada por una regla. */
    private record Diferencia(String regla, String texto) {
    }

    /**
     * Homologa un dato original con las reglas activas en su orden y deja un solo estado por precedencia.
     *
     * @param o   dato original
     * @param c   reglas y catálogos vigentes
     * @param ref versiones publicadas de las referencias
     */
    public static Resultado homologar(Origen o, Configuracion c, Referencias ref) {
        List<Paso> pasos = new ArrayList<>();
        // R-010 / R-011: texto comparable con abreviaturas y unidades
        String descripcion = normalizarTexto(o.descripcion());
        String expandido = c.activa(R010) || c.activa(R011)
                ? expandir(descripcion, c.activa(R010) ? c.abreviaturas() : Map.of(), c.activa(R011) ? c.unidades() : Map.of()) : descripcion;
        if (o.descripcion() != null && !o.descripcion().strip().equals(expandido) && !expandido.isEmpty()) {
            pasos.add(new Paso(c.activa(R010) ? R010 : R011, o.descripcion().strip(), expandido));
        }
        Optional<Concentracion> conc = concentracion(expandido);
        Optional<String> formaTexto = formaDeTexto(expandido, c.formas());
        Optional<Integer> empaque = presentacion(expandido);
        String principioTexto = o.principioActivo() != null && !o.principioActivo().isBlank()
                ? expandir(normalizarTexto(o.principioActivo()), c.activa(R010) ? c.abreviaturas() : Map.of(), Map.of())
                : principioDeDescripcion(expandido, c.formas());
        Set<String> dcis = dcis(principioTexto, c);
        Set<String> sinSinonimos = new TreeSet<>();
        for (String n : nombresPrincipios(principioTexto)) {
            sinSinonimos.add(c.activa(R013) ? sinSal(n, c.sales()) : n);
        }
        boolean usoSinonimo = c.activa(R004) && !sinSinonimos.equals(dcis);
        if (usoSinonimo) {
            pasos.add(new Paso(R004, String.join(" + ", sinSinonimos), String.join(" + ", dcis)));
        }
        String cumTexto = o.cumOriginal() == null ? null : o.cumOriginal().strip();
        Original original = new Original(cumTexto == null || cumTexto.isEmpty() ? null : cumTexto, vacioANulo(expandido),
                vacioANulo(principioTexto), conc.map(Concentracion::texto).orElse(null), formaTexto.orElse(null),
                empaque.map(n -> "X " + n).orElse(null), conc.map(Concentracion::unidad).orElse(null), vacioANulo(o.codigoSsfm()),
                vacioANulo(o.atc()));

        if (cumTexto != null && !cumTexto.isEmpty()) {
            return porCum(o, c, ref, pasos, original, cumTexto, dcis, usoSinonimo, conc, formaTexto, empaque);
        }
        return porDescripcion(o, c, ref, pasos, original, dcis, usoSinonimo, conc, formaTexto, empaque);
    }

    private static Resultado porCum(Origen o, Configuracion c, Referencias ref, List<Paso> pasos, Original original, String cumTexto,
                                    Set<String> dcis, boolean usoSinonimo, Optional<Concentracion> conc, Optional<String> formaTexto,
                                    Optional<Integer> empaque) {
        Optional<String> n = normalizarCum(cumTexto);
        if (n.isEmpty()) {
            return new Resultado(o.id(), Estado.NO_VALIDO, R002, "formato de CUM no válido", List.of(), pasos, null, null, List.of(), original);
        }
        String cum = n.get();
        if (!cum.equals(cumTexto)) {
            pasos.add(new Paso(R002, cumTexto, cum));
        }
        if (!ref.hayCum()) {
            return new Resultado(o.id(), Estado.SIN_CORRESPONDENCIA, R050, "sin versión publicada del CUM del INVIMA", List.of(), pasos, null, null,
                    List.of(), original);
        }
        List<Presentacion> hallados = new ArrayList<>(ref.porCum(cum));
        if (hallados.isEmpty()) {
            List<String> candidatos = candidatos(dcis, c, ref).stream().map(Presentacion::cum).distinct().limit(20).toList();
            return new Resultado(o.id(), Estado.SIN_CORRESPONDENCIA, R050, "el CUM no existe en las versiones publicadas del INVIMA", List.of(), pasos,
                    null, null, candidatos, original);
        }
        hallados.sort(Comparator.comparingInt(p -> CONJUNTOS.indexOf(p.conjunto())));
        Presentacion p = hallados.getFirst();
        // R-021: solo en vencidos u otros estados, o inactivo
        if (c.activa(R021) && !p.vigente()) {
            String porque = "INACTIVO".equals(normalizarTexto(p.estadoCum())) && ("vigentes".equals(p.conjunto()) || "renovacion".equals(p.conjunto()))
                    ? "CUM inactivo en el INVIMA" : "CUM en el conjunto de " + nombreConjunto(p.conjunto()) + " del INVIMA";
            return new Resultado(o.id(), Estado.NO_VALIDO, R021, porque, List.of(), pasos, null, p, List.of(), original);
        }
        // R-003: registro sanitario con formato y vigente a la fecha de dispensación (en trámite de renovación se tolera)
        if (c.activa(R003)) {
            if (!registroValido(p.registro())) {
                return new Resultado(o.id(), Estado.NO_VALIDO, R003, "registro sanitario con formato no válido", List.of(), pasos, null, p, List.of(),
                        original);
            }
            if (!"renovacion".equals(p.conjunto()) && p.vencimiento() != null && o.fechaReferencia() != null
                    && p.vencimiento().isBefore(o.fechaReferencia())) {
                return new Resultado(o.id(), Estado.NO_VALIDO, R003, "registro sanitario vencido el " + p.vencimiento() + ", antes de la dispensación del "
                        + o.fechaReferencia(), List.of(), pasos, null, p, List.of(), original);
            }
        }
        List<Diferencia> difs = comparar(p, c, dcis, conc, formaTexto, empaque);
        List<String> completados = new ArrayList<>();
        if (c.activa(R041)) {
            if (vacioANulo(o.principioActivo()) == null) {
                completados.add("principio activo");
            }
            if (original.atc() == null && p.atc() != null) {
                completados.add("ATC");
            }
            if (!completados.isEmpty()) {
                pasos.add(new Paso(R041, "(vacío)", String.join(" y ", completados) + " completado desde CUM INVIMA"));
            }
        }
        DatoFinal dato = datoFinal(p, c, p.cum(), null);
        if (!difs.isEmpty()) {
            return inconsistente(o, c, pasos, original, difs, dato, p, List.of(p.cum()));
        }
        String regla = !completados.isEmpty() ? R041 : usoSinonimo ? R004 : R001;
        String causa = !completados.isEmpty() ? "completado desde CUM INVIMA" : usoSinonimo ? "principio activo por sinónimo DCI" : "CUM exacto vigente";
        return ssfm(o, c, ref, new Resultado(o.id(), Estado.HOMOLOGADO, regla, causa, List.of(), pasos, dato, p, List.of(), original));
    }

    private static Resultado porDescripcion(Origen o, Configuracion c, Referencias ref, List<Paso> pasos, Original original, Set<String> dcis,
                                            boolean usoSinonimo, Optional<Concentracion> conc, Optional<String> formaTexto,
                                            Optional<Integer> empaque) {
        if (dcis.isEmpty() || !ref.hayCum()) {
            return new Resultado(o.id(), Estado.SIN_CORRESPONDENCIA, R050, dcis.isEmpty() ? "sin CUM ni principio activo reconocible"
                    : "sin versión publicada del CUM del INVIMA", List.of(), pasos, null, null, List.of(), original);
        }
        List<Presentacion> cand = candidatos(dcis, c, ref);
        if (cand.isEmpty()) {
            return new Resultado(o.id(), Estado.SIN_CORRESPONDENCIA, R050, "ningún CUM vigente con ese principio activo", List.of(), pasos, null, null,
                    List.of(), original);
        }
        List<String> nombres = cand.stream().map(Presentacion::cum).distinct().limit(20).toList();
        BigDecimal tol = numero(c.parametro(R012, "toleranciaPorcentaje", "0")).orElse(BigDecimal.ZERO);
        List<Presentacion> paso = cand;
        if (c.activa(R012) && conc.isPresent()) {
            List<Presentacion> f = paso.stream().filter(p -> concentracionCum(p.principios(), c.unidades())
                    .map(x -> mismaConcentracion(conc.get(), x, tol)).orElse(false)).toList();
            if (f.isEmpty()) {
                return inconsistente(o, c, pasos, original, List.of(new Diferencia(R012, "diferencia en concentración")), null, paso.getFirst(), nombres);
            }
            paso = f;
        }
        int tolForma = tolerancia(c);
        if (c.activa(R015) && formaTexto.isPresent()) {
            Forma fo = c.formas().get(formaTexto.get());
            List<Presentacion> f = paso.stream().filter(p -> formaCompatible(fo, forma(p.forma(), c.formas()), tolForma)).toList();
            if (f.isEmpty()) {
                return inconsistente(o, c, pasos, original, List.of(new Diferencia(R015, "diferencia en forma farmacéutica (origen "
                        + formaTexto.get().toLowerCase(Locale.ROOT) + ")")), null, paso.getFirst(), nombres);
            }
            paso = f;
        }
        if (c.activa(R016) && empaque.isPresent()) {
            List<Presentacion> f = paso.stream().filter(p -> p.cantidadCum() != null && p.cantidadCum().compareTo(BigDecimal.valueOf(empaque.get())) == 0)
                    .toList();
            if (!f.isEmpty()) {
                paso = f;
            }
        }
        Presentacion p = paso.getFirst();
        boolean unico = paso.stream().map(Presentacion::cum).distinct().count() == 1;
        boolean mismoAtc = paso.stream().map(Presentacion::atc).filter(Objects::nonNull).distinct().count() <= 1;
        if (unico && c.activa(R041)) {
            pasos.add(new Paso(R041, "(sin CUM)", "CUM " + p.cum() + " completado desde la descripción (una sola presentación posible)"));
        }
        DatoFinal base = datoFinal(p, c, unico ? p.cum() : null, empaque.map(n -> "caja x " + n).orElse(null));
        DatoFinal dato = mismoAtc ? base : new DatoFinal(base.nombreComercial(), base.dci(), base.principioActivo(), base.concentracion(),
                base.forma(), base.presentacion(), base.unidad(), base.cum(), base.codigoSsfm(), null, null, base.registroSanitario());
        if (!unico) {
            dato = new DatoFinal(null, dato.dci(), dato.principioActivo(), dato.concentracion(), dato.forma(), dato.presentacion(), dato.unidad(),
                    null, dato.codigoSsfm(), dato.atc(), dato.grupoFarmacologico(), null);
        }
        String regla = usoSinonimo ? R004 : R012;
        String causa = usoSinonimo ? "principio activo por sinónimo DCI" : unico ? "una sola presentación con esa DCI, concentración y forma"
                : "DCI, concentración y forma del INVIMA (" + paso.size() + " presentaciones)";
        return ssfm(o, c, ref, new Resultado(o.id(), Estado.HOMOLOGADO, regla, causa, List.of(), pasos, dato, p, unico ? List.of() : nombres,
                original));
    }

    private static int tolerancia(Configuracion c) {
        return numero(c.parametro(R015, "tolerancia", "0")).map(BigDecimal::intValue).orElse(0);
    }

    /** Presentaciones vigentes cuyo conjunto de DCI es igual al del dato (con sinónimos y sales del catálogo), ordenadas por CUM. */
    private static List<Presentacion> candidatos(Set<String> dcis, Configuracion c, Referencias ref) {
        if (dcis.isEmpty()) {
            return List.of();
        }
        Map<String, Presentacion> r = new TreeMap<>();
        for (String clave : clavesBusqueda(dcis.iterator().next(), c)) {
            for (Presentacion p : ref.porPrincipio(clave)) {
                if (p.vigente() && dcisDe(p, c).equals(dcis)) {
                    r.putIfAbsent(p.cum(), p);
                }
            }
        }
        return new ArrayList<>(r.values());
    }

    /** Claves de búsqueda por la base del principio: la DCI y sus sinónimos (la referencia puede usar el nombre no DCI). */
    public static Set<String> clavesBusqueda(String dci, Configuracion c) {
        Set<String> s = new TreeSet<>();
        s.add(dci);
        c.sinonimos().forEach((k, v) -> {
            if (v.equals(dci)) {
                s.add(k);
            }
        });
        return s;
    }

    private static Set<String> dcisDe(Presentacion p, Configuracion c) {
        Set<String> r = new TreeSet<>();
        for (PrincipioCum pc : p.principios()) {
            r.addAll(dcis(normalizarTexto(pc.nombre()), c));
        }
        return r;
    }

    private static List<Diferencia> comparar(Presentacion p, Configuracion c, Set<String> dcis, Optional<Concentracion> conc,
                                             Optional<String> formaTexto, Optional<Integer> empaque) {
        List<Diferencia> d = new ArrayList<>();
        if (c.activa(R013) && !dcis.isEmpty() && !dcisDe(p, c).equals(dcis)) {
            d.add(new Diferencia(R013, "diferencia en principio activo"));
        }
        if (c.activa(R012) && conc.isPresent()) {
            BigDecimal tol = numero(c.parametro(R012, "toleranciaPorcentaje", "0")).orElse(BigDecimal.ZERO);
            Optional<Concentracion> cc = concentracionCum(p.principios(), c.unidades());
            if (cc.isPresent() && !mismaConcentracion(conc.get(), cc.get(), tol)) {
                d.add(new Diferencia(R012, "diferencia en concentración"));
            }
        }
        if (c.activa(R015) && formaTexto.isPresent() && p.forma() != null) {
            Forma fo = c.formas().get(formaTexto.get());
            if (!formaCompatible(fo, forma(p.forma(), c.formas()), tolerancia(c))) {
                d.add(new Diferencia(R015, "diferencia en forma farmacéutica (origen " + formaTexto.get().toLowerCase(Locale.ROOT) + ")"));
            }
        }
        if (c.activa(R016) && empaque.isPresent() && p.cantidadCum() != null && p.cantidadCum().compareTo(BigDecimal.valueOf(empaque.get())) != 0) {
            d.add(new Diferencia(R016, "diferencia en presentación"));
        }
        return d;
    }

    private static Resultado inconsistente(Origen o, Configuracion c, List<Paso> pasos, Original original, List<Diferencia> difs, DatoFinal dato,
                                           Presentacion p, List<String> candidatos) {
        Diferencia primera = difs.stream().min(Comparator.comparingInt(x -> c.orden(x.regla()))).orElseThrow();
        return new Resultado(o.id(), Estado.INCONSISTENTE, primera.regla(), primera.texto(), difs.stream().map(Diferencia::texto).toList(), pasos,
                dato, p, candidatos, original);
    }

    /** R-030: con catálogo SSFM publicado, lo homologado sin código interno queda pendiente de revisión. */
    private static Resultado ssfm(Origen o, Configuracion c, Referencias ref, Resultado r) {
        if (!c.activa(R030) || !ref.haySsfm() || r.dato() == null) {
            return r;
        }
        String propio = vacioANulo(o.codigoSsfm());
        DatoFinal d = r.dato();
        IndiceSsfm indice = ref.indiceSsfm(c.formas());
        String codigo;
        if (propio != null) {
            codigo = indice.porCodigo(propio).orElse(null);
            if (codigo == null) {
                return r.conEstado(Estado.PENDIENTE, R030, "el código SSFM del insumo no está en el catálogo de la DIGSA");
            }
        } else {
            String dci = d.dci() == null ? null : normalizarTexto(d.dci());
            String forma = d.forma() == null ? null : forma(d.forma(), c.formas()).canonica();
            codigo = indice.porCum(d.cum()).or(() -> indice.porDci(dci, forma, d.concentracion() == null ? null : normalizarTexto(d.concentracion())))
                    .orElse(null);
            if (codigo == null) {
                return r.conEstado(Estado.PENDIENTE, R030, "sin código interno SSFM para el medicamento");
            }
        }
        DatoFinal nuevo = new DatoFinal(d.nombreComercial(), d.dci(), d.principioActivo(), d.concentracion(), d.forma(), d.presentacion(), d.unidad(),
                d.cum(), codigo, d.atc(), d.grupoFarmacologico(), d.registroSanitario());
        return new Resultado(r.origenId(), r.estado(), r.regla(), r.causa(), r.diferencias(), r.pasos(), nuevo, r.fuente(), r.candidatos(), r.original());
    }

    /** Dato final tomado de una presentación del INVIMA (aprobación manual con el CUM elegido en «Revisión»). */
    public static DatoFinal datoDe(Presentacion p, Configuracion c) {
        return datoFinal(p, c, p.cum(), null);
    }

    /**
     * CUM normalizados y bases de principio activo que la homologación de este dato puede consultar (para traer de la base solo esas
     * presentaciones antes de homologar un lote de datos originales).
     */
    public static Set<String> cumsDe(Origen o) {
        return normalizarCum(o.cumOriginal()).map(Set::of).orElse(Set.of());
    }

    /** Bases de principio activo (DCI y sus sinónimos) que la homologación de este dato puede consultar. */
    public static Set<String> basesDe(Origen o, Configuracion c) {
        String descripcion = normalizarTexto(o.descripcion());
        String expandido = expandir(descripcion, c.abreviaturas(), c.unidades());
        String principio = o.principioActivo() != null && !o.principioActivo().isBlank()
                ? expandir(normalizarTexto(o.principioActivo()), c.abreviaturas(), Map.of()) : principioDeDescripcion(expandido, c.formas());
        Set<String> r = new TreeSet<>();
        for (String d : dcis(principio, c)) {
            r.addAll(clavesBusqueda(d, c));
        }
        for (String n : nombresPrincipios(principio)) {
            r.add(principioBase(n, c.sales()));
        }
        return r;
    }

    private static DatoFinal datoFinal(Presentacion p, Configuracion c, String cum, String presentacion) {
        Set<String> dcis = dcisDe(p, c);
        String dci = dcis.isEmpty() ? null : nombrePropio(String.join(" + ", dcis));
        String principio = p.principios().stream().map(PrincipioCum::nombre).filter(Objects::nonNull).map(String::strip)
                .reduce((a, b) -> a + " + " + b).orElse(null);
        String conc = concentracionCum(p.principios(), c.unidades()).map(Concentracion::texto).orElse(null);
        String forma = p.forma() == null ? null : p.forma().strip().toLowerCase(Locale.ROOT);
        String pres = presentacion != null ? presentacion : p.descripcionComercial() == null ? null : p.descripcionComercial().strip();
        String atc = nivelesAtc(p.atc()).map(List::getLast).orElse(null);
        return new DatoFinal(p.producto(), dci, principio, conc, forma, pres, p.unidad(), cum, null, atc, grupoFarmacologico(atc), p.registro());
    }

    /** «PARACETAMOL» ⇒ «Paracetamol» (como el mockup: «Metformina · 850 mg · tableta»). */
    public static String nombrePropio(String t) {
        if (t == null || t.isEmpty()) {
            return t;
        }
        String l = t.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(l.charAt(0)) + l.substring(1);
    }

    private static String nombreConjunto(String conjunto) {
        return switch (conjunto) {
            case "vencidos" -> "vencidos";
            case "otros" -> "otros estados";
            case "renovacion" -> "en trámite de renovación";
            default -> "vigentes";
        };
    }

    private static String vacioANulo(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    // ---------- Duplicados (R-040) ----------

    /**
     * R-040: el mismo dato original (descripción normalizada) con dos o más CUM válidos distintos queda «duplicado» en todos sus registros
     * (salvo los no válidos, que tienen precedencia). Devuelve los resultados en el mismo orden.
     */
    public static List<Resultado> marcarDuplicados(List<Origen> origenes, List<Resultado> resultados, Configuracion c) {
        if (!c.activa(R040)) {
            return resultados;
        }
        Map<String, Set<String>> cumsPorDescripcion = new HashMap<>();
        for (Origen o : origenes) {
            String d = normalizarTexto(o.descripcion());
            Optional<String> cum = normalizarCum(o.cumOriginal());
            if (!d.isEmpty() && cum.isPresent()) {
                cumsPorDescripcion.computeIfAbsent(d, k -> new TreeSet<>()).add(cum.get());
            }
        }
        List<Resultado> r = new ArrayList<>(resultados.size());
        for (int i = 0; i < resultados.size(); i++) {
            Resultado x = resultados.get(i);
            Origen o = origenes.get(i);
            Set<String> cums = cumsPorDescripcion.get(normalizarTexto(o.descripcion()));
            if (cums != null && cums.size() > 1 && normalizarCum(o.cumOriginal()).isPresent()
                    && precedente(List.of(x.estado(), Estado.DUPLICADO)) == Estado.DUPLICADO) {
                r.add(x.conEstado(Estado.DUPLICADO, R040, "mismo dato original con " + cums.size() + " CUM (" + String.join(", ", cums) + ")"));
            } else {
                r.add(x);
            }
        }
        return r;
    }

    /**
     * Atributo de un dato original (principio activo, ATC, código SSFM) cuando sus registros traen valores distintos: el más frecuente y,
     * si empatan, el menor en orden lexicográfico (String.compareTo). Los vacíos no cuentan; sin valores, nulo. No depende del orden de
     * las filas del insumo (invariante «misma entrada, mismo resultado»).
     */
    public static String masFrecuente(Map<String, Long> conteos) {
        String mejor = null;
        long n = 0;
        for (Map.Entry<String, Long> e : conteos.entrySet()) {
            String v = e.getKey();
            long c = e.getValue() == null ? 0 : e.getValue();
            if (v == null || v.isBlank() || c <= 0) {
                continue;
            }
            if (c > n || c == n && v.compareTo(mejor) < 0) {
                mejor = v;
                n = c;
            }
        }
        return mejor;
    }

    /**
     * Estado final de un registro de la tabla maestra con la última decisión humana (capa de decisiones de «Revisión»): «aprobar» deja
     * homologado; «devolver» deja pendiente si las reglas no lo homologaron; sin decisión, el de las reglas. La misma regla aplica la
     * función SQL hom_tabla_maestra_version.
     *
     * @param accion «aprobar», «devolver» o nulo
     */
    public static Estado estadoFinal(Estado reglas, String accion) {
        if ("aprobar".equals(accion)) {
            return Estado.HOMOLOGADO;
        }
        if ("devolver".equals(accion) && reglas != Estado.HOMOLOGADO) {
            return Estado.PENDIENTE;
        }
        return reglas;
    }

    /** Llave estable del dato original: CUM normalizado (o el texto tal cual si no cumple el formato) + descripción normalizada. */
    public static String llaveOrigen(String cumOriginal, String descripcion) {
        String cum = normalizarCum(cumOriginal).orElse(cumOriginal == null ? "" : cumOriginal.strip().toUpperCase(Locale.ROOT));
        return Huellas.sha256(cum + "|" + normalizarTexto(descripcion));
    }

    // ---------- Calidad ----------

    /**
     * Porcentajes por estado con dos decimales que suman exactamente 100,00 (método del mayor residuo; sin registros, todos en cero).
     * El orden es el del enum.
     */
    public static Map<Estado, BigDecimal> porcentajes(Map<Estado, Long> conteos) {
        Map<Estado, BigDecimal> r = new EnumMap<>(Estado.class);
        long total = conteos.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0) {
            for (Estado e : Estado.values()) {
                r.put(e, BigDecimal.ZERO.setScale(2));
            }
            return r;
        }
        long asignados = 0;
        Map<Estado, Long> centesimas = new EnumMap<>(Estado.class);
        Map<Estado, Long> residuo = new EnumMap<>(Estado.class);
        for (Estado e : Estado.values()) {
            long n = conteos.getOrDefault(e, 0L);
            long base = n * 10_000 / total;
            centesimas.put(e, base);
            residuo.put(e, n * 10_000 % total);
            asignados += base;
        }
        List<Estado> orden = new ArrayList<>(List.of(Estado.values()));
        orden.sort(Comparator.comparing((Estado e) -> residuo.get(e)).reversed().thenComparing(Enum::ordinal));
        for (int i = 0; asignados < 10_000; i++, asignados++) {
            Estado e = orden.get(i % orden.size());
            centesimas.merge(e, 1L, Long::sum);
        }
        for (Estado e : Estado.values()) {
            r.put(e, BigDecimal.valueOf(centesimas.get(e), 2));
        }
        return r;
    }

    // ---------- CUM del INVIMA: columnas y agrupación (una fila por principio activo y rol) ----------

    /** Las 29 columnas de los cuatro conjuntos del CUM en datos.gov.co (fieldName de Socrata). */
    public static final List<String> COLUMNAS_CUM = List.of("expediente", "producto", "titular", "registrosanitario", "fechaexpedicion",
            "fechavencimiento", "estadoregistro", "expedientecum", "consecutivocum", "cantidadcum", "descripcioncomercial", "estadocum",
            "fechaactivo", "fechainactivo", "muestramedica", "unidad", "atc", "descripcionatc", "viaadministracion", "concentracion",
            "principioactivo", "unidadmedida", "cantidad", "unidadreferencia", "formafarmaceutica", "nombrerol", "tiporol", "modalidad", "ium");

    /** Columnas que el archivo del CUM debe traer (sin ellas la versión se rechaza). */
    public static final List<String> COLUMNAS_CUM_OBLIGATORIAS = List.of("expediente", "consecutivocum", "producto", "registrosanitario",
            "estadoregistro", "estadocum", "principioactivo", "formafarmaceutica", "atc");

    /** Encabezado comparable: minúsculas, sin tildes ni signos («Consecutivo CUM», «consecutivo_cum» ⇒ «consecutivocum»). */
    public static String columna(String encabezado) {
        return normalizarTexto(encabezado).replace(" ", "").replace("/", "").replace("%", "").replace(",", "").replace(".", "")
                .toLowerCase(Locale.ROOT);
    }

    /** Columnas obligatorias que faltan en el encabezado, en el orden de la lista. */
    public static List<String> faltantes(List<String> encabezado, List<String> obligatorias) {
        Set<String> h = new java.util.HashSet<>();
        for (String e : encabezado) {
            h.add(columna(e));
        }
        return obligatorias.stream().filter(o -> !h.contains(o)).toList();
    }

    /** Presentación lista para guardar (una por CUM y versión). */
    public record PresentacionCarga(String cum, long expediente, int consecutivo, String producto, String titular, String registro,
                                    LocalDate fechaExpedicion, LocalDate fechaVencimiento, String estadoRegistro, BigDecimal cantidadCum,
                                    String descripcionComercial, String estadoCum, LocalDate fechaActivo, LocalDate fechaInactivo,
                                    String muestraMedica, String unidad, String atc, String descripcionAtc, String via, String forma, String ium,
                                    String productoNorm) {
    }

    /** Principio activo de una presentación (uno por CUM y principio). */
    public record PrincipioCarga(String cum, String principio, String principioNorm, String principioBase, BigDecimal cantidad, String unidadMedida,
                                 String concentracion, String unidadReferencia) {
    }

    /** Rol (fabricante, importador…) de una presentación. */
    public record RolCarga(String cum, String nombre, String tipo, String modalidad) {
    }

    /** Filas del CUM agrupadas: una presentación, sus principios y roles, y lo que se cuenta en el informe. */
    public record GrupoCum(PresentacionCarga presentacion, List<PrincipioCarga> principios, List<RolCarga> roles, int fechasInvalidas,
                           boolean atcInvalido) {
    }

    /**
     * Agrupa las filas de un mismo CUM (expediente + consecutivo; una fila por principio activo y por rol, FO §1.2) en una presentación,
     * sus principios activos (sin repetir) y sus roles (sin repetir). Las fechas MM/DD/YYYY que no se pueden convertir quedan nulas y se
     * cuentan. Vacío si las filas no traen una llave válida.
     *
     * @param filas valores por columna comparable ({@link #columna(String)}) de cada fila, todas del mismo CUM
     * @param sales sales del catálogo (para la base de búsqueda del principio)
     */
    public static Optional<GrupoCum> agruparCum(List<Map<String, String>> filas, Set<String> sales) {
        if (filas.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> f0 = filas.getFirst();
        Optional<String> cum = cumDe(f0.get("expediente"), f0.get("consecutivocum"));
        if (cum.isEmpty()) {
            return Optional.empty();
        }
        String c = cum.get();
        int[] invalidas = {0};
        LocalDate expedicion = fechaContada(f0.get("fechaexpedicion"), invalidas);
        LocalDate vencimiento = fechaContada(f0.get("fechavencimiento"), invalidas);
        LocalDate activo = fechaContada(f0.get("fechaactivo"), invalidas);
        LocalDate inactivo = fechaContada(f0.get("fechainactivo"), invalidas);
        String atc = vacioANulo(f0.get("atc"));
        String atcNorm = atc == null ? null : atc.toUpperCase(Locale.ROOT);
        boolean atcInvalido = atcNorm != null && nivelesAtc(atcNorm).isEmpty();
        String[] partes = c.split("-");
        PresentacionCarga p = new PresentacionCarga(c, Long.parseLong(partes[0]), Integer.parseInt(partes[1]), vacioANulo(f0.get("producto")),
                vacioANulo(f0.get("titular")), vacioANulo(f0.get("registrosanitario")), expedicion, vencimiento, vacioANulo(f0.get("estadoregistro")),
                numero(f0.get("cantidadcum")).orElse(null), vacioANulo(f0.get("descripcioncomercial")), vacioANulo(f0.get("estadocum")), activo,
                inactivo, vacioANulo(f0.get("muestramedica")), vacioANulo(f0.get("unidad")), atcNorm, vacioANulo(f0.get("descripcionatc")),
                vacioANulo(f0.get("viaadministracion")), vacioANulo(f0.get("formafarmaceutica")), vacioANulo(f0.get("ium")),
                normalizarTexto(f0.get("producto")));
        Map<String, PrincipioCarga> principios = new LinkedHashMap<>();
        Map<String, RolCarga> roles = new LinkedHashMap<>();
        for (Map<String, String> f : filas) {
            String pa = vacioANulo(f.get("principioactivo"));
            if (pa != null) {
                String n = normalizarTexto(pa);
                principios.putIfAbsent(n, new PrincipioCarga(c, pa, n, principioBase(n, sales), numero(f.get("cantidad")).orElse(null),
                        vacioANulo(f.get("unidadmedida")), vacioANulo(f.get("concentracion")), vacioANulo(f.get("unidadreferencia"))));
            }
            String rol = vacioANulo(f.get("nombrerol"));
            if (rol != null) {
                String k = normalizarTexto(rol) + "|" + normalizarTexto(f.get("tiporol")) + "|" + normalizarTexto(f.get("modalidad"));
                roles.putIfAbsent(k, new RolCarga(c, rol, vacioANulo(f.get("tiporol")), vacioANulo(f.get("modalidad"))));
            }
        }
        return Optional.of(new GrupoCum(p, List.copyOf(principios.values()), List.copyOf(roles.values()), invalidas[0], atcInvalido));
    }

    private static LocalDate fechaContada(String texto, int[] invalidas) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        Optional<LocalDate> f = fechaInvima(texto);
        if (f.isEmpty()) {
            invalidas[0]++;
        }
        return f.orElse(null);
    }
}
