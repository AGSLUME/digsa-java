# Especificación: maestros-carga-masiva

> Extraída por spec-miner el 2026-10-20 del PHP de referencia (develop, commit db2e330).
> Fuentes: app/Support/ImportadorMaestros.php, ajax/maestros.ajax.php, controladores/maestros.controlador.php, modelos/puntos.modelo.php, modelos/auditores.modelo.php, modelos/catalogos.modelo.php, modelos/usuarios.modelo.php, app/Support/Reparto.php (ROLES), Database/Structure/004_maestros.sql, Database/Structure/002_catalogos.sql (par_regionales, par_fuerzas), tests/importador_maestros_test.php, tests/e2e_local.php, docs/decisiones_2026-10-15.md (2a, 9, 16, 24, 26, agentes 3), docs/dudas_pendientes.md (8.9, 9.1, 9.7).

---

### Requirement: Revisión del archivo antes de las filas
<!-- id: ImportadorMaestros.revisarArchivo -->
<!-- entidades: CargaMasiva -->
<!-- impuesto_en: app/Support/ImportadorMaestros.php ImportadorMaestros::revisarArchivo(), ImportadorMaestros::COLUMNAS, ImportadorMaestros::OBLIGATORIAS; controladores/maestros.controlador.php importarPuntos(), importarAuditores() -->
<!-- decision: decisión 7 del mockup (archivo vacío, columnas) -->

Una carga masiva (filas ya convertidas desde Excel o JSON, máximo 5000) DEBE rechazarse completa si no tiene filas con datos o le faltan columnas obligatorias (puntos: nombre y unidad; auditores: nombre y correo), y DEBE avisar las columnas que no reconoce; los encabezados se comparan sin mayúsculas, tildes ni espacios y admiten sinónimos.

#### Scenario: Archivo vacío
<!-- test: tests/importador_maestros_test.php:79 -->
<!-- test: tests/e2e_local.php:221 -->
- **CUANDO** el archivo no tiene filas, o solo filas con celdas vacías o con espacios
- **ENTONCES** responde 422 "El archivo está vacío: no tiene filas con datos."

#### Scenario: Falta una columna obligatoria
<!-- test: tests/importador_maestros_test.php:81 -->
<!-- test: tests/importador_maestros_test.php:85 -->
- **CUANDO** un archivo de puntos no trae la columna unidad, o uno de auditores no trae correo
- **ENTONCES** se rechaza con "Al archivo le faltan las columnas obligatorias: <columnas>. Use la plantilla."

#### Scenario: Columna desconocida
<!-- test: tests/importador_maestros_test.php:83 -->
- **CUANDO** el archivo trae una columna "Teléfono" en puntos
- **ENTONCES** se acepta y la respuesta lista `desconocidas = ["Teléfono"]`

#### Scenario: Más de 5000 filas o filas ilegibles
- **CUANDO** el archivo trae más de 5000 filas, o `filas` no es una lista JSON
- **ENTONCES** responde 422 "El archivo supera 5000 filas. Divídalo en varios." o "El archivo no tiene filas legibles."

#### Scenario: Sinónimos de columnas
- **CUANDO** el encabezado es, por ejemplo, "Nombre del punto", "Tipo de punto", "Código municipio", "Ciudad", "Email", "Requiere QF", "Se llega desde", "Cédula", "Celular", "Profesión" o "Ciudad base"
- **ENTONCES** se lee como nombre, unidad, código de municipio (también "divipola"), municipio, correos, exige_qf, llega_desde, documento, teléfono, rol y municipio_base

---

### Requirement: Vista previa y confirmación de la carga
<!-- id: ControladorMaestros.importarPuntos -->
<!-- entidades: CargaMasiva, Punto, Auditor, Bitacora -->
<!-- impuesto_en: controladores/maestros.controlador.php ControladorMaestros::importarPuntos(), ControladorMaestros::importarAuditores(); modelos/puntos.modelo.php ModeloPuntos::aplicarCarga() -->

Sin `confirmar=1` la carga DEBE devolver solo la vista previa (nuevos, a actualizar, errores por fila con número de fila y motivo, columnas desconocidas, una muestra de 5 y, en puntos, cuántos quedan sin correo); con `confirmar=1` DEBE aplicar solo las filas válidas en una transacción y saltar las filas con error.

