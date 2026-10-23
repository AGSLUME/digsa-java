#!/usr/bin/env bash
# Suite DIGSA · restauración de un respaldo de docker/respaldar.sh (base y archivos, juntos). Equivalente: docker/restaurar.ps1.
#
# Uso, en la carpeta del despliegue (la que tiene docker-compose.yml y .env), con la MISMA versión de la imagen del respaldo o una
# posterior (nunca una anterior: no conoce las migraciones nuevas):
#   docker/restaurar.sh 20261122_0100          # pide confirmación
#   docker/restaurar.sh 20261122_0100 --si     # sin preguntar (pipeline, guion de contingencia)
# Los archivos del respaldo deben estar en respaldos/ (o en DIGSA_RESPALDOS).
#
# Pasos: 1. huellas; 2. detiene la aplicación; 3. base vacía y pg_restore en una sola transacción; 4. filas por tabla iguales a
# las del respaldo; 5. archivos (se vacía /datos/archivos y se extrae con el usuario de la aplicación); 6. arranca la aplicación
# (Flyway valida y, si la imagen es más nueva, migra) y espera «healthy»; 7. cada archivo de la base existe y su SHA-256 coincide;
# 8. bitácora de respaldos («restauracion»).
# El rol de la aplicación (DIGSA_DB_USUARIO) debe existir con el mismo nombre del respaldo: lo crea docker/postgres/01-rol-app.sh
# en un volumen nuevo. Si el nombre cambió, ver herramientas/permisos_rol_app.sql y la guía de operación.
# DIGSA_VERIFICAR_CON_APP=1: el paso 7 usa además el modo de la aplicación --digsa.verificar-archivos.
set -Eeuo pipefail

F="${1:-}"
CONFIRMADO="${2:-}"
[ -n "$F" ] || { echo "Uso: docker/restaurar.sh <AAAAMMDD_HHMM> [--si]" >&2; exit 1; }
case "$F" in *[!0-9_]*) echo "ERROR: marca de respaldo no válida: $F" >&2; exit 1 ;; esac

DIR=$(cd "$(dirname "$0")/.." && pwd)
cd "$DIR"
RESPALDOS="${DIGSA_RESPALDOS:-$DIR/respaldos}"
BITACORA="$RESPALDOS/bitacora_respaldos.csv"
etapa="inicio"
version_actual=$(sed -n 's/^DIGSA_VERSION=//p' .env 2>/dev/null | tr -d '\r' | head -n 1)

huella() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
anotar() {
  [ -f "$BITACORA" ] || echo "fecha;operacion;version;archivos;tamanos;huellas;resultado" > "$BITACORA"
  echo "$(date '+%Y-%m-%d %H:%M:%S');restauracion;$version_actual;digsa_$F.dump archivos_$F.tgz;;;$1" >> "$BITACORA"
}
al_fallar() {
  codigo=$?
  # En una subcapa ( … ) solo se sale: el aviso y la bitácora los escribe una vez la capa principal
  [ "${BASHPID:-$$}" = "$$" ] || exit "${codigo:-1}"
  echo "ERROR: la restauración de $F falló en la etapa «$etapa». Si la aplicación ya se había detenido, sigue detenida hasta resolverlo." >&2
  anotar "falló: $etapa"
  exit "${codigo:-1}"
}
trap al_fallar ERR

echo "==> 1. Huellas del respaldo $F"
etapa="faltan archivos del respaldo"
for a in "digsa_$F.dump" "archivos_$F.tgz" "respaldo_$F.sha256"; do
  [ -f "$RESPALDOS/$a" ] || { echo "ERROR: falta $RESPALDOS/$a" >&2; false; }
done
etapa="huellas distintas"
(cd "$RESPALDOS" && huella -c "respaldo_$F.sha256")

if [ -f "$RESPALDOS/respaldo_$F.info" ]; then
  version_respaldo=$(sed -n 's/^DIGSA_VERSION=//p' "$RESPALDOS/respaldo_$F.info" | head -n 1)
  echo "    respaldo hecho con la versión «${version_respaldo:-desconocida}»; este servidor tiene «${version_actual:-sin DIGSA_VERSION}»"
  etapa="la imagen es más vieja que el respaldo"
  if printf '%s' "$version_respaldo" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' \
     && printf '%s' "$version_actual" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    menor=$(printf '%s\n%s\n' "$version_respaldo" "$version_actual" | sort -V | head -n 1)
    if [ "$menor" != "$version_respaldo" ]; then
      echo "ERROR: el respaldo es de la $version_respaldo y la imagen es la $version_actual. Suba antes a la $version_respaldo o posterior." >&2
      false
    fi
  fi
fi

if [ "$CONFIRMADO" != "--si" ]; then
  printf 'Se REEMPLAZAN la base y los archivos de este servidor por el respaldo %s. Escriba «si» para seguir: ' "$F"
  read -r respuesta
  [ "$respuesta" = "si" ] || { echo "Cancelado: no se cambió nada."; trap - ERR; exit 1; }
