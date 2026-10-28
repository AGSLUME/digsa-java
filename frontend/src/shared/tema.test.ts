import { describe, expect, it } from 'vitest';
import css from '../lume.css?raw';
import { APAGADO, BORDE_CAMPO, COMPLEMENTOS, LUME, PALETAS, PLACEHOLDER, TONO, VARIANTES, variablesLume } from './tema';

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

describe('tema LUME: contraste WCAG 2.2 AA', () => {
  it('texto, apagado y placeholder sobre blanco y sobre el fondo de la página', () => {
    for (const fondo of [LUME.white, LUME.bg]) {
      expect(contraste(LUME.text, fondo)).toBeGreaterThanOrEqual(4.5);
      expect(contraste(APAGADO, fondo)).toBeGreaterThanOrEqual(4.5);
      expect(contraste(PLACEHOLDER, fondo)).toBeGreaterThanOrEqual(4.5);
    }
    // Encabezados de tabla y migas (#64748B) solo van sobre blanco o #FAFBFC; las etiquetas de campo usan el apagado AA
    expect(contraste(LUME.textMuted, LUME.white)).toBeGreaterThanOrEqual(4.5);
    expect(contraste(LUME.textMuted, COMPLEMENTOS.encabezadoTabla)).toBeGreaterThanOrEqual(4.5);
  });

  it('borde de los campos a 3:1 (WCAG 1.4.11)', () => {
    expect(contraste(BORDE_CAMPO, LUME.white)).toBeGreaterThanOrEqual(3);
    expect(contraste(BORDE_CAMPO, LUME.bg)).toBeGreaterThanOrEqual(3);
  });

  it('sidebar: texto, títulos de sección y activo', () => {
    expect(contraste(COMPLEMENTOS.sidebarTexto, LUME.blueDark)).toBeGreaterThanOrEqual(4.5);
    expect(contraste(COMPLEMENTOS.sidebarApagado, LUME.blueDark)).toBeGreaterThanOrEqual(4.5);
    expect(contraste(LUME.white, LUME.blueActive)).toBeGreaterThanOrEqual(4.5);
  });

  it('botones: primario, outline (y su hover), peligro y enlaces', () => {
    expect(contraste(LUME.white, LUME.blueActive)).toBeGreaterThanOrEqual(4.5);
    expect(contraste(LUME.white, LUME.blue)).toBeGreaterThanOrEqual(4.5);
    expect(contraste(LUME.blueActive, LUME.white)).toBeGreaterThanOrEqual(4.5);
    expect(contraste(LUME.blue, LUME.infoBg)).toBeGreaterThanOrEqual(4.5);
    expect(contraste(LUME.danger, LUME.white)).toBeGreaterThanOrEqual(4.5);
    expect(contraste(LUME.danger, LUME.dangerBg)).toBeGreaterThanOrEqual(4.5);
  });

  it.each(Object.entries(VARIANTES))('insignia y alerta %s: texto sobre su fondo', (_n, v) => {
    expect(contraste(v.texto, v.fondo)).toBeGreaterThanOrEqual(4.5);
  });

  it.each(['red', 'orange', 'yellow', 'green', 'blue', 'lume'])('texto %s (c="color") sobre blanco', (c) => {
    expect(contraste(PALETAS[c]![TONO]!, LUME.white)).toBeGreaterThanOrEqual(4.5);
  });
});

describe('tema LUME: tokens', () => {
  it('los valores del tema son los de lume.css (única fuente)', () => {
    const variables = Object.fromEntries([...css.matchAll(/--lume-([a-z-]+):\s*([^;]+);/g)].map((m) => [m[1], m[2]!.trim()]));
    const nombre = (k: string) => k.replace(/[A-Z]/g, (l) => '-' + l.toLowerCase());
    for (const [k, v] of Object.entries(LUME)) expect(variables[nombre(k)], k).toBe(v);
    expect(Object.keys(variables)).toHaveLength(Object.keys(LUME).length);
  });

  it('las paletas de Mantine tienen 10 tonos y su tono de texto es el token', () => {
    for (const p of Object.values(PALETAS)) expect(p).toHaveLength(10);
    expect(PALETAS.lume![TONO]).toBe(LUME.blueActive);
    expect(PALETAS.lume![TONO + 1]).toBe(LUME.blue);
    expect(PALETAS.red![TONO]).toBe(LUME.danger);
    expect(PALETAS.orange![TONO]).toBe(LUME.warningText);
    expect(PALETAS.green![TONO]).toBe(LUME.successText);
    expect(PALETAS.blue![TONO]).toBe(LUME.infoText);
    expect(PALETAS.gray![TONO]).toBe(LUME.neutralText);
  });

  it('el texto apagado de Mantine es el de AA', () => {
    const v = variablesLume({} as never).light;
    expect(v['--mantine-color-dimmed']).toBe(APAGADO);
    expect(v['--mantine-color-placeholder']).toBe(PLACEHOLDER);
  });
});
