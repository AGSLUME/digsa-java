# Especificación: presupuesto (módulo de presupuesto del contrato de medicamentos)

> Extraída por spec-miner el 2026-10-22. **Capacidad nueva: no existe en el PHP de referencia**, así que las fuentes son los anexos, el mockup y el código Java (rama `presupuesto`, commit b8f79b0).
> Fuentes: Anexo 5 (L243 ítem 1.2 nota; L359 2.7; L361 2.9; L362 2.10; L363 2.11; L377 2.12; L428 3.7; L532-536 §4.6; L552 y L769 §4.7.5); Anexo 2 (L73, L75, L489, L557, L737-741, L1124, L1126); Anexo No. 3 del OPL (L2521, L2641, L3354-3356 §27.2 satélites, L5236-5240 HOMIL intramural, L5654-5662 §38 soportes de facturación); Anexo 1 (L1364, L1372); Anexo 4 (L456, L482, L724); `RESPUESTAS OBSERVACIONES.md` (L1973-1977 obs. 61, L4641-4681 obs. 4 sobre los umbrales); `PROYECTO DE PLIEGO` (L510-530 vigencias); mockup `Mockup_Modulos_DIGSA.html` (L166, L192, L200, L209, L464-512 sección `presupuesto`, L528 reporte «Ejecución presupuestal»); protocolo de la prueba en sitio, casos M7-1, M7-2 y M7-3 (contractuales, no eliminatorios; texto entregado por el usuario, no está en los .md). Código: `reglas/Alcance.java`, `Perfil.java`, `Pantallas.java`, `Alertas.java`, `Exportacion.java`, `Bitacora.java`; `alertas/ServicioAlertas.java`; `plataforma/BandejaSalida.java`, `Reporte.java`, `Exportador.java`; `reportes/ServicioReportes.java`; migraciones V3, V7, V9, V16, V17; spec `maestros-carga-masiva` (patrón de simular y aplicar).
> **Mandan**, en este orden: el enfoque validado con el usuario el 2026-10-22 (marcado `<!-- decision: usuario 2026-10-22 … -->`), `docs/decisiones_2026-10-21.md`, `docs/decisiones_2026-10-20.md` y la fuente de verdad. Lo que no fija ninguno va como `<!-- incierto: … -->` con la propuesta, y en «Dudas».
> Relacionadas: catálogo y envío de alertas en `exportacion-alertas`; alcance por perfil en `perfiles-alcance`; formato de reportes y descargas en `riesgo-tablero` y `exportacion-alertas`; carga con vista previa en `maestros-carga-masiva`; bitácora con antes y después en `bitacora`.
> Estado en Java: **no hay nada del módulo**. Existen y se reutilizan: `ServicioAlertas.disparar(tipo, clave, detalle)` (operativa = un aviso único por `(tipo, clave)`, destinatarios = usuarios activos de los perfiles del catálogo, **sin filtro por fuerza**, tope de 200), `BandejaSalida.encolar(...)`, `Exportador` (xlsx, csv «;» con coma decimal, txt, pdf), `Reporte`, `Almacen`, `par_fuerzas.correos` (buzón de la DISAN o JEFSA) y `Fechas.BOGOTA`. `par_modalidades` es la modalidad **del punto** (Dispensación, Suministro, Consignación, Satélite), no la de facturación. `archivos.visita_id` es obligatorio: el soporte de una novedad necesita su propia tabla.

---

### Requirement: Vigencia presupuestal
<!-- id: (nuevo) ServicioPresupuesto.crearVigencia -->
<!-- entidades: PresupuestoVigencia -->
<!-- impuesto_en: sin equivalente (capacidad nueva) -->
<!-- decision: usuario 2026-10-22 punto 8 | Pliego L520-530 (vigencias futuras anuales) | RESPUESTAS L1975-1977 (obs. 61) -->

El presupuesto DEBE organizarse por **vigencia fiscal anual** (año, fecha de inicio y de fin dentro del año, estado abierta o cerrada), con la vista «Contrato» que suma todas las vigencias. Una vigencia puede empezar o terminar a mitad de año (la primera y la última del contrato del OPL). Una vigencia cerrada no admite movimientos ni ejecución.
<!-- incierto: el contrato del OPL es plurianual y con vigencias futuras (como el de la interventoría, pliego L520-530); las fechas reales del contrato del OPL no están en la fuente de verdad (RESPUESTAS L1975: «aspectos propios de la ejecución del contrato»). Propuesta: anual, porque los umbrales y la proyección mensual solo tienen sentido sobre un monto con fin conocido. Ver duda 1. -->

#### Scenario: Vigencia parcial
<!-- test: sin prueba (capacidad nueva) -->
- **CUANDO** el coordinador crea la vigencia 2027 del 2027-03-01 al 2027-12-31
- **ENTONCES** la vigencia tiene 10 meses (marzo a diciembre) y la proyección lineal reparte en 10 meses

#### Scenario: Vigencia cerrada
<!-- test: sin prueba -->
- **CUANDO** se registra una adición en la vigencia 2027 ya cerrada
- **ENTONCES** 422 «La vigencia 2027 está cerrada: no admite novedades ni ejecución.»

---

### Requirement: Modalidades de facturación (catálogo propio)
<!-- id: (nuevo) presupuesto_modalidades -->
<!-- entidades: PresupuestoModalidad -->
<!-- impuesto_en: sin equivalente -->
<!-- decision: usuario 2026-10-22 punto 1 | Anexo 5 L534 | Anexo 3 OPL L5658, L2521, L2641 | mockup L478, L503 -->

El presupuesto DEBE usar un catálogo propio de **modalidades de facturación**, editable por el coordinador (crear, renombrar, inactivar; nunca borrar una con líneas), distinto de `par_modalidades`. Carga inicial activa: Dispensación, Órdenes de requisición y Suministro (Anexo 5 §4.6 y mockup). Se precargan **inactivas** Consignación, Satélite e Intramural HOMIL (Anexo 3 OPL §38), para activarlas si la DIGSA las presupuesta aparte.

#### Scenario: Modalidad inactiva
<!-- test: sin prueba -->
- **CUANDO** una fila de carga trae la modalidad «Consignación» y está inactiva
- **ENTONCES** la fila se rechaza con «La modalidad Consignación no está activa en el presupuesto.»

---

### Requirement: Línea presupuestal Fuerza → ESM → modalidad
<!-- id: (nuevo) presupuesto_lineas -->
<!-- entidades: PresupuestoLinea, Punto, Fuerza -->
<!-- impuesto_en: sin equivalente -->
<!-- decision: usuario 2026-10-22 punto 1 | Anexo 2 L739 («por cada ESM y por Fuerza … cada una de las modalidades») | Anexo 2 L1124 | Anexo 5 L534 -->

La unidad del presupuesto DEBE ser la **línea** (vigencia, fuerza, ESM, modalidad de facturación), única. La fuerza de la línea DEBE ser la del ESM, salvo en los ESM con fuerza «NA» (requisito HOMIL), donde es obligatoria y una de las fuerzas reales. Solo los puntos activos de unidad ESM tienen líneas (no las farmacias alternas ni el nivel central). Los totales por Fuerza, por modalidad y nacional DEBEN ser la suma de las líneas, nunca un valor cargado aparte.

