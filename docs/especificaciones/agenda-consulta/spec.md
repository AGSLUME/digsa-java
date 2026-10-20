# Especificación: agenda-consulta

> Extraída por spec-miner el 2026-10-20 del PHP de referencia (develop, commit db2e330).
> Parte de `agenda-notificacion`, dividida por tamaño: aquí van las alertas, la cobertura de la línea base, la previsualización, el calendario y la lista con filtros, el calendario de consulta de DIGSA/Sanidad/ESM, Mis visitas y el detalle.
> Fuentes: controladores/agenda.controlador.php (enriquecer, equipoPorNotificar, cobertura, previsualizar, filtrosLista, detalleCompleto, tiposVisita, festivosVisibles), modelos/visitas.modelo.php (lista, rango, abiertas, delAuditor, cobertura), ajax/agenda.ajax.php, ajax/mis_visitas.ajax.php, ajax/consulta.ajax.php, app/Support/Plazos.php, tests/e2e_local.php, tests/ui/ui_abc.js, tests/ui/ui_fase2.js, docs/decisiones_2026-10-15.md, docs/dudas_pendientes.md.
> Relacionadas: cálculo de las alertas de plazo en `catalogos-festivos-plazos`; filtro por alcance en `perfiles-alcance`; acciones de la agenda en `agenda-notificacion`.

---

### Requirement: Alertas de la agenda
<!-- id: ControladorAgenda.enriquecer -->
<!-- entidades: Visita -->
<!-- impuesto_en: controladores/agenda.controlador.php ControladorAgenda::enriquecer(), equipoPorNotificar(); ajax/agenda.ajax.php accion=alertas; app/Support/Plazos.php Plazos::alertas(), Plazos::nivel() -->
<!-- decision: 12 | 28 | 3.2 (novena ronda) -->
<!-- duda: 3.2 -->

Cada visita de la agenda, de Mis visitas y del calendario de consulta DEBE llevar sus alertas de plazo (calculadas con la fecha de hoy según `catalogos-festivos-plazos`; el químico farmacéutico puede ser cualquier integrante del equipo), más la alerta crítica "El equipo auditor cambió y el cambio no se ha notificado…" cuando el aviso no es `no-opl`, la visita está `notificada` o `reprogramada` y la última reasignación es posterior a la última notificación enviada; su nivel es el más grave (`bad`, `warn` o vacío); la caja de alertas del coordinador DEBE listar las visitas abiertas (ni cerradas ni canceladas) con alguna alerta que no sea informativa, críticas primero y luego por fecha ascendente, junto con la cobertura de la línea base.

#### Scenario: Cambio de equipo sin notificar
<!-- test: tests/e2e_local.php:957 -->
- **CUANDO** se reasigna el equipo de una visita notificada sin reenviar la notificación
- **ENTONCES** la visita tiene la alerta "no se ha notificado" y la acción `notificar`

#### Scenario: La alerta desaparece al notificar
<!-- test: tests/e2e_local.php:961 -->
- **CUANDO** el coordinador notifica a mano el cambio de equipo
- **ENTONCES** sale el correo, desaparece la acción `notificar` y la visita sigue `notificada`

#### Scenario: Caja de alertas
<!-- test: tests/e2e_local.php:295 -->
<!-- test: tests/ui/ui_abc.js:59 -->
- **CUANDO** el coordinador abre la agenda
- **ENTONCES** recibe las visitas con alertas y la cobertura (`total`, `alerta`)

---

### Requirement: Cobertura de la línea base
<!-- id: ControladorAgenda.cobertura -->
<!-- entidades: Punto, Visita -->
<!-- impuesto_en: controladores/agenda.controlador.php ControladorAgenda::cobertura(); modelos/visitas.modelo.php ModeloVisitas::cobertura() -->
<!-- decision: Anexo 3 p.12 | 3.3 (octava ronda) -->

