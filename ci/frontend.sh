#!/usr/bin/env bash
# Suite DIGSA · trabajo «frontend»: npm ci, verificación de tipos, ESLint (hooks y accesibilidad), Vitest y compilación. Deja
# frontend/dist para el trabajo e2e y frontend/test-results/vitest-junit.xml como informe.
. "$(dirname "$0")/comun.sh"

tiene node || falla "No se encontró Node 20 o superior."
mayor=$(node -p 'process.versions.node.split(".")[0]')
[ "$mayor" -ge 20 ] || falla "Se necesita Node 20 o superior (hay $(node --version))."

cd "$RAIZ/frontend"
info "npm ci"
npm ci --no-audit --no-fund
info "Verificación de tipos"
npm run typecheck
info "ESLint (reglas de hooks y de accesibilidad; los avisos no detienen)"
npm run lint
info "Vitest"
npm test -- --reporter=default --reporter=junit --outputFile.junit=test-results/vitest-junit.xml
info "Compilación"
npm run build
[ -f dist/index.html ] || falla "La compilación no dejó frontend/dist/index.html."
info "Frontend en verde."
