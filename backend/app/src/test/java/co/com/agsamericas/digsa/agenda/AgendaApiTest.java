package co.com.agsamericas.digsa.agenda;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Agenda (Anexo 5, prueba 11): el coordinador crea la visita eligiendo los componentes y asigna cada uno a un auditor; cada auditor ve
 * solo lo suyo; estados con cambio condicionado; notificación previa a la DISAN, al OPL y al punto.
 */
class AgendaApiTest extends PruebaIntegracion {

    private static final String OPL = "opl.notificaciones@example.com";
    private static final String DISAN = "disan.ejc@example.com";

    @BeforeEach
    void correosDeLaNotificacion() {
        if (jdbc.sql("SELECT count(*) FROM opl WHERE activo").query(Long.class).single() == 0) {
            jdbc.sql("INSERT INTO opl (nombre, correos) VALUES ('Operador de prueba', ARRAY[:c])").param("c", OPL).update();
        } else {
            jdbc.sql("UPDATE opl SET correos = ARRAY[:c] WHERE activo").param("c", OPL).update();
        }
        jdbc.sql("UPDATE par_fuerzas SET correos = ARRAY[:c] WHERE codigo = 'EJC'").param("c", DISAN).update();
    }

    private Navegador coordinador() {
        Navegador n = navegador();
        assertThat(n.ingresar(crearUsuario("coord", Perfil.COORDINADOR), CLAVE).estado()).isEqualTo(200);
        return n;
    }

    private String auditor(String prefijo, String rol) {
        String u = crearUsuario(prefijo, Perfil.AUDITOR);
        jdbc.sql("INSERT INTO auditores (usuario_id, rol) VALUES (:id, :r)").param("id", idDe(u)).param("r", rol).update();
        return u;
    }

    private long punto(String unidad, String fuerza, String modalidad, boolean exigeQf) {
        return jdbc.sql("""
                INSERT INTO puntos (nombre, unidad, fuerza, modalidad, municipio, correos, exige_qf, creado_por)
                VALUES (:n, :u, :f, :m, '05001', ARRAY['punto.contacto@example.com'], :qf, 'prueba') RETURNING id""")
                .param("n", unico("Punto de prueba ")).param("u", unidad).param("f", fuerza).param("m", modalidad).param("qf", exigeQf)
                .query(Long.class).single();
    }

    private String dentroDe(int dias) {
        return LocalDate.now(reloj.withZone(Fechas.BOGOTA)).plusDays(dias).toString();
    }

    private Map<String, Object> visita(long punto, String lider, List<String> otros, List<String> componentes) {
        Map<String, Object> m = new HashMap<>();
        m.put("tipo", "anual");
        m.put("puntoId", punto);
        m.put("fecha", dentroDe(25));
        m.put("hora", "08:30");
        m.put("liderId", idDe(lider));
        m.put("otros", otros.stream().map(this::idDe).toList());
        m.put("alcance", "Verificar almacenamiento, cadena de frío y dispensación.");
        m.put("componentes", componentes);
        return m;
    }

    /** Último correo al OPL de esta visita (no el de otra prueba). */
    private Correo.Mensaje correoDe(String numero) {
        return correos().reversed().stream().filter(m -> m.para().contains(OPL) && m.asunto().contains(numero)).findFirst()
                .orElseThrow(() -> new AssertionError("No hay correo de la visita " + numero));
    }

    private static String numeroDe(Navegador coord, long id) {
        return coord.get("/api/agenda/" + id).json().path("numero").asText();
    }

    private static List<String> partes(JsonNode detalle) {
        List<String> r = new ArrayList<>();
        detalle.path("reparto").forEach(p -> r.add(p.path("parte").asText()));
        return r;
    }

