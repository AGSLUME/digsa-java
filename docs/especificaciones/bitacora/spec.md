# Especificación: bitacora

> Extraída por spec-miner el 2026-10-20 del PHP de referencia (develop, commit db2e330).
> Fuentes: app/Support/Bitacora.php, modelos/log_transacciones.modelo.php, modelos/logs.modelo.php, ajax/logs.ajax.php, app/Support/Auth.php (requerirAjax), controladores/plantilla.controlador.php, vistas/js/logs.js, app/Support/Rutas.php, Database/Structure/001_usuarios.sql (log_transacciones), tests/bitacora_test.php, tests/e2e_local.php, docs/decisiones_2026-10-15.md (decisión 5), docs/dudas_pendientes.md (7.2, 7.3, 7.5, 8.12).

---

### Requirement: Registro de cada petición ajax en una sola fila
<!-- id: Bitacora.iniciarPeticion -->
<!-- entidades: Bitacora -->
<!-- impuesto_en: app/Support/Bitacora.php Bitacora::iniciarPeticion(), Bitacora::cerrarPeticion(); app/Support/Auth.php Auth::requerirAjax() -->
<!-- decision: 5 | Anexo 5, prueba 15 -->

Cada petición a un endpoint `ajax/` con sesión válida DEBE abrir un contexto de bitácora (módulo = nombre del archivo `ajax/<modulo>.ajax.php`, método, acción y datos) y, al terminar, escribir a lo sumo una fila con el código HTTP final; los eventos que anotan los controladores durante la petición DEBEN sumarse a esa misma fila.

#### Scenario: Consulta de un endpoint
<!-- test: tests/e2e_local.php:591 -->
- **CUANDO** el coordinador hace `GET ajax/agenda.ajax.php?accion=formulario` con éxito
- **ENTONCES** queda una fila `operacion=consulta`, `tipo=agenda.formulario`, `modulo=agenda`

#### Scenario: Escritura con evento del controlador
- **CUANDO** durante un POST el controlador registra un evento (p. ej. `usuario_creado`)
- **ENTONCES** la fila usa ese evento como `tipo`, la operación de la acción y agrega la descripción del evento al detalle; si hay varios eventos se unen con "; "

#### Scenario: Error fatal o transacción abierta al terminar
<!-- test: tests/bitacora_test.php:216 -->
- **CUANDO** la petición termina con un error fatal o con una transacción de base de datos sin confirmar
- **ENTONCES** el código se toma como 500 (error fatal), la transacción se revierte y la fila se escribe igual, con " · transacción sin confirmar" si quedó abierta

---

### Requirement: Qué peticiones se registran
<!-- id: Bitacora.fila -->
<!-- entidades: Bitacora -->
<!-- impuesto_en: app/Support/Bitacora.php Bitacora::fila() -->
<!-- duda: 7.2 -->

DEBEN registrarse: lo exitoso (código < 300), el acceso denegado (403) y el rechazo de CSRF (419) como `acceso_denegado`, las escrituras que terminaron en 5xx y todo lo que un controlador anotó (con el código si no fue exitoso); NO DEBEN registrarse las validaciones rechazadas (422), los 404, las consultas con 5xx, la sesión vencida (401), el 413 ni el latido de la sesión.

#### Scenario: Petición exitosa
<!-- test: tests/bitacora_test.php:203 -->
- **CUANDO** `POST agenda.editar` con `id=4` termina en 200 sin anotaciones
- **ENTONCES** la fila es `tipo=agenda.editar`, `operacion=actualizacion`, `descripcion=agenda.editar · id=4`

#### Scenario: Validación rechazada o consulta fallida
<!-- test: tests/bitacora_test.php:204 -->
<!-- test: tests/bitacora_test.php:205 -->
<!-- test: tests/bitacora_test.php:206 -->
- **CUANDO** un POST sin anotaciones termina en 422, o un GET termina en 404 o 500
- **ENTONCES** no se escribe fila

