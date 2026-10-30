/**
 * Lógica pura del tablero 12 «Predictivo» (docs/especificaciones/analitica-cohortes-alertas, A5): tipos de la API, parámetros de la
 * consulta, validación del ajuste del escenario, geometría del gráfico (observado continuo, proyectado discontinuo, banda IC 95 %, marca
 * del corte) y su tabla equivalente. Sin IA: el servidor proyecta con reglas/AnaliticaPrediccion.
 */
import { cifra, conteo, monto, porcentaje } from '../shared/formato';
import { maximoEje, type EstadoAnalitica } from './tableros';

export type MetricaPrediccion = 'gasto' | 'dispensaciones' | 'pacientes';
export type DimensionPrediccion = 'atc' | 'principio' | 'cie10' | 'cohorte' | 'gcf' | 'grc';

export const METRICAS_PREDICCION: { value: MetricaPrediccion; label: string }[] = [
  { value: 'gasto', label: 'Gasto' },
  { value: 'dispensaciones', label: 'Dispensaciones' },
  { value: 'pacientes', label: 'Pacientes distintos' },
];

export const DIMENSIONES_PREDICCION: { value: DimensionPrediccion; label: string }[] = [
  { value: 'atc', label: 'ATC (nivel elegido)' },
  { value: 'principio', label: 'Principio activo' },
  { value: 'cie10', label: 'CIE-10' },
  // A7.1 (AN.30): con el mismo filtro de los tableros (miembros en el mes de cada dispensación, membresía del corte)
  { value: 'cohorte', label: 'Cohorte' },
  { value: 'gcf', label: 'GCF (grupo de consumo)' },
  { value: 'grc', label: 'GRC (grupo de riesgo clínico)' },
];

/** Horizontes que ofrece la pantalla (la API acepta de 6 a 24 meses). */
export const HORIZONTES = ['6', '9', '12', '18', '24'];

/** Horizonte con que abre la pantalla (A4 §11.1 f: mínimo 6). */
export const HORIZONTE_DEFECTO = '6';

export interface PuntoObservado {
  mes: string;
  valor: number;
  parcial: boolean;
}

export interface PuntoProyectado {
  mes: string;
  h: number;
  valor: number;
  inferior: number;
  superior: number;
  ajustado: number | null;
}

export interface Evaluado {
  metodo: string;
  nombre: string;
  parametros: Record<string, number>;
  wmape: number | null;
  mape: number | null;
  elegido: boolean;
}

export interface ModeloPrediccion {
  entrenamiento: number | null;
  registrado: boolean;
  origen: 'automatico' | 'manual' | null;
  usuario: string | null;
  fechaEntrenamiento: string | null;
  estado: 'ok' | 'historia_insuficiente' | 'error';
  mensaje: string | null;
  metodo: string | null;
  metodoNombre: string | null;
  parametros: Record<string, number>;
  evaluados: Evaluado[];
  historiaDesde: string | null;
  historiaHasta: string | null;
  historiaMeses: number;
  backtestMeses: number;
  mape: number | null;
  wmape: number | null;
  sigma: number | null;
  notas: string[];
  version: string;
  sha256: string | null;
  horizonte: number;
  duracionMs: number | null;
}

export interface Comparado {
  mes: string;
  proyectado: number;
  inferior: number;
  superior: number;
  observado: number;
  error: number | null;
  dentroIc: boolean;
  h: number;
  entrenamiento: number;
  fechaEntrenamiento: string;
}

export interface SerieClave {
  metrica: MetricaPrediccion;
  nivel: string;
  valor: string | null;
  dimension: DimensionPrediccion | null;
  valorDimension: string | null;
}

export interface Prediccion {
  serie: SerieClave;
  serieTexto: string;
  niveles: string[];
  hayCargues: boolean;
  mensaje?: string;
  corte?: { cargueId: number; aplicadoEn: string; texto: string; fechaCorte: string | null; tablaMaestra: number | null };
  alcance?: string;
  suprimida?: boolean;
  advertencias?: string[];
  observado?: PuntoObservado[];
  modelo?: ModeloPrediccion | null;
  proyeccion?: PuntoProyectado[];
  ajuste?: number | null;
  escenarios?: { base: number; inferior: number; superior: number; ajustado: number | null } | null;
  comparacion?: Comparado[];
  supresion?: string;
  /** Cohorte o grupo de GCF / GRC de la serie con su versión y criterio (A7.1). */
  cohorte?: string | null;
  puedeReentrenar: boolean;
}

