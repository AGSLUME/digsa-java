# Analítica farmacéutica: resumen para el traspaso a la nueva suite

> Escrito el 2026-10-31 en la fase A7.5, la última de la hoja de ruta de analítica (rama `analitica`, worktree `digsa-java-analitica`).
> El usuario cerró aquí la hoja de ruta: el desarrollo pasa a otra suite con arquitectura nueva. **La IA está en pausa:** A6 no se construyó.
> **La prueba de carga M8 está aplazada** por el usuario. Las metas y mediciones de rendimiento están en [`rendimiento_analitica.md`](rendimiento_analitica.md).
> Especificaciones: [`especificaciones/analitica/spec.md`](especificaciones/analitica/spec.md) (índice y plan de fases) y las de ingesta, homologación, tableros, cohortes y alertas, y asistente. Dudas: [`dudas_pendientes.md`](dudas_pendientes.md), sección «Analítica».

## 1. Lo construido por fase

| Fase | Commit (fecha) | Integración en develop | Qué hace |
|---|---|---|---|
| A0 · Especificación | `9210e4e` (2026-10-22) | `6ab664d` | Especificación del módulo en cinco capacidades y plan de fases A0–A7 |
| A1 · Ingesta y hechos | `01d61b3` (2026-10-23) | `6ab664d` | Insumo del OPL por archivo (CSV, TXT, gz, zip o xlsx; subida por partes) o por el servicio web simulado. Incluye mapeo de columnas, seudónimo HMAC, COPY a un staging, validación con causa y línea, aplicación todo o nada en hechos particionados por mes, bitácora de cargues y generador sintético de 2 M |
| A2 · Homologación | `d40bb04` (2026-10-23) | `6ab664d` | Referencias oficiales versionadas (CUM INVIMA, ATC, CIE-10, precio regulado y listas), reglas R-0xx deterministas y versionadas, tabla maestra inmutable por versión, Revisión (aprobar o devolver al OPL) y tablero de calidad |
| A3 · Tableros, descargas y trazabilidad | `400ce1a` (2026-10-23) | `6ab664d` | Capa semántica común, 12 tableros con filtros múltiples, comparación y Top 20, descargas Excel, CSV y PDF con metadatos 14.8, reproducibilidad por corte, rastreo de una cifra hasta el registro de origen y bitácora de consultas |
| A4 · Cohortes y alertas | `2cfaa6d` (2026-10-24) | `6ab664d` | Cohortes persistentes y versionadas (las del A4, GCF y GRC) con serie agregada por corte, motor de alertas tempranas con contexto y análisis técnico, validación humana, consola y aviso por correo |
| A4 · PDF protegido (AN.17) | `65a508f` (2026-10-24) | `6ab664d` | El PDF de los tableros sale del `Exportador` de reportes, cifrado y con permisos de solo impresión y copia. Agrega el aviso en Revisión (AN.16) |
| A4.5 · Pantallas en LUME | `27a566b`, `debd3f7` (2026-10-29) | `6ab664d` | Las cinco pantallas adaptadas a la guía de interfaz y al design system LUME, ubicadas en el grupo «Presupuesto» del menú. E2E de alertas independientes de corridas anteriores |
| A5 · Predicción | `0c9c5f6` (2026-10-29) | `33a4c11` | Tablero 12: proyección del gasto, las dispensaciones y los pacientes a 6 meses con Holt-Winters o Holt, sin IA. Incluye validación temporal, IC 95 %, observado frente a proyectado y bitácora de entrenamientos |
| A6 · Asistente IA | — | — | **En pausa (IA)** desde el 2026-10-28. No se construyó |
| A7.1 · Cohorte como filtro | `4803936` (2026-10-30) | `779b3f5` | Cohorte, GCF y GRC como filtro de los tableros 1–12 y como dimensión de la predicción (AN.22, AN.30), con supresión reforzada k = 5 para cohortes sensibles (AN.31) y volcado de la membresía a un archivo temporal (AN.32) |
| A7.2 · Exportación de hechos | `742af84` (2026-10-30) | `4415831` | Exportación de `ana_dispensaciones` por periodo: un `.csv.gz` por mes con SHA-256 y LEEME. Cada mes va en su transacción con tope de 10 min y la pide solo el Administrador (AN.14) |
| A7.3 · Descarga diferida | `80a4b8c` (2026-10-30) | `8e3456f` | El CSV de registros de origen con más de 100 000 registros se genera en segundo plano (202) y queda en «Mis descargas del día» durante 24 h (AN.16) |
| A7.4 · Recálculo incremental | `3e6417d` (2026-10-31) | **Pendiente** (develop está en `8e3456f`) | Al aplicar un cargue, cohortes y alertas recalculan solo los meses afectados, con resultado idéntico al completo y respaldo automático a completo (AN.23) |
| A7.5 · Documentación | sin commit al escribir esto | — | Este documento, `rendimiento_analitica.md`, el cierre del plan en la spec y la decisión de AN.39 |

