# Especificación: analitica-tableros

> Escrita por spec-miner el 2026-10-22. **No hay PHP de referencia** para este módulo: la especificación sale de la fuente de verdad, el mockup M8, la hoja de ruta de la prueba en sitio y el diseño común de la sesión (que **manda**; sus decisiones van marcadas `<!-- decision: diseño 2026-10-22 -->`).
> Fuentes: `FUENTE DE VERDAD/md/` (A4 = Anexo 4 Analítica de datos; A5 = Anexo 5; RESP = Respuestas a observaciones, texto OCR; PLIEGO); `MOCKUP/Mockup_Modulos_DIGSA.html` (`#analitica` l.577–670); `EVALUACION_SOFTWARE/Hoja_ruta_prueba_en_sitio_DIGSA_2026-10-22.xlsx` (casos M8); diseño común de analítica (2026-10-22); código del worktree `digsa-java-analitica` (commit fe9c98e).
> Relacionadas: carga, bitácora del cargue, hechos y corte en `analitica-ingesta`; tabla maestra, estados de homologación y reglas en `analitica-homologacion`; cohortes, alertas analíticas y predicción en `analitica-cohortes-alertas`; asistente de IA en `analitica-asistente`; perfiles y alcance en `perfiles-alcance`.
> Estado en Java (fe9c98e): no existe nada del módulo. Existen y se reutilizan: `reglas/Alcance` (Todo/Fuerza/Esm, `PERFILES_CONSULTA`), `reglas/Pantallas` (enum con grupo y perfiles), `reglas/Bitacora.TIPOS`, `reglas/Exportacion.campo`/`celda` (CSV sin fórmulas), `plataforma/Exportador` + `Reporte` (Excel SXSSF, CSV «;» con BOM, PDF OpenPDF carta horizontal), el patrón diferido de `exportacion/`, `ServicioRiesgo.alcancePuntos` (SQL de alcance) y `frontend/src/shared/Barras.tsx` (`BarraHorizontal`, `GraficaBarras` con «Ver tabla») con `shared/tablero.ts` (`maximo`, `porcentajeBarra`). Ver «Datos y piezas a construir».

---

### Requirement: Pantalla «Analítica farmacéutica» desde el navegador
<!-- id: Pantallas.ANALITICA -->
<!-- entidades: Pantalla, Usuario -->
<!-- impuesto_en: A5 L934 (p. 32) 14.1; A5 L552 (p. 21) 4.7.1; mockup #analitica l.577–670 -->
<!-- decision: diseño 2026-10-22 (grupo «Información», ruta analitica) -->

El sistema DEBE ofrecer la pantalla `ANALITICA` («Analítica farmacéutica», ruta `analitica`, grupo de menú «Información») con el subtítulo «Riesgo farmacéutico, cohortes y caracterización del gasto. Información siempre agregada, sin datos que identifiquen pacientes.», el selector de 12 tableros, la línea de filtros, la etiqueta «Corte dd/mm/aaaa» y los botones de descarga, dentro de la misma aplicación web (sin saltar a otra herramienta, A5 L1008 condición 12).

#### Scenario: Abrir el módulo
<!-- test: M8-1; e2e analitica.spec.ts «abre el módulo» -->
- **CUANDO** un usuario con perfil de analítica entra a `/analitica`
- **ENTONCES** la página carga con el tablero «Gasto farmacéutico» activo, sus KPI y la fecha de corte del último cargue aplicado, sin errores

#### Scenario: Sin datos cargados
<!-- test: AnaliticaTablerosApiTest.sinCargues -->
- **CUANDO** no hay ningún cargue aplicado
- **ENTONCES** la pantalla carga y dice «Aún no hay cargues aplicados» con enlace a «Cargues del OPL» (para quien tenga esa pantalla); no muestra ceros como si fueran cifras

---

### Requirement: Perfiles con acceso y alcance aplicado en SQL
<!-- id: ServicioConsultaAnalitica.alcance -->
<!-- entidades: Usuario, Perfil, Alcance, Punto -->
<!-- impuesto_en: A5 L934–948 (p. 32–33) 14.2; A5 L428 (p. 17) 3.7; A4 L784 (p. 24) 11.2.2 -->
<!-- decision: diseño 2026-10-22 (ADMINISTRADOR, COORDINADOR, ANALISTA y PERFILES_CONSULTA; DIGSA nacional, SANIDAD su Fuerza, ESM su establecimiento) -->

`ANALITICA` DEBE estar habilitada para ADMINISTRADOR, COORDINADOR, ANALISTA, DIGSA, SANIDAD y ESM. Toda consulta (tablero, descarga, trazabilidad, opciones de filtro y asistente) DEBE llevar el predicado de alcance en el SQL: `Alcance.Todo` sin restricción, `Alcance.Fuerza` limita `fuerza`, `Alcance.Esm` limita el ESM; cualquier otro alcance responde 403. Un filtro que pida fuera del alcance se recorta al alcance y se advierte; nunca amplía.

#### Scenario: Perfil ESM
<!-- test: M8-2; AnaliticaTablerosApiTest.esmSoloSuEstablecimiento -->
- **CUANDO** el jefe de un ESM abre cualquier tablero
- **ENTONCES** todas las cifras son solo de su establecimiento, la opción de ESM tiene solo el suyo y la vista gerencial nacional no aparece

#### Scenario: Sanidad pide otra Fuerza
<!-- test: AnaliticaTablerosApiTest.sanidadNoAmplia -->
- **CUANDO** Sanidad ARC envía `fuerza=[EJC]`
- **ENTONCES** el resultado es vacío para EJC con la advertencia «Fuera de su alcance: EJC»; no devuelve cifras del Ejército

#### Scenario: Perfil sin la pantalla
<!-- test: AnaliticaTablerosApiTest.auditorProhibido -->
- **CUANDO** un AUDITOR u OPL llama `/api/analitica/...`
- **ENTONCES** responde 403 y el menú no muestra «Analítica farmacéutica»

---

### Invariant: Toda salida general es agregada y sin identificadores
<!-- entidades: Dispensacion, Paciente -->
<!-- impuesto_en: A5 L737–739 (p. 24) 4.7.3; A5 L271 (p. 11); A5 L962 (p. 34) 14.9 minimización; RESP L629 (p. 26) AGS-44; RESP L2209 (p. 84) GMI P73; RESP L4551–4577 (p. 176–177) VCO P23 -->
<!-- decision: diseño 2026-10-22 (pacientes = count(distinct seudónimo); umbral de supresión de celdas pequeñas parametrizable y DESACTIVADO por defecto) -->
<!-- verificado_por: AnaliticaTablerosApiTest.sinIdentificadores (recorre los 12 tableros y las descargas buscando el patrón de seudónimo, documentos y nombres); M8-2 -->

