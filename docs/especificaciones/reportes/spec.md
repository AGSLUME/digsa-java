# Especificación: reportes (módulo transversal)

> Extraída por spec-miner el 2026-10-22. **No sale del PHP**: es el motor nuevo de reportes que generaliza lo que hoy existe en Java (worktree `digsa-java-reportes`, rama `reportes`, commit fe9c98e).
> Fuentes, en orden de autoridad: `docs/decisiones_2026-10-20.md` (15.2, 13.3), `docs/decisiones_2026-10-21.md` (D3), `docs/dudas_pendientes.md` (16.5, 16.6, 17.3, 17.11, 19.9, 19.15, 26.6, 26.8); contrato `FUENTE DE VERDAD/md/ANEXO 5 …CUENTAS MÉDICAS.md` (2.4 l.356, 2.7 l.359, 2.8 l.360, 2.9 l.361, 2.10 l.362, 2.11 l.363, 2.12 l.377, 4.7.5 l.769, protocolo ítems 9 l.901, 13 l.933, 15 l.976, condiciones 12 l.1008 y 17 l.1018) y `RESPUESTAS OBSERVACIONES.md` (l.555, 629, 775, 781, 899, 901, 2209, 2213); `EVALUACION_SOFTWARE/Hoja_ruta_prueba_en_sitio_DIGSA_2026-10-22.xlsx` (casos M5-1 a M5-5, M6-11 a M6-14, M9-1 a M9-3, M10-1 a M10-6; simulacro órdenes 5, 6, 7, 8, 9, 13 y 15); `MOCKUP/Mockup_Modulos_DIGSA.html:514-570` (sección `reportes`).
> Código leído: `reportes/ControladorReportes`, `reportes/ServicioReportes`, `plataforma/{Reporte, Exportador, BandejaSalida, DespachadorSalida, Correo, CorreoSmtp, ConfiguracionDigsa, ValidacionArranque, RegistroBitacora, FiltroBitacora}`, `tablero/{ControladorTablero, ExportadorTablero}`, `auditoria/ControladorLogs`, `exportacion/{ServicioExportacion, GeneradorExportacion, EscritorZip}`, `reglas/{Alcance, Pantallas, Perfil, Bitacora, Exportacion, Huellas}`, `db/migration/V6, V9, V12, V17, V18`; pruebas `reportes/ReportesApiTest`, `auditoria/LogsApiTest`, `ejecucion/DashboardApiTest`, `agenda/PropuestaApiTest`, `reglas/ExportacionTest`, `frontend/src/paginas/Reportes.test.ts`, `e2e/pruebas/{formulario-acta-reportes, usuarios-y-logs, riesgo-tablero}.spec.ts`.
> Relacionadas: formato CSV y ZIP diferido en `exportacion-alertas`; alcance y pantallas en `perfiles-alcance` (la sesión paralela de identidad cambia los roles a «perfil base + pantallas» y el Analista a consulta nacional); tipos de bitácora en `bitacora`; tableros en `riesgo-tablero`; ejecución presupuestal en la sesión de presupuesto (worktree `digsa-java-presupuesto`).
> El texto describe el comportamiento NUEVO. Donde el código actual difiere se dice en «Hoy».

---

### Requirement: Contrato `ProveedorReporte` y registro por módulo
<!-- id: (nuevo) ProveedorReporte | RegistroReportes -->
<!-- entidades: ProveedorReporte, DefinicionReporte -->
<!-- impuesto_en: hoy reportes/ServicioReportes.java TIPOS (mapa fijo de 4) y switch en generar(); tablero/ExportadorTablero.java y auditoria/ControladorLogs.java arman su propio Reporte -->
<!-- decision: Anexo 5 2.8 l.360 («módulo de reportes en línea, parametrizable, dinámico») | Anexo 5 4.7.5 l.769 | RESPUESTAS l.899 -->

Cada módulo funcional (visitas, tableros, seguridad y, cuando lleguen, cuentas médicas, glosas, conciliaciones, presupuesto) DEBE aportar sus reportes como beans que implementan `ProveedorReporte`, y el motor los DEBE descubrir al arrancar sin tocar su código. Cada proveedor declara: `id` estable (`^[a-z][a-z0-9_]{2,40}$`), título, grupo del catálogo, módulo, clase (`GENERAL` u `OPERATIVO`), pantalla o perfiles que lo ven, filtros que admite (con su tipo y origen de opciones), campo temporal y agrupaciones permitidas, columnas (con tipo: texto, entero, decimal, fecha, fecha y hora, porcentaje), orden total de las filas, fuente («Fuente» del encabezado) y versión de la definición. El proveedor solo devuelve datos (columnas y filas en flujo); el encabezado, la exportación, la huella, el repositorio, el correo y la bitácora son del motor.
<!-- incierto: nombres de la interfaz y de los métodos; se propone `definicion()` y `filas(Consulta, Alcance, Consumer<List<Object>>)` con cursor para no cargar todo en memoria. -->

#### Scenario: Proveedor nuevo sin tocar el motor
<!-- test: pendiente -->
- **CUANDO** un módulo registra un bean `ProveedorReporte` con id `glosas_estado`
- **ENTONCES** aparece en el catálogo de los perfiles que declara y se puede ver, exportar, enviar y verificar con los mismos endpoints

#### Scenario: Id duplicado o inválido
<!-- test: pendiente -->
- **CUANDO** dos proveedores declaran el mismo `id`, o un id que no cumple el patrón
- **ENTONCES** la aplicación no arranca y el log dice cuáles chocan

#### Scenario: Reporte inexistente
<!-- test: backend/app/src/test/java/co/com/agsamericas/digsa/reportes/ReportesApiTest.java:79 -->
- **CUANDO** se pide `/api/reportes/otro`
- **ENTONCES** 404 «El reporte no existe.»

---

### Requirement: Catálogo agrupado y filtrado por perfil
<!-- id: (nuevo) ControladorReportes.catalogo | hoy ServicioReportes.opciones -->
<!-- entidades: Perfil, Pantalla, ProveedorReporte -->
<!-- impuesto_en: reglas/Pantallas.java REPORTES (Coordinador + DIGSA, Sanidad, ESM; Administrador entra a todo), LOGS (solo Administrador); ServicioReportes.opciones() quita `distribucion` si el alcance no es Alcance.Todo -->
<!-- decision: MOCKUP l.514-570 (catálogo) | Anexo 5 4.7.5 l.769 | duda 13.3 | duda 17.3 -->

