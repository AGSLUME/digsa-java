# Especificación: checklist

> Extraída por spec-miner el 2026-10-20 del PHP de referencia (develop, commit db2e330).
> Fuentes: app/Support/EstadoChecklist.php, app/Support/ResumenChecklist.php, app/Support/Seguimiento.php (componentesActivos, hallazgos, cambiosBloqueados), controladores/checklist.controlador.php (acceso, datosPagina, imponerAgenda, guardar, problemaReglas, conservarOpl, estadoActual, noAdmite, naDeLaVisita, enviar: solo faltantes), modelos/checklist.modelo.php, ajax/checklist.ajax.php, Database/Structure/003_checklist.sql, Database/Structure/006_checklist_visita.sql, Database/Data/003_checklist_v6.sql (estructura y conteos), tests/estado_checklist_test.php, tests/resumen_checklist_test.php, tests/seguimiento_test.php (hallazgos), tests/e2e_local.php, docs/decisiones_2026-10-15.md, docs/dudas_pendientes.md.
> Relacionadas: el guardado por partes y quién escribe qué en `equipo-reparto`; archivos y soportes en `archivos`; extracción de la minuta en `minuta`; acta, firmas y envío en `acta-envio`; estado inicial y prórrogas del seguimiento en `seguimiento`; versión publicada inmutable en `catalogos-festivos-plazos`.

---

### Requirement: Catálogo del checklist de la visita
<!-- id: ModeloChecklist.catalogo -->
<!-- entidades: ChecklistVersion, Componente, Item, Vacio, GuiaObligacion -->
<!-- impuesto_en: modelos/checklist.modelo.php ModeloChecklist::catalogo() -->

El checklist de una visita DEBE construirse con la versión con que se ejecuta (`visitas.checklist_version`): componentes en su orden con código, nombre, descripción, grupo, `especial`, escala propia (JSON), encabezado de la guía, unidades y fuente; cada ítem en su orden con código, punto, criterio, fuente, origen, requisito, evidencia, aplica, condición, prioridad (vacío de mayor riesgo), medición (JSON) y subvalidación (JSON); además los vacíos de mayor riesgo y la guía de calificación de obligaciones (Cumple, No cumple, No aplica, fuente). Los campos vacíos no se envían.

#### Scenario: Versión fijada al iniciar
<!-- test: tests/e2e_local.php:309 -->
- **CUANDO** se inicia una visita
- **ENTONCES** queda con `checklist_version = 'v6'` y su checklist usa esa versión aunque después se publique otra

---

### Requirement: Componentes activos de la visita
<!-- id: Seguimiento.componentesActivos -->
<!-- entidades: Componente -->
<!-- impuesto_en: app/Support/Seguimiento.php Seguimiento::componentesActivos(); app/Support/ResumenChecklist.php calcular() -->
<!-- duda: 6.11 -->

Un componente DEBE estar activo si así lo marcó el auditor en `comps` (lo marcado a mano manda, activo o inactivo); si no está marcado, se activa solo cuando aplica a la unidad del punto y, si es especial `plan` (C02 "Seguimiento a planes de mejoramiento"), además la visita es de seguimiento a plan (`tipo = plan`) o el perfil declara plan de mejoramiento vigente (`perfil.planVigente`).

#### Scenario: Componente de plan sin plan vigente
<!-- test: tests/resumen_checklist_test.php:49 -->
- **CUANDO** la visita es inicial, sin plan vigente
- **ENTONCES** no cuentan el componente de plan ni el de otra unidad

#### Scenario: Ajuste manual y visita de seguimiento a plan
<!-- test: tests/resumen_checklist_test.php:53 -->
- **CUANDO** el auditor desactiva C01 y activa C03 (de otra unidad) en una visita de tipo `plan`
- **ENTONCES** C01 no cuenta, C03 sí y el componente de plan se activa solo

#### Scenario: El seguimiento no activa el componente de plan
<!-- test: tests/seguimiento_test.php:51 -->
<!-- duda: 6.11 -->
- **CUANDO** se arma el estado inicial de un seguimiento
- **ENTONCES** los componentes quedan fijos con los de la visita anterior (el de plan, desactivado)

---

### Requirement: Quién lee y quién escribe el checklist
<!-- id: ControladorChecklist.acceso -->
<!-- entidades: Visita, VisitaAuditor -->
<!-- impuesto_en: controladores/checklist.controlador.php acceso(), escritura(); ajax/checklist.ajax.php Auth::requerirAjax(['Auditor','Coordinador']) -->
<!-- decision: 2b | 2.2 (novena ronda) -->