## 2. Migraciones V60–V68

| Migración | Fase | Contenido |
|---|---|---|
| V60 `analitica_ingesta` | A1 | Esquema `analitica_particiones` y las tablas `ana_cargues`, `ana_cargue_partes`, `ana_ws_lotes`, `ana_cargue_rechazos` y `ana_mapeo_plantillas`. `ana_staging_dispensacion` va particionada por cargue y es UNLOGGED; `ana_dispensaciones` va particionada por mes, con BRIN. Además `ana_llaves`, `ana_dim_tiempo` y las funciones `ana_cargues_visibles`, `ana_asegurar_particiones`, `ana_analizar_particiones` y `ana_staging_*` |
| V61 `analitica_homologacion` | A2 | Tablas `ref_*` para las fuentes y versiones (CUM, CIE-10, precio, vitales, listas y SSFM) y la vista `ref_atc_v`. Tablas `hom_*` para reglas, historial, catálogos, versiones, tabla maestra, CIE-10, revisión y devoluciones. Funciones de inmutabilidad y `hom_tabla_maestra_version` / `hom_tabla_cie10_version` |
| V62 `analitica_tableros` | A3 | `ana_pacientes` (paciente como entero), `ana_hechos_tablero` (copia angosta de los hechos), `ana_cargue_valores` y `ana_consultas` (bitácora). Agrega `ana_cargues.campos_presentes` |
| V63 `analitica_cohortes_alertas` | A4 | Tablas `ana_cohortes*`: versiones, ejecuciones, resultados, serie, miembros y conteos. Tablas `ana_ref_versiones` y `ana_ref_filas`, y las `ana_alertas*`: parámetros con historial, alertas, historial y ejecuciones. Tipo de aviso `analitica_alerta_alta` |
| V64 `analitica_prediccion` | A5 | `ana_pred_entrenamientos` y `ana_pred_proyecciones` |
| V65 `analitica_filtro_cohorte` | A7.1 | Agrega `ana_cohortes_miembros.grupo` (membresía de GCF y GRC) y `ana_pred_entrenamientos.cohorte_version_id`, y amplía las dimensiones de la predicción |
| V66 `analitica_exportacion_hechos` | A7.2 | `ana_exportaciones_hechos` y `ana_exportaciones_hechos_meses` |
| V67 `analitica_descargas_diferidas` | A7.3 | `ana_descargas_diferidas` |
| V68 `analitica_recalculo_incremental` | A7.4 | `ana_riesgo_inc_cortes`, `ana_riesgo_inc_secuencia` y `ana_riesgo_inc_puntos` (versión utilizable de los dos últimos cortes). Agrega `ana_cohortes_ejecuciones.modo`, `meses_recalculados` y `motivo_modo` |

El plan original reservaba V65 para A6. Al quedar A6 en pausa, V65–V68 se usaron en A7.1–A7.4 y V69 queda libre.

## 3. Piezas principales

### 3.1 Backend (`backend/app/src/main/java/co/com/agsamericas/digsa/analitica`)

