import { expect, test } from '@playwright/test';
import { ADMIN, api, ingresar, ingresarConClaveInicial, salir, unico } from './apoyo';

// Exportación completa de los datos (docs/especificaciones/exportacion-alertas; duda 15.2): el administrador la pide, se genera en
// segundo plano, la pantalla la muestra «Lista» y la descarga el navegador. Otro perfil no ve la pantalla ni la API.

test.afterEach(async ({ page }) => salir(page));

const INICIAL = 'Inicial.Exportacion.2026';

test('el administrador genera la exportación completa, espera a que esté lista y la descarga', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  await page.getByRole('link', { name: 'Exportación de datos' }).click();
  await expect(page.getByRole('heading', { name: 'Exportación de datos' })).toBeVisible();
  await expect(page.getByTestId('aviso-ley-1581')).toContainText('no lo envíe por correo');
  await expect(page.getByText(/separado por punto y coma/)).toBeVisible();

  const generar = page.getByRole('button', { name: 'Generar exportación completa' });
  // Si quedó una en curso de una corrida anterior, se espera a que termine
  await expect(generar).toBeEnabled({ timeout: 120_000 });
  const pedido = page.waitForResponse((r) => r.url().endsWith('/api/exportacion') && r.request().method() === 'POST');
  await generar.click();
  const r = await pedido;
  expect(r.status()).toBe(202);
  const id = ((await r.json()) as { id: number }).id;

  const fila = page.getByTestId('exportacion-' + id);
  await expect(fila).toBeVisible();
  await expect(fila.getByTestId('estado')).toHaveText('Lista', { timeout: 120_000 });
  await expect(fila).toContainText(/Vence/);
  await expect(fila).toContainText(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}/);
  await expect(generar).toBeEnabled();

  const [descarga] = await Promise.all([page.waitForEvent('download'), fila.getByRole('link', { name: 'Descargar la exportación' }).click()]);
  expect(descarga.suggestedFilename()).toMatch(/^digsa_exportacion_.+\.zip$/);
  expect(await descarga.failure()).toBeNull();
});

test('un coordinador no ve la exportación en el menú ni puede abrirla', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  const usuario = unico('coordexp');
  const creado = await api<{ id: number }>(page, 'POST', '/api/usuarios', {
    nombre: `Coordinador exportación ${usuario}`,
    usuario,
    correo: `${usuario}@example.com`,
    documento: '80000003',
    perfiles: [2],
    claveInicial: INICIAL,
  });
  expect(creado.estado, JSON.stringify(creado.datos)).toBe(201);
  await salir(page);

  await ingresarConClaveInicial(page, usuario, INICIAL, 'Coordinador.Exportacion.2026');
  await expect(page.getByRole('link', { name: 'Exportación de datos' })).toHaveCount(0);
  await page.goto('/exportacion');
  await expect(page.getByText('Acceso restringido.')).toBeVisible();
  expect((await api(page, 'GET', '/api/exportacion')).estado).toBe(403);
});
