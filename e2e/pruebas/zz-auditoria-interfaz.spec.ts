import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Page } from '@playwright/test';
import { api, ingresarComoAdminPropio, ingresarConClaveInicial, salir, unico } from './apoyo';

/**
 * Auditoría de la interfaz (fase U0): recorre las pantallas de cada perfil en 360, 768 y 1280 px, guarda capturas de página completa y
 * un resumen JSON con el desbordamiento horizontal, los errores de consola, los encabezados, las tablas y los campos sin etiqueta.
 * No es una prueba de regresión: solo corre con DIGSA_AUDITORIA=1 y al final de la suite (nombre «zz-»), cuando la base local ya
 * tiene datos de las demás pruebas. Las capturas quedan en test-results/auditoria (no se versionan).
 */

test.skip(!process.env.DIGSA_AUDITORIA, 'Solo con DIGSA_AUDITORIA=1');
test.setTimeout(30 * 60_000);

const ANCHOS = [
  { nombre: '360', width: 360, height: 780 },
  { nombre: '768', width: 768, height: 1024 },
  { nombre: '1280', width: 1280, height: 900 },
];
const SALIDA = process.env.DIGSA_AUDITORIA_DIR ?? join(process.cwd(), 'test-results', 'auditoria');
const INICIAL = 'Inicial.Auditoria.2026';
const PROPIA = 'Propia.Auditoria.2026';

interface Medicion {
  ruta: string;
  ancho: string;
  desbordaPx: number;
  encabezados: string[];
  tablas: number;
  camposSinEtiqueta: string[];
  botonesSinNombre: number;
  erroresConsola: string[];
}

