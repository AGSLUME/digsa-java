# Especificación: analitica-homologacion

> Escrita por spec-miner el 2026-10-22. **No hay PHP de referencia**: sale de la fuente de verdad (A4, A5, RESP y PLIEGO en `FUENTE DE VERDAD/md/`), del mockup M8 (`#homologacion`, l.673–723), de la hoja de ruta de la prueba en sitio (caso M8-4), de la investigación de fuentes oficiales del 2026-10-22 y del diseño común del módulo de la misma fecha (que manda).
> Fuentes: A5 4.7.2 (L649–700, tabla maestra L691), A5 4.7.4 (L743–765), A5 1.1 (L242), A5 14.4 (L948), A5 condición 4 (L982); A4 §6 (L320–326), §7 (L330–336), 11.2.1 y 11.2.5 (L766–788); RESP L2287–2291, L3853–3857, L3943–3945, L4357–4363, L4535–4545, L5099–5103, L5657–5661, L5671–5675, L5689–5693; PLIEGO L2406–2427 (P1–P3); Res. 3311/2018 y 1113/2020 (citadas en P3, no transcritas: RESP L2291).
> Relacionadas: `analitica-ingesta` (hechos y staging), `analitica-tableros` (filtro por estado de homologación, trazabilidad 14.9), `analitica-asistente` (mismo proveedor de IA que `SugeridorHomologacion`).
> Estado en Java (worktree `digsa-java-analitica`, fe9c98e): **no existe tabla de medicamentos** ni ninguna referencia oficial; todo es nuevo (V61).

Abreviaturas de cita: A4, A5, RESP (texto OCR; ante diferencias manda el PDF), PLIEGO, MOCK (mockup M8), FO (`fuentes_oficiales.md`, con marca [V] verificado / [S] secundaria / [NV] no verificado), DIS (diseño 2026-10-22).

---

### Requirement: Registro de fuentes oficiales con versión y fecha de la fuente
<!-- id: ServicioReferencias.registrarVersion -->
<!-- entidades: RefFuente, RefVersion -->
<!-- impuesto_en: RESP L4535–4545 («la herramienta deberá permitir identificar y documentar la fecha de actualización de la fuente empleada en cada proceso de homologación»); RESP L2287–2291; RESP L5657–5661; PLIEGO L2407 (P2: «identificando y documentando la última fecha de actualización disponible») -->
<!-- decision: diseño 2026-10-22 -->

Cada fuente oficial DEBE existir en `ref_fuente` (código, nombre, URL o dataset, licencia, atribución) y cada carga DEBE crear una `ref_version` con fecha de corte de la fuente (declarada por quien carga: `rowsUpdatedAt` de datos.gov.co o `max(Fecha_Actualizacion)` de SISPRO), fecha de carga, usuario, nombre del archivo, SHA-256, filas leídas y estado `cargando → validada → publicada → reemplazada` (o `rechazada`).

#### Scenario: Fuentes sembradas
<!-- test: prueba por escribir ReferenciasApiTest#fuentesSembradas -->
- **CUANDO** se aplica V61 sobre una base limpia
- **ENTONCES** existen las fuentes `INVIMA_CUM_VIGENTES` (i7cb-raxc), `INVIMA_CUM_VENCIDOS` (vwwf-4ftk), `INVIMA_CUM_RENOVACION` (vgr4-gemg), `INVIMA_CUM_OTROS` (spzp-dfuc), `SISPRO_CIE10`, `MINSALUD_PRECIO_REGULADO` (nauz-qkjw), `INVIMA_VITALES` (sdmr-tfmf), `INVIMA_ESTRECHO_MARGEN`, `MSPS_CONTROL_ESPECIAL`, `DIGSA_CODIGO_SSFM`, con licencia CC BY-SA 4.0 y atribución INVIMA o MinSalud donde FO la verificó, y ninguna versión cargada

#### Scenario: Sin fecha de corte
<!-- test: prueba por escribir ReferenciasApiTest#sinFechaCorte -->
- **CUANDO** se sube un archivo sin indicar la fecha de corte de la fuente
- **ENTONCES** responde 422 «Indique la fecha de actualización de la fuente.» y no crea la versión

---

### Requirement: Carga de una versión por archivo descargado y publicación atómica
<!-- id: ServicioReferencias.cargar / publicar -->
<!-- entidades: RefVersion, RefCumStaging -->
<!-- impuesto_en: RESP L2287 («no prescribe el mecanismo»); RESP L5673 («desde la propia interfaz de administración»); A5 L1006 (prueba sin operaciones directas en la base); FO §6 (patrón versión + staging + publicación) -->
<!-- decision: diseño 2026-10-22 (carga por archivo, nunca internet en caliente durante la prueba) -->

La carga DEBE hacerse desde la pantalla Homologación subiendo el archivo ya descargado (CSV con «,» o «;», UTF-8 con o sin BOM, o xlsx; .zip admitido), por partes como en la ingesta, a una tabla de staging UNLOGGED con todas las columnas en `text`; luego DEBE validar (encabezados, llave, fechas, conteos) y dejar la versión `validada` con un informe; solo al «Publicar» DEBE quedar como vigente, en una transacción que marca la anterior `reemplazada`. El sistema NO DEBE consultar internet durante la homologación.

#### Scenario: Publicación atómica
<!-- test: prueba por escribir ReferenciasApiTest#publicacionAtomica -->
- **CUANDO** se publica la versión 2 del CUM mientras una homologación lee la versión 1
- **ENTONCES** la homologación en curso termina con la versión 1 y la siguiente usa la 2; nunca se ve una mezcla

#### Scenario: Encabezados que no corresponden
<!-- test: prueba por escribir ReferenciasApiTest#encabezadosErrados -->
- **CUANDO** el archivo del CUM no trae `expediente` o `consecutivocum`
- **ENTONCES** la versión queda `rechazada` con «Faltan las columnas: …» y la vigente no cambia

#### Scenario: Mismo archivo dos veces
<!-- test: prueba por escribir ReferenciasApiTest#mismoSha -->
- **CUANDO** se sube un archivo con el mismo SHA-256 de una versión ya publicada de esa fuente
- **ENTONCES** responde 409 «Este archivo ya está cargado como versión N.» sin crear otra

#### Scenario: Sin acceso a la red
<!-- test: prueba por escribir HomologacionSinRedTest -->
- **CUANDO** se ejecuta la homologación con la red bloqueada
- **ENTONCES** termina igual, porque solo lee las versiones publicadas en la base

---

