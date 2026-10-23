# Especificación: analitica-ingesta

> Escrita por spec-miner el 2026-10-22. **Sin PHP de referencia: sale de la fuente de verdad, el mockup y el diseño de la sesión.**
> Fuentes: `FUENTE DE VERDAD/md/` (A4 = Anexo 4; A5 = Anexo 5; RESP = Respuestas a observaciones, texto OCR: ante diferencias manda el PDF); `MOCKUP/Mockup_Modulos_DIGSA.html` (bitácora de cargues en `#interop`, trazabilidad en `#homologacion`); `EVALUACION_SOFTWARE/Hoja_ruta_prueba_en_sitio_DIGSA_2026-10-22.xlsx` (casos M8-3 y M10-7, Preparación 12 y 13, etapa E0); diseño común del módulo de la sesión del 2026-10-22 (marcado `<!-- decision: diseño 2026-10-22 -->`, manda en lo que la DIGSA no fija).
> Relacionadas: `analitica-homologacion` (tabla maestra, referencias oficiales, estado de homologación de cada hecho), `analitica-tableros` (agregados V62, filtros, corte en las consultas, descargas, rastreo 14.9), `analitica-cohortes-alertas` (recalculo al aplicar un cargue), `analitica-asistente` (consulta sobre los mismos hechos).
> Estado en Java (rama `analitica`, commit fe9c98e): **nada construido** del módulo. Reutilizable: `plataforma/Almacen` (guardar, leer, escribir en flujo), patrón diferido de `exportacion/` (ejecutor de un hilo, `pg_try_advisory_lock`, `FOR UPDATE SKIP LOCKED`, SHA-256 con `DigestOutputStream`), lector SAX de xlsx y CSV «;» de `maestros/carga` (solo CLI, 10 MB), `RegistroBitacora` + `reglas/Bitacora.TIPOS`, `reglas/Alcance` y `Pantallas`, catálogos `par_fuerzas` (con `sanidad` = DISAN), `par_modalidades`, `par_regionales`, `puntos`; `soporte/PruebaIntegracion` (Postgres 16 embebido). Multipart global 110 MB. No existe tabla de medicamentos. V60–V69 libres.

---

### Requirement: Puerto único de consumo del insumo del OPL
<!-- id: FuenteDispensacion -->
<!-- entidades: Cargue -->
<!-- impuesto_en: A5 L552 (p. 21) 4.7.1 punto 1; A5 L242 (p. 9) 1.1; RESP L827 (p. 33) AGS-64; A5 L948 (p. 33) 14.3 -->
<!-- decision: diseño 2026-10-22 (puerto con adaptadores ArchivoDispensacion y ServicioWebOpl) -->

El módulo DEBE consumir la información de dispensación del OPL por archivo estructurado y por servicio web, ambos a través del puerto `FuenteDispensacion`, de modo que validación, carga, bitácora y trazabilidad sean idénticas sea cual sea el mecanismo; la fuente queda registrada en el cargue («Archivo CSV (;) UTF-8», «Archivo xlsx», «API OPL · dispensación»).

#### Scenario: Mismo insumo por los dos caminos
<!-- test: por escribir: IngestaApiTest.mismoResultadoArchivoYServicio -->
- **CUANDO** el mismo conjunto de 10 000 registros se carga una vez por archivo y otra por el servicio web simulado (en bases distintas)
- **ENTONCES** recibidos, procesados, rechazados, motivos y totales de gasto, unidades y pacientes son iguales; solo cambia la fuente

### Requirement: Formatos de archivo aceptados
<!-- id: ArchivoDispensacion.abrir -->
<!-- entidades: Cargue -->
<!-- impuesto_en: RESP L2279 (p. 87) GMI P77 f («;», coma decimal, UTF-8); RESP L827 (p. 33) AGS-64 (CSV, TXT, XML o JSON por lotes); RESP L2017 (p. 77) (1,5 a 2 M registros) -->
<!-- decision: diseño 2026-10-22 (detección de «;» o «,», BOM, .gz/.zip, xlsx SAX con varias hojas) -->

El adaptador de archivo DEBE leer en flujo, sin cargar el archivo en memoria: CSV y TXT con delimitador «;» o «,» detectado en el encabezado, UTF-8 con o sin BOM, coma decimal cuando el delimitador es «;» (punto decimal si es «,»); los mismos comprimidos en `.gz` o `.zip` (un solo archivo de datos dentro); y xlsx leído con SAX, uniendo varias hojas con el mismo encabezado (2 M filas superan el límite de 1 048 576 por hoja). La línea de origen es la del archivo (en xlsx, «hoja:fila»).

#### Scenario: CSV oficial de la DIGSA
<!-- test: por escribir: AnaliticaIngestaTest.detectaPuntoYComa -->
- **CUANDO** llega un CSV con «;», BOM y valores `1234,50`
- **ENTONCES** se detecta «;», el BOM no entra en el primer encabezado y el valor se lee como 1234.50

#### Scenario: xlsx de dos hojas
<!-- test: por escribir: ArchivoDispensacionTest.xlsxVariasHojas -->
- **CUANDO** un xlsx trae 1 048 575 filas en la hoja 1 y 951 425 en la hoja 2, con el mismo encabezado
- **ENTONCES** se reciben 2 000 000 registros y la línea de origen del último es «2:951426»

### Requirement: Subida por partes con verificación de integridad
<!-- id: CarguesApi.partes -->
<!-- entidades: Cargue, CarguePart -->
<!-- impuesto_en: RESP L2017 (p. 77) (volumen); A4 L382–384 (p. 14) (conservar soportes del origen) -->
<!-- decision: diseño 2026-10-22 (partes ≤ 50 MB al Almacen; no se toca el multipart global de 110 MB) -->

La subida DEBE hacerse por partes de hasta 50 MB que se escriben en el `Almacen`; al cerrar, el servidor ensambla, calcula el SHA-256 del archivo completo y lo compara con el que declaró el navegador. Si no coincide, o falta una parte, el cargue queda «fallido» sin procesar nada. La pantalla muestra el avance de la subida.

#### Scenario: Archivo de 700 MB
<!-- test: por escribir: IngestaApiTest.subidaPorPartes -->
- **CUANDO** se suben 14 partes de 50 MB y se cierra con el SHA-256 correcto
- **ENTONCES** el cargue pasa a «mapeo» con el SHA-256 y el tamaño guardados

#### Scenario: Huella distinta
<!-- test: por escribir: IngestaApiTest.huellaDistinta -->
- **CUANDO** una parte llegó alterada
- **ENTONCES** 422 «El archivo llegó incompleto o alterado; vuelva a subirlo» y el cargue queda «fallido»

