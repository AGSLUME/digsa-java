import { describe, expect, it } from 'vitest';
import {
  COLOR_ESTADO,
  estadoVisible,
  INTERVALO_SONDEO_MS,
  intervaloSondeo,
  NOMBRE_ESTADO,
  numero,
  rutaDescarga,
  rutaSoportesOpl,
  soportesOplDeVisita,
  tamanoLegible,
  veSoportesOpl,
  type EstadoExportacion,
} from './exportacion';

describe('estado de la exportación', () => {
  it('cada estado tiene etiqueta y color', () => {
    const estados: EstadoExportacion[] = ['pendiente', 'generando', 'lista', 'fallida', 'vencida'];
    for (const e of estados) {
      expect(NOMBRE_ESTADO[e]).toBeTruthy();
      expect(COLOR_ESTADO[e]).toBeTruthy();
    }
    expect(NOMBRE_ESTADO.lista).toBe('Lista');
    expect(COLOR_ESTADO.lista).toBe('green');
    expect(COLOR_ESTADO.fallida).toBe('red');
  });

  it('una lista cuyo plazo pasó se muestra vencida; las demás, como vienen', () => {
    const ahora = Date.parse('2026-10-22T12:00:00-05:00');
    expect(estadoVisible({ estado: 'lista', vence: '2026-10-22T11:59:59-05:00' }, ahora)).toBe('vencida');
    expect(estadoVisible({ estado: 'lista', vence: '2026-10-23T12:00:00-05:00' }, ahora)).toBe('lista');
    expect(estadoVisible({ estado: 'lista', vence: null }, ahora)).toBe('lista');
    expect(estadoVisible({ estado: 'fallida', vence: null }, ahora)).toBe('fallida');
  });
});

describe('sondeo', () => {
  it('sondea solo mientras haya una pendiente o generando', () => {
    expect(intervaloSondeo(undefined)).toBe(false);
    expect(intervaloSondeo([])).toBe(false);
    expect(intervaloSondeo([{ estado: 'lista' }, { estado: 'fallida' }, { estado: 'vencida' }])).toBe(false);
    expect(intervaloSondeo([{ estado: 'pendiente' }])).toBe(INTERVALO_SONDEO_MS);
    expect(intervaloSondeo([{ estado: 'lista' }, { estado: 'generando' }])).toBe(INTERVALO_SONDEO_MS);
  });
});

describe('tamaño legible', () => {
  it('bytes, KB, MB y GB con coma decimal', () => {
    expect(tamanoLegible(0)).toBe('0 B');
    expect(tamanoLegible(512)).toBe('512 B');
    expect(tamanoLegible(1024)).toBe('1 KB');
    expect(tamanoLegible(1536)).toBe('1,5 KB');
    expect(tamanoLegible(5 * 1024 * 1024)).toBe('5 MB');
    expect(tamanoLegible(2.34 * 1024 ** 3)).toBe('2,3 GB');
    expect(tamanoLegible(1023.9 * 1024)).toBe('1023,9 KB');
  });

  it('sin dato o inválido, una raya', () => {
    expect(tamanoLegible(null)).toBe('—');
    expect(tamanoLegible(undefined)).toBe('—');
    expect(tamanoLegible(-1)).toBe('—');
    expect(tamanoLegible(Number.NaN)).toBe('—');
  });
});

describe('otros', () => {
  it('número con separador de miles', () => {
    expect(numero(1234567)).toBe('1.234.567');
    expect(numero(999)).toBe('999');
    expect(numero(null)).toBe('—');
  });

  it('rutas de descarga', () => {
    expect(rutaDescarga(7)).toBe('/api/exportacion/7/descarga');
    expect(rutaSoportesOpl(12)).toBe('/api/hallazgos/12/soportes-opl.zip');
  });

  it('soportes del OPL de la visita y quién ve el enlace', () => {
    expect(soportesOplDeVisita({ hallazgos: [{ soportes: 2 }, {}, { soportes: 1 }] })).toBe(3);
    expect(soportesOplDeVisita({ hallazgos: [{}, {}] })).toBe(0);
    expect(veSoportesOpl(true, 2)).toBe(true);
    expect(veSoportesOpl(true, 0)).toBe(false);
    expect(veSoportesOpl(false, 2)).toBe(false);
    expect(veSoportesOpl(undefined, 2)).toBe(false);
  });
});
