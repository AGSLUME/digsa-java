import { expect, test, type Page } from '@playwright/test';
import { ADMIN, api, ingresar, ingresarConClaveInicial, salir, unico } from './apoyo';

// Tablero 12 «Predictivo» (docs/especificaciones/analitica-cohortes-alertas, A5; caso M8-11): proyección a 6 meses con IC 95 %, gráfico
// accesible con su tabla, metadatos del modelo (método real, evaluados, periodo histórico, fecha de entrenamiento, MAPE/WMAPE, notas),
// escenario ajustado por % (validado junto al campo), proyectado vs observado, bitácora de entrenamientos y «Reentrenar» solo para quien
// opera; el ESM ve solo su establecimiento y no reentrena. Sin IA. Datos sintéticos: el insumo se carga por la API de «Cargues del OPL»
// con documentos y nombres ficticios. La base de e2e se reutiliza entre corridas: no se comparan cifras exactas.

const INICIAL = 'Inicial.Prediccion.2026';
const ESM = 'BAS10';

test.afterEach(async ({ page }) => salir(page));

/** CSV «;» UTF-8 con BOM: una dispensación por mes de 2024 en el BAS10 (EJC), con gasto creciente; único por corrida. */
function insumo(): { nombre: string; contenido: string } {
  const sello = `${Date.now()}`;
  const base = 84_000_000 + (Number(sello.slice(-5)) % 50_000) * 20;
  const enc =
    'TIPO_DOC;NUM_DOC;NOMBRE_PACIENTE;FUERZA;COD_ESM;MODALIDAD;FECHA_DISPENSACION;DESC_MEDICAMENTO;CUM;PRINCIPIO_ACTIVO;ATC;CIE10;CANTIDAD;VALOR_TOTAL;NUM_FORMULA';
  const filas: string[] = [];
  for (let mes = 1; mes <= 12; mes++) {
    const ultimo = new Date(Date.UTC(2024, mes, 0)).getUTCDate();
    filas.push(
      `CC;${base + mes};PACIENTE FICTICIO ${mes};EJC;${ESM};Dispensación;${ultimo}/${String(mes).padStart(2, '0')}/2024;LOSARTAN 50 MG TABLETA;20010037-1;LOSARTAN;C09CA01;I10X;30;${1000 + 100 * mes},00;E2P${sello}${mes}`,
    );
  }
  return { nombre: `insumo_prediccion_e2e_${sello}.csv`, contenido: '﻿' + [enc, ...filas].join('\r\n') + '\r\n' };
}

/** Carga el insumo por la API (como la pantalla «Cargues del OPL») y espera a que quede aplicado; devuelve el cargue (el corte). */
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
  const conf = await api(page, 'POST', `/api/analitica/cargues/${id}/mapeo`, {
    columnas: mapeo.datos.propuesta,
    modo: 'agregar',
    guardarPlantilla: false,
  });
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
    documento: '80000043',
    perfiles: [perfil],
    claveInicial: INICIAL,
    ...extra,
  });
  expect(r.estado, JSON.stringify(r.datos)).toBe(201);
  return usuario;
}

