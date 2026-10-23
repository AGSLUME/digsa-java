import { describe, expect, it } from 'vitest';
import {
  consultaPresupuesto,
  mesesDe,
  cuerpoEjecucion,
  problemaEjecucion,
  cuerpoNovedad,
  formularioVacio,
  leerMonto,
  mostrarMonto,
  problemaNovedad,
  sumaMontos,
  type FormNovedad,
} from './presupuesto';

function form(cambios: Partial<FormNovedad>): FormNovedad {
  return { ...formularioVacio('adicion', '7'), soporte: 'Oficio 456 del 2027-05-02', ...cambios };
}

describe('leerMonto (igual que Presupuesto.monto del servidor)', () => {
  it('escenario «Valores»: coma o punto decimal; más de 2 decimales se rechaza', () => {
    expect(leerMonto('1.250.000,5')).toBe('1250000.50');
    expect(leerMonto('1250000.50')).toBe('1250000.50');
    expect(leerMonto('$ 1 250 000,505')).toBeNull();
    expect(leerMonto('450000000,00')).toBe('450000000.00');
  });
  it('miles, signo y errores de digitación', () => {
    expect(leerMonto('1.250')).toBe('1250.00');
    expect(leerMonto('1,250,000')).toBe('1250000.00');
    expect(leerMonto('-5')).toBe('-5.00');
    expect(leerMonto('-0')).toBe('0.00');
    expect(leerMonto('12.5,00')).toBeNull();
    expect(leerMonto('abc')).toBeNull();
    expect(leerMonto('')).toBeNull();
    expect(leerMonto('12345678901234567')).toBeNull();
    expect(mostrarMonto(leerMonto('1.000,5'))).toBe('1000,50');
  });
  it('suma exacta en centavos', () => {
    expect(sumaMontos(['0.10', '0.20'])).toBe('0,30');
    expect(sumaMontos(['300000000.00', '-200000000.00', '-99999999.99'])).toBe('0,01');
    expect(sumaMontos(['-5.00', '2.00'])).toBe('-3,00');
  });
});

describe('problemaNovedad', () => {
  const fila = (valor: string, puntoId = '1', modalidad = 'dispensacion') => ({ puntoId, modalidad, fuerza: '', valor });

  it('vigencia y soporte obligatorios', () => {
    expect(problemaNovedad(form({ vigenciaId: '' }))).toBe('Seleccione la vigencia.');
    expect(problemaNovedad(form({ soporte: '  ', filas: [fila('1')] }))).toBe('El soporte de la DIGSA es obligatorio.');
    expect(problemaNovedad(form({ soporte: 'Of.1', filas: [fila('1')] }))).toContain('entre 5 y 300');
  });
  it('adición: cada valor > 0; líneas completas y sin repetir', () => {
    expect(problemaNovedad(form({ filas: [fila('10')] }))).toBeNull();
    expect(problemaNovedad(form({ filas: [fila('0')] }))).toBe('Línea 1: el valor debe ser mayor que 0.');
    expect(problemaNovedad(form({ filas: [fila('-3')] }))).toBe('Línea 1: el valor debe ser mayor que 0.');
    expect(problemaNovedad(form({ filas: [{ ...fila('1'), puntoId: '' }] }))).toBe('Línea 1: seleccione el ESM.');
    expect(problemaNovedad(form({ filas: [fila('1'), fila('2')] }))).toBe('Línea 2: la línea está repetida.');
    expect(problemaNovedad(form({ filas: [fila('1,234')] }))).toContain('máximo 2 decimales');
  });
  it('rebalanceo del mockup: mismo valor en origen y destino, líneas distintas', () => {
    expect(problemaNovedad(form({ tipo: 'rebalanceo', filas: [fila('450.000.000,00', '11'), fila('450000000', '7')] }))).toBeNull();
    expect(problemaNovedad(form({ tipo: 'rebalanceo', filas: [fila('5', '11'), fila('6', '7')] }))).toContain('deben ser iguales');
    expect(problemaNovedad(form({ tipo: 'rebalanceo', filas: [fila('5'), fila('5')] }))).toContain('distintas');
  });
  it('escenario «Recomposición que no cuadra»: suma 0,01', () => {
    const filas = [fila('300000000', '1'), fila('-200000000', '2'), fila('-99999999,99', '3')];
    expect(problemaNovedad(form({ tipo: 'recomposicion', filas }))).toBe('La recomposición debe sumar 0,00 (suma 0,01).');
    filas[2] = fila('-100000000', '3');
    expect(problemaNovedad(form({ tipo: 'recomposicion', filas }))).toBeNull();
  });
  it('HOMIL exige la fuerza de adscripción', () => {
    const homil = new Set(['9']);
    expect(problemaNovedad(form({ filas: [fila('1', '9')] }), homil)).toBe('Línea 1: seleccione la fuerza de adscripción.');
    expect(problemaNovedad(form({ filas: [{ ...fila('1', '9'), fuerza: 'ARC' }] }), homil)).toBeNull();
  });
  it('reajuste: con signo por línea, o por porcentaje', () => {
    expect(problemaNovedad(form({ tipo: 'reajuste', filas: [fila('-10')] }))).toBeNull();
    expect(problemaNovedad(form({ tipo: 'reajuste', filas: [fila('0')] }))).toContain('distinto de 0');
    expect(problemaNovedad(form({ tipo: 'reajuste', porPorcentaje: true, porcentaje: '5,5' }))).toBeNull();
    expect(problemaNovedad(form({ tipo: 'reajuste', porPorcentaje: true, porcentaje: '0' }))).toContain('porcentaje');
    expect(problemaNovedad(form({ tipo: 'reajuste', porPorcentaje: true, porcentaje: '-101' }))).toContain('entre -100 y 1000');
  });
});

