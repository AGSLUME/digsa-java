import { expect, test, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { ADMIN, api, ingresar, salir } from './apoyo';

// Homologación (docs/especificaciones/analitica-homologacion; caso M8-4): con las referencias de ejemplo FICTICIAS del CUM publicadas,
// el administrador carga un insumo pequeño, ve los KPI de calidad que suman 100 %, busca en la tabla maestra, revisa y aprueba un
// inconsistente (OPL frente a INVIMA, sin sugerencia automática) y cambia la tolerancia de R-015 viendo la traza en el historial.
// Datos sintéticos: documentos y nombres ficticios; referencias de backend/app/src/test/resources/analitica (marcadas como ficticias).

const REFERENCIAS = resolve(__dirname, '../../backend/app/src/test/resources/analitica');

test.afterEach(async ({ page }) => salir(page));

/** Publica la versión de ejemplo del CUM vigente por la pantalla si aún no hay una publicada (la base de e2e se reutiliza). */
async function asegurarCumPublicado(page: Page): Promise<void> {
  const fuentes = await api<Array<{ codigo: string; publicada: unknown }>>(page, 'GET', '/api/homologacion/fuentes');
  const vigentes = fuentes.datos.find((f) => f.codigo === 'INVIMA_CUM_VIGENTES');
  if (vigentes?.publicada) return;
  await page.getByRole('tab', { name: 'Fuentes oficiales' }).click();
  const fila = page.getByTestId('fuente-INVIMA_CUM_VIGENTES');
  await fila.getByTestId('cargar-INVIMA_CUM_VIGENTES').click();
  const dialogo = page.getByRole('dialog', { name: /Cargar CUM del INVIMA/ });
  await dialogo.locator('input[type="file"]').setInputFiles({
    name: 'cum_vigentes_ejemplo_ficticio.csv',
    mimeType: 'text/csv',
    buffer: readFileSync(resolve(REFERENCIAS, 'cum_vigentes_ejemplo_ficticio.csv')),
  });
  await dialogo.getByLabel('Fecha de actualización de la fuente').fill('2026-10-16');
  await dialogo.getByLabel(/Datos de ejemplo/).check();
  await dialogo.getByRole('button', { name: 'Cargar y validar' }).click();
  await expect(dialogo).toContainText(/Versión \d+ validada/, { timeout: 60_000 });
  await page.keyboard.press('Escape');
  await fila.getByRole('button', { name: 'Publicar' }).first().click();
  // Publicar pide confirmación (la versión pasa a ser la vigente)
  await page.getByTestId('confirmar-publicar-boton').click();
  await expect(fila).toContainText('Datos de ejemplo');
}

/**
 * Si la tabla maestra quedó desactualizada (p. ej. otra spec cargó la metformina antes de publicar el CUM: la homologación del cargue
 * es incremental y ese dato conserva «Sin correspondencia» hasta re-ejecutar), re-ejecuta por el aviso de la pantalla y espera el fin.
 */
async function reejecutarSiDesactualizada(page: Page): Promise<void> {
  const resumen = await api<{ desactualizada: { motivo: string } | null }>(page, 'GET', '/api/homologacion/resumen');
  if (!resumen.datos.desactualizada) return;
  const aviso = page.getByTestId('aviso-desactualizada');
  await expect(aviso).toBeVisible();
  await expect(aviso).toContainText('después de la versión');
  // El botón del aviso abre la misma confirmación de la re-ejecución
  await expect(aviso.getByTestId('reejecutar-desactualizada')).toBeVisible();
  // Sobre la base reutilizada de e2e la re-ejecución puede pasar del minuto de inactividad de estas pruebas: se lanza por la API y se
  // espera moviendo el ratón (actividad del usuario) hasta que termine; luego el aviso desaparece.
  const inicio = await api(page, 'POST', '/api/homologacion/ejecuciones');
  expect([202, 409]).toContain(inicio.estado);
  for (let i = 0; i < 120; i++) {
    const s = await api<{ estado: string; error: string | null }>(page, 'GET', '/api/homologacion/ejecuciones/actual');
    if (s.datos.estado !== 'en_curso') {
      expect(s.datos.error).toBeNull();
      break;
    }
    await page.mouse.move(10 + (i % 50), 10);
    await page.waitForTimeout(2_000);
  }
  await page.reload();
  await expect(page.getByTestId('aviso-desactualizada')).toBeHidden({ timeout: 30_000 });
}

/** Insumo «;» UTF-8 con BOM: metformina (homologada) y enalapril con otra concentración (inconsistente), con descripción única por corrida. */
function insumo(sello: string): { nombre: string; contenido: string } {
  const enc = 'TIPO_DOC;NUM_DOC;FUERZA;COD_ESM;MODALIDAD;FECHA_DISPENSACION;DESC_MEDICAMENTO;CUM;CANTIDAD;VALOR_TOTAL;NUM_FORMULA';
  const filas = [
    `CC;82000001;EJC;BAS02;Dispensación;14/03/2025;METFORMINA 850 MG TABLETA;20010074-2;60;5712,00;HOM${sello}A`,
    `CC;82000002;EJC;BAS02;Dispensación;15/03/2025;ENALAPRIL 20 MG TABLETA LOTE ${sello};20010148-1;30;2259,00;HOM${sello}B`,
    `CC;82000003;ARC;DMBOG;Dispensación;16/04/2025;ENALAPRIL 20 MG TABLETA LOTE ${sello};20010148-1;30;2259,00;HOM${sello}C`,
  ];
  return { nombre: `insumo_homologacion_${sello}.csv`, contenido: '﻿' + [enc, ...filas].join('\r\n') + '\r\n' };
}

async function cargarInsumo(page: Page, sello: string): Promise<void> {
  await page.getByRole('link', { name: 'Cargues del OPL' }).click();
  await page.getByRole('button', { name: 'Cargar archivo estructurado' }).click();
  const dialogo = page.getByRole('dialog', { name: 'Cargar archivo estructurado' });
  const archivo = insumo(sello);
  await dialogo
    .locator('input[type="file"]')
    .setInputFiles({ name: archivo.nombre, mimeType: 'text/csv', buffer: Buffer.from(archivo.contenido, 'utf-8') });
  await dialogo.getByRole('button', { name: 'Subir' }).click();
  await expect(dialogo.getByTestId('mapeo')).toBeVisible({ timeout: 30_000 });
  await dialogo.getByRole('button', { name: 'Confirmar y procesar' }).click();
  await expect(dialogo.getByTestId('estado-asistente')).toHaveText('Aplicado', { timeout: 120_000 });
  await page.keyboard.press('Escape');
}

test('KPI que suman 100 %, búsqueda en la tabla maestra, revisión y aprobación de un inconsistente, y cambio de regla con traza', async ({
  page,
}) => {
  // Publicar el CUM, re-ejecutar si la tabla quedó desactualizada y aplicar el cargue pueden pasar del tiempo por defecto
  test.setTimeout(360_000);
  const sello = `${Date.now()}`;
  await ingresar(page, ...ADMIN);
  await page.getByRole('link', { name: 'Homologación' }).click();
  await expect(page.getByRole('heading', { name: 'Homologación' })).toBeVisible();
  await asegurarCumPublicado(page);
  await reejecutarSiDesactualizada(page);
  await cargarInsumo(page, sello);

  // Calidad: los 6 KPI con dos decimales y coma, que suman 100 %
  await page.getByRole('link', { name: 'Homologación' }).click();
  await page.getByRole('tab', { name: 'Calidad' }).click();
  const kpi = page.getByTestId('kpi-calidad');
  await expect(kpi.locator('[data-testid^="kpi-"]')).toHaveCount(6);
  await expect(kpi.getByTestId('kpi-homologado').getByTestId('porcentaje')).toHaveText(/^\d{1,3},\d{2} %$/);
  await expect(page.getByTestId('suma-calidad')).toContainText('suma 100,00 %');

  // Tabla maestra: buscar «850» muestra la metformina con su fuente, regla y dato homologado
  await page.getByRole('tab', { name: 'Tabla maestra' }).click();
  await page.getByTestId('buscar-tabla').fill('850');
  await page.getByRole('button', { name: 'Buscar', exact: true }).click();
  const tabla = page.getByTestId('tabla-maestra');
  const metformina = tabla.locator('tbody tr', { hasText: 'METFORMINA 850 MG TABLETA' }).first();
  await expect(metformina).toContainText('CUM del INVIMA · vigentes');
  // R-001 (CUM vigente con datos completos) o R-041 (datos completados desde el CUM del INVIMA: el insumo no trae principio activo)
  await expect(metformina).toContainText(/R-001|R-041/);
  await expect(metformina).toContainText('Metformina · 850 mg · tableta');
  await expect(metformina.getByTestId('estado-maestra')).toHaveText('Homologado');

  // Revisión: el enalapril de esta corrida, OPL frente a INVIMA con la concentración resaltada; sin IA; se aprueba
  await page.getByRole('tab', { name: 'Revisión' }).click();
  const pendiente = page
    .getByTestId('tabla-maestra')
    .locator('tbody tr', { hasText: `LOTE ${sello}` })
    .first();
  await expect(pendiente.getByTestId('estado-maestra')).toHaveText('Inconsistente');
  await pendiente.getByRole('button', { name: /Revisar/ }).click();
  const revision = page.getByTestId('revision');
  await expect(revision.getByTestId('comparacion').locator('tr[data-distinto="true"]', { hasText: 'Concentración' })).toBeVisible();
  // La diferencia también se marca con texto (no solo con color)
  await expect(revision.getByTestId('comparacion').locator('tr', { hasText: 'Concentración' }).getByTestId('campo-distinto')).toHaveText(
    '≠ Distinto',
  );
  await expect(revision.getByTestId('sin-sugerencia')).toHaveText('Sin sugerencia automática');
  await revision.getByRole('button', { name: 'Aprobar homologación' }).click();
  await expect(revision).toBeHidden();
  await page.getByRole('tab', { name: 'Tabla maestra' }).click();
  await page.getByTestId('buscar-tabla').fill(`LOTE ${sello}`);
  await page.getByRole('button', { name: 'Buscar', exact: true }).click();
  const aprobado = page.getByTestId('tabla-maestra').locator('tbody tr').first();
  await expect(aprobado.getByTestId('estado-maestra')).toHaveText('Homologado');
  await expect(aprobado).toContainText('MANUAL');

  // Reglas: la tolerancia de R-015 cambia en vivo y el historial muestra el antes y el después; se deja como estaba
  await page.getByRole('tab', { name: 'Reglas y catálogos' }).click();
  for (const valor of ['1', '0']) {
    await page.getByTestId('regla-R-015').getByRole('button', { name: 'Modificar R-015' }).click();
    const editar = page.getByTestId('editar-regla');
    await editar.getByTestId('parametro-tolerancia').fill(valor);
    await editar.getByRole('button', { name: 'Guardar' }).click();
    await expect(editar).toBeHidden();
    const ultimo = page.getByTestId('historial').locator('tbody tr').first();
    await expect(ultimo).toContainText('Regla R-015 modificada (tolerancia de forma farmacéutica)');
    await expect(ultimo).toContainText(`"tolerancia": "${valor}"`);
  }
});
