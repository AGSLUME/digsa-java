package co.com.agsamericas.digsa.analitica.riesgo;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cohorte, GCF y GRC como filtro de los tableros y como dimensión de la predicción (A7.1; dudas AN.22 y AN.30). Con el insumo de 20 000
 * filas y sus patrones (año 2025): el total con el filtro cuadra con la suma hecha a mano sobre los miembros de la membresía del corte
 * (paciente y mes) y con la serie del motor; las particiones suman el total; alcance por perfil; supresión reforzada de las cohortes
 * sensibles (AN.24); errores con motivo; versión y criterio en los metadatos, la descarga y la traza; bitácora con el filtro; predicción por
 * cohorte con la versión guardada en el entrenamiento.
 */
class FiltroCohorteApiTest extends PruebaRiesgo {

    private static final String DESDE = "2025-01-01";
    private static final String HASTA = "2025-12-31";

    /** Suma a mano: gasto, dispensaciones y pacientes distintos (seudónimos). */
    private record Cuenta(BigDecimal gasto, long dispensaciones, Set<String> pacientes) {
    }

    private static String q(Base b, String tablero, String extra) {
        return "/api/analitica/tableros/" + tablero + "?" + PERIODO + "&corte=" + b.cargue() + (extra == null || extra.isEmpty() ? "" : "&" + extra);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static JsonNode kpi(JsonNode t, String clave) {
        for (JsonNode k : t.path("kpis")) {
            if (k.path("clave").asText().equals(clave)) {
                return k;
            }
        }
        throw new AssertionError("Sin KPI " + clave + ": " + t.path("kpis"));
    }

    private static BigDecimal gasto(JsonNode t) {
        return new BigDecimal(kpi(t, "gasto").path("valor").asText());
    }

    private static int col(JsonNode tabla, String clave) {
        int i = 0;
        for (JsonNode c : tabla.path("columnas")) {
            if (c.path("clave").asText().equals(clave)) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /** Versión de la cohorte con resultado en el corte (la regla del filtro: la de mayor número). */
    private long version(String codigo, long corte) {
        return jdbc.sql("""
                SELECT v.id FROM ana_cohortes c JOIN ana_cohortes_versiones v ON v.cohorte_id = c.id
                JOIN ana_cohortes_resultados r ON r.version_id = v.id AND r.corte_cargue_id = :c
                WHERE c.codigo = :codigo ORDER BY v.version DESC LIMIT 1""").param("c", corte).param("codigo", codigo).query(Long.class).single();
    }

    /**
     * Cuenta a mano, sin la capa semántica: lee la membresía (mes, seudónimo) de la versión en el corte y los hechos completos del cargue
     * (ana_dispensaciones), y suma en Java los que caen en un mes en que su paciente es miembro y cumplen {@code otro}.
     */
    private Cuenta aMano(long versionId, String grupo, long corte, Predicate<Map<String, Object>> otro) {
        Set<String> pares = new HashSet<>(jdbc.sql("""
                SELECT to_char(m.mes, 'YYYY-MM') || '|' || p.seudo FROM ana_cohortes_miembros m JOIN ana_pacientes p ON p.id = m.paciente_id
                WHERE m.version_id = :v AND m.corte_cargue_id = :c AND (CAST(:g AS text) IS NULL OR m.grupo = CAST(:g AS text))""")
                .param("v", versionId).param("c", corte).param("g", grupo).query(String.class).list());
        BigDecimal g = BigDecimal.ZERO.setScale(2);
        long n = 0;
        Set<String> pac = new HashSet<>();
        for (Map<String, Object> f : jdbc.sql("""
                SELECT to_char(fecha, 'YYYY-MM') AS mes, paciente_seudo AS seudo, valor_total AS valor, fuerza, esm_codigo AS esm FROM ana_dispensaciones
                WHERE cargue_id = :c AND fecha BETWEEN CAST(:d AS date) AND CAST(:h AS date)""").param("c", corte).param("d", DESDE).param("h", HASTA)
                .query().listOfRows()) {
            String seudo = String.valueOf(f.get("seudo")).strip();
            if (pares.contains(f.get("mes") + "|" + seudo) && otro.test(f)) {
                g = g.add((BigDecimal) f.get("valor"));
                n++;
                pac.add(seudo);
            }
        }
        return new Cuenta(g, n, pac);
    }

    @Test
    @DisplayName("cuadre: con cohorte=renal el total es la suma a mano sobre los miembros del mes (membresía del corte), la serie del motor y el esperado")
    void cuadreConLaMembresia() throws IOException {
        Base b = base();
        Navegador n = nuevo("anafiltro", Perfil.ANALISTA);
        long v = version("renal", b.cargue());
        JsonNode t = ok(n.get(q(b, "general", "cohorte=renal")));
        Cuenta c = aMano(v, null, b.cargue(), f -> true);
        assertThat(c.dispensaciones()).as("la cohorte renal tiene miembros sembrados").isPositive();
        assertThat(gasto(t)).isEqualByComparingTo(c.gasto());
        assertThat(kpi(t, "dispensaciones").path("valor").asLong()).isEqualTo(c.dispensaciones());
        assertThat(kpi(t, "pacientes").path("valor").asLong()).isEqualTo(c.pacientes().size())
                .isEqualTo(b.riesgo().path("cohortes_esperadas").path("renal").asLong());
        // La serie que guardó el motor (miembros del mes, nivel nacional) da el mismo gasto: una sola cifra en el tablero 10 y en el filtro
        long centavos = jdbc.sql("""
                SELECT coalesce(sum(gasto_centavos), 0) FROM ana_cohortes_serie WHERE version_id = :v AND corte_cargue_id = :c AND nivel = 'nacional'
                AND grupo = '' AND mes BETWEEN CAST(:d AS date) AND CAST(:h AS date)""").param("v", v).param("c", b.cargue()).param("d", DESDE)
                .param("h", HASTA).query(Long.class).single();
        assertThat(gasto(t)).isEqualByComparingTo(BigDecimal.valueOf(centavos, 2));
        // Sin el filtro el gasto es mayor: el filtro restringe
        assertThat(gasto(ok(n.get(q(b, "general", null))))).isGreaterThan(gasto(t));
        // Cuadre entre dimensiones con el filtro (consulta guiada por Fuerza): la suma de las filas es el KPI
        Map<String, Object> consulta = new LinkedHashMap<>();
        consulta.put("intencion", "METRICAS");
        consulta.put("metricas", List.of("GASTO", "PACIENTES"));
        consulta.put("dimensiones", List.of("FUERZA"));
        consulta.put("filtros", Map.of("COHORTE", List.of("renal")));
        consulta.put("desde", DESDE);
        consulta.put("hasta", HASTA);
        consulta.put("corte", b.cargue());
        JsonNode r = ok(n.post("/api/analitica/consulta", consulta));
        JsonNode tabla = r.path("tablas").get(0);
        BigDecimal suma = BigDecimal.ZERO;
        for (JsonNode f : tabla.path("filas")) {
            suma = suma.add(new BigDecimal(f.path("valores").get(col(tabla, "gasto")).asText()));
        }
        assertThat(suma).isEqualByComparingTo(c.gasto());
        // Los tableros 1 a 9 aceptan el filtro y responden con la cohorte en sus metadatos
        for (String tablero : List.of("general", "gasto", "atc", "principio", "medicamento", "diagnostico", "territorial", "alto-costo",
                "poliformulacion")) {
            JsonNode x = ok(n.get(q(b, tablero, "cohorte=renal")));
            assertThat(x.path("metadatos").path("filtros").toString()).as(tablero).contains("\"variable\":\"cohorte\"");
            assertThat(x.path("observaciones").toString()).as(tablero).contains("Filtrado por cohorte");
        }
        // Nada que identifique pacientes: ningún seudónimo de los miembros sale en la respuesta
        String texto = t.toString() + r;
        assertThat(c.pacientes()).noneMatch(texto::contains);
    }

    @Test
    @DisplayName("GCF y GRC: la suma por grupo es el total sin filtro (partición del mes); varios grupos se suman (O); con otra variable, Y")
    void particionesSumanElTotal() throws IOException {
        Base b = base();
        Navegador n = nuevo("anafiltro", Perfil.ANALISTA);
        JsonNode todo = ok(n.get(q(b, "general", null)));
        for (String p : List.of("grc", "gcf")) {
            JsonNode op = ok(n.get("/api/analitica/opciones?variable=" + p + "&corte=" + b.cargue())).path("opciones");
            assertThat(op.size()).as(p).isPositive();
            BigDecimal suma = BigDecimal.ZERO;
            long disp = 0;
            long vp = version(p, b.cargue());
            for (JsonNode o : op) {
                String grupo = o.path("valor").asText();
                JsonNode t = ok(n.get(q(b, "general", p + "=" + enc(grupo))));
                if (kpi(t, "gasto").path("valor").isNull()) {
                    // Grupo sensible (p. ej. GCF J05, antirretrovirales) con pocos pacientes: oculto por la reforzada; se suma a mano
                    assertThat(t.path("metadatos").path("supresion").asText()).as(grupo).startsWith("Reforzada");
                    Cuenta c = aMano(vp, grupo, b.cargue(), f -> true);
                    suma = suma.add(c.gasto());
                    disp += c.dispensaciones();
                    continue;
                }
                suma = suma.add(gasto(t));
                disp += kpi(t, "dispensaciones").path("valor").asLong();
            }
            assertThat(suma).as(p).isEqualByComparingTo(gasto(todo));
            assertThat(disp).as(p).isEqualTo(kpi(todo, "dispensaciones").path("valor").asLong());
        }
        BigDecimal g1 = gasto(ok(n.get(q(b, "general", "grc=GRC1"))));
        BigDecimal g2 = gasto(ok(n.get(q(b, "general", "grc=GRC2"))));
        assertThat(gasto(ok(n.get(q(b, "general", "grc=GRC1&grc=GRC2"))))).isEqualByComparingTo(g1.add(g2));
        // GRC1 a mano con la membresía de la partición
        long vGrc = version("grc", b.cargue());
        assertThat(g1).isEqualByComparingTo(aMano(vGrc, "GRC1", b.cargue(), f -> true).gasto());
        // Entre variables, Y: renal y GRC1 no supera a ninguna de las dos
        BigDecimal ambos = gasto(ok(n.get(q(b, "general", "cohorte=renal&grc=GRC1"))));
        assertThat(ambos).isLessThanOrEqualTo(g1).isLessThanOrEqualTo(gasto(ok(n.get(q(b, "general", "cohorte=renal")))));
        // La composición de una partición ya no es la de todos los pacientes: se consulta por grupo en los tableros
        JsonNode tarjetas = ok(n.get("/api/analitica/cohortes?corte=" + b.cargue() + "&" + PERIODO));
        long idGcf = 0;
        for (JsonNode c : tarjetas.path("cohortes")) {
            if (c.path("codigo").asText().equals("gcf")) {
                idGcf = c.path("id").asLong();
            }
        }
        assertThat(n.get("/api/analitica/cohortes/" + idGcf + "/composicion?por=atc1&corte=" + b.cargue() + "&" + PERIODO).estado()).isEqualTo(422);
    }

    @Test
    @DisplayName("alcance: Sanidad ARC y el ESM ven solo lo suyo con el filtro; VIH en el ESM se oculta entero (k = 5, cifras incluidas); por ESM nacional también")
    void alcanceYSupresionSensible() throws IOException {
        Base b = base();
        long vRenal = version("renal", b.cargue());
        String sanidad = crearUsuario("sanfiltro", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'ARC' WHERE usuario = :u").param("u", sanidad).update();
        Navegador s = como(sanidad);
        JsonNode ts = ok(s.get(q(b, "general", "cohorte=renal")));
        Cuenta arc = aMano(vRenal, null, b.cargue(), f -> "ARC".equals(f.get("fuerza")));
        assertThat(gasto(ts)).isEqualByComparingTo(arc.gasto());
        assertThat(kpi(ts, "pacientes").path("valor").asLong()).isEqualTo(arc.pacientes().size());

        long punto = jdbc.sql("SELECT id FROM puntos WHERE upper(sigla) = 'DMCOV' ORDER BY id LIMIT 1").query(Long.class).single();
        String esm = crearUsuario("esmfiltro", Perfil.ESM);
        jdbc.sql("UPDATE usuarios SET alcance_punto = :p WHERE usuario = :u").param("p", punto).param("u", esm).update();
        Navegador e = como(esm);
        // VIH en DMCOV: los sembrados (1 a 4 pacientes) quedan bajo k = 5 aunque la supresión general esté apagada: todo oculto
        JsonNode tv = ok(e.get(q(b, "general", "cohorte=vih")));
        assertThat(kpi(tv, "pacientes").path("valor").isNull()).as(tv.path("kpis").toString()).isTrue();
        assertThat(kpi(tv, "pacientes").path("texto").asText()).isEqualTo("<5");
        assertThat(kpi(tv, "gasto").path("valor").isNull()).isTrue();
        assertThat(tv.path("metadatos").path("supresion").asText()).startsWith("Reforzada");
        for (JsonNode tabla : tv.path("tablas")) {
            for (JsonNode f : tabla.path("filas")) {
                for (int i = 0; i < tabla.path("columnas").size(); i++) {
                    if (!"TEXTO".equals(tabla.path("columnas").get(i).path("tipo").asText())) {
                        assertThat(f.path("valores").get(i).isNull()).as(tabla.path("id").asText() + " " + f).isTrue();
                    }
                }
            }
        }
        // Una cohorte no sensible en el mismo ESM sí muestra sus cifras (supresión general apagada)
        JsonNode tr = ok(e.get(q(b, "general", "cohorte=renal")));
        assertThat(tr.path("metadatos").path("supresion").asText()).doesNotStartWith("Reforzada");
        assertThat(gasto(tr)).isEqualByComparingTo(aMano(vRenal, null, b.cargue(), f -> "DMCOV".equals(f.get("esm"))).gasto());
        // Las opciones de GRC del ESM son las de su alcance
        assertThat(ok(e.get("/api/analitica/opciones?variable=grc&corte=" + b.cargue())).path("opciones").size()).isPositive();

        // Nacional, tablero territorial con VIH: ninguna fila visible con 1 a 4 pacientes y las ocultas pierden también el gasto
        Navegador n = nuevo("anafiltro", Perfil.ANALISTA);
        JsonNode tt = ok(n.get(q(b, "territorial", "cohorte=vih")));
        boolean algunaOculta = false;
        for (JsonNode tabla : tt.path("tablas")) {
            int ip = col(tabla, "pacientes");
            int ig = col(tabla, "gasto");
            if (ip < 0 || ig < 0) {
                continue;
            }
            for (JsonNode f : tabla.path("filas")) {
                JsonNode p = f.path("valores").get(ip);
                if (p.isNull()) {
                    algunaOculta |= "<5".equals(f.path("textos").get(ip).asText());
                    if ("<5".equals(f.path("textos").get(ip).asText())) {
                        assertThat(f.path("valores").get(ig).isNull()).as(f.toString()).isTrue();
                    }
                } else {
                    assertThat(p.asLong() == 0 || p.asLong() >= 5).as(tabla.path("id").asText() + " " + f).isTrue();
                }
            }
        }
        assertThat(algunaOculta).as("VIH por ESM tiene celdas pequeñas (DMCOV)").isTrue();
    }

    @Test
    @DisplayName("versión y criterio en metadatos, descarga y traza; misma huella al repetir; inexistente, partición y corte sin membresía responden 422")
    void versionTrazaYErrores() throws IOException {
        Base b = base();
        String yo = crearUsuario("anafiltro", Perfil.ANALISTA);
        Navegador n = como(yo);
        JsonNode t1 = ok(n.get(q(b, "general", "cohorte=renal")));
        JsonNode t2 = ok(n.get(q(b, "general", "cohorte=renal")));
        assertThat(t1.path("huella").asText()).isEqualTo(t2.path("huella").asText());
        String criterio = jdbc.sql("SELECT criterio_texto FROM ana_cohortes_versiones WHERE id = :v").param("v", version("renal", b.cargue()))
                .query(String.class).single();
        JsonNode filtro = t1.path("metadatos").path("filtros").get(0);
        assertThat(filtro.path("variable").asText()).isEqualTo("cohorte");
        assertThat(filtro.path("textos").get(0).asText()).contains("(v").contains(criterio);
        // Descarga CSV con la cohorte, su versión y su criterio en los metadatos (14.8)
        Navegador.Respuesta csv = n.get(q(b, "general", "cohorte=renal&formato=csv").replace("/tableros/general?", "/tableros/general/descarga?"));
        assertThat(csv.estado()).isEqualTo(200);
        assertThat(csv.texto()).contains("Cohorte: ").contains(criterio);
        // Traza (14.9): los registros suman la cifra y la consulta dice con qué versión se filtró
        JsonNode traza = ok(n.get("/api/analitica/traza/general?" + PERIODO + "&corte=" + b.cargue() + "&cohorte=renal"));
        assertThat(new BigDecimal(traza.path("desglose").path("gasto").asText())).isEqualByComparingTo(gasto(t1));
        assertThat(traza.path("desglose").path("consulta").path("cohortes").path("cohorte").get(0).asText()).contains("(v");
        // Bitácora: el filtro (código de la lista blanca) queda en ana_consultas
        long id = idDe(yo);
        assertThat(jdbc.sql("SELECT filtros::text FROM ana_consultas WHERE usuario_id = :u AND accion = 'consulta' ORDER BY id DESC LIMIT 1").param("u", id)
                .query(String.class).single()).contains("\"cohorte\": [\"renal\"]");

        // Errores con su motivo (nunca cifras en cero)
        Navegador.Respuesta x = n.get(q(b, "general", "cohorte=no_existe"));
        assertThat(x.estado()).isEqualTo(422);
        assertThat(x.mensaje()).isEqualTo("Cohorte «no_existe» no existe o está fuera de su alcance.");
        Navegador.Respuesta p = n.get(q(b, "general", "cohorte=gcf"));
        assertThat(p.estado()).isEqualTo(422);
        assertThat(p.mensaje()).contains("partición");
        assertThat(n.get(q(b, "general", "grc=GRC9")).estado()).isEqualTo(422);
        assertThat(n.get(q(b, "general", "gcf=ZZ9")).estado()).isEqualTo(422);
        JsonNode op = ok(n.get("/api/analitica/opciones?variable=cohorte&corte=" + b.cargue())).path("opciones");
        assertThat(op.toString()).contains("\"valor\":\"renal\"").doesNotContain("\"valor\":\"gcf\"").doesNotContain("\"valor\":\"grc\"");

        // Cohorte nueva: v1 filtra; su v2 cambia la huella y los metadatos; un corte sin membresía responde 422
        String codigo = "filtro_prueba_" + sufijo();
        Navegador.Respuesta cr = n.post("/api/analitica/cohortes", Map.of("codigo", codigo, "nombre", "Filtro de prueba", "criterios",
                Map.of("tipo", "principiosMes", "minimo", 5)));
        assertThat(cr.estado()).as(cr.texto()).isEqualTo(201);
        long cohorte = cr.json().path("id").asLong();
        esperarLibre();
        // La versión nueva se calcula sobre el corte vigente (otra prueba del mismo contexto pudo aplicar otro cargue)
        long corteV1 = jdbc.sql("""
                SELECT r.corte_cargue_id FROM ana_cohortes_resultados r JOIN ana_cohortes_versiones v ON v.id = r.version_id
                WHERE v.cohorte_id = :c AND v.version = 1""").param("c", cohorte).query(Long.class).single();
        String ruta = "/api/analitica/tableros/general?" + PERIODO + "&corte=" + corteV1 + "&cohorte=" + codigo;
        JsonNode v1 = ok(n.get(ruta));
        assertThat(v1.path("metadatos").path("filtros").get(0).path("textos").get(0).asText()).contains("(v1:");
        Navegador.Respuesta ed = n.put("/api/analitica/cohortes/" + cohorte, Map.of("criterios", Map.of("tipo", "principiosMes", "minimo", 6)));
        assertThat(ed.estado()).as(ed.texto()).isEqualTo(200);
        esperarLibre();
        long corteV2 = jdbc.sql("""
                SELECT r.corte_cargue_id FROM ana_cohortes_resultados r JOIN ana_cohortes_versiones v ON v.id = r.version_id
                WHERE v.cohorte_id = :c AND v.version = 2""").param("c", cohorte).query(Long.class).single();
        if (corteV2 == corteV1) {
            JsonNode v2 = ok(n.get(ruta));
            assertThat(v2.path("metadatos").path("filtros").get(0).path("textos").get(0).asText()).contains("(v2:");
            assertThat(v2.path("huella").asText()).isNotEqualTo(v1.path("huella").asText());
            assertThat(gasto(v2)).isLessThanOrEqualTo(gasto(v1));
        }
        jdbc.sql("UPDATE ana_cohortes_resultados r SET miembros = false FROM ana_cohortes_versiones v WHERE v.id = r.version_id AND v.cohorte_id = :c")
                .param("c", cohorte).update();
        try {
            Navegador.Respuesta sin = n.get(ruta);
            assertThat(sin.estado()).isEqualTo(422);
            assertThat(sin.mensaje()).contains("solo conserva su serie mensual");
        } finally {
            jdbc.sql("UPDATE ana_cohortes_resultados r SET miembros = true FROM ana_cohortes_versiones v WHERE v.id = r.version_id AND v.cohorte_id = :c")
                    .param("c", cohorte).update();
        }
    }

    @Test
    @DisplayName("predicción por cohorte, GCF y GRC: la serie observada cuadra con el tablero filtrado; Top 20; el entrenamiento guarda la versión; VIH en el ESM se suprime")
    void prediccionPorCohorte() throws IOException {
        Base b = base();
        Navegador n = nuevo("anafiltro", Perfil.ANALISTA);
        JsonNode p = ok(n.get("/api/analitica/prediccion?metrica=gasto&dimension=cohorte&valorDimension=renal&corte=" + b.cargue()));
        assertThat(p.path("suprimida").asBoolean()).isFalse();
        assertThat(p.path("cohorte").asText()).contains("(v");
        BigDecimal observado = BigDecimal.ZERO;
        for (JsonNode o : p.path("observado")) {
            if (o.path("mes").asText().startsWith("2025-")) {
                observado = observado.add(new BigDecimal(o.path("valor").asText()));
            }
        }
        assertThat(observado).isEqualByComparingTo(gasto(ok(n.get(q(b, "general", "cohorte=renal")))));
        assertThat(p.path("modelo").path("metodo").asText()).isNotBlank();
        // Top 20 por gasto: cohortes usables (sin particiones) y grupos de GRC
        List<String> cohortes = new ArrayList<>();
        ok(n.get("/api/analitica/prediccion/top?metrica=gasto&dimension=cohorte&corte=" + b.cargue())).forEach(x -> cohortes.add(x.path("valor").asText()));
        assertThat(cohortes).contains("renal").doesNotContain("gcf", "grc").hasSizeLessThanOrEqualTo(20);
        JsonNode grc = ok(n.get("/api/analitica/prediccion/top?metrica=gasto&dimension=grc&corte=" + b.cargue()));
        assertThat(grc.size()).isPositive();
        assertThat(grc.get(0).path("valor").asText()).matches("^GRC[1-4]$");
        JsonNode pg = ok(n.get("/api/analitica/prediccion?metrica=dispensaciones&dimension=grc&valorDimension=" + grc.get(0).path("valor").asText()
                + "&corte=" + b.cargue()));
        assertThat(pg.path("observado").size()).isPositive();
        // Reentrenar: el entrenamiento guarda la dimensión y la versión de la cohorte
        Navegador.Respuesta r = n.post("/api/analitica/prediccion/reentrenar", Map.of("metrica", "gasto", "dimension", "cohorte", "valorDimension", "renal",
                "corte", b.cargue()));
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        Map<String, Object> e = jdbc.sql("SELECT dimension, dimension_valor, cohorte_version_id FROM ana_pred_entrenamientos WHERE id = :id")
                .param("id", r.json().path("id").asLong()).query().singleRow();
        assertThat(e.get("dimension")).isEqualTo("cohorte");
        assertThat(e.get("dimension_valor")).isEqualTo("renal");
        assertThat(((Number) e.get("cohorte_version_id")).longValue()).isEqualTo(version("renal", b.cargue()));
        JsonNode lista = ok(n.get("/api/analitica/prediccion/entrenamientos?metrica=gasto&dimension=cohorte&valorDimension=renal&corte=" + b.cargue()));
        assertThat(lista.path("entrenamientos").get(0).path("versionCohorte").asInt()).isPositive();
        // Lo consultado después usa lo guardado (misma huella de la serie)
        assertThat(ok(n.get("/api/analitica/prediccion?metrica=gasto&dimension=cohorte&valorDimension=renal&corte=" + b.cargue())).path("modelo")
                .path("registrado").asBoolean()).isTrue();
        // Inexistente: 422
        assertThat(n.get("/api/analitica/prediccion?metrica=gasto&dimension=cohorte&valorDimension=no_existe&corte=" + b.cargue()).estado()).isEqualTo(422);
        // VIH en el ESM DMCOV: meses bajo k = 5 ⇒ la serie no se muestra ni se proyecta
        long punto = jdbc.sql("SELECT id FROM puntos WHERE upper(sigla) = 'DMCOV' ORDER BY id LIMIT 1").query(Long.class).single();
        String esm = crearUsuario("esmpred", Perfil.ESM);
        jdbc.sql("UPDATE usuarios SET alcance_punto = :p WHERE usuario = :u").param("p", punto).param("u", esm).update();
        JsonNode pv = ok(como(esm).get("/api/analitica/prediccion?metrica=gasto&dimension=cohorte&valorDimension=vih&corte=" + b.cargue()));
        assertThat(pv.path("suprimida").asBoolean()).as(pv.toString()).isTrue();
        assertThat(pv.path("advertencias").toString()).contains("menos de 5");
    }

    @Test
    @DisplayName("revisión A7.1 (P1-2, P1-3): con VIH, la consulta por ESM sin pacientes los cuenta igual y oculta las filas pequeñas; el tablero 8 de un ESM con pocos pacientes no publica gasto; la comparación con pocos pacientes no se publica")
    void reforzadaSinConteoYComparacion() throws IOException {
        Base b = base();
        Navegador n = nuevo("anafiltro", Perfil.ANALISTA);
        // Consulta guiada: solo GASTO por ESM, nacional, cohorte=vih: el servicio añade los pacientes y oculta las filas pequeñas (DMCOV)
        Map<String, Object> consulta = new LinkedHashMap<>();
        consulta.put("intencion", "METRICAS");
        consulta.put("metricas", List.of("GASTO"));
        consulta.put("dimensiones", List.of("ESM"));
        consulta.put("filtros", Map.of("COHORTE", List.of("vih")));
        consulta.put("desde", DESDE);
        consulta.put("hasta", HASTA);
        consulta.put("corte", b.cargue());
        JsonNode r = ok(n.post("/api/analitica/consulta", consulta));
        JsonNode tabla = r.path("tablas").get(0);
        int ip = col(tabla, "pacientes");
        int ig = col(tabla, "gasto");
        assertThat(ip).as("la reforzada agrega los pacientes por fila").isNotNegative();
        for (JsonNode f : tabla.path("filas")) {
            JsonNode p = f.path("valores").get(ip);
            if (p.isNull()) {
                assertThat(f.path("valores").get(ig).isNull()).as(f.toString()).isTrue();
            } else {
                assertThat(p.asLong()).as(f.toString()).isGreaterThanOrEqualTo(5);
            }
            if (f.path("clave").asText().equals("DMCOV")) {
                assertThat(f.path("valores").get(ig).isNull()).as("DMCOV tiene de 1 a 4 pacientes con VIH").isTrue();
            }
        }
        // Medicamento: igual
        consulta.put("dimensiones", List.of("MEDICAMENTO"));
        JsonNode rm = ok(n.post("/api/analitica/consulta", consulta)).path("tablas").get(0);
        for (JsonNode f : rm.path("filas")) {
            JsonNode p = f.path("valores").get(col(rm, "pacientes"));
            assertThat(p.isNull() || p.asLong() >= 5).as(f.toString()).isTrue();
        }
        // Tablero 8 (alto costo) del ESM DMCOV con VIH: el total es de 1 a 4 pacientes, ninguna cifra
        JsonNode ac = ok(n.get(q(b, "alto-costo", "cohorte=vih&esm=DMCOV")));
        for (JsonNode k : ac.path("kpis")) {
            assertThat(k.path("valor").isNull()).as(k.toString()).isTrue();
        }
        // Comparación: segundo semestre frente al primero; si el periodo comparado tiene menos de 5 pacientes, su gasto no se publica
        String sem2 = "/api/analitica/tableros/gasto?desde=2025-07-01&hasta=2025-12-31&comparacion=anterior&corte=" + b.cargue() + "&cohorte=vih";
        JsonNode tc = ok(n.get(sem2));
        JsonNode sem1 = ok(n.get("/api/analitica/tableros/gasto?desde=2025-01-01&hasta=2025-06-30&corte=" + b.cargue() + "&cohorte=vih"));
        JsonNode p1 = kpi(sem1, "pacientes");
        boolean pocosAntes = p1.path("valor").isNull() || p1.path("valor").asLong() < 5;
        if (pocosAntes) {
            assertThat(kpi(tc, "gasto").path("comparado").isNull()).as(kpi(tc, "gasto").toString()).isTrue();
        }
        // En la reforzada las columnas del periodo de comparación por fila nunca se publican
        for (JsonNode t : tc.path("tablas")) {
            int icmp = col(t, "comparado");
            if (icmp >= 0) {
                for (JsonNode f : t.path("filas")) {
                    assertThat(f.path("valores").get(icmp).isNull()).as(t.path("id").asText() + " " + f).isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("revisión A7.1 (P1-1, P2-4, P2-5): partición sin membresía da 422; mezclar VIH con otra cohorte da 422; una cohorte nueva con B20 y el grupo GCF J05 son sensibles")
    void sensibilidadYParticionSinMembresia() throws IOException {
        Base b = base();
        String yo = crearUsuario("anafiltro", Perfil.ANALISTA);
        Navegador n = como(yo);
        // P1-1: un corte cuya partición no conserva la membresía (como los calculados antes de V65) responde 422, no cifras en cero
        long vGrc = version("grc", b.cargue());
        jdbc.sql("UPDATE ana_cohortes_resultados SET miembros = false WHERE version_id = :v AND corte_cargue_id = :c").param("v", vGrc)
                .param("c", b.cargue()).update();
        try {
            Navegador.Respuesta r = n.get(q(b, "general", "grc=GRC1"));
            assertThat(r.estado()).isEqualTo(422);
            assertThat(r.mensaje()).contains("no tiene su membresía por grupo");
        } finally {
            jdbc.sql("UPDATE ana_cohortes_resultados SET miembros = true WHERE version_id = :v AND corte_cargue_id = :c").param("v", vGrc)
                    .param("c", b.cargue()).update();
        }
        // P2-4: VIH junto con otra cohorte en el mismo filtro (O) da 422 con la variable
        Navegador.Respuesta mezcla = n.get(q(b, "general", "cohorte=renal&cohorte=vih"));
        assertThat(mezcla.estado()).isEqualTo(422);
        assertThat(mezcla.mensaje()).contains("No se puede combinar");
        assertThat(mezcla.json().path("variable").asText()).isEqualTo("cohorte");
        // Dos sensibles juntas sí (la diferencia sigue siendo sensible y reforzada)
        JsonNode dos = ok(n.get(q(b, "general", "cohorte=vih&cohorte=huerfanas")));
        assertThat(dos.path("metadatos").path("supresion").asText()).startsWith("Reforzada");
        // P2-5: una cohorte con otro código pero criterio de VIH (CIE-10 B20) es sensible
        String codigo = "filtro_b_" + sufijo();
        Navegador.Respuesta cr = n.post("/api/analitica/cohortes", Map.of("codigo", codigo, "nombre", "Diagnóstico B20 (prueba)", "criterios",
                Map.of("tipo", "cie10", "valores", List.of("B20", "B24"), "ventanaMeses", 12)));
        assertThat(cr.estado()).as(cr.texto()).isEqualTo(201);
        esperarLibre();
        long corteNueva = jdbc.sql("""
                SELECT r.corte_cargue_id FROM ana_cohortes_resultados r JOIN ana_cohortes_versiones v ON v.id = r.version_id
                WHERE v.cohorte_id = :c""").param("c", cr.json().path("id").asLong()).query(Long.class).single();
        JsonNode tb = ok(n.get("/api/analitica/tableros/general?" + PERIODO + "&corte=" + corteNueva + "&cohorte=" + codigo));
        assertThat(tb.path("metadatos").path("supresion").asText()).startsWith("Reforzada");
        // y por eso no se mezcla con una no sensible
        assertThat(n.get("/api/analitica/tableros/general?" + PERIODO + "&corte=" + corteNueva + "&cohorte=" + codigo + "&cohorte=renal").estado())
                .isEqualTo(422);
        // El grupo GCF J05 (antivirales: incluye los antirretrovirales de la cohorte VIH) es sensible
        JsonNode gcf = ok(n.get("/api/analitica/opciones?variable=gcf&corte=" + b.cargue())).path("opciones");
        if (gcf.toString().contains("\"valor\":\"J05\"")) {
            JsonNode tj = ok(n.get(q(b, "general", "gcf=J05")));
            assertThat(tj.path("metadatos").path("supresion").asText()).startsWith("Reforzada");
        }
        // Un grupo no sensible no se refuerza
        assertThat(ok(n.get(q(b, "general", "grc=GRC1"))).path("metadatos").path("supresion").asText()).doesNotStartWith("Reforzada");
    }

    @Test
    @DisplayName("rendimiento (volumen de las pruebas normales): el filtro es un semijoin por hash sobre la membresía de UNA versión y UN corte; tiempos de los 9 tableros con y sin filtro")
    void rendimientoDelFiltro() throws IOException {
        Base b = base();
        Navegador n = nuevo("anafiltro", Perfil.ANALISTA);
        long v = version("renal", b.cargue());
        // Plan del total con el filtro: semijoin (no un recorrido de la membresía por cada hecho)
        List<String> plan = jdbc.sql("""
                EXPLAIN (ANALYZE, COSTS OFF) SELECT sum(x.valor_centavos), count(*) FROM ana_hechos_tablero x
                WHERE x.cargue_id = ANY(CAST(:cargues AS bigint[])) AND x.fecha BETWEEN CAST(:d AS date) AND CAST(:h AS date)
                AND EXISTS (SELECT 1 FROM ana_cohortes_miembros m WHERE m.version_id = ANY(CAST(:v AS bigint[])) AND m.corte_cargue_id = :c
                    AND m.mes = CAST(date_trunc('month', x.fecha) AS date) AND m.paciente_id = x.paciente_id)""")
                .param("cargues", new Long[] {b.cargue()}).param("d", DESDE).param("h", HASTA).param("v", new Long[] {v}).param("c", b.cargue())
                .query(String.class).list();
        System.out.println("[A7.1] plan del filtro de cohorte:" + System.lineSeparator() + String.join(System.lineSeparator(), plan));
        assertThat(String.join(" | ", plan)).containsAnyOf("Semi Join", "Hash Join");
        Map<String, Object> vol = jdbc.sql("""
                SELECT (SELECT count(*) FROM ana_hechos_tablero WHERE cargue_id = :c) AS hechos,
                       (SELECT count(*) FROM ana_cohortes_miembros WHERE corte_cargue_id = :c AND grupo = '') AS miembros_cohortes,
                       (SELECT count(*) FROM ana_cohortes_miembros WHERE corte_cargue_id = :c AND grupo <> '') AS miembros_particiones,
                       pg_total_relation_size('ana_cohortes_miembros') AS bytes""").param("c", b.cargue()).query().singleRow();
        System.out.println("[A7.1] volumen: " + vol);
        String periodo = "desde=2025-01-01&hasta=2025-12-30&corte=" + b.cargue();
        for (String t : List.of("general", "gasto", "atc", "principio", "medicamento", "diagnostico", "territorial", "alto-costo", "poliformulacion")) {
            long t0 = System.nanoTime();
            ok(n.get("/api/analitica/tableros/" + t + "?" + periodo));
            long sin = (System.nanoTime() - t0) / 1_000_000;
            t0 = System.nanoTime();
            ok(n.get("/api/analitica/tableros/" + t + "?" + periodo + "&cohorte=renal"));
            long con = (System.nanoTime() - t0) / 1_000_000;
            t0 = System.nanoTime();
            ok(n.get("/api/analitica/tableros/" + t + "?" + periodo + "&grc=GRC1&grc=GRC2"));
            long grc = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("[A7.1] tablero " + t + ": sin filtro " + sin + " ms; cohorte=renal " + con + " ms; grc=GRC1,GRC2 " + grc + " ms");
        }
    }

    /** Letras al azar (el código de una cohorte solo admite minúsculas y «_»). */
    private static String sufijo() {
        StringBuilder sb = new StringBuilder();
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 8; i++) {
            sb.append((char) ('a' + r.nextInt(26)));
        }
        return sb.toString();
    }
}
