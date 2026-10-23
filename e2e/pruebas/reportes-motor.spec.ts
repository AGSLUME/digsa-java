import { expect, test } from '@playwright/test';
import { ADMIN, api, ingresar, salir, sinDesplazamientoHorizontal, unico } from './apoyo';

// Módulo de reportes, fase R1 (docs/especificaciones/reportes): catálogo con buscador, filtros de una, varias o todas las opciones
// (Anexo 5, 2.11), agrupación por bimestres, encabezado de 2.9 con establecimiento, filtros, fuente y página, y descarga.

test.afterEach(async ({ page }) => salir(page));

interface Opcion {
  valor: string;
  etiqueta: string;
}

test('R1: catálogo, varias fuerzas, agrupación por bimestres, encabezado 2.9 y descarga CSV', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  const lugarEjc = unico('ESM motor EJC ');
  const lugarArc = unico('ESM motor ARC ');
  for (const [nombre, fuerza] of [
    [lugarEjc, 'EJC'],
    [lugarArc, 'ARC'],
  ]) {
    const r = await api(page, 'POST', '/api/maestros/puntos', {
      nombre,
      unidad: 'ESM',
      fuerza,
      modalidad: 'Dispensación',
      municipio: '05001',
      correos: [],
    });
    expect(r.estado, JSON.stringify(r.datos)).toBe(201);
  }
  const opciones = await api<Record<string, Opcion[]>>(page, 'GET', '/api/reportes/lugar/opciones');
  const nombreDe = (codigo: string) => opciones.datos.fuerza!.find((o) => o.valor === codigo)!.etiqueta;

  await page.goto('/reportes');
  const catalogo = page.getByTestId('catalogo-reportes');
  await expect(catalogo).toContainText('Visitas y hallazgos');
  await expect(catalogo).toContainText('Detalle de visitas');
  await catalogo.getByLabel('Buscar reporte').fill('proceso');
  await expect(page.getByTestId('reporte-proceso')).toBeVisible();
  await expect(page.getByTestId('reporte-detalle')).toHaveCount(0);
  await catalogo.getByLabel('Buscar reporte').fill('');
  await page.getByTestId('reporte-lugar').click();
  await expect(page.getByTestId('filtros-reporte')).toContainText('Consolidado de visitas por lugar');

  await page.getByRole('textbox', { name: 'Fuerza', exact: true }).click();
  await page.getByRole('option', { name: nombreDe('EJC'), exact: true }).click();
  await page.getByRole('option', { name: nombreDe('ARC'), exact: true }).click();
  await page.keyboard.press('Escape');
  await page.getByRole('textbox', { name: 'Agrupar por' }).click();
  await page.getByRole('option', { name: 'Bimestres' }).click();
  await page.getByRole('button', { name: 'Generar reporte' }).click();

  const encabezado = page.getByTestId('encabezado-reporte');
  await expect(encabezado).toContainText('Título: Consolidado de visitas por lugar');
  await expect(encabezado).toContainText('agrupado por bimestres');
  await expect(encabezado).toContainText('Usuario:');
  await expect(encabezado).toContainText('Establecimiento: Todos');
  // Los valores van ordenados por código (ARC antes que EJC): el mismo reporte da el mismo encabezado sin importar el orden elegido
  await expect(encabezado).toContainText(`Fuerza: ${nombreDe('ARC')}, ${nombreDe('EJC')}`);
  await expect(encabezado).toContainText('Fuente: Suite DIGSA');
  await expect(encabezado).toContainText(/Página: 1 de \d+/); // la base de las e2e acumula visitas entre navegadores
  await expect(page.getByTestId('tabla-reporte').locator('th').first()).toHaveText('Periodo');

  const descarga = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Descargar CSV' }).click();
  expect((await descarga).suggestedFilename()).toMatch(/^reporte_lugar_.*\.csv$/);

  // En el celular la página no se desplaza a lo ancho: la tabla tiene su propio desplazamiento
  await page.setViewportSize({ width: 390, height: 844 });
  await sinDesplazamientoHorizontal(page);
});

