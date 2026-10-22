# Especificación: identidad-sesion

> Extraída por spec-miner el 2026-10-20 del PHP de referencia (develop, commit db2e330).
> Fuentes: app/Support/Auth.php, app/Support/Sesion.php, app/Support/Claves.php, app/Support/Rutas.php, controladores/controlador.php, controladores/usuarios.controlador.php, controladores/plantilla.controlador.php, ajax/sesion.ajax.php, ajax/usuarios.ajax.php (acción restablecer), modelos/usuarios.modelo.php, scripts/crear_admin.php, vistas/js/plantilla.js, vistas/partes/captcha.php, Database/Structure/001_usuarios.sql, .env.example, tests/sesion_test.php, tests/reglas_test.php, tests/ui/ui_sesion.js, tests/e2e_local.php, docs/decisiones_2026-10-15.md, docs/dudas_pendientes.md (sección 7).

---

### Requirement: Ingreso con usuario y contraseña
<!-- id: ControladorUsuarios.attemptLogin -->
<!-- entidades: Usuario, Bitacora -->
<!-- impuesto_en: controladores/usuarios.controlador.php ControladorUsuarios::attemptLogin() -->
<!-- decision: Anexo 5, 3.2 -->

El sistema DEBE iniciar sesión solo si el formulario trae token CSRF válido, el captcha se supera, el usuario existe, está activo, no está bloqueado y la contraseña coincide; tras el ingreso DEBE redirigir a `inicio`.

#### Scenario: Ingreso correcto
<!-- test: tests/e2e_local.php:174 -->
- **CUANDO** un usuario activo, con contraseña definida y sin bloqueo envía usuario, contraseña, token CSRF y captcha válidos
- **ENTONCES** se regenera el identificador de sesión, la sesión queda iniciada con sus perfiles activos, se registra `login` (operación `ingreso`) en la bitácora y redirige a `inicio`

#### Scenario: Mismo mensaje para usuario inexistente, inactivo o contraseña errada
<!-- test: tests/e2e_local.php:543 -->
- **CUANDO** el usuario no existe, está inactivo, está bloqueado o la contraseña no coincide
- **ENTONCES** la pantalla de ingreso muestra siempre "Usuario o contraseña incorrectos. Después de 5 intentos fallidos el usuario se bloquea 15 minutos." y se registra `login_fallido` con el motivo real (usuario inexistente, inactivo, bloqueado o contraseña incorrecta)

#### Scenario: Tiempo de respuesta sin delatar al usuario
- **CUANDO** el usuario no existe, está inactivo o está bloqueado
- **ENTONCES** igual se ejecuta una verificación de contraseña contra un hash de relleno, para que el tiempo de respuesta no revele si el usuario existe

#### Scenario: Usuario escrito en la bitácora sin revelar lo tecleado
- **CUANDO** el ingreso falla con un usuario inexistente o falla el captcha
- **ENTONCES** la bitácora guarda solo los 3 primeros caracteres de lo escrito seguidos de `***`

#### Scenario: Token CSRF del formulario vencido
- **CUANDO** el formulario de ingreso llega sin token CSRF válido
- **ENTONCES** se vuelve a la pantalla de ingreso con "La página expiró. Intente de nuevo." y no se verifica la contraseña

---

### Requirement: Captcha Cloudflare Turnstile en el ingreso y en "olvidé mi contraseña"
<!-- id: Controlador.checkCaptcha -->
<!-- entidades: Bitacora -->
<!-- impuesto_en: controladores/controlador.php Controlador::checkCaptcha() -->
<!-- decision: .env.example CLOUDFLARE_SECRET_KEY / CLOUDFLARE_SITE_KEY -->

El servidor DEBE verificar la respuesta de Turnstile (`cf-turnstile-response`) contra el servicio de Cloudflare, enviando la IP del cliente (`CF-Connecting-IP` o la remota) con un tiempo máximo de 10 s; sin clave secreta configurada, el captcha DEBE darse por superado solo en el entorno local.

#### Scenario: Captcha no superado
- **CUANDO** Cloudflare responde `success` distinto de `true`, no responde o la respuesta falta
- **ENTONCES** el ingreso se rechaza con "Por favor complete la verificación de seguridad (captcha)." y se registra `captcha_fallido` (operación `ingreso`)