#### Scenario: Escritura con error del servidor
<!-- test: tests/bitacora_test.php:208 -->
- **CUANDO** `POST agenda.reprogramar` termina en 500
- **ENTONCES** se escribe la fila con la operación de la acción y el detalle termina en " · HTTP 500"

#### Scenario: Acceso denegado y CSRF
<!-- test: tests/bitacora_test.php:210 -->
<!-- test: tests/bitacora_test.php:212 -->
<!-- test: tests/e2e_local.php:596 -->
- **CUANDO** la petición termina en 403 o 419
- **ENTONCES** la fila es `tipo=acceso_denegado`, `operacion=consulta`, con " · HTTP 403" o " · HTTP 419"

#### Scenario: Anotación con código no exitoso
<!-- test: tests/bitacora_test.php:214 -->
- **CUANDO** un controlador anotó `usuario_creado` y la respuesta fue 422
- **ENTONCES** la fila se escribe con ese tipo, la anotación y " · HTTP 422"

#### Scenario: Latido de la sesión
<!-- test: tests/bitacora_test.php:217 -->
<!-- test: tests/e2e_local.php:554 -->
- **CUANDO** llega `GET sesion.mantener`
- **ENTONCES** no se escribe fila (`SIN_REGISTRO = ['sesion.mantener']`)

---

### Requirement: Tipo de operación de cada acción
<!-- id: Bitacora.operacion -->
<!-- entidades: Bitacora -->
<!-- impuesto_en: app/Support/Bitacora.php Bitacora::ESCRITURAS, Bitacora::LECTURAS, Bitacora::operacion() -->
<!-- decision: 5 -->

Todo GET DEBE ser `consulta`; cada POST DEBE tomar la operación de la tabla `ESCRITURAS` por módulo y acción, con `actualizacion` para lo no listado; los marcadores `segun_id` (id > 0 → actualización, si no inserción) y `segun_confirmar` (`confirmar=1` → inserción, si no consulta) DEBEN resolverse con los datos.

#### Scenario: Guardar usuario nuevo o existente
<!-- test: tests/bitacora_test.php:75 -->
<!-- test: tests/bitacora_test.php:78 -->
- **CUANDO** `POST usuarios.guardar` llega sin id, con id `0` o vacío, o con id `7`
- **ENTONCES** la operación es `insercion` en los tres primeros casos y `actualizacion` con id 7

#### Scenario: Vista previa de una carga masiva
<!-- test: tests/bitacora_test.php:82 -->
<!-- test: tests/bitacora_test.php:219 -->
- **CUANDO** `POST maestros.puntos_importar` llega con `confirmar=0` o sin `confirmar`
- **ENTONCES** la operación es `consulta`; con `confirmar=1`, `insercion`

#### Scenario: Acción POST no listada
<!-- test: tests/bitacora_test.php:91 -->
<!-- test: tests/bitacora_test.php:92 -->
- **CUANDO** llega un POST de una acción o módulo desconocido
- **ENTONCES** la operación es `actualizacion`

#### Scenario: Cobertura de las acciones
<!-- test: tests/bitacora_test.php:111 -->
<!-- test: tests/bitacora_test.php:112 -->
- **CUANDO** se agrega una acción a un endpoint
- **ENTONCES** DEBE quedar clasificada en exactamente una de las dos tablas (escrituras o lecturas)

---

### Requirement: Eventos de los controladores
<!-- id: ModeloLogTransacciones.registrar -->
<!-- entidades: Bitacora -->
<!-- impuesto_en: modelos/log_transacciones.modelo.php ModeloLogTransacciones::registrar(); app/Support/Bitacora.php Bitacora::TIPOS, Bitacora::operacionDeTipo() -->

Un evento registrado por un controlador DEBE sumarse a la fila de la petición ajax en curso o, fuera de ajax (ingreso, salida, páginas, línea de comandos), escribirse de inmediato con módulo `sesion` (o `cli` por línea de comandos); su operación sale de la tabla `TIPOS`, con `actualizacion` para tipos desconocidos.

