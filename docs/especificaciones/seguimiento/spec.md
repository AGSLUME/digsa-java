# Especificación: seguimiento

> Extraída por spec-miner el 2026-10-21 del PHP de referencia (develop, commit db2e330).
> Fuentes: app/Support/Seguimiento.php, controladores/checklist.controlador.php (seguimiento(), archivosAnteriores(), servirArchivoAnterior(), enviar(), cerrar(), programarSeguimiento(), avisarSeguimiento(), textoSeguimiento(), prorrogas(), nombreActa()), controladores/hallazgos.controlador.php, ajax/hallazgos.ajax.php, controladores/opl.controlador.php (resolver), modelos/visitas.modelo.php (seguimientoDe), scripts/recordatorios_seguimiento.php, Database/Structure/006_checklist_visita.sql (visita_hallazgos), tests/seguimiento_test.php, tests/e2e_local.php, tests/ui/ui_seg.js, docs/decisiones_2026-10-15.md, docs/dudas_pendientes.md (llamada 2026-10-14, dudas 6.x y 12.x).
> **Mandan sobre el PHP** las decisiones de `docs/decisiones_2026-10-20.md` (marcadas `<!-- decision: 2026-10-20 X.Y -->`): el texto describe el comportamiento NUEVO. La 12.1 está «con la resolución recomendada, pendiente del visto bueno del usuario»: se especifica la recomendación.
> Relacionadas: regla de hallazgo, respuesta del OPL, plan 5W2H y reporte a la DIGSA en `flujo-opl`; bloqueo de lo que no es hallazgo al guardar (`cambiosBloqueados`) y componentes activos en `checklist`; notificación previa en `agenda-notificacion`; alcance de cada perfil en `perfiles-alcance`.
> Estado en Java: la tabla `visitas` no tiene `origen_id` ni `seguimiento_n`; no hay programación automática, prórrogas, recordatorio ni «Prestadores con hallazgos». Los parámetros `max_seguimientos` (3), `recordatorio_seguimiento_habiles` (5) y `correo_digsa` ya están en `R__catalogos.sql`; el tipo de visita `plan` («Seguimiento a plan de mejoramiento») también. Ver «Datos que la reescritura Java debe agregar» al final.

---

### Requirement: Programación automática al cerrar una visita con hallazgos
<!-- id: ControladorChecklist.programarSeguimiento | Seguimiento.fecha -->
<!-- entidades: Visita, VisitaHallazgo, VisitaAuditor -->
<!-- impuesto_en: controladores/checklist.controlador.php cerrar(), programarSeguimiento(); app/Support/Seguimiento.php fecha(), abiertos() -->
<!-- decision: 30 | duda 6.1 | duda 6.2 | duda 6.12 -->

En la misma transacción del cierre, si la visita no tiene ya un seguimiento no cancelado, el sistema DEBE crear la visita de seguimiento: tipo `plan`, mismo punto, hora, auditor líder y otros auditores (el formulario se reparte de nuevo), `origen_id` = la visita cerrada, `seguimiento_n` = el de la visita + 1, estado `programada`, creada por `sistema`, en la **fecha fin más próxima de las acciones abiertas** del plan (estado distinto de Completado y Cancelado) o el siguiente día hábil, con alcance «{Ordinal} visita de seguimiento al plan de mejoramiento de la visita {número}: verificar N acción(es) del plan 5W2H.»; queda historial en ambas visitas. Los hallazgos que el líder levantó no cuentan.

#### Scenario: Se programa sola
<!-- test: tests/e2e_local.php:485, tests/e2e_local.php:489, tests/e2e_local.php:490 -->
- **CUANDO** se cierra una visita con un hallazgo cuya acción vence un día hábil
- **ENTONCES** se crea la visita tipo `plan`, N° 1, mismo punto y auditor, en esa fecha, con «plan de mejoramiento» en el alcance

#### Scenario: Fecha del seguimiento
<!-- test: tests/seguimiento_test.php:38, tests/seguimiento_test.php:40 -->
- **CUANDO** las acciones abiertas vencen el 20-nov y el 15-dic / la del 20-nov está Completado
- **ENTONCES** la fecha es 2026-11-20 / 2026-12-15