#### Scenario: Sin clave secreta fuera del entorno local
- **CUANDO** `CLOUDFLARE_SECRET_KEY` está vacía y `APP_ENV` no es `local`
- **ENTONCES** el captcha se considera NO superado (falla cerrado) y nadie puede ingresar

#### Scenario: Entorno local sin claves
- **CUANDO** `APP_ENV=local` y no hay `CLOUDFLARE_SITE_KEY` ni secreta
- **ENTONCES** el captcha se da por superado y la pantalla muestra "Captcha desactivado en local"

---

### Requirement: Bloqueo temporal por intentos fallidos
<!-- id: ControladorUsuarios.verificarClave -->
<!-- entidades: Usuario -->
<!-- impuesto_en: controladores/usuarios.controlador.php ControladorUsuarios::verificarClave() (INTENTOS_MAXIMOS=5, MINUTOS_BLOQUEO=15) -->

El sistema DEBE bloquear el usuario 15 minutos al acumular 5 contraseñas erradas, contando los intentos de forma atómica en la base.

#### Scenario: Quinto intento fallido
- **CUANDO** un usuario sin bloqueo vigente falla la contraseña y con ese fallo `intentos` llega a 5
- **ENTONCES** `bloqueado_hasta` queda en ahora + 15 minutos

#### Scenario: Intento con el usuario bloqueado
- **CUANDO** el usuario tiene `bloqueado_hasta` en el futuro, aunque la contraseña sea correcta
- **ENTONCES** el ingreso se rechaza con el mensaje genérico, no se incrementa el contador y se registra `login_fallido` "Intento con el usuario bloqueado"

#### Scenario: Bloqueo vencido
- **CUANDO** el bloqueo ya venció y el usuario vuelve a fallar
- **ENTONCES** el contador se reinicia a 0 antes de sumar el nuevo fallo

#### Scenario: Ingreso correcto reinicia el contador
- **CUANDO** la contraseña coincide y no hay bloqueo vigente
- **ENTONCES** `intentos` vuelve a 0 y `bloqueado_hasta` a NULL
<!-- incierto: el bloqueo no tiene una aserción propia en tests/e2e_local.php (líneas 537-538 hacen 6 intentos pero solo se verifica el mensaje genérico) -->

---

### Requirement: Reglas de la contraseña
<!-- id: Claves.problema -->
<!-- entidades: Usuario -->
<!-- impuesto_en: app/Support/Claves.php Claves::problema() -->
<!-- decision: decisión del usuario 2026-10-14 (sin ñ ni tildes) -->

Una contraseña nueva DEBE tener mínimo 12 caracteres, mayúsculas y minúsculas, al menos un número, al menos un carácter especial y no DEBE contener ñ ni vocales con tilde o diéresis; además DEBE coincidir con su confirmación y ser distinta de la actual.

#### Scenario: Contraseña válida
<!-- test: tests/reglas_test.php:41 -->
- **CUANDO** se propone `Digsa.Prueba.2026`
- **ENTONCES** no hay problema

#### Scenario: Con ñ o tildes
<!-- test: tests/reglas_test.php:42 -->
<!-- test: tests/e2e_local.php:167 -->
- **CUANDO** la contraseña contiene ñ, Ñ, á, é, í, ó, ú (o mayúsculas) o ü
- **ENTONCES** se rechaza con "La contraseña no puede tener ñ ni tildes." (esta regla se evalúa primero)

#### Scenario: Menos de 12 caracteres
<!-- test: tests/reglas_test.php:44 -->
- **CUANDO** la contraseña tiene 11 caracteres
- **ENTONCES** se rechaza con "La contraseña debe tener mínimo 12 caracteres."

#### Scenario: Sin carácter especial
<!-- test: tests/reglas_test.php:45 -->
- **CUANDO** la contraseña solo tiene letras y números
- **ENTONCES** se rechaza con "La contraseña debe tener al menos un carácter especial."

#### Scenario: Confirmación distinta o igual a la actual
- **CUANDO** la confirmación no coincide, o la nueva contraseña verifica contra el hash actual
- **ENTONCES** se rechaza con "Las contraseñas no coinciden." o "La nueva contraseña debe ser diferente a la actual."

