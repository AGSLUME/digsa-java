# Rendimiento del módulo de analítica farmacéutica

> Escrito el 2026-10-31 en la fase A7.5 (rama `analitica`). Reúne las metas de rendimiento del módulo y todas las mediciones reales hechas entre A1 y A7.4.
> Las mediciones vienen de las notas de trabajo de la sesión (`.local/notas/mediciones.md`, sin versionar), que aquí se pasan completas, y de las decisiones registradas en las especificaciones.
> **La prueba de carga M8 con 1,5 a 2 M de registros está APLAZADA por el usuario desde el 2026-10-29.** Todo lo medido desde A7.1 usa el volumen de las pruebas normales, unos 20 000 registros.
> Todos los insumos son sintéticos: documentos y nombres ficticios del `GeneradorSintetico`. El documento no trae datos personales.

## 1. Entorno de las mediciones

| Grupo | Fases | Volumen | Entorno |
|---|---|---|---|
| **Volumen de contrato** | A1, A2, A3 y A4 (2026-10-22 a 2026-10-24) | 2 M filas (370 MB). Insumo `sintetico_2000000.csv` de un año (2025) con 11 causas de rechazo sembradas | Equipo de desarrollo, PostgreSQL embebido (zonky, configuración por defecto) o base local persistente, y otras sesiones activas en la misma máquina (CPU al 80–100 %). **No representa un servidor dedicado.** |
| **Volumen normal** | A7.1, A7.2, A7.3 y A7.4 (2026-10-29 a 2026-10-31) | 19 977 a 20 758 hechos. Generador con patrones, año 2025 | PostgreSQL embebido, contexto de prueba propio y máquina compartida con otras sesiones. Ruido de unos ±0,5 s |

Los dos grupos **no se comparan entre sí**. Las cifras de A7.x sirven para ver el costo relativo de cada cambio (filtro, exportación, diferido o incremental) y para extrapolar, **sin medir**, el volumen real.

## 2. Metas y estado

Hay tres estados posibles: **cumple**, **no medido con volumen real** y **en riesgo**. Este último quiere decir que con 2 M se midió por encima de la meta.