#### Scenario: Reabrir y cerrar de nuevo no duplica
<!-- test: tests/e2e_local.php:760 -->
- **CUANDO** una visita con seguimiento se reabre y se cierra otra vez
- **ENTONCES** sigue habiendo un solo seguimiento no cancelado

---

### Requirement: Sin acciones abiertas no hay seguimiento; sin fecha fin no se envía
<!-- id: Seguimiento.fecha | Seguimiento.sinFecha -->
<!-- entidades: Plan5W2H -->
<!-- impuesto_en: app/Support/Seguimiento.php fecha(), sinFecha(); app/Support/ResumenChecklist.php faltantesEnvio() -->
<!-- decision: 6.7 -->

Si todas las acciones del plan están Completado o Cancelado, NO DEBE programarse seguimiento (el acta lo dice). Una acción abierta sin fecha fin DEBE impedir el envío del acta («Fecha fin en el plan 5W2H de …»).

#### Scenario: Todo cerrado
<!-- test: tests/seguimiento_test.php:42, tests/e2e_local.php:520 -->
- **CUANDO** el seguimiento se cierra sin incumplimientos
- **ENTONCES** no se crea otro seguimiento

#### Scenario: Acción sin fecha
<!-- test: tests/seguimiento_test.php:44, tests/seguimiento_test.php:70 -->
- **CUANDO** OB-OPL-01 sigue abierta sin fecha fin
- **ENTONCES** `sinFecha = ['OB-OPL-01']` y el envío pide «Fecha fin»

---

### Requirement: Avisos al programar el seguimiento
<!-- id: ControladorChecklist.avisarSeguimiento -->
<!-- entidades: Visita, VisitaNotificacion -->
<!-- impuesto_en: controladores/checklist.controlador.php avisarSeguimiento(); ControladorAgenda::enviarNotificacion() -->
<!-- decision: 30 | duda 6.2 -->

Fuera de la transacción del cierre, el sistema DEBE notificar de inmediato la visita de seguimiento (camino A: DISAN o JEFSA, OPL y contacto del punto, como la notificación previa) y enviar al auditor líder un correo con número, fecha larga, lugar y la lista de acciones por verificar (código, texto, fin del plan); el resultado de cada aviso queda en el historial y en el mensaje del cierre.

#### Scenario: Notificada y avisada
<!-- test: tests/e2e_local.php:494, tests/e2e_local.php:495 -->
- **CUANDO** se programa el seguimiento
- **ENTONCES** el auditor recibe el correo con OB-OPL-01 y la visita queda `notificada`

---

### Requirement: Máximo de seguimientos y escalamiento con reporte formal
<!-- id: ControladorChecklist.programarSeguimiento (máximo) | avisarSeguimiento (escalamiento) -->
<!-- entidades: Visita, ReporteDigsa -->
<!-- impuesto_en: controladores/checklist.controlador.php programarSeguimiento(), avisarSeguimiento(); parámetro max_seguimientos = 3 -->
<!-- decision: 24 | duda 6.3 | duda 12.4 | 2026-10-20 Reunión 5 -->

Si el seguimiento siguiente superaría `max_seguimientos`, NO DEBE programarse: queda historial «Máximo de seguimientos» y se escala por correo «Plan de mejoramiento sin cerrar» a los coordinadores activos con copia a la DIGSA (sin coordinador, a la DIGSA como destinataria principal; sin correos de la DIGSA, solo al coordinador y el historial lo dice), con las acciones abiertas. **Cambio 2026-10-20 (Reunión 5):** el plan incumplido genera además el **reporte formal a la DIGSA y al auditor** con consecutivo, hallazgo, fechas y evidencias (el mismo de `flujo-opl`, motivo `plan_incumplido`) y el plan queda **«reportado a DIGSA»**; la aplicación nunca inicia la multa.

#### Scenario: Escalamiento
<!-- test: tests/e2e_local.php:1185, tests/e2e_local.php:1186 -->
- **CUANDO** se cierra el tercer seguimiento con acciones abiertas
- **ENTONCES** no se programa un cuarto, sale el correo al coordinador con copia a la DIGSA y el historial dice «Escalamiento»