### Requirement: Servicio web simulado del OPL
<!-- id: OplDispensacionApi / ServicioWebOpl -->
<!-- entidades: Cargue, LoteWs -->
<!-- impuesto_en: A5 L948 (p. 33) 14.3 («simulando el web service del OPL»); RESP L4513–4527 (p. 174–175) (no se exige WS real, protocolo ni interfaz); A5 L432 (p. 17) 4.1 (consumo diario); RESP L4395 (p. 170) (el OPL carga a diario) -->
<!-- decision: diseño 2026-10-22 (POST /api/analitica/opl/dispensacion, lotes JSON, perfil OPL_NACIONAL) -->

El sistema DEBE exponer `POST /api/analitica/opl/dispensacion`, solo para el perfil OPL_NACIONAL, que recibe lotes JSON numerados de un envío (`envio`, `numero`, `final`, `registros[]` con los mismos campos del insumo). Los lotes se acumulan en staging del mismo cargue; con `final = true` el cargue entra a la cola y sigue el mismo proceso del archivo. Un lote repetido (mismo `envio` y `numero`) se ignora y responde lo mismo. Responsable en la bitácora: «Proceso automático · API OPL (usuario)».

#### Scenario: Envío en 200 lotes
<!-- test: por escribir: IngestaApiTest.servicioWebPorLotes -->
- **CUANDO** OPL_NACIONAL envía 200 lotes de 10 000 registros y el último con `final = true`
- **ENTONCES** se crea un cargue con fuente «API OPL · dispensación», 2 000 000 recibidos y estado «pendiente»

#### Scenario: Perfil no autorizado
<!-- test: por escribir: IngestaApiTest.servicioWebSoloOpl -->
- **CUANDO** un ANALISTA u OPL_REGIONAL llama el endpoint
- **ENTONCES** 403 y no se crea nada

### Requirement: Mapeo de columnas sin diccionario previo
<!-- id: AnaliticaIngesta.proponerMapeo -->
<!-- entidades: Cargue, PlantillaMapeo -->
<!-- impuesto_en: RESP L2017 (p. 77) y RESP L3899 (p. 148) (insumo sin diccionario previo, se entrega en la sesión); RESP L3917 (p. 149) (variables mínimas); A4 L811–843 (p. 25) (variables transversales) -->
<!-- decision: diseño 2026-10-22 (sinónimos de encabezado, confirmación del usuario, plantilla reutilizable) -->

Al cerrar la subida, el sistema DEBE proponer el mapeo de cada encabezado a un campo del modelo por sinónimos normalizados (mayúsculas, sin tildes ni signos: «FECHA_DISPENSACION», «Fecha de entrega», «F. DISP.» → `fecha`), mostrar las 20 primeras filas **con el identificador del paciente ya enmascarado** y esperar la confirmación del usuario. Campos obligatorios: identificador del paciente, Fuerza, ESM, modalidad, fecha de dispensación, medicamento o CUM, cantidad y valor (RESP L3917); opcionales: principio activo, ATC, CIE-10, valor unitario, código SSFM, profesional, especialidad, tipo de fórmula, regional, año o grupo de edad. Sin todos los obligatorios mapeados no se puede confirmar. El mapeo confirmado se guarda como plantilla, que se propone sola cuando llega un archivo con el mismo encabezado.

#### Scenario: Encabezados desconocidos
<!-- test: por escribir: AnaliticaIngestaTest.proponeMapeoPorSinonimos -->
- **CUANDO** el archivo trae «DOC_PACIENTE;FZA;COD_ESM;MOD;FEC_DISP;DESC_MED;CUM;CANT;VR_TOTAL»
- **ENTONCES** se propone el mapeo completo de obligatorios y el usuario solo confirma

#### Scenario: Falta un obligatorio
<!-- test: por escribir: IngestaApiTest.mapeoIncompleto -->
- **CUANDO** ningún encabezado corresponde a la cantidad y el usuario no lo asigna
- **ENTONCES** 422 «Falta asignar la columna de cantidad» y el cargue sigue en «mapeo»

### Requirement: Seudonimización del paciente al ingresar
<!-- id: Seudonimizador.seudonimo -->
<!-- entidades: Dispensacion -->
<!-- impuesto_en: A5 L737–739 (p. 24) 4.7.3; A5 L271 (p. 11); RESP L2209 (p. 84) GMI P73 («agregada o seudonimizada»); RESP L4551–4577 (p. 176–177) VCO P23 (minimización); A5 L242 (p. 9) (Ley 1581) -->
<!-- decision: diseño 2026-10-22 (HMAC-SHA256 con DIGSA_ANALITICA_CLAVE_SEUDONIMO) -->

El identificador del paciente DEBE reemplazarse por `HMAC-SHA256(clave, tipo_doc normalizado + "|" + número normalizado)` en el flujo de lectura en Java, **antes** de escribir en staging; el valor en claro no llega a la base, al log, a la bitácora ni a los rechazos. Lo mismo para el profesional si viene. El mismo paciente en dos cargues da el mismo seudónimo. Nombre, apellidos, dirección, teléfono, correo y fecha de nacimiento completa se descartan antes de staging (de la fecha de nacimiento solo queda el año o el grupo de edad); el mapeo las marca «Se descarta (dato personal)».

#### Scenario: Mismo paciente en dos cargues
<!-- test: por escribir: SeudonimizadorTest.estable -->
- **CUANDO** «CC 1.023.456» llega en enero y «CC1023456» en febrero
- **ENTONCES** ambos hechos tienen el mismo `paciente_seudo` de 64 hexadecimales y en ninguna tabla aparece «1023456»

#### Scenario: Sin clave en producción
<!-- test: por escribir: ValidacionArranqueAnaliticaTest -->
- **CUANDO** la aplicación arranca con el perfil `prod` sin `DIGSA_ANALITICA_CLAVE_SEUDONIMO`
- **ENTONCES** no arranca y el mensaje nombra la variable (en el perfil `test` hay una clave fija de prueba que no es secreto)

#### Scenario: Insumo con nombre y nacimiento
<!-- test: por escribir: IngestaApiTest.descartaPersonales -->
- **CUANDO** el archivo trae «NOMBRE_PACIENTE» y «FECHA_NAC» = 14/03/1980
- **ENTONCES** staging y hechos no tienen nombre y guardan `anio_nacimiento = 1980`

### Requirement: Rechazo del archivo completo por estructura
<!-- id: AnaliticaIngesta.validarEstructura -->
<!-- entidades: Cargue -->
<!-- impuesto_en: RESP L2279 (p. 87) GMI P77 f («estructura, encabezados, número y tipo de campos [...] Los archivos que no cumplan estas condiciones serán rechazados, indicando los errores») -->
<!-- decision: diseño 2026-10-22 (estructura → archivo; contenido → registro; ver Contradicciones 1) -->

