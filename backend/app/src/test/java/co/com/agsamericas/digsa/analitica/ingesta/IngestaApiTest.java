package co.com.agsamericas.digsa.analitica.ingesta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.com.agsamericas.digsa.analitica.GeneradorSintetico;
import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Ingesta del insumo del OPL por la API (docs/especificaciones/analitica-ingesta): subida por partes con SHA-256 y tamaño exacto, mapeo
 * por sinónimos con vista previa enmascarada, cargue con los errores sembrados del generador sintético, bitácora de 8 campos, cuadre de
 * los hechos contra los totales calculados aparte, idempotencia (duplicado al cerrar y en cola), perfiles, seudónimo (ningún documento
 * en claro, tampoco en staging mientras se procesa), rechazo estructural, falla sin efecto, cola de uno, proceso interrumpido, cargues
 * abandonados que vencen, descarte, «Reemplazar el periodo» con periodo declarado y servicio web del OPL (lotes durables 1..N).
 */
class IngestaApiTest extends PruebaIntegracion {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int FILAS_CUADRE = 20_000;
    /** Parte de la subida en las pruebas (digsa.analitica.parte-max-mb: 1 en application-test.yml). */
    static final int PARTE = 1024 * 1024;
    /** Cargue base compartido por varias pruebas (el contexto y la base son los mismos): se carga una vez. */
    private static volatile Base base;

    private record Base(long id, GeneradorSintetico.Resultado esperado, byte[] archivo, String analista) {
    }

    @Autowired
    AplicadorHechos aplicador;
    @Autowired
    MantenimientoCargues mantenimiento;
    @Autowired
    ProcesadorCargues procesador;
    @Autowired
    Almacen almacen;
    @Autowired
    javax.sql.DataSource ds;

    @AfterEach
    void sinGancho() {
        aplicador.trasInsertar = id -> { };
    }

    // ---------- Apoyo ----------

    private Navegador como(String prefijo, Perfil... perfiles) {
        Navegador n = navegador();
        assertThat(n.ingresar(crearUsuario(prefijo, perfiles), CLAVE).estado()).isEqualTo(200);
        return n;
    }

    static int partes(byte[] datos) {
        return (datos.length + PARTE - 1) / PARTE;
    }

