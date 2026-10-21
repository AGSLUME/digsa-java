import { expect, test } from '@playwright/test';
import { ADMIN, api, ingresar, ingresarConClaveInicial, salir, unico } from './apoyo';
import { firmarRol, llenarPlan, PNG } from './apoyo-acta';

// Anexo 5, prueba 12 (formulario), prueba 10 (acta en PDF y reabrir) y prueba 13 (reportes con descarga).

test.afterEach(async ({ page }) => salir(page));

function ahora(minutos: number): string {
  const d = new Date(Date.now() - minutos * 60_000);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

test('pruebas 12, 10 y 13: formulario, evidencias, firmas, acta, reabrir y reportes con descarga', async ({ page, browser }) => {
  test.setTimeout(240_000);
  await ingresar(page, ...ADMIN);
  const inicial = 'Inicial.Lider.2026';
  const usuario = unico('lidere2e');
  const r = await api<{ id: number }>(page, 'POST', '/api/usuarios', {
    nombre: `Auditor líder ${usuario}`, usuario, correo: `${usuario}@example.com`, documento: '80000002', perfiles: [3], claveInicial: inicial,
  });
  expect(r.estado).toBe(201);
  await api(page, 'PUT', `/api/maestros/auditores/${r.datos.id}`, { rol: 'quimico_farmaceutico' });
  const lugar = unico('ESM formulario ');
  const punto = await api<{ id: number }>(page, 'POST', '/api/maestros/puntos', {
    nombre: lugar, unidad: 'ESM', fuerza: 'EJC', modalidad: 'Dispensación', municipio: '05001', correos: ['contacto.e2e@example.com'],
  });
  await api(page, 'PUT', '/api/maestros/opl', { nombre: 'Operador logístico de prueba', correos: ['opl.e2e@example.com'] });
  const ejc = await api<{ fuerzas: { codigo: string; nombre: string; sanidad: string | null; correos: string[] }[] }>(page, 'GET', '/api/catalogos');
  const fuerza = ejc.datos.fuerzas.find((f) => f.codigo === 'EJC')!;
  if (fuerza.correos.length === 0) {
    await api(page, 'PUT', '/api/catalogos/fuerzas/EJC', { nombre: fuerza.nombre, sanidad: fuerza.sanidad, correos: ['disan.e2e@example.com'], activo: true });
  }
  const hoy = ahora(0).slice(0, 10);
  const v = await api<{ id: number; numero: string }>(page, 'POST', '/api/agenda', {
    tipo: 'anual', puntoId: punto.datos.id, fecha: hoy, liderId: r.datos.id, otros: [], alcance: 'Almacenamiento e inventarios.', componentes: ['C08'],
  });
  expect(v.estado).toBe(201);
  const { id, numero } = v.datos;

  // El líder (equipo de uno) entra, inicia y diligencia el formulario
  const ctx = await browser.newContext();
  const p2 = await ctx.newPage();
  await ingresarConClaveInicial(p2, usuario, inicial, 'Lider.Propia.2026');
  expect((await api(p2, 'POST', `/api/mis-visitas/${id}/iniciar`)).estado).toBe(200);

  await p2.goto(`/visita/${id}`);
  await expect(p2.getByTestId('formulario-visita')).toBeVisible();
  await expect(p2.getByTestId('campo-numero')).toHaveValue(numero);
  await expect(p2.getByTestId('campo-auditor')).toHaveValue(new RegExp(usuario));
  await expect(p2.getByTestId('campo-lugar')).toHaveValue(new RegExp(lugar));


  await p2.getByLabel('Inicio real').fill(ahora(90));
  await p2.getByLabel('Fin real').fill(ahora(5));
  await p2.getByLabel('Objeto de la visita').fill('Verificar almacenamiento e inventarios del servicio farmacéutico.');
  await p2.getByRole('button', { name: 'Guardar datos de la visita' }).click();
  await expect(p2.getByText('Datos de la visita guardados.')).toBeVisible();


  const personal = p2.getByTestId('personal-auditado');
  await personal.getByRole('button', { name: 'Agregar persona' }).click();
  await personal.getByTestId('persona-0').getByLabel('Nombre').fill('Regente del punto e2e');
  await personal.getByRole('button', { name: 'Guardar personal' }).click();
  await expect(p2.getByText('Personal auditado guardado.')).toBeVisible();


  // Calificar todo: el primero No cumple con su hallazgo y los demás Cumple
  const grupos = p2.locator('[data-testid^="calificacion-"]');
  const total = await grupos.count();
  expect(total).toBeGreaterThan(0);
  const primero = ((await grupos.first().getAttribute('data-testid')) ?? '').replace('calificacion-', '');
  for (let i = 0; i < total; i++) {
    const opcion = grupos.nth(i).getByText(i === 0 ? 'No cumple' : 'Cumple', { exact: true });
    await opcion.scrollIntoViewIfNeeded();
    await opcion.click();
    await expect(grupos.nth(i).getByRole('radio', { name: i === 0 ? 'No cumple' : 'Cumple', exact: true })).toBeChecked();
  }

  await p2.getByLabel('Hallazgo de ' + primero).fill('La nevera no tiene registro diario de temperatura.');
  await p2.getByTestId('guardar-calificaciones').click();
  await expect(p2.getByText(/Guardado \(\d+ ítems\)/)).toBeVisible();


  // Prueba 12: varias evidencias en un solo envío
  const evid = p2.getByTestId('evidencias-' + primero);
  await evid.locator('input[type="file"]').setInputFiles([
    { name: 'foto1.png', mimeType: 'image/png', buffer: PNG },
    { name: 'foto2.png', mimeType: 'image/png', buffer: PNG },
  ]);
  await p2.getByTestId('subir-' + primero).click();
  await expect(p2.getByText('2 archivo(s) cargado(s).')).toBeVisible();
  await expect(evid).toContainText('foto1.png');
  await expect(evid).toContainText('foto2.png');


  await firmarRol(p2, 'auditor', 'Auditor líder e2e');
  await firmarRol(p2, 'esm', 'Regente del punto e2e');

  // Con un hallazgo, el acta exige el plan 5W2H (fecha, qué y fin de cada fila) y sus dos firmas
  await expect(p2.getByTestId('faltantes')).toContainText('fecha del plan');
  await expect(p2.getByTestId('enviar-acta')).toBeDisabled();
  await llenarPlan(p2, hoy);
  await firmarRol(p2, 'w5_auditor', 'Auditor líder e2e');
  await firmarRol(p2, 'w5_auditado', 'Regente del punto e2e');
  await expect(p2.getByTestId('faltantes')).toHaveCount(0);


  // Prueba 10: acta en PDF y cierre
  p2.once('dialog', (d) => d.accept());
  await p2.getByTestId('enviar-acta').click();
  await expect(p2.getByText(/Acta enviada a \d+ destinatarios/)).toBeVisible();
  await expect(p2.getByTestId('estado-visita')).toContainText('Cerrada');
  await expect(p2.getByTestId('actas')).toContainText('Ver acta');
  await salir(p2);
  await ctx.close();


  // Prueba 10: el coordinador (administrador) reabre la visita desde la pantalla. Su sesión venció mientras trabajaba el líder
  // (las pruebas corren con 1 minuto de inactividad): vuelve a ingresar
  await salir(page);
  await ingresar(page, ...ADMIN);
  await page.goto(`/agenda/visita/${id}`);
  await page.getByTestId('reabrir').click();
  const dialogo = page.getByRole('dialog');
  await dialogo.getByRole('textbox', { name: 'Motivo' }).click();
  await page.getByRole('option', { name: 'Faltó cargar un anexo o soporte' }).click();
  await dialogo.getByLabel('Descripción').fill('Falta el soporte de la cadena de frío.');
  await dialogo.getByRole('button', { name: 'Reabrir visita' }).click();
  await expect(page.getByTestId('estado-visita')).toHaveText('En ejecución');


  // Prueba 13: reporte de detalle con la visita y descarga en Excel
  await page.goto('/reportes');
  await page.getByRole('button', { name: 'Generar reporte' }).click();
  await expect(page.getByTestId('encabezado-reporte')).toContainText('Usuario:');
  await expect(page.getByTestId('tabla-reporte')).toContainText(numero);
  const descarga = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Descargar Excel' }).click();
  expect((await descarga).suggestedFilename()).toMatch(/^reporte_detalle_.*\.xlsx$/);
});