#### Scenario: Reporte del plan incumplido
<!-- test: sin prueba en el PHP (decisión nueva) -->
- **CUANDO** se alcanza el máximo con acciones abiertas
- **ENTONCES** sale el reporte con su consecutivo y el plan queda «reportado a DIGSA»

<!-- incierto: si «plan incumplido» se reporta solo al llegar al máximo o también en cada seguimiento con la acción vencida y en NC. Se especifica al máximo (donde el PHP ya escala); confirmarlo con el usuario. -->

---

### Requirement: Texto del seguimiento en el acta
<!-- id: ControladorChecklist.textoSeguimiento | nombreActa -->
<!-- entidades: VisitaActa -->
<!-- impuesto_en: controladores/checklist.controlador.php textoSeguimiento(), nombreActa(); app/Support/ActaHtml.php -->
<!-- decision: 6.10 (novena ronda) -->

Bajo el plan 5W2H y antes de sus firmas, el acta DEBE decir la fecha de la visita de seguimiento (fecha larga, «la fecha fin más próxima… la {ordinal} visita se programa sola»), o que no habrá seguimiento (todo completado o cancelado), o que se alcanzó el máximo y se escala, o que falta la fecha fin. El acta y el asunto de un seguimiento se llaman «Acta de seguimiento a plan de mejoramiento» y traen el ordinal y la visita de origen; las demás, «Acta de visita».

#### Scenario: Asunto del seguimiento
<!-- test: tests/ui/ui_seg.js:97 -->
- **CUANDO** el auditor abre el envío del acta de un seguimiento
- **ENTONCES** el asunto propone «Acta de seguimiento a plan de mejoramiento …»

---

### Requirement: Qué se verifica en el seguimiento
<!-- id: ControladorChecklist.seguimiento -->
<!-- entidades: VisitaHallazgo, OplRespuesta -->
<!-- impuesto_en: controladores/checklist.controlador.php seguimiento() -->
<!-- decision: 17 | 6.5 | 6.11 -->

Los hallazgos por verificar (editables) DEBEN ser los de `visita_hallazgos` de la visita de origen menos los levantados por el líder; solo ellos se califican de nuevo, desde cero (se muestra el hallazgo anterior). Lo demás se ve y no se edita (ver `checklist`, «En el seguimiento solo se recalifican los hallazgos»). El resumen, los «No aplica» de habilitación y el acta del seguimiento cuentan y muestran solo lo verificado.

#### Scenario: Recalificar el hallazgo
<!-- test: tests/e2e_local.php:515, tests/e2e_local.php:517 -->
- **CUANDO** el auditor califica Cumple el hallazgo y marca su acción Completado
- **ENTONCES** se guarda y el resumen es `c=1, nc=0, na=0, pend=0`

#### Scenario: Pantalla
<!-- test: tests/ui/ui_seg.js:60, tests/ui/ui_seg.js:64, tests/ui/ui_seg.js:70 -->
- **CUANDO** el auditor abre el seguimiento
- **ENTONCES** ve el banner «Primera visita de seguimiento…», lo que cumplía bloqueado y por defecto solo los ítems por verificar

---

### Requirement: Estado inicial del checklist del seguimiento
<!-- id: Seguimiento.estadoInicial -->
<!-- entidades: VisitaChecklist, Plan5W2H -->
<!-- impuesto_en: app/Support/Seguimiento.php estadoInicial(); controladores/checklist.controlador.php seguimiento(), imponerAgenda() -->
<!-- decision: 6.5 | 6.11 | 7 | duda 12.5 -->

El seguimiento DEBE partir del estado de la visita de origen con: los componentes fijados a los activos de entonces; cada hallazgo por verificar sin calificación, sin hallazgo, sin «inmediata» y sin subvalidación; lo que cumplía o no aplicaba igual; el plan 5W2H anterior como punto de partida, **con el quién y el cómo que remitió el OPL**, sin la justificación de prórroga anterior y sin fecha del plan; firmas del 5W2H y del cierre, cierre (recomendaciones, observaciones, compromisos), envío y fechas de la visita (notificación, inicio, fin, solicitud) en blanco; los datos de la agenda impuestos por el servidor.

