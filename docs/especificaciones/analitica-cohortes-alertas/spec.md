# Especificación: analitica-cohortes-alertas

> Escrita por spec-miner el 2026-10-22. **No hay PHP de referencia**: la capacidad es nueva. Sale de la fuente de verdad (`FUENTE DE VERDAD/md/`: A4 = Anexo 4, A5 = Anexo 5, RESP = Respuestas a observaciones (OCR; ante diferencias manda el PDF), PLIEGO = proyecto de pliego 28-09-2026), del mockup M8 (`MOCKUP/Mockup_Modulos_DIGSA.html`, `#analitica`), de la hoja de ruta de la prueba en sitio (casos M8-6, M8-7, M8-11) y del diseño común del módulo de analítica (sesión 2026-10-22, que **manda** sobre lo demás salvo la fuente de verdad).
> Fuentes leídas: A4 §11.1 d, e, f (L650–726), L275, L304, 11.2.4 y 11.2.7 (L787, L806), L855, L865; A5 4.7.1 tableros 10–12 (L627–647), 4.7.3 (L737–739), 4.7.4 (L743–765), ítem 14.6 y 14.7 (L948, L962); RESP L701–707, L2217, L2239–2241, L2245–2249, L2259, L5143–5149, L5173–5175, L5361–5367, L5647, L5671; PLIEGO L2428–2430 (P4, P5); `alertas/ServicioAlertas.java`, `alertas/ServicioCatalogoAlertas.java`, `reglas/Alertas.java`, `V17__alertas_bandeja.sql`.
> Relacionadas (en redacción en la misma rama): ingesta y modelo (`ana_dispensaciones`, seudónimo, corte), homologación (referencias oficiales V61: estrecho margen, control especial, vitales; estados «diferencia en concentración/forma/presentación»), tableros, descargas y trazabilidad 14.8/14.9, asistente IA. Alcance y perfiles en `perfiles-alcance`; canal de alertas en `exportacion-alertas`.
> Estado en Java (worktree `digsa-java-analitica`, commit fe9c98e): no existe el paquete `analitica` ni migraciones V60–V69. Existen y se **usan sin modificar**: `ServicioAlertas.disparar` (NESTED, operativa = un aviso por `(tipo, clave)` vía `ux_alertas_eventos_unico`), catálogo `alertas_criticas` con `CHECK (critica OR NOT sms)` y edición que no permite cambiar `critica`, `RegistroBitacora`, `Bitacora.TIPOS`, `Exportador`, `reglas/Alcance` (Todo, Fuerza, Esm; `PERFILES_CONSULTA`), `Pantallas`.

---

### Requirement: Cohorte como objeto persistente, parametrizable y versionado
<!-- id: ServicioCohortes.guardar -->
<!-- entidades: Cohorte, CohorteVersion -->
<!-- impuesto_en: RESP L5143–5145 (ATI 3.7: «mantenga la definición de la cohorte como objeto persistente y parametrizable, la actualice conforme ingresa nueva información, permita comparar su comportamiento entre períodos y niveles») | A5 L962 14.6 («se generan cohortes con distintos criterios») -->
<!-- decision: diseño 2026-10-22 -->

Una cohorte DEBE guardarse como objeto con código, nombre, descripción, criterios combinados y versión; NO DEBE ser un filtro efímero. Editar los criterios DEBE crear una versión nueva (la anterior queda consultable con sus resultados); los resultados de cada versión se recalculan sobre **toda** la historia disponible. Criterios admitidos, combinables con Y / O y negación: CIE-10 (código o prefijo, del histórico de diagnósticos del paciente), ATC (cualquier nivel), principio activo, medicamento normalizado (tabla maestra), grupo farmacológico, lista de referencia (estrecho margen, control especial, biológicos, huérfanas, crónicos), patrones de consumo (n.º de principios activos distintos por mes, meses consecutivos, gasto en ventana móvil) y variables de la operación (Fuerza, ESM, modalidad). Cada criterio lleva su ventana de observación.

#### Scenario: Cohorte que indica el comité
<!-- test: M8-6; por escribir: CohortesApiTest#creaYCalculaCohorteNueva -->
- **CUANDO** un ANALISTA crea «Diabetes con insulina» = CIE-10 E10–E14 Y ATC A10A, en la ventana 2024-2025
- **ENTONCES** la cohorte queda guardada como versión 1, se calcula sobre el corte vigente sin esperar otro cargue y su tarjeta muestra el conteo agregado y el criterio

#### Scenario: Edición de criterios
<!-- test: por escribir: CohortesApiTest#editarCreaVersion -->
- **CUANDO** se cambia el umbral de poliformulados de 5 a 6 principios activos
- **ENTONCES** nace la versión 2 con su propia serie; la versión 1 y los reportes generados con ella siguen reproducibles; la bitácora registra `analitica_cohorte_version` con antes y después

---

### Requirement: Las diez cohortes del Anexo 4 y las de 11.2.4 vienen predefinidas
<!-- id: AnaliticaCohortes.PREDEFINIDAS -->
<!-- entidades: Cohorte -->
<!-- impuesto_en: A4 L684–706 §11.1 e (alto costo, polimedicados, huérfanas, VIH, renal, biológicos, control especial, estrecho margen, GRC, GCF) | A4 L787 11.2.4 (poliformulados, pluripatológicos, por diagnóstico, medicamento y principio activo) | A4 L275 | A4 L304 -->
<!-- decision: diseño 2026-10-22 -->

El sistema DEBE traer predefinidas (marcadas `predefinida`, editables en sus parámetros, no eliminables) estas cohortes con criterio por defecto:

| Código | Criterio por defecto (parametrizable) |
|---|---|
| `alto_costo` | gasto del paciente en 12 meses móviles ≥ umbral; umbral = percentil 99 del gasto por paciente del corte o valor absoluto (modo parametrizable); la tarjeta muestra el valor en pesos resultante |
| `polimedicados` | ≥ 5 principios activos distintos dispensados en cada mes durante ≥ 6 meses consecutivos (A4 L275: «cinco o más medicamentos durante un período igual o superior a seis meses») |
| `poliformulados` | ≥ 5 principios activos distintos dispensados en un mismo mes |
| `pluripatologicos` | ≥ 2 diagnósticos crónicos distintos (lista CIE-10 de crónicos) en 12 meses |
| `huerfanas` | CIE-10 de la lista de enfermedades huérfanas cargada como referencia |
| `vih` | CIE-10 B20–B24 o Z21, o ATC J05AE, J05AF, J05AG, J05AR |
| `renal` | CIE-10 N18, N19, Z49 o Z99.2 |
| `biologicos` | principio activo en la lista de biológicos (por defecto ATC L04AB, L04AC, L01F) |
| `control_especial` | medicamento en la referencia de control especial (V61) |
| `estrecho_margen` | principio activo en la referencia de estrecho margen (V61) |
| `gcf` | partición: grupo ATC nivel 2 con mayor gasto del paciente en el periodo |
| `grc` | partición por n.º de crónicos y alto costo: GRC1 sin crónicos; GRC2 un crónico; GRC3 dos o tres; GRC4 cuatro o más, o alto costo |

