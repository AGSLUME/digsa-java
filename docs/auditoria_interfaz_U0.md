# Auditoría de la interfaz (U0) y plan de fases U1–U5

**Fecha:** 2026-10-23 · **Rama:** `interfaz` (desde develop 3d85b9e) · **Alcance:** frontend React (shared/, main.tsx, Plantilla, App y páginas de visitas, OPL, alertas, exportación, presupuesto, usuarios, logs, catálogos, maestros, dashboard y riesgo). Quedan fuera hasta que se integren: `Reportes.tsx` y las pantallas nuevas de reportes, analítica y homologación.

**Referencias:** mockup aprobado `MOCKUP/Mockup_Modulos_DIGSA.html`; Anexo 5 num. 1.18 (L318), 2.4, 3.7 y protocolo de la prueba en sitio (ítems 2 a 15, condiciones 11, 12 y 17); hoja de ruta, casos M10-5 y M10-6.

**Cómo se hizo:** tres revisiones de código (validación 2.4; responsive, perfiles y formatos; sistema visual frente al mockup) y un recorrido con Playwright de todas las pantallas de cada perfil en 360, 768 y 1280 px (`e2e/pruebas/zz-auditoria-interfaz.spec.ts`, solo con `DIGSA_AUDITORIA=1`; capturas y `resumen.json` en `e2e/test-results/auditoria`, no versionadas).

---

## 1. Requisitos de los anexos: estado actual

| Requisito | Estado | Brecha principal |
|---|---|---|
| **L318 · navegadores** (Edge, Chrome, Firefox, Safari, Opera) | Parcial | Se prueba en Chromium, Firefox, WebKit, Chrome y Edge. **Opera no está instalado** en el equipo: usa el motor Chromium (Blink) igual que Chrome y Edge. Duda UI-1 |
| **L318 · responsive** (smartphone y tableta) | Parcial | La página no se desborda (las tablas van en `ScrollArea`), pero **27 tablas** se desplazan a lo ancho por dentro sin vista en tarjetas. Grave: OPL (10 columnas, 1040 px), que el OPL usa desde el celular. Solo 1 de 13 modales de formulario pasa a pantalla completa en celular |
| **2.4 · español** | Cumple | Todo en español. `<html lang>` y `index.html` por revisar (título «Suite DIGSA · Visitas») |
| **2.4 · hora oficial colombiana, 24 h** | Parcial | La captura cumple (`CampoHora24`). Pero «hoy» se calcula con la zona del navegador en Solicitudes (mín./máx. de fecha), Riesgo (`hoyIso`), CalendarioMes, Agenda, Calendario y Logs; `DatesProvider` sin `timezone`. Historial y actas cortan el texto ISO sin convertir zona. Seguimiento muestra `HH:mm:ss` |
| **2.4 · cifras** (2 decimales, sin miles, solo coma) | **No cumple** | `exportacion.numero()` pone **«.» de miles** (Exportación). `cumplimientoLegible` con **1 decimal** («75,0 %», acta y checklist). `tablero.decimal()` no muestra «,00» en enteros. `Riesgo.tsx:306` muestra el puntaje en crudo (saldría con punto). No hay una utilidad única: hay 7 funciones distintas |
| **2.4 · alertas visuales y/o sonoras** ante el mal diligenciamiento | **No cumple el estándar** | Solo 4 campos muestran el error junto al campo. Ninguna pantalla mueve el foco al primer error ni tiene sonido. 8 formularios no tienen `<form>`, así que `required`, `min` y `type=email` son decorativos. Errores propios en texto gris o en toasts de 4 s. 12 botones se deshabilitan sin explicar el motivo |
| **3.7 · cada perfil ve lo suyo** | Cumple casi todo | El menú y las rutas los filtra el servidor; las acciones usan banderas del servidor. Dos desajustes con roles adicionales: Solicitudes muestra «Agendar» y «Anular» sin la pantalla *agenda* (el servidor responde 403); Riesgo oculta incidencias a quien sí puede |
| **Condición 12 (L1008) · sin cambiar de pantalla ni de herramienta** | Parcial | 4 enlaces abren documentos en **otra pestaña** (`target="_blank"`): evidencias y actas anteriores del checklist, escrito de la solicitud. Duda UI-5 |
| **Condición 17 · reportes por correo desde el sistema** | Parcial (de otra sesión) | Logs y Presupuesto envían por correo. El resto es de la sesión de reportes |
| **Mensajes de error del servidor visibles** | Parcial | 68 `Alert` persistentes, pero 21 errores del servidor salen solo en toasts (4 o 10 s): Checklist, Propuesta, Presupuesto, Riesgo, RespuestaOpl, VisitaDetalle, Usuarios, Maestros, Catálogos, Logs y Alertas |
| **Accesibilidad** | Parcial | No hay `h1` en ninguna página (el primer encabezado del DOM es el h4 de la cabecera). Estados por color con 7 tonos sueltos (red, orange, yellow, green, blue, gray, grape); ámbar en `yellow` no llega a AA. Foco visible por defecto de Mantine |

