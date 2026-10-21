package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChecklistTest {

    private static final Checklist.Item HB_SIN = new Checklist.Item("HB-01", "C00", "C00", true, false);
    private static final Checklist.Item HB_CON = new Checklist.Item("HB-05", "C00", "C00", true, true);
    private static final Checklist.Item NORMAL = new Checklist.Item("P08-01", "C08", "C08", false, false);

    @Test
    @DisplayName("«No aplica» en habilitación solo con condición; en los demás componentes siempre")
    void noAplica() {
        assertThat(Checklist.problema(HB_SIN, new Checklist.Respuesta("NA", null))).get().asString().contains("habilitación con condición");
        assertThat(Checklist.problema(HB_CON, new Checklist.Respuesta("NA", null))).isEmpty();
        assertThat(Checklist.problema(NORMAL, new Checklist.Respuesta("NA", null))).isEmpty();
        assertThat(Checklist.problema(NORMAL, new Checklist.Respuesta("X", null))).isPresent();
        assertThat(Checklist.problema(NORMAL, new Checklist.Respuesta("", null))).as("sin calificar se puede guardar").isEmpty();
        assertThat(Checklist.problema(NORMAL, new Checklist.Respuesta("NC", "x".repeat(2001)))).isPresent();
    }

    @Test
    @DisplayName("para enviar: todo calificado y cada «No cumple» con su hallazgo")
    void faltantes() {
        List<Checklist.Item> items = List.of(HB_SIN, HB_CON, NORMAL);
        assertThat(Checklist.faltantes(items, Map.of())).hasSize(1).first().asString().startsWith("Calificar todo: quedan 3");
        Map<String, Checklist.Respuesta> r = Map.of("HB-01", new Checklist.Respuesta("C", null), "HB-05", new Checklist.Respuesta("NA", null),
                "P08-01", new Checklist.Respuesta("NC", " "));
        assertThat(Checklist.faltantes(items, r)).containsExactly("Describa el hallazgo de los ítems que no cumplen: P08-01.");
        assertThat(Checklist.faltantes(items, Map.of("HB-01", new Checklist.Respuesta("C", null), "HB-05", new Checklist.Respuesta("NA", null),
                "P08-01", new Checklist.Respuesta("NC", "Nevera sin registro de temperatura")))).isEmpty();
    }

    @Test
    @DisplayName("resumen por componente y cumplimiento sin contar los «No aplica»")
    void resumen() {
        Checklist.Resumen s = Checklist.resumen(List.of(HB_SIN, HB_CON, NORMAL),
                Map.of("HB-01", new Checklist.Respuesta("C", null), "P08-01", new Checklist.Respuesta("NC", "h")));
        assertThat(s.c()).isEqualTo(1);
        assertThat(s.nc()).isEqualTo(1);
        assertThat(s.pendientes()).isEqualTo(1);
        assertThat(s.hallazgos()).isEqualTo(1);
        assertThat(s.porComponente().get("C00")).containsExactly(1, 0, 0, 1);
        assertThat(Checklist.cumplimiento(3, 1)).isEqualTo(75.0);
        assertThat(Checklist.cumplimiento(0, 0)).isNull();
    }
}
