package co.com.agsamericas.digsa.analitica.riesgo;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.analitica.GeneradorSintetico;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.TestPropertySource;

/**
 * Tiempo del recálculo de cohortes y alertas al aplicar el cargue de 2 M filas (con los patrones sembrados) frente al tiempo del propio
 * cargue (meta: no duplicarlo), y de las consultas de la consola de alertas y de las cohortes (meta: segundos; límite A5 2.7: 3 min).
 * Bajo demanda:
 * <pre>
 *   ./mvnw -q test -Dtest=RendimientoCohortesAlertasTest -Ddigsa.rendimiento=true -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * Reutiliza {@code .local/analitica/sintetico_2000000_patrones.csv} (lo genera una sola vez si no existe).
 */
@Tag("rendimiento")
@EnabledIfSystemProperty(named = "digsa.rendimiento", matches = "true")
@TestPropertySource(properties = {"digsa.analitica.parte-max-mb=50", "digsa.analitica.riesgo.recalcular-al-aplicar=true",
    "digsa.analitica.tableros.precalentar=false"})
class RendimientoCohortesAlertasTest extends PruebaIntegracion {

    static final int FILAS = Integer.getInteger("digsa.rendimiento.filas", 2_000_000);
    static final int PARTE = 50 * 1024 * 1024;
    static final long LIMITE_VER_MS = 180_000;

