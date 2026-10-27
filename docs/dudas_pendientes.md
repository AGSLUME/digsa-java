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

## S3 · Formulario y acta, y S4 · Reportes (2026-10-21)

| # | Duda | Qué se hizo mientras tanto |
|---|---|---|
| 19.1 | **Diseño del formulario:** el PHP guarda el checklist como un JSON con revisión | Aquí cada calificación es una fila (`visitas_respuestas`). Así cada auditor escribe solo sus ítems sin pisar a los demás, y los reportes leen tablas. El comportamiento funcional es el mismo |
| 19.2 | **Obligaciones de la minuta (parte OBL):** se extraen por OCR de la minuta del contrato. Las minutas reales siguen pendientes (duda 5.1 del PHP) | En S3 la parte OBL no tiene ítems: el formulario evalúa los componentes elegidos. La lectura de la minuta y las obligaciones llegan con el flujo del OPL (fase 6) |
| 19.3 | **Plan 5W2H:** el PHP exige el plan y sus dos firmas para enviar el acta | Para la prueba (ítems 10 y 12) se exige:<br>- todo calificado, y cada «No cumple» con su hallazgo;<br>- inicio y fin reales, objeto y personal auditado;<br>- la firma del líder y la de quien atiende (ESM u OPL).<br>El 5W2H llega con el flujo del OPL (fase 6) |
| 19.4 | **Firmas después de reabrir:** el acta de la ronda 2 reutiliza las firmas de la ronda 1. ¿Se exigen firmas nuevas en cada ronda? | Se reutilizan. El acta indica la ronda y que la visita fue reabierta. El coordinador que corrige no puede cambiar las firmas |
| 19.5 | **Inactividad y formularios largos:** el aviso y el cierre a los 10 minutos contaban solo las peticiones al servidor, así que quien diligenciaba el formulario sin guardar perdía la sesión | Escribir o hacer clic (fuera de botones y enlaces) renueva la sesión, como máximo cada 20 s. Sin interacción, el aviso y el cierre siguen igual (Anexo 5) |
| 19.6 | **Hora militar en el formulario:** «Inicio real» y «Fin real» usan el control nativo de fecha y hora. En un equipo configurado en 12 horas se ve a. m./p. m. (igual que en la 18.11) | Se guarda en 24 h. Si la prueba exige verla en 24 h en cualquier equipo, se cambia por un control propio |
| 19.7 | **Destinatarios del acta:** el acta también va a los correos del personal auditado que registra el líder | Se deja así, porque el líder ya ve todo. El número de destinatarios queda en el historial. ¿Se muestran los destinatarios antes de enviar? |
| 19.8 | **Lugar en lista (ítem 12):** el lugar es el punto de la visita y lo fija la agenda | En el formulario se muestra como lista de solo lectura con el punto. Se elige en la agenda, también de una lista |
| 19.9 | **Reportes:**<br>- el detalle y el consolidado por lugar leen el resumen calculado al enviar el acta;<br>- el consolidado por proceso cuenta solo visitas cerradas | Las visitas sin acta salen en el detalle con los resultados vacíos |

### Revisión de los agentes de S3 y S4 (2026-10-21)

Se corrigieron las prioridades 1:
- **Repositorio:** las actas y fotos de las pruebas quedaban en el árbol fuente sin ignorar. Ahora `**/almacen/` está en `.gitignore` y las pruebas usan `target/archivos-prueba`.
- **Firma:** una firma corrupta salía en el acta legal como «(firma no legible)». Ahora la firma se decodifica, se le aplica un tope de dimensiones y se recodifica al registrarla, y el acta falla si no puede leerla.
- **Bitácora:** registraba «visita cerrada» aunque la visita no se cerrara.
- **TXT:** recortaba las celdas a 60 caracteres.
- **Frontend:** se podía enviar el acta con cambios sin guardar.
- **Pruebas:** faltaban las de autorización de archivos, acta fallida con reintento, coordinador que no firma y reapertura por el administrador.

También se corrigieron, entre otras prioridades 2:
- bombas de descompresión en fotos y firmas;
- tope de tamaño antes de leer el archivo, y tipo solo por el contenido: el nombre ya no hace pasar por PDF unos bytes cualquiera;
- el formulario queda en solo lectura mientras el acta se envía;
- orden de bloqueo al cerrar;
- el consolidado por proceso usa solo visitas cerradas, y la DIGSA no ve el equipo;
- un estado inválido en los reportes responde 422;
- las firmas no se borran (V10);
- la descarga usa la extensión del tipo detectado y `no-store`;
- reenvío de la SPA en rutas de tres niveles: recargar `/agenda/visita/12` daba «No existe»;
- aviso al salir con cambios sin guardar;
- mensaje claro cuando el proxy rechaza un archivo por tamaño (413).

Quedan pendientes, sin bloquear:

| # | Pendiente | Cuándo |
|---|---|---|
| 19.10 | Generar el PDF del acta y escribirlo en el almacén fuera de la transacción que bloquea la visita, y descartar el archivo si la transacción se revierte | Fase 5 |
| 19.11 | Subida por streaming: hoy cada archivo se lee completo en memoria (máximo 100 MB por archivo y 120 MB por petición) | Antes de Azure Blob |
| 19.12 | Rendimiento del formulario con unos 350 ítems: memoizar los ítems y abrir solo el componente en uso | Fase 5 |
| 19.13 | Conflicto por ítem si dos pestañas del mismo auditor editan el mismo ítem (hoy gana la última), y revisión en los datos de la visita | Fase 5 |
| 19.14 | Pruebas de concurrencia (dos envíos del acta, dos auditores guardando a la vez), de EXIF con una foto real girada y del contenido del PDF del acta extraído como texto | Fase 5 |
| 19.15 | Con la lista de dominios vacía, los reportes se envían a cualquier correo: exigirla en producción | Antes del despliegue |

## Fase 5 · Solicitudes de visita a necesidad (2026-10-21)

Implementado con las decisiones 9.3, 9.4 y 9.6:
- **9.3:** al cancelar la visita se avisa al solicitante y la solicitud vuelve a pendiente.
- **9.4:** cada solicitante ve solo las suyas; el coordinador ve todas.
- **9.6:** correo a los coordinadores por cada solicitud nueva.

La solicitud fija el tipo, el punto y la fecha del escrito al agendar y también al editar la visita.

| # | Duda | Qué se hizo mientras tanto |
|---|---|---|
| 20.1 | **Datos de pacientes en el motivo:** el correo a los coordinadores trae hasta 600 caracteres del motivo, que puede incluir datos de pacientes | Se envía el motivo recortado. ¿Se envía solo el aviso con el enlace (Ley 1581)? |
| 20.2 | **Escrito más antiguo:** el PHP no deja registrar un escrito de más de 60 días | Se mantiene esa regla |
| 20.3 | **Plazo al agendar:** el formulario no avisa si la fecha elegida pasa los 5 días hábiles | El plazo se ve en la bandeja (con la marca «Vencida») y en las alertas de la agenda |

Quedan pendientes, sin bloquear:

| # | Pendiente | Cuándo |
|---|---|---|
| 20.4 | Vista en tarjetas de la bandeja en el celular (hoy, tabla con desplazamiento lateral) | Fase 8 |
| 20.5 | Historial propio de la solicitud: hoy, al cancelar, la visita que la atendió solo queda en el historial de la visita | Fase 7 |
| 20.6 | Bandeja de salida de correos con reintentos para todos los avisos (hoy SMTP síncrono, con el fallo en la bitácora) | Fase 10 |

## Fase 6 · Flujo del OPL (2026-10-21)

Implementado con las decisiones 7, 8, 11.2, 11.5, 11.7, 11.8, 12.2, 17 y 25:
- **Hallazgos al cierre:** las obligaciones del contrato y los ítems en NC, con el 5W2H del auditor y sus dos firmas.
- **Acta con QR:** el QR lleva al enlace de respuesta, con los plazos de 3 y 5 días hábiles destacados.
- **Código de 6 dígitos:** se envía solo al correo del OPL. Vence en 15 minutos, admite 5 intentos y como máximo salen 5 por hora por visita y 3 por hora por IP.
- **Respuesta del OPL:** acepta u objeta cada hallazgo con soportes y diligencia quién y cómo. Firma el plan una sola vez, sin firma tardía y con el número de documento.
- **Decisión del líder:** solo el auditor líder ratifica o levanta cada objeción. Al levantar el último hallazgo se cierra el plan y se avisa solo al OPL.
- **Tarea diaria:** corre a las 00:15 con candado, aplica la aceptación automática y avisa al líder. Si el plan venció, lo marca «vencido» y envía el reporte formal a la DIGSA y al líder, con consecutivo RD-aaaa-nnnn y el acta adjunta; si el envío sale, el plan queda «reportado a DIGSA». Nunca inicia una multa.

| # | Duda | Qué se hizo mientras tanto |
|---|---|---|
| 21.1 | **Consecutivo del reporte a la DIGSA:** formato y alcance (anual o global; prefijo) | RD-aaaa-nnnn, reinicia cada año |
| 21.2 | **Forma del reporte:** ¿PDF propio o el cuerpo del correo? | Correo con la tabla de hallazgos, su estado y los soportes, más el acta en PDF adjunta y su huella. El contenido queda guardado en `reportes_digsa` |
| 21.3 | **Sin correos de la DIGSA ni del líder** | El reporte queda «fallido», el plan sigue «vencido» y la tarea lo reintenta cada día |
| 21.4 | **Firma con objeciones sin decidir:** ¿puede el OPL firmar el plan mientras el líder no ha decidido una objeción? | Sí se permite: lo objetado no entra al plan. Si el líder lo ratifica después, el OPL completa quién y cómo de ese hallazgo, pero solo en lo vacío y antes del plazo |
| 21.5 | **Aviso de la decisión del líder** | Va solo al OPL, no al punto. Es una deducción de 11.2 y 12.2 |
| 21.6 | **Vencido el plazo del plan** | El enlace responde 410 y el usuario OPL ya no ve la visita en su lista. ¿Debe seguir viendo lo respondido, en solo lectura? |
| 21.7 | **Hasta cuándo dura la alerta crítica de plan vencido** | Mientras el plan no esté «cerrado» (pendiente de la fase 7, seguimiento) |
| 21.8 | **Hora de la tarea diaria** | 00:15 en Bogotá; se configura con `digsa.opl.cron`. El administrador también puede correrla a mano |

Quedan pendientes, sin bloquear:

| # | Pendiente | Cuándo |
|---|---|---|
| 21.9 | Alerta crítica de planes vencidos en el tablero (`planesOplVencidos`) | Fase 9 (riesgo y tablero) |
| 21.10 | Exportar los soportes del OPL en la carpeta de la visita | Fase 10 |
| 21.11 | IP real detrás del proxy de Azure (`server.forward-headers-strategy`) para los topes por IP | Fase 11 |

### Revisión de los agentes de la fase 6 (2026-10-21)

Revisaron el código Java, la base de datos, los fallos silenciosos y la seguridad. Se corrigieron antes del commit:

**Prioridad alta (P1):**
- La firma del plan respondía 200 sin guardar nada cuando el plan ya estaba cerrado. Ahora responde 409.
- El reporte quedaba «reportado a DIGSA» sin ningún correo de la DIGSA, o con su correo rechazado. Ahora queda «fallido» y se reintenta.

**Prioridad media (P2) y menor (P3):**
- **Avisos:**
  - Un aviso que falla ya deja historial y lo advierte en la respuesta.
  - El aviso de «plan cerrado» guarda su resultado.
  - El aviso de aceptación automática se reintenta si falló.
  - Cambiar la respuesta varias veces no inunda el correo del líder.
- **Plan y respuestas:**
  - La aceptación automática se confirma aparte, así no se pierde con el error que la anuncia.
  - Con el plan firmado se llenan solo los campos vacíos, uno por uno.
  - El líder decide solo sobre la visita cerrada y el acta vigente.
  - Una obligación ya calificada no cambia su código ni su prestador.
- **Tarea diaria:**
  - Revalida la visita después de bloquearla.
  - No reenvía un reporte que ya salió.
  - Filtra en SQL lo que ya no tiene trabajo.
- **Código de 6 dígitos:**
  - Topes con candado por visita y por IP, con reserva mientras sale el correo; un envío fallido no consume el tope.
  - El código queda atado a la sesión que lo pidió: otra sesión con el mismo QR no le gasta los intentos.
- **Datos personales:**
  - Correo enmascarado en la columna «usuario» de la bitácora.
  - El detalle ya no devuelve el número de documento de quien firmó.
- **Soportes:** se revisan el tope y el estado antes de leer los archivos.
- **Base de datos (V12):** CHECK de estados y del consecutivo, índice por vencimiento y sin DELETE de más.

| # | Duda | Qué se hizo mientras tanto |
|---|---|---|
| 21.12 | **Habilitación en trámite:** la spec excluye de los hallazgos el ítem NC con subvalidación REPS «SI», pero el formulario no tiene ese campo | Todo NC es hallazgo. ¿Se agrega la subvalidación SI/NO al ítem de habilitación? |
| 21.13 | **Bloqueo del QR por terceros:** quien tenga el acta (por ejemplo, el punto) puede agotar el cupo de 5 códigos por hora de la visita | Se mantienen los topes de la spec. Queda el usuario OPL como alternativa. ¿Se avisa a la interventoría al llegar al tope? |
| 21.14 | **Huella del código:** SHA-256 sin llave, como dice la spec | ¿Se cambia a HMAC con una llave de Key Vault? |

Quedan pendientes, sin bloquear:

| # | Pendiente | Cuándo |
|---|---|---|
| 21.15 | El token viaja en la consulta del GET del enlace: excluirlo de la telemetría de Application Insights y del ingreso | Fase 11 |
| 21.16 | Lista del OPL y detalle con consultas N+1: consulta agregada cuando crezca el volumen | Fase 8 |
| 21.17 | Archivo del acta huérfano si falla la transacción de reserva (no se pierde evidencia; queda un archivo de más) | Fase 10 |
| 21.18 | Fijar `server.tomcat.remoteip.internal-proxies` a la subred de Container Apps y probar que se registra la IP real | Fase 11 |

