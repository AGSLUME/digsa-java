# Guía: ver los correos de la Suite DIGSA en Mailpit (desarrollo local)

Módulo Correos, fase C4 (pedido del usuario del 2026-10-29).

Mailpit es un servidor de correo de pruebas. Recibe todo lo que envía la aplicación y lo muestra en una página web, y nada llega a un buzón real. Sirve para revisar cómo se ven los correos (MFA, actas, avisos, plantillas editadas) sin mandar nada a nadie.

| Qué | Dónde |
|---|---|
| SMTP (la aplicación envía aquí) | `localhost:1025`, sin usuario y sin STARTTLS |
| Página para ver los correos | <http://localhost:8025> |
| Perfil de la aplicación | `mailpit` (archivo `backend/app/src/main/resources/application-mailpit.yml`) |

El perfil `mailpit` **solo arranca con `digsa.entorno=local`** y con el SMTP en `localhost` o `127.0.0.1`. Si no se cumple, la aplicación no inicia y dice por qué (`ValidacionArranque`). Así una variable mal puesta en un servidor no puede mandar correos sin cifrar a otra parte.

Sin Mailpit, el arranque local sigue igual que antes: los correos quedan como archivos en `.local/correos`. El modo «archivo» se conserva.

## Opción 1 · Ejecutable de Windows (sin Docker)

Este equipo no tiene Docker; esta es la forma recomendada.

1. Descargue `mailpit-windows-amd64.zip` de la página de versiones del proyecto: <https://github.com/axllent/mailpit/releases>.
2. Descomprímalo en una carpeta suya, por ejemplo `%LOCALAPPDATA%\Programs\mailpit`. No necesita permisos de administrador.
3. Arránquelo en una consola, escuchando solo en este equipo:

   ```powershell
   & "$env:LOCALAPPDATA\Programs\mailpit\mailpit.exe" --smtp 127.0.0.1:1025 --listen 127.0.0.1:8025
   ```

   Déjelo abierto mientras prueba. Para detenerlo, pulse `Ctrl+C`.
4. Abra <http://localhost:8025>: debe verse la bandeja vacía.

## Arrancar la aplicación con el perfil mailpit

Con Mailpit corriendo, desde `backend`:

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\jdk21"
.\mvnw.cmd -pl app spring-boot:test-run `
  "-Dspring-boot.run.main-class=co.com.agsamericas.digsa.DigsaLocal" `
  "-Dspring-boot.run.jvmArguments=-Ddigsa.local.correo=mailpit"
```

`-Ddigsa.local.correo=mailpit` hace que el arranque local active el perfil `mailpit` en lugar del correo a archivo. Para volver a los archivos, quite ese argumento.

Pruebe con el ingreso: el código de verificación llega a <http://localhost:8025>. En la pantalla **Correos › Envíos** de la aplicación queda el mismo envío, con el código oculto.

Para usar otro equipo o puerto (por ejemplo, Mailpit en otro puerto), use las variables `DIGSA_MAILPIT_HOST` (solo `localhost` o `127.0.0.1`) y `DIGSA_MAILPIT_PUERTO`.

## Opción 2 · Docker (en equipos que lo tengan)

```bash
docker run -d --name mailpit -p 127.0.0.1:1025:1025 -p 127.0.0.1:8025:8025 axllent/mailpit:v1.20
```

Luego arranque la aplicación igual que en la opción 1. Para detenerlo: `docker stop mailpit && docker rm mailpit`.

El `docker-compose.yml` de la raíz también trae Mailpit (servicio `correo`, perfil `pruebas`) para la aplicación en contenedores. Allí la aplicación le envía por la red interna de Docker y solo se publica la página `8025`.

## Qué no hace

- No es para servidores de pruebas ni de producción. Allí el correo sale por el SMTP institucional (`DIGSA_SMTP_HOST`, con usuario y STARTTLS).
- No cambia los canales: MFA y recuperación por correo; alertas críticas por correo y SMS. Los SMS siguen en su modo (archivo en local).
