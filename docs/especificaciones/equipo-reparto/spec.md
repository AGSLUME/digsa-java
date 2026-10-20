# Especificación: equipo-reparto

> Extraída por spec-miner el 2026-10-20 del PHP de referencia (develop, commit db2e330).
> Fuentes: app/Support/Reparto.php, controladores/agenda.controlador.php (validar, guardarEquipoYReparto, repartirDeNuevo, iniciar, moverParte, reasignar, reabrir, nombresPartes), controladores/checklist.controlador.php (acceso, permisos, esPropia, guardar, guardarParte, registrarCorreccion, subir, eliminar, enviar), modelos/visitas.modelo.php (equipo, guardarEquipo, reparto, guardarReparto, moverParte), modelos/checklist.modelo.php (estadoParaFusion, marcarRevision, nuevaRevision), app/Support/EstadosVisita.php, app/Support/Plazos.php (alerta QF), Database/Structure/005_agenda.sql, Database/Structure/006_checklist_visita.sql, tests/reparto_test.php, tests/reglas_test.php, tests/plazos_test.php, tests/e2e_local.php, tests/ui/ui_fase1.js, tests/ui/ui_fase2.js, docs/decisiones_2026-10-15.md, docs/dudas_pendientes.md.
> Relacionadas: programar, editar y notificar en `agenda-notificacion`; el guardado del checklist con revisión en `checklist`; el acta y el envío en `acta-envio`; el enlace del OPL tras reabrir en `opl-respuesta`.

---

### Requirement: Equipo de la visita
<!-- id: ControladorAgenda.validar#equipo -->
<!-- entidades: Visita, VisitaAuditor, Auditor -->
<!-- impuesto_en: controladores/agenda.controlador.php ControladorAgenda::validar(), guardarEquipoYReparto(); modelos/visitas.modelo.php ModeloVisitas::guardarEquipo(), equipo() -->
<!-- decision: 2 -->

Cada visita DEBE tener un auditor líder obligatorio (activo, con perfil Auditor; también en `visitas.auditor_id`) y de 0 a 5 otros auditores activos con perfil Auditor, distintos del líder y sin repetir; el equipo se guarda reemplazando el anterior (líder con orden 0, los demás con orden 1…N en el orden recibido) y se lista con el líder primero; una visita de un solo auditor es válida.

#### Scenario: Líder y dos otros auditores
<!-- test: tests/e2e_local.php:664 -->
<!-- test: tests/ui/ui_fase1.js:72 -->
- **CUANDO** el coordinador programa con líder QF, un ingeniero y un regente
- **ENTONCES** el detalle trae 3 integrantes, el primero marcado líder, cada uno con su rol

#### Scenario: Más de cinco
<!-- test: tests/reparto_test.php:41 -->
- **CUANDO** se envían 6 otros auditores
- **ENTONCES** responde 422 "Máximo 5 auditores además del líder."

#### Scenario: Otro auditor inactivo
- **CUANDO** uno de los otros auditores está inactivo o no tiene perfil Auditor
- **ENTONCES** responde 422 "Alguno de los otros auditores no está activo o no tiene el perfil Auditor."

---

### Requirement: Rol técnico del auditor
<!-- id: Reparto.ROLES -->
<!-- entidades: Auditor -->
<!-- impuesto_en: app/Support/Reparto.php Reparto::ROLES, esAsistencial(); modelos/visitas.modelo.php (COALESCE(a.rol, 'otro')) -->
<!-- decision: 2a -->
<!-- duda: 8.1 | 8.8 -->

Cada auditor DEBE tener uno de cinco roles: `quimico_farmaceutico` (Químico farmacéutico), `regente` (Regente de farmacia), `auditor_salud` (Auditor en salud), `ingeniero_sistemas` (Ingeniero de sistemas) u `otro` (Otro); sin rol registrado cuenta como `otro`; todos menos el ingeniero son talento asistencial (incluido `otro`); cambiar el rol de un auditor después de programar NO DEBE recalcular el reparto de sus visitas.

#### Scenario: Asistenciales
<!-- test: tests/reparto_test.php:45 -->
- **CUANDO** se pregunta si cada rol es asistencial
- **ENTONCES** QF, regente, auditor en salud y otro sí; el ingeniero de sistemas no

---

