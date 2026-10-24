package co.com.agsamericas.digsa.analitica.riesgo;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.analitica.GeneradorSintetico;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cohortes por la API (docs/especificaciones/analitica-cohortes-alertas; M8-6): con el generador de 20 000 filas y sus patrones, las
 * cohortes cuadran con los conteos esperados calculados sin la base; GCF suma el total; criterio visible y versionado; composición que cuadra
 * con el gasto de la cohorte; descargas y salidas sin individualización; alcance por perfil; recálculo con cada cargue y reproducibilidad
 * por corte.
 */
class CohortesApiTest extends PruebaRiesgo {

    private static JsonNode tarjeta(JsonNode t, String codigo) {
        for (JsonNode c : t.path("cohortes")) {
            if (c.path("codigo").asText().equals(codigo)) {
                return c;
            }
        }
        throw new AssertionError("Sin la cohorte " + codigo + ": " + t.path("cohortes"));
    }

    private static long idDeCohorte(JsonNode t, String codigo) {
        return tarjeta(t, codigo).path("id").asLong();
    }

    private String tarjetas(long corte) {
        return "/api/analitica/cohortes?corte=" + corte + "&" + PERIODO;
    }

    /** Tarjetas del corte vigente (las versiones nuevas se calculan sobre él). */
    private static String vigente() {
        return "/api/analitica/cohortes?" + PERIODO;
    }

    @Test
    @DisplayName("las cohortes del A4 cuadran con los conteos esperados del generador; control especial «no evaluado» sin su lista; criterio y versión a la vista")
    void cohortesCuadranConLosEsperados() throws IOException {
        Base b = base();
        String yo = crearUsuario("anariesgo", Perfil.ANALISTA);
        Navegador n = como(yo);
        JsonNode t = ok(n.get(tarjetas(b.cargue())));
        JsonNode esperadas = b.riesgo().path("cohortes_esperadas");
        Map<String, String> diferencias = new TreeMap<>();
        esperadas.fields().forEachRemaining(e -> {
            JsonNode c = tarjeta(t, e.getKey());
            if (e.getValue().isTextual()) {
                assertThat(c.path("estado").asText()).as(e.getKey()).isEqualTo("no_evaluada");
                assertThat(c.path("motivo").asText()).as(e.getKey()).startsWith("no evaluado: sin lista de");
                return;
            }
            assertThat(c.path("estado").asText()).as(e.getKey() + ": " + c).isEqualTo("evaluada");
            if (c.path("pacientes").asLong() != e.getValue().asLong()) {
                diferencias.put(e.getKey(), "esperado " + e.getValue().asLong() + ", sistema " + c.path("pacientes").asLong());
            }
            assertThat(c.path("criterio").asText()).as(e.getKey()).endsWith(" · v1");
            assertThat(c.path("registros").asLong()).as(e.getKey()).isPositive();
        });
        assertThat(diferencias).as("cohortes que no cuadran").isEmpty();
        // La tarjeta trae el criterio en texto, el corte y el periodo, sin abrir otra pantalla (M8-6)
        JsonNode poli = tarjeta(t, "polimedicados");
        assertThat(poli.path("criterio").asText()).isEqualTo("≥ 5 principios activos distintos en cada mes durante ≥ 6 meses consecutivos · v1");
        assertThat(t.path("corteTexto").asText()).contains("cargue " + b.cargue());
        assertThat(t.path("periodo").asText()).isEqualTo("01/01/2025 – 31/12/2025");
        assertThat(tarjeta(t, "alto_costo").path("umbral").asText()).startsWith("Umbral del gasto en 12 meses en 2025-12: $ ");
        // Las seis del mockup van primero
        List<String> orden = new java.util.ArrayList<>();
        t.path("cohortes").forEach(c -> orden.add(c.path("codigo").asText()));
        assertThat(orden.subList(0, 6)).containsExactly("alto_costo", "polimedicados", "renal", "vih", "biologicos", "huerfanas");
        assertThat(t.path("metodologia").asText()).contains("GCF").contains("GRC");
    }

