package co.com.agsamericas.digsa.analitica.tableros;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.analitica.GeneradorSintetico;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta;
import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tableros de analítica por la API (docs/especificaciones/analitica-tableros; M8-1, M8-2, M8-5, M8-8, M8-9): cuadre contra los totales
 * esperados del generador sintético (20 000 filas) en total y por Fuerza, ESM, modalidad, ATC nivel 1, mes, CUM y CIE-10, consistencia entre
 * dimensiones, alcance por perfil, filtro inexistente, corte anterior reproducible, descargas reproducibles con todos los metadatos,
 * trazabilidad hasta el registro de origen y bitácora.
 *
 * <p>La base es compartida con las demás pruebas de analítica (mismo contexto): el insumo de esta clase se pasa al año 2023 (el generador
 * escribe 2025) y todas las consultas van con ese periodo y con el corte de su cargue, así las cifras son solo las de este insumo.
 */
class TablerosApiTest extends PruebaIntegracion {

    private static final int FILAS = 20_000;
    private static final int PARTE = 1024 * 1024;
    private static final List<String> TERMINALES = List.of("aplicado", "rechazado", "fallido", "duplicado", "vencido", "descartado");
    private static final String PERIODO = "desde=2023-01-01&hasta=2023-12-31";

    /** Insumo base: el cargue aplicado, los totales esperados y los documentos ficticios (para buscar que nunca salgan). */
    private record Base(long cargue, GeneradorSintetico.Resultado esperado, Map<String, Map<String, String[]>> dimensiones) {
    }

    private static volatile Base base;

    // ---------- Apoyo ----------

    private Navegador como(String usuario) {
        Navegador n = navegador();
        assertThat(n.ingresar(usuario, CLAVE).estado()).isEqualTo(200);
        return n;
    }

