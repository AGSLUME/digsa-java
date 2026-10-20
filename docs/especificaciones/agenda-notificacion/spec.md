# Especificación: agenda-notificacion

> Extraída por spec-miner el 2026-10-20 del PHP de referencia (develop, commit db2e330).
> Fuentes: controladores/agenda.controlador.php, modelos/visitas.modelo.php, ajax/agenda.ajax.php, ajax/mis_visitas.ajax.php, ajax/consulta.ajax.php, app/Support/EstadosVisita.php, app/Support/Plazos.php, mails/notificacion_visita.html, Database/Structure/005_agenda.sql, Database/Data/002_catalogos.sql (par_tipos_visita, par_parametros), modelos/solicitudes.modelo.php (marcarProgramada, liberarDeVisita), tests/plazos_test.php, tests/reglas_test.php, tests/e2e_local.php, tests/ui/ui_abc.js, tests/ui/ui_fase2.js, docs/decisiones_2026-10-15.md, docs/dudas_pendientes.md.
> Dividida por tamaño: alertas, cobertura, previsualización, calendario, lista, consulta, Mis visitas y detalle están en `agenda-consulta`.
> Relacionadas: el cálculo de días hábiles, del límite de notificación y de las alertas de plazo está en `catalogos-festivos-plazos`; el filtro por alcance de cada perfil, en `perfiles-alcance`; el equipo, el reparto, reasignar y reabrir, en `equipo-reparto`; la solicitud de visita a necesidad, en `solicitudes-necesidad`.

---

### Requirement: Programar una visita
<!-- id: ControladorAgenda.programar -->
<!-- entidades: Visita, VisitaAuditor, VisitaReparto, VisitaHistorial -->
<!-- impuesto_en: controladores/agenda.controlador.php ControladorAgenda::programar(), ControladorAgenda::validar(); modelos/visitas.modelo.php ModeloVisitas::crear() -->
<!-- decision: 2 | 11 | 12 | 29 | Anexo 3 p.9, p.12-14 -->

El coordinador (o el administrador) DEBE poder programar una visita con tipo, punto activo, fecha, hora opcional, auditor líder activo con perfil Auditor, de 0 a 5 otros auditores y alcance; la visita, su número, su equipo, su reparto y la primera línea del historial ("Programada", con el líder y los otros auditores) DEBEN guardarse en una sola transacción, en estado `programada`, con `creado_por` = usuario; si se pidió `notificar` y el tipo lleva aviso, DEBE intentarse la notificación previa después de confirmar la transacción, y un fallo al notificar NO DEBE deshacer la programación.

#### Scenario: Programar con notificación automática
<!-- test: tests/e2e_local.php:260 -->
<!-- test: tests/e2e_local.php:265 -->
<!-- test: tests/ui/ui_abc.js:77 -->
- **CUANDO** el coordinador programa una visita inicial a un ESM con `notificar` y están registrados los correos de la DISAN, del OPL y del punto
- **ENTONCES** responde 200 con `notificacion_ok = true`, sale un correo y la visita queda `notificada` con número `VS-…`

#### Scenario: Notificación fallida no deshace la visita
<!-- test: tests/e2e_local.php:284 -->
- **CUANDO** se programa con `notificar` y faltan los correos del OPL
- **ENTONCES** la visita queda creada en `programada`, la respuesta trae `notificacion_ok = false` y el mensaje pide usar «Notificar» en la agenda

#### Scenario: Campos obligatorios
- **CUANDO** falta o no es válido el tipo, el punto (o está inactivo), la fecha, la hora escrita, el líder (activo con perfil Auditor) o el alcance
- **ENTONCES** responde 422 con "Falta o no es válido: …" y la lista de campos

#### Scenario: Tipo de otra unidad
- **CUANDO** el tipo de visita fija una unidad (p. ej. `inicial` → ESM, `farmacia` → FA, `central` → NC) y el punto es de otra unidad
- **ENTONCES** responde 422 "El tipo de visita «…» corresponde a otra unidad de intervención."