| # | Meta | Valor | Fuente | Lo medido (fecha · volumen · entorno) | Estado |
|---|---|---|---|---|---|
| R1 | Ver un tablero | ≤ 3 min por petición. Presupuesto de 170 s en `digsa.analitica.tableros.tiempo-maximo-segundos` | A5 2.7 (A5 L359); RESP L899 (AGS-75) y L2213 (GMI P74); spec analitica-tableros «Tiempos» | 2026-10-23/24 · 2 M · máquina compartida. Peor tablero en frío sin filtros: 76,3 s (diagnóstico). Base persistente: 11–28 s | **Cumple** |
| R2 | Exportar o descargar un tablero | ≤ 10 min. Presupuesto de 570 s en `tableros.tiempo-maximo-descarga-segundos` | A5 2.7; AGS-75 | 2026-10-24 · 2 M. Tablero de medicamento, frío y tras verlo: xlsx 10,9/1,1 s; csv 7,2/0,7 s; pdf 7,7/1,0 s | **Cumple** |
| R3 | Meta interna: tableros en frío | < 5 s por tablero, con y sin filtros, sobre 2 M | Decisión de diseño del 2026-10-22 (spec analitica-tableros, «Escenario: Tablero con todos los filtros») | 2026-10-23/24 · 2 M. En frío sin filtros, 18,7–76,3 s (11–28 s en la base persistente). Con filtros Fuerza + ESM + CUM, 4,1–19,6 s (0,8–7,9 s en la base persistente). En caché, 0,7–3,6 s (25–100 ms en la base persistente). A 20 000 registros (A7.1): 1,3–4,0 s | **En riesgo** en frío sin filtros. Cumple en caché y con el precálculo (`tableros.precalentar`). Hay que medirlo en un servidor dedicado |
| R4 | Meta interna: ingesta | < 5 min (300 s) para 2 M filas | Decisión de diseño del 2026-10-22 (spec analitica-ingesta, «Pruebas de cuadre y tiempos medidos») | Mejor corrida, A1 revisada: 5,5 min. Con homologación (A2): 5,7 min. A4: 20,3 min con la máquina muy cargada (no representativa) | **En riesgo**: 10–15 % por encima en el equipo compartido |
| R5 | Exportación de los hechos por periodo | ≤ 10 min por mes. Cada mes en su transacción; `exportacion-hechos.limite-minutos-mes` = 10 | AGS-75 / GMI P74 (10 min para exportar); duda AN.14; spec analitica-ingesta | 2026-10-30 · 19 977 filas · 12 meses en 5,1–7,8 s; 116–434 ms por mes. Sin medir con 2 M filas por mes | **No medido con volumen real** (AN.38) |
| R6 | Descarga diferida de los registros de origen | ≤ 10 min por descarga (`descargas-diferidas.limite-minutos` = 10); tope de 2 000 000 registros | A5 2.7; duda AN.16; spec analitica-tableros «Descarga diferida» | 2026-10-30 · 11 902 registros (4,06 MB) en 2,1–3,5 s de la solicitud a «lista». En A3, a 2 M: CSV en la petición de 99 255 registros (33 MB) en 5,5 s | **No medido con volumen real** (AN.16, AN.38) |
| R7 | Recálculo de cohortes y alertas al aplicar un cargue | Sin meta numérica en los anexos. La referencia es «cuasi real» (RESP L2217). Cada sentencia tiene un tope de 30 min (`riesgo.tiempo-maximo-minutos`) | spec analitica-cohortes-alertas | 2026-10-24 · 2 M · máquina cargada · versión **anterior** a la revisión A4: 180 s (15 % del cargue). A 20 000 filas (A7.4): 2,6–5,5 s, completo o incremental | **No medido con volumen real** después de la revisión A4 ni con el incremental (AN.40) |
| R8 | Recálculo incremental: ganancia frente al completo | Resultado idéntico al completo (obligatorio) y menor tiempo con historia larga (deseable) | Duda AN.23; spec analitica-cohortes-alertas «Recálculo incremental» | 2026-10-31 · 20 000 + 3 cargues de un mes · un año de historia. Equivalencia comprobada (`RecalculoIncrementalApiTest#equivalencia`). Sin ganancia: la ventana de 19 meses cubre todo el año | Equivalencia **cumple**. Ganancia **no medida con volumen real** (AN.40) |
| R9 | Caso M10-7: ingesta de 2 M con 10 a 20 usuarios consultando | La aplicación no se cae y el cargue termina «aplicado» | Hoja de ruta de la prueba en sitio, caso M10-7; spec analitica-ingesta | No se corrió | **No medido con volumen real** |
| R10 | Asistente de IA | ≤ 60 s por pregunta (`DIGSA_IA_ESPERA_S`) | spec analitica-asistente | A6 no se construyó: la IA está en pausa | No aplica |

## 3. Mediciones con 2 M de registros (A1–A4, máquina compartida)

### 3.1 Ingesta de 2 M filas (370 MB)

| Fecha | Fase | Subida | Lectura | Validación | Homologación | Hechos | Tablas/agregados | Procesamiento | Nota |
|---|---|---|---|---|---|---|---|---|---|
| 2026-10-22 | A1 v1, corrida 1 | 17 s | 162 s | 154 s | — | 304 s | 0 | 10,4 min | Python en paralelo |
| 2026-10-22 | A1 v1, corrida 2 | 17 s | 119 s | 99 s | — | 337 s | 0 | 9,3 min | 7 índices en hechos |
| 2026-10-23 | A1 revisada | 20,5 s | 116,7 s | 76,1 s | — | 135,4 s | 0 | 5,5 min | PK + 2 BRIN, `ana_llaves` |
| 2026-10-23 | A2 | 18,6 s | 103,8 s | 50,3 s | 56,1 s | 132,1 s | 0 | 5,7 min | |
| 2026-10-24 | A4 (máquina muy cargada) | 31 s | 416 s | 228 s | 149 s | 327 s | 99 s | 20,3 min | No representativa |