### Requirement: CUM INVIMA desde sus cuatro conjuntos de datos
<!-- id: AnaliticaHomologacion.normalizarCum / ServicioReferencias.cargarCum -->
<!-- entidades: RefCumPresentacion, RefCumPrincipioActivo, RefCumRol -->
<!-- impuesto_en: RESP L3853 («la validación de medicamentos se realiza contra los datos abiertos de registros sanitarios y códigos CUM del INVIMA»); FO §1.1–1.3 [V] -->
<!-- decision: diseño 2026-10-22 -->

El CUM DEBE cargarse de los 4 conjuntos de datos.gov.co (vigentes i7cb-raxc, vencidos vwwf-4ftk, en trámite de renovación vgr4-gemg, otros estados spzp-dfuc) con sus 29 columnas (expediente, producto, titular, registrosanitario, fechaexpedicion, fechavencimiento, estadoregistro, expedientecum, consecutivocum, cantidadcum, descripcioncomercial, estadocum, fechaactivo, fechainactivo, muestramedica, unidad, atc, descripcionatc, viaadministracion, concentracion, principioactivo, unidadmedida, cantidad, unidadreferencia, formafarmaceutica, nombrerol, tiporol, modalidad, ium), guardando el conjunto de origen. La llave DEBE ser `cum = expediente || '-' || consecutivocum`; las filas repetidas por principio activo y por rol DEBEN separarse en presentación (una fila por CUM), principios activos (una por CUM y principio) y roles.

#### Scenario: Combinación de principios activos
<!-- test: prueba por escribir AnaliticaHomologacionTest#cumCombinado -->
- **CUANDO** el expediente 19911481 consecutivo 1 trae 2 filas (dos principios activos) y 2 roles
- **ENTONCES** queda 1 presentación `19911481-1`, 2 principios activos y 2 roles

#### Scenario: Fechas del INVIMA
<!-- test: prueba por escribir AnaliticaHomologacionTest#fechaMesDiaAnio -->
- **CUANDO** `fechavencimiento` viene «08/23/2012»
- **ENTONCES** se guarda 2012-08-23 (formato MM/DD/YYYY); un texto no convertible queda nulo y se cuenta en el informe de la versión

#### Scenario: Mismo CUM en dos conjuntos
<!-- test: prueba por escribir ReferenciasApiTest#cumEnDosConjuntos -->
- **CUANDO** un CUM aparece en vigentes y en otros estados
- **ENTONCES** prevalece vigentes, luego en trámite, luego vencidos, luego otros, y el informe lista el conflicto

#### Scenario: IUM como atributo
- **CUANDO** una presentación trae IUM de 15 caracteres
- **ENTONCES** se guarda como atributo; la llave sigue siendo el CUM (solo ~21 % de las filas vigentes lo traen, FO §2.2)

---

### Requirement: ATC tomado del propio CUM
<!-- id: ServicioReferencias.derivarAtc -->
<!-- entidades: RefAtc -->
<!-- impuesto_en: RESP L3855 (clasificación «contra la clasificación ATC y las referencias de identificación de medicamentos» de MinSalud); FO §3 [V] (WHOCC: «Copying and distribution for commercial purposes is not allowed»; archivo electrónico de pago) -->
<!-- decision: diseño 2026-10-22 -->

El catálogo ATC DEBE derivarse de `atc` y `descripcionatc` de la versión publicada del CUM (CC BY-SA 4.0), con los niveles 1 a 5 calculados como `left(atc, 1|3|4|5|7)`; los nombres de los niveles 1 a 4 DEBEN venir de una lista parametrizable con su origen (`manual` o `WHOCC licenciado`) y NO DEBE copiarse el índice del WHOCC sin licencia.

#### Scenario: Niveles
<!-- test: prueba por escribir AnaliticaHomologacionTest#nivelesAtc -->
- **CUANDO** el CUM trae `A10BA02`
- **ENTONCES** los niveles son A, A10, A10B, A10BA y A10BA02; un código que no tenga 7 caracteres con el patrón `^[A-Z]\d{2}[A-Z]{2}\d{2}$` queda como ATC no válido en el informe

---

### Requirement: CIE-10 de SISPRO a 4 caracteres
<!-- id: ServicioReferencias.cargarCie10 / AnaliticaHomologacion.normalizarCie10 -->
<!-- entidades: RefCie10 -->
<!-- impuesto_en: A5 L673 (tabla paramétrica «Diagnóstico CIE-10»); RESP L3855 («codificación de diagnósticos contra la CIE-10»); A5 L982 (CIE-10 como tabla paramétrica precargada); FO §4 [V] -->
<!-- decision: diseño 2026-10-22 -->

La CIE-10 DEBE cargarse de la tabla SISPRO `CIE10` (código de 4 caracteres sin punto, grupo de 3, capítulo, sexo y edades) y el código del insumo DEBE normalizarse (mayúsculas, sin punto ni espacios) antes de compararlo.

#### Scenario: Código con punto o de 3 caracteres
<!-- test: prueba por escribir AnaliticaHomologacionTest#cie10 -->
- **CUANDO** el insumo trae «e11.9», «E119» o «E11»
- **ENTONCES** los dos primeros se homologan a `E119`; «E11» se acepta como grupo con estado «pendiente de revisión»; «Z999X» o un código inexistente queda «sin correspondencia»

---

### Requirement: Precio regulado, vitales no disponibles y listas normativas
<!-- id: ServicioReferencias.cargarListas -->
<!-- entidades: RefPrecioRegulado, RefVitalNoDisponible, RefListaNormativa -->
<!-- impuesto_en: RESP L2239–2241 (interacciones, estrecho margen: «corresponde al contratista disponer, a su costo, de las fuentes»); A4 L584 (vital no disponible); FO §2.3, §5.1–5.3 -->
<!-- decision: diseño 2026-10-22 -->

DEBEN cargarse como referencias versionadas: el precio máximo regulado (dataset nauz-qkjw, Circular 22 de 2026, llave cum + circular + fecha de inicio, precios de texto convertidos a numeric con coma o punto), los vitales no disponibles (sdmr-tfmf, sin CUM: se cruzan por IUM o principio activo) y las listas de estrecho margen y de control especial (solo existen en PDF: lista parametrizable `lista, principio activo normalizado, norma, fecha de la norma`) editable desde la pantalla con traza.

#### Scenario: Precio en texto
<!-- test: prueba por escribir AnaliticaHomologacionTest#precioTexto -->
- **CUANDO** el precio viene «1234,50» o «1234.5»
- **ENTONCES** queda 1234,50; un texto no numérico se rechaza en el informe con su línea

#### Scenario: Lista de estrecho margen a mano
<!-- test: prueba por escribir ReferenciasApiTest#listaManual -->
- **CUANDO** el ANALISTA agrega «WARFARINA» a estrecho margen citando el PDF del INVIMA de julio de 2024
- **ENTONCES** queda en una versión nueva de la lista con usuario, fecha, valor anterior y nuevo, y las cohortes y alertas lo leen en su siguiente ejecución

