package co.com.agsamericas.digsa.correos;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Módulo Correos, fase C2: catálogo de plantillas, vista previa, edición con variables protegidas y «Restaurar original». */
class PlantillasApiTest extends PruebaIntegracion {

    private static final String ORIGINAL_LOGS = "<p>Adjunto el reporte de logs de auditoría generado en la Suite DIGSA.</p><p>Archivo: {{archivo}}</p>";

    private Navegador admin(String prefijo) {
        String adm = crearUsuario(prefijo, Perfil.ADMINISTRADOR);
        Navegador n = navegador();
        assertThat(n.ingresar(adm, CLAVE).estado()).isEqualTo(200);
        return n;
    }

    @Test
    @DisplayName("el catálogo lista todas las plantillas; MFA, contraseña y código del OPL se ven pero no se editan; solo el Administrador")
    void catalogo() {
        Navegador n = admin("admplc");
        Respuesta lista = n.get("/api/correos/plantillas");
        assertThat(lista.estado()).isEqualTo(200);
        Map<String, Boolean> editables = new java.util.HashMap<>();
        lista.json().forEach(p -> editables.put(p.path("clave").asText(), p.path("editable").asBoolean()));
        assertThat(editables).containsEntry("mfa_codigo", false).containsEntry("clave_enlace", false).containsEntry("opl_codigo", false)
                .containsEntry("notificacion_previa", true).containsEntry("acta", true).containsEntry("reporte_logs", true)
                .containsEntry("presupuesto_notificacion", true).containsEntry("opl_objecion", true).containsEntry("alerta", true);
        assertThat(editables).hasSizeGreaterThanOrEqualTo(25);

        Respuesta mfa = n.get("/api/correos/plantillas/mfa_codigo");
        assertThat(mfa.json().path("motivoNoEditable").asText()).contains("un solo uso");
        assertThat(mfa.json().path("cuerpo").asText()).contains("{{codigo}}");
        assertThat(n.put("/api/correos/plantillas/mfa_codigo", Map.of("asunto", "x", "cuerpo", "{{codigo}}", "motivo", "Intento de editar el MFA"))
                .estado()).isEqualTo(409);
        assertThat(n.post("/api/correos/plantillas/mfa_codigo/restaurar", Map.of("motivo", "Intento de restaurar el MFA")).estado()).isEqualTo(409);
        // La vista previa del MFA usa el código oculto de ejemplo
        assertThat(n.post("/api/correos/plantillas/mfa_codigo/vista-previa", Map.of()).json().path("html").asText()).contains("••••••");
        assertThat(n.get("/api/correos/plantillas/no_existe").estado()).isEqualTo(404);

        String coord = crearUsuario("coorplc", Perfil.COORDINADOR);
        Navegador c = navegador();
        c.ingresar(coord, CLAVE);
        assertThat(c.get("/api/correos/plantillas").estado()).isEqualTo(403);
    }