### Requirement: El equipo debe incluir químico farmacéutico donde se exige
<!-- id: Plazos.alertas#qf -->
<!-- entidades: Punto, VisitaAuditor -->
<!-- impuesto_en: app/Support/Plazos.php Plazos::alertas(); controladores/agenda.controlador.php enriquecer(), previsualizar(); modelos/visitas.modelo.php equipo_qf -->
<!-- decision: 2a | Anexo 3 p.9 -->

Cuando el punto tiene marcada la casilla "Exige químico farmacéutico", la visita DEBE mostrar la advertencia "En {punto} el equipo debe incluir un químico farmacéutico (Anexo 3 p.9)…" si ningún integrante del equipo (líder u otro) tiene el rol químico farmacéutico; es una advertencia, no impide programar.

#### Scenario: Basta un integrante QF
<!-- test: tests/e2e_local.php:659 -->
- **CUANDO** el líder no es QF pero otro auditor del equipo sí
- **ENTONCES** no aparece la advertencia

#### Scenario: Ninguno es QF
<!-- test: tests/plazos_test.php:83 -->
<!-- test: tests/e2e_local.php:249 -->
- **CUANDO** el punto exige QF y nadie del equipo lo es
- **ENTONCES** aparece la advertencia (`warn`)

#### Scenario: Sin la casilla no hay alerta
<!-- test: tests/plazos_test.php:87 -->
- **CUANDO** el punto se llama "Hospital Militar Central" pero no tiene la casilla
- **ENTONCES** no hay alerta

---

### Requirement: Partes del formulario
<!-- id: Reparto.partes -->
<!-- entidades: VisitaReparto, Componente -->
<!-- impuesto_en: app/Support/Reparto.php Reparto::partes(), SISTEMAS, PESO_OBLIGACIONES -->
<!-- decision: 2c | 15 -->

Las partes del formulario de una visita DEBEN ser: cada componente del checklist que aplica a la unidad del punto, con peso = número de sus ítems; `OBL` (obligaciones de la minuta) con peso fijo 10; y `SIS` solo si en el equipo hay un ingeniero de sistemas, con los ítems SO-01, NC-04, NC-05, P04-16 e I05-16 de los componentes incluidos (que salen de su componente; un componente que se queda sin ítems desaparece); SO-10 queda en su componente.

#### Scenario: ESM sin ingeniero
<!-- test: tests/reparto_test.php:60 -->
- **CUANDO** se calculan las partes de un ESM sin ingeniero
- **ENTONCES** son los componentes del ESM con todos sus ítems y `OBL = 10`, sin `SIS`

#### Scenario: ESM con ingeniero
<!-- test: tests/reparto_test.php:64 -->
<!-- test: tests/reparto_test.php:65 -->
- **CUANDO** hay ingeniero
- **ENTONCES** los ítems de sistemas pasan a `SIS`, el componente que solo tenía P04-16 desaparece y SO-10 sigue en su componente

#### Scenario: Sin ítems de sistemas o sin componentes
<!-- test: tests/reparto_test.php:75 -->
<!-- test: tests/reparto_test.php:78 -->
- **CUANDO** hay ingeniero pero los componentes no tienen ítems de sistemas, o la unidad no tiene componentes
- **ENTONCES** no hay `SIS`; en el segundo caso solo queda `OBL`

---

### Requirement: Reparto automático
<!-- id: Reparto.repartir -->
<!-- entidades: VisitaReparto -->
<!-- impuesto_en: app/Support/Reparto.php Reparto::repartir(); controladores/agenda.controlador.php repartirDeNuevo() -->
<!-- decision: 2c | 15 -->
<!-- duda: 8.2 -->

El reparto DEBE ser determinista: las partes se recorren por peso descendente (empate: código ascendente) y cada una va al integrante con menos carga acumulada (empate: el primero en el orden del equipo, líder primero); `SIS` va al ingeniero menos cargado; lo demás va al talento asistencial, o a todo el equipo si no hay asistenciales; con un solo integrante, todo es suyo.

#### Scenario: Equipo mixto
<!-- test: tests/reparto_test.php:91 -->
<!-- test: tests/reparto_test.php:93 -->
- **CUANDO** se reparten OBL 10, C05 6, C01 4, C02 2 y SIS 4 entre regente líder (7), QF (8) e ingeniero (9)
- **ENTONCES** OBL→7, C05→8, C01→8, C02→7, SIS→9; el ingeniero solo recibe SIS

