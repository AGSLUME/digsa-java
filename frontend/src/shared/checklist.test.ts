import { describe, expect, it } from 'vitest';
import {
  conCalificacion,
  conteo,
  cuerpoPlan,
  cumplimientoLegible,
  esProrroga,
  faltantesPlan,
  fechaValida,
  justificacionValida,
  planCambiado,
  respuestasCambiadas,
  sinHallazgo,
  tamanoLegible,
  textoONulo,
  type ComponenteChecklist,
  type EdicionPlan,
  type DatosPlan,
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
      'HB-01': { calificacion: 'C', hallazgo: '', enTramite: false },
      'HB-02': { calificacion: 'NC', hallazgo: ' Sin rótulo ', enTramite: false },
      'HB-03': { calificacion: 'NC', hallazgo: 'Nevera sin registro', enTramite: false },
      'HB-04': { calificacion: 'C', hallazgo: '', enTramite: false },
    });
    expect(r).toEqual([{ item: 'HB-03', calificacion: 'NC', hallazgo: 'Nevera sin registro', enTramite: false }]);
  });

  it('al dejar de ser "No cumple" el hallazgo se borra; vaciar la calificación también cuenta como cambio', () => {
    const r = respuestasCambiadas(COMPONENTES, {
      'HB-01': { calificacion: null, hallazgo: '', enTramite: false },
      'HB-02': { calificacion: 'C', hallazgo: 'Sin rótulo', enTramite: false },
    });
    expect(r).toEqual([
      { item: 'HB-01', calificacion: null, hallazgo: null, enTramite: false },
      { item: 'HB-02', calificacion: 'C', hallazgo: null, enTramite: false },
    ]);
    expect(sinHallazgo([{ item: 'HB-03', calificacion: 'NC', hallazgo: null, enTramite: false }])).toEqual(['HB-03']);
  });

  it('cuenta con las ediciones locales aplicadas', () => {
    const items = COMPONENTES[0]!.items;
    expect(conteo(items, {})).toEqual({ c: 1, nc: 1, na: 0, pendientes: 2 });
    expect(conteo(items, { 'HB-03': { calificacion: 'NA', hallazgo: '', enTramite: false } })).toEqual({ c: 1, nc: 1, na: 1, pendientes: 1 });
  });

  it('habilitación en trámite (D5): solo en un ítem que la admite y en «No cumple»; marcarla o quitarla es un cambio', () => {
    const c00: ComponenteChecklist[] = [
      {
        codigo: 'C00',
        nombre: 'Validaciones de habilitación',
        escalaPropia: false,
        items: [
          item('C00-01', { admiteTramite: true, calificacion: 'NC', hallazgo: 'Sin distintivo', enTramite: true }),
          item('C00-02', { admiteTramite: true }),
          item('HB-09'),
        ],
      },
    ];
    // Lo guardado sin editar no se envía; desmarcar la casilla sí
    expect(respuestasCambiadas(c00, { 'C00-01': { calificacion: 'NC', hallazgo: 'Sin distintivo', enTramite: true } })).toEqual([]);
    expect(respuestasCambiadas(c00, { 'C00-01': { calificacion: 'NC', hallazgo: 'Sin distintivo', enTramite: false } })).toEqual([
      { item: 'C00-01', calificacion: 'NC', hallazgo: 'Sin distintivo', enTramite: false },
    ]);
    // Marcada en un ítem que la admite; en uno que no la admite nunca se envía
    expect(
      respuestasCambiadas(c00, {
        'C00-02': { calificacion: 'NC', hallazgo: 'Habilitación vencida', enTramite: true },
        'HB-09': { calificacion: 'NC', hallazgo: 'Nevera', enTramite: true },
      }),
    ).toEqual([
      { item: 'C00-02', calificacion: 'NC', hallazgo: 'Habilitación vencida', enTramite: true },
      { item: 'HB-09', calificacion: 'NC', hallazgo: 'Nevera', enTramite: false },
    ]);
    // Fuera de «No cumple» la casilla se desmarca; al volver a «No cumple» sigue desmarcada
    const marcada = { calificacion: 'NC' as const, hallazgo: 'Sin distintivo', enTramite: true };
    expect(conCalificacion(marcada, 'C')).toEqual({ calificacion: 'C', hallazgo: 'Sin distintivo', enTramite: false });
    expect(conCalificacion(marcada, null).enTramite).toBe(false);
    expect(conCalificacion(marcada, 'NC').enTramite).toBe(true);
    expect(conCalificacion(conCalificacion(marcada, 'NA'), 'NC').enTramite).toBe(false);
  });

  it('formatea textos, tamaños y cumplimiento', () => {
    expect(textoONulo('  ')).toBeNull();
    expect(textoONulo(' a ')).toBe('a');
    expect(tamanoLegible(2048)).toBe('2 KB');
    expect(tamanoLegible(5 * 1024 * 1024)).toBe('5,0 MB');
    expect(cumplimientoLegible('75.0')).toBe('75,0 %');
    expect(cumplimientoLegible('null')).toBeNull();
  });
});

