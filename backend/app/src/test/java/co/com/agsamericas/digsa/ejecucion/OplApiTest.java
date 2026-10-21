package co.com.agsamericas.digsa.ejecucion;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Flujo del OPL después del acta (docs/especificaciones/flujo-opl): enlace del QR con código de 6 dígitos al correo del OPL, aceptar u
 * objetar, soportes, quién y cómo, firma del plan, decisión del líder, alcance del usuario OPL y tarea diaria con el reporte a la DIGSA.
 */
class OplApiTest extends EscenariosVisita {

    private static List<String> claves(JsonNode detalle) {
        List<String> r = new ArrayList<>();
        detalle.path("hallazgos").forEach(h -> r.add(h.path("clave").asText()));
        return r;
    }

    private String codigoEnviado() {
        Correo.Mensaje m = correos().reversed().stream().filter(x -> x.para().contains(OPL) && x.asunto().startsWith("Código")).findFirst().orElseThrow();
        Matcher c = Pattern.compile("<strong>([0-9]{6})</strong>").matcher(m.html());
        assertThat(c.find()).isTrue();
        return c.group(1);
    }

    /** Abre el enlace y verifica el código, como el OPL desde el QR. */
    private Navegador qrVerificado(String token) {
        Navegador q = navegador();
        Respuesta abre = q.get("/api/opl-publico/enlace?t=" + token);
        assertThat(abre.estado()).as(abre.texto()).isEqualTo(200);
        assertThat(q.post("/api/opl-publico/codigo", Map.of("indice", 0)).estado()).isEqualTo(200);
        assertThat(q.post("/api/opl-publico/verificar", Map.of("codigo", codigoEnviado())).estado()).isEqualTo(200);
        return q;
    }

