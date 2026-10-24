package co.com.agsamericas.digsa.analitica.riesgo;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.analitica.GeneradorSintetico;
import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.test.context.TestPropertySource;

/**
 * Base de las pruebas de cohortes y alertas (A4): contexto propio (base embebida propia) con el recálculo al aplicar cada cargue encendido.
 * El insumo base es el del generador sintético de 20 000 filas CON los patrones clínicos sembrados (año 2025), cargado después de publicar
 * la lista de estrecho margen de ejemplo y la referencia ficticia de enfermedades huérfanas; las interacciones NO se cargan (el primer
 * recálculo debe informar «no evaluado»). Las pruebas esperan a que termine el recálculo del corte.
 */
@TestPropertySource(properties = {"digsa.analitica.riesgo.recalcular-al-aplicar=true"})
abstract class PruebaRiesgo extends PruebaIntegracion {

    static final int FILAS = 20_000;
    static final int PARTE = 1024 * 1024;
    static final String PERIODO = "desde=2025-01-01&hasta=2025-12-31";
    static final List<String> TERMINALES = List.of("aplicado", "rechazado", "fallido", "duplicado", "vencido", "descartado");
    /** Seudónimo HMAC (64 hexadecimales): nunca debe salir en una respuesta de cohortes o alertas. */
    static final Pattern SEUDONIMO = Pattern.compile("[0-9a-f]{64}");
    static final ObjectMapper JSON = new ObjectMapper();

    /** Insumo base: cargue aplicado, resultado del generador, la sección «riesgo» de los totales y la ejecución del motor de ese cargue. */
    record Base(long cargue, GeneradorSintetico.Resultado esperado, JsonNode riesgo, String analista, long ejecucionAlertas) {
    }

    /** Un insumo base por contexto (cada contexto de prueba tiene su propia base embebida: supresión, control sin patrones…). */
    private static final Map<Object, Base> BASES = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    /** Con los patrones sembrados (las pruebas del conjunto de control lo apagan). */
    boolean conPatrones() {
        return true;
    }

    @org.springframework.beans.factory.annotation.Autowired
    RecalculoRiesgo riesgo;

    Navegador como(String usuario) {
        Navegador n = navegador();
        assertThat(n.ingresar(usuario, CLAVE).estado()).isEqualTo(200);
        return n;
    }

    Navegador nuevo(String prefijo, Perfil... perfiles) {
        return como(crearUsuario(prefijo, perfiles));
    }

    static JsonNode ok(Navegador.Respuesta r) {
        assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        return r.json();
    }

    Base base() throws IOException {
        Base b = BASES.get(jdbc);
        if (b != null) {
            return b;
        }
        synchronized (PruebaRiesgo.class) {
            if (BASES.get(jdbc) == null) {
                String analista = crearUsuario("anariesgo", Perfil.ANALISTA);
                Navegador n = como(analista);
                // Lista de estrecho margen de ejemplo (ficticia) publicada antes del cargue
                List<Map<String, Object>> filas = new ArrayList<>();
                for (String p : GeneradorSintetico.ESTRECHO_EJEMPLO) {
                    filas.add(Map.of("principioActivo", p, "norma", "INVIMA, listado de estrecho margen (EJEMPLO FICTICIO)", "fechaNorma", "2024-07-01"));
                }
                Navegador.Respuesta l = n.put("/api/homologacion/fuentes/INVIMA_ESTRECHO_MARGEN/lista", Map.of("filas", filas));
                assertThat(l.estado()).as(l.texto()).isEqualTo(200);
                // Referencia ficticia de enfermedades huérfanas (E75)
                Navegador.Respuesta h = cargarReferencia(n, "huerfanas", GeneradorSintetico.HUERFANAS_EJEMPLO + ";Ejemplo ficticio\r\n");
                assertThat(h.estado()).as(h.texto()).isEqualTo(201);
                Path dir = Files.createTempDirectory("digsa-sintetico-riesgo-");
                GeneradorSintetico.Resultado r = GeneradorSintetico.generar(dir, "sintetico_riesgo", FILAS, 2024L, conPatrones());
                JsonNode fin = cargarInsumo(n, "insumo_riesgo_2025.csv", Files.readAllBytes(r.csv()));
                assertThat(fin.path("estado").asText()).as(fin.toString()).isEqualTo("aplicado");
                assertThat(fin.path("procesados").asLong()).isEqualTo(r.procesados());
                long cargue = fin.path("id").asLong();
                long ejecucion = esperarRecalculo(cargue);
                JsonNode riesgo = JSON.readTree(Files.readString(r.totales(), StandardCharsets.UTF_8)).path("riesgo");
                n.post("/api/auth/salir", null);
                BASES.put(jdbc, new Base(cargue, r, riesgo, analista, ejecucion));
            }
            return BASES.get(jdbc);
        }
    }

