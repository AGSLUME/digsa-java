import { describe, expect, it } from 'vitest';
import { cifra as decimal, fecha as fechaCorta, monto as moneda } from '../shared/formato';
import {
  abrirReporte,
  bajarNivel,
  busquedaDe,
  colorCalor,
  compacto,
  conCorte,
  conTablero,
  cruzarFiltro,
  consultaDe,
  descripcionSerie,
  estadoCelda,
  filasGrafico,
  indiceColumna,
  intensidad,
  leerEstado,
  maximoEje,
  problemaPeriodo,
  puntosSerie,
  quitarFiltro,
  rutaDescarga,
  rutaTablero,
  rutaTraza,
  rutaTrazaCsv,
  seudonimoCorto,
  SIN_HOMOLOGAR,
  textoPastilla,
  textoValor,
  verCohorteEnTableros,
  type Tabla,
} from './tableros';

describe('estado de la pantalla en la URL', () => {
  it('lee el tablero, los generales y los filtros repetidos (ordenados y sin repetir)', () => {
    const e = leerEstado('?t=medicamento&fuerza=EJC&fuerza=ARC&fuerza=EJC&desde=2025-01-01&otro=x&atc=');
    expect(e.tablero).toBe('medicamento');
    expect(e.filtros.fuerza).toEqual(['ARC', 'EJC']);
    expect(e.filtros.atc).toBeUndefined();
    expect(e.generales.desde).toBe('2025-01-01');
    expect(leerEstado('').tablero).toBe('gasto');
  });

  it('la misma consulta da la misma URL (orden fijo) y viaja igual a la descarga', () => {
    const a = leerEstado('?t=gasto&fuerza=EJC&fuerza=ARC&hasta=2025-12-31&desde=2025-01-01');
    const b = leerEstado('?desde=2025-01-01&fuerza=ARC&t=gasto&fuerza=EJC&hasta=2025-12-31');
    expect(consultaDe(a)).toBe(consultaDe(b));
    expect(consultaDe(a)).toBe('desde=2025-01-01&hasta=2025-12-31&fuerza=ARC&fuerza=EJC');
    expect(rutaTablero(a)).toBe('/api/analitica/tableros/gasto?desde=2025-01-01&hasta=2025-12-31&fuerza=ARC&fuerza=EJC');
    expect(rutaDescarga(a, 'pdf')).toBe('/api/analitica/tableros/gasto/descarga?desde=2025-01-01&hasta=2025-12-31&fuerza=ARC&fuerza=EJC&formato=pdf');
    expect(busquedaDe(a)).toBe('t=gasto&desde=2025-01-01&hasta=2025-12-31&fuerza=ARC&fuerza=EJC');
  });

  it('cambiar de tablero conserva los filtros', () => {
    const e = conTablero(leerEstado('?t=gasto&fuerza=EJC'), 'medicamento');
    expect(e.tablero).toBe('medicamento');
    expect(e.filtros.fuerza).toEqual(['EJC']);
  });

  it('bajar de nacional a Fuerza y a ESM acumula los filtros; quitar la Fuerza vuelve a la vista por defecto', () => {
    const n = leerEstado('?t=territorial');
    const f = bajarNivel(n, 'fuerza', 'EJC', ['nacional', 'fuerza', 'esm'])!;
    expect(f.generales.nivel).toBe('fuerza');
    expect(f.filtros.fuerza).toEqual(['EJC']);
    const e = bajarNivel(f, 'esm', 'BAS02', ['nacional', 'fuerza', 'esm'])!;
    expect(e.generales.nivel).toBe('esm');
    expect(e.filtros).toEqual({ fuerza: ['EJC'], esm: ['BAS02'] });
    expect(bajarNivel(n, 'fuerza', 'EJC', ['esm'])).toBeNull();
    const q = quitarFiltro(f, 'fuerza');
    expect(q.filtros.fuerza).toBeUndefined();
    expect(q.generales.nivel).toBeUndefined();
    const dos = leerEstado('?fuerza=EJC&fuerza=ARC');
    expect(quitarFiltro(dos, 'fuerza', 'EJC').filtros.fuerza).toEqual(['ARC']);
  });

  it('periodo: fechas completas y en orden', () => {
    expect(problemaPeriodo('2025-01-01', '2025-12-31')).toBeNull();
    expect(problemaPeriodo('2025-12-31', '2025-01-01')).toBe('La fecha «Desde» es posterior a «Hasta».');
    expect(problemaPeriodo('2025-1-1', undefined)).toBe('Escriba las fechas como aaaa-mm-dd.');
    expect(problemaPeriodo(undefined, undefined)).toBeNull();
    expect(problemaPeriodo('0202-01-01', undefined)).toBe('El año debe estar entre 2000 y 2100.');
  });
});

