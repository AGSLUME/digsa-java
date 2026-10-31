import { expect, test, type Page, type Route } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { ADMIN, api, ingresar, ingresarConClaveInicial, salir, unico } from './apoyo';

// Descarga diferida de analítica (A7.3, duda AN.16; docs/especificaciones/analitica-tableros «Descarga diferida cuando no cabe en la
// petición»). Solo el CSV de registros de origen se difiere; las descargas de los tableros son agregadas y siempre salen en la petición.
// Un analista abre la analítica sobre un insumo sintético de 2003 (ningún otro insumo de las e2e usa ese año; la barra de filtros solo
// acepta años de 2000 a 2100, así que 1996 no sirve en la pantalla): con pocos registros el tablero y el CSV de registros de origen salen
// en la petición y «Mis descargas del día» no aparece. El servidor de las e2e usa el umbral por defecto (100 000 registros) y cargar tanto
// en la base compartida de las e2e la volvería lenta para todas: la generación en diferido del servidor y la descarga de su archivo se
// prueban en AnaliticaDescargasApiTest (umbral bajado por propiedad en su contexto propio); aquí la respuesta 202, el avance y el archivo
// se simulan en el navegador (page.route) para recorrer la pantalla: aviso, avance «En cola» → «Generando» → «Lista», la descarga con el
// helper de la aplicación y el aviso de una vencida (410). Datos sintéticos: documentos y nombres ficticios, únicos por corrida.

const INICIAL = 'Inicial.Diferidas.2026';
const PERIODO = 'desde=2003-01-01&hasta=2003-12-31';

test.afterEach(async ({ page }) => salir(page));

/** CSV «;» UTF-8 con BOM: 3 dispensaciones válidas de 2003. */
function insumo(): { nombre: string; contenido: string; documentos: string[] } {
  const sello = `${Date.now()}`;
  const documentos = [1, 2, 3].map((i) => `93${sello.slice(-6)}${i}`);
  const enc =
    'TIPO_DOC;NUM_DOC;NOMBRE_PACIENTE;FUERZA;COD_ESM;MODALIDAD;FECHA_DISPENSACION;DESC_MEDICAMENTO;CUM;ATC;CANTIDAD;VALOR_TOTAL;NUM_FORMULA';
  const filas = documentos.map(
    (d, i) =>
      `CC;${d};PACIENTE FICTICIO DIFERIDA ${i};EJC;BAS02;Dispensación;1${i}/05/2003;LOSARTAN 50 MG TABLETA;20010037-1;C09CA01;30;5415,00;E2ED${sello}${i}`,
  );
  return { nombre: `diferidas_e2e_${sello}.csv`, contenido: '﻿' + [enc, ...filas].join('\r\n') + '\r\n', documentos };
}

/** Carga el insumo por la API (como la pantalla «Cargues del OPL») y espera a que quede aplicado. */
async function cargar(page: Page): Promise<{ corte: number; documentos: string[] }> {
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
  const conf = await api(page, 'POST', `/api/analitica/cargues/${id}/mapeo`, {
    columnas: mapeo.datos.propuesta,
    modo: 'agregar',
    guardarPlantilla: false,
  });
  expect(conf.estado, JSON.stringify(conf.datos)).toBe(202);
  await expect
    .poll(async () => (await api<{ estado: string }>(page, 'GET', `/api/analitica/cargues/${id}`)).datos.estado, { timeout: 120_000 })
    .toBe('aplicado');
  return { corte: id, documentos: a.documentos };
}

/** Admin carga el insumo y crea un analista (perfil 10); entra el analista con su clave nueva. */
async function comoAnalista(page: Page, prefijo: string): Promise<{ corte: number; documentos: string[] }> {
  await ingresar(page, ...ADMIN);
  const datos = await cargar(page);
  const analista = unico(prefijo);
  const r = await api(page, 'POST', '/api/usuarios', {
    nombre: `Persona ${analista}`,
    usuario: analista,
    correo: `${analista}@example.com`,
    documento: '80000096',
    perfiles: [10],
    claveInicial: INICIAL,
  });
  expect(r.estado, JSON.stringify(r.datos)).toBe(201);
  await salir(page);
  await ingresarConClaveInicial(page, analista, INICIAL, 'Analista.Diferidas.E2E.2026');
  return datos;
}