export interface Entrenamiento {
  id: number;
  fecha: string;
  origen: 'automatico' | 'manual';
  usuario: string | null;
  corte: number;
  fechaCorte: string | null;
  horizonte: number;
  metodo: string | null;
  metodoNombre: string | null;
  estado: 'ok' | 'historia_insuficiente' | 'error';
  mensaje: string | null;
  historiaDesde: string | null;
  historiaHasta: string | null;
  historiaMeses: number;
  backtestMeses: number;
  mape: number | null;
  wmape: number | null;
  version: string;
  sha256: string | null;
  duracionMs: number;
  /** Versión de la cohorte de la serie (A7.1); nula sin dimensión de cohorte. */
  versionCohorte?: number | null;
}

export interface PaginaEntrenamientos {
  entrenamientos: Entrenamiento[];
  total: number;
  pagina: number;
  porPagina: number;
}

/** Lo que el usuario eligió en el panel (la serie y el horizonte). */
export interface Seleccion {
  metrica: MetricaPrediccion;
  nivel?: string;
  valor?: string;
  dimension?: DimensionPrediccion;
  nivelAtc?: string;
  valorDimension?: string;
  horizonte?: string;
}

/**
 * Selección inicial desde la barra de filtros del tablero: la vista y, si hay un solo valor, la Fuerza o el ESM; la dimensión de un ATC,
 * principio o CIE-10 elegido (un solo valor) o, si no hay, la de una cohorte, un grupo de GCF o uno de GRC (A7.1).
 */
export function seleccionInicial(e: EstadoAnalitica): Seleccion {
  const s: Seleccion = { metrica: 'gasto' };
  const uno = (v: string[] | undefined) => (v && v.length === 1 ? v[0] : undefined);
  const nivel = e.generales.nivel;
  if (nivel) s.nivel = nivel;
  if (nivel === 'fuerza') s.valor = uno(e.filtros.fuerza);
  if (nivel === 'esm') s.valor = uno(e.filtros.esm);
  const atc = uno(e.filtros.atc);
  const principio = uno(e.filtros.principio);
  const cie10 = uno(e.filtros.cie10);
  if (atc) {
    s.dimension = 'atc';
    s.valorDimension = atc;
    s.nivelAtc = String(nivelAtcDe(atc));
  } else if (principio) {
    s.dimension = 'principio';
    s.valorDimension = principio;
  } else if (cie10) {
    s.dimension = 'cie10';
    s.valorDimension = cie10;
  } else {
    for (const d of ['cohorte', 'gcf', 'grc'] as const) {
      const v = uno(e.filtros[d]);
      if (v) {
        s.dimension = d;
        s.valorDimension = v;
        break;
      }
    }
  }
  return s;
}

/** Nivel ATC (1 a 5) por el largo del código: A, A10, A10B, A10BA, A10BA02. */
export function nivelAtcDe(codigo: string): number {
  switch (codigo.length) {
    case 1:
      return 1;
    case 3:
      return 2;
    case 4:
      return 3;
    case 5:
      return 4;
    default:
      return 5;
  }
}

/** Parámetros de la serie para la API (los vacíos no van). El corte es el de la barra de filtros, si se eligió. */
export function parametrosSerie(e: EstadoAnalitica, s: Seleccion, extra: Record<string, string | undefined> = {}): string {
  const p = new URLSearchParams();
  p.append('metrica', s.metrica);
  if (s.nivel) p.append('nivel', s.nivel);
  if (s.valor && s.nivel && s.nivel !== 'nacional') p.append('valor', s.valor);
  if (s.dimension && s.valorDimension) {
    p.append('dimension', s.dimension);
    p.append('valorDimension', s.valorDimension);
  }
  if (e.generales.corte) p.append('corte', e.generales.corte);
  // Siempre explícito: la pantalla elige el horizonte (el servidor tiene su propio valor por defecto configurable)
  p.append('horizonte', s.horizonte ?? HORIZONTE_DEFECTO);
  for (const [k, v] of Object.entries(extra)) if (v !== undefined && v !== '') p.append(k, v);
  return p.toString();
}

