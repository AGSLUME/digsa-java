package co.com.agsamericas.digsa.analitica.riesgo;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.analitica.GeneradorSintetico;
import co.com.agsamericas.digsa.reglas.AnaliticaAlertas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Alertas tempranas por la API (docs/especificaciones/analitica-cohortes-alertas; M8-7): con el generador de 20 000 filas y sus patrones
 * sembrados el motor detecta TODAS las alertas esperadas (la ausencia de una es falla, RESP L5647/L5649), cada alerta trae su contexto y su
 * análisis técnico, sus registros de origen se recorren en la trazabilidad, reejecutar no duplica, un tipo sin fuente se informa, la
 * parametrización deja traza y se aplica al reejecutar, la validación es humana, el aviso de severidad alta sale una vez por correo y
 * nunca por SMS, y ninguna salida identifica a un paciente. Perfiles: el ESM no abre la consola; Sanidad solo ve su Fuerza.
 */
class AlertasApiTest extends PruebaRiesgo {

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Todas las alertas de un tipo en el periodo (recorre las páginas). */
    static List<JsonNode> alertas(Navegador n, String tipo, String periodo) {
        YearMonth ym = YearMonth.parse(periodo);
        List<JsonNode> r = new ArrayList<>();
        int pagina = 1;
        while (true) {
            JsonNode l = ok(n.get("/api/analitica/alertas?tipo=" + tipo + "&desde=" + ym.atDay(1) + "&hasta=" + ym.atEndOfMonth() + "&pagina=" + pagina));
            l.path("alertas").forEach(r::add);
            if (pagina >= l.path("paginas").asInt()) {
                return r;
            }
            pagina++;
        }
    }

    /** La alerta esperada en la consola, o nula. */
    static JsonNode buscar(Navegador n, JsonNode e) {
        for (JsonNode a : alertas(n, e.path("tipo").asText(), e.path("periodo").asText())) {
            String codigo = switch (a.path("nivel").asText()) {
                case "fuerza" -> a.path("fuerza").asText();
                case "esm" -> a.path("esm").asText();
                default -> "";
            };
            if (a.path("nivel").asText().equals(e.path("nivel").asText()) && codigo.equals(e.path("codigo_nivel").asText())
                    && a.path("dimension").asText().equals(e.path("dimension").asText()) && a.path("valor").asText().equals(e.path("valor").asText())
                    && a.path("hasta").asText().startsWith(e.path("periodo").asText())
                    && (!e.has("fuerza") || e.path("fuerza").asText().equals(a.path("fuerza").asText()))) {
                return a;
            }
        }
        return null;
    }

    private static long medida(JsonNode a, String medida) {
        return switch (medida) {
            case "pacientes" -> a.path("pacientes").asLong();
            case "registros" -> a.path("registros").asLong();
            default -> a.path("observado").decimalValue().longValue();
        };
    }

    @Test
    @DisplayName("detecta TODAS las alertas sembradas del generador (tipo, nivel, dimensión, valor, periodo y mínimo); la ausencia de una es falla")
    void todasLasEsperadas() throws IOException {
        Base b = base();
        String yo = crearUsuario("anariesgo", Perfil.ANALISTA);
        Navegador n = como(yo);
        List<String> faltan = new ArrayList<>();
        for (JsonNode e : b.riesgo().path("alertas_esperadas")) {
            if (!e.path("requiere").asText().isEmpty()) {
                continue;
            }
            JsonNode a = buscar(n, e);
            if (a == null) {
                faltan.add(e.toString());
            } else if (medida(a, e.path("medida").asText()) < e.path("minimo").asLong()) {
                faltan.add(e + " ⇒ " + e.path("medida").asText() + " " + medida(a, e.path("medida").asText()));
            }
        }
        assertThat(faltan).as("alertas esperadas que no aparecen").isEmpty();
    }

    @Test
    @DisplayName("contexto completo en la consola (umbral, observado, periodo, dimensión, severidad, registros, pacientes) y análisis técnico en el detalle")
    void contextoCompleto() throws IOException {
        Base b = base();
        String yo = crearUsuario("anariesgo", Perfil.ANALISTA);
        Navegador n = como(yo);
        JsonNode l = ok(n.get("/api/analitica/alertas"));
        assertThat(l.path("total").asLong()).isPositive();
        // Orden por prioridad: primero las de severidad alta
        assertThat(l.path("alertas").get(0).path("severidad").asText()).isEqualTo("alta");
        for (JsonNode a : l.path("alertas")) {
            for (String c : List.of("umbralTexto", "observadoTexto", "periodo", "dimensionTexto", "valorTexto", "severidad", "nivelTexto", "gastoTexto")) {
                assertThat(a.path(c).asText()).as(c + " en " + a).isNotBlank();
            }
            assertThat(a.path("registros").asLong()).isPositive();
            assertThat(a.path("pacientes").isNumber()).isTrue();
        }
        JsonNode d = ok(n.get("/api/analitica/alertas/" + l.path("alertas").get(0).path("id").asLong()));
        assertThat(d.path("causa").asText()).contains("umbral de la banda");
        assertThat(d.path("valoracion").asText()).startsWith("Severidad ");
        assertThat(d.path("recomendacion").asText()).isNotBlank();
        assertThat(d.path("seleccion").path("desde").asText()).isNotBlank();
        assertThat(d.path("seleccion").path("corte").asLong()).isPositive();
        sinIndividualizacion(l.toString() + d, b);
        // Resumen por tipo con la evaluación
        assertThat(l.path("resumen").path("tipos")).hasSize(12);
    }

