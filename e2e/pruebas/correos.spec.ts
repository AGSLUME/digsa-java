import { expect, test } from '@playwright/test';
import { ingresarComoAdminPropio, salir } from './apoyo';

test.afterEach(async ({ page }) => salir(page));

// Módulo Correos · C1: bitácora de envíos (pedido del usuario del 2026-10-29). El ingreso del administrador deja sus códigos MFA en la
// bitácora como envíos directos: se ven con el destinatario enmascarado, la vista previa oculta el código y no se reintentan.

test('el administrador ve su código MFA en la bitácora de envíos, oculto y sin reintento, y descarga la bitácora', async ({ page }) => {
  const usuario = await ingresarComoAdminPropio(page, 'corr');
  const correo = `${usuario}@example.com`;
  await page.getByRole('link', { name: 'Correos', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Correos' })).toBeVisible();

  await page.getByRole('textbox', { name: 'Destinatario' }).fill('incompleto');
  await page.getByRole('button', { name: 'Buscar' }).click();
  await expect(page.getByTestId('error-filtros-correos')).toContainText('Destinatario');
  await expect(page.getByText('Escriba el correo completo del destinatario (la búsqueda es exacta).')).toBeVisible();

  await page.getByRole('textbox', { name: 'Destinatario' }).fill(correo);
  await page.getByRole('textbox', { name: 'Tipo de correo' }).click();
  await page.getByRole('option', { name: 'Código de verificación (MFA)', exact: true }).click();
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: 'Buscar' }).click();
  const tabla = page.getByTestId('tabla-correos');
  const filas = tabla.getByRole('row').filter({ hasText: 'Código de verificación (MFA)' });
  await expect(filas.first()).toBeVisible();
  await expect(filas.first()).toContainText('Directo');
  await expect(filas.first()).not.toContainText(usuario);

  await filas.first().getByRole('button', { name: /^Ver el envío/ }).click();
  const dialogo = page.getByRole('dialog');
  await expect(dialogo.getByTestId('para-completo')).toContainText(correo);
  await expect(dialogo.getByTestId('aviso-enmascarado')).toBeVisible();
  await expect(dialogo.frameLocator('iframe[title="Vista previa del correo"]').getByText('••••••')).toBeVisible();
  const reintentar = dialogo.getByRole('button', { name: 'Reintentar envío' });
  await expect(reintentar).toBeDisabled();
  await reintentar.click({ force: true });
  const motivo = dialogo.locator('[id^="motivo-reintento-"]');
  await expect(motivo).toContainText('un solo uso');
  await expect(motivo).toBeFocused();
  await expect(dialogo.getByLabel('Motivo del reintento')).toHaveCount(0);
  await page.keyboard.press('Escape');

  const descarga = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Descargar Excel' }).click();
  expect((await descarga).suggestedFilename()).toMatch(/\.xlsx$/);
});