| Paquete | Piezas |
|---|---|
| `analitica` (raíz) | `PropiedadesAnalitica`, `ValidacionArranqueAnalitica` (sin clave de seudónimo en producción la aplicación no arranca), `PerfilesOperacion` (perfil directo o base de un rol adicional más la pantalla), evento `CargueAplicado`, puerto `PasoAplicacion`, puertos `HomologacionCargue` y `SugeridorHomologacion` |
| `ingesta` | Puerto `FuenteDispensacion` con `ArchivoDispensacion` y `ServicioWebOpl`. `LectorCsv`, `LectorXlsx` y `LectorTabular` leen el insumo; `Seudonimizador` aplica el HMAC; `CopiadorStaging` hace el COPY. Siguen `ValidadorStaging`, `AplicadorHechos`, `ProcesadorCargues` (ejecutor de un hilo), `ServicioCargues` y `MantenimientoCargues`. API: `CarguesApi` y `OplDispensacionApi` |
| `homologacion` | `ServicioReferencias` (carga del CUM con COPY), `ServicioHomologacion`, `ServicioReglas`, `ServicioRevision`, `ConsultasHomologacion`, `SugeridorNoDisponible` y `HomologacionApi` |
| `tableros` | `ServicioConsultaAnalitica` (corte, filtros, caché y huella), `SemanticaSql`, `ConstructorTableros`, `FiltroCohortes`, `Supresion`, `Plazo` (`statement_timeout` por petición), `PasoTablas`, `Precalentador`, `ServicioTrazaAnalitica`, `ServicioDescargaAnalitica`, `DescargaGrande`, `ExportadorAnalitica` y `BitacoraAnalitica`. API: `TablerosApi` y `DescargasDiferidasApi` |
| `riesgo` | `ServicioCohortes`, `MotorRiesgo`, `DetectoresAlertas`, `RecalculoRiesgo` (recálculo al aplicar), `RecalculoIncremental`, `CatalogoClinico`, `SupresionRiesgo`, `Volcado` y `ServicioAlertasAnaliticas`. Los avisos salen por `ServicioAlertas`, que llama `RecalculoRiesgo`. API: `CohortesApi` y `AlertasAnaliticasApi` |
| `prediccion` | `ServicioPrediccion`, `EntrenamientoAutomatico` y `PrediccionApi` |
| `exportacion` | `ServicioExportacionHechos`, `GeneradorHechosMes` y `ExportacionHechosApi` |

**Reglas puras** (`backend/reglas/.../reglas`, Java sin Spring ni base de datos): `AnaliticaIngesta`, `AnaliticaHomologacion`, `AnaliticaConsulta`, `AnaliticaCohortes`, `AnaliticaAlertas`, `AnaliticaPrediccion`, `AnaliticaExportacionHechos`, `AnaliticaDescargaDiferida` y `AnaliticaIncremental`. Archivos compartidos con bloque propio `// --- Analítica (rama analitica) ---`: `Pantallas`, `Bitacora` (47 tipos `analitica_*` y `homologacion_*`) y `Exportacion` (tablas en la exportación completa y en `TECNICAS`).

API: `/api/analitica/**` (cargues, OPL, catálogo, opciones, cortes, consulta, tableros, traza, bitácora, cohortes, alertas, atípicos, predicción, exportaciones de hechos y descargas diferidas) y `/api/homologacion/**`.

### 3.2 Frontend (`frontend/src`)

| Pieza | Contenido |
|---|---|
| `paginas/` | `CarguesOpl.tsx` (incluye `PanelExportacionHechos`), `Homologacion.tsx`, `Analitica.tsx` (tableros con `PanelCohortes`, `PanelAtipicos`, `PanelPrediccion`, `PanelTraza` y `PanelDescargasDiferidas`), `TrazabilidadAnalitica.tsx` y `AlertasAnaliticas.tsx` |
| `analitica/` | Clientes y lógica: `cargues.ts`, `subida.ts`, `sha256.ts` con `sha256.worker.ts`, `homologacion.ts`, `tableros.ts`, `riesgo.ts`, `prediccion.ts`, `exportacionHechos.ts` y `descargasDiferidas.ts`. Componentes: `FiltrosAnalitica.tsx` y `GraficosAnalitica.tsx` (SVG propio) |
| `App.tsx` | Cinco rutas perezosas en el bloque de analítica |

## 4. Pantallas y permisos

El Administrador entra a todas las pantallas (`Pantallas.puedeEntrar`). Las cinco están en el grupo «Presupuesto» y se pueden asignar como rol adicional.

