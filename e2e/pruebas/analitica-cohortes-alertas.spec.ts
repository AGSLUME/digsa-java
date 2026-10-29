import { expect, test, type Page } from '@playwright/test';
import { ADMIN, api, ingresar, ingresarConClaveInicial, salir, unico } from './apoyo';

// Cohortes y alertas tempranas (docs/especificaciones/analitica-cohortes-alertas; casos M8-6 y M8-7): ver las cohortes con su criterio
// (siempre agregadas), abrir la consola de alertas con su contexto y su análisis técnico, cambiar un umbral con traza (antes → después),
// validar y descartar alertas con nota (validación humana) y comprobar que el ESM no tiene la consola (403) y solo ve los atípicos
// agregados de su establecimiento. Datos sintéticos: el insumo se carga por la API de «Cargues del OPL» con documentos y nombres ficticios.

const INICIAL = 'Inicial.Riesgo.2026';
const PERIODO = 'desde=2023-01-01&hasta=2023-12-31';

test.afterEach(async ({ page }) => salir(page));

/**
 * CSV «;» UTF-8 con BOM: 12 pacientes con apixabán y rivaroxabán (mismo ATC4 B01AF) a 15 días en un mes propio de la corrida
 * (duplicidad terapéutica sembrada) y 6 pacientes con 5 principios activos cada mes de febrero a julio de 2023 (polimedicados). Único por
 * corrida; devuelve también el mes de la duplicidad.
 */
function insumo(): { nombre: string; contenido: string; anio: number; mes: string } {
  const sello = `${Date.now()}`;
  const base = 83_000_000 + (Number(sello.slice(-5)) % 50_000) * 20;
  const enc =
    'TIPO_DOC;NUM_DOC;NOMBRE_PACIENTE;FUERZA;COD_ESM;MODALIDAD;FECHA_DISPENSACION;DESC_MEDICAMENTO;CUM;PRINCIPIO_ACTIVO;ATC;CIE10;CANTIDAD;VALOR_TOTAL;NUM_FORMULA';
  const filas: string[] = [];
  let n = 0;
  // El mes de la duplicidad cambia en cada corrida: la base de e2e se reutiliza entre navegadores y una alerta ya validada en una
  // corrida anterior (mismo tipo, nivel y mes) no se reevalúa, así que cada corrida necesita su propia alerta nueva. Un mes por minuto
  // entre 2000 y 2023 (288 meses): los navegadores de una verificación corren con decenas de minutos de distancia.
  const k = Math.floor(Number(sello) / 60_000) % 288;
  const anio = 2000 + Math.floor(k / 12);
  const mes = String(1 + (k % 12)).padStart(2, '0');
  for (let i = 0; i < 12; i++) {
    const doc = base + i;
    filas.push(
      `CC;${doc};PACIENTE FICTICIO ${i};EJC;BAS02;Dispensación;03/${mes}/${anio};APIXABAN 5 MG TABLETA;20019001-1;APIXABAN;B01AF02;I48X;60;150000,00;E2R${sello}${n++}`,
    );
    filas.push(
      `CC;${doc};PACIENTE FICTICIO ${i};EJC;BAS02;Dispensación;18/${mes}/${anio};RIVAROXABAN 20 MG TABLETA;20019002-1;RIVAROXABAN;B01AF01;I48X;30;114000,00;E2R${sello}${n++}`,
    );
  }
  const cinco = [
    ['LOSARTAN 50 MG TABLETA', '20010037-1', 'LOSARTAN', 'C09CA01'],
    ['METFORMINA 850 MG TABLETA', '20010074-2', 'METFORMINA', 'A10BA02'],
    ['ATORVASTATINA 20 MG TABLETA', '20010111-3', 'ATORVASTATINA', 'C10AA05'],
    ['OMEPRAZOL 20 MG CAPSULA', '20010222-3', 'OMEPRAZOL', 'A02BC01'],
    ['HIDROCLOROTIAZIDA 25 MG TABLETA', '20010481-1', 'HIDROCLOROTIAZIDA', 'C03AA03'],
  ];
  for (let i = 0; i < 6; i++) {
    const doc = base + 100 + i;
    for (let mes = 2; mes <= 7; mes++) {
      for (const m of cinco) {
        filas.push(
          `CC;${doc};PACIENTE FICTICIO P${i};ARC;DMBAQ;Dispensación;10/${String(mes).padStart(2, '0')}/2023;${m[0]};${m[1]};${m[2]};${m[3]};I10X;30;5000,00;E2R${sello}${n++}`,
        );
      }
    }
  }
  return { nombre: `insumo_riesgo_e2e_${sello}.csv`, contenido: '﻿' + [enc, ...filas].join('\r\n') + '\r\n', anio, mes };
}

