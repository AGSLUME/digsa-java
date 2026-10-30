/**
 * Tipos y funciones puras de «Analítica farmacéutica» (docs/especificaciones/analitica-tableros): estado de la pantalla en la URL
 * (tablero y filtros con parámetros repetidos, contrato de reportes 2.11), rutas de la API (tablero, descarga y rastreo), filtros de una
 * celda para rastrearla, escala de la serie de tiempo en SVG, color del mapa de calor y formato del Anexo 5 2.4.
 */
import { cifra } from '../shared/formato';

// ---------- Tipos de la API ----------

export type Tipo = 'TEXTO' | 'MONEDA' | 'ENTERO' | 'UNIDADES' | 'DECIMAL' | 'PORCENTAJE';

export interface Kpi {
  clave: string;
  etiqueta: string;
  tipo: Tipo;
  valor: number | null;
  texto: string;
  comparado: number | null;
  textoComparado: string | null;
  variacion: number | null;
  variacionPct: number | null;
  textoVariacion: string | null;
  nota: string | null;
  /** Filtros adicionales para rastrear la cifra (vacío = la consulta completa; nulo = no se rastrea). */
  rastreo: Record<string, string> | null;
}

export interface Columna {
  clave: string;
  etiqueta: string;
  tipo: Tipo;
}

export interface Fila {
  clave: string;
  rastreo: Record<string, string> | null;
  valores: (string | number | null)[];
  textos: string[];
  tipo: 'dato' | 'resto' | 'sin' | 'total';
}

export interface Tabla {
  id: string;
  titulo: string;
  grafico: 'barras' | 'serie' | 'calor' | 'ninguno';
  valorGrafico: string | null;
  comparado: string | null;
  columnas: Columna[];
  filas: Fila[];
  nota: string | null;
}

export interface FiltroAplicado {
  variable: string;
  etiqueta: string;
  valores: string[];
  textos: string[];
}

export interface Metadatos {
  corteCargueId: number;
  corte: string;
  corteTexto: string;
  tablaMaestra: number | null;
  cargues: number[];
  fuente: string;
  desde: string;
  hasta: string;
  periodoTexto: string;
  granularidad: string;
  comparacion: string;
  periodoComparacion: string | null;
  alcance: string;
  establecimiento: string;
  filtros: FiltroAplicado[];
  registros: number;
  ultimoDia: string | null;
  supresion: string;
}

export interface ResultadoTablero {
  tablero: string | null;
  titulo: string;
  nivel: string;
  niveles: string[];
  disponible: boolean;
  mensaje: string | null;
  kpis: Kpi[];
  tablas: Tabla[];
  advertencias: string[];
  observaciones: string[];
  noDisponible: string[];
  metadatos: Metadatos | null;
  huella: string | null;
}

export interface Opcion {
  valor: string;
  etiqueta: string;
}

/** Opciones de un filtro; `truncado`: hay más de las que se muestran (escriba más letras). */
export interface Opciones {
  opciones: Opcion[];
  truncado: boolean;
}

export interface CatalogoAnalitica {
  tableros: { codigo: string; titulo: string; disponible: boolean }[];
  variables: { parametro: string; etiqueta: string; prefijo: boolean }[];
  granularidades: { codigo: string; etiqueta: string; disponible: boolean }[];
  comparaciones: { codigo: string; etiqueta: string }[];
  niveles: { codigo: string; etiqueta: string }[];
  alcance: string;
  hayCargues: boolean;
  reportesA4: {
    codigo: string;
    nombre: string;
    tablero: string | null;
    parametros: Record<string, string>;
    disponible: boolean;
    nota: string | null;
  }[];
  puedeRastrear: boolean;
  puedeVerBitacora: boolean;
  puedeVerCargues: boolean;
}

export interface Corte {
  id: number;
  aplicadoEn: string;
  texto: string;
  fuente: string;
  /** Solo con alcance nacional y la pantalla de cargues o de trazabilidad. */
  archivo: string | null;
  periodoDesde: string | null;
  periodoHasta: string | null;
  procesados: number | null;
  reemplazado: boolean;
}

