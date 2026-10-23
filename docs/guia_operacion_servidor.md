# Guía de operación del servidor · Suite DIGSA

Para: TI de AGS o el proveedor del datacenter que opera el servidor. No hace falta saber Java ni Spring.
Sirve para cualquier servidor Linux con Docker, en un datacenter en Colombia o en una nube con región en Colombia (duda 16.1). No depende de ningún proveedor.

La unidad de despliegue es **la imagen `digsa-suite:<versión>` que publica el pipeline** más el `docker-compose.yml` del repositorio. En el servidor no se compila nada: solo cambia el `.env`.

| Si necesita… | Vaya a |
|---|---|
| Montar un servidor nuevo | §1, §2, §3 y §4 |
| Respaldar | §6 |
| Restaurar (o probar la restauración en un servidor alterno) | §7 |
| Pasar a una versión nueva o volver a la anterior | §8 |
| Saber si todo está bien | §9 |

Las guías relacionadas son estas: [guia_docker_local.md](guia_docker_local.md) para el equipo de un desarrollador y [guia_despliegue_prueba.md](guia_despliegue_prueba.md) para la prueba en sitio del 11 al 27 de noviembre.

---

## 1. Requisitos del servidor

| Componente | Mínimo recomendado | Nota |
|---|---|---|
| Sistema | Linux x86-64 con soporte vigente (Ubuntu 22.04/24.04 LTS, RHEL 9 o equivalente) | Docker Engine es software libre en Linux (no aplica la licencia de Docker Desktop) |
| Docker | Docker Engine 24 o superior con el plugin **compose v2.20 o superior** (`docker compose version`) | Las órdenes de esta guía usan `--wait` y `docker compose cp` |
| CPU y memoria | 2 vCPU y 8 GB de RAM (aplicación ~2 GB, PostgreSQL ~2 GB, sistema y caché) | La aplicación usa hasta el 75 % de la memoria que se le asigne |
| Disco | 40 GB para la base y `/datos` al inicio, más 3 veces eso para los respaldos locales del día | Las evidencias y actas crecen con las visitas: vigilar (§9) |
| Hora | Zona `America/Bogota` y NTP activo | Plazos, festivos y las tareas diarias dependen de la hora |
| Red | Solo el proxy https expuesto (443). PostgreSQL **nunca** se publica | La aplicación escucha en `127.0.0.1:8080` |
| Proxy https | Caddy 2, nginx o el balanceador del proveedor, con **TLS 1.2 o 1.3** únicamente (sin SSL ni TLS 1.0/1.1: GMI P71 d) | Certificado de un dominio real; las cookies son `Secure` |
| Correo | Un buzón SMTP con usuario y clave (TLS en 587) | Códigos de ingreso, enlaces de contraseña y reportes |
| Salida a internet | Al registro de la imagen (o nada, si se usa el paquete) y a Cloudflare Turnstile (captcha) | |

**Una sola réplica.** Las tareas diarias de las 00:15 y las 06:30 corren dentro de la aplicación y todavía no tienen candado. Con dos réplicas correrían dos veces (duda 16.4).

## 2. Obtener la imagen

Cada versión trae dos archivos y sus huellas: el paquete de la imagen y el de despliegue. La etiqueta de la imagen es la versión (`1.4.0`), **nunca `latest`**.

**Opción A: desde el registro privado** (GHCR o ACR, según la duda 16.3):

```bash
docker login <registro>                                   # credencial de solo lectura que entrega AGS
docker pull <registro>/digsa-suite:1.4.0
docker tag <registro>/digsa-suite:1.4.0 digsa-suite:1.4.0  # el compose usa el nombre local digsa-suite:<versión>
```

**Opción B: servidor sin acceso al registro** (paquete para TI, publicado en cada versión `v*`):

```bash
sha256sum -c SHA256SUMS                                   # todas las líneas deben decir «OK»
docker load -i digsa-suite_1.4.0.tar.gz                   # deja digsa-suite:1.4.0
tar -xzf digsa-suite_1.4.0_despliegue.tar.gz              # docker-compose.yml, .env.example, docker/, herramientas/, esta guía y el SBOM
```

**Comprobar que es la imagen que probó el pipeline.** La revisión debe ser el commit de la versión y el digest debe coincidir con el que registró el pipeline (artefacto «publicacion», archivo `imagen_publicada.txt`):

```bash
docker image inspect digsa-suite:1.4.0 --format '{{index .Config.Labels "org.opencontainers.image.revision"}} {{.Id}} {{.RepoDigests}}'
```

