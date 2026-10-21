# Especificación: flujo-opl

> Extraída por spec-miner el 2026-10-21 del PHP de referencia (develop, commit db2e330).
> Fuentes: app/Support/Opl.php, app/Support/Plazos.php, app/Support/Seguimiento.php (hallazgos), controladores/opl.controlador.php, modelos/opl.modelo.php, ajax/opl.ajax.php, ajax/opl_publico.ajax.php, scripts/plazos_opl.php, controladores/checklist.controlador.php (líneas 587-1088: acta y envío, cerrar), modelos/checklist.modelo.php (guardarHallazgos, hallazgos), app/Support/ResumenChecklist.php (faltantesEnvio), app/Support/ActaHtml.php (hallazgos, plazos, QR, 5W2H), Database/Structure/006_checklist_visita.sql, Database/Structure/007_opl.sql, mails/acta_visita.html, tests/opl_test.php, tests/e2e_local.php, tests/ui/ui_fase4.js, docs/decisiones_2026-10-15.md, docs/dudas_pendientes.md (fase 4, dudas 11.x).
> **Mandan sobre el PHP** las decisiones de `docs/decisiones_2026-10-20.md` (marcadas `<!-- decision: 2026-10-20 X.Y -->`): el texto describe el comportamiento NUEVO.
> Relacionadas: calificación y regla de hallazgo en `checklist`; generación y destinatarios del acta en `acta-envio`; visitas de seguimiento, prórrogas, «Prestadores con hallazgos» y cierre del plan en `seguimiento`; días hábiles en `catalogos-festivos-plazos`.
> Estado en Java (S3): `visitas_respuestas` (una fila por ítem: `calificacion` C/NC/NA + `hallazgo`), `visitas_actas` (una por ronda) y `ServicioActa.enviar()` (genera, envía y cierra). **No existen** el plan 5W2H, las obligaciones de la minuta (parte OBL), la tabla de hallazgos al cierre, el enlace QR ni nada del OPL: ver «Datos que la reescritura Java debe agregar» al final.

---

### Requirement: Hallazgos de la visita al cerrarla
<!-- id: Seguimiento.hallazgos | ModeloChecklist.guardarHallazgos -->
<!-- entidades: Visita, VisitaHallazgo -->
<!-- impuesto_en: app/Support/Seguimiento.php hallazgos(); controladores/checklist.controlador.php cerrar(); modelos/checklist.modelo.php guardarHallazgos() -->
<!-- decision: 7 | 17 -->

Al cerrar la visita (misma transacción que el cambio a `cerrada`) el sistema DEBE guardar sus hallazgos reemplazando los anteriores: cada obligación de la minuta del prestador evaluado (`opl` o `fa` si la unidad es FA) en NC con clave `ob:<id>` y origen `obligacion`, y cada ítem NC de los componentes activos con clave `it:<código>` y origen `item`, **salvo** el ítem con subvalidación marcada `SI` (habilitación en trámite); cada uno con código, texto (obligación o punto), hallazgo (evidencia), fecha fin y estado de su fila del 5W2H (`Pendiente` si no hay). En un seguimiento solo entran las claves que se verificaron.

#### Scenario: Obligación e ítem NC; la habilitación en trámite no
<!-- test: tests/seguimiento_test.php:37 -->
- **CUANDO** la visita tiene OB-OPL-01 en NC, HB-02 en NC y un ítem NC con subvalidación `SI`
- **ENTONCES** los hallazgos son `['ob:OB-OPL-01', 'it:HB-02']`

#### Scenario: Se guardan al cerrar
<!-- test: tests/e2e_local.php:491 -->
- **CUANDO** se envía el acta de una visita con un hallazgo y se cierra
- **ENTONCES** `visita_hallazgos` tiene exactamente `ob:OB-OPL-01`

---

### Requirement: Enlace de respuesta (QR) al enviar un acta con hallazgos
<!-- id: ControladorChecklist.enviar (paso 2 y 3) | ModeloOpl.guardarAcceso -->
<!-- entidades: OplAcceso, VisitaActa -->
<!-- impuesto_en: controladores/checklist.controlador.php enviar(); modelos/opl.modelo.php guardarAcceso(), quitarAcceso() -->
<!-- decision: 25 | 18 | duda 11.6 -->

Si el acta tiene hallazgos, al enviarla el sistema DEBE crear un token aleatorio de 64 hexadecimales, poner en el acta el código QR (PNG) del enlace `opl-responder?t=<token>` con su fecha de vencimiento, y en la transacción de reserva del envío guardar (o reemplazar, uno por visita) `opl_accesos` con la huella SHA-256 del token, el acta, la fecha de envío y los dos vencimientos; el token en claro solo viaja en el acta y en el correo. Si el acta nueva no tiene hallazgos, DEBE borrar el enlace anterior (lo respondido queda como evidencia).

#### Scenario: Sin hallazgos no hay enlace
<!-- test: tests/e2e_local.php:1003 -->
- **CUANDO** se envía el acta de una visita sin hallazgos
- **ENTONCES** el correo no trae enlace y no hay fila en `opl_accesos`