#### Scenario: Vista previa
<!-- test: tests/e2e_local.php:219 -->
- **CUANDO** se envían 3 puntos válidos y 1 con unidad "XYZ" sin confirmar
- **ENTONCES** responde 200 "Vista previa" con `nuevos=3` y un error, sin escribir nada

#### Scenario: Confirmación
<!-- test: tests/e2e_local.php:223 -->
- **CUANDO** se confirma la misma carga
- **ENTONCES** se crean los 3 puntos, se responde "Se cargaron 3 puntos nuevos y se actualizaron 0." y se registra `puntos_carga` con nuevos, actualizados y errores

#### Scenario: Ninguna fila válida
- **CUANDO** se confirma una carga sin filas válidas
- **ENTONCES** responde 422 "No hay filas válidas para cargar."

#### Scenario: Choque de nombres en la base
- **CUANDO** al aplicar dos puntos quedarían con el mismo nombre y unidad (la base no distingue mayúsculas ni tildes)
- **ENTONCES** no se aplica nada y responde 422 "La carga no se aplicó: dos puntos quedarían con el mismo nombre y unidad (…)"

#### Scenario: Celdas vacías al actualizar
- **CUANDO** una fila actualiza un punto existente y trae celdas vacías
- **ENTONCES** esos campos no se borran (solo se escriben los valores no vacíos)

---

### Requirement: Validación de cada punto
<!-- id: ImportadorMaestros.puntos -->
<!-- entidades: Punto, Municipio, Regional, Auditor -->
<!-- impuesto_en: app/Support/ImportadorMaestros.php ImportadorMaestros::puntos(), unidad(), fuerza(), municipio() -->
<!-- decision: decisiones 5 y 6 del mockup (exige QF, modalidad) | Anexo 3 p.9 -->

Cada fila de puntos DEBE tener nombre, unidad reconocible (ESM, FA o NC; también "farmacia…", "…central…", "establecimiento"/"sanidad"), fuerza reconocible (EJC, ARC, FAC, NA, o "ejército", "armada", "aérea"/"aeroespacial", "no aplica"; vacía en NC = NA), modalidad válida si es ESM, municipio existente si se da, correos válidos, regional activa por nombre, municipio "se llega desde" existente y auditor asignado activo por correo; la llave del punto DEBE ser nombre normalizado + unidad.

#### Scenario: Casos del mockup v6
<!-- test: tests/importador_maestros_test.php:36 -->
<!-- test: tests/importador_maestros_test.php:37 -->
<!-- test: tests/importador_maestros_test.php:38 -->
- **CUANDO** se cargan 8 filas: 3 nuevas válidas, 1 con el nombre y unidad de un punto existente, unidad "XYZ" con correo inválido, un repetido en el archivo, código de municipio 99999 y una fila vacía
- **ENTONCES** quedan 3 nuevos, 1 a actualizar (por nombre y unidad), 3 errores y la fila vacía se ignora

#### Scenario: Código de municipio y nombres sin tildes
<!-- test: tests/importador_maestros_test.php:40 -->
<!-- test: tests/importador_maestros_test.php:41 -->
<!-- test: tests/importador_maestros_test.php:44 -->
- **CUANDO** el código viene como `5001`, la modalidad como "dispensacion" o el municipio como "Bolivar / Cartagena de Indias"
- **ENTONCES** se guarda `05001`, "Dispensación" y `13001`

#### Scenario: Municipio no encontrado
<!-- test: tests/importador_maestros_test.php:48 -->
- **CUANDO** el código no existe, o se da departamento sin municipio, o no se encuentra el par departamento–municipio
- **ENTONCES** la fila tiene error "código de municipio "<código>" no existe" o "departamento o municipio no encontrado"

#### Scenario: Modalidad obligatoria en ESM
<!-- test: tests/importador_maestros_test.php:101 -->
<!-- test: tests/e2e_local.php:208 -->
- **CUANDO** un ESM no trae modalidad, y una farmacia alterna tampoco
- **ENTONCES** el ESM tiene error "falta la modalidad del ESM (…)" y la farmacia pasa

#### Scenario: NIT solo en farmacia alterna
<!-- test: tests/importador_maestros_test.php:97 -->
- **CUANDO** un ESM trae NIT
- **ENTONCES** el NIT se ignora; la fuerza "Armada Nacional" se lee como ARC

