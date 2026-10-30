package co.com.agsamericas.digsa.ejecucion;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.Plantilla;
import co.com.agsamericas.digsa.plataforma.PlantillaCorreo;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Módulo Correos, fase C2 (precisión 3): las plantillas de fábrica del acta y de los seguimientos dan el mismo asunto y html que el
 * código anterior (copiado aquí literal). Los valores llevan caracteres que se escapan.
 */
class PlantillasEjecucionFabricaTest {

    private static final String PUNTO = "ESM <Uno> & Cía";

    @Test
    void acta() {
        String huella = "ab12cd";
        for (int ronda : new int[] {1, 2}) {
            for (String url : new String[] {null, "https://x.example.com/opl?tkn=a&b", PlantillaCorreo.ENLACE_OCULTO}) {
                for (boolean seguimiento : new boolean[] {false, true}) {
                    String plazos = "Tiene <5> días & más";
                    String complemento = (seguimiento ? " · Primera visita de seguimiento de la visita VS-1" : "") + (ronda > 1 ? " · ronda " + ronda : "");
                    String asuntoAntes = (seguimiento ? "Acta de seguimiento a plan de mejoramiento " : "Acta de visita ") + "VS-2" + " · " + PUNTO + complemento;
                    String antes = PlantillaCorreo.marco(seguimiento ? "Acta de seguimiento a plan de mejoramiento" : "Acta de visita",
                            "<p>Adjuntamos el acta de la visita <strong>" + PlantillaCorreo.e("VS-2")
                                    + "</strong>" + (ronda > 1 ? " (ronda " + ronda + ", visita reabierta)" : "") + ".</p>"
                                    + (url == null ? "<p>La visita no tiene hallazgos.</p>"
                                            : "<p style=\"border:2px solid #961414;padding:10px;color:#961414\"><strong>" + PlantillaCorreo.e(plazos)
                                                    + "</strong></p><p>Responda los hallazgos y firme el plan en: <a href=\"" + PlantillaCorreo.e(url) + "\">"
                                                    + PlantillaCorreo.e(url) + "</a></p>")
                                    + "<p style=\"color:#6b7280;font-size:11px\">Huella SHA-256 del acta: " + huella + "</p>");
                    Map<String, String> v = new HashMap<>();
                    v.put("tipo_acta", seguimiento ? "Acta de seguimiento a plan de mejoramiento" : "Acta de visita");
                    v.put("numero", "VS-2");
                    v.put("punto", PUNTO);
                    v.put("complemento", complemento);
                    v.put("ronda", ronda > 1 ? " (ronda " + ronda + ", visita reabierta)" : "");
                    v.put("hallazgos", url == null ? PlantillasEjecucion.SIN_HALLAZGOS : PlantillasEjecucion.hallazgos(plazos, url));
                    v.put("huella", huella);
                    Plantilla.Renderizada r = PlantillasEjecucion.ACTA.render(v);
                    assertThat(r.asunto()).isEqualTo(asuntoAntes);
                    assertThat(r.html()).isEqualTo(antes);
                }
            }
        }
    }

    @Test
    void avisoDeSeguimiento() {
        String filas = "<tr><td>H-1</td><td>A &amp; B</td><td></td></tr>";
        Plantilla.Renderizada r = PlantillasEjecucion.SEGUIMIENTO_AVISO.render(Map.of("ordinal", "Primera", "numero", "VS-<2>", "punto", PUNTO,
                "origen", "VS-1", "fecha", "lunes 1 de diciembre de 2026", "acciones", filas));
        assertThat(r.asunto()).isEqualTo("Visita de seguimiento VS-<2> · " + PUNTO);
        assertThat(r.html()).isEqualTo(PlantillaCorreo.marco("Primera" + " visita de seguimiento · " + "VS-<2>",
                "<p>Se programó la visita de seguimiento <strong>" + PlantillaCorreo.e("VS-<2>") + "</strong> al plan de mejoramiento de la visita "
                        + PlantillaCorreo.e("VS-1") + ", para el <strong>" + PlantillaCorreo.e("lunes 1 de diciembre de 2026") + "</strong> en "
                        + PlantillaCorreo.e(PUNTO) + ".</p><p>Acciones por verificar:</p><table border=\"1\" cellpadding=\"4\" style=\"border-collapse:collapse\">"
                        + "<tr><th>Hallazgo</th><th>Descripción</th><th>Fin del plan</th></tr>" + filas + "</table>"));
    }

    @Test
    void escalamiento() {
        String filas = "<li>H-1: A &amp; B</li>";
        Plantilla.Renderizada r = PlantillasEjecucion.SEGUIMIENTO_ESCALAMIENTO.render(Map.of("numero", "VS-<1>", "punto", PUNTO, "maximo", "3",
                "acciones", filas));
        assertThat(r.asunto()).isEqualTo("Plan de mejoramiento sin cerrar · visita VS-<1>");
        assertThat(r.html()).isEqualTo(PlantillaCorreo.marco("Plan de mejoramiento sin cerrar · " + "VS-<1>", "<p>La visita <strong>" + PlantillaCorreo.e("VS-<1>")
                + "</strong> (" + PlantillaCorreo.e(PUNTO) + ") llegó al máximo de " + 3 + " visitas de seguimiento con acciones abiertas del plan de mejoramiento:</p><ul>"
                + filas + "</ul><p>No se programa otro seguimiento. El reporte formal a la DIGSA sale por separado con su consecutivo.</p>"));
    }

    @Test
    void recordatorio() {
        String filas = "<li>H-1: A (fin del plan 2026-11-30)</li>";
        for (String hora : new String[] {"", " a las 08:00"}) {
            Map<String, String> v = new HashMap<>(Map.of("ordinal", "Segunda", "ordinal_minuscula", "segunda", "numero", "VS-<3>", "origen", "VS-1",
                    "fecha", "lunes 1 de diciembre de 2026", "hora", hora, "punto", PUNTO, "acciones", filas));
            Plantilla.Renderizada r = PlantillasEjecucion.RECORDATORIO_SEGUIMIENTO.render(v);
            String titulo = "Segunda" + " visita de seguimiento · " + "VS-<3>";
            assertThat(r.asunto()).isEqualTo("Recordatorio: " + titulo);
            assertThat(r.html()).isEqualTo(PlantillaCorreo.marco(titulo, "<p>Le recordamos la " + PlantillaCorreo.e("segunda")
                    + " visita de seguimiento al plan de mejoramiento de la visita " + PlantillaCorreo.e("VS-1") + ": <strong>" + PlantillaCorreo.e("VS-<3>")
                    + "</strong>, el " + PlantillaCorreo.e("lunes 1 de diciembre de 2026") + hora + " en "
                    + PlantillaCorreo.e(PUNTO) + ".</p><p>Acciones abiertas por verificar:</p><ul>" + filas + "</ul>"));
        }
    }

    @Test
    void elTextoDeFabricaPasaSuPropiaValidacion() {
        for (Plantilla p : new PlantillasEjecucion().plantillas()) {
            assertThat(p.validar(p.asunto(), p.cuerpo())).as(p.clave()).isEmpty();
        }
    }
}