    @Test
    @DisplayName("GCF y GRC son particiones: en diciembre la suma de los grupos es el total de pacientes distintos del mes (nacional)")
    void gcfSumaElTotal() throws IOException {
        Base b = base();
        String yo = crearUsuario("anariesgo", Perfil.ANALISTA);
        Navegador n = como(yo);
        JsonNode t = ok(n.get(tarjetas(b.cargue())));
        long totalDic = Long.parseLong(GeneradorSintetico.leerDimensiones(b.esperado().totales()).get("mes").get("2025-12")[2]);
        for (String p : List.of("gcf", "grc")) {
            JsonNode c = tarjeta(t, p);
            long suma = 0;
            for (JsonNode g : c.path("grupos")) {
                suma += g.path("pacientes").asLong();
            }
            assertThat(suma).as(p).isEqualTo(totalDic);
            assertThat(c.path("nota").asText()).contains("un solo grupo");
        }
    }

    @Test
    @DisplayName("comité: «Diabetes con insulina» (CIE-10 E10–E14 y ATC A10A) se guarda como v1 y se calcula sin esperar otro cargue; Analista y Admin editan")
    void creaYCalculaCohorteNueva() throws IOException {
        Base b = base();
        String yo = crearUsuario("anariesgo", Perfil.ANALISTA);
        Navegador n = como(yo);
        Map<String, Object> crit = Map.of("tipo", "grupo", "op", "Y", "criterios", List.of(Map.of("tipo", "cie10", "valores", List.of("E10", "E11", "E12",
                "E13", "E14"), "ventanaMeses", 12), Map.of("tipo", "atc", "valores", List.of("A10A"), "ventanaMeses", 12)));
        Map<String, Object> cuerpo = new LinkedHashMap<>(Map.of("codigo", "diabetes_insulina", "nombre", "Diabetes con insulina", "criterios", crit));
        Navegador.Respuesta r = n.post("/api/analitica/cohortes", cuerpo);
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        assertThat(n.post("/api/analitica/cohortes", cuerpo).estado()).as("código repetido").isEqualTo(409);
        esperarLibre();
        JsonNode c = tarjeta(ok(n.get(vigente())), "diabetes_insulina");
        assertThat(c.path("estado").asText()).isEqualTo("evaluada");
        assertThat(c.path("criterio").asText()).isEqualTo("CIE-10 E10, E11, E12, E13 o E14 en 12 meses y ATC A10A en 12 meses · v1");
        assertThat(c.path("pacientes").asLong()).isPositive();
        // Criterio inválido: 422 con el motivo; Coordinador y DIGSA no crean (403)
        Map<String, Object> malo = Map.of("codigo", "mala_cohorte", "nombre", "Mala", "criterios", Map.of("tipo", "cie10", "valores", List.of("??")));
        assertThat(n.post("/api/analitica/cohortes", malo).estado()).isEqualTo(422);
        assertThat(nuevo("coordcoh", Perfil.COORDINADOR).post("/api/analitica/cohortes", cuerpo).estado()).isEqualTo(403);
        assertThat(nuevo("digsacoh", Perfil.DIGSA).post("/api/analitica/cohortes", cuerpo).estado()).isEqualTo(403);
    }