describe('rastreo de una cifra', () => {
  const e = leerEstado('?t=atc&fuerza=EJC&desde=2025-01-01&hasta=2025-12-31');

  it('la celda reemplaza la variable del tablero y el periodo va aparte', () => {
    const c = estadoCelda(e, { atc: 'L04', periodo: '2025-09' });
    expect(c.estado.filtros).toEqual({ fuerza: ['EJC'], atc: ['L04'] });
    expect(c.periodo).toBe('2025-09');
    expect(rutaTraza(e, { atc: 'L04', periodo: '2025-09' })).toBe(
      '/api/analitica/traza/atc?desde=2025-01-01&hasta=2025-12-31&fuerza=EJC&atc=L04&periodo=2025-09',
    );
    expect(rutaTraza(e, {}, 3, 1234)).toBe('/api/analitica/traza/atc?desde=2025-01-01&hasta=2025-12-31&fuerza=EJC&pagina=3&despues=1234');
    expect(rutaTrazaCsv(e, { atc: SIN_HOMOLOGAR })).toBe(
      '/api/analitica/traza/atc/registros.csv?desde=2025-01-01&hasta=2025-12-31&fuerza=EJC&atc=%28sin+homologar%29',
    );
  });
});

describe('gráficos', () => {
  const tabla: Tabla = {
    id: 'x',
    titulo: 'X',
    grafico: 'barras',
    valorGrafico: 'gasto',
    comparado: null,
    columnas: [
      { clave: 'clave', etiqueta: 'ATC', tipo: 'TEXTO' },
      { clave: 'gasto', etiqueta: 'Gasto', tipo: 'MONEDA' },
    ],
    filas: [
      { clave: 'A', rastreo: { atc: 'A' }, valores: ['A', 10], textos: ['A', '$ 10,00'], tipo: 'dato' },
      { clave: '(total)', rastreo: {}, valores: ['Total', 10], textos: ['Total', '$ 10,00'], tipo: 'total' },
    ],
    nota: null,
  };

  it('las barras no incluyen la fila Total; columna por clave', () => {
    expect(filasGrafico(tabla).map((f) => f.clave)).toEqual(['A']);
    expect(indiceColumna(tabla, 'gasto')).toBe(1);
    expect(indiceColumna(tabla, 'otra')).toBe(-1);
    expect(indiceColumna(tabla, null)).toBe(-1);
  });

  it('puntos de la serie: y invertida y proporcional al máximo; un solo punto va al centro', () => {
    expect(puntosSerie([0, 50, 100], 200, 100, 100)).toBe('0,100 100,50 200,0');
    expect(puntosSerie([5], 200, 100, 10, { x: 10, y: 5 })).toBe('110,55');
    expect(puntosSerie([], 200, 100, 10)).toBe('');
    expect(puntosSerie([3], 200, 100, 0)).toBe('100,100');
  });

  it('máximo redondo del eje', () => {
    expect(maximoEje(0)).toBe(1);
    expect(maximoEje(7)).toBe(10);
    expect(maximoEje(1500)).toBe(2000);
    expect(maximoEje(4200000)).toBe(5000000);
  });

  it('mapa de calor: intensidad 0 a 1 y texto claro solo sobre fondo oscuro', () => {
    expect(intensidad(5, 10)).toBe(0.5);
    expect(intensidad(5, 0)).toBe(0);
    expect(intensidad(20, 10)).toBe(1);
    expect(colorCalor(0)).toEqual({ fondo: 'color-mix(in srgb, var(--lume-blue) 6%, var(--lume-white))', texto: 'var(--lume-text)' });
    expect(colorCalor(0.55).texto).toBe('var(--lume-text)');
    expect(colorCalor(0.56).texto).toBe('var(--lume-white)');
    expect(colorCalor(1)).toEqual({ fondo: 'color-mix(in srgb, var(--lume-blue) 100%, var(--lume-white))', texto: 'var(--lume-white)' });
  });

  it('texto alternativo de la serie con periodo y unidad', () => {
    expect(descripcionSerie('Gasto por periodo', tabla.filas.slice(0, 1), 'pesos', true)).toBe(
      'Gasto por periodo, de A a A, en pesos; línea discontinua: periodo de comparación.',
    );
    expect(descripcionSerie('Gasto', [], 'pesos', false)).toBe('Gasto: sin datos.');
  });
});

