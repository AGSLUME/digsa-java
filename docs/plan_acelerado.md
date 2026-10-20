# Plan acelerado hacia la prueba en sitio (decisión del 2026-10-20)

La prueba en sitio del Anexo 5 se hace entre el **11 y el 27 de noviembre de 2026**; el día exacto se publica dos días después del cierre de ofertas. Por decisión del usuario, la reescritura se acelera para llegar con lo que pide el protocolo. Las demás fases vuelven después de la prueba.

Cada sprint cierra igual:
1. pruebas en verde (JUnit, integración y API con PostgreSQL 16 real, Vitest y Playwright en los tres motores);
2. revisión de los agentes, con la prioridad 1 corregida antes del commit;
3. un commit;
4. las dudas en `docs/dudas_pendientes.md`;
5. un reporte.

| Sprint | Fechas | Protocolo del Anexo 5 | Contenido |
|---|---|---|---|
| **S1 · Plataforma y seguridad** | 20–23 oct | 2, 15, cond. 12 y 17 | Proyecto, ingreso con MFA por correo, bloqueo, contraseña por enlace, sesión única, inactividad, usuarios y perfiles desde la pantalla (incluido Analista), bitácora y logs con descarga en Excel o CSV y envío por correo desde la aplicación |
| **S2 · Catálogos y agenda** | 26–30 oct | 4, 11 | Catálogos editables en vivo con validaciones; puntos (ESM del anexo del OPL y 10 puntos alternos); checklist v6; crear la visita eligiendo los componentes; asignar cada componente a un auditor, que solo ve lo suyo; estados; calendario; seguimiento en tiempo real |
| **S3 · Formulario y acta** | 3–6 nov | 10, 12 | Lugar en lista; fecha y hora con controles que no son texto; número y auditor automáticos; objeto, personal auditado, hallazgos y evidencias múltiples en una petición; calificación; reabrir; acta PDF con firmas |
| **S4 · Reportes y ensayo** | 9–10 nov y margen | 13, cond. 4 y 17 | Tres reportes con encabezado, descarga en Excel, CSV, PDF y TXT y envío por correo; base solo con tablas paramétricas; despliegue en un equipo de AGS; ensayo general del protocolo |

**Después de la prueba:** notificación previa completa, solicitudes a necesidad, flujo del OPL, seguimiento, propuesta de agenda, riesgo, exportación completa, alertas por SMS, ACS Email, infraestructura como código y pipeline.

**Plan B:** la aplicación en PHP, que otra sesión está ajustando para la prueba.
