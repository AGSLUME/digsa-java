import { expect, test } from '@playwright/test';
import { ADMIN, api, ingresar, ingresarConClaveInicial, salir, unico } from './apoyo';

test.afterEach(async ({ page }) => salir(page));

// Usuarios, roles y logs · E2: roles adicionales parametrizables (Anexo 5, 1.12) y Analista con consulta nacional (prueba en sitio, ítem 3).

const INICIAL = 'Inicial.E2E.Roles.2026';
const PROPIA = 'Propia.E2E.Roles.2026';

test('el administrador crea un rol con base Coordinador y solo Riesgo y Dashboard; el usuario con ese rol ve solo esas pantallas', async ({
  page,
  browser,
}) => {
  await ingresar(page, ...ADMIN);
  await page.getByRole('link', { name: 'Usuarios' }).click();
  await page.getByRole('button', { name: 'Roles adicionales' }).click();
  const roles = page.getByRole('dialog', { name: 'Roles adicionales' });
  await roles.getByRole('button', { name: 'Nuevo rol' }).click();
  const nombreRol = unico('Gestor del riesgo ');
  const form = roles.getByTestId('formulario-rol');
  await form.getByLabel('Nombre del rol').fill(nombreRol);
  await form.getByRole('textbox', { name: 'Perfil base' }).click();
  await page.getByRole('option', { name: 'Coordinador', exact: true }).click();
  await form.getByLabel('Dashboard de riesgo').check();
  await form.getByLabel('Dashboard', { exact: true }).check();
  await form.getByRole('button', { name: 'Guardar rol' }).click();
  await expect(page.getByText('Rol creado.')).toBeVisible();
  await expect(roles.getByTestId('rol-' + nombreRol)).toContainText('Coordinador');

  // Se asigna por la API (el formulario de usuario ya se prueba en usuarios-y-logs) con contraseña inicial
  const lista = await api<{ perfiles: { id: number; nombre: string }[] }>(page, 'GET', '/api/usuarios');
  const rolId = lista.datos.perfiles.find((p) => p.nombre === nombreRol)?.id;
  expect(rolId).toBeTruthy();
  const usuario = unico('riesgo');
  const r = await api(page, 'POST', '/api/usuarios', {
    nombre: 'Gestor de Riesgo de Prueba',
    usuario,
    correo: `${usuario}@example.com`,
    perfiles: [rolId],
    claveInicial: INICIAL,
  });
  expect(r.estado, JSON.stringify(r.datos)).toBe(201);
  await salir(page);

  const ctx = await browser.newContext();
  const p = await ctx.newPage();
  await ingresarConClaveInicial(p, usuario, INICIAL, PROPIA);
  await expect(p.getByRole('link', { name: 'Dashboard de riesgo' })).toBeVisible();
  await expect(p.getByRole('link', { name: 'Dashboard', exact: true })).toBeVisible();
  await expect(p.getByRole('link', { name: 'Agenda de visitas' })).toHaveCount(0);
  await expect(p.getByRole('link', { name: 'Usuarios' })).toHaveCount(0);
  expect((await api(p, 'GET', '/api/agenda/opciones')).estado).toBe(403);
  await salir(p);
  await ctx.close();
});

test('Analista (ítem 3): consulta nacional, sin agenda ni solicitudes ni administración', async ({ page, browser }) => {
  await ingresar(page, ...ADMIN);
  const usuario = unico('analista');
  const r = await api(page, 'POST', '/api/usuarios', {
    nombre: 'Analista Uno de Prueba',
    usuario,
    correo: `${usuario}@example.com`,
    perfiles: [10],
    claveInicial: INICIAL,
  });
  expect(r.estado, JSON.stringify(r.datos)).toBe(201);
  await salir(page);

  const ctx = await browser.newContext();
  const p = await ctx.newPage();
  await ingresarConClaveInicial(p, usuario, INICIAL, PROPIA);
  for (const pantalla of ['Calendario de visitas', 'Dashboard de riesgo', 'Reportes de visitas', 'Prestadores con hallazgos']) {
    await expect(p.getByRole('link', { name: pantalla })).toBeVisible();
  }
  for (const pantalla of ['Agenda de visitas', 'Solicitudes de visita', 'Usuarios', 'Logs del sistema']) {
    await expect(p.getByRole('link', { name: pantalla })).toHaveCount(0);
  }
  await salir(p);
  await ctx.close();
});
