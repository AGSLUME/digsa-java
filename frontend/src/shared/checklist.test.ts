import { describe, expect, it } from 'vitest';
import {
  aFechaHoraLocal,
  conteo,
  cumplimientoLegible,
  respuestasCambiadas,
  sinHallazgo,
  tamanoLegible,
  textoONulo,
  type ComponenteChecklist,
  type ItemChecklist,
} from './checklist';

function item(codigo: string, extra: Partial<ItemChecklist> = {}): ItemChecklist {
  return {
    id: 1,
    codigo,
    parte: 'C01',
    responsable: 'Ana',
    editable: true,
    punto: 'Punto',
    criterio: null,
    requisito: null,
    evidencia: null,
    condicion: null,
    admiteNoAplica: false,
    calificacion: null,
    hallazgo: null,
    archivos: [],
    ...extra,
  };
}

const COMPONENTES: ComponenteChecklist[] = [
  {
    codigo: 'C01',
    nombre: 'Infraestructura',
    escalaPropia: false,
    items: [
      item('HB-01', { calificacion: 'C' }),
      item('HB-02', { calificacion: 'NC', hallazgo: 'Sin rótulo' }),
      item('HB-03'),
      item('HB-04', { editable: false }),
    ],
  },
];

describe('formulario del checklist', () => {
  it('solo envía los ítems editables que cambiaron', () => {
    const r = respuestasCambiadas(COMPONENTES, {
      'HB-01': { calificacion: 'C', hallazgo: '' },
      'HB-02': { calificacion: 'NC', hallazgo: ' Sin rótulo ' },
      'HB-03': { calificacion: 'NC', hallazgo: 'Nevera sin registro' },
      'HB-04': { calificacion: 'C', hallazgo: '' },
    });
    expect(r).toEqual([{ item: 'HB-03', calificacion: 'NC', hallazgo: 'Nevera sin registro' }]);
  });

  it('al dejar de ser "No cumple" el hallazgo se borra; vaciar la calificación también cuenta como cambio', () => {
    const r = respuestasCambiadas(COMPONENTES, {
      'HB-01': { calificacion: null, hallazgo: '' },
      'HB-02': { calificacion: 'C', hallazgo: 'Sin rótulo' },
    });
    expect(r).toEqual([
      { item: 'HB-01', calificacion: null, hallazgo: null },
      { item: 'HB-02', calificacion: 'C', hallazgo: null },
    ]);
    expect(sinHallazgo([{ item: 'HB-03', calificacion: 'NC', hallazgo: null }])).toEqual(['HB-03']);
  });

  it('cuenta con las ediciones locales aplicadas', () => {
    const items = COMPONENTES[0]!.items;
    expect(conteo(items, {})).toEqual({ c: 1, nc: 1, na: 0, pendientes: 2 });
    expect(conteo(items, { 'HB-03': { calificacion: 'NA', hallazgo: '' } })).toEqual({ c: 1, nc: 1, na: 1, pendientes: 1 });
  });

  it('formatea fechas, textos, tamaños y cumplimiento', () => {
    expect(aFechaHoraLocal('2026-11-03T08:30:00')).toBe('2026-11-03T08:30');
    expect(aFechaHoraLocal('2026-11-03 08:30')).toBe('2026-11-03T08:30');
    expect(aFechaHoraLocal(null)).toBe('');
    expect(textoONulo('  ')).toBeNull();
    expect(textoONulo(' a ')).toBe('a');
    expect(tamanoLegible(2048)).toBe('2 KB');
    expect(tamanoLegible(5 * 1024 * 1024)).toBe('5,0 MB');
    expect(cumplimientoLegible('75.0')).toBe('75,0 %');
    expect(cumplimientoLegible('null')).toBeNull();
  });
});
