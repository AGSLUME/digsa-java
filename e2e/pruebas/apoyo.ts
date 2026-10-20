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

export function unico(prefijo: string): string {
  return `${prefijo}${Date.now() % 1_000_000}${Math.floor(Math.random() * 100)}`;
}

/** Sin desplazamiento horizontal de la página. */
export async function sinDesplazamientoHorizontal(page: Page): Promise<void> {
  const r = await page.evaluate(() => ({ ancho: document.documentElement.scrollWidth, visible: document.documentElement.clientWidth }));
  expect(r.ancho, `scrollWidth ${r.ancho} > clientWidth ${r.visible}`).toBeLessThanOrEqual(r.visible);
}
