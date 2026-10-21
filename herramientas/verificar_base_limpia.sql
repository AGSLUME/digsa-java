-- Suite DIGSA · Verificación de la base antes de la prueba en sitio (Anexo 5, condiciones generales, numeral 4):
-- "la base de datos no debe tener ninguna información registrada en las tablas maestras del proceso de auditoría; únicamente la
-- información de las tablas paramétricas (departamentos, municipios, ESM, ...)".
--
-- Uso:  psql "$DIGSA_DB_URL" -f herramientas/verificar_base_limpia.sql
-- Resultado esperado: todas las tablas del proceso con 0 filas y las paramétricas con su carga. Los usuarios: solo los administradores
-- creados para la prueba (la bitácora tendrá sus ingresos).

SELECT 'PROCESO (debe ser 0)' AS grupo, tabla, filas FROM (
    SELECT 'visitas' AS tabla, count(*) AS filas FROM visitas
    UNION ALL SELECT 'visitas_auditores', count(*) FROM visitas_auditores
    UNION ALL SELECT 'visitas_componentes', count(*) FROM visitas_componentes
    UNION ALL SELECT 'visitas_reparto', count(*) FROM visitas_reparto
    UNION ALL SELECT 'visitas_historial', count(*) FROM visitas_historial
    UNION ALL SELECT 'visitas_notificaciones', count(*) FROM visitas_notificaciones
    UNION ALL SELECT 'visitas_ejecucion', count(*) FROM visitas_ejecucion
    UNION ALL SELECT 'visitas_personal', count(*) FROM visitas_personal
    UNION ALL SELECT 'visitas_respuestas', count(*) FROM visitas_respuestas
    UNION ALL SELECT 'visitas_firmas', count(*) FROM visitas_firmas
    UNION ALL SELECT 'visitas_actas', count(*) FROM visitas_actas
    UNION ALL SELECT 'visitas_resumen', count(*) FROM visitas_resumen
    UNION ALL SELECT 'archivos', count(*) FROM archivos
    UNION ALL SELECT 'auditores', count(*) FROM auditores
) p
UNION ALL
SELECT 'PARAMÉTRICAS (con carga)', tabla, filas FROM (
    SELECT 'par_departamentos' AS tabla, count(*) AS filas FROM par_departamentos
    UNION ALL SELECT 'par_municipios', count(*) FROM par_municipios
    UNION ALL SELECT 'par_fuerzas', count(*) FROM par_fuerzas
    UNION ALL SELECT 'par_regionales', count(*) FROM par_regionales
    UNION ALL SELECT 'par_tipos_visita', count(*) FROM par_tipos_visita
    UNION ALL SELECT 'par_parametros', count(*) FROM par_parametros
    UNION ALL SELECT 'catalogo_valores', count(*) FROM catalogo_valores
    UNION ALL SELECT 'puntos (ESM y puntos alternos)', count(*) FROM puntos
    UNION ALL SELECT 'chk_componentes', count(*) FROM chk_componentes
    UNION ALL SELECT 'chk_items', count(*) FROM chk_items
) q
UNION ALL
SELECT 'USUARIOS', 'usuarios', count(*) FROM usuarios
ORDER BY 1, 2;
