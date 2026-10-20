# Decisiones del 2026-10-20 que cambian el comportamiento del PHP

Fuente: `SUITE DIGSA\Dudas_resueltas_Suite_DIGSA_2026-10-20.xlsx`, con las respuestas del usuario cruzadas con los anexos y con las respuestas de la DIGSA del 19-oct. El PHP de referencia (`db2e330`) todavía no las tiene. **En estos puntos manda este documento, no el PHP.**

## Por desarrollar (decididas)

| Duda | Qué se implementa |
|---|---|
| 7.1 | **Sesión única: se bloquea el segundo ingreso** con un mensaje. La sesión abierta se libera con «Salir», a los 10 minutos de inactividad o cuando el administrador la cierra (botón en Usuarios). Lo pide el Anexo 5, ítem 3, p.27. |
| 7.3 | Al cambiar la contraseña o inactivar al usuario, la sesión se cierra **y la salida queda en la bitácora** |
| 7.5 | La bitácora se conserva toda, sin purga, y entra en la entrega final |
| 11.5 | **Sin firma tardía del plan**, ni por QR ni con usuario OPL. La notificación del acta dice en forma destacada el plazo máximo de 5 días hábiles y la fecha. Al vencer: estado «vencido», alerta y reporte a la DIGSA |
| Reunión 5 | Plan no presentado o incumplido: **reporte formal a la DIGSA y al auditor** con hallazgo, fechas, evidencias y consecutivo. Estado «reportado a DIGSA». La aplicación nunca inicia la multa |
| 16.1 | Las visitas virtuales no cambian la aplicación. **La cobertura en sitio excluye los puntos de suministro y satélite**, que se vigilan de forma remota (respuesta INTERSALUD P9) |
| 12.2 | **El ESM no ve las objeciones del OPL ni sus soportes** |
| 11.7 | Al levantar el último hallazgo, el plan se cierra con su registro de cierre y **se notifica solo al OPL** |
| 11.8 | La firma del plan por QR pide también el **número de documento** |
| 9.4 | **Cada solicitante ve solo sus solicitudes**; el coordinador ve todas |
| 9.8 | Al reasignar auditores **el reenvío de la notificación es obligatorio**. Si faltan menos de 15 días, se advierte |
| 8.3 | **Las partes del checklist solo se mueven antes de iniciar la visita** |
| 15.2 | Exportación con CSV ";", coma decimal y UTF-8, **más la carpeta de soportes** (fotos, PDF, actas) con índice y huella por archivo |
| 10.7 | Las fotos se corrigen según la orientación EXIF al subirlas |
| 14.3 | El puntaje de riesgo es **solo para los ESM de dispensación** |
| 11.2 | El código de 6 dígitos del QR va **solo al OPL** |
| 9.3 | Al cancelar la visita de una solicitud **se avisa al solicitante** y la solicitud vuelve a pendiente para reprogramarla |
| 9.6 | **Correo al coordinador** por cada solicitud nueva |
| 13.4 | El ciclo anual se cuenta **desde la fecha de inicio del contrato** (parámetro) |

## Con la resolución recomendada, pendientes del visto bueno del usuario

Se implementan con la recomendación y van en la lista final de validación.

| Duda | Qué se implementa |
|---|---|
| 14.1 | Riesgo:<br>- **pesos editables por el coordinador**, con bitácora y la etiqueta «propuesta AGS, pendiente de aprobación DIGSA»;<br>- **«días sin visita» sale del puntaje** y queda como criterio de la agenda;<br>- se agregan las **incidencias reportadas por la DIGSA** (registro manual) |
| 12.1 | La prórroga queda **«solicitada» hasta que la aprueben el coordinador y la DIGSA**, ambos con bitácora |
| 7.2 | La bitácora registra los accesos negados por permisos (403), no los errores de formulario |
| 13.1 | Distancia en línea recta con las coordenadas DANE y «se llega desde» (sin cambio) |
| 13.3 | Reparto de la carga: 60 km por visita de más, sin tope. Se agrega un **reporte de la distribución auditor–punto** para la DIGSA |
| 15.3 | Un solo perfil con alcance por usuario; no hay usuarios viejos que revisar |
| 14.2 | El volumen de dispensación queda fuera hasta que el OPL cargue datos |

## Sin cambio (cerradas)

11.1 (vencimiento a las 23:59:59), 11.3 (el líder no tiene plazo), 11.4 (sin firma nueva tras ratificar), 10.4 (copia visible, cualquier dominio), 10.12 (el correo del personal se queda en el acta), 10.2 (un solo resultado con los soportes adjuntos), 13.5 (ingeniero solo en el nivel central), 14.4 (sin ciclo por riesgo), 9.2 y 8.5 (se mantiene el seguimiento al reabrir).

## Pendientes de TI (no bloqueantes)

1.3 (correo real; se recomienda un buzón de pruebas tipo Mailtrap), 10.1 (tope del SMTP), 15.1 (Safari), 4.1 (triggers), 2.8 (OCR en el servidor), 5.1 (minutas reales), 8.13 (versión de la base con soporte vigente).

## Prueba en sitio: lo que se diseña desde el inicio

| Ítem o condición | Qué se diseña |
|---|---|
| Ítem 2 y condición 12 | **Contraseña inicial asignada por el administrador, con cambio obligatorio dentro de la aplicación** (sin salir al correo). El enlace por correo sigue disponible |
| Ítem 4 | Fuerzas y tipos de documento editables desde la pantalla (crear, modificar, eliminar) |
| Ítem 11 | El coordinador elige los componentes al crear la visita |
| Ítem 12 | Hora con un control que no es texto; «lugar», «componente» y «criterio» como listas parametrizables |
| Ítem 13 | Hora real de inicio y fin, componente, objeto y texto del hallazgo |
| Ítem 15 | El detalle de la bitácora trae los **valores de antes y después** de cada modificación |
| Respuestas de la DIGSA | Nombres de los perfiles configurables (el catálogo final llega por adenda) y 8 regionales: Bogotá, Medellín, Cali, Barranquilla, Bucaramanga, Cartagena, Ibagué y Neiva |