#### Scenario: Nivel central sin fuerza
<!-- test: tests/importador_maestros_test.php:45 -->
- **CUANDO** un punto NC no trae fuerza
- **ENTONCES** su fuerza es NA

#### Scenario: Exige químico farmacéutico
<!-- test: tests/importador_maestros_test.php:105 -->
- **CUANDO** la columna exige_qf trae "Sí", "No" o viene vacía
- **ENTONCES** queda 1, 0 o sin dato (no borra lo marcado; un punto nuevo sin dato queda en 0); "Sí" acepta si, s, x, 1, true, verdadero, yes

#### Scenario: Correos de la celda
<!-- test: tests/importador_maestros_test.php:42 -->
<!-- test: tests/importador_maestros_test.php:91 -->
- **CUANDO** los correos vienen separados por coma, punto y coma o espacio, con repetidos que solo difieren en mayúsculas
- **ENTONCES** se guardan sin repetir, unidos por ", "

#### Scenario: Fila de ejemplo de la plantilla
<!-- test: tests/importador_maestros_test.php:89 -->
- **CUANDO** el nombre contiene "borrar esta fila"
- **ENTONCES** la fila tiene error "es una fila de ejemplo de la plantilla"

#### Scenario: Largo de los campos
<!-- test: tests/importador_maestros_test.php:93 -->
<!-- test: tests/importador_maestros_test.php:95 -->
- **CUANDO** el nombre tiene 201 caracteres o la dirección 201
- **ENTONCES** es error de esa fila (no tumba la carga); 200 caracteres con tildes pasan; topes: nombre 200, NIT 20, dirección 200, contacto 150, correos 500

#### Scenario: Regional, se llega desde y auditor asignado
<!-- test: tests/importador_maestros_test.php:125 -->
<!-- test: tests/importador_maestros_test.php:126 -->
<!-- test: tests/importador_maestros_test.php:127 -->
<!-- test: tests/importador_maestros_test.php:128 -->
<!-- test: tests/importador_maestros_test.php:130 -->
- **CUANDO** la regional viene por nombre sin mayúsculas, llega_desde como `5001` y el auditor asignado por correo en mayúsculas; o alguno no existe; o no vienen
- **ENTONCES** se guardan el id de la regional, `05001` y el id del usuario; si no existen, error de la fila ("regional … no existe (regístrela en Puntos y auditores › Contrato)", "… no es un auditor activo (use su correo)"); si no vienen, quedan sin dato

---

### Requirement: Crear o editar un punto a mano
<!-- id: ControladorMaestros.guardarPunto -->
<!-- entidades: Punto, Regional, Auditor, Bitacora -->
<!-- impuesto_en: controladores/maestros.controlador.php ControladorMaestros::guardarPunto() -->

El formulario de puntos DEBE aplicar las mismas reglas de la carga masiva (con la casilla exige QF siempre explícita), recibir la regional y el auditor asignado por id y permitir vaciar campos al editar; una regional o un auditor que se inactivaron DEBEN conservarse si el punto ya los tenía.

#### Scenario: Punto que exige QF
<!-- test: tests/e2e_local.php:210 -->
- **CUANDO** se crea el "Hospital Militar Central" ESM de dispensación con la casilla marcada
- **ENTONCES** responde "Punto creado." y se registra `punto_creado`

#### Scenario: Punto con datos de la fase 2
<!-- test: tests/e2e_local.php:813 -->
- **CUANDO** se guarda un ESM con regional, "se llega desde" 05001 y auditor asignado
- **ENTONCES** quedan guardados

#### Scenario: Auditor asignado que no es auditor activo
<!-- test: tests/e2e_local.php:814 -->
- **CUANDO** el auditor asignado no es un auditor activo
- **ENTONCES** responde 422 "El auditor asignado no es un auditor activo."

#### Scenario: Regional inactiva
<!-- test: tests/e2e_local.php:942 -->
<!-- test: tests/e2e_local.php:943 -->
- **CUANDO** se edita un punto que ya tenía una regional ahora inactiva, o se asigna esa regional a otro punto
- **ENTONCES** el primero se guarda y la conserva; el segundo responde 422 "La regional seleccionada no existe o está inactiva."

