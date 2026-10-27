import { describe, expect, it } from 'vitest';
import { nombreDeArchivo } from './api';

describe('nombreDeArchivo (Content-Disposition)', () => {
  it('prefiere el filename* en UTF-8, con tildes', () => {
    expect(nombreDeArchivo(`inline; filename="fotograf_a.jpg"; filename*=UTF-8''fotograf%C3%ADa.jpg`)).toBe('fotografía.jpg');
  });
  it('sin filename* usa el nombre ASCII, y «reporte» si no hay nombre', () => {
    expect(nombreDeArchivo('attachment; filename="logs_auditoria_20261027_0930.xlsx"')).toBe('logs_auditoria_20261027_0930.xlsx');
    expect(nombreDeArchivo(null)).toBe('reporte');
  });
});
