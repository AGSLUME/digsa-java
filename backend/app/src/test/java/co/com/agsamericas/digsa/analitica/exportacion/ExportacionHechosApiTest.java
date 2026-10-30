package co.com.agsamericas.digsa.analitica.exportacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.com.agsamericas.digsa.analitica.GeneradorSintetico;
import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.reglas.AnaliticaExportacionHechos;
import co.com.agsamericas.digsa.reglas.Exportacion;
import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Exportación de los hechos de dispensación por periodo (A7.2, duda AN.14): un .csv.gz por mes con el formato de la exportación completa,
 * el paciente solo como seudónimo, SHA-256 por archivo, LEEME, trabajo diferido con progreso por mes, permisos (solo Administrador),
 * bitácora y caducidad. Insumo sintético propio en 1998 (ningún otro cargue de las pruebas usa ese año): enero con 3 dispensaciones,
 * febrero con 2, marzo sin datos y abril con 1; documentos y nombres ficticios. Contexto propio (base embebida propia; la propiedad
 * repite el valor por defecto): los 20 000 registros de 2025 de {@link #volumenNormal()} no deben verse en la base compartida de las
 * demás pruebas de integración (la homologación cuenta y homologa los datos originales de todos los cargues).
 */
@TestPropertySource(properties = {"digsa.analitica.exportacion-hechos.lote=5000"})
class ExportacionHechosApiTest extends PruebaIntegracion {

    static final int PARTE = 1024 * 1024;
    static final List<String> TERMINALES = List.of("aplicado", "rechazado", "fallido", "duplicado", "vencido", "descartado");
    /** Documentos del insumo (nunca deben salir en claro). */
    static final List<String> DOCUMENTOS = List.of("91800001", "91800002", "91800003", "91800004", "91800005", "91800006");
    /** Valor de cada dispensación por mes (1998). */
    static final Map<YearMonth, List<String>> VALORES = new LinkedHashMap<>();

    static {
        VALORES.put(YearMonth.of(1998, 1), List.of("1000,50", "2000,25", "300,00"));
        VALORES.put(YearMonth.of(1998, 2), List.of("450,75", "49,25"));
        VALORES.put(YearMonth.of(1998, 4), List.of("1234,56"));
    }

    private static volatile Long cargue;

    @Autowired
    GeneradorHechosMes generador;
    @Autowired
    ServicioExportacionHechos servicio;
    @Autowired
    Almacen almacen;

    @Autowired
    org.springframework.context.ApplicationContext contexto;

    @AfterEach
    void sinGancho() {
        generador.trasEncabezado = m -> { };
        generador.alEmpezar = m -> { };
        servicio.antesDeLeeme = () -> { };
        ReflectionTestUtils.setField(aplicador(), "trasInsertar", (Consumer<Long>) id -> { });
    }

    /** AplicadorHechos (paquete de la ingesta, no visible aquí): su gancho trasInsertar corre dentro de la transacción del cargue. */
    Object aplicador() {
        try {
            return contexto.getBean(Class.forName("co.com.agsamericas.digsa.analitica.ingesta.AplicadorHechos"));
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    /** Una dispensación sintética «;» (documento y nombre ficticios). */
    static String fila(String doc, LocalDate f, String fuerza, String esm, String valor, String formula) {
        return String.join(";", "CC", doc, "PACIENTE FICTICIO " + doc, "01/01/1960", fuerza, esm, "Dispensación",
                String.format("%02d/%02d/%04d", f.getDayOfMonth(), f.getMonthValue(), f.getYear()), "LOSARTAN 50 MG TABLETA", "20010037-2", "LOSARTAN",
                "C09CA01", "I10X", "1", valor, valor, "RM1", "Crónica", formula);
    }

    static byte[] csv(List<String> filas) {
        return ("﻿" + GeneradorSintetico.ENCABEZADO + "\r\n" + String.join("\r\n", filas) + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    // ---------- Apoyo ----------

    Navegador como(String prefijo, Perfil... perfiles) {
        Navegador n = navegador();
        assertThat(n.ingresar(crearUsuario(prefijo, perfiles), CLAVE).estado()).isEqualTo(200);
        return n;
    }

    static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** El insumo de 1998, cargado una vez por contexto como lo carga la pantalla «Cargues del OPL». */
    synchronized long cargue() {
        if (cargue != null && jdbc.sql("SELECT count(*) FROM ana_cargues WHERE id = :id AND estado = 'aplicado'").param("id", cargue).query(Long.class).single() == 1) {
            return cargue;
        }
        List<String> filas = new ArrayList<>();
        int i = 0;
        for (Map.Entry<YearMonth, List<String>> e : VALORES.entrySet()) {
            int dia = 3;
            for (String v : e.getValue()) {
                LocalDate f = e.getKey().atDay(dia++);
                String doc = DOCUMENTOS.get(i++);
                filas.add(String.join(";", "CC", doc, "PACIENTE FICTICIO " + doc, "01/01/1960", "EJC", "BAS02", "Dispensación",
                        String.format("%02d/%02d/%04d", f.getDayOfMonth(), f.getMonthValue(), f.getYear()), "LOSARTAN 50 MG TABLETA", "20010037-2",
                        "LOSARTAN", "C09CA01", "I10X", "1", v, v, "RM1", "Crónica", "RX" + i));
            }
        }
        byte[] datos = ("﻿" + GeneradorSintetico.ENCABEZADO + "\r\n" + String.join("\r\n", filas) + "\r\n").getBytes(StandardCharsets.UTF_8);
        cargue = cargar("hechos_1998.csv", datos);
        return cargue;
    }

    /** Sube por partes, confirma la propuesta de mapeo y espera a que quede aplicado. */
    long cargar(String nombre, byte[] datos) {
        Navegador n = como("anexphechos", Perfil.ANALISTA);
        long id = confirmar(n, nombre, datos, Map.of());
        esperarCargue(n, id);
        return id;
    }

    /** Sube, confirma el mapeo propuesto (con {@code extra}: modo y periodo declarado) y devuelve el id sin esperar. */
    long confirmar(Navegador n, String nombre, byte[] datos, Map<String, Object> extra) {
        int partes = (datos.length + PARTE - 1) / PARTE;
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("nombre", nombre);
        s.put("tamano", datos.length);
        s.put("sha256", Huellas.sha256(datos));
        s.put("partes", partes);
        Navegador.Respuesta c = n.post("/api/analitica/cargues", s);
        assertThat(c.estado()).as(c.texto()).isEqualTo(201);
        long id = c.json().path("id").asLong();
        for (int p = 0; p < partes; p++) {
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
        conf.putAll(extra);
        Navegador.Respuesta r = n.post("/api/analitica/cargues/" + id + "/mapeo", conf);
        assertThat(r.estado()).as(r.texto()).isEqualTo(202);
        return id;
    }

    static void esperarCargue(Navegador n, long id) {
        long limite = System.nanoTime() + 180_000_000_000L;
        while (true) {
            JsonNode e = n.get("/api/analitica/cargues/" + id).json();
            if (TERMINALES.contains(e.path("estado").asText())) {
                assertThat(e.path("estado").asText()).as(e.toString()).isEqualTo("aplicado");
                break;
            }
            assertThat(System.nanoTime()).as("el cargue no terminó").isLessThan(limite);
            dormir(200);
        }
    }

    static Navegador.Respuesta pedir(Navegador n, String desde, String hasta) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("desde", desde);
        s.put("hasta", hasta);
        return n.post("/api/analitica/exportaciones-hechos", s);
    }

    /** Espera (hasta 2 min) a que la exportación termine (lista o fallida). */
    static JsonNode esperar(Navegador n, long id) {
        long limite = System.nanoTime() + 120_000_000_000L;
        while (true) {
            Navegador.Respuesta r = n.get("/api/analitica/exportaciones-hechos/" + id);
            assertThat(r.estado()).as(r.texto()).isEqualTo(200);
            String e = r.json().path("estado").asText();
            if (e.equals("lista") || e.equals("fallida")) {
                return r.json();
            }
            assertThat(System.nanoTime()).as("la exportación no terminó: " + r.texto()).isLessThan(limite);
            dormir(100);
        }
    }

    static String gunzip(byte[] gz) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Filas y suma de valor_total de un mes en la base con el mismo corte (lo que la exportación debe traer). */
    record EnBase(long filas, BigDecimal valor) {
    }

    EnBase enBase(YearMonth mes, OffsetDateTime corte) {
        return jdbc.sql("""
                SELECT count(*) AS filas, coalesce(sum(valor_total), 0) AS valor FROM ana_dispensaciones
                WHERE fecha >= :d AND fecha < :h AND cargue_id IN (SELECT cargue_id FROM ana_cargues_visibles(:c))""")
                .param("d", mes.atDay(1)).param("h", mes.plusMonths(1).atDay(1)).param("c", corte).query(EnBase.class).single();
    }

    static BigDecimal suma(List<String> valores) {
        return valores.stream().map(v -> new BigDecimal(v.replace(',', '.'))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    long bitacora(String tipo, long usuarioId, String contiene) {
        return jdbc.sql("SELECT count(*) FROM log_transacciones WHERE tipo = :t AND usuario_id = :u AND descripcion LIKE :d")
                .param("t", tipo).param("u", usuarioId).param("d", "%" + contiene + "%").query(Long.class).single();
    }

    // ---------- Pruebas ----------

    @Test
    @DisplayName("rango de 4 meses: un .csv.gz por mes que cuadra con la base (filas y valor), seudónimo sin documento, SHA-256, mes vacío, LEEME y bitácora")
    void rangoCompleto() {
        long c = cargue();
        String admin = crearUsuario("adhechos", Perfil.ADMINISTRADOR);
        Navegador n = navegador();
        assertThat(n.ingresar(admin, CLAVE).estado()).isEqualTo(200);
        long inicio = System.nanoTime();
        Navegador.Respuesta r = pedir(n, "1998-01", "1998-04");
        assertThat(r.estado()).as(r.texto()).isEqualTo(202);
        long id = r.json().path("id").asLong();
        assertThat(r.json().path("meses").asInt()).isEqualTo(4);
        assertThat(r.json().path("archivos")).hasSize(4);
        JsonNode fin = esperar(n, id);
        long ms = Duration.ofNanos(System.nanoTime() - inicio).toMillis();
        assertThat(fin.path("estado").asText()).as(fin.toString()).isEqualTo("lista");
        assertThat(fin.path("mesesListos").asInt()).isEqualTo(4);
        assertThat(fin.path("leeme").asBoolean()).isTrue();
        assertThat(fin.path("vence").isNull()).isFalse();
        OffsetDateTime corte = OffsetDateTime.parse(fin.path("corte").asText());
        assertThat(fin.path("corteCargueId").asLong()).isGreaterThanOrEqualTo(c);
        String cargueSha = jdbc.sql("SELECT sha256 FROM ana_cargues WHERE id = :id").param("id", c).query(String.class).single();

        long filasTotal = 0;
        for (JsonNode a : fin.path("archivos")) {
            YearMonth mes = YearMonth.parse(a.path("mes").asText());
            assertThat(a.path("estado").asText()).isEqualTo("lista");
            Navegador.Respuesta d = n.get("/api/analitica/exportaciones-hechos/" + id + "/meses/" + mes);
            assertThat(d.estado()).as(d.texto()).isEqualTo(200);
            assertThat(d.cabecera("Content-Disposition")).contains(AnaliticaExportacionHechos.nombreArchivo(id, mes));
            assertThat(d.cabecera("Content-Type")).startsWith("application/gzip");
            // SHA-256 del archivo tal como se descarga
            assertThat(Huellas.sha256(d.cuerpo())).isEqualTo(a.path("sha256").asText());
            assertThat(a.path("tamano").asLong()).isEqualTo(d.cuerpo().length);
            String csv = gunzip(d.cuerpo());
            assertThat(csv).startsWith("﻿" + String.join(";", AnaliticaExportacionHechos.COLUMNAS) + "\r\n");
            List<String> lineas = Arrays.stream(csv.substring(1).split("\r\n")).filter(x -> !x.isEmpty()).toList();
            List<String[]> filas = lineas.subList(1, lineas.size()).stream().map(x -> x.split(";", -1)).toList();
            EnBase b = enBase(mes, corte);
            assertThat((long) filas.size()).as("filas de " + mes).isEqualTo(b.filas()).isEqualTo(a.path("filas").asLong());
            int iValor = AnaliticaExportacionHechos.COLUMNAS.indexOf("valor_total");
            BigDecimal valor = filas.stream().map(f -> new BigDecimal(f[iValor].replace(',', '.'))).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(valor).as("valor de " + mes).isEqualByComparingTo(b.valor()).isEqualByComparingTo(a.path("valorTotal").decimalValue());
            // Los del insumo de 1998 están todos, con su cargue, el SHA-256 del cargue, su línea y el seudónimo (64 hex)
            int iCargue = AnaliticaExportacionHechos.COLUMNAS.indexOf("cargue_id");
            List<String[]> propias = filas.stream().filter(f -> f[iCargue].equals(Long.toString(c))).toList();
            assertThat(propias).hasSize(VALORES.getOrDefault(mes, List.of()).size());
            BigDecimal esperado = suma(VALORES.getOrDefault(mes, List.of()));
            assertThat(propias.stream().map(f -> new BigDecimal(f[iValor].replace(',', '.'))).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo(esperado);
            for (String[] f : propias) {
                assertThat(f[AnaliticaExportacionHechos.COLUMNAS.indexOf("paciente_seudo")]).matches("[0-9a-f]{64}");
                assertThat(f[AnaliticaExportacionHechos.COLUMNAS.indexOf("cargue_sha256")]).isEqualTo(cargueSha.strip());
                assertThat(f[AnaliticaExportacionHechos.COLUMNAS.indexOf("linea_origen")]).isNotBlank();
                assertThat(f[AnaliticaExportacionHechos.COLUMNAS.indexOf("fecha")]).startsWith(mes.toString());
                assertThat(f[iValor]).contains(",").doesNotContain(".");
            }
            // Ni el documento ni el nombre en claro
            for (String doc : DOCUMENTOS) {
                assertThat(csv).doesNotContain(doc);
            }
            assertThat(csv).doesNotContain("PACIENTE FICTICIO");
            if (mes.equals(YearMonth.of(1998, 3))) {
                assertThat(filas).as("marzo no tiene datos: solo el encabezado").isEmpty();
            }
            filasTotal += filas.size();
        }
        assertThat(fin.path("filas").asLong()).isEqualTo(filasTotal);

        Navegador.Respuesta l = n.get("/api/analitica/exportaciones-hechos/" + id + "/leeme");
        assertThat(l.estado()).isEqualTo(200);
        String leeme = l.texto();
        assertThat(leeme).contains("Periodo: 1998-01 a 1998-04 (4 meses)", "1998-03 · lista · 0 · 0,00 · ", "Versión del esquema de la base: ",
                "último cargue visible: " + fin.path("corteCargueId").asLong(), "Total: 4 meses, " + filasTotal + " filas");
        for (JsonNode a : fin.path("archivos")) {
            assertThat(leeme).contains(a.path("sha256").asText());
        }
        assertThat(leeme).doesNotContain("desconocida");

        long admId = idDe(admin);
        assertThat(bitacora("analitica_hechos_exportacion_solicitada", admId, "Exportación de hechos " + id + " · 1998-01 a 1998-04")).isEqualTo(1);
        assertThat(bitacora("analitica_hechos_exportacion_generada", admId, "Exportación de hechos " + id + " ")).isEqualTo(1);
        assertThat(bitacora("analitica_hechos_exportacion_descarga", admId, "Exportación de hechos " + id + " ")).isEqualTo(4);
        // Medición con el volumen de las pruebas normales (.local/notas/mediciones.md, A7.2)
        System.out.println("[medicion A7.2] 4 meses, " + filasTotal + " filas: " + ms + " ms de la solicitud a lista; por mes (ms): "
                + fin.path("archivos").findValuesAsText("duracionMs"));
    }

    @Test
    @DisplayName("un solo mes sin «hasta»; la lista «Exportaciones de hechos» la trae con sus archivos; otra persona no la ve (404)")
    void unMesYLista() {
        cargue();
        Navegador n = como("adhechos1", Perfil.ADMINISTRADOR);
        Navegador.Respuesta r = pedir(n, "1998-02", null);
        assertThat(r.estado()).as(r.texto()).isEqualTo(202);
        long id = r.json().path("id").asLong();
        JsonNode fin = esperar(n, id);
        assertThat(fin.path("archivos")).hasSize(1);
        assertThat(fin.path("archivos").get(0).path("mes").asText()).isEqualTo("1998-02");
        JsonNode lista = n.get("/api/analitica/exportaciones-hechos").json();
        assertThat(lista.get(0).path("id").asLong()).isEqualTo(id);
        assertThat(lista.get(0).path("archivos")).hasSize(1);
        Navegador otro = como("adhechos2", Perfil.ADMINISTRADOR);
        assertThat(otro.get("/api/analitica/exportaciones-hechos/" + id).estado()).isEqualTo(404);
        assertThat(otro.get("/api/analitica/exportaciones-hechos/" + id + "/meses/1998-02").estado()).isEqualTo(404);
        assertThat(otro.get("/api/analitica/exportaciones-hechos/" + id + "/leeme").estado()).isEqualTo(404);
        assertThat(otro.get("/api/analitica/exportaciones-hechos").json()).as("la lista del otro no la trae").noneMatch(x -> x.path("id").asLong() == id);
    }

    @Test
    @DisplayName("permisos: Analista y Coordinador (tienen «Cargues del OPL») y DIGSA reciben 403 al pedir, listar y descargar")
    void soloAdministrador() {
        for (Perfil p : List.of(Perfil.ANALISTA, Perfil.COORDINADOR, Perfil.DIGSA)) {
            Navegador n = como("nohechos", p);
            assertThat(pedir(n, "1998-01", "1998-01").estado()).as(p.name()).isEqualTo(403);
            assertThat(n.get("/api/analitica/exportaciones-hechos").estado()).as(p.name()).isEqualTo(403);
            assertThat(n.get("/api/analitica/exportaciones-hechos/1/meses/1998-01").estado()).as(p.name()).isEqualTo(403);
            assertThat(n.get("/api/analitica/exportaciones-hechos/1/leeme").estado()).as(p.name()).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("validación: mes con formato, no futuro, en orden y con tope; cada 422 trae su campo")
    void validacion() {
        Navegador n = como("adhechosv", Perfil.ADMINISTRADOR);
        Navegador.Respuesta r = pedir(n, "1998/01", null);
        assertThat(r.estado()).isEqualTo(422);
        assertThat(r.json().path("campo").asText()).isEqualTo("desde");
        r = pedir(n, "1998-04", "1998-01");
        assertThat(r.estado()).isEqualTo(422);
        assertThat(r.json().path("campo").asText()).isEqualTo("hasta");
        r = pedir(n, "1998-01", YearMonth.now().plusMonths(2).toString());
        assertThat(r.estado()).isEqualTo(422);
        assertThat(r.json().path("campo").asText()).isEqualTo("hasta");
        r = pedir(n, "1990-01", "1998-01");
        assertThat(r.estado()).as("más de 60 meses").isEqualTo(422);
        assertThat(r.mensaje()).contains("60 meses");
    }

    @Test
    @DisplayName("un mes que falla queda «fallido» sin archivo a medias; los demás se descargan; el trabajo queda «fallida» y lo dice el LEEME")
    void mesFallido() throws Exception {
        cargue();
        generador.trasEncabezado = m -> {
            if (m.equals(YearMonth.of(1998, 2))) {
                throw new IllegalStateException("falla simulada");
            }
        };
        Navegador n = como("adhechosf", Perfil.ADMINISTRADOR);
        long id = pedir(n, "1998-01", "1998-02").json().path("id").asLong();
        JsonNode fin = esperar(n, id);
        assertThat(fin.path("estado").asText()).isEqualTo("fallida");
        assertThat(fin.path("mesesListos").asInt()).isEqualTo(1);
        assertThat(fin.path("mesesFallidos").asInt()).isEqualTo(1);
        assertThat(fin.path("archivos").get(1).path("estado").asText()).isEqualTo("fallida");
        assertThat(fin.path("archivos").get(1).path("error").asText()).isEqualTo(AnaliticaExportacionHechos.MENSAJE_FALLA);
        assertThat(jdbc.sql("SELECT ruta FROM ana_exportaciones_hechos_meses WHERE exportacion_id = :id AND mes = '1998-02-01'").param("id", id)
                .query(String.class).list()).containsExactly((String) null);
        assertThat(n.get("/api/analitica/exportaciones-hechos/" + id + "/meses/1998-01").estado()).isEqualTo(200);
        assertThat(n.get("/api/analitica/exportaciones-hechos/" + id + "/meses/1998-02").estado()).isEqualTo(409);
        assertThat(n.get("/api/analitica/exportaciones-hechos/" + id + "/leeme").texto()).contains("1998-02 · fallida · sin archivo");
        long usuario = jdbc.sql("SELECT solicitada_por FROM ana_exportaciones_hechos WHERE id = :id").param("id", id).query(Long.class).single();
        assertThat(bitacora("analitica_hechos_exportacion_fallida", usuario, "Exportación de hechos " + id + " ")).isEqualTo(1);
    }

    @Test
    @DisplayName("caducidad: al vencer se borran los archivos y el LEEME, la descarga responde 410 y queda en la bitácora")
    void caducidad() {
        cargue();
        String admin = crearUsuario("adhechosc", Perfil.ADMINISTRADOR);
        Navegador n = navegador();
        assertThat(n.ingresar(admin, CLAVE).estado()).isEqualTo(200);
        long id = pedir(n, "1998-04", "1998-04").json().path("id").asLong();
        assertThat(esperar(n, id).path("estado").asText()).isEqualTo("lista");
        String ruta = jdbc.sql("SELECT ruta FROM ana_exportaciones_hechos_meses WHERE exportacion_id = :id").param("id", id).query(String.class).single();
        String leeme = jdbc.sql("SELECT leeme_ruta FROM ana_exportaciones_hechos WHERE id = :id").param("id", id).query(String.class).single();
        reloj.adelantar(Duration.ofHours(25));
        n = navegador(); // la sesión anterior venció por inactividad
        assertThat(n.ingresar(admin, CLAVE).estado()).isEqualTo(200);
        assertThat(n.get("/api/analitica/exportaciones-hechos/" + id + "/meses/1998-04").estado()).as("vencida aunque la limpieza no haya pasado").isEqualTo(410);
        assertThat(servicio.limpiar()).isGreaterThanOrEqualTo(1);
        assertThat(n.get("/api/analitica/exportaciones-hechos/" + id).json().path("estado").asText()).isEqualTo("vencida");
        assertThat(n.get("/api/analitica/exportaciones-hechos/" + id + "/meses/1998-04").estado()).isEqualTo(410);
        assertThat(n.get("/api/analitica/exportaciones-hechos/" + id + "/leeme").estado()).isEqualTo(410);
        assertThatThrownBy(() -> almacen.leer(ruta).close()).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> almacen.leer(leeme).close()).isInstanceOf(IOException.class);
        assertThat(jdbc.sql("SELECT estado FROM ana_exportaciones_hechos_meses WHERE exportacion_id = :id").param("id", id).query(String.class).single())
                .isEqualTo("vencida");
        assertThat(bitacora("analitica_hechos_exportacion_vencida", idDe(admin), "Exportación de hechos " + id)).isEqualTo(1);
    }

    @Test
    @DisplayName("volumen de las pruebas normales (20 000 registros sintéticos de 2025): 12 archivos que cuadran mes a mes con la base; se mide el tiempo")
    void volumenNormal() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("digsa-hechos-");
        try {
            GeneradorSintetico.Resultado g = GeneradorSintetico.generar(dir, "sintetico_a72", 20_000, 7202L);
            cargar("sintetico_a72.csv", java.nio.file.Files.readAllBytes(g.csv()));
        } finally {
            try (var archivos = java.nio.file.Files.walk(dir)) {
                archivos.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Navegador n = como("adhechosm", Perfil.ADMINISTRADOR);
        long inicio = System.nanoTime();
        long id = pedir(n, "2025-01", "2025-12").json().path("id").asLong();
        JsonNode fin = esperar(n, id);
        long ms = Duration.ofNanos(System.nanoTime() - inicio).toMillis();
        assertThat(fin.path("estado").asText()).as(fin.toString()).isEqualTo("lista");
        OffsetDateTime corte = OffsetDateTime.parse(fin.path("corte").asText());
        long total = 0;
        StringBuilder porMes = new StringBuilder();
        for (JsonNode a : fin.path("archivos")) {
            YearMonth mes = YearMonth.parse(a.path("mes").asText());
            EnBase b = enBase(mes, corte);
            assertThat(a.path("filas").asLong()).as("filas de " + mes).isEqualTo(b.filas());
            assertThat(a.path("valorTotal").decimalValue()).as("valor de " + mes).isEqualByComparingTo(b.valor());
            total += b.filas();
            porMes.append(mes).append('=').append(a.path("filas").asLong()).append(" filas/").append(a.path("duracionMs").asLong()).append(" ms/")
                    .append(a.path("tamano").asLong()).append(" B; ");
        }
        assertThat(total).isGreaterThan(15_000);
        // Un mes completo se vuelve a descargar y se cuenta (el archivo es el que se midió)
        Navegador.Respuesta d = n.get("/api/analitica/exportaciones-hechos/" + id + "/meses/2025-06");
        assertThat(Huellas.sha256(d.cuerpo())).isEqualTo(fin.path("archivos").get(5).path("sha256").asText());
        System.out.println("[medicion A7.2] 12 meses, " + total + " filas, " + fin.path("tamano").asLong() + " B: " + ms + " ms de la solicitud a lista; " + porMes);
    }

    @Test
    @DisplayName("P1: un cargue que reemplaza el periodo y se confirma mientras la exportación corre no entra en ningún mes; todos usan la lista congelada")
    void cargueDuranteLaExportacion() throws Exception {
        long sello = System.nanoTime() % 100_000;
        // A: mayo y junio de 1997 (ninguna otra prueba usa ese año)
        long a = cargar("hechos_1997_a_" + sello + ".csv", csv(List.of(
                fila("9705" + sello + "1", LocalDate.of(1997, 5, 10), "EJC", "BAS02", "100,00", "PA" + sello + "1"),
                fila("9706" + sello + "1", LocalDate.of(1997, 6, 10), "EJC", "BAS02", "200,00", "PA" + sello + "2"))));
        // B reemplaza mayo y junio; su transacción se detiene dentro de la carga de hechos (aplicado_en ya fijado, sin confirmar)
        CountDownLatch dentro = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        ReflectionTestUtils.setField(aplicador(), "trasInsertar", (Consumer<Long>) x -> {
            dentro.countDown();
            try {
                soltar.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Navegador analista = como("anexphechosb", Perfil.ANALISTA);
        Map<String, Object> reemplazo = new LinkedHashMap<>();
        reemplazo.put("modo", "reemplazar");
        reemplazo.put("periodoDesde", "1997-05-01");
        reemplazo.put("periodoHasta", "1997-06-30");
        long[] bId = {0};
        try {
            bId[0] = confirmar(analista, "hechos_1997_b_" + sello + ".csv", csv(List.of(
                    fila("9705" + sello + "2", LocalDate.of(1997, 5, 11), "EJC", "BAS02", "300,00", "PB" + sello + "1"),
                    fila("9706" + sello + "2", LocalDate.of(1997, 6, 11), "EJC", "BAS02", "400,00", "PB" + sello + "2"))), reemplazo);
            assertThat(dentro.await(120, TimeUnit.SECONDS)).as("B llegó a la carga de hechos").isTrue();
        } catch (RuntimeException | Error e) {
            soltar.countDown();
            throw e;
        }
        long b = bId[0];
        // Junio se lee DESPUÉS de que B confirma: antes de la corrección ese mes traía B (su aplicado_en es anterior a la solicitud)
        generador.alEmpezar = m -> {
            if (m.equals(YearMonth.of(1997, 6))) {
                soltar.countDown();
                long limite = System.nanoTime() + 120_000_000_000L;
                while (jdbc.sql("SELECT estado FROM ana_cargues WHERE id = :id").param("id", b).query(String.class).single().equals("procesando")) {
                    if (System.nanoTime() > limite) {
                        throw new AssertionError("B no terminó");
                    }
                    dormir(100);
                }
            }
        };
        long id;
        JsonNode fin;
        Navegador adm = como("adhechosp1", Perfil.ADMINISTRADOR);
        try {
            Navegador.Respuesta r = pedir(adm, "1997-05", "1997-06");
            assertThat(r.estado()).as(r.texto()).isEqualTo(202);
            id = r.json().path("id").asLong();
            fin = esperar(adm, id);
        } finally {
            soltar.countDown();
        }
        esperarCargue(analista, b);
        assertThat(fin.path("estado").asText()).as(fin.toString()).isEqualTo("lista");
        List<Long> congelados = jdbc.sql("SELECT unnest(cargues) FROM ana_exportaciones_hechos WHERE id = :id").param("id", id).query(Long.class).list();
        assertThat(congelados).contains(a).doesNotContain(b);
        assertThat(fin.path("corteCargueId").asLong()).isNotEqualTo(b);
        int iCargue = AnaliticaExportacionHechos.COLUMNAS.indexOf("cargue_id");
        for (String mes : List.of("1997-05", "1997-06")) {
            String texto = gunzip(adm.get("/api/analitica/exportaciones-hechos/" + id + "/meses/" + mes).cuerpo());
            List<String> cargues = Arrays.stream(texto.split("\r\n")).skip(1).filter(x -> !x.isBlank()).map(x -> x.split(";", -1)[iCargue]).toList();
            assertThat(cargues).as(mes).contains(Long.toString(a)).doesNotContain(Long.toString(b));
        }
        assertThat(adm.get("/api/analitica/exportaciones-hechos/" + id + "/leeme").texto()).contains("Cargues incluidos (" + congelados.size() + ")");
    }

    @Test
    @DisplayName("P3: dos solicitudes simultáneas del mismo administrador: una 202 y la otra 409; mientras corre, otra más también 409")
    void solicitudesSimultaneas() throws Exception {
        cargue();
        CountDownLatch soltar = new CountDownLatch(1);
        generador.alEmpezar = m -> {
            try {
                soltar.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        String admin = crearUsuario("adhechoss", Perfil.ADMINISTRADOR);
        Navegador n = navegador();
        assertThat(n.ingresar(admin, CLAVE).estado()).isEqualTo(200);
        try {
            CompletableFuture<Navegador.Respuesta> uno = CompletableFuture.supplyAsync(() -> pedir(n, "1998-01", null));
            CompletableFuture<Navegador.Respuesta> dos = CompletableFuture.supplyAsync(() -> pedir(n, "1998-02", null));
            List<Integer> estados = new ArrayList<>(List.of(uno.get(60, TimeUnit.SECONDS).estado(), dos.get(60, TimeUnit.SECONDS).estado()));
            estados.sort(null);
            assertThat(estados).containsExactly(202, 409);
            Navegador.Respuesta tres = pedir(n, "1998-04", null);
            assertThat(tres.estado()).isEqualTo(409);
            assertThat(tres.mensaje()).contains("en curso");
        } finally {
            soltar.countDown();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM ana_exportaciones_hechos WHERE solicitada_por = :u").param("u", idDe(admin)).query(Long.class).single())
                .isEqualTo(1);
        esperar(n, n.get("/api/analitica/exportaciones-hechos").json().get(0).path("id").asLong());
    }

    @Test
    @DisplayName("P3: reanudación tras una caída: el mes a medias se borra y se rehace igual (mismo SHA-256), el listo se conserva, mismo corte y cargues")
    void reanudacion() throws Exception {
        cargue();
        Navegador n = como("adhechosr", Perfil.ADMINISTRADOR);
        long id = pedir(n, "1998-01", "1998-02").json().path("id").asLong();
        assertThat(esperar(n, id).path("estado").asText()).isEqualTo("lista");
        String sqlMes = "SELECT ruta, sha256, filas, tamano FROM ana_exportaciones_hechos_meses WHERE exportacion_id = :id AND mes = CAST(:m AS date)";
        MesBd enero = jdbc.sql(sqlMes).param("id", id).param("m", "1998-01-01").query(MesBd.class).single();
        MesBd febrero = jdbc.sql(sqlMes).param("id", id).param("m", "1998-02-01").query(MesBd.class).single();
        String corte = jdbc.sql("SELECT corte::text || cargues::text FROM ana_exportaciones_hechos WHERE id = :id").param("id", id).query(String.class).single();
        // Estado de un proceso que se cayó escribiendo febrero: archivo a medias, mes «generando», trabajo «generando» sin LEEME
        String medio = "analitica/hechos/" + id + "_1998-02_amedias_csv.gz";
        almacen.guardar(medio, new byte[] {31, -117, 8});
        almacen.descartar(febrero.ruta());
        almacen.descartar(jdbc.sql("SELECT leeme_ruta FROM ana_exportaciones_hechos WHERE id = :id").param("id", id).query(String.class).single());
        jdbc.sql("""
                UPDATE ana_exportaciones_hechos_meses SET estado = 'generando', ruta = :r, filas = NULL, valor_total = NULL, tamano = NULL, sha256 = NULL,
                       terminado = NULL WHERE exportacion_id = :id AND mes = '1998-02-01'""").param("r", medio).param("id", id).update();
        jdbc.sql("""
                UPDATE ana_exportaciones_hechos SET estado = 'generando', meses_listos = 1, filas = :f, tamano = :t, leeme_ruta = NULL, terminado = NULL,
                       vence = NULL WHERE id = :id""").param("f", enero.filas()).param("t", enero.tamano()).param("id", id).update();
        servicio.correr(); // como al reiniciar (si el ejecutor de fondo tiene el candado, lo hace él)
        servicio.despertar();
        JsonNode despues = esperar(n, id);
        assertThat(despues.path("estado").asText()).isEqualTo("lista");
        assertThatThrownBy(() -> almacen.leer(medio).close()).as("el archivo a medias se borró").isInstanceOf(IOException.class);
        MesBd enero2 = jdbc.sql(sqlMes).param("id", id).param("m", "1998-01-01").query(MesBd.class).single();
        MesBd febrero2 = jdbc.sql(sqlMes).param("id", id).param("m", "1998-02-01").query(MesBd.class).single();
        assertThat(enero2).as("enero se conserva tal cual").isEqualTo(enero);
        assertThat(febrero2.sha256()).as("febrero rehecho con la misma lista de cargues: mismo contenido").isEqualTo(febrero.sha256());
        assertThat(febrero2.ruta()).isNotEqualTo(medio);
        assertThat(despues.path("mesesListos").asInt()).isEqualTo(2);
        assertThat(despues.path("filas").asLong()).isEqualTo(enero.filas() + febrero.filas());
        assertThat(jdbc.sql("SELECT corte::text || cargues::text FROM ana_exportaciones_hechos WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo(corte);
        assertThat(Huellas.sha256(n.get("/api/analitica/exportaciones-hechos/" + id + "/meses/1998-02").cuerpo())).isEqualTo(febrero.sha256());
    }

    record MesBd(String ruta, String sha256, long filas, long tamano) {
    }

    @Test
    @DisplayName("P3: si el LEEME no se puede guardar, el trabajo queda pendiente con su mensaje y la revisión lo reintenta hasta «lista»")
    void leemeSeReintenta() {
        cargue();
        servicio.antesDeLeeme = () -> {
            throw new IllegalStateException("almacén no disponible (simulado)");
        };
        Navegador n = como("adhechosl", Perfil.ADMINISTRADOR);
        long id = pedir(n, "1998-04", null).json().path("id").asLong();
        esperar(() -> ServicioExportacionHechos.MENSAJE_FALLA_LEEME.equals(n.get("/api/analitica/exportaciones-hechos/" + id).json().path("error").asText()),
                "el LEEME falla y queda para reintentar");
        JsonNode e = n.get("/api/analitica/exportaciones-hechos/" + id).json();
        assertThat(e.path("estado").asText()).isEqualTo("pendiente");
        assertThat(e.path("mesesListos").asInt()).isEqualTo(1);
        assertThat(e.path("leeme").asBoolean()).isFalse();
        servicio.antesDeLeeme = () -> { };
        servicio.revisar();
        JsonNode fin = esperar(n, id);
        assertThat(fin.path("estado").asText()).isEqualTo("lista");
        assertThat(fin.path("error").isNull()).isTrue();
        assertThat(n.get("/api/analitica/exportaciones-hechos/" + id + "/leeme").texto()).contains("1998-04 · lista · 1 · ");
    }

    @Test
    @DisplayName("P2: limpieza idempotente: con la exportación ya «vencida» y el mes aún «lista» (caída entre pasos), borra el archivo y el LEEME")
    void limpiezaTrasCaida() {
        cargue();
        Navegador n = como("adhechosk", Perfil.ADMINISTRADOR);
        long id = pedir(n, "1998-01", null).json().path("id").asLong();
        assertThat(esperar(n, id).path("estado").asText()).isEqualTo("lista");
        String ruta = jdbc.sql("SELECT ruta FROM ana_exportaciones_hechos_meses WHERE exportacion_id = :id").param("id", id).query(String.class).single();
        String leeme = jdbc.sql("SELECT leeme_ruta FROM ana_exportaciones_hechos WHERE id = :id").param("id", id).query(String.class).single();
        jdbc.sql("UPDATE ana_exportaciones_hechos SET estado = 'vencida' WHERE id = :id").param("id", id).update();
        servicio.limpiar();
        assertThatThrownBy(() -> almacen.leer(ruta).close()).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> almacen.leer(leeme).close()).isInstanceOf(IOException.class);
        assertThat(jdbc.sql("SELECT estado || '/' || coalesce(ruta, '-') FROM ana_exportaciones_hechos_meses WHERE exportacion_id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("vencida/-");
        assertThat(jdbc.sql("SELECT leeme_ruta FROM ana_exportaciones_hechos WHERE id = :id").param("id", id).query(String.class).list())
                .containsExactly((String) null);
    }

    @Test
    @DisplayName("P2: si el borrado falla, la ruta se conserva (el archivo sigue referenciado) y la siguiente revisión lo borra")
    void borradoFallidoSeReintenta() throws Exception {
        cargue();
        Navegador n = como("adhechosb2", Perfil.ADMINISTRADOR);
        long id = pedir(n, "1998-01", null).json().path("id").asLong();
        assertThat(esperar(n, id).path("estado").asText()).isEqualTo("lista");
        String real = jdbc.sql("SELECT ruta FROM ana_exportaciones_hechos_meses WHERE exportacion_id = :id").param("id", id).query(String.class).single();
        // Un «archivo» que el almacén no puede borrar: una carpeta con algo dentro en la ruta del mes
        String ruta = "analitica/hechos/" + id + "_bloqueado_csv.gz";
        java.nio.file.Path raiz = (java.nio.file.Path) ReflectionTestUtils.getField(almacen, "raiz");
        java.nio.file.Path carpeta = raiz.resolve(ruta);
        java.nio.file.Path dentro = carpeta.resolve("dentro");
        java.nio.file.Files.createDirectories(carpeta);
        java.nio.file.Files.writeString(dentro, "x");
        try {
            jdbc.sql("UPDATE ana_exportaciones_hechos_meses SET ruta = :r WHERE exportacion_id = :id").param("r", ruta).param("id", id).update();
            almacen.descartar(real);
            reloj.adelantar(Duration.ofHours(25));
            assertThat(servicio.limpiar()).isGreaterThanOrEqualTo(1);
            assertThat(jdbc.sql("SELECT ruta FROM ana_exportaciones_hechos_meses WHERE exportacion_id = :id").param("id", id).query(String.class).single())
                    .as("no se pudo borrar: la ruta sigue en la base").isEqualTo(ruta);
            assertThat(jdbc.sql("SELECT estado FROM ana_exportaciones_hechos WHERE id = :id").param("id", id).query(String.class).single()).isEqualTo("vencida");
            java.nio.file.Files.delete(dentro);
            servicio.limpiar();
            assertThat(java.nio.file.Files.exists(carpeta)).isFalse();
            assertThat(jdbc.sql("SELECT estado || '/' || coalesce(ruta, '-') FROM ana_exportaciones_hechos_meses WHERE exportacion_id = :id").param("id", id)
                    .query(String.class).single()).isEqualTo("vencida/-");
        } finally {
            java.nio.file.Files.deleteIfExists(dentro);
            java.nio.file.Files.deleteIfExists(carpeta);
        }
    }

    @Test
    @DisplayName("P3: el panel de «Cargues del OPL» se muestra con la misma regla que autoriza la exportación (exportaHechos)")
    void banderaDelPanel() {
        assertThat(como("adhechosf2", Perfil.ADMINISTRADOR).get("/api/analitica/cargues").json().path("exportaHechos").asBoolean()).isTrue();
        assertThat(como("anhechosf2", Perfil.ANALISTA).get("/api/analitica/cargues").json().path("exportaHechos").asBoolean()).isFalse();
        assertThat(como("cohechosf2", Perfil.COORDINADOR).get("/api/analitica/cargues").json().path("exportaHechos").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("la exportación completa sigue sin ana_dispensaciones ni ana_llaves y lleva las tablas de las exportaciones de hechos; su LEEME dice dónde están")
    void completaSinHechos() {
        assertThat(Exportacion.TECNICAS).contains("ana_dispensaciones", "ana_llaves");
        assertThat(Exportacion.TABLAS).doesNotContain("ana_dispensaciones", "ana_llaves")
                .contains("ana_exportaciones_hechos", "ana_exportaciones_hechos_meses");
        String leeme = Exportacion.leeme(new Exportacion.Leeme("Admin (admin)", OffsetDateTime.now(), List.of(), 0, 0, 0));
        assertThat(leeme).contains("exportación de hechos por periodo", "Cargues del OPL");
    }

    @Test
    @DisplayName("toda columna de ana_dispensaciones está decidida (exportada o fuera); una nueva sin decidir haría fallar la exportación")
    void columnasDecididas() throws Exception {
        try (java.sql.Connection c = ((javax.sql.DataSource) ds).getConnection()) {
            GeneradorHechosMes.verificarColumnas(c);
        }
    }

    @Autowired
    javax.sql.DataSource ds;
}
