# Especificación: archivos

> Extraída por spec-miner el 2026-10-20 del PHP de referencia (develop, commit db2e330).
> Fuentes: app/Support/Archivos.php, app/Support/LimitesSubida.php, app/Support/Auth.php (requerirAjax), controladores/checklist.controlador.php (subir, eliminar, servir*), controladores/solicitudes.controlador.php (escrito PDF), controladores/opl.controlador.php (soportes), modelos/checklist.modelo.php (eliminarArchivos), Database/Structure/006_checklist_visita.sql (visita_archivos), tests/limites_subida_test.php, tests/e2e_local.php, docs/decisiones_2026-10-15.md (3, 2.5, 2.7, agentes 4, 5.6), docs/dudas_pendientes.md (5.6, 7.6, 10.8).

---

### Requirement: Tipo de archivo validado por el contenido
<!-- id: Archivos.guardar -->
<!-- entidades: Archivo -->
<!-- impuesto_en: app/Support/Archivos.php Archivos::PERMITIDOS, Archivos::guardar() -->
<!-- decision: 2.7 | agentes 4 -->

El servidor DEBE decidir el tipo del archivo por su contenido (detección de tipo MIME real), nunca por la extensión ni por lo que diga el navegador, y aceptar solo: JPEG, PNG, WebP, GIF, HEIC, HEIF, PDF, DOCX, XLSX, DOC, XLS, MP4, MOV (QuickTime), WebM, 3GP, texto plano y Markdown; el archivo se guarda con la extensión que corresponde al tipo detectado.

#### Scenario: Tipo no permitido
<!-- test: tests/e2e_local.php:719 -->
- **CUANDO** se sube un archivo cuyo contenido es código PHP (aunque se llame `.pdf`) o cualquier tipo fuera de la lista
- **ENTONCES** se rechaza con 422 "Tipo de archivo no permitido (<mime>). Use imágenes (JPG, PNG, HEIC…), PDF, Word, Excel, video (MP4, MOV, WebM) o texto."

#### Scenario: Foto HEIC del iPhone
<!-- test: tests/e2e_local.php:438 -->
<!-- duda: 10.8 -->
- **CUANDO** se sube una foto HEIC
- **ENTONCES** se acepta y queda con mime `image/heic` (se guarda y se descarga; no se convierte)

#### Scenario: Archivo vacío
- **CUANDO** el archivo tiene 0 bytes
- **ENTONCES** se rechaza con "El archivo está vacío."

---

### Requirement: Tope de tamaño por archivo
<!-- id: Archivos.guardar -->
<!-- entidades: Archivo -->
<!-- impuesto_en: app/Support/Archivos.php Archivos::MAX_BYTES (25 MB), Archivos::MAX_VIDEO (100 MB), Archivos::MUY_GRANDE -->
<!-- decision: 5.6 | 2.7 -->
<!-- duda: 5.6 -->

Cada archivo DEBE pesar como máximo 25 MB, salvo los videos, que admiten hasta 100 MB; superar el tope DEBE responder 413 con el tope en el mensaje.

#### Scenario: Documento de 26 MB
<!-- test: tests/e2e_local.php:588 -->
- **CUANDO** se sube un PDF de 26 MB
- **ENTONCES** responde 413 "El archivo supera 25 MB (los videos admiten hasta 100 MB)."

#### Scenario: Cualquier archivo de más de 100 MB
- **CUANDO** el archivo supera 100 MB, sea del tipo que sea
- **ENTONCES** responde 413 "El archivo supera 100 MB." sin llegar a detectar el tipo

#### Scenario: Archivo mayor que el límite del servidor
<!-- test: tests/e2e_local.php:624 -->
- **CUANDO** el archivo supera el límite por archivo del servidor (`upload_max_filesize`)
- **ENTONCES** responde 413 "El archivo supera el tamaño que admite el servidor (<n> MB)." (o KB si es menos de 1 MB), no 422

#### Scenario: Recepción incompleta
- **CUANDO** el archivo no llegó completo o no llegó ningún archivo
- **ENTONCES** responde 422 "El archivo no se pudo recibir completo. Intente de nuevo." o "No llegó ningún archivo."

---

### Requirement: Petición más grande que el máximo del servidor
<!-- id: LimitesSubida.peticionExcedida -->
<!-- entidades: Archivo -->
<!-- impuesto_en: app/Support/LimitesSubida.php; app/Support/Auth.php Auth::requerirAjax() -->
<!-- duda: 7.6 -->

Si un POST llega vacío porque su tamaño declarado (`Content-Length`) supera el máximo por petición del servidor, el endpoint DEBE responder 413 con el máximo en MB (redondeado hacia abajo) o en KB si es menos de 1 MB, antes de comprobar CSRF, y sin hablar de sesión expirada ni de la visita.

