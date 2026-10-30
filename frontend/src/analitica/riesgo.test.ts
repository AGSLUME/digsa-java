import { describe, expect, it } from 'vitest';
import {
  consultaConsola,
  criterioAEditor,
  editorACriterio,
  filaVacia,
  filtrosDeUrl,
  filtrosIgnorados,
  ignoradosCohortes,
  intervaloEjecuciones,
  noEvaluados,
  problemaNota,
  problemasEditor,
  rutaTrazaAlerta,
  separarTarjetas,
  siguientes,
  tarjetasDelFiltro,
  totalesSeveridad,
  type Editor,
  type ResumenTipo,
  type TarjetaCohorte,
} from './riesgo';
import type { EstadoAnalitica } from './tableros';

describe('editor de criterios de cohortes', () => {
  it('«Diabetes con insulina»: CIE-10 E10–E14 Y ATC A10A en 12 meses va como un grupo Y y vuelve igual', () => {
    const e: Editor = {
      op: 'Y',
      filas: [
        { ...filaVacia('cie10'), valores: 'E10, E11,E12 , E13, E14' },
        { ...filaVacia('atc'), valores: 'A10A' },
      ],
    };
    const c = editorACriterio(e);
    expect(c).toEqual({
      tipo: 'grupo',
      op: 'Y',
      criterios: [
        { tipo: 'cie10', valores: ['E10', 'E11', 'E12', 'E13', 'E14'], ventanaMeses: 12 },
        { tipo: 'atc', valores: ['A10A'], ventanaMeses: 12 },
      ],
    });
    const vuelta = criterioAEditor(c);
    expect(vuelta?.op).toBe('Y');
    expect(vuelta?.filas.map((f) => f.valores)).toEqual(['E10, E11, E12, E13, E14', 'A10A']);
  });

  it('un solo criterio va sin grupo; los de patrón llevan su mínimo; la negación se conserva', () => {
    expect(editorACriterio({ op: 'O', filas: [{ ...filaVacia('principiosMes'), minimo: 6 }] })).toEqual({ tipo: 'principiosMes', minimo: 6 });
    expect(editorACriterio({ op: 'Y', filas: [{ ...filaVacia('principiosConsecutivos'), minimo: 5, meses: 6 }] })).toEqual({
      tipo: 'principiosConsecutivos',
      minimo: 5,
      meses: 6,
    });
    expect(editorACriterio({ op: 'Y', filas: [{ ...filaVacia('gastoMovil'), minimo: 95 }] })).toEqual({
      tipo: 'gastoMovil',
      meses: 12,
      modo: 'percentil',
      percentil: 95,
    });
    const no = editorACriterio({ op: 'Y', filas: [{ ...filaVacia('cie10'), valores: 'E11', negar: true }] });
    expect(no.negar).toBe(true);
    expect(criterioAEditor(no)?.filas[0]?.negar).toBe(true);
  });

  it('las particiones (GCF, GRC) y los grupos anidados no caben en el editor sencillo', () => {
    expect(criterioAEditor({ tipo: 'particion', metodo: 'gcf' })).toBeNull();
    expect(criterioAEditor({ tipo: 'grupo', op: 'O', criterios: [{ tipo: 'grupo', op: 'Y', criterios: [] }] })).toBeNull();
    expect(criterioAEditor({ tipo: 'gastoMovil', modo: 'absoluto', valor: '1000' })).toBeNull();
  });

  it('problemas: sin criterios, códigos vacíos o ventana fuera de rango', () => {
    expect(problemasEditor({ op: 'Y', filas: [] })).toEqual(['Agregue al menos un criterio.']);
    expect(problemasEditor({ op: 'Y', filas: [{ ...filaVacia('atc'), valores: ' ' }] })).toEqual(['Criterio 1: escriba al menos un código.']);
    expect(problemasEditor({ op: 'Y', filas: [{ ...filaVacia('atc'), valores: 'L04', ventanaMeses: 0 }] })).toHaveLength(1);
    expect(problemasEditor({ op: 'Y', filas: [{ ...filaVacia('principiosMes') }] })).toEqual([]);
  });
});

