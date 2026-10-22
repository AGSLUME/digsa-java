import { describe, expect, it } from 'vitest';
import { horaValida, leerHora, separarFechaHora, unirFechaHora } from './hora';

describe('hora militar', () => {
  it('lee la hora en 24 h con o sin dos puntos y la deja con dos dígitos', () => {
    expect(leerHora('09:30')).toBe('09:30');
    expect(leerHora('9:30')).toBe('09:30');
    expect(leerHora('930')).toBe('09:30');
    expect(leerHora('0930')).toBe('09:30');
    expect(leerHora(' 23:59 ')).toBe('23:59');
    expect(leerHora('00:00')).toBe('00:00');
    expect(leerHora('14')).toBe('14:00');
    expect(leerHora('7')).toBe('07:00');
  });

  it('vacío es sin hora; fuera de rango, a. m./p. m. o segundos no son válidos', () => {
    expect(leerHora('')).toBe('');
    expect(leerHora(null)).toBe('');
    expect(leerHora('   ')).toBe('');
    // «2:30 PM» pegado entero (el campo admite 12 caracteres): se rechaza, no se lee en silencio como 02:30
    for (const t of ['24:00', '12:60', '2:30 pm', '2:30 PM', '2:30 p. m.', '02:30 a. m.', '10:30:00', '9:3', '12345', 'ab:cd', '-1:00']) {
      expect(leerHora(t), t).toBeNull();
    }
  });

  it('horaValida exige una hora (vacía no cuenta)', () => {
    expect(horaValida('18:45')).toBe(true);
    expect(horaValida('')).toBe(false);
    expect(horaValida('25:00')).toBe(false);
  });

  it('separa la fecha y la hora del servidor', () => {
    expect(separarFechaHora('2026-11-03T08:30:00')).toEqual({ fecha: '2026-11-03', hora: '08:30' });
    expect(separarFechaHora('2026-11-03 17:05')).toEqual({ fecha: '2026-11-03', hora: '17:05' });
    expect(separarFechaHora('2026-11-03')).toEqual({ fecha: '2026-11-03', hora: '' });
    expect(separarFechaHora(null)).toEqual({ fecha: '', hora: '' });
    expect(separarFechaHora('basura')).toEqual({ fecha: '', hora: '' });
  });

  it('une fecha y hora al valor yyyy-MM-ddTHH:mm del servidor, o dice qué falta', () => {
    expect(unirFechaHora('2026-11-03', '930', 'Inicio real')).toEqual({ valor: '2026-11-03T09:30', error: null });
    expect(unirFechaHora('2026-11-03', '17:05', 'Inicio real')).toEqual({ valor: '2026-11-03T17:05', error: null });
    expect(unirFechaHora('', '', 'Inicio real')).toEqual({ valor: '', error: null });
    expect(unirFechaHora('2026-11-03', '', 'Fin real').error).toBe('Fin real: falta la hora.');
    expect(unirFechaHora('', '08:00', 'Fin real').error).toBe('Fin real: falta la fecha.');
    expect(unirFechaHora('2026-11-03', '8:00 pm', 'Fin real').error).toMatch(/^Fin real: escriba la hora en formato de 24 horas/);
  });
});
