# Especificación: propuesta-agenda

> Extraída por spec-miner el 2026-10-21 del PHP de referencia (develop, commit db2e330).
> Fuentes: app/Support/PropuestaAgenda.php, controladores/propuesta.controlador.php, ajax/propuesta.ajax.php, modelos/propuesta.modelo.php, vistas/modulos/agenda/propuesta.php, vistas/js/propuesta.js, app/Support/Rutas.php, app/Support/Bitacora.php, Database/Structure/008_propuesta_agenda.sql, tests/propuesta_agenda_test.php, tests/e2e_local.php (fase 6, l.1190-1263), tests/ui/ui_fase6.js, .claude/validaciones/2026-10-16_fase-6.md, docs/decisiones_2026-10-15.md (13, 32), docs/dudas_pendientes.md (13.1 a 13.6, 14.4), docs/fases_desarrollo.md (fase 6).
> **Mandan sobre el PHP**, en este orden: `docs/decisiones_2026-10-21.md`, `docs/decisiones_2026-10-20.md` (marcadas `<!-- decision: 2026-10-20 X.Y -->`) y la fuente de verdad del contrato (`FUENTE DE VERDAD\md\ANEXO 3 VISITAS-CONCURENCIA.md`, marcada `Anexo 3 p.N`). El texto describe el comportamiento NUEVO. Las decisiones 13.1 y 13.3 están «con la resolución recomendada, pendiente del visto bueno del usuario»: se especifica la recomendación. Las del 2026-10-21 no tocan esta capacidad.
> Relacionadas: programar y notificar (camino A) en `agenda-notificacion`; cobertura de la línea base en la agenda en `agenda-consulta`; días hábiles, festivos y parámetros (`visitas_por_dia` 1–6, `km_mismo_dia` 1–300) en `catalogos-festivos-plazos`; roles y reparto en `equipo-reparto`; puntos, auditores y municipio base en `maestros-carga-masiva`; acceso por pantalla en `perfiles-alcance`.
> Estado en Java: existen `Pantallas.PROPUESTA` (solo Coordinador), los parámetros `visitas_por_dia`, `km_mismo_dia`, `inicio_contrato` e `inicial_meses` (R__catalogos.sql, con rangos en `ServicioCatalogos.RANGOS`), las coordenadas DANE en `par_municipios`, `puntos.llega_desde`, `puntos.auditor_asignado`, `puntos.exige_qf`, `auditores.rol` y `auditores.municipio_base` (V3), las 8 regionales (V7) y `ServicioAgenda.programar` con notificación automática. **No existe** nada de la propuesta: ni reglas, ni tablas, ni API, ni pantalla. Ver «Datos que la reescritura Java debe agregar» al final.

---

### Requirement: Solo el coordinador entra a la propuesta
<!-- id: Rutas::PRIVADAS['propuesta'] | ajax/propuesta.ajax.php Auth::requerirAjax -->
<!-- entidades: Usuario, Perfil -->
<!-- impuesto_en: app/Support/Rutas.php 'propuesta'; ajax/propuesta.ajax.php Auth::requerirAjax(['Coordinador']); reglas/Pantallas.java PROPUESTA -->
<!-- decision: 13 | 32 | tabla de perfiles de decisiones_2026-10-15 (Coordinador: propuesta de agenda) -->

La pantalla «Propuesta de agenda» (grupo Visitas) y todas sus acciones DEBEN quedar reservadas al Coordinador (y al Administrador, que entra a todo); cualquier otro perfil recibe 403.

#### Scenario: Acceso
<!-- test: tests/e2e_local.php:1195, tests/ui/ui_fase6.js:25 -->
- **CUANDO** un auditor o un usuario DIGSA piden los datos de la propuesta / el coordinador ingresa
- **ENTONCES** reciben 403 / su menú trae «Propuesta de agenda»

---

### Requirement: La pantalla explica cómo arma la propuesta antes de usarla
<!-- id: vistas/modulos/agenda/propuesta.php #pa-como -->
<!-- entidades: Parametro -->
<!-- impuesto_en: vistas/modulos/agenda/propuesta.php; vistas/js/propuesta.js -->
<!-- decision: 13 («antes de programarla se explica el funcionamiento») -->

La pantalla DEBE mostrar, plegada, la explicación de los cuatro pasos (qué se visita, auditor líder, fecha, borrador), con los valores vigentes de `visitas_por_dia` y `km_mismo_dia`, la frase «Nada se programa ni se notifica hasta confirmar», que la distancia es en línea recta con las coordenadas del DANE y dónde se cambian los parámetros. Debe verse en el celular sin desplazamiento horizontal de la página.

#### Scenario: Explicación y celular
<!-- test: tests/ui/ui_fase6.js:30, tests/ui/ui_fase6.js:60 -->
- **CUANDO** el coordinador abre la pantalla / la abre en un ancho de 375 px
- **ENTONCES** la caja «Cómo arma la propuesta» dice «Nada se programa ni se notifica hasta confirmar» / la página no pasa de 392 px de ancho

---

### Requirement: Ciclos de la propuesta
<!-- id: PropuestaAgenda::CICLOS | ModeloPropuesta::TIPOS_CICLO -->
<!-- entidades: Punto, TipoVisita -->
<!-- impuesto_en: app/Support/PropuestaAgenda.php CICLOS; modelos/propuesta.modelo.php TIPOS_CICLO -->
<!-- decision: 13 | 32 | 2026-10-20 16.1 | 2026-10-20 14.4 | Anexo 3 p.12-13 | Anexo 3 p.15 -->

La propuesta DEBE trabajar por cuatro ciclos, en este orden, cada uno con su unidad, su modalidad, el tipo de visita que crea y los tipos que lo cubren:

