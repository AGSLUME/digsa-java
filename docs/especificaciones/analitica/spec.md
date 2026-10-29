# Especificación: analitica (índice y plan de fases)

> Escrita el 2026-10-22 por la sesión de analítica (rama `analitica`, desde develop fe9c98e).
> **Sin PHP de referencia:** el módulo no existía en la app PHP. La especificación sale de la fuente de verdad (ANEXO 4 completo; ANEXO 5 §1.1, §1.2, §2.4–2.12, §4.7.1–4.7.5 y protocolo ítem 14; RESPUESTAS OBSERVACIONES; proyecto de pliego, ponderables P1–P6), del mockup (`data-mod="analitica"`, `"homologacion"` e `"interop"`), de la hoja de ruta de la prueba en sitio (casos M8-1 a M8-12; preparación 12 y 13; simulacro orden 14) y de la investigación de fuentes oficiales (INVIMA, SISPRO, WHOCC).
> Cuando el texto dice «decisión de diseño», es una decisión de esta sesión, pendiente del visto bueno, y se puede cambiar sin tocar la fuente de verdad.

## Capacidades

| Capacidad | Archivo | Ítem 14 | Casos |
|---|---|---|---|
| Ingesta del insumo del OPL, bitácora de cargues y modelo de hechos | [analitica-ingesta](../analitica-ingesta/spec.md) | 14.3 | M8-3, M10-7 |
| Homologación, referencias oficiales, tabla maestra y calidad del dato | [analitica-homologacion](../analitica-homologacion/spec.md) | 14.4 | M8-4 |
| Tableros, filtros, descargas, reproducibilidad y trazabilidad | [analitica-tableros](../analitica-tableros/spec.md) | 14.1, 14.2, 14.5, 14.8, 14.9 | M8-1, M8-2, M8-5, M8-8, M8-9 |
| Cohortes, alertas tempranas y predicción | [analitica-cohortes-alertas](../analitica-cohortes-alertas/spec.md) | 14.6, 14.7, tablero 12 | M8-6, M8-7, M8-11 |
| Asistente de IA en lenguaje natural | [analitica-asistente](../analitica-asistente/spec.md) | 14.10 | M8-10, M8-12 |

## Principios transversales

- **Exclusividad:** el módulo trata solo información farmacéutica (A5 L552, L769). Cuentas, glosas, visitas, presupuesto y seguridad no se reportan aquí.
- **Siempre agregado:** ningún tablero, reporte general, cohorte, alerta ni respuesta del asistente expone a un paciente (A5 L271, L737; RESP L629, L2209, L4551). El paciente se guarda solo como seudónimo HMAC con la clave en el entorno.
- **Alcance:** toda consulta pasa por el `Alcance` del usuario (nacional, Fuerza o ESM; A5 L428).
- **Corte y reproducibilidad:** toda cifra se calcula contra una fecha de corte (los cargues aplicados hasta ese momento). El mismo perfil, filtros y corte dan el mismo resultado (A5 L723–731, 14.8).
- **Trazable:** de cualquier cifra se llega a los registros de origen, a su cargue, archivo, línea y regla de homologación (A5 4.7.4, 14.9; RESP L3989, L5173).
- **Bitácora:** cargues, consultas, filtros, descargas, cambios de reglas y fuentes, alertas, entrenamientos y preguntas al asistente (A5 4.7.4).
- **Tiempos:** ver en ≤ 3 min y exportar en ≤ 10 min (A5 2.7; RESP L899, L2213). La meta interna son segundos sobre 2 M de registros.
- **Formato:** dos decimales, coma decimal, sin separador de miles y hora militar (A5 2.4).

## Plan de fases (un commit por fase en la rama `analitica`)

Cada fase cierra con:
- JUnit de las reglas puras;
- pruebas de API e integración con `PruebaIntegracion`;
- Vitest;
- Playwright en Chromium, Firefox y WebKit, más Chrome y Edge;
- ArquitecturaTest;
- revisión por agentes: security, java, react, database, silent-failure, pr-test-analyzer y **performance-optimizer**, más los adaptados de ECC donde aplique. Los P1 se corrigen antes del commit.