---

### Requirement: Enlace de un solo uso para definir la contraseña
<!-- id: ControladorUsuarios.enviarEnlaceClave -->
<!-- entidades: Usuario, Correo -->
<!-- impuesto_en: controladores/usuarios.controlador.php ControladorUsuarios::enviarEnlaceClave(), ControladorUsuarios::cambiarClave(); app/Support/Claves.php Claves::enlaceVigente() (MINUTOS_ENLACE=60) -->

El sistema DEBE generar un token aleatorio de 64 caracteres hexadecimales, guardar solo su SHA-256 con vencimiento a 60 minutos y enviarlo por correo como enlace `cambiar-clave?tkn=…`; el enlace DEBE servir una sola vez y solo antes de vencer.

#### Scenario: Enlace vigente
<!-- test: tests/reglas_test.php:47 -->
<!-- test: tests/e2e_local.php:169 -->
- **CUANDO** el token tiene formato `^[a-f0-9]{64}$`, su SHA-256 coincide con el guardado y `token_expira` es igual o posterior a ahora
- **ENTONCES** se muestra el formulario y, con una contraseña válida, se guarda y redirige al ingreso con "La contraseña se cambió correctamente. Inicie sesión con su nueva contraseña."

#### Scenario: Enlace vencido, con otro token o sin token guardado
<!-- test: tests/reglas_test.php:48 -->
<!-- test: tests/reglas_test.php:49 -->
<!-- test: tests/reglas_test.php:50 -->
- **CUANDO** el enlace venció, el token no coincide o no hay token guardado
- **ENTONCES** la página dice que el enlace no es válido y no permite cambiar la contraseña

#### Scenario: Segundo uso del mismo enlace
<!-- test: tests/e2e_local.php:171 -->
- **CUANDO** se abre de nuevo un enlace ya usado
- **ENTONCES** la página dice que no es válido (al usarlo se borraron `token` y `token_expira`)

#### Scenario: Efectos del cambio de contraseña
- **CUANDO** se guarda una contraseña nueva por el enlace
- **ENTONCES** se guarda su hash, `ultimo_login` y `clave_actualizada` quedan en ahora, `debe_cambiar_clave` = 0, `fecha_cambio_contrasena` = ahora + `CLAVE_VIGENCIA_DIAS` (30 por defecto, mínimo 1), se borran el token, los intentos y el bloqueo, y se registra `clave_cambio`

#### Scenario: Modo local sin correo
- **CUANDO** `APP_ENV=local` y `MAIL_MAILER=log`
- **ENTONCES** la respuesta incluye `enlace_local` con el enlace para mostrarlo en pantalla

---

### Requirement: Cambio de contraseña obligatorio
<!-- id: ControladorUsuarios.forzarCambio -->
<!-- entidades: Usuario, Correo -->
<!-- impuesto_en: app/Support/Auth.php Auth::revalidar() estado 'cambio'; controladores/usuarios.controlador.php ControladorUsuarios::forzarCambio(); controladores/plantilla.controlador.php -->

El sistema DEBE exigir definir la contraseña cuando el usuario nunca la ha definido (`ultimo_login` NULL), cuando el administrador lo exigió (`debe_cambiar_clave` = 1) o cuando venció (`fecha_cambio_contrasena` anterior a ahora); mientras tanto la sesión no sirve.

#### Scenario: Primer ingreso
<!-- test: tests/e2e_local.php:165 -->
- **CUANDO** un usuario que nunca definió su contraseña ingresa y abre una página
- **ENTONCES** la sesión se cierra (también su huella de sesión vigente), se envía un enlace nuevo al correo y se muestra "Es su primer ingreso: debe definir su contraseña." con el aviso de revisar el correo

#### Scenario: Contraseña vencida a los 30 días o cambio exigido
- **CUANDO** `fecha_cambio_contrasena` ya pasó o `debe_cambiar_clave` = 1 y el usuario abre una página
- **ENTONCES** la sesión se cierra, se envía un enlace nuevo (reemplaza al anterior) y se muestra "Debe actualizar su contraseña."