| Ciclo | Nombre | Unidad y modalidad | Crea | Lo cubren |
|---|---|---|---|---|
| `linea_base` | Línea base: visita inicial a los ESM de dispensación | ESM · Dispensación | `inicial` | `inicial` |
| `anual` | Ciclo anual de los ESM de dispensación | ESM · Dispensación | `anual` | `inicial`, `anual` |
| `farmacia` | Farmacias alternas (anual) | FA · cualquiera | `farmacia` | `farmacia` |
| `central` | Nivel central del OPL (anual) | NC · cualquiera | `central` | `central` |

<!-- decision: 2026-10-20 16.1 — la cobertura en sitio excluye los ESM de suministro y satélite, que se vigilan solo de forma remota (Anexo 3 p.15, «únicamente de forma remota»). Ningún ciclo los incluye; tampoco los de consignación (el Anexo 3 p.12 pide el 100 % solo de los ESM de dispensación). -->
<!-- decision: 2026-10-20 14.4 — no hay ciclo por riesgo: la visita «Basada en riesgos» se programa a mano desde la agenda. -->

#### Scenario: Los cuatro ciclos
<!-- test: tests/e2e_local.php:1194, tests/ui/ui_fase6.js:29 -->
- **CUANDO** el coordinador pide los datos del año
- **ENTONCES** recibe los ciclos `linea_base`, `anual`, `farmacia` y `central` en ese orden

#### Scenario: Suministro y satélite fuera
<!-- test: sin prueba en el PHP (decisión 2026-10-20 16.1; el filtro por modalidad ya existe en ModeloPropuesta::pendientes) -->
- **CUANDO** hay ESM activos de suministro y de satélite sin visita
- **ENTONCES** no aparecen en ningún ciclo ni cuentan en su cobertura

<!-- incierto: el Anexo 3 p.12 tiene además «Visitas a ESM según el anexo de medicamentos» (tipo `anexo`), cuya frecuencia fija un documento que no está disponible. No tiene ciclo; se especifica así. -->

---

### Requirement: Periodo y límite de cada ciclo
<!-- id: ControladorPropuesta::limite | diasCiclo -->
<!-- entidades: Parametro -->
<!-- impuesto_en: controladores/propuesta.controlador.php limite(), diasCiclo(), generar(); parámetros inicio_contrato e inicial_meses -->
<!-- decision: 2026-10-20 13.4 | Anexo 3 p.12 | duda 13.4 (el PHP usaba el año calendario) -->

La línea base DEBE tener como límite `inicio_contrato` + `inicial_meses` (3) meses y no depende del año elegido. **Cambio 2026-10-20 13.4:** los ciclos anuales DEBEN contarse por **año de contrato**: el año N va de `inicio_contrato + (N−1) años` al día anterior a `inicio_contrato + N años`, que es su límite, y sus días propuestos caen dentro de él. Al generar se DEBE responder 422 si falta `inicio_contrato` (ahora en los cuatro ciclos: «Registre la fecha de inicio del contrato (Puntos y auditores › Contrato)…»), si el límite ya pasó («El plazo de este ciclo ya venció (fecha). Programe lo que falte desde la agenda.») o si no queda ningún día hábil antes del límite que deje los 15 días de notificación previa.

#### Scenario: Línea base sin inicio del contrato
<!-- test: tests/e2e_local.php:1249 -->
- **CUANDO** `inicio_contrato` está vacío y se genera la línea base
- **ENTONCES** 422 con un mensaje que pide registrar el «inicio del contrato»

#### Scenario: Línea base vencida
<!-- test: tests/e2e_local.php:1251 -->
- **CUANDO** el contrato empezó hace dos años y se genera la línea base
- **ENTONCES** 422

#### Scenario: Año de contrato
<!-- test: sin prueba en el PHP (decisión 2026-10-20 13.4) -->
- **CUANDO** `inicio_contrato` = 2027-01-15 y se genera el ciclo anual del año de contrato 2
- **ENTONCES** el periodo es 2028-01-15 a 2029-01-14, el límite es 2029-01-14 y cubre solo una visita `inicial` o `anual` no cancelada con fecha en ese periodo

<!-- incierto: (1) si la 13.4 vale también para farmacias alternas y nivel central; se especifica que sí, para que los tres ciclos anuales usen el mismo periodo. (2) Cómo se elige el periodo en la pantalla: el PHP pide el año calendario (2020–2100); se recomienda un selector «Año de contrato N (desde – hasta)». (3) La línea base cae en el año de contrato 1 y, como en el PHP, la visita `inicial` cubre el ciclo anual de ese año. (4) El PHP suma 3 meses (inicio 2027-01-15 → límite 2027-04-15, inclusive); «los 3 primeros meses» podría querer decir hasta 2027-04-14. Se deja como el PHP. -->

---

### Requirement: Paso 1, qué se visita
<!-- id: ModeloPropuesta::pendientes -->
<!-- entidades: Punto, Visita -->
<!-- impuesto_en: modelos/propuesta.modelo.php pendientes() -->
<!-- decision: 32 | Anexo 3 p.12 (100 % de los ESM de dispensación) | Anexo 3 p.9 (cobertura de la totalidad de las unidades) -->

Los puntos por proponer DEBEN ser los puntos activos de la unidad (y la modalidad) del ciclo sin ninguna visita no cancelada de los tipos que lo cubren: en la línea base, en cualquier fecha; en los anuales, con fecha dentro del periodo del ciclo. Cuentan también las visitas abiertas (programadas, notificadas, reprogramadas o en ejecución).

#### Scenario: Punto nuevo sin visita
<!-- test: tests/e2e_local.php:1205 -->
- **CUANDO** se crea un ESM de dispensación sin visita en el periodo y se genera el ciclo anual
- **ENTONCES** el borrador lo incluye

