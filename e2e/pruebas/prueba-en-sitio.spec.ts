import { expect, test, type Browser, type Page } from '@playwright/test';
import { ADMIN, api, ingresar, ingresarConClaveInicial, salir, unico } from './apoyo';

// Guion de la prueba en sitio (Anexo 5, protocolo de pruebas), ítems 2, 3 y 15, tal como lo hará el Comité:
//  2. Crear, solo desde la interfaz y de forma simultánea en dos equipos, los usuarios Analista1, Auditor1, Auditor2, Administrador y
//     OPL DIGSA, con contraseñas seguras.
//  3. Ingresar con cada uno: usuario y contraseña, rechazo de credenciales inválidas, bloqueo de sesión simultánea, cierre por inactividad
//     (TIME OUT) y funcionalidades según el rol.
// 15. Logs en el navegador con las operaciones de los usuarios de prueba, descarga del informe y envío por correo.
// El servidor de las e2e corre con 1 minuto de inactividad (playwright.config.ts).

test.describe.configure({ mode: 'serial' });

const SUFIJO = unico('').slice(-6);
const INICIAL = 'Inicial.Prueba.Sitio.2026';
const PROPIA = 'Propia.Prueba.Sitio.2026';

/** Los cinco usuarios del ítem 2: perfil (id) y lo que el Comité espera ver en el menú. */
const USUARIOS = [
  {
    usuario: `analista1.${SUFIJO}`,
    nombre: 'Analista1 DIGSA',
    perfil: 'Analista',
    ve: ['Calendario de visitas', 'Reportes de visitas'],
    noVe: ['Agenda de visitas', 'Usuarios'],
  },
  {
    usuario: `auditor1.${SUFIJO}`,
    nombre: 'Auditor1 DIGSA',
    perfil: 'Auditor',
    ve: ['Mis visitas'],
    noVe: ['Agenda de visitas', 'Usuarios', 'Logs del sistema'],
  },
  {
    usuario: `auditor2.${SUFIJO}`,
    nombre: 'Auditor2 DIGSA',
    perfil: 'Auditor',
    ve: ['Mis visitas'],
    noVe: ['Agenda de visitas', 'Usuarios', 'Logs del sistema'],
  },
  { usuario: `admin.${SUFIJO}`, nombre: 'Administrador DIGSA', perfil: 'Administrador', ve: ['Usuarios', 'Logs del sistema'], noVe: [] },
  {
    usuario: `opl.${SUFIJO}`,
    nombre: 'OPL DIGSA',
    perfil: 'OPL nacional',
    ve: ['Respuesta a hallazgos'],
    noVe: ['Agenda de visitas', 'Calendario de visitas', 'Usuarios'],
  },
] as const;

/** Crea un usuario por el formulario de Usuarios con contraseña inicial (ítem 2: «solo desde la interfaz gráfica»). */
async function crearPorPantalla(page: Page, u: (typeof USUARIOS)[number], documento: string): Promise<void> {
  await page.getByRole('button', { name: 'Nuevo usuario' }).click();
  const dialogo = page.getByRole('dialog', { name: 'Nuevo usuario' });
  await dialogo.getByLabel('Nombre completo').fill(u.nombre);
  await dialogo.getByLabel('Usuario').fill(u.usuario);
  await dialogo.getByLabel('Correo').fill(`${u.usuario}@example.com`);
  await dialogo.getByLabel('Documento').fill(documento);
  await dialogo.getByLabel(u.perfil, { exact: true }).check();
  // El input del Switch de Mantine está oculto: se pulsa su etiqueta, como en sesion.spec.ts
  await dialogo.getByText('Asignar contraseña inicial (se cambia al primer ingreso)').click();
  await dialogo.getByRole('textbox', { name: 'Contraseña inicial' }).fill(INICIAL);
  await dialogo.getByRole('button', { name: 'Guardar' }).click();
  await expect(page.getByText('Usuario creado con contraseña inicial.')).toBeVisible();
  await expect(page.getByTestId('usuario-' + u.usuario)).toBeVisible();
}

/** Segundo equipo: otro administrador (el ítem 2 crea en dos equipos a la vez y la sesión única no deja usar el mismo usuario). */
async function segundoAdministrador(page: Page, browser: Browser): Promise<Page> {
  const usuario = `admin2.${SUFIJO}`;
  const r = await api(page, 'POST', '/api/usuarios', {
    nombre: 'Segundo Administrador de Prueba',
    usuario,
    correo: `${usuario}@example.com`,
    perfiles: [1],
    claveInicial: INICIAL,
  });
  expect(r.estado, JSON.stringify(r.datos)).toBe(201);
  const p = await (await browser.newContext()).newPage();
  await ingresarConClaveInicial(p, usuario, INICIAL, PROPIA);
  await p.getByRole('link', { name: 'Usuarios' }).click();
  return p;
}

