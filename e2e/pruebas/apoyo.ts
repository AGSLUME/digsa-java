import { expect, type Page } from '@playwright/test';

export const ADMIN = (process.env.DIGSA_E2E_ADMIN ?? 'admin.e2e:Admin.E2E.Prueba.2026').split(':') as [string, string];

/** Ingreso por la pantalla: usuario, contraseña y el código (en local, el servidor lo muestra en el mensaje). */
export async function ingresar(page: Page, usuario: string, clave: string): Promise<void> {
  await page.goto('/');
  await page.getByLabel('Usuario').fill(usuario);
  await page.getByLabel('Contraseña').fill(clave);
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click();
  const mensaje = page.getByText(/local: \d{6}/);
  await expect(mensaje).toBeVisible();
  const codigo = /local: (\d{6})/.exec((await mensaje.textContent()) ?? '')?.[1];
  expect(codigo).toBeTruthy();
  const casillas = page.locator('[data-testid="codigo"] input');
  for (let i = 0; i < 6; i++) await casillas.nth(i).fill(codigo![i]!);
  await page.getByRole('button', { name: 'Verificar e ingresar' }).click();
  await expect(page.getByRole('button', { name: 'Salir', exact: true })).toBeVisible();
}

/**
 * Cierra la sesión del navegador con «Salir» por la API. Desde la decisión 7.1 una sesión abierta bloquea un segundo ingreso del
 * mismo usuario, así que cada prueba deja libre al administrador compartido. No falla si la página ya no tiene sesión.
 */
export async function salir(page: Page): Promise<void> {
  try {
    if (page.isClosed() || !page.url().startsWith('http')) return;
    await page.evaluate(async () => {
      const t = /(?:^|; )XSRF-TOKEN=([^;]+)/.exec(document.cookie)?.[1];
      await fetch('/api/auth/salir', { method: 'POST', headers: t ? { 'X-XSRF-TOKEN': decodeURIComponent(t) } : {} });
    });
  } catch {
    // sin sesión o página cerrada
  }
}

export function unico(prefijo: string): string {
  return `${prefijo}${Date.now() % 1_000_000}${Math.floor(Math.random() * 100)}`;
}

/** Sin desplazamiento horizontal de la página. */
export async function sinDesplazamientoHorizontal(page: Page): Promise<void> {
  const r = await page.evaluate(() => ({ ancho: document.documentElement.scrollWidth, visible: document.documentElement.clientWidth }));
  expect(r.ancho, `scrollWidth ${r.ancho} > clientWidth ${r.visible}`).toBeLessThanOrEqual(r.visible);
}

/** Llamada a la API desde la página (misma cookie de sesión y token CSRF), para preparar datos sin pasar por la pantalla. */
export async function api<T = Record<string, unknown>>(page: Page, metodo: string, ruta: string, cuerpo?: unknown): Promise<{ estado: number; datos: T }> {
  return page.evaluate(
    async ({ metodo, ruta, cuerpo }) => {
      const t = /(?:^|; )XSRF-TOKEN=([^;]+)/.exec(document.cookie)?.[1];
      const r = await fetch(ruta, {
        method: metodo,
        headers: { 'Content-Type': 'application/json', ...(t ? { 'X-XSRF-TOKEN': decodeURIComponent(t) } : {}) },
        body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo),
      });
      return { estado: r.status, datos: await r.json().catch(() => ({})) };
    },
    { metodo, ruta, cuerpo },
  ) as Promise<{ estado: number; datos: T }>;
}

/** Ingreso de un usuario creado con contraseña inicial: la cambia en la pantalla y sigue con el código. */
export async function ingresarConClaveInicial(page: Page, usuario: string, inicial: string, nueva: string): Promise<void> {
  await page.goto('/');
  await page.getByLabel('Usuario').fill(usuario);
  await page.getByLabel('Contraseña').fill(inicial);
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click();
  await page.getByRole('textbox', { name: 'Contraseña nueva', exact: true }).fill(nueva);
  await page.getByLabel('Confirme la contraseña nueva').fill(nueva);
  await page.getByRole('button', { name: 'Cambiar contraseña y continuar' }).click();
  const mensaje = page.getByText(/local: \d{6}/);
  await expect(mensaje).toBeVisible();
  const codigo = /local: (\d{6})/.exec((await mensaje.textContent()) ?? '')?.[1] ?? '';
  const casillas = page.locator('[data-testid="codigo"] input');
  for (let i = 0; i < 6; i++) await casillas.nth(i).fill(codigo[i]!);
  await page.getByRole('button', { name: 'Verificar e ingresar' }).click();
  await expect(page.getByRole('button', { name: 'Salir', exact: true })).toBeVisible();
}

/** Fecha AAAA-MM-DD dentro de n días (hora de Bogotá aproximada por la del equipo). */
export function dentroDe(dias: number): string {
  const d = new Date();
  d.setDate(d.getDate() + dias);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
