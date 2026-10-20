# Especificación: perfiles-alcance

> Extraída por spec-miner el 2026-10-20 del PHP de referencia (develop, commit db2e330).
> Fuentes: app/Support/Alcance.php, app/Support/Auth.php (tiene, tienePropio, revalidar, requerirAlcance), app/Support/Rutas.php, ajax/usuarios.ajax.php, modelos/usuarios.modelo.php, controladores/usuarios.controlador.php (enviarEnlaceClave), controladores/maestros.controlador.php (crearOVincularAuditor), Database/Data/001_perfiles.sql, Database/Structure/001_usuarios.sql, Database/Structure/004_maestros.sql (FK de alcance), tests/alcance_test.php, tests/reglas_test.php, tests/e2e_local.php, docs/decisiones_2026-10-15.md (10, 16, 20, 26, 27, 33-36, tabla "Perfiles de acceso (9)"), docs/dudas_pendientes.md (9.2, 9.10, 9.12, 15.3).

---

### Requirement: Alcance de datos según los perfiles
<!-- id: Alcance.de -->
<!-- entidades: Usuario, Perfil, Alcance -->
<!-- impuesto_en: app/Support/Alcance.php Alcance::de(); app/Support/Auth.php Auth::revalidar() -->
<!-- decision: 10 | 16 | 27 | 34 | 35 | tabla "Perfiles de acceso (9)" -->

El alcance del usuario DEBE recalcularse en cada petición así: Administrador, Coordinador o DIGSA → `todo`; si no, OPL nacional → `opl` (país sin visitas a necesidad pendientes); si no, el primero que tenga su dato en este orden: Sanidad → `fuerza` (alcance_fuerza), OPL regional → `regional` (alcance_regional), ESM → `esm` (alcance_punto), Auditor → `equipo` (su id); si ninguno aplica → `ninguno` (falla cerrado).

#### Scenario: Perfiles de alcance total
<!-- test: tests/alcance_test.php:40 -->
<!-- test: tests/alcance_test.php:43 -->
- **CUANDO** el usuario es Administrador, Coordinador o DIGSA, aunque tenga datos de alcance sobrantes
- **ENTONCES** el alcance es `todo`

#### Scenario: Perfiles acotados
<!-- test: tests/alcance_test.php:47 -->
<!-- test: tests/alcance_test.php:53 -->
<!-- test: tests/alcance_test.php:55 -->
<!-- test: tests/alcance_test.php:57 -->
- **CUANDO** el usuario es Sanidad con fuerza EJC, OPL regional con regional 3, ESM con punto 7 o Auditor con id 12
- **ENTONCES** el alcance es `fuerza=EJC`, `regional=3`, `esm=7` o `equipo=12`

#### Scenario: Perfil sin su dato
<!-- test: tests/alcance_test.php:62 -->
<!-- test: tests/alcance_test.php:64 -->
<!-- test: tests/alcance_test.php:66 -->
<!-- test: tests/alcance_test.php:68 -->
<!-- test: tests/alcance_test.php:76 -->
- **CUANDO** Sanidad no tiene fuerza (o vacía), ESM no tiene punto u OPL regional no tiene regional, o el registro no trae esas claves
- **ENTONCES** el alcance es `ninguno`, sin error

#### Scenario: Perfil retirado o mal escrito
<!-- test: tests/alcance_test.php:72 -->
<!-- test: tests/alcance_test.php:74 -->
- **CUANDO** el perfil es "Consulta" o "administrador" (otra grafía)
- **ENTONCES** no aporta alcance

#### Scenario: Varios perfiles
<!-- test: tests/alcance_test.php:80 -->
<!-- test: tests/alcance_test.php:82 -->
<!-- test: tests/alcance_test.php:87 -->
<!-- test: tests/alcance_test.php:93 -->
- **CUANDO** el usuario tiene Auditor + Coordinador, ESM + OPL nacional, los cuatro acotados con sus datos, o Sanidad sin fuerza + Auditor
- **ENTONCES** el alcance es `todo`, `opl`, `fuerza` y `equipo` respectivamente (el perfil sin dato no cuenta)

#### Scenario: Perfil con alcance sin su dato al consultar
<!-- test: tests/e2e_local.php:971 -->
- **CUANDO** un usuario Sanidad sin fuerza pide una consulta con alcance
- **ENTONCES** responde 403 "Su usuario no tiene asignado el alcance (fuerza, ESM o regional). Pida al administrador que lo registre en Usuarios." y no una lista vacía

---