export interface RegistroOrigen {
  id: number;
  fecha: string;
  paciente: string;
  esm: string;
  cumOriginal: string | null;
  medicamentoOriginal: string | null;
  cantidad: number;
  valor: number;
  cargue: number;
  cargueAplicado: string | null;
  fuente: string;
  archivo: string | null;
  sha256: string | null;
  linea: string;
  tablaMaestra: number | null;
  regla: string;
  estado: string;
  datoOriginal: string | null;
  fuenteReferencia: string | null;
  datoFinal: string | null;
}

export interface Traza {
  desglose: Record<string, unknown>;
  registros: RegistroOrigen[];
  pagina: number;
  paginas: number;
  total: number;
  huella: string | null;
  /** Cursor de la página siguiente (id del último registro); nulo en la última. */
  siguiente: number | null;
}

// ---------- Estado de la pantalla en la URL ----------

/** Variables de filtro de selección múltiple (parámetro repetido en la URL). */
export const VARIABLES = [
  'fuerza',
  'disan',
  'regional',
  'esm',
  'modalidad',
  'atc',
  'principio',
  'medicamento',
  'cum',
  'ssfm',
  'cie10',
  'estado',
  // A7.1 (AN.22): cohorte por código; GCF y GRC por grupo (miembros en el mes de cada dispensación, membresía del corte)
  'cohorte',
  'gcf',
  'grc',
] as const;
export type Variable = (typeof VARIABLES)[number];

/** Parámetros de un solo valor. */
export const GENERALES = ['desde', 'hasta', 'granularidad', 'comparacion', 'corte', 'nivel', 'nivelAtc', 'orden'] as const;
export type General = (typeof GENERALES)[number];

export const SIN_HOMOLOGAR = '(sin homologar)';
export const SIN_DATO = '(sin dato)';

export interface EstadoAnalitica {
  tablero: string;
  generales: Partial<Record<General, string>>;
  filtros: Partial<Record<Variable, string[]>>;
}

/** Tablero por defecto (mockup: «Gasto farmacéutico» activo). */
export const TABLERO_INICIAL = 'gasto';

/** Lee el estado de la query string de la pantalla (`?t=gasto&fuerza=EJC&fuerza=ARC&desde=…`). Lo desconocido se ignora. */
export function leerEstado(busqueda: string): EstadoAnalitica {
  const p = new URLSearchParams(busqueda);
  const generales: Partial<Record<General, string>> = {};
  for (const g of GENERALES) {
    const v = p.get(g);
    if (v) generales[g] = v;
  }
  const filtros: Partial<Record<Variable, string[]>> = {};
  for (const v of VARIABLES) {
    const valores = unicos(p.getAll(v).filter((x) => x.trim() !== ''));
    if (valores.length) filtros[v] = valores;
  }
  return { tablero: p.get('t') || TABLERO_INICIAL, generales, filtros };
}

function unicos(l: string[]): string[] {
  return [...new Set(l)].sort();
}

/** Query string de la consulta (sin el tablero): generales y filtros en orden fijo, valores ordenados (misma URL ⇒ misma consulta). */
export function consultaDe(e: Pick<EstadoAnalitica, 'generales' | 'filtros'>, extra: Record<string, string> = {}): string {
  const p = new URLSearchParams();
  for (const g of GENERALES) {
    const v = e.generales[g];
    if (v) p.append(g, v);
  }
  for (const v of VARIABLES) {
    for (const x of unicos(e.filtros[v] ?? [])) p.append(v, x);
  }
  for (const [k, v] of Object.entries(extra)) if (v !== '') p.append(k, v);
  return p.toString();
}

/** Query string de la pantalla, con el tablero. */
export function busquedaDe(e: EstadoAnalitica): string {
  const c = consultaDe(e);
  return `t=${encodeURIComponent(e.tablero)}${c ? '&' + c : ''}`;
}

/**
 * El estado con el corte fijado: si la pantalla no eligió corte, el del resultado que se está viendo (`metadatos.corteCargueId`). Así la
 * descarga, el rastreo y el enlace copiado usan el mismo corte que la pantalla aunque se aplique otro cargue entretanto (14.8).
 */
export function conCorte(e: EstadoAnalitica, corte: number | null | undefined): EstadoAnalitica {
  if (e.generales.corte || corte === null || corte === undefined) return e;
  return { ...e, generales: { ...e.generales, corte: String(corte) } };
}

