package co.com.agsamericas.digsa.ejecucion;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fase 10: el acta enviada con hallazgos de intervención inmediata avisa en segundos a la coordinación y a la DIGSA (alerta operativa
 * «intervencion_inmediata», solo correo), con el número de la visita y el punto y sin el texto de los hallazgos.
 */
class AlertaIntervencionApiTest extends EscenariosVisita {

    @Test
    @DisplayName("acta con intervención inmediata → aviso a coordinación y DIGSA en segundos, una vez por acta y sin el texto del hallazgo")
    void actaConIntervencionInmediata() throws Exception {
        String digsa = crearUsuario("digsaalerta", Perfil.DIGSA);
        Escenario e = escenario();
        String base = "/api/visitas/" + e.id();
        completar(e);
        JsonNode f = e.lider().get(base + "/formulario").json();
        String codigo = items(f, "C00").get(0).path("codigo").asText();
        Map<String, Object> nc = new LinkedHashMap<>(Map.of("item", codigo, "calificacion", "NC", "hallazgo", "Nevera sin registro de temperatura", "inmediata", true));
        assertThat(e.lider().put(base + "/respuestas", Map.of("respuestas", List.of(nc))).estado()).isEqualTo(200);
        planYFirmas(e, List.of("it:" + codigo));

        Respuesta envio = e.lider().post(base + "/acta", null);
        long t0 = System.nanoTime(); // el acta ya salió (generarla y enviarla toma segundos): desde aquí corre solo el aviso
        assertThat(envio.json().path("ok").asBoolean()).as(envio.texto()).isTrue();
        Correo.Mensaje aviso = esperarCorreo(m -> m.asunto().startsWith("Aviso · Hallazgo de intervención inmediata") && m.html().contains(e.numero()));
        long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        assertThat(ms).isLessThan(3000);
        assertThat(aviso.para()).contains(digsa + "@example.com");
        assertThat(aviso.html()).contains("1 hallazgo(s) de intervención inmediata").doesNotContain("Nevera sin registro");
        assertThat(aviso.adjuntos()).isEmpty();

        long actaId = jdbc.sql("SELECT id FROM visitas_actas WHERE visita_id = :v").param("v", e.id()).query(Long.class).single();
        assertThat(jdbc.sql("SELECT count(*) FROM alertas_eventos WHERE tipo = 'intervencion_inmediata' AND clave = :c AND NOT abierta")
                .param("c", "acta:" + actaId).query(Long.class).single()).isEqualTo(1);
        esperar(() -> jdbc.sql("""
                SELECT count(*) FROM correos_salida c JOIN alertas_eventos a ON a.id = c.alerta_id WHERE a.clave = :c AND c.estado = 'enviado'""")
                .param("c", "acta:" + actaId).query(Long.class).single() == 1, "aviso enviado");
        double latencia = jdbc.sql("""
                SELECT extract(epoch FROM c.enviado - a.creado) * 1000 FROM correos_salida c JOIN alertas_eventos a ON a.id = c.alerta_id
                WHERE a.clave = :c AND a.tipo = 'intervencion_inmediata'""").param("c", "acta:" + actaId).query(Double.class).single();
        System.out.println("[latencia] intervención inmediata: respuesta del acta → aviso entregado en " + ms + " ms; evento → envío guardado " + Math.round(latencia) + " ms");
        assertThat(latencia).isLessThan(3000);
        assertThat(jdbc.sql("SELECT count(*) FROM sms_salida s JOIN alertas_eventos a ON a.id = s.alerta_id WHERE a.clave = :c AND a.tipo = 'intervencion_inmediata'")
                .param("c", "acta:" + actaId).query(Long.class).single()).as("operativa: sin SMS").isZero();
        assertThat(jdbc.sql("SELECT estado FROM visitas WHERE id = :v").param("v", e.id()).query(String.class).single()).isEqualTo("cerrada");
    }
}