Ningún tablero, KPI, descarga ni respuesta del servicio de consulta DEBE contener seudónimos de paciente, documento, nombre, fecha de nacimiento completa ni contacto. Los pacientes DEBEN mostrarse como conteos (`count(distinct paciente_seudo)`). La única vista con registros individuales es la trazabilidad 14.9, con seudónimo y solo para los perfiles autorizados.

---

### Requirement: Selector de los 12 tableros de la lista mínima
<!-- id: AnaliticaConsulta.TABLEROS -->
<!-- entidades: Tablero -->
<!-- impuesto_en: A5 L552 (p. 21), L560–647 (p. 22–23) 4.7.1 lista mínima; mockup #analitica (12 chips, activo «Gasto farmacéutico») -->
<!-- decision: diseño 2026-10-22 -->

La pantalla DEBE mostrar 12 chips de selección única con `aria-pressed`, en este orden: General de dispensación, Gasto farmacéutico, Clasificación ATC, Principio activo, Medicamento, Diagnóstico CIE-10, Territorial, Alto costo, Poliformulación, Cohortes y riesgo, Comportamientos atípicos, Predictivo. Al cambiar de chip DEBE cambiar el contenido (a diferencia del mockup) conservando los filtros, y la URL DEBE llevar el tablero y los filtros (`?t=gasto&...`) para volver al mismo estado. Cada tablero cubre toda la data histórica de los cargues aplicados (A5 L552).

#### Scenario: Cambiar de tablero
<!-- test: e2e analitica.spec.ts «recorre los 12 tableros» -->
- **CUANDO** el usuario filtra `fuerza=EJC` y pasa de «Gasto farmacéutico» a «Medicamento»
- **ENTONCES** el tablero de medicamento carga con `fuerza=EJC` y el chip activo cambia

---

### Requirement: Métricas de los tableros 1 a 9
<!-- id: AnaliticaConsulta.metricas -->
<!-- entidades: Dispensacion, Medicamento, PrincipioActivo, Atc, Cie10, Esm -->
<!-- impuesto_en: A5 L560–647 (p. 22–23) 4.7.1 tableros 1–9; A4 L784–807 (p. 24) 11.2.3 -->
<!-- decision: diseño 2026-10-22 (métricas: gasto, unidades, dispensaciones, pacientes distintos, promedios, variación, participación, mediana y rangos, Top 20) -->

Cada tablero DEBE calcular sobre los hechos visibles al corte, con el alcance y los filtros, exactamente estas métricas:

| # | Tablero | Métricas (A5 4.7.1) |
|---|---|---|
| 1 | General de dispensación | n.º de dispensaciones; cantidad entregada (unidades); n.º agregado de pacientes; valor total dispensado; evolución por periodo (serie) |
| 2 | Gasto farmacéutico | gasto total; promedio por dispensación (gasto / dispensaciones); promedio agregado por paciente (gasto / pacientes distintos); variación absoluta y % frente al periodo de comparación; participación en el gasto total; por modalidad de dispensación o suministro |
| 3 | Clasificación ATC | por nivel ATC 1 a 5 elegido: gasto, cantidades, frecuencia (dispensaciones), pacientes, participación por grupo terapéutico |
| 4 | Principio activo | por molécula: gasto, concentración, forma farmacéutica, presentación, cantidades, frecuencia, tendencia temporal |
| 5 | Medicamento | nombre comercial y genérico, CUM, registro sanitario, presentación, cantidad, valor unitario (gasto / unidades), valor total |
| 6 | Diagnóstico CIE-10 | por CIE-10: medicamentos y principios activos asociados (Top 20 dentro del diagnóstico), gasto, frecuencia, pacientes |
| 7 | Territorial y organizacional | Fuerza, DISAN, regional, ESM, departamento y municipio del ESM, modalidad: gasto, frecuencia, cantidades, pacientes; matriz ESM × periodo coloreada (mapa de calor) con su tabla |
| 8 | Alto costo | medicamentos de mayor impacto presupuestal (Top 20), principios de mayor gasto, variación por periodo, participación, pacientes de la cohorte de alto costo |
| 9 | Poliformulación | pacientes por n.º de medicamentos (principios activos distintos) en el mes, distribución por rangos (1, 2–4, 5–9, ≥ 10), por Fuerza, ESM, diagnóstico y grupo farmacológico |

Medicamento, principio activo y ATC salen de la **tabla maestra homologada**; los registros sin homologar cuentan en los totales bajo «Sin homologar» (nunca se pierden).
<!-- decision: diseño 2026-10-22 (umbral de poliformulado ≥ 5 principios activos distintos en el mes, parametrizable; definición en analitica-cohortes-alertas) -->
<!-- incierto: los rangos de la distribución de poliformulación (1, 2–4, 5–9, ≥ 10) no los fija ninguna fuente; quedan parametrizables. -->

#### Scenario: Tablero de gasto
<!-- test: AnaliticaConsultaTest.gasto (reglas, datos fijos) -->
- **CUANDO** hay 4 dispensaciones de 3 pacientes por $ 100,00 cada una
- **ENTONCES** gasto 400,00; promedio por dispensación 100,00; promedio por paciente 133,33; pacientes 3

#### Scenario: Registros sin homologar
<!-- test: AnaliticaTablerosApiTest.sinHomologarCuenta -->
- **CUANDO** 10 de 1000 registros no tienen medicamento homologado
- **ENTONCES** el tablero de medicamento muestra la fila «Sin homologar» con esos 10 y el total sigue siendo 1000

---

### Requirement: Tableros 10, 11 y 12 remiten a sus capacidades
<!-- id: AnaliticaConsulta.TABLEROS (cohortes, atipicos, predictivo) -->
<!-- entidades: Cohorte, AlertaAnalitica, Proyeccion -->
<!-- impuesto_en: A5 (p. 22–23) tableros 10–12; A4 L650–726 (p. 21–22) §11.1 d, e, f -->
<!-- decision: diseño 2026-10-22 -->

Los chips «Cohortes y riesgo», «Comportamientos atípicos» y «Predictivo» DEBEN presentar, con los mismos filtros, corte y alcance, el contenido especificado en `analitica-cohortes-alertas` (conteos agregados con criterio visible; alertas por severidad con umbral, periodo y dimensión; proyección a 6 meses con IC 95 %, método, histórico, fecha de entrenamiento y observado frente a proyectado). Aquí solo se fija que comparten la barra de filtros, la descarga con metadatos y la trazabilidad de esta especificación. ESM no ve la consola de alertas (ver la duda en la especificación de alertas).

