# Especificación: riesgo-tablero (fase 9)

> Extraída por spec-miner el 2026-10-21 del PHP de referencia (develop, commit db2e330).
> Fuentes: app/Support/Riesgo.php, modelos/riesgo.modelo.php, controladores/riesgo.controlador.php, ajax/riesgo.ajax.php, vistas/modulos/riesgo/riesgo.php, vistas/js/riesgo.js; controladores/dashboard.controlador.php, ajax/dashboard.ajax.php, vistas/modulos/dashboard/dashboard.php, vistas/js/dashboard.js; modelos/visitas.modelo.php (filtroDashboard, paraDashboard, resultadosDashboard, cobertura); modelos/opl.modelo.php (conPlanVencido); controladores/agenda.controlador.php (cobertura); app/Support/Plazos.php (nivel); Database/Structure/006_checklist_visita.sql (visita_resumen); Database/Migraciones/2026-10-16_fase8.sql; tests/riesgo_test.php, tests/e2e_local.php, tests/reglas_test.php, tests/ui/ui_fase7.js, tests/ui/ui_f.js, tests/ui/ui_fase1.js; docs/decisiones_2026-10-15.md (10, 14, 27, 36, novena ronda 3.2 y 3.7), docs/dudas_pendientes.md (3.x, 8.6, 8.7, 14.x), docs/fases_desarrollo.md.
> **Mandan sobre el PHP**, en este orden: `docs/decisiones_2026-10-21.md`, `docs/decisiones_2026-10-20.md` (marcadas `<!-- decision: 2026-10-2X … -->`) y la fuente de verdad (Anexo 3, Anexo 4, Anexo 5 ítem 14 y `FUENTE DE VERDAD/ANALISIS_RESPUESTAS_DIGSA_vs_SOFTWARE_2026-10-20.md`). El texto describe el comportamiento NUEVO. La 14.1 está «con la resolución recomendada, pendiente del visto bueno del usuario»: se especifica la recomendación.
> Relacionadas: alertas de plazo y cobertura de la línea base en `agenda-consulta`; resumen de la visita en `checklist`; plan del OPL, «vencido», «reportado a DIGSA» y alerta de plan vencido en `flujo-opl`; seguimiento y prórrogas en `seguimiento`; alcance en `perfiles-alcance`; días sin visita como criterio de agenda en `propuesta-agenda`.
> Estado en Java (commit bad1cc0): `Pantallas.RIESGO` y `Pantallas.DASHBOARD` existen (Coordinador + `Alcance.PERFILES_CONSULTA` = DIGSA, Sanidad, ESM; el Administrador entra a todo), pero no hay endpoint, servicio ni página de ninguno de los dos. `visitas_resumen` (V9) solo tiene c, nc, na, pendientes, hallazgos y `por_componente`; no tiene inmediata, w5_abiertos, hab, tramite, no_hab, prestador ni vacíos, y `visitas_respuestas` no guarda la marca de intervención inmediata. `visitas_hallazgos` (V12, por acta, con `fin`, `estado_plan` y `en_tramite` de V14), `opl_planes.estado` (`vencido`, `reportado_digsa`, `cerrado`), `reportes_digsa`, `prorrogas` y `visitas.origen_id` (V13) ya existen. `Exportador` ya escribe CSV con «;», coma decimal y UTF-8. Ver «Datos que la reescritura Java debe agregar».

---

### Requirement: Población del puntaje de riesgo
<!-- id: ModeloRiesgo.datos (puntos) -->
<!-- entidades: Punto -->
<!-- impuesto_en: modelos/riesgo.modelo.php datos() (p.estado = 1 AND p.unidad = 'ESM' AND Alcance::sqlPunto) -->
<!-- decision: 14 | 36 | duda 14.3 | 2026-10-20 14.3 -->

El puntaje DEBE calcularse solo para los puntos activos de unidad ESM **y modalidad Dispensación** dentro del alcance del usuario, con filtro opcional por fuerza (código `^[A-Z]{2,6}$`; otro valor se ignora). Las farmacias alternas, el nivel central y los ESM de suministro, consignación o satélite no tienen puntaje.
<!-- decision: 2026-10-20 14.3 — el PHP toma todos los ESM activos; la reescritura filtra `modalidad = 'Dispensación'`, como la cobertura de la línea base. -->

#### Scenario: Solo su ESM
<!-- test: tests/e2e_local.php:1276 -->
- **CUANDO** el perfil ESM consulta el riesgo
- **ENTONCES** la lista trae solo su punto

#### Scenario: Sanidad de otra fuerza
<!-- test: tests/e2e_local.php:1278 -->
- **CUANDO** Sanidad ARC consulta el riesgo
- **ENTONCES** no ve el ESM del Ejército y las opciones de fuerza son solo `['ARC']`

#### Scenario: Filtro por fuerza
<!-- test: tests/e2e_local.php:1283, tests/ui/ui_fase7.js:30 -->
- **CUANDO** el coordinador filtra `fuerza=EJC`
- **ENTONCES** todas las filas son del Ejército

#### Scenario: ESM de suministro
<!-- test: sin prueba en el PHP (decisión nueva) -->
- **CUANDO** un ESM activo tiene modalidad Suministro
- **ENTONCES** no aparece en el tablero de riesgo ni cuenta en los agregados