#### Scenario: Partida
<!-- test: tests/seguimiento_test.php:49, tests/seguimiento_test.php:50, tests/seguimiento_test.php:51, tests/seguimiento_test.php:52, tests/seguimiento_test.php:53 -->
- **CUANDO** se arma el estado inicial con OB-OPL-01 y HB-02 por verificar
- **ENTONCES** ambos quedan sin calificar, lo que cumplía sigue en C, los componentes quedan fijos (C02 no se activa), cierre y firmas en blanco y la fila del plan conserva `fin = 2026-11-20`

#### Scenario: Quién y cómo del OPL
<!-- test: tests/e2e_local.php:1084 -->
- **CUANDO** el OPL remitió «Director técnico» como responsable
- **ENTONCES** el seguimiento parte con ese quién en la fila del plan

---

### Requirement: Prórroga de una acción del plan
<!-- id: Seguimiento.prorrogasSinJustificar | Seguimiento.esProrroga | ControladorChecklist.prorrogas -->
<!-- entidades: Prorroga, Plan5W2H, Bitacora -->
<!-- impuesto_en: app/Support/Seguimiento.php prorrogasSinJustificar(), esProrroga(); controladores/checklist.controlador.php enviar() paso 1, prorrogas() -->
<!-- decision: 6.4 | 2026-10-20 12.1 (recomendación pendiente del visto bueno del usuario) | duda 12.5 -->

En el seguimiento, una acción que sigue en incumplimiento y cuya fecha fin válida pasa a ser **posterior** a la que tenía en la visita anterior es una prórroga: el envío del acta DEBE exigir su justificación (mínimo 10 caracteres sin contar espacios alrededor; las tildes cuentan como una letra). Adelantar la fecha, fijarla donde no había o una fecha inválida no es prórroga. La justificación es de esa visita y no pasa al siguiente seguimiento. El acta lista acción, fin anterior, fin nuevo y justificación. **Cambio 2026-10-20 12.1:** al enviar el acta, cada prórroga queda **«solicitada»** y solo pasa a **«aprobada»** cuando la aprueban **el coordinador y la DIGSA**, cada uno con su registro en la bitácora (quién, cuándo, decisión); cualquiera de los dos puede rechazarla con motivo. El acta dice que la prórroga está solicitada.

#### Scenario: Sin justificación
<!-- test: tests/seguimiento_test.php:75, tests/e2e_local.php:1147 -->
- **CUANDO** la fecha fin de P01 pasa del 10-nov al 30-nov sin justificación
- **ENTONCES** `prorrogasSinJustificar = ['it:P01']` y el acta no se envía (422 «prórroga»)

#### Scenario: Justificación corta y válida
<!-- test: tests/seguimiento_test.php:78, tests/seguimiento_test.php:80, tests/e2e_local.php:1151 -->
- **CUANDO** la justificación tiene menos de 10 caracteres / tiene 10 o más
- **ENTONCES** sigue faltando / ya no falta

#### Scenario: No es prórroga
<!-- test: tests/seguimiento_test.php:76, tests/seguimiento_test.php:85 -->
- **CUANDO** se adelanta la fecha, se fija donde no había o es `2026-13-40`
- **ENTONCES** no se pide justificación

#### Scenario: Doble aprobación
<!-- test: sin prueba en el PHP (decisión nueva) -->
- **CUANDO** se envía el acta con una prórroga justificada y solo la aprueba el coordinador
- **ENTONCES** sigue «solicitada»; al aprobarla también la DIGSA pasa a «aprobada» y la bitácora tiene las dos aprobaciones

#### Scenario: Pantalla
<!-- test: tests/ui/ui_seg.js:81 -->
- **CUANDO** el auditor corre la fecha fin en el 5W2H
- **ENTONCES** aparece el campo de justificación de la prórroga

