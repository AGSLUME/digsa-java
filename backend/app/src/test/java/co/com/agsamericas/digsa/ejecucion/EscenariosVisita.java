package co.com.agsamericas.digsa.ejecucion;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;

/** Escenario común de una visita en ejecución (líder QF y regente) y ayudantes para calificar, firmar y completar el acta. */
abstract class EscenariosVisita extends PruebaIntegracion {

    protected static final String OPL = "opl.actas@example.com";
    protected static final DateTimeFormatter FH = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    @BeforeEach
    void destinatarios() {
        if (jdbc.sql("SELECT count(*) FROM opl WHERE activo").query(Long.class).single() == 0) {
            jdbc.sql("INSERT INTO opl (nombre, correos) VALUES ('Operador de prueba', ARRAY[:c])").param("c", OPL).update();
        } else {
            jdbc.sql("UPDATE opl SET correos = ARRAY[:c] WHERE activo").param("c", OPL).update();
        }
        jdbc.sql("UPDATE par_fuerzas SET correos = ARRAY['disan.ejc@example.com'] WHERE codigo = 'EJC'").update();
    }

    protected String auditor(String prefijo, String rol) {
        String u = crearUsuario(prefijo, Perfil.AUDITOR);
        jdbc.sql("INSERT INTO auditores (usuario_id, rol) VALUES (:id, :r)").param("id", idDe(u)).param("r", rol).update();
        return u;
    }

    protected Navegador entrar(String usuario) {
        Navegador n = navegador();
        assertThat(n.ingresar(usuario, CLAVE).estado()).isEqualTo(200);
        return n;
    }

    /** Visita en ejecución con C00 (habilitación) y C08, el líder QF y el regente con C08. */
    protected record Escenario(long id, String numero, Navegador coord, Navegador lider, Navegador regente, String liderUsuario) {
    }

    protected Escenario escenario() {
        String qf = auditor("qf", "quimico_farmaceutico");
        String reg = auditor("reg", "regente");
        long punto = jdbc.sql("""
                INSERT INTO puntos (nombre, unidad, fuerza, modalidad, municipio, correos, creado_por)
                VALUES (:n, 'ESM', 'EJC', 'Dispensación', '05001', ARRAY['contacto.punto@example.com'], 'prueba') RETURNING id""")
                .param("n", unico("Punto ejecucion ")).query(Long.class).single();
        Navegador coord = entrar(crearUsuario("coord", Perfil.COORDINADOR));
        Map<String, Object> d = new HashMap<>();
        d.put("tipo", "anual");
        d.put("puntoId", punto);
        d.put("fecha", LocalDate.now(reloj.withZone(Fechas.BOGOTA)).toString());
        d.put("liderId", idDe(qf));
        d.put("otros", List.of(idDe(reg)));
        d.put("alcance", "Habilitación y almacenamiento.");
        d.put("componentes", List.of("C00", "C08"));
        d.put("reparto", Map.of("C00", idDe(qf), "C08", idDe(reg), "OBL", idDe(qf)));
        Respuesta r = coord.post("/api/agenda", d);
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        long id = r.json().path("id").asLong();
        Navegador lider = entrar(qf);
        assertThat(lider.post("/api/mis-visitas/" + id + "/iniciar", null).estado()).isEqualTo(200);
        return new Escenario(id, r.json().path("numero").asText(), coord, lider, entrar(reg), qf);
    }

    protected static List<JsonNode> items(JsonNode formulario, String componente) {
        List<JsonNode> r = new ArrayList<>();
        formulario.path("componentes").forEach(c -> {
            if (c.path("codigo").asText().equals(componente)) {
                c.path("items").forEach(r::add);
            }
        });
        return r;
    }

    protected static List<Map<String, Object>> calificar(List<JsonNode> items, String calificacion) {
        List<Map<String, Object>> r = new ArrayList<>();
        for (JsonNode i : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item", i.path("codigo").asText());
            m.put("calificacion", calificacion);
            m.put("hallazgo", "NC".equals(calificacion) ? "No se encontró el soporte de " + i.path("codigo").asText() : null);
            r.add(m);
        }
        return r;
    }