### Requirement: Filtro de visitas por alcance
<!-- id: Alcance.sql -->
<!-- entidades: Visita, Punto, VisitaAuditor -->
<!-- impuesto_en: app/Support/Alcance.php Alcance::sql() -->
<!-- decision: 27 | 31 | Anexo 3 p.14 -->

Toda consulta de visitas DEBE filtrarse por el alcance: `todo` → todas; `opl` → todas menos las visitas a necesidad que aún no están en ejecución ni cerradas; `fuerza` → las de puntos de esa fuerza (incluidas las visitas a necesidad pendientes); `regional` → las de puntos de esa regional menos las visitas a necesidad pendientes; `esm` → las de ese punto menos las visitas a necesidad pendientes; `equipo` → aquellas en que el usuario es líder u otro auditor; `ninguno` → ninguna.

#### Scenario: Condiciones
<!-- test: tests/alcance_test.php:101 -->
<!-- test: tests/alcance_test.php:103 -->
<!-- test: tests/alcance_test.php:107 -->
<!-- test: tests/alcance_test.php:110 -->
<!-- test: tests/alcance_test.php:112 -->
- **CUANDO** se arma el filtro para `todo`, `ninguno`, `regional=3`, `esm=7` o `equipo=12`
- **ENTONCES** es verdadero, falso, `p.regional_id = 3 AND NOT (v.tipo = 'necesidad' AND v.estado NOT IN ('ejecucion','cerrada'))`, `v.punto_id = 7 AND` la misma exclusión, y existe fila en `visita_auditores` con ese usuario

#### Scenario: OPL nunca ve la visita a necesidad antes de hacerse
<!-- test: tests/alcance_test.php:173 -->
<!-- test: tests/alcance_test.php:174 -->
- **CUANDO** un OPL nacional o regional consulta visitas
- **ENTONCES** las visitas a necesidad programadas o reprogramadas no aparecen; en ejecución o cerradas sí

#### Scenario: Sanidad fuera de su fuerza
<!-- test: tests/e2e_local.php:851 -->
- **CUANDO** Sanidad EJC pide el detalle de una visita de la Armada
- **ENTONCES** responde 404

#### Scenario: Alias inseguros
<!-- test: tests/alcance_test.php:135 -->
<!-- test: tests/alcance_test.php:143 -->
- **CUANDO** el alias de tabla no es solo letras minúsculas (p. ej. `v; DROP TABLE visitas`, `p) OR (1=1`)
- **ENTONCES** se lanza un error de argumento inválido

---

### Requirement: Filtro de puntos por alcance
<!-- id: Alcance.sqlPunto -->
<!-- entidades: Punto -->
<!-- impuesto_en: app/Support/Alcance.php Alcance::sqlPunto(), Alcance::puedeVerPunto() -->

Las listas de puntos (opciones de filtro, cobertura, puntos que se pueden solicitar) DEBEN filtrarse: `todo` y `opl` → todos; `fuerza` → los de su fuerza; `regional` → los de su regional; `esm` → solo su punto; `equipo` y `ninguno` → ninguno.

#### Scenario: Condiciones sobre puntos
<!-- test: tests/alcance_test.php:179 -->
<!-- test: tests/alcance_test.php:183 -->
<!-- test: tests/alcance_test.php:184 -->
- **CUANDO** se filtra con `todo`, `esm=7` o `equipo=12`
- **ENTONCES** da todos, solo el punto 7 o ninguno

#### Scenario: ¿Puede ver el punto?
<!-- test: tests/alcance_test.php:150 -->
<!-- test: tests/alcance_test.php:151 -->
<!-- test: tests/alcance_test.php:152 -->
<!-- test: tests/alcance_test.php:158 -->
<!-- test: tests/alcance_test.php:161 -->
- **CUANDO** se pregunta por un punto EJC de la regional 3 con id 7 (los números pueden llegar como texto)
- **ENTONCES** lo ven `todo`, `fuerza=EJC`, `regional=3` y `esm=7`; no lo ven `fuerza=ARC`, `ninguno` ni `equipo`; un punto sin fuerza o sin regional no lo ve el alcance correspondiente

---

### Requirement: Combinaciones de perfiles no válidas
<!-- id: Alcance.combinacionInvalida -->
<!-- entidades: Usuario, Perfil -->
<!-- impuesto_en: app/Support/Alcance.php Alcance::combinacionInvalida(); ajax/usuarios.ajax.php alcanceDelPost(); controladores/maestros.controlador.php crearOVincularAuditor() -->
<!-- duda: 15.3 -->