---

### Requirement: Datos de ejemplo ficticios para pruebas
<!-- id: soporte/ReferenciasDeEjemplo -->
<!-- entidades: RefVersion -->
<!-- impuesto_en: A5 L982 (base limpia en la prueba); DIS («datos de ejemplo ficticios marcados como tales») -->
<!-- decision: diseño 2026-10-22 -->

Las pruebas automáticas DEBEN usar referencias ficticias (CUM, CIE-10, listas) con el mismo formato de columnas, cargadas por el mismo flujo y con la versión marcada `ejemplo = true`; la pantalla DEBE mostrar «Datos de ejemplo» en toda versión marcada, y una versión de ejemplo NO DEBE poder publicarse en el perfil de producción.

#### Scenario: Ejemplo en producción
<!-- test: prueba por escribir ReferenciasApiTest#ejemploBloqueado -->
- **CUANDO** se intenta publicar una versión de ejemplo con el perfil `prod`
- **ENTONCES** responde 422 «Las referencias de ejemplo no se pueden publicar en producción.»

---

### Requirement: Reglas deterministas, en orden y versionadas
<!-- id: AnaliticaHomologacion.homologar -->
<!-- entidades: HomRegla, HomTablaMaestra -->
<!-- impuesto_en: A5 L651 («procesar, limpiar, normalizar y homologar»); PLIEGO L2409 (P3: homologación automática, «como mínimo la DCI, la concentración y la forma farmacéutica»); RESP L5099–5103 (puntaje = automatizada, completa y trazable); MOCK tabla maestra (R-004, R-012, R-015, R-021, R-030) -->
<!-- decision: diseño 2026-10-22 -->

La homologación DEBE ser una función pura (`reglas/AnaliticaHomologacion`) que aplica a cada dato original las reglas activas en su `orden`, cada una con id estable `R-0xx`, versión y parámetros; DEBE registrar la regla que decidió el estado y la lista de reglas que transformaron el dato. Reglas iniciales (los ids del mockup se conservan):

| Id | Regla | Resultado |
|---|---|---|
| R-001 | CUM exacto en la versión publicada, `estadocum` Activo y registro Vigente o en trámite | homologado con los atributos del INVIMA |
| R-002 | Formato del CUM: quitar espacios, ceros a la izquierda del consecutivo, aceptar «/», «.» o expediente y consecutivo en columnas separadas; patrón `^\d{1,8}-\d{1,3}$` | CUM normalizado; si no cumple, «CUM o registro no válido» |
| R-003 | Registro sanitario: formato `INVIMA AAAAM-NNNNNNN[-Rn]` y vigencia por `fechavencimiento` frente a la fecha de dispensación | «CUM o registro no válido» si no existe o venció |
| R-004 | Sinónimos DCI (ACETAMINOFEN → Paracetamol), parametrizable | principio activo en DCI |
| R-010 | Texto normalizado: mayúsculas, sin tildes, espacios simples, abreviaturas (TAB, CAP, JER, AMP, SUSP…) | texto comparable |
| R-011 | Unidades (MG, G, MCG, UI, ML, %) y conversión g ↔ mg | unidad canónica |
| R-012 | Concentración: número + unidad (+ «/ml») extraída del texto y comparada con `cantidad`/`unidadmedida` del CUM | igual u «diferencia en concentración» |
| R-013 | Principio activo comparado con el del CUM (sales toleradas por lista: «losartán» ≈ «losartán potásico») | igual o «diferencia en principio activo» |
| R-015 | Forma farmacéutica con tabla de equivalencias y tolerancia parametrizable | igual o «diferencia en forma farmacéutica» (inconsistente) |
| R-016 | Presentación (cantidad del empaque «X30» frente a `cantidadcum`) | igual o «diferencia en presentación» |
| R-021 | CUM encontrado solo en vencidos u otros estados, o `estadocum` Inactivo | «CUM o registro no válido» (registro no válido) |
| R-030 | Sin código interno SSFM para el medicamento | «pendiente de revisión» |
| R-040 | Duplicados: misma llave natural del registro de dispensación, o mismo dato original con dos CUM | «duplicado» |
| R-041 | Incompletos: corrección automática (CUM desde la descripción cuando hay una sola presentación posible; principio activo, ATC y forma desde el CUM) | dato completado con la regla anotada; si no se puede, «pendiente de revisión» |
| R-050 | Ninguna regla encuentra correspondencia | «sin correspondencia» |
| R-060 | CIE-10 (ver su requisito) | homologado, pendiente o sin correspondencia |

#### Scenario: Ejemplos del mockup
<!-- test: M8-4 -->
<!-- test: prueba por escribir AnaliticaHomologacionTest#casosMockup -->
- **CUANDO** se homologan «METFORMINA 850MG TAB X30», «ACETAMINOFEN 500 TAB», «ADALIMUMAB 40MG JER» (CUM vencido), «LOSARTAN 50 MG CAP» (CUM de tableta recubierta) e «INSULINA GLARGINA 100UI» (sin código SSFM) contra referencias de ejemplo equivalentes
- **ENTONCES** quedan, en orden: homologado por R-012 (Metformina · 850 mg · tableta · caja x 30, A10BA02); homologado por R-004 (Paracetamol · 500 mg · tableta, N02BE01); registro no válido por R-021 (sin dato final); inconsistente por R-015 (diferencia en forma farmacéutica, origen cápsula); pendiente de revisión por R-030

#### Scenario: CUM exacto vigente
<!-- test: prueba por escribir AnaliticaHomologacionTest#cumExacto -->
- **CUANDO** el insumo trae CUM `19911481-01` y el CUM `19911481-1` está Activo y Vigente
- **ENTONCES** R-002 normaliza a `19911481-1`, R-001 lo homologa y el dato final toma DCI, concentración, forma, presentación, ATC y registro del INVIMA

#### Scenario: CUM con formato imposible
<!-- test: prueba por escribir AnaliticaHomologacionTest#cumFormato -->
- **CUANDO** el CUM es «ABC-1» o «123456789-1»
- **ENTONCES** el estado es «CUM o registro no válido» por R-002 y la causa dice «formato de CUM no válido»

#### Scenario: Registro sanitario vencido a la fecha de dispensación
<!-- test: prueba por escribir AnaliticaHomologacionTest#registroVencido -->
- **CUANDO** la dispensación es del 2025-03-01 y el registro venció el 2024-12-31
- **ENTONCES** el estado es «CUM o registro no válido» por R-003, aunque el CUM exista