    static byte[] firmaPng() throws Exception {
        BufferedImage img = new BufferedImage(240, 80, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setColor(java.awt.Color.BLACK);
        g.drawLine(10, 60, 200, 20);
        g.drawLine(20, 20, 220, 70);
        g.dispose();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(img, "png", b);
        return b.toByteArray();
    }

    protected static String base64(byte[] b) {
        return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(b);
    }

    protected String hace(int minutos) {
        return FH.format(LocalDateTime.now(reloj.withZone(Fechas.BOGOTA)).minusMinutes(minutos));
    }

    /** Deja la visita lista para enviar el acta: todo calificado (C), datos, personal y las dos firmas. */
    protected void completar(Escenario e) throws Exception {
        String base = "/api/visitas/" + e.id();
        JsonNode f = e.lider().get(base + "/formulario").json();
        assertThat(e.lider().put(base + "/respuestas", Map.of("respuestas", calificar(items(f, "C00"), "C"))).estado()).isEqualTo(200);
        assertThat(e.regente().put(base + "/respuestas", Map.of("respuestas", calificar(items(f, "C08"), "C"))).estado()).isEqualTo(200);
        Map<String, Object> ej = new HashMap<>();
        ej.put("inicioReal", hace(60));
        ej.put("finReal", hace(5));
        ej.put("objeto", "Seguimiento");
        assertThat(e.lider().put(base + "/ejecucion", ej).estado()).isEqualTo(200);
        assertThat(e.lider().put(base + "/personal", Map.of("personas", List.of(Map.of("entidad", "ESM", "nombre", "Regente del punto")))).estado())
                .isEqualTo(200);
        String png = base64(firmaPng());
        assertThat(e.lider().post(base + "/firmas", Map.of("rol", "auditor", "nombre", "Auditor líder", "imagen", png)).estado()).isEqualTo(200);
        assertThat(e.lider().post(base + "/firmas", Map.of("rol", "esm", "nombre", "Regente del punto", "imagen", png)).estado()).isEqualTo(200);
    }

    /** 5W2H del auditor (fecha, qué y fin de cada hallazgo) y las dos firmas del plan. */
    protected void planYFirmas(Escenario e, List<String> claves) throws Exception {
        String base = "/api/visitas/" + e.id();
        LocalDate hoy = LocalDate.now(reloj.withZone(Fechas.BOGOTA));
        List<Map<String, Object>> filas = new ArrayList<>();
        for (String c : claves) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("clave", c);
            m.put("causas", "Falta de control del registro");
            m.put("que", "Implementar el control diario del soporte");
            m.put("porque", "Cumplir la habilitación");
            m.put("donde", "Farmacia del punto");
            m.put("inicio", hoy.toString());
            m.put("fin", hoy.plusDays(30).toString());
            m.put("indicador", "Registros completos / registros esperados");
            m.put("estado", "Pendiente");
            filas.add(m);
        }
        Respuesta p = e.lider().put(base + "/plan", Map.of("fecha", hoy.toString(), "filas", filas));
        assertThat(p.estado()).as(p.texto()).isEqualTo(200);
        String png = base64(firmaPng());
        assertThat(e.lider().post(base + "/firmas", Map.of("rol", "w5_auditor", "nombre", "Auditor líder", "imagen", png)).estado()).isEqualTo(200);
        assertThat(e.lider().post(base + "/firmas", Map.of("rol", "w5_auditado", "nombre", "Regente del punto", "imagen", png)).estado()).isEqualTo(200);
    }

    /**
     * Visita completa con los primeros {@code n} ítems de C00 en NC, su 5W2H y el acta enviada. @return el token del enlace del OPL, que
     * solo viaja en el correo del acta
     */
    protected String cerrarConHallazgos(Escenario e, int n) throws Exception {
        String base = "/api/visitas/" + e.id();
        completar(e);
        JsonNode f = e.lider().get(base + "/formulario").json();
        List<JsonNode> c00 = items(f, "C00");
        assertThat(e.lider().put(base + "/respuestas", Map.of("respuestas", calificar(c00.subList(0, n), "NC"))).estado()).isEqualTo(200);
        planYFirmas(e, c00.subList(0, n).stream().map(i -> "it:" + i.path("codigo").asText()).toList());
        Respuesta envio = e.lider().post(base + "/acta", null);
        assertThat(envio.json().path("ok").asBoolean()).as(envio.texto()).isTrue();
        return token(e);
    }

    /** Firmas de la ronda vigente (decisión D12: cada ronda del acta se firma de nuevo); con hallazgos, también las del 5W2H. */
    protected void firmarRonda(Escenario e, boolean conPlan) throws Exception {
        String base = "/api/visitas/" + e.id();
        String png = base64(firmaPng());
        List<String> roles = conPlan ? List.of("auditor", "esm", "w5_auditor", "w5_auditado") : List.of("auditor", "esm");
        for (String rol : roles) {
            Respuesta r = e.lider().post(base + "/firmas", Map.of("rol", rol, "nombre", "auditor".equals(rol) || "w5_auditor".equals(rol) ? "Auditor líder" : "Regente del punto",
                    "imagen", png));
            assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        }
    }

    /** Token del enlace del OPL en el último correo del acta de la visita. */
    protected String token(Escenario e) {
        Correo.Mensaje m = correos().reversed().stream().filter(x -> x.asunto().contains(e.numero()) && x.asunto().startsWith("Acta")).findFirst().orElseThrow();
        java.util.regex.Matcher t = java.util.regex.Pattern.compile("opl-responder\\?t=([0-9a-f]{64})").matcher(m.html());
        assertThat(t.find()).as("el correo del acta trae el enlace del OPL").isTrue();
        return t.group(1);
    }
}
