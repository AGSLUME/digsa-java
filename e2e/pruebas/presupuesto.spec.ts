import { expect, test, type Locator, type Page } from '@playwright/test';
import { ADMIN, api, ingresar, ingresarConClaveInicial, salir, sinDesplazamientoHorizontal, unico } from './apoyo';

// Módulo de presupuesto (docs/especificaciones/presupuesto; Anexo 5 §4.6), casos M7 de la prueba en sitio:
// M7-1 carga del presupuesto por ESM, Fuerza y modalidad; M7-2 novedades trazadas con antes y después y visibles en el historial;
// la DIGSA consulta sin registrar; celular a 360 px sin desplazamiento horizontal.

test.afterEach(async ({ page }) => salir(page));

const INICIAL = 'Inicial.Presupuesto.2026';
const NUEVA = 'Nueva.Presupuesto.2026';

interface Punto {
  id: number;
  sigla: string | null;
  nombre: string;
  fuerza: string;
  modalidad: string;
}

async function crearUsuario(page: Page, prefijo: string, perfil: number): Promise<string> {
  const usuario = unico(prefijo);
  const r = await api(page, 'POST', '/api/usuarios', {
    nombre: `Persona ${usuario}`,
    usuario,
    correo: `${usuario}@example.com`,
    documento: '80000031',
    perfiles: [perfil],
    claveInicial: INICIAL,
  });
  expect(r.estado, JSON.stringify(r.datos)).toBe(201);
  return usuario;
}

/** Elige una opción de un Select de Mantine dentro de un contenedor. */
async function elegir(page: Page, dentro: Locator | Page, etiqueta: string, buscar: string, opcion: string | RegExp): Promise<void> {
  const campo = dentro.getByRole('textbox', { name: etiqueta });
  // Un clic sobre la opción ya elegida la deselecciona en Mantine: si ya está, no se toca
  const actual = await campo.inputValue();
  if (!buscar && (typeof opcion === 'string' ? actual === opcion : opcion.test(actual))) return;
  await campo.click();
  if (buscar) await campo.fill(buscar);
  await page.getByRole('option', { name: opcion }).first().click();
}

