# Dudas e inquietudes pendientes (reescritura Java/React)

Las dudas del PHP de referencia (secciones 1 a 15 de `C:\Users\dgonzalez\dev\digsa\docs\dudas_pendientes.md`) **se están resolviendo en otra sesión**. Antes de cada sprint se vuelven a leer los documentos del PHP y se aplica lo que esté decidido. Mientras tanto, cada duda abierta mantiene el comportamiento del PHP.

Aquí van solo las dudas nuevas de la reescritura, numeradas desde la 16 para no chocar con las del PHP. Cada una dice qué se hizo mientras tanto.

## Abiertas sin bloquear (decisión del usuario del 2026-10-20)

| # | Tema | Qué se hizo mientras tanto |
|---|---|---|
| 16.1 | **Datacenter nacional:** el pliego exige alojar todo en Colombia (respuestas AGS-29, AGS-77/78 y GMI P71), y hasta donde se sabe Azure no tiene región en Colombia | Se desarrolla para Azure, pero el código queda portátil: correo, archivos y secretos van detrás de interfaces, y todo se empaca en contenedores. Así puede ir a un datacenter o colocation en Colombia sin reescribir |
| 16.2 | Infraestructura como código: Bicep o Terraform | Sin decidir. No bloquea hasta el despliegue |
| 16.3 | CI/CD (GitHub Actions o Azure DevOps) y ubicación del repositorio remoto | Repositorio git local en `C:\Users\dgonzalez\dev\digsa-java`, sin remoto |
| 16.4 | Tareas diarias: Container Apps Jobs, `@Scheduled` con ShedLock o Azure Functions | Llegan después de la prueba (plazos del OPL, recordatorios) |
| 16.5 | ACS Email: ampliar el tope de unos 10 MB a los 25 MB codificados del acta, y delegar el DNS de un subdominio para SPF y DKIM | Hay adaptador SMTP. El de ACS se agrega cuando TI confirme |
| 16.6 | Tamaño que aceptan los servidores de correo de la DIGSA y de las fuerzas | Se medirá con el correo real |
| 16.7 | Probar en un iPhone o iPad real | WebKit de Playwright cubre el motor de Safari en Windows |
| 16.8 | Disponibilidad del 99,74 % | Producción se diseña con PostgreSQL en alta disponibilidad de zona |

## S1 · Plataforma y seguridad (2026-10-20)

