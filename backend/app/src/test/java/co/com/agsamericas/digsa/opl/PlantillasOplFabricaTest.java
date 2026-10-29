package co.com.agsamericas.digsa.opl;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.Plantilla;
import co.com.agsamericas.digsa.plataforma.PlantillaCorreo;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Módulo Correos, fase C2 (precisión 3): las plantillas de fábrica del OPL dan el mismo asunto y html que el código anterior (copiado
 * aquí literal). Los valores llevan caracteres que se escapan.
 */
class PlantillasOplFabricaTest {

    private static final String NUMERO = "VS-<1>";
    private static final String PUNTO = "ESM & Uno";

    private static void aviso(Plantilla p, Map<String, String> otros, String asuntoAntes, String cuerpoAntes) {
        Map<String, String> v = new HashMap<>(otros);
        v.put("numero", NUMERO);
        v.put("punto", PUNTO);
        Plantilla.Renderizada r = p.render(v);
        assertThat(r.asunto()).as(p.clave()).isEqualTo(asuntoAntes);
        assertThat(r.html()).as(p.clave()).isEqualTo(PlantillaCorreo.marco("Visita " + NUMERO + " · " + PUNTO, "<p>" + PlantillaCorreo.texto(cuerpoAntes) + "</p>"));
    }

    @Test
    void avisos() {
        String clave = "H-2";
        String m = "Soporte <ilegible> & vencido";
        aviso(PlantillasOpl.OBJECION, Map.of("hallazgo", clave), "Objeción del OPL en la visita " + NUMERO, "El operador logístico objetó el hallazgo " + clave
                + " de la visita " + NUMERO + ". Revíselo en la Suite DIGSA y ratifíquelo o levántelo.");
        aviso(PlantillasOpl.PLAN_FIRMADO, Map.of(), "Plan de mejoramiento firmado · visita " + NUMERO, "El operador logístico firmó el plan de mejoramiento de la visita "
                + NUMERO + ".");
        for (String resolucion : new String[] {"ratifica", "levanta"}) {
            aviso("ratifica".equals(resolucion) ? PlantillasOpl.RATIFICADO : PlantillasOpl.LEVANTADO, Map.of("hallazgo", clave, "motivo", m),
                    "ratifica".equals(resolucion) ? "Hallazgo ratificado · visita " + NUMERO : "Hallazgo levantado · visita " + NUMERO,
                    "El auditor líder " + ("ratifica".equals(resolucion) ? "ratificó" : "levantó") + " el hallazgo " + clave + " de la visita " + NUMERO
                            + ". Motivo: " + m + ("ratifica".equals(resolucion) ? "\n\nDiligencie quién y cómo de este hallazgo en el plan de mejoramiento." : ""));
        }
        aviso(PlantillasOpl.CERRADO_LEVANTADOS, Map.of(), "Plan de mejoramiento cerrado · visita " + NUMERO, "Se levantaron todos los hallazgos de la visita "
                + NUMERO + ": el plan de mejoramiento queda cerrado.");
        aviso(PlantillasOpl.CERRADO_SEGUIMIENTO, Map.of("seguimiento", "VS-2"), "Plan de mejoramiento cerrado · visita " + NUMERO, "El seguimiento " + "VS-2"
                + " verificó que todas las acciones del plan de mejoramiento de la visita " + NUMERO + " quedaron cerradas: el plan queda cerrado.");
        aviso(PlantillasOpl.CERRADO, Map.of(), "Plan de mejoramiento cerrado · visita " + NUMERO, "El plan de mejoramiento de la visita " + NUMERO + " quedó cerrado.");
        aviso(PlantillasOpl.ACEPTACION_AUTOMATICA, Map.of("cantidad", "2", "dias", "5"), "Aceptación automática de hallazgos · visita " + NUMERO, 2
                + " hallazgo(s) de la visita " + NUMERO + " quedaron aceptados automáticamente: el operador logístico no los objetó en los " + 5 + " días hábiles.");
    }

    @Test
    void codigoNoEditableIdentico() {
        for (String codigo : new String[] {"123456", PlantillaCorreo.OCULTO}) {
            assertThat(ServicioOplPublico.htmlCodigo(NUMERO, codigo)).isEqualTo(PlantillaCorreo.marco("Código de verificación",
                    "<p>Su código para responder el acta de la visita " + PlantillaCorreo.e(NUMERO) + " es:</p><p style=\"font-size:24px;letter-spacing:4px\"><strong>"
                            + codigo + "</strong></p><p>Vence en " + ServicioOplPublico.MINUTOS_CODIGO + " minutos. Si usted no lo pidió, ignore este correo.</p>"));
        }
        assertThat(PlantillasOpl.CODIGO.render(Map.of("numero", NUMERO)).asunto()).isEqualTo("Código de verificación · visita " + NUMERO);
        assertThat(PlantillasOpl.CODIGO.editable()).isFalse();
    }

    @Test
    void reporteDigsa() {
        String filas = "<tr><td>H-1</td><td>A &amp; B</td><td>Aceptado</td><td>0</td></tr>";
        Map<String, String> v = new HashMap<>();
        v.put("consecutivo", "OPL-2026-0001");
        v.put("motivo", "Plan <sin> firmar");
        v.put("numero", NUMERO);
        v.put("punto", PUNTO);
        v.put("falto", "No firmó & no objetó.");
        v.put("acta_enviada", "2026-11-03");
        v.put("vence_objeciones", "2026-11-10");
        v.put("vence_plan", "2026-11-17");
        v.put("hallazgos", filas);
        v.put("huella", "ab12");
        Plantilla.Renderizada r = PlantillasOpl.REPORTE_DIGSA.render(v);
        assertThat(r.asunto()).isEqualTo("Reporte N° " + "OPL-2026-0001" + " · " + "Plan <sin> firmar" + " · visita " + NUMERO);
        assertThat(r.html()).isEqualTo(PlantillaCorreo.marco("Reporte N° " + "OPL-2026-0001" + " · " + "Plan <sin> firmar",
                "<p>" + PlantillaCorreo.e("No firmó & no objetó.") + " Visita <strong>" + PlantillaCorreo.e(NUMERO) + "</strong> ("
                        + PlantillaCorreo.e(PUNTO) + ").</p>"
                        + "<p>Acta enviada: " + PlantillaCorreo.e("2026-11-03") + " · plazo para objetar: "
                        + PlantillaCorreo.e("2026-11-10") + " · plazo del plan: " + PlantillaCorreo.e("2026-11-17")
                        + "</p><table border=\"1\" cellpadding=\"4\" style=\"border-collapse:collapse\"><tr><th>Hallazgo</th><th>Descripción</th><th>Estado</th>"
                        + "<th>Soportes del OPL</th></tr>" + filas + "</table>"
                        + "<p style=\"color:#6b7280;font-size:11px\">Huella SHA-256 del acta adjunta: " + PlantillaCorreo.e("ab12")
                        + "</p><p>Este reporte es informativo para la DIGSA; la aplicación no inicia ninguna multa.</p>"));
    }

    @Test
    void elTextoDeFabricaPasaSuPropiaValidacion() {
        for (Plantilla p : new PlantillasOpl().plantillas()) {
            if (p.editable()) {
                assertThat(p.validar(p.asunto(), p.cuerpo())).as(p.clave()).isEmpty();
            }
        }
    }
}