#### Scenario: Línea de un ESM del Ejército
<!-- test: sin prueba -->
- **CUANDO** se asignan $1.000.000.000,00 a ESM 07 Cali (EJC) en Dispensación, vigencia 2027
- **ENTONCES** existe la línea (2027, EJC, ESM 07, Dispensación) con asignado 1.000.000.000,00 y el total EJC y el nacional suben en lo mismo

#### Scenario: Fuerza distinta a la del ESM
<!-- test: sin prueba -->
- **CUANDO** una fila asigna ESM 07 Cali con fuerza ARC
- **ENTONCES** se rechaza: «La fuerza ARC no corresponde al ESM 07 Cali (EJC).»

#### Scenario: Farmacia alterna
<!-- test: sin prueba -->
- **CUANDO** una fila trae un punto de unidad FA
- **ENTONCES** se rechaza: «Solo los ESM tienen presupuesto.»

---

### Requirement: ESM satélite sin presupuesto propio
<!-- id: (nuevo) -->
<!-- entidades: Punto, PresupuestoLinea, PresupuestoEjecucion -->
<!-- impuesto_en: sin equivalente; V7 carga 51 ESM con modalidad «Satélite» y sin enlace a su centralizador -->
<!-- decision: Anexo 3 OPL L3356 («dependen presupuestalmente de un establecimiento centralizador modalidad dispensación») -->

Un ESM de modalidad Satélite NO DEBE tener líneas propias. Su ejecución DEBE imputarse a la línea de su ESM **centralizador** (nuevo `puntos.centralizador_id`), guardando el satélite de origen en la fila de ejecución para el detalle. Si el satélite no tiene centralizador registrado, la fila se rechaza.
<!-- incierto: nadie ha entregado el mapa satélite → centralizador (V7 no lo tiene). Propuesta: columna nueva editable en Puntos y en la carga masiva de puntos. Ver duda 6. -->

#### Scenario: Asignación a un satélite
<!-- test: sin prueba -->
- **CUANDO** una fila asigna presupuesto al satélite BITER02
- **ENTONCES** se rechaza: «BITER02 es satélite: su presupuesto está en su ESM centralizador.»

#### Scenario: Ejecución de un satélite
<!-- test: sin prueba -->
- **CUANDO** se carga facturado $5.000.000,00 de BITER02 en Dispensación y su centralizador es BAS02
- **ENTONCES** suma a la línea (EJC, BAS02, Dispensación) y el detalle de BAS02 muestra «de ellos BITER02: 5.000.000,00»

#### Scenario: Satélite sin centralizador
<!-- test: sin prueba -->
- **CUANDO** el satélite GBMAT no tiene centralizador
- **ENTONCES** la fila se rechaza: «GBMAT es satélite y no tiene ESM centralizador registrado.»

---

### Requirement: HOMIL intramural repartido por fuerza de adscripción
<!-- id: (nuevo) -->
<!-- entidades: Punto, PresupuestoLinea, PresupuestoEjecucion -->
<!-- impuesto_en: V7 L1298 (HOMIL es ESM con fuerza «NA») -->
<!-- decision: Anexo 3 OPL L5236-5240 («discriminará la participación … por adscripción y afectará el presupuesto de acuerdo con la fuerza a la que pertenezca») -->

Para un ESM con fuerza «NA» (hoy solo HOMIL), las líneas y la ejecución DEBEN llevar la fuerza de adscripción (EJC, ARC o FAC) y cada fuerza ve y suma solo su parte. El reparto del costo por paciente lo hace la interventoría fuera del módulo y llega ya discriminado por fuerza en la carga o en el registro manual.

#### Scenario: HOMIL por fuerza
<!-- test: sin prueba -->
- **CUANDO** se cargan para HOMIL, Dispensación, marzo de 2027: EJC 40.000.000,00, ARC 25.000.000,00 y FAC 15.000.000,00
- **ENTONCES** cada valor afecta la línea de su fuerza; Sanidad ARC ve solo 25.000.000,00 y el total nacional de HOMIL es 80.000.000,00

#### Scenario: HOMIL sin fuerza
<!-- test: sin prueba -->
- **CUANDO** una fila de HOMIL trae fuerza vacía o «NA»
- **ENTONCES** se rechaza: «HOMIL exige la fuerza de adscripción (EJC, ARC o FAC).»

---

### Requirement: Asignación inicial y carga masiva del presupuesto (M7-1)
<!-- id: (nuevo) ServicioPresupuesto.cargarAsignacion -->
<!-- entidades: PresupuestoLote, PresupuestoMovimiento, PresupuestoLinea, PresupuestoSaldo -->
<!-- impuesto_en: patrón de spec maestros-carga-masiva («Revisión del archivo» y «Vista previa y confirmación») -->
<!-- decision: usuario 2026-10-22 puntos 2 y 3 | M7-1 | Anexo 5 L536 («la información … será entregada … por la DIGSA») -->

El presupuesto por ESM, Fuerza y modalidad DEBE poder cargarse con un archivo (CSV «;» UTF-8 o Excel; columnas vigencia, fuerza, ESM por sigla o nombre, modalidad, valor; máximo 5000 filas) en dos pasos: **simular** (valida todo, devuelve filas válidas, errores por fila con número y motivo, totales por fuerza y nacional, sin escribir) y **aplicar** (todo o nada: con un solo error no se aplica nada). Al aplicar se crea UN movimiento «asignación inicial» con soporte obligatorio y una fila por línea. El lote es idempotente por la huella SHA-256 del contenido: el mismo archivo aplicado dos veces responde «Este archivo ya se aplicó el <fecha> (lote <n>).» sin duplicar. También se puede asignar línea por línea en el formulario.
<!-- decision: usuario 2026-10-22 punto 3 — todo o nada (distinto de maestros, que aplica las válidas y salta las erradas). -->

#### Scenario: Simular
<!-- test: sin prueba -->
- **CUANDO** se simula un archivo de 120 filas con 2 errores (ESM inexistente, valor «-5»)
- **ENTONCES** 200 con 118 válidas, los 2 errores con su fila y motivo, el total nacional de las válidas, y nada escrito

#### Scenario: Aplicar con errores
<!-- test: sin prueba -->
- **CUANDO** se aplica ese mismo archivo
- **ENTONCES** 422 «La carga no se aplicó: 2 filas con errores.» y nada escrito

#### Scenario: Aplicar limpio (M7-1)
<!-- test: M7-1 -->
- **CUANDO** se aplican 118 filas válidas con soporte «Oficio DIGSA 123 del 2027-01-15»
- **ENTONCES** queda un movimiento «asignación inicial» con 118 líneas, los saldos de cada línea, la bitácora `presupuesto_carga_aplicada` con filas y total, y el tablero muestra los valores

#### Scenario: Línea ya asignada
<!-- test: sin prueba -->
- **CUANDO** una fila trae una línea que ya tiene asignación en la vigencia
- **ENTONCES** la fila se rechaza: «La línea ya tiene presupuesto: use una adición, disminución o reajuste.»

#### Scenario: Valores
<!-- test: sin prueba -->
- **CUANDO** una fila trae «1.250.000,5», otra «1250000.50» y otra «$ 1 250 000,505»
- **ENTONCES** las dos primeras valen 1250000,50; la tercera se rechaza por más de 2 decimales

---