    @Test
    @DisplayName("prueba 11: crear la visita con componentes elegidos, asignar C13 al regente; cada auditor ve solo sus partes")
    void componentesYReparto() {
        String qf = auditor("qf", "quimico_farmaceutico");
        String regente = auditor("reg", "regente");
        long p = punto("ESM", "EJC", "Dispensación", false);
        Navegador coord = coordinador();
        Map<String, Object> d = visita(p, qf, List.of(regente), List.of("C05", "C08", "C13"));
        d.put("reparto", Map.of("C13", idDe(regente)));
        d.put("notificar", true);
        Respuesta r = coord.post("/api/agenda", d);
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        long id = r.json().path("id").asLong();
        assertThat(r.json().path("numero").asText()).matches("VS-\\d{8}-\\d{4,}").contains(dentroDe(25).replace("-", ""));
        assertThat(r.json().path("notificacionOk").asBoolean()).as(r.texto()).isTrue();

        JsonNode det = coord.get("/api/agenda/" + id).json();
        assertThat(det.path("estado").asText()).isEqualTo("notificada");
        assertThat(det.path("componentes").toString()).contains("C05", "C08", "C13").doesNotContain("C09");
        assertThat(partes(det)).containsExactlyInAnyOrder("C05", "C08", "C13", "OBL");
        assertThat(det.path("historial").toString()).contains("Programada", "Notificada");

        Navegador nr = navegador();
        nr.ingresar(regente, CLAVE);
        JsonNode mias = nr.get("/api/mis-visitas").json();
        JsonNode esta = null;
        for (JsonNode x : mias) {
            if (x.path("id").asLong() == id) {
                esta = x;
            }
        }
        assertThat(esta).isNotNull();
        assertThat(esta.path("partes").toString()).contains("C13");
        JsonNode detReg = nr.get("/api/mis-visitas/" + id).json();
        assertThat(partes(detReg)).contains("C13").allMatch(x -> !x.isEmpty());
        detReg.path("reparto").forEach(x -> assertThat(x.path("auditorId").asLong()).isEqualTo(idDe(regente)));
        assertThat(detReg.has("historial")).as("el auditor no ve el historial ni las notificaciones").isFalse();

        Navegador otro = navegador();
        otro.ingresar(auditor("ajeno", "regente"), CLAVE);
        assertThat(otro.get("/api/mis-visitas/" + id).estado()).isEqualTo(404);
        assertThat(otro.get("/api/agenda/" + id).estado()).isEqualTo(403);
    }

    @Test
    @DisplayName("notificación previa: a la DISAN, al OPL y al punto, con el equipo, el documento y la nota de la Ley 1581")
    void contenidoNotificacion() {
        String qf = auditor("qf", "quimico_farmaceutico");
        long p = punto("ESM", "EJC", "Dispensación", false);
        Navegador coord = coordinador();
        Map<String, Object> d = visita(p, qf, List.of(), List.of("C08"));
        d.put("notificar", true);
        Respuesta r = coord.post("/api/agenda", d);
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        Correo.Mensaje m = correoDe(r.json().path("numero").asText());
        assertThat(m.para()).contains(OPL, DISAN, "punto.contacto@example.com");
        assertThat(m.asunto()).startsWith("Notificación de visita de seguimiento VS-");
        assertThat(m.html()).contains("Alcance específico", "Equipo auditor", "auditor líder", "Ley 1581", "Químico farmacéutico", "documento 1000");
    }

    @Test
    @DisplayName("sin correos del OPL: la visita queda programada, la respuesta dice que no se notificó y el historial lo registra")
    void notificacionPendiente() {
        String qf = auditor("qf", "quimico_farmaceutico");
        long p = punto("ESM", "EJC", "Dispensación", false);
        Navegador coord = coordinador();
        jdbc.sql("UPDATE opl SET correos = '{}' WHERE activo").update();
        Map<String, Object> d = visita(p, qf, List.of(), List.of("C08"));
        d.put("notificar", true);
        Respuesta r = coord.post("/api/agenda", d);
        assertThat(r.json().path("notificacionOk").asBoolean()).isFalse();
        assertThat(r.json().path("mensaje").asText()).contains("faltan los correos de OPL");
        JsonNode det = coord.get("/api/agenda/" + r.json().path("id").asLong()).json();
        assertThat(det.path("estado").asText()).isEqualTo("programada");
        assertThat(det.path("historial").toString()).contains("Notificación pendiente");
        assertThat(det.path("acciones").toString()).contains("editar", "notificar");
    }

