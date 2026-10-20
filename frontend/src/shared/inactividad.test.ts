import { describe, expect, it } from 'vitest';
import { estadoAviso, mmss } from './inactividad';

// Portado de tests/ui/ui_sesion.js (aviso con cuenta regresiva y vuelta al ingreso) como regla pura.
describe('aviso de inactividad', () => {
  const t0 = 1_800_000_000_000;

  it('con 10 minutos: sin aviso hasta faltar 60 s; con aviso desde ahí', () => {
    expect(estadoAviso(t0, t0 + 539_000, 10).mostrar).toBe(false);
    const e = estadoAviso(t0, t0 + 540_000, 10);
    expect(e.mostrar).toBe(true);
    expect(e.restantes).toBe(60);
  });

  it('con 1 minuto el aviso sale a la mitad del plazo', () => {
    expect(estadoAviso(t0, t0 + 29_000, 1).mostrar).toBe(false);
    expect(estadoAviso(t0, t0 + 30_000, 1).mostrar).toBe(true);
  });

  it('vence a los 2 segundos del plazo', () => {
    expect(estadoAviso(t0, t0 + 601_000, 10).vencida).toBe(false);
    expect(estadoAviso(t0, t0 + 602_000, 10).vencida).toBe(true);
    expect(estadoAviso(t0, t0 + 700_000, 10).restantes).toBe(0);
  });

  it('cuenta regresiva en mm:ss', () => {
    expect(mmss(60)).toBe('01:00');
    expect(mmss(9)).toBe('00:09');
  });
});
