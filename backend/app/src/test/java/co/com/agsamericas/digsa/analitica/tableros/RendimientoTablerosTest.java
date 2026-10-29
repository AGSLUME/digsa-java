package co.com.agsamericas.digsa.analitica.tableros;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.analitica.GeneradorSintetico;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta;
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
 * Tiempo de cada tablero y de cada formato de descarga sobre las 2 M filas del generador sintético (analitica-tableros, «Tiempos de
 * respuesta»; A5 2.7: ver en ≤ 3 min y exportar en ≤ 10 min; meta interna: segundos). Bajo demanda, con la misma activación que
 * RendimientoIngestaTest; reutiliza {@code .local/analitica/sintetico_2000000.csv} (lo genera una sola vez si no existe):
 * <pre>
 *   ./mvnw -q test -Dtest=RendimientoTablerosTest -Ddigsa.rendimiento=true -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * Imprime la duración de cada tablero sin filtros y con filtros (Fuerza, ATC y CIE-10), dos corridas, y la de cada descarga. Falla solo si
 * se pasa de los límites del anexo (3 min por tablero, 10 min por descarga); la meta interna queda en la salida.
 */
@Tag("rendimiento")
@EnabledIfSystemProperty(named = "digsa.rendimiento", matches = "true")
@TestPropertySource(properties = {"digsa.analitica.parte-max-mb=50", "digsa.analitica.tableros.max-registros-traza=500000"})
class RendimientoTablerosTest extends PruebaIntegracion {

    @org.springframework.beans.factory.annotation.Autowired
    ServicioConsultaAnalitica servicio;

    static final int FILAS = Integer.getInteger("digsa.rendimiento.filas", 2_000_000);
    static final int PARTE = 50 * 1024 * 1024;
    static final long LIMITE_VER_MS = 180_000;
    static final long LIMITE_EXPORTAR_MS = 600_000;
    static final long META_MS = 5_000;