---

### Requirement: Factores y puntaje (sin «días sin visita»)
<!-- id: Riesgo.puntaje -->
<!-- entidades: Punto, Visita, VisitaResumen, VisitaHallazgo, OplPlan, IncidenciaDigsa -->
<!-- impuesto_en: app/Support/Riesgo.php puntaje(), PESOS -->
<!-- decision: 14 | duda 14.1 | 2026-10-20 14.1 | Anexo 3 p.12 («hallazgos recurrentes, criticidad operativa o incidencias reportadas por la DIGSA») -->

El puntaje DEBE ser la suma, redondeada a entero, de los factores, cada uno redondeado y con tope en su peso: **incumplimiento** = peso × NC / (C + NC) (0 si no hay nada calificado); **recurrencia** = min(5, recurrentes) × peso / 5; **intervención inmediata** = min(3, inmediatas) × peso / 3; **planes vencidos** = peso/2 si hay plan del OPL vencido + min(5, acciones vencidas) × peso / 10; **incidencias de la DIGSA** = factor nuevo (ver su requisito). El factor «días sin visita» del PHP (0 hasta 180 días, lineal hasta su peso a los 365, peso completo sin visita cerrada) **NO DEBE** sumar al puntaje.
<!-- decision: 2026-10-20 14.1 — «días sin visita» sale del puntaje y queda como criterio de la agenda; se agregan las incidencias reportadas por la DIGSA. -->
<!-- incierto: con días fuera, los pesos del PHP suman 85 (30+20+15+20). Recomendación: el peso que deja «días sin visita» (15) pasa a «incidencias de la DIGSA», para seguir en 100 sin cambiar los demás. Pendiente del visto bueno del usuario (14.1). -->

#### Scenario: Todo cumple
<!-- test: tests/riesgo_test.php:19 -->
- **CUANDO** C 100, NC 0, sin recurrentes, inmediatas, planes ni acciones vencidas
- **ENTONCES** total 0, nivel bajo

#### Scenario: Todo al máximo
<!-- test: tests/riesgo_test.php:21 (adaptar: sin días y con incidencias al máximo) -->
- **CUANDO** NC 10 de 10, 9 recurrentes, 7 inmediatas, plan vencido, 12 acciones vencidas e incidencias por encima del tope
- **ENTONCES** total 100, nivel alto (cada factor llega a su tope)

#### Scenario: Mitad de No cumple
<!-- test: tests/riesgo_test.php:25 -->
- **CUANDO** C 50 y NC 50
- **ENTONCES** incumplimiento 15 de 30

#### Scenario: Sin nada calificado
<!-- test: tests/riesgo_test.php:27 -->
- **CUANDO** C 0 y NC 0
- **ENTONCES** incumplimiento 0 (no divide por cero)

#### Scenario: Planes y recurrencia
<!-- test: tests/riesgo_test.php:30, tests/riesgo_test.php:31 -->
- **CUANDO** plan del OPL vencido y 2 acciones vencidas / 2 recurrentes
- **ENTONCES** planes 14 / recurrencia 8

#### Scenario: Nunca visitado
<!-- test: tests/riesgo_test.php:23 (cambia: en el PHP suma 15) -->
- **CUANDO** el ESM no tiene ninguna visita cerrada ni incidencias
- **ENTONCES** total 0; la pantalla dice «Sin visita cerrada» (la prioridad por antigüedad la da la agenda)

---

### Requirement: Incumplimiento e intervención inmediata de la última visita completa
<!-- id: ModeloRiesgo.datos (completa) -->
<!-- entidades: Visita, VisitaResumen -->
<!-- impuesto_en: modelos/riesgo.modelo.php datos() ($completa: primera cerrada con origen_id NULL, orden fecha DESC, id DESC) -->
<!-- decision: duda 14.6 -->

C, NC e intervención inmediata DEBEN salir del resumen de la **última visita cerrada que no es seguimiento** (`origen_id` nulo), ordenando por fecha y id descendentes. Un seguimiento solo califica lo verificado y no se compara con una visita completa.

#### Scenario: Seguimiento posterior
<!-- test: sin prueba en el PHP -->
- **CUANDO** la última cerrada es un seguimiento con 1 C y 0 NC, y la anterior completa tuvo 40 C y 10 NC
- **ENTONCES** incumplimiento = 30 × 10/50 = 6

---

### Requirement: Visita cerrada sin resumen
<!-- id: ModeloRiesgo.datos (sin_resumen) -->
<!-- entidades: VisitaResumen -->
<!-- impuesto_en: modelos/riesgo.modelo.php datos() error_log; vistas/js/riesgo.js pintar() -->
<!-- decision: — -->

Si la última visita completa está cerrada sin resumen, el sistema DEBE dejar C, NC e inmediata en 0, registrar el error en el log de la aplicación y marcar la fila «Visita sin resumen: incumplimiento no medido»; NO DEBE tomarla como «todo cumple» en silencio.

#### Scenario: Datos migrados
<!-- test: sin prueba en el PHP -->
- **CUANDO** la visita completa cerrada no tiene fila en `visitas_resumen`
- **ENTONCES** `sin_resumen = true` y la fila muestra el aviso en rojo

---

