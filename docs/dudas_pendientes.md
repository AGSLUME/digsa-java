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
| 26.1 | **CSRF de doble envío:** el token se renueva al ingresar y al cambiar la contraseña, pero no está atado a la sesión. Un token viejo junto con su cookie vieja seguiría pasando, aunque un tercero no puede leer la cookie (SameSite=Strict, mismo origen) | Se deja así: es el patrón documentado de Spring para SPA. Atarlo a la sesión exige cambiar el cliente; queda para después de la prueba si TI lo pide |
| 26.2 | **Tope de «olvidé mi contraseña» con varias instancias:** se decide leyendo `token_vence` y escribiendo después; con dos instancias, dos pedidos simultáneos pueden enviar dos enlaces (el segundo anula el primero) | Con una instancia, el hilo único lo serializa. Pendiente: UPDATE condicional si se despliega en más de una instancia |
| 26.3 | **El enlace del QR del OPL** (`opl/ServicioOplPublico`) sigue usando `DIGSA_SESION_MINUTOS`, no el TIME OUT editable | No es una sesión de usuario. Coordinar con la sesión 1 si debe seguir al parámetro |
| 26.4 | **Ramas 409 sin prueba:** el cambio en el ingreso cuando la contraseña dejó de estar vencida entre los dos pasos, y el cambio concurrente de la propia contraseña | El control optimista (`WHERE clave_hash = :anterior`) está; faltan sus pruebas |
| 26.5 | **Restablecer el TIME OUT en las e2e:** el servidor de las e2e es compartido; la prueba lo restablece y falla con un mensaje claro si no puede | Hecho |
| 26.6 | **Coordinación con la exportación (sesión 1):** cada tabla nueva debe ir en `reglas/Exportacion.TABLAS` (lo exige `ExportacionApiTest.tablasContraElEsquema`) | Se agregó `seg_parametros` (V30) con una línea aditiva; las tablas de E2 y E3 se agregarán igual. Avisar a la sesión 1 al integrar |

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
| 26.7 | **Analista en visitas:** los anexos no le dan funciones en este módulo | Consulta nacional, como la DIGSA, sin solicitar visitas ni descargar los soportes del OPL (decisión del usuario). Si la adenda dice otra cosa, se ajusta con un rol adicional sin programar |
| 26.8 | **Un rol da consulta, no las acciones de su perfil base:** los servicios de agenda, tablero, ejecución y solicitudes deciden por el perfil directo (`perfilesActuales()`, `hasAnyAuthority`). Un rol con base Coordinador y la pantalla Agenda la ve, pero no actúa como coordinador | Así falla cerrado (P1 de la revisión). Para que un rol pueda actuar, cada servicio tendría que recibir los perfiles según la pantalla: coordinar con la sesión 1 si la DIGSA pide un rol con acciones |
| 26.9 | **Usuario cuyo único rol está inactivo:** el formulario no muestra el rol inactivo; para editar al usuario hay que marcarle otro perfil o reactivar el rol | La asignación al rol inactivo se conserva al editar |
| 26.10 | **`AlertaIntervencionApiTest` (módulo ejecución, sesión 1) falla en cada corrida completa (`mvnw verify`) y pasa sola:** espera 5 s el aviso de la bandeja de salida; en la corrida completa la bandeja trae avisos de las pruebas anteriores y la máquina está cargada | Sin cambio de código (no es de este módulo). Avisar a la sesión 1: ampliar la espera o vaciar la bandeja antes de la prueba |

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
| 26.11 | **Otros módulos** (agenda, alertas, catálogos, maestros, ejecución, OPL, hallazgos) siguen con el antes y el después solo en el texto de la descripción (`Bitacora.cambios`), cortado a 40 caracteres | Coordinar con la sesión 1: basta cambiar `bitacora.registrar(tipo, desc + cambios(...))` por `bitacora.registrarCambios(tipo, desc, antes, despues, sinValor, …)` |
| 26.12 | **Particiones de la bitácora (17.17):** V1 creó particiones mensuales hasta enero de 2031 (el contrato termina a más tardar en julio de 2030) y una partición por defecto | Cubren todo el contrato; no se agregó tarea. Si el contrato se prorroga, crear las particiones en una migración nueva |