#### Scenario: Tipos de ingreso y salida
<!-- test: tests/bitacora_test.php:130 -->
- **CUANDO** se registran `login`, `login_fallido`, `captcha_fallido`, `logout`, `sesion_expirada` o `sesion_reemplazada`
- **ENTONCES** los tres primeros son `ingreso` y los tres últimos `salida`

#### Scenario: Tipo sin mapeo
<!-- test: tests/bitacora_test.php:134 -->
- **CUANDO** se registra un tipo que no está en `TIPOS`
- **ENTONCES** la operación es `actualizacion`

#### Scenario: Todo evento del código tiene su tipo
<!-- test: tests/bitacora_test.php:146 -->
- **CUANDO** un controlador, endpoint o script llama `registrar('<tipo>', …)`
- **ENTONCES** `<tipo>` DEBE estar en `TIPOS`

#### Scenario: Páginas
<!-- test: tests/e2e_local.php:592 -->
- **CUANDO** un usuario con sesión abre una página permitida, o una prohibida
- **ENTONCES** se escribe `consulta` con `tipo=pagina.<ruta>`, o `tipo=acceso_denegado` con "pagina.<ruta> · HTTP 403"

---

### Requirement: Detalle sin datos personales ni secretos
<!-- id: Bitacora.detalle -->
<!-- entidades: Bitacora -->
<!-- impuesto_en: app/Support/Bitacora.php Bitacora::detalle(), Bitacora::CLAVES_DETALLE, Bitacora::sinCorreos() -->
<!-- decision: Ley 1581 -->

El detalle DEBE ser `modulo.accion · clave=valor, …` con solo las claves permitidas (identificadores y filtros: id, archivo, acta, revision, clave, tipo, estado, punto_id, auditor_id, fecha, hora, desde, hasta, fuerza, unidad, confirmar, prefijo, reemplazar, dep, parte, usuario_id, formato, reporte, cantidad, solicitud_id, regional_id, municipio, ciclo, anio, item), en el orden en que llegan; DEBE omitir valores no escalares, cortar cada valor a 60 caracteres (59 + "…"), omitir `estado` de más de 20 caracteres, cambiar saltos de línea y tabulaciones por espacio, limpiar la acción a `[\w.-]` y 40 caracteres, y no pasar de 500 caracteres; las direcciones de correo DEBEN reemplazarse por `[correo]`.

#### Scenario: Ejemplo
<!-- test: tests/bitacora_test.php:150 -->
- **CUANDO** `agenda.programar` llega con tipo, punto_id, fecha, csrf y alcance
- **ENTONCES** el detalle es `agenda.programar · tipo=inicial, punto_id=4, fecha=2026-11-20`

#### Scenario: Datos personales y contraseñas
<!-- test: tests/bitacora_test.php:155 -->
<!-- test: tests/bitacora_test.php:157 -->
<!-- test: tests/e2e_local.php:599 -->
- **CUANDO** la petición trae password, mail, nombre, documento, teléfono, para, asunto o mensaje
- **ENTONCES** no aparecen en el detalle ni queda ninguna @ en la bitácora

#### Scenario: Estado JSON del checklist
<!-- test: tests/bitacora_test.php:163 -->
<!-- test: tests/bitacora_test.php:165 -->
<!-- test: tests/bitacora_test.php:167 -->
<!-- test: tests/e2e_local.php:597 -->
- **CUANDO** `estado` tiene 21 caracteres o más
- **ENTONCES** se omite; con 20 se conserva

#### Scenario: Valores largos con caracteres multibyte
<!-- test: tests/bitacora_test.php:179 -->
<!-- test: tests/bitacora_test.php:182 -->
<!-- test: tests/bitacora_test.php:191 -->
- **CUANDO** un valor tiene 61 "ñ" o el detalle total supera 500 caracteres
- **ENTONCES** se corta contando caracteres (no bytes), sin romper UTF-8

#### Scenario: Código y anotaciones siempre caben
<!-- test: tests/bitacora_test.php:221 -->
- **CUANDO** el detalle es largo y hay anotación y código no exitoso
- **ENTONCES** se recorta el detalle (el sufijo se reserva hasta 440 caracteres) y la descripción termina en " · HTTP <código>"