## 2. Sistema visual frente al mockup

| Elemento | Mockup | Hoy |
|---|---|---|
| Color principal | Verde `--brand #1C4632` / `#2E6A4B` | **Azul** `#036199` / `#0b3d5c` (`main.tsx:16-18`) |
| Fondo y bordes | `--bg #F3F5F2`, `--line #DCE2DD`, `th` en `--soft` | Blanco y grises de Mantine; tablas `striped` |
| Tipografía | IBM Plex Sans y Plex Mono (cifras en monoespaciada, tabulares) | Segoe UI; monoespaciada solo en presupuesto |
| Cabecera | Verde, logo, «Interventoría Integral DIGSA» con subtítulo, fecha y hora militar, cuenta regresiva de inactividad, píldora de perfil | Blanca, «Suite DIGSA», nombre del usuario y «Salir». La cuenta regresiva solo aparece en el modal final |
| Menú | 4 grupos (General, Auditoría, Información, Administración), ícono por módulo, numeral del anexo, activo en `#E1ECE5` | 3 grupos del servidor (Visitas, Presupuesto, Configuración), sin íconos ni numeral |
| Encabezado de página | `h1` 24 px + descripción en `--muted` + etiqueta de fuente «Anexo 5 · 4.5» | `Title order=3`, sin descripción uniforme ni fuente |
| KPI | Tarjeta: etiqueta 12 px, valor 24 px/600 monoespaciado, subtítulo | 6 componentes locales distintos (`Tarjeta`, `Caja` ×2, `Indicador`, `Cifra`, `Dato`) |
| Insignias | 5 tonos suaves: crit, warn, info, ok, neu | 76 `Badge`, 32 sólidos; 7 colores |
| Campos | Etiqueta arriba, borde `#C9D1CB`, error con borde y fondo `#FFF8F7` + mensaje | Por defecto de Mantine |

## 3. Brechas por pantalla (prioridad)

Prioridad: **A** = flujo de la prueba en sitio o requisito del anexo incumplido; **B** = requisito parcial o uso en celular; **C** = estética.

| Pantalla | Ítems de la prueba | Brechas | Prioridad |
|---|---|---|---|
| Plantilla (cabecera y menú) | Todos (3: «funcionalidades según el rol») | Tema, cabecera con hora militar y cuenta regresiva, perfil, menú con íconos y grupos del mockup, `h1` | A |
| Ingreso, Claves, Mi contraseña | 2, 3 | Reglas de contraseña y coincidencia solo en el servidor; botón del código deshabilitado sin explicar; error no va al campo | A |
| Usuarios y roles adicionales | 2, 3 | Errores de acciones en toast de 4 s; tabla de 7 columnas sin tarjetas; modales sin pantalla completa; botón «Guardar rol» deshabilitado sin motivo | A |
| Catálogos | 4 («alertas de validación ante datos mal diligenciados») | El patrón del código va solo en la descripción; correos sin validar; errores de regionales y parámetros en toast; 3 tablas sin tarjetas | A |
| Agenda, FormularioVisita, VisitaDetalle | 10, 11 | Errores propios en un Alert arriba y no en el campo; reprogramar, reabrir, reasignar y decidir objeción con errores en toast; 3 tablas del detalle sin tarjetas; fechas ISO | A |
| Checklist (ejecución, plan 5W2H, firma, acta) | 12 («introducir datos inválidos… comprobar validaciones») | Sin `<form>` en datos de la visita, personal y plan: `required` y `email` sin efecto; faltantes en texto naranja o toast; cumplimiento con 1 decimal; evidencias en otra pestaña | **A (máxima)** |
| Logs | 15 | Correo sin validar (sin `<form>`); descargas con error en toast; tabla de 7 columnas sin tarjetas; fecha con segundos | A |
| Exportación | M10-5 | **Separador de miles «.»** | A |
| OPL, OplResponder, RespuestaOpl | (perfil OPL, ítem 3) | Tabla de 10 columnas sin tarjetas en celular; nombre, cargo y justificación con error en toast; firma del plan sin mensaje | A |
| Presupuesto (novedad, ejecución, cargas, vigencia, reportes, tablero) | M7-1 a M7-3 | Errores en texto gris junto al botón; monto acepta letras sin marcar el campo; botones deshabilitados sin motivo; 6 tablas sin tarjetas; 9 pestañas con desplazamiento | B |
| Solicitudes, Prórrogas, Hallazgos, Seguimiento | 11 | «Hoy» del navegador; Agendar y Anular visibles sin permiso (3.7); Seguimiento y Solicitudes sin tarjetas; motivo de rechazo con error arriba | B |
| Riesgo y Dashboard | 13 | Puntaje en crudo; `decimal()` sin «,00»; errores de pesos e incidencias sin marcar el campo; tablas del Dashboard sin tarjetas | B |
| Alertas, Maestros, Propuesta, Calendario, Mis visitas | — | Errores de acciones en toast; NIT y correos sin validar; filas del borrador de propuesta sin validación; `SimpleGrid cols={4}` a 360 px | B/C |