<!-- incierto: la 12.1 no dice qué fecha manda mientras la prórroga está «solicitada» ni qué pasa al rechazarla (para programar el siguiente seguimiento). Recomendación: el seguimiento se programa con la fecha solicitada (como el PHP) y, si se rechaza, el coordinador lo reprograma a la fecha anterior; confirmarlo. También es nuevo que el perfil DIGSA haga una acción de escritura (decisión 20: «solo ven»). -->

---

### Requirement: Soportes de la visita anterior en solo lectura
<!-- id: ControladorChecklist.archivosAnteriores | servirArchivoAnterior -->
<!-- entidades: Archivo -->
<!-- impuesto_en: controladores/checklist.controlador.php archivosAnteriores(), servirArchivoAnterior() -->
<!-- decision: 6.6 | duda 12.3 -->

El seguimiento DEBE mostrar y dejar descargar, en solo lectura, los soportes vigentes de la visita de origen **solo de los hallazgos que se verifican**, a quien tiene acceso al seguimiento; cualquier otro archivo o usuario recibe 404.

#### Scenario: Descarga
<!-- test: tests/e2e_local.php:1162, tests/e2e_local.php:1163, tests/e2e_local.php:1164, tests/e2e_local.php:1165 -->
- **CUANDO** el auditor del seguimiento / otro auditor / un id ajeno piden el soporte anterior
- **ENTONCES** se descarga el PDF / 404 / 404

---

### Requirement: Recordatorio al auditor antes del seguimiento
<!-- id: scripts/recordatorios_seguimiento.php -->
<!-- entidades: Visita, VisitaHistorial -->
<!-- impuesto_en: scripts/recordatorios_seguimiento.php; parámetro recordatorio_seguimiento_habiles = 5 -->
<!-- decision: duda 6.9 -->

Una tarea diaria DEBE enviar al auditor líder, una sola vez por visita, un recordatorio de cada seguimiento (programado, notificado o reprogramado) cuya fecha esté entre hoy y hoy + `recordatorio_seguimiento_habiles` días hábiles: ordinal, visita de origen, número, fecha y hora, lugar y acciones abiertas. Queda «Recordatorio al auditor» en el historial (marca de una sola vez); si falla, «Recordatorio al auditor fallido», se reintenta al día siguiente y la tarea termina con código 1. La salida de consola no trae correos.

#### Scenario: Recordatorio
<!-- test: sin prueba automática en el PHP (script de consola) -->
- **CUANDO** un seguimiento es dentro de 3 días hábiles y no tiene recordatorio
- **ENTONCES** sale el correo al auditor y la siguiente corrida no lo repite

---

### Requirement: «Prestadores con hallazgos»
<!-- id: ControladorHallazgos.lista -->
<!-- entidades: Punto, Visita, VisitaHallazgo, OplRespuesta -->
<!-- impuesto_en: controladores/hallazgos.controlador.php lista(); ajax/hallazgos.ajax.php -->
<!-- decision: 6.8 | 36 | duda 11.3 -->

La pantalla DEBE listar, por prestador, la última visita cerrada con hallazgos de cada cadena (sin un seguimiento cerrado después): número, fecha, auditor, N° de seguimiento y origen, hallazgos con fin y estado del plan y la respuesta del OPL, acciones abiertas (sin levantados), si el usuario es el líder, plazos y firma del plan, la siguiente visita y la situación: `programada` (hay seguimiento), `plan_cerrado` (sin abiertas), `escalar` (llegó al máximo) o `sin_programar`. Entran Auditor (sus visitas; con otro perfil conserva las de su equipo), Coordinador y DIGSA (todo), Sanidad (su fuerza) y ESM (su establecimiento); el OPL no (403). Solo Auditor y Coordinador abren el checklist; solo el líder resuelve objeciones.

#### Scenario: Alcance
<!-- test: tests/e2e_local.php:1169, tests/e2e_local.php:1171, tests/e2e_local.php:1173, tests/e2e_local.php:1176 -->
- **CUANDO** consultan DIGSA, Sanidad EJC, el ESM, Sanidad ARC y el OPL
- **ENTONCES** los tres primeros ven el prestador (el ESM solo el suyo y sin abrir el checklist), ARC no lo ve y el OPL recibe 403

