package co.com.agsamericas.digsa.opl;

import co.com.agsamericas.digsa.plataforma.Plantilla;
import co.com.agsamericas.digsa.plataforma.Plantilla.Variable;
import co.com.agsamericas.digsa.plataforma.PlantillaCorreo;
import co.com.agsamericas.digsa.plataforma.ProveedorPlantillas;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Plantillas de correo del OPL (módulo Correos, fase C2): los avisos al OPL y al auditor líder (todos salen con el origen opl_aviso), el
 * código de verificación para responder el acta (no editable: es de un solo uso) y el reporte formal a la DIGSA.
 */
@Component
class PlantillasOpl implements ProveedorPlantillas {

    private static final String TITULO_AVISO = "Visita {{numero}} · {{punto}}";
    private static final Variable NUMERO = Variable.obligatoria("numero", "Número de la visita", "VS-2026-0001");
    private static final Variable PUNTO = Variable.de("punto", "Punto visitado", "ESM Ejemplo (ficticio)");
    private static final Variable HALLAZGO = Variable.obligatoria("hallazgo", "Código del hallazgo", "H-2");
    private static final Variable MOTIVO = Variable.obligatoria("motivo", "Motivo que escribió el auditor líder", "El soporte no corresponde al lote.");

    private static Plantilla aviso(String clave, String nombre, String asunto, String cuerpo, Variable... otras) {
        List<Variable> v = new ArrayList<>(List.of(NUMERO, PUNTO));
        v.addAll(List.of(otras));
        return new Plantilla(clave, nombre, "OPL", Plantilla.Formato.TEXTO, true, TITULO_AVISO, asunto, cuerpo, v);
    }

    static final Plantilla OBJECION = aviso("opl_objecion", "Aviso al líder: objeción del OPL", "Objeción del OPL en la visita {{numero}}",
            "El operador logístico objetó el hallazgo {{hallazgo}} de la visita {{numero}}. Revíselo en la Suite DIGSA y ratifíquelo o levántelo.", HALLAZGO);

    static final Plantilla PLAN_FIRMADO = aviso("opl_plan_firmado", "Aviso al líder: plan de mejoramiento firmado",
            "Plan de mejoramiento firmado · visita {{numero}}", "El operador logístico firmó el plan de mejoramiento de la visita {{numero}}.");

    static final Plantilla RATIFICADO = aviso("opl_hallazgo_ratificado", "Aviso al OPL: hallazgo ratificado", "Hallazgo ratificado · visita {{numero}}",
            "El auditor líder ratificó el hallazgo {{hallazgo}} de la visita {{numero}}. Motivo: {{motivo}}\n\n"
                    + "Diligencie quién y cómo de este hallazgo en el plan de mejoramiento.", HALLAZGO, MOTIVO);

    static final Plantilla LEVANTADO = aviso("opl_hallazgo_levantado", "Aviso al OPL: hallazgo levantado", "Hallazgo levantado · visita {{numero}}",
            "El auditor líder levantó el hallazgo {{hallazgo}} de la visita {{numero}}. Motivo: {{motivo}}", HALLAZGO, MOTIVO);

    static final Plantilla CERRADO_LEVANTADOS = aviso("opl_plan_cerrado_levantados", "Aviso al OPL: plan cerrado (hallazgos levantados)",
            "Plan de mejoramiento cerrado · visita {{numero}}", "Se levantaron todos los hallazgos de la visita {{numero}}: el plan de mejoramiento queda cerrado.");

    static final Plantilla CERRADO_SEGUIMIENTO = aviso("opl_plan_cerrado_seguimiento", "Aviso al OPL: plan cerrado por el seguimiento",
            "Plan de mejoramiento cerrado · visita {{numero}}", "El seguimiento {{seguimiento}} verificó que todas las acciones del plan de mejoramiento de "
                    + "la visita {{numero}} quedaron cerradas: el plan queda cerrado.",
            Variable.obligatoria("seguimiento", "Número de la visita de seguimiento", "VS-2026-0002"));

