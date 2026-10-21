package co.com.agsamericas.digsa.plataforma;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Exporta un {@link Reporte}. Sin fórmulas en ninguna celda (todo texto o número). Formatos de la DIGSA (respuestas GMI P77 f y
 * VCO P27): CSV con ";" en UTF-8, números con coma decimal y sin separador de miles; fechas en hora de 24 h.
 */
@Component
public class Exportador {

    public static final String TIPO_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String TIPO_CSV = "text/csv; charset=UTF-8";
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Excel en streaming (memoria constante con muchas filas). */
    public byte[] excel(Reporte r) {
        try (SXSSFWorkbook libro = new SXSSFWorkbook(200); ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
            Sheet hoja = libro.createSheet(nombreHoja(r.titulo()));
            CellStyle negrita = libro.createCellStyle();
            Font fuente = libro.createFont();
            fuente.setBold(true);
            negrita.setFont(fuente);
            int fila = 0;
            for (String[] linea : encabezado(r)) {
                Row row = hoja.createRow(fila++);
                Cell c0 = row.createCell(0);
                c0.setCellValue(linea[0]);
                c0.setCellStyle(negrita);
                row.createCell(1).setCellValue(linea[1]);
            }
            fila++;
            Row titulos = hoja.createRow(fila++);
            for (int i = 0; i < r.columnas().size(); i++) {
                Cell c = titulos.createCell(i);
                c.setCellValue(r.columnas().get(i));
                c.setCellStyle(negrita);
            }
            for (List<Object> datos : r.filas()) {
                Row row = hoja.createRow(fila++);
                for (int i = 0; i < datos.size(); i++) {
                    Object v = datos.get(i);
                    Cell c = row.createCell(i);
                    if (v instanceof Number n && !(v instanceof BigDecimal b && b.scale() > 2)) {
                        c.setCellValue(n.doubleValue());
                    } else {
                        c.setCellValue(texto(v)); // texto: aunque empiece por "=", Excel no lo evalúa como fórmula
                    }
                }
            }
            try {
                libro.write(salida);
            } finally {
                libro.dispose(); // borra los temporales de SXSSF también si falla la escritura
            }
            return salida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el Excel", e);
        }
    }