export function rutaTablero(e: EstadoAnalitica): string {
  const c = consultaDe(e);
  return `/api/analitica/tableros/${encodeURIComponent(e.tablero)}${c ? '?' + c : ''}`;
}

export function rutaDescarga(e: EstadoAnalitica, formato: string): string {
  return `/api/analitica/tableros/${encodeURIComponent(e.tablero)}/descarga?${consultaDe(e, { formato })}`;
}

/** Variables que se filtran por prefijo (cualquier nivel): ATC y CIE-10. */
const POR_PREFIJO: readonly string[] = ['atc', 'cie10'];

/**
 * Valores del filtro con que se rastrea la celda de una variable: el CRUCE del filtro del tablero con el valor de la celda, para que la
 * suma de la traza sea exactamente la cifra (14.9). En ATC y CIE-10 (por prefijo) se conservan los valores del tablero más específicos
 * que la celda (p. ej. tablero atc=N02BE01 en nivel 1: la fila «N» se rastrea con N02BE01, no con todo N); si ninguno lo es, la celda.
 * En las demás variables, la celda (está dentro del filtro porque la fila salió de él).
 */
export function cruzarFiltro(variable: string, previos: string[] | undefined, celda: string): string[] {
  if (!previos?.length || !POR_PREFIJO.includes(variable) || celda === SIN_HOMOLOGAR || celda === SIN_DATO) return [celda];
  const masEspecificos = previos.filter((p) => p !== SIN_HOMOLOGAR && p !== SIN_DATO && p.startsWith(celda));
  return masEspecificos.length ? masEspecificos : [celda];
}

/**
 * Consulta de una celda para rastrearla: la del tablero cruzada con los filtros de la celda (ver {@link cruzarFiltro}); «periodo» acota el
 * periodo a esa etiqueta.
 */
export function estadoCelda(e: EstadoAnalitica, rastreo: Record<string, string>): { estado: EstadoAnalitica; periodo: string | null } {
  const filtros = { ...e.filtros };
  let periodo: string | null = null;
  for (const [k, v] of Object.entries(rastreo)) {
    if (k === 'periodo') periodo = v;
    else if ((VARIABLES as readonly string[]).includes(k)) filtros[k as Variable] = cruzarFiltro(k, e.filtros[k as Variable], v);
  }
  return { estado: { ...e, filtros }, periodo };
}

/** Ruta de una página del rastreo: la primera sin cursor; las siguientes con el id del último registro de la anterior (paginación por llave). */
export function rutaTraza(e: EstadoAnalitica, rastreo: Record<string, string>, pagina = 1, despues: number | null = null): string {
  const { estado, periodo } = estadoCelda(e, rastreo);
  const extra: Record<string, string> = {};
  if (periodo) extra.periodo = periodo;
  if (pagina > 1) extra.pagina = String(pagina);
  if (despues !== null) extra.despues = String(despues);
  return `/api/analitica/traza/${encodeURIComponent(e.tablero)}?${consultaDe(estado, extra)}`;
}

export function rutaTrazaCsv(e: EstadoAnalitica, rastreo: Record<string, string>): string {
  const { estado, periodo } = estadoCelda(e, rastreo);
  return `/api/analitica/traza/${encodeURIComponent(e.tablero)}/registros.csv?${consultaDe(estado, periodo ? { periodo } : {})}`;
}

/**
 * Abre un reporte del Anexo 4: su tablero con sus parámetros. Del estado anterior conserva solo el periodo, la granularidad, la
 * comparación y el corte (el orden y el nivel ATC de otro reporte no se heredan) y los filtros.
 */
export function abrirReporte(e: EstadoAnalitica, tablero: string, parametros: Record<string, string>): EstadoAnalitica {
  const generales: EstadoAnalitica['generales'] = {};
  for (const g of ['desde', 'hasta', 'granularidad', 'comparacion', 'corte'] as const) if (e.generales[g]) generales[g] = e.generales[g];
  return { tablero, generales: { ...generales, ...parametros }, filtros: e.filtros };
}

/** Cambia de tablero conservando los filtros (spec «Cambiar de tablero»). */
export function conTablero(e: EstadoAnalitica, tablero: string): EstadoAnalitica {
  return { ...e, tablero };
}

