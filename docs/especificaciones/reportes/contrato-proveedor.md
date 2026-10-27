# Contrato `ProveedorReporte`: cómo un módulo agrega sus reportes

> Para las sesiones de identidad, presupuesto, cuentas médicas y analítica. Paquete `co.com.agsamericas.digsa.reportes`
> (rama `reportes`). Lo que manda: Anexo 5, 2.7 a 2.12 y l.769; RESPUESTAS l.555, 629 y 2209.

Cada módulo registra sus reportes como **beans de Spring que implementan `ProveedorReporte`**. El motor los descubre al arrancar,
los pone en el catálogo de los perfiles que pueden generarlos y les da, sin código adicional:

- pantalla de reportes con filtros, presets y agrupación;
- encabezado de 2.9 (título, periodo, generado, usuario, establecimiento, filtros, fuente, registros y página);
- TXT, CSV, Excel y PDF (2.12), con huella y código de verificación (fase R2);
- «Descargas del día» y generación diferida (fase R3);
- envío por correo y bitácora (`reporte_generado`, `reporte_descargado`, `reporte_enviado`).

**No se edita `Reportes.tsx`, ni el controlador, ni el motor para agregar un reporte.** Solo se agrega una clase al módulo dueño.

---

## 1. La interfaz

```java
public interface ProveedorReporte {
    DefinicionReporte definicion();                                       // estática: se lee al arrancar
    default List<Opcion> opciones(String filtro, Alcance alcance) { ... } // opciones de un filtro de lista, recortadas al alcance
    default boolean disponible(Alcance alcance) { return true; }          // p. ej. solo con Alcance.Todo
    void filas(ConsultaReporte consulta, Consumer<List<Object>> destino); // escribe las filas en orden total
}
```

Las reglas son:

- **El proveedor solo entrega datos.** No arma el encabezado, ni el archivo, ni la huella, ni escribe en la bitácora.
- **El alcance se aplica en el SQL**, con `consulta.alcance()`. Nunca se filtra después de leer, ni en el frontend.
- **Las opciones de los filtros salen recortadas al alcance.** El motor valida cada valor del cliente contra
  `opciones(filtro, alcance)`. Un valor que no está devuelve 422 «no existe o está fuera de su alcance», con el mismo mensaje en
  los dos casos para no revelar lo que el usuario no ve.
- **Las filas salen en orden total, con desempate por llave.** Por ejemplo, `ORDER BY p.nombre, p.id`, no solo `p.nombre`: la
  huella de los datos (2.10) depende del orden.
- **Cada fila trae exactamente las columnas de `consulta.columnas()`, en ese orden.** Si `consulta.agrupacion()` no es null, la
  fila empieza además por la etiqueta del periodo. Una fila de otro ancho es un error de programación: el motor la rechaza.
- **Los decimales van en `BigDecimal`**, no en `double`; las fechas en `LocalDate`, y las fechas y horas en `OffsetDateTime`. El
  motor las pasa a hora de Bogotá y 24 h en pantalla y en los archivos.
  - Tipos admitidos en una celda (`TiposCelda`): `String`, `Integer`, `Long`, `Short`, `BigDecimal`, `Double` finito, `Boolean`,
    `LocalDate`, `LocalDateTime`, `OffsetDateTime`, `ZonedDateTime` y `null`.
  - Cualquier otro (`java.sql.Timestamp`, `UUID`, `Float`, `Instant`…) es un error del proveedor, y el motor lo rechaza nombrando la
    columna.
  - Por eso no use `rs.getObject("x")` sin tipo sobre un `timestamptz`, un `uuid` o un `numeric`: use
    `rs.getObject("x", OffsetDateTime.class)` o `rs.getBigDecimal`.
- **`filas` corre dentro de una transacción propia de solo lectura en REPEATABLE READ** que abre el motor.
  - Para leer, inyecte `JdbcReportes` y use `jdbcReportes.jdbc()` (cursor de 1.000 filas) con `query(RowCallbackHandler)`
    (`rs -> {...}`), no `.list()`. Así las filas van al destino por lotes y no se carga todo en memoria.
  - Termine el SQL con `+ JdbcReportes.limite(consulta)` (` LIMIT n`). El motor pide una fila más de las que muestra en pantalla
    (5.000) o de las que admite el archivo (`maxFilas`), para avisar o rechazar sin leer el resto.
  - Entregue **una lista nueva por fila**: el motor no la copia.
  - No capture ni envuelva las excepciones que lance `destino.accept(...)`.

