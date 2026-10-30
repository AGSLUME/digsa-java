package co.com.agsamericas.digsa.correos;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Nombre visible de cada tipo de correo (la columna {@code origen} de correos_salida). Los tipos con familia (alerta:tipo,
 * presupuesto:tipo) se nombran por la familia y el tipo. Un origen que no está aquí se muestra tal cual.
 */
final class TiposCorreo {

    static final Map<String, String> NOMBRES = new LinkedHashMap<>();

    static {
        // Encolados (bandeja de salida)
        NOMBRES.put("solicitud_nueva", "Solicitud de visita a necesidad");
        NOMBRES.put("solicitud_agendada", "Visita a necesidad agendada");
        NOMBRES.put("solicitud_cancelada", "Visita a necesidad cancelada");
        NOMBRES.put("recordatorio_seguimiento", "Recordatorio de visita de seguimiento");
        // Directos (EnvioRegistrado)
        NOMBRES.put("mfa_codigo", "Código de verificación (MFA)");
        NOMBRES.put("clave_enlace", "Enlace para definir la contraseña");
        NOMBRES.put("opl_codigo", "Código de verificación del OPL (QR)");
        NOMBRES.put("notificacion_previa", "Notificación previa de visita");
        NOMBRES.put("acta", "Acta de visita");
        NOMBRES.put("seguimiento_aviso", "Aviso de visita de seguimiento");
        NOMBRES.put("seguimiento_escalamiento", "Plan de mejoramiento sin cerrar");
        NOMBRES.put("opl_aviso", "Aviso de la respuesta del OPL");
        NOMBRES.put("opl_reporte_digsa", "Reporte formal a la DIGSA");
        NOMBRES.put("reporte_motor", "Reporte enviado por correo");
        NOMBRES.put("reporte_presupuesto", "Reporte de presupuesto enviado por correo");
        NOMBRES.put("reporte_logs", "Logs de auditoría enviados por correo");
    }

    private TiposCorreo() {
    }

    static String nombre(String origen) {
        if (origen == null) {
            return "";
        }
        String n = NOMBRES.get(origen);
        if (n != null) {
            return n;
        }
        if (origen.startsWith("alerta:")) {
            return "Alerta · " + origen.substring(7);
        }
        if (origen.startsWith("presupuesto:")) {
            return "Notificación presupuestal · " + origen.substring(12);
        }
        return origen;
    }
}