### Requirement: Recurrencia
<!-- id: Riesgo.recurrentes -->
<!-- entidades: VisitaHallazgo -->
<!-- impuesto_en: app/Support/Riesgo.php recurrentes(); modelos/riesgo.modelo.php datos() (dos últimas cerradas) -->
<!-- decision: 14 | duda 14.5 | Anexo 3 p.12 -->

Recurrentes DEBE ser el número de claves de hallazgo (sin repetir) de la última visita cerrada que también son hallazgo en la visita cerrada anterior del mismo punto, de cualquier tipo (también el seguimiento). Con una sola visita cerrada es 0. En Java los hallazgos se toman del **acta vigente** de cada visita (la última ronda).

#### Scenario: Claves repetidas
<!-- test: tests/riesgo_test.php:33 -->
- **CUANDO** la última tiene `it:P01, it:P02, ob:X` y la anterior `it:P02, ob:X, it:P09`
- **ENTONCES** recurrentes = 2

<!-- incierto: si un hallazgo que el líder levantó (11.7) en la visita anterior cuenta como recurrente. El PHP sí lo cuenta (lee visita_hallazgos sin mirar la resolución). Recomendación: no contarlo. -->

---

### Requirement: Planes vencidos
<!-- id: ModeloRiesgo.datos (acciones_vencidas, plan_opl_vencido) | ModeloOpl.conPlanVencido -->
<!-- entidades: VisitaHallazgo, OplPlan, Prorroga -->
<!-- impuesto_en: modelos/riesgo.modelo.php datos() ($abiertas, $vencidas); modelos/opl.modelo.php conPlanVencido() -->
<!-- decision: 14 | 2026-10-20 11.5 | 2026-10-20 Reunión 5 | 2026-10-21 D9 -->

Se miran la última visita cerrada y todas las **visitas con plan abierto** del punto (cerradas, con hallazgos y sin un seguimiento cerrado que las reemplace). **Acciones vencidas** DEBE contar las filas del plan con `fin` anterior a hoy (Bogotá) y estado distinto de Completado y Cancelado. **Plan del OPL vencido** DEBE ser verdadero si alguna de esas visitas tiene el plan del acta vigente en `vencido` o `reportado_digsa`.
<!-- decision: 2026-10-20 11.5 — sin firma tardía: el PHP calcula «vence_plan < ahora sin plan firmado y con hallazgos no levantados»; Java usa el estado del plan, que la tarea diaria pone en «vencido» a las 23:59:59 del día hábil 5 (11.1) y que pasa a «cerrado» al levantar el último hallazgo (11.7). -->

#### Scenario: Acción vencida en una visita anterior
<!-- test: sin prueba en el PHP -->
- **CUANDO** la última visita no tiene hallazgos, pero una anterior sin seguimiento cerrado tiene 2 acciones vencidas «En curso»
- **ENTONCES** acciones vencidas = 2

#### Scenario: Plan cerrado
<!-- test: sin prueba en el PHP -->
- **CUANDO** el plan pasó a «cerrado» porque el líder levantó todo
- **ENTONCES** no cuenta como plan vencido

<!-- incierto: con prórroga (seguimiento 12.1 y D8), qué fecha manda para «acción vencida»: la `fin` original, la solicitada o solo la aprobada. Recomendación: la `fin_nuevo` de la prórroga aprobada; mientras esté «solicitada», la solicitada (como la programación del seguimiento, 22.1). -->

---

### Requirement: Incidencias reportadas por la DIGSA
<!-- id: (nuevo) ServicioRiesgo.incidencias -->
<!-- entidades: IncidenciaDigsa, Punto -->
<!-- impuesto_en: sin equivalente en el PHP -->
<!-- decision: 2026-10-20 14.1 | Anexo 3 p.12 | Anexo 3 p.14 (num. 5) -->

El sistema DEBE permitir registrar a mano incidencias reportadas por la DIGSA sobre un ESM (fecha, punto, descripción, quién la registra y fuente) con bitácora, y el factor «incidencias» DEBE sumar por las incidencias vigentes del punto, con tope en su peso.
<!-- incierto: quién registra (recomendación: Coordinador y DIGSA), qué es «vigente» (recomendación: no cerrada y de los últimos 12 meses), cuánto suma cada una (recomendación: peso/3, tope 3, como la intervención inmediata), si se cierran y si las ven Sanidad y ESM. Nada de esto está en el PHP ni en la fuente de verdad. -->

#### Scenario: Una incidencia
<!-- test: sin prueba en el PHP (decisión nueva) -->
- **CUANDO** el coordinador registra una incidencia de la DIGSA sobre un ESM sin hallazgos
- **ENTONCES** el ESM suma el valor de una incidencia, la bitácora lo registra y el tablero muestra la cuenta

---

### Requirement: Pesos editables por el coordinador
<!-- id: (nuevo) ServicioRiesgo.guardarPesos -->
<!-- entidades: Parametro, Bitacora -->
<!-- impuesto_en: app/Support/Riesgo.php PESOS, NIVELES (constantes en el PHP) -->
<!-- decision: 2026-10-20 14.1 | duda 14.1 | Análisis 2.6 «Parametrización en vivo» (TAS P5) -->