#### Scenario: Topes del formulario
- **CUANDO** el alcance pasa de 5000 caracteres, hay más de 5 otros auditores, o alguno de los otros no está activo o no tiene perfil Auditor
- **ENTONCES** responde 422; los otros auditores repetidos o iguales al líder se descartan sin error

#### Scenario: Visita a necesidad sin fecha de solicitud
<!-- test: tests/e2e_local.php:274 -->
<!-- decision: 12 -->
- **CUANDO** se programa una visita de tipo `necesidad` sin `fecha_solicitud`, con fecha de solicitud futura o posterior a la fecha de la visita
- **ENTONCES** responde 422 (Anexo 3 p.14: 5 días hábiles desde la solicitud)

#### Scenario: Visita a necesidad con fecha de solicitud
<!-- test: tests/e2e_local.php:277 -->
- **CUANDO** se programa una visita a necesidad con fecha de solicitud válida, aunque se marque `notificar`
- **ENTONCES** queda `programada`, la respuesta no trae `notificacion_ok` y entre sus acciones no está `notificar`

---

### Requirement: Número de la visita
<!-- id: ModeloVisitas.crear -->
<!-- entidades: Visita -->
<!-- impuesto_en: modelos/visitas.modelo.php ModeloVisitas::crear(); Database/Structure/005_agenda.sql uq_visitas_numero -->

Al crear la visita DEBE asignarse en la misma transacción el número `VS-` + fecha programada de la visita en formato AAAAMMDD + `-` + id de la visita con al menos 4 dígitos (relleno con ceros); el número NO DEBE cambiar al reprogramar.

#### Scenario: Formato del número
<!-- test: tests/e2e_local.php:265 -->
<!-- test: tests/ui/ui_abc.js:80 -->
- **CUANDO** se programa la visita con id 37 para el 2026-11-16
- **ENTONCES** su número es `VS-20261116-0037`

<!-- incierto: el número usa la fecha de la visita al crearla, no la de creación; tras reprogramar, la fecha del número y la de la visita difieren. No hay prueba que lo fije. -->

---

### Requirement: Agendar una solicitud de visita a necesidad
<!-- id: ControladorAgenda.programar#solicitud -->
<!-- entidades: Visita, SolicitudVisita -->
<!-- impuesto_en: controladores/agenda.controlador.php ControladorAgenda::programar(); modelos/solicitudes.modelo.php ModeloSolicitudes::marcarProgramada() -->
<!-- decision: 31 | 33 -->

Al programar con `solicitud_id`, la solicitud DEBE estar `pendiente`; la visita DEBE ser de tipo `necesidad`, al punto de la solicitud y con su fecha de solicitud (estos valores mandan sobre lo enviado); la solicitud DEBE pasar a `programada` y quedar ligada a la visita dentro de la misma transacción, con UPDATE condicionado a `estado = 'pendiente'`; si otro usuario la agendó o la anuló entre tanto, todo se deshace; después DEBE avisarse al solicitante (nunca al OPL), y un fallo del aviso NO DEBE deshacer la visita.

#### Scenario: Agendar la solicitud
<!-- test: tests/e2e_local.php:892 -->
<!-- test: tests/e2e_local.php:896 -->
<!-- test: tests/ui/ui_fase2.js:81 -->
- **CUANDO** el coordinador agenda una solicitud pendiente de Sanidad EJC
- **ENTONCES** la visita es `necesidad`, al punto pedido, con la fecha de la solicitud; la solicitud queda `programada` con el número de la visita y se avisa al solicitante

#### Scenario: Solicitud ya agendada
<!-- test: tests/e2e_local.php:897 -->
- **CUANDO** se intenta agendar de nuevo la misma solicitud
- **ENTONCES** responde 422 "La solicitud no existe o ya no está pendiente."

---

### Requirement: Editar una visita programada
<!-- id: ControladorAgenda.editar -->
<!-- entidades: Visita, VisitaAuditor, VisitaReparto -->
<!-- impuesto_en: controladores/agenda.controlador.php ControladorAgenda::editar(); app/Support/EstadosVisita.php ORIGEN['editar'] -->
<!-- decision: 28 | duda 9.9 -->

