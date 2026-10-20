import { expect, test } from '@playwright/test';
import { ADMIN, ingresar } from './apoyo';

// Portado de tests/ui/ui_sesion.js: ingreso, aviso de inactividad, «Seguir conectado», cierre por inactividad y sesión única.

test('ingreso en dos pasos y salir', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  await expect(page.getByRole('link', { name: 'Usuarios' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Logs del sistema' })).toBeVisible();
  await page.getByRole('button', { name: 'Salir', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Ingresar', exact: true })).toBeVisible();
});

test('contraseña errada: mensaje genérico', async ({ page }) => {
  await page.goto('/');
  await page.getByLabel('Usuario').fill(ADMIN[0]);
  await page.getByLabel('Contraseña').fill('No.Es.La.Clave.2026');
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click();
  await expect(page.getByTestId('error-ingreso')).toContainText('Usuario o contraseña incorrectos');
});

test('aviso de inactividad con cuenta regresiva y «Seguir conectado»', async ({ page }) => {
  await page.clock.install();
  await ingresar(page, ...ADMIN);
  // Servidor de pruebas con 1 minuto: el aviso sale a los 30 s (la mitad del plazo)
  await page.clock.fastForward(31_000);
  await expect(page.getByText('Su sesión está por cerrarse')).toBeVisible();
  await expect(page.getByTestId('cuenta-regresiva')).toHaveText(/00:\d{2}/);
  await page.getByRole('button', { name: 'Seguir conectado' }).click();
  await expect(page.getByText('Su sesión está por cerrarse')).toBeHidden();
});

test('actividad en otra pestaña: el aviso de esta pestaña se cierra', async ({ page }) => {
  await page.clock.install();
  await ingresar(page, ...ADMIN);
  await page.clock.fastForward(31_000);
  const aviso = page.getByRole('dialog', { name: 'Su sesión está por cerrarse' });
  await expect(aviso).toBeVisible();
  // Otra pestaña tuvo respuesta del servidor: deja su marca en localStorage (clave compartida)
  await page.evaluate(() => localStorage.setItem('digsa.actividad', String(Date.now())));
  await page.clock.fastForward(2_000);
  await expect(aviso).toBeHidden();
});

test('cierre por inactividad: vuelve al ingreso con el motivo', async ({ page, browserName }) => {
  test.skip(browserName !== 'chromium', 'Espera real de 1 minuto: basta un motor (el servidor es el mismo)');
  test.setTimeout(150_000);
  await ingresar(page, ...ADMIN);
  await expect(page.getByText('Su sesión está por cerrarse')).toBeVisible({ timeout: 40_000 });
  await expect(page.getByTestId('motivo-cierre')).toContainText('La sesión se cerró por inactividad', { timeout: 60_000 });
});

test('sesión única: el ingreso desde otro navegador cierra la sesión anterior con el motivo', async ({ browser }) => {
  const equipoA = await browser.newContext();
  const equipoB = await browser.newContext();
  const a = await equipoA.newPage();
  const b = await equipoB.newPage();
  await ingresar(a, ...ADMIN);
  await ingresar(b, ...ADMIN);
  await a.getByRole('link', { name: 'Usuarios' }).click();
  await expect(a.getByTestId('motivo-cierre')).toContainText('ingresó desde otro equipo o navegador');
  await b.getByRole('link', { name: 'Usuarios' }).click();
  await expect(b.getByRole('heading', { name: 'Usuarios' })).toBeVisible();
  await equipoA.close();
  await equipoB.close();
});