El cargue completo DEBE quedar «rechazado», sin ningún hecho, cuando falla la estructura: archivo vacío o sin encabezado, codificación no UTF-8, encabezados repetidos, obligatorios sin mapear, o filas con número de campos distinto del encabezado en más del 1 % de las líneas. La bitácora registra recibidos = líneas leídas, procesados = 0, rechazados = recibidos y cada causa con la primera línea donde ocurre.

#### Scenario: Columnas corridas
<!-- test: por escribir: IngestaApiTest.rechazoEstructural -->
- **CUANDO** 3 % de las filas tienen 10 campos y el encabezado 9
- **ENTONCES** estado «rechazado», motivo «Número de campos distinto del encabezado (60 000; primera línea 18)»
<!-- incierto: el umbral del 1 % es propuesta; por debajo, esas líneas se rechazan una a una. -->

### Requirement: Validación por registro con causa y línea
<!-- id: ValidadorStaging -->
<!-- entidades: Cargue, Rechazo -->
<!-- impuesto_en: A5 L552 (p. 21) 4.7.1 punto 3 (rechazados y motivo); RESP L2279 (p. 87) (tipos, fechas, obligatorios, duplicados); A4 L388–390 (p. 14) §9.2; A4 L330–336 (p. 12) §7 (válidos, rechazados, duplicados); RESP L3943–3945 (p. 150) (duplicados e incompletos sin conocerlos de antemano) -->
<!-- decision: diseño 2026-10-22 (SQL por conjuntos sobre staging) -->

Cada registro DEBE validarse con SQL por conjuntos sobre staging y, si falla, rechazarse con su línea y **todas** sus causas de este catálogo cerrado: obligatorio vacío (con la columna), fecha inválida, fecha fuera del periodo declarado o futura, número inválido, cantidad ≤ 0, valor < 0, CUM con formato inválido (`^\d{1,9}-\d{1,2}$` tras normalizar), Fuerza no reconocida, modalidad no reconocida, duplicado exacto dentro del cargue, duplicado por llave natural, ya cargado en otro cargue aplicado. Fuerza y modalidad se reconocen por código o nombre con sinónimos («Ejército», «EJERCITO», «EJC» → EJC). El ESM que no existe en `puntos` NO se rechaza: se guarda con el código del insumo y queda marcado «ESM sin correspondencia» para el tablero de calidad. Medicamento o CUM sin correspondencia en la tabla maestra NO es causa de rechazo de la ingesta (lo resuelve `analitica-homologacion`).

#### Scenario: Registro con dos errores
<!-- test: por escribir: IngestaApiTest.rechazoConCausas -->
- **CUANDO** la línea 1 044 tiene fecha «31/02/2025» y cantidad «-2»
- **ENTONCES** se rechaza con las causas «Fecha inválida» y «Cantidad menor o igual a cero», línea 1 044, y cuenta una sola vez en rechazados

#### Scenario: Duplicado exacto
<!-- test: por escribir: IngestaApiTest.duplicadoExacto -->
- **CUANDO** las líneas 50 y 9 000 son idénticas en todas las columnas mapeadas
- **ENTONCES** se procesa la 50 y se rechaza la 9 000 con «Duplicado exacto de la línea 50»
<!-- duda: llave natural de duplicados (ver Dudas 4) -->

#### Scenario: ESM desconocido
<!-- test: por escribir: IngestaApiTest.esmSinCorrespondencia -->
- **CUANDO** el código de ESM «ESM-999» no existe en `puntos`
- **ENTONCES** el registro se procesa con `esm_codigo = 'ESM-999'`, `punto_id` nulo, y suma en «ESM sin correspondencia»
<!-- duda: 5 -->

### Requirement: Rechazos agrupados y descargables sin datos personales
<!-- id: CarguesApi.rechazos -->
<!-- entidades: Rechazo -->
<!-- impuesto_en: A5 L552 (p. 21) 4.7.1 punto 3 (motivo del rechazo); RESP L2279 (p. 87) («indicando los errores identificados para su corrección y nueva entrega»); RESP L4357–4363 (p. 168) -->
<!-- decision: diseño 2026-10-22 (línea y causa, nunca el contenido de la celda) -->

El motivo DEBE mostrarse agrupado por causa con su conteo («CUM con formato inválido (241), Fecha inválida (71)») y descargarse en CSV «;» UTF-8 con `linea;causa;columna`, sin el valor de la celda, para devolverlo al OPL.

#### Scenario: Descarga de rechazos
<!-- test: por escribir: IngestaApiTest.descargaRechazos -->
- **CUANDO** el analista descarga los rechazos de un cargue con 312
- **ENTONCES** recibe un CSV de 312 filas (o más, una por causa) con `linea;causa;columna` y la descarga queda en la bitácora

### Requirement: Una transacción lógica por cargue
<!-- id: AplicadorHechos.aplicar -->
<!-- entidades: Cargue, Dispensacion -->
<!-- impuesto_en: A5 L723–731 (p. 24) (reproducibilidad); A5 L962 (p. 34) 14.8 -->
<!-- decision: diseño 2026-10-22 -->

Un cargue DEBE quedar «aplicado» completo o no verse en ninguna consulta: los hechos se insertan con su `cargue_id` y las consultas solo leen hechos de cargues en estado «aplicado» con `aplicado_en` ≤ corte. El paso a «aplicado», la escritura de la bitácora y el refresco de agregados ocurren al final; si cualquier etapa falla, el cargue queda «fallido», sus hechos se borran (o se dejan invisibles hasta limpiarse) y las consultas siguen dando lo mismo que antes.

#### Scenario: Caída a mitad de la carga
<!-- test: por escribir: IngestaApiTest.fallaSinEfecto -->
- **CUANDO** el proceso falla tras insertar 1,2 M de 2 M hechos
- **ENTONCES** el cargue queda «fallido» con la etapa y el error, y el total del tablero general no cambia

### Requirement: Idempotencia y reemplazo de periodo
<!-- id: ServicioCargues.crear (idempotencia) -->
<!-- entidades: Cargue -->
<!-- impuesto_en: A4 L330–336 (p. 12) (duplicados); PLIEGO L2406–2427 (p. 66–67) P1 (depurar duplicados) -->
<!-- decision: diseño 2026-10-22 (mismo SHA-256 o llave natural no duplica; reemplazo de periodo a elección) -->

