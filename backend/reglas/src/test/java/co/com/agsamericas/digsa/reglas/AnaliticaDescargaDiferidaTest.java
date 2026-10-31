package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnaliticaDescargaDiferidaTest {

    @Test
    @DisplayName("umbral: el umbral mismo cabe en la petición; uno más va a diferido; por defecto 100 000; 3 intentos")
    void umbral() {
        assertThat(AnaliticaDescargaDiferida.UMBRAL_FILAS).isEqualTo(100_000);
        assertThat(AnaliticaDescargaDiferida.MAX_INTENTOS).isEqualTo(3);
        assertThat(AnaliticaDescargaDiferida.diferir(100_000, 100_000)).isFalse();
        assertThat(AnaliticaDescargaDiferida.diferir(100_001, 100_000)).isTrue();
        assertThat(AnaliticaDescargaDiferida.diferir(0, 0)).isFalse();
        assertThat(AnaliticaDescargaDiferida.diferir(1, 0)).isTrue();
    }

    @Test
    @DisplayName("traza: en la petición hasta el menor entre el umbral y max-registros-traza; en diferido con tope (422 con el máximo)")
    void traza() {
        assertThat(AnaliticaDescargaDiferida.limiteTrazaSincrona(100_000, 100_000)).isEqualTo(100_000);
        assertThat(AnaliticaDescargaDiferida.limiteTrazaSincrona(100_000, 500_000)).isEqualTo(100_000);
        assertThat(AnaliticaDescargaDiferida.limiteTrazaSincrona(5, 100_000)).isEqualTo(5);
        assertThat(AnaliticaDescargaDiferida.excedeTraza(2_000_000, 2_000_000)).isEmpty();
        assertThat(AnaliticaDescargaDiferida.excedeTraza(2_000_001, 2_000_000).orElseThrow()).contains("2000001").contains("máximo 2000000");
        assertThat(AnaliticaDescargaDiferida.mensajeDiferida(150_000, 100_000)).contains("150000").contains("Mis descargas del día");
    }

    @Test
    @DisplayName("alcance congelado: ida y vuelta para nacional, Fuerza y ESM; los demás, los códigos de Fuerza fuera del patrón y los raros no se aceptan")
    void alcance() {
        for (Alcance a : List.of(new Alcance.Todo(), new Alcance.Fuerza("EJC"), new Alcance.Esm(42))) {
            String c = AnaliticaDescargaDiferida.codigo(a).orElseThrow();
            assertThat(AnaliticaDescargaDiferida.alcance(c)).contains(a);
        }
        assertThat(AnaliticaDescargaDiferida.codigo(new Alcance.Todo())).contains("nacional");
        assertThat(AnaliticaDescargaDiferida.codigo(new Alcance.Fuerza("ARC"))).contains("fuerza:ARC");
        assertThat(AnaliticaDescargaDiferida.codigo(new Alcance.Esm(7))).contains("esm:7");
        assertThat(AnaliticaDescargaDiferida.codigo(new Alcance.Opl())).isEmpty();
        assertThat(AnaliticaDescargaDiferida.codigo(new Alcance.Ninguno())).isEmpty();
        assertThat(AnaliticaDescargaDiferida.codigo(new Alcance.Fuerza(" "))).isEmpty();
        assertThat(AnaliticaDescargaDiferida.codigo(new Alcance.Fuerza("ejc"))).as("el CHECK de V67 no lo aceptaría: no es un 500").isEmpty();
        assertThat(AnaliticaDescargaDiferida.codigo(new Alcance.Fuerza("X".repeat(21)))).isEmpty();
        assertThat(AnaliticaDescargaDiferida.codigo(new Alcance.Esm(0))).isEmpty();
        assertThat(AnaliticaDescargaDiferida.alcance(null)).isEmpty();
        assertThat(AnaliticaDescargaDiferida.alcance("ninguno")).isEmpty();
        assertThat(AnaliticaDescargaDiferida.alcance("esm:0")).isEmpty();
        assertThat(AnaliticaDescargaDiferida.alcance("esm:abc")).isEmpty();
        assertThat(AnaliticaDescargaDiferida.alcance("fuerza:EJC' OR 1=1")).isEmpty();
    }

    @Test
    @DisplayName("nombre con el formato de la descarga en la petición y el sello de la generación en hora de Bogotá; ruta y resumen sin filtros")
    void nombres() {
        OffsetDateTime t = OffsetDateTime.of(2026, 10, 30, 19, 15, 0, 0, ZoneOffset.UTC);
        assertThat(AnaliticaDescargaDiferida.nombreArchivo("atc", t)).isEqualTo("registros_origen_atc_20261030_1415.csv");
        assertThat(AnaliticaDescargaDiferida.nombreArchivo("alto-costo", null)).isEqualTo("registros_origen_alto-costo.csv");
        assertThat(AnaliticaDescargaDiferida.nombreArchivo("../x\"y", t)).isEqualTo("registros_origen_xy_20261030_1415.csv");
        assertThat(AnaliticaDescargaDiferida.ruta(15, "abc123")).isEqualTo("analitica/descargas/15_abc123.csv").matches("^[A-Za-z0-9_/-]+\\.csv$");
        assertThat(AnaliticaDescargaDiferida.resumen(15, "atc")).isEqualTo("Descarga diferida 15 · registros de origen atc · csv");
    }

    @Test
    @DisplayName("parámetros guardados: lista blanca (generales, filtros y la celda); sin corte, formato, paginación ni texto libre; con tope de tamaño")
    void parametros() {
        for (String k : List.of("fuerza", "esm", "desde", "hasta", "granularidad", "nivel", "periodo")) {
            assertThat(AnaliticaDescargaDiferida.parametroGuardado(k)).as(k).isTrue();
        }
        for (String k : List.of("formato", "corte", "pagina", "despues", "texto", "variable", "cualquiera")) {
            assertThat(AnaliticaDescargaDiferida.parametroGuardado(k)).as(k).isFalse();
        }
        assertThat(AnaliticaDescargaDiferida.parametroGuardado(null)).isFalse();
        Map<String, List<String>> p = new LinkedHashMap<>();
        p.put("fuerza", List.of("EJC", "ARC"));
        assertThat(AnaliticaDescargaDiferida.parametrosCaben(p, 4000)).isTrue();
        p.put("cum", List.of("x".repeat(5000)));
        assertThat(AnaliticaDescargaDiferida.parametrosCaben(p, 4000)).isFalse();
    }
}
