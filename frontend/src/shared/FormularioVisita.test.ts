import { describe, expect, it } from 'vitest';
import type { ComponenteOpcion } from './agenda';
import { partesDelReparto, preseleccion } from './FormularioVisita';

const C: ComponenteOpcion[] = [
  { codigo: 'C01', nombre: 'Infraestructura', unidades: ['ESM', 'FA'], items: 10, especial: null, sistemas: 0 },
  { codigo: 'C05', nombre: 'Sistemas de información', unidades: ['ESM'], items: 6, especial: null, sistemas: 2 },
  { codigo: 'C20', nombre: 'Plan de mejoramiento', unidades: ['ESM', 'FA'], items: 3, especial: 'plan', sistemas: 0 },
];

describe('formulario de visita', () => {
  it('preselecciona los componentes de la unidad sin el plan de mejoramiento, salvo en la visita de plan', () => {
    expect(preseleccion(C, 'ESM', 'seguimiento')).toEqual(['C01', 'C05']);
    expect(preseleccion(C, 'FA', 'plan')).toEqual(['C01', 'C20']);
    expect(preseleccion(C, undefined, 'plan')).toEqual([]);
  });

  it('las obligaciones siempre se reparten; los ítems de sistemas solo con ingeniero y componentes que los tengan', () => {
    expect(partesDelReparto(C, ['C01'], ['ingeniero_sistemas']).map((p) => p.parte)).toEqual(['C01', 'OBL']);
    expect(partesDelReparto(C, ['C01', 'C05'], ['regente']).map((p) => p.parte)).toEqual(['C01', 'C05', 'OBL']);
    expect(partesDelReparto(C, ['C05'], ['regente', 'ingeniero_sistemas']).map((p) => p.parte)).toEqual(['C05', 'OBL', 'SIS']);
  });
});
