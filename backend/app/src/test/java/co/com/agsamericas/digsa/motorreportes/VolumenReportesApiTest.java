package co.com.agsamericas.digsa.motorreportes;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.TestPropertySource;

/**
 * Prueba de volumen del módulo de reportes (plan R5): con 1,5 millones de visitas sintéticas en un trimestre (el tamaño del insumo de la
 * prueba en sitio; RESPUESTAS l.2213: consultas de hasta tres meses), ver en pantalla debe tardar 3 minutos o menos y exportar en cada
 * uno de los cuatro formatos 10 minutos o menos (Anexo 5, 2.7). Fuera del ciclo rápido: corre solo con {@code -Ddigsa.volumen=true}
 * (filas con {@code -Ddigsa.volumen.filas=N}). Deja los tiempos en {@code target/volumen-reportes.txt}.
 */
@EnabledIfSystemProperty(named = "digsa.volumen", matches = "true")
@TestPropertySource(properties = {"digsa.reportes.espera-sincrona-ms=0", "digsa.reportes.limpieza-inicial-ms=36000000"})
class VolumenReportesApiTest extends PruebaIntegracion {

    private static final String PERIODO = "desde=2025-01-01&hasta=2025-03-31";
    private final List<String> resultados = new ArrayList<>();

    private void anotar(String linea) {
        System.out.println("[volumen] " + linea);
        resultados.add(linea);
    }

    private long sembrar(int filas) {
        long auditor = idDe(crearUsuario("volaud", Perfil.AUDITOR));
        long inicio = System.nanoTime();
        // Puntos reales de la carga inicial, repartidos en el trimestre; estados variados (sin actas: los resultados son de las cerradas)
        jdbc.sql("""
                INSERT INTO visitas (numero, tipo, punto_id, fecha, auditor_id, alcance, estado, creado_por)
                WITH p AS (SELECT array_agg(id ORDER BY id) AS ids FROM puntos WHERE activo)
                SELECT 'VS-' || to_char(DATE '2025-01-01' + (g % 90), 'YYYYMMDD') || '-' || (10000000 + g), 'anual', p.ids[1 + g % cardinality(p.ids)],
                       DATE '2025-01-01' + (g % 90), :a, 'Volumen sintético.', (ARRAY['programada', 'notificada', 'reprogramada', 'cancelada'])[1 + g % 4], :a
                FROM generate_series(1, :n) g CROSS JOIN p""")
                .param("a", auditor).param("n", filas).update();
        jdbc.sql("ANALYZE visitas").update();
        anotar("Siembra de " + filas + " visitas: " + Duration.ofNanos(System.nanoTime() - inicio).toSeconds() + " s");
        return auditor;
    }

    /** Espera a que la generación termine (lista o fallida), consultando cada segundo hasta el tope técnico. */
    private Map<String, Object> esperarArchivo(String codigo) {
        long limite = System.nanoTime() + Duration.ofMinutes(65).toNanos();
        while (List.of("pendiente", "generando").contains(
                jdbc.sql("SELECT estado FROM reportes_generados WHERE codigo = :c").param("c", codigo).query(String.class).single())) {
            assertThat(System.nanoTime()).as("el archivo " + codigo + " no terminó en el tope técnico").isLessThan(limite);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        return jdbc.sql("SELECT estado, filas, tamano, ms_consulta, ms_archivo, ms_total, error FROM reportes_generados WHERE codigo = :c")
                .param("c", codigo).query().singleRow();
    }

    @Test
    @Timeout(value = 3, unit = java.util.concurrent.TimeUnit.HOURS)
    @DisplayName("volumen: 1,5 M visitas en un trimestre; ver ≤ 3 min y exportar ≤ 10 min en xlsx, csv, pdf y txt")
    void volumen() throws IOException {
        int filas = Integer.getInteger("digsa.volumen.filas", 1_500_000);
        sembrar(filas);
        Navegador c = navegador();
        c.ingresar(crearUsuario("volcoord", Perfil.COORDINADOR), CLAVE);
        try {
            for (String reporte : List.of("lugar", "detalle", "proceso")) {
                long t0 = System.nanoTime();
                Respuesta r = c.get("/api/reportes/" + reporte + "?" + PERIODO);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                anotar("Ver «" + reporte + "» en pantalla: " + ms + " ms (estado " + r.estado() + ", " + r.json().path("registros").asText() + " filas)");
                assertThat(r.estado()).as(r.texto()).isEqualTo(200);
                assertThat(ms).as("2.7: ver en 3 minutos").isLessThanOrEqualTo(180_000);
            }
            // El correo corta en cuanto el adjunto pasa de 10 MB, sin terminar el archivo
            long t0 = System.nanoTime();
            Respuesta correo = c.post("/api/reportes/detalle/enviar", Map.of("para", "comite@example.com", "formato", "csv", "desde", "2025-01-01", "hasta",
                    "2025-03-31"));
            anotar("Correo del detalle en CSV: " + correo.estado() + " en " + (System.nanoTime() - t0) / 1_000_000 + " ms · " + correo.mensaje());
            if (filas >= 200_000) { // con menos filas (prueba corta del propio test) el CSV cabe en el correo
                assertThat(correo.estado()).isEqualTo(422);
                assertThat(correo.mensaje()).contains("MB");
            }

            for (String formato : List.of("csv", "txt", "xlsx", "pdf")) {
                Respuesta r = c.get("/api/reportes/detalle/exportar?formato=" + formato + "&" + PERIODO);
                assertThat(r.estado()).as(r.texto()).isEqualTo(202);
                Map<String, Object> a = esperarArchivo(r.json().path("codigo").asText());
                anotar("Exportar el detalle en " + formato + ": " + a.get("ms_total") + " ms (consulta " + a.get("ms_consulta") + ", archivo " + a.get("ms_archivo")
                        + "), " + a.get("filas") + " filas, " + a.get("tamano") + " bytes, estado " + a.get("estado") + (a.get("error") == null ? "" : " · " + a.get("error")));
                assertThat(a.get("estado")).isEqualTo("lista");
                assertThat(((Number) a.get("filas")).longValue()).isGreaterThanOrEqualTo(filas);
                assertThat(((Number) a.get("ms_total")).longValue()).as("2.7: exportar en 10 minutos (" + formato + ")").isLessThanOrEqualTo(600_000);
            }
        } finally {
            try {
                Files.createDirectories(Path.of("target"));
                Files.writeString(Path.of("target", "volumen-reportes.txt"), String.join(System.lineSeparator(), resultados) + System.lineSeparator(),
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.out.println("[volumen] No se pudieron guardar los resultados: " + e.getMessage()); // no tapa el error de la prueba
            }
        }
    }
}