#### Scenario: Subida de 2 MB con máximo de 1 MB
<!-- test: tests/e2e_local.php:636 -->
- **CUANDO** se envían 2 MB a un servidor con `post_max_size=1M`
- **ENTONCES** responde 413 "Lo que intenta subir supera el máximo que admite el servidor (1 MB por envío). Suba menos archivos a la vez o archivos más livianos."

#### Scenario: Notación del límite
<!-- test: tests/limites_subida_test.php:32 -->
- **CUANDO** el límite vale `120M`, `110m`, `1G`, `512K`, `2048` o ` 8M `
- **ENTONCES** equivale a 125829120, 115343360, 1073741824, 524288, 2048 y 8388608 bytes; `''`, `0`, `-1`, `abc` o `M` equivalen a 0 (sin límite)

#### Scenario: Borde del límite
<!-- test: tests/limites_subida_test.php:36 -->
<!-- test: tests/limites_subida_test.php:37 -->
<!-- test: tests/limites_subida_test.php:38 -->
- **CUANDO** el tamaño es igual al máximo, un byte mayor, o el máximo es 0
- **ENTONCES** igual no excede, un byte más sí excede, y sin límite nunca excede

#### Scenario: Mensaje
<!-- test: tests/limites_subida_test.php:44 -->
<!-- test: tests/limites_subida_test.php:45 -->
<!-- test: tests/limites_subida_test.php:47 -->
<!-- test: tests/limites_subida_test.php:51 -->
- **CUANDO** el máximo es 110,86 MB o 512 KB
- **ENTONCES** el mensaje dice "110 MB" o "512 KB" (nunca "0 MB") y no menciona la visita ni "expiró"
<!-- incierto: un envío sin Content-Length (chunked) que supere el máximo no se detecta como 413 (prioridad 2 de la fase 0 no corregida) -->

---

### Requirement: Varios archivos en una sola petición, todos o ninguno
<!-- id: ControladorChecklist.subir -->
<!-- entidades: Archivo, Visita -->
<!-- impuesto_en: controladores/checklist.controlador.php ControladorChecklist::subir(), ControladorChecklist::maxArchivos() (MAX_ARCHIVOS_PETICION=20) -->
<!-- decision: 3 | Anexo 5, prueba 12 -->

El soporte de una parte del checklist DEBE admitir uno o varios archivos en la misma petición, hasta el menor entre 20 y el máximo de archivos por petición del servidor; si uno falla, no se guarda ninguno, se borran del disco los ya escritos y se dice cuál falló.

#### Scenario: Tres archivos a la vez
<!-- test: tests/e2e_local.php:714 -->
- **CUANDO** el auditor sube 3 PDF a la misma clave en una sola petición
- **ENTONCES** responde 200 "3 archivos guardados." con la lista `archivos` (id, nombre, mime, tamaño, url) y `archivo` = el primero

#### Scenario: Uno no válido
<!-- test: tests/e2e_local.php:719 -->
- **CUANDO** en la misma petición va un PDF válido y un archivo PHP
- **ENTONCES** responde 422 "malo.php: Tipo de archivo no permitido (…) No se guardó ninguno de los archivos." y la visita conserva los mismos archivos que antes

#### Scenario: Más archivos de los permitidos o llegaron menos de los enviados
<!-- test: tests/e2e_local.php:729 -->
- **CUANDO** se envían 21 archivos, o el campo `cantidad` no coincide con los que llegaron
- **ENTONCES** responde 422 "Se pueden subir hasta 20 archivos a la vez (se enviaron N y llegaron M). No se guardó ninguno: súbalos en varias tandas."

#### Scenario: La visita deja de admitir cambios a mitad de la subida
- **CUANDO** al registrar los archivos la visita ya no admite escrituras (cerrada o con el acta enviándose)
- **ENTONCES** se revierte el registro, se borran los archivos del disco y se responde como cualquier escritura no admitida (423 o 403)

#### Scenario: Reemplazar
- **CUANDO** la subida llega con `reemplazar=1`
- **ENTONCES** en la misma transacción se marcan eliminados los archivos vigentes de esa clave y se registran los nuevos

---

### Requirement: Un solo archivo en otros flujos
<!-- id: ControladorSolicitudes.registrar -->
<!-- entidades: Archivo, Solicitud, OplSoporte -->
<!-- impuesto_en: controladores/solicitudes.controlador.php (escrito de la solicitud); controladores/opl.controlador.php ControladorOpl::subirSoporte() -->

El escrito de una solicitud de visita y cada soporte de una objeción del OPL DEBEN subirse de a un archivo con las mismas reglas de tipo y tamaño; el escrito DEBE ser PDF y, si el registro falla después de guardar el archivo, el archivo DEBE borrarse.

#### Scenario: Escrito que no es PDF
- **CUANDO** el escrito de la solicitud es un tipo permitido pero no PDF
- **ENTONCES** se borra del disco y se responde "El escrito debe ser un PDF."