#### Scenario: Ir a atípicos con filtro
<!-- test: e2e analitica.spec.ts «atípicos conserva filtros» -->
- **CUANDO** el usuario con `atc=L04` pasa al chip «Comportamientos atípicos»
- **ENTONCES** se ven solo las alertas del ATC L04 y su descarga lleva el filtro en los metadatos

---

### Requirement: Vistas gerencial nacional, por Fuerza y por ESM
<!-- id: AnaliticaConsulta.nivel -->
<!-- entidades: Fuerza, Esm -->
<!-- impuesto_en: A4 L632–648 (p. 21) 11.1 a, b, c; A4 L522 (p. 17) niveles de consulta -->
<!-- decision: diseño 2026-10-22 (vista según el alcance) -->

Cada tablero DEBE poder verse en nivel «Nacional» (consolidado, con desglose por Fuerza EJC/ARC/FAC), «Fuerza» (una Fuerza, desglose por DISAN, regional y ESM) y «ESM» (detalle del establecimiento: medicamentos, diagnósticos, modalidades y serie). El nivel por defecto lo da el alcance (Todo → Nacional; Fuerza → su Fuerza; Esm → su ESM) y solo se ofrecen los niveles a los que el alcance llega.

#### Scenario: DIGSA baja a un ESM
<!-- test: e2e analitica.spec.ts «gerencial a ESM» -->
- **CUANDO** DIGSA en nivel Nacional hace clic en la barra «EJC» y luego en un ESM
- **ENTONCES** pasa a nivel Fuerza EJC y luego a ESM, con los filtros acumulados visibles y removibles

---

### Requirement: Filtros con la unión de las listas, selección múltiple y granularidad
<!-- id: AnaliticaConsulta.Filtros -->
<!-- entidades: Filtro -->
<!-- impuesto_en: A5 L552 (p. 21) 4.7.1; A5 L948 (p. 33) 14.5; A5 L363 (p. 15) 2.11; A4 L811–843 (p. 25); RESP L693–697 (p. 28–29) AGS-48; RESP L705 (p. 29) AGS-49 -->
<!-- decision: diseño 2026-10-22 -->

La barra de filtros DEBE ofrecer: periodo (desde–hasta) con granularidad día, semana, mes, bimestre, trimestre, semestre o año; fecha de corte; Fuerza; DISAN; regional; ESM; modalidad (dispensación o suministro); ATC (cualquier nivel, por prefijo); principio activo; medicamento; CUM; código SSFM; CIE-10 (capítulo, grupo o código, por prefijo); cohorte; GCF; GRC; estado de homologación. Cada variable admite una, varias o todas las opciones (selección múltiple con «Todas» por defecto). Las opciones se cargan desde los catálogos y la tabla maestra **dentro del alcance**. Entre variables se combinan con Y; dentro de una variable, con O.
<!-- incierto: la granularidad «horas» de 2.11 solo aplica si el insumo trae la hora de dispensación; si no, se muestra deshabilitada con «No disponible en el insumo». -->
<!-- incierto: «Operador logístico» (A4 variable 16) es hoy un único OPL: se muestra como dato fijo en los metadatos, no como filtro, hasta que haya más de uno. -->

#### Scenario: Selección múltiple
<!-- test: M8-5; AnaliticaConsultaTest.filtroMultiple -->
- **CUANDO** se filtra `esm=[A,B]` y `cie10=[E11]`
- **ENTONCES** el gasto es la suma de A y B solo para E11*, y la suma por ESM de la tabla es igual al KPI

#### Scenario: Granularidad bimestral
<!-- test: AnaliticaConsultaTest.bimestres -->
- **CUANDO** periodo 2025-01-01 a 2025-12-31 con granularidad bimestre
- **ENTONCES** la serie tiene 6 puntos (ene–feb … nov–dic) y su suma es el total del año

---

### Requirement: Valor de filtro inexistente o ambiguo advierte, no devuelve cero
<!-- id: AnaliticaConsulta.resolverFiltros -->
<!-- entidades: Filtro -->
<!-- impuesto_en: A5 L962 (p. 34) 14.10 resultado esperado (mismo servicio para tableros y asistente) -->
<!-- decision: diseño 2026-10-22 (resolución contra catálogos compartida con el asistente) -->

El servicio de consulta DEBE resolver cada valor de filtro contra su catálogo antes de consultar. Un valor que no existe DEBE devolver la advertencia «No existe <variable> = <valor>» sin cifras; uno ambiguo (varias coincidencias por texto) DEBE devolver las opciones. Un valor que existe pero sin datos en el periodo SÍ devuelve 0 con la nota «Sin dispensaciones con estos filtros».

#### Scenario: CUM inexistente
<!-- test: AnaliticaConsultaTest.cumInexistente -->
- **CUANDO** se pide `cum=99999999-99`
- **ENTONCES** la respuesta trae la advertencia y ningún KPI en 0

---

### Requirement: Comparación entre periodos
<!-- id: AnaliticaConsulta.comparar -->
<!-- entidades: Periodo -->
<!-- impuesto_en: A5 L552 (p. 21) 4.7.1 (retrospectivo, comparativo entre periodos y de tendencia); A5 tablero 2 (variación absoluta y porcentual); mockup KPI «+6,20 % frente a 2025» -->
<!-- decision: diseño 2026-10-22 -->

El usuario DEBE poder activar «Comparar con» el periodo anterior de igual duración o el mismo periodo del año anterior (por defecto). Cada KPI y cada fila muestran valor, valor del periodo de comparación, variación absoluta y variación % (`(actual − anterior) / anterior × 100`; con anterior 0 se muestra «—», no infinito). Ambos periodos usan el mismo corte, alcance y filtros.

#### Scenario: Variación
<!-- test: AnaliticaConsultaTest.variacion -->
- **CUANDO** el gasto actual es 106,20 y el del año anterior 100,00
- **ENTONCES** variación absoluta 6,20 y variación 6,20 %

---

### Requirement: Top 20, mediana y rangos
<!-- id: AnaliticaConsulta.top / AnaliticaConsulta.distribucion -->
<!-- entidades: Medicamento, PrincipioActivo, Cie10 -->
<!-- impuesto_en: A4 L556–628 (p. 18–21) 11.1.1 «ranking Top 20», 11.1.17, 11.1.18; A4 L784–807 (p. 24) 11.2.3 «medianas y rangos» -->
<!-- decision: diseño 2026-10-22 -->