// Módulo Correos · C2: plantillas. El MFA se ve pero no se edita; una editable se cambia con vista previa y motivo, y se restaura.
test('el administrador ve las plantillas, no edita el MFA y edita y restaura el recordatorio de seguimiento con motivo', async ({ page }) => {
  await ingresarComoAdminPropio(page, 'corp');
  await page.getByRole('link', { name: 'Correos', exact: true }).click();
  await page.getByRole('tab', { name: 'Plantillas' }).click();
  const tabla = page.getByTestId('tabla-plantillas');
  await expect(tabla.getByTestId('plantilla-mfa_codigo')).toContainText('Solo lectura');

  await page.getByRole('button', { name: 'Abrir la plantilla Código de verificación (MFA)' }).click();
  let dialogo = page.getByRole('dialog');
  await expect(dialogo.getByTestId('aviso-no-editable')).toContainText('un solo uso');
  await expect(dialogo.getByRole('textbox', { name: 'Texto del correo' })).toHaveAttribute('readonly', '');
  await expect(dialogo.getByRole('button', { name: 'Guardar cambios' })).toHaveCount(0);
  await dialogo.getByRole('button', { name: 'Vista previa' }).click();
  await expect(dialogo.frameLocator('iframe[title="Vista previa de la plantilla"]').getByText('••••••')).toBeVisible();
  await page.keyboard.press('Escape');

  await page.getByRole('button', { name: 'Abrir la plantilla Recordatorio de visita de seguimiento' }).click();
  dialogo = page.getByRole('dialog');
  const cuerpo = dialogo.getByRole('textbox', { name: 'Texto del correo' });
  await expect(cuerpo).toHaveValue(/\{\{acciones\}\}/);
  await expect(dialogo.getByRole('button', { name: 'Restaurar original' })).toBeDisabled();
  await expect(dialogo.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled();

  // Quitar una variable protegida: la vista previa dice por qué no se puede guardar
  const original = await cuerpo.inputValue();
  await cuerpo.fill(original.replace('{{acciones}}', ''));
  await dialogo.getByRole('button', { name: 'Vista previa' }).click();
  await expect(dialogo.getByTestId('vista-previa-plantilla')).toContainText('{{acciones}}');

  await cuerpo.fill(original.replace('Le recordamos la', 'Recuerde la'));
  await dialogo.getByRole('button', { name: 'Vista previa' }).click();
  await expect(dialogo.frameLocator('iframe[title="Vista previa de la plantilla"]').getByText(/Recuerde la primera visita de seguimiento/)).toBeVisible();
  await dialogo.getByRole('button', { name: 'Guardar cambios' }).click();
  await dialogo.getByRole('textbox', { name: 'Motivo' }).fill('corto');
  await dialogo.getByRole('button', { name: 'Guardar', exact: true }).click();
  await expect(page.getByTestId('error-plantilla')).toContainText('Motivo');
  await dialogo.getByRole('textbox', { name: 'Motivo' }).fill('Texto más directo para el auditor');
  await dialogo.getByRole('button', { name: 'Guardar', exact: true }).click();
  await expect(page.getByText('Plantilla guardada')).toBeVisible();
  await expect(dialogo.getByTestId('historial-plantilla')).toContainText('Texto más directo para el auditor');
  await expect(tabla.getByTestId('plantilla-recordatorio_seguimiento')).toContainText('Editada');

  await dialogo.getByRole('button', { name: 'Restaurar original' }).click();
  await dialogo.getByRole('textbox', { name: 'Motivo' }).fill('Volver al texto de fábrica');
  await dialogo.getByRole('button', { name: 'Restaurar', exact: true }).click();
  await expect(page.getByText('Plantilla restaurada')).toBeVisible();
  await expect(cuerpo).toHaveValue(original);
  await expect(tabla.getByTestId('plantilla-recordatorio_seguimiento')).toContainText('Original');
});

// Módulo Correos · C3: destinatarios. Reglas por tipo de correo con su pantalla, dominios de los reportes (comité) y edición con
// motivo del buzón de la DIGSA (se restaura al final: otras pruebas lo usan).
test('el administrador ve a quién le llega cada correo y edita con motivo el buzón de la DIGSA', async ({ page }) => {
  await ingresarComoAdminPropio(page, 'cord');
  await page.getByRole('link', { name: 'Correos', exact: true }).click();
  await page.getByRole('tab', { name: 'Destinatarios' }).click();
  await expect(page.getByTestId('tabla-reglas-destinatarios')).toContainText('Todos los coordinadores activos.');
  await expect(page.getByTestId('dominios-reportes')).toContainText('lo indica quien envía · dominios permitidos:');
  await expect(page.getByTestId('tabla-alertas-destinatarios')).toBeVisible();

  const buzon = page.getByTestId('buzon-digsa');
  const original = (await buzon.textContent()) ?? '';
  await page.getByRole('button', { name: 'Editar el buzón de la DIGSA' }).click();
  let dialogo = page.getByRole('dialog', { name: 'Buzón de la DIGSA' });
  const correos = dialogo.getByRole('textbox', { name: 'Correos' });
  await correos.fill('no-es-correo');
  await dialogo.getByRole('textbox', { name: 'Motivo' }).fill('Prueba del buzón de la DIGSA');
  await dialogo.getByRole('button', { name: 'Guardar' }).click();
  await expect(page.getByTestId('error-buzones')).toContainText('Correos');

  await correos.fill('digsa.e2e@example.com\nsegundo.e2e@example.com');
  await dialogo.getByRole('button', { name: 'Guardar' }).click();
  await expect(page.getByText('Buzón de la DIGSA guardado: 2 correo(s).')).toBeVisible();
  await expect(buzon).toHaveText('digsa.e2e@example.com, segundo.e2e@example.com');

  // Vuelve exactamente al valor de antes (también vacío: otras pruebas dependen de que falte el buzón)
  await page.getByRole('button', { name: 'Editar el buzón de la DIGSA' }).click();
  dialogo = page.getByRole('dialog', { name: 'Buzón de la DIGSA' });
  await dialogo.getByRole('textbox', { name: 'Correos' }).fill(original.includes('@') ? original : '');
  await dialogo.getByRole('textbox', { name: 'Motivo' }).fill('Restaurar el buzón de la prueba');
  await dialogo.getByRole('button', { name: 'Guardar' }).click();
  await expect(dialogo).toBeHidden();
});