### Frontend de la fase 6 (2026-10-21)

Construido:
- **Checklist:** las obligaciones del contrato y el 5W2H con sus dos firmas. Un cambio sin guardar en el plan bloquea el acta.
- **Maestros:** la pestaña «Obligaciones del contrato».
- **Pantallas del OPL:**
  - `/opl`: lista y detalle para los usuarios OPL;
  - `/opl-responder`: la página pública del QR, sin sesión. El token sale de la barra de direcciones apenas abre y queda en sessionStorage para poder recargar.
- **Detalle de la visita:** la sección «Respuesta del OPL», con Ratificar y Levantar para el líder.
- **Pruebas e2e:** se configura `digsa.opl.codigos-por-hora-ip` (3 por defecto). Las pruebas lo suben porque todos los navegadores piden el código desde la misma IP local.

| # | Duda | Qué se hizo mientras tanto |
|---|---|---|
| 21.19 | **Catálogo de obligaciones:** ¿editable a mano o solo desde la lectura de la minuta? | Editable por el coordinador mientras llega la lectura de la minuta |
| 21.20 | **Avance (%) del 5W2H:** ¿se edita en la visita o solo en el seguimiento? | No se muestra en la visita: va en 0 y se edita en el seguimiento (fase 7) |
| 21.21 | **Documento del firmante:** la decisión 11.8 habla del QR | También se pide al usuario OPL: es la misma firma del plan |
| 21.22 | **Soportes del OPL desde la visita:** el equipo ve sus nombres, pero no hay descarga | Se agrega la descarga para el equipo en la fase 7 |

Revisión del frontend (React y seguridad). Se corrigió antes del commit:
- **P1, sesión del QR vencida:** pasados 30 minutos sin peticiones, la sesión del QR vencía y la página quedaba sin salida. Ahora reabre el enlace con el mismo token y pide el código en un diálogo, sin perder lo escrito.
- **P2:**
  - No se puede firmar con respuestas sin guardar ni con hallazgos sin responder.
  - La pantalla se refresca tras un 409, 410 o 422.
  - El reenvío del código no admite clics repetidos.

Quedan pendientes, sin bloquear:

| # | Pendiente | Cuándo |
|---|---|---|
| 21.23 | Enlace del acta con fragmento (`#t=`) y apertura por POST, para que el token no llegue a los registros del ingreso. Se suma a 21.15 | Fase 11 |
| 21.24 | E2E de reverificar por inactividad, conservando lo escrito, y e2e de `/opl` con usuarios OPL nacional y regional | Fase 7 |
| 21.25 | Detalles de pantalla: aviso al cerrar que también cuente la firma, carga por botón, parpadeo tras guardar, 5W2H con revisión ante dos editores, tarjetas en el celular, errores 500 visibles en «Respuesta del OPL» y ESLint con react-hooks y jsx-a11y | Fase 8 |

## Fase 7 · Seguimiento al plan de mejoramiento (2026-10-21)

Implementado con las decisiones 6.x, 17, 24, 30 y la 12.1 (con la resolución recomendada):
- **Programación automática:** al cerrar una visita con acciones abiertas, en la misma transacción del cierre, se crea la visita de seguimiento.
  - Tipo «plan», con el mismo punto, hora, equipo activo, componentes y versión del checklist; el reparto se hace de nuevo.
  - Su fecha es la fin más próxima de las acciones abiertas; si ya pasó, el siguiente día hábil.
  - Se notifica de inmediato y se avisa al líder con las acciones por verificar.
  - Reabrir y volver a cerrar no la duplica (índice único parcial).
- **Qué se verifica:** el seguimiento recalifica solo los hallazgos no levantados de la visita de origen. El resumen, los faltantes y el acta cuentan solo eso.
- **Estado inicial:** el plan parte del anterior, con el quién y el cómo del OPL en solo lectura. Los soportes anteriores se descargan en solo lectura.
- **Prórroga:**
  - Correr la fecha fin de una acción abierta exige una justificación (10 caracteres o más).
  - Al enviar el acta queda «solicitada», y pasa a «aprobada» con el coordinador y la DIGSA.
  - Cualquiera de los dos la rechaza con motivo, y cada decisión queda en la bitácora.
- **Máximo de seguimientos (`max_seguimientos`):**
  - no se programa otro;
  - el plan se escala a los coordinadores con copia a la DIGSA;
  - sale el reporte formal «plan incumplido» con consecutivo, y el plan queda «reportado a DIGSA».
- **Cierres:**
  - Un seguimiento sin acciones abiertas cierra el plan del OPL de la visita de origen y avisa solo al OPL.
  - Levantar el último hallazgo cancela el seguimiento pendiente.
- **Recordatorio diario** al líder, una sola vez, dentro de los `recordatorio_seguimiento_habiles` días.
- **«Prestadores con hallazgos»**, con el alcance de cada perfil. El ESM no ve la objeción, el motivo ni los soportes.

| # | Duda | Qué se hizo mientras tanto |
|---|---|---|
| 22.1 | **Fecha que manda con la prórroga «solicitada»** | El siguiente seguimiento se programa con la fecha solicitada. Si se rechaza, el coordinador lo reprograma (se le dice en el mensaje) |
| 22.2 | **Cuándo se reporta el «plan incumplido»** | Solo al llegar al máximo de seguimientos con acciones abiertas, no en cada seguimiento con la acción vencida |
| 22.3 | **La DIGSA decide prórrogas:** es su única escritura; la decisión 20 dice «solo ven» | Se habilitó solo para las prórrogas (pantalla «Prórrogas del plan») |
| 22.4 | **Cierre del plan sin acciones abiertas:** el seguimiento termina sin abiertas | Mismo cierre y mismo aviso solo al OPL que al levantar el último hallazgo |
| 22.5 | **Lo que ve el ESM de un hallazgo objetado** | Lo ve como «vigente», sin la objeción |
| 22.6 | **Reparto del seguimiento:** se hace de nuevo, como dice la spec | El hallazgo lo puede recalificar otro auditor del equipo. ¿Debe ir al mismo auditor que lo encontró? |
| 22.7 | **Seguimiento con el líder inactivo:** si el líder ya no está activo al cerrar, el seguimiento no se programa | La visita igual se cierra. El historial y el mensaje lo dicen, y el coordinador lo programa a mano |
| 22.8 | **Hora del recordatorio** | 06:30 en Bogotá (`digsa.seguimiento.cron`). El administrador también lo corre a mano |

### Revisión de los agentes de la fase 7 (2026-10-21)

Se corrigieron antes del commit:

**Prioridad alta (P1):**
- **Doble aprobación de la prórroga:**
  - Dos aprobaciones al mismo tiempo dejaban la prórroga «solicitada» para siempre. Ahora la fila se bloquea y el estado se calcula en el SQL.
  - Una prórroga cambiada conservaba la aprobación anterior. Ahora se registra solo cuando el acta salió y la visita se cerró. Si cambia, vuelve a «solicitada» sin decisiones; la que ya no está en el acta queda retirada.
- **Quién y cómo del OPL:** nunca llegaban al seguimiento, porque el OPL responde después del cierre. Ahora se leen en vivo y quedan en el plan al enviar el acta del seguimiento.
- **Prórroga de un ítem que ya Cumple:** daba un error 500. Ahora la prórroga se detecta solo en lo que sigue en NC.

**Prioridad media (P2):**
- **Prórrogas:**
  - El administrador no decide a nombre de la coordinación, y quien tiene Coordinador y DIGSA a la vez no decide.
  - Cada lado lo decide una persona distinta (CHECK en la base).
  - El líder no aprueba su propia prórroga.
  - El motivo del rechazo no se copia al historial.
- **Cierre de la visita:**
  - La programación del seguimiento corre en un punto de guardado: si falla, el cierre sigue en pie y queda la advertencia.
  - Al reabrir y cerrar de nuevo, el seguimiento existente ajusta su fecha y su plan, o se cancela si ya no hay acciones abiertas.
- **Tarea diaria:**
  - Retoma el plan incumplido que no se reportó (el aviso era solo en memoria).
  - Reintenta los avisos de cierre del plan.
  - Cada fila va con su propio manejo de errores.
- **Recordatorio:** usa candado.
- **«Prestadores con hallazgos»:**
  - una sola consulta de hallazgos y la siguiente visita en la consulta principal;
  - solo el auditor y la coordinación ven el enlace al checklist.
- **Datos fijos del seguimiento:**
  - no se edita: se reprograma, se reasigna o se cancela;
  - CHECK en la base: tipo «plan».
- **Nueva acción del coordinador:** «Programar seguimiento», para la situación «sin programar».

| # | Duda | Qué se hizo mientras tanto |
|---|---|---|
| 22.9 | **Plan del acta del último seguimiento:** con el plan incumplido, ¿el plan de esa acta queda «reportado a DIGSA» o el OPL todavía puede responderla? | Sigue «pendiente», con el reporte ligado: el OPL responde y firma esa acta |
| 22.10 | **Creador del seguimiento:** la spec dice que lo crea «sistema» | Queda creado por quien envió el acta (la base exige un usuario); el historial dice «sistema» |

Quedan pendientes, sin bloquear:

| # | Pendiente | Cuándo |
|---|---|---|
| 22.11 | Prueba de concurrencia de dos decisiones de prórroga a la vez, y de la reapertura con hallazgos distintos | Fase 8 |
| 22.12 | Descarga de los soportes del OPL para la DIGSA y Sanidad desde «Prestadores con hallazgos» | Fase 10 |
| 22.13 | Métrica o alerta cuando una tarea diaria termina con fallos (hoy queda en el registro de errores) | Fase 11 |

### Frontend de la fase 7 (2026-10-21)

Construido:
- **Checklist del seguimiento:** el aviso de la pantalla, el hallazgo y los soportes anteriores en solo lectura, el quién y el cómo del OPL, la fecha fin anterior y la justificación de la prórroga.
- **Pantalla «Prórrogas del plan»:** filtro por estado y la aprobación o el rechazo de cada lado. El administrador solo la consulta.
- **Pantalla «Prestadores con hallazgos»:** tarjetas en el celular y el botón «Programar seguimiento» del coordinador.

| # | Duda | Qué se hizo mientras tanto |
|---|---|---|
| 22.14 | **Perfil por nombre:** la pantalla decide los botones por el nombre visible del perfil, y si el administrador lo renombra, desaparecen | Se dejó así. En la fase 8 /api/sesion mandará los códigos de los perfiles |
| 22.15 | **Estado de la siguiente visita:** en «Prestadores con hallazgos» se muestra el código (`notificada`) | Pendiente de mostrar el nombre legible (fase 8) |

Revisión del frontend de la fase 7 (React y seguridad). Se corrigió antes del commit:
- **P1, botones de las prórrogas:** dependían del nombre visible del perfil, que el administrador puede renombrar. Ahora el servidor dice por fila si el usuario puede decidir (`puedeDecidir`), lo que también evita mostrar los botones al líder que pidió la prórroga.
- **P2, celular:** «Prórrogas del plan» usa tarjetas en el celular, con los botones a la vista.
- **P3:**
  - La fecha propuesta para programar el seguimiento sale solo de las acciones abiertas y se calcula con el «hoy» de Bogotá.
  - El motivo del rechazo no se pierde con un clic fuera de la ventana.

Quedan pendientes, sin bloquear (fase 8):

| # | Pendiente |
|---|---|
| 22.16 | El aviso de que se pedirá la justificación de la prórroga, en una fila aún sin calificar |
| 22.17 | Mostrar el vencimiento del plazo para objetar en «Prestadores con hallazgos» |
| 22.18 | Botones táctiles de 44 px en las tablas |
| 22.19 | ESLint con react-hooks y jsx-a11y |


## Decisiones del 2026-10-21 y mini-fase de ajustes

Las dudas bloqueantes quedaron resueltas en `docs/decisiones_2026-10-21.md` (D1 a D14).

**Cerradas sin cambios:**
- 17.1: consultar el correo no es un cambio de pantalla.
- 17.2: el mismo usuario no abre sesión en dos equipos (ya hecho).
- 17.11: los dominios son configurables.
- 22.1 y 22.9: se dejan como estaban.
- 22.3: se deja como estaba; la fuente de verdad no regula la aprobación de prórrogas por la DIGSA.

**Implementado en los ajustes:**
- **D4 (18.11 y 19.6):** control de hora militar propio.
- **D5 (21.12):** el ítem de habilitación en «No cumple» puede marcarse «habilitación en trámite». Sigue siendo hallazgo, y el acta dice que queda pendiente aportar el distintivo o las actas del ente habilitador.
- **D6 (22.6):** en el seguimiento, cada parte vuelve al auditor que la calificó.
- **D9 (22.2):** en cada seguimiento, la acción vencida que sigue en «No cumple» se reporta como plan incumplido. La que pidió prórroga no cuenta.
- **D11 (21.4):** el OPL no firma el plan mientras haya objeciones sin decidir.
- **D12 (19.4):** cada ronda del acta se firma de nuevo (V14); las firmas de la ronda anterior se conservan.
- **D14 (16.1 a 16.4):** Dockerfile, docker-compose (aplicación + PostgreSQL 16 + Mailpit de pruebas) y .env.example.

| # | Pendiente | Qué se hizo mientras tanto |
|---|---|---|
| 23.1 | **D13, OCR de la minuta:** es obligatorio | Fase propia. Se necesita una minuta real, o de ejemplo con el mismo formato, para ajustar la extracción. Mientras tanto sigue el catálogo editable |
| 23.2 | **D14, construir la imagen:** este equipo no tiene Docker | Se necesita Docker Desktop o un servidor con Docker, o construirla en el pipeline |
| 23.3 | **Acción vencida con prórroga solicitada (D9):** ¿se reporta si la prórroga se rechaza después? | Hoy no se reporta mientras esté la prórroga. Al rechazarla, el coordinador puede reprogramar; el siguiente seguimiento la verá vencida y la reportará |

## Fase 8 · Propuesta de agenda y cobertura (2026-10-21)