#### Scenario: Punto ya agendado
<!-- test: tests/e2e_local.php:1229 -->
- **CUANDO** el punto ya tiene una visita `anual` notificada en el periodo
- **ENTONCES** no sale en la siguiente propuesta

<!-- incierto: la decisión 2026-10-20 14.1 (recomendada) saca «días sin visita» del puntaje de riesgo y lo deja «como criterio de la agenda», sin decir cómo. El PHP no lo usa en la propuesta. Recomendación: mostrar en cada fila del borrador los días desde la última visita cerrada del punto, sin cambiar el cálculo. -->

---

### Requirement: Cobertura del periodo por ciclo
<!-- id: ModeloPropuesta::cobertura | ControladorPropuesta::datos -->
<!-- entidades: Punto, Visita, Propuesta -->
<!-- impuesto_en: modelos/propuesta.modelo.php cobertura(), borrador(), confirmadaIncompleta(); controladores/propuesta.controlador.php datos() -->
<!-- decision: 13 («cobertura anual») | Anexo 3 p.9 | Anexo 3 p.12 -->

Para cada ciclo los datos DEBEN traer: nombre, tipo, límite, `total` (puntos activos del ciclo), `cerradas` (con visita cerrada de los tipos que lo cubren en el periodo), `agendadas` (con una visita no cancelada y no cerrada, sin contar las que ya tienen una cerrada), `faltan = max(0, total − cerradas − agendadas)`, el id del borrador vigente y el de la última propuesta confirmada con filas sin resultado (para continuarla). También la lista de auditores activos (id, nombre, rol) y los parámetros `visitas_por_dia` (mínimo 1) y `km_mismo_dia` (mínimo 1). «Proponer agenda» queda deshabilitado si no falta nada; con borrador, el botón dice «Proponer de nuevo».

#### Scenario: Agendado cuenta
<!-- test: tests/e2e_local.php:1229 -->
- **CUANDO** se confirma la visita de un punto
- **ENTONCES** la cobertura del ciclo anual tiene `agendadas ≥ 1`

#### Scenario: Parámetros
<!-- test: tests/e2e_local.php:1261 -->
- **CUANDO** el coordinador guarda 3 visitas por día y 60 km
- **ENTONCES** los datos de la propuesta traen `visitas_por_dia = 3` y `km_mismo_dia = 60`

---

### Requirement: Quién puede liderar
<!-- id: PropuestaAgenda::puedeLiderar | ROLES_LIDER -->
<!-- entidades: Auditor, Punto -->
<!-- impuesto_en: app/Support/PropuestaAgenda.php puedeLiderar(); controladores/propuesta.controlador.php editarItem() -->
<!-- decision: duda 13.2 | Anexo 3 p.9 (HMC y Hospital Naval de Cartagena: químico farmacéutico) -->

En un punto con `exige_qf` solo DEBE liderar un químico farmacéutico; en los demás, un químico farmacéutico, un regente o un auditor en salud. El ingeniero de sistemas y el rol «otro» no lideran (acompañan).

#### Scenario: Exige químico farmacéutico
<!-- test: tests/propuesta_agenda_test.php:44 -->
- **CUANDO** un punto de Medellín exige QF y el único QF está en Bogotá
- **ENTONCES** se propone el QF de Bogotá

#### Scenario: Sin químico farmacéutico activo
<!-- test: tests/propuesta_agenda_test.php:46 -->
- **CUANDO** no hay QF activo
- **ENTONCES** la fila queda sin auditor, con la alerta «No hay químico farmacéutico activo para este punto.», y el resumen dice que no cabe

#### Scenario: El ingeniero no lidera
<!-- test: tests/propuesta_agenda_test.php:38, tests/e2e_local.php:1211 -->
- **CUANDO** se calcula Soacha con un ingeniero en Bogotá / el coordinador pone al ingeniero como líder
- **ENTONCES** se elige al QF de Bogotá / 422

---

### Requirement: Paso 2, auditor líder
<!-- id: PropuestaAgenda::generar (paso 2) -->
<!-- entidades: Punto, Auditor, Municipio -->
<!-- impuesto_en: app/Support/PropuestaAgenda.php generar(), distanciaKm(), KM_POR_VISITA; modelos/propuesta.modelo.php auditores(), coordenadas() -->
<!-- decision: 32 | 2026-10-20 13.1 (recomendada) | 2026-10-20 13.3 (recomendada) | Anexo 3 p.9 (auditor asignado por ESM de dispensación) -->

Por cada punto no fijado, atendiendo primero los que tienen menos candidatos (desempate por id), el sistema DEBE elegir el líder así:
1. Si el punto tiene `auditor_asignado` activo que puede liderar, lo elige con el motivo «Auditor asignado al punto».
2. Si el asignado no cumple, avisa «El auditor asignado (nombre) no cumple el requisito del punto.».
3. Sin asignado que cumpla, elige el candidato de menor puntaje, con el motivo «El más cercano con el requisito, repartiendo la carga». El puntaje es la distancia en línea recta (haversine, radio 6371 km) del municipio base del auditor al municipio de acceso del punto (`llega_desde`, o su municipio), más 60 km por cada visita que tenga por encima del candidato menos cargado; sin coordenadas de alguno de los dos, la distancia vale 5000 km; desempate por id.

La carga es el número de visitas no canceladas del auditor (como líder o apoyo) entre el primer día posible y el límite, más lo que ya le asignó esta misma propuesta. La fila guarda los km redondeados del líder elegido.

#### Scenario: Cercanía
<!-- test: tests/propuesta_agenda_test.php:37 -->
- **CUANDO** el punto está en Medellín y hay auditores en Bogotá, Medellín y Neiva
- **ENTONCES** se elige al de Medellín