El coordinador DEBE poder editar tipo, punto, fecha, hora, equipo y alcance solo mientras la visita está `programada` (sin notificar), con las mismas validaciones de programar, bloqueando la fila (`FOR UPDATE`); el equipo y el reparto se rehacen y el historial registra "Editada" con el equipo resultante; la visita que viene de una solicitud DEBE conservar tipo `necesidad`, punto y fecha de solicitud.

#### Scenario: Visita ya notificada
<!-- test: tests/reglas_test.php:54 -->
- **CUANDO** la visita está `notificada`, `reprogramada`, en ejecución, cerrada o cancelada
- **ENTONCES** responde 422 "Solo se edita una visita programada sin notificar. Use Reprogramar o Cancelar."

#### Scenario: Visita a necesidad anterior a la fase 2
<!-- duda: 9.9 -->
- **CUANDO** se edita una visita a necesidad que no tiene fecha de solicitud
- **ENTONCES** se exige registrar la fecha de la solicitud

---

### Requirement: Destinatarios de la notificación previa
<!-- id: ControladorAgenda.destinatarios -->
<!-- entidades: Visita, Punto, Fuerza, Opl, ParametroDigsa -->
<!-- impuesto_en: controladores/agenda.controlador.php ControladorAgenda::destinatarios() -->
<!-- decision: 26 | 1.2 (octava ronda) | Anexo 3 p.9 -->
<!-- duda: 9.7 -->

La notificación DEBE ir a: la DISAN o JEFSA de la fuerza del punto (obligatoria si la fuerza tiene sanidad), el OPL (obligatorio), la DIGSA cuando la unidad es el nivel central (obligatoria) y el contacto del punto (opcional); los correos DEBEN validarse, repetidos (sin distinguir mayúsculas) contarse una vez y etiquetarse con su origen; si falta un destinatario obligatorio, la visita NO DEBE quedar notificada y el historial DEBE registrar "Notificación pendiente" con los que faltan.

#### Scenario: Los tres destinatarios
<!-- test: tests/e2e_local.php:263 -->
- **CUANDO** se notifica una visita a un ESM del Ejército
- **ENTONCES** el correo va a la DISAN EJC, al OPL y al contacto del punto

#### Scenario: Nivel central también a la DIGSA
<!-- test: tests/e2e_local.php:926 -->
- **CUANDO** se notifica una visita a nivel central (fuerza "No aplica", sin DISAN)
- **ENTONCES** el correo va al OPL, al contacto y a los correos de la DIGSA, sin exigir DISAN

#### Scenario: Sin correos de la DIGSA
<!-- test: tests/e2e_local.php:932 -->
<!-- test: tests/e2e_local.php:935 -->
- **CUANDO** no hay correos de la DIGSA registrados
- **ENTONCES** la visita al nivel central se programa pero queda `programada` (`notificacion_ok = false`); la de un ESM sí se notifica

#### Scenario: Sin correos del OPL
<!-- test: tests/e2e_local.php:284 -->
- **CUANDO** faltan los correos del OPL
- **ENTONCES** no sale correo y el mensaje dice "No se notificó: faltan los correos de OPL (…)"

<!-- duda: el código cita la "Decisión 24" para la copia a la DIGSA del nivel central; la regla viene de la octava ronda (duda 1.2). Mismo comportamiento. -->

---

### Requirement: Contenido de la notificación previa
<!-- id: ControladorAgenda.cuerpoNotificacion -->
<!-- entidades: Visita, VisitaAuditor -->
<!-- impuesto_en: controladores/agenda.controlador.php ControladorAgenda::asuntoNotificacion(), cuerpoNotificacion(), lineasEquipo(), NOTA_LEY_1581; mails/notificacion_visita.html -->
<!-- decision: 22 | 26 | Anexo 3 p.9 -->