<!-- decision: revisión A2 2026-10-23 · R-003 compara con la fecha de dispensación más reciente del dato original en cargues aplicados (un estado por dato original y versión; evaluarlo por dispensación exigiría un estado por mes). Ver dudas_pendientes AN.15 -->

#### Scenario: Corrección automática de un incompleto (P1)
<!-- test: prueba por escribir AnaliticaHomologacionTest#incompletoCorregido -->
- **CUANDO** el registro trae CUM válido pero sin principio activo ni ATC
- **ENTONCES** R-041 los completa desde el CUM, el estado es homologado y la tabla maestra muestra «R-041 · completado desde CUM INVIMA»

#### Scenario: Duplicado
<!-- test: prueba por escribir AnaliticaHomologacionTest#duplicado -->
- **CUANDO** el mismo dato original aparece con dos CUM distintos en el mismo cargue
- **ENTONCES** ambos quedan «duplicado» por R-040 y van a Revisión

---

### Requirement: Tabla maestra versionada
<!-- id: ServicioHomologacion.ejecutar -->
<!-- entidades: HomVersion, HomMedicamentoOrigen, HomTablaMaestra -->
<!-- impuesto_en: A5 L691 («tabla maestra normalizada que conserve el dato original, la fuente consultada, la regla aplicada y el dato homologado final»); A5 L948 (14.4); RESP L3855; RESP L5689–5693; A4 L788 (11.2.5) -->
<!-- decision: diseño 2026-10-22 -->

<!-- decision: revisión A2 2026-10-23 · versionado por diferencia y capa de decisiones -->
Cada versión guarda solo las filas calculadas nuevas o cambiadas (huella SHA-256); la versión N completa es, por dato original, la fila más reciente de las versiones publicadas con número ≤ N más la última decisión de Revisión con número ≤ N (`hom_tabla_maestra_version(v)`; `hom_version_vigente(corte)` da la versión de un corte). Aprobar o devolver crea una versión sin copiar filas; una versión o un corte anterior no ve la decisión.

Cada ejecución DEBE crear una versión de la tabla maestra (`hom_version`: número, fecha, usuario o proceso, versiones de cada fuente y de cada regla usadas) y, por cada dato original distinto, un registro con: dato original (CUM, descripción, principio activo, concentración, forma, presentación, unidad, código SSFM, CIE-10 tal como llegaron), fuente consultada (nombre, versión y fecha de corte), regla que decidió y reglas aplicadas, dato final (las 13 tablas paramétricas del A5 4.7.2: nombre comercial, DCI, principio activo, concentración, forma, presentación, unidad, CUM, código SSFM, ATC, CIE-10, grupo farmacológico = ATC nivel 3, registro sanitario), estado, detalle de las diferencias y ATC.

#### Scenario: Pantalla de la tabla maestra
<!-- test: M8-4 -->
<!-- test: prueba por escribir HomologacionApiTest#tablaMaestra -->
- **CUANDO** el ANALISTA abre «Tabla maestra normalizada» y busca «850» o un CUM
- **ENTONCES** ve las columnas Dato original (OPL) | Fuente consultada (con versión y fecha) | Regla aplicada | Dato homologado | ATC | Estado | Acción («Ver» o «Revisar»), paginadas en el servidor

#### Scenario: Ver la cadena de un registro
- **CUANDO** pulsa «Ver»
- **ENTONCES** ve cada regla aplicada en orden con el valor antes y después, la versión de la fuente y los cargues y el número de registros de dispensación que usan ese dato

---

### Requirement: Enlace con los hechos de dispensación sin reescribirlos
<!-- id: ServicioHomologacion.enlazar -->
<!-- entidades: AnaDispensacion, HomMedicamentoOrigen, HomTablaMaestra -->
<!-- impuesto_en: RESP L5675 («No se exige que el cambio afecte registros ya procesados»); A5 L765 (recorrer el dato desde el OPL hasta el tablero); RESP L5173 (resultado no vinculable = inconsistente) -->
<!-- decision: diseño 2026-10-22 -->

Al aplicar un cargue, cada hecho DEBE guardar `medicamento_id` = id estable de `hom_medicamento_origen` (llave: CUM normalizado + descripción normalizada del dato original) y la versión de la tabla maestra vigente en ese momento; los datos nuevos se homologan antes de aplicar el cargue. Una re-ejecución NO DEBE actualizar los hechos: crea una versión nueva, y los tableros resuelven el dato final uniendo `medicamento_id` con la versión elegida (por defecto la vigente a la fecha de corte de la consulta).
<!-- decision: revisión A2 2026-10-23 · incremental, atómica con el cargue, llave por hash -->
La etapa homologa solo los datos originales nuevos del cargue (y los que comparten su descripción, por R-040): crece con el cargue, no con la historia; los ya existentes conservan su fila hasta una re-ejecución completa. Lo que escribe queda «pendiente del cargue N» (datos originales, versión sin número, pares y meses) y no se ve; en la transacción que marca el cargue «aplicado» se publica la versión, se confirman los datos, se actualizan la fecha de referencia y el último cargue y se hace el seguimiento de devoluciones; si el cargue termina fallido, rechazado o duplicado, lo pendiente se borra. La carga de hechos se une con `hom_cargue_origen` por el SHA-256 en hexadecimal de `cum || chr(1) || medicamento`; la ingesta rechaza medicamento, CUM o principio de más de 1000 caracteres (causa `texto_largo`). Si algún hecho queda sin `medicamento_id`, el cargue falla en «hechos» con el conteo (la cadena no puede quedar rota). Los atributos del dato original (principio, ATC, SSFM) son el valor más frecuente en los registros del cargue, con empate el menor lexicográfico.

#### Scenario: Re-homologar no toca lo procesado
<!-- test: prueba por escribir HomologacionApiTest#rehomologarNoReescribe -->
- **CUANDO** se modifica R-015 y se re-ejecuta la homologación
- **ENTONCES** ninguna fila de `ana_dispensaciones` cambia (mismo conteo y suma de comprobación), existe la versión N+1 y la consulta con la fecha de corte anterior sigue mostrando el resultado de la versión N

#### Scenario: Trazabilidad desde un hecho
- **CUANDO** desde el detalle de una cifra (14.9) se abre un registro de origen
- **ENTONCES** se ve el dato original, la fuente con su versión, la regla y el dato final del registro de la tabla maestra usado

---

### Requirement: Revisión humana con sugerencia opcional de la IA
<!-- id: ServicioRevision.aprobar / devolver -->
<!-- entidades: HomRevision, HomTablaMaestra -->
<!-- impuesto_en: MOCK «Revisión · Losartán 50 mg» (Campo | OPL | INVIMA; «Aprobar homologación», «Devolver al OPL»); A4 L362 (resultados de IA trazables y verificables); RESP L5153–5191 (la IA no decide de forma autónoma); RESP L5175 (validación humana) -->
<!-- decision: diseño 2026-10-22 -->