| # | Duda | Qué se hizo mientras tanto |
|---|---|---|
| 17.1 | **MFA por correo y prueba en sitio:** los cinco usuarios de la prueba (ítem 2) necesitan un buzón al que se pueda entrar durante la prueba grabada para leer el código y el enlace de la contraseña. Además, la condición 12 prohíbe cambiar de pantalla a otra herramienta | Pendiente: crear buzones de prueba y acordar cómo leer el código sin salir de la aplicación (otro equipo o un celular). Si no es viable, decidir un plan para el día de la prueba |
| 17.2 | **Ítem 2 "en dos equipos a la vez":** si los dos equipos usan el mismo administrador, la sesión única (decisión 7.1) rechaza el segundo ingreso | Hay que llevar **dos administradores** creados de antemano. La creación simultánea del mismo usuario está probada: uno queda creado y el otro recibe 422 |
| 17.3 | **Perfil Analista:** lo pide el ítem 2 y la respuesta AGS-30. En este módulo no ve visitas (alcance "ninguno"); solo entra al inicio | Se creó como perfil 10. Su alcance real lo dará la adenda de perfiles y el módulo de cuentas médicas |
| 17.4 | **Documento obligatorio para auditores (decisión 26):** el PHP no lo exige en la pantalla de Usuarios, solo en Puntos y auditores | Aquí se exige también en Usuarios. Se avisa a la sesión del PHP |
| 17.5 | **Tope de códigos MFA:** 5 por hora y por usuario, igual que el código del QR del OPL | Es un parámetro (`digsa.mfa.codigos-por-hora`). Las pruebas de punta a punta lo suben a 1000 |
| 17.6 | **Persistencia:** el pedido decía JPA, o jOOQ para el SQL fino | Por velocidad, en S1 se usa SQL explícito con `JdbcClient` y parámetros enlazados. JPA o jOOQ se evaluarán donde aporten |
| 17.7 | **Docker:** el equipo no tiene WSL ni Docker | Pedido a TI en paralelo. Mientras tanto, PostgreSQL 16 embebido (zonky) en pruebas y en local |
| 17.8 | **Revalidación en cada petición:** por cada petición se lee el usuario y se guarda la sesión en la base (unos 30 ms en local) | Así lo pide la especificación. Se medirá con carga en S4 |
| 17.9 | **Navegación de páginas en la bitácora:** el PHP registraba cada página consultada (`pagina.agenda`). En la SPA la navegación no llama al servidor, así que cuenta la fila de cada consulta a la API. ¿Basta para la prueba 15? | Cada petición a la API deja su fila (consulta, inserción, actualización, etc.) |
| 17.10 | **Códigos MFA guardados con su IP:** la tabla `mfa_codigos` crece sin límite. No es bitácora, así que no le aplica la duda 7.5 | Se conservan. Falta decidir si se purgan los vencidos de más de N días (Ley 1581) |
| 17.11 | **Dominios para enviar reportes:** la bitácora y los reportes solo se envían a dominios permitidos (`DIGSA_DOMINIOS_REPORTES`). Para la prueba en sitio debe incluir `sanidad.mil.co` (correo del comité, condición 17) | Sin valor por defecto: en producción la aplicación no arranca si falta |
| 17.12 | **Esquema de la base:** las tablas quedan en `public` de una base propia para DIGSA, no en un esquema `digsa` | Se deja `public`, porque la base es exclusiva de DIGSA |

### Revisión de los agentes de S1 (2026-10-20)

Se corrigieron todas las prioridades 1:
- la contraseña nueva quedaba en claro en la bitácora;
- la exportación de logs no devolvía la conexión al pool;
- el envío parcial del SMTP se daba por entregado a los demás destinatarios;
- el comando del primer administrador no tenía prueba.

También se corrigieron la mayoría de las prioridades 2:
- correos fuera de las transacciones y código MFA consumido con un UPDATE condicionado;
- la IP se toma solo de proxies de confianza;
- validación al arrancar (HTTPS, SMTP, remitente, dominios de reportes y modo local solo en localhost);
- «olvidé mi contraseña» ya no devuelve el enlace en la respuesta;
- permisos mínimos para el rol de la aplicación (V2);
- particiones cortadas a medianoche de Bogotá;
- en el frontend: lo que se exporta coincide con lo que se ve, el aviso de inactividad no se congela, hay límite de errores, y los usuarios ESM y OPL regional quedan deshabilitados hasta S2.

Quedan pendientes, sin bloquear:

| # | Pendiente | Cuándo |
|---|---|---|
| 17.13 | Tipos de la API generados del OpenAPI, React Hook Form con Zod y ESLint (`react-hooks`, `jsx-a11y`) | Antes de los formularios de S2 |
| 17.14 | Prueba de que cada `registrar(...)` usa un tipo de `Bitacora.TIPOS`, y tabla esperada de endpoint a operación | S2 |
| 17.15 | El token del enlace va en `?tkn=`, que puede quedar en los registros del proxy. Cambiarlo a `#tkn=` con validación por POST | S2 |
| 17.16 | Renovar el token CSRF al iniciar sesión; tope por usuario en «olvidé mi contraseña», cuyo tiempo de respuesta revela si el usuario existe | S2 |
| 17.17 | Filtros opcionales de la bitácora con el patrón `(:x IS NULL OR …)`: armar el WHERE según los filtros. Tarea que cree las particiones futuras y alerte si la partición por defecto tiene filas | S4 |
| 17.18 | Paquete JS de unos 500 KB sin división por ruta | S2, antes de FullCalendar y pdf.js |

