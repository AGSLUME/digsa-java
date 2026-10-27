# Guía de interfaz de la Suite DIGSA

Para quien agrega o cambia pantallas del frontend (`frontend/src`). Resume lo que dejaron las fases U0–U5 de la sesión de interfaz. El
detalle de cada decisión está en `docs/dudas_pendientes.md`, sección «Interfaz» (UI-1 a UI-48).

**Requisitos que cubre:** Anexo 5 num. 1.18 (navegadores y responsive), 2.4 (español, hora oficial de Colombia en 24 h, cifras con
dos decimales, alertas visuales y sonoras) y 3.7 (cada perfil ve lo suyo); condición 12 de la prueba en sitio (sin cambiar de
pantalla ni de herramienta).

## 1. Design system LUME

- **Tokens:** `frontend/src/lume.css`, variables `--lume-*`. Es la única fuente de colores; no se agregan colores fuera de la paleta.
  `shared/tema.ts` repite los valores para Mantine y `tema.test.ts` comprueba que coinciden y que todo llega a AA.
- **Colores en las pantallas:** usar los nombres de Mantine `red`, `orange`, `yellow`, `green`, `blue`, `gray` (o `lume` para el
  primario). El tema los traduce a las 5 variantes LUME (danger, warning, warning, success, info, neutral). No usar tonos fijos
  (`red.8`, `#B91C1C`) ni colores como `grape` o `teal`.
- **Contraste:** el texto apagado (`c="dimmed"`) es #475569 porque el #64748B de la especificación no llega a AA sobre el fondo de la
  página. Los ajustes aprobados están en UI-35.
- **Fuente:** Inter (`@fontsource/inter`, alojada en la aplicación, sin CDN). Las cifras usan números tabulares.

## 2. Componentes compartidos (`frontend/src/shared`)

| Componente | Para qué |
|---|---|
| `EncabezadoPagina` | Toda pantalla de la aplicación empieza con él (las públicas, como el ingreso, ponen su propio h1): h1, descripción, fuente normativa («Anexo 5 · 4.5») y acciones. Pone el título de la pestaña |
| `Kpi` | Tarjeta de indicador (etiqueta, cifra, detalle) |
| `TablaAdaptable` | Envuelve una `Table`: en escritorio se desplaza dentro de su caja; en celular se apila en tarjetas. No cambia filas ni `data-testid` |
| `Paginacion` + `usePaginacion` | Paginación en el navegador, «Mostrando X–Y de Z» |
| `BotonConMotivo` | Botón que todavía no se puede usar: `aria-disabled`, suena y lleva el foco a lo que falta. No usar `disabled` sin explicar |
| `CampoHora24` | Hora militar en dos listas (00–23 y 00–59). No usar `type="time"` ni un campo de texto para la hora |
| `VisorDocumento` | Ver PDF, PNG o JPEG dentro de la aplicación (lista blanca; lo demás se descarga). Nunca `target="_blank"` |
| `Plantilla` | Shell: sidebar, cabecera con migas, campana, sonido y usuario. El menú sale del servidor (`Pantallas.java`); `shared/menu.ts` solo dice grupo, ícono y numeral por ruta |

Una pantalla nueva del servidor aparece sola en el menú (en el grupo que mande `Pantallas.java`). Para darle grupo LUME, ícono y numeral,
se agrega su ruta en `shared/menu.ts`.

## 3. Formato (`shared/formato.ts`)

| Dato | Función | Ejemplo |
|---|---|---|
| Monto, porcentaje, puntaje, promedio, tiempo | `cifra`, `porcentaje`, `monto` | «1450000,00», «61,40 %» |
| Conteo | `conteo` | «12» (decisión UI-2; cambiable en una línea con `CONTEOS_CON_DECIMALES`) |
| Fecha | `fecha` | «23/10/2026» |
| Fecha y hora | `fechaHora` | «23/10/2026 14:05», en hora de Bogotá |
| Hora | `hora` | «14:05» |
| «Hoy» | `hoyBogota` | Nunca `new Date()` con la zona del equipo |

Nunca separador de miles. No usar `toFixed`, `toLocaleString` ni `Intl.NumberFormat` en las pantallas (`formato.test.ts` vigila `toLocaleString` e `Intl.NumberFormat`). Los
archivos exportados llevan las fechas en ISO.

## 4. Validación (Anexo 5 num. 2.4)

- `useValidacion` (`shared/validacion.tsx`): `validar({campo: mensaje})` marca cada campo, muestra el resumen con `role="alert"`, lleva
  el foco al primer error y suena. `errorServidor(e, texto)` deja el error del servidor en el resumen (persistente) y marca el campo
  si el 422 trae `campo`.
- Formularios con `noValidate`: el mensaje propio va junto al campo (`error={v.error('campo')}`). Los toasts quedan solo para éxito.
- Reglas reutilizables en `shared/validaciones.ts` (correo, contraseña, `conTexto` para grupos de casillas).
- El sonido (Web Audio, sin archivos) se apaga desde la cabecera y el navegador lo recuerda. Siempre acompaña a un aviso visual.

## 5. Responsive y accesibilidad

- Sin desplazamiento horizontal de la página a 360 px en ninguna pantalla (`responsivo-360.spec.ts` lo prueba en cada motor). Las tablas
  van en `TablaAdaptable`; Catálogos y Maestros conservan el desplazamiento interno (UI-10).
- Modales a pantalla completa en celular (CSS global). Objetivos táctiles de 44 px en la navegación.
- Un solo h1 por pantalla (el de `EncabezadoPagina`); las secciones en h2 y h3, sin saltos. Para que se vean más pequeñas, `size`, no
  `order`.
- Íconos con `aria-hidden`; botones de solo ícono con `aria-label`. Cuidado con nombres que contengan palabras que las e2e buscan con
  `getByLabel` sin `exact` (UI-46).
- ESLint (`npm run lint`, también en el pipeline) revisa los hooks de React y jsx-a11y.

## 6. Pruebas

- `npm run typecheck`, `npm run lint`, `npm test` (Vitest) y `npm run build` en `frontend`.
- E2E en `e2e` con los puertos de la sesión (`DIGSA_E2E_PUERTO`, `DIGSA_E2E_PGPUERTO`) en Chromium, Firefox y WebKit; con
  `DIGSA_E2E_CANALES=chrome,msedge` también en Chrome y Edge instalados. Antes, `mvnw install -DskipTests` y el build del frontend.
- `DIGSA_AUDITORIA=1` corre el recorrido de capturas por perfil en 360, 768 y 1280 px (`e2e/test-results/auditoria`): desbordes,
  botones sin nombre, campos sin etiqueta y saltos de encabezado.
- La base local de las e2e (`.local/pgdata`) acumula datos: si `presupuesto.spec` dice que no queda año para la vigencia, se aparta esa
  carpeta y se corre con base nueva (UI-29).

## 7. Navegadores

Chrome, Edge, Firefox y Safari (motor WebKit) se prueban en cada corrida. **Opera** no está instalado en el equipo de desarrollo; usa el
mismo motor (Chromium/Blink) que Chrome y Edge, y se agrega como canal si se instala (UI-1).