    @Test
    @DisplayName("editar el umbral de poliformulados de 5 a 6 crea la v2 con su serie; la v1 sigue consultable; la bitácora guarda antes y después")
    void editarCreaVersion() throws IOException {
        Base b = base();
        String yo = crearUsuario("anariesgo", Perfil.ANALISTA);
        Navegador n = como(yo);
        Map<String, Object> cuerpo = Map.of("codigo", "poliformulados_prueba", "nombre", "Poliformulados (prueba)", "criterios",
                Map.of("tipo", "principiosMes", "minimo", 5));
        Navegador.Respuesta r = n.post("/api/analitica/cohortes", cuerpo);
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        long id = r.json().path("id").asLong();
        esperarLibre();
        long v1 = tarjeta(ok(n.get(vigente())), "poliformulados_prueba").path("pacientes").asLong();
        assertThat(v1).isEqualTo(b.riesgo().path("cohortes_esperadas").path("poliformulados").asLong());
        Navegador.Respuesta e = n.put("/api/analitica/cohortes/" + id, Map.of("criterios", Map.of("tipo", "principiosMes", "minimo", 6)));
        assertThat(e.estado()).as(e.texto()).isEqualTo(200);
        assertThat(e.json().path("version").asInt()).isEqualTo(2);
        esperarLibre();
        JsonNode t2 = tarjeta(ok(n.get(vigente())), "poliformulados_prueba");
        assertThat(t2.path("criterio").asText()).isEqualTo("≥ 6 principios activos distintos en el mes · v2");
        assertThat(t2.path("pacientes").asLong()).isLessThanOrEqualTo(v1);
        // La v1 sigue con sus resultados
        JsonNode d1 = ok(n.get("/api/analitica/cohortes/" + id + "?version=1&" + PERIODO));
        assertThat(d1.path("estado").asText()).isEqualTo("evaluada");
        assertThat(d1.path("criterio").asText()).endsWith(" · v1");
        assertThat(d1.path("versiones")).hasSize(2);
        assertThat(bitacoraDe(idDe(yo)).stream().anyMatch(l -> l.contains("analitica_cohorte_version") && l.contains("v2")
                && l.contains("≥ 5") && l.contains("≥ 6"))).as("bitácora con antes y después").isTrue();
    }

    @Test
    @DisplayName("composición de «renal» por ATC nivel 3: la suma del gasto de los grupos es el gasto de la cohorte; Top 20 y sin individualizar")
    void composicionPorAtcCuadra() throws IOException {
        Base b = base();
        String yo = crearUsuario("anariesgo", Perfil.ANALISTA);
        Navegador n = como(yo);
        JsonNode t = ok(n.get(tarjetas(b.cargue())));
        long id = idDeCohorte(t, "renal");
        JsonNode c = ok(n.get("/api/analitica/cohortes/" + id + "/composicion?por=atc3&corte=" + b.cargue() + "&" + PERIODO));
        BigDecimal suma = BigDecimal.ZERO;
        for (JsonNode f : c.path("filas")) {
            suma = suma.add(new BigDecimal(f.path("gasto").asText()));
        }
        assertThat(suma).isEqualByComparingTo(c.path("gastoTotal").asText());
        assertThat(c.path("gastoTotalTexto").asText()).isEqualTo(tarjeta(t, "renal").path("gasto").asText());
        assertThat(c.path("filas").size()).isLessThanOrEqualTo(21);
        sinIndividualizacion(c.toString(), b);
        assertThat(n.get("/api/analitica/cohortes/" + id + "/composicion?por=otra&corte=" + b.cargue()).estado()).isEqualTo(422);
    }

    @Test
    @DisplayName("descarga de la composición de «biológicos» en CSV: criterio y versión en el encabezado y ninguna columna de paciente ni seudónimo")
    void exportaSinSeudonimos() throws IOException {
        Base b = base();
        String yo = crearUsuario("anariesgo", Perfil.ANALISTA);
        Navegador n = como(yo);
        long id = idDeCohorte(ok(n.get(tarjetas(b.cargue()))), "biologicos");
        Navegador.Respuesta r = n.get("/api/analitica/cohortes/" + id + "/descarga?formato=csv&vista=composicion&por=principio&corte=" + b.cargue() + "&" + PERIODO);
        assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        String csv = new String(r.cuerpo(), StandardCharsets.UTF_8);
        assertThat(csv).contains("Biológicos · v1").contains("ATC L01F, L04AB o L04AC");
        String sinHuella = String.join("\n", csv.lines().filter(l -> !l.contains("Huella")).toList());
        sinIndividualizacion(sinHuella, b);
        assertThat(csv.toLowerCase()).doesNotContain("seudónimo").doesNotContain("paciente_seudo");
        Navegador.Respuesta x = n.get("/api/analitica/cohortes/" + id + "/descarga?formato=xlsx&vista=serie&corte=" + b.cargue() + "&" + PERIODO);
        assertThat(x.estado()).as(x.texto()).isEqualTo(200);
    }