## 3. Instalación

En una carpeta del servidor, por ejemplo `/opt/digsa`, con los archivos del paquete de despliegue (o del repositorio en la etiqueta de la versión):

```bash
cd /opt/digsa
cp .env.example .env && chmod 600 .env
```

Diligencie el `.env`. Las claves se toman del gestor de secretos del servidor y no se versionan:

| Variable | Valor en el servidor |
|---|---|
| `DIGSA_VERSION` | La versión de la imagen, p. ej. `1.4.0` |
| `DIGSA_ENTORNO` | `produccion` (o `pruebas`). **Nunca `local`** |
| `DIGSA_URL` | `https://<dominio>/` con la barra final |
| `DIGSA_ESCUCHA` / `DIGSA_PUERTO` | `127.0.0.1` / `8080` si el proxy está en el mismo servidor; `0.0.0.0` solo detrás de un proxy en otra máquina |
| `DIGSA_DB_DUENO`, `DIGSA_DB_DUENO_CLAVE` | Dueño de la base (corre las migraciones). Clave larga y aleatoria |
| `DIGSA_DB_USUARIO`, `DIGSA_DB_CLAVE` | Rol de la aplicación, con permisos mínimos. Otra clave |
| `DIGSA_CORREO_MODO` y `DIGSA_SMTP_*`, `DIGSA_CORREO_REMITENTE` | `smtp` y los datos del buzón |
| `DIGSA_DOMINIOS_REPORTES`, `DIGSA_CORREO_REPORTES` | Dominios a los que se pueden enviar reportes y el correo por defecto |
| `DIGSA_TURNSTILE_SITIO`, `DIGSA_TURNSTILE_SECRETA` | Captcha. Sin la clave secreta nadie ingresa |

```bash
docker compose up -d --no-build --wait     # base + aplicación; espera a que queden «healthy» (la primera vez, 1 a 2 minutos)
docker compose ps                          # app y db: «running (healthy)»
docker compose logs app --tail 50          # «Successfully applied N migrations» y «Started DigsaApplication»
```

`--no-build` es obligatorio en el servidor. El compose trae `build: .` solo para el equipo de desarrollo.

**Primer administrador** (una sola vez). El enlace para definir la contraseña llega al correo:

```bash
docker compose run --rm app --digsa.crear-admin.correo=admin@dominio --digsa.crear-admin.nombre="Administrador DIGSA" --digsa.crear-admin.usuario=admin.digsa
```

**Carga inicial de auditores, puntos y usuarios.** Es la herramienta de línea de comandos de la especificación `despliegue-carga`, parte B, y **todavía no está construida**. Cuando lo esté, se correrá así:

```bash
docker compose run --rm --no-deps -v "$PWD/carga:/carga" app --digsa.carga.plantillas=/carga                       # plantillas vacías
docker compose run --rm -v "$PWD/carga:/carga" app --digsa.carga.carpeta=/carga --digsa.carga.responsable=admin.digsa  # simular
docker compose run --rm -v "$PWD/carga:/carga" app --digsa.carga.carpeta=/carga --digsa.carga.responsable=admin.digsa --digsa.carga.modo=aplicar
```

Las plantillas y el informe de la carga tienen **datos personales** (Ley 1581):
- se suben al servidor por un canal cifrado (SFTP o SCP), **nunca por correo**;
- se montan en solo lectura cuando se pueda (`:ro`);
- no se guardan en el repositorio (`carga/` está en `.gitignore`);
- se borran del servidor al terminar.

La carga **no** se corre en la base de la prueba en sitio: allí las tablas del proceso deben quedar vacías (`herramientas/verificar_base_limpia.sql`).

## 4. Proxy https

Ejemplo con Caddy. Por defecto usa TLS 1.2 y 1.3 y obtiene el certificado solo si el dominio es público:

```
digsa.ejemplo.gov.co {
    reverse_proxy 127.0.0.1:8080
}
```

Ejemplo con nginx (fragmento del `server` en 443):

```nginx
ssl_protocols TLSv1.2 TLSv1.3;
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto https;
}
```

Para comprobarlo, `curl -I https://<dominio>/` debe responder 200 y `openssl s_client -connect <dominio>:443 -tls1_1` debe fallar.

## 5. Tareas diarias

Corren solas dentro de la aplicación, en hora de Bogotá. No hay que programarlas en el servidor:
- **00:15**: plazos del OPL (`TareaDiariaOpl`).
- **06:30**: recordatorios del seguimiento (`TareaRecordatorioSeguimiento`).

