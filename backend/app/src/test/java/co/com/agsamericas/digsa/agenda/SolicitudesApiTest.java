package co.com.agsamericas.digsa.agenda;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

/**
 * Fase 5: solicitudes de visita a necesidad (decisiones 12, 31 y 33; 9.3, 9.4 y 9.6 del 2026-10-20): registro por la DIGSA o la
 * Sanidad con su escrito, aviso al coordinador, cada solicitante ve solo las suyas, agendar como visita a necesidad y liberar al cancelar.
 */
class SolicitudesApiTest extends PruebaIntegracion {

    private static final byte[] PDF = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF".getBytes();

    private String hoy(int dias) {
        return LocalDate.now(reloj.withZone(Fechas.BOGOTA)).plusDays(dias).toString();
    }

    private String sanidad(String fuerza) {
        String u = crearUsuario("sanidad", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = :f WHERE usuario = :u").param("f", fuerza).param("u", u).update();
        return u;
    }

    private Navegador entrar(String u) {
        Navegador n = navegador();
        assertThat(n.ingresar(u, CLAVE).estado()).isEqualTo(200);
        return n;
    }

    private long punto(String fuerza) {
        return jdbc.sql("""
                INSERT INTO puntos (nombre, unidad, fuerza, modalidad, municipio, creado_por) VALUES (:n, 'ESM', :f, 'Dispensación', '05001', 'prueba')
                RETURNING id""").param("n", unico("Punto solicitudes ")).param("f", fuerza).query(Long.class).single();
    }

    private Respuesta solicitar(Navegador n, long punto, String fecha, String motivo, Map<String, byte[]> escrito) {
        Map<String, String> campos = new HashMap<>();
        campos.put("punto_id", String.valueOf(punto));
        campos.put("fecha_solicitud", fecha);
        campos.put("motivo", motivo);
        return n.multipart("/api/solicitudes", campos, escrito, "escrito");
    }

    private Map<String, Object> visitaDe(long solicitud, long otroPunto, String lider) {
        Map<String, Object> d = new HashMap<>();
        d.put("tipo", "anual"); // la solicitud manda: queda como visita a necesidad a su punto
        d.put("puntoId", otroPunto);
        d.put("fecha", hoy(2));
        d.put("liderId", idDe(lider));
        d.put("alcance", "Visita a necesidad por la solicitud.");
        d.put("componentes", List.of("C08"));
        d.put("solicitudId", solicitud);
        return d;
    }

    @Test
    @DisplayName("Sanidad registra con su escrito en PDF dentro de su alcance; el coordinador recibe el correo (9.6); validaciones")
    void registrar() {
        String coord = crearUsuario("coord", Perfil.COORDINADOR);
        String s = sanidad("EJC");
        Navegador n = entrar(s);
        long ejc = punto("EJC");
        long arc = punto("ARC");
        JsonNode lista = n.get("/api/solicitudes").json();
        assertThat(lista.path("puedeSolicitar").asBoolean()).isTrue();
        assertThat(lista.path("entidad").asText()).contains("EJC");
        assertThat(lista.path("puntos").toString()).contains("\"id\":" + ejc).doesNotContain("\"id\":" + arc + ",");

        Respuesta r = solicitar(n, ejc, hoy(0), "Quejas de usuarios por medicamentos pendientes.", Map.of("escrito.pdf", PDF));
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        assertThat(r.json().path("mensaje").asText()).contains("5 días hábiles");
        // Aviso informativo por la bandeja de salida: sale en otro hilo al confirmar
        esperarCorreoA(coord + "@example.com", "Solicitud de visita a necesidad N.º " + r.json().path("id").asLong());

        assertThat(solicitar(n, arc, hoy(0), "Fuera de mi alcance de fuerza", Map.of()).mensaje()).contains("punto activo de su alcance");
        assertThat(solicitar(n, ejc, hoy(1), "Fecha futura no permitida", Map.of()).mensaje()).contains("no puede ser futura");
        assertThat(solicitar(n, ejc, hoy(0), "Corto", Map.of()).mensaje()).contains("motivo");
        assertThat(solicitar(n, ejc, hoy(0), "Escrito que no es PDF", Map.of("foto.png", co.com.agsamericas.digsa.ejecucion.EjecucionApiTestPng.png())).mensaje())
                .contains("debe ser un PDF");
        Navegador opl = entrar(crearUsuario("opl", Perfil.OPL_NACIONAL));
        assertThat(opl.get("/api/solicitudes").estado()).isEqualTo(403);
    }

    @Test
    @DisplayName("9.4: cada solicitante ve solo sus solicitudes y su escrito; el coordinador ve todas con el solicitante")
    void cadaQuienLoSuyo() {
        long ejc = punto("EJC");
        Navegador a = entrar(sanidad("EJC"));
        Navegador b = entrar(sanidad("EJC"));
        long id = solicitar(a, ejc, hoy(0), "Solicitud del usuario A de Sanidad", Map.of("escrito.pdf", PDF)).json().path("id").asLong();
        assertThat(a.get("/api/solicitudes").texto()).contains("Solicitud del usuario A");
        assertThat(b.get("/api/solicitudes").texto()).doesNotContain("Solicitud del usuario A");
        assertThat(a.get("/api/solicitudes/" + id + "/escrito").estado()).isEqualTo(200);
        assertThat(b.get("/api/solicitudes/" + id + "/escrito").estado()).isEqualTo(404);
        Navegador coord = entrar(crearUsuario("coord", Perfil.COORDINADOR));
        JsonNode todas = coord.get("/api/solicitudes").json();
        assertThat(todas.path("solicitudes").toString()).contains("Solicitud del usuario A", "Persona sanidad");
        assertThat(coord.get("/api/solicitudes/" + id + "/escrito").estado()).isEqualTo(200);
        assertThat(b.post("/api/solicitudes/" + id + "/anular", Map.of("motivo", "No")).estado()).as("solo el coordinador anula").isEqualTo(403);
        assertThat(coord.post("/api/solicitudes/" + id + "/anular", Map.of("motivo", "Duplicada")).estado()).isEqualTo(200);
        assertThat(coord.post("/api/solicitudes/" + id + "/anular", Map.of("motivo", "Otra vez")).mensaje()).contains("ya no está pendiente");
    }

    @Test
    @DisplayName("la solicitud manda también al editar; anulada no se agenda (sin visita nueva); Sanidad no agenda; el solicitante no ve su propio nombre")
    void bordes() {
        String qf = crearUsuario("qf", Perfil.AUDITOR);
        jdbc.sql("INSERT INTO auditores (usuario_id, rol) VALUES (:id, 'quimico_farmaceutico')").param("id", idDe(qf)).update();
        long ejc = punto("EJC");
        long otro = punto("EJC");
        String s = sanidad("EJC");
        Navegador ns = entrar(s);
        long id = solicitar(ns, ejc, hoy(0), "Solicitud para probar los bordes", Map.of()).json().path("id").asLong();
        assertThat(ns.get("/api/solicitudes").json().path("solicitudes").get(0).path("solicitante").isNull()).isTrue();
        assertThat(ns.post("/api/agenda", visitaDe(id, otro, qf)).estado()).as("Sanidad no agenda").isEqualTo(403);

        Navegador coord = entrar(crearUsuario("coord", Perfil.COORDINADOR));
        long visita = coord.post("/api/agenda", visitaDe(id, otro, qf)).json().path("id").asLong();
        Map<String, Object> editada = visitaDe(id, otro, qf);
        editada.remove("solicitudId");
        editada.put("tipo", "anual");
        assertThat(coord.put("/api/agenda/" + visita, editada).estado()).isEqualTo(200);
        Map<String, Object> v = jdbc.sql("SELECT tipo, punto_id FROM visitas WHERE id = :id").param("id", visita).query().singleRow();
        assertThat(v.get("tipo")).as("sigue siendo a necesidad").isEqualTo("necesidad");
        assertThat(((Number) v.get("punto_id")).longValue()).isEqualTo(ejc);

        long otra = solicitar(ns, ejc, hoy(0), "Otra solicitud que se anula", Map.of()).json().path("id").asLong();
        assertThat(coord.post("/api/solicitudes/" + otra + "/anular", Map.of("motivo", "Duplicada")).estado()).isEqualTo(200);
        long antes = jdbc.sql("SELECT count(*) FROM visitas").query(Long.class).single();
        assertThat(coord.post("/api/agenda", visitaDe(otra, otro, qf)).estado()).isEqualTo(422);
        assertThat(jdbc.sql("SELECT count(*) FROM visitas").query(Long.class).single()).as("no queda visita").isEqualTo(antes);
    }

    @Test
    @DisplayName("agendar: la solicitud manda (necesidad, su punto y su fecha), se avisa al solicitante; cancelar la libera y avisa (9.3)")
    void agendarYCancelar() {
        String qf = crearUsuario("qf", Perfil.AUDITOR);
        jdbc.sql("INSERT INTO auditores (usuario_id, rol) VALUES (:id, 'quimico_farmaceutico')").param("id", idDe(qf)).update();
        long ejc = punto("EJC");
        long otro = punto("EJC");
        String s = sanidad("EJC");
        long id = solicitar(entrar(s), ejc, hoy(0), "Desabastecimiento reportado por el punto", Map.of()).json().path("id").asLong();
        Navegador coord = entrar(crearUsuario("coord", Perfil.COORDINADOR));

        Respuesta r = coord.post("/api/agenda", visitaDe(id, otro, qf));
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        long visita = r.json().path("id").asLong();
        assertThat(r.json().path("mensaje").asText()).contains("Se avisó al solicitante");
        Map<String, Object> v = jdbc.sql("SELECT tipo, punto_id, fecha_solicitud FROM visitas WHERE id = :id").param("id", visita).query().singleRow();
        assertThat(v.get("tipo")).isEqualTo("necesidad");
        assertThat(((Number) v.get("punto_id")).longValue()).isEqualTo(ejc);
        assertThat(v.get("fecha_solicitud").toString()).isEqualTo(hoy(0));
        esperarCorreoA(s + "@example.com", "Visita a necesidad agendada");
        assertThat(jdbc.sql("SELECT estado FROM visitas_solicitudes WHERE id = :id").param("id", id).query(String.class).single()).isEqualTo("programada");
        assertThat(coord.post("/api/agenda", visitaDe(id, otro, qf)).mensaje()).contains("ya no está pendiente");

        Respuesta c = coord.post("/api/agenda/" + visita + "/cancelar", Map.of("motivo", "El punto estaba cerrado"));
        assertThat(c.estado()).as(c.texto()).isEqualTo(200);
        assertThat(c.json().path("mensaje").asText()).contains("volvió a pendiente");
        assertThat(jdbc.sql("SELECT estado FROM visitas_solicitudes WHERE id = :id").param("id", id).query(String.class).single()).isEqualTo("pendiente");
        esperarCorreoA(s + "@example.com", "Visita a necesidad cancelada");
        assertThat(coord.get("/api/agenda/" + visita).json().path("historial").toString()).contains("Solicitud liberada", "Aviso al solicitante");
        assertThat(coord.post("/api/agenda", visitaDe(id, otro, qf)).estado()).as("se puede reprogramar").isEqualTo(201);
    }
}