    static Navegador.Respuesta crear(Navegador n, String nombre, byte[] datos, String shaDeclarado) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("nombre", nombre);
        s.put("tamano", datos.length);
        s.put("sha256", shaDeclarado == null ? Huellas.sha256(datos) : shaDeclarado);
        s.put("partes", partes(datos));
        return n.post("/api/analitica/cargues", s);
    }

    static void enviarPartes(Navegador n, long id, byte[] datos) {
        for (int p = 0; p < partes(datos); p++) {
            byte[] parte = Arrays.copyOfRange(datos, p * PARTE, Math.min(datos.length, (p + 1) * PARTE));
            Navegador.Respuesta r = n.putBinario("/api/analitica/cargues/" + id + "/partes/" + (p + 1), parte);
            assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        }
    }

    /** Crea, sube en partes de 1 MB (la última con el resto) y cierra. Devuelve la respuesta del cierre (o de la creación si ya existía). */
    static Navegador.Respuesta subir(Navegador n, String nombre, byte[] datos, String shaDeclarado) {
        Navegador.Respuesta c = crear(n, nombre, datos, shaDeclarado);
        if (c.estado() != 201) {
            return c;
        }
        long id = c.json().path("id").asLong();
        enviarPartes(n, id, datos);
        return n.post("/api/analitica/cargues/" + id + "/cerrar", null);
    }

    static List<String> propuesta(Navegador n, long id) {
        Navegador.Respuesta m = n.get("/api/analitica/cargues/" + id + "/mapeo");
        assertThat(m.estado()).as(m.texto()).isEqualTo(200);
        List<String> l = new ArrayList<>();
        m.json().path("propuesta").forEach(x -> l.add(x.asText()));
        return l;
    }

    static Navegador.Respuesta confirmar(Navegador n, long id, List<String> columnas, String modo, boolean plantilla) {
        return confirmar(n, id, columnas, modo, plantilla, null, null);
    }

    static Navegador.Respuesta confirmar(Navegador n, long id, List<String> columnas, String modo, boolean plantilla, String desde, String hasta) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("columnas", columnas);
        c.put("modo", modo);
        c.put("guardarPlantilla", plantilla);
        c.put("nombrePlantilla", plantilla ? "Insumo sintético" : null);
        c.put("periodoDesde", desde);
        c.put("periodoHasta", hasta);
        return n.post("/api/analitica/cargues/" + id + "/mapeo", c);
    }

    private static final List<String> TERMINALES = List.of("aplicado", "rechazado", "fallido", "duplicado", "vencido", "descartado");

    /** Espera (hasta 3 min) a que el cargue termine: aplicado, rechazado, fallido o duplicado. */
    static JsonNode esperarFin(Navegador n, long id) {
        long limite = System.nanoTime() + 180_000_000_000L;
        while (true) {
            JsonNode e = n.get("/api/analitica/cargues/" + id).json();
            if (TERMINALES.contains(e.path("estado").asText())) {
                return e;
            }
            if (System.nanoTime() > limite) {
                throw new AssertionError("El cargue " + id + " no terminó: " + e);
            }
            pausa(200);
        }
    }

    private static void pausa(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    /** Carga completa por la API: subir, mapear con la propuesta y esperar el fin. */
    static JsonNode cargar(Navegador n, String nombre, byte[] datos, String modo) {
        Navegador.Respuesta c = subir(n, nombre, datos, null);
        assertThat(c.estado()).as(c.texto()).isEqualTo(200);
        long id = c.json().path("id").asLong();
        assertThat(c.json().path("estado").asText()).isEqualTo("mapeo");
        Navegador.Respuesta r = confirmar(n, id, propuesta(n, id), modo, false);
        assertThat(r.estado()).as(r.texto()).isEqualTo(202);
        return esperarFin(n, id);
    }

    private Base base() throws IOException {
        Base b = base;
        if (b != null) {
            return b;
        }
        synchronized (IngestaApiTest.class) {
            if (base == null) {
                Path dir = Files.createTempDirectory("digsa-sintetico-");
                GeneradorSintetico.Resultado r = GeneradorSintetico.generar(dir, "sintetico", FILAS_CUADRE, 777L);
                byte[] datos = Files.readAllBytes(r.csv());
                assertThat(partes(datos)).as("el archivo base se sube en varias partes").isGreaterThan(1);
                String analista = crearUsuario("analista", Perfil.ANALISTA);
                Navegador n = navegador();
                assertThat(n.ingresar(analista, CLAVE).estado()).isEqualTo(200);
                Navegador.Respuesta c = subir(n, "insumo_sintetico_2025.csv", datos, null);
                assertThat(c.estado()).as(c.texto()).isEqualTo(200);
                long id = c.json().path("id").asLong();
                Navegador.Respuesta conf = confirmar(n, id, propuesta(n, id), "agregar", true);
                assertThat(conf.estado()).as(conf.texto()).isEqualTo(202);
                JsonNode fin = esperarFin(n, id);
                assertThat(fin.path("estado").asText()).as(fin.toString()).isEqualTo("aplicado");
                n.post("/api/auth/salir", null);
                base = new Base(id, r, datos, analista);
            }
            return base;
        }
    }

    private long contar(String sql, Object... params) {
        var q = jdbc.sql(sql);
        for (int i = 0; i < params.length; i += 2) {
            q = q.param((String) params[i], params[i + 1]);
        }
        return q.query(Long.class).single();
    }

    private String estado(long id) {
        return jdbc.sql("SELECT estado FROM ana_cargues WHERE id = :c").param("c", id).query(String.class).single();
    }

    private static String fila(String doc, String fecha) {
        return "CC;" + doc + ";EJC;BAS02;Dispensación;" + fecha + ";20010037-1;30;5415,00";
    }

    private static final String ENC_CORTO = "TIPO_DOC;NUM_DOC;FUERZA;COD_ESM;MODALIDAD;FECHA_DISPENSACION;CUM;CANTIDAD;VALOR_TOTAL";

    // ---------- Subida ----------

    @Test
    @DisplayName("subida por partes: crea, recibe las partes de tamaño exacto, verifica el SHA-256 y pasa a «mapeo»; las partes se borran")
    void subidaPorPartes() {
        Navegador n = como("analistapartes", Perfil.ANALISTA);
        StringBuilder sb = new StringBuilder("CUM;CANTIDAD\r\n");
        for (int i = 0; sb.length() < 2 * PARTE + 500; i++) {
            sb.append("19901234-").append(i % 99).append(";").append(i).append("\r\n");
        }
        byte[] datos = sb.toString().getBytes(StandardCharsets.UTF_8);
        assertThat(partes(datos)).isEqualTo(3);
        Navegador.Respuesta c = subir(n, "pequeno.csv", datos, null);
        assertThat(c.estado()).as(c.texto()).isEqualTo(200);
        assertThat(c.json().path("estado").asText()).isEqualTo("mapeo");
        assertThat(c.json().path("sha256").asText()).isEqualTo(Huellas.sha256(datos));
        assertThat(c.json().path("tamano").asLong()).isEqualTo(datos.length);
        assertThat(c.json().path("fuenteTexto").asText()).isEqualTo("Archivo CSV (;) UTF-8");
        long id = c.json().path("id").asLong();
        assertThat(contar("SELECT count(*) FROM ana_cargue_partes WHERE cargue_id = :c", "c", id)).as("las partes se borran al ensamblar").isZero();
        assertThat(n.delete("/api/analitica/cargues/" + id).estado()).isEqualTo(200);
    }

    @Test
    @DisplayName("partes: el número debe ser ceil(tamaño / 1 MB) y cada parte de tamaño exacto; máximo 3 cargues abiertos por usuario")
    void partesExactasYCarguesAbiertos() {
        Navegador n = como("analistaexacta", Perfil.ANALISTA);
        byte[] datos = IngestaApiTest.csv(ENC_CORTO, fila("75000001", "10/05/2025"));
        Map<String, Object> s = new LinkedHashMap<>(Map.of("nombre", "x.csv", "tamano", datos.length, "sha256", Huellas.sha256(datos), "partes", 2));
        Navegador.Respuesta mal = n.post("/api/analitica/cargues", s);
        assertThat(mal.estado()).isEqualTo(422);
        assertThat(mal.mensaje()).startsWith("Número de partes no válido: son 1 partes");
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Navegador.Respuesta c = crear(n, "abierto" + i + ".csv", (i + "-" + new String(datos, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8), null);
            assertThat(c.estado()).as(c.texto()).isEqualTo(201);
            ids.add(c.json().path("id").asLong());
        }
        Navegador.Respuesta cuarto = crear(n, "cuarto.csv", datos, null);
        assertThat(cuarto.estado()).isEqualTo(409);
        assertThat(cuarto.mensaje()).contains("3 cargues sin terminar");
        // Parte de otro tamaño: 422 sin dejar nada en el almacén ni en la tabla
        Navegador.Respuesta corta = n.putBinario("/api/analitica/cargues/" + ids.getFirst() + "/partes/1", Arrays.copyOf(datos, 10));
        assertThat(corta.estado()).isEqualTo(422);
        assertThat(corta.mensaje()).contains("debe tener");
        assertThat(contar("SELECT count(*) FROM ana_cargue_partes WHERE cargue_id = :c", "c", ids.getFirst())).isZero();
        for (long id : ids) {
            assertThat(n.delete("/api/analitica/cargues/" + id).estado()).isEqualTo(200);
        }
        assertThat(crear(n, "ahora.csv", datos, null).estado()).as("descartados ya no cuentan como abiertos").isEqualTo(201);
    }

    @Test
    @DisplayName("huella distinta: 422 «El archivo llegó incompleto o alterado; vuelva a subirlo» y el cargue queda «fallido»")
    void huellaDistinta() {
        Navegador n = como("analistahuella", Perfil.ANALISTA);
        byte[] datos = csv("CUM;CANTIDAD", "19901234-1;2");
        Navegador.Respuesta r = subir(n, "alterado.csv", datos, "0".repeat(64));
        assertThat(r.estado()).isEqualTo(422);
        assertThat(r.mensaje()).isEqualTo("El archivo llegó incompleto o alterado; vuelva a subirlo");
        assertThat(jdbc.sql("SELECT estado FROM ana_cargues WHERE sha256_declarado = :s ORDER BY id DESC LIMIT 1").param("s", "0".repeat(64))
                .query(String.class).single()).isEqualTo("fallido");
    }

    // ---------- Mapeo y privacidad ----------

    @Test
    @DisplayName("mapeo por sinónimos del encabezado del generador: personales se descartan; falta cantidad ⇒ 422 y sigue en «mapeo»")
    void mapeoYMapeoIncompleto() {
        Navegador n = como("analistamapeo", Perfil.ANALISTA);
        byte[] datos = csv("DOC_PACIENTE;FZA;COD_ESM;MOD;FEC_DISP;DESC_MED;CUM;VR_TOTAL;NOMBRE_PACIENTE",
                "CC 1.023.456;EJC;BAS02;Dispensación;14/03/2025;LOSARTAN;20010037-1;1000,00;JUAN FICTICIO");
        Navegador.Respuesta c = subir(n, "sin_cantidad.csv", datos, null);
        long id = c.json().path("id").asLong();
        JsonNode m = n.get("/api/analitica/cargues/" + id + "/mapeo").json();
        List<String> p = new ArrayList<>();
        m.path("propuesta").forEach(x -> p.add(x.asText()));
        assertThat(p).containsExactly("paciente", "fuerza", "esm", "modalidad", "fecha", "medicamento", "cum", "valor", "descartar");
        String vista = m.path("vistaPrevia").toString();
        assertThat(vista).as("vista previa enmascarada").doesNotContain("1.023.456").doesNotContain("JUAN").contains("seud. ").contains("LOSARTAN");
        assertThat(m.path("problemas").toString()).contains("Falta asignar la columna de cantidad");
        Navegador.Respuesta r = confirmar(n, id, p, "agregar", false);
        assertThat(r.estado()).isEqualTo(422);
        assertThat(r.mensaje()).isEqualTo("Falta asignar la columna de cantidad");
        assertThat(n.get("/api/analitica/cargues/" + id).json().path("estado").asText()).isEqualTo("mapeo");
    }

    @Test
    @DisplayName("vista previa: toda columna no asignada a un campo no personal va enmascarada (encabezados no reconocidos incluidos)")
    void vistaPreviaEnmascarada() {
        Navegador n = como("analistavista", Perfil.ANALISTA);
        byte[] datos = csv("IDENT;FZA;COD_ESM;MOD;FEC_DISP;DESC_MED;CUM;CANTIDAD;VR_TOTAL;NOM_AFIL",
                "CC 55.443.322;EJC;BAS02;Dispensación;14/03/2025;LOSARTAN;20010037-1;30;1000,00;LUISA FICTICIA");
        long id = subir(n, "raros.csv", datos, null).json().path("id").asLong();
        Navegador.Respuesta m = n.get("/api/analitica/cargues/" + id + "/mapeo");
        assertThat(m.texto()).doesNotContain("55.443.322").doesNotContain("LUISA").contains("(oculto)").contains("LOSARTAN");
        // Con el mapeo de la pantalla: IDENT como paciente ⇒ seudónimo; NOM_AFIL asignada a especialidad ⇒ sigue oculta si es personal
        String columnas = "paciente,fuerza,esm,modalidad,fecha,medicamento,cum,cantidad,valor,descartar";
        Navegador.Respuesta v = n.get("/api/analitica/cargues/" + id + "/vista-previa?columnas=" + columnas);
        assertThat(v.estado()).isEqualTo(200);
        assertThat(v.texto()).doesNotContain("55.443.322").doesNotContain("LUISA").contains("seud. ").contains("(dato personal)");
        assertThat(n.delete("/api/analitica/cargues/" + id).estado()).isEqualTo(200);
    }

    @Test
    @DisplayName("privacidad del mapeo: una columna personal (NOMBRE_PACIENTE) asignada a un campo de texto ⇒ 422")
    void columnaPersonalACampoDeTexto() {
        Navegador n = como("analistatexto", Perfil.ANALISTA);
        byte[] datos = csv("NUM_DOC;FUERZA;COD_ESM;MODALIDAD;FECHA_DISPENSACION;CUM;CANTIDAD;VALOR_TOTAL;NOMBRE_PACIENTE",
                "CC76000001;EJC;BAS02;Dispensación;10/05/2025;20010037-1;30;5415,00;MARTA FICTICIA");
        long id = subir(n, "texto.csv", datos, null).json().path("id").asLong();
        List<String> p = new ArrayList<>(propuesta(n, id));
        p.set(8, "especialidad");
        Navegador.Respuesta r = confirmar(n, id, p, "agregar", false);
        assertThat(r.estado()).isEqualTo(422);
        assertThat(r.mensaje()).contains("«NOMBRE_PACIENTE» tiene datos personales");
        assertThat(estado(id)).isEqualTo("mapeo");
        assertThat(n.delete("/api/analitica/cargues/" + id).estado()).isEqualTo(200);
    }

    @Test
    @DisplayName("primera fila sin encabezado reconocible: se guardan «Columna 1..n», nunca sus valores, con un aviso")
    void encabezadoNoReconocido() {
        Navegador n = como("analistasinenc", Perfil.ANALISTA);
        byte[] datos = csv("CC 66.554.433;ROSA FICTICIA;EJC;BAS02", "CC 66.554.434;OTRA FICTICIA;EJC;BAS02");
        Navegador.Respuesta c = subir(n, "sin_encabezado.csv", datos, null);
        assertThat(c.estado()).as(c.texto()).isEqualTo(200);
        long id = c.json().path("id").asLong();
        JsonNode m = n.get("/api/analitica/cargues/" + id + "/mapeo").json();
        assertThat(m.path("encabezados").toString()).isEqualTo("[\"Columna 1\",\"Columna 2\",\"Columna 3\",\"Columna 4\"]");
        assertThat(m.path("encabezadoReconocido").asBoolean()).isFalse();
        assertThat(m.path("avisos").toString()).contains("no trae encabezados reconocibles");
        assertThat(m.toString()).doesNotContain("66.554").doesNotContain("FICTICIA");
        assertThat(contar("SELECT count(*) FROM ana_cargues x WHERE x::text LIKE '%66.554%' OR x::text LIKE '%FICTICIA%'")).isZero();
        assertThat(n.delete("/api/analitica/cargues/" + id).estado()).isEqualTo(200);
    }

    @Test
    @DisplayName("columnas corridas («;» dentro del nombre): la fila llega a staging sin ningún valor; nombre, documento y nacimiento nunca")
    void columnasCorridasSinPersonales() {
        Navegador n = como("analistacorrida", Perfil.ANALISTA);
        String enc = "TIPO_DOC;NUM_DOC;NOMBRE_PACIENTE;FECHA_NAC;FUERZA;COD_ESM;MODALIDAD;FECHA_DISPENSACION;CUM;CANTIDAD;VALOR_TOTAL";
        List<String> filas = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            filas.add("CC;" + (77_000_000 + i) + ";NOMBRE QXFICTICIO;01/01/1970;EJC;BAS02;Dispensación;12/05/2025;20010037-1;30;5415,00");
        }
        filas.add("CC;88776655;ZQPEREZ;ZQANA;14/03/1980;EJC;BAS02;Dispensación;12/05/2025;20010037-1;31;5415,00");
        AtomicReference<String> visto = new AtomicReference<>();
        // Las columnas de texto (no las huellas en hexadecimal, donde unos dígitos pueden aparecer por azar) y los seudónimos con su forma
        aplicador.trasInsertar = id -> visto.set(jdbc.sql("""
                WITH t AS (SELECT concat_ws('|', x.linea_origen, x.anio_nacimiento, x.grupo_edad, x.fuerza, x.esm, x.modalidad, x.fecha,
                                            x.medicamento, x.cum, x.cum_original, x.principio_activo, x.atc, x.cie10, x.cantidad, x.valor,
                                            x.valor_unitario, x.codigo_ssfm, x.especialidad, x.tipo_formula, x.numero_formula, x.id_dispensacion,
                                            x.item, x.regional) AS v, x.paciente_seudo, x.campos_distintos
                           FROM ana_staging_dispensacion x WHERE x.cargue_id = :c)
                SELECT count(*) FILTER (WHERE v ~ '(ZQPEREZ|ZQANA|88776655|14/03/1980|1980-03-14|QXFICTICIO|7700)'
                                          OR (paciente_seudo IS NOT NULL AND paciente_seudo !~ '^[0-9a-f]{64}$'))
                       || '|' || count(*) || '|' || count(*) FILTER (WHERE campos_distintos)
                FROM t""").param("c", id).query(String.class).single());
        JsonNode fin = cargar(n, "corridas_personales.csv", csv(enc, filas.toArray(String[]::new)), "agregar");
        assertThat(fin.path("estado").asText()).as(fin.toString()).isEqualTo("aplicado");
        assertThat(visto.get()).as("personales en staging | filas | corridas").isEqualTo("0|151|1");
        assertThat(fin.path("rechazados").asLong()).isEqualTo(1);
        assertThat(fin.path("motivos").toString()).contains("campos_distintos");
    }

    @Test
    @DisplayName("seudónimo: ningún documento sembrado aparece en staging mientras se procesa, ni en tablas, ni en las respuestas de la API")
    void sinDocumentoEnClaro() throws IOException {
        Base b = base();
        // Otro insumo del generador: el staging se revisa desde el gancho, con la partición llena (antes de borrarse)
        GeneradorSintetico.Resultado g = GeneradorSintetico.generar(Files.createTempDirectory("digsa-staging-"), "staging", 1_500, 4242L);
        List<String> docs = g.documentos();
        assertThat(docs).hasSize(50);
        String[] patrones = docs.stream().map(d -> "%" + d + "%").toArray(String[]::new);
        AtomicReference<Long> enStaging = new AtomicReference<>();
        aplicador.trasInsertar = id -> enStaging.set(jdbc.sql("""
                SELECT count(*) FROM ana_staging_dispensacion x WHERE x.cargue_id = :c AND x::text LIKE ANY(:p)""")
                .param("c", id).param("p", patrones).query(Long.class).single());
        Navegador n = como("analistastaging", Perfil.ANALISTA);
        Navegador.Respuesta c = subir(n, "staging.csv", Files.readAllBytes(g.csv()), null);
        long id = c.json().path("id").asLong();
        Navegador.Respuesta mapeo = n.get("/api/analitica/cargues/" + id + "/mapeo");
        Navegador.Respuesta conf = confirmar(n, id, propuesta(n, id), "agregar", false);
        assertThat(conf.estado()).as(conf.texto()).isEqualTo(202);
        JsonNode fin = esperarFin(n, id);
        assertThat(fin.path("estado").asText()).as(fin.toString()).isEqualTo("aplicado");
        assertThat(enStaging.get()).as("documentos en claro en staging durante el proceso").isZero();
        // Respuestas de la API (detalle, bitácora, CSV de la bitácora, mapeo)
        List<String> respuestas = List.of(mapeo.texto(), n.get("/api/analitica/cargues/" + id).texto(), n.get("/api/analitica/cargues").texto(),
                n.get("/api/analitica/cargues/bitacora.csv").texto(), n.get("/api/analitica/cargues/" + b.id()).texto());
        for (String d : docs) {
            assertThat(respuestas).noneMatch(r -> r.contains(d));
        }
        for (String d : b.esperado().documentos()) {
            assertThat(respuestas).noneMatch(r -> r.contains(d));
        }
        // Tablas (todos los documentos sembrados de los dos insumos)
        List<String> todos = new ArrayList<>(docs);
        todos.addAll(b.esperado().documentos());
        String[] p2 = todos.stream().map(d -> "%" + d + "%").toArray(String[]::new);
        for (String t : List.of("ana_dispensaciones", "ana_cargue_rechazos", "ana_cargues", "ana_mapeo_plantillas", "ana_ws_lotes", "ana_llaves",
                "ana_staging_dispensacion", "log_transacciones")) {
            assertThat(jdbc.sql("SELECT count(*) FROM " + t + " x WHERE x::text LIKE ANY(:p)").param("p", p2).query(Long.class).single())
                    .as(t + " contiene un documento en claro").isZero();
        }
        assertThat(contar("SELECT count(*) FROM ana_dispensaciones WHERE cargue_id = :c AND paciente_seudo !~ '^[0-9a-f]{64}$'", "c", b.id())).isZero();
    }

    // ---------- Cargue base ----------

    @Test
    @DisplayName("M8-3: cargue de un año con errores sembrados: recibidos = procesados + rechazados, causas esperadas y bitácora de 8 campos")
    void cargueConErroresSembrados() throws IOException {
        Base b = base();
        Navegador n = como("coordbitacora", Perfil.COORDINADOR);
        JsonNode lista = n.get("/api/analitica/cargues?estado=aplicado").json();
        JsonNode fila = null;
        for (JsonNode f : lista.path("filas")) {
            if (f.path("id").asLong() == b.id()) {
                fila = f;
            }
        }
        assertThat(fila).as("el cargue está en la bitácora").isNotNull();
        GeneradorSintetico.Resultado e = b.esperado();
        // Los ocho atributos de 4.7.1 punto 3, más SHA-256 y duraciones
        assertThat(fila.path("aplicadoEn").asText()).isNotBlank();
        assertThat(fila.path("fuenteTexto").asText()).isEqualTo("Archivo CSV (;) UTF-8");
        assertThat(fila.path("periodoDesde").asText()).isEqualTo("2025-01-01");
        assertThat(fila.path("periodoHasta").asText()).isEqualTo("2025-12-31");
        assertThat(fila.path("recibidos").asLong()).isEqualTo(e.recibidos());
        assertThat(fila.path("procesados").asLong()).isEqualTo(e.procesados());
        assertThat(fila.path("rechazados").asLong()).isEqualTo(e.rechazados());
        assertThat(fila.path("recibidos").asLong()).isEqualTo(fila.path("procesados").asLong() + fila.path("rechazados").asLong());
        assertThat(fila.path("responsable").asText()).isEqualTo(b.analista() + " · analista");
        assertThat(fila.path("motivo").asText()).contains("Fecha inválida (" + e.rechazosPorCausa().get("fecha_invalida") + ")");
        assertThat(fila.path("sha256").asText()).isEqualTo(Huellas.sha256(b.archivo()));
        assertThat(fila.path("etapasMs").has("lectura") && fila.path("etapasMs").has("validacion") && fila.path("etapasMs").has("hechos")
                && fila.path("etapasMs").has("agregados") && fila.path("etapasMs").has("subida")).as(fila.path("etapasMs").toString()).isTrue();
        Map<String, Long> motivos = new LinkedHashMap<>();
        fila.path("motivos").forEach(x -> motivos.put(x.path("causa").asText(), x.path("cantidad").asLong()));
        assertThat(motivos).isEqualTo(e.rechazosPorCausa());
        assertThat(fila.path("esmSinCorrespondencia").asLong()).isEqualTo(e.esmSinCorrespondencia());
        assertThat(lista.path("puedeCargar").asBoolean()).as("el coordinador consulta pero no carga").isFalse();
        // Procesados = hechos del cargue = llaves del cargue; todos trazables a su línea
        assertThat(contar("SELECT count(*) FROM ana_dispensaciones WHERE cargue_id = :c", "c", b.id())).isEqualTo(e.procesados());
        assertThat(contar("SELECT count(*) FROM ana_llaves WHERE cargue_id = :c", "c", b.id())).isEqualTo(e.procesados());
        assertThat(contar("SELECT count(*) FROM ana_dispensaciones WHERE cargue_id = :c AND (linea_origen IS NULL OR llave IS NULL)", "c", b.id())).isZero();
        // 12 particiones mensuales con cada hecho en la de su mes
        assertThat(jdbc.sql("""
                SELECT count(*) FROM pg_class c JOIN pg_namespace s ON s.oid = c.relnamespace
                WHERE s.nspname = 'analitica_particiones' AND c.relkind = 'r' AND c.relname ~ '^ana_dispensaciones_2025_[0-9]{2}$'""").query(Long.class).single())
                .isEqualTo(12);
        assertThat(contar("""
                SELECT count(*) FROM ana_dispensaciones d JOIN pg_class c ON c.oid = d.tableoid
                WHERE d.cargue_id = :c AND c.relname <> 'ana_dispensaciones_' || to_char(d.fecha, 'YYYY_MM')""", "c", b.id())).isZero();
        // Staging borrado al terminar
        assertThat(jdbc.sql("SELECT to_regclass('analitica_particiones.ana_staging_' || :c) IS NULL").param("c", b.id()).query(Boolean.class).single()).isTrue();
        // Evento en la bitácora general
        assertThat(jdbc.sql("SELECT descripcion FROM log_transacciones WHERE tipo = 'analitica_cargue_aplicado' AND descripcion LIKE :d")
                .param("d", "Cargue " + b.id() + " ·%").query(String.class).list())
                .containsExactly("Cargue " + b.id() + " · Archivo CSV (;) UTF-8 · " + e.recibidos() + " recibidos · " + e.rechazados() + " rechazados");
    }

    @Test
    @DisplayName("cuadre: gasto, unidades, dispensaciones y pacientes distintos de los hechos = totales del generador, en total y por dimensión")
    void cuadreContraElGenerador() throws IOException {
        Base b = base();
        GeneradorSintetico.Resultado e = b.esperado();
        Map<String, Object> t = jdbc.sql("""
                SELECT sum(valor_total) AS gasto, sum(cantidad) AS unidades, count(*) AS disp, count(DISTINCT paciente_seudo) AS pacientes
                FROM ana_dispensaciones WHERE cargue_id = :c""").param("c", b.id()).query().singleRow();
        assertThat((BigDecimal) t.get("gasto")).isEqualByComparingTo(e.gasto());
        assertThat((BigDecimal) t.get("unidades")).isEqualByComparingTo(BigDecimal.valueOf(e.unidades()));
        assertThat(((Number) t.get("disp")).longValue()).isEqualTo(e.procesados());
        assertThat(((Number) t.get("pacientes")).longValue()).isEqualTo(e.pacientes());
        Map<String, Map<String, String[]>> dims = GeneradorSintetico.leerDimensiones(e.totales());
        Map<String, String> sql = Map.of("fuerza", "fuerza", "esm", "esm_codigo", "modalidad", "modalidad", "atc1", "left(atc_original, 1)",
                "mes", "to_char(fecha, 'YYYY-MM')", "cum", "cum", "cie10", "coalesce(cie10, '(sin CIE-10)')");
        for (var d : sql.entrySet()) {
            Map<String, String[]> esperado = dims.get(d.getKey());
            assertThat(esperado).as(d.getKey()).isNotEmpty();
            Map<String, String[]> real = new LinkedHashMap<>();
            jdbc.sql("SELECT " + d.getValue() + " AS k, count(*), sum(valor_total), count(DISTINCT paciente_seudo), sum(cantidad) FROM ana_dispensaciones "
                    + "WHERE cargue_id = :c GROUP BY 1").param("c", b.id()).query(rs -> {
                        real.put(rs.getString(1), new String[] {rs.getString(2), rs.getBigDecimal(3).toPlainString(), rs.getString(4),
                            rs.getBigDecimal(5).stripTrailingZeros().toPlainString()});
                    });
            assertThat(real.keySet()).as(d.getKey()).isEqualTo(esperado.keySet());
            for (var k : esperado.entrySet()) {
                String[] r = real.get(k.getKey());
                assertThat(r[0]).as(d.getKey() + " " + k.getKey() + " dispensaciones").isEqualTo(k.getValue()[0]);
                assertThat(new BigDecimal(r[1])).as(d.getKey() + " " + k.getKey() + " gasto").isEqualByComparingTo(new BigDecimal(k.getValue()[1]));
                assertThat(r[2]).as(d.getKey() + " " + k.getKey() + " pacientes").isEqualTo(k.getValue()[2]);
                assertThat(new BigDecimal(r[3])).as(d.getKey() + " " + k.getKey() + " unidades").isEqualByComparingTo(new BigDecimal(k.getValue()[3]));
            }
        }
    }

    @Test
    @DisplayName("descarga de rechazos: CSV «linea;causa;columna» con una fila por causa, sin contenido de celdas, y queda en la bitácora")
    void descargaRechazos() throws IOException {
        Base b = base();
        Navegador n = navegador();
        assertThat(n.ingresar(b.analista(), CLAVE).estado()).isEqualTo(200);
        Navegador.Respuesta r = n.get("/api/analitica/cargues/" + b.id() + "/rechazos.csv");
        assertThat(r.estado()).isEqualTo(200);
        String texto = r.texto();
        List<String> lineas = texto.lines().toList();
        assertThat(lineas.getFirst()).isEqualTo("﻿linea;causa;columna");
        long filas = contar("SELECT count(*) FROM ana_cargue_rechazos WHERE cargue_id = :c", "c", b.id());
        assertThat(lineas).hasSize((int) filas + 1);
        assertThat(filas).isGreaterThanOrEqualTo(b.esperado().rechazados());
        assertThat(texto).contains("Duplicado exacto de la línea").contains("Fecha inválida;fecha").doesNotContain("12a,50").doesNotContain("POLICIA")
                .doesNotContain("ABC-12");
        for (String doc : b.esperado().documentos()) {
            assertThat(texto).doesNotContain(doc);
        }
        assertThat(bitacoraDe(idDe(b.analista()))).anyMatch(l -> l.contains("analitica_cargue_rechazos_descarga"));
        n.post("/api/auth/salir", null);
    }

    // ---------- Idempotencia ----------

    @Test
    @DisplayName("idempotencia: el mismo archivo otra vez devuelve el cargue aplicado (200) sin crear otro")
    void mismoArchivo() throws IOException {
        Base b = base();
        Navegador n = como("analistaotravez", Perfil.ANALISTA);
        long antes = contar("SELECT count(*) FROM ana_cargues");
        Navegador.Respuesta r = subir(n, "otra_vez.csv", b.archivo(), null);
        assertThat(r.estado()).isEqualTo(200);
        assertThat(r.json().path("existente").asBoolean()).isTrue();
        assertThat(r.json().path("id").asLong()).isEqualTo(b.id());
        assertThat(r.mensaje()).startsWith("Este archivo ya se cargó el ");
        assertThat(contar("SELECT count(*) FROM ana_cargues")).isEqualTo(antes);
    }

    @Test
    @DisplayName("archivo aplicado mientras se subía: al cerrar el cargue queda «duplicado» con la referencia (nunca se borra) y sin original")
    void duplicadoAlCerrar() {
        Navegador n = como("analistadupcierre", Perfil.ANALISTA);
        byte[] datos = csv(ENC_CORTO, fila("78000001", "11/05/2025"), fila("78000002", "12/05/2025"));
        long id = crear(n, "dup.csv", datos, null).json().path("id").asLong();
        enviarPartes(n, id, datos);
        JsonNode otro = cargar(como("analistadupotro", Perfil.ANALISTA), "dup_otro.csv", datos, "agregar");
        assertThat(otro.path("estado").asText()).isEqualTo("aplicado");
        Navegador.Respuesta r = n.post("/api/analitica/cargues/" + id + "/cerrar", null);
        assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        assertThat(r.json().path("existente").asBoolean()).isTrue();
        assertThat(r.json().path("id").asLong()).isEqualTo(otro.path("id").asLong());
        JsonNode d = n.get("/api/analitica/cargues/" + id).json();
        assertThat(d.path("estado").asText()).isEqualTo("duplicado");
        assertThat(d.path("duplicadoDe").asLong()).isEqualTo(otro.path("id").asLong());
        assertThat(d.path("originalDisponible").asBoolean()).isFalse();
        assertThat(contar("SELECT count(*) FROM ana_cargue_partes WHERE cargue_id = :c", "c", id)).isZero();
    }

    @Test
    @DisplayName("dos cargues del mismo archivo en cola: el primero se aplica y el segundo termina «duplicado de #N»")
    void dosEnCola() {
        Navegador n = como("analistadoscola", Perfil.ANALISTA);
        byte[] datos = csv(ENC_CORTO, fila("79000001", "13/05/2025"));
        long a = subir(n, "cola_a.csv", datos, null).json().path("id").asLong();
        long b = subir(n, "cola_b.csv", datos, null).json().path("id").asLong();
        assertThat(confirmar(n, a, propuesta(n, a), "agregar", false).estado()).isEqualTo(202);
        assertThat(confirmar(n, b, propuesta(n, b), "agregar", false).estado()).isEqualTo(202);
        assertThat(esperarFin(n, a).path("estado").asText()).isEqualTo("aplicado");
        JsonNode fin = esperarFin(n, b);
        assertThat(fin.path("estado").asText()).isEqualTo("duplicado");
        assertThat(fin.path("motivo").asText()).startsWith("Duplicado de #" + a);
        assertThat(contar("SELECT count(*) FROM ana_dispensaciones WHERE cargue_id = :c", "c", b)).isZero();
    }

    @Test
    @DisplayName("idempotencia por llave natural: los mismos registros en otro archivo se rechazan como «Ya cargado» y no se duplica el gasto")
    void yaCargado() throws IOException {
        Base b = base();
        Navegador n = como("analistarepite", Perfil.ANALISTA);
        String texto = new String(b.archivo(), StandardCharsets.UTF_8);
        List<String> lineas = texto.lines().toList();
        // Encabezado y 50 líneas válidas del archivo base, más una nueva: otro SHA-256
        List<String> nuevas = new ArrayList<>(lineas.subList(0, 51));
        String l1 = lineas.get(1);
        String extra = l1.substring(0, l1.lastIndexOf(";F")) + ";G" + l1.substring(l1.lastIndexOf(";F") + 2); // otra fórmula
        nuevas.add(extra);
        JsonNode fin = cargar(n, "repetidos.csv", (String.join("\r\n", nuevas) + "\r\n").getBytes(StandardCharsets.UTF_8), "agregar");
        assertThat(fin.path("estado").asText()).isEqualTo("aplicado");
        assertThat(fin.path("recibidos").asLong()).isEqualTo(51);
        assertThat(fin.path("procesados").asLong()).isEqualTo(1);
        assertThat(fin.path("rechazados").asLong()).isEqualTo(50);
        assertThat(fin.path("motivos").toString()).contains("ya_cargado");
    }

    // ---------- Perfiles ----------

    @Test
    @DisplayName("perfiles: el coordinador consulta pero no carga (403); auditor y OPL no ven la pantalla; el original solo el administrador")
    void perfiles() throws IOException {
        Base b = base();
        Navegador coord = como("coordcarga", Perfil.COORDINADOR);
        Map<String, Object> s = Map.of("nombre", "x.csv", "tamano", 10, "sha256", "a".repeat(64), "partes", 1);
        assertThat(coord.post("/api/analitica/cargues", s).estado()).isEqualTo(403);
        assertThat(coord.get("/api/analitica/cargues").estado()).isEqualTo(200);
        assertThat(coord.get("/api/analitica/cargues/" + b.id()).estado()).isEqualTo(200);
        assertThat(coord.get("/api/analitica/cargues/" + b.id() + "/original").estado()).isEqualTo(403);
        assertThat(coord.get("/api/analitica/cargues/" + b.id() + "/rechazos.csv").estado()).isEqualTo(200);
        Navegador auditor = como("auditorcarga", Perfil.AUDITOR);
        assertThat(auditor.get("/api/analitica/cargues").estado()).isEqualTo(403);
        assertThat(auditor.post("/api/analitica/cargues", s).estado()).isEqualTo(403);
        Navegador opl = como("oplcarga", Perfil.OPL_NACIONAL);
        assertThat(opl.get("/api/analitica/cargues").estado()).isEqualTo(403);
        Navegador analista = navegador();
        assertThat(analista.ingresar(b.analista(), CLAVE).estado()).isEqualTo(200);
        assertThat(analista.get("/api/analitica/cargues/" + b.id() + "/original").estado()).isEqualTo(403);
        analista.post("/api/auth/salir", null);
        Navegador admin = como("admincarga", Perfil.ADMINISTRADOR);
        Navegador.Respuesta o = admin.get("/api/analitica/cargues/" + b.id() + "/original");
        assertThat(o.estado()).isEqualTo(200);
        assertThat(Huellas.sha256(o.cuerpo())).isEqualTo(Huellas.sha256(b.archivo()));
        assertThat(bitacoraDe(idDe(admin.get("/api/sesion").json().path("usuario").asText())))
                .anyMatch(l -> l.contains("analitica_cargue_original_descarga"));
    }

    // ---------- Estructura, fallas y cola ----------

    @Test
    @DisplayName("rechazo estructural: más del 1 % de filas con otro número de campos rechaza el archivo completo, sin hechos")
    void rechazoEstructural() {
        Navegador n = como("analistaestructura", Perfil.ANALISTA);
        List<String> l = new ArrayList<>();
        l.add(ENC_CORTO);
        for (int i = 0; i < 100; i++) {
            String f = fila(Integer.toString(70_000_000 + i), "14/03/2025");
            l.add(i % 30 == 15 ? f + ";X" : f);
        }
        JsonNode fin = cargar(n, "corridas.csv", (String.join("\n", l) + "\n").getBytes(StandardCharsets.UTF_8), "agregar");
        assertThat(fin.path("estado").asText()).isEqualTo("rechazado");
        assertThat(fin.path("motivo").asText()).isEqualTo("Número de campos distinto del encabezado (3; primera línea 17)");
        assertThat(fin.path("recibidos").asLong()).isEqualTo(100);
        assertThat(fin.path("procesados").asLong()).isZero();
        assertThat(fin.path("rechazados").asLong()).isEqualTo(100);
        long id = fin.path("id").asLong();
        assertThat(contar("SELECT count(*) FROM ana_dispensaciones WHERE cargue_id = :c", "c", id)).isZero();
        assertThat(jdbc.sql("SELECT linea FROM ana_cargue_rechazos WHERE cargue_id = :c").param("c", id).query(String.class).list()).containsExactly("17");
        assertThat(contar("SELECT count(*) FROM log_transacciones WHERE tipo = 'analitica_cargue_rechazado' AND descripcion LIKE :d", "d",
                "Cargue " + id + " ·%")).isEqualTo(1);
    }

    @Test
    @DisplayName("comilla sin cerrar: el archivo se rechaza por estructura con la línea, sin tragarse el resto como una celda")
    void comillaSinCerrar() {
        Navegador n = como("analistacomilla", Perfil.ANALISTA);
        List<String> l = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            l.add(fila(Integer.toString(80_000_000 + i), "14/03/2025"));
        }
        l.add("CC;\"80000099;EJC;BAS02;Dispensación;14/03/2025;20010037-1;30;5415,00");
        for (int i = 0; i < 20; i++) {
            l.add(fila(Integer.toString(80_000_100 + i), "14/03/2025"));
        }
        JsonNode fin = cargar(n, "comilla.csv", csv(ENC_CORTO, l.toArray(String[]::new)), "agregar");
        assertThat(fin.path("estado").asText()).as(fin.toString()).isEqualTo("rechazado");
        assertThat(fin.path("motivo").asText()).isEqualTo("Comilla sin cerrar en el registro que empieza en la línea 22");
        assertThat(contar("SELECT count(*) FROM ana_dispensaciones WHERE cargue_id = :c", "c", fin.path("id").asLong())).isZero();
    }

    @Test
    @DisplayName("cantidad con más de 3 decimales: rechazo de esa fila por número inválido; el cargue se aplica")
    void cantidadConDecimales() {
        Navegador n = como("analistadecimales", Perfil.ANALISTA);
        byte[] datos = csv(ENC_CORTO, "CC;81000001;EJC;BAS02;Dispensación;14/03/2025;20010037-1;0,0004;5415,00",
                "CC;81000002;EJC;BAS02;Dispensación;14/03/2025;20010037-1;2,5000;5415,00");
        JsonNode fin = cargar(n, "decimales.csv", datos, "agregar");
        assertThat(fin.path("estado").asText()).as(fin.toString()).isEqualTo("aplicado");
        assertThat(fin.path("procesados").asLong()).isEqualTo(1);
        assertThat(fin.path("rechazados").asLong()).isEqualTo(1);
        assertThat(fin.path("motivos").toString()).contains("numero_invalido");
        assertThat(jdbc.sql("SELECT cantidad FROM ana_dispensaciones WHERE cargue_id = :c").param("c", fin.path("id").asLong()).query(BigDecimal.class)
                .single()).isEqualByComparingTo("2.5");
    }

    @Test
    @DisplayName("falla a mitad de la carga: el cargue queda «fallido» con la etapa, sin hechos visibles ni staging, y queda en la bitácora")
    void fallaSinEfecto() {
        Navegador n = como("analistafalla", Perfil.ANALISTA);
        AtomicReference<Long> visibles = new AtomicReference<>();
        aplicador.trasInsertar = id -> {
            // Con otra conexión (no la de la transacción en curso): los hechos recién insertados no se ven fuera de ella (todo o nada)
            try (var con = ds.getConnection(); var ps = con.prepareStatement("SELECT count(*) FROM ana_dispensaciones WHERE cargue_id = ?")) {
                ps.setLong(1, id);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    visibles.set(rs.getLong(1));
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
            throw new IllegalStateException("Falla simulada tras insertar los hechos");
        };
        List<String> l = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            l.add(fila(Integer.toString(71_000_000 + i), "14/04/2025"));
        }
        JsonNode fin = cargar(n, "fallara.csv", csv(ENC_CORTO, l.toArray(String[]::new)), "agregar");
        long id = fin.path("id").asLong();
        assertThat(fin.path("estado").asText()).isEqualTo("fallido");
        assertThat(fin.path("etapa").asText()).isEqualTo("hechos");
        assertThat(fin.path("error").asText()).startsWith("Etapa hechos:");
        assertThat(visibles.get()).isZero();
        assertThat(contar("SELECT count(*) FROM ana_dispensaciones WHERE cargue_id = :c", "c", id)).isZero();
        assertThat(contar("SELECT count(*) FROM ana_llaves WHERE cargue_id = :c", "c", id)).isZero();
        assertThat(contar("SELECT count(*) FROM ana_cargues_visibles(now()) v WHERE v.cargue_id = :c", "c", id)).isZero();
        assertThat(jdbc.sql("SELECT to_regclass('analitica_particiones.ana_staging_' || :c) IS NULL").param("c", id).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT descripcion FROM log_transacciones WHERE tipo = 'analitica_cargue_fallido' AND descripcion LIKE :d")
                .param("d", "Cargue " + id + " ·%").query(String.class).list()).anyMatch(x -> x.endsWith("etapa hechos"));
    }

    @Test
    @DisplayName("cola de uno: mientras uno procesa, el siguiente espera «pendiente» en la posición 1; luego se aplican en orden")
    void colaDeUno() throws InterruptedException {
        Navegador n = como("analistacola", Perfil.ANALISTA);
        long a = subir(n, "cola1.csv", csv(ENC_CORTO, fila("82000001", "15/05/2025")), null).json().path("id").asLong();
        long b = subir(n, "cola2.csv", csv(ENC_CORTO, fila("82000002", "16/05/2025")), null).json().path("id").asLong();
        CountDownLatch llego = new CountDownLatch(1);
        CountDownLatch seguir = new CountDownLatch(1);
        aplicador.trasInsertar = id -> {
            if (id == a) {
                llego.countDown();
                try {
                    seguir.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        try {
            assertThat(confirmar(n, a, propuesta(n, a), "agregar", false).estado()).isEqualTo(202);
            assertThat(llego.await(60, TimeUnit.SECONDS)).as("el primero llegó a la carga de hechos").isTrue();
            assertThat(confirmar(n, b, propuesta(n, b), "agregar", false).estado()).isEqualTo(202);
            JsonNode enCola = n.get("/api/analitica/cargues/" + b).json();
            assertThat(enCola.path("estado").asText()).isEqualTo("pendiente");
            assertThat(enCola.path("posicion").asLong()).isEqualTo(1);
            assertThat(n.get("/api/analitica/cargues/" + a).json().path("estado").asText()).isEqualTo("procesando");
            assertThat(contar("SELECT count(*) FROM ana_cargues WHERE estado = 'procesando'")).isEqualTo(1);
        } finally {
            seguir.countDown();
        }
        JsonNode fa = esperarFin(n, a);
        JsonNode fb = esperarFin(n, b);
        assertThat(fa.path("estado").asText()).isEqualTo("aplicado");
        assertThat(fb.path("estado").asText()).isEqualTo("aplicado");
        assertThat(jdbc.sql("SELECT (SELECT iniciado FROM ana_cargues WHERE id = :b) >= (SELECT aplicado_en FROM ana_cargues WHERE id = :a)")
                .param("a", a).param("b", b).query(Boolean.class).single()).as("el segundo empezó cuando el primero ya estaba aplicado").isTrue();
    }

    @Test
    @DisplayName("proceso interrumpido: un «procesando» huérfano queda «fallido» sin staging, queda en la bitácora y la cola sigue")
    void procesoInterrumpido() {
        long id = jdbc.sql("""
                INSERT INTO ana_cargues (fuente, fuente_texto, formato, estado, etapa, responsable_texto)
                VALUES ('archivo', 'Archivo CSV (;) UTF-8', 'csv', 'procesando', 'validacion', 'prueba · huérfano') RETURNING id""")
                .query(Long.class).single();
        jdbc.sql("SELECT ana_staging_crear(:c)").param("c", id).query().singleRow();
        procesador.correr();
        Navegador n = como("analistahuerfano", Perfil.ANALISTA);
        JsonNode fin = esperarFin(n, id);
        assertThat(fin.path("estado").asText()).isEqualTo("fallido");
        assertThat(fin.path("error").asText()).isEqualTo(ProcesadorCargues.INTERRUMPIDO);
        assertThat(jdbc.sql("SELECT to_regclass('analitica_particiones.ana_staging_' || :c) IS NULL").param("c", id).query(Boolean.class).single()).isTrue();
        assertThat(contar("SELECT count(*) FROM log_transacciones WHERE tipo = 'analitica_cargue_fallido' AND descripcion = :d", "d",
                "Cargue " + id + " · " + ProcesadorCargues.INTERRUMPIDO)).isEqualTo(1);
        assertThat(cargar(n, "tras_huerfano.csv", csv(ENC_CORTO, fila("83000001", "17/05/2025")), "agregar").path("estado").asText())
                .isEqualTo("aplicado");
    }

    // ---------- Abandono y descarte ----------

    @Test
    @DisplayName("cargues abandonados (subida, mapeo y envío sin lote final): vencen tras el plazo y se borran partes, original y lotes")
    void abandonadosVencen() throws IOException {
        Navegador n = como("analistaabandona", Perfil.ANALISTA);
        byte[] datos = csv(ENC_CORTO, fila("84000001", "18/05/2025"));
        long subiendo = crear(n, "abandonado.csv", datos, null).json().path("id").asLong();
        enviarPartes(n, subiendo, datos);
        String parte = jdbc.sql("SELECT almacen_ruta FROM ana_cargue_partes WHERE cargue_id = :c").param("c", subiendo).query(String.class).single();
        long mapeo = subir(n, "abandonado2.csv", csv(ENC_CORTO, fila("84000002", "18/05/2025")), null).json().path("id").asLong();
        String original = jdbc.sql("SELECT almacen_ruta FROM ana_cargues WHERE id = :c").param("c", mapeo).query(String.class).single();
        Navegador opl = como("oplabandona", Perfil.OPL_NACIONAL);
        String envio = "ENV-ABANDONO-" + System.nanoTime();
        Navegador.Respuesta l1 = opl.postJson("/api/analitica/opl/dispensacion", ServicioWebOpl.cuerpo(JSON, envio, 1, false, List.of(registro(1))));
        assertThat(l1.estado()).as(l1.texto()).isEqualTo(200);
        long ws = l1.json().path("cargue").asLong();
        String lote = jdbc.sql("SELECT almacen_ruta FROM ana_ws_lotes WHERE cargue_id = :c").param("c", ws).query(String.class).single();
        // Recientes: no vencen
        assertThat(mantenimiento.vencerAbandonados()).isZero();
        jdbc.sql("UPDATE ana_cargues SET actividad = now() - interval '25 hours' WHERE id IN (:a, :b, :c)").param("a", subiendo).param("b", mapeo)
                .param("c", ws).update();
        assertThat(mantenimiento.vencerAbandonados()).isEqualTo(3);
        for (long id : List.of(subiendo, mapeo, ws)) {
            assertThat(estado(id)).isEqualTo("vencido");
            assertThat(contar("SELECT count(*) FROM log_transacciones WHERE tipo = 'analitica_cargue_vencido' AND descripcion LIKE :d", "d",
                    "Cargue " + id + " ·%")).isEqualTo(1);
        }
        assertThat(contar("SELECT count(*) FROM ana_cargue_partes WHERE cargue_id = :c", "c", subiendo)).isZero();
        assertThat(jdbc.sql("SELECT almacen_ruta IS NULL AND original_borrado IS NOT NULL FROM ana_cargues WHERE id = :c").param("c", mapeo)
                .query(Boolean.class).single()).isTrue();
        assertThat(contar("SELECT count(*) FROM ana_ws_lotes WHERE cargue_id = :c AND almacen_ruta IS NOT NULL", "c", ws)).isZero();
        for (String ruta : List.of(parte, original, lote)) {
            assertThatThrownBy(() -> almacen.leer(ruta).close()).as(ruta).isInstanceOf(IOException.class);
        }
        assertThat(n.get("/api/analitica/cargues/" + mapeo).json().path("motivo").asText()).startsWith("Vencido: sin actividad en 24 h");
    }

    @Test
    @DisplayName("descartar: el dueño descarta su cargue en mapeo (queda «descartado», sin original); otro analista 404; el coordinador 403")
    void descartar() {
        Navegador n = como("analistadescarta", Perfil.ANALISTA);
        long id = subir(n, "descartar.csv", csv(ENC_CORTO, fila("85000001", "19/05/2025")), null).json().path("id").asLong();
        assertThat(como("analistaajeno", Perfil.ANALISTA).delete("/api/analitica/cargues/" + id).estado()).isEqualTo(404);
        assertThat(como("coorddescarta", Perfil.COORDINADOR).delete("/api/analitica/cargues/" + id).estado()).isEqualTo(403);
        Navegador.Respuesta r = n.delete("/api/analitica/cargues/" + id);
        assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        assertThat(r.json().path("estado").asText()).isEqualTo("descartado");
        assertThat(r.json().path("originalDisponible").asBoolean()).isFalse();
        assertThat(n.delete("/api/analitica/cargues/" + id).estado()).isEqualTo(409);
        // En una petición, el evento del servicio va en la misma fila de la petición (FiltroBitacora)
        // y el filtro la escribe al terminar la respuesta: se espera a que aparezca
        esperar(() -> contar("SELECT count(*) FROM log_transacciones WHERE tipo = 'analitica_cargue_descartado' AND descripcion LIKE :d", "d",
                "%Cargue " + id + " · Descartado por%") == 1, "el descarte en la bitácora");
    }

    @Test
    @DisplayName("bitácora en CSV con los filtros y el borrado programado (verificado) del original vencido")
    void bitacoraCsvYBorrado() throws IOException {
        Base b = base();
        Navegador n = como("admincsv", Perfil.ADMINISTRADOR);
        Navegador.Respuesta r = n.get("/api/analitica/cargues/bitacora.csv?estado=aplicado");
        assertThat(r.estado()).isEqualTo(200);
        assertThat(r.texto()).startsWith("﻿id;fecha_hora_recepcion;fecha_hora_aplicacion;fuente;periodo_procesado;recibidos;procesados;rechazados;")
                .contains(b.id() + ";").contains("01/01/2025 – 31/12/2025");
        // Un cargue pequeño cuyo original vence: la tarea lo borra del almacén y deja cargue y hechos
        JsonNode fin = cargar(n, "vence.csv", csv("NUM_DOC;FUERZA;COD_ESM;MODALIDAD;FECHA_DISPENSACION;CUM;CANTIDAD;VALOR_TOTAL",
                "CC73000001;EJC;BAS02;Dispensación;10/06/2025;20010037-1;30;5415,00"), "agregar");
        long id = fin.path("id").asLong();
        String ruta = jdbc.sql("SELECT almacen_ruta FROM ana_cargues WHERE id = :c").param("c", id).query(String.class).single();
        jdbc.sql("UPDATE ana_cargues SET conservar_hasta = now() - interval '1 day' WHERE id = :c").param("c", id).update();
        assertThat(mantenimiento.borrarOriginalesVencidos()).isGreaterThanOrEqualTo(1);
        assertThatThrownBy(() -> almacen.leer(ruta).close()).isInstanceOf(IOException.class);
        assertThat(n.get("/api/analitica/cargues/" + id).json().path("originalDisponible").asBoolean()).isFalse();
        assertThat(n.get("/api/analitica/cargues/" + id + "/original").estado()).isEqualTo(410);
        assertThat(contar("SELECT count(*) FROM ana_dispensaciones WHERE cargue_id = :c", "c", id)).isEqualTo(1);
    }

    // ---------- Reemplazar el periodo ----------

    @Test
    @DisplayName("reemplazar el periodo: exige el periodo declarado, muestra lo que oculta, rechaza las filas fuera y el corte es reproducible")
    void reemplazoPeriodoYCorte() {
        Navegador n = como("analistareemplazo", Perfil.ANALISTA);
        String enc = "NUM_DOC;FUERZA;COD_ESM;MODALIDAD;FECHA_DISPENSACION;CUM;CANTIDAD;VALOR_TOTAL;NUM_FORMULA";
        String sello = Long.toString(System.nanoTime());
        JsonNode original = cargar(n, "septiembre.csv", csv(enc,
                "CC74000001;EJC;BAS02;Dispensación;03/09/2025;20010037-1;30;1000,00;R" + sello + "1",
                "CC74000002;EJC;BAS02;Dispensación;20/09/2025;20010037-1;30;2000,00;R" + sello + "2"), "agregar");
        assertThat(original.path("estado").asText()).isEqualTo("aplicado");
        long c1 = original.path("id").asLong();
        String corteAnterior = jdbc.sql("SELECT aplicado_en::text FROM ana_cargues WHERE id = :c").param("c", c1).query(String.class).single();
        // Corregido: el primer registro igual, el segundo con otro valor y uno de octubre (fuera del periodo declarado)
        long c2 = subir(n, "septiembre_corregido.csv", csv(enc,
                "CC74000001;EJC;BAS02;Dispensación;03/09/2025;20010037-1;30;1000,00;R" + sello + "1",
                "CC74000002;EJC;BAS02;Dispensación;20/09/2025;20010037-1;30;2500,00;R" + sello + "2",
                "CC74000003;EJC;BAS02;Dispensación;05/10/2025;20010037-1;30;9000,00;R" + sello + "3"), null).json().path("id").asLong();
        List<String> p = propuesta(n, c2);
        Navegador.Respuesta sinPeriodo = confirmar(n, c2, p, "reemplazar", false);
        assertThat(sinPeriodo.estado()).isEqualTo(422);
        assertThat(sinPeriodo.mensaje()).contains("periodo declarado");
        JsonNode oculta = n.get("/api/analitica/cargues/" + c2 + "/reemplazo?desde=2025-09-01&hasta=2025-09-30").json();
        assertThat(oculta.path("cargues").toString()).contains(Long.toString(c1));
        assertThat(oculta.path("registros").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(confirmar(n, c2, p, "reemplazar", false, "2025-09-01", "2025-09-30").estado()).isEqualTo(202);
        JsonNode corregido = esperarFin(n, c2);
        assertThat(corregido.path("estado").asText()).isEqualTo("aplicado");
        assertThat(corregido.path("procesados").asLong()).isEqualTo(2);
        assertThat(corregido.path("rechazados").asLong()).isEqualTo(1);
        assertThat(corregido.path("motivos").toString()).contains("fecha_fuera_periodo");
        assertThat(jdbc.sql("SELECT reemplazado_por FROM ana_cargues WHERE id = :c").param("c", c1).query(Long.class).single()).isEqualTo(c2);
        String gasto = """
                SELECT coalesce(sum(d.valor_total), 0) FROM ana_dispensaciones d
                WHERE d.cargue_id IN (:c1, :c2) AND d.cargue_id IN (SELECT cargue_id FROM ana_cargues_visibles(CAST(:corte AS timestamptz)))""";
        assertThat(jdbc.sql(gasto).param("c1", c1).param("c2", c2).param("corte", corteAnterior).query(BigDecimal.class).single())
                .as("corte anterior: el total original").isEqualByComparingTo("3000.00");
        assertThat(jdbc.sql(gasto).param("c1", c1).param("c2", c2).param("corte", "2100-01-01T00:00:00Z").query(BigDecimal.class).single())
                .as("corte nuevo: solo el corregido").isEqualByComparingTo("3500.00");
    }

    // ---------- Servicio web del OPL ----------

    private static Map<String, Object> registro(int i) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tipoDocumento", "CC");
        r.put("paciente", String.valueOf(72_000_000 + i % 10));
        r.put("fuerza", "EJC");
        r.put("esm", i == 0 ? "ESM-999" : "BAS10");
        r.put("modalidad", "Dispensación");
        r.put("fecha", "2025-05-" + String.format("%02d", 1 + i % 28));
        r.put("medicamento", "LOSARTAN 50 MG TABLETA");
        r.put("cum", i == 29 ? "XYZ" : "20010037-1");
        r.put("cantidad", 30);
        r.put("valor", new BigDecimal("5415.50"));
        r.put("numeroFormula", "W" + i);
        return r;
    }

    @Test
    @DisplayName("servicio web del OPL: lotes durables, el mismo lote otra vez con la misma respuesta, claves desconocidas avisadas, cierre con el final")
    void servicioWebPorLotes() {
        Navegador opl = como("oplnacionalws", Perfil.OPL_NACIONAL);
        String envio = "ENV-" + System.nanoTime();
        List<Map<String, Object>> registros = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Map<String, Object> r = registro(i);
            r.put("nombre", "NOMBRE FICTICIO " + i); // dato personal: se ignora, se avisa y no se guarda
            registros.add(r);
        }
        byte[] lote1 = ServicioWebOpl.cuerpo(JSON, envio, 1, false, registros.subList(0, 20));
        byte[] lote2 = ServicioWebOpl.cuerpo(JSON, envio, 2, true, registros.subList(20, 30));
        Navegador.Respuesta r1 = opl.postJson("/api/analitica/opl/dispensacion", lote1);
        assertThat(r1.estado()).as(r1.texto()).isEqualTo(200);
        assertThat(r1.json().path("clavesDesconocidas").toString()).isEqualTo("[\"nombre\"]");
        long id = r1.json().path("cargue").asLong();
        assertThat(contar("SELECT count(*) FROM ana_staging_dispensacion WHERE cargue_id = :c", "c", id)).as("los lotes no esperan en staging").isZero();
        Navegador.Respuesta r1b = opl.postJson("/api/analitica/opl/dispensacion", lote1);
        assertThat(r1b.estado()).isEqualTo(200);
        assertThat(r1b.texto()).as("lote repetido: misma respuesta").isEqualTo(r1.texto());
        Navegador.Respuesta r2 = opl.postJson("/api/analitica/opl/dispensacion", lote2);
        assertThat(r2.estado()).as(r2.texto()).isEqualTo(200);
        Navegador analista = como("analistaws", Perfil.ANALISTA);
        JsonNode fin = esperarFin(analista, id);
        assertThat(fin.path("estado").asText()).as(fin.toString()).isEqualTo("aplicado");
        assertThat(fin.path("fuenteTexto").asText()).isEqualTo("API OPL · dispensación");
        assertThat(fin.path("responsable").asText()).startsWith("Proceso automático · API OPL (");
        assertThat(fin.path("recibidos").asLong()).isEqualTo(30);
        assertThat(fin.path("rechazados").asLong()).isEqualTo(1);
        assertThat(fin.path("procesados").asLong()).isEqualTo(29);
        assertThat(fin.path("esmSinCorrespondencia").asLong()).isEqualTo(1);
        assertThat(fin.path("sha256").asText()).matches("^[0-9a-f]{64}$");
        assertThat(fin.path("avisos").toString()).contains("claves desconocidas ignoradas: nombre");
        assertThat(jdbc.sql("SELECT sum(valor_total) FROM ana_dispensaciones WHERE cargue_id = :c").param("c", id).query(BigDecimal.class).single())
                .isEqualByComparingTo(new BigDecimal("5415.50").multiply(BigDecimal.valueOf(29)));
        assertThat(jdbc.sql("SELECT linea FROM ana_cargue_rechazos WHERE cargue_id = :c").param("c", id).query(String.class).list())
                .containsExactly("lote 2 · registro 10");
        // Los lotes sueltos se borraron; el envío serializado (el original) no trae las claves desconocidas
        assertThat(contar("SELECT count(*) FROM ana_ws_lotes WHERE cargue_id = :c AND almacen_ruta IS NOT NULL", "c", id)).isZero();
        Navegador.Respuesta o = como("adminws", Perfil.ADMINISTRADOR).get("/api/analitica/cargues/" + id + "/original");
        assertThat(o.estado()).isEqualTo(200);
        assertThat(o.texto()).contains("LOSARTAN").doesNotContain("NOMBRE FICTICIO");
        // Otros perfiles: 403 sin crear nada
        Navegador regional = como("oplregionalws", Perfil.OPL_REGIONAL);
        byte[] otro = ServicioWebOpl.cuerpo(JSON, envio + "-X", 1, true, registros.subList(0, 1));
        assertThat(regional.postJson("/api/analitica/opl/dispensacion", otro).estado()).isEqualTo(403);
        assertThat(analista.postJson("/api/analitica/opl/dispensacion", otro).estado()).isEqualTo(403);
        assertThat(contar("SELECT count(*) FROM ana_cargues WHERE envio = :e", "e", envio + "-X")).isZero();
        // Envío ya cerrado
        assertThat(opl.postJson("/api/analitica/opl/dispensacion", ServicioWebOpl.cuerpo(JSON, envio, 3, false, registros.subList(0, 1))).estado())
                .isEqualTo(409);
        // El envío de otra cuenta del OPL: 409
        assertThat(como("oplotraws", Perfil.OPL_NACIONAL).postJson("/api/analitica/opl/dispensacion",
                ServicioWebOpl.cuerpo(JSON, envio, 4, false, registros.subList(0, 1))).estado()).isEqualTo(409);
    }

    @Test
    @DisplayName("servicio web: el envío solo cierra con todos los lotes 1..N; mayor que el final 422; repetido distinto 409; cuerpo grande 413")
    void servicioWebSecuencia() {
        Navegador opl = como("oplsecuencia", Perfil.OPL_NACIONAL);
        String envio = "ENV-SEQ-" + System.nanoTime();
        String ruta = "/api/analitica/opl/dispensacion";
        assertThat(opl.postJson(ruta, ServicioWebOpl.cuerpo(JSON, envio, 1, false, List.of(registro(1), registro(2)))).estado()).isEqualTo(200);
        Navegador.Respuesta r3 = opl.postJson(ruta, ServicioWebOpl.cuerpo(JSON, envio, 3, true, List.of(registro(3))));
        assertThat(r3.estado()).isEqualTo(200);
        long id = r3.json().path("cargue").asLong();
        assertThat(estado(id)).as("falta el lote 2: no se cierra").isEqualTo("subiendo");
        Navegador.Respuesta r4 = opl.postJson(ruta, ServicioWebOpl.cuerpo(JSON, envio, 4, false, List.of(registro(4))));
        assertThat(r4.estado()).isEqualTo(422);
        assertThat(r4.mensaje()).isEqualTo("El lote 4 es posterior al lote final del envío (3).");
        Navegador.Respuesta otroFinal = opl.postJson(ruta, ServicioWebOpl.cuerpo(JSON, envio, 2, true, List.of(registro(5))));
        assertThat(otroFinal.estado()).isEqualTo(422);
        Navegador.Respuesta distinto = opl.postJson(ruta, ServicioWebOpl.cuerpo(JSON, envio, 1, false, List.of(registro(9))));
        assertThat(distinto.estado()).isEqualTo(409);
        assertThat(distinto.mensaje()).contains("ya se recibió con otro contenido");
        assertThat(opl.postJson(ruta, ServicioWebOpl.cuerpo(JSON, envio, 2, false, List.of(registro(5), registro(6)))).estado()).isEqualTo(200);
        JsonNode fin = esperarFin(como("analistasecuencia", Perfil.ANALISTA), id);
        assertThat(fin.path("estado").asText()).as(fin.toString()).isEqualTo("aplicado");
        assertThat(fin.path("recibidos").asLong()).isEqualTo(5);
        // Un final menor que un lote ya recibido: 422
        String envio2 = "ENV-SEQ2-" + System.nanoTime();
        assertThat(opl.postJson(ruta, ServicioWebOpl.cuerpo(JSON, envio2, 3, false, List.of(registro(7)))).estado()).isEqualTo(200);
        Navegador.Respuesta menor = opl.postJson(ruta, ServicioWebOpl.cuerpo(JSON, envio2, 2, true, List.of(registro(8))));
        assertThat(menor.estado()).isEqualTo(422);
        assertThat(menor.mensaje()).isEqualTo("Ya se recibió el lote 3, posterior al final declarado (2).");
        // Cuerpo de más de 1 MB (tope de las pruebas): 413
        List<Map<String, Object>> muchos = new ArrayList<>();
        for (int i = 0; i < 6_000; i++) {
            muchos.add(registro(i));
        }
        Navegador.Respuesta grande = opl.postJson(ruta, ServicioWebOpl.cuerpo(JSON, "ENV-GRANDE-" + System.nanoTime(), 1, true, muchos));
        assertThat(grande.estado()).isEqualTo(413);
    }

    static byte[] csv(String encabezado, String... filas) {
        StringBuilder sb = new StringBuilder("﻿").append(encabezado).append("\r\n");
        for (String f : filas) {
            sb.append(f).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