#### Scenario: «Se llega desde»
<!-- test: tests/propuesta_agenda_test.php:39, tests/propuesta_agenda_test.php:41 -->
- **CUANDO** el punto está en Chaparral y se llega desde Neiva / sin «se llega desde»
- **ENTONCES** se elige al auditor de Neiva / se mide desde Chaparral

#### Scenario: Auditor asignado
<!-- test: tests/propuesta_agenda_test.php:49, tests/propuesta_agenda_test.php:51, tests/e2e_local.php:1205 -->
- **CUANDO** el punto tiene asignado un QF lejano / tiene asignado un ingeniero
- **ENTONCES** se respeta el QF / se elige a otro y queda la alerta

#### Scenario: Reparto de la carga
<!-- test: tests/propuesta_agenda_test.php:57 -->
- **CUANDO** hay 6 puntos en Bogotá y dos auditores asistenciales en Bogotá
- **ENTONCES** cada uno lleva 3

#### Scenario: Sin ubicación
<!-- test: tests/propuesta_agenda_test.php:82, tests/propuesta_agenda_test.php:87, tests/propuesta_agenda_test.php:89 -->
- **CUANDO** el municipio del punto no tiene coordenadas / un auditor no tiene municipio base / es el único auditor
- **ENTONCES** se propone igual con la alerta de coordenadas / se prefiere al que tiene ubicación / se propone con la alerta «… no tiene municipio base con coordenadas.»

---

### Requirement: Ingeniero de sistemas en el nivel central
<!-- id: PropuestaAgenda::generar (nivel central) -->
<!-- entidades: Auditor, Punto -->
<!-- impuesto_en: app/Support/PropuestaAgenda.php generar() -->
<!-- decision: 2026-10-20 13.5 (cerrada: solo nivel central) | decisión 15 -->

En los puntos de unidad NC el sistema DEBE sumar al equipo el ingeniero de sistemas activo con menos carga (desempate por id), que cuenta en la carga y en la ocupación del día. Sin ingeniero, avisa «No hay ingeniero de sistemas activo para los ítems de sistemas del nivel central.». En los ESM y las farmacias no se suma.

#### Scenario: Ingeniero sugerido
<!-- test: tests/propuesta_agenda_test.php:73 -->
- **CUANDO** se propone el nivel central en Bogotá
- **ENTONCES** el líder es el QF y el ingeniero va en `otros`

#### Scenario: Ingeniero ocupado
<!-- test: tests/propuesta_agenda_test.php:95 -->
- **CUANDO** el ingeniero ya tiene una visita el primer día y el máximo es 1 por día
- **ENTONCES** la fecha pasa al día siguiente

---

### Requirement: Días posibles
<!-- id: PropuestaAgenda::dias | ControladorPropuesta::diasCiclo -->
<!-- entidades: Festivo, Parametro -->
<!-- impuesto_en: app/Support/PropuestaAgenda.php dias(); controladores/propuesta.controlador.php diasCiclo(); reglas/Plazos.java limiteNotificacion() -->
<!-- decision: Anexo 3 p.9 (notificación con mínimo 15 días a DISANES y OPL) | 2026-10-20 13.4 -->

Los días que se proponen DEBEN ser días hábiles (sin fines de semana ni festivos) desde el primero que respeta la notificación previa hasta el límite del ciclo y, en los ciclos anuales, dentro de su periodo. En Java, un día D respeta la notificación previa si `Plazos.limiteNotificacion("opl", D)` (el hábil anterior a D − 15 días) no es anterior a hoy en Bogotá. Es la misma regla que usan las alertas de la agenda. Los cuatro tipos que crea la propuesta tienen aviso `opl`.

#### Scenario: Primer día y festivos
<!-- test: tests/propuesta_agenda_test.php:105, tests/propuesta_agenda_test.php:106, tests/e2e_local.php:1207 -->
- **CUANDO** hoy es 2026-10-16 y el límite es 2026-11-20
- **ENTONCES** el primer día es 2026-11-03 o posterior, y no están ni el 2026-11-07 (sábado) ni el 2026-11-16 (festivo)

#### Scenario: Sin días
<!-- test: tests/propuesta_agenda_test.php:101 -->
- **CUANDO** no hay ningún día posible
- **ENTONCES** las filas quedan sin fecha con la alerta «No hay días hábiles entre la primera fecha posible y el límite del ciclo.» (al generar, el controlador responde 422 antes de llegar aquí)

<!-- duda: el PHP toma como primer día hoy + 15 días calendario y luego cualquier hábil. Si hoy no es hábil, hoy + 15 puede tener límite de notificación en el hábil anterior a hoy, y la agenda lo marcaría «ya no se cumple la antelación». Se especifica la regla de Plazos.java para que la propuesta y la agenda no se contradigan. -->

---

### Requirement: Paso 3, fecha de cada visita
<!-- id: PropuestaAgenda::generar (paso 3) | PropuestaAgenda::cabe -->
<!-- entidades: Visita, VisitaAuditor, Municipio -->
<!-- impuesto_en: app/Support/PropuestaAgenda.php generar(), cabe(); modelos/propuesta.modelo.php ocupados() -->
<!-- decision: 32 | parámetros visitas_por_dia (2) y km_mismo_dia (40) -->

