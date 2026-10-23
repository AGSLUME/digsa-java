package co.com.agsamericas.digsa.ejecucion;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fase 11, parte C (docs/especificaciones/despliegue-carga, duda 25.3): resultados del dashboard por categoría (con «Sin calificar» y
 * las obligaciones del contrato), por prestador (nivel central = operador logístico; «Sin prestador»), vacíos de mayor riesgo con No
 * cumple (todos, también en 0), la descarga con las mismas cifras de la pantalla y el alcance por perfil. Los datos se insertan
 * controlados en un año sin visitas reales (2001) y se comparan las diferencias antes y después.
 */
class DashboardApiTest extends EscenariosVisita {

    private static final String FECHA = "2001-06-15";
    private static final String FILTRO = "desde=2001-01-01&hasta=2001-12-31&fuerza=EJC";

    private long punto(String prefijo, String unidad) {
        return jdbc.sql("""
                INSERT INTO puntos (nombre, unidad, fuerza, modalidad, municipio, correos, creado_por)
                VALUES (:n, :u, 'EJC', :m, '05001', ARRAY['contacto.punto@example.com'], 'prueba') RETURNING id""")
                .param("n", unico(prefijo)).param("u", unidad).param("m", "ESM".equals(unidad) ? "Dispensación" : null).query(Long.class).single();
    }

    private long visita(long punto, long usuario, String estado) {
        return jdbc.sql("""
                INSERT INTO visitas (tipo, punto_id, fecha, auditor_id, alcance, estado, checklist_version, creado_por)
                VALUES ('anual', :p, :f, :u, 'Dashboard de prueba.', :e, (SELECT version FROM chk_versiones WHERE vigente), :u) RETURNING id""")
                .param("p", punto).param("f", LocalDate.parse(FECHA)).param("u", usuario).param("e", estado).query(Long.class).single();
    }

    private void resumen(long visita, int c, int nc, int na, int pendientes, String porComponente) {
        jdbc.sql("""
                INSERT INTO visitas_resumen (visita_id, c, nc, na, pendientes, hallazgos, por_componente)
                VALUES (:v, :c, :nc, :na, :p, :nc, CAST(:j AS jsonb))""")
                .param("v", visita).param("c", c).param("nc", nc).param("na", na).param("p", pendientes).param("j", porComponente).update();
    }

    private void respuesta(long visita, long item, String parte, String calificacion, long usuario) {
        jdbc.sql("""
                INSERT INTO visitas_respuestas (visita_id, item_id, parte, calificacion, hallazgo, usuario_id)
                VALUES (:v, :i, :p, :c, CASE WHEN :c = 'NC' THEN 'Hallazgo de prueba' END, :u)""")
                .param("v", visita).param("i", item).param("p", parte).param("c", calificacion).param("u", usuario).update();
    }

    /** codigo → {c, nc, na, pendientes}. */
    private static Map<String, long[]> filas(JsonNode lista, String clave) {
        Map<String, long[]> m = new LinkedHashMap<>();
        lista.forEach(x -> m.put(x.path(clave).asText(),
                new long[] {x.path("c").asLong(), x.path("nc").asLong(), x.path("na").asLong(), x.path("pendientes").asLong()}));
        return m;
    }

    private static long[] delta(Map<String, long[]> antes, Map<String, long[]> despues, String k) {
        long[] a = antes.getOrDefault(k, new long[4]);
        long[] d = despues.getOrDefault(k, new long[4]);
        return new long[] {d[0] - a[0], d[1] - a[1], d[2] - a[2], d[3] - a[3]};
    }

    private static Map<String, Long> vacios(JsonNode r) {
        Map<String, Long> m = new LinkedHashMap<>();
        r.path("vacios").forEach(x -> m.put(x.path("codigo").asText(), x.path("nc").asLong()));
        return m;
    }