function archivo(perfil: string, ruta: string, ancho: string): string {
  const limpio = (ruta === '/' ? 'inicio' : ruta.replace(/^\//, '').replace(/[^a-z0-9]+/gi, '_')) || 'inicio';
  return join(SALIDA, `${perfil.replace(/[^a-z0-9]+/gi, '_')}__${limpio}__${ancho}.png`);
}

async function medir(page: Page, ruta: string, ancho: string, errores: string[]): Promise<Medicion> {
  const datos = await page.evaluate(() => {
    const visibles = (sel: string) => Array.from(document.querySelectorAll<HTMLElement>(sel)).filter((e) => e.offsetParent !== null);
    const sinEtiqueta = visibles('input:not([type=hidden]), select, textarea')
      .filter((e) => {
        const i = e as HTMLInputElement;
        if (i.getAttribute('aria-label') || i.getAttribute('aria-labelledby') || i.closest('label')) return false;
        return !(i.id && document.querySelector(`label[for="${CSS.escape(i.id)}"]`));
      })
      .map((e) => `${e.tagName.toLowerCase()}[${(e as HTMLInputElement).type ?? ''}] ${(e as HTMLInputElement).placeholder ?? ''}`.trim());
    const botonesSinNombre = visibles('button, a[href]').filter(
      (b) => !(b.textContent ?? '').trim() && !b.getAttribute('aria-label') && !b.getAttribute('title'),
    ).length;
    return {
      desbordaPx: document.documentElement.scrollWidth - document.documentElement.clientWidth,
      encabezados: visibles('h1, h2, h3').map((h) => `${h.tagName}: ${(h.textContent ?? '').trim().slice(0, 80)}`),
      tablas: visibles('table').length,
      camposSinEtiqueta: sinEtiqueta,
      botonesSinNombre,
    };
  });
  return { ruta, ancho, ...datos, erroresConsola: [...errores] };
}

async function capturarRuta(page: Page, perfil: string, ruta: string, mediciones: Medicion[]): Promise<string[]> {
  const errores: string[] = [];
  const alError = (m: { type(): string; text(): string }) => {
    if (m.type() === 'error') errores.push(m.text().slice(0, 200));
  };
  page.on('console', alError);
  const detalles: string[] = [];
  try {
    for (const a of ANCHOS) {
      errores.length = 0;
      await page.setViewportSize({ width: a.width, height: a.height });
      await page.goto(ruta);
      // La sesión consulta al servidor periódicamente: la red nunca queda del todo quieta, así que la espera es acotada
      await page.waitForLoadState('networkidle', { timeout: 5000 }).catch(() => undefined);
      await page.waitForTimeout(600);
      mediciones.push(await medir(page, ruta, a.nombre, errores));
      await page.screenshot({ path: archivo(perfil, ruta, a.nombre), fullPage: true, timeout: 30_000 });
      if (a.nombre === '1280') {
        // Primer detalle de visita enlazado desde la pantalla (agenda, mis visitas, calendario, OPL)
        const enlaces = page.locator('a[href*="/visita/"]');
        if ((await enlaces.count()) > 0) {
          const enlace = await enlaces
            .first()
            .getAttribute('href', { timeout: 2000 })
            .catch(() => null);
          if (enlace) detalles.push(enlace);
        }
      }
    }
  } finally {
    page.off('console', alError);
  }
  return detalles;
}

test('U0 · capturas por perfil en 360, 768 y 1280 px', async ({ page, browser }) => {
  mkdirSync(SALIDA, { recursive: true });
  const mediciones: Medicion[] = [];
  const menus: Record<string, { ruta: string; titulo: string; grupo: string }[]> = {};

  // Pantallas públicas: ingreso y código MFA
  for (const a of ANCHOS) {
    await page.setViewportSize({ width: a.width, height: a.height });
    await page.goto('/');
    await page.screenshot({ path: archivo('publico', '/ingreso', a.nombre), fullPage: true });
    mediciones.push(await medir(page, '/ingreso', a.nombre, []));
  }

  await ingresarComoAdminPropio(page, 'audit');
  const lista = await api<{ perfiles: { id: number; nombre: string }[] }>(page, 'GET', '/api/usuarios');
  const perfiles = lista.datos.perfiles ?? [];
  const creados: { perfil: string; usuario: string }[] = [];
  for (const p of perfiles) {
    const usuario = unico('aud');
    const r = await api(page, 'POST', '/api/usuarios', {
      nombre: `Auditoria ${p.nombre}`.slice(0, 60),
      usuario,
      correo: `${usuario}@example.com`,
      perfiles: [p.id],
      claveInicial: INICIAL,
    });
    if (r.estado === 201) creados.push({ perfil: p.nombre, usuario });
    else
      mediciones.push({
        ruta: `(no se creó ${p.nombre}: ${r.estado})`,
        ancho: '-',
        desbordaPx: 0,
        encabezados: [],
        tablas: 0,
        camposSinEtiqueta: [],
        botonesSinNombre: 0,
        erroresConsola: [JSON.stringify(r.datos).slice(0, 200)],
      });
  }
  await salir(page);

  const yaCapturadas = new Set<string>();
  for (const c of creados) {
    const ctx = await browser.newContext();
    const p = await ctx.newPage();
    try {
      await ingresarConClaveInicial(p, c.usuario, INICIAL, PROPIA);
      const sesion = await api<{ menu: { ruta: string; titulo: string; grupo: string }[] }>(p, 'GET', '/api/sesion');
      menus[c.perfil] = sesion.datos.menu ?? [];
      // Menú abierto en celular, una vez por perfil
      await p.setViewportSize({ width: 360, height: 780 });
      await p.goto('/');
      await p.getByRole('button', { name: 'Menú' }).click();
      await p.waitForTimeout(400);
      await p.screenshot({ path: archivo(c.perfil, '/menu-abierto', '360') });
      const rutas = ['/', ...menus[c.perfil]!.filter((m) => m.ruta !== 'inicio').map((m) => '/' + m.ruta)];
      for (const ruta of rutas) {
        const clave = `${ruta}`;
        if (ruta !== '/' && yaCapturadas.has(clave)) continue;
        yaCapturadas.add(clave);
        const detalles = await capturarRuta(p, c.perfil, ruta, mediciones);
        for (const d of detalles.slice(0, 1)) {
          const base = d.replace(/\d+$/, ':id');
          if (yaCapturadas.has(base)) continue;
          yaCapturadas.add(base);
          await capturarRuta(p, c.perfil, d, mediciones);
        }
      }
    } finally {
      await salir(p);
      await ctx.close();
    }
  }

  writeFileSync(join(SALIDA, 'resumen.json'), JSON.stringify({ menus, mediciones }, null, 2), 'utf8');
  expect(creados.length).toBeGreaterThan(0);
});
