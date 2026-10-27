import { describe, expect, it } from 'vitest';
import { agrupar } from './menu';

const item = (ruta: string, grupo = 'Visitas') => ({ ruta, titulo: ruta, grupo });

describe('agrupar (menú del mockup, decisión UI-7)', () => {
  it('ordena los grupos como el mockup y conserva el orden del servidor dentro de cada uno', () => {
    const g = agrupar([item('inicio'), item('logs', 'Configuración'), item('agenda'), item('reportes'), item('presupuesto', 'Presupuesto')]);
    expect(g.map((x) => x.grupo)).toEqual(['Auditoría', 'Información', 'Administración']);
    expect(g[0]!.entradas.map((e) => e.ruta)).toEqual(['agenda', 'presupuesto']);
    expect(g[0]!.entradas[1]!.numeral).toBe('4.6');
  });

  it('una pantalla que no conoce va al grupo del servidor, al final', () => {
    const g = agrupar([item('mapas', 'Visitas'), item('usuarios', 'Configuración')]);
    expect(g.map((x) => x.grupo)).toEqual(['Administración', 'Visitas']);
    expect(g[1]!.entradas[0]!.numeral).toBeUndefined();
  });

  it('no repite Inicio (va fijo arriba)', () => {
    expect(agrupar([item('inicio', '')])).toEqual([]);
  });
});
