import { expect, test, type Page } from '@playwright/test';
import { ADMIN, api, dentroDe, ingresar, ingresarConClaveInicial, salir, sinDesplazamientoHorizontal, unico } from './apoyo';

// Propuesta de agenda (docs/especificaciones/propuesta-agenda; equivalente de tests/ui/ui_fase6.js): el coordinador propone el ciclo del
// nivel central, ajusta una fila (queda fija), recalcula, confirma y la visita queda en la agenda. Un auditor no ve la pantalla.

test.afterEach(async ({ page }) => salir(page));

const INICIAL = 'Inicial.Propuesta.2026';

interface Ciclo {
  ciclo: string;
  limite?: string;
}

/** Día hábil siguiente (sin sábados ni domingos; los festivos los rechaza el servidor y se prueba el siguiente). */
function siguienteDiaSemana(fecha: string): string {
  const d = new Date(fecha + 'T12:00:00');
  do d.setDate(d.getDate() + 1);
  while (d.getDay() === 0 || d.getDay() === 6);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

async function crearUsuario(page: Page, prefijo: string, perfil: number, nombre: string): Promise<{ id: number; usuario: string }> {
  const usuario = unico(prefijo);
  const r = await api<{ id: number }>(page, 'POST', '/api/usuarios', {
    nombre: `${nombre} ${usuario}`,
    usuario,
    correo: `${usuario}@example.com`,
    documento: '80000002',
    perfiles: [perfil],
    claveInicial: INICIAL,
  });
  expect(r.estado, JSON.stringify(r.datos)).toBe(201);
  return { id: r.datos.id, usuario };
}

test('propuesta del nivel central: proponer, ajustar, recalcular, confirmar y ver la visita en la agenda; el auditor no entra', async ({
  page,
  browser,
}) => {
  page.on('dialog', (d) => void d.accept());

  // Datos por la API (administrador): coordinador, químico farmacéutico e ingeniero con base en Bogotá y un punto NC propio
  await ingresar(page, ...ADMIN);
  const coord = await crearUsuario(page, 'coordpro', 2, 'Coordinador propuesta');
  const qf = await crearUsuario(page, 'qfpro', 3, 'QF propuesta');
  const ing = await crearUsuario(page, 'ingpro', 3, 'Ingeniero propuesta');
  for (const [a, rol] of [
    [qf, 'quimico_farmaceutico'],
    [ing, 'ingeniero_sistemas'],
  ] as const) {
    expect((await api(page, 'PUT', `/api/maestros/auditores/${a.id}`, { rol, municipioBase: '11001' })).estado).toBe(200);
  }
  const nombrePunto = unico('Nivel central e2e ');
  const punto = await api<{ id: number }>(page, 'POST', '/api/maestros/puntos', {
    nombre: nombrePunto,
    unidad: 'NC',
    fuerza: 'EJC',
    municipio: '11001',
    correos: ['central.opl@example.com'],
    auditorAsignado: qf.id,
  });
  expect(punto.estado, JSON.stringify(punto.datos)).toBe(201);
  const puntoId = punto.datos.id;
  await salir(page);

  // El coordinador entra y abre la propuesta desde el menú
  await ingresarConClaveInicial(page, coord.usuario, INICIAL, 'Coordinador.Propuesta.2026');
  await page.getByRole('link', { name: 'Propuesta de agenda' }).click();
  await expect(page.getByRole('heading', { name: 'Propuesta de agenda' })).toBeVisible();

  // Si falta el inicio del contrato (o el año de contrato vigente está por terminar), lo registra en Catálogos › Parámetros
  const datos = await api<{ inicioContrato: string | null; ciclos: Ciclo[] }>(page, 'GET', '/api/propuesta');
  const central = datos.datos.ciclos.find((c) => c.ciclo === 'central');
  if (!datos.datos.inicioContrato || !central?.limite || central.limite < dentroDe(60)) {
    if (!datos.datos.inicioContrato) await expect(page.getByTestId('sin-inicio-contrato')).toContainText('Catálogos › Parámetros');
    await page.getByRole('link', { name: 'Catálogos', exact: true }).click();
    await page.getByRole('tab', { name: 'Parámetros' }).click();
    await page.getByLabel('Valor de inicio_contrato').fill(dentroDe(-30));
    await page.getByRole('button', { name: 'Guardar inicio_contrato' }).click();
    await expect(page.getByText('Parámetro actualizado.')).toBeVisible();
    await page.getByRole('link', { name: 'Propuesta de agenda' }).click();
  }
  await expect(page.getByTestId('anio-contrato')).toHaveValue(/^Año de contrato \d+ \(\d{2}\/\d{2}\/\d{4} – \d{2}\/\d{2}\/\d{4}\)$/);

  // Explicación plegada con los parámetros vigentes
  await page.getByRole('button', { name: 'Cómo arma la propuesta' }).click();
  const como = page.getByTestId('como-arma');
  await expect(como).toContainText('Nada se programa ni se notifica hasta confirmar');
  await expect(como).toContainText('coordenadas del DANE');
  await expect(como.getByTestId('visitas-por-dia')).toHaveText(/^\d+$/);
  await expect(como.getByTestId('km-mismo-dia')).toHaveText(/^\d+,\d{2}$/); // km con 2 decimales (Anexo 5 num. 2.4)

  // Cuatro ciclos; el del nivel central tiene al menos el punto nuevo por proponer
  for (const c of ['linea_base', 'anual', 'farmacia', 'central']) await expect(page.getByTestId('ciclo-' + c)).toBeVisible();
  const tarjeta = page.getByTestId('ciclo-central');
  await expect(tarjeta.getByTestId('cobertura')).toContainText('Faltan');
  const generar = page.waitForResponse((r) => r.url().endsWith('/api/propuesta/generar'));
  await tarjeta.getByRole('button', { name: /^Proponer (agenda|de nuevo)$/ }).click();
  expect((await generar).status()).toBe(200);

  // El borrador: el QF asignado lidera y el ingeniero acompaña (nivel central); nada se programa todavía
  const vista = page.getByTestId('propuesta');
  await expect(vista.getByTestId('estado-propuesta')).toHaveText('Borrador');
  await expect(vista.getByTestId('resumen-propuesta')).toContainText('visita(s)');
  const tabla = vista.getByTestId('tabla-propuesta');
  const fila = tabla.getByTestId('fila-propuesta-' + puntoId);
  await expect(fila).toBeVisible();
  await expect(fila.getByRole('textbox', { name: 'Auditor líder de ' + nombrePunto })).toHaveValue(new RegExp(qf.usuario));
  await expect(fila).toContainText(ing.usuario);
  await expect(fila).toContainText('Auditor asignado al punto');
  await expect(fila.getByTestId('calculada')).toBeVisible();

  // Ajuste: la fecha pasa a otro día hábil y la fila queda fija
  const campoFecha = fila.getByLabel('Fecha de ' + nombrePunto);
  let fecha = (await campoFecha.inputValue()) || dentroDe(25);
  let guardada = false;
  for (let intento = 0; intento < 5 && !guardada; intento++) {
    fecha = siguienteDiaSemana(fecha);
    await campoFecha.fill(fecha);
    const ajuste = page.waitForResponse((r) => r.url().includes('/api/propuesta/items/') && r.request().method() === 'PUT');
    await tabla.getByRole('button', { name: 'Guardar el ajuste de ' + nombrePunto }).click();
    guardada = (await ajuste).status() === 200; // un festivo responde 422 y se prueba el siguiente día
  }
  expect(guardada).toBe(true);
  await expect(fila.getByTestId('fija')).toBeVisible();
  await expect(fila).toContainText('Ajustado por el coordinador');

  // Recalcular respeta lo fijado
  const recalcular = page.waitForResponse((r) => r.url().endsWith('/recalcular'));
  await vista.getByRole('button', { name: 'Recalcular' }).click();
  expect((await recalcular).status()).toBe(200);
  await expect(fila.getByTestId('fija')).toBeVisible();
  await expect(fila.getByLabel('Fecha de ' + nombrePunto)).toHaveValue(fecha);

  // En el celular, tarjetas sin desplazamiento horizontal
  await page.setViewportSize({ width: 360, height: 800 });
  await expect(vista.getByTestId('tarjeta-propuesta-' + puntoId)).toBeVisible();
  await expect(tabla).toBeHidden();
  await sinDesplazamientoHorizontal(page);
  await page.setViewportSize({ width: 1280, height: 720 });

  // Confirmar (con la confirmación previa): programa y notifica; cada fila muestra su resultado
  const confirmar = page.waitForResponse((r) => r.url().endsWith('/confirmar'));
  await vista.getByRole('button', { name: 'Confirmar y programar' }).click();
  expect((await confirmar).status()).toBe(200);
  await expect(vista.getByTestId('resultado-confirmacion')).toContainText(/Se programaron \d+ visita\(s\)/);
  await expect(vista.getByTestId('estado-propuesta')).toHaveText('Confirmada');
  const resultado = fila.getByTestId('resultado-fila');
  await expect(resultado).toContainText(/VS-\d{8}-\d{4,}/);
  await expect(fila).toContainText(fecha.split('-').reverse().join('/')); // en pantalla, dd/mm/aaaa (decisión UI-3)
  const numero = (/VS-\d{8}-\d{4,}/.exec((await resultado.textContent()) ?? '') ?? [''])[0];

  // La visita queda en la agenda
  await resultado.getByRole('link', { name: numero }).click();
  const detalle = page.getByTestId('detalle-visita');
  await expect(detalle).toContainText(numero);
  await expect(detalle).toContainText(nombrePunto);
  await expect(page.getByTestId('estado-visita')).toHaveText(/Notificada|Programada/);
  // En la lista de la agenda, en el mes de la fecha ajustada
  await page.goto('/agenda');
  const hoy = new Date();
  const meses = (Number(fecha.slice(0, 4)) - hoy.getFullYear()) * 12 + Number(fecha.slice(5, 7)) - 1 - hoy.getMonth();
  for (let i = 0; i < meses; i++) await page.getByRole('button', { name: 'Mes siguiente' }).click();
  await expect(page.getByTestId('fila-visita-' + numero)).toContainText(nombrePunto);

  // La cobertura del ciclo la cuenta como agendada
  const despues = await api<{ ciclos: (Ciclo & { agendadas: number })[] }>(page, 'GET', '/api/propuesta');
  expect(despues.datos.ciclos.find((c) => c.ciclo === 'central')?.agendadas).toBeGreaterThanOrEqual(1);

  // Un auditor no ve la pantalla ni sus datos
  const otro = await browser.newContext();
  const p2 = await otro.newPage();
  await ingresarConClaveInicial(p2, ing.usuario, INICIAL, 'Ingeniero.Propuesta.2026');
  await expect(p2.getByRole('link', { name: 'Mis visitas' })).toBeVisible();
  await expect(p2.getByRole('link', { name: 'Propuesta de agenda' })).toHaveCount(0);
  await p2.goto('/propuesta');
  await expect(p2.getByText('Acceso restringido.')).toBeVisible();
  expect((await api(p2, 'GET', '/api/propuesta')).estado).toBe(403);
  await salir(p2);
  await otro.close();
});
