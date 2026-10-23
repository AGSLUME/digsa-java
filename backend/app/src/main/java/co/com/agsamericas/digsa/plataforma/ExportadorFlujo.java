package co.com.agsamericas.digsa.plataforma;

import co.com.agsamericas.digsa.plataforma.ReporteEncabezado.Linea;
import co.com.agsamericas.digsa.reglas.Exportacion;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Exportación en flujo de los reportes del módulo de reportes (Anexo 5, 2.9, 2.10 y 2.12): escribe al {@link OutputStream} desde
 * filas que se recorren sin tenerlas en memoria, con el encabezado de 2.9 completo en los cuatro formatos. Las reglas de las celdas son
 * las de {@link Exportador} (coma decimal, 24 h en Bogotá, sin fórmulas). El PDF sale cifrado (AES-256) con una contraseña de
 * propietario aleatoria que no se guarda y sin contraseña de apertura: los lectores que respetan los permisos dejan abrir, imprimir y
 * copiar, pero no modificar (RESPUESTAS l.555); la garantía de integridad es la huella (2.10). El llamador cierra la salida.
 */
@Component
public class ExportadorFlujo {

    /** Página en los formatos sin páginas (CSV y TXT). */
    static final String PAGINA_CONTINUA = "1 de 1 (archivo continuo)";
    /** Filas del PDF que se acumulan antes de pasarlas al documento (tabla grande en partes, memoria acotada). */
    private static final int LOTE_PDF = 200;
    private static final SecureRandom AZAR = new SecureRandom();
    /** Ancho máximo de alineación de una columna del TXT: un valor más largo sale completo y solo descuadra su fila. */
    static final int ANCHO_TXT = 60;
    /** Filas por hoja de Excel antes de seguir en otra (el límite de Excel es 1.048.576). */
    static final int FILAS_POR_HOJA = 1_000_000;
    /** Tope de una celda de Excel (32.767 caracteres). */
    static final int MAX_CELDA = 32_767;
    /** Un valor del encabezado más largo que esto va completo en la hoja «Filtros» de Excel. */
    static final int LARGO_ENCABEZADO = 1_000;
    private static final java.util.regex.Pattern SALTOS = java.util.regex.Pattern.compile("[\\r\\n\\t]+");

