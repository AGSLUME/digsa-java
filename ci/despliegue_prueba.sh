# Suite DIGSA · despliegue de prueba con docker compose para ci/imagen.sh y ci/respaldo_restauracion.sh. Se carga con
# «. ci/despliegue_prueba.sh» después de comun.sh; no se ejecuta sola.
# Arma una carpeta igual al paquete que recibe TI (docker-compose.yml, docker/, herramientas/ y un .env), así la prueba usa los
# mismos archivos y comandos que el servidor. El .env tiene claves aleatorias, vive fuera del repositorio y se borra al terminar.
# shellcheck shell=bash

DESPLIEGUE=""

# preparar_despliegue <nombre> <versión> <entorno: pruebas|local> <puerto>
preparar_despliegue() {
  nombre=$1; version=$2; entorno=$3; puerto=$4
  base="${RUNNER_TEMP:-${AGENT_TEMPDIRECTORY:-${TMPDIR:-/tmp}}}"
  DESPLIEGUE="$base/digsa-$nombre"
  rm -rf "$DESPLIEGUE"
  mkdir -p "$DESPLIEGUE"
  cp "$RAIZ/docker-compose.yml" "$DESPLIEGUE/"
  cp -R "$RAIZ/docker" "$RAIZ/herramientas" "$DESPLIEGUE/"
  chmod +x "$DESPLIEGUE"/docker/*.sh "$DESPLIEGUE"/docker/postgres/*.sh 2>/dev/null || true
  export COMPOSE_PROJECT_NAME="digsa-$nombre"
  {
    echo "DIGSA_VERSION=$version"
    echo "DIGSA_DB_DUENO=digsa_dueno"
    echo "DIGSA_DB_DUENO_CLAVE=$(clave_aleatoria)"
    echo "DIGSA_DB_USUARIO=digsa_app"
    echo "DIGSA_DB_CLAVE=$(clave_aleatoria)"
    echo "DIGSA_ENTORNO=$entorno"
    echo "DIGSA_PUERTO=$puerto"
    echo "DIGSA_ESCUCHA=127.0.0.1"
    echo "DIGSA_SESION_MINUTOS=10"
    if [ "$entorno" = "local" ]; then
      echo "DIGSA_URL=http://localhost:$puerto/"
      echo "DIGSA_CORREO_MODO=archivo"
    else
      # Mailpit del perfil «pruebas» recibe todos los correos; la URL https solo cumple la validación de arranque
      echo "DIGSA_URL=https://digsa-ci.example.com/"
      echo "DIGSA_CORREO_MODO=smtp"
      echo "DIGSA_CORREO_REMITENTE=notificaciones@example.com"
      echo "DIGSA_SMTP_HOST=correo"
      echo "DIGSA_SMTP_PUERTO=1025"
      echo "DIGSA_DOMINIOS_REPORTES=example.com"
    fi
  } > "$DESPLIEGUE/.env"
  chmod 600 "$DESPLIEGUE/.env"
}

dc() { (cd "$DESPLIEGUE" && docker compose "$@"); }

# Consulta SQL como dueño de la base (la sentencia va como argumento, sin pasar por las comillas de la consola)
sql_dueno() {
  dc exec -T db sh -c 'psql -v ON_ERROR_STOP=1 -X -q -At -U "$POSTGRES_USER" -d digsa -c "$1"' sh "$1"
}

registros_aplicacion() {
  [ -n "$DESPLIEGUE" ] || return 0
  echo "---- últimas líneas del log de la aplicación ----" >&2
  dc logs --no-color --tail 200 app >&2 2>/dev/null || true
}

# Baja el despliegue y borra sus volúmenes y su .env
retirar_despliegue() {
  [ -n "$DESPLIEGUE" ] && [ -d "$DESPLIEGUE" ] || return 0
  dc --profile pruebas down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$DESPLIEGUE"
}

# levantar <perfil o ""> [servicios…]: espera a que queden «healthy» (healthcheck de la imagen y de PostgreSQL)
levantar() {
  perfil=$1; shift
  if ! dc ${perfil:+--profile "$perfil"} up -d --no-build --wait --wait-timeout "${DIGSA_ESPERA_SEGUNDOS:-300}" "$@"; then
    registros_aplicacion
    falla "Los contenedores no quedaron sanos (healthy) a tiempo."
  fi
}
