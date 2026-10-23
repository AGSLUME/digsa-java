# Guía: carga inicial de datos maestros (auditores, puntos y usuarios)

Para TI. Especificación: `docs/especificaciones/despliegue-carga/spec.md`, parte B. La carga es un modo de arranque de la **misma imagen** de la aplicación (como el primer administrador): hace su trabajo, escribe un informe y termina con un código de salida. No abre el puerto 8080 ni corre las tareas diarias, así que se puede ejecutar con la aplicación en servicio.

> **No se corre en la base de la prueba en sitio (11 al 27 de noviembre).** La condición general 4 del Anexo 5 exige las tablas maestras del proceso vacías (`herramientas/verificar_base_limpia.sql`). Solo en la base de operación.

## 1. Antes de empezar

- La aplicación instalada (`docker compose up -d`) y un **administrador activo** creado con `--digsa.crear-admin.*`: su usuario va en `--digsa.carga.responsable` y queda como autor en la bitácora.
- El correo SMTP configurado (los usuarios nuevos reciben el enlace «Bienvenido a la Suite DIGSA»). Si aún no hay correo, cargue con `--digsa.carga.enlaces=no` y envíe después con `enlaces=pendientes` (paso 5).
- Una carpeta `carga/` junto al `docker-compose.yml`. **No se versiona** (`.gitignore`) y no entra a la imagen (`.dockerignore`).
- En Linux el contenedor corre con el usuario sin privilegios de la imagen: para que pueda escribir el informe en la carpeta montada, agregue `--user "$(id -u):$(id -g)"` a los comandos `docker compose run` (o dé permiso de escritura a la carpeta).

## 2. Plantillas

```bash
mkdir -p carga
docker compose run --rm --no-deps -v "$PWD/carga:/carga" app --digsa.carga.plantillas=/carga
```

Deja en `carga/`:

| Archivo | Contenido |
|---|---|
| `plantilla_carga_maestros.xlsx` | Hojas Instrucciones, Auditores, Puntos, Usuarios y Listas (unidades, fuerzas, modalidades, roles, perfiles cargables, regionales activas y los municipios con su código DIVIPOLA, tomados de la base). Las columnas de lista tienen validación de datos. |
| `csv/auditores.csv`, `csv/puntos.csv`, `csv/usuarios.csv` | Los mismos encabezados en CSV UTF-8 con BOM y separador `;` (en una subcarpeta para que la carpeta de la carga no quede con los dos formatos). |

Los mismos tres CSV vacíos (sin las listas de la base) están en el repositorio, en `herramientas/plantillas/`, para preparar la carga sin servidor; una prueba verifica que son idénticos a los que genera la herramienta. Cada hoja trae una fila de ejemplo con «borrar esta fila»: **bórrela**, o la carga la marca como error. `--no-deps` evita arrancar la base si no está arriba; las listas se leen de la base, así que esta debe estar en servicio.

### Columnas (obligatorias en **negrilla**)

| Hoja | Columnas | Clave natural |
|---|---|---|
| Auditores | **nombre**, **correo**, **documento**, teléfono, **rol** (químico farmacéutico, regente, auditor en salud, ingeniero de sistemas, otro), municipio_base (código DIVIPOLA o «Departamento / Municipio»), usuario | correo |
| Puntos | sigla, **nombre**, **unidad** (ESM, FA, NC), fuerza (EJC, ARC, FAC; vacía en NC), modalidad (obligatoria en ESM), nit (solo FA), divipola o departamento + municipio, dirección, contacto, correos, exige_qf, regional, llega_desde, auditor_asignado (correo), activo | sigla; sin sigla, nombre + unidad |
| Usuarios | **nombre**, **correo**, usuario, documento, teléfono, **perfiles** (separados por coma), alcance_fuerza (Sanidad), alcance_punto (sigla o nombre del ESM), alcance_regional (OPL regional), activo | correo |

- **No hay columnas de contraseña.** Un archivo con una columna `clave`, `contraseña`, `password` o parecida se rechaza completo. Cada usuario nuevo recibe un enlace para definirla.
- El perfil **Administrador no se carga** (se crea con `--digsa.crear-admin`). El perfil **Auditor** va en la hoja Auditores (exige documento y rol).
- Para **completar puntos existentes** (por ejemplo, correos y auditor asignado de los ESM del anexo del OPL) basta la columna `sigla` y las columnas a cambiar: nombre, dirección y horario no se tocan.
- Un punto puede asignarse a un auditor de la misma carga y un usuario ESM a un punto de la misma carga: se resuelven en el orden auditores, puntos, usuarios.
- Los encabezados se comparan sin mayúsculas, tildes ni espacios y admiten sinónimos («Nombre del punto», «Código municipio», «Cédula», «Celular», «Email»…). Las columnas desconocidas se informan sin rechazar.

## 3. Simular (por defecto: no escribe nada)

```bash
docker compose run --rm -v "$PWD/carga:/carga" app --digsa.carga.carpeta=/carga --digsa.carga.responsable=admin
```