Subir un archivo con el SHA-256 de un cargue «aplicado» DEBE devolver ese cargue sin procesar de nuevo («Este archivo ya se cargó el 21/10/2026 11:12»). Al confirmar el mapeo el usuario elige «Agregar» (por defecto: los registros cuya llave natural ya está en un cargue aplicado se rechazan como «Ya cargado») o «Reemplazar el periodo» (el cargue nuevo sustituye, desde su aplicación, a los cargues de la misma fuente cuyo periodo queda contenido en el suyo; los reemplazados se marcan y sus hechos dejan de verse en cortes posteriores pero siguen viéndose en cortes anteriores).

#### Scenario: Mismo archivo dos veces
<!-- test: por escribir: IngestaApiTest.mismoArchivo -->
- **CUANDO** se cierra la subida de un archivo cuyo SHA-256 ya está aplicado
- **ENTONCES** 200 con el id del cargue existente y no se crea otro

#### Scenario: Corrección del OPL
<!-- test: por escribir: IngestaApiTest.reemplazoPeriodo -->
- **CUANDO** se aplica con «Reemplazar el periodo» un archivo corregido de septiembre 2026
- **ENTONCES** el corte nuevo suma solo el corregido y una consulta con el corte anterior da el total original

### Requirement: Bitácora del cargue
<!-- id: Cargue (bitácora) -->
<!-- entidades: Cargue -->
<!-- impuesto_en: A5 L552 (p. 21) 4.7.1 punto 3 (fecha y hora, fuente, periodo procesado, recibidos, procesados, rechazados, motivo, usuario o proceso responsable); A5 L948 (p. 33) 14.3 («la bitácora del cargue registra todos los atributos exigidos»); A4 L766–782 (p. 23–24) 11.2.1 -->
<!-- decision: diseño 2026-10-22 (+ duración por etapa y SHA-256) -->
<!-- test: caso M8-3 -->

Cada cargue, aplicado, rechazado o fallido, DEBE dejar en la bitácora de cargues: fecha y hora (de recepción y de aplicación, hora militar), fuente, periodo procesado (fecha mínima – máxima de dispensación de los procesados), recibidos, procesados, rechazados, motivo del rechazo agrupado, usuario o proceso responsable, duración total y por etapa (subida, lectura y COPY, validación, carga de hechos, agregados) y SHA-256 del archivo o del envío. Los rechazos de estructura también quedan.

#### Scenario: Caso M8-3
<!-- test: por escribir: IngestaApiTest.bitacoraCompleta; caso M8-3 -->
- **CUANDO** el analista carga el archivo de un año con 2 000 000 de registros y 1 250 errores sembrados
- **ENTONCES** la fila muestra «22/10/2026 14:35 · Archivo CSV (;) UTF-8 · 01/01/2025 – 31/12/2025 · 2000000 · 1998750 · 1250 · <causas con conteo> · jgarcia · analista», con duración y SHA-256 en el detalle

### Requirement: Procesamiento diferido de a un cargue, con estados y progreso
<!-- id: ProcesadorCargues / ServicioCargues.estado -->
<!-- entidades: Cargue -->
<!-- impuesto_en: RESP L2217 (p. 84) (actualización diaria, rezago ≤ 48 h); A5 L948 (p. 33) 14.3; caso M10-7 (Anexo 5 2.5, ingesta de 2 M con 10–20 usuarios concurrentes) -->
<!-- decision: diseño 2026-10-22 (patrón de exportacion/: ejecutor de un hilo, advisory lock, FOR UPDATE SKIP LOCKED) -->

Los cargues confirmados DEBEN procesarse fuera de la petición, por un ejecutor de un hilo que toma el siguiente «pendiente» con `FOR UPDATE SKIP LOCKED` bajo `pg_try_advisory_lock`; uno a la vez en todo el sistema, los demás en cola con su posición. Estados: `subiendo → mapeo → pendiente → procesando → aplicado`, o `rechazado` (estructura) o `fallido` (error técnico, huella o «Proceso interrumpido» si al arrancar quedó uno «procesando»). `GET /api/analitica/cargues/{id}` devuelve estado, etapa, porcentaje y filas leídas; la pantalla lo refresca cada 2 s.
<!-- incierto: el diseño nombra pendiente/procesando/aplicado/fallido; «subiendo», «mapeo» y «rechazado» se agregan porque la subida por partes, la confirmación del mapeo y RESP L2279 los necesitan. -->

#### Scenario: Dos cargues seguidos
<!-- test: por escribir: IngestaApiTest.colaDeUno -->
- **CUANDO** se confirman dos cargues casi a la vez
- **ENTONCES** el segundo queda «pendiente (posición 1)» hasta que el primero termina

#### Scenario: Caso M10-7
<!-- test: caso M10-7; por escribir: herramientas de carga (k6 o Playwright) documentadas en docs/rendimiento_analitica.md -->
- **CUANDO** se ingieren 2 M registros mientras 10 a 20 usuarios consultan tableros
- **ENTONCES** la aplicación responde sin caerse y la ingesta termina «aplicado»

### Requirement: Carga en hechos particionados por mes
<!-- id: AplicadorHechos.cargar -->
<!-- entidades: Dispensacion -->
<!-- impuesto_en: A5 L552 (p. 21) (totalidad de la data histórica); A4 L522 (p. 17) (niveles); A4 L811–843 (p. 25) (variables transversales); RESP L3917 (p. 149) -->
<!-- decision: diseño 2026-10-22 (ana_dispensaciones RANGE por mes, MERGE desde staging, BRIN por fecha) -->

Los registros válidos DEBEN pasar de staging a `ana_dispensaciones`, particionada por mes de dispensación, creando antes las particiones que falten (con sus índices BRIN por fecha y B-tree por ESM, CUM, medicamento, CIE-10, paciente_seudo). Cada hecho guarda `cargue_id`, `linea_origen`, la Fuerza y su DISAN (`par_fuerzas.sanidad`), la regional, el ESM (`punto_id` y código del insumo), la modalidad, el dato original del medicamento y del CUM, y lo opcional que venga. Medicamento, principio activo y ATC se resuelven contra la versión vigente de la tabla maestra (`analitica-homologacion`); lo no resuelto queda con estado de homologación «pendiente» y **sí** suma en los totales.

#### Scenario: Año completo
<!-- test: por escribir: IngestaApiTest.particiones -->
- **CUANDO** se aplica un insumo de enero a diciembre de 2025 sin particiones previas
- **ENTONCES** existen 12 particiones `ana_dispensaciones_2025_01…12` y cada hecho está en la de su mes

### Requirement: Refresco de agregados al aplicar
<!-- id: AplicadorHechos.refrescarAgregados -->
<!-- entidades: AgregadoMensual -->
<!-- impuesto_en: RESP L2217 (p. 84) (tableros actualizados a diario); A5 L359 (p. 15) 2.7 (≤ 3 min ver) -->
<!-- decision: diseño 2026-10-22 (agregados mes × ESM × modalidad × medicamento × CIE-10, V62) -->