El catálogo DEBE mostrar solo los reportes que el usuario puede generar, agrupados como en el mockup: «Dispensado frente a auditado», «Pendientes OPL y gestión», «Glosas por estado y término», «Conciliaciones y actas», «Visitas y hallazgos», «Ejecución presupuestal» y «Usuarios y seguridad». Un grupo sin reportes disponibles para el usuario no aparece, ni vacío ni deshabilitado. La analítica farmacéutica (riesgo farmacéutico, cohortes, gasto) NO DEBE entrar al catálogo: es del módulo de analítica (4.7.5). El permiso se comprueba otra vez en cada petición, no solo al pintar el catálogo.
<!-- duda: el Analista hoy tiene alcance «Ninguno» (17.3) y la sesión de identidad lo deja como consulta nacional; su catálogo depende de esa decisión. Mientras tanto: ve lo que su pantalla permita, con el alcance que le dé identidad. -->
<!-- incierto: los grupos de cuentas, glosas, conciliaciones y presupuesto quedan vacíos hasta que sus módulos aporten proveedores; «Dispensado frente a auditado» es de cuentas médicas (RESPUESTAS l.899), no de analítica. -->

#### Scenario: Distribución auditor–punto solo con alcance total
<!-- test: backend/app/src/test/java/co/com/agsamericas/digsa/agenda/PropuestaApiTest.java:124, :129 -->
- **CUANDO** Sanidad (alcance de fuerza) pide el catálogo o `/api/reportes/distribucion`
- **ENTONCES** el catálogo no lo trae y la petición directa responde 404; el coordinador sí lo ve

#### Scenario: Perfil sin la pantalla
<!-- test: backend/app/src/test/java/co/com/agsamericas/digsa/reportes/ReportesApiTest.java:138 -->
- **CUANDO** un auditor pide un reporte de visitas
- **ENTONCES** 403, y el 403 queda en la bitácora (`acceso_denegado`)

#### Scenario: Logs solo para el Administrador
<!-- test: backend/app/src/test/java/co/com/agsamericas/digsa/auditoria/LogsApiTest.java:83 -->
- **CUANDO** el coordinador abre «Usuarios y seguridad»
- **ENTONCES** no ve el reporte de logs; el Administrador sí

---

### Requirement: Filtros de una, varias o todas las opciones
<!-- id: (nuevo) Consulta.filtros | hoy ServicioReportes.Filtros (un solo valor por campo) -->
<!-- entidades: Fuerza, Punto, Municipio, Componente, EstadoVisita -->
<!-- impuesto_en: Anexo 5 2.11 l.363; hoy ServicioReportes.java:38 record Filtros(String desde, …, Long puntoId, String municipio, String fuerza, String unidad, String estado, String componente) -->
<!-- decision: Anexo 5 2.11 | Hoja de ruta M10-3 | MOCKUP «Fuerza (una, varias o todas)», «ESM Todos» -->

Cada filtro de catálogo (fuerza, ESM o lugar, municipio, unidad, estado, componente, regional y los que declare el proveedor) DEBE admitir una, varias o todas las opciones. En la API van como parámetro repetido (`fuerza=EJC&fuerza=ARC`); sin el parámetro es «todas». En pantalla son listas de selección múltiple con «Todas». Un valor que no existe responde 422 con el nombre del filtro. Un valor que existe pero está fuera del alcance del usuario responde 422 «fuera de su alcance» sin revelar su nombre. El encabezado nombra las opciones elegidas o dice «Todas».
<!-- Hoy: un solo valor por filtro; fuerza o unidad fuera del alcance devuelven vacío en silencio; solo el lugar fuera del alcance da 422 (ServicioReportes.java:79). -->
<!-- duda: con muchas opciones (p. ej. 40 ESM) el encabezado se alarga. Propuesta: en pantalla «ESM: 40 seleccionados»; en el archivo, la lista completa (Excel: hoja «Filtros»; PDF y TXT: anexo al final; CSV: línea completa). -->

#### Scenario: Varias fuerzas
<!-- test: pendiente -->
- **CUANDO** el coordinador filtra `fuerza=EJC&fuerza=ARC`
- **ENTONCES** las filas son solo de esas dos fuerzas y el encabezado dice «Fuerza: Ejército, Armada»

#### Scenario: Estado inválido
<!-- test: backend/app/src/test/java/co/com/agsamericas/digsa/reportes/ReportesApiTest.java:122 -->
- **CUANDO** se pide `estado=inventado`
- **ENTONCES** 422

#### Scenario: Lugar fuera del alcance
<!-- test: pendiente (hoy cubierto solo por código: ServicioReportes.java:79) -->
- **CUANDO** un ESM pide un `punto_id` de otro ESM
- **ENTONCES** 422 «El lugar no existe o está fuera de su alcance.» sin el nombre del punto

---

### Requirement: Periodo, presets y agrupación de horas a años
<!-- id: (nuevo) Periodo | Agrupacion -->
<!-- entidades: — -->
<!-- impuesto_en: Anexo 5 2.11 l.363; hoy ServicioReportes.generar() (desde/hasta en días; por defecto 1 de enero a hoy; máximo 3×366 días) -->
<!-- decision: Anexo 5 2.11 («horas, días, semanas, meses, bimestres, trimestres, semestres y años») | Anexo 5 2.4 (hora oficial colombiana, 24 h) | RESPUESTAS l.2213 (consultas de hasta 3 meses) | MOCKUP «Agrupar por», «Rango» -->

Todo reporte con campo temporal DEBE pedir un rango `desde`–`hasta` (fecha, o fecha y hora cuando se agrupa por horas) en hora de Bogotá, con presets: hoy, ayer, últimos 7 días, mes actual, mes anterior, trimestre actual, semestre actual, año actual y año de contrato vigente. DEBE admitir agrupar por hora, día, semana (ISO, lunes a domingo), mes, bimestre, trimestre, semestre y año; la agrupación agrega la columna «Periodo» con etiqueta estable (`2026-07-14 09h`, `2026-07-14`, `2026-W29`, `2026-07`, `2026-B4`, `2026-T3`, `2026-S2`, `2026`). Los periodos se cortan a medianoche de Bogotá. «hasta» posterior a «desde» o rango mayor al tope responde 422. El encabezado muestra el periodo resuelto con fechas, nunca el nombre del preset solo.
<!-- duda: tope del rango. Hoy 3 años en reportes de visitas y 92 días con texto en logs; la DIGSA garantiza tiempos solo para consultas de hasta 3 meses (RESPUESTAS l.2213). Propuesta: tope por proveedor, 3 años por defecto, con aviso de que más de 3 meses puede ir diferido. -->
<!-- duda: ¿los periodos sin datos salen con 0 (serie continua) o se omiten? Propuesta: salen con 0 en los consolidados y se omiten en el detalle. -->
<!-- Resuelto en R1: la semana ISO lleva «W» (`2026-W29`) para no chocar con el semestre (duda R.10). -->