### Requirement: Novedades presupuestales (M7-2)
<!-- id: (nuevo) ServicioPresupuesto.registrarNovedad -->
<!-- entidades: PresupuestoMovimiento, PresupuestoMovimientoLinea, PresupuestoSaldo, PresupuestoSoporte -->
<!-- impuesto_en: sin equivalente -->
<!-- decision: usuario 2026-10-22 punto 2 | Anexo 5 L536 («adiciones, disminuciones, reajustes, rebalanceos, recomposición presupuestal mediante formulario(s)») | Anexo 2 L739 (la recomposición la decide la DIGSA) | mockup L498-509 | M7-2 -->

El formulario «Novedad presupuestal» DEBE registrar estos tipos, cada uno con soporte DIGSA (texto obligatorio de 5 a 300 caracteres, p. ej. «Oficio 456 del 2027-05-02») y archivo opcional (PDF o imagen, con huella):

| Tipo | Líneas | Regla del valor |
|---|---|---|
| Adición | una o varias | cada valor > 0 |
| Disminución | una o varias | cada valor > 0 (resta) |
| Reajuste | una o varias, o un ámbito (Fuerza o nacional) con un porcentaje | valor con signo o porcentaje; el centavo que sobra del redondeo va a la línea de mayor valor |
| Rebalanceo | origen → destino (misma vigencia; puede cambiar ESM, modalidad o fuerza) | valor > 0; neto cero |
| Recomposición | varias líneas con valores con signo | suma exactamente 0,00 |

Cada movimiento DEBE guardar usuario, fecha y hora (Bogotá), y por línea afectada el asignado **antes y después** y el ejecutado en ese momento. Los saldos se actualizan en la misma transacción, bloqueando las filas de saldo en orden de id.
<!-- incierto: «reajuste» no está definido en la fuente de verdad (puede ser el reajuste de precios por IPC o un ajuste técnico). Propuesta: un ajuste con signo, que no exige neto cero y se distingue de la adición y la disminución por su soporte. Ver duda 9. -->

#### Scenario: Rebalanceo del mockup (M7-2)
<!-- test: M7-2; mockup L503-507 -->
- **CUANDO** se rebalancean $450.000.000,00 de ESM 11 Neiva a ESM 07 Cali, Dispensación, con el soporte «Oficio [número] del [fecha]»
- **ENTONCES** Neiva pasa de 900.000.000,00 a 450.000.000,00, Cali de 1.000.000.000,00 a 1.450.000.000,00, el total nacional no cambia, y el historial y los reportes muestran el movimiento con los dos «antes» y «después»

#### Scenario: Recomposición que no cuadra
<!-- test: sin prueba -->
- **CUANDO** una recomposición trae +300.000.000,00, −200.000.000,00 y −99.999.999,99
- **ENTONCES** 422 «La recomposición debe sumar 0,00 (suma 0,01).» y nada cambia

#### Scenario: Sin soporte
<!-- test: sin prueba -->
- **CUANDO** se envía una adición sin el texto del soporte
- **ENTONCES** 422 «El soporte de la DIGSA es obligatorio.»

#### Scenario: Dos novedades a la vez
<!-- test: sin prueba (prueba de concurrencia) -->
- **CUANDO** dos usuarios disminuyen la misma línea de 100.000.000,00 en 60.000.000,00 al mismo tiempo
- **ENTONCES** una se aplica (antes 100.000.000,00, después 40.000.000,00) y la otra ve el saldo actualizado y se rechaza por dejar el asignado negativo

---

### Requirement: Una novedad no deja el asignado por debajo de lo ejecutado
<!-- id: (nuevo) -->
<!-- entidades: PresupuestoSaldo -->
<!-- impuesto_en: sin equivalente -->
<!-- decision: propuesta (usuario 2026-10-22, «propón: se rechaza o se advierte») -->

Una disminución, un reajuste negativo, el origen de un rebalanceo o una línea negativa de una recomposición DEBEN rechazarse (422, listando las líneas) si dejan el asignado de una línea por debajo de su **ejecutado facturado**, o negativo. La adición y la ejecución nunca se bloquean.
<!-- incierto: propuesta de rechazo, no de advertencia, porque lo ejecutado ya es un compromiso del contrato y un asignado menor da % > 100 sin consumo nuevo. Ver duda 10. -->

#### Scenario: Disminución bajo lo ejecutado
<!-- test: sin prueba -->
- **CUANDO** una línea tiene asignado 500.000.000,00 y facturado 420.000.000,00, y se pide disminuir 100.000.000,00
- **ENTONCES** 422 «ESM 05 B/quilla · Suministro quedaría con 400.000.000,00, menos que lo ejecutado (420.000.000,00).»

---

### Requirement: Ejecución por un puerto con varias fuentes
<!-- id: (nuevo) PuertoEjecucion -->
<!-- entidades: PresupuestoEjecucion, PresupuestoLote -->
<!-- impuesto_en: sin equivalente -->
<!-- decision: usuario 2026-10-22 punto 3 | Anexo 2 L75 (valores reconocidos tras glosas y conciliación) | Anexo 2 L557 | Anexo 3 OPL L5662 (oficio con valor total por modalidad y Fuerza) | Anexo 5 L536 -->

La ejecución DEBE entrar por un puerto `PuertoEjecucion.registrarCorte(periodo, línea, facturado, reconocido, fuente, referencia)` con tres adaptadores: **carga de archivo** de la DIGSA o del OPL (mismo patrón simular/aplicar, todo o nada, idempotente por huella), **registro manual** en el formulario y, después, el **módulo de cuentas médicas**. Cada registro es por **periodo mensual** (primer día del mes), línea y fuente, con dos cifras: **facturado** (o comprometido) y **reconocido** (facturado menos glosas tras la conciliación; vacío mientras no se concilie). Un nuevo registro del mismo periodo y línea **reemplaza** al anterior como versión vigente, sin borrarlo (el historial muestra las versiones). Los saldos se recalculan en la misma transacción.
<!-- incierto: «reemplaza» (corte acumulado del mes) frente a «suma» (cada factura). Propuesta: reemplaza, porque la DIGSA y el oficio del §38 reportan el total del mes por modalidad y Fuerza; cuentas médicas publicará el total del mes. Ver duda 3. -->

#### Scenario: Carga de ejecución
<!-- test: sin prueba -->
- **CUANDO** se aplica un CSV de marzo de 2027 con ESM 07 Cali, Dispensación, facturado 260.000.000,00, reconocido vacío
- **ENTONCES** la línea queda con facturado acumulado 260.000.000,00 y reconocido 0,00 (sin conciliar), y la bitácora `presupuesto_ejecucion_carga` registra el lote

#### Scenario: Corrección del mes
<!-- test: sin prueba -->
- **CUANDO** después se registra a mano marzo de 2027 para la misma línea con facturado 250.000.000,00 y reconocido 245.000.000,00
- **ENTONCES** la versión vigente es la nueva, el acumulado baja a 250.000.000,00 y el historial conserva la de 260.000.000,00 con quién y cuándo

#### Scenario: Reconocido mayor que facturado
<!-- test: sin prueba -->
- **CUANDO** una fila trae facturado 10.000.000,00 y reconocido 12.000.000,00
- **ENTONCES** la fila se rechaza: «El reconocido (12.000.000,00) no puede superar el facturado (10.000.000,00).»