La pestaña «Revisión» DEBE listar los registros inconsistentes, duplicados, sin correspondencia, pendientes y no válidos, y para cada uno comparar campo a campo OPL frente a INVIMA (principio activo, concentración, forma, registro con su vigencia, presentación, ATC) resaltando las diferencias. «Aprobar homologación» DEBE fijar el dato final elegido (el del INVIMA o un CUM buscado) con estado homologado y regla `MANUAL`; «Devolver al OPL» DEBE exigir motivo (≤ 500 caracteres) y dejar el registro en «pendiente de revisión · devuelto al OPL». La sugerencia de la IA, si está disponible, DEBE mostrarse como «Sugerencia (IA)» y nunca aplicarse sola.
<!-- decision: revisión A2 2026-10-23 -->
Aprobar y devolver envían la versión revisada (409 si cambió) y esperan el candado como máximo 5 s (luego 409). Un «CUM o registro no válido» solo se aprueba eligiendo un CUM vigente (422), y el CUM del dato final también debe estar vigente. El motivo libre queda solo en la revisión y el seguimiento; la tabla maestra dice «devuelto al OPL · revisión N» (la pantalla avisa que no se escriban datos de pacientes).

#### Scenario: Aprobar con el dato del INVIMA
<!-- test: prueba por escribir RevisionApiTest#aprobar -->
- **CUANDO** el ANALISTA aprueba Losartán 50 mg con la forma «tableta recubierta» del INVIMA
- **ENTONCES** se crea la versión N+1 con ese registro homologado, regla `MANUAL`, usuario y fecha; la bitácora registra `homologacion_aprobada` con el antes y el después

#### Scenario: Devolver sin motivo
<!-- test: prueba por escribir RevisionApiTest#devolverSinMotivo -->
- **CUANDO** pulsa «Devolver al OPL» sin motivo
- **ENTONCES** responde 422 «Escriba el motivo de la devolución.»

#### Scenario: Sin IA
<!-- test: prueba por escribir RevisionApiTest#sinIa -->
- **CUANDO** no hay clave del proveedor de IA (`SugeridorNoDisponible`)
- **ENTONCES** la revisión funciona igual, sin la caja de sugerencia y sin error en pantalla

#### Scenario: Qué recibe la IA
<!-- test: prueba por escribir SugeridorHomologacionTest#soloNombres -->
- **CUANDO** se pide una sugerencia
- **ENTONCES** el puerto `SugeridorHomologacion` recibe solo el texto del medicamento y los candidatos del CUM (nunca seudónimos, ESM ni cantidades) y su respuesta se valida contra los CUM existentes; un CUM inventado se descarta

---

### Requirement: Parametrización en vivo con traza y re-ejecución
<!-- id: ServicioReglas.actualizar -->
<!-- entidades: HomRegla, HomReglaHistorial, HomSinonimo -->
<!-- impuesto_en: RESP L5671–5675 (TAS P5: «usuario, fecha y valor anterior y nuevo»; «ejecutando nuevamente la validación correspondiente sobre el insumo cargado»); A5 L757–759 (registrar cambios en reglas y ajustes en fuentes o tablas maestras); MOCK «Regla R-015 modificada (tolerancia de forma farmacéutica)» -->
<!-- decision: diseño 2026-10-22 -->

Desde la pestaña «Reglas» un ANALISTA o ADMINISTRADOR DEBE poder activar o desactivar una regla, cambiar su orden y sus parámetros (tolerancias, equivalencias de forma, abreviaturas, unidades) y editar los catálogos (sinónimos DCI, sales, códigos SSFM, listas normativas) sin tocar código; cada cambio DEBE crear una versión nueva de la regla o catálogo y una fila de historial con usuario, fecha, valor anterior y nuevo (JSON). «Re-ejecutar homologación» DEBE recalcular sobre todos los datos originales del insumo cargado y mostrar el antes y el después por estado.
<!-- decision: revisión A2 2026-10-23 · re-ejecución diferida -->
La re-ejecución corre diferida en un hilo (202; estado y avance en `GET …/ejecuciones/actual`; 409 si ya hay una), con espera máxima de 60 s por el candado; reglas y catálogos se editan con su versión (409 si otra persona los cambió).
<!-- decision: revisión A2 2026-10-23 · tabla maestra desactualizada -->
Como el cargue solo homologa los datos originales nuevos, el resumen trae `desactualizada` (motivo, p. ej. «Se publicó INVIMA_CUM_VIGENTES v3 después de la versión 4 de la tabla maestra…») cuando la última re-ejecución o un cargue posterior se calculó con otras fuentes publicadas, reglas (por contenido) o catálogos que los de hoy; la pantalla lo avisa arriba con «Re-ejecutar la homologación» (solo quien opera) y al publicar una fuente. Probado en `HomologacionApiTest#desactualizadaTrasPublicarCum`.

#### Scenario: Cambio pedido por el comité
<!-- test: M8-4 -->
<!-- test: prueba por escribir ReglasApiTest#toleranciaForma -->
- **CUANDO** el comité pide aceptar «cápsula» frente a «tableta» y el ANALISTA cambia la tolerancia de R-015 y re-ejecuta
- **ENTONCES** Losartán pasa de inconsistente a homologado en la versión N+1, el historial muestra el parámetro anterior y el nuevo, y el resumen dice cuántos registros cambiaron de estado

---

### Requirement: Tablero de calidad de la homologación y del dato
<!-- id: ServicioCalidad.tablero -->
<!-- entidades: HomTablaMaestra, AnaCargue -->
<!-- impuesto_en: A5 L948 (14.4: «el tablero de calidad muestra los porcentajes de registros homologados, inconsistentes, sin correspondencia y pendientes»); A4 L330–336 (§7: válidos, rechazados, duplicados, consistencia y oportunidad); A4 L766–788 (11.2.1 en cada procesamiento; 11.2.5 dashboard de calidad); RESP L4357–4363; MOCK 6 KPI -->
<!-- decision: diseño 2026-10-22 -->

El tablero de calidad DEBE mostrar los 6 porcentajes del mockup (homologados, inconsistentes, duplicados, sin correspondencia, pendientes de revisión, CUM o registro no válido) con dos decimales y coma, sobre registros de dispensación (no sobre datos originales distintos; ambos conteos visibles), filtrables por fuente, periodo, cargue y versión de la tabla maestra; y los indicadores del A4 §7 por cargue: válidos, rechazados, duplicados, consistencia (% homologados entre los válidos) y oportunidad (horas entre la fecha de dispensación más reciente y la aplicación del cargue, frente al máximo de 48 h).