<!-- duda: 1 (umbral de alto costo) | 2 (poliformulado) | 4 (listas de huérfanas, biológicos, crónicos) -->

#### Scenario: Polimedicado con cinco meses
<!-- test: por escribir: AnaliticaCohortesTest#polimedicadoExigeSeisMeses -->
- **CUANDO** un paciente recibe 6 principios activos distintos en cada mes de enero a mayo y 3 en junio
- **ENTONCES** es poliformulado en cada uno de esos 5 meses pero NO es polimedicado

#### Scenario: Cambio de marca no suma medicamentos
<!-- test: por escribir: AnaliticaCohortesTest#mismoPrincipioDosCumCuentaUno -->
- **CUANDO** en un mes el paciente recibe losartán de dos CUM distintos y otros 3 principios activos
- **ENTONCES** cuenta 4 principios activos distintos (no 5)

---

### Requirement: GCF y GRC con metodología propia documentada
<!-- id: AnaliticaCohortes.gcf, AnaliticaCohortes.grc -->
<!-- entidades: Cohorte -->
<!-- impuesto_en: RESP L2245–2249 («la construcción de las agrupaciones y la operacionalización del modelo [...] hacen parte de la solución técnica de cada [oferente]») | RESP L5361–5367 (documentación de metodología, variables, parámetros y supuestos) | A5 L962 14.6 -->
<!-- decision: diseño 2026-10-22 -->

GCF y GRC DEBEN ser particiones: cada paciente del periodo pertenece a exactamente un grupo (o a «sin gasto» / «sin diagnóstico crónico»). La pantalla DEBE mostrar, junto a los conteos, la regla de asignación y un enlace al documento de metodología (variables, parámetros, supuestos, versión), entregable a la DIGSA.

#### Scenario: Empate en GCF
<!-- test: por escribir: AnaliticaCohortesTest#gcfEmpateDeterminista -->
- **CUANDO** el gasto de un paciente se reparte por igual entre C09 y A10
- **ENTONCES** se asigna al código ATC2 menor en orden alfabético (A10), siempre igual en cada recálculo

#### Scenario: Suma de la partición
<!-- test: M8-6; por escribir: CohortesApiTest#gcfSumaElTotal -->
- **CUANDO** se consulta GCF a nivel nacional para un periodo
- **ENTONCES** la suma de pacientes de todos los grupos es igual al total de pacientes distintos del periodo

---

### Requirement: Recálculo al aplicar cada cargue y bajo demanda
<!-- id: ServicioCohortes.recalcular -->
<!-- entidades: CohorteVersion, CohorteMiembro, CohorteSerie, Cargue -->
<!-- impuesto_en: RESP L5145 («la actualice conforme ingresa nueva información») | A4 L787 (periodicidad mensual) -->
<!-- decision: diseño 2026-10-22 -->

Al quedar «aplicado» un cargue, el sistema DEBE recalcular todas las versiones vigentes de cohortes activas sobre el nuevo corte (proceso diferido con el patrón de `exportacion/`: un hilo, advisory lock, estado y progreso consultables), y DEBE permitir recalcular una cohorte bajo demanda. El cálculo es determinista: mismo corte + misma versión = mismos miembros y misma serie. La consulta de un corte anterior se recalcula al vuelo con los hechos de los cargues ≤ ese corte.

#### Scenario: Cargue nuevo
<!-- test: por escribir: CohortesApiTest#cargueActualizaSerie -->
- **CUANDO** se aplica el cargue de noviembre
- **ENTONCES** la serie de cada cohorte gana el punto de noviembre, la ejecución queda en la bitácora (`analitica_cohorte_recalculo`) con duración y versiones, y las tarjetas muestran el corte nuevo
---

### Requirement: Serie en el tiempo, comparación y composición (P5)
<!-- id: ServicioCohortes.serie, ServicioCohortes.composicion -->
<!-- entidades: CohorteSerie -->
<!-- impuesto_en: RESP L5143–5145 (seguimiento en el tiempo, caracterización agregada del consumo y gasto, comparación entre periodos y niveles) | PLIEGO L2430 P5 (agrupado por nivel ATC, principio activo, medicamento normalizado, Fuerza y ESM, exportación) | A4 L787 -->
<!-- decision: diseño 2026-10-22 -->

Para cada cohorte el sistema DEBE dar: (a) serie mensual de pacientes, ingresos, salidas, dispensaciones, unidades y gasto; (b) comparación entre dos periodos (variación absoluta y %) y entre niveles (nacional, Fuerza, DISAN, ESM); (c) composición del gasto y de los pacientes por nivel ATC, principio activo, medicamento normalizado, CIE-10, Fuerza y ESM, con Top 20; (d) poliformulados y alto costo **dentro** de la cohorte; (e) alertas asociadas (enlace a la consola filtrada por la cohorte). La cohorte DEBE estar disponible como filtro en los tableros 1–12. Serie, comparación y composición DEBEN descargarse en Excel, CSV y PDF (P5: «Exportación de la información») con los metadatos de 14.8 más el criterio y la versión, solo con cifras agregadas y por el mismo mecanismo de los tableros.

#### Scenario: Cohorte por CIE-10 agrupada por ATC
<!-- test: M8-6; por escribir: CohortesApiTest#composicionPorAtcCuadra -->
- **CUANDO** se consulta la composición de «renal» por ATC nivel 3 en 2025
- **ENTONCES** la suma del gasto de los grupos es igual al gasto total de la cohorte en 2025 y coincide con el tablero 3 filtrado por esa cohorte

#### Scenario: Descarga
<!-- test: por escribir: CohortesApiTest#exportaSinSeudonimos -->
- **CUANDO** se descarga la composición de «biológicos» en CSV
- **ENTONCES** el encabezado trae criterio y versión y no hay ninguna columna de paciente ni seudónimo

---

### Requirement: Tarjetas «Cohortes (conteos agregados)» con el criterio visible
<!-- id: PaginaAnalitica.cohortes -->
<!-- entidades: Cohorte -->
<!-- impuesto_en: A5 L962 14.6 («los criterios de cohorte son consultables, con cifras trazables al insumo») | caso M8-6 («Siempre agregados y con el criterio visible») | mockup #analitica tarjeta «Cohortes (conteos agregados)» -->
<!-- decision: diseño 2026-10-22 -->

