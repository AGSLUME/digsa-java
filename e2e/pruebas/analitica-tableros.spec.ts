import { expect, test, type Page } from '@playwright/test';
import { ADMIN, api, ingresar, ingresarConClaveInicial, salir, unico } from './apoyo';

// Analítica farmacéutica (docs/especificaciones/analitica-tableros; casos M8-1, M8-2, M8-5, M8-8 y M8-9): recorrer los 12 tableros con
// filtros, comparar las cifras de la pantalla con las de la API, descargar Excel, CSV y PDF con sus metadatos, rastrear una cifra hasta los
// registros de origen (seudonimizados) y comprobar que un perfil sin la pantalla de trazabilidad no ve «Rastrear». Datos sintéticos: el
// insumo se carga por la API de «Cargues del OPL» con documentos y nombres ficticios.

const INICIAL = 'Inicial.Analitica.2026';

test.afterEach(async ({ page }) => salir(page));

/** CSV «;» UTF-8 con BOM de 40 registros válidos del año 2024 (único por corrida: el número de fórmula lleva el sello). */
function insumo(): { nombre: string; contenido: string } {
  const sello = `${Date.now()}`;
  const enc = 'TIPO_DOC;NUM_DOC;NOMBRE_PACIENTE;FUERZA;COD_ESM;MODALIDAD;FECHA_DISPENSACION;DESC_MEDICAMENTO;CUM;ATC;CIE10;CANTIDAD;VALOR_TOTAL;NUM_FORMULA';
  const meds = [
    ['LOSARTAN 50 MG TABLETA', '20010037-1', 'C09CA01', '5415,00'],
    ['METFORMINA 850 MG TABLETA', '20010074-2', 'A10BA02', '5712,00'],
    ['OMEPRAZOL 20 MG CAPSULA', '20010222-3', 'A02BC01', '2652,00'],
  ];
  const esm = [
    ['EJC', 'BAS02', 'Dispensación'],
    ['ARC', 'DMBOG', 'Dispensación'],
    ['FAC', 'CATAM', 'Suministro'],
  ];
  const filas: string[] = [];
  for (let i = 0; i < 40; i++) {
    const m = meds[i % 3]!;
    const e = esm[i % 3]!;
    const mes = String(1 + (i % 12)).padStart(2, '0');
    filas.push(`CC;${82000000 + (i % 15)};PACIENTE FICTICIO ${i};${e[0]};${e[1]};${e[2]};15/${mes}/2024;${m[0]};${m[1]};${m[2]};I10X;30;${m[3]};E2T${sello}${i}`);
  }
  return { nombre: `insumo_tableros_e2e_${sello}.csv`, contenido: '﻿' + [enc, ...filas].join('\r\n') + '\r\n' };
}

/** Carga el insumo por la API (como la pantalla «Cargues del OPL») y espera a que quede aplicado. */
async function cargar(page: Page): Promise<number> {
  const a = insumo();
  const bytes = new TextEncoder().encode(a.contenido);
  const sha = await page.evaluate(async (texto) => {
    const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(texto));
    return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, '0')).join('');
  }, a.contenido);
  const c = await api<{ id: number }>(page, 'POST', '/api/analitica/cargues', { nombre: a.nombre, tamano: bytes.length, sha256: sha, partes: 1 });
  expect(c.estado, JSON.stringify(c.datos)).toBe(201);
  const id = c.datos.id;
  const parte = await page.evaluate(
    async ({ id, texto }) => {
      const t = /(?:^|; )XSRF-TOKEN=([^;]+)/.exec(document.cookie)?.[1];
      const r = await fetch(`/api/analitica/cargues/${id}/partes/1`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/octet-stream', ...(t ? { 'X-XSRF-TOKEN': decodeURIComponent(t) } : {}) },
        body: new TextEncoder().encode(texto),
      });
      return r.status;
    },
    { id, texto: a.contenido },
  );
  expect(parte).toBe(200);
  expect((await api(page, 'POST', `/api/analitica/cargues/${id}/cerrar`)).estado).toBe(200);
  const mapeo = await api<{ propuesta: string[] }>(page, 'GET', `/api/analitica/cargues/${id}/mapeo`);
  const conf = await api(page, 'POST', `/api/analitica/cargues/${id}/mapeo`, { columnas: mapeo.datos.propuesta, modo: 'agregar', guardarPlantilla: false });
  expect(conf.estado, JSON.stringify(conf.datos)).toBe(202);
  await expect
    .poll(async () => (await api<{ estado: string }>(page, 'GET', `/api/analitica/cargues/${id}`)).datos.estado, { timeout: 120_000 })
    .toBe('aplicado');
  return id;
}

async function crearUsuario(page: Page, prefijo: string, perfil: number, extra: Record<string, unknown> = {}): Promise<string> {
  const usuario = unico(prefijo);
  const r = await api(page, 'POST', '/api/usuarios', {
    nombre: `Persona ${usuario}`,
    usuario,
    correo: `${usuario}@example.com`,
    documento: '80000041',
    perfiles: [perfil],
    claveInicial: INICIAL,
    ...extra,
  });
  expect(r.estado, JSON.stringify(r.datos)).toBe(201);
  return usuario;
}

const PERIODO = 'desde=2024-01-01&hasta=2024-12-31';