| Pantalla (ruta) | Perfiles que entran | Operaciones restringidas |
|---|---|---|
| `ANALITICA_CARGUES` «Cargues del OPL» (`analitica/cargues`) | Analista y Coordinador | Cargar: Administrador y Analista (`AnaliticaIngesta.PERFILES_CARGA`). Original del cargue: Administrador. Servicio web: `OPL_NACIONAL`. Exportación de hechos: solo Administrador (`PermisosExportacionHechos`) |
| `HOMOLOGACION` «Homologación» (`homologacion`) | Analista y Coordinador | Operar (fuentes, reglas, ejecutar y revisar): Administrador y Analista (`PermisosHomologacion.OPERAN`) |
| `ANALITICA` «Analítica farmacéutica» (`analitica`) | Coordinador, Analista, DIGSA, Sanidad y ESM (cada uno en su alcance) | Bitácora: Administrador y Coordinador. Editar cohortes: Administrador y Analista. Recalcular cohortes y reentrenar la predicción: Administrador, Coordinador y Analista |
| `ANALITICA_TRAZABILIDAD` «Trazabilidad de cifras» (`analitica/trazabilidad`) | Coordinador, Analista y DIGSA | Rastreo y CSV de registros de origen (`PermisosTableros.RASTREAN`) |
| `ANALITICA_ALERTAS` «Alertas analíticas» (`analitica/alertas`) | Coordinador, Analista, DIGSA y Sanidad. El ESM recibe 403 | Validar y ejecutar: Administrador, Coordinador y Analista. Parametrizar: Administrador y Analista (`PermisosRiesgo`) |

## 5. Propiedades de configuración (`digsa.analitica.*` en `application.yml`)

| Bloque (clase) | Propiedades (valor por defecto) |
|---|---|
| `digsa.analitica` (`PropiedadesAnalitica`) | `clave-seudonimo` (`DIGSA_ANALITICA_CLAVE_SEUDONIMO`, obligatoria en producción), `parte-max-mb` 50, `archivo-max-mb` 4096, `conservar-dias` 365, `umbral-mal-formadas` 1, `lote-validacion` 250000, `max-registros-lote` 50000, `horas-abandono` 24, `celda-max-kb` 64, `registro-max-kb` 1024, `cuerpo-ws-max-mb` 64, `textos-xlsx-max-mb` 256, `cargues-abiertos-max` 3, `abandono-cron` |
| `.tableros` (`PropiedadesTableros`) | `tiempo-maximo-segundos` 170, `tiempo-maximo-descarga-segundos` 570, `percentil-alto-costo` 99, `poliformulado-minimo` 5, `horas-rezago` 48, `max-registros-traza` 100000, `registros-por-pagina` 50, `supresion-minima` 0, `memoria-trabajo-mb` 128, `paralelos` 2, `cache-resultados` 200, `precalentar` true |
| `.riesgo` (`PropiedadesRiesgo`) | `recalcular-al-aplicar` true, `tiempo-maximo-minutos` 30, `memoria-trabajo-mb` 256, `por-pagina` 50, `cortes-con-membresia` 6, `meses-gracia` 2, `supresion-sensibles` 5, `cohortes-sensibles` vih,huerfanas, `meses-aviso` 3, `incremental` true |
| `.prediccion` (`PropiedadesPrediccion`) | `entrenar-al-aplicar` true, `horizonte` 6, `por-pagina` 20 |
| `.exportacion-hechos` (`PropiedadesExportacionHechos`) | `vigencia-horas` 24, `limite-minutos-mes` 10, `meses-max` 60, `lote` 5000, `revision-ms` 60000 |
| `.descargas-diferidas` (`PropiedadesDescargasDiferidas`) | `umbral-filas` 100000, `max-registros-traza` 2000000, `limite-minutos` 10, `vigencia-horas` 24, `edad-maxima-minutos` 60, `revision-ms` 60000 |

Casi todas se pueden sobrescribir con variables `DIGSA_ANALITICA_*`. No hay bloque `digsa.ia.*`, porque A6 no se construyó.

## 6. Pruebas