El tablero 10 («Cohortes y riesgo») DEBE mostrar una tarjeta por cohorte con el conteo de pacientes distintos y, **en la misma tarjeta**, el criterio en texto generado desde la definición (p. ej. «≥ 5 principios activos distintos en ≥ 6 meses consecutivos · v1»), el periodo, el corte y el n.º de registros que la sustentan; más las particiones GCF y GRC. Las seis del mockup (alto costo, polimedicados, renal, VIH, biológicos, huérfanas) van primero; las demás debajo.

#### Scenario: Criterio a la vista
<!-- test: M8-6; por escribir: e2e cohortes.spec.ts -->
- **CUANDO** el comité abre «Cohortes y riesgo»
- **ENTONCES** cada tarjeta muestra número, criterio, versión y corte, sin abrir otra pantalla

---

### Requirement: Motor de alertas tempranas determinista
<!-- id: MotorAlertasAnaliticas.ejecutar -->
<!-- entidades: AlertaAnalitica, EjecucionMotor, ParametroAlerta -->
<!-- impuesto_en: A4 L650–682 §11.1 d («Generación automática de alertas») | RESP L5647 (i) «automatizada [...] sin intervención manual» | RESP L2217 (críticas en tiempo real o cuasi real) | A5 L962 14.7 -->
<!-- decision: diseño 2026-10-22 -->

El motor DEBE correr al aplicar cada cargue (después del recálculo de cohortes) y bajo demanda, evaluar cada tipo activo con su versión de parámetros en los niveles configurados (nacional, Fuerza, ESM) y guardar cada alerta con una llave natural `(tipo, nivel, dimensión, valor, periodo)`. Reejecutar sobre el mismo corte NO DEBE duplicar: actualiza el valor observado de la alerta abierta y anota el corte de su última evaluación. Las reglas son puras (`reglas/AnaliticaAlertas`); la IA no decide si hay alerta. Cada ejecución queda en `ana_alertas_ejecuciones` y la bitácora (`analitica_alerta_generada`, 4.7.4 «alertas generadas»).

#### Scenario: Reejecución
<!-- test: por escribir: AlertasApiTest#reejecutarNoDuplica -->
- **CUANDO** se ejecuta el motor dos veces sobre el mismo corte
- **ENTONCES** el número de alertas es el mismo y la ejecución 2 informa 0 nuevas

#### Scenario: Un tipo falla
<!-- test: por escribir: AlertasApiTest#tipoConErrorNoTumbaLosDemas -->
- **CUANDO** la evaluación de un tipo lanza un error
- **ENTONCES** los demás tipos se evalúan, la ejecución queda «con fallos» con el tipo y el error, y el cargue sigue «aplicado»

---

### Requirement: Tipos de alerta y regla por defecto
<!-- id: AnaliticaAlertas.TIPOS -->
<!-- entidades: AlertaAnalitica, ParametroAlerta -->
<!-- impuesto_en: A4 L650–682 (10 tipos) | A5 L627–647 tablero 11 (formulaciones inusuales, concentraciones o presentaciones inconsistentes, severidad) | PLIEGO L2428 P4 | mockup «Alertas de comportamientos atípicos» -->
<!-- decision: diseño 2026-10-22 -->

El motor DEBE implementar estos tipos (umbrales, ventanas, niveles y severidades parametrizables):

| Tipo | Regla por defecto | Unidad de la alerta |
|---|---|---|
| `incremento_gasto` | gasto del mes vs. media de los 3 meses previos: variación ≥ 30 % y desviación robusta (mediana/MAD de ≥ 6 meses) ≥ 3; gasto base mínimo parametrizable | dimensión (ATC 1–3, principio activo, ESM, Fuerza) × mes |
| `incremento_consumo` | igual sobre unidades dispensadas | ídem |
| `alto_costo` | ingresos del mes a la cohorte `alto_costo` ≥ N | ESM o Fuerza × mes |
| `polimedicados` | ingresos del mes a `polimedicados` ≥ N | ESM o Fuerza × mes |
| `duplicidad_terapeutica` | mismo paciente con dos principios activos distintos del mismo ATC nivel 4 dispensados con cobertura superpuesta (días de tratamiento si el insumo los trae; si no, ventana de 30 días); exclusiones parametrizables | ATC4 × mes (pacientes y ESM afectados) |
| `interaccion` | par de principios activos de la tabla de interacciones con cobertura superpuesta | par × mes |
| `fuera_indicacion` | dispensación cuyo CIE-10 no está entre los pares ATC–CIE-10 admitidos | ATC × mes |
| `marca_estrecho_margen` | cambio de CUM (titular) entre dispensaciones consecutivas del mismo paciente y principio activo de estrecho margen | principio activo × ESM × mes |
| `desabastecimiento` | caída de dispensación de un medicamento vital ≥ 50 % frente a su media de 3 meses (aproximación; ver duda 7) | medicamento × ESM × mes |
| `concentracion_prescripciones` | un profesional concentra ≥ 50 % de las fórmulas de control especial o alto costo de un ESM con ≥ 20 fórmulas en el mes | ESM × mes |
| `formulacion_inusual` | cantidad por dispensación > mediana + k·MAD del mismo CUM (k = 5) | CUM × mes |
| `presentacion_inconsistente` | registro homologado con estado «diferencia en concentración, forma o presentación» | medicamento × mes |

#### Scenario: Incremento del mockup
<!-- test: M8-7; por escribir: AnaliticaAlertasTest#incrementoConsumoL04 -->
- **CUANDO** las unidades de ATC L04 del Ejército pasan de una media de 1000 (jun–ago) a 1420 en septiembre, con historia estable
- **ENTONCES** sale `incremento_consumo` · Fuerza EJC · ATC L04 · sep 2026 · observado 42,00 % · umbral 30,00 % · severidad media

#### Scenario: Duplicidad terapéutica
<!-- test: M8-7; por escribir: AnaliticaAlertasTest#duplicidadAnticoagulantes -->
- **CUANDO** 38 pacientes en 6 ESM reciben warfarina y apixabán (ATC4 B01AA y B01AF son distintos) y otros 12 reciben apixabán y rivaroxabán (B01AF ambos) en 20 días
- **ENTONCES** sale una alerta B01AF con 12 pacientes; la combinación B01AA + B01AF solo sale si el par está en la tabla de interacciones

<!-- incierto: el mockup dice «dos anticoagulantes orales» (38 pacientes). Con la regla ATC4 eso solo se detecta si los dos son del mismo ATC4 o si el par está en la tabla de interacciones. Si el comité entiende «duplicidad» por grupo terapéutico (ATC3 B01A), el nivel ATC de la regla es un parámetro: se recomienda ATC4 por defecto y mostrar el nivel en el contexto. -->

---

