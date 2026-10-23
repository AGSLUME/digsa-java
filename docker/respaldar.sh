#!/usr/bin/env bash
# Suite DIGSA · respaldo de la base y de los archivos, juntos (Anexo 5, 1.23 y 3.6). Equivalente: docker/respaldar.ps1.
#
# Uso, en la carpeta del despliegue (la que tiene docker-compose.yml y .env), sin detener la aplicación:
#   docker/respaldar.sh
# Programarlo una vez al día como mínimo (cron o el programador de tareas del servidor). Termina con un código distinto de 0 si algo
# falla, para que el programador alerte. Deja en respaldos/ (o en DIGSA_RESPALDOS):
#   digsa_<F>.dump       base, formato propio de pg_dump, comprimido
#   archivos_<F>.tgz     evidencias, actas y soportes del OPL (/datos/archivos)
#   conteos_<F>.csv      filas por tabla del respaldo (herramientas/verificar_restauracion.sql), para comparar al restaurar
#   respaldo_<F>.info    versión de la imagen, commit y fecha
#   respaldo_<F>.sha256  huellas de los anteriores (sha256sum -c, dentro de respaldos/)
#   bitacora_respaldos.csv  una línea por respaldo o restauración (fecha;operación;versión;archivos;tamaños;huellas;resultado)
# Orden: primero la base y después los archivos; así todo archivo que la base referencia está en la copia (a lo sumo sobran
# archivos huérfanos). Antes de dar el respaldo por bueno, el .dump se restaura en una base temporal y se cuentan sus filas
# (DIGSA_RESPALDO_SIN_PRUEBA=1 lo omite en bases muy grandes).
# La copia se lleva a un medio y una ubicación EN COLOMBIA distintos del servidor. La exportación completa no es un respaldo.
#
# Variables opcionales: DIGSA_RESPALDOS (carpeta), DIGSA_RESPALDO_MARCA (marca de tiempo, por defecto AAAAMMDD_HHMM),
# DIGSA_RESPALDO_RETENER_DIAS (borra de la carpeta los respaldos más viejos; por defecto no borra nada).
set -Eeuo pipefail

DIR=$(cd "$(dirname "$0")/.." && pwd)
cd "$DIR"
RESPALDOS="${DIGSA_RESPALDOS:-$DIR/respaldos}"
F="${DIGSA_RESPALDO_MARCA:-$(date +%Y%m%d_%H%M)}"
BITACORA="$RESPALDOS/bitacora_respaldos.csv"
mkdir -p "$RESPALDOS"

etapa="inicio"
huella() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
tamano() { wc -c < "$1" | tr -d ' '; }

anotar() {
  [ -f "$BITACORA" ] || echo "fecha;operacion;version;archivos;tamanos;huellas;resultado" > "$BITACORA"
  echo "$(date '+%Y-%m-%d %H:%M:%S');respaldo;${version:-};$1;$2;$3;$4" >> "$BITACORA"
}

al_fallar() {
  codigo=$?
  # En una subcapa ( … ) solo se sale: el aviso y la bitácora los escribe una vez la capa principal
  [ "${BASHPID:-$$}" = "$$" ] || exit "${codigo:-1}"
  echo "ERROR: el respaldo $F falló en la etapa «$etapa»." >&2
  anotar "" "" "" "falló: $etapa"
  docker compose exec -T db rm -f /tmp/digsa.dump /tmp/conteos.csv >/dev/null 2>&1 || true
  docker compose exec -T app rm -f /tmp/archivos.tgz >/dev/null 2>&1 || true
  exit "${codigo:-1}"
}
trap al_fallar ERR

[ -f docker-compose.yml ] || { etapa="carpeta sin docker-compose.yml"; false; }

etapa="base no disponible"
docker compose exec -T db sh -c 'pg_isready -q -U "$POSTGRES_USER" -d digsa'

