package co.com.agsamericas.digsa.analitica.homologacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.com.agsamericas.digsa.analitica.GeneradorSintetico;
import co.com.agsamericas.digsa.analitica.SugeridorHomologacion;
import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Homologación por la API (docs/especificaciones/analitica-homologacion): fuentes sembradas, carga de las referencias de ejemplo FICTICIAS
 * (src/test/resources/analitica) con su versión, fecha de corte, informe y publicación atómica; homologación como etapa del cargue con
 * el generador sintético de A1 (20 000 filas) y las categorías esperadas por CUM; tabla maestra con la cadena original → fuente → regla
 * → final; revisión (aprobar y devolver); parametrización en vivo con traza y re-ejecución que no toca lo procesado; porcentajes de
 * calidad que suman 100; perfiles (403); exportación; inmutabilidad de lo publicado.
 */
class HomologacionApiTest extends PruebaIntegracion {

    private static final int FILAS = 20_000;
    private static final int PARTE = 1024 * 1024;
    private static final List<String> TERMINALES = List.of("aplicado", "rechazado", "fallido", "duplicado", "vencido", "descartado");
    /** Referencias de ejemplo y el conjunto de cada una. */
    private static final String[][] FUENTES = {
        {"INVIMA_CUM_VIGENTES", "cum_vigentes_ejemplo_ficticio.csv"}, {"INVIMA_CUM_RENOVACION", "cum_renovacion_ejemplo_ficticio.csv"},
        {"INVIMA_CUM_VENCIDOS", "cum_vencidos_ejemplo_ficticio.csv"}, {"INVIMA_CUM_OTROS", "cum_otros_ejemplo_ficticio.csv"},
        {"SISPRO_CIE10", "cie10_ejemplo_ficticio.csv"}, {"DIGSA_CODIGO_SSFM", "codigos_ssfm_ejemplo_ficticio.csv"},
        {"INVIMA_ESTRECHO_MARGEN", "estrecho_margen_ejemplo_ficticio.csv"}, {"MINSALUD_PRECIO_REGULADO", "precio_regulado_ejemplo_ficticio.csv"}};

    /** Cargue base compartido por las pruebas (el contexto y la base son los mismos): referencias publicadas y un insumo aplicado. */
    private record Base(long cargue, int version, GeneradorSintetico.Resultado esperado, String analista, Map<String, Integer> publicadas) {
    }

    private static volatile Base base;
    /** Analista de cada prueba (una sesión por usuario: cada prueba ingresa con el suyo). */
    private String analista;

    @Autowired
    javax.sql.DataSource ds;
    @Autowired
    ServicioReferencias referencias;
    @Autowired
    org.springframework.context.ApplicationContext contexto;
    @Autowired
    co.com.agsamericas.digsa.plataforma.Almacen almacen;

    // ---------- Apoyo ----------

    private String analista() {
        if (analista == null) {
            analista = crearUsuario("anahom", Perfil.ANALISTA);
        }
        return analista;
    }

    private Navegador como(String usuario) {
        Navegador n = navegador();
        assertThat(n.ingresar(usuario, CLAVE).estado()).isEqualTo(200);
        return n;
    }

    private Navegador nuevo(String prefijo, Perfil... perfiles) {
        return como(crearUsuario(prefijo, perfiles));
    }