#### Scenario: Periodo fuera de la vigencia
<!-- test: sin prueba -->
- **CUANDO** una fila de la vigencia 2027 (desde marzo) trae periodo 2027-02
- **ENTONCES** la fila se rechaza: «El periodo 2027-02 está fuera de la vigencia 2027.»

#### Scenario: Mismo archivo dos veces
<!-- test: sin prueba -->
- **CUANDO** se aplica de nuevo el CSV de marzo ya aplicado
- **ENTONCES** 200 «Este archivo ya se aplicó el 2027-04-05 (lote 12).» y nada cambia

---

### Requirement: Ejecución por encima del 100 % y saldo negativo
<!-- id: (nuevo) -->
<!-- entidades: PresupuestoSaldo -->
<!-- impuesto_en: sin equivalente -->
<!-- decision: usuario 2026-10-22 («se permiten y alertan, no se bloquean») | Anexo 2 L739 («alertas que permitan identificar posibles excesos») -->

La ejecución DEBE aceptarse aunque supere el asignado: el saldo queda negativo, el % pasa de 100 y la pantalla y los reportes lo muestran en rojo («Exceso»). La notificación del 100 % sale una vez (requisito de umbrales).

#### Scenario: Exceso
<!-- test: sin prueba -->
- **CUANDO** una línea con asignado 200.000.000,00 acumula facturado 230.000.000,00
- **ENTONCES** % 115,00, saldo −30.000.000,00 en rojo, y la carga no se rechaza

---

### Requirement: Umbrales del 25, 50, 75 y 100 % con notificación única
<!-- id: (nuevo) ServicioPresupuesto.evaluarUmbrales -->
<!-- entidades: PresupuestoUmbral, AlertaEvento, PresupuestoNotificacion -->
<!-- impuesto_en: alertas/ServicioAlertas.java disparar() (operativa: un aviso por (tipo, clave)) -->
<!-- decision: usuario 2026-10-22 puntos 4 y 6 | Anexo 2 L741 | Anexo 5 L243 (nota 1.2) | RESPUESTAS L4673-4679 («ambos anexos … de manera complementaria … incluidos los cuatro umbrales») -->

Después de cada cambio de ejecución o de asignado, en la misma transacción, el sistema DEBE calcular por línea (ESM × modalidad) el porcentaje = ejecutado ÷ asignado vigente × 100 con la base del parámetro `presupuesto_base_umbral` (`facturado` por defecto, o `reconocido`). Cuando el ejecutado alcanza o pasa asignado × umbral ÷ 100 (comparación exacta, sin redondear) para 25, 50, 75 o 100 y ese umbral no está registrado para la línea, DEBE registrarlo en `presupuesto_umbrales` y disparar **una sola** alerta `presupuesto_umbral` por el umbral más alto recién alcanzado (los más bajos cruzados en el mismo cambio quedan registrados sin aviso propio). La clave de deduplicación es `pu:<vigencia>:<línea>:<umbral>`: cada umbral se notifica **una vez por vigencia y línea**, aunque una adición baje después el % y se vuelva a cruzar.
<!-- incierto: Anexo 2 L741 habla del % del «ESM»; aquí se mide por ESM y modalidad (Anexo 2 L739). Si la DIGSA lo quiere también por ESM total, se agrega la clave `pu:<vigencia>:esm:<punto>:<umbral>`. Ver dudas 2 y 4. -->

#### Scenario: Primer umbral
<!-- test: sin prueba -->
- **CUANDO** la línea ESM 07 Cali, Dispensación (asignado 1.000.000.000,00) pasa de 240.000.000,00 a 260.000.000,00 facturado
- **ENTONCES** se registra el umbral 25 (26,00 %) y sale una notificación «ESM 07 Cali · Dispensación alcanzó el 25 % del presupuesto (26,00 %)»

#### Scenario: Varios umbrales de una vez
<!-- test: sin prueba -->
- **CUANDO** una línea pasa de 10,00 % a 80,00 % en una carga
- **ENTONCES** quedan registrados 25, 50 y 75 y sale una sola notificación, la del 75 %

#### Scenario: Adición y nuevo cruce
<!-- test: sin prueba -->
- **CUANDO** una línea notificada al 75 % (asignado 100,00 → facturado 76,00) recibe una adición de 50,00 (50,67 %) y luego llega a 113,00 (75,33 %)
- **ENTONCES** no se notifica otra vez el 75 %; el tablero dice «75 % notificado» y muestra el % actual

#### Scenario: Línea sin asignado
<!-- test: sin prueba -->
- **CUANDO** una línea con asignado 0,00 recibe facturado 1.000.000,00
- **ENTONCES** se registran 25, 50, 75 y 100 y sale la notificación del 100 % («sin presupuesto asignado»)

#### Scenario: Base reconocido
<!-- test: sin prueba -->
- **CUANDO** `presupuesto_base_umbral = reconocido` y la línea tiene facturado 300,00 y reconocido 240,00 sobre 1.000,00
- **ENTONCES** el % para umbrales es 24,00 y no se notifica el 25 %

---

### Requirement: Notificación formal y electrónica a la DIGSA y a la Sanidad de la fuerza
<!-- id: (nuevo) ServicioPresupuesto.notificar -->
<!-- entidades: PresupuestoNotificacion, CorreoSalida, Fuerza -->
<!-- impuesto_en: plataforma/BandejaSalida.java encolar(); V3 par_fuerzas.correos; ServicioAlertas.destinatarios() (sin filtro por fuerza) -->
<!-- decision: usuario 2026-10-22 punto 6 | Anexo 2 L741 («notificación formal y electrónica a la DIGSA y a las Direcciones de Sanidad y Jefatura de Salud de la FAC») | memoria «canales según anexos» (formal y electrónica = correo; sin SMS) -->

Cada umbral nuevo y cada desviación DEBEN producir, en la misma transacción: (a) la alerta del catálogo (`presupuesto_umbral` o `presupuesto_desviacion`, operativas, solo correo, sin SMS), que llega a los perfiles del catálogo (DIGSA y Coordinador); y (b) una **notificación formal** con consecutivo `PRE-<año>-<0001>` encolada en la bandeja de salida a los buzones de `par_fuerzas.correos` de la fuerza de la línea (DISAN EJC, DISAN ARC o JEFSA FAC) y al buzón de la DIGSA (parámetro existente `correo_digsa`). El texto formal dice: consecutivo, fecha, vigencia, fuerza, ESM, modalidad, umbral, asignado, ejecutado, % y base usada, y «Generado por la interventoría · Suite DIGSA». Sin datos personales. Una desviación nacional va a la DIGSA y a las tres sanidades.
<!-- incierto: si las sanidades reciben por buzón institucional (par_fuerzas.correos) o por usuario con perfil Sanidad. ServicioAlertas no filtra por fuerza: poner SANIDAD en el catálogo mandaría la alerta de un ESM del Ejército a la Armada. Ver duda 5 (bloqueante). -->

#### Scenario: Notificación al Ejército
<!-- test: sin prueba -->
- **CUANDO** ESM 07 Cali (EJC) alcanza el 75 %
- **ENTONCES** sale PRE-2027-0007 a los buzones de DISAN EJC y de la DIGSA, el aviso a los usuarios DIGSA y Coordinador, y ninguno a la Armada ni a la FAC