etapa="versión de la imagen"
version=""
contenedor=$(docker compose ps -q app 2>/dev/null || true)
if [ -n "$contenedor" ]; then
  imagen=$(docker inspect --format '{{.Config.Image}}' "$contenedor")
  revision=$(docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$contenedor" 2>/dev/null || true)
  version="$imagen${revision:+ ($revision)}"
fi

echo "==> 1. Base (pg_dump dentro del contenedor y copia afuera)"
etapa="copia de la base"
docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d digsa -Fc -Z 6 -f /tmp/digsa.dump && pg_restore -l /tmp/digsa.dump > /dev/null'

etapa="prueba del respaldo de la base"
docker compose cp herramientas/verificar_restauracion.sql db:/tmp/verificar_restauracion.sql
if [ -z "${DIGSA_RESPALDO_SIN_PRUEBA:-}" ]; then
  echo "    restauración de prueba en una base temporal y conteo de filas"
  docker compose exec -T db sh -c '
    set -e
    dropdb -U "$POSTGRES_USER" --if-exists digsa_prueba_respaldo
    createdb -U "$POSTGRES_USER" -O "$POSTGRES_USER" digsa_prueba_respaldo
    pg_restore -U "$POSTGRES_USER" -d digsa_prueba_respaldo --exit-on-error --single-transaction /tmp/digsa.dump
    psql -X -q -At -F ";" -U "$POSTGRES_USER" -d digsa_prueba_respaldo -f /tmp/verificar_restauracion.sql -o /tmp/conteos.csv
    dropdb -U "$POSTGRES_USER" digsa_prueba_respaldo'
else
  echo "    AVISO: sin restauración de prueba (DIGSA_RESPALDO_SIN_PRUEBA); conteos de la base en uso"
  docker compose exec -T db sh -c 'psql -X -q -At -F ";" -U "$POSTGRES_USER" -d digsa -f /tmp/verificar_restauracion.sql -o /tmp/conteos.csv'
fi

etapa="copia de la base fuera del contenedor"
docker compose cp db:/tmp/digsa.dump "$RESPALDOS/digsa_$F.dump"
docker compose cp db:/tmp/conteos.csv "$RESPALDOS/conteos_$F.csv"
docker compose exec -T db rm -f /tmp/digsa.dump /tmp/conteos.csv /tmp/verificar_restauracion.sql

echo "==> 2. Archivos (con el usuario de la aplicación)"
etapa="aplicación no disponible para copiar los archivos"
docker compose exec -T app tar -czf /tmp/archivos.tgz -C /datos archivos
etapa="copia de los archivos fuera del contenedor"
docker compose cp app:/tmp/archivos.tgz "$RESPALDOS/archivos_$F.tgz"
docker compose exec -T app rm -f /tmp/archivos.tgz

echo "==> 3. Huellas y bitácora de respaldos"
etapa="huellas"
{
  echo "fecha=$(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "version=$version"
  echo "DIGSA_VERSION=$(sed -n 's/^DIGSA_VERSION=//p' .env 2>/dev/null | tr -d '\r' | head -n 1)"
} > "$RESPALDOS/respaldo_$F.info"
(cd "$RESPALDOS" && huella "digsa_$F.dump" "archivos_$F.tgz" "conteos_$F.csv" "respaldo_$F.info" > "respaldo_$F.sha256")
h_db=$(cd "$RESPALDOS" && huella "digsa_$F.dump" | cut -c1-64)
h_ar=$(cd "$RESPALDOS" && huella "archivos_$F.tgz" | cut -c1-64)
anotar "digsa_$F.dump archivos_$F.tgz" "$(tamano "$RESPALDOS/digsa_$F.dump") $(tamano "$RESPALDOS/archivos_$F.tgz")" "$h_db $h_ar" "ok"

if [ -n "${DIGSA_RESPALDO_RETENER_DIAS:-}" ]; then
  etapa="retención"
  echo "    retención: se borran de $RESPALDOS los respaldos de más de $DIGSA_RESPALDO_RETENER_DIAS días"
  find "$RESPALDOS" -maxdepth 1 -type f \( -name 'digsa_*.dump' -o -name 'archivos_*.tgz' -o -name 'conteos_*.csv' \
    -o -name 'respaldo_*.info' -o -name 'respaldo_*.sha256' \) -mtime +"$DIGSA_RESPALDO_RETENER_DIAS" -print -delete
fi

trap - ERR
echo "Respaldo $F listo en $RESPALDOS (base $(tamano "$RESPALDOS/digsa_$F.dump") bytes, archivos $(tamano "$RESPALDOS/archivos_$F.tgz") bytes)."
echo "Lleve digsa_$F.dump, archivos_$F.tgz, conteos_$F.csv, respaldo_$F.info y respaldo_$F.sha256 al medio de custodia."