#### Scenario: Acta reenviada tras reabrir
<!-- test: tests/e2e_local.php:1132 -->
- **CUANDO** la visita se reabre y se envía el acta nueva con hallazgos
- **ENTONCES** hay un token nuevo, el anterior responde 404 y los plazos corren desde el nuevo envío

---

### Requirement: Plazos en el correo y en el acta, en forma destacada
<!-- id: ControladorChecklist.enviar (paso 4) | ActaHtml.generar -->
<!-- entidades: VisitaActa -->
<!-- impuesto_en: controladores/checklist.controlador.php enviar(); app/Support/ActaHtml.php sección «Hallazgos y plazos» -->
<!-- decision: 2026-10-20 11.5 | 2026-10-20 11.2 | 18 -->

Con hallazgos, el acta y el cuerpo del correo DEBEN decir el enlace de respuesta, la fecha límite para aceptar u objetar (3 días hábiles) y, **en forma destacada**, que el plazo máximo para remitir el plan de mejoramiento firmado es de 5 días hábiles desde el envío del acta, con la fecha, y que vencido no se recibe. DEBEN decir que el código de 6 dígitos se envía **al correo del OPL** (no al del punto). La huella SHA-256 del acta va al pie del correo.

#### Scenario: Correo del acta con hallazgos
<!-- test: sin prueba en el PHP (texto nuevo) -->
- **CUANDO** se envía un acta con hallazgos el jueves 8-oct-2026 (lunes 12 festivo)
- **ENTONCES** el correo dice, destacado, «plazo máximo: 5 días hábiles, hasta el vie 16 de oct de 2026», da el 14-oct para objetar y dice que el código llega al correo del OPL

---

### Requirement: Cálculo de los plazos del OPL
<!-- id: Opl.plazos | Opl.fase -->
<!-- entidades: OplAcceso -->
<!-- impuesto_en: app/Support/Opl.php plazos(), fase(); Opl::DIAS_OBJECIONES = 3, Opl::DIAS_PLAN = 5 -->
<!-- decision: 8 | 18 | duda 11.1 (cerrada sin cambio 2026-10-20) -->

Desde el día del envío del acta (sin contarlo) DEBEN correr a la vez 3 días hábiles para aceptar u objetar y 5 para el plan, ambos hasta las 23:59:59 del último día hábil (festivos y ajustes de `par_festivos`). La fase DEBE ser `objeciones` hasta el primer vencimiento, `plan` hasta el segundo y `vencido` después.

#### Scenario: Envío un jueves con lunes festivo
<!-- test: tests/opl_test.php:26 -->
- **CUANDO** el acta sale el 2026-10-08 16:30 y el 12-oct es festivo
- **ENTONCES** objeciones vence `2026-10-14 23:59:59` y plan `2026-10-16 23:59:59`

#### Scenario: Fases
<!-- test: tests/opl_test.php:28 -->
- **CUANDO** se consulta el 14-oct 10:00, el 15-oct 08:00 y el 17-oct 00:00:01
- **ENTONCES** la fase es `objeciones`, `plan` y `vencido`

---

### Requirement: Página pública del enlace del acta
<!-- id: ControladorOpl.paginaPublica -->
<!-- entidades: OplAcceso -->
<!-- impuesto_en: controladores/opl.controlador.php paginaPublica(), accesoAbierto() -->
<!-- decision: 25 | duda 11.6 -->

La página `opl-responder?t=…` DEBE abrirse sin usuario solo si el token tiene el formato, su huella existe, no pasó `vence_plan` y la visita está `cerrada`; entonces abre la sesión del QR limitada a esa visita (recargar con el mismo enlace conserva la verificación; otro enlace empieza de cero) y registra «enlace del acta abierto» en la bitácora. Si no, responde 404 (enlace inválido) o 410 (vencido: «comuníquese con la interventoría»; visita reabierta: «use el QR del acta nueva»).

#### Scenario: Enlace válido e inventado
<!-- test: tests/e2e_local.php:1034, tests/e2e_local.php:1035 -->
- **CUANDO** se abre el enlace del acta / un token inventado de 64 caracteres
- **ENTONCES** responde 200 con el número de la visita / 404

#### Scenario: Vencido y reabierta
<!-- test: tests/e2e_local.php:1120, tests/e2e_local.php:1126 -->
- **CUANDO** pasó `vence_plan` / la visita se reabrió
- **ENTONCES** responde 410; en la reabierta el texto dice «reabrió» y el OPL con usuario tampoco la ve

---

### Requirement: Correo al que se envía el código de 6 dígitos
<!-- id: ControladorOpl.correosCodigo -->
<!-- entidades: Opl, Punto -->
<!-- impuesto_en: controladores/opl.controlador.php correosCodigo() -->
<!-- decision: 2026-10-20 11.2 | 25 -->

La lista de correos para el código DEBE ser **solo la del OPL** (correos del contrato en el catálogo `opl`), válidos y sin repetir, mostrados enmascarados (primera y última letra del usuario, `a*******z@dominio`; si el usuario tiene 2 letras o menos, la primera y asteriscos). El PHP agregaba los correos del punto visitado: eso se quita.

