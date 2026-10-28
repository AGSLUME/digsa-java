import { describe, expect, it } from 'vitest';
import { agrupar, ORDEN_GRUPOS } from './menu';

const item = (ruta: string, grupo = 'Visitas') => ({ ruta, titulo: ruta, grupo });

describe('agrupar (menú por módulos, U7)', () => {
  it('ordena los módulos como U7 y las pantallas en el orden pedido dentro de cada uno', () => {
    const g = agrupar([
      item('inicio'),
      item('logs', 'Configuración'),
      item('reportes'),
      item('opl'),
      item('agenda'),
      item('mapas'),
      item('dashboard'),
      item('presupuesto', 'Presupuesto'),
      item('maestros', 'Configuración'),
      item('usuarios', 'Configuración'),
    ]);
    expect(g.map((x) => x.grupo)).toEqual(['Visitas', 'Interventoría y seguimiento presupuestal', 'Reportes', 'Seguridad', 'Administración']);
    expect(g[0]!.entradas.map((e) => e.ruta)).toEqual(['agenda', 'opl']);
    expect(g[1]!.entradas[0]!.numeral).toBe('4.6');
    expect(g[2]!.entradas.map((e) => e.ruta)).toEqual(['dashboard', 'mapas', 'reportes']);
    expect(g[3]!.entradas.map((e) => e.ruta)).toEqual(['usuarios', 'logs']);
    expect(g[4]!.entradas.map((e) => e.ruta)).toEqual(['maestros']);
  });

  it('los módulos sin pantallas para el perfil no salen', () => {
    expect(agrupar([item('mis-visitas')]).map((x) => x.grupo)).toEqual(['Visitas']);
  });

  it('una pantalla que no conoce va al módulo de su grupo del servidor, al final de él', () => {
    const g = agrupar([item('analitica', 'Presupuesto'), item('presupuesto', 'Presupuesto'), item('nueva', 'Configuración'), item('usuarios', 'Configuración')]);
    expect(g.map((x) => x.grupo)).toEqual(['Interventoría y seguimiento presupuestal', 'Seguridad', 'Administración']);
    expect(g[0]!.entradas.map((e) => e.ruta)).toEqual(['presupuesto', 'analitica']);
    expect(g[0]!.entradas[1]!.numeral).toBeUndefined();
    expect(g[2]!.entradas.map((e) => e.ruta)).toEqual(['nueva']);
  });

  it('un grupo del servidor que no conoce va al final', () => {
    expect(agrupar([item('x', 'Otro'), item('agenda')]).map((x) => x.grupo)).toEqual(['Visitas', 'Otro']);
  });

  it('no repite Inicio (va fijo arriba) y «Mi cuenta» cierra el orden', () => {
    expect(agrupar([item('inicio', '')])).toEqual([]);
    expect(ORDEN_GRUPOS.at(-1)).toBe('Mi cuenta');
  });
});