Solo los integrantes del equipo y el coordinador (o administrador) DEBEN abrir el checklist, y solo con la visita en ejecución o cerrada; escriben los integrantes con la visita en ejecución (cada uno en lo suyo, ver `equipo-reparto`) y el coordinador solo si la visita fue reabierta; todo lo demás es solo lectura con el motivo ("Visita cerrada: el acta ya se envió." o "Consulta: solo los auditores de la visita modifican el checklist…").

#### Scenario: Coordinador durante la ejecución
<!-- test: tests/e2e_local.php:339 -->
- **CUANDO** el coordinador guarda el checklist de una visita en ejecución no reabierta
- **ENTONCES** recibe 403

#### Scenario: Visita sin iniciar
- **CUANDO** se abre el checklist de una visita programada o notificada
- **ENTONCES** se responde "La visita aún no se ha iniciado." (404 en la página)

#### Scenario: Visita cerrada
<!-- test: tests/e2e_local.php:401 -->
<!-- test: tests/e2e_local.php:402 -->
- **CUANDO** se guarda o se sube un soporte a una visita cerrada
- **ENTONCES** recibe 403 "La visita está cerrada: el acta ya se envió y no se puede modificar."

---

### Requirement: Forma válida del estado que envía el navegador
<!-- id: EstadoChecklist.problema -->
<!-- entidades: VisitaChecklist -->
<!-- impuesto_en: app/Support/EstadoChecklist.php EstadoChecklist::problema(), RE_OBLIGACION, RE_ITEM, RE_FILA_W5; controladores/checklist.controlador.php guardar() MAX_ESTADO -->

El servidor NO DEBE confiar en el JSON del checklist: DEBE rechazar con 413 un estado de más de 15 MB y con 422 ("El checklist no tiene un formato válido: …") uno que no tenga `visita` y `perfil` como objetos; cuyas secciones `comps`, `resp`, `minutas`, `prestadores`, `w5`, `cierre` o `envio` no sean objetos; con componentes que no cumplan `^[A-Z0-9][A-Z0-9\-]{1,15}$` o con valor no booleano; con respuestas de ítem con código no válido o que no sean objeto; con minutas de un prestador distinto de `opl` o `fa`, páginas no enteras u obligaciones que no sean lista; con obligaciones cuyo id no cumpla `^OB-(OPL|FA)-\d{2,3}$`; con filas del 5W2H cuya clave no sea `ob:OB-…` o `it:…`; o con calificaciones fuera de '', C, NC, NA, marca de intervención inmediata no booleana o subvalidación no textual.

#### Scenario: Estado válido o recién creado
<!-- test: tests/estado_checklist_test.php:29 -->
<!-- test: tests/estado_checklist_test.php:30 -->
- **CUANDO** llega un estado completo válido, o uno con secciones vacías
- **ENTONCES** se acepta

#### Scenario: Identificadores con HTML
<!-- test: tests/estado_checklist_test.php:34 -->
<!-- test: tests/estado_checklist_test.php:37 -->
<!-- test: tests/e2e_local.php:341 -->
- **CUANDO** el id de una obligación o la clave de una fila del 5W2H trae HTML
- **ENTONCES** responde 422 (evita XSS en la pantalla del coordinador)

#### Scenario: Tipos que rompían el resumen
<!-- test: tests/estado_checklist_test.php:42 -->
<!-- test: tests/estado_checklist_test.php:45 -->
<!-- test: tests/estado_checklist_test.php:48 -->
<!-- test: tests/e2e_local.php:343 -->
- **CUANDO** `comps` es texto, una calificación es "SI" o un arreglo, o la lista de obligaciones es un objeto
- **ENTONCES** responde 422 antes de guardar

---

### Requirement: Calificaciones y habilitación
<!-- id: EstadoChecklist.naSinCondicion -->
<!-- entidades: Item, VisitaChecklist -->
<!-- impuesto_en: app/Support/EstadoChecklist.php naSinCondicion(); controladores/checklist.controlador.php problemaReglas(), naDeLaVisita() -->
<!-- decision: Agentes 1 (novena ronda) -->
<!-- duda: 10.9 -->

