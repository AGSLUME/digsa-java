#!/usr/bin/env bash
# Suite DIGSA · trabajo «imagen»: construye la imagen UNA vez, la prueba con docker compose y la guarda para publicarla tal cual.
#   1. docker buildx build (etiquetas OCI: versión, commit, fecha, origen)
#   2. prueba de humo: compose con esa imagen, PostgreSQL 16 nuevo, DIGSA_ENTORNO=pruebas y Mailpit; espera «healthy»;
#      Flyway sin migraciones fallidas y con todas las del repositorio; /actuator/health en 200; / entrega la SPA;
#      verificar_base_limpia.sql en 0 para las tablas del proceso
#   3. docker save (artefactos/imagen/): el trabajo «publicar» y el de «respaldo» cargan ESTA imagen
#   4. Trivy de la imagen (CRITICAL y HIGH con corrección bloquean; sistema operativo en fin de vida bloquea; secretos embebidos)
#   5. SBOM CycloneDX (con licencias)
# Requiere Docker con buildx y el plugin compose (ejecutor Linux). Variables opcionales: DIGSA_BUILDX_OPCIONES (caché del
# orquestador), DIGSA_PUERTO_HUMO (18080).
. "$(dirname "$0")/comun.sh"
. "$(dirname "$0")/despliegue_prueba.sh"

tiene docker || falla "Este paso necesita Docker (ejecutor Linux del pipeline)."
docker compose version >/dev/null 2>&1 || falla "Falta el plugin «docker compose»."

cd "$RAIZ"
VERSION=$(version_imagen)
COMMIT=$(commit_largo)
CORTO=$(commit_corto)
FECHA=$(date -u +%Y-%m-%dT%H:%M:%SZ)
ORIGEN="${DIGSA_ORIGEN:-$(git config --get remote.origin.url 2>/dev/null | sed 's#://[^@/]*@#://#' || true)}"
IMAGEN="digsa-suite:$VERSION"
salida="$ARTEFACTOS/imagen"
mkdir -p "$salida" "$ARTEFACTOS/trivy"

info "1. Construcción de $IMAGEN (commit $CORTO)"
# shellcheck disable=SC2086
docker buildx build --load --pull \
  --build-arg DIGSA_VERSION="$VERSION" --build-arg DIGSA_COMMIT="$COMMIT" \
  --label org.opencontainers.image.title="Suite DIGSA" \
  --label org.opencontainers.image.version="$VERSION" \
  --label org.opencontainers.image.revision="$COMMIT" \
  --label org.opencontainers.image.created="$FECHA" \
  --label org.opencontainers.image.source="$ORIGEN" \
  ${DIGSA_BUILDX_OPCIONES:-} \
  -t "$IMAGEN" .
ID=$(docker image inspect --format '{{.Id}}' "$IMAGEN")
info "Imagen $IMAGEN · $ID"

info "2. Prueba de humo con docker compose (entorno «pruebas», Mailpit, PostgreSQL 16 nuevo)"
PUERTO="${DIGSA_PUERTO_HUMO:-18080}"
preparar_despliegue humo "$VERSION" pruebas "$PUERTO"
trap 'estado=$?; [ $estado -eq 0 ] || registros_aplicacion; retirar_despliegue; exit $estado' EXIT
levantar pruebas

fallidas=$(sql_dueno "SELECT count(*) FROM flyway_schema_history WHERE NOT success")
[ "$fallidas" = "0" ] || falla "Flyway registra $fallidas migraciones fallidas."
aplicadas=$(sql_dueno "SELECT count(DISTINCT version) FROM flyway_schema_history WHERE success AND version IS NOT NULL")
esperadas=$(find backend/app/src/main/resources/db/migration -name 'V*__*.sql' | wc -l | tr -d ' ')
[ "$aplicadas" = "$esperadas" ] || falla "Flyway aplicó $aplicadas migraciones versionadas y el repositorio tiene $esperadas."
echo "Flyway: $aplicadas migraciones versionadas aplicadas, ninguna fallida."

codigo=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PUERTO/actuator/health")
[ "$codigo" = "200" ] || falla "/actuator/health respondió $codigo (se esperaba 200)."
echo "/actuator/health: 200"

curl -fsS "http://127.0.0.1:$PUERTO/" | grep -q 'id="raiz"' || falla "GET / no entregó la SPA (no aparece <div id=\"raiz\">)."
echo "GET /: SPA entregada"

dc cp herramientas/verificar_base_limpia.sql db:/tmp/verificar_base_limpia.sql
sucias=$(dc exec -T db sh -c 'psql -v ON_ERROR_STOP=1 -X -q -At -F ";" -U "$POSTGRES_USER" -d digsa -f /tmp/verificar_base_limpia.sql' \
  | awk -F';' '$1 ~ /^PROCESO/ && $3 != "0" { print $2 "=" $3 }')
[ -z "$sucias" ] || falla "La base nueva trae filas en tablas del proceso: $sucias"
echo "verificar_base_limpia.sql: tablas del proceso en 0"

if dc logs --no-color app 2>/dev/null | grep -q "Suite DIGSA $VERSION"; then
  echo "Log de arranque: «Suite DIGSA $VERSION»"
else
  aviso "El log de arranque no trae «Suite DIGSA $VERSION ($CORTO)» (pendiente en la aplicación: DIGSA_VERSION y DIGSA_COMMIT)."
fi
retirar_despliegue
trap - EXIT

info "3. docker save"
docker save "$IMAGEN" -o "$salida/digsa-suite.tar"
{
  echo "DIGSA_VERSION=$VERSION"
  echo "DIGSA_COMMIT=$COMMIT"
  echo "DIGSA_IMAGEN=$IMAGEN"
  echo "DIGSA_IMAGEN_ID=$ID"
  echo "DIGSA_FECHA=$FECHA"
} > "$salida/imagen.env"
(cd "$salida" && huella digsa-suite.tar > digsa-suite.tar.sha256)

info "4. Trivy de la imagen"
validar_trivyignore
tar_trivy=$(ruta_trivy "$salida/digsa-suite.tar")
trivy_ejecutar image --input "$tar_trivy" --scanners vuln,secret --format json \
  --output "$(ruta_trivy "$ARTEFACTOS/trivy/imagen.json")"
if grep -Eq '"EOSL": *true' "$ARTEFACTOS/trivy/imagen.json"; then
  falla "El sistema operativo de la imagen base está en fin de vida (EOSL): actualice la imagen base del Dockerfile."
fi
trivy_ejecutar image --input "$tar_trivy" --scanners vuln,secret --severity HIGH,CRITICAL --ignore-unfixed \
  --ignorefile "$(ruta_trivy "$RAIZ/.trivyignore")" --exit-code 1 --format table \
  || falla "La imagen tiene vulnerabilidades CRITICAL o HIGH con corrección, o secretos embebidos (tabla de arriba)."

info "5. SBOM CycloneDX"
trivy_ejecutar image --input "$tar_trivy" --format cyclonedx --output "$(ruta_trivy "$salida/sbom.cdx.json")"
info "Imagen $IMAGEN probada y guardada en ${salida#"$RAIZ"/}/ (id $ID)."
