# Especificación: exportacion-alertas

> Extraída por spec-miner el 2026-10-21 del PHP de referencia (develop, commit db2e330).
> Fuentes: app/Support/Exportacion.php, controladores/exportacion.controlador.php, ajax/exportacion.ajax.php, vistas/modulos/exportacion.php, app/Support/Rutas.php ('exportacion'), app/Support/Mail/Mailer.php, app/Support/Exportador.php, app/Support/Reportes.php (csv), tests/exportacion_test.php, tests/e2e_local.php (fase 8, l.1291-1310), docs/decisiones_2026-10-15.md, docs/dudas_pendientes.md (15.2, 8.11, 2.5), docs/fases_desarrollo.md (fase 8), docs/DESPLIEGUE_TI.md (§6 y §7), docs/operacion_local.md, README.md, docs/cobertura_anexos_pliego.md.
> **Mandan sobre el PHP**, en este orden: `docs/decisiones_2026-10-21.md` (no toca esta capacidad, salvo D14: contenedores), `docs/decisiones_2026-10-20.md` (15.2), las dudas 16.5, 20.6, 21.10 y 22.12 de `docs/dudas_pendientes.md` y la fuente de verdad (`RESPUESTAS OBSERVACIONES.md`: GMI P77 b y f, P78, AGS-72, AGS-36, AGS-75; Anexo 5 ítems 1.1, 1.16, 1.23 y 2.4; `ANALISIS_RESPUESTAS_DIGSA_vs_SOFTWARE_2026-10-20.md` §2.1, §2.2 y plan C). Se marcan `<!-- decision: … -->`. El texto describe el comportamiento NUEVO.
> Relacionadas: formatos de los reportes y el tablero en `reportes-dashboard`; archivos, huellas y almacén en `archivos`; bitácora en `bitacora`; correos de cada flujo (notificación, acta, OPL, MFA) en sus capacidades; acceso por pantalla en `perfiles-alcance`.
> Estado en Java: existen `Pantallas.EXPORTACION` (solo Administrador), el puerto `plataforma/Correo` con `CorreoSmtp` y `CorreoArchivo` (este solo con `digsa.entorno=local`, lo impone `ConfiguracionCorreo`), `plataforma/Exportador` (reportes: CSV «;» con BOM, coma decimal, sin fórmulas; Excel, TXT y PDF), el puerto `plataforma/Almacen` con `AlmacenLocal`, `archivos.sha256` y `opl_soportes.sha256`, y `usuarios.telefono` (texto libre ≤30). **No existe**: ni el servicio ni la API de la exportación completa, ni la carpeta de soportes, ni el adaptador ACS, ni la bandeja de salida, ni nada de SMS. Ver «Datos que la reescritura Java debe agregar».

---

### Requirement: Solo el administrador exporta todos los datos
<!-- id: Rutas::PRIVADAS['exportacion'] | ajax/exportacion.ajax.php Auth::requerirAjax -->
<!-- entidades: Usuario, Perfil -->
<!-- impuesto_en: app/Support/Rutas.php 'exportacion'; ajax/exportacion.ajax.php Auth::requerirAjax([ADMINISTRADOR]); reglas/Pantallas.java EXPORTACION -->
<!-- decision: duda 15.2 | fases_desarrollo fase 8 («exportación completa de datos») -->

La pantalla «Exportación de datos» (grupo Configuración) y su descarga DEBEN quedar reservadas al Administrador; cualquier otro perfil recibe 403 (y el 403 queda en la bitácora, decisión 7.2).

#### Scenario: Otro perfil
<!-- test: tests/e2e_local.php:1293 -->
- **CUANDO** el coordinador pide la descarga de la exportación
- **ENTONCES** 403

---

### Requirement: La pantalla explica qué trae y advierte de los datos personales
<!-- id: vistas/modulos/exportacion.php -->
<!-- entidades: — -->
<!-- impuesto_en: vistas/modulos/exportacion.php -->
<!-- decision: Ley 1581 de 2012 | 2026-10-20 15.2 -->

La pantalla DEBE decir que se descarga un ZIP con un CSV por tabla (usuarios, puntos, auditores, visitas, checklist, actas, hallazgos, respuestas del OPL, propuestas de agenda, bitácora y catálogos), un `LEEME.txt` y, **nuevo (15.2)**, la carpeta de soportes por visita con su índice; que no incluye contraseñas ni huellas de enlaces y códigos; que contiene datos personales (Ley 1581 de 2012) y debe guardarse en un lugar seguro y no enviarse por correo; y que la descarga queda en la bitácora. Ofrece un botón «Descargar exportación completa (ZIP)».

#### Scenario: Aviso
<!-- test: sin prueba de pantalla en el PHP (vistas/modulos/exportacion.php) -->
- **CUANDO** el administrador abre la pantalla
- **ENTONCES** ve el aviso de la Ley 1581 y el botón de descarga

---