#### Scenario: Solo el OPL
<!-- test: tests/e2e_local.php:1037 (con el cambio: ya no incluye el contacto del punto) -->
- **CUANDO** el punto tiene `esm@…` y el OPL `opl@…`
- **ENTONCES** la página lista solo `o*l@…` y nunca la dirección completa

#### Scenario: Enmascarado
<!-- test: tests/opl_test.php:57 -->
- **CUANDO** se enmascara `ana.perez@opl.com.co` / `ab@x.co`
- **ENTONCES** queda `a*******z@opl.com.co` / `a*@x.co`

---

### Requirement: Envío del código de verificación
<!-- id: ControladorOpl.enviarCodigo -->
<!-- entidades: OplCodigo -->
<!-- impuesto_en: controladores/opl.controlador.php enviarCodigo(); app/Support/Opl.php nuevoCodigo(), huella(); Opl::MINUTOS_CODIGO = 15, CODIGOS_POR_HORA = 5, CODIGOS_POR_HORA_IP = 3 -->
<!-- decision: 25 | duda 11.10 -->

Al pedir el código para el correo elegido (por índice) el sistema DEBE rechazar con 429 si en la última hora ya salieron 5 códigos de la visita o 3 desde la misma IP; si no, generar 6 dígitos aleatorios, enviarlos por correo («vence en 15 minutos») y **solo si el correo salió** guardar en `opl_codigos` la huella SHA-256, el vencimiento (+15 min) y la IP, y dejar ese correo como pendiente de verificar. Si el correo falla responde 502 y no consume el tope. Cada intento queda en la bitácora con el correo enmascarado.

#### Scenario: El código sale
<!-- test: tests/e2e_local.php:1045 -->
- **CUANDO** se pide el código para el correo 0
- **ENTONCES** responde 200 «Le enviamos un código de 6 dígitos a o*l@…» y el correo trae 6 dígitos

#### Scenario: Código aleatorio guardado como huella
<!-- test: tests/opl_test.php:54, tests/opl_test.php:55 -->
- **CUANDO** se genera y se compara un código
- **ENTONCES** tiene 6 dígitos y solo valida contra su propia huella

---

### Requirement: Verificación del código
<!-- id: ControladorOpl.verificarCodigo -->
<!-- entidades: OplCodigo -->
<!-- impuesto_en: controladores/opl.controlador.php verificarCodigo(); modelos/opl.modelo.php codigoVigente() (FOR UPDATE), intentoCodigo(); Opl::INTENTOS_CODIGO = 5 -->
<!-- decision: 25 -->

La verificación DEBE usar el último código vigente y sin usar del correo pendiente, con la fila bloqueada; cada intento suma uno; a los 5 intentos responde 429 aunque el código sea correcto; uno errado responde 422 «no es correcto»; uno vencido o usado, 422 «venció o ya se usó». El acierto marca el código `usado`, regenera la sesión, fija el correo verificado y registra en la bitácora `opl_acceso` (ingreso) con el correo enmascarado.

#### Scenario: Errado, vencido, agotado y correcto
<!-- test: tests/e2e_local.php:1046, tests/e2e_local.php:1050, tests/e2e_local.php:1052, tests/e2e_local.php:1055 -->
- **CUANDO** se verifica un código errado / vencido / tras 5 intentos / el correcto
- **ENTONCES** 422 / 422 «venció» / 429 / 200 y acceso al detalle de esa visita

#### Scenario: Un código no sirve dos veces
<!-- test: tests/e2e_local.php:1056 -->
- **CUANDO** el código se verificó
- **ENTONCES** `opl_codigos.usado` no es nulo

---

### Requirement: Sesión del acceso por QR
<!-- id: ajax/opl_publico.ajax.php -->
<!-- entidades: OplAcceso -->
<!-- impuesto_en: ajax/opl_publico.ajax.php; App\Support\Sesion::vencida() -->
<!-- decision: 25 -->

Cada petición del QR DEBE exigir CSRF en los POST (419), que el enlace de la sesión siga abierto, con el mismo token y en plazo (si no: 401 y se borra la sesión del QR); detalle, acta, soportes, respuestas, plan y firma DEBEN exigir el código verificado (403 con `reverificar`); la verificación vence por inactividad con los mismos minutos de los usuarios (403 «pida un código nuevo; lo que escribió no se pierde»). Un soporte que no es de la visita da 404.

#### Scenario: Sin verificar
<!-- test: tests/e2e_local.php:1038 -->
- **CUANDO** sin código verificado se pide detalle, responder, firmar o descargar
- **ENTONCES** responde 403

#### Scenario: Soporte ajeno
<!-- test: tests/e2e_local.php:1057 -->
- **CUANDO** se pide por id un soporte de otra visita
- **ENTONCES** responde 404

---

