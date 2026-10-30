# Especificación: analitica-ingesta

> Escrita por spec-miner el 2026-10-22 y actualizada el 2026-10-23 con lo construido en A1 tras la revisión. **Sin PHP de referencia: sale de la fuente de verdad, el mockup y el diseño de la sesión.**
> Fuentes: `FUENTE DE VERDAD/md/` (A4 = Anexo 4; A5 = Anexo 5; RESP = Respuestas a observaciones, texto OCR: ante diferencias manda el PDF); `MOCKUP/Mockup_Modulos_DIGSA.html` (bitácora de cargues en `#interop`, trazabilidad en `#homologacion`); `EVALUACION_SOFTWARE/Hoja_ruta_prueba_en_sitio_DIGSA_2026-10-22.xlsx` (casos M8-3 y M10-7, Preparación 12 y 13, etapa E0); diseño común del módulo (marcado `<!-- decision: diseño 2026-10-22 -->`, manda en lo que la DIGSA no fija); revisión de A1 (marcado `<!-- decision: revisión A1 2026-10-23 -->`).
> Relacionadas: `analitica-homologacion` (tabla maestra, referencias oficiales, estado de homologación de cada hecho), `analitica-tableros` (agregados V62, filtros, corte en las consultas, descargas, rastreo 14.9), `analitica-cohortes-alertas` (recalculo al aplicar un cargue), `analitica-asistente` (consulta sobre los mismos hechos).
> Estado en Java: **A1 construida** en el worktree `digsa-java-analitica` (rama `analitica`, sin commit): V60, paquete `analitica/` y `analitica/ingesta/`, `reglas/AnaliticaIngesta`, pantalla `CarguesOpl.tsx`, e2e `analitica-cargues.spec.ts`, `herramientas/totales_insumo.py`. Pendiente: `docs/rendimiento_analitica.md` (M10-7) y los pasos de aplicación de A3 (agregados).

---

### Requirement: Puerto único de consumo del insumo del OPL
<!-- id: FuenteDispensacion -->
<!-- entidades: Cargue -->
<!-- impuesto_en: A5 L552 (p. 21) 4.7.1 punto 1; A5 L242 (p. 9) 1.1; RESP L827 (p. 33) AGS-64; A5 L948 (p. 33) 14.3 -->
<!-- decision: diseño 2026-10-22 (puerto con adaptadores ArchivoDispensacion y ServicioWebOpl) -->

El módulo DEBE consumir la información de dispensación del OPL por archivo estructurado y por servicio web, ambos a través del puerto `FuenteDispensacion`, de modo que validación, carga, bitácora y trazabilidad sean idénticas sea cual sea el mecanismo; la fuente queda registrada en el cargue («Archivo CSV (;) UTF-8», «Archivo xlsx», «API OPL · dispensación»).

#### Scenario: Mismo insumo por los dos caminos
<!-- test: por escribir: IngestaApiTest.mismoResultadoArchivoYServicio -->
- **CUANDO** el mismo conjunto de registros se carga una vez por archivo y otra por el servicio web (en bases distintas)
- **ENTONCES** recibidos, procesados, rechazados, motivos y totales de gasto, unidades y pacientes son iguales; solo cambia la fuente

### Requirement: Formatos de archivo aceptados y topes del lector
<!-- id: ArchivoDispensacion.abrir / LectorCsv / LectorXlsx -->
<!-- entidades: Cargue -->
<!-- impuesto_en: RESP L2279 (p. 87) GMI P77 f («;», coma decimal, UTF-8); RESP L827 (p. 33) AGS-64; RESP L2017 (p. 77) (1,5 a 2 M registros) -->
<!-- decision: diseño 2026-10-22 (detección de «;» o «,», BOM, .gz/.zip, xlsx SAX con varias hojas) -->
<!-- decision: revisión A1 2026-10-23 (topes de celda, registro, textos de xlsx y descomprimido) -->

El adaptador de archivo DEBE leer en flujo: CSV y TXT con «;» o «,» detectado en el encabezado, UTF-8 con o sin BOM, coma decimal con «;» (punto con «,»); los mismos en `.gz` o `.zip` (un solo archivo de datos); y xlsx con SAX, uniendo hojas con el mismo encabezado (otra firma: rechazo «hojas distintas»). La línea de origen es la física del archivo (en xlsx, «hoja:fila»). Topes, todos parámetros de `digsa.analitica`: archivo y contenido descomprimido 4096 MB; celda del CSV 64 KB y registro 1 MB (más, o una comilla sin cerrar al final, es rechazo estructural «formato» con la línea donde empezó el registro: evita que una comilla abierta se trague el resto del archivo); textos compartidos del xlsx 256 MB sin comprimir (se leen en memoria). xlsx dañado o con contraseña: rechazo «formato».

#### Scenario: CSV oficial de la DIGSA
<!-- test: AnaliticaIngestaTest.detectaPuntoYComa; ArchivoDispensacionTest.csvPuntoYComa -->
- **CUANDO** llega un CSV con «;», BOM y valores `1234,50`
- **ENTONCES** se detecta «;», el BOM no entra en el primer encabezado y el valor se lee como 1234.50

#### Scenario: xlsx de dos hojas
<!-- test: ArchivoDispensacionTest.xlsxVariasHojas, xlsxHojasDistintas -->
- **CUANDO** un xlsx trae dos hojas con el mismo encabezado
- **ENTONCES** se reciben las filas de ambas con línea «hoja:fila»; si los encabezados difieren, el archivo se rechaza

#### Scenario: Comilla sin cerrar
<!-- test: ArchivoDispensacionTest.comillaSinCerrar, topesDeCeldaYRegistro; IngestaApiTest.comillaSinCerrar -->
- **CUANDO** una celda abre comillas y no las cierra, o pasa de 64 KB
- **ENTONCES** el cargue queda «rechazado» por estructura con la línea donde empezó el registro, sin hechos

### Requirement: Subida por partes con tamaño exacto y verificación de integridad
<!-- id: ServicioCargues.crear / recibirParte / cerrar -->
<!-- entidades: Cargue, CarguePart -->
<!-- impuesto_en: RESP L2017 (p. 77) (volumen); A4 L382–384 (p. 14) (soportes del origen) -->
<!-- decision: diseño 2026-10-22 (partes ≤ 50 MB al Almacen; no se toca el multipart global de 110 MB) -->
<!-- decision: revisión A1 2026-10-23 (partes exactas, máximo 3 cargues abiertos por usuario) -->

Al crear el cargue el navegador declara nombre, tamaño, SHA-256 y número de partes, que DEBE ser exactamente `ceil(tamaño / 50 MB)` (422 si no). Cada parte se escribe en flujo al `Almacen` y DEBE medir exactamente 50 MB salvo la última, que lleva el resto (422 «La parte n debe tener … bytes»; nunca se escribe más que lo declarado); repetir una parte la reemplaza. Al cerrar, el servidor ensambla, calcula el SHA-256 y lo compara con el declarado: si no coincide o falta una parte, el cargue queda «fallido» (422). Cada usuario tiene como máximo 3 cargues abiertos (subiendo o mapeo); el cuarto recibe 409 «Tiene 3 cargues sin terminar… Termínelos o descártelos». La pantalla muestra el avance.

#### Scenario: Archivo de 700 MB
<!-- test: IngestaApiTest.subidaPorPartes; AnaliticaIngestaTest.partesDeLaSubida -->
- **CUANDO** se suben 14 partes de 50 MB y se cierra con el SHA-256 correcto
- **ENTONCES** el cargue pasa a «mapeo» con el SHA-256 y el tamaño guardados

#### Scenario: Parte de otro tamaño y cuarto cargue abierto
<!-- test: IngestaApiTest.partesExactasYCarguesAbiertos -->
- **CUANDO** una parte intermedia llega con menos bytes, o el usuario ya tiene 3 cargues en subida o mapeo
- **ENTONCES** 422 sin guardar la parte; 409 sin crear el cargue

#### Scenario: Huella distinta
<!-- test: IngestaApiTest.huellaDistinta -->
- **CUANDO** una parte llegó alterada
- **ENTONCES** 422 «El archivo llegó incompleto o alterado; vuelva a subirlo» y el cargue queda «fallido»