    @Test
    @DisplayName("resultados por categoría con obligaciones y sin calificar, prestador con nivel central y «Sin prestador», todos los vacíos y la misma cifra en el CSV")
    void resultadosNuevos() {
        String coordU = crearUsuario("coord", Perfil.COORDINADOR);
        long coordId = idDe(coordU);
        Navegador coord = entrar(coordU);
        JsonNode antes = coord.get("/api/dashboard?" + FILTRO).json().path("resultados");

        long esm = punto("Dash ESM ", "ESM");
        long nc = punto("Dash NC ", "NC");
        long fa = punto("Dash FA ", "FA");
        // ESM: C05 con 1 C y 3 sin calificar; 2 obligaciones en No cumple y 1 sin calificar
        long v1 = visita(esm, coordId, "cerrada");
        resumen(v1, 1, 2, 0, 4, "{\"C05\":{\"c\":1,\"nc\":0,\"na\":0,\"pendientes\":3},\"OBL\":{\"c\":0,\"nc\":2,\"na\":0,\"pendientes\":1}}");
        // Nivel central: 4 No cumple en C01 (cuenta en «Operador logístico»)
        long v2 = visita(nc, coordId, "cerrada");
        resumen(v2, 0, 4, 0, 0, "{\"C01\":{\"c\":0,\"nc\":4,\"na\":0,\"pendientes\":0}}");
        // Farmacia alterna: 2 Cumple en C02
        long v3 = visita(fa, coordId, "cerrada");
        resumen(v3, 2, 0, 0, 0, "{\"C02\":{\"c\":2,\"nc\":0,\"na\":0,\"pendientes\":0}}");
        // Cerrada sin resumen: «Sin prestador»; cancelada: no cuenta
        visita(nc, coordId, "cerrada"); // nivel central (fuera del riesgo, que solo mide ESM)
        long v5 = visita(esm, coordId, "cancelada");
        resumen(v5, 0, 9, 0, 0, "{\"C05\":{\"c\":0,\"nc\":9,\"na\":0,\"pendientes\":0}}");

        // Vacíos: 2 No cumple en ítems del vacío 2 (uno marcado en trámite) y 1 Cumple en el vacío 1; la cancelada no cuenta
        List<Map<String, Object>> vacio2 = jdbc.sql("""
                SELECT i.id, cc.codigo FROM chk_items i JOIN chk_componentes cc ON cc.id = i.componente_id JOIN chk_versiones cv ON cv.version = cc.version AND cv.vigente
                WHERE split_part(i.prioridad, ' ', 1) = '2' AND cc.codigo <> 'C00' ORDER BY i.id LIMIT 2""").query().listOfRows();
        List<Map<String, Object>> vacio1 = jdbc.sql("""
                SELECT i.id, cc.codigo FROM chk_items i JOIN chk_componentes cc ON cc.id = i.componente_id JOIN chk_versiones cv ON cv.version = cc.version AND cv.vigente
                WHERE split_part(i.prioridad, ' ', 1) = '1' AND cc.codigo <> 'C00' ORDER BY i.id LIMIT 1""").query().listOfRows();
        assertThat(vacio2).as("la versión vigente tiene ítems del vacío 2").hasSize(2);
        assertThat(vacio1).hasSize(1);
        for (Map<String, Object> i : vacio2) {
            respuesta(v1, ((Number) i.get("id")).longValue(), (String) i.get("codigo"), "NC", coordId);
            respuesta(v5, ((Number) i.get("id")).longValue(), (String) i.get("codigo"), "NC", coordId);
        }
        jdbc.sql("UPDATE visitas_respuestas SET en_tramite = true WHERE visita_id = :v AND item_id = :i").param("v", v1)
                .param("i", ((Number) vacio2.get(0).get("id")).longValue()).update();
        respuesta(v1, ((Number) vacio1.get(0).get("id")).longValue(), (String) vacio1.get(0).get("codigo"), "C", coordId);

        Respuesta resp = coord.get("/api/dashboard?" + FILTRO);
        assertThat(resp.estado()).as(resp.texto()).isEqualTo(200);
        JsonNode despues = resp.json().path("resultados");

        // Categorías: todos los componentes de la versión vigente más las obligaciones, también en 0; NC descendente
        List<String> vigentes = jdbc.sql("SELECT cc.codigo FROM chk_componentes cc JOIN chk_versiones cv ON cv.version = cc.version AND cv.vigente")
                .query(String.class).list();
        Map<String, long[]> catA = filas(antes.path("porCategoria"), "codigo");
        Map<String, long[]> catD = filas(despues.path("porCategoria"), "codigo");
        assertThat(catD.keySet()).containsAll(vigentes).contains("OBL");
        assertThat(despues.path("porComponente").isMissingNode()).as("«Resultados por categoría» reemplaza «por componente»").isTrue();
        assertThat(delta(catA, catD, "OBL")).containsExactly(0, 2, 0, 1);
        assertThat(delta(catA, catD, "C05")).containsExactly(1, 0, 0, 3);
        assertThat(delta(catA, catD, "C01")).containsExactly(0, 4, 0, 0);
        assertThat(delta(catA, catD, "C02")).containsExactly(2, 0, 0, 0);
        List<Long> ncs = new ArrayList<>();
        catD.values().forEach(x -> ncs.add(x[1]));
        assertThat(ncs).isSortedAccordingTo((a, b) -> Long.compare(b, a));
        for (JsonNode x : despues.path("porCategoria")) {
            if ("OBL".equals(x.path("codigo").asText())) {
                assertThat(x.path("nombre").asText()).isEqualTo("Obligaciones del contrato");
            }
        }

        // Prestador: ESM y nivel central = operador logístico; FA aparte; cerrada sin resumen = «Sin prestador»
        Map<String, long[]> preA = filas(antes.path("porPrestador"), "prestador");
        Map<String, long[]> preD = filas(despues.path("porPrestador"), "prestador");
        assertThat(delta(preA, preD, "Operador logístico")).containsExactly(1, 6, 0, 4);
        assertThat(delta(preA, preD, "Farmacia alterna")).containsExactly(2, 0, 0, 0);
        assertThat(preD).containsKey("Sin prestador");

        // Vacíos: los de la versión vigente (5 en la v6), ordenados, el 2 con 2 y los demás sin cambio (el trámite cuenta: D5)
        long nVacios = jdbc.sql("SELECT count(*) FROM chk_vacios vc JOIN chk_versiones cv ON cv.version = vc.version AND cv.vigente").query(Long.class).single();
        Map<String, Long> vacA = vacios(antes);
        Map<String, Long> vacD = vacios(despues);
        assertThat((long) vacD.size()).isEqualTo(nVacios);
        assertThat(new ArrayList<>(vacD.keySet())).isSortedAccordingTo((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)));
        vacD.forEach((k, n) -> assertThat(n - vacA.getOrDefault(k, 0L)).as("vacío " + k).isEqualTo("2".equals(k) ? 2 : 0));