Al aplicar un cargue se DEBEN refrescar, antes de marcarlo «aplicado», los agregados de los meses que toca, y avisar a `analitica-cohortes-alertas` para recalcular cohortes y alertas (después de aplicar, sin bloquear el cargue). Los pacientes distintos no se suman entre celdas: se cuentan exactos sobre hechos.

#### Scenario: Cuadre agregado contra hechos
<!-- test: por escribir: CuadreInsumoTest.agregadosIgualHechos -->
- **CUANDO** se aplica un cargue
- **ENTONCES** la suma de gasto y unidades de los agregados de cada mes es igual a la de los hechos de ese mes

### Requirement: Fecha de corte y reproducibilidad
<!-- id: Corte -->
<!-- entidades: Cargue -->
<!-- impuesto_en: A5 L552 (p. 21) (filtro «fecha de corte»); A5 L723–731 (p. 24) («dos usuarios con el mismo perfil, periodo y filtros obtengan resultados consistentes»); A5 L962 (p. 34) 14.8; A4 L883 (p. 26) (reproducción por terceros) -->
<!-- decision: diseño 2026-10-22 -->

La fecha de corte DEBE ser la fecha y hora de aplicación de un cargue (por defecto el último aplicado; se puede elegir uno anterior) y toda consulta analítica se resuelve con los cargues aplicados hasta ese momento y no reemplazados a esa fecha. Misma consulta + mismo corte + mismo alcance = mismo resultado, aunque después se apliquen otros cargues. El corte se muestra como «Corte 21/10/2026» (mockup).

#### Scenario: Cargue posterior
<!-- test: por escribir: IngestaApiTest.corteReproducible -->
- **CUANDO** se consulta el gasto con corte C1, luego se aplica otro cargue y se repite con corte C1
- **ENTONCES** las dos respuestas son idénticas; con el corte nuevo el gasto aumenta en lo del cargue

### Requirement: Conservación del archivo original
<!-- id: ServicioCargues.conservar -->
<!-- entidades: Cargue -->
<!-- impuesto_en: A4 L382–384 (p. 14) («conservando los soportes y evidencias que permitan validar su origen»); A5 L743–765 (p. 24) 4.7.4 (recorrido desde la información original) -->
<!-- decision: diseño 2026-10-22 (Almacen con SHA-256, vigencia parametrizable, solo ADMINISTRADOR) -->
<!-- duda: 3 -->

El archivo (o el envío del servicio web serializado) DEBE guardarse en el `Almacen` con su SHA-256 hasta `conservar_hasta` (parámetro, por defecto 365 días); solo el ADMINISTRADOR lo descarga, y la descarga queda en la bitácora. Una tarea diaria lo borra al vencer, deja el cargue y sus hechos, y registra el borrado.

#### Scenario: Analista pide el original
<!-- test: por escribir: IngestaApiTest.originalSoloAdmin -->
- **CUANDO** un ANALISTA pide `GET /api/analitica/cargues/{id}/original`
- **ENTONCES** 403

### Requirement: Perfiles que cargan y consultan la bitácora
<!-- id: Pantallas.ANALITICA_CARGUES -->
<!-- entidades: Perfil -->
<!-- impuesto_en: RESP L473–483 (p. 21–22) AGS-30 (roles Analista, Administrador, OPL); A4 L784 (p. 24) 11.2.2 (matriz de perfiles) -->
<!-- decision: diseño 2026-10-22 (ADMINISTRADOR y ANALISTA cargan; COORDINADOR consulta; OPL_NACIONAL solo el servicio web) -->

Crear cargues, confirmar mapeos y descargar rechazos DEBE estar permitido a ADMINISTRADOR y ANALISTA; COORDINADOR ve la bitácora y el detalle, sin cargar; OPL_NACIONAL solo usa el servicio web (no ve la pantalla); los demás perfiles reciben 403. Todos los endpoints llevan `@Accion` y `@PreAuthorize("@permisos.pantalla('ANALITICA_CARGUES')")` (el del servicio web, su propia regla).

#### Scenario: Coordinador intenta cargar
<!-- test: por escribir: IngestaApiTest.coordinadorSoloConsulta -->
- **CUANDO** el COORDINADOR crea un cargue
- **ENTONCES** 403; `GET /api/analitica/cargues` le responde 200

### Requirement: Pantalla «Cargues del OPL»
<!-- id: CarguesOpl.tsx -->
<!-- entidades: Cargue -->
<!-- impuesto_en: mockup `#interop` «Bitácora de cargues» (columnas y botones «Cargar archivo estructurado», «Exportar CSV»); A5 L948 (p. 33) 14.3 («muestra la bitácora resultante»); A5 L356 (p. 15) 2.4 (coma decimal, sin separador de miles) -->
<!-- decision: diseño 2026-10-22 (pantalla ANALITICA_CARGUES, ruta analitica/cargues, grupo «Información») -->

La pantalla DEBE mostrar la bitácora con las columnas del mockup (Fecha y hora · Fuente · Periodo procesado · Recibidos · Procesados · Rechazados · Motivo del rechazo · Responsable), más Estado; cifras sin separador de miles, rechazados > 0 en rojo; filtros por fuente, estado y fechas; botones «Cargar archivo estructurado» (subida por partes con avance, mapeo, «Agregar»/«Reemplazar el periodo», confirmación) y «Exportar CSV» de la bitácora. El detalle de un cargue muestra duración por etapa, SHA-256, motivos con conteo y «Descargar rechazos». Todo sin salir del sistema.

#### Scenario: Ver el cargue recién aplicado
<!-- test: por escribir: e2e analitica-cargues.spec.ts; caso M8-3 -->
- **CUANDO** el analista termina un cargue
- **ENTONCES** la fila aparece arriba con estado «Aplicado» y los ocho atributos, sin recargar la página

### Requirement: Eventos en la bitácora general
<!-- id: Bitacora.TIPOS (analitica_cargue_*) -->
<!-- entidades: LogTransaccion -->
<!-- impuesto_en: A5 L743–765 (p. 24) 4.7.4 (cargues, descargas, ajustes en fuentes); A5 L385 (p. 16) 3.1; A5 L962 (p. 34) 14.9 (usuario, fecha, hora, acción y objeto) -->
<!-- decision: diseño 2026-10-22 -->

