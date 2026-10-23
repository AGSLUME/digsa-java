package co.com.agsamericas.digsa.ejecucion;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Seguimiento al plan de mejoramiento (docs/especificaciones/seguimiento): programación automática al cerrar, qué se verifica, estado
 * inicial con quién y cómo del OPL, prórroga con doble aprobación, máximo con escalamiento y reporte, cancelación al levantar todo,
 * soportes anteriores, «Prestadores con hallazgos» y recordatorio.
 */
class SeguimientoApiTest extends EscenariosVisita {

    private LocalDate hoy() {
        return LocalDate.now(reloj.withZone(Fechas.BOGOTA));
    }

    private Map<String, Object> seguimientoDe(long origen) {
        return jdbc.sql("SELECT id, numero, tipo, fecha, estado, seguimiento_n, punto_id, auditor_id, alcance FROM visitas WHERE origen_id = :o AND estado <> 'cancelada'")
                .param("o", origen).query().listOfRows().stream().findFirst().orElse(null);
    }

    private String claveNc(Escenario e) {
        return "it:" + items(e.lider().get("/api/visitas/" + e.id() + "/formulario").json(), "C00").get(0).path("codigo").asText();
    }

    /** Califica el hallazgo del seguimiento, guarda el plan y completa lo que pide el acta. */
    private void completarSeguimiento(long sid, Navegador lider, Navegador otro, String clave, String calificacion, String estadoPlan, LocalDate fin, String prorroga)
            throws Exception {
        String base = "/api/visitas/" + sid;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("item", clave.substring(3));
        r.put("calificacion", calificacion);
        r.put("hallazgo", "NC".equals(calificacion) ? "Sigue sin el soporte" : null);
        // El seguimiento se reparte de nuevo (decisión 30): califica quien tenga la parte del hallazgo
        Respuesta g = lider.put(base + "/respuestas", Map.of("respuestas", List.of(r)));
        if (g.estado() == 422 && otro != null) {
            g = otro.put(base + "/respuestas", Map.of("respuestas", List.of(r)));
        }
        assertThat(g.estado()).as(g.texto()).isEqualTo(200);
        Map<String, Object> ej = new HashMap<>();
        ej.put("inicioReal", hace(60));
        ej.put("finReal", hace(5));
        ej.put("objeto", "Seguimiento al plan");
        assertThat(lider.put(base + "/ejecucion", ej).estado()).isEqualTo(200);
        assertThat(lider.put(base + "/personal", Map.of("personas", List.of(Map.of("entidad", "ESM", "nombre", "Regente del punto")))).estado()).isEqualTo(200);
        String png = base64(firmaPng());
        assertThat(lider.post(base + "/firmas", Map.of("rol", "auditor", "nombre", "Auditor líder", "imagen", png)).estado()).isEqualTo(200);
        assertThat(lider.post(base + "/firmas", Map.of("rol", "esm", "nombre", "Regente del punto", "imagen", png)).estado()).isEqualTo(200);
        if ("NC".equals(calificacion)) {
            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("clave", clave);
            fila.put("que", "Implementar el control diario del soporte");
            fila.put("fin", fin.toString());
            fila.put("estado", estadoPlan);
            fila.put("prorroga", prorroga);
            Respuesta p = lider.put(base + "/plan", Map.of("fecha", hoy().toString(), "filas", List.of(fila)));
            assertThat(p.estado()).as(p.texto()).isEqualTo(200);
            assertThat(lider.post(base + "/firmas", Map.of("rol", "w5_auditor", "nombre", "Auditor líder", "imagen", png)).estado()).isEqualTo(200);
            assertThat(lider.post(base + "/firmas", Map.of("rol", "w5_auditado", "nombre", "Regente del punto", "imagen", png)).estado()).isEqualTo(200);
        }
    }

    private long iniciar(Escenario e, long sid) {
        Respuesta r = e.lider().post("/api/mis-visitas/" + sid + "/iniciar", null);
        assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        return sid;
    }