Cada ítem y obligación DEBE calificarse C (Cumple), NC (No cumple) o NA (No aplica), o quedar sin calificar (''); en los componentes con escala propia (C00 "Validación de soportes de habilitación": C = "Está habilitado", NC = "No está habilitado"), "No aplica" DEBE admitirse solo en los ítems con condición: el servidor DEBE rechazar con 422 el guardado de un componente activo que tenga NA en un ítem sin condición, nombrando los ítems; en un seguimiento solo cuentan los ítems que se verifican de nuevo.

#### Scenario: NA sin condición
<!-- test: tests/estado_checklist_test.php:90 -->
<!-- test: tests/e2e_local.php:361 -->
- **CUANDO** se guarda HB-01 (sin condición) con NA
- **ENTONCES** responde 422 "En la validación de habilitación solo admiten "No aplica" los ítems con condición. Califique HB-01 como habilitado o no habilitado."

#### Scenario: NA permitido
<!-- test: tests/estado_checklist_test.php:91 -->
<!-- test: tests/estado_checklist_test.php:92 -->
<!-- test: tests/estado_checklist_test.php:93 -->
- **CUANDO** el NA está en un ítem con condición, en un componente sin escala propia, o el componente de habilitación está desactivado
- **ENTONCES** se acepta

#### Scenario: Habilitación en trámite
<!-- test: tests/resumen_checklist_test.php:44 -->
- **CUANDO** un ítem con subvalidación queda "No está habilitado" y la subvalidación ("¿El prestador presenta el soporte de inscripción en el REPS?") es "SI"
- **ENTONCES** queda en trámite: no es hallazgo

---

### Requirement: Obligaciones de la minuta bloqueadas
<!-- id: EstadoChecklist.cambiosObligaciones -->
<!-- entidades: VisitaChecklist, Obligacion -->
<!-- impuesto_en: app/Support/EstadoChecklist.php cambiosObligaciones(); controladores/checklist.controlador.php problemaReglas(), guardar() -->
<!-- decision: 5.2 | 5.3 | 5.4 | 5.5 (octava ronda) | Sin obligaciones a mano (novena ronda) -->
<!-- duda: 10.9 -->

Al guardar, comparando con el estado anterior (o con el estado de partida del seguimiento) y por cada minuta (OPL y farmacia alterna), el servidor DEBE rechazar con 422: quitar una obligación extraída (`auto`); cambiar el texto o la fuente de una extraída (se comparan sin espacios extremos); y agregar una obligación a mano, o una "extraída" que llega sin una lectura nueva de la minuta. Calificar una obligación y describir su hallazgo sí se permite. Mientras la minuta está en estado `leyendo` (en el guardado nuevo o en el anterior) no se compara; al pasar a `leyendo` se cuentan las extraídas reemplazadas y el historial DEBE registrar "Minuta reemplazada" con cuántas se descartaron. Las obligaciones agregadas a mano antes de esta regla se conservan y se pueden quitar.

#### Scenario: Quitar una extraída
<!-- test: tests/estado_checklist_test.php:58 -->
- **CUANDO** desaparece OB-OPL-02 extraída
- **ENTONCES** se detecta como quitada y se rechaza: "Las obligaciones extraídas de la minuta no se pueden quitar (…). Si una no corresponde a una obligación, califíquela "No aplica" y explíquelo en el hallazgo."

#### Scenario: Editar el texto
<!-- test: tests/estado_checklist_test.php:68 -->
<!-- test: tests/estado_checklist_test.php:70 -->
<!-- test: tests/e2e_local.php:475 -->
- **CUANDO** cambia el texto o la fuente de OB-OPL-01 extraída
- **ENTONCES** responde 422 nombrando OB-OPL-01

#### Scenario: Agregar a mano
<!-- test: tests/estado_checklist_test.php:77 -->
<!-- test: tests/estado_checklist_test.php:80 -->
<!-- test: tests/e2e_local.php:358 -->
- **CUANDO** llega una obligación nueva a mano, o una marcada `auto` sin lectura nueva
- **ENTONCES** responde 422 "No se agregan obligaciones a mano (…): solo las que se extraen de la minuta."

#### Scenario: Cargar otra minuta
<!-- test: tests/estado_checklist_test.php:60 -->
<!-- test: tests/estado_checklist_test.php:62 -->
<!-- test: tests/estado_checklist_test.php:83 -->
- **CUANDO** la minuta pasa a `leyendo` y luego llega la lista nueva
- **ENTONCES** se registran 2 reemplazadas una sola vez y las obligaciones leídas no cuentan como agregadas

