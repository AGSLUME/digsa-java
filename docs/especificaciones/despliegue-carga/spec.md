# Especificación: despliegue-carga (fase 11)

> Escrita el 2026-10-22 con el alcance que fijó el usuario ese día, a partir del PHP de referencia (develop, commit db2e330) y del repositorio Java (commit 23ecaef, fase 9).
> Fuentes PHP: scripts/empaquetar.ps1, scripts/crear_admin.php, scripts/plazos_opl.php, scripts/recordatorios_seguimiento.php, app/Support/ImportadorMaestros.php, controladores/maestros.controlador.php (importarPuntos, importarAuditores), modelos/visitas.modelo.php (resultadosDashboard), app/Support/ResumenChecklist.php (prestador), tests/importador_maestros_test.php, tests/e2e_local.php, docs/DESPLIEGUE_TI.md (§1 a §9), docs/operacion_local.md (§3), docs/campos_usuarios_y_puntos.md, docs/fases_desarrollo.md.
> Fuentes Java: README.md, Dockerfile, docker-compose.yml, .env.example, .dockerignore, .gitignore, docker/postgres/01-rol-app.sh, docker/iniciar-local.ps1, docs/guia_docker_local.md, docs/guia_despliegue_prueba.md, herramientas/ (generar_catalogos_pg.py, verificar_base_limpia.sql, datos/puntos_anexo3_opl.json y su LEEME), backend/app/src/main/resources/application.yml, db/migration (V1, V3, V5, V7, V12, V16), identidad/CrearAdministrador.java, tablero/ServicioDashboard.java, tablero/ExportadorTablero.java, frontend/src/paginas/Riesgo.tsx y Dashboard.tsx, e2e/playwright.config.ts, e2e/package.json, e2e/pruebas/riesgo-tablero.spec.ts, docs/especificaciones/maestros-carga-masiva, perfiles-alcance, identidad-sesion, riesgo-tablero y exportacion-alertas.
> **Mandan sobre el PHP**, en este orden: la decisión del usuario del 2026-10-22 (alcance de la fase 11: CI portable, carga inicial, brechas 25.3 y 25.4, IaC fuera), `docs/decisiones_2026-10-21.md` (D14: Docker para cualquier servidor), `docs/decisiones_2026-10-20.md`, las dudas 16.1 a 16.8, 17.7, 23.2, 24.4, 25.3 y 25.4 de `docs/dudas_pendientes.md` y la fuente de verdad (Anexo 5: ítems 1.15, 1.16, 1.22 a 1.24, 2.1 a 2.3, 2.15, 2.16, 3.4 a 3.6 y condición general 4 de la prueba en sitio; `RESPUESTAS OBSERVACIONES.md`: AGS-29, AGS-33, AGS-35, AGS-41, AGS-67, AGS-72, AGS-73/79, AGS-77/78, GMI P71, P72, P74, P77 f; `ANALISIS_RESPUESTAS_DIGSA_vs_SOFTWARE_2026-10-20.md` §2.1 y §2.2). Se marcan `<!-- decision: … -->`. El texto describe el comportamiento NUEVO.
> Relacionadas: reglas de cada punto y auditor en `maestros-carga-masiva`; usuarios, perfiles, alcance y combinaciones en `perfiles-alcance`; enlace de contraseña y primer administrador en `identidad-sesion`; tablero y dashboard en `riesgo-tablero`; exportación completa (entrega de datos, no respaldo) en `exportacion-alertas`; bitácora en `bitacora`.
> Estado en Java (commit 23ecaef): existen el Dockerfile de tres etapas (Node 20, Maven con JDK 21, JRE 21 sin privilegios, `HEALTHCHECK` a `/actuator/health`, volumen `/datos`), el docker-compose (PostgreSQL 16.4 + aplicación + Mailpit con el perfil «pruebas»), `.env.example`, el rol de la aplicación con permisos mínimos (`01-rol-app.sh`, V2, V10), el primer administrador por línea de comandos (`CrearAdministrador`, `--digsa.crear-admin.*`), los datos iniciales V7 (185 puntos del anexo del OPL con sigla, 8 regionales, sin auditores ni municipio base: duda 24.4) y `verificar_base_limpia.sql`. **No existen**: repositorio remoto ni pipeline (16.3), ningún importador de maestros en Java (ni pantalla ni línea de comandos: `maestros-carga-masiva` está especificada pero sin construir), guía de restauración, procedimiento de actualización, y las piezas del dashboard de la duda 25.3 y la vista de tarjetas de la 25.4. La imagen no se ha construido nunca en este equipo (17.7, 23.2). Ver «Datos que la reescritura Java debe agregar».

---

## A. Integración continua portable

