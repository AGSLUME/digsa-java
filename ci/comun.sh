# Suite DIGSA · funciones comunes de los pasos del pipeline (ci/*.sh). Se carga con «. ci/comun.sh»; no se ejecuta sola.
# Los orquestadores (.github/workflows/ci.yml, azure-pipelines.yml) solo llaman a ci/*.sh: toda la lógica vive aquí.
# shellcheck shell=bash

set -eu
if (set -o pipefail) 2>/dev/null; then set -o pipefail; fi

RAIZ=$(cd "$(dirname "$0")/.." && pwd)
ARTEFACTOS="${DIGSA_ARTEFACTOS:-$RAIZ/artefactos}"
export TZ="${TZ:-America/Bogota}"
export LANG="${LANG:-C.UTF-8}"

# Herramientas que corren como contenedor: versión exacta y digest (una etiqueta se puede volver a apuntar; el digest no).
# Se actualizan a mano (README, «Integración continua»): versión publicada hace al menos dos semanas y su digest de Docker Hub.
TRIVY_IMAGEN="${TRIVY_IMAGEN:-aquasec/trivy:0.75.0@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa}"
GITLEAKS_IMAGEN="${GITLEAKS_IMAGEN:-zricethezav/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f}"

info()  { printf '\n==> %s\n' "$*"; }
aviso() { printf 'AVISO: %s\n' "$*" >&2; }
falla() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

en_ci() { [ -n "${CI:-}" ] || [ -n "${TF_BUILD:-}" ] || [ -n "${GITHUB_ACTIONS:-}" ]; }

es_windows() {
  case "$(uname -s 2>/dev/null || echo desconocido)" in
    MINGW*|MSYS*|CYGWIN*) return 0 ;;
    *) return 1 ;;
  esac
}

tiene() { command -v "$1" >/dev/null 2>&1; }

# JDK 21: el del entorno (setup-java, JavaToolInstaller o JAVA_HOME_21_X64 de Azure); en el equipo de desarrollo con Windows,
# la instalación portátil de %LOCALAPPDATA%.
preparar_java() {
  if [ -z "${JAVA_HOME:-}" ] && [ -n "${JAVA_HOME_21_X64:-}" ]; then
    JAVA_HOME="$JAVA_HOME_21_X64"
  fi
  if [ -z "${JAVA_HOME:-}" ] && es_windows && [ -n "${LOCALAPPDATA:-}" ]; then
    JAVA_HOME="$LOCALAPPDATA/Programs/jdk21"
  fi
  if [ -n "${JAVA_HOME:-}" ]; then
    export JAVA_HOME
    PATH="$JAVA_HOME/bin:$PATH"
    export PATH
  fi
  tiene java || falla "No se encontró Java 21. Defina JAVA_HOME (en Windows: %LOCALAPPDATA%\\Programs\\jdk21)."
  java -version 2>&1 | head -n 1
}

# PostgreSQL embebido (zonky) se niega a correr como root
no_root() {
  if ! es_windows && [ "$(id -u)" = "0" ]; then
    falla "Este paso no puede correr como root: PostgreSQL embebido (zonky) se niega. Use un usuario sin privilegios."
  fi
}

# Envoltorio de Maven con permiso de ejecución aunque git lo haya traído sin él
mvnw() {
  if es_windows; then
    (cd "$RAIZ/backend" && ./mvnw.cmd "$@")
  else
    chmod +x "$RAIZ/backend/mvnw" 2>/dev/null || true
    (cd "$RAIZ/backend" && ./mvnw "$@")
  fi
}

# Referencia de git que dispara la corrida (refs/heads/main, refs/tags/v1.4.0, refs/pull/…)
referencia() {
  if [ -n "${GITHUB_REF:-}" ]; then
    printf '%s' "$GITHUB_REF"
  elif [ -n "${BUILD_SOURCEBRANCH:-}" ]; then
    printf '%s' "$BUILD_SOURCEBRANCH"
  else
    t=$(git -C "$RAIZ" describe --exact-match --tags --match 'v*' 2>/dev/null || true)
    if [ -n "$t" ]; then printf 'refs/tags/%s' "$t"; else printf 'refs/heads/%s' "$(git -C "$RAIZ" rev-parse --abbrev-ref HEAD 2>/dev/null || echo local)"; fi
  fi
}

commit_largo() { git -C "$RAIZ" rev-parse HEAD 2>/dev/null || printf '%s' "${GITHUB_SHA:-${BUILD_SOURCEVERSION:-desconocido}}"; }
commit_corto() { commit_largo | cut -c1-7; }