    static final Plantilla CERRADO = aviso("opl_plan_cerrado", "Aviso al OPL: plan cerrado", "Plan de mejoramiento cerrado · visita {{numero}}",
            "El plan de mejoramiento de la visita {{numero}} quedó cerrado.");

    static final Plantilla ACEPTACION_AUTOMATICA = aviso("opl_aceptacion_automatica", "Aviso al líder: aceptación automática de hallazgos",
            "Aceptación automática de hallazgos · visita {{numero}}", "{{cantidad}} hallazgo(s) de la visita {{numero}} quedaron aceptados automáticamente: "
                    + "el operador logístico no los objetó en los {{dias}} días hábiles.",
            Variable.obligatoria("cantidad", "Hallazgos aceptados automáticamente", "2"), Variable.obligatoria("dias", "Días hábiles para objetar", "5"));

    static final Plantilla CODIGO = new Plantilla("opl_codigo", "Código de verificación del OPL (QR)", "OPL", Plantilla.Formato.HTML, false,
            "Código de verificación", "Código de verificación · visita {{numero}}",
            "<p>Su código para responder el acta de la visita {{numero}} es:</p><p style=\"font-size:24px;letter-spacing:4px\"><strong>{{codigo}}</strong></p>"
                    + "<p>Vence en {{minutos}} minutos. Si usted no lo pidió, ignore este correo.</p>",
            List.of(NUMERO, Variable.obligatoria("codigo", "Código de un solo uso (en la bitácora queda oculto)", PlantillaCorreo.OCULTO),
                    Variable.obligatoria("minutos", "Minutos de vigencia del código", "10")));

    static final Plantilla REPORTE_DIGSA = new Plantilla("opl_reporte_digsa", "Reporte formal a la DIGSA", "OPL", Plantilla.Formato.HTML, true,
            "Reporte N° {{consecutivo}} · {{motivo}}", "Reporte N° {{consecutivo}} · {{motivo}} · visita {{numero}}",
            "<p>{{falto}} Visita <strong>{{numero}}</strong> ({{punto}}).</p><p>Acta enviada: {{acta_enviada}} · plazo para objetar: {{vence_objeciones}} · "
                    + "plazo del plan: {{vence_plan}}</p><table border=\"1\" cellpadding=\"4\" style=\"border-collapse:collapse\"><tr><th>Hallazgo</th>"
                    + "<th>Descripción</th><th>Estado</th><th>Soportes del OPL</th></tr>{{hallazgos}}</table><p style=\"color:#6b7280;font-size:11px\">"
                    + "Huella SHA-256 del acta adjunta: {{huella}}</p><p>Este reporte es informativo para la DIGSA; la aplicación no inicia ninguna multa.</p>",
            List.of(Variable.obligatoria("consecutivo", "Consecutivo del reporte", "OPL-2026-0001"),
                    Variable.obligatoria("motivo", "Motivo del reporte", "Plan de mejoramiento sin firmar"),
                    NUMERO, Variable.obligatoria("punto", "Punto visitado", "ESM Ejemplo (ficticio)"),
                    Variable.obligatoria("falto", "Qué faltó del OPL", "El operador logístico no firmó el plan de mejoramiento en el plazo."),
                    Variable.obligatoria("acta_enviada", "Fecha de envío del acta", "2026-11-03"),
                    Variable.obligatoria("vence_objeciones", "Plazo para objetar", "2026-11-10"),
                    Variable.obligatoria("vence_plan", "Plazo del plan", "2026-11-17"),
                    Variable.bloque("hallazgos", "Filas de la tabla: hallazgo, descripción, estado y soportes",
                            "<tr><td>H-1</td><td>Medicamentos vencidos</td><td>Aceptado</td><td>0</td></tr>"),
                    Variable.obligatoria("huella", "Huella SHA-256 del acta adjunta", "3f5a…e9")));

    @Override
    public List<Plantilla> plantillas() {
        return List.of(OBJECION, PLAN_FIRMADO, RATIFICADO, LEVANTADO, CERRADO_LEVANTADOS, CERRADO_SEGUIMIENTO, CERRADO, ACEPTACION_AUTOMATICA, CODIGO,
                REPORTE_DIGSA);
    }
}