> El resultado del recorrido con capturas (desbordamiento por pantalla y ancho, errores de consola, campos sin etiqueta) está en la sección 6.

## 4. Plan de fases

Reglas en todas las fases: no cambiar comportamiento, endpoints ni reglas; no romper selectores de las e2e (o actualizar la prueba en el mismo commit); sin librerías nuevas salvo la excepción de la duda UI-4; commits pequeños en `main.tsx`, `Plantilla.tsx` y `App.tsx`; revisión de `react-reviewer` y `a11y-architect` (P1 se corrigen antes del commit); Vitest, `tsc`, build y e2e completas en Chromium, Firefox, WebKit, Chrome y Edge en verde; 360 px sin desplazamiento horizontal de la página.

### U1 · Sistema visual
- Tema Mantine con los tokens del mockup: paleta `digsa` verde (10 tonos derivados de `#1C4632`/`#2E6A4B`), colores semánticos `crit`, `warn`, `info`, `ok` y `neu` con sus fondos, variables CSS (`--digsa-bg`, `--digsa-line`, `--digsa-soft`…) y fondo `#F3F5F2`.
- Tipografía IBM Plex Sans y Plex Mono alojadas en la aplicación (sin CDN), con cifras tabulares (duda UI-4).
- Estilos por defecto de los componentes en el tema (`Card`, `Paper`, `Table` con `th` suave y sin franjas, `Badge` suave, campos con borde y error del mockup, foco visible de 2 px).
- Componentes compartidos en `shared/ui/`: `EncabezadoPagina` (`h1` + descripción + fuente «Anexo 5 · x»), `Kpi`, `Estado` (insignia con texto: crit/warn/info/ok/neu) y la función que traduce los colores actuales (`COLOR_ESTADO`, `colorSemaforo`, etc.) a los 5 tonos.
- Contraste AA de todos los tonos verificado con prueba unitaria.

### U2 · Utilidades transversales (2.4)
- **`shared/formato.ts`, utilidad única con pruebas:** `cifra(n)` (2 decimales, redondeo a 2, coma, sin miles), `porcentaje(n)`, `monto(n | texto del servidor)`, `entero(n)` para conteos (duda UI-2), `fecha(iso)` y `fechaHora(v)` en hora de Bogotá 24 h (duda UI-3), `hoyBogota()`. Reemplaza `exportacion.numero`, `tablero.decimal`, `cumplimientoLegible`, `mostrarMonto` y los `toFixed`; los «hoy» del navegador; `DatesProvider` con `timezone: 'America/Bogota'`. Prueba que falla si aparece `toLocaleString` o `Intl.NumberFormat` en `src/`.
- **Validación y alertas estándar:** `shared/validacion.ts` + `useValidacion` + `ResumenErrores`:
  - las funciones `problema*` / `error*` existentes pasan a devolver un mapa `{campo: mensaje}` (sin cambiar las reglas);
  - mensaje junto al campo (`error` de Mantine, que pone `aria-invalid` y `aria-describedby`), borde y fondo de error;
  - resumen con `role="alert"` (aria-live) que enlaza a cada campo;
  - foco en el primer campo con error al enviar;
  - aviso sonoro corto con Web Audio (sin archivos), activable desde la cabecera y recordado en el navegador (duda UI-8);
  - errores del servidor en el resumen persistente, no en toasts (los toasts quedan solo para éxito);
  - botones que hoy se deshabilitan sin motivo: quedan habilitados y al pulsarlos muestran qué falta, o se explica el motivo junto al botón.
- **Aplicación en todos los formularios** (unos 45), empezando por los de la prueba: ingreso y claves, usuarios y roles, catálogos, formulario de visita, checklist (datos, personal, ítems, plan 5W2H, firma), reprogramar, reabrir, reasignar, respuesta del OPL, logs, presupuesto.
- Pruebas de componente (Vitest + Testing Library) de `ResumenErrores`, del foco y del sonido; e2e con datos inválidos en catálogos (ítem 4) y en el checklist (ítem 12).