Por cada auditor líder, el sistema DEBE recorrer sus puntos desde su municipio base, yendo siempre al más cercano del último visitado (sin ubicación, en el orden de la lista). A cada punto le asigna el primer día posible, desde el día del punto anterior, en que todo el equipo (líder y otros) tiene menos de `visitas_por_dia` visitas y las que ya tiene son en el mismo municipio de acceso o a no más de `km_mismo_dia` km (si falta alguna coordenada, no se juntan). Las visitas ya agendadas (no canceladas, como líder o apoyo, por municipio de acceso) y lo fijado por el coordinador ocupan su lugar. Lo que no cabe antes del límite queda sin fecha con la alerta «No cabe antes del límite del ciclo: amplíe el plazo o asígnela a mano.».

#### Scenario: Máximo por día
<!-- test: tests/propuesta_agenda_test.php:63 -->
- **CUANDO** un auditor tiene 3 puntos en Medellín y el máximo es 2
- **ENTONCES** quedan 2 un día y 1 el siguiente

#### Scenario: Municipios lejanos el mismo día
<!-- test: tests/propuesta_agenda_test.php:65, tests/propuesta_agenda_test.php:92 -->
- **CUANDO** hay un punto en Medellín y otro en Turbo / el auditor ya tiene una visita en Turbo el primer día
- **ENTONCES** van en días distintos / el punto de Medellín pasa al segundo día

#### Scenario: Ocupado y lleno
<!-- test: tests/propuesta_agenda_test.php:69, tests/propuesta_agenda_test.php:67 -->
- **CUANDO** el auditor ya tiene una visita el primer día con máximo 1 / hay 10 puntos para 4 días con máximo 2
- **ENTONCES** se propone el segundo día / 8 quedan con fecha y el resumen dice que no cabe

---

### Requirement: Resumen y alertas del cálculo
<!-- id: PropuestaAgenda::generar (resumen) | ControladorPropuesta::textoResumen -->
<!-- entidades: Propuesta -->
<!-- impuesto_en: app/Support/PropuestaAgenda.php generar(); controladores/propuesta.controlador.php textoResumen() -->
<!-- decision: 32 -->

El cálculo DEBE devolver, por fila, punto, líder, otros, fecha, km, si está fija, motivo y alertas; un resumen con el número de visitas, de auditores distintos y de días distintos, la primera y la última fecha, y si todo cabe (todas las filas tienen auditor y fecha); y las alertas generales graves «N punto(s) sin auditor posible.», «N visita(s) no caben antes del límite.» y la de días. El mensaje es «N visita(s) · N auditor(es) · N día(s) hábil(es) · cabe en el plazo.» o «… no todo cabe: revise las alertas.».

#### Scenario: Resumen en pantalla
<!-- test: tests/ui/ui_fase6.js:42, tests/ui/ui_fase6.js:43 -->
- **CUANDO** el coordinador propone un ciclo con pendientes
- **ENTONCES** ve las filas (fecha, punto, líder, otros) y el resumen de visitas, auditores, días y si cabe

---

### Requirement: Generar el borrador
<!-- id: ControladorPropuesta::generar | ModeloPropuesta::crear -->
<!-- entidades: Propuesta, PropuestaItem, Bitacora -->
<!-- impuesto_en: controladores/propuesta.controlador.php generar(); modelos/propuesta.modelo.php crear(); app/Support/Bitacora.php propuesta_generada -->
<!-- decision: 13 | 32 -->

Al generar, en una transacción, el sistema DEBE descartar el borrador anterior del mismo ciclo con sus ajustes (la pantalla lo advierte antes con «Proponer de nuevo»), crear la propuesta `borrador` (ciclo, límite, resumen con alertas, quién y cuándo) con una fila por punto pendiente y registrar `propuesta_generada` («Propuesta N · ciclo · N visita(s)») en la bitácora. No se crea ninguna visita ni se envía ningún aviso. Con un ciclo no válido responde 422. Sin puntos pendientes responde éxito sin borrador: «No hay puntos pendientes en este ciclo: la cobertura está completa o ya está agendada.».

#### Scenario: Nada se programa
<!-- test: tests/e2e_local.php:1208 -->
- **CUANDO** se genera el borrador del ciclo anual
- **ENTONCES** el punto no tiene ninguna visita `anual`

#### Scenario: Proponer de nuevo
<!-- test: tests/ui/ui_fase6.js:33 (flujo con «Proponer de nuevo») -->
- **CUANDO** el ciclo ya tiene borrador y el coordinador vuelve a proponer
- **ENTONCES** se le advierte que el borrador actual se descarta y, si acepta, queda un solo borrador del ciclo

---

### Requirement: Ajustar una fila del borrador
<!-- id: ControladorPropuesta::editarItem | ModeloPropuesta::editarItem -->
<!-- entidades: PropuestaItem, Auditor -->
<!-- impuesto_en: controladores/propuesta.controlador.php editarItem(); modelos/propuesta.modelo.php editarItem() -->
<!-- decision: 13 (asignación manual) | 32 | duda 13.6 -->

El coordinador DEBE poder cambiar el líder, los otros auditores y la fecha de una fila de un borrador. La fila queda **fija**, con el motivo «Ajustado por el coordinador» y sin alertas. El líder, si viene, debe estar activo con perfil Auditor y poder liderar el punto (si no, 422: «Este punto exige un químico farmacéutico como auditor líder (Anexo 3 p.9).» o «El auditor líder debe ser químico farmacéutico, regente o auditor en salud.»). Los otros deben estar activos, sin repetir y sin el líder (estos dos se descartan sin error), máximo 5. La fecha, si viene, debe ser hábil y respetar la notificación previa (si no, 422 con el primer día posible); puede pasar del límite del ciclo (duda 13.6). Si la fila no es de un borrador, responde 404; si la propuesta dejó de ser borrador mientras tanto, 409. Liderazgo y fecha pueden quedar vacíos.

#### Scenario: Sábado
<!-- test: tests/e2e_local.php:1210 -->
- **CUANDO** el coordinador pone un sábado
- **ENTONCES** 422