# Versión de la imagen: X.Y.Z en una etiqueta vX.Y.Z; si no, 0.0.0-sha-<commit corto>. Nunca «latest».
version_imagen() {
  r=$(referencia)
  case "$r" in
    refs/tags/v[0-9]*) printf '%s' "${r#refs/tags/v}" ;;
    *) printf '0.0.0-sha-%s' "$(commit_corto)" ;;
  esac
}

es_etiqueta_version() { case "$(referencia)" in refs/tags/v[0-9]*) return 0 ;; *) return 1 ;; esac; }
es_main() { [ "$(referencia)" = "refs/heads/main" ]; }

# sha256sum (GNU) o shasum (macOS)
huella() {
  if tiene sha256sum; then sha256sum "$@"; else shasum -a 256 "$@"; fi
}

# Trivy: el binario si está instalado; si no, la imagen fija por Docker, con el repositorio montado en /repo
trivy_ejecutar() {
  if tiene trivy; then
    trivy "$@"
  elif tiene docker; then
    mkdir -p "${TRIVY_CACHE:-$HOME/.cache/trivy}"
    docker run --rm \
      -v "$RAIZ:/repo" -w /repo \
      -v "${TRIVY_CACHE:-$HOME/.cache/trivy}:/root/.cache/trivy" \
      -v /var/run/docker.sock:/var/run/docker.sock \
      "$TRIVY_IMAGEN" "$@"
  else
    falla "No se encontró Trivy ni Docker. Instale Trivy (https://trivy.dev) o Docker para este paso."
  fi
}

# Rutas para Trivy: dentro del contenedor el repositorio está en /repo
ruta_trivy() {
  if tiene trivy; then printf '%s' "$1"
  elif [ "$1" = "$RAIZ" ]; then printf '/repo'
  else
    case "$1" in "$RAIZ"/*) ;; *) falla "Con Trivy en Docker, la ruta debe estar dentro del repositorio: $1" ;; esac
    printf '/repo/%s' "${1#"$RAIZ"/}"
  fi
}

# .trivyignore: cada excepción debe traer motivo, quién la aceptó y vencimiento (exp:AAAA-MM-DD) a 90 días como máximo.
# Trivy ya ignora las vencidas (vuelven a bloquear); aquí se rechaza la que no cumple el formato.
validar_trivyignore() {
  archivo="$RAIZ/.trivyignore"
  [ -f "$archivo" ] || return 0
  hoy=$(date +%Y-%m-%d)
  limite=$(date -d "+90 days" +%Y-%m-%d 2>/dev/null || date -v+90d +%Y-%m-%d)
  errores=0
  motivo=""; quien=""
  n=0
  while IFS= read -r linea || [ -n "$linea" ]; do
    n=$((n + 1))
    linea=$(printf '%s' "$linea" | tr -d '\r')
    case "$linea" in
      ''|'#'*)
        case "$linea" in *'motivo:'*) motivo=1 ;; esac
        case "$linea" in *'aceptó:'*|*'acepto:'*) quien=1 ;; esac
        [ -z "$linea" ] && { motivo=""; quien=""; }
        continue ;;
    esac
    id=${linea%% *}
    vence=$(printf '%s' "$linea" | sed -n 's/.*exp:\([0-9-]\{10\}\).*/\1/p')
    if [ -z "$vence" ]; then
      aviso ".trivyignore línea $n ($id): falta «exp:AAAA-MM-DD»."; errores=$((errores + 1))
    elif [ "$vence" \> "$limite" ]; then
      aviso ".trivyignore línea $n ($id): vence el $vence, más de 90 días (máximo $limite)."; errores=$((errores + 1))
    elif [ "$vence" \< "$hoy" ]; then
      aviso ".trivyignore línea $n ($id): venció el $vence; vuelve a bloquear."
    fi
    if [ -z "$motivo" ] || [ -z "$quien" ]; then
      aviso ".trivyignore línea $n ($id): falta el comentario «# motivo: …» y «# aceptó: …» encima."; errores=$((errores + 1))
    fi
    motivo=""; quien=""
  done < "$archivo"
  [ "$errores" -eq 0 ] || falla ".trivyignore tiene $errores excepciones sin el formato exigido (ver el encabezado del archivo)."
}

# Clave aleatoria solo con letras y números (sirve en .env y en URL de conexión)
clave_aleatoria() {
  if tiene openssl; then
    openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | cut -c1-32
  else
    LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 32
  fi
}

# Espera a que una URL responda 200 (máximo $2 segundos)
esperar_url() {
  url=$1; max=${2:-180}; t=0
  until curl -fsS -o /dev/null "$url" 2>/dev/null; do
    t=$((t + 3))
    [ "$t" -lt "$max" ] || return 1
    sleep 3
  done
}