/** Abre el rastreo de la primera barra del gráfico ATC (el panel de trazabilidad en el cajón). */
async function abrirTraza(page: Page, corte: number) {
  await page.goto(`/analitica?t=atc&${PERIODO}&corte=${corte}`);
  const grafico = page.getByTestId('grafico-atc');
  await expect(grafico).toBeVisible();
  await grafico
    .getByRole('button', { name: /^Rastrear/ })
    .first()
    .click();
  const panel = page.getByTestId('panel-traza');
  await expect(panel).toBeVisible();
  return panel;
}

/** Una descarga diferida como la da la API («Mis descargas del día»). */
function descarga(id: number, corte: number, estado: string, extra: Record<string, unknown> = {}) {
  return {
    id,
    tablero: 'atc',
    titulo: 'Clasificación ATC',
    nombre: 'registros_origen_atc_20031030_1415.csv',
    corteCargueId: corte,
    registros: 150000,
    estado,
    tamano: null,
    sha256: null,
    huella: null,
    error: null,
    creado: new Date().toISOString(),
    iniciado: null,
    terminado: null,
    vence: null,
    duracionMs: null,
    ...extra,
  };
}

/** La versión «lista» (con su archivo y vencimiento). */
function lista(id: number, corte: number, sha: string) {
  return descarga(id, corte, 'lista', {
    tamano: 2202,
    sha256: sha,
    terminado: new Date().toISOString(),
    vence: new Date(Date.now() + 3_600_000).toISOString(),
  });
}

test('con pocos registros el tablero y el CSV de registros de origen salen en la petición y «Mis descargas del día» no aparece', async ({ page }) => {
  const { corte, documentos } = await comoAnalista(page, 'anadif');
  await page.goto(`/analitica?t=medicamento&${PERIODO}&corte=${corte}`);
  await expect(page.getByTestId('tablero')).toBeVisible();
  const espera = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Descargar CSV' }).click();
  const d = await espera;
  expect(d.suggestedFilename()).toMatch(/^analitica_medicamento_\d{8}_\d{4}\.csv$/);
  const csv = readFileSync((await d.path())!, 'utf-8');
  // Los registros del corte (las corridas anteriores de esta prueba también cargan 2003)
  const t = await api<{ metadatos: { registros: number } }>(page, 'GET', `/api/analitica/tableros/medicamento?${PERIODO}&corte=${corte}`);
  expect(csv).toContain(`N.º de registros procesados;${t.datos.metadatos.registros}`);
  for (const doc of documentos) expect(csv).not.toContain(doc);
  // El CSV de registros de origen, también en la petición (bajo el umbral)
  const panel = await abrirTraza(page, corte);
  const esperaTraza = page.waitForEvent('download');
  await panel.getByRole('button', { name: 'Descargar CSV de los registros' }).click();
  expect((await esperaTraza).suggestedFilename()).toBe('registros_origen_atc.csv');
  await page.keyboard.press('Escape');
  await expect(page.getByTestId('descargas-diferidas')).toHaveCount(0);
  const mis = await api<unknown[]>(page, 'GET', '/api/analitica/descargas-diferidas');
  expect(mis.estado).toBe(200);
  expect(mis.datos).toEqual([]);
});