Un usuario DEBE tener a lo sumo un perfil con alcance propio (DIGSA, Sanidad, ESM, OPL nacional, OPL regional), y los perfiles del OPL NO DEBEN combinarse con Auditor, Coordinador ni Administrador; la regla se aplica al guardar el usuario y al vincular un auditor por correo.

#### Scenario: Dos perfiles con alcance
<!-- test: tests/alcance_test.php:190 -->
<!-- test: tests/e2e_local.php:837 -->
- **CUANDO** el administrador guarda un usuario con DIGSA + Sanidad, o ESM + OPL nacional
- **ENTONCES** responde 422 "Un usuario tiene un solo perfil de consulta u OPL (…): cada uno ve un alcance distinto."

#### Scenario: OPL con perfil de la interventoría
<!-- test: tests/alcance_test.php:191 -->
<!-- test: tests/e2e_local.php:837 -->
- **CUANDO** se combina OPL regional con Auditor, u OPL nacional con Coordinador
- **ENTONCES** responde 422 "Los perfiles del OPL (entidad auditada) no se combinan con los de la interventoría."

#### Scenario: Combinaciones válidas
<!-- test: tests/alcance_test.php:192 -->
- **CUANDO** se combina Auditor + Coordinador, Administrador + DIGSA, o un solo perfil
- **ENTONCES** se acepta

#### Scenario: Usuarios anteriores a la regla
- **CUANDO** un usuario ya tenía dos perfiles con alcance antes de esta regla
- **ENTONCES** sigue funcionando; la regla se aplica cuando se edita

---

### Requirement: Acceso a pantallas por perfil
<!-- id: Rutas.PRIVADAS -->
<!-- entidades: Perfil -->
<!-- impuesto_en: app/Support/Rutas.php Rutas::PRIVADAS; app/Support/Auth.php Auth::tiene() -->
<!-- decision: tabla "Perfiles de acceso (9)" | 33 | 35 | 36 -->

Las pantallas DEBEN abrirse así (el Administrador entra a todas): inicio y salir → todos; agenda, propuesta de agenda, puntos y auditores → Coordinador; mis visitas → Auditor; prestadores con hallazgos → Auditor, Coordinador, DIGSA, Sanidad, ESM; calendario de consulta → DIGSA, Sanidad, ESM; solicitudes → Coordinador, DIGSA, Sanidad; respuesta a hallazgos → OPL nacional, OPL regional; dashboard, dashboard de riesgo y reportes → Coordinador, DIGSA, Sanidad, ESM; usuarios, exportación y logs → Administrador; visita (checklist) → Auditor y Coordinador.

#### Scenario: Auditor
<!-- test: tests/reglas_test.php:66 -->
<!-- test: tests/reglas_test.php:67 -->
<!-- test: tests/reglas_test.php:82 -->
<!-- test: tests/reglas_test.php:88 -->
- **CUANDO** un Auditor pide pantallas
- **ENTONCES** entra a mis visitas; no a agenda, maestros, dashboard, usuarios, calendario, solicitudes ni riesgo

#### Scenario: DIGSA y Sanidad
<!-- test: tests/reglas_test.php:74 -->
<!-- test: tests/reglas_test.php:86 -->
- **CUANDO** DIGSA o Sanidad piden pantallas
- **ENTONCES** entran a calendario, dashboard, reportes, prestadores con hallazgos, solicitudes y riesgo; no a agenda, maestros, mis visitas ni propuesta

#### Scenario: ESM
<!-- test: tests/reglas_test.php:90 -->
<!-- decision: 35 -->
- **CUANDO** el ESM pide pantallas
- **ENTONCES** entra a calendario, dashboard, reportes y prestadores con hallazgos; no a solicitudes

#### Scenario: OPL
<!-- test: tests/reglas_test.php:79 -->
- **CUANDO** OPL nacional u OPL regional piden pantallas
- **ENTONCES** solo entran a la respuesta a hallazgos

#### Scenario: Consulta retirado
<!-- test: tests/reglas_test.php:71 -->
<!-- decision: 20 -->
- **CUANDO** un usuario solo tiene el perfil Consulta
- **ENTONCES** no entra a dashboard, agenda ni calendario

#### Scenario: Solicitar visita sin el atajo del Administrador
<!-- duda: 9.12 -->
- **CUANDO** se decide quién solicita una visita a necesidad
- **ENTONCES** solo cuentan los perfiles propios DIGSA o Sanidad; el Administrador no solicita a nombre de la DIGSA

---

### Requirement: Listar usuarios
<!-- id: usuarios.ajax.listar -->
<!-- entidades: Usuario, Perfil, Fuerza, Punto, Regional -->
<!-- impuesto_en: ajax/usuarios.ajax.php accion=listar; modelos/usuarios.modelo.php ModeloUsuarios::listar(), ModeloUsuarios::perfilesDisponibles() -->

