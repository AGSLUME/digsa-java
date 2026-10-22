package co.com.agsamericas.digsa.reglas;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Formato de las cargas del presupuesto (asignación inicial, ejecución y proyección; caso M7-1): CSV separado por «;» en UTF-8, o la
 * primera hoja de un Excel que el servidor convierte a la misma tabla de textos. Aquí se valida el formato de cada fila; que el ESM, la
 * fuerza, la modalidad y la vigencia existan lo valida el servicio contra la base. Simular y aplicar usan exactamente esta lectura.
 */
public final class PresupuestoCarga {

    public static final int MAX_FILAS = 5000;
    public static final int MAX_REFERENCIA = 120;
    /** Topes de lo que se arma en memoria al leer el CSV (un archivo de solo «;» no debe tumbar el servicio). */
    public static final int MAX_COLUMNAS = 50;
    public static final int MAX_CAMPO = 1000;

    public enum Clase {
        ASIGNACION("asignacion", List.of("vigencia", "fuerza", "esm", "modalidad", "valor"), List.of()),
        EJECUCION("ejecucion", List.of("vigencia", "fuerza", "esm", "modalidad", "periodo", "facturado"), List.of("reconocido", "referencia")),
        PROYECCION("proyeccion", List.of("vigencia", "fuerza", "esm", "modalidad", "periodo", "valor"), List.of());

        private final String codigo;
        private final List<String> obligatorias;
        private final List<String> opcionales;

        Clase(String codigo, List<String> obligatorias, List<String> opcionales) {
            this.codigo = codigo;
            this.obligatorias = obligatorias;
            this.opcionales = opcionales;
        }

        public String codigo() {
            return codigo;
        }

        public List<String> obligatorias() {
            return obligatorias;
        }

        public List<String> opcionales() {
            return opcionales;
        }
    }

    /** Fila leída con el formato válido. periodo y reconocido pueden venir vacíos según la clase. */
    public record Fila(int numero, int vigencia, String fuerza, String esm, String modalidad, YearMonth periodo, BigDecimal valor,
                       BigDecimal reconocido, String referencia) {
    }

    public record ErrorFila(int fila, String motivo) {
    }

    /** Resultado de la lectura: las filas con formato válido y los errores por número de fila del archivo (el encabezado es la 1). */
    public record Lectura(List<Fila> filas, List<ErrorFila> errores) {
    }

    private static final Pattern MARCAS = Pattern.compile("\\p{M}+");
    private static final Map<String, String> SINONIMOS = Map.ofEntries(
            Map.entry("vigencia", "vigencia"), Map.entry("ano", "vigencia"), Map.entry("anio", "vigencia"),
            Map.entry("fuerza", "fuerza"), Map.entry("fuerza de adscripcion", "fuerza"),
            Map.entry("esm", "esm"), Map.entry("punto", "esm"), Map.entry("sigla", "esm"), Map.entry("establecimiento", "esm"),
            Map.entry("modalidad", "modalidad"), Map.entry("modalidad de facturacion", "modalidad"),
            Map.entry("valor", "valor"), Map.entry("presupuesto", "valor"), Map.entry("asignado", "valor"), Map.entry("proyectado", "valor"),
            Map.entry("periodo", "periodo"), Map.entry("mes", "periodo"),
            Map.entry("facturado", "facturado"), Map.entry("comprometido", "facturado"),
            Map.entry("reconocido", "reconocido"), Map.entry("referencia", "referencia"));

    private PresupuestoCarga() {
    }