#### Scenario: Cifras que cuadran
<!-- test: M8-4 -->
<!-- test: prueba por escribir CalidadApiTest#sumaCien -->
- **CUANDO** se carga el insumo de ejemplo con errores sembrados conocidos
- **ENTONCES** cada estado coincide con el conteo esperado del generador y los 6 porcentajes suman 100,00 %

#### Scenario: Descarga del tablero de calidad
- **CUANDO** se descarga el tablero en Excel, CSV o PDF
- **ENTONCES** lleva los metadatos de `analitica-tableros` (título, fecha y hora, usuario, filtros, fuentes con versión y fecha, registros procesados) y queda en la bitácora

---

### Requirement: Seguimiento de las correcciones ante el OPL
<!-- id: ServicioCalidad.seguimientoOpl -->
<!-- entidades: HomDevolucion -->
<!-- impuesto_en: RESP L4361 («disponer de un mecanismo de seguimiento por fuente y periodo […] gestión de la corrección ante el OPL y su evolución en el tiempo», texto resumido en fuentes_analitica.md §2) -->
<!-- decision: diseño 2026-10-22 -->

Cada devolución DEBE quedar con fuente, periodo, cargue, motivo, fecha, usuario y estado (`devuelta`, `corregida` cuando un cargue posterior trae el dato homologado, `persiste`), y la pantalla DEBE mostrar por fuente y periodo cuántas hay abiertas y la serie mensual de correcciones; la lista DEBE poder exportarse para enviarla al OPL.
<!-- decision: revisión A2 2026-10-23 --> Solo cuenta un cargue aplicado. También es `corregida` si el cargue trae un dato original nuevo con el mismo CUM o la misma descripción ya homologado (el OPL corrigió el dato); `resuelta` si se aprueba en Revisión. La pantalla muestra 500 y el total; la exportación las trae todas.

#### Scenario: Corrección en un cargue posterior
<!-- test: prueba por escribir CalidadApiTest#devolucionCorregida -->
- **CUANDO** un dato devuelto en septiembre llega homologado en el cargue de octubre
- **ENTONCES** la devolución pasa a `corregida` con la referencia al cargue, y el indicador de septiembre no cambia

---

### Requirement: Trazabilidad del módulo en la bitácora
<!-- id: Bitacora.TIPOS (bloque Analítica) -->
<!-- entidades: Bitacora -->
<!-- impuesto_en: A5 L743–765 (4.7.4: cargues, «cambios en las reglas de homologación», «ajustes en las fuentes o tablas maestras», consultas, descargas); A5 L962 (14.9: usuario, fecha, hora, acción y objeto); MOCK «Trazabilidad del módulo» -->
<!-- decision: diseño 2026-10-22 -->

DEBEN registrarse en `log_transacciones`, con usuario o «Proceso automático», fecha, hora, acción y objeto: `homologacion_fuente_cargada`, `homologacion_fuente_publicada`, `homologacion_lista_modificada`, `homologacion_regla_modificada`, `homologacion_ejecutada` (versión, registros, conteo por estado, duración), `homologacion_aprobada`, `homologacion_devuelta`, `homologacion_consulta` y `homologacion_exportada`; la tarjeta «Trazabilidad del módulo» DEBE mostrar los últimos eventos de estos tipos más los de cargue y modelos predictivos que publica `analitica-ingesta`.

#### Scenario: Evento de regla
<!-- test: M8-9 -->
<!-- test: prueba por escribir ReglasApiTest#bitacora -->
- **CUANDO** se modifica R-015
- **ENTONCES** aparece «Regla R-015 modificada (tolerancia de forma farmacéutica)» con el usuario y su perfil, y el detalle guarda el antes y el después sin datos personales

---

### Requirement: Exportación de la tabla maestra en formatos abiertos
<!-- id: ServicioHomologacion.exportar -->
<!-- entidades: HomTablaMaestra -->
<!-- impuesto_en: A5 L242 («tabla maestra normalizada en formatos abiertos»); A5 L315; RESP L883; A5 L377 (2.12) -->
<!-- decision: diseño 2026-10-22 -->

«Exportar» DEBE generar la versión elegida de la tabla maestra (o la búsqueda filtrada) en CSV (`;`, UTF-8 con BOM, coma decimal) y Excel con todas las columnas del requisito de la tabla maestra, por el patrón diferido de `exportacion/` si supera el tamaño de la respuesta directa; el encabezado DEBE indicar versión, fecha y versiones de las fuentes.

#### Scenario: Exportar la versión vigente
<!-- test: prueba por escribir HomologacionApiTest#exportarCsv -->
- **CUANDO** el COORDINADOR exporta la versión vigente
- **ENTONCES** recibe un CSV con una fila por dato original, la misma cantidad que la pantalla, y queda `homologacion_exportada`

---

### Requirement: Perfiles de la homologación
<!-- id: Pantallas.HOMOLOGACION -->
<!-- entidades: Perfil -->
<!-- impuesto_en: RESP L473–483 (roles Analista, Auditor, Administrador y OPL por adenda); MOCK log «jramirez · Químico farmacéutico» -->
<!-- decision: diseño 2026-10-22 -->

La pantalla HOMOLOGACION (grupo «Información», ruta `homologacion`) DEBE abrirse solo para ADMINISTRADOR, ANALISTA y COORDINADOR: ANALISTA carga fuentes, edita reglas y catálogos, ejecuta, revisa, aprueba y devuelve; COORDINADOR consulta todo, aprueba y devuelve en Revisión y exporta; ADMINISTRADOR todo. El acceso va por pantallas asignables (HOMOLOGACION y las acciones de edición), no por perfiles nuevos: «químico farmacéutico» es un **rol de auditor** ya establecido en visitas (`auditores.rol`; su usuario tiene perfil AUDITOR) y NO se mapea a ANALISTA; si la DIGSA quiere que opere la homologación, se le asigna la pantalla mediante los roles adicionales (perfil base + subconjunto de pantallas) de la sesión de identidad. No se cambian perfiles existentes.
<!-- decision: aprobación sesión 1, 2026-10-22, ajuste 3 -->
<!-- duda: el COORDINADOR no edita reglas; la edición podría ser una pantalla separable (HOMOLOGACION_REGLAS) para asignarla por rol adicional; mientras tanto se valida por perfil en el servicio -->

#### Scenario: Pantalla asignada por rol adicional
<!-- test: prueba por escribir HomologacionApiTest#rolAdicional (cuando se integre la rama identidad) -->
- **CUANDO** a un AUDITOR con rol «químico farmacéutico» se le asigna la pantalla HOMOLOGACION como rol adicional
- **ENTONCES** entra a la homologación con las acciones que esa pantalla permite, sin cambiar su perfil base

