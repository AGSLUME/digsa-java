import { expect, test, type Page } from '@playwright/test';
import { ADMIN, api, dentroDe, ingresar, ingresarConClaveInicial, salir, unico } from './apoyo';

// Fase 9 «Riesgo y tablero» (docs/especificaciones/riesgo-tablero): marca de intervención inmediata del OPL en el checklist, dashboard de
// riesgo (tabla, filtro, pesos y umbrales, incidencias de la DIGSA), dashboard de visitas (indicadores, filtros, descarga), celular sin
// desplazamiento horizontal y un perfil OPL sin acceso.

test.afterEach(async ({ page }) => salir(page));

const INICIAL = 'Inicial.Riesgo.2026';
const PESOS_BASE = { incumplimiento: 30, recurrencia: 20, inmediata: 15, planes: 20, incidencias: 15 };
const UMBRALES_BASE = { alto: 60, medio: 30 };

interface Creado {
  id: number;
  usuario: string;
}

async function crearUsuario(page: Page, prefijo: string, perfil: number, nombre: string): Promise<Creado> {
  const usuario = unico(prefijo);
  const r = await api<{ id: number }>(page, 'POST', '/api/usuarios', {
    nombre: `${nombre} ${usuario}`,
    usuario,
    correo: `${usuario}@example.com`,
    documento: '80000019',
    perfiles: [perfil],
    claveInicial: INICIAL,
  });
  expect(r.estado, JSON.stringify(r.datos)).toBe(201);
  return { id: r.datos.id, usuario };
}

async function crearEsm(page: Page, prefijo: string, fuerza: string): Promise<{ id: number; nombre: string }> {
  const nombre = unico(prefijo);
  const r = await api<{ id: number }>(page, 'POST', '/api/maestros/puntos', {
    nombre,
    unidad: 'ESM',
    fuerza,
    modalidad: 'Dispensación',
    municipio: '05001',
    correos: ['contacto.riesgo@example.com'],
  });
  expect(r.estado, JSON.stringify(r.datos)).toBe(201);
  return { id: r.datos.id, nombre };
}

/** El administrador crea un coordinador; devuelve su usuario (la página queda sin sesión). */
async function coordinadorNuevo(page: Page, prefijo: string): Promise<Creado> {
  await ingresar(page, ...ADMIN);
  const coord = await crearUsuario(page, prefijo, 2, 'Coordinadora riesgo');
  await salir(page);
  return coord;
}

/** Sin desplazamiento horizontal de la página (con 1 px de tolerancia por el redondeo). */
async function sinScrollHorizontal(page: Page): Promise<void> {
  const r = await page.evaluate(() => ({ ancho: document.documentElement.scrollWidth, visible: document.documentElement.clientWidth }));
  expect(r.ancho, `scrollWidth ${r.ancho} > clientWidth ${r.visible}`).toBeLessThanOrEqual(r.visible + 1);
}

