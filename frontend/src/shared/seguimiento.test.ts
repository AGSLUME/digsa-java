import { describe, expect, it } from 'vitest';
import { errorMotivo, fechaHora, textoCadena } from './seguimiento';

describe('prórrogas del plan', () => {
  it('el motivo del rechazo va de 10 a 800 caracteres', () => {
    expect(errorMotivo('  corto  ')).toContain('de 10 a 800');
    expect(errorMotivo('Sin soporte del proveedor')).toBeNull();
    expect(errorMotivo('x'.repeat(801))).not.toBeNull();
  });

  it('muestra la fecha y hora en hora militar de Bogotá', () => {
    expect(fechaHora('2026-10-21T19:05:00.000+00:00')).toBe('2026-10-21 14:05');
    expect(fechaHora('2026-10-21T14:05:00.000-05:00')).toBe('2026-10-21 14:05');
    expect(fechaHora(Date.UTC(2026, 9, 21, 23, 30))).toBe('2026-10-21 18:30');
    expect(fechaHora('2026-10-21T08:30:00')).toBe('2026-10-21 08:30');
    expect(fechaHora(null)).toBe('—');
  });
});

describe('prestadores con hallazgos', () => {
  it('dice el N° de seguimiento y la visita de origen', () => {
    expect(textoCadena({ seguimientoN: null, origenNumero: null })).toBe('Visita de origen');
    expect(textoCadena({ seguimientoN: 2, origenNumero: 'V-2026-0007' })).toBe('Seguimiento N° 2 de la visita V-2026-0007');
  });
});
