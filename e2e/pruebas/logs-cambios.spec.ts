import { expect, test } from '@playwright/test';
import { ADMIN, api, ingresar, salir, unico } from './apoyo';

test.afterEach(async ({ page }) => salir(page));

// Usuarios, roles y logs · E3: valores de antes y después en la bitácora (Anexo 5, prueba en sitio ítem 15 b), filtros por tipo y módulo.

test('el administrador edita un usuario y en Logs ve el antes y el después; el dato personal solo dice «modificado»', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  const usuario = unico('logcam');
  const creado = await api<{ id: number }>(page, 'POST', '/api/usuarios', {
    nombre: 'Persona Original de Prueba',
    usuario,
    correo: `${usuario}@example.com`,
    documento: '52333444',
    perfiles: [2],
    claveInicial: 'Inicial.E2E.Logs.2026',
  });
  expect(creado.estado, JSON.stringify(creado.datos)).toBe(201);
  const nuevo = usuario + 'b';
  const editado = await api(page, 'PUT', `/api/usuarios/${creado.datos.id}`, {
    nombre: 'Persona Cambiada de Prueba',
    usuario: nuevo,
    correo: `${usuario}@example.com`,
    documento: '52333444',
    perfiles: [2],
  });
  expect(editado.estado, JSON.stringify(editado.datos)).toBe(200);

  await page.getByRole('link', { name: 'Logs del sistema' }).click();
  await page.getByLabel('Tipo', { exact: true }).fill('no_existe');
  await page.getByRole('button', { name: 'Buscar' }).click();
  await expect(page.getByText('No hay registros con estos filtros.')).toBeVisible();
  await page.getByLabel('Tipo', { exact: true }).fill('usuario_editado');
  await page.getByRole('textbox', { name: 'Módulo' }).click();
  await page.getByRole('option', { name: 'usuarios', exact: true }).click();
  await page.getByRole('button', { name: 'Buscar' }).click();
  const tabla = page.getByTestId('tabla-logs');
  const fila = tabla.getByRole('row').filter({ hasText: `Usuario ${nuevo} editado` });
  await expect(fila).toHaveCount(1);
  await fila.getByRole('button', { name: /^Ver los cambios del registro/ }).click();
  const cambios = page.getByTestId('tabla-cambios');
  const filaUsuario = cambios.getByRole('row').filter({ has: page.getByRole('cell', { name: 'usuario', exact: true }) });
  const celdas = filaUsuario.getByRole('cell');
  await expect(celdas.nth(1)).toHaveText(usuario);
  await expect(celdas.nth(2)).toHaveText(nuevo);
  await expect(cambios.getByRole('row').filter({ has: page.getByRole('cell', { name: 'nombre', exact: true }) })).toContainText('dato personal');
  await expect(cambios).not.toContainText('Persona Cambiada');
});