### Requirement: Contexto obligatorio y análisis técnico de cada alerta
<!-- id: AnaliticaAlertas.contexto, AnaliticaAlertas.analisis -->
<!-- entidades: AlertaAnalitica -->
<!-- impuesto_en: A5 L948 14.7 («con su contexto (umbral, periodo, dimensión y severidad)») | PLIEGO L2429 P4 («Cada alerta generada deberá presentar su respectivo análisis técnico, evidenciando la causa de la alerta, la valoración del riesgo o impacto identificado, así como las acciones y recomendaciones») | caso M8-7 -->
<!-- decision: diseño 2026-10-22 -->

Toda alerta DEBE guardar y mostrar: tipo, regla y versión de parámetros, severidad (alta, media, baja), umbral aplicado, valor observado, valor de referencia (línea base), unidad, periodo (desde–hasta), nivel, dimensión y valor, n.º de registros, n.º de pacientes (distintos, agregado), gasto involucrado, corte y fecha de generación; y su **análisis técnico**: causa (texto armado por plantilla con las cifras, p. ej. «el aumento es por volumen: el valor unitario varió 0,40 %»), valoración del riesgo o impacto (gasto, pacientes y severidad) y recomendación (plantilla por tipo, parametrizable). La severidad DEBE ser la banda más alta alcanzada entre tres umbrales ordenados por tipo (A5 tablero 11: «alertas clasificadas por severidad»); por defecto: incrementos 30 / 60 / 100 %; duplicidad, interacción y marca ≥ 1 / 10 / 30 pacientes; presentación inconsistente baja, media desde 100 registros. La prioridad DEBE ser severidad y luego gasto involucrado. Una alerta sin alguno de estos campos NO DEBE guardarse (NOT NULL en `ana_alertas`).

#### Scenario: Banda alta
<!-- test: por escribir: AnaliticaAlertasTest#bandas -->
- **CUANDO** el incremento de gasto es 120 %
- **ENTONCES** la severidad es alta y el umbral mostrado es 100,00 %

#### Scenario: Consola con contexto
<!-- test: M8-7; por escribir: AlertasApiTest#contextoCompleto -->
- **CUANDO** se abre una alerta en la consola
- **ENTONCES** se ven umbral, periodo, dimensión y severidad como columnas, y causa, valoración y recomendación en el detalle

---

### Requirement: Alerta válida y ausencia como falla
<!-- id: AnaliticaAlertas (criterios RESP L5647) -->
<!-- entidades: AlertaAnalitica -->
<!-- impuesto_en: RESP L5647 (TAS P3: (i) automatizada, (ii) presente en el insumo, (iii) trazable a registros, reglas o parámetros, (iv) explicable y consistente con fuentes oficiales, (v) clasificación y agregación exigidas) | RESP L5649 («el resultado de la herramienta se contrasta con los datos cargados y con los resultados esperados definidos por la Entidad»: un caso esperado sin alerta no cumple; es lectura, no texto literal) | RESP L5173 -->
<!-- decision: diseño 2026-10-22 -->

Cada alerta DEBE cumplir los cinco criterios: nace del motor; su lista de registros de origen no está vacía y se recorre desde la consola (14.9); guarda la regla, la versión y los parámetros; su causa cita la fuente de referencia usada (con versión) cuando aplica; y se presenta agregada con severidad. Un tipo que no se puede evaluar (falta su fuente o su campo) NO DEBE callar: la consola y el tablero 11 DEBEN mostrarlo como «no evaluado: <motivo>».

#### Scenario: Sin tabla de interacciones
<!-- test: por escribir: AlertasApiTest#tipoSinFuenteSeInforma -->
- **CUANDO** el motor corre sin pares de interacción cargados
- **ENTONCES** la ejecución registra `interaccion` = «no evaluado: sin fuente de interacciones cargada» y la consola lo muestra en el resumen por tipo

---

### Requirement: Errores sembrados del generador detectados
<!-- id: GeneradorDispensacion.sembrados, AlertasSembradasIT -->
<!-- entidades: AlertaAnalitica -->
<!-- impuesto_en: caso M8-7 («Detecta los errores sembrados») | hoja de ruta, Preparación 12 («con errores sembrados») | RESP L5647–5649 (contraste con los resultados esperados) -->
<!-- decision: diseño 2026-10-22 -->

El generador sintético de 2 M filas DEBE sembrar, con semilla fija, al menos un caso conocido por tipo evaluable (incremento de gasto, incremento de consumo, alto costo, polimedicados, duplicidad, marca de estrecho margen, formulación inusual, presentación inconsistente, concentración de prescripciones cuando el archivo trae profesional, y pares de interacción y fuera de indicación de ejemplo marcados como ficticios) y escribir el archivo de alertas esperadas (tipo, nivel, dimensión, valor, periodo, pacientes). La prueba de integración DEBE fallar si falta una alerta esperada o si su contexto no coincide.

#### Scenario: Cuadre de alertas sembradas
<!-- test: M8-7; por escribir: AlertasSembradasIT#todasLasEsperadas -->
- **CUANDO** se carga el archivo generado y corre el motor
- **ENTONCES** cada alerta del archivo de esperadas existe con su tipo, dimensión, periodo y pacientes; el informe lista las faltantes (debe ser vacío)

#### Scenario: Serie de control sin siembra
<!-- test: por escribir: AlertasSembradasIT#sinFalsosPositivosEnControl -->
- **CUANDO** se evalúa el conjunto de control del generador (sin casos sembrados)
- **ENTONCES** no sale ninguna alerta de severidad media o alta

---

### Requirement: Validación humana de la alerta
<!-- id: ServicioAlertasAnaliticas.cambiarEstado -->
<!-- entidades: AlertaAnalitica, AlertaHistorial -->
<!-- impuesto_en: RESP L5175 («cualquier alerta [...] deberá ser objeto de validación por parte del equipo interdisciplinario de la interventoría y de las instancias competentes de la Entidad») | RESP L2241 (las alertas son de apoyo; las decisiones clínicas son de los profesionales) -->
<!-- decision: diseño 2026-10-22 -->

Los estados DEBEN ser `nueva` → `en_revision` → `validada` | `descartada` (y `descartada` → `en_revision` para reabrir). Validar o descartar exige nota (≥ 10 caracteres) y lo hacen ADMINISTRADOR, COORDINADOR o ANALISTA. Cada cambio queda en `ana_alertas_historial` y en la bitácora (`analitica_alerta_estado`). Solo una alerta `validada` puede citarse como soporte de un hallazgo; la generación nunca espera la validación.

#### Scenario: Descartar sin nota
<!-- test: por escribir: AlertasApiTest#descartarExigeNota -->
- **CUANDO** un ANALISTA descarta una alerta sin nota
- **ENTONCES** 422 «Escriba el motivo (mínimo 10 caracteres)» y el estado no cambia

#### Scenario: Perfil sin permiso
<!-- test: por escribir: AlertasApiTest#digsaNoValida -->
- **CUANDO** un usuario DIGSA intenta validar
- **ENTONCES** 403
<!-- duda: 5 (validación por la Entidad) -->

