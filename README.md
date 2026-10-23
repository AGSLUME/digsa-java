# Suite DIGSA · Visitas de seguimiento farmacéutico (Java + React)

Reescritura del módulo de visitas de la interventoría de AGS Colombia al contrato de medicamentos de las Fuerzas Militares (DIGSA).
La versión en PHP (`C:\Users\dgonzalez\dev\digsa`, rama develop) es la **referencia funcional**: reglas, decisiones y pruebas.
Esta versión no copia su arquitectura.

- **Backend:** Java 21 y Spring Boot 3.5, monolito modular (Spring Modulith). Las reglas de negocio puras viven en `backend/reglas`, que solo depende del JDK.
- **Frontend:** React 18, TypeScript estricto, Vite y Mantine 7. Se sirve desde el mismo origen que la API.
- **Base de datos:** PostgreSQL 16, con migraciones de Flyway. Las sesiones van en la misma base (Spring Session JDBC); no hay Redis.
- **Despliegue:** contenedores, con correo, archivos y secretos detrás de interfaces. Así puede ir a Azure o a un datacenter en Colombia, que es lo que pide el pliego.

El plan de trabajo está en [docs/plan_acelerado.md](docs/plan_acelerado.md) y las dudas en [docs/dudas_pendientes.md](docs/dudas_pendientes.md).

## Estructura

```
backend/reglas      reglas puras (festivos, plazos, sesión, bitácora, alcance, pantallas, MFA, correos) con sus pruebas JUnit
backend/app         Spring Boot: plataforma (correo, bitácora, exportador, errores), identidad (ingreso, sesión, usuarios), auditoria (logs)
frontend            SPA (src/paginas, src/shared)
e2e                 Playwright: Chromium, Firefox, WebKit (y canales Chrome y Edge)
docs                plan, dudas, especificaciones extraídas del PHP (docs/especificaciones)
```

## Requisitos de desarrollo (sin administrador)

- JDK 21: `%LOCALAPPDATA%\Programs\jdk21` (Temurin).
- Node 20 o superior.
- Maven: lo baja el envoltorio `backend/mvnw`.
- Docker no es obligatorio. Las pruebas de integración usan PostgreSQL 16 embebido (zonky). Con Docker se puede usar Testcontainers: `-Dzonky.test.database.provider=docker`.

## Correr en local

```bash
cd frontend && npm install && npm run build          # la SPA compilada (la sirve el backend local)
cd ../backend
export JAVA_HOME="$LOCALAPPDATA/Programs/jdk21"
./mvnw -q install -DskipTests                         # instala el módulo reglas: spring-boot:test-run -pl app usa el instalado
# primer administrador (solo una vez): el enlace para definir la contraseña sale en el log
./mvnw -pl app spring-boot:test-run -Dspring-boot.run.main-class=co.com.agsamericas.digsa.DigsaLocal \
  "-Dspring-boot.run.arguments=--digsa.crear-admin.correo=admin@example.com --digsa.crear-admin.nombre=Administrador --digsa.crear-admin.usuario=admin"
# servidor en http://localhost:8080 (PostgreSQL embebido en .local/pgdata, correos en .local/correos)
./mvnw -pl app spring-boot:test-run -Dspring-boot.run.main-class=co.com.agsamericas.digsa.DigsaLocal
```

La base local trae los datos iniciales editables (V7): 4 fuerzas, los tipos de documento y de factura, las 8 regionales y los 185 puntos del anexo del OPL. Para regenerar los catálogos y el checklist desde la fuente del mockup v6: `py herramientas/generar_catalogos_pg.py` (no reescribe V4 ni V7 si ya existen).

En local, el correo se guarda en archivo y no sale. Para poder probar sin buzón, el código de verificación aparece en el mensaje de la pantalla y el enlace de la contraseña en el log. Esto solo ocurre con `digsa.entorno=local`: en cualquier otro entorno la aplicación no arranca con correo a archivo.

## En contenedores (Docker)

La aplicación se empaca en **una sola imagen**: el backend sirve la SPA desde el mismo origen. Así se despliega en cualquier servidor con Docker, en un datacenter en Colombia (duda 16.1) o en un orquestador (Azure Container Apps, Kubernetes). La imagen no lleva secretos: la configuración entra por variables de entorno.

```bash
cp .env.example .env              # diligencie DIGSA_DB_CLAVE y lo demás (el .env no se versiona)
docker compose up -d --build      # aplicación en http://localhost:8080 + PostgreSQL 16
# primer administrador (solo una vez): el enlace para definir la contraseña sale en el log
docker compose run --rm app --digsa.crear-admin.correo=admin@ejemplo.gov.co --digsa.crear-admin.nombre=Administrador --digsa.crear-admin.usuario=admin
docker compose --profile pruebas up -d   # además, correo de pruebas en http://localhost:8025 (Mailpit)
```