### Requirement: Acceso del usuario OPL
<!-- id: ajax/opl.ajax.php | ControladorOpl.lista | ControladorOpl.visitaDeUsuario -->
<!-- entidades: Usuario, Visita -->
<!-- impuesto_en: ajax/opl.ajax.php Auth::requerirAjax(['OPL nacional','OPL regional']); controladores/opl.controlador.php lista() -->
<!-- decision: 16 | 34 -->

La pantalla «Respuesta a hallazgos» DEBE ser solo de los perfiles OPL nacional (país) y OPL regional (su regional); lista las visitas cerradas de su alcance con enlace vigente (número, fecha, punto, fuerza, hallazgos, sin respuesta, faltan en plan, plan firmado, fase y vencimientos). Una visita fuera de su alcance responde 404 como si no existiera; otro perfil, 403.

#### Scenario: Regional propia y ajena
<!-- test: tests/e2e_local.php:1007, tests/e2e_local.php:1026, tests/e2e_local.php:1029 -->
- **CUANDO** el OPL regional Centro, el de otra regional y el nacional consultan una visita de la regional Centro
- **ENTONCES** el primero y el nacional la ven; el otro recibe 404 y no le aparece en la lista

#### Scenario: Otros perfiles
<!-- test: tests/e2e_local.php:1009, tests/e2e_local.php:1023 -->
- **CUANDO** un auditor o un ESM llaman al endpoint del OPL
- **ENTONCES** responde 403

---

### Requirement: Detalle para responder
<!-- id: ControladorOpl.detalle -->
<!-- entidades: VisitaHallazgo, OplRespuesta, OplSoporte, OplPlan -->
<!-- impuesto_en: controladores/opl.controlador.php detalle() -->
<!-- decision: 7 -->

El detalle DEBE (después de aplicar la aceptación automática si venció la fase de objeciones) traer la visita, los plazos, la fase, el enlace al acta enviada y, por hallazgo: clave, código, texto, hallazgo, **la parte del 5W2H que diligenció el auditor** (causas, qué, por qué, dónde, inicio, fin, indicador; solo lectura), su estado, la respuesta del OPL, la decisión del líder y los soportes; además los que faltan en el plan y la firma del plan (nombre, cargo, documento, fecha).

#### Scenario: Pantalla del OPL
<!-- test: tests/ui/ui_fase4.js:45 -->
- **CUANDO** el OPL abre una visita
- **ENTONCES** ve los hallazgos, «3 días hábiles» y el recuadro de firma del plan

---

### Requirement: Aceptar u objetar cada hallazgo
<!-- id: ControladorOpl.responder | ModeloOpl.responder -->
<!-- entidades: OplRespuesta -->
<!-- impuesto_en: controladores/opl.controlador.php responder(); modelos/opl.modelo.php responder() (FOR UPDATE) -->
<!-- decision: 17 | 8 -->

Mientras el enlace esté abierto, en la fase `objeciones` y sin plan firmado, el OPL (usuario o QR) DEBE poder aceptar u objetar cada hallazgo de la visita, con nombre (3-150) y cargo (2-150) de quien responde; objetar exige justificación de 10 a 4000 caracteres. Puede cambiar su respuesta hasta que el líder decida; queda canal, usuario o correo verificado, IP y fecha; historial de la visita y bitácora (`opl_respuesta`). Una objeción avisa por correo al auditor líder. Errores: 404 sin acta vigente, 409 plan firmado, 422 hallazgo inexistente, fuera de plazo («quedaron aceptados»), decisión inválida o líder ya decidió.

#### Scenario: Objeción con justificación
<!-- test: tests/e2e_local.php:1013, tests/e2e_local.php:1017 -->
- **CUANDO** objeta con «corta» / con una justificación válida
- **ENTONCES** 422 / 200 y sale un correo al auditor líder

#### Scenario: Sin nombre y cargo
<!-- test: tests/e2e_local.php:1014 -->
- **CUANDO** acepta sin nombre ni cargo
- **ENTONCES** responde 422

#### Scenario: Por QR queda el registro
<!-- test: tests/e2e_local.php:1060 -->
- **CUANDO** acepta por el QR
- **ENTONCES** la respuesta queda con canal `qr`, el correo verificado y la IP

#### Scenario: Fuera de plazo o con plan firmado
<!-- test: tests/e2e_local.php:1104, tests/e2e_local.php:1077 -->
- **CUANDO** objeta después de los 3 días / con el plan firmado
- **ENTONCES** 422 / 409

---

### Requirement: Soportes de la objeción
<!-- id: ControladorOpl.subirSoporte -->
<!-- entidades: OplSoporte -->
<!-- impuesto_en: controladores/opl.controlador.php subirSoporte(); App\Support\Archivos::guardarSubido() ("visita_{id}/opl"); MAX_SOPORTES = 10 -->
<!-- decision: 17 -->

Solo para un hallazgo ya objetado y sin decisión del líder, en la fase `objeciones` y sin plan firmado, el OPL DEBE poder subir soportes (máximo 10 por hallazgo, límites generales de subida; 413 si excede) que se guardan con nombre, ruta, tipo, tamaño, SHA-256, quién (usuario o «QR correo») y fecha, y quedan en la bitácora (`opl_soporte`).