/** Bajar de vista: al hacer clic en una Fuerza se pasa a la vista por Fuerza con ese filtro; en un ESM, a la vista por ESM. */
export function bajarNivel(e: EstadoAnalitica, variable: string, valor: string, niveles: string[]): EstadoAnalitica | null {
  if (variable === 'fuerza' && niveles.includes('fuerza')) {
    return { ...e, generales: { ...e.generales, nivel: 'fuerza' }, filtros: { ...e.filtros, fuerza: [valor] } };
  }
  if (variable === 'esm' && niveles.includes('esm')) {
    return { ...e, generales: { ...e.generales, nivel: 'esm' }, filtros: { ...e.filtros, esm: [valor] } };
  }
  return null;
}

/** Quita un valor de un filtro (o todo el filtro); al quitar la Fuerza o el ESM de la vista, vuelve a la vista por defecto. */
export function quitarFiltro(e: EstadoAnalitica, variable: Variable, valor?: string): EstadoAnalitica {
  const resto = (e.filtros[variable] ?? []).filter((x) => valor !== undefined && x !== valor);
  const filtros = { ...e.filtros };
  if (resto.length) filtros[variable] = resto;
  else delete filtros[variable];
  const generales = { ...e.generales };
  if ((variable === 'fuerza' && generales.nivel === 'fuerza') || (variable === 'esm' && generales.nivel === 'esm')) delete generales.nivel;
  return { ...e, generales, filtros };
}

/** Problema del periodo elegido (fechas completas y en orden). */
export function problemaPeriodo(desde?: string, hasta?: string): string | null {
  const ok = (v?: string) => !v || (/^\d{4}-\d{2}-\d{2}$/.test(v) && !Number.isNaN(Date.parse(v + 'T12:00:00Z')));
  if (!ok(desde) || !ok(hasta)) return 'Escriba las fechas como aaaa-mm-dd.';
  if (desde && hasta && desde > hasta) return 'La fecha «Desde» es posterior a «Hasta».';
  const anio = (v?: string) => (v ? Number(v.slice(0, 4)) : 2000);
  if ([anio(desde), anio(hasta)].some((a) => a < 2000 || a > 2100)) return 'El año debe estar entre 2000 y 2100.';
  return null;
}

// ---------- Gráficos ----------

/** Índice de una columna por su clave (-1 si no está). */
export function indiceColumna(t: Pick<Tabla, 'columnas'>, clave: string | null): number {
  return clave === null ? -1 : t.columnas.findIndex((c) => c.clave === clave);
}

/** Número de una celda (los BigDecimal llegan como número JSON). */
export function numero(v: string | number | null | undefined): number {
  if (v === null || v === undefined) return 0;
  const n = typeof v === 'number' ? v : Number(v);
  return Number.isFinite(n) ? n : 0;
}

/** Filas que se dibujan como barras: sin la fila «Total». */
export function filasGrafico(t: Tabla): Fila[] {
  return t.filas.filter((f) => f.tipo !== 'total');
}

/** Puntos de una serie en un lienzo (x de izquierda a derecha, y invertida): «x,y x,y …» para un polyline SVG. */
export function puntosSerie(valores: number[], ancho: number, alto: number, maximo: number, margen = { x: 0, y: 0 }): string {
  const n = valores.length;
  if (n === 0) return '';
  return valores
    .map((v, i) => {
      const x = margen.x + (n === 1 ? ancho / 2 : (ancho * i) / (n - 1));
      const y = margen.y + alto - (maximo > 0 ? (alto * Math.max(0, v)) / maximo : 0);
      return `${redondear(x)},${redondear(y)}`;
    })
    .join(' ');
}

function redondear(n: number): number {
  return Math.round(n * 10) / 10;
}

/** Máximo «redondo» del eje (1, 2 o 5 × 10^n) para que las marcas sean legibles. */
export function maximoEje(maximo: number): number {
  if (!(maximo > 0)) return 1;
  const p = Math.pow(10, Math.floor(Math.log10(maximo)));
  for (const m of [1, 2, 5, 10]) if (m * p >= maximo) return m * p;
  return 10 * p;
}

/** Intensidad del mapa de calor (0 a 1) y su tono: de claro a oscuro, con texto oscuro hasta 0,55 para contraste AA. */
export function intensidad(valor: number, maximo: number): number {
  if (!(maximo > 0) || !(valor > 0)) return 0;
  return Math.min(1, valor / maximo);
}