    private static Map<String, Object> respuesta(String clave, String decision, String justificacion) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("clave", clave);
        m.put("decision", decision);
        m.put("justificacion", justificacion);
        m.put("nombre", "Coordinadora del OPL");
        m.put("cargo", "Coordinadora de calidad");
        return m;
    }

    private static Map<String, Object> firma() throws Exception {
        return Map.of("nombre", "Coordinadora del OPL", "cargo", "Coordinadora de calidad", "documento", "52123456", "imagen", base64(firmaPng()));
    }

    @Test
    @DisplayName("QR: enlace, código solo al OPL, aceptar y objetar, soportes, decisión del líder, quién y cómo y firma del plan una vez")
    void flujoPorQr() throws Exception {
        Escenario e = escenario();
        String token = cerrarConHallazgos(e, 2);
        Navegador q = navegador();
        assertThat(q.get("/api/opl-publico/enlace?t=" + "a".repeat(64)).estado()).isEqualTo(404);
        Respuesta abre = q.get("/api/opl-publico/enlace?t=" + token);
        assertThat(abre.estado()).as(abre.texto()).isEqualTo(200);
        assertThat(abre.json().path("numero").asText()).isEqualTo(e.numero());
        assertThat(abre.texto()).doesNotContain(OPL).doesNotContain("contacto.punto");
        assertThat(abre.json().path("correos").get(0).asText()).startsWith("o").contains("*").endsWith("@example.com");
        assertThat(q.get("/api/opl-publico/detalle").estado()).as("sin código verificado").isEqualTo(403);
        assertThat(q.post("/api/opl-publico/respuestas", respuesta("x", "acepta", null)).estado()).isEqualTo(403);
        assertThat(q.sinCsrfUnaVez().post("/api/opl-publico/codigo", Map.of("indice", 0)).estado()).isIn(403, 419);

        assertThat(q.post("/api/opl-publico/codigo", Map.of("indice", 0)).estado()).isEqualTo(200);
        String codigo = codigoEnviado();
        Navegador intruso = navegador();
        assertThat(intruso.get("/api/opl-publico/enlace?t=" + token).estado()).isEqualTo(200);
        assertThat(intruso.post("/api/opl-publico/verificar", Map.of("codigo", "123456")).mensaje())
                .as("el código está atado a la sesión que lo pidió: otra no le gasta los intentos").contains("Primero pida el código");
        String errado = codigo.equals("000000") ? "111111" : "000000";
        assertThat(q.post("/api/opl-publico/verificar", Map.of("codigo", errado)).estado()).isEqualTo(422);
        assertThat(q.post("/api/opl-publico/verificar", Map.of("codigo", codigo)).estado()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM opl_codigos WHERE visita_id = :v AND usado IS NOT NULL").param("v", e.id()).query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM opl_codigos WHERE codigo_hash = :c").param("c", codigo).query(Long.class).single())
                .as("el código no se guarda en claro").isZero();

        JsonNode det = q.get("/api/opl-publico/detalle").json();
        assertThat(det.path("fase").asText()).isEqualTo("objeciones");
        List<String> cl = claves(det);
        assertThat(cl).hasSize(2);
        assertThat(det.path("hallazgos").get(0).path("estado").asText()).isEqualTo("sin_respuesta");
        assertThat(det.path("hallazgos").get(0).path("planAuditor").path("que").asText()).isEqualTo("Implementar el control diario del soporte");

        assertThat(q.post("/api/opl-publico/respuestas", respuesta(cl.get(1), "objeta", "corto")).estado()).as("objeción sin justificación").isEqualTo(422);
        assertThat(q.post("/api/opl-publico/respuestas", respuesta(cl.get(0), "acepta", null)).estado()).isEqualTo(200);
        Respuesta obj = q.post("/api/opl-publico/respuestas", respuesta(cl.get(1), "objeta", "El soporte sí existe: se adjunta el registro firmado."));
        assertThat(obj.estado()).as(obj.texto()).isEqualTo(200);
        String correoLider = e.liderUsuario() + "@example.com";
        assertThat(ultimoCorreoA(correoLider).asunto()).startsWith("Objeción del OPL");

        byte[] png = firmaPng();
        assertThat(q.multipart("/api/opl-publico/soportes", Map.of("clave", cl.get(0)), Map.of("registro.png", png)).estado())
                .as("solo los objetados llevan soportes").isEqualTo(422);
        Respuesta sop = q.multipart("/api/opl-publico/soportes", Map.of("clave", cl.get(1)), Map.of("registro.png", png));
        assertThat(sop.estado()).as(sop.texto()).isEqualTo(201);
        long soporteId = jdbc.sql("SELECT id FROM opl_soportes WHERE visita_id = :v").param("v", e.id()).query(Long.class).single();
        assertThat(q.get("/api/opl-publico/soportes/" + soporteId).estado()).isEqualTo(200);
        assertThat(q.get("/api/opl-publico/soportes/" + (soporteId + 100000)).estado()).isEqualTo(404);
        assertThat(new String(q.get("/api/opl-publico/acta").cuerpo(), 0, 5)).isEqualTo("%PDF-");

        // Decisión del auditor líder: solo él, una vez, con motivo
        String base = "/api/visitas/" + e.id() + "/opl";
        assertThat(e.lider().get(base).json().path("hallazgos").get(1).path("estado").asText()).isEqualTo("objetado");
        assertThat(e.regente().post(base + "/resolucion", Map.of("clave", cl.get(1), "resolucion", "ratifica", "motivo", "El registro no cubre el periodo."))
                .estado()).isEqualTo(403);
        assertThat(e.lider().post(base + "/resolucion", Map.of("clave", cl.get(0), "resolucion", "ratifica", "motivo", "No aplica a un aceptado."))
                .estado()).as("un aceptado no se resuelve").isEqualTo(422);
        assertThat(e.lider().post(base + "/resolucion", Map.of("clave", cl.get(1), "resolucion", "ratifica", "motivo", "corto")).estado()).isEqualTo(422);
        Respuesta rat = e.lider().post(base + "/resolucion", Map.of("clave", cl.get(1), "resolucion", "ratifica", "motivo", "El registro no cubre el periodo."));
        assertThat(rat.estado()).as(rat.texto()).isEqualTo(200);
        assertThat(ultimoCorreoA(OPL).asunto()).startsWith("Hallazgo ratificado");
        assertThat(e.lider().post(base + "/resolucion", Map.of("clave", cl.get(1), "resolucion", "levanta", "motivo", "Cambio de opinión del líder."))
                .estado()).as("se decide una sola vez").isEqualTo(422);

        // Quién y cómo, y firma del plan
        Respuesta sinQuien = q.post("/api/opl-publico/firma", firma());
        assertThat(sinQuien.mensaje()).contains("Falta quién y cómo");
        Respuesta plan = q.put("/api/opl-publico/plan", Map.of("filas", List.of(
                Map.of("clave", cl.get(0), "quien", "Regente del punto", "como", "Formato diario revisado por calidad"),
                Map.of("clave", cl.get(1), "quien", "Jefe de bodega", "como", "Registro de temperatura con alarma"))));
        assertThat(plan.estado()).as(plan.texto()).isEqualTo(200);
        assertThat(plan.json().path("faltan").size()).isZero();
        Map<String, Object> sinDocumento = new java.util.HashMap<>(firma());
        sinDocumento.put("documento", "");
        assertThat(q.post("/api/opl-publico/firma", sinDocumento).estado()).isEqualTo(422);
        Respuesta firmo = q.post("/api/opl-publico/firma", firma());
        assertThat(firmo.estado()).as(firmo.texto()).isEqualTo(200);
        assertThat(q.post("/api/opl-publico/firma", firma()).estado()).isEqualTo(409);
        assertThat(ultimoCorreoA(correoLider).asunto()).startsWith("Plan de mejoramiento firmado");
        assertThat(jdbc.sql("SELECT estado FROM opl_planes WHERE visita_id = :v").param("v", e.id()).query(String.class).single()).isEqualTo("firmado");
        assertThat(String.join("\n", bitacoraTipos())).contains("opl_acceso", "opl_respuesta", "opl_soporte", "opl_firma");
    }

    private List<String> bitacoraTipos() {
        return jdbc.sql("SELECT tipo FROM log_transacciones WHERE modulo = 'opl' ORDER BY id").query(String.class).list();
    }

    @Test
    @DisplayName("levantar el último hallazgo cierra el plan y avisa solo al OPL")
    void levantarTodoCierraElPlan() throws Exception {
        Escenario e = escenario();
        Navegador q = qrVerificado(cerrarConHallazgos(e, 1));
        String clave = claves(q.get("/api/opl-publico/detalle").json()).get(0);
        assertThat(q.post("/api/opl-publico/respuestas", respuesta(clave, "objeta", "El ítem no aplica: el servicio no está habilitado.")).estado())
                .isEqualTo(200);
        Respuesta lev = e.lider().post("/api/visitas/" + e.id() + "/opl/resolucion",
                Map.of("clave", clave, "resolucion", "levanta", "motivo", "Se verificó que el servicio no está habilitado."));
        assertThat(lev.estado()).as(lev.texto()).isEqualTo(200);
        assertThat(lev.mensaje()).contains("plan quedó cerrado");
        assertThat(jdbc.sql("SELECT estado FROM opl_planes WHERE visita_id = :v").param("v", e.id()).query(String.class).single()).isEqualTo("cerrado");
        assertThat(ultimoCorreoA(OPL).asunto()).startsWith("Plan de mejoramiento cerrado");
        assertThat(ultimoCorreoA(OPL).para()).doesNotContain("contacto.punto@example.com");
        Respuesta tarde = q.post("/api/opl-publico/firma", firma());
        assertThat(tarde.estado()).as("el plan cerrado no admite firma: antes respondía 200 sin guardar nada").isEqualTo(409);
        assertThat(jdbc.sql("SELECT count(*) FROM opl_planes WHERE visita_id = :v AND firmado IS NOT NULL").param("v", e.id()).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("usuario OPL: el nacional y el de la regional ven la visita; el de otra regional recibe 404; un auditor, 403")
    void alcanceDelUsuarioOpl() throws Exception {
        Escenario e = escenario();
        cerrarConHallazgos(e, 1);
        long centro = jdbc.sql("INSERT INTO par_regionales (nombre) VALUES (:n) RETURNING id").param("n", unico("Centro ")).query(Long.class).single();
        long otra = jdbc.sql("INSERT INTO par_regionales (nombre) VALUES (:n) RETURNING id").param("n", unico("Norte ")).query(Long.class).single();
        jdbc.sql("UPDATE puntos SET regional_id = :r WHERE id = (SELECT punto_id FROM visitas WHERE id = :v)").param("r", centro).param("v", e.id()).update();
        String nac = crearUsuario("oplnac", Perfil.OPL_NACIONAL);
        String reg = crearUsuario("oplreg", Perfil.OPL_REGIONAL);
        String ajeno = crearUsuario("oplajeno", Perfil.OPL_REGIONAL);
        jdbc.sql("UPDATE usuarios SET alcance_regional = :r WHERE id = :id").param("r", centro).param("id", idDe(reg)).update();
        jdbc.sql("UPDATE usuarios SET alcance_regional = :r WHERE id = :id").param("r", otra).param("id", idDe(ajeno)).update();
        String ruta = "/api/opl/visitas/" + e.id();
        Navegador n = entrar(nac);
        assertThat(n.get("/api/opl/visitas").texto()).contains(e.numero());
        assertThat(n.get(ruta).estado()).isEqualTo(200);
        Navegador r = entrar(reg);
        assertThat(r.get(ruta).estado()).isEqualTo(200);
        Respuesta acepta = r.post(ruta + "/respuestas", respuesta(claves(r.get(ruta).json()).get(0), "acepta", null));
        assertThat(acepta.estado()).as(acepta.texto()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT canal FROM opl_respuestas WHERE visita_id = :v").param("v", e.id()).query(String.class).single()).isEqualTo("usuario");
        Navegador a = entrar(ajeno);
        assertThat(a.get(ruta).estado()).isEqualTo(404);
        assertThat(a.get("/api/opl/visitas").texto()).doesNotContain(e.numero());
        assertThat(e.regente().get("/api/opl/visitas").estado()).isEqualTo(403);
    }

    @Test
    @DisplayName("tarea diaria: aceptación automática, plan vencido y reporte a la DIGSA con consecutivo, una sola vez; el enlace vencido responde 410")
    void tareaDiaria() throws Exception {
        jdbc.sql("UPDATE par_parametros SET valor = '' WHERE clave = 'correo_digsa'").update();
        try {
            Escenario e = escenario();
            String token = cerrarConHallazgos(e, 1);
            reloj.adelantar(Duration.ofDays(9)); // más de 5 días hábiles
            Navegador admin = entrar(crearUsuario("admin", Perfil.ADMINISTRADOR));
            // Sin correo de la DIGSA no queda «reportado» aunque el líder sí tenga correo: sigue vencido y se reintenta
            Respuesta sinDigsa = admin.post("/api/opl/tarea-diaria", null);
            assertThat(sinDigsa.estado()).as(sinDigsa.texto()).isEqualTo(200);
            assertThat(jdbc.sql("SELECT estado FROM opl_planes WHERE visita_id = :v").param("v", e.id()).query(String.class).single()).isEqualTo("vencido");
            assertThat(jdbc.sql("SELECT d.estado FROM reportes_digsa d WHERE d.visita_id = :v").param("v", e.id()).query(String.class).single()).isEqualTo("fallido");
            jdbc.sql("UPDATE par_parametros SET valor = 'digsa.reportes@example.com' WHERE clave = 'correo_digsa'").update();
            Respuesta r = admin.post("/api/opl/tarea-diaria", null);
            assertThat(r.estado()).as(r.texto()).isEqualTo(200);
            assertThat(r.json().path("reportados").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(jdbc.sql("SELECT automatica FROM opl_respuestas WHERE visita_id = :v").param("v", e.id()).query(Boolean.class).single()).isTrue();
            Map<String, Object> plan = jdbc.sql("SELECT p.estado, d.consecutivo, d.estado AS envio FROM opl_planes p JOIN reportes_digsa d ON d.id = p.reporte_id WHERE p.visita_id = :v")
                    .param("v", e.id()).query().singleRow();
            assertThat(plan.get("estado")).isEqualTo("reportado_digsa");
            assertThat((String) plan.get("consecutivo")).matches("^RD-[0-9]{4}-[0-9]{4,}$");
            assertThat(plan.get("envio")).isEqualTo("enviado");
            Correo.Mensaje m = ultimoCorreoA("digsa.reportes@example.com");
            assertThat(m.asunto()).contains((String) plan.get("consecutivo"), e.numero());
            assertThat(m.para()).contains(e.liderUsuario() + "@example.com");
            assertThat(m.adjuntos()).hasSize(1);
            assertThat(ultimoCorreoA(e.liderUsuario() + "@example.com").asunto()).isNotNull();

            Respuesta otra = admin.post("/api/opl/tarea-diaria", null);
            assertThat(otra.estado()).isEqualTo(200);
            assertThat(jdbc.sql("SELECT count(*) FROM reportes_digsa WHERE visita_id = :v").param("v", e.id()).query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT count(*) FROM opl_avisos WHERE visita_id = :v AND tipo = 'aceptacion_automatica'").param("v", e.id()).query(Long.class)
                    .single()).isEqualTo(1);
            assertThat(navegador().get("/api/opl-publico/enlace?t=" + token).estado()).isEqualTo(410);
            assertThat(entrar(e.liderUsuario()).post("/api/opl/tarea-diaria", null).estado()).as("solo el administrador").isEqualTo(403);
        } finally {
            jdbc.sql("UPDATE par_parametros SET valor = '' WHERE clave = 'correo_digsa'").update();
        }
    }

    @Test
    @DisplayName("reabrir la visita cierra el enlace (410) y la saca de la lista del OPL")
    void reabiertaCierraElEnlace() throws Exception {
        Escenario e = escenario();
        String token = cerrarConHallazgos(e, 1);
        assertThat(e.coord().post("/api/visitas/" + e.id() + "/reabrir", Map.of("motivo", "soporte", "descripcion", "Falta un soporte del ítem")).estado())
                .isEqualTo(200);
        Respuesta r = navegador().get("/api/opl-publico/enlace?t=" + token);
        assertThat(r.estado()).isEqualTo(410);
        assertThat(r.mensaje()).contains("reabrió");
        Navegador n = entrar(crearUsuario("oplnac", Perfil.OPL_NACIONAL));
        assertThat(n.get("/api/opl/visitas").texto()).doesNotContain(e.numero());
    }
}