#### Scenario: Nombre y unidad de otro punto
- **CUANDO** el nombre y la unidad coinciden con otro punto
- **ENTONCES** responde 422 "Ya existe otro punto con ese nombre y unidad."

---

### Requirement: Activar o inactivar un punto
<!-- id: maestros.ajax.punto_estado -->
<!-- entidades: Punto, Bitacora -->
<!-- impuesto_en: ajax/maestros.ajax.php accion=punto_estado -->

El coordinador DEBE poder activar o inactivar un punto; uno inactivo ya no se ofrece al programar visitas.

#### Scenario: Inactivar
- **CUANDO** el coordinador inactiva un punto existente
- **ENTONCES** responde "Punto inactivado: ya no se ofrece al programar visitas." y se registra `punto_estado`; un id inexistente da 404

---

### Requirement: Validación de cada auditor
<!-- id: ImportadorMaestros.auditores -->
<!-- entidades: Auditor, Usuario -->
<!-- impuesto_en: app/Support/ImportadorMaestros.php ImportadorMaestros::auditores(), ImportadorMaestros::rol() -->
<!-- decision: 2a | 26 -->
<!-- duda: 8.9 -->

Cada fila de auditores DEBE tener nombre (mínimo 3 caracteres), correo válido (se guarda en minúscula y es la llave), documento `^[A-Za-z0-9.\-]{3,30}$` obligatorio y rol técnico reconocible (químico farmacéutico, regente, auditor en salud, ingeniero de sistemas u otro); sin columna rol, la columna QF de archivos anteriores decide (Sí → químico farmacéutico, si no → otro); el municipio base, si viene, DEBE existir.

#### Scenario: Roles reconocidos
<!-- test: tests/importador_maestros_test.php:71 -->
- **CUANDO** el rol viene como "Regente de farmacia", "Ingeniero de sistemas", "Auditora en salud" o "químico farmacéutico"
- **ENTONCES** se guarda regente, ingeniero_sistemas, auditor_salud y quimico_farmaceutico

#### Scenario: Rol no reconocido
<!-- test: tests/importador_maestros_test.php:72 -->
- **CUANDO** el rol es "médico", "Q.F." o "Ing. de sistemas"
- **ENTONCES** la fila tiene error "rol … no válido (químico farmacéutico, regente, auditor en salud, ingeniero de sistemas u otro)"; "Ingeniero" a secas cuenta como ingeniero de sistemas

#### Scenario: Documento
<!-- test: tests/importador_maestros_test.php:73 -->
<!-- test: tests/importador_maestros_test.php:74 -->
- **CUANDO** falta el documento o trae espacios ("12 34")
- **ENTONCES** la fila tiene error ("falta el documento (va en la notificación de la visita para el ingreso a la unidad)" o "no válido")

#### Scenario: Archivo anterior con columna QF
<!-- test: tests/importador_maestros_test.php:57 -->
<!-- test: tests/importador_maestros_test.php:107 -->
- **CUANDO** no hay columna rol y QF vale "Sí", "X", "verdadero" o "quizás"
- **ENTONCES** los tres primeros son químico farmacéutico y el último otro

#### Scenario: Existente, sin correo o repetido
<!-- test: tests/importador_maestros_test.php:58 -->
<!-- test: tests/importador_maestros_test.php:59 -->
- **CUANDO** el correo ya es de un usuario, falta el correo, o se repite en el archivo
- **ENTONCES** el primero va a actualizar con el id de ese usuario; los otros dos son error de la fila

#### Scenario: Municipio base
<!-- test: tests/importador_maestros_test.php:136 -->
<!-- test: tests/importador_maestros_test.php:137 -->
<!-- test: tests/importador_maestros_test.php:138 -->
- **CUANDO** el municipio base es 11001, 12345 o no viene
- **ENTONCES** se guarda 11001, la fila (número 3 con base 2) tiene error, o queda sin dato

---

### Requirement: Crear o vincular un auditor
<!-- id: ControladorMaestros.crearOVincularAuditor -->
<!-- entidades: Auditor, Usuario, Perfil, Correo, Bitacora -->
<!-- impuesto_en: controladores/maestros.controlador.php ControladorMaestros::crearOVincularAuditor(), guardarAuditor(), importarAuditores() -->
<!-- decision: 26 -->
<!-- duda: 8.9 -->