Para verlas en el log: `docker compose logs app --since 24h | grep -E "TareaDiariaOpl|TareaRecordatorioSeguimiento"`.

## 6. Respaldo (diario)

La base y los archivos (evidencias, actas, soportes del OPL) se respaldan **juntos**: uno sin el otro no sirve. El script copia primero la base y después los archivos, sin detener la aplicación. Antes de dar el respaldo por bueno, lo restaura en una base temporal y cuenta las filas.

```bash
cd /opt/digsa && docker/respaldar.sh            # Windows: powershell -File docker\respaldar.ps1
```

En `respaldos/` (o en la carpeta de `DIGSA_RESPALDOS`) quedan estos archivos:

| Archivo | Contenido |
|---|---|
| `digsa_<F>.dump` | Base (formato propio de `pg_dump`, comprimido) |
| `archivos_<F>.tgz` | `/datos/archivos` |
| `conteos_<F>.csv` | Filas por tabla del respaldo, para comparar al restaurar |
| `respaldo_<F>.info` | Versión de la imagen y commit |
| `respaldo_<F>.sha256` | Huellas de los cuatro anteriores |
| `bitacora_respaldos.csv` | Una línea por respaldo o restauración, con su resultado (Anexo 5, 3.6) |

**Programación** (cron del usuario que opera Docker). Si el script falla termina con un código distinto de 0 y anota «falló: <etapa>» en la bitácora, y cron envía la salida al correo de `MAILTO`:

```cron
MAILTO=ti@agsamericas.com.co
0 1 * * * cd /opt/digsa && DIGSA_RESPALDO_RETENER_DIAS=7 docker/respaldar.sh >> respaldos/respaldar.log 2>&1
```

**Custodia.** La copia se lleva cada día a un medio y una ubicación **en Colombia**, distintos del servidor (AGS-29, AGS-73/79). `DIGSA_RESPALDO_RETENER_DIAS` solo limpia la copia local; la retención en el medio de custodia la define el plan de continuidad. La propuesta es 30 copias diarias y 12 mensuales. La exportación completa de la aplicación es la entrega de datos a la DIGSA, **no** un respaldo.

**Base administrada** (sin el servicio `db`): el proveedor da el respaldo de la base con restauración a un punto en el tiempo. El script cubre entonces solo los archivos y la copia en el medio de custodia.

Propuesta para el plan de continuidad (AGS-41), que aprueba la DIGSA: **RPO ≤ 24 h** (respaldo diario) y **RTO ≤ 8 h**.

## 7. Restauración

Se restaura sobre una base vacía, con la aplicación detenida y con la **misma versión de la imagen del respaldo o una posterior**. Una versión anterior no conoce las migraciones nuevas: si el respaldo es de la 1.5.0, primero se sube el servidor a la 1.5.0 (§8) y después se restaura.

```bash
cd /opt/digsa
# copie a respaldos/ los cinco archivos del respaldo elegido
docker/restaurar.sh 20261122_0100               # Windows: powershell -File docker\restaurar.ps1 20261122_0100
```

El script hace estos pasos y se detiene en el primero que falle:
1. Verifica las huellas.
2. Detiene la aplicación.
3. Recrea la base y la restaura en una sola transacción.
4. Compara las filas por tabla con `conteos_<F>.csv`.
5. Vacía `/datos/archivos` y extrae los archivos con el usuario de la aplicación.
6. Arranca la aplicación y espera a que quede `healthy`. Flyway valida el esquema y, si la imagen es más nueva, migra.
7. Recalcula el SHA-256 de cada archivo registrado en la base (`archivos` y `opl_soportes`).
8. Anota el resultado en la bitácora de respaldos.

Al final, ingrese con un usuario para confirmar.

**El rol de la aplicación con otro nombre.** Es el caso de una base administrada donde el DBA creó otro rol. Los permisos mínimos viajan en el respaldo a nombre del rol original, y se aplican así:

```bash
psql -v paso=antes   -v rol_respaldo=digsa_app -v rol_nuevo=<rol del servidor> -d digsa -f herramientas/permisos_rol_app.sql
pg_restore --no-owner --exit-on-error --single-transaction -d digsa digsa_<F>.dump
psql -v paso=despues -v rol_respaldo=digsa_app -v rol_nuevo=<rol del servidor> -d digsa -f herramientas/permisos_rol_app.sql
```