| Tipo | Pruebas |
|---|---|
| Reglas puras (JUnit, `backend/reglas`) | `AnaliticaIngestaTest`, `AnaliticaHomologacionTest`, `AnaliticaConsultaTest`, `AnaliticaCohortesTest`, `AnaliticaAlertasTest`, `AnaliticaPrediccionTest`, `AnaliticaExportacionHechosTest`, `AnaliticaDescargaDiferidaTest` y `AnaliticaIncrementalTest` |
| API e integración (`backend/app`, `PruebaIntegracion`) | **Ingesta:** `IngestaApiTest`, `IngestaPrivilegiosTest`, `ArchivoDispensacionTest` y `SeudonimizadorTest`. **Homologación:** `HomologacionApiTest`, `HomologacionSinRedTest` y `SugeridorHomologacionTest`. **Tableros:** `TablerosApiTest`, `AnaliticaDescargasApiTest` y `PlazoYSupresionTest`. **Riesgo:** `CohortesApiTest`, `AlertasApiTest`, `AlertasSembradasTest`, `FiltroCohorteApiTest`, `SupresionRiesgoApiTest`, `RecalculoIncrementalApiTest`, `RecalculoIncrementalApagadoApiTest` y `VolcadoTest`. **Predicción:** `PrediccionApiTest`. **Exportación:** `ExportacionHechosApiTest`. **Raíz:** `PerfilesOperacionApiTest`, `PerfilesOperacionParidadTest`, `ValidacionArranqueAnaliticaTest` y `GeneradorSinteticoTest` |
| Rendimiento, bajo demanda (`-Ddigsa.rendimiento=true`) | `RendimientoIngestaTest`, `RendimientoTablerosTest` y `RendimientoCohortesAlertasTest` |
| Vitest (`frontend/src/analitica`) | `cargues`, `homologacion`, `tableros`, `riesgo`, `prediccion`, `exportacionHechos`, `descargasDiferidas` y `sha256` (`*.test.ts`) |
| E2E Playwright (`e2e/pruebas`) | `analitica-cargues`, `analitica-homologacion`, `analitica-tableros`, `analitica-cohortes-alertas`, `analitica-prediccion`, `analitica-exportacion-hechos` y `analitica-descargas-diferidas` (`.spec.ts`), en Chromium, Firefox, WebKit, Chrome y Edge |
| Apoyo | `GeneradorSintetico` (insumo con errores sembrados y `--patrones`) y `herramientas/totales_insumo.py` (totales esperados fuera del sistema) |

## 7. Lo pendiente

### 7.1 Fases y pruebas

| Pendiente | Estado |
|---|---|
| A6 · Asistente IA y texto a SQL (incluido el sugeridor de homologación por IA; hoy solo existe `SugeridorNoDisponible`) | **En pausa** por orden del usuario (2026-10-28). Hay propuesta para retomar en AN.1 |
| Prueba de carga M8 con 1,5 a 2 M de registros | **Aplazada** por el usuario. Plan, insumo y criterios en `rendimiento_analitica.md`, sección 5 |
| Recorrido e2e del simulacro (orden 14, M8-1 a M8-10 seguidos, en 5 navegadores) y documentación de operación y metodología (GCF, GRC, modelos y reglas) del plan A7 | No hecho. Va con el simulacro |
| Integrar A7.5 en develop | Pendiente de la integradora. A7.4 ya está en develop (`767ff11`) |

### 7.2 Duda AN-1 (AN.1 en `dudas_pendientes.md`)

**Proveedor del modelo de IA frente a datos procesados en Colombia.** Está marcada como BLOQUEANTE para el día de la prueba (caso M8-12) y **la decide el usuario**. Toda la IA está en pausa desde el 2026-10-28. La propuesta para retomar es un asistente «texto a SQL»: al proveedor solo salen el esquema de un catálogo cerrado de vistas y la pregunta, nunca filas ni datos personales.

### 7.3 Dudas AN abiertas

Se cuentan como abiertas las que no dicen «Resuelta», «Aprobada» o «Cerrada», y las que, aun aprobadas o resueltas, tienen una parte pendiente. AN.39 quedó decidida hoy (sección 7.4) y AN.40 aceptada hoy.