    private Navegador nuevo(String prefijo, Perfil... perfiles) {
        return como(crearUsuario(prefijo, perfiles));
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
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        }
    }

    /** El CSV del generador pasado de 2025 a 2023 (los mismos días: ninguno de los dos años es bisiesto). */
    private static byte[] a2023(byte[] csv) {
        return new String(csv, StandardCharsets.UTF_8).replace("/2025;", "/2023;").replace("2025-13-45", "2023-13-45").getBytes(StandardCharsets.UTF_8);
    }

    private Base base() throws IOException {
        Base b = base;
        if (b != null) {
            return b;
        }
        synchronized (TablerosApiTest.class) {
            if (base == null) {
                Navegador n = nuevo("anatab", Perfil.ANALISTA);
                Path dir = Files.createTempDirectory("digsa-sintetico-tab-");
                GeneradorSintetico.Resultado r = GeneradorSintetico.generar(dir, "sintetico_tab", FILAS, 2023L);
                JsonNode fin = cargarInsumo(n, "insumo_tableros_2023.csv", a2023(Files.readAllBytes(r.csv())));
                assertThat(fin.path("estado").asText()).as(fin.toString()).isEqualTo("aplicado");
                assertThat(fin.path("procesados").asLong()).isEqualTo(r.procesados());
                Map<String, Map<String, String[]>> dims = GeneradorSintetico.leerDimensiones(r.totales());
                // Los meses del generador son de 2025: el insumo de esta clase va en 2023
                Map<String, String[]> meses = new TreeMap<>();
                dims.get("mes").forEach((k, v) -> meses.put(k.replace("2025-", "2023-"), v));
                dims.put("mes", meses);
                n.post("/api/auth/salir", null);
                base = new Base(fin.path("id").asLong(), r, dims);
            }
            return base;
        }
    }

    private static String q(String tablero, String extra) {
        return "/api/analitica/tableros/" + tablero + "?" + PERIODO + "&corte=" + base.cargue() + (extra == null || extra.isEmpty() ? "" : "&" + extra);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static JsonNode ok(Navegador.Respuesta r) {
        assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        return r.json();
    }

    private static JsonNode kpi(JsonNode t, String clave) {
        for (JsonNode k : t.path("kpis")) {
            if (k.path("clave").asText().equals(clave)) {
                return k;
            }
        }
        throw new AssertionError("Sin KPI " + clave + ": " + t.path("kpis"));
    }

    private static JsonNode tabla(JsonNode t, String id) {
        for (JsonNode x : t.path("tablas")) {
            if (x.path("id").asText().equals(id)) {
                return x;
            }
        }
        throw new AssertionError("Sin tabla " + id);
    }

    /**
     * Un código CIE-10 homologado del insumo base (el filtro CIE-10 usa el código final homologado, como la dimensión): el primero de las
     * opciones; sin referencias de CIE-10 publicadas en la base, «Sin homologar». Con {@code grupo}, sus 3 primeros caracteres.
     */
    private static String cie10(Navegador n, boolean grupo) {
        JsonNode op = ok(n.get("/api/analitica/opciones?variable=cie10&corte=" + base.cargue())).path("opciones");
        if (op.isEmpty()) {
            return enc(AnaliticaConsulta.SIN_HOMOLOGAR);
        }
        String c = op.get(0).path("valor").asText();
        return grupo ? c.substring(0, 3) : c;
    }

    private static int colOpcional(JsonNode tabla, String clave) {
        int i = 0;
        for (JsonNode c : tabla.path("columnas")) {
            if (c.path("clave").asText().equals(clave)) {
                return i;
            }
            i++;
        }
        return -1;
    }

    private static int col(JsonNode tabla, String clave) {
        int i = 0;
        for (JsonNode c : tabla.path("columnas")) {
            if (c.path("clave").asText().equals(clave)) {
                return i;
            }
            i++;
        }
        throw new AssertionError("Sin columna " + clave);
    }

    /** Valores por clave de fila (sin «Total»): gasto, unidades, dispensaciones y pacientes. */
    private static Map<String, Object[]> filas(JsonNode tabla) {
        Map<String, Object[]> m = new TreeMap<>();
        int g = col(tabla, "gasto");
        int u = colOpcional(tabla, "unidades");
        int d = col(tabla, "dispensaciones");
        int p = colOpcional(tabla, "pacientes");
        for (JsonNode f : tabla.path("filas")) {
            if (f.path("tipo").asText().equals("total")) {
                continue;
            }
            JsonNode v = f.path("valores");
            m.put(f.path("clave").asText(), new Object[] {new BigDecimal(v.get(g).asText()), u < 0 ? BigDecimal.ZERO : new BigDecimal(v.get(u).asText()), v.get(d).asLong(),
                p < 0 || v.get(p).isNull() ? null : v.get(p).asLong()});
        }
        return m;
    }

    /** Consulta estructurada (intención METRICAS) por la API. */
    private static JsonNode consulta(Navegador n, List<String> dimensiones, Map<String, List<String>> filtros) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("intencion", "METRICAS");
        c.put("metricas", List.of("GASTO", "UNIDADES", "DISPENSACIONES", "PACIENTES"));
        c.put("dimensiones", dimensiones);
        c.put("filtros", filtros);
        c.put("desde", "2023-01-01");
        c.put("hasta", "2023-12-31");
        c.put("corte", base.cargue());
        return ok(n.post("/api/analitica/consulta", c));
    }

    /** Filas de la consulta estructurada por clave: gasto, unidades, dispensaciones y pacientes. */
    private static Map<String, Object[]> filasConsulta(JsonNode r) {
        Map<String, Object[]> m = new TreeMap<>();
        for (JsonNode f : r.path("tablas").get(0).path("filas")) {
            JsonNode v = f.path("valores");
            int n = v.size();
            m.put(f.path("clave").asText(), new Object[] {new BigDecimal(v.get(n - 4).asText()), new BigDecimal(v.get(n - 3).asText()),
                v.get(n - 2).asLong(), v.get(n - 1).asLong()});
        }
        return m;
    }

    private static void cuadra(String que, Object[] sistema, String[] esperado, boolean pacientes) {
        assertThat(sistema).as(que + " existe").isNotNull();
        assertThat((BigDecimal) sistema[0]).as(que + " gasto").isEqualByComparingTo(esperado[1]);
        assertThat((BigDecimal) sistema[1]).as(que + " unidades").isEqualByComparingTo(esperado[3]);
        assertThat((Long) sistema[2]).as(que + " dispensaciones").isEqualTo(Long.parseLong(esperado[0]));
        if (pacientes) {
            assertThat((Long) sistema[3]).as(que + " pacientes").isEqualTo(Long.parseLong(esperado[2]));
        }
    }

    // ---------- Cuadre (14.5, M8-5) ----------

    @Test
    @DisplayName("cuadre total: gasto, unidades, dispensaciones y pacientes distintos iguales a los del generador; KPI del tablero general")
    void cuadreTotal() throws IOException {
        Base b = base();
        Navegador n = nuevo("anatab", Perfil.ANALISTA);
        JsonNode t = ok(n.get(q("general", null)));
        assertThat(t.path("disponible").asBoolean()).isTrue();
        assertThat(new BigDecimal(kpi(t, "gasto").path("valor").asText())).isEqualByComparingTo(b.esperado().gasto());
        assertThat(kpi(t, "dispensaciones").path("valor").asLong()).isEqualTo(b.esperado().procesados());
        assertThat(kpi(t, "unidades").path("valor").asLong()).isEqualTo(b.esperado().unidades());
        assertThat(kpi(t, "pacientes").path("valor").asLong()).isEqualTo(b.esperado().pacientes());
        assertThat(t.path("metadatos").path("registros").asLong()).isEqualTo(b.esperado().procesados());
        assertThat(t.path("metadatos").path("corteCargueId").asLong()).isEqualTo(b.cargue());
        assertThat(t.path("huella").asText()).hasSize(64);
        // Formato 2.4 del KPI de gasto: «$ 123,45» sin separador de miles
        assertThat(kpi(t, "gasto").path("texto").asText()).matches("^\\$ \\d+,\\d{2}$");
        // La serie mensual tiene los 12 meses de 2023 y suma el total
        JsonNode serie = tabla(t, "serie");
        assertThat(serie.path("filas")).hasSize(12);
        BigDecimal s = BigDecimal.ZERO;
        for (JsonNode f : serie.path("filas")) {
            s = s.add(new BigDecimal(f.path("valores").get(col(serie, "gasto")).asText()));
        }
        assertThat(s).isEqualByComparingTo(b.esperado().gasto());
    }

    @Test
    @DisplayName("cuadre por Fuerza, ESM, modalidad, mes y CUM contra los totales del generador (con pacientes distintos exactos por celda)")
    void cuadrePorDimension() throws IOException {
        Base b = base();
        Navegador n = nuevo("anatab", Perfil.ANALISTA);
        Map<String, String> dims = Map.of("fuerza", "FUERZA", "esm", "ESM", "modalidad", "MODALIDAD", "mes", "PERIODO", "cum", "CUM");
        for (Map.Entry<String, String> d : dims.entrySet()) {
            Map<String, Object[]> sistema = filasConsulta(consulta(n, List.of(d.getValue()), Map.of()));
            Map<String, String[]> esperado = b.dimensiones().get(d.getKey());
            assertThat(sistema.keySet()).as(d.getKey()).isEqualTo(esperado.keySet());
            esperado.forEach((k, v) -> cuadra(d.getKey() + " " + k, sistema.get(k), v, true));
        }
    }

    @Test
    @DisplayName("cuadre por ATC nivel 1 y CIE-10: lo homologado por código, lo demás en «Sin homologar»; ninguna dispensación se pierde")
    void cuadreAtcYCie10() throws IOException {
        Base b = base();
        Navegador n = nuevo("anatab", Perfil.ANALISTA);
        // ATC 1 del sistema por CUM (tabla maestra vigente al corte) y totales del generador por CUM
        Map<String, String> atcDeCum = new HashMap<>();
        for (JsonNode f : consulta(n, List.of("CUM", "ATC1"), Map.of()).path("tablas").get(0).path("filas")) {
            String[] k = f.path("clave").asText().split("\\|");
            atcDeCum.put(k[0], k[1]);
        }
        Map<String, String[]> porCum = b.dimensiones().get("cum");
        assertThat(atcDeCum.keySet()).isEqualTo(porCum.keySet());
        Map<String, long[]> esperado = new TreeMap<>();
        Map<String, BigDecimal> gastoEsperado = new TreeMap<>();
        porCum.forEach((c, v) -> {
            String a = atcDeCum.get(c);
            long[] x = esperado.computeIfAbsent(a, k -> new long[2]);
            x[0] += Long.parseLong(v[0]);
            x[1] += Long.parseLong(v[3]);
            gastoEsperado.merge(a, new BigDecimal(v[1]), BigDecimal::add);
        });
        Map<String, Object[]> atc = filasConsulta(consulta(n, List.of("ATC1"), Map.of()));
        assertThat(atc.keySet()).isEqualTo(esperado.keySet());
        esperado.forEach((k, v) -> {
            assertThat((Long) atc.get(k)[2]).as("ATC1 " + k + " dispensaciones").isEqualTo(v[0]);
            assertThat((BigDecimal) atc.get(k)[1]).as("ATC1 " + k + " unidades").isEqualByComparingTo(BigDecimal.valueOf(v[1]));
            assertThat((BigDecimal) atc.get(k)[0]).as("ATC1 " + k + " gasto").isEqualByComparingTo(gastoEsperado.get(k));
        });
        // Pacientes por ATC 1 donde el grupo del sistema es exactamente el del insumo (sin mezclar CUM de otros grupos)
        Map<String, String[]> atc1Insumo = b.dimensiones().get("atc1");
        for (String k : atc.keySet()) {
            if (!k.equals(AnaliticaConsulta.SIN_HOMOLOGAR) && atc1Insumo.containsKey(k)
                    && Long.parseLong(atc1Insumo.get(k)[0]) == (Long) atc.get(k)[2]) {
                assertThat((Long) atc.get(k)[3]).as("ATC1 " + k + " pacientes").isEqualTo(Long.parseLong(atc1Insumo.get(k)[2]));
            }
        }
        // CIE-10: los códigos homologados cuadran uno a uno; «Sin CIE-10» es el vacío del insumo; el resto suma «Sin homologar»
        Map<String, Object[]> cie = filasConsulta(consulta(n, List.of("CIE10"), Map.of()));
        Map<String, String[]> cieInsumo = b.dimensiones().get("cie10");
        long sinHomologar = 0;
        for (Map.Entry<String, String[]> e : cieInsumo.entrySet()) {
            String k = e.getKey().equals("(sin CIE-10)") ? AnaliticaConsulta.SIN_DATO : e.getKey();
            if (cie.containsKey(k)) {
                cuadra("CIE-10 " + k, cie.get(k), e.getValue(), true);
            } else {
                sinHomologar += Long.parseLong(e.getValue()[0]);
            }
        }
        long sh = cie.containsKey(AnaliticaConsulta.SIN_HOMOLOGAR) ? (Long) cie.get(AnaliticaConsulta.SIN_HOMOLOGAR)[2] : 0;
        assertThat(sh).as("CIE-10 sin homologar").isEqualTo(sinHomologar);
    }

    @Test
    @DisplayName("consistencia entre dimensiones: la suma por ATC = por ESM = por medicamento = por CIE-10 = total (con Resto y Sin homologar)")
    void consistenciaEntreDimensiones() throws IOException {
        Base b = base();
        Navegador n = nuevo("anatab", Perfil.ANALISTA);
        for (String[] t : new String[][] {{"atc", "atc"}, {"territorial", "esm"}, {"medicamento", "medicamento"}, {"diagnostico", "cie10"},
            {"principio", "principio"}, {"territorial", "fuerza"}, {"gasto", "modalidad"}}) {
            JsonNode r = ok(n.get(q(t[0], null)));
            JsonNode tb = tabla(r, t[1]);
            BigDecimal suma = BigDecimal.ZERO;
            long disp = 0;
            for (Object[] f : filas(tb).values()) {
                suma = suma.add((BigDecimal) f[0]);
                disp += (Long) f[2];
            }
            assertThat(suma).as(t[0] + "/" + t[1] + " gasto").isEqualByComparingTo(b.esperado().gasto());
            assertThat(disp).as(t[0] + "/" + t[1] + " dispensaciones").isEqualTo(b.esperado().procesados());
            // La fila Total es la del KPI y la participación acumulada llega a 100 %
            JsonNode total = tb.path("filas").get(tb.path("filas").size() - 1);
            assertThat(total.path("tipo").asText()).isEqualTo("total");
            assertThat(new BigDecimal(total.path("valores").get(col(tb, "gasto")).asText())).isEqualByComparingTo(b.esperado().gasto());
        }
        // Top 20 + Resto en principio activo (40 principios en el generador) cuando hay más de 20 homologados
        JsonNode pa = tabla(ok(n.get(q("principio", null))), "principio");
        long datos = 0;
        for (JsonNode f : pa.path("filas")) {
            if (f.path("tipo").asText().equals("dato")) {
                datos++;
            }
        }
        assertThat(datos).isLessThanOrEqualTo(20);
    }

    @Test
    @DisplayName("los 12 tableros responden: 9 con cifras y cohortes, atípicos y predictivo «Disponible en la siguiente fase»; sin seudónimos ni documentos")
    void doceTableros() throws IOException {
        Base b = base();
        Navegador n = nuevo("anatab", Perfil.ANALISTA);
        StringBuilder todo = new StringBuilder();
        for (AnaliticaConsulta.Tablero t : AnaliticaConsulta.Tablero.values()) {
            Navegador.Respuesta r = n.get(q(t.codigo(), "comparacion=anio-anterior"));
            JsonNode j = ok(r);
            com.fasterxml.jackson.databind.node.ObjectNode sinHuella = j.deepCopy();
            sinHuella.remove("huella");
            todo.append(sinHuella);
            assertThat(j.path("disponible").asBoolean()).as(t.codigo()).isEqualTo(t.disponible());
            if (!t.disponible()) {
                assertThat(j.path("mensaje").asText()).isEqualTo("Disponible en la siguiente fase");
            } else {
                assertThat(j.path("kpis").size()).as(t.codigo()).isPositive();
                assertThat(j.path("tablas").size()).as(t.codigo()).isPositive();
            }
        }
        String texto = todo.toString();
        for (String doc : b.esperado().documentos()) {
            assertThat(texto).as("sin documentos en claro").doesNotContain(doc);
        }
        assertThat(texto).as("sin seudónimos de paciente (la única huella SHA-256 es la del resultado)").doesNotContainPattern("[0-9a-f]{64}");
        assertThat(texto).doesNotContain("PACIENTE SINTETICO");
        // Poliformulación: los rangos suman los pacientes-mes y el umbral es 5
        JsonNode p = ok(n.get(q("poliformulacion", null)));
        assertThat(kpi(p, "poliformulados").path("nota").asText()).contains("≥ 5 principios activos distintos en el mes");
        // Alto costo: umbral p99 de 12 meses
        JsonNode ac = ok(n.get(q("alto-costo", null)));
        assertThat(kpi(ac, "umbralAltoCosto").path("etiqueta").asText()).contains("p99");
        assertThat(kpi(ac, "pacientesAltoCosto").path("valor").asLong()).isPositive();
        // Gasto: comparación con el año anterior (2022 sin datos ⇒ «—», nunca infinito)
        JsonNode g = ok(n.get(q("gasto", "comparacion=anio-anterior")));
        assertThat(kpi(g, "gasto").path("variacionPct").isNull()).isTrue();
        assertThat(kpi(g, "gasto").path("textoVariacion").asText()).startsWith("—");
    }

    @Test
    @DisplayName("granularidad bimestral: 6 puntos de 2023 que suman el total; filtro múltiple de ESM y CIE-10 cuadra con la suma de sus filas")
    void granularidadYFiltroMultiple() throws IOException {
        Base b = base();
        Navegador n = nuevo("anatab", Perfil.ANALISTA);
        JsonNode serie = tabla(ok(n.get(q("general", "granularidad=bimestre"))), "serie");
        assertThat(serie.path("filas")).hasSize(6);
        assertThat(serie.path("filas").get(0).path("clave").asText()).isEqualTo("2023-B1");
        BigDecimal s = BigDecimal.ZERO;
        for (JsonNode f : serie.path("filas")) {
            s = s.add(new BigDecimal(f.path("valores").get(col(serie, "gasto")).asText()));
        }
        assertThat(s).isEqualByComparingTo(b.esperado().gasto());
        // esm=[BAS02, DMBOG] y cie10=[E11]: el KPI es la suma de las filas por ESM
        JsonNode t = ok(n.get(q("territorial", "esm=BAS02&esm=DMBOG&cie10=" + cie10(n, true))));
        BigDecimal kpi = new BigDecimal(kpi(t, "gasto").path("valor").asText());
        BigDecimal suma = BigDecimal.ZERO;
        for (Object[] f : filas(tabla(t, "esm")).values()) {
            suma = suma.add((BigDecimal) f[0]);
        }
        assertThat(suma).isEqualByComparingTo(kpi);
        assertThat(filas(tabla(t, "esm")).keySet()).isSubsetOf(Set.of("BAS02", "DMBOG"));
    }

    // ---------- Alcance y validación ----------

    @Test
    @DisplayName("alcance: Sanidad ARC ve solo su Fuerza (cuadra con el generador); pedir EJC es 422; ESM solo su establecimiento; sin pantalla 403")
    void alcance() throws IOException {
        Base b = base();
        String sanidad = crearUsuario("sanidadtab", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'ARC' WHERE usuario = :u").param("u", sanidad).update();
        Navegador s = como(sanidad);
        JsonNode t = ok(s.get(q("general", null)));
        String[] arc = b.dimensiones().get("fuerza").get("ARC");
        assertThat(new BigDecimal(kpi(t, "gasto").path("valor").asText())).isEqualByComparingTo(arc[1]);
        assertThat(kpi(t, "pacientes").path("valor").asLong()).isEqualTo(Long.parseLong(arc[2]));
        assertThat(t.path("niveles").toString()).doesNotContain("nacional");
        assertThat(t.path("nivel").asText()).isEqualTo("fuerza");
        Navegador.Respuesta otra = s.get(q("general", "fuerza=EJC"));
        assertThat(otra.estado()).isEqualTo(422);
        assertThat(otra.mensaje()).isEqualTo("Fuerza «EJC» no existe o está fuera de su alcance.");
        // Las opciones de Fuerza solo traen la suya
        JsonNode op = ok(s.get("/api/analitica/opciones?variable=fuerza&corte=" + b.cargue())).path("opciones");
        assertThat(op).hasSize(1);
        assertThat(op.get(0).path("valor").asText()).isEqualTo("ARC");

        long punto = jdbc.sql("SELECT id FROM puntos WHERE upper(sigla) = 'DMBOG' ORDER BY id LIMIT 1").query(Long.class).single();
        String esm = crearUsuario("esmtab", Perfil.ESM);
        jdbc.sql("UPDATE usuarios SET alcance_punto = :p WHERE usuario = :u").param("p", punto).param("u", esm).update();
        Navegador e = como(esm);
        JsonNode te = ok(e.get(q("territorial", null)));
        String[] dmbog = b.dimensiones().get("esm").get("DMBOG");
        assertThat(new BigDecimal(kpi(te, "gasto").path("valor").asText())).isEqualByComparingTo(dmbog[1]);
        assertThat(kpi(te, "pacientes").path("valor").asLong()).isEqualTo(Long.parseLong(dmbog[2]));
        assertThat(te.path("niveles").toString()).isEqualTo("[\"esm\"]");
        assertThat(e.get(q("territorial", "esm=BAS02")).estado()).isEqualTo(422);

        for (Perfil sin : List.of(Perfil.AUDITOR, Perfil.OPL_NACIONAL)) {
            Navegador x = nuevo("sinana", sin);
            assertThat(x.get(q("gasto", null)).estado()).as(sin.name()).isEqualTo(403);
            assertThat(x.get("/api/analitica/catalogo").estado()).as(sin.name()).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("filtro inexistente: CUM 99999999-99 responde 422 sin cifras; un parámetro desconocido también; un tablero inexistente 404")
    void filtroInexistente() throws IOException {
        base();
        Navegador n = nuevo("anatab", Perfil.ANALISTA);
        Navegador.Respuesta r = n.get(q("medicamento", "cum=99999999-99"));
        assertThat(r.estado()).isEqualTo(422);
        assertThat(r.mensaje()).isEqualTo("CUM «99999999-99» no existe o está fuera de su alcance.");
        assertThat(r.texto()).doesNotContain("kpis");
        assertThat(n.get(q("medicamento", "atc=Q99")).estado()).isEqualTo(422);
        assertThat(n.get(q("medicamento", "ciudad=X")).mensaje()).isEqualTo("Parámetro desconocido: «ciudad».");
        assertThat(n.get(q("medicamento", "granularidad=hora")).mensaje()).startsWith("No disponible en el insumo");
        assertThat(n.get("/api/analitica/tableros/otro?" + PERIODO).estado()).isEqualTo(404);
        // Un valor que existe pero sin datos en el periodo sí da 0
        JsonNode cero = ok(n.get("/api/analitica/tableros/gasto?desde=2023-01-01&hasta=2023-01-01&corte=" + base.cargue() + "&esm=BAS02&cie10=" + cie10(n, false)));
        assertThat(cero.path("disponible").asBoolean()).isTrue();
    }

    // ---------- Corte y reproducibilidad (14.8) ----------

    @Test
    @DisplayName("corte anterior reproducible: la misma consulta con el corte C1 da la misma huella después de aplicar C2; el corte por defecto incluye C2")
    void corteAnteriorReproducible() throws IOException {
        Base b = base();
        Navegador n = nuevo("anatab", Perfil.ANALISTA);
        String antes = ok(n.get(q("gasto", "fuerza=EJC"))).path("huella").asText();
        StringBuilder csv = new StringBuilder("﻿" + GeneradorSintetico.ENCABEZADO + "\r\n");
        for (int i = 0; i < 30; i++) {
            csv.append("CC;").append(70_000_000 + i).append(";PACIENTE FICTICIO;01/01/1980;EJC;BAS02;Dispensación;").append(String.format("%02d", 1 + i % 28))
                    .append("/06/2023;LOSARTAN 50 MG TABLETA;20010037-1;LOSARTAN;C09CA01;I10X;30;180,50;5415,00;RM10001;Crónica;FC2-").append(i).append("\r\n");
        }
        JsonNode c2 = cargarInsumo(n, "insumo_corte_c2.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        assertThat(c2.path("estado").asText()).as(c2.toString()).isEqualTo("aplicado");
        JsonNode despues = ok(n.get(q("gasto", "fuerza=EJC")));
        assertThat(despues.path("huella").asText()).isEqualTo(antes);
        String porDefecto = "/api/analitica/tableros/gasto?" + PERIODO + "&fuerza=EJC";
        JsonNode nuevo = ok(n.get(porDefecto));
        assertThat(nuevo.path("metadatos").path("cargues").toString()).contains(c2.path("id").asText());
        assertThat(new BigDecimal(kpi(nuevo, "gasto").path("valor").asText()))
                .isEqualByComparingTo(new BigDecimal(kpi(despues, "gasto").path("valor").asText()).add(new BigDecimal("162450.00")));
        // Los cortes aplicados se listan para elegir uno anterior
        assertThat(ok(n.get("/api/analitica/cortes")).toString()).contains("\"id\":" + b.cargue());
    }

    @Test
    @DisplayName("dos usuarios del mismo perfil: misma huella en pantalla y en las descargas; las filas de datos iguales byte a byte salvo usuario y hora")
    void dosUsuariosMismoResultado() throws IOException {
        base();
        Navegador a = nuevo("anaunoa", Perfil.ANALISTA);
        Navegador b = nuevo("anadosb", Perfil.ANALISTA);
        String consulta = q("medicamento", "fuerza=EJC&comparacion=anio-anterior");
        assertThat(ok(a.get(consulta)).path("huella").asText()).isEqualTo(ok(b.get(consulta)).path("huella").asText());
        for (String f : List.of("csv", "xlsx", "pdf")) {
            String ruta = q("medicamento", "fuerza=EJC&formato=" + f).replace("/tableros/medicamento?", "/tableros/medicamento/descarga?");
            Navegador.Respuesta ra = a.get(ruta);
            Navegador.Respuesta rb = b.get(ruta);
            assertThat(ra.estado()).as(f + ": " + (ra.estado() == 200 ? "" : ra.texto())).isEqualTo(200);
            assertThat(ra.cabecera("X-Huella-Datos")).isEqualTo(rb.cabecera("X-Huella-Datos")).hasSize(64);
            if (f.equals("csv")) {
                assertThat(sinUsuarioNiHora(ra.texto())).isEqualTo(sinUsuarioNiHora(rb.texto()));
            } else if (f.equals("xlsx")) {
                assertThat(hoja(ra.cuerpo(), "Datos")).isEqualTo(hoja(rb.cuerpo(), "Datos"));
            }
        }
    }

    private static List<String> sinUsuarioNiHora(String csv) {
        return csv.lines().filter(l -> !l.startsWith("Usuario;") && !l.startsWith("Generado;") && !l.startsWith("Fecha y hora de generación;")).toList();
    }

    private static List<String> hoja(byte[] xlsx, String nombre) throws IOException {
        List<String> l = new ArrayList<>();
        try (XSSFWorkbook w = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet s = w.getSheet(nombre);
            assertThat(s).as("hoja " + nombre).isNotNull();
            for (Row r : s) {
                StringBuilder sb = new StringBuilder();
                for (Cell c : r) {
                    sb.append(c.getCellType() == CellType.NUMERIC ? String.valueOf(c.getNumericCellValue()) : c.getStringCellValue()).append('|');
                }
                String t = sb.toString();
                if (!t.startsWith("Usuario|") && !t.startsWith("Generado|")) {
                    l.add(t);
                }
            }
        }
        return l;
    }

    @Test
    @DisplayName("metadatos completos en Excel, CSV y PDF (título, página, fecha, usuario, perfil, periodo, establecimiento, filtros, fuente, corte, registros, gráficos, observaciones, huella) y «Página n de m» en el PDF")
    void metadatosCompletos() throws IOException {
        Base b = base();
        Navegador n = nuevo("anatab", Perfil.ANALISTA);
        List<String> etiquetas = List.of("Título", "N.º de página", "Fecha y hora de generación", "Usuario", "Perfil", "Periodo", "Granularidad",
                "Periodo de comparación", "Establecimiento", "Filtros", "Fuente", "Fecha de corte", "N.º de registros procesados", "Gráficos",
                "Observaciones y alertas", "Huella SHA-256 del contenido");
        String cie = cie10(n, true);
        String filtros = "fuerza=EJC&cie10=" + cie + "&comparacion=anio-anterior";
        for (String t : List.of("gasto", "medicamento", "territorial")) {
            String ruta = q(t, filtros + "&formato=").replace("/tableros/" + t + "?", "/tableros/" + t + "/descarga?");
            Navegador.Respuesta csv = n.get(ruta + "csv");
            assertThat(csv.estado()).as(csv.texto()).isEqualTo(200);
            String texto = csv.texto();
            assertThat(texto).startsWith("﻿");
            for (String e : etiquetas) {
                assertThat(texto).as(t + " CSV: " + e).contains(e + ";");
            }
            assertThat(texto).contains("Fuerza: EJC").contains("CIE-10: ").contains("Alcance: Nacional").contains("Tabla maestra homologada")
                    .contains("(cargue " + b.cargue() + ")");
            assertThat(texto).doesNotContain(";=").doesNotContain(";+");

            Navegador.Respuesta x = n.get(ruta + "xlsx");
            assertThat(x.estado()).isEqualTo(200);
            List<String> meta = hoja(x.cuerpo(), "Metadatos");
            for (String e : etiquetas) {
                if (!e.equals("Usuario")) {
                    assertThat(meta).as(t + " Excel: " + e).anyMatch(l -> l.startsWith(e + "|"));
                }
            }
            assertThat(hoja(x.cuerpo(), "Datos")).isNotEmpty();

            Navegador.Respuesta p = n.get(ruta + "pdf");
            assertThat(p.estado()).isEqualTo(200);
            assertThat(p.cabecera("Content-Type")).startsWith("application/pdf");
            com.lowagie.text.pdf.PdfReader lector = new com.lowagie.text.pdf.PdfReader(p.cuerpo());
            int paginas = lector.getNumberOfPages();
            com.lowagie.text.pdf.parser.PdfTextExtractor ex = new com.lowagie.text.pdf.parser.PdfTextExtractor(lector);
            String primera = ex.getTextFromPage(1);
            for (String e : etiquetas) {
                if (!e.equals("N.º de página")) {
                    assertThat(primera.replace("\n", " ")).as(t + " PDF: " + e).contains(e);
                }
            }
            assertThat(ex.getTextFromPage(paginas)).contains("Página " + paginas + " de " + paginas);
            lector.close();
        }
    }

    // ---------- Trazabilidad (14.9, M8-9) ----------

    @Test
    @DisplayName("rastrear una barra: los registros de origen suman la cifra, con cargue, archivo, línea, SHA-256 y regla; sin documentos en claro")
    void trazabilidad() throws IOException {
        Base b = base();
        Navegador n = nuevo("anatab", Perfil.ANALISTA);
        JsonNode t = ok(n.get(q("atc", "fuerza=EJC")));
        JsonNode tb = tabla(t, "atc");
        JsonNode fila = tb.path("filas").get(0);
        String atc = fila.path("rastreo").path("atc").asText();
        BigDecimal cifra = new BigDecimal(fila.path("valores").get(col(tb, "gasto")).asText());
        String base = "/api/analitica/traza/atc?" + PERIODO + "&corte=" + b.cargue() + "&fuerza=EJC&atc=" + enc(atc) + "&periodo=2023-09";
        Navegador.Respuesta r = n.get(base);
        JsonNode traza = ok(r);
        // La celda «ATC × EJC × septiembre» es la misma consulta con el periodo del mes
        JsonNode mes = ok(n.get("/api/analitica/tableros/atc?desde=2023-09-01&hasta=2023-09-30&corte=" + b.cargue() + "&fuerza=EJC&atc=" + enc(atc)));
        BigDecimal cifraMes = new BigDecimal(kpi(mes, "gasto").path("valor").asText());
        assertThat(new BigDecimal(traza.path("desglose").path("gasto").asText())).isEqualByComparingTo(cifraMes);
        assertThat(cifra).isGreaterThanOrEqualTo(cifraMes);
        JsonNode reg = traza.path("registros").get(0);
        assertThat(reg.path("cargue").asLong()).isEqualTo(b.cargue());
        assertThat(reg.path("archivo").asText()).isEqualTo("insumo_tableros_2023.csv");
        assertThat(reg.path("sha256").asText()).hasSize(64);
        assertThat(reg.path("linea").asText()).isNotBlank();
        assertThat(reg.path("regla").asText()).isNotBlank();
        assertThat(reg.path("paciente").asText()).matches("^[0-9a-f]{64}$");
        // La lista completa (CSV) suma la cifra
        Navegador.Respuesta csv = n.get(base.replace("/traza/atc?", "/traza/atc/registros.csv?"));
        assertThat(csv.estado()).as(csv.texto()).isEqualTo(200);
        List<String> lineas = csv.texto().lines().toList();
        int inicio = lineas.indexOf(lineas.stream().filter(l -> l.startsWith("Id del hecho;")).findFirst().orElseThrow()) + 1;
        BigDecimal suma = BigDecimal.ZERO;
        // Termina con «FIN;n registros» (un archivo cortado no la tiene)
        assertThat(lineas.getLast()).isEqualTo("FIN;" + traza.path("total").asInt() + " registros");
        int valor = ServicioTrazaAnalitica.COLUMNAS_CSV.indexOf("Valor");
        for (String l : lineas.subList(inicio, lineas.size() - 1)) {
            suma = suma.add(new BigDecimal(l.split(";")[valor].replace(',', '.')));
        }
        assertThat(suma).isEqualByComparingTo(cifraMes);
        assertThat(lineas.size() - 1 - inicio).isEqualTo(traza.path("total").asInt());
        // Minimización: el registro no lleva el diagnóstico ni la Fuerza junto al seudónimo
        assertThat(reg.has("cie10")).isFalse();
        assertThat(ServicioTrazaAnalitica.COLUMNAS_CSV).doesNotContain("CIE-10");
        // Paginación por llave: la página 2 empieza después del último id de la 1 y no repite registros
        if (traza.path("siguiente").isNumber()) {
            JsonNode p2 = ok(n.get(base + "&pagina=2&despues=" + traza.path("siguiente").asLong()));
            assertThat(p2.path("registros").get(0).path("id").asLong()).isGreaterThan(traza.path("siguiente").asLong());
            assertThat(p2.path("desglose").path("gasto").decimalValue()).isEqualByComparingTo(cifraMes);
        }
        for (String doc : b.esperado().documentos()) {
            assertThat(r.texto() + csv.texto()).as("sin documentos en claro").doesNotContain(doc);
        }
        assertThat(csv.texto()).doesNotContain("PACIENTE SINTETICO");
        // DIGSA también rastrea; Sanidad y ESM no (403) y su catálogo dice que no
        assertThat(nuevo("digsatab", Perfil.DIGSA).get(base).estado()).isEqualTo(200);
        String sanidad = crearUsuario("sanidadtz", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'EJC' WHERE usuario = :u").param("u", sanidad).update();
        Navegador s = como(sanidad);
        assertThat(s.get(base).estado()).isEqualTo(403);
        assertThat(ok(s.get("/api/analitica/catalogo")).path("puedeRastrear").asBoolean()).isFalse();
        assertThat(ok(n.get("/api/analitica/catalogo")).path("puedeRastrear").asBoolean()).isTrue();
    }

    /** Gasto de la traza de una celda (la misma consulta del tablero con los filtros de la celda). */
    private static BigDecimal gastoTraza(Navegador n, String tablero, String filtros) {
        JsonNode t = ok(n.get("/api/analitica/traza/" + tablero + "?" + PERIODO + "&corte=" + base.cargue() + filtros));
        return t.path("desglose").path("gasto").decimalValue();
    }

    @Test
    @DisplayName("rastreo que suma: cada fila de ATC nivel 1 a 5 y de grupo CIE-10 rastreada da exactamente su cifra; con un filtro más específico que la fila, también")
    void rastreoSumaLaCifra() throws IOException {
        base();
        Navegador n = nuevo("anatab", Perfil.ANALISTA);
        String atc5 = null;
        for (int nivel = 1; nivel <= 5; nivel++) {
            JsonNode tb = tabla(ok(n.get(q("atc", "nivelAtc=" + nivel))), "atc");
            int g = col(tb, "gasto");
            int vistas = 0;
            for (JsonNode f : tb.path("filas")) {
                if (f.path("rastreo").isNull() || f.path("tipo").asText().equals("total") || vistas++ >= 4) {
                    continue;
                }
                String valor = f.path("rastreo").path("atc").asText();
                if (nivel == 5 && !valor.equals(AnaliticaConsulta.SIN_HOMOLOGAR) && atc5 == null) {
                    atc5 = valor;
                }
                assertThat(gastoTraza(n, "atc", "&nivelAtc=" + nivel + "&atc=" + enc(valor))).as("ATC " + nivel + " " + valor)
                        .isEqualByComparingTo(new BigDecimal(f.path("valores").get(g).asText()));
            }
        }
        if (atc5 != null) {
            // Tablero filtrado por un ATC de nivel 5 y mostrado en nivel 1: la fila de nivel 1 se rastrea con el filtro más específico
            // (lo que hace la pantalla al cruzar la celda con el filtro) y suma exactamente su cifra
            JsonNode tb = tabla(ok(n.get(q("atc", "nivelAtc=1&atc=" + enc(atc5)))), "atc");
            JsonNode f = tb.path("filas").get(0);
            assertThat(f.path("rastreo").path("atc").asText()).isEqualTo(atc5.substring(0, 1));
            assertThat(gastoTraza(n, "atc", "&nivelAtc=1&atc=" + enc(atc5)))
                    .isEqualByComparingTo(new BigDecimal(f.path("valores").get(col(tb, "gasto")).asText()));
        }
        // Grupo CIE-10 (código final homologado, igual en el filtro y en la dimensión) y «Sin homologar» / «Sin CIE-10»
        JsonNode c = consulta(n, List.of("CIE10_GRUPO"), Map.of());
        for (JsonNode f : c.path("tablas").get(0).path("filas")) {
            String grupo = f.path("rastreo").path("cie10").asText();
            JsonNode v = f.path("valores");
            assertThat(gastoTraza(n, "diagnostico", "&cie10=" + enc(grupo))).as("CIE-10 " + grupo)
                    .isEqualByComparingTo(new BigDecimal(v.get(v.size() - 4).asText()));
        }
        // La fila «Sin DISAN» (disan=(sin dato)) es un filtro válido
        assertThat(n.get(q("territorial", "disan=" + enc(AnaliticaConsulta.SIN_DATO))).estado()).isEqualTo(200);
    }

    @Test
    @DisplayName("PDF sin edición: cifrado con permisos solo de impresión y copia (RESP L555)")
    void pdfSinEdicion() throws IOException {
        base();
        Navegador.Respuesta p = nuevo("anatab", Perfil.ANALISTA).get(q("gasto", "formato=pdf").replace("/tableros/gasto?", "/tableros/gasto/descarga?"));
        com.lowagie.text.pdf.PdfReader lector = new com.lowagie.text.pdf.PdfReader(p.cuerpo());
        assertThat(lector.isEncrypted()).isTrue();
        assertThat(lector.getPermissions() & com.lowagie.text.pdf.PdfWriter.ALLOW_MODIFY_CONTENTS).isZero();
        assertThat(lector.getPermissions() & com.lowagie.text.pdf.PdfWriter.ALLOW_MODIFY_ANNOTATIONS).isZero();
        assertThat(lector.getPermissions() & com.lowagie.text.pdf.PdfWriter.ALLOW_PRINTING).isNotZero();
        assertThat(lector.getPermissions() & com.lowagie.text.pdf.PdfWriter.ALLOW_COPY).isNotZero();
        lector.close();
    }

    @Test
    @DisplayName("versión de la tabla maestra publicada después del corte (Revisión): la huella del corte no cambia y el resultado lo advierte")
    void tablaMaestraPosteriorAlCorte() throws IOException {
        base();
        Navegador n = nuevo("anatab", Perfil.ANALISTA);
        JsonNode antes = ok(n.get(q("general", "")));
        Integer numero = jdbc.sql("SELECT coalesce(max(numero), 0) + 1 FROM hom_version").query(Integer.class).single();
        jdbc.sql("INSERT INTO hom_version (numero, estado, creada, motivo, responsable_texto) VALUES (:n, 'publicada', now(), 'aprobacion', 'Prueba')")
                .param("n", numero).update();
        JsonNode despues = ok(n.get(q("general", "")));
        assertThat(despues.path("huella").asText()).isEqualTo(antes.path("huella").asText());
        assertThat(despues.path("advertencias").toString()).contains("Hay una tabla maestra más reciente (v" + numero);
        assertThat(despues.path("metadatos").path("tablaMaestra")).isEqualTo(antes.path("metadatos").path("tablaMaestra"));
    }

    @Test
    @DisplayName("cortes con alcance: Sanidad no ve nombres de archivo ni registros nacionales; el analista nacional sí; la supresión se declara")
    void cortesConAlcance() throws IOException {
        Base b = base();
        String sanidad = crearUsuario("sanidadct", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'ARC' WHERE usuario = :u").param("u", sanidad).update();
        for (JsonNode c : ok(como(sanidad).get("/api/analitica/cortes"))) {
            assertThat(c.path("archivo").isNull()).isTrue();
            assertThat(c.path("procesados").isNull()).isTrue();
        }
        Navegador n = nuevo("anatab", Perfil.ANALISTA);
        boolean visto = false;
        for (JsonNode c : ok(n.get("/api/analitica/cortes"))) {
            if (c.path("id").asLong() == b.cargue()) {
                visto = true;
                assertThat(c.path("archivo").asText()).isEqualTo("insumo_tableros_2023.csv");
            }
        }
        assertThat(visto).isTrue();
        assertThat(ok(n.get(q("general", ""))).path("metadatos").path("supresion").asText()).startsWith("Desactivada");
    }

    // ---------- Bitácora ----------

    @Test
    @DisplayName("bitácora: consulta y descarga quedan en ana_consultas con usuario, filtros, corte y la misma huella, y en log_transacciones; sin datos personales")
    void bitacora() throws IOException {
        Base b = base();
        String usuario = crearUsuario("anabit", Perfil.ANALISTA);
        Navegador n = como(usuario);
        String huella = ok(n.get(q("gasto", "fuerza=EJC"))).path("huella").asText();
        Navegador.Respuesta d = n.get(q("gasto", "fuerza=EJC&formato=pdf").replace("/tableros/gasto?", "/tableros/gasto/descarga?"));
        assertThat(d.estado()).isEqualTo(200);
        long id = idDe(usuario);
        List<Map<String, Object>> filas = jdbc.sql("SELECT accion, huella, corte_cargue_id, filtros::text AS filtros, formato, registros FROM ana_consultas "
                + "WHERE usuario_id = :u ORDER BY id").param("u", id).query().listOfRows();
        assertThat(filas).extracting(f -> f.get("accion")).containsExactly("consulta", "descarga");
        assertThat(filas).allSatisfy(f -> {
            assertThat(f.get("huella")).isEqualTo(huella);
            assertThat(((Number) f.get("corte_cargue_id")).longValue()).isEqualTo(b.cargue());
            assertThat(f.get("filtros").toString()).contains("\"fuerza\": [\"EJC\"]");
        });
        assertThat(filas.get(1).get("formato")).isEqualTo("pdf");
        List<String> log = bitacoraDe(id);
        assertThat(log).anyMatch(l -> l.startsWith("consulta|analitica_consulta|")).anyMatch(l -> l.startsWith("consulta|analitica_descarga|"));
        for (String doc : b.esperado().documentos()) {
            assertThat(String.join("\n", log)).doesNotContain(doc);
        }
        // Un filtro rechazado también queda (resultado «rechazada»)
        n.get(q("gasto", "cum=99999999-99"));
        assertThat(jdbc.sql("SELECT resultado FROM ana_consultas WHERE usuario_id = :u ORDER BY id DESC LIMIT 1").param("u", id).query(String.class).single())
                .isEqualTo("rechazada");
        // Sin datos personales: un valor rechazado (p. ej. un documento escrito en un filtro) no queda en la bitácora, ni en los filtros ni en
        // las advertencias ni en log_transacciones; solo la variable y cuántos valores traía
        n.get(q("gasto", "cum=80123456-7&disan=" + enc("PACIENTE SINTETICO 80123456")));
        Map<String, Object> rech = jdbc.sql("SELECT resultado, filtros::text AS filtros, array_to_string(advertencias, '|') AS adv FROM ana_consultas "
                + "WHERE usuario_id = :u ORDER BY id DESC LIMIT 1").param("u", id).query().singleRow();
        assertThat(rech.get("resultado")).isEqualTo("rechazada");
        assertThat(rech.get("filtros") + " " + rech.get("adv") + " " + String.join(" ", bitacoraDe(id))).doesNotContain("80123456")
                .doesNotContain("PACIENTE");
        // La bitácora de analítica la ven Coordinador y Administrador, no el Analista
        assertThat(n.get("/api/analitica/bitacora").estado()).isEqualTo(403);
        JsonNode bc = ok(nuevo("coortab", Perfil.COORDINADOR).get("/api/analitica/bitacora?usuario=" + usuario));
        assertThat(bc.path("total").asLong()).isEqualTo(4);
        assertThat(bc.path("siguiente").isNull()).isTrue();
    }
}