#### Scenario: Con y sin objeción
<!-- test: tests/e2e_local.php:1020, tests/e2e_local.php:1021 -->
- **CUANDO** sube un PDF a un hallazgo objetado / a uno aceptado
- **ENTONCES** 200 / 422 «Primero registre la objeción»

---

### Requirement: Aceptación automática a los 3 días hábiles
<!-- id: ControladorOpl.aceptarVencidos | ModeloOpl.aceptarAutomaticamente -->
<!-- entidades: OplRespuesta -->
<!-- impuesto_en: controladores/opl.controlador.php aceptarVencidos(); scripts/plazos_opl.php paso 1 -->
<!-- decision: 8 | duda 11.9 | duda 11.3 (cerrada sin cambio) -->

Vencida la fase de objeciones, todo hallazgo sin respuesta DEBE quedar aceptado (`decision = acepta`, `automatica = 1`, canal `sistema`), con historial «Aceptación automática». Lo hace la tarea diaria y también cualquier detalle, guardado del plan o firma del OPL antes de que ella corra. La tarea avisa una sola vez por acta al líder, con la lista de lo aceptado y la fecha límite del plan; si no hay nada, registra el aviso «sin pendientes». El líder no tiene plazo para decidir las objeciones.

#### Scenario: Tarea diaria
<!-- test: tests/e2e_local.php:1111 -->
- **CUANDO** corre la tarea después del vencimiento de las objeciones
- **ENTONCES** el hallazgo sin respuesta queda `acepta` con `automatica = 1`

#### Scenario: Antes de la tarea
<!-- test: tests/e2e_local.php:1106 -->
- **CUANDO** vencidos los 3 días y antes de la tarea, el OPL intenta firmar sin quién y cómo de lo no objetado
- **ENTONCES** responde 422 nombrando P05-05 (ya quedó aceptado y le falta el plan)

---

### Requirement: Quién y cómo del plan (parte del OPL)
<!-- id: ControladorOpl.guardarPlan -->
<!-- entidades: OplRespuesta, Plan5W2H -->
<!-- impuesto_en: controladores/opl.controlador.php guardarPlan(); app/Support/Opl.php faltanEnPlan() -->
<!-- decision: 7 | 2026-10-20 11.5 | duda 11.4 (cerrada sin cambio) -->

El OPL DEBE diligenciar quién (≤200) y cómo (≤4000) solo de los hallazgos aceptados, aceptados automáticamente o ratificados; los demás se ignoran. Todo se valida antes de escribir (todo o nada); queda en la bitácora (`opl_plan`) y responde lo que falta. Con el plan firmado solo se completa lo vacío (p. ej. un ratificado después), sin firma nueva. **Cambio 2026-10-20:** sin plan firmado, después de `vence_plan` ya no se guarda (el plan quedó «vencido»); el PHP lo permitía (decisión C).

#### Scenario: Plan completo
<!-- test: tests/e2e_local.php:1073 -->
- **CUANDO** guarda quién y cómo de todos los aceptados y ratificados
- **ENTONCES** responde 200 con `faltan = []`

#### Scenario: Qué cuenta para el plan
<!-- test: tests/opl_test.php:48 -->
- **CUANDO** hay un aceptado sin cómo, un objetado pendiente y un levantado
- **ENTONCES** falta solo el aceptado

<!-- incierto: la decisión 11.5 prohíbe la firma tardía; que tampoco se guarde quién y cómo sin firma después del plazo se deduce de ella. -->

---

### Requirement: Firma del plan de mejoramiento
<!-- id: ControladorOpl.firmar | ModeloOpl.firmarPlan -->
<!-- entidades: OplPlan -->
<!-- impuesto_en: controladores/opl.controlador.php firmar(); modelos/opl.modelo.php firmarPlan() (INSERT IGNORE) -->
<!-- decision: 19 | 2026-10-20 11.5 | 2026-10-20 11.8 -->

El OPL DEBE firmar el plan una vez por acta, solo **antes de `vence_plan`**, cuando: todo hallazgo tiene respuesta (dentro de la fase de objeciones; vencida, se aplica la aceptación automática), todo aceptado o ratificado tiene quién y cómo (un objetado pendiente no bloquea), nombre 3-150, cargo 2-150, firma PNG legible y, **por el QR, el número de documento** de quien firma. Queda nombre, cargo, documento, firma, canal, usuario o correo, IP y fecha; historial «Plan del OPL firmado», bitácora `opl_firma` y correo al líder. **Cambio 2026-10-20 11.5:** no hay firma tardía ni por QR ni con usuario OPL (desaparece `tardio`): después del plazo responde 422 «venció el plazo de 5 días hábiles; el plan quedó vencido y se reportó a la DIGSA».

#### Scenario: Sin quién y cómo
<!-- test: tests/e2e_local.php:1061 -->
- **CUANDO** firma por QR con hallazgos aceptados sin quién y cómo
- **ENTONCES** responde 422 «Falta quién y cómo de: …»