Un auditor es un usuario con el perfil Auditor: si el correo no existe, DEBE crearse el usuario (usuario propuesto desde el correo, contraseña aleatoria, activo) con el perfil Auditor, su rol y su municipio base; si existe, DEBE agregarse el perfil Auditor sin tocar nombre ni correo, completar el documento solo si no lo tenía, fijar el rol (o solo si no tenía, cuando el archivo no trae rol ni QF) y no borrar el municipio base con una celda vacía; los correos de bienvenida DEBEN salir después de confirmar la transacción.

#### Scenario: Auditor nuevo a mano
<!-- test: tests/e2e_local.php:232 -->
- **CUANDO** el coordinador crea una auditora QF con documento
- **ENTONCES** se crea el usuario con el perfil y el rol, se envía el enlace "Bienvenido a la Suite DIGSA" y se registra `auditor_creado`

#### Scenario: Sin documento o sin rol
<!-- test: tests/e2e_local.php:227 -->
<!-- test: tests/e2e_local.php:229 -->
- **CUANDO** falta el documento o el rol técnico
- **ENTONCES** responde 422 con un mensaje que menciona el documento, o "Seleccione el rol técnico del auditor."

#### Scenario: Correo de un usuario existente
<!-- test: tests/e2e_local.php:236 -->
- **CUANDO** el correo ya es de un coordinador
- **ENTONCES** responde 200 "Ese correo ya tenía usuario: se le agregó el perfil Auditor (sus datos personales no se modificaron)." y no se envía correo

#### Scenario: Usuario del OPL
- **CUANDO** el correo es de un usuario OPL nacional o regional
- **ENTONCES** no se carga nada y se responde "No se cargó nada: El correo … ya es de un usuario que no puede tener el perfil Auditor. Los perfiles del OPL…"

#### Scenario: Carga masiva con enlaces
<!-- test: tests/e2e_local.php:234 -->
- **CUANDO** se confirma una carga de auditores con `enviar_enlaces=1` (por defecto)
- **ENTONCES** se crean en una transacción, se envía el enlace a cada auditor nuevo, el mensaje dice cuántos enlaces salieron y a quiénes no, y se registra `auditores_carga`; con `enviar_enlaces=0` no se envían

#### Scenario: Editar un auditor
<!-- test: tests/e2e_local.php:816 -->
<!-- test: tests/e2e_local.php:818 -->
- **CUANDO** el coordinador edita rol, documento y municipio base de un auditor existente
- **ENTONCES** se guardan (el municipio base se puede vaciar); un municipio base inexistente da 422 "El municipio base no existe."; nombre y correo solo se cambian en Usuarios

---

### Requirement: Contrato, destinatarios y regionales
<!-- id: ControladorMaestros.guardarConfiguracion -->
<!-- entidades: Opl, Fuerza, Parametro, Regional, Bitacora -->
<!-- impuesto_en: controladores/maestros.controlador.php ControladorMaestros::guardarConfiguracion(); modelos/catalogos.modelo.php guardarOpl(), guardarCorreosFuerza(), guardarRegionales(), correosDigsa() -->
<!-- decision: 9 | 24 | 16 -->
<!-- duda: 9.1 -->
<!-- duda: 9.7 -->

En Puntos y auditores › Contrato el coordinador DEBE guardar en una sola transacción: el OPL único del contrato (razón social hasta 200, NIT hasta 20, correos válidos hasta 500), los correos de cada DISAN o JEFSA (solo fuerzas con sanidad), los correos de la DIGSA, los parámetros y las regionales (una por línea, sin repetidas, máximo 30); un campo que no viene en el formulario NO DEBE borrarse.

#### Scenario: OPL sin razón social
<!-- test: tests/e2e_local.php:201 -->
- **CUANDO** se envían correos del OPL sin razón social
- **ENTONCES** responde 422 "Escriba la razón social del OPL: sin ella no se guardan su NIT ni sus correos."

#### Scenario: Configuración completa
<!-- test: tests/e2e_local.php:205 -->
- **CUANDO** se guardan OPL, correos de DISAN EJC, DISAN ARC y JEFSA FAC, parámetros e inicio del contrato
- **ENTONCES** responde "Configuración guardada." y se registra `maestros_config`