Implementado según `docs/especificaciones/propuesta-agenda`, con las decisiones 13, 32 y 13.4 (año de contrato):
- **Ciclos:** línea base, anual, farmacias alternas y nivel central, con la cobertura del periodo (total, cerradas, agendadas y faltan).
- **Borrador:** se calcula en 4 pasos (qué se visita, auditor líder por asignación o cercanía repartiendo la carga, fecha y borrador), con las reglas del PHP pasadas a `reglas/PropuestaAgenda` y sus 26 pruebas.
- **Ajuste y recálculo:** el coordinador ajusta una fila, que queda fija, y al recalcular se respeta lo fijado.
- **Confirmación:** programa y notifica cada fila una sola vez por el camino de siempre. Una confirmación cortada se puede continuar.
- **Reporte nuevo:** la distribución auditor–punto (13.3) es un reporte más de «Reportes de visitas», para la coordinación y la DIGSA.
- **Avisos de la cobertura:** ciudades principales sin auditor con base en ellas y ESM de dispensación sin auditor asignado (Anexo 3 p.9 y p.13).

| # | Duda | Qué se hizo mientras tanto |
|---|---|---|
| 24.1 | **Componentes de las visitas que crea la propuesta:** Java exige al menos uno y el PHP no tenía | Todos los componentes de la versión vigente que aplican a la unidad del punto, menos C02 (seguimiento): es la auditoría «al 100 %» |
| 24.2 | **Año de contrato (13.4) en las farmacias alternas y el nivel central** | Los tres ciclos anuales usan el año de contrato. La pantalla elige «Año de contrato N (desde – hasta)». La visita inicial cubre el año 1 |
| 24.3 | **Primer día posible** | Regla de la agenda: el día D sirve si su límite de notificación (hábil anterior a D − 15) no ha pasado. Antes era hoy + 15 días calendario |
| 24.4 | **Datos iniciales:** ningún ESM trae auditor asignado, ningún auditor trae municipio base y no hay puntos de nivel central | La propuesta funciona igual, pero mide todo «sin ubicación» hasta que se carguen esos datos en Puntos y auditores |
| 24.5 | **Avisos de las ciudades principales y de los ESM sin auditor asignado:** son nuevos (fuente de verdad), el PHP no los tenía | Se muestran en la cobertura como avisos; no impiden proponer |
| 24.6 | **Confirmación cortada a mitad** | Cada fila se programa en su propia transacción (tomar, volver a comprobar, programar sin notificar) y la notificación sale después. Si algo falla, la fila queda sin resultado y «Continuar la confirmación» la retoma; si falla solo la notificación, la visita queda programada con el botón «Notificar» de la agenda |
| 24.7 | **Fin de la línea base:** el PHP suma 3 meses inclusive (15-ene → 15-abr) | Se dejó como el PHP |

### Revisión de los ajustes (2026-10-22)

**Backend (Java, base de datos y seguridad).** Se corrigieron:
- **P1:**
  - el healthcheck de Docker apuntaba a una ruta que no existe;
  - el reporte de plan incumplido decía una causa falsa y listaba hallazgos de más;
  - la acción vencida se medía contra el envío del acta y no contra el día de la visita;
  - el compose publicaba en la red el modo local.
- **P2 y P3:**
  - **Reportes a la DIGSA:** el reporte de un seguimiento reabierto no se duplica, y se reclama antes de enviarse (estado «enviando»).
  - **Compose:** usuario dueño de la base y rol de la aplicación con permisos mínimos.
  - **Firmas existentes (V14):** quedan en su ronda.
  - **Marca de trámite:** si el campo no llega, se conserva la que había.
- **Duda 23.4 (nueva):** si el líder decide la última objeción después del plazo del plan, el OPL tiene 2 días hábiles más para firmar, y mientras haya objeciones sin decidir el plan no vence. ¿Es correcto?

**Frontend (React).** Sin P1. Se corrigieron:
- la hora pegada en 12 h («2:30 PM»), que se guardaba como de la mañana;
- un posible ciclo de renders del campo de hora.

Quedan pendientes, sin bloquear (fase 11):
- e2e de ida y vuelta de la fecha y hora real;
- aviso junto al campo cuando falta la hora;
- `aria-describedby` en «Firmar el plan»;
- ocultar el aviso de la ronda cuando ya no faltan firmas.

## Fase 9 · Riesgo y tablero (2026-10-22)

Implementado según `docs/especificaciones/riesgo-tablero`, con la decisión 14 y las del 2026-10-20 (14.1 y 14.3):
- **Dashboard de riesgo:** solo ESM de dispensación. Puntaje 0–100 con 5 factores: incumplimiento, recurrencia, intervención inmediata, planes y acciones vencidos, e incidencias de la DIGSA (reemplaza «días sin visita»). Agregado por país y por fuerza, con su factor principal.
- **Pesos y umbrales:** los cambia la coordinación desde el mismo tablero, deben sumar 100 y quedan en la bitácora. En Catálogos se ven pero no se editan.
- **Incidencias de la DIGSA:** las registran y cierran la coordinación y la DIGSA. Suman mientras estén abiertas y sean del último año.
- **Marca «Requiere intervención inmediata del OPL»:** se pone en el ítem u obligación calificado «No cumple» y llega al hallazgo del acta.
- **Dashboard de visitas:** indicadores por estado, tipo, fuerza, unidad y auditor (este último solo para la coordinación), línea base, resultados y exportación.

| # | Duda | Qué se hizo mientras tanto |
|---|---|---|
| 25.1 | **Pesos iniciales 30/20/15/20/15 y umbrales 60/30:** son propuesta AGS, pendiente de aprobación de la DIGSA (14.1) | Se usan así, con el aviso «Propuesta AGS, pendiente de aprobación DIGSA» en el tablero |
| 25.2 | **Redondeo del puntaje:** el spec y el PHP redondean cada factor a entero; Java suma con 2 decimales y redondea el total | Con los pesos iniciales da lo mismo; con pesos editados puede cambiar 1 punto en el borde de un nivel |
| 25.3 | **Faltan en el dashboard:** «sin calificar» por categoría, obligaciones como categoría, «Vacíos de mayor riesgo con No cumple» y el prestador «Sin prestador» (el nivel central cuenta como operador logístico); la exportación no lleva habilitación, componentes ni prestador | La pantalla muestra lo que ya calcula el servicio. Queda para la fase 11 |
| 25.4 | **Tabla de riesgo en celular (360 px):** el spec pide sin desplazamiento horizontal | La tabla se desplaza dentro de su recuadro (la página no). Queda una vista de tarjetas por ESM para la fase 11 |
| 25.5 | **Incidencias visibles para Sanidad y ESM:** ven la descripción y la fuente | Se dejó visible en su alcance (fuerza o punto). Confirmar |
| 25.6 | **Acciones vencidas:** se miden con la fecha fin original del 5W2H, sin la prórroga aprobada | Duda abierta del spec. Se dejó así |
| 25.7 | **Plan vencido en el dashboard:** ahora usa el mismo criterio del riesgo (no cuenta la visita que ya tiene un seguimiento cerrado) | Hecho. Pendiente: que el plan en «reportado a la DIGSA» se cierre cuando el seguimiento deja todo completado |
| 25.8 | **Prueba intermitente `IngresoYSesionApiTest.csrf`:** en una corrida completa la última fila de la bitácora fue la del ingreso y no la del 419 | Pasa sola y pasó en las otras corridas completas. Sin cambio de código; si se repite, ordenar la bitácora de la prueba por id |


## Fase 10 · Exportación y alertas (2026-10-22)

Implementado según `docs/especificaciones/exportacion-alertas`. Los anexos piden que las alertas críticas se generen «en tiempo real o cuasi real» (respuesta P75) y que vayan por correo y además por SMS (P77 b):
- **Bandeja de salida:** el aviso se guarda en la misma transacción del cambio y se despacha al confirmarse, en milisegundos. Un barrido cada 15 s hace de respaldo (reintentos a 1, 5 y 30 min y 2 h; tomas con `SKIP LOCKED`; sin doble envío). El correo crítico pasa primero y el SMS va por un hilo propio.
- **Alertas:** catálogo editable por el administrador y eventos con la latencia de cada envío (p50 y p95 de correos y SMS) en la pantalla «Alertas». Disparadores en el momento del evento: tarea diaria fallida, correo agotado, archivo sin integridad, base o almacén sin respuesta (monitor cada 30 s), bloqueo masivo de cuentas, intervención inmediata al enviar el acta y plan del OPL vencido.
- **SMS:** construido detrás de un puerto (archivo en local, ninguno en pruebas). Sin proveedor, la aplicación no arranca en producción.
- **Exportación completa:** ZIP diferido con un CSV por tabla, LEEME y soportes por visita con huella SHA-256, en una sola foto de la base. Además, ZIP de soportes del OPL para la DIGSA, Sanidad y el administrador.
- **Corregido en el cierre:** el aviso de una solicitud con más de 100 coordinadores daba error 500 (ahora se reparte en lotes de 100 y el registro es atómico); el primer clic en un perfil de Usuarios se perdía tras un teléfono inválido.

| # | Duda | Qué se hizo mientras tanto |
|---|---|---|
| 26.1 | **Proveedor de SMS (P77 b lo exige para las alertas críticas):** sin él la aplicación no arranca con `DIGSA_ENTORNO=produccion` | Construido con los modos `archivo` y `ninguno`. Para la prueba en sitio: contratar el proveedor o desplegar con `DIGSA_ENTORNO=pruebas` y `DIGSA_SMS_MODO=ninguno` (las críticas salen igual por correo) |
| 26.2 | **Correo por ACS (Azure):** no hay geografía Colombia | No se construyó el adaptador. Sigue el SMTP corporativo |
| 26.3 | **Destinatarios iniciales del catálogo:** críticas al Administrador; plan del OPL vencido a la coordinación, solo por correo | Editables por el administrador en «Alertas» |
| 26.4 | **Decisión del líder sobre una objeción:** sigue síncrona (va con el aviso de «plan cerrado») | Los demás avisos informativos (solicitudes, cancelación, recordatorios) ya salen por la bandeja |
| 26.5 | **¿El coordinador descarga los soportes del OPL?** El spec solo nombra a DIGSA y Sanidad | Hoy recibe 403 |
| 26.6 | **¿Solo descarga la exportación quien la pidió, o cualquier administrador?** | Solo quien la pidió; vence a las 24 h |
| 26.7 | **Exportación sin redondeo a 2 decimales y archivos eliminados marcados en el índice** | Así quedó |
| 26.8 | **Retención de la bandeja de salida (Ley 1581)** | Purga diaria de lo terminado con más de 90 días (`bandeja_retencion_dias`) |

## Fase 11 · Pipeline, respaldo, carga inicial y brechas del dashboard (2026-10-22)

Implementado según `docs/especificaciones/despliegue-carga`:
- **Integración continua:** GitHub Actions (principal) y Azure DevOps (equivalente), ambos llaman a `ci/*.sh`. Secretos (gitleaks), backend, frontend, e2e (Chromium en cada cambio; Firefox y WebKit de noche), dependencias e imagen con Trivy, SBOM, publicación etiquetada por versión y commit (nunca `latest`) y prueba de respaldo y restauración.
- **Respaldo y restauración:** `docker/respaldar` y `docker/restaurar` (sh y ps1) con verificación de filas y de huellas de archivos; guía `docs/guia_operacion_servidor.md`.
- **Carga inicial de maestros:** herramienta de línea de comandos dentro de la imagen (`--digsa.carga.*`), plantillas en `herramientas/plantillas/`, simular por defecto, aplicar todo o nada, idempotente; guía `docs/guia_carga_maestros.md`.
- **Dashboard:** «sin calificar» por categoría, obligaciones como categoría, «Sin prestador», vacíos de mayor riesgo; exportación con las cifras de la pantalla; tarjetas por ESM en celular.
- **Imagen:** versión y commit en la imagen y en el log de arranque; healthcheck de readiness.

| # | Duda | Qué se hizo mientras tanto |
|---|---|---|
| 27.1 | **Repositorio y registro de imágenes:** ¿GitHub o Azure DevOps? ¿Pueden alojarse fuera de Colombia? | GitHub Actions como principal; cambiar es trivial porque ambos usan los mismos scripts |
| 27.2 | **Alojamiento del servidor (16.1):** datacenter en Colombia o nube con región en Colombia | La infraestructura como código queda fuera hasta decidirlo. Imagen, compose, respaldo y guía listos |
| 27.3 | **¿Quién opera el servidor, la carga inicial y los respaldos?** TI de AGS o el proveedor | Las guías sirven para cualquiera de los dos |
| 27.4 | **Vigencia del enlace de bienvenida en la carga masiva:** hoy 60 minutos | Se recomienda 72 h; sin parámetro todavía |
| 27.5 | **RPO, RTO y retención de respaldos** | Propuesta: RPO ≤ 24 h, RTO ≤ 8 h, 30 copias diarias y 12 mensuales |
| 27.6 | **La carga inicial no se corre en la base de la prueba en sitio:** la condición 4 exige las tablas del proceso vacías | Advertido en la guía |
| 27.7 | **Lo que necesita Docker no se ha probado:** imagen, prueba de humo, Trivy y la ida y vuelta del respaldo | Se prueba en la primera corrida del pipeline o cuando TI instale Docker |
| 27.8 | **Dashboard:** ¿«Farmacia alterna» en una sola fila o una por farmacia? | Una sola fila. La habilitación en trámite cuenta en los vacíos (D5) |


## Reportes · módulo transversal (sesión de reportes, 2026-10-22)

Especificación en `docs/especificaciones/reportes/spec.md`, plan en `plan.md` y contrato para los demás módulos en `contrato-proveedor.md`. La sesión 1 aprobó el plan y las dudas R.1 a R.5 el 2026-10-22, por delegación del usuario.

**R1 · Motor, contrato, catálogo y filtros:** contrato `ProveedorReporte` y registro por módulo; catálogo por perfil con buscador; filtros de una, varias o todas las opciones, validados contra las opciones del alcance; periodos con presets y agrupación de horas a años; los 4 reportes de visitas en el motor, con el contenido mínimo de la prueba 13.