/** Mensaje si el ajuste (%) no es válido: número de −100 a 1000 con hasta dos decimales (coma o punto); vacío es válido (sin ajuste). */
export function problemaAjuste(texto: string): string | null {
  const t = texto.trim();
  if (t === '') return null;
  if (!/^-?\d{1,4}([.,]\d{1,2})?$/.test(t)) return 'Escriba un porcentaje con hasta dos decimales, por ejemplo 5 o -2,5.';
  const n = Number(t.replace(',', '.'));
  if (n < -100 || n > 1000) return 'El ajuste debe estar entre -100 % y 1000 %.';
  return null;
}

/** El ajuste como lo pide la API (punto decimal). */
export function ajusteApi(texto: string): string {
  return texto.trim().replace(',', '.');
}

/** Valor con el formato 2.4 de la métrica: gasto en pesos; dispensaciones y pacientes observados como conteo y proyectados con 2 decimales. */
export function valorTexto(metrica: MetricaPrediccion, v: number | null | undefined, proyectado = false): string {
  if (v === null || v === undefined) return '—';
  if (metrica === 'gasto') return monto(v);
  return proyectado ? cifra(v) : conteo(v);
}

/** Parámetros del método: «alfa = 0,30, beta = 0,10». */
export function textoParametros(p: Record<string, number> | null | undefined): string {
  const l = Object.entries(p ?? {});
  return l.length === 0 ? '—' : l.map(([k, v]) => `${k} = ${cifra(v)}`).join(', ');
}

/** Porcentaje del error o «—» si no es calculable. */
export function textoError(v: number | null | undefined): string {
  return v === null || v === undefined ? '—' : porcentaje(v);
}

/** «2025-03» ⇒ «03/2025». */
export function textoMes(mes: string): string {
  const [a, m] = mes.split('-');
  return a && m ? `${m}/${a}` : mes;
}

/** Fila de la tabla equivalente del gráfico. */
export interface FilaSerie {
  mes: string;
  tipo: 'Observado' | 'Observado parcial' | 'Proyectado';
  valor: number;
  inferior: number | null;
  superior: number | null;
}

/** Tabla accesible equivalente al gráfico: observado (con el parcial marcado) y después la proyección con su IC. */
export function filasSerie(observado: PuntoObservado[], proyeccion: PuntoProyectado[]): FilaSerie[] {
  return [
    ...observado.map((o) => ({
      mes: o.mes,
      tipo: o.parcial ? ('Observado parcial' as const) : ('Observado' as const),
      valor: o.valor,
      inferior: null,
      superior: null,
    })),
    ...proyeccion.map((p) => ({ mes: p.mes, tipo: 'Proyectado' as const, valor: p.valor, inferior: p.inferior, superior: p.superior })),
  ];
}

/** Geometría del gráfico en coordenadas del SVG. */
export interface Geometria {
  meses: string[];
  maximo: number;
  /** Línea continua de los meses completos observados. */
  observado: string;
  /** Punto del mes parcial (si hay). */
  parcial: { x: number; y: number } | null;
  /** Línea discontinua: del último mes completo observado a la proyección. */
  proyectado: string;
  /** Polígono de la banda IC 95 % (desde el último observado completo). */
  banda: string;
  /** x de la marca del corte (el mes del corte: el parcial o el último completo). */
  corteX: number | null;
}

const r1 = (n: number) => Math.round(n * 10) / 10;

/**
 * Geometría del gráfico: un eje x por mes (observados y proyectados, en orden), un eje y de 0 al máximo redondeado (`maximoEje`). La
 * proyección y la banda arrancan en el último mes completo observado para que la línea no quede suelta.
 */
