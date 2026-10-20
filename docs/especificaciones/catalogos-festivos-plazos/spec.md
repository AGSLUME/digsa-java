# Especificación: catalogos-festivos-plazos

> Extraída por spec-miner el 2026-10-20 del PHP de referencia (develop, commit db2e330).
> Fuentes: app/Support/Festivos.php, app/Support/Plazos.php, app/Support/Fechas.php, modelos/catalogos.modelo.php, controladores/agenda.controlador.php (plazos()), controladores/maestros.controlador.php (guardarConfiguracion), Database/Structure/002_catalogos.sql, Database/Structure/003_checklist.sql, Database/Data/002_catalogos.sql, Database/Data/003_checklist_v6.sql, tests/plazos_test.php, tests/reglas_test.php, tests/e2e_local.php, docs/decisiones_2026-10-15.md (12, 31), docs/dudas_pendientes.md (6.13, 9.11, fase 0).

---

### Requirement: Festivos de Colombia calculados para cualquier año
<!-- id: Festivos.delAnio -->
<!-- entidades: Festivo -->
<!-- impuesto_en: app/Support/Festivos.php Festivos::delAnio(), Festivos::pascua(), Festivos::esFestivo() -->
<!-- decision: decisión del usuario 2026-10-14 (festivos siempre al día) | Ley 51 de 1983 -->

El sistema DEBE calcular los festivos de cualquier año sin depender de una tabla: fijos el 1 de enero, 1 de mayo, 20 de julio, 7 de agosto, 8 y 25 de diciembre, Jueves y Viernes Santo; trasladados al lunes siguiente (si no caen en lunes) el 6 de enero, 19 de marzo, 29 de junio, 15 de agosto, 12 de octubre, 1 y 11 de noviembre, Ascensión (Pascua + 39), Corpus Christi (Pascua + 60) y Sagrado Corazón (Pascua + 68); la Pascua DEBE calcularse con el algoritmo gregoriano de Meeus/Jones/Butcher y las fechas repetidas DEBEN contarse una vez.

#### Scenario: 2026 y 2027
<!-- test: tests/reglas_test.php:28 -->
- **CUANDO** se calculan los festivos de 2026 y 2027
- **ENTONCES** son exactamente las 36 fechas del mockup v6, empezando por 2026-01-01, 2026-01-12, 2026-03-23, 2026-04-02, 2026-04-03

#### Scenario: Pascua
<!-- test: tests/reglas_test.php:29 -->
- **CUANDO** se calcula la Pascua de 2026
- **ENTONCES** es el 5 de abril

#### Scenario: Traslado al lunes
<!-- test: tests/reglas_test.php:30 -->
- **CUANDO** el 6 de enero de 2028 cae en jueves
- **ENTONCES** el festivo es el lunes 10 de enero de 2028

#### Scenario: Dos festivos el mismo día
<!-- test: tests/reglas_test.php:31 -->
<!-- test: tests/reglas_test.php:32 -->
- **CUANDO** en 2030 San Pedro y Sagrado Corazón caen ambos el lunes 1 de julio
- **ENTONCES** el año tiene 17 fechas (2028 y 2045 tienen 18)

---

### Requirement: Días hábiles
<!-- id: Plazos.esHabil -->
<!-- entidades: Festivo -->
<!-- impuesto_en: app/Support/Plazos.php Plazos::esHabil(), Plazos::sumarHabiles(), Plazos::restarHabiles(), Plazos::habilAnterior(); controladores/agenda.controlador.php ControladorAgenda::plazos() -->

Un día DEBE ser hábil si es de lunes a viernes, no es festivo calculado y no está en la tabla de ajustes (`par_festivos`); sumar o restar N días hábiles NO DEBE contar la fecha de partida.

#### Scenario: Sumar saltando fin de semana y festivo
<!-- test: tests/plazos_test.php:22 -->
- **CUANDO** se suman 5 hábiles al viernes 9-oct-2026 (el lunes 12 es festivo)
- **ENTONCES** el resultado es el lunes 19-oct-2026

