// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { etiquetarCeldas } from './TablaAdaptable';

describe('etiquetarCeldas (tablas en tarjetas en celular)', () => {
  it('pone a cada celda el nombre de su columna y los roles de tabla', () => {
    const raiz = document.createElement('div');
    raiz.innerHTML = `<table>
      <thead><tr><th>Visita</th><th colspan="2">Fechas</th><th>Estado</th></tr></thead>
      <tbody>
        <tr><td>VS-1</td><td>01/10</td><td>02/10</td><td>Cerrada</td></tr>
        <tr><td colspan="4">Sin visitas</td></tr>
        <tr><td>VS-2</td><td colspan="3">Modificado (dato personal)</td></tr>
      </tbody></table>`;
    etiquetarCeldas(raiz);
    const filas = raiz.querySelectorAll('tbody tr');
    expect([...filas[0]!.children].map((c) => c.getAttribute('data-etiqueta'))).toEqual(['Visita', 'Fechas', 'Fechas', 'Estado']);
    expect(filas[1]!.children[0]!.getAttribute('data-etiqueta')).toBe('');
    expect(filas[2]!.children[1]!.getAttribute('data-etiqueta')).toBe('Fechas / Estado');
    expect(raiz.querySelector('table')!.getAttribute('role')).toBe('table');
    expect(filas[0]!.getAttribute('role')).toBe('row');
    expect(filas[0]!.children[0]!.getAttribute('role')).toBe('cell');
    expect(raiz.querySelector('th')!.getAttribute('role')).toBe('columnheader');
  });
});
