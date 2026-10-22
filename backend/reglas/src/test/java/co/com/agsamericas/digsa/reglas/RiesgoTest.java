package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Riesgo.Datos;
import co.com.agsamericas.digsa.reglas.Riesgo.Pesos;
import co.com.agsamericas.digsa.reglas.Riesgo.Puntaje;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Puntaje de riesgo: tests/riesgo_test.php del PHP sin «días sin visita» (14.1) y con las incidencias de la DIGSA. */
class RiesgoTest {

    static final Pesos P = Riesgo.PESOS_INICIALES;

    static Datos d(int c, int nc, int rec, int inm, boolean plan, int acc, int inc) {
        return new Datos(c, nc, rec, inm, plan, acc, inc);
    }

    @Test
    void pesosInicialesSuman100() {
        assertThat(P.problema()).isNull();
        assertThat(new Pesos(Map.of("incumplimiento", 30, "recurrencia", 20, "inmediata", 15, "planes", 20, "incidencias", 10), 60, 30).problema())
                .contains("suman 95");
        assertThat(new Pesos(P.pesos(), 30, 60).problema()).contains("umbrales");
    }

    @Test
    void todoCumpleYTodoAlMaximo() {
        Puntaje sano = Riesgo.puntaje(d(100, 0, 0, 0, false, 0, 0), P);
        assertThat(sano.total()).isZero();
        assertThat(sano.nivel()).isEqualTo("bajo");
        Puntaje peor = Riesgo.puntaje(d(0, 10, 9, 7, true, 12, 8), P);
        assertThat(peor.total()).isEqualTo(100);
        assertThat(peor.nivel()).isEqualTo("alto");
        assertThat(peor.factores()).containsEntry("incidencias", 15.0);
    }

    @Test
    void nuncaVisitadoNoSuma() {
        assertThat(Riesgo.puntaje(d(0, 0, 0, 0, false, 0, 0), P).total()).as("sin días sin visita en el puntaje").isZero();
    }

    @Test
    void factores() {
        assertThat(Riesgo.puntaje(d(50, 50, 0, 0, false, 0, 0), P).factores()).containsEntry("incumplimiento", 15.0);
        assertThat(Riesgo.puntaje(d(0, 0, 0, 0, true, 2, 0), P).factores()).containsEntry("planes", 14.0);
        assertThat(Riesgo.puntaje(d(0, 0, 2, 0, false, 0, 0), P).factores()).containsEntry("recurrencia", 8.0);
        assertThat(Riesgo.puntaje(d(0, 0, 0, 0, false, 0, 1), P).factores()).containsEntry("incidencias", 5.0);
    }

    @Test
    void niveles() {
        assertThat(Riesgo.nivel(59, P)).isEqualTo("medio");
        assertThat(Riesgo.nivel(60, P)).isEqualTo("alto");
        assertThat(Riesgo.nivel(29, P)).isEqualTo("bajo");
        assertThat(Riesgo.nivel(30, P)).isEqualTo("medio");
    }

    @Test
    void recurrentes() {
        assertThat(Riesgo.recurrentes(List.of("it:P01", "it:P02", "ob:X"), List.of("it:P02", "ob:X", "it:P09"))).isEqualTo(2);
    }

    @Test
    void agregado() {
        Puntaje a = Riesgo.puntaje(d(100, 0, 0, 0, false, 0, 0), P);
        Puntaje b = Riesgo.puntaje(d(0, 10, 9, 7, true, 12, 8), P);
        Puntaje c = Riesgo.puntaje(d(0, 0, 0, 0, false, 0, 3), P);
        Riesgo.Agregado ag = Riesgo.agregar(List.of(a, b, c));
        assertThat(ag.esm()).isEqualTo(3);
        assertThat(ag.promedio()).as("2 decimales").isEqualTo(38.33);
        assertThat(ag.niveles()).containsEntry("alto", 1).containsEntry("medio", 0).containsEntry("bajo", 2);
        assertThat(Riesgo.agregar(List.of()).promedio()).isZero();
        assertThat(Riesgo.factorPrincipal(ag.factores())).isEqualTo("Incumplimiento");
        assertThat(Riesgo.factorPrincipal(Riesgo.agregar(List.of(a)).factores())).isEqualTo("—");
    }
}