El asunto DEBE ser "Notificación de visita de seguimiento {número} · {punto}"; el cuerpo DEBE traer número, fecha larga en español (con hora en formato HH:MM h si la hay), tipo, unidad de intervención con el punto (y su modalidad si es ESM), municipio y departamento, dirección, alcance específico, y una línea por integrante del equipo (líder primero, marcado "auditor líder") con nombre, rol, correo y documento ("sin registrar" si no lo tiene), seguidos de la nota de uso exclusivo para el control de ingreso (Ley 1581 de 2012) y la firma "Interventoría DIGSA"; el correo DEBE salir con la plantilla HTML, con el cuerpo escapado y los saltos de línea como `<br>`.

#### Scenario: Equipo completo con sus datos
<!-- test: tests/e2e_local.php:908 -->
<!-- test: tests/e2e_local.php:670 -->
- **CUANDO** se notifica una visita con líder QF y otro auditor regente
- **ENTONCES** el correo trae el correo y el documento del líder, "Regente de farmacia", "auditor líder" y "Ley 1581"

#### Scenario: Datos mínimos del Anexo 3
<!-- test: tests/e2e_local.php:263 -->
- **CUANDO** sale la notificación
- **ENTONCES** contiene "Alcance específico" y "Equipo auditor"

<!-- duda: la decisión 26 hace obligatorio el documento del auditor; la notificación tolera un integrante sin documento ("sin registrar"). La obligación se impone en maestros (ver maestros-carga-masiva). -->
<!-- incierto: visitas anteriores a la fase 1 sin otros auditores agregan el texto libre `visitas.equipo` como una línea más. -->

---

### Requirement: Envío de la notificación con registro previo
<!-- id: ControladorAgenda.enviarNotificacion -->
<!-- entidades: Visita, VisitaNotificacion, VisitaHistorial -->
<!-- impuesto_en: controladores/agenda.controlador.php ControladorAgenda::enviarNotificacion(); modelos/visitas.modelo.php ModeloVisitas::reservarNotificacion(), resultadoNotificacion(), cambiarEstado() -->

Antes de enviar, la notificación DEBE registrarse `pendiente` en `visitas_notificaciones` (destinatarios, asunto, cuerpo, origen, usuario) dentro de una transacción que bloquea la visita y comprueba que sigue en un estado permitido (por defecto `programada` o `reprogramada`) y que no hay otra pendiente de menos de 10 minutos; luego sale un solo correo a todos los destinatarios; el registro pasa a `enviada` o `fallida` (con el error, máximo 500 caracteres); si salió, la visita DEBE pasar a `notificada` con UPDATE condicionado a `programada`/`reprogramada` y el historial DEBE registrar "Notificada", "Notificada la nueva fecha" (si estaba reprogramada) o "Notificado el cambio de equipo" (si ya estaba notificada), con el origen y los destinatarios; si falló, el historial registra "Notificación fallida".

#### Scenario: No se notifica dos veces
<!-- test: tests/e2e_local.php:267 -->
- **CUANDO** se pide notificar una visita ya `notificada` sin cambio de equipo pendiente
- **ENTONCES** responde 422

#### Scenario: Doble clic o dos coordinadores
- **CUANDO** hay una notificación `pendiente` de la misma visita registrada hace menos de 10 minutos
- **ENTONCES** responde "Ya hay una notificación de esta visita en envío…" y no sale otro correo

#### Scenario: Origen registrado
- **CUANDO** la notificación sale al programar, al reprogramar, al reasignar o a mano
- **ENTONCES** el origen es "Envío automático al programar", "Envío automático al reprogramar", "Envío automático al reasignar el equipo" o "Envío manual del coordinador"

---

### Requirement: Notificación manual del coordinador
<!-- id: ControladorAgenda.notificarManual -->
<!-- entidades: Visita, VisitaNotificacion -->
<!-- impuesto_en: controladores/agenda.controlador.php ControladorAgenda::notificarManual(), MAX_DESTINATARIOS, MAX_CUERPO -->
<!-- decision: 26 | 28 | Anexo 3 p.9 -->

El coordinador DEBE poder notificar a mano una visita `programada` o `reprogramada` (o una `notificada`/`reprogramada` con cambio de equipo sin notificar), editando destinatarios, asunto y cuerpo: de 1 a 15 correos válidos, sin quitar a la DISAN/JEFSA, al OPL ni a la DIGSA del nivel central (el contacto del punto sí se puede quitar), asunto de máximo 200 caracteres que incluya el número de la visita y cuerpo de máximo 6000 caracteres; asunto o cuerpo vacíos toman los calculados.