#### Scenario: Firma y doble firma
<!-- test: tests/e2e_local.php:1075, tests/e2e_local.php:1076 -->
- **CUANDO** firma con todo completo / vuelve a firmar
- **ENTONCES** 200 y fila en `opl_planes` con canal `qr` / 409

#### Scenario: Documento en la firma por QR
<!-- test: sin prueba en el PHP (decisión nueva) -->
- **CUANDO** firma por QR sin número de documento
- **ENTONCES** responde 422 «Escriba el número de documento de quien firma»

#### Scenario: Después del plazo
<!-- test: sin prueba en el PHP (decisión nueva) -->
- **CUANDO** el usuario OPL intenta firmar después de `vence_plan`
- **ENTONCES** responde 422 y no se crea la firma

<!-- incierto: 11.8 habla de la firma por QR; si el usuario OPL también debe dar documento no está decidido (recomendación: pedirlo en ambos canales). Longitud y formato del documento: no definidos (Java usa ≤30 en visitas_firmas). -->

---

### Requirement: El auditor líder ratifica o levanta la objeción
<!-- id: ControladorOpl.resolver | ModeloOpl.resolver -->
<!-- entidades: OplRespuesta, Visita -->
<!-- impuesto_en: controladores/opl.controlador.php resolver(); ajax/hallazgos.ajax.php accion=resolver -->
<!-- decision: 17 | duda 11.3 | 2026-10-20 11.7 -->

Solo el auditor líder de la visita (403 a cualquier otro, también DIGSA y coordinador) DEBE poder decidir, una sola vez y sin plazo, cada objeción pendiente: `ratifica` o `levanta` con motivo de 10 a 800 caracteres; queda quién y cuándo, historial («Hallazgo ratificado/levantado: motivo») y bitácora `opl_resolucion`. Lo levantado sale del plan y del seguimiento. Se avisa por correo **al OPL** (al ratificar le pide diligenciar quién y cómo). Si se levanta el último hallazgo vigente, se cierra el plan (ver `seguimiento`, «Cierre del plan al levantar el último hallazgo»).

#### Scenario: Solo el líder
<!-- test: tests/e2e_local.php:1064, tests/e2e_local.php:1175 -->
- **CUANDO** otro auditor de la visita / la DIGSA intentan resolver
- **ENTONCES** responde 403

#### Scenario: Ratifica una vez
<!-- test: tests/e2e_local.php:1068, tests/e2e_local.php:1069 -->
- **CUANDO** el líder ratifica / intenta luego levantar la misma objeción
- **ENTONCES** 200 / 422 «no tiene una objeción pendiente»

#### Scenario: Hallazgo no objetado
<!-- test: tests/e2e_local.php:1096 -->
- **CUANDO** el líder intenta resolver un hallazgo aceptado
- **ENTONCES** responde 422

<!-- incierto: el PHP avisa la decisión al OPL y al contacto del punto (correosCodigo). Con 11.2 (código solo al OPL) y 12.2 (el ESM no ve las objeciones) el aviso va solo al OPL; es deducción, no decisión expresa. -->

---

### Requirement: Plan no presentado: vencido y reporte formal a la DIGSA
<!-- id: scripts/plazos_opl.php (paso 2) | ModeloOpl.planesVencidosSinAviso -->
<!-- entidades: OplPlan, ReporteDigsa, OplAviso -->
<!-- impuesto_en: scripts/plazos_opl.php -->
<!-- decision: 2026-10-20 11.5 | 2026-10-20 Reunión 5 | 8 | C (reemplazada) -->

La tarea diaria (después de medianoche, sin dos corridas a la vez) DEBE tomar cada visita cerrada cuyo acta vigente pasó `vence_plan` sin plan firmado y con hallazgos vigentes, y: (1) dejar el plan en estado **«vencido»** y la alerta crítica; (2) generar el **reporte formal a la DIGSA y al auditor** con **consecutivo**, hallazgos, fechas (envío del acta, vencimientos), evidencias (acta con su huella, respuestas y soportes del OPL) y lo que faltó; (3) enviarlo a los correos de la DIGSA y al auditor líder y, si salió, dejar el plan en **«reportado a DIGSA»** con historial y aviso registrado una sola vez por acta. Si falla, sigue «vencido» y se reintenta al día siguiente (la tarea termina con código 1). Sin hallazgos vigentes (todo levantado) no se reporta. **La aplicación nunca inicia la multa.**

#### Scenario: Plan vencido
<!-- test: tests/e2e_local.php:1114 (PHP: aviso al auditor con copia a la DIGSA; ahora reporte con consecutivo) -->
- **CUANDO** corre la tarea y el plan venció sin firma
- **ENTONCES** sale el reporte N° consecutivo a la DIGSA y al auditor y el plan queda «reportado a DIGSA»

#### Scenario: Una sola vez por acta
<!-- test: tests/e2e_local.php:1116, tests/e2e_local.php:1134 -->
- **CUANDO** la tarea corre otra vez / se reenvía un acta nueva y vence de nuevo
- **ENTONCES** no se repite / se reporta el acta nueva