const PLAN: DatosPlan = {
  fecha: null,
  estados: ['Pendiente', 'En curso', 'Completado', 'Retrasado', 'Cancelado'],
  ayudaIndicador: 'Cómo se mide',
  hallazgos: [
    { clave: 'it:HB-02', codigo: 'HB-02', origen: 'item', texto: 'Rótulo', hallazgo: 'Sin rótulo', fila: { estado: 'Pendiente', avance: 0 } },
    {
      clave: 'ob:OB-OPL-01',
      codigo: 'OB-OPL-01',
      origen: 'obligacion',
      texto: 'Entregar a tiempo',
      hallazgo: 'Entrega tardía',
      fila: { que: 'Ajustar la ruta', fin: '2026-11-30', estado: 'En curso', avance: 20 },
    },
  ],
};

describe('plan de mejoramiento 5W2H', () => {
  it('pide la fecha, el qué y la fecha fin de las acciones abiertas', () => {
    expect(faltantesPlan(PLAN, null, {})).toEqual(['la fecha del plan', 'el qué de HB-02', 'la fecha fin de HB-02']);
    const completa = { causas: '', que: 'Rotular', porque: '', donde: '', inicio: '', fin: '', indicador: '', estado: 'Completado', prorroga: '' };
    expect(faltantesPlan(PLAN, '2026-10-21', { 'it:HB-02': completa })).toEqual([]);
    expect(faltantesPlan({ ...PLAN, hallazgos: [] }, null, {})).toEqual([]);
  });

  it('detecta cambios sin guardar (sin contar espacios) y arma el cuerpo con todas las filas', () => {
    expect(planCambiado(PLAN, null, {})).toBe(false);
    expect(planCambiado(PLAN, '2026-10-21', {})).toBe(true);
    const igual = {
      causas: '',
      que: ' Ajustar la ruta ',
      porque: '',
      donde: '',
      inicio: '',
      fin: '2026-11-30',
      indicador: '',
      estado: 'En curso',
      prorroga: '',
    };
    expect(planCambiado(PLAN, null, { 'ob:OB-OPL-01': igual })).toBe(false);
    expect(planCambiado(PLAN, null, { 'ob:OB-OPL-01': { ...igual, fin: '2026-12-15' } })).toBe(true);
    const c = cuerpoPlan(PLAN, '2026-10-21', { 'it:HB-02': { ...igual, que: ' Rotular ', estado: 'Pendiente', fin: '' } });
    expect(c.fecha).toBe('2026-10-21');
    expect(c.filas).toEqual([
      {
        clave: 'it:HB-02',
        causas: null,
        que: 'Rotular',
        porque: null,
        donde: null,
        inicio: null,
        fin: null,
        indicador: null,
        estado: 'Pendiente',
        avance: 0,
        prorroga: null,
      },
      {
        clave: 'ob:OB-OPL-01',
        causas: null,
        que: 'Ajustar la ruta',
        porque: null,
        donde: null,
        inicio: null,
        fin: '2026-11-30',
        indicador: null,
        estado: 'En curso',
        avance: 20,
        prorroga: null,
      },
    ]);
  });
});

