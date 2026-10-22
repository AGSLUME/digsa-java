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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ejecución de la visita (Anexo 5, pruebas 10 y 12): formulario por partes, datos de la visita, personal, evidencias múltiples en una
 * sola petición, firmas, acta en PDF con cierre, reapertura con motivo y corrección del coordinador.
 */
class EjecucionApiTest extends EscenariosVisita {

    @Test
    @DisplayName("prueba 12: número, lugar y auditor automáticos; cada auditor califica solo su parte; «No aplica» de habilitación solo con condición")
    void formularioPorPartes() {
        Escenario e = escenario();
        JsonNode f = e.regente().get("/api/visitas/" + e.id() + "/formulario").json();
        assertThat(f.path("visita").path("numero").asText()).isEqualTo(e.numero());
        assertThat(f.path("visita").path("lugar").asText()).startsWith("Punto ejecucion");
        assertThat(f.path("visita").path("lider").asText()).startsWith("Persona qf");
        assertThat(f.path("permisos").path("misPartes").toString()).contains("C08").doesNotContain("C00");
        assertThat(f.path("permisos").path("escribeVisita").asBoolean()).isFalse();
        List<JsonNode> c08 = items(f, "C08");
        List<JsonNode> c00 = items(f, "C00");
        assertThat(c08).isNotEmpty().allMatch(i -> i.path("editable").asBoolean());
        assertThat(c00).isNotEmpty().noneMatch(i -> i.path("editable").asBoolean());

        Respuesta ajena = e.regente().put("/api/visitas/" + e.id() + "/respuestas", Map.of("respuestas", calificar(c00.subList(0, 1), "C")));
        assertThat(ajena.estado()).isEqualTo(422);
        assertThat(ajena.mensaje()).contains("es de otro auditor");
        Respuesta propia = e.regente().put("/api/visitas/" + e.id() + "/respuestas", Map.of("respuestas", calificar(c08, "C")));
        assertThat(propia.estado()).as(propia.texto()).isEqualTo(200);

        JsonNode sinCondicion = c00.stream().filter(i -> !i.path("admiteNoAplica").asBoolean()).findFirst().orElseThrow();
        Respuesta na = e.lider().put("/api/visitas/" + e.id() + "/respuestas", Map.of("respuestas", calificar(List.of(sinCondicion), "NA")));
        assertThat(na.estado()).isEqualTo(422);
        assertThat(na.mensaje()).contains("habilitación con condición");
        assertThat(e.regente().put("/api/visitas/" + e.id() + "/ejecucion", Map.of("objeto", "x")).estado()).as("solo el líder").isEqualTo(403);

        Navegador ajeno = entrar(auditor("ajeno", "regente"));
        assertThat(ajeno.get("/api/visitas/" + e.id() + "/formulario").estado()).isEqualTo(404);
    }