El coordinador DEBE poder cambiar desde la pantalla los pesos de cada factor y los umbrales de nivel; cada cambio DEBE quedar en la bitácora con usuario, fecha, valor anterior y nuevo, y aplicarse al siguiente cálculo. La pantalla, la descarga y la explicación DEBEN llevar la etiqueta «Propuesta AGS, pendiente de aprobación DIGSA» mientras no se apruebe. Los perfiles de consulta ven los pesos pero no los editan.

#### Scenario: Cambio de peso
<!-- test: sin prueba en el PHP (decisión nueva) -->
- **CUANDO** el coordinador sube «recurrencia» de 20 a 25 y baja «planes» de 20 a 15
- **ENTONCES** se guarda, la bitácora trae 20→25 y 20→15, y el siguiente tablero usa los nuevos pesos

#### Scenario: Consulta no edita
<!-- test: sin prueba en el PHP -->
- **CUANDO** un usuario DIGSA intenta guardar pesos
- **ENTONCES** 403

<!-- incierto: si los pesos deben sumar 100 al guardar (recomendación: sí, se rechaza con 422 si no) y si los umbrales alto/medio son editables o fijos (recomendación: editables, con alto > medio > 0). -->

---

### Requirement: Nivel de riesgo
<!-- id: Riesgo.nivel -->
<!-- entidades: — -->
<!-- impuesto_en: app/Support/Riesgo.php nivel(), NIVELES -->
<!-- decision: duda 14.1 -->

El nivel DEBE ser «alto» con total ≥ umbral alto (60), «medio» con total ≥ umbral medio (30) y «bajo» por debajo.

#### Scenario: Bordes
<!-- test: tests/riesgo_test.php:32 -->
- **CUANDO** el total es 59 / 60 / 29 / 30
- **ENTONCES** medio / alto / bajo / medio

---

### Requirement: Tablero de riesgo por país, fuerza y ESM
<!-- id: ControladorRiesgo.tablero | Riesgo.agregar -->
<!-- entidades: Punto, Fuerza -->
<!-- impuesto_en: controladores/riesgo.controlador.php tablero(); app/Support/Riesgo.php agregar(); vistas/js/riesgo.js pintar() -->
<!-- decision: 14 | 36 -->

El tablero DEBE devolver: **país** (número de ESM, puntaje promedio, ESM por nivel y promedio de cada factor); **por fuerza** lo mismo, sin «No aplica», con el factor que más pesa (o «—» si todos son 0); **ESM** ordenados por total descendente y luego por nombre, cada uno con fuerza, municipio, total, nivel, sus factores frente a su peso, última visita cerrada, días desde ella (informativo) y los datos crudos (NC, recurrentes, inmediatas, acciones vencidas, plan vencido, incidencias); además los pesos, nombres, umbrales y las fuerzas del alcance para el filtro.
<!-- decision: VCO P27 (Análisis 2.2) — los promedios y factores se muestran y descargan con 2 decimales y coma decimal (el PHP usa 1 decimal con punto). -->

#### Scenario: Agregado
<!-- test: tests/riesgo_test.php:35, tests/e2e_local.php:1271 -->
- **CUANDO** se agregan tres ESM con totales 0, 100 y 15
- **ENTONCES** esm 3, promedio 38,33, niveles alto 1, medio 0, bajo 2

#### Scenario: Agregado vacío
<!-- test: tests/riesgo_test.php:37 -->
- **CUANDO** no hay ESM en el alcance
- **ENTONCES** promedio 0 y la tabla dice «Sin ESM en su alcance.»

#### Scenario: Orden y fuerzas
<!-- test: tests/e2e_local.php:1275, tests/e2e_local.php:1285 -->
- **CUANDO** el coordinador abre el tablero
- **ENTONCES** los ESM van de mayor a menor puntaje y «No aplica» no aparece en «Por fuerza»

#### Scenario: Pantalla
<!-- test: tests/ui/ui_fase7.js:23, tests/ui/ui_fase7.js:27, tests/ui/ui_fase7.js:29, tests/ui/ui_fase7.js:31, tests/ui/ui_fase7.js:38 -->
- **CUANDO** el coordinador entra a «Dashboard de riesgo» en escritorio y en un celular de 390 px
- **ENTONCES** ve 4 cajas del país, la tabla por fuerza, cada ESM con una barra por factor y la explicación de pesos, sin desplazamiento horizontal

---

### Requirement: Descarga del tablero de riesgo
<!-- id: ControladorRiesgo.exportar -->
<!-- entidades: — -->
<!-- impuesto_en: controladores/riesgo.controlador.php exportar(); app/Support/Exportador.php -->
<!-- decision: 3.7 | 36 | Análisis 2.2 (TAS P12, AGS-36, GMI P77 f) -->

El tablero DEBE descargarse en TXT, CSV, Excel o PDF con encabezado (título «Dashboard de riesgo por ESM», fecha y hora de generación, usuario, filtros, pesos y umbrales con la etiqueta de 14.1) y dos tablas: «Puntaje por ESM» (ESM, fuerza, municipio, puntaje, nivel, cada factor, última visita cerrada o «Sin visita cerrada», días sin visita) y «Por fuerza» (fuerza, ESM, promedio, alto, medio, bajo). Un formato fuera de la lista DEBE responder 422. La descarga DEBE quedar en la bitácora.
<!-- decision: TAS P12 — reportes completos, sin truncar: el PHP rechaza el PDF con más de 500 ESM (MAX_FILAS_PDF); la reescritura pagina el PDF. -->