#### Scenario: Empates
<!-- test: tests/reparto_test.php:97 -->
<!-- test: tests/reparto_test.php:100 -->
- **CUANDO** dos partes pesan igual, o dos integrantes tienen la misma carga
- **ENTONCES** va primero el código menor, y gana el primero del equipo (no el id menor)

#### Scenario: Solo ingenieros
<!-- test: tests/reparto_test.php:115 -->
<!-- test: tests/reparto_test.php:116 -->
- **CUANDO** el equipo es de dos ingenieros
- **ENTONCES** SIS va a uno de ellos y lo demás se reparte entre los dos por peso

#### Scenario: Equidad
<!-- test: tests/reparto_test.php:131 -->
<!-- test: tests/reparto_test.php:132 -->
- **CUANDO** se reparten 13 partes entre 6 asistenciales
- **ENTONCES** todos reciben carga y la diferencia entre el más y el menos cargado no pasa de 10

#### Scenario: En la visita real
<!-- test: tests/e2e_local.php:665 -->
<!-- test: tests/e2e_local.php:668 -->
- **CUANDO** se programa con QF, regente e ingeniero
- **ENTONCES** SIS es del ingeniero, lo demás se reparte entre QF y regente y el ingeniero no tiene otra parte

---

### Requirement: Cuándo se reparte
<!-- id: ControladorAgenda.repartirDeNuevo -->
<!-- entidades: VisitaReparto, ChecklistVersion -->
<!-- impuesto_en: controladores/agenda.controlador.php guardarEquipoYReparto(), repartirDeNuevo(), iniciar() -->
<!-- duda: 8.8 -->

El reparto DEBE rehacerse automáticamente (asignado por `sistema`) cada vez que se guarda el equipo (programar, editar, reasignar), con la versión vigente del checklist si la visita no ha iniciado; al iniciar, si las partes guardadas no son exactamente las de la versión con que se ejecuta (o no hay reparto), DEBE repartirse de nuevo con esa versión y, si había reparto, el historial registra "Reparto rehecho"; sin versión vigente no se reparte.

#### Scenario: Cambio de versión entre programar e iniciar
- **CUANDO** la visita se programó con una versión y al iniciar la vigente es otra con partes distintas
- **ENTONCES** se reparte de nuevo con la versión de ejecución y queda "Reparto rehecho" en el historial

---

### Requirement: Mover una parte entre auditores
<!-- id: ControladorAgenda.moverParte -->
<!-- entidades: VisitaReparto, VisitaHistorial -->
<!-- impuesto_en: controladores/agenda.controlador.php ControladorAgenda::moverParte(); app/Support/Reparto.php Reparto::puedeRecibir(); modelos/visitas.modelo.php ModeloVisitas::moverParte() -->
<!-- decision: 2c -->
<!-- duda: 8.3 | 8.10 -->

El coordinador DEBE poder pasar una parte a otro integrante activo del equipo mientras la visita no esté cerrada ni cancelada (también en ejecución), bloqueando la visita; `SIS` solo a un ingeniero de sistemas; las demás partes solo a un asistencial si el equipo tiene alguno (si no, a cualquiera); el cambio queda con `asignado_por` = coordinador y en el historial como "Reparto cambiado" ("{parte}: {antes} → {después}"); asignarla a quien ya la tiene responde "Sin cambios."; la pantalla ofrece mover partes solo si el equipo tiene más de un integrante.

#### Scenario: Respetar el rol
<!-- test: tests/e2e_local.php:733 -->
<!-- test: tests/e2e_local.php:735 -->
<!-- test: tests/reparto_test.php:136 -->
<!-- test: tests/reparto_test.php:140 -->
- **CUANDO** se intenta pasar SIS a un regente, o un componente asistencial al ingeniero habiendo asistenciales
- **ENTONCES** responde 422 ("Los ítems de sistemas solo se asignan a un ingeniero de sistemas." / "Esta parte es del equipo asistencial…")

#### Scenario: Equipo sin asistenciales
<!-- test: tests/reparto_test.php:143 -->
<!-- test: tests/reparto_test.php:147 -->
- **CUANDO** el equipo es solo de ingenieros
- **ENTONCES** cualquier parte puede ir al ingeniero; SIS nunca va a un asistencial