#### Scenario: Perfil sin acceso
<!-- test: prueba por escribir HomologacionApiTest#perfiles -->
- **CUANDO** un usuario DIGSA, SANIDAD, ESM, AUDITOR u OPL llama `/api/homologacion/**`, o un COORDINADOR intenta `PUT /api/homologacion/reglas/R-015`
- **ENTONCES** recibe 403 (y a los primeros la pantalla no les aparece en el menú)

---

### Invariant: Las reglas detectan sin conocer el insumo
<!-- entidades: HomRegla -->
<!-- impuesto_en: RESP L3943–3945 («la capacidad de la herramienta para detectarlas sin conocerlas de antemano es, precisamente, el objeto de la verificación»); PLIEGO L2406 (P1) -->
<!-- verificado_por: prueba por escribir AnaliticaHomologacionTest#insumoNoVisto (insumo sintético con errores sembrados al azar, semilla distinta en cada corrida) -->

Las reglas DEBEN ser genéricas (formatos, referencias oficiales, catálogos y tolerancias); NO DEBEN contener valores tomados del insumo de la prueba ni listas de casos conocidos.

---

### Invariant: Misma entrada y mismas versiones dan el mismo resultado
<!-- entidades: HomVersion, HomTablaMaestra -->
<!-- impuesto_en: A4 L883 (reproducción por terceros); A5 L962 (14.8 reproducibilidad) -->
<!-- verificado_por: prueba por escribir AnaliticaHomologacionTest#determinista -->

Con el mismo dato original, las mismas versiones de fuentes y las mismas versiones de reglas, la homologación DEBE producir el mismo estado, regla y dato final, sin depender del orden de las filas, la hora ni la IA.

---

### Invariant: Una versión publicada no se modifica ni se borra
<!-- entidades: RefVersion, HomVersion, HomReglaHistorial -->
<!-- impuesto_en: RESP L5689–5693 (trazabilidad con tabla maestra, registros de homologación y reglas); A5 L757–759 -->
<!-- verificado_por: prueba por escribir ReferenciasApiTest#inmutable (UPDATE/DELETE rechazado por trigger) -->

Las versiones publicadas o reemplazadas de las referencias, de la tabla maestra y del historial de reglas NO DEBEN actualizarse ni borrarse; solo puede haber una versión `publicada` por fuente (índice único parcial).

---

### Invariant: Cada registro de la tabla maestra tiene su cadena completa y un solo estado
<!-- entidades: HomTablaMaestra -->
<!-- impuesto_en: A5 L691; A5 L679–691 (categorías de resultado) -->
<!-- verificado_por: prueba por escribir HomologacionApiTest#cadenaCompleta -->

Todo registro DEBE tener dato original, fuente con versión y fecha (o «sin fuente» cuando el estado es sin correspondencia), regla que decidió y estado ∈ {homologado, inconsistente, duplicado, sin correspondencia, pendiente de revisión, CUM o registro no válido}; las diferencias (principio activo, concentración, forma, presentación) van en un campo aparte y obligan el estado «inconsistente». Precedencia cuando aplican varias: no válido > duplicado > inconsistente > sin correspondencia > pendiente > homologado.

---

### Invariant: La IA solo sugiere y nunca ve datos de pacientes
<!-- entidades: HomRevision -->
<!-- impuesto_en: RESP L5153–5191; RESP L5721 (TAS P8 iii, Ley 1581); A5 L271 -->
<!-- verificado_por: prueba por escribir ArquitecturaTest (el paquete homologacion no expone ana_dispensaciones al puerto) y SugeridorHomologacionTest#soloNombres -->

Ningún cambio de estado DEBE originarse en la IA; solo una persona con perfil autorizado aprueba o devuelve, y lo enviado al proveedor es solo texto de medicamentos y candidatos del CUM.

---

## Contradicciones registradas

1. **Base limpia frente a referencias precargadas.** La condición 4 exige una base sin información en las tablas maestras de auditoría (A5 L982); RESP L2287 permite precargar INVIMA y MinSalud como paramétricas. Se precargan solo las referencias oficiales; la tabla maestra homologada empieza vacía y se llena con el insumo.
2. **P1 «datos definidos previamente por la entidad»** (PLIEGO L2406) frente a «sin conocerlas de antemano» (RESP L3943). Se sigue RESP: reglas genéricas (invariante).
3. **Res. 3311/2018 y 1113/2020** exigidas en P3 (PLIEGO L2409) frente a RESP L2291 (rige la normatividad vigente, no se transcribe). Se toman como referencia de DCI, concentración y forma; el texto consolidado no se verificó (FO §2.2 [NV]).
4. **«Químico farmacéutico»** aparece como perfil en el log del mockup. En la app es un **rol de auditor** de visitas (perfil AUDITOR), no un perfil: el acceso a homologación se le da asignando la pantalla por rol adicional (sesión de identidad). No se mapea a ANALISTA (ajuste 3 de la aprobación del 2026-10-22).
5. **«Tabla CUM INVIMA actualizada · 214330 registros»** (mockup) frente a 156.913 filas vigentes y ~426 mil en los 4 conjuntos (FO §1.1). La pantalla muestra los conteos reales por conjunto y por presentación.
6. **«Listado MinSalud»** como fuente de la insulina con R-030 (mockup): el código SSFM no es de MinSalud sino de la DIGSA (A5 L378, 2.13). La fuente mostrada será «Código SSFM (DIGSA)».
7. **ATC «de MinSalud»** (RESP L3855) frente a la licencia del WHOCC y a que SISPRO no tiene tabla ATC propia (FO §2.1, §3 [V]). Se usa el ATC del CUM INVIMA y de `CatalogoCUMs`.
8. **A4 §6 y §7 repetidos cuatro veces** (A4 L320–336): se cuenta una sola obligación (RESP L4357).

## Dudas

