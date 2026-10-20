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
