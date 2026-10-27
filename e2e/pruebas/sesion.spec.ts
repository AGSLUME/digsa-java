import { expect, test } from '@playwright/test';
import { ADMIN, filaDeUsuario, ingresar, salir, unico } from './apoyo';

test.afterEach(async ({ page }) => salir(page));

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
  // Cada respuesta del servidor cuenta como actividad: si una consulta del inicio responde después de adelantar el reloj, el plazo
  // vuelve a empezar y el aviso no sale. Se adelanta con la red quieta.
  await page.waitForLoadState('networkidle');
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
  await page.waitForLoadState('networkidle');
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

test('sesión única (decisión 7.1): con una sesión abierta el ingreso desde otro navegador se rechaza; tras «Salir» entra', async ({ browser }) => {
  const equipoA = await browser.newContext();
  const equipoB = await browser.newContext();
  const a = await equipoA.newPage();
  const b = await equipoB.newPage();
  await ingresar(a, ...ADMIN);
  await b.goto('/');
  await b.getByLabel('Usuario').fill(ADMIN[0]);
  await b.getByLabel('Contraseña').fill(ADMIN[1]);
  await b.getByRole('button', { name: 'Ingresar', exact: true }).click();
  await expect(b.getByTestId('error-ingreso')).toContainText('ya tiene una sesión abierta');
  await a.getByRole('link', { name: 'Usuarios' }).click();
  await expect(a.getByRole('heading', { name: 'Usuarios' })).toBeVisible();
  await a.getByRole('button', { name: 'Salir', exact: true }).click();
  await expect(a.getByRole('button', { name: 'Ingresar', exact: true })).toBeVisible();
  await ingresar(b, ...ADMIN);
  await salir(b);
  await equipoA.close();
  await equipoB.close();
});

test('contraseña inicial y cierre por el administrador: el usuario la cambia al entrar; el administrador cierra su sesión', async ({ page, browser }) => {
  await ingresar(page, ...ADMIN);
  await page.getByRole('link', { name: 'Usuarios' }).click();
  await page.getByRole('button', { name: 'Nuevo usuario' }).click();
  const usuario = unico('inicial');
  const dialogo = page.getByRole('dialog');
  await dialogo.getByLabel('Nombre completo').fill('Usuario Inicial de Prueba');
  await dialogo.getByLabel('Usuario').fill(usuario);
  await dialogo.getByLabel('Correo').fill(`${usuario}@example.com`);
  await dialogo.getByLabel('Coordinador', { exact: true }).check();
  await dialogo.getByText('Asignar contraseña inicial (se cambia al primer ingreso)').click();
  await dialogo.getByRole('textbox', { name: 'Contraseña inicial' }).fill('Inicial.Asignada.2026');
  await dialogo.getByRole('button', { name: 'Guardar' }).click();
  await expect(page.getByText(/Usuario creado con contraseña inicial/)).toBeVisible();

  const otro = await browser.newContext();
  const p2 = await otro.newPage();
  await p2.goto('/');
  await p2.getByLabel('Usuario').fill(usuario);
  await p2.getByLabel('Contraseña').fill('Inicial.Asignada.2026');
  await p2.getByRole('button', { name: 'Ingresar', exact: true }).click();
  await p2.getByRole('textbox', { name: 'Contraseña nueva', exact: true }).fill('Propia.Del.Usuario.2026');
  await p2.getByLabel('Confirme la contraseña nueva').fill('Propia.Del.Usuario.2026');
  await p2.getByRole('button', { name: 'Cambiar contraseña y continuar' }).click();
  const mensaje = p2.getByText(/local: \d{6}/);
  await expect(mensaje).toBeVisible();
  const codigo = /local: (\d{6})/.exec((await mensaje.textContent()) ?? '')?.[1] ?? '';
  const casillas = p2.locator('[data-testid="codigo"] input');
  for (let i = 0; i < 6; i++) await casillas.nth(i).fill(codigo[i]!);
  await p2.getByRole('button', { name: 'Verificar e ingresar' }).click();
  await expect(p2.getByRole('button', { name: 'Salir', exact: true })).toBeVisible();

  await page.reload();
  const fila = await filaDeUsuario(page, usuario);
  await expect(fila.getByText('Sesión abierta')).toBeVisible();
  page.once('dialog', (d) => d.accept());
  await fila.getByRole('button', { name: /Cerrar la sesión de/ }).click();
  await expect(page.getByText('Sesión cerrada.')).toBeVisible();
  await p2.reload();
  await expect(p2.getByTestId('motivo-cierre')).toContainText('Su sesión fue cerrada');
  await otro.close();
});
