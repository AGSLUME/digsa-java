# Plan de fases: módulo de reportes

> Rama `reportes` (worktree `digsa-java-reportes`, desde develop fe9c98e). Especificación: [spec.md](spec.md).
> Migraciones V50 a V59. Un commit por fase, con revisión de security-reviewer, java-reviewer, react-reviewer, database-reviewer,
> silent-failure-hunter, pr-test-analyzer y performance-optimizer. Los P1 se corrigen antes del commit.

## Estado (2026-10-23)

| Fase | Estado | Commit |
|---|---|---|
| R1 · Motor, contrato, catálogo y filtros | Hecha | 42364dc (junto con R2) |
| R2 · Formatos en flujo, encabezado 2.9 e integridad 2.10 | Hecha | 42364dc |
| R3 · Descargas del día, diferido, limpieza y tiempos | Hecha | f88cc5c; integración de develop en f0b2a71 |
| R4 · Correo, generales y operativos, tablero y logs en el motor | Hecha | 7c4ebfc |
| R5 · Rendimiento, volumen y cierre | Hecha, en el índice | sin commit todavía |

Las decisiones y los cambios de cada fase están en `docs/dudas_pendientes.md`, sección «Reportes» (R.1 a R.55).

**Lo que cambió respecto del plan original:**
- **Tiempos (decisión de la sesión 1, R3).** Los plazos de 3 min (ver) y 10 min (exportar) **no cortan**: el usuario recibe su
  reporte completo. Al pasar el 80 % o el plazo se dispara la alerta «reporte lento» (V51) al Administrador. Solo cortan los topes
  técnicos: 60 min al generar un archivo (`digsa.reportes.tope-tecnico-ms`) y 10 min en pantalla (`tope-tecnico-ver-ms`, R.40).
- **Sin topes de filas (TAS P12, R.26).** Ni el PDF ni los reportes de visitas tienen tope de filas. Excel pasa a la hoja «Datos 2»
  al llegar al millón de filas.
- **Migraciones.** V50 trae ya las columnas de R3 (R.25). V51 es la alerta «reporte lento». V52 es el índice del cupo por usuario y
  dos CHECK de estados, no los índices de R5. No se creó V53: con la prueba de volumen no hizo falta ningún índice (R.51).
- **Correo.** No es síncrono aparte: pasa por la misma cola y el mismo cupo de las descargas, y la petición espera el resultado
  (R.39). La generación se corta al pasar de 10 MB, con 422 (R.39, R.52).
- **Filtros de un valor.** Los tableros y parte de presupuesto filtran por un valor a la vez. Es provisional: el filtro lleva la marca
  `deUnValor()` y elegir más de uno da 422 (R.45, RP-7 en `contrato-proveedor.md`).

## R1 · Motor, contrato, catálogo y filtros · hecha (42364dc)

- **Contrato.** `reportes/ProveedorReporte` con id, título, grupo, clase GENERAL u OPERATIVO, perfiles, filtros, campo temporal,
  agrupaciones, columnas tipadas, orden total, fuente y versión. Las filas se entregan en flujo, no en memoria.
- **Registro.** `RegistroReportes` descubre los beans al arrancar. Un id duplicado impide el arranque.
- **Reglas puras en `reglas/`.**
  - Periodos: presets y agrupación por horas, días, semanas ISO, meses, bimestres, trimestres, semestres y años, al corte de Bogotá.
  - Filtros multivalor: una, varias o todas las opciones.
- **Endpoints.**
  - `GET /api/reportes/catalogo`: lo que el perfil puede ver.
  - `GET /api/reportes/{id}/opciones`: opciones de filtro dentro del alcance.
  - `GET /api/reportes/{id}`: vista en pantalla.
  - Se mantienen las rutas actuales `/api/reportes/{tipo}`, `/exportar` y `/enviar`.
- **Visitas en el motor.** Los reportes detalle, lugar, proceso y distribución pasan al motor. Se cierra la brecha de la prueba 13:
  - el detalle gana inicio y fin reales, objeto, componentes y el texto de los hallazgos;
  - el consolidado por lugar gana la columna de componente.
