package co.com.agsamericas.digsa.presupuesto;

import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.PresupuestoCarga;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;

/**
 * Convierte el archivo de una carga en la tabla de textos que lee {@link PresupuestoCarga}: CSV «;» UTF-8 o la primera hoja de un Excel
 * (.xlsx). Los números del Excel salen sin notación científica y las fechas como «AAAA-MM» (el periodo).
 */
final class LectorTablas {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(LectorTablas.class);

    static final long MAX_BYTES = 10L * 1024 * 1024;
    /** Lo más que se acepta descomprimido de un .xlsx (5000 filas ocupan unos 2 MB): corta las «bombas zip» antes de abrirlo con POI. */
    static final long MAX_DESCOMPRIMIDO = 20L * 1024 * 1024;
    private static final int MAX_COLUMNAS = 30;

    private LectorTablas() {
    }

    static List<List<String>> leer(String nombre, byte[] contenido) {
        if (contenido == null || contenido.length == 0) {
            throw ErrorNegocio.validacion("El archivo está vacío.");
        }
        if (contenido.length > MAX_BYTES) {
            throw new ErrorNegocio(HttpStatus.PAYLOAD_TOO_LARGE, "El archivo pesa más de " + MAX_BYTES / (1024 * 1024) + " MB: divídalo.");
        }
        String n = nombre == null ? "" : nombre.toLowerCase(Locale.ROOT);
        boolean zip = contenido.length > 4 && contenido[0] == 'P' && contenido[1] == 'K';
        if (n.endsWith(".xls") && !zip) {
            throw ErrorNegocio.validacion("El formato .xls no se admite: guarde el archivo como .xlsx o como CSV UTF-8 separado por «;».");
        }
        if (zip) {
            return excel(contenido);
        }
        try {
            return PresupuestoCarga.leerCsv(contenido);
        } catch (IllegalArgumentException e) {
            throw ErrorNegocio.validacion(e.getMessage());
        }
    }

    /** Mide lo que se descomprime de verdad, entrada por entrada, sin guardarlo; pasa del tope: 422 sin abrir el libro. */
    static void revisarDescomprimido(byte[] contenido) {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(contenido))) {
            while (zip.getNextEntry() != null) {
                int n;
                while ((n = zip.read(buffer)) > 0) {
                    total += n;
                    if (total > MAX_DESCOMPRIMIDO) {
                        throw ErrorNegocio.validacion("El Excel es demasiado grande al descomprimirlo: divídalo o súbalo como CSV.");
                    }
                }
            }
        } catch (IOException e) {
            throw ErrorNegocio.validacion("No se pudo leer el Excel: guárdelo de nuevo como .xlsx o como CSV UTF-8 separado por «;».");
        }
    }

    private static List<List<String>> excel(byte[] contenido) {
        revisarDescomprimido(contenido);
        try (Workbook libro = new XSSFWorkbook(new ByteArrayInputStream(contenido))) {
            if (libro.getNumberOfSheets() == 0) {
                throw ErrorNegocio.validacion("El Excel no tiene hojas.");
            }
            Sheet hoja = libro.getSheetAt(0);
            List<List<String>> tabla = new ArrayList<>();
            int ultima = hoja.getLastRowNum();
            if (ultima > PresupuestoCarga.MAX_FILAS + 1) {
                throw ErrorNegocio.validacion("El archivo tiene más de " + PresupuestoCarga.MAX_FILAS + " filas: divídalo.");
            }
            for (int r = 0; r <= ultima; r++) {
                Row fila = hoja.getRow(r);
                List<String> valores = new ArrayList<>();
                if (fila != null) {
                    int celdas = Math.min(Math.max(fila.getLastCellNum(), 0), MAX_COLUMNAS);
                    for (int c = 0; c < celdas; c++) {
                        valores.add(texto(fila.getCell(c)));
                    }
                }
                tabla.add(valores);
            }
            return tabla;
        } catch (IOException | RuntimeException e) {
            if (e instanceof ErrorNegocio en) {
                throw en;
            }
            LOG.warn("[presupuesto] Excel ilegible en una carga", e);
            throw ErrorNegocio.validacion("No se pudo leer el Excel: guárdelo de nuevo como .xlsx o como CSV UTF-8 separado por «;».");
        }
    }

    private static String texto(Cell c) {
        if (c == null) {
            return "";
        }
        CellType t = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        return switch (t) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(c)) {
                    LocalDateTime f = c.getLocalDateTimeCellValue();
                    yield String.format("%04d-%02d", f.getYear(), f.getMonthValue());
                }
                // Hasta 2 decimales exactos; si el Excel trae más (p. ej. 0,1 + 0,2), se deja tal cual para que la fila se rechace
                BigDecimal v = BigDecimal.valueOf(c.getNumericCellValue()).stripTrailingZeros();
                yield v.scale() < 0 ? v.setScale(0).toPlainString() : v.toPlainString();
            }
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            default -> "";
        };
    }
}