#### Scenario: Excel y CSV
<!-- test: tests/e2e_local.php:1287, tests/e2e_local.php:1289 -->
- **CUANDO** la DIGSA descarga en Excel y en CSV
- **ENTONCES** el Excel es un ZIP válido y el CSV trae «Puntaje por ESM» y «Pesos»

---

### Requirement: Acceso a los tableros
<!-- id: Pantallas.RIESGO | Pantallas.DASHBOARD -->
<!-- entidades: Perfil -->
<!-- impuesto_en: ajax/riesgo.ajax.php y ajax/dashboard.ajax.php (Auth::requerirAjax(['Coordinador', ...PERFILES_CONSULTA]) + requerirAlcance) -->
<!-- decision: 10 | 27 | 36 -->

Al tablero de riesgo y al dashboard DEBEN entrar el Coordinador, la DIGSA, Sanidad (DISAN o JEFSA) y el ESM, cada uno con su alcance (ver `perfiles-alcance`); el Administrador entra a toda pantalla. El Auditor y los perfiles OPL DEBEN recibir 403. Un perfil de consulta sin su dato de alcance no ve nada (falla cerrado).

#### Scenario: Permisos
<!-- test: tests/e2e_local.php:314, tests/e2e_local.php:1281, tests/e2e_local.php:967, tests/reglas_test.php:86, tests/reglas_test.php:88 -->
- **CUANDO** el auditor o el OPL regional piden los datos del dashboard o del riesgo
- **ENTONCES** 403; la DIGSA sí los ve

---

### Requirement: Filtros y opciones del dashboard
<!-- id: ControladorDashboard.filtros | ControladorDashboard.opciones -->
<!-- entidades: Visita, Punto, Usuario -->
<!-- impuesto_en: controladores/dashboard.controlador.php filtros(), opciones(); modelos/visitas.modelo.php filtroDashboard() -->
<!-- decision: 36 | duda 3.4 | duda 8.7 -->

El dashboard DEBE filtrar por desde y hasta (fecha de la visita), fuerza (sin «No aplica»), tipo de visita, auditor (líder **o** integrante del equipo) y unidad (ESM, FA, NC), siempre dentro del alcance; un valor que no cumple su formato DEBE ignorarse sin error. Las opciones DEBEN traer solo código y nombre: fuerzas del alcance sin «No aplica», tipos, auditores **activos** sin correo, unidades y estados.

#### Scenario: Filtro inválido
<!-- test: tests/e2e_local.php:320, tests/e2e_local.php:322 -->
- **CUANDO** se pide `unidad=NC, fuerza=NA, desde=2026-13-40`
- **ENTONCES** filtra por unidad, ignora la fuerza y la fecha, y «Visitas por fuerza» queda vacía

#### Scenario: Opciones sin correos
<!-- test: tests/e2e_local.php:316, tests/e2e_local.php:864 -->
- **CUANDO** el coordinador / Sanidad EJC pide las opciones
- **ENTONCES** no hay «NA» ni correos / la única fuerza es EJC

#### Scenario: Alcance
<!-- test: tests/e2e_local.php:867, tests/e2e_local.php:868 -->
- **CUANDO** Sanidad EJC consulta / filtra la Armada
- **ENTONCES** ve menos visitas que la DIGSA / ve 0

---

### Requirement: Indicadores y gráficas de visitas
<!-- id: ControladorDashboard.datos (visitas) -->
<!-- entidades: Visita -->
<!-- impuesto_en: controladores/dashboard.controlador.php datos(); vistas/js/dashboard.js pintar() -->
<!-- decision: dudas 3.1, 8.7 -->

Sobre las visitas del filtro, el dashboard DEBE mostrar: visitas en el periodo; por ejecutar (programada + notificada + reprogramada, con el desglose); en ejecución; cerradas; canceladas; con alertas críticas; y gráficas de barras con «Ver tabla» por tipo, por fuerza (sin el nivel central «No aplica»), por unidad (todas las unidades, también en 0), por auditor (**solo el líder**) y por estado. Las canceladas cuentan en el total y en las gráficas. Solo conteos, sin porcentaje de cumplimiento.

#### Scenario: Suma por estado
<!-- test: tests/e2e_local.php:319, tests/ui/ui_f.js:30 -->
- **CUANDO** el coordinador abre el dashboard sin filtros
- **ENTONCES** la suma de los estados es igual al total y el total es mayor que 0

<!-- incierto: el PHP corta en 10 000 visitas (LIMIT) sin avisarlo; la reescritura debe contar en SQL (duda 4.5) o avisar el recorte. -->

---

### Requirement: Alertas críticas, con el plan del OPL vencido
<!-- id: ControladorDashboard.datos (criticas, planesOplVencidos) -->
<!-- entidades: Visita, OplPlan -->
<!-- impuesto_en: controladores/dashboard.controlador.php datos(); app/Support/Plazos.php nivel(); modelos/opl.modelo.php conPlanVencido() -->
<!-- decision: novena ronda 3.2 | duda 3.2 | 2026-10-20 11.5 | duda Java 21.9 -->