    /** Carga la indicación ficticia (colchicina ⇒ gota) y publica el vital ficticio de ejemplo (para fuera de indicación y desabastecimiento). */
    void cargarIndicacionesYVitales(Navegador n) {
        List<String> ind = GeneradorSintetico.INDICACION_EJEMPLO;
        Navegador.Respuesta c = cargarReferencia(n, "indicaciones", "atc;cie10;nota\r\n" + ind.get(0) + ";" + ind.get(1) + ";Ejemplo ficticio\r\n");
        assertThat(c.estado()).as(c.texto()).isEqualTo(201);
        Navegador.Respuesta v = n.multipart("/api/homologacion/fuentes/INVIMA_VITALES/versiones", Map.of("fechaCorte", "2025-01-01", "ejemplo", "true"),
                Map.of("vitales_ejemplo.csv", ("nombre_comercial_,principio_activo1\r\nPRODUCTO FICTICIO," + GeneradorSintetico.VITAL_EJEMPLO + "\r\n")
                        .getBytes(StandardCharsets.UTF_8)), "archivo", "text/csv");
        assertThat(v.estado()).as(v.texto()).isEqualTo(201);
        assertThat(v.json().path("estado").asText()).isEqualTo("validada");
        Navegador.Respuesta p = n.post("/api/homologacion/versiones/" + v.json().path("id").asInt() + "/publicar", null);
        assertThat(p.estado()).as(p.texto()).isEqualTo(200);
    }

    /** Corre el motor bajo demanda sobre el corte vigente y espera a que termine; devuelve la ejecución de alertas. */
    long ejecutar(Navegador n) {
        esperarLibre();
        Navegador.Respuesta r = n.post("/api/analitica/alertas/ejecutar", null);
        assertThat(r.estado()).as(r.texto()).isEqualTo(202);
        esperarLibre();
        return r.json().path("ejecucion").asLong();
    }

    static Navegador.Respuesta cargarReferencia(Navegador n, String lista, String contenido) {
        return n.multipart("/api/analitica/alertas/referencias/" + lista, Map.of("fuente", "Referencia de ejemplo (EJEMPLO FICTICIO) 2026", "ficticio", "true"),
                Map.of(lista + ".csv", contenido.getBytes(StandardCharsets.UTF_8)), "archivo", "text/csv");
    }

    /**
     * Espera a que termine el recálculo (cohortes y alertas) del corte y devuelve la ejecución del motor de alertas de ese corte. Falla si
     * terminó con error.
     */
    long esperarRecalculo(long corte) {
        long limite = System.nanoTime() + 300_000_000_000L;
        while (true) {
            List<Map<String, Object>> e = jdbc.sql("""
                    SELECT id, estado, error FROM ana_alertas_ejecuciones WHERE corte_cargue_id = :c ORDER BY id DESC LIMIT 1""").param("c", corte)
                    .query().listOfRows();
            boolean libre = jdbc.sql("SELECT count(*) FROM ana_cohortes_ejecuciones WHERE fin IS NULL").query(Long.class).single() == 0;
            if (!e.isEmpty() && !"en_curso".equals(e.getFirst().get("estado")) && libre) {
                assertThat(e.getFirst().get("estado")).as("ejecución del motor: " + e.getFirst()).isNotEqualTo("error");
                return ((Number) e.getFirst().get("id")).longValue();
            }
            if (System.nanoTime() > limite) {
                throw new AssertionError("El recálculo del corte " + corte + " no terminó: " + e);
            }
            dormir(200);
        }
    }