| # | Duda | Qué se hizo mientras tanto |
|---|---|---|
| R.1 | **Prueba 13 · forma de las filas** | **Aprobado:** el detalle lleva una fila por visita, con los componentes y los hallazgos del acta más reciente en una celda separados por « · ». El consolidado por lugar lleva una fila por lugar y componente; ahí «Hallazgos» es el número de No cumple de ese componente |
| R.2 | **2.10 entre alcances distintos** | **Aprobado:** misma huella entre usuarios con el mismo alcance. Si un perfil no ve una columna (por ejemplo, el auditor), su reporte es otra variante |
| R.3 | **Envío síncrono o por la bandeja** | **Aprobado:** síncrono, con el resultado en pantalla, porque el comité verifica la llegada en el momento. En R4 pasa a varios destinatarios, con el adjunto del almacén |
| R.4 | **Tope del adjunto** | **Aprobado:** 10 MB, configurable (R4) |
| R.5 | **BouncyCastle para el PDF cifrado** | **Aprobado:** `bcprov-jdk18on` 1.78.1 (R2) |
| R.6 | **Un valor de filtro fuera del alcance** | Hoy (R1) la fuerza o la unidad fuera del alcance ya no devuelven vacío en silencio: dan 422. El mensaje es el mismo si no existe o si está fuera del alcance, para no revelar lo que el perfil no ve |
| R.7 | **`FiltroBitacora` toma solo el primer valor de un parámetro repetido** (plataforma, fuera de esta sesión) | El motor escribe en la misma fila de la petición (anotación `reporte_generado`, `reporte_descargado` o `reporte_enviado`) todos los valores de cada filtro. No se tocó `FiltroBitacora` |
| R.8 | **Filas en pantalla** | Hasta 5.000 (`digsa.reportes.filas-pantalla`), con aviso; el archivo trae todas. La pantalla pagina de a 100 |
| R.9 | **Año de contrato** | El preset sale del parámetro `inicio_contrato` (Catálogos › Parámetros) que ya usa la propuesta de agenda; sin él no se ofrece |
| R.10 | **Semana ISO** | Etiqueta `2026-W29`, para no confundirla con el semestre (`2026-S2`) |
| R.11 | **Proceso sin filtro de estado** | El consolidado por proceso cuenta solo las visitas cerradas: el filtro de estado ya no aplica y devuelve 422 «no aplica» |
| R.12 | **Rango por horas** | El rango se elige en días. La agrupación por horas queda para los reportes con fecha y hora (logs, R4) |
| R.13 | **«Por proceso» frente al tablero** | El consolidado por proceso cuenta las respuestas del checklist de las visitas cerradas, como antes (19.9). El tablero y el consolidado por lugar leen el resumen del acta, que en los seguimientos guarda solo los hallazgos verificados. En un seguimiento las cifras pueden diferir. Mientras tanto se deja así y se documenta en el reporte. Propuesta: que la sesión 1 decida si «Por proceso» pasa al resumen |
| R.14 | **Título del menú «Reportes de visitas»** (`Pantallas.REPORTES`, compartido) | No se cambió para no chocar con la sesión de identidad, que edita `Pantallas`. La pantalla ya dice «Reportes». Propuesta: que quien integre `Pantallas` lo cambie a «Reportes» |
| R.15 | **Textos libres del acta en el detalle (Ley 1581)** | El objeto y los hallazgos los redacta el auditor y pueden nombrar personas, y los ven DIGSA, Sanidad y ESM (el detalle es OPERATIVO). Mientras tanto los ven, como el acta que ya reciben. Si no deben verlos, basta con marcar esas columnas con `soloPara` |
| R.16 | **Módulos** | El contrato (`reportes`) quedó aparte del motor (`motorreportes`), para que identidad, auditoría, catálogos, agenda y presupuesto puedan aportar reportes sin ciclos (revisión de Java de R1) |
| R.17 | **Consolidado por lugar: obligaciones y visitas sin componentes** | Además de una fila por componente, lleva la fila «OBL Obligaciones del contrato» (visitas cerradas) y «Sin componente», para que Cumple y No cumple de un lugar cuadren con el detalle. «Hallazgos» es el No cumple de esa fila |
| R.18 | **Detalle con visitas no cerradas** | Los resultados y los hallazgos solo salen de visitas cerradas, con el último acta enviada (o enviada en parte). Una visita reabierta sale sin resultados hasta que se vuelva a cerrar, como en los consolidados y el tablero (revisión de base de datos de R1) |
| R.19 | **Tope del archivo hasta R2** | Los 4 reportes de visitas admiten hasta 200.000 filas por archivo, como los logs; más es 422. En R2 la exportación va en flujo y el tope se revisa |

**R2 · Formatos en flujo, encabezado 2.9 e integridad 2.10:** el archivo se arma así: cursor del proveedor, temporal en disco, archivo en el almacén. Lleva el encabezado completo (periodo, generado, usuario, establecimiento, filtros, fuente, registros, código de verificación, huella de los datos y página). El PDF va cifrado con AES-256 y sin permiso de modificar. Cada generación queda en `reportes_generados` (V50), y se puede verificar por archivo o por código, con regeneración.

| # | Duda | Qué se hizo mientras tanto |
|---|---|---|
| R.20 | **Huella impresa completa o recortada** | La spec proponía imprimir los primeros 16 caracteres. Se imprimen los 64: así se coteja sin entrar al sistema |
| R.21 | **Alcance real del «PDF no editable»** | Los permisos del PDF los respeta el lector (Acrobat, navegadores), no una herramienta que los ignore. La garantía de 2.10 es la huella: un archivo modificado ya no coincide con ningún registro. La contraseña de propietario es aleatoria por archivo y no se guarda en ningún lado |
| R.22 | **Excel y CSV sin protección de edición** | RESPUESTAS l.555 solo la exige en PDF. Llevan código y huella |
| R.23 | **Quién verifica** | Cualquier usuario con sesión verifica por archivo o por código, y ve el usuario de otro enmascarado. Regenerar exige poder generar ese reporte, con el mismo alcance y las mismas columnas |
| R.24 | **El reporte en pantalla también queda registrado** | Con código y huella si caben todas las filas. Con más de 5.000 filas en pantalla no hay huella, y el aviso pide descargar el archivo |
| R.25 | **V51 no se usó** | V50 trae ya las columnas de R3 (estado, ruta, vence y tiempos). Quedan libres V51 a V59 |
| R.26 | **Tope de filas del PDF** | **Corregido en R3 (sesión 1):** la DIGSA exige reportes completos (TAS P12). El PDF ya no tiene tope; tampoco los 200.000 de los reportes de visitas. Lo grande va en segundo plano y la prueba de volumen de R5 mide los 10 minutos. Excel pasa a «Datos 2» al llegar al millón de filas |
| R.27 | **Celdas de Excel de más de 32.767 caracteres** | Salen recortadas con la marca «…[recortado por el límite de Excel; el CSV y el TXT traen el texto completo]». Una lista larga del encabezado va completa en la hoja «Filtros» |
| R.28 | **Tope de verificaciones** | 30 por usuario y minuto (`digsa.reportes.verificaciones-por-minuto`); más es 429 y queda en la bitácora. El conteo vive en la memoria de cada instancia: con varias réplicas, el tope es por réplica |
| R.29 | **Largo del código** | `RPT-aaaammdd-` y 8 caracteres (32⁸ ≈ 10¹² por día): con el tope por minuto, no se puede adivinar enumerando |
| R.30 | **Caracteres fuera de cp1252 en el PDF** | **Corregido en R3 (sesión 1):** el PDF incrusta Liberation Sans (SIL OFL, dependencia `openpdf-fonts-extra` de la misma versión de OpenPDF): ≤, ≥, →, ° y eñes se ven |
| R.31 | **Lo que ve de la verificación quien no generó el reporte** | Título, fecha, periodo, formato, filas, huellas y el usuario con iniciales. No ve los valores de los filtros, que pueden ser búsquedas con datos personales o de otro alcance (Ley 1581) |
| R.32 | **TXT con textos largos** | La alineación llega hasta 60 caracteres por columna: un objeto o un hallazgo largo sale completo y solo descuadra su fila, sin inflar el archivo |
| R.33 | **Retención de `reportes_generados`** | No se purga (es la evidencia para verificar) y guarda el nombre de quien generó. Decidir la retención con la DIGSA junto con la de la bitácora (decisión 7.5) |
| R.34 | **Pruebas intermitentes ajenas a reportes** (cierre de R1 y R2, 2026-10-23) | `SeguimientoApiTest.accionVencidaEsPlanIncumplido` falló una vez en la batería completa y pasa sola (9/9). `riesgo-tablero.spec.ts:149` falla en Firefox al esperar el aviso «Pesos guardados»; en la corrida de R1 había pasado. Ninguna toca código de reportes: se avisó a la sesión 1 |

**R3 · Descargas del día, diferido, limpieza y tiempos (Anexo 5, 2.7):**
- **Cola de descargas:** cada descarga entra a una cola de la instancia, con 4 a la vez y 2 en curso por usuario (429). Si termina en 15 s se entrega en la misma petición; si no, responde 202 con su código y sigue en segundo plano.
- **«Descargas del día»:** pestaña que se consulta cada 5 s mientras algo se genera y avisa cuando queda listo. Cada archivo se vuelve a bajar sin regenerarlo, y solo lo baja quien lo pidió.
- **Limpieza:** cada minuto, con candado entre réplicas, borra los archivos al terminar el día y repone las generaciones interrumpidas.
- **Tiempos (ajuste de la sesión 1):** los plazos de 2.7 (3 minutos para ver, 10 para exportar) **no cortan**: el usuario recibe su reporte completo. Al pasar el 80 % o el plazo se dispara la alerta «reporte lento» (V51) al Administrador, porque 2.7 obliga a informar a la DIGSA y corregir en 8 días. Solo corta un tope técnico de 60 minutos (`digsa.reportes.tope-tecnico-ms`, `statement_timeout`) para consultas colgadas, con 503 y un mensaje claro.

| # | Duda | Qué se hizo mientras tanto |
|---|---|---|
| R.35 | **Espera antes de seguir en segundo plano** | 15 s (`digsa.reportes.espera-sincrona-ms`). Más tiempo deja el navegador esperando. Menos manda a «Descargas del día» reportes que salen al instante |
| R.36 | **La cola vive en la memoria de la instancia** | Al apagarse, la instancia espera 20 s y marca «fallida» lo que tenía en cola o en curso, para que el cupo del usuario no quede ocupado. Si se cae sin apagarse, la limpieza lo repone: lo que lleva generándose más del tope técnico y 5 minutos, y lo que lleva en cola más de lo que tarda la cola entera (cola de 20, 4 a la vez: unas 6 horas con el tope de 60 minutos). Con varias réplicas, cada una atiende sus solicitudes |
| R.37 | **Aviso de diferido listo** | Solo en la aplicación (pestaña y aviso). No por correo: los anexos no lo piden (regla de canales) |
| R.38 | **Reutilizar el archivo del día** | No se reutiliza solo: el usuario ve en «Descargas del día» lo que ya pidió y lo baja de ahí. Detectar «sin cambios en los datos» exigiría una marca por fuente |
| R.39 | **El envío por correo va por la cola** | **Corregido en la revisión de R3:** usa la misma cola y el mismo cupo por usuario que las descargas, y la petición espera el resultado, porque se muestra en pantalla (duda R.3). La generación se corta en cuanto el archivo pasa de 10 MB (422, sin terminar el archivo) y queda en la bitácora como «NO enviado» |
| R.40 | **Tope técnico en pantalla** | 10 minutos (`digsa.reportes.tope-tecnico-ver-ms`), no 60: una petición no queda colgada una hora. Lo grande se descarga en segundo plano. Los plazos de 2.7 siguen solo avisando |
| R.41 | **El tope técnico cubre toda la generación** | Lectura, cinta y escritura del archivo. Si no, la limpieza podía dar por interrumpida una generación viva (un PDF muy grande que se escribe después de una lectura larga). Lo que termina pasado el tope se trata como cortado |
| R.42 | **Archivo vencido que no se pudo borrar** | La limpieza comprueba que el archivo ya no está antes de anotar «archivo borrado». Si no se pudo borrar (p. ej. abierto en Windows), queda en el log como error y se reintenta cada minuto. Falta decidir si, después de varios intentos, debe disparar una alerta al Administrador (R5) |
| R.43 | **Temporales de Excel** | La cinta usa su carpeta propia (`digsa-reportes`, que se vacía al arrancar). Los temporales de POI (SXSSF) siguen en la carpeta común de POI porque esa estrategia es global y la comparte el módulo de exportación. Si el proceso cae mientras escribe, pueden quedar ahí: se documenta para operación (limpiar `poifiles` al arrancar el contenedor) |
| R.44 | **Lectura larga en REPEATABLE READ** | Una generación de hasta 60 minutos mantiene una foto de los datos y una conexión del pool. Con 4 a la vez son 4 conexiones y 4 fotos (frena el autovacuum mientras dura). Revisar `maximumPoolSize` y medir con la prueba de volumen de R5 |

**R4 · Correo, tableros, logs y presupuesto en el motor:**
- **Correo:** hasta 15 destinatarios por envío, en lista o en un texto separado por comas, punto y coma o espacios. Cada uno es válido y de un dominio permitido. Uno que no lo es rechaza el envío completo. Si el servidor de correo rechaza a algunos, la respuesta y la bitácora dicen a quién no llegó, y el aviso sale en amarillo.
- **Nuevos proveedores (aditivos):** dashboard de visitas y de riesgo (tablero), logs de auditoría (auditoria) y los cuatro de presupuesto (autorizado por la sesión 1). Sus pantallas y endpoints siguen igual.