«Con alertas críticas» DEBE ser la suma de: (a) las visitas abiertas del filtro (ni cerradas ni canceladas) cuyo nivel de alertas, calculado con la fecha de hoy, es crítico (`bad`: sin notificar a tiempo, visita a necesidad que supera los 5 días hábiles, cambio de equipo sin notificar; ver `agenda-consulta`); y (b) **planes del OPL vencidos**: las visitas cerradas del filtro con el plan del acta vigente en `vencido` o `reportado_digsa`. El número (b) DEBE ir aparte (`planesOplVencidos`) y la tarjeta lo dice («N plan(es) del OPL vencido(s)»).
<!-- decision: 2026-10-20 11.5 — sin firma tardía, la alerta no se apaga con una firma; se apaga cuando el plan pasa a «cerrado» (ver flujo-opl). -->

#### Scenario: Plan vencido
<!-- test: tests/e2e_local.php:1119 -->
- **CUANDO** una visita cerrada del alcance tiene el plan vencido
- **ENTONCES** `planesOplVencidos >= 1` y está sumado en «Con alertas críticas»

#### Scenario: Visita cerrada sin plan vencido
<!-- test: sin prueba en el PHP -->
- **CUANDO** una visita cerrada tenía una alerta de notificación tardía
- **ENTONCES** no cuenta: las alertas de plazo solo valen para visitas abiertas

<!-- incierto: Análisis 2.2 (GMI P77 b) pide alertas críticas por correo y SMS en tiempo real. El tablero solo las cuenta; el aviso por correo del plan vencido ya sale en la tarea diaria (flujo-opl). El SMS es transversal y no es de esta fase. -->

---

### Requirement: Línea base de los ESM de dispensación
<!-- id: ControladorAgenda.cobertura (en el dashboard) -->
<!-- entidades: Punto, Visita -->
<!-- impuesto_en: controladores/agenda.controlador.php cobertura(); modelos/visitas.modelo.php cobertura() -->
<!-- decision: duda 3.3 | duda 3.6 | 2026-10-20 16.1 | Anexo 3 p.12 -->

Si hay ESM de dispensación en el alcance, el dashboard DEBE mostrar «{cerradas} de {total}» ESM con visita inicial cerrada, con en curso o programadas, sin programar y el plazo (inicio del contrato + `inicial_meses`), **sin aplicar los filtros** del dashboard. La regla y los textos están en `agenda-consulta`.

#### Scenario: Tarjeta
<!-- test: tests/e2e_local.php:783 (indicador en la descarga) -->
- **CUANDO** hay 10 ESM de dispensación y 4 con inicial cerrada
- **ENTONCES** la tarjeta dice «4 de 10» aunque se filtre por fechas

---

### Requirement: Resultados de las visitas cerradas
<!-- id: ModeloVisitas.resultadosDashboard -->
<!-- entidades: VisitaResumen, VisitaResumenComponente, VisitaResumenVacio -->
<!-- impuesto_en: modelos/visitas.modelo.php resultadosDashboard(); controladores/dashboard.controlador.php datos() -->
<!-- decision: dudas 3.5, 8.6 | novena ronda «vacío prioritario en trámite» | 2026-10-21 D5 -->

Desde el resumen guardado de las visitas **cerradas** del filtro (una reabierta sale hasta cerrarla de nuevo), el dashboard DEBE mostrar: Cumple, No cumple, No aplica, Hallazgos, Requieren intervención inmediata del OPL, Planes 5W2H abiertos; habilitación (habilitado, no habilitado en trámite, no habilitado sin soporte); resultados C/NC/NA/sin calificar por categoría (obligaciones y componentes, también los que quedaron en 0) y por prestador (OPL o farmacia alterna; «Sin prestador» si falta), ordenados por NC descendente; y «Vacíos de mayor riesgo con No cumple» con todos los vacíos de la versión vigente del checklist (también en 0).
<!-- decision: 2026-10-21 D5 — la habilitación en trámite ES hallazgo (con la marca «en trámite»). Cambian los textos del PHP: «Hallazgos» deja de decir «sin contar la habilitación en trámite» y la tarjeta «No habilitado · en trámite» deja de decir «no es hallazgo». -->
<!-- incierto: con D5, si el vacío prioritario en trámite vuelve a contar en «Vacíos de mayor riesgo con No cumple» (la novena ronda decía que no, cuando el trámite no era hallazgo). Recomendación: contarlo, porque ya es hallazgo; confirmar. -->

#### Scenario: Seguimiento no duplica
<!-- test: tests/e2e_local.php:517 -->
- **CUANDO** se cierra un seguimiento que verificó un solo hallazgo (ahora Cumple)
- **ENTONCES** su resumen es `c=1, nc=0, na=0, pend=0`

---

### Requirement: Descarga del dashboard
<!-- id: ControladorDashboard.exportar -->
<!-- entidades: — -->
<!-- impuesto_en: controladores/dashboard.controlador.php exportar(); ajax/dashboard.ajax.php -->
<!-- decision: novena ronda 3.7 | Análisis 2.2 (TAS P12, VCO P27) -->