export function geometria(
  observado: PuntoObservado[],
  proyeccion: PuntoProyectado[],
  ancho: number,
  alto: number,
  margen: { x: number; y: number },
): Geometria {
  const meses = [...observado.map((o) => o.mes), ...proyeccion.filter((p) => !observado.some((o) => o.mes === p.mes)).map((p) => p.mes)];
  const n = meses.length;
  const maximo = maximoEje(Math.max(0, ...observado.map((o) => o.valor), ...proyeccion.map((p) => p.superior)));
  const x = (mes: string) => {
    const i = meses.indexOf(mes);
    return r1(margen.x + (n <= 1 ? ancho / 2 : (ancho * i) / (n - 1)));
  };
  const y = (v: number) => r1(margen.y + alto - (maximo > 0 ? (alto * Math.max(0, v)) / maximo : 0));
  const completos = observado.filter((o) => !o.parcial);
  const parcialPunto = observado.find((o) => o.parcial);
  const ultimo = completos[completos.length - 1];
  const linea = (puntos: { mes: string; v: number }[]) => puntos.map((p) => `${x(p.mes)},${y(p.v)}`).join(' ');
  const proyectados = proyeccion.map((p) => ({ mes: p.mes, v: p.valor }));
  const inicio = ultimo ? [{ mes: ultimo.mes, v: ultimo.valor }] : [];
  const superior = [...inicio, ...proyeccion.map((p) => ({ mes: p.mes, v: p.superior }))];
  const inferior = [...inicio, ...proyeccion.map((p) => ({ mes: p.mes, v: p.inferior }))].reverse();
  const mesCorte = parcialPunto?.mes ?? ultimo?.mes;
  return {
    meses,
    maximo,
    observado: linea(completos.map((o) => ({ mes: o.mes, v: o.valor }))),
    parcial: parcialPunto ? { x: x(parcialPunto.mes), y: y(parcialPunto.valor) } : null,
    proyectado: proyeccion.length ? linea([...inicio, ...proyectados]) : '',
    banda: proyeccion.length ? linea([...superior, ...inferior]) : '',
    corteX: mesCorte ? x(mesCorte) : null,
  };
}

/** Nombre accesible del gráfico: qué serie, de cuándo a cuándo y qué es cada trazo. */
export function descripcionGrafico(
  serieTexto: string,
  observado: PuntoObservado[],
  proyeccion: PuntoProyectado[],
  metrica: MetricaPrediccion,
): string {
  if (observado.length === 0 && proyeccion.length === 0) return `${serieTexto}: sin datos.`;
  const unidad = metrica === 'gasto' ? 'pesos' : metrica === 'pacientes' ? 'pacientes distintos' : 'dispensaciones';
  const desde = observado[0]?.mes ?? proyeccion[0]?.mes ?? '';
  const hasta = proyeccion[proyeccion.length - 1]?.mes ?? observado[observado.length - 1]?.mes ?? '';
  return (
    `${serieTexto}, de ${textoMes(desde)} a ${textoMes(hasta)}, en ${unidad}: línea continua observado` +
    (proyeccion.length ? `; línea discontinua proyectado ${proyeccion.length} meses con banda del IC 95 %` : '') +
    '; línea vertical: corte.'
  );
}

/** Texto del origen de un entrenamiento. */
export function textoOrigen(origen: string | null, usuario: string | null): string {
  if (origen === 'automatico') return 'Automático (cargue)';
  if (origen === 'manual') return `Reentrenar${usuario ? ` · ${usuario}` : ''}`;
  return 'Calculada al consultar (no registrada)';
}

/** Texto del estado de un entrenamiento. */
export function textoEstado(estado: string, mensaje: string | null): string {
  if (estado === 'ok') return 'Correcto';
  if (estado === 'historia_insuficiente') return mensaje ?? 'Historia insuficiente';
  return mensaje ? `Error: ${mensaje}` : 'Error';
}

// ---------- Cohorte, GCF y GRC como dimensión (A7.1, AN.30) ----------

/** ¿La dimensión se resuelve contra la membresía de las cohortes? */
export function dimensionDeCohorte(d: DimensionPrediccion | undefined | null): boolean {
  return d === 'cohorte' || d === 'gcf' || d === 'grc';
}

/**
 * Texto de un valor del Top 20: «código · nombre» en ATC, principio y CIE-10; en cohorte, GCF y GRC el servidor ya manda el nombre con su
 * versión o el código del grupo con su nombre.
 */
export function etiquetaTop(d: DimensionPrediccion | undefined, o: { valor: string; etiqueta: string }): string {
  return dimensionDeCohorte(d) ? o.etiqueta : `${o.valor} · ${o.etiqueta}`;
}

/** Mensaje de la lista vacía del Top 20 según la dimensión. */
export function sinValoresTop(d: DimensionPrediccion | undefined): string {
  return dimensionDeCohorte(d) ? 'Sin cohortes ni grupos calculados con su membresía en este corte' : 'Sin valores homologados en el alcance';
}
