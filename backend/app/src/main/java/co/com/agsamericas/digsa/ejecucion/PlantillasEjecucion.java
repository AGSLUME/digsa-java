package co.com.agsamericas.digsa.ejecucion;

import co.com.agsamericas.digsa.plataforma.Plantilla;
import co.com.agsamericas.digsa.plataforma.Plantilla.Variable;
import co.com.agsamericas.digsa.plataforma.PlantillaCorreo;
import co.com.agsamericas.digsa.plataforma.ProveedorPlantillas;
import java.util.List;
import org.springframework.stereotype.Component;

/** Plantillas de correo de la ejecución de visitas: acta y seguimientos (módulo Correos, fase C2). */
@Component
class PlantillasEjecucion implements ProveedorPlantillas {

    private static final String TABLA = "<table border=\"1\" cellpadding=\"4\" style=\"border-collapse:collapse\">";

    static final Plantilla ACTA = new Plantilla("acta", "Acta de visita", "Ejecución", Plantilla.Formato.HTML, true, "{{tipo_acta}}",
            "{{tipo_acta}} {{numero}} · {{punto}}{{complemento}}",
            "<p>Adjuntamos el acta de la visita <strong>{{numero}}</strong>{{ronda}}.</p>{{hallazgos}}"
                    + "<p style=\"color:#6b7280;font-size:11px\">Huella SHA-256 del acta: {{huella}}</p>",
            List.of(Variable.obligatoria("tipo_acta", "«Acta de visita» o «Acta de seguimiento a plan de mejoramiento»", "Acta de visita"),
                    Variable.obligatoria("numero", "Número de la visita", "VS-2026-0001"),
                    Variable.obligatoria("punto", "Punto visitado", "ESM Ejemplo (ficticio)"),
                    Variable.de("complemento", "En un seguimiento, « · 1.ª visita de seguimiento de la visita …»; si se reabrió, « · ronda N»", ""),
                    Variable.de("ronda", "« (ronda N, visita reabierta)» cuando la visita se reabrió, o vacío", ""),
                    Variable.bloque("hallazgos", "Plazos y enlace para que el OPL responda los hallazgos (en la bitácora el enlace queda oculto), "
                            + "o «La visita no tiene hallazgos.»", "<p style=\"border:2px solid #961414;padding:10px;color:#961414\"><strong>Tiene 5 días "
                            + "hábiles para objetar.</strong></p><p>Responda los hallazgos y firme el plan en: <a href=\"" + PlantillaCorreo.ENLACE_OCULTO
                            + "\">" + PlantillaCorreo.ENLACE_OCULTO + "</a></p>"),
                    Variable.obligatoria("huella", "Huella SHA-256 del PDF del acta", "3f5a…e9")));

    static final String SIN_HALLAZGOS = "<p>La visita no tiene hallazgos.</p>";

    /** Bloque de los hallazgos con el enlace de respuesta del OPL (o el enlace oculto, para la bitácora). */
    static String hallazgos(String textoPlazos, String url) {
        return "<p style=\"border:2px solid #961414;padding:10px;color:#961414\"><strong>" + PlantillaCorreo.e(textoPlazos)
                + "</strong></p><p>Responda los hallazgos y firme el plan en: <a href=\"" + PlantillaCorreo.e(url) + "\">" + PlantillaCorreo.e(url) + "</a></p>";
    }

