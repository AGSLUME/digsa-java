import { describe, expect, it } from 'vitest';
import type { DatosSesion } from './sesion';
import {
  conConsulta,
  consultaFiltros,
  cuerpoPesos,
  decimal,
  etiquetaCategoria,
  fechaCompleta,
  FILTROS_VACIOS,
  filtrosConsultables,
  maximo,
  nivelRiesgo,
  porcentajeBarra,
  problemaCierre,
  problemaFechas,
  problemaIncidencia,
  problemaPesos,
  puedeEditarPesos,
  puedeRegistrarIncidencias,
  sumaPesos,
  textoNivel,
  textoFiltrosAplicados,
  textoPlanesVencidos,
  tonoTexto,
} from './tablero';

const FACTORES = ['incumplimiento', 'recurrencia', 'inmediata', 'planes', 'incidencias'];
const NOMBRES: Record<string, string> = {
  incumplimiento: 'Incumplimiento',
  recurrencia: 'Hallazgos recurrentes',
  inmediata: 'Intervención inmediata',
  planes: 'Planes vencidos',
  incidencias: 'Incidencias de la DIGSA',
};
const INICIALES = { incumplimiento: 30, recurrencia: 20, inmediata: 15, planes: 20, incidencias: 15 };

function sesion(pantallas: string[]): DatosSesion {
  return { id: 1, usuario: 'u', nombre: 'Usuario', perfiles: [], menu: [], pantallas, minutosInactividad: 30 };
}

describe('pesos y umbrales del riesgo', () => {
  it('acepta los pesos iniciales y un cambio que sigue sumando 100', () => {
    expect(problemaPesos(INICIALES, FACTORES, NOMBRES, 60, 30)).toBeNull();
    expect(problemaPesos({ ...INICIALES, recurrencia: 25, planes: 15 }, FACTORES, NOMBRES, 60, 30)).toBeNull();
    expect(problemaPesos({ ...INICIALES, recurrencia: '25', planes: '15' }, FACTORES, NOMBRES, '60', '30')).toBeNull();
  });

  it('rechaza pesos que no suman 100, vacíos, negativos o con decimales', () => {
    expect(problemaPesos({ ...INICIALES, recurrencia: 25 }, FACTORES, NOMBRES, 60, 30)).toBe('Los pesos deben sumar 100 (suman 105).');
    expect(problemaPesos({ ...INICIALES, planes: '' }, FACTORES, NOMBRES, 60, 30)).toContain('Planes vencidos');
    expect(problemaPesos({ ...INICIALES, planes: -5, incidencias: 40 }, FACTORES, NOMBRES, 60, 30)).toContain('entre 0 y 100');
    expect(problemaPesos({ ...INICIALES, planes: 19.5, incidencias: 15.5 }, FACTORES, NOMBRES, 60, 30)).toContain('entero');
    const { incidencias: _, ...sinUno } = INICIALES;
    expect(problemaPesos(sinUno, FACTORES, NOMBRES, 60, 30)).toContain('Incidencias de la DIGSA');
  });

  it('exige 100 ≥ alto > medio > 0', () => {
    expect(problemaPesos(INICIALES, FACTORES, NOMBRES, 30, 30)).toContain('alto > medio');
    expect(problemaPesos(INICIALES, FACTORES, NOMBRES, 30, 60)).toContain('alto > medio');
    expect(problemaPesos(INICIALES, FACTORES, NOMBRES, 60, 0)).toContain('alto > medio');
    expect(problemaPesos(INICIALES, FACTORES, NOMBRES, 101, 30)).toContain('alto > medio');
    expect(problemaPesos(INICIALES, FACTORES, NOMBRES, '', 30)).toContain('enteros');
    expect(problemaPesos(INICIALES, FACTORES, NOMBRES, 100, 99)).toBeNull();
  });

  it('suma y arma el cuerpo del PUT con números', () => {
    expect(sumaPesos({ ...INICIALES, planes: '' }, FACTORES)).toBe(80);
    expect(cuerpoPesos({ ...INICIALES, planes: '20' }, FACTORES, 60, 30)).toEqual({ pesos: INICIALES, alto: 60, medio: 30 });
  });

  it('solo la coordinación edita los pesos; coordinación y DIGSA registran incidencias', () => {
    expect(puedeEditarPesos(sesion(['agenda', 'riesgo', 'prorrogas']))).toBe(true);
    expect(puedeEditarPesos(sesion(['riesgo', 'prorrogas']))).toBe(false);
    expect(puedeRegistrarIncidencias(sesion(['riesgo', 'prorrogas']))).toBe(true);
    expect(puedeRegistrarIncidencias(sesion(['riesgo', 'dashboard']))).toBe(false);
  });
});

