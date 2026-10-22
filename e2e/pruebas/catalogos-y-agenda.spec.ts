import { expect, test, type Page } from '@playwright/test';
import { ADMIN, api, dentroDe, ingresar, ingresarConClaveInicial, salir, unico } from './apoyo';

// Anexo 5, prueba 4 (catálogos en vivo) y prueba 11 (visita con componentes elegidos, cada uno asignado a un auditor que ve solo lo suyo,
// estados, calendario y seguimiento en tiempo real).

test.afterEach(async ({ page }) => salir(page));

function letras(n: number): string {
  return Array.from({ length: n }, () => String.fromCharCode(65 + Math.floor(Math.random() * 26))).join('');
}

/** Elige una opción de un Select o MultiSelect de Mantine. */
async function elegir(page: Page, etiqueta: string, opcion: string | RegExp): Promise<void> {
  const dialogo = page.getByRole('dialog');
  await dialogo.getByRole('textbox', { name: etiqueta }).click();
  await page.getByRole('option', { name: opcion }).first().click();
}

test('prueba 4: el coordinador crea, modifica y elimina una fuerza y un tipo de documento en vivo', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  await page.getByRole('link', { name: 'Catálogos' }).click();
  const codigo = letras(5);
  await page.getByRole('button', { name: 'Nueva fuerza' }).click();
  const dialogo = page.getByRole('dialog');
  await dialogo.getByLabel('Código').fill(codigo);
  await dialogo.getByLabel('Nombre').fill('Fuerza de prueba e2e');
  await dialogo.getByRole('button', { name: 'Guardar' }).click();
  const fila = page.getByTestId('fila-fuerza-' + codigo);
  await expect(fila).toContainText('Fuerza de prueba e2e');

  await page.getByRole('button', { name: 'Editar la fuerza ' + codigo }).click();
  await dialogo.getByLabel('Nombre').fill('Fuerza editada e2e');
  await dialogo.getByRole('button', { name: 'Guardar' }).click();
  await expect(fila).toContainText('Fuerza editada e2e');

  page.once('dialog', (d) => d.accept());
  await page.getByRole('button', { name: 'Eliminar la fuerza ' + codigo }).click();
  await expect(fila).toHaveCount(0);

  // Validación en vivo: una fuerza en uso no se elimina
  page.once('dialog', (d) => d.accept());
  await page.getByRole('button', { name: 'Eliminar la fuerza EJC' }).click();
  await expect(page.getByText(/está en uso/)).toBeVisible();

  await page.getByRole('tab', { name: 'Tipos de documento' }).click();
  const doc = letras(3);
  await page.getByRole('button', { name: 'Nuevo valor' }).click();
  await dialogo.getByLabel('Código').fill(doc);
  await dialogo.getByLabel('Descripción').fill('Documento de prueba e2e');
  await dialogo.getByRole('button', { name: 'Guardar' }).click();
  const tabla = page.getByTestId('tabla-catalogo-tipo_documento');
  await expect(tabla).toContainText('Documento de prueba e2e');
  page.once('dialog', (d) => d.accept());
  await page.getByRole('button', { name: 'Eliminar ' + doc, exact: true }).click();
  await expect(tabla).not.toContainText('Documento de prueba e2e');
});