test('R2: el código de verificación del encabezado y el archivo descargado se verifican; uno alterado no', async ({ page }, info) => {
  await ingresar(page, ...ADMIN);
  await page.goto('/reportes');
  await page.getByRole('button', { name: 'Generar reporte' }).click();
  const encabezado = page.getByTestId('encabezado-reporte');
  await expect(encabezado).toContainText('Código de verificación: RPT-');
  const codigo = /RPT-\d{8}-[A-Z0-9]{8}/.exec((await encabezado.textContent()) ?? '')![0];

  const descarga = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Descargar CSV' }).click();
  const archivo = info.outputPath('reporte.csv');
  await (await descarga).saveAs(archivo);

  await page.getByRole('tab', { name: 'Verificar reporte' }).click();
  await page.getByRole('textbox', { name: 'Código' }).fill(codigo.toLowerCase());
  await page.getByRole('button', { name: 'Consultar' }).click();
  await expect(page.getByTestId('resultado-verificacion')).toContainText(`El código ${codigo} corresponde a un reporte generado`);
  await expect(page.getByTestId('registro-verificado')).toContainText('Detalle de visitas');
  await page.getByRole('button', { name: 'Regenerar y comparar' }).click();
  await expect(page.getByTestId('resultado-verificacion')).toContainText('los datos no han cambiado');
  // Al escribir otro código el resultado anterior desaparece; un código inexistente lo dice
  await page.getByRole('textbox', { name: 'Código' }).fill('RPT-20990101-AAAAAAAA');
  await expect(page.getByTestId('resultado-verificacion')).toHaveCount(0);
  await page.getByRole('button', { name: 'Consultar' }).click();
  await expect(page.getByText('No hay ningún reporte con el código RPT-20990101-AAAAAAAA.')).toBeVisible();

  await page.locator('input[type="file"]').setInputFiles(archivo);
  await page.getByRole('button', { name: 'Verificar archivo' }).click();
  await expect(page.getByTestId('resultado-verificacion').first()).toContainText('Íntegro: el archivo es el que generó la Suite DIGSA');

  const fs = await import('node:fs');
  const alterado = info.outputPath('alterado.csv');
  // Se cambia una cifra (los registros del encabezado)
  fs.writeFileSync(
    alterado,
    fs.readFileSync(archivo, 'utf8').replace(/Registros;(\d+)/, (_m, n: string) => `Registros;${Number(n) + 1}`),
  );
  await page.locator('input[type="file"]').setInputFiles(alterado);
  await expect(page.getByTestId('resultado-verificacion')).toHaveCount(0); // el resultado del archivo anterior ya no se ve
  await page.getByRole('button', { name: 'Verificar archivo' }).click();
  await expect(page.getByTestId('resultado-verificacion').first()).toContainText('Alterado o no generado por la Suite DIGSA');
  await expect(page.getByTestId('registro-verificado').first()).toContainText('Huella del archivo subido');

  // En el celular las huellas de 64 caracteres no desbordan la página
  await page.setViewportSize({ width: 390, height: 844 });
  await sinDesplazamientoHorizontal(page);
});

test('R3: «Descargas del día» lista lo que se pidió hoy y lo vuelve a bajar sin regenerarlo', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  await page.goto('/reportes');
  await page.getByTestId('reporte-proceso').click();
  await page.getByRole('button', { name: 'Generar reporte' }).click();
  await expect(page.getByTestId('encabezado-reporte')).toContainText('Código de verificación: RPT-');
  const descarga = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Descargar TXT' }).click();
  const primera = await descarga;
  expect(primera.suggestedFilename()).toMatch(/^reporte_proceso_.*\.txt$/);

  await page.getByRole('tab', { name: 'Descargas del día' }).click();
  const tabla = page.getByTestId('tabla-descargas');
  const fila = tabla.getByRole('row').filter({ hasText: 'Consolidado de visitas por proceso' }).first();
  await expect(fila).toContainText('TXT');
  await expect(fila).toContainText('Listo');
  await expect(fila).toContainText('23:59');
  const otra = page.waitForEvent('download');
  await fila.getByRole('button', { name: 'Descargar' }).click();
  expect((await otra).suggestedFilename()).toBe(primera.suggestedFilename());

  await page.setViewportSize({ width: 390, height: 844 });
  await sinDesplazamientoHorizontal(page);
});

test('R4: tableros y logs en el catálogo; logs en PDF y envío a varios correos', async ({ page }) => {
  await ingresar(page, ...ADMIN);
  await page.goto('/reportes');
  const catalogo = page.getByTestId('catalogo-reportes');
  await expect(catalogo).toContainText('Dashboard de riesgo por ESM');
  await expect(catalogo).toContainText('Usuarios y seguridad');
  await page.getByTestId('reporte-logs_auditoria').click();
  await page.getByRole('button', { name: 'Generar reporte' }).click();
  const encabezado = page.getByTestId('encabezado-reporte');
  await expect(encabezado).toContainText('Logs de auditoría');
  await expect(encabezado).toContainText('Fuente: Bitácora de la Suite DIGSA');
  const descarga = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Descargar PDF' }).click();
  expect((await descarga).suggestedFilename()).toMatch(/^reporte_logs_auditoria_.*\.pdf$/);

  await page.getByTestId('reporte-dashboard_riesgo').click();
  await page.getByRole('button', { name: 'Generar reporte' }).click();
  await expect(page.getByTestId('tabla-reporte')).toContainText('Puntaje por ESM');
  // Los pesos y umbrales van al final del reporte: con muchos ESM quedan fuera de la primera página de la pantalla
  const riesgo = await api<{ filas: unknown[][] }>(page, 'GET', '/api/reportes/dashboard_riesgo');
  expect(JSON.stringify(riesgo.datos.filas)).toContain('Pesos y umbrales');
  const a = unico('comite') + '@example.com';
  const b = unico('digsa') + '@example.com';
  await page.getByLabel('Enviar a').fill(`${a}, ${b}`);
  await page.getByRole('button', { name: 'Enviar por correo' }).click();
  await expect(page.getByText(`Reporte enviado a ${a}, ${b}.`)).toBeVisible();

  // R5: el Administrador ve los tiempos de generación (2.7)
  await page.getByRole('tab', { name: 'Tiempos' }).click();
  const tiempos = page.getByTestId('tabla-tiempos');
  await expect(tiempos).toContainText('Logs de auditoría');
  await expect(tiempos).toContainText('PDF');
  await page.setViewportSize({ width: 390, height: 844 });
  await sinDesplazamientoHorizontal(page);
});