#### Scenario: Calificar
<!-- test: tests/estado_checklist_test.php:74 -->
- **CUANDO** se califica NC una obligación extraída y se describe el hallazgo
- **ENTONCES** se acepta

<!-- incierto: la regla del texto bloqueado se puede saltar pasando la minuta a "leyendo", porque la extracción ocurre en el navegador (prioridad 2-3 de la fase 3, no corregida). -->

---

### Requirement: Datos de la visita impuestos por la agenda
<!-- id: ControladorChecklist.imponerAgenda -->
<!-- entidades: Visita, Punto, VisitaChecklist -->
<!-- impuesto_en: controladores/checklist.controlador.php imponerAgenda(), datosPagina() -->
<!-- decision: 2 -->
<!-- duda: 2.1 -->

En cada guardado el servidor DEBE sobrescribir, con los de la agenda y el maestro, número, tipo, lugar (punto), fuerza, auditor líder, equipo (los otros auditores), departamento y municipio del punto, unidad y modalidad del punto; la dirección NO se impone (el auditor la corrige en la visita sin cambiar el maestro); la pantalla DEBE precargar además el alcance, la fecha de la última notificación enviada y el inicio (fecha y hora programadas, 08:00 si no hay hora); el alcance, las fechas de inicio y fin y el personal que atiende se editan.

#### Scenario: Municipio y unidad
<!-- test: tests/e2e_local.php:353 -->
- **CUANDO** el auditor guarda con otro municipio y otra unidad
- **ENTONCES** quedan los del punto de la agenda

---

### Requirement: Guardado con revisión
<!-- id: ControladorChecklist.guardar -->
<!-- entidades: VisitaChecklist, VisitaResumen -->
<!-- impuesto_en: controladores/checklist.controlador.php guardar(); modelos/checklist.modelo.php guardarEstado(), marcarRevision(), estadoCrudo() -->
<!-- decision: 2.9 (sin modo sin conexión) -->

Quien escribe todo el formulario DEBE enviar el estado completo con la revisión que leyó (0 si no hay); el servidor valida la forma, impone los datos de la agenda, aplica las reglas de obligaciones, habilitación, 5W2H y seguimiento, calcula el resumen antes de escribir y, en una transacción que bloquea primero la visita y luego el checklist, guarda solo si la revisión sigue siendo la leída y la visita admite cambios: revisión nueva = anterior + 1 (la primera es 1), con fecha y usuario, marca de "todo el formulario" y resumen; un guardado igual al que ya está, con revisión igual a la leída o la siguiente, DEBE responder éxito sin escribir (la respuesta anterior se perdió); si otra pestaña u otro equipo guardó antes, DEBE responder 409 "El checklist se modificó en otra pestaña u otro equipo. Recargue la página…".

#### Scenario: Primer guardado y repetido
<!-- test: tests/e2e_local.php:345 -->
<!-- test: tests/e2e_local.php:347 -->
- **CUANDO** el auditor guarda con revisión 0 y repite el mismo guardado
- **ENTONCES** ambos responden 200 con revisión 1

#### Scenario: Revisión vieja con otro contenido
<!-- test: tests/e2e_local.php:349 -->
- **CUANDO** guarda con revisión 0 un contenido distinto
- **ENTONCES** responde 409

#### Scenario: Sin revisión
- **CUANDO** el guardado no trae revisión
- **ENTONCES** se toma −1 y responde 409

<!-- incierto: ante el 409 la pantalla ofrece descargar una copia `.json` de los cambios (validación D-F, punto 4); no se leyó el JS. -->

---

### Requirement: La visita no admite cambios
<!-- id: ControladorChecklist.noAdmite -->
<!-- entidades: Visita, VisitaEnvio -->
<!-- impuesto_en: controladores/checklist.controlador.php noAdmite(), envioEnCurso(); modelos/checklist.modelo.php abierta(), RESERVA_MINUTOS, SQL_ENVIADA_SIN_CERRAR -->

Una escritura condicionada que no se aplicó DEBE explicarse: 423 "El acta se está enviando: espere a que termine." si hay un envío pendiente de menos de 10 minutos; 423 "El acta ya se envió y falta cerrar la visita…" si el acta de esta ronda (después de la última reapertura) ya salió y la visita no se cerró; 403 "La visita está cerrada…" en otro caso.