La agenda DEBE mostrar cuántos ESM de dispensación activos (dentro del alcance) tienen visita inicial cerrada, cuántos en curso o programada (no cancelada) y cuáles no tienen visita inicial (hasta 10 nombres y "y N más"), frente al límite `inicio_contrato` + `inicial_meses` (3) meses; el nivel es `info` si falta registrar el inicio del contrato, `warn` (o `bad` si venció el plazo) si hay ESM sin programar, `info` (o `bad`) si faltan cerradas y `ok` con cobertura completa.

#### Scenario: Sin fecha de inicio del contrato
- **CUANDO** hay ESM de dispensación y `inicio_contrato` está vacío
- **ENTONCES** la alerta es `info` y pide registrar la fecha de inicio

---

### Requirement: Previsualizar antes de programar
<!-- id: ControladorAgenda.previsualizar -->
<!-- entidades: Punto, Visita -->
<!-- impuesto_en: controladores/agenda.controlador.php ControladorAgenda::previsualizar() -->

Mientras el coordinador llena el formulario, la agenda DEBE devolver los destinatarios de la notificación del punto elegido y las alertas del borrador (como visita `programada`), más "La fecha cae en fin de semana o festivo." si no es hábil y "No se podrá notificar: faltan los correos de …" si el tipo lleva aviso y falta un destinatario obligatorio.

#### Scenario: Alerta de QF
<!-- test: tests/e2e_local.php:249 -->
- **CUANDO** el punto exige químico farmacéutico y ningún integrante del borrador lo es
- **ENTONCES** las alertas mencionan "químico farmacéutico"

#### Scenario: Destinatarios antes de guardar
<!-- test: tests/ui/ui_abc.js:72 -->
- **CUANDO** se elige el punto en el formulario
- **ENTONCES** la pantalla muestra los correos de los destinatarios

---

### Requirement: Calendario y lista de la agenda con filtros
<!-- id: ControladorAgenda.filtrosLista -->
<!-- entidades: Visita -->
<!-- impuesto_en: controladores/agenda.controlador.php ControladorAgenda::filtrosLista(); modelos/visitas.modelo.php ModeloVisitas::lista(), rango(); ajax/agenda.ajax.php accion=calendario|lista -->
<!-- decision: 11 | 2b -->
<!-- duda: 8.7 -->

La agenda DEBE ofrecer calendario (por defecto el mes en curso, `desde`/`hasta` válidos) y lista con filtros por estado, tipo, fuerza, unidad, municipio (5 dígitos), punto (ESM), regional, auditor (como líder u otro auditor) y periodo; los valores que no están en los catálogos o no son válidos DEBEN ignorarse; siempre DEBE aplicarse el alcance del usuario (sin alcance, nada); la lista DEBE ordenarse por fecha y hora, con máximo 2000 visitas, y NO DEBE devolver los correos del punto ni de la fuerza.

#### Scenario: Calendario
<!-- test: tests/e2e_local.php:293 -->
<!-- test: tests/ui/ui_abc.js:58 -->
- **CUANDO** el coordinador pide el calendario del periodo
- **ENTONCES** recibe las visitas del periodo y el mes se pinta

#### Scenario: Lista
<!-- test: tests/ui/ui_abc.js:83 -->
- **CUANDO** el coordinador abre la lista
- **ENTONCES** carga visitas

#### Scenario: Filtro por fuerza
<!-- test: tests/e2e_local.php:869 -->
- **CUANDO** filtra por fuerza ARC
- **ENTONCES** todas las visitas devueltas son de ARC

#### Scenario: Filtro por regional
<!-- test: tests/e2e_local.php:925 -->
- **CUANDO** filtra por una regional
- **ENTONCES** solo vuelven visitas de puntos de esa regional

---

### Requirement: Calendario de consulta de DIGSA, Sanidad y ESM
<!-- id: consulta.ajax -->
<!-- entidades: Visita -->
<!-- impuesto_en: ajax/consulta.ajax.php; app/Support/Alcance.php -->
<!-- decision: 10 | 20 | 27 | 36 | Agentes 5 (octava ronda) -->

