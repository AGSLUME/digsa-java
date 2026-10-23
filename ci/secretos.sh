#!/usr/bin/env bash
# Suite DIGSA · trabajo «secretos»: gitleaks sobre el cambio (o sobre toda la historia) y archivos que nunca se versionan.
# Uso: bash ci/secretos.sh            → toda la historia (push a main, etiquetas, corrida nocturna)
#      DIGSA_SECRETOS_BASE=<commit> bash ci/secretos.sh   → solo los commits del cambio (solicitud de cambio)
# El informe no repite ningún valor (--redact): dice archivo, línea y regla.
. "$(dirname "$0")/comun.sh"

cd "$RAIZ"
mkdir -p "$ARTEFACTOS/secretos"

info "Archivos que no se versionan nunca (.env, .local/, carga/, respaldos/, almacenes)"
prohibidos=$(git ls-files | grep -E '(^|/)\.env($|\.)|(^|/)\.local/|(^|/)carga/|(^|/)respaldos/|(^|/)almacen/|\.(dump|pem|key|p12|pfx|jks)$' | grep -vE '(^|/)\.env\.example$' || true)
if [ -n "$prohibidos" ]; then
  printf '%s\n' "$prohibidos" >&2
  falla "El repositorio tiene archivos que no se versionan (secretos, datos o respaldos). Quítelos con «git rm --cached» y revise .gitignore."
fi

# Azure DevOps no da el commit base de la solicitud de cambio: se usa la rama destino
if [ -z "${DIGSA_SECRETOS_BASE:-}" ] && [ "${BUILD_REASON:-}" = "PullRequest" ] && [ -n "${SYSTEM_PULLREQUEST_TARGETBRANCH:-}" ]; then
  DIGSA_SECRETOS_BASE="origin/${SYSTEM_PULLREQUEST_TARGETBRANCH#refs/heads/}"
fi

opciones_log=""
if [ -n "${DIGSA_SECRETOS_BASE:-}" ]; then
  opciones_log="${DIGSA_SECRETOS_BASE}..HEAD"
  info "gitleaks sobre los commits del cambio (${DIGSA_SECRETOS_BASE}..HEAD)"
else
  info "gitleaks sobre toda la historia"
fi

argumentos="git --config .gitleaks.toml --redact --no-banner --exit-code 1 --report-format sarif"
if tiene gitleaks; then
  # shellcheck disable=SC2086
  gitleaks $argumentos --report-path "$ARTEFACTOS/secretos/gitleaks.sarif" ${opciones_log:+--log-opts="$opciones_log"} .
elif tiene docker; then
  # safe.directory: el repositorio montado pertenece a otro usuario dentro del contenedor
  # shellcheck disable=SC2086
  docker run --rm -v "$RAIZ:/repo" -w /repo \
    -e GIT_CONFIG_COUNT=1 -e GIT_CONFIG_KEY_0=safe.directory -e GIT_CONFIG_VALUE_0='*' \
    "$GITLEAKS_IMAGEN" $argumentos --report-path "/repo/${ARTEFACTOS#"$RAIZ"/}/secretos/gitleaks.sarif" \
    ${opciones_log:+--log-opts="$opciones_log"} /repo
else
  falla "No se encontró gitleaks ni Docker. Instale gitleaks (https://github.com/gitleaks/gitleaks) o Docker para este paso."
fi
info "Sin secretos en el repositorio."