| Fase | Alcance | Migración | Pruebas clave |
|---|---|---|---|
| **A0 · Especificación** | Estas especificaciones, el plan y los agentes adaptados (la carpeta `.claude` no se versiona) | — | — |
| **A1 · Ingesta y hechos** | Puerto `FuenteDispensacion`: archivo CSV/TXT/gz/zip/xlsx y web service simulado del OPL. Subida por partes, mapeo de columnas, seudonimización, COPY a staging, validación y rechazos con causa, aplicación todo o nada e idempotente, bitácora de 8 campos, procesamiento diferido con progreso y pantalla «Cargues del OPL». Generador sintético de 2 M de filas con errores sembrados y totales esperados; `herramientas/totales_insumo.py` | V60 | Recibidos = procesados + rechazados. Idempotencia. Causas de rechazo. Seudónimo, sin documento en claro. Ingesta de 2 M de filas cronometrada |
| **A2 · Homologación** | Referencias oficiales versionadas (CUM INVIMA en sus 4 conjuntos, ATC del CUM, CIE-10, precio regulado y listas parametrizables). Reglas deterministas versionadas, tabla maestra (original → fuente → regla → final), «Revisión» (aprobar o devolver), parametrización en vivo con traza, tablero de calidad y exportación de la tabla maestra | V61 | Cada regla con su caso. Categorías de resultado. Re-ejecución sin tocar lo procesado. % de calidad que suman 100 |
| **A3 · Tableros, descargas y trazabilidad** | Servicio de consulta común (capa semántica que también usa el asistente). Agregados refrescados por cargue. Los 12 tableros: 9 completos, y cohortes, atípicos y predictivo como enlace a A4/A5. Vistas gerencial, por Fuerza y por ESM. Filtros con selección múltiple y granularidades, comparación entre periodos, Top 20, descargas Excel/CSV/PDF con todos los metadatos, reproducibilidad, recorrido de una cifra hasta el registro de origen y bitácora de consultas y descargas | V62 | **Cuadre** contra los totales del generador, por dimensión y entre dimensiones. Alcance por perfil. Reproducibilidad entre dos usuarios. Metadatos completos. Tiempo por tablero sobre 2 M de filas |
| **A4 · Cohortes y alertas** | Cohortes persistentes y parametrizables (las 10 del A4, GCF y GRC), siempre agregadas y con criterio visible. Motor de alertas tempranas con contexto (umbral, periodo, dimensión y severidad), análisis técnico y validación humana. Consola con perfiles autorizados, umbrales parametrizables y aviso por correo con `ServicioAlertas` | V63 | Detecta **todos** los errores sembrados del generador. Sin individualización. Contexto completo. Perfiles |
| **A5 · Predicción** | Proyección a 6 meses (Holt-Winters o Holt con tendencia, según la historia disponible), validación temporal, IC 95 %, metadatos, observado frente a proyectado y bitácora de entrenamientos | V64 | Series conocidas con resultado esperado. Poca historia (1 año). Metadatos visibles |
| **A6 · Asistente IA** | Puerto `AsistenteAnalitico` con los adaptadores Claude (por defecto `claude-opus-5-5`), compatible con OpenAI (modelo en Colombia o local) y «no disponible». Consulta estructurada validada contra la capa semántica, advertencia ante filtros inexistentes o ambiguos, auditoría, límites, tiempo de espera y defensa contra inyección. Sugeridor de homologación por el mismo puerto | V65 | Cifras iguales a las del tablero. Filtro inexistente → advertencia. Auditoría con herramientas ejecutadas. Inyección. Modo sin IA. Evals con adaptador simulado |
| **A7 · Rendimiento y simulacro M8** | Prueba de carga documentada (ingesta, tableros, exportación y asistente, repetida). Recorrido e2e del simulacro (orden 14: M8-1 a M8-10 seguidos), afinamiento, documentación de operación y metodología (GCF, GRC, modelos y reglas) | V66 si hace falta | `docs/rendimiento_analitica.md`. Simulacro en 5 navegadores |

V67–V69 quedan de reserva.

## Aprobación

Aprobado el 2026-10-22 por la sesión 1 (suite-digsa-d8), con delegación del usuario. Se aprobaron las opciones recomendadas de las dudas 1 a 5 y estos ajustes:
- **Seudónimo:** con `DIGSA_ENTORNO=produccion` y sin `DIGSA_ANALITICA_CLAVE_SEUDONIMO` la aplicación no arranca (bloque aditivo en ValidacionArranque). La clave nunca va al repositorio ni a los logs, y la rotación queda documentada.
- **Perfiles:** «químico farmacéutico» es un rol de auditor y no se mapea a ANALISTA. El acceso va por pantallas asignables, con los roles adicionales de identidad, y no se cambian perfiles existentes.
- **Exportador:** `ExportadorAnalitica` es una capa delgada sobre `plataforma/Exportador`, cuyo dueño es la sesión de reportes. Se sustituye al integrar su R2.
- **11.1.15:** pasa al módulo de reportes, por su contrato `ProveedorReporte`.
- **Barras:** se usan barras propias; el componente compartido no se modifica.
- **Maven y puertos:** un repositorio Maven local por worktree (`MAVEN_OPTS=-Dmaven.repo.local=.local/m2 …`), y puerto propio si 8080 o 54329 están ocupados.
- **Cargas de 2 M filas:** se corren lo menos posible y se reutiliza el archivo generado.

## Archivos compartidos (cambios mínimos, aditivos y en bloque propio `// --- Analítica (rama analitica) ---`)

- `reglas/Pantallas.java`: ANALITICA, ANALITICA_CARGUES y HOMOLOGACION, en el grupo «Información».
- `reglas/Bitacora.java`: tipos `analitica_*` y `homologacion_*`.
- `reglas/Exportacion.java`: tablas nuevas en TABLAS; staging en TECNICAS.
- `frontend/src/App.tsx`: rutas e imports (a mano).
- `application.yml`: claves `digsa.analitica.*` e `digsa.ia.*` con `${DIGSA_*:defecto}`, en un bloque propio. Si hay que tocar `ConfiguracionDigsa`, se hace con una clase de propiedades propia del módulo para no chocar.
- Alertas: solo `INSERT … ON CONFLICT DO NOTHING` de un tipo nuevo en `alertas_criticas` (V63) y llamadas a `ServicioAlertas.disparar`.
- **No se tocan** identidad, alertas (código), exportacion, tablero, reportes, agenda, ejecucion, opl, presupuesto, ci ni docker.
- Dependencias: ninguna nueva prevista. El cliente HTTP de la IA usa el `RestClient` de Spring, el COPY usa el `CopyManager` del driver de PostgreSQL y los gráficos usan SVG propio.