#### Scenario: Agrupado por bimestre
<!-- test: pendiente -->
- **CUANDO** se pide el consolidado por lugar de 2026-01-01 a 2026-06-30 agrupado por bimestre
- **ENTONCES** cada lugar sale en filas `2026-B1`, `2026-B2` y `2026-B3`

#### Scenario: Fecha inválida
<!-- test: backend/app/src/test/java/co/com/agsamericas/digsa/reportes/ReportesApiTest.java:80 -->
- **CUANDO** `desde=2026-13-01`
- **ENTONCES** 422

#### Scenario: Agrupación no admitida
<!-- test: pendiente -->
- **CUANDO** se pide agrupar por horas un reporte cuyo campo temporal es una fecha sin hora
- **ENTONCES** 422 con las agrupaciones que sí admite

---

### Requirement: Encabezado 2.9 en pantalla y en cada formato
<!-- id: (nuevo) Encabezado | hoy Exportador.encabezado() -->
<!-- entidades: Reporte -->
<!-- impuesto_en: Anexo 5 2.9 l.361; hoy plataforma/Exportador.java encabezado() (Reporte, Generado, Usuario, «Filtro: …», Registros) -->
<!-- decision: Anexo 5 2.9 | Hoja de ruta M10-2 | MOCKUP (Título, Periodo, Generado, Usuario, Establecimiento, Filtros, Fuente, Página) -->

Todo reporte, en pantalla y en TXT, CSV, Excel y PDF, DEBE llevar: **Título**; **Periodo** (desde–hasta resueltos y agrupación); **Generado** (fecha y hora de Bogotá, 24 h, `yyyy-MM-dd HH:mm:ss`); **Usuario** (nombre y usuario); **Establecimiento** (los ESM o lugares elegidos, o «Todos» dentro del alcance); **Filtros** (los demás, con sus valores); **Fuente**; **Registros** (filas); **Código de verificación**; y **Página «N de M»**. En PDF la página va en cada hoja con el total; en Excel, en el pie de impresión (`&P de &N`) y el resto en las primeras filas; en pantalla, la página de la tabla. Los filtros que el reporte no aplica no se muestran.
<!-- Hoy: no hay «Página», ni «Establecimiento» propio (el lugar va como filtro), ni «Fuente» (solo en el dashboard y el riesgo), ni código de verificación; el periodo va como «Filtro: Periodo». El PDF no numera las páginas. -->
<!-- duda: CSV y TXT no tienen páginas. Propuesta: «Página: 1 de 1 (archivo continuo)» en el encabezado. -->

#### Scenario: Encabezado del detalle
<!-- test: backend/app/src/test/java/co/com/agsamericas/digsa/reportes/ReportesApiTest.java:61 (ampliar) -->
- **CUANDO** el coordinador genera el detalle de visitas
- **ENTONCES** la respuesta trae título, periodo, generado, usuario, establecimiento, filtros, fuente, registros y código

#### Scenario: Página en el PDF
<!-- test: pendiente -->
- **CUANDO** el PDF de un reporte de 300 filas ocupa 7 hojas
- **ENTONCES** cada hoja dice «Página k de 7» y repite los títulos de las columnas

#### Scenario: Encabezado en el CSV
<!-- test: backend/app/src/test/java/co/com/agsamericas/digsa/reportes/ReportesApiTest.java:93 (ampliar) -->
- **CUANDO** se descarga el CSV
- **ENTONCES** empieza con el BOM y las líneas del encabezado `clave;valor`, una vacía y luego los títulos de las columnas

---

### Requirement: Formatos TXT, CSV, Excel y PDF en flujo
<!-- id: Exportador.txt/csv/excel/pdf -->
<!-- entidades: Reporte -->
<!-- impuesto_en: Anexo 5 2.12 l.377; plataforma/Exportador.java (CSV «;» con BOM y CRLF, coma decimal, sin fórmulas vía reglas/Exportacion.campo; Excel con SXSSFWorkbook(200); TXT alineado sin recortar; PDF OpenPDF 2.0.3 carta horizontal) -->
<!-- decision: Anexo 5 2.4 l.356 y 2.12 | RESPUESTAS l.775 (delimitadores se definen en la parametrización) | duda 15.2 | Hoja de ruta M10-5 -->

Todo reporte del catálogo DEBE exportarse a TXT, CSV, Excel (`.xlsx`) y PDF con las mismas columnas, filas y orden que en pantalla y sin truncar. Reglas comunes: hora de Bogotá en 24 h; decimales con coma, sin separador de miles y redondeados a 2 cifras (mitad hacia arriba) en la presentación; CSV en UTF-8 con BOM, separador `;`, fin de línea `\r\n` y sin fórmulas (apóstrofo ante `= + - @ \t \r`); Excel sin fórmulas, con números como número y texto como texto. La generación DEBE ir en flujo (cursor por lotes hacia el escritor), sin cargar las filas ni el archivo completos en memoria. El separador y la extensión quedan parametrizables por la DIGSA (RESPUESTAS l.775).
<!-- Hoy: las filas se cargan completas en memoria (List<List<Object>>) y el archivo se arma en un byte[]; los logs solo exportan Excel y CSV (ControladorLogs.java:168). -->
<!-- incierto: los 2 decimales son de presentación; la exportación completa de datos no redondea (duda 26.7). -->

#### Scenario: Cuatro formatos
<!-- test: backend/app/src/test/java/co/com/agsamericas/digsa/reportes/ReportesApiTest.java:85 -->
- **CUANDO** se descarga el mismo reporte en xlsx, csv, pdf y txt
- **ENTONCES** empiezan por `PK`, BOM + `Reporte;…`, `%PDF-` y `Reporte: …`; `formato=doc` responde 422

#### Scenario: TXT sin recortar
<!-- test: backend/app/src/test/java/co/com/agsamericas/digsa/reportes/ReportesApiTest.java:110 -->
- **CUANDO** un lugar tiene un nombre muy largo
- **ENTONCES** el TXT lo trae completo

#### Scenario: Logs en los cuatro formatos
<!-- test: pendiente (hoy LogsApiTest.java:83 cubre xlsx y csv) -->
- **CUANDO** el administrador exporta los logs en PDF o TXT
- **ENTONCES** sale el archivo con el encabezado completo, como los demás reportes

---