- **Pantalla.** Reportes.tsx con catálogo (buscador y grupos) y selectores múltiples (`MultiSelect`).
- **Documentación.** `docs/especificaciones/reportes/contrato-proveedor.md`.
- **Pruebas.**
  - JUnit de periodos y filtros.
  - API: catálogo por perfil, filtros multivalor, valor fuera del alcance (422 sin revelar el nombre) y los ReportesApiTest actuales.
  - Vitest y e2e de reportes en 5 navegadores.

## R2 · Formatos en flujo, encabezado 2.9 e integridad 2.10 · hecha (42364dc)

- **Formatos en flujo.** `Exportador` agrega variantes que escriben a un `OutputStream` desde un cursor. Los métodos `byte[]` actuales
  se conservan para sus usos.
- **Encabezado 2.9** en los 4 formatos: título, periodo, generado, usuario, establecimiento, filtros, fuente, registros, código y página.
  - En PDF, «Página N de M» en cada hoja; en Excel, en el pie de impresión.
  - Con muchas opciones elegidas, el archivo lleva la lista completa (en Excel, una hoja «Filtros»).
- **Integridad.**
  - V50 crea `reportes_generados`.
  - Huella de los datos: SHA-256 canónico, sin usuario, fecha ni formato. Va impresa en el archivo (los 64 caracteres, R.20).
  - Huella del archivo: SHA-256 de los bytes finales.
  - Código de verificación `RPT-aaaammdd-` y 8 caracteres (R.29).
- **PDF.** AES-256 con contraseña de propietario aleatoria: imprimir y copiar sí, modificar no. Requiere `bcprov-jdk18on` en el pom.
- **Verificación.**
  - `POST /api/reportes/verificar`, por archivo o por código, con regeneración opcional.
  - Pantalla «Verificar reporte».
- **Determinismo.** Orden total, una sola transacción `REPEATABLE READ` y `BigDecimal`.
- **Pruebas.**
  - Misma huella con 2 usuarios del mismo alcance.
  - Un PDF abierto con OpenPDF muestra los permisos.
  - Un archivo alterado se rechaza.
  - Bitácora `reporte_generado` y `reporte_verificado`.
  - E2E de verificación.

## R3 · Descargas del día, diferido, limpieza y tiempos · hecha (f88cc5c)

- **Repositorio diario.** El estado, el archivo en el almacén, la vigencia hasta las 23:59:59 de Bogotá y los tiempos (consulta,
  archivo y total) ya venían en V50 (R.25). V51 agrega la alerta «reporte lento» y V52 el índice del cupo y dos CHECK de estados.
- **Diferido.** En vez del patrón de `exportacion` se usa una cola en la memoria de la instancia (R.36):
  - 4 a la vez por instancia y 2 en curso por usuario (más es 429);
  - si la generación pasa de 15 s (`digsa.reportes.espera-sincrona-ms`), responde 202 y la fila queda «Generando»;
  - «Descargas del día» consulta cada 5 s mientras algo se genera y avisa cuando está listo.
- **Descargas.** Solo las ve quien las pidió (404 para otro usuario). Un reporte pedido igual el mismo día no se reutiliza solo: el
  usuario lo baja de «Descargas del día» (R.38).
- **Limpieza.** Tarea `@Scheduled` con candado: al terminar el día borra el archivo y conserva el registro. Las «generando» huérfanas
  pasan a fallida.
- **Tiempos.** ~~Corte a 3 min (ver) y 10 min (exportar)~~. Cambió por decisión de la sesión 1: los plazos no cortan, solo disparan
  la alerta «reporte lento» al 80 % y al pasar el plazo. Solo cortan los topes técnicos (60 min archivo, 10 min pantalla). La pantalla
  muestra «generado en N s».
- **Pantalla.** «Descargas del día», como en el mockup.
- **Pruebas.** API con RelojMovible (vigencia y limpieza), diferido, acceso ajeno, tope técnico y e2e.