test('ítem 2: dos administradores crean a la vez, por la interfaz, los cinco usuarios de la prueba', async ({ page, browser }) => {
  await ingresar(page, ...ADMIN);
  const otro = await segundoAdministrador(page, browser);
  await page.getByRole('link', { name: 'Usuarios' }).click();
  // Equipo 1: Analista1, Auditor1 y Administrador; equipo 2: Auditor2 y OPL, al mismo tiempo
  await Promise.all([
    (async () => {
      await crearPorPantalla(page, USUARIOS[0], '80100001');
      await crearPorPantalla(page, USUARIOS[1], '80100002');
      await crearPorPantalla(page, USUARIOS[3], '80100004');
    })(),
    (async () => {
      await crearPorPantalla(otro, USUARIOS[2], '80100003');
      await crearPorPantalla(otro, USUARIOS[4], '80100005');
    })(),
  ]);
  await page.reload();
  for (const u of USUARIOS) {
    await expect(page.getByTestId('usuario-' + u.usuario)).toContainText(u.perfil);
    await expect(page.getByTestId('usuario-' + u.usuario)).toContainText('Contraseña inicial');
  }
  await salir(otro);
  await otro.context().close();
  await salir(page);
});

test('ítem 3: ingreso, credenciales inválidas, funcionalidades por rol y sin sesión simultánea', async ({ browser }) => {
  for (const u of USUARIOS) {
    const ctx = await browser.newContext();
    const p = await ctx.newPage();

    // Credenciales inválidas: mismo mensaje, sin decir qué falló
    await p.goto('/');
    await p.getByLabel('Usuario').fill(u.usuario);
    await p.getByLabel('Contraseña').fill('No.Es.La.Clave.2026');
    await p.getByRole('button', { name: 'Ingresar', exact: true }).click();
    await expect(p.getByTestId('error-ingreso')).toContainText('Usuario o contraseña incorrectos.');

    // Primer ingreso con la contraseña inicial: la cambia en la aplicación y sigue con el código
    await ingresarConClaveInicial(p, u.usuario, INICIAL, PROPIA);
    for (const pantalla of u.ve) {
      await expect(p.getByRole('link', { name: pantalla, exact: true })).toBeVisible();
    }
    for (const pantalla of u.noVe) {
      await expect(p.getByRole('link', { name: pantalla, exact: true })).toHaveCount(0);
    }

    // Sesión simultánea: el mismo usuario en otro equipo o navegador queda bloqueado
    const ctx2 = await browser.newContext();
    const p2 = await ctx2.newPage();
    await p2.goto('/');
    await p2.getByLabel('Usuario').fill(u.usuario);
    await p2.getByLabel('Contraseña').fill(PROPIA);
    await p2.getByRole('button', { name: 'Ingresar', exact: true }).click();
    await expect(p2.getByTestId('error-ingreso')).toContainText('ya tiene una sesión abierta');
    await ctx2.close();

    await p.getByRole('button', { name: 'Salir', exact: true }).click();
    await expect(p.getByRole('button', { name: 'Ingresar', exact: true })).toBeVisible();
    await ctx.close();
  }
});

test('ítem 3: TIME OUT, la sesión se cierra sola por inactividad y avisa antes', async ({ browser }) => {
  test.setTimeout(240_000);
  const u = USUARIOS[1];
  const ctx = await browser.newContext();
  const p = await ctx.newPage();
  await ingresar(p, u.usuario, PROPIA);
  // Con 1 minuto de inactividad el aviso sale a los 30 s y la sesión se cierra al minuto
  await expect(p.getByRole('dialog', { name: 'Su sesión está por cerrarse' })).toBeVisible({ timeout: 70_000 });
  await expect(p.getByRole('button', { name: 'Ingresar', exact: true })).toBeVisible({ timeout: 70_000 });
  await expect(p.getByTestId('motivo-cierre')).toContainText('inactividad');
  // El usuario puede volver a entrar: la sesión vencida no bloquea un ingreso nuevo
  await ingresar(p, u.usuario, PROPIA);
  await salir(p);
  await ctx.close();
});

test('ítem 15: logs en el navegador con las operaciones de los usuarios de prueba; descarga y envío por correo', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  await page.getByRole('link', { name: 'Logs del sistema' }).click();
  await page.getByLabel('Texto', { exact: true }).fill(SUFIJO);
  await page.getByRole('button', { name: 'Buscar' }).click();
  const tabla = page.getByTestId('tabla-logs');
  // Creación (inserción), ingreso, salida y el ingreso fallido de cada usuario de prueba
  await expect(tabla.getByRole('cell', { name: 'usuario_creado', exact: true }).first()).toBeVisible();
  for (const u of USUARIOS) {
    await expect(tabla.getByRole('row').filter({ hasText: u.usuario }).first()).toBeVisible();
  }
  for (const operacion of ['Inserción', 'Ingreso', 'Salida']) {
    await expect(tabla.getByText(operacion, { exact: true }).first()).toBeVisible();
  }
  const [descarga] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: 'Descargar Excel' }).click()]);
  expect(descarga.suggestedFilename()).toMatch(/^logs_auditoria_\d{8}_\d{4}\.xlsx$/);
  await page.getByRole('button', { name: 'Enviar por correo' }).click();
  await page.getByLabel('Correo de destino').fill('comite.evaluador@example.com');
  await page.getByRole('button', { name: 'Enviar', exact: true }).click();
  await expect(page.getByText('Reporte enviado a comite.evaluador@example.com.')).toBeVisible();
  await salir(page);
});
