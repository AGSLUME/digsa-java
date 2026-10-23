package co.com.agsamericas.digsa.analitica.ingesta;

import co.com.agsamericas.digsa.reglas.AnaliticaIngesta;
import co.com.agsamericas.digsa.reglas.AnaliticaIngesta.Causa;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * xlsx en flujo (SAX sobre el XML de cada hoja, sin cargar el libro; el mismo enfoque de maestros/carga/LectorPlantillas) uniendo varias
 * hojas con el mismo encabezado: 2 M filas superan el límite de 1 048 576 por hoja. La línea de origen es «hoja:fila». Límites de
 * descompresión de POI contra un archivo malicioso. Nunca registra el contenido de las celdas.
 */
final class LectorXlsx {

    private LectorXlsx() {
    }

    /**
     * POI lee completa en memoria la tabla de textos compartidos (xl/sharedStrings.xml): con 2 M filas de documentos y fórmulas distintos
     * puede pasar de cientos de MB. Antes de abrir el libro se revisa su tamaño sin comprimir (el directorio del zip lo trae) y, si pasa
     * del tope, el archivo se rechaza por estructura pidiendo CSV.
     */
    static void revisarTextos(Path archivo, long tope) throws IOException {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(archivo.toFile())) {
            java.util.zip.ZipEntry e = z.getEntry("xl/sharedStrings.xml");
            if (e != null && (e.getSize() < 0 || e.getSize() > tope)) {
                throw new ErrorEstructura(Causa.FORMATO, "El Excel es demasiado grande para leerlo (textos de más de " + tope / (1024 * 1024)
                        + " MB); envíe el insumo en CSV (puede ir comprimido en .gz o .zip)", "1", 1);
            }
        } catch (java.util.zip.ZipException e) {
            throw new ErrorEstructura(Causa.FORMATO, "No se pudo leer el Excel (archivo dañado, protegido con contraseña o no es un .xlsx válido)",
                    "1", 1);
        }
    }

    /** Primera fila con datos de la primera hoja con datos. */
    static List<String> encabezado(Path archivo) throws IOException {
        List<List<String>> h = new ArrayList<>(1);
        try {
            recorrer(archivo, null, (orden, linea, celdas) -> {
                h.add(celdas);
                throw new FuenteDispensacion.Detener();
            }, true);
        } catch (FuenteDispensacion.Detener e) {
            // leída
        }
        return h.isEmpty() ? List.of() : h.getFirst();
    }

    /**
     * Recorre las filas de datos de todas las hojas. Cada hoja debe empezar con el mismo encabezado (normalizado) que la primera.
     *
     * @param soloEncabezado entrega también la fila del encabezado (para leerlo) y nada más
     */
    static void recorrer(Path archivo, List<String> encabezado, FuenteDispensacion.Consumidor consumidor, boolean soloEncabezado)
            throws IOException {
        ZipSecureFile.setMinInflateRatio(0.005);
        ZipSecureFile.setMaxEntrySize(0xFFFFFFFFL); // una hoja de 1 048 576 filas pasa de los 200 MB de la carga de maestros
        List<String> firma = encabezado == null ? null : encabezado.stream().map(AnaliticaIngesta::normalizarEncabezado).toList();
        long[] orden = {0};
        try (OPCPackage pkg = OPCPackage.open(archivo.toFile(), PackageAccess.READ)) {
            ReadOnlySharedStringsTable textos = new ReadOnlySharedStringsTable(pkg, false);
            XSSFReader lector = new XSSFReader(pkg);
            XSSFReader.SheetIterator it = (XSSFReader.SheetIterator) lector.getSheetsData();
            int hoja = 0;
            while (it.hasNext()) {
                try (InputStream in = it.next()) {
                    hoja++;
                    Hoja h = new Hoja(textos, hoja, firma, consumidor, orden, soloEncabezado);
                    XMLReader xml = XMLHelper.newXMLReader();
                    xml.setContentHandler(h);
                    xml.parse(new InputSource(in));
                } catch (SAXException e) {
                    if (e.getCause() instanceof RuntimeException r) {
                        throw r;
                    }
                    if (e.getCause() instanceof IOException io) {
                        throw io;
                    }
                    throw new IOException("Excel no válido", e);
                }
            }
        } catch (FuenteDispensacion.Detener | ErrorEstructura e) {
            throw e;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new ErrorEstructura(Causa.FORMATO, "No se pudo leer el Excel (archivo dañado, protegido con contraseña o no es un .xlsx válido)",
                    "1", 1);
        }
    }

    /** SAX de una hoja: celdas por su columna; texto compartido, en línea, números y booleanos. */
    private static final class Hoja extends DefaultHandler {
        private final ReadOnlySharedStringsTable textos;
        private final int hoja;
        private final List<String> firma;
        private final FuenteDispensacion.Consumidor consumidor;
        private final long[] orden;
        private final boolean soloEncabezado;
        private boolean encabezadoVisto;

        private List<String> celdas;
        private int numeroFila;
        private int filaAnterior;
        private int columna;
        private String tipo;
        private boolean enValor;
        private boolean enTexto;
        private final StringBuilder valor = new StringBuilder();

        Hoja(ReadOnlySharedStringsTable textos, int hoja, List<String> firma, FuenteDispensacion.Consumidor consumidor, long[] orden,
             boolean soloEncabezado) {
            this.textos = textos;
            this.hoja = hoja;
            this.firma = firma;
            this.consumidor = consumidor;
            this.orden = orden;
            this.soloEncabezado = soloEncabezado;
        }

        @Override
        public void startElement(String uri, String local, String qName, Attributes a) {
            switch (local) {
                case "row" -> {
                    String r = a.getValue("r");
                    numeroFila = r == null ? filaAnterior + 1 : Integer.parseInt(r);
                    filaAnterior = numeroFila;
                    celdas = new ArrayList<>(24);
                }
                case "c" -> {
                    String ref = a.getValue("r");
                    columna = ref == null ? celdas.size() : columna(ref);
                    tipo = a.getValue("t");
                    valor.setLength(0);
                }
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
        public void endElement(String uri, String local, String qName) throws SAXException {
            switch (local) {
                case "v" -> enValor = false;
                case "t" -> enTexto = false;
                case "c" -> {
                    while (celdas.size() < columna) {
                        celdas.add("");
                    }
                    if (celdas.size() == columna) {
                        celdas.add(texto());
                    }
                }
                case "row" -> fila();
                default -> {
                }
            }
        }

        private void fila() throws SAXException {
            boolean vacia = celdas.stream().allMatch(String::isBlank);
            if (vacia) {
                return;
            }
            try {
                if (!encabezadoVisto) {
                    encabezadoVisto = true;
                    if (soloEncabezado) {
                        consumidor.registro(0, hoja + ":" + numeroFila, List.copyOf(celdas));
                        return;
                    }
                    List<String> esta = celdas.stream().map(AnaliticaIngesta::normalizarEncabezado).toList();
                    if (firma != null && !sinVaciasAlFinal(esta).equals(sinVaciasAlFinal(firma))) {
                        throw new ErrorEstructura(Causa.HOJAS_DISTINTAS, Causa.HOJAS_DISTINTAS.texto() + " (hoja " + hoja + ")",
                                hoja + ":" + numeroFila, 1);
                    }
                    return;
                }
                consumidor.registro(++orden[0], hoja + ":" + numeroFila, List.copyOf(celdas));
            } catch (IOException e) {
                throw new SAXException(e);
            }
        }

        private static List<String> sinVaciasAlFinal(List<String> l) {
            int n = l.size();
            while (n > 0 && l.get(n - 1).isBlank()) {
                n--;
            }
            return l.subList(0, n);
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
                default -> v; // inlineStr, str
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