#### Scenario: Sin el OPL
<!-- test: tests/e2e_local.php:288 -->
- **CUANDO** el coordinador notifica a mano quitando el correo del OPL
- **ENTONCES** responde 422 "La notificación debe incluir a las DISANES y al OPL (Anexo 3 p.9). Faltan: …"

#### Scenario: Asunto sin número
- **CUANDO** el asunto no contiene el número de la visita o pasa de 200 caracteres
- **ENTONCES** responde 422

#### Scenario: Visita a necesidad
<!-- test: tests/reglas_test.php:57 -->
- **CUANDO** se intenta notificar una visita de aviso `no-opl`
- **ENTONCES** responde 422 "La visita a necesidad se hace sin previo aviso al OPL (Anexo 3 p.14)."

---

### Requirement: Reprogramar una visita
<!-- id: ControladorAgenda.reprogramar -->
<!-- entidades: Visita, VisitaHistorial -->
<!-- impuesto_en: controladores/agenda.controlador.php ControladorAgenda::reprogramar(); app/Support/EstadosVisita.php ORIGEN['reprogramar'] -->
<!-- decision: v6 (reprogramada exige notificar la nueva fecha) -->
<!-- duda: 9.3 -->

El coordinador DEBE poder reprogramar una visita que no ha iniciado (`programada`, `notificada` o `reprogramada`) con nueva fecha válida, hora opcional válida y motivo obligatorio (máximo 800 caracteres), bloqueando la fila; si fecha y hora son las mismas, se rechaza; la `programada` sigue `programada` y las demás pasan a `reprogramada` (UPDATE condicionado al estado leído); el historial DEBE registrar "Reprogramada" con fecha y hora anteriores y nuevas y el motivo; si quedó `reprogramada` y se pidió `notificar`, DEBE notificarse la nueva fecha (asunto con " · nueva fecha"). La reprogramación NO DEBE avisar al solicitante de una visita a necesidad.

#### Scenario: Reprogramar una notificada
<!-- test: tests/e2e_local.php:271 -->
- **CUANDO** se reprograma una visita `notificada` con `notificar`
- **ENTONCES** sale la notificación de la nueva fecha, la visita vuelve a `notificada` y tiene 2 notificaciones registradas

#### Scenario: Sin motivo o misma fecha
- **CUANDO** falta el motivo, la fecha no es válida, o la fecha y la hora no cambian
- **ENTONCES** responde 422

#### Scenario: Visita iniciada
- **CUANDO** la visita está en ejecución, cerrada o cancelada
- **ENTONCES** responde 422 "Solo se reprograma una visita que no ha iniciado."

---

### Requirement: Cancelar una visita
<!-- id: ControladorAgenda.cancelar -->
<!-- entidades: Visita, SolicitudVisita, VisitaHistorial -->
<!-- impuesto_en: controladores/agenda.controlador.php ControladorAgenda::cancelar(); modelos/solicitudes.modelo.php ModeloSolicitudes::liberarDeVisita() -->
<!-- decision: 3.1 (octava ronda: las canceladas cuentan en totales) -->
<!-- duda: 9.3 -->

El coordinador DEBE poder cancelar una visita `programada`, `notificada`, `reprogramada` o en ejecución, con motivo obligatorio (máximo 800 caracteres), en una transacción con UPDATE condicionado a esos estados; el historial DEBE registrar "Cancelada" con el motivo; si la visita atendía una solicitud de visita a necesidad, la solicitud DEBE volver a `pendiente` sin visita (su plazo sigue corriendo desde la fecha del escrito), con historial "Solicitud liberada" y sin correo al solicitante. La visita NO DEBE borrarse.

#### Scenario: Cancelar con motivo
<!-- test: tests/e2e_local.php:290 -->
- **CUANDO** el coordinador cancela una visita programada con motivo
- **ENTONCES** responde 200 y la visita queda `cancelada`

