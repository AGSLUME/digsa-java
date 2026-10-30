package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.AnaliticaExportacionHechos.MesExportado;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnaliticaExportacionHechosTest {

    private static final YearMonth ACTUAL = YearMonth.of(2026, 10);

    @Test
    @DisplayName("columnas: con el seudónimo, el cargue, su SHA-256 y la línea; sin la llave natural ni documento en claro")
    void columnas() {
        assertThat(AnaliticaExportacionHechos.COLUMNAS).contains("paciente_seudo", "cargue_id", "cargue_sha256", "linea_origen", "valor_total")
                .doesNotContain("llave", "documento", "paciente", "numero_documento").doesNotHaveDuplicates();
        assertThat(AnaliticaExportacionHechos.COLUMNAS).doesNotContainAnyElementsOf(AnaliticaExportacionHechos.FUERA);
    }

    @Test
    @DisplayName("periodo: mes aaaa-mm, hasta opcional (un solo mes), sin meses futuros, en orden y con tope de meses; cada error con su campo")
    void validar() {
        assertThat(AnaliticaExportacionHechos.validar("2025-01", "2025-03", ACTUAL, 60)).isEmpty();
        assertThat(AnaliticaExportacionHechos.validar("2025-01", null, ACTUAL, 60)).isEmpty();
        assertThat(AnaliticaExportacionHechos.validar("2026-10", "2026-10", ACTUAL, 60)).as("el mes en curso se puede pedir").isEmpty();
        assertThat(AnaliticaExportacionHechos.validar("", "2025-03", ACTUAL, 60).orElseThrow().campo()).isEqualTo("desde");
        assertThat(AnaliticaExportacionHechos.validar("2025-13", "2025-03", ACTUAL, 60).orElseThrow().campo()).isEqualTo("desde");
        assertThat(AnaliticaExportacionHechos.validar("01/2025", null, ACTUAL, 60).orElseThrow().campo()).isEqualTo("desde");
        assertThat(AnaliticaExportacionHechos.validar("2025-01", "marzo", ACTUAL, 60).orElseThrow().campo()).isEqualTo("hasta");
        assertThat(AnaliticaExportacionHechos.validar("1989-12", null, ACTUAL, 60).orElseThrow().campo()).isEqualTo("desde");
        assertThat(AnaliticaExportacionHechos.validar("2026-11", null, ACTUAL, 60).orElseThrow().campo()).isEqualTo("desde");
        assertThat(AnaliticaExportacionHechos.validar("2026-09", "2026-11", ACTUAL, 60).orElseThrow().campo()).isEqualTo("hasta");
        assertThat(AnaliticaExportacionHechos.validar("2025-03", "2025-01", ACTUAL, 60).orElseThrow().mensaje()).contains("anterior al inicial");
        assertThat(AnaliticaExportacionHechos.validar("2025-01", "2025-12", ACTUAL, 12)).isEmpty();
        assertThat(AnaliticaExportacionHechos.validar("2025-01", "2026-01", ACTUAL, 12).orElseThrow().mensaje()).contains("12 meses");
    }

    @Test
    @DisplayName("meses del periodo con ambos extremos, a través del cambio de año; nombres de archivo")
    void mesesYNombres() {
        assertThat(AnaliticaExportacionHechos.meses(YearMonth.of(2024, 11), YearMonth.of(2025, 2)))
                .containsExactly(YearMonth.of(2024, 11), YearMonth.of(2024, 12), YearMonth.of(2025, 1), YearMonth.of(2025, 2));
        assertThat(AnaliticaExportacionHechos.nombreArchivo(7, YearMonth.of(2025, 1))).isEqualTo("digsa_hechos_dispensacion_7_2025-01.csv.gz");
        assertThat(AnaliticaExportacionHechos.nombreLeeme(7)).isEqualTo("LEEME_hechos_dispensacion_7.txt");
        assertThat(AnaliticaExportacionHechos.resumen(7, YearMonth.of(2025, 1), YearMonth.of(2025, 1))).isEqualTo("Exportación de hechos 7 · 2025-01");
        assertThat(AnaliticaExportacionHechos.resumen(7, YearMonth.of(2025, 1), YearMonth.of(2025, 3))).isEqualTo("Exportación de hechos 7 · 2025-01 a 2025-03");
    }

    @Test
    @DisplayName("LEEME: periodo, corte, versión del esquema, filas, valor con coma decimal y SHA-256 por mes, total y meses fallidos")
    void leeme() {
        OffsetDateTime t = OffsetDateTime.of(2026, 10, 30, 15, 0, 0, 0, ZoneOffset.UTC);
        String sha = "a".repeat(64);
        String texto = AnaliticaExportacionHechos.leeme(new AnaliticaExportacionHechos.Leeme(7, "Admin (admin)", t, t.plusMinutes(2), t, 41L, List.of(7L, 41L),
                YearMonth.of(2025, 1), YearMonth.of(2025, 3), "66", List.of(
                        new MesExportado(YearMonth.of(2025, 1), "lista", 10, new BigDecimal("1500.50"), 300, sha),
                        new MesExportado(YearMonth.of(2025, 2), "lista", 0, BigDecimal.ZERO, 120, sha),
                        new MesExportado(YearMonth.of(2025, 3), "fallida", 0, BigDecimal.ZERO, 0, null))));
        assertThat(texto).contains("Exportación: 7", "Generada por: Admin (admin)", "Periodo: 2025-01 a 2025-03 (3 meses)",
                "Corte: 2026-10-30 10:00:00 · último cargue visible: 41", "Cargues incluidos (2): 7, 41", "Versión del esquema de la base: 66",
                "2025-01 · lista · 10 · 1500,50 · 300 · " + sha, "2025-02 · lista · 0 · 0 · 120", "2025-03 · fallida · sin archivo",
                "Total: 2 meses, 10 filas, valor_total 1500,50 · 1 meses sin archivo", "paciente_seudo", "Ley 1581");
        assertThat(texto).contains("\r\n").doesNotContain("llave,");
    }
}