#### Scenario: Petición ajax con cambio pendiente
- **CUANDO** una petición a `ajax/` llega con el cambio pendiente
- **ENTONCES** responde 403 "Debe definir su contraseña antes de continuar."
<!-- incierto: tras «Restablecer contraseña» del administrador, si el usuario tenía sesión abierta, su siguiente página cierra la sesión y envía otro enlace que invalida el del administrador; el código lo hace así pero no hay prueba ni decisión que lo confirme -->

---

### Requirement: Contraseña vencida y «Cambiar mi contraseña» dentro de la aplicación
<!-- decision: condición 12 de la prueba en sitio (no saltar a otra herramienta); sesión 2, E1 (2026-10-22) -->

La contraseña vencida (30 días) de un usuario que la conoce DEBE cambiarse en el ingreso, sin enlace al correo; el usuario con sesión DEBE poder cambiar su contraseña dando la actual.

#### Scenario: Vencida al ingresar
- **CUANDO** el usuario ingresa con su contraseña correcta y vencida
- **ENTONCES** el ingreso responde el paso `cambiar_clave` («Su contraseña venció…»), no envía correo, y después del cambio sigue el código; queda `clave_cambio` «Contraseña vencida cambiada»

#### Scenario: Vence con la sesión abierta
- **CUANDO** la contraseña vence mientras la sesión está abierta
- **ENTONCES** la siguiente petición responde 401 `clave_vencida` («Ingrese de nuevo para cambiarla»), sin correo, y queda la salida `sesion_cerrada`

#### Scenario: Cambiar mi contraseña
- **CUANDO** el usuario con sesión envía `POST /api/cuenta/clave` con la actual, la nueva y la confirmación
- **ENTONCES** con la actual correcta se guarda (vigencia nueva de 30 días), la sesión en curso sigue abierta y queda `clave_cambio`; con la actual errada responde 422 y suma un intento fallido (`clave_cambio_fallido`); al quinto el usuario se bloquea 15 minutos y la sesión se cierra (401 `bloqueado`)

#### Scenario: Desbloqueo por el administrador
- **CUANDO** el administrador pulsa «Desbloquear» en un usuario bloqueado por intentos
- **ENTONCES** se reinician los intentos y queda `usuario_desbloqueado`

---

### Requirement: Olvidé mi contraseña
<!-- id: ControladorUsuarios.olvideClave -->
<!-- entidades: Usuario, Correo, Bitacora -->
<!-- impuesto_en: controladores/usuarios.controlador.php ControladorUsuarios::olvideClave(); modelos/usuarios.modelo.php ModeloUsuarios::porUsuarioOCorreo() -->

La ruta pública `olvide-clave` DEBE aceptar usuario o correo, exigir CSRF y captcha, enviar un enlace solo si el usuario existe y está activo, y responder siempre lo mismo para no revelar quién está registrado; el enlace NO DEBE obligar al cambio ni cerrar sesiones.

#### Scenario: Solicitud de un tercero
<!-- test: tests/e2e_local.php:534 -->
- **CUANDO** alguien pide el enlace para un usuario que tiene sesión abierta
- **ENTONCES** la sesión de ese usuario sigue vigente y su contraseña no cambia mientras no use el enlace

#### Scenario: Usuario inexistente o inactivo
- **CUANDO** el valor no corresponde a un usuario activo
- **ENTONCES** no se envía nada y la pantalla muestra el mismo mensaje de enviado

#### Scenario: Solicitud válida
- **CUANDO** el usuario existe y está activo
- **ENTONCES** se envía el enlace "Recuperación de contraseña" y se registra `clave_olvido` indicando si el correo salió

#### Scenario: Mismo tiempo de respuesta y tope por usuario (Java, duda 17.16)
- **CUANDO** se pide la recuperación
- **ENTONCES** la búsqueda y el correo van en un hilo aparte (la respuesta no revela por su tiempo si el usuario existe) y un mismo usuario recibe como máximo un enlace cada 5 minutos; el pedido repetido queda en la bitácora sin enviar otro

---

### Requirement: Sesión única por usuario
<!-- id: Auth.iniciar -->
<!-- entidades: Usuario, Sesion, Bitacora -->
<!-- impuesto_en: app/Support/Auth.php Auth::iniciar(), Auth::revalidar(), Auth::invalidar(); app/Support/Sesion.php Sesion::huella(), Sesion::esLaVigente() -->
<!-- decision: Anexo 5, 3.2 -->
<!-- duda: 7.1 -->

