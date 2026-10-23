-- Suite DIGSA · restaurar un respaldo cuando el rol de la aplicación del servidor destino tiene OTRO nombre que el del respaldo
-- (por ejemplo, una base administrada donde el DBA creó «app_digsa» y el respaldo es de «digsa_app»).
--
-- Los permisos mínimos del rol (V2, V3, V6, V9 a V16: sin borrar la bitácora ni las firmas) viajan en el respaldo a nombre del
-- rol original. En lugar de reescribirlos a mano (y que se desactualicen con cada migración), se conserva ese rol como un grupo
-- sin ingreso y el rol nuevo se hace miembro: hereda exactamente los mismos permisos.
--
-- Paso 1, ANTES de pg_restore (como dueño o DBA, en la base destino ya creada):
--   psql -v ON_ERROR_STOP=1 -v paso=antes -v rol_respaldo=digsa_app -v rol_nuevo=app_digsa -d digsa -f herramientas/permisos_rol_app.sql
-- Paso 2, restauración sin dueños (el dueño pasa a ser quien restaura) pero CON los permisos:
--   pg_restore --no-owner --exit-on-error --single-transaction -d digsa digsa_<F>.dump
-- Paso 3, DESPUÉS de pg_restore:
--   psql -v ON_ERROR_STOP=1 -v paso=despues -v rol_respaldo=digsa_app -v rol_nuevo=app_digsa -d digsa -f herramientas/permisos_rol_app.sql
-- Luego, en el .env: DIGSA_DB_USUARIO (y DIGSA_DB_ROL_APP) con el rol nuevo, para que las migraciones futuras le den sus permisos.

\set ON_ERROR_STOP on
SELECT :'paso' = 'antes' AS es_antes, :'paso' = 'despues' AS es_despues \gset

\if :es_antes
    -- El rol del respaldo, como grupo sin ingreso (si ya existe, no se toca)
    SELECT format('CREATE ROLE %I NOLOGIN', :'rol_respaldo')
    WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'rol_respaldo') \gexec
\elif :es_despues
    SELECT format('GRANT %I TO %I', :'rol_respaldo', :'rol_nuevo')
    WHERE :'rol_respaldo' <> :'rol_nuevo' \gexec
    SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'rol_nuevo') \gexec
    SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'rol_nuevo') \gexec
    \echo 'Permisos del rol de la aplicación aplicados.'
\else
    \echo 'Indique -v paso=antes o -v paso=despues (ver el encabezado del archivo).'
    SELECT 1/0;
\endif