El coordinador DEBE poder también **liberar** una fila de un borrador (pulsando el candado; `ControladorPropuesta::liberarItem`): deja de ser fija y se calcula de nuevo al recalcular («La fila vuelve a calcularse.»); fuera de un borrador, 404.

#### Scenario: Ajuste válido
<!-- test: tests/e2e_local.php:1214, tests/ui/ui_fase6.js:50 -->
- **CUANDO** cambia la fecha al siguiente día hábil
- **ENTONCES** 200 y la fila muestra el candado de fija

#### Scenario: Liberar
<!-- test: sin prueba propia en el PHP (botón del candado en vistas/js/propuesta.js:117) -->
- **CUANDO** el coordinador pulsa el candado de una fila fija y recalcula
- **ENTONCES** la fila se calcula de nuevo

---

### Requirement: Paso 4, recalcular respetando lo fijado
<!-- id: ControladorPropuesta::recalcular | ModeloPropuesta::bloquear, reemplazar -->
<!-- entidades: Propuesta, PropuestaItem -->
<!-- impuesto_en: controladores/propuesta.controlador.php recalcular(); app/Support/PropuestaAgenda.php generar() (fijos) -->
<!-- decision: 32 -->

Al recalcular, el sistema DEBE trabajar en una transacción con la propuesta bloqueada (`FOR UPDATE`): toma como fijos el líder, los otros y la fecha de las filas fijas (ocupan su lugar y su carga primero), recalcula solo los puntos del borrador que siguen pendientes (los que se programaron por otro lado salen solos) y reemplaza las filas y el resumen. Si la propuesta ya no es borrador, responde 404.

#### Scenario: Lo fijado se respeta
<!-- test: tests/propuesta_agenda_test.php:77, tests/propuesta_agenda_test.php:78, tests/e2e_local.php:1217 -->
- **CUANDO** una fila está fijada al 2026-11-03 con máximo 1 por día
- **ENTONCES** conserva su fecha y la otra visita del mismo auditor pasa al 2026-11-04

#### Scenario: Fijo que ya no está pendiente
<!-- test: tests/propuesta_agenda_test.php:98 -->
- **CUANDO** hay un fijo de un punto que ya no está pendiente
- **ENTONCES** no aparece en el resultado

---

### Requirement: Confirmar y programar
<!-- id: ControladorPropuesta::confirmar | ModeloPropuesta::marcarConfirmada, tomarItem, resultadoItem -->
<!-- entidades: Propuesta, PropuestaItem, Visita, VisitaNotificacion, Bitacora -->
<!-- impuesto_en: controladores/propuesta.controlador.php confirmar(); ControladorAgenda::programar(); agenda/ServicioAgenda.java programar() -->
<!-- decision: 32 (camino A) | Anexo 3 p.9 -->

Al confirmar, el sistema DEBE seguir estos pasos:
1. **Comprobar.** Sin ninguna fila con líder y fecha, responde 422 («Ninguna visita tiene auditor y fecha: no hay nada que programar.»).
2. **Marcar.** Marca la propuesta `confirmada` (quién y cuándo) de forma atómica; si otro ya la confirmó o descartó, responde 409. Las filas se leen después de marcar.
3. **Tomar cada fila** sin resultado una sola vez (marca «En proceso…») y volver a comprobarla: si falta líder o fecha, si el punto ya tiene visita del ciclo o si la fecha ya no es hábil o no respeta la notificación previa, la fila queda «Sin programar: …» con el motivo.
4. **Programar.** Si no, programa la visita por el camino de siempre (`programar` con `notificar`, camino A): tipo del ciclo, punto, fecha, líder, otros y alcance «{finalidad del tipo} (propuesta de agenda N° N)». Guarda en la fila la visita y el mensaje; una falla interna deja «Error interno al programar.» y sigue con las demás.
5. **Cerrar.** Registra `propuesta_confirmada` («Propuesta N · N programada(s), N notificada(s)») y responde «Se programaron N visita(s) y se notificaron N.», más «Las no notificadas quedan con la alerta y el botón «Notificar» en la agenda.» si alguna no se notificó y la lista de las filas sin programar (hasta 8 nombres); en esos casos marca `parcial`.

Una confirmación cortada se puede **continuar**: la propuesta confirmada con filas sin resultado vuelve a pasar por los pasos 3 a 5, sin repetir las filas que ya tienen resultado.

#### Scenario: Confirmar
<!-- test: tests/e2e_local.php:1223 -->
- **CUANDO** se confirma la fila del punto con auditor y fecha
- **ENTONCES** la visita `anual` queda creada con esa fecha y ese auditor, en estado `notificada`, y sale el correo

#### Scenario: Dos veces
<!-- test: tests/e2e_local.php:1225 -->
- **CUANDO** se confirma otra vez la misma propuesta
- **ENTONCES** 404

#### Scenario: Borrador viejo
<!-- test: tests/e2e_local.php:1239, tests/e2e_local.php:1241 -->
- **CUANDO** un punto se programó desde la agenda después de proponer y otra fila quedó a 3 días
- **ENTONCES** no se duplica la visita («ya tiene visita»), la otra queda sin programar («15 días») y la confirmación responde 200

<!-- incierto: en Java `programar` exige al menos un componente del checklist (Anexo 5, prueba 11). El PHP no tenía componentes. Recomendación: la visita de la propuesta lleva todos los componentes de la versión vigente que aplican a la unidad del punto (es la auditoría «al 100 %» del Anexo 3 p.12), con el reparto automático. La fila no guarda componentes. Confirmarlo. -->
<!-- incierto: la propuesta no tiene hora y `visitas.hora` admite nulo; la visita queda sin hora, como en el PHP. -->

---