DEBEN registrarse en `log_transacciones`, con usuario o «Proceso automático», sin datos personales: `analitica_cargue_creado`, `analitica_cargue_mapeo` (plantilla usada o guardada), `analitica_cargue_aplicado`, `analitica_cargue_rechazado`, `analitica_cargue_fallido`, `analitica_cargue_rechazos_descarga`, `analitica_cargue_bitacora_exportar`, `analitica_cargue_original_descarga`, `analitica_cargue_original_borrado`, `analitica_ws_lote` (solo el primero y el final de cada envío). La descripción lleva id del cargue, fuente y conteos.

#### Scenario: Cargue aplicado en el log
<!-- test: por escribir: IngestaApiTest.eventosBitacora -->
- **CUANDO** se aplica un cargue
- **ENTONCES** hay un `analitica_cargue_aplicado` con «Cargue 17 · Archivo CSV (;) UTF-8 · 2000000 recibidos · 1250 rechazados»

### Requirement: Generador sintético de 2 M filas con errores sembrados
<!-- id: GeneradorSintetico -->
<!-- entidades: — -->
<!-- impuesto_en: hoja de ruta, Preparación 12 («1,5–2 M de registros de un año [...] con errores sembrados») y etapa E0; RESP L4513–4515 (p. 174) (en 14.3 los datos los pone el oferente); RESP L2017 (p. 77) -->
<!-- decision: diseño 2026-10-22 (Java, sin datos reales, totales esperados calculados aparte sin SQL) -->

DEBE existir un generador en Java (herramientas o fuente de prueba), con semilla fija, que produzca un año de dispensaciones (2 M por defecto, tamaño parametrizable) con las columnas mínimas del insumo y documentos ficticios, en CSV «;» UTF-8 y en xlsx; que siembre una cantidad conocida de cada causa de rechazo y de casos para homologación, cohortes y alertas; y que escriba aparte `totales_esperados.csv` (recibidos, rechazados por causa, procesados, y gasto, unidades, dispensaciones y pacientes distintos por mes, Fuerza, DISAN, ESM, modalidad, ATC 1–5, principio activo, medicamento, CUM y CIE-10) calculados en el propio generador sin pasar por la base.

#### Scenario: Semilla fija
<!-- test: por escribir: GeneradorSinteticoTest.determinista -->
- **CUANDO** se genera dos veces con la misma semilla
- **ENTONCES** los dos archivos tienen el mismo SHA-256

### Requirement: Script de totales del insumo real
<!-- id: herramientas/totales_insumo.py -->
<!-- entidades: — -->
<!-- impuesto_en: hoja de ruta, Preparación 13 («Gasto, unidades y pacientes por dimensión calculados fuera del sistema»); A5 L948 (p. 33) 14.5 -->
<!-- decision: diseño 2026-10-22 -->

DEBE existir `herramientas/totales_insumo.py` (biblioteca estándar, en flujo) que, con el archivo de la prueba y el mapeo, calcule fuera del sistema la misma salida que `totales_esperados.csv` con las mismas reglas de rechazo.

#### Scenario: Concuerda con el generador
<!-- test: por escribir: CI ejecuta el script sobre el archivo sintético pequeño y compara con totales_esperados.csv -->
- **CUANDO** se corre sobre el archivo del generador
- **ENTONCES** su salida es idéntica a `totales_esperados.csv`

### Requirement: Pruebas de cuadre
<!-- id: CuadreInsumoTest -->
<!-- entidades: Dispensacion -->
<!-- impuesto_en: A5 L948 (p. 33) 14.5 («cifras coinciden con el insumo [...] y son consistentes al cruzarlas entre dimensiones»); caso M8-5 -->
<!-- decision: diseño 2026-10-22 -->

DEBE haber una prueba que cargue el archivo sintético por la API y compare, por cada dimensión, gasto, unidades, dispensaciones y pacientes distintos contra `totales_esperados.csv`, y que la suma por cualquier dimensión sea igual al total. En CI corre con 50 000 filas; con 2 M, bajo demanda.

#### Scenario: Consistencia entre dimensiones
<!-- test: por escribir: CuadreInsumoTest.consistenciaDimensiones -->
- **CUANDO** se aplica el archivo sintético
- **ENTONCES** recibidos = esperado, rechazados por causa = sembrados, y Σ gasto por ATC 1 = Σ por ESM = Σ por mes = total esperado (al centavo)

### Requirement: Tiempos medidos
<!-- id: Cargue.etapas -->
<!-- entidades: Cargue -->
<!-- impuesto_en: RESP L2017 (p. 77) (1,5–2 M); A5 L357 (p. 15) 2.5 (estrés); caso M8-3 («cronometrar»); caso M10-7 -->
<!-- decision: diseño 2026-10-22 (meta < 5 min para 2 M en el equipo de desarrollo) -->

La duración de cada etapa DEBE medirse y guardarse en el cargue; la prueba de rendimiento con 2 M filas se corre dos veces y sus resultados (ingesta por etapa, con y sin usuarios concurrentes) se anotan en `docs/rendimiento_analitica.md`. Meta interna: 2 M filas aplicadas en menos de 5 minutos.

#### Scenario: Medición
<!-- test: por escribir: RendimientoIngestaTest (bajo demanda) -->
- **CUANDO** se ingiere el archivo sintético de 2 M
- **ENTONCES** el detalle muestra las cinco duraciones y la total (< 300 s)

### Invariant: El identificador del paciente nunca se guarda en claro
<!-- entidades: Dispensacion, Staging, Rechazo, LogTransaccion -->
<!-- impuesto_en: A5 L737–739 (p. 24) 4.7.3; RESP L2209 (p. 84); RESP L4551–4577 (p. 176–177); Ley 1581 (A5 L242) -->
<!-- verificado_por: por escribir: SeudonimizadorTest.sinClaroEnBase (busca los documentos sembrados en todas las tablas y en el log) -->

Ninguna tabla, log, archivo de rechazos, plantilla de mapeo ni respuesta de la API DEBE contener el documento, nombre, contacto o fecha de nacimiento completa del paciente; solo el seudónimo HMAC. La única copia en claro es el archivo original del `Almacen` (ver su requisito).

### Invariant: Cuadre de conteos del cargue
<!-- entidades: Cargue -->
<!-- impuesto_en: A5 L552 (p. 21) 4.7.1 punto 3 -->
<!-- verificado_por: por escribir: CHECK en ana_cargues + IngestaApiTest.bitacoraCompleta -->

En todo cargue terminado DEBE cumplirse `recibidos = procesados + rechazados`, y `procesados` = número de hechos con su `cargue_id`; la suma de conteos por causa es ≥ rechazados (un registro puede tener varias causas).

### Invariant: Solo lo aplicado se ve
<!-- entidades: Dispensacion, Cargue -->
<!-- impuesto_en: A5 L723–731 (p. 24) -->
<!-- verificado_por: por escribir: IngestaApiTest.fallaSinEfecto -->

