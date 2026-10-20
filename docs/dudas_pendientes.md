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
| 17.2 | **Ítem 2 "en dos equipos a la vez":** si los dos equipos usan el mismo administrador, la sesión única cierra la sesión del primero | Hay que llevar **dos administradores** creados de antemano. La creación simultánea del mismo usuario está probada: uno queda creado y el otro recibe 422 |
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