test('auditor: la marca «Requiere intervención inmediata del OPL» se guarda en No cumple y desaparece en Cumple', async ({ page, browser }) => {
  test.setTimeout(180_000);
  await ingresar(page, ...ADMIN);
  const lider = await crearUsuario(page, 'liderries', 3, 'Auditor líder');
  expect((await api(page, 'PUT', `/api/maestros/auditores/${lider.id}`, { rol: 'quimico_farmaceutico' })).estado).toBe(200);
  const punto = await crearEsm(page, 'ESM riesgo marca ', 'EJC');
  await api(page, 'PUT', '/api/maestros/opl', { nombre: 'Operador logístico de prueba', correos: ['opl.e2e@example.com'] });
  const v = await api<{ id: number }>(page, 'POST', '/api/agenda', {
    tipo: 'anual',
    puntoId: punto.id,
    fecha: dentroDe(0),
    liderId: lider.id,
    otros: [],
    alcance: 'Almacenamiento.',
    componentes: ['C08'],
  });
  expect(v.estado, JSON.stringify(v.datos)).toBe(201);
  const id = v.datos.id;
  await salir(page);

  const ctx = await browser.newContext();
  const p2 = await ctx.newPage();
  try {
    await ingresarConClaveInicial(p2, lider.usuario, INICIAL, 'Lider.Riesgo.2026');
    expect((await api(p2, 'POST', `/api/mis-visitas/${id}/iniciar`)).estado).toBe(200);
    await p2.goto(`/visita/${id}`);
    await expect(p2.getByTestId('formulario-visita')).toBeVisible();

    const grupo = p2.locator('[data-testid^="calificacion-"]').first();
    const codigo = ((await grupo.getAttribute('data-testid')) ?? '').replace('calificacion-', '');
    expect(codigo).toBeTruthy();
    const marca = p2.getByTestId('marca-inmediata-' + codigo);
    // Sin calificar (o en Cumple) no hay casilla
    await expect(marca).toHaveCount(0);

    const nc = grupo.getByText('No cumple', { exact: true });
    await nc.scrollIntoViewIfNeeded();
    await nc.click();
    await expect(grupo.getByRole('radio', { name: 'No cumple', exact: true })).toBeChecked();
    await p2.getByLabel('Hallazgo de ' + codigo).fill('La nevera no tiene registro diario de temperatura.');
    const casilla = marca.getByRole('checkbox', { name: 'Requiere intervención inmediata del OPL' });
    await expect(casilla).not.toBeChecked();
    await casilla.check();
    await expect(casilla).toBeChecked();
    await p2.getByTestId('guardar-calificaciones').click();
    await expect(p2.getByText(/Guardado \(\d+ ítems\)/)).toBeVisible();

    // Al recargar la marca sigue
    await p2.reload();
    await expect(p2.getByTestId('formulario-visita')).toBeVisible();
    await expect(p2.getByTestId('calificacion-' + codigo).getByRole('radio', { name: 'No cumple', exact: true })).toBeChecked();
    await expect(
      p2.getByTestId('marca-inmediata-' + codigo).getByRole('checkbox', { name: 'Requiere intervención inmediata del OPL' }),
    ).toBeChecked();

    // Al pasar a Cumple la casilla desaparece, y después de guardar y recargar no vuelve
    const cumple = p2.getByTestId('calificacion-' + codigo).getByText('Cumple', { exact: true });
    await cumple.scrollIntoViewIfNeeded();
    await cumple.click();
    await expect(p2.getByTestId('calificacion-' + codigo).getByRole('radio', { name: 'Cumple', exact: true })).toBeChecked();
    await expect(p2.getByTestId('marca-inmediata-' + codigo)).toHaveCount(0);
    await p2.getByTestId('guardar-calificaciones').click();
    await expect(p2.getByText(/Guardado \(\d+ ítems\)/)).toBeVisible();
    await p2.reload();
    await expect(p2.getByTestId('calificacion-' + codigo).getByRole('radio', { name: 'Cumple', exact: true })).toBeChecked();
    await expect(p2.getByTestId('marca-inmediata-' + codigo)).toHaveCount(0);
  } finally {
    await salir(p2);
    await ctx.close();
  }
});