describe('nivel, números y barras', () => {
  it('color y etiqueta del nivel', () => {
    expect(nivelRiesgo('alto')).toEqual({ color: 'red', insignia: 'red.9', etiqueta: 'Alto' });
    expect(nivelRiesgo('medio')).toEqual({ color: 'orange', insignia: 'orange.4', etiqueta: 'Medio' });
    expect(nivelRiesgo('bajo')).toEqual({ color: 'green', insignia: 'green.9', etiqueta: 'Bajo' });
    expect(nivelRiesgo('otro')).toEqual({ color: 'gray', insignia: 'gray.7', etiqueta: 'otro' });
  });

  it('tono de texto con contraste: rojo .8, naranja y verde .9; tonos explícitos y otros colores se respetan', () => {
    expect(tonoTexto('red')).toBe('red.8');
    expect(tonoTexto('orange')).toBe('orange.9');
    expect(tonoTexto('green')).toBe('green.9');
    expect(tonoTexto('orange.7')).toBe('orange.7');
    expect(tonoTexto('blue')).toBe('blue');
    expect(tonoTexto(undefined)).toBeUndefined();
  });

  it('fechas del dashboard: solo completas y válidas se consultan; desde > hasta se avisa', () => {
    expect(fechaCompleta('2026-02-28')).toBe(true);
    expect(fechaCompleta('2026-02-30')).toBe(false);
    expect(fechaCompleta('0002-01-0')).toBe(false);
    expect(fechaCompleta('')).toBe(false);
    const f = { ...FILTROS_VACIOS, desde: '2026-1', hasta: '2026-03-01', fuerza: 'EJC' };
    expect(filtrosConsultables(f)).toEqual({ ...f, desde: '' });
    expect(problemaFechas(f)).toBeNull();
    expect(problemaFechas({ ...f, desde: '2026-04-01' })).toBe('La fecha «Desde» es posterior a «Hasta».');
    expect(problemaFechas({ ...f, desde: '2026-03-01' })).toBeNull();
  });

  it('filtros aplicados con los nombres de fuerza, tipo y auditor', () => {
    const o = {
      fuerzas: [{ codigo: 'EJC', nombre: 'Ejército' }],
      tipos: [{ codigo: 'anual', nombre: 'Visita anual' }],
      auditores: [{ id: 7, nombre: 'Ana Prueba' }],
      unidades: ['ESM'],
      estados: {},
    };
    const filtros = { Desde: '2026-01-01', Fuerza: 'EJC', Tipo: 'anual', Auditor: '7', Unidad: 'ESM' };
    expect(textoFiltrosAplicados(filtros, o)).toBe('Desde: 2026-01-01 · Fuerza: Ejército · Tipo: Visita anual · Auditor: Ana Prueba · Unidad: ESM');
    expect(textoFiltrosAplicados({ Auditor: '9' }, o)).toBe('Auditor: 9');
    expect(textoFiltrosAplicados({ Tipo: 'anual' }, undefined)).toBe('Tipo: anual');
    expect(textoFiltrosAplicados(null, o)).toBe('');
  });

  it('decimales con coma y 2 cifras (VCO P27)', () => {
    expect(decimal(38.333)).toBe('38,33');
    expect(decimal(15)).toBe('15');
    expect(decimal(7.5)).toBe('7,50');
    expect(decimal(null)).toBe('—');
    expect(decimal(Number.NaN)).toBe('—');
  });

  it('porcentaje de la barra entre 0 y 100', () => {
    expect(porcentajeBarra(15, 30)).toBe(50);
    expect(porcentajeBarra(1, 3)).toBe(33.33);
    expect(porcentajeBarra(40, 30)).toBe(100);
    expect(porcentajeBarra(5, 0)).toBe(0);
    expect(porcentajeBarra(-2, 10)).toBe(0);
    expect(porcentajeBarra(0, 10)).toBe(0);
    expect(
      maximo([
        { etiqueta: 'a', valor: 3 },
        { etiqueta: 'b', valor: 9 },
      ]),
    ).toBe(9);
    expect(maximo([])).toBe(0);
  });

  it('texto de los planes del OPL vencidos', () => {
    expect(textoPlanesVencidos(1)).toBe('1 plan del OPL vencido');
    expect(textoPlanesVencidos(0)).toBe('0 planes del OPL vencidos');
  });
});

