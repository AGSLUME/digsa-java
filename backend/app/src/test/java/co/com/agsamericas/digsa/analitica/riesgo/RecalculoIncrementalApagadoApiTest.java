package co.com.agsamericas.digsa.analitica.riesgo;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.analitica.GeneradorSintetico;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Recálculo incremental apagado (A7.4: digsa.analitica.riesgo.incremental=false). Contexto propio con un insumo pequeño (2 000 filas, sin
 * patrones): el segundo cargue, que con la propiedad encendida sería incremental, hace el recorrido completo y lo dice en la ejecución.
 */
@TestPropertySource(properties = {"digsa.analitica.riesgo.incremental=false"})
class RecalculoIncrementalApagadoApiTest extends PruebaRiesgo {

    @Test
    @DisplayName("con la propiedad apagada cada cargue hace el recorrido completo y la ejecución dice por qué")
    void apagado() throws IOException {
        Navegador n = como(crearUsuario("anaincoff", Perfil.ANALISTA));
        Path dir = Files.createTempDirectory("digsa-sintetico-apagado-");
        GeneradorSintetico.Resultado r = GeneradorSintetico.generar(dir, "sintetico_apagado", 2_000, 7L, false);
        JsonNode a = cargarInsumo(n, "insumo_apagado_2025.csv", Files.readAllBytes(r.csv()));
        assertThat(a.path("estado").asText()).as(a.toString()).isEqualTo("aplicado");
        esperarRecalculo(a.path("id").asLong());
        // Segundo cargue: diciembre corrido a 2026-01
        List<String> lineas = Files.readAllLines(r.csv(), StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(lineas.getFirst()).append("\r\n");
        for (String l : lineas.subList(1, lineas.size())) {
            String[] c = l.split(";", -1);
            if (c.length > 7 && c[7].matches("\\d{2}/12/2025")) {
                c[7] = c[7].substring(0, 6) + "2026";
                c[7] = c[7].replace("/12/", "/01/");
                sb.append(String.join(";", c)).append("\r\n");
            }
        }
        JsonNode b = cargarInsumo(n, "insumo_apagado_2026_01.csv", sb.toString().getBytes(StandardCharsets.UTF_8));
        assertThat(b.path("estado").asText()).as(b.toString()).isEqualTo("aplicado");
        long corte = b.path("id").asLong();
        esperarRecalculo(corte);
        Map<String, Object> e = jdbc.sql("""
                SELECT origen, estado, modo, meses_recalculados, motivo_modo FROM ana_cohortes_ejecuciones WHERE corte_cargue_id = :c ORDER BY id DESC LIMIT 1""")
                .param("c", corte).query().listOfRows().getFirst();
        assertThat(e.get("origen")).isEqualTo("cargue");
        assertThat(e.get("modo")).as(e.toString()).isEqualTo("completo");
        assertThat(e.get("meses_recalculados")).isNull();
        assertThat((String) e.get("motivo_modo")).contains("incremental apagado");
        assertThat(jdbc.sql("SELECT count(*) FROM ana_cohortes_serie WHERE corte_cargue_id = :c").param("c", corte).query(Long.class).single()).isPositive();
    }
}