## 2. La definición

Se arma con `DefinicionReporte.nuevo(id, titulo)` y se valida al construirla. Si algo está mal, la aplicación no arranca.

| Campo | Qué es | Regla |
|---|---|---|
| `id` | Va en la URL (`/api/reportes/{id}`), en la huella y en la bitácora | `^[a-z][a-z0-9_]{2,40}$`; único entre todos los módulos; no puede ser `catalogo`, `presets`, `descargas`, `verificar` ni `opciones` |
| `titulo`, `descripcion` | Lo que se ve en el catálogo y en el encabezado | En español |
| `grupo` | Grupo del catálogo (`GrupoReporte`) | `DISPENSADO_AUDITADO`, `PENDIENTES_OPL`, `GLOSAS`, `CONCILIACIONES`, `VISITAS`, `PRESUPUESTO`, `SEGURIDAD`. La analítica farmacéutica **no** va aquí (Anexo 5, 4.7.5) |
| `modulo` | Módulo dueño | p. ej. `presupuesto` |
| `clase` | `GENERAL` u `OPERATIVO` (RESPUESTAS l.629) | GENERAL: solo agregados, ninguna columna `personal`. OPERATIVO: detalle, con las columnas personales limitadas a sus perfiles |
| `pantalla` | La pantalla que hay que poder abrir para generarlo | Por defecto `Pantallas.REPORTES`; los logs usan `LOGS` |
| `fuente` | Línea «Fuente» del encabezado | p. ej. «Suite DIGSA · presupuesto» |
| `version` | Súbala al cambiar columnas o cálculos | Cambia la huella de los datos |
| `periodo` | `NINGUNO`, `FECHA` o `FECHA_HORA` | Con `NINGUNO` no hay rango ni agrupación |
| `agrupaciones` | Subconjunto de `Periodos.Agrupacion`: hora, día, semana, mes, bimestre, trimestre, semestre, año | Por horas solo con `FECHA_HORA` |
| `maxDias` | Tope del rango | 3 años por defecto |
| `maxFilas` | Tope de filas del archivo | 0 = sin tope. Superarlo devuelve 422: nunca sale un archivo truncado |
| `filtros` | `Filtro.lista(clave, etiqueta)`, `Filtro.establecimiento(...)` o `Filtro.texto(...)` | Claves únicas, ninguna reservada (`desde`, `hasta`, `agrupacion`, `formato`, `preset`, `para`, `codigo`); como máximo un filtro de establecimiento |
| `filtros` de un valor (provisional) | `Filtro.lista(...).deUnValor()` | Solo si el servicio del módulo todavía filtra por un valor a la vez. El motor rechaza varios (422) en un único punto y la pantalla deja elegir uno. 2.11 pide «una, varias o todas»: al ampliar el servicio a listas, se quita la marca (duda RP-7). Hoy la usan los tableros y la vigencia, el ESM, el tipo y la agrupación de presupuesto |
| `columnas` | `columna(nombre, TipoColumna)` o `Columna.personal(nombre, perfiles)` | Tipos: TEXTO, ENTERO, DECIMAL, PORCENTAJE, FECHA, FECHA_HORA |
| `orden` | El orden total, en palabras | Lo documenta; el SQL lo cumple |
| `posicion` | Posición dentro del grupo en el catálogo | Por defecto 100 |

**Filtros de lista (2.11).** Admiten una, varias o todas las opciones. En la API van como parámetro repetido
(`fuerza=EJC&fuerza=ARC`); sin el parámetro, salen todas. El motor entrega en `consulta.valores("fuerza")` los valores validados,
ordenados y sin repetir; úselos con `IN (:lista)`. El filtro de establecimiento (ESM o lugar) va en la línea «Establecimiento» del
encabezado.

