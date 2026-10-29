package co.com.agsamericas.digsa.motorreportes;

import co.com.agsamericas.digsa.plataforma.Plantilla;
import co.com.agsamericas.digsa.plataforma.Plantilla.Variable;
import co.com.agsamericas.digsa.plataforma.ProveedorPlantillas;
import java.util.List;
import org.springframework.stereotype.Component;

/** Plantilla del reporte del motor enviado por correo (módulo Correos, fase C2). */
@Component
class PlantillasReportes implements ProveedorPlantillas {

    static final Plantilla REPORTE = new Plantilla("reporte_motor", "Reporte enviado por correo", "Reportes", Plantilla.Formato.HTML, true, "{{titulo}}",
            "{{titulo}} · Suite DIGSA",
            "<p>Adjunto el reporte generado en la Suite DIGSA.</p><p>Archivo: {{archivo}}</p><p>Código de verificación: <strong>{{codigo}}</strong></p>",
            List.of(Variable.obligatoria("titulo", "Nombre del reporte", "Detalle de visitas"),
                    Variable.obligatoria("archivo", "Nombre del archivo adjunto", "detalle_visitas_2026-11-03.xlsx"),
                    Variable.obligatoria("codigo", "Código de verificación del reporte", "RPT-20261103-AB12CD34")));

    @Override
    public List<Plantilla> plantillas() {
        return List.of(REPORTE);
    }
}
