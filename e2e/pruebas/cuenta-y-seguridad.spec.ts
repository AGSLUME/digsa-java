import { expect, test } from '@playwright/test';
import { ADMIN, api, ingresar, ingresarConClaveInicial, salir, sinDesplazamientoHorizontal, unico } from './apoyo';

test.afterEach(async ({ page }) => salir(page));

// Usuarios, roles y logs · E1: «Cambiar mi contraseña» sin salir de la aplicación (condición 12), TIME OUT editable por el administrador
// (Anexo 5, 3.2: «parametrizable y ajustable») y desbloqueo de un usuario.

const INICIAL = 'Inicial.E2E.Cuenta.2026';
const PROPIA = 'Propia.E2E.Cuenta.2026';
const NUEVA = 'Nueva.E2E.Cuenta.2026';

/** Crea por la API (con la sesión del administrador) un auditor con contraseña inicial. */
async function crearAuditor(page: import('@playwright/test').Page): Promise<string> {
  const usuario = unico('cuenta');
  const r = await api(page, 'POST', '/api/usuarios', {
    nombre: 'Auditor Cuenta de Prueba',
    usuario,
    correo: `${usuario}@example.com`,
    documento: '80111222',
    perfiles: [3],
    claveInicial: INICIAL,
  });
  expect(r.estado, JSON.stringify(r.datos)).toBe(201);
  return usuario;
}

test('cambiar mi contraseña: con la actual errada avisa; con la correcta sigue en la sesión y el próximo ingreso es con la nueva', async ({
  page,
  browser,
}) => {
  await ingresar(page, ...ADMIN);
  const usuario = await crearAuditor(page);
  await salir(page);

  const ctx = await browser.newContext();
  const p = await ctx.newPage();
  await ingresarConClaveInicial(p, usuario, INICIAL, PROPIA);
  await p.getByRole('button', { name: 'Mi contraseña' }).click();
  const dialogo = p.getByRole('dialog', { name: 'Cambiar mi contraseña' });
  await dialogo.getByLabel('Contraseña actual').fill('No.Es.La.Actual.2026');
  // La etiqueta lleva el asterisco de obligatorio: por el nombre accesible, como en apoyo.ts
  await dialogo.getByRole('textbox', { name: 'Contraseña nueva', exact: true }).fill(NUEVA);
  await dialogo.getByLabel('Confirme la contraseña nueva').fill(NUEVA);
  await dialogo.getByRole('button', { name: 'Cambiar contraseña' }).click();
  await expect(p.getByTestId('error-mi-clave')).toHaveText('La contraseña actual no es correcta.');

  await dialogo.getByLabel('Contraseña actual').fill(PROPIA);
  await dialogo.getByRole('button', { name: 'Cambiar contraseña' }).click();
  await expect(p.getByText('Su contraseña se cambió.')).toBeVisible();
  await expect(dialogo).toHaveCount(0);
  await p.getByRole('link', { name: 'Mis visitas' }).click();
  await expect(p.getByRole('button', { name: 'Salir', exact: true })).toBeVisible();
  await p.getByRole('button', { name: 'Salir', exact: true }).click();
  await expect(p.getByRole('button', { name: 'Ingresar', exact: true })).toBeVisible(); // «Salir» termina de volver al ingreso

  await ingresar(p, usuario, NUEVA);
  await salir(p);
  await ctx.close();
});

test('el administrador cambia el TIME OUT en Usuarios › Seguridad y lo restablece', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  const antes = (await api<{ minutosSesion: number }>(page, 'GET', '/api/usuarios/seguridad')).datos.minutosSesion;
  const otro = antes === 2 ? 3 : 2;
  try {
    await page.getByRole('link', { name: 'Usuarios' }).click();
    await page.getByRole('button', { name: 'Seguridad' }).click();
    const campo = page.getByTestId('minutos-sesion');
    await expect(campo).toHaveValue(String(antes));
    await campo.fill(String(otro));
    await page.getByRole('dialog', { name: 'Seguridad' }).getByRole('button', { name: 'Guardar' }).click();
    await expect(page.getByText(`Tiempo de inactividad actualizado: ${otro} minutos.`)).toBeVisible();
    expect((await api<{ minutosSesion: number }>(page, 'GET', '/api/usuarios/seguridad')).datos.minutosSesion).toBe(otro);
    expect((await api<{ minutosInactividad: number }>(page, 'GET', '/api/sesion')).datos.minutosInactividad).toBe(otro);
  } finally {
    // El servidor de las e2e es compartido: si no se restablece, las pruebas siguientes fallan por inactividad sin decir por qué
    const r = await api(page, 'PUT', '/api/usuarios/seguridad', { minutosSesion: antes });
    expect(r.estado, 'restablecer el TIME OUT').toBe(200);
  }
});

test('un usuario bloqueado por 5 intentos aparece «Bloqueado» y el administrador lo desbloquea', async ({ page, browser }) => {
  await ingresar(page, ...ADMIN);
  const usuario = await crearAuditor(page);

  const ctx = await browser.newContext();
  const p = await ctx.newPage();
  await p.goto('/');
  await api(p, 'GET', '/api/auth/inicio');
  for (let i = 0; i < 5; i++) {
    expect((await api(p, 'POST', '/api/auth/ingreso', { usuario, clave: `Errada.Numero.${i}.X` })).estado).toBe(401);
  }
  await ctx.close();

  await page.getByRole('link', { name: 'Usuarios' }).click();
  const fila = page.getByTestId('usuario-' + usuario);
  await expect(fila.getByText('Bloqueado')).toBeVisible();
  await fila.getByRole('button', { name: 'Desbloquear a Auditor Cuenta de Prueba' }).click();
  await expect(page.getByText('Usuario desbloqueado.')).toBeVisible();
  await expect(fila.getByText('Bloqueado')).toHaveCount(0);
});

test('celular (360 px): «Mi contraseña» está en el menú y no hay desplazamiento horizontal', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await ingresar(page, ...ADMIN);
  await sinDesplazamientoHorizontal(page);
  await page.getByRole('button', { name: 'Menú' }).click();
  await page.getByRole('button', { name: 'Mi contraseña' }).click();
  await expect(page.getByRole('dialog', { name: 'Cambiar mi contraseña' })).toBeVisible();
  await sinDesplazamientoHorizontal(page);
});