| # | Título | Estado |
|---|---|---|
| AN.1 | Proveedor del modelo de IA frente a datos procesados en Colombia | Abierta y bloqueante para M8-12. La decide el usuario. IA en pausa |
| AN.2 | ¿Quién pone los datos del ítem 14? | Aplicada (generador sintético y mapeo por sinónimos). Sin confirmación |
| AN.3 | Archivo original del insumo con datos sensibles | Aplicada (almacén con SHA-256, solo Administrador, 365 días). Sin confirmación |
| AN.4 | Umbrales de alto costo y poliformulación (AGS-49 sin respuesta) | Aplicada con valores parametrizables. Sin confirmación |
| AN.5 | Fuentes de interacciones, uso fuera de indicación, huérfanas y biológicos | Aplicada (tablas cargables; sin fuente, «no evaluado»). Sin confirmación |
| AN.6 | Clave del seudónimo | Aplicada (obligatoria en producción y sin rotación durante el contrato). Sin confirmación |
| AN.7 | «Químico farmacéutico» del mockup | Aplicada (rol de auditor con pantallas como rol adicional). Sin confirmación |
| AN.8 | El Exportador no cubre 14.8 | Aplicada (`ExportadorAnalitica`, capa delgada). Sin confirmación |
| AN.9 | 11.1.15, indicadores del Anexo Técnico 1 | Aplicada (va a reportes). Sin confirmación |
| AN.10 | El ESM y la consola de alertas | Aplicada (403 en la consola; ve los atípicos). Sin confirmación |
| AN.11 | Supresión de celdas pequeñas (k) | Aplicada (parámetro desactivado por defecto). Sin confirmación |
| AN.12 | ¿El 14.10 es eliminatorio? | Se trata como eliminatorio. Sin confirmación |
| AN.13 | Código interno SSFM sin entregar | Aplicada (catálogo cargable; R-030 deja «pendiente de revisión»). Sin confirmación |
| AN.14 | Hechos de dispensación en la exportación completa | Resuelta en A7.2. **Sigue abierta** la confirmación de la DIGSA sobre si también los quiere dentro de la exportación completa |
| AN.15 | R-003: vigencia del registro sanitario frente a qué fecha de dispensación | Falta la decisión de la DIGSA |
| AN.16 | Corte de los tableros y aprobaciones de Revisión; descarga diferida | La descarga diferida se resolvió en A7.3. **Falta** la decisión de la DIGSA sobre el corte por cargue y la medición con 2 M |
| AN.18 | Avisos por correo de las alertas altas al cargar historia | Aplicado (solo los últimos 3 meses; lo anterior va en un resumen). Falta la decisión de la DIGSA |
| AN.19 | Reproducibilidad de los pacientes de una cohorte en cortes antiguos | Aplicado (últimos 6 cortes, parametrizable). Sin confirmación |
| AN.20 | Cohortes y alertas con datos no homologados | Aplicado (se usa el dato del insumo). Sin confirmación |
| AN.21 | Umbrales y bandas sin fuente | Abierto: falta calibrar con la serie real de la DIGSA. ¿Los valida el comité? |
| AN.24 | Supresión de celdas pequeñas en VIH y huérfanas por ESM | Aplicado k = 5. Falta la decisión de la DIGSA (k, cohortes y si se extiende a renal) |
| AN.25 | Ingresos y salidas de una cohorte | Aplicado (2 meses de gracia). ¿La DIGSA lo acepta? |
| AN.26 | Un solo umbral de alto costo | Aplicado (manda la cohorte). Sin confirmación |
| AN.27 | Reproducibilidad de las referencias y de las cifras de una alerta por corte | Mitigado. **Quedó para A7 y no se hizo**: falta fijar las referencias por corte y guardar la evaluación de cada alerta por corte |
| AN.29 | Analítica con la guía de interfaz | Aplicado (formateadores compartidos y tokens LUME). Sin confirmación |
| AN.30 | Predicción: dimensiones y decisiones fuera de la letra de la spec | Aprobada y resuelta en A7.1. Quedan dos cosas: la pregunta de si la DIGSA quiere guardar las proyecciones consultadas al vuelo o entrenar por ESM, y la recomendación de que la DIGSA declare el periodo en cada cargue |
| AN.31 | Supresión de los tableros con una cohorte sensible en el filtro | Aprobada (k = 5 en todos los niveles). Queda como confirmación para la DIGSA |
| AN.32 | Membresía guardada de GCF y GRC | Aprobada. **Falta medirla** en la prueba de carga |
| AN.33 | Versión de la cohorte con que se filtra un corte | Aprobada (versión mayor con resultado). Queda como confirmación para la DIGSA |
| AN.38 | Tope de 10 minutos por mes sin medir con el volumen real | **Sigue abierta** hasta la prueba de carga |
| AN.41 | Los hechos de un cargue aplicado no cambian | **Pendiente para la nueva suite** (integradora, 2026-10-31: sin código al cerrar la hoja de ruta). Recomendación: que reconstruir `ana_hechos_tablero` borre `ana_riesgo_inc_cortes`, para que el siguiente recálculo sea completo. Mientras tanto, recalcular con «Ejecutar» tras cualquier reconstrucción |
| AN.42 | Punto de un ESM con varios puntos | **Pendiente para la nueva suite** (integradora, 2026-10-31: sin código al cerrar la hoja de ruta). Recomendación: que la alerta lleve el punto del último cargue, no el del primer registro. Mientras tanto, respaldo a recorrido completo con su motivo |