#### Scenario: Correo de la DIGSA
<!-- test: tests/e2e_local.php:803 -->
<!-- test: tests/e2e_local.php:807 -->
- **CUANDO** el correo de la DIGSA no es válido, o es válido junto con regionales repetidas en la lista
- **ENTONCES** el primero responde 422; el segundo guarda el correo y dos regionales activas (sin la repetida)

#### Scenario: Quitar una regional de la lista
<!-- test: tests/e2e_local.php:942 -->
- **CUANDO** se guarda la lista sin una regional que ya existía
- **ENTONCES** esa regional queda inactiva (no se borra) y los puntos y usuarios ligados la conservan

#### Scenario: Un solo OPL
- **CUANDO** se guarda el OPL y ya hay uno activo
- **ENTONCES** se actualiza ese mismo registro; si no hay, se crea

---

### Requirement: Datos del maestro para la pantalla
<!-- id: maestros.ajax.datos -->
<!-- entidades: Punto, Auditor, Opl, Parametro -->
<!-- impuesto_en: ajax/maestros.ajax.php accion=datos, accion=municipios -->

Solo el Coordinador (y el Administrador) DEBE obtener unidades, fuerzas con sus correos, modalidades, departamentos, parámetros, OPL, puntos (con municipio, departamento, regional, se llega desde y auditor asignado), auditores (con rol, por defecto "otro", y municipio base), regionales y municipios con su departamento; los municipios de un departamento se piden con su código de 2 dígitos.

#### Scenario: Coordinadora carga los maestros
<!-- test: tests/e2e_local.php:199 -->
- **CUANDO** la coordinadora pide los datos
- **ENTONCES** responde 200 con 33 departamentos

---

### Invariant: Un punto por nombre y unidad
<!-- entidades: Punto -->
<!-- impuesto_en: Database/Structure/004_maestros.sql uq_puntos_nombre_unidad -->

No DEBEN existir dos puntos con el mismo nombre (sin distinguir mayúsculas ni tildes) y la misma unidad.

---

### Invariant: Referencias del punto
<!-- entidades: Punto, Unidad, Fuerza, Modalidad, Municipio, Regional, Usuario -->
<!-- impuesto_en: Database/Structure/004_maestros.sql fk_puntos_unidad, fk_puntos_fuerza, fk_puntos_modalidad, fk_puntos_municipio, fk_puntos_regional, fk_puntos_llega_desde, fk_puntos_auditor_asignado -->

Unidad y fuerza DEBEN existir en sus catálogos; modalidad, municipio, regional, "se llega desde" y auditor asignado DEBEN existir o estar vacíos; `exige_qf` es 0 por defecto y el punto nace activo.
<!-- incierto: auditor_asignado apunta a usuarios y no a auditores; la regla de que sea un auditor activo la impone solo la aplicación (prioridad 2 de la fase 2 no corregida) -->

---

### Invariant: Lo propio del auditor
<!-- entidades: Auditor, Usuario -->
<!-- impuesto_en: Database/Structure/004_maestros.sql auditores (usuario_id PK, rol enum, municipio_base) -->

Cada auditor DEBE tener a lo sumo una fila propia, ligada a un usuario existente (se borra con él), con rol en {quimico_farmaceutico, regente, auditor_salud, ingeniero_sistemas, otro} ("otro" por defecto) y municipio base existente o vacío; el documento del auditor vive en el usuario y no se exige único.

---

### Invariant: Los cambios del punto se reflejan en sus visitas
<!-- entidades: Punto, Visita -->
<!-- impuesto_en: decisión agentes 3 (las visitas leen la fuerza y la unidad del punto; no se guarda una foto) -->
<!-- decision: agentes 3 -->

Si un punto cambia de fuerza o unidad, sus visitas pasadas DEBEN mostrarse con los datos actuales del punto.
<!-- incierto: no se leyó la consulta de visitas que lo implementa (modelos/visitas.modelo.php) -->

<!-- pendiente: vistas/modulos/maestros/*, vistas/js/maestros.js (lectura del Excel en el navegador y plantilla), modelos/visitas.modelo.php (auditoresActivos, uso de los datos del punto), modelos/modelo.php -->