#### Scenario: El líder ve la objeción
<!-- test: tests/e2e_local.php:1066, tests/ui/ui_fase4.js:80 -->
- **CUANDO** el líder abre la pantalla con una objeción pendiente
- **ENTONCES** ve la columna «Respuesta del OPL» con estado `objetado` y `es_lider = true`

#### Scenario: Lista y fecha del seguimiento
<!-- test: tests/ui/ui_seg.js:43 -->
- **CUANDO** el auditor abre un prestador
- **ENTONCES** ve sus hallazgos, «Fin del plan» y la visita de seguimiento

---

### Requirement: El ESM no ve las objeciones del OPL ni sus soportes
<!-- id: ControladorHallazgos.lista | ControladorHallazgos.servirSoporte -->
<!-- entidades: OplRespuesta, OplSoporte -->
<!-- impuesto_en: controladores/hallazgos.controlador.php lista(), servirSoporte() -->
<!-- decision: 2026-10-20 12.2 | duda 12.2 (el PHP se los mostraba) -->

Para el perfil ESM, «Prestadores con hallazgos» NO DEBE traer la justificación de la objeción, el motivo de la decisión del líder ni los soportes del OPL, y la descarga de un soporte DEBE responder 404. DIGSA, Sanidad, Coordinador y Auditor los siguen viendo.

#### Scenario: ESM
<!-- test: sin prueba en el PHP (decisión nueva) -->
- **CUANDO** el ESM consulta un hallazgo objetado y pide su soporte
- **ENTONCES** la respuesta no trae justificación ni soportes y la descarga responde 404

#### Scenario: DIGSA
<!-- test: tests/e2e_local.php:1178 -->
- **CUANDO** la DIGSA descarga el soporte de la objeción
- **ENTONCES** recibe el PDF

<!-- incierto: si el ESM ve que el hallazgo está «objetado» (solo el estado) o un estado neutro hasta que el líder decida. Recomendación: no mostrar la objeción; mostrar el hallazgo como vigente o levantado. -->

---

### Requirement: Cierre del plan al levantar el último hallazgo
<!-- id: ControladorOpl.resolver (levanta) -->
<!-- entidades: OplPlan, Visita, VisitaHistorial -->
<!-- impuesto_en: controladores/opl.controlador.php resolver(); app/Support/Opl.php vigentes(); modelos/visitas.modelo.php seguimientoDe() -->
<!-- decision: 2026-10-20 11.7 | 17 -->

Cuando el líder levanta una objeción y ya no queda ningún hallazgo vigente, en la misma transacción el sistema DEBE: cancelar la visita de seguimiento pendiente (si está por ejecutar), con historial «Todos los hallazgos de la visita anterior fueron levantados por el auditor líder»; **cerrar el plan con su registro de cierre** (fecha, quién, motivo «hallazgos levantados»); y después **notificar solo al OPL** (no al punto ni a otros) que el plan quedó cerrado. Levantar uno de varios no cancela ni cierra nada.

#### Scenario: Último hallazgo
<!-- test: tests/e2e_local.php:1091 (cancelación); cierre y aviso: sin prueba en el PHP -->
- **CUANDO** el líder levanta el único hallazgo
- **ENTONCES** el seguimiento queda `cancelado`, el plan «cerrado» con su registro y sale un correo solo a los correos del OPL

#### Scenario: Uno de dos
<!-- test: tests/e2e_local.php:1099 -->
- **CUANDO** el líder levanta uno de dos hallazgos
- **ENTONCES** el seguimiento no se cancela

<!-- incierto: si el cierre del plan cuando el seguimiento termina sin acciones abiertas (situación «plan_cerrado») también crea el registro de cierre y avisa solo al OPL. La 11.7 habla de «levantar el último hallazgo»; recomendación: mismo registro y mismo aviso en ambos casos. -->

---

### Invariant: Un seguimiento vigente por visita de origen
<!-- entidades: Visita -->
<!-- impuesto_en: controladores/checklist.controlador.php cerrar() (seguimientoDe antes de programar); modelos/visitas.modelo.php seguimientoDe() -->
<!-- verificado_por: tests/e2e_local.php:760 -->