### Requirement: Descartar el borrador
<!-- id: ControladorPropuesta::descartar -->
<!-- entidades: Propuesta, Bitacora -->
<!-- impuesto_en: controladores/propuesta.controlador.php descartar(); modelos/propuesta.modelo.php descartar() -->
<!-- decision: 32 -->

El coordinador DEBE poder descartar un borrador: queda `descartada`, con `propuesta_descartada` en la bitácora; si no es borrador, 404.

#### Scenario: Descartar
<!-- test: tests/ui/ui_fase6.js:57 -->
- **CUANDO** el coordinador descarta el borrador abierto
- **ENTONCES** la pantalla ya no muestra borrador

---

### Requirement: Reporte de la distribución auditor–punto para la DIGSA
<!-- id: (nuevo) -->
<!-- entidades: Auditor, Punto, Visita, Propuesta -->
<!-- impuesto_en: sin equivalente en el PHP -->
<!-- decision: 2026-10-20 13.3 (recomendada, pendiente del visto bueno del usuario) -->

El sistema DEBE ofrecer un reporte de la distribución auditor–punto, descargable con el formato de los reportes. Por auditor trae: rol, municipio base, regional, puntos que tiene asignados y visitas del periodo por ciclo (agendadas y cerradas), con los km del municipio base a cada punto. Justifica ante la DIGSA el reparto por cercanía y carga.

#### Scenario: Distribución
<!-- test: sin prueba en el PHP (decisión nueva) -->
- **CUANDO** el coordinador o la DIGSA descargan el reporte del año de contrato
- **ENTONCES** cada auditor aparece con sus puntos asignados, sus visitas por ciclo y la distancia a cada punto

<!-- incierto: la 13.3 solo dice «reporte de la distribución auditor–punto para la DIGSA». No fija columnas, periodo ni pantalla. La pantalla PROPUESTA es solo del Coordinador; se recomienda ponerlo en «Reportes de visitas» (Coordinador y perfiles de consulta, con su alcance). -->

---

### Requirement: Aviso de ciudades principales sin auditor
<!-- id: (nuevo) -->
<!-- entidades: Auditor, Regional, Municipio -->
<!-- impuesto_en: sin equivalente en el PHP -->
<!-- decision: Anexo 3 p.9 (equipos en las principales ciudades; las 8 ciudades) | Anexo 3 p.13 (un auditor en cada ciudad principal) | 2026-10-20 respuestas de la DIGSA (8 regionales) -->

La cobertura de la propuesta DEBE avisar qué ciudades principales (Bogotá, Medellín, Cali, Barranquilla, Bucaramanga, Cartagena, Ibagué y Neiva) no tienen ningún auditor activo con municipio base en ellas. También DEBE avisar qué ESM de dispensación activos no tienen auditor asignado (Anexo 3 p.9: un auditor asignado por cada ESM de dispensación). Son avisos: no impiden proponer.

#### Scenario: Ciudad sin auditor
<!-- test: sin prueba en el PHP (fuente de verdad) -->
- **CUANDO** ningún auditor activo tiene municipio base en Neiva (41001)
- **ENTONCES** la cobertura muestra «Neiva: sin auditor con base en la ciudad»

<!-- incierto: recomendación sacada de la fuente de verdad; ni el PHP ni las decisiones la piden. Las 8 regionales (V7) no tienen municipio. Para el aviso hace falta el código DANE de cada ciudad (11001, 05001, 76001, 08001, 68001, 13001, 73001, 41001). Confirmar con el usuario antes de construirla. -->

---

### Invariant: Un solo borrador por ciclo
<!-- entidades: Propuesta -->
<!-- impuesto_en: modelos/propuesta.modelo.php crear() (descarta el anterior), borrador() -->
<!-- verificado_por: tests/ui/ui_fase6.js:33 (flujo «Proponer de nuevo») -->

Cada ciclo DEBE tener a lo sumo una propuesta en `borrador`. Los estados posibles son `borrador`, `confirmada` y `descartada`, y solo se pasa de `borrador` a cualquiera de los otros dos; una propuesta confirmada o descartada no vuelve a borrador.

---

### Invariant: Una fila por punto y cada fila se confirma una sola vez
<!-- entidades: PropuestaItem, Visita -->
<!-- impuesto_en: Database/Structure/008_propuesta_agenda.sql uq_propuesta_punto, fk ON DELETE RESTRICT; modelos/propuesta.modelo.php tomarItem() -->
<!-- verificado_por: tests/e2e_local.php:1225, tests/e2e_local.php:1239 -->

Una propuesta DEBE tener a lo sumo una fila por punto. Una fila con resultado no se vuelve a programar. Las filas de una propuesta confirmada no se borran: son la evidencia de qué se programó. Solo las de un borrador se reemplazan al recalcular. Cada fila programada apunta a su visita.

---

### Invariant: La propuesta no programa fuera de las reglas de la agenda
<!-- entidades: Visita, PropuestaItem -->
<!-- impuesto_en: app/Support/PropuestaAgenda.php dias(), cabe(), puedeLiderar(); controladores/propuesta.controlador.php confirmar() -->
<!-- verificado_por: tests/propuesta_agenda_test.php:63, tests/propuesta_agenda_test.php:105, tests/e2e_local.php:1241 -->

El cálculo NO DEBE proponer un día que no sea hábil, que no respete la notificación previa o que quede fuera del periodo del ciclo; tampoco un líder que no pueda liderar el punto, más de `visitas_por_dia` visitas de un auditor en un día ni, en un mismo día, municipios a más de `km_mismo_dia` km. Lo fijado por el coordinador puede pasar del límite del ciclo (duda 13.6). Al confirmar, la regla de días se vuelve a comprobar para todas las filas.

---