- **Carga inicial de maestros** (auditores, puntos y usuarios, sin contraseñas): herramienta de línea de comandos en la misma imagen. Guía completa en `docs/guia_carga_maestros.md`:
  ```bash
  docker compose run --rm --no-deps -v "$PWD/carga:/carga" app --digsa.carga.plantillas=/carga                                   # plantillas
  docker compose run --rm -v "$PWD/carga:/carga" app --digsa.carga.carpeta=/carga --digsa.carga.responsable=admin                 # simular
  docker compose run --rm -v "$PWD/carga:/carga" app --digsa.carga.carpeta=/carga --digsa.carga.responsable=admin --digsa.carga.modo=aplicar
  ```
- **Datos:** los archivos (evidencias y actas) quedan en el volumen `datos-app` (`/datos`) y la base en `datos-db`.
- **Producción:** se quita el servicio `db` y `DIGSA_DB_URL` apunta a la base administrada (alta disponibilidad).
- **Validación de arranque:** con `DIGSA_ENTORNO=produccion` la aplicación no arranca sin https, SMTP, remitente y dominios de los reportes.
- **En este equipo:** guía paso a paso en `docs/guia_docker_local.md` y arranque con doble clic en `docker/iniciar-local.bat`. Pendiente: construir y probar la imagen cuando TI instale Docker (duda 17.7).

## Pruebas

```bash
cd backend && ./mvnw verify          # reglas (JUnit), integración y API (PostgreSQL embebido), arquitectura (Modulith y ArchUnit)
cd frontend && npm test              # Vitest
cd e2e && npm test                   # Playwright en Chromium, Firefox y WebKit; levanta el servidor local con 1 minuto de inactividad
DIGSA_E2E_CANALES=chrome,msedge npx playwright test --project=chrome --project=msedge
```

## Integración continua

Toda la lógica vive en `ci/*.sh`, que corren en Linux y, salvo los que usan Docker, también en Git Bash de Windows. Los orquestadores solo llaman a esos scripts:
- `.github/workflows/ci.yml` (GitHub Actions) es el principal y el recomendado.
- `azure-pipelines.yml` (Azure DevOps) es el equivalente, con los mismos trabajos y las mismas puertas.

Si se cambia de orquestador, los scripts no se tocan (duda 16.3).

| # | Trabajo | Script | Cuándo | Depende de |
|---|---|---|---|---|
| 1 | `secretos` | `ci/secretos.sh`: gitleaks (`.gitleaks.toml`, informe sin valores) y archivos que nunca se versionan (`.env`, `.local/`, `respaldos/`, `carga/`) | siempre | — |
| 2 | `backend` | `ci/backend.sh`: `mvnw -B -ntp verify` (JUnit, PostgreSQL 16 embebido, Modulith y ArchUnit) | siempre | — |
| 3 | `frontend` | `ci/frontend.sh`: `npm ci`, `typecheck`, Vitest (JUnit) y `build` | siempre | — |
| 4 | `e2e` | `ci/e2e.sh`: Playwright en Chromium contra `DigsaLocal`, con la SPA del trabajo 3 | siempre | 3 |
| 4b | `e2e` en Firefox y WebKit | `DIGSA_E2E_MOTORES="firefox webkit" ci/e2e.sh` | de noche, manual y en etiquetas `v*` | 3 |
| 5 | `dependencias` | `ci/dependencias.sh`: Trivy sobre `pom.xml` y `package-lock.json` y, como segunda opinión informativa, `npm audit --omit=dev` | siempre | — |
| 6 | `imagen` | `ci/imagen.sh`: build, prueba de humo con compose, Trivy de la imagen y SBOM CycloneDX | siempre | 2 y 3 |
| 7 | `publicar` | `ci/publicar.sh`: sube **la misma** imagen probada y, en `v*`, arma el paquete para TI | push a `main` y etiquetas `v*` | 1 a 6 |
| 8 | `respaldo` | `ci/respaldo_restauracion.sh`: respaldo, `down -v` y restauración con `docker/respaldar.sh` y `docker/restaurar.sh` | de noche, manual y en etiquetas `v*` | 6 |

- **Puertas.** Cualquier trabajo en rojo deja el pipeline en rojo. En GitHub, la regla de la rama `main` exige como *required status checks* `secretos`, `backend`, `frontend`, `e2e`, `dependencias` e `imagen`. En Azure DevOps, la *build validation* de la rama hace lo mismo.
- **Vulnerabilidades.** Bloquean las CRITICAL o HIGH que tengan versión corregida y la imagen base en fin de vida. Las que no tienen corrección van al informe JSON sin bloquear. Una excepción solo vale en `.trivyignore` con motivo, quién la aceptó y `exp:` a 90 días como máximo; al vencer, vuelve a bloquear.
- **Etiquetas de la imagen.** Un push a `main` publica `sha-<commit>` y `main`. Una etiqueta `vX.Y.Z` publica `X.Y.Z` y `sha-<commit>`. Nunca se publica `latest`. La imagen lleva las etiquetas OCI `version`, `revision`, `created` y `source`.
- **Paquete para TI** (etiquetas `v*`):
  - `digsa-suite_<versión>.tar.gz`, el `docker save` de la imagen, que se carga con `docker load -i`;
  - `digsa-suite_<versión>_despliegue.tar.gz`, con el compose, `.env.example`, `docker/`, `herramientas/` y la guía de operación;
  - el SBOM;
  - `SHA256SUMS`.

  En GitHub queda en el *release* de la etiqueta.