Cada visita DEBE tener a lo sumo un seguimiento no cancelado (`origen_id`), y su `seguimiento_n` DEBE ser el de la visita de origen + 1, sin pasar de `max_seguimientos`.

---

### Invariant: Ordinales y acciones abiertas
<!-- entidades: Plan5W2H -->
<!-- impuesto_en: app/Support/Seguimiento.php ordinal(), ESTADOS_CERRADOS_PLAN -->
<!-- verificado_por: tests/seguimiento_test.php:45 -->

Los ordinales DEBEN ser Primera…Quinta y «N.ª» después; una acción está cerrada solo en Completado o Cancelado.

---

### Invariant: El seguimiento mantiene los componentes y la lista de obligaciones
<!-- entidades: VisitaChecklist -->
<!-- impuesto_en: app/Support/Seguimiento.php cambiosBloqueados() -->
<!-- verificado_por: tests/seguimiento_test.php:62, tests/seguimiento_test.php:64, tests/e2e_local.php:506 -->

En un seguimiento NO DEBEN cambiar los componentes, la marca de plan vigente, la lista de obligaciones ni la calificación de lo que no es hallazgo por verificar; no se carga otra minuta (tests/ui/ui_seg.js:65).

---

## Datos que la reescritura Java debe agregar

1. **`visitas.origen_id`** (FK a `visitas`, nulo en las que no son seguimiento) y **`visitas.seguimiento_n`** (smallint ≥ 1), con índice único parcial `(origen_id) WHERE estado <> 'cancelada'`.
2. **`visitas_hallazgos`** y **`visitas_plan`** (5W2H con `fin`, `estado`, `avance`, `prorroga_justificacion`) de `flujo-opl`: sin ellos no hay fecha de seguimiento ni editables. También las obligaciones de la minuta (`visitas_obligaciones`) y la subvalidación de `visitas_respuestas`, porque los hallazgos incluyen obligaciones NC.
3. **Prórrogas:** `prorrogas` (id, visita_id del seguimiento, origen_visita_id, clave, fin_anterior, fin_nuevo, justificacion ≥10, estado `solicitada|aprobada|rechazada`, coord_usuario_id, coord_decision, coord_fecha, digsa_usuario_id, digsa_decision, digsa_fecha, motivo_rechazo) + permiso de aprobación para Coordinador y DIGSA y eventos de bitácora `prorroga_aprobada_coordinador`, `prorroga_aprobada_digsa`, `prorroga_rechazada`.
4. **Estado y cierre del plan** (de `flujo-opl`): `cerrado_en`, `cerrado_por`, `motivo_cierre` y el estado `reportado_digsa`; `reportes_digsa` con motivo `plan_incumplido`.
5. **Estado inicial del seguimiento:** como Java guarda filas y no un JSON, al crear el seguimiento se copian del origen las respuestas que no son hallazgo (solo lectura), las filas del plan (con quién y cómo del OPL) y las obligaciones, con los hallazgos en blanco; los componentes del seguimiento (`visitas_componentes`) se fijan a los de la visita de origen.
6. **Archivos anteriores:** consulta de `archivos` de la visita de origen filtrada por las claves editables (no se copian).
7. **Tareas programadas:** recordatorio diario (usa `recordatorio_seguimiento_habiles`; marca en el historial) y escalamiento al llegar a `max_seguimientos`.
8. **«Prestadores con hallazgos»:** endpoint y pantalla con el alcance de `perfiles-alcance`, y el filtro del ESM (12.2).
9. **`ServicioActa`:** asunto y título según `nombreActa` (hoy siempre dice «visita de seguimiento»), texto del seguimiento bajo el 5W2H, lista de prórrogas y programación del seguimiento en la transacción del cierre.

<!-- pendiente: vistas/js/hallazgos.js y vistas/modulos/hallazgos/hallazgos.php (pantalla), modelos/riesgo.modelo.php (uso de los hallazgos en el riesgo), controladores/agenda.controlador.php guardarEquipoYReparto() y enviarNotificacion() (ver equipo-reparto y agenda-notificacion) -->