#### Scenario: Restar saltando festivo
<!-- test: tests/plazos_test.php:23 -->
- **CUANDO** se restan 3 hábiles al martes 3-nov-2026 (el lunes 2 es festivo)
- **ENTONCES** el resultado es el miércoles 28-oct-2026

#### Scenario: Cambio de año
<!-- test: tests/plazos_test.php:24 -->
<!-- test: tests/plazos_test.php:25 -->
- **CUANDO** se suma 1 hábil al viernes 31-dic-2027, o al viernes 7-ene-2028
- **ENTONCES** da lunes 3-ene-2028, y martes 11-ene-2028 (el lunes 10 es Reyes trasladado)

#### Scenario: Festivo de ajuste
<!-- test: tests/plazos_test.php:26 -->
- **CUANDO** `par_festivos` tiene 2026-10-15 y se suma 1 hábil al 14-oct-2026
- **ENTONCES** da 16-oct-2026

#### Scenario: Partida en fin de semana o festivo
<!-- duda: 9.11 -->
- **CUANDO** la fecha de partida no es hábil (p. ej. una solicitud del sábado)
- **ENTONCES** el primer día contado es el siguiente hábil

---

### Requirement: Límite para notificar una visita
<!-- id: Plazos.limiteNotificacion -->
<!-- entidades: Visita, TipoVisita -->
<!-- impuesto_en: app/Support/Plazos.php Plazos::limiteNotificacion() -->
<!-- decision: Anexo 3 p.9 | decisión del usuario 2026-10-14 (día hábil anterior) -->

Según el aviso del tipo de visita, el límite DEBE ser: `opl` → la fecha de la visita menos `notif_dias_opl` días calendario (15) y, si ese día no es hábil, el hábil anterior; `modulo` → `notif_habiles_modulo` días hábiles (3 por defecto) antes de la visita; `no-opl` → sin límite.

#### Scenario: 15 días antes cae en domingo
<!-- test: tests/plazos_test.php:30 -->
- **CUANDO** la visita es el lunes 16-nov-2026
- **ENTONCES** el límite es el viernes 30-oct-2026

#### Scenario: 15 días antes cae en festivo
<!-- test: tests/plazos_test.php:31 -->
- **CUANDO** la visita es el martes 27-oct-2026 (15 días antes es el lunes festivo 12-oct)
- **ENTONCES** el límite es el viernes 9-oct-2026

#### Scenario: 15 días antes es hábil
<!-- test: tests/plazos_test.php:32 -->
- **CUANDO** la visita es el jueves 29-oct-2026
- **ENTONCES** el límite es el miércoles 14-oct-2026

#### Scenario: Visita a necesidad
<!-- test: tests/plazos_test.php:33 -->
<!-- decision: 31 -->
- **CUANDO** el aviso es `no-opl`
- **ENTONCES** no hay límite de notificación

---

### Requirement: Plazo de la visita a necesidad
<!-- id: Plazos.limiteNecesidad -->
<!-- entidades: Visita, Solicitud -->
<!-- impuesto_en: app/Support/Plazos.php Plazos::limiteNecesidad() -->
<!-- decision: 12 | Anexo 3 p.14 -->

La visita a necesidad DEBE hacerse a más tardar `necesidad_habiles` (5) días hábiles después de la fecha de la solicitud.

#### Scenario: Solicitud en jueves
<!-- test: tests/plazos_test.php:101 -->
- **CUANDO** la solicitud es del jueves 15-oct-2026
- **ENTONCES** el límite es el jueves 22-oct-2026

#### Scenario: Con festivo en medio
<!-- test: tests/plazos_test.php:103 -->
- **CUANDO** la solicitud es del viernes 13-nov-2026 y el lunes 16 es festivo
- **ENTONCES** el límite es el lunes 23-nov-2026

---

### Requirement: Alertas de plazo de una visita
<!-- id: Plazos.alertas -->
<!-- entidades: Visita, TipoVisita, Punto, Auditor -->
<!-- impuesto_en: app/Support/Plazos.php Plazos::alertas(), Plazos::nivel() -->
<!-- decision: 12 | 2a | Anexo 3 p.9 -->