- **Secretos.** Viven solo en las variables seguras del orquestador:
  - en GitHub, en el entorno `publicacion`, con aprobación; para GHCR basta `GITHUB_TOKEN`, y otro registro usa la variable `DIGSA_REGISTRO` y los secretos `DIGSA_REGISTRO_USUARIO` y `DIGSA_REGISTRO_CLAVE`;
  - en Azure DevOps, en el grupo de variables `digsa-publicacion` y el entorno `publicacion`.

  Las pruebas no usan secretos. Con `CI` definido, la contraseña del administrador de Playwright es aleatoria en cada corrida.
- **Versiones fijas.**
  - Las acciones de GitHub van por SHA, con su versión al lado. Dependabot (`.github/dependabot.yml`) propone actualizar las acciones, Maven, npm y Docker.
  - Trivy y gitleaks corren como contenedor con versión y digest fijos, en `ci/comun.sh` (`TRIVY_IMAGEN`, `GITLEAKS_IMAGEN`). Se actualizan a mano con una versión publicada hace al menos dos semanas. Si el binario está instalado en el equipo, se usa ese.
- **En el equipo** (Git Bash): `bash ci/backend.sh`, `bash ci/frontend.sh` y `bash ci/e2e.sh` dan el mismo resultado que el pipeline sin Docker. `ci/secretos.sh` y `ci/dependencias.sh` necesitan gitleaks y Trivy, o Docker. `ci/imagen.sh`, `ci/publicar.sh` y `ci/respaldo_restauracion.sh` necesitan Docker (ejecutor Linux).
- **Antes del primer push:** `git update-index --chmod=+x backend/mvnw ci/*.sh docker/*.sh docker/postgres/*.sh`. Los scripts igual dan el permiso a `mvnw` si falta.

La operación del servidor (instalación, respaldo, restauración, actualización y monitoreo) está en [docs/guia_operacion_servidor.md](docs/guia_operacion_servidor.md).

## Configuración (variables de entorno; nunca en el repositorio)

| Variable | Qué es |
|---|---|
| `DIGSA_DB_URL`, `DIGSA_DB_USUARIO`, `DIGSA_DB_CLAVE` | Conexión a PostgreSQL |
| `DIGSA_URL` | URL pública con `https://` y la barra final; va en los enlaces de los correos |
| `DIGSA_ENTORNO` | `produccion`, `pruebas` o `local` |
| `DIGSA_SESION_MINUTOS` | Inactividad (Anexo 5, 3.2): 10 por defecto |
| `DIGSA_CLAVE_VIGENCIA_DIAS` | Vigencia de la contraseña: 30 por defecto |
| `DIGSA_SMTP_HOST`, `DIGSA_SMTP_PUERTO`, `DIGSA_SMTP_USUARIO`, `DIGSA_SMTP_CLAVE`, `DIGSA_CORREO_REMITENTE` | Correo SMTP |
| `DIGSA_TURNSTILE_SITIO`, `DIGSA_TURNSTILE_SECRETA` | Captcha Cloudflare Turnstile. Sin clave secreta nadie ingresa, salvo en local |
| `DIGSA_CORREO_REPORTES` | Correo por defecto para enviar reportes (prueba en sitio: el del comité) |
| `digsa.reportes.*` (opcionales) | Módulo de reportes: `filas-pantalla` (5000), `max-filas-pdf` (5000), `adjunto-max-mb` (10), `verificaciones-por-minuto` (30) |

**Módulo de reportes** (`docs/especificaciones/reportes`):
- `backend/app/.../reportes` tiene solo el contrato `ProveedorReporte`: cada módulo aporta así sus reportes (`contrato-proveedor.md`).
- `motorreportes` tiene el motor, el catálogo, los filtros, los formatos en flujo, la huella con su código de verificación, el
  registro `reportes_generados` y la verificación.

El primer administrador se crea solo por línea de comandos (`--digsa.crear-admin.correo=… --digsa.crear-admin.nombre=…`). No hay contraseñas en el repositorio. Los demás usuarios de la carga inicial entran con `--digsa.carga.*` (`docs/guia_carga_maestros.md`) y reciben el enlace para definir su contraseña.