### Requirement: Servicio web simulado del OPL con lotes durables
<!-- id: OplDispensacionApi / ServicioWebOpl -->
<!-- entidades: Cargue, LoteWs -->
<!-- impuesto_en: A5 L948 (p. 33) 14.3 («simulando el web service del OPL»); RESP L4513–4527 (p. 174–175); A5 L432 (p. 17) 4.1; RESP L4395 (p. 170) -->
<!-- decision: diseño 2026-10-22 (POST /api/analitica/opl/dispensacion, lotes JSON, perfil OPL_NACIONAL) -->
<!-- decision: revisión A1 2026-10-23 (lotes durables con SHA-256, secuencia 1..N, 409/422/413, claves desconocidas como aviso) -->

`POST /api/analitica/opl/dispensacion`, solo OPL_NACIONAL, DEBE recibir lotes JSON (`envio`, `numero` 1..10 000, `final`, `registros[]` con los campos del insumo). Cada lote se guarda durable antes de responder: en el `Almacen` (solo con las claves del modelo) y en `ana_ws_lotes` con el SHA-256 del cuerpo; staging se llena solo cuando el envío está completo (una caída de la base no pierde lotes). Reglas: el mismo lote con el mismo SHA-256 responde lo mismo; con otro contenido, 409; un envío cerrado o de otra cuenta, 409; un lote posterior al final declarado, otro final distinto o un final menor que un lote ya recibido, 422; JSON inválido, 422. El envío se cierra y entra a la cola cuando llegaron **todos** los lotes 1..N (N = número del lote final). Las claves que no son del modelo se ignoran y quedan como aviso (solo sus nombres) en la respuesta, en `ana_cargues.avisos` y en la bitácora. Un cuerpo de más de 64 MB responde 413 sin leerlo entero. Responsable: «Proceso automático · API OPL (usuario)». Un envío sin lotes nuevos vence como cualquier cargue abierto.

#### Scenario: Envío en varios lotes
<!-- test: IngestaApiTest.servicioWebPorLotes -->
- **CUANDO** OPL_NACIONAL envía los lotes de un envío, el último con `final = true`
- **ENTONCES** se crea un cargue con fuente «API OPL · dispensación» que pasa a «pendiente» y se procesa como un archivo

#### Scenario: Secuencia incompleta o lote alterado
<!-- test: IngestaApiTest.servicioWebSecuencia -->
- **CUANDO** llega el final 3 sin el lote 2, o se reenvía el lote 1 con otro contenido
- **ENTONCES** el envío sigue abierto hasta recibir el 2; el reenvío alterado recibe 409

#### Scenario: Perfil no autorizado
<!-- test: IngestaApiTest.perfiles -->
- **CUANDO** un ANALISTA u OPL_REGIONAL llama el endpoint
- **ENTONCES** 403 y no se crea nada

### Requirement: Mapeo de columnas sin diccionario previo, con vista previa enmascarada
<!-- id: ServicioCargues.mapeo / vistaPrevia; AnaliticaIngesta.proponerMapeo -->
<!-- entidades: Cargue, PlantillaMapeo -->
<!-- impuesto_en: RESP L2017 (p. 77) y RESP L3899 (p. 148); RESP L3917 (p. 149) (variables mínimas); A4 L811–843 (p. 25) -->
<!-- decision: diseño 2026-10-22 (sinónimos de encabezado, confirmación del usuario, plantilla reutilizable) -->
<!-- decision: revisión A1 2026-10-23 (encabezado no reconocible, vista previa enmascarada, 422 por columna personal a campo de texto) -->

Al cerrar la subida, el sistema DEBE proponer el mapeo por sinónimos normalizados («FECHA_DISPENSACION», «Fecha de entrega», «F. DISP.» → `fecha`) y esperar la confirmación. Obligatorios: paciente, Fuerza, ESM, modalidad, fecha, medicamento o CUM, cantidad y valor (RESP L3917); opcionales: tipo de documento, principio activo, ATC, CIE-10, valor unitario, SSFM, profesional, especialidad, tipo y número de fórmula, id de dispensación e ítem, regional, año o grupo de edad. Si la primera fila no se reconoce como encabezado (puede ser de datos), se guardan «Columna 1..n» y nunca esa fila. La vista previa (`GET /{id}/mapeo` y `GET /{id}/vista-previa?columnas=` al cambiar el mapeo) muestra 20 filas enmascaradas: en claro solo columnas asignadas a un campo no personal cuyo encabezado no es de dato personal; paciente y profesional como seudónimo corto, fecha de nacimiento como año, lo descartado «(dato personal)» y lo demás «(oculto)». Confirmar (`POST /{id}/mapeo`) DEBE dar 422 si falta un obligatorio, si el mapeo no trae un campo por columna o si una columna con encabezado personal (documento, nombre, contacto, nacimiento) se asigna a un campo de texto de los hechos (solo «Se descarta», «No se usa» o su campo personal). El mapeo confirmado se guarda como plantilla por firma del encabezado.

#### Scenario: Encabezados desconocidos
<!-- test: AnaliticaIngestaTest.proponeMapeoPorSinonimos, sinonimosVariados -->
- **CUANDO** el archivo trae «DOC_PACIENTE;FZA;COD_ESM;MOD;FEC_DISP;DESC_MED;CUM;CANT;VR_TOTAL»
- **ENTONCES** se propone el mapeo completo de obligatorios y el usuario solo confirma

#### Scenario: Falta un obligatorio
<!-- test: IngestaApiTest.mapeoYMapeoIncompleto; AnaliticaIngestaTest.faltaObligatorio -->
- **CUANDO** ninguna columna se asigna a la cantidad
- **ENTONCES** 422 «Falta asignar la columna de cantidad» y el cargue sigue en «mapeo»

#### Scenario: Privacidad del mapeo y de la vista previa
<!-- test: IngestaApiTest.vistaPreviaEnmascarada, columnaPersonalACampoDeTexto, encabezadoNoReconocido; AnaliticaIngestaTest.privacidadDelMapeo, encabezadoNoReconocido -->
- **CUANDO** se asigna «NOMBRE_PACIENTE» a «especialidad», o el archivo no trae encabezado
- **ENTONCES** 422 que nombra la columna; sin encabezado se ven «Columna 1..n» y ningún valor personal en claro

### Requirement: Seudonimización del paciente al ingresar
<!-- id: Seudonimizador / ValidacionArranqueAnalitica -->
<!-- entidades: Dispensacion -->
<!-- impuesto_en: A5 L737–739 (p. 24) 4.7.3; A5 L271 (p. 11); RESP L2209 (p. 84) GMI P73; RESP L4551–4577 (p. 176–177) VCO P23; A5 L242 (p. 9) (Ley 1581) -->
<!-- decision: diseño 2026-10-22 (HMAC-SHA256 con DIGSA_ANALITICA_CLAVE_SEUDONIMO) -->
<!-- decision: revisión A1 2026-10-23 (sin clave: producción no arranca; otros entornos salvo local arrancan y rechazan cargues) -->

El identificador del paciente DEBE reemplazarse por `HMAC-SHA256(clave, tipo_doc normalizado + "|" + número normalizado)` en Java, **antes** de staging; el valor en claro no llega a la base, al log, a la bitácora ni a los rechazos. Igual el profesional. Nombre, apellidos, dirección, teléfono, correo y fecha de nacimiento completa se descartan antes de staging (queda el año o el grupo de edad). Clave: en `produccion` es obligatoria, de al menos 32 caracteres y distinta de la de desarrollo, o la aplicación no arranca (el mensaje nombra la variable, nunca el valor); en `local` sin variable se usa una clave fija de desarrollo; en cualquier otro entorno sin clave la aplicación arranca, pero crear un cargue o recibir un lote responde 422 «Falta configurar la clave del seudónimo… los cargues del insumo están deshabilitados hasta que TI la configure», y la bitácora (`avisoConfiguracion`) lo advierte en pantalla. Nunca una clave aleatoria (partiría a cada paciente en dos al reiniciar).

