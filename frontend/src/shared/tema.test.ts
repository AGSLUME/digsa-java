import { describe, expect, it } from 'vitest';
import { PALETAS, PLACEHOLDER, TONO, TOKENS, variablesDigsa } from './tema';

function luminancia(hex: string): number {
  const [r, g, b] = [1, 3, 5]
    .map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!;
}

function contraste(a: string, b: string): number {
  const [x, y] = [luminancia(a), luminancia(b)].sort((m, n) => n - m);
  return (x! + 0.05) / (y! + 0.05);
}

/** Color con opacidad sobre blanco (el fondo suave que Mantine pone a las insignias y alertas `light`). */
function sobreBlanco(hex: string, opacidad: number): string {
  return (
    '#' +
    [1, 3, 5]
      .map((i) => Math.round(255 + (parseInt(hex.slice(i, i + 2), 16) - 255) * opacidad))
      .map((c) => c.toString(16).padStart(2, '0'))
      .join('')
  );
}

describe('tema: contraste WCAG 2.2 AA', () => {
  it.each(['digsa', 'red', 'orange', 'yellow', 'green', 'blue', 'gray'])(
    'el tono %s usado como texto llega a 4,5:1 sobre blanco, el fondo y su fondo suave',
    (c) => {
      const texto = PALETAS[c]![TONO]!;
      expect(contraste(texto, TOKENS.superficie)).toBeGreaterThanOrEqual(4.5);
      expect(contraste(texto, TOKENS.fondo)).toBeGreaterThanOrEqual(4.5);
      expect(contraste(texto, sobreBlanco(texto, 0.12))).toBeGreaterThanOrEqual(4.5);
    },
  );

  it('las insignias del mockup (crit, adv, info, ok) son legibles sobre su fondo', () => {
    for (const [t, f] of [
      [TOKENS.crit, TOKENS.critFondo],
      [TOKENS.adv, TOKENS.advFondo],
      [TOKENS.info, TOKENS.infoFondo],
      [TOKENS.ok, TOKENS.okFondo],
    ] as const) {
      expect(contraste(t, f)).toBeGreaterThanOrEqual(4.5);
    }
  });

  it('el texto apagado (dimmed) llega a 4,5:1 y el borde de los campos a 3:1', () => {
    expect(PALETAS.gray![6]).toBe(TOKENS.apagado);
    expect(contraste(TOKENS.apagado, TOKENS.superficie)).toBeGreaterThanOrEqual(4.5);
    expect(contraste(TOKENS.apagado, TOKENS.fondo)).toBeGreaterThanOrEqual(4.5);
    expect(contraste(TOKENS.bordeCampo, TOKENS.superficie)).toBeGreaterThanOrEqual(3);
    expect(contraste(TOKENS.bordeCampo, TOKENS.fondo)).toBeGreaterThanOrEqual(3);
  });

  it('el texto de ejemplo de los campos (placeholder) llega a 4,5:1, también en un campo con error', () => {
    expect(variablesDigsa({} as never).light['--mantine-color-placeholder']).toBe(PLACEHOLDER);
    for (const fondo of [TOKENS.superficie, TOKENS.fondo, TOKENS.errorCampoFondo]) expect(contraste(PLACEHOLDER, fondo)).toBeGreaterThanOrEqual(4.5);
  });

  it('la marca sobre blanco y el texto claro sobre la marca (cabecera)', () => {
    expect(contraste(TOKENS.marca, TOKENS.superficie)).toBeGreaterThanOrEqual(7);
    expect(contraste(TOKENS.marcaTinta, TOKENS.marca)).toBeGreaterThanOrEqual(7);
  });
});

describe('tema: tokens del mockup', () => {
  it('cada paleta tiene 10 tonos y pone el token del mockup en el tono que usa Mantine', () => {
    for (const p of Object.values(PALETAS)) expect(p).toHaveLength(10);
    expect(PALETAS.digsa![TONO]).toBe(TOKENS.marca);
    expect(PALETAS.red![TONO]).toBe(TOKENS.crit);
    expect(PALETAS.orange![TONO]).toBe(TOKENS.adv);
    expect(PALETAS.green![TONO]).toBe(TOKENS.ok);
    expect(PALETAS.blue![TONO]).toBe(TOKENS.info);
  });

  it('expone los tokens como variables CSS --digsa-*', () => {
    const { variables } = variablesDigsa({} as never);
    expect(variables['--digsa-fondo']).toBe('#F3F5F2');
    expect(variables['--digsa-borde-campo']).toBe(TOKENS.bordeCampo);
    expect(variables['--digsa-crit-fondo']).toBe(TOKENS.critFondo);
  });
});
