package co.com.agsamericas.digsa.agenda;

import co.com.agsamericas.digsa.plataforma.Plantilla;
import co.com.agsamericas.digsa.plataforma.Plantilla.Variable;
import co.com.agsamericas.digsa.plataforma.ProveedorPlantillas;
import java.util.List;
import org.springframework.stereotype.Component;

/** Plantillas de correo de la agenda (módulo Correos, fase C2): editables por el administrador. */
@Component
class PlantillasAgenda implements ProveedorPlantillas {

    /** Texto plano: es el cuerpo que la agenda muestra y que el coordinador puede ajustar antes de notificar a mano. */
    static final Plantilla NOTIFICACION_PREVIA = new Plantilla("notificacion_previa", "Notificación previa de visita", "Agenda", Plantilla.Formato.TEXTO,
            true, "Notificación de visita de seguimiento", "Notificación de visita de seguimiento {{numero}} · {{punto}}{{sufijo}}", """
            Número de la visita: {{numero}}
            Fecha: {{fecha}}
            Tipo de visita: {{tipo}}
            Unidad de intervención: {{unidad}}
            Municipio: {{municipio}}
            Dirección: {{direccion}}

            Alcance específico:
            {{alcance}}

            Equipo auditor:
            {{equipo}}
            Los datos del equipo auditor se comparten solo para el control de ingreso al establecimiento (Ley 1581 de 2012); \
            no se pueden usar para otro fin.

            Interventoría DIGSA""",
            List.of(Variable.obligatoria("numero", "Número de la visita", "VS-2026-0001"),
                    Variable.obligatoria("punto", "Punto o unidad visitada", "ESM Ejemplo (ficticio)"),
                    Variable.de("sufijo", "Complemento del asunto en una reprogramación (p. ej. « · reprogramada»), o vacío", ""),
                    Variable.obligatoria("fecha", "Fecha y hora de la visita", "lunes 3 de noviembre de 2026, 08:00 h"),
                    Variable.obligatoria("tipo", "Tipo de visita", "Seguimiento"),
                    Variable.obligatoria("unidad", "Unidad de intervención y punto", "ESM · ESM Ejemplo (ficticio) (Dispensación)"),
                    Variable.obligatoria("municipio", "Municipio y departamento", "Bogotá D.C., Bogotá D.C."),
                    Variable.obligatoria("direccion", "Dirección del punto", "Calle 1 # 2-3"),
                    Variable.obligatoria("alcance", "Alcance específico de la visita", "Revisión del inventario y la dispensación."),
                    Variable.obligatoria("equipo", "Equipo auditor, una línea por persona (nombre, rol, correo y documento)",
                            "- Auditor Ficticio · Químico farmacéutico (auditor líder) · auditor@example.com · documento 0000000\n")));

    static final Plantilla SOLICITUD_NUEVA = new Plantilla("solicitud_nueva", "Solicitud de visita a necesidad", "Agenda", Plantilla.Formato.TEXTO, true,
            "Solicitud de visita a necesidad", "Solicitud de visita a necesidad N.º {{id}} · {{punto}}", """
            Nueva solicitud de visita a necesidad N.º {{id}}

            Entidad: {{entidad}}
            Punto: {{punto}}
            Fecha del escrito: {{fecha}}
            Plazo máximo para la visita: {{limite}} (5 días hábiles, Anexo 3 p.14)

            Motivo:
            {{motivo}}

            Agéndela en la Suite DIGSA › Solicitudes de visita.""",
            List.of(Variable.obligatoria("id", "Número de la solicitud", "15"),
                    Variable.obligatoria("entidad", "Entidad que solicita", "Dirección de Sanidad (ficticia)"),
                    Variable.obligatoria("punto", "Punto que se pide visitar", "ESM Ejemplo (ficticio)"),
                    Variable.obligatoria("fecha", "Fecha del escrito", "lunes 3 de noviembre de 2026"),
                    Variable.obligatoria("limite", "Plazo máximo para la visita", "lunes 10 de noviembre de 2026"),
                    Variable.obligatoria("motivo", "Motivo de la solicitud (máximo 600 caracteres)", "Quejas por entregas incompletas.")));

    static final Plantilla SOLICITUD_AGENDADA = new Plantilla("solicitud_agendada", "Visita a necesidad agendada", "Agenda", Plantilla.Formato.TEXTO,
            true, "Visita a necesidad agendada", "Visita a necesidad agendada {{numero}} · {{punto}}", """
            Cordial saludo, {{nombre}}:

            La solicitud de visita a necesidad de {{entidad}} del {{fecha_escrito}} quedó agendada:

            - Visita N.º: {{numero}}
            - Punto: {{punto}}
            - Fecha: {{fecha}}

            La visita a necesidad se hace sin previo aviso al operador logístico (Anexo 3 p.14): por favor no comparta esta \
            información con el OPL ni con el punto.

            Interventoría DIGSA""",
            List.of(Variable.de("nombre", "Nombre de quien solicitó", "Ana Pérez (ficticia)"),
                    Variable.obligatoria("entidad", "Entidad que solicitó", "Dirección de Sanidad (ficticia)"),
                    Variable.obligatoria("fecha_escrito", "Fecha del escrito", "lunes 3 de noviembre de 2026"),
                    Variable.obligatoria("numero", "Número de la visita", "VN-2026-0001"),
                    Variable.obligatoria("punto", "Punto visitado", "ESM Ejemplo (ficticio)"),
                    Variable.obligatoria("fecha", "Fecha y hora de la visita", "jueves 6 de noviembre de 2026, 08:00 h")));

    static final Plantilla SOLICITUD_CANCELADA = new Plantilla("solicitud_cancelada", "Visita a necesidad cancelada", "Agenda", Plantilla.Formato.TEXTO,
            true, "Visita a necesidad cancelada", "Visita a necesidad cancelada {{numero}} · {{punto}}", """
            Cordial saludo, {{nombre}}:

            La solicitud de visita a necesidad de {{entidad}} del {{fecha_escrito}} tenía agendada la visita {{numero}}, que fue cancelada.
            Motivo: {{motivo}}

            La solicitud vuelve a quedar pendiente para que el coordinador la reprograme; su plazo se sigue contando desde la fecha del \
            escrito.

            Interventoría DIGSA""",
            List.of(Variable.de("nombre", "Nombre de quien solicitó", "Ana Pérez (ficticia)"),
                    Variable.obligatoria("entidad", "Entidad que solicitó", "Dirección de Sanidad (ficticia)"),
                    Variable.obligatoria("fecha_escrito", "Fecha del escrito", "lunes 3 de noviembre de 2026"),
                    Variable.obligatoria("numero", "Número de la visita", "VN-2026-0001"),
                    Variable.obligatoria("punto", "Punto de la visita", "ESM Ejemplo (ficticio)"),
                    Variable.obligatoria("motivo", "Motivo de la cancelación", "El punto estará cerrado por inventario.")));

    @Override
    public List<Plantilla> plantillas() {
        return List.of(NOTIFICACION_PREVIA, SOLICITUD_NUEVA, SOLICITUD_AGENDADA, SOLICITUD_CANCELADA);
    }
}
