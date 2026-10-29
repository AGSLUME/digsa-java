package co.com.agsamericas.digsa.analitica.ingesta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.com.agsamericas.digsa.reglas.AnaliticaIngesta.Causa;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Adaptador de archivo del puerto FuenteDispensacion: CSV/TXT con «;» o «,», BOM, .gz, .zip y xlsx con varias hojas. */
class ArchivoDispensacionTest {

    private static final long MAX = 100L * 1024 * 1024;

    private record Leido(List<String> encabezado, List<String> lineas, List<List<String>> filas, boolean comaDecimal, String fuente) {
    }

    private static Leido leer(String nombre, byte[] datos) throws IOException {
        try (ArchivoDispensacion a = ArchivoDispensacion.abrir(() -> new ByteArrayInputStream(datos), nombre, MAX, null)) {
            List<String> lineas = new ArrayList<>();
            List<List<String>> filas = new ArrayList<>();
            a.recorrer((orden, linea, celdas) -> {
                lineas.add(linea);
                filas.add(celdas);
            });
            return new Leido(a.encabezado(), lineas, filas, a.comaDecimal(), a.fuenteTexto());
        }
    }

    @Test
    @DisplayName("CSV con «;» y BOM: el BOM no entra en el primer encabezado; coma decimal; líneas en blanco saltadas; comillas")
    void csvPuntoYComa() throws IOException {
        byte[] d = "﻿CUM;VALOR\r\n19901234-1;1234,50\r\n\r\n\"2002;3-1\";\"5\"\"x\"\r\n".getBytes(StandardCharsets.UTF_8);
        Leido l = leer("insumo.csv", d);
        assertThat(l.encabezado()).containsExactly("CUM", "VALOR");
        assertThat(l.comaDecimal()).isTrue();
        assertThat(l.fuente()).isEqualTo("Archivo CSV (;) UTF-8");
        assertThat(l.lineas()).containsExactly("2", "4");
        assertThat(l.filas().get(1)).containsExactly("2002;3-1", "5\"x");
    }

    @Test
    @DisplayName("TXT con «,»: punto decimal y fuente «Archivo TXT (,) UTF-8»")
    void txtComa() throws IOException {
        Leido l = leer("insumo.txt", "CUM,VALOR\n19901234-1,1234.50\n".getBytes(StandardCharsets.UTF_8));
        assertThat(l.comaDecimal()).isFalse();
        assertThat(l.fuente()).isEqualTo("Archivo TXT (,) UTF-8");
        assertThat(l.filas()).containsExactly(List.of("19901234-1", "1234.50"));
    }

