package co.com.agsamericas.digsa.analitica.tableros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.com.agsamericas.digsa.analitica.GeneradorSintetico;
import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.reglas.AnaliticaDescargaDiferida;
import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Descarga diferida del CSV de registros de origen (A7.3, duda AN.16; analitica-tableros «Descarga diferida cuando no cabe en la
 * petición», escenario «Descarga grande»). Contexto propio (base embebida propia) con el umbral bajado a 5 registros, así el diferido se
 * prueba sin cargar 100 000 filas, y el tope de la traza en diferido en 15 000. Las descargas de los tableros siguen en la petición aunque
 * pasen del umbral (son agregadas). Insumo sintético propio de 1996 (8 dispensaciones: 6 en enero y 2 en febrero, documentos y nombres
 * ficticios) y, para la medición y el tope, los 20 000 registros sintéticos de 2025 del generador.
 */
@TestPropertySource(properties = {"digsa.analitica.descargas-diferidas.umbral-filas=5", "digsa.analitica.descargas-diferidas.max-registros-traza=15000"})
class AnaliticaDescargasApiTest extends PruebaIntegracion {

    static final int PARTE = 1024 * 1024;
    static final List<String> TERMINALES = List.of("aplicado", "rechazado", "fallido", "duplicado", "vencido", "descartado");
    static final List<String> DOCUMENTOS = List.of("91600001", "91600002", "91600003", "91600004", "91600005", "91600006", "91600007", "91600008");
    static final String PERIODO = "desde=1996-01-01&hasta=1996-12-31";
    static final String NOMBRE = "^registros_origen_general_\\d{8}_\\d{4}\\.csv$";

    private static volatile Long cargue;
    private static volatile Long cargueVolumen;

    @Autowired
    ServicioDescargaAnalitica servicio;
    @Autowired
    Almacen almacen;