## R4 · Correo, generales y operativos, tablero y logs en el motor · hecha (7c4ebfc)

- **Correo.**
  - Hasta 15 destinatarios, con los dominios permitidos.
  - El adjunto es el archivo del repositorio, con la misma huella.
  - Tope `digsa.reportes.adjunto-max-mb` = 10, con 422. La generación se corta al pasar de 10 MB, antes del SMTP (R.39).
  - El envío pasa por la cola y el cupo de las descargas, y la petición espera el resultado: el comité verifica la llegada en el
    momento (simulacro, ítems 5 a 9, 13 y 15).
- **Generales y operativos.** Los generales solo agregados; los operativos con detalle, solo para los perfiles autorizados.
- **Proveedores nuevos** (aditivos, sin cambiar la lógica de los módulos): dashboard y riesgo en tablero, logs en auditoria y los
  cuatro de presupuesto (autorizado por la sesión 1).
  - Se conservan sus endpoints y el periodo de 92 días de los logs con texto. El tope de 200 000 filas ya no aplica en el motor (R.26).
  - Los filtros de un valor de tableros y presupuesto quedan provisionales (R.45).
- **Bitácora completa.** Filtros multivalor y los 4 eventos.
- **Pruebas.** API de envío (varios destinatarios, dominio no permitido) y e2e de envío, dashboard, riesgo y logs. La prueba del tope
  del adjunto pasó a R5 (R.50).

## R5 · Rendimiento, volumen y cierre · hecha, en el índice sin commit

- **Índices.** ~~V52 crea los índices de las consultas agregadas~~. No hizo falta ninguno: las consultas usan `ix_visitas_fecha`,
  `ix_visitas_punto` y los de la bitácora. No se creó V53 (R.51). El único candidato es un GIN trigram para el texto de los logs,
  si la prueba de noviembre lo pide (R.48).
- **Prueba de volumen.** `VolumenReportesApiTest`, fuera del ciclo rápido: corre solo con `-Ddigsa.volumen=true` (filas con
  `-Ddigsa.volumen.filas=N`, 1.500.000 por defecto) y deja los tiempos en `target/volumen-reportes.txt`. Con 1,5 M visitas en un
  trimestre: ver ≤ 66 s y exportar el detalle entre 3 min 8 s (CSV) y 6 min 45 s (PDF), todo dentro de 2.7. El correo del detalle
  responde 422 al pasar de 10 MB. Tabla completa en `dudas_pendientes.md`, R5.
- **Ajustes de rendimiento.** La cinta temporal va comprimida (R.54) y los anchos de columna del PDF siguen al contenido (R.55).
- **Tiempos para el Administrador.** Pestaña «Tiempos» en Reportes: p50, p95 y máximo por reporte, en pantalla y por formato, y
  cuántas pasaron el 80 % del plazo, en 1, 7, 30 o 90 días (`GET /api/reportes/tiempos`, R.53).
- **Exportación completa.** `reportes_generados` está en `Exportacion.TABLAS`.
- **Documentación.** README, guía y `dudas_pendientes.md`, con doc-updater.
- **Pruebas.** Batería completa en 5 navegadores y ArquitecturaTest.

## Archivos compartidos (cambios mínimos, aditivos y en un bloque comentado)

- `reglas/Bitacora.TIPOS`: `reporte_generado`, `reporte_verificado`, `reporte_solicitado`, `reporte_vencido` y `reporte_fallido`.
- `Pantallas.java`: ninguna pantalla nueva; la verificación va dentro de REPORTES. Solo se cambia si hiciera falta.
- `App.tsx`: ~~rutas de Descargas del día y Verificar~~. No se tocó: «Descargas del día», «Verificar reporte» y «Tiempos» son
  pestañas de `Reportes.tsx`.
- `reglas/Exportacion.TABLAS`: `reportes_generados`.
- `backend/app/pom.xml`: dependencia `bcprov-jdk18on`.
- tablero, presupuesto y auditoria: solo los proveedores.