    @Test
    @DisplayName("los registros de origen de cada alerta esperada se recorren en la trazabilidad (14.9) y no están vacíos")
    void registrosDeOrigenNoVacios() throws IOException {
        Base b = base();
        String yo = crearUsuario("anariesgo", Perfil.ANALISTA);
        Navegador n = como(yo);
        for (JsonNode e : b.riesgo().path("alertas_esperadas")) {
            if (!e.path("requiere").asText().isEmpty()) {
                continue;
            }
            JsonNode a = buscar(n, e);
            assertThat(a).as(e.toString()).isNotNull();
            JsonNode sel = ok(n.get("/api/analitica/alertas/" + a.path("id").asLong())).path("seleccion");
            StringBuilder q = new StringBuilder("/api/analitica/traza/" + sel.path("t").asText() + "?corte=" + sel.path("corte").asLong() + "&desde="
                    + sel.path("desde").asText() + "&hasta=" + sel.path("hasta").asText());
            for (String v : List.of("fuerza", "esm", "cum", "atc")) {
                sel.path(v).forEach(x -> q.append("&").append(v).append("=").append(enc(x.asText())));
            }
            JsonNode t = ok(n.get(q.toString()));
            assertThat(t.path("total").asLong()).as("registros de origen de " + e).isGreaterThanOrEqualTo(Math.min(a.path("registros").asLong(), 1));
            assertThat(t.path("total").asLong()).as("registros de origen de " + e).isPositive();
        }
    }

    @Test
    @DisplayName("reejecutar sobre el mismo corte no duplica: el número de alertas es el mismo y la ejecución informa 0 nuevas")
    void reejecutarNoDuplica() throws IOException {
        Base b = base();
        String yo = crearUsuario("anariesgo", Perfil.ANALISTA);
        Navegador n = como(yo);
        esperarLibre();
        long antes = jdbc.sql("SELECT count(*) FROM ana_alertas").query(Long.class).single();
        Navegador.Respuesta r = n.post("/api/analitica/alertas/ejecutar", null);
        assertThat(r.estado()).as(r.texto()).isEqualTo(202);
        long ej = r.json().path("ejecucion").asLong();
        esperarLibre();
        JsonNode e = null;
        for (JsonNode x : ok(n.get("/api/analitica/alertas/ejecuciones"))) {
            if (x.path("id").asLong() == ej) {
                e = x;
            }
        }
        assertThat(e).isNotNull();
        assertThat(e.path("estado").asText()).isEqualTo("ok");
        assertThat(e.path("nuevas").asInt()).isZero();
        assertThat(e.path("actualizadas").asInt()).isPositive();
        assertThat(jdbc.sql("SELECT count(*) FROM ana_alertas").query(Long.class).single()).isEqualTo(antes);
    }