Un usuario DEBE tener una sola sesión vigente: al ingresar se guarda en `usuarios.sesion_huella` el SHA-256 de la marca de la sesión nueva. **Decisión 7.1 (2026-10-20, manda sobre el PHP): mientras la sesión abierta tenga actividad dentro del plazo de inactividad, el segundo ingreso se rechaza (409 «ya tiene una sesión abierta»)**; la sesión se libera con «Salir», por inactividad o cuando el administrador la cierra. Solo si la sesión anterior venció por inactividad, el ingreso nuevo la reemplaza y la anterior recibe «reemplazada».

#### Scenario: Segundo ingreso desde otro navegador
<!-- test: tests/e2e_local.php:559 -->
<!-- test: tests/e2e_local.php:560 -->
<!-- test: tests/e2e_local.php:562 -->
- **CUANDO** el mismo usuario ingresa desde otro equipo o navegador
- **ENTONCES** la sesión nueva funciona y la anterior recibe 401 en ajax (o vuelve al ingreso en páginas)

#### Scenario: Motivo en la pantalla de ingreso
<!-- test: tests/e2e_local.php:561 -->
<!-- test: tests/ui/ui_sesion.js:78 -->
- **CUANDO** la sesión reemplazada hace su siguiente petición
- **ENTONCES** la pantalla de ingreso dice "Su sesión se cerró porque su usuario ingresó desde otro equipo o navegador. Solo se permite una sesión a la vez." y se registra `sesion_reemplazada` (operación `salida`)

#### Scenario: Bitácora del ingreso que reemplaza
<!-- test: tests/e2e_local.php:572 -->
- **CUANDO** el usuario ingresa teniendo una huella de sesión guardada
- **ENTONCES** el `login` se registra con "Inicio de sesión; reemplaza una sesión anterior que no se cerró con «Salir» (sesión única)"

#### Scenario: «Salir» desde una sesión ya reemplazada
<!-- test: tests/e2e_local.php:568 -->
- **CUANDO** una sesión reemplazada pulsa «Salir»
- **ENTONCES** la huella vigente (de la otra sesión) no se borra y esa otra sesión sigue funcionando

#### Scenario: Huella de la sesión
<!-- test: tests/sesion_test.php:50 -->
<!-- test: tests/sesion_test.php:55 -->
<!-- test: tests/sesion_test.php:57 -->
<!-- test: tests/sesion_test.php:59 -->
- **CUANDO** se compara la huella guardada con el identificador de una sesión
- **ENTONCES** es vigente solo si la huella no es NULL ni vacía y coincide (comparación de tiempo constante) con el SHA-256 en hexadecimal del identificador; el identificador en claro nunca se acepta como huella

---

### Requirement: Cierre por inactividad
<!-- id: Auth.iniciarSesion -->
<!-- entidades: Sesion, Bitacora -->
<!-- impuesto_en: app/Support/Auth.php Auth::iniciarSesion(); app/Support/Sesion.php Sesion::minutos(), Sesion::vencida() -->
<!-- decision: Anexo 5, 3.2 -->
<!-- duda: 7.4 -->

El servidor DEBE cerrar la sesión cuando pasan los minutos de inactividad (10 por defecto) o más desde el último acceso. **Java (E1 de la sesión 2): el Administrador los cambia en Usuarios › Seguridad (tabla `seg_parametros`, V30; aplica en la siguiente petición y queda en la bitácora); sin valor guardado vale `DIGSA_SESION_MINUTOS`**; un valor de `SESSION_MINUTOS` que no sea un entero positivo DEBE volver a 10 y uno mayor que 480 DEBE quedar en 480.

#### Scenario: Plazo cumplido
<!-- test: tests/sesion_test.php:42 -->
<!-- test: tests/e2e_local.php:632 -->
<!-- test: tests/e2e_local.php:633 -->
- **CUANDO** han pasado exactamente `minutos × 60` segundos o más desde el último acceso
- **ENTONCES** la sesión se cierra, se borra la huella vigente, se registra `sesion_expirada` (operación `salida`), ajax responde 401 y la pantalla de ingreso dice "La sesión se cerró por inactividad. Vuelva a iniciar sesión."