    @AfterEach
    void sinGancho() {
        servicio.alEmpezar = id -> { };
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

    static String fila(String doc, LocalDate f, String formula) {
        String valor = "1000,50";
        return String.join(";", "CC", doc, "PACIENTE FICTICIO " + doc, "01/01/1960", "EJC", "BAS02", "Dispensación",
                String.format("%02d/%02d/%04d", f.getDayOfMonth(), f.getMonthValue(), f.getYear()), "LOSARTAN 50 MG TABLETA", "20010037-2",
                "LOSARTAN", "C09CA01", "I10X", "1", valor, valor, "RM1", "Crónica", formula);
    }

    static byte[] csv(List<String> filas) {
        return ("﻿" + GeneradorSintetico.ENCABEZADO + "\r\n" + String.join("\r\n", filas) + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    /** El insumo de 1996, cargado una vez por contexto como lo carga la pantalla «Cargues del OPL». */
    synchronized long cargue() {
        if (cargue != null) {
            return cargue;
        }
        List<String> filas = new ArrayList<>();
        for (int i = 0; i < DOCUMENTOS.size(); i++) {
            LocalDate f = i < 6 ? LocalDate.of(1996, 1, 3 + i) : LocalDate.of(1996, 2, 3 + i);
            filas.add(fila(DOCUMENTOS.get(i), f, "RX96" + i));
        }
        cargue = cargar("descargas_1996.csv", csv(filas));
        return cargue;
    }

    synchronized long cargueVolumen() throws IOException {
        if (cargueVolumen != null) {
            return cargueVolumen;
        }
        Path dir = Files.createTempDirectory("digsa-descargas-");
        try {
            GeneradorSintetico.Resultado g = GeneradorSintetico.generar(dir, "sintetico_a73", 20_000, 7303L);
            cargueVolumen = cargar("sintetico_a73.csv", Files.readAllBytes(g.csv()));
        } finally {
            try (var archivos = Files.walk(dir)) {
                archivos.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        return cargueVolumen;
    }

    /** Sube por partes, confirma la propuesta de mapeo y espera a que quede aplicado. */
    long cargar(String nombre, byte[] datos) {
        Navegador n = como("anadifcar", Perfil.ANALISTA);
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
        Navegador.Respuesta r = n.post("/api/analitica/cargues/" + id + "/mapeo", conf);
        assertThat(r.estado()).as(r.texto()).isEqualTo(202);
        long limite = System.nanoTime() + 300_000_000_000L;
        while (true) {
            JsonNode e = n.get("/api/analitica/cargues/" + id).json();
            if (TERMINALES.contains(e.path("estado").asText())) {
                assertThat(e.path("estado").asText()).as(e.toString()).isEqualTo("aplicado");
                return id;
            }
            assertThat(System.nanoTime()).as("el cargue no terminó").isLessThan(limite);
            dormir(200);
        }
    }

    /** CSV de registros de origen de la celda «enero de 1996» (6 registros: pasa del umbral de 5) con el corte del insumo. */
    String trazaEnero() {
        return "/api/analitica/traza/general/registros.csv?" + PERIODO + "&corte=" + cargue() + "&periodo=1996-01";
    }

    /** Espera (hasta 2 min) a que la descarga diferida termine (lista o fallida). */
    static JsonNode esperar(Navegador n, long id) {
        long limite = System.nanoTime() + 120_000_000_000L;
        while (true) {
            Navegador.Respuesta r = n.get("/api/analitica/descargas-diferidas/" + id);
            assertThat(r.estado()).as(r.texto()).isEqualTo(200);
            String e = r.json().path("estado").asText();
            if (e.equals("lista") || e.equals("fallida")) {
                return r.json();
            }
            assertThat(System.nanoTime()).as("la descarga no terminó: " + r.texto()).isLessThan(limite);
            dormir(100);
        }
    }

    long bitacora(String tipo, long usuarioId, String contiene) {
        return jdbc.sql("SELECT count(*) FROM log_transacciones WHERE tipo = :t AND usuario_id = :u AND descripcion LIKE :d")
                .param("t", tipo).param("u", usuarioId).param("d", "%" + contiene + "%").query(Long.class).single();
    }

    long consultas(long usuarioId, String resultado, long id) {
        return jdbc.sql("SELECT count(*) FROM ana_consultas WHERE usuario_id = :u AND accion = 'traza' AND resultado = :r AND objeto LIKE :o")
                .param("u", usuarioId).param("r", resultado).param("o", "%diferida " + id + "%").query(Long.class).single();
    }

    long usuarioDe(long id) {
        return jdbc.sql("SELECT solicitada_por FROM ana_descargas_diferidas WHERE id = :id").param("id", id).query(Long.class).single();
    }

    /** Una descarga diferida escrita directamente en la base (para las pruebas de la cola). */
    long insertar(long usuarioId, String estado, int intentos, String ruta, OffsetDateTime creado) {
        return jdbc.sql("""
                INSERT INTO ana_descargas_diferidas (solicitada_por, usuario_texto, perfil, alcance, tablero, parametros, corte_cargue_id, registros,
                                                     estado, intentos, ruta, creado, iniciado)
                VALUES (:u, 'Persona de prueba (prueba)', 'Analista', 'nacional', 'general',
                        CAST('{"desde":["1996-01-01"],"hasta":["1996-12-31"],"periodo":["1996-01"]}' AS jsonb), :c, 6, :e, :i, :r, :creado, :creado)
                RETURNING id""").param("u", usuarioId).param("c", cargue()).param("e", estado).param("i", intentos).param("r", ruta)
                .param("creado", creado).query(Long.class).single();
    }

    // ---------- Pruebas ----------

    @Test
    @DisplayName("Descarga grande: el CSV de registros de origen pasa del umbral ⇒ 202 con el id; queda «lista» en «Mis descargas del día» completo (FIN), con SHA-256, la huella del rastreo y su fila «ok» en la bitácora")
    void diferida() {
        long c = cargue();
        Navegador n = como("anadif", Perfil.ANALISTA);
        long inicio = System.nanoTime();
        // «texto» y «variable» son texto libre: no se guardan con la descarga
        Navegador.Respuesta r = n.get(trazaEnero() + "&texto=libre");
        assertThat(r.estado()).as(r.texto()).isEqualTo(202);
        assertThat(r.cabecera("Content-Type")).contains("application/json");
        long id = r.json().path("id").asLong();
        assertThat(r.json().path("registros").asLong()).isEqualTo(6);
        assertThat(r.json().path("corteCargueId").asLong()).isEqualTo(c);
        assertThat(r.json().path("mensaje").asText()).contains("Mis descargas del día");
        assertThat(jdbc.sql("SELECT parametros::text FROM ana_descargas_diferidas WHERE id = :id").param("id", id).query(String.class).single())
                .contains("periodo").doesNotContain("texto").doesNotContain("corte").doesNotContain("libre");
        JsonNode fin = esperar(n, id);
        long ms = Duration.ofNanos(System.nanoTime() - inicio).toMillis();
        assertThat(fin.path("estado").asText()).as(fin.toString()).isEqualTo("lista");
        assertThat(Duration.ofMillis(ms)).as("menos de 10 minutos").isLessThan(Duration.ofMinutes(10));
        assertThat(fin.path("nombre").asText()).matches(NOMBRE);
        assertThat(n.get("/api/analitica/descargas-diferidas").json()).anySatisfy(x -> assertThat(x.path("id").asLong()).isEqualTo(id));
        Navegador.Respuesta a = n.get("/api/analitica/descargas-diferidas/" + id + "/archivo");
        assertThat(a.estado()).as(a.texto()).isEqualTo(200);
        assertThat(a.cabecera("Content-Disposition")).contains(fin.path("nombre").asText());
        assertThat(Huellas.sha256(a.cuerpo())).isEqualTo(fin.path("sha256").asText()).isEqualTo(a.cabecera("X-Sha256"));
        List<String> lineas = a.texto().lines().toList();
        assertThat(lineas.getLast()).isEqualTo("FIN;6 registros");
        assertThat(a.texto()).contains("Descarga diferida " + id).contains("Registros procesados;6").doesNotContain(ServicioTrazaAnalitica.INCOMPLETO);
        DOCUMENTOS.forEach(d -> assertThat(a.texto()).doesNotContain(d));
        // La misma huella que el rastreo de la cifra con el mismo corte
        String huella = n.get("/api/analitica/traza/general?" + PERIODO + "&corte=" + c + "&periodo=1996-01").json().path("huella").asText();
        assertThat(fin.path("huella").asText()).isEqualTo(huella).isEqualTo(a.cabecera("X-Huella-Datos"));
        long u = usuarioDe(id);
        assertThat(consultas(u, "ok", id)).isEqualTo(1);
        assertThat(bitacora("analitica_descarga_diferida_solicitada", u, "Descarga diferida " + id + " ")).isEqualTo(1);
        assertThat(bitacora("analitica_descarga_diferida_generada", u, "Descarga diferida " + id + " ")).isEqualTo(1);
        assertThat(bitacora("analitica_descarga_diferida_descarga", u, "Descarga diferida " + id + " ")).isEqualTo(1);
        System.out.println("[medicion A7.3] traza diferida (6 registros, umbral 5): " + ms + " ms de la solicitud a lista");
    }

    @Test
    @DisplayName("tableros: por encima del umbral siguen en la petición (200 en Excel, CSV y PDF; son agregados) y no encolan nada; la traza hasta el umbral también")
    void tableroSiempreSincrono() {
        long c = cargue();
        Navegador n = como("anadiftab", Perfil.ANALISTA);
        for (String f : List.of("csv", "xlsx", "pdf")) {
            Navegador.Respuesta r = n.get("/api/analitica/tableros/medicamento/descarga?" + PERIODO + "&corte=" + c + "&formato=" + f);
            assertThat(r.estado()).as(f + ": " + r.texto()).isEqualTo(200);
            assertThat(r.cabecera("X-Huella-Datos")).hasSize(64);
        }
        Navegador.Respuesta csvTablero = n.get("/api/analitica/tableros/medicamento/descarga?" + PERIODO + "&corte=" + c + "&formato=csv");
        assertThat(csvTablero.texto()).contains("N.º de registros procesados;8");
        // Traza de febrero: 2 registros (≤ 5) ⇒ en la petición, en flujo
        Navegador.Respuesta chico = n.get(trazaEnero().replace("periodo=1996-01", "periodo=1996-02"));
        assertThat(chico.estado()).as(chico.texto()).isEqualTo(200);
        assertThat(chico.texto().lines().toList().getLast()).isEqualTo("FIN;2 registros");
        assertThat(n.get("/api/analitica/descargas-diferidas").json()).isEmpty();
        // Quien no rastrea no la pide (403), aunque vea los tableros
        assertThat(como("digdift", Perfil.SANIDAD).get(trazaEnero()).estado()).isEqualTo(403);
    }

    @Test
    @DisplayName("tope del diferido en la traza: más de max-registros-traza (15 000 en esta prueba) responde 422 con el máximo, sin encolar nada")
    void trazaSobreElTope() throws IOException {
        long c = cargueVolumen();
        Navegador n = como("anadiftt", Perfil.ANALISTA);
        Navegador.Respuesta r = n.get("/api/analitica/traza/general/registros.csv?desde=2025-01-01&hasta=2025-12-31&corte=" + c);
        assertThat(r.estado()).as(r.texto()).isEqualTo(422);
        assertThat(r.mensaje()).contains("máximo 15000");
        assertThat(n.get("/api/analitica/descargas-diferidas").json()).isEmpty();
    }

    @Test
    @DisplayName("corte congelado: pedida SIN corte en la URL, un cargue aplicado entre la solicitud y la generación no entra (mismo corte y registros)")
    void corteCongelado() throws Exception {
        cargue();
        CountDownLatch soltar = new CountDownLatch(1);
        CountDownLatch empezo = new CountDownLatch(1);
        servicio.alEmpezar = id -> {
            empezo.countDown();
            try {
                soltar.await(240, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Navegador n = como("anadifcc", Perfil.ANALISTA);
        long id;
        long corte;
        try {
            Navegador.Respuesta r = n.get("/api/analitica/traza/general/registros.csv?" + PERIODO + "&periodo=1996-01");
            assertThat(r.estado()).as(r.texto()).isEqualTo(202);
            id = r.json().path("id").asLong();
            corte = r.json().path("corteCargueId").asLong();
            assertThat(r.json().path("registros").asLong()).isEqualTo(6);
            assertThat(empezo.await(30, TimeUnit.SECONDS)).isTrue();
            // Otro cargue con 2 dispensaciones más de enero de 1996 se aplica mientras la descarga espera
            long sello = System.nanoTime() % 100_000;
            long nuevo = cargar("descargas_1996_b_" + sello + ".csv", csv(List.of(fila("9160" + sello + "1", LocalDate.of(1996, 1, 20), "RB" + sello + "1"),
                    fila("9160" + sello + "2", LocalDate.of(1996, 1, 21), "RB" + sello + "2"))));
            assertThat(nuevo).isNotEqualTo(corte);
        } finally {
            soltar.countDown();
        }
        JsonNode fin = esperar(n, id);
        assertThat(fin.path("estado").asText()).as(fin.toString()).isEqualTo("lista");
        assertThat(fin.path("corteCargueId").asLong()).isEqualTo(corte);
        Navegador.Respuesta a = n.get("/api/analitica/descargas-diferidas/" + id + "/archivo");
        assertThat(a.texto().lines().toList().getLast()).as("el cargue nuevo no entra").isEqualTo("FIN;6 registros");
        assertThat(a.texto()).contains("(cargue " + corte + ")");
        // Una petición nueva sin corte ya ve el cargue nuevo
        assertThat(n.get("/api/analitica/traza/general?" + PERIODO + "&periodo=1996-01").json().path("total").asLong()).isEqualTo(8);
    }

    @Test
    @DisplayName("una en curso por usuario: mientras se genera, otra descarga grande responde 409; otra persona no la ve (404) ni la descarga")
    void unaEnCurso() throws Exception {
        cargue();
        CountDownLatch soltar = new CountDownLatch(1);
        CountDownLatch empezo = new CountDownLatch(1);
        servicio.alEmpezar = id -> {
            empezo.countDown();
            try {
                soltar.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Navegador n = como("anadifu", Perfil.ANALISTA);
        long id;
        try {
            Navegador.Respuesta r = n.get(trazaEnero());
            assertThat(r.estado()).as(r.texto()).isEqualTo(202);
            id = r.json().path("id").asLong();
            assertThat(empezo.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(n.get("/api/analitica/descargas-diferidas/" + id).json().path("estado").asText()).isEqualTo("generando");
            Navegador.Respuesta otra = n.get(trazaEnero().replace("/general/", "/gasto/"));
            assertThat(otra.estado()).as(otra.texto()).isEqualTo(409);
            assertThat(otra.mensaje()).contains("en curso");
            assertThat(n.get("/api/analitica/descargas-diferidas/" + id + "/archivo").estado()).isEqualTo(409);
        } finally {
            soltar.countDown();
        }
        assertThat(esperar(n, id).path("estado").asText()).isEqualTo("lista");
        Navegador otro = como("anadifo", Perfil.ANALISTA);
        assertThat(otro.get("/api/analitica/descargas-diferidas/" + id).estado()).isEqualTo(404);
        assertThat(otro.get("/api/analitica/descargas-diferidas/" + id + "/archivo").estado()).isEqualTo(404);
        assertThat(otro.get("/api/analitica/descargas-diferidas").json()).noneSatisfy(x -> assertThat(x.path("id").asLong()).isEqualTo(id));
    }

    @Test
    @DisplayName("falla: «fallida» con mensaje genérico, sin archivo, sin fila «ok» y con su fila «error» en ana_consultas y la bitácora; luego se puede pedir otra")
    void fallida() {
        cargue();
        servicio.alEmpezar = id -> {
            throw new IllegalStateException("falla de prueba");
        };
        Navegador n = como("anadiffa", Perfil.ANALISTA);
        long id = n.get(trazaEnero()).json().path("id").asLong();
        JsonNode fin = esperar(n, id);
        assertThat(fin.path("estado").asText()).isEqualTo("fallida");
        assertThat(fin.path("error").asText()).contains("No se pudo generar").doesNotContain("falla de prueba");
        assertThat(jdbc.sql("SELECT ruta FROM ana_descargas_diferidas WHERE id = :id").param("id", id).query(String.class).optional()).isEmpty();
        assertThat(n.get("/api/analitica/descargas-diferidas/" + id + "/archivo").estado()).isEqualTo(409);
        long u = usuarioDe(id);
        assertThat(bitacora("analitica_descarga_diferida_fallida", u, "Descarga diferida " + id + " ")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM ana_consultas WHERE usuario_id = :u AND accion = 'traza' AND resultado = 'ok'").param("u", u)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM ana_consultas WHERE usuario_id = :u AND accion = 'traza' AND resultado = 'error'").param("u", u)
                .query(Long.class).single()).isEqualTo(1);
        servicio.alEmpezar = x -> { };
        assertThat(n.get(trazaEnero()).estado()).isEqualTo(202);
    }

    @Test
    @DisplayName("vencimiento a las 24 h: la descarga responde 410, la limpieza borra el archivo (borrado comprobado) y deja «vencida» en la bitácora")
    void caducidad() {
        cargue();
        String usuario = crearUsuario("anadifv", Perfil.ANALISTA);
        Navegador n = navegador();
        assertThat(n.ingresar(usuario, CLAVE).estado()).isEqualTo(200);
        long id = n.get(trazaEnero()).json().path("id").asLong();
        assertThat(esperar(n, id).path("estado").asText()).isEqualTo("lista");
        String ruta = jdbc.sql("SELECT ruta FROM ana_descargas_diferidas WHERE id = :id").param("id", id).query(String.class).single();
        reloj.adelantar(Duration.ofHours(25));
        n = navegador(); // la sesión anterior venció por inactividad
        assertThat(n.ingresar(usuario, CLAVE).estado()).isEqualTo(200);
        assertThat(n.get("/api/analitica/descargas-diferidas/" + id + "/archivo").estado()).as("vencida aunque la limpieza no haya pasado").isEqualTo(410);
        assertThat(n.get("/api/analitica/descargas-diferidas").json()).as("ya no está en las del día")
                .noneSatisfy(x -> assertThat(x.path("id").asLong()).isEqualTo(id));
        assertThat(servicio.limpiar()).isGreaterThanOrEqualTo(1);
        assertThat(n.get("/api/analitica/descargas-diferidas/" + id).json().path("estado").asText()).isEqualTo("vencida");
        assertThat(n.get("/api/analitica/descargas-diferidas/" + id + "/archivo").estado()).isEqualTo(410);
        assertThatThrownBy(() -> almacen.leer(ruta).close()).isInstanceOf(IOException.class);
        assertThat(jdbc.sql("SELECT ruta FROM ana_descargas_diferidas WHERE id = :id").param("id", id).query(String.class).optional()).isEmpty();
        assertThat(bitacora("analitica_descarga_diferida_vencida", idDe(usuario), "Descarga diferida " + id + " ")).isEqualTo(1);
        assertThat(servicio.limpiar()).as("idempotente").isZero();
    }

    @Test
    @DisplayName("alcance congelado: si el alcance del usuario cambió (ahora Sanidad EJC), la descarga pedida con alcance nacional responde 403")
    void alcanceCambiado() {
        cargue();
        String usuario = crearUsuario("anadifa", Perfil.ANALISTA);
        Navegador n = navegador();
        assertThat(n.ingresar(usuario, CLAVE).estado()).isEqualTo(200);
        long id = n.get(trazaEnero()).json().path("id").asLong();
        assertThat(esperar(n, id).path("estado").asText()).isEqualTo("lista");
        long uid = idDe(usuario);
        n.post("/api/auth/salir", null); // una sesión por usuario
        usuarios.reemplazarPerfiles(uid, List.of(Perfil.SANIDAD));
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'EJC' WHERE id = :id").param("id", uid).update();
        Navegador s = navegador();
        assertThat(s.ingresar(usuario, CLAVE).estado()).isEqualTo(200);
        Navegador.Respuesta r = s.get("/api/analitica/descargas-diferidas/" + id + "/archivo");
        assertThat(r.estado()).as(r.texto()).isEqualTo(403);
        assertThat(r.mensaje()).contains("alcance");
    }

    @Test
    @DisplayName("permiso retirado: con un rol de base Analista sin la pantalla de trazabilidad (mismo alcance nacional), la descarga lista responde 403 y una pendiente no se genera")
    void sinPermisoDeTraza() {
        cargue();
        Navegador adm = como("admdif", Perfil.ADMINISTRADOR);
        Map<String, Object> rol = new HashMap<>();
        rol.put("nombre", unico("Rol analítica sin traza "));
        rol.put("descripcion", "Rol de prueba");
        rol.put("baseId", Perfil.ANALISTA.id());
        rol.put("pantallas", List.of("ANALITICA"));
        Navegador.Respuesta rr = adm.post("/api/usuarios/roles", rol);
        assertThat(rr.estado()).as(rr.texto()).isEqualTo(201);
        int rolId = rr.json().path("id").asInt();
        String usuario = crearUsuario("anadifp", Perfil.ANALISTA);
        Navegador n = navegador();
        assertThat(n.ingresar(usuario, CLAVE).estado()).isEqualTo(200);
        long id = n.get(trazaEnero()).json().path("id").asLong();
        assertThat(esperar(n, id).path("estado").asText()).isEqualTo("lista");
        long uid = idDe(usuario);
        n.post("/api/auth/salir", null);
        usuarios.reemplazarPerfiles(uid, List.of());
        jdbc.sql("INSERT INTO usuarios_perfiles (usuario_id, perfil_id) VALUES (:u, :p)").param("u", uid).param("p", rolId).update();
        Navegador s = navegador();
        assertThat(s.ingresar(usuario, CLAVE).estado()).isEqualTo(200);
        Navegador.Respuesta r = s.get("/api/analitica/descargas-diferidas/" + id + "/archivo");
        assertThat(r.estado()).as(r.texto()).isEqualTo(403);
        assertThat(r.mensaje()).contains("trazabilidad");
        // Una pendiente de ese usuario (pedida cuando aún podía) no se genera: revalidación al generar
        long pendiente = insertar(uid, "pendiente", 0, null, OffsetDateTime.now());
        servicio.despertar();
        JsonNode fin = esperar(s, pendiente);
        assertThat(fin.path("estado").asText()).isEqualTo("fallida");
        assertThat(fin.path("error").asText()).isEqualTo(AnaliticaDescargaDiferida.MENSAJE_SIN_ACCESO);
        assertThat(jdbc.sql("SELECT ruta FROM ana_descargas_diferidas WHERE id = :id").param("id", pendiente).query(String.class).optional()).isEmpty();
    }

    @Test
    @DisplayName("cola: una «generando» de un proceso caído se recupera (archivo a medias borrado, se rehace hasta «lista»); sin intentos queda «fallida»; una demorada vence por edad")
    void recuperacionDeLaCola() throws Exception {
        cargue();
        String usuario = crearUsuario("anadifh", Perfil.ANALISTA);
        Navegador n = navegador();
        assertThat(n.ingresar(usuario, CLAVE).estado()).isEqualTo(200);
        String media = "analitica/descargas/huerfana_" + System.nanoTime() + ".csv";
        almacen.guardar(media, "Título;a medias\r\n".getBytes(StandardCharsets.UTF_8));
        long id = insertar(idDe(usuario), "generando", 1, media, OffsetDateTime.now());
        // Otra «generando» que ya agotó sus intentos (de otro usuario: una en curso por usuario)
        String otro = crearUsuario("anadifh2", Perfil.ANALISTA);
        long agotada = insertar(idDe(otro), "generando", AnaliticaDescargaDiferida.MAX_INTENTOS, null, OffsetDateTime.now());
        servicio.despertar();
        JsonNode fin = esperar(n, id);
        assertThat(fin.path("estado").asText()).as(fin.toString()).isEqualTo("lista");
        assertThatThrownBy(() -> almacen.leer(media).close()).as("el archivo a medias se borró").isInstanceOf(IOException.class);
        assertThat(jdbc.sql("SELECT intentos FROM ana_descargas_diferidas WHERE id = :id").param("id", id).query(Integer.class).single()).isEqualTo(2);
        assertThat(n.get("/api/analitica/descargas-diferidas/" + id + "/archivo").texto().lines().toList().getLast()).isEqualTo("FIN;6 registros");
        assertThat(jdbc.sql("SELECT estado || '|' || error FROM ana_descargas_diferidas WHERE id = :id").param("id", agotada).query(String.class).single())
                .isEqualTo("fallida|" + AnaliticaDescargaDiferida.MENSAJE_INTENTOS);
        // Una pendiente desde hace más de la edad máxima (60 min) vence: el usuario no queda bloqueado por «una en curso»
        String tercero = crearUsuario("anadifh3", Perfil.ANALISTA);
        long vieja = insertar(idDe(tercero), "pendiente", 0, null, OffsetDateTime.now().minusHours(2));
        assertThat(servicio.vencerDemoradas()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT estado || '|' || error FROM ana_descargas_diferidas WHERE id = :id").param("id", vieja).query(String.class).single())
                .isEqualTo("fallida|" + AnaliticaDescargaDiferida.MENSAJE_DEMORA);
        Navegador t = navegador();
        assertThat(t.ingresar(tercero, CLAVE).estado()).isEqualTo(200);
        Navegador.Respuesta r = t.get(trazaEnero());
        assertThat(r.estado()).as(r.texto()).isEqualTo(202);
        assertThat(esperar(t, r.json().path("id").asLong()).path("estado").asText()).isEqualTo("lista");
    }

    @Test
    @DisplayName("volumen de las pruebas normales (20 000 registros sintéticos de 2025): CSV de registros de origen de una Fuerza en diferido; el tablero sigue en la petición; se mide el tiempo")
    void volumenNormal() throws IOException {
        long c = cargueVolumen();
        Navegador n = como("anadifm", Perfil.ANALISTA);
        String periodo = "desde=2025-01-01&hasta=2025-12-31&corte=" + c;
        long t0 = System.nanoTime();
        Navegador.Respuesta tab = n.get("/api/analitica/tableros/medicamento/descarga?" + periodo + "&formato=csv");
        long msTablero = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        assertThat(tab.estado()).as(tab.texto()).isEqualTo(200);
        t0 = System.nanoTime();
        Navegador.Respuesta r = n.get("/api/analitica/traza/general/registros.csv?" + periodo + "&fuerza=EJC");
        assertThat(r.estado()).as(r.texto()).isEqualTo(202);
        long id = r.json().path("id").asLong();
        JsonNode fin = esperar(n, id);
        long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        assertThat(fin.path("estado").asText()).as(fin.toString()).isEqualTo("lista");
        Navegador.Respuesta a = n.get("/api/analitica/descargas-diferidas/" + id + "/archivo");
        assertThat(a.texto().lines().toList().getLast()).isEqualTo("FIN;" + fin.path("registros").asLong() + " registros");
        System.out.println("[medicion A7.3] tablero de medicamento csv en la petición (19 977 registros): " + msTablero + " ms; registros de origen EJC: "
                + fin.path("registros").asLong() + " registros, " + fin.path("tamano").asLong() + " B, " + ms + " ms de la solicitud a lista (generación "
                + fin.path("duracionMs").asLong() + " ms)");
    }
}
