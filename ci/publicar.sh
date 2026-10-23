#!/usr/bin/env bash
# Suite DIGSA · trabajo «publicar» (solo push a main y etiquetas vX.Y.Z): sube al registro privado LA MISMA imagen que probó el
# trabajo «imagen» (artefactos/imagen/digsa-suite.tar, se comprueba su id) y, en las etiquetas, arma el paquete para TI.
#
#   push a main   → <registro>:sha-<commit corto> y <registro>:main
#   etiqueta vX.Y.Z → <registro>:X.Y.Z y <registro>:sha-<commit corto>
#   Nunca «latest».
#
# Variables: DIGSA_REGISTRO (p. ej. ghcr.io/ags-colombia/digsa-suite o agsdigsa.azurecr.io/digsa-suite). Credenciales: solo de
# las variables seguras del orquestador, DIGSA_REGISTRO_USUARIO y DIGSA_REGISTRO_CLAVE (si el orquestador no inició ya la sesión);
# nunca se escriben en el log. DIGSA_SIN_REGISTRO=1 arma solo el paquete (sin subir nada).
. "$(dirname "$0")/comun.sh"

tiene docker || falla "Este paso necesita Docker."
cd "$RAIZ"
entrada="$ARTEFACTOS/imagen"
[ -f "$entrada/imagen.env" ] && [ -f "$entrada/digsa-suite.tar" ] \
  || falla "Falta la imagen del trabajo «imagen» (artefactos/imagen/digsa-suite.tar e imagen.env)."
# shellcheck disable=SC1091
. "$entrada/imagen.env"

if es_etiqueta_version; then
  etiquetas="$DIGSA_VERSION sha-$(commit_corto)"
elif es_main; then
  etiquetas="sha-$(commit_corto) main"
else
  falla "Solo se publica desde main o desde una etiqueta vX.Y.Z (referencia: $(referencia))."
fi
[ "$DIGSA_COMMIT" = "$(commit_largo)" ] || falla "La imagen guardada es del commit $DIGSA_COMMIT y esta corrida es de $(commit_largo)."

info "Verificación de la huella y carga de la imagen probada"
(cd "$entrada" && huella -c digsa-suite.tar.sha256)
docker load -i "$entrada/digsa-suite.tar" >/dev/null
id=$(docker image inspect --format '{{.Id}}' "$DIGSA_IMAGEN")
[ "$id" = "$DIGSA_IMAGEN_ID" ] || falla "El id de la imagen cargada ($id) no es el de la probada ($DIGSA_IMAGEN_ID)."
echo "Imagen $DIGSA_IMAGEN · $id (la misma que pasó la prueba de humo)"

salida="$ARTEFACTOS/publicacion"
mkdir -p "$salida"

if [ -z "${DIGSA_SIN_REGISTRO:-}" ]; then
  [ -n "${DIGSA_REGISTRO:-}" ] || falla "Falta DIGSA_REGISTRO (registro privado de la imagen)."
  registro=$(printf '%s' "$DIGSA_REGISTRO" | tr '[:upper:]' '[:lower:]')
  if [ -n "${DIGSA_REGISTRO_USUARIO:-}" ] && [ -n "${DIGSA_REGISTRO_CLAVE:-}" ]; then
    info "Inicio de sesión en el registro ${registro%%/*}"
    printf '%s' "$DIGSA_REGISTRO_CLAVE" | docker login "${registro%%/*}" -u "$DIGSA_REGISTRO_USUARIO" --password-stdin >/dev/null
  fi
  : > "$salida/imagen_publicada.txt"
  for e in $etiquetas; do
    info "Publicando $registro:$e"
    docker tag "$DIGSA_IMAGEN" "$registro:$e"
    docker push "$registro:$e"
    echo "$registro:$e" >> "$salida/imagen_publicada.txt"
  done
  digest=$(docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$DIGSA_IMAGEN" | grep "^$registro@" | head -n 1 || true)
  {
    echo "digest: $digest"
    echo "id: $DIGSA_IMAGEN_ID"
    echo "commit: $DIGSA_COMMIT"
    echo "versión: $DIGSA_VERSION"
  } >> "$salida/imagen_publicada.txt"
  info "Publicada: $digest"
fi

if es_etiqueta_version; then
  info "Paquete para TI (servidor sin acceso al registro)"
  paquete="$ARTEFACTOS/paquete"
  rm -rf "$paquete"
  mkdir -p "$paquete"
  nombre="digsa-suite_$DIGSA_VERSION"
  # 1. La imagen: docker load -i digsa-suite_<versión>.tar.gz (docker load acepta el gzip directamente)
  gzip -c "$entrada/digsa-suite.tar" > "$paquete/$nombre.tar.gz"
  # 2. Lo necesario para operarla: compose, ejemplo del .env, docker/, herramientas/ y la guía de operación
  temporal=$(mktemp -d)
  mkdir -p "$temporal/$nombre/docs"
  cp docker-compose.yml .env.example "$temporal/$nombre/"
  cp -R docker herramientas "$temporal/$nombre/"
  cp docs/guia_operacion_servidor.md "$temporal/$nombre/docs/"
  [ -f "$entrada/sbom.cdx.json" ] && cp "$entrada/sbom.cdx.json" "$temporal/$nombre/sbom.cdx.json"
  tar -czf "$paquete/${nombre}_despliegue.tar.gz" -C "$temporal" "$nombre"
  rm -rf "$temporal"
  [ -f "$entrada/sbom.cdx.json" ] && cp "$entrada/sbom.cdx.json" "$paquete/${nombre}_sbom.cdx.json"
  (cd "$paquete" && huella -- * > "$ARTEFACTOS/SHA256SUMS.tmp")
  mv "$ARTEFACTOS/SHA256SUMS.tmp" "$paquete/SHA256SUMS"
  cat "$paquete/SHA256SUMS"
  info "Paquete listo en ${paquete#"$RAIZ"/}/: TI verifica con «sha256sum -c SHA256SUMS» y carga con «docker load -i $nombre.tar.gz»."
fi