Para una visita abierta (no cerrada ni cancelada) con fecha válida, el sistema DEBE calcular alertas de nivel `info`, `warn` o `bad`: notificación pendiente (programada o reprogramada) según el límite y el umbral (`aviso_umbral_opl` = 5, `aviso_umbral_modulo` = 1), plazo de la visita a necesidad, fecha pasada sin iniciar, punto que exige químico farmacéutico sin ese rol en el equipo y modalidad del punto distinta a la del tipo; el nivel de la visita DEBE ser el más grave.

#### Scenario: Notificación al OPL fuera de tiempo
<!-- test: tests/plazos_test.php:43 -->
- **CUANDO** hoy es 14-oct-2026 y una visita `opl` programada es el 20-oct
- **ENTONCES** hay alerta `bad` "Sin notificar y ya no se cumple la antelación mínima…" que cita los 15 días

#### Scenario: Límite hoy o dentro del umbral
<!-- test: tests/plazos_test.php:47 -->
<!-- test: tests/plazos_test.php:54 -->
<!-- test: tests/plazos_test.php:56 -->
- **CUANDO** faltan entre 0 y el umbral de días para el límite
- **ENTONCES** la alerta es `warn` ("hoy", "falta 1 día" o "faltan N días"); con más días es `info`; el umbral es parametrizable

#### Scenario: Ya notificada
<!-- test: tests/plazos_test.php:49 -->
- **CUANDO** la visita está notificada
- **ENTONCES** no hay alerta de notificación

#### Scenario: Visita a necesidad después del límite
<!-- test: tests/plazos_test.php:118 -->
<!-- test: tests/plazos_test.php:120 -->
<!-- test: tests/plazos_test.php:116 -->
- **CUANDO** la visita a necesidad está programada después del límite de 5 días hábiles desde la solicitud
- **ENTONCES** hay alerta `bad` que cita "5 días hábiles" y "Anexo 3 p.14"; justo en el límite no hay alerta

#### Scenario: Plazo de la necesidad vencido sin iniciar
<!-- test: tests/plazos_test.php:124 -->
<!-- test: tests/plazos_test.php:128 -->
<!-- test: tests/plazos_test.php:130 -->
- **CUANDO** hoy es posterior al límite y la visita a necesidad no está en ejecución
- **ENTONCES** hay alerta `bad` "El plazo de la visita a necesidad venció el …"; el mismo día del límite o en ejecución no la hay

#### Scenario: Visita a necesidad sin fecha de solicitud
<!-- test: tests/plazos_test.php:71 -->
<!-- test: tests/plazos_test.php:136 -->
<!-- test: tests/plazos_test.php:140 -->
- **CUANDO** la visita a necesidad no tiene fecha de solicitud (anterior a la fase 2), o es NULL o vacía
- **ENTONCES** no hay alertas de plazo ni de notificación, sin error

#### Scenario: Exige químico farmacéutico
<!-- test: tests/plazos_test.php:83 -->
<!-- test: tests/plazos_test.php:87 -->
- **CUANDO** el punto tiene marcada la casilla "exige QF" y ningún integrante del equipo tiene el rol químico farmacéutico
- **ENTONCES** hay alerta `warn`; el nombre del punto no cuenta, solo la casilla

#### Scenario: Cerrada o fecha no válida
<!-- test: tests/plazos_test.php:74 -->
<!-- test: tests/plazos_test.php:78 -->
- **CUANDO** la visita está cerrada o cancelada, o su fecha no es válida (2026-02-30)
- **ENTONCES** no hay alertas
<!-- incierto: el comentario de cabecera de Plazos.php aún dice que la app no pide la fecha de solicitud (decisión 4); el código sigue la decisión 12 que la revierte -->

---

### Requirement: Fechas y horas estrictas
<!-- id: Fechas.valida -->
<!-- entidades: — -->
<!-- impuesto_en: app/Support/Fechas.php Fechas::valida(), Fechas::hora() -->

