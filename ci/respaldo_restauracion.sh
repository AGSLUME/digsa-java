#!/usr/bin/env bash
# Suite DIGSA · trabajo «respaldo» (cada noche y en cada etiqueta vX.Y.Z): prueba de ida y vuelta del respaldo con los MISMOS
# scripts que usará TI (docker/respaldar.sh y docker/restaurar.sh), sobre la imagen del trabajo «imagen».
#   1. compose con la imagen probada (entorno local: el código del segundo factor sale en la respuesta y no hay captcha)
#   2. datos ficticios: un administrador, una visita y dos evidencias con su SHA-256 en /datos/archivos
#   3. el administrador ingresa (deja filas en la bitácora)
#   4. docker/respaldar.sh
#   5. docker compose down -v (se destruyen la base y los archivos)
#   6. docker/restaurar.sh: huellas, filas por tabla iguales, archivos con su SHA-256, aplicación sana
#   7. el administrador vuelve a ingresar y las dos evidencias siguen ahí
# Requiere Docker y el plugin compose. DIGSA_PUERTO_RESPALDO (18081) cambia el puerto local.
. "$(dirname "$0")/comun.sh"
. "$(dirname "$0")/despliegue_prueba.sh"

tiene docker || falla "Este paso necesita Docker (ejecutor Linux del pipeline)."
tiene curl || falla "Falta curl."
cd "$RAIZ"

entrada="$ARTEFACTOS/imagen"
if [ -f "$entrada/imagen.env" ]; then
  # shellcheck disable=SC1091
  . "$entrada/imagen.env"
  if ! docker image inspect "$DIGSA_IMAGEN" >/dev/null 2>&1; then
    info "Carga de la imagen probada ($DIGSA_IMAGEN)"
    docker load -i "$entrada/digsa-suite.tar" >/dev/null
  fi
  id=$(docker image inspect --format '{{.Id}}' "$DIGSA_IMAGEN")
  [ "$id" = "$DIGSA_IMAGEN_ID" ] || falla "La imagen cargada no es la que probó el trabajo «imagen»."
else
  DIGSA_VERSION=$(version_imagen)
  docker image inspect "digsa-suite:$DIGSA_VERSION" >/dev/null 2>&1 \
    || falla "No está la imagen digsa-suite:$DIGSA_VERSION ni artefactos/imagen/ (corra antes ci/imagen.sh)."
fi

PUERTO="${DIGSA_PUERTO_RESPALDO:-18081}"
BASE_URL="http://localhost:$PUERTO"
ADMIN_USUARIO="admin.ci"
ADMIN_CLAVE="Ci.$(clave_aleatoria).9z"
FRASCO=$(mktemp)

info "1. Despliegue de prueba con digsa-suite:$DIGSA_VERSION"
preparar_despliegue respaldo "$DIGSA_VERSION" local "$PUERTO"
trap 'estado=$?; [ $estado -eq 0 ] || registros_aplicacion; retirar_despliegue; rm -f "$FRASCO"; exit $estado' EXIT
levantar ""

info "2. Datos ficticios: administrador, visita y dos evidencias"
# La clave se cifra con BCrypt en PostgreSQL (pgcrypto, solo dentro de esta transacción); Spring acepta el prefijo $2a$
sql_dueno "BEGIN;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
INSERT INTO usuarios (nombre, usuario, correo, clave_hash, clave_definida, clave_vence, documento)
  VALUES ('Administrador de la prueba de respaldo', '$ADMIN_USUARIO', '$ADMIN_USUARIO@example.com',
          crypt('$ADMIN_CLAVE', gen_salt('bf', 12)), true, now() + interval '30 days', '0000');
DROP EXTENSION pgcrypto;
INSERT INTO usuarios_perfiles (usuario_id, perfil_id) SELECT id, 1 FROM usuarios WHERE usuario = '$ADMIN_USUARIO';
INSERT INTO visitas (tipo, punto_id, fecha, auditor_id, alcance, creado_por)
  SELECT 'inicial', (SELECT min(id) FROM puntos), current_date, u.id, 'Prueba de respaldo y restauración (datos ficticios)', u.id
  FROM usuarios u WHERE u.usuario = '$ADMIN_USUARIO';
