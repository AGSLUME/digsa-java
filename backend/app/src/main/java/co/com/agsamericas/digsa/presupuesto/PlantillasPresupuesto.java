package co.com.agsamericas.digsa.presupuesto;

import co.com.agsamericas.digsa.plataforma.Plantilla;
import co.com.agsamericas.digsa.plataforma.Plantilla.Variable;
import co.com.agsamericas.digsa.plataforma.ProveedorPlantillas;
import java.util.List;
import org.springframework.stereotype.Component;

/** Plantillas de correo del presupuesto (módulo Correos, fase C2). */
@Component
class PlantillasPresupuesto implements ProveedorPlantillas {

    static final Plantilla REPORTE = new Plantilla("reporte_presupuesto", "Reporte de presupuesto enviado por correo", "Presupuesto",
            Plantilla.Formato.HTML, true, "{{titulo}}", "{{titulo}} · Suite DIGSA",
            "<p>Adjunto el reporte generado en la Suite DIGSA.</p><p>Archivo: {{archivo}}</p>",
            List.of(Variable.obligatoria("titulo", "Nombre del reporte", "Ejecución presupuestal por línea"),
                    Variable.obligatoria("archivo", "Nombre del archivo adjunto", "ejecucion_2026-11-03.xlsx")));

    static final Plantilla NOTIFICACION = new Plantilla("presupuesto_notificacion", "Notificación presupuestal", "Presupuesto", Plantilla.Formato.HTML,
            true, "Notificación presupuestal {{consecutivo}}", "{{consecutivo}} · {{asunto}}",
            "<p>La interventoría informa a la DIGSA y a la Dirección de Sanidad o Jefatura de Salud correspondiente, en cumplimiento del Anexo 2 "
                    + "(auditoría de facturación), la siguiente novedad de la ejecución presupuestal del contrato de medicamentos:</p>"
                    + "<table style=\"border-collapse:collapse\">{{datos}}</table>"
                    + "<p>Consulte el detalle en la Suite DIGSA: <a href=\"{{enlace}}\">{{enlace}}</a></p><p>Generado por la interventoría · Suite DIGSA.</p>",
            List.of(Variable.obligatoria("consecutivo", "Consecutivo de la notificación", "PRE-2026-0001"),
                    Variable.obligatoria("asunto", "Novedad según el tipo de aviso (umbral, línea agotada…)", "Línea al 90 % de ejecución"),
                    Variable.bloque("datos", "Filas de la tabla con los datos de la novedad",
                            "<tr><th style=\"text-align:left;padding:2px 8px\">Línea</th><td style=\"padding:2px 8px\">Medicamentos (ficticia)</td></tr>"),
                    Variable.obligatoria("enlace", "Enlace al presupuesto en la Suite DIGSA", "https://suite.example.com/presupuesto")));

    @Override
    public List<Plantilla> plantillas() {
        return List.of(REPORTE, NOTIFICACION);
    }
}