### U3 · Navegación y responsive
- Plantilla como el mockup: cabecera verde con nombre y subtítulo, fecha y hora militar de Bogotá, cuenta regresiva de inactividad (reusa `inactividad.ts`), píldora de perfil, interruptor de sonido y «Salir» (mismo botón y texto).
- Menú con los 4 grupos del mockup, íconos de `@tabler/icons-react` (ya es dependencia) y numeral del anexo, mapeados en el frontend por `ruta` (sin tocar `Pantallas.java`; duda UI-7). Íconos y numeral con `aria-hidden` para no cambiar el nombre de los enlaces que usan las e2e. Objetivos táctiles de 44 px.
- Inicio como «Panel de interventoría»: KPI y tarjetas de los módulos que el perfil puede abrir (sin datos nuevos del servidor: solo lo que ya entrega cada pantalla).
- Componente `TablaAdaptable`: tabla en escritorio y tarjetas en celular y tableta vertical, aplicado a OPL, TablaVisitas (Agenda, Calendario), Seguimiento, Solicitudes, Usuarios, Logs, detalle de visita, Presupuesto (líneas, historial, ejecución) y Dashboard. Catálogos y Maestros mantienen desplazamiento interno (duda UI-10).
- Modales de formulario en pantalla completa en celular.
- Documentos dentro de la aplicación (visor en modal) en lugar de otra pestaña (duda UI-5).
- Pestañas de Presupuesto: selector compacto en celular.

### U4 · Pulido de pantallas por módulo
En el orden de la prueba en sitio: usuarios y roles (2, 3) → catálogos (4) → agenda y visita (10, 11) → checklist y acta (12) → reportes de visitas y riesgo (13) → logs (15) → OPL → presupuesto → resto. Cada pantalla con `EncabezadoPagina`, `Estado`, `Kpi`, cifras en monoespaciada alineadas a la derecha y mensajes cronometrables (estado de «Generando…» y «Listo» visibles). Corrección de 3.7 en Solicitudes.

### U5 · Opera, batería completa, rendimiento y guía
- Opera como canal si se instala; si no, documentado (duda UI-1).
- Prueba de 360 px sin desplazamiento en todas las pantallas y en los 5 motores; capturas finales.
- Bundle (hoy 886 kB): división por rutas con `React.lazy` en `App.tsx` **después** de que entren reportes y analítica (evita choques en `App.tsx`).
- `docs/guia_interfaz.md`: tokens, componentes, formato, validación, responsive y reglas para las otras sesiones.

## 5. Dudas (también en `docs/dudas_pendientes.md`, sección «Interfaz»)

Ver la sección «Interfaz» de `dudas_pendientes.md` (UI-1 a UI-12).

## 6. Resultado del recorrido con capturas

Corrida del 2026-10-23 en Chromium (85 capturas; antes, la suite e2e completa en Chromium como línea base: **48 de 48 en verde**).

**Menú por perfil (3.7):**
- **Administrador:** 18 pantallas, todas.
- **Coordinador:** 12 (sin Mis visitas, Calendario, OPL, Usuarios, Exportación ni Logs).
- **DIGSA:** 8.
- **Analista:** 6, todas de consulta.
- **OPL nacional:** solo Respuesta a hallazgos.
- **Rol adicional con base Coordinador:** solo Riesgo y Dashboard.

Coincide con lo que espera el ítem 3. Faltó crear desde la prueba los perfiles Auditor, Sanidad, ESM y OPL regional, que exigen documento, fuerza, establecimiento o regional; se recorren en U4 con esos datos.

| Hallazgo | Pantallas | Prioridad |
|---|---|---|
| Ninguna página tiene `h1` | Todas | A (U1) |
| **Insignias de estado cortadas** («CERRA…», «EN EJE…», «SIN A…»): el estado no se puede leer | Agenda y Calendario (`TablaVisitas`), a 1280 px | A (U1) |
| Desbordamiento horizontal de 4 px en los 3 anchos | Exportación | A (U3) |
| 14 botones sin nombre accesible y un `textarea` sin etiqueta | Riesgo | A (U2) |
| 1 botón sin nombre accesible | Ingreso, Solicitudes y Presupuesto | B (U2) |
| A 360 px, tabla con desplazamiento interno y columnas cortadas («Hall…») | OPL, Solicitudes, Seguimiento, Catálogos, Logs, Usuarios | B (U3) |
| Sin errores de consola | Todas | — |

En celular, el menú (Burger) funciona y la página no se desborda, salvo en Exportación.