#### Scenario: Mover y perder la escritura
<!-- test: tests/e2e_local.php:737 -->
<!-- test: tests/e2e_local.php:738 -->
- **CUANDO** el coordinador pasa la parte del regente a otro asistencial
- **ENTONCES** responde 200 y el regente ya no puede subir soportes a esa parte (403)

#### Scenario: El auditor no mueve partes
<!-- test: tests/e2e_local.php:739 -->
- **CUANDO** un auditor pide mover una parte
- **ENTONCES** recibe 403

#### Scenario: Destino no válido
- **CUANDO** la parte no existe en la visita, el destino no es del equipo o está inactivo, o la visita está cerrada o cancelada
- **ENTONCES** responde 422 con el motivo

---

### Requirement: Qué escribe cada usuario en el checklist
<!-- id: ControladorChecklist.permisos -->
<!-- entidades: VisitaReparto, VisitaAuditor -->
<!-- impuesto_en: controladores/checklist.controlador.php ControladorChecklist::acceso(), permisos(), esPropia() -->
<!-- decision: 2 | 2b | 2.2 (novena ronda) -->
<!-- duda: 8.4 -->

Con la visita en ejecución, cada integrante DEBE escribir solo sus partes y ver las de los demás en solo lectura; el único integrante de un equipo de uno, y el coordinador que no es del equipo en una visita reabierta, escriben todo el formulario; el líder es el integrante que es `auditor_id` (o el único integrante); el coordinador que corrige NO es líder.

#### Scenario: Ingeniero en su parte
<!-- test: tests/ui/ui_fase1.js:41 -->
<!-- test: tests/ui/ui_fase1.js:46 -->
<!-- test: tests/ui/ui_fase1.js:47 -->
- **CUANDO** el ingeniero abre el checklist
- **ENTONCES** por defecto ve solo los ítems de sistemas; lo ajeno se ve bloqueado con "Lo diligencia …"

#### Scenario: Datos de la visita solo del líder
<!-- test: tests/ui/ui_fase1.js:37 -->
- **CUANDO** quien no es líder abre el checklist
- **ENTONCES** los datos de la visita (p. ej. el alcance) están en solo lectura

#### Scenario: Quien no es del equipo
<!-- test: tests/e2e_local.php:699 -->
<!-- test: tests/e2e_local.php:700 -->
- **CUANDO** un auditor que no es del equipo lee el estado o guarda
- **ENTONCES** recibe 403

#### Scenario: Soportes por parte
<!-- test: tests/e2e_local.php:711 -->
<!-- test: tests/e2e_local.php:712 -->
<!-- test: tests/e2e_local.php:725 -->
- **CUANDO** el ingeniero sube un soporte a SO-01, a un ítem del QF, o quita un soporte de otra parte por su id
- **ENTONCES** el primero responde 200 y los otros 403 "Esa parte del formulario está asignada a otro auditor de la visita."

---

### Requirement: Parte a la que pertenece una clave
<!-- id: Reparto.parteDe -->
<!-- entidades: VisitaArchivo -->
<!-- impuesto_en: app/Support/Reparto.php Reparto::parteDe() -->

Las claves `minuta:…` y `ob:…` DEBEN pertenecer a `OBL`; `it:{código}` a `SIS` si el código es de sistemas y hay parte SIS, o si no al componente del ítem; cualquier otra clave o un ítem desconocido no pertenece a ninguna parte.

#### Scenario: Casos
<!-- test: tests/reparto_test.php:167 -->
- **CUANDO** se consulta `ob:OB-OPL-01`, `it:SO-01` con y sin SIS, `it:SO-10`, `it:ZZ-99` o `xx:P01-01`
- **ENTONCES** da OBL, SIS / C01, C01, ninguna, ninguna

---

### Requirement: Guardado por partes sin pisarse
<!-- id: ControladorChecklist.guardarParte -->
<!-- entidades: VisitaChecklist -->
<!-- impuesto_en: controladores/checklist.controlador.php guardarParte(); app/Support/Reparto.php Reparto::fusionar(); modelos/checklist.modelo.php estadoParaFusion(), marcarRevision() -->
<!-- decision: 2b | 2c -->
<!-- duda: 8.3 -->

