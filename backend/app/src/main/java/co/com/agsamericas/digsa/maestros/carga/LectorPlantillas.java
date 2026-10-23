package co.com.agsamericas.digsa.maestros.carga;

import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros.FilaLeida;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros.Hoja;
import co.com.agsamericas.digsa.reglas.ImportadorMaestros.Tipo;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Lee la carpeta de la carga: un .xlsx con las hojas de la plantilla, o uno o varios de auditores.csv, puntos.csv y usuarios.csv
 * (UTF-8, separador «;»). El .xlsx se lee en flujo (SAX sobre el XML de cada hoja, sin cargar el libro) y con los límites de
 * descompresión de POI; una celda con fórmula se lee por su valor calculado y se avisa. Nunca registra el contenido de las celdas.
 */
@Component
public class LectorPlantillas {

    public static final long MAX_BYTES = 10L * 1024 * 1024;
    public static final String PREFIJO_INFORME = "resultado_carga_";
    static final String MENSAJE_CSV = "Guarde el archivo como CSV UTF-8 (delimitado por punto y coma).";

    /** Archivo leído y su SHA-256 (va al resumen de la bitácora). */
    public record Archivo(String nombre, String sha256) {
    }

    public record Lectura(List<Hoja> hojas, List<Archivo> archivos, List<String> avisos) {
    }

    /** Uso no válido (código 1): carpeta inexistente o sin archivos de carga, los dos formatos a la vez. */
    public static class ErrorUso extends RuntimeException {
        public ErrorUso(String mensaje) {
            super(mensaje);
        }
    }

    /** El archivo se rechaza completo con este motivo, sin mirar las filas (código 3). */
    public static class ErrorArchivo extends RuntimeException {
        private final String archivo;

        public ErrorArchivo(String archivo, String mensaje) {
            super(mensaje);
            this.archivo = archivo;
        }

        public String archivo() {
            return archivo;
        }
    }

    public Lectura leer(Path carpeta) {
        if (carpeta == null || !Files.isDirectory(carpeta)) {
            throw new ErrorUso("La carpeta de la carga no existe o no es una carpeta.");
        }
        List<Path> archivos;
        try (Stream<Path> s = Files.list(carpeta)) {
            archivos = s.filter(Files::isRegularFile).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
        } catch (IOException e) {
            throw new ErrorUso("No se pudo leer la carpeta de la carga.");
        }
        List<Path> xlsx = new ArrayList<>();
        List<Path> csv = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        for (Path p : archivos) {
            String nombre = p.getFileName().toString();
            String n = nombre.toLowerCase(Locale.ROOT);
            if (n.startsWith(PREFIJO_INFORME) || n.startsWith(".") || n.startsWith("~$")) {
                continue; // informes de corridas anteriores, ocultos y bloqueos de Excel
            }
            if (n.endsWith(".xls") || n.endsWith(".xlsm") || n.endsWith(".xlsb")) {
                throw new ErrorArchivo(nombre, "Formato no aceptado (libro antiguo o con macros): guárdelo como .xlsx o como CSV UTF-8.");
            }
            if (n.endsWith(".xlsx")) {
                xlsx.add(p);
            } else if (n.endsWith(".csv") && Tipo.de(nombre).isPresent()) {
                csv.add(p);
            } else {
                avisos.add("Se ignoró el archivo " + nombre + " (solo se leen un .xlsx o auditores.csv, puntos.csv y usuarios.csv).");
            }
        }
        if (!xlsx.isEmpty() && !csv.isEmpty()) {
            throw new ErrorUso("Use la plantilla de Excel o los CSV, no ambos.");
        }
        if (xlsx.size() > 1) {
            throw new ErrorUso("Deje un solo .xlsx en la carpeta de la carga.");
        }
        if (xlsx.isEmpty() && csv.isEmpty()) {
            throw new ErrorUso("La carpeta no tiene archivos de carga (un .xlsx o auditores.csv, puntos.csv y usuarios.csv).");
        }
        List<Hoja> hojas = new ArrayList<>();
        List<Archivo> leidos = new ArrayList<>();
        for (Path p : xlsx.isEmpty() ? csv : xlsx) {
            String nombre = p.getFileName().toString();
            byte[] bytes = bytes(p);
            leidos.add(new Archivo(nombre, Huellas.sha256(bytes)));
            if (nombre.toLowerCase(Locale.ROOT).endsWith(".csv")) {
                hojas.add(leerCsv(nombre, bytes));
            } else {
                hojas.addAll(leerXlsx(p, nombre, avisos));
            }
        }
        return new Lectura(hojas, leidos, avisos);
    }