**Prueba de restauración.** El pipeline la hace cada noche y en cada versión con estos mismos scripts (trabajo «respaldo»). Además, el plan de contingencia agenda la restauración del respaldo real en un **servidor alterno**, por ejemplo cada trimestre. El resultado queda en `bitacora_respaldos.csv` (Anexo 5, 3.6).

## 8. Actualización de versión y vuelta atrás

1. Anuncie la ventana de mantenimiento (los mantenimientos autorizados no cuentan en la disponibilidad: AGS-33, GMI P74).
2. Haga un respaldo completo: `docker/respaldar.sh`.
3. Obtenga la versión nueva (§2: `docker pull` y `docker tag`, o `docker load`).
4. Cambie `DIGSA_VERSION` en el `.env` y ejecute `docker compose up -d --no-build --wait app`. Al arrancar, Flyway aplica las migraciones pendientes en orden, cada una en su transacción, y valida las ya aplicadas.
5. Revise el log: `docker compose logs app --tail 100` debe mostrar «Successfully applied N migrations» y el arranque sin errores. Luego ingrese con un usuario de prueba.
6. Anote la versión anterior y la nueva en `respaldos/bitacora_respaldos.csv`.

| Situación | Vuelta atrás |
|---|---|
| Una migración falla | PostgreSQL revierte esa migración y la aplicación no queda `healthy`. Vuelva a la `DIGSA_VERSION` anterior y `docker compose up -d --no-build app` |
| Migró bien, pero la versión tiene un error y las migraciones eran compatibles | Vuelva a la `DIGSA_VERSION` anterior. Flyway ignora las migraciones futuras |
| Migró bien, pero alguna migración no era compatible | `docker compose stop app`, restaure el respaldo del paso 2 (§7) con la versión anterior |

El equipo de desarrollo escribe cada migración **compatible con la versión anterior**: tablas nuevas, columnas con valor por defecto o que admiten nulo, índices. Borrar o renombrar se hace en dos versiones.

## 9. Monitoreo

| Qué | Cómo | Alerta si… |
|---|---|---|
| Salud | `docker compose ps`; `curl -fsS http://127.0.0.1:8080/actuator/health` | No dice `healthy` o no responde 200 |
| Disco | `df -h /var/lib/docker /opt/digsa`; `docker system df -v` (volúmenes `datos-db` y `datos-app`) | Más del 80 % |
| Respaldos | Última línea de `respaldos/bitacora_respaldos.csv` | No es de hoy o no dice «ok» |
| Bitácora | `docker compose exec db psql -U digsa_dueno -d digsa -c "SELECT relname, pg_size_pretty(pg_total_relation_size(oid)) FROM pg_class WHERE relname LIKE 'log_transacciones%' AND relkind = 'r' ORDER BY 1"` (con el valor de `DIGSA_DB_DUENO`) | Filas en `log_transacciones_defecto` (falta la partición del mes) o crecimiento anormal |
| Logs del contenedor | En `/etc/docker/daemon.json`: `{"log-driver": "json-file", "log-opts": {"max-size": "50m", "max-file": "5"}}` | Sin rotación, el log llena el disco |
| Certificado | Fecha de vencimiento del certificado del proxy | Menos de 15 días |

## 10. Pendiente del alojamiento

Estos puntos no bloquean la construcción, pero sí el despliegue definitivo:
- **Alojamiento** (16.1): datacenter o colocation en Colombia, o nube con región en Colombia. De él dependen la base (contenedor o administrada), el almacén de archivos (disco local, NFS u objeto), el gestor de secretos, el proxy https y el certificado. La IaC queda fuera de alcance hasta decidirlo.
- **Repositorio remoto y registro** (16.3): GitHub con GHCR (recomendado) o Azure DevOps con ACR. Falta confirmar si el código y la imagen pueden alojarse fuera de Colombia (la imagen no lleva datos).
- **Quién opera el servidor** (TI de AGS o el proveedor) y con qué acceso: define quién corre la carga inicial y los respaldos.
- **SMTP real** para los enlaces de bienvenida, con su tope de envíos por minuto.
- **RPO, RTO, retención y medio de custodia** de los respaldos (AGS-41, AGS-73/79): la propuesta está en §6.
- **Imagen**: falta que la aplicación escriba «Suite DIGSA <versión> (<commit>)» al arrancar, que el `HEALTHCHECK` use `/actuator/health/readiness` y que exista el modo `--digsa.verificar-archivos`. Hoy los scripts verifican las huellas de los archivos por su cuenta. También falta que el compose admita el nombre del registro sin el `docker tag` del §2.
