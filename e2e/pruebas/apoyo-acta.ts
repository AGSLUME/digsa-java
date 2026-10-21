import { expect, type Locator, type Page } from '@playwright/test';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

/** PNG de 2x2 px para las evidencias y los soportes (el servidor valida el tipo por el contenido). */
export const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAFklEQVR4nGP8z8DwnwEIGBkZGRgYGAAqBAIDL2QaUQAAAABJRU5ErkJggg==',
  'base64',
);

/** Carpeta del correo a archivo del arranque local (digsa.local.raiz es la raíz del repositorio). */
const CORREOS = resolve(process.cwd(), '..', '.local', 'correos');

/** Dibuja un trazo en un panel de firma. */
export async function dibujarEn(page: Page, panel: Locator): Promise<void> {
  await panel.scrollIntoViewIfNeeded();
  const caja = (await panel.boundingBox())!;
  await page.mouse.move(caja.x + 20, caja.y + caja.height - 20);
  await page.mouse.down();
  await page.mouse.move(caja.x + caja.width / 2, caja.y + 20, { steps: 8 });
  await page.mouse.move(caja.x + caja.width - 20, caja.y + caja.height - 30, { steps: 8 });
  await page.mouse.up();
}

/** Firma de un rol del formulario (acta o plan de mejoramiento) en su diálogo. */
export async function firmarRol(page: Page, rol: string, nombre: string): Promise<void> {
  await page.getByTestId('firma-' + rol).getByRole('button', { name: 'Firmar' }).click();
  const dialogo = page.getByRole('dialog');
  await dialogo.getByLabel('Nombre').fill(nombre);
  await dibujarEn(page, dialogo.locator('canvas').first());
  await dialogo.getByRole('button', { name: 'Guardar firma' }).click();
  await expect(page.getByTestId('firma-' + rol)).toContainText(nombre);
}

/** Completa el 5W2H del auditor (fecha del plan, qué y fin de cada fila) y lo guarda. */
export async function llenarPlan(page: Page, fecha: string): Promise<void> {
  const plan = page.getByTestId('plan-mejoramiento');
  await plan.getByLabel(/^Fecha del plan/).fill(fecha);
  const filas = plan.locator('[data-testid^="plan-"]');
  const n = await filas.count();
  expect(n).toBeGreaterThan(0);
  const fin = new Date(Date.now() + 30 * 86_400_000);
  const p = (x: number) => String(x).padStart(2, '0');
  for (let i = 0; i < n; i++) {
    const que = filas.nth(i).getByRole('textbox', { name: /^Qué/ });
    if (!(await que.inputValue())) await que.fill('Implementar el control diario del soporte.');
    await filas.nth(i).getByLabel(/^Fin/).fill(`${fin.getFullYear()}-${p(fin.getMonth() + 1)}-${p(fin.getDate())}`);
  }
  await page.getByTestId('guardar-plan').click();
  await expect(page.getByText('Plan de mejoramiento guardado.')).toBeVisible();
  await expect(plan.getByText('Sin cambios por guardar en el plan.')).toBeVisible();
}

/** Último correo (a archivo) cuya cabecera contiene el texto; espera a que llegue. */
export async function correoCon(texto: string): Promise<string> {
  let encontrado = '';
  await expect
    .poll(
      () => {
        const archivos = readdirSync(CORREOS)
          .filter((f) => f.endsWith('.html'))
          .map((f) => ({ f, t: statSync(join(CORREOS, f)).mtimeMs }))
          .sort((a, b) => b.t - a.t)
          .map((x) => x.f);
        for (const f of archivos.slice(0, 200)) {
          const contenido = readFileSync(join(CORREOS, f), 'utf8');
          if (contenido.split('\n', 1)[0]!.includes(texto)) {
            encontrado = contenido;
            return true;
          }
        }
        return false;
      },
      { message: 'llega el correo «' + texto + '»', timeout: 20_000 },
    )
    .toBe(true);
  return encontrado;
}