    @Test
    @DisplayName("al cerrar con una acción abierta se programa solo el seguimiento (tipo plan, mismo punto y líder, en la fecha fin); reabrir y cerrar no lo duplica")
    void programacionAutomatica() throws Exception {
        Escenario e = escenario();
        cerrarConHallazgos(e, 1);
        Map<String, Object> s = seguimientoDe(e.id());
        assertThat(s).as("se creó el seguimiento").isNotNull();
        assertThat(s.get("tipo")).isEqualTo("plan");
        assertThat(((Number) s.get("seguimiento_n")).intValue()).isEqualTo(1);
        assertThat(s.get("fecha").toString()).isEqualTo(hoy().plusDays(30).toString());
        assertThat((String) s.get("alcance")).contains("Primera visita de seguimiento al plan de mejoramiento de la visita " + e.numero(), "1 acción");
        assertThat(((Number) s.get("auditor_id")).longValue()).isEqualTo(idDe(e.liderUsuario()));
        Correo.Mensaje aviso = ultimoCorreoA(e.liderUsuario() + "@example.com");
        assertThat(aviso.asunto()).startsWith("Visita de seguimiento " + s.get("numero"));
        assertThat(aviso.html()).contains("Acciones por verificar");
        Correo.Mensaje acta = correos().reversed().stream().filter(x -> x.asunto().startsWith("Acta de visita " + e.numero())).findFirst().orElseThrow();
        assertThat(acta.adjuntos()).hasSize(1);

        // Decisión D6: la parte del hallazgo vuelve al auditor que la calificó en la visita de origen
        long sid0 = ((Number) s.get("id")).longValue();
        assertThat(jdbc.sql("SELECT usuario_id FROM visitas_reparto WHERE visita_id = :s AND parte = 'C00'").param("s", sid0).query(Long.class).single())
                .isEqualTo(idDe(e.liderUsuario()));
        assertThat(e.coord().post("/api/visitas/" + e.id() + "/reabrir", Map.of("motivo", "soporte", "descripcion", "Falta un soporte del ítem")).estado()).isEqualTo(200);
        firmarRonda(e, true);
        Respuesta otra = e.lider().post("/api/visitas/" + e.id() + "/acta", null);
        assertThat(otra.json().path("ok").asBoolean()).as(otra.texto()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM visitas WHERE origen_id = :o AND estado <> 'cancelada'").param("o", e.id()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("el seguimiento recalifica solo el hallazgo, parte del plan con quién y cómo del OPL, muestra el soporte anterior y exige justificar la prórroga, que aprueban coordinador y DIGSA")
    void verificarYProrroga() throws Exception {
        Escenario e = escenario();
        String clave = claveNc(e);
        // Soporte del hallazgo en la visita de origen
        completar(e);
        Respuesta sub = e.lider().multipart("/api/visitas/" + e.id() + "/archivos", Map.of("clave", clave), Map.of("soporte.png", firmaPng()));
        assertThat(sub.estado()).as(sub.texto()).isEqualTo(201);
        long archivo = sub.json().path("archivos").get(0).path("id").asLong();
        cerrarConHallazgos(e, 1);
        // El OPL remitió quién y cómo
        jdbc.sql("INSERT INTO opl_respuestas (visita_id, clave, decision, canal, quien, como, nombre, cargo) VALUES (:v, :c, 'acepta', 'sistema', 'Director técnico', 'Formato diario', 'OPL', 'Calidad')")
                .param("v", e.id()).param("c", clave).update();
        long sid = ((Number) seguimientoDe(e.id()).get("id")).longValue();
        iniciar(e, sid);
        String base = "/api/visitas/" + sid;
        JsonNode f = e.lider().get(base + "/formulario").json();
        assertThat(f.path("seguimiento").path("ordinal").asText()).isEqualTo("Primera");
        assertThat(f.path("seguimiento").path("origenNumero").asText()).isEqualTo(e.numero());
        List<String> claves = new ArrayList<>();
        f.path("componentes").forEach(c -> c.path("items").forEach(i -> claves.add(i.path("clave").asText())));
        assertThat(claves).as("solo se recalifica el hallazgo").containsExactly(clave);
        JsonNode item = f.path("componentes").get(0).path("items").get(0);
        assertThat(item.path("calificacion").isNull()).isTrue();
        assertThat(item.path("anterior").path("hallazgo").asText()).startsWith("No se encontró el soporte");
        assertThat(item.path("archivosAnteriores").get(0).path("id").asLong()).isEqualTo(archivo);
        assertThat(e.lider().get(base + "/anteriores/" + archivo).estado()).isEqualTo(200);
        assertThat(e.lider().get(base + "/anteriores/" + (archivo + 99999)).estado()).isEqualTo(404);
        Navegador ajeno = entrar(auditor("ajeno", "regente"));
        assertThat(ajeno.get(base + "/anteriores/" + archivo).estado()).isEqualTo(404);
        JsonNode fila = f.path("plan").path("hallazgos").get(0);
        assertThat(fila.path("fila").path("quien").asText()).isEqualTo("Director técnico");
        assertThat(fila.path("finAnterior").asText()).isEqualTo(hoy().plusDays(30).toString());
        assertThat(e.lider().put("/api/visitas/" + sid + "/respuestas", Map.of("respuestas", calificar(items(e.lider().get("/api/visitas/" + e.id() + "/formulario").json(), "C08"), "C"))).estado())
                .as("lo que no es hallazgo no se califica en el seguimiento").isEqualTo(422);

        // Sigue en NC y la fecha fin pasa de +30 a +45: es prórroga
        completarSeguimiento(sid, e.lider(), e.regente(), clave, "NC", "En curso", hoy().plusDays(45), null);
        Respuesta sinJustificar = e.lider().post(base + "/acta", null);
        assertThat(sinJustificar.mensaje()).contains("Justifique la prórroga");
        completarSeguimiento(sid, e.lider(), e.regente(), clave, "NC", "En curso", hoy().plusDays(45), "El proveedor del formato entrega en diciembre");
        Respuesta envio = e.lider().post(base + "/acta", null);
        assertThat(envio.json().path("ok").asBoolean()).as(envio.texto()).isTrue();
        Correo.Mensaje acta = correos().reversed().stream().filter(x -> x.asunto().startsWith("Acta de seguimiento a plan de mejoramiento")).findFirst().orElseThrow();
        assertThat(acta.asunto()).contains(jdbc.sql("SELECT numero FROM visitas WHERE id = :s").param("s", sid).query(String.class).single(),
                "Primera de la visita " + e.numero());
        assertThat(jdbc.sql("SELECT quien FROM visitas_plan WHERE visita_id = :s").param("s", sid).query(String.class).single())
                .as("el quién del OPL queda en el plan del seguimiento al enviar el acta").isEqualTo("Director técnico");
        long prorroga = jdbc.sql("SELECT id FROM prorrogas WHERE visita_id = :s").param("s", sid).query(Long.class).single();
        Map<String, Object> seg2 = seguimientoDe(sid);
        assertThat(((Number) seg2.get("seguimiento_n")).intValue()).as("segundo seguimiento").isEqualTo(2);
        assertThat(seg2.get("fecha").toString()).as("con la fecha solicitada").isEqualTo(hoy().plusDays(45).toString());

        // Doble aprobación: coordinador y DIGSA
        Navegador digsa = entrar(crearUsuario("digsa", Perfil.DIGSA));
        assertThat(e.regente().get("/api/prorrogas").estado()).isEqualTo(403);
        assertThat(e.coord().get("/api/prorrogas").texto()).contains("El proveedor del formato", "\"puedeDecidir\":true");
        Navegador admin = entrar(crearUsuario("admin", Perfil.ADMINISTRADOR));
        assertThat(admin.get("/api/prorrogas").texto()).as("el administrador consulta sin decidir").contains("\"puedeDecidir\":false");
        assertThat(admin.post("/api/prorrogas/" + prorroga + "/decision", Map.of("decision", "aprueba")).estado())
                .as("el administrador no decide a nombre de la coordinación").isEqualTo(403);
        Respuesta c1 = e.coord().post("/api/prorrogas/" + prorroga + "/decision", Map.of("decision", "aprueba"));
        assertThat(c1.mensaje()).as(c1.texto()).contains("falta la de la DIGSA");
        assertThat(e.coord().post("/api/prorrogas/" + prorroga + "/decision", Map.of("decision", "aprueba")).estado()).as("una vez por lado").isEqualTo(422);
        assertThat(jdbc.sql("SELECT estado FROM prorrogas WHERE id = :id").param("id", prorroga).query(String.class).single()).isEqualTo("solicitada");
        Respuesta d1 = digsa.post("/api/prorrogas/" + prorroga + "/decision", Map.of("decision", "aprueba"));
        assertThat(d1.mensaje()).as(d1.texto()).contains("aprobada por la coordinación y la DIGSA");
        assertThat(jdbc.sql("SELECT estado FROM prorrogas WHERE id = :id").param("id", prorroga).query(String.class).single()).isEqualTo("aprobada");
        assertThat(jdbc.sql("SELECT tipo FROM log_transacciones WHERE tipo LIKE 'prorroga_%' ORDER BY id").query(String.class).list())
                .contains("prorroga_aprobada_coordinador", "prorroga_aprobada_digsa");
    }

    @Test
    @DisplayName("al llegar al máximo de seguimientos con acciones abiertas no se programa otro: escalamiento y reporte de plan incumplido a la DIGSA")
    void maximoYEscalamiento() throws Exception {
        jdbc.sql("UPDATE par_parametros SET valor = '1' WHERE clave = 'max_seguimientos'").update();
        jdbc.sql("UPDATE par_parametros SET valor = 'digsa.seguimiento@example.com' WHERE clave = 'correo_digsa'").update();
        try {
            Escenario e = escenario();
            String clave = claveNc(e);
            cerrarConHallazgos(e, 1);
            long sid = iniciar(e, ((Number) seguimientoDe(e.id()).get("id")).longValue());
            completarSeguimiento(sid, e.lider(), e.regente(), clave, "NC", "En curso", hoy().plusDays(30), null);
            Respuesta envio = e.lider().post("/api/visitas/" + sid + "/acta", null);
            assertThat(envio.json().path("ok").asBoolean()).as(envio.texto()).isTrue();
            assertThat(envio.mensaje()).contains("máximo de seguimientos");
            assertThat(seguimientoDe(sid)).as("no se programa otro").isNull();
            Correo.Mensaje esc = correos().reversed().stream().filter(x -> x.asunto().startsWith("Plan de mejoramiento sin cerrar")).findFirst().orElseThrow();
            assertThat(esc.copia()).contains("digsa.seguimiento@example.com");
            Map<String, Object> rep = jdbc.sql("SELECT consecutivo, estado, motivo FROM reportes_digsa WHERE visita_id = :s").param("s", sid).query().singleRow();
            assertThat(rep.get("motivo")).isEqualTo("plan_incumplido");
            assertThat(rep.get("estado")).isEqualTo("enviado");
            assertThat(ultimoCorreoA("digsa.seguimiento@example.com").asunto()).contains((String) rep.get("consecutivo"), "incumplido");
            // El plan del acta recién enviada sigue recibiendo la respuesta del OPL; el reporte queda ligado a él (duda 22.9)
            assertThat(jdbc.sql("SELECT p.estado || ':' || (p.reporte_id IS NOT NULL) FROM opl_planes p JOIN opl_accesos a ON a.acta_id = p.acta_id WHERE a.visita_id = :s")
                    .param("s", sid).query(String.class).single()).isEqualTo("pendiente:true");
            assertThat(jdbc.sql("SELECT string_agg(evento, '|') FROM visitas_historial WHERE visita_id = :s").param("s", sid).query(String.class).single())
                    .contains("Máximo de seguimientos", "Escalamiento", "Reporte a la DIGSA");
        } finally {
            jdbc.sql("UPDATE par_parametros SET valor = '3' WHERE clave = 'max_seguimientos'").update();
            jdbc.sql("UPDATE par_parametros SET valor = '' WHERE clave = 'correo_digsa'").update();
        }
    }

    @Test
    @DisplayName("el seguimiento que deja todo completado no programa otro y cierra el plan del OPL de la visita de origen")
    void seguimientoSinAbiertas() throws Exception {
        Escenario e = escenario();
        String clave = claveNc(e);
        cerrarConHallazgos(e, 1);
        long sid = iniciar(e, ((Number) seguimientoDe(e.id()).get("id")).longValue());
        completarSeguimiento(sid, e.lider(), e.regente(), clave, "C", null, null, null);
        Respuesta envio = e.lider().post("/api/visitas/" + sid + "/acta", null);
        assertThat(envio.json().path("ok").asBoolean()).as(envio.texto()).isTrue();
        assertThat(seguimientoDe(sid)).isNull();
        assertThat(jdbc.sql("SELECT estado FROM opl_planes p JOIN opl_accesos a ON a.acta_id = p.acta_id WHERE a.visita_id = :v").param("v", e.id())
                .query(String.class).single()).isEqualTo("cerrado");
        assertThat(ultimoCorreoA(OPL).asunto()).startsWith("Plan de mejoramiento cerrado");
    }

    @Test
    @DisplayName("al levantar el único hallazgo el seguimiento pendiente se cancela")
    void levantarCancelaElSeguimiento() throws Exception {
        Escenario e = escenario();
        String clave = claveNc(e);
        cerrarConHallazgos(e, 1);
        long sid = ((Number) seguimientoDe(e.id()).get("id")).longValue();
        jdbc.sql("INSERT INTO opl_respuestas (visita_id, clave, decision, canal, justificacion, nombre, cargo) VALUES (:v, :c, 'objeta', 'sistema', 'El soporte existe y se adjunta', 'OPL', 'Calidad')")
                .param("v", e.id()).param("c", clave).update();
        Respuesta lev = e.lider().post("/api/visitas/" + e.id() + "/opl/resolucion", Map.of("clave", clave, "resolucion", "levanta", "motivo", "Se verificó el soporte."));
        assertThat(lev.estado()).as(lev.texto()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT estado FROM visitas WHERE id = :s").param("s", sid).query(String.class).single()).isEqualTo("cancelada");
        assertThat(jdbc.sql("SELECT string_agg(detalle, '|') FROM visitas_historial WHERE visita_id = :s").param("s", sid).query(String.class).single())
                .contains("fueron levantados por el auditor líder");
    }

    @Test
    @DisplayName("Prestadores con hallazgos: el coordinador ve la visita y su seguimiento; el ESM no ve la objeción; el OPL recibe 403")
    void prestadoresConHallazgos() throws Exception {
        Escenario e = escenario();
        String clave = claveNc(e);
        cerrarConHallazgos(e, 1);
        jdbc.sql("INSERT INTO opl_respuestas (visita_id, clave, decision, canal, justificacion, nombre, cargo) VALUES (:v, :c, 'objeta', 'sistema', 'Justificación privada del OPL', 'OPL', 'Calidad')")
                .param("v", e.id()).param("c", clave).update();
        Respuesta coord = e.coord().get("/api/hallazgos");
        assertThat(coord.estado()).as(coord.texto()).isEqualTo(200);
        assertThat(coord.texto()).contains(e.numero(), "\"situacion\":\"programada\"", "Justificación privada del OPL", "objetado");
        long punto = jdbc.sql("SELECT punto_id FROM visitas WHERE id = :v").param("v", e.id()).query(Long.class).single();
        String esm = crearUsuario("esm", Perfil.ESM);
        jdbc.sql("UPDATE usuarios SET alcance_punto = :p WHERE id = :id").param("p", punto).param("id", idDe(esm)).update();
        Respuesta r = entrar(esm).get("/api/hallazgos");
        assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        assertThat(r.texto()).contains(e.numero(), "vigente").doesNotContain("Justificación privada del OPL").doesNotContain("objetado");
        assertThat(entrar(crearUsuario("oplnac", Perfil.OPL_NACIONAL)).get("/api/hallazgos").estado()).isEqualTo(403);
    }

    @Test
    @DisplayName("decisión D9: en cada seguimiento, la acción vencida que sigue en No cumple se reporta a la DIGSA como plan incumplido")
    void accionVencidaEsPlanIncumplido() throws Exception {
        jdbc.sql("UPDATE par_parametros SET valor = 'digsa.vencida@example.com' WHERE clave = 'correo_digsa'").update();
        try {
            Escenario e = escenario();
            String clave = claveNc(e);
            cerrarConHallazgos(e, 1);
            long sid = iniciar(e, ((Number) seguimientoDe(e.id()).get("id")).longValue());
            // La fecha fin del plan de origen ya pasó y el seguimiento la deja igual (no es prórroga). Dos días atrás: el inicio real
            // del seguimiento es «hace 60 minutos» y, entre las 00:00 y la 01:00, cae en el día anterior (la acción vence ese día)
            jdbc.sql("UPDATE visitas_plan SET fin = :f, inicio = NULL WHERE visita_id IN (:o, :s)").param("f", hoy().minusDays(2)).param("o", e.id()).param("s", sid).update();
            completarSeguimiento(sid, e.lider(), e.regente(), clave, "NC", "Retrasado", hoy().minusDays(2), null);
            Respuesta envio = e.lider().post("/api/visitas/" + sid + "/acta", null);
            assertThat(envio.json().path("ok").asBoolean()).as(envio.texto()).isTrue();
            assertThat(envio.mensaje()).contains("plan incumplido");
            Map<String, Object> rep = jdbc.sql("SELECT motivo, estado FROM reportes_digsa WHERE visita_id = :s").param("s", sid).query().singleRow();
            assertThat(rep.get("motivo")).isEqualTo("plan_incumplido");
            assertThat(rep.get("estado")).isEqualTo("enviado");
            assertThat(seguimientoDe(sid)).as("igual se programa el siguiente seguimiento").isNotNull();
        } finally {
            jdbc.sql("UPDATE par_parametros SET valor = '' WHERE clave = 'correo_digsa'").update();
        }
    }

    @Test
    @DisplayName("decisión D5: la habilitación en trámite sigue siendo hallazgo y el acta dice qué queda pendiente aportar")
    void habilitacionEnTramite() throws Exception {
        Escenario e = escenario();
        String base = "/api/visitas/" + e.id();
        completar(e);
        JsonNode f = e.lider().get(base + "/formulario").json();
        String codigo = items(f, "C00").get(0).path("codigo").asText();
        String c08 = items(f, "C08").get(0).path("codigo").asText();
        Map<String, Object> mal = new LinkedHashMap<>(Map.of("item", codigo, "calificacion", "C", "enTramite", true));
        assertThat(e.lider().put(base + "/respuestas", Map.of("respuestas", List.of(mal))).mensaje()).contains("habilitación en trámite");
        assertThat(e.regente().put(base + "/respuestas", Map.of("respuestas", List.of(Map.of("item", c08, "calificacion", "NC", "hallazgo", "Sin registro", "enTramite", true))))
                .mensaje()).as("solo en habilitación (C00)").contains("habilitación en trámite");
        Map<String, Object> ok = new LinkedHashMap<>(Map.of("item", codigo, "calificacion", "NC", "hallazgo", "Sin distintivo de habilitación", "enTramite", true));
        assertThat(e.lider().put(base + "/respuestas", Map.of("respuestas", List.of(ok))).estado()).isEqualTo(200);
        JsonNode item = items(e.lider().get(base + "/formulario").json(), "C00").get(0);
        assertThat(item.path("enTramite").asBoolean()).isTrue();
        assertThat(item.path("admiteTramite").asBoolean()).isTrue();
        planYFirmas(e, List.of("it:" + codigo));
        Respuesta envio = e.lider().post(base + "/acta", null);
        assertThat(envio.json().path("ok").asBoolean()).as(envio.texto()).isTrue();
        assertThat(jdbc.sql("SELECT en_tramite FROM visitas_hallazgos WHERE visita_id = :v").param("v", e.id()).query(Boolean.class).single())
                .as("sigue siendo hallazgo, marcado en trámite").isTrue();
    }

    @Test
    @DisplayName("recordatorio al auditor dentro de los 5 días hábiles previos, una sola vez")
    void recordatorio() throws Exception {
        Escenario e = escenario();
        cerrarConHallazgos(e, 1);
        long sid = ((Number) seguimientoDe(e.id()).get("id")).longValue();
        jdbc.sql("UPDATE visitas SET fecha = :f WHERE id = :s").param("f", hoy().plusDays(2)).param("s", sid).update();
        Navegador admin = entrar(crearUsuario("admin", Perfil.ADMINISTRADOR));
        Respuesta r1 = admin.post("/api/seguimiento/recordatorios", null);
        assertThat(r1.estado()).as(r1.texto()).isEqualTo(200);
        assertThat(r1.json().path("enviados").asInt()).isGreaterThanOrEqualTo(1);
        esperarCorreoA(e.liderUsuario() + "@example.com", "Recordatorio: Primera visita de seguimiento");
        admin.post("/api/seguimiento/recordatorios", null);
        assertThat(jdbc.sql("SELECT count(*) FROM visitas_historial WHERE visita_id = :s AND evento = 'Recordatorio al auditor'").param("s", sid).query(Long.class).single())
                .isEqualTo(1);
        reloj.adelantar(Duration.ZERO);
    }
}
