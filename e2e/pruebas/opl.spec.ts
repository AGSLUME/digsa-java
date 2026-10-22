import { expect, test, type Locator, type Page } from '@playwright/test';
import { ADMIN, api, ingresar, ingresarConClaveInicial, salir, sinDesplazamientoHorizontal, unico } from './apoyo';
import { correoCon, dibujarEn, firmarRol, llenarInicioFin, llenarPlan, PNG } from './apoyo-acta';

// Equivalente de tests/ui/ui_fase4.js: obligaciones del contrato y 5W2H en el checklist, acta con hallazgos, enlace del acta (QR) sin
// usuario con el código de 6 dígitos que llega al correo del OPL, aceptar u objetar, soportes, quién y cómo, firma del plan y la
// decisión del auditor líder sobre la objeción.

test.afterEach(async ({ page }) => salir(page));

const OBLIGACION = 'OB-E2E-01';

function ahora(minutos: number): string {
  const d = new Date(Date.now() - minutos * 60_000);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** Registra (o deja activa) la obligación de prueba desde la pestaña «Obligaciones del contrato» de Maestros. */
async function asegurarObligacion(page: Page): Promise<void> {
  await page.goto('/maestros');
  await page.getByRole('tab', { name: 'Obligaciones del contrato' }).click();
  const tabla = page.getByTestId('tabla-obligaciones');
  await expect(tabla).toBeVisible();
  const fila = page.getByTestId('fila-obligacion-' + OBLIGACION);
  const dialogo = page.getByRole('dialog');
  if ((await fila.count()) === 0) {
    await page.getByRole('button', { name: 'Nueva obligación' }).click();
    await dialogo.getByRole('textbox', { name: /^Código/ }).fill(OBLIGACION);
    await dialogo.getByRole('textbox', { name: /^Texto de la obligación/ }).fill('Entregar los medicamentos en el plazo pactado en la minuta.');
    await dialogo.getByRole('textbox', { name: /^Fuente/ }).fill('Cláusula de prueba e2e');
    await dialogo.getByRole('button', { name: 'Guardar' }).click();
    await expect(page.getByText('Obligación creada.')).toBeVisible();
  } else {
    await fila.getByRole('button', { name: 'Editar la obligación ' + OBLIGACION }).click();
    await dialogo.getByLabel('Activa').check();
    await dialogo.getByRole('button', { name: 'Guardar' }).click();
    await expect(page.getByText('Obligación actualizada.')).toBeVisible();
  }
  await expect(fila).toContainText('Activa');
}

async function llenarCodigo(casillas: Locator, codigo: string): Promise<void> {
  for (let i = 0; i < 6; i++) await casillas.nth(i).fill(codigo[i]!);
}

/** Código de 6 dígitos del último correo de verificación de la visita; con `distintoDe`, espera uno nuevo. */
async function codigoDelCorreo(numero: string, distintoDe?: string): Promise<string> {
  let codigo = '';
  await expect
    .poll(
      async () => {
        const correo = await correoCon(`Asunto: Código de verificación · visita ${numero}`);
        codigo = /<strong>(\d{6})<\/strong>/.exec(correo)?.[1] ?? '';
        return /^\d{6}$/.test(codigo) && codigo !== distintoDe;
      },
      { message: 'llega el código de verificación', timeout: 30_000 },
    )
    .toBe(true);
  return codigo;
}

test('OPL: 5W2H en el checklist, acta con hallazgos y respuesta por el enlace del acta con el código', async ({ page, browser }) => {
  test.setTimeout(300_000);
  await ingresar(page, ...ADMIN);
  const inicial = 'Inicial.Lider.2026';
  const propia = 'Lider.Propia.2026';
  const usuario = unico('lideropl');
  const r = await api<{ id: number }>(page, 'POST', '/api/usuarios', {
    nombre: `Auditor líder ${usuario}`,
    usuario,
    correo: `${usuario}@example.com`,
    documento: '80000003',
    perfiles: [3],
    claveInicial: inicial,
  });
  expect(r.estado).toBe(201);
  await api(page, 'PUT', `/api/maestros/auditores/${r.datos.id}`, { rol: 'quimico_farmaceutico' });
  const lugar = unico('ESM opl ');
  const punto = await api<{ id: number }>(page, 'POST', '/api/maestros/puntos', {
    nombre: lugar,
    unidad: 'ESM',
    fuerza: 'EJC',
    modalidad: 'Dispensación',
    municipio: '05001',
    correos: ['contacto.e2e@example.com'],
  });
  await api(page, 'PUT', '/api/maestros/opl', { nombre: 'Operador logístico de prueba', correos: ['opl.e2e@example.com'] });
  const ejc = await api<{ fuerzas: { codigo: string; nombre: string; sanidad: string | null; correos: string[] }[] }>(page, 'GET', '/api/catalogos');
  const fuerza = ejc.datos.fuerzas.find((f) => f.codigo === 'EJC')!;
  if (fuerza.correos.length === 0) {
    await api(page, 'PUT', '/api/catalogos/fuerzas/EJC', {
      nombre: fuerza.nombre,
      sanidad: fuerza.sanidad,
      correos: ['disan.e2e@example.com'],
      activo: true,
    });
  }
  await asegurarObligacion(page);
  const hoy = ahora(0).slice(0, 10);
  const v = await api<{ id: number; numero: string }>(page, 'POST', '/api/agenda', {
    tipo: 'anual',
    puntoId: punto.datos.id,
    fecha: hoy,
    liderId: r.datos.id,
    otros: [],
    alcance: 'Almacenamiento y obligaciones del contrato.',
    componentes: ['C08'],
  });
  expect(v.estado).toBe(201);
  const { id, numero } = v.datos;
  await salir(page);

  // ---------- El líder diligencia el checklist con un hallazgo de ítem y otro de la obligación ----------
  const ctx = await browser.newContext();
  const p2 = await ctx.newPage();
  await ingresarConClaveInicial(p2, usuario, inicial, propia);
  expect((await api(p2, 'POST', `/api/mis-visitas/${id}/iniciar`)).estado).toBe(200);
  await p2.goto(`/visita/${id}`);
  await expect(p2.getByTestId('formulario-visita')).toBeVisible();
  await expect(p2.getByTestId('item-' + OBLIGACION)).toContainText('Obligación del contrato');

  await llenarInicioFin(p2, ahora(90), ahora(5));
  await p2.getByLabel('Objeto de la visita').fill('Verificar almacenamiento y obligaciones del contrato.');
  await p2.getByRole('button', { name: 'Guardar datos de la visita' }).click();
  await expect(p2.getByText('Datos de la visita guardados.')).toBeVisible();
  const personal = p2.getByTestId('personal-auditado');
  await personal.getByRole('button', { name: 'Agregar persona' }).click();
  await personal.getByTestId('persona-0').getByLabel('Nombre').fill('Regente del punto e2e');
  await personal.getByRole('button', { name: 'Guardar personal' }).click();
  await expect(p2.getByText('Personal auditado guardado.')).toBeVisible();

  const grupos = p2.locator('[data-testid^="calificacion-"]');
  const total = await grupos.count();
  const item = ((await grupos.first().getAttribute('data-testid')) ?? '').replace('calificacion-', '');
  expect(item).not.toBe(OBLIGACION);
  for (let i = 0; i < total; i++) {
    const codigo = ((await grupos.nth(i).getAttribute('data-testid')) ?? '').replace('calificacion-', '');
    const nombre = codigo === item || codigo === OBLIGACION ? 'No cumple' : 'Cumple';
    const opcion = grupos.nth(i).getByText(nombre, { exact: true });
    await opcion.scrollIntoViewIfNeeded();
    await opcion.click();
    await expect(grupos.nth(i).getByRole('radio', { name: nombre, exact: true })).toBeChecked();
  }
  await p2.getByLabel('Hallazgo de ' + item).fill('La nevera no tiene registro diario de temperatura.');
  await p2.getByLabel('Hallazgo de ' + OBLIGACION).fill('Dos entregas llegaron fuera del plazo pactado.');
  await p2.getByTestId('guardar-calificaciones').click();
  await expect(p2.getByText(/Guardado \(\d+ ítems\)/)).toBeVisible();

  // El 5W2H aparece con una fila por hallazgo; con hallazgos el acta exige el plan y sus dos firmas
  const plan = p2.getByTestId('plan-mejoramiento');
  await expect(plan.getByTestId('plan-' + item)).toBeVisible();
  await expect(plan.getByTestId('plan-' + OBLIGACION)).toBeVisible();
  await firmarRol(p2, 'auditor', 'Auditor líder e2e');
  await firmarRol(p2, 'esm', 'Regente del punto e2e');
  await expect(p2.getByTestId('faltantes')).toContainText('fecha del plan');
  await expect(p2.getByTestId('enviar-acta')).toBeDisabled();
  await plan.getByLabel(/^Fecha del plan/).fill(hoy);
  await plan
    .getByTestId('plan-' + item)
    .getByRole('textbox', { name: /^Qué/ })
    .fill('Implementar el registro diario de temperatura.');
  await expect(plan.getByText('El plan tiene cambios sin guardar.')).toBeVisible();
  await expect(p2.getByText('Hay cambios sin guardar: guárdelos antes de enviar el acta.')).toBeVisible();
  await llenarPlan(p2, hoy);
  await firmarRol(p2, 'w5_auditor', 'Auditor líder e2e');
  await firmarRol(p2, 'w5_auditado', 'Regente del punto e2e');
  await expect(p2.getByTestId('faltantes')).toHaveCount(0);
  await sinDesplazamientoHorizontal(p2);

  p2.once('dialog', (d) => d.accept());
  await p2.getByTestId('enviar-acta').click();
  await expect(p2.getByText(/Acta enviada a \d+ destinatarios/)).toBeVisible();
  await expect(p2.getByTestId('estado-visita')).toContainText('Cerrada');
  await salir(p2);
  await ctx.close();

  // ---------- El OPL abre el enlace del acta sin usuario ----------
  const correoActa = await correoCon(`Asunto: Acta de visita ${numero}`);
  const token = /opl-responder\?t=([0-9a-f]{64})/.exec(correoActa)?.[1];
  expect(token, 'el correo del acta trae el enlace del OPL').toBeTruthy();
  const qr = await browser.newContext();
  const opl = await qr.newPage();
  await opl.goto(`/opl-responder?t=${token}`);
  await expect(opl.getByTestId('opl-responder')).toContainText(numero);
  // El token sale de la dirección apenas abre el enlace
  await expect(opl).toHaveURL(/\/opl-responder$/);
  const correos = opl.getByTestId('correos-opl');
  await expect(correos).toContainText('o*****e@example.com');
  await expect(correos).not.toContainText('opl.e2e@example.com');
  await expect(correos).not.toContainText('contacto.e2e');
  await opl.getByRole('radio', { name: 'o*****e@example.com' }).check();
  await opl.getByRole('button', { name: 'Enviar código' }).click();
  await expect(opl.getByTestId('codigo-enviado')).toContainText('Le enviamos un código de 6 dígitos a o*****e@example.com');
  const codigo = await codigoDelCorreo(numero);
  const casillas = opl.locator('[data-testid="codigo-opl"] input');
  // Un código errado no deja pasar
  await llenarCodigo(casillas, codigo === '000000' ? '111111' : '000000');
  await opl.getByRole('button', { name: 'Verificar código' }).click();
  await expect(opl.getByTestId('error-codigo')).toContainText('no es correcto');
  await llenarCodigo(casillas, codigo);
  await opl.getByRole('button', { name: 'Verificar código' }).click();

  const respuesta = opl.getByTestId('respuesta-opl');
  await expect(respuesta).toBeVisible();
  await expect(opl.getByTestId('plazos-opl')).toContainText('5 días hábiles');
  await expect(opl.getByTestId('plazos-opl')).toContainText('3 días hábiles');
  await expect(opl.getByTestId('firma-plan-opl')).toBeVisible();
  await sinDesplazamientoHorizontal(opl);

  // La parte del auditor se ve en solo lectura
  const hItem = opl.getByTestId('hallazgo-opl-' + item);
  const hObl = opl.getByTestId('hallazgo-opl-' + OBLIGACION);
  await hItem.getByText('Plan del auditor (5W2H)').click();
  await expect(hItem).toContainText('Implementar el registro diario de temperatura.');

  await opl.getByLabel(/^Nombre de quien responde/).fill('Coordinadora OPL e2e');
  await opl.getByLabel(/^Cargo de quien responde/).fill('Coordinadora de calidad');
  // Acepta la obligación
  await hObl.getByRole('radio', { name: 'Acepto el hallazgo' }).check();
  await hObl.getByTestId('guardar-respuesta-' + OBLIGACION).click();
  await expect(opl.getByText('Hallazgo aceptado.')).toBeVisible();
  await expect(opl.getByTestId('estado-' + OBLIGACION)).toHaveText('Aceptado');
  // Objeta el ítem con justificación y sube un soporte
  await hItem.getByRole('radio', { name: 'Objeto el hallazgo' }).check();
  await hItem.getByLabel('Justificación de la objeción de ' + item).fill('El registro de temperatura existe en el sistema de información del punto.');
  await hItem.getByTestId('guardar-respuesta-' + item).click();
  await expect(opl.getByText(/Objeción registrada/)).toBeVisible();
  await expect(opl.getByTestId('estado-' + item)).toContainText('Objetado');
  await hItem.getByLabel('Soportes de la objeción de ' + item).setInputFiles([{ name: 'registro.png', mimeType: 'image/png', buffer: PNG }]);
  await hItem.getByTestId('subir-soportes-' + item).click();
  await expect(opl.getByText('1 soporte(s) cargado(s).')).toBeVisible();
  await expect(opl.getByTestId('soportes-' + item)).toContainText('registro.png');

  // Quién y cómo del aceptado
  const qc = opl.getByTestId('quien-como-' + OBLIGACION);
  await qc.getByLabel('Quién de ' + OBLIGACION).fill('Jefe de distribución');
  await qc.getByLabel('Cómo de ' + OBLIGACION).fill('Ajustar la ruta de entregas y controlar los tiempos semanalmente.');
  await opl.getByTestId('guardar-quien-como').click();
  await expect(opl.getByText('Plan guardado: está completo para firmar.')).toBeVisible();

  // Recargar sin el token en la dirección conserva el acceso de la pestaña
  await opl.reload();
  await expect(opl.getByTestId('respuesta-opl')).toBeVisible();
  await expect(opl.getByTestId('quien-como-' + OBLIGACION).getByLabel('Quién de ' + OBLIGACION)).toHaveValue('Jefe de distribución');

  // Decisión D11: con la objeción sin decidir por el auditor líder el plan no se firma
  const firma = opl.getByTestId('firma-plan-opl');
  await firma.getByLabel(/^Nombre de quien firma/).fill('Coordinadora OPL e2e');
  await firma.getByLabel(/^Cargo de quien firma/).fill('Coordinadora de calidad');
  await firma.getByLabel(/^Número de documento/).fill('1020304050');
  await dibujarEn(opl, firma.locator('canvas'));
  await expect(firma.getByTestId('objeciones-por-decidir')).toHaveText(`El plan se firma cuando el auditor líder decida las objeciones de: ${item}.`);
  await expect(opl.getByTestId('firmar-plan-opl')).toBeDisabled();
  await qr.close();

  // ---------- El líder ve la respuesta del OPL y levanta la objeción ----------
  const ctx3 = await browser.newContext();
  const p3 = await ctx3.newPage();
  await ingresar(p3, usuario, propia);
  await p3.goto(`/mis-visitas/visita/${id}`);
  const seccion = p3.getByTestId('respuesta-del-opl');
  await expect(seccion).toBeVisible();
  await expect(seccion.getByTestId('opl-' + OBLIGACION)).toContainText('Aceptado');
  await expect(seccion.getByTestId('opl-' + item)).toContainText('El registro de temperatura existe');
  await seccion
    .getByTestId('opl-' + item)
    .getByRole('button', { name: 'Levantar' })
    .click();
  const dialogo = p3.getByRole('dialog');
  await dialogo.getByLabel(/^Motivo/).fill('El soporte del OPL demuestra el registro diario.');
  await dialogo.getByRole('button', { name: 'Levantar' }).click();
  await expect(p3.getByText(/Hallazgo levantado\./)).toBeVisible();
  await expect(seccion.getByTestId('opl-' + item)).toContainText('Levantado');
  await expect(seccion.getByRole('button', { name: 'Ratificar' })).toHaveCount(0);
  await salir(p3);
  await ctx3.close();

  // ---------- Decidida la objeción, el OPL vuelve por el enlace (código nuevo) y firma el plan ----------
  const qr2 = await browser.newContext();
  const opl2 = await qr2.newPage();
  await opl2.goto(`/opl-responder?t=${token}`);
  await expect(opl2.getByTestId('opl-responder')).toContainText(numero);
  await opl2.getByRole('radio', { name: 'o*****e@example.com' }).check();
  await opl2.getByRole('button', { name: 'Enviar código' }).click();
  await expect(opl2.getByTestId('codigo-enviado')).toBeVisible();
  await llenarCodigo(opl2.locator('[data-testid="codigo-opl"] input'), await codigoDelCorreo(numero, codigo));
  await opl2.getByRole('button', { name: 'Verificar código' }).click();
  await expect(opl2.getByTestId('estado-' + item)).toContainText('Levantado');
  const firma2 = opl2.getByTestId('firma-plan-opl');
  await expect(firma2.getByTestId('objeciones-por-decidir')).toHaveCount(0);
  await firma2.getByLabel(/^Nombre de quien firma/).fill('Coordinadora OPL e2e');
  await firma2.getByLabel(/^Cargo de quien firma/).fill('Coordinadora de calidad');
  await firma2.getByLabel(/^Número de documento/).fill('1020304050');
  await dibujarEn(opl2, firma2.locator('canvas'));
  await opl2.getByTestId('firmar-plan-opl').click();
  await expect(opl2.getByText(/Plan de mejoramiento firmado\./)).toBeVisible();
  await expect(firma2).toContainText('Plan firmado');
  await expect(firma2).not.toContainText('1020304050');
  await qr2.close();
});

test('OPL: el enlace sin token pide abrir el del acta y uno inventado no es válido', async ({ browser }) => {
  const ctx = await browser.newContext();
  const p = await ctx.newPage();
  await p.goto('/opl-responder');
  await expect(p.getByTestId('sin-enlace')).toContainText('Abra el enlace del acta');
  await p.goto('/opl-responder?t=' + 'f'.repeat(64));
  await expect(p.getByTestId('error-enlace')).toContainText('no es válido');
  await expect(p).toHaveURL(/\/opl-responder$/);
  await ctx.close();
});