#### Scenario: Antes del plazo
<!-- test: tests/sesion_test.php:41 -->
<!-- test: tests/e2e_local.php:630 -->
- **CUANDO** han pasado 599 s con 10 minutos
- **ENTONCES** la sesión sigue y la petición renueva el último acceso

#### Scenario: Valores de SESSION_MINUTOS
<!-- test: tests/sesion_test.php:35 -->
- **CUANDO** el valor es `''`, `'abc'`, `'15 min'`, `'10.5'`, `0`, `-5` o NULL
- **ENTONCES** se usan 10 minutos; `' 15 '` da 15; `481` o `'1000'` dan 480

---

### Requirement: Aviso de inactividad en el navegador
<!-- id: plantilla.js aviso-sesion -->
<!-- entidades: Sesion -->
<!-- impuesto_en: vistas/js/plantilla.js; vistas/partes/head.php meta sesion-minutos; ajax/sesion.ajax.php accion=mantener -->
<!-- decision: Anexo 5, 3.2 -->

Cada página con sesión DEBE conocer los minutos de inactividad y, un minuto antes del cierre (o la mitad del plazo si es menor), mostrar un aviso con cuenta regresiva y los botones «Salir» y «Seguir conectado»; la actividad DEBE compartirse entre pestañas.

#### Scenario: Aviso con cuenta regresiva
<!-- test: tests/ui/ui_sesion.js:32 -->
<!-- test: tests/ui/ui_sesion.js:52 -->
- **CUANDO** faltan 60 segundos o menos para el plazo sin respuestas del servidor
- **ENTONCES** aparece el aviso "Su sesión está por cerrarse" con los segundos restantes

#### Scenario: Seguir conectado
<!-- test: tests/ui/ui_sesion.js:55 -->
<!-- test: tests/e2e_local.php:553 -->
<!-- test: tests/e2e_local.php:563 -->
- **CUANDO** el usuario pulsa «Seguir conectado»
- **ENTONCES** se llama `GET ajax/sesion.ajax.php?accion=mantener` (solo GET; otro método da 405), responde 200 con los minutos y el aviso desaparece; ese latido no se registra en la bitácora

#### Scenario: Actividad en otra pestaña
<!-- test: tests/ui/ui_sesion.js:65 -->
- **CUANDO** otra pestaña de la aplicación tuvo una respuesta del servidor
- **ENTONCES** el aviso de esta pestaña desaparece (la última actividad se comparte por `localStorage` con la clave `digsa.actividad`)

#### Scenario: Plazo vencido en el navegador
<!-- test: tests/ui/ui_sesion.js:71 -->
<!-- test: tests/ui/ui_sesion.js:99 -->
- **CUANDO** pasan 2 segundos después del plazo
- **ENTONCES** la página navega a la raíz y el servidor muestra el motivo del cierre

---

### Requirement: Revalidación de la sesión en cada petición
<!-- id: Auth.revalidar -->
<!-- entidades: Usuario, Perfil, Alcance -->
<!-- impuesto_en: app/Support/Auth.php Auth::revalidar(), Auth::requerirAjax(); controladores/plantilla.controlador.php -->
<!-- duda: 7.3 -->

En cada página y cada petición ajax el servidor DEBE releer el usuario: si está inactivo o su contraseña cambió después de iniciar esta sesión, la sesión DEBE cerrarse; los perfiles y el alcance DEBEN recalcularse desde la base.

#### Scenario: Usuario inactivado
<!-- test: tests/e2e_local.php:528 -->
- **CUANDO** el administrador inactiva a un usuario con sesión abierta
- **ENTONCES** su siguiente petición ajax recibe 401 y la pantalla de ingreso dice "Su usuario está inactivo."

#### Scenario: Contraseña cambiada en otra sesión
- **CUANDO** `clave_actualizada` es posterior al inicio de esta sesión
- **ENTONCES** la sesión se cierra y el ingreso dice "La contraseña cambió. Inicie sesión de nuevo."

#### Scenario: Perfil quitado
- **CUANDO** el administrador quita un perfil o el perfil se inactiva en `usuarios_perfiles`
- **ENTONCES** deja de servir desde la siguiente petición