Solo el Administrador DEBE listar los usuarios con nombre, documento, teléfono, usuario, correo, estado, último ingreso, bloqueo, alcance y perfiles, junto con los perfiles activos y las opciones de alcance: fuerzas con sanidad (código y "DISAN EJC (Ejército…)"), ESM activos y regionales activas.

#### Scenario: Perfiles disponibles
<!-- test: tests/e2e_local.php:822 -->
- **CUANDO** el administrador abre Usuarios
- **ENTONCES** ve Administrador, Coordinador, Auditor, DIGSA, Sanidad, ESM, OPL nacional y OPL regional, sin Consulta

---

### Requirement: Crear o editar un usuario
<!-- id: usuarios.ajax.guardar -->
<!-- entidades: Usuario, Perfil, Correo, Bitacora -->
<!-- impuesto_en: ajax/usuarios.ajax.php accion=guardar, alcanceDelPost() -->
<!-- decision: 34 | 35 | 16 -->

El Administrador DEBE guardar usuarios con nombre (mínimo 3 caracteres), usuario `^[a-z0-9._-]{3,50}$` en minúscula, correo válido en minúscula, documento y teléfono opcionales, perfiles activos existentes y, según los perfiles, su alcance obligatorio (Sanidad → fuerza con sanidad; ESM → ESM activo; OPL regional → regional activa); el alcance que no aplica DEBE quedar vacío; perfiles y datos DEBEN guardarse en una transacción.

#### Scenario: Usuario nuevo
<!-- test: tests/e2e_local.php:188 -->
- **CUANDO** se crea un usuario válido
- **ENTONCES** responde 201, el usuario queda activo con una contraseña aleatoria que nadie conoce, se envía "Bienvenido a la Suite DIGSA" con el enlace de 60 minutos y se registra `usuario_creado`

#### Scenario: Correo que no sale al crear
- **CUANDO** el usuario se crea pero el correo falla
- **ENTONCES** responde 201 "Usuario creado, pero NO se pudo enviar el correo (…). Use «Restablecer contraseña» cuando el correo funcione." con `correo_ok=false`

#### Scenario: Usuario o correo repetido
<!-- test: tests/e2e_local.php:190 -->
- **CUANDO** el usuario o el correo ya son de otro usuario
- **ENTONCES** responde 422 "Ya existe un usuario con ese nombre de usuario." o "Ya existe un usuario con ese correo."

#### Scenario: Perfil inactivo o inexistente
<!-- test: tests/e2e_local.php:839 -->
- **CUANDO** se marca el perfil Consulta (id 4, inactivo) u otro id que no está activo
- **ENTONCES** responde 422 "Hay un perfil no válido."

#### Scenario: Alcance faltante
<!-- test: tests/e2e_local.php:831 -->
<!-- test: tests/e2e_local.php:832 -->
- **CUANDO** se marca Sanidad sin fuerza, ESM sin ESM activo u OPL regional sin regional activa
- **ENTONCES** responde 422 "El perfil Sanidad necesita la fuerza (DISAN Ejército, DISAN Armada o JEFSA).", "El perfil ESM necesita el establecimiento (ESM activo)." o "El perfil OPL regional necesita la regional (regístrela en Puntos y auditores › Contrato)."

#### Scenario: Alcance que se inactivó después
- **CUANDO** se edita un usuario cuyo ESM o regional ya están inactivos y se envía el mismo valor
- **ENTONCES** se conserva sin error

#### Scenario: Quitarse el perfil Administrador
- **CUANDO** el administrador se edita a sí mismo sin el perfil Administrador
- **ENTONCES** responde 422 "No puede quitarse a sí mismo el perfil Administrador."

#### Scenario: Usuarios de consulta con su alcance
<!-- test: tests/e2e_local.php:843 -->
- **CUANDO** se crean DIGSA, Sanidad (EJC) y ESM con su alcance
- **ENTONCES** se crean y pueden ingresar tras definir la contraseña

#### Scenario: Documento obligatorio para auditores
<!-- decision: 26 -->
<!-- duda: decisión 26 · diferencia con el código: en Usuarios el documento es opcional aunque se marque el perfil Auditor, y no se registra su rol técnico (queda "otro"); solo Puntos y auditores lo exige -->
- **CUANDO** el administrador guarda un usuario con el perfil Auditor
- **ENTONCES** el documento DEBE ser obligatorio (decisión 26); hoy el PHP solo lo exige en Puntos y auditores
<!-- incierto: el PHP acepta un usuario sin ningún perfil (queda con alcance ninguno y solo ve el inicio); no hay prueba ni decisión al respecto -->