### Requirement: Integridad del archivo con huella y código de verificación
<!-- id: (nuevo) Integridad | RegistroReporte -->
<!-- entidades: ReporteGenerado -->
<!-- impuesto_en: Anexo 5 2.10 l.362; RESPUESTAS l.555 («El requisito de integridad aplicará a todos los formatos de descarga; sin embargo, la restricción de edición se exigirá únicamente para PDF»); patrón de huellas en exportacion/EscritorZip.java sha256() y V18 exportaciones.sha256 -->
<!-- decision: Anexo 5 2.10 | RESPUESTAS l.555 | MOCKUP nota «Las cifras no se pueden modificar después de descargadas…» -->

Cada generación (ver, descargar o enviar) DEBE quedar en `reportes_generados` con: código de verificación, id y versión del reporte, filtros normalizados, alcance efectivo, usuario, formato, filas, **huella de los datos** y, si hay archivo, **huella del archivo** (SHA-256 de los bytes finales) y tamaño. El código DEBE ir impreso en el encabezado de todos los formatos. Las dos huellas se separan porque el encabezado lleva usuario y fecha:
- **huella de los datos** = SHA-256 de la serialización canónica (UTF-8, sin BOM) de: id y versión del reporte, filtros normalizados (ordenados, sin presets), periodo resuelto, agrupación, alcance efectivo, columnas y filas en su orden, con números sin redondear de presentación y fechas en ISO. NO incluye usuario, fecha de generación, código ni formato. Va impresa en el encabezado (primeros 16 caracteres y el resto en el registro);
- **huella del archivo** = SHA-256 del archivo ya escrito (con su encabezado). Se calcula al terminar y solo vive en el registro, porque imprimirla cambiaría el archivo.

El código DEBE ser aleatorio (no derivado de la huella), corto y legible (propuesta `RPT-aaaammdd-XXXXXX`, alfabeto sin 0/O ni 1/I) y único.
<!-- incierto: formato del código. -->

#### Scenario: Código impreso
<!-- test: pendiente -->
- **CUANDO** se descarga un Excel
- **ENTONCES** el encabezado trae «Código de verificación: RPT-…» y «Huella de los datos: …», y `reportes_generados` guarda la huella del archivo

#### Scenario: Mismos datos, distinto formato
<!-- test: pendiente -->
- **CUANDO** el mismo usuario descarga el reporte en CSV y en PDF con los mismos filtros, sin cambios en la base
- **ENTONCES** las dos generaciones tienen la misma huella de los datos y distinta huella del archivo

---

### Requirement: PDF que no se puede editar
<!-- id: (nuevo) Exportador.pdf (cifrado y permisos) -->
<!-- entidades: — -->
<!-- impuesto_en: RESPUESTAS l.555; OpenPDF 2.0.3 (PdfWriter.setEncryption con ENCRYPTION_AES_256_V3 o AES_128 y ALLOW_PRINTING, ALLOW_COPY, ALLOW_SCREENREADERS…; com/lowagie/text/pdf/crypto/AESCipher usa org.bouncycastle.crypto.engines.AESEngine) -->
<!-- decision: RESPUESTAS l.555 | Anexo 5 2.10 -->

El PDF DEBE salir cifrado con AES-256 y contraseña de propietario aleatoria que no se guarda, sin contraseña de apertura, con permiso de imprimir, copiar texto y lectores de pantalla, y **sin** permiso de modificar contenido, anotar, rellenar formularios ni ensamblar. Excel y CSV no llevan restricción de edición (RESPUESTAS l.555), pero sí el código y la huella.
<!-- incierto: OpenPDF declara bcprov-jdk18on como dependencia OPCIONAL y el pom de la app no la tiene: hay que agregarla explícita (1.78.1 está en el repositorio local de Maven). Los permisos del PDF los respeta el lector, no impiden la edición con herramientas que los ignoran: por eso la verificación por huella es la garantía real. -->
<!-- duda: ¿se protege también la hoja de Excel (protectSheet con clave aleatoria) como disuasión? La DIGSA no lo exige. Propuesta: no, para no estorbar la reutilización. -->

#### Scenario: Permisos del PDF
<!-- test: pendiente -->
- **CUANDO** se abre el PDF descargado con OpenPDF o PDFBox en la prueba
- **ENTONCES** está cifrado, abre sin contraseña, permite imprimir y copiar y no permite modificar

---

### Requirement: Verificar un reporte por archivo o por código
<!-- id: (nuevo) ControladorReportes.verificar -->
<!-- entidades: ReporteGenerado -->
<!-- impuesto_en: Anexo 5 2.10 l.362; RESPUESTAS l.555 (pregunta (ii) huella y (iii) volver a generar) -->
<!-- decision: Anexo 5 2.10 | Hoja de ruta M10-4 -->

Cualquier usuario con sesión DEBE poder verificar desde la pantalla de reportes: (a) **subiendo el archivo**, que se compara por huella del archivo con el registro; o (b) **escribiendo el código**, que muestra título, generado, usuario (enmascarado si no es él), periodo, filtros, filas, formato y huellas. Con el código, si el usuario tiene permiso sobre ese reporte y su alcance cubre el alcance registrado, DEBE poder **regenerarlo** con los mismos parámetros y ver si la huella de los datos coincide hoy o si los datos cambiaron desde entonces. El resultado es «Íntegro», «Alterado o no generado por la Suite DIGSA» o «Íntegro; los datos cambiaron desde la generación». La verificación queda en la bitácora (`reporte_verificado`). El archivo subido no se guarda.
<!-- duda: ¿quién verifica? ¿Cualquier perfil o solo el que tiene el reporte? Propuesta: cualquiera con sesión ve el resultado y los metadatos sin filas; regenerar exige permiso y alcance. -->

#### Scenario: Archivo alterado
<!-- test: pendiente -->
- **CUANDO** se sube un CSV descargado al que se le cambió una cifra
- **ENTONCES** «Alterado o no generado por la Suite DIGSA»

#### Scenario: Código válido y datos sin cambio
<!-- test: pendiente -->
- **CUANDO** se escribe el código de un reporte de ayer y se regenera sin cambios en la base
- **ENTONCES** «Íntegro» con la misma huella de los datos

---

### Invariant: Mismo reporte, mismos filtros y mismo alcance: mismas cifras y orden
<!-- entidades: ProveedorReporte, ReporteGenerado -->
<!-- impuesto_en: Anexo 5 2.10 l.362 («las cifras no deben cambiar al generar el mismo reporte con diferentes usuarios usando los mismos filtros y/o parámetros») -->
<!-- verificado_por: pendiente (Hoja de ruta M10-4: exportar dos veces y desde dos usuarios) -->