### Requirement: Un CSV por tabla de la aplicación, en un ZIP
<!-- id: Exportacion::TABLAS | ControladorExportacion::descargar -->
<!-- entidades: todas las tablas de la aplicación -->
<!-- impuesto_en: app/Support/Exportacion.php TABLAS; controladores/exportacion.controlador.php descargar() -->
<!-- decision: duda 15.2 (PHP: «CSV por tabla») | 2026-10-20 15.2 | AGS-72 y Anexo 5 1.1/1.16 (formatos abiertos y reutilizables) | 2026-10-20 7.5 (la bitácora entra en la entrega final) -->

La exportación DEBE generar un ZIP con un archivo `<tabla>.csv` por cada tabla de la aplicación (la lista de Java está en «Datos que la reescritura Java debe agregar», punto 2), un `LEEME.txt` y la carpeta `soportes/`. La primera fila de cada CSV son los nombres de las columnas exportadas, en el orden de la tabla. La bitácora (`log_transacciones`) sale completa, con todas sus particiones en un solo `log_transacciones.csv`.

#### Scenario: ZIP completo
<!-- test: tests/e2e_local.php:1302 -->
- **CUANDO** el administrador descarga la exportación
- **ENTONCES** el ZIP abre, tiene un CSV por tabla de la lista, el `LEEME.txt` con «Tablas y filas» y la carpeta `soportes/`

#### Scenario: Datos al día
<!-- test: tests/e2e_local.php:1305 -->
- **CUANDO** se acaba de crear una visita y se exporta
- **ENTONCES** `visitas.csv` contiene su número

---

### Requirement: Formato del CSV de la DIGSA
<!-- id: Exportacion::fila | fputcsv -->
<!-- entidades: — -->
<!-- impuesto_en: app/Support/Exportacion.php fila(); controladores/exportacion.controlador.php (fputcsv con ',' y BOM); plataforma/Exportador.java csv(), texto() -->
<!-- decision: 2026-10-20 15.2 | GMI P77 f (RESPUESTAS OBSERVACIONES p.87) | Anexo 5 2.4 | VCO P27 -->

Cada CSV DEBE:
- ir en UTF-8 con BOM, separado por **punto y coma** (`;`), con fin de línea `\r\n`;
- encerrar entre comillas dobles el campo que tenga `;`, comillas o saltos de línea, duplicando las comillas internas;
- llevar los números con **coma decimal** y sin separador de miles;
- llevar las fechas y horas en hora de Colombia, 24 h (`yyyy-MM-dd HH:mm:ss`), y los booleanos como `true`/`false`;
- dejar vacío el NULL;
- anteponer un apóstrofo al texto que empieza por `=`, `+`, `-`, `@`, tabulador o retorno (inyección de fórmulas), sin tocar los números con signo.

<!-- decision: 2026-10-20 15.2 — «CSV ";", coma decimal y UTF-8». El PHP usaba «,» y punto decimal. -->

#### Scenario: Sin fórmulas y NULL vacío
<!-- test: tests/exportacion_test.php:29 -->
- **CUANDO** una fila trae `=SUMA(A1)`, `@x`, `-x`, NULL, `normal` y 5
- **ENTONCES** sale `'=SUMA(A1)`, `'@x`, `'-x`, vacío, `normal` y `5`

#### Scenario: Números con signo y coma decimal
<!-- test: tests/exportacion_test.php:30, tests/e2e_local.php:1308 (ajustar a «;» y coma) -->
- **CUANDO** se exporta la longitud -75.581775 de un municipio
- **ENTONCES** `par_municipios.csv` trae `;-75,581775` sin apóstrofo

<!-- incierto: la regla de 2 decimales (Anexo 5 2.4, VCO P27) es de presentación. En la entrega de datos se recomienda NO redondear (coordenadas de 6 decimales, pesos del riesgo): solo cambiar el punto por la coma. Confirmar. -->
<!-- incierto: columnas jsonb (resumen, contenido, alertas, respuestas) y text[] (destinatarios): se recomienda el JSON tal cual en el campo, y el arreglo como JSON. -->

---

### Requirement: Ningún secreto sale en la exportación
<!-- id: Exportacion::EXCLUIDAS | Exportacion::columnas -->
<!-- entidades: Usuario, MfaCodigo, OplAcceso, OplCodigo, Sesion -->
<!-- impuesto_en: app/Support/Exportacion.php EXCLUIDAS, columnas() -->
<!-- decision: duda 15.2 («sin secretos») | AGS-34 -->

La exportación NO DEBE incluir contraseñas, enlaces, códigos ni sus huellas, ni sesiones. En Java:

| Tabla | Columnas que no salen |
|---|---|
| `usuarios` | `clave_hash`, `token_hash`, `token_vence`, `sesion_huella` |
| `mfa_codigos` | `codigo_hash` |
| `opl_accesos` | `token_hash` |
| `opl_codigos` | `codigo_hash` |

`spring_session`, `spring_session_attributes` y `flyway_schema_history` no se exportan (son técnicas). Las demás tablas salen con todas sus columnas.

