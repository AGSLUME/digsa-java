import { describe, expect, it } from 'vitest';
import {
  cuantosMeses,
  descargable,
  erroresPeriodo,
  estadoExportacion,
  indiceMes,
  intervaloSondeoHechos,
  mesDe,
  periodoTexto,
  progreso,
  rutaLeeme,
  rutaMes,
  solicitud,
  textoProgreso,
  valorMes,
} from './exportacionHechos';

describe('exportación de hechos por periodo', () => {
  it('lee meses aaaa-mm y rechaza lo demás', () => {
    expect(indiceMes('1998-01')).toBe(1998 * 12);
    expect(indiceMes(' 2025-12 ')).toBe(2025 * 12 + 11);
    expect(indiceMes('2025-13')).toBeNull();
    expect(indiceMes('2025-00')).toBeNull();
    expect(indiceMes('01/2025')).toBeNull();
    expect(indiceMes('')).toBeNull();
    expect(mesDe('2026-10-30')).toBe('2026-10');
  });

  it('cuenta los meses del periodo con ambos extremos, a través del cambio de año', () => {
    expect(cuantosMeses('2024-11', '2025-02')).toBe(4);
    expect(cuantosMeses('2025-01', '')).toBe(1);
    expect(cuantosMeses('2025-03', '2025-01')).toBe(0);
  });

  it('valida el periodo por campo como el servidor', () => {
    expect(erroresPeriodo('2025-01', '2025-03', '2026-10')).toEqual({});
    expect(erroresPeriodo('2025-01', '', '2026-10')).toEqual({});
    expect(erroresPeriodo('2026-10', '2026-10', '2026-10')).toEqual({});
    expect(Object.keys(erroresPeriodo('', '2025-03', '2026-10'))).toEqual(['desde']);
    expect(Object.keys(erroresPeriodo('1989-12', '', '2026-10'))).toEqual(['desde']);
    expect(Object.keys(erroresPeriodo('2026-11', '', '2026-10'))).toContain('desde');
    expect(erroresPeriodo('2025-03', '2025-01', '2026-10').hasta).toContain('anterior al inicial');
    expect(erroresPeriodo('2025-01', '2026-11', '2026-10').hasta).toContain('posterior al mes actual');
    expect(erroresPeriodo('2025-01', 'marzo', '2026-10').hasta).toContain('aaaa-mm');
    expect(erroresPeriodo('2020-01', '2025-12', '2026-10').hasta).toContain('60 meses');
    expect(erroresPeriodo('2025-01', '2025-12', '2026-10', 12)).toEqual({});
  });

  it('arma la solicitud sin «hasta» para un solo mes', () => {
    expect(solicitud(' 2025-01 ', '')).toEqual({ desde: '2025-01', hasta: null });
    expect(solicitud('2025-01', '2025-04')).toEqual({ desde: '2025-01', hasta: '2025-04' });
  });

  it('progreso y textos con el formato único (sin separador de miles, coma decimal)', () => {
    expect(progreso({ meses: 4, mesesListos: 1, mesesFallidos: 1 })).toBe(50);
    expect(progreso({ meses: 0, mesesListos: 0, mesesFallidos: 0 })).toBe(0);
    expect(textoProgreso({ meses: 12, mesesListos: 3, mesesFallidos: 0 })).toBe('3 de 12 meses listos');
    expect(textoProgreso({ meses: 12, mesesListos: 3, mesesFallidos: 2 })).toBe('3 de 12 meses listos · 2 con falla');
    expect(periodoTexto({ desde: '1998-01', hasta: '1998-01' })).toBe('1998-01');
    expect(periodoTexto({ desde: '1998-01', hasta: '1998-04' })).toBe('1998-01 a 1998-04');
    expect(valorMes({ valorTotal: 1234567.5 })).toBe('1234567,50');
    expect(valorMes({ valorTotal: null })).toBe('—');
  });

  it('una exportación lista o fallida cuyo plazo pasó se ve vencida y no se descarga', () => {
    const ahora = Date.parse('2026-10-30T12:00:00-05:00');
    const antes = '2026-10-30T11:00:00-05:00';
    const despues = '2026-10-31T11:00:00-05:00';
    expect(estadoExportacion({ estado: 'lista', vence: antes }, ahora)).toBe('vencida');
    expect(estadoExportacion({ estado: 'fallida', vence: antes }, ahora)).toBe('vencida');
    expect(estadoExportacion({ estado: 'lista', vence: despues }, ahora)).toBe('lista');
    expect(descargable({ estado: 'lista', vence: despues }, { estado: 'lista' }, ahora)).toBe(true);
    expect(descargable({ estado: 'fallida', vence: despues }, { estado: 'lista' }, ahora)).toBe(true);
    expect(descargable({ estado: 'fallida', vence: despues }, { estado: 'fallida' }, ahora)).toBe(false);
    expect(descargable({ estado: 'lista', vence: antes }, { estado: 'lista' }, ahora)).toBe(false);
    expect(descargable({ estado: 'generando', vence: null }, { estado: 'lista' }, ahora)).toBe(false);
  });

  it('sondea solo mientras haya una en curso; rutas de descarga', () => {
    expect(intervaloSondeoHechos([{ estado: 'generando' }])).toBe(3000);
    expect(intervaloSondeoHechos([{ estado: 'lista' }])).toBe(false);
    expect(intervaloSondeoHechos(undefined)).toBe(false);
    expect(rutaMes(7, '1998-01')).toBe('/api/analitica/exportaciones-hechos/7/meses/1998-01');
    expect(rutaLeeme(7)).toBe('/api/analitica/exportaciones-hechos/7/leeme');
  });
});