    @Test
    @DisplayName("editar con motivo cambia el correo que sale; las variables protegidas y el html no permitido se rechazan; restaurar vuelve al original")
    void editarYRestaurar() {
        String adm = crearUsuario("admple", Perfil.ADMINISTRADOR);
        Navegador n = navegador();
        n.ingresar(adm, CLAVE);
        String ruta = "/api/correos/plantillas/reporte_logs";
        String asunto = "Reporte de logs de auditoría · Suite DIGSA";
        String nuevo = "<p>Le enviamos los <strong>logs</strong> pedidos.</p><p>Archivo: {{archivo}}</p>";
        try {
            // Vista previa sin guardar: con datos ficticios, y con los errores de un texto que no sirve
            Respuesta vp = n.post(ruta + "/vista-previa", Map.of("asunto", asunto, "cuerpo", nuevo));
            assertThat(vp.json().path("errores").size()).isZero();
            assertThat(vp.json().path("html").asText()).contains("<strong>logs</strong>").contains("logs_2026-11-03.xlsx");
            Respuesta malo = n.post(ruta + "/vista-previa", Map.of("asunto", asunto, "cuerpo", "<p>Sin archivo</p><script>x()</script>"));
            assertThat(texto(malo.json().path("errores"))).contains("{{archivo}}").contains("<script>");

            assertThat(n.put(ruta, Map.of("asunto", asunto, "cuerpo", "<p>Sin archivo</p>", "motivo", "Texto más corto para los logs")).estado())
                    .isEqualTo(422);
            assertThat(n.put(ruta, Map.of("asunto", asunto, "cuerpo", "<p onclick=\"x()\">{{archivo}}</p>", "motivo", "Texto más corto para los logs"))
                    .estado()).isEqualTo(422);
            assertThat(n.put(ruta, Map.of("asunto", asunto, "cuerpo", nuevo, "motivo", "corto")).estado()).isEqualTo(422);
            assertThat(n.put(ruta, Map.of("asunto", asunto, "cuerpo", ORIGINAL_LOGS, "motivo", "Sin cambios respecto al vigente")).estado())
                    .isEqualTo(422);
            assertThat(n.post(ruta + "/restaurar", Map.of("motivo", "Restaurar algo que no se editó")).estado()).isEqualTo(409);

            Respuesta ok = n.put(ruta, Map.of("asunto", asunto, "cuerpo", nuevo, "motivo", "Texto más claro para los logs"));
            assertThat(ok.estado()).isEqualTo(200);
            assertThat(bitacoraDe(idDe(adm))).anyMatch(b -> b.startsWith("actualizacion|correo_plantilla_editada|") && b.contains("reporte_logs")
                    && b.contains("Texto más claro para los logs"));
            JsonNode ver = n.get(ruta).json();
            assertThat(ver.path("editada").asBoolean()).isTrue();
            assertThat(ver.path("cuerpo").asText()).isEqualTo(nuevo);
            assertThat(ver.path("cuerpoFabrica").asText()).isEqualTo(ORIGINAL_LOGS);
            assertThat(ver.path("historial").get(0).path("accion").asText()).isEqualTo("Editada");

            assertThat(n.post("/api/logs/enviar", Map.of("para", "evaluador@example.com", "usuarioId", idDe(adm))).estado()).isEqualTo(200);
            Correo.Mensaje editado = ultimoCorreoA("evaluador@example.com");
            assertThat(editado.html()).contains("Le enviamos los <strong>logs</strong> pedidos.").contains("Archivo: ");

            Respuesta rest = n.post(ruta + "/restaurar", Map.of("motivo", "Volver al texto original de los logs"));
            assertThat(rest.estado()).isEqualTo(200);
            assertThat(bitacoraDe(idDe(adm))).anyMatch(b -> b.startsWith("actualizacion|correo_plantilla_restaurada|") && b.contains("reporte_logs"));
            assertThat(n.get(ruta).json().path("editada").asBoolean()).isFalse();
            assertThat(n.post("/api/logs/enviar", Map.of("para", "evaluador@example.com", "usuarioId", idDe(adm))).estado()).isEqualTo(200);
            assertThat(ultimoCorreoA("evaluador@example.com").html()).contains("Adjunto el reporte de logs de auditoría generado en la Suite DIGSA.");
            // Nada se borra: quedan las dos versiones
            assertThat(jdbc.sql("SELECT count(*) FROM correo_plantillas_versiones WHERE clave = 'reporte_logs'").query(Long.class).single())
                    .isGreaterThanOrEqualTo(2);
        } finally {
            if (n.get(ruta).json().path("editada").asBoolean()) {
                n.post(ruta + "/restaurar", Map.of("motivo", "Limpieza de la prueba de plantillas"));
            }
        }
    }

    private static String texto(JsonNode arreglo) {
        return String.join(" ", StreamSupport.stream(arreglo.spliterator(), false).map(JsonNode::asText).toList());
    }
}