- Rechazos sembrados: 11 causas × ~125 = 1 376. Procesados: 1 998 624. El gasto cuadra al centavo con los totales esperados del generador.
- `PasoTablas`, el paso que llena las tablas de apoyo de los tableros, tarda 29 s en la base persistente. Los agregados de la primera versión tardaban 36–68 s.

### 3.2 Tableros (A3 después de la revisión, 2 M filas, CPU al 80–100 % por otras sesiones)

Antes de la revisión, «general» tardaba unos 100 s y «gasto» pasaba de 170 s (respondía 422). La causa era `count(DISTINCT char(64))` con la intercalación es_CO. La solución fue el paciente como entero en `ana_hechos_tablero`.

| Tablero | Frío sin filtros | Caché | Filtros Fuerza+ESM+CUM (frío/caché) | ARC semanal + año anterior (frío/caché) |
|---|---|---|---|---|
| general | 28,9 s | 2,6 s | 5,3/1,2 s | 6,7/0,8 s |
| gasto | 42,6 s | 1,6 s | 8,5/1,1 s | 8,8/0,9 s |
| atc | 34,8 s | 2,7 s | 6,2/1,5 s | 5,2/0,9 s |
| principio | 55,3 s | 3,6 s | 6,3/0,8 s | 6,0/1,0 s |
| medicamento | 45,2 s | 1,4 s | 6,1/2,4 s | 4,1/1,0 s |
| diagnóstico | 76,3 s | 1,1 s | 7,0/0,8 s | 7,7/0,9 s |
| territorial | 18,7 s | 0,7 s | 5,4/0,9 s | 5,9/1,0 s |
| alto costo | 25,9 s | 0,8 s | 19,6/1,9 s | 18,2/1,7 s |
| poliformulación | 27,0 s | 1,3 s | 5,2/1,0 s | 10,3/0,8 s |

- Descargas del tablero de medicamento (frío / después de verlo): xlsx 10,9/1,1 s; csv 7,2/0,7 s; pdf 7,7/1,0 s.
- CSV de registros de origen: 99 255 registros (33 MB) en 5,5 s.
- Base persistente menos cargada: en frío sin filtros 11–28 s, con filtros 0,8–7,9 s y en caché 25–100 ms.

### 3.3 Cohortes y alertas (A4 antes de la revisión, 2 M, máquina cargada)

- Recálculo automático: 180 s, el 15 % del cargue. Se reparte así: lectura 151 s, recorrido 70 s, alertas 65 s, escritura 29 s y umbrales 14 s. Las etapas se copian como se anotaron; su suma no coincide con el total y la nota no lo explica.
- Consola: 2–4 s. Tarjetas de cohortes: 10 s. Composición: 12–18 s. Descarga CSV: 3–4 s. Reejecución: 215 s.
- A 2 M salió un falso negativo (concentración BAS10 en 2025-11). Se corrigió en la revisión A4, pero **la corrida de 2 M no se repitió** después de la corrección: queda para el simulacro M8.

## 4. Mediciones con el volumen normal (A7.1–A7.4, unos 20 000 registros)

### 4.1 A7.1 · Cohorte, GCF y GRC como filtro (2026-10-29)

Insumo: 20 758 hechos, generador con patrones, año 2025. Se midió en `FiltroCohorteApiTest#rendimientoDelFiltro`, con la primera consulta de cada combinación y sin caché de resultados.