describe('consola de alertas', () => {
  it('la consulta lleva solo los filtros conocidos con valor, en orden fijo, y la página desde la 2', () => {
    expect(consultaConsola({ severidad: 'alta', tipo: 'duplicidad_terapeutica', fuerza: ' ' })).toBe('tipo=duplicidad_terapeutica&severidad=alta');
    expect(consultaConsola({}, 3)).toBe('pagina=3');
    expect(consultaConsola({ atc: 'N02' }, 1)).toBe('atc=N02');
  });

  it('validación humana: transiciones y nota mínima para validar o descartar', () => {
    expect(siguientes('nueva')).toEqual(['en_revision', 'validada', 'descartada']);
    expect(siguientes('validada')).toEqual([]);
    expect(siguientes('descartada')).toEqual(['en_revision']);
    expect(problemaNota('validada', 'corta')).toBe('Escriba el motivo (mínimo 10 caracteres)');
    expect(problemaNota('validada', 'Revisada con el comité')).toBeNull();
    expect(problemaNota('en_revision', '')).toBeNull();
  });

  it('el enlace a los registros de origen abre la trazabilidad con el corte, el periodo y los filtros de la alerta', () => {
    const r = rutaTrazaAlerta({
      t: 'general',
      corte: 7,
      desde: '2025-05-01',
      hasta: '2025-05-31',
      esm: ['BAS02'],
      cum: ['20019002-1', '20019001-1'],
    });
    expect(r).toBe('/analitica/trazabilidad?t=general&desde=2025-05-01&hasta=2025-05-31&corte=7&esm=BAS02&cum=20019001-1&cum=20019002-1');
  });

  it('resumen por tipo: totales por severidad y los tipos no evaluados con su motivo', () => {
    const tipos: ResumenTipo[] = [
      {
        tipo: 'interaccion',
        nombre: 'Interacción',
        baja: 0,
        media: 0,
        alta: 0,
        evaluacion: 'no_evaluado',
        motivo: 'no evaluado: sin fuente de interacciones cargada',
      },
      { tipo: 'duplicidad_terapeutica', nombre: 'Duplicidad', baja: 3, media: 1, alta: 0, evaluacion: 'evaluado' },
      { tipo: 'incremento_consumo', nombre: 'Incremento', baja: 1, media: 0, alta: 2, evaluacion: 'evaluado' },
    ];
    expect(totalesSeveridad(tipos)).toEqual({ alta: 2, media: 1, baja: 4 });
    expect(noEvaluados(tipos)).toEqual([{ nombre: 'Interacción', motivo: 'no evaluado: sin fuente de interacciones cargada' }]);
  });
});

describe('tarjetas de cohortes', () => {
  it('las seis del mockup van aparte de las demás', () => {
    const t = (codigo: string, delMockup: boolean) => ({ codigo, delMockup }) as TarjetaCohorte;
    const r = separarTarjetas([t('alto_costo', true), t('gcf', false), t('vih', true)]);
    expect(r.principales.map((x) => x.codigo)).toEqual(['alto_costo', 'vih']);
    expect(r.otras.map((x) => x.codigo)).toEqual(['gcf']);
  });
});

describe('revisión A4: filtros, consola y ejecuciones', () => {
  it('filtros de la barra que el tablero no aplica (o con más de un valor) se informan', () => {
    const e = { tablero: 'cohortes', generales: {}, filtros: { fuerza: ['EJC', 'ARC'], esm: ['BAS02'], atc: [] } };
    expect(filtrosIgnorados(e, ['fuerza'])).toEqual(['fuerza (más de un valor)', 'esm']);
    expect(filtrosIgnorados({ ...e, filtros: { fuerza: ['EJC'] } }, ['fuerza'])).toEqual([]);
  });

  it('la consola lee sus filtros de la dirección (enlace desde la cohorte) e ignora lo demás', () => {
    expect(filtrosDeUrl(new URLSearchParams('cohorte=7&otro=x&tipo=interaccion&esm='))).toEqual({ cohorte: '7', tipo: 'interaccion' });
  });

  it('las ejecuciones se consultan cada 5 s solo con una en curso y sin error', () => {
    expect(intervaloEjecuciones([{ estado: 'ok' }], false)).toBe(false);
    expect(intervaloEjecuciones([{ estado: 'en_curso' }], false)).toBe(5000);
    expect(intervaloEjecuciones([{ estado: 'en_curso' }], true)).toBe(false);
    expect(intervaloEjecuciones(undefined, false)).toBe(false);
  });
});

describe('tablero 10 con el filtro de cohorte, GCF o GRC (A7.1)', () => {
  const tarjeta = (codigo: string, particion = false): TarjetaCohorte => ({
    id: codigo.length,
    codigo,
    nombre: codigo,
    descripcion: null,
    predefinida: true,
    delMockup: false,
    version: 1,
    criterio: 'x · v1',
    particion,
    estado: 'evaluada',
    motivo: null,
    fuentes: null,
  });
  const todas = [tarjeta('renal'), tarjeta('vih'), tarjeta('gcf', true), tarjeta('grc', true)];
  const estado = (filtros: EstadoAnalitica['filtros']): EstadoAnalitica => ({ tablero: 'cohortes', generales: {}, filtros });

  it('muestra solo las cohortes elegidas y la partición filtrada; sin esos filtros, todas', () => {
    expect(tarjetasDelFiltro(todas, estado({})).filtradas).toBe(false);
    expect(tarjetasDelFiltro(todas, estado({})).tarjetas).toHaveLength(4);
    const r = tarjetasDelFiltro(todas, estado({ cohorte: ['vih'], grc: ['GRC1'] }));
    expect(r.filtradas).toBe(true);
    expect(r.tarjetas.map((x) => x.codigo)).toEqual(['vih', 'grc']);
  });

  it('cohorte, GCF y GRC no se cuentan como ignorados (eligen tarjetas); los demás filtros sí', () => {
    expect(ignoradosCohortes(estado({ cohorte: ['vih', 'renal'], gcf: ['A10'], atc: ['C09'], fuerza: ['EJC'] }))).toEqual(['atc']);
  });
});