#### Scenario: Mismo paciente en dos cargues
<!-- test: SeudonimizadorTest.estable; AnaliticaIngestaTest.documento -->
- **CUANDO** «CC 1.023.456» llega en enero y «CC1023456» en febrero
- **ENTONCES** ambos hechos tienen el mismo `paciente_seudo` de 64 hexadecimales

#### Scenario: Sin clave
<!-- test: ValidacionArranqueAnaliticaTest.produccionSinClave, produccionClaveDebil, pruebasSinClave; SeudonimizadorTest.pruebasSinClave -->
- **CUANDO** la aplicación arranca sin `DIGSA_ANALITICA_CLAVE_SEUDONIMO`
- **ENTONCES** en producción no arranca; en pruebas arranca, avisa en pantalla y rechaza todo cargue con 422

#### Scenario: Insumo con nombre y nacimiento
<!-- test: IngestaApiTest.sinDocumentoEnClaro -->
- **CUANDO** el archivo trae «NOMBRE_PACIENTE» y «FECHA_NAC» = 14/03/1980
- **ENTONCES** staging y hechos no tienen nombre y guardan `anio_nacimiento = 1980`

### Requirement: Rechazo del archivo completo por estructura
<!-- id: ProcesadorCargues (rechazo estructural) -->
<!-- entidades: Cargue -->
<!-- impuesto_en: RESP L2279 (p. 87) GMI P77 f («Los archivos que no cumplan estas condiciones serán rechazados, indicando los errores») -->
<!-- decision: diseño 2026-10-22 (estructura → archivo; contenido → registro; ver Contradicciones 1) -->
<!-- decision: revisión A1 2026-10-23 (filas malformadas sin datos en staging) -->

El cargue completo DEBE quedar «rechazado», sin hechos, cuando falla la estructura: archivo vacío o sin encabezado, codificación no UTF-8, encabezados repetidos, hojas con encabezado distinto, formato ilegible o topes del lector, obligatorios sin mapear, o filas con número de campos distinto del encabezado en más del 1 % (parámetro `umbral-mal-formadas`). Por debajo del umbral, esas filas se rechazan una a una. Una fila malformada se guarda en staging **solo con su línea** y la marca `campos_distintos` (sus columnas vacías: no se sabe a qué campo corresponde cada celda y podría dejar un documento en claro). La bitácora registra recibidos = líneas leídas, procesados = 0, rechazados = recibidos y la causa con la primera línea.

#### Scenario: Columnas corridas
<!-- test: IngestaApiTest.rechazoEstructural, columnasCorridasSinPersonales; AnaliticaIngestaTest.umbral -->
- **CUANDO** 3 % de las filas tienen 10 campos y el encabezado 9
- **ENTONCES** estado «rechazado» con «Número de campos distinto del encabezado (n; primera línea 18)», y staging no tuvo ningún valor de esas filas

### Requirement: Validación por registro con causa y línea
<!-- id: ValidadorStaging -->
<!-- entidades: Cargue, Rechazo -->
<!-- impuesto_en: A5 L552 (p. 21) 4.7.1 punto 3; RESP L2279 (p. 87); A4 L388–390 (p. 14) §9.2; A4 L330–336 (p. 12) §7; RESP L3943–3945 (p. 150) -->
<!-- decision: diseño 2026-10-22 (SQL por conjuntos sobre staging) -->

Cada registro DEBE validarse con SQL por conjuntos (lotes de 250 000) y, si falla, rechazarse con su línea y **todas** sus causas del catálogo cerrado: obligatorio vacío (con la columna), fecha inválida, fecha futura, anterior a la mínima o fuera del periodo declarado, número inválido (incluida la cantidad con más de 3 decimales), cantidad ≤ 0, valor < 0, CUM con formato inválido (`^\d{1,9}-\d{1,2}$` tras normalizar), Fuerza no reconocida, modalidad no reconocida, campos distintos, duplicado exacto en el cargue, duplicado por llave natural, ya cargado en un cargue aplicado. Fuerza y modalidad se reconocen por código o nombre con sinónimos («Ejército», «EJC» → EJC). El ESM que no está en `puntos` NO se rechaza: se guarda con su código y cuenta en `esm_sin_correspondencia`. Medicamento o CUM sin homologar NO es causa de rechazo. El motivo se muestra agrupado por causa con su conteo («CUM con formato inválido (241), Fecha inválida (71)») y los rechazos se descargan en CSV «;» UTF-8 con `linea;causa;columna;referencia` (referencia = línea original de un duplicado), nunca el valor de la celda (RESP L4357–4363).

#### Scenario: Registro con dos errores
<!-- test: IngestaApiTest.cargueConErroresSembrados -->
- **CUANDO** la línea 1 044 tiene fecha «31/02/2025» y cantidad «-2»
- **ENTONCES** se rechaza con «Fecha inválida» y «Cantidad menor o igual a cero», línea 1 044, y cuenta una vez en rechazados

#### Scenario: Duplicado exacto y ya cargado
<!-- test: IngestaApiTest.cargueConErroresSembrados, yaCargado; AnaliticaIngestaTest.llaveNatural -->
- **CUANDO** las líneas 50 y 9 000 son idénticas, o un registro ya está en un cargue aplicado
- **ENTONCES** se procesa la 50 y la 9 000 se rechaza «Duplicado exacto de la línea 50»; el otro, «Ya cargado»
<!-- duda: llave natural de duplicados (ver Dudas 4) -->

#### Scenario: ESM desconocido
<!-- test: IngestaApiTest.cargueConErroresSembrados -->
- **CUANDO** el código de ESM «ESM-999» no existe en `puntos`
- **ENTONCES** el registro se procesa con `esm_codigo = 'ESM-999'`, `punto_id` nulo, y suma en «ESM sin correspondencia»
<!-- duda: 5 -->

#### Scenario: Descarga de rechazos
<!-- test: IngestaApiTest.descargaRechazos -->
- **CUANDO** el analista descarga los rechazos de un cargue con 312
- **ENTONCES** recibe un CSV con una fila por causa, sin valores, y la descarga queda en la bitácora

### Requirement: Una transacción lógica por cargue, agregados y fecha de corte
<!-- id: AplicadorHechos.aplicar / PasoAplicacion / CargueAplicado / ana_cargues_visibles -->
<!-- entidades: Cargue, Dispensacion, AgregadoMensual -->
<!-- impuesto_en: A5 L723–731 (p. 24); A5 L962 (p. 34) 14.8; RESP L2217 (p. 84); A5 L359 (p. 15) 2.7; A5 L552 (p. 21) (fecha de corte); A4 L883 (p. 26) -->
<!-- decision: diseño 2026-10-22 (agregados mes × ESM × modalidad × medicamento × CIE-10, V62) -->

Un cargue DEBE quedar «aplicado» completo o no verse: hechos, llaves, dimensión de tiempo, marca de reemplazados, pasos de aplicación (`PasoAplicacion`: refresco de los agregados de los meses tocados; A1 no trae ninguno, A3 agrega el de V62) y estado «aplicado» van en una transacción; ya aplicado se publica `CargueAplicado` para que cohortes y alertas recalculen sin bloquear. Si algo falla, el cargue queda «fallido» con la etapa y un error genérico, sus hechos y llaves se limpian y las consultas dan lo mismo que antes. Al arrancar, un cargue que quedó «procesando» pasa a «fallido» «Proceso interrumpido».

La fecha de corte es la fecha y hora de aplicación de un cargue (por defecto el último; se muestra «Corte 21/10/2026») y toda consulta analítica se resuelve con `ana_cargues_visibles(corte)`: aplicados hasta ese momento y no reemplazados a esa fecha. Misma consulta + mismo corte + mismo alcance = mismo resultado. Los pacientes distintos se cuentan exactos sobre hechos.

#### Scenario: Caída a mitad de la carga
<!-- test: IngestaApiTest.fallaSinEfecto, procesoInterrumpido -->
- **CUANDO** el proceso falla durante la carga de hechos
- **ENTONCES** el cargue queda «fallido» con la etapa y el error, y ningún hecho suyo es visible

