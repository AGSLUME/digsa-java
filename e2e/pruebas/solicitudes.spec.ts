import { expect, test } from '@playwright/test';
import { ADMIN, api, dentroDe, ingresar, ingresarConClaveInicial, salir, unico } from './apoyo';

// Fase 5: solicitud de visita a necesidad (Sanidad con su escrito) y el coordinador la agenda desde la bandeja.

test.afterEach(async ({ page }) => salir(page));

const PDF = Buffer.from('%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF');

test('Sanidad solicita una visita a necesidad con su escrito; el coordinador la agenda y la solicitud queda programada', async ({ page, browser }) => {
  test.setTimeout(180_000);
  await ingresar(page, ...ADMIN);
  const inicial = 'Inicial.Sanidad.2026';
  const usuario = unico('sanidade2e');
  const r = await api<{ id: number }>(page, 'POST', '/api/usuarios', {
    nombre: `Sanidad EJC ${usuario}`, usuario, correo: `${usuario}@example.com`, perfiles: [6], alcanceFuerza: 'EJC', claveInicial: inicial,
  });
  expect(r.estado).toBe(201);
  const lider = unico('qfsol');
  const a = await api<{ id: number }>(page, 'POST', '/api/usuarios', {
    nombre: `Auditor ${lider}`, usuario: lider, correo: `${lider}@example.com`, documento: '80000003', perfiles: [3], claveInicial: inicial,
  });
  await api(page, 'PUT', `/api/maestros/auditores/${a.datos.id}`, { rol: 'quimico_farmaceutico' });
  const nombrePunto = unico('ESM solicitud ');
  await api(page, 'POST', '/api/maestros/puntos', { nombre: nombrePunto, unidad: 'ESM', fuerza: 'EJC', modalidad: 'Dispensación', municipio: '05001' });

  const ctx = await browser.newContext();
  const s = await ctx.newPage();
  await ingresarConClaveInicial(s, usuario, inicial, 'Sanidad.Propia.2026');

  await s.getByRole('link', { name: 'Solicitudes de visita' }).click();

  await s.getByRole('button', { name: 'Nueva solicitud' }).click();
  const d = s.getByRole('dialog');

  await d.getByRole('textbox', { name: 'Punto' }).fill(nombrePunto);
  await s.getByRole('option', { name: new RegExp(nombrePunto) }).click();

  await d.getByLabel('Motivo de la solicitud').fill('Quejas de usuarios por medicamentos pendientes de entrega.');

  await d.locator('input[type="file"]').setInputFiles({ name: 'escrito.pdf', mimeType: 'application/pdf', buffer: PDF });

  await d.getByRole('button', { name: 'Registrar solicitud' }).click();
  await expect(s.getByText(/Solicitud registrada/)).toBeVisible();
  await expect(s.getByTestId('tabla-solicitudes')).toContainText(nombrePunto);
  await salir(s);
  await ctx.close();

  // El coordinador (administrador) la agenda desde la bandeja
  await salir(page);
  await ingresar(page, ...ADMIN);

  await page.goto('/solicitudes');
  const fila = page.getByTestId('tabla-solicitudes').getByRole('row', { name: new RegExp(nombrePunto) });

  await fila.getByRole('button', { name: 'Agendar' }).click();
  const f = page.getByRole('dialog');
  await expect(f.getByTestId('form-visita')).toBeVisible();

  await f.locator('input[type="date"]').first().fill(dentroDe(2));
  await f.getByRole('textbox', { name: 'Auditor líder' }).fill(lider);
  await page.getByRole('option', { name: new RegExp(lider) }).click();
  await f.getByLabel('Alcance específico').fill('Atender la solicitud de Sanidad EJC.');

  await f.getByRole('button', { name: 'Programar', exact: true }).click();
  await expect(f.getByTestId('error-visita')).toHaveCount(0); // si el servidor rechaza, el mensaje queda en el formulario
  await expect(page.getByTestId('detalle-visita')).toBeVisible();
  await expect(page.getByTestId('detalle-visita')).toContainText('A necesidad');
  await expect(page.getByTestId('detalle-visita')).toContainText('Solicitud agendada');
  await page.goto('/solicitudes');
  await page.getByRole('textbox', { name: 'Estado' }).click();
  await page.getByRole('option', { name: 'Programadas' }).click();
  await expect(page.getByTestId('tabla-solicitudes')).toContainText(nombrePunto);
});