- **Plan del total con `cohorte=renal`.** El planificador usa un Hash Semi Join por (mes, paciente). La membresía entra por un Index Only Scan de la llave primaria de `ana_cohortes_miembros` (versión y corte) y su hash ocupa 9 kB. El total tarda 187 ms, de los cuales 155 ms son el recorrido de los hechos. No hizo falta un índice nuevo.
- **Membresía del corte.** Son 3 019 filas de cohortes y 18 748 de las particiones GCF y GRC (nuevas en V65, ≈ 0,9 filas por hecho a este volumen). La tabla completa ocupa 7,4 MB.
- **Recálculo del corte** (12 versiones, con la membresía de las particiones). Primera corrida: 13,9 s (escritura 3,1 s). Segunda corrida: 6,3 s (escritura 3,0 s). La escritura crece con la membresía de las particiones y hay que medirla a escala (AN.32).

| Tablero | Sin filtro | cohorte=renal | grc=GRC1,GRC2 |
|---|---|---|---|
| general | 1 802 ms | 1 598 ms | 3 956 ms |
| gasto | 2 430 ms | 2 621 ms | 2 316 ms |
| atc | 1 657 ms | 1 610 ms | 2 062 ms |
| principio | 1 866 ms | 1 945 ms | 1 877 ms |
| medicamento | 1 438 ms | 1 342 ms | 1 784 ms |
| diagnóstico | 1 968 ms | 1 574 ms | 1 704 ms |
| territorial | 1 686 ms | 1 345 ms | 1 756 ms |
| alto costo | 1 818 ms | 1 618 ms | 2 120 ms |
| poliformulación | 1 636 ms | 1 344 ms | 1 790 ms |

El filtro no encarece la consulta: el semijoin descarta filas antes de agrupar. Con dos grupos, GRC mete más miembros en el hash, cerca del 60 % de los pacientes-mes.

### 4.2 A7.2 · Exportación de los hechos por periodo (2026-10-30)

Se midió con `ExportacionHechosApiTest`.

| Caso | Corrida 1 | Corrida 2 | Corrida 3 (después de la revisión A7.2) |
|---|---|---|---|
| 12 meses de 2025: 19 977 filas, 1 415 089 B en .csv.gz | 6 804 ms de la solicitud a «lista» | 7 839 ms | 5 094 ms |
| Por mes (≈ 1 400–1 750 filas, ≈ 100–122 KB) | 151–273 ms | 203–434 ms | 116–217 ms |
| 4 meses de 1998: 6 filas, con marzo vacío | 5 695 ms (en cola detrás de otra exportación) | 3 234 ms | 4 339 ms |
| Por mes (0–3 filas) | 188–314 ms | 111–241 ms | 152–323 ms |

- Un mes casi vacío cuesta unos 100–250 ms: conexión, `SET LOCAL statement_timeout`, revisión de columnas, `ana_cargues_visibles` y escritura del gzip y del almacén. El resto del total es la cola y el sondeo de la prueba, que pregunta cada 100 ms.
- Una fila ocupa unos 71 B comprimida y unos 1 400 B sin comprimir.
- **Extrapolación sin medir:** un mes de 2 M filas daría unos 140 MB comprimidos. Al ritmo de esta prueba (≈ 10 000 filas/s con el gzip incluido) tardaría unos 3–4 min, bajo el tope de 10 min (AN.38).

### 4.3 A7.3 · Descarga diferida de analítica (2026-10-30)

Se midió con `AnaliticaDescargasApiTest`, en un contexto propio con `umbral-filas` = 5 y `max-registros-traza` = 15 000. Cada cifra es el tiempo de la solicitud (202) a «lista»; entre paréntesis va `duracion_ms`, la duración de la generación que guarda el servidor.