    static final Plantilla SEGUIMIENTO_AVISO = new Plantilla("seguimiento_aviso", "Aviso de visita de seguimiento", "Ejecución", Plantilla.Formato.HTML,
            true, "{{ordinal}} visita de seguimiento · {{numero}}", "Visita de seguimiento {{numero}} · {{punto}}",
            "<p>Se programó la visita de seguimiento <strong>{{numero}}</strong> al plan de mejoramiento de la visita {{origen}}, para el "
                    + "<strong>{{fecha}}</strong> en {{punto}}.</p><p>Acciones por verificar:</p>" + TABLA
                    + "<tr><th>Hallazgo</th><th>Descripción</th><th>Fin del plan</th></tr>{{acciones}}</table>",
            List.of(Variable.obligatoria("ordinal", "Primera, Segunda…", "Primera"),
                    Variable.obligatoria("numero", "Número de la visita de seguimiento", "VS-2026-0002"),
                    Variable.obligatoria("punto", "Punto visitado", "ESM Ejemplo (ficticio)"),
                    Variable.obligatoria("origen", "Número de la visita del plan de mejoramiento", "VS-2026-0001"),
                    Variable.obligatoria("fecha", "Fecha del seguimiento", "lunes 1 de diciembre de 2026"),
                    Variable.bloque("acciones", "Filas de la tabla: hallazgo, descripción y fin del plan",
                            "<tr><td>H-1</td><td>Medicamentos vencidos en el área de dispensación</td><td>2026-11-30</td></tr>")));

    static final Plantilla SEGUIMIENTO_ESCALAMIENTO = new Plantilla("seguimiento_escalamiento", "Plan de mejoramiento sin cerrar", "Ejecución",
            Plantilla.Formato.HTML, true, "Plan de mejoramiento sin cerrar · {{numero}}", "Plan de mejoramiento sin cerrar · visita {{numero}}",
            "<p>La visita <strong>{{numero}}</strong> ({{punto}}) llegó al máximo de {{maximo}} visitas de seguimiento con acciones abiertas del plan "
                    + "de mejoramiento:</p><ul>{{acciones}}</ul><p>No se programa otro seguimiento. El reporte formal a la DIGSA sale por separado con "
                    + "su consecutivo.</p>",
            List.of(Variable.obligatoria("numero", "Número de la visita", "VS-2026-0001"),
                    Variable.obligatoria("punto", "Punto visitado", "ESM Ejemplo (ficticio)"),
                    Variable.obligatoria("maximo", "Máximo de visitas de seguimiento", "3"),
                    Variable.bloque("acciones", "Lista de las acciones abiertas", "<li>H-1: Medicamentos vencidos en el área de dispensación</li>")));

    static final Plantilla RECORDATORIO_SEGUIMIENTO = new Plantilla("recordatorio_seguimiento", "Recordatorio de visita de seguimiento", "Ejecución",
            Plantilla.Formato.HTML, true, "{{ordinal}} visita de seguimiento · {{numero}}", "Recordatorio: {{ordinal}} visita de seguimiento · {{numero}}",
            "<p>Le recordamos la {{ordinal_minuscula}} visita de seguimiento al plan de mejoramiento de la visita {{origen}}: <strong>{{numero}}</strong>, "
                    + "el {{fecha}}{{hora}} en {{punto}}.</p><p>Acciones abiertas por verificar:</p><ul>{{acciones}}</ul>",
            List.of(Variable.obligatoria("ordinal", "Primera, Segunda…", "Primera"),
                    Variable.de("ordinal_minuscula", "primera, segunda…", "primera"),
                    Variable.obligatoria("numero", "Número de la visita de seguimiento", "VS-2026-0002"),
                    Variable.obligatoria("origen", "Número de la visita del plan de mejoramiento", "VS-2026-0001"),
                    Variable.obligatoria("fecha", "Fecha del seguimiento", "lunes 1 de diciembre de 2026"),
                    Variable.de("hora", "« a las HH:MM» si tiene hora, o vacío", " a las 08:00"),
                    Variable.obligatoria("punto", "Punto visitado", "ESM Ejemplo (ficticio)"),
                    Variable.bloque("acciones", "Lista de las acciones abiertas", "<li>H-1: Medicamentos vencidos (fin del plan 2026-11-30)</li>")));

    @Override
    public List<Plantilla> plantillas() {
        return List.of(ACTA, SEGUIMIENTO_AVISO, SEGUIMIENTO_ESCALAMIENTO, RECORDATORIO_SEGUIMIENTO);
    }
}