#### Scenario: Usuarios sin secretos
<!-- test: tests/exportacion_test.php:25, tests/e2e_local.php:1304 -->
- **CUANDO** se exporta `usuarios`
- **ENTONCES** la cabecera trae `usuario` y ninguna de `clave_hash`, `token_hash`, `token_vence`, `sesion_huella`

#### Scenario: OPL sin huellas
<!-- test: tests/exportacion_test.php:26, tests/e2e_local.php:1307 -->
- **CUANDO** se exportan `opl_accesos` y `opl_codigos`
- **ENTONCES** traen `visita_id` y no traen `token_hash` ni `codigo_hash`

#### Scenario: Las demás completas
<!-- test: tests/exportacion_test.php:28 -->
- **CUANDO** se exporta `visitas`
- **ENTONCES** salen todas sus columnas

<!-- incierto: `mfa_codigos` guarda la IP de cada código (duda 17.10, Ley 1581). Se exporta sin la huella; si se decide purgarla, sale lo que quede. -->

---

### Requirement: Una sola foto de la base, fila por fila
<!-- id: ControladorExportacion::descargar (START TRANSACTION WITH CONSISTENT SNAPSHOT, consulta sin búfer) -->
<!-- entidades: — -->
<!-- impuesto_en: controladores/exportacion.controlador.php descargar() -->
<!-- decision: AGS-75 / GMI P74 (10 min para exportar) -->

Todas las tablas DEBEN leerse en una misma transacción de solo lectura con aislamiento `REPEATABLE READ` (en PostgreSQL, una sola instantánea), y cada tabla fila por fila (cursor por lotes), escribiendo el ZIP en flujo, sin cargar una tabla ni el ZIP entero en memoria. La descarga no bloquea la sesión del administrador en sus otras pestañas. El límite de tiempo de la generación es de 10 minutos.

#### Scenario: Consistencia
<!-- test: sin prueba en el PHP -->
- **CUANDO** se crea una visita mientras la exportación va por `usuarios`
- **ENTONCES** la visita no aparece en `visitas.csv` ni en las tablas hijas (todo es de la misma foto)

<!-- incierto: con los soportes (fotos y videos de hasta 100 MB) el ZIP puede pesar varios GB y no caber en 10 minutos por HTTP. Se recomienda generarlo en segundo plano en el almacén, avisar cuando esté listo y descargarlo después (ver contradicción 6). -->

---

### Requirement: Carpeta de soportes por visita, con índice y huella
<!-- id: (nuevo) -->
<!-- entidades: Visita, Archivo, VisitaActa, VisitaFirma, OplSoporte, ReporteDigsa -->
<!-- impuesto_en: sin equivalente en el PHP (el PHP no exporta archivos: los respalda TI) -->
<!-- decision: 2026-10-20 15.2 («más la carpeta de soportes (fotos, PDF, actas) con índice y huella por archivo») | duda 21.10 (soportes del OPL en la carpeta de la visita) | AGS-72 y GMI P78 (imágenes y soportes, consulta y metadatos) | Anexo 5 1.16 -->

El ZIP DEBE traer `soportes/<número de visita>/` por cada visita con archivos, con:
- `evidencias/`: los de `archivos` de la visita (fotos, soportes de ítems y obligaciones, minuta);
- `actas/`: el PDF de cada ronda (`visitas_actas` → `archivos`), como `acta_ronda_<n>.pdf`;
- `opl/`: los de `opl_soportes` (21.10), por clave del hallazgo;
- `firmas/`: la imagen PNG de cada firma (`visitas_firmas.imagen`), como `<rol>_ronda_<n>.png`; esa columna no va en el CSV.

El nombre de cada archivo en el ZIP es `<id>_<nombre original saneado>` (sin rutas, solo `[A-Za-z0-9._-]`). Además, `soportes/indice.csv` (mismo formato CSV) con una fila por archivo: número de visita, punto, carpeta, tabla de origen, id, clave (ítem u obligación), nombre original, ruta en el ZIP, tipo, tamaño, SHA-256 guardado, SHA-256 calculado al leer, quién lo subió y cuándo, y si estaba eliminado.

#### Scenario: Carpeta de la visita
<!-- test: sin prueba en el PHP (decisión 2026-10-20 15.2) -->
- **CUANDO** una visita cerrada tiene 2 fotos, un acta y una respuesta del OPL con un soporte
- **ENTONCES** `soportes/<número>/` trae `evidencias/` con 2 archivos, `actas/acta_ronda_1.pdf`, `opl/` con 1 archivo, `firmas/` con sus PNG, y `indice.csv` las lista con la huella

#### Scenario: Huella que no coincide
<!-- test: sin prueba en el PHP -->
- **CUANDO** un archivo del almacén ya no coincide con su `sha256`
- **ENTONCES** se exporta igual, el índice lo marca «huella distinta» y el `LEEME.txt` cuenta cuántos hay

