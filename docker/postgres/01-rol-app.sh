#!/bin/bash
# Suite DIGSA · crea el rol de la aplicación con permisos mínimos (solo la primera vez que arranca la base vacía).
# El dueño (POSTGRES_USER) corre las migraciones; las migraciones V2, V10 y siguientes dan al rol solo lo necesario
# (sin borrar la bitácora ni las firmas). La clave llega por variable de entorno, nunca por el repositorio.
set -euo pipefail

: "${DIGSA_DB_USUARIO:?Falta DIGSA_DB_USUARIO}"
: "${DIGSA_DB_CLAVE:?Falta DIGSA_DB_CLAVE}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v usuario="$DIGSA_DB_USUARIO" -v clave="$DIGSA_DB_CLAVE" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'usuario', :'clave') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'usuario') \gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'usuario') \gexec
SQL
