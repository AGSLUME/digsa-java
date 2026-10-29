package co.com.agsamericas.digsa.analitica.ingesta;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.analitica.GeneradorSintetico;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.TestPropertySource;

/**
 * Ingesta de 2 M filas cronometrada (analitica-ingesta, «Tiempos medidos»; caso M8-3; meta interna < 5 min). Bajo demanda: no corre en
 * la suite normal. Reutiliza {@code .local/analitica/sintetico_2000000.csv} (lo genera una sola vez si no existe):
 * <pre>
 *   ./mvnw -q test -Dtest=RendimientoIngestaTest -Ddigsa.rendimiento=true -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * Imprime la duración por etapa (subida, lectura y COPY, validación, hechos, agregados) y la total.
 */
@Tag("rendimiento")
@EnabledIfSystemProperty(named = "digsa.rendimiento", matches = "true")
@TestPropertySource(properties = "digsa.analitica.parte-max-mb=50") // partes de 50 MB como en producción (las demás pruebas usan 1 MB)
class RendimientoIngestaTest extends PruebaIntegracion {

    static final int FILAS = Integer.getInteger("digsa.rendimiento.filas", 2_000_000);
    static final int PARTE = 50 * 1024 * 1024;
    static final long META_MS = 300_000;

    @Test
    @DisplayName("2 M filas de un año con errores sembrados: aplicado, cuadra con el generador y se registran los tiempos por etapa")
    void dosMillones() throws Exception {
        Path carpeta = Path.of(System.getProperty("digsa.rendimiento.carpeta", "../../.local/analitica"));
        Path csv = carpeta.resolve("sintetico_" + FILAS + ".csv");
        Path totales = carpeta.resolve("totales_esperados_" + FILAS + ".json");
        if (!Files.exists(csv) || !Files.exists(totales)) {
            GeneradorSintetico.generar(carpeta, "sintetico_" + FILAS, FILAS, GeneradorSintetico.SEMILLA);
        }
        String json = Files.readString(totales);
        long recibidos = numero(json, "recibidos");
        long procesados = numero(json, "procesados");
        long rechazados = numero(json, "rechazados");

        Navegador n = navegador();
        assertThat(n.ingresar(crearUsuario("analistarend", Perfil.ANALISTA), CLAVE).estado()).isEqualTo(200);
        long tamano = Files.size(csv);
        int partes = (int) ((tamano + PARTE - 1) / PARTE);
        long t0 = System.nanoTime();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("nombre", "sintetico_" + FILAS + ".csv");
        s.put("tamano", tamano);
        s.put("sha256", sha256(csv));
        s.put("partes", partes);
        long tSha = System.nanoTime();
        Navegador.Respuesta c = n.post("/api/analitica/cargues", s);
        assertThat(c.estado()).as(c.texto()).isIn(200, 201);
        long id = c.json().path("id").asLong();
        if (c.estado() == 201) {
            try (InputStream in = Files.newInputStream(csv)) {
                for (int p = 1; p <= partes; p++) {
                    byte[] parte = in.readNBytes(PARTE);
                    Navegador.Respuesta r = n.putBinario("/api/analitica/cargues/" + id + "/partes/" + p, parte);
                    assertThat(r.estado()).as(r.texto()).isEqualTo(200);
                }
            }
            Navegador.Respuesta cerrado = n.post("/api/analitica/cargues/" + id + "/cerrar", null);
            assertThat(cerrado.estado()).as(cerrado.texto()).isEqualTo(200);
            long tSubida = System.nanoTime();
            assertThat(IngestaApiTest.confirmar(n, id, IngestaApiTest.propuesta(n, id), "agregar", false).estado()).isEqualTo(202);
            JsonNode fin = esperar(n, id);
            long tFin = System.nanoTime();
            System.out.printf("%n[rendimiento] %d filas (%d MB, %d partes): SHA-256 en el cliente %d ms, subida y cierre %d ms, procesamiento %d ms, "
                            + "total %d ms%n[rendimiento] etapas del servidor (ms): %s · duración del procesamiento %s ms%n[rendimiento] %s%n",
                    FILAS, tamano / (1024 * 1024), partes, (tSha - t0) / 1_000_000, (tSubida - tSha) / 1_000_000, (tFin - tSubida) / 1_000_000,
                    (tFin - t0) / 1_000_000, fin.path("etapasMs"), fin.path("duracionMs"), fin.path("motivo").asText());
            // Meta interna (spec «Tiempos medidos»): 2 M filas aplicadas en menos de 300 s. Se compara y se deja en la salida sin fallar:
            // la medición depende del equipo y de la carga de las demás sesiones (se anota en docs/rendimiento_analitica.md)
            long duracion = fin.path("duracionMs").asLong();
            boolean cincoEtapas = List.of("subida", "lectura", "validacion", "homologacion", "hechos", "agregados").stream().allMatch(fin.path("etapasMs")::has);
            System.out.printf("[rendimiento] meta < %d s: %s (procesamiento %,.1f s; cinco etapas medidas: %s)%n", META_MS / 1000,
                    duracion < META_MS ? "CUMPLE" : "NO CUMPLE", duracion / 1000.0, cincoEtapas ? "sí" : "no");
            assertThat(cincoEtapas).as("la duración de las cinco etapas queda en el cargue").isTrue();
            assertThat(fin.path("estado").asText()).as(fin.toString()).isEqualTo("aplicado");
            assertThat(fin.path("recibidos").asLong()).isEqualTo(recibidos);
            assertThat(fin.path("procesados").asLong()).isEqualTo(procesados);
            assertThat(fin.path("rechazados").asLong()).isEqualTo(rechazados);
            BigDecimal gasto = jdbc.sql("SELECT sum(valor_total) FROM ana_dispensaciones WHERE cargue_id = :c").param("c", id).query(BigDecimal.class).single();
            assertThat(gasto.toPlainString()).isEqualTo(texto(json, "gasto"));
        }
    }

    private static JsonNode esperar(Navegador n, long id) throws InterruptedException {
        long limite = System.nanoTime() + 30L * 60 * 1_000_000_000L;
        while (System.nanoTime() < limite) {
            JsonNode e = n.get("/api/analitica/cargues/" + id).json();
            if (java.util.List.of("aplicado", "rechazado", "fallido").contains(e.path("estado").asText())) {
                return e;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("El cargue no terminó en 30 min");
    }

    private static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new DigestInputStream(Files.newInputStream(p), md)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** Primer valor numérico de la clave en el JSON de totales (la sección «filas» va antes que «total»). */
    private static long numero(String json, String clave) {
        Matcher m = Pattern.compile("\"" + clave + "\": (\\d+)").matcher(json);
        assertThat(m.find()).as(clave).isTrue();
        return Long.parseLong(m.group(1));
    }

    /** Gasto total: el de la sección «total» (la última del JSON). */
    private static String texto(String json, String clave) {
        int i = json.lastIndexOf("\"total\": {");
        Matcher m = Pattern.compile("\"" + clave + "\": \"([^\"]+)\"").matcher(json.substring(i));
        assertThat(m.find()).as(clave).isTrue();
        return m.group(1);
    }
}
