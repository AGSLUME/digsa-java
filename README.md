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

El primer administrador se crea solo por línea de comandos (`--digsa.crear-admin.correo=… --digsa.crear-admin.nombre=…`). No hay contraseñas en el repositorio.
