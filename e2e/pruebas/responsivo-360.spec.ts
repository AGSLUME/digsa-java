import { expect, test } from '@playwright/test';
import { api, ingresarComoAdminPropio, salir, sinDesplazamientoHorizontal } from './apoyo';

// U5 · Anexo 5 num. 1.18 (L318): smartphone. Todas las pantallas del menú (el administrador las ve todas) a 360 px, en cada motor, sin
// desplazamiento horizontal de la página: las tablas se desplazan o se apilan dentro de su caja (TablaAdaptable), nunca la página. Se
// navega como en el celular, con «Menú» y el enlace de cada pantalla, que se descarga al abrirla (división por rutas).

test.afterEach(async ({ page }) => salir(page));

test('celular (360 px): ninguna pantalla del menú desborda a lo ancho', async ({ page }) => {
  test.setTimeout(300_000);
  await ingresarComoAdminPropio(page, 'r360');
  const sesion = await api<{ menu: { ruta: string; titulo: string }[] }>(page, 'GET', '/api/sesion');
  const pantallas = sesion.datos.menu.filter((m) => m.ruta !== 'inicio');
  expect(pantallas.length).toBeGreaterThan(10);
  await page.setViewportSize({ width: 360, height: 780 });
  await page.goto('/');
  await sinDesplazamientoHorizontal(page);
  const modulos = page.getByRole('navigation', { name: 'Módulos' });
  for (const p of pantallas) {
    await test.step(p.titulo, async () => {
      await page.getByRole('button', { name: 'Menú' }).click();
      await modulos.getByRole('link', { name: p.titulo, exact: true }).click();
      await expect(page).toHaveURL(new RegExp(`/${p.ruta}$`));
      // La pantalla terminó de descargarse (sin la espera de la división por rutas); los encabezados los revisa el recorrido de capturas
      await expect(page.getByRole('main').getByRole('status', { name: 'Cargando la pantalla' })).toHaveCount(0);
      await page.waitForLoadState('networkidle', { timeout: 5000 }).catch(() => undefined);
      await sinDesplazamientoHorizontal(page);
    });
  }
});