### Requirement: Un pipeline principal y uno equivalente, con la lógica en el repositorio
<!-- id: (nuevo) ci/*.sh, .github/workflows/ci.yml, azure-pipelines.yml -->
<!-- entidades: — -->
<!-- impuesto_en: sin equivalente en el PHP (scripts/empaquetar.ps1 empaqueta a mano el último commit) -->
<!-- decision: 2026-10-22 alcance 1 | duda 16.3 (GitHub Actions o Azure DevOps, sin decidir) | 16.1 (código portátil) | D14 -->

Cada paso del pipeline DEBE vivir en un script del repositorio (`ci/backend.sh`, `ci/frontend.sh`, `ci/e2e.sh`, `ci/dependencias.sh`, `ci/imagen.sh`, `ci/respaldo_restauracion.sh`), ejecutable en Linux y, salvo el de la imagen, en el equipo Windows de desarrollo con Git Bash. Los archivos del orquestador solo llaman a esos scripts, en el mismo orden y con las mismas condiciones:

| Orquestador | Archivo | Papel |
|---|---|---|
| **GitHub Actions** (recomendado) | `.github/workflows/ci.yml` | Principal |
| Azure DevOps Pipelines | `azure-pipelines.yml` | Equivalente: mismos trabajos, mismos scripts, mismas puertas |

Se ejecuta en cada solicitud de cambio (pull request) hacia `main`, en cada push a `main` y en cada etiqueta `v*`. Cambiar de orquestador NO DEBE exigir tocar los scripts.

<!-- decision: recomendación de esta especificación — GitHub Actions: los ejecutores Linux traen Docker, Node, JDK y los navegadores de Playwright; Dependabot (avisos y actualizaciones de dependencias) es gratuito en repositorios privados; GHCR sirve de registro privado de la imagen; Trivy, gitleaks y Playwright tienen acciones mantenidas. Azure DevOps queda como equivalente si AGS ya lo usa o si el despliegue termina en Azure: la misma lógica corre con un ejecutor `ubuntu-latest` (1 trabajo paralelo gratuito en privados) y el registro sería ACR. -->

#### Scenario: Cambio de orquestador
<!-- test: sin prueba en el PHP -->
- **CUANDO** el repositorio se aloja en Azure DevOps en lugar de GitHub
- **ENTONCES** basta con `azure-pipelines.yml`: los trabajos, las puertas y los artefactos son los mismos, porque los dos llaman a `ci/*.sh`

#### Scenario: Correr un paso en el equipo
<!-- test: sin prueba en el PHP -->
- **CUANDO** un desarrollador corre `bash ci/backend.sh` en Git Bash
- **ENTONCES** obtiene el mismo resultado que el trabajo del pipeline, sin Docker

---

### Requirement: Trabajos, orden y puertas
<!-- id: (nuevo) ci.yml jobs -->
<!-- entidades: — -->
<!-- impuesto_en: README.md «Pruebas» (mvnw verify, npm test, Playwright) -->
<!-- decision: 2026-10-22 alcance 1 | GMI P71 c y P72 (gestión de vulnerabilidades, pruebas de seguridad) | AGS-35 (componentes sin fin de vida) -->

El pipeline DEBE correr estos trabajos; cualquiera que falle deja el pipeline en rojo y bloquea la fusión a `main`:

| # | Trabajo | Qué hace | Depende de |
|---|---|---|---|
| 1 | `secretos` | gitleaks sobre el cambio (y sobre toda la historia en `main`) | — |
| 2 | `backend` | JDK 21 Temurin, `./mvnw -B -ntp verify`: reglas (JUnit), integración y API con PostgreSQL 16 embebido (zonky), arquitectura (Modulith, ArchUnit). Publica los informes JUnit | — |
| 3 | `frontend` | Node 20, `npm ci`, `npm run typecheck`, `npm test` (Vitest), `npm run build`. Publica `frontend/dist` para el trabajo 4 | — |
| 4 | `e2e` | Playwright **Chromium** contra el arranque local (`DigsaLocal`, PostgreSQL embebido, correo a archivo) con la SPA del trabajo 3. Publica el informe HTML y el JUnit | 3 |
| 5 | `dependencias` | Trivy en modo sistema de archivos sobre `backend/**/pom.xml`, `frontend/package-lock.json` y `e2e/package-lock.json`; además `npm audit --omit=dev` del frontend como segunda opinión (informativa) | — |
| 6 | `imagen` | `docker buildx build` del Dockerfile, prueba de humo de la imagen (requisito siguiente), Trivy de la imagen y SBOM CycloneDX | 2, 3 |
| 7 | `publicar` | Solo en `main` y en etiquetas `v*`: sube la imagen al registro y los artefactos | 1 a 6 |
| 8 | `respaldo` | Prueba de respaldo y restauración (ver su requisito) | 6 |

Firefox y WebKit (motor de Safari, duda 16.7) DEBEN correr cada noche sobre `main` y en las etiquetas `v*`, no en cada solicitud de cambio (tardan el triple). Los trabajos 1, 2, 3 y 5 corren en paralelo.

#### Scenario: Prueba que falla
<!-- test: sin prueba en el PHP -->
- **CUANDO** una solicitud de cambio rompe una prueba de API
- **ENTONCES** el trabajo `backend` queda en rojo, no se construye ni publica la imagen y la fusión queda bloqueada

#### Scenario: Etiqueta de versión
<!-- test: sin prueba en el PHP -->
- **CUANDO** se crea la etiqueta `v1.4.0` sobre `main`
- **ENTONCES** corren los ocho trabajos más Firefox y WebKit, y se publica la imagen `digsa-suite:1.4.0`

---

### Requirement: Las pruebas corren igual en Linux que en Windows
<!-- id: (nuevo) e2e/playwright.config.ts webServer, e2e/package.json pretest -->
<!-- entidades: — -->
<!-- impuesto_en: e2e/playwright.config.ts (webServer con `..\\backend\\mvnw.cmd` y JAVA_HOME en %LOCALAPPDATA%); e2e/package.json (pretest con `powershell` y Get-NetTCPConnection) -->
<!-- decision: 2026-10-22 alcance 1 (pipeline portable) -->

El arranque del servidor de las pruebas de punta a punta DEBE elegir `mvnw` o `mvnw.cmd` según el sistema y tomar `JAVA_HOME` del entorno (en Windows, con la ruta de `%LOCALAPPDATA%` solo como respaldo). La limpieza de puertos previa DEBE ser un script de Node multiplataforma o saltarse cuando `CI` está definido. El ejecutor DEBE fijar `TZ=America/Bogota` y el idioma `es-CO`, porque las fechas de las reglas («hoy», plazos, festivos) se calculan en Bogotá. `backend/mvnw` DEBE quedar con permiso de ejecución en git y PostgreSQL embebido DEBE correr con un usuario que no sea root (zonky se niega con root).

#### Scenario: Ejecutor Linux
<!-- test: sin prueba en el PHP -->
- **CUANDO** el trabajo `e2e` corre en `ubuntu-latest`
- **ENTONCES** levanta el servidor con `../backend/mvnw` y pasan las mismas pruebas que en Windows

#### Scenario: Fecha cerca de medianoche
<!-- test: sin prueba en el PHP -->
- **CUANDO** el pipeline corre a las 00:30 UTC (19:30 en Bogotá)
- **ENTONCES** las pruebas que dependen de «hoy» usan la fecha de Bogotá y no fallan por el cambio de día en UTC

---

### Requirement: Umbral de vulnerabilidades y excepciones justificadas
<!-- id: (nuevo) ci/dependencias.sh, ci/imagen.sh, .trivyignore -->
<!-- entidades: — -->
<!-- impuesto_en: sin equivalente en el PHP -->
<!-- decision: GMI P71 c y P72 («gestión de vulnerabilidades, pruebas de seguridad») | AGS-35 y GMI P77 e (componentes vigentes, con soporte del fabricante y sin fin de vida) | Anexo 5 2.1 (legalidad del software) -->

Los trabajos `dependencias` e `imagen` DEBEN fallar con cualquier vulnerabilidad **CRITICAL** o **HIGH** que tenga versión corregida, y con una imagen base o un paquete del sistema marcados como fin de vida. Las que no tienen corrección se informan sin bloquear. Una excepción solo vale si está en `.trivyignore` con el identificador (CVE o GHSA), el motivo, quién la aceptó y una fecha de vencimiento de 90 días como máximo; vencida, vuelve a bloquear. El SBOM (CycloneDX, con las licencias de cada componente) se publica en cada versión como soporte de la legalidad del software (Anexo 5 2.1).

#### Scenario: Dependencia vulnerable con arreglo
<!-- test: sin prueba en el PHP -->
- **CUANDO** `poi-ooxml` tiene un CVE HIGH corregido en una versión menor
- **ENTONCES** `dependencias` falla y el informe dice el paquete, el CVE y la versión que lo corrige

#### Scenario: Excepción vencida
<!-- test: sin prueba en el PHP -->
- **CUANDO** una entrada de `.trivyignore` tiene fecha de vencimiento de ayer
- **ENTONCES** el trabajo vuelve a fallar por ese CVE

<!-- incierto: el umbral (HIGH con arreglo) es una recomendación; la DIGSA dice que los requisitos de ciberseguridad se precisarán en el Anexo Técnico definitivo (GMI P72). Se usa Trivy para dependencias e imagen porque es una sola herramienta, corre como contenedor en cualquier orquestador y no exige clave de la NVD (OWASP Dependency-Check sí, y tarda varios minutos por corrida). -->

---

### Requirement: La imagen que se prueba es la que se publica
<!-- id: (nuevo) ci/imagen.sh -->
<!-- entidades: — -->
<!-- impuesto_en: Dockerfile; docker-compose.yml (image: digsa-suite:${DIGSA_VERSION:-local}) -->
<!-- decision: D14 | duda 23.2 (construir la imagen en el pipeline) -->

El trabajo `imagen` DEBE construir la imagen una sola vez y, antes de publicarla, probarla: levanta `docker compose` con esa imagen, un PostgreSQL 16 nuevo y `DIGSA_ENTORNO=pruebas` con Mailpit; espera `healthy`; comprueba que Flyway aplicó todas las migraciones (`flyway_schema_history` sin filas fallidas), que `GET /actuator/health` responde 200, que `GET /` entrega la SPA y que `verificar_base_limpia.sql` da 0 en las tablas del proceso. Luego publica **esa misma** imagen, identificada por su digest, con estas etiquetas:

| Origen | Etiquetas |
|---|---|
| push a `main` | `sha-<commit corto>` y `main` |
| etiqueta `vX.Y.Z` | `X.Y.Z` y `sha-<commit corto>` |

Nunca `latest`. La imagen lleva las etiquetas OCI `org.opencontainers.image.version`, `.revision`, `.created` y `.source`, y la aplicación escribe al arrancar «Suite DIGSA <versión> (<commit>)» en el log.

#### Scenario: Imagen que no arranca
<!-- test: sin prueba en el PHP -->
- **CUANDO** una migración nueva falla contra la base vacía
- **ENTONCES** la prueba de humo falla y no se publica nada

#### Scenario: Trazabilidad de una versión
<!-- test: sin prueba en el PHP -->
- **CUANDO** TI pregunta qué código corre en el servidor
- **ENTONCES** el log de arranque y la etiqueta `revision` de la imagen dan el commit, y el digest coincide con el que publicó el pipeline

---

### Requirement: Secretos solo en las variables seguras del CI
<!-- id: (nuevo) -->
<!-- entidades: — -->
<!-- impuesto_en: .gitignore (.env, .env.*), .dockerignore (.env, .claude, .local), application.yml («Ningún secreto aquí»), README «Configuración» -->
<!-- decision: 2026-10-22 alcance 1 | AGS-34 («no hay que mostrar código ni llaves») | Ley 1581 -->

Las credenciales que necesita el pipeline (registro de la imagen y, si se usa, la firma) DEBEN guardarse solo en las variables seguras del orquestador (GitHub: *secrets* del entorno `publicacion`, con aprobación; Azure DevOps: grupo de variables secretas o enlazado a un almacén de secretos), nunca en el repositorio, en la imagen, en los artefactos ni en el log. Las pruebas no necesitan secretos: corren con PostgreSQL embebido, correo a archivo y datos ficticios; la contraseña del administrador de las pruebas de punta a punta (`DIGSA_E2E_ADMIN`) DEBE generarse al azar en cada corrida cuando `CI` está definido. Las solicitudes de cambio desde bifurcaciones no reciben secretos. El trabajo `secretos` bloquea cualquier cambio que agregue una clave, un token o un `.env`.

#### Scenario: Clave pegada por error
<!-- test: sin prueba en el PHP -->
- **CUANDO** un commit agrega `DIGSA_SMTP_CLAVE=…` en un archivo versionado
- **ENTONCES** el trabajo `secretos` falla y dice el archivo y la línea, sin repetir el valor

#### Scenario: Artefactos limpios
<!-- test: sin prueba en el PHP -->
- **CUANDO** se descargan los artefactos de una corrida
- **ENTONCES** no traen `.env`, `.local/`, almacenes de archivos ni correos de prueba

---

### Requirement: Artefactos publicados y paquete para TI
<!-- id: (nuevo) ci/publicar -->
<!-- entidades: — -->
<!-- impuesto_en: scripts/empaquetar.ps1 (ZIP del commit con su SHA-256); docs/DESPLIEGUE_TI.md §1 -->
<!-- decision: 2026-10-22 alcance 1 | Anexo 5 1.22 (mantenimiento evolutivo con trazabilidad) -->

El pipeline DEBE publicar:

| Artefacto | Cuándo | Retención |
|---|---|---|
| Imagen en el registro privado | `main` y `v*` | las de `v*` mientras dure el contrato; las de `main`, 90 días |
| Paquete para TI `digsa-suite_<versión>.tar.gz` (`docker save` de la imagen) con `docker-compose.yml`, `.env.example`, `docker/`, `herramientas/` y la guía de operación, más su `SHA256SUMS` | `v*` | mientras dure el contrato |
| SBOM CycloneDX e informes de Trivy | siempre | 90 días (`v*`: contrato) |
| Informes JUnit, Vitest y Playwright (HTML; trazas y videos solo de las pruebas fallidas) | siempre | 30 días |

El paquete existe para servidores sin salida al registro (un datacenter aislado en Colombia): TI lo verifica con `sha256sum -c SHA256SUMS` y lo carga con `docker load`.

#### Scenario: Servidor sin acceso al registro
<!-- test: sin prueba en el PHP -->
- **CUANDO** TI recibe `digsa-suite_1.4.0.tar.gz` y su `SHA256SUMS`
- **ENTONCES** verifica la huella, ejecuta `docker load -i …` y el compose arranca con `DIGSA_VERSION=1.4.0` sin descargar nada

---

### Requirement: La restauración se prueba en el pipeline
<!-- id: (nuevo) ci/respaldo_restauracion.sh -->
<!-- entidades: Archivo, OplSoporte, VisitaActa -->
<!-- impuesto_en: sin equivalente en el PHP (DESPLIEGUE_TI §7 solo nombra el respaldo) -->
<!-- decision: Anexo 5 3.6 («procedimientos de Back Up diarios, documentados, donde se verifique la restauración»; cronograma de pruebas de restablecimiento) | AGS-73/79 (restauración probada) -->

En cada etiqueta `v*` y cada noche, el trabajo `respaldo` DEBE: levantar el compose con la imagen del trabajo 6; cargar datos ficticios con la herramienta de carga (parte B) y crear por la API una visita con dos evidencias; ejecutar el respaldo de la guía (requisito «Respaldo» de la parte D); destruir los volúmenes (`docker compose down -v`); restaurar con la guía; y verificar que coinciden el número de filas de cada tabla, que `--digsa.verificar-archivos` no encuentra archivos faltantes ni con huella distinta y que el administrador ingresa. Así la guía de respaldo y restauración queda probada con los mismos comandos que usará TI.

#### Scenario: Ida y vuelta
<!-- test: sin prueba en el PHP -->
- **CUANDO** se respalda, se borran los volúmenes y se restaura
- **ENTONCES** las filas por tabla son iguales, las dos evidencias abren con su SHA-256 y la bitácora conserva sus filas

---

## B. Carga inicial de datos maestros

### Requirement: La carga es una herramienta de línea de comandos dentro de la imagen
<!-- id: (nuevo) CargaMaestrosCli -->
<!-- entidades: CargaMasiva, Punto, Auditor, Usuario -->
<!-- impuesto_en: identidad/CrearAdministrador.java (mismo patrón: ApplicationRunner con --digsa.crear-admin.*); controladores/maestros.controlador.php importarPuntos(), importarAuditores() (en el PHP es una pantalla) -->
<!-- decision: 2026-10-22 alcance 2 («decide si endpoint + pantalla o CLI; TI la correrá en el servidor con Docker») | 24.4 (faltan auditor asignado, municipio base y nivel central) | Anexo 5 2.3 (el contratista carga los establecimientos) -->

La carga inicial DEBE ser un modo de arranque de la misma imagen, como el primer administrador: se activa con `--digsa.carga.carpeta`, hace su trabajo y termina con un código de salida, sin levantar el servidor web. TI la ejecuta así:

```bash
# 1. Plantillas vacías, con listas desplegables e instrucciones (no requiere datos)
docker compose run --rm --no-deps -v "$PWD/carga:/carga" app --digsa.carga.plantillas=/carga
# 2. Simular (por defecto): revisa todo y no escribe nada
docker compose run --rm -v "$PWD/carga:/carga" app --digsa.carga.carpeta=/carga --digsa.carga.responsable=admin.digsa
# 3. Aplicar
docker compose run --rm -v "$PWD/carga:/carga" app --digsa.carga.carpeta=/carga --digsa.carga.responsable=admin.digsa --digsa.carga.modo=aplicar
```

| Código de salida | Significado |
|---|---|
| 0 | Sin errores (en `aplicar`, todo quedó cargado) |
| 1 | Uso no válido (falta un parámetro, carpeta vacía, responsable que no es administrador activo) |
| 2 | Falla técnica (base no disponible, archivo ilegible); no se escribió nada |
| 3 | Hay filas con errores; no se escribió nada |
| 4 | Cargado, pero algún correo de bienvenida no salió (ver el informe) |

`--digsa.carga.responsable` DEBE ser el usuario de un Administrador activo: queda como autor en la bitácora. La validación de cada fila vive en `reglas/ImportadorMaestros` (pura, sin Spring), de modo que la pantalla de `maestros-carga-masiva`, cuando se construya, dé exactamente los mismos resultados.

<!-- decision: recomendación de esta especificación — línea de comandos y no endpoint + pantalla, porque: (1) TI la corre en el servidor con Docker y el patrón ya existe (`CrearAdministrador`); (2) no abre una superficie nueva de subida y lectura de Excel en la web; (3) no la limita el tiempo de una petición HTTP ni la sesión de 10 minutos; (4) aplica las tres plantillas en una sola transacción, de todo o nada; (5) sirve antes de que haya usuarios que ingresen. El mantenimiento posterior (un punto nuevo, un auditor que llega) sigue en la pantalla Puntos y auditores; la carga masiva por pantalla de `maestros-carga-masiva` puede construirse después sobre las mismas reglas. -->

#### Scenario: Responsable no válido
<!-- test: sin prueba en el PHP -->
- **CUANDO** `--digsa.carga.responsable=coordinador1` no tiene el perfil Administrador
- **ENTONCES** termina con código 1 y «El responsable de la carga debe ser un administrador activo.», sin leer los archivos

#### Scenario: Sin servidor web
<!-- test: sin prueba en el PHP -->
- **CUANDO** se ejecuta la carga mientras la aplicación sigue en servicio
- **ENTONCES** el contenedor de la carga no abre el puerto 8080 ni corre las tareas programadas, y termina al acabar

---

### Requirement: Plantillas generadas por la herramienta
<!-- id: (nuevo) GeneradorPlantillas -->
<!-- entidades: — -->
<!-- impuesto_en: app/Support/ImportadorMaestros.php COLUMNAS, LARGOS, MARCA_EJEMPLO; docs/campos_usuarios_y_puntos.md -->
<!-- decision: 2026-10-22 alcance 2 | GMI P77 f (CSV «;», coma decimal, UTF-8) | decisión 7 del mockup (fila de ejemplo) -->

Con `--digsa.carga.plantillas=<carpeta>` la herramienta DEBE escribir `plantilla_carga_maestros.xlsx` con las hojas «Instrucciones», «Auditores», «Puntos», «Usuarios» y «Listas» (unidades, fuerzas, modalidades, roles técnicos, perfiles cargables, regionales activas y municipios con su código DIVIPOLA, tomados de la base), con validación de datos en las columnas de lista y una fila de ejemplo que contiene «borrar esta fila»; y las mismas tres hojas como `auditores.csv`, `puntos.csv` y `usuarios.csv` (UTF-8 con BOM, separador `;`). Ninguna plantilla tiene columna de contraseña. Columnas:

| Plantilla | Columnas (obligatorias en **negrilla**) | Clave natural |
|---|---|---|
| Auditores | **nombre**, **correo**, **documento**, teléfono, **rol** (químico farmacéutico, regente, auditor en salud, ingeniero de sistemas, otro), municipio_base (código o «Departamento / Municipio»), usuario | correo |
| Puntos | sigla, **nombre**, **unidad** (ESM, FA, NC), fuerza (EJC, ARC, FAC; vacía en NC), modalidad (obligatoria en ESM), nit (solo FA), divipola o departamento + municipio, dirección, contacto, correos, exige_qf, regional, llega_desde, auditor_asignado (correo), activo | sigla; sin sigla, nombre + unidad |
| Usuarios | **nombre**, **correo**, usuario, documento, teléfono, **perfiles** (separados por coma), alcance_fuerza (Sanidad), alcance_punto (sigla o nombre del ESM), alcance_regional (OPL regional), activo | correo |

Los encabezados se comparan sin mayúsculas, tildes ni espacios y admiten los sinónimos del PHP (`maestros-carga-masiva`, «Sinónimos de columnas»).

#### Scenario: Plantillas con los catálogos de la base
<!-- test: sin prueba en el PHP -->
- **CUANDO** se generan las plantillas en una base recién creada
- **ENTONCES** la hoja «Listas» trae las 4 fuerzas, las 8 regionales y los 1 122 municipios, y la columna `rol` solo deja elegir los cinco roles

---

### Requirement: Revisión del archivo antes de las filas
<!-- id: (nuevo) LectorPlantillas | ImportadorMaestros.revisarArchivo -->
<!-- entidades: CargaMasiva -->
<!-- impuesto_en: app/Support/ImportadorMaestros.php revisarArchivo(), OBLIGATORIAS -->
<!-- decision: GMI P77 f («se validan estructura, encabezados, tipos y duplicados; los archivos con errores se rechazan con el detalle») | Ley 1581 -->

La carpeta DEBE traer un `.xlsx` con las hojas de la plantilla, o uno o varios de `auditores.csv`, `puntos.csv` y `usuarios.csv`; si trae los dos formatos, se rechaza («Use la plantilla de Excel o los CSV, no ambos.»). Se rechaza el archivo completo, sin mirar las filas, cuando:
- no es `.xlsx` ni `.csv` (`.xls` y `.xlsm` no se aceptan: formato viejo o con macros);
- pesa más de 10 MB o una hoja trae más de 5 000 filas;
- el CSV no es UTF-8 válido («Guarde el archivo como CSV UTF-8 (delimitado por punto y coma).») o su encabezado no se separa con `;`;
- le falta una columna obligatoria, o trae una columna de contraseña (`clave`, `contraseña`, `password`, sus variantes): «Las plantillas no llevan contraseñas: cada usuario recibe un enlace para definirla.»

Las columnas desconocidas se informan sin rechazar. Una celda de Excel con fórmula se lee por su valor calculado y se avisa en el informe. El `.xlsx` se lee en flujo y con los límites de descompresión de POI (archivo comprimido malicioso).

#### Scenario: Columna de contraseña
<!-- test: sin prueba en el PHP -->
- **CUANDO** `usuarios.csv` trae la columna «Contraseña»
- **ENTONCES** se rechaza el archivo con el mensaje de arriba y el informe no repite ningún valor de esa columna

#### Scenario: CSV en ANSI
<!-- test: sin prueba en el PHP -->
- **CUANDO** `puntos.csv` viene en Windows-1252 con «Bogotá»
- **ENTONCES** se rechaza con «Guarde el archivo como CSV UTF-8…»

---

### Requirement: Validación de cada fila
<!-- id: ImportadorMaestros.puntos | ImportadorMaestros.auditores | (nuevo) ImportadorMaestros.usuarios -->
<!-- entidades: Punto, Auditor, Usuario, Perfil, Municipio, Regional, Fuerza -->
<!-- impuesto_en: app/Support/ImportadorMaestros.php puntos(), auditores(), rol(), unidad(), fuerza(), municipio(); app/Support/Alcance.php combinacionInvalida(); ajax/usuarios.ajax.php alcanceDelPost() -->
<!-- decision: decisiones 5, 6, 26 y 2a | perfiles-alcance «Combinaciones de perfiles no válidas» y «Crear o editar un usuario» | 2026-10-20 14.3 -->

Cada fila DEBE validarse con las reglas de `maestros-carga-masiva` («Validación de cada punto», «Validación de cada auditor») y de `perfiles-alcance` («Crear o editar un usuario», «Combinaciones de perfiles no válidas»), más estas:
- **Puntos:** la sigla, si viene, cumple `^[A-Z0-9-]{2,20}$` (mayúsculas al leer); un ESM sin modalidad es error; el nivel central va con fuerza NA; `auditor_asignado` es el correo de un auditor activo **de la base o de la hoja de auditores de esta misma carga**.
- **Auditores:** documento obligatorio (decisión 26) con `^[A-Za-z0-9.\-]{3,30}$`; rol reconocible; municipio base existente.
- **Usuarios:** perfiles por su nombre vigente (los nombres son editables, V8) o por su número; el perfil **Administrador no se acepta** («Los administradores se crean con --digsa.crear-admin.»); el perfil Auditor tampoco (va en la hoja de auditores, que exige documento y rol); el alcance obligatorio según el perfil (Sanidad → fuerza con sanidad; ESM → ESM activo, por sigla o nombre, **de la base o de la hoja de puntos de esta carga**; OPL regional → regional activa); el alcance que no aplica, vacío; las combinaciones no válidas son error.
- **Todas:** correo válido en minúscula; `usuario`, si viene, con `^[a-z0-9._-]{3,50}$` (si no viene, se deriva del correo como en `CrearAdministrador.usuarioLibre`); repetidos dentro del archivo son error de la segunda fila («repetido en el archivo (fila N)»); las filas vacías se ignoran; la fila de ejemplo es error.

#### Scenario: Referencias dentro de la misma carga
<!-- test: sin prueba en el PHP -->
- **CUANDO** la hoja de puntos asigna un ESM a `qf.norte@ags…`, que solo existe en la hoja de auditores, y la hoja de usuarios crea el perfil ESM de ese punto nuevo
- **ENTONCES** la simulación no da error: auditores, puntos y usuarios se resuelven en ese orden

#### Scenario: Administrador en la plantilla
<!-- test: sin prueba en el PHP -->
- **CUANDO** una fila de usuarios trae el perfil Administrador
- **ENTONCES** es error de esa fila con «Los administradores se crean con --digsa.crear-admin.»

#### Scenario: Casos del PHP
<!-- test: tests/importador_maestros_test.php:36-130 (pasan a JUnit en reglas/ImportadorMaestrosTest) -->
- **CUANDO** se cargan las 8 filas de puntos del mockup v6 (3 nuevas, 1 existente, unidad «XYZ», repetida, municipio 99999 y vacía)
- **ENTONCES** 3 nuevos, 1 a actualizar, 3 errores y la vacía ignorada, igual que en el PHP

---

### Requirement: Simular por defecto, aplicar todo o nada
<!-- id: (nuevo) ServicioCargaMaestros.simular, .aplicar -->
<!-- entidades: CargaMasiva, Punto, Auditor, Usuario, Bitacora -->
<!-- impuesto_en: controladores/maestros.controlador.php importarPuntos() (sin confirmar = vista previa; con confirmar aplica las válidas y salta las que tienen error) -->
<!-- decision: 2026-10-22 alcance 2 («modo simular que reporta errores por fila sin escribir») | GMI P77 f (los archivos con errores se rechazan con el detalle) -->

Sin `--digsa.carga.modo=aplicar`, la herramienta DEBE **simular**: valida, resuelve las referencias y ejecuta los cambios dentro de una transacción que siempre se revierte (así también salen los choques que solo detecta la base), escribe el informe y termina con 0 o 3. Con `aplicar`, DEBE repetir la validación completa y, **si hay una sola fila con error, no escribe nada** (código 3); si no hay errores, aplica auditores, puntos y usuarios en una sola transacción, en ese orden, y la confirma. Dos cargas a la vez no se cruzan: la transacción toma un candado consultivo de PostgreSQL y la segunda termina con 2 y «Hay otra carga en curso.».

<!-- decision: el PHP aplica las filas válidas y salta las erradas. Para la carga inicial se sigue GMI P77 f: todo o nada, para que TI no deje la base a medias y pueda corregir y repetir. -->

#### Scenario: Simulación con errores
<!-- test: sin prueba en el PHP -->
- **CUANDO** se simula una carga de 40 auditores, 185 puntos y 60 usuarios con 2 errores
- **ENTONCES** el informe lista las 2 filas con su motivo, cuenta nuevos, a actualizar y sin cambios, la base queda igual y el código es 3

#### Scenario: Aplicar con un error
<!-- test: sin prueba en el PHP -->
- **CUANDO** se aplica la misma carpeta sin corregir
- **ENTONCES** no se escribe ninguna fila, no sale ningún correo y el código es 3

#### Scenario: Choque que solo ve la base
<!-- test: sin prueba en el PHP (maestros-carga-masiva «Choque de nombres en la base») -->
- **CUANDO** dos puntos quedarían con el mismo nombre y unidad según la intercalación de la base
- **ENTONCES** la simulación ya lo informa como error y la aplicación no escribe nada

---

### Requirement: Idempotente por clave natural
<!-- id: (nuevo) ServicioCargaMaestros -->
<!-- entidades: Punto, Auditor, Usuario -->
<!-- impuesto_en: modelos/puntos.modelo.php aplicarCarga() (celdas vacías no borran); app/Support/ImportadorMaestros.php claveUnidad() -->
<!-- decision: 2026-10-22 alcance 2 («actualiza por clave natural») | V7 (los 185 puntos ya existen con sigla) -->

La carga DEBE crear lo que no existe y actualizar lo que existe, buscando por la clave natural (puntos: sigla; sin sigla, nombre normalizado + unidad; auditores y usuarios: correo). Además:
- si la sigla apunta a un punto y el nombre + unidad a otro, es error de la fila;
- si el correo existe con otro `usuario` distinto del de la fila, es error;
- una celda vacía no borra el valor guardado;
- los perfiles de la fila se **agregan**; los que el usuario ya tenía se conservan (quitarlos es de la pantalla Usuarios) y el informe los lista;
- un usuario existente que aparece en la hoja de auditores recibe el perfil Auditor y su fila de `auditores` (si la combinación lo permite);
- nada se borra ni se inactiva por no venir en el archivo; `activo` solo cambia si la columna trae valor;
- una fila igual a lo guardado cuenta como «sin cambios» y no deja bitácora.

#### Scenario: Segunda corrida
<!-- test: sin prueba en el PHP -->
- **CUANDO** se aplica dos veces la misma carpeta
- **ENTONCES** la segunda da 0 nuevos, 0 actualizados, todo «sin cambios», no envía correos y no agrega bitácora salvo el resumen

#### Scenario: Completar los puntos del anexo
<!-- test: sin prueba en el PHP (duda 24.4) -->
- **CUANDO** la hoja de puntos trae solo sigla, correos y auditor asignado de los 74 ESM de dispensación de V7
- **ENTONCES** se actualizan esos dos campos por sigla, sin tocar nombre, dirección ni horario

---

### Requirement: Sin contraseñas: los usuarios nuevos reciben el enlace
<!-- id: (nuevo) ServicioCargaMaestros.enviarEnlaces -->
<!-- entidades: Usuario, Correo -->
<!-- impuesto_en: identidad/CrearAdministrador.java (clave aleatoria que nadie conoce + claves.enviarEnlace después del commit); perfiles-alcance «Crear o editar un usuario» -->
<!-- decision: 2026-10-22 alcance 2 («sin contraseñas en las plantillas; los usuarios reciben el enlace») | AGS-34 | duda 17.20 -->

Cada usuario nuevo (de las hojas de auditores y de usuarios) DEBE quedar activo, con una contraseña aleatoria que nadie conoce y `clave_definida = false`. **Después de confirmar** la transacción, la herramienta envía a cada uno «Bienvenido a la Suite DIGSA» con el enlace de un solo uso, de a uno, con una pausa configurable entre envíos (`--digsa.carga.pausa-ms`, 500 por defecto) para no disparar los topes del servidor de correo. Un correo que no sale no deshace la carga: el informe lo marca y el código final es 4; el administrador lo resuelve con «Restablecer contraseña». Con `--digsa.carga.enlaces=no` se carga sin enviar, y `--digsa.carga.enlaces=pendientes` envía después el enlace a los usuarios activos que nunca definieron la contraseña. A un usuario que ya existía no se le envía nada.

#### Scenario: Envío por lotes
<!-- test: sin prueba en el PHP -->
- **CUANDO** se aplican 60 usuarios nuevos y el correo rechaza 1
- **ENTONCES** quedan creados los 60, salen 59 enlaces, el informe marca el rechazado y el código es 4

#### Scenario: Enlaces diferidos
<!-- test: sin prueba en el PHP -->
- **CUANDO** se carga con `--digsa.carga.enlaces=no` y una semana después se corre `--digsa.carga.enlaces=pendientes`
- **ENTONCES** reciben el enlace solo los que aún no definieron su contraseña

<!-- duda: el enlace vence a los 60 minutos (identidad-sesion). En una carga de decenas de usuarios muchos no lo abrirán a tiempo y tendrán que usar «Olvidé mi contraseña». Se recomienda una vigencia propia para el enlace de bienvenida (por ejemplo 72 horas, parámetro) o enviar por grupos con `enlaces=pendientes`. Confirmar. -->

---

### Requirement: Informe, bitácora y datos personales
<!-- id: (nuevo) InformeCarga -->
<!-- entidades: Bitacora, CargaMasiva -->
<!-- impuesto_en: controladores/maestros.controlador.php (registra `puntos_carga` con nuevos, actualizados y errores); reglas/Bitacora.java TIPOS -->
<!-- decision: Ley 1581 de 2012 | 2026-10-20 15 (valores de antes y después en la bitácora) | Anexo 5 3.1 (logs de inserción y actualización) -->

Cada corrida DEBE escribir en la carpeta `resultado_carga_<aaaammdd_hhmm>.csv` (UTF-8 con BOM, `;`) con una fila por fila leída: archivo, hoja, fila, clave natural, acción (`nuevo`, `actualizar`, `sin cambios`, `error`, `correo no enviado`), campos que cambian y motivo. En la consola y en el log solo salen los conteos y, por cada error, archivo, fila, campo y motivo, **sin valores** (ni nombres, documentos, teléfonos ni correos). En `aplicar`, la bitácora DEBE registrar: un resumen `carga_maestros` (responsable, origen `cli`, modo, SHA-256 de cada archivo, nuevos, actualizados, sin cambios y correos no enviados) y una fila por registro creado o actualizado con su tipo de siempre (`punto_creado`, `punto_editado`, `auditor_editado`, `usuario_creado`, …) y los valores de antes y después, con el documento y el teléfono enmascarados. La simulación deja solo el resumen `carga_maestros_simulada`.

Las plantillas y el informe contienen datos personales: la guía de operación DEBE indicar que se transfieren al servidor por un canal cifrado (no por correo), se montan en solo lectura cuando se puede, no se guardan en el repositorio (`carga/` en `.gitignore`) y se borran del servidor al terminar.

#### Scenario: Log sin datos personales
<!-- test: sin prueba en el PHP -->
- **CUANDO** la fila 12 de auditores tiene el documento «12.345.678-x» no válido
- **ENTONCES** la consola dice «auditores fila 12 · documento: no válido (solo letras, números, punto o guion)» sin el documento, y el informe CSV sí lo identifica por su correo

---

## C. Brechas del dashboard (dudas 25.3 y 25.4)

### Requirement: Resultados por categoría con «sin calificar» y las obligaciones
<!-- id: ModeloVisitas.resultadosDashboard (categorias) -->
<!-- entidades: VisitaRespuesta, VisitaObligacion, ChkComponente, VisitaComponente -->
<!-- impuesto_en: modelos/visitas.modelo.php resultadosDashboard() ($categorias: c, nc, na, pend por visita_resumen_componentes); tablero/ServicioDashboard.java resultados() porComponente (sin pend ni obligaciones) -->
<!-- decision: duda 25.3 | riesgo-tablero «Resultados de las visitas cerradas» | dudas PHP 3.5 y 8.6 -->

«Resultados por categoría» DEBE traer, para las visitas cerradas del filtro, una fila por componente de la versión vigente del checklist **más la categoría «Obligaciones del contrato»** (las de `visitas_obligaciones`), cada una con Cumple, No cumple, No aplica y **Sin calificar**, también las que suman 0. «Sin calificar» cuenta, por visita, los ítems de sus componentes asignados (`visitas_componentes`) y las obligaciones de su prestador que no tienen calificación. Orden: No cumple descendente y luego el nombre. La tabla reemplaza «Resultados por componente».

#### Scenario: Obligaciones y pendientes
<!-- test: sin prueba en el PHP (tests/e2e_local.php:517 cubre el resumen del seguimiento) -->
- **CUANDO** una visita cerrada tiene 2 obligaciones en No cumple y 3 ítems de C05 sin calificar
- **ENTONCES** «Obligaciones del contrato» muestra NC 2 y C05 muestra Sin calificar 3

#### Scenario: Componente sin datos
<!-- test: sin prueba en el PHP -->
- **CUANDO** ningún ítem de C12 se calificó en el periodo
- **ENTONCES** C12 aparece con 0 en las cuatro columnas

<!-- incierto: un seguimiento solo califica los hallazgos verificados; sus ítems no verificados NO DEBEN contar como «sin calificar». Se recomienda contar solo los ítems con fila en visitas_respuestas para los seguimientos (origen_id no nulo). -->

---

### Requirement: Resultados por prestador, con «Sin prestador»
<!-- id: ModeloVisitas.resultadosDashboard (prestadores) -->
<!-- entidades: Visita, Punto, Opl -->
<!-- impuesto_en: modelos/visitas.modelo.php resultadosDashboard() (COALESCE(r.prestador, 'Sin prestador')); app/Support/ResumenChecklist.php prestador; tablero/ServicioDashboard.java porPrestador (CASE por unidad) -->
<!-- decision: duda 25.3 («el nivel central cuenta como operador logístico») -->

«Resultados por prestador» DEBE agrupar las visitas cerradas del filtro en «Operador logístico» (ESM y nivel central), «Farmacia alterna» (FA) y «Sin prestador» (visitas cuyo punto no tiene una unidad de esas tres, o cerradas sin resumen), con C, NC, NA y Sin calificar, ordenadas por No cumple descendente. «Sin prestador» solo aparece si tiene visitas.

#### Scenario: Nivel central
<!-- test: sin prueba en el PHP -->
- **CUANDO** se cierra una visita a un punto NC con 4 No cumple
- **ENTONCES** suma 4 en «Operador logístico»

<!-- incierto: el PHP agrupa por el nombre del prestador guardado en el resumen (razón social del OPL o nombre de la farmacia). Si la DIGSA quiere ver cada farmacia alterna por separado, la fila «Farmacia alterna» se abre por punto FA. Se recomienda dejar las tres filas y confirmarlo. -->

---

### Requirement: Vacíos de mayor riesgo con No cumple
<!-- id: ModeloVisitas.resultadosDashboard (vacios) -->
<!-- entidades: ChkVacio, ChkItem, VisitaRespuesta -->
<!-- impuesto_en: modelos/visitas.modelo.php resultadosDashboard() (visita_resumen_vacios); vistas/js/dashboard.js; V3 chk_vacios y chk_items.prioridad -->
<!-- decision: duda 25.3 | novena ronda («vacío prioritario en trámite») | 2026-10-21 D5 -->

El dashboard DEBE mostrar «Vacíos de mayor riesgo con No cumple» con **todos** los vacíos de la versión vigente (`chk_vacios`, 5 en la v6), también en 0, cada uno con el número de ítems en No cumple de las visitas cerradas del filtro cuyo `chk_items.prioridad` es ese vacío, ordenados por número de vacío. El ítem en No cumple marcado «habilitación en trámite» cuenta (D5: es hallazgo).

#### Scenario: Todos los vacíos
<!-- test: sin prueba en el PHP -->
- **CUANDO** solo hay No cumple en ítems del vacío 2
- **ENTONCES** la tabla muestra los 5 vacíos, el 2 con su número y los demás en 0

<!-- incierto: la novena ronda excluía el trámite de esta tabla cuando no era hallazgo; con D5 se recomienda contarlo (ya lo dice riesgo-tablero). Confirmar. -->

---

### Requirement: La descarga del dashboard trae las mismas cifras de la pantalla
<!-- id: ControladorDashboard.exportar -->
<!-- entidades: — -->
<!-- impuesto_en: tablero/ExportadorTablero.java dashboard() (sin habilitación, categorías, prestador ni vacíos) -->
<!-- decision: duda 25.3 | Anexo 5 2.10 (las cifras no cambian entre usuarios con los mismos filtros) | AGS-36 | VCO P27 -->

La descarga (TXT, CSV, Excel, PDF) DEBE construirse con la **misma respuesta** del servicio que pinta la pantalla para esos filtros y DEBE traer, además de lo que ya trae, las tablas «Habilitación» (habilitado, no habilitado en trámite, no habilitado sin soporte), «Resultados por categoría» (con Sin calificar y las obligaciones), «Resultados por prestador» y «Vacíos de mayor riesgo con No cumple», con 2 decimales y coma decimal donde haya promedios.

#### Scenario: Pantalla y CSV
<!-- test: sin prueba en el PHP (ampliar e2e/pruebas/riesgo-tablero.spec.ts) -->
- **CUANDO** el coordinador filtra por EJC y descarga el CSV
- **ENTONCES** cada número de las cuatro tablas nuevas coincide con el de la pantalla (la prueba compara el JSON de `/api/dashboard` con el CSV)

---

### Requirement: Tarjetas por ESM en el celular
<!-- id: (nuevo) Riesgo.tsx TarjetasEsm -->
<!-- entidades: — -->
<!-- impuesto_en: frontend/src/paginas/Riesgo.tsx (Table dentro de ScrollArea con miw); vistas/js/riesgo.js (barras por factor) -->
<!-- decision: duda 25.4 | riesgo-tablero «Tablero de riesgo por país, fuerza y ESM» (escenario «Pantalla»: celular sin desplazamiento horizontal) | Anexo 5 1.18 (navegadores) -->

Por debajo de 768 px (punto `sm` de Mantine), el Dashboard de riesgo DEBE mostrar cada ESM como una tarjeta, en el mismo orden de la tabla, con: nombre, fuerza y municipio; puntaje y nivel (con texto, no solo color); una barra por factor con «valor de peso»; última visita cerrada o «Sin visita cerrada» y días desde ella; y los avisos («Visita sin resumen…»). «Por fuerza» pasa también a tarjetas. Desde 768 px sigue la tabla. Ni la página ni las tarjetas se desplazan en horizontal a 360 px.

#### Scenario: Celular de 360 px
<!-- test: e2e/pruebas/riesgo-tablero.spec.ts:276 (ampliar: tarjetas visibles y tabla oculta) -->
- **CUANDO** el coordinador abre «Dashboard de riesgo» con 360 × 800
- **ENTONCES** ve una tarjeta por ESM con sus cinco barras, no ve la tabla y `scrollWidth` de la página y de cada tarjeta no supera su ancho

#### Scenario: Lector de pantalla
<!-- test: sin prueba en el PHP -->
- **CUANDO** un lector de pantalla recorre una tarjeta
- **ENTONCES** anuncia el nombre del ESM como encabezado y el nivel como texto («Riesgo alto, 72 de 100»)

---

## D. Despliegue sin IaC: lo que queda listo

### Requirement: La imagen y el compose son la unidad de despliegue
<!-- id: Dockerfile | docker-compose.yml -->
<!-- entidades: — -->
<!-- impuesto_en: Dockerfile; docker-compose.yml; plataforma/ValidacionArranque.java -->
<!-- decision: D14 | 16.1 | 2026-10-22 alcance 4 (IaC fuera de alcance) -->

El despliegue en cualquier servidor (datacenter en Colombia o nube con región en Colombia) DEBE hacerse con la imagen publicada por el pipeline y el `docker-compose.yml` del repositorio, cambiando solo el `.env`. El compose DEBE fijar la versión con `DIGSA_VERSION` (sin construir en el servidor: `build` queda solo para el equipo de desarrollo, con un archivo `docker-compose.override.yml` o el perfil `local`), no publicar el puerto de PostgreSQL, publicar la aplicación en `127.0.0.1` salvo que `DIGSA_ESCUCHA` diga otra cosa (detrás del proxy https) y conservar `no-new-privileges`. La aplicación corre en **una sola réplica** mientras las tareas diarias (`TareaDiariaOpl`, `TareaRecordatorioSeguimiento`) no tengan candado.

#### Scenario: Servidor nuevo
<!-- test: ci/imagen.sh (prueba de humo) -->
- **CUANDO** TI copia el compose y el `.env`, ejecuta `docker compose pull` (o `docker load`) y `docker compose up -d`
- **ENTONCES** la aplicación arranca sin compilar nada en el servidor

<!-- incierto: con dos réplicas (16.8, alta disponibilidad) las tareas de las 00:15 y 06:30 correrían dos veces. Antes de escalar: ShedLock o `pg_try_advisory_lock` en cada tarea (16.4). -->

---

### Requirement: Variables completas y documentadas
<!-- id: .env.example | application.yml -->
<!-- entidades: — -->
<!-- impuesto_en: .env.example; application.yml; README «Configuración»; docs/guia_despliegue_prueba.md §4 -->
<!-- decision: D14 | 17.11 -->

`.env.example` DEBE listar **todas** las variables que lee `application.yml` y el compose, con su valor por defecto y si es obligatoria fuera de local (hoy faltan, entre otras, `DIGSA_VERSION`, `DIGSA_ESCUCHA`, `DIGSA_CLAVE_VIGENCIA_DIAS`, `DIGSA_DB_POOL`, `DIGSA_ARCHIVOS_CARPETA` y las de las tareas diarias). Una prueba DEBE comparar los marcadores `${DIGSA_…}` de `application.yml` con `.env.example` y fallar si falta alguna. Sus comentarios no DEBEN dar por hecho Azure: «en el gestor de secretos del servidor (Key Vault en Azure u otro)».

#### Scenario: Variable nueva sin documentar
<!-- test: sin prueba en el PHP -->
- **CUANDO** un cambio agrega `${DIGSA_SMS_MODO}` en `application.yml` y no en `.env.example`
- **ENTONCES** falla la prueba del trabajo `backend`

---

### Requirement: Verificación de salud
<!-- id: Dockerfile HEALTHCHECK | management.endpoint.health -->
<!-- entidades: — -->
<!-- impuesto_en: Dockerfile (curl a /actuator/health); application.yml (show-details never, health de correo desactivado) -->
<!-- decision: 2.2 y AGS-33 (99,74 % mensual) | revisión del 2026-10-22 (el healthcheck apuntaba a una ruta inexistente) -->

La aplicación DEBE exponer `/actuator/health/liveness` (el proceso responde) y `/actuator/health/readiness` (además, la base responde y el almacén de archivos es escribible), sin detalles hacia afuera. El `HEALTHCHECK` de la imagen usa `readiness`; un orquestador usa las dos. Una caída del correo no marca la aplicación como caída.

#### Scenario: Volumen sin permiso
<!-- test: sin prueba en el PHP -->
- **CUANDO** `/datos/archivos` pertenece a root y la aplicación no puede escribir
- **ENTONCES** `readiness` responde 503, el contenedor queda `unhealthy` y el log dice «El almacén de archivos no es escribible (/datos/archivos).»

---

### Requirement: Respaldo de la base y de los archivos, juntos
<!-- id: (nuevo) docker/respaldar.sh, docker/respaldar.ps1 -->
<!-- entidades: Archivo, OplSoporte, VisitaActa, VisitaFirma -->
<!-- impuesto_en: docs/DESPLIEGUE_TI.md §7 (base + carpeta de archivos, con la misma frecuencia; una sin la otra no sirve); docs/guia_docker_local.md §4; docs/guia_despliegue_prueba.md §7 punto 10 -->
<!-- decision: Anexo 5 1.23 y 3.6 (backups diarios, bitácora de respaldos, custodia) | AGS-73/79 y AGS-29 (respaldos en Colombia; un espacio en la nube del contratista no es entrega) | AGS-41 (RPO y RTO los propone el contratista) -->

El respaldo DEBE copiar **primero la base y después los archivos** (así todo archivo referenciado por la base está en la copia; sobran a lo sumo archivos huérfanos), sin detener la aplicación, con estos comandos (iguales en Bash y en PowerShell, porque el binario nunca pasa por una redirección de la consola):

```bash
F=$(date +%Y%m%d_%H%M)        # PowerShell: $F = Get-Date -Format yyyyMMdd_HHmm
mkdir -p respaldos
# 1. Base: formato propio de pg_dump, comprimido, dentro del contenedor; luego se copia afuera
docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d digsa -Fc -Z 6 -f /tmp/digsa.dump && pg_restore -l /tmp/digsa.dump > /dev/null'
docker compose cp db:/tmp/digsa.dump respaldos/digsa_$F.dump
docker compose exec -T db rm -f /tmp/digsa.dump
# 2. Archivos (evidencias, actas, soportes del OPL): con el usuario de la aplicación
docker compose exec -T app tar -czf /tmp/archivos.tgz -C /datos archivos
docker compose cp app:/tmp/archivos.tgz respaldos/archivos_$F.tgz
docker compose exec -T app rm -f /tmp/archivos.tgz
# 3. Huellas y bitácora de respaldos (Anexo 5 3.6)
sha256sum respaldos/digsa_$F.dump respaldos/archivos_$F.tgz > respaldos/respaldo_$F.sha256
```

`docker/respaldar.sh` y `docker/respaldar.ps1` hacen lo mismo, agregan una línea a `respaldos/bitacora_respaldos.csv` (fecha, versión de la imagen, archivos, tamaños, huellas, resultado) y terminan con código distinto de 0 si algo falla, para que el programador de tareas del servidor alerte. Se programa una vez al día como mínimo (Anexo 5 3.6). La copia se lleva a un medio y una ubicación **en Colombia**, distintos del servidor. La exportación completa (`exportacion-alertas`) es la entrega de datos a la DIGSA, no un respaldo.

#### Scenario: Respaldo diario
<!-- test: ci/respaldo_restauracion.sh -->
- **CUANDO** corre el respaldo programado
- **ENTONCES** quedan `digsa_<F>.dump`, `archivos_<F>.tgz`, sus huellas y una línea «ok» en la bitácora de respaldos

#### Scenario: Base caída durante el respaldo
<!-- test: sin prueba en el PHP -->
- **CUANDO** el contenedor `db` no responde
- **ENTONCES** el script termina con código 1, no copia los archivos y la bitácora registra «falló: base no disponible»

---

### Requirement: Restauración probada
<!-- id: (nuevo) docker/restaurar.sh, docker/restaurar.ps1, herramientas/verificar_restauracion.sql, --digsa.verificar-archivos -->
<!-- entidades: Archivo, OplSoporte, VisitaActa -->
<!-- impuesto_en: sin equivalente en el PHP (DESPLIEGUE_TI §3.5 solo advierte no restaurar una base local en el servidor) -->
<!-- decision: Anexo 5 1.23 (capacitar a la DIGSA en la restauración) y 3.6 (procedimiento de restauración y pruebas en un servidor alterno) | AGS-73/79 -->

La restauración DEBE hacerse sobre una base vacía y con la aplicación detenida, con la **misma versión de la imagen o una posterior** a la del respaldo:

```bash
F=20261122_0100                                    # el respaldo elegido
sha256sum -c respaldos/respaldo_$F.sha256          # 1. huellas
docker compose stop app                            # 2. nadie escribe
docker compose cp respaldos/digsa_$F.dump db:/tmp/digsa.dump
docker compose exec -T db sh -c 'dropdb -U "$POSTGRES_USER" --if-exists digsa && createdb -U "$POSTGRES_USER" -O "$POSTGRES_USER" digsa'
docker compose exec -T db sh -c 'pg_restore -U "$POSTGRES_USER" -d digsa --exit-on-error --single-transaction /tmp/digsa.dump && rm -f /tmp/digsa.dump'
# 3. archivos: se vacía la carpeta y se extrae con el usuario de la aplicación
docker compose run --rm --no-deps -v "$PWD/respaldos:/respaldos:ro" --entrypoint sh app -c "rm -rf /datos/archivos/* && tar -xzf /respaldos/archivos_$F.tgz -C /datos"
docker compose up -d app                           # 4. Flyway valida; si la imagen es más nueva, migra
docker compose run --rm app --digsa.verificar-archivos   # 5. cada archivo existe y su SHA-256 coincide
```

El rol de la aplicación (`DIGSA_DB_USUARIO`) DEBE existir con el mismo nombre antes del paso 2: lo crea `01-rol-app.sh` en un volumen nuevo; en una base administrada lo crea el DBA. Si el nombre cambia, se restaura con `--no-owner --no-privileges` y luego se aplican los permisos mínimos con `herramientas/permisos_rol_app.sql`. `--digsa.verificar-archivos` recorre `archivos` y `opl_soportes`, recalcula cada SHA-256, informa faltantes y huellas distintas (sin nombres de personas) y termina con 0 solo si todo coincide. El resultado se anota en la bitácora de respaldos («restauración»), y la prueba en un servidor alterno se agenda según el plan de contingencia.

#### Scenario: Restauración en un servidor alterno
<!-- test: ci/respaldo_restauracion.sh -->
- **CUANDO** se restaura el respaldo de anoche en otro servidor con la misma versión
- **ENTONCES** la aplicación arranca, `verificar-archivos` termina con 0 y los conteos de `verificar_restauracion.sql` coinciden con los anotados en el respaldo

#### Scenario: Imagen más vieja que el respaldo
<!-- test: sin prueba en el PHP -->
- **CUANDO** se restaura un respaldo hecho con la versión 1.5.0 en un servidor con 1.4.0
- **ENTONCES** la guía exige subir antes a 1.5.0 o posterior: la versión vieja no conoce las migraciones nuevas y el esquema puede no servirle

<!-- incierto: los valores de RPO (≤ 24 h, con respaldo diario) y RTO (≤ 8 h) son la propuesta del análisis (AGS-41); los aprueba la DIGSA en el plan de continuidad. Con una base administrada, el respaldo de la base lo da el proveedor (punto en el tiempo) y el script cubre solo los archivos y la copia en el medio de custodia. -->

---

### Requirement: Actualización de versión con Flyway y vuelta atrás
<!-- id: (nuevo) guía de operación «Actualizar» -->
<!-- entidades: — -->
<!-- impuesto_en: application.yml (spring.flyway con el rol dueño); docs/DESPLIEGUE_TI.md §3 «Actualizar una base ya instalada» (migraciones a mano en el PHP); docs/guia_docker_local.md §3 -->
<!-- decision: AGS-33 y GMI P74 (la disponibilidad excluye los mantenimientos autorizados) | Anexo 5 2.6 (campos nuevos en 10 días) y 1.22 -->

La actualización DEBE seguir estos pasos: (1) anunciar la ventana de mantenimiento; (2) respaldo completo (requisito anterior); (3) `docker compose pull` o `docker load` de la versión nueva; (4) poner `DIGSA_VERSION` en el `.env` y `docker compose up -d app`: al arrancar, Flyway aplica con el rol dueño las migraciones pendientes, en orden y cada una en su transacción, bajo un candado de la base (dos réplicas no migran a la vez), y valida las ya aplicadas; (5) esperar `healthy`, revisar en el log «Suite DIGSA <versión>» y «Successfully applied N migrations», e ingresar con un usuario de prueba; (6) anotar versión anterior y nueva en la bitácora de respaldos.

Para que la vuelta atrás sea posible, cada migración DEBE ser **compatible con la versión anterior** (agregar tablas, columnas que admiten nulo o tienen valor por defecto, índices); borrar o renombrar se hace en dos versiones (primero deja de usarse, después se borra). Las migraciones versionadas ya publicadas no se editan; `R__catalogos.sql` vuelve a correr cuando cambia y no pisa los parámetros editados.

| Situación | Vuelta atrás |
|---|---|
| Una migración falla | PostgreSQL revierte esa migración (DDL transaccional) y la aplicación no arranca: se vuelve a la `DIGSA_VERSION` anterior, que arranca sobre el mismo esquema |
| Migró bien, pero la versión tiene un error y las migraciones eran compatibles | Se vuelve a la `DIGSA_VERSION` anterior (Flyway ignora las migraciones «futuras» que no conoce) |
| Migró bien, pero alguna migración no era compatible | Se detiene la aplicación, se restaura el respaldo del paso 2 y se arranca la versión anterior |

#### Scenario: Migración fallida
<!-- test: ci/imagen.sh (prueba de humo con una migración rota, en una prueba del pipeline) -->
- **CUANDO** V18 falla a la mitad al actualizar
- **ENTONCES** `flyway_schema_history` no la registra, el contenedor no queda `healthy` y al volver a la versión anterior la aplicación arranca

#### Scenario: Vuelta atrás compatible
<!-- test: sin prueba en el PHP -->
- **CUANDO** la 1.5.0 agregó una columna con valor por defecto y se vuelve a la 1.4.0
- **ENTONCES** la 1.4.0 arranca y funciona sin restaurar

---

### Requirement: Guía de operación del servidor
<!-- id: (nuevo) docs/guia_operacion_servidor.md -->
<!-- entidades: — -->
<!-- impuesto_en: docs/DESPLIEGUE_TI.md (PHP); docs/guia_docker_local.md; docs/guia_despliegue_prueba.md -->
<!-- decision: Anexo 5 1.23, 1.24 y 3.6 | Anexo 5 1.16 (documentos técnicos y manuales de acceso) -->

DEBE existir `docs/guia_operacion_servidor.md`, para TI, independiente del proveedor, con: requisitos del servidor (Linux con Docker Engine y el plugin compose, CPU, memoria, disco para la base y `/datos`, proxy https con TLS 1.2 o 1.3, sin SSL ni TLS 1.0/1.1, GMI P71 d); instalación (registro o paquete, `.env`, primer administrador, carga inicial); tareas diarias (ya dentro de la aplicación, a las 00:15 y 06:30; cómo verlas en el log); respaldo, restauración y actualización (esta especificación, con los mismos comandos que prueba el pipeline); monitoreo (`healthy`, espacio en disco, tamaño de la bitácora y sus particiones); y una sección «Pendiente del alojamiento» con lo que bloquea (ver «Dudas»). Las guías existentes DEBEN apuntar a ella y la referencia a `docs/guia_despliegue_azure.md`, que no existe, se quita.

#### Scenario: TI sin experiencia en Spring
<!-- test: revisión manual con TI -->
- **CUANDO** TI sigue la guía en un servidor Linux limpio
- **ENTONCES** llega a la aplicación en https, con datos cargados y un respaldo restaurado, sin consultar al equipo de desarrollo

---

### Invariant: Ningún secreto en el repositorio, la imagen ni los artefactos
<!-- entidades: — -->
<!-- impuesto_en: .gitignore, .dockerignore, application.yml, Dockerfile (configuración solo por variables), CrearAdministrador (sin contraseñas) -->
<!-- verificado_por: trabajo `secretos` (gitleaks) y Trivy de la imagen (secretos embebidos) -->

Las contraseñas de la base, del SMTP, del captcha y del registro DEBEN llegar solo por variables de entorno o del gestor de secretos del servidor o del CI. Ningún usuario de la aplicación recibe una contraseña escrita por otro: todos la definen con el enlace.

---

### Invariant: Se despliega lo que se probó
<!-- entidades: — -->
<!-- impuesto_en: ci/imagen.sh -->
<!-- verificado_por: digest publicado frente a `docker inspect` en el servidor -->

La imagen que corre en un servidor DEBE tener el digest de una imagen que pasó los trabajos 2 a 6 del pipeline. En el servidor no se compila.

---

### Invariant: La carga nunca deja la base a medias ni borra
<!-- entidades: Punto, Auditor, Usuario -->
<!-- impuesto_en: ServicioCargaMaestros (una transacción, candado consultivo) -->
<!-- verificado_por: escenarios «Aplicar con un error» y «Segunda corrida» -->

Una carga aplicada DEBE quedar entera o no quedar; ninguna carga borra ni inactiva registros que no vienen en el archivo, y repetirla no cambia nada.

---

### Invariant: Base y archivos se respaldan y restauran juntos
<!-- entidades: Archivo, OplSoporte, VisitaActa -->
<!-- impuesto_en: docs/DESPLIEGUE_TI.md §7 («una sin la otra no sirve») -->
<!-- verificado_por: ci/respaldo_restauracion.sh -->

Todo respaldo DEBE tener su par (`digsa_<F>.dump`, `archivos_<F>.tgz`) con la misma marca de tiempo y sus huellas; una restauración termina con `--digsa.verificar-archivos` en 0.

---

### Invariant: Pantalla y descarga del dashboard dan las mismas cifras
<!-- entidades: — -->
<!-- impuesto_en: tablero/ServicioDashboard.java, tablero/ExportadorTablero.java -->
<!-- verificado_por: escenario «Pantalla y CSV» -->

La descarga DEBE construirse con la misma respuesta que la pantalla; no hay una segunda consulta con otra lógica.

---

## Contradicciones registradas

1. **Respaldo en PowerShell.** `docs/guia_docker_local.md` §4 indica `docker compose exec -T db … pg_dump -Fc digsa > respaldo_digsa.dump` en PowerShell. En Windows PowerShell 5.1, `>` vuelve texto la salida y corrompe el binario: ese respaldo no se puede restaurar. Se reemplaza por `pg_dump -f` dentro del contenedor y `docker compose cp`.
2. **Carga con errores.** El PHP (y `maestros-carga-masiva`) aplica las filas válidas y salta las erradas; GMI P77 f dice que los archivos con errores se rechazan con el detalle. La carga inicial sigue P77 f (todo o nada). Si después se construye la carga por pantalla, conviene la misma regla.
3. **Base limpia para la prueba en sitio.** La condición general 4 del Anexo 5 exige las tablas maestras del proceso vacías (`verificar_base_limpia.sql` cuenta `auditores` en 0). La carga inicial NO DEBE correrse en la base de la prueba del 11 al 27 de noviembre; solo en la de operación.
4. **Enlace de 60 minutos** (identidad-sesion) frente a una carga de decenas de usuarios a la vez: ver la duda del requisito de enlaces.
5. **Pruebas solo para Windows.** `e2e/playwright.config.ts` usa `mvnw.cmd` y `%LOCALAPPDATA%`, y el `pretest` usa `powershell`: no corren en un ejecutor Linux. Se corrigen para el pipeline portable.
6. **Azure como destino.** `.env.example`, `application.yml` y `guia_despliegue_prueba.md` (que cita una `guia_despliegue_azure.md` inexistente) suponen Azure y Key Vault; el alojamiento no está decidido y debe estar en Colombia (16.1, AGS-29, AGS-77/78). Los textos pasan a «gestor de secretos del servidor».
7. **Construir en el servidor.** El compose trae `build: .` y las guías usan `up --build`; en un servidor se usa la imagen del pipeline (D14 + 23.2).
8. **Tareas programadas y réplicas.** `@Scheduled` sin candado obliga a una sola réplica; la alta disponibilidad del 99,74 % (16.8) pediría dos. Queda como condición del despliegue.
9. **Prestador.** El PHP agrupa por el nombre guardado en el resumen; Java agrupa por unidad y nunca produce «Sin prestador». Se especifica el criterio por unidad con «Sin prestador» para lo que no encaja (25.3).
10. **Ancho del celular.** `riesgo-tablero` dice 390 px; la prueba e2e y la duda 25.4 usan 360 px. Se toma 360 px, que es más exigente.

## Dudas

**Bloquean el despliegue en el servidor definitivo (no bloquean construir esta fase):**
- **Alojamiento** (16.1): datacenter o colocation en Colombia, o nube con región en Colombia. De él dependen la IaC (16.2: Bicep, Terraform o ninguna), la base administrada o en contenedor, el almacén de archivos (disco local, NFS u objeto), el gestor de secretos, el proxy https y el certificado, y el medio de custodia de los respaldos.
- **Repositorio remoto y registro** (16.3): GitHub (recomendado, con GHCR) o Azure DevOps (con ACR). ¿Pueden el código fuente y la imagen alojarse fuera de Colombia? La imagen no lleva datos, pero conviene confirmarlo con la DIGSA o con el área jurídica.
- **Quién opera el servidor** (TI de AGS o el proveedor del datacenter) y con qué acceso: define si la carga y los respaldos los corre TI.
- **SMTP real** para los enlaces de bienvenida de la carga (y su tope de envíos por minuto).
- **RPO, RTO, retención y medio de custodia** de los respaldos (AGS-41, AGS-73/79): propuesta RPO ≤ 24 h, RTO ≤ 8 h, 30 copias diarias y 12 mensuales.

**No bloquean (se construye con la recomendación y se valida):**
- Vigencia del enlace de bienvenida en la carga (60 min o un parámetro de días).
- Perfiles en la carga: agregar sin quitar (recomendado) o reemplazar el conjunto.
- «Farmacia alterna» como una sola fila o una por farmacia; el trámite en los vacíos (D5).
- Umbral de vulnerabilidades (HIGH con arreglo) y si se firma la imagen (cosign).
- Firefox y WebKit cada noche y no en cada cambio.
- Licencia de Docker Desktop en los equipos de AGS (guía local); en servidores Linux, Docker Engine es libre.

## Datos que la reescritura Java debe agregar

1. **Pipeline:** `ci/backend.sh`, `ci/frontend.sh`, `ci/e2e.sh`, `ci/dependencias.sh`, `ci/imagen.sh`, `ci/respaldo_restauracion.sh`; `.github/workflows/ci.yml` (con el trabajo nocturno de Firefox, WebKit y respaldo) y `azure-pipelines.yml` equivalente; `.trivyignore` con el formato de excepción (CVE, motivo, quién, vence); configuración de gitleaks; Dependabot para Maven, npm, Docker y las acciones.
2. **Pruebas portables:** `webServer` de Playwright con `mvnw`/`mvnw.cmd` según la plataforma y `JAVA_HOME` del entorno; `pretest` en Node; `DIGSA_E2E_ADMIN` al azar con `CI`; `TZ=America/Bogota`; `git update-index --chmod=+x backend/mvnw`.
3. **Imagen:** `ARG DIGSA_VERSION` y `ARG DIGSA_COMMIT` en el Dockerfile, etiquetas OCI, registro en el log de arranque; compose sin `build` para servidores (`docker-compose.override.yml` para desarrollo); `HEALTHCHECK` a `/actuator/health/readiness` con `management.endpoint.health.probes.enabled=true` y un indicador del almacén.
4. **`reglas/ImportadorMaestros.java`** (pura): columnas y sinónimos, normalización, `unidad`, `fuerza`, `municipio`, `rol`, `siNo`, `listaCorreos`, `puntos`, `auditores`, `usuarios`, con las pruebas de `tests/importador_maestros_test.php` en JUnit y las nuevas de usuarios, referencias cruzadas y columnas de contraseña.
5. **`maestros/carga/`**: `CargaMaestrosCli` (`ApplicationRunner` con `@ConditionalOnProperty` en `digsa.carga.carpeta` o `digsa.carga.plantillas`, sin servidor web ni tareas: `spring.main.web-application-type=none` y tareas desactivadas en ese modo), `LectorPlantillas` (POI en flujo con `ZipSecureFile` y CSV UTF-8 `;`), `ServicioCargaMaestros` (simular con reversión, aplicar en una transacción con `pg_advisory_xact_lock`), `GeneradorPlantillas` e `InformeCarga`.
6. **Bitácora:** tipos `carga_maestros` (inserción) y `carga_maestros_simulada` (consulta) en `Bitacora.TIPOS`; reusar `punto_creado`, `punto_editado`, `auditor_editado`, `usuario_creado`, con enmascarado de documento y teléfono.
7. **Enlaces:** `digsa.carga.enlaces` (`enviar`, `no`, `pendientes`), `digsa.carga.pausa-ms`; si se aprueba, `digsa.clave.enlace-bienvenida-horas`.
8. **Dashboard:** en `ServicioDashboard.resultados` las categorías con «Sin calificar» y «Obligaciones del contrato», prestador con «Sin prestador» y vacíos de la versión vigente (consultas con `GROUP BY` sobre `visitas_respuestas`, `visitas_obligaciones`, `visitas_componentes`, `chk_items.prioridad` y `chk_vacios`; no hace falta tabla nueva); las cuatro tablas en `ExportadorTablero.dashboard`; las tablas en `Dashboard.tsx`; `TarjetasEsm` y tarjetas por fuerza en `Riesgo.tsx` con `hiddenFrom`/`visibleFrom="sm"`; pruebas e2e a 360 px y de igualdad pantalla–CSV.
9. **Respaldo y restauración:** `docker/respaldar.sh` y `.ps1`, `docker/restaurar.sh` y `.ps1`, `herramientas/verificar_restauracion.sql` (filas por tabla), `herramientas/permisos_rol_app.sql`, y el modo `--digsa.verificar-archivos` (recalcula el SHA-256 de `archivos` y `opl_soportes`, código 0 o 3).
10. **Documentación:** `docs/guia_operacion_servidor.md`; `.env.example` completo y neutral frente al proveedor; corrección del respaldo de `guia_docker_local.md`; quitar la referencia a `guia_despliegue_azure.md`; `carga/` y `respaldos/` en `.gitignore` y `.dockerignore`.

Lo que se puede construir sin decidir el alojamiento: todo lo anterior. Lo único que espera es la IaC, el gestor de secretos concreto, el registro definitivo y el medio de custodia de los respaldos.

<!-- pendiente: scripts/plazos_opl.php y recordatorios_seguimiento.php (ya están en Java como @Scheduled; su candado es de 16.4), la decisión sobre Container Apps Jobs u otro programador externo si se descarta @Scheduled, y la carga del Acuerdo 080, precios y matriz de precios (Anexo 5 2.3), que son del módulo de cuentas médicas -->