test('prueba 11: visita con componentes elegidos y C13 asignado al regente, que solo ve su parte e inicia la visita', async ({ page, browser }) => {
  await ingresar(page, ...ADMIN);
  // Datos de la prueba por la API: dos auditores con contraseña inicial y un punto propio
  const inicial = 'Inicial.Auditor.2026';
  const auditores: Record<string, { id: number; usuario: string; nombre: string }> = {};
  for (const [clave, rol] of [
    ['qf', 'quimico_farmaceutico'],
    ['reg', 'regente'],
  ] as const) {
    const usuario = unico(clave + 'e2e');
    const nombre = `Auditor ${clave.toUpperCase()} ${usuario}`;
    const r = await api<{ id: number }>(page, 'POST', '/api/usuarios', {
      nombre,
      usuario,
      correo: `${usuario}@example.com`,
      documento: '80000001',
      perfiles: [3],
      claveInicial: inicial,
    });
    expect(r.estado).toBe(201);
    expect((await api(page, 'PUT', `/api/maestros/auditores/${r.datos.id}`, { rol })).estado).toBe(200);
    auditores[clave] = { id: r.datos.id, usuario, nombre };
  }
  const nombrePunto = unico('ESM e2e ');
  const punto = await api<{ id: number }>(page, 'POST', '/api/maestros/puntos', {
    nombre: nombrePunto,
    unidad: 'ESM',
    fuerza: 'EJC',
    modalidad: 'Dispensación',
    municipio: '05001',
    correos: [],
  });
  expect(punto.estado).toBe(201);

  await page.getByRole('link', { name: 'Agenda de visitas' }).click();
  await page.getByRole('button', { name: 'Programar visita' }).click();
  const dialogo = page.getByRole('dialog');
  await expect(dialogo.getByTestId('form-visita')).toBeVisible();
  await elegir(page, 'Tipo de visita', 'Anual');
  await dialogo.getByRole('textbox', { name: 'Punto' }).fill(nombrePunto);
  await page.getByRole('option', { name: new RegExp(nombrePunto) }).click();
  await dialogo.locator('input[type="date"]').first().fill(dentroDe(20));
  // Hora militar (D4): campo propio en 24 h, sin a. m./p. m.; «1545» queda 15:45 al salir del campo
  const hora = dialogo.getByRole('textbox', { name: 'Hora', exact: true });
  await hora.fill('1545');
  await hora.blur();
  await expect(hora).toHaveValue('15:45');
  await dialogo.getByRole('textbox', { name: 'Auditor líder' }).fill(auditores.qf!.usuario);
  await page.getByRole('option', { name: new RegExp(auditores.qf!.usuario) }).click();
  await dialogo.getByRole('textbox', { name: 'Otros auditores' }).fill(auditores.reg!.usuario);
  await page.getByRole('option', { name: new RegExp(auditores.reg!.usuario) }).click();
  await dialogo.getByLabel('Alcance específico').fill('Almacenamiento, cadena de frío y dispensación.');
  // Componentes: la pantalla preselecciona los que aplican al ESM; el coordinador asigna C13 (Dispensación) al regente
  await expect(dialogo.getByTestId('componente-C13')).toBeChecked();
  // El coordinador deja solo tres componentes (prueba 11: elige los componentes al crear la visita)
  const casillas = dialogo.locator('[data-testid^="componente-"]');
  for (let i = 0; i < (await casillas.count()); i++) {
    const c = casillas.nth(i);
    const codigo = ((await c.getAttribute('data-testid')) ?? '').replace('componente-', '');
    const quiero = ['C05', 'C08', 'C13'].includes(codigo);
    if ((await c.isChecked()) !== quiero) await c.click({ force: true });
  }
  await dialogo.getByTestId('asignar-C13').click();
  await page.getByRole('option', { name: new RegExp(auditores.reg!.usuario) }).click();
  const notificar = dialogo.getByLabel('Enviar la notificación previa al guardar');
  if (await notificar.isChecked()) await notificar.uncheck({ force: true });
  await dialogo.getByRole('button', { name: 'Programar', exact: true }).click();

  const detalle = page.getByTestId('detalle-visita');
  await expect(detalle).toBeVisible();
  await expect(page.getByTestId('estado-visita')).toHaveText('Programada');
  const numero = (/VS-\d{8}-\d{4,}/.exec((await detalle.textContent()) ?? '') ?? [''])[0];
  expect(numero).not.toBe('');
  await expect(page.getByTestId('asignacion')).toContainText('C13');
  await expect(detalle).toContainText('15:45');
  // La reprogramación usa el mismo campo: trae la hora en 24 h y rechaza una hora fuera de rango
  await page.getByRole('button', { name: 'Reprogramar' }).click();
  const reprogramar = page.getByRole('dialog');
  const horaNueva = reprogramar.getByRole('textbox', { name: 'Hora', exact: true });
  await expect(horaNueva).toHaveValue('15:45');
  await horaNueva.fill('25:00');
  await horaNueva.blur();
  await expect(reprogramar.getByText('Escriba la hora en formato de 24 horas (HH:MM), de 00:00 a 23:59.')).toBeVisible();
  await reprogramar.getByRole('button', { name: 'Volver' }).click();
  await expect(reprogramar).toHaveCount(0);

  // El regente entra (cambia su contraseña inicial) y en Mis visitas ve solo su parte
  const otro = await browser.newContext();
  const p2 = await otro.newPage();
  await ingresarConClaveInicial(p2, auditores.reg!.usuario, inicial, 'Regente.Propia.2026');
  await p2.getByRole('link', { name: 'Mis visitas' }).click();
  const tarjeta = p2.getByTestId('fila-visita-' + numero);
  await expect(tarjeta).toBeVisible();
  // Solo sus partes: C13 (asignada por el coordinador) y lo que le tocó en el reparto, nunca las cuatro (C05, C08, C13 y OBL)
  const partes = ((await tarjeta.getByTestId('mis-partes').textContent()) ?? '').replace('Mis partes: ', '').split(', ');
  expect(partes).toContain('C13');
  expect(partes.length).toBeLessThan(4);
  await tarjeta.click();
  await expect(p2.getByTestId('mis-partes')).toContainText('C13');
  for (const ajena of ['C05', 'C08', 'C13', 'OBL'].filter((x) => !partes.includes(x))) {
    await expect(p2.getByTestId('mis-partes')).not.toContainText(ajena);
  }
  await p2.getByRole('button', { name: 'Iniciar visita' }).click();
  await expect(p2.getByTestId('estado-visita')).toHaveText('En ejecución');
  await salir(p2);
  await otro.close();

  // Seguimiento en tiempo real del coordinador
  await page.goto('/seguimiento');
  await expect(page.getByTestId('tabla-seguimiento')).toContainText(numero);
  await expect(page.getByTestId('fila-visita-' + numero)).toContainText('En ejecución');
});