### Invariant: Equipo de la fila
<!-- entidades: PropuestaItem -->
<!-- impuesto_en: controladores/propuesta.controlador.php editarItem(); reglas/Reparto.java MAX_OTROS -->
<!-- verificado_por: tests/e2e_local.php:1211 -->

Los otros auditores de una fila DEBEN ser como máximo 5, sin repetir y sin el líder. Todos deben estar activos con perfil Auditor al ajustar y al programar.

---

## Contradicciones registradas

1. **Año calendario o año de contrato** (13.4). El PHP cuenta los ciclos anuales por año calendario y pone el límite el 31 de diciembre. La decisión del 2026-10-20 los cuenta desde `inicio_contrato`. Manda la decisión: los ciclos anuales también exigen `inicio_contrato`.
2. **Suministro y satélite** (16.1). El PHP ya los dejaba fuera por modalidad. La decisión y el Anexo 3 p.15 lo vuelven regla explícita. No hay cambio de comportamiento, solo un invariante y un escenario.
3. **Primer día posible.** El PHP usa hoy + 15 días calendario. `Plazos.java` y la agenda usan el hábil anterior a la fecha − 15. Pueden no coincidir cuando hoy no es hábil. Se especifica la regla de `Plazos.java`.
4. **Componentes.** El PHP programa sin componentes. Java exige al menos uno (Anexo 5, prueba 11).
5. **Rol «otro».** `Reparto.esAsistencial("otro")` es verdadero (recibe partes asistenciales), pero la propuesta no lo deja liderar (13.2). No se contradicen, pero conviene no unificar las dos reglas sin querer.
6. **Auditor asignado y ciudades principales.** El Anexo 3 p.9 exige un auditor asignado por ESM de dispensación (y que sea QF en el HMC y el Hospital Naval) y equipos en las 8 ciudades. El PHP no lo verifica: solo usa el asignado si lo hay.

## Datos que la reescritura Java debe agregar

1. **Reglas puras** en `reglas/PropuestaAgenda.java`: `CICLOS`, `ROLES_LIDER`, `KM_POR_VISITA = 60`, `distanciaKm` (haversine, 6371 km), `puedeLiderar`, `generar(puntos, auditores, coords, dias, porDia, kmMismoDia, ocupados, fijos)`, `cabe` y `dias(Plazos, hoy, hasta)` con la regla de `Plazos.limiteNotificacion`; las 26 pruebas de `tests/propuesta_agenda_test.php` pasan a JUnit.
2. **`propuestas_agenda`**: `id`, `ciclo` (CHECK de los 4), `periodo_desde`, `hasta`, `estado` (CHECK `borrador|confirmada|descartada`), `resumen` jsonb, `creado_por` (FK usuarios), `creado`, `confirmado_por`, `confirmado`; índice único parcial `(ciclo) WHERE estado = 'borrador'`.
3. **`propuestas_agenda_items`**: `id`, `propuesta_id` (FK RESTRICT), `punto_id`, `auditor_id` nulo, `fecha` nula, `fijo`, `km`, `motivo` (≤200), `alertas` jsonb, `visita_id` nulo, `resultado` (≤500); `UNIQUE (propuesta_id, punto_id)` e índice `(auditor_id, fecha)`. Los otros auditores van en `propuestas_agenda_items_otros (item_id, usuario_id, orden)` con FK, no en una lista separada por comas como en el PHP.
4. **Año de contrato** (13.4): función `(inicio_contrato, N) → [desde, hasta]` que usan la cobertura, los pendientes y los días; la API recibe el año de contrato en lugar de `anio`.
5. **API** `/api/propuesta`, solo Coordinador (`Pantallas.PROPUESTA`): `GET datos`, `GET {id}`, `POST generar`, `PUT items/{id}`, `POST items/{id}/liberar`, `POST {id}/recalcular`, `POST {id}/confirmar`, `POST {id}/descartar`. Eventos de bitácora `propuesta_generada` (inserción), `propuesta_confirmada` (inserción) y `propuesta_descartada` (actualización).
6. **Confirmar** llama a `ServicioAgenda.programar` con `notificar = true` y los componentes que se decidan; una transacción por fila, y el resultado se guarda aunque falle la notificación.
7. **Consultas de apoyo**: auditores activos con perfil Auditor (rol `otro` si no tienen fila, municipio base, carga como líder o apoyo en el rango); ocupación por auditor y día por `coalesce(llega_desde, municipio)`; coordenadas de `par_municipios`.
8. **Datos de V7**: no hay ningún punto NC, así que el ciclo `central` tiene total 0 hasta que el coordinador lo cree. Los 185 puntos son 74 ESM de dispensación, 50 de suministro, 51 de satélite y 10 FA. Ningún ESM trae `auditor_asignado` y ningún auditor trae `municipio_base`: sin esos datos, todo se mide como «sin ubicación».
9. **Pantalla React** `/propuesta`: cobertura por ciclo («Proponer agenda», «Proponer de nuevo», «Ver borrador», «Continuar confirmación»), explicación plegable, tabla editable (fecha con control de fecha, líder, otros, candado), recalcular, confirmar y descartar; sin desplazamiento horizontal en el celular.
10. **Reporte de distribución auditor–punto** (13.3) y **avisos de ciudades principales y ESM sin auditor asignado**, si se confirman (ver sus marcas `incierto`).

<!-- pendiente: controladores/agenda.controlador.php programar() (cubierto en agenda-notificacion), controladores/maestros.controlador.php config() (parámetros, cubierto en catalogos-festivos-plazos), modelos/auditores.modelo.php (municipio base, cubierto en maestros-carga-masiva), app/Support/Exportacion.php (las dos tablas entran en la exportación: ver exportacion) -->