describe('cuerpoNovedad y consulta', () => {
  it('montos con punto decimal, fuerza vacía como null, ámbito solo en el reajuste por %', () => {
    const c = cuerpoNovedad(form({ filas: [{ puntoId: '5', modalidad: 'suministro', fuerza: '', valor: '1.000,5' }] }));
    expect(c).toMatchObject({ vigenciaId: 7, tipo: 'adicion', porcentaje: null, ambito: null });
    expect(c.items).toEqual([{ puntoId: 5, modalidad: 'suministro', fuerza: null, valor: '1000.50' }]);
    const p = cuerpoNovedad(form({ tipo: 'reajuste', porPorcentaje: true, porcentaje: '10', ambitoFuerza: 'EJC' }));
    expect(p).toMatchObject({ porcentaje: '10.00', ambito: { fuerza: 'EJC', modalidad: null }, items: [] });
  });
  it('consulta solo con los filtros diligenciados y en orden fijo', () => {
    expect(consultaPresupuesto({ punto: '3', vigencia: '2' }, { pagina: 2 })).toBe('vigencia=2&punto=3&pagina=2');
    expect(consultaPresupuesto({})).toBe('');
  });
});

describe('problemaEjecucion y cuerpoEjecucion', () => {
  const base = {
    vigenciaId: '3',
    puntoId: '5',
    modalidad: 'dispensacion',
    fuerza: '',
    periodo: '2027-03',
    facturado: '260.000.000,00',
    reconocido: '',
    referencia: '',
  };
  it('completo y válido', () => {
    expect(problemaEjecucion(base)).toBeNull();
    expect(cuerpoEjecucion(base)).toEqual({
      vigenciaId: 3,
      puntoId: 5,
      modalidad: 'dispensacion',
      fuerza: null,
      periodo: '2027-03',
      facturado: '260000000.00',
      reconocido: null,
      referencia: null,
    });
  });
  it('mes, montos y HOMIL', () => {
    expect(problemaEjecucion({ ...base, periodo: '2027-13' })).toContain('mes');
    expect(problemaEjecucion({ ...base, facturado: '-1' })).toContain('facturado');
    expect(problemaEjecucion({ ...base, facturado: '10000000', reconocido: '12000000' })).toBe(
      'El reconocido (12000000,00) no puede superar el facturado (10000000,00).',
    );
    expect(problemaEjecucion({ ...base, facturado: '0' })).toBeNull();
    expect(problemaEjecucion(base, new Set(['5']))).toBe('Seleccione la fuerza de adscripción.');
  });
});

describe('mesesDe', () => {
  it('meses de una vigencia parcial y de un año completo', () => {
    expect(mesesDe('2027-03-01', '2027-12-31')).toHaveLength(10);
    expect(mesesDe('2027-03-01', '2027-12-31')[0]).toBe('2027-03');
    expect(mesesDe('2026-12-29', '2026-12-31')).toEqual(['2026-12']);
    expect(mesesDe('2027-01-01', '2027-12-31').at(-1)).toBe('2027-12');
  });
});