Los tableros 3 a 8 DEBEN tener un ranking Top 20 por gasto (alternable a unidades, dispensaciones o pacientes) con participación y participación acumulada, más la fila «Resto» para que el ranking cuadre con el total. Los tableros 1, 2 y 9 DEBEN mostrar, del gasto por dispensación y del gasto por paciente en el periodo: mínimo, P25, mediana, P75, máximo y rango intercuartílico, calculados con `percentile_cont` sobre los hechos (no sobre agregados). Empates del Top 20 se ordenan por código ascendente para que el orden sea estable.

#### Scenario: Top 20 cuadra
<!-- test: AnaliticaConsultaTest.topMasResto -->
- **CUANDO** hay 35 medicamentos con gasto
- **ENTONCES** se listan 20 más «Resto (15)» y la suma es el gasto total

---

### Invariant: Las cifras cuadran con el insumo y entre dimensiones
<!-- entidades: Dispensacion, Cargue -->
<!-- impuesto_en: A5 L948 (p. 33) 14.5 («coinciden con el insumo 2024-2025 y son consistentes al cruzarlas entre dimensiones») -->
<!-- decision: diseño 2026-10-22 (pruebas de cuadre contra los totales del generador sintético; herramientas/totales_insumo.py el día de la prueba) -->
<!-- verificado_por: M8-5; AnaliticaCuadreTest (2 M filas sintéticas: gasto, unidades, dispensaciones y pacientes por cada dimensión contra el archivo de totales esperados calculado sin SQL) -->

Para el mismo corte, alcance y filtros: el total de gasto, unidades y dispensaciones DEBE ser igual al sumar por cualquier dimensión (ATC = principio activo = medicamento = CIE-10 = ESM = Fuerza = modalidad = periodo = total), incluidas las filas «Sin homologar», «Sin CIE-10» y «Resto»; y DEBE ser igual a la suma de los registros procesados del insumo. Los pacientes NO se suman entre celdas: el total de pacientes es `count(distinct)` sobre el conjunto filtrado, y la tabla lo aclara («un paciente puede estar en varias filas»).

---

### Requirement: Fecha de corte y reproducibilidad de la consulta
<!-- id: AnaliticaConsulta.corte -->
<!-- entidades: Cargue, Corte -->
<!-- impuesto_en: A5 L552 (p. 21) filtro «fecha de corte»; A5 L731 (p. 24) 4.7.2 reproducible; A5 L362 (p. 15) 2.10; A4 L883 (p. 26) reproducción por terceros -->
<!-- decision: diseño 2026-10-22 (corte = último cargue aplicado o uno anterior elegido; solo se ven cargues aplicados) -->

Cada consulta DEBE resolverse contra una fecha de corte: por defecto el último cargue aplicado; el usuario puede elegir un corte anterior de la lista de cargues aplicados. Son visibles los hechos de los cargues aplicados hasta ese corte y no reemplazados antes de él. Un cargue en proceso o fallido nunca se ve. La respuesta DEBE traer el corte, la fuente («tabla maestra homologada v<n>, cargues <ids>») y el n.º de registros procesados detrás de las cifras.
<!-- decision: diseño 2026-10-22 — para que un corte anterior se reproduzca después de nuevos cargues, los agregados se guardan por cargue_id y la consulta suma solo los cargues visibles al corte (el detalle está en analitica-ingesta). -->

#### Scenario: Corte anterior estable
<!-- test: AnaliticaTablerosApiTest.corteAnteriorReproducible -->
- **CUANDO** se consulta el gasto con corte C1, luego se aplica el cargue C2 y se repite la consulta con corte C1
- **ENTONCES** ambas respuestas son idénticas (misma huella SHA-256 del resultado)

#### Scenario: Cargue en proceso
<!-- test: AnaliticaTablerosApiTest.cargueEnProcesoInvisible -->
- **CUANDO** un cargue está en estado «procesando»
- **ENTONCES** ningún tablero ni descarga incluye sus filas

---

### Invariant: Mismo perfil, filtros y corte producen el mismo resultado
<!-- entidades: Consulta -->
<!-- impuesto_en: A5 L731 (p. 24) 4.7.2; A5 L962 (p. 34) 14.8 («idénticos para usuarios del mismo perfil con los mismos filtros»); A5 L362 (p. 15) 2.10 -->
<!-- verificado_por: M8-8; AnaliticaTablerosApiTest.dosUsuariosMismoResultado -->

Dos usuarios con el mismo alcance, filtros y corte DEBEN obtener las mismas filas en el mismo orden (orden total con desempate por código) y la misma huella SHA-256 del resultado canónico. El resultado no depende del usuario, la hora de consulta ni de cachés.

---

### Invariant: Formato numérico del Anexo 5 2.4
<!-- entidades: — -->
<!-- impuesto_en: A5 L356 (p. 15) 2.4; mockup banda superior («coma decimal y sin separador de miles; hora militar») -->
<!-- verificado_por: AnaliticaConsultaTest.formato; formato.test.ts -->

Valores monetarios, promedios, porcentajes y variaciones DEBEN mostrarse y exportarse con 2 decimales, redondeo `HALF_UP` a 2 cifras, coma decimal y sin separador de miles (`$ 64310220400,00`, `6,20 %`); conteos (dispensaciones, pacientes, unidades enteras) como enteros sin separador (`105218`); fechas `dd/mm/aaaa` y horas en 24 h de Colombia. El redondeo se hace al final (las sumas se calculan sin redondear).
<!-- decision: diseño 2026-10-22 — conteos enteros como en el mockup; ver la contradicción 6. -->

---

### Requirement: Descargas Excel, CSV y PDF con la unión de metadatos
<!-- id: ExportadorAnalitica -->
<!-- entidades: Descarga, Consulta -->
<!-- impuesto_en: A5 L361 (p. 15) 2.9; A5 L723–731 (p. 24) 4.7.2; A5 L962 (p. 34) 14.8; A5 L377 (p. 16) 2.12; RESP L555 (p. 24) AGS-36; RESP L2279 (p. 87) GMI P77 f -->
<!-- decision: diseño 2026-10-22 (todos los metadatos; tabla del gráfico en Excel/CSV y gráfico dibujado en PDF; compuesto sin modificar plataforma/Exportador) -->

Cada tablero DEBE tener «Descargar Excel», «Descargar CSV» y «Descargar PDF» que generan exactamente lo visualizado (mismos filtros, nivel, corte, comparación y orden) y DEBEN incluir:

