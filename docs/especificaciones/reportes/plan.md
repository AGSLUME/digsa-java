# Plan de fases: módulo de reportes

> Rama `reportes` (worktree `digsa-java-reportes`, desde develop fe9c98e). Especificación: [spec.md](spec.md).
> Migraciones V50 a V59. Un commit por fase, con revisión de security-reviewer, java-reviewer, react-reviewer, database-reviewer,
> silent-failure-hunter, pr-test-analyzer y performance-optimizer. Los P1 se corrigen antes del commit.

## R1 · Motor, contrato, catálogo y filtros

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

## R2 · Formatos en flujo, encabezado 2.9 e integridad 2.10

- **Formatos en flujo.** `Exportador` agrega variantes que escriben a un `OutputStream` desde un cursor. Los métodos `byte[]` actuales
  se conservan para sus usos.
- **Encabezado 2.9** en los 4 formatos: título, periodo, generado, usuario, establecimiento, filtros, fuente, registros, código y página.
  - En PDF, «Página N de M» en cada hoja; en Excel, en el pie de impresión.
  - Con muchas opciones elegidas, el archivo lleva la lista completa (en Excel, una hoja «Filtros»).
- **Integridad.**
  - V50 crea `reportes_generados`.
  - Huella de los datos: SHA-256 canónico, sin usuario, fecha ni formato. Va impresa en el archivo.
  - Huella del archivo: SHA-256 de los bytes finales.
  - Código de verificación `RPT-aaaammdd-XXXXXX`.
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

## R3 · Descargas del día, diferido, limpieza y tiempos

- **Repositorio diario.** V51 agrega el estado, el archivo en el almacén, la vigencia hasta las 23:59:59 de Bogotá y los tiempos
  (consulta, archivo y total).
- **Diferido.** Se reutiliza el patrón de `exportacion`:
  - `FOR UPDATE SKIP LOCKED`, candado y ejecutor acotado (2 en curso por usuario, 4 por instancia);
  - si la generación pasa de unos 15 s, responde 202 y la fila queda «Generando»;
  - la pantalla avisa cuando está lista.
- **Descargas.** Solo las ve quien las pidió (404 para otro usuario). Un reporte pedido igual el mismo día reutiliza el archivo.
- **Limpieza.** Tarea `@Scheduled` con candado: al terminar el día borra el archivo y conserva el registro. Las «generando» huérfanas
  pasan a fallida.
- **Tiempos.** Corte a 3 min (ver) y 10 min (exportar), alerta operativa al 80 % y tiempo visible al usuario.
- **Pantalla.** «Descargas del día», como en el mockup.
- **Pruebas.** API con RelojMovible (vigencia y limpieza), diferido, acceso ajeno y e2e.

## R4 · Correo, generales y operativos, tablero y logs en el motor

- **Correo.**
  - Hasta 15 destinatarios, con los dominios permitidos.
  - El adjunto es el archivo del repositorio, con la misma huella.
  - Tope `digsa.reportes.adjunto-max-mb` = 10, con 422 antes del SMTP.
  - Envío síncrono, porque el comité verifica la llegada en el momento (simulacro, ítems 5 a 9, 13 y 15).
- **Generales y operativos.** Los generales solo agregados; los operativos con detalle, solo para los perfiles autorizados.
- **Proveedores nuevos** (aditivos, sin cambiar la lógica de los módulos): dashboard y riesgo en tablero/ejecucion, logs en auditoria.
  - Se conservan sus endpoints, el tope de 200 000 filas y el periodo de 92 días de los logs.
- **Bitácora completa.** Filtros multivalor y los 4 eventos.
- **Pruebas.** API de envío (varios destinatarios, dominio no permitido y tope) y e2e de envío, dashboard, riesgo y logs.

## R5 · Rendimiento, volumen y cierre

- **Índices.** V52 crea los índices de las consultas agregadas (visitas por fecha, punto y estado; bitácora por fecha).
- **Prueba de volumen.** Con 1,5 a 2 millones de registros sintéticos (el tamaño del insumo de la prueba en sitio). Debe demostrar
  ≤ 3 min al ver y ≤ 10 min al exportar en los 4 formatos. Va etiquetada, fuera del ciclo rápido.
- **Exportación completa.** Las tablas `reportes_generados` se agregan a `Exportacion.TABLAS`.
- **Documentación.** README, guía y `dudas_pendientes.md`, con doc-updater.
- **Pruebas.** Batería completa en 5 navegadores y ArquitecturaTest.

## Archivos compartidos (cambios mínimos, aditivos y en un bloque comentado)

- `reglas/Bitacora.TIPOS`: `reporte_generado` y `reporte_verificado`.
- `Pantallas.java`: ninguna pantalla nueva; la verificación va dentro de REPORTES. Solo se cambia si hiciera falta.
- `App.tsx`: rutas de Descargas del día y Verificar.
- `reglas/Exportacion.TABLAS`: `reportes_generados`.
- `backend/app/pom.xml`: dependencia `bcprov-jdk18on`.
- tablero, ejecucion y auditoria: solo los proveedores.