---

### Requirement: Consola de alertas solo para perfiles autorizados
<!-- id: ControladorAlertasAnaliticas.listar -->
<!-- entidades: AlertaAnalitica -->
<!-- impuesto_en: A5 L962 14.7 («son consultables únicamente por los perfiles autorizados») | A5 L428 (ESM solo lo suyo) -->
<!-- decision: diseño 2026-10-22 -->

La consola (`Pantallas.ANALITICA_ALERTAS`) DEBE abrirse solo para ADMINISTRADOR, COORDINADOR, ANALISTA, DIGSA (nacional) y SANIDAD (solo alertas de nivel Fuerza o ESM de su Fuerza). El perfil ESM no la ve (403) y en el tablero 11 solo ve las métricas agregadas de atípicos de su establecimiento, sin lista de alertas. Filtros: tipo, severidad, estado, periodo, nivel, Fuerza, ESM, cohorte; orden por prioridad; descarga con los metadatos de 14.8. Cada consulta queda en la bitácora.

#### Scenario: Sanidad de otra Fuerza
<!-- test: M8-2, M8-7; por escribir: AlertasApiTest#sanidadSoloSuFuerza -->
- **CUANDO** Sanidad ARC lista alertas
- **ENTONCES** no ve alertas nacionales ni del Ejército, y pedir por id una del Ejército da 404

#### Scenario: ESM
<!-- test: por escribir: AlertasApiTest#esmSinConsola -->
- **CUANDO** un usuario ESM llama `GET /api/analitica/alertas`
- **ENTONCES** 403 y la entrada no aparece en su menú
<!-- duda: 3 -->

---

### Requirement: Parametrización de alertas con traza
<!-- id: ServicioParametrosAlertas.guardar -->
<!-- entidades: ParametroAlerta, ParametroAlertaHistorial -->
<!-- impuesto_en: RESP L5671 (TAS P5: cambio desde la interfaz, traza de usuario, fecha, valor anterior y nuevo, reejecución sin afectar lo procesado) | A5 L743–765 4.7.4 | fuentes_analitica §15.7 (umbrales y severidad los define el oferente) -->
<!-- decision: diseño 2026-10-22 -->

ADMINISTRADOR y ANALISTA DEBEN poder activar o desactivar un tipo y editar umbrales, bandas, ventana, niveles, exclusiones y plantilla de recomendación desde la interfaz; COORDINADOR los consulta. Cada guardado crea una versión nueva con usuario, fecha, antes y después (jsonb) y bitácora `analitica_parametro_alerta`. Las alertas ya generadas conservan la versión con que nacieron; «Reejecutar» aplica la nueva versión al corte vigente.

#### Scenario: Cambio en vivo
<!-- test: por escribir: AlertasApiTest#cambioUmbralYReejecucion -->
- **CUANDO** el comité pide bajar el umbral de `incremento_gasto` de 30 % a 20 % y se reejecuta
- **ENTONCES** aparecen las alertas nuevas con la versión 2; las de la versión 1 siguen con su umbral 30,00 %; el historial muestra 30 → 20 con usuario y hora

---

### Requirement: Notificación de las alertas de severidad alta por ServicioAlertas
<!-- id: MotorAlertasAnaliticas.notificar -->
<!-- entidades: AlertaAnalitica, AlertaEvento -->
<!-- impuesto_en: RESP L2259 (GMI P77 b: informativas y operativas por correo; críticas de continuidad, seguridad, disponibilidad o integridad por correo y SMS) | alertas/ServicioAlertas.java disparar() | V17 alertas_criticas CHECK (critica OR NOT sms) -->
<!-- decision: diseño 2026-10-22 -->

Al crearse una alerta analítica **nueva** de severidad alta, el motor DEBE llamar `ServicioAlertas.disparar("analitica_alerta_alta", clave, detalle)` dentro de su transacción (si la ejecución se revierte, no sale correo). El tipo se crea en V63 con `INSERT ... ON CONFLICT (tipo) DO NOTHING` como **operativo** (`critica = false`, `sms = false`, perfiles `{COORDINADOR,DIGSA,ANALISTA}`): solo correo, sin SMS, porque no es una alerta de continuidad o integridad. La `clave` es `ana:<id de la alerta analítica>` (≤ 120): como las operativas avisan una vez por `(tipo, clave)`, reejecutar el motor no repite el correo. El `detalle` (≤ 500) lleva tipo, dimensión, periodo, valor observado, umbral, n.º de pacientes y el número de la alerta; nunca seudónimos, documentos ni nombres. Subir de media a alta en una reevaluación también dispara (misma clave, un solo aviso).

#### Scenario: Alta nueva
<!-- test: por escribir: AlertasApiTest#altaEncolaCorreoSinSms -->
- **CUANDO** el motor crea una duplicidad de severidad alta
- **ENTONCES** `alertas_eventos` tiene un evento `analitica_alerta_alta` cerrado con `aviso_unico`, `correos_salida` un correo a los destinatarios del tipo y `sms_salida` nada

<!-- incierto: los destinatarios de `ServicioAlertas` se eligen por perfil sin alcance. Si el administrador agrega SANIDAD al tipo, un usuario de Sanidad recibiría el aviso de alertas de otras Fuerzas (sin datos personales). Se recomienda no agregar SANIDAD ni ESM al tipo y decirlo en la descripción del catálogo. -->

---

### Requirement: Tipos que dependen de fuentes por definir o de campos opcionales
<!-- id: AnaliticaAlertas.evaluable -->
<!-- entidades: ReferenciaInteraccion, ReferenciaIndicacion, ParametroAlerta -->
<!-- impuesto_en: RESP L2239–2241 (interacciones, indicaciones y estrecho margen: fuentes oficiales y referencias reconocidas, «corresponde al contratista disponer, a su costo, de las fuentes») | RESP L3917 (variables mínimas del insumo: sin profesional) | A4 L682 -->
<!-- decision: diseño 2026-10-22 -->

`interaccion`, `fuera_indicacion`, `marca_estrecho_margen`, `desabastecimiento` y las listas de huérfanas, biológicos y crónicos DEBEN leer tablas de referencia versionadas (fuente, versión, fecha, SHA-256) que se cargan por archivo desde la interfaz; `concentracion_prescripciones` DEBE evaluarse solo si el cargue trae `profesional_seudo`. Sin fuente o sin campo, el tipo queda «no evaluado» con el motivo (requisito «Alerta válida»). Los datos de ejemplo del generador van marcados como ficticios y no se cargan en producción.

#### Scenario: Insumo sin profesional
<!-- test: por escribir: AnaliticaAlertasTest#sinProfesionalNoEvaluado -->
- **CUANDO** el archivo de la prueba no trae columna de profesional
- **ENTONCES** `concentracion_prescripciones` = «no evaluado: el insumo no trae profesional»; los demás tipos corren
<!-- duda: 4 | 6 | 7 -->

