import { describe, expect, it } from 'vitest';
import { aNumero, cifra, conteo, fecha, fechaHora, hora, hoyBogota, monto, porcentaje, redondear2, sumarDias } from './formato';

describe('cifras (Anexo 5 num. 2.4)', () => {
  it('2 decimales, coma decimal y sin separador de miles', () => {
    expect(cifra(1450000000)).toBe('1450000000,00');
    expect(cifra(72)).toBe('72,00');
    expect(cifra(0.5)).toBe('0,50');
    expect(cifra(1234567.891)).toBe('1234567,89');
    expect(cifra('7412580330.00')).toBe('7412580330,00');
    expect(cifra('185,4')).toBe('185,40');
  });

  it('redondeo a 2 decimales, mitad hacia afuera de cero (también 1,005 y negativos)', () => {
    expect(redondear2(1.005)).toBe(1.01);
    expect(cifra(2.675)).toBe('2,68');
    expect(cifra(-3.105)).toBe('-3,11');
    expect(cifra(-0.001)).toBe('0,00');
    expect(cifra(0.995)).toBe('1,00');
    // Notación exponencial: restos de punto flotante y números muy pequeños
    expect(cifra(0.1 + 0.2 - 0.3)).toBe('0,00');
    expect(cifra(1e-7)).toBe('0,00');
    expect(cifra(-1e-7)).toBe('0,00');
  });

  it('sin dato o con un texto que no es número: «—»', () => {
    for (const v of [null, undefined, NaN, Infinity, '', 'abc', '1.234,5', '1,2,3']) expect(cifra(v as never)).toBe('—');
    expect(aNumero('  12,5 ')).toBe(12.5);
  });

  it('porcentaje y monto', () => {
    expect(porcentaje(75)).toBe('75,00 %');
    expect(porcentaje(61.4)).toBe('61,40 %');
    expect(monto(98500000000)).toBe('$ 98500000000,00');
    expect(porcentaje(null)).toBe('—');
  });

  it('conteos enteros sin separador de miles (decisión UI-2 mientras se confirma)', () => {
    expect(conteo(18452)).toBe('18452');
    expect(conteo(3.6)).toBe('4');
    expect(conteo(null)).toBe('—');
  });
});

describe('fechas y horas en hora militar de Bogotá', () => {
  it('fecha en pantalla dd/mm/aaaa (decisión UI-3)', () => {
    expect(fecha('2026-10-23')).toBe('23/10/2026');
    expect(fecha('2026-10-23T08:30:00')).toBe('23/10/2026');
    expect(fecha(null)).toBe('—');
    expect(fecha('octubre')).toBe('octubre');
  });

  it('un instante con zona se muestra en Bogotá con 24 h, sin a. m./p. m.', () => {
    expect(fechaHora('2026-10-23T19:05:00Z')).toBe('23/10/2026 14:05');
    expect(fechaHora('2026-10-24T03:30:00Z')).toBe('23/10/2026 22:30');
    expect(fechaHora(Date.UTC(2026, 9, 23, 5, 0, 7), { segundos: true })).toBe('23/10/2026 00:00:07');
  });

  it('un texto sin zona ya viene en hora de Bogotá', () => {
    expect(fechaHora('2026-10-21 14:05')).toBe('21/10/2026 14:05');
    expect(fechaHora('2026-10-21T14:05:09')).toBe('21/10/2026 14:05');
    expect(fechaHora('2026-10-21 14:05:09', { segundos: true })).toBe('21/10/2026 14:05:09');
    expect(fechaHora('2026-10-21')).toBe('21/10/2026');
    expect(fechaHora('')).toBe('—');
  });

  it('hora HH:mm', () => {
    expect(hora('08:30:00')).toBe('08:30');
    expect(hora('2026-10-21T14:05:00')).toBe('14:05');
    expect(hora('2026-10-23T19:05:00Z')).toBe('14:05');
    expect(hora(null)).toBe('—');
  });

  it('hoy en Bogotá aunque el equipo esté en otra zona (a las 02:00 UTC en Bogotá aún es el día anterior)', () => {
    expect(hoyBogota(new Date(Date.UTC(2026, 9, 24, 2, 0)))).toBe('2026-10-23');
    expect(hoyBogota(new Date(Date.UTC(2026, 9, 24, 6, 0)))).toBe('2026-10-24');
  });

  it('sumar días al calendario', () => {
    expect(sumarDias('2026-10-23', -60)).toBe('2026-08-24');
    expect(sumarDias('2026-12-31', 1)).toBe('2027-01-01');
  });
});

describe('ninguna pantalla formatea números por su cuenta', () => {
  // Todo el código de la aplicación como texto, sin las pruebas. Reportes es de otra sesión: la sesión 1 lo incluye al integrarlo.
  // Analítica ya usa shared/formato (A4.5) y entra en la revisión.
  const fuentes = import.meta.glob<string>(['../**/*.ts', '../**/*.tsx', '!../**/*.test.ts', '!../**/*.test.tsx', '!../paginas/Reportes*.tsx'], {
    query: '?raw',
    import: 'default',
    eager: true,
  });

  it('no hay toLocaleString, Intl.NumberFormat ni separador de miles en src/', () => {
    expect(Object.keys(fuentes).length).toBeGreaterThan(40);
    const prohibido = /\.toLocaleString\(|Intl\.NumberFormat|thousandSeparator|toLocaleDateString\(|toLocaleTimeString\(/;
    const culpables = Object.entries(fuentes)
      .filter(([, texto]) => prohibido.test(texto))
      .map(([ruta]) => ruta);
    expect(culpables).toEqual([]);
  });
});