describe('prórroga en el seguimiento', () => {
  it('es prórroga solo si la acción sigue abierta y la fecha fin válida pasa a ser posterior a la anterior', () => {
    expect(esProrroga('En curso', '2026-11-30', '2026-11-10')).toBe(true);
    expect(esProrroga('Pendiente', '2026-11-11', '2026-11-10')).toBe(true);
    expect(esProrroga(null, '2026-11-30', '2026-11-10')).toBe(true);
    // Adelantar, dejar igual, fijarla donde no había o una fecha inválida no es prórroga
    expect(esProrroga('En curso', '2026-11-05', '2026-11-10')).toBe(false);
    expect(esProrroga('En curso', '2026-11-10', '2026-11-10')).toBe(false);
    expect(esProrroga('En curso', '2026-11-30', null)).toBe(false);
    expect(esProrroga('En curso', '2026-13-40', '2026-11-10')).toBe(false);
    expect(esProrroga('En curso', '', '2026-11-10')).toBe(false);
    // Cerrada (Completado o Cancelado) no es prórroga
    expect(esProrroga('Completado', '2026-11-30', '2026-11-10')).toBe(false);
    expect(esProrroga('Cancelado', '2026-11-30', '2026-11-10')).toBe(false);
    expect(fechaValida('2026-02-29')).toBe(false);
    expect(fechaValida('2028-02-29')).toBe(true);
  });

  it('la justificación pide 10 caracteres sin los espacios alrededor y la tilde cuenta como una letra', () => {
    expect(justificacionValida('  corto  ')).toBe(false);
    expect(justificacionValida('123456789')).toBe(false);
    expect(justificacionValida('1234567890')).toBe(true);
    expect(justificacionValida(' acción ñañ ')).toBe(true);
    expect(justificacionValida('acciónñañ')).toBe(false);
    expect(justificacionValida(null)).toBe(false);
  });

  const SEG: DatosPlan = {
    ...PLAN,
    fecha: '2026-10-21',
    hallazgos: [
      {
        clave: 'it:HB-02',
        codigo: 'HB-02',
        origen: 'item',
        texto: 'Rótulo',
        hallazgo: 'Sigue sin rótulo',
        finAnterior: '2026-11-10',
        fila: { que: 'Rotular', fin: '2026-11-10', estado: 'En curso', avance: 0, quien: 'Director técnico', como: 'Formato diario' },
      },
    ],
  };
  const fila = (cambio: Partial<EdicionPlan>): Record<string, EdicionPlan> => ({
    'it:HB-02': {
      causas: '',
      que: 'Rotular',
      porque: '',
      donde: '',
      inicio: '',
      fin: '2026-11-10',
      indicador: '',
      estado: 'En curso',
      prorroga: '',
      ...cambio,
    },
  });

  it('al correr la fecha fin pide la justificación y solo la envía cuando hay prórroga', () => {
    expect(faltantesPlan(SEG, null, {})).toEqual([]);
    expect(faltantesPlan(SEG, null, fila({ fin: '2026-11-30' }))).toEqual(['la justificación de la prórroga (mínimo 10 caracteres) de HB-02']);
    expect(faltantesPlan(SEG, null, fila({ fin: '2026-11-30', prorroga: 'El proveedor entrega en diciembre' }))).toEqual([]);
    expect(faltantesPlan(SEG, null, fila({ fin: '2026-11-30', estado: 'Completado' }))).toEqual([]);
    // Solo si sigue en No cumple: una fila aún sin recalificar no pide la justificación
    const sinCalificar = { ...SEG, hallazgos: [{ ...SEG.hallazgos[0]!, hallazgo: null, sinCalificar: true }] };
    expect(faltantesPlan(sinCalificar, null, fila({ fin: '2026-11-30' }))).toEqual([]);
    expect(cuerpoPlan(sinCalificar, null, fila({ fin: '2026-11-30', prorroga: 'Texto que no aplica' })).filas[0]!.prorroga).toBeNull();
    expect(cuerpoPlan(SEG, null, fila({ fin: '2026-11-30', prorroga: ' El proveedor entrega en diciembre ' })).filas[0]!.prorroga).toBe(
      'El proveedor entrega en diciembre',
    );
    expect(cuerpoPlan(SEG, null, fila({ fin: '2026-11-05', prorroga: 'Ya no es prórroga' })).filas[0]!.prorroga).toBeNull();
    // La justificación guardada se conserva al recargar
    const guardada = {
      ...SEG,
      hallazgos: [{ ...SEG.hallazgos[0]!, fila: { ...SEG.hallazgos[0]!.fila, fin: '2026-11-30', prorroga_justificacion: 'Llega en diciembre' } }],
    };
    expect(faltantesPlan(guardada, null, {})).toEqual([]);
    expect(planCambiado(guardada, null, {})).toBe(false);
  });
});
