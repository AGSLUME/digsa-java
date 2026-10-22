import { describe, expect, it } from 'vitest';
import {
  botonesCiclo,
  cuerpoAjuste,
  edicionDe,
  etiquetaAnio,
  filaCambiada,
  filasProgramables,
  filasSinResultado,
  opcionesLider,
  opcionesOtros,
  periodoCiclo,
  type CicloPropuesta,
  type ItemPropuesta,
  type Propuesta,
} from './propuesta';

const ciclo: CicloPropuesta = {
  ciclo: 'central',
  nombre: 'Nivel central del OPL (anual)',
  tipo: 'central',
  desde: '2026-09-22',
  limite: '2027-09-21',
  anioContrato: 1,
  total: 3,
  cerradas: 1,
  agendadas: 1,
  faltan: 1,
  borradorId: null,
  confirmadaIncompletaId: null,
};

const item: ItemPropuesta = {
  id: 7,
  puntoId: 3,
  punto: 'Nivel central',
  unidad: 'NC',
  municipio: 'Bogotá, D.C.',
  auditorId: 10,
  lider: 'QF',
  otros: [{ id: 11, nombre: 'Ingeniero' }],
  fecha: '2026-11-12',
  fijo: false,
  km: 0,
  motivo: 'Auditor asignado al punto',
  alertas: [],
  visitaId: null,
  visita: null,
  resultado: null,
};

const auditores = [
  { id: 10, nombre: 'QF', rol: 'quimico_farmaceutico' },
  { id: 11, nombre: 'Ingeniero', rol: 'ingeniero_sistemas' },
  { id: 12, nombre: 'Regente', rol: 'regente' },
  { id: 13, nombre: 'Otro', rol: 'otro' },
];

describe('botones de la tarjeta del ciclo', () => {
  it('sin borrador: «Proponer agenda», habilitado si falta algo', () => {
    const b = botonesCiclo(ciclo);
    expect(b.proponer).toBe('Proponer agenda');
    expect(b.proponerDeshabilitado).toBe(false);
    expect(b.advertirDescarte).toBe(false);
    expect(b.verBorrador).toBe(false);
    expect(b.continuarConfirmacion).toBe(false);
  });

  it('deshabilitado si no falta nada o falta el inicio del contrato', () => {
    expect(botonesCiclo({ ...ciclo, faltan: 0 }).proponerDeshabilitado).toBe(true);
    expect(
      botonesCiclo({ ciclo: 'anual', nombre: 'Anual', tipo: 'anual', error: 'Falta la fecha de inicio del contrato.' }).proponerDeshabilitado,
    ).toBe(true);
  });

  it('con borrador: «Proponer de nuevo» con advertencia y «Ver borrador»', () => {
    const b = botonesCiclo({ ...ciclo, borradorId: 5 });
    expect(b.proponer).toBe('Proponer de nuevo');
    expect(b.advertirDescarte).toBe(true);
    expect(b.verBorrador).toBe(true);
  });

  it('una confirmación cortada se puede continuar', () => {
    expect(botonesCiclo({ ...ciclo, confirmadaIncompletaId: 4 }).continuarConfirmacion).toBe(true);
  });

  it('etiqueta del año y periodo', () => {
    expect(etiquetaAnio({ anio: 2, desde: '2028-01-15', hasta: '2029-01-14' })).toBe('Año de contrato 2 (2028-01-15 – 2029-01-14)');
    expect(periodoCiclo(ciclo)).toBe('2026-09-22 a 2027-09-21');
    expect(periodoCiclo({ ciclo: 'anual', nombre: '', tipo: '' })).toBe('—');
  });
});

describe('ajuste de una fila', () => {
  it('la edición parte de la fila y detecta cambios', () => {
    const e = edicionDe(item);
    expect(e).toEqual({ fecha: '2026-11-12', lider: '10', otros: ['11'] });
    expect(filaCambiada(item, e)).toBe(false);
    expect(filaCambiada(item, { ...e, fecha: '2026-11-13' })).toBe(true);
    expect(filaCambiada(item, { ...e, otros: [] })).toBe(true);
    expect(edicionDe({ ...item, auditorId: null, fecha: null, otros: [] })).toEqual({ fecha: '', lider: null, otros: [] });
  });

  it('el cuerpo no repite, no lleva al líder entre los otros y deja máximo 5', () => {
    expect(cuerpoAjuste({ fecha: '2026-11-13', lider: '10', otros: ['11', '10', '11', '12'] })).toEqual({
      auditorId: 10,
      otros: [11, 12],
      fecha: '2026-11-13',
    });
    expect(cuerpoAjuste({ fecha: '', lider: null, otros: ['1', '2', '3', '4', '5', '6'] }).otros).toEqual([1, 2, 3, 4, 5]);
    expect(cuerpoAjuste({ fecha: '', lider: null, otros: [] }).auditorId).toBeNull();
  });

  it('el ingeniero y «otro» no lideran, salvo que ya sean el líder de la fila', () => {
    expect(opcionesLider(auditores, null).map((o) => o.value)).toEqual(['10', '12']);
    expect(opcionesLider(auditores, '11').map((o) => o.value)).toEqual(['10', '11', '12']);
    // Líder guardado e inactivado: sigue visible con su nombre (no aparece «Sin auditor» con un id puesto)
    expect(opcionesLider(auditores, '99', { id: 99, nombre: 'Auditor retirado' }).at(-1)).toEqual({
      value: '99',
      label: 'Auditor retirado (inactivo)',
    });
    expect(opcionesLider(auditores, '10', { id: 99, nombre: 'Auditor retirado' }).map((o) => o.value)).toEqual(['10', '12']);
    expect(opcionesLider(auditores, null)[0]!.label).toBe('QF · Químico farmacéutico');
  });

  it('los otros excluyen al líder y conservan a los de la fila', () => {
    expect(opcionesOtros(auditores, '10').map((o) => o.value)).toEqual(['11', '12', '13']);
    expect(opcionesOtros(auditores, '10', [{ id: 99, nombre: 'Inactivo' }]).map((o) => o.value)).toEqual(['11', '12', '13', '99']);
  });
});

describe('confirmación', () => {
  const p: Propuesta = {
    id: 1,
    ciclo: 'central',
    nombre: 'Nivel central',
    anioContrato: 1,
    desde: '2026-09-22',
    hasta: '2027-09-21',
    estado: 'borrador',
    resumen: {},
    items: [
      item,
      { ...item, id: 8, auditorId: null },
      { ...item, id: 9, fecha: null, resultado: 'Sin programar: le falta el auditor líder o la fecha' },
    ],
  };

  it('cuenta las filas con líder y fecha y las que siguen sin resultado', () => {
    expect(filasProgramables(p)).toBe(1);
    expect(filasSinResultado(p)).toBe(2);
  });
});