| Caso | Corrida 1 | Corrida 2 |
|---|---|---|
| Tablero de medicamento en CSV, 2025, 19 977 registros, 2 202 B | 1 285 ms (299 ms) | 1 526 ms (513 ms) |
| Tablero de medicamento en Excel, mismos registros, ≈ 6 000 B | 1 565 ms (551 ms) | 1 555 ms (589 ms) |
| Tablero de medicamento en PDF, mismos registros, ≈ 70 800 B | 2 130 ms (1 219 ms) | 1 576 ms (517 ms) |
| CSV de registros de origen de una Fuerza, 11 902 registros, 4 061 502 B (≈ 341 B por registro) | 2 632 ms (1 232 ms) | 2 144 ms (832 ms) |
| Tablero de medicamento en CSV, 1996, 8 registros | 1 318 ms | 1 442 ms |

- **Corrida 3, después de la revisión independiente del mismo día.** Los tableros ya no se difieren, así que sus filas de arriba quedan solo como referencia. La descarga en la petición ya no lleva conteo previo:
  - CSV del tablero de medicamento en la petición, sobre 19 977 registros: 1 165 ms.
  - CSV de registros de origen en diferido: 11 902 registros en 3 455 ms (generación 1 815 ms, con la máquina más cargada).
  - Traza de 6 registros: 2 563 ms.
- Las descargas de los tableros son agregadas (Top 20 y tablas por dimensión): su tamaño no crece con los registros. Lo que cuestan es la consulta del tablero (sección 3.2).
- **Extrapolación sin medir:** el CSV de registros de origen avanza a unos 10 000–14 000 registros/s. Con 2 M registros serían unos 680 MB en 3–4 min, bajo el presupuesto de 10 min (AN.16, AN.38).

### 4.4 A7.4 · Recálculo incremental de cohortes y alertas (2026-10-31)

Se midió con `RecalculoIncrementalApiTest#equivalencia`. Base: 20 000 filas sintéticas con patrones (2025, 12 versiones de cohorte). Encima se aplicaron tres cargues de un mes, de unas 1 600–1 900 filas cada uno. En cada paso se corrió primero el recálculo automático incremental y luego «Ejecutar» (completo) sobre el mismo corte. La cifra es `duracion_ms` de la ejecución de cohortes, que incluye alertas y escritura.

| Paso (meses recalculados) | Incremental, corrida 1 | Completo, corrida 1 | Incremental, corrida 2 | Completo, corrida 2 | Filas leídas inc./comp. |
|---|---|---|---|---|---|
| Mes nuevo 2026-01 (2025-11 a 2026-01) | 4 195 ms | 3 878 ms | 5 514 ms (alertas 2,2 s: ruido) | 3 267 ms | 23 609 / 22 519 |
| Agregar a 2025-06 (2025-04 a 2026-01) | 3 750 ms | 3 809 ms | 2 942 ms | 3 224 ms | 25 531 / 24 354 |
| Reemplazar 2026-01 (2025-11 a 2026-01) | 3 550 ms | 3 494 ms | 2 832 ms | 2 584 ms | 25 411 / 24 262 |

- **Corrida 3, después de la revisión A7.4.** Incremental frente a completo:
  - mes nuevo: 3 280 frente a 3 366 ms;
  - agregar a 2025-06: 3 298 frente a 3 564 ms;
  - reemplazar 2026-01: 3 085 frente a 3 080 ms;
  - después de una corrida parcial (corte de 18 meses): 4 442 frente a 4 096 ms.

  Las filas leídas son iguales en los dos modos (22 519–33 172).
- **Por qué no hay ganancia.** Con un año de historia, un mes nuevo obliga a leer 19 meses: 14 hacia atrás por las cohortes de 12 meses y la gracia, y 2 hacia adelante por las salidas. Eso cubre todo el corte. El incremental suma además:
  - la pasada de estrecho margen (unos 20 ms);
  - traer la versión anterior (26–137 ms);
  - copiar la membresía con INSERT…SELECT (138–607 ms);
  - los conteos en SQL (60–88 ms).

  La escritura (≈ 1,6–2,3 s, sobre todo la membresía de unas 25 000 filas) no cambia.