/**
 * Tono del mapa de calor con los colores LUME: azul primario mezclado con blanco. Hasta 0,55 el fondo va de 6 % a 72 % de azul con texto
 * oscuro; desde ahí, de 85 % a 100 % con texto blanco. El salto evita la franja media en la que ningún texto llega a AA (4,5:1).
 */
export function colorCalor(i: number): { fondo: string; texto: string } {
  const oscuro = i > 0.55;
  const azul = Math.round(oscuro ? 85 + ((i - 0.55) / 0.45) * 15 : 6 + (i / 0.55) * 66);
  return {
    fondo: `color-mix(in srgb, var(--lume-blue) ${azul}%, var(--lume-white))`,
    texto: oscuro ? 'var(--lume-white)' : 'var(--lume-text)',
  };
}

/** Texto alternativo de una serie: qué, periodo y unidad (lector de pantalla). */
export function descripcionSerie(titulo: string, filas: Fila[], unidad: string, conComparado: boolean): string {
  if (filas.length === 0) return `${titulo}: sin datos.`;
  const primero = filas[0]?.clave ?? '';
  const ultimo = filas[filas.length - 1]?.clave ?? '';
  return `${titulo}, de ${primero} a ${ultimo}, en ${unidad}${conComparado ? '; línea discontinua: periodo de comparación' : ''}.`;
}

// ---------- Formato 2.4 (cifras con shared/formato) ----------

/** Valor del eje compacto: «1,50 mil M», «320,00 M», «12,40 mil» o el número (cifra de shared/formato con su escala). */
export function compacto(n: number): string {
  const a = Math.abs(n);
  if (a >= 1e9) return `${cifra(n / 1e9)} mil M`;
  if (a >= 1e6) return `${cifra(n / 1e6)} M`;
  if (a >= 1e3) return `${cifra(n / 1e3)} mil`;
  return cifra(n);
}

/** Seudónimo abreviado para la tabla (completo en el CSV de la trazabilidad). */
export function seudonimoCorto(s: string): string {
  return s.length > 12 ? `${s.slice(0, 12)}…` : s;
}

/** Valores elegidos de una variable con su etiqueta (para las pastillas de filtros aplicados). */
export function textoValor(opciones: Opcion[] | undefined, valor: string): string {
  if (valor === SIN_HOMOLOGAR) return 'Sin homologar';
  if (valor === SIN_DATO) return 'Sin dato';
  return opciones?.find((o) => o.valor === valor)?.etiqueta ?? valor;
}

// ---------- Cohorte, GCF y GRC como filtro (A7.1, AN.22) ----------

/** Variables que filtran por la membresía de las cohortes (miembros en el mes de cada dispensación, con la membresía del corte). */
export const VARIABLES_COHORTE: readonly Variable[] = ['cohorte', 'gcf', 'grc'];

/**
 * «Ver en los tableros» desde la tarjeta de una cohorte (tablero 10): el tablero inicial (gasto) con el filtro de esa cohorte, conservando el
 * periodo, la granularidad, el corte y la vista; los demás filtros se conservan salvo cohorte, GCF y GRC (la tarjeta es de UNA cohorte).
 */
export function verCohorteEnTableros(e: EstadoAnalitica, codigo: string, tablero = TABLERO_INICIAL): EstadoAnalitica {
  const filtros: EstadoAnalitica['filtros'] = { ...e.filtros };
  for (const v of VARIABLES_COHORTE) delete filtros[v];
  filtros.cohorte = [codigo];
  return { tablero, generales: { ...e.generales }, filtros };
}

/**
 * Texto corto de la pastilla de un filtro de cohorte: el servidor manda «Renal (v1: criterio…)» (o «GRC2 · … — GRC (v1: …)») para los
 * metadatos y la descarga; la pastilla muestra «Renal · v1» (el criterio completo va en el título de la pastilla).
 */
export function textoPastilla(variable: string, texto: string): string {
  if (!(VARIABLES_COHORTE as readonly string[]).includes(variable)) return texto;
  const grupo = texto.split(' — ')[0] ?? texto;
  if (variable !== 'cohorte') return grupo;
  const m = /^(.*) \(v(\d+): .*\)$/s.exec(texto);
  return m ? `${m[1]} · v${m[2]}` : texto;
}