test('M8-1, M8-5 y M8-8: recorre los 12 tableros con filtros, compara con la API y descarga Excel, CSV y PDF', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  const corte = await cargar(page);
  const analista = await crearUsuario(page, 'anae2e', 10);
  await salir(page);
  await ingresarConClaveInicial(page, analista, INICIAL, 'Analista.Tableros.2026');

  await page.getByRole('link', { name: 'Analítica farmacéutica' }).click();
  await expect(page.getByRole('heading', { name: 'Analítica farmacéutica' })).toBeVisible();
  // Por defecto, «Gasto farmacéutico» activo y la fecha de corte del último cargue
  await expect(page.getByTestId('chip-gasto')).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByTestId('corte')).toContainText('Corte');

  // Filtros en la URL: periodo 2024, corte del cargue y Fuerza EJC
  await page.goto(`/analitica?t=gasto&${PERIODO}&corte=${corte}&fuerza=EJC`);
  await expect(page.getByTestId('tablero')).toBeVisible();
  const api1 = await api<{ kpis: { clave: string; texto: string }[] }>(
    page,
    'GET',
    `/api/analitica/tableros/gasto?${PERIODO}&corte=${corte}&fuerza=EJC`,
  );
  const gasto = api1.datos.kpis.find((k) => k.clave === 'gasto')!.texto;
  await expect(page.getByTestId('kpi-gasto')).toContainText(gasto);

  // Recorre los 12 chips conservando el filtro
  const chips = ['general', 'gasto', 'atc', 'principio', 'medicamento', 'diagnostico', 'territorial', 'alto-costo', 'poliformulacion'];
  for (const c of chips) {
    await page.getByTestId(`chip-${c}`).click();
    await expect(page.getByTestId(`chip-${c}`)).toHaveAttribute('aria-pressed', 'true');
    await expect(page).toHaveURL(new RegExp(`t=${c}.*fuerza=EJC`));
    await expect(page.getByTestId('tablero')).toBeVisible();
    await expect(page.getByTestId('kpi-gasto').or(page.getByTestId('kpi-pacientes')).first()).toBeVisible();
  }
  // Cohortes y atípicos (A4) abren sus paneles con la misma barra de filtros; el predictivo llega en A5
  await page.getByTestId('chip-cohortes').click();
  await expect(page.getByTestId('panel-cohortes')).toBeVisible();
  await page.getByTestId('chip-atipicos').click();
  await expect(page.getByTestId('panel-atipicos')).toBeVisible();
  await page.getByTestId('chip-predictivo').click();
  await expect(page.getByTestId('siguiente-fase')).toContainText('Disponible en la siguiente fase');

  // «Ver tabla» de las barras con aria-expanded
  await page.getByTestId('chip-territorial').click();
  const barras = page.getByTestId('grafico-esm');
  await barras.getByRole('button', { name: 'Ver tabla' }).click();
  await expect(barras.getByRole('button', { name: 'Ver gráfico' })).toHaveAttribute('aria-expanded', 'true');
  await expect(barras.getByRole('table')).toContainText('Total');

  // Descargas de lo visualizado: Excel, CSV y PDF
  for (const [boton, extension] of [
    ['Descargar Excel', '.xlsx'],
    ['Descargar CSV', '.csv'],
    ['Descargar PDF', '.pdf'],
  ] as const) {
    const espera = page.waitForEvent('download');
    await page.getByRole('button', { name: boton }).click();
    const d = await espera;
    expect(d.suggestedFilename()).toMatch(new RegExp(`^analitica_territorial_\\d{8}_\\d{4}\\${extension}$`));
  }
  const csv = await page.evaluate(async (ruta) => (await fetch(ruta)).text(), `/api/analitica/tableros/territorial/descarga?${PERIODO}&corte=${corte}&fuerza=EJC&formato=csv`);
  for (const e of ['Título;', 'Fecha de corte;', 'Huella SHA-256 del contenido;', 'N.º de registros procesados;', 'Filtros;Alcance: Nacional']) {
    expect(csv).toContain(e);
  }
  expect(csv).not.toContain('82000001');
  expect(csv).not.toContain('PACIENTE FICTICIO');
});

test('M8-9: rastrea una cifra hasta los registros de origen; Sanidad no ve «Rastrear» y la API le responde 403', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  const corte = await cargar(page);
  const analista = await crearUsuario(page, 'anatz', 10);
  const sanidad = await crearUsuario(page, 'sanaz', 6, { alcanceFuerza: 'EJC' });
  await salir(page);

  await ingresarConClaveInicial(page, analista, INICIAL, 'Analista.Traza.2026');
  await page.goto(`/analitica?t=atc&${PERIODO}&corte=${corte}&fuerza=EJC`);
  const grafico = page.getByTestId('grafico-atc');
  await expect(grafico).toBeVisible();
  await grafico.getByRole('button', { name: /^Rastrear/ }).first().click();
  const panel = page.getByTestId('panel-traza');
  await expect(panel).toBeVisible();
  await expect(panel).toContainText('Registros de origen');
  await expect(panel).toContainText('línea');
  await expect(panel).not.toContainText('8200000');
  // La suma de la lista es la cifra de la barra
  const suma = await panel.getByTestId('traza-suma').textContent();
  await expect(grafico).toContainText((suma ?? '').trim());
  const espera = page.waitForEvent('download');
  await panel.getByRole('button', { name: 'Descargar CSV de los registros' }).click();
  expect((await espera).suggestedFilename()).toBe('registros_origen_atc.csv');
  await salir(page);

  await ingresarConClaveInicial(page, sanidad, INICIAL, 'Sanidad.Traza.2026');
  await page.goto(`/analitica?t=atc&${PERIODO}&corte=${corte}`);
  await expect(page.getByTestId('grafico-atc')).toBeVisible();
  await expect(page.getByRole('button', { name: /^Rastrear/ })).toHaveCount(0);
  const r = await api(page, 'GET', `/api/analitica/traza/atc?${PERIODO}&corte=${corte}`);
  expect(r.estado).toBe(403);
  await expect(page.getByRole('link', { name: 'Trazabilidad de cifras' })).toHaveCount(0);
});
