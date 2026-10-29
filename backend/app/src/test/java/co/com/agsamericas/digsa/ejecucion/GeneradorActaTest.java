package co.com.agsamericas.digsa.ejecucion;

import static org.assertj.core.api.Assertions.assertThat;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VIS-14 (Anexo 3: los hallazgos de intervención inmediata del OPL se consultan «tanto en el acta como en el informe»): el acta trae el
 * recuadro con los inmediatos y la columna «Intervención inmediata» en la tabla de hallazgos; sin ninguno, el recuadro no sale.
 */
class GeneradorActaTest {

    private static GeneradorActa.Contenido contenido(List<String[]> items) {
        Map<String, String> datos = new LinkedHashMap<>();
        datos.put("Número", "VS-PRUEBA-1");
        Map<String, String> resumen = new LinkedHashMap<>();
        resumen.put("Cumple", "1");
        resumen.put("No cumple", "2");
        return new GeneradorActa.Contenido("VS-PRUEBA-1", 1, datos, List.of(), List.of(),
                List.of(new GeneradorActa.Componente("C08", "Almacenamiento", items)), resumen, null, null, null, null, List.of(), List.of(),
                "2026-10-28 10:00", List.of(), null, null, null, null, null, null, List.of());
    }

    private static String texto(byte[] pdf) throws Exception {
        PdfReader r = new PdfReader(pdf);
        try {
            PdfTextExtractor ex = new PdfTextExtractor(r);
            StringBuilder s = new StringBuilder();
            for (int p = 1; p <= r.getNumberOfPages(); p++) {
                s.append(ex.getTextFromPage(p)).append('\n');
            }
            return s.toString().replaceAll("\\s+", " ");
        } finally {
            r.close();
        }
    }

    @Test
    @DisplayName("con un hallazgo inmediato: recuadro con N, ítem y hallazgo, y la columna Sí/No en la tabla de hallazgos")
    void conInmediato() throws Exception {
        String t = texto(GeneradorActa.generar(contenido(List.of(
                new String[] {"C08-01", "Cadena de frío", "No cumple", "Nevera sin registro de temperatura", GeneradorActa.SI},
                new String[] {"C08-02", "Rotulación", "No cumple", "Estantes sin rotular", GeneradorActa.NO},
                new String[] {"C08-03", "Inventario", "Cumple", "", GeneradorActa.NO}))));
        assertThat(t).contains("Hallazgos que requieren intervención inmediata del OPL (1)", "C08-01: Nevera sin registro de temperatura",
                "Intervención inmediata")
                .containsPattern("Nevera sin registro de temperatura ?Sí").containsPattern("Estantes sin rotular ?No");
        // El recuadro va al inicio de la sección «Hallazgos», antes de su tabla
        int recuadro = t.indexOf("Hallazgos que requieren intervención inmediata");
        assertThat(t.indexOf("Ítem Hallazgo Intervención inmediata")).isGreaterThan(recuadro);
        assertThat(t).doesNotContain("C08-02: Estantes sin rotular");
    }

    @Test
    @DisplayName("sin hallazgos inmediatos: no sale el recuadro y cada hallazgo dice «No»")
    void sinInmediatos() throws Exception {
        String t = texto(GeneradorActa.generar(contenido(List.<String[]>of(
                new String[] {"C08-02", "Rotulación", "No cumple", "Estantes sin rotular", GeneradorActa.NO}))));
        assertThat(t).doesNotContain("requieren intervención inmediata").contains("Intervención inmediata")
                .containsPattern("Estantes sin rotular ?No");
    }
}