#### Scenario: Tope de soportes de una objeción
- **CUANDO** el hallazgo ya tiene 10 soportes del OPL
- **ENTONCES** se rechaza con "Máximo 10 soportes por hallazgo."

---

### Requirement: Cómo se sirven los archivos
<!-- id: Archivos.servir -->
<!-- entidades: Archivo -->
<!-- impuesto_en: app/Support/Archivos.php Archivos::servir() -->

Un archivo DEBE servirse solo después de comprobar que el usuario tiene acceso a la visita (o al recurso) que lo contiene, con su tipo real, `X-Content-Type-Options: nosniff`, `Cache-Control: private, max-age=3600`, en línea si es imagen, video o PDF y como descarga en los demás casos, con el nombre original saneado (y en UTF-8 en `filename*`); todo lo que no sea PDF DEBE llevar `Content-Security-Policy: sandbox`.

#### Scenario: Documento Word
- **CUANDO** se pide un soporte DOCX de una visita a la que el usuario tiene acceso
- **ENTONCES** llega como descarga (`attachment`) con `Content-Security-Policy: sandbox`

#### Scenario: PDF
- **CUANDO** se pide un PDF
- **ENTONCES** llega en línea (`inline`) y sin `sandbox` (el visor del navegador no abre un PDF con sandbox)

#### Scenario: Sin acceso o archivo inexistente
- **CUANDO** el usuario no tiene acceso a la visita, el archivo no es de esa visita o no está en disco
- **ENTONCES** responde 404 "Archivo no encontrado." (o el motivo del acceso denegado)

---

### Requirement: Quitar un soporte
<!-- id: ControladorChecklist.eliminar -->
<!-- entidades: Archivo, Visita -->
<!-- impuesto_en: controladores/checklist.controlador.php ControladorChecklist::eliminar(); modelos/checklist.modelo.php ModeloChecklist::eliminarArchivos() -->

Quitar un soporte DEBE marcarlo eliminado (fecha en `eliminado`), no borrarlo del disco, y solo mientras la visita admita cambios; el borrado por prefijo DEBE limitarse a las obligaciones (`ob:`).

#### Scenario: Quitar un soporte propio
<!-- test: tests/e2e_local.php:582 -->
- **CUANDO** el auditor quita un soporte de su parte con la visita en ejecución
- **ENTONCES** responde 200 "Archivo quitado." y la bitácora registra `eliminacion`

#### Scenario: Prefijo de ítems
<!-- test: tests/e2e_local.php:726 -->
- **CUANDO** se pide borrar por prefijo una clave `it:`
- **ENTONCES** responde 422 "Archivo no válido."

---

### Invariant: Los archivos viven fuera de la carpeta web con nombre aleatorio
<!-- entidades: Archivo -->
<!-- impuesto_en: app/Support/Archivos.php Archivos::raiz(), Archivos::guardar(), Archivos::rutaAbsoluta() -->

Todo archivo DEBE guardarse bajo la carpeta de archivos (`ARCHIVOS_RUTA`, por defecto `storage/visitas`), en una subcarpeta por recurso (`visita_<id>`, `visita_<id>/actas`, `visita_<id>/opl`, `solicitudes`), con nombre `AAAAMMDD_HHMMSS_<16 hex aleatorios>.<ext>`; una ruta relativa con `..` o fuera del patrón `^[\w\-/]+\.[a-z0-9]+$` DEBE rechazarse.

---

### Invariant: Cada archivo guarda su huella
<!-- entidades: Archivo -->
<!-- impuesto_en: Database/Structure/006_checklist_visita.sql visita_archivos (nombre, ruta, mime, tamano, sha256, subido_por, eliminado) -->

Cada archivo registrado DEBE guardar nombre original, ruta relativa, tipo real detectado, tamaño, SHA-256 del contenido guardado y quién lo subió; quitarlo no borra la fila.

---

### Invariant: Lo generado por el servidor pasa por las mismas reglas
<!-- entidades: Archivo, Acta -->
<!-- impuesto_en: app/Support/Archivos.php Archivos::guardarContenido() -->

Un contenido generado en el servidor (p. ej. el PDF del acta) DEBE guardarse con las mismas validaciones de tipo y tamaño que un archivo subido y devolver ruta, tipo, tamaño y SHA-256.

<!-- decision 2.5 · Word, Excel y video no viajan en el correo del acta: se mencionan en el acta y quedan en la aplicación (ver acta-envio) -->
<!-- pendiente: vistas/js/checklist.js (subida desde el navegador), controladores/checklist.controlador.php (archivosPagina, servirArchivoAnterior, documentos del acta), modelos/checklist.modelo.php (agregarArchivo), controladores/solicitudes.controlador.php (servir escrito), controladores/opl.controlador.php (servirSoporte) -->