<!-- incierto: formato y alcance del consecutivo (anual, global; prefijo), si el reporte es un PDF adjunto o el cuerpo del correo, y si sin correos de la DIGSA el estado queda «vencido» (recomendación: sí, con historial). -->

---

### Requirement: Alerta crítica de plan vencido
<!-- id: ModeloOpl.conPlanVencido -->
<!-- entidades: OplPlan -->
<!-- impuesto_en: modelos/opl.modelo.php conPlanVencido() (dashboard) -->
<!-- decision: novena ronda 3.2 | 2026-10-20 11.5 -->

El dashboard DEBE contar como alerta crítica cada visita cerrada de su alcance con el plan del acta vigente en «vencido» o «reportado a DIGSA» y con hallazgos vigentes; como ya no hay firma tardía, la alerta no se apaga con una firma.

#### Scenario: Alerta
<!-- test: tests/e2e_local.php:1119 -->
- **CUANDO** una visita tiene el plan vencido
- **ENTONCES** `planesOplVencidos >= 1`

<!-- incierto: cuándo deja de mostrarse la alerta (al cerrar el plan en el seguimiento o al levantar todo); recomendación: mientras el plan no esté «cerrado». -->

---

### Requirement: Visita reabierta
<!-- id: ControladorOpl.accesoAbierto -->
<!-- entidades: OplAcceso, OplPlan -->
<!-- impuesto_en: controladores/opl.controlador.php accesoAbierto(); modelos/opl.modelo.php plan() (join por acta) -->
<!-- decision: 1 | duda 11.6 -->

Mientras la visita esté reabierta, el enlace y la pantalla del OPL NO DEBEN admitir respuestas. Al enviar el acta nueva, los plazos, los avisos, el estado y la firma del plan corren de nuevo (van por acta; la firma anterior se conserva); las respuestas a cada hallazgo se conservan.

#### Scenario: Reabierta
<!-- test: tests/e2e_local.php:1126 -->
- **CUANDO** el coordinador reabre la visita
- **ENTONCES** el enlace responde 410 y la visita sale de la lista del OPL

---

### Invariant: Estados de un hallazgo frente al OPL
<!-- entidades: OplRespuesta -->
<!-- impuesto_en: app/Support/Opl.php estadoHallazgo(), vigentes() -->
<!-- verificado_por: tests/opl_test.php:33, tests/opl_test.php:50 -->

El estado DEBE derivarse así: sin fila → `sin_respuesta`; `acepta` → `aceptado` (o `aceptado_auto` si es automática); `objeta` sin resolución → `objetado`; con `ratifica` → `ratificado`; con `levanta` → `levantado`. Vigentes = todos menos los levantados.

---

### Invariant: Estructura del plan 5W2H y quién diligencia cada parte
<!-- entidades: Plan5W2H, OplRespuesta -->
<!-- impuesto_en: controladores/checklist.controlador.php W5_COLUMNAS, W5_ESTADOS, W5_OPL, conservarOpl(); app/Support/ResumenChecklist.php faltantesEnvio() -->
<!-- verificado_por: tests/e2e_local.php:479-481, tests/e2e_local.php:1084 -->
<!-- decision: 6 | 7 | 19 -->

Cada hallazgo DEBE tener una fila del plan con: **parte del auditor** (en la visita) causas, qué, por qué, dónde, inicio, fin, indicador (con el texto de ayuda del Anexo 3 p.11 v), estado (`Pendiente`, `En curso`, `Completado`, `Retrasado`, `Cancelado`) y % de avance; **parte del OPL** (después) quién y cómo, que el servidor nunca toma del formulario del auditor. El plan lleva la fecha y dos firmas en la visita (auditor y «recibí los hallazgos» del auditado), obligatorias para enviar un acta con hallazgos, igual que la fecha fin de cada acción abierta; la firma del OPL (`opl_planes`) es la «configuración del plan». HOW MUCH no se usa.

---

### Invariant: Una firma del plan por acta
<!-- entidades: OplPlan -->
<!-- impuesto_en: Database/Structure/007_opl.sql opl_planes PRIMARY KEY (visita_id, acta_id) -->
<!-- verificado_por: tests/e2e_local.php:1076 -->

DEBE haber como máximo una firma del plan por (visita, acta); con el plan firmado no cambian las respuestas ni se suben soportes.

---

### Invariant: Secretos solo como huella
<!-- entidades: OplAcceso, OplCodigo -->
<!-- impuesto_en: Database/Structure/007_opl.sql token_hash, codigo_hash; app/Support/Opl.php huella(), codigoValido() (hash_equals) -->
<!-- verificado_por: tests/opl_test.php:56 -->

El token del enlace y el código DEBEN guardarse solo como SHA-256 y compararse en tiempo constante; la bitácora del QR lleva el correo enmascarado (Ley 1581).

---

### Invariant: Evidencia sin borrado
<!-- entidades: OplRespuesta, OplSoporte, OplPlan, OplAviso, OplCodigo -->
<!-- impuesto_en: Database/Structure/007_opl.sql (ON DELETE RESTRICT en todas) -->
<!-- verificado_por: tests/e2e_local.php:1188 -->