---

### Requirement: Proyección mensual a 6 meses, también con poca historia
<!-- id: ServicioPrediccion.proyectar, AnaliticaPrediccion.historiaMinima -->
<!-- entidades: Entrenamiento, Proyeccion -->
<!-- impuesto_en: A4 L708–726 §11.1 f («horizonte mínimo de proyección de seis (6) meses y comparación permanente entre valores proyectados y observados») | A4 L806 11.2.7 | A4 L865 entregable 6 | A5 L647 tablero 12 -->
<!-- decision: diseño 2026-10-22 -->

El tablero 12 DEBE proyectar, por mes y con horizonte parametrizable ≥ 6 (por defecto 6), gasto, dispensaciones y pacientes distintos, a nivel nacional, Fuerza y ESM (según el alcance), y por ATC (nivel elegido), principio activo, CIE-10, cohorte, GCF y GRC (Top N por gasto, N = 20). El mes del corte, si está incompleto, NO DEBE entrar al entrenamiento: se muestra como observado parcial. **Poca historia** (RESP L2001, L2227–2235: calibración con el histórico que entregue la DIGSA; RESP L299; A5 L948: insumo de un año): con menos de 6 meses completos NO DEBE proyectar y muestra «Historia insuficiente: n meses (mínimo 6)», registrando el entrenamiento como `historia_insuficiente`; con 6 a 23 meses no hay estacionalidad y la pantalla dice «sin componente estacional: menos de 24 meses»; con 6 a 8 meses el backtesting usa 1 mes en lugar de 3 y lo indica.

#### Scenario: Corte a mitad de mes
<!-- test: M8-11; por escribir: AnaliticaPrediccionTest#mesIncompletoFuera -->
- **CUANDO** el corte es 21/10/2026
- **ENTONCES** se entrena hasta septiembre y se proyecta de octubre 2026 a marzo 2027

#### Scenario: Prueba con un año
<!-- test: M8-11; por escribir: PrediccionApiTest#unAnioSinEstacionalidad -->
- **CUANDO** el insumo cubre 2024-2025 (12 meses) y ningún mes es parcial
- **ENTONCES** se proyectan 6 meses con Holt o regresión, el backtesting usa 3 meses y la nota de estacionalidad es visible

---

### Requirement: Métodos en Java puro, selección por backtesting e IC 95 %
<!-- id: AnaliticaPrediccion.entrenar -->
<!-- entidades: Entrenamiento -->
<!-- impuesto_en: A5 L647 tablero 12 («criterios estadísticos utilizados, intervalos de confianza») | A4 L806 («precisión del modelo») | RESP L5361–5367 (metodología reproducible) | A4 L883 (reproducción por terceros) -->
<!-- decision: diseño 2026-10-22 -->

`reglas/AnaliticaPrediccion` (sin librerías ni azar) DEBE evaluar: Holt-Winters aditivo con estacionalidad 12 si hay ≥ 24 meses, Holt con tendencia si hay ≥ 6, y regresión lineal como respaldo; los parámetros se ajustan por búsqueda en rejilla fija minimizando el error a un paso. Elige el de menor WMAPE en el backtesting de los últimos 3 meses (entrena sin ellos, proyecta y compara); empate → el más simple. Reentrena el elegido con toda la serie. IC 95 % = proyección ± 1,96 · σ · √h (σ = desviación de los residuos a un paso, h = meses adelante), con límite inferior en 0. Misma serie + misma versión del algoritmo = mismo resultado.

#### Scenario: Serie con tendencia
<!-- test: por escribir: AnaliticaPrediccionTest#tendenciaLineal -->
- **CUANDO** la serie es 100, 110, …, 210 (12 meses)
- **ENTONCES** se elige Holt o regresión con WMAPE ≈ 0, la proyección de h = 1 es 220 ± 1 y el IC es angosto

---

### Requirement: Metadatos, escenarios, observado frente a proyectado y bitácora de entrenamientos
<!-- id: PaginaAnalitica.predictivo, ServicioPrediccion.entrenar -->
<!-- entidades: Entrenamiento, Proyeccion -->
<!-- impuesto_en: A5 L647 tablero 12 («periodo histórico, fecha de entrenamiento y comparación entre valores proyectados y observados») | A4 L726 | A4 L806 («escenarios y precisión del modelo») | caso M8-11 | mockup «Proyección del gasto · 6 meses» -->
<!-- decision: diseño 2026-10-22 -->

La pantalla DEBE mostrar el gráfico (línea continua observada, discontinua proyectada, banda IC 95 %, marca del corte) con su tabla accesible, y la tabla de metadatos: método real y parámetros, métodos evaluados con su error, periodo histórico, fecha de entrenamiento, corte, MAPE y WMAPE del backtesting. Escenarios: base, inferior y superior (límites del IC) y ajustado por un % que escribe el usuario (no se guarda como entrenamiento). Comparación permanente: cada entrenamiento guarda su proyección; cuando llegan los meses reales, la pantalla muestra lo que proyectó el entrenamiento anterior frente a lo observado y su error real.

**Bitácora de entrenamientos** (A5 L743–765 4.7.4: «ejecuciones y actualizaciones de modelos predictivos»): cada entrenamiento (al aplicar un cargue que completa un mes, o bajo demanda por ADMINISTRADOR, COORDINADOR o ANALISTA) DEBE quedar en `ana_pred_entrenamientos` (serie, nivel, corte, origen, método, parámetros, evaluados, historia, errores, versión del algoritmo, SHA-256 de la serie, duración, estado) y en la bitácora (`analitica_prediccion_entrenamiento`); la pantalla los lista.

#### Scenario: Llega el mes proyectado
<!-- test: M8-11; por escribir: PrediccionApiTest#observadoVsProyectado -->
- **CUANDO** el entrenamiento de septiembre proyectó octubre en 7,9 mil M y el cargue de noviembre trae octubre completo con 8,1 mil M
- **ENTONCES** la tabla «proyectado vs observado» muestra octubre: 7,9 / 8,1 / error 2,47 % y la fecha del entrenamiento que lo proyectó

#### Scenario: Reentrenamiento manual
<!-- test: por escribir: PrediccionApiTest#entrenarRegistra -->
- **CUANDO** un ANALISTA pulsa «Reentrenar» en el gasto nacional
- **ENTONCES** aparece un entrenamiento con su usuario, y la bitácora tiene el evento con la serie y el método

---

### Invariant: Ninguna salida de cohortes, alertas o predicción identifica a un paciente
<!-- entidades: Cohorte, AlertaAnalitica, Proyeccion -->
<!-- impuesto_en: A5 L739 4.7.3 («deberán identificarse mediante resultados consolidados y agregados») | A5 L962 14.6 («nunca individualizada») | RESP L2209 | RESP L4551–4577 -->
<!-- verificado_por: por escribir: PrivacidadAnaliticaTest (respuestas JSON y descargas sin seudónimo, documento, nombre ni fecha de nacimiento) -->