#### Scenario: Salida sin fila de bitácora
- **CUANDO** la sesión se cierra por usuario inactivado o por contraseña cambiada
- **ENTONCES** no se escribe fila de "salida" (sí la dejan «Salir», la inactividad y la sesión reemplazada)

---

### Requirement: Protección de los endpoints ajax
<!-- id: Auth.requerirAjax -->
<!-- entidades: Sesion, Bitacora -->
<!-- impuesto_en: app/Support/Auth.php Auth::requerirAjax(), Auth::csrfValido() -->

Todo endpoint `ajax/` DEBE responder JSON y comprobar, en este orden: sesión (401), tamaño de la petición (413), vigencia de la sesión (401) o cambio pendiente (403), perfil (403) y token CSRF en todo lo que no sea GET (419).

#### Scenario: POST sin token CSRF
<!-- test: tests/e2e_local.php:182 -->
- **CUANDO** llega un POST sin encabezado `X-CSRF-Token` ni campo `csrf` válidos
- **ENTONCES** responde 419 "La página expiró. Recárguela e intente de nuevo."

#### Scenario: Sin sesión
- **CUANDO** llega una petición sin sesión iniciada
- **ENTONCES** responde 401 "La sesión expiró. Vuelva a iniciar sesión."

#### Scenario: Perfil sin permiso
- **CUANDO** el usuario no tiene ninguno de los perfiles exigidos (el Administrador siempre pasa)
- **ENTONCES** responde 403 "No tiene permiso para esta acción."

---

### Requirement: Salir
<!-- id: ControladorUsuarios.salir -->
<!-- entidades: Sesion, Bitacora -->
<!-- impuesto_en: controladores/usuarios.controlador.php ControladorUsuarios::salir() -->

La ruta `salir` DEBE registrar `logout`, borrar la huella vigente solo si era la de esta sesión, destruir la sesión y su cookie, y volver al ingreso.

#### Scenario: Salir desde la sesión vigente
<!-- test: tests/e2e_local.php:601 -->
<!-- test: tests/e2e_local.php:602 -->
- **CUANDO** el usuario pulsa «Salir» en la sesión vigente
- **ENTONCES** queda `logout` (operación `salida`) en la bitácora y `sesion_huella` queda NULL

---

### Requirement: Crear el primer administrador
<!-- id: scripts/crear_admin.php -->
<!-- entidades: Usuario, Perfil, Correo, Bitacora -->
<!-- impuesto_en: scripts/crear_admin.php -->

El primer administrador DEBE crearse solo por línea de comandos con correo, nombre completo y usuario opcional; no hay contraseñas en el repositorio: se crea con una contraseña aleatoria que nadie conoce y se envía el enlace de un solo uso.

#### Scenario: Administrador nuevo
<!-- test: tests/e2e_local.php:161 -->
- **CUANDO** se ejecuta `php scripts/crear_admin.php correo "Nombre Completo" [usuario]` con un correo que no existe
- **ENTONCES** en una transacción se crea el usuario activo con el perfil Administrador, se envía el enlace "Bienvenido a la Suite DIGSA", se registra `admin_creado` con usuario `cli` y termina con código 0 (o 2 si el correo no salió)

#### Scenario: Correo ya registrado
- **CUANDO** el correo ya pertenece a un usuario
- **ENTONCES** se le agrega el perfil Administrador sin quitar los que tiene y se le envía el enlace
<!-- incierto: si ese usuario está inactivo, el script no lo activa; no hay prueba de ese caso -->

#### Scenario: Datos no válidos o ejecución por la web
- **CUANDO** el correo no es válido, el nombre tiene menos de 3 caracteres, el usuario no cumple `^[a-z0-9._-]{3,50}$`, o el script se invoca fuera de la línea de comandos
- **ENTONCES** termina con código 1 y el mensaje de uso (o 403 "Solo por línea de comandos.")

#### Scenario: Usuario por defecto
- **CUANDO** no se da el usuario
- **ENTONCES** se propone uno libre a partir de la parte del correo antes de la @ (solo `a-z0-9._-`, máximo 45, relleno a 3 con ceros, sufijo 2, 3… si existe)

