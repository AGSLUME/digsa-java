import { describe, expect, it } from 'vitest';
import {
  ajusteApi,
  descripcionGrafico,
  filasSerie,
  geometria,
  nivelAtcDe,
  parametrosSerie,
  problemaAjuste,
  seleccionInicial,
  textoError,
  textoMes,
  textoOrigen,
  textoParametros,
  valorTexto,
  type PuntoObservado,
  type PuntoProyectado,
} from './prediccion';
import type { EstadoAnalitica } from './tableros';

const estado = (generales: EstadoAnalitica['generales'] = {}, filtros: EstadoAnalitica['filtros'] = {}): EstadoAnalitica => ({
  tablero: 'predictivo',
  generales,
  filtros,
});

const observado: PuntoObservado[] = [
  { mes: '2025-10', valor: 100, parcial: false },
  { mes: '2025-11', valor: 110, parcial: false },
  { mes: '2025-12', valor: 40, parcial: true },
];
const proyeccion: PuntoProyectado[] = [
  { mes: '2025-12', h: 1, valor: 120, inferior: 110, superior: 130, ajustado: null },
  { mes: '2026-01', h: 2, valor: 130, inferior: 115, superior: 145, ajustado: null },
];

describe('selección y parámetros de la serie', () => {
  it('toma de la barra de filtros la vista con su único valor y la dimensión de un ATC elegido', () => {
    const s = seleccionInicial(estado({ nivel: 'fuerza' }, { fuerza: ['EJC'], atc: ['C09A'] }));
    expect(s).toEqual({ metrica: 'gasto', nivel: 'fuerza', valor: 'EJC', dimension: 'atc', valorDimension: 'C09A', nivelAtc: '3' });
    // Con dos Fuerzas no hay un valor que proyectar
    expect(seleccionInicial(estado({ nivel: 'fuerza' }, { fuerza: ['EJC', 'ARC'] })).valor).toBeUndefined();
    expect(seleccionInicial(estado({}, { principio: ['LOSARTAN'] }))).toEqual({
      metrica: 'gasto',
      dimension: 'principio',
      valorDimension: 'LOSARTAN',
    });
  });

  it('arma la consulta sin vacíos, con el corte de la barra y sin el valor en la vista nacional', () => {
    expect(parametrosSerie(estado({ corte: '7' }), { metrica: 'pacientes', nivel: 'nacional', valor: 'EJC' })).toBe(
      'metrica=pacientes&nivel=nacional&corte=7&horizonte=6',
    );
    expect(
      parametrosSerie(
        estado(),
        { metrica: 'gasto', nivel: 'esm', valor: 'BAS02', dimension: 'cie10', valorDimension: 'I10', horizonte: '12' },
        { ajuste: '5' },
      ),
    ).toBe('metrica=gasto&nivel=esm&valor=BAS02&dimension=cie10&valorDimension=I10&horizonte=12&ajuste=5');
    // Dimensión sin valor: no va
    expect(parametrosSerie(estado(), { metrica: 'gasto', dimension: 'atc' })).toBe('metrica=gasto&horizonte=6');
  });

  it('nivel ATC por el largo del código', () => {
    expect(['A', 'A10', 'A10B', 'A10BA', 'A10BA02'].map(nivelAtcDe)).toEqual([1, 2, 3, 4, 5]);
  });
});

describe('ajuste del escenario', () => {
  it('acepta vacío, enteros y hasta dos decimales con coma o punto entre -100 y 1000', () => {
    for (const ok of ['', '5', '-2,5', '10.25', '-100', '1000']) expect(problemaAjuste(ok)).toBeNull();
    expect(problemaAjuste('-150')).toBe('El ajuste debe estar entre -100 % y 1000 %.');
    expect(problemaAjuste('1000,5')).toBe('El ajuste debe estar entre -100 % y 1000 %.');
    expect(problemaAjuste('5,123')).toMatch(/hasta dos decimales/);
    expect(problemaAjuste('cinco')).toMatch(/porcentaje/);
    expect(ajusteApi(' -2,5 ')).toBe('-2.5');
  });
});

describe('formato 2.4', () => {
  it('gasto en pesos, conteos observados enteros y proyectados con dos decimales, sin separador de miles', () => {
    expect(valorTexto('gasto', 1450000.5)).toBe('$ 1450000,50');
    expect(valorTexto('dispensaciones', 1203)).toBe('1203');
    expect(valorTexto('pacientes', 12.345, true)).toBe('12,35');
    expect(valorTexto('gasto', null)).toBe('—');
    expect(textoParametros({ alfa: 0.3, beta: 0.1 })).toBe('alfa = 0,30, beta = 0,10');
    expect(textoParametros({})).toBe('—');
    expect(textoError(2.47)).toBe('2,47 %');
    expect(textoError(null)).toBe('—');
    expect(textoMes('2026-03')).toBe('03/2026');
    expect(textoOrigen('manual', 'ana1')).toBe('Reentrenar · ana1');
    expect(textoOrigen(null, null)).toBe('Calculada al consultar (no registrada)');
  });
});

describe('gráfico y su tabla equivalente', () => {
  it('la tabla lista el observado (con el parcial marcado) y la proyección con su IC', () => {
    const f = filasSerie(observado, proyeccion);
    expect(f.map((x) => x.tipo)).toEqual(['Observado', 'Observado', 'Observado parcial', 'Proyectado', 'Proyectado']);
    expect(f[3]).toEqual({ mes: '2025-12', tipo: 'Proyectado', valor: 120, inferior: 110, superior: 130 });
  });

  it('la línea continua cubre solo los meses completos; la discontinua y la banda arrancan en el último completo; el corte en el mes parcial', () => {
    const g = geometria(observado, proyeccion, 300, 100, { x: 0, y: 0 });
    // Meses: 2025-10, 2025-11, 2025-12 (parcial y primer proyectado), 2026-01 ⇒ 4 posiciones de 0 a 300
    expect(g.meses).toEqual(['2025-10', '2025-11', '2025-12', '2026-01']);
    expect(g.maximo).toBe(200);
    expect(g.observado).toBe('0,50 100,45');
    expect(g.parcial).toEqual({ x: 200, y: 80 });
    expect(g.proyectado).toBe('100,45 200,40 300,35');
    expect(g.banda.split(' ')).toHaveLength(6);
    expect(g.corteX).toBe(200);
  });

  it('sin proyección (historia insuficiente) no hay línea discontinua ni banda', () => {
    const g = geometria(observado.slice(0, 2), [], 100, 100, { x: 0, y: 0 });
    expect(g.proyectado).toBe('');
    expect(g.banda).toBe('');
    expect(g.corteX).toBe(100);
  });

  it('nombre accesible con la serie, el periodo, la unidad y los trazos', () => {
    expect(descripcionGrafico('Gasto · Nacional', observado, proyeccion, 'gasto')).toBe(
      'Gasto · Nacional, de 10/2025 a 01/2026, en pesos: línea continua observado; línea discontinua proyectado 2 meses con banda del IC 95 %; línea vertical: corte.',
    );
    expect(descripcionGrafico('Pacientes', [], [], 'pacientes')).toBe('Pacientes: sin datos.');
  });
});