| Metadato | Origen |
|---|---|
| Título del reporte (nombre del tablero y nivel) | 2.9, 4.7.2, 14.8 |
| N.º de página («Página n de m», PDF) | 2.9 |
| Fecha y hora de generación (24 h, Colombia) | 2.9, 4.7.2, 14.8 |
| Usuario y perfil | 2.9, 4.7.2, 14.8; perfil por 4.7.2 «mismo perfil» |
| Periodo, granularidad y periodo de comparación | 2.9, 4.7.2, 14.8 |
| Establecimiento (ESM o «Todos los del alcance») | 2.9 |
| Filtros aplicados, uno por línea, incluido el alcance | 2.9, 4.7.2, 14.8 |
| Fuente (tabla maestra y versión, cargues incluidos) | 4.7.2, 14.8 |
| Fecha de corte | 4.7.2, 14.8 |
| N.º de registros procesados (del insumo, no de filas del reporte) y n.º de filas | 4.7.2, 14.8 |
| Gráficos: en Excel/CSV la tabla de datos de cada gráfico; en PDF el gráfico dibujado y su tabla | 4.7.2, 14.8 |
| Observaciones y alertas (advertencias de filtros, «Sin homologar», alertas del periodo y filtros) | 4.7.2, 14.8 |
| Huella SHA-256 del contenido | decisión de diseño (reproducibilidad verificable) |

`ExportadorAnalitica` DEBE ser una **capa delgada** sobre `plataforma/Exportador`: aporta solo los metadatos 14.8 y los gráficos, y reutiliza las rutinas públicas del Exportador (y `reglas/Exportacion.campo`) para CSV y Excel (coma decimal, sin fórmulas, BOM), sin duplicarlas. La sesión de reportes es la dueña del Exportador y agrega en su R2 el encabezado 2.9 con «Página N de M», escritura en flujo y PDF protegido: el punto de sustitución queda marcado en el código (`// SUSTITUIR al integrar reportes R2`) y se alinea con `docs/especificaciones/reportes/contrato-proveedor.md` cuando se publique.
<!-- decision: aprobación sesión 1, 2026-10-22, ajuste 4 -->

El CSV DEBE ir en UTF-8 con BOM, «;» y coma decimal, sin fórmulas; el Excel sin fórmulas, con hojas «Metadatos», «Datos» y una por gráfico; el PDF en carta horizontal, con restricción de edición (permisos solo de impresión y copia, RESP L555) y sin tope de filas. Todas las descargas pasan por el formato 2.4.

#### Scenario: Descarga desde varios tableros
<!-- test: M8-8; AnaliticaDescargasApiTest.metadatosCompletos (los tres formatos, tableros gasto, medicamento y territorial) -->
- **CUANDO** el usuario descarga Excel, CSV y PDF de tres tableros con `fuerza=EJC`, `atc=L04`
- **ENTONCES** cada archivo trae los 13 metadatos, las filas iguales a las de pantalla y el filtro de Fuerza y ATC en «Filtros»

#### Scenario: PDF no editable
<!-- test: AnaliticaDescargasApiTest.pdfSinEdicion -->
- **CUANDO** se abre el PDF con un lector
- **ENTONCES** el documento no permite modificar el contenido y cada página dice «Página n de m»

---

### Invariant: Las descargas son reproducibles
<!-- entidades: Descarga -->
<!-- impuesto_en: A5 L731 (p. 24); A5 L962 (p. 34) 14.8; A5 L362 (p. 15) 2.10 -->
<!-- verificado_por: M8-8; AnaliticaDescargasApiTest.dosUsuariosMismasFilas (comparación byte a byte de la hoja «Datos» y de las filas del CSV, sin las líneas de usuario y fecha) -->

Dos descargas del mismo tablero, formato, alcance, filtros y corte DEBEN tener las mismas filas byte a byte y la misma huella; solo cambian usuario, fecha y hora de generación.

---

### Requirement: Descarga diferida cuando no cabe en la petición
<!-- id: ServicioDescargaAnalitica -->
<!-- entidades: Descarga -->
<!-- impuesto_en: A5 L359 (p. 15) 2.7 (10 minutos para exportar; repositorio de las descargas del día); RESP L899 (p. 36) AGS-75 -->
<!-- decision: diseño 2026-10-22 (patrón diferido de exportacion/: ejecutor de un hilo, advisory lock, estados) -->

Si la descarga supera un umbral parametrizable de filas (por defecto 100000) DEBE generarse en diferido con el patrón de `exportacion/` (pendiente → generando → lista/fallida, una en curso por usuario, transacción `READ ONLY REPEATABLE READ`), guardarse en el `Almacen` con su SHA-256 y quedar en «Mis descargas del día» del usuario que la generó, que vencen a las 24 h.

#### Scenario: Descarga grande
<!-- test: AnaliticaDescargasApiTest.diferida -->
- **CUANDO** se pide el CSV del tablero de medicamento sin filtros sobre 2 M de registros
- **ENTONCES** la respuesta es 202 con el id; la pantalla muestra el avance y el enlace cuando queda «lista», en menos de 10 minutos

---

### Requirement: Trazabilidad de una cifra hasta el registro de origen
<!-- id: ServicioTrazaAnalitica -->
<!-- entidades: Dispensacion, Cargue, ReglaHomologacion, TablaMaestra -->
<!-- impuesto_en: A5 L962 (p. 34) 14.9; A5 L743–765 (p. 24) 4.7.4; A4 L869 (p. 26) entregable 7; RESP L3989 (p. 151); RESP L5689–5693 (p. 226) TAS P6 -->
<!-- decision: diseño 2026-10-22 (perfiles ADMINISTRADOR, COORDINADOR, ANALISTA y DIGSA; registro seudonimizado) -->

Para los perfiles autorizados, toda cifra de un KPI, barra o celda DEBE tener «Rastrear» que muestra: (1) el desglose de la cifra (la consulta estructurada, corte, filtros y número de registros); (2) la lista paginada de registros de origen con seudónimo del paciente, fecha, ESM, CUM y medicamento original, cantidad, valor, cargue (id, fecha, fuente), archivo (nombre y SHA-256), línea del archivo, y la regla de homologación aplicada con la versión de la tabla maestra (dato original → fuente → regla → dato final); (3) la suma de la lista, que DEBE ser igual a la cifra. La lista se puede descargar en CSV con los mismos metadatos. Los demás perfiles no ven «Rastrear» y el endpoint les responde 403.

#### Scenario: Rastrear una barra
<!-- test: M8-9; AnaliticaTrazaApiTest.sumaIgualCifra -->
- **CUANDO** el analista rastrea el gasto de «ATC L04 · EJC · septiembre»
- **ENTONCES** ve los registros de origen con cargue, archivo, línea, SHA-256 y regla, y la suma de sus valores es igual al valor de la barra

#### Scenario: Perfil no autorizado
<!-- test: AnaliticaTrazaApiTest.sanidadSinTraza -->
- **CUANDO** Sanidad o ESM llaman `/api/analitica/traza`
- **ENTONCES** responde 403 y la pantalla no muestra «Rastrear»

