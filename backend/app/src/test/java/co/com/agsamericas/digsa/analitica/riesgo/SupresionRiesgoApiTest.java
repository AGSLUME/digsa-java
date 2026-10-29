package co.com.agsamericas.digsa.analitica.riesgo;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Supresión de celdas pequeñas en cohortes y alertas con k = 5 (revisión A4 2026-10-24; A5 14.6, Ley 1581 art. 5): contexto propio con
 * {@code supresion-minima = 5} y todos los meses del corte con aviso por correo. Ningún conteo de 1 a 4 pacientes sale por la lista, el
 * detalle (causa y valoración), la descarga ni el correo; las celdas ocultas tampoco publican su gasto, sus dispensaciones ni sus ingresos,
 * y en las particiones un solo grupo oculto arrastra al siguiente menor.
 */
@TestPropertySource(properties = {"digsa.analitica.tableros.supresion-minima=5", "digsa.analitica.riesgo.meses-aviso=12"})
class SupresionRiesgoApiTest extends PruebaRiesgo {

    /** La alerta de cambio de marca del BAS02 de la Fuerza dada (3 pacientes de la Armada, 4 del Ejército). */
    private static JsonNode marcaBas02(Navegador n, String fuerza) {
        return AlertasApiTest.alertas(n, "marca_estrecho_margen", "2025-07").stream()
                .filter(a -> a.path("esm").asText().equals("BAS02") && a.path("fuerza").asText().equals(fuerza)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("alertas con k = 5: una alerta de 3 o 4 pacientes no publica la cifra en pacientes, observado, causa, valoración, CSV ni correo")
    void alertasSinConteosPequenos() throws IOException {
        Base b = base();
        Navegador n = como(crearUsuario("anasupr", Perfil.ANALISTA));
        for (String f : List.of("ARC", "EJC")) {
            JsonNode a = marcaBas02(n, f);
            long real = jdbc.sql("SELECT pacientes FROM ana_alertas WHERE id = :id").param("id", a.path("id").asLong()).query(Long.class).single();
            assertThat(real).as(f).isBetween(1L, 4L);
            assertThat(a.path("pacientes").isNull()).isTrue();
            assertThat(a.path("pacientesTexto").asText()).isEqualTo("<5");
            assertThat(a.path("observado").isNull()).isTrue();
            assertThat(a.path("observadoTexto").asText()).isEqualTo("<5 pacientes");
            assertThat(a.path("gasto").isNull()).isTrue();
            assertThat(a.path("registros").isNull()).isTrue();
            JsonNode d = ok(n.get("/api/analitica/alertas/" + a.path("id").asLong()));
            for (String campo : List.of("causa", "valoracion")) {
                assertThat(d.path(campo).asText()).as(campo).contains("<5 pacientes").doesNotContain(real + " pacientes").doesNotContain("⟦");
            }
        }
        String csv = new String(n.get("/api/analitica/alertas/descarga?formato=csv&tipo=marca_estrecho_margen").cuerpo(), StandardCharsets.UTF_8);
        assertThat(csv).contains("<5 pacientes").doesNotContain("3,00 pacientes").doesNotContain("4,00 pacientes");
        // Correo: la marca de estrecho margen sube a alta (umbral 1) y avisa con el conteo suprimido
        esperarLibre();
        assertThat(n.put("/api/analitica/alertas/parametros/marca_estrecho_margen", Map.of("baja", "1", "media", "1", "alta", "1")).estado()).isEqualTo(200);
        ejecutar(n);
        long id = marcaBas02(n, "ARC").path("id").asLong();
        String detalle = jdbc.sql("SELECT detalle FROM alertas_eventos WHERE tipo = 'analitica_alerta_alta' AND clave = :c").param("c", "ana:" + id)
                .query(String.class).single();
        assertThat(detalle).contains("<5 pacientes").doesNotContain("3 pacientes").doesNotContain("3,00 pacientes");
        sinIndividualizacion(detalle, b);
    }

    @Test
    @DisplayName("cohortes con k = 5: VIH (4) y huérfanas (3) ocultan pacientes, gasto y registros en tarjeta, serie, comparación y composición")
    void cohortesSinCeldasPequenas() throws IOException {
        Base b = base();
        Navegador n = como(crearUsuario("anasupr", Perfil.ANALISTA));
        String q = "corte=" + b.cargue() + "&" + PERIODO;
        JsonNode t = ok(n.get("/api/analitica/cohortes?" + q));
        List<String> salidas = new ArrayList<>();
        salidas.add(t.toString());
        for (JsonNode c : t.path("cohortes")) {
            String codigo = c.path("codigo").asText();
            if (!List.of("vih", "huerfanas").contains(codigo)) {
                continue;
            }
            assertThat(c.path("pacientesTexto").asText()).as(codigo).isEqualTo("<5");
            assertThat(c.path("gasto").asText()).as(codigo).isEqualTo("<5");
            assertThat(c.path("registros").isNull()).as(codigo).isTrue();
            long id = c.path("id").asLong();
            JsonNode d = ok(n.get("/api/analitica/cohortes/" + id + "?" + q));
            salidas.add(d.toString());
            d.path("serie").forEach(f -> {
                if (f.path("pacientes").isNull()) {
                    assertThat(f.path("gasto").isNull()).isTrue();
                    assertThat(f.path("dispensaciones").isNull()).isTrue();
                    assertThat(f.path("ingresos").isNull()).isTrue();
                }
                assertThat(f.path("ingresos").isNull() || f.path("ingresos").asLong() == 0 || f.path("ingresos").asLong() >= 5).isTrue();
            });
            d.path("comparacion").path("filas").forEach(f -> assertThat(f.path("actual").asText()).as(f.toString()).isEqualTo("<5"));
            JsonNode comp = ok(n.get("/api/analitica/cohortes/" + id + "/composicion?por=atc1&" + q));
            salidas.add(comp.toString());
            comp.path("filas").forEach(f -> {
                if (f.path("pacientes").isNumber()) {
                    assertThat(f.path("pacientes").asLong()).isGreaterThanOrEqualTo(5);
                } else if (!f.path("clave").asText().startsWith("Resto")) {
                    assertThat(f.path("gasto").isNull()).isTrue();
                }
            });
            String csv = new String(n.get("/api/analitica/cohortes/" + id + "/descarga?formato=csv&vista=serie&" + q).cuerpo(), StandardCharsets.UTF_8);
            salidas.add(String.join("\n", csv.lines().filter(l -> !l.contains("Huella")).toList()));
        }
        // Particiones: ningún grupo visible con 1 a 4 pacientes; si alguno se oculta, se ocultan al menos dos (no se deduce por diferencia)
        for (String p : List.of("gcf", "grc")) {
            JsonNode c = null;
            for (JsonNode x : t.path("cohortes")) {
                if (x.path("codigo").asText().equals(p)) {
                    c = x;
                }
            }
            long ocultos = 0;
            for (JsonNode g : c.path("grupos")) {
                if (g.path("pacientes").isNull()) {
                    ocultos++;
                } else {
                    assertThat(g.path("pacientes").asLong() == 0 || g.path("pacientes").asLong() >= 5).as(p + " " + g).isTrue();
                }
            }
            assertThat(ocultos).as(p).isNotEqualTo(1);
        }
        salidas.forEach(s -> sinIndividualizacion(s, b));
    }
}