#### Scenario: La solicitud vuelve a pendiente
<!-- test: tests/e2e_local.php:950 -->
<!-- test: tests/e2e_local.php:952 -->
- **CUANDO** se cancela la visita de una solicitud
- **ENTONCES** la solicitud queda `pendiente` sin `visita_id` y se puede agendar de nuevo

#### Scenario: Visita cerrada
- **CUANDO** la visita ya está cerrada o cancelada
- **ENTONCES** responde 422 "La visita no existe o ya está cerrada o cancelada."

---

### Requirement: Iniciar una visita
<!-- id: ControladorAgenda.iniciar -->
<!-- entidades: Visita, ChecklistVersion, VisitaReparto -->
<!-- impuesto_en: controladores/agenda.controlador.php ControladorAgenda::iniciar(); ajax/mis_visitas.ajax.php -->
<!-- decision: 2b -->

Cualquier integrante del equipo (líder u otro auditor) DEBE poder iniciar una visita `programada`, `notificada` o `reprogramada`; en una transacción la visita pasa a `ejecucion` con UPDATE condicionado y se fija la versión vigente del checklist; si el reparto no corresponde a las partes de esa versión, se reparte de nuevo (ver `equipo-reparto`); el historial DEBE registrar "En ejecución" con quién la inició y la versión. Iniciar una visita ya en ejecución DEBE responder éxito sin cambios.

#### Scenario: Iniciar
<!-- test: tests/e2e_local.php:309 -->
- **CUANDO** la auditora del equipo inicia su visita notificada
- **ENTONCES** queda `ejecucion` con `checklist_version = 'v6'`

#### Scenario: Un integrante que no es líder
<!-- test: tests/e2e_local.php:672 -->
- **CUANDO** el regente (otro auditor) inicia la visita del equipo
- **ENTONCES** responde 200

#### Scenario: No es del equipo o sin versión vigente
- **CUANDO** quien inicia no es del equipo, o no hay versión vigente del checklist
- **ENTONCES** responde 422 ("no está asignada a usted" o "No hay una versión vigente del checklist…")

---

### Requirement: Acciones disponibles según estado y usuario
<!-- id: EstadosVisita.acciones -->
<!-- entidades: Visita -->
<!-- impuesto_en: app/Support/EstadosVisita.php EstadosVisita::acciones(); controladores/agenda.controlador.php detalleCompleto() -->
<!-- decision: 1 | 28 -->

El detalle DEBE ofrecer al coordinador, en este orden, `editar`, `reasignar`, `reprogramar` y `cancelar` según la tabla de estados, `notificar` si el aviso no es `no-opl` y el estado lo permite (o hay cambio de equipo sin notificar), y `reabrir` en la cerrada; al integrante del equipo, `iniciar` en `programada`, `notificada`, `reprogramada` o `ejecucion` (continuar).

#### Scenario: Programada
<!-- test: tests/reglas_test.php:53 -->
- **CUANDO** el coordinador ve una visita `programada` con aviso al OPL
- **ENTONCES** las acciones son `editar`, `reprogramar`, `cancelar`, `notificar`

#### Scenario: Notificada
<!-- test: tests/reglas_test.php:54 -->
- **CUANDO** el coordinador ve una visita `notificada`
- **ENTONCES** las acciones son `reasignar`, `reprogramar`, `cancelar`

#### Scenario: Auditor
<!-- test: tests/reglas_test.php:58 -->
<!-- test: tests/reglas_test.php:59 -->
- **CUANDO** un integrante ve su visita `notificada` o en ejecución, o un auditor ve una visita de otro equipo
- **ENTONCES** obtiene `iniciar`, o nada

#### Scenario: Cerrada
<!-- test: tests/reglas_test.php:60 -->
- **CUANDO** se ve una visita cerrada
- **ENTONCES** el coordinador tiene solo `reabrir` y el auditor nada

---

### Invariant: Seis estados de la visita
<!-- entidades: Visita -->
<!-- impuesto_en: app/Support/EstadosVisita.php NOMBRES; Database/Structure/005_agenda.sql visitas.estado -->