test('M8-11: proyección con IC, metadatos, escenario ajustado, proyectado vs observado y reentrenamiento del analista', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  const corte = await cargar(page);
  const analista = await crearUsuario(page, 'anapre', 10);
  await salir(page);
  await ingresarConClaveInicial(page, analista, INICIAL, 'Analista.Prediccion.2026');

  await page.goto(`/analitica?t=predictivo&nivel=esm&esm=${ESM}&corte=${corte}`);
  await expect(page.getByTestId('chip-predictivo')).toHaveAttribute('aria-pressed', 'true');
  const panel = page.getByTestId('panel-prediccion');
  await expect(panel).toBeVisible();
  await expect(page.getByTestId('siguiente-fase')).toHaveCount(0);
  await expect(panel.getByRole('heading', { level: 2, name: 'Predictivo' })).toBeVisible();

  // Gráfico accesible (observado continuo, proyectado discontinuo, banda IC 95 %, corte) y su tabla equivalente
  const grafico = page.getByTestId('grafico-prediccion');
  await expect(grafico.getByRole('img')).toHaveAttribute('aria-label', /línea continua observado; línea discontinua proyectado 6 meses con banda del IC 95 %/);
  await grafico.getByRole('button', { name: 'Ver tabla' }).click();
  const tabla = page.getByTestId('tabla-serie-prediccion');
  await expect(tabla).toBeVisible();
  await expect(tabla.getByRole('row').filter({ hasText: 'Proyectado' })).toHaveCount(6);

  // Metadatos: método real y parámetros, evaluados con su error, periodo histórico, fecha de entrenamiento, corte, MAPE/WMAPE
  const meta = page.getByTestId('metadatos-prediccion');
  for (const r of ['Método y parámetros', 'Periodo histórico', 'Backtesting', 'Fecha de entrenamiento', 'Corte', 'Versión y huella de la serie']) {
    await expect(meta.getByRole('rowheader', { name: r })).toBeVisible();
  }
  await expect(meta).toContainText('WMAPE');
  await expect(meta.getByRole('table', { name: 'Métodos evaluados en el backtesting' })).toContainText('(elegido)');
  await expect(page.getByTestId('notas-prediccion')).toContainText('sin componente estacional');

  // Escenario ajustado: un % inválido se marca junto al campo; uno válido agrega la columna (no se guarda)
  const escenarios = page.getByTestId('escenarios-prediccion');
  await escenarios.getByLabel('Ajuste del escenario (%)').fill('cinco');
  await escenarios.getByRole('button', { name: 'Aplicar ajuste' }).click();
  await expect(page.getByTestId('error-prediccion')).toBeVisible();
  await escenarios.getByLabel('Ajuste del escenario (%)').fill('5');
  await escenarios.getByRole('button', { name: 'Aplicar ajuste' }).click();
  await expect(escenarios.getByRole('columnheader', { name: 'Ajustado (5,00 %)' })).toBeVisible();

  // Proyectado vs observado: la sección siempre está (vacía si ningún entrenamiento anterior cubrió un mes ya observado)
  await expect(page.getByTestId('comparacion-prediccion').getByRole('heading', { name: 'Proyectado vs observado' })).toBeVisible();

  // Reentrenar: queda en la bitácora de entrenamientos con el usuario
  await page.getByTestId('reentrenar').click();
  await expect(page.getByText(/Entrenamiento \d+ registrado/)).toBeVisible();
  const bitacora = page.getByTestId('entrenamientos-prediccion');
  await expect(bitacora.getByRole('row').nth(1)).toContainText(`Reentrenar · ${analista}`);
  await expect(page.getByTestId('fecha-entrenamiento')).toContainText(`Reentrenar · ${analista}`);
});

test('M8-11: el ESM ve la proyección de su establecimiento, sin otras vistas ni «Reentrenar»', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  const usuarios = await api<{ puntos: { id: number; sigla?: string | null }[] }>(page, 'GET', '/api/usuarios');
  const punto = usuarios.datos.puntos[0]!.id;
  const esm = await crearUsuario(page, 'esmpre', 7, { alcancePunto: punto });
  await salir(page);
  await ingresarConClaveInicial(page, esm, INICIAL, 'Esm.Prediccion.2026');
  await page.goto('/analitica?t=predictivo');
  const panel = page.getByTestId('panel-prediccion');
  await expect(panel).toBeVisible();
  await expect(page.getByTestId('reentrenar')).toHaveCount(0);
  // Solo la vista ESM
  await page.getByTestId('prediccion-nivel').click();
  await expect(page.getByRole('option')).toHaveText(['ESM']);
  await page.keyboard.press('Escape');
  expect((await api(page, 'POST', '/api/analitica/prediccion/reentrenar', { metrica: 'gasto' })).estado).toBe(403);
  expect((await api(page, 'GET', '/api/analitica/prediccion?metrica=gasto&nivel=nacional')).estado).toBe(422);
});