        // La descarga trae las mismas cifras de la pantalla (25.3)
        Respuesta csv = coord.get("/api/dashboard/exportar?formato=csv&" + FILTRO);
        assertThat(csv.estado()).isEqualTo(200);
        String texto = csv.texto();
        JsonNode pantalla = coord.get("/api/dashboard?" + FILTRO).json().path("resultados");
        JsonNode hab = pantalla.path("habilitacion");
        assertThat(texto).contains("Habilitación;Habilitado;" + hab.path("habilitado").asLong() + ";;;;\r\n")
                .contains("Habilitación;No habilitado · en trámite;" + hab.path("tramite").asLong() + ";;;;\r\n")
                .contains("Habilitación;No habilitado sin soporte;" + hab.path("no_habilitado").asLong() + ";;;;\r\n");
        for (JsonNode x : pantalla.path("porCategoria")) {
            String codigo = x.path("codigo").asText();
            String etiqueta = "OBL".equals(codigo) ? x.path("nombre").asText() : codigo + " · " + x.path("nombre").asText();
            assertThat(texto).contains("Resultados por categoría;" + etiqueta + ";;" + x.path("c").asLong() + ";" + x.path("nc").asLong() + ";"
                    + x.path("na").asLong() + ";" + x.path("pendientes").asLong() + "\r\n");
        }
        for (JsonNode x : pantalla.path("porPrestador")) {
            assertThat(texto).contains("Resultados por prestador;" + x.path("prestador").asText() + ";;" + x.path("c").asLong() + ";" + x.path("nc").asLong()
                    + ";" + x.path("na").asLong() + ";" + x.path("pendientes").asLong() + "\r\n");
        }
        for (JsonNode x : pantalla.path("vacios")) {
            assertThat(texto).contains("Vacíos de mayor riesgo con No cumple;" + x.path("nombre").asText() + ";;;" + x.path("nc").asLong() + ";;\r\n");
        }
        for (String formato : List.of("xlsx", "pdf", "txt")) {
            assertThat(coord.get("/api/dashboard/exportar?formato=" + formato + "&" + FILTRO).estado()).as(formato).isEqualTo(200);
        }

        // Alcance: Sanidad del Ejército ve exactamente lo mismo; Sanidad de la Armada, nada (pero las filas en 0 siguen)
        String ejc = crearUsuario("sanejc", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'EJC' WHERE usuario = :u").param("u", ejc).update();
        JsonNode deEjc = entrar(ejc).get("/api/dashboard?" + FILTRO).json().path("resultados");
        for (String k : List.of("porCategoria", "porPrestador", "vacios", "habilitacion")) {
            assertThat(deEjc.path(k)).as(k).isEqualTo(pantalla.path(k));
        }
        String arc = crearUsuario("sanarc", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'ARC' WHERE usuario = :u").param("u", arc).update();
        JsonNode deArc = entrar(arc).get("/api/dashboard?" + FILTRO).json().path("resultados");
        assertThat(deArc.path("porPrestador").size()).isZero();
        Map<String, Long> totalArc = new HashMap<>();
        deArc.path("porCategoria").forEach(x -> totalArc.merge("t", x.path("c").asLong() + x.path("nc").asLong() + x.path("na").asLong()
                + x.path("pendientes").asLong(), Long::sum));
        assertThat(totalArc.getOrDefault("t", 0L)).isZero();
        assertThat(filas(deArc.path("porCategoria"), "codigo").keySet()).containsAll(vigentes).contains("OBL");
        assertThat(vacios(deArc).values()).hasSize((int) nVacios).containsOnly(0L);
    }
}