---

### Requirement: Bitácora de consultas, filtros, descargas y rastreos
<!-- id: BitacoraAnalitica -->
<!-- entidades: ConsultaAnalitica, Bitacora -->
<!-- impuesto_en: A5 L743–765 (p. 24) 4.7.4; A5 L962 (p. 34) 14.9 (usuario, fecha, hora, acción y objeto); A5 L385 (p. 16) 3.1; A4 L784 (p. 24) 11.2.2 registro de consultas; RESP L4611–4623 (p. 178–179) VCO P25 -->
<!-- decision: diseño 2026-10-22 (tabla propia en V62 + Bitacora.TIPOS) -->

Cada consulta de tablero, cambio de filtros que dispare consulta, descarga y rastreo DEBE registrar en la bitácora de analítica: usuario, perfil, alcance, fecha y hora, acción (`consulta`, `descarga`, `traza`, `asistente`), objeto (tablero o cifra), filtros en JSON, corte, formato, n.º de registros, huella del resultado y duración; y un evento en `log_transacciones` con los tipos `analitica_consulta`, `analitica_descarga` y `analitica_traza`. La bitácora DEBE poder consultarse por ADMINISTRADOR y COORDINADOR filtrando por usuario, acción y fechas, sin datos personales.

#### Scenario: Revisar los logs
<!-- test: M8-9; AnaliticaBitacoraApiTest.consultaYDescarga -->
- **CUANDO** un usuario abre el tablero de gasto con `fuerza=EJC` y descarga el PDF
- **ENTONCES** la bitácora tiene dos filas (consulta y descarga) con usuario, fecha, hora, filtros, corte y la misma huella

---

### Requirement: Tiempos de respuesta
<!-- id: ServicioConsultaAnalitica (rendimiento) -->
<!-- entidades: Consulta -->
<!-- impuesto_en: A5 L359 (p. 15) 2.7; RESP L899 (p. 36) AGS-75; RESP L2213 (p. 84) GMI P74 -->
<!-- decision: diseño 2026-10-22 (meta interna < 5 s por tablero sobre 2 M filas; prueba de carga en docs/rendimiento_analitica.md) -->

Ver un tablero DEBE tardar menos de 3 minutos y exportarlo menos de 10, para cualquier combinación de filtros sobre el insumo completo (no solo consultas de 3 meses). La meta interna es < 5 s por tablero con y sin filtros sobre 2 M de filas, usando los agregados de V62 para sumas y los hechos indexados para pacientes distintos y percentiles. Cada consulta tiene `statement_timeout`; al vencer se informa «La consulta tardó demasiado; acote el periodo» y queda en la bitácora.

#### Scenario: Tablero con todos los filtros
<!-- test: M8-5; AnaliticaRendimientoTest (2 M filas, cronometrado, dos corridas) -->
- **CUANDO** se abre el tablero territorial con periodo de un año y filtros de Fuerza, ATC y CIE-10
- **ENTONCES** responde en menos de 5 s y la duración queda en la bitácora

---

### Requirement: Accesibilidad de gráficos y KPI
<!-- id: componentes analitica -->
<!-- entidades: — -->
<!-- impuesto_en: mockup #analitica (aria-label del gráfico de proyección, leyenda textual); decisión del proyecto de barras accesibles (shared/Barras.tsx) -->
<!-- decision: diseño 2026-10-22 (barras existentes y series en SVG propio accesible, sin librerías nuevas) -->

Las barras DEBEN usar `BarraHorizontal`/`GraficaBarras` (etiqueta y valor en texto, barra `aria-hidden`, botón «Ver tabla» con `aria-expanded`). Las series de tiempo DEBEN ser SVG propio con `role="img"`, `aria-label` que describa la serie (qué, periodo, unidad) y su botón «Ver tabla»; la línea observada continua y la comparada discontinua, con leyenda textual (no solo color). Los chips usan `aria-pressed`; los KPI son texto. Todo funciona con teclado y a 360 px de ancho.

#### Scenario: Lector de pantalla
<!-- test: e2e analitica.spec.ts con axe (sin violaciones serias) -->
- **CUANDO** se recorre el tablero de gasto con teclado
- **ENTONCES** cada gráfico tiene nombre accesible y su tabla equivalente se abre con «Ver tabla»

---

### Requirement: Cobertura de los reportes 11.1.1 a 11.1.21 del Anexo 4
<!-- id: AnaliticaConsulta.REPORTES_A4 -->
<!-- entidades: Tablero, Dispensacion -->
<!-- impuesto_en: A4 L556–628 (p. 18–21) 11.1.1–11.1.21; A4 L632–648 (p. 21) productos a, b, c; RESP L5279 (p. 208) ATI 3.11 (mismos tableros para A4 y A5) -->
<!-- decision: diseño 2026-10-22 (campos opcionales del insumo: profesional_seudo, especialidad, tipo_formula, homil) -->

Cada reporte del A4 DEBE estar disponible como un tablero con filtros predefinidos (enlace guardado), no como pantalla aparte. Cuando depende de un campo opcional que el insumo no trae, el tablero DEBE mostrar «No disponible en el insumo: <campo>» en lugar de ceros o vacío:

| A4 | Cubierto por | Depende de |
|---|---|---|
| 11.1.1 Frecuencia, uso y gasto (Top 20, series, georreferenciación) | 4, 5, 7 + Top 20 + serie; territorial por departamento/municipio y mapa de calor | — |
| 11.1.2 Gasto promedio por fórmula | 2 (promedio por dispensación) | n.º de fórmula para «por fórmula» |
| 11.1.3 / 11.1.6 / 11.1.9 / 11.1.10 / 11.1.14 (HOMIL) | el mismo tablero con filtro «HOMIL» | campo `homil` o ESM HOMIL en el insumo |
| 11.1.4 Promedio por afiliado, dispensación y suministro | 2 por modalidad | — |
| 11.1.5 Consumo por subgrupo terapéutico | 3 (unidades por ATC) | — |
| 11.1.7 Alto costo por afiliado | 8 + cohorte de alto costo (agregada por rangos de gasto) | — |
| 11.1.8 / 11.1.19 Alto costo y gasto por profesional | 8 y 2 por especialidad, ESM, regional y Fuerza | `profesional_seudo`, `especialidad` |
| 11.1.11 Molécula por diagnóstico | 6 (matriz principio activo × CIE-10); pertinencia en alertas | — |
| 11.1.12 Vital no disponible | 5 filtrado por la referencia de vitales no disponibles | referencia oficial cargada; causas de desabastecimiento: no están en el insumo |
| 11.1.13 / 11.1.20 / 11.1.21 Tipo de fórmula y fórmulas manuales | 2 y 5 por tipo de fórmula | `tipo_formula` |
| 11.1.15 Indicadores del Anexo Técnico 1 | no cubierto aquí (ver la duda 5) | — |
| 11.1.16 Alto costo consolidado | 8 (afiliados, diagnósticos, medicamentos; profesionales si vienen) | `profesional_seudo` para profesionales |
| 11.1.17 / 11.1.18 Diagnóstico y medicamento más costoso | Top 20 de 6 y de 5 | — |