Cuando un integrante con solo una parte guarda, el servidor DEBE bloquear la visita y el checklist, y fusionar sobre el estado guardado (o, si es el primer guardado de un seguimiento, sobre su estado de partida) solo lo suyo: las respuestas de los ítems de sus partes (agregar, cambiar o quitar), las minutas solo si es dueño de `OBL`, y las secciones `visita`, `perfil`, `prestadores`, `comps`, `w5`, `cierre` y `envio` solo si es el líder (un no líder nunca las crea: quedan vacías); cualquier otra clave de primer nivel se descarta; luego aplica las mismas reglas del guardado completo (ver `checklist`); si no cambió nada responde la revisión actual; la respuesta DEBE incluir las partes vigentes del auditor para que su pantalla se entere si el coordinador las movió.

#### Scenario: Tres auditores a la vez
<!-- test: tests/e2e_local.php:686 -->
<!-- test: tests/e2e_local.php:688 -->
<!-- test: tests/e2e_local.php:689 -->
- **CUANDO** líder, regente e ingeniero guardan con revisiones viejas
- **ENTONCES** los tres guardan sin 409, cada uno solo cambió lo suyo y el cierre sigue siendo el del líder

#### Scenario: Un no líder no cuela secciones del líder
<!-- test: tests/e2e_local.php:695 -->
<!-- test: tests/reparto_test.php:222 -->
<!-- test: tests/reparto_test.php:246 -->
- **CUANDO** el ingeniero envía firmas, destinatarios del envío y una clave inventada
- **ENTONCES** no se guardan; en el primer guardado de un no líder las secciones del líder quedan vacías

#### Scenario: Pantalla vieja del mismo auditor
<!-- test: tests/e2e_local.php:697 -->
- **CUANDO** un auditor guarda con una revisión menor que la última que él mismo guardó (otra pestaña) o que la última corrección de todo el formulario
- **ENTONCES** responde 409 "Hay cambios más recientes en esta visita…" y no borra lo guardado después

#### Scenario: Partes vigentes
<!-- test: tests/e2e_local.php:698 -->
- **CUANDO** el ingeniero guarda
- **ENTONCES** la respuesta trae `partes = ['SIS']`

#### Scenario: Reglas de la fusión
<!-- test: tests/reparto_test.php:205 -->
<!-- test: tests/reparto_test.php:214 -->
<!-- test: tests/reparto_test.php:239 -->
<!-- test: tests/reparto_test.php:257 -->
- **CUANDO** el líder dueño de OBL y C02 envía cambios en ítems de otros, en SIS y en un ítem desconocido; o un auditor quita la calificación de un ítem suyo
- **ENTONCES** solo cambia lo suyo; la calificación quitada desaparece; la fusión es idempotente

---

### Requirement: El acta es del auditor líder
<!-- id: ControladorChecklist.enviar#lider -->
<!-- entidades: Visita, VisitaActa -->
<!-- impuesto_en: controladores/checklist.controlador.php enviar() -->
<!-- decision: 2 -->

Solo el auditor líder DEBE generar y enviar el acta (y firmar y diligenciar 5W2H y cierre); los demás integrantes sí ven el borrador del acta.

#### Scenario: Otro integrante envía
<!-- test: tests/e2e_local.php:721 -->
<!-- test: tests/ui/ui_fase1.js:49 -->
- **CUANDO** el ingeniero pide enviar el acta
- **ENTONCES** recibe 403; en su pantalla el botón está deshabilitado

#### Scenario: Borrador para el equipo
<!-- test: tests/e2e_local.php:722 -->
- **CUANDO** un integrante pide el borrador del acta
- **ENTONCES** recibe un PDF

---

### Requirement: Reasignar auditores
<!-- id: ControladorAgenda.reasignar -->
<!-- entidades: Visita, VisitaAuditor, VisitaReparto, VisitaHistorial, VisitaNotificacion -->
<!-- impuesto_en: controladores/agenda.controlador.php ControladorAgenda::reasignar(), aplicarReasignacion() -->
<!-- decision: 26 | 28 -->
<!-- duda: 9.8 | 9.9 | 8.10 -->

El coordinador DEBE poder cambiar el equipo (líder y otros) de una visita `notificada` o `reprogramada` que no ha iniciado, con motivo obligatorio (máximo 800 caracteres) y las mismas reglas del equipo; en una transacción con la visita bloqueada se actualiza el líder, se guarda el equipo y se reparte de nuevo; si el equipo resultante es el mismo, todo se deshace; el historial DEBE registrar "Equipo reasignado" ("{antes} → {después}. Motivo: …"); si el aviso no es `no-opl` y se marcó `notificar` (marcado por defecto, opcional), DEBE reenviarse la notificación con asunto "… · cambio de equipo auditor"; si no sale, queda la alerta de cambio sin notificar y la acción `notificar`; en una `reprogramada`, el reenvío la deja `notificada`.