    /** Espera a que no quede ningún recálculo en curso (después de «Ejecutar», «Recalcular» o una versión nueva). */
    void esperarLibre() {
        long limite = System.nanoTime() + 300_000_000_000L;
        while (jdbc.sql("SELECT (SELECT count(*) FROM ana_cohortes_ejecuciones WHERE fin IS NULL) + (SELECT count(*) FROM ana_alertas_ejecuciones WHERE fin IS NULL)")
                .query(Long.class).single() > 0) {
            if (System.nanoTime() > limite) {
                throw new AssertionError("Hay recálculos que no terminan");
            }
            dormir(200);
        }
    }

    static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static int partes(byte[] d) {
        return (d.length + PARTE - 1) / PARTE;
    }

    /** Sube el insumo por partes, confirma la propuesta de mapeo y espera el fin (como la pantalla «Cargues del OPL»). */
    static JsonNode cargarInsumo(Navegador n, String nombre, byte[] datos) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("nombre", nombre);
        s.put("tamano", datos.length);
        s.put("sha256", Huellas.sha256(datos));
        s.put("partes", partes(datos));
        Navegador.Respuesta c = n.post("/api/analitica/cargues", s);
        assertThat(c.estado()).as(c.texto()).isEqualTo(201);
        long id = c.json().path("id").asLong();
        for (int p = 0; p < partes(datos); p++) {
            byte[] parte = Arrays.copyOfRange(datos, p * PARTE, Math.min(datos.length, (p + 1) * PARTE));
            assertThat(n.putBinario("/api/analitica/cargues/" + id + "/partes/" + (p + 1), parte).estado()).isEqualTo(200);
        }
        assertThat(n.post("/api/analitica/cargues/" + id + "/cerrar", null).estado()).isEqualTo(200);
        List<String> columnas = new ArrayList<>();
        n.get("/api/analitica/cargues/" + id + "/mapeo").json().path("propuesta").forEach(x -> columnas.add(x.asText()));
        Map<String, Object> conf = new LinkedHashMap<>();
        conf.put("columnas", columnas);
        conf.put("modo", "agregar");
        conf.put("guardarPlantilla", false);
        Navegador.Respuesta r = n.post("/api/analitica/cargues/" + id + "/mapeo", conf);
        assertThat(r.estado()).as(r.texto()).isEqualTo(202);
        long limite = System.nanoTime() + 180_000_000_000L;
        while (true) {
            JsonNode e = n.get("/api/analitica/cargues/" + id).json();
            if (TERMINALES.contains(e.path("estado").asText())) {
                return e;
            }
            if (System.nanoTime() > limite) {
                throw new AssertionError("El cargue " + id + " no terminó: " + e);
            }
            dormir(200);
        }
    }

    /** Ninguna salida identifica a un paciente: sin seudónimos, documentos ni nombres ficticios. */
    void sinIndividualizacion(String salida, Base b) {
        assertThat(SEUDONIMO.matcher(salida).find()).as("seudónimo en la salida").isFalse();
        assertThat(salida).doesNotContain("PACIENTE SINTETICO").doesNotContain("PACIENTE SEMBRADO");
        for (String doc : b.esperado().documentos()) {
            assertThat(salida).doesNotContain(";" + doc + ";").doesNotContain("\"" + doc + "\"");
        }
        assertThat(salida).doesNotContain("RM99999");
    }
}