    @Test
    @DisplayName(".gz y .zip con un solo archivo de datos; un .zip con dos se rechaza")
    void comprimidos() throws IOException {
        byte[] csv = "CUM;VALOR\n19901234-1;10\n19901234-2;20\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(gz)) {
            g.write(csv);
        }
        assertThat(leer("insumo.csv.gz", gz.toByteArray()).filas()).hasSize(2);
        assertThat(leer("insumo.zip", zip("insumo.csv", csv)).filas()).hasSize(2);
        assertThatThrownBy(() -> leer("insumo.zip", zip("a.csv", csv, "b.csv", csv))).isInstanceOf(ErrorEstructura.class)
                .satisfies(e -> assertThat(((ErrorEstructura) e).causa()).isEqualTo(Causa.FORMATO));
    }

    @Test
    @DisplayName("codificación distinta de UTF-8: rechazo de estructura")
    void noUtf8() {
        byte[] latin = "CUM;DESCRIPCION\n19901234-1;ACETAMINOFÉN\n".getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> leer("insumo.csv", latin)).isInstanceOf(ErrorEstructura.class)
                .satisfies(e -> assertThat(((ErrorEstructura) e).causa()).isEqualTo(Causa.CODIFICACION));
    }

    @Test
    @DisplayName("archivo vacío o sin encabezado: rechazo de estructura")
    void vacio() {
        assertThatThrownBy(() -> leer("vacio.csv", "\n\n".getBytes(StandardCharsets.UTF_8))).isInstanceOf(ErrorEstructura.class)
                .satisfies(e -> assertThat(((ErrorEstructura) e).causa()).isEqualTo(Causa.ARCHIVO_VACIO));
    }

    @Test
    @DisplayName("comilla sin cerrar: rechazo de estructura con la línea donde empezó el registro (no se traga el resto del archivo)")
    void comillaSinCerrar() {
        byte[] d = "CUM;VALOR\n19901234-1;10\n\"19901234-2;20\n19901234-3;30\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> leer("insumo.csv", d)).isInstanceOf(ErrorEstructura.class)
                .hasMessage("Comilla sin cerrar en el registro que empieza en la línea 3")
                .satisfies(e -> assertThat(((ErrorEstructura) e).linea()).isEqualTo("3"));
    }

    @Test
    @DisplayName("celda o registro por encima del tope: rechazo de estructura con la línea (protege la memoria)")
    void topesDeCeldaYRegistro() {
        ArchivoDispensacion.Limites chicos = new ArchivoDispensacion.Limites(1024, 4096, 1L << 20);
        String larga = "x".repeat(2000);
        byte[] celda = ("CUM;VALOR\n19901234-1;10\n\"" + larga + "\";20\n").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> leer("insumo.csv", celda, chicos)).isInstanceOf(ErrorEstructura.class)
                .hasMessageContaining("celda demasiado larga").hasMessageContaining("línea 3");
        byte[] registro = ("CUM;VALOR\n" + (larga.substring(0, 900) + ";").repeat(6) + "\n").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> leer("insumo.csv", registro, chicos)).isInstanceOf(ErrorEstructura.class)
                .hasMessageContaining("Registro demasiado largo");
        byte[] sinSaltos = ("CUM;VALOR" + ";x".repeat(5000)).getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> leer("insumo.csv", sinSaltos, chicos)).isInstanceOf(ErrorEstructura.class)
                .hasMessageContaining("primera línea del archivo pasa de");
    }

    private static Leido leer(String nombre, byte[] datos, ArchivoDispensacion.Limites limites) throws IOException {
        try (ArchivoDispensacion a = ArchivoDispensacion.abrir(() -> new ByteArrayInputStream(datos), nombre, MAX, limites, null)) {
            List<String> lineas = new ArrayList<>();
            List<List<String>> filas = new ArrayList<>();
            a.recorrer((orden, linea, celdas) -> {
                lineas.add(linea);
                filas.add(celdas);
            });
            return new Leido(a.encabezado(), lineas, filas, a.comaDecimal(), a.fuenteTexto());
        }
    }

    @Test
    @DisplayName("xlsx de dos hojas con el mismo encabezado: se unen y la línea de origen es «hoja:fila»")
    void xlsxVariasHojas() throws IOException {
        byte[] x = xlsx(new int[] {5, 3}, false);
        Leido l = leer("insumo.xlsx", x);
        assertThat(l.encabezado()).containsExactly("CUM", "CANTIDAD", "VALOR");
        assertThat(l.filas()).hasSize(8);
        assertThat(l.lineas().getFirst()).isEqualTo("1:2");
        assertThat(l.lineas().getLast()).isEqualTo("2:4");
        assertThat(l.filas().getFirst()).containsExactly("19901234-1", "2", "1234.5");
        assertThat(l.comaDecimal()).isFalse();
        assertThat(l.fuente()).isEqualTo("Archivo xlsx");
    }

    @Test
    @DisplayName("xlsx con una hoja de otro encabezado: rechazo de estructura")
    void xlsxHojasDistintas() throws IOException {
        byte[] x = xlsx(new int[] {2, 2}, true);
        assertThatThrownBy(() -> leer("insumo.xlsx", x)).isInstanceOf(ErrorEstructura.class)
                .satisfies(e -> assertThat(((ErrorEstructura) e).causa()).isEqualTo(Causa.HOJAS_DISTINTAS));
    }

    private static byte[] zip(Object... nombresYDatos) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            for (int i = 0; i < nombresYDatos.length; i += 2) {
                z.putNextEntry(new ZipEntry((String) nombresYDatos[i]));
                z.write((byte[]) nombresYDatos[i + 1]);
                z.closeEntry();
            }
        }
        return b.toByteArray();
    }

    private static byte[] xlsx(int[] filasPorHoja, boolean otroEncabezado) throws IOException {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(100)) {
            for (int h = 0; h < filasPorHoja.length; h++) {
                Sheet s = wb.createSheet("Hoja" + (h + 1));
                Row e = s.createRow(0);
                e.createCell(0).setCellValue(h == 1 && otroEncabezado ? "OTRO" : "CUM");
                e.createCell(1).setCellValue("CANTIDAD");
                e.createCell(2).setCellValue("VALOR");
                for (int i = 1; i <= filasPorHoja[h]; i++) {
                    Row r = s.createRow(i);
                    r.createCell(0).setCellValue("19901234-" + i);
                    r.createCell(1).setCellValue(2);
                    r.createCell(2).setCellValue(1234.5);
                }
            }
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            wb.write(b);
            return b.toByteArray();
        }
    }
}
