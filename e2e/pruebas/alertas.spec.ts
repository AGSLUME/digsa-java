import { expect, test } from '@playwright/test';
import { ADMIN, api, ingresar, ingresarConClaveInicial, salir, sinDesplazamientoHorizontal, unico } from './apoyo';

// Alertas (docs/especificaciones/exportacion-alertas; GMI P75 y P77 b): el administrador edita el catálogo en la pantalla Alertas (/alertas) y
// ve los eventos con la latencia de la bandeja; la coordinación ve los eventos pero no el catálogo; el teléfono de Usuarios debe ser
// un móvil colombiano porque recibe el SMS de las alertas críticas.

test.afterEach(async ({ page }) => salir(page));

const INICIAL = 'Inicial.Alertas.2026';
/** Tipo operativo (solo correo): desactivarlo un momento no afecta los avisos críticos de otras pruebas. */
const TIPO = 'plan_opl_vencido';

test('el administrador desactiva un tipo del catálogo, lo restaura, ve la validación del servidor y los eventos', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  await page.getByRole('link', { name: 'Alertas', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Alertas' })).toBeVisible();
  await page.getByRole('tab', { name: 'Catálogo', exact: true }).click();

  const fila = page.getByTestId('fila-alerta-' + TIPO);
  await expect(fila).toBeVisible();
  await expect(page.getByTestId('fila-alerta-tarea_diaria_fallida')).toContainText('Crítica');
  await expect(fila.getByTestId('estado-alerta')).toHaveText('Activa');
  const dialogo = page.getByRole('dialog');

  // El SMS no se ofrece en una alerta operativa
  await fila.getByRole('button', { name: /^Editar la alerta/ }).click();
  await expect(dialogo.getByLabel('Enviar también por SMS')).toBeDisabled();

  // Validación del servidor: nombre demasiado corto
  const nombre = dialogo.getByLabel('Nombre');
  const original = await nombre.inputValue();
  await nombre.fill('ab');
  await dialogo.getByRole('button', { name: 'Guardar' }).click();
  await expect(dialogo.getByTestId('error-alerta')).toContainText('de 3 a 80 caracteres');
  await nombre.fill(original);

  // Desactivar
  // El input del Switch de Mantine está oculto y fuera de la vista: se pulsa su etiqueta, como haría el usuario
  await dialogo.getByText('Activa', { exact: true }).click();
  await expect(dialogo.getByLabel('Activa')).not.toBeChecked();
  await dialogo.getByRole('button', { name: 'Guardar' }).click();
  await expect(page.getByText(/Alerta actualizada/)).toBeVisible();
  await expect(fila.getByTestId('estado-alerta')).toHaveText('Inactiva');

  // Restaurar
  await fila.getByRole('button', { name: /^Editar la alerta/ }).click();
  await dialogo.getByText('Activa', { exact: true }).click();
  await expect(dialogo.getByLabel('Activa')).toBeChecked();
  await dialogo.getByRole('button', { name: 'Guardar' }).click();
  await expect(fila.getByTestId('estado-alerta')).toHaveText('Activa');

  // Eventos y bandeja (p50/p95 de las últimas 24 h)
  await page.getByRole('tab', { name: 'Eventos', exact: true }).click();
  await expect(page.getByTestId('bandeja-alertas')).toContainText('Latencia p95');
  await expect(page.getByTestId('indicador-p50')).toHaveText(/—|\d/);
  // El radio del SegmentedControl también está oculto: se pulsa su etiqueta
  await page.getByLabel('Eventos que se muestran').getByText('Recientes', { exact: true }).click();
  await expect(page.getByRole('radio', { name: 'Recientes' })).toBeChecked();
  await expect(page.getByTestId('tabla-eventos').or(page.getByTestId('sin-eventos'))).toBeVisible();

  // A 360 px: tarjetas, sin desplazamiento horizontal
  await page.setViewportSize({ width: 360, height: 740 });
  await sinDesplazamientoHorizontal(page);
});

test('la coordinación ve los eventos de alerta pero no el catálogo', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  const usuario = unico('coordalert');
  const creado = await api<{ id: number }>(page, 'POST', '/api/usuarios', {
    nombre: `Coordinador alertas ${usuario}`,
    usuario,
    correo: `${usuario}@example.com`,
    documento: '80000004',
    perfiles: [2],
    claveInicial: INICIAL,
  });
  expect(creado.estado, JSON.stringify(creado.datos)).toBe(201);
  await salir(page);

  await ingresarConClaveInicial(page, usuario, INICIAL, 'Coordinador.Alertas.2026');
  await page.getByRole('link', { name: 'Alertas', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Alertas' })).toBeVisible();
  await expect(page.getByRole('tab', { name: 'Catálogo', exact: true })).toHaveCount(0);
  await expect(page.getByTestId('tabla-alertas')).toHaveCount(0);
  await expect(page.getByTestId('bandeja-alertas')).toBeVisible();
  expect((await api(page, 'GET', '/api/alertas/catalogo')).estado).toBe(403);
  expect((await api(page, 'GET', '/api/alertas/eventos?abiertas=true')).estado).toBe(200);
});

test('un teléfono que no es móvil colombiano muestra el error del servidor', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  await page.getByRole('link', { name: 'Usuarios' }).click();
  await page.getByRole('button', { name: 'Nuevo usuario' }).click();
  const usuario = unico('telefono');
  const dialogo = page.getByRole('dialog');
  await dialogo.getByLabel('Nombre completo').fill('Usuario Teléfono de Prueba');
  await dialogo.getByLabel('Usuario').fill(usuario);
  await dialogo.getByLabel('Correo').fill(`${usuario}@example.com`);
  await dialogo.getByLabel('Teléfono móvil').fill('601 000 0000');
  await dialogo.getByLabel('Auditor', { exact: true }).check();
  await expect(dialogo.getByText(/móvil colombiano: \+57 y 10 dígitos/).first()).toBeVisible();
  await dialogo.getByRole('button', { name: 'Guardar' }).click();
  await expect(page.getByTestId('error-usuario')).toContainText('alertas críticas');
  await expect(page.getByTestId('usuario-' + usuario)).toHaveCount(0);
});
