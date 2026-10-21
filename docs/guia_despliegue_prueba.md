# Despliegue para la prueba en sitio (equipo de AGS)

Guía para dejar la Suite DIGSA (visitas de seguimiento) lista en un equipo de AGS para la prueba del Anexo 5. La prueba se hace del 11 al 27 de noviembre de 2026. Para Azure, ver `docs/guia_despliegue_azure.md`.

**El día de la prueba no se usa el modo local.** Ese modo muestra el código MFA en la pantalla y guarda los correos en archivo. La prueba corre en modo `produccion`, con correo real y HTTPS.

## 1. Requisitos del equipo

| Componente | Versión | Nota |
|---|---|---|
| Windows 10/11 o Linux | — | 8 GB de RAM, 20 GB libres |
| Java (Temurin JDK o JRE) | 21 | Sirve la versión portátil en `%LOCALAPPDATA%\Programs\jdk21` |
| PostgreSQL | 16 | Instalador de EDB, o los binarios portátiles con `initdb` |
| Proxy HTTPS | Caddy 2 (recomendado) o IIS/nginx | Las cookies de sesión son `Secure` |
| Buzón SMTP | Corporativo o de pruebas (p. ej. Mailtrap) | Reciben los códigos MFA, los enlaces de contraseña y los reportes |

## 2. Compilar el paquete (en el equipo de desarrollo)

```bash
cd frontend && npm ci && npm run build
cd ../backend && ./mvnw -q -DskipTests package       # deja backend/app/target/digsa.jar con la SPA adentro
```

Se copia `digsa.jar` al equipo de la prueba. Es un solo archivo: el frontend va dentro.

## 3. Base de datos

```sql
-- como superusuario de PostgreSQL
CREATE ROLE digsa_migracion LOGIN PASSWORD '<clave fuerte>';
CREATE ROLE digsa_app LOGIN PASSWORD '<otra clave fuerte>';
CREATE DATABASE digsa OWNER digsa_migracion ENCODING 'UTF8' TEMPLATE template0;
\c digsa
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO digsa_app;
```

Al arrancar, Flyway crea las tablas y carga solo los catálogos paramétricos:
- departamentos y municipios;
- fuerzas;
- tipos de documento y de factura;
- las 8 regionales;
- los 185 puntos del anexo del OPL;
- el checklist v6.

El rol de la aplicación queda con permisos mínimos: no puede borrar visitas, historial ni bitácora.

**Condición 4 de la prueba:** las tablas del proceso de auditoría quedan vacías. Se verifica con:

```bash
psql "postgresql://digsa_migracion@localhost/digsa" -f herramientas/verificar_base_limpia.sql
```

## 4. Variables de entorno (nunca en el repositorio)

| Variable | Ejemplo | Para qué |
|---|---|---|
| `DIGSA_ENTORNO` | `produccion` | Nunca `local` en la prueba |
| `DIGSA_URL` | `https://digsa-prueba.ags.local/` | Enlaces de los correos y cookies seguras |
| `DIGSA_DB_URL` | `jdbc:postgresql://localhost:5432/digsa` | |
| `DIGSA_DB_USUARIO` / `DIGSA_DB_CLAVE` | `digsa_app` / … | Rol de la aplicación |
| `DIGSA_DB_MIGRACION_USUARIO` / `DIGSA_DB_MIGRACION_CLAVE` | `digsa_migracion` / … | Dueño de las tablas (Flyway) |
| `DIGSA_DB_ROL_APP` | `digsa_app` | Para que las migraciones den los permisos mínimos |
| `DIGSA_SMTP_HOST` / `DIGSA_SMTP_PUERTO` / `DIGSA_SMTP_USUARIO` / `DIGSA_SMTP_CLAVE` | | Correo saliente |
| `DIGSA_CORREO_REMITENTE` | `notificaciones@agsamericas.com.co` | |
| `DIGSA_DOMINIOS_REPORTES` | `sanidad.mil.co,agsamericas.com.co` | Dominios a los que se envían reportes y logs (condición 17) |
| `DIGSA_CORREO_REPORTES` | correo del comité | Destino por defecto de los reportes |
| `DIGSA_ARCHIVOS_CARPETA` | `D:\digsa\archivos` | Evidencias y actas, fuera de la carpeta de la aplicación |
| `DIGSA_TURNSTILE_SITIO` / `DIGSA_TURNSTILE_SECRETA` | | Captcha. Sin ellas se desactiva y queda en el registro |

Si falta algo obligatorio, la aplicación no arranca y dice qué falta.

## 5. Arranque

```powershell
java -Xms512m -Xmx2g -jar digsa.jar
```

Primer administrador, una sola vez. El enlace para definir la contraseña llega al correo:

```powershell
java -jar digsa.jar --digsa.crear-admin.correo=admin.prueba@agsamericas.com.co --digsa.crear-admin.nombre="Administrador DIGSA" --digsa.crear-admin.usuario=admin.digsa --digsa.crear-admin.salir=true
```

**Duda 17.2:** para el ítem 2, que pide crear usuarios en dos equipos a la vez, se crean dos administradores. La sesión única bloquea un segundo ingreso del mismo usuario.

## 6. HTTPS con Caddy

`Caddyfile`:

```
digsa-prueba.ags.local {
    tls internal            # certificado propio de Caddy; con un dominio público, quite esta línea y Caddy pide uno de Let's Encrypt
    reverse_proxy 127.0.0.1:8080
}
```

Con `tls internal`, los equipos del comité deben confiar en la raíz de Caddy, o se usa un dominio público con certificado real (recomendado). La aplicación toma la IP real del cliente solo de los proxies de confianza, que son la red local por defecto de Tomcat.

## 7. Ensayo general (checklist del día anterior)

1. Base limpia verificada (paso 3) y dos administradores creados.
2. Ingreso con código MFA en el buzón real, cierre por inactividad a los 10 minutos y sesión única.
3. Ítem 2: crear Analista, Auditor 1, Auditor 2, Administrador y OPL, con contraseña inicial o por enlace.
4. Ítem 4: crear, modificar y eliminar una fuerza y un tipo de documento.
5. Ítem 11: crear una visita eligiendo los componentes y asignarlos a dos auditores; cada uno ve solo lo suyo; seguimiento en tiempo real.
6. Ítem 12: formulario con lugar en lista, inicio y fin con controles de fecha, número y auditor automáticos, personal auditado, hallazgos y varias evidencias en un solo envío.
7. Ítem 10: acta en PDF con firmas; reabrir la visita con motivo y volver a enviar el acta.
8. Ítem 13: los tres reportes con filtros; descarga en Excel, CSV, PDF y TXT; envío al correo del comité.
9. Ítem 15: logs en pantalla, con descarga y envío.
10. Copia de seguridad: `pg_dump -Fc digsa > digsa_antes_prueba.dump` y copia de `DIGSA_ARCHIVOS_CARPETA`.
