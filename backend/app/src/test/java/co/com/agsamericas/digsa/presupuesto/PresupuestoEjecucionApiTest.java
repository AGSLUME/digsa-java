package co.com.agsamericas.digsa.presupuesto;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Presupuesto F3: ejecución por el puerto (carga, manual, cuentas médicas), versiones del mes, umbrales y notificación formal. */
class PresupuestoEjecucionApiTest extends PruebaIntegracion {

    private static final AtomicInteger ANIO = new AtomicInteger(2060);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String DISAN_EJC = "disan.ejc.presupuesto@example.com";
    static final String DIGSA = "digsa.presupuesto@example.com";

    @Autowired
    PuertoEjecucion puerto;

    record P(long id, String sigla, String fuerza) {
    }

    P ejc1;
    P ejc2;
    P arc;
    P satelite;
    String correosEjc;
    String correosArc;
    String correoDigsa;

    @BeforeEach
    void preparar() {
        List<P> ejc = esm("p.fuerza = 'EJC' AND p.modalidad = 'Dispensación'", 2);
        ejc1 = ejc.get(0);
        ejc2 = ejc.get(1);
        arc = esm("p.fuerza = 'ARC' AND p.modalidad <> 'Satélite'", 1).get(0);
        satelite = esm("p.modalidad = 'Satélite'", 1).get(0);
        correosEjc = jdbc.sql("SELECT correos::text FROM par_fuerzas WHERE codigo = 'EJC'").query(String.class).single();
        correosArc = jdbc.sql("SELECT correos::text FROM par_fuerzas WHERE codigo = 'ARC'").query(String.class).single();
        correoDigsa = jdbc.sql("SELECT valor FROM par_parametros WHERE clave = 'correo_digsa'").query(String.class).optional().orElse("");
        jdbc.sql("UPDATE par_fuerzas SET correos = ARRAY[:c] WHERE codigo = 'EJC'").param("c", DISAN_EJC).update();
        jdbc.sql("UPDATE par_fuerzas SET correos = '{}' WHERE codigo = 'ARC'").update();
        jdbc.sql("UPDATE par_parametros SET valor = :c WHERE clave = 'correo_digsa'").param("c", DIGSA).update();
    }

    @AfterEach
    void restaurar() {
        jdbc.sql("UPDATE par_fuerzas SET correos = CAST(:c AS text[]) WHERE codigo = 'EJC'").param("c", correosEjc).update();
        jdbc.sql("UPDATE par_fuerzas SET correos = CAST(:c AS text[]) WHERE codigo = 'ARC'").param("c", correosArc).update();
        jdbc.sql("UPDATE par_parametros SET valor = :c WHERE clave = 'correo_digsa'").param("c", correoDigsa).update();
        jdbc.sql("UPDATE par_parametros SET valor = 'facturado' WHERE clave = 'presupuesto_base_umbral'").update();
    }

    private List<P> esm(String condicion, int cuantos) {
        List<P> r = jdbc.sql("SELECT p.id, p.sigla, p.fuerza FROM puntos p WHERE p.unidad = 'ESM' AND p.activo AND p.sigla IS NOT NULL AND " + condicion
                + " AND NOT EXISTS (SELECT 1 FROM puntos o WHERE o.id <> p.id AND o.sigla = p.sigla) ORDER BY p.id DESC LIMIT " + cuantos)
                .query((rs, n) -> new P(rs.getLong(1), rs.getString(2), rs.getString(3))).list();
        assertThat(r).hasSize(cuantos);
        return r;
    }

    private String ultimo;

    private Navegador coordinador() {
        Navegador n = navegador();
        ultimo = crearUsuario("coordeje", Perfil.COORDINADOR);
        assertThat(n.ingresar(ultimo, CLAVE).estado()).isEqualTo(200);
        return n;
    }