    private static byte[] recurso(String nombre) {
        try (InputStream in = HomologacionApiTest.class.getResourceAsStream("/analitica/" + nombre)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Navegador.Respuesta subirFuente(Navegador n, String codigo, String nombre, byte[] datos, String fechaCorte) {
        Map<String, String> campos = new LinkedHashMap<>();
        if (fechaCorte != null) {
            campos.put("fechaCorte", fechaCorte);
        }
        campos.put("ejemplo", "true");
        return n.multipart("/api/homologacion/fuentes/" + codigo + "/versiones", campos, Map.of(nombre, datos), "archivo", "text/csv");
    }

    /** CUM ficticio del medicamento i del generador sintético. */
    private static String cum(int i) {
        return (20010000 + (i + 1) * 37) + "-" + (1 + i % 3);
    }

    /** Estado esperado del medicamento i del generador con las referencias de ejemplo (ver LEEME_DATOS_FICTICIOS.txt). */
    private static String estadoEsperado(int i) {
        return switch (i) {
            case 0, 3 -> "inconsistente";
            case 10 -> "pendiente";
            case 28, 29 -> "no_valido";
            case 30 -> "sin_correspondencia";
            default -> "homologado";
        };
    }

    private static int partes(byte[] d) {
        return (d.length + PARTE - 1) / PARTE;
    }

    /** Sube el insumo por partes, confirma la propuesta de mapeo y espera el fin (igual que la pantalla «Cargues del OPL»). */
    private static JsonNode cargarInsumo(Navegador n, String nombre, byte[] datos) {
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

    private Base base() throws IOException {
        Base b = base;
        if (b != null) {
            return b;
        }
        synchronized (HomologacionApiTest.class) {
            if (base == null) {
                String analista = crearUsuario("analistahom", Perfil.ANALISTA);
                Navegador n = como(analista);
                assertThat(jdbc.sql("SELECT count(*) FROM ref_version").query(Long.class).single()).as("ninguna versión cargada al empezar").isZero();
                Map<String, Integer> publicadas = new TreeMap<>();
                for (String[] f : FUENTES) {
                    Navegador.Respuesta r = subirFuente(n, f[0], f[1], recurso(f[1]), "2026-10-16");
                    assertThat(r.estado()).as(f[0] + ": " + r.texto()).isEqualTo(201);
                    assertThat(r.json().path("estado").asText()).isEqualTo("validada");
                    assertThat(r.json().path("ejemplo").asBoolean()).isTrue();
                    int id = r.json().path("id").asInt();
                    Navegador.Respuesta p = n.post("/api/homologacion/versiones/" + id + "/publicar", null);
                    assertThat(p.estado()).as(p.texto()).isEqualTo(200);
                    assertThat(p.json().path("estado").asText()).isEqualTo("publicada");
                    publicadas.put(f[0], id);
                }
                Path dir = Files.createTempDirectory("digsa-sintetico-hom-");
                GeneradorSintetico.Resultado r = GeneradorSintetico.generar(dir, "sintetico_hom", FILAS, 4242L);
                JsonNode fin = cargarInsumo(n, "insumo_homologacion_2025.csv", Files.readAllBytes(r.csv()));
                assertThat(fin.path("estado").asText()).as(fin.toString()).isEqualTo("aplicado");
                long id = fin.path("id").asLong();
                int version = jdbc.sql("SELECT DISTINCT version_id FROM hom_cargue_origen WHERE cargue_id = :c").param("c", id).query(Integer.class).single();
                n.post("/api/auth/salir", null);
                base = new Base(id, version, r, analista, publicadas);
            }
            return base;
        }
    }

    /** Registros esperados por estado (de los totales del generador por CUM, calculados sin la base). */
    private static Map<String, Long> esperadoPorEstado(GeneradorSintetico.Resultado r) throws IOException {
        Map<String, String[]> porCum = GeneradorSintetico.leerDimensiones(r.totales()).get("cum");
        Map<String, Long> m = new TreeMap<>();
        for (int i = 0; i < 40; i++) {
            String[] x = porCum.get(cum(i));
            if (x != null) {
                m.merge(estadoEsperado(i), Long.parseLong(x[0]), Long::sum);
            }
        }
        return m;
    }

    private Map<String, Long> kpis(JsonNode calidad) {
        Map<String, Long> m = new TreeMap<>();
        calidad.path("kpis").forEach(k -> {
            if (k.path("registros").asLong() > 0) {
                m.put(k.path("estado").asText(), k.path("registros").asLong());
            }
        });
        return m;
    }

    private static BigDecimal sumaPorcentajes(JsonNode calidad) {
        BigDecimal s = BigDecimal.ZERO;
        for (JsonNode k : calidad.path("kpis")) {
            s = s.add(new BigDecimal(k.path("porcentaje").asText()));
        }
        return s;
    }

    private long origenDe(int version, String cum) {
        return jdbc.sql("SELECT origen_id FROM hom_tabla_maestra_version(:v) WHERE orig_cum = :c").param("v", version).param("c", cum)
                .query(Long.class).single();
    }

    private String estadoEn(int version, long origen) {
        return jdbc.sql("SELECT estado FROM hom_tabla_maestra_version(:v) WHERE origen_id = :o").param("v", version).param("o", origen)
                .query(String.class).single();
    }

    private int vigente() {
        return jdbc.sql("SELECT id FROM hom_version WHERE estado = 'publicada' ORDER BY numero DESC LIMIT 1").query(Integer.class).single();
    }

    /** Versión publicada hoy del CUM vigente (puede no ser la de la base: ver desactualizadaTrasPublicarCum). */
    private int cumVigentesPublicada() {
        return jdbc.sql("""
                SELECT v.id FROM ref_version v JOIN ref_fuente f ON f.id = v.fuente_id WHERE f.codigo = 'INVIMA_CUM_VIGENTES' AND v.estado = 'publicada'""")
                .query(Integer.class).single();
    }

    // ---------- Fuentes ----------

    @Test
    @DisplayName("fuentes sembradas: las 10 de V61 con licencia CC BY-SA 4.0 e INVIMA donde se verificó; la pantalla muestra «Datos de ejemplo»")
    void fuentesSembradas() throws IOException {
        Base b = base();
        Navegador n = como(analista());
        JsonNode f = n.get("/api/homologacion/fuentes").json();
        List<String> codigos = new ArrayList<>();
        f.forEach(x -> codigos.add(x.path("codigo").asText()));
        assertThat(codigos).containsExactly("INVIMA_CUM_VIGENTES", "INVIMA_CUM_RENOVACION", "INVIMA_CUM_VENCIDOS", "INVIMA_CUM_OTROS", "SISPRO_CIE10",
                "MINSALUD_PRECIO_REGULADO", "INVIMA_VITALES", "INVIMA_ESTRECHO_MARGEN", "MSPS_CONTROL_ESPECIAL", "DIGSA_CODIGO_SSFM");
        JsonNode vig = f.get(0);
        assertThat(vig.path("dataset").asText()).isEqualTo("i7cb-raxc");
        assertThat(vig.path("licencia").asText()).isEqualTo("CC BY-SA 4.0");
        assertThat(vig.path("atribucion").asText()).isEqualTo("INVIMA");
        assertThat(vig.path("publicada").path("ejemplo").asBoolean()).isTrue();
        assertThat(vig.path("publicada").path("fechaCorte").asText()).isEqualTo("2026-10-16");
        assertThat(n.get("/api/homologacion/resumen").json().path("datosDeEjemplo").asBoolean()).isTrue();
        assertThat(f.get(6).path("publicada").isNull()).as("vitales sin versión").isTrue();
    }

    @Test
    @DisplayName("sin fecha de corte: 422 «Indique la fecha de actualización de la fuente.» y no crea la versión")
    void sinFechaCorte() throws IOException {
        Base b = base();
        Navegador n = como(analista());
        long antes = jdbc.sql("SELECT count(*) FROM ref_version").query(Long.class).single();
        Navegador.Respuesta r = subirFuente(n, "INVIMA_VITALES", "vitales.csv", "nombre_comercial_,principio_activo1\r\nX,Y\r\n".getBytes(StandardCharsets.UTF_8), null);
        assertThat(r.estado()).isEqualTo(422);
        assertThat(r.mensaje()).isEqualTo("Indique la fecha de actualización de la fuente.");
        assertThat(jdbc.sql("SELECT count(*) FROM ref_version").query(Long.class).single()).isEqualTo(antes);
    }

    @Test
    @DisplayName("encabezados errados: sin consecutivocum la versión queda «rechazada» con «Faltan las columnas: …» y la vigente no cambia")
    void encabezadosErrados() throws IOException {
        Base b = base();
        Navegador n = como(analista());
        int publicadaAntes = cumVigentesPublicada();
        String csv = new String(recurso("cum_vigentes_ejemplo_ficticio.csv"), StandardCharsets.UTF_8).replace("consecutivocum", "consecutivo_mal");
        Navegador.Respuesta r = subirFuente(n, "INVIMA_CUM_VIGENTES", "vigentes_mal.csv", csv.getBytes(StandardCharsets.UTF_8), "2026-10-17");
        assertThat(r.estado()).isEqualTo(422);
        assertThat(r.mensaje()).isEqualTo("Faltan las columnas: consecutivocum");
        assertThat(jdbc.sql("SELECT estado FROM ref_version WHERE fuente_id = (SELECT id FROM ref_fuente WHERE codigo = 'INVIMA_CUM_VIGENTES') "
                + "ORDER BY numero DESC LIMIT 1").query(String.class).single()).isEqualTo("rechazada");
        assertThat(jdbc.sql("SELECT id FROM ref_version WHERE estado = 'publicada' AND fuente_id = (SELECT id FROM ref_fuente WHERE codigo = "
                + "'INVIMA_CUM_VIGENTES')").query(Integer.class).single()).isEqualTo(publicadaAntes);
        assertThat(jdbc.sql("SELECT count(*) FROM ref_cum_staging").query(Long.class).single()).as("el staging se vacía").isZero();
    }

    @Test
    @DisplayName("mismo archivo dos veces: 409 «Este archivo ya está cargado como versión 1.» sin crear otra")
    void mismoSha() throws IOException {
        Base b = base();
        Navegador n = como(analista());
        long antes = jdbc.sql("SELECT count(*) FROM ref_version").query(Long.class).single();
        Navegador.Respuesta r = subirFuente(n, "SISPRO_CIE10", "cie10.csv", recurso("cie10_ejemplo_ficticio.csv"), "2026-10-16");
        assertThat(r.estado()).isEqualTo(409);
        assertThat(r.mensaje()).isEqualTo("Este archivo ya está cargado como versión 1.");
        assertThat(jdbc.sql("SELECT count(*) FROM ref_version").query(Long.class).single()).isEqualTo(antes);
    }

    @Test
    @DisplayName("informe del CUM: presentaciones, principios y roles separados, fila sin llave, fecha no MM/DD/YYYY y CUM en otro conjunto")
    void informeCum() throws IOException {
        Base b = base();
        // La publicada de hoy (desactualizadaTrasPublicarCum publica otra con las mismas filas más una)
        int vig = cumVigentesPublicada();
        Navegador n = como(analista());
        JsonNode i = n.get("/api/homologacion/fuentes").json().get(0).path("publicada").path("informe");
        assertThat(i.path("filasSinLlave").asLong()).isEqualTo(1);
        assertThat(i.path("fechasNoConvertibles").asLong()).isGreaterThanOrEqualTo(1);
        // El combinado salmeterol/fluticasona: 4 filas (2 principios × 2 roles) ⇒ 1 presentación, 2 principios, 2 roles
        assertThat(jdbc.sql("SELECT count(*) FROM ref_cum_presentacion WHERE version_id = :v AND cum = :c").param("v", vig).param("c", cum(39))
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM ref_cum_principio_activo WHERE version_id = :v AND cum = :c").param("v", vig).param("c", cum(39))
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM ref_cum_rol WHERE version_id = :v AND cum = :c").param("v", vig).param("c", cum(39))
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT principio_base FROM ref_cum_principio_activo WHERE version_id = :v AND cum = :c").param("v", vig).param("c", cum(0))
                .query(String.class).single()).isEqualTo("LOSARTAN");
        // «otros estados» se cargó después de publicar vigentes: su informe lista la metformina que también está en vigentes
        JsonNode otros = n.get("/api/homologacion/fuentes").json().get(3).path("publicada").path("informe");
        assertThat(otros.path("enOtrosConjuntos").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(otros.path("ejemplosEnOtrosConjuntos").toString()).contains(cum(1));
    }

    @Test
    @DisplayName("precio regulado: «1234,50» y «1234.5» quedan 1234,50; el precio no numérico se rechaza en el informe con su línea")
    void precioTexto() throws IOException {
        Base b = base();
        int v = b.publicadas().get("MINSALUD_PRECIO_REGULADO");
        List<BigDecimal> precios = jdbc.sql("SELECT precio_institucional FROM ref_precio_regulado WHERE version_id = :v ORDER BY linea").param("v", v)
                .query(BigDecimal.class).list();
        assertThat(precios).hasSize(2).allSatisfy(p -> assertThat(p).isEqualByComparingTo("1234.50"));
        JsonNode inf = como(analista()).get("/api/homologacion/fuentes").json().get(5).path("publicada").path("informe");
        assertThat(inf.path("filasRechazadas").asLong()).isEqualTo(1);
        assertThat(inf.path("rechazos").toString()).contains("Línea 4: precio institucional no numérico");
    }

    @Test
    @DisplayName("publicación atómica: la v2 de la lista reemplaza a la v1 en una transacción; una sola publicada; lo publicado no se modifica")
    void publicacionAtomicaEInmutable() throws IOException {
        Base b = base();
        Navegador n = como(analista());
        int v1 = b.publicadas().get("INVIMA_ESTRECHO_MARGEN");
        Map<String, Object> cuerpo = Map.of("filas", List.of(
                Map.of("principioActivo", "Carbamazepina", "norma", "INVIMA, listado de estrecho margen (EJEMPLO FICTICIO)", "fechaNorma", "2024-07-01"),
                Map.of("principioActivo", "Levotiroxina", "norma", "INVIMA, listado de estrecho margen (EJEMPLO FICTICIO)", "fechaNorma", "2024-07-01"),
                Map.of("principioActivo", "Ácido valproico", "norma", "INVIMA, listado de estrecho margen (EJEMPLO FICTICIO)", "fechaNorma", "2024-07-01"),
                Map.of("principioActivo", "Warfarina", "norma", "INVIMA, PDF de julio de 2024 (EJEMPLO FICTICIO)", "fechaNorma", "2024-07-01")));
        Navegador.Respuesta r = n.put("/api/homologacion/fuentes/INVIMA_ESTRECHO_MARGEN/lista", cuerpo);
        assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        assertThat(r.json().path("estado").asText()).isEqualTo("publicada");
        assertThat(r.json().path("origen").asText()).isEqualTo("edicion");
        assertThat(jdbc.sql("SELECT estado FROM ref_version WHERE id = :v").param("v", v1).query(String.class).single()).isEqualTo("reemplazada");
        assertThat(jdbc.sql("SELECT count(*) FROM ref_version WHERE estado = 'publicada' AND fuente_id = (SELECT id FROM ref_fuente WHERE codigo = "
                + "'INVIMA_ESTRECHO_MARGEN')").query(Long.class).single()).isEqualTo(1);
        // La lista nueva tiene WARFARINA y el historial guarda el antes y el después
        assertThat(n.get("/api/homologacion/fuentes/INVIMA_ESTRECHO_MARGEN/lista").texto()).contains("Warfarina");
        String despues = jdbc.sql("SELECT despues::text FROM hom_regla_historial WHERE objeto = 'lista:INVIMA_ESTRECHO_MARGEN' ORDER BY id DESC LIMIT 1")
                .query(String.class).single();
        assertThat(despues).contains("Warfarina");
        assertThat(jdbc.sql("SELECT descripcion FROM hom_regla_historial WHERE objeto = 'lista:INVIMA_ESTRECHO_MARGEN' ORDER BY id DESC LIMIT 1")
                .query(String.class).single()).contains("+WARFARINA");
        esperar(() -> bitacoraDe(idDe(analista())).stream().anyMatch(l -> l.contains("homologacion_lista_modificada")), "bitácora de la lista");
        // Inmutable: una versión publicada o reemplazada no cambia ni se borra; la tabla maestra y su historial tampoco
        int publicada = b.publicadas().get("SISPRO_CIE10");
        assertThatThrownBy(() -> jdbc.sql("UPDATE ref_version SET fecha_corte = DATE '2020-01-01' WHERE id = :v").param("v", publicada).update())
                .hasMessageContaining("no se modifica");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ref_version WHERE id = :v").param("v", v1).update()).hasMessageContaining("no se borra");
        assertThatThrownBy(() -> jdbc.sql("UPDATE hom_tabla_maestra SET estado = 'homologado' WHERE version_id = :v").param("v", b.version()).update())
                .hasMessageContaining("no se modifica");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM hom_regla_historial").update()).hasMessageContaining("no se modifica");
        assertThatThrownBy(() -> jdbc.sql("UPDATE ref_version SET estado = 'publicada', publicada_en = now() WHERE id = :v").param("v", v1).update())
                .as("una sola publicada por fuente").isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("versión de referencia interrumpida por un reinicio: al arrancar pasa a «fallida» con motivo, sin archivo ni staging")
    void versionInterrumpida() throws IOException {
        base();
        int fuente = jdbc.sql("SELECT id FROM ref_fuente WHERE codigo = 'INVIMA_VITALES'").query(Integer.class).single();
        String ruta = "homologacion/referencias/invima_vitales/interrumpida.csv";
        try (var out = almacen.escribir(ruta)) {
            out.write("nombre_comercial_,principio_activo1\r\nX,Y\r\n".getBytes(StandardCharsets.UTF_8));
        }
        int v = jdbc.sql("""
                INSERT INTO ref_version (fuente_id, numero, fecha_corte, responsable_texto, origen, nombre_archivo, almacen_ruta, sha256)
                VALUES (:f, (SELECT coalesce(max(numero), 0) + 1 FROM ref_version WHERE fuente_id = :f), DATE '2026-10-01', 'prueba', 'archivo',
                        'interrumpida.csv', :r, repeat('a', 64)) RETURNING id""").param("f", fuente).param("r", ruta).query(Integer.class).single();
        jdbc.sql("INSERT INTO ref_cum_staging (version_id, linea) VALUES (:v, 1)").param("v", v).update();
        referencias.recuperarInterrumpidas(); // lo que corre al arrancar (ApplicationReadyEvent)
        Map<String, Object> r = jdbc.sql("SELECT estado, motivo, almacen_ruta FROM ref_version WHERE id = :v").param("v", v).query().singleRow();
        assertThat(r.get("estado")).isEqualTo("fallida");
        assertThat(r.get("motivo")).isEqualTo(ServicioReferencias.INTERRUMPIDA);
        assertThat(r.get("almacen_ruta")).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM ref_cum_staging WHERE version_id = :v").param("v", v).query(Long.class).single()).isZero();
        assertThatThrownBy(() -> almacen.leer(ruta).close()).as("el archivo se borró del almacén").isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("ejemplo en producción: publicar una versión de ejemplo responde 422 y la versión sigue «validada»; la publicada no cambia")
    void ejemploBloqueado() throws IOException {
        base();
        Navegador n = como(analista());
        Navegador.Respuesta r = subirFuente(n, "INVIMA_VITALES", "vitales_ejemplo.csv",
                "nombre_comercial_,principio_activo1\r\nPRODUCTO FICTICIO,PRINCIPIO FICTICIO\r\n".getBytes(StandardCharsets.UTF_8), "2026-10-01");
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        int id = r.json().path("id").asInt();
        assertThat(r.json().path("ejemplo").asBoolean()).isTrue();
        var cfg = contexto.getBean(co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa.class);
        var prod = new co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa(cfg.url(), "produccion", cfg.sesionMinutos(), cfg.claveVigenciaDias(),
                cfg.correo(), cfg.captcha(), cfg.reportes(), cfg.archivos(), cfg.sms());
        ServicioReferencias enProduccion = new ServicioReferencias(jdbc, contexto.getBean(org.springframework.jdbc.core.JdbcTemplate.class), ds, almacen,
                contexto.getBean(co.com.agsamericas.digsa.plataforma.RegistroBitacora.class), contexto.getBean(com.fasterxml.jackson.databind.ObjectMapper.class),
                reloj, prod, contexto.getBean(co.com.agsamericas.digsa.analitica.PropiedadesAnalitica.class),
                contexto.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        Integer publicadaAntes = jdbc.sql("SELECT id FROM ref_version WHERE estado = 'publicada' AND fuente_id = (SELECT id FROM ref_fuente WHERE codigo = "
                + "'INVIMA_VITALES')").query(Integer.class).optional().orElse(null);
        assertThatThrownBy(() -> enProduccion.publicar(id, null))
                .hasMessage("Las referencias de ejemplo no se pueden publicar en producción.");
        assertThat(jdbc.sql("SELECT estado FROM ref_version WHERE id = :v").param("v", id).query(String.class).single()).isEqualTo("validada");
        assertThat(jdbc.sql("SELECT id FROM ref_version WHERE estado = 'publicada' AND fuente_id = (SELECT id FROM ref_fuente WHERE codigo = "
                + "'INVIMA_VITALES')").query(Integer.class).optional().orElse(null)).isEqualTo(publicadaAntes);
        // Vitales: el archivo original trae el solicitante; tras validar se conserva solo el SHA-256
        assertThat(jdbc.sql("SELECT almacen_ruta FROM ref_version WHERE id = :v").param("v", id).query(String.class).optional().orElse(null)).isNull();
    }

    // ---------- Homologación en el cargue ----------

    @Test
    @DisplayName("cargue de 20 000 filas: la etapa «homologacion» corre antes de los hechos y cada estado cuadra con el generador por CUM")
    void homologacionEnElCargue() throws IOException {
        Base b = base();
        Navegador n = como(analista());
        JsonNode cargue = n.get("/api/analitica/cargues/" + b.cargue()).json();
        assertThat(cargue.path("etapasMs").has("homologacion")).as(cargue.path("etapasMs").toString()).isTrue();
        // Todos los hechos con su dato original y la versión de la tabla maestra del cargue
        assertThat(jdbc.sql("SELECT count(*) FROM ana_dispensaciones WHERE cargue_id = :c AND medicamento_id IS NULL").param("c", b.cargue())
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(DISTINCT hom_version_id) FROM ana_dispensaciones WHERE cargue_id = :c").param("c", b.cargue())
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT max(hom_version_id) FROM ana_dispensaciones WHERE cargue_id = :c").param("c", b.cargue()).query(Integer.class).single())
                .isEqualTo(b.version());
        // Cada medicamento del generador en su estado
        for (int i = 0; i < 40; i++) {
            Optional<String> e = jdbc.sql("SELECT estado FROM hom_tabla_maestra_version(:v) WHERE orig_cum = :c").param("v", b.version())
                    .param("c", cum(i)).query(String.class).optional();
            assertThat(e).as("medicamento " + i + " (" + cum(i) + ")").contains(estadoEsperado(i));
        }
        assertThat(jdbc.sql("SELECT regla FROM hom_tabla_maestra_version(:v) WHERE orig_cum = :c").param("v", b.version()).param("c", cum(6))
                .query(String.class).single()).as("ACETAMINOFEN ⇒ PARACETAMOL").isEqualTo("R-004");
        assertThat(jdbc.sql("SELECT regla FROM hom_tabla_maestra_version(:v) WHERE orig_cum = :c").param("v", b.version()).param("c", cum(0))
                .query(String.class).single()).isEqualTo("R-015");
        assertThat(jdbc.sql("SELECT regla FROM hom_tabla_maestra_version(:v) WHERE orig_cum = :c").param("v", b.version()).param("c", cum(28))
                .query(String.class).single()).isEqualTo("R-021");
        assertThat(jdbc.sql("SELECT regla FROM hom_tabla_maestra_version(:v) WHERE orig_cum = :c").param("v", b.version()).param("c", cum(10))
                .query(String.class).single()).isEqualTo("R-030");
        // Tablero de calidad: registros por estado = esperado; los 6 porcentajes suman 100,00
        JsonNode c = n.get("/api/homologacion/calidad?cargue=" + b.cargue() + "&version=" + b.version()).json();
        assertThat(kpis(c)).isEqualTo(esperadoPorEstado(b.esperado()));
        assertThat(c.path("registros").asLong()).isEqualTo(b.esperado().procesados());
        assertThat(sumaPorcentajes(c)).isEqualByComparingTo("100.00");
        assertThat(c.path("kpis")).hasSize(6);
        // Indicadores del A4 §7 del cargue
        JsonNode ind = c.path("cargues").get(0);
        assertThat(ind.path("cargue").asLong()).isEqualTo(b.cargue());
        assertThat(ind.path("validos").asLong()).isEqualTo(b.esperado().procesados());
        assertThat(ind.path("duplicados").asLong()).isEqualTo(b.esperado().rechazosPorCausa().getOrDefault("duplicado_exacto", 0L));
        // Periodo: enero solo cuenta lo de enero
        JsonNode enero = n.get("/api/homologacion/calidad?cargue=" + b.cargue() + "&version=" + b.version() + "&desde=2025-01&hasta=2025-01").json();
        long esperadoEnero = Long.parseLong(GeneradorSintetico.leerDimensiones(b.esperado().totales()).get("mes").get("2025-01")[0]);
        assertThat(enero.path("registros").asLong()).isEqualTo(esperadoEnero);
        assertThat(sumaPorcentajes(enero)).isEqualByComparingTo("100.00");
        // CIE-10 de la versión (F200 no está en la CIE-10 de ejemplo)
        assertThat(c.path("cie10").toString()).contains("homologado").contains("sin_correspondencia");
        // La ejecución quedó en la bitácora como proceso automático (se registra al confirmar la aplicación del cargue)
        esperar(() -> jdbc.sql("SELECT count(*) FROM log_transacciones WHERE tipo = 'homologacion_ejecutada' AND descripcion LIKE :d")
                .param("d", "%cargue " + b.cargue() + "%").query(Long.class).single() >= 1, "ejecución del cargue en la bitácora");
        // Nada quedó pendiente del cargue aplicado
        assertThat(jdbc.sql("SELECT count(*) FROM hom_medicamento_origen WHERE pendiente_cargue_id = :c").param("c", b.cargue()).query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("SELECT estado FROM hom_version WHERE id = :v").param("v", b.version()).query(String.class).single()).isEqualTo("publicada");
    }

    @Test
    @DisplayName("tabla maestra: buscar «850» o un CUM muestra la cadena original → fuente (versión y fecha) → regla → dato final, paginada")
    void tablaMaestra() throws IOException {
        Base b = base();
        Navegador n = como(analista());
        JsonNode t = n.get("/api/homologacion/tabla-maestra?version=" + b.version() + "&q=850").json();
        assertThat(t.path("total").asLong()).isGreaterThanOrEqualTo(1);
        JsonNode f = null;
        for (JsonNode x : t.path("filas")) {
            if (cum(1).equals(x.path("original").path("cum").asText())) {
                f = x;
            }
        }
        assertThat(f).as(t.toString()).isNotNull();
        assertThat(f.path("original").path("descripcion").asText()).isEqualTo("METFORMINA 850 MG TABLETA");
        assertThat(f.path("fuente").path("codigo").asText()).isEqualTo("INVIMA_CUM_VIGENTES");
        assertThat(f.path("fuente").path("version").asInt()).isEqualTo(1);
        assertThat(f.path("fuente").path("fechaCorte").asText()).isEqualTo("2026-10-16");
        assertThat(f.path("regla").asText()).isEqualTo("R-001");
        assertThat(f.path("final").path("dci").asText()).isEqualTo("Metformina");
        assertThat(f.path("final").path("concentracion").asText()).isEqualTo("850 mg");
        assertThat(f.path("final").path("atc").asText()).isEqualTo("A10BA02");
        assertThat(f.path("final").path("grupoFarmacologico").asText()).isEqualTo("A10B");
        assertThat(f.path("final").path("codigoSsfm").asText()).isEqualTo("SSFM-0002");
        assertThat(f.path("estado").asText()).isEqualTo("homologado");
        assertThat(f.path("accion").asText()).isEqualTo("Ver");
        // Por CUM y por estado
        JsonNode porCum = n.get("/api/homologacion/tabla-maestra?version=" + b.version() + "&q=" + cum(28)).json();
        assertThat(porCum.path("filas").get(0).path("estado").asText()).isEqualTo("no_valido");
        assertThat(porCum.path("filas").get(0).path("final").isNull()).as("sin dato final").isTrue();
        assertThat(porCum.path("filas").get(0).path("accion").asText()).isEqualTo("Revisar");
        JsonNode inconsistentes = n.get("/api/homologacion/tabla-maestra?version=" + b.version() + "&estado=inconsistente").json();
        inconsistentes.path("filas").forEach(x -> assertThat(x.path("diferencias").size()).isPositive());
        // «Ver»: la cadena, la fuente y los cargues con sus registros
        long origen = f.path("origenId").asLong();
        JsonNode d = n.get("/api/homologacion/tabla-maestra/" + origen + "?version=" + b.version()).json();
        assertThat(d.path("cargues").toString()).contains("\"cargue\":" + b.cargue());
        long registros = Long.parseLong(GeneradorSintetico.leerDimensiones(b.esperado().totales()).get("cum").get(cum(1))[0]);
        assertThat(d.path("registros").asLong()).isGreaterThanOrEqualTo(registros);
        assertThat(n.get("/api/homologacion/tabla-maestra?estado=raro").estado()).isEqualTo(422);
        esperar(() -> bitacoraDe(idDe(analista())).stream().anyMatch(l -> l.contains("homologacion_consulta")), "consulta en la bitácora");
    }

    @Test
    @DisplayName("cadena completa: todo registro tiene dato original, regla, un estado válido y fuente con versión y fecha según su estado y regla")
    void cadenaCompleta() throws IOException {
        Base b = base();
        // La versión del cargue y la vigente (con las decisiones humanas de las demás pruebas encima)
        for (int v : List.of(b.version(), vigente())) {
            List<Map<String, Object>> filas = jdbc.sql("SELECT * FROM hom_tabla_maestra_version(:v)").param("v", v).query().listOfRows();
            assertThat(filas).isNotEmpty();
            for (Map<String, Object> f : filas) {
                String estado = (String) f.get("estado");
                String regla = (String) f.get("regla");
                String que = "dato " + f.get("origen_id") + " · " + estado + " · " + regla;
                assertThat(regla).as(que).isNotNull();
                assertThat(List.of("homologado", "inconsistente", "duplicado", "sin_correspondencia", "pendiente", "no_valido")).contains(estado);
                assertThat(f.get("orig_descripcion") != null || f.get("orig_cum") != null).as(que).isTrue();
                if (f.get("fuente_codigo") != null) {
                    assertThat(f.get("fuente_version_id")).as(que).isNotNull();
                    assertThat(f.get("fuente_fecha_corte")).as(que).isNotNull();
                }
                // «Sin fuente» solo donde la invariante lo admite: sin correspondencia (R-050), formato de CUM no válido (R-002), duplicado de
                // datos sin correspondencia (R-040) o devuelto al OPL cuyo dato no tenía fuente; homologado e inconsistente, siempre con fuente
                if (List.of("homologado", "inconsistente").contains(estado) || List.of("R-001", "R-003", "R-004", "R-012", "R-013", "R-015", "R-016",
                        "R-021", "R-030", "R-041").contains(regla)) {
                    assertThat(f.get("fuente_codigo")).as(que + " sin fuente").isNotNull();
                } else if (f.get("fuente_codigo") == null) {
                    assertThat(regla).as(que).isIn("R-050", "R-002", "R-040", "MANUAL");
                    if ("MANUAL".equals(regla)) {
                        assertThat(f.get("devuelto")).as(que).isEqualTo(true);
                    }
                }
            }
        }
    }

    // ---------- Revisión ----------

    /** Sube un insumo pequeño con las filas dadas (DESC_MEDICAMENTO;CUM;FECHA dd/mm/aaaa) y espera que se aplique. */
    private JsonNode insumo(Navegador n, String sello, List<String[]> filas) {
        StringBuilder sb = new StringBuilder("﻿TIPO_DOC;NUM_DOC;FUERZA;COD_ESM;MODALIDAD;FECHA_DISPENSACION;DESC_MEDICAMENTO;CUM;PRINCIPIO_ACTIVO;"
                + "CANTIDAD;VALOR_TOTAL;NUM_FORMULA\r\n");
        int i = 0;
        for (String[] f : filas) {
            i++;
            sb.append("CC;7").append(sello).append(i).append(";EJC;BAS02;Dispensación;").append(f.length > 2 ? f[2] : "10/10/2025").append(';')
                    .append(f[0]).append(';').append(f[1]).append(';').append(f.length > 3 ? f[3] : "").append(";30;2259,00;F").append(sello).append(i)
                    .append("\r\n");
        }
        JsonNode fin = cargarInsumo(n, "insumo_" + sello + ".csv", sb.toString().getBytes(StandardCharsets.UTF_8));
        assertThat(fin.path("estado").asText()).as(fin.toString()).isEqualTo("aplicado");
        return fin;
    }

    private static String sello() {
        return Long.toString(System.nanoTime() % 1_000_000_000L);
    }

    private long origenPorDescripcion(String descripcion) {
        return jdbc.sql("SELECT id FROM hom_medicamento_origen WHERE descripcion = :d").param("d", descripcion).query(Long.class).single();
    }

    @Test
    @DisplayName("revisión: OPL frente a INVIMA, sin IA «Sin sugerencia automática»; devolver exige motivo; aprobar crea la versión N+1 con MANUAL")
    void revisionAprobarYDevolver() throws IOException {
        Base b = base();
        Navegador analista = como(analista());
        int v0 = vigente();
        long enalapril = origenDe(v0, cum(3));
        JsonNode comp = analista.get("/api/homologacion/revision/" + enalapril).json();
        assertThat(comp.path("estado").asText()).isEqualTo("inconsistente");
        assertThat(comp.path("version").asInt()).isEqualTo(v0);
        boolean concDistinta = false;
        for (JsonNode c : comp.path("campos")) {
            if ("Concentración".equals(c.path("campo").asText())) {
                concDistinta = c.path("distinto").asBoolean();
                assertThat(c.path("opl").asText()).isEqualTo("20 MG");
                assertThat(c.path("invima").asText()).isEqualTo("10 mg");
            }
        }
        assertThat(concDistinta).isTrue();
        assertThat(comp.path("sugeridor").path("disponible").asBoolean()).isFalse();
        JsonNode sug = analista.post("/api/homologacion/revision/" + enalapril + "/sugerencia", null).json();
        assertThat(sug.path("mensaje").asText()).isEqualTo("Sin sugerencia automática");
        // Devolver sin motivo ⇒ 422; con una versión revisada vieja ⇒ 409; con motivo ⇒ pendiente · devuelto al OPL en la versión N+1
        Navegador.Respuesta sinMotivo = analista.post("/api/homologacion/revision/" + enalapril + "/devolver", Map.of("motivo", " "));
        assertThat(sinMotivo.estado()).isEqualTo(422);
        assertThat(sinMotivo.mensaje()).isEqualTo("Escriba el motivo de la devolución.");
        assertThat(sinMotivo.json().path("campo").asText()).as("UI-21: la interfaz marca el campo").isEqualTo("motivo");
        assertThat(analista.post("/api/homologacion/revision/" + enalapril + "/devolver", Map.of("motivo", "x", "version", v0 - 1)).estado())
                .as("versión revisada vieja").isEqualTo(409);
        long filasAntes = jdbc.sql("SELECT count(*) FROM hom_tabla_maestra").query(Long.class).single();
        Navegador.Respuesta dev = analista.post("/api/homologacion/revision/" + enalapril + "/devolver", Map.of("motivo",
                "La concentración del insumo no coincide con el CUM reportado", "version", v0));
        assertThat(dev.estado()).as(dev.texto()).isEqualTo(200);
        int v1 = dev.json().path("version").asInt();
        assertThat(estadoEn(v1, enalapril)).isEqualTo("pendiente");
        Map<String, Object> devuelta = jdbc.sql("SELECT devuelto, causa FROM hom_tabla_maestra_version(:v) WHERE origen_id = :o").param("v", v1)
                .param("o", enalapril).query().singleRow();
        assertThat(devuelta.get("devuelto")).isEqualTo(true);
        assertThat((String) devuelta.get("causa")).as("el motivo libre no va a la tabla maestra").startsWith("devuelto al OPL · revisión ")
                .doesNotContain("concentración del insumo");
        assertThat(estadoEn(v0, enalapril)).as("la versión anterior no cambia").isEqualTo("inconsistente");
        assertThat(analista.get("/api/homologacion/devoluciones").json().path("filas").toString()).contains("La concentración del insumo");
        // El COORDINADOR aprueba con el dato del INVIMA: homologado, regla MANUAL, versión N+2 y bitácora con el antes y el después
        String coord = crearUsuario("coordhom", Perfil.COORDINADOR);
        Navegador coordinador = como(coord);
        Navegador.Respuesta ap = coordinador.post("/api/homologacion/revision/" + enalapril + "/aprobar", Map.of("version", v1));
        assertThat(ap.estado()).as(ap.texto()).isEqualTo(200);
        int v2 = ap.json().path("version").asInt();
        assertThat(estadoEn(v2, enalapril)).isEqualTo("homologado");
        Map<String, Object> fila = jdbc.sql("SELECT regla, fin_cum, revision_id, reglas_aplicadas::text FROM hom_tabla_maestra_version(:v) WHERE origen_id = :o")
                .param("v", v2).param("o", enalapril).query().singleRow();
        assertThat(fila.get("regla")).isEqualTo("MANUAL");
        assertThat(fila.get("fin_cum")).isEqualTo(cum(3));
        assertThat(fila.get("revision_id")).isNotNull();
        assertThat((String) fila.get("reglas_aplicadas")).contains("MANUAL");
        // Sin copia: las dos decisiones no agregaron filas a la tabla maestra; la versión completa tiene los mismos datos originales
        assertThat(jdbc.sql("SELECT count(*) FROM hom_tabla_maestra").query(Long.class).single()).isEqualTo(filasAntes);
        assertThat(jdbc.sql("SELECT count(*) FROM hom_tabla_maestra_version(:v)").param("v", v2).query(Long.class).single())
                .isEqualTo(jdbc.sql("SELECT count(*) FROM hom_tabla_maestra_version(:v)").param("v", v0).query(Long.class).single());
        esperar(() -> bitacoraDe(idDe(coord)).stream().anyMatch(l -> l.contains("homologacion_aprobada") && l.contains("pendiente · MANUAL → homologado")),
                "aprobación en la bitácora");
        assertThat(coordinador.post("/api/homologacion/revision/" + enalapril + "/aprobar", Map.of()).estado()).as("ya homologado").isEqualTo(409);
        // Aprobar un dato devuelto cierra su devolución como «resuelta» en revisión (no la corrigió el OPL)
        assertThat(jdbc.sql("SELECT estado FROM hom_devolucion WHERE origen_id = :o ORDER BY id DESC LIMIT 1").param("o", enalapril)
                .query(String.class).single()).isEqualTo("resuelta");
        // Una re-ejecución conserva la decisión humana
        int v3 = reejecutar(analista).path("resultado").path("version").asInt();
        assertThat(estadoEn(v3, enalapril)).isEqualTo("homologado");
        assertThat(jdbc.sql("SELECT regla FROM hom_tabla_maestra_version(:v) WHERE origen_id = :o").param("v", v3).param("o", enalapril)
                .query(String.class).single()).isEqualTo("MANUAL");
    }

    /** Lanza la re-ejecución (202) y espera a que termine; devuelve su estado final. */
    private JsonNode reejecutar(Navegador n) {
        Navegador.Respuesta re = n.post("/api/homologacion/ejecuciones", null);
        assertThat(re.estado()).as(re.texto()).isEqualTo(202);
        assertThat(re.json().path("estado").asText()).isEqualTo("en_curso");
        JsonNode[] fin = {null};
        esperar(() -> {
            fin[0] = n.get("/api/homologacion/ejecuciones/actual").json();
            return !"en_curso".equals(fin[0].path("estado").asText());
        }, "fin de la re-ejecución");
        assertThat(fin[0].path("estado").asText()).as(fin[0].toString()).isEqualTo("terminada");
        return fin[0];
    }

    @Test
    @DisplayName("aprobar un «CUM o registro no válido» sin elegir un CUM vigente: 422 con mensaje claro; con un CUM vencido, 422")
    void aprobarNoValidoSinCum() throws IOException {
        Base b = base();
        Navegador n = como(analista());
        long adalimumab = origenDe(vigente(), cum(28));
        assertThat(estadoEn(vigente(), adalimumab)).isEqualTo("no_valido");
        Navegador.Respuesta r = n.post("/api/homologacion/revision/" + adalimumab + "/aprobar", Map.of());
        assertThat(r.estado()).isEqualTo(422);
        assertThat(r.mensaje()).isEqualTo("El registro tiene «CUM o registro no válido»: elija un CUM vigente del INVIMA para aprobarlo.");
        Navegador.Respuesta vencido = n.post("/api/homologacion/revision/" + adalimumab + "/aprobar", Map.of("cum", cum(28)));
        assertThat(vencido.estado()).isEqualTo(422);
        assertThat(vencido.mensaje()).isEqualTo("El CUM elegido no está vigente en las versiones publicadas del INVIMA.");
        assertThat(estadoEn(vigente(), adalimumab)).isEqualTo("no_valido");
    }

    @Test
    @DisplayName("50 decisiones seguidas no multiplican las filas: cada una es una versión sin copia y un corte anterior no la ve")
    void decisionesSinCopia() throws IOException {
        base();
        Navegador n = como(analista());
        String s = sello();
        List<String[]> filas = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            filas.add(new String[] {"PRODUCTO FICTICIO " + s + " NUMERO " + i, ""});
        }
        insumo(n, s, filas);
        int antes = vigente();
        long filasAntes = jdbc.sql("SELECT count(*) FROM hom_tabla_maestra").query(Long.class).single();
        long versionesAntes = jdbc.sql("SELECT count(*) FROM hom_version").query(Long.class).single();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            long o = origenPorDescripcion("PRODUCTO FICTICIO " + s + " NUMERO " + i);
            ids.add(o);
            assertThat(estadoEn(antes, o)).isEqualTo("sin_correspondencia");
            Navegador.Respuesta d = n.post("/api/homologacion/revision/" + o + "/devolver", Map.of("motivo", "Producto sin CUM ni principio (ficticio)"));
            assertThat(d.estado()).as(d.texto()).isEqualTo(200);
        }
        int despues = vigente();
        assertThat(jdbc.sql("SELECT count(*) FROM hom_tabla_maestra").query(Long.class).single()).as("ninguna fila copiada").isEqualTo(filasAntes);
        assertThat(jdbc.sql("SELECT count(*) FROM hom_version").query(Long.class).single()).isEqualTo(versionesAntes + 50);
        for (long o : ids) {
            assertThat(estadoEn(despues, o)).isEqualTo("pendiente");
            assertThat(estadoEn(antes, o)).as("la versión anterior no ve la decisión").isEqualTo("sin_correspondencia");
        }
        // Reproducibilidad por corte: la versión vigente un instante antes de la primera decisión es la anterior
        assertThat(jdbc.sql("""
                SELECT hom_version_vigente(hv.creada - interval '1 microsecond') FROM hom_revision r JOIN hom_version hv ON hv.id = r.version_id
                WHERE r.origen_id = :o""").param("o", ids.getFirst()).query(Integer.class).single()).isEqualTo(antes);
        // El conteo de la versión completa cuadra con la tabla resuelta
        Map<String, Long> resuelto = new TreeMap<>();
        jdbc.sql("SELECT estado, count(*) FROM hom_tabla_maestra_version(:v) GROUP BY estado").param("v", despues).query(rs -> {
            resuelto.put(rs.getString(1), rs.getLong(2));
        });
        JsonNode conteo = n.get("/api/homologacion/tabla-maestra?version=" + despues).json().path("version").path("conteo");
        resuelto.forEach((e, c) -> assertThat(conteo.path(e).asLong()).as(e).isEqualTo(c));
    }

    @Test
    @DisplayName("incremental: un cargue con un dato original nuevo guarda una sola fila nueva; la versión completa suma ese dato")
    void incremental() throws IOException {
        Base b = base();
        Navegador n = como(analista());
        int antes = vigente();
        long filasAntes = jdbc.sql("SELECT count(*) FROM hom_tabla_maestra").query(Long.class).single();
        long completaAntes = jdbc.sql("SELECT count(*) FROM hom_tabla_maestra_version(:v)").param("v", antes).query(Long.class).single();
        String s = sello();
        // Un dato ya conocido (metformina del generador) y uno nuevo
        JsonNode fin = insumo(n, s, List.of(new String[] {"METFORMINA 850 MG TABLETA", cum(1)}, new String[] {"MEDICAMENTO NUEVO " + s, ""}));
        int v = jdbc.sql("SELECT DISTINCT version_id FROM hom_cargue_origen WHERE cargue_id = :c").param("c", fin.path("id").asLong())
                .query(Integer.class).single();
        assertThat(v).isNotEqualTo(antes).isEqualTo(vigente());
        assertThat(jdbc.sql("SELECT count(*) FROM hom_tabla_maestra WHERE version_id = :v").param("v", v).query(Long.class).single())
                .as("solo el dato nuevo se homologa y se escribe").isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM hom_tabla_maestra").query(Long.class).single()).isEqualTo(filasAntes + 1);
        assertThat(jdbc.sql("SELECT count(*) FROM hom_tabla_maestra_version(:v)").param("v", v).query(Long.class).single()).isEqualTo(completaAntes + 1);
        assertThat(estadoEn(v, origenDe(v, cum(1)))).isEqualTo("homologado");
        // Un cargue que solo trae datos conocidos no crea versión
        JsonNode otro = insumo(n, sello(), List.<String[]>of(new String[] {"METFORMINA 850 MG TABLETA", cum(1)}));
        assertThat(jdbc.sql("SELECT DISTINCT version_id FROM hom_cargue_origen WHERE cargue_id = :c").param("c", otro.path("id").asLong())
                .query(Integer.class).single()).isEqualTo(v);
        assertThat(vigente()).isEqualTo(v);
    }

    @Test
    @DisplayName("atributos del dato original deterministas: con principios distintos gana el más frecuente y, en empate, el menor, en cualquier orden")
    void atributosDeterministas() throws IOException {
        base();
        Navegador n = como(analista());
        String s = sello();
        // Mismo dato con dos principios distintos, dos registros cada uno, en órdenes opuestos en dos datos originales
        String a = "PRODUCTO ORDEN A " + s;
        String z = "PRODUCTO ORDEN Z " + s;
        insumo(n, s, List.of(new String[] {a, "", "10/10/2025", "BETA"}, new String[] {a, "", "11/10/2025", "ALFA"}, new String[] {a, "", "12/10/2025", "BETA"},
                new String[] {a, "", "13/10/2025", "ALFA"}, new String[] {z, "", "10/10/2025", "ALFA"}, new String[] {z, "", "11/10/2025", "BETA"},
                new String[] {z, "", "12/10/2025", "ALFA"}, new String[] {z, "", "13/10/2025", "BETA"}, new String[] {z, "", "14/10/2025", "BETA"}));
        assertThat(jdbc.sql("SELECT principio_activo FROM hom_medicamento_origen WHERE id = :o").param("o", origenPorDescripcion(a)).query(String.class)
                .single()).as("empate 2 a 2: el menor").isEqualTo("ALFA");
        assertThat(jdbc.sql("SELECT principio_activo FROM hom_medicamento_origen WHERE id = :o").param("o", origenPorDescripcion(z)).query(String.class)
                .single()).as("3 contra 2: el más frecuente").isEqualTo("BETA");
    }

    @Test
    @DisplayName("seguimiento: el dato devuelto vuelve igual ⇒ «persiste»; el OPL lo corrige (mismo CUM, otra descripción) ⇒ «corregida» con el cargue")
    void devolucionCorregidaPorElOpl() throws IOException {
        base();
        Navegador n = como(analista());
        String s = sello();
        // Enalapril con 20 mg y el CUM de 10 mg: inconsistente (dato propio de esta prueba, con una grafía distinta del generador)
        String malo = "ENALAPRIL 20 MG TABLETA LOTE " + s;
        insumo(n, s, List.<String[]>of(new String[] {malo, cum(3), "15/09/2025"}));
        long origen = origenPorDescripcion(malo);
        assertThat(estadoEn(vigente(), origen)).isEqualTo("inconsistente");
        assertThat(n.post("/api/homologacion/revision/" + origen + "/devolver", Map.of("motivo", "Concentración distinta del CUM")).estado()).isEqualTo(200);
        Map<String, Object> d0 = jdbc.sql("SELECT id, periodo FROM hom_devolucion WHERE origen_id = :o").param("o", origen).query().singleRow();
        assertThat(d0.get("periodo").toString()).isEqualTo("2025-09-01");
        // El mismo dato otra vez, sin corregir ⇒ persiste
        insumo(n, sello(), List.<String[]>of(new String[] {malo, cum(3), "16/10/2025"}));
        assertThat(jdbc.sql("SELECT estado FROM hom_devolucion WHERE id = :d").param("d", d0.get("id")).query(String.class).single()).isEqualTo("persiste");
        // El OPL corrige la descripción (mismo CUM): llega como otro dato original, homologado ⇒ corregida por ese cargue
        JsonNode corregido = insumo(n, sello(), List.<String[]>of(new String[] {"ENALAPRIL 10 MG TABLETA LOTE " + s, cum(3), "17/10/2025"}));
        Map<String, Object> d = jdbc.sql("SELECT estado, corregida_cargue_id, periodo FROM hom_devolucion WHERE id = :d").param("d", d0.get("id"))
                .query().singleRow();
        assertThat(d.get("estado")).isEqualTo("corregida");
        assertThat(((Number) d.get("corregida_cargue_id")).longValue()).isEqualTo(corregido.path("id").asLong());
        assertThat(d.get("periodo").toString()).as("el periodo de la devolución no cambia").isEqualTo("2025-09-01");
        JsonNode seguimiento = n.get("/api/homologacion/devoluciones").json();
        assertThat(seguimiento.path("correccionesPorMes").toString()).contains("corregidas");
    }

    @Test
    @DisplayName("exportación de devoluciones al OPL completa: con más de 500, el archivo las trae todas (la pantalla muestra 500 y el total)")
    void exportarDevolucionesCompletas() throws IOException {
        base();
        Navegador n = como(analista());
        // Devoluciones sembradas por SQL sobre una revisión existente (la prueba mide la exportación, no la revisión)
        Map<String, Object> rev = jdbc.sql("SELECT r.id, r.origen_id, r.usuario_id FROM hom_revision r ORDER BY r.id LIMIT 1").query().listOfRows().stream()
                .findFirst().orElse(null);
        if (rev == null) {
            long o = origenDe(vigente(), cum(30));
            assertThat(n.post("/api/homologacion/revision/" + o + "/devolver", Map.of("motivo", "Sin correspondencia (ficticio)")).estado()).isEqualTo(200);
            rev = jdbc.sql("SELECT r.id, r.origen_id, r.usuario_id FROM hom_revision r ORDER BY r.id LIMIT 1").query().singleRow();
        }
        for (int i = 0; i < 520; i++) {
            jdbc.sql("INSERT INTO hom_devolucion (origen_id, revision_id, motivo, usuario_id, estado) VALUES (:o, :r, :m, :u, 'devuelta')")
                    .param("o", rev.get("origen_id")).param("r", rev.get("id")).param("m", "Devolución sembrada " + i).param("u", rev.get("usuario_id")).update();
        }
        long total = jdbc.sql("SELECT count(*) FROM hom_devolucion").query(Long.class).single();
        JsonNode pantalla = n.get("/api/homologacion/devoluciones").json();
        assertThat(pantalla.path("filas").size()).isEqualTo(500);
        assertThat(pantalla.path("total").asLong()).isEqualTo(total);
        Navegador.Respuesta csv = n.get("/api/homologacion/devoluciones/exportar?formato=csv");
        assertThat(csv.estado()).isEqualTo(200);
        List<String> lineas = csv.texto().lines().toList();
        int cabecera = 0;
        for (int i = 0; i < lineas.size(); i++) {
            if (lineas.get(i).startsWith("Id;Dato original")) {
                cabecera = i;
            }
        }
        assertThat(lineas.size() - cabecera - 1).as("todas las devoluciones en el archivo").isEqualTo((int) total);
        assertThat(csv.texto()).contains("Registros;" + total);
    }

    @Test
    @DisplayName("concurrencia: con la homologación ocupada, aprobar responde 409 tras la espera y una segunda re-ejecución responde 409")
    void concurrencia() throws Exception {
        base();
        Navegador n = como(analista());
        long o = origenDe(vigente(), cum(30));
        try (var c = ds.getConnection(); var s = c.createStatement()) {
            s.execute("SELECT pg_advisory_lock(" + ServicioHomologacion.CANDADO + ")");
            try {
                Navegador.Respuesta r = n.post("/api/homologacion/revision/" + o + "/devolver", Map.of("motivo", "Ocupado"));
                assertThat(r.estado()).isEqualTo(409);
                assertThat(r.mensaje()).startsWith("Hay una homologación en curso");
                assertThat(n.post("/api/homologacion/ejecuciones", null).estado()).isEqualTo(202);
                Navegador.Respuesta otra = n.post("/api/homologacion/ejecuciones", null);
                assertThat(otra.estado()).isEqualTo(409);
                assertThat(otra.mensaje()).startsWith("Ya hay una re-ejecución en curso");
            } finally {
                s.execute("SELECT pg_advisory_unlock(" + ServicioHomologacion.CANDADO + ")");
            }
        }
        esperar(() -> !"en_curso".equals(n.get("/api/homologacion/ejecuciones/actual").json().path("estado").asText()), "fin de la re-ejecución");
        assertThat(n.get("/api/homologacion/ejecuciones/actual").json().path("estado").asText()).isEqualTo("terminada");
    }

    @Test
    @DisplayName("sugeridor: la respuesta de la IA solo vale si su CUM está entre los candidatos (un CUM inventado se descarta)")
    void sugerenciaValidada() {
        List<SugeridorHomologacion.Candidato> c = List.of(new SugeridorHomologacion.Candidato("19911481-1", "LOSARTAN 50 MG"));
        assertThat(ServicioRevision.validarSugerencia(Optional.of(new SugeridorHomologacion.Sugerencia("19911481-1", "igual", BigDecimal.ONE)), c))
                .isPresent();
        assertThat(ServicioRevision.validarSugerencia(Optional.of(new SugeridorHomologacion.Sugerencia("99999999-9", "inventado", BigDecimal.ONE)), c))
                .isEmpty();
        assertThat(new SugeridorNoDisponible().sugerir("LOSARTAN", c)).isEmpty();
    }

    // ---------- Reglas y re-ejecución ----------

    @Test
    @DisplayName("R-015 en vivo: el comité acepta cápsula ≈ tableta; traza antes/después; re-ejecución N+1 sin tocar los hechos ni la versión N")
    void parametrizacionYReejecucion() throws IOException {
        Base b = base();
        Navegador analista = como(analista());
        String huellaAntes = jdbc.sql("""
                SELECT count(*) || '|' || md5(string_agg(id::text || ':' || coalesce(medicamento_id, 0) || ':' || coalesce(hom_version_id, 0), ',' ORDER BY id))
                FROM ana_dispensaciones WHERE cargue_id = :c""").param("c", b.cargue()).query(String.class).single();
        JsonNode calidadN = analista.get("/api/homologacion/calidad?cargue=" + b.cargue() + "&version=" + b.version()).json();
        // El COORDINADOR no edita reglas
        String coord = crearUsuario("coordregla", Perfil.COORDINADOR);
        assertThat(como(coord).put("/api/homologacion/reglas/R-015", Map.of("parametros", Map.of("tolerancia", "1"))).estado()).isEqualTo(403);
        // Validaciones
        assertThat(analista.put("/api/homologacion/reglas/R-001", Map.of("activa", false)).mensaje()).contains("obligatoria");
        assertThat(analista.put("/api/homologacion/reglas/R-015", Map.of("parametros", Map.of("tolerancia", "7"))).estado()).isEqualTo(422);
        assertThat(analista.put("/api/homologacion/reglas/R-099", Map.of("activa", true)).estado()).isEqualTo(404);
        Navegador.Respuesta cambio = analista.put("/api/homologacion/reglas/R-015", Map.of("parametros", Map.of("tolerancia", "1")));
        assertThat(cambio.estado()).as(cambio.texto()).isEqualTo(200);
        assertThat(cambio.json().path("descripcion").asText()).isEqualTo("Regla R-015 modificada (tolerancia de forma farmacéutica)");
        JsonNode historial = analista.get("/api/homologacion/reglas").json().path("historial").get(0);
        assertThat(historial.path("objeto").asText()).isEqualTo("R-015");
        assertThat(historial.path("antes").asText()).contains("\"tolerancia\": \"0\"");
        assertThat(historial.path("despues").asText()).contains("\"tolerancia\": \"1\"");
        assertThat(historial.path("responsable").asText()).endsWith("· analista");
        esperar(() -> bitacoraDe(idDe(analista())).stream().anyMatch(l -> l.contains("homologacion_regla_modificada")
                && l.contains("Regla R-015 modificada (tolerancia de forma farmacéutica)")), "regla en la bitácora");
        // Edición concurrente: con la versión vieja de la regla, 409 (el segundo en guardar no pisa al primero)
        int versionRegla = cambio.json().path("version").asInt();
        Navegador.Respuesta vieja = analista.put("/api/homologacion/reglas/R-015", Map.of("parametros", Map.of("tolerancia", "2"), "version",
                versionRegla - 1));
        assertThat(vieja.estado()).isEqualTo(409);
        assertThat(vieja.mensaje()).startsWith("Otra persona cambió la regla R-015");
        try {
            JsonNode fin = reejecutar(analista).path("resultado");
            int nueva = fin.path("version").asInt();
            assertThat(fin.path("cambios").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(fin.path("antes").has("inconsistente")).isTrue();
            // Por diferencia: la re-ejecución solo escribe las filas que cambiaron
            assertThat(fin.path("filas").asLong()).isEqualTo(jdbc.sql("SELECT count(*) FROM hom_tabla_maestra WHERE version_id = :v").param("v", nueva)
                    .query(Long.class).single()).isLessThan(jdbc.sql("SELECT count(*) FROM hom_tabla_maestra_version(:v)").param("v", nueva)
                    .query(Long.class).single());
            long losartan = origenDe(nueva, cum(0));
            assertThat(estadoEn(b.version(), losartan)).isEqualTo("inconsistente");
            assertThat(estadoEn(nueva, losartan)).isEqualTo("homologado");
            // Los hechos no cambian (mismo conteo y suma de comprobación) y la versión N sigue dando lo mismo
            String huellaDespues = jdbc.sql("""
                    SELECT count(*) || '|' || md5(string_agg(id::text || ':' || coalesce(medicamento_id, 0) || ':' || coalesce(hom_version_id, 0), ',' ORDER BY id))
                    FROM ana_dispensaciones WHERE cargue_id = :c""").param("c", b.cargue()).query(String.class).single();
            assertThat(huellaDespues).isEqualTo(huellaAntes);
            JsonNode calidadN2 = analista.get("/api/homologacion/calidad?cargue=" + b.cargue() + "&version=" + b.version()).json();
            assertThat(kpis(calidadN2)).isEqualTo(kpis(calidadN));
            JsonNode calidadNueva = analista.get("/api/homologacion/calidad?cargue=" + b.cargue() + "&version=" + nueva).json();
            assertThat(kpis(calidadNueva).get("homologado")).isGreaterThan(kpis(calidadN).get("homologado"));
            assertThat(sumaPorcentajes(calidadNueva)).isEqualByComparingTo("100.00");
            // La versión vigente a una fecha de corte anterior a la re-ejecución es la anterior y da el mismo resultado de entonces
            int previa = jdbc.sql("SELECT hom_version_vigente(creada - interval '1 microsecond') FROM hom_version WHERE id = :v").param("v", nueva)
                    .query(Integer.class).single();
            assertThat(previa).isNotEqualTo(nueva);
            assertThat(estadoEn(previa, losartan)).isEqualTo("inconsistente");
        } finally {
            assertThat(analista.put("/api/homologacion/reglas/R-015", Map.of("parametros", Map.of("tolerancia", "0"))).estado()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("revisión A2: publicar otra versión del CUM después de un cargue deja la tabla maestra desactualizada; tras re-ejecutar se aplica")
    void desactualizadaTrasPublicarCum() throws IOException {
        base();
        Navegador n = como(analista());
        // Punto de partida al día (otras pruebas pudieron cambiar catálogos o reglas)
        reejecutar(n);
        assertThat(n.get("/api/homologacion/resumen").json().path("desactualizada").isNull()).isTrue();
        // Un medicamento con un CUM que el INVIMA de hoy no tiene: el cargue lo homologa con lo de hoy y la tabla sigue al día
        String s = sello();
        insumo(n, s, List.<String[]>of(new String[] {"ZAFIRLUKAST 20 MG TABLETA", "20019991-1"}));
        long zafirlukast = origenPorDescripcion("ZAFIRLUKAST 20 MG TABLETA");
        assertThat(estadoEn(vigente(), zafirlukast)).isNotEqualTo("homologado");
        JsonNode r0 = n.get("/api/homologacion/resumen").json();
        assertThat(r0.path("desactualizada").isNull()).as(r0.path("desactualizada").toString()).isTrue();
        int numeroAntes = r0.path("vigente").path("numero").asInt();
        // Se publica otra versión del CUM vigente (las mismas filas más el zafirlukast, todo ficticio): la tabla queda desactualizada
        String nueva = new String(recurso("cum_vigentes_ejemplo_ficticio.csv"), StandardCharsets.UTF_8).stripTrailing() + "\r\n"
                + "20019991,ZAFIRLUKAST 20 MG TABLETA (EJEMPLO FICTICIO),LABORATORIO FICTICIO S.A.S.,INVIMA 2019M-0019991,01/15/2019,12/31/2030,"
                + "Vigente,20019991,1,30,CAJA X 30 TABLETA,Activo,01/15/2019,,No,U,R03DC01,DESCRIPCION ATC FICTICIA,ORAL,A,ZAFIRLUKAST,mg,20,"
                + "TABLETA,TABLETA,LABORATORIO FICTICIO UNO,FABRICANTE,FABRICAR Y VENDER,\r\n";
        Navegador.Respuesta subida = subirFuente(n, "INVIMA_CUM_VIGENTES", "vigentes_v2.csv", nueva.getBytes(StandardCharsets.UTF_8), "2026-10-16");
        assertThat(subida.estado()).as(subida.texto()).isEqualTo(201);
        int numeroCum = subida.json().path("numero").asInt();
        Navegador.Respuesta pub = n.post("/api/homologacion/versiones/" + subida.json().path("id").asInt() + "/publicar", null);
        assertThat(pub.estado()).as(pub.texto()).isEqualTo(200);
        JsonNode d = n.get("/api/homologacion/resumen").json().path("desactualizada");
        assertThat(d.isObject()).as("desactualizada tras publicar el CUM").isTrue();
        assertThat(d.path("desdeVersion").asInt()).isLessThanOrEqualTo(numeroAntes);
        assertThat(d.path("motivo").asText()).startsWith("Se publicó INVIMA_CUM_VIGENTES v" + numeroCum + " después de la versión ")
                .contains("re-ejecutar la homologación");
        // El COORDINADOR también lo ve (sin poder re-ejecutar)
        assertThat(nuevo("coorddesac", Perfil.COORDINADOR).get("/api/homologacion/resumen").json().path("desactualizada").isObject()).isTrue();
        assertThat(cumFinal(vigente(), zafirlukast)).as("el dato existente conserva su fila hasta re-ejecutar").isNull();
        // Tras la re-ejecución ya no está desactualizada y el dato queda homologado contra el CUM nuevo; como el catálogo SSFM de
        // ejemplo no tiene código para él, R-030 lo deja «pendiente» (sin código interno SSFM), no «homologado»
        int v = reejecutar(n).path("resultado").path("version").asInt();
        assertThat(n.get("/api/homologacion/resumen").json().path("desactualizada").isNull()).isTrue();
        assertThat(cumFinal(v, zafirlukast)).isEqualTo("20019991-1");
        Map<String, Object> fila = jdbc.sql("SELECT estado, fuente_codigo, causa FROM hom_tabla_maestra_version(:v) WHERE origen_id = :o")
                .param("v", v).param("o", zafirlukast).query().singleRow();
        assertThat(fila.get("fuente_codigo")).isEqualTo("INVIMA_CUM_VIGENTES");
        assertThat(fila.get("estado")).isEqualTo("pendiente");
        assertThat((String) fila.get("causa")).contains("sin código interno SSFM");
    }

    private String cumFinal(int version, long origen) {
        return jdbc.sql("SELECT fin_cum FROM hom_tabla_maestra_version(:v) WHERE origen_id = :o").param("v", version).param("o", origen)
                .query().singleRow().get("fin_cum") instanceof String s ? s : null;
    }

    @Test
    @DisplayName("catálogo de sinónimos DCI: versión nueva completa con traza; entradas normalizadas; unidad canónica validada")
    void catalogoEditable() throws IOException {
        Base b = base();
        Navegador n = como(analista());
        JsonNode c = n.get("/api/homologacion/catalogos/sinonimo_dci").json();
        int version = c.path("version").asInt();
        List<Map<String, Object>> entradas = new ArrayList<>();
        c.path("entradas").forEach(e -> entradas.add(Map.of("clave", e.path("clave").asText(), "valor", e.path("valor").asText())));
        entradas.add(Map.of("clave", "aspirina", "valor", "Ácido acetilsalicílico"));
        Navegador.Respuesta r = n.put("/api/homologacion/catalogos/sinonimo_dci", Map.of("entradas", entradas));
        assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        assertThat(r.json().path("version").asInt()).isEqualTo(version + 1);
        assertThat(r.json().path("descripcion").asText()).contains("+ASPIRINA ⇒ ACIDO ACETILSALICILICO");
        assertThat(n.get("/api/homologacion/catalogos/sinonimo_dci").texto()).contains("ASPIRINA");
        // Con la versión que se abrió (ya vieja): 409, no pisa el cambio anterior
        Navegador.Respuesta vieja = n.put("/api/homologacion/catalogos/sinonimo_dci", Map.of("entradas", entradas.subList(0, 1), "version", version));
        assertThat(vieja.estado()).isEqualTo(409);
        assertThat(n.get("/api/homologacion/catalogos/sinonimo_dci").texto()).contains("ASPIRINA");
        assertThat(n.put("/api/homologacion/catalogos/unidad", Map.of("entradas", List.of(Map.of("clave", "KG", "valor", "KG")))).estado()).isEqualTo(422);
        assertThat(n.get("/api/homologacion/catalogos/otro").estado()).isEqualTo(404);
    }

    @Test
    @DisplayName("los catálogos sembrados por V61 son los de la regla pura (Configuracion.porDefecto)")
    void catalogosSembradosIgualesALaRegla() {
        var c = co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.Configuracion.porDefecto();
        Map<String, String> abreviaturas = new TreeMap<>();
        jdbc.sql("SELECT clave, valor FROM hom_catalogo WHERE tipo = 'abreviatura' AND version = 1").query(rs -> {
            abreviaturas.put(rs.getString(1), rs.getString(2));
        });
        assertThat(abreviaturas).isEqualTo(new TreeMap<>(c.abreviaturas()));
        assertThat(jdbc.sql("SELECT count(*) FROM hom_catalogo WHERE tipo = 'forma' AND version = 1").query(Long.class).single())
                .isEqualTo(c.formas().size());
        assertThat(jdbc.sql("SELECT count(*) FROM hom_catalogo WHERE tipo = 'sal' AND version = 1").query(Long.class).single()).isEqualTo(c.sales().size());
        assertThat(jdbc.sql("SELECT id FROM hom_regla ORDER BY orden").query(String.class).list())
                .isEqualTo(co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.REGLAS.stream().map(d -> d.id()).toList());
    }

    // ---------- Perfiles y exportación ----------

    @Test
    @DisplayName("perfiles: DIGSA, SANIDAD, ESM, AUDITOR y OPL reciben 403; el COORDINADOR consulta pero no carga fuentes, reglas ni re-ejecuta")
    void perfiles() throws IOException {
        base();
        for (Perfil p : List.of(Perfil.DIGSA, Perfil.SANIDAD, Perfil.ESM, Perfil.AUDITOR, Perfil.OPL_NACIONAL, Perfil.OPL_REGIONAL)) {
            Navegador n = nuevo("sinhom" + p.id(), p);
            assertThat(n.get("/api/homologacion/resumen").estado()).as(p.name()).isEqualTo(403);
            assertThat(n.get("/api/homologacion/tabla-maestra").estado()).as(p.name()).isEqualTo(403);
        }
        Navegador c = nuevo("coordperf", Perfil.COORDINADOR);
        assertThat(c.get("/api/homologacion/resumen").estado()).isEqualTo(200);
        assertThat(c.get("/api/homologacion/resumen").json().path("puedeOperar").asBoolean()).isFalse();
        assertThat(c.get("/api/homologacion/tabla-maestra").estado()).isEqualTo(200);
        assertThat(c.put("/api/homologacion/reglas/R-015", Map.of("parametros", Map.of("tolerancia", "1"))).estado()).isEqualTo(403);
        assertThat(c.post("/api/homologacion/ejecuciones", null).estado()).isEqualTo(403);
        assertThat(subirFuente(c, "INVIMA_VITALES", "v.csv", "a,b\r\n1,2\r\n".getBytes(StandardCharsets.UTF_8), "2026-10-01").estado()).isEqualTo(403);
        assertThat(c.put("/api/homologacion/catalogos/sal", Map.of("entradas", List.of(Map.of("clave", "X", "valor", "X")))).estado()).isEqualTo(403);
        Navegador adm = nuevo("admhom", Perfil.ADMINISTRADOR);
        assertThat(adm.get("/api/homologacion/resumen").json().path("puedeOperar").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("exportar: el COORDINADOR descarga la versión en CSV (una fila por dato original, BOM, «;») y en Excel; queda en la bitácora")
    void exportarCsv() throws IOException {
        Base b = base();
        String coord = crearUsuario("coordexphom", Perfil.COORDINADOR);
        Navegador n = como(coord);
        Navegador.Respuesta r = n.get("/api/homologacion/tabla-maestra/exportar?version=" + b.version() + "&formato=csv");
        assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        assertThat(r.cabecera("Content-Type")).startsWith("text/csv");
        assertThat(r.cabecera("Content-Disposition")).contains("tabla_maestra_homologacion.csv");
        String texto = r.texto();
        assertThat(texto).startsWith("﻿").contains("Versión de la tabla maestra").contains("INVIMA_CUM_VIGENTES v1 (corte 2026-10-16, datos de ejemplo)");
        long filas = jdbc.sql("SELECT count(*) FROM hom_tabla_maestra_version(:v)").param("v", b.version()).query(Long.class).single();
        long total = n.get("/api/homologacion/tabla-maestra?version=" + b.version()).json().path("total").asLong();
        assertThat(total).isEqualTo(filas);
        List<String> lineas = texto.lines().toList();
        int cabecera = 0;
        for (int i = 0; i < lineas.size(); i++) {
            if (lineas.get(i).startsWith("Id del dato original;")) {
                cabecera = i;
            }
        }
        assertThat(lineas.size() - cabecera - 1).as("una fila por dato original").isEqualTo((int) filas);
        assertThat(texto).contains(";Metformina;").contains("R-004");
        Navegador.Respuesta x = n.get("/api/homologacion/tabla-maestra/exportar?version=" + b.version() + "&formato=xlsx");
        assertThat(x.estado()).isEqualTo(200);
        assertThat(x.cabecera("Content-Type")).startsWith("application/vnd.openxmlformats");
        assertThat(n.get("/api/homologacion/tabla-maestra/exportar?formato=pdf").estado()).isEqualTo(422);
        esperar(() -> bitacoraDe(idDe(coord)).stream().anyMatch(l -> l.contains("homologacion_exportada")), "exportación en la bitácora");
        Navegador.Respuesta cal = n.get("/api/homologacion/calidad/exportar?cargue=" + b.cargue() + "&formato=csv");
        assertThat(cal.estado()).isEqualTo(200);
        assertThat(cal.texto()).contains("Tablero de calidad de la homologación").contains("Homologado");
    }

    @Test
    @DisplayName("trazabilidad del módulo: fuentes publicadas, ejecución del cargue (Proceso automático) y usuario con su perfil")
    void trazabilidad() throws IOException {
        Base b = base();
        JsonNode t = como(analista()).get("/api/homologacion/trazabilidad?limite=200").json();
        String s = t.toString();
        assertThat(s).contains("homologacion_fuente_publicada").contains("homologacion_ejecutada").contains("analitica_cargue_aplicado");
        boolean conPerfil = false;
        for (JsonNode e : t) {
            if ("homologacion_fuente_publicada".equals(e.path("tipo").asText())) {
                conPerfil |= e.path("perfil").asText().contains("Analista");
            }
        }
        assertThat(conPerfil).isTrue();
    }
}