    /** Texto sin tildes, en minúsculas y con un solo espacio: para comparar encabezados, nombres de ESM y modalidades. */
    public static String normalizar(String s) {
        if (s == null) {
            return "";
        }
        return MARCAS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    /**
     * Lee el CSV: UTF-8 estricto (con o sin BOM), separado por «;», con comillas dobles para los campos que traen «;» o saltos de línea.
     *
     * @throws IllegalArgumentException con el mensaje para el usuario si no es UTF-8 o no está separado por «;»
     */
    public static List<List<String>> leerCsv(byte[] contenido) {
        String texto;
        try {
            texto = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(contenido)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("El archivo no está en UTF-8. Guárdelo como «CSV UTF-8 (delimitado por punto y coma)».");
        }
        if (texto.startsWith("\uFEFF")) {
            texto = texto.substring(1);
        }
        List<List<String>> filas = new ArrayList<>();
        List<String> fila = new ArrayList<>();
        StringBuilder campo = new StringBuilder();
        boolean comillas = false;
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (comillas) {
                if (c == '"') {
                    if (i + 1 < texto.length() && texto.charAt(i + 1) == '"') {
                        campo.append('"');
                        i++;
                    } else {
                        comillas = false;
                    }
                } else {
                    campo.append(c);
                }
            } else if (c == '"' && campo.isEmpty()) {
                comillas = true;
            } else if (c == ';') {
                fila.add(campo.toString());
                campo.setLength(0);
                if (fila.size() >= MAX_COLUMNAS) {
                    throw new IllegalArgumentException("La fila " + (filas.size() + 1) + " tiene más de " + MAX_COLUMNAS + " columnas.");
                }
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < texto.length() && texto.charAt(i + 1) == '\n') {
                    i++;
                }
                fila.add(campo.toString());
                campo.setLength(0);
                filas.add(fila);
                fila = new ArrayList<>();
                if (filas.size() > MAX_FILAS + 1) {
                    throw new IllegalArgumentException("El archivo tiene más de " + MAX_FILAS + " filas: divídalo.");
                }
            } else {
                campo.append(c);
            }
            if (campo.length() > MAX_CAMPO) {
                throw new IllegalArgumentException("La fila " + (filas.size() + 1) + " tiene un campo de más de " + MAX_CAMPO + " caracteres.");
            }
        }
        if (comillas) {
            throw new IllegalArgumentException("Hay comillas sin cerrar en el archivo: revise la fila " + (filas.size() + 1) + ".");
        }
        if (!campo.isEmpty() || !fila.isEmpty()) {
            fila.add(campo.toString());
            filas.add(fila);
        }
        if (!filas.isEmpty() && filas.get(0).size() == 1 && filas.get(0).get(0).contains(",")) {
            throw new IllegalArgumentException("El archivo debe venir separado por punto y coma («;»), no por comas.");
        }
        return filas;
    }

    /** Lee la tabla (CSV o Excel ya convertido) con los encabezados de la clase. Las filas vacías se saltan. */
    public static Lectura leer(Clase clase, List<List<String>> tabla) {
        List<ErrorFila> errores = new ArrayList<>();
        if (tabla.isEmpty()) {
            return new Lectura(List.of(), List.of(new ErrorFila(1, "El archivo está vacío.")));
        }
        Map<String, Integer> col = new LinkedHashMap<>();
        List<String> encabezado = tabla.get(0);
        for (int i = 0; i < encabezado.size(); i++) {
            String original = encabezado.get(i) == null ? "" : encabezado.get(i).strip();
            if (original.isEmpty()) {
                continue;
            }
            String n = SINONIMOS.get(normalizar(original));
            // Una columna desconocida (p. ej. «reconocida») no se ignora en silencio: dejaría el dato vacío sin aviso
            if (n == null || !(clase.obligatorias().contains(n) || clase.opcionales().contains(n))) {
                return new Lectura(List.of(), List.of(new ErrorFila(1, "La columna «" + (original.length() > 40 ? original.substring(0, 40) + "…" : original)
                        + "» no es de esta carga. Columnas esperadas: " + String.join(";", clase.obligatorias())
                        + (clase.opcionales().isEmpty() ? "" : " (opcionales: " + String.join(", ", clase.opcionales()) + ")") + ".")));
            }
            if (col.putIfAbsent(n, i) != null) {
                return new Lectura(List.of(), List.of(new ErrorFila(1, "La columna «" + n + "» viene dos veces en el encabezado.")));
            }
        }
        List<String> faltan = clase.obligatorias().stream().filter(c -> !col.containsKey(c)).toList();
        if (!faltan.isEmpty()) {
            return new Lectura(List.of(), List.of(new ErrorFila(1, "Faltan las columnas: " + String.join(", ", faltan) + ". Columnas esperadas: "
                    + String.join(";", clase.obligatorias()) + (clase.opcionales().isEmpty() ? "" : " (opcionales: " + String.join(", ", clase.opcionales()) + ")")
                    + ".")));
        }
        List<Fila> filas = new ArrayList<>();
        int datos = 0;
        for (int r = 1; r < tabla.size(); r++) {
            List<String> f = tabla.get(r);
            if (f.stream().allMatch(v -> v == null || v.isBlank())) {
                continue;
            }
            if (++datos > MAX_FILAS) {
                return new Lectura(List.of(), List.of(new ErrorFila(r + 1, "El archivo tiene más de " + MAX_FILAS + " filas: divídalo.")));
            }
            int numero = r + 1;
            List<String> motivos = new ArrayList<>();
            String vig = celda(f, col.get("vigencia"));
            Integer vigencia = vig.matches("^\\d{4}$") ? Integer.valueOf(vig) : null;
            if (vigencia == null) {
                motivos.add("vigencia «" + corto(vig) + "» no es un año");
            }
            // Vacía: el servicio toma la del ESM; en HOMIL (fuerza «NA») la exige (fuerza de adscripción)
            String fuerza = celda(f, col.get("fuerza")).toUpperCase(Locale.ROOT);
            String esm = celda(f, col.get("esm"));
            if (esm.isEmpty()) {
                motivos.add("falta el ESM");
            }
            String modalidad = celda(f, col.get("modalidad"));
            if (modalidad.isEmpty()) {
                motivos.add("falta la modalidad");
            }
            YearMonth periodo = null;
            if (col.containsKey("periodo")) {
                String p = celda(f, col.get("periodo"));
                periodo = Presupuesto.periodo(p).orElse(null);
                if (periodo == null) {
                    motivos.add("periodo «" + corto(p) + "» no es un mes (AAAA-MM)");
                }
            }
            String campoValor = clase == Clase.EJECUCION ? "facturado" : "valor";
            String v = celda(f, col.get(campoValor));
            Optional<BigDecimal> valor = Presupuesto.monto(v);
            boolean ceroPermitido = clase != Clase.ASIGNACION;
            if (valor.isEmpty()) {
                motivos.add(campoValor + " «" + corto(v) + "» no es un valor en pesos con máximo 2 decimales");
            } else if (valor.get().signum() < 0 || (!ceroPermitido && valor.get().signum() == 0)) {
                motivos.add(campoValor + " debe ser " + (ceroPermitido ? "mayor o igual a 0" : "mayor que 0"));
            }
            BigDecimal reconocido = null;
            if (col.containsKey("reconocido")) {
                String rc = celda(f, col.get("reconocido"));
                if (!rc.isEmpty()) {
                    Optional<BigDecimal> rv = Presupuesto.monto(rc);
                    if (rv.isEmpty() || rv.get().signum() < 0) {
                        motivos.add("reconocido «" + corto(rc) + "» no es un valor en pesos mayor o igual a 0");
                    } else {
                        reconocido = rv.get();
                        if (valor.isPresent() && reconocido.compareTo(valor.get()) > 0) {
                            motivos.add("El reconocido (" + Presupuesto.formato(reconocido) + ") no puede superar el facturado ("
                                    + Presupuesto.formato(valor.get()) + ")");
                        }
                    }
                }
            }
            String referencia = col.containsKey("referencia") ? celda(f, col.get("referencia")) : "";
            if (referencia.length() > MAX_REFERENCIA) {
                motivos.add("la referencia tiene más de " + MAX_REFERENCIA + " caracteres");
            }
            if (motivos.isEmpty()) {
                filas.add(new Fila(numero, vigencia, fuerza, esm, modalidad, periodo, valor.orElseThrow(), reconocido, referencia.isEmpty() ? null : referencia));
            } else {
                errores.add(new ErrorFila(numero, mayuscula(String.join("; ", motivos)) + "."));
            }
        }
        if (filas.isEmpty() && errores.isEmpty()) {
            errores.add(new ErrorFila(2, "El archivo no tiene filas de datos."));
        }
        return new Lectura(filas, errores);
    }

    private static String celda(List<String> f, Integer i) {
        return i == null || i >= f.size() || f.get(i) == null ? "" : f.get(i).strip();
    }

    private static String corto(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private static String mayuscula(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