test('coordinador: dashboard de riesgo desde el menú, filtro por fuerza y pesos que deben sumar 100', async ({ page }) => {
  test.setTimeout(180_000);
  await ingresar(page, ...ADMIN);
  const coord = await crearUsuario(page, 'coordries', 2, 'Coordinadora riesgo');
  const ejc = await crearEsm(page, 'ESM riesgo EJC ', 'EJC');
  const arc = await crearEsm(page, 'ESM riesgo ARC ', 'ARC');
  await salir(page);

  await ingresarConClaveInicial(page, coord.usuario, INICIAL, 'Coordinadora.Riesgo.2026');
  try {
    await page.getByRole('link', { name: 'Dashboard de riesgo' }).click();
    await expect(page).toHaveURL(/\/riesgo$/);
    await expect(page.getByRole('heading', { name: 'Dashboard de riesgo' })).toBeVisible();
    const tabla = page.getByTestId('tabla-riesgo');
    await expect(tabla).toBeVisible();
    await expect(tabla.getByTestId('fila-riesgo-' + ejc.id)).toContainText(ejc.nombre);
    await expect(tabla.getByTestId('fila-riesgo-' + arc.id)).toContainText(arc.nombre);
    await expect(page.getByTestId('riesgo-pais')).toBeVisible();

    // Filtro por fuerza: solo quedan los ESM del Ejército
    const filtrado = page.waitForResponse((r) => r.url().includes('/api/riesgo?fuerza=EJC') && r.status() === 200);
    await page.getByRole('textbox', { name: 'Fuerza' }).click();
    await page.getByRole('option', { name: 'EJC', exact: true }).click();
    await filtrado;
    await expect(tabla.getByTestId('fila-riesgo-' + arc.id)).toHaveCount(0);
    await expect(tabla.getByTestId('fila-riesgo-' + ejc.id)).toBeVisible();
    const fuerzas = await tabla.locator('tbody tr[data-testid^="fila-riesgo-"] td:nth-child(2)').allTextContents();
    expect(fuerzas.length).toBeGreaterThan(0);
    expect(new Set(fuerzas)).toEqual(new Set(['EJC']));
    await page.getByRole('button', { name: 'Limpiar filtros' }).click();
    await expect(tabla.getByTestId('fila-riesgo-' + arc.id)).toBeVisible();

    // Pesos: con suma distinta de 100 se ve el error y no se puede guardar
    const panel = page.getByTestId('panel-pesos');
    const guardar = panel.getByTestId('guardar-pesos');
    await expect(guardar).toBeDisabled();
    await panel.getByTestId('peso-incumplimiento').fill('40');
    await expect(panel.getByTestId('problema-pesos')).toHaveText('Los pesos deben sumar 100 (suman 110).');
    await expect(panel.getByText('Suma de los pesos: 110 de 100')).toBeVisible();
    await expect(guardar).toBeDisabled();
    const sinCambio = await api<{ pesos: Record<string, number> }>(page, 'GET', '/api/riesgo');
    expect(sinCambio.datos.pesos.incumplimiento).toBe(PESOS_BASE.incumplimiento);

    // Con suma 100 guarda y avisa
    await panel.getByTestId('peso-recurrencia').fill('10');
    await expect(panel.getByTestId('problema-pesos')).toHaveCount(0);
    await expect(guardar).toBeEnabled();
    await guardar.click();
    await expect(page.getByText(/^Pesos guardados: /)).toBeVisible();
    const guardados = await api<{ pesos: Record<string, number> }>(page, 'GET', '/api/riesgo');
    expect(guardados.datos.pesos).toMatchObject({ incumplimiento: 40, recurrencia: 10 });
    await expect(panel.getByTestId('peso-incumplimiento')).toHaveValue('40');
  } finally {
    // Restaura los pesos y umbrales de la propuesta AGS aunque la prueba falle
    const r = await api(page, 'PUT', '/api/riesgo/pesos', { pesos: PESOS_BASE, ...UMBRALES_BASE });
    expect(r.estado, JSON.stringify(r.datos)).toBe(200);
  }
  const final = await api<{ pesos: Record<string, number>; umbrales: { alto: number; medio: number } }>(page, 'GET', '/api/riesgo');
  expect(final.datos.pesos).toEqual(PESOS_BASE);
  expect(final.datos.umbrales).toMatchObject(UMBRALES_BASE);
});

test('coordinador: registra una incidencia de la DIGSA sobre un ESM y la cierra con motivo', async ({ page }) => {
  test.setTimeout(150_000);
  await ingresar(page, ...ADMIN);
  const coord = await crearUsuario(page, 'coordinc', 2, 'Coordinadora incidencias');
  const punto = await crearEsm(page, 'ESM incidencia ', 'EJC');
  await salir(page);

  await ingresarConClaveInicial(page, coord.usuario, INICIAL, 'Coordinadora.Incid.2026');
  await page.goto('/riesgo');
  const form = page.getByTestId('form-incidencia');
  await expect(form).toBeVisible();
  const descripcion = unico('Quejas repetidas por entregas incompletas, caso ');

  await form.getByRole('textbox', { name: /^ESM/ }).fill(punto.nombre);
  await page.getByRole('option', { name: `${punto.nombre} (EJC)` }).click();
  await expect(form.getByLabel(/^Fecha/)).toHaveValue(dentroDe(0));
  await form.getByLabel(/^Descripción/).fill(descripcion);
  await form.getByLabel(/^Fuente/).fill('Oficio de prueba de la DIGSA');
  const registro = page.waitForResponse((r) => r.url().endsWith('/api/riesgo/incidencias') && r.request().method() === 'POST');
  await form.getByRole('button', { name: 'Registrar incidencia' }).click();
  expect((await registro).status()).toBe(200);
  await expect(page.getByText('Incidencia registrada.')).toBeVisible();

  const fila = page.getByTestId('tabla-incidencias').locator('tbody tr').filter({ hasText: descripcion });
  await expect(fila).toHaveCount(1);
  await expect(fila).toContainText(`${punto.nombre} (EJC)`);
  await expect(fila).toContainText('Abierta');
  // Suma al factor del ESM
  await expect(page.getByTestId('fila-riesgo-' + punto.id)).toContainText('Incidencias de la DIGSA 1');

  // Cerrar con motivo (mínimo 10 caracteres)
  await fila.getByRole('button', { name: 'Cerrar' }).click();
  const dialogo = page.getByRole('dialog', { name: 'Cerrar incidencia' });
  await expect(dialogo).toBeVisible();
  const confirmar = dialogo.getByRole('button', { name: 'Cerrar incidencia' });
  await dialogo.getByTestId('motivo-cierre-incidencia').fill('Corto');
  await expect(confirmar).toBeDisabled();
  await dialogo.getByTestId('motivo-cierre-incidencia').fill('La DIGSA confirmó la entrega completa.');
  await expect(confirmar).toBeEnabled();
  await confirmar.click();
  await expect(page.getByText('Incidencia cerrada.')).toBeVisible();
  await expect(dialogo).toBeHidden();
  await expect(fila).toContainText('Cerrada');
  await expect(fila).toContainText('La DIGSA confirmó la entrega completa.');
  await expect(fila.getByRole('button', { name: 'Cerrar' })).toHaveCount(0);
  await expect(page.getByTestId('fila-riesgo-' + punto.id)).toContainText('Incidencias de la DIGSA 0');
});