#### Scenario: Archivo que falta
<!-- test: sin prueba en el PHP -->
- **CUANDO** el almacén no tiene un archivo registrado
- **ENTONCES** la fila del índice dice «no encontrado» y la exportación sigue

<!-- incierto: (1) si los archivos eliminados (`archivos.eliminado`) se exportan: se recomienda que sí, marcados, porque son evidencia. (2) Si la huella distinta o el faltante debería ser una alerta crítica (integridad, GMI P77 b): se recomienda que sí. (3) GMI P78 pide consulta «por documento, tirilla y fecha de dispensación»: es del módulo de cuentas médicas; para visitas basta el índice. -->

---

### Requirement: LEEME del ZIP
<!-- id: Exportacion::leeme -->
<!-- entidades: — -->
<!-- impuesto_en: app/Support/Exportacion.php leeme() -->
<!-- decision: duda 15.2 | 2026-10-20 15.2 | AGS-36 (integridad con huella en CSV y Excel) | GMI P78 (documentos técnicos de acceso y consulta) -->

El `LEEME.txt` (UTF-8, `\r\n`) DEBE decir: «Suite DIGSA · Exportación completa de los datos», quién (nombre y usuario) y cuándo la generó (hora de Colombia, 24 h); el formato («Un archivo CSV por tabla (UTF-8 con BOM, separado por punto y coma, coma decimal)»); las columnas excluidas por tabla; la estructura de `soportes/` y del índice; y la lista «Tablas y filas» con el número de filas de cada tabla, el SHA-256 de cada CSV y el número de soportes, con los de huella distinta y los no encontrados.

#### Scenario: Contenido
<!-- test: tests/exportacion_test.php:32 -->
- **CUANDO** se arma el LEEME con usuarios 3 y visitas 10 por «Admin (admin)»
- **ENTONCES** trae «Admin (admin)», las columnas excluidas, la explicación de los soportes y `visitas 10`

---

### Requirement: Descarga, bitácora y temporales
<!-- id: ControladorExportacion::descargar (cabeceras, registrar, finally) -->
<!-- entidades: Bitacora -->
<!-- impuesto_en: controladores/exportacion.controlador.php descargar(); ajax/exportacion.ajax.php -->
<!-- decision: 2026-10-20 15 (valores de antes y después; aquí no aplica) | Anexo 5 14.9 (los logs registran descargas) -->

Al terminar, el sistema DEBE:
- registrar en la bitácora el tipo `exportacion_completa`, operación `consulta`, con «N tablas · N filas · N soportes» (sin datos personales);
- entregar `digsa_exportacion_aaaammdd_hhmm.zip` con `Content-Type: application/zip`, `Content-Disposition: attachment`, `X-Content-Type-Options: nosniff` y `Cache-Control: no-store`;
- borrar siempre los temporales, también si la descarga se corta o falla (contienen datos personales).

Si algo falla (disco lleno, tabla ilegible, ZIP vacío), no entrega un archivo a medias que parezca completo: responde 500 con «No se pudo generar la exportación. Intente de nuevo o comuníquese con TI.», sin detalle interno.

#### Scenario: Falla
<!-- test: sin prueba en el PHP -->
- **CUANDO** se llena el disco al escribir un CSV
- **ENTONCES** 500 con el mensaje genérico, sin temporales y sin registro `exportacion_completa`

---

### Requirement: Descarga de los soportes del OPL para la DIGSA y Sanidad
<!-- id: (nuevo) -->
<!-- entidades: OplSoporte, Visita -->
<!-- impuesto_en: sin equivalente en el PHP -->
<!-- decision: duda 22.12 (fase 10) | 2026-10-20 12.2 (el ESM no ve las objeciones ni sus soportes) -->

Desde «Prestadores con hallazgos», los perfiles DIGSA y Sanidad DEBEN poder descargar en un ZIP los soportes del OPL de una visita de su alcance, con el mismo índice y huella. El ESM no.

#### Scenario: Alcance
<!-- test: sin prueba en el PHP -->
- **CUANDO** un usuario de Sanidad del EJC pide los soportes de una visita de la ARC / de una del EJC / un usuario ESM los pide
- **ENTONCES** 403 / ZIP con `opl/` e `indice.csv` / 403

---

### Requirement: Correo por un puerto con adaptadores intercambiables
<!-- id: Mailer::send | plataforma/Correo, ConfiguracionCorreo -->
<!-- entidades: — -->
<!-- impuesto_en: app/Support/Mail/Mailer.php send(); plataforma/Correo.java; ConfiguracionCorreo.java; CorreoSmtp.java; CorreoArchivo.java -->
<!-- decision: duda 16.5 | 16.1 (código portátil: correo detrás de interfaces) | decisión del 2026-10-19 (Azure) | D14 (contenedores) -->

Todo correo DEBE salir por el puerto `Correo` (`enviar(Mensaje) → Resultado`, nunca lanza por una falla del servidor). El adaptador se elige con `digsa.correo.modo`:

| Modo | Adaptador | Dónde se admite |
|---|---|---|
| `smtp` | `CorreoSmtp` (corporativo, STARTTLS) | todos |
| `archivo` | `CorreoArchivo`: guarda `.html` y adjuntos, no envía | solo `digsa.entorno=local` |
| `acs` | `CorreoAcs` (nuevo): Azure Communication Services Email | todos, cuando TI entregue el recurso |

Con `archivo` fuera de local, o sin la configuración del modo elegido, la aplicación NO arranca. El resultado distingue entregado, rechazados y error sin datos internos ni direcciones en el registro.

#### Scenario: Archivo fuera de local
<!-- test: README.md:60 y Mailer.php (MAIL_MAILER=log fuera de local = falla); prueba JUnit de ConfiguracionCorreo -->
- **CUANDO** `digsa.correo.modo=archivo` y `digsa.entorno=pruebas`
- **ENTONCES** la aplicación no arranca con «digsa.correo.modo=archivo solo se permite con digsa.entorno=local…»

#### Scenario: Rechazo parcial
<!-- test: CorreoArchivo.DOMINIO_RECHAZO; Mailer.php (recipients failed) -->
- **CUANDO** uno de dos destinatarios es rechazado
- **ENTONCES** `ok = true`, el rechazado en `rechazados` y el mensaje «Algunos destinatarios rechazaron el correo.»

---

### Requirement: Adaptador ACS Email
<!-- id: (nuevo) CorreoAcs -->
<!-- entidades: — -->
<!-- impuesto_en: sin equivalente en el PHP -->
<!-- decision: duda 16.5 (el de ACS se agrega cuando TI confirme) | plan_acelerado (ACS Email después de la prueba) -->

`CorreoAcs` DEBE enviar con el SDK de ACS Email (`azure-communication-email`) usando la cadena de conexión o la identidad administrada que entregue TI (nunca en el repositorio: variable de entorno o Key Vault), el remitente del dominio verificado y los mismos `para`, `copia`, asunto, HTML y adjuntos. Antes de enviar calcula el tamaño codificado en base64 del mensaje: si pasa el tope configurado (`digsa.correo.acs.tope-mb`, 10 por defecto), devuelve `falla` con «El correo supera el tamaño que admite el servicio (N MB).» sin intentar el envío. Espera el resultado de la operación (sondeo del SDK) con un tiempo máximo; si vence, responde falla «No se confirmó el envío.». Como ACS no informa rechazos por destinatario al aceptar, un `Succeeded` cuenta como entregado a todos.

#### Scenario: Acta grande
<!-- test: sin prueba en el PHP (duda 16.5) -->
- **CUANDO** el acta y sus soportes en PDF suman 15 MB codificados y el tope es 10
- **ENTONCES** falla sin enviar, la visita registra el envío fallido y el mensaje dice el tope

<!-- duda: 16.5 — TI debe ampliar el tope a ~25 MB codificados y delegar el DNS de un subdominio (SPF, DKIM). Mientras tanto el modo por defecto sigue siendo `smtp`. -->
<!-- incierto: ACS guarda los datos del mensaje en la geografía elegida; no hay geografía Colombia (decisión 16.1 y AGS-29/77/78: datos en Colombia). Confirmar con Microsoft y la DIGSA si el correo saliente por ACS es admisible; si no, se queda en `smtp`. -->

---

### Requirement: Bandeja de salida con reintentos
<!-- id: (nuevo) -->
<!-- entidades: CorreoSalida -->
<!-- impuesto_en: sin equivalente en el PHP (SMTP síncrono, falla en el registro de errores) -->
<!-- decision: duda 20.6 (fase 10) -->

Los avisos que no esperan respuesta del usuario DEBEN encolarse en `correos_salida` en la misma transacción que el cambio que los origina, y una tarea los envía después de confirmar, con reintentos (por ejemplo 1, 5, 30 min y 2 h; máximo 5 intentos) y candado para que dos réplicas no envíen el mismo. Cada intento guarda estado (`pendiente`, `enviando`, `enviado`, `parcial`, `fallido`), intentos, último error (sin direcciones) y fecha. El fallo definitivo queda en la bitácora (`correo_fallido`) y, si el aviso es de una alerta crítica, dispara el SMS (requisito siguiente). Los envíos que el usuario espera en pantalla (código MFA, enlace de contraseña, código del QR del OPL) siguen síncronos.

#### Scenario: Reintento
<!-- test: sin prueba en el PHP -->
- **CUANDO** el servidor de correo no responde al primer intento de una notificación previa y sí al segundo
- **ENTONCES** la fila queda `enviado` con 2 intentos y la visita `notificada`

#### Scenario: Agotado
<!-- test: sin prueba en el PHP -->
- **CUANDO** falla 5 veces
- **ENTONCES** `fallido`, `correo_fallido` en la bitácora y el botón de reenvío del flujo sigue disponible