Con los mismos datos, el mismo reporte, filtros, periodo y agrupación DEBE dar la misma huella de los datos para cualquier usuario con el mismo alcance efectivo. Para eso: (1) cada proveedor DEBE declarar un orden total (con desempate por llave: `ORDER BY p.nombre, p.id`, no solo `p.nombre`); (2) el periodo se resuelve a fechas antes de consultar, y un preset relativo («hoy») se guarda resuelto; (3) la lectura se hace en una sola transacción de solo lectura (`REPEATABLE READ`), como la exportación completa; (4) los cálculos usan `BigDecimal` con redondeo fijo, no `double`; (5) las columnas no dependen del usuario sino de la definición: si un perfil no puede ver una columna (Ley 1581), el reporte de ese perfil es **otra variante** con su propia versión y huella.
<!-- Hoy: el detalle agrega «Auditor líder» solo si verEquipo() (ControladorReportes.java:65); el consolidado por lugar ordena solo por p.nombre y la distribución por u.nombre, p.nombre (sin desempate); % cumplimiento sale de Checklist.cumplimiento como double. -->
<!-- duda: dos usuarios con alcances distintos (coordinador y Sanidad ARC) ven cifras distintas por diseño. Propuesta: la igualdad se exige entre usuarios con el mismo alcance; entre alcances, las filas comunes coinciden. Confirmar que así se lee 2.10. -->

---

### Invariant: El alcance se aplica siempre en el servidor
<!-- entidades: Alcance, Punto -->
<!-- impuesto_en: reglas/Alcance.java (Todo, Opl, Fuerza, Regional, Esm, Equipo, Ninguno); ServicioConsultaAgenda.condicion(alcance) usado en ServicioReportes.generar(); ServicioReportes.opciones() -->
<!-- verificado_por: backend/app/src/test/java/co/com/agsamericas/digsa/reportes/ReportesApiTest.java:127 -->

El motor DEBE pasar al proveedor el `Alcance` de la sesión y el proveedor DEBE aplicarlo en el SQL, nunca en el frontend ni filtrando después de leer. Las opciones de los filtros (puntos, fuerzas, regionales) DEBEN salir también recortadas al alcance. Los parámetros del cliente no pueden ampliar el alcance. El alcance efectivo queda en `reportes_generados`.

---

### Requirement: Reportes generales agregados y operativos con detalle
<!-- id: (nuevo) ClaseReporte GENERAL | OPERATIVO -->
<!-- entidades: ProveedorReporte -->
<!-- impuesto_en: RESPUESTAS l.629 («los reportes generales corresponden a información consolidada, agregada… bajo criterios de agregación y anonimización»; los operativos «podrán contener el nivel de detalle necesario… respetando… los perfiles de acceso») y l.2209 («Los reportes, tableros y análisis presentarán información agregada o seudonimizada»); hoy ServicioReportes.detalle(): los perfiles de consulta no ven «Auditor líder» (Ley 1581) -->
<!-- decision: Ley 1581 de 2012 | RESPUESTAS l.629 y l.2209 -->

Un reporte `GENERAL` DEBE salir solo agregado, sin columnas que identifiquen personas (pacientes, usuarios del sistema, documentos). Un reporte `OPERATIVO` puede llevar detalle e identificar a personas, pero solo para los perfiles que declare, y los datos de pacientes (cuando lleguen con cuentas médicas) DEBEN salir seudonimizados salvo para los perfiles autorizados por la DIGSA. El motor DEBE rechazar al arrancar una definición `GENERAL` con una columna marcada como dato personal.
<!-- duda: umbral de celda pequeña (no mostrar agregados con menos de k personas, p. ej. k=5, para que no se reidentifique). La DIGSA no lo pide. Propuesta: parámetro por proveedor, apagado en los reportes de visitas (no traen pacientes), a decidir para cuentas médicas. -->
<!-- duda: el nombre del auditor en el detalle: la prueba 13 lo exige («usuario auditor», Anexo 5 l.933) y hoy lo ve el coordinador y el administrador, no DIGSA, Sanidad ni ESM. Se mantiene así. -->

#### Scenario: DIGSA sin el equipo
<!-- test: backend/app/src/test/java/co/com/agsamericas/digsa/reportes/ReportesApiTest.java:121 -->
- **CUANDO** la DIGSA (alcance total) genera el detalle de visitas
- **ENTONCES** ve las visitas pero no la columna «Auditor líder»

---

### Requirement: Descargas del día (repositorio diario por usuario)
<!-- id: (nuevo) RepositorioDiario | /api/reportes/descargas -->
<!-- entidades: ReporteGenerado, Archivo (almacén) -->
<!-- impuesto_en: Anexo 5 2.7 l.359 («repositorio de los reportes durante el día que se descargan, con objeto que el usuario que ejecutó la descarga tenga la posibilidad de acceder a él durante todo el día»); RESPUESTAS l.901 («Se prevén un repositorio diario de reportes descargados»); patrón exportacion/ServicioExportacion.java y V18 exportaciones (pendiente → generando → lista → vencida, solo quien la pidió, duda 26.6) -->
<!-- decision: Anexo 5 2.7 | MOCKUP «Descargas del día» (Reporte, Formato, Solicitado, Estado Listo/Generando, Disponible hasta 23:59) -->

Cada archivo exportado DEBE guardarse en el almacén y listarse en «Descargas del día» del usuario que lo pidió, con reporte, formato, hora de solicitud, estado («Generando», «Listo», «Fallido»), filas, tamaño, código y «Disponible hasta 23:59». Solo ese usuario lo vuelve a bajar, sin regenerarlo, hasta las 23:59:59 de Bogotá del día en que lo pidió. Pedir el mismo reporte con los mismos parámetros y formato el mismo día, sin cambios de datos detectables, DEBE ofrecer el archivo ya listo en vez de generarlo otra vez. La descarga va con `Content-Disposition: attachment`, `X-Content-Type-Options: nosniff` y `Cache-Control: no-store`.
<!-- incierto: «sin cambios de datos detectables» exige una marca de cambio por fuente; sin ella, se propone ofrecer el archivo del día con el aviso «generado a las HH:mm; ¿generar uno nuevo?». -->
<!-- Hoy: la descarga se genera en cada petición y no se guarda; la de reportes de visitas no lleva nosniff ni no-store (ControladorReportes.java:109), la del tablero sí (ControladorTablero.java:119). -->

#### Scenario: Volver a bajar en la tarde
<!-- test: pendiente -->
- **CUANDO** el usuario bajó un Excel a las 09:10 y vuelve a las 16:00
- **ENTONCES** lo ve «Listo» en «Descargas del día» y lo baja con la misma huella del archivo

#### Scenario: Otro usuario
<!-- test: pendiente -->
- **CUANDO** otro usuario pide la descarga de ese id
- **ENTONCES** 404 (no se revela que existe)