#### Scenario: Fuerza sin buzón
<!-- test: sin prueba -->
- **CUANDO** `par_fuerzas.correos` de ARC está vacío
- **ENTONCES** la notificación se guarda con estado «sin destinatario de la fuerza», sale a la DIGSA y el tablero la marca para reenviar

---

### Requirement: Proyección de consumo mensual
<!-- id: (nuevo) ServicioPresupuesto.proyeccion -->
<!-- entidades: PresupuestoProyeccion -->
<!-- impuesto_en: sin equivalente -->
<!-- decision: usuario 2026-10-22 punto 5 | Anexo 5 L534 («proyección de consumo por cada ESM, FUERZA y a nivel nacional») | Anexo 4 L482 y L724 | mockup L192 («Proyección al corte: 58,00 %») -->

Cada línea DEBE tener una proyección mensual: **cargada** (archivo con vigencia, fuerza, ESM, modalidad, periodo y valor; simular/aplicar) o, si no la hay, **calculada** de forma lineal: asignado vigente ÷ meses de la vigencia, con el residuo de centavos en el último mes. La calculada no se guarda: sigue los cambios del asignado. El proyectado acumulado al corte (meses de la vigencia hasta el corte, inclusive) se agrega a Fuerza y nacional sumando líneas. Si la proyección cargada de una línea no suma su asignado, se advierte («La proyección suma X y el asignado es Y») sin rechazar.
<!-- incierto: el método no lo fija la fuente (RESPUESTAS L4643: «la Entidad no establecerá un algoritmo particular»). Propuesta: lineal ahora; perfil histórico del año anterior cuando haya datos (Anexo 4 L482, proyección con IA del módulo analítico). -->

#### Scenario: Proyección lineal
<!-- test: sin prueba -->
- **CUANDO** una línea tiene 1.000.000.000,00 en una vigencia de 12 meses y el corte es junio
- **ENTONCES** proyectado mensual 83.333.333,33 (diciembre 83.333.333,37) y acumulado a junio 499.999.999,98

#### Scenario: Proyección tras una adición
<!-- test: sin prueba -->
- **CUANDO** en julio se adicionan 200.000.000,00 a esa línea sin proyección cargada
- **ENTONCES** el mensual pasa a 100.000.000,00 y el acumulado a junio a 600.000.000,00

---

### Requirement: Alarma de desviación frente a la proyección en ESM, Fuerza y nacional (M7-3)
<!-- id: (nuevo) ServicioPresupuesto.evaluarDesviacion -->
<!-- entidades: PresupuestoProyeccion, PresupuestoSaldo, AlertaEvento -->
<!-- impuesto_en: ServicioAlertas.disparar() -->
<!-- decision: usuario 2026-10-22 punto 5 | Anexo 5 L534 («alarmas … cuando la ejecución presupuestal no se cumpla de acuerdo con la proyección») | M7-3 | mockup L473 («ESM sobre la proyección: 6 de 38») -->

Al aplicar ejecución de un periodo (corte = ese mes), el sistema DEBE comparar el ejecutado acumulado (base del umbral) con el proyectado acumulado al corte, en tres niveles: **ESM** (cada línea ESM × modalidad), **Fuerza** (suma de sus líneas) y **nacional**. Desviación % = (ejecutado − proyectado) ÷ proyectado × 100. Si pasa de `presupuesto_tolerancia_exceso` (10,00 por defecto) es **exceso**; si es menor que −`presupuesto_tolerancia_subejecucion` (20,00 por defecto) es **subejecución**. Cada caso dispara `presupuesto_desviacion` con clave `pd:<vigencia>:<corte>:<nivel>:<id>:<sentido>` (una vez por corte, nivel y sentido) y la notificación formal. Proyectado 0 con ejecutado > 0 es exceso.

#### Scenario: Simulación de exceso (M7-3)
<!-- test: M7-3 -->
- **CUANDO** con proyectado acumulado a marzo de 300.000.000,00 en ESM 07 Cali · Dispensación, se carga facturado acumulado de 360.000.000,00 (+20,00 %), y eso lleva al EJC a +12,50 % y al nacional a +10,40 %
- **ENTONCES** salen tres alarmas de exceso (ESM, Fuerza EJC, nacional), cada una con su notificación formal, y el tablero muestra «ESM sobre la proyección: 1»

#### Scenario: Dentro de la tolerancia
<!-- test: sin prueba -->
- **CUANDO** el ejecutado está +8,00 % sobre lo proyectado
- **ENTONCES** no hay alarma; la fila se ve «sobre la proyección» en ámbar

#### Scenario: Subejecución
<!-- test: sin prueba -->
- **CUANDO** a junio el ejecutado es 350.000.000,00 y lo proyectado 500.000.000,00 (−30,00 %)
- **ENTONCES** alarma de subejecución del ESM, una sola vez para el corte de junio

#### Scenario: Recarga del mismo corte
<!-- test: sin prueba -->
- **CUANDO** se corrige marzo y la desviación sigue en exceso
- **ENTONCES** no se repite la alarma de marzo; la del corte de abril sí puede salir

---

### Requirement: Tablero de presupuesto y ejecución
<!-- id: (nuevo) GET /api/presupuesto/tablero -->
<!-- entidades: PresupuestoSaldo, PresupuestoUmbral -->
<!-- impuesto_en: sin equivalente -->
<!-- decision: usuario 2026-10-22 punto 7 | mockup L464-496 y L192 | Anexo 5 L428 (3.7) -->

La pantalla «Presupuesto» DEBE mostrar, con filtros de vigencia (o «Contrato»), corte, Fuerza, ESM y modalidad (una, varias o todas) y siempre dentro del alcance: (a) cajas de presupuesto vigente (con el número de adiciones), ejecutado y %, saldo, y «ESM sobre la proyección: N de M»; (b) tabla Fuerza × modalidad con total ejecutado, % de ejecución, % proyectado al corte y el umbral más alto notificado; (c) «ESM con mayor ejecución»: barras con marcas en 25, 50, 75 y 100 % y semáforo (verde < 75, ámbar 75–99,99, rojo ≥ 100); (d) detalle por ESM y modalidad con asignado, facturado, reconocido, saldo, %, proyectado y desviación. Los valores van en pesos con 2 decimales, coma decimal y sin separador de miles (VCO P27), y los % con 2 decimales. El inicio muestra la caja «Ejecución presupuestal nacional» con la proyección al corte (mockup L192) a quien tenga la pantalla. Responsive a 360 px sin desplazamiento horizontal de la página.

#### Scenario: Cifras del mockup
<!-- test: sin prueba; mockup L471-483 -->
- **CUANDO** el asignado vigente es 98.500.000.000,00 y lo ejecutado 60.479.000.000,00 (EJC 35.542.000.000,00; ARC 13.124.000.000,00; FAC 11.813.000.000,00)
- **ENTONCES** ejecutado 61,40 %, saldo 38.021.000.000,00 y la suma de las fuerzas es el nacional

#### Scenario: Sanidad
<!-- test: sin prueba -->
- **CUANDO** Sanidad ARC abre el tablero
- **ENTONCES** ve solo las líneas de la ARC (incluida la parte ARC de HOMIL) y el total es el de la ARC, no el nacional

---