    public void escribir(String formato, ReporteEncabezado e, List<String> columnas, ReporteFilas filas, OutputStream salida) {
        try {
            switch (formato) {
                case "csv" -> csv(e, columnas, filas, salida);
                case "txt" -> txt(e, columnas, filas, salida);
                case "pdf" -> pdf(e, columnas, filas, salida);
                case "xlsx" -> excel(e, columnas, filas, salida, FILAS_POR_HOJA);
                default -> throw new IllegalArgumentException("Formato desconocido: " + formato);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("No se pudo escribir el reporte en " + formato, ex);
        }
    }

    private static Writer escritor(OutputStream salida) {
        return new BufferedWriter(new OutputStreamWriter(salida, StandardCharsets.UTF_8), 64 * 1024);
    }

    /** CSV con «;», BOM UTF-8 y CRLF; el encabezado en líneas «etiqueta;valor», una vacía y las columnas. */
    void csv(ReporteEncabezado e, List<String> columnas, ReporteFilas filas, OutputStream salida) throws IOException {
        Writer w = escritor(salida);
        w.write('﻿');
        w.write(Exportacion.campo("Reporte") + ";" + Exportacion.campo(e.titulo()) + "\r\n");
        for (Linea l : e.lineas()) {
            w.write(Exportacion.campo(l.etiqueta()) + ";" + Exportacion.campo(l.valor()) + "\r\n");
        }
        w.write(Exportacion.campo("Página") + ";" + Exportacion.campo(PAGINA_CONTINUA) + "\r\n\r\n");
        w.write(String.join(";", columnas.stream().map(Exportacion::campo).toList()) + "\r\n");
        filas.recorrer(datos -> {
            try {
                w.write(String.join(";", datos.stream().map(v -> v instanceof Number ? Exportador.texto(v) : Exportacion.campo(Exportador.texto(v))).toList()));
                w.write("\r\n");
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
        w.flush();
    }

    /**
     * TXT con columnas alineadas y sin recortar: una pasada para medir y otra para escribir. La alineación llega hasta
     * {@value #ANCHO_TXT} caracteres: un texto largo (objeto, hallazgos) sale completo sin inflar con espacios todas las filas.
     */
    void txt(ReporteEncabezado e, List<String> columnas, ReporteFilas filas, OutputStream salida) throws IOException {
        int n = columnas.size();
        int[] ancho = new int[n];
        for (int i = 0; i < n; i++) {
            ancho[i] = Math.min(ANCHO_TXT, columnas.get(i).length());
        }
        filas.recorrer(datos -> {
            for (int i = 0; i < n && i < datos.size(); i++) {
                ancho[i] = Math.max(ancho[i], Math.min(ANCHO_TXT, celdaTxt(datos.get(i)).length()));
            }
        });
        Writer w = escritor(salida);
        String fin = "\r\n";
        w.write("Reporte: " + e.titulo() + fin);
        for (Linea l : e.lineas()) {
            w.write(l.etiqueta() + ": " + l.valor() + fin);
        }
        w.write("Página: " + PAGINA_CONTINUA + fin + fin);
        w.write(linea(columnas.toArray(String[]::new), ancho) + fin);
        StringBuilder sep = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sep.append(i == 0 ? "" : "-+-").append("-".repeat(ancho[i]));
        }
        w.write(sep + fin);
        filas.recorrer(datos -> {
            String[] f = new String[n];
            for (int i = 0; i < n; i++) {
                f[i] = i < datos.size() ? celdaTxt(datos.get(i)) : "";
            }
            try {
                w.write(linea(f, ancho) + fin);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
        w.flush();
    }

    private static String celdaTxt(Object v) {
        return SALTOS.matcher(Exportador.texto(v)).replaceAll(" ");
    }

    private static String linea(String[] celdas, int[] ancho) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ancho.length; i++) {
            String c = i < celdas.length && celdas[i] != null ? celdas[i] : "";
            sb.append(i == 0 ? "" : " | ").append(c).append(" ".repeat(Math.max(0, ancho[i] - c.length())));
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Excel en streaming: el encabezado arriba, «Página N de M» en el pie de impresión y los títulos de columna repetidos al imprimir.
     * Un valor del encabezado largo (muchas opciones elegidas) va completo en la hoja «Filtros», una opción por fila. Una celda de datos
     * de más de 32.767 caracteres (límite de Excel) sale recortada con la marca visible; el CSV y el TXT la traen completa.
     */
    void excel(ReporteEncabezado e, List<String> columnas, ReporteFilas filas, OutputStream salida, int filasPorHoja) throws IOException {
        try (SXSSFWorkbook libro = new SXSSFWorkbook(200)) {
            libro.setCompressTempFiles(true); // los temporales de SXSSF ocupan varias veces menos disco
            try {
                Sheet hoja = libro.createSheet(nombreHoja(e.titulo()));
                List<Linea> largas = new java.util.ArrayList<>();
                CellStyle negrita = libro.createCellStyle();
                org.apache.poi.ss.usermodel.Font fuente = libro.createFont();
                fuente.setBold(true);
                negrita.setFont(fuente);
                int[] fila = {0};
                java.util.function.BiConsumer<String, String> par = (k, v) -> {
                    Row row = hoja.createRow(fila[0]++);
                    Cell c0 = row.createCell(0);
                    c0.setCellValue(k);
                    c0.setCellStyle(negrita);
                    row.createCell(1).setCellValue(v); // texto: aunque empiece por "=", Excel no lo evalúa como fórmula
                };
                par.accept("Reporte", e.titulo());
                for (Linea l : e.lineas()) {
                    if (l.valor().length() > LARGO_ENCABEZADO) {
                        largas.add(l);
                        par.accept(l.etiqueta(), "Lista completa en la hoja «Filtros»");
                    } else {
                        par.accept(l.etiqueta(), l.valor());
                    }
                }
                par.accept("Página", "Ver el pie de página al imprimir (Página N de M)");
                fila[0]++;
                int filaTitulos = fila[0];
                Row titulos = hoja.createRow(fila[0]++);
                for (int i = 0; i < columnas.size(); i++) {
                    Cell c = titulos.createCell(i);
                    c.setCellValue(columnas.get(i));
                    c.setCellStyle(negrita);
                }
                hoja.setRepeatingRows(new org.apache.poi.ss.util.CellRangeAddress(filaTitulos, filaTitulos, -1, -1));
                hoja.getFooter().setLeft((e.titulo() + (e.codigo().isEmpty() ? "" : " · " + e.codigo())).replace("&", "&&")); // «&» es un código del pie
                hoja.getFooter().setRight("Página &P de &N"); // códigos de página y total de Excel
                Sheet[] actual = {hoja};
                int[] parte = {1};
                filas.recorrer(datos -> {
                    // Una hoja de Excel admite 1.048.576 filas: el reporte completo sigue en «Datos 2», «Datos 3»…, con los títulos
                    if (fila[0] >= filasPorHoja) {
                        actual[0] = libro.createSheet("Datos " + ++parte[0]);
                        Row t2 = actual[0].createRow(0);
                        for (int i = 0; i < columnas.size(); i++) {
                            Cell c = t2.createCell(i);
                            c.setCellValue(columnas.get(i));
                            c.setCellStyle(negrita);
                        }
                        actual[0].setRepeatingRows(new org.apache.poi.ss.util.CellRangeAddress(0, 0, -1, -1));
                        actual[0].getFooter().setLeft(hoja.getFooter().getLeft()); // el mismo pie de 2.9 en cada hoja
                        actual[0].getFooter().setRight(hoja.getFooter().getRight());
                        fila[0] = 1;
                    }
                    Row row = actual[0].createRow(fila[0]++);
                    for (int i = 0; i < datos.size(); i++) {
                        Object v = datos.get(i);
                        Cell c = row.createCell(i);
                        if (v instanceof Number num && !(v instanceof BigDecimal b && b.scale() > 2)) {
                            c.setCellValue(num.doubleValue());
                        } else {
                            c.setCellValue(celdaExcel(Exportador.texto(v)));
                        }
                    }
                });
                if (!largas.isEmpty()) {
                    Sheet f = libro.createSheet("Filtros");
                    int k = 0;
                    for (Linea l : largas) {
                        Row r0 = f.createRow(k++);
                        Cell c0 = r0.createCell(0);
                        c0.setCellValue(l.etiqueta());
                        c0.setCellStyle(negrita);
                        for (String opcion : l.valor().split(", ")) {
                            f.createRow(k++).createCell(0).setCellValue(celdaExcel(opcion));
                        }
                        k++;
                    }
                }
                libro.write(salida);
            } finally {
                if (!libro.dispose()) { // borra los temporales de SXSSF también si falla la escritura
                    org.slf4j.LoggerFactory.getLogger(ExportadorFlujo.class).warn("[reportes] Quedaron temporales de Excel sin borrar");
                }
            }
        }
    }

    private static String nombreHoja(String titulo) {
        return org.apache.poi.ss.util.WorkbookUtil.createSafeSheetName(titulo == null || titulo.isBlank() ? "Reporte" : titulo, ' ');
    }

    /** Marca de una celda recortada por el límite de Excel. */
    static final String RECORTADO = " …[recortado por el límite de Excel; el CSV y el TXT traen el texto completo]";

    static String celdaExcel(String s) {
        return s.length() <= MAX_CELDA ? s : s.substring(0, MAX_CELDA - RECORTADO.length()) + RECORTADO;
    }

    /** Letra Unicode incrustada (Liberation Sans, SIL OFL, de openpdf-fonts-extra): el PDF muestra ≤, ≥, →, °, tildes y eñes. */
    private static final String LETRA = "liberation/LiberationSans-Regular.ttf";
    private static final String LETRA_NEGRITA = "liberation/LiberationSans-Bold.ttf";

    private static BaseFont letra(String recurso) {
        try {
            return BaseFont.createFont(recurso, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("No se pudo cargar la letra del PDF " + recurso, e);
        }
    }

    /**
     * «Página N de M» y el código al pie de cada hoja. El total se escribe en una plantilla al cerrar el documento: así el PDF se arma
     * en una sola pasada. Las filas no se acumulan; lo único que crece con el reporte es el índice de páginas y objetos del PDF (unos
     * cientos de bytes por página).
     */
    private static final class Paginas extends PdfPageEventHelper {
        private final String pie;
        private final BaseFont letra;
        private PdfTemplate total;

        Paginas(String pie, BaseFont letra) {
            this.pie = pie;
            this.letra = letra;
        }

        @Override
        public void onOpenDocument(PdfWriter w, Document d) {
            total = w.getDirectContent().createTemplate(40, 12);
        }

        @Override
        public void onEndPage(PdfWriter w, Document d) {
            PdfContentByte cb = w.getDirectContent();
            String texto = pie + "Página " + w.getPageNumber() + " de ";
            float x = d.right() - letra.getWidthPoint(texto, 7) - 20;
            float y = d.bottom() - 18;
            cb.beginText();
            cb.setFontAndSize(letra, 7);
            cb.setTextMatrix(x, y);
            cb.showText(texto);
            cb.endText();
            cb.addTemplate(total, x + letra.getWidthPoint(texto, 7), y);
        }

        @Override
        public void onCloseDocument(PdfWriter w, Document d) {
            total.beginText();
            total.setFontAndSize(letra, 7);
            total.setTextMatrix(0, 0);
            total.showText(String.valueOf(w.getPageNumber() - 1));
            total.endText();
        }
    }

    /** Filas que se miran para repartir el ancho de las columnas del PDF. */
    static final int MUESTRA_ANCHOS = 500;
    /** Ancho relativo mínimo y máximo de una columna del PDF (en caracteres): un texto muy largo se parte en varias líneas. */
    static final int ANCHO_MIN = 4;
    static final int ANCHO_MAX = 40;

    /** Corta el recorrido de la muestra. */
    private static final class FinMuestra extends RuntimeException {
        FinMuestra() {
            super(null, null, false, false);
        }
    }

    /**
     * Ancho relativo de cada columna del PDF según lo que trae: la palabra más larga del título y el texto más largo de las primeras
     * {@link #MUESTRA_ANCHOS} filas, entre {@link #ANCHO_MIN} y {@link #ANCHO_MAX}. Así una columna de códigos no ocupa lo mismo que una
     * de descripciones.
     */
    static float[] anchosPdf(List<String> columnas, ReporteFilas filas, int n) throws IOException {
        int[] largo = new int[n];
        for (int i = 0; i < n && i < columnas.size(); i++) {
            for (String palabra : columnas.get(i).split("\\s+")) {
                largo[i] = Math.max(largo[i], palabra.length());
            }
        }
        int[] vistas = {0};
        try {
            filas.recorrer(datos -> {
                for (int i = 0; i < n && i < datos.size(); i++) {
                    largo[i] = Math.max(largo[i], Math.min(ANCHO_MAX, Exportador.texto(datos.get(i)).length()));
                }
                if (++vistas[0] >= MUESTRA_ANCHOS) {
                    throw new FinMuestra();
                }
            });
        } catch (FinMuestra fin) {
            // basta con la muestra
        }
        float[] anchos = new float[n];
        for (int i = 0; i < n; i++) {
            anchos[i] = Math.max(ANCHO_MIN, Math.min(ANCHO_MAX, largo[i]));
        }
        return anchos;
    }

    /**
     * PDF horizontal, cifrado (AES-256) y sin permiso de modificar, sin tope de filas (la DIGSA exige reportes completos): la tabla se
     * pasa al documento por partes y el total de páginas va en una plantilla, así que las filas no se acumulan en memoria (solo el índice
     * de páginas del PDF crece con el reporte).
     */
    void pdf(ReporteEncabezado e, List<String> columnas, ReporteFilas filas, OutputStream salida) throws IOException {
        BaseFont base = letra(LETRA);
        BaseFont baseNegrita = letra(LETRA_NEGRITA);
        Document doc = new Document(PageSize.LETTER.rotate(), 30, 30, 36, 40);
        Font titulo = new Font(baseNegrita, 13, Font.NORMAL, new Color(11, 61, 92));
        Font normal = new Font(base, 8);
        Font negrita = new Font(baseNegrita, 8);
        try {
            PdfWriter w = PdfWriter.getInstance(doc, salida);
            w.setCloseStream(false);
            byte[] propietario = new byte[32];
            AZAR.nextBytes(propietario); // aleatoria por archivo, nunca guardada ni registrada
            w.setEncryption(null, java.util.HexFormat.of().formatHex(propietario).getBytes(StandardCharsets.US_ASCII),
                    PdfWriter.ALLOW_PRINTING | PdfWriter.ALLOW_COPY | PdfWriter.ALLOW_SCREENREADERS | PdfWriter.ALLOW_DEGRADED_PRINTING,
                    PdfWriter.ENCRYPTION_AES_256_V3);
            // El pie va en Helvetica WinAnsi (basta para «Página» y el código), así el total de la plantilla queda como texto legible
            w.setPageEvent(new Paginas(e.codigo().isEmpty() ? "" : e.codigo() + " · ", BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED)));
            doc.addTitle(e.titulo());
            doc.addCreator("Suite DIGSA");
            doc.open();
            doc.add(new Paragraph(e.titulo(), titulo));
            for (Linea l : e.lineas()) {
                doc.add(new Paragraph(l.etiqueta() + ": " + l.valor(), normal));
            }
            doc.add(new Paragraph(" ", normal));
            int n = Math.max(1, columnas.size());
            PdfPTable t = new PdfPTable(n);
            t.setWidthPercentage(100);
            t.setWidths(anchosPdf(columnas, filas, n));
            t.setHeaderRows(1);
            t.setComplete(false);
            for (String c : columnas) {
                PdfPCell celda = new PdfPCell(new Phrase(c, negrita));
                celda.setBackgroundColor(new Color(235, 239, 243));
                t.addCell(celda);
            }
            int[] cuenta = {0};
            filas.recorrer(datos -> {
                for (int i = 0; i < n; i++) {
                    t.addCell(new Phrase(i < datos.size() ? Exportador.texto(datos.get(i)) : "", normal));
                }
                if (++cuenta[0] % LOTE_PDF == 0) {
                    try {
                        doc.add(t); // pasa las filas completas al documento y libera la memoria de la tabla
                    } catch (DocumentException ex) {
                        throw new IllegalStateException("No se pudo generar el PDF del reporte", ex);
                    }
                }
            });
            if (filas.total() == 0) {
                PdfPCell vacia = new PdfPCell(new Phrase("Sin registros con estos filtros.", normal));
                vacia.setColspan(n);
                vacia.setHorizontalAlignment(Element.ALIGN_LEFT);
                t.addCell(vacia);
            }
            t.setComplete(true);
            doc.add(t);
            doc.close();
        } catch (DocumentException ex) {
            throw new IllegalStateException("No se pudo generar el PDF del reporte", ex);
        }
    }
}