- **Etapas de la corrida 2, paso «Reemplazar».**

  | Etapa | Incremental | Completo |
  |---|---|---|
  | Plan | 34 ms | — |
  | Catálogo | 124 ms | — |
  | Umbrales | 111 ms | — |
  | Recorrido | 313 ms | 324 ms |
  | Estrecho margen | 17 ms | — |
  | Versión anterior | 59 ms | — |
  | Alertas | 444 ms | 337 ms |
  | Conteos | 60 ms | — |
  | Copia de la membresía | 483 ms | — |
  | Versión utilizable | 44 ms | 40 ms |
- **Extrapolación sin medir:** con 2 M filas por mes y 3 años de historia (72 M), un mes nuevo leería 19 de 36 meses (≈ 53 %) en el recorrido y los umbrales. Los detectores agregados en SQL (65 s de 180 s a 2 M en A4) seguirían leyendo todo (AN.40).

## 5. Simulacro M8 (pendiente de aprobación del usuario)

No se corre sin la aprobación expresa del usuario. Cargar 2 M de filas se hace lo menos posible y se reutiliza el archivo generado (spec analitica, «Aprobación»).

### 5.1 Qué se mediría

| # | Medición | Cómo | Meta |
|---|---|---|---|
| S1 | Ingesta de 2 M filas, con las etapas en `ana_cargues.etapas_ms`, dos corridas | `RendimientoIngestaTest#dosMillones` | R4 (< 5 min) |
| S2 | Los 9 tableros completos en frío, en caché, con filtros y con ARC semanal + año anterior. Además, filtro de cohorte, GCF y GRC a escala | `RendimientoTablerosTest#tablerosSobreDosMillones`. El filtro de cohorte no está en esa prueba: se mide a mano o se agrega | R1, R2, R3 |
| S3 | Cohortes y alertas, completo, con la membresía de GCF y GRC (V65), y verificación de que el motor detecta todas las alertas sembradas después de la revisión A4 | `RendimientoCohortesAlertasTest#cohortesYAlertasSobreDosMillones` (falla si falta una sembrada o si una cohorte no cuadra) | R7; AN.32 |
| S4 | Cohortes y alertas **completo frente a incremental** con 2 a 5 años de historia y un mes nuevo, agregar y reemplazar | **No existe prueba de 2 M para esto.** Hay que repetir el patrón de `RecalculoIncrementalApiTest#equivalencia` con insumo grande | R7, R8; AN.40 |
| S5 | Exportación de los hechos por mes con unos 2 M filas por mes, más un periodo de 12 meses | **No existe prueba de 2 M.** Hay que medir con `ExportacionHechosApiTest` o a mano desde «Cargues del OPL» | R5; AN.38 |
| S6 | Descarga diferida de los registros de origen con 0,5 M, 1 M y 2 M registros (el tope por defecto) | **No existe prueba de 2 M.** Hay que medir con `AnaliticaDescargasApiTest` o a mano desde «Trazabilidad de cifras» | R6; AN.16, AN.38 |
| S7 | Caso M10-7: ingesta de 2 M mientras 10 a 20 usuarios consultan tableros | Fuera de JUnit, con un cliente de carga | R9 |
| S8 | Recorrido e2e del simulacro: orden 14 de la hoja de ruta, M8-1 a M8-10 seguidos, en 5 navegadores | Playwright (Chromium, Firefox, WebKit, Chrome y Edge, según `e2e/playwright.config.ts`) | Plan A7 de la spec analitica |

### 5.2 Insumo y parámetros (verificados en el código)