test('M7-1 y M7-2: vigencia, carga del presupuesto, rebalanceo trazado en el historial; la DIGSA solo consulta', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  const coordinador = await crearUsuario(page, 'coordpre', 2);
  const digsa = await crearUsuario(page, 'digsapre', 5);
  await salir(page);

  await ingresarConClaveInicial(page, coordinador, INICIAL, NUEVA);
  await page.goto('/presupuesto');
  await expect(page.getByRole('heading', { name: 'Presupuesto del contrato de medicamentos' })).toBeVisible();

  // Dos ESM del Ejército de dispensación, con sigla única, y un año sin vigencia
  const o = await api<{ puntos: Punto[]; vigencias: { anio: number }[] }>(page, 'GET', '/api/presupuesto/opciones');
  const siglas = new Map<string, number>();
  o.datos.puntos.forEach((p) => p.sigla && siglas.set(p.sigla, (siglas.get(p.sigla) ?? 0) + 1));
  const ejc = o.datos.puntos.filter((p) => p.fuerza === 'EJC' && p.modalidad === 'Dispensación' && p.sigla && siglas.get(p.sigla) === 1);
  expect(ejc.length).toBeGreaterThanOrEqual(2);
  const [neiva, cali] = [ejc[0]!, ejc[1]!];
  const usados = new Set(o.datos.vigencias.map((v) => v.anio));
  let anio = 2030 + (Date.now() % 70);
  while (usados.has(anio)) anio = 2030 + ((anio - 2029) % 70);

  // Configuración: la vigencia
  await page.getByRole('tab', { name: 'Configuración' }).click();
  await page.getByTestId('vigencia-anio').fill(String(anio));
  await page.getByTestId('vigencia-crear').click();
  await expect(page.getByText('Vigencia creada.')).toBeVisible();

  // M7-1: simular con un error, corregir y aplicar
  await page.getByRole('tab', { name: 'Cargas' }).click();
  const panel = page.getByRole('tabpanel');
  const malo = `vigencia;fuerza;esm;modalidad;valor\n${anio};ARC;${neiva.sigla};Dispensación;5\n`;
  await panel.locator('input[type="file"]').setInputFiles({ name: 'malo.csv', mimeType: 'text/csv', buffer: Buffer.from(malo, 'utf-8') });
  await page.getByTestId('carga-simular').click();
  await expect(page.getByTestId('carga-errores')).toContainText('Fila 2: La fuerza ARC no corresponde al ESM');
  await expect(page.getByTestId('carga-aplicar')).toBeDisabled();

  const bueno = `vigencia;fuerza;esm;modalidad;valor\n${anio};EJC;${neiva.sigla};Dispensación;900.000.000,00\n${anio};;${cali.sigla};Dispensación;1000000000\n`;
  await panel.locator('input[type="file"]').setInputFiles({ name: 'asignacion.csv', mimeType: 'text/csv', buffer: Buffer.from(bueno, 'utf-8') });
  await page.getByTestId('carga-simular').click();
  await expect(page.getByTestId('carga-resultado')).toContainText('listo para aplicar: 2 líneas, total 1900000000,00');
  await page.getByTestId('carga-soporte').fill(`Oficio DIGSA 123 de ${anio}`);
  await page.getByTestId('carga-aplicar').click();
  await expect(page.getByTestId('carga-resultado')).toContainText('Carga aplicada: 2 líneas, total 1900000000,00.');

  // M7-2: rebalanceo del mockup (450 millones de un ESM a otro)
  await page.getByRole('tab', { name: 'Novedad presupuestal' }).click();
  await elegir(page, page, 'Vigencia', '', new RegExp(`^${anio}$`));
  await expect(page.getByTestId('novedad-tipo')).toHaveValue('Rebalanceo');
  const origen = page.getByTestId('novedad-fila-0');
  const destino = page.getByTestId('novedad-fila-1');
  await elegir(page, origen, 'ESM', neiva.sigla!, new RegExp(`^${neiva.sigla} · `));
  await elegir(page, origen, 'Modalidad', '', 'Dispensación');
  await origen.getByTestId('novedad-valor').fill('450.000.000,00');
  await elegir(page, destino, 'ESM', cali.sigla!, new RegExp(`^${cali.sigla} · `));
  await elegir(page, destino, 'Modalidad', '', 'Dispensación');
  await destino.getByTestId('novedad-valor').fill('450000000');
  await expect(page.getByTestId('novedad-registrar')).toBeDisabled();
  await page.getByTestId('novedad-soporte').fill('Oficio [número] del [fecha]');
  await expect(page.getByText('Queda reflejada en el historial y en los reportes.')).toBeVisible({ timeout: 5000 });
  await page.getByTestId('novedad-registrar').click();
  await expect(page.getByText(/Rebalanceo registrada: 2 línea\(s\), neto 0,00\./)).toBeVisible();

  // Historial: el movimiento con su soporte y el antes y el después de cada línea
  await page.getByRole('tab', { name: 'Historial' }).click();
  await elegir(page, page, 'Vigencia', '', new RegExp(`^${anio}$`));
  await expect(page.getByTestId('historial-total')).toHaveText('2 movimiento(s)');
  const rebalanceo = page.locator('[data-testid^="movimiento-"]').first();
  await expect(rebalanceo).toContainText('Rebalanceo');
  await expect(rebalanceo).toContainText('Oficio [número] del [fecha]');
  await expect(rebalanceo.getByRole('row', { name: new RegExp(neiva.sigla!) })).toContainText('900000000,00');
  await expect(rebalanceo.getByRole('row', { name: new RegExp(neiva.sigla!) })).toContainText('450000000,00');
  await expect(rebalanceo.getByRole('row', { name: new RegExp(cali.sigla!) })).toContainText('1450000000,00');

  // Líneas: los saldos actualizados; en celular, sin desplazamiento horizontal de la página
  await page.getByRole('tab', { name: 'Líneas' }).click();
  await elegir(page, page, 'Vigencia', '', new RegExp(`^${anio}$`));
  await expect(page.getByTestId('tabla-lineas')).toContainText('1450000000,00');
  await page.setViewportSize({ width: 360, height: 740 });
  await sinDesplazamientoHorizontal(page);
  await page.getByRole('tab', { name: 'Novedad presupuestal' }).click();
  await sinDesplazamientoHorizontal(page);
  await page.setViewportSize({ width: 1280, height: 720 });
  await salir(page);

  // La DIGSA consulta, sin pestañas de registro (y el servidor responde 403 si lo intenta)
  await ingresarConClaveInicial(page, digsa, INICIAL, NUEVA);
  await page.goto('/presupuesto');
  await expect(page.getByRole('tab', { name: 'Líneas' })).toBeVisible();
  await expect(page.getByRole('tab', { name: 'Novedad presupuestal' })).toHaveCount(0);
  await expect(page.getByRole('tab', { name: 'Cargas' })).toHaveCount(0);
  expect((await api(page, 'POST', '/api/presupuesto/vigencias', { anio: 2101 })).estado).toBe(403);
});