    @Test
    @DisplayName("sin pares de interacción cargados la ejecución dice «no evaluado: sin fuente…»; con el par ficticio cargado y reejecutando, sale la alerta")
    void tipoSinFuenteSeInformaYConFuenteSeDetecta() throws IOException {
        Base b = base();
        String yo = crearUsuario("anariesgo", Perfil.ANALISTA);
        Navegador n = como(yo);
        JsonNode inicial = null;
        for (JsonNode x : ok(n.get("/api/analitica/alertas/ejecuciones"))) {
            if (x.path("id").asLong() == b.ejecucionAlertas()) {
                inicial = x;
            }
        }
        assertThat(inicial).isNotNull();
        assertThat(inicial.path("porTipo").path("interaccion").path("estado").asText()).isEqualTo("no_evaluado");
        assertThat(inicial.path("porTipo").path("interaccion").path("motivo").asText()).isEqualTo("no evaluado: sin fuente de interacciones cargada");
        assertThat(inicial.path("porTipo").path("concentracion_prescripciones").path("estado").asText()).isEqualTo("evaluado");
        assertThat(inicial.path("porTipo").path("desabastecimiento").path("motivo").asText()).startsWith("no evaluado:");
        // Carga del par ficticio (con encabezado) y reejecución
        List<String> par = GeneradorSintetico.INTERACCION_EJEMPLO;
        Navegador.Respuesta c = cargarReferencia(n, "interacciones", "principio1;principio2;nota\r\n" + par.get(0) + ";" + par.get(1) + ";Ejemplo ficticio\r\n");
        assertThat(c.estado()).as(c.texto()).isEqualTo(201);
        assertThat(cargarReferencia(n, "interacciones", "principio1;principio2\r\nSOLO UNO\r\n").estado()).isEqualTo(422);
        assertThat(inicial.path("porTipo").path("fuera_indicacion").path("estado").asText()).isEqualTo("no_evaluado");
        // Indicación ficticia (colchicina ⇒ gota) y vital ficticio publicado: fuera de indicación y desabastecimiento también se detectan
        cargarIndicacionesYVitales(n);
        ejecutar(n);
        List<String> faltan = new ArrayList<>();
        for (JsonNode e : b.riesgo().path("alertas_esperadas")) {
            if (e.path("requiere").asText().isEmpty()) {
                continue;
            }
            JsonNode a = buscar(n, e);
            if (a == null) {
                faltan.add(e.toString());
                continue;
            }
            long medida = "pacientes".equals(e.path("medida").asText()) ? a.path("pacientes").asLong() : a.path("observado").decimalValue().longValue();
            assertThat(medida).as(e.toString()).isGreaterThanOrEqualTo(e.path("minimo").asLong());
            assertThat(ok(n.get("/api/analitica/alertas/" + a.path("id").asLong())).path("causa").asText()).as(e.toString()).contains("Fuente de referencia");
        }
        assertThat(faltan).as("alertas que requieren fuente y no aparecen").isEmpty();
    }

