import { describe, expect, it } from 'vitest';
import { parametros } from './Reportes';

describe('parametros del reporte', () => {
  const vacio = { desde: '2026-01-01', hasta: '2026-11-30', puntoId: '', fuerza: '', unidad: '', estado: '', componente: '' };

  it('envía solo los filtros con valor', () => {
    expect(parametros(vacio, 'detalle').toString()).toBe('desde=2026-01-01&hasta=2026-11-30');
    expect(parametros({ ...vacio, puntoId: '7', fuerza: 'EJC' }, 'lugar').toString()).toBe('desde=2026-01-01&hasta=2026-11-30&punto_id=7&fuerza=EJC');
  });

  it('el componente solo aplica al consolidado por proceso', () => {
    expect(parametros({ ...vacio, componente: 'C08' }, 'detalle').has('componente')).toBe(false);
    expect(parametros({ ...vacio, componente: 'C08' }, 'proceso').get('componente')).toBe('C08');
  });
});
