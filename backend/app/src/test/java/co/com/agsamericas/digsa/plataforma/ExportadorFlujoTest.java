package co.com.agsamericas.digsa.plataforma;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.ReporteEncabezado.Linea;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Exportación en flujo (Anexo 5, 2.9, 2.10 y 2.12): encabezado completo, página, sin fórmulas y PDF que no se puede modificar. */
class ExportadorFlujoTest {

    private final ExportadorFlujo exportador = new ExportadorFlujo();
    private static final ReporteEncabezado ENCABEZADO = new ReporteEncabezado("Detalle de visitas", List.of(new Linea("Periodo", "2026-01-01 a 2026-01-31"),
            new Linea("Usuario", "Persona (usuario)"), new Linea("Registros", "2"), new Linea("Código de verificación", "RPT-20261022-ABCDEF"),
            new Linea("Huella de los datos (SHA-256)", "a".repeat(64))), "RPT-20261022-ABCDEF");
    private static final List<String> COLUMNAS = List.of("Lugar", "Valor", "Fecha", "Inicio");

    private static List<List<Object>> filas(int n) {
        List<List<Object>> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(List.of(i == 0 ? "=HIPERVINCULO(\"x\")" : "Punto " + i, new BigDecimal("1234.5"), LocalDate.of(2026, 1, 2),
                    OffsetDateTime.of(2026, 1, 2, 13, 0, 0, 0, ZoneOffset.UTC)));
        }
        return l;
    }

    private byte[] escribir(String formato, int n) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportador.escribir(formato, ENCABEZADO, COLUMNAS, ReporteFilas.de(filas(n)), out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("CSV: BOM, «;», encabezado con código, huella y página continua; coma decimal, hora de Bogotá y sin fórmulas")
    void csv() {
        String s = new String(escribir("csv", 2), StandardCharsets.UTF_8);
        assertThat(s).startsWith("﻿Reporte;Detalle de visitas\r\nPeriodo;2026-01-01 a 2026-01-31\r\n")
                .contains("Código de verificación;RPT-20261022-ABCDEF\r\n", "Página;1 de 1 (archivo continuo)\r\n\r\nLugar;Valor;Fecha;Inicio\r\n")
                .contains("'=HIPERVINCULO", "1234,5;2026-01-02;2026-01-02 08:00:00");
    }

    @Test
    @DisplayName("TXT: columnas alineadas sin recortar y el encabezado completo")
    void txt() {
        String s = new String(escribir("txt", 2), StandardCharsets.UTF_8);
        assertThat(s).startsWith("Reporte: Detalle de visitas\r\n").contains("Huella de los datos (SHA-256): " + "a".repeat(64), "Página: 1 de 1 (archivo continuo)");
        String[] lineas = s.split("\r\n");
        int titulos = List.of(lineas).indexOf(List.of(lineas).stream().filter(l -> l.startsWith("Lugar")).findFirst().orElseThrow());
        assertThat(lineas[titulos].indexOf("| Valor")).isEqualTo(lineas[titulos + 2].indexOf("| 1234,5"));
    }

    @Test
    @DisplayName("Excel: encabezado arriba, «Página N de M» en el pie de impresión y el texto como texto")
    void excel() throws Exception {
        try (XSSFWorkbook libro = new XSSFWorkbook(new java.io.ByteArrayInputStream(escribir("xlsx", 2)))) {
            var hoja = libro.getSheetAt(0);
            assertThat(hoja.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Detalle de visitas");
            assertThat(hoja.getFooter().getRight()).contains("&P", "&N");
            assertThat(hoja.getFooter().getLeft()).contains("RPT-20261022-ABCDEF");
            int titulos = 0;
            while (!"Lugar".equals(hoja.getRow(titulos) == null ? null : hoja.getRow(titulos).getCell(0).getStringCellValue())) {
                titulos++;
            }
            var primera = hoja.getRow(titulos + 1);
            assertThat(primera.getCell(0).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
            assertThat(primera.getCell(1).getNumericCellValue()).isEqualTo(1234.5);
        }
    }

    @Test
    @DisplayName("PDF: cifrado, abre sin contraseña, imprime y copia pero no se modifica; «Página N de M» con el total en cada hoja")
    void pdf() throws Exception {
        byte[] b = escribir("pdf", 300);
        assertThat(new String(b, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        PdfReader r = new PdfReader(b);
        try {
            assertThat(r.isEncrypted()).isTrue();
            int permisos = r.getPermissions();
            assertThat(permisos & PdfWriter.ALLOW_PRINTING).isEqualTo(PdfWriter.ALLOW_PRINTING);
            assertThat(permisos & PdfWriter.ALLOW_COPY).isEqualTo(PdfWriter.ALLOW_COPY);
            assertThat(permisos & PdfWriter.ALLOW_MODIFY_CONTENTS).isZero();
            assertThat(permisos & PdfWriter.ALLOW_MODIFY_ANNOTATIONS).isZero();
            assertThat(permisos & PdfWriter.ALLOW_ASSEMBLY).isZero();
            assertThat(permisos & PdfWriter.ALLOW_FILL_IN).isZero();
            assertThat(permisos & PdfWriter.ALLOW_SCREENREADERS).isEqualTo(PdfWriter.ALLOW_SCREENREADERS);
            int paginas = r.getNumberOfPages();
            assertThat(paginas).isGreaterThan(2);
            PdfTextExtractor texto = new PdfTextExtractor(r);
            assertThat(texto.getTextFromPage(1)).contains("Detalle de visitas", "RPT-20261022-ABCDEF", "Página 1 de " + paginas);
            assertThat(texto.getTextFromPage(paginas)).contains("Página " + paginas + " de " + paginas, "Lugar");
        } finally {
            r.close();
        }
        // Dos archivos del mismo reporte no comparten la contraseña de propietario (aleatoria): sus bytes difieren
        assertThat(escribir("pdf", 2)).isNotEqualTo(escribir("pdf", 2));
    }

    @Test
    @DisplayName("TXT: un texto largo sale completo pero no infla con espacios las demás filas (alineación hasta 60)")
    void txtTextoLargo() {
        List<List<Object>> f = new ArrayList<>();
        f.add(List.of("x".repeat(2000), BigDecimal.ONE, LocalDate.of(2026, 1, 1), OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)));
        for (int i = 0; i < 1000; i++) {
            f.add(List.of("Punto " + i, BigDecimal.ONE, LocalDate.of(2026, 1, 1), OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportador.escribir("txt", ENCABEZADO, COLUMNAS, ReporteFilas.de(f), out);
        String s = out.toString(StandardCharsets.UTF_8);
        assertThat(s).contains("x".repeat(2000));
        assertThat(out.size()).isLessThan(300_000);
    }

    @Test
    @DisplayName("Excel: una lista larga del encabezado va completa en la hoja «Filtros» y una celda de más de 32.767 caracteres sale recortada con marca")
    void excelLimites() throws Exception {
        String muchos = String.join(", ", java.util.stream.IntStream.range(0, 1000).mapToObj(i -> "Punto con un nombre largo número " + i).toList());
        ReporteEncabezado e = new ReporteEncabezado("Detalle", List.of(new Linea("Filtro: Lugar", muchos)), "RPT-20261022-ABCDEFGH");
        List<List<Object>> f = List.of(List.of("y".repeat(40_000), BigDecimal.ONE, LocalDate.of(2026, 1, 1), OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportador.escribir("xlsx", e, COLUMNAS, ReporteFilas.de(f), out);
        try (XSSFWorkbook libro = new XSSFWorkbook(new java.io.ByteArrayInputStream(out.toByteArray()))) {
            assertThat(libro.getSheetAt(0).getRow(1).getCell(1).getStringCellValue()).contains("hoja «Filtros»");
            var filtros = libro.getSheet("Filtros");
            assertThat(filtros).isNotNull();
            assertThat(filtros.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Filtro: Lugar");
            assertThat(filtros.getRow(1000).getCell(0).getStringCellValue()).isEqualTo("Punto con un nombre largo número 999");
            var hoja = libro.getSheetAt(0);
            String celda = hoja.getRow(hoja.getLastRowNum()).getCell(0).getStringCellValue();
            assertThat(celda).hasSize(ExportadorFlujo.MAX_CELDA).endsWith(ExportadorFlujo.RECORTADO);
        }
    }

    @Test
    @DisplayName("sin filas: el PDF lo dice y los demás formatos traen solo el encabezado y los títulos")
    void vacio() throws Exception {
        PdfReader r = new PdfReader(escribir("pdf", 0));
        assertThat(new PdfTextExtractor(r).getTextFromPage(1)).contains("Sin registros con estos filtros.");
        r.close();
        assertThat(new String(escribir("csv", 0), StandardCharsets.UTF_8)).endsWith("Lugar;Valor;Fecha;Inicio\r\n");
    }
}
