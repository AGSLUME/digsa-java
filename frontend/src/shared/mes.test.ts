import { describe, expect, it } from 'vitest';
import { moverMes, rangoMes, semanasDelMes, tituloMes } from './mes';

describe('cuadrícula del mes', () => {
  it('noviembre de 2026: empieza el lunes 26 de octubre porque el 1 es domingo, y termina el domingo 6 de diciembre', () => {
    const s = semanasDelMes(2026, 10);
    expect(s).toHaveLength(6);
    expect(s.every((w) => w.length === 7)).toBe(true);
    expect(s[0]![0]).toEqual({ iso: '2026-10-26', dia: 26, delMes: false });
    expect(s[0]![6]).toEqual({ iso: '2026-11-01', dia: 1, delMes: true });
    expect(s[1]![0]!.iso).toBe('2026-11-02');
    expect(s[5]![0]!.iso).toBe('2026-11-30');
    expect(s[5]![6]).toEqual({ iso: '2026-12-06', dia: 6, delMes: false });
    expect(s.flat().filter((d) => d.delMes)).toHaveLength(30);
  });

  it('un mes que empieza en lunes no agrega días del mes anterior (febrero de 2027)', () => {
    const s = semanasDelMes(2027, 1);
    expect(s[0]![0]).toEqual({ iso: '2027-02-01', dia: 1, delMes: true });
    expect(s).toHaveLength(4);
  });

  it('rango, título y navegación entre años', () => {
    expect(rangoMes(2026, 10)).toEqual({ desde: '2026-11-01', hasta: '2026-11-30' });
    expect(rangoMes(2028, 1).hasta).toBe('2028-02-29');
    expect(tituloMes(2026, 10)).toBe('noviembre de 2026');
    expect(moverMes(2026, 11, 1)).toEqual({ anio: 2027, mes: 0 });
    expect(moverMes(2026, 0, -1)).toEqual({ anio: 2025, mes: 11 });
  });
});
