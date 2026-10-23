package co.com.agsamericas.digsa.presupuesto;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Los cuatro reportes de presupuesto en el catálogo del módulo de reportes (sesión de reportes, R4): aparecen con su alcance (DIGSA todo,
 * Sanidad su fuerza, ESM su punto) y el reporte del motor trae las mismas cifras que el de la pantalla de presupuesto.
 */
class PresupuestoCatalogoReportesApiTest extends PruebaIntegracion {

    private static final AtomicInteger ANIO = new AtomicInteger(2060);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> IDS = List.of("presupuesto_ejecucion", "presupuesto_mensual", "presupuesto_movimientos", "presupuesto_alertas");

    private long esm(String fuerza) {
        return jdbc.sql("""
                SELECT p.id FROM puntos p WHERE p.unidad = 'ESM' AND p.activo AND p.sigla IS NOT NULL AND p.fuerza = :f AND p.modalidad <> 'Satélite'
                  AND NOT EXISTS (SELECT 1 FROM puntos o WHERE o.id <> p.id AND o.sigla = p.sigla) ORDER BY p.id LIMIT 1 OFFSET 3""").param("f", fuerza)
                .query(Long.class).single();
    }

    private static List<String> valores(JsonNode opciones) {
        List<String> v = new ArrayList<>();
        opciones.forEach(o -> v.add(o.path("valor").asText()));
        return v;
    }

    private static List<String> ids(JsonNode catalogo) {
        List<String> ids = new ArrayList<>();
        catalogo.path("grupos").forEach(g -> g.path("reportes").forEach(r -> ids.add(r.path("id").asText())));
        return ids;
    }