Los perfiles DIGSA, Sanidad (DISAN/JEFSA) y ESM DEBEN ver calendario, lista y detalle de las visitas de su alcance, en solo lectura, con los mismos filtros (las fuerzas ofrecidas se limitan a la suya si su alcance es una fuerza) y la lista de auditores activos sin correos; el detalle DEBE traer el equipo con nombre y rol, sin acciones, historial ni notificaciones, y sin correos ni datos de contacto de nadie; una visita fuera del alcance DEBE responder 404 como si no existiera; el ESM NO DEBE ver las visitas a necesidad que no se han hecho (ver `perfiles-alcance`).

#### Scenario: Fuera del alcance
<!-- test: tests/e2e_local.php:851 -->
<!-- test: tests/e2e_local.php:856 -->
- **CUANDO** Sanidad EJC pide el detalle de una visita de la Armada, o filtra por ARC
- **ENTONCES** recibe 404, o una lista vacía

#### Scenario: Sin correos
<!-- test: tests/e2e_local.php:853 -->
- **CUANDO** la DIGSA abre el detalle de una visita
- **ENTONCES** la respuesta no contiene correos y sí el equipo

#### Scenario: Solo lectura
<!-- test: tests/e2e_local.php:854 -->
<!-- test: tests/ui/ui_fase2.js:39 -->
<!-- test: tests/ui/ui_fase2.js:43 -->
- **CUANDO** la DIGSA intenta programar o abrir el formulario de la agenda, o toca un día del calendario
- **ENTONCES** recibe 403; el calendario de consulta no tiene botón Programar ni alertas y no abre "Programar"

#### Scenario: Filtros de Sanidad
<!-- test: tests/ui/ui_fase2.js:61 -->
- **CUANDO** Sanidad EJC abre los filtros
- **ENTONCES** la fuerza solo ofrece EJC

#### Scenario: ESM y la visita a necesidad pendiente
<!-- test: tests/e2e_local.php:898 -->
<!-- test: tests/e2e_local.php:922 -->
- **CUANDO** el ESM lista sus visitas
- **ENTONCES** no ve la visita a necesidad aún no hecha y sí las demás de su ESM

#### Scenario: Identificador no válido
<!-- test: tests/e2e_local.php:974 -->
- **CUANDO** se pide el detalle con id 0
- **ENTONCES** responde 404

#### Scenario: El auditor no entra
<!-- test: tests/e2e_local.php:855 -->
- **CUANDO** un auditor pide la lista de consulta
- **ENTONCES** recibe 403

---

### Requirement: Mis visitas del auditor
<!-- id: mis_visitas.ajax -->
<!-- entidades: Visita -->
<!-- impuesto_en: ajax/mis_visitas.ajax.php; modelos/visitas.modelo.php ModeloVisitas::delAuditor(); controladores/agenda.controlador.php detalleCompleto() -->
<!-- decision: 2b -->

El auditor DEBE ver solo las visitas en que es líder u otro auditor, sin las canceladas, ordenadas por fecha y hora, con sus alertas y los festivos para el calendario; el detalle de una visita de la que no es integrante DEBE responder 404; el auditor NO DEBE ver los correos de los destinatarios: las notificaciones le llegan con `para = '—'` y sin error, y el historial sin la lista ". A: …".

#### Scenario: Solo sus visitas
<!-- test: tests/e2e_local.php:302 -->
<!-- test: tests/e2e_local.php:671 -->
<!-- test: tests/ui/ui_abc.js:94 -->
- **CUANDO** la auditora lista sus visitas, o el ingeniero que es otro auditor de una visita
- **ENTONCES** ve las suyas (incluida la del equipo) y ninguna de otro auditor

#### Scenario: Visita ajena
<!-- test: tests/e2e_local.php:306 -->
- **CUANDO** pide el detalle de una visita de otro auditor
- **ENTONCES** recibe 404

