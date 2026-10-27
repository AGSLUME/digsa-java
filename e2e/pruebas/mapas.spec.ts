import { expect, test, type Locator, type Page } from '@playwright/test';
import { ADMIN, api, dentroDe, ingresar, ingresarConClaveInicial, salir, sinDesplazamientoHorizontal, unico } from './apoyo';

// Mapas georreferenciados (Anexo 4 §9 y §11): mapa de visitas por departamento (un punto por visita con el color de su estado) y
// mapa de control con la cobertura de los puntos; mismos perfiles y alcance que el dashboard.

test.afterEach(async ({ page }) => salir(page));

const INICIAL = 'Inicial.Mapas.2026';

/** Color que de verdad se pinta (los rellenos van por CSS con var() y color-mix()). */
const relleno = (l: Locator) => l.evaluate((e) => getComputedStyle(e).fill);
const ancho = (l: Locator) => l.evaluate((e) => e.getBoundingClientRect().width);

async function crearUsuario(page: Page, prefijo: string, perfil: number, nombre: string): Promise<{ id: number; usuario: string }> {
  const usuario = unico(prefijo);
  const r = await api<{ id: number }>(page, 'POST', '/api/usuarios', {
    nombre: `${nombre} ${usuario}`,
    usuario,
    correo: `${usuario}@example.com`,
    documento: '80000021',
    perfiles: [perfil],
    claveInicial: INICIAL,
  });
  expect(r.estado, JSON.stringify(r.datos)).toBe(201);
  return { id: r.datos.id, usuario };
}

test('coordinador: mapa de visitas por departamento y mapa de control con la cobertura del punto', async ({ page }) => {
  test.setTimeout(180_000);
  await ingresar(page, ...ADMIN);
  const coord = await crearUsuario(page, 'coordmapa', 2, 'Coordinadora mapas');
  const lider = await crearUsuario(page, 'lidermapa', 3, 'Auditor mapas');
  const nombre = unico('ESM mapa ');
  const punto = await api<{ id: number }>(page, 'POST', '/api/maestros/puntos', {
    nombre,
    unidad: 'ESM',
    fuerza: 'EJC',
    modalidad: 'Dispensación',
    municipio: '05001',
    correos: ['contacto.mapa@example.com'],
  });
  expect(punto.estado, JSON.stringify(punto.datos)).toBe(201);
  const v = await api<{ id: number }>(page, 'POST', '/api/agenda', {
    tipo: 'anual',
    puntoId: punto.datos.id,
    fecha: dentroDe(0),
    liderId: lider.id,
    otros: [],
    alcance: 'Mapa de visitas.',
    componentes: ['C08'],
  });
  expect(v.estado, JSON.stringify(v.datos)).toBe(201);
  await salir(page);

  await ingresarConClaveInicial(page, coord.usuario, INICIAL, 'Coordinadora.Mapas.2026');
  await page.getByRole('link', { name: 'Mapas de visitas' }).click();
  await expect(page).toHaveURL(/\/mapas$/);
  await expect(page.getByRole('heading', { name: 'Mapas de visitas' })).toBeVisible();

  // Mapa de visitas: la visita es un punto azul (programada) sobre Antioquia
  const mapa = page.getByTestId('mapa-colombia');
  await expect(mapa.locator('path[data-departamento]')).toHaveCount(33);
  const marca = mapa.locator(`circle[data-marca="${v.datos.id}"]`);
  await expect(marca).toHaveCount(1);
  expect(await relleno(marca)).toBe('rgb(29, 78, 216)'); // --lume-info-text
  expect(Number(await page.getByTestId('total-programada').textContent())).toBeGreaterThan(0);

  // El radio de las marcas va por CSS (--r): al acercar 2,56 veces la marca crece menos que el mapa
  const antes = await ancho(marca);
  await page.getByRole('button', { name: 'Acercar' }).click();
  await page.getByRole('button', { name: 'Acercar' }).click();
  await expect(mapa).toHaveAttribute('data-zoom', '2.56');
  await expect.poll(async () => (await ancho(marca)) / antes).toBeLessThan(2);
  await page.getByRole('button', { name: 'Restablecer la vista' }).click();
  await expect(mapa).toHaveAttribute('data-zoom', '1.00');

  // Al elegir Antioquia en la tabla sale el detalle con el punto
  await page.getByTestId('tabla-departamentos').locator('tr[data-departamento="05"]').click();
  await expect(page.getByTestId('detalle-departamento')).toContainText(nombre);
  await expect(mapa.locator('path[data-departamento="05"]')).toHaveAttribute('aria-pressed', 'true');

  // Ocultar «Programada» quita la marca
  await page.getByRole('checkbox', { name: /Programada/ }).uncheck();
  await expect(marca).toHaveCount(0);

  // Mapa de control: el punto aparece con visita por ejecutar
  await page.getByRole('tab', { name: /Mapa de control/ }).click();
  const control = page.getByTestId('mapa-colombia');
  const marcaPunto = control.locator(`circle[data-marca="${punto.datos.id}"]`);
  await expect(marcaPunto).toHaveCount(1);
  expect(await relleno(marcaPunto)).toBe('rgb(29, 78, 216)');
  await expect(page.getByTestId('cobertura-porcentaje')).toContainText('Cobertura del periodo');
  await control.locator('path[data-departamento="05"]').click();
  await expect(page.getByTestId('detalle-departamento')).toContainText(nombre);
  await expect(page.getByTestId('detalle-departamento')).toContainText('Con visita por ejecutar');
});