| # | Duda | Qué se hizo mientras tanto |
|---|---|---|
| R.45 | **Filtros de un solo valor en tableros y presupuesto** | Sus servicios filtran por una fuerza, un ESM, una vigencia o un tipo a la vez, y sin cambiar su lógica no admiten varios. En el motor esos filtros son de lista, pero elegir más de uno responde 422 («elija solo uno»). Las fuerzas y modalidades de presupuesto sí admiten varias |
| R.46 | **Presupuesto en memoria** | `ServicioReportesPresupuesto.generar` arma hasta 100.000 filas en memoria y el proveedor las recorre. Pasarle el límite o un consumidor cambia la lógica de presupuesto: queda para la sesión de presupuesto, y lo mide la prueba de volumen de R5 |
| R.47 | **La pantalla de logs sigue con un destinatario** | `ControladorLogs.enviar` no se toca (módulo de auditoría: solo se agregan proveedores). Los logs a varios correos salen desde «Reportes → Logs de auditoría» |
| R.48 | **Búsqueda por texto en logs** | Es `ILIKE '%texto%'`, sin índice, en periodos de hasta 92 días. Cabe hoy en los 3 minutos. Si en la prueba de noviembre pasa de unos 60 s, V53 lleva un índice GIN trigram sobre `descripcion` (encarece cada inserción de la bitácora: no se pone sin medir) |
| R.49 | **Pesos y umbrales del riesgo** | En la descarga de la pantalla van en el encabezado. En el motor, el encabezado es el de 2.9 y no admite líneas propias del proveedor, así que van como filas «Pesos y umbrales» al final |
| R.50 | **Prueba del tope del adjunto** | El corte a 10 MB se prueba en R5 junto con la prueba de volumen: generar más de 10 MB en una prueba de API es lento |

**R5 · Rendimiento, volumen y cierre (2026-10-23):**

Prueba de volumen (`VolumenReportesApiTest`, corre solo con `-Ddigsa.volumen=true`): 1.500.000 visitas sintéticas en un trimestre
(RESPUESTAS l.2213: consultas de hasta tres meses), en el equipo de desarrollo con PostgreSQL embebido. Tiempos de 2.7 (3 minutos para
ver, 10 para exportar):

| Operación | Tiempo | Detalle |
|---|---|---|
| Ver «Consolidado por lugar» | 66 s | 185 filas agregadas |
| Ver «Detalle de visitas» | 2,5 s | primeras 5.000 filas en pantalla |
| Exportar el detalle en CSV | 3 min 8 s | 1.500.000 filas, 252 MB |
| Exportar el detalle en TXT | 3 min 50 s | 488 MB |
| Exportar el detalle en Excel | 4 min 58 s | 139 MB, en dos hojas («Datos 2») |
| Exportar el detalle en PDF | 6 min 45 s | 402 MB, cifrado y con letra incrustada |
| Correo del detalle en CSV | 422 a los 3 min 40 s | corta al pasar de 10 MB |

| # | Duda | Qué se hizo mientras tanto |
|---|---|---|
| R.51 | **Índices de R5** | Con los tiempos de la prueba de volumen no hace falta ninguno: las consultas usan `ix_visitas_fecha`, `ix_visitas_punto` y los de la bitácora. No se crea V53. El único candidato, que depende de lo que se mida en noviembre, es un GIN trigram para el texto de los logs (R.48) |
| R.52 | **El correo corta después de leer** | El tope de 10 MB se aplica al escribir el archivo, y el archivo se escribe cuando termina la lectura (el encabezado lleva la huella de todas las filas). Por eso un reporte enorme tarda la lectura completa (unos 3,5 minutos con 1,5 M filas) en responder 422. Estimar el tamaño antes exigiría contar las filas dos veces |
| R.53 | **Tiempos para el Administrador** | Pestaña «Tiempos» en Reportes, solo para el Administrador: p50, p95 y máximo por reporte, en pantalla y por formato, y cuántas generaciones pasaron el 80 % del plazo, en 1, 7, 30 o 90 días |
| R.54 | **La cinta va comprimida** | El temporal de filas usa Deflater en nivel rápido. A cambio de algo de CPU, un reporte de 2 M filas no ocupa varios GB de disco temporal |
| R.55 | **Anchos de columna del PDF** | Se reparten según la palabra más larga del título y el texto más largo de las primeras 500 filas, entre 4 y 40 caracteres |
| R.56 | **Lo cortado en los tiempos** | Una exportación fallida o cortada por el tope técnico guarda el tiempo que alcanzó a correr y cuenta en p95, máximo y «Fallidas o cortadas». Un reporte en pantalla que llega al tope (10 min) no deja fila, porque se registra al terminar: no aparece en la pestaña, pero sí dispara la alerta «reporte lento». Registrarlo exigiría insertar la fila antes de leer |

## Presupuesto (rama `presupuesto`, 2026-10-22)

Especificación: `docs/especificaciones/presupuesto/spec.md` (Anexo 5 §4.6, Anexo 2 L739 y L741, casos M7 de la prueba en sitio).

**Resueltas por el usuario el 2026-10-22:** vigencia anual con vista «Contrato»; umbrales sobre lo facturado (parámetro); la ejecución del mes reemplaza con versiones; notificación por ESM × modalidad al buzón de la Sanidad de la Fuerza y al de la DIGSA, más la alerta del catálogo.

| # | Duda | Qué se hizo mientras tanto |
|---|---|---|
| P.6 | **Mapa satélite → centralizador:** ¿quién lo entrega? | Columna `puntos.centralizador_id` editable; la ejecución de un satélite sin centralizador se rechaza |
| P.7 | **Alcance del Analista:** en esta rama `Alcance.de` le da `Ninguno` | Consulta nacional de solo lectura en presupuesto; se alinea con la rama de identidad al integrar |
| P.8 | **Quién registra novedades y ejecución** | Coordinador y Administrador; la DIGSA entrega la información y consulta |
| P.9 | **Qué es un «reajuste»** | Ajuste con signo o porcentaje sobre un ámbito, sin neto cero, con soporte obligatorio |
| P.10 | **Disminución por debajo de lo ejecutado** | Se rechaza; la adición y la ejecución nunca se bloquean |
| P.11 | **Tolerancias de la desviación** (10 % exceso, 20 % subejecución) | Parámetros editables |
| P.12 | **Re-notificar un umbral tras una adición** | No: un aviso por vigencia, línea y umbral |
| P.13 | **Modalidades:** el Anexo 5 trae 3 y el OPL factura por 5 (anexo OPL §38) | Catálogo editable; activas las 3 del Anexo 5 |
| P.14 | **Método de proyección** | Lineal, o la cargada por la DIGSA si existe |
| P.15 | **«Precio techo global de consumo mensual»** (Anexo 2 L1126) | Fuera del módulo |
| P.16 | **Fuentes que faltan:** «anexo técnico financiero, módulo presupuesto» (Anexo 2 L741), contrato del OPL y formato del archivo de ejecución de la DIGSA | Formato de carga propio (CSV «;» UTF-8 o Excel) documentado en la pantalla |


### Revisión de los agentes de la F1 de presupuesto (2026-10-22)

Sin P1. Corregidos: FK compuestas para que un movimiento no cruce vigencias; `evento_id` en los umbrales; desviaciones con `linea_id` y `fuerza` (con FK) en vez de una referencia de texto; miles mal agrupados en los montos; comillas sin cerrar, encabezados desconocidos o repetidos y topes de memoria en el CSV; línea sin saldo que resta (falla cerrado); proyección lineal sin meses negativos; empate del reajuste por % hacia la línea de menor id; base y tolerancias que ya no caen al defecto sin aviso.

| # | Pendiente (P2/P3 sin corregir) | Qué se hizo mientras tanto |
|---|---|---|
| P.17 | Vigencia cerrada, periodo dentro de la vigencia y fuerza de la línea = fuerza del ESM solo se garantizan en la aplicación (sin triggers; duda 4.1 de TI) | Validación en el servicio con pruebas de API |
| P.18 | `GRANT USAGE ON ALL SEQUENCES` y el `RETURN` sin aviso del bloque de permisos siguen el patrón de V16 y V17 | Igual que las demás migraciones |
| P.19 | `presupuesto_notificaciones.para` repite los destinatarios de `correos_salida` | Se deja por trazabilidad de la notificación formal; `par_fuerzas.correos` debe tener solo buzones institucionales (Ley 1581) |
| P.20 | La desviación se muestra con 2 decimales y se compara exacta: +10,004 % se ve «10,00 %» y ya es exceso | Así lo pide la spec («se comparan sin redondear») |

### F2 de presupuesto: presupuesto y novedades (2026-10-22)

Hecho: vigencias, modalidades, centralizador de los satélites y parámetros desde la pantalla; asignación inicial por archivo (CSV «;» o .xlsx, simular y aplicar todo o nada, idempotente por huella; M7-1); novedades por formulario (adición, disminución, reajuste por valor o por %, rebalanceo y recomposición) con soporte obligatorio y archivo opcional, saldos bloqueados en orden y antes y después por línea (M7-2); historial paginado y descarga del soporte en el alcance. Pruebas: 12 de API, 99 de Vitest y la e2e en Chromium, Firefox, WebKit, Chrome y Edge.

Revisión de los agentes: P1 corregidos (reajuste por % calculado con los saldos ya bloqueados; Excel medido al descomprimir antes de abrirlo con POI). También: doble asignación inicial simultánea (409), tope de 500 líneas por novedad, HOMIL exige la fuerza en el formulario, columna de vigencia en la vista «Contrato», tope de 5000 líneas con mensaje, nombre del archivo fuera de la observación.

| # | Pendiente | Qué se hizo mientras tanto |
|---|---|---|
| P.21 | La carga masiva hace varias consultas por fila (asegurar la línea, movimientos): con 5000 filas son miles de idas a la base dentro de la transacción | Se deja para la fase de rendimiento (F6) con inserciones por lote |
| P.22 | Un usuario ESM de un satélite no ve nada: los satélites no tienen líneas propias (su ejecución está en la línea del centralizador) | Así, por la spec; confirmar si debe ver la línea de su centralizador |
| P.23 | `ejecutado_al_momento` del movimiento guarda lo facturado aunque la base de los umbrales sea «reconocido» | Lo facturado es lo que limita la disminución (spec) |
| P.24 | Interfaz: Cerrar/Reabrir vigencia y los interruptores de modalidad no piden confirmación ni muestran carga | Pendiente de pulir en F5 |
| P.25 | El `install` del backend publica en el repositorio Maven compartido y las sesiones se pisan | Para las e2e de presupuesto se usa `MAVEN_OPTS="-Dmaven.repo.local=<worktree>/.local/m2 -Dmaven.repo.local.tail=%USERPROFILE%/.m2/repository"` (Maven 3.9): sugerido para las tres sesiones |

### F3 de presupuesto: ejecución, umbrales y notificación formal (2026-10-22)

Hecho: puerto `PuertoEjecucion` (carga de archivo de la DIGSA o del OPL, registro manual y, después, cuentas médicas) con facturado y reconocido por mes; la versión nueva del mes reemplaza a la anterior sin borrarla y el saldo es la suma de las vigentes; la ejecución puede pasar del 100 %. Umbrales del 25, 50, 75 y 100 % sobre lo facturado (parámetro), uno por línea, solo el más alto con aviso; alerta del catálogo (DIGSA y Coordinador) y notificación formal con consecutivo `PRE-AAAA-NNNN` por correo al buzón de la Sanidad de la fuerza y de la DIGSA, sin SMS. Pantallas «Ejecución» y «Notificaciones» con reenvío.

Revisión de los agentes: P1 corregidos (mes con lista de meses de la vigencia, porque `input type="month"` no existe en Firefox ni en Safari; notificación con correo agotado visible como «fallida» y reenviable; buzón de la DIGSA vacío marcado `sin_buzon_digsa` con V43). También: líneas aseguradas en orden fijo (sin interbloqueos), validaciones del puerto iguales a las del registro manual, línea sin fuerza falla cerrado, índice del saldo vigente.

| # | Pendiente | Qué se hizo mientras tanto |
|---|---|---|
| P.26 | La desviación de la fuerza y la nacional suma solo las líneas que reportaron el mes del corte (si no, la primera carga del mes daría subejecución falsa) | Así; confirmar con el usuario |
| P.27 | El reenvío vuelve a mandar a todos los buzones (también a los que ya recibieron) | Se acepta: es una notificación formal y el consecutivo es el mismo |
| P.28 | El puerto de cuentas médicas no tiene idempotencia por referencia de lote | Se agrega cuando exista el módulo de cuentas |
| P.29 | Registrar() hace varias sentencias por corte; consecutivo anual serializa a quienes notifican hasta el commit | Aceptable con el volumen esperado; se mide en F6 |
| P.30 | El historial de versiones muestra el usuario que registró a Sanidad y ESM | Es el registro de la interventoría; confirmar si debe ocultarse |

### F4 de presupuesto: proyección y desviación (2026-10-22)

Hecho: proyección mensual cargada por archivo (simular y aplicar, versiones, advertencia si no suma el asignado contando lo vigente de los demás meses) o lineal (el asignado repartido en los meses de la vigencia, sin guardarse). Alarma de desviación (M7-3) al corte de cada mes registrado, en tres niveles: ESM × modalidad, fuerza y nacional; exceso por encima de la tolerancia de exceso y subejecución por debajo de la de subejecución (parámetros); una por vigencia, corte, nivel y sentido, con la alerta del catálogo y la notificación formal (la nacional a las tres sanidades y a la DIGSA).

Revisión de los agentes: P1 corregidos (bloqueo consultivo por vigencia al empezar toda escritura del presupuesto: novedades, ejecución y proyección, así dos cargas no se bloquean entre sí ni chocan en el índice de la proyección; prueba de concurrencia). También: con base «reconocido», un mes sin conciliar no cuenta como reportado.