<!-- incierto: hoy el estado de la visita (notificada), del acta (enviada/parcial/fallida) y del reporte a la DIGSA (reportado) depende del resultado del envío síncrono. Pasar esos tres a la bandeja exige que el estado cambie al confirmar el envío (devolución de llamada). Se recomienda empezar por los avisos informativos (solicitudes 9.6, cancelación 9.3, decisión del líder, recordatorios) y dejar notificación, acta y reporte síncronos con su reintento propio. Confirmar. -->

---

### Requirement: Alertas críticas por correo y además por SMS
<!-- id: (nuevo) -->
<!-- entidades: AlertaCritica, Usuario -->
<!-- impuesto_en: sin equivalente en el PHP (no hay SMS) -->
<!-- decision: GMI P77 b (RESPUESTAS OBSERVACIONES p.86) | ANALISIS_RESPUESTAS §2.2 («Nuevo: integrar un proveedor de SMS») y plan C | Anexo 5 (glosas: «mensajes de texto o correos») | plan_acelerado (fase 10) -->

Las alertas informativas y operativas DEBEN ir solo por correo. Las **críticas** (las que afectan la continuidad, seguridad, disponibilidad o integridad de la información) DEBEN ir por correo **y además** por SMS a los destinatarios configurados para cada tipo. El catálogo de tipos críticos es parametrizable por el administrador (activo, destinatarios por usuario o perfil, si va por SMS). Tipos iniciales recomendados:

| Tipo | Disparo |
|---|---|
| `tarea_diaria_fallida` | una tarea diaria termina con fallos (22.13) |
| `correo_agotado` | un aviso crítico agota los reintentos |
| `integridad_archivo` | un archivo no coincide con su huella o falta (exportación, acta) |
| `almacen_no_disponible` | el almacén o la base no responden a la verificación de salud |
| `bloqueo_masivo` | N cuentas bloqueadas por intentos en una hora (posible ataque) |

El SMS lleva solo el tipo, la hora y «Revise la Suite DIGSA», sin datos personales, sin enlaces con token y sin el contenido del correo (Ley 1581). Cada envío queda en `sms_salida` y en la bitácora (`alerta_critica`), con el teléfono enmascarado. Una misma alerta abierta no se repite por SMS más de una vez por hora.

#### Scenario: Tarea diaria con fallos
<!-- test: sin prueba en el PHP -->
- **CUANDO** la tarea diaria del OPL termina con 2 reportes sin enviar y el tipo está activo
- **ENTONCES** sale un correo a los destinatarios del tipo y un SMS a sus teléfonos, y la bitácora registra `alerta_critica`

#### Scenario: Alerta informativa
<!-- test: sin prueba en el PHP -->
- **CUANDO** llega una solicitud a necesidad nueva
- **ENTONCES** solo sale el correo al coordinador

#### Scenario: Sin teléfono
<!-- test: sin prueba en el PHP -->
- **CUANDO** un destinatario no tiene teléfono móvil válido
- **ENTONCES** recibe el correo y la alerta registra «sin teléfono» para él

<!-- incierto: la DIGSA dice que «los destinatarios, niveles de criticidad, tiempos de notificación y mecanismos de escalamiento se precisarán en el Anexo Técnico» (GMI P77 b). El catálogo de arriba es una recomendación; ni el PHP ni las decisiones del usuario lo fijan. «Plan vencido» (21.7, 21.9) es una alerta crítica del tablero, pero de negocio, no de continuidad o integridad: se recomienda no mandarla por SMS. -->

---

### Requirement: SMS por un puerto con adaptadores
<!-- id: (nuevo) Sms -->
<!-- entidades: SmsSalida -->
<!-- impuesto_en: sin equivalente en el PHP -->
<!-- decision: 16.1 (portabilidad: servicios externos detrás de interfaces) | GMI P77 b («SMS u otro canal inmediato definido por la DIGSA») -->

El SMS DEBE salir por un puerto `Sms` (`enviar(telefono, texto) → Resultado`, nunca lanza), elegido con `digsa.sms.modo`:

| Modo | Adaptador | Dónde se admite |
|---|---|---|
| `archivo` | `SmsArchivo`: escribe el mensaje en `almacen/sms/` y no lo envía | solo `digsa.entorno=local` |
| `ninguno` | no envía; registra «SMS no configurado» y la alerta queda solo por correo | local y pruebas |
| `<proveedor>` | el que se contrate (ACS SMS u otro) | todos |

En producción, sin un proveedor configurado, la aplicación no arranca. El teléfono se normaliza a E.164 (`+57` y 10 dígitos que empiezan por 3); el texto, a 160 caracteres GSM-7 sin tildes problemáticas.

#### Scenario: Modo archivo
<!-- test: sin prueba en el PHP -->
- **CUANDO** en local se dispara una alerta crítica
- **ENTONCES** queda un archivo en `almacen/sms/` con el teléfono y el texto, y `sms_salida` la marca `enviado`