### Requirement: Historial de movimientos y de ejecución
<!-- id: (nuevo) GET /api/presupuesto/movimientos -->
<!-- entidades: PresupuestoMovimiento, PresupuestoMovimientoLinea, PresupuestoEjecucion -->
<!-- impuesto_en: sin equivalente -->
<!-- decision: usuario 2026-10-22 punto 2 | Anexo 5 L536 («dichas modificaciones … se deben evidenciar en los reportes») | Anexo 5 L765 (trazabilidad del dato hasta su origen) -->

El historial DEBE listar los movimientos (fecha y hora, tipo, soporte, archivo, usuario, líneas con valor, antes y después) y las versiones de ejecución (periodo, línea, facturado, reconocido, fuente, lote o «manual», usuario), filtrables por vigencia, tipo, Fuerza, ESM, modalidad y fechas, con paginación en el servidor. Desde una línea del tablero se llega a sus movimientos y ejecuciones.

#### Scenario: Trazar una línea
<!-- test: sin prueba -->
- **CUANDO** se abre el historial de ESM 07 Cali · Dispensación
- **ENTONCES** se ven la asignación inicial (0,00 → 1.000.000.000,00), el rebalanceo (1.000.000.000,00 → 1.450.000.000,00) y las versiones de ejecución mes a mes

---

### Requirement: Reportes de ejecución presupuestal
<!-- id: (nuevo) GET /api/presupuesto/reportes/{tipo}/exportar -->
<!-- entidades: Reporte -->
<!-- impuesto_en: plataforma/Reporte.java; plataforma/Exportador.java; reportes/ControladorReportes.java (formatos) -->
<!-- decision: usuario 2026-10-22 punto 7 | Anexo 5 L359 (2.7), L361 (2.9), L362 (2.10), L363 (2.11), L377 (2.12), L769 (§4.7.5) | mockup L528 -->

DEBEN existir los reportes «Ejecución presupuestal por Fuerza, ESM y modalidad» (al corte), «Ejecución mensual frente a la proyección», «Movimientos presupuestales» (con antes y después) y «Umbrales y desviaciones notificados», en el módulo de presupuesto **y** en el catálogo del módulo de reportes (no en la analítica, §4.7.5). Filtros de una, varias o todas las opciones de Fuerza, ESM y modalidad, y agrupación por meses, bimestres, trimestres, semestres o años. Descarga en xlsx, csv, pdf y txt con encabezado: título, número de página (PDF), fecha y hora de generación, usuario, periodo, establecimiento («Todos» o el nombre) y filtros. Mismas cifras para dos usuarios del mismo perfil y filtros. Visualización en menos de 3 minutos y exportación en menos de 10. Un formato no válido responde 422. Cada descarga queda en la bitácora (`reporte_descargado`).
<!-- incierto: `Reporte` no tiene campos de periodo ni de establecimiento, y el PDF de `Exportador` no numera páginas (no se encontró en el código): verificar y agregar para cumplir 2.9. -->

#### Scenario: Movimientos en Excel
<!-- test: sin prueba -->
- **CUANDO** la DIGSA descarga «Movimientos presupuestales» de 2027 en xlsx
- **ENTONCES** el archivo trae el rebalanceo con origen, destino, valor, soporte, usuario y los antes y después, y el encabezado con periodo «2027-01-01 a 2027-12-31»

#### Scenario: Agrupación trimestral
<!-- test: sin prueba -->
- **CUANDO** se agrupa por trimestres la ejecución de enero a junio
- **ENTONCES** salen dos filas por línea (T1 y T2), cada una con la suma de sus 3 meses

---

### Requirement: Acceso y alcance
<!-- id: Pantallas.PRESUPUESTO -->
<!-- entidades: Perfil -->
<!-- impuesto_en: reglas/Pantallas.java; reglas/Alcance.java de() -->
<!-- decision: usuario 2026-10-22 punto 7 (propuesta) | Anexo 5 L428 (3.7: «el jefe del ESM solo podrá visualizar la información propia de su establecimiento») -->

La pantalla nueva `Pantallas.PRESUPUESTO` («Presupuesto», grupo «Presupuesto») DEBE dar: consulta a DIGSA, Coordinador y Administrador (todo), Analista (nacional, solo consulta), Sanidad (su fuerza) y ESM (su punto y, si es centralizador, el detalle de sus satélites); registro de novedades, ejecución, proyección, vigencias y modalidades solo a **Coordinador y Administrador** (interventoría). El Auditor y los perfiles OPL DEBEN recibir 403 (registrado en la bitácora). Sin su dato de alcance, el perfil no ve nada (falla cerrado).
<!-- incierto: en esta rama `Alcance.de` da `Ninguno` al Analista (duda 17.3); el módulo necesita «nacional de consulta» para él (otra sesión lo decidió en la rama de identidad). Ver duda 7. -->

#### Scenario: Permisos
<!-- test: sin prueba -->
- **CUANDO** un usuario DIGSA intenta registrar una adición / un OPL nacional abre el tablero / un ESM consulta
- **ENTONCES** 403 / 403 / ve solo su punto

---

### Requirement: Bitácora con antes y después
<!-- id: Bitacora.TIPOS (nuevos) -->
<!-- entidades: Bitacora -->
<!-- impuesto_en: reglas/Bitacora.java TIPOS, cambios() -->
<!-- decision: decisiones_2026-10-20 «Prueba en sitio» ítem 15 (valores de antes y después) | Anexo 1 L1364 -->

Cada acción DEBE dejar su fila: `presupuesto_vigencia` (inserción o actualización), `presupuesto_modalidad` (inserción o actualización), `presupuesto_movimiento` (inserción, con tipo, número de líneas, neto y «asignado: «X» → «Y»» por línea hasta 500 caracteres), `presupuesto_carga_simulada` (consulta), `presupuesto_carga_aplicada`, `presupuesto_ejecucion_carga`, `presupuesto_ejecucion_manual`, `presupuesto_proyeccion_carga` (inserción), `presupuesto_umbral` y `presupuesto_desviacion` (inserción) y `presupuesto_notificacion_fallida` (inserción). Los parámetros usan el `parametro_editado` existente.

#### Scenario: Detalle del rebalanceo
<!-- test: sin prueba -->
- **CUANDO** se registra el rebalanceo de Neiva a Cali
- **ENTONCES** la bitácora trae «rebalanceo · 2 líneas · neto 0,00 · ESM 11 Dispensación: «900000000,00» → «450000000,00»; ESM 07 Dispensación: «1000000000,00» → «1450000000,00»»

---

### Invariant: Los movimientos y la ejecución son solo de inserción
<!-- entidades: PresupuestoMovimiento, PresupuestoMovimientoLinea, PresupuestoEjecucion, PresupuestoUmbral, PresupuestoNotificacion -->
<!-- impuesto_en: migración V40 (GRANT SELECT, INSERT sin DELETE al rol de la aplicación; UPDATE solo de la columna `vigente` en ejecución y proyección, y de los saldos y catálogos) -->
<!-- verificado_por: prueba de integración que intenta UPDATE y DELETE con el rol de la aplicación -->

Ningún movimiento, línea de movimiento, versión de ejecución, umbral registrado ni notificación DEBE modificarse ni borrarse; una corrección es un registro nuevo. Solo `presupuesto_saldos`, `presupuesto_ejecucion.vigente` y los catálogos se actualizan.

---