    @Test
    @DisplayName("catálogo: los 4 reportes de presupuesto con su alcance; DIGSA ve todo, Sanidad su fuerza y ESM su punto; las cifras son las de presupuesto")
    void catalogoConAlcance() throws Exception {
        long ejc = esm("EJC");
        long arc = esm("ARC");
        Navegador coord = navegador();
        coord.ingresar(crearUsuario("coordcat", Perfil.COORDINADOR), CLAVE);
        int anio = ANIO.getAndIncrement();
        long vig = coord.post("/api/presupuesto/vigencias", Map.of("anio", anio)).json().path("id").asLong();
        Map<String, Object> d = new HashMap<>(Map.of("vigenciaId", vig, "tipo", "asignacion_inicial", "soporte", "Oficio DIGSA de asignación"));
        d.put("items", List.of(Map.of("puntoId", ejc, "modalidad", "dispensacion", "valor", new BigDecimal("900000000")),
                Map.of("puntoId", arc, "modalidad", "dispensacion", "valor", new BigDecimal("300000000"))));
        assertThat(coord.multipart("/api/presupuesto/novedades", Map.of("datos", JSON.writeValueAsString(d)), Map.of(), "archivo").estado()).isEqualTo(201);

        Navegador digsa = navegador();
        digsa.ingresar(crearUsuario("digsacat", Perfil.DIGSA), CLAVE);
        JsonNode cat = digsa.get("/api/reportes/catalogo").json();
        assertThat(ids(cat)).containsAll(IDS);
        JsonNode grupo = null;
        for (JsonNode g : cat.path("grupos")) {
            if ("Ejecución presupuestal".equals(g.path("nombre").asText())) {
                grupo = g;
            }
        }
        assertThat(grupo).isNotNull();
        assertThat(valores(digsa.get("/api/reportes/presupuesto_ejecucion/opciones").json().path("punto"))).contains(String.valueOf(ejc), String.valueOf(arc));

        String sanidad = crearUsuario("sanidadcat", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'ARC' WHERE usuario = :u").param("u", sanidad).update();
        Navegador s = navegador();
        s.ingresar(sanidad, CLAVE);
        assertThat(ids(s.get("/api/reportes/catalogo").json())).containsAll(IDS);
        JsonNode opS = s.get("/api/reportes/presupuesto_ejecucion/opciones").json();
        assertThat(valores(opS.path("fuerza"))).containsExactly("ARC");
        assertThat(valores(opS.path("punto"))).contains(String.valueOf(arc)).doesNotContain(String.valueOf(ejc));
        Respuesta fuera = s.get("/api/reportes/presupuesto_ejecucion?vigencia=" + vig + "&punto=" + ejc);
        assertThat(fuera.estado()).as("un ESM de otra fuerza").isEqualTo(422);

        String esmUsuario = crearUsuario("esmcat", Perfil.ESM);
        jdbc.sql("UPDATE usuarios SET alcance_punto = :p WHERE usuario = :u").param("p", ejc).param("u", esmUsuario).update();
        Navegador e = navegador();
        e.ingresar(esmUsuario, CLAVE);
        assertThat(valores(e.get("/api/reportes/presupuesto_ejecucion/opciones").json().path("punto"))).containsExactly(String.valueOf(ejc));
        JsonNode suyo = e.get("/api/reportes/presupuesto_ejecucion?vigencia=" + vig).json();
        assertThat(suyo.path("filas").get(0).get(4).asDouble()).isEqualTo(900_000_000d);
        assertThat(suyo.toString()).contains("Total EJC").doesNotContain("Total ARC");

        // Mismas cifras que la pantalla de presupuesto, con las columnas fijas del catálogo
        JsonNode motor = digsa.get("/api/reportes/presupuesto_ejecucion?vigencia=" + vig).json();
        JsonNode pantalla = digsa.get("/api/presupuesto/reportes/ejecucion?vigencia=" + vig).json();
        assertThat(motor.path("filas")).hasSize(pantalla.path("filas").size());
        assertThat(motor.path("columnas").toString()).contains("Ejecutado", "Proyectado al corte");
        assertThat(motor.path("filas").get(0).get(4).asDouble()).as("Asignado")
                .isEqualTo(Double.parseDouble(pantalla.path("filas").get(0).get(4).asText().replace(".", "").replace(",", "."))); // la pantalla: «900000000,00»
        // Con corte, el servicio llama «Ejecutado al corte» a la columna «Ejecutado» y agrega la proyección: las dos traen datos
        JsonNode conCorte = digsa.get("/api/reportes/presupuesto_ejecucion?vigencia=" + vig + "&corte=" + anio + "-03").json();
        assertThat(conCorte.path("filas").get(0).get(7).isNull()).as("Ejecutado").isFalse();
        assertThat(conCorte.path("filas").get(0).get(10).isNull()).as("Proyectado al corte").isFalse();
        assertThat(conCorte.path("filas").get(0).get(13).asText()).as("Corte efectivo").isEqualTo(anio + "-03");
        JsonNode mensual = digsa.get("/api/reportes/presupuesto_mensual?vigencia=" + vig + "&agrupar=trimestre").json();
        assertThat(mensual.path("columnas").get(0).asText()).isEqualTo("Periodo de ejecución");
        assertThat(mensual.path("filas").get(0).get(0).asText()).isEqualTo(anio + "-T1");
        // Los cuatro reportes traen todas sus columnas (si el servicio renombra una, el proveedor falla en vez de dejarla vacía)
        java.time.LocalDate hoy = java.time.LocalDate.now(co.com.agsamericas.digsa.reglas.Fechas.BOGOTA);
        String rango = "desde=" + hoy.minusDays(2) + "&hasta=" + hoy;
        JsonNode movimientos = digsa.get("/api/reportes/presupuesto_movimientos?" + rango + "&vigencia=" + vig).json();
        assertThat(movimientos.path("filas").size()).isPositive();
        assertThat(movimientos.path("columnas").toString()).as("Usuario: dato personal, solo interventoría").doesNotContain("\"Usuario\"");
        assertThat(coord.get("/api/reportes/presupuesto_movimientos?" + rango + "&vigencia=" + vig).json().path("columnas").toString()).contains("Usuario");
        assertThat(digsa.get("/api/reportes/presupuesto_alertas?" + rango).estado()).isEqualTo(200);
        assertThat(digsa.get("/api/reportes/presupuesto_ejecucion/exportar?formato=pdf&vigencia=" + vig).estado()).isEqualTo(200);
        // Un solo ESM a la vez (el servicio de presupuesto filtra por uno)
        assertThat(digsa.get("/api/reportes/presupuesto_ejecucion?vigencia=" + vig + "&punto=" + ejc + "&punto=" + arc).mensaje()).contains("solo uno");
    }
}