Las columnas D/Dx/P/Pr del A4 se cubren así: descriptivo y diagnóstico en estos tableros; predictivo y prescriptivo en `analitica-cohortes-alertas` (proyección y recomendaciones de las alertas).

#### Scenario: Insumo sin tipo de fórmula
<!-- test: AnaliticaTablerosApiTest.campoOpcionalAusente -->
- **CUANDO** el insumo aplicado no trae `tipo_formula` y el usuario abre «11.1.20 Profesional con más fórmulas manuales»
- **ENTONCES** el tablero muestra «No disponible en el insumo: tipo de fórmula, profesional» y la descarga lo lleva en «Observaciones»

---

### Requirement: Actualización de los tableros con cada cargue
<!-- id: ServicioConsultaAnalitica.refrescar -->
<!-- entidades: Cargue, Agregado -->
<!-- impuesto_en: RESP L2217 (p. 84) GMI P75 (diaria, rezago ≤ 48 h); A4 L877 (p. 26) actualización permanente; A5 L552 (p. 21) punto 2 -->
<!-- decision: diseño 2026-10-22 (refresco de agregados al aplicar cada cargue, dentro del mismo proceso) -->

Al aplicar un cargue, los agregados DEBEN quedar actualizados antes de que el cargue pase a «aplicado», de modo que el nuevo corte aparece en los tableros apenas se publica. La pantalla muestra la fecha y hora del último cargue y advierte «Datos con más de 48 h» si el último periodo procesado tiene más de 48 h.

#### Scenario: Nuevo cargue
<!-- test: AnaliticaTablerosApiTest.nuevoCorte -->
- **CUANDO** se aplica un cargue del 21/10/2026
- **ENTONCES** el corte por defecto pasa a ese cargue y los totales lo incluyen

---

### Invariant: Tableros y asistente usan el mismo servicio de consulta
<!-- entidades: Consulta -->
<!-- impuesto_en: A5 L962 (p. 34) 14.10 («cifras que coinciden con las de los tableros para los mismos filtros»); RESP L5701–5711 (p. 227) TAS P7 -->
<!-- verificado_por: AnaliticaAsistenteEvalTest (cada pregunta del conjunto compara su cifra con la del tablero para la misma consulta estructurada) -->

Tableros, descargas, trazabilidad y asistente DEBEN ejecutar la misma `ConsultaAnalitica` (métrica, dimensiones, filtros, corte) por el mismo `ServicioConsultaAnalitica`, con SQL parametrizado de solo lectura generado por la capa semántica `reglas/AnaliticaConsulta` (lista blanca de métricas y dimensiones). No hay otro camino de SQL hacia los hechos.

---

## Contradicciones registradas

1. **Metadatos de los reportes: tres listas.** 2.9 (n.º de página, establecimiento), 4.7.2 (fuente, fecha de corte, n.º de registros procesados) y 14.8 (gráficos y observaciones) no coinciden (fuentes §15.11). Se cumple la unión, más perfil y huella.
2. **Filtros: tres listas.** 4.7.1 (fecha de corte, DISAN), 14.5 (CUM, modalidad) y A4 (regional, SSFM, GCF, GRC, operador, estado de calidad). RESP L693 pide consultas multidimensionales; se toma la unión (fuentes §15.6).
3. **Tiempos frente a volumen.** RESP L2213 limita los 3/10 minutos a «consultas de hasta tres meses», pero A5 L552 exige tableros sobre toda la data histórica. Se diseña para cumplir con el insumo completo (fuentes §15.10).
4. **El mockup no tiene descargas en analítica** y sus chips no cambian el contenido. Mandan 14.8 y 4.7.1: hay botones de descarga y cada chip cambia el tablero.
5. **Anonimización, seudonimización o agregación.** A4 11.2.2 dice anonimización; A5 L737 agregada; RESP L2209 «agregada o seudonimizada». Se adopta: salidas generales agregadas, registro individual seudonimizado solo en la trazabilidad para perfiles autorizados (fuentes §15.5).
6. **2.4 «dos dígitos decimales»** para «valores o cifras» frente al mockup, que muestra los conteos sin decimales (`105218`). Se aplica 2 decimales a valores y porcentajes y enteros a conteos.
7. **Exclusividad farmacéutica** (A5 L552, L769) frente a 11.1.15 «Indicadores del Anexo Técnico 1» (cumplimiento contractual) y el informe de analítica del A1 que integra facturación y visitas (fuentes §15.9).
8. **Excel por correo (condición 17, A5 L1018) frente a «sin saltos entre herramientas» (condición 12, A5 L1008).** Aquí solo se descarga; el envío no se especifica (fuentes §15.13).
9. **Delimitador del CSV.** AGS-57 lo deja para la ejecución; GMI P77 f fija «;» y UTF-8. Manda el más concreto (fuentes §15.12).

## Dudas

Ninguna bloquea la construcción; se construye con la opción recomendada.