Ninguna consulta analítica (tableros, cohortes, alertas, asistente, descargas) DEBE leer hechos de un cargue que no esté «aplicado» a la fecha de corte usada.

### Invariant: Todo hecho es trazable a su origen
<!-- entidades: Dispensacion, Cargue -->
<!-- impuesto_en: A5 L743–765 (p. 24) 4.7.4 («recorrer el dato desde la información original»); A4 L869 (p. 26) entregable 7; RESP L3989 (p. 151); RESP L5173 (p. 204) -->
<!-- verificado_por: por escribir: IngestaApiTest.trazabilidadLinea -->

Cada hecho DEBE tener `cargue_id` y `linea_origen` no nulos, y el cargue su fuente y SHA-256, de modo que cualquier cifra se rastree a archivo y línea (lo usa el rastreo 14.9 de `analitica-tableros`).

### Invariant: Un archivo aplicado no se repite y un solo cargue en proceso
<!-- entidades: Cargue -->
<!-- impuesto_en: A4 L330–336 (p. 12) (duplicados); PLIEGO L2406–2427 P1; decisión de diseño 2026-10-22 (cola de uno) -->
<!-- verificado_por: por escribir: únicos parciales `ana_cargues (sha256) WHERE estado = 'aplicado'` y `ana_cargues ((true)) WHERE estado = 'procesando'` + IngestaApiTest.mismoArchivo, colaDeUno -->

No DEBE haber dos cargues «aplicado» con el mismo SHA-256, ni más de un cargue «procesando».

### Invariant: Base de la prueba sin hechos precargados
<!-- entidades: Dispensacion -->
<!-- impuesto_en: A5 L982 (p. 35) condición 4 (solo tablas paramétricas) -->
<!-- verificado_por: por escribir: MigracionesTest (V60 no inserta en ana_dispensaciones, ana_cargues ni plantillas) -->

Las migraciones NO DEBEN insertar hechos, cargues ni plantillas de mapeo: el insumo entra solo por la interfaz el día de la prueba.

---

## Contradicciones registradas

1. **Rechazo del archivo o del registro.** RESP L2279 (GMI P77 f) dice que «los archivos» que no cumplan se rechazan; A5 L552 punto 3 y 14.3 piden contar registros recibidos, procesados y rechazados con su motivo, lo que supone rechazar registros. Se resuelve con dos niveles: estructura → archivo completo; contenido → solo la línea.
2. **Quién pone los datos del 14.3.** RESP L4515 (p. 174): el oferente; RESP L2017, L3899, L4047: la Entidad entrega el insumo en la sesión. Se preparan los dos: generador sintético y mapeo sin diccionario para el archivo de la DIGSA.
3. **Delimitador.** AGS-57 (RESP L773) lo deja para la ejecución; GMI P77 f (RESP L2279) fija «;». Se aceptan «;» y «,» detectados; el oficial es «;».
4. **Anonimizar o seudonimizar.** A4 L784 dice «anonimización»; RESP L2209 admite «seudonimizada». Las cohortes y los pacientes distintos exigen identificar al mismo paciente entre registros: se seudonimiza al ingresar y toda salida general es agregada.
5. **Periodicidad.** A5 L552 punto 2 remite a la periodicidad de la DIGSA; RESP L2217 pide actualización diaria con rezago ≤ 48 h; A4 L879, informes a 20 días. La ingesta admite cargues diarios (servicio web) y mensuales (archivo).
6. **Dónde vive la bitácora.** En el mockup está en «Interoperabilidad OPL» (4.1), que no es de este módulo, con un motivo «soporte sin indexar» que no aplica a la analítica. Se toma solo como referencia de columnas para «Cargues del OPL».

## Dudas

1. **Datos del día de la prueba (15.1).** Recomendado: llevar el sintético y estar listos para el insumo de la DIGSA. Mientras tanto: se construyen el generador, el mapeo y `totales_insumo.py`.
2. **Estructura real del insumo** («Anexo Herramienta Tecnológica» del OPL, sin publicar; RESP L4147). Recomendado: mapeo por sinónimos con confirmación. Mientras tanto: sinónimos amplios en `reglas/AnaliticaIngesta` y plantilla editable.
3. **Conservar o descartar el archivo original** (tiene datos personales en claro). Recomendado: conservar en el `Almacen` cifrado en reposo, solo ADMINISTRADOR, 365 días por defecto (A4 L382). Mientras tanto: se conserva con el borrado automático ya construido.
4. **Llave natural de duplicados.** Las variables mínimas (RESP L3917) no traen id de dispensación. Recomendado: si el insumo trae id de fórmula/dispensación e ítem, llave = (fuente, id, ítem); si no, solo se rechazan duplicados exactos de todas las columnas mapeadas. Mientras tanto: así, y el campo «id de dispensación» queda como opcional en el mapeo.
5. **ESM, Fuerza o modalidad fuera del catálogo.** Recomendado: Fuerza y modalidad se rechazan; ESM desconocido se acepta con su código y cuenta en calidad, para no perder gasto ni romper el cuadre. Mientras tanto: así.
6. **Cómo se muestra «simulando el web service» sin salir del sistema** (condición 12, A5 L1008) y cómo se autentica una máquina (CSRF de la sesión). Recomendado: en la prueba usar el archivo (RESP L4513 lo permite) y tener el endpoint del OPL probado; para un cliente máquina, una credencial de servicio del perfil OPL_NACIONAL sin CSRF solo en esa ruta. Mientras tanto: endpoint con sesión de OPL_NACIONAL y prueba e2e que envía los lotes.
7. **Rotación de la clave del seudónimo.** Cambiarla parte a todos los pacientes en dos. Recomendado: no rotar durante el contrato salvo incidente, y si se rota, recalcular todo. Mientras tanto: clave única en Key Vault.
8. **Umbral del 1 % de filas mal formadas** para rechazar el archivo entero. Recomendado: parámetro. Mientras tanto: 1 %.

## Datos y piezas a construir

**Migración V60 (`-- Suite DIGSA · Analítica · V60 ingesta`)**, con CHECKs, comentarios por columna y GRANT al `${rol_app}`:

| Tabla | Columnas principales |
|---|---|
| `ana_cargues` | `id` identity, `fuente` CHECK (`archivo`, `ws_opl`), `fuente_texto`, `nombre_archivo`, `formato` (`csv`, `txt`, `xlsx`, `gz`, `zip`, `json`), `tamano_bytes`, `sha256` char(64), `almacen_ruta`, `conservar_hasta`, `plantilla_id`, `modo` CHECK (`agregar`, `reemplazar`), `estado` CHECK (`subiendo`, `mapeo`, `pendiente`, `procesando`, `aplicado`, `rechazado`, `fallido`), `etapa`, `porcentaje`, `periodo_desde`, `periodo_hasta`, `recibidos`, `procesados`, `rechazados`, `motivos` jsonb (causa → conteo), `etapas_ms` jsonb, `duracion_ms`, `usuario_id` (nulo = proceso), `responsable_texto`, `creado`, `iniciado`, `aplicado_en`, `terminado`, `reemplazado_por`, `error` (≤ 500); CHECK `recibidos = procesados + rechazados` al terminar; únicos parciales por `sha256` aplicado y por `procesando` |
| `ana_cargue_partes` | `cargue_id`, `numero`, `tamano`, `sha256`, `almacen_ruta`; PK (`cargue_id`, `numero`) |
| `ana_ws_lotes` | `cargue_id`, `envio`, `numero`, `registros`, `recibido`; único (`envio`, `numero`) |
| `ana_cargue_rechazos` | `cargue_id`, `linea` text, `causa` (código del catálogo), `columna`; índice (`cargue_id`, `causa`); sin valores de celda |
| `ana_mapeo_plantillas` | `id`, `nombre`, `firma_encabezado` (SHA-256 de los encabezados normalizados), `mapeo` jsonb, `creado_por`, `creado` |
| `ana_staging_dispensacion` | UNLOGGED: `cargue_id`, `linea`, columnas de texto crudo ya seudonimizadas; se vacía por cargue al terminar (en `Exportacion.TECNICAS`) |
| `ana_dispensaciones` | PARTITION BY RANGE (`fecha`) mensual: `id` bigint, `cargue_id`, `linea_origen`, `paciente_seudo` char(64), `anio_nacimiento`, `fuerza`, `disan`, `regional_id`, `punto_id`, `esm_codigo`, `modalidad`, `fecha`, `cum`, `cum_original`, `medicamento_original`, `medicamento_id`, `principio_activo_id`, `atc5`, `cie10`, `cantidad` numeric, `valor_total` numeric(16,2), `valor_unitario`, `codigo_ssfm`, `profesional_seudo`, `especialidad`, `tipo_formula`, `homil` bool, `id_dispensacion`; BRIN (`fecha`), B-tree por dimensiones (en `Exportacion.EXCLUIDAS` justificado por tamaño, o en `TABLAS` si la exportación en flujo lo soporta) |
| `ana_dim_tiempo` | `fecha` PK, `semana`, `mes`, `bimestre`, `trimestre`, `semestre`, `anio` (se llena para el rango de cada cargue) |

Fuerza, DISAN, regional, ESM y modalidad usan `par_fuerzas`, `par_regionales`, `puntos`, `par_modalidades`. Medicamento, principio activo, ATC y CIE-10 son de V61 (`analitica-homologacion`); los agregados, de V62 (`analitica-tableros`). Tipos nuevos en `Bitacora.TIPOS` (bloque `// --- Analítica (rama analitica) ---`).

**Clases**

| Pieza | Responsabilidad |
|---|---|
| `reglas/AnaliticaIngesta` (+ JUnit) | detectar delimitador y BOM, normalizar encabezado, sinónimos de campos y de valores (Fuerza, modalidad), proponer mapeo, leer número con coma o punto, leer fechas (dd/mm/aaaa, aaaa-mm-dd, serial xlsx), formato de CUM, catálogo de causas con su texto, columnas personales a descartar |
| `analitica/ingesta/FuenteDispensacion` | puerto: abrir, encabezado, filas en flujo con línea de origen |
| `ArchivoDispensacion`, `ServicioWebOpl` | adaptadores (archivo/comprimido/xlsx SAX; lotes JSON) |
| `Seudonimizador`, `ValidacionArranqueAnalitica` | HMAC-SHA256 con `DIGSA_ANALITICA_CLAVE_SEUDONIMO`; no arrancar sin clave en `prod` |
| `ServicioCargues` | crear, partes, cerrar (SHA-256), mapeo, idempotencia, cola, conservación |
| `ProcesadorCargues` | ejecutor de un hilo, advisory lock, etapas y tiempos, estado y progreso |
| `CopiadorStaging` | `CopyManager` de pgjdbc hacia `ana_staging_dispensacion` |
| `ValidadorStaging` | SQL por conjuntos → `ana_cargue_rechazos` y `motivos` |
| `AplicadorHechos` | particiones, MERGE a hechos, reemplazo de periodo, refresco de agregados, evento para cohortes y alertas |
| `CarguesApi`, `OplDispensacionApi` | endpoints con `@Accion` |
| `herramientas/GeneradorSintetico` (Java) y `herramientas/totales_insumo.py` | insumo sintético + `totales_esperados.csv`; totales del insumo real |

**Endpoints**

| Método y ruta | Perfil |
|---|---|
| `POST /api/analitica/cargues` (nombre, tamaño, SHA-256, n.º de partes) | ADMINISTRADOR, ANALISTA |
| `PUT /api/analitica/cargues/{id}/partes/{n}` · `POST …/{id}/cerrar` | ADMINISTRADOR, ANALISTA |
| `GET …/{id}/mapeo` · `POST …/{id}/mapeo` (mapeo, modo, guardar plantilla) | ADMINISTRADOR, ANALISTA |
| `GET /api/analitica/cargues` (bitácora paginada) · `GET …/{id}` (estado, etapas) | + COORDINADOR |
| `GET …/{id}/rechazos.csv` · `GET /api/analitica/cargues/bitacora.csv` | ADMINISTRADOR, ANALISTA, COORDINADOR |
| `GET …/{id}/original` | ADMINISTRADOR |
| `POST /api/analitica/opl/dispensacion` (lote JSON) | OPL_NACIONAL |

**Pantallas:** `Pantallas.ANALITICA_CARGUES` («Cargues del OPL», ruta `analitica/cargues`, grupo «Información», {ADMINISTRADOR, ANALISTA, COORDINADOR}); `paginas/CarguesOpl.tsx` con la bitácora, el asistente de subida (avance, mapeo con vista previa enmascarada, modo, confirmación) y el detalle del cargue; ruta en `App.tsx` dentro del bloque de la rama.

**Pruebas:** `AnaliticaIngestaTest`, `SeudonimizadorTest`, `ArchivoDispensacionTest`, `IngestaApiTest`, `CuadreInsumoTest` (50 000 en CI), `GeneradorSinteticoTest`, `RendimientoIngestaTest` (2 M, bajo demanda), e2e `analitica-cargues.spec.ts` (caso M8-3); M10-7 documentado en `docs/rendimiento_analitica.md`.