Cerradas y sin parte pendiente: AN.17 (cerrada), AN.22 (resuelta), AN.23 (resuelta), AN.28 (resuelta), AN.34, AN.35, AN.36 y AN.37 (aprobadas), AN.39 (decidida hoy) y AN.40 (aceptada hoy: el incremental queda encendido; su ganancia se mide con el simulacro M8 de varios años).

### 7.4 AN.39, decidida el 2026-10-31

Las dos «Descargas del día», la de reportes y la de analítica, quedan separadas. El puerto público `FuenteDescargasDelDia` en `motorreportes` queda como mejora para la nueva suite.

## 8. Riesgos y recomendaciones para migrar

### 8.1 Lo que depende de PostgreSQL

| Mecanismo | Dónde | Recomendación |
|---|---|---|
| `COPY … FROM STDIN` (driver `org.postgresql`: `PGCopyOutputStream`, `getCopyAPI().copyIn`) | `ingesta/CopiadorStaging` (staging), `homologacion/ServicioReferencias` (CUM del INVIMA) y `riesgo/RecalculoRiesgo` (membresía desde `Volcado`) | Es la base de la ingesta de 2 M en minutos. En otro motor hace falta su carga masiva equivalente; con JDBC por lotes el tiempo sube mucho |
| Particiones declarativas (`PARTITION BY RANGE (fecha)` y `PARTITION BY LIST (cargue_id)`), staging UNLOGGED y esquema `analitica_particiones` | V60; funciones `ana_asegurar_particiones`, `ana_staging_crear` y `ana_staging_borrar` | Conservar la partición mensual de los hechos. Las funciones son `SECURITY DEFINER` porque la aplicación no es dueña de las tablas: hay que revisar los roles del nuevo despliegue |
| Índices BRIN y parciales (`WHERE estado = …`), índices únicos parciales como candados de negocio (un cargue «procesando», una exportación en curso por usuario) | V60–V68 | Los únicos parciales imponen reglas («una en curso»). Si el motor no los tiene, hay que llevar esas reglas al código |
| Candados consultivos (`pg_try_advisory_lock`, `pg_advisory_xact_lock`) y `FOR UPDATE SKIP LOCKED` | Ejecutor de cargues, homologación, referencias, recálculo de riesgo, alertas, exportación de hechos y descarga diferida | Son la coordinación entre réplicas. En otra arquitectura (colas o workers) hay que reemplazarlos por un mecanismo explícito de exclusión |
| `SET LOCAL statement_timeout`, `work_mem`, `enable_sort = off`, `max_parallel_workers_per_gather` y `READ ONLY REPEATABLE READ` | `tableros/Plazo`, consultas de tableros, recálculo, exportación y diferido | Sostienen los presupuestos de 3 y 10 min y la consistencia de las exportaciones por corte |
| Funciones SQL: `ana_cargues_visibles(corte)`, `hom_tabla_maestra_version`, `hom_tabla_cie10_version`, `hom_version_vigente` y disparadores de inmutabilidad (`*_inmutable`) | V60, V61 | El corte reproducible (14.8) y la inmutabilidad de la tabla maestra dependen de ellas. Hay que portarlas o moverlas a la capa de datos |
| Funciones de agregación y SQL propio: `percentile_disc`/`percentile_cont`, `date_trunc`, `generate_series`, `unnest`, `= ANY(array)`, `jsonb`, `ON CONFLICT`, `COLLATE "C"` | `SemanticaSql`, detectores de alertas, criterios de cohortes (`jsonb`) y umbral de alto costo | El SQL de los tableros y de los detectores es lo menos portable. Hay que tratarlo como dialecto PostgreSQL |

