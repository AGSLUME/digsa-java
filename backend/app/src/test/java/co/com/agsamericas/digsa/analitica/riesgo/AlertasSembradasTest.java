package co.com.agsamericas.digsa.analitica.riesgo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Conjunto de control del generador (analitica-cohortes-alertas, «Errores sembrados del generador detectados», escenario «Serie de control
 * sin siembra»): el mismo insumo de 20 000 filas SIN patrones, en un contexto propio. El motor no debe inventar alertas: ninguna de
 * severidad media o alta, ningún aviso por correo y ninguna de los tipos cuyos patrones solo existen sembrados.
 */
// Propiedad con su mismo valor por defecto: obliga a un contexto (y una base embebida) propio, distinto del de las pruebas con patrones
@TestPropertySource(properties = {"digsa.analitica.riesgo.meses-gracia=2"})
class AlertasSembradasTest extends PruebaRiesgo {

    @Override
    boolean conPatrones() {
        return false;
    }

    @Test
    @DisplayName("control sin siembra: ninguna de las alertas sembradas aparece; 0 alertas de ingresos, marca, formulación, presentación, concentración, "
            + "duplicidad media o alta; los incrementos media o alta solo por el gasto de biológicos aleatorios (se informa)")
    void sinFalsosPositivosEnControl() throws IOException {
        Base b = base();
        // Las alertas que el archivo con patrones espera no salen sin los patrones con la medida esperada (pueden salir más débiles: el
        // incremento de N02 del Ejército en octubre existe en el azar del insumo y la siembra lo lleva sobre el 100 %): lo que el motor detecta
        // con esa fuerza es lo sembrado, no un artefacto
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("digsa-control-");
        var conPatrones = co.com.agsamericas.digsa.analitica.GeneradorSintetico.generar(dir, "con", FILAS, 2024L, true);
        List<String> aparecen = new java.util.ArrayList<>();
        for (var e : JSON.readTree(java.nio.file.Files.readString(conPatrones.totales())).path("riesgo").path("alertas_esperadas")) {
            long n = jdbc.sql("""
                    SELECT count(*) FROM ana_alertas WHERE tipo = :t AND nivel = :n AND dimension = :d AND valor = :v AND to_char(periodo_hasta, 'YYYY-MM') = :p
                    AND CASE :m WHEN 'pacientes' THEN pacientes WHEN 'registros' THEN registros ELSE floor(valor_observado)::bigint END >= :min""")
                    .param("t", e.path("tipo").asText()).param("n", e.path("nivel").asText()).param("d", e.path("dimension").asText())
                    .param("v", e.path("valor").asText()).param("p", e.path("periodo").asText()).param("m", e.path("medida").asText())
                    .param("min", e.path("minimo").asLong()).query(Long.class).single();
            if (n > 0) {
                aparecen.add(e.toString());
            }
        }
        assertThat(aparecen).as("alertas sembradas que salen también sin los patrones").isEmpty();
        for (String t : List.of("alto_costo", "polimedicados", "marca_estrecho_margen", "formulacion_inusual", "presentacion_inconsistente",
                "concentracion_prescripciones")) {
            assertThat(jdbc.sql("SELECT count(*) FROM ana_alertas WHERE tipo = :t").param("t", t).query(Long.class).single()).as(t).isZero();
        }
        // Media o alta: solo incrementos (calibración abierta, AN.21): en el insumo sintético, un biológico aleatorio duplica el gasto de un
        // ESM pequeño en un mes. Ningún otro tipo
        List<Map<String, Object>> fuertes = jdbc.sql("""
                SELECT tipo, nivel, coalesce(fuerza, '') AS fuerza, dimension, valor, periodo_hasta, severidad, valor_observado
                FROM ana_alertas WHERE severidad IN ('media', 'alta') AND tipo NOT IN ('incremento_gasto', 'incremento_consumo') ORDER BY id""")
                .query().listOfRows();
        assertThat(fuertes).as("alertas media o alta en el conjunto de control (fuera de los incrementos)").isEmpty();
        long incrementos = jdbc.sql("SELECT count(*) FROM ana_alertas WHERE severidad IN ('media', 'alta')").query(Long.class).single();
        System.out.println("[control] incrementos media o alta sin siembra: " + incrementos);
        assertThat(jdbc.sql("SELECT estado FROM ana_alertas_ejecuciones WHERE id = :e").param("e", b.ejecucionAlertas()).query(String.class).single())
                .isEqualTo("ok");
    }
}
