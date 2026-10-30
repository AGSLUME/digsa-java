import { expect, test } from '@playwright/test';
import { ADMIN, salir } from './apoyo';

// Módulo Correos · C4: con el perfil «mailpit» los correos salen por SMTP a Mailpit (docs/guia_mailpit.md). Solo corre con Mailpit
// arriba en este equipo y DIGSA_E2E_CORREO=mailpit (el servidor de las pruebas arranca con -Ddigsa.local.correo=mailpit); con correo
// real la pantalla ya no muestra el código, así que se lee de Mailpit. Correr solo este archivo en ese modo:
//   DIGSA_E2E_CORREO=mailpit npx playwright test pruebas/mailpit.spec.ts --project=chromium
const MAILPIT = process.env.DIGSA_E2E_MAILPIT_URL ?? 'http://localhost:8025';

test.skip(process.env.DIGSA_E2E_CORREO !== 'mailpit', 'Requiere Mailpit corriendo y DIGSA_E2E_CORREO=mailpit (docs/guia_mailpit.md).');
test.afterEach(async ({ page }) => salir(page));

test('con el perfil mailpit el código de ingreso llega a Mailpit, no aparece en pantalla y queda oculto en la bitácora', async ({ page, request }) => {
  const [usuario, clave] = ADMIN;
  await request.delete(`${MAILPIT}/api/v1/messages`);
  await page.goto('/');
  await page.getByLabel('Usuario').fill(usuario);
  await page.getByLabel('Contraseña').fill(clave);
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click();
  await expect(page.locator('[data-testid="codigo"] input').first()).toBeVisible();
  await expect(page.getByText(/local: \d{6}/)).toHaveCount(0);

  let id = '';
  await expect
    .poll(async () => {
      const r = await (await request.get(`${MAILPIT}/api/v1/messages`)).json();
      const m = (r.messages ?? []).find((x: { Subject: string }) => x.Subject === 'Código de verificación · Suite DIGSA');
      id = m?.ID ?? '';
      return id;
    })
    .not.toBe('');
  const mensaje = await (await request.get(`${MAILPIT}/api/v1/message/${id}`)).json();
  const codigo = /(\d{6})/.exec(String(mensaje.Text ?? mensaje.HTML))?.[1];
  expect(codigo).toBeTruthy();
  const casillas = page.locator('[data-testid="codigo"] input');
  for (let i = 0; i < 6; i++) await casillas.nth(i).fill(codigo![i]!);
  await page.getByRole('button', { name: 'Verificar e ingresar' }).click();
  await expect(page.getByRole('button', { name: 'Salir', exact: true })).toBeVisible();

  // La bitácora de envíos tiene el mismo correo como enviado, con el código oculto
  await page.getByRole('link', { name: 'Correos', exact: true }).click();
  const fila = page.getByTestId('tabla-correos').getByRole('row').filter({ hasText: 'Código de verificación (MFA)' }).first();
  await expect(fila).toContainText('Enviado');
  await fila.getByRole('button', { name: /^Ver el envío/ }).click();
  await expect(page.getByRole('dialog').frameLocator('iframe[title="Vista previa del correo"]').getByText('••••••')).toBeVisible();
  await page.keyboard.press('Escape');
});