    private static byte[] bytes(Path p) {
        String nombre = p.getFileName().toString();
        try {
            if (Files.size(p) > MAX_BYTES) {
                throw new ErrorArchivo(nombre, "El archivo pesa más de 10 MB. Divídalo en varias cargas.");
            }
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer el archivo " + nombre + ".", e);
        }
    }

    // ---------- CSV ----------

    static Hoja leerCsv(String nombre, byte[] bytes) {
        Tipo tipo = Tipo.de(nombre).orElseThrow();
        int inicio = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        String texto;
        try {
            texto = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, inicio, bytes.length - inicio)).toString();
        } catch (CharacterCodingException e) {
            throw new ErrorArchivo(nombre, MENSAJE_CSV);
        }
        List<List<String>> registros = registrosCsv(texto);
        if (registros.isEmpty() || registros.getFirst().size() < 2) {
            throw new ErrorArchivo(nombre, MENSAJE_CSV); // encabezado sin «;»
        }
        if (registros.size() - 1 > ImportadorMaestros.MAX_FILAS) {
            throw new ErrorArchivo(nombre, "El archivo supera " + ImportadorMaestros.MAX_FILAS + " filas. Divídalo en varias cargas.");
        }
        List<FilaLeida> filas = new ArrayList<>();
        for (int i = 1; i < registros.size(); i++) {
            filas.add(new FilaLeida(i + 1, registros.get(i)));
        }
        return new Hoja(tipo, nombre, registros.getFirst(), filas);
    }

    /** Registros separados por «;» con comillas dobles (una celda entre comillas puede tener «;», saltos de línea y "" escapadas). */
    static List<List<String>> registrosCsv(String texto) {
        List<List<String>> registros = new ArrayList<>();
        List<String> actual = new ArrayList<>();
        StringBuilder celda = new StringBuilder();
        boolean comillas = false;
        boolean hayDatos = false;
        for (int i = 0; i < texto.length(); i++) {
            char ch = texto.charAt(i);
            if (comillas) {
                if (ch == '"') {
                    if (i + 1 < texto.length() && texto.charAt(i + 1) == '"') {
                        celda.append('"');
                        i++;
                    } else {
                        comillas = false;
                    }
                } else {
                    celda.append(ch);
                }
                continue;
            }
            switch (ch) {
                case '"' -> {
                    comillas = true;
                    hayDatos = true;
                }
                case ';' -> {
                    actual.add(celda.toString());
                    celda.setLength(0);
                    hayDatos = true;
                }
                case '\r' -> {
                }
                case '\n' -> {
                    if (hayDatos || celda.length() > 0) {
                        actual.add(celda.toString());
                        registros.add(actual);
                    } else {
                        registros.add(List.of()); // línea en blanco: conserva la numeración
                    }
                    actual = new ArrayList<>();
                    celda.setLength(0);
                    hayDatos = false;
                }
                default -> {
                    celda.append(ch);
                    hayDatos = true;
                }
            }
        }
        if (hayDatos || celda.length() > 0) {
            actual.add(celda.toString());
            registros.add(actual);
        }
        while (!registros.isEmpty() && registros.getLast().isEmpty()) {
            registros.removeLast();
        }
        return registros;
    }

    // ---------- Excel (.xlsx en flujo) ----------

    List<Hoja> leerXlsx(Path p, String nombre, List<String> avisos) {
        // Límites contra un archivo comprimido malicioso (zip bomb): proporción mínima y tamaño máximo por entrada
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(200L * 1024 * 1024);
        List<Hoja> hojas = new ArrayList<>();
        try (OPCPackage pkg = OPCPackage.open(p.toFile(), PackageAccess.READ)) {
            ReadOnlySharedStringsTable textos = new ReadOnlySharedStringsTable(pkg, false);
            XSSFReader lector = new XSSFReader(pkg);
            XSSFReader.SheetIterator it = (XSSFReader.SheetIterator) lector.getSheetsData();
            while (it.hasNext()) {
                try (InputStream in = it.next()) {
                    String hoja = it.getSheetName();
                    var tipo = Tipo.de(hoja);
                    if (tipo.isEmpty()) {
                        String n = ImportadorMaestros.normalizar(hoja);
                        if (!n.equals("instrucciones") && !n.equals("listas")) {
                            avisos.add("Se ignoró la hoja «" + hoja + "» de " + nombre + " (las hojas de la carga son Auditores, Puntos y Usuarios).");
                        }
                        continue;
                    }
                    LectorHoja h = new LectorHoja(textos, nombre, hoja);
                    XMLReader xml = XMLHelper.newXMLReader();
                    xml.setContentHandler(h);
                    xml.parse(new InputSource(in));
                    if (h.formulas > 0) {
                        avisos.add(nombre + " · " + hoja + ": " + h.formulas + " celda(s) con fórmula; se usó su valor calculado (p. ej. "
                                + h.primeraFormula + ").");
                    }
                    if (h.filas.isEmpty()) {
                        continue;
                    }
                    hojas.add(new Hoja(tipo.get(), nombre, h.filas.getFirst().celdas(), h.filas.subList(1, h.filas.size())));
                }
            }
        } catch (ErrorArchivo e) {
            throw e;
        } catch (Exception e) {
            throw new ErrorArchivo(nombre, "No se pudo leer el Excel (archivo dañado, protegido con contraseña o no es un .xlsx válido).");
        }
        return hojas;
    }

    /** SAX de una hoja: filas con su número, celdas por su columna; texto compartido, en línea, números, booleanos y fórmulas. */
    private static final class LectorHoja extends DefaultHandler {
        private final ReadOnlySharedStringsTable textos;
        private final String archivo;
        private final String hoja;
        final List<FilaLeida> filas = new ArrayList<>();
        int formulas;
        String primeraFormula;

        private List<String> celdas;
        private int numeroFila;
        private int filaAnterior;
        private int columna;
        private String tipo;
        private String referencia;
        private boolean formula;
        private boolean enValor;
        private boolean enTexto;
        private final StringBuilder valor = new StringBuilder();

        LectorHoja(ReadOnlySharedStringsTable textos, String archivo, String hoja) {
            this.textos = textos;
            this.archivo = archivo;
            this.hoja = hoja;
        }

        @Override
        public void startElement(String uri, String local, String qName, Attributes a) {
            switch (local) {
                case "row" -> {
                    String r = a.getValue("r");
                    numeroFila = r == null ? filaAnterior + 1 : Integer.parseInt(r);
                    filaAnterior = numeroFila;
                    celdas = new ArrayList<>();
                }
                case "c" -> {
                    referencia = a.getValue("r");
                    columna = referencia == null ? celdas.size() : columna(referencia);
                    tipo = a.getValue("t");
                    formula = false;
                    valor.setLength(0);
                }
                case "f" -> formula = true;
                case "v" -> {
                    enValor = true;
                    valor.setLength(0);
                }
                case "t" -> {
                    if ("inlineStr".equals(tipo)) {
                        enTexto = true;
                    }
                }
                default -> {
                }
            }
        }

        @Override
        public void characters(char[] ch, int inicio, int largo) {
            if (enValor || enTexto) {
                valor.append(ch, inicio, largo);
            }
        }

        @Override
        public void endElement(String uri, String local, String qName) {
            switch (local) {
                case "v" -> enValor = false;
                case "t" -> enTexto = false;
                case "c" -> {
                    while (celdas.size() < columna) {
                        celdas.add("");
                    }
                    celdas.add(texto());
                    if (formula) {
                        formulas++;
                        if (primeraFormula == null) {
                            primeraFormula = referencia;
                        }
                    }
                }
                case "row" -> {
                    boolean vacia = celdas.stream().allMatch(String::isBlank);
                    if (!(filas.isEmpty() && vacia)) { // el encabezado es la primera fila con datos
                        filas.add(new FilaLeida(numeroFila, List.copyOf(celdas)));
                    }
                    if (filas.size() - 1 > ImportadorMaestros.MAX_FILAS) {
                        throw new ErrorArchivo(archivo, "La hoja «" + hoja + "» supera " + ImportadorMaestros.MAX_FILAS
                                + " filas. Divídala en varias cargas.");
                    }
                }
                default -> {
                }
            }
        }

        private String texto() {
            String v = valor.toString();
            if (tipo == null || "n".equals(tipo)) {
                if (v.isEmpty()) {
                    return "";
                }
                try {
                    return new BigDecimal(v).stripTrailingZeros().toPlainString(); // 5001.0 ⇒ 5001; 1.0101E10 ⇒ 10101000000
                } catch (NumberFormatException e) {
                    return v;
                }
            }
            return switch (tipo) {
                case "s" -> v.isEmpty() ? "" : textos.getItemAt(Integer.parseInt(v.strip())).getString();
                case "b" -> "1".equals(v) ? "Sí" : "No";
                case "e" -> "";
                default -> v; // inlineStr, str (resultado de fórmula de texto)
            };
        }

        /** «AB12» ⇒ 27 (índice desde 0). */
        static int columna(String referencia) {
            int c = 0;
            for (int i = 0; i < referencia.length() && Character.isLetter(referencia.charAt(i)); i++) {
                c = c * 26 + (Character.toUpperCase(referencia.charAt(i)) - 'A' + 1);
            }
            return c - 1;
        }
    }
}
