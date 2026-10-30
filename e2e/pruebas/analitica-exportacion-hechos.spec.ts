import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { createHash } from 'node:crypto';
import { api, ingresarComoAdminPropio, ingresarConClaveInicial, salir, unico } from './apoyo';

// Exportación de los hechos por periodo (A7.2, duda AN.14; docs/especificaciones/analitica-ingesta): un administrador propio carga un
// insumo sintético de marzo de 1997 por la pantalla, pide la exportación de ese mes en «Cargues del OPL», espera a que quede lista y
// descarga el .csv.gz: SHA-256 igual al de la pantalla, encabezado de las columnas, el paciente solo como seudónimo y ningún documento ni
// nombre en claro. Datos sintéticos: documentos y nombres ficticios, únicos por corrida.

test.afterEach(async ({ page }) => salir(page));

/** CSV «;» UTF-8 con BOM: 2 dispensaciones válidas en marzo de 1997 (ningún otro insumo de las e2e usa ese año). */
function insumo(): { nombre: string; contenido: string; documentos: string[] } {
  const sello = `${Date.now()}`;
  const documentos = [`97${sello.slice(-6)}1`, `97${sello.slice(-6)}2`];
  const enc = 'TIPO_DOC;NUM_DOC;NOMBRE_PACIENTE;FUERZA;COD_ESM;MODALIDAD;FECHA_DISPENSACION;DESC_MEDICAMENTO;CUM;CANTIDAD;VALOR_TOTAL;NUM_FORMULA';
  const filas = [
    `CC;${documentos[0]};PACIENTE FICTICIO HECHOS UNO;EJC;BAS02;Dispensación;10/03/1997;LOSARTAN 50 MG TABLETA;20010037-1;30;5415,00;E2EH${sello}A`,
    `CC;${documentos[1]};PACIENTE FICTICIO HECHOS DOS;ARC;DMBOG;Dispensación;11/03/1997;OMEPRAZOL 20 MG CAPSULA;20010222-3;30;2652,50;E2EH${sello}B`,
  ];
  return { nombre: `hechos_e2e_${sello}.csv`, contenido: '﻿' + [enc, ...filas].join('\r\n') + '\r\n', documentos };
}

interface Exportacion {
  id: number;
  estado: string;
  archivos: { mes: string; estado: string; filas: number | null; sha256: string | null }[];
}