#### Scenario: Envío en curso
- **CUANDO** un auditor guarda mientras el líder envía el acta
- **ENTONCES** responde 423 y no se guarda

---

### Requirement: Quién y cómo del plan los pone el OPL
<!-- id: ControladorChecklist.conservarOpl -->
<!-- entidades: VisitaChecklist, PlanW5 -->
<!-- impuesto_en: controladores/checklist.controlador.php conservarOpl(), W5_OPL -->
<!-- decision: 7 -->

En cada guardado del checklist, los campos `quien` y `como` de cada fila del 5W2H DEBEN tomarse del estado anterior (vacíos si no había): el auditor no los escribe; los diligencia el OPL.

#### Scenario: El auditor escribe quién
- **CUANDO** el auditor envía una fila del 5W2H con `quien` lleno
- **ENTONCES** se guarda el `quien` anterior (vacío en una visita nueva)

---

### Requirement: En el seguimiento solo se recalifican los hallazgos
<!-- id: Seguimiento.cambiosBloqueados -->
<!-- entidades: VisitaChecklist -->
<!-- impuesto_en: app/Support/Seguimiento.php cambiosBloqueados(); controladores/checklist.controlador.php guardar(), seguimiento() -->
<!-- decision: 6.5 | 6.11 -->

En una visita de seguimiento el guardado DEBE rechazarse con 422 ("En la visita de seguimiento solo se califican de nuevo los hallazgos de la visita anterior. No se puede cambiar: …", hasta 10) si cambia un componente, la marca de plan vigente, la lista de obligaciones, o la calificación (o la subvalidación) de lo que no es hallazgo por verificar; los hallazgos que el líder levantó ante una objeción del OPL no se verifican de nuevo.

#### Scenario: Activar un componente
<!-- test: tests/seguimiento_test.php:62 -->
- **CUANDO** se activa C02 en el seguimiento
- **ENTONCES** se detecta "componente C02"

---

### Requirement: Resumen de resultados calculado en el servidor
<!-- id: ResumenChecklist.calcular -->
<!-- entidades: VisitaResumen, VisitaResumenComponente, VisitaResumenVacio -->
<!-- impuesto_en: app/Support/ResumenChecklist.php calcular(); modelos/checklist.modelo.php guardarResumen() -->
<!-- decision: Agentes 2 (novena ronda) | 3.5 (octava ronda) -->
<!-- duda: 10.9 -->

El resumen DEBE calcularse en el servidor, sin fallar ante tipos inválidos: cuenta C, NC, NA y sin calificar de las obligaciones de la minuta del prestador visitado (`fa` si la unidad es FA, si no `opl`, como parte `OBL`) y de los ítems de los componentes activos, total y por componente; hallazgos = NC que no están en trámite; intervención inmediata y acciones del 5W2H abiertas (estado distinto de Completado o Cancelado; sin fila = Pendiente) entre los hallazgos; habilitación (componentes con escala): habilitados, en trámite y no habilitados; vacíos de mayor riesgo con NC, sin contar los que están en trámite; y el prestador evaluado (nombre del OPL o de la farmacia alterna). En un seguimiento solo cuenta lo que se verificó. El resumen DEBE guardarse en la misma transacción que el estado y al enviar el acta.

#### Scenario: Totales y hallazgos
<!-- test: tests/resumen_checklist_test.php:43 -->
<!-- test: tests/resumen_checklist_test.php:44 -->
<!-- test: tests/resumen_checklist_test.php:45 -->
<!-- test: tests/resumen_checklist_test.php:46 -->
- **CUANDO** hay 3 obligaciones (C, NC inmediata, sin calificar) y 5 ítems del ESM (NC en trámite, NC, NC prioritario, NA, C) más un NC de otra unidad
- **ENTONCES** C 2, NC 4, NA 1, sin calificar 1; 3 hallazgos; 1 inmediata; 2 acciones abiertas

#### Scenario: Habilitación y vacíos
<!-- test: tests/resumen_checklist_test.php:47 -->
<!-- test: tests/resumen_checklist_test.php:48 -->
<!-- test: tests/resumen_checklist_test.php:68 -->
- **CUANDO** HB-01 está en trámite, HB-02 no habilitado y P01 (vacío 3) en NC
- **ENTONCES** habilitación 0 / 1 / 1 y vacíos `{'3 · Dosis unitaria': 1}`; un vacío en trámite no cuenta