Respuestas, soportes, firmas, avisos y códigos del OPL DEBEN conservarse (sin borrado en cascada); solo el enlace (`opl_accesos`) se reemplaza o se quita con el acta nueva.

---

### Invariant: Un aviso fallido no deshace la acción
<!-- entidades: VisitaHistorial -->
<!-- impuesto_en: controladores/opl.controlador.php avisar(), avisarLider(), avisarOpl() -->

Cada aviso (objeción y firma al líder; decisión al OPL) DEBE salir con la plantilla de notificación («Visita N · punto»); si no hay destinatarios o el correo falla, la acción se conserva, el historial dice «Aviso al … no enviado / fallido» y la respuesta al usuario lo advierte.

---

### Invariant: La aplicación no inicia multas
<!-- entidades: ReporteDigsa -->
<!-- impuesto_en: docs/decisiones_2026-10-20.md Reunión 5 -->
<!-- decision: 2026-10-20 Reunión 5 -->

Ningún estado, tarea ni acción DEBE iniciar o calcular una multa: la aplicación solo reporta a la DIGSA.

---

## Datos que la reescritura Java debe agregar

Java hoy: `visitas_respuestas` (visita, ítem, parte `C00…|OBL|SIS`, calificación, hallazgo), `visitas_firmas` (roles `auditor|esm|opl`), `visitas_actas` (una por ronda, `estado` del envío), `archivos` (clave `visita|acta|it:…`), `visitas_resumen`. `ServicioActa.enviar()` no arma QR, no da plazos en el correo, no guarda hallazgos y usa siempre el asunto «Acta de visita de seguimiento» (el PHP: «Acta de visita» o «Acta de seguimiento a plan de mejoramiento»).

1. **Obligaciones de la minuta (parte OBL):** `visitas_obligaciones` (visita_id, prestador `opl|fa`, codigo `OB-…`, texto, calificacion, hallazgo, auto); `visitas_respuestas.item_id` apunta a `chk_items` y no sirve para ellas. `archivos.clave` debe admitir `ob:<código>` y `minuta:opl|fa`.
2. **Subvalidación REPS:** columna `subvalidacion` (SI/NO) en `visitas_respuestas`: sin ella no se excluye la habilitación en trámite de los hallazgos.
3. **Plan 5W2H (parte auditor):** `visitas_plan` (visita_id, clave, causas, que, porque, donde, inicio date, fin date, indicador, estado CHECK de los 5, avance 0-100, prorroga_justificacion) + `visitas_plan_meta` (fecha) y firmas `w5_auditor` y `w5_auditado` en `visitas_firmas.rol`.
4. **Hallazgos al cierre:** `visitas_hallazgos` (visita_id, acta_id, clave, codigo, origen `obligacion|item`, texto, hallazgo, fin, estado_plan), reemplazados en cada cierre.
5. **Enlace QR:** `opl_accesos` (visita_id PK, token_hash char(64) UNIQUE, acta_id → visitas_actas, enviado, vence_objeciones, vence_plan timestamptz).
6. **Códigos:** `opl_codigos` (id, visita_id, correo, codigo_hash, creado, vence, intentos, usado, ip inet).
7. **Respuestas:** `opl_respuestas` (visita_id, clave, decision `acepta|objeta`, automatica, justificacion, quien, como, nombre, cargo, canal `qr|usuario|sistema`, usuario_id, correo, ip, fecha, resolucion `ratifica|levanta`, resolucion_motivo, resuelto_por, resuelto_fecha).
8. **Soportes:** `opl_soportes` o `archivos` con clave `opl:<clave>`; en ambos casos `subido_por_correo` (el QR no tiene usuario y `archivos.subido_por` es FK a `usuarios`).
9. **Plan del OPL y su estado:** `opl_planes` (visita_id, acta_id, nombre, cargo, **documento**, firma bytea, canal, usuario_id, correo, ip, fecha; sin `tardio`) y un estado por acta `pendiente|firmado|vencido|reportado_digsa|cerrado` con `vencido_en`, `reportado_en`, `reporte_id`, `cerrado_en`, `cerrado_por`, `motivo_cierre`.
10. **Reporte formal:** `reportes_digsa` (id, consecutivo UNIQUE, visita_id, acta_id, motivo `plan_no_presentado|plan_incumplido`, contenido jsonb, archivo_id del PDF si lo hay, destinatarios text[], estado, enviado, error).
11. **Avisos de la tarea:** `opl_avisos` (visita_id, acta_id, tipo `aceptacion_automatica|plan_vencido|reporte_digsa`, fecha, resultado) PK (visita_id, acta_id, tipo), y la **tarea diaria programada** (aceptación automática y vencidos) con bloqueo contra corridas simultáneas (p. ej. `pg_try_advisory_lock`).

<!-- pendiente: vistas/opl_responder.php, vistas/js/opl.js (pantalla: redibujo que pierde lo escrito), app/Support/ActaCorreos.php (firmaValida), controladores/dashboard (uso de conPlanVencido), tests/ui/ui_fase4.js completo -->