<!-- duda: proveedor de SMS sin elegir. ACS SMS no se sabe si entrega a móviles de Colombia ni con qué remitente; alternativas locales (operadores o agregadores colombianos) cumplen mejor el alojamiento en Colombia. Bloquea solo el adaptador real. -->

---

### Invariant: La exportación trae todas las tablas de la aplicación, ni una más
<!-- entidades: todas -->
<!-- impuesto_en: app/Support/Exportacion.php TABLAS frente a la guarda de Database/Structure -->
<!-- verificado_por: tests/exportacion_test.php:24 -->

La lista de tablas exportadas DEBE coincidir con las tablas de `information_schema` del esquema `public` menos las técnicas (`flyway_schema_history`, `spring_session`, `spring_session_attributes`) y las particiones de `log_transacciones`. Una migración que crea una tabla sin agregarla a la lista hace fallar la prueba.

---

### Invariant: Nada sensible sale por los canales de aviso
<!-- entidades: Correo, Sms -->
<!-- impuesto_en: vistas/modulos/exportacion.php («no lo envíe por correo»); Bitacora.sinCorreos -->
<!-- verificado_por: sin prueba en el PHP -->

Ningún correo ni SMS DEBE llevar la exportación, contraseñas, códigos de otro usuario, huellas ni enlaces con token salvo el del propio destinatario. Los registros (bitácora, errores, `correos_salida`, `sms_salida`) guardan los correos y teléfonos enmascarados en los mensajes de error.

---

### Invariant: Los adaptadores de prueba nunca corren fuera de local
<!-- entidades: — -->
<!-- impuesto_en: app/Support/Mail/Mailer.php (MAIL_MAILER=log solo en local); plataforma/ConfiguracionCorreo.java -->
<!-- verificado_por: prueba de arranque de ConfiguracionCorreo -->

`digsa.correo.modo=archivo` y `digsa.sms.modo=archivo` DEBEN impedir el arranque fuera de `digsa.entorno=local`: de lo contrario los avisos parecerían enviados sin salir.

---

### Invariant: Cada soporte exportado se puede verificar
<!-- entidades: Archivo, OplSoporte, VisitaActa -->
<!-- impuesto_en: archivos.sha256, opl_soportes.sha256 (V9, V12) -->
<!-- verificado_por: sin prueba en el PHP -->

Todo archivo de `soportes/` DEBE tener su fila en `indice.csv` con la huella SHA-256 guardada y la calculada; todo CSV del ZIP, su huella en el `LEEME.txt` (AGS-36).

---

## Contradicciones registradas

1. **Separador y decimales.** El PHP exporta con «,» y punto decimal (`fputcsv(..., ',')`, LEEME «separado por comas»), aunque sus reportes ya usaban «;». Manda 15.2 y GMI P77 f: «;», coma decimal, UTF-8. La prueba e2e de longitudes (`,-75.581775`) cambia a `;-75,581775`.
2. **Archivos.** La duda 15.2 del PHP dejó la exportación sin archivos («los respalda TI»; DESPLIEGUE_TI §7). La decisión 15.2 del 2026-10-20, la 21.10 y AGS-72 piden la carpeta de soportes con índice y huella.
3. **Alertas solo por correo.** El PHP no tiene SMS. GMI P77 b exige SMS u otro canal inmediato para las críticas.
4. **Firmas.** En Java la firma es `bytea` en `visitas_firmas`; en el CSV no sirve. Sale como PNG en la carpeta de la visita.
5. **ACS y alojamiento en Colombia.** 16.5 prevé ACS Email; AGS-29/77/78 exigen datos en Colombia y ACS no tiene esa geografía. No se resuelve aquí: el modo `smtp` sigue por defecto.
6. **Descarga directa o diferida.** El PHP genera y descarga en la misma petición (600 s). Con los soportes el tamaño puede no caber; la entrega final de la DIGSA es en disco duro externo (AGS-72, Anexo 5 1.16), así que se recomienda generar en el almacén y descargar o copiar después.
7. **Bitácora en la exportación.** El PHP la exporta; la decisión 7.5 (se conserva toda y entra en la entrega final) lo confirma. No hay contradicción, pero su tamaño (43 meses de particiones) refuerza el punto 6.

## Dudas

**Bloquean solo su adaptador real (lo demás se construye):**
- **Proveedor de SMS** (contrato, remitente, cobertura en Colombia, costo «sin costo adicional para la Entidad»). Sin él, `Sms` funciona en `archivo`/`ninguno`.
- **Credenciales y recurso de ACS Email** (16.5): cadena de conexión o identidad administrada, dominio verificado, DNS del subdominio (SPF, DKIM) y tope de ~25 MB. Sin ellos, `smtp`.
- **Si ACS es admisible** con los datos en Colombia (16.1).

**No bloquean (se construye con la recomendación y se valida):**
- Catálogo de alertas críticas, destinatarios y tiempos (GMI P77 b remite al Anexo Técnico definitivo).
- Qué avisos pasan a la bandeja de salida y cuáles siguen síncronos (20.6).
- Redondeo a 2 decimales en la exportación; archivos eliminados; huella distinta como alerta crítica.
- Exportación diferida en el almacén frente a descarga directa.