COMMIT;" >/dev/null
visita=$(sql_dueno "SELECT max(id) FROM visitas")
for n in 1 2; do
  contenido="Evidencia ficticia $n de la prueba de respaldo · $(date -u +%Y-%m-%dT%H:%M:%SZ) · $(clave_aleatoria)"
  sha=$(printf '%s' "$contenido" | huella | cut -c1-64)
  tamano=$(printf '%s' "$contenido" | wc -c | tr -d ' ')
  printf '%s' "$contenido" | dc exec -T app sh -c "mkdir -p /datos/archivos/ci && cat > /datos/archivos/ci/evidencia_$n.txt"
  sql_dueno "INSERT INTO archivos (visita_id, clave, nombre_original, ruta, tipo, tamano, sha256, subido_por)
    SELECT $visita, 'visita', 'evidencia_$n.txt', 'ci/evidencia_$n.txt', 'text/plain', $tamano, '$sha', id
    FROM usuarios WHERE usuario = '$ADMIN_USUARIO'" >/dev/null
done
echo "Visita $visita con 2 evidencias."

# Ingreso por la API, como la SPA: token CSRF de la cookie, usuario y clave, código del segundo factor (en local viene en el mensaje)
ingresar() {
  rm -f "$FRASCO"
  curl -fsS -c "$FRASCO" -b "$FRASCO" "$BASE_URL/api/auth/inicio" >/dev/null
  xsrf=$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$FRASCO" | tail -n 1)
  [ -n "$xsrf" ] || falla "No llegó la cookie XSRF-TOKEN."
  paso=$(curl -fsS -c "$FRASCO" -b "$FRASCO" -H "X-XSRF-TOKEN: $xsrf" -H 'Content-Type: application/json' \
    -d "{\"usuario\":\"$ADMIN_USUARIO\",\"clave\":\"$ADMIN_CLAVE\",\"captcha\":\"\"}" "$BASE_URL/api/auth/ingreso") \
    || falla "El administrador no pudo ingresar (usuario y clave)."
  codigo=$(printf '%s' "$paso" | sed -n 's/.*(local: \([A-Za-z0-9]*\)).*/\1/p')
  [ -n "$codigo" ] || falla "La respuesta del ingreso no trae el código local del segundo factor."
  xsrf=$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$FRASCO" | tail -n 1)
  curl -fsS -c "$FRASCO" -b "$FRASCO" -H "X-XSRF-TOKEN: $xsrf" -H 'Content-Type: application/json' \
    -d "{\"codigo\":\"$codigo\"}" "$BASE_URL/api/auth/codigo" >/dev/null || falla "El código del segundo factor no fue aceptado."
  curl -fsS -c "$FRASCO" -b "$FRASCO" "$BASE_URL/api/sesion" | grep -q "$ADMIN_USUARIO"     || falla "La sesión del administrador no quedó abierta."
  # Sale: la sesión es única por usuario y el segundo ingreso (después de restaurar) no debe chocar con esta
  xsrf=$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$FRASCO" | tail -n 1)
  curl -fsS -c "$FRASCO" -b "$FRASCO" -H "X-XSRF-TOKEN: $xsrf" -X POST "$BASE_URL/api/auth/salir" >/dev/null     || aviso "No se pudo cerrar la sesión de prueba."
}

info "3. Ingreso del administrador"
ingresar
echo "El administrador ingresó."

info "4. Respaldo con docker/respaldar.sh"
F=$(date +%Y%m%d_%H%M%S)
DIGSA_RESPALDO_MARCA="$F" bash "$DESPLIEGUE/docker/respaldar.sh"
grep -q ";ok$" "$DESPLIEGUE/respaldos/bitacora_respaldos.csv" || falla "La bitácora de respaldos no registró «ok»."

info "5. Se destruyen la base y los archivos (docker compose down -v)"
dc down -v
levantar "" db

info "6. Restauración con docker/restaurar.sh"
bash "$DESPLIEGUE/docker/restaurar.sh" "$F" --si

info "7. Comprobaciones después de restaurar"
ingresar
echo "El administrador ingresó de nuevo."
evidencias=$(sql_dueno "SELECT count(*) FROM archivos WHERE visita_id = $visita AND eliminado IS NULL")
[ "$evidencias" = "2" ] || falla "Después de restaurar hay $evidencias evidencias de la visita $visita (se esperaban 2)."
bitacora=$(sql_dueno "SELECT count(*) FROM log_transacciones")
[ "$bitacora" -gt 0 ] || falla "La bitácora quedó vacía después de restaurar."
mkdir -p "$ARTEFACTOS/respaldo"
cp "$DESPLIEGUE/respaldos/bitacora_respaldos.csv" "$DESPLIEGUE/respaldos/conteos_$F.csv" "$ARTEFACTOS/respaldo/"
info "Respaldo y restauración probados: mismas filas por tabla, 2 evidencias con su SHA-256, bitácora con $bitacora filas e ingreso del administrador."
