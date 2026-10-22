package co.com.agsamericas.digsa.ejecucion;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fase 9 (docs/especificaciones/riesgo-tablero): marca de intervención inmediata en el formulario y el acta, dashboard de riesgo con sus
 * factores, pesos editables solo por la coordinación, incidencias de la DIGSA y dashboard de visitas en el alcance del perfil.
 */
class RiesgoApiTest extends EscenariosVisita {

    private static JsonNode fila(JsonNode tablero, long puntoId) {
        for (JsonNode x : tablero.path("esm")) {
            if (x.path("puntoId").asLong() == puntoId) {
                return x;
            }
        }
        throw new AssertionError("el ESM " + puntoId + " no está en el tablero");
    }

    @Test
    @DisplayName("intervención inmediata solo en No cumple; llega al hallazgo del acta y suma al riesgo del ESM con su factor")
    void inmediataYPuntaje() throws Exception {
        Escenario e = escenario();
        String base = "/api/visitas/" + e.id();
        long punto = jdbc.sql("SELECT punto_id FROM visitas WHERE id = :v").param("v", e.id()).query(Long.class).single();
        completar(e);
        JsonNode f = e.lider().get(base + "/formulario").json();
        List<JsonNode> c00 = items(f, "C00");
        String codigo = c00.get(0).path("codigo").asText();

        Map<String, Object> mal = new LinkedHashMap<>(Map.of("item", codigo, "calificacion", "C", "inmediata", true));
        assertThat(e.lider().put(base + "/respuestas", Map.of("respuestas", List.of(mal))).mensaje()).contains("intervención inmediata");
        Map<String, Object> ok = new LinkedHashMap<>(Map.of("item", codigo, "calificacion", "NC", "hallazgo", "Sin control de temperatura", "inmediata", true));
        assertThat(e.lider().put(base + "/respuestas", Map.of("respuestas", List.of(ok))).estado()).isEqualTo(200);
        // Un cliente que solo edita el hallazgo no borra la marca
        Map<String, Object> sinCampo = new LinkedHashMap<>(Map.of("item", codigo, "calificacion", "NC", "hallazgo", "Sin control de temperatura en la nevera"));
        assertThat(e.lider().put(base + "/respuestas", Map.of("respuestas", List.of(sinCampo))).estado()).isEqualTo(200);
        assertThat(items(e.lider().get(base + "/formulario").json(), "C00").get(0).path("inmediata").asBoolean()).isTrue();

        planYFirmas(e, List.of("it:" + codigo));
        Respuesta envio = e.lider().post(base + "/acta", null);
        assertThat(envio.json().path("ok").asBoolean()).as(envio.texto()).isTrue();
        assertThat(jdbc.sql("SELECT inmediata FROM visitas_hallazgos WHERE visita_id = :v").param("v", e.id()).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT estado FROM visitas WHERE id = :v").param("v", e.id()).query(String.class).single()).isEqualTo("cerrada");

        JsonNode t = e.coord().get("/api/riesgo").json();
        JsonNode x = fila(t, punto);
        assertThat(x.path("datos").path("inmediata").asInt()).isEqualTo(1);
        assertThat(x.path("datos").path("nc").asInt()).isEqualTo(1);
        assertThat(x.path("total").asInt()).isPositive();
        assertThat(x.path("factores").path("inmediata").asDouble()).isPositive();
        assertThat(t.path("factores").size()).isEqualTo(5);
        assertThat(t.path("pais").path("esm").asInt()).isPositive();

        // La incidencia abierta de la DIGSA suma; al cerrarla deja de sumar
        Respuesta inc = e.coord().post("/api/riesgo/incidencias", Map.of("puntoId", punto, "fecha", LocalDate.now(reloj.withZone(Fechas.BOGOTA)).toString(),
                "descripcion", "Quejas repetidas por medicamentos no entregados", "fuente", "Oficio DIGSA de prueba"));
        assertThat(inc.estado()).as(inc.texto()).isEqualTo(200);
        assertThat(fila(e.coord().get("/api/riesgo").json(), punto).path("datos").path("incidencias").asInt()).isEqualTo(1);
        assertThat(e.coord().post("/api/riesgo/incidencias/" + inc.json().path("id").asLong() + "/cerrar", Map.of("motivo", "La DIGSA confirmó la entrega"))
                .estado()).isEqualTo(200);
        assertThat(fila(e.coord().get("/api/riesgo").json(), punto).path("datos").path("incidencias").asInt()).isZero();
        assertThat(e.coord().post("/api/riesgo/incidencias", Map.of("puntoId", punto, "fecha", LocalDate.now(reloj.withZone(Fechas.BOGOTA)).plusDays(1).toString(),
                "descripcion", "Incidencia con fecha futura")).estado()).as("fecha futura").isEqualTo(422);
    }

    @Test
    @DisplayName("pesos: solo la coordinación los cambia, deben sumar 100 y el tablero los usa; la DIGSA consulta pero no edita")
    void pesos() {
        Navegador coord = entrar(crearUsuario("coord", Perfil.COORDINADOR));
        Navegador digsa = entrar(crearUsuario("digsa", Perfil.DIGSA));
        Map<String, Integer> malos = Map.of("incumplimiento", 50, "recurrencia", 20, "inmediata", 15, "planes", 20, "incidencias", 15);
        assertThat(coord.put("/api/riesgo/pesos", Map.of("pesos", malos, "alto", 60, "medio", 30)).estado()).as("suman 120").isEqualTo(422);
        Map<String, Integer> nuevos = Map.of("incumplimiento", 40, "recurrencia", 20, "inmediata", 10, "planes", 15, "incidencias", 15);
        try {
            assertThat(digsa.put("/api/riesgo/pesos", Map.of("pesos", nuevos, "alto", 60, "medio", 30)).estado()).isEqualTo(403);
            Respuesta r = coord.put("/api/riesgo/pesos", Map.of("pesos", nuevos, "alto", 70, "medio", 35));
            assertThat(r.estado()).as(r.texto()).isEqualTo(200);
            JsonNode t = digsa.get("/api/riesgo").json();
            assertThat(t.path("pesos").path("incumplimiento").asInt()).isEqualTo(40);
            assertThat(t.path("umbrales").path("alto").asInt()).isEqualTo(70);
            assertThat(jdbc.sql("SELECT count(*) FROM log_transacciones WHERE descripcion LIKE '%Pesos del riesgo:%'").query(Long.class).single())
                    .isPositive();
        } finally {
            coord.put("/api/riesgo/pesos", Map.of("pesos", Map.of("incumplimiento", 30, "recurrencia", 20, "inmediata", 15, "planes", 20, "incidencias", 15),
                    "alto", 60, "medio", 30));
        }
    }

    @Test
    @DisplayName("alcance: Sanidad ve solo su fuerza, un ESM de suministro no entra al riesgo, el auditor no entra")
    void alcance() {
        long ejc = jdbc.sql("""
                INSERT INTO puntos (nombre, unidad, fuerza, modalidad, municipio, correos, creado_por)
                VALUES (:n, 'ESM', 'EJC', 'Dispensación', '05001', ARRAY['contacto.punto@example.com'], 'prueba') RETURNING id""")
                .param("n", unico("Riesgo EJC ")).query(Long.class).single();
        long suministro = jdbc.sql("""
                INSERT INTO puntos (nombre, unidad, fuerza, modalidad, municipio, correos, creado_por)
                VALUES (:n, 'ESM', 'EJC', 'Suministro', '05001', ARRAY['contacto.punto@example.com'], 'prueba') RETURNING id""")
                .param("n", unico("Riesgo suministro ")).query(Long.class).single();
        String arc = crearUsuario("sanarc", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'ARC' WHERE usuario = :u").param("u", arc).update();
        String ejcU = crearUsuario("sanejc", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'EJC' WHERE usuario = :u").param("u", ejcU).update();

        List<Long> vistosArc = new java.util.ArrayList<>();
        entrar(arc).get("/api/riesgo").json().path("esm").forEach(x -> vistosArc.add(x.path("puntoId").asLong()));
        assertThat(vistosArc).doesNotContain(ejc, suministro);
        List<Long> vistosEjc = new java.util.ArrayList<>();
        entrar(ejcU).get("/api/riesgo").json().path("esm").forEach(x -> vistosEjc.add(x.path("puntoId").asLong()));
        assertThat(vistosEjc).contains(ejc).doesNotContain(suministro);
        assertThat(entrar(auditor("audr", "quimico_farmaceutico")).get("/api/riesgo").estado()).isEqualTo(403);
    }

    @Test
    @DisplayName("dashboard de visitas: indicadores por estado en el periodo; el OPL no entra; una fecha inválida usa el periodo por defecto")
    void dashboard() {
        Navegador coord = entrar(crearUsuario("coord", Perfil.COORDINADOR));
        String hoy = LocalDate.now(reloj.withZone(Fechas.BOGOTA)).toString();
        Respuesta d = coord.get("/api/dashboard?desde=" + hoy.substring(0, 4) + "-01-01&hasta=" + hoy);
        assertThat(d.estado()).as(d.texto()).isEqualTo(200);
        JsonNode ind = d.json().path("indicadores");
        assertThat(ind.has("total") && ind.has("cerradas") && ind.has("criticas")).isTrue();
        assertThat(coord.get("/api/dashboard/opciones").estado()).isEqualTo(200);
        Respuesta x = coord.get("/api/dashboard/exportar?formato=xlsx&desde=" + hoy.substring(0, 4) + "-01-01&hasta=" + hoy);
        assertThat(x.estado()).isEqualTo(200);
        assertThat(coord.get("/api/dashboard?desde=basura").estado()).as("una fecha inválida usa el periodo por defecto").isEqualTo(200);
        assertThat(entrar(crearUsuario("oplr", Perfil.OPL_REGIONAL)).get("/api/dashboard").estado()).isEqualTo(403);
        assertThat(entrar(crearUsuario("oplr2", Perfil.OPL_REGIONAL)).get("/api/riesgo").estado()).isEqualTo(403);
    }
}