#### Scenario: Sin correos de destinatarios
<!-- test: tests/e2e_local.php:304 -->
- **CUANDO** la auditora abre el detalle de su visita notificada
- **ENTONCES** no aparece el correo del OPL

#### Scenario: Celular
<!-- test: tests/ui/ui_abc.js:103 -->
- **CUANDO** se abre Mis visitas en un celular
- **ENTONCES** no hay desplazamiento horizontal

---

### Requirement: Detalle de la visita para el coordinador
<!-- id: ControladorAgenda.detalleCompleto -->
<!-- entidades: Visita, VisitaHistorial, VisitaNotificacion -->
<!-- impuesto_en: controladores/agenda.controlador.php ControladorAgenda::detalleCompleto() -->

El detalle del coordinador DEBE traer la visita con alertas, historial (fecha, usuario, evento, detalle en orden cronológico), notificaciones (más recientes primero, con destinatarios, estado y error), acciones, equipo (sin correos ni documentos), reparto por parte, si puede mover partes, los motivos de reapertura, los destinatarios con los que faltan y, si puede notificar, el borrador de asunto y cuerpo.

#### Scenario: Detalle tras programar
<!-- test: tests/ui/ui_abc.js:80 -->
- **CUANDO** el coordinador programa desde la pantalla
- **ENTONCES** queda abierto el detalle titulado "Visita VS-…"

---

### Requirement: Datos del formulario de la agenda
<!-- id: agenda.ajax#formulario -->
<!-- entidades: TipoVisita, Punto, Auditor -->
<!-- impuesto_en: ajax/agenda.ajax.php accion=formulario; controladores/agenda.controlador.php tiposVisita(), festivosVisibles(), solicitudesPendientes() -->

El formulario DEBE traer los tipos de visita (en requerimiento y seguimiento a plan, el texto de notificación con el parámetro vigente de días hábiles), los puntos activos, los auditores activos con su rol, los estados, los festivos del año anterior al año +2 (calculados más los de ajuste), las fuerzas sin correos, unidades, municipios, regionales y las solicitudes pendientes por agendar (solo para el coordinador).

#### Scenario: Puntos y auditores
<!-- test: tests/e2e_local.php:243 -->
<!-- test: tests/e2e_local.php:654 -->
- **CUANDO** el coordinador abre el formulario
- **ENTONCES** están los puntos y los auditores creados, cada auditor con su rol

#### Scenario: Solicitudes pendientes
<!-- test: tests/ui/ui_fase2.js:71 -->
- **CUANDO** hay solicitudes de visita a necesidad pendientes
- **ENTONCES** la agenda muestra la caja de solicitudes con el botón para agendar

---

### Invariant: La consulta no expone datos de contacto
<!-- entidades: Visita, Punto, Fuerza, Auditor -->
<!-- impuesto_en: controladores/agenda.controlador.php enriquecer() (quita punto_correos, fuerza_correos); ajax/consulta.ajax.php paraConsulta() (quita auditor_mail, contacto, creado_por, equipo) -->
<!-- decision: Ley 1581 (mínimo necesario) -->

Ninguna lista ni calendario DEBE devolver los correos del punto ni de la fuerza; la consulta de DIGSA, Sanidad y ESM tampoco el correo del auditor, el contacto del punto, quién creó la visita ni el texto libre del equipo.

<!-- incierto: el informe de la fase 1 (prioridades 2-3, "minimización de datos") dice que Mis visitas trae correos del punto y de la fuerza; en el código actual `enriquecer` ya los quita, pero la agenda y Mis visitas siguen devolviendo `auditor_mail` y `contacto` del punto. Confirmar si la reescritura debe quitarlos también. -->
<!-- pendiente: modelos/puntos.modelo.php (opcionesFiltro), app/Support/Alcance.php (completo), vistas de la agenda y del calendario (JS) -->