---

### Requirement: Activar o inactivar un usuario
<!-- id: usuarios.ajax.estado -->
<!-- entidades: Usuario, Bitacora -->
<!-- impuesto_en: ajax/usuarios.ajax.php accion=estado -->

El Administrador DEBE poder activar o inactivar usuarios (nunca a sí mismo); un usuario inactivado pierde su sesión en la siguiente petición.

#### Scenario: Inactivar a otro
<!-- test: tests/e2e_local.php:528 -->
- **CUANDO** el administrador inactiva a un coordinador con sesión abierta
- **ENTONCES** responde "Usuario inactivado.", se registra `usuario_estado` y el coordinador recibe 401 en su siguiente petición

#### Scenario: Inactivarse a sí mismo
- **CUANDO** el administrador intenta inactivar su propio usuario
- **ENTONCES** responde 422 "No puede inactivar su propio usuario."

---

### Requirement: Restablecer la contraseña de un usuario
<!-- id: usuarios.ajax.restablecer -->
<!-- entidades: Usuario, Correo, Bitacora -->
<!-- impuesto_en: ajax/usuarios.ajax.php accion=restablecer -->

El Administrador DEBE poder exigir el cambio de contraseña: se quitan los intentos y el bloqueo, se marca `debe_cambiar_clave` = 1 y se envía un enlace nuevo de 60 minutos.

#### Scenario: Correo enviado
- **CUANDO** el administrador pulsa «Restablecer contraseña» y el correo sale
- **ENTONCES** responde 200 "Se envió a <correo> el enlace para definir una contraseña nueva." y se registra `usuario_restablecer`

#### Scenario: Correo fallido
- **CUANDO** el correo no sale
- **ENTONCES** responde 502 "No se pudo enviar el correo: <motivo>"

---

### Invariant: Nueve perfiles de acceso
<!-- entidades: Perfil -->
<!-- impuesto_en: Database/Data/001_perfiles.sql -->
<!-- verificado_por: tests/e2e_local.php:822 -->

Los perfiles DEBEN ser, con id fijo: 1 Administrador, 2 Coordinador, 3 Auditor, 4 Consulta (inactivo, reemplazado por DIGSA, Sanidad y ESM), 5 DIGSA, 6 Sanidad (DISAN EJC, DISAN ARC o JEFSA FAC; la fuerza va en el usuario), 7 ESM, 8 OPL nacional y 9 OPL regional; el script se puede reejecutar y no crea usuarios.
<!-- duda: 9.2 (los usuarios que eran Consulta pasaron a DIGSA; revisar uno por uno) -->

---

### Invariant: Un perfil por usuario una sola vez
<!-- entidades: Usuario, Perfil -->
<!-- impuesto_en: Database/Structure/001_usuarios.sql usuarios_permisos uq_permisos, fk_permisos_usuario ON DELETE CASCADE, fk_permisos_perfil -->

La asignación usuario–perfil DEBE ser única y referir a un usuario y un perfil existentes; borrar el usuario borra sus asignaciones.

---

### Invariant: El alcance refiere a datos existentes
<!-- entidades: Usuario, Fuerza, Punto, Regional -->
<!-- impuesto_en: Database/Structure/004_maestros.sql fk_usuarios_alcance_fuerza, fk_usuarios_alcance_punto, fk_usuarios_alcance_regional -->

`alcance_fuerza`, `alcance_punto` y `alcance_regional` DEBEN referir a una fuerza, un punto y una regional existentes, o estar vacíos.

---

### Invariant: Constantes de perfiles
<!-- entidades: Perfil -->
<!-- impuesto_en: app/Support/Alcance.php PERFILES_CONSULTA, PERFILES_SOLICITAN, PERFILES_CON_ALCANCE -->
<!-- verificado_por: tests/alcance_test.php:33 -->

Los perfiles de consulta DEBEN ser DIGSA, Sanidad y ESM; solicitan visitas solo DIGSA y Sanidad (el ESM no); tienen alcance propio DIGSA, Sanidad, ESM, OPL nacional y OPL regional.

<!-- duda: 9.10 · superada: OPL nacional y regional ya tienen la pantalla de respuesta a hallazgos desde la fase 4 -->
<!-- pendiente: vistas/modulos/usuarios.php, vistas/js/usuarios.js, ajax/consulta.ajax.php (uso del alcance en el calendario), modelos/visitas.modelo.php (uso de Alcance::sql) -->