/**
 * Carga el insumo por la API (como la pantalla «Cargues del OPL»), espera a que quede aplicado y a que corra el motor de alertas.
 * Devuelve el corte y el mes de la duplicidad sembrada (primer y último día, para filtrar la consola).
 */
async function cargar(page: Page): Promise<{ corte: number; desde: string; hasta: string }> {
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
  // El motor corre en segundo plano después de aplicar el cargue
  await expect
    .poll(
      async () => {
        const e = await api<{ corte: number; estado: string }[]>(page, 'GET', '/api/analitica/alertas/ejecuciones');
        return e.datos.some((x) => x.corte === id && x.estado !== 'en_curso');
      },
      { timeout: 180_000 },
    )
    .toBe(true);
  const ultimo = new Date(Date.UTC(a.anio, Number(a.mes), 0)).getUTCDate();
  return { corte: id, desde: `${a.anio}-${a.mes}-01`, hasta: `${a.anio}-${a.mes}-${ultimo}` };
}

async function crearUsuario(page: Page, prefijo: string, perfil: number, extra: Record<string, unknown> = {}): Promise<string> {
  const usuario = unico(prefijo);
  const r = await api(page, 'POST', '/api/usuarios', {
    nombre: `Persona ${usuario}`,
    usuario,
    correo: `${usuario}@example.com`,
    documento: '80000042',
    perfiles: [perfil],
    claveInicial: INICIAL,
    ...extra,
  });
  expect(r.estado, JSON.stringify(r.datos)).toBe(201);
  return usuario;
}