test('mapa político: capitales, zoom con botones y teclado, departamento elegido acercado y coropleta', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  await page.goto('/mapas');
  const mapa = page.getByTestId('mapa-colombia');
  await expect(mapa.locator('path[data-departamento]')).toHaveCount(33);
  // Capitales: Medellín y Bogotá con punto; Agua de Dios (25001) no es capital
  await expect(mapa.locator('[data-capital="05001"] circle')).toHaveCount(1);
  await expect(mapa.locator('[data-capital="11001"]')).toHaveCount(1);
  await expect(mapa.locator('[data-capital="25001"]')).toHaveCount(0);
  await expect(mapa.getByText('VENEZUELA')).toHaveCount(1);
  await expect(page.getByTestId('escala-mapa')).toContainText('km');

  // Botones de zoom
  await expect(mapa).toHaveAttribute('data-zoom', '1.00');
  await page.getByRole('button', { name: 'Acercar' }).click();
  await expect(mapa).toHaveAttribute('data-zoom', '1.60');
  await page.getByRole('button', { name: 'Restablecer la vista' }).click();
  await expect(mapa).toHaveAttribute('data-zoom', '1.00');

  // Teclado: Enter sobre Antioquia la elige y la acerca; otra vez Enter la suelta y vuelve al país
  const antioquia = mapa.locator('path[data-departamento="05"]');
  await antioquia.focus();
  await page.keyboard.press('Enter');
  await expect(antioquia).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByTestId('detalle-departamento')).toContainText('Antioquia');
  expect(Number(await mapa.getAttribute('data-zoom'))).toBeGreaterThan(1.5);
  await page.keyboard.press('Enter');
  await expect(antioquia).toHaveAttribute('aria-pressed', 'false');
  await expect(mapa).toHaveAttribute('data-zoom', '1.00');
  // Un solo departamento en el orden de tabulación; las flechas pasan al siguiente (código DANE)
  await expect(mapa.locator('path[data-departamento][tabindex="0"]')).toHaveCount(1);
  await page.keyboard.press('ArrowRight');
  await expect(mapa.locator('path[data-departamento="08"]')).toBeFocused();

  // Coropleta: el relleno pastel cambia por la intensidad y aparece su leyenda
  const pastel = await relleno(antioquia);
  // uno de los 5 fondos LUME
  expect(['rgb(220, 239, 225)', 'rgb(245, 232, 211)', 'rgb(219, 234, 254)', 'rgb(245, 222, 222)', 'rgb(226, 232, 240)']).toContain(pastel);
  await page.getByText('Ver como coropleta').click();
  await expect(page.getByRole('switch', { name: 'Ver como coropleta' })).toBeChecked();
  await expect(page.getByTestId('leyenda-coropleta')).toBeVisible();
  await expect.poll(() => relleno(antioquia)).not.toBe(pastel);
});

test('celular (360 px): /mapas sin desplazamiento horizontal', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/mapas');
  await expect(page.getByTestId('mapa-colombia')).toBeVisible();
  await sinDesplazamientoHorizontal(page);
  await page.getByRole('tab', { name: /Mapa de control/ }).click();
  await expect(page.getByTestId('cobertura-porcentaje')).toBeVisible();
  await sinDesplazamientoHorizontal(page);
});

test('OPL: no ve el menú de mapas y recibe «Acceso restringido.»', async ({ page }) => {
  test.setTimeout(150_000);
  await ingresar(page, ...ADMIN);
  const opl = await crearUsuario(page, 'oplmapa', 8, 'OPL nacional');
  await salir(page);

  await ingresarConClaveInicial(page, opl.usuario, INICIAL, 'Opl.Mapas.Propia.2026');
  await expect(page.getByRole('link', { name: 'Respuesta a hallazgos' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Mapas de visitas' })).toHaveCount(0);
  await page.goto('/mapas');
  await expect(page.getByText('Acceso restringido.')).toBeVisible();
  expect((await api(page, 'GET', '/api/mapas/visitas')).estado).toBe(403);
});