    @Test
    @DisplayName("parametrización: bajar el umbral alto de duplicidad de 30 a 10 deja traza (antes → después, usuario) y al reejecutar la de B01AF sube a alta con la v2")
    void cambioUmbralYReejecucion() throws IOException {
        Base b = base();
        String yo = crearUsuario("anariesgo", Perfil.ANALISTA);
        Navegador n = como(yo);
        esperarLibre();
        JsonNode e = null;
        for (JsonNode x : b.riesgo().path("alertas_esperadas")) {
            if (x.path("tipo").asText().equals("duplicidad_terapeutica")) {
                e = x;
            }
        }
        JsonNode antes = buscar(n, e);
        assertThat(antes.path("severidad").asText()).isEqualTo("media");
        // Otra duplicidad baja (si la hay) conserva su versión y su umbral
        Navegador.Respuesta p = n.put("/api/analitica/alertas/parametros/duplicidad_terapeutica", Map.of("alta", "10"));
        assertThat(p.estado()).as(p.texto()).isEqualTo(200);
        int version = p.json().path("version").asInt();
        assertThat(version).isGreaterThanOrEqualTo(2);
        JsonNode h = ok(n.get("/api/analitica/alertas/parametros/duplicidad_terapeutica/historial")).get(0);
        assertThat(h.path("antes").path("alta").asText()).isEqualTo("30");
        assertThat(h.path("despues").path("alta").asText()).isEqualTo("10");
        assertThat(h.path("responsable").asText()).contains("Persona");
        assertThat(bitacoraDe(idDe(yo)).stream().anyMatch(l -> l.contains("analitica_alerta_parametro") && l.contains("30") && l.contains("10")))
                .isTrue();
        // Umbrales no crecientes: 422; el Coordinador solo consulta (403)
        assertThat(n.put("/api/analitica/alertas/parametros/duplicidad_terapeutica", Map.of("media", "50")).estado()).isEqualTo(422);
        assertThat(nuevo("coordpar", Perfil.COORDINADOR).put("/api/analitica/alertas/parametros/duplicidad_terapeutica", Map.of("alta", "9")).estado())
                .isEqualTo(403);
        assertThat(n.post("/api/analitica/alertas/ejecutar", null).estado()).isEqualTo(202);
        esperarLibre();
        JsonNode despues = buscar(n, e);
        assertThat(despues.path("id").asLong()).isEqualTo(antes.path("id").asLong());
        assertThat(despues.path("severidad").asText()).isEqualTo("alta");
        assertThat(despues.path("parametrosVersion").asInt()).isEqualTo(version);
        assertThat(despues.path("umbralTexto").asText()).isEqualTo("10,00 pacientes");
        // Subir a alta en un periodo anterior (mayo; el corte llega a diciembre) no manda correo propio: queda «agrupado» en el resumen de la
        // ejecución (un solo aviso), sin avalancha de correos por historia
        long id = despues.path("id").asLong();
        assertThat(jdbc.sql("SELECT count(*) FROM alertas_eventos WHERE tipo = 'analitica_alerta_alta' AND clave = :c").param("c", "ana:" + id)
                .query(Long.class).single()).isZero();
        assertThat(despues.path("aviso").asText()).isEqualTo("agrupado");
        long ej = jdbc.sql("SELECT max(id) FROM ana_alertas_ejecuciones").query(Long.class).single();
        assertThat(jdbc.sql("SELECT count(*) FROM alertas_eventos WHERE tipo = 'analitica_alerta_alta' AND clave = :c").param("c", "ana_ej:" + ej)
                .query(Long.class).single()).isEqualTo(1);
        // Las duplicidades de severidad baja conservan la versión y el umbral con que nacieron
        assertThat(jdbc.sql("""
                SELECT count(*) FROM ana_alertas WHERE tipo = 'duplicidad_terapeutica' AND severidad = 'baja' AND (parametros_version <> 1 OR umbral <> 1)""")
                .query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("aviso de severidad alta: un evento operativo por alerta (clave ana:<id>), un correo, ningún SMS y sin datos personales; reejecutar no repite")
    void altaEncolaCorreoSinSms() throws IOException {
        Base b = base();
        String yo = crearUsuario("anariesgo", Perfil.ANALISTA);
        Navegador n = como(yo);
        JsonNode inc = null;
        for (JsonNode x : b.riesgo().path("alertas_esperadas")) {
            if (x.path("tipo").asText().equals("incremento_consumo")) {
                inc = buscar(n, x);
            }
        }
        assertThat(inc).isNotNull();
        assertThat(inc.path("severidad").asText()).isEqualTo("alta");
        long id = inc.path("id").asLong();
        Map<String, Object> ev = jdbc.sql("SELECT id, aviso_unico, detalle FROM alertas_eventos WHERE tipo = 'analitica_alerta_alta' AND clave = :c")
                .param("c", "ana:" + id).query().singleRow();
        long evento = ((Number) ev.get("id")).longValue();
        assertThat((Boolean) ev.get("aviso_unico")).isTrue();
        String detalle = (String) ev.get("detalle");
        assertThat(detalle).contains("Alerta analítica " + id).contains("N02").hasSizeLessThanOrEqualTo(500);
        sinIndividualizacion(detalle, b);
        assertThat(jdbc.sql("SELECT count(*) FROM correos_salida WHERE alerta_id = :e").param("e", evento).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM sms_salida WHERE alerta_id = :e").param("e", evento).query(Long.class).single()).isZero();
        esperarLibre();
        assertThat(n.post("/api/analitica/alertas/ejecutar", null).estado()).isEqualTo(202);
        esperarLibre();
        assertThat(jdbc.sql("SELECT count(*) FROM correos_salida WHERE alerta_id = :e").param("e", evento).query(Long.class).single()).isEqualTo(1);
        // El tipo es operativo: sin SMS y con los perfiles de la spec
        Map<String, Object> tipo = jdbc.sql("SELECT critica, sms, perfiles::text AS perfiles FROM alertas_criticas WHERE tipo = 'analitica_alerta_alta'").query()
                .singleRow();
        assertThat(tipo.get("critica")).isEqualTo(false);
        assertThat(tipo.get("sms")).isEqualTo(false);
        assertThat(tipo.get("perfiles")).isEqualTo("{COORDINADOR,DIGSA,ANALISTA}");
    }

    @Test
    @DisplayName("validación humana: en revisión sin nota; validar sin nota 422; con nota queda en el historial; validada no se descarta; DIGSA no valida (403)")
    void validacionHumana() throws IOException {
        Base b = base();
        String yo = crearUsuario("anariesgo", Perfil.ANALISTA);
        Navegador n = como(yo);
        long id = ok(n.get("/api/analitica/alertas?estado=nueva")).path("alertas").get(0).path("id").asLong();
        assertThat(n.post("/api/analitica/alertas/" + id + "/estado", Map.of("estado", "en_revision")).estado()).isEqualTo(200);
        Navegador.Respuesta sin = n.post("/api/analitica/alertas/" + id + "/estado", Map.of("estado", "validada", "nota", "corta"));
        assertThat(sin.estado()).isEqualTo(422);
        assertThat(sin.mensaje()).isEqualTo("Escriba el motivo (mínimo 10 caracteres)");
        assertThat(sin.json().path("campo").asText()).as("UI-21: la interfaz marca la nota").isEqualTo("nota");
        assertThat(ok(n.get("/api/analitica/alertas/" + id)).path("estado").asText()).isEqualTo("en_revision");
        assertThat(n.post("/api/analitica/alertas/" + id + "/estado", Map.of("estado", "validada", "nota", "Revisada con el comité: procede")).estado())
                .isEqualTo(200);
        JsonNode d = ok(n.get("/api/analitica/alertas/" + id));
        assertThat(d.path("estado").asText()).isEqualTo("validada");
        assertThat(d.path("historial")).hasSize(2);
        assertThat(d.path("historial").get(1).path("nota").asText()).isEqualTo("Revisada con el comité: procede");
        assertThat(n.post("/api/analitica/alertas/" + id + "/estado", Map.of("estado", "descartada", "nota", "Ya no aplica al corte")).estado()).isEqualTo(409);
        Navegador digsa = nuevo("digsaval", Perfil.DIGSA);
        assertThat(digsa.get("/api/analitica/alertas").estado()).isEqualTo(200);
        assertThat(digsa.post("/api/analitica/alertas/" + id + "/estado", Map.of("estado", "descartada", "nota", "Intento de la DIGSA")).estado())
                .isEqualTo(403);
        assertThat(bitacoraDe(idDe(yo)).stream().anyMatch(l -> l.contains("analitica_alerta_estado") && l.contains("validada"))).isTrue();
    }

    @Test
    @DisplayName("perfiles: el ESM no abre la consola (403) y en el tablero 11 solo ve conteos; Sanidad ARC solo su Fuerza (otra: 404); Auditor 403")
    void perfiles() throws IOException {
        Base b = base();
        long punto = jdbc.sql("SELECT id FROM puntos WHERE upper(sigla) = 'DMBOG' ORDER BY id LIMIT 1").query(Long.class).single();
        String esm = crearUsuario("esmale", Perfil.ESM);
        jdbc.sql("UPDATE usuarios SET alcance_punto = :p WHERE usuario = :u").param("p", punto).param("u", esm).update();
        Navegador e = como(esm);
        assertThat(e.get("/api/analitica/alertas").estado()).isEqualTo(403);
        assertThat(e.get("/api/analitica/alertas/catalogo").estado()).isEqualTo(403);
        JsonNode at = ok(e.get("/api/analitica/atipicos?" + PERIODO));
        assertThat(at.path("soloConteos").asBoolean()).isTrue();
        assertThat(at.has("alertas")).isFalse();
        assertThat(at.path("total").asLong()).as("la de alto costo del DMBOG").isPositive();
        String sanidad = crearUsuario("sanidadale", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'ARC' WHERE usuario = :u").param("u", sanidad).update();
        Navegador s = como(sanidad);
        JsonNode l = ok(s.get("/api/analitica/alertas"));
        assertThat(l.path("total").asLong()).isPositive();
        for (JsonNode a : l.path("alertas")) {
            assertThat(a.path("fuerza").asText()).isEqualTo("ARC");
            assertThat(a.path("nivel").asText()).isIn("fuerza", "esm");
        }
        long ejc = jdbc.sql("SELECT id FROM ana_alertas WHERE fuerza = 'EJC' OR nivel = 'nacional' ORDER BY id LIMIT 1").query(Long.class).single();
        assertThat(s.get("/api/analitica/alertas/" + ejc).estado()).isEqualTo(404);
        assertThat(nuevo("audale", Perfil.AUDITOR).get("/api/analitica/alertas").estado()).isEqualTo(403);
        // El tablero 11 de un nivel nacional trae la lista de mayor prioridad y el filtro de ATC
        JsonNode nac = ok(nuevo("anariesgo", Perfil.ANALISTA).get("/api/analitica/atipicos?" + PERIODO + "&atc=N02"));
        assertThat(nac.path("alertas").size()).isPositive();
        nac.path("alertas").forEach(a -> assertThat(a.path("valor").asText()).startsWith("N02"));
    }

    @Test
    @DisplayName("un tipo falla (error SQL real en formulación y excepción en duplicidad): los demás tipos se evalúan, la ejecución queda «con fallos» y la serie de cohortes se escribe")
    void tipoConErrorNoTumbaLosDemas() throws IOException {
        Base b = base();
        Navegador n = como(crearUsuario("anariesgo", Perfil.ANALISTA));
        esperarLibre();
        long corte = riesgo.corteVigente().orElseThrow();
        riesgo.sonda = new RecalculoRiesgo.Sonda() {
            @Override
            public void detector(AnaliticaAlertas.Tipo t, org.springframework.jdbc.core.simple.JdbcClient j) {
                if (t == AnaliticaAlertas.Tipo.FORMULACION_INUSUAL) {
                    // Falla en PostgreSQL: sin punto de guardado dejaría abortada la transacción de lectura y caerían los demás
                    j.sql("SELECT 1 / 0").query(Integer.class).single();
                }
            }

            @Override
            public void secuencia(AnaliticaAlertas.Tipo t) {
                if (t == AnaliticaAlertas.Tipo.DUPLICIDAD) {
                    throw new IllegalStateException("falla de prueba en la secuencia");
                }
            }
        };
        long ej;
        try {
            ej = ejecutar(n);
        } finally {
            riesgo.sonda = RecalculoRiesgo.Sonda.NINGUNA;
        }
        JsonNode e = null;
        for (JsonNode x : ok(n.get("/api/analitica/alertas/ejecuciones"))) {
            if (x.path("id").asLong() == ej) {
                e = x;
            }
        }
        assertThat(e).isNotNull();
        assertThat(e.path("estado").asText()).isEqualTo("con_fallos");
        assertThat(e.path("porTipo").path("formulacion_inusual").path("estado").asText()).isEqualTo("error");
        assertThat(e.path("porTipo").path("duplicidad_terapeutica").path("estado").asText()).isEqualTo("error");
        for (String t : List.of("incremento_consumo", "presentacion_inconsistente", "alto_costo", "marca_estrecho_margen")) {
            assertThat(e.path("porTipo").path(t).path("estado").asText()).as(t).isEqualTo("evaluado");
        }
        assertThat(e.path("porTipo").path("presentacion_inconsistente").path("alertas").asInt()).as("un detector SQL después del que falló").isPositive();
        Map<String, Object> coh = jdbc.sql("SELECT estado, id FROM ana_cohortes_ejecuciones WHERE corte_cargue_id = :c ORDER BY id DESC LIMIT 1").param("c", corte)
                .query().singleRow();
        assertThat(coh.get("estado")).isEqualTo("ok");
        assertThat(jdbc.sql("SELECT count(*) FROM ana_cohortes_resultados WHERE ejecucion_id = :e AND estado = 'evaluada'").param("e", coh.get("id"))
                .query(Long.class).single()).isPositive();
        assertThat(jdbc.sql("SELECT count(*) FROM ana_cohortes_serie WHERE corte_cargue_id = :c").param("c", corte).query(Long.class).single()).isPositive();
        assertThat(jdbc.sql("SELECT estado FROM ana_cargues WHERE id = :c").param("c", b.cargue()).query(String.class).single()).isEqualTo("aplicado");
        // La consola lo dice en el resumen por tipo; la siguiente ejecución sin falla vuelve a evaluar todo
        JsonNode tipos = ok(n.get("/api/analitica/alertas")).path("resumen").path("tipos");
        tipos.forEach(t -> {
            if (t.path("tipo").asText().equals("formulacion_inusual")) {
                assertThat(t.path("evaluacion").asText()).isEqualTo("error");
            }
        });
        long otra = ejecutar(n);
        assertThat(jdbc.sql("SELECT estado FROM ana_alertas_ejecuciones WHERE id = :e").param("e", otra).query(String.class).single()).isEqualTo("ok");
    }

    @Test
    @DisplayName("una versión de cohorte que lanza queda «no evaluada: error» y las demás cohortes y las alertas siguen")
    void versionConErrorNoTumbaLasDemas() throws IOException {
        base();
        Navegador n = como(crearUsuario("anariesgo", Perfil.ANALISTA));
        esperarLibre();
        long corte = riesgo.corteVigente().orElseThrow();
        riesgo.sonda = new RecalculoRiesgo.Sonda() {
            @Override
            public void version(String codigo) {
                if ("renal".equals(codigo)) {
                    throw new ArithmeticException("falla de prueba en la evaluación");
                }
            }
        };
        long ej;
        try {
            ej = ejecutar(n);
        } finally {
            riesgo.sonda = RecalculoRiesgo.Sonda.NINGUNA;
        }
        try {
            assertThat(jdbc.sql("SELECT estado FROM ana_cohortes_ejecuciones WHERE corte_cargue_id = :c ORDER BY id DESC LIMIT 1").param("c", corte)
                    .query(String.class).single()).isEqualTo("con_fallos");
            Map<String, Object> renal = jdbc.sql("""
                    SELECT r.estado, r.motivo FROM ana_cohortes_resultados r JOIN ana_cohortes_versiones v ON v.id = r.version_id JOIN ana_cohortes c ON c.id = v.cohorte_id
                    WHERE c.codigo = 'renal' AND v.vigente AND r.corte_cargue_id = :c""").param("c", corte).query().singleRow();
            assertThat(renal.get("estado")).isEqualTo("no_evaluada");
            assertThat((String) renal.get("motivo")).startsWith("no evaluada: error");
            assertThat(jdbc.sql("""
                    SELECT r.estado FROM ana_cohortes_resultados r JOIN ana_cohortes_versiones v ON v.id = r.version_id JOIN ana_cohortes c ON c.id = v.cohorte_id
                    WHERE c.codigo = 'biologicos' AND v.vigente AND r.corte_cargue_id = :c""").param("c", corte).query(String.class).single()).isEqualTo("evaluada");
            assertThat(jdbc.sql("SELECT estado FROM ana_alertas_ejecuciones WHERE id = :e").param("e", ej).query(String.class).single()).isIn("ok", "con_fallos");
            assertThat(jdbc.sql("SELECT por_tipo -> 'alto_costo' ->> 'estado' FROM ana_alertas_ejecuciones WHERE id = :e").param("e", ej).query(String.class)
                    .single()).isEqualTo("evaluado");
        } finally {
            ejecutar(n); // deja la cohorte renal evaluada otra vez para las demás pruebas
        }
    }

    @Test
    @DisplayName("ejecución huérfana «en curso» (reinicio): al arrancar pasa a «interrumpida» y el recálculo del último corte se re-encola")
    void ejecucionHuerfanaSeCierraAlArrancar() throws IOException {
        base();
        esperarLibre();
        long corte = riesgo.corteVigente().orElseThrow();
        long ec = jdbc.sql("INSERT INTO ana_cohortes_ejecuciones (origen, corte_cargue_id, inicio) VALUES ('cargue', :c, now()) RETURNING id").param("c", corte)
                .query(Long.class).single();
        long ea = jdbc.sql("INSERT INTO ana_alertas_ejecuciones (origen, corte_cargue_id, inicio) VALUES ('cargue', :c, now()) RETURNING id").param("c", corte)
                .query(Long.class).single();
        java.util.Optional<RecalculoRiesgo.Programada> p = riesgo.recuperar();
        assertThat(jdbc.sql("SELECT estado FROM ana_cohortes_ejecuciones WHERE id = :e").param("e", ec).query(String.class).single()).isEqualTo("interrumpida");
        assertThat(jdbc.sql("SELECT estado FROM ana_alertas_ejecuciones WHERE id = :e").param("e", ea).query(String.class).single()).isEqualTo("interrumpida");
        assertThat(p).isPresent();
        esperarLibre();
        assertThat(jdbc.sql("SELECT origen || ':' || estado FROM ana_alertas_ejecuciones WHERE id = :e").param("e", p.get().ejecucionAlertas())
                .query(String.class).single()).isIn("reinicio:ok", "reinicio:con_fallos");
        // Sin huérfanas no hace nada
        assertThat(riesgo.recuperar()).isEmpty();
    }

    @Test
    @DisplayName("aviso de severidad alta que falla (tipo inactivo): queda «pendiente», el reintento lo envía una sola vez y no lo duplica")
    void avisoPendienteSeReintenta() throws IOException {
        base();
        esperarLibre();
        long id = jdbc.sql("""
                SELECT id FROM ana_alertas WHERE aviso = 'no_aplica' AND severidad <> 'alta' AND periodo_hasta >= DATE '2025-10-01' AND estado = 'nueva'
                ORDER BY id LIMIT 1""").query(Long.class).single();
        jdbc.sql("UPDATE alertas_criticas SET activa = false WHERE tipo = 'analitica_alerta_alta'").update();
        try {
            jdbc.sql("UPDATE ana_alertas SET severidad = 'alta', aviso = 'pendiente' WHERE id = :id").param("id", id).update();
            riesgo.reintentarAvisos();
            assertThat(jdbc.sql("SELECT aviso FROM ana_alertas WHERE id = :id").param("id", id).query(String.class).single()).isEqualTo("pendiente");
        } finally {
            jdbc.sql("UPDATE alertas_criticas SET activa = true WHERE tipo = 'analitica_alerta_alta'").update();
        }
        riesgo.reintentarAvisos();
        Map<String, Object> a = jdbc.sql("SELECT aviso, evento_alerta_id FROM ana_alertas WHERE id = :id").param("id", id).query().singleRow();
        assertThat(a.get("aviso")).isEqualTo("enviado");
        long evento = ((Number) a.get("evento_alerta_id")).longValue();
        assertThat(jdbc.sql("SELECT count(*) FROM correos_salida WHERE alerta_id = :e").param("e", evento).query(Long.class).single()).isEqualTo(1);
        // Idempotente: otro reintento (o una reejecución) no repite el correo
        jdbc.sql("UPDATE ana_alertas SET aviso = 'pendiente' WHERE id = :id").param("id", id).update();
        riesgo.reintentarAvisos();
        assertThat(jdbc.sql("SELECT aviso FROM ana_alertas WHERE id = :id").param("id", id).query(String.class).single()).isEqualTo("enviado");
        assertThat(jdbc.sql("SELECT count(*) FROM correos_salida WHERE alerta_id = :e").param("e", evento).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM alertas_eventos WHERE tipo = 'analitica_alerta_alta' AND clave = :c").param("c", "ana:" + id)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("primer cargue histórico: sin «ingresos» en los meses de calentamiento, sin avalancha de alertas de ingresos ni de correos")
    void primerCargueSinAvalancha() throws IOException {
        Base b = base();
        // Alto costo cuenta ingresos desde abril (gracia de 2 meses) y alerta desde julio (con 3 meses de referencia); polimedicados desde octubre
        assertThat(jdbc.sql("""
                SELECT count(*) FROM ana_alertas WHERE (tipo = 'alto_costo' AND periodo_hasta < DATE '2025-07-01')
                   OR (tipo = 'polimedicados' AND periodo_hasta < DATE '2025-10-01')""").query(Long.class).single()).isZero();
        long ingresos = jdbc.sql("SELECT count(*) FROM ana_alertas WHERE tipo IN ('alto_costo', 'polimedicados')").query(Long.class).single();
        assertThat(ingresos).as("alertas de ingresos del primer cargue (las sembradas y pocas más)").isLessThanOrEqualTo(12);
        // La serie no cuenta ingresos en el primer mes de la historia
        assertThat(jdbc.sql("SELECT coalesce(sum(ingresos), 0) FROM ana_cohortes_serie WHERE corte_cargue_id = :c AND mes = DATE '2025-01-01'")
                .param("c", b.cargue()).query(Long.class).single()).isZero();
        // Correos individuales solo de los últimos meses del corte; lo anterior va en un resumen por ejecución
        assertThat(jdbc.sql("""
                SELECT count(*) FROM alertas_eventos e JOIN ana_alertas a ON 'ana:' || a.id = e.clave
                WHERE e.tipo = 'analitica_alerta_alta' AND a.periodo_hasta < DATE '2025-10-01'""").query(Long.class).single()).isZero();
        long avisos = jdbc.sql("SELECT avisos FROM ana_alertas_ejecuciones WHERE id = :e").param("e", b.ejecucionAlertas()).query(Long.class).single();
        assertThat(avisos).as("correos del primer cargue: " + jdbc.sql("SELECT tipo || ' ' || nivel || ' ' || dimension || ' ' || valor || ' ' || periodo_hasta FROM ana_alertas WHERE aviso = 'enviado' ORDER BY tipo").query(String.class).list()).isLessThanOrEqualTo(25);
    }

    @Test
    @DisplayName("ESM compartido por dos Fuerzas: Sanidad ARC ve la alerta del BAS02 solo con sus 3 pacientes y no la del Ejército; Sanidad EJC ve sus 4")
    void alcanceEsmCompartido() throws IOException {
        base();
        String arc = crearUsuario("sanidadesm", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'ARC' WHERE usuario = :u").param("u", arc).update();
        String ejc = crearUsuario("sanidadesm", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'EJC' WHERE usuario = :u").param("u", ejc).update();
        List<JsonNode> deArc = alertas(como(arc), "marca_estrecho_margen", "2025-07").stream().filter(a -> a.path("esm").asText().equals("BAS02")).toList();
        assertThat(deArc).hasSize(1);
        assertThat(deArc.getFirst().path("fuerza").asText()).isEqualTo("ARC");
        assertThat(deArc.getFirst().path("pacientes").asLong()).isEqualTo(3);
        List<JsonNode> deEjc = alertas(como(ejc), "marca_estrecho_margen", "2025-07").stream().filter(a -> a.path("esm").asText().equals("BAS02")).toList();
        assertThat(deEjc).hasSize(1);
        assertThat(deEjc.getFirst().path("pacientes").asLong()).isEqualTo(4);
    }

    @Test
    @DisplayName("niveles por tipo: configurar un nivel que el detector no evalúa responde 422 (no se acepta y calla); la consola informa los admitidos")
    void nivelNoAdmitido() throws IOException {
        base();
        Navegador n = como(crearUsuario("anariesgo", Perfil.ANALISTA));
        Navegador.Respuesta r = n.put("/api/analitica/alertas/parametros/incremento_gasto", Map.of("niveles", List.of("esm")));
        assertThat(r.estado()).isEqualTo(422);
        assertThat(r.mensaje()).contains("no se evalúa");
        assertThat(r.json().path("campo").asText()).as("UI-21: la interfaz marca «Niveles»").isEqualTo("niveles");
        JsonNode p = ok(n.get("/api/analitica/alertas/parametros"));
        p.forEach(x -> {
            if (x.path("tipo").asText().equals("formulacion_inusual")) {
                assertThat(x.path("nivelesAdmitidos").toString()).isEqualTo("[\"nacional\"]");
            }
        });
        // El tope de los umbrales: 422 con el motivo
        Navegador.Respuesta tope = n.put("/api/analitica/alertas/parametros/duplicidad_terapeutica", Map.of("alta", "99999999999999"));
        assertThat(tope.estado()).isEqualTo(422);
        assertThat(tope.json().path("campo").asText()).isEqualTo("alta");
    }

    @Test
    @DisplayName("descarga de la consola en CSV sin datos personales; la bitácora registra la consulta y la descarga")
    void descargaSinIndividualizacion() throws IOException {
        Base b = base();
        String yo = crearUsuario("anariesgo", Perfil.ANALISTA);
        Navegador n = como(yo);
        Navegador.Respuesta r = n.get("/api/analitica/alertas/descarga?formato=csv&severidad=alta");
        assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        String csv = new String(r.cuerpo(), StandardCharsets.UTF_8);
        assertThat(csv).contains("Alertas tempranas").contains("Umbral");
        sinIndividualizacion(String.join("\n", csv.lines().filter(x -> !x.contains("Huella")).toList()), b);
        assertThat(bitacoraDe(idDe(yo)).stream().anyMatch(l -> l.contains("analitica_alerta_descarga"))).isTrue();
    }
}