    /** Vigencia nueva con asignaciones iniciales por línea (punto, modalidad, valor). */
    private long vigenciaConPresupuesto(Navegador n, int anio, Object... lineas) {
        Respuesta v = n.post("/api/presupuesto/vigencias", Map.of("anio", anio));
        assertThat(v.estado()).as(v.texto()).isEqualTo(201);
        long vig = v.json().path("id").asLong();
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < lineas.length; i += 3) {
            Map<String, Object> m = new HashMap<>();
            m.put("puntoId", ((P) lineas[i]).id());
            m.put("modalidad", lineas[i + 1]);
            m.put("valor", new BigDecimal((String) lineas[i + 2]));
            items.add(m);
        }
        Map<String, Object> d = new HashMap<>();
        d.put("vigenciaId", vig);
        d.put("tipo", "asignacion_inicial");
        d.put("soporte", "Oficio DIGSA de asignación");
        d.put("items", items);
        Respuesta r = multipartNovedad(n, d);
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        return vig;
    }

    private static Respuesta multipartNovedad(Navegador n, Map<String, Object> d) {
        try {
            return n.multipart("/api/presupuesto/novedades", Map.of("datos", JSON.writeValueAsString(d)), Map.of(), "archivo");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Respuesta manual(Navegador n, long vig, P p, String periodo, String facturado, String reconocido) {
        Map<String, Object> m = new HashMap<>();
        m.put("vigenciaId", vig);
        m.put("puntoId", p.id());
        m.put("modalidad", "dispensacion");
        m.put("periodo", periodo);
        m.put("facturado", new BigDecimal(facturado));
        m.put("reconocido", reconocido == null ? null : new BigDecimal(reconocido));
        return n.post("/api/presupuesto/ejecucion/manual", m);
    }

    private Map<String, Object> saldo(long vig, P p) {
        return jdbc.sql("""
                SELECT s.asignado, s.facturado, s.reconocido, l.id FROM presupuesto_saldos s JOIN presupuesto_lineas l ON l.id = s.linea_id
                WHERE l.vigencia_id = :v AND l.punto_id = :p AND l.modalidad = 'dispensacion'""").param("v", vig).param("p", p.id()).query().singleRow();
    }

    private List<Integer> umbrales(long linea) {
        return jdbc.sql("SELECT umbral FROM presupuesto_umbrales WHERE linea_id = :l ORDER BY umbral").param("l", linea).query(Integer.class).list();
    }

    private long notificaciones(long linea) {
        return jdbc.sql("SELECT count(*) FROM presupuesto_notificaciones WHERE linea_id = :l AND tipo = 'umbral'").param("l", linea).query(Long.class).single();
    }

    @Test
    @DisplayName("carga de ejecución: el saldo suma las versiones vigentes; la corrección del mes reemplaza sin borrar; mismo archivo, sin duplicar")
    void cargaYCorreccion() {
        Navegador n = coordinador();
        int anio = ANIO.incrementAndGet();
        long vig = vigenciaConPresupuesto(n, anio, ejc1, "dispensacion", "1000000000");
        String csv = "vigencia;fuerza;esm;modalidad;periodo;facturado;reconocido;referencia\n"
                + anio + ";EJC;" + ejc1.sigla() + ";Dispensación;" + anio + "-03;100000000,00;;Oficio 7\n"
                + anio + ";;" + ejc1.sigla() + ";Dispensación;" + anio + "-04;160000000;150000000;\n";
        Respuesta s = n.multipart("/api/presupuesto/ejecucion/simular", Map.of(), Map.of("e.csv", csv.getBytes(StandardCharsets.UTF_8)), "archivo");
        assertThat(s.json().path("validas").asInt()).as(s.texto()).isEqualTo(2);
        Respuesta a = n.multipart("/api/presupuesto/ejecucion/aplicar", Map.of("fuente", "opl"), Map.of("e.csv", csv.getBytes(StandardCharsets.UTF_8)), "archivo");
        assertThat(a.estado()).as(a.texto()).isEqualTo(200);
        assertThat(saldo(vig, ejc1)).containsEntry("facturado", new BigDecimal("260000000.00")).containsEntry("reconocido", new BigDecimal("150000000.00"));
        assertThat(n.multipart("/api/presupuesto/ejecucion/aplicar", Map.of(), Map.of("e.csv", csv.getBytes(StandardCharsets.UTF_8)), "archivo").mensaje())
                .startsWith("Este archivo ya se aplicó el ");

        // Corrección del mes de marzo a mano: reemplaza la versión, no suma
        assertThat(manual(n, vig, ejc1, anio + "-03", "90000000", "85000000").estado()).isEqualTo(201);
        assertThat(saldo(vig, ejc1)).containsEntry("facturado", new BigDecimal("250000000.00")).containsEntry("reconocido", new BigDecimal("235000000.00"));
        JsonNode versiones = n.get("/api/presupuesto/ejecucion?vigencia=" + vig + "&periodo=" + anio + "-03").json();
        assertThat(versiones.path("total").asInt()).as("las dos versiones de marzo").isEqualTo(2);
        assertThat(versiones.path("items").get(0).path("vigente").asBoolean()).isTrue();
        assertThat(versiones.path("items").get(1).path("facturado").asText()).isEqualTo("100000000,00");
        assertThat(versiones.path("items").get(1).path("reemplazada").isNull()).isFalse();
        assertThat(bitacoraDe(idDe(ultimo))).anyMatch(f -> f.contains("Ejecución manual") && f.contains("facturado 90000000,00"));

        String malo = "vigencia;fuerza;esm;modalidad;periodo;facturado;reconocido\n"
                + anio + ";;" + ejc1.sigla() + ";Dispensación;" + (anio - 1) + "-12;1;\n"
                + anio + ";;" + ejc1.sigla() + ";Dispensación;" + anio + "-05;10;12\n";
        JsonNode e = n.multipart("/api/presupuesto/ejecucion/simular", Map.of(), Map.of("m.csv", malo.getBytes(StandardCharsets.UTF_8)), "archivo").json();
        assertThat(e.path("errores").get(0).path("motivo").asText()).contains("está fuera de la vigencia " + anio);
        assertThat(e.path("errores").get(1).path("motivo").asText()).isEqualTo("El reconocido (12,00) no puede superar el facturado (10,00).");
    }

    @Test
    @DisplayName("satélite: su ejecución va a la línea del centralizador; sin centralizador se rechaza")
    void satelite() {
        Navegador n = coordinador();
        int anio = ANIO.incrementAndGet();
        long vig = vigenciaConPresupuesto(n, anio, ejc1, "dispensacion", "1000");
        jdbc.sql("UPDATE puntos SET centralizador_id = NULL WHERE id = :s").param("s", satelite.id()).update();
        String csv = "vigencia;fuerza;esm;modalidad;periodo;facturado\n" + anio + ";;" + satelite.sigla() + ";Dispensación;" + anio + "-02;5\n";
        assertThat(n.multipart("/api/presupuesto/ejecucion/simular", Map.of(), Map.of("s.csv", csv.getBytes(StandardCharsets.UTF_8)), "archivo").json()
                .path("errores").get(0).path("motivo").asText()).contains("es satélite y no tiene ESM centralizador registrado");
        assertThat(n.put("/api/presupuesto/puntos/" + satelite.id() + "/centralizador", Map.of("centralizadorId", ejc1.id())).estado()).isEqualTo(200);
        Respuesta a = n.multipart("/api/presupuesto/ejecucion/aplicar", Map.of(), Map.of("s.csv", csv.getBytes(StandardCharsets.UTF_8)), "archivo");
        assertThat(a.estado()).as(a.texto()).isEqualTo(200);
        assertThat(saldo(vig, ejc1)).containsEntry("facturado", new BigDecimal("5.00"));
        assertThat(n.get("/api/presupuesto/ejecucion?vigencia=" + vig).json().path("items").get(0).path("satelite").asText()).startsWith(satelite.sigla());
    }

    @Test
    @DisplayName("umbrales: el primero notifica a DISAN EJC y a la DIGSA con consecutivo; varios de una vez avisan solo el más alto; no se repiten")
    void umbrales() {
        Navegador n = coordinador();
        int anio = ANIO.incrementAndGet();
        long vig = vigenciaConPresupuesto(n, anio, ejc1, "dispensacion", "1000000000");
        long linea = ((Number) saldo(vig, ejc1).get("id")).longValue();
        assertThat(manual(n, vig, ejc1, anio + "-01", "240000000", null).estado()).isEqualTo(201);
        assertThat(umbrales(linea)).isEmpty();
        assertThat(manual(n, vig, ejc1, anio + "-01", "260000000", null).estado()).isEqualTo(201);
        assertThat(umbrales(linea)).containsExactly(25);
        Map<String, Object> notif = jdbc.sql("SELECT consecutivo, estado, para::text AS para, asunto FROM presupuesto_notificaciones WHERE linea_id = :l AND tipo = 'umbral'")
                .param("l", linea).query().singleRow();
        assertThat((String) notif.get("consecutivo")).matches("PRE-\\d{4}-\\d{4,}");
        assertThat(notif.get("estado")).isEqualTo("encolada");
        assertThat((String) notif.get("para")).contains(DISAN_EJC, DIGSA);
        assertThat((String) notif.get("asunto")).contains("alcanzó el 25 % del presupuesto (26,00 %)");
        Correo.Mensaje m = esperarCorreoA(DISAN_EJC, (String) notif.get("consecutivo"));
        assertThat(m.html()).contains("Umbral alcanzado", "25 %", "1000000000,00", "260000000,00", "Generado por la interventoría");
        assertThat(jdbc.sql("SELECT count(*) FROM alertas_eventos WHERE tipo = 'presupuesto_umbral' AND clave = :c").param("c", "pu:" + anio + ":" + linea + ":25")
                .query(Long.class).single()).isEqualTo(1);

        // De 26 % a 80 % en un cambio: quedan 50 y 75, y solo se notifica el 75
        assertThat(manual(n, vig, ejc1, anio + "-02", "540000000", null).estado()).isEqualTo(201);
        assertThat(umbrales(linea)).containsExactly(25, 50, 75);
        assertThat(notificaciones(linea)).isEqualTo(2);
        assertThat(jdbc.sql("SELECT notificada FROM presupuesto_umbrales WHERE linea_id = :l AND umbral = 50").param("l", linea).query(Boolean.class).single())
                .isFalse();

        // Una adición baja el % y luego se vuelve a cruzar el 75: no se notifica otra vez
        Map<String, Object> ad = new HashMap<>();
        ad.put("vigenciaId", vig);
        ad.put("tipo", "adicion");
        ad.put("soporte", "Oficio de adición");
        ad.put("items", List.of(Map.of("puntoId", ejc1.id(), "modalidad", "dispensacion", "valor", new BigDecimal("500000000"))));
        assertThat(multipartNovedad(n, ad).estado()).isEqualTo(201);
        assertThat(manual(n, vig, ejc1, anio + "-03", "400000000", null).estado()).isEqualTo(201); // 1.200 de 1.500 = 80 %
        assertThat(notificaciones(linea)).isEqualTo(2);

        // Exceso: pasa del 100 % sin bloquearse, saldo negativo y notificación del 100 %
        assertThat(manual(n, vig, ejc1, anio + "-04", "520000000", null).estado()).isEqualTo(201); // 1.720 de 1.500
        assertThat(umbrales(linea)).containsExactly(25, 50, 75, 100);
        JsonNode l = n.get("/api/presupuesto/lineas?vigencia=" + vig).json().get(0);
        assertThat(l.path("saldo").asText()).isEqualTo("-220000000,00");
        assertThat(l.path("porcentaje").asText()).isEqualTo("114,67");
        assertThat(l.path("semaforo").asText()).isEqualTo("rojo");
    }

    @Test
    @DisplayName("una disminución también puede cruzar un umbral; base reconocido; línea sin asignado notifica el 100 %")
    void otrosCruces() {
        Navegador n = coordinador();
        int anio = ANIO.incrementAndGet();
        long vig = vigenciaConPresupuesto(n, anio, ejc1, "dispensacion", "1000", ejc2, "dispensacion", "1000");
        long l1 = ((Number) saldo(vig, ejc1).get("id")).longValue();
        assertThat(manual(n, vig, ejc1, anio + "-01", "200", null).estado()).isEqualTo(201);
        assertThat(umbrales(l1)).isEmpty();
        Map<String, Object> dis = new HashMap<>();
        dis.put("vigenciaId", vig);
        dis.put("tipo", "disminucion");
        dis.put("soporte", "Oficio de disminución");
        dis.put("items", List.of(Map.of("puntoId", ejc1.id(), "modalidad", "dispensacion", "valor", new BigDecimal("600"))));
        assertThat(multipartNovedad(n, dis).estado()).isEqualTo(201); // 200 de 400 = 50 %
        assertThat(umbrales(l1)).containsExactly(25, 50);

        jdbc.sql("UPDATE par_parametros SET valor = 'reconocido' WHERE clave = 'presupuesto_base_umbral'").update();
        long l2 = ((Number) saldo(vig, ejc2).get("id")).longValue();
        assertThat(manual(n, vig, ejc2, anio + "-01", "300", "240").estado()).isEqualTo(201);
        assertThat(umbrales(l2)).as("24 % sobre lo reconocido").isEmpty();
        assertThat(manual(n, vig, ejc2, anio + "-01", "300", "250").estado()).isEqualTo(201);
        assertThat(umbrales(l2)).containsExactly(25);
        jdbc.sql("UPDATE par_parametros SET valor = 'facturado' WHERE clave = 'presupuesto_base_umbral'").update();

        // ARC sin asignado y sin buzón: los cuatro umbrales, notificación del 100 % solo a la DIGSA, marcada para reenviar
        assertThat(manual(n, vig, arc, anio + "-01", "1000", null).estado()).isEqualTo(201);
        long l3 = jdbc.sql("SELECT id FROM presupuesto_lineas WHERE vigencia_id = :v AND punto_id = :p").param("v", vig).param("p", arc.id()).query(Long.class)
                .single();
        assertThat(umbrales(l3)).containsExactly(25, 50, 75, 100);
        Map<String, Object> notif = jdbc.sql("SELECT id, estado, asunto FROM presupuesto_notificaciones WHERE linea_id = :l AND tipo = 'umbral'").param("l", l3).query().singleRow();
        assertThat(notif.get("estado")).isEqualTo("sin_buzon_fuerza");
        assertThat((String) notif.get("asunto")).contains("sin presupuesto asignado");
        long id = ((Number) notif.get("id")).longValue();
        assertThat(n.post("/api/presupuesto/notificaciones/" + id + "/reenviar", Map.of()).estado()).as("aún sin buzón").isEqualTo(422);
        jdbc.sql("UPDATE par_fuerzas SET correos = ARRAY['disan.arc.presupuesto@example.com'] WHERE codigo = 'ARC'").update();
        assertThat(n.post("/api/presupuesto/notificaciones/" + id + "/reenviar", Map.of()).estado()).isEqualTo(200);
        esperarCorreoA("disan.arc.presupuesto@example.com", "PRE-");
    }

    @Test
    @DisplayName("concurrencia: dos registros a la vez que cruzan el 50 % dejan un solo umbral y una sola notificación")
    void concurrencia() throws Exception {
        Navegador n = coordinador();
        int anio = ANIO.incrementAndGet();
        long vig = vigenciaConPresupuesto(n, anio, ejc1, "dispensacion", "1000");
        long linea = ((Number) saldo(vig, ejc1).get("id")).longValue();
        Navegador otro = coordinador();
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService ex = Executors.newFixedThreadPool(2);
        List<Future<Integer>> f = new ArrayList<>();
        f.add(ex.submit(() -> {
            salida.await();
            return manual(n, vig, ejc1, anio + "-01", "300", null).estado();
        }));
        f.add(ex.submit(() -> {
            salida.await();
            return manual(otro, vig, ejc1, anio + "-02", "300", null).estado();
        }));
        salida.countDown();
        for (Future<Integer> x : f) {
            assertThat(x.get()).isEqualTo(201);
        }
        ex.shutdown();
        assertThat(saldo(vig, ejc1)).containsEntry("facturado", new BigDecimal("600.00"));
        assertThat(umbrales(linea)).containsExactly(25, 50);
        assertThat(notificaciones(linea)).as("25 en el primero y 50 en el segundo, o solo 50 si llegaron juntos").isBetween(1L, 2L);
        assertThat(jdbc.sql("SELECT count(*) FROM presupuesto_umbrales WHERE linea_id = :l AND notificada AND umbral = 50").param("l", linea).query(Long.class)
                .single()).isEqualTo(1);
    }

    @Test
    @DisplayName("puerto para cuentas médicas: todo o nada, fuente cuentas_medicas, sin usuario")
    void puertoCuentasMedicas() {
        Navegador n = coordinador();
        int anio = ANIO.incrementAndGet();
        long vig = vigenciaConPresupuesto(n, anio, ejc1, "dispensacion", "1000");
        PuertoEjecucion.Resultado malo = puerto.registrarCortes(List.of(
                new PuertoEjecucion.Corte(anio, null, ejc1.sigla(), "Dispensación", YearMonth.of(anio, 5), new BigDecimal("10"), null, "Acta 1"),
                new PuertoEjecucion.Corte(anio, null, "No existe", "Dispensación", YearMonth.of(anio, 5), new BigDecimal("10"), null, "Acta 1")), "acta-1");
        assertThat(malo.aplicado()).isFalse();
        assertThat(saldo(vig, ejc1)).containsEntry("facturado", new BigDecimal("0.00"));
        PuertoEjecucion.Resultado ok = puerto.registrarCortes(List.of(
                new PuertoEjecucion.Corte(anio, null, ejc1.sigla(), "Dispensación", YearMonth.of(anio, 5), new BigDecimal("10"), new BigDecimal("8"), "Acta 1")),
                "acta-1");
        assertThat(ok.aplicado()).isTrue();
        assertThat(saldo(vig, ejc1)).containsEntry("facturado", new BigDecimal("10.00")).containsEntry("reconocido", new BigDecimal("8.00"));
        assertThat(jdbc.sql("SELECT fuente FROM presupuesto_ejecucion e JOIN presupuesto_lineas l ON l.id = e.linea_id WHERE l.vigencia_id = :v")
                .param("v", vig).query(String.class).single()).isEqualTo("cuentas_medicas");
    }

    @Test
    @DisplayName("alcance de las notificaciones: Sanidad ARC no ve las del Ejército; la DIGSA sí; Auditor 403")
    void alcanceNotificaciones() {
        Navegador n = coordinador();
        int anio = ANIO.incrementAndGet();
        long vig = vigenciaConPresupuesto(n, anio, ejc1, "dispensacion", "100");
        manual(n, vig, ejc1, anio + "-01", "30", null);
        String sanidad = crearUsuario("saneje", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'ARC' WHERE usuario = :u").param("u", sanidad).update();
        Navegador s = navegador();
        s.ingresar(sanidad, CLAVE);
        assertThat(s.get("/api/presupuesto/notificaciones?tipo=umbral&vigencia=" + vig).json().path("total").asInt()).isZero();
        Navegador d = navegador();
        d.ingresar(crearUsuario("digeje", Perfil.DIGSA), CLAVE);
        assertThat(d.get("/api/presupuesto/notificaciones?tipo=umbral&vigencia=" + vig).json().path("total").asInt()).isEqualTo(1);
        Navegador au = navegador();
        au.ingresar(crearUsuario("audeje", Perfil.AUDITOR), CLAVE);
        assertThat(au.get("/api/presupuesto/notificaciones").estado()).isEqualTo(403);
        assertThat(d.post("/api/presupuesto/ejecucion/manual", Map.of()).estado()).as("la DIGSA no registra").isEqualTo(403);
    }

    @Test
    @DisplayName("registro manual: reconocido mayor, periodo fuera de la vigencia, vigencia cerrada, satélite sin centralizador; la DIGSA no reenvía")
    void manualRechazos() {
        Navegador n = coordinador();
        int anio = ANIO.incrementAndGet();
        long vig = vigenciaConPresupuesto(n, anio, ejc1, "dispensacion", "1000");
        assertThat(manual(n, vig, ejc1, anio + "-01", "10", "12").mensaje()).isEqualTo("El reconocido (12,00) no puede superar el facturado (10,00).");
        assertThat(manual(n, vig, ejc1, (anio + 1) + "-01", "10", null).mensaje()).isEqualTo("El periodo " + (anio + 1) + "-01 está fuera de la vigencia " + anio + ".");
        assertThat(manual(n, vig, ejc1, "enero", "10", null).mensaje()).contains("AAAA-MM");
        jdbc.sql("UPDATE puntos SET centralizador_id = NULL WHERE id = :s").param("s", satelite.id()).update();
        assertThat(manual(n, vig, satelite, anio + "-01", "10", null).mensaje()).contains("no tiene ESM centralizador registrado");
        assertThat(n.put("/api/presupuesto/vigencias/" + vig, Map.of("estado", "cerrada")).estado()).isEqualTo(200);
        assertThat(manual(n, vig, ejc1, anio + "-01", "10", null).mensaje()).contains("está cerrada");
        Navegador d = navegador();
        d.ingresar(crearUsuario("digree", Perfil.DIGSA), CLAVE);
        assertThat(d.post("/api/presupuesto/notificaciones/1/reenviar", Map.of()).estado()).isEqualTo(403);
    }

    @Test
    @DisplayName("notificación: sin buzón de la DIGSA queda marcada; correo agotado se muestra fallido y se puede reenviar; una completa no")
    void notificacionIncompleta() {
        Navegador n = coordinador();
        int anio = ANIO.incrementAndGet();
        long vig = vigenciaConPresupuesto(n, anio, ejc1, "dispensacion", "100", ejc2, "dispensacion", "100");
        jdbc.sql("UPDATE par_parametros SET valor = '' WHERE clave = 'correo_digsa'").update();
        manual(n, vig, ejc1, anio + "-01", "30", null);
        long l1 = ((Number) saldo(vig, ejc1).get("id")).longValue();
        Map<String, Object> sinDigsa = jdbc.sql("SELECT id, estado FROM presupuesto_notificaciones WHERE linea_id = :l AND tipo = 'umbral'").param("l", l1).query().singleRow();
        assertThat(sinDigsa.get("estado")).isEqualTo("sin_buzon_digsa");
        assertThat(bitacoraDe(idDe(ultimo))).anyMatch(f -> f.contains("sin buzon digsa"));
        jdbc.sql("UPDATE par_parametros SET valor = :c WHERE clave = 'correo_digsa'").param("c", DIGSA).update();
        assertThat(n.post("/api/presupuesto/notificaciones/" + sinDigsa.get("id") + "/reenviar", Map.of()).estado()).isEqualTo(200);
        esperarCorreoA(DIGSA, "PRE-");

        manual(n, vig, ejc2, anio + "-01", "30", null);
        long l2 = ((Number) saldo(vig, ejc2).get("id")).longValue();
        Map<String, Object> completa = jdbc.sql("SELECT id, correo_id FROM presupuesto_notificaciones WHERE linea_id = :l AND tipo = 'umbral'").param("l", l2).query().singleRow();
        long id = ((Number) completa.get("id")).longValue();
        assertThat(n.post("/api/presupuesto/notificaciones/" + id + "/reenviar", Map.of()).mensaje()).isEqualTo("La notificación ya salió completa.");
        // El correo agotó sus intentos en la bandeja: la lista la muestra fallida y el reenvío vuelve a encolarla
        esperar(() -> !"pendiente".equals(jdbc.sql("SELECT estado FROM correos_salida WHERE id = :c").param("c", completa.get("correo_id"))
                .query(String.class).single()), "el correo salió de pendiente");
        jdbc.sql("UPDATE correos_salida SET estado = 'fallido', enviado = NULL WHERE id = :c").param("c", completa.get("correo_id")).update();
        JsonNode lista = n.get("/api/presupuesto/notificaciones?vigencia=" + vig).json();
        assertThat(lista.path("items").findValuesAsText("estado")).contains("fallida");
        assertThat(n.post("/api/presupuesto/notificaciones/" + id + "/reenviar", Map.of()).estado()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT correo_id FROM presupuesto_notificaciones WHERE id = :id").param("id", id).query(Long.class).single())
                .isNotEqualTo(((Number) completa.get("correo_id")).longValue());
    }

    @Test
    @DisplayName("puerto: más de 2 decimales y cortes repetidos se rechazan sin registrar nada")
    void puertoValidaciones() {
        Navegador n = coordinador();
        int anio = ANIO.incrementAndGet();
        long vig = vigenciaConPresupuesto(n, anio, ejc1, "dispensacion", "1000");
        YearMonth mes = YearMonth.of(anio, 2);
        assertThat(puerto.registrarCortes(List.of(new PuertoEjecucion.Corte(anio, null, ejc1.sigla(), "Dispensación", mes, new BigDecimal("1.005"), null, null)),
                "x").aplicado()).isFalse();
        PuertoEjecucion.Resultado dup = puerto.registrarCortes(List.of(
                new PuertoEjecucion.Corte(anio, null, ejc1.sigla(), "Dispensación", mes, new BigDecimal("1"), null, null),
                new PuertoEjecucion.Corte(anio, null, ejc1.sigla(), "Dispensación", mes, new BigDecimal("2"), null, null)), "x");
        assertThat(dup.errores()).singleElement().asString().contains("repite el ESM");
        assertThat(saldo(vig, ejc1)).containsEntry("facturado", new BigDecimal("0.00"));
    }
}