## Datos que la reescritura Java debe agregar

1. **Reglas puras** en `reglas/Exportacion.java`: `TABLAS`, `EXCLUIDAS`, `columnas(tabla, cols)`, `celda(Object)` (coma decimal, fechas 24 h, sin fórmulas, NULL vacío; reusar `Exportador.texto`/`campo`), `nombreSoporte(id, original)` y `leeme(...)`; las 7 pruebas de `tests/exportacion_test.php` pasan a JUnit (la de tablas, contra `information_schema` con Flyway).
2. **Tablas exportadas (V1 a V15, 53):** `perfiles`, `usuarios`, `usuarios_perfiles`, `mfa_codigos`, `log_transacciones`; `par_departamentos`, `par_municipios`, `par_unidades`, `par_fuerzas`, `par_regionales`, `par_modalidades`, `par_tipos_visita`, `par_parametros`, `par_festivos`, `catalogos`, `catalogo_valores`, `opl`, `puntos`, `auditores`, `chk_versiones`, `chk_vacios`, `chk_guia_obligacion`, `chk_componentes`, `chk_items`; `visitas`, `visitas_componentes`, `visitas_auditores`, `visitas_reparto`, `visitas_historial`, `visitas_notificaciones`, `visitas_solicitudes`; `visitas_ejecucion`, `visitas_personal`, `visitas_respuestas`, `archivos`, `visitas_firmas` (sin `imagen`), `visitas_actas`, `visitas_resumen`; `obligaciones_contrato`, `visitas_obligaciones`, `visitas_plan`, `visitas_hallazgos`, `opl_accesos`, `opl_codigos`, `opl_respuestas`, `opl_soportes`, `reportes_digsa`, `opl_planes`, `opl_avisos`; `prorrogas`; `propuestas_agenda`, `propuestas_agenda_items`, `propuestas_agenda_items_otros`. Más las que agregue la fase 10 (`correos_salida`, `sms_salida`, `alertas_criticas`).
3. **`ServicioExportacion`**: transacción `READ ONLY, REPEATABLE READ`, `JdbcClient` con `fetchSize` y `ZipOutputStream` hacia un temporal o el `Almacen`; lee los soportes con `Almacen.leer` y calcula SHA-256 al copiar. API `GET /api/exportacion` (o `POST` + `GET {id}` si es diferida), solo `Pantallas.EXPORTACION`; bitácora `exportacion_completa`.
4. **Pantalla React** `/exportacion`: el texto de la vista del PHP actualizado (formato «;», soportes, Ley 1581) y el botón; si es diferida, estado y enlace de descarga.
5. **ZIP de soportes del OPL** para DIGSA y Sanidad en «Prestadores con hallazgos» (22.12), con el alcance de cada perfil.
6. **`CorreoAcs`** con `com.azure:azure-communication-email`, `digsa.correo.acs.{endpoint|cadena, remitente, tope-mb, espera-s}` desde variables de entorno o Key Vault; la rama `acs` en `ConfiguracionCorreo`. Probado con un doble del cliente del SDK.
7. **`correos_salida`**: `id`, `origen` (tipo de aviso), `referencia` (visita, acta, solicitud), `para` text[], `copia` text[], `asunto`, `html`, adjuntos (rutas del almacén, no bytes), `estado` (CHECK), `intentos`, `proximo`, `error` (≤500), `creado`, `enviado`; índice `(estado, proximo)`. Tarea con candado (ShedLock o `FOR UPDATE SKIP LOCKED`).
8. **Puerto `Sms`** con `SmsArchivo`, `SmsNinguno` y el adaptador del proveedor; `digsa.sms.modo`; `sms_salida` (`id`, `alerta_id`, `telefono` enmascarado y cifrado o solo enmascarado, `texto`, `estado`, `error`, `creado`, `enviado`).
9. **`alertas_criticas`** (catálogo editable por el administrador: `tipo`, `nombre`, `activa`, `sms`, destinatarios por usuario o perfil) y `alertas_criticas_eventos` (tipo, detalle sin datos personales, abierta/cerrada, último SMS) para no repetir el SMS más de una vez por hora.
10. **Teléfono móvil**: `usuarios.telefono` es texto libre; validar E.164 colombiano (`+573…`) al guardar si el usuario es destinatario de alertas críticas.

Lo que se puede construir sin credenciales: puntos 1 a 5, 7 a 10 completos con `CorreoArchivo`/`Mailpit` (docker-compose de D14) y `SmsArchivo`; el punto 6 hasta la prueba con el doble del SDK.

<!-- pendiente: app/Support/ActaCorreos.php (adjuntos del acta y su tamaño, cubierto en acta-envio), scripts/ (tareas programadas del PHP), Database/Structure (la guarda de tablas del PHP, sustituida por las migraciones Java), controladores de los avisos de cada flujo (qué correos son informativos) -->