#### Scenario: Farmacia alterna
<!-- test: tests/resumen_checklist_test.php:58 -->
- **CUANDO** la unidad es FA
- **ENTONCES** solo cuenta la minuta de la farmacia y el prestador es la farmacia

#### Scenario: Estado mal formado
<!-- test: tests/estado_checklist_test.php:99 -->
- **CUANDO** el estado guardado trae tipos inválidos
- **ENTONCES** el resumen no falla y cuenta el ítem como sin calificar

#### Scenario: Seguimiento
<!-- test: tests/seguimiento_test.php:69 -->
- **CUANDO** se resume un seguimiento
- **ENTONCES** solo cuenta lo verificado

---

### Requirement: Hallazgos de la visita
<!-- id: Seguimiento.hallazgos -->
<!-- entidades: VisitaHallazgo -->
<!-- impuesto_en: app/Support/Seguimiento.php hallazgos(); modelos/checklist.modelo.php guardarHallazgos() -->

Los hallazgos DEBEN ser las obligaciones de la minuta del prestador visitado y los ítems de los componentes activos calificados NC, sin la habilitación en trámite (misma regla del resumen), cada uno con clave (`ob:…` o `it:…`), código, origen (`obligacion` o `item`), texto (obligación o punto del ítem), hallazgo descrito, fecha fin válida y estado de su acción en el 5W2H (Pendiente si no hay); se guardan al cerrar la visita.

#### Scenario: Obligación e ítem
<!-- test: tests/seguimiento_test.php:37 -->
- **CUANDO** hay una obligación NC, un ítem NC y una habilitación en trámite
- **ENTONCES** los hallazgos son `ob:OB-OPL-01` e `it:HB-02`

---

### Requirement: Calificar todo antes de enviar el acta
<!-- id: ResumenChecklist.faltantesEnvio -->
<!-- entidades: VisitaChecklist -->
<!-- impuesto_en: app/Support/ResumenChecklist.php faltantesEnvio(); controladores/checklist.controlador.php enviar() -->
<!-- decision: 2.4 y 3.5 (octava ronda) -->

El acta NO DEBE enviarse si queda alguna obligación o ítem activo sin calificar ("Calificar todo: quedan N…"), sin firma del auditor en el acta, sin firma de al menos una persona que atiende (ESM u OPL), con hallazgos sin las dos firmas del 5W2H, con acciones abiertas sin fecha fin, o con NA de habilitación sin condición; el resto de requisitos del envío está en `acta-envio`.

#### Scenario: Faltan firmas
<!-- test: tests/resumen_checklist_test.php:60 -->
<!-- test: tests/resumen_checklist_test.php:62 -->
- **CUANDO** no hay firmas y hay 2 hallazgos; luego se firma todo
- **ENTONCES** faltan 3 requisitos; después ninguno

#### Scenario: Sin calificar
<!-- test: tests/resumen_checklist_test.php:70 -->
<!-- test: tests/e2e_local.php:363 -->
- **CUANDO** quedan 3 sin calificar
- **ENTONCES** se pide "Calificar todo" y el envío responde 422 "sin calificar"

---

### Requirement: Estado actual para el equipo
<!-- id: ControladorChecklist.estadoActual -->
<!-- entidades: VisitaChecklist, VisitaArchivo -->
<!-- impuesto_en: controladores/checklist.controlador.php estadoActual(), datosPagina(); ajax/checklist.ajax.php accion=estado -->

Quien tiene acceso a la visita DEBE poder pedir el estado guardado, su revisión y los archivos vigentes por clave (también los de los demás auditores); la página del checklist DEBE recibir el catálogo, la visita con permisos, equipo y reparto, el estado guardado (en un seguimiento sin estado, el de partida), los archivos, las actas y los envíos.

#### Scenario: Ajeno
<!-- test: tests/e2e_local.php:699 -->
- **CUANDO** quien no es del equipo pide el estado
- **ENTONCES** recibe 403

---

### Invariant: Un checklist por visita
<!-- entidades: VisitaChecklist -->
<!-- impuesto_en: Database/Structure/006_checklist_visita.sql visita_checklist PK (visita_id) -->

Cada visita DEBE tener a lo sumo un estado de checklist (JSON con la estructura del mockup v6: `visita`, `perfil`, `comps`, `resp`, `minutas`, `prestadores`, `w5`, `cierre`, `envio`), con su revisión, el mapa usuario → última revisión guardada (`*` = todo el formulario), fecha y usuario del último guardado.

