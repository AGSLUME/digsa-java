import { expect, test } from '@playwright/test';
import { ADMIN, ingresar, sinDesplazamientoHorizontal, unico } from './apoyo';

// Anexo 5, prueba 2 (usuarios por la interfaz, contraseñas cifradas) y prueba 15 (logs en el navegador, con descarga y correo).

test('el administrador crea un auditor; el auditor define su contraseña con el enlace e ingresa con su perfil', async ({ page, browser }) => {
  await ingresar(page, ...ADMIN);
  await page.getByRole('link', { name: 'Usuarios' }).click();
  await page.getByRole('button', { name: 'Nuevo usuario' }).click();
  const usuario = unico('auditor');
  const dialogo = page.getByRole('dialog');
  await dialogo.getByLabel('Nombre completo').fill('Auditor Uno de Prueba');
  await dialogo.getByLabel('Usuario').fill(usuario);
  await dialogo.getByLabel('Correo').fill(`${usuario}@example.com`);
  await dialogo.getByLabel('Documento').fill('80123456');
  await dialogo.getByLabel('Auditor', { exact: true }).check();
  await dialogo.getByRole('button', { name: 'Guardar' }).click();
  const aviso = page.getByText(/Usuario creado/);
  await expect(aviso).toBeVisible();
  const enlace = /local: (http\S+)\)/.exec((await aviso.textContent()) ?? '')?.[1];
  expect(enlace).toBeTruthy();
  await expect(page.getByTestId('usuario-' + usuario)).toBeVisible();

  // Validación: el mismo usuario otra vez
  await page.getByRole('button', { name: 'Nuevo usuario' }).click();
  await dialogo.getByLabel('Nombre completo').fill('Duplicado');
  await dialogo.getByLabel('Usuario').fill(usuario);
  await dialogo.getByLabel('Correo').fill(`otro.${usuario}@example.com`);
  await dialogo.getByLabel('Coordinador', { exact: true }).check();
  await dialogo.getByRole('button', { name: 'Guardar' }).click();
  await expect(page.getByTestId('error-usuario')).toContainText('Ya existe un usuario con ese nombre de usuario.');

  const otro = await browser.newContext();
  const p2 = await otro.newPage();
  await p2.goto(enlace!.replace('http://localhost:8080', ''));
  await p2.getByLabel('Contraseña nueva').fill('Auditor.Prueba.2026');
  await p2.getByLabel('Confirme la contraseña').fill('Auditor.Prueba.2026');
  await p2.getByRole('button', { name: 'Guardar contraseña' }).click();
  await expect(p2.getByTestId('clave-ok')).toBeVisible();
  await ingresar(p2, usuario, 'Auditor.Prueba.2026');
  await expect(p2.getByRole('link', { name: 'Mis visitas' })).toBeVisible();
  await expect(p2.getByRole('link', { name: 'Usuarios' })).toHaveCount(0);
  await otro.close();
});

test('logs: se ven en pantalla con usuario, fecha, operación y detalle; se descargan en Excel y se envían por correo', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  await page.getByRole('link', { name: 'Logs del sistema' }).click();
  await page.getByRole('button', { name: 'Buscar' }).click();
  const tabla = page.getByTestId('tabla-logs');
  await expect(tabla.getByRole('cell', { name: 'login', exact: true }).first()).toBeVisible();
  await expect(tabla.getByRole('cell', { name: /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/ }).first()).toBeVisible();
  const [descarga] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: 'Descargar Excel' }).click()]);
  expect(descarga.suggestedFilename()).toMatch(/^logs_auditoria_\d{8}_\d{4}\.xlsx$/);
  await page.getByRole('button', { name: 'Enviar por correo' }).click();
  await page.getByLabel('Correo de destino').fill('evaluador@example.com');
  const respuesta = page.waitForResponse((r) => r.url().includes('/api/logs/enviar'));
  await page.getByRole('button', { name: 'Enviar', exact: true }).click();
  const r = await respuesta;
  expect(r.status(), await r.text()).toBe(200);
  await expect(page.getByText('Reporte enviado a evaluador@example.com.')).toBeVisible();
});

for (const [nombre, ancho] of [['celular', 360], ['tableta', 768], ['escritorio', 1280]] as const) {
  test(`responsivo ${nombre} (${ancho} px): sin desplazamiento horizontal en ingreso, inicio y logs`, async ({ page }) => {
    await page.setViewportSize({ width: ancho, height: 800 });
    await page.goto('/');
    await sinDesplazamientoHorizontal(page);
    await ingresar(page, ...ADMIN);
    await sinDesplazamientoHorizontal(page);
    await page.goto('/logs');
    await expect(page.getByRole('heading', { name: 'Logs del sistema' })).toBeVisible();
    await sinDesplazamientoHorizontal(page);
  });
}