    @Test
    @DisplayName("2 M filas: cohortes y alertas caben en la aplicación del cargue sin duplicar su tiempo; consola y cohortes en segundos")
    void cohortesYAlertasSobreDosMillones() throws Exception {
        Path carpeta = Path.of(System.getProperty("digsa.rendimiento.carpeta", "../../.local/analitica"));
        String base = "sintetico_" + FILAS + "_patrones";
        Path csv = carpeta.resolve(base + ".csv");
        Path esperadas = carpeta.resolve("totales_esperados_" + FILAS + "_patrones.json");
        // El archivo en caché se regenera si falta su JSON de esperados o si es de otra versión de los patrones del generador
        boolean vigente = Files.exists(csv) && Files.exists(esperadas) && GeneradorSintetico.VERSION_PATRONES.equals(
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(esperadas.toFile()).path("riesgo").path("version_patrones").asText());
        if (!vigente) {
            Files.createDirectories(carpeta);
            Path tmp = carpeta.resolve("patrones");
            GeneradorSintetico.Resultado r = GeneradorSintetico.generar(tmp, base, FILAS, GeneradorSintetico.SEMILLA, true);
            Files.move(r.csv(), csv, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.move(r.totales(), esperadas, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        String analista = crearUsuario("analistarendrie", Perfil.ANALISTA);
        Navegador n = navegador();
        assertThat(n.ingresar(analista, CLAVE).estado()).isEqualTo(200);
        // Las referencias de ejemplo de las pruebas de API antes del cargue: estrecho margen, huérfanas, interacciones, indicaciones y vitales
        // (con todas, cada cohorte y cada tipo sembrado se evalúan y se exigen)
        List<Map<String, Object>> filas = new ArrayList<>();
        for (String p : GeneradorSintetico.ESTRECHO_EJEMPLO) {
            filas.add(Map.of("principioActivo", p, "norma", "INVIMA, listado de estrecho margen (EJEMPLO FICTICIO)", "fechaNorma", "2024-07-01"));
        }
        assertThat(n.put("/api/homologacion/fuentes/INVIMA_ESTRECHO_MARGEN/lista", Map.of("filas", filas)).estado()).isEqualTo(200);
        assertThat(PruebaRiesgo.cargarReferencia(n, "huerfanas", GeneradorSintetico.HUERFANAS_EJEMPLO + ";Ejemplo ficticio\r\n").estado()).isEqualTo(201);
        List<String> par = GeneradorSintetico.INTERACCION_EJEMPLO;
        assertThat(PruebaRiesgo.cargarReferencia(n, "interacciones", par.get(0) + ";" + par.get(1) + ";Ejemplo ficticio\r\n").estado()).isEqualTo(201);
        List<String> ind = GeneradorSintetico.INDICACION_EJEMPLO;
        assertThat(PruebaRiesgo.cargarReferencia(n, "indicaciones", ind.get(0) + ";" + ind.get(1) + ";Ejemplo ficticio\r\n").estado()).isEqualTo(201);
        Navegador.Respuesta v = n.multipart("/api/homologacion/fuentes/INVIMA_VITALES/versiones", Map.of("fechaCorte", "2025-01-01", "ejemplo", "true"),
                Map.of("vitales_ejemplo.csv", ("nombre_comercial_,principio_activo1\r\nPRODUCTO FICTICIO," + GeneradorSintetico.VITAL_EJEMPLO + "\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)), "archivo", "text/csv");
        assertThat(v.estado()).as(v.texto()).isEqualTo(201);
        assertThat(n.post("/api/homologacion/versiones/" + v.json().path("id").asInt() + "/publicar", null).estado()).isEqualTo(200);
        long cargue = cargar(n, csv);
        long cargueMs = jdbc.sql("SELECT duracion_ms FROM ana_cargues WHERE id = :c").param("c", cargue).query(Long.class).single();
        String etapasCargue = jdbc.sql("SELECT etapas_ms::text FROM ana_cargues WHERE id = :c").param("c", cargue).query(String.class).single();
        // Recálculo automático al aplicar
        long limite = System.nanoTime() + 60L * 60 * 1_000_000_000L;
        Map<String, Object> ej;
        while (true) {
            List<Map<String, Object>> l = jdbc.sql("""
                    SELECT e.id, e.estado, e.duracion_ms, e.filas, e.etapas_ms::text AS etapas, a.estado AS estado_alertas, a.nuevas, a.por_tipo::text AS por_tipo
                    FROM ana_cohortes_ejecuciones e JOIN ana_alertas_ejecuciones a ON a.corte_cargue_id = e.corte_cargue_id AND a.origen = e.origen
                    WHERE e.corte_cargue_id = :c AND e.origen = 'cargue' ORDER BY e.id DESC LIMIT 1""").param("c", cargue).query().listOfRows();
            if (!l.isEmpty() && !"en_curso".equals(l.getFirst().get("estado")) && !"en_curso".equals(l.getFirst().get("estado_alertas"))) {
                ej = l.getFirst();
                break;
            }
            assertThat(System.nanoTime()).as("el recálculo no terminó en 60 min").isLessThan(limite);
            Thread.sleep(1000);
        }
        assertThat(ej.get("estado")).as(ej.toString()).isEqualTo("ok");
        long recalculoMs = ((Number) ej.get("duracion_ms")).longValue();
        List<String> salida = new ArrayList<>();
        salida.add(String.format("cargue %d: %,d filas aplicadas en %,d ms %s", cargue, FILAS, cargueMs, etapasCargue));
        salida.add(String.format("recálculo de cohortes y alertas (automático): %,d ms (%.0f %% del cargue) · %,d hechos · etapas %s · %s alertas nuevas",
                recalculoMs, 100.0 * recalculoMs / cargueMs, ((Number) ej.get("filas")).longValue(), ej.get("etapas"), ej.get("nuevas")));
        salida.add("resumen por tipo: " + ej.get("por_tipo"));
        // Las alertas sembradas también se detectan a escala: la ausencia de una es falla (RESP L5647/L5649), como a 20 000 filas
        JsonNode riesgo = new com.fasterxml.jackson.databind.ObjectMapper().readTree(esperadas.toFile()).path("riesgo");
        List<String> faltan = new ArrayList<>();
        for (JsonNode e : riesgo.path("alertas_esperadas")) {
            List<Long> medidas = jdbc.sql("""
                    SELECT CASE :m WHEN 'pacientes' THEN pacientes WHEN 'registros' THEN registros ELSE floor(valor_observado)::bigint END FROM ana_alertas
                    WHERE tipo = :t AND nivel = :n AND dimension = :d AND valor = :v AND to_char(periodo_hasta, 'YYYY-MM') = :p
                    AND coalesce(CASE nivel WHEN 'fuerza' THEN fuerza WHEN 'esm' THEN esm END, '') = :c AND (CAST(:f AS text) IS NULL OR fuerza = :f)""")
                    .param("m", e.path("medida").asText()).param("t", e.path("tipo").asText()).param("n", e.path("nivel").asText())
                    .param("d", e.path("dimension").asText()).param("v", e.path("valor").asText()).param("p", e.path("periodo").asText())
                    .param("c", e.path("codigo_nivel").asText()).param("f", e.has("fuerza") ? e.path("fuerza").asText() : null).query(Long.class).list();
            if (medidas.isEmpty() || medidas.getFirst() < e.path("minimo").asLong()) {
                faltan.add(e.path("tipo").asText() + " " + e.path("codigo_nivel").asText() + " " + e.path("valor").asText() + " " + e.path("periodo").asText()
                        + (medidas.isEmpty() ? "" : " (" + medidas.getFirst() + " < " + e.path("minimo").asLong() + ")"));
            }
        }
        salida.add("alertas sembradas no detectadas a 2 M: " + (faltan.isEmpty() ? "ninguna" : faltan));
        salida.add("alertas de ingresos (alto costo y polimedicados) del primer cargue: " + jdbc.sql(
                "SELECT count(*) FROM ana_alertas WHERE tipo IN ('alto_costo', 'polimedicados')").query(Long.class).single());
        // Consultas de la consola, del tablero 11 y de las cohortes: dos corridas cada una
        long biologicos = jdbc.sql("SELECT id FROM ana_cohortes WHERE codigo = 'biologicos'").query(Long.class).single();
        long estrecho = jdbc.sql("SELECT id FROM ana_cohortes WHERE codigo = 'estrecho_margen'").query(Long.class).single();
        Map<String, String> consultas = new LinkedHashMap<>();
        consultas.put("consola (página 1)", "/api/analitica/alertas");
        consultas.put("consola alta · nacional", "/api/analitica/alertas?severidad=alta&nivel=nacional");
        consultas.put("consola tipo + periodo", "/api/analitica/alertas?tipo=incremento_consumo&desde=2025-10-01&hasta=2025-10-31");
        consultas.put("atípicos (tablero 11)", "/api/analitica/atipicos?desde=2025-01-01&hasta=2025-12-31");
        consultas.put("tarjetas de cohortes", "/api/analitica/cohortes?corte=" + cargue + "&desde=2025-01-01&hasta=2025-12-31");
        consultas.put("detalle y serie (estrecho margen)", "/api/analitica/cohortes/" + estrecho + "?corte=" + cargue + "&desde=2025-01-01&hasta=2025-12-31");
        consultas.put("composición ATC3 (biológicos)", "/api/analitica/cohortes/" + biologicos + "/composicion?por=atc3&corte=" + cargue
                + "&desde=2025-01-01&hasta=2025-12-31");
        consultas.put("composición principio (estrecho margen)", "/api/analitica/cohortes/" + estrecho + "/composicion?por=principio&corte=" + cargue
                + "&desde=2025-01-01&hasta=2025-12-31");
        consultas.put("descarga CSV de la consola", "/api/analitica/alertas/descarga?formato=csv");
        boolean dentro = true;
        for (Map.Entry<String, String> c : consultas.entrySet()) {
            for (int corrida = 1; corrida <= 2; corrida++) {
                long t0 = System.nanoTime();
                Navegador.Respuesta r = n.get(c.getValue());
                long ms = (System.nanoTime() - t0) / 1_000_000;
                assertThat(r.estado()).as(c.getKey() + ": " + r.texto().substring(0, Math.min(300, r.texto().length()))).isEqualTo(200);
                salida.add(String.format("%-42s corrida %d: %,8d ms", c.getKey(), corrida, ms));
                dentro &= ms <= LIMITE_VER_MS;
            }
        }
        Map<String, String> cohortes = new LinkedHashMap<>();
        Map<String, String> noCuadran = new java.util.TreeMap<>();
        JsonNode tarjetas = n.get(consultas.get("tarjetas de cohortes")).json().path("cohortes");
        tarjetas.forEach(c -> cohortes.put(c.path("codigo").asText(),
                c.path("estado").asText().equals("evaluada") ? c.path("pacientesTexto").asText(c.path("grupos").size() + " grupos") : c.path("estado").asText()));
        salida.add("cohortes del sistema a 2 M: " + cohortes);
        // Cada cohorte esperada por el generador cuadra con la del sistema (o ambas «no evaluada» si falta su lista)
        riesgo.path("cohortes_esperadas").fields().forEachRemaining(e -> {
            String sistema = cohortes.get(e.getKey());
            String esperado = e.getValue().isTextual() ? e.getValue().asText() : String.format("%,d", e.getValue().asLong()).replace(",", "");
            String deSistema = sistema == null ? "(sin tarjeta)" : sistema.replace(".", "");
            if (e.getValue().isTextual() ? !"no_evaluada".equals(deSistema) : !esperado.equals(deSistema)) {
                noCuadran.put(e.getKey(), "esperado " + esperado + ", sistema " + sistema);
            }
        });
        // Reejecución bajo demanda (mismo corte: no duplica)
        long t0 = System.nanoTime();
        JsonNode p = n.post("/api/analitica/alertas/ejecutar", null).json();
        long id = p.path("ejecucion").asLong();
        while (!"ok".equals(jdbc.sql("SELECT estado FROM ana_alertas_ejecuciones WHERE id = :i").param("i", id).query(String.class).single())) {
            assertThat(jdbc.sql("SELECT estado FROM ana_alertas_ejecuciones WHERE id = :i").param("i", id).query(String.class).single()).isIn("en_curso", "ok");
            Thread.sleep(500);
        }
        salida.add(String.format("reejecución bajo demanda: %,d ms (nuevas: %d)", (System.nanoTime() - t0) / 1_000_000,
                jdbc.sql("SELECT nuevas FROM ana_alertas_ejecuciones WHERE id = :i").param("i", id).query(Integer.class).single()));
        salida.forEach(s -> System.out.println("[rendimiento-riesgo] " + s));
        assertThat(recalculoMs).as("el recálculo no duplica el tiempo del cargue").isLessThanOrEqualTo(cargueMs);
        assertThat(dentro).as("consultas ≤ 3 min (A5 2.7)").isTrue();
        assertThat(faltan).as("alertas sembradas no detectadas a 2 M").isEmpty();
        assertThat(noCuadran).as("cohortes que no cuadran con el generador a 2 M").isEmpty();
        assertThat(ej.get("estado_alertas")).as("estado del motor a 2 M: " + ej.get("por_tipo")).isEqualTo("ok");
        assertThat(String.valueOf(ej.get("por_tipo"))).as("ningún tipo en error a 2 M").doesNotContain("\"error\"");
    }

    /** Sube el archivo en partes de 50 MB (en flujo) y espera el fin del cargue. */
    long cargar(Navegador n, Path csv) throws Exception {
        long tamano = Files.size(csv);
        int partes = (int) ((tamano + PARTE - 1) / PARTE);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new DigestInputStream(Files.newInputStream(csv), md)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("nombre", csv.getFileName().toString());
        s.put("tamano", tamano);
        s.put("sha256", HexFormat.of().formatHex(md.digest()));
        s.put("partes", partes);
        Navegador.Respuesta c = n.post("/api/analitica/cargues", s);
        assertThat(c.estado()).as(c.texto()).isIn(200, 201);
        long id = c.json().path("id").asLong();
        try (InputStream in = Files.newInputStream(csv)) {
            for (int p = 1; p <= partes; p++) {
                assertThat(n.putBinario("/api/analitica/cargues/" + id + "/partes/" + p, in.readNBytes(PARTE)).estado()).isEqualTo(200);
            }
        }
        assertThat(n.post("/api/analitica/cargues/" + id + "/cerrar", null).estado()).isEqualTo(200);
        List<String> columnas = new ArrayList<>();
        n.get("/api/analitica/cargues/" + id + "/mapeo").json().path("propuesta").forEach(x -> columnas.add(x.asText()));
        Map<String, Object> conf = new LinkedHashMap<>();
        conf.put("columnas", columnas);
        conf.put("modo", "agregar");
        conf.put("guardarPlantilla", false);
        assertThat(n.post("/api/analitica/cargues/" + id + "/mapeo", conf).estado()).isEqualTo(202);
        long limite = System.nanoTime() + 45L * 60 * 1_000_000_000L;
        while (System.nanoTime() < limite) {
            JsonNode e = n.get("/api/analitica/cargues/" + id).json();
            String estado = e.path("estado").asText();
            if (estado.equals("aplicado")) {
                return id;
            }
            assertThat(List.of("rechazado", "fallido", "duplicado")).as(e.toString()).doesNotContain(estado);
            Thread.sleep(1000);
        }
        throw new AssertionError("El cargue no terminó en 45 min");
    }
}