describe('formato del Anexo 5 2.4', () => {
  it('dos decimales con coma, sin separador de miles', () => {
    expect(decimal(64310220400)).toBe('64310220400,00');
    expect(decimal(6.2)).toBe('6,20');
    expect(moneda(152400.3)).toBe('$ 152400,30');
    expect(moneda(null)).toBe('—');
    expect(compacto(1_500_000_000)).toBe('1,50 mil M');
    expect(compacto(320_000_000)).toBe('320,00 M');
    expect(compacto(12_400)).toBe('12,40 mil');
    expect(fechaCorta('2026-10-21')).toBe('21/10/2026');
    expect(seudonimoCorto('a'.repeat(64))).toBe('aaaaaaaaaaaa…');
    expect(textoValor([{ valor: 'EJC', etiqueta: 'EJC · Ejército' }], 'EJC')).toBe('EJC · Ejército');
    expect(textoValor(undefined, SIN_HOMOLOGAR)).toBe('Sin homologar');
  });
});

describe('rastreo que suma la cifra (14.9)', () => {
  it('ATC y CIE-10: la celda se cruza con el filtro del tablero (se conserva el valor más específico)', () => {
    expect(cruzarFiltro('atc', ['N02BE01'], 'N')).toEqual(['N02BE01']);
    expect(cruzarFiltro('atc', ['N02', 'N05', 'C01'], 'N')).toEqual(['N02', 'N05']);
    expect(cruzarFiltro('atc', ['N'], 'N02BE01')).toEqual(['N02BE01']);
    expect(cruzarFiltro('atc', undefined, 'N')).toEqual(['N']);
    expect(cruzarFiltro('cie10', ['J459'], 'J45')).toEqual(['J459']);
    expect(cruzarFiltro('cie10', ['J45', SIN_HOMOLOGAR], SIN_HOMOLOGAR)).toEqual([SIN_HOMOLOGAR]);
    expect(cruzarFiltro('fuerza', ['EJC', 'ARC'], 'EJC')).toEqual(['EJC']);
    const e = leerEstado('?t=atc&atc=N02BE01&nivelAtc=1');
    expect(estadoCelda(e, { atc: 'N' }).estado.filtros.atc).toEqual(['N02BE01']);
  });

  it('la descarga y el rastreo fijan el corte de lo que se ve; un reporte del A4 no hereda orden ni nivel ATC', () => {
    const e = leerEstado('?t=atc&orden=unidades&nivelAtc=3&desde=2025-01-01&fuerza=EJC');
    expect(conCorte(e, 7).generales.corte).toBe('7');
    expect(conCorte({ ...e, generales: { ...e.generales, corte: '3' } }, 7).generales.corte).toBe('3');
    const r = abrirReporte(e, 'medicamento', {});
    expect(r.generales).toEqual({ desde: '2025-01-01' });
    expect(r.filtros).toEqual({ fuerza: ['EJC'] });
    expect(abrirReporte(e, 'atc', { nivelAtc: '2' }).generales.nivelAtc).toBe('2');
  });
});

describe('cohorte, GCF y GRC como filtro (A7.1)', () => {
  it('viajan en la URL como los demás filtros y llegan a la consulta, la descarga y el rastreo', () => {
    const e = leerEstado('t=gasto&cohorte=vih&cohorte=renal&grc=GRC2&desde=2025-01-01');
    expect(e.filtros.cohorte).toEqual(['renal', 'vih']);
    expect(e.filtros.grc).toEqual(['GRC2']);
    expect(rutaTablero(e)).toBe('/api/analitica/tableros/gasto?desde=2025-01-01&cohorte=renal&cohorte=vih&grc=GRC2');
    expect(rutaTraza(e, { atc: 'C09' })).toContain('&cohorte=renal&cohorte=vih&grc=GRC2');
  });

  it('«Ver en los tableros» abre el tablero de gasto con UNA cohorte, conservando periodo, corte y los demás filtros', () => {
    const e = leerEstado('t=cohortes&corte=7&desde=2025-01-01&fuerza=EJC&cohorte=vih&gcf=A10');
    const v = verCohorteEnTableros(e, 'renal');
    expect(v.tablero).toBe('gasto');
    expect(v.generales).toEqual({ corte: '7', desde: '2025-01-01' });
    expect(v.filtros).toEqual({ fuerza: ['EJC'], cohorte: ['renal'] });
    expect(busquedaDe(v)).toBe('t=gasto&desde=2025-01-01&corte=7&fuerza=EJC&cohorte=renal');
  });

  it('la pastilla muestra «Renal · v1» (el criterio completo va en el título) y el grupo sin la partición', () => {
    expect(textoPastilla('cohorte', 'Renal (v1: CIE-10 N18, N19, Z49 o Z992 en 12 meses)')).toBe('Renal · v1');
    expect(textoPastilla('grc', 'GRC2 · un diagnóstico crónico — Grupos de riesgo clínico (GRC) (v1: Partición…)')).toBe(
      'GRC2 · un diagnóstico crónico',
    );
    expect(textoPastilla('fuerza', 'EJC · Ejército (v1: x)')).toBe('EJC · Ejército (v1: x)');
    expect(textoPastilla('cohorte', 'renal')).toBe('renal');
  });
});