**Columnas personales (Ley 1581).** `Columna.personal("Auditor", Set.of(Perfil.COORDINADOR))` solo la ven esos perfiles y el
Administrador. Para un perfil que no la ve, el reporte es otra variante, con su propia huella. Pregunte con `consulta.ve("Auditor")`
si debe escribir esa celda.

**Periodo.** El motor resuelve `desde` y `hasta` (o un preset) antes de llamar al proveedor; `consulta.desde()` y
`consulta.hasta()` son fechas incluidas. Para agrupar, arme la etiqueta en el SQL con `SqlPeriodo.etiqueta(agrupacion, expresion)`,
que coincide con `Periodos.Agrupacion.etiqueta` (`2026-07-14 09h`, `2026-07-14`, `2026-W29`, `2026-07`, `2026-B4`, `2026-T3`,
`2026-S2`, `2026`). Para un `timestamptz`, pase `(x AT TIME ZONE 'America/Bogota')`.

## 3. Ejemplo mínimo (presupuesto)

```java
@Component
class ReporteEjecucionPresupuestal implements ProveedorReporte {

    private static final DefinicionReporte DEFINICION = DefinicionReporte.nuevo("ejecucion_presupuestal", "Ejecución presupuestal")
            .descripcion("Por fuerza y mes: apropiado, facturado, auditado y saldo.")
            .grupo(GrupoReporte.PRESUPUESTO).modulo("presupuesto").fuente("Suite DIGSA · presupuesto").posicion(1)
            .periodo(CampoTemporal.FECHA, EnumSet.range(Agrupacion.MES, Agrupacion.ANIO))
            .filtro(Filtro.lista("fuerza", "Fuerza"))
            .columna("Fuerza", TipoColumna.TEXTO).columna("Facturado", TipoColumna.DECIMAL).columna("% ejecución", TipoColumna.PORCENTAJE)
            .orden("periodo y código de la fuerza").crear();

    private final JdbcClient jdbc;

    ReporteEjecucionPresupuestal(JdbcReportes jdbc) { this.jdbc = jdbc.jdbc(); }   // con cursor

    @Override public DefinicionReporte definicion() { return DEFINICION; }

    @Override public List<Opcion> opciones(String filtro, Alcance alcance) {
        // las fuerzas que el alcance deja ver (Alcance.Fuerza: solo la suya)
    }

    @Override public void filas(ConsultaReporte c, Consumer<List<Object>> destino) {
        String periodo = c.agrupacion() == null ? null : SqlPeriodo.etiqueta(c.agrupacion(), "f.fecha");
        // SELECT [periodo,] ... WHERE f.fecha BETWEEN :desde AND :hasta AND <alcance> [AND f.fuerza IN (:fuerzas)]
        // GROUP BY ... ORDER BY [1,] f.fuerza  + JdbcReportes.limite(c)
        jdbc.sql(sql).params(params).query(rs -> {
            List<Object> fila = new ArrayList<>();
            if (periodo != null) fila.add(rs.getString("periodo"));
            fila.add(rs.getString("fuerza"));
            fila.add(rs.getBigDecimal("facturado"));
            fila.add(rs.getBigDecimal("porcentaje"));
            destino.accept(fila);
        });
    }
}
```

Los cuatro reportes de visitas, en el paquete `motorreportes`, son ejemplos completos: `ReporteDetalleVisitas`,
`ReporteVisitasLugar`, `ReporteVisitasProceso` y `ReporteDistribucion`. Sus filtros comunes (lugar, municipio, fuerza, unidad,
regional, estado y componente) están en `FiltrosVisitas`. Las reglas del recuadro aplican también al ejemplo: `JdbcReportes`,
`LIMIT` y una lista nueva por fila.

Adaptadores de un servicio que ya existe (R4), sin cambiar su lógica:

- `presupuesto/ProveedoresReportePresupuesto`: traduce `ServicioReportesPresupuesto.DEFINICIONES` y entrega las filas de `generar()`
  por nombre de columna. Una columna que falte (salvo las que solo vienen con corte) es un error, no una columna vacía.
- `tablero/ProveedoresReporteTablero`: arma las filas con las mismas respuestas que pintan el dashboard y el riesgo.
- `auditoria/ProveedorReporteLogs`: SQL propio sobre `log_transacciones`, con los topes de la pantalla de logs.