test('registros de origen grandes: aviso, avance «En cola» → «Generando» → «Lista» y la descarga desde «Mis descargas del día»', async ({ page }) => {
  const { corte } = await comoAnalista(page, 'anadifg');
  const id = 990000 + (Date.now() % 9999);
  // Simulación del servidor (ver el encabezado): 202 al pedir el CSV, el avance en «Mis descargas del día» y el archivo
  const estados = ['pendiente', 'generando', 'lista'];
  let pedida = false;
  let consultas = 0;
  await page.route(/\/api\/analitica\/traza\/atc\/registros\.csv\?/, async (route: Route) => {
    pedida = true;
    await route.fulfill({
      status: 202,
      contentType: 'application/json',
      body: JSON.stringify({
        ...descarga(id, corte, 'pendiente'),
        mensaje:
          'La descarga tiene 150000 registros de origen (más de 100000): se está generando en segundo plano. Quedará en «Mis descargas del día» con su enlace cuando esté lista.',
      }),
    });
  });
  await page.route(/\/api\/analitica\/descargas-diferidas$/, async (route: Route) => {
    if (!pedida) return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    const estado = estados[Math.min(consultas++, estados.length - 1)]!;
    const fila = estado === 'lista' ? lista(id, corte, 'a'.repeat(64)) : descarga(id, corte, estado);
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([fila]) });
  });
  // La descarga desde el panel va por fetch (el helper de la aplicación), que sí pasa por page.route
  await page.route(new RegExp(`/api/analitica/descargas-diferidas/${id}/archivo$`), (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'text/csv;charset=UTF-8',
      headers: { 'Content-Disposition': 'attachment; filename="registros_origen_atc_20031030_1415.csv"' },
      body: '﻿Título;Registros de origen\r\nFIN;150000 registros\r\n',
    }),
  );

  const panel = await abrirTraza(page, corte);
  await expect(page.getByTestId('descargas-diferidas')).toHaveCount(0);
  await panel.getByRole('button', { name: 'Descargar CSV de los registros' }).click();
  // Aviso de la descarga diferida (no se guarda un archivo con el JSON del 202)
  await expect(page.getByText(/se está generando en segundo plano/)).toBeVisible();
  await page.keyboard.press('Escape');
  const mis = page.getByTestId('descargas-diferidas');
  await expect(mis.getByRole('heading', { name: 'Mis descargas del día' })).toBeVisible();
  const fila = mis.getByTestId(`descarga-diferida-${id}`);
  await expect(fila).toContainText('Registros de origen · Clasificación ATC');
  await expect(fila.getByTestId('estado')).toHaveText(/En cola|Generando/);
  await expect(fila.getByTestId('estado')).toHaveText('Lista', { timeout: 20_000 });
  await expect(fila).toContainText('SHA-256 aaaaaaaaaaaaaaaa');
  const espera = page.waitForEvent('download');
  await fila.getByRole('button', { name: 'Descargar Registros de origen · Clasificación ATC' }).click();
  const d = await espera;
  expect(d.suggestedFilename()).toBe('registros_origen_atc_20031030_1415.csv');
  expect(readFileSync((await d.path())!, 'utf-8')).toContain('FIN;150000 registros');
});

test('una descarga vencida en «Mis descargas del día» avisa el 410 y la lista se vuelve a consultar', async ({ page }) => {
  const { corte } = await comoAnalista(page, 'anadifv');
  const id = 980000 + (Date.now() % 9999);
  let consultas = 0;
  await page.route(/\/api\/analitica\/descargas-diferidas$/, async (route: Route) => {
    consultas++;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([lista(id, corte, 'b'.repeat(64))]) });
  });
  await page.route(new RegExp(`/api/analitica/descargas-diferidas/${id}/archivo$`), (route: Route) =>
    route.fulfill({
      status: 410,
      contentType: 'application/json',
      body: JSON.stringify({ mensaje: 'La descarga venció y su archivo se borró. Pídala de nuevo.' }),
    }),
  );
  await page.goto(`/analitica?t=atc&${PERIODO}&corte=${corte}`);
  const fila = page.getByTestId(`descarga-diferida-${id}`);
  await expect(fila.getByTestId('estado')).toHaveText('Lista');
  const antes = consultas;
  await fila.getByRole('button', { name: 'Descargar Registros de origen · Clasificación ATC' }).click();
  await expect(page.getByText('La descarga venció y su archivo se borró. Pídala de nuevo.')).toBeVisible();
  await expect.poll(() => consultas).toBeGreaterThan(antes);
});