---

### Requirement: Acceso a rutas por perfil
<!-- id: Rutas.puedeEntrar -->
<!-- entidades: Perfil -->
<!-- impuesto_en: app/Support/Rutas.php Rutas::PRIVADAS, Rutas::puedeEntrar(), Rutas::menu(); controladores/plantilla.controlador.php -->

Cada ruta privada DEBE declarar sus perfiles; el Administrador entra a todas, una lista vacía admite a cualquier usuario con sesión y el menú DEBE mostrar solo las rutas a las que el usuario puede entrar.

#### Scenario: Ruta sin permiso
<!-- test: tests/reglas_test.php:67 -->
- **CUANDO** un Auditor pide `agenda`, `maestros`, `dashboard` o `usuarios`
- **ENTONCES** recibe 403 "Acceso restringido" y se registra `acceso_denegado`

#### Scenario: Ruta inexistente o sin sesión
<!-- test: tests/reglas_test.php:93 -->
<!-- test: tests/reglas_test.php:95 -->
- **CUANDO** la ruta no existe, o no hay sesión
- **ENTONCES** nadie entra (404 con sesión; ingreso sin sesión)

#### Scenario: Rutas públicas
- **CUANDO** se pide `olvide-clave`, `cambiar-clave` u `opl-responder`
- **ENTONCES** se atienden sin sesión

---

### Invariant: La contraseña nunca se guarda en claro
<!-- entidades: Usuario -->
<!-- impuesto_en: Database/Structure/001_usuarios.sql usuarios.password (password_hash) -->

`usuarios.password` DEBE contener solo el hash de `password_hash()`; los usuarios creados por el administrador, la carga de auditores o `crear_admin.php` DEBEN recibir una contraseña aleatoria de 48 caracteres hexadecimales que nadie conoce.

---

### Invariant: Del enlace y de la sesión solo se guarda la huella
<!-- entidades: Usuario -->
<!-- impuesto_en: Database/Structure/001_usuarios.sql usuarios.token char(64), usuarios.sesion_huella char(64) -->
<!-- verificado_por: tests/sesion_test.php:59 -->

`usuarios.token` y `usuarios.sesion_huella` DEBEN guardar el SHA-256 en hexadecimal del token y del identificador de sesión, nunca su valor en claro.

---

### Invariant: Usuario y correo únicos
<!-- entidades: Usuario -->
<!-- impuesto_en: Database/Structure/001_usuarios.sql uq_usuarios_usuario, uq_usuarios_mail -->
<!-- verificado_por: tests/e2e_local.php:190 -->

`usuarios.usuario` y `usuarios.mail` DEBEN ser únicos.

---

### Invariant: Cookie de sesión endurecida
<!-- entidades: Sesion -->
<!-- impuesto_en: app/Support/Auth.php Auth::iniciarSesion() -->

La cookie de sesión DEBE ser `HttpOnly`, `SameSite=Strict`, sin vencimiento propio (dura lo que el navegador), `Secure` cuando `APP_URL` empieza por `https://`, con nombre `DIGSA_<APP_ENV>`; el servidor NO DEBE aceptar identificadores de sesión inventados por el cliente.

---

### Invariant: Token CSRF por sesión
<!-- entidades: Sesion -->
<!-- impuesto_en: app/Support/Auth.php Auth::csrf(), Auth::csrfValido() -->

Cada sesión DEBE tener un token CSRF aleatorio de 64 caracteres hexadecimales, regenerado al ingresar, y compararse en tiempo constante. (Java: cookie `XSRF-TOKEN`; el ingreso emite un token nuevo, duda 17.16.)

<!-- duda: 7.6 · TI de producción: `session.gc_maxlifetime` mayor que `SESSION_MINUTOS`; en la reescritura equivale a la vida de la sesión del servidor -->
<!-- duda: 7.7 · las sesiones abiertas antes del despliegue de la fase 0 se cerraron una vez con el aviso de "otro equipo" -->
<!-- pendiente: vistas/login.php, vistas/publico/cambiar_clave.php, vistas/publico/olvide_clave.php, vistas/publico/revise_correo.php, mails/cambio_clave.html, app/Support/Mail/Mailer.php, vistas/js/usuarios.js -->