    /** CSV con ";" y BOM UTF-8; valores que empiezan por = + - @ se anteponen con apóstrofo (sin fórmulas). */
    public byte[] csv(Reporte r) {
        StringBuilder sb = new StringBuilder("﻿");
        for (String[] linea : encabezado(r)) {
            sb.append(campo(linea[0])).append(';').append(campo(linea[1])).append("\r\n");
        }
        sb.append("\r\n");
        sb.append(String.join(";", r.columnas().stream().map(Exportador::campo).toList())).append("\r\n");
        for (List<Object> datos : r.filas()) {
            sb.append(String.join(";", datos.stream().map(v -> v instanceof Number ? texto(v) : campo(texto(v))).toList())).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static final String TIPO_TXT = "text/plain; charset=UTF-8";
    public static final String TIPO_PDF = "application/pdf";

    /** Texto plano con columnas alineadas (Anexo 5, 2.12: exportación TXT). */
    public byte[] txt(Reporte r) {
        String fin = "\r\n";
        StringBuilder sb = new StringBuilder();
        for (String[] linea : encabezado(r)) {
            sb.append(linea[0]).append(": ").append(linea[1]).append(fin);
        }
        sb.append(fin);
        int n = r.columnas().size();
        int[] ancho = new int[n];
        for (int i = 0; i < n; i++) {
            ancho[i] = r.columnas().get(i).length();
        }
        List<String[]> filas = new java.util.ArrayList<>();
        for (List<Object> datos : r.filas()) {
            String[] f = new String[n];
            for (int i = 0; i < n; i++) {
                f[i] = i < datos.size() ? texto(datos.get(i)).replaceAll("[\\r\\n\\t]+", " ") : "";
                ancho[i] = Math.max(ancho[i], f[i].length()); // sin recortar: los reportes salen completos
            }
            filas.add(f);
        }
        sb.append(linea(r.columnas().toArray(String[]::new), ancho)).append(fin);
        StringBuilder sep = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sep.append(i == 0 ? "" : "-+-").append("-".repeat(ancho[i]));
        }
        sb.append(sep).append(fin);
        for (String[] f : filas) {
            sb.append(linea(f, ancho)).append(fin);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String linea(String[] celdas, int[] ancho) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ancho.length; i++) {
            String c = i < celdas.length && celdas[i] != null ? celdas[i] : "";
            sb.append(i == 0 ? "" : " | ").append(c).append(" ".repeat(ancho[i] - c.length()));
        }
        return sb.toString().stripTrailing();
    }

    /** PDF horizontal con el encabezado del reporte y las columnas repetidas en cada página; sin tope de filas. */
    public byte[] pdf(Reporte r) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        com.lowagie.text.Document doc = new com.lowagie.text.Document(com.lowagie.text.PageSize.LETTER.rotate(), 30, 30, 36, 36);
        com.lowagie.text.Font titulo = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 13, com.lowagie.text.Font.BOLD, new java.awt.Color(11, 61, 92));
        com.lowagie.text.Font normal = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 8);
        com.lowagie.text.Font negrita = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 8, com.lowagie.text.Font.BOLD);
        try {
            com.lowagie.text.pdf.PdfWriter.getInstance(doc, out);
            doc.addTitle(r.titulo());
            doc.open();
            doc.add(new com.lowagie.text.Paragraph(r.titulo(), titulo));
            for (String[] linea : encabezado(r)) {
                if (!"Reporte".equals(linea[0])) {
                    doc.add(new com.lowagie.text.Paragraph(linea[0] + ": " + linea[1], normal));
                }
            }
            doc.add(new com.lowagie.text.Paragraph(" ", normal));
            int n = Math.max(1, r.columnas().size());
            com.lowagie.text.pdf.PdfPTable t = new com.lowagie.text.pdf.PdfPTable(n);
            t.setWidthPercentage(100);
            t.setHeaderRows(1);
            for (String c : r.columnas()) {
                com.lowagie.text.pdf.PdfPCell celda = new com.lowagie.text.pdf.PdfPCell(new com.lowagie.text.Phrase(c, negrita));
                celda.setBackgroundColor(new java.awt.Color(235, 239, 243));
                t.addCell(celda);
            }
            for (List<Object> datos : r.filas()) {
                for (int i = 0; i < n; i++) {
                    t.addCell(new com.lowagie.text.Phrase(i < datos.size() ? texto(datos.get(i)) : "", normal));
                }
            }
            if (r.filas().isEmpty()) {
                com.lowagie.text.pdf.PdfPCell vacia = new com.lowagie.text.pdf.PdfPCell(new com.lowagie.text.Phrase("Sin registros con estos filtros.", normal));
                vacia.setColspan(n);
                t.addCell(vacia);
            }
            doc.add(t);
            doc.close();
        } catch (com.lowagie.text.DocumentException e) {
            throw new IllegalStateException("No se pudo generar el PDF del reporte", e);
        }
        return out.toByteArray();
    }

    private static List<String[]> encabezado(Reporte r) {
        List<String[]> l = new java.util.ArrayList<>();
        l.add(new String[] {"Reporte", r.titulo()});
        l.add(new String[] {"Generado", r.generado().format(FECHA_HORA)});
        l.add(new String[] {"Usuario", r.usuario()});
        for (Map.Entry<String, String> f : r.filtros().entrySet()) {
            l.add(new String[] {"Filtro: " + f.getKey(), f.getValue()});
        }
        l.add(new String[] {"Registros", String.valueOf(r.filas().size())});
        return l;
    }

    /** Texto de una celda: fechas en 24 h, números con coma decimal sin separador de miles. */
    static String texto(Object v) {
        return switch (v) {
            case null -> "";
            case OffsetDateTime o -> o.atZoneSameInstant(co.com.agsamericas.digsa.reglas.Fechas.BOGOTA).format(FECHA_HORA);
            case ZonedDateTime z -> z.withZoneSameInstant(co.com.agsamericas.digsa.reglas.Fechas.BOGOTA).format(FECHA_HORA);
            case LocalDate d -> d.toString();
            case BigDecimal b -> b.setScale(Math.min(Math.max(b.scale(), 0), 2), java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',');
            case Double d -> BigDecimal.valueOf(d).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',');
            case Float f -> BigDecimal.valueOf(f).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',');
            default -> String.valueOf(v);
        };
    }

    private static String campo(String s) {
        String t = s == null ? "" : s;
        if (!t.isEmpty() && "=+-@\t\r".indexOf(t.charAt(0)) >= 0) {
            t = "'" + t;
        }
        if (t.contains(";") || t.contains("\"") || t.contains("\n") || t.contains("\r")) {
            t = "\"" + t.replace("\"", "\"\"") + "\"";
        }
        return t;
    }

    private static String nombreHoja(String titulo) {
        String limpio = titulo == null ? "Reporte" : titulo.replaceAll("[\\\\/?*\\[\\]:]", " ");
        return limpio.length() > 31 ? limpio.substring(0, 31) : limpio;
    }
}