- **Generador:** `backend/app/src/test/java/co/com/agsamericas/digsa/analitica/GeneradorSintetico.java`. Acepta `--filas N`, `--semilla`, `--salida` y `--patrones`, que siembra los patrones clínicos que el motor debe detectar y escribe las alertas esperadas en el JSON. Escribe `sintetico_<filas>[_patrones].csv` y `totales_esperados_<filas>.json`. Los mismos totales se pueden calcular fuera del sistema con `herramientas/totales_insumo.py`.
- **Pruebas de rendimiento:** `RendimientoIngestaTest`, `RendimientoTablerosTest` y `RendimientoCohortesAlertasTest` llevan `@Tag("rendimiento")` y `@EnabledIfSystemProperty(named = "digsa.rendimiento", matches = "true")`. Su volumen sale de `-Ddigsa.rendimiento.filas` (2 000 000 por defecto) y su carpeta de `-Ddigsa.rendimiento.carpeta` (`../../.local/analitica` por defecto). Ejemplo, tomado del comentario de la prueba:
  `./mvnw -q test -Dtest=RendimientoTablerosTest -Ddigsa.rendimiento=true -Dsurefire.failIfNoSpecifiedTests=false`
- **Faltante para S4:** el generador escribe un solo año (`ANIO = 2025`, constante). Para tener 2 a 5 años de historia hay que extenderlo con un parámetro de año inicial o de número de años, o generar varios archivos y desplazar sus fechas. Ese cambio es de código de prueba y queda para cuando se apruebe el simulacro.
- **Entorno:** un servidor dedicado o, como mínimo, la máquina sin otras sesiones. Hay que anotar CPU, memoria, versión de PostgreSQL y si la base era embebida o persistente.

### 5.3 Criterios de aceptación

1. **Ingesta (S1):** procesamiento < 5 min en las dos corridas; recibidos = procesados + rechazados; rechazos por causa iguales a los sembrados; gasto cuadrado al centavo.
2. **Tableros (S2):** cada tablero < 3 min por petición, sin 422 por tiempo, en cualquier combinación. Meta interna < 5 s en caché y con filtros. Si en frío sin filtros pasa de 5 s, se documenta con el efecto del precálculo.
3. **Cohortes y alertas (S3):** se detectan **todas** las alertas sembradas (incluida la concentración que falló antes de la revisión A4) y todas las cohortes cuadran. Se anota el tiempo total y por etapa, y el tamaño de `ana_cohortes_miembros` con GCF y GRC.
4. **Incremental (S4):** resultado idéntico al completo, tabla por tabla. Con más de unos 19 meses de historia, el incremental tarda menos que el completo. Se anota la parte de los detectores SQL.
5. **Exportación de hechos (S5):** cada mes de unos 2 M filas termina en menos de 10 min, sin meses fallidos, con su SHA-256 y el cuadre de filas y `valor_total` en el LEEME.
6. **Descarga diferida (S6):** 2 M registros en menos de 10 min, con el archivo completo y su SHA-256.
7. **M10-7 (S7):** ninguna caída; el cargue termina «aplicado»; las consultas siguen dentro de R1.
8. **Resultado:** se actualiza este documento con fecha, volumen y entorno de cada medición, y el estado de cada meta de la sección 2.

### 5.4 Dudas que cerraría

| Duda | Qué cierra el simulacro |
|---|---|
| AN.32 | Peso real de la membresía guardada de GCF y GRC (filas, MB y tiempo de escritura del COPY desde `riesgo/Volcado`) con la retención de 6 cortes. Si pesa, se elige entre un arreglo por paciente o conservarla solo en el último corte |
| AN.38 | Si un mes de 2 M filas cabe en el tope de 10 min. Si no cabe, se baja el lote o se parte el mes por quincena |
| AN.40 | Ganancia del incremental con 2 a 5 años de historia, y si los detectores SQL dominan, caso en que se pasarían a agregados mensuales guardados |
| AN.16 (parte de medición) | Descarga diferida de 2 M registros dentro de `limite-minutos` |
| AN.19 | Costo de conservar la membresía de 6 cortes (relacionada con AN.32), como dato para decidir si se conservan más o menos cortes |
| AN.21 (en parte) | El simulacro sintético solo confirma que no hay falsos negativos. La calibración de umbrales necesita la serie real de la DIGSA |
| Sin número | Repetir la corrida de cohortes y alertas de 2 M después de la revisión A4: el falso negativo está corregido pero no verificado a escala |
