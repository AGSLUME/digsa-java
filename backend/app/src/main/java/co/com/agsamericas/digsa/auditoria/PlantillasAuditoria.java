package co.com.agsamericas.digsa.auditoria;

import co.com.agsamericas.digsa.plataforma.Plantilla;
import co.com.agsamericas.digsa.plataforma.Plantilla.Variable;
import co.com.agsamericas.digsa.plataforma.ProveedorPlantillas;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Plantilla de los logs enviados por correo (módulo Correos, fase C2). Sale sin el marco común, como antes; el nombre del archivo va
 * escapado como antes (por eso es un bloque).
 */
@Component
class PlantillasAuditoria implements ProveedorPlantillas {

    static final Plantilla LOGS = new Plantilla("reporte_logs", "Logs de auditoría enviados por correo", "Auditoría", Plantilla.Formato.SIN_MARCO, true,
            null, "Reporte de logs de auditoría · Suite DIGSA",
            "<p>Adjunto el reporte de logs de auditoría generado en la Suite DIGSA.</p><p>Archivo: {{archivo}}</p>",
            List.of(Variable.bloque("archivo", "Nombre del archivo adjunto", "logs_2026-11-03.xlsx")));

    @Override
    public List<Plantilla> plantillas() {
        return List.of(LOGS);
    }
}