### Ajustes de S1 por las decisiones del 2026-10-20

Aplicado en S1 (ver `docs/decisiones_2026-10-20.md`):
- **7.1:** el segundo ingreso se rechaza (409 «ya tiene una sesión abierta») mientras la sesión abierta tenga actividad dentro de los 10 minutos. La actividad se guarda en la base como máximo cada 30 s. El administrador cierra la sesión desde Usuarios.
- **7.3:** inactivar, cambiar la contraseña o asignar una inicial cierra la sesión y deja la salida en la bitácora.
- **Ítem 2 y condición 12:** contraseña inicial asignada por el administrador, con cambio obligatorio dentro de la aplicación antes del código.
- **Ítem 15:** valores de antes y después en la bitácora; los datos personales solo dicen «modificado».
- Nombres de los perfiles configurables desde Usuarios.

| # | Duda | Qué se hizo mientras tanto |
|---|---|---|
| 17.19 | Con la sesión única bloqueante, un navegador que se cierra sin «Salir» deja al usuario sin poder entrar hasta 10 minutos | Se le dice en el mensaje y el administrador puede cerrarla. Las pruebas de punta a punta cierran la sesión al terminar cada caso |
| 17.20 | La contraseña inicial la ve el administrador al escribirla. ¿Se entrega por un canal aparte (papel, llamada)? | Se avisa en pantalla que se entregue por un canal seguro. No sale por correo y la bitácora no la guarda |

## S2 · Catálogos y agenda (2026-10-20)

| # | Duda | Qué se hizo mientras tanto |
|---|---|---|
| 18.1 | **Datos iniciales editables:** las fuerzas, los tipos de documento y de factura, las 8 regionales y los 185 puntos van en una migración versionada (V7) que corre una sola vez | Así lo que el coordinador elimine en vivo (prueba 4) no reaparece al volver a desplegar. Los catálogos de solo lectura (municipios, tipos de visita, parámetros) siguen en la repetible |
| 18.2 | **HOMIL y las 10 farmacias alternas** no traen fuerza en el anexo del OPL | Quedan con fuerza «NA» (no aplica). HOMIL y el Hospital Naval de Cartagena quedan con «exige químico farmacéutico» (Anexo 3 p.9) |
| 18.3 | **HONAC** aparece dos veces (ambulatoria y hospitalaria) con el mismo nombre | Son dos puntos: «HOSPITAL NAVAL DE CARTAGENA - AMBULATORIA» y «… - HOSPITALARIA» |
| 18.4 | **«Regional N» del anexo del OPL** no corresponde a las 8 regionales de la interventoría | Se guarda en `puntos.regional_opl` como texto. La regional de la interventoría queda vacía: la asigna el coordinador |
| 18.5 | **Municipios inferidos:** Apiay (Villavicencio), Tres Esquinas (Solano), Santana (Puerto Asís) y Usme (Bogotá) no son municipios. **BAEEV03 «La Mata, Cesar»** quedó sin municipio | Se tomó el municipio que los contiene. BAEEV03 lo completa el coordinador |
| 18.6 | **Fecha de la visita:** el PHP no impide programar o reprogramar en una fecha pasada | Aquí se rechaza (422 «no puede ser anterior a hoy») |
| 18.7 | **Componentes elegidos (prueba 11):** el coordinador elige los componentes al crear la visita. ¿El ESM puede tener C02 «Seguimiento a planes de mejoramiento» en una visita que no es de seguimiento? | La pantalla preselecciona los que aplican a la unidad, sin C02 salvo en la visita de seguimiento a plan. El servidor acepta cualquiera que aplique a la unidad |
| 18.8 | **Lo que ve el auditor (prueba 11):** «cada uno ve solo lo suyo» | En Mis visitas y en el detalle el auditor ve solo sus partes; no ve el historial ni las notificaciones. El formulario de S3 mostrará las partes de los demás en solo lectura, como en el PHP |
| 18.9 | **Seguimiento en tiempo real:** la pantalla se actualiza cada 15 s | Basta para la prueba. Si se pide inmediato, se agrega SSE o WebSocket |
| 18.10 | **Notificación previa en S2:** sale completa (destinatarios, contenido del Anexo 3, Ley 1581, registro previo), aunque el plan la dejaba para después de la prueba | Se adelantó porque la agenda la necesita para los estados. Las solicitudes a necesidad siguen para después |