#### Scenario: Cuadre agregado contra hechos
<!-- test: por escribir (A3): CuadreInsumoTest.agregadosIgualHechos -->
- **CUANDO** se aplica un cargue
- **ENTONCES** la suma de gasto y unidades de los agregados de cada mes es igual a la de los hechos de ese mes

#### Scenario: Cargue posterior con el mismo corte
<!-- test: IngestaApiTest.reemplazoPeriodoYCorte -->
- **CUANDO** se consulta con corte C1, se aplica otro cargue y se repite con corte C1
- **ENTONCES** las dos respuestas son idénticas; con el corte nuevo cambia en lo del cargue

### Requirement: Idempotencia, duplicados y reemplazo de periodo
<!-- id: ServicioCargues.crear / cerrar / reemplazo; ProcesadorCargues.verificarDuplicado / objetivosReemplazo -->
<!-- entidades: Cargue -->
<!-- impuesto_en: A4 L330–336 (p. 12) (duplicados); PLIEGO L2406–2427 (p. 66–67) P1 -->
<!-- decision: diseño 2026-10-22 (mismo SHA-256 o llave natural no duplica; reemplazo de periodo a elección) -->
<!-- decision: revisión A1 2026-10-23 (estado duplicado; periodo declarado obligatorio para reemplazar; confirmación con GET /{id}/reemplazo) -->

Crear un cargue con el SHA-256 declarado de un cargue aplicado vigente DEBE devolver ese cargue sin crear otro («Este archivo ya se cargó el 21/10/2026 11:12»). Si la coincidencia se descubre al cerrar la subida o al procesar (dos copias en cola, o un envío del servicio web), el cargue nuevo termina en estado `duplicado` con `duplicado_de` = el aplicado, se borra su original y queda en la bitácora (nunca se borra el cargue). Al confirmar el mapeo el usuario declara un periodo (`declarado_desde`–`declarado_hasta`) y elige «Agregar» (por defecto; las llaves ya aplicadas se rechazan «Ya cargado») o «Reemplazar el periodo», que **exige** el periodo declarado (422 sin él; CHECK en la base). Las filas con fecha fuera del periodo declarado se rechazan «Fecha fuera del periodo». Se reemplazan los cargues aplicados vigentes de la misma fuente cuyo periodo procesado queda contenido en el **declarado** (nunca en el rango de fechas del archivo: una fecha mal digitada ocultaría años). Antes de confirmar, `GET /{id}/reemplazo?desde=&hasta=` devuelve los cargues y registros que dejarían de verse, y la pantalla los muestra. Los reemplazados dejan de verse en cortes posteriores y se siguen viendo en anteriores.

#### Scenario: Mismo archivo dos veces
<!-- test: IngestaApiTest.mismoArchivo, duplicadoAlCerrar, dosEnCola -->
- **CUANDO** se crea un cargue cuyo SHA-256 ya está aplicado, o dos copias llegan a la cola
- **ENTONCES** 200 con el cargue existente y no se crea otro; la segunda copia termina «duplicado» con la referencia

#### Scenario: Corrección del OPL
<!-- test: IngestaApiTest.reemplazoPeriodoYCorte -->
- **CUANDO** se aplica con «Reemplazar el periodo» (septiembre 2026 declarado) un archivo corregido, tras ver la confirmación
- **ENTONCES** el corte nuevo suma solo el corregido y una consulta con el corte anterior da el total original

### Requirement: Cargues abiertos que vencen o se descartan
<!-- id: MantenimientoCargues.vencerAbandonados / cerrarAbierto; ServicioCargues.descartar -->
<!-- entidades: Cargue, CarguePart, LoteWs -->
<!-- impuesto_en: A5 L242 (p. 9) (Ley 1581: no conservar datos personales sin propósito); A4 L382 (p. 14) -->
<!-- decision: revisión A1 2026-10-23 -->

Un cargue en subida, en mapeo o esperando lotes del servicio web sin actividad (`actividad`: parte, lote, cierre, mapeo) por más de `horas-abandono` (24 h por defecto) DEBE pasar a `vencido` por una tarea horaria; `DELETE /api/analitica/cargues/{id}` lo pasa a `descartado` si lo pide su dueño (cargues de archivo) o el ADMINISTRADOR (a otro usuario: 404; fuera de subida o mapeo: 409). En ambos casos se borran partes, lotes, original y staging, el cargue queda con su motivo en la bitácora (`analitica_cargue_vencido`, `analitica_cargue_descartado`) y deja de contar entre los abiertos del usuario.

#### Scenario: Subida abandonada
<!-- test: IngestaApiTest.abandonadosVencen -->
- **CUANDO** un cargue queda en «mapeo» más de 24 h
- **ENTONCES** pasa a «vencido» «Vencido: sin actividad en 24 h (mapeo sin confirmar)…» y su archivo ya no está en el almacén

#### Scenario: El dueño descarta
<!-- test: IngestaApiTest.descartar -->
- **CUANDO** el analista descarta su cargue en mapeo, y otro analista intenta descartarlo
- **ENTONCES** queda «descartado» con sus archivos borrados; el otro recibe 404

### Requirement: Bitácora del cargue
<!-- id: Cargue (bitácora) -->
<!-- entidades: Cargue -->
<!-- impuesto_en: A5 L552 (p. 21) 4.7.1 punto 3; A5 L948 (p. 33) 14.3; A4 L766–782 (p. 23–24) 11.2.1 -->
<!-- decision: diseño 2026-10-22 (+ duración por etapa y SHA-256) -->
<!-- test: caso M8-3 -->

Cada cargue, en cualquier estado final, DEBE dejar: fecha y hora de recepción y de aplicación (hora militar), fuente, periodo procesado (mín.–máx. de los procesados) y declarado, recibidos, procesados, rechazados, motivo agrupado, avisos, usuario o proceso responsable, duración total y por etapa (subida, lectura, validación, hechos, agregados) y SHA-256 del archivo o del envío serializado. La bitácora se exporta en CSV.

#### Scenario: Caso M8-3
<!-- test: IngestaApiTest.cargueConErroresSembrados, bitacoraCsvYBorrado; caso M8-3 -->
- **CUANDO** el analista carga el archivo de un año con 2 000 000 de registros y 1 250 errores sembrados
- **ENTONCES** la fila muestra «22/10/2026 14:35 · Archivo CSV (;) UTF-8 · 01/01/2025 – 31/12/2025 · 2000000 · 1998750 · 1250 · <causas con conteo> · jgarcia · analista», con duración y SHA-256 en el detalle

### Requirement: Procesamiento diferido de a un cargue, con estados y progreso
<!-- id: ProcesadorCargues / ServicioCargues.detalle -->
<!-- entidades: Cargue -->
<!-- impuesto_en: RESP L2217 (p. 84); A5 L948 (p. 33) 14.3; caso M10-7 -->
<!-- decision: diseño 2026-10-22 (ejecutor de un hilo, advisory lock, FOR UPDATE SKIP LOCKED) -->
<!-- decision: revisión A1 2026-10-23 (estados duplicado, vencido, descartado) -->

Los cargues confirmados DEBEN procesarse fuera de la petición, por un ejecutor de un hilo que toma el siguiente «pendiente» con `FOR UPDATE SKIP LOCKED` bajo `pg_try_advisory_lock`; uno a la vez, los demás en cola con su posición. Estados: `subiendo → mapeo → pendiente → procesando → aplicado`; finales alternos `rechazado` (estructura), `fallido` (error técnico, huella, «Proceso interrumpido»), `duplicado`, `vencido` y `descartado`. `GET /api/analitica/cargues/{id}` devuelve estado, etapa, porcentaje, filas leídas y posición; la pantalla lo refresca cada 2 s.

#### Scenario: Dos cargues seguidos
<!-- test: IngestaApiTest.colaDeUno -->
- **CUANDO** se confirman dos cargues casi a la vez
- **ENTONCES** el segundo queda «pendiente (posición 1)» hasta que el primero termina

#### Scenario: Caso M10-7
<!-- test: caso M10-7; por escribir: docs/rendimiento_analitica.md -->
- **CUANDO** se ingieren 2 M registros mientras 10 a 20 usuarios consultan tableros
- **ENTONCES** la aplicación responde sin caerse y la ingesta termina «aplicado»