/** POST multipart de una novedad desde la página (misma sesión y token CSRF). */
async function novedadApi(page: Page, datos: unknown): Promise<number> {
  return page.evaluate(async (d) => {
    const t = /(?:^|; )XSRF-TOKEN=([^;]+)/.exec(document.cookie)?.[1];
    const f = new FormData();
    f.append('datos', JSON.stringify(d));
    const r = await fetch('/api/presupuesto/novedades', { method: 'POST', body: f, headers: t ? { 'X-XSRF-TOKEN': decodeURIComponent(t) } : {} });
    return r.status;
  }, datos);
}

test('F3: ejecución manual que cruza el 25 %, versión del mes y notificación formal con consecutivo', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  const coordinador = await crearUsuario(page, 'coordeje', 2);
  await salir(page);
  await ingresarConClaveInicial(page, coordinador, INICIAL, NUEVA);
  await page.goto('/presupuesto');

  const o = await api<{ puntos: Punto[]; vigencias: { anio: number }[] }>(page, 'GET', '/api/presupuesto/opciones');
  const siglas = new Map<string, number>();
  o.datos.puntos.forEach((p) => p.sigla && siglas.set(p.sigla, (siglas.get(p.sigla) ?? 0) + 1));
  const esm = o.datos.puntos.find((p) => p.fuerza === 'EJC' && p.modalidad === 'Dispensación' && p.sigla && siglas.get(p.sigla) === 1)!;
  const usados = new Set(o.datos.vigencias.map((v) => v.anio));
  let anio = 2030 + ((Date.now() + 37) % 70);
  for (let i = 0; usados.has(anio) && i < 70; i++) anio = 2030 + ((anio - 2029) % 70);
  const v = await api<{ id: number }>(page, 'POST', '/api/presupuesto/vigencias', { anio });
  expect(v.estado).toBe(201);
  expect(
    await novedadApi(page, {
      vigenciaId: v.datos.id,
      tipo: 'asignacion_inicial',
      soporte: 'Oficio DIGSA de asignación',
      items: [{ puntoId: esm.id, modalidad: 'dispensacion', valor: '1000000000' }],
    }),
  ).toBe(201);
  await page.reload();

  await page.getByRole('tab', { name: 'Ejecución' }).click();
  const manual = page.getByTestId('ejecucion-manual');
  await elegir(page, manual, 'Vigencia', '', new RegExp(`^${anio}$`));
  await elegir(page, manual, 'ESM', esm.sigla!, new RegExp(`^${esm.sigla} · `));
  await elegir(page, manual, 'Modalidad', '', 'Dispensación');
  await elegir(page, manual, 'Mes', '', `${anio}-03`);
  await page.getByTestId('ejecucion-facturado').fill('260.000.000,00');
  await page.getByTestId('ejecucion-registrar').click();
  await expect(page.getByText(/Ejecución registrada: .*, \d{4}-03\./)).toBeVisible();
  await expect(page.getByTestId('tabla-ejecucion')).toContainText('260000000,00');

  await page.getByRole('tab', { name: 'Notificaciones' }).click();
  const tarjeta = page.locator('[data-testid^="notificacion-PRE-"]').filter({ hasText: esm.sigla! }).filter({ hasText: 'alcanzó el 25 %' }).first();
  await expect(tarjeta).toContainText('alcanzó el 25 % del presupuesto (26,00 %)');
  await expect(tarjeta).toContainText(/Enviada a la bandeja de salida|Falta el buzón de la fuerza|Sin destinatarios/);

  await page.setViewportSize({ width: 360, height: 740 });
  await sinDesplazamientoHorizontal(page);
  await page.getByRole('tab', { name: 'Ejecución' }).click();
  await sinDesplazamientoHorizontal(page);
  await page.setViewportSize({ width: 1280, height: 720 });
});
