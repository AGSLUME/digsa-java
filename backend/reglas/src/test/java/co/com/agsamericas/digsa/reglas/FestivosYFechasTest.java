package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Portado de tests/reglas_test.php (festivos y fechas estrictas). */
class FestivosYFechasTest {

    @Test
    @DisplayName("festivos 2026-2027 calculados = los 36 del mockup v6")
    void festivosDelMockup() {
        List<String> mockup = List.of("2026-01-01", "2026-01-12", "2026-03-23", "2026-04-02", "2026-04-03", "2026-05-01", "2026-05-18",
                "2026-06-08", "2026-06-15", "2026-06-29", "2026-07-20", "2026-08-07", "2026-08-17", "2026-10-12", "2026-11-02", "2026-11-16",
                "2026-12-08", "2026-12-25", "2027-01-01", "2027-01-11", "2027-03-22", "2027-03-25", "2027-03-26", "2027-05-01", "2027-05-10",
                "2027-05-31", "2027-06-07", "2027-07-05", "2027-07-20", "2027-08-07", "2027-08-16", "2027-10-18", "2027-11-01", "2027-11-15",
                "2027-12-08", "2027-12-25");
        List<String> calculados = new ArrayList<>();
        Stream.concat(Festivos.delAnio(2026).stream(), Festivos.delAnio(2027).stream()).forEach(d -> calculados.add(d.toString()));
        assertThat(calculados).containsExactlyElementsOf(mockup);
    }

    @Test
    @DisplayName("Pascua 2026 = 5 de abril")
    void pascua2026() {
        assertThat(Festivos.pascua(2026)).isEqualTo(LocalDate.of(2026, 4, 5));
    }

    @Test
    @DisplayName("2028: Reyes se traslada al lunes 10 de enero")
    void reyes2028() {
        assertThat(Festivos.esFestivo(LocalDate.of(2028, 1, 10))).isTrue();
    }

    @Test
    @DisplayName("18 festivos al año (2028, 2045)")
    void dieciochoAlAnio() {
        assertThat(Festivos.delAnio(2028)).hasSize(18);
        assertThat(Festivos.delAnio(2045)).hasSize(18);
    }

    @Test
    @DisplayName("2030: San Pedro y Sagrado Corazón caen ambos el lunes 1 de julio (17 fechas)")
    void coincidencia2030() {
        assertThat(Festivos.delAnio(2030)).hasSize(17);
        assertThat(Festivos.esFestivo(LocalDate.of(2030, 7, 1))).isTrue();
    }

    @Test
    @DisplayName("fechas estrictas: 30 de febrero y \"+1 year\" se rechazan; una válida se acepta")
    void fechasEstrictas() {
        assertThat(Fechas.valida("2026-02-30")).isEmpty();
        assertThat(Fechas.valida("+1 year")).isEmpty();
        assertThat(Fechas.valida("2026-2-28")).isEmpty();
        assertThat(Fechas.valida(null)).isEmpty();
        assertThat(Fechas.valida("2026-02-28")).contains(LocalDate.of(2026, 2, 28));
    }

    @Test
    @DisplayName("hora 24:00 se rechaza y 08:30 se acepta (hora militar)")
    void horaMilitar() {
        assertThat(Fechas.hora("24:00")).isEmpty();
        assertThat(Fechas.hora("8:30")).isEmpty();
        assertThat(Fechas.hora("23:59")).contains(LocalTime.of(23, 59));
        assertThat(Fechas.hora("08:30")).contains(LocalTime.of(8, 30));
    }
}