---

### Requirement: Limpieza al terminar el día
<!-- id: (nuevo) RepositorioDiario.limpiar -->
<!-- entidades: ReporteGenerado -->
<!-- impuesto_en: patrón ServicioExportacion.limpiar() y revisar() (@Scheduled cada minuto; marca «vencida» y borra el archivo); Bitacora exportacion_vencida = ELIMINACION -->
<!-- decision: Anexo 5 2.7 | Ley 1581 | duda 26.8 -->

Después de las 23:59:59 de Bogotá los archivos del día DEBEN borrarse del almacén y su fila quedar «vencida». El registro (código, huellas, filtros, usuario, filas, tiempos) NO se borra: sirve para verificar después (bitácora sin purga, decisión 7.5). La limpieza corre con candado entre réplicas y se repone si el servidor estuvo apagado a medianoche.

#### Scenario: Al día siguiente
<!-- test: pendiente -->
- **CUANDO** a las 00:05 el usuario abre «Descargas del día»
- **ENTONCES** no ve los de ayer, el archivo ya no está en el almacén y el código de ayer sigue verificándose

---

### Requirement: Generación diferida con aviso
<!-- id: (nuevo) EjecutorReportes -->
<!-- entidades: ReporteGenerado -->
<!-- impuesto_en: patrón exportacion/ServicioExportacion.java (cola en tabla, FOR UPDATE SKIP LOCKED, una en curso por usuario uq_exportaciones_en_curso, huérfanas a «fallida», archivo a medias descartado, mensaje de error genérico) -->
<!-- decision: Anexo 5 2.7 («a fin de no recargar al sistema con procesos repetitivos») | MOCKUP estado «Generando» -->

Una exportación que no termina en un tiempo corto DEBE seguir en segundo plano: la petición responde 202 con el id, la fila queda «Generando» en «Descargas del día» y la pantalla avisa cuando pasa a «Listo» (consulta periódica o aviso en la aplicación). A diferencia de la exportación completa, varias pueden correr a la vez con un tope de concurrencia por instancia y por usuario. Una falla deja «Fallido» con mensaje genérico, sin archivo a medias, y queda en la bitácora.
<!-- duda: umbral para pasar a diferido. Propuesta: siempre diferido por debajo (la petición espera hasta 15 s; si no terminó, 202). Tope: 2 en curso por usuario, 4 por instancia. -->
<!-- duda: aviso al terminar. Canales según los anexos: correo solo si lo piden; propuesta: solo aviso en la aplicación. -->

#### Scenario: Reporte grande
<!-- test: pendiente -->
- **CUANDO** un Excel de 500 000 filas tarda 2 minutos
- **ENTONCES** la petición responde 202, la fila se ve «Generando» y luego «Listo» sin recargar la página

---

### Requirement: Tiempos de 3 minutos para ver y 10 para exportar, medidos y registrados
<!-- id: (nuevo) MedicionReportes -->
<!-- entidades: ReporteGenerado, Alerta -->
<!-- impuesto_en: Anexo 5 2.7 l.359; RESPUESTAS l.899 y l.2213 («las consultas de hasta tres (3) meses deberán responder en 3 o 10 minutos»); patrón límite de 10 min en ServicioExportacion (digsa.exportacion.limite-minutos) y latencias p50/p95 de Alertas -->
<!-- decision: Anexo 5 2.7 | Hoja de ruta M5-5 y M10-1 -->

Cada generación DEBE registrar en `reportes_generados` el tiempo de consulta, el de escritura del archivo y el total, en milisegundos. La visualización DEBE cortar a los 3 minutos y la exportación a los 10 (tiempo de espera de la consulta en PostgreSQL más el plazo del escritor), con mensaje claro. Pasar el 80 % del plazo o cortarse DEBE disparar una alerta operativa al Administrador con el reporte, los filtros y el tiempo (2.7 obliga a informar por escrito a la DIGSA). La pantalla de reportes DEBE mostrar al usuario el tiempo que tardó cada generación. El administrador ve p50, p95 y máximo por reporte.
<!-- Hoy: no se mide ni se registra el tiempo de ningún reporte. -->
<!-- incierto: el informe escrito a la DIGSA lo hace una persona; la aplicación da la evidencia. -->

#### Scenario: Visualización lenta
<!-- test: pendiente -->
- **CUANDO** una consulta supera los 3 minutos
- **ENTONCES** se corta, el usuario ve «El reporte superó el tiempo máximo; acote el periodo o expórtelo» y se dispara la alerta

#### Scenario: Tiempo visible
<!-- test: pendiente -->
- **CUANDO** se genera cualquier reporte
- **ENTONCES** la pantalla muestra «Generado en N s» y la fila guarda los tres tiempos

---

### Requirement: Envío por correo desde la aplicación a varios destinatarios
<!-- id: ControladorReportes.enviar | ControladorLogs.enviar -->
<!-- entidades: ReporteGenerado, Correo -->
<!-- impuesto_en: hoy ControladorReportes.java:113-138 y ControladorLogs.java:116-140 (un solo destinatario, dominios de ConfiguracionDigsa.dominiosReportes() con subdominios, envío síncrono, 502 si falla, bitácora `reporte_enviado` con correo enmascarado y «NO salió»); ValidacionArranque (sin DIGSA_DOMINIOS_REPORTES no arranca en producción); BandejaSalida.MAX_DESTINATARIOS = 100; RESPUESTAS l.781 -->
<!-- decision: Anexo 5 condiciones 12 l.1008 y 17 l.1018 | D3 (dominios configurables) | dudas 17.11, 19.15, 16.5, 16.6 | Hoja de ruta M6-14, M9-3, M10-6; simulacro órdenes 5–9, 13 y 15 -->

Desde la misma pantalla del reporte (sin abrir otra herramienta) el usuario DEBE poder enviar el archivo del reporte en el formato que elija (Excel por defecto) a uno o varios correos (máximo 15 por envío), cada uno validado y dentro de los dominios permitidos (`DIGSA_DOMINIOS_REPORTES`, incluidos sus subdominios). El adjunto es el mismo archivo del repositorio del día (misma huella), con el código en el cuerpo del correo. El resultado DEBE mostrarse en pantalla («Enviado a…» o el error) y quedar en la bitácora con los correos enmascarados. Un adjunto por encima del tope configurable se rechaza antes de enviar, con mensaje para acotar el reporte o cambiar de formato. Si el correo no sale, responde 502 y queda «NO salió».
<!-- Hoy: un solo destinatario; el archivo se genera aparte de la descarga (otra huella); no hay tope de tamaño del adjunto en ningún lado (ni en la bandeja ni en CorreoSmtp); la bandeja no admite bytes, solo rutas del almacén. -->
<!-- duda: tope del adjunto. ACS ronda 10 MB (16.5) y el de la DIGSA y las fuerzas no se conoce (16.6). Propuesta: `digsa.reportes.adjunto-max-mb`, 10 MB por defecto. -->
<!-- duda: ¿síncrono (como hoy) o por la bandeja de salida con reintentos? Propuesta: síncrono, porque el resultado se muestra (regla de BandejaSalida) y el comité verifica la llegada en el momento; el adjunto ya está en el almacén, así que pasar a la bandeja después es trivial. -->
<!-- incierto: el tope de 15 copia el de la notificación manual (18.13); no hay cifra en los anexos. -->