---

### Invariant: Escrituras solo con la visita abierta
<!-- entidades: VisitaChecklist, VisitaArchivo, VisitaActa -->
<!-- impuesto_en: modelos/checklist.modelo.php abierta(), guardarEstado(), agregarArchivo(), eliminarArchivos(), agregarActa() -->
<!-- duda: 4.1 -->

Toda escritura del checklist, de sus archivos y de sus actas DEBE comprobar en la misma sentencia SQL que la visita está en ejecución, que no hay un envío del acta pendiente de menos de 10 minutos y que el acta de esta ronda no salió ya sin cerrar la visita.

---

### Invariant: Resumen persistido
<!-- entidades: VisitaResumen -->
<!-- impuesto_en: Database/Structure/006_checklist_visita.sql visita_resumen, visita_resumen_componentes, visita_resumen_vacios -->

El resumen de cada visita DEBE guardarse por reemplazo completo: totales (c, nc, na, pend, hallazgos, inmediata, w5_abiertos, hab, tramite, no_hab, prestador, calculado), una fila por componente (OBL = obligaciones) y una por vacío con NC; reportes y dashboard leen de aquí, no del navegador.

---

### Invariant: Estructura del checklist v6
<!-- entidades: ChecklistVersion, Componente, Item -->
<!-- impuesto_en: Database/Data/003_checklist_v6.sql; Database/Structure/003_checklist.sql -->

La versión v6 DEBE tener 21 componentes y 343 ítems: C00 Validación de soportes de habilitación (ESM, FA; escala `{"C":"Está habilitado","NC":"No está habilitado"}`, guía "Está habilitado si"; 9 ítems HB-01…HB-09, con subvalidación REPS en algunos), C01 Seguimiento a la operación del OPL (ESM, FA; 11), C02 Seguimiento a planes de mejoramiento (ESM, FA, NC; especial `plan`; 4), C03 Nivel central del OPL (NC; 8), C04 Farmacia alterna (FA; 5), C05 Capacidad instalada e infraestructura (ESM, FA; 63), C06 Seguridad física (ESM, FA, NC; 5), C07 Selección, adquisición y recepción técnica (ESM, FA, NC; 26), C08 Almacenamiento e inventarios (ESM, FA, NC; 23), C09 Cadena de frío (ESM, FA, NC; 13), C10 Oportunidad, pendientes y entrega a domicilio (ESM, FA; 29), C11 Servicio farmacéutico contratado y legalidad (ESM, FA; 10), C12 Talento humano y dirección técnica (ESM, FA; 16), C13 Dispensación (ESM, FA; 13), C14 Distribución intrahospitalaria y dosis unitaria (ESM; 14), C15 Transporte y distribución física (ESM, FA, NC; 10), C16 Devoluciones, retiro del mercado y destrucción (ESM, FA, NC; 13), C17 Comité de Farmacia y Terapéutica (ESM; 12), C18 Atención farmacéutica y farmacovigilancia (ESM, FA; 19), C19 Gestión de calidad, información y horarios (ESM, FA, NC; 16), C20 Farmacia alterna: establecimiento, inyectología y glicemia (FA; 24); 5 vacíos de mayor riesgo (1 Recepción técnica y muestras médicas, 2 Control de existencias e inventarios, 3 Dosis unitaria, 4 Comité de Farmacia y Terapéutica, 5 Servicio farmacéutico contratado). El contenido de cada ítem se toma del script, no se reescribe.

<!-- incierto: qué ítems de C00 tienen condición (permiten "No aplica") y cuáles tienen subvalidación: se ve que HB-01 y HB-02 tienen subvalidación; no se leyó ítem por ítem. -->
<!-- incierto: las unidades por componente se leyeron del script con grep; conviene comprobarlas al cargar el script en la base nueva. -->
<!-- duda: 2.9 (sin conexión no guarda: avisa y reintenta) -->
<!-- pendiente: vistas/checklist (JS: autoguardado, copia .json ante 409, filtro "ver lo de los demás"), app/Support/Seguimiento.php (estadoInicial y prórrogas: capacidad seguimiento), controladores/checklist.controlador.php líneas 587-1088 (acta y envío: capacidad acta-envio), tests/ui/ui_de.js -->
