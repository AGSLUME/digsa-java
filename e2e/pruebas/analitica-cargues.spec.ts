import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { ADMIN, api, ingresar, salir } from './apoyo';

// Cargues del OPL (docs/especificaciones/analitica-ingesta; caso M8-3): el administrador sube un archivo estructurado por partes, asigna
// las columnas (propuestas por sinónimos), espera a que se aplique, ve la fila de la bitácora con los rechazados en rojo y descarga los
// rechazos (línea y causa, sin el contenido de las celdas). Datos sintéticos: documentos y nombres ficticios.

test.afterEach(async ({ page }) => salir(page));

/** CSV «;» UTF-8 con BOM: 5 registros válidos y 2 con errores (fecha inválida y CUM con formato inválido). Único por corrida. */
function insumo(): { nombre: string; contenido: string } {
  const sello = `${Date.now()}`;
  const enc = 'TIPO_DOC;NUM_DOC;NOMBRE_PACIENTE;FUERZA;COD_ESM;MODALIDAD;FECHA_DISPENSACION;DESC_MEDICAMENTO;CUM;CANTIDAD;VALOR_TOTAL;NUM_FORMULA';
  const filas = [
    `CC;81000001;PACIENTE FICTICIO UNO;EJC;BAS02;Dispensación;14/03/2025;LOSARTAN 50 MG TABLETA;20010037-1;30;5415,00;E2E${sello}A`,
    `CC;81000002;PACIENTE FICTICIO DOS;Ejército;BAS02;DISPENSACION;15/03/2025;METFORMINA 850 MG TABLETA;20010074-2;60;5712,00;E2E${sello}B`,
    `TI;81000003;PACIENTE FICTICIO TRES;ARC;DMBOG;Dispensación;16/04/2025;OMEPRAZOL 20 MG CAPSULA;20010222-3;30;2652,00;E2E${sello}C`,
    `CC;81000004;PACIENTE FICTICIO CUATRO;FAC;CATAM;Suministro;20/05/2025;ACETAMINOFEN 500 MG TABLETA;20010259-1;20;900,00;E2E${sello}D`,
    `CC;81000005;PACIENTE FICTICIO CINCO;EJC;ESM-999;Dispensación;21/06/2025;IBUPROFENO 400 MG TABLETA;20010296-2;20;1202,00;E2E${sello}E`,
    `CC;81000006;PACIENTE FICTICIO SEIS;EJC;BAS02;Dispensación;31/02/2025;LOSARTAN 50 MG TABLETA;20010037-1;30;5415,00;E2E${sello}F`,
    `CC;81000007;PACIENTE FICTICIO SIETE;EJC;BAS02;Dispensación;10/07/2025;LOSARTAN 50 MG TABLETA;ABC-12;30;5415,00;E2E${sello}G`,
  ];
  return { nombre: `insumo_e2e_${sello}.csv`, contenido: '﻿' + [enc, ...filas].join('\r\n') + '\r\n' };
}