fi

echo "==> 2. Se detiene la aplicación (nadie escribe)"
etapa="no se pudo detener la aplicación"
docker compose stop app
etapa="base no disponible"
docker compose up -d --wait db
etapa="no existe el rol de la aplicación"
docker compose exec -T db sh -c 'psql -X -q -At -U "$POSTGRES_USER" -d postgres -c "SELECT 1 FROM pg_roles WHERE rolname = '"'"'$DIGSA_DB_USUARIO'"'"'" | grep -q 1' \
  || { echo "ERROR: no existe el rol DIGSA_DB_USUARIO en PostgreSQL; créelo (docker/postgres/01-rol-app.sh) o vea herramientas/permisos_rol_app.sql." >&2; false; }

echo "==> 3. Base: vacía y pg_restore en una sola transacción"
etapa="restauración de la base"
docker compose cp "$RESPALDOS/digsa_$F.dump" db:/tmp/digsa.dump
docker compose exec -T db sh -c '
  set -e
  dropdb -U "$POSTGRES_USER" --if-exists --force digsa
  createdb -U "$POSTGRES_USER" -O "$POSTGRES_USER" digsa
  pg_restore -U "$POSTGRES_USER" -d digsa --exit-on-error --single-transaction /tmp/digsa.dump
  rm -f /tmp/digsa.dump'
# El permiso de conexión a la base no viaja en el formato propio de pg_dump: se vuelve a dar (las tablas sí traen los suyos)
docker compose exec -T db sh -c 'psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d digsa -c "GRANT CONNECT ON DATABASE digsa TO \"$DIGSA_DB_USUARIO\"; GRANT USAGE ON SCHEMA public TO \"$DIGSA_DB_USUARIO\";"'

echo "==> 4. Filas por tabla frente a las del respaldo"
etapa="conteos distintos a los del respaldo"
docker compose cp herramientas/verificar_restauracion.sql db:/tmp/verificar_restauracion.sql
docker compose exec -T db sh -c 'psql -X -q -At -F ";" -U "$POSTGRES_USER" -d digsa -f /tmp/verificar_restauracion.sql -o /tmp/conteos.csv'
docker compose cp db:/tmp/conteos.csv "$RESPALDOS/conteos_restaurado_$F.csv"
docker compose exec -T db rm -f /tmp/conteos.csv /tmp/verificar_restauracion.sql
if [ -f "$RESPALDOS/conteos_$F.csv" ]; then
  if ! diff "$RESPALDOS/conteos_$F.csv" "$RESPALDOS/conteos_restaurado_$F.csv" >&2; then
    echo "ERROR: las filas por tabla no coinciden con las del respaldo (arriba: < respaldo, > restaurado)." >&2
    false
  fi
  echo "    $(wc -l < "$RESPALDOS/conteos_$F.csv" | tr -d ' ') tablas con las mismas filas que el respaldo"
else
  echo "    AVISO: el respaldo no trae conteos_$F.csv; no se pudo comparar"
fi

echo "==> 5. Archivos (se vacía /datos/archivos y se extrae con el usuario de la aplicación)"
etapa="restauración de los archivos"
docker compose run --rm --no-deps -T -v "$RESPALDOS:/respaldos:ro" --entrypoint sh app \
  -c "find /datos/archivos -mindepth 1 -delete && tar -xzf /respaldos/archivos_$F.tgz -C /datos"

echo "==> 6. Arranque de la aplicación (Flyway valida; si la imagen es más nueva, migra)"
etapa="la aplicación no arrancó sana"
docker compose up -d --no-build --wait --wait-timeout "${DIGSA_ESPERA_SEGUNDOS:-300}" app

echo "==> 7. Cada archivo de la base existe y su SHA-256 coincide"
etapa="archivos faltantes o con huella distinta"
lista=$(mktemp)
docker compose exec -T db sh -c 'psql -X -q -At -U "$POSTGRES_USER" -d digsa -c "SELECT sha256 || '"'"'  '"'"' || ruta FROM archivos WHERE eliminado IS NULL UNION ALL SELECT sha256 || '"'"'  '"'"' || ruta FROM opl_soportes ORDER BY 1"' > "$lista"
total=$(grep -c . "$lista" || true)
if [ "$total" -gt 0 ]; then
  docker compose cp "$lista" app:/tmp/huellas_archivos.txt
  docker compose exec -T app sh -c 'cd /datos/archivos && sha256sum -c --quiet /tmp/huellas_archivos.txt; r=$?; rm -f /tmp/huellas_archivos.txt; exit $r'
fi
rm -f "$lista"
echo "    $total archivos verificados"
if [ -n "${DIGSA_VERIFICAR_CON_APP:-}" ]; then
  docker compose run --rm --no-deps app --digsa.verificar-archivos
fi

trap - ERR
anotar "ok"
echo "Restauración de $F terminada: base, $total archivos y aplicación en servicio. Ingrese con un usuario para confirmar."
