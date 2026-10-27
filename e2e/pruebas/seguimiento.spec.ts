import { expect, test, type Page } from '@playwright/test';
import { ADMIN, api, dentroDe, ingresar, ingresarConClaveInicial, salir, sinDesplazamientoHorizontal, unico } from './apoyo';
import { firmarRol, llenarInicioFin, llenarPlan } from './apoyo-acta';

// Equivalente de tests/ui/ui_seg.js: al cerrar una visita con un hallazgo cuyo plan vence en 30 días se programa sola la visita de
// seguimiento; el líder la inicia, ve el aviso y el hallazgo anterior, lo deja en No cumple y corre la fecha fin (prórroga con
// justificación); la coordinación y la DIGSA aprueban la prórroga y «Prestadores con hallazgos» muestra el seguimiento.

test.afterEach(async ({ page }) => salir(page));

const TIPO_SEGUIMIENTO = 'Seguimiento a plan de mejoramiento';
const JUSTIFICACION = 'El proveedor del formato de registro entrega en diciembre.';

function ahora(minutos: number): string {
  const d = new Date(Date.now() - minutos * 60_000);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** Datos de la visita, personal auditado y firmas del acta (lo que pide el envío además del checklist y el plan). */
async function datosYFirmas(page: Page, objeto: string): Promise<void> {
  await llenarInicioFin(page, ahora(90), ahora(5));
  await page.getByLabel('Objeto de la visita').fill(objeto);
  await page.getByRole('button', { name: 'Guardar datos de la visita' }).click();
  await expect(page.getByText('Datos de la visita guardados.')).toBeVisible();
  const personal = page.getByTestId('personal-auditado');
  await personal.getByRole('button', { name: 'Agregar persona' }).click();
  await personal.getByTestId('persona-0').getByLabel('Nombre').fill('Regente del punto e2e');
  await personal.getByRole('button', { name: 'Guardar personal' }).click();
  await expect(page.getByText('Personal auditado guardado.')).toBeVisible();
  await firmarRol(page, 'auditor', 'Auditor líder e2e');
  await firmarRol(page, 'esm', 'Regente del punto e2e');
}

async function enviarActa(page: Page): Promise<void> {
  await expect(page.getByTestId('faltantes')).toHaveCount(0);
  page.once('dialog', (d) => d.accept());
  await page.getByTestId('enviar-acta').click();
  await expect(page.getByText(/Acta enviada a \d+ destinatarios/)).toBeVisible();
  await expect(page.getByTestId('estado-visita')).toContainText('Cerrada');
}

test('Seguimiento: programación automática, hallazgo anterior, prórroga con justificación, doble aprobación y prestadores con hallazgos', async ({
  page,
  browser,
}) => {
  test.setTimeout(420_000);
  await ingresar(page, ...ADMIN);
  const inicial = 'Inicial.Seguim.2026';
  const propia = 'Seguim.Propia.2026';
  const crear = async (prefijo: string, nombre: string, perfil: number) => {
    const usuario = unico(prefijo);
    const r = await api<{ id: number }>(page, 'POST', '/api/usuarios', {
      nombre: `${nombre} ${usuario}`,
      usuario,
      correo: `${usuario}@example.com`,
      documento: '80000009',
      perfiles: [perfil],
      claveInicial: inicial,
    });
    expect(r.estado, JSON.stringify(r.datos)).toBe(201);
    return { usuario, id: r.datos.id };
  };
  const lider = await crear('liderseg', 'Auditor líder', 3);
  await api(page, 'PUT', `/api/maestros/auditores/${lider.id}`, { rol: 'quimico_farmaceutico' });
  const coord = await crear('coordseg', 'Coordinadora', 2);
  const digsa = await crear('digsaseg', 'Analista DIGSA', 5);
  const lugar = unico('ESM seguimiento ');
  const punto = await api<{ id: number }>(page, 'POST', '/api/maestros/puntos', {
    nombre: lugar,
    unidad: 'ESM',
    fuerza: 'EJC',
    modalidad: 'Dispensación',
    municipio: '05001',
    correos: ['contacto.seg@example.com'],
  });
  expect(punto.estado).toBe(201);
  await api(page, 'PUT', '/api/maestros/opl', { nombre: 'Operador logístico de prueba', correos: ['opl.e2e@example.com'] });
  const cat = await api<{ fuerzas: { codigo: string; nombre: string; sanidad: string | null; correos: string[] }[] }>(page, 'GET', '/api/catalogos');
  const fuerza = cat.datos.fuerzas.find((f) => f.codigo === 'EJC')!;
  if (fuerza.correos.length === 0) {
    await api(page, 'PUT', '/api/catalogos/fuerzas/EJC', {
      nombre: fuerza.nombre,
      sanidad: fuerza.sanidad,
      correos: ['disan.e2e@example.com'],
      activo: true,
    });
  }
  const hoy = dentroDe(0);
  const v = await api<{ id: number; numero: string }>(page, 'POST', '/api/agenda', {
    tipo: 'anual',
    puntoId: punto.datos.id,
    fecha: hoy,
    liderId: lider.id,
    otros: [],
    alcance: 'Almacenamiento.',
    componentes: ['C08'],
  });
  expect(v.estado).toBe(201);
  const { id, numero } = v.datos;
  await salir(page);

  // ---------- Visita de origen: un hallazgo con su plan a 30 días y el acta enviada ----------
  const ctx = await browser.newContext();
  const p2 = await ctx.newPage();
  await ingresarConClaveInicial(p2, lider.usuario, inicial, propia);
  expect((await api(p2, 'POST', `/api/mis-visitas/${id}/iniciar`)).estado).toBe(200);
  await p2.goto(`/visita/${id}`);
  await expect(p2.getByTestId('formulario-visita')).toBeVisible();
  await expect(p2.getByTestId('aviso-seguimiento')).toHaveCount(0);
  await datosYFirmas(p2, 'Verificar el almacenamiento.');
  const grupos = p2.locator('[data-testid^="calificacion-"]');
  const total = await grupos.count();
  const item = ((await grupos.first().getAttribute('data-testid')) ?? '').replace('calificacion-', '');
  for (let i = 0; i < total; i++) {
    const nombre = i === 0 ? 'No cumple' : 'Cumple';
    const opcion = grupos.nth(i).getByText(nombre, { exact: true });
    await opcion.scrollIntoViewIfNeeded();
    await opcion.click();
    await expect(grupos.nth(i).getByRole('radio', { name: nombre, exact: true })).toBeChecked();
  }
  const hallazgo = 'La nevera no tiene registro diario de temperatura.';
  await p2.getByLabel('Hallazgo de ' + item).fill(hallazgo);
  await p2.getByTestId('guardar-calificaciones').click();
  await expect(p2.getByText(/Guardado \(\d+ ítems\)/)).toBeVisible();
  await llenarPlan(p2, hoy); // fecha fin a 30 días
  await firmarRol(p2, 'w5_auditor', 'Auditor líder e2e');
  await firmarRol(p2, 'w5_auditado', 'Regente del punto e2e');
  await enviarActa(p2);

  // ---------- Aparece la visita de seguimiento y el líder la inicia ----------
  await p2.goto('/mis-visitas');
  const tarjeta = p2.locator('[data-testid^="fila-visita-"]').filter({ hasText: lugar }).filter({ hasText: TIPO_SEGUIMIENTO });
  await expect(tarjeta).toHaveCount(1);
  await tarjeta.getByRole('button', { name: /^Abrir la visita / }).click();
  await p2.getByRole('button', { name: 'Iniciar visita' }).click();
  await expect(p2).toHaveURL(/\/visita\/\d+$/);
  const sid = Number(/\/visita\/(\d+)$/.exec(p2.url())?.[1]);
  expect(sid).not.toBe(id);
  await expect(p2.getByTestId('formulario-visita')).toBeVisible();
  const numeroSeguimiento = await p2.getByTestId('campo-numero').inputValue();
  const aviso = p2.getByTestId('aviso-seguimiento');
  await expect(aviso).toContainText('Primera visita de seguimiento');
  await expect(aviso).toContainText(numero);
  // Solo se recalifica el hallazgo anterior, desde cero, viendo lo que se encontró
  await expect(p2.locator('[data-testid^="calificacion-"]')).toHaveCount(1);
  await expect(p2.getByTestId('anterior-' + item)).toContainText(hallazgo);
  await expect(p2.getByTestId('calificacion-' + item).getByRole('radio', { name: 'No cumple', exact: true })).not.toBeChecked();
  await sinDesplazamientoHorizontal(p2);

  // ---------- Sigue en No cumple y corre la fecha fin: se pide la justificación ----------
  await datosYFirmas(p2, 'Verificar el plan de mejoramiento.');
  await p2
    .getByTestId('calificacion-' + item)
    .getByText('No cumple', { exact: true })
    .click();
  await p2.getByLabel('Hallazgo de ' + item).fill('Sigue sin el registro diario de temperatura.');
  await p2.getByTestId('guardar-calificaciones').click();
  await expect(p2.getByText(/Guardado \(\d+ ítems?\)/)).toBeVisible();
  const plan = p2.getByTestId('plan-mejoramiento');
  const fila = plan.getByTestId('plan-' + item);
  await expect(fila.getByLabel('Fin anterior')).toHaveValue(dentroDe(30));
  await expect(fila.getByLabel('Quién (del OPL)')).toHaveJSProperty('readOnly', true);
  await plan.getByLabel(/^Fecha del plan/).fill(hoy);
  await expect(fila.getByTestId('prorroga-' + item)).toHaveCount(0);
  await fila.getByLabel(/^Fin\s*\*?$/).fill(dentroDe(45));
  const justificacion = fila.getByRole('textbox', { name: /^Justificación de la prórroga/ });
  await expect(justificacion).toBeVisible();
  await expect(plan.getByTestId('faltantes-plan')).toContainText('justificación de la prórroga');
  // Guardado sin justificar, el envío del acta lo pide
  await p2.getByTestId('guardar-plan').click();
  await expect(p2.getByText('Plan de mejoramiento guardado.')).toBeVisible();
  await expect(p2.getByTestId('faltantes')).toContainText('Justifique la prórroga');
  await expect(p2.getByTestId('enviar-acta')).toBeDisabled();
  await justificacion.fill(JUSTIFICACION);
  await p2.getByTestId('guardar-plan').click();
  await expect(plan.getByText('Sin cambios por guardar en el plan.')).toBeVisible();
  await expect(plan.getByTestId('faltantes-plan')).toHaveCount(0);
  await firmarRol(p2, 'w5_auditor', 'Auditor líder e2e');
  await firmarRol(p2, 'w5_auditado', 'Regente del punto e2e');
  await enviarActa(p2);

  // El líder ve el seguimiento en «Prestadores con hallazgos», también en el celular sin desplazamiento horizontal
  await p2.goto('/hallazgos');
  const visitaLider = p2.getByTestId('visita-hallazgos-' + numeroSeguimiento);
  await expect(visitaLider).toBeVisible();
  await expect(visitaLider.getByRole('link', { name: 'Abrir checklist' })).toBeVisible();
  await expect(visitaLider.getByRole('link', { name: 'Detalle de la visita' })).toBeVisible();
  await p2.setViewportSize({ width: 360, height: 800 });
  // Recargar en el tamaño del celular: medir durante la transición del menú lateral da un ancho falso
  await p2.reload();
  await expect(visitaLider).toBeVisible();
  await expect(visitaLider.getByTestId('tarjetas-hallazgos')).toBeVisible();
  await expect(visitaLider.getByTestId('tabla-hallazgos')).toBeHidden();
  await sinDesplazamientoHorizontal(p2);
  await salir(p2);
  await ctx.close();

  // ---------- La coordinación aprueba la prórroga: sigue solicitada ----------
  const decidir = async (usuario: string, mensaje: RegExp) => {
    const c = await browser.newContext();
    const p = await c.newPage();
    await ingresarConClaveInicial(p, usuario, inicial, propia);
    await p.getByRole('link', { name: 'Prórrogas del plan' }).click();
    const filaP = p.getByTestId('tabla-prorrogas').getByRole('row').filter({ hasText: numeroSeguimiento });
    await expect(filaP).toHaveCount(1);
    await expect(filaP).toContainText(item);
    await expect(filaP).toContainText(JUSTIFICACION);
    // Fechas en pantalla como dd/mm/aaaa (decisión UI-3)
    await expect(filaP).toContainText(dentroDe(30).split('-').reverse().join('/'));
    await expect(filaP).toContainText(dentroDe(45).split('-').reverse().join('/'));
    p.once('dialog', (d) => d.accept());
    await filaP.getByRole('button', { name: /^Aprobar la prórroga/ }).click();
    await expect(p.getByText(mensaje)).toBeVisible();
    return { c, p };
  };
  const co = await decidir(coord.usuario, /falta la de la DIGSA/);
  const filaCoord = co.p.getByTestId('tabla-prorrogas').getByRole('row').filter({ hasText: numeroSeguimiento });
  await expect(filaCoord.getByTestId('decision-coord')).toContainText('Aprobó');
  await expect(filaCoord.getByTestId('decision-coord')).toContainText(/\d{2}\/\d{2}\/\d{4} \d{2}:\d{2}/);
  await expect(filaCoord.getByRole('button', { name: /^Aprobar/ })).toHaveCount(0);
  await expect(filaCoord).toContainText('Solicitada');
  await salir(co.p);
  await co.c.close();

  // ---------- La DIGSA también la aprueba: queda aprobada ----------
  const di = await decidir(digsa.usuario, /aprobada por la coordinación y la DIGSA/);
  const p4 = di.p;
  await expect(p4.getByTestId('tabla-prorrogas').getByRole('row').filter({ hasText: numeroSeguimiento })).toHaveCount(0);
  await p4.getByTestId('filtro-prorrogas').click();
  await p4.getByRole('option', { name: 'Aprobadas' }).click();
  const aprobada = p4.getByTestId('tabla-prorrogas').getByRole('row').filter({ hasText: numeroSeguimiento });
  await expect(aprobada).toContainText('Aprobada');
  await expect(aprobada.getByTestId('decision-digsa')).toContainText('Aprobó');

  // ---------- «Prestadores con hallazgos» muestra el seguimiento con su origen y la siguiente visita ----------
  await p4.getByRole('link', { name: 'Prestadores con hallazgos' }).click();
  const prestador = p4.locator('[data-testid^="prestador-"]').filter({ hasText: lugar });
  await expect(prestador).toHaveCount(1);
  const visita = prestador.getByTestId('visita-hallazgos-' + numeroSeguimiento);
  await expect(visita.getByTestId('cadena')).toHaveText(`Seguimiento N° 1 de la visita ${numero}`);
  await expect(visita.getByTestId('situacion')).toHaveText('Seguimiento programado');
  // La fecha del siguiente seguimiento es la fecha fin solicitada o el siguiente día hábil
  await expect(visita.getByTestId('siguiente')).not.toContainText('ninguna programada');
  await expect(visita.getByTestId('tabla-hallazgos')).toContainText(item);
  await expect(visita.getByTestId('tabla-hallazgos')).toContainText(dentroDe(45).split('-').reverse().join('/'));
  // La visita de origen ya tiene un seguimiento cerrado: no se lista
  await expect(prestador.getByTestId('visita-hallazgos-' + numero)).toHaveCount(0);
  // La DIGSA consulta: no abre el checklist ni es líder
  await expect(visita.getByRole('link', { name: 'Abrir checklist' })).toHaveCount(0);
  await expect(visita.getByRole('link', { name: 'Detalle de la visita' })).toHaveCount(0);
  await sinDesplazamientoHorizontal(p4);
  await salir(p4);
  await di.c.close();
});
