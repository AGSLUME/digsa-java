package co.com.agsamericas.digsa.presupuesto;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Presupuesto F2: vigencias, asignación inicial (M7-1), novedades (M7-2), historial, alcance y permisos (docs/especificaciones/presupuesto). */
class PresupuestoApiTest extends PruebaIntegracion {

    private static final AtomicInteger ANIO = new AtomicInteger(2040);
    private static final ObjectMapper JSON = new ObjectMapper();

    record P(long id, String sigla, String nombre, String fuerza) {
        String texto() {
            return sigla == null ? nombre : sigla;
        }
    }

    P ejc1;
    P ejc2;
    P arc;
    P satelite;
    P homil;
    P farmacia;

    @BeforeEach
    void puntos() {
        List<P> ejc = esm("p.fuerza = 'EJC' AND p.modalidad = 'Dispensación' AND p.sigla IS NOT NULL", 2);
        ejc1 = ejc.get(0);
        ejc2 = ejc.get(1);
        arc = esm("p.fuerza = 'ARC' AND p.modalidad <> 'Satélite' AND p.sigla IS NOT NULL", 1).get(0);
        satelite = esm("p.modalidad = 'Satélite' AND p.sigla IS NOT NULL", 1).get(0);
        homil = esm("p.fuerza = 'NA'", 1).get(0);
        farmacia = jdbc.sql("SELECT id, sigla, nombre, fuerza FROM puntos p WHERE unidad = 'FA' AND activo AND NOT EXISTS (SELECT 1 FROM puntos o WHERE o.id <> p.id AND o.nombre = p.nombre) ORDER BY id LIMIT 1")
                .query((rs, n) -> new P(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))).single();
    }

    private List<P> esm(String condicion, int cuantos) {
        List<P> r = jdbc.sql("SELECT p.id, p.sigla, p.nombre, p.fuerza FROM puntos p WHERE p.unidad = 'ESM' AND p.activo AND " + condicion
                + " AND NOT EXISTS (SELECT 1 FROM puntos o WHERE o.id <> p.id AND (o.nombre = p.nombre OR o.sigla = p.sigla)) ORDER BY p.id LIMIT " + cuantos)
                .query((rs, n) -> new P(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
        assertThat(r).as("puntos de prueba: " + condicion).hasSize(cuantos);
        return r;
    }

    private Navegador como(Perfil... perfiles) {
        Navegador n = navegador();
        assertThat(n.ingresar(crearUsuario("pre", perfiles), CLAVE).estado()).isEqualTo(200);
        return n;
    }

    private String ultimoUsuario;

    private Navegador coordinador() {
        Navegador n = navegador();
        ultimoUsuario = crearUsuario("coordpre", Perfil.COORDINADOR);
        assertThat(n.ingresar(ultimoUsuario, CLAVE).estado()).isEqualTo(200);
        return n;
    }

    private long vigencia(Navegador n, int anio) {
        Respuesta r = n.post("/api/presupuesto/vigencias", Map.of("anio", anio));
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        return r.json().path("id").asLong();
    }

    private static Map<String, byte[]> csv(String nombre, String contenido) {
        return Map.of(nombre, contenido.getBytes(StandardCharsets.UTF_8));
    }

    private Respuesta novedad(Navegador n, Map<String, Object> datos) {
        try {
            return n.multipart("/api/presupuesto/novedades", Map.of("datos", JSON.writeValueAsString(datos)), Map.of(), "archivo");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> item(P p, String modalidad, String valor) {
        Map<String, Object> m = new HashMap<>();
        m.put("puntoId", p.id());
        m.put("modalidad", modalidad);
        m.put("valor", new BigDecimal(valor));
        return m;
    }

    private static Map<String, Object> datos(long vigencia, String tipo, String soporte, List<Map<String, Object>> items) {
        Map<String, Object> m = new HashMap<>();
        m.put("vigenciaId", vigencia);
        m.put("tipo", tipo);
        m.put("soporte", soporte);
        m.put("items", items);
        return m;
    }

    private BigDecimal asignado(long vigencia, P p, String modalidad) {
        return jdbc.sql("""
                SELECT s.asignado FROM presupuesto_saldos s JOIN presupuesto_lineas l ON l.id = s.linea_id
                WHERE l.vigencia_id = :v AND l.punto_id = :p AND l.modalidad = :m""").param("v", vigencia).param("p", p.id()).param("m", modalidad)
                .query(BigDecimal.class).single();
    }

    /** Invariante: el asignado de cada línea es la suma de sus movimientos y cada movimiento encadena antes → después. */
    private void invarianteSaldos(long vigencia) {
        assertThat(jdbc.sql("""
                SELECT count(*) FROM presupuesto_lineas l JOIN presupuesto_saldos s ON s.linea_id = l.id
                WHERE l.vigencia_id = :v AND s.asignado <> (SELECT coalesce(sum(valor), 0) FROM presupuesto_movimientos_lineas WHERE linea_id = l.id)""")
                .param("v", vigencia).query(Long.class).single()).as("asignado = Σ movimientos").isZero();
    }

    @Test
    @DisplayName("permisos: Auditor y OPL 403; DIGSA consulta pero no registra; el coordinador registra")
    void permisos() {
        assertThat(como(Perfil.AUDITOR).get("/api/presupuesto/opciones").estado()).isEqualTo(403);
        assertThat(como(Perfil.OPL_NACIONAL).get("/api/presupuesto/lineas").estado()).isEqualTo(403);
        Navegador digsa = como(Perfil.DIGSA);
        Respuesta o = digsa.get("/api/presupuesto/opciones");
        assertThat(o.estado()).isEqualTo(200);
        assertThat(o.json().path("puedeRegistrar").asBoolean()).isFalse();
        assertThat(digsa.post("/api/presupuesto/vigencias", Map.of("anio", 2099)).estado()).isEqualTo(403);
        assertThat(novedad(digsa, datos(1, "adicion", "Oficio 1 de prueba", List.of())).estado()).isEqualTo(403);
        assertThat(coordinador().get("/api/presupuesto/opciones").json().path("puedeRegistrar").asBoolean()).isTrue();
        Navegador analista = como(Perfil.ANALISTA);
        assertThat(analista.get("/api/presupuesto/opciones").json().path("ambito").asText()).as("Analista: consulta nacional (duda P.7)").isEqualTo("Nacional");
    }

    @Test
    @DisplayName("escritura: Analista, OPL y Auditor reciben 403 y el rechazo queda en la bitácora")
    void escrituraProhibida() {
        String analista = crearUsuario("anapre", Perfil.ANALISTA);
        Navegador a = navegador();
        a.ingresar(analista, CLAVE);
        assertThat(a.post("/api/presupuesto/vigencias", Map.of("anio", 2098)).estado()).isEqualTo(403);
        assertThat(bitacoraDe(idDe(analista)).getFirst()).contains("acceso_denegado");
        assertThat(novedad(como(Perfil.OPL_NACIONAL), datos(1, "adicion", "Oficio 1 de prueba", List.of())).estado()).isEqualTo(403);
        assertThat(como(Perfil.AUDITOR).put("/api/presupuesto/parametros/presupuesto_base_umbral", Map.of("valor", "reconocido")).estado()).isEqualTo(403);
    }

    @Test
    @DisplayName("asignación inicial por formulario sobre una línea que ya tiene presupuesto: 422")
    void asignacionRepetida() {
        Navegador n = coordinador();
        long vig = vigencia(n, ANIO.incrementAndGet());
        assertThat(novedad(n, datos(vig, "asignacion_inicial", "Oficio DIGSA 1 del 2027-01-02", List.of(item(ejc1, "dispensacion", "10")))).estado())
                .isEqualTo(201);
        Respuesta otra = novedad(n, datos(vig, "asignacion_inicial", "Oficio DIGSA 2 del 2027-01-02", List.of(item(ejc1, "dispensacion", "20"))));
        assertThat(otra.estado()).isEqualTo(422);
        assertThat(otra.mensaje()).contains("la línea ya tiene presupuesto");
        Map<String, Object> pct = datos(vig, "adicion", "Oficio DIGSA 3 del 2027-01-02", List.of(item(ejc1, "dispensacion", "1")));
        pct.put("porcentaje", new BigDecimal("5"));
        assertThat(novedad(n, pct).mensaje()).isEqualTo("El porcentaje solo aplica al reajuste por ámbito, sin líneas.");
    }

    @Test
    @DisplayName("carga en Excel: primera hoja .xlsx con números y fechas; .xls se rechaza")
    void cargaExcel() throws Exception {
        Navegador n = coordinador();
        int anio = ANIO.incrementAndGet();
        vigencia(n, anio);
        byte[] xlsx;
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook libro = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             java.io.ByteArrayOutputStream salida = new java.io.ByteArrayOutputStream()) {
            var hoja = libro.createSheet("Presupuesto");
            String[] enc = {"Vigencia", "Fuerza", "ESM", "Modalidad", "Valor"};
            var r0 = hoja.createRow(0);
            for (int i = 0; i < enc.length; i++) {
                r0.createCell(i).setCellValue(enc[i]);
            }
            var r1 = hoja.createRow(1);
            r1.createCell(0).setCellValue(anio);
            r1.createCell(1).setCellValue("EJC");
            r1.createCell(2).setCellValue(ejc1.texto());
            r1.createCell(3).setCellValue("Dispensación");
            r1.createCell(4).setCellValue(1250000.5);
            libro.write(salida);
            xlsx = salida.toByteArray();
        }
        Respuesta s = n.multipart("/api/presupuesto/asignacion/simular", Map.of(), Map.of("presupuesto.xlsx", xlsx), "archivo");
        assertThat(s.estado()).as(s.texto()).isEqualTo(200);
        assertThat(s.json().path("validas").asInt()).isEqualTo(1);
        assertThat(s.json().path("totalNacional").asText()).isEqualTo("1250000,50");
        Respuesta xls = n.multipart("/api/presupuesto/asignacion/simular", Map.of(), Map.of("viejo.xls", new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0}),
                "archivo");
        assertThat(xls.estado()).isEqualTo(422);
        assertThat(xls.mensaje()).contains(".xls no se admite");
    }

    @Test
    @DisplayName("vigencia: parcial, duplicada, fechas fuera del año; cerrada no admite novedades")
    void vigencias() {
        Navegador n = coordinador();
        int anio = ANIO.incrementAndGet();
        Respuesta r = n.post("/api/presupuesto/vigencias", Map.of("anio", anio, "inicio", anio + "-03-01", "fin", anio + "-12-31"));
        assertThat(r.estado()).isEqualTo(201);
        long id = r.json().path("id").asLong();
        assertThat(n.post("/api/presupuesto/vigencias", Map.of("anio", anio)).estado()).isEqualTo(409);
        assertThat(n.post("/api/presupuesto/vigencias", Map.of("anio", anio + 500, "inicio", "2026-01-01")).estado()).isEqualTo(422);
        assertThat(n.put("/api/presupuesto/vigencias/" + id, Map.of("estado", "cerrada")).estado()).isEqualTo(200);
        Respuesta x = novedad(n, datos(id, "adicion", "Oficio 99 del 2027-05-02", List.of(item(ejc1, "dispensacion", "10"))));
        assertThat(x.estado()).isEqualTo(422);
        assertThat(x.mensaje()).isEqualTo("La vigencia " + anio + " está cerrada: no admite novedades ni ejecución.");
        assertThat(bitacoraDe(idDe(ultimoUsuario)).getFirst()).contains("estado: «abierta» → «cerrada»");
    }

    @Test
    @DisplayName("M7-1: simular con errores por fila, aplicar todo o nada, idempotente por archivo y línea ya asignada")
    void cargaAsignacion() {
        Navegador n = coordinador();
        int anio = ANIO.incrementAndGet();
        long vig = vigencia(n, anio);
        jdbc.sql("UPDATE presupuesto_modalidades SET activa = false WHERE codigo = 'consignacion'").update();
        String malo = "vigencia;fuerza;esm;modalidad;valor\n"
                + anio + ";EJC;" + ejc1.texto() + ";Dispensación;1.000.000.000,00\n"
                + anio + ";;" + ejc2.texto() + ";Órdenes de requisición;250000000.50\n"
                + anio + ";ARC;" + ejc1.texto() + ";Suministro;5\n"                    // fuerza que no es la del ESM
                + anio + ";;" + satelite.texto() + ";Dispensación;5\n"                 // satélite
                + anio + ";;" + farmacia.texto() + ";Dispensación;5\n"                 // farmacia alterna
                + anio + ";;" + homil.texto() + ";Dispensación;5\n"                    // HOMIL sin fuerza
                + anio + ";;" + ejc2.texto() + ";Consignación;5\n"                     // modalidad inactiva
                + anio + ";;" + ejc1.texto() + ";Dispensación;7\n"                     // línea repetida en el archivo
                + anio + ";;No existe ESM;Dispensación;-5\n";
        Respuesta s = n.multipart("/api/presupuesto/asignacion/simular", Map.of(), csv("asignacion.csv", malo), "archivo");
        assertThat(s.estado()).as(s.texto()).isEqualTo(200);
        assertThat(s.json().path("validas").asInt()).isEqualTo(2);
        List<String> errores = new ArrayList<>();
        s.json().path("errores").forEach(e -> errores.add(e.path("fila").asInt() + ": " + e.path("motivo").asText()));
        assertThat(errores).hasSize(7);
        assertThat(String.join("\n", errores)).contains("4: La fuerza ARC no corresponde al ESM", "es satélite: su presupuesto está en su ESM centralizador",
                "Solo los ESM tienen presupuesto", "exige la fuerza de adscripción", "La modalidad Consignación no está activa en el presupuesto",
                "9: La línea ya viene en la fila 2", "10: Valor debe ser mayor que 0.");
        assertThat(s.json().path("totalNacional").asText()).isEqualTo("1250000000,50");
        assertThat(jdbc.sql("SELECT count(*) FROM presupuesto_lineas WHERE vigencia_id = :v").param("v", vig).query(Long.class).single()).isZero();

        Respuesta a = n.multipart("/api/presupuesto/asignacion/aplicar", Map.of("soporte", "Oficio DIGSA 123 del 2027-01-15"), csv("asignacion.csv", malo),
                "archivo");
        assertThat(a.estado()).isEqualTo(422);
        assertThat(a.mensaje()).isEqualTo("La carga no se aplicó: 7 fila(s) con errores.");
        assertThat(jdbc.sql("SELECT count(*) FROM presupuesto_movimientos WHERE vigencia_id = :v").param("v", vig).query(Long.class).single()).isZero();

        String bueno = "vigencia;fuerza;esm;modalidad;valor\n"
                + anio + ";EJC;" + ejc1.texto() + ";Dispensación;1.000.000.000,00\n"
                + anio + ";;" + ejc2.texto() + ";Dispensación;900000000\n"
                + anio + ";EJC;" + homil.texto() + ";Dispensación;40000000\n"
                + anio + ";ARC;" + homil.texto() + ";Dispensación;25000000\n";
        assertThat(n.multipart("/api/presupuesto/asignacion/aplicar", Map.of(), csv("b.csv", bueno), "archivo").estado()).as("sin soporte").isEqualTo(400);
        Respuesta ok = n.multipart("/api/presupuesto/asignacion/aplicar", Map.of("soporte", "Oficio DIGSA 123 del 2027-01-15"), csv("b.csv", bueno), "archivo");
        assertThat(ok.estado()).as(ok.texto()).isEqualTo(200);
        assertThat(ok.json().path("filas").asInt()).isEqualTo(4);
        assertThat(asignado(vig, ejc1, "dispensacion")).isEqualByComparingTo("1000000000");
        assertThat(jdbc.sql("SELECT count(DISTINCT fuerza) FROM presupuesto_lineas WHERE vigencia_id = :v AND punto_id = :p").param("v", vig)
                .param("p", homil.id()).query(Long.class).single()).as("HOMIL por fuerza de adscripción").isEqualTo(2);
        assertThat(bitacoraDe(idDe(ultimoUsuario)).getFirst()).contains("presupuesto_carga_aplicada", "4 filas", "total 1965000000,00");
        invarianteSaldos(vig);

        Respuesta otraVez = n.multipart("/api/presupuesto/asignacion/aplicar", Map.of("soporte", "Oficio DIGSA 123 del 2027-01-15"), csv("b.csv", bueno),
                "archivo");
        assertThat(otraVez.estado()).isEqualTo(200);
        assertThat(otraVez.mensaje()).startsWith("Este archivo ya se aplicó el ").contains("(lote " + ok.json().path("lote").asLong() + ")");
        Respuesta yaTiene = n.multipart("/api/presupuesto/asignacion/simular", Map.of(),
                csv("c.csv", "vigencia;fuerza;esm;modalidad;valor\n" + anio + ";;" + ejc1.texto() + ";Dispensación;5\n"), "archivo");
        assertThat(yaTiene.json().path("errores").get(0).path("motivo").asText())
                .isEqualTo("La línea ya tiene presupuesto: use una adición, disminución o reajuste.");
    }

    @Test
    @DisplayName("M7-2: adición, disminución, reajuste, rebalanceo del mockup y recomposición, trazados con antes y después")
    void novedades() {
        Navegador n = coordinador();
        long vig = vigencia(n, ANIO.incrementAndGet());
        assertThat(novedad(n, datos(vig, "asignacion_inicial", "Oficio DIGSA 1 del 2027-01-02",
                List.of(item(ejc1, "dispensacion", "1000000000"), item(ejc2, "dispensacion", "900000000"), item(ejc2, "suministro", "100000000")))).estado())
                .isEqualTo(201);

        // Rebalanceo del mockup: 450 millones de ESM 11 (ejc2) a ESM 07 (ejc1)
        Respuesta reb = novedad(n, datos(vig, "rebalanceo", "Oficio [número] del [fecha]",
                List.of(item(ejc2, "dispensacion", "450000000"), item(ejc1, "dispensacion", "450000000"))));
        assertThat(reb.estado()).as(reb.texto()).isEqualTo(201);
        assertThat(asignado(vig, ejc2, "dispensacion")).isEqualByComparingTo("450000000");
        assertThat(asignado(vig, ejc1, "dispensacion")).isEqualByComparingTo("1450000000");
        assertThat(bitacoraDe(idDe(ultimoUsuario)).getFirst()).contains("rebalanceo · 2 línea(s) · neto 0,00", "«900000000,00» → «450000000,00»",
                "«1000000000,00» → «1450000000,00»");

        assertThat(novedad(n, datos(vig, "adicion", "Oficio 2 del 2027-02-01", List.of(item(ejc1, "requisicion", "50000000")))).estado())
                .as("la adición crea la línea").isEqualTo(201);
        assertThat(novedad(n, datos(vig, "disminucion", "Oficio 3 del 2027-02-01", List.of(item(ejc2, "suministro", "40000000")))).estado()).isEqualTo(201);
        assertThat(novedad(n, datos(vig, "reajuste", "Oficio 4 del 2027-02-01", List.of(item(ejc2, "suministro", "-10000000")))).estado()).isEqualTo(201);
        assertThat(asignado(vig, ejc2, "suministro")).isEqualByComparingTo("50000000");

        Respuesta rec = novedad(n, datos(vig, "recomposicion", "Oficio 5 del 2027-03-01", List.of(item(ejc1, "dispensacion", "300000000"),
                item(ejc2, "dispensacion", "-200000000"), item(ejc2, "suministro", "-49999999.99"))));
        assertThat(rec.estado()).isEqualTo(422);
        assertThat(rec.mensaje()).isEqualTo("La recomposición debe sumar 0,00 (suma 50000000,01).");
        Respuesta sin = novedad(n, datos(vig, "adicion", " ", List.of(item(ejc1, "dispensacion", "1"))));
        assertThat(sin.mensaje()).isEqualTo("El soporte de la DIGSA es obligatorio.");
        Respuesta noExiste = novedad(n, datos(vig, "disminucion", "Oficio 6 del 2027-03-01", List.of(item(arc, "suministro", "1"))));
        assertThat(noExiste.estado()).isEqualTo(422);
        assertThat(noExiste.mensaje()).contains("no tiene presupuesto en la vigencia");
        Respuesta negativo = novedad(n, datos(vig, "disminucion", "Oficio 7 del 2027-03-01", List.of(item(ejc2, "suministro", "60000000"))));
        assertThat(negativo.mensaje()).contains("quedaría con -10000000,00: el asignado no puede ser negativo.");

        // Bajo lo ejecutado: con 420 millones facturados, no se puede bajar la línea a 400
        long linea = jdbc.sql("SELECT id FROM presupuesto_lineas WHERE vigencia_id = :v AND punto_id = :p AND modalidad = 'dispensacion'")
                .param("v", vig).param("p", ejc2.id()).query(Long.class).single();
        jdbc.sql("UPDATE presupuesto_saldos SET facturado = 420000000 WHERE linea_id = :l").param("l", linea).update();
        Respuesta bajo = novedad(n, datos(vig, "disminucion", "Oficio 8 del 2027-03-01", List.of(item(ejc2, "dispensacion", "50000000"))));
        assertThat(bajo.mensaje()).contains("quedaría con 400000000,00, menos que lo ejecutado (420000000,00).");
        jdbc.sql("UPDATE presupuesto_saldos SET facturado = 0 WHERE linea_id = :l").param("l", linea).update();
        invarianteSaldos(vig);

        JsonNode h = n.get("/api/presupuesto/movimientos?vigencia=" + vig + "&punto=" + ejc1.id()).json();
        assertThat(h.path("total").asInt()).as("asignación, rebalanceo y adición de requisición").isEqualTo(3);
        JsonNode rebalanceo = h.path("items").get(1);
        assertThat(rebalanceo.path("tipo").asText()).isEqualTo("rebalanceo");
        assertThat(rebalanceo.path("soporte").asText()).isEqualTo("Oficio [número] del [fecha]");
        // El historial muestra las dos caras del rebalanceo (origen y destino), ordenadas por valor
        assertThat(rebalanceo.path("lineas").size()).isEqualTo(2);
        JsonNode origen = rebalanceo.path("lineas").get(0);
        JsonNode destino = rebalanceo.path("lineas").get(1);
        assertThat(origen.path("esm").asText()).startsWith(ejc2.sigla());
        assertThat(origen.path("antes").asText() + " → " + origen.path("despues").asText()).isEqualTo("900000000,00 → 450000000,00");
        assertThat(destino.path("esm").asText()).startsWith(ejc1.sigla());
        assertThat(destino.path("antes").asText() + " → " + destino.path("despues").asText()).isEqualTo("1000000000,00 → 1450000000,00");
        assertThat(n.get("/api/presupuesto/movimientos?vigencia=" + vig + "&tipo=rebalanceo").json().path("total").asInt()).isEqualTo(1);
        assertThat(n.get("/api/presupuesto/movimientos?tipo=otro").estado()).isEqualTo(422);
    }

    @Test
    @DisplayName("reajuste por porcentaje sobre una fuerza; el residuo va a la línea de mayor asignado")
    void reajustePorcentaje() {
        Navegador n = coordinador();
        long vig = vigencia(n, ANIO.incrementAndGet());
        novedad(n, datos(vig, "asignacion_inicial", "Oficio DIGSA 1 del 2027-01-02",
                List.of(item(ejc1, "dispensacion", "33.33"), item(ejc2, "dispensacion", "33.34"), item(arc, "suministro", "100"))));
        Map<String, Object> d = datos(vig, "reajuste", "Resolución de reajuste IPC", null);
        d.put("porcentaje", new BigDecimal("10"));
        d.put("ambito", Map.of("fuerza", "EJC"));
        Respuesta r = novedad(n, d);
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        assertThat(asignado(vig, ejc1, "dispensacion")).isEqualByComparingTo("36.66");
        assertThat(asignado(vig, ejc2, "dispensacion")).isEqualByComparingTo("36.68");
        assertThat(asignado(vig, arc, "suministro")).as("otra fuerza no cambia").isEqualByComparingTo("100");
        assertThat(jdbc.sql("SELECT porcentaje FROM presupuesto_movimientos WHERE id = :id").param("id", r.json().path("id").asLong()).query(BigDecimal.class)
                .single()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("alcance: Sanidad ARC ve solo lo de la ARC (con su parte de HOMIL); un ESM solo su punto")
    void alcance() {
        Navegador n = coordinador();
        long vig = vigencia(n, ANIO.incrementAndGet());
        Map<String, Object> h = item(homil, "dispensacion", "25000000");
        h.put("fuerza", "ARC");
        Map<String, Object> he = item(homil, "dispensacion", "40000000");
        he.put("fuerza", "EJC");
        assertThat(novedad(n, datos(vig, "asignacion_inicial", "Oficio DIGSA 1 del 2027-01-02",
                List.of(item(ejc1, "dispensacion", "100"), item(arc, "suministro", "200"), h, he))).estado()).isEqualTo(201);
        String sanidad = crearUsuario("sanpre", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'ARC' WHERE usuario = :u").param("u", sanidad).update();
        Navegador s = navegador();
        s.ingresar(sanidad, CLAVE);
        JsonNode lineas = s.get("/api/presupuesto/lineas?vigencia=" + vig).json();
        assertThat(lineas.size()).isEqualTo(2);
        lineas.forEach(l -> assertThat(l.path("fuerza").asText()).isEqualTo("ARC"));
        assertThat(s.get("/api/presupuesto/movimientos?vigencia=" + vig).json().path("items").get(0).path("lineas").size()).isEqualTo(2);
        assertThat(s.get("/api/presupuesto/opciones").json().path("fuerzas").size()).isEqualTo(1);

        String esm = crearUsuario("esmpre", Perfil.ESM);
        jdbc.sql("UPDATE usuarios SET alcance_punto = :p WHERE usuario = :u").param("p", ejc1.id()).param("u", esm).update();
        Navegador e = navegador();
        e.ingresar(esm, CLAVE);
        JsonNode suyas = e.get("/api/presupuesto/lineas?vigencia=" + vig).json();
        assertThat(suyas.size()).isEqualTo(1);
        assertThat(suyas.get(0).path("puntoId").asLong()).isEqualTo(ejc1.id());
    }

    @Test
    @DisplayName("concurrencia: dos disminuciones de 60 sobre 100 a la vez: una se aplica y la otra se rechaza")
    void concurrencia() throws Exception {
        Navegador n = coordinador();
        long vig = vigencia(n, ANIO.incrementAndGet());
        novedad(n, datos(vig, "asignacion_inicial", "Oficio DIGSA 1 del 2027-01-02", List.of(item(ejc1, "suministro", "100000000"))));
        Navegador otro = coordinador();
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService ex = Executors.newFixedThreadPool(2);
        List<Callable<Integer>> tareas = List.of(n, otro).stream().<Callable<Integer>>map(nav -> () -> {
            salida.await();
            return novedad(nav, datos(vig, "disminucion", "Oficio de disminución", List.of(item(ejc1, "suministro", "60000000")))).estado();
        }).toList();
        List<Future<Integer>> f = new ArrayList<>();
        tareas.forEach(t -> f.add(ex.submit(t)));
        salida.countDown();
        List<Integer> estados = new ArrayList<>();
        for (Future<Integer> x : f) {
            estados.add(x.get());
        }
        ex.shutdown();
        assertThat(estados).containsExactlyInAnyOrder(201, 422);
        assertThat(asignado(vig, ejc1, "suministro")).isEqualByComparingTo("40000000");
        invarianteSaldos(vig);
    }

    @Test
    @DisplayName("soporte de la novedad: PDF guardado con huella y descarga; otro tipo se rechaza; fuera del alcance no existe")
    void soporteArchivo() {
        Navegador n = coordinador();
        long vig = vigencia(n, ANIO.incrementAndGet());
        byte[] pdf = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        Respuesta r;
        try {
            r = n.multipart("/api/presupuesto/novedades", Map.of("datos", JSON.writeValueAsString(datos(vig, "asignacion_inicial", "Oficio con archivo adjunto",
                    List.of(item(ejc1, "dispensacion", "10"))))), Map.of("oficio.pdf", pdf), "archivo");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        long sop = jdbc.sql("SELECT soporte_id FROM presupuesto_movimientos WHERE id = :id").param("id", r.json().path("id").asLong()).query(Long.class).single();
        Respuesta d = n.get("/api/presupuesto/soportes/" + sop);
        assertThat(d.estado()).isEqualTo(200);
        assertThat(d.cuerpo()).isEqualTo(pdf);
        String esm = crearUsuario("esmsop", Perfil.ESM);
        jdbc.sql("UPDATE usuarios SET alcance_punto = :p WHERE usuario = :u").param("p", arc.id()).param("u", esm).update();
        Navegador e = navegador();
        e.ingresar(esm, CLAVE);
        assertThat(e.get("/api/presupuesto/soportes/" + sop).estado()).isEqualTo(404);
        Respuesta txt;
        try {
            txt = n.multipart("/api/presupuesto/novedades", Map.of("datos", JSON.writeValueAsString(datos(vig, "adicion", "Oficio con archivo de texto",
                    List.of(item(ejc1, "dispensacion", "10"))))), Map.of("nota.txt", "hola".getBytes(StandardCharsets.UTF_8)), "archivo");
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        assertThat(txt.mensaje()).isEqualTo("El soporte debe ser un PDF o una imagen (JPG o PNG).");
    }

    @Test
    @DisplayName("configuración: centralizador del satélite, modalidades y parámetros con validación y bitácora")
    void configuracion() {
        Navegador n = coordinador();
        assertThat(n.put("/api/presupuesto/puntos/" + satelite.id() + "/centralizador", Map.of("centralizadorId", ejc1.id())).estado()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT centralizador_id FROM puntos WHERE id = :id").param("id", satelite.id()).query(Long.class).single()).isEqualTo(ejc1.id());
        assertThat(n.put("/api/presupuesto/puntos/" + ejc1.id() + "/centralizador", Map.of("centralizadorId", ejc2.id())).estado()).isEqualTo(422);
        assertThat(n.put("/api/presupuesto/parametros/presupuesto_base_umbral", Map.of("valor", "reconocida")).estado()).isEqualTo(422);
        assertThat(n.put("/api/presupuesto/parametros/presupuesto_tolerancia_exceso", Map.of("valor", "12,5 %")).estado()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT valor FROM par_parametros WHERE clave = 'presupuesto_tolerancia_exceso'").query(String.class).single()).isEqualTo("12,50");
        assertThat(bitacoraDe(idDe(ultimoUsuario)).getFirst()).contains("«10,00» → «12,50»");
        n.put("/api/presupuesto/parametros/presupuesto_tolerancia_exceso", Map.of("valor", "10,00"));
        assertThat(n.put("/api/presupuesto/parametros/otra_cosa", Map.of("valor", "1")).estado()).isEqualTo(404);
        assertThat(n.put("/api/presupuesto/modalidades/consignacion", Map.of("activa", true)).estado()).isEqualTo(200);
        assertThat(bitacoraDe(idDe(ultimoUsuario)).getFirst()).contains("activa: «false» → «true»");
        n.put("/api/presupuesto/modalidades/consignacion", Map.of("activa", false));
        assertThat(n.put("/api/presupuesto/modalidades/suministro", Map.of("nombre", "Dispensación")).estado()).isEqualTo(409);
    }
}