describe('filtros del dashboard', () => {
  it('solo van los filtros con valor, recortados y en orden fijo', () => {
    expect(consultaFiltros(FILTROS_VACIOS)).toBe('');
    expect(consultaFiltros({ ...FILTROS_VACIOS, unidad: 'NC', desde: '2026-01-01', fuerza: ' EJC ' })).toBe('desde=2026-01-01&fuerza=EJC&unidad=NC');
    expect(consultaFiltros({ ...FILTROS_VACIOS, auditor: '7', tipo: 'inicial' }, { formato: 'xlsx' })).toBe('formato=xlsx&tipo=inicial&auditor=7');
  });

  it('ruta sin «?» cuando no hay filtros', () => {
    expect(conConsulta('/api/dashboard', '')).toBe('/api/dashboard');
    expect(conConsulta('/api/riesgo', 'fuerza=ARC')).toBe('/api/riesgo?fuerza=ARC');
  });
});

describe('incidencias de la DIGSA', () => {
  const base = { puntoId: '12', fecha: '2026-10-01', descripcion: 'Reporte de desabastecimiento en el ESM', fuente: 'Oficio de la DIGSA' };

  it('valida ESM, fecha no futura, descripción y fuente', () => {
    expect(problemaIncidencia(base, '2026-10-22')).toBeNull();
    expect(problemaIncidencia({ ...base, puntoId: '' }, '2026-10-22')).toBe('Elija el ESM.');
    expect(problemaIncidencia({ ...base, fecha: '' }, '2026-10-22')).toBe('La fecha no es válida.');
    expect(problemaIncidencia({ ...base, fecha: '2026-10-23' }, '2026-10-22')).toBe('La fecha de la incidencia no puede ser futura.');
    expect(problemaIncidencia({ ...base, fecha: '2026-10-22' }, '2026-10-22')).toBeNull();
    expect(problemaIncidencia({ ...base, descripcion: '  corta  ' }, '2026-10-22')).toContain('de 10 a 2000');
    expect(problemaIncidencia({ ...base, descripcion: 'x'.repeat(2001) }, '2026-10-22')).toContain('de 10 a 2000');
    expect(problemaIncidencia({ ...base, fuente: 'f'.repeat(201) }, '2026-10-22')).toBe('La fuente tiene máximo 200 caracteres.');
    expect(problemaIncidencia({ ...base, fuente: '' }, '2026-10-22')).toBeNull();
  });

  it('motivo del cierre de 10 a 800 caracteres', () => {
    expect(problemaCierre('Atendida por el OPL')).toBeNull();
    expect(problemaCierre('   corto   ')).not.toBeNull();
    expect(problemaCierre('m'.repeat(801))).not.toBeNull();
  });
});

describe('resultados del dashboard (25.3) y tarjetas del riesgo (25.4)', () => {
  it('etiqueta de la categoría: código y nombre; las obligaciones solo con su nombre', () => {
    expect(etiquetaCategoria({ codigo: 'C05', nombre: 'Capacidad instalada e infraestructura' })).toBe('C05 · Capacidad instalada e infraestructura');
    expect(etiquetaCategoria({ codigo: 'OBL', nombre: 'Obligaciones del contrato' })).toBe('Obligaciones del contrato');
    expect(etiquetaCategoria({ codigo: 'C99', nombre: 'C99' })).toBe('C99');
    expect(etiquetaCategoria({ codigo: 'C98', nombre: '' })).toBe('C98');
  });

  it('nivel en texto para el lector de pantalla', () => {
    expect(textoNivel('alto', 72)).toBe('Riesgo alto, 72 de 100');
    expect(textoNivel('medio', 41.256)).toBe('Riesgo medio, 41,26 de 100');
    expect(textoNivel('bajo', 0)).toBe('Riesgo bajo, 0 de 100');
    expect(textoNivel('raro', 5)).toBe('Riesgo raro, 5 de 100');
  });
});