### Requirement: Carga en hechos particionados por mes
<!-- id: AplicadorHechos.cargar -->
<!-- entidades: Dispensacion -->
<!-- impuesto_en: A5 L552 (p. 21); A4 L522 (p. 17); A4 L811–843 (p. 25); RESP L3917 (p. 149) -->
<!-- decision: diseño 2026-10-22 (ana_dispensaciones RANGE por mes) -->
<!-- decision: revisión A1 2026-10-23 (índices reducidos a PK + BRIN fecha + BRIN cargue; ana_llaves para «Ya cargado»; B-tree de dimensiones en A2/A3/A4) -->

Los registros válidos DEBEN pasar de staging a `ana_dispensaciones`, particionada por mes en el esquema `analitica_particiones`, creando antes solo las particiones de los meses con filas (`ana_asegurar_particiones`, SECURITY DEFINER) y analizándolas después. Índices de hechos: solo PK (`id`, `fecha`), BRIN (`fecha`) y BRIN (`cargue_id`): cada índice se mantiene fila a fila en la carga de 2 M y la medición de A1 mostró que 7 índices eran el cuello. Los B-tree por ESM, CUM, medicamento, CIE-10 y paciente los crea la fase que los consulta (A2 homologación, A3 tableros, A4 cohortes), compuestos según la consulta. La llave natural de cada hecho se escribe en la misma transacción en `ana_llaves` (no particionada), donde «Ya cargado» hace una búsqueda por fila sin recorrer todas las particiones. Cada hecho guarda `cargue_id`, `linea_origen`, Fuerza y DISAN (`par_fuerzas.sanidad`), regional, ESM (`punto_id` y código), modalidad, los datos originales de medicamento, CUM, principio activo y ATC, y lo opcional que venga; la homologación la hace A2 (lo no resuelto **sí** suma en los totales).

#### Scenario: Año completo
<!-- test: IngestaApiTest.cuadreContraElGenerador -->
- **CUANDO** se aplica un insumo de enero a diciembre de 2025 sin particiones previas
- **ENTONCES** existen 12 particiones `ana_dispensaciones_2025_01…12` y cada hecho está en la de su mes

### Requirement: Conservación y borrado verificado del original
<!-- id: ServicioCargues.original; MantenimientoCargues.borrarOriginalesVencidos / borrarVerificado -->
<!-- entidades: Cargue -->
<!-- impuesto_en: A4 L382–384 (p. 14); A5 L743–765 (p. 24) 4.7.4 -->
<!-- decision: diseño 2026-10-22 (Almacen con SHA-256, vigencia parametrizable, solo ADMINISTRADOR) -->
<!-- decision: revisión A1 2026-10-23 (borrado verificado; reintento) -->
<!-- duda: 3 -->

El archivo (o el envío del servicio web serializado) DEBE guardarse en el `Almacen` hasta `conservar_hasta` (365 días por defecto); solo el ADMINISTRADOR lo descarga (`GET /{id}/original`, 410 si ya no está) y la descarga queda en la bitácora. Una tarea diaria borra los originales vencidos (y partes y lotes que pasaron su plazo), deja el cargue y sus hechos y registra `analitica_cargue_original_borrado`. El borrado solo se da por hecho si el almacén confirma que el archivo ya no existe: entonces `almacen_ruta` queda nula y se fija `original_borrado`; si no, la ruta se conserva y la tarea reintenta. Lo mismo al vencer, descartar o marcar duplicado.

#### Scenario: Analista pide el original
<!-- test: IngestaApiTest.perfiles -->
- **CUANDO** un ANALISTA pide `GET /api/analitica/cargues/{id}/original`
- **ENTONCES** 403

#### Scenario: Original vencido
<!-- test: IngestaApiTest.bitacoraCsvYBorrado -->
- **CUANDO** corre la tarea con un original vencido
- **ENTONCES** el archivo ya no está en el almacén, `original_borrado` tiene fecha y el cargue y sus hechos siguen

### Requirement: Perfiles que cargan y consultan la bitácora
<!-- id: Pantallas.ANALITICA_CARGUES / PermisosAnalitica -->
<!-- entidades: Perfil -->
<!-- impuesto_en: RESP L473–483 (p. 21–22) AGS-30; A4 L784 (p. 24) 11.2.2 -->
<!-- decision: diseño 2026-10-22 (ADMINISTRADOR y ANALISTA cargan; COORDINADOR consulta; OPL_NACIONAL solo el servicio web) -->

Crear cargues, subir partes, mapear, consultar el reemplazo y descartar DEBE estar permitido a ADMINISTRADOR y ANALISTA (`@permisosAnalitica.cargar()`); COORDINADOR ve la bitácora, el detalle y descarga rechazos y bitácora, sin cargar; el original, solo ADMINISTRADOR; OPL_NACIONAL solo usa el servicio web; los demás, 403. Todos los endpoints llevan `@Accion` y `@PreAuthorize("@permisos.pantalla('ANALITICA_CARGUES')")` (el del servicio web, su propia regla).

#### Scenario: Coordinador intenta cargar
<!-- test: IngestaApiTest.perfiles -->
- **CUANDO** el COORDINADOR crea un cargue
- **ENTONCES** 403; `GET /api/analitica/cargues` le responde 200

### Requirement: Pantalla «Cargues del OPL»
<!-- id: CarguesOpl.tsx -->
<!-- entidades: Cargue -->
<!-- impuesto_en: mockup `#interop` «Bitácora de cargues»; A5 L948 (p. 33) 14.3; A5 L356 (p. 15) 2.4 -->
<!-- decision: diseño 2026-10-22 (pantalla ANALITICA_CARGUES, ruta analitica/cargues, grupo «Información») -->

La pantalla DEBE mostrar la bitácora con las columnas del mockup (Fecha y hora · Fuente · Periodo procesado · Recibidos · Procesados · Rechazados · Motivo del rechazo · Responsable) más Estado; cifras sin separador de miles, rechazados > 0 en rojo; filtros por fuente, estado y fechas; el aviso de configuración si falta la clave; «Cargar archivo estructurado» (subida con avance, mapeo con vista previa enmascarada, periodo declarado, «Agregar»/«Reemplazar el periodo» con la confirmación de lo que se oculta, descartar) y «Exportar CSV». El detalle muestra duración por etapa, SHA-256, motivos, avisos y «Descargar rechazos».

#### Scenario: Ver el cargue recién aplicado
<!-- test: e2e analitica-cargues.spec.ts; caso M8-3 -->
- **CUANDO** el analista termina un cargue
- **ENTONCES** la fila aparece arriba con estado «Aplicado» y los ocho atributos, sin recargar la página

### Requirement: Eventos en la bitácora general
<!-- id: Bitacora.TIPOS (analitica_*) -->
<!-- entidades: LogTransaccion -->
<!-- impuesto_en: A5 L743–765 (p. 24) 4.7.4; A5 L385 (p. 16) 3.1; A5 L962 (p. 34) 14.9 -->
<!-- decision: diseño 2026-10-22 -->
<!-- decision: revisión A1 2026-10-23 (duplicado, vencido, descartado) -->

DEBEN registrarse en `log_transacciones`, con usuario o «Proceso automático», sin datos personales: `analitica_cargue_creado`, `_mapeo`, `_aplicado`, `_rechazado`, `_fallido`, `_duplicado`, `_vencido`, `_descartado`, `_rechazos_descarga`, `_bitacora_exportar`, `_original_descarga`, `_original_borrado` y `analitica_ws_lote` (primer lote, final y los que traen claves desconocidas). La descripción lleva id del cargue, fuente y conteos.

#### Scenario: Cargue aplicado en el log
<!-- test: IngestaApiTest.cargueConErroresSembrados -->
- **CUANDO** se aplica un cargue
- **ENTONCES** hay un `analitica_cargue_aplicado` con «Cargue 17 · Archivo CSV (;) UTF-8 · 2000000 recibidos · 1250 rechazados»