### Invariant: El saldo es la suma de los movimientos y de la ejecución vigente
<!-- entidades: PresupuestoSaldo -->
<!-- impuesto_en: ServicioPresupuesto (misma transacción, SELECT … FOR UPDATE del saldo) -->
<!-- verificado_por: prueba de integración que recalcula desde cero y compara -->

Para toda línea, `asignado` DEBE ser la suma de sus `presupuesto_movimientos_lineas.valor`, `facturado` y `reconocido` la suma de sus ejecuciones vigentes, y `saldo = asignado − ejecutado (base)`. El total de Fuerza y el nacional son la suma de las líneas. Un rebalanceo y una recomposición dejan el total de la vigencia igual al centavo.

---

### Invariant: Montos con 2 decimales y fechas en Bogotá
<!-- entidades: — -->
<!-- impuesto_en: columnas numeric(18,2); reglas/Fechas.BOGOTA -->
<!-- verificado_por: pruebas JUnit de la regla de montos -->

Todos los montos DEBEN ser `numeric(18,2)` (nunca `double`) y se calculan con `BigDecimal` y redondeo HALF_EVEN solo al repartir (reajuste por %, proyección lineal). Los % se muestran con 2 decimales y se comparan sin redondear. Periodos, cortes y «hoy» se calculan en America/Bogota.

---

### Invariant: Un umbral se notifica una sola vez por vigencia y línea
<!-- entidades: PresupuestoUmbral, AlertaEvento -->
<!-- impuesto_en: PK (linea_id, umbral) de presupuesto_umbrales; ux_alertas_eventos_unico (V17) -->
<!-- verificado_por: prueba de concurrencia: dos cargas a la vez que cruzan el 50 % dejan una sola fila y un solo aviso -->

No DEBE haber dos registros ni dos avisos del mismo umbral para la misma línea, ni siquiera con dos cargas simultáneas.

---

## Contradicciones

1. **Modalidades.** Anexo 5 L534 y el mockup: dispensación, órdenes de requisición y suministro. Anexo 3 OPL §38 (L5658): se factura por DISPENSACIÓN, CONSIGNACIÓN, SUMINISTRO, SATÉLITE e INTRAMURAL HOMIL; L2521 y L2641 nombran «orden de requisición» como modalidad de distribución. `par_modalidades` (V7) es otra cosa: la modalidad del punto (Dispensación, Suministro, Consignación, Satélite). Se resuelve con el catálogo propio: las 3 del Anexo 5 activas y las otras precargadas inactivas.
2. **Nivel del umbral.** Anexo 2 L741 notifica cuando «la ejecución presupuestal de los ESM» alcanza el umbral; L739 controla «por cada ESM y por Fuerza … cada una de las modalidades»; el mockup (L480-482) muestra «50 % notificado» por Fuerza. Se notifica por ESM × modalidad; el umbral de la Fuerza se muestra calculado, sin notificación propia (duda 4).
3. **Satélites con modalidad propia.** Anexo 3 OPL L3356: los satélites dependen del presupuesto del centralizador; L5658: «SATÉLITE» es una modalidad de facturación. Se imputa al centralizador; si la DIGSA activa la modalidad Satélite, la línea es (centralizador, Satélite).
4. **HOMIL con fuerza «NA».** V7 L1298 lo carga en el nivel central; Anexo 3 OPL L5240 manda afectar el presupuesto de cada fuerza por adscripción. Se exige la fuerza en las líneas de los puntos «NA».
5. **Granularidad de los filtros.** Anexo 5 2.11 pide horas, días y semanas; la ejecución es mensual (§38: facturación del mes). El presupuesto agrupa de meses a años.
6. **Destinatarios de las alertas.** `ServicioAlertas` manda a todos los usuarios de los perfiles del catálogo, sin fuerza; el Anexo 2 L741 manda a la Sanidad de cada fuerza. Se agrega la notificación formal por buzón de la fuerza sin tocar el módulo de alertas.
7. **Alcance del Analista.** El usuario lo quiere como consulta nacional; en esta rama `Alcance.de` le da `Ninguno` (17.3).
8. **«Anexo técnico financiero, módulo presupuesto»** (Anexo 2 L741) no existe en la fuente de verdad; RESPUESTAS L1975 remite al contrato del OPL (173-MDN-COGFM-JEMCO-DIGSA-2026), que no tenemos.

## Dudas

> **Resueltas por el usuario el 2026-10-22 (dudas 1 a 5):** vigencia anual con vista «Contrato»; umbrales sobre lo **facturado** (parámetro `presupuesto_base_umbral`); la ejecución del mes **reemplaza** con versiones; la notificación del umbral es por **ESM × modalidad** al buzón de la Sanidad de la Fuerza (`par_fuerzas.correos`) y al de la DIGSA, más la alerta del catálogo (DIGSA y Coordinador). Las propuestas de esas dudas pasan a ser requisito.

Bloqueantes (el módulo se construye con la propuesta, pero un cambio después obliga a rehacer datos o pantallas):

1. **Vigencia anual o total del contrato.** Propuesta: vigencia fiscal anual, con vista «Contrato» que suma. *Mientras tanto:* anual; las tablas llevan `vigencia_id` y la vista suma.
2. **Base del umbral: facturado o reconocido.** Propuesta: facturado (anticipa el exceso; el reconocido llega meses después por la conciliación). *Mientras tanto:* parámetro `presupuesto_base_umbral = facturado`, editable por el coordinador.
3. **La ejecución del mes reemplaza o suma.** Propuesta: reemplaza (corte mensual versionado). *Mientras tanto:* reemplaza; cuentas médicas publicará el total del mes por el mismo puerto.
4. **Nivel de la notificación del umbral** (ESM × modalidad, ESM total o también Fuerza). Propuesta: ESM × modalidad; la Fuerza solo en pantalla. *Mientras tanto:* así; agregar ESM total es una clave más.
5. **Destinatarios de la notificación formal.** Propuesta: catálogo de alertas con DIGSA y Coordinador, y notificación formal con consecutivo a `par_fuerzas.correos` de la fuerza de la línea más el buzón DIGSA (`correo_digsa`). *Mientras tanto:* así; el perfil Sanidad NO va en el catálogo (recibiría las de otras fuerzas).

No bloqueantes:

6. **Mapa satélite → centralizador.** ¿Quién lo entrega? *Mientras tanto:* `puntos.centralizador_id` editable; la ejecución de un satélite sin centralizador se rechaza por fila.
7. **Alcance del Analista.** *Mientras tanto:* nacional de solo consulta en este módulo; se alinea con la rama de identidad al unir.
8. **Quién registra novedades y ejecución.** Propuesta: Coordinador y Administrador (la DIGSA entrega la información, Anexo 5 L536). *Mientras tanto:* así; la DIGSA, Sanidad y ESM solo consultan; el OPL no entra.
9. **Qué es un «reajuste».** *Mientras tanto:* ajuste con signo o % sobre un ámbito, sin neto cero, con soporte obligatorio.
10. **Disminución bajo lo ejecutado.** Propuesta: rechazo. *Mientras tanto:* 422 con las líneas; la adición y la ejecución nunca se bloquean.
11. **Tolerancias de desviación** (10 % exceso, 20 % subejecución) y si la subejecución se notifica a las sanidades. *Mientras tanto:* parámetros editables; se notifica igual que el exceso.
12. **Re-notificación tras una adición.** Propuesta: no, una vez por vigencia, línea y umbral. *Mientras tanto:* así (aviso único de la alerta operativa).
13. **Modalidades activas.** *Mientras tanto:* las 3 del Anexo 5; Consignación, Satélite e Intramural HOMIL inactivas.
14. **Método de proyección.** *Mientras tanto:* lineal, o la cargada por la DIGSA si existe.
15. **«Precio techo global de consumo mensual»** (Anexo 2 L1126): no se sabe su fórmula (obs. 61 sin respuesta de fondo). *Mientras tanto:* fuera de este módulo.