test('coordinador: dashboard de visitas con indicadores, filtro de fecha y descarga en Excel', async ({ page }) => {
  test.setTimeout(150_000);
  const coord = await coordinadorNuevo(page, 'coorddash');
  await ingresarConClaveInicial(page, coord.usuario, INICIAL, 'Coordinadora.Dash.2026');
  await page.getByRole('link', { name: 'Dashboard', exact: true }).click();
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByRole('heading', { name: 'Dashboard', exact: true })).toBeVisible();
  const indicadores = page.getByTestId('indicadores-dashboard');
  await expect(indicadores).toBeVisible();
  await expect(indicadores.getByTestId('ind-total')).toContainText(/\d+/);

  // Cambiar «Desde» recarga los datos con el filtro
  const desde = `${new Date().getFullYear()}-01-01`;
  const recarga = page.waitForResponse((r) => r.url().includes('/api/dashboard?') && r.url().includes('desde=' + desde) && r.status() === 200);
  await page.getByLabel('Desde').fill(desde);
  await recarga;
  await expect(page.getByTestId('filtros-aplicados')).toBeVisible();
  await expect(indicadores).toBeVisible();
  await expect(page.getByRole('button', { name: 'Limpiar filtros' })).toBeEnabled();

  // «Desde» posterior a «Hasta»: aviso y sin descarga hasta corregirlo
  await page.getByLabel('Hasta').fill(`${new Date().getFullYear() - 1}-12-31`);
  await expect(page.getByTestId('problema-fechas')).toHaveText('La fecha «Desde» es posterior a «Hasta».');
  await expect(page.getByRole('button', { name: 'Descargar Excel' })).toBeDisabled();
  await page.getByLabel('Hasta').fill('');
  await expect(page.getByTestId('problema-fechas')).toHaveCount(0);

  // La descarga en Excel inicia con los filtros
  const descarga = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Descargar Excel' }).click();
  expect((await descarga).suggestedFilename()).toMatch(/\.xlsx$/);
});

test('celular (360 px): /dashboard y /riesgo sin desplazamiento horizontal de la página', async ({ page }) => {
  test.setTimeout(150_000);
  const coord = await coordinadorNuevo(page, 'coordcel');
  await page.setViewportSize({ width: 360, height: 800 });
  await ingresarConClaveInicial(page, coord.usuario, INICIAL, 'Coordinadora.Cel.2026');

  await page.goto('/dashboard');
  await expect(page.getByTestId('indicadores-dashboard')).toBeVisible();
  await expect(page.getByTestId('grafica-estado')).toBeVisible();
  await sinScrollHorizontal(page);

  await page.goto('/riesgo');
  await expect(page.getByTestId('tabla-riesgo')).toBeVisible();
  await expect(page.getByTestId('panel-pesos')).toBeVisible();
  await expect(page.getByTestId('form-incidencia')).toBeVisible();
  await sinScrollHorizontal(page);
});

test('OPL: no ve los menús de los dashboards y recibe «Acceso restringido.» en /riesgo y /dashboard', async ({ page }) => {
  test.setTimeout(150_000);
  await ingresar(page, ...ADMIN);
  const opl = await crearUsuario(page, 'oplries', 8, 'OPL nacional');
  await salir(page);

  await ingresarConClaveInicial(page, opl.usuario, INICIAL, 'Opl.Riesgo.Propia.2026');
  await expect(page.getByRole('link', { name: 'Respuesta a hallazgos' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Dashboard de riesgo' })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Dashboard', exact: true })).toHaveCount(0);
  await page.goto('/riesgo');
  await expect(page.getByText('Acceso restringido.')).toBeVisible();
  await expect(page.getByTestId('tabla-riesgo')).toHaveCount(0);
  await page.goto('/dashboard');
  await expect(page.getByText('Acceso restringido.')).toBeVisible();
  expect((await api(page, 'GET', '/api/riesgo')).estado).toBe(403);
});