### 8.2 Lo portable

- **Reglas puras** en `backend/reglas` (las nueve clases `Analitica*` y sus pruebas). Son Java sin Spring ni base de datos: homologación R-0xx, consulta estructurada y lista blanca, criterios de cohortes, detectores de alertas en memoria, predicción (Holt-Winters o Holt, determinista), meses afectados del incremental, exportación por periodo y diferido. Se pueden llevar tal cual y conviene usarlas como especificación ejecutable.
- **Contratos HTTP** `/api/analitica/**` y `/api/homologacion/**`, y los formatos de descarga: CSV UTF-8 con BOM, «;» y coma decimal; Excel sin fórmulas; PDF protegido; metadatos 14.8.
- **Puertos** `FuenteDispensacion`, `HomologacionCargue`, `SugeridorHomologacion` y `PasoAplicacion`, y el evento `CargueAplicado`.
- **Generador sintético y `totales_insumo.py`**, que sirven de banco de pruebas y cuadre en la nueva suite.

### 8.3 Datos y tablas que hay que migrar

| Grupo | Tablas | Nota |
|---|---|---|
| Con datos de negocio (están en la exportación completa) | `ana_cargues`, `ana_cargue_partes`, `ana_ws_lotes`, `ana_cargue_rechazos`, `ana_mapeo_plantillas`, `ana_dim_tiempo`, todas las `ref_*` y `hom_*` de V61, `ana_consultas`, `ana_cohortes`, `ana_cohortes_versiones`, `ana_cohortes_resultados`, `ana_cohortes_serie`, `ana_cohortes_conteos`, `ana_cohortes_ejecuciones`, todas las `ana_alertas*`, `ana_ref_versiones`, `ana_ref_filas`, `ana_pred_entrenamientos`, `ana_pred_proyecciones`, `ana_exportaciones_hechos*` y `ana_descargas_diferidas` | Se migran completas. La tabla maestra y las versiones son inmutables: se copian sin cambios para conservar la reproducibilidad por corte |
| Hechos (fuera de la exportación completa) | `ana_dispensaciones` y `ana_llaves` | Volumen de unos 2 M filas por mes. Se migran por mes, por ejemplo con la exportación de hechos por periodo (A7.2) o con COPY directo. El paciente es un seudónimo HMAC: **la nueva suite necesita la misma `DIGSA_ANALITICA_CLAVE_SEUDONIMO`**, o se rompe la continuidad de cohortes (AN.6) |
| Derivadas (se reconstruyen) | `ana_hechos_tablero`, `ana_pacientes`, `ana_cargue_valores`, `ana_cohortes_miembros`, `ana_riesgo_inc_*`, staging (`ana_staging_dispensacion` y `ref_cum_staging`) | Se pueden regenerar desde los hechos. Si se regenera `ana_hechos_tablero`, hay que borrar `ana_riesgo_inc_cortes` o recalcular completo (AN.41). Si se regenera `ana_pacientes`, cambia la numeración y hay que recalcular la membresía |
| Archivos en el almacén | Originales de cargues (365 días), exportaciones de hechos y descargas diferidas (24 h) | Solo los originales vigentes. Los demás vencen |

### 8.4 Recomendaciones

1. Antes de migrar, correr el simulacro M8 (o hacerlo en la nueva suite) con el mismo generador: las metas R3 y R4 están en riesgo y R5–R8 no se midieron con volumen real.
2. Mantener el corte por cargue (`ana_cargues_visibles`) y la huella del resultado: sostienen la reproducibilidad 14.8 y la trazabilidad 14.9.
3. Si la nueva arquitectura separa el cómputo analítico (un almacén columnar, por ejemplo), empezar por `ana_hechos_tablero` y los detectores SQL de alertas, que son la parte cara (tableros en frío 11–76 s y detectores 65 s de 180 s a 2 M).
4. Antes de retomar A6, cerrar AN.1 con el usuario.
5. Al portar el recálculo incremental, resolver AN.41 y AN.42 (sección 7.3): borrar `ana_riesgo_inc_cortes` al reconstruir los hechos y usar el punto del último cargue en las alertas de nivel ESM.