Toda fecha que llega del navegador DEBE tener formato `AAAA-MM-DD` y existir en el calendario; toda hora DEBE tener formato `HH:MM` de 24 horas (00:00 a 23:59).

#### Scenario: Fechas
<!-- test: tests/reglas_test.php:35 -->
<!-- test: tests/reglas_test.php:36 -->
<!-- test: tests/reglas_test.php:37 -->
- **CUANDO** llega `2026-02-30`, `+1 year` o `2026-02-28`
- **ENTONCES** las dos primeras se rechazan y la tercera se acepta

#### Scenario: Horas
<!-- test: tests/reglas_test.php:38 -->
- **CUANDO** llega `24:00` o `08:30`
- **ENTONCES** la primera se rechaza y la segunda se acepta

---

### Requirement: Fecha en español
<!-- id: Plazos.fechaLarga -->
<!-- entidades: — -->
<!-- impuesto_en: app/Support/Plazos.php Plazos::fechaLarga() -->

Las fechas de los mensajes DEBEN escribirse en español de Colombia con el patrón "EEE d 'de' MMM 'de' y" (zona America/Bogota); sin soporte de internacionalización DEBEN caer a `AAAA-MM-DD`.

#### Scenario: 19 de octubre de 2026
<!-- test: tests/plazos_test.php:27 -->
- **CUANDO** se formatea 2026-10-19
- **ENTONCES** el texto contiene "oct"

---

### Requirement: Parámetros editables por el coordinador
<!-- id: ControladorMaestros.guardarConfiguracion -->
<!-- entidades: Parametro -->
<!-- impuesto_en: controladores/maestros.controlador.php ControladorMaestros::guardarConfiguracion(); modelos/catalogos.modelo.php ModeloCatalogos::guardarParametro() -->

El coordinador DEBE poder cambiar solo los parámetros editables, dentro de sus rangos: `notif_habiles_modulo` 1–30, `aviso_umbral_opl` 0–15, `aviso_umbral_modulo` 0–10, `max_seguimientos` 1–5, `recordatorio_seguimiento_habiles` 1–20, `visitas_por_dia` 1–6 y `km_mismo_dia` 1–300 (estos dos solo si vienen), `inicio_contrato` fecha válida o vacía, y `correo_digsa` lista de correos válidos de hasta 500 caracteres (solo si viene); un parámetro no editable NO DEBE cambiar.

#### Scenario: Valor fuera de rango
- **CUANDO** `aviso_umbral_opl` llega como 16 o como texto
- **ENTONCES** responde 422 "El valor de «aviso_umbral_opl» debe ser un número entre 0 y 15." y no se guarda nada

#### Scenario: Parámetro fijo del Anexo 3
- **CUANDO** se intenta guardar un parámetro con `editable = 0`
- **ENTONCES** la transacción se revierte con error

#### Scenario: Reglas de plazos leídas de los parámetros
- **CUANDO** se construyen los plazos de la agenda
- **ENTONCES** se toman `notif_dias_opl`, `notif_habiles_modulo`, `aviso_umbral_opl`, `aviso_umbral_modulo` y `necesidad_habiles` de `par_parametros` si son enteros, con los valores por defecto 15, 3, 5, 1 y 5, y los ajustes de `par_festivos`

---

### Invariant: Reimportar los catálogos no pisa lo que edita el coordinador
<!-- entidades: Parametro, Fuerza -->
<!-- impuesto_en: Database/Data/002_catalogos.sql (INSERT … ON DUPLICATE KEY UPDATE) -->
<!-- verificado_por: — -->

La carga de los catálogos DEBE poder repetirse: inserta o actualiza sin borrar; en `par_parametros` DEBE conservar el `valor` de los editables (solo impone el valor de los no editables) y actualizar descripción, fuente y editable; en `par_fuerzas` NO DEBE tocar `correos` (los registra el coordinador).

---

### Invariant: Parámetros fijos del Anexo 3
<!-- entidades: Parametro -->
<!-- impuesto_en: Database/Data/002_catalogos.sql par_parametros editable = 0 -->