Las respuestas y descargas de cohortes, consola, tablero 11 y predicción DEBEN tener solo cifras agregadas; el seudónimo vive en `ana_cohortes_miembros` y en los hechos y solo sale en la trazabilidad 14.9 para perfiles autorizados. Al correo de alertas y al modelo de IA nunca van seudónimos.

---

### Invariant: Los pacientes no se suman entre celdas
<!-- entidades: CohorteSerie -->
<!-- impuesto_en: diseño 2026-10-22 (modelo analítico: conteos exactos con count(distinct)) -->
<!-- verificado_por: por escribir: CohortesApiTest#nacionalNoEsSumaDeEsm -->

Todo conteo de pacientes de una cohorte o alerta en un nivel DEBE calcularse como pacientes distintos en ese nivel; la cifra nacional no es la suma de las de los ESM (un paciente atendido en dos ESM cuenta una vez).

---

### Invariant: Toda cifra de cohorte o alerta es trazable a registros de origen
<!-- entidades: CohorteVersion, AlertaAnalitica -->
<!-- impuesto_en: RESP L3989 | RESP L5173 («cualquier resultado que no pueda ser [...] vinculado a los registros [...] será considerado inconsistente») | RESP L5647 (iii) -->
<!-- verificado_por: por escribir: AlertasApiTest#registrosDeOrigenNoVacios -->

Cada alerta DEBE guardar su selección (jsonb con regla, versión, filtros y periodo) que reproduce la lista de registros de origen; cada cifra de cohorte, la versión y el corte. Una alerta cuya selección no devuelve registros es un error del motor.

---

### Invariant: Las alertas analíticas no van por SMS
<!-- entidades: AlertaCritica -->
<!-- impuesto_en: RESP L2259 | V17 CHECK (critica OR NOT sms) | ServicioCatalogoAlertas (la edición no cambia `critica`) -->
<!-- verificado_por: por escribir: AlertasApiTest#tipoAnaliticoOperativo -->

`analitica_alerta_alta` DEBE ser operativo (`critica = false`); la base impide `sms = true` en un tipo no crítico y el catálogo no permite cambiar `critica`.

---

### Invariant: Resultados reproducibles por corte y versión
<!-- entidades: CohorteVersion, ParametroAlerta, Entrenamiento -->
<!-- impuesto_en: A5 L723–731 (reproducible) | A4 L883 | RESP L5361–5367 -->
<!-- verificado_por: por escribir: CohortesApiTest#corteAnteriorReproducible, AnaliticaPrediccionTest#mismaEntradaMismaSalida -->

Mismo corte + misma versión de cohorte, de parámetros de alerta o del algoritmo + mismo alcance DEBEN dar las mismas cifras, alertas y proyecciones, hoy o después de nuevos cargues.

---

## Contradicciones registradas

1. **Mockup «SARIMA» frente al método real.** El mockup dice «Serie temporal con estacionalidad (SARIMA) + regresión por ATC» con histórico 01/2024–09/2026; con un año de insumo no hay estacionalidad estimable. La pantalla dice el método real elegido (diseño).
2. **Mockup de alertas sin umbral, periodo ni dimensión** (solo Severidad | Alerta | Alcance) frente a 14.7 y M8-7, que los exigen. Manda 14.7: columnas separadas.
3. **Mockup de cohortes sin criterio** (solo «≥ 5» en polimedicados) frente a 14.6 («criterios consultables») y M8-6. Manda 14.6.
4. **Umbrales sin definir.** Alto costo es cualitativo (A4 L304), poliformulado no está definido y AGS-49 no fija umbrales (RESP L701–707); polimedicado sí (A4 L275). Se parametrizan con valores por defecto documentados.
5. **«Severidad alta» no es «crítica».** RESP L2259 reserva el SMS a lo que afecta la continuidad, seguridad, disponibilidad o integridad de la información; RESP L2217 pide «alertas críticas en tiempo real o cuasi real». Las analíticas salen al aplicar cada cargue (cuasi real) y solo por correo.
6. **A4 §11.1 f «variación presupuestal» y A4 L468–486 «agotamientos y sobreinventarios»** frente a la exclusividad del módulo (A5 L552, L769) y a un insumo sin inventarios ni presupuesto. Se proyecta el gasto farmacéutico; la comparación con el presupuesto es del módulo de presupuesto; inventarios quedan fuera hasta que haya fuente.
7. **Predictivo a 6 meses con un año de datos** (fuentes_analitica §15.15): sin estacionalidad y con backtesting corto; se informa en pantalla.
8. **Generación automática (RESP L5647 i) y validación humana (RESP L5175).** No chocan: el motor genera sin intervención; la persona valida el uso de la alerta.

## Dudas

1. **Umbral de alto costo.** Recomendada: percentil 99 del gasto por paciente en 12 meses móviles, parametrizable a valor absoluto. Mientras tanto: ese valor por defecto, con el equivalente en pesos visible en la tarjeta.
2. **Umbral de poliformulado.** Recomendada: ≥ 5 principios activos distintos en el mes (paralelo a A4 L275). Mientras tanto: ese, parametrizable.
3. **ESM y la consola de alertas.** Recomendada: el ESM no ve la consola; ve métricas agregadas de atípicos de su establecimiento. Mientras tanto: así (403 en la consola).
4. **Fuentes de interacciones, indicaciones, huérfanas, biológicos, crónicos** (a cargo del contratista, RESP L2239–2241). Recomendada: licenciar una referencia técnica reconocida y cargar el listado oficial de huérfanas de MinSalud. Mientras tanto: tablas cargables por archivo, vacías en producción (tipos «no evaluado») y datos de ejemplo ficticios en pruebas.
5. **Validación por la Entidad** («instancias competentes», RESP L5175). Recomendada: DIGSA registra una observación sobre la alerta sin cambiar su estado. Mientras tanto: solo la interventoría valida; DIGSA consulta.
6. **Profesional en el insumo** (concentración de prescripciones, 11.1.8, 11.1.19, 11.1.20). Recomendada: mapear la columna si viene, seudonimizada. Mientras tanto: el tipo queda «no evaluado» sin ella.
7. **Riesgo de desabastecimiento.** Recomendada: aproximación por caída de dispensación de vitales (referencia V61) y, cuando existan, los pendientes del OPL de interoperabilidad. Mientras tanto: solo la aproximación, rotulada como tal.
8. **Supresión de celdas pequeñas en VIH y huérfanas por ESM.** Recomendada: k = 5 solo para esas cohortes a nivel ESM, desactivable. Mientras tanto: desactivada, como el resto del módulo, para que las cifras cuadren con el insumo.
9. **Escenarios del dashboard prospectivo** (11.2.7). Recomendada: base, inferior, superior y ajuste por %. Mientras tanto: esos.