#### Scenario: Nuevo equipo y reenvío
<!-- test: tests/e2e_local.php:915 -->
<!-- test: tests/e2e_local.php:917 -->
- **CUANDO** el coordinador cambia el equipo de una notificada con motivo y reenvío
- **ENTONCES** sale el correo, queda el historial, la visita sigue `notificada` y el auditor que salió ya no la ve en Mis visitas

#### Scenario: Programada sin notificar
<!-- test: tests/e2e_local.php:909 -->
- **CUANDO** se intenta reasignar una visita `programada`
- **ENTONCES** responde 422 (se edita)

#### Scenario: Mismo equipo o sin motivo
<!-- test: tests/e2e_local.php:910 -->
<!-- test: tests/e2e_local.php:911 -->
- **CUANDO** el equipo no cambia, o falta el motivo
- **ENTONCES** responde 422 ("El equipo es el mismo: no hay cambios que guardar." / "Registre el motivo…")

#### Scenario: Visita a necesidad
<!-- test: tests/e2e_local.php:963 -->
- **CUANDO** se intenta reasignar una visita a necesidad (nunca queda notificada)
- **ENTONCES** responde 422; nunca se avisa al OPL

#### Scenario: Auditor
<!-- test: tests/e2e_local.php:918 -->
- **CUANDO** un auditor pide reasignar
- **ENTONCES** recibe 403

#### Scenario: Pantalla
<!-- test: tests/ui/ui_fase2.js:91 -->
<!-- test: tests/ui/ui_fase2.js:93 -->
- **CUANDO** el coordinador abre el detalle de una notificada
- **ENTONCES** tiene "Reasignar auditores" con el líder precargado, buscador de otros auditores y motivo

---

### Requirement: Reabrir una visita cerrada
<!-- id: ControladorAgenda.reabrir -->
<!-- entidades: Visita, VisitaChecklist, VisitaHistorial -->
<!-- impuesto_en: controladores/agenda.controlador.php ControladorAgenda::reabrir(); app/Support/EstadosVisita.php MOTIVOS_REAPERTURA; modelos/checklist.modelo.php nuevaRevision() -->
<!-- decision: 1 | 2.2 (novena ronda) | Anexo 5 prueba 10 -->
<!-- duda: 8.5 | 8.6 | 11.6 -->

Solo el coordinador o el administrador DEBE poder reabrir una visita `cerrada`, con motivo `soporte` ("Faltó cargar un anexo o soporte") o `cliente` ("Necesidad del cliente (DIGSA)") y descripción de 10 a 800 caracteres; en una transacción con la visita bloqueada pasa a `ejecucion` (UPDATE condicionado a `cerrada`), suma una reapertura, sube la revisión del checklist (el acta nueva es de otra revisión y se envía a todos) y registra "Reabierta" con motivo y descripción y el evento de bitácora `visita_reabierta`; al enviar de nuevo se genera un acta nueva y la anterior se conserva con su huella; al cerrarse de nuevo NO DEBE programarse otro seguimiento si ya existe uno.

#### Scenario: Reabrir
<!-- test: tests/e2e_local.php:746 -->
<!-- test: tests/ui/ui_fase1.js:77 -->
- **CUANDO** el coordinador reabre una cerrada por soporte faltante con descripción
- **ENTONCES** la visita queda `ejecucion` con "Reabierta" en el historial

#### Scenario: Motivo no permitido o auditor
<!-- test: tests/e2e_local.php:742 -->
<!-- test: tests/e2e_local.php:743 -->
<!-- test: tests/reglas_test.php:62 -->
- **CUANDO** el motivo es "otro", o quien reabre es un auditor
- **ENTONCES** responde 422, o 403

#### Scenario: Solo desde cerrada
<!-- test: tests/reglas_test.php:61 -->
- **CUANDO** la visita está en ejecución o cancelada
- **ENTONCES** responde 422 "Solo se reabre una visita cerrada."