## 4. Dependencias entre módulos (Spring Modulith)

Son dos módulos:

| Módulo | Qué tiene | Depende de |
|---|---|---|
| `reportes` | Solo el contrato: `ProveedorReporte`, `DefinicionReporte`, `ConsultaReporte`, `Opcion`, `GrupoReporte`, `SqlPeriodo` y `JdbcReportes` | `reglas` (y Spring JDBC) |
| `motorreportes` | El motor, el controlador `/api/reportes`, la guarda de acceso y los reportes de visitas | `reportes`, `identidad`, `catalogos`, `agenda`, `plataforma` |

- El módulo que aporta un reporte **depende solo de `reportes`**, que no depende de ningún módulo funcional. Por eso **cualquier
  módulo puede aportar reportes sin crear ciclos**, incluidos `identidad` (usuarios y seguridad), `auditoria` (logs) y
  `presupuesto`. Lo verifica `ArquitecturaTest`.
- `motorreportes` descubre los beans por la interfaz, sin importar al módulo que los aporta.
- No hace falta tocar `Pantallas`: el reporte usa una pantalla que ya existe. Si su módulo necesita una pantalla propia, la crea su
  sesión, y el reporte la declara en `pantalla(...)`. La guarda de `/api/reportes` deja entrar a quien puede abrir la pantalla de
  algún reporte registrado, y el motor la vuelve a comprobar en cada reporte: si no la puede abrir, responde 404 sin revelar que
  existe.

## 5. Endpoints que obtiene el reporte

| Método y ruta | Qué hace |
|---|---|
| `GET /api/reportes/catalogo` | Grupos con los reportes del perfil, formatos y presets del periodo |
| `GET /api/reportes/{id}/opciones` | Opciones de cada filtro de lista, dentro del alcance |
| `GET /api/reportes/{id}?desde&hasta&preset&agrupacion&<filtro>=…` | El reporte en pantalla: encabezado de 2.9, columnas, filas (hasta 5 000; el archivo trae todas) y tiempo en ms |
| `GET /api/reportes/{id}/exportar?formato=xlsx\|csv\|pdf\|txt&…` | El archivo si termina en 15 s; si no, 202 con el código y sigue en «Descargas del día» |
| `GET /api/reportes/descargas` y `/descargas/{codigo}` | Lo que el usuario pidió hoy, y volver a bajarlo sin regenerarlo |
| `POST /api/reportes/{id}/enviar` | `{ para, formato, desde, hasta, agrupacion, filtros: { clave: [valores] } }`: hasta 15 correos de los dominios permitidos; la generación se corta al pasar de 10 MB |
| `GET /api/reportes/tiempos?dias=` | Solo el Administrador: p50, p95 y máximo por reporte y formato, y cuántas pasaron el 80 % del plazo |
| `POST /api/reportes/verificar` (multipart `archivo`) | ¿El archivo es uno que generó la Suite DIGSA? Lo compara por su huella SHA-256 |
| `GET /api/reportes/verificar/{codigo}` | Lo que se registró con ese código de verificación |
| `POST /api/reportes/verificar/{codigo}/regenerar` | Regenera el reporte con los mismos parámetros y compara la huella de los datos |

Además, cada generación queda registrada en `reportes_generados` (V50) con su código de verificación `RPT-aaaammdd-XXXXXXXX`. El
archivo:
- lleva el encabezado de 2.9 completo, con el código y la huella de los datos;
- se arma en flujo: cursor, temporal en disco y almacén;
- en PDF sale cifrado y sin permiso de modificar, con letra Unicode incrustada y sin tope de filas (reportes completos, TAS P12).

Nada de esto exige cambios al proveedor: «Descargas del día», la generación diferida, la medición de tiempos y el envío a varios
destinatarios son del motor.

## 6. Pruebas que debe traer el reporte nuevo

- Una prueba de API (`PruebaIntegracion`) que compruebe:
  - el catálogo de un perfil con acceso y de uno sin acceso;
  - un filtro con dos valores;
  - un valor fuera del alcance (422);
  - si el reporte agrupa, una agrupación.
- Las validaciones de la definición ya las hace el motor. Sí revise que `ArquitecturaTest` siga en verde.