#### Scenario: Envío al comité
<!-- test: backend/app/src/test/java/co/com/agsamericas/digsa/reportes/ReportesApiTest.java:100 (ampliar a varios destinatarios) -->
- **CUANDO** el coordinador envía el consolidado por lugar en PDF a `comite@example.com`
- **ENTONCES** llega un correo con un adjunto `.pdf`; la bitácora dice `reporte_enviado` con el correo enmascarado

#### Scenario: Dominio no permitido
<!-- test: backend/app/src/test/java/co/com/agsamericas/digsa/reportes/ReportesApiTest.java:105, backend/app/src/test/java/co/com/agsamericas/digsa/auditoria/LogsApiTest.java:107 -->
- **CUANDO** uno de los destinatarios es `alguien@otro-dominio.com`
- **ENTONCES** 422 «Los reportes solo se envían a correos de: …» y no sale a ninguno

#### Scenario: Adjunto muy grande
<!-- test: pendiente -->
- **CUANDO** el Excel pesa más que el tope
- **ENTONCES** 422 antes de llamar al SMTP, con el tamaño y el tope

---

### Requirement: Bitácora de generado, descargado, enviado y verificado
<!-- id: Bitacora.TIPOS reporte_* -->
<!-- entidades: Bitacora -->
<!-- impuesto_en: reglas/Bitacora.java TIPOS (hoy `reporte_descargado` y `reporte_enviado` = CONSULTA); FiltroBitacora (una fila por petición con CLAVES_DETALLE: desde, hasta, fuerza, unidad, formato, reporte, componente…); ControladorTablero.java:56 y :115 -->
<!-- decision: Anexo 5 ítem 15 l.976 | decisión 7.5 (sin purga) | duda 17.14 -->

DEBEN quedar en la bitácora, con usuario, fecha, reporte, código, formato, filtros resumidos, filas y tiempo: `reporte_generado` (ver en pantalla), `reporte_descargado` (cada descarga, también la segunda del repositorio), `reporte_enviado` (con destinatarios enmascarados y si salió) y `reporte_verificado` (con el resultado), todos de operación CONSULTA y declarados en `Bitacora.TIPOS`. Los filtros multivalor se registran completos (todos los valores, no el primero).
<!-- Hoy: la descarga de los logs no escribe `reporte_descargado` (solo la fila del filtro de la petición); la de reportes y tableros sí. -->
<!-- incierto: si FiltroBitacora toma solo el primer valor de un parámetro repetido; revisar al hacer los filtros multivalor. -->

#### Scenario: Descarga de logs
<!-- test: pendiente -->
- **CUANDO** el administrador descarga los logs en Excel
- **ENTONCES** la bitácora tiene `reporte_descargado` con el nombre del archivo y su código

---

### Requirement: Reportes de visitas de la prueba 13 con el contenido mínimo
<!-- id: ServicioReportes.detalle / porLugar / porProceso -->
<!-- entidades: Visita, VisitaEjecucion, VisitaComponente, VisitaHallazgo, VisitaAuditor -->
<!-- impuesto_en: Anexo 5 ítem 13 l.933; hoy ServicioReportes.java:165-269; tablas V6 visitas_componentes, V9 visitas_ejecucion (inicio_real, fin_real, objeto), V12 visitas_hallazgos (texto, hallazgo) -->
<!-- decision: Anexo 5 ítem 13 | duda 19.9 | Hoja de ruta M6-11 a M6-13 -->

Al migrar, los reportes de visitas DEBEN cumplir el mínimo del ítem 13: **detalle** con lugar visitado, fecha y hora de inicio real, fecha y hora de fin real, número de visita, componentes auditados, objeto, hallazgos (texto) y usuario auditor, filtrable por periodo y lugar; **consolidado por lugar** con lugar, **componente auditado** y número de visitas; **consolidado por proceso** con componente y número de visitas. Se conservan las columnas que ya tienen (estado, tipo, municipio, fuerza, unidad, C/NC/NA, % de cumplimiento, cantidad de hallazgos).
<!-- Hoy (brecha para la prueba): el detalle trae la fecha programada, no inicio y fin reales, ni objeto, ni componentes, ni el texto de los hallazgos, y el auditor solo como «Auditor líder»; el consolidado por lugar no tiene la columna de componente. -->
<!-- duda: ¿una fila por visita con los componentes y hallazgos en una celda (separados por « · »), o una fila por visita y componente? Propuesta: detalle una fila por visita; consolidado por lugar una fila por lugar y componente. -->

#### Scenario: Detalle con inicio, fin y objeto
<!-- test: pendiente (ampliar ReportesApiTest.java:61 y e2e/pruebas/formulario-acta-reportes.spec.ts:171) -->
- **CUANDO** se genera el detalle de una visita cerrada con inicio 08:00, fin 11:30, objeto y 2 hallazgos
- **ENTONCES** la fila trae «… 08:00:00», «… 11:30:00», el objeto, los componentes, los dos hallazgos y el auditor

#### Scenario: Visita sin acta
<!-- test: pendiente (duda 19.9) -->
- **CUANDO** una visita del periodo no tiene acta
- **ENTONCES** sale en el detalle con los resultados vacíos; el consolidado por proceso solo cuenta visitas cerradas

---

### Requirement: Migración de la pantalla actual sin perder funcionalidad
<!-- id: frontend/src/paginas/Reportes.tsx | Logs.tsx | Dashboard.tsx | Riesgo.tsx -->
<!-- entidades: — -->
<!-- impuesto_en: hoy /api/reportes (tipos), /api/reportes/{tipo}, /{tipo}/exportar, /{tipo}/enviar; /api/logs, /api/logs/exportar, /api/logs/enviar; /api/dashboard/exportar y /api/riesgo/exportar (TXT, CSV, Excel, PDF, sin envío); Pantallas REPORTES, LOGS, DASHBOARD, RIESGO -->
<!-- decision: plan_acelerado S4 | Hoja de ruta M6-11 a M6-14, M9-1 a M9-3 -->