test('M8-6 y M8-7: cohortes con su criterio, consola con contexto, umbral con traza y validación humana', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  const { corte, desde, hasta } = await cargar(page);
  const analista = await crearUsuario(page, 'anarie', 10);
  await salir(page);
  await ingresarConClaveInicial(page, analista, INICIAL, 'Analista.Riesgo.2026');

  // Cohortes y riesgo: tarjetas con el conteo agregado, el criterio en texto y la versión, sin abrir otra pantalla
  await page.goto(`/analitica?t=cohortes&${PERIODO}&corte=${corte}`);
  await expect(page.getByTestId('chip-cohortes')).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByTestId('panel-cohortes')).toBeVisible();
  const poli = page.getByTestId('cohorte-polimedicados');
  await expect(poli.getByTestId('criterio')).toContainText('≥ 5 principios activos distintos en cada mes durante ≥ 6 meses consecutivos · v1');
  await expect(poli.getByTestId('conteo')).toContainText('pacientes');
  await expect(page.getByTestId('cohorte-gcf')).toBeVisible();
  // El detalle trae la serie mensual y la composición; nunca una lista de pacientes
  await poli.getByRole('button', { name: /Ver detalle de Polimedicados/ }).click();
  await expect(page.getByTestId('detalle-criterio')).toContainText('consecutivos');
  await expect(page.getByRole('table', { name: 'Serie mensual de la cohorte' })).toBeVisible();
  // Enlace a la consola filtrada por la cohorte (spec §(e)): la consola abre con el filtro puesto
  await expect(page.getByTestId('alertas-de-la-cohorte')).toHaveAttribute('href', /\/analitica\/alertas\?cohorte=\d+/);
  await page.keyboard.press('Escape');

  // Comportamientos atípicos (tablero 11) en el mes de la duplicidad sembrada: conteos por severidad
  await page.goto(`/analitica?t=atipicos&desde=${desde}&hasta=${hasta}&corte=${corte}`);
  await expect(page.getByTestId('chip-atipicos')).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByTestId('panel-atipicos')).toBeVisible();
  await expect(page.getByTestId('atipicos-total')).not.toHaveText('0');

  // Consola de alertas: columnas de contexto y el detalle con el análisis técnico
  await page.getByRole('link', { name: 'Alertas analíticas' }).click();
  await expect(page.getByRole('heading', { name: 'Alertas analíticas' })).toBeVisible();
  await page.getByTestId('filtro-tipo').click();
  await page.getByRole('option', { name: 'Duplicidad terapéutica' }).click();
  // Solo el mes de esta corrida: su duplicidad B01AF es nueva y está abierta (las de corridas anteriores pueden estar validadas)
  await page.getByLabel('Desde').fill(desde);
  await page.getByLabel('Hasta').fill(hasta);
  const fila = page
    .getByTestId('alerta-duplicidad_terapeutica')
    .filter({ hasText: 'B01AF' })
    .filter({ hasNotText: /Validada|Descartada/ })
    .first();
  await expect(fila).toBeVisible();
  for (const encabezado of ['Severidad', 'Umbral', 'Observado', 'Periodo', 'Dimensión', 'Pacientes']) {
    await expect(page.getByTestId('tabla-alertas').getByRole('columnheader', { name: encabezado })).toBeVisible();
  }
  await fila.getByRole('button', { name: /Abrir la alerta/ }).click();
  const detalle = page.getByTestId('detalle-alerta');
  await expect(detalle).toContainText('Causa:');
  await expect(detalle).toContainText('Valoración del riesgo o impacto:');
  await expect(detalle).toContainText('Recomendación:');
  await expect(detalle.getByTestId('registros-origen')).toBeVisible();

  // Validación humana: la nota corta no deja guardar; con nota queda en el historial
  await detalle.getByTestId('nuevo-estado').click();
  await page.getByRole('option', { name: 'Validada' }).click();
  await detalle.getByTestId('nota-estado').fill('corta');
  await expect(detalle.getByTestId('guardar-estado')).toBeDisabled();
  await detalle.getByTestId('nota-estado').fill('Revisada con el comité: duplicidad real');
  await detalle.getByTestId('guardar-estado').click();
  await expect(detalle).toContainText('Revisada con el comité: duplicidad real');
  await page.keyboard.press('Escape');

  // Descartar otra alerta (si la hay en la página) con su motivo
  const otra = page
    .getByTestId('tabla-alertas')
    .getByRole('button', { name: /Abrir la alerta/ })
    .nth(1);
  if (await otra.isVisible()) {
    await otra.click();
    const d2 = page.getByTestId('detalle-alerta');
    if (await d2.getByTestId('nuevo-estado').isVisible()) {
      await d2.getByTestId('nuevo-estado').click();
      await page.getByRole('option', { name: 'Descartada' }).click();
      await d2.getByTestId('nota-estado').fill('No aplica: cambio de terapia documentado');
      await d2.getByTestId('guardar-estado').click();
      await expect(d2).toContainText('No aplica: cambio de terapia documentado');
    }
    await page.keyboard.press('Escape');
  }

  // Parámetros: cambiar el umbral bajo de «Incremento inusual del gasto» deja una versión con antes → después
  await page.getByRole('tab', { name: 'Parámetros' }).click();
  const p = page.getByTestId('parametro-incremento_gasto');
  const actual = await p.getByTestId('umbral-baja-incremento_gasto').inputValue();
  const nuevo = actual === '25' ? '26' : '25';
  await p.getByTestId('umbral-baja-incremento_gasto').fill(nuevo);
  await p.getByTestId('guardar-parametro-incremento_gasto').click();
  await expect(page.getByText(/Versión \d+ guardada/)).toBeVisible();
  const p2 = page.getByTestId('parametro-incremento_gasto');
  await p2.getByRole('button', { name: 'Historial' }).click();
  await expect(p2.getByTestId('historial-parametro').first()).toContainText(`baja ${actual} → ${nuevo}`);
});

test('M8-7: el ESM no tiene la consola de alertas (403) y en el tablero 11 solo ve los conteos de su establecimiento', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  const usuarios = await api<{ puntos: { id: number }[] }>(page, 'GET', '/api/usuarios');
  const punto = usuarios.datos.puntos[0]!.id;
  const esm = await crearUsuario(page, 'esmrie', 7, { alcancePunto: punto });
  await salir(page);
  await ingresarConClaveInicial(page, esm, INICIAL, 'Esm.Riesgo.2026');
  await expect(page.getByRole('link', { name: 'Alertas analíticas' })).toHaveCount(0);
  expect((await api(page, 'GET', '/api/analitica/alertas')).estado).toBe(403);
  await page.goto('/analitica?t=atipicos');
  await expect(page.getByTestId('panel-atipicos')).toBeVisible();
  await expect(page.getByRole('table', { name: 'Alertas de mayor prioridad' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Abrir la consola de alertas' })).toHaveCount(0);
});