1. **`GraficaBarras` no es genérica** (encabezado «Visitas» y texto «Sin visitas con estos filtros.» fijos en `shared/Barras.tsx`). Recomendado: dos props opcionales (`columna`, `vacio`) con los valores actuales por defecto, retrocompatibles, avisando a la rama del tablero. Mientras tanto: `analitica/BarrasAnalitica.tsx` reusa `BarraHorizontal`, `maximo` y `porcentajeBarra` sin tocar el archivo compartido.
2. **`Exportador` no alcanza para 14.8**: su encabezado es fijo (Reporte, Generado, Usuario, «Filtro: X», Registros = filas del reporte, no registros procesados); el PDF no numera páginas ni dibuja gráficos ni restringe la edición; `texto()` es privado del paquete y no da 2 decimales a un `BigDecimal` de escala 0. Recomendado: `ExportadorAnalitica` propio con POI SXSSF y OpenPDF (mismas convenciones), reusando `reglas/Exportacion.campo` y un formateador 2.4 en `reglas/AnaliticaConsulta`. Mientras tanto no se modifica `Exportador`; si se prefiere ampliar `Reporte` con metadatos y paginación, se coordina con su dueño.
3. **Supresión de celdas pequeñas.** Recomendado: parámetro con umbral desactivado por defecto (las cifras deben cuadrar con el insumo, 14.5). Mientras tanto: desactivado y documentado.
4. **Ranking por profesional (11.1.8, 11.1.19, 11.1.20).** ¿Se muestra el profesional seudonimizado o solo especialidad, ESM y Fuerza? Recomendado: en tableros generales solo especialidad, ESM, regional y Fuerza; el ranking por profesional seudonimizado solo para los perfiles de trazabilidad. Mientras tanto así.
5. **11.1.15 Indicadores del Anexo Técnico 1** (cumplimiento contractual) choca con la exclusividad farmacéutica. Recomendado: va en el módulo de reportes, no aquí. Mientras tanto, «No cubierto en analítica» en la tabla de cobertura.
6. **Georreferenciación del gasto (11.1.1, §9.5 mapas).** Recomendado: tabla por departamento y municipio más mapa de calor ESM × periodo; un mapa de Colombia en SVG requiere la geometría DANE como recurso estático. Mientras tanto: tabla y mapa de calor.
7. **«Gasto promedio por fórmula» (11.1.2)** requiere el n.º de fórmula, que no está en las variables mínimas del insumo (RESP L3917). Recomendado: campo opcional `formula_id` en el mapeo de columnas. Mientras tanto: promedio por dispensación con la nota «por fórmula: no disponible en el insumo».
8. **Granularidad «horas» (2.11).** Solo si el insumo trae hora. Mientras tanto: opción deshabilitada con «No disponible en el insumo».

## Datos y piezas a construir

1. **V62 (agregados y bitácora de consultas):**
   - `ana_agregado_mes` (tabla resumen por `cargue_id` × mes × fuerza × disan × regional × esm × modalidad × medicamento_id × principio_activo_id × atc5 × cie10 × estado_homologacion: `gasto numeric(18,2)`, `unidades numeric`, `dispensaciones bigint`), llenada en el mismo proceso que aplica el cargue; índices por mes y por cada dimensión. Las sumas por corte = cargues visibles. Pacientes distintos y percentiles se calculan sobre `ana_dispensaciones` (V60), no aquí.
   - `ana_dim_tiempo` (día → semana ISO, mes, bimestre, trimestre, semestre, año) o funciones SQL inmutables equivalentes.
   - `ana_consultas` (id identity, usuario_id, perfil, alcance, accion CHECK in (consulta, descarga, traza, asistente), tablero, objeto, filtros jsonb, corte_cargue_id, formato, registros bigint, huella char(64), duracion_ms, advertencias text[], creado timestamptz), particionada por mes; en `Exportacion.TABLAS`.
   - `ana_descargas` (patrón de `exportacion/`: estado, almacen_ruta, sha256, vence) y `ana_vistas_guardadas` (enlaces predefinidos de los reportes 11.1.x: nombre, tablero, filtros jsonb).
   - Bitacora.TIPOS: `analitica_consulta`, `analitica_descarga`, `analitica_traza` (bloque `// --- Analítica (rama analitica) ---`).
2. **Reglas puras `reglas/AnaliticaConsulta`** (JUnit): `ConsultaAnalitica` (record: tablero, métricas, dimensiones, filtros, granularidad, comparación, corte, nivel), lista blanca de métricas y dimensiones, `TABLEROS` (12) y `REPORTES_A4`, construcción del SQL parametrizado, `variacion`, `participacion`, top + «Resto», periodos de comparación, rangos de poliformulación, huella canónica del resultado y formateador 2.4.
3. **`analitica.tableros.ServicioConsultaAnalitica`** (`JdbcClient`, solo lectura, `statement_timeout`): resuelve filtros contra catálogos (advertencias), aplica el alcance (reusa el SQL de `ServicioRiesgo.alcancePuntos`), resuelve el corte, ejecuta y devuelve `ResultadoAnalitico` (kpis, series, tablas, advertencias, metadatos, huella, registros). **El asistente lo usa tal cual** (`analitica-asistente`). Más `ServicioTrazaAnalitica`, `ServicioDescargaAnalitica`, `ExportadorAnalitica`, `BitacoraAnalitica`.
4. **Endpoints** (todos con `@Accion` y `@PreAuthorize("@permisos.pantalla('ANALITICA')")`; traza con chequeo de perfil adicional):
   - `GET /api/analitica/opciones?variable=&texto=` (opciones de filtro en el alcance)
   - `GET /api/analitica/cortes`
   - `POST /api/analitica/tableros/{tablero}` (cuerpo `ConsultaAnalitica`) → resultado
   - `POST /api/analitica/tableros/{tablero}/descarga?formato=xlsx|csv|pdf` → archivo o 202 con id; `GET /api/analitica/descargas` y `GET /api/analitica/descargas/{id}`
   - `POST /api/analitica/traza` (cifra → desglose) y `GET /api/analitica/traza/registros?...&pagina=`; `.../registros.csv`
   - `GET /api/analitica/bitacora` (ADMINISTRADOR, COORDINADOR)
5. **React** (`frontend/src/paginas/Analitica.tsx` y `frontend/src/analitica/`): `SelectorTableros` (chips), `FiltrosAnalitica` (multiselección, periodo, granularidad, corte, comparar, estado en la URL), `KpiAnalitica`, `SerieTiempoSvg` (SVG accesible con «Ver tabla»), `BarrasAnalitica` (o `GraficaBarras` ampliada, duda 1), `TablaTop20`, `MapaCalor`, `Distribucion` (mediana y rangos), `PanelTraza`, `BotonesDescarga` + «Mis descargas del día», `NoDisponible`; `analitica/formato.ts` (2.4) con Vitest. Ruta `analitica` en `App.tsx` dentro del bloque de la rama.
6. **Pantallas** (`reglas/Pantallas`, bloque de la rama): `ANALITICA("analitica", "Analítica farmacéutica", "Información", {ADMINISTRADOR, COORDINADOR, ANALISTA} ∪ PERFILES_CONSULTA)`; `HOMOLOGACION` y `ANALITICA_CARGUES` se definen en sus especificaciones. El grupo «Información» es nuevo en el menú.
7. **Pruebas**: `AnaliticaConsultaTest` (reglas), `AnaliticaTablerosApiTest`, `AnaliticaDescargasApiTest`, `AnaliticaTrazaApiTest`, `AnaliticaBitacoraApiTest`, `AnaliticaCuadreTest` y `AnaliticaRendimientoTest` (2 M filas del generador sintético de `analitica-ingesta`), e2e `analitica.spec.ts` (M8-1, M8-2, M8-5, M8-8, M8-9).