| 18.11 | **Hora de la visita (ítem 12):** se usa el control nativo de hora del navegador (no es texto). En un equipo con Windows en formato de 12 horas muestra a. m./p. m. | Se guarda siempre en 24 h (HH:MM). Si la prueba exige verla en 24 h en cualquier equipo, se cambia por un control propio de hora militar |
| 18.12 | **Contraseña inicial: vigencia de 72 horas** sin usarse (revisión de seguridad) | Vencida, el ingreso responde como contraseña errada y la bitácora dice «contraseña inicial vencida»; el administrador asigna otra |
| 18.13 | **Notificación manual:** el coordinador puede agregar destinatarios externos (hasta 15) y cambiar el texto, que lleva el documento de los auditores | Se deja así (lo pide el Anexo 3 p.9 con destinatarios editables). Queda en el historial de la visita con el número de destinatarios. ¿Se restringe a dominios permitidos? |

### Revisión de los agentes de S2 (2026-10-20)

Se corrigieron las prioridades 1:
- **Seguridad:** el ESM (y el OPL regional) veían la visita a necesidad que aún no se hace.
- **Base de datos:** el rol de la aplicación no podía renombrar perfiles (V8).
- **Java:**
  - la notificación marcaba como notificada una visita que cambió mientras salía el correo;
  - el envío automático podía responder 422 después de guardar la reprogramación o la reasignación.
- **Fallos silenciosos:**
  - la entrega parcial contaba como completa, aunque rechazaran a la DISAN o al OPL;
  - con más de 15 destinatarios fallaba el registro después de crear la visita.
- **Pruebas:** había aserciones condicionales y lecturas de la bitácora de otra prueba. Faltaban pruebas de 403 por perfil, de detalle fuera de alcance y de la visita a necesidad para ESM y OPL.
- **React:** el fallo de la notificación automática salía en verde.

También se corrigieron, entre otras prioridades 2:
- carrera de dos ingresos simultáneos (`iniciarSesion` condicionado);
- doble envío del cambio de contraseña inicial;
- notificaciones «pendientes» huérfanas: se cierran como fallidas;
- DISAN o JEFSA exigida para cada fuerza;
- asunto sin saltos de línea;
- reglas del equipo en la base (orden único, líder con orden 0) y un índice nuevo en `puntos (fuerza)`;
- el reparto al iniciar no le da partes a un auditor inactivo;
- el calendario de consulta no muestra el equipo.

Quedan pendientes, sin bloquear:

| # | Pendiente | Cuándo |
|---|---|---|
| 18.14 | ESLint con `react-hooks` y `jsx-a11y`; alertas de plazo legibles en el celular (hoy en un tooltip); botones del calendario de 44 px en el celular | S3 |
| 18.15 | Pruebas de concurrencia en paralelo (dos coordinadores cancelan a la vez, iniciar frente a mover) y de la carrera de notificar frente a reprogramar con un correo falso que se demora | S3 |
| 18.16 | Lecturas SQL de agenda e identidad a tablas de catálogos y maestros: exponerlas como métodos de esos módulos | S3 |
| 18.17 | El cierre de sesión hecho por el administrador queda dos veces en la bitácora (al cerrar y en la siguiente petición de la sesión cerrada) | S3 |
| 18.18 | Avisar al correo anterior del usuario cuando el administrador le cambia el correo o le asigna una contraseña inicial | Después de la prueba |
