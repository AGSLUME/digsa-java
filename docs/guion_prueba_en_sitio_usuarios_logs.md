# Guion de la prueba en sitio · ítems 2, 3 y 15 (usuarios, roles y logs)

Para quien opera la aplicación frente al Comité (Anexo 5, protocolo de pruebas, 11 a 27 de noviembre de 2026). El mismo recorrido está automatizado en `e2e/pruebas/prueba-en-sitio.spec.ts`: si esa prueba pasa en los cinco navegadores, la aplicación está lista para estos ítems.

## Antes de la prueba

- **Dos administradores** creados de antemano, uno por equipo. La sesión única (decisión 7.1) rechaza el mismo usuario en dos equipos.
- **TIME OUT:** en **Usuarios › Seguridad** se ve el valor vigente (10 minutos por defecto). Si el Comité pide comprobar el cierre sin esperar 10 minutos, se baja a 1 o 2 minutos ahí mismo y luego se restablece. El cambio queda en la bitácora.
- **Correo del Comité:** debe estar en `DIGSA_CORREO_REPORTES`, y su dominio en `DIGSA_DOMINIOS_REPORTES` (duda 17.11).
- **Buzón para el código de verificación:** cada ingreso pide un código de 6 dígitos que llega al correo del usuario (MFA, decisión del usuario: se deja encendido). Hay que tener a mano el buzón de los cinco usuarios (duda 17.1).

## Ítem 2 · Crear los cinco usuarios por la interfaz, en dos equipos a la vez

1. En cada equipo, ingresar con su administrador y abrir **Usuarios › Nuevo usuario**.
2. Por cada usuario:
   - diligenciar nombre, usuario, correo y documento (el documento es obligatorio para los auditores);
   - marcar el perfil que indique el Comité;
   - activar **«Asignar contraseña inicial»** y escribir una contraseña segura: mínimo 12 caracteres, mayúsculas, minúsculas, número y carácter especial, sin ñ ni tildes.

| Usuario | Perfil |
|---|---|
| Analista1 DIGSA | Analista |
| Auditor1 DIGSA, Auditor2 DIGSA | Auditor |
| Administrador DIGSA | Administrador |
| OPL DIGSA | OPL nacional (o el que indique el Comité) |

3. **Contraseñas cifradas:** se guardan con BCrypt; nadie, ni el administrador, puede leerlas después. La bitácora registra la creación sin la contraseña.
4. **Roles adicionales:** si el Comité pide un rol que no existe, se crea en **Usuarios › Roles adicionales** (Anexo 5, 1.12). Se elige un perfil base y las pantallas del rol.

## Ítem 3 · Ingreso y control por roles

| Qué verifica el Comité | Qué se hace |
|---|---|
| Usuario y contraseña | Ingresar con la contraseña inicial: la aplicación pide cambiarla ahí mismo, sin salir al correo, y luego el código de 6 dígitos |
| Rechazo de credenciales inválidas | Escribir una contraseña errada: sale «Usuario o contraseña incorrectos…», sin decir qué falló. A los 5 intentos el usuario se bloquea 15 minutos; el administrador lo puede **Desbloquear** |
| Bloqueo de sesión simultánea | Con la sesión abierta, ingresar con el mismo usuario en otro equipo o navegador: se rechaza («ya tiene una sesión abierta»). Se libera con «Salir», por inactividad o con **Cerrar sesión** en Usuarios |
| TIME OUT | Dejar la sesión quieta: al faltar un minuto (o la mitad del plazo) sale el aviso con la cuenta regresiva; al vencer, vuelve al ingreso con «La sesión se cerró por inactividad» |
| Funcionalidades por rol | **Analista:** consulta nacional (calendario, dashboards, riesgo, reportes, prestadores con hallazgos). **Auditor:** Mis visitas. **OPL:** solo Respuesta a hallazgos. **Administrador:** Usuarios, Logs, Exportación y todo lo demás |

## Ítem 15 · Logs de auditoría

1. Abrir **Logs del sistema** con el administrador. Filtrar por fecha y, si se quiere, por usuario, operación, módulo o tipo.
2. Cada fila trae fecha y hora, usuario, operación, tipo, detalle e IP. Las operaciones son inserción, actualización, eliminación, consulta, ingreso y salida.
3. **Modificaciones:** el botón **Ver** muestra cada campo con su valor de antes y de después (ítem 15 b). Los datos personales (nombre, documento, teléfono, correo) solo dicen «modificado» (Ley 1581).
4. **Descargar Excel** o **Descargar CSV** baja el informe con los filtros aplicados.
5. **Enviar por correo** lo manda en Excel al correo del Comité, sin salir de la aplicación.