#### Scenario: Correos en un error de SMTP
<!-- test: tests/bitacora_test.php:225 -->
- **CUANDO** la descripción trae "recipients failed: ana.perez@agsamericas.com.co"
- **ENTONCES** se guarda "[correo]" en su lugar

---

### Requirement: La bitácora nunca tumba la operación
<!-- id: Bitacora.escribir -->
<!-- entidades: Bitacora -->
<!-- impuesto_en: app/Support/Bitacora.php Bitacora::escribir() -->

Si la escritura de la fila falla, la operación del usuario DEBE seguir y el contenido de la fila DEBE quedar en el registro de errores del servidor.

#### Scenario: Base de datos sin acceso a la tabla
- **CUANDO** el INSERT en `log_transacciones` lanza una excepción
- **ENTONCES** se escribe "[bitacora] No se pudo registrar {fila en JSON}: {error}" en el log de errores y la respuesta al usuario no cambia

---

### Requirement: Pantalla de logs del administrador
<!-- id: logs.ajax.lista -->
<!-- entidades: Bitacora, Usuario -->
<!-- impuesto_en: ajax/logs.ajax.php; modelos/logs.modelo.php ModeloLogs::lista() (POR_PAGINA=100); app/Support/Rutas.php ruta logs -->
<!-- decision: 5 | Anexo 5, prueba 15 -->
<!-- duda: 8.12 -->

Solo el Administrador DEBE ver la bitácora con usuario, fecha, operación, tipo, detalle e IP, filtrada siempre por rango de fechas (por defecto los últimos 7 días hasta hoy), y opcionalmente por usuario, operación y texto, ordenada de lo más reciente a lo más antiguo, en páginas de 100 con paginación por llave `(fecha, id_log)`, nunca por desplazamiento.

#### Scenario: Lista del día
<!-- test: tests/e2e_local.php:788 -->
- **CUANDO** el administrador pide `GET logs?accion=lista&desde=hoy&hasta=hoy`
- **ENTONCES** responde 200 con filas que traen `usuario`, `fecha`, `operacion`, `descripcion` (además de `id_log`, `tipo`, `modulo`, `ip`) y `siguiente` = [fecha, id] de la última fila si hay más

#### Scenario: Página siguiente
<!-- test: tests/e2e_local.php:791 -->
- **CUANDO** se pide la lista con `despues_fecha` y `despues_id` de la página anterior
- **ENTONCES** llegan filas estrictamente anteriores a esa llave, sin repetir ninguna

#### Scenario: Filtro por operación
<!-- test: tests/e2e_local.php:793 -->
- **CUANDO** se filtra por `operacion=eliminacion`
- **ENTONCES** solo llegan filas de eliminación; una operación fuera de las 6 se ignora

#### Scenario: Búsqueda por texto
- **CUANDO** se busca texto (máximo 80 caracteres, se escapan `%` y `_`) en un periodo de más de 92 días
- **ENTONCES** responde 422 "Para buscar por texto, use un periodo de máximo 92 días."; con un periodo menor busca en tipo, detalle y usuario

#### Scenario: Fechas invertidas o no válidas
- **CUANDO** `desde` es posterior a `hasta`, o alguna no es una fecha válida
- **ENTONCES** se intercambian, o se usan los valores por defecto

#### Scenario: Otro perfil
<!-- test: tests/e2e_local.php:794 -->
- **CUANDO** el coordinador pide la lista
- **ENTONCES** responde 403

#### Scenario: Opciones de filtro
- **CUANDO** se pide `GET logs?accion=opciones`
- **ENTONCES** llegan las 6 operaciones y todos los usuarios (id, nombre, usuario)

#### Scenario: Todo lo mostrado se escapa
- **CUANDO** la pantalla pinta fecha, usuario, operación, tipo, detalle o IP
- **ENTONCES** cada valor se escapa como texto y la fecha se muestra en hora de 24 h

---