    @Test
    @DisplayName("alcance: Sanidad ARC ve sus conteos (menores o iguales al nacional); el ESM su establecimiento; ninguna salida individualiza")
    void alcancePorPerfil() throws IOException {
        Base b = base();
        JsonNode nacional = ok(nuevo("anariesgo", Perfil.ANALISTA).get(tarjetas(b.cargue())));
        String sanidad = crearUsuario("sanidadcoh", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'ARC' WHERE usuario = :u").param("u", sanidad).update();
        JsonNode arc = ok(como(sanidad).get(tarjetas(b.cargue())));
        assertThat(arc.path("nivel").asText()).isEqualTo("Fuerza ARC");
        // Los 8 de alto costo sembrados son del DMBOG (ARC) y los 6 polimedicados del DMBAQ (ARC)
        assertThat(tarjeta(arc, "polimedicados").path("pacientes").asLong()).isEqualTo(6);
        assertThat(tarjeta(arc, "renal").path("pacientes").asLong()).isZero();
        for (JsonNode c : arc.path("cohortes")) {
            if (c.path("pacientes").isNumber()) {
                assertThat(c.path("pacientes").asLong()).as(c.path("codigo").asText()).isLessThanOrEqualTo(tarjeta(nacional, c.path("codigo").asText())
                        .path("pacientes").asLong());
            }
        }
        long punto = jdbc.sql("SELECT id FROM puntos WHERE upper(sigla) = 'DMBAQ' ORDER BY id LIMIT 1").query(Long.class).single();
        String esm = crearUsuario("esmcoh", Perfil.ESM);
        jdbc.sql("UPDATE usuarios SET alcance_punto = :p WHERE usuario = :u").param("p", punto).param("u", esm).update();
        JsonNode e = ok(como(esm).get(tarjetas(b.cargue())));
        assertThat(tarjeta(e, "polimedicados").path("pacientes").asLong()).isEqualTo(6);
        sinIndividualizacion(nacional.toString() + arc + e, b);
        // El ESM no edita ni ve la consola
        assertThat(e.path("puedeEditar").asBoolean()).isFalse();
        assertThat(e.path("puedeVerConsola").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("los pacientes no se suman entre celdas: el nacional es el de pacientes distintos y es menor que la suma de los ESM (un paciente en dos ESM)")
    void nacionalNoEsSumaDeEsm() throws IOException {
        Base b = base();
        JsonNode t = ok(nuevo("anariesgo", Perfil.ANALISTA).get(tarjetas(b.cargue())));
        long nacional = tarjeta(t, "estrecho_margen").path("pacientes").asLong();
        long version = jdbc.sql("""
                SELECT v.id FROM ana_cohortes_versiones v JOIN ana_cohortes c ON c.id = v.cohorte_id WHERE c.codigo = 'estrecho_margen' AND v.vigente""")
                .query(Long.class).single();
        long distintos = jdbc.sql("SELECT count(DISTINCT paciente_id) FROM ana_cohortes_miembros WHERE version_id = :v AND corte_cargue_id = :c")
                .param("v", version).param("c", b.cargue()).query(Long.class).single();
        long sumaEsm = jdbc.sql("""
                SELECT coalesce(sum(n), 0) FROM (SELECT u, count(DISTINCT paciente_id) AS n FROM ana_cohortes_miembros, unnest(fuerzas_esms) u
                WHERE version_id = :v AND corte_cargue_id = :c GROUP BY u) x""").param("v", version).param("c", b.cargue()).query(Long.class).single();
        assertThat(nacional).isEqualTo(distintos);
        assertThat(sumaEsm).as("la suma de los ESM cuenta dos veces a quien dispensó en dos").isGreaterThan(nacional);
        // Y la cifra precalculada de la tarjeta es la misma que la de la membresía
        assertThat(jdbc.sql("SELECT pacientes FROM ana_cohortes_conteos WHERE version_id = :v AND corte_cargue_id = :c AND nivel = 'nacional'")
                .param("v", version).param("c", b.cargue()).query(Long.class).single()).isEqualTo(nacional);
    }

    @Test
    @DisplayName("ESM compartido: Sanidad ARC ve en la comparación por ESM del BAS02 solo sus 3 pacientes de estrecho margen, no los del Ejército")
    void nivelesConEsmCompartido() throws IOException {
        Base b = base();
        String sanidad = crearUsuario("sanidadcoh", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'ARC' WHERE usuario = :u").param("u", sanidad).update();
        Navegador s = como(sanidad);
        long id = idDeCohorte(ok(s.get(tarjetas(b.cargue()))), "estrecho_margen");
        JsonNode d = ok(s.get("/api/analitica/cohortes/" + id + "?corte=" + b.cargue() + "&" + PERIODO));
        JsonNode bas02 = null;
        for (JsonNode x : d.path("niveles")) {
            if (x.path("codigo").asText().equals("BAS02")) {
                bas02 = x;
            }
        }
        assertThat(bas02).as(d.path("niveles").toString()).isNotNull();
        assertThat(bas02.path("pacientes").asLong()).isEqualTo(3);
        // Nacional: la comparación por Fuerza no mezcla tampoco
        assertThat(jdbc.sql("SELECT count(*) FROM ana_cohortes_serie WHERE corte_cargue_id = :c AND nivel = 'esm' AND codigo = 'BAS02' AND fuerza = 'ARC'")
                .param("c", b.cargue()).query(Long.class).single()).isPositive();
    }

    @Test
    @DisplayName("cohorte sensible (VIH) vista por el ESM: k = 5 aunque la supresión general esté apagada, sin ingresos ni salidas y sin composición por CIE-10")
    void sensibleEnNivelEsm() throws IOException {
        Base b = base();
        long punto = jdbc.sql("SELECT id FROM puntos WHERE upper(sigla) = 'DMCOV' ORDER BY id LIMIT 1").query(Long.class).single();
        String esm = crearUsuario("esmvih", Perfil.ESM);
        jdbc.sql("UPDATE usuarios SET alcance_punto = :p WHERE usuario = :u").param("p", punto).param("u", esm).update();
        Navegador e = como(esm);
        JsonNode t = ok(e.get(tarjetas(b.cargue())));
        JsonNode vih = tarjeta(t, "vih");
        long real = jdbc.sql("""
                SELECT count(DISTINCT m.paciente_id) FROM ana_cohortes_miembros m JOIN ana_cohortes_versiones v ON v.id = m.version_id JOIN ana_cohortes c ON c.id = v.cohorte_id
                WHERE c.codigo = 'vih' AND v.vigente AND m.corte_cargue_id = :c AND 'DMCOV' = ANY(m.esms)""").param("c", b.cargue()).query(Long.class).single();
        assertThat(real).as("los 4 sembrados (y quizá alguno más)").isBetween(1L, 4L);
        assertThat(vih.path("pacientes").isNull()).as(vih.toString()).isTrue();
        assertThat(vih.path("pacientesTexto").asText()).isEqualTo("<5");
        assertThat(vih.path("gasto").asText()).isEqualTo("<5");
        assertThat(vih.path("registros").isNull()).isTrue();
        long id = vih.path("id").asLong();
        JsonNode d = ok(e.get("/api/analitica/cohortes/" + id + "?corte=" + b.cargue() + "&" + PERIODO));
        assertThat(d.path("avisoSupresion").asText()).contains("sensible");
        d.path("serie").forEach(f -> {
            assertThat(f.path("ingresos").isNull()).isTrue();
            assertThat(f.path("salidas").isNull()).isTrue();
        });
        assertThat(e.get("/api/analitica/cohortes/" + id + "/composicion?por=cie10&corte=" + b.cargue() + "&" + PERIODO).estado()).isEqualTo(422);
        assertThat(e.get("/api/analitica/cohortes/" + id + "/composicion?por=atc1&corte=" + b.cargue() + "&" + PERIODO).estado()).isEqualTo(200);
        // Una cohorte no sensible en el mismo ESM sí muestra sus cifras (con la supresión general apagada)
        assertThat(tarjeta(t, "biologicos").path("pacientesTexto").asText()).doesNotStartWith("<");
    }

    @Test
    @DisplayName("un solo umbral de alto costo: el KPI del tablero 8 (diciembre) es el mismo umbral que guarda la cohorte «alto_costo» para ese mes")
    void umbralAltoCostoUnificado() throws IOException {
        Base b = base();
        Navegador n = nuevo("anariesgo", Perfil.ANALISTA);
        JsonNode ac = ok(n.get("/api/analitica/tableros/alto-costo?" + PERIODO + "&corte=" + b.cargue()));
        BigDecimal tablero = null;
        for (JsonNode k : ac.path("kpis")) {
            if (k.path("clave").asText().equals("umbralAltoCosto")) {
                tablero = k.path("valor").decimalValue();
            }
        }
        String cohorte = jdbc.sql("""
                SELECT r.umbrales ->> '2025-12' FROM ana_cohortes_resultados r JOIN ana_cohortes_versiones v ON v.id = r.version_id
                JOIN ana_cohortes c ON c.id = v.cohorte_id WHERE c.codigo = 'alto_costo' AND v.vigente AND r.corte_cargue_id = :c""").param("c", b.cargue())
                .query(String.class).single();
        assertThat(tablero).isNotNull();
        assertThat(tablero).isEqualByComparingTo(cohorte);
        assertThat(tarjeta(ok(n.get(tarjetas(b.cargue()))), "alto_costo").path("umbral").asText()).endsWith(
                co.com.agsamericas.digsa.reglas.AnaliticaConsulta.moneda(new BigDecimal(cohorte)));
    }

    @Test
    @DisplayName("un cargue nuevo agrega su mes a la serie y queda en la bitácora; el corte anterior da las mismas cifras (reproducible)")
    void cargueActualizaSerieYCorteAnteriorReproducible() throws IOException {
        Base b = base();
        String yo = crearUsuario("anariesgo", Perfil.ANALISTA);
        Navegador n = como(yo);
        JsonNode antes = ok(n.get(tarjetas(b.cargue())));
        long id = idDeCohorte(antes, "biologicos");
        JsonNode serieAntes = ok(n.get("/api/analitica/cohortes/" + id + "?corte=" + b.cargue() + "&desde=2025-01-01&hasta=2026-01-31")).path("serie");
        // Un cargue pequeño de enero de 2026: dos pacientes con adalimumab
        StringBuilder csv = new StringBuilder("﻿" + GeneradorSintetico.ENCABEZADO + "\r\n");
        for (int i = 0; i < 2; i++) {
            csv.append("CC;").append(91_000_000 + i).append(";PACIENTE ENERO;01/01/1980;EJC;BAS02;Dispensación;15/01/2026;ADALIMUMAB 40 MG JERINGA PRELLENADA;")
                    .append("20011073-2;ADALIMUMAB;L04AB04;M069;2;1850000,00;3700000,00;RM10001;Crónica;FE").append(i).append("\r\n");
        }
        JsonNode fin = cargarInsumo(n, "insumo_riesgo_2026.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        assertThat(fin.path("estado").asText()).as(fin.toString()).isEqualTo("aplicado");
        long nuevo = fin.path("id").asLong();
        esperarRecalculo(nuevo);
        JsonNode serieNueva = ok(n.get("/api/analitica/cohortes/" + id + "?corte=" + nuevo + "&desde=2025-01-01&hasta=2026-01-31")).path("serie");
        assertThat(serieNueva.toString()).contains("\"mes\":\"2026-01\"");
        assertThat(serieAntes.toString()).doesNotContain("\"mes\":\"2026-01\"");
        assertThat(jdbc.sql("SELECT count(*) FROM log_transacciones WHERE tipo = 'analitica_cohorte_recalculo' AND descripcion LIKE :d")
                .param("d", "%corte " + nuevo + " %").query(Long.class).single()).isPositive();
        // El corte anterior: mismas tarjetas y misma serie
        JsonNode despues = ok(n.get(tarjetas(b.cargue())));
        assertThat(despues.path("cohortes").toString()).isEqualTo(antes.path("cohortes").toString());
        JsonNode serieOtraVez = ok(n.get("/api/analitica/cohortes/" + id + "?corte=" + b.cargue() + "&desde=2025-01-01&hasta=2026-01-31")).path("serie");
        assertThat(serieOtraVez.toString()).isEqualTo(serieAntes.toString());
    }
}