1. **Código interno SSFM** (A5 L669, L378): la DIGSA lo entrega al inicio y aún no existe. Recomendado: catálogo cargable por archivo (`código SSFM ↔ CUM o DCI+concentración+forma`). Mientras tanto: R-030 deja «pendiente de revisión» y la prueba usa un catálogo ficticio marcado como ejemplo.
2. **Nombres de los niveles 1–4 del ATC**: licenciar el índice WHOCC (precio 2026 [NV]) o digitar a mano. Recomendado: nivel 1 (14 grupos) y nivel 2 a mano con origen «manual». Mientras tanto: niveles 3–4 solo con código.
3. **¿Aprobación con un segundo nivel?** El diseño dice «COORDINADOR consulta y aprueba». Recomendado: un solo nivel (ANALISTA o COORDINADOR). Mientras tanto: ambos aprueban y queda quién lo hizo.
4. **¿El OPL ve las devoluciones en la herramienta?** Recomendado: no en esta fase; lista exportable. Mientras tanto: sin pantalla OPL ni correo (los canales solo si el anexo los pide).
5. **Diferencia de forma tolerada (cápsula ≈ tableta) e «inconsistente» frente a «homologado con diferencia»**: recomendado inconsistente hasta que una persona apruebe. Mientras tanto: tolerancia de R-015 en cero.
6. **Definición de duplicado** (exacto, por llave natural del registro, por dato original con varios CUM): recomendado las tres, con causa distinta. Mientras tanto: las tres en R-040.
7. **CIE-10 de 3 caracteres**: ¿homologar al grupo o dejar pendiente? Recomendado pendiente. Mientras tanto: pendiente.
8. **Re-ejecución sobre periodos ya aplicados**: RESP L5675 no la exige; ¿la DIGSA querrá ver los tableros históricos con la versión nueva? Mientras tanto: los tableros eligen la versión (por defecto la vigente a la fecha de corte).
9. **No verificado en FO §7** (se carga con lo que llegue y se ajusta el lector): formato del archivo que SISPRO envía por correo (CIE-10, IUM, `CatalogoCUMs`) y si exige registro; total de `CatalogoCUMs` (~166.149) y si SISPRO tiene tabla ATC con otro nombre; contenido del ZIP de precios máximos (se asume igual a nauz-qkjw); conteo y licencia de vitales (sdmr-tfmf); contenido del PDF de estrecho margen (jul-2024, «propuesta») y de los anexos de la Res. 116 de 2026 (control especial); URL `rows.csv?accessType=DOWNLOAD` y límites sin *app token*; grafía de los `estadoregistro` de «otros estados»; alineación del ATC del INVIMA con el WHOCC vigente; texto oficial de la Res. 948 de 2026 (RIPS) y de la Res. 1657 de 2025 (CIE-11: hoy no hay tabla CIE11 en SISPRO; `ref_cie10` lleva una columna de sistema para una versión CIE-11 futura).
10. **Retención de los archivos de referencia** en Almacen: recomendado conservarlos (son públicos, CC BY-SA). Mientras tanto: se conservan con su SHA-256.

## Datos y piezas a construir

1. **V61** (`-- Suite DIGSA · Analítica V61`), en el bloque de la rama analítica:
   - `ref_fuente`, `ref_version` (estado con CHECK, `ejemplo boolean`, índice único parcial de una publicada por fuente, trigger de inmutabilidad);
   - `ref_cum_staging` UNLOGGED (29 columnas `text` + `version_id`, `conjunto`, `linea`); `ref_cum_presentacion`, `ref_cum_principio_activo`, `ref_cum_rol` (con `version_id`; btree en cum, expediente, atc, ium; `pg_trgm` GIN sobre producto y principio activo normalizados);
   - `ref_atc` (vista o tabla derivada por versión) y `ref_atc_nombre_nivel` (nivel, código, nombre, origen);
   - `ref_cie10` (código char(4), grupo, capítulo, sexo, edad mínima y máxima, sistema `CIE10`);
   - `ref_precio_regulado`, `ref_vital_no_disponible`, `ref_lista_normativa`;
   - `hom_regla` (id `R-0xx`, nombre, orden, activa, parámetros jsonb, versión), `hom_regla_historial` (antes/después jsonb, usuario, fecha), `hom_catalogo` (tipo: sinónimo DCI, sal, abreviatura, unidad, forma equivalente, código SSFM; versión);
   - `hom_version`, `hom_medicamento_origen` (id estable, llave normalizada única), `hom_tabla_maestra` (version_id, origen_id, columnas originales, fuente+versión, regla, reglas aplicadas jsonb, 13 columnas finales, estado CHECK, diferencias text[]), `hom_revision`, `hom_devolucion`;
   - tipos en `Bitacora.TIPOS` y tablas nuevas en `Exportacion.TABLAS` (staging en TECNICAS; `ref_cum_*` en TABLAS); GRANT al `${rol_app}`.
2. **Regla pura** `reglas/AnaliticaHomologacion` (JDK, JUnit 5 + AssertJ): normalizar texto, CUM, registro, CIE-10, ATC; parsear concentración y presentación; comparar forma con equivalencias; ordenar reglas y decidir estado con la precedencia; sin Spring ni `java.sql`.
3. **Paquete** `co.com.agsamericas.digsa.homologacion`: `ServicioReferencias`, `ServicioHomologacion` (ejecución diferida con advisory lock, set-based por lotes), `ServicioRevision`, `ServicioReglas`, `ServicioCalidad`; puerto `SugeridorHomologacion` con `SugeridorClaude`, `SugeridorCompatibleOpenAI` y `SugeridorNoDisponible` (por defecto).
4. **Endpoints** (todos con `@Accion` y `@PreAuthorize("@permisos.pantalla('HOMOLOGACION')")` + permiso de operación): fuentes `GET /api/homologacion/fuentes`, `POST …/fuentes/{codigo}/versiones` (por partes), `POST …/versiones/{id}/publicar`; tabla maestra `GET …/tabla-maestra?version&q&estado&pagina`, `GET …/tabla-maestra/{id}`, `GET …/tabla-maestra/exportar?formato=csv|xlsx`; ejecuciones `POST|GET …/ejecuciones[/{id}]`; revisión `GET …/revision`, `POST …/revision/{id}/aprobar|devolver|sugerencia`; reglas `GET|PUT …/reglas[/{id}]`, `GET|PUT …/catalogos/{tipo}`; calidad `GET …/calidad?fuente&periodo&cargue&version`, `GET …/calidad/exportar`, `GET …/devoluciones`, `GET …/trazabilidad`.
5. **Pantalla** `Pantallas.HOMOLOGACION` («Homologación», ruta `homologacion`, grupo «Información») con pestañas: Calidad (6 KPI + indicadores A4 §7 + seguimiento OPL), Tabla maestra, Revisión, Reglas y catálogos, Fuentes oficiales (versión, fecha de la fuente, filas, estado, «Datos de ejemplo»), Trazabilidad. Responsive a 390 px; cifras con 2 decimales y coma.
6. **Pruebas**: `AnaliticaHomologacionTest` (reglas puras y casos del mockup), `ReferenciasApiTest`, `HomologacionApiTest`, `RevisionApiTest`, `ReglasApiTest`, `CalidadApiTest`, E2E Playwright del caso M8-4 con referencias de ejemplo.

<!-- pendiente: confirmar con analitica-ingesta la llave natural del registro de dispensación (R-040) y el momento exacto en que se homologa antes de aplicar el cargue; confirmar con analitica-tableros el selector de versión de la tabla maestra -->