El botón «Descargar» DEBE generar TXT, CSV, Excel o PDF con las mismas cifras de la pantalla y los filtros activos: encabezado «Dashboard de visitas» con fecha y hora, usuario y filtros con nombre legible; tabla «Indicadores» (incluye «Planes del OPL vencidos (sin firmar a los 5 días hábiles)», la línea base y los resultados) y una tabla por gráfica. Un formato no válido DEBE responder 422 «Formato no válido (TXT, CSV, Excel o PDF).». La descarga DEBE quedar en la bitácora.

#### Scenario: Excel y CSV
<!-- test: tests/e2e_local.php:781, tests/e2e_local.php:783, tests/ui/ui_fase1.js:92 -->
- **CUANDO** el coordinador descarga en Excel / CSV
- **ENTONCES** recibe un ZIP válido / un CSV con «Indicadores» y «Visitas por tipo»; la pantalla tiene 4 botones de formato

---

### Requirement: Respuestas lentas no pisan el filtro actual
<!-- id: riesgo.js cargar() | dashboard.js cargar() -->
<!-- entidades: — -->
<!-- impuesto_en: vistas/js/riesgo.js cargar() (abort); vistas/js/dashboard.js cargar() (abort) -->
<!-- decision: — -->

Al cambiar un filtro, la pantalla DEBE cancelar la petición anterior; una respuesta vieja NO DEBE pintarse sobre la del filtro actual. «Limpiar filtros» vuelve a cargar sin filtros.

#### Scenario: Dos cambios seguidos
<!-- test: sin prueba en el PHP -->
- **CUANDO** el usuario cambia la fuerza dos veces antes de que llegue la primera respuesta
- **ENTONCES** se pinta solo la segunda

---

### Invariant: El puntaje está entre 0 y 100 y cada factor tiene tope
<!-- entidades: — -->
<!-- impuesto_en: app/Support/Riesgo.php puntaje() (min, tope por peso) -->
<!-- verificado_por: tests/riesgo_test.php:18, tests/riesgo_test.php:22, tests/e2e_local.php:1269 -->

Cada factor DEBE estar entre 0 y su peso, los pesos vigentes DEBEN sumar 100 y el total DEBE estar entre 0 y 100. Un ESM tiene exactamente un factor por cada peso configurado (5 en la reescritura: incumplimiento, recurrencia, intervención inmediata, planes vencidos, incidencias).

---

### Invariant: Todo sale del servidor y del alcance
<!-- entidades: VisitaResumen -->
<!-- impuesto_en: modelos/visitas.modelo.php filtroDashboard() (sin alcance: 'ninguno'); modelos/riesgo.modelo.php datos() (Alcance::sqlPunto) -->
<!-- verificado_por: tests/e2e_local.php:867, tests/e2e_local.php:1276 -->

Los tableros DEBEN leer solo el resumen calculado por el servidor al cerrar (nunca del navegador) y DEBEN aplicar el alcance del perfil en la consulta; sin alcance no devuelven nada. Se calculan en cada consulta, así que cumplen el rezago máximo de 48 h (AGS-38/45).

---

### Invariant: Las fechas se calculan en Bogotá
<!-- entidades: — -->
<!-- impuesto_en: controladores/riesgo.controlador.php tablero() date('Y-m-d'); modelos/riesgo.modelo.php (misma hora que la alerta del dashboard) -->
<!-- verificado_por: sin prueba en el PHP -->

«Hoy» (acciones vencidas, días sin visita, alertas) DEBE ser la fecha de America/Bogotá, y el tablero de riesgo y el dashboard DEBEN usar el mismo criterio para «plan vencido».

---

## Contradicciones

1. **Días sin visita** (PHP, 15 puntos) frente a 2026-10-20 14.1 (fuera del puntaje): se sigue la decisión. Rompe `tests/riesgo_test.php:22, 24, 28` y la explicación de pesos; los pesos dejan de sumar 100 si no se reasigna (ver incierto del factor).
2. **Todos los ESM** (PHP) frente a 2026-10-20 14.3 (solo dispensación): se sigue la decisión. La línea base ya era solo de dispensación.
3. **Plan vencido por fecha** (`conPlanVencido`: vence_plan < ahora, sin plan firmado, con hallazgos no levantados) frente a 11.5 y 11.7 (estado `vencido`/`reportado_digsa`, sin firma tardía, «cerrado» al levantar todo): se sigue el estado del plan. El texto de la descarga del PHP («sin firmar a los 5 días hábiles») sigue siendo correcto.
4. **Habilitación en trámite**: el PHP la excluye de «Hallazgos» y la novena ronda la excluye de los vacíos; D5 (2026-10-21) dice que es hallazgo. Se sigue D5 en los textos; los vacíos quedan inciertos.
5. **Plan incumplido**: 22.2 (solo al máximo de seguimientos) frente a D9 (en cada seguimiento con la acción vencida). Manda D9; más planes quedarán en `reportado_digsa`, y eso sube «planes vencidos» y las alertas críticas antes.
6. **PDF con tope de 500 filas** (PHP, y la duda 8.11 dice 3 000) frente a TAS P12 (reportes completos, sin truncar): se pagina.
7. **Decimales**: el PHP redondea a 1 decimal con punto; VCO P27 exige 2 decimales, coma y sin separador de miles. Se sigue VCO P27 en pantalla y descargas.
8. **Nombres de auditores**: el dashboard del PHP muestra «Visitas por auditor» y filtra por auditor a los perfiles de consulta (decisión «Agentes 5»: la DIGSA ve auditores y su número de visitas); los reportes Java ocultan el auditor líder a los perfiles de consulta (Ley 1581). Se sigue «Agentes 5» en el dashboard, sin correos; confirmar si Sanidad y ESM también los ven.
9. **Pesos fijos en el código** (PHP) frente a 14.1 (editables por el coordinador, con bitácora y etiqueta): se sigue la decisión.

