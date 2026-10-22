# Suite DIGSA · imagen única (backend Spring Boot que sirve la SPA de React desde el mismo origen).
# Portátil a cualquier servidor con Docker o a un orquestador (Azure Container Apps, Kubernetes, un datacenter en Colombia: duda 16.1).
# Sin secretos en la imagen: toda la configuración entra por variables de entorno (ver .env.example y el README).

# ---------- 1. Frontend (Vite) ----------
FROM node:20.18.0-bookworm-slim AS frontend
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---------- 2. Backend (Maven, JDK 21) ----------
FROM maven:3.9.9-eclipse-temurin-21 AS backend
WORKDIR /src/backend
COPY backend/ ./
# El pom copia ../../frontend/dist a static/ al empaquetar
COPY --from=frontend /src/frontend/dist /src/frontend/dist
RUN mvn -q -B -DskipTests package

# ---------- 3. Ejecución (solo JRE, usuario sin privilegios) ----------
FROM eclipse-temurin:21.0.5_11-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --system digsa && useradd --system --gid digsa --home-dir /app digsa \
    && mkdir -p /app /datos/archivos /datos/correos && chown -R digsa:digsa /app /datos
WORKDIR /app
COPY --from=backend /src/backend/app/target/digsa.jar /app/digsa.jar
USER digsa
# Archivos (evidencias, actas) y correos en archivo (solo local) fuera de la imagen: van en un volumen
ENV DIGSA_ARCHIVOS_CARPETA=/datos/archivos \
    DIGSA_CORREO_CARPETA=/datos/correos \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.awt.headless=true -Duser.timezone=America/Bogota"
VOLUME ["/datos"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/digsa.jar"]