test('el administrador exporta los hechos de un mes y descarga el .csv.gz con su SHA-256, sin documentos en claro', async ({ page }) => {
  await ingresarComoAdminPropio(page, 'admhech');
  await page.getByRole('link', { name: 'Cargues del OPL' }).click();
  await expect(page.getByRole('heading', { name: 'Cargues del OPL' })).toBeVisible();

  // Insumo de marzo de 1997 por la pantalla (como el caso M8-3)
  await page.getByRole('button', { name: 'Cargar archivo estructurado' }).click();
  const dialogo = page.getByRole('dialog', { name: 'Cargar archivo estructurado' });
  const archivo = insumo();
  await dialogo
    .locator('input[type="file"]')
    .setInputFiles({ name: archivo.nombre, mimeType: 'text/csv', buffer: Buffer.from(archivo.contenido, 'utf-8') });
  await dialogo.getByRole('button', { name: 'Subir' }).click();
  await expect(dialogo.getByTestId('mapeo')).toBeVisible({ timeout: 30_000 });
  await dialogo.getByRole('button', { name: 'Confirmar y procesar' }).click();
  await expect(dialogo.getByTestId('estado-asistente')).toHaveText('Aplicado', { timeout: 120_000 });
  await page.keyboard.press('Escape');

  // Sección «Exportación de hechos por periodo»: validación por campo y solicitud de un solo mes
  const seccion = page.getByTestId('exportacion-hechos');
  await expect(seccion.getByRole('heading', { name: 'Exportación de hechos por periodo' })).toBeVisible();
  await seccion.getByRole('button', { name: /^Exportar hechos/ }).click();
  await expect(seccion.getByTestId('error-exportacion-hechos')).toBeVisible();
  await seccion.getByLabel('Mes inicial').fill('1997-03');
  await seccion.getByRole('button', { name: /^Exportar hechos/ }).click();

  // La exportación propia (administrador propio: la lista solo trae las suyas) llega a «Lista»
  let propia: Exportacion | undefined;
  await expect
    .poll(
      async () => {
        const r = await api<Exportacion[]>(page, 'GET', '/api/analitica/exportaciones-hechos');
        propia = r.datos[0];
        return propia?.estado;
      },
      { timeout: 120_000 },
    )
    .toBe('lista');
  const tarjeta = seccion.getByTestId(`exportacion-hechos-${propia!.id}`);
  await expect(tarjeta.getByTestId('estado')).toHaveText('Lista');
  await expect(tarjeta).toContainText('1 de 1 meses listos');
  const mes = tarjeta.getByTestId('mes-1997-03');
  await expect(mes).toContainText('Listo');
  const sha = propia!.archivos[0].sha256!;
  await expect(mes).toContainText(sha);

  // Descarga del mes (enlace con «download»): SHA-256 del archivo tal como se descarga, CSV con BOM y las columnas, seudónimo y sin documentos en claro
  const [descarga] = await Promise.all([page.waitForEvent('download'), mes.getByRole('link', { name: 'Descargar los hechos de 1997-03' }).click()]);
  expect(descarga.suggestedFilename()).toBe(`digsa_hechos_dispensacion_${propia!.id}_1997-03.csv.gz`);
  const gz = readFileSync((await descarga.path())!);
  expect(createHash('sha256').update(gz).digest('hex')).toBe(sha);
  const csv = gunzipSync(gz).toString('utf-8');
  expect(csv.startsWith('﻿id;cargue_id;cargue_sha256;linea_origen;paciente_seudo;')).toBe(true);
  expect(csv).toContain(`E2EH`);
  for (const doc of archivo.documentos) expect(csv).not.toContain(doc);
  expect(csv).not.toContain('PACIENTE FICTICIO');
  const filas = csv.trim().split('\r\n').slice(1);
  expect(filas.length).toBe(propia!.archivos[0].filas);

  // El LEEME se descarga con el periodo y la huella
  const [leeme] = await Promise.all([page.waitForEvent('download'), tarjeta.getByRole('link', { name: /Descargar el LEEME/ }).click()]);
  const texto = readFileSync((await leeme.path())!, 'utf-8');
  expect(texto).toContain('Periodo: 1997-03 a 1997-03');
  expect(texto).toContain(sha);
});

test('la exportación de hechos es solo del administrador: el analista no ve la sección y la API le responde 403', async ({ page }) => {
  await ingresarComoAdminPropio(page, 'admhech2');
  const analista = unico('anhech');
  // Perfil 10 = Analista (Perfil.ANALISTA): tiene «Cargues del OPL» pero no la exportación de hechos
  const r = await api(page, 'POST', '/api/usuarios', {
    nombre: `Persona ${analista}`,
    usuario: analista,
    correo: `${analista}@example.com`,
    perfiles: [10],
    claveInicial: 'Inicial.Analista.Hechos.2026',
  });
  expect(r.estado, JSON.stringify(r.datos)).toBe(201);
  await salir(page);
  await ingresarConClaveInicial(page, analista, 'Inicial.Analista.Hechos.2026', 'Analista.Hechos.E2E.2026');
  await page.getByRole('link', { name: 'Cargues del OPL' }).click();
  await expect(page.getByRole('heading', { name: 'Cargues del OPL' })).toBeVisible();
  await expect(page.getByTestId('exportacion-hechos')).toHaveCount(0);
  expect((await api(page, 'POST', '/api/analitica/exportaciones-hechos', { desde: '1997-03', hasta: null })).estado).toBe(403);
  expect((await api(page, 'GET', '/api/analitica/exportaciones-hechos')).estado).toBe(403);
});