## IA analítica obligatoria (Anexo 5 ítem 14, Anexo 4): qué toca a esta fase

Los subítems 14.1 a 14.9 (obligatorios; 14.1 habilitante) y el 14.10 (asistente, ponderable) se prueban sobre el **insumo de dispensación 2024-2025 del OPL** (1,5 a 2 millones de registros): ingesta, normalización contra INVIMA y MinSalud, tableros de gasto, cohortes, patrones y alertas. **Esta fase no implementa esa IA** ni la sustituye; no tiene modelos de aprendizaje ni datos de dispensación (el volumen sigue fuera, 2026-10-20 14.2). Lo que sí aporta, con criterios que la prueba exige al módulo analítico:
- **14.2:** cada perfil ve solo su alcance y ningún tablero expone datos de un paciente (aquí no los hay).
- **14.7 (por analogía):** un puntaje explicable, con el factor, su peso, su tope y el umbral del nivel a la vista. Es una regla, no un modelo.
- **14.8:** descargas en Excel, CSV y PDF con título, fecha, usuario y filtros. Faltan fuente, fecha de corte, número de registros y observaciones: conviene agregarlos al encabezado. Las cifras deben ser idénticas para usuarios del mismo perfil con los mismos filtros.
- **14.9 y TAS P5:** bitácora de consultas, descargas y cambios de pesos (valor anterior y nuevo), y la posibilidad de rastrear cada factor hasta la visita, el hallazgo o el plan que lo origina.
- **Anexo 3 p.12:** el puntaje es el «modelo de riesgos del contrato» construido sobre la línea base. Además, es un insumo natural del módulo analítico (Anexo 4, «resultados derivados de las actividades de auditoría»). La integración con ese módulo es del aliado o de otra fase.

## Datos que la reescritura Java debe agregar

1. **`visitas_resumen`**: `inmediata`, `w5_abiertos`, `hab`, `tramite`, `no_hab`, `prestador`, y una tabla `visitas_resumen_vacios (visita_id, vacio, nc)`. Los componentes ya están en `por_componente` (jsonb); conviene una tabla `visitas_resumen_componentes (visita_id, componente, nombre, c, nc, na, pend)` para agrupar en SQL.
2. **Marca de intervención inmediata** por respuesta y obligación: `visitas_respuestas.inmediata boolean` (y en las obligaciones de la minuta) o `visitas_hallazgos.inmediata`. Sin ella, el factor y la tarjeta valen 0.
3. **Acta vigente**: una vista o consulta de `visitas_hallazgos` y `opl_planes` filtrada por el último `acta_id` de cada visita.
4. **Incidencias de la DIGSA**: `riesgo_incidencias (id, punto_id, fecha, descripcion ≤2000, fuente, estado abierta|cerrada, registrada_por, creado, cerrada_por, cerrada_en)` con su permiso y los eventos de bitácora `incidencia_registrada` e `incidencia_cerrada`.
5. **Pesos y umbrales**: en `par_parametros`, con las claves `riesgo_peso_incumplimiento`, `…_recurrencia`, `…_inmediata`, `…_planes`, `…_incidencias`, `riesgo_nivel_alto`, `riesgo_nivel_medio` y `riesgo_estado_pesos` («propuesta AGS, pendiente de aprobación DIGSA»). La edición deja en la bitácora el valor anterior y el nuevo.
6. **Índices**: `visitas (punto_id, estado, fecha DESC, id DESC)`, equivalente al `ix_visitas_punto_estado_fecha` de la fase 8 del PHP, e índice parcial de `opl_planes (estado) WHERE estado IN ('vencido','reportado_digsa')`.
7. **Endpoints**: `GET /api/riesgo` (con `fuerza`), `GET /api/riesgo/exportar`, `PUT /api/riesgo/pesos` (Coordinador), CRUD de `/api/riesgo/incidencias`; `GET /api/dashboard/opciones`, `GET /api/dashboard` y `GET /api/dashboard/exportar`, todos con `@Accion` (consulta o descarga) y el alcance de `UsuarioSesion`.
8. **Páginas React** «Dashboard» y «Dashboard de riesgo» (con barras por factor, responsivas a 390 px) y un formulario de pesos e incidencias.
9. **Conteos en SQL** (`GROUP BY`) en vez de traer hasta 10 000 visitas a memoria.

<!-- pendiente: vistas/checklist/paneles.php y vistas/js/checklist.js (dónde se marca «inmediata»), app/Support/ResumenChecklist.php completo (habilitación y vacíos; ver checklist), app/Support/Alcance.php sql() (necesidad pendiente para ESM; ver perfiles-alcance), tests/ui/ui_f.js completo -->