test('el administrador sube un archivo, mapea, ve la bitácora con rechazados y descarga los rechazos', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  await page.getByRole('link', { name: 'Cargues del OPL' }).click();
  await expect(page.getByRole('heading', { name: 'Cargues del OPL' })).toBeVisible();

  await page.getByRole('button', { name: 'Cargar archivo estructurado' }).click();
  const dialogo = page.getByRole('dialog', { name: 'Cargar archivo estructurado' });
  const archivo = insumo();
  await dialogo.locator('input[type="file"]').setInputFiles({
    name: archivo.nombre,
    mimeType: 'text/csv',
    buffer: Buffer.from(archivo.contenido, 'utf-8'),
  });
  await dialogo.getByRole('button', { name: 'Subir' }).click();

  // Mapeo propuesto por sinónimos: el paciente se ve enmascarado y el nombre se descarta
  const mapeo = dialogo.getByTestId('mapeo');
  await expect(mapeo).toBeVisible({ timeout: 30_000 });
  await expect(mapeo.getByLabel('Campo de la columna NUM_DOC')).toHaveValue('Identificador del paciente *');
  await expect(mapeo.getByLabel('Campo de la columna NOMBRE_PACIENTE')).toHaveValue('Se descarta (dato personal)');
  const vista = dialogo.getByTestId('vista-previa');
  await expect(vista).toContainText('seud. ');
  await expect(vista).not.toContainText('81000001');
  await expect(vista).not.toContainText('PACIENTE FICTICIO');
  await dialogo.getByRole('button', { name: 'Confirmar y procesar' }).click();

  await expect(dialogo.getByTestId('estado-asistente')).toHaveText('Aplicado', { timeout: 120_000 });
  await expect(dialogo).toContainText('Recibidos 7 · procesados 5 · rechazados 2');
  await dialogo.getByRole('button', { name: 'Ver el detalle' }).click();

  // Detalle: duración por etapa, SHA-256, motivos con conteo y descarga de rechazos
  const detalle = page.getByTestId('detalle-cargue');
  await expect(detalle.getByTestId('estado-detalle')).toHaveText('Aplicado');
  await expect(detalle.getByTestId('etapas')).toContainText('Validación');
  await expect(detalle.getByTestId('motivos')).toContainText('Fecha inválida');
  await expect(detalle.getByTestId('motivos')).toContainText('CUM con formato inválido');
  const [descarga] = await Promise.all([page.waitForEvent('download'), detalle.getByRole('button', { name: 'Descargar rechazos' }).click()]);
  expect(descarga.suggestedFilename()).toMatch(/^rechazos_cargue_\d+\.csv$/);
  const ruta = await descarga.path();
  const texto = readFileSync(ruta!, 'utf-8');
  expect(texto).toContain('linea;causa;columna');
  expect(texto).toContain('Fecha inválida;fecha');
  expect(texto).not.toContain('ABC-12');
  expect(texto).not.toContain('81000006');
  await page.keyboard.press('Escape');

  // Bitácora: la fila aparece arriba con los ocho atributos y los rechazados en rojo, sin separador de miles
  const tabla = page.getByTestId('bitacora-cargues');
  const fila = tabla.locator('tbody tr').first();
  await expect(fila).toContainText('Archivo CSV (;) UTF-8');
  await expect(fila).toContainText('14/03/2025 – 21/06/2025');
  await expect(fila.getByTestId('rechazados')).toHaveText('2');
  await expect(fila.getByTestId('estado')).toHaveText('Aplicado');
  await expect(fila).toContainText('admin');
});

test('el servicio web del OPL solo acepta al OPL nacional: el administrador recibe 403', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  // El administrador ve la bitácora; el servicio web es solo del OPL nacional (la prueba de API cubre cada perfil)
  expect((await api(page, 'GET', '/api/analitica/cargues')).estado).toBe(200);
  expect((await api(page, 'POST', '/api/analitica/opl/dispensacion', { envio: 'x', numero: 1, final: true, registros: [] })).estado).toBe(403);
});

test('reemplazar el periodo pide el periodo declarado y confirma lo que oculta; un cargue en mapeo se descarta', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  await page.getByRole('link', { name: 'Cargues del OPL' }).click();
  await page.getByRole('button', { name: 'Cargar archivo estructurado' }).click();
  const dialogo = page.getByRole('dialog', { name: 'Cargar archivo estructurado' });
  const archivo = insumo();
  await dialogo
    .locator('input[type="file"]')
    .setInputFiles({ name: archivo.nombre, mimeType: 'text/csv', buffer: Buffer.from(archivo.contenido, 'utf-8') });
  await dialogo.getByRole('button', { name: 'Subir' }).click();
  await expect(dialogo.getByTestId('mapeo')).toBeVisible({ timeout: 30_000 });

  // Reemplazar sin periodo: no se puede confirmar; con periodo, la confirmación dice qué se deja de ver
  await dialogo.getByLabel(/Reemplazar el periodo declarado/).check();
  await expect(dialogo.getByTestId('confirmar-mapeo')).toBeDisabled();
  await dialogo.getByLabel(/Periodo declarado: desde/).fill('2025-03-01');
  await dialogo.getByLabel('Hasta').fill('2025-07-31');
  await dialogo.getByTestId('confirmar-mapeo').click();
  const confirmacion = page.getByRole('dialog', { name: 'Reemplazar el periodo' });
  await expect(confirmacion).toContainText('Periodo declarado: 01/03/2025 – 31/07/2025');
  await confirmacion.getByRole('button', { name: 'Volver' }).click();

  // Descartar el cargue en mapeo: queda en la bitácora como «Descartado»
  await dialogo.getByRole('button', { name: 'Descartar cargue' }).click();
  await expect(dialogo).toBeHidden();
  await page.getByRole('textbox', { name: 'Estado' }).click();
  await page.getByRole('option', { name: 'Descartado' }).click();
  await expect(page.getByTestId('bitacora-cargues').locator('tbody tr').first().getByTestId('estado')).toHaveText('Descartado');
});
