#!/usr/bin/env bash
# Suite DIGSA · trabajo «dependencias»: Trivy (sistema de archivos) sobre backend/**/pom.xml, frontend/package-lock.json y
# e2e/package-lock.json. Falla con cualquier vulnerabilidad CRITICAL o HIGH que tenga versión corregida, salvo las excepciones
# vigentes de .trivyignore. Las que no tienen corrección se informan sin bloquear (informe JSON completo).
# Segunda opinión informativa: npm audit --omit=dev del frontend.
. "$(dirname "$0")/comun.sh"

cd "$RAIZ"
salida="$ARTEFACTOS/trivy"
mkdir -p "$salida"

info "Excepciones de .trivyignore"
validar_trivyignore

omitir=(--skip-dirs '**/node_modules' --skip-dirs '**/target' --skip-dirs .local --skip-dirs artefactos --skip-dirs frontend/dist)

info "Trivy: informe completo (todas las severidades, con y sin corrección)"
trivy_ejecutar fs --scanners vuln "${omitir[@]}" --format json \
  --output "$(ruta_trivy "$salida/dependencias.json")" "$(ruta_trivy "$RAIZ")"

info "Trivy: puerta (CRITICAL y HIGH con versión corregida)"
trivy_ejecutar fs --scanners vuln "${omitir[@]}" --severity HIGH,CRITICAL --ignore-unfixed \
  --ignorefile "$(ruta_trivy "$RAIZ/.trivyignore")" --exit-code 1 --format table "$(ruta_trivy "$RAIZ")" \
  || falla "Hay dependencias con vulnerabilidades CRITICAL o HIGH que tienen corrección (tabla de arriba: paquete, CVE y versión corregida). Actualice la dependencia o registre una excepción justificada en .trivyignore."

info "npm audit --omit=dev (frontend, informativo)"
(cd frontend && npm audit --omit=dev --audit-level=high) \
  || aviso "npm audit encontró avisos en el frontend (informativo; la puerta es Trivy)."
info "Dependencias sin vulnerabilidades bloqueantes."