El motor DEBE absorber, como proveedores, los 4 reportes de visitas (`detalle`, `lugar`, `proceso`, `distribucion`), la exportación del dashboard de visitas y del dashboard de riesgo (con las cifras de la pantalla) y los logs de auditoría, sin perder: los ids y rutas actuales (`/api/reportes/{tipo}…` siguen respondiendo), la regla de `distribucion` solo con alcance total, el corte de 200 000 filas de los logs con 422 en vez de un archivo incompleto, el texto en logs solo con periodo de hasta 92 días, la paginación de los logs en pantalla y el periodo solo en la distribución (el componente filtra ahora el detalle, el consolidado por lugar y el de proceso). Las pantallas de Logs, Dashboard y Riesgo siguen donde están (la prueba 15 pide ver los logs en el navegador) y ganan del motor el encabezado, la huella, el repositorio del día y el envío por correo.
<!-- incierto: rutas nuevas propuestas: GET /api/reportes/catalogo, GET /api/reportes/descargas, GET /api/reportes/descargas/{id}, POST /api/reportes/verificar (archivo) y GET /api/reportes/verificar/{codigo}. -->

#### Scenario: Prueba 13 por la pantalla
<!-- test: e2e/pruebas/formulario-acta-reportes.spec.ts:171 -->
- **CUANDO** el coordinador abre /reportes, pulsa «Generar reporte» y «Descargar Excel»
- **ENTONCES** ve el encabezado con «Usuario:», la visita en la tabla y baja `reporte_detalle_….xlsx`

#### Scenario: Logs por la pantalla
<!-- test: e2e/pruebas/usuarios-y-logs.spec.ts:48, backend/app/src/test/java/co/com/agsamericas/digsa/auditoria/LogsApiTest.java:114 -->
- **CUANDO** el administrador ve, descarga en Excel y envía los logs, muchas veces seguidas
- **ENTONCES** todo funciona y el pool de conexiones no se agota

#### Scenario: Dashboard exportado
<!-- test: backend/app/src/test/java/co/com/agsamericas/digsa/ejecucion/DashboardApiTest.java:159, :181; e2e/pruebas/riesgo-tablero.spec.ts:259 -->
- **CUANDO** se exporta el dashboard de visitas en los cuatro formatos
- **ENTONCES** 200 en cada uno con las cifras de la pantalla

#### Scenario: Parámetros del cliente
<!-- test: frontend/src/shared/reportes.test.ts («parámetros del reporte») -->
- **CUANDO** el cliente arma la consulta
- **ENTONCES** envía solo los filtros con valor que el reporte declara (un parámetro por opción elegida) y solo el periodo en la distribución

---

### Invariant: Nada de lo descargado se trunca en silencio
<!-- entidades: Reporte -->
<!-- impuesto_en: plataforma/Reporte.java («columnas y filas completas, sin truncar»); ControladorLogs.java:150 (más de MAX_EXPORTAR = 200 000 → 422) -->
<!-- verificado_por: backend/app/src/test/java/co/com/agsamericas/digsa/reportes/ReportesApiTest.java:110 -->

Un archivo DEBE traer todas las filas del reporte o no generarse; si un proveedor tiene tope, superarlo responde 422 con el tope y cómo acotar. El número de «Registros» del encabezado DEBE coincidir con las filas del archivo.

---

### Invariant: Fechas, horas y cifras con las reglas del Anexo 5 2.4
<!-- entidades: — -->
<!-- impuesto_en: Anexo 5 2.4 l.356; plataforma/Exportador.java texto() (BigDecimal y Double a 2 decimales HALF_UP con coma; fechas yyyy-MM-dd HH:mm:ss en Fechas.BOGOTA) -->
<!-- verificado_por: backend/reglas/src/test/java/co/com/agsamericas/digsa/reglas/ExportacionTest.java -->

En pantalla y en todos los formatos, las horas DEBEN ir en hora oficial de Colombia y en 24 h, y las cifras decimales con 2 decimales, coma decimal y sin separador de miles. El idioma de títulos, columnas, mensajes y encabezados es el español.
<!-- Hoy: ControladorLogs usa ZonedDateTime.now(reloj) sin fijar la zona de Bogotá para «Generado» y el nombre del archivo (Exportador sí convierte las fechas de las filas). -->

---

### Invariant: Los reportes solo salen a dominios permitidos
<!-- entidades: Correo -->
<!-- impuesto_en: ConfiguracionDigsa.dominiosReportes(); ValidacionArranque.java:58 (falta DIGSA_DOMINIOS_REPORTES → no arranca en producción) -->
<!-- verificado_por: backend/app/src/test/java/co/com/agsamericas/digsa/plataforma/ValidacionArranqueYCaptchaTest.java -->

Ningún reporte ni log DEBE enviarse a un correo fuera de los dominios configurados; con la lista vacía solo se permite en local o pruebas (duda 19.15). Para la prueba en sitio la lista incluye `sanidad.mil.co` (condición 17, duda 17.11).

---

## Datos que la reescritura debe agregar

1. Tabla `reportes_generados`: id, código (único), reporte, versión, usuario_id, alcance (jsonb), filtros (jsonb normalizado), periodo_desde, periodo_hasta, agrupación, formato (null si solo se vio), estado (`generando`, `lista`, `fallida`, `vencida`, `vista`), ruta (almacén, se borra al vencer), tamaño, filas, huella_datos (char 64), huella_archivo (char 64), ms_consulta, ms_archivo, ms_total, error genérico, creado, terminado, vence (23:59:59 Bogotá). Índices por (usuario_id, creado) y por código. Sin DELETE para el rol de la aplicación.
2. Tipos `reporte_generado` y `reporte_verificado` en `Bitacora.TIPOS`.
3. Dependencia `org.bouncycastle:bcprov-jdk18on` explícita para el cifrado AES del PDF.
4. Parámetros: `digsa.reportes.adjunto-max-mb`, `digsa.reportes.max-destinatarios`, `digsa.reportes.espera-sincrona-s`, `digsa.reportes.concurrencia`, plazos de 3 y 10 minutos.
5. Alerta operativa «reporte lento» en el catálogo de alertas.

<!-- pendiente: ServicioDashboard.java y ServicioRiesgo.java (filtros del tablero, hoy de un valor), RepositorioLogs.java (recorrer en flujo), FiltroBitacora.java (parámetros repetidos), frontend/src/shared/tablero.ts, Logs.tsx y Dashboard.tsx a fondo; módulos de cuentas médicas y presupuesto (otros worktrees). -->