### Requirement: Generador sintético con errores sembrados
<!-- id: GeneradorSintetico (fuente de prueba) -->
<!-- entidades: — -->
<!-- impuesto_en: hoja de ruta, Preparación 12 y etapa E0; RESP L4513–4515 (p. 174); RESP L2017 (p. 77) -->
<!-- decision: diseño 2026-10-22 (Java, sin datos reales, totales esperados calculados aparte sin SQL) -->

DEBE existir un generador en Java (`src/test/.../analitica/GeneradorSintetico`), con semilla fija, que produzca un año de dispensaciones (2 M por defecto, parametrizable) con documentos ficticios, en CSV «;» UTF-8 y xlsx; que siembre una cantidad conocida de cada causa de rechazo y de casos para homologación, cohortes y alertas; y que escriba `totales_esperados.csv` por mes, Fuerza, DISAN, ESM, modalidad, ATC, principio activo, medicamento, CUM y CIE-10, calculados sin pasar por la base. Para el insumo real (Preparación 13), `herramientas/totales_insumo.py` (biblioteca estándar, en flujo) DEBE calcular fuera del sistema la misma salida con el archivo y el mapeo.

#### Scenario: Semilla fija
<!-- test: GeneradorSinteticoTest.determinista, erroresSembrados, cuadreEntreDimensiones -->
- **CUANDO** se genera dos veces con la misma semilla
- **ENTONCES** los dos archivos tienen el mismo SHA-256

#### Scenario: El script concuerda con el generador
<!-- test: por escribir: CI ejecuta el script sobre el archivo sintético pequeño y compara -->
- **CUANDO** se corre sobre el archivo del generador
- **ENTONCES** su salida es idéntica a `totales_esperados.csv`

### Requirement: Exportación de los hechos por periodo
<!-- id: ExportacionHechosApiTest -->
<!-- entidades: Dispensacion, ExportacionHechos -->
<!-- impuesto_en: AGS-75 / GMI P74 (10 min para exportar); GMI P77 f (formato); exportacion-alertas «Solo el administrador exporta todos los datos»; duda AN.14 -->
<!-- decision: A7.2 2026-10-30 (dudas AN.14, AN.35 a AN.38) -->

Los hechos de dispensación (`ana_dispensaciones`, unos 2 M de filas por mes) NO van en la exportación completa (una transacción con tope de 10 minutos). El ADMINISTRADOR DEBE poder pedir, desde «Cargues del OPL», la exportación de un periodo (un mes, o de un mes a otro, hasta `digsa.analitica.exportacion-hechos.meses-max` = 60, ninguno posterior al mes actual). La solicitud fija el corte (su instante) y crea un trabajo que corre en segundo plano, uno a la vez en el despliegue (candado de PostgreSQL) y uno en curso por administrador (409). Cada mes DEBE leerse en su propia transacción de solo lectura (REPEATABLE READ), con un cursor por lotes sobre la partición del mes y escribirse en flujo, con su propio tope (`limite-minutos-mes` = 10): un archivo por mes, `digsa_hechos_dispensacion_<id>_<aaaa-mm>.csv.gz`, con el formato de la exportación completa (UTF-8 con BOM, «;», coma decimal, fechas ISO, sin fórmulas). Columnas: las de `ana_dispensaciones` con el paciente y el profesional solo como seudónimo, más `cargue_sha256`; nunca el documento en claro; sin la llave natural (técnica). Una columna nueva de los hechos sin decidir hace fallar la exportación (falla cerrado). Salen solo los hechos de los cargues visibles y confirmados al pedir la exportación: la lista se congela en la solicitud y la usan todos los meses (un cargue que se confirma mientras corre no entra en ningún mes; una reanudación da lo mismo; AN.35). Un mes que falla queda «fallido» sin archivo a medias y los demás siguen; el trabajo queda «fallida» con los meses listos descargables. El LEEME DEBE traer periodo, corte y último cargue visible, versión del esquema, filas, suma de `valor_total` y SHA-256 del `.csv.gz` de cada mes. La pantalla muestra el progreso por mes y la lista «Exportaciones de hechos» con la descarga de cada mes y del LEEME (enlace directo, en flujo). Si el LEEME no se puede guardar, el trabajo queda pendiente y se reintenta en la revisión siguiente. Los archivos vencen a las `vigencia-horas` (24) y una limpieza idempotente los borra (410 después); la ruta de un archivo solo se anula cuando se comprobó que ya no está en el almacén (si no, se reintenta). Solo ADMINISTRADOR (AN.36): cualquier otro perfil, 403. Bitácora: `analitica_hechos_exportacion_solicitada`, `_generada`, `_fallida`, `_descarga` y `_vencida`, con id, periodo, meses y filas, sin datos personales.

#### Scenario: Rango de varios meses
<!-- test: ExportacionHechosApiTest.rangoCompleto -->
- **CUANDO** el administrador pide 1998-01 a 1998-04 (marzo sin datos)
- **ENTONCES** se generan 4 archivos; cada uno trae las filas de su mes que ve el corte (conteo y suma del valor iguales a la base), el SHA-256 del archivo descargado es el de la pantalla y del LEEME, marzo trae solo el encabezado, ningún documento ni nombre sale en claro y la solicitud, la generación y cada descarga quedan en la bitácora

#### Scenario: Mes que falla
<!-- test: ExportacionHechosApiTest.mesFallido -->
- **CUANDO** falla la generación de un mes
- **ENTONCES** ese mes queda «Falló» sin archivo (409 al descargarlo), los demás se descargan y el LEEME lo dice

#### Scenario: Cargue que se confirma durante la exportación
<!-- test: ExportacionHechosApiTest.cargueDuranteLaExportacion, reanudacion -->
- **CUANDO** un cargue que reemplaza el periodo empezó antes de la solicitud y se confirma mientras la exportación corre, o el proceso se cae a mitad
- **ENTONCES** ningún mes trae sus hechos (todos usan la lista congelada en la solicitud) y la reanudación rehace el mes a medias con el mismo contenido y conserva los listos

#### Scenario: Caducidad y permisos
<!-- test: ExportacionHechosApiTest.caducidad, soloAdministrador -->
- **CUANDO** pasa la vigencia, o un Analista o Coordinador (que tienen «Cargues del OPL») pide, lista o descarga
- **ENTONCES** la descarga responde 410 y los archivos ya no están en el almacén; a los demás perfiles, 403

### Requirement: Pruebas de cuadre y tiempos medidos
<!-- id: IngestaApiTest.cuadreContraElGenerador / RendimientoIngestaTest -->
<!-- entidades: Dispensacion, Cargue -->
<!-- impuesto_en: A5 L948 (p. 33) 14.5; caso M8-5; RESP L2017 (p. 77); A5 L357 (p. 15) 2.5; casos M8-3 y M10-7 -->
<!-- decision: diseño 2026-10-22 (meta < 5 min para 2 M en el equipo de desarrollo) -->

DEBE haber una prueba que cargue el archivo sintético por la API y compare por dimensión gasto, unidades, dispensaciones y pacientes distintos contra `totales_esperados.csv` (en CI con un archivo pequeño; con 2 M, bajo demanda). La duración de cada etapa se guarda en el cargue; la prueba de 2 M se corre dos veces y se anota en `docs/rendimiento_analitica.md`. Meta interna: 2 M filas en menos de 5 minutos.

#### Scenario: Consistencia entre dimensiones
<!-- test: IngestaApiTest.cuadreContraElGenerador -->
- **CUANDO** se aplica el archivo sintético
- **ENTONCES** recibidos = esperado, rechazados por causa = sembrados, y Σ gasto por cualquier dimensión = total esperado (al centavo)

#### Scenario: Medición
<!-- test: RendimientoIngestaTest.dosMillones (bajo demanda) -->
- **CUANDO** se ingiere el archivo sintético de 2 M
- **ENTONCES** el detalle muestra las cinco duraciones y la total (< 300 s)