La visita DEBE estar en uno de `programada` (Programada), `notificada` (Notificada), `reprogramada` (Reprogramada), `ejecucion` (En ejecución), `cerrada` (Cerrada) o `cancelada` (Cancelada); por ejecutar son `programada`, `notificada` y `reprogramada`.

---

### Invariant: Transiciones permitidas
<!-- entidades: Visita -->
<!-- impuesto_en: app/Support/EstadosVisita.php ORIGEN, permite() -->
<!-- verificado_por: tests/reglas_test.php:55 -->

Cada acción DEBE partir solo de estos estados: editar ← programada; notificar ← programada, reprogramada; reprogramar ← por ejecutar; cancelar ← por ejecutar o ejecucion; reasignar ← notificada, reprogramada; iniciar ← por ejecutar; reabrir ← cerrada. El cierre (ejecucion → cerrada) lo hace el envío del acta (ver `acta-envio`).

---

### Invariant: Cambio de estado condicionado
<!-- entidades: Visita -->
<!-- impuesto_en: modelos/visitas.modelo.php ModeloVisitas::cambiarEstado() -->

Todo cambio de estado DEBE hacerse con `UPDATE … WHERE id = ? AND estado IN (origen)` y tomarse como fallido si no cambió ninguna fila; junto con el estado solo pueden cambiarse `fecha`, `hora` y `checklist_version`.

---

### Invariant: Las visitas no se borran
<!-- entidades: Visita, VisitaHistorial, VisitaNotificacion -->
<!-- impuesto_en: Database/Structure/005_agenda.sql (FK ON DELETE RESTRICT) -->

Una visita NO DEBE borrarse (se cancela con motivo); su historial y sus notificaciones son evidencia y tampoco se borran.

---

### Invariant: Historial de la visita
<!-- entidades: VisitaHistorial -->
<!-- impuesto_en: modelos/visitas.modelo.php ModeloVisitas::agregarHistorial(); Database/Structure/005_agenda.sql visitas_historial -->

Cada evento DEBE quedar en `visitas_historial` con fecha, usuario de la sesión (o el indicado), evento (máximo 60) y detalle cortado a 1000 caracteres.

---

### Invariant: Ninguna notificación sin registro
<!-- entidades: VisitaNotificacion -->
<!-- impuesto_en: Database/Structure/005_agenda.sql visitas_notificaciones; modelos/visitas.modelo.php reservarNotificacion() -->
<!-- duda: 7.5 -->

Cada notificación DEBE quedar en `visitas_notificaciones` antes de salir el correo, con destinatarios, asunto (máximo 255), cuerpo completo (incluye documentos de los auditores), origen, estado `pendiente` | `enviada` | `fallida`, error y fecha de resultado; se conserva sin plazo definido.

---

### Invariant: Número único
<!-- entidades: Visita -->
<!-- impuesto_en: Database/Structure/005_agenda.sql uq_visitas_numero -->

Dos visitas NO DEBEN tener el mismo número.

---

### Invariant: Solo el coordinador usa la agenda
<!-- entidades: Visita -->
<!-- impuesto_en: ajax/agenda.ajax.php Auth::requerirAjax(['Coordinador']); app/Support/Auth.php Auth::tiene() -->
<!-- verificado_por: tests/e2e_local.php:310 -->

La agenda (programar, editar, notificar, reprogramar, cancelar, reasignar, mover partes, reabrir) DEBE ser solo del Coordinador; el Administrador pasa todas las comprobaciones de perfil; el auditor recibe 403.

<!-- duda: 9.12 (el administrador hace todo lo del coordinador pero no solicita visitas) -->
<!-- incierto: Plazos.php conserva en su comentario de cabecera que "la app no pide la fecha de solicitud"; es anterior a la decisión 12 y el código sí la pide. -->
<!-- pendiente: controladores/solicitudes.controlador.php (avisarSolicitante, pendientes), modelos/puntos.modelo.php (opcionesFiltro), app/Support/Alcance.php (completo), app/Support/Mail/Mailer.php, vistas de la agenda (JS del calendario) -->