    @Test
    @DisplayName("validaciones: componente de otra unidad, sin componentes, tipo de otra unidad, SIS a quien no es ingeniero, fecha pasada")
    void validaciones() {
        String qf = auditor("qf", "quimico_farmaceutico");
        String ing = auditor("ing", "ingeniero_sistemas");
        long esm = punto("ESM", "EJC", "Dispensación", false);
        Navegador coord = coordinador();
        assertThat(coord.post("/api/agenda", visita(esm, qf, List.of(), List.of("C03"))).mensaje()).contains("no aplican a la unidad ESM");
        assertThat(coord.post("/api/agenda", visita(esm, qf, List.of(), List.of())).mensaje()).contains("al menos un componente");
        Map<String, Object> central = visita(esm, qf, List.of(), List.of("C08"));
        central.put("tipo", "central");
        assertThat(coord.post("/api/agenda", central).mensaje()).contains("corresponde a otra unidad");
        Map<String, Object> sis = visita(esm, qf, List.of(ing), List.of("C01", "C08", "C19"));
        Respuesta conIng = coord.post("/api/agenda", sis);
        assertThat(conIng.estado()).as(conIng.texto()).isEqualTo(201);
        JsonNode det = coord.get("/api/agenda/" + conIng.json().path("id").asLong()).json();
        assertThat(partes(det)).as("con ingeniero en el equipo hay parte SIS").contains("SIS");
        Map<String, Object> mal = visita(esm, qf, List.of(ing), List.of("C01", "C08", "C19"));
        mal.put("reparto", Map.of("SIS", idDe(qf)));
        Respuesta sisMal = coord.post("/api/agenda", mal);
        assertThat(sisMal.estado()).isEqualTo(422);
        assertThat(sisMal.mensaje()).contains("ingeniero de sistemas");
        Map<String, Object> pasada = visita(esm, qf, List.of(), List.of("C08"));
        pasada.put("fecha", dentroDe(-1));
        assertThat(coord.post("/api/agenda", pasada).mensaje()).contains("anterior a hoy");
        Map<String, Object> sinLider = visita(esm, qf, List.of(), List.of("C08"));
        sinLider.put("liderId", idDe(crearUsuario("noaud", Perfil.COORDINADOR)));
        assertThat(coord.post("/api/agenda", sinLider).mensaje()).startsWith("Falta o no es válido: auditor líder");
    }