`notif_dias_opl` = 15 (Anexo 3 p.9), `inicial_meses` = 3 (Anexo 3 p.12) y `necesidad_habiles` = 5 (Anexo 3 p.14) DEBEN ser no editables; son editables `notif_habiles_modulo` (3), `inicio_contrato` (vacío), `aviso_umbral_opl` (5), `aviso_umbral_modulo` (1), `max_seguimientos` (3), `correo_digsa` (vacío), `recordatorio_seguimiento_habiles` (5), `visitas_por_dia` (2) y `km_mismo_dia` (40).
<!-- duda: 6.13 (resuelta en la fase 0: max_seguimientos y recordatorio_seguimiento_habiles viven en 002_catalogos.sql) -->

---

### Invariant: Contenido de los catálogos
<!-- entidades: Departamento, Municipio, Unidad, Fuerza, Modalidad, TipoVisita -->
<!-- impuesto_en: Database/Data/002_catalogos.sql; Database/Structure/002_catalogos.sql -->
<!-- verificado_por: tests/e2e_local.php:199 -->

Los catálogos DEBEN traer: 33 departamentos (código DANE de 2 dígitos) y 1122 municipios (código de 5 dígitos, con latitud y longitud del DANE); unidades ESM, FA (farmacia alterna del OPL) y NC (nivel central del OPL); fuerzas EJC (DISAN EJC), ARC (DISAN ARC), FAC (JEFSA FAC) y NA (nivel central, sin sanidad); modalidades Dispensación, Suministro, Consignación y Satélite; y 9 tipos de visita con su aviso: inicial, anual y anexo (ESM de dispensación, `opl`), riesgo (`opl`), necesidad (`no-opl`), requerimiento (`modulo`), farmacia (FA, `opl`), central (NC, `opl`) y plan "Seguimiento a plan de mejoramiento" (`modulo`).

---

### Invariant: Regionales sin borrado
<!-- entidades: Regional -->
<!-- impuesto_en: Database/Structure/002_catalogos.sql par_regionales uq_regionales_nombre; modelos/catalogos.modelo.php ModeloCatalogos::guardarRegionales() -->
<!-- duda: 9.1 -->

Las regionales DEBEN tener nombre único (máximo 80 caracteres, máximo 30 regionales) y no borrarse nunca: guardar la lista deja activas exactamente las que vienen, crea las nuevas y desactiva las demás.

---

### Invariant: Versión del checklist publicada es inmutable
<!-- entidades: ChecklistVersion, Componente, Item -->
<!-- impuesto_en: Database/Structure/003_checklist.sql; Database/Data/003_checklist_v6.sql (INSERT sin ON DUPLICATE, en transacción) -->

Una versión publicada del checklist NO DEBE modificarse ni borrarse: un cambio sale como versión nueva (v7, v8…); publicar una versión que ya existe DEBE fallar (no se importa con "continuar ante errores"); cada visita queda ligada a la versión con que se ejecutó.

---

### Invariant: Una sola versión vigente del checklist
<!-- entidades: ChecklistVersion -->
<!-- impuesto_en: Database/Structure/003_checklist.sql chk_versiones.vigente_unica (columna generada) uq_versiones_vigente -->

Solo una versión DEBE estar vigente (la que usan las visitas nuevas); publicar una versión la deja vigente y quita la vigencia a las demás en la misma transacción.

---

### Invariant: Estructura del checklist v6
<!-- entidades: ChecklistVersion, Componente, Item, Vacio -->
<!-- impuesto_en: Database/Data/003_checklist_v6.sql; Database/Structure/003_checklist.sql uq_componentes, uq_items -->

La versión v6 DEBE tener 21 componentes (C00 "Validación de soportes de habilitación" con escala propia "Está habilitado / No está habilitado", C01…), 343 ítems con código único por componente, 5 vacíos de mayor riesgo y una guía de calificación de obligaciones; cada componente declara a qué unidades (ESM, FA, NC) aplica.

<!-- pendiente: Database/Data/003_checklist_v6.sql (contenido ítem por ítem: va en la capacidad checklist), generar_sql_v6.py (generador; no está en el repositorio leído), Database/Migraciones/ -->