### Invariant: El identificador del paciente nunca se guarda en claro
<!-- entidades: Dispensacion, Staging, Rechazo, LogTransaccion, PlantillaMapeo -->
<!-- impuesto_en: A5 L737–739 (p. 24) 4.7.3; RESP L2209 (p. 84); RESP L4551–4577 (p. 176–177); Ley 1581 (A5 L242) -->
<!-- verificado_por: IngestaApiTest.sinDocumentoEnClaro, columnasCorridasSinPersonales, vistaPreviaEnmascarada -->

Ninguna tabla (incluido staging, también en filas malformadas), log, archivo de rechazos, plantilla de mapeo (sus encabezados nunca son una fila de datos), vista previa ni respuesta de la API DEBE contener el documento, nombre, contacto o fecha de nacimiento completa del paciente; solo el seudónimo HMAC. Las únicas copias en claro son el original, las partes y los lotes del `Almacen`, con plazo y borrado verificado.

### Invariant: Cuadre de conteos del cargue
<!-- entidades: Cargue -->
<!-- impuesto_en: A5 L552 (p. 21) 4.7.1 punto 3 -->
<!-- verificado_por: CHECK en ana_cargues (aplicado o rechazado) + IngestaApiTest.cargueConErroresSembrados -->

En todo cargue aplicado o rechazado DEBE cumplirse `recibidos = procesados + rechazados`, y `procesados` = número de hechos con su `cargue_id`; la suma de conteos por causa es ≥ rechazados.

### Invariant: Solo lo aplicado se ve y todo hecho es trazable a su origen
<!-- entidades: Dispensacion, Cargue -->
<!-- impuesto_en: A5 L743–765 (p. 24) 4.7.4; A4 L869 (p. 26); RESP L3989 (p. 151); RESP L5173 (p. 204) -->
<!-- verificado_por: NOT NULL en ana_dispensaciones + IngestaApiTest.fallaSinEfecto, reemplazoPeriodoYCorte, cuadreContraElGenerador -->

Ninguna consulta analítica DEBE leer hechos de un cargue que no esté en `ana_cargues_visibles(corte)`. Cada hecho DEBE tener `cargue_id` y `linea_origen` no nulos, y el cargue su fuente y SHA-256, de modo que cualquier cifra se rastree a archivo y línea (rastreo 14.9 de `analitica-tableros`).

### Invariant: Un archivo aplicado no se repite y un solo cargue en proceso
<!-- entidades: Cargue -->
<!-- impuesto_en: A4 L330–336 (p. 12); PLIEGO L2406–2427 P1 -->
<!-- verificado_por: únicos parciales uq_ana_cargues_sha_aplicado (aplicado y no reemplazado), uq_ana_cargues_procesando, uq_ana_cargues_envio + IngestaApiTest.mismoArchivo, colaDeUno -->

No DEBE haber dos cargues aplicados vigentes con el mismo SHA-256, ni más de un cargue «procesando», ni dos cargues con el mismo `envio`.

### Invariant: Bitácora de cargues permanente y base sin hechos precargados
<!-- entidades: Cargue, Dispensacion -->
<!-- impuesto_en: A5 L982 (p. 35) condición 4 -->
<!-- decision: revisión A1 2026-10-23 -->
<!-- verificado_por: IngestaPrivilegiosTest.privilegiosMinimos (sin DELETE en ana_cargues); V60 sin INSERT de datos -->

El rol de la aplicación NO DEBE tener DELETE sobre `ana_cargues` (todo cargue que no termina queda con estado explícito), y las migraciones NO DEBEN insertar hechos, cargues ni plantillas.

---

## Contradicciones registradas

1. **Rechazo del archivo o del registro.** RESP L2279 rechaza «los archivos»; A5 L552 punto 3 y 14.3 cuentan registros rechazados. Dos niveles: estructura → archivo; contenido → línea.
2. **Quién pone los datos del 14.3.** RESP L4515: el oferente; RESP L2017, L3899, L4047: la Entidad. Se preparan los dos: generador sintético y mapeo sin diccionario.
3. **Delimitador.** AGS-57 (RESP L773) lo deja abierto; GMI P77 f fija «;». Se aceptan «;» y «,»; el oficial es «;».
4. **Anonimizar o seudonimizar.** A4 L784 dice «anonimización»; RESP L2209 admite «seudonimizada». Cohortes y pacientes distintos exigen seudonimizar; toda salida general es agregada.
5. **Periodicidad.** A5 L552 remite a la DIGSA; RESP L2217 pide diaria con rezago ≤ 48 h; A4 L879, informes a 20 días. Se admiten cargues diarios (servicio web) y mensuales (archivo).
6. **Dónde vive la bitácora.** En el mockup está en «Interoperabilidad OPL» (4.1) con un motivo «soporte sin indexar» que no aplica. Solo referencia de columnas.

## Dudas

1. **Datos del día de la prueba (15.1).** Recomendado: llevar el sintético y estar listos para el insumo de la DIGSA. Construidos: generador, mapeo y `totales_insumo.py`.
2. **Estructura real del insumo** («Anexo Herramienta Tecnológica» del OPL; RESP L4147). Recomendado y construido: sinónimos amplios en `reglas/AnaliticaIngesta`, confirmación y plantilla.
3. **Conservar o descartar el original** (datos personales en claro). Recomendado: conservar en el `Almacen` cifrado en reposo, solo ADMINISTRADOR, 365 días (A4 L382). Construido así, con borrado verificado.
4. **Llave natural de duplicados.** Las variables mínimas no traen id de dispensación. Construido: con id de dispensación e ítem, llave = (fuente, id, ítem); si no, la fila completa mapeada.
5. **ESM, Fuerza o modalidad fuera del catálogo.** Construido: Fuerza y modalidad se rechazan; ESM desconocido se acepta y cuenta en calidad.
6. **«Simulando el web service» sin salir del sistema** (condición 12, A5 L1008) y autenticación de máquina (CSRF). Recomendado: en la prueba usar el archivo y tener el endpoint probado; para un cliente máquina, credencial de servicio OPL_NACIONAL sin CSRF solo en esa ruta. Construido: endpoint con sesión de OPL_NACIONAL y prueba que envía los lotes.
7. **Rotación de la clave del seudónimo.** Parte a cada paciente en dos. Recomendado: no rotar salvo incidente; si se rota, recargar todo con la clave nueva (documentado en `PropiedadesAnalitica`).
8. **Umbral del 1 % de filas malformadas.** Construido como parámetro (`umbral-mal-formadas`).
9. **AN.14 · Hechos de dispensación en la exportación completa** (AGS-75, tope de 10 minutos). `ana_dispensaciones` crece unos 2 M de filas por mes y la exportación completa corre en una transacción con ese tope: con unos meses dejaría de caber y fallaría entera. Construido: `ana_dispensaciones` y `ana_llaves` (su índice de llaves) van en `Exportacion.TECNICAS` (`EXCLUIDAS` es por columnas); las demás tablas de V60 siguen en la exportación completa; los hechos se entregan aparte por la exportación de hechos por periodo (**resuelta en A7.2, 2026-10-30**: requirement «Exportación de los hechos por periodo» y «Datos y piezas construidas en A7.2»). Sigue abierta la confirmación de la DIGSA sobre si además los quiere en la exportación completa (AN.14; derivadas AN.35 a AN.38).
<!-- decision: revisión A1 2026-10-23 -->

## Datos y piezas construidas en A1

**Migración V60 (`V60__analitica_ingesta.sql`)**, con CHECKs, comentarios por columna, privilegios mínimos al `${rol_app}` (sin DELETE en `ana_cargues`) y particiones en el esquema `analitica_particiones`:

| Tabla | Columnas principales |
|---|---|
| `ana_cargues` | `fuente` (`archivo`, `ws_opl`), `fuente_texto`, `envio` (único), `nombre_archivo`, `formato`, `delimitador`, `tamano_bytes`, `partes`, `sha256`, `sha256_declarado`, `almacen_ruta`, `conservar_hasta`, `original_borrado`, `encabezados`, `mapeo`, `plantilla_id`, `modo`, `declarado_desde`/`hasta` (obligatorios para reemplazar), `estado` (10 valores), `etapa`, `porcentaje`, `filas_leidas`, `periodo_desde`/`hasta`, `recibidos`, `procesados`, `rechazados`, `esm_sin_correspondencia`, `motivos`, `motivo_texto`, `avisos`, `etapas_ms`, `duracion_ms`, `usuario_id`, `responsable_texto`, `creado`, `actividad`, `recibido`, `iniciado`, `aplicado_en`, `terminado`, `reemplazado_por`/`_en`, `duplicado_de` (⇔ `duplicado`), `error` |
| `ana_cargue_partes` | PK (`cargue_id`, `numero`), `tamano`, `sha256`, `almacen_ruta`, `conservar_hasta` |
| `ana_ws_lotes` | único (`envio`, `numero`), `registros`, `final`, `sha256`, `almacen_ruta`, `conservar_hasta`, `claves_desconocidas` |
| `ana_cargue_rechazos` | `cargue_id`, `orden`, `linea`, `causa` (catálogo cerrado de 18), `columna`, `referencia`; sin valores |
| `ana_mapeo_plantillas` | `nombre`, `firma_encabezado` (única), `encabezados`, `mapeo`, `creado_por` |
| `ana_staging_dispensacion` | por LIST (`cargue_id`): una partición UNLOGGED por cargue, creada al procesar y borrada al terminar; texto normalizado y seudonimizado, `campos_distintos`, `huella`, `llave` |
| `ana_dispensaciones` | RANGE (`fecha`) mensual; PK (`id`, `fecha`), BRIN `fecha`, BRIN `cargue_id`; sin FK a cargues (peso en la carga) |
| `ana_llaves` | PK (`llave`, `cargue_id`), BRIN `cargue_id`; índice de «Ya cargado» |
| `ana_dim_tiempo` | `fecha` PK, semana, mes, bimestre, trimestre, semestre, año |

Funciones: `ana_cargues_visibles(corte)`; SECURITY DEFINER `ana_asegurar_particiones`, `ana_analizar_particiones`, `ana_staging_crear`, `_analizar`, `_borrar`. Exportación: V60 en `Exportacion.TABLAS` salvo `ana_staging_dispensacion`, `ana_dispensaciones` y `ana_llaves`, en `TECNICAS` (AN.14). Trece tipos nuevos en `Bitacora.TIPOS`. Parámetros `digsa.analitica.*` en `application.yml` (`PropiedadesAnalitica`).

**Clases** (`analitica/` y `analitica/ingesta/`)

| Pieza | Responsabilidad |
|---|---|
| `reglas/AnaliticaIngesta` | delimitador y BOM, encabezados y «Columna n», sinónimos de campos y valores, mapeo y su privacidad, números, fechas, CUM, documento, llave, causas, partes esperadas, umbral, motivo |
| `FuenteDispensacion`, `ArchivoDispensacion`, `LectorCsv`, `LectorXlsx`, `ServicioWebOpl` | puerto y adaptadores con topes |
| `Seudonimizador`, `ValidacionArranqueAnalitica`, `PropiedadesAnalitica` | HMAC; arranque según entorno; parámetros |
| `ServicioCargues`, `MantenimientoCargues` | crear, partes, cerrar, mapeo, vista previa, reemplazo, descartar, consultas y descargas; vencimiento y borrado verificado |
| `ProcesadorCargues`, `CopiadorStaging`, `ValidadorStaging`, `AplicadorHechos` | cola de uno, etapas; COPY; validación por conjuntos; hechos, llaves, reemplazo, `PasoAplicacion` |
| `CarguesApi`, `OplDispensacionApi`, `PermisosAnalitica`, `CargueAplicado` | endpoints, permisos y evento |

**Endpoints** (`/api/analitica/cargues`)

| Método y ruta | Perfil |
|---|---|
| `POST /` · `PUT /{id}/partes/{n}` · `POST /{id}/cerrar` · `DELETE /{id}` | ADMINISTRADOR, ANALISTA (descartar: dueño o ADMINISTRADOR) |
| `GET /{id}/mapeo` · `GET /{id}/vista-previa` · `GET /{id}/reemplazo` · `POST /{id}/mapeo` | ADMINISTRADOR, ANALISTA |
| `GET /` · `GET /{id}` · `GET /{id}/rechazos.csv` · `GET /bitacora.csv` | + COORDINADOR |
| `GET /{id}/original` | ADMINISTRADOR |
| `POST /api/analitica/opl/dispensacion` | OPL_NACIONAL |

**Pantalla:** `Pantallas.ANALITICA_CARGUES` («Cargues del OPL», `analitica/cargues`, grupo «Información») y `paginas/CarguesOpl.tsx` con ruta en `App.tsx`.

**Pruebas:** `AnaliticaIngestaTest`, `SeudonimizadorTest`, `ValidacionArranqueAnaliticaTest`, `ArchivoDispensacionTest`, `IngestaApiTest`, `IngestaPrivilegiosTest`, `GeneradorSinteticoTest`, `RendimientoIngestaTest` (2 M, bajo demanda), e2e `analitica-cargues.spec.ts`. Pendiente: `docs/rendimiento_analitica.md` (M10-7) y la prueba CI de `totales_insumo.py`.

## Datos y piezas construidas en A7.2 (exportación de los hechos por periodo, AN.14)

**Migración V66 (`V66__analitica_exportacion_hechos.sql`)**: `ana_exportaciones_hechos` (`solicitada_por`, `desde`, `hasta`, `corte`, `corte_cargue_id`, `cargues` (lista congelada), `estado` pendiente/generando/lista/fallida/vencida, `meses`, `meses_listos`, `meses_fallidos`, `filas`, `tamano`, `leeme_ruta`, `version_esquema`, `error`, `vence`; índice único parcial: una en curso por usuario) y `ana_exportaciones_hechos_meses` (PK `exportacion_id`, `mes`; `estado`, `filas`, `valor_total`, `tamano`, `sha256`, `ruta`, `duracion_ms`). Privilegios: SELECT, INSERT, UPDATE (sin DELETE: traza) y SELECT sobre `flyway_schema_history` (versión del esquema del LEEME). Las dos tablas van en `Exportacion.TABLAS` (quién exportó qué y cuándo); los archivos están en el almacén (`analitica/hechos/…`) y vencen.

| Pieza | Responsabilidad |
|---|---|
| `reglas/AnaliticaExportacionHechos` | columnas, validación del periodo con campo, meses, nombres de archivo, resumen de bitácora y LEEME |
| `analitica/exportacion/GeneradorHechosMes` | un mes en flujo: cursor por lotes sobre los hechos visibles al corte, `Exportacion.fila`, gzip, SHA-256, filas y suma; columnas decididas (falla cerrado) |
| `ServicioExportacionHechos` | solicitud (corte, lista congelada de cargues `cargues bigint[]` y meses), ejecutor de uno con candado (cola de una corrida, conexión abortada si no se suelta), un mes por transacción de solo lectura con su tope, falla por mes, LEEME, retoma tras caída, descargas, limpieza de vencidas con borrado verificado; LEEME que se reintenta |
| `ExportacionHechosApi`, `PermisosExportacionHechos`, `PropiedadesExportacionHechos` | endpoints, solo ADMINISTRADOR (`@permisosHechos.exportar()` + pantalla `ANALITICA_CARGUES`), parámetros `digsa.analitica.exportacion-hechos.*` |

**Endpoints** (`/api/analitica/exportaciones-hechos`, solo ADMINISTRADOR): `POST /` (`{desde, hasta}` aaaa-mm; 202), `GET /` (las del usuario con sus meses), `GET /{id}`, `GET /{id}/meses/{aaaa-mm}` (`application/gzip`), `GET /{id}/leeme`.

**Pantalla:** sección «Exportación de hechos por periodo» de `paginas/CarguesOpl.tsx` (`analitica/PanelExportacionHechos.tsx`, lógica en `analitica/exportacionHechos.ts`), visible solo con `esAdministrador`.

**Pruebas:** `AnaliticaExportacionHechosTest`, `ExportacionHechosApiTest`, `IngestaPrivilegiosTest.privilegiosExportacionHechos`, `exportacionHechos.test.ts`, e2e `analitica-exportacion-hechos.spec.ts`. Tiempos con el volumen de las pruebas normales en `.local/notas/mediciones.md` (A7.2).