    @Test
    @DisplayName("estados: editar solo programada; reprogramar con motivo notifica la nueva fecha; mover partes solo antes de iniciar (8.3)")
    void estados() {
        String qf = auditor("qf", "quimico_farmaceutico");
        String regente = auditor("reg", "regente");
        long p = punto("ESM", "EJC", "Dispensación", false);
        Navegador coord = coordinador();
        Map<String, Object> d = visita(p, qf, List.of(regente), List.of("C05", "C08"));
        d.put("notificar", true);
        long id = coord.post("/api/agenda", d).json().path("id").asLong();
        assertThat(coord.put("/api/agenda/" + id, visita(p, qf, List.of(regente), List.of("C05"))).mensaje())
                .contains("Solo se edita una visita programada sin notificar");

        Respuesta mover = coord.post("/api/agenda/" + id + "/mover", Map.of("parte", "C05", "usuarioId", idDe(regente)));
        assertThat(mover.estado()).as(mover.texto()).isEqualTo(200);
        assertThat(coord.post("/api/agenda/" + id + "/mover", Map.of("parte", "C05", "usuarioId", idDe(regente))).mensaje()).isEqualTo("Sin cambios.");

        assertThat(coord.post("/api/agenda/" + id + "/reprogramar", Map.of("fecha", dentroDe(30))).mensaje()).contains("motivo");
        Respuesta rep = coord.post("/api/agenda/" + id + "/reprogramar", Map.of("fecha", dentroDe(30), "hora", "09:00", "motivo", "Solicitud del punto"));
        assertThat(rep.estado()).as(rep.texto()).isEqualTo(200);
        assertThat(rep.json().path("notificacionOk").asBoolean()).isTrue();
        assertThat(correoDe(numeroDe(coord, id)).asunto()).endsWith(" · nueva fecha");
        JsonNode det = coord.get("/api/agenda/" + id).json();
        assertThat(det.path("estado").asText()).isEqualTo("notificada");
        assertThat(det.path("historial").toString()).contains("Reprogramada", "Notificada la nueva fecha", "Reparto cambiado");
        assertThat(jdbc.sql("SELECT count(*) FROM visitas_notificaciones WHERE visita_id = :v AND estado = 'enviada'").param("v", id)
                .query(Long.class).single()).isEqualTo(2);

        Navegador nr = navegador();
        nr.ingresar(regente, CLAVE);
        Respuesta ini = nr.post("/api/mis-visitas/" + id + "/iniciar", null);
        assertThat(ini.estado()).as(ini.texto()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT checklist_version FROM visitas WHERE id = :id").param("id", id).query(String.class).single()).isEqualTo("v6");
        assertThat(nr.post("/api/mis-visitas/" + id + "/iniciar", null).json().path("mensaje").asText()).contains("ya estaba en ejecución");
        assertThat(coord.post("/api/agenda/" + id + "/mover", Map.of("parte", "C05", "usuarioId", idDe(qf))).mensaje())
                .contains("solo se mueven antes de iniciar");
        assertThat(coord.get("/api/agenda/seguimiento").json().path("visitas").toString()).contains(det.path("numero").asText());
        // UI-20: además de la hora («iniciada»), la fecha y hora de inicio en Bogotá («iniciadaEl»)
        com.fasterxml.jackson.databind.JsonNode enSeguimiento = null;
        for (com.fasterxml.jackson.databind.JsonNode v : coord.get("/api/agenda/seguimiento").json().path("visitas")) {
            if (v.path("numero").asText().equals(det.path("numero").asText())) {
                enSeguimiento = v;
            }
        }
        assertThat(enSeguimiento).isNotNull();
        // toString() omite los segundos cuando son 0: «14:05» o «14:05:12»
        assertThat(enSeguimiento.path("iniciada").asText()).matches("\\d{2}:\\d{2}(:\\d{2})?");
        assertThat(enSeguimiento.path("iniciadaEl").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?")
                .endsWith("T" + enSeguimiento.path("iniciada").asText());

        assertThat(coord.post("/api/agenda/" + id + "/cancelar", Map.of("motivo", "")).estado()).isEqualTo(422);
        assertThat(coord.post("/api/agenda/" + id + "/cancelar", Map.of("motivo", "El punto cerró")).estado()).isEqualTo(200);
        assertThat(coord.post("/api/agenda/" + id + "/cancelar", Map.of("motivo", "Otra vez")).mensaje()).contains("ya está cerrada o cancelada");
    }

    @Test
    @DisplayName("reasignar (9.8): el reenvío de la notificación es obligatorio; con menos de 15 días se advierte; el mismo equipo se rechaza")
    void reasignar() {
        String qf = auditor("qf", "quimico_farmaceutico");
        String regente = auditor("reg", "regente");
        long p = punto("ESM", "EJC", "Dispensación", true);
        Navegador coord = coordinador();
        Map<String, Object> d = visita(p, qf, List.of(), List.of("C08"));
        d.put("fecha", dentroDe(10));
        d.put("notificar", true);
        long id = coord.post("/api/agenda", d).json().path("id").asLong();
        assertThat(coord.post("/api/agenda/" + id + "/reasignar", Map.of("liderId", idDe(qf), "otros", List.of(), "motivo", "Nada")).mensaje())
                .isEqualTo("El equipo no cambió.");
        Respuesta r = coord.post("/api/agenda/" + id + "/reasignar", Map.of("liderId", idDe(regente), "otros", List.of(), "motivo", "Incapacidad"));
        assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        assertThat(r.json().path("notificacionOk").asBoolean()).isTrue();
        assertThat(r.json().path("advertencias").toString()).contains("menos de 15 días", "químico farmacéutico");
        assertThat(correoDe(numeroDe(coord, id)).asunto()).endsWith(" · cambio de equipo");
        JsonNode det = coord.get("/api/agenda/" + id).json();
        assertThat(det.path("historial").toString()).contains("Equipo reasignado", "Notificado el cambio de equipo");
        assertThat(partes(det)).isNotEmpty();
        det.path("reparto").forEach(x -> assertThat(x.path("auditorId").asLong()).isEqualTo(idDe(regente)));
    }

    @Test
    @DisplayName("alcance: el auditor no entra a la agenda (403); Sanidad EJC ve en el calendario las del EJC y no las de la ARC")
    void alcance() {
        String qf = auditor("qf", "quimico_farmaceutico");
        long ejc = punto("ESM", "EJC", "Dispensación", false);
        long arc = punto("ESM", "ARC", "Dispensación", false);
        Navegador coord = coordinador();
        String nEjc = coord.post("/api/agenda", visita(ejc, qf, List.of(), List.of("C08"))).json().path("numero").asText();
        String nArc = coord.post("/api/agenda", visita(arc, qf, List.of(), List.of("C08"))).json().path("numero").asText();

        Navegador aud = navegador();
        aud.ingresar(qf, CLAVE);
        assertThat(aud.get("/api/agenda").estado()).isEqualTo(403);

        String sanidad = crearUsuario("sanidad", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'EJC' WHERE usuario = :u").param("u", sanidad).update();
        Navegador s = navegador();
        s.ingresar(sanidad, CLAVE);
        Respuesta cal = s.get("/api/calendario?desde=" + dentroDe(0) + "&hasta=" + dentroDe(60));
        assertThat(cal.estado()).as(cal.texto()).isEqualTo(200);
        assertThat(cal.texto()).contains(nEjc).doesNotContain(nArc).doesNotContain("alertas");
    }

    @Test
    @DisplayName("iniciar: solo un integrante del equipo; un auditor ajeno recibe 404 en Mis visitas")
    void iniciarSoloEquipo() {
        String qf = auditor("qf", "quimico_farmaceutico");
        long p = punto("ESM", "EJC", "Dispensación", false);
        long id = coordinador().post("/api/agenda", visita(p, qf, List.of(), List.of("C08"))).json().path("id").asLong();
        Navegador ajeno = navegador();
        ajeno.ingresar(auditor("ajeno", "regente"), CLAVE);
        assertThat(ajeno.post("/api/mis-visitas/" + id + "/iniciar", null).mensaje()).contains("no está asignada a usted");
        assertThat(jdbc.sql("SELECT estado FROM visitas WHERE id = :id").param("id", id).query(String.class).single()).isEqualTo("programada");
    }

    @Test
    @DisplayName("calendario: Sanidad EJC no abre el detalle de una visita ARC (404); el de una EJC sale sin equipo, reparto ni historial")
    void calendarioDetalleFueraDeAlcance() {
        String qf = auditor("qf", "quimico_farmaceutico");
        Navegador coord = coordinador();
        long idEjc = coord.post("/api/agenda", visita(punto("ESM", "EJC", "Dispensación", false), qf, List.of(), List.of("C08"))).json().path("id").asLong();
        long idArc = coord.post("/api/agenda", visita(punto("ESM", "ARC", "Dispensación", false), qf, List.of(), List.of("C08"))).json().path("id").asLong();
        String sanidad = crearUsuario("sanidad", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'EJC' WHERE usuario = :u").param("u", sanidad).update();
        Navegador s = navegador();
        s.ingresar(sanidad, CLAVE);
        assertThat(s.get("/api/calendario/" + idArc).estado()).isEqualTo(404);
        Respuesta ok = s.get("/api/calendario/" + idEjc);
        assertThat(ok.estado()).isEqualTo(200);
        assertThat(ok.json().has("equipo") || ok.json().has("reparto") || ok.json().has("historial")).isFalse();
        assertThat(ok.json().path("acciones").size()).isZero();
    }

    @Test
    @DisplayName("visita a necesidad por ejecutar: el OPL nacional no la ve (Anexo 3 p.14: sin previo aviso)")
    void oplNoVeNecesidadPendiente() {
        String qf = auditor("qf", "quimico_farmaceutico");
        Navegador coord = coordinador();
        Map<String, Object> d = visita(punto("ESM", "EJC", "Dispensación", false), qf, List.of(), List.of("C08"));
        d.put("tipo", "necesidad");
        d.put("fechaSolicitud", dentroDe(0));
        d.put("fecha", dentroDe(3));
        Respuesta r = coord.post("/api/agenda", d);
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        assertThat(r.json().path("notificacionOk").isMissingNode() || r.json().path("notificacionOk").isNull()).isTrue();
        Map<String, Object> det = Map.of("acciones", coord.get("/api/agenda/" + r.json().path("id").asLong()).json().path("acciones").toString());
        assertThat(det.get("acciones").toString()).doesNotContain("notificar");
        // Condición SQL del alcance del OPL: excluye la visita a necesidad por ejecutar
        var c = ServicioConsultaAgenda.condicion(new co.com.agsamericas.digsa.reglas.Alcance.Opl());
        long visibles = jdbc.sql("SELECT count(*) FROM visitas v JOIN puntos p ON p.id = v.punto_id WHERE v.id = :id AND (" + c.sql() + ")")
                .param("id", r.json().path("id").asLong()).query(Long.class).single();
        assertThat(visibles).isZero();
    }

    @Test
    @DisplayName("ESM: no ve en su calendario la visita a necesidad por ejecutar en su punto (lista ni detalle); sí la anual, sin el equipo")
    void esmNoVeNecesidadPendiente() {
        String qf = auditor("qf", "quimico_farmaceutico");
        long p = punto("ESM", "EJC", "Dispensación", false);
        Navegador coord = coordinador();
        Map<String, Object> nec = visita(p, qf, List.of(), List.of("C08"));
        nec.put("tipo", "necesidad");
        nec.put("fechaSolicitud", dentroDe(0));
        nec.put("fecha", dentroDe(3));
        Respuesta rn = coord.post("/api/agenda", nec);
        assertThat(rn.estado()).as(rn.texto()).isEqualTo(201);
        Respuesta ra = coord.post("/api/agenda", visita(p, qf, List.of(), List.of("C08")));
        String esm = crearUsuario("esm", Perfil.ESM);
        jdbc.sql("UPDATE usuarios SET alcance_punto = :p WHERE usuario = :u").param("p", p).param("u", esm).update();
        Navegador e = navegador();
        e.ingresar(esm, CLAVE);
        Respuesta cal = e.get("/api/calendario?desde=" + dentroDe(0) + "&hasta=" + dentroDe(60));
        assertThat(cal.estado()).as(cal.texto()).isEqualTo(200);
        assertThat(cal.texto()).contains(ra.json().path("numero").asText()).doesNotContain(rn.json().path("numero").asText())
                .doesNotContain("\"lider\"");
        assertThat(e.get("/api/calendario/" + rn.json().path("id").asLong()).estado()).isEqualTo(404);
    }

    @Test
    @DisplayName("403 con el perfil equivocado en las pantallas nuevas, y no cambia nada")
    void perfilesSinPantalla() {
        Navegador aud = navegador();
        aud.ingresar(auditor("aud", "regente"), CLAVE);
        String sanidad = crearUsuario("sanidad", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'EJC' WHERE usuario = :u").param("u", sanidad).update();
        Navegador san = navegador();
        san.ingresar(sanidad, CLAVE);
        for (Navegador n : List.of(aud, san)) {
            assertThat(n.get("/api/maestros/puntos").estado()).isEqualTo(403);
            assertThat(n.post("/api/maestros/puntos", Map.of("nombre", "No debe")).estado()).isEqualTo(403);
            assertThat(n.put("/api/maestros/opl", Map.of("nombre", "No debe")).estado()).isEqualTo(403);
            assertThat(n.delete("/api/catalogos/fuerzas/EJC").estado()).isEqualTo(403);
            assertThat(n.put("/api/catalogos/parametros/aviso_umbral_opl", Map.of("valor", "3")).estado()).isEqualTo(403);
            assertThat(n.post("/api/agenda", Map.of()).estado()).isEqualTo(403);
        }
        assertThat(aud.get("/api/calendario").estado()).isEqualTo(403);
        assertThat(coordinador().get("/api/mis-visitas").estado()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM par_fuerzas WHERE codigo = 'EJC'").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM log_transacciones WHERE tipo = 'acceso_denegado' AND usuario_id = :u").param("u", idDe(sanidad))
                .query(Long.class).single()).isPositive();
    }
}