    @Test
    @DisplayName("prueba 12: varias evidencias en una sola petición; si una no es de un tipo admitido no queda ninguna")
    void evidenciasMultiples() throws Exception {
        Escenario e = escenario();
        String item = items(e.regente().get("/api/visitas/" + e.id() + "/formulario").json(), "C08").get(0).path("codigo").asText();
        Map<String, byte[]> dos = new LinkedHashMap<>();
        dos.put("foto1.png", firmaPng());
        dos.put("soporte.pdf", "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF".getBytes());
        Respuesta ok = e.regente().multipart("/api/visitas/" + e.id() + "/archivos", Map.of("clave", "it:" + item), dos);
        assertThat(ok.estado()).as(ok.texto()).isEqualTo(201);
        assertThat(ok.json().path("archivos").size()).isEqualTo(2);
        long id = ok.json().path("archivos").get(0).path("id").asLong();
        Respuesta bajada = e.coord().get("/api/archivos/" + id);
        assertThat(bajada.estado()).isEqualTo(200);
        assertThat(bajada.cabecera("X-Content-Type-Options")).isEqualTo("nosniff");

        Map<String, byte[]> mala = new LinkedHashMap<>();
        mala.put("otra.png", firmaPng());
        mala.put("programa.exe", new byte[] {'M', 'Z', 0, 1, 2, 3, 4, 5, 6, 7, 8, 9});
        long antes = jdbc.sql("SELECT count(*) FROM archivos WHERE visita_id = :v").param("v", e.id()).query(Long.class).single();
        Respuesta r = e.regente().multipart("/api/visitas/" + e.id() + "/archivos", Map.of("clave", "it:" + item), mala);
        assertThat(r.estado()).isEqualTo(422);
        assertThat(r.mensaje()).contains("programa.exe");
        assertThat(jdbc.sql("SELECT count(*) FROM archivos WHERE visita_id = :v").param("v", e.id()).query(Long.class).single()).isEqualTo(antes);
        String ajeno = items(e.regente().get("/api/visitas/" + e.id() + "/formulario").json(), "C00").get(0).path("codigo").asText();
        assertThat(e.regente().multipart("/api/visitas/" + e.id() + "/archivos", Map.of("clave", "it:" + ajeno), Map.of("x.png", firmaPng())).estado())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("firma con cabecera PNG y contenido corrupto: 422 y no queda guardada")
    void firmaCorruptaSeRechaza() {
        Escenario e = escenario();
        byte[] falso = new byte[300];
        falso[0] = (byte) 0x89;
        falso[1] = 'P';
        falso[2] = 'N';
        falso[3] = 'G';
        Respuesta r = e.lider().post("/api/visitas/" + e.id() + "/firmas", Map.of("rol", "auditor", "nombre", "Auditor líder", "imagen", base64(falso)));
        assertThat(r.estado()).isEqualTo(422);
        assertThat(jdbc.sql("SELECT count(*) FROM visitas_firmas WHERE visita_id = :v").param("v", e.id()).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("si el acta no llega al OPL la visita sigue en ejecución; al reintentar con el correo bien, la misma ronda sale y cierra")
    void actaFallidaSeReintenta() throws Exception {
        Escenario e = escenario();
        completar(e);
        jdbc.sql("UPDATE opl SET correos = ARRAY['opl@rechazo.invalid'] WHERE activo").update(); // el correo local rechaza ese dominio
        Respuesta falla = e.lider().post("/api/visitas/" + e.id() + "/acta", null);
        assertThat(falla.json().path("ok").asBoolean()).as(falla.texto()).isFalse();
        assertThat(jdbc.sql("SELECT estado FROM visitas WHERE id = :id").param("id", e.id()).query(String.class).single()).isEqualTo("ejecucion");
        assertThat(jdbc.sql("SELECT estado FROM visitas_actas WHERE visita_id = :v").param("v", e.id()).query(String.class).single()).isEqualTo("fallida");
        assertThat(bitacoraDe(idDe(e.liderUsuario()))).noneMatch(x -> x.contains("visita_cerrada"));
        jdbc.sql("UPDATE opl SET correos = ARRAY[:c] WHERE activo").param("c", OPL).update();
        Respuesta ok = e.lider().post("/api/visitas/" + e.id() + "/acta", null);
        assertThat(ok.json().path("ok").asBoolean()).as(ok.texto()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM visitas_actas WHERE visita_id = :v").param("v", e.id()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT estado FROM visitas WHERE id = :id").param("id", e.id()).query(String.class).single()).isEqualTo("cerrada");
    }

    @Test
    @DisplayName("archivos: el ajeno no los ve (404), los perfiles de consulta no entran (403); quitar uno de otra parte 403; el acta no se quita")
    void archivosAutorizacion() throws Exception {
        Escenario e = escenario();
        JsonNode f = e.regente().get("/api/visitas/" + e.id() + "/formulario").json();
        String propio = items(f, "C08").get(0).path("codigo").asText();
        long id = e.regente().multipart("/api/visitas/" + e.id() + "/archivos", Map.of("clave", "it:" + propio), Map.of("a.png", firmaPng()))
                .json().path("archivos").get(0).path("id").asLong();
        assertThat(entrar(auditor("ajeno", "regente")).get("/api/archivos/" + id).estado()).isEqualTo(404);
        String sanidad = crearUsuario("sanidad", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'EJC' WHERE usuario = :u").param("u", sanidad).update();
        assertThat(entrar(sanidad).get("/api/archivos/" + id).estado()).isEqualTo(403);
        assertThat(e.lider().get("/api/archivos/" + id).estado()).as("el equipo sí lo ve").isEqualTo(200);
        assertThat(e.lider().delete("/api/archivos/" + id).estado()).as("es de la parte del regente").isEqualTo(403);
        assertThat(e.regente().delete("/api/archivos/" + id).estado()).isEqualTo(200);
        assertThat(e.regente().delete("/api/archivos/" + id).estado()).isEqualTo(404);

        completar(e);
        assertThat(e.lider().post("/api/visitas/" + e.id() + "/acta", null).json().path("ok").asBoolean()).isTrue();
        long acta = jdbc.sql("SELECT archivo_id FROM visitas_actas WHERE visita_id = :v").param("v", e.id()).query(Long.class).single();
        assertThat(e.coord().delete("/api/archivos/" + acta).estado()).isEqualTo(422);
        assertThat(e.coord().get("/api/archivos/" + acta).estado()).isEqualTo(200);
    }

    @Test
    @DisplayName("reabrir: el administrador también; no se reabre una en ejecución; descripción corta 422; el coordinador que corrige no firma")
    void reaperturaBordes() throws Exception {
        Escenario e = escenario();
        String base = "/api/visitas/" + e.id();
        assertThat(e.coord().post(base + "/reabrir", Map.of("motivo", "soporte", "descripcion", "Falta un soporte")).mensaje())
                .contains("Solo se reabre una visita cerrada");
        completar(e);
        assertThat(e.lider().post(base + "/acta", null).json().path("ok").asBoolean()).isTrue();
        Navegador admin = entrar(crearUsuario("admin", Perfil.ADMINISTRADOR));
        assertThat(admin.post(base + "/reabrir", Map.of("motivo", "cliente", "descripcion", "Corto")).estado()).isEqualTo(422);
        assertThat(admin.post(base + "/reabrir", Map.of("motivo", "cliente", "descripcion", "Solicitud de la DIGSA de revisar")).estado()).isEqualTo(200);
        Respuesta firma = e.coord().post(base + "/firmas", Map.of("rol", "esm", "nombre", "Otra persona", "imagen", base64(firmaPng())));
        assertThat(firma.estado()).isEqualTo(403);
        assertThat(firma.mensaje()).contains("no cambia las firmas");
    }

    @Test
    @DisplayName("prueba 10: el acta no sale con pendientes; completa, sale en PDF a los destinatarios y cierra; se reabre, se corrige y sale la ronda 2")
    void actaYReapertura() throws Exception {
        Escenario e = escenario();
        String base = "/api/visitas/" + e.id();
        Respuesta antes = e.lider().post(base + "/acta", null);
        assertThat(antes.estado()).isEqualTo(422);
        assertThat(antes.mensaje()).contains("Calificar todo", "inicio y fin", "objeto", "personal auditado", "firma del auditor");

        JsonNode f = e.lider().get(base + "/formulario").json();
        List<JsonNode> c00 = items(f, "C00");
        List<Map<String, Object>> resp = calificar(c00, "C");
        resp.set(0, calificar(c00.subList(0, 1), "NC").get(0));
        assertThat(e.lider().put(base + "/respuestas", Map.of("respuestas", resp)).estado()).isEqualTo(200);
        assertThat(e.regente().put(base + "/respuestas", Map.of("respuestas", calificar(items(f, "C08"), "C"))).estado()).isEqualTo(200);
        Map<String, Object> ej = new HashMap<>();
        ej.put("inicioReal", hace(120));
        ej.put("finReal", hace(10));
        ej.put("objeto", "Verificar habilitación y almacenamiento del servicio farmacéutico.");
        ej.put("recomendaciones", "Mantener el registro de temperatura diario.");
        ej.put("observacionesEsm", "Sin observaciones del establecimiento.");
        Respuesta guardada = e.lider().put(base + "/ejecucion", ej);
        assertThat(guardada.estado()).as(guardada.texto()).isEqualTo(200);
        Map<String, Object> fin = new HashMap<>(ej);
        fin.put("finReal", hace(200));
        assertThat(e.lider().put(base + "/ejecucion", fin).mensaje()).contains("no puede ser anterior");
        assertThat(e.lider().put(base + "/personal", Map.of("personas", List.of(
                Map.of("entidad", "ESM", "nombre", "Regente del punto", "cargo", "Regente de farmacia"),
                Map.of("entidad", "OPL", "nombre", "Coordinador del OPL", "cargo", "Coordinador", "correo", "coord.opl@example.com")))).estado())
                .isEqualTo(200);
        String png = base64(firmaPng());
        assertThat(e.lider().post(base + "/firmas", Map.of("rol", "auditor", "nombre", "Auditor líder de prueba", "imagen", png)).estado()).isEqualTo(200);
        assertThat(e.lider().post(base + "/firmas", Map.of("rol", "esm", "nombre", "Regente del punto", "imagen", "data:image/png;base64,AAAA"))
                .mensaje()).contains("PNG");
        assertThat(e.lider().post(base + "/firmas", Map.of("rol", "esm", "nombre", "Regente del punto", "imagen", png)).estado()).isEqualTo(200);
        assertThat(e.regente().post(base + "/acta", null).estado()).as("el acta es del líder").isEqualTo(403);
        Respuesta sinPlan = e.lider().post(base + "/acta", null);
        assertThat(sinPlan.mensaje()).contains("fecha del plan de mejoramiento", "qué del plan", "firma del auditor en el plan");
        planYFirmas(e, List.of("it:" + c00.get(0).path("codigo").asText()));

        Respuesta envio = e.lider().post(base + "/acta", null);
        assertThat(envio.estado()).as(envio.texto()).isEqualTo(200);
        assertThat(envio.json().path("ok").asBoolean()).as(envio.texto()).isTrue();
        assertThat(jdbc.sql("SELECT estado FROM visitas WHERE id = :id").param("id", e.id()).query(String.class).single()).isEqualTo("cerrada");
        Correo.Mensaje m = correos().reversed().stream().filter(x -> x.asunto().contains(e.numero()) && x.asunto().startsWith("Acta")).findFirst().orElseThrow();
        assertThat(m.para()).contains(OPL, "disan.ejc@example.com", "coord.opl@example.com");
        assertThat(m.adjuntos()).hasSize(1);
        assertThat(new String(m.adjuntos().get(0).contenido(), 0, 5)).isEqualTo("%PDF-");
        assertThat(m.html()).contains("PLAZO MÁXIMO PARA REMITIR EL PLAN DE MEJORAMIENTO FIRMADO", "Huella SHA-256");
        assertThat(token(e)).hasSize(64);
        assertThat(jdbc.sql("SELECT count(*) FROM visitas_hallazgos WHERE visita_id = :v").param("v", e.id()).query(Long.class).single()).isEqualTo(1);
        Map<String, Object> resumen = jdbc.sql("SELECT nc, hallazgos FROM visitas_resumen WHERE visita_id = :v").param("v", e.id()).query().singleRow();
        assertThat(((Number) resumen.get("nc")).intValue()).isEqualTo(1);
        assertThat(e.lider().put(base + "/respuestas", Map.of("respuestas", calificar(c00.subList(0, 1), "C"))).mensaje()).contains("Visita cerrada");

        // Reabrir (prueba 10): solo el coordinador, con motivo y descripción
        assertThat(e.lider().post(base + "/reabrir", Map.of("motivo", "soporte", "descripcion", "Falta un soporte del ítem")).estado()).isEqualTo(403);
        assertThat(e.coord().post(base + "/reabrir", Map.of("motivo", "otro", "descripcion", "Falta un soporte del ítem")).estado()).isEqualTo(422);
        Respuesta re = e.coord().post(base + "/reabrir", Map.of("motivo", "soporte", "descripcion", "Faltó cargar el soporte de habilitación"));
        assertThat(re.estado()).as(re.texto()).isEqualTo(200);
        JsonNode fc = e.coord().get(base + "/formulario").json();
        assertThat(fc.path("permisos").path("correccion").asBoolean()).isTrue();
        assertThat(fc.path("visita").path("ronda").asInt()).isEqualTo(2);
        assertThat(e.coord().put(base + "/respuestas", Map.of("respuestas", calificar(c00.subList(0, 1), "C"))).estado()).isEqualTo(200);
        JsonNode det = e.coord().get("/api/agenda/" + e.id()).json();
        assertThat(det.path("historial").toString()).contains("Reabierta", "Corrección", "Cerrada");
        assertThat(e.coord().post(base + "/acta", null).estado()).as("el coordinador no envía el acta").isEqualTo(403);

        // Decisión D12: la ronda nueva se firma de nuevo; las firmas de la ronda 1 se conservan
        Respuesta sinFirmas = e.lider().post(base + "/acta", null);
        assertThat(sinFirmas.mensaje()).contains("Falta la firma del auditor líder");
        assertThat(e.lider().get(base + "/formulario").json().path("firmas").size()).as("la ronda 2 empieza sin firmas").isZero();
        firmarRonda(e, false);
        Respuesta ronda2 = e.lider().post(base + "/acta", null);
        assertThat(ronda2.json().path("ok").asBoolean()).as(ronda2.texto()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM visitas_firmas WHERE visita_id = :v AND ronda = 1").param("v", e.id()).query(Long.class).single())
                .as("las firmas de la ronda 1 se conservan").isEqualTo(4);
        assertThat(ronda2.json().path("ronda").asInt()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM visitas_actas WHERE visita_id = :v").param("v", e.id()).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT nc FROM visitas_resumen WHERE visita_id = :v").param("v", e.id()).query(Integer.class).single()).isZero();
    }
}