#### Scenario: Cerrar de nuevo
<!-- test: tests/e2e_local.php:760 -->
- **CUANDO** la visita reabierta se cierra otra vez
- **ENTONCES** no se duplica la visita de seguimiento

---

### Requirement: Corrección del coordinador en una visita reabierta
<!-- id: ControladorChecklist.registrarCorreccion -->
<!-- entidades: VisitaChecklist, VisitaHistorial -->
<!-- impuesto_en: controladores/checklist.controlador.php guardar(), registrarCorreccion(); app/Support/Reparto.php Reparto::cambios() -->
<!-- decision: 2.2 (octava y novena ronda) -->
<!-- duda: 8.4 -->

En una visita reabierta, el coordinador que no es del equipo DEBE poder corregir todo el formulario, pero NO DEBE cambiar las firmas del 5W2H ni del cierre (se conservan las guardadas) ni generar o enviar el acta; cada guardado que cambie algo en una visita con reaperturas DEBE dejar en el historial "Corrección" con quién y las rutas cambiadas (`resp.X`, `minutas.opl`, `w5.filas.X`, `cierre.rec`…, ordenadas, detalle hasta 1000 caracteres); durante la ejecución normal el coordinador solo consulta.

#### Scenario: Corrige con trazabilidad
<!-- test: tests/e2e_local.php:751 -->
<!-- test: tests/e2e_local.php:754 -->
- **CUANDO** el coordinador guarda una corrección en la visita reabierta
- **ENTONCES** queda "Corrección" a su nombre y las firmas no cambian

#### Scenario: No envía el acta
<!-- test: tests/e2e_local.php:755 -->
- **CUANDO** el coordinador que corrige pide enviar
- **ENTONCES** recibe 403 (lo envía el líder)

#### Scenario: Rutas cambiadas
<!-- test: tests/reparto_test.php:277 -->
<!-- test: tests/reparto_test.php:280 -->
<!-- test: tests/reparto_test.php:282 -->
- **CUANDO** se comparan dos estados
- **ENTONCES** se listan las rutas que cambiaron, aparecieron o desaparecieron, ordenadas; un cambio dentro de un ítem cuenta como `resp.X`

#### Scenario: Durante la ejecución
<!-- test: tests/e2e_local.php:339 -->
- **CUANDO** el coordinador intenta guardar una visita en ejecución que no ha sido reabierta
- **ENTONCES** recibe 403

<!-- incierto: cada autoguardado de la visita reabierta deja una entrada "Corrección" y subir o quitar anexos no la deja (prioridades 2 y 3 de la fase 1, no corregidas). -->

---

### Invariant: Un solo líder por visita
<!-- entidades: VisitaAuditor -->
<!-- impuesto_en: Database/Structure/005_agenda.sql visita_auditores.lider_de, uq_visaud_un_lider -->
<!-- verificado_por: tests/e2e_local.php:707 -->

Una visita NO DEBE tener dos integrantes con función `lider`; las funciones son `lider` y `apoyo` (otro auditor) y un usuario aparece una sola vez por visita.

---

### Invariant: El reparto es del equipo
<!-- entidades: VisitaReparto -->
<!-- impuesto_en: Database/Structure/005_agenda.sql fk_reparto_miembro, visita_reparto PK (visita_id, parte) -->

Cada parte de una visita DEBE tener exactamente un dueño y ese dueño DEBE ser integrante del equipo; al reemplazar el equipo se borra antes el reparto.

---

### Invariant: Constantes del reparto
<!-- entidades: VisitaReparto -->
<!-- impuesto_en: app/Support/Reparto.php SISTEMAS, MAX_OTROS, PESO_OBLIGACIONES, SECCIONES_LIDER -->
<!-- verificado_por: tests/reparto_test.php:39 -->

Ítems de sistemas: SO-01, NC-04, NC-05, P04-16, I05-16; máximo 5 otros auditores; peso de las obligaciones 10; secciones del líder: `visita`, `perfil`, `prestadores`, `comps`, `w5`, `cierre`, `envio`.

<!-- duda: valores repetidos entre el servidor y el JS (roles, secciones del líder): prioridad 3 de la fase 1, no corregida. -->
<!-- pendiente: vistas/checklist (JS de la pantalla por partes y del aviso al recargar), controladores/maestros.controlador.php (rol y documento del auditor), tests/ui/ui_de.js (reapertura en Edge) -->
