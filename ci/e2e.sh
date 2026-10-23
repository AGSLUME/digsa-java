#!/usr/bin/env bash
# Suite DIGSA · trabajo «e2e»: Playwright contra el arranque local (DigsaLocal: PostgreSQL embebido, correo a archivo, SPA de
# frontend/dist en el mismo origen, puerto 8080).
# Uso: bash ci/e2e.sh                       → Chromium (cada cambio)
#      DIGSA_E2E_MOTORES="firefox webkit" bash ci/e2e.sh   → corrida nocturna y etiquetas v*
#      bash ci/e2e.sh pruebas/sesion.spec.ts → argumentos extra para Playwright
# En el pipeline la contraseña del administrador de las pruebas es aleatoria (playwright.config.ts, con CI definido).
. "$(dirname "$0")/comun.sh"

no_root
motores="${DIGSA_E2E_MOTORES:-chromium}"

info "JDK"
preparar_java

if [ ! -f "$RAIZ/frontend/dist/index.html" ]; then
  info "No hay frontend/dist (no vino del trabajo frontend): se compila"
  (cd "$RAIZ/frontend" && npm ci --no-audit --no-fund && npm run build)
fi

info "Módulos del backend en el repositorio local de Maven (sin pruebas)"
mvnw -B -ntp -q install -DskipTests

cd "$RAIZ/e2e"
info "npm ci (e2e)"
npm ci --no-audit --no-fund

if en_ci && ! es_windows; then
  info "Navegadores de Playwright: $motores"
  # shellcheck disable=SC2086
  npx playwright install --with-deps $motores
fi

proyectos=""
for m in $motores; do proyectos="$proyectos --project=$m"; done

info "Playwright ($motores), TZ=$TZ"
# npm test corre antes limpiar-puertos.mjs (no hace nada con CI definido)
# shellcheck disable=SC2086
npm test -- $proyectos "$@"
info "Pruebas de punta a punta en verde."
