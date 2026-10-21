package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Reglas del seguimiento (tests/seguimiento_test.php del PHP). */
class SeguimientoTest {

    private static final Plazos PLAZOS = new Plazos();
    private static final LocalDate HOY = LocalDate.of(2026, 10, 21);

    @Test
    void fechaDelSeguimientoEsLaFinMasProximaDeLasAbiertas() {
        var a = new Seguimiento.Accion("it:P01", "Pendiente", LocalDate.of(2026, 11, 20));
        var b = new Seguimiento.Accion("ob:OB-OPL-01", "En curso", LocalDate.of(2026, 12, 15));
        assertThat(Seguimiento.fecha(List.of(a, b), HOY, PLAZOS)).contains(LocalDate.of(2026, 11, 20));
        var aCompletada = new Seguimiento.Accion("it:P01", "Completado", LocalDate.of(2026, 11, 20));
        assertThat(Seguimiento.fecha(List.of(aCompletada, b), HOY, PLAZOS)).contains(LocalDate.of(2026, 12, 15));
        assertThat(Seguimiento.fecha(List.of(aCompletada), HOY, PLAZOS)).isEmpty();
        // Una fecha fin ya pasada lleva el seguimiento al siguiente día hábil
        var vencida = new Seguimiento.Accion("it:P02", "Retrasado", LocalDate.of(2026, 10, 1));
        assertThat(Seguimiento.fecha(List.of(vencida), HOY, PLAZOS)).contains(PLAZOS.habilSiguiente(HOY));
    }

    @Test
    void abiertaSinFechaImpideElEnvio() {
        assertThat(Seguimiento.sinFecha(List.of(new Seguimiento.Accion("ob:OB-OPL-01", "Pendiente", null),
                new Seguimiento.Accion("it:P01", "Cancelado", null)))).containsExactly("ob:OB-OPL-01");
    }

    @Test
    void ordinales() {
        assertThat(Seguimiento.ordinal(1)).isEqualTo("Primera");
        assertThat(Seguimiento.ordinal(5)).isEqualTo("Quinta");
        assertThat(Seguimiento.ordinal(6)).isEqualTo("6.ª");
    }

    @Test
    void prorroga() {
        LocalDate antes = LocalDate.of(2026, 11, 10);
        assertThat(Seguimiento.esProrroga(new Seguimiento.Accion("it:P01", "Pendiente", LocalDate.of(2026, 11, 30)), antes)).isTrue();
        assertThat(Seguimiento.esProrroga(new Seguimiento.Accion("it:P01", "Pendiente", LocalDate.of(2026, 11, 1)), antes)).as("adelantar").isFalse();
        assertThat(Seguimiento.esProrroga(new Seguimiento.Accion("it:P01", "Pendiente", LocalDate.of(2026, 11, 30)), null)).as("no había").isFalse();
        assertThat(Seguimiento.esProrroga(new Seguimiento.Accion("it:P01", "Completado", LocalDate.of(2026, 11, 30)), antes)).as("cerrada").isFalse();
        assertThat(Seguimiento.justificacionValida("  corta  ")).isFalse();
        assertThat(Seguimiento.justificacionValida("áéíóúñ áéí")).as("10 con tildes").isTrue();
    }
}
