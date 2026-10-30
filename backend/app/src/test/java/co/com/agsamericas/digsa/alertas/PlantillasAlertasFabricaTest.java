package co.com.agsamericas.digsa.alertas;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.Plantilla;
import co.com.agsamericas.digsa.plataforma.PlantillaCorreo;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Módulo Correos, fase C2 (precisión 3): la plantilla de fábrica de las alertas da el mismo correo que el código anterior. */
class PlantillasAlertasFabricaTest {

    @Test
    void criticaConSmsYAviso() {
        for (boolean critica : new boolean[] {true, false}) {
            String nombre = "Correo <sin> salir & más";
            String hora = "2026-11-03 08:15";
            String detalle = "Línea 1\nLínea <2>";
            String enlace = "https://suite.example.com/?a=1&b=2";
            String titulo = (critica ? "Alerta crítica · " : "Aviso · ") + nombre;
            Plantilla.Renderizada r = PlantillasAlertas.ALERTA.render(Map.of("titulo", titulo, "hora", hora, "alerta", nombre,
                    "detalle", PlantillaCorreo.texto(detalle), "aviso_sms", critica ? PlantillasAlertas.AVISO_SMS : "", "enlace", enlace));
            String cuerpo = "<p><strong>" + PlantillaCorreo.e(nombre) + "</strong> (" + PlantillaCorreo.e(hora) + ")</p>"
                    + "<p>" + PlantillaCorreo.texto(detalle) + "</p>"
                    + (critica ? "<p>Es una alerta crítica: también se envió un SMS a los destinatarios con móvil registrado.</p>" : "")
                    + "<p>Revise la Suite DIGSA: <a href=\"" + PlantillaCorreo.e(enlace) + "\">" + PlantillaCorreo.e(enlace) + "</a></p>";
            assertThat(r.asunto()).isEqualTo(titulo + " · " + hora);
            assertThat(r.html()).isEqualTo(PlantillaCorreo.marco(titulo, cuerpo));
        }
        assertThat(PlantillasAlertas.ALERTA.validar(PlantillasAlertas.ALERTA.asunto(), PlantillasAlertas.ALERTA.cuerpo())).isEmpty();
    }
}
