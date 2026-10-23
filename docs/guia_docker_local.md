# Guía: Suite DIGSA en Docker en este equipo

Para: quien prueba la aplicación en su propio equipo con Windows 11, sin ser experto en Docker.
Resultado: la aplicación en http://localhost:8080. Solo se ve desde este equipo y queda lista para llevarla a cualquier servidor.

Docker empaca la aplicación y su base de datos en «contenedores». La misma imagen corre igual en este equipo, en un servidor en Colombia o en la nube. Por eso no hay que instalar Java, Node ni PostgreSQL: todo viene adentro.

---

## 1. Lo que instala TI (una sola vez, con permisos de administrador)

1. **WSL 2** (el subsistema de Linux de Windows). En PowerShell como administrador:
   ```powershell
   wsl --install --no-distribution
   ```
   Reinicie el equipo cuando lo pida.
2. **Un motor de Docker**, uno de estos dos:
   - **Docker Desktop** (https://www.docker.com/products/docker-desktop/). Ojo con la licencia: en empresas de más de 250 empleados o más de USD 10 millones de ingresos requiere suscripción paga. TI debe confirmar si AGS la tiene.
   - **Rancher Desktop** (https://rancherdesktop.io/). Es gratuito y de código abierto. Al instalarlo, elija el motor **dockerd (moby)**, no containerd, para que funcionen los mismos comandos `docker`.
3. Verificación: abra una ventana de PowerShell normal (no de administrador) y escriba:
   ```powershell
   docker version
   docker compose version
   ```
   Ambos deben responder con un número de versión. Si dice «no se reconoce», cierre sesión en Windows y vuelva a entrar.

> Si el equipo está detrás de un proxy corporativo, TI debe configurarlo en Docker Desktop o Rancher Desktop (Settings → Proxies). Si no, la primera construcción falla al descargar.

## 2. Primera vez: levantar la aplicación

1. Abra Docker Desktop (o Rancher Desktop) y espere a que diga que está corriendo.
2. En el Explorador de archivos vaya a `C:\Users\dgonzalez\dev\digsa-java\docker` y haga doble clic en **`iniciar-local.bat`**.
3. El script hace lo siguiente:
   - Crea el archivo `.env` con claves aleatorias para la base de datos. Ese archivo no se versiona y no sale del equipo: **guárdelo**, porque sin él no se abre la base de este equipo.
   - Construye la imagen. La primera vez tarda entre 5 y 15 minutos porque descarga Java, Node y PostgreSQL. Las siguientes veces tarda segundos.
   - Levanta la base y la aplicación, y espera a que respondan.
   - Pide el **correo, el nombre y el usuario del primer administrador**. Luego muestra un enlace para definir la contraseña: ábralo en el navegador y póngala.
   - Abre http://localhost:8080.
4. Ingrese con ese usuario. En este modo local:
   - **el código de verificación aparece en la misma pantalla**, porque el correo no sale del equipo;
   - **no se pide captcha**.

   Así nada le impide probar.

## 3. Uso diario

| Para… | Haga… |
|---|---|
| Arrancar | Abra Docker Desktop. La aplicación arranca sola (`restart: unless-stopped`). Si no, doble clic en `iniciar-local.bat` |
| Detener sin borrar nada | En PowerShell, dentro de `C:\Users\dgonzalez\dev\digsa-java`: `docker compose stop` |
| Ver qué está corriendo | `docker compose ps` |
| Ver el log de la aplicación | `docker compose logs app --tail 100` |
| Actualizar a una versión nueva del código | `git pull` y luego doble clic en `iniciar-local.bat` (reconstruye la imagen; los datos se conservan) |
| Ver los correos que enviaría la aplicación | Ponga `DIGSA_CORREO_MODO=smtp`, `DIGSA_SMTP_HOST=correo` y `DIGSA_SMTP_PUERTO=1025` en `.env`, luego `docker compose --profile pruebas up -d` y abra http://localhost:8025 |

Los comandos `docker compose …` se escriben en PowerShell, dentro de la carpeta `C:\Users\dgonzalez\dev\digsa-java`. Para llegar ahí: `cd C:\Users\dgonzalez\dev\digsa-java`.

## 4. Dónde quedan los datos

- **Base de datos:** volumen `datos-db`.
- **Evidencias, actas y archivos:** volumen `datos-app`.

Los volúmenes sobreviven a `docker compose stop`, `docker compose down` y a las actualizaciones.

**Copia de seguridad de la base** (genera `respaldo_digsa.dump` en la carpeta actual). El respaldo se escribe dentro del contenedor y luego se copia: redirigir con `>` en PowerShell 5.1 corrompe el archivo binario y no se podría restaurar.
```powershell
docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -Fc -f /tmp/respaldo_digsa.dump digsa'
docker compose cp db:/tmp/respaldo_digsa.dump .\respaldo_digsa.dump
docker compose exec -T db rm /tmp/respaldo_digsa.dump
```

**Restaurar ese respaldo** (reemplaza los datos actuales de la base por los del archivo):
```powershell
docker compose stop app
docker compose cp .\respaldo_digsa.dump db:/tmp/respaldo_digsa.dump
docker compose exec -T db sh -c 'pg_restore -U "$POSTGRES_USER" -d digsa --clean --if-exists --no-owner /tmp/respaldo_digsa.dump'
docker compose exec -T db rm /tmp/respaldo_digsa.dump
docker compose start app
```
Los archivos (evidencias y actas) están en el volumen `datos-app` y deben respaldarse junto con la base. Para eso están `dockerespaldar.ps1` y `dockerestaurar.ps1`: hacen las dos cosas, con huellas y conteo de filas. Ver [guia_operacion_servidor.md](guia_operacion_servidor.md), §6 y §7.

**Empezar de cero** (borra **todos** los datos de prueba de este equipo):
```powershell
docker compose down -v
```
Luego doble clic en `iniciar-local.bat`. Volverá a pedir el administrador.

## 5. Problemas comunes

| Síntoma | Causa y solución |
|---|---|
| «Docker está instalado pero no está corriendo» | Abra Docker Desktop o Rancher Desktop y espere a que diga «running» |
| `Falta DIGSA_DB_DUENO_CLAVE en .env` | El `.env` quedó incompleto: bórrelo **solo si aún no tiene datos** y ejecute de nuevo el `.bat` |
| La construcción falla descargando (`timeout`, `TLS`, `proxy`) | Proxy corporativo: TI lo configura en Docker (Settings → Proxies) |
| `port is already allocated` en 8080 | Otro programa usa el puerto. En `.env` ponga `DIGSA_PUERTO=8081` y entre por http://localhost:8081 (cambie también `DIGSA_URL`) |
| La aplicación no responde | `docker compose logs app --tail 100`: el error suele estar al final |
| Perdió el `.env` y la base ya tenía datos | La base no abre con claves nuevas. Si son solo datos de prueba, `docker compose down -v` y empezar de cero |

## 6. Llevarlo a un servidor (más adelante)

Es la misma imagen. En el servidor cambian solo las variables del `.env`:
- `DIGSA_ENTORNO=pruebas` o `produccion`, con **https** y SMTP real. Fuera de `local` el código ya no aparece en pantalla y se exige captcha.
- `DIGSA_ESCUCHA=0.0.0.0` detrás del proxy https.

Las claves van en el gestor de secretos del servidor (Key Vault en Azure u otro), nunca en el repositorio. En el servidor no se construye la imagen: se usa la que publica el pipeline. El procedimiento completo está en [guia_operacion_servidor.md](guia_operacion_servidor.md).

**Pendiente:** el envío del código por **SMS** espera al proveedor y sus credenciales. Por ahora el código llega solo por correo, y así lo dice la pantalla de ingreso.