    @Test
    @DisplayName("2 M filas: cada tablero con y sin filtros en menos de 3 min (meta 5 s) y cada formato de descarga en menos de 10 min")
    void tablerosSobreDosMillones() throws Exception {
        Path carpeta = Path.of(System.getProperty("digsa.rendimiento.carpeta", "../../.local/analitica"));
        Path csv = carpeta.resolve("sintetico_" + FILAS + ".csv");
        if (!Files.exists(csv)) {
            GeneradorSintetico.generar(carpeta, "sintetico_" + FILAS, FILAS, GeneradorSintetico.SEMILLA);
        }
        Navegador n = navegador();
        assertThat(n.ingresar(crearUsuario("analistarendtab", Perfil.ANALISTA), CLAVE).estado()).isEqualTo(200);
        long cargue = cargar(n, csv);
        System.out.printf("%n[rendimiento-tableros] cargue %d aplicado; %d filas%n", cargue, FILAS);

        List<String> filas = new ArrayList<>();
        boolean dentro = true;
        // Filtros válidos sin referencias de homologación cargadas: Fuerza + ESM + CUM (pocas filas, uniones a la tabla maestra y la
        // CIE-10 por «Sin homologar»), y Fuerza con granularidad semanal y comparación con el año anterior (el doble de periodo)
        List<String> conjuntos = List.of("", "&fuerza=EJC&esm=BITER10&cum=20010259-1&atc=" + enc(AnaliticaConsulta.SIN_HOMOLOGAR) + "&cie10="
                + enc(AnaliticaConsulta.SIN_HOMOLOGAR), "&fuerza=ARC&granularidad=semana&comparacion=anio-anterior");
        for (String filtros : conjuntos) {
            for (AnaliticaConsulta.Tablero t : AnaliticaConsulta.Tablero.values()) {
                if (!t.disponible()) {
                    continue;
                }
                // Corrida 1: en frío (calculado); corrida 2: el mismo corte y consulta (caché de resultados, 14.8)
                for (int corrida = 1; corrida <= 2; corrida++) {
                    long t0 = System.nanoTime();
                    Navegador.Respuesta r = n.get("/api/analitica/tableros/" + t.codigo() + "?corte=" + cargue + filtros);
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    assertThat(r.estado()).as(t.codigo() + filtros + ": " + r.texto()).isEqualTo(200);
                    filas.add(String.format("%-16s %-22s %s: %,8d ms %s", t.codigo(), nombre(filtros), corrida == 1 ? "en frío" : "en caché", ms,
                            ms <= META_MS ? "meta OK" : "sobre la meta"));
                    dentro &= ms <= LIMITE_VER_MS;
                }
            }
        }
        // Descargas en frío (sin la caché que dejó la pantalla) y después de ver el tablero
        for (String formato : List.of("xlsx", "csv", "pdf")) {
            servicio.vaciarCache();
            long t0 = System.nanoTime();
            Navegador.Respuesta r = n.get("/api/analitica/tableros/medicamento/descarga?formato=" + formato + "&corte=" + cargue);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(r.estado()).as(formato).isEqualTo(200);
            long t1 = System.nanoTime();
            assertThat(n.get("/api/analitica/tableros/medicamento/descarga?formato=" + formato + "&corte=" + cargue).estado()).isEqualTo(200);
            long ms2 = (System.nanoTime() - t1) / 1_000_000;
            filas.add(String.format("descarga %-6s medicamento sin filtros: en frío %,8d ms, después de verlo %,6d ms (%d KB)", formato, ms, ms2,
                    r.cuerpo().length / 1024));
            dentro &= ms <= LIMITE_EXPORTAR_MS;
        }
        // CSV de registros de origen de una cifra grande (Fuerza EJC, un mes), en flujo
        long t0 = System.nanoTime();
        Navegador.Respuesta csvTraza = n.get("/api/analitica/traza/general/registros.csv?corte=" + cargue + "&fuerza=EJC&desde=2025-09-01&hasta=2025-09-30");
        long msCsv = (System.nanoTime() - t0) / 1_000_000;
        assertThat(csvTraza.estado()).as(csvTraza.texto().substring(0, Math.min(300, csvTraza.texto().length()))).isEqualTo(200);
        List<String> lineas = csvTraza.texto().lines().toList();
        assertThat(lineas.getLast()).startsWith("FIN;");
        filas.add(String.format("CSV de registros de origen (EJC, septiembre): %,8d ms, %s (%d KB)", msCsv, lineas.getLast(), csvTraza.cuerpo().length / 1024));
        dentro &= msCsv <= LIMITE_EXPORTAR_MS;
        // La duración queda en la bitácora de analítica
        assertThat(jdbc.sql("SELECT count(*) FROM ana_consultas WHERE accion = 'consulta' AND duracion_ms > 0 AND corte_cargue_id = :c").param("c", cargue)
                .query(Long.class).single()).isGreaterThan(0);
        filas.forEach(f -> System.out.println("[rendimiento-tableros] " + f));
        assertThat(dentro).as("ver ≤ 3 min y exportar ≤ 10 min (A5 2.7)").isTrue();
    }

    private static String nombre(String filtros) {
        return filtros.isEmpty() ? "sin filtros" : filtros.contains("cum=") ? "Fuerza+ESM+CUM+SH" : "ARC semanal + año ant.";
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
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
        if (c.estado() == 200) {
            return id; // ya aplicado
        }
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
        long limite = System.nanoTime() + 30L * 60 * 1_000_000_000L;
        while (System.nanoTime() < limite) {
            JsonNode e = n.get("/api/analitica/cargues/" + id).json();
            String estado = e.path("estado").asText();
            if (estado.equals("aplicado")) {
                // Etapas de la ingesta; «agregados» es el paso de los tableros (tablas de apoyo, PasoTablas)
                System.out.printf("%n[rendimiento-tableros] etapas del cargue (ms): %s%n", e.path("etapasMs"));
                return id;
            }
            assertThat(List.of("rechazado", "fallido", "duplicado")).as(e.toString()).doesNotContain(estado);
            Thread.sleep(1000);
        }
        throw new AssertionError("El cargue no terminó en 30 min");
    }
}