| # | Pendiente | Qué se hizo mientras tanto |
|---|---|---|
| P.26 (amplía) | La fuerza y el país suman solo las líneas que reportaron el mes del corte. La primera alarma del mes queda fijada aunque luego lleguen las demás líneas, y una línea que nunca reporta no se suma (la spec dice «suma de sus líneas») | Así; alternativa: evaluar fuerza y país solo cuando reporten todas las líneas con asignado, o en una tarea al cierre del mes. Confirmar |
| P.31 | Al recargar un mes, la alarma anterior no se corrige: si el sentido cambia se crea otra, y si vuelve a la tolerancia la anterior queda | Las alarmas son avisos formales ya enviados; la pantalla muestra todas con su corte |
| P.32 | La proyección acepta la fuente «OPL» | Se deja: la DIGSA dice que la información la entrega ella, pero el OPL puede aportar la proyección |
| P.33 | Rendimiento: dos consultas de toda la vigencia por cada mes cargado, dentro de la transacción | Se mide en F6 |

### F5 de presupuesto: tablero (2026-10-22)

Hecho: pestaña «Tablero» (la primera) como en el mockup: cajas de presupuesto vigente (con el número de adiciones), ejecutado y %, saldo y «ESM sobre la proyección: N de M»; tabla Fuerza × modalidad con % de ejecución, % proyectado al corte y umbral alcanzado; ESM con mayor ejecución con barras, marcas de 25/50/75 % y semáforo; detalle por línea con lo proyectado al corte y la desviación. Filtros de vigencia o vista «Contrato», corte, fuerza y modalidad, siempre en el alcance. Caja «Ejecución presupuestal» en el inicio. Con vigencia, todo se mide al corte; la vista «Contrato» cuenta lo ejecutado a la fecha.

Revisión de los agentes: sin P1. Corregidos: cifras al corte en todo el tablero, filtros visibles aunque falle la consulta, columnas de modalidad con datos y nombre de la fuerza; pruebas del corte, del alcance ESM y Analista y del 403.

| # | Pendiente | Qué se hizo mientras tanto |
|---|---|---|
| P.34 | Faltan en el tablero el filtro por ESM y la elección de varias modalidades (spec L376) | La pestaña «Líneas» filtra por ESM; varias modalidades queda para F6 |
| P.35 | `Proyecciones.alCorte` calcula toda la vigencia aunque el usuario tenga alcance de fuerza o ESM (también al abrir el inicio) | Correcto en cifras; se optimiza en F6 |
| P.36 | La tabla por fuerza muestra el umbral alcanzado (la spec habla de notificado); «Mayor ejecución» muestra el umbral notificado más alto de las líneas del ESM | Se rotula «alcanzado» y «notificado»; confirmar |

### F6 de presupuesto: reportes y rendimiento (2026-10-22)

Hecho: 4 reportes (ejecución por fuerza, ESM y modalidad con totales; mensual frente a la proyección con agrupación por mes, bimestre, trimestre, semestre o año; movimientos con antes y después; umbrales y desviaciones notificados), en pantalla y en xlsx, csv, pdf y txt, con envío por correo y bitácora; filtros de una, varias o todas las fuerzas y modalidades. Rendimiento: operaciones por lote y notificación consolidada por fuerza (V44): una carga de 4.428 filas pasó de 463 s a 5 s.

Revisión de los agentes: P1 corregidos (el perfil ESM no ve el detalle de otros ESM en una notificación consolidada; pruebas de la consolidación y del ESM). También: totales visibles en pantalla aunque el reporte pase de 2.000 filas, detalle consolidado dentro del tope de texto, reporte de alertas por ESM con las consolidadas, descarga con los filtros de la tabla vista.

| # | Pendiente | Qué se hizo mientras tanto |
|---|---|---|
| P.37 | Registrar los 4 reportes como `ProveedorReporte` del módulo de reportes | Alineados al contrato (`DEFINICIONES`); se registran cuando reportes llegue a develop (aviso de la sesión integradora) |
| P.38 | Número de página del PDF | Bloque aislado en `Exportador.pdf`; al integrar se queda la versión de la sesión de reportes («Página N de M») |
| P.39 | El establecimiento admite un solo ESM en los reportes (la spec pide «una, varias o todas» también para ESM) | Fuerza y modalidad ya admiten varias; ESM queda con uno, como el filtro de establecimiento del contrato de reportes |
| P.40 | En el reporte de ejecución, la desviación de los totales suma todas las líneas, mientras la alarma suma solo las que reportaron el mes | Documentado; confirmar junto con P.26 |
| P.41 | Las desviaciones por ESM aún se registran con un INSERT por línea (los umbrales ya van por lote) | Con la consolidación, la carga de 4.428 filas tarda 5 s |

## Usuarios, roles y logs (sesión 2, rama identidad-roles-logs, 2026-10-22)

Decisiones del usuario del 2026-10-22: el MFA por correo **se deja como está** (siempre encendido, sin interruptor); los roles adicionales son **perfil base + subconjunto de sus pantallas**; el **Analista** tiene consulta nacional en visitas (los anexos solo le dan funciones en cuentas médicas). Entregas: E1 contraseñas y sesión, E2 roles adicionales, E3 bitácora con antes y después, E4 guion de la prueba en sitio.

### E1 · Contraseñas y sesión

Hecho:

- **«Cambiar mi contraseña»** en el menú lateral (`POST /api/cuenta/clave`), sin salir al correo (condición 12):
  - la actual errada suma intento fallido y, al quinto, bloquea y cierra la sesión;
  - con el usuario ya bloqueado responde 401 y no cambia nada;
  - la sesión en curso sigue abierta, con identificador y token CSRF nuevos.
- **Contraseña vencida (30 días):**
  - se cambia en el ingreso (paso `cambiar_clave`), sin enlace;
  - si vence con la sesión abierta, la sesión se cierra con «clave_vencida».
- **TIME OUT editable por el Administrador** en Usuarios › Seguridad (Anexo 5, 3.2: «parametrizable y ajustable»):
  - se guarda en `seg_parametros` (V30), aplica en la siguiente petición y queda en la bitácora con el antes y el después;
  - la SPA consulta `GET /api/sesion/estado`, que no renueva la sesión, y adopta el plazo vigente.
- **Desbloquear** (Administrador): atómico; si el usuario no estaba bloqueado no borra los intentos.
- **17.16:**
  - token CSRF nuevo al ingresar;
  - «olvidé mi contraseña» en un hilo aparte, con el mismo tiempo de respuesta exista o no el usuario;
  - un enlace cada 5 minutos por usuario; un enlace que no salió no cuenta.
- **18.17:** el cierre de sesión por el administrador deja una sola fila de salida.
- La especificación `identidad-sesion` ya dice que el segundo ingreso se rechaza (decisión 7.1), no «gana el último».

| N.º | Duda o pendiente | Qué se hizo mientras tanto |
|---|---|---|
| 28.1 | **CSRF de doble envío:** el token se renueva al ingresar y al cambiar la contraseña, pero no está atado a la sesión. Un token viejo junto con su cookie vieja seguiría pasando, aunque un tercero no puede leer la cookie (SameSite=Strict, mismo origen) | Se deja así: es el patrón documentado de Spring para SPA. Atarlo a la sesión exige cambiar el cliente; queda para después de la prueba si TI lo pide |
| 28.2 | **Tope de «olvidé mi contraseña» con varias instancias:** se decide leyendo `token_vence` y escribiendo después; con dos instancias, dos pedidos simultáneos pueden enviar dos enlaces (el segundo anula el primero) | Con una instancia, el hilo único lo serializa. Pendiente: UPDATE condicional si se despliega en más de una instancia |
| 28.3 | **El enlace del QR del OPL** (`opl/ServicioOplPublico`) sigue usando `DIGSA_SESION_MINUTOS`, no el TIME OUT editable | No es una sesión de usuario. Coordinar con la sesión 1 si debe seguir al parámetro |
| 28.4 | **Ramas 409 sin prueba:** el cambio en el ingreso cuando la contraseña dejó de estar vencida entre los dos pasos, y el cambio concurrente de la propia contraseña | El control optimista (`WHERE clave_hash = :anterior`) está; faltan sus pruebas |
| 28.5 | **Restablecer el TIME OUT en las e2e:** el servidor de las e2e es compartido; la prueba lo restablece y falla con un mensaje claro si no puede | Hecho |
| 28.6 | **Coordinación con la exportación (sesión 1):** cada tabla nueva debe ir en `reglas/Exportacion.TABLAS` (lo exige `ExportacionApiTest.tablasContraElEsquema`) | Se agregó `seg_parametros` (V30) con una línea aditiva; las tablas de E2 y E3 se agregarán igual. Avisar a la sesión 1 al integrar |

### E2 · Roles adicionales y Analista

Hecho:

- **Roles adicionales** (Anexo 5, 1.12) en Usuarios › Roles adicionales. Cada rol tiene un perfil base y un subconjunto de sus pantallas, y se asigna en el formulario del usuario.
- **Autorización:** el menú y `@permisos.pantalla` usan las mismas pantallas calculadas en cada petición. El alcance de los datos sale de los perfiles directos más la base de cada rol.
- **Falla cerrado (P1 de la revisión de seguridad):**
  - las autoridades y `perfilesActuales()` llevan **solo los perfiles directos**: un rol no da la identidad de su base en las reglas de los demás módulos (registrar incidencias, decidir prórrogas…);
  - un usuario con roles no tiene perfiles directos y todos sus roles comparten base; si una mezcla queda por reactivar un rol, el rol no suma nada;
  - el Auditor no es base, y las pantallas ocultas (Visita) no se eligen;
  - CHECK en la base: solo bases 2 y 5 a 10.
- **Analista:** consulta nacional.
- **Base de datos:** V31 (`perfiles.base_id`, `perfiles_pantallas` y ids desde 100). `perfiles_pantallas` va en `Exportacion.TABLAS`.

| N.º | Duda o pendiente | Qué se hizo mientras tanto |
|---|---|---|
| 28.7 | **Analista en visitas:** los anexos no le dan funciones en este módulo | Consulta nacional, como la DIGSA, sin solicitar visitas ni descargar los soportes del OPL (decisión del usuario). Si la adenda dice otra cosa, se ajusta con un rol adicional sin programar |
| 28.8 | **Un rol da consulta, no las acciones de su perfil base:** los servicios de agenda, tablero, ejecución y solicitudes deciden por el perfil directo (`perfilesActuales()`, `hasAnyAuthority`). Un rol con base Coordinador y la pantalla Agenda la ve, pero no actúa como coordinador | Así falla cerrado (P1 de la revisión). Para que un rol pueda actuar, cada servicio tendría que recibir los perfiles según la pantalla: coordinar con la sesión 1 si la DIGSA pide un rol con acciones |
| 28.9 | **Usuario cuyo único rol está inactivo:** el formulario no muestra el rol inactivo; para editar al usuario hay que marcarle otro perfil o reactivar el rol | La asignación al rol inactivo se conserva al editar |
| 28.10 | **`AlertaIntervencionApiTest` (módulo ejecución, sesión 1) falla en cada corrida completa (`mvnw verify`) y pasa sola:** espera 5 s el aviso de la bandeja de salida; en la corrida completa la bandeja trae avisos de las pruebas anteriores y la máquina está cargada | **Cerrada:** corregida en develop (fe9c98e, el aviso puede salir en varios lotes); llegó con el merge de develop |

### E3 · Bitácora con antes y después

Hecho:

- **Antes y después completos** (V32, columna `cambios` jsonb) además del resumen de la descripción. Los datos personales quedan ocultos.
- **Logs:**
  - filtros por tipo y por módulo;
  - botón «Ver» con la tabla campo / antes / después;
  - columna de cambios en Excel y CSV.
- **Registro estructurado** (`registrarCambios`) en identidad: usuario editado, estado del usuario, perfil renombrado, rol editado y TIME OUT.

| N.º | Duda o pendiente | Qué se hizo mientras tanto |
|---|---|---|
| 28.11 | **Otros módulos** (agenda, alertas, catálogos, maestros, ejecución, OPL, hallazgos) siguen con el antes y el después solo en el texto de la descripción (`Bitacora.cambios`), cortado a 40 caracteres | Coordinar con la sesión 1: basta cambiar `bitacora.registrar(tipo, desc + cambios(...))` por `bitacora.registrarCambios(tipo, desc, antes, despues, sinValor, …)` |
| 28.12 | **Particiones de la bitácora (17.17):** V1 creó particiones mensuales hasta enero de 2031 (el contrato termina a más tardar en julio de 2030) y una partición por defecto | Cubren todo el contrato; no se agregó tarea. Si el contrato se prorroga, crear las particiones en una migración nueva |

### E5 · E2E aisladas entre specs y entre sesiones

| N.º | Duda o pendiente | Qué se hizo mientras tanto |
|---|---|---|
| 28.13 | **E2E que se bloquean entre sí:** con la sesión única (decisión 7.1), una sesión de `admin.e2e` que queda abierta bloquea un minuto el ingreso de las specs siguientes | Las specs de usuarios, roles y logs usan un administrador propio (`ingresarComoAdminPropio` en `apoyo.ts`) y cierran siempre la sesión (`afterEach`/`finally`) |
| 28.14 | **Entorno compartido entre sesiones:** <br>- la limpieza versionada mata lo que esté en 8080/54329, aunque sea de otra sesión; <br>- el servidor de las e2e toma el módulo `reglas` del `~/.m2` compartido; <br>- los puertos chocaban entre sesiones | La sesión 1 lo corrige en develop: `DIGSA_E2E_PUERTO`/`DIGSA_E2E_PGPUERTO`, limpieza solo de esos puertos y `MAVEN_OPTS` heredado. Mientras tanto, este worktree corre con una configuración local no versionada (`e2e/.local`): puertos 18180/54349, repositorio Maven propio y limpieza propia |
| 28.15 | **`riesgo-tablero` (sesión 1) en Firefox:** la notificación «Pesos guardados» a veces tarda más de los 10 s de espera en la corrida completa | Pasa sola (6/6). Sin cambio (no es de este módulo); avisar a la sesión 1 |

### AV1 · Mapas georreferenciados (sesión de ajustes de visitas)

Hecho: pantalla «Mapas de visitas» (`MAPAS`, mismos perfiles y alcance que el dashboard) con dos pestañas:

- **Visitas por departamento:** un punto por visita del filtro con el color de su estado: programada (programada, notificada y reprogramada), en ejecución y ejecutada (cerrada). Las canceladas no salen.
- **Mapa de control (cobertura de puntos):** por punto activo del alcance: visitado en el periodo, con visita por ejecutar, sin visita en el periodo (visitado antes) o nunca visitado. Decisión del usuario del 2026-10-26: el mapa de control es solo la capa de cobertura.

| N.º | Duda o pendiente | Qué se hizo mientras tanto |
|---|---|---|
| AV.1 | **Mapa base:** ¿tiles (OpenStreetMap, Google) o mapa propio? | SVG propio con los límites departamentales del DANE simplificados (`frontend/src/shared/colombia-departamentos.json`, 54 KB). Sin servicios externos ni cambios de CSP y sin dependencias nuevas; se carga solo al abrir la pantalla. San Andrés y Providencia van en un recuadro |
| AV.2 | **Ubicación de cada punto:** los puntos no tienen coordenadas propias | Latitud y longitud DANE del municipio del punto (`par_municipios`). Las marcas del mismo municipio se reparten en espiral. Lo que no tiene municipio con coordenadas se cuenta en «sin ubicación» |
| AV.3 | **Población de la cobertura** | Puntos activos del alcance, con los filtros de fuerza y unidad, **sin suministro ni satélite** (decisión 16.1). Fechas, tipo y auditor filtran las visitas que cuentan. Sin fechas, el periodo es todo el contrato |
| AV.4 | **Datos personales** | Los mapas no envían el líder ni el equipo. El filtro por auditor sigue siendo solo de la coordinación (como en el dashboard) |
| AV.5 | **Opciones de filtro** | `/api/mapas/opciones` devuelve las mismas opciones del dashboard con el permiso de `MAPAS`, porque un rol puede tener los mapas sin el dashboard |
| AV.6 | **Mapas v2: capitales** con «el municipio DANE terminado en 001» | En Cundinamarca el 25001 es Agua de Dios; su capital es Bogotá (11001), que ya está. Se excluye el 25001 y quedan 32 capitales. Vienen de `par_municipios` en `/api/mapas/opciones` |
| AV.7 | **Mapas v2: mapa político** | Generado con `frontend/scripts/generar-mapa-base.mjs` (DANE al 2,5 % —al 1,2 % Pereira quedaba fuera de Risaralda— + Natural Earth 1:50m, dominio público; ambos citados en el JSON, 90 KB). Colores por coloreado de grafo (5 fondos LUME); la vecindad se mide por cercanía (3 km) porque el DANE deja fuera los cauces del Magdalena y el Cauca. Los cauces se ven como franjas finas grises. Etiquetas en el polo de inaccesibilidad; Atlántico, Bogotá, Quindío y Risaralda con línea guía, que desaparece desde 3 aumentos. Chocó, Boyacá, Cundinamarca y Valle con posición manual; Cartagena, Cúcuta y Cali con nombre corto. Los nombres que no caben en su departamento se ocultan y aparecen al acercar. El mapa se ve en claro también en tema oscuro, como un mapa impreso |
| AV.8 | **Mapas v2: interacción** | Cualquier departamento (con o sin datos) se puede elegir: lo acerca y filtra el detalle. La rueda solo captura el desplazamiento de la página si se acerca o si ya hay zoom. Coropleta en gris (para no tapar las marcas de color): visitas totales por departamento (5 intervalos iguales) en el mapa de visitas y % de puntos visitados (cortes de 20 %) en el de control |

## Interfaz (sesión de interfaz, rama `interfaz`, 2026-10-23)

Auditoría U0 en `docs/auditoria_interfaz_U0.md`. Numeración propia (UI-n) para no chocar con las de las sesiones paralelas.

| N.º | Duda | Qué se hizo mientras tanto |
|---|---|---|
| UI-1 | **Opera (Anexo 5 num. 1.18, L318):** no está instalado en el equipo de desarrollo, así que no se puede probar como canal de Playwright. Opera usa el motor Chromium (Blink), el mismo de Chrome y Edge | Se documenta la equivalencia de motor. Recomendado: instalar Opera en el equipo de pruebas (o en el de la prueba en sitio) y correr la suite con `DIGSA_E2E_CANALES=opera` si Playwright lo admite, o un recorrido manual grabado |
| UI-2 | **«Dos decimales» en conteos (2.4):** ¿un conteo (número de visitas, hallazgos, registros) se muestra «12» o «12,00»? | Recomendado: montos, porcentajes, puntajes, promedios y tiempos siempre con 2 decimales («72,00»); conteos enteros sin decimales («12»), sin separador de miles en ningún caso. La utilidad de formato separa `cifra()` de `entero()` para poder cambiarlo en un solo lugar |
| UI-3 | **Formato de fecha en pantalla:** hoy las pantallas muestran `2026-10-23` (ISO); el mockup aprobado usa `23/10/2026` | Recomendado: `dd/mm/aaaa` y `dd/mm/aaaa HH:mm` en pantalla (como el mockup), ISO solo en archivos exportados. Las e2e que leen fechas ISO en pantalla se actualizan en el mismo commit |
| UI-4 | **Tipografía del mockup (IBM Plex Sans y Mono):** el mockup la carga de Google Fonts; la aplicación no debe depender de un CDN externo (alojamiento en Colombia, prueba grabada sin cortes) | Recomendado: alojar los archivos `woff2` en la aplicación con el paquete `@fontsource/ibm-plex-sans` y `@fontsource/ibm-plex-mono` (versión exacta, licencia SIL OFL 1.1, solo latín 400/500/600). Si no se aprueba, se queda la fuente del sistema con los demás tokens del mockup |
| UI-5 | **Condición 12 (L1008), «cambios de pantalla»:** evidencias, actas anteriores y el escrito de la solicitud se abren hoy en otra pestaña (`target="_blank"`) | Recomendado: visor dentro de la aplicación (modal con el PDF o la imagen) y un enlace de descarga; ninguna acción de la prueba abre otra pestaña |
| UI-6 | **Errores del servidor junto al campo:** el 422 trae solo `{"mensaje"}` sin el nombre del campo (`ManejadorErrores`), así que la interfaz no sabe a qué campo pertenece | Mientras tanto: las validaciones del cliente marcan el campo y el error del servidor va al resumen persistente del formulario (con `role="alert"`). Recomendado a la sesión 1: agregar `campo` opcional al cuerpo del 422 (compatible hacia atrás) |
| UI-7 | **Grupos del menú:** el servidor manda 3 grupos (Visitas, Presupuesto, Configuración); el mockup tiene 4 (General, Auditoría, Información, Administración) con ícono y numeral | Se mapean en el frontend por `ruta` (grupo visible, ícono y numeral), sin tocar `Pantallas.java` ni los títulos que usan las e2e |
| UI-8 | **Aviso sonoro (2.4):** ¿activado por omisión? | Recomendado: activado por omisión, corto y suave, con interruptor en la cabecera recordado en el navegador. Nunca es el único aviso: siempre va con el visual |
| UI-9 | **Nombre de la aplicación:** «Suite DIGSA» en la cabecera, el ingreso y la pestaña; el mockup usa «Interventoría Integral DIGSA» con el subtítulo «Sistema de información de interventoría · cuentas médicas» | Recomendado: el del mockup. Ninguna e2e usa el texto |
| UI-10 | **Tablas en celular:** ¿basta el desplazamiento horizontal dentro de la tabla o todas deben pasar a tarjetas? | Tarjetas en las pantallas que se usan en campo o en la prueba (OPL, agenda, calendario, seguimiento, solicitudes, usuarios, logs, detalle de visita, presupuesto, dashboard); las de administración (catálogos, maestros) mantienen el desplazamiento interno |
| UI-11 | **Campos de fecha:** el `type="date"` nativo (20 campos) se muestra según la configuración regional del navegador (en un equipo en inglés, mm/dd/aaaa) | Se mantiene el nativo (no acepta letras, lo que pide el ítem 12, y las e2e lo usan); se fija `lang="es-CO"` en la página. Cambiar a `DateInput` de Mantine queda como opción si el Comité lo pide |
| UI-12 | **Solicitudes, 3.7:** «Agendar» y «Anular» se muestran con `coordinador` de la respuesta, pero el servidor exige la pantalla *agenda*; un rol adicional sin *agenda* recibe 403 | Se ocultan también cuando falta la pantalla *agenda* (no cambia permisos: alinea la interfaz con el servidor) |

### Decisiones de la sesión 1 sobre UI-1 a UI-12 (2026-10-23)

| N.º | Decisión | Qué se aplica |
|---|---|---|
| UI-1 | Va al Excel para TI | Se documenta que Opera usa el motor Chromium; si aparece instalado se agrega como canal |
| UI-2 | Va al Excel para el usuario | Mientras tanto: montos, porcentajes y puntajes con 2 decimales y coma; conteos enteros; nunca separador de miles. Centralizado en `shared/formato.ts` (una línea para cambiarlo) |
| UI-3 | Aprobada | `dd/mm/aaaa` en pantalla; ISO en archivos. Las e2e se ajustan en el mismo commit |
| UI-4 | Aprobada | `@fontsource/ibm-plex-sans` y `@fontsource/ibm-plex-mono`, versión exacta, OFL 1.1, solo latín 400/500/600, sin CDN |
| UI-5 | Aprobada | Visor en modal más enlace de descarga (condición 12, L1008) |
| UI-6 | Se hace en la rama `interfaz` | `campo` opcional y compatible en `ManejadorErrores`, con prueba JUnit; sin cambiar mensajes ni códigos |
| UI-7 | Aprobada | Mapeo de grupos, íconos y numerales en el frontend, sin tocar `Pantallas.java` |
| UI-8 | Aprobada | Sonido activado por omisión, interruptor en la cabecera recordado por navegador (con try/catch), siempre junto al aviso visual |
| UI-9 | Aprobada | «Interventoría Integral DIGSA» en la cabecera; el título del documento queda «Suite DIGSA · <pantalla>» |
| UI-10, UI-11, UI-12 | Aprobadas como se propusieron | — |

Condiciones: antes de cada commit, suite e2e completa en Chromium, Firefox, WebKit, Chrome y Edge; revisar los `getByRole('heading')` al poner `h1`; el texto de los errores del servidor que buscan las e2e sigue visible donde lo buscan; la división por rutas espera a la integración de reportes y analítica.

### U1 · Sistema visual (2026-10-23)

| N.º | Pendiente o hallazgo | Qué se hizo |
|---|---|---|
| UI-13 | **Niveles de encabezado:** con el h1 por página, los subtítulos de sección siguen en `Title order={4|5}` (saltan h2 y h3). Revisión de a11y: P2 | Pasa a U4 (`order={2} size="h5"` sin cambio visual), pantalla por pantalla |
| UI-14 | La cabecera abre con un h4 «Suite DIGSA» antes del h1 de la página | Pasa a U3 (cabecera nueva, decisión UI-9) |
| UI-15 | **E2E `sesion.spec` en Edge:** el aviso de inactividad no salía porque la consulta del tablero de presupuesto del inicio respondía después de adelantar el reloj y reiniciaba el plazo (carrera de la prueba, visible porque U1 carga fuentes) | La prueba espera la red quieta antes de `clock.fastForward` (2 pruebas). Pasa en los 5 motores |
| UI-16 | **E2E intermitente en Firefox:** «contraseña inicial y cierre por el administrador» a veces no ve el aviso «Usuario creado…» en 10 s (el servidor tarda; el aviso aparece después) | Sin cambio: pasa sola (2/2) y en la corrida completa. Mismo tipo que 28.15 |
| UI-17 | El frontend no tiene ESLint (`react-hooks`, `jsx-a11y`) | Sin cambio (sería dependencia nueva); se propone a la sesión 1 |

### U2 · Formato y alertas de validación (2026-10-23)