## Datos que la reescritura Java debe agregar

1. **V40__presupuesto.sql** (tablas; todas con `GRANT` al `${rol_app}` como V16 y V17):
   - `presupuesto_vigencias (id, anio smallint UNIQUE, inicio date, fin date, estado CHECK ('abierta','cerrada'), creado_por, creado)`, `CHECK (inicio <= fin AND extract(year FROM inicio) = anio AND extract(year FROM fin) = anio)`.
   - `presupuesto_modalidades (codigo text PK CHECK '^[a-z_]{3,30}$', nombre, activa, orden)`; semilla `dispensacion`, `requisicion`, `suministro` activas; `consignacion`, `satelite`, `intramural_homil` inactivas.
   - `presupuesto_lineas (id, vigencia_id, fuerza → par_fuerzas, punto_id → puntos, modalidad → presupuesto_modalidades, creado)`, `UNIQUE (vigencia_id, punto_id, modalidad, fuerza)`, `CHECK (fuerza <> 'NA')`.
   - `presupuesto_saldos (linea_id PK, asignado, facturado, reconocido numeric(18,2) NOT NULL DEFAULT 0, version bigint, actualizado)` con UPDATE para la aplicación.
   - `presupuesto_soportes (id, nombre_original, ruta UNIQUE, tipo, tamano, sha256 char(64), subido_por, subido)` (el soporte DIGSA no cabe en `archivos`, que exige visita).
   - `presupuesto_lotes (id, clase CHECK ('asignacion','ejecucion','proyeccion'), fuente CHECK ('digsa','opl','cuentas_medicas'), sha256 char(64), nombre_original, filas, total numeric(18,2), aplicado_por, aplicado)`, `UNIQUE (clase, sha256)`.
   - `presupuesto_movimientos (id, vigencia_id, tipo CHECK ('asignacion_inicial','adicion','disminucion','reajuste','rebalanceo','recomposicion'), soporte text CHECK 5–300, soporte_id → presupuesto_soportes, lote_id, observacion ≤1000, registrado_por, creado)`.
   - `presupuesto_movimientos_lineas (movimiento_id, linea_id, valor numeric(18,2) CHECK (valor <> 0), asignado_antes, asignado_despues, ejecutado_al_momento)`, PK `(movimiento_id, linea_id)`.
   - `presupuesto_ejecucion (id, linea_id, punto_origen_id (satélite), periodo date CHECK (extract(day FROM periodo) = 1), facturado numeric(18,2) CHECK (>= 0), reconocido numeric(18,2) CHECK (reconocido IS NULL OR reconocido BETWEEN 0 AND facturado), fuente, referencia ≤120, lote_id, vigente boolean, registrado_por, creado)`; índice único parcial `(linea_id, periodo) WHERE vigente`.
   - `presupuesto_proyeccion (id, linea_id, periodo, valor, lote_id, vigente, creado)`; único parcial `(linea_id, periodo) WHERE vigente`.
   - `presupuesto_umbrales (linea_id, umbral smallint CHECK IN (25,50,75,100), porcentaje numeric(7,2), base text, alcanzado timestamptz, evento_id → alertas_eventos, notificacion_id)`, PK `(linea_id, umbral)`.
   - `presupuesto_notificaciones (id, consecutivo text UNIQUE, tipo CHECK ('umbral','desviacion'), nivel CHECK ('esm','fuerza','nacional'), linea_id, fuerza, corte date, texto, para text[], correo_id → correos_salida, estado, creado)`.
2. **V41__presupuesto_puntos.sql:** `puntos.centralizador_id bigint REFERENCES puntos(id)` con `CHECK (centralizador_id <> id)` e índice.
3. **V42__presupuesto_alertas.sql:** filas en `alertas_criticas`: `presupuesto_umbral` («Umbral de ejecución presupuestal») y `presupuesto_desviacion` («Desviación frente a la proyección»), `critica=false`, `sms=false`, `perfiles='{DIGSA,COORDINADOR}'`. Parámetros en `par_parametros`: `presupuesto_base_umbral` (`facturado`), `presupuesto_tolerancia_exceso` (`10,00`), `presupuesto_tolerancia_subejecucion` (`20,00`), `presupuesto_consecutivo_prefijo` (`PRE`).
4. **Índices:** `presupuesto_lineas (vigencia_id, fuerza, modalidad)`, `(punto_id)`; `presupuesto_ejecucion (linea_id, periodo) WHERE vigente`; `presupuesto_movimientos (vigencia_id, creado DESC)`; `presupuesto_movimientos_lineas (linea_id)`; FK indexadas. El tablero es un `GROUP BY` sobre `presupuesto_saldos ⋈ presupuesto_lineas ⋈ puntos` (sin N+1); la proyección lineal se calcula en SQL.
5. **Reglas puras** `reglas/Presupuesto.java`: montos (lectura con coma o punto, 2 decimales), % y umbrales alcanzados (`BigDecimal`), proyección lineal con residuo, desviación y sentido, neto cero, reparto de un reajuste por %, consecutivo y clave de deduplicación; con pruebas JUnit de todos los escenarios con cifras.
6. **`Pantallas.PRESUPUESTO`** y la regla de alcance del Analista; **`Exportacion.TABLAS`** + las 12 tablas de V40 (y la prueba contra `information_schema`); **`Bitacora.TIPOS`** + los tipos del requisito de bitácora.
7. **API** con `@Accion`: `GET/POST /api/presupuesto/vigencias`, `GET/POST/PUT /api/presupuesto/modalidades`, `POST /api/presupuesto/asignacion/{simular|aplicar}`, `POST /api/presupuesto/novedades` (multipart con soporte), `POST /api/presupuesto/ejecucion/{simular|aplicar}`, `POST /api/presupuesto/ejecucion/manual`, `POST /api/presupuesto/proyeccion/{simular|aplicar}`, `GET /api/presupuesto/tablero`, `GET /api/presupuesto/movimientos`, `GET /api/presupuesto/reportes/{tipo}` y `/exportar`; y el reporte «Ejecución presupuestal» en el catálogo de `/api/reportes`.
8. **Páginas React** «Presupuesto» (tablero, novedad, historial, cargas, reportes) a 360 px, y la caja del inicio.
9. **`Reporte`/`Exportador`:** periodo, establecimiento y número de página en el PDF (Anexo 5 2.9).

<!-- pendiente: el contrato del OPL 173-MDN-COGFM-JEMCO-DIGSA-2026 (estructura presupuestal real, plazo, modalidades presupuestadas) y el «anexo técnico financiero, módulo presupuesto»; el texto oficial del protocolo M7 (no está en FUENTE DE VERDAD/md); formato del archivo de ejecución que entregaría la DIGSA. -->