La carpeta debe traer **un** `.xlsx` (la plantilla) **o** uno o varios de `auditores.csv`, `puntos.csv` y `usuarios.csv`, no ambos. No se aceptan `.xls` ni `.xlsm`; máximo 10 MB por archivo y 5 000 filas por hoja. Una celda de Excel con fórmula se lee por su valor calculado (el informe lo avisa).

La simulación valida cada fila, resuelve las referencias y ejecuta los cambios en una transacción que **siempre se revierte**, así salen también los choques que solo ve la base. Escribe `resultado_carga_<aaaammdd_hhmm>.csv` en la carpeta (UTF-8 con BOM, `;`): una línea por fila con archivo, hoja, fila, clave natural, acción (`nuevo`, `actualizar`, `sin cambios`, `error`, `correo no enviado`, `aviso`), campos que cambian y motivo.

En la consola y en el log solo salen los conteos y, por cada error, la hoja, la fila, el campo y el motivo, **sin valores** (Ley 1581). Ejemplo: `auditores fila 12 · documento: no válido (solo letras, números, punto o guion)`. El informe CSV sí identifica la fila por su clave (correo o sigla).

Para dejar la carpeta de la carga en solo lectura, escriba el informe en otra:

```bash
docker compose run --rm -v "$PWD/carga:/carga:ro" -v "$PWD/informe:/informe" app \
  --digsa.carga.carpeta=/carga --digsa.carga.informe=/informe --digsa.carga.responsable=admin
```

## 4. Aplicar (todo o nada)

```bash
docker compose run --rm -v "$PWD/carga:/carga" app --digsa.carga.carpeta=/carga --digsa.carga.responsable=admin --digsa.carga.modo=aplicar
```

- Repite la validación completa. **Si hay una sola fila con error no escribe nada** (GMI P77 f): corrija y repita.
- Sin errores, aplica auditores, puntos y usuarios en **una sola transacción**. Dos cargas a la vez no se cruzan: la segunda termina con «Hay otra carga en curso.».
- **Idempotente:** crea lo que no existe y actualiza lo que existe por la clave natural. Una celda vacía no borra lo guardado; nada se borra ni se inactiva por no venir en el archivo (`activo` solo cambia si la columna trae valor); los perfiles se **agregan** y los que el usuario ya tenía se conservan (el informe los lista); a un usuario existente que aparece en la hoja de auditores se le agrega el perfil Auditor. Repetir la misma carpeta da todo «sin cambios».
- Después de confirmar envía «Bienvenido a la Suite DIGSA» con el enlace de un solo uso a cada usuario nuevo, de a uno y con una pausa (`--digsa.carga.pausa-ms`, 500 por defecto). Un correo que no sale no deshace la carga: el informe lo marca y el código es 4; resuélvalo con «Restablecer contraseña» en la pantalla Usuarios. A un usuario que ya existía no se le envía nada.
- La bitácora registra el resumen `carga_maestros` (responsable, origen `cli`, modo, SHA-256 de cada archivo, nuevos, actualizados, sin cambios y correos no enviados) y una fila por registro creado o actualizado (`auditor_creado`, `auditor_editado`, `punto_creado`, `punto_editado`, `usuario_creado`, `usuario_editado`) con los valores de antes y después; documento y teléfono enmascarados, nombre y correos sin valor. La simulación solo deja `carga_maestros_simulada`.

## 5. Enlaces diferidos

```bash
docker compose run --rm app --digsa.carga.enlaces=pendientes --digsa.carga.responsable=admin
```

Envía el enlace a los usuarios **activos que nunca definieron su contraseña**. Útil si se cargó con `--digsa.carga.enlaces=no` o por grupos.

> **Vigencia del enlace (duda abierta):** el enlace vence a los **60 minutos** (identidad-sesion), igual que el de «Olvidé mi contraseña». En una carga de decenas de usuarios muchos no lo abrirán a tiempo y deberán usar «¿Olvidó su contraseña?». Se recomienda una vigencia propia para la bienvenida (por ejemplo 72 horas, `digsa.clave.enlace-bienvenida-horas`) o enviar por grupos con `enlaces=pendientes`. Mientras se decide, la carga respeta los 60 minutos.

## 6. Códigos de salida

| Código | Significado |
|---|---|
| 0 | Sin errores (en `aplicar`, todo quedó cargado) |
| 1 | Uso no válido: falta un parámetro, carpeta vacía, Excel y CSV a la vez, o el responsable no es un administrador activo |
| 2 | Falla técnica (base no disponible, otra carga en curso); no se escribió nada |
| 3 | Hay filas con errores o el archivo se rechazó; no se escribió nada |
| 4 | Cargado, pero algún correo de bienvenida no salió (ver el informe) |

## 7. Datos personales (Ley 1581 de 2012)

Las plantillas diligenciadas y el informe contienen datos personales (nombres, documentos, teléfonos y correos):

1. Transfiéralos al servidor por un **canal cifrado** (SFTP, `scp`, recurso compartido cifrado), **nunca por correo**.
2. Móntelos en solo lectura cuando se pueda (paso 3, `--digsa.carga.informe`).
3. No los guarde en el repositorio (`carga/` e `informe/` están en `.gitignore`).
4. **Bórrelos del servidor al terminar**: `rm -rf carga informe`.