| N.º | Duda o pendiente | Qué se hizo mientras tanto |
|---|---|---|
| UI-18 | **Reportes (sesión de reportes) aún fuera del estándar 2.4:** «Enviar a» sin validar y con el botón deshabilitado sin explicación, errores de envío y descarga en toasts, Desde/Hasta sin validar, `celda()` con 1 decimal (`toFixed(1)`). Revisión de a11y: P1 | No se tocó (`Reportes.tsx` es de otra sesión). Al integrar: `useValidacion` + `cifra()` de `shared/formato.ts`; la prueba de formato lo excluye hasta entonces |
| UI-19 | Latencias en milisegundos con 2 decimales («42,40 ms»): se aplicó la regla de cifras a los tiempos | Si se prefieren enteros para los ms, es una línea en `shared/alertas.ts` |
| UI-20 | Seguimiento: «Iniciada» muestra solo la hora (el servidor manda `LocalTime`); una visita iniciada otro día no dice la fecha | Sin cambio (requiere el servidor) |
| UI-21 | Propuesta y Usuarios deducen el campo del error por el texto del mensaje del servidor (frágil). El 422 de validación ya trae `campo` (UI-6), pero los `ErrorNegocio` no | Se propone a la sesión 1 agregar `campo` a los `datos` de los `ErrorNegocio` de campo |
| UI-22 | Validaciones del cliente que se dejaron al servidor porque una e2e espera el mensaje del servidor: teléfono no móvil en Usuarios (alertas.spec:104) y nombre de alerta de 3 a 80 (alertas.spec:35) | El mensaje del servidor queda persistente en el resumen y el campo marcado |
| UI-23 | Filtros con rango invertido: Dashboard y Reportes de presupuesto bloquean ver y descargar (como ya hacía el Dashboard); Logs no, porque el servidor intercambia las fechas | Sin cambio de reglas del servidor |
| UI-24 | Botones que siguen deshabilitados porque las e2e comprueban `toBeDisabled` (registrar novedad, aplicar carga, guardar pesos, cerrar incidencia, enviar acta, firmar plan OPL, código OPL): explican el motivo con texto enlazado (`aria-describedby`), pero no avisan con sonido al intentar | Propuesta: pasar a `aria-disabled` + validación al pulsar, ajustando esas e2e, en U4 |
| UI-25 | **E2E intermitentes con el equipo cargado** (otras sesiones corren sus e2e a la vez): en Firefox, crear un usuario o un rol a veces tarda más de 10 s en mostrar el aviso; en WebKit, el envío de logs por correo | Las 5 aserciones de creación de usuario o rol pasan a 30 s (como `riesgo-tablero.spec`); todas pasan solas |
| UI-26 | Plan 5W2H: después de un guardado parcial que funcionó, los campos que faltan para el acta quedan en rojo (antes del primer intento se muestran en gris) | Se deja así: es lo que falta para enviar el acta |
| UI-27 | P3 de a11y: (a) con solo la firma faltante el foco iba al botón «Borrar» del panel y no al lienzo (`PanelFirma` pone `tabIndex=-1`, que `primerCampoConError` excluía); (b) el `Checkbox.Group` de Roles adicionales usa `error` sin `conTexto` (hoy el servidor no lo marca) | (a) corregido en U2: un campo marcado con `tabindex=-1` se enfoca él mismo (prueba en `validacion.test.tsx`). (b) en U3 |
| UI-29 | **E2E colgada en `presupuesto.spec.ts:46`** (dos corridas de U2): la prueba buscaba un año de vigencia libre con un `while` sobre 2030–2099; la base local de las e2e (`.local/pgdata`) conserva las vigencias de cada corrida y ya tenía los 70 años ocupados, así que el ciclo no terminaba y bloqueaba el proceso de Node (ni el timeout de Playwright saltaba). No era de U2 | `anioLibre()` recorre 2030–2100 una sola vez y, si no queda año, falla con el motivo (5 sitios del spec). La base vieja quedó en `.local/pgdata.bak-20261027`. Las sesiones con base local persistente tienen el mismo límite (81 años) |
| UI-30 | **Hora como campo de texto (ítem 12 de la prueba en sitio):** el riesgo es que el evaluador pida escribir una hora inválida y lo vea como campo libre | `CampoHora24` pasa a dos listas nativas (hora 00 a 23, minutos 00 a 59, más «--»): no se puede escribir ni elegir una hora fuera de rango y funciona igual con teclado, lector de pantalla y celular. Al elegir la hora sin minutos quedan en 00; minutos sin hora no avisan una hora (el formulario dice «falta la hora»). Nombres accesibles «<etiqueta>: hora» y «<etiqueta>: minutos»; las e2e usan `elegirHora()` |
| UI-28 | Revisión de U2 (react-reviewer, sin P1): P3 que se dejan para después: el foco que el resumen toma al fallar el cierre de una alerta o incidencia se lo devuelve el `Modal` al botón de la fila (`returnFocus`); el soporte de la novedad de presupuesto se marca en rojo desde la primera letra (debe ser al salir del campo) | Pasan a U4 (Alertas, Riesgo y Presupuesto). Corregidos en U2: `hora()` convierte a Bogotá los instantes con zona; la descarga de reportes de presupuesto valida lo consultado; el interruptor de sonido se sincroniza entre pestañas y el audio se prepara también con `touchend` y `click` (tabletas) |


### U3 · Navegación y responsive (2026-10-27)

| N.º | Duda o pendiente | Qué se hizo |
|---|---|---|
| UI-31 | **Visor de documentos (UI-5) y la política de seguridad:** la API no se puede enmarcar (`frame-ancestors 'none'`, `X-Frame-Options: DENY`) y `frame-src` solo admitía el captcha | El visor pide el archivo con la sesión de la página y lo muestra desde un `blob:` (imagen o PDF; lo demás solo se descarga). Se agregó `blob:` a `frame-src` en `ConfiguracionSeguridad`; `script-src`, `object-src` y `worker-src` quedan igual. Condiciones de la integradora (riesgo de XSS almacenado: un `blob:` hereda el origen de la aplicación): (1) lista blanca del cliente, solo `application/pdf`, `image/png` e `image/jpeg`, y el blob se crea con ese tipo y no con el de la respuesta; HTML, SVG, HEIC, video y lo demás se descargan como `application/octet-stream`; (2) **el iframe del PDF va sin `sandbox`**: Chrome y Edge (los instalados en el equipo) no dibujan un PDF en un iframe con sandbox, ni con un solo permiso (`allow-scripts`, `allow-same-origin` o `allow-downloads`; probado con capturas el 2026-10-27), y la única combinación que lo dibuja (`allow-scripts allow-same-origin`) anula el sandbox. La protección es el tipo forzado: el navegador lo abre con su visor de PDF, nunca como HTML. Las imágenes van en `<img>`, que no ejecuta scripts; (3) solo cambia `frame-src`; (4) `VisorDocumento.test.tsx` comprueba que un `.html` o un `.svg` no se pinta (ni iframe ni imagen, solo «Descargar»). Ninguna acción abre otra pestaña (e2e en `solicitudes.spec`) |
| UI-32 | **Pestañas de Presupuesto en celular:** el plan decía «selector compacto», pero `presupuesto.spec` usa las pestañas a 360 px y el teclado las recorre con flechas | Se mantienen las pestañas con desplazamiento horizontal dentro de su barra (la página no se desborda). Un selector compacto queda como opción si el Comité lo pide |
| UI-33 | **Tablas en tarjetas:** en lugar de duplicar el marcado (tabla y tarjetas, con `data-testid` repetidos), `TablaAdaptable` apila la misma tabla por CSS por debajo de 48em, con el nombre de la columna sobre cada valor y roles explícitos | Aplicado a OPL, agenda y calendario (`TablaVisitas`), seguimiento, solicitudes, usuarios, logs, detalle de visita, presupuesto (líneas e historial), ejecución y dashboard. Catálogos y Maestros siguen con desplazamiento interno (UI-10) |
| UI-34 | **Inicio:** las tarjetas de módulos del «Panel de interventoría» son botones y no enlaces, para que el menú lateral conserve los únicos enlaces con el nombre de cada pantalla (las e2e los buscan por nombre) | — |

### U3.5 · Design system LUME (2026-10-27)

Encargo del usuario: adoptar el design system LUME (`referencias_interfaz/LUME_design_system.md`) en lugar de los tokens del mockup de U1. Se mantiene Mantine 7 (decisión de la integradora): tokens `--lume-*` en `frontend/src/lume.css` (única fuente; `shared/tema.ts` repite los valores y una prueba comprueba que coinciden), tema de Mantine y estilos por componente, y shell nuevo en `shared/Plantilla.tsx`.

| N.º | Duda o pendiente | Qué se hizo |
|---|---|---|
| UI-35 | **Contraste AA con la paleta LUME:** 4 pares no llegan a AA: títulos de sección del sidebar (#64748B sobre #1E293B, 3,07), texto apagado sobre --lume-bg (4,40), insignia de éxito (#15803D sobre #DCEFE1, 4,17) y hover del botón outline (#2563EB sobre #DBEAFE, 4,24). Además el borde --lume-border de los campos no llega a 3:1 (WCAG 1.4.11) | Con colores de la misma paleta (aprobado por la integradora): títulos de sección en #94A3B8 (el del subtítulo del sidebar, 5,71); `dimmed` en #475569 (--lume-neutral-text); texto de la insignia de éxito en --lume-text (el punto y el fondo siguen verdes); hover del outline con texto --lume-blue (5,49); borde de los campos en #64748B (--lume-text-muted, 4,76). Lo comprueba `tema.test.ts` |
| UI-36 | **Fuente Inter:** la especificación pide «Inter», «Segoe UI», system-ui | Como en UI-4 (sin CDN): `@fontsource/inter` 5.3.0 exacta (OFL 1.1, solo latín 400/500/600/700) en lugar de IBM Plex Sans y Mono, que se quitan. Las cifras usan números tabulares de la misma familia |
| UI-37 | **Variantes de estado:** LUME tiene 5 (success, warning, info, neutral, danger) y la agenda tiene 6 estados | `orange` y `yellow` son la misma variante warning: «reprogramada» y «en ejecución» se distinguen por el texto de la insignia, no solo por el color |
| UI-38 | **Lo que la especificación trae y la aplicación no tiene:** buscador del header, dock de modales minimizados, control segmentado por rol, filtros con checklist en la cabecera de columna | No se inventan (instrucción del encargo). La campana va a Alertas solo si el perfil la tiene. Se conservan la hora militar y la cuenta regresiva de inactividad (funciones reales de U3), más discretas |
| UI-39 | **Menú en celular:** la especificación colapsa el sidebar por debajo de 900 px | Entre 48em y 900 px el sidebar colapsa solo a 68 px (íconos con nombre accesible y ayuda al pasar); por debajo de 48em es un panel que se abre con «Menú» (las e2e y el ítem de 360 px lo usan). El botón de colapsar recuerda la elección en el navegador |
| UI-40 | **Montos en pesos COP (regla 5 de LUME)** frente al Anexo 5 num. 2.4 (dos decimales con coma, sin separador de miles) | Manda el anexo: se mantiene `monto()` de `shared/formato.ts` sin separador de miles ni signo |
| UI-41 | Recorrido de capturas U3.5: Reportes (de la sesión de reportes) desborda 15 px a 360 px y no tiene h1; Exportación desborda 3 px; Ingreso y Solicitudes tienen un botón sin nombre accesible | Pasan a U4 (Reportes, después de traer develop) |
| UI-42 | **E2E del aviso de inactividad intermitente en la suite completa** (Chromium, Firefox y WebKit; pasa sola): el reloj falso de Playwright solo corre en el navegador, y el aviso se sincroniza cada 60 s con /api/sesion/estado, que ve pasar el tiempo real; con el equipo cargado esa sincronización caía dentro del adelanto y la cuenta volvía a empezar (o quedaba detenida). No es un defecto de la aplicación: el servidor manda | Las dos pruebas del aviso (`sesion.spec`) esperan en tiempo real (servidor de 1 minuto: el aviso a los 30 s), sin reloj falso. 38/38 en los 3 motores con dos repeticiones. Cuesta unos 30 s más por prueba y motor |

### U4 · Pulido por pantalla (2026-10-27)

| N.º | Duda o pendiente | Qué se hizo |
|---|---|---|
| UI-43 | **Botones deshabilitados sin aviso (UI-24):** enviar acta, verificar código OPL, registrar novedad, aplicar carga, guardar pesos, cerrar incidencia y firmar el plan OPL | `BotonConMotivo`: `aria-disabled` y aspecto de deshabilitado, pero se puede pulsar; al pulsarlo suena el aviso y el foco va al texto que dice qué falta. No ejecuta la acción ni envía el formulario. Las e2e siguen comprobando `toBeDisabled` (Playwright respeta `aria-disabled`) |
| UI-44 | **Niveles de encabezado (UI-13):** las secciones saltaban de h1 a h4 o h5 | Cada pantalla pasa su nivel más alto de sección a h2 (h3 en la respuesta del OPL, que va bajo el h2 del portal) con el mismo tamaño visual (`size`) |
| UI-45 | **Indicadores:** tres componentes locales distintos (Dashboard, Riesgo, tablero de presupuesto) | `shared/Kpi.tsx` único; los tonos del texto son los tokens LUME (sin oscurecer: ya llegan a AA) |
| UI-46 | Botones internos de Mantine sin nombre accesible (ver contraseña, borrar la selección, quitar el archivo) | Nombres por omisión en el tema. El de la contraseña es «Mostrar u ocultar lo escrito»: con la palabra «Contraseña», `getByLabel('Contraseña')` de las e2e encontraba dos elementos |
| UI-47 | Recorrido de capturas: solo creaba los perfiles sin alcance | Crea todos (Auditor con documento, Sanidad con fuerza, ESM con establecimiento y OPL regional con regional) y dice qué elemento desborda y qué botón no tiene nombre. Menús: Administrador 19, Coordinador 13, Auditor 3, DIGSA 9, Sanidad 8, ESM 7, OPL nacional 2, OPL regional 2, Analista 7 |
| UI-48 | **Reportes (INT-9, INT-17; ítem 13):** desbordaba 15 px a 360 px (pestañas), sin h1, cifras con un decimal o sin decimales y «hoy» del equipo | Después de traer develop (R1–R5): `EncabezadoPagina` con h1 y fuente, pestañas que parten en celular, `celda()`, `segundos()` y `tamano()` con `cifra()` (dos decimales también en enteros: «1200,00»), «hoy» y el año de «Desde» con `hoyBogota()`, tablas con `TablaAdaptable`. El recorrido de capturas queda limpio en todas las pantallas salvo Mapas (sesión de mapas: sin h1 y salto h3→h5) |

### U5 · Cierre: ESLint, división por rutas, 360 px y guía (2026-10-27)

| N.º | Duda o pendiente | Qué se hizo |
|---|---|---|
| UI-49 | **ESLint (UI-17):** el frontend no tenía linter | ESLint 9 con `typescript-eslint`, `react-hooks` y `jsx-a11y` (versiones exactas publicadas hace más de dos semanas; jsx-a11y aún no admite ESLint 10). `npm run lint` y paso en `ci/frontend.sh`. Dos excepciones documentadas en el código: `aria-invalid` en el lienzo de la firma (lo usa el foco al primer error) y las dependencias de `useIrAFila`. Queda un aviso en `Mapas.tsx` (sesión de mapas) |
| UI-50 | **División por rutas:** el paquete principal pesaba unos 930 kB | Todas las pantallas con `React.lazy` salvo Inicio e Ingreso; la espera se muestra dentro de la Plantilla (el menú y la cabecera siguen a la vista). Paquete principal: 548 kB; cada pantalla en su propio archivo |
| UI-51 | **360 px en todas las pantallas y motores** | `responsivo-360.spec.ts`: el administrador recorre todo su menú a 360 px y ninguna pantalla desborda a lo ancho, en cada motor (y en Chrome y Edge con `DIGSA_E2E_CANALES`) |
| UI-52 | **Opera (UI-1)** | Sigue sin instalarse en el equipo de desarrollo; documentado en `docs/guia_interfaz.md` (mismo motor que Chrome y Edge, canal si se instala) |
| UI-53 | **Guía para las otras sesiones** | `docs/guia_interfaz.md`: tokens LUME, componentes compartidos, formato, validación, responsive, accesibilidad, pruebas y navegadores |