### Invariant: Seis tipos de operación
<!-- entidades: Bitacora -->
<!-- impuesto_en: Database/Structure/001_usuarios.sql log_transacciones.operacion enum -->
<!-- verificado_por: tests/bitacora_test.php:27 -->

`operacion` DEBE ser una de: `insercion`, `actualizacion`, `eliminacion`, `consulta`, `ingreso`, `salida`; un valor distinto se guarda como `actualizacion`.

---

### Invariant: La bitácora se conserva tal cual
<!-- entidades: Bitacora, Usuario -->
<!-- impuesto_en: Database/Structure/001_usuarios.sql log_transacciones.usuario_id (sin FK a propósito) -->
<!-- duda: 7.5 -->

Las filas de la bitácora NO DEBEN depender de que el usuario siga existiendo (sin llave foránea) y se conservan todas, sin plazo de retención, mientras la duda 7.5 siga abierta.

---

### Invariant: Cada fila trae usuario, fecha e IP
<!-- entidades: Bitacora -->
<!-- impuesto_en: app/Support/Bitacora.php Bitacora::escribir() -->

Cada fila DEBE guardar `usuario_id` y `usuario` de la sesión (o los que pase el evento; antes del ingreso, el usuario enmascarado), la IP remota y la fecha y hora del servidor; un acceso por QR (usuario que empieza por "QR") NO DEBE quedar a nombre de un usuario interno con sesión en el mismo navegador.

---

### Invariant: Índices de consulta
<!-- entidades: Bitacora -->
<!-- impuesto_en: Database/Structure/001_usuarios.sql ix_log_fecha, ix_log_operacion_fecha, ix_log_tipo_fecha, ix_log_usuario_id_fecha, ix_log_usuario_fecha -->

La tabla de la bitácora DEBE tener índices por fecha y por (operación, tipo, usuario_id, usuario) con fecha, para que el filtro por rango sea la condición principal.
<!-- incierto: dudas_pendientes (fase 0) pedía quitar los índices que no use la pantalla de logs; la estructura actual mantiene los cinco -->

<!-- duda: 7.3 · la salida por cambio de contraseña o por usuario inactivado no deja fila de "salida" (ver identidad-sesion) -->
<!-- pendiente: vistas/modulos/logs.php, vistas/js/logs.js (solo se muestreó el escape), tests/ui (sin recorrido propio de logs) -->

---

### Requirement: Antes y después completos y estructurados (Java, sesión 2 · E3)
<!-- decision: Anexo 5, prueba en sitio ítem 15 b; decisión del 2026-10-20 (ítem 15: valores de antes y después); Ley 1581 -->
<!-- impuesto_en: reglas/Bitacora.cambiosDe, Cambio; plataforma/RegistroBitacora.registrarCambios; V32 log_transacciones.cambios -->

Toda modificación registrada con `registrarCambios` DEBE guardar, además del resumen en la descripción, los valores completos de antes y después de cada campo que cambió en la columna `cambios` (jsonb, hasta 1000 caracteres por valor y 20.000 bytes por fila); los datos personales DEBEN ir con `oculto=true` y sin valores; ningún valor DEBE llevar direcciones de correo.

#### Scenario: Usuario editado
- **CUANDO** el administrador cambia el usuario, los perfiles y el nombre de una persona
- **ENTONCES** la fila `usuario_editado` trae `usuario` y `perfiles` con sus valores de antes y después, y `nombre` oculto («modificado»)

#### Scenario: Pantalla de logs
- **CUANDO** el administrador filtra por tipo (exacto) y por módulo, y pulsa «Ver» en una fila con cambios
- **ENTONCES** ve la tabla campo / antes / después; el dato personal dice «Modificado (dato personal…)»; el Excel y el CSV traen la columna «Cambios (antes → después)»

#### Scenario: La fila nunca se pierde por los cambios
- **CUANDO** los valores no caben en los 20.000 bytes
- **ENTONCES** se acortan (con «…») hasta caber; si aun así no caben, la fila se escribe sin la columna de cambios
