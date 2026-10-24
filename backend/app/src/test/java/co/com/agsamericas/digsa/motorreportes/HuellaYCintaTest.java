package co.com.agsamericas.digsa.motorreportes;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Periodos.Agrupacion;
import co.com.agsamericas.digsa.reportes.ConsultaReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.TipoColumna;
import co.com.agsamericas.digsa.reportes.GrupoReporte;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Huella de los datos (2.10) y cinta temporal de las filas. */
class HuellaYCintaTest {

    private static final DefinicionReporte D = DefinicionReporte.nuevo("prueba_huella", "Prueba").grupo(GrupoReporte.VISITAS).modulo("prueba")
            .columna("A", TipoColumna.TEXTO).crear();

    private static ConsultaReporte consulta(Alcance a, Map<String, List<String>> filtros, List<String> columnas) {
        return new ConsultaReporte(a, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), Agrupacion.MES, filtros, columnas, 0);
    }

    private static String huella(ConsultaReporte c, List<List<Object>> filas) {
        HuellaDatos h = new HuellaDatos(D, c);
        filas.forEach(h::fila);
        return h.hex();
    }

    @Test
    @DisplayName("la huella no depende de la representación de los números ni de la zona del instante, sí del alcance, columnas y orden")
    void huella() {
        ConsultaReporte c = consulta(new Alcance.Todo(), Map.of("fuerza", List.of("ARC", "EJC")), List.of("A"));
        String base = huella(c, List.of(List.of(new BigDecimal("66.70"), OffsetDateTime.of(2026, 1, 2, 8, 0, 0, 0, ZoneOffset.ofHours(-5)))));
        assertThat(base).hasSize(64);
        assertThat(huella(c, List.of(List.of(66.7, OffsetDateTime.of(2026, 1, 2, 13, 0, 0, 0, ZoneOffset.UTC))))).isEqualTo(base);
        assertThat(huella(consulta(new Alcance.Fuerza("EJC"), Map.of("fuerza", List.of("ARC", "EJC")), List.of("A")),
                List.of(List.of(new BigDecimal("66.7"), OffsetDateTime.of(2026, 1, 2, 13, 0, 0, 0, ZoneOffset.UTC))))).isNotEqualTo(base);
        assertThat(huella(consulta(new Alcance.Todo(), Map.of("fuerza", List.of("ARC", "EJC")), List.of("A", "B")),
                List.of(List.of(new BigDecimal("66.7"), OffsetDateTime.of(2026, 1, 2, 13, 0, 0, 0, ZoneOffset.UTC))))).isNotEqualTo(base);
        // Separadores escapados: una celda con tabulador no se confunde con dos celdas
        assertThat(huella(c, List.of(List.of("a\tb")))).isNotEqualTo(huella(c, List.of(Arrays.asList("a", "b"))));
        assertThat(huella(c, List.of(List.of("x"), List.of("y")))).isNotEqualTo(huella(c, List.of(List.of("y"), List.of("x"))));
        // El nulo no se confunde con ningún texto, tampoco con el que «parece» el marcador
        assertThat(HuellaDatos.valor(null)).isNotEqualTo(HuellaDatos.valor("\\N")).isNotEqualTo(HuellaDatos.valor("∅"));
        assertThat(huella(c, List.of(Arrays.asList((Object) null)))).isNotEqualTo(huella(c, List.of(List.of("\\N"))));
        assertThat(HuellaDatos.valor(new BigDecimal("0.00"))).isEqualTo("0");
        assertThat(HuellaDatos.alcance(new Alcance.Esm(12))).isEqualTo("esm:12");
        // Los filtros no se confunden por sus separadores: ["a,b"] no es ["a","b"] ni «a;estado=b» es otro filtro
        assertThat(huella(consulta(new Alcance.Todo(), Map.of("texto", List.of("a,b")), List.of("A")), List.of()))
                .isNotEqualTo(huella(consulta(new Alcance.Todo(), Map.of("texto", List.of("a", "b")), List.of("A")), List.of()));
        assertThat(huella(consulta(new Alcance.Todo(), Map.of("texto", List.of("x;estado=b")), List.of("A")), List.of()))
                .isNotEqualTo(huella(consulta(new Alcance.Todo(), new java.util.LinkedHashMap<>(Map.of("texto", List.of("x"), "estado", List.of("b"))),
                        List.of("A")), List.of()));
    }

    @Test
    @DisplayName("tipos de celda: los mismos en pantalla, huella y archivo; un double no finito o un tipo ajeno se rechaza nombrando la columna")
    void tipos() {
        assertThat(TiposCelda.admitido(1.5)).isTrue();
        assertThat(TiposCelda.admitido(Double.NaN)).isFalse();
        assertThat(TiposCelda.admitido(1.5f)).isFalse();
        assertThat(TiposCelda.admitido(java.util.UUID.randomUUID())).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TiposCelda.validar("prueba", List.of("A", "B"), Arrays.asList("x", java.time.Instant.now())))
                .hasMessageContaining("columna «B»").hasMessageContaining("java.time.Instant");
    }

    @Test
    @DisplayName("la cinta devuelve los mismos valores y tipos, se recorre dos veces y se borra al cerrar")
    void cinta() throws Exception {
        List<Object> fila = Arrays.asList(null, "Bogotá «ESM» · 1", 7L, 3, new BigDecimal("12.345"), 1.5, LocalDate.of(2026, 2, 28),
                OffsetDateTime.of(2026, 2, 28, 23, 59, 59, 123_000_000, ZoneOffset.UTC), true, LocalDateTime.of(2026, 1, 1, 9, 30), "x".repeat(70_000));
        java.nio.file.Path ruta;
        try (CintaFilas c = new CintaFilas()) {
            c.escribir(fila);
            c.escribir(List.of("segunda"));
            c.terminar();
            assertThat(c.total()).isEqualTo(2);
            for (int vuelta = 0; vuelta < 2; vuelta++) {
                List<List<Object>> leidas = new ArrayList<>();
                c.recorrer(leidas::add);
                assertThat(leidas).hasSize(2);
                assertThat(leidas.get(0)).containsExactlyElementsOf(fila);
                assertThat(leidas.get(1)).containsExactly("segunda");
            }
            var campo = CintaFilas.class.getDeclaredField("archivo");
            campo.setAccessible(true);
            ruta = (java.nio.file.Path) campo.get(c);
            assertThat(ruta).exists();
        }
        assertThat(ruta).doesNotExist();
    }

    @Test
    @DisplayName("código de verificación: forma RPT-aaaammdd-XXXXXX sin 0, O, 1 ni I; se normaliza lo que escribe el usuario")
    void codigo() {
        String c = CodigoVerificacion.nuevo(LocalDate.of(2026, 10, 22));
        assertThat(c).matches("RPT-20261022-[A-HJ-NP-Z2-9]{8}");
        assertThat(CodigoVerificacion.normalizar("  " + c.toLowerCase() + " ")).isEqualTo(c);
        assertThat(CodigoVerificacion.normalizar("RPT-20261022-ABCDEFG0")).isEmpty();
        assertThat(CodigoVerificacion.normalizar(null)).isEmpty();
        assertThat(ControladorVerificacion.iniciales("Ana María Pérez (aperez)")).isEqualTo("A. M. P.");
        assertThat(ControladorVerificacion.iniciales("")).isEqualTo("Otro usuario");
    }
}