## Datos y piezas a construir

**V63 (cohortes y alertas analíticas):**

| Tabla | Columnas principales |
|---|---|
| `ana_cohortes` | id, codigo único `^[a-z_]{3,40}$`, nombre, descripcion, predefinida, activa, creada, creada_por |
| `ana_cohortes_versiones` | id, cohorte_id, version, criterios jsonb, criterio_texto, vigente (único por cohorte), creada, creada_por; único (cohorte_id, version) |
| `ana_cohortes_miembros` | version_id, mes, paciente_seudo, grupo (GCF/GRC, nulo en las demás); PK (version_id, mes, paciente_seudo); solo el corte vigente |
| `ana_cohortes_serie` | version_id, corte_cargue_id, mes, nivel, codigo_nivel, pacientes, ingresos, salidas, dispensaciones, unidades, gasto (caché del corte vigente) |
| `ana_cohortes_ejecuciones` | id, origen (cargue/usuario), cargue_id, inicio, fin, estado, versiones, duracion_ms, error |
| `ana_alertas_parametros` | tipo PK, activo, version, niveles text[], ventana, umbrales jsonb (baja/media/alta), exclusiones jsonb, plantilla_recomendacion, actualizado, actualizado_por |
| `ana_alertas_parametros_historial` | id, tipo, version, antes jsonb, despues jsonb, usuario_id, fecha |
| `ana_alertas` | id, tipo, parametros_version, llave única, nivel, fuerza, punto_id, dimension, valor_dimension, cohorte_id, periodo_desde, periodo_hasta, severidad CHECK, umbral, valor_observado, valor_referencia, unidad, registros, pacientes, gasto, causa, valoracion, recomendacion, seleccion jsonb, estado CHECK, corte_cargue_id, ultima_evaluacion_cargue_id, generada, evento_alerta_id → alertas_eventos |
| `ana_alertas_historial` | id, alerta_id, de, a, nota, usuario_id, fecha |
| `ana_alertas_ejecuciones` | id, origen, cargue_id, usuario_id, inicio, fin, estado, nuevas, actualizadas, por_tipo jsonb (incluye «no evaluado: motivo») |
| `ana_ref_interacciones`, `ana_ref_indicaciones`, `ana_ref_cie10_listas` | pares o listas (huérfanas, crónicos, biológicos) con fuente, version, fecha_fuente, sha256, ficticio |

Más: `INSERT INTO alertas_criticas (tipo, nombre, descripcion, critica, sms, perfiles) VALUES ('analitica_alerta_alta', 'Alerta analítica de severidad alta', 'El motor de analítica generó una alerta de severidad alta (solo correo; sin datos personales)', false, false, '{COORDINADOR,DIGSA,ANALISTA}') ON CONFLICT (tipo) DO NOTHING`; filas de `ana_alertas_parametros` y de las cohortes predefinidas; `par_parametros` (`analitica_alto_costo_modo`, `analitica_alto_costo_percentil`, `analitica_poliformulado_minimo`); todas las tablas en `Exportacion.TABLAS` salvo `ana_cohortes_miembros` (en EXCLUIDAS, contiene seudónimos y es derivable); GRANT al `${rol_app}`.

**V64 (predicción):** `ana_pred_entrenamientos` (id, metrica, nivel, codigo_nivel, dimension, valor_dimension, corte_cargue_id, origen, usuario_id, metodo, parametros jsonb, evaluados jsonb, historia_desde, historia_hasta, meses, backtest_meses, mape, wmape, version_algoritmo, sha256_serie, estado CHECK (`ok`, `historia_insuficiente`, `error`), mensaje, entrenado, duracion_ms); `ana_pred_proyecciones` (entrenamiento_id, mes, valor, ic_inferior, ic_superior; PK (entrenamiento_id, mes)); `par_parametros` `analitica_pred_horizonte` (6, mínimo 6), `analitica_pred_backtest` (3), `analitica_pred_historia_minima` (6), `analitica_pred_top` (20).

**Reglas puras (backend/reglas, JUnit):** `AnaliticaCohortes` (criterios → predicado, polimedicado, poliformulado, alto costo por percentil, GCF, GRC, `criterioTexto`), `AnaliticaAlertas` (los 12 tipos sobre series y conteos ya agregados, bandas, línea base mediana/MAD, plantillas de causa, valoración y recomendación, llave natural), `AnaliticaPrediccion` (Holt, Holt-Winters aditivo, regresión, rejilla, backtesting, MAPE/WMAPE, IC). `Bitacora.TIPOS`: `analitica_cohorte_version`, `analitica_cohorte_recalculo`, `analitica_alerta_generada`, `analitica_alerta_estado`, `analitica_parametro_alerta`, `analitica_prediccion_entrenamiento`.

**Endpoints (`/api/analitica`, todos con `@Accion` y alcance en SQL):** `GET|POST cohortes`, `GET|PUT cohortes/{id}`, `POST cohortes/{id}/recalcular`, `GET cohortes/{id}/serie`, `GET cohortes/{id}/composicion?por=`, `GET cohortes/{id}/exportar?formato=`; `GET alertas`, `GET alertas/{id}`, `POST alertas/{id}/estado`, `GET alertas/{id}/registros` (14.9), `POST alertas/ejecutar`, `GET alertas/ejecuciones`, `GET|PUT alertas/parametros/{tipo}`, `GET alertas/parametros/{tipo}/historial`, `GET alertas/exportar`; `POST referencias/{lista}` (carga por archivo); `GET prediccion?metrica&nivel&dimension&valor`, `POST prediccion/entrenar`, `GET prediccion/entrenamientos`.

**Pantallas:** dentro de ANALITICA, los tableros 10 (tarjetas, GCF/GRC, gestor de cohortes con editor de criterios), 11 (atípicos agregados) y 12 (gráfico SVG accesible, metadatos, escenarios, proyectado vs observado, entrenamientos); nueva `Pantallas.ANALITICA_ALERTAS` (ruta `analitica/alertas`, grupo «Información», {ADMINISTRADOR, COORDINADOR, ANALISTA, DIGSA, SANIDAD}) con la consola y la pestaña de parámetros (edición solo ADMINISTRADOR y ANALISTA). <!-- decision: diseño 2026-10-22 (la pantalla aparte permite negar la consola al ESM sin negarle los tableros) -->

<!-- pendiente: contrato de la capa de ingesta (nombres finales de `ana_dispensaciones` y del corte) y de las referencias V61 (estrecho margen, control especial, vitales), que escriben las especificaciones hermanas; documento de metodología de GCF, GRC, alertas y predicción (entregable) -->
