/**
 * Tipos y funciones puras de cohortes y alertas tempranas (docs/especificaciones/analitica-cohortes-alertas): tarjetas «Cohortes (conteos
 * agregados)», consola de alertas con su contexto, validación humana, parámetros con traza, enlace a la trazabilidad de los registros de
 * origen y el editor sencillo de criterios (Y / O de criterios con ventana). Siempre agregado: ninguna de estas estructuras trae pacientes.
 */
import { busquedaDe, type EstadoAnalitica, type Variable } from './tableros';

// ---------- Cohortes ----------

export interface Grupo {
  grupo: string;
  etiqueta: string;
  pacientes: number | null;
  pacientesTexto: string;
  /** Nulo si la celda se oculta (supresión de celdas pequeñas). */
  dispensaciones: number | null;
  gasto: string;
}

export interface TarjetaCohorte {
  id: number;
  codigo: string;
  nombre: string;
  descripcion: string | null;
  predefinida: boolean;
  delMockup: boolean;
  version: number;
  criterio: string;
  particion: boolean;
  /** «error_calculo»: la última ejecución del corte falló (el motivo dice cuándo y por qué). */
  estado: 'evaluada' | 'no_evaluada' | 'sin_calcular' | 'error_calculo';
  motivo: string | null;
  fuentes: string | null;
  pacientes?: number | null;
  pacientesTexto?: string;
  /** Nulo si la celda se oculta. */
  registros?: number | null;
  gasto?: string;
  umbral?: string;
  nota?: string;
  /** La versión mostrada no es la vigente (aún sin resultados en este corte). */
  aviso?: string;
  grupos?: Grupo[];
}

export interface Tarjetas {
  corte?: number;
  corteTexto?: string;
  periodo?: string;
  nivel?: string;
  mensaje?: string;
  metodologia: string;
  supresion: string;
  cohortes: TarjetaCohorte[];
  puedeEditar: boolean;
  puedeRecalcular: boolean;
  puedeVerConsola: boolean;
}

export interface FilaSerie {
  mes: string;
  grupo: string;
  pacientes: number | null;
  pacientesTexto: string;
  ingresos: number | null;
  salidas: number | null;
  dispensaciones: number | null;
  unidades: number | null;
  gasto: number | null;
  gastoTexto: string;
}

export interface VersionCohorte {
  version: number;
  criterio: string;
  criterios: Criterio;
  vigente: boolean;
  creada: string;
  creadaPor: string;
}

export interface DetalleCohorte {
  id: number;
  codigo: string;
  nombre: string;
  descripcion: string | null;
  predefinida: boolean;
  activa: boolean;
  versiones: VersionCohorte[];
  metodologia: string;
  version?: number;
  criterio?: string;
  estado?: string;
  motivo?: string | null;
  fuentes?: string | null;
  corte?: number;
  corteTexto?: string;
  periodo?: string;
  nivel?: string;
  /** Cohorte sensible vista por un ESM: sin ingresos, salidas ni composición clínica. */
  avisoSupresion?: string;
  serie?: FilaSerie[];
  grupos?: Grupo[];
  comparacion?: {
    periodoAnterior: string;
    filas: { indicador: string; actual: string; anterior: string; variacion?: string | null; variacionPct?: string | null }[];
  };
  niveles?: { nivel: string; codigo: string; pacientesTexto: string; dispensaciones: number | null; gasto: string; nota?: string }[];
}

export interface Composicion {
  cohorte: string;
  criterio: string;
  por: string;
  porTexto: string;
  periodo: string;
  gastoTotalTexto: string;
  filas: { clave: string; pacientesTexto: string; dispensaciones: number | null; gastoTexto: string; participacion: string | null }[];
  nota: string;
}

/**
 * Filtros de la barra que un tablero NO aplica (los admitidos solo con un valor): se muestran para que la cifra no parezca filtrada por lo
 * que no lo está (revisión A4).
 */
export function filtrosIgnorados(e: EstadoAnalitica, admitidos: readonly string[]): string[] {
  const r: string[] = [];
  for (const [k, v] of Object.entries(e.filtros)) {
    if (!v || v.length === 0) continue;
    if (!admitidos.includes(k)) r.push(k);
    else if (v.length > 1) r.push(`${k} (más de un valor)`);
  }
  return r;
}

/** Dimensiones de la composición (P5). */
export const COMPOSICIONES = [
  { valor: 'atc1', etiqueta: 'ATC nivel 1' },
  { valor: 'atc2', etiqueta: 'ATC nivel 2' },
  { valor: 'atc3', etiqueta: 'ATC nivel 3' },
  { valor: 'atc4', etiqueta: 'ATC nivel 4' },
  { valor: 'atc5', etiqueta: 'ATC nivel 5' },
  { valor: 'principio', etiqueta: 'Principio activo' },
  { valor: 'medicamento', etiqueta: 'Medicamento' },
  { valor: 'cie10', etiqueta: 'CIE-10' },
  { valor: 'fuerza', etiqueta: 'Fuerza' },
  { valor: 'esm', etiqueta: 'ESM' },
] as const;

/** Las seis del mockup primero (el servidor ya las ordena; aquí se separan para la pantalla). */
export function separarTarjetas(t: TarjetaCohorte[]): { principales: TarjetaCohorte[]; otras: TarjetaCohorte[] } {
  return { principales: t.filter((x) => x.delMockup), otras: t.filter((x) => !x.delMockup) };
}

/**
 * Tarjetas que muestra el tablero 10 con los filtros de cohorte, GCF o GRC de la barra (A7.1): solo las cohortes elegidas y la partición
 * de GCF o GRC si se filtró por sus grupos. Los conteos siguen siendo los de cada cohorte entera (no el cruce entre ellas). Sin esos
 * filtros, todas.
 */
export function tarjetasDelFiltro(t: TarjetaCohorte[], e: EstadoAnalitica): { tarjetas: TarjetaCohorte[]; filtradas: boolean } {
  const codigos = new Set<string>(e.filtros.cohorte ?? []);
  if (e.filtros.gcf?.length) codigos.add('gcf');
  if (e.filtros.grc?.length) codigos.add('grc');
  if (codigos.size === 0) return { tarjetas: t, filtradas: false };
  return { tarjetas: t.filter((x) => codigos.has(x.codigo)), filtradas: true };
}

/** Filtros de la barra que el tablero 10 no aplica: los demás, salvo una Fuerza y los de cohorte, GCF y GRC (que eligen tarjetas). */
export function ignoradosCohortes(e: EstadoAnalitica): string[] {
  return filtrosIgnorados(e, ['fuerza', 'cohorte', 'gcf', 'grc']).filter((x) => !/^(cohorte|gcf|grc) /.test(x));
}

// ---------- Editor de criterios ----------

/** Criterio en JSON (reglas/AnaliticaCohortes). */
export type Criterio = Record<string, unknown>;

export type TipoFila =
  | 'cie10'
  | 'atc'
  | 'principio'
  | 'medicamento'
  | 'lista'
  | 'fuerza'
  | 'esm'
  | 'modalidad'
  | 'principiosMes'
  | 'principiosConsecutivos'
  | 'gastoMovil'
  | 'cronicos';

export interface FilaEditor {
  tipo: TipoFila;
  /** Códigos separados por coma (criterios de código). */
  valores: string;
  ventanaMeses: number;
  negar: boolean;
  minimo: number;
  meses: number;
}

export interface Editor {
  op: 'Y' | 'O';
  filas: FilaEditor[];
}

export const TIPOS_FILA: { valor: TipoFila; etiqueta: string; codigos: boolean }[] = [
  { valor: 'cie10', etiqueta: 'CIE-10 (prefijos)', codigos: true },
  { valor: 'atc', etiqueta: 'ATC (prefijos)', codigos: true },
  { valor: 'principio', etiqueta: 'Principio activo', codigos: true },
  { valor: 'medicamento', etiqueta: 'Medicamento (CUM)', codigos: true },
  { valor: 'lista', etiqueta: 'Lista de referencia', codigos: true },
  { valor: 'fuerza', etiqueta: 'Fuerza', codigos: true },
  { valor: 'esm', etiqueta: 'ESM', codigos: true },
  { valor: 'modalidad', etiqueta: 'Modalidad', codigos: true },
  { valor: 'principiosMes', etiqueta: 'Principios activos distintos en el mes', codigos: false },
  { valor: 'principiosConsecutivos', etiqueta: 'Principios activos en meses consecutivos', codigos: false },
  { valor: 'gastoMovil', etiqueta: 'Gasto en 12 meses ≥ percentil', codigos: false },
  { valor: 'cronicos', etiqueta: 'Diagnósticos crónicos distintos', codigos: false },
];

export function filaVacia(tipo: TipoFila = 'cie10'): FilaEditor {
  return { tipo, valores: '', ventanaMeses: 12, negar: false, minimo: tipo === 'gastoMovil' ? 99 : 5, meses: 6 };
}

function esCodigo(t: string): t is TipoFila {
  return TIPOS_FILA.some((x) => x.valor === t && x.codigos);
}

/** Del editor al JSON: una fila sola va sin grupo; varias, en un grupo Y / O. */
export function editorACriterio(e: Editor): Criterio {
  const criterios = e.filas.map((f): Criterio => {
    let c: Criterio;
    if (esCodigo(f.tipo)) {
      c = {
        tipo: f.tipo,
        valores: f.valores
          .split(',')
          .map((v) => v.trim())
          .filter((v) => v !== ''),
        ventanaMeses: f.ventanaMeses,
      };
    } else if (f.tipo === 'principiosMes') {
      c = { tipo: f.tipo, minimo: f.minimo };
    } else if (f.tipo === 'principiosConsecutivos') {
      c = { tipo: f.tipo, minimo: f.minimo, meses: f.meses };
    } else if (f.tipo === 'gastoMovil') {
      c = { tipo: f.tipo, meses: 12, modo: 'percentil', percentil: f.minimo };
    } else {
      c = { tipo: f.tipo, minimo: f.minimo, ventanaMeses: f.ventanaMeses };
    }
    if (f.negar) c.negar = true;
    return c;
  });
  const unico = criterios.length === 1 ? criterios[0] : undefined;
  return unico ?? { tipo: 'grupo', op: e.op, criterios };
}

/** Del JSON al editor; nulo si el criterio no cabe en el editor sencillo (grupos anidados o particiones GCF/GRC). */
export function criterioAEditor(c: Criterio): Editor | null {
  const fila = (x: Criterio): FilaEditor | null => {
    const tipo = String(x.tipo);
    if (!TIPOS_FILA.some((t) => t.valor === tipo)) return null;
    const f = filaVacia(tipo as TipoFila);
    f.negar = x.negar === true;
    if (Array.isArray(x.valores)) f.valores = (x.valores as unknown[]).map(String).join(', ');
    if (typeof x.ventanaMeses === 'number') f.ventanaMeses = x.ventanaMeses;
    if (typeof x.minimo === 'number') f.minimo = x.minimo;
    if (typeof x.meses === 'number' && tipo === 'principiosConsecutivos') f.meses = x.meses;
    if (tipo === 'gastoMovil') {
      if (x.modo !== 'percentil') return null;
      f.minimo = typeof x.percentil === 'number' ? x.percentil : 99;
    }
    return f;
  };
  if (c.tipo === 'grupo') {
    if (c.negar === true || !Array.isArray(c.criterios)) return null;
    const filas = (c.criterios as Criterio[]).map(fila);
    if (filas.some((f) => f === null)) return null;
    return { op: c.op === 'O' ? 'O' : 'Y', filas: filas as FilaEditor[] };
  }
  const f = fila(c);
  return f ? { op: 'Y', filas: [f] } : null;
}

/** Problemas del editor antes de enviar (el servidor valida de nuevo). */
export function problemasEditor(e: Editor): string[] {
  const p: string[] = [];
  if (e.filas.length === 0) p.push('Agregue al menos un criterio.');
  e.filas.forEach((f, i) => {
    if (esCodigo(f.tipo) && f.valores.trim() === '') p.push(`Criterio ${i + 1}: escriba al menos un código.`);
    if (f.ventanaMeses < 1 || f.ventanaMeses > 60) p.push(`Criterio ${i + 1}: la ventana va de 1 a 60 meses.`);
  });
  return p;
}

// ---------- Alertas ----------

export type Severidad = 'baja' | 'media' | 'alta';
export type EstadoAlerta = 'nueva' | 'en_revision' | 'validada' | 'descartada';

export interface Alerta {
  id: number;
  tipo: string;
  tipoNombre: string;
  severidad: Severidad;
  estado: EstadoAlerta;
  nivel: string;
  nivelTexto: string;
  fuerza: string | null;
  esm: string | null;
  dimension: string;
  dimensionTexto: string;
  valor: string;
  valorTexto: string;
  desde: string;
  hasta: string;
  periodo: string;
  unidad: string;
  umbralTexto: string;
  observadoTexto: string;
  referenciaTexto: string;
  /** Nulo si la celda se oculta (pocos pacientes); el texto lo dice. */
  registros: number | null;
  registrosTexto: string;
  pacientes: number | null;
  pacientesTexto: string;
  gastoTexto: string;
  generada: string;
  /** Corte en que nació y corte de sus cifras (su última evaluación). */
  corte: number;
  corteCifras: number;
  parametrosVersion: number;
  cohorteId: number | null;
  /** Aviso por correo: no_aplica, pendiente (se reintenta), enviado o agrupado (periodo anterior, en el resumen). */
  aviso: 'no_aplica' | 'pendiente' | 'enviado' | 'agrupado';
}

export interface Seleccion {
  t: string;
  corte: number;
  desde: string;
  hasta: string;
  fuerza?: string[];
  esm?: string[];
  cum?: string[];
  atc?: string[];
}

export interface DetalleAlerta extends Alerta {
  causa: string;
  valoracion: string;
  recomendacion: string;
  fuenteReferencia: string | null;
  seleccion: Seleccion;
  ultimaEvaluacion: number;
  actualizada: string;
  avisoCorreo: boolean;
  cohorte: string | null;
  historial: { de: string; a: string; nota: string | null; responsable: string; fecha: string }[];
  puedeValidar: boolean;
  puedeRastrear: boolean;
}

export interface ResumenTipo {
  tipo: string;
  nombre: string;
  baja: number;
  media: number;
  alta: number;
  evaluacion: string;
  motivo?: string | null;
  nivelesAdmitidos?: string[];
}

export interface ListaAlertas {
  total: number;
  pagina: number;
  paginas: number;
  alertas: Alerta[];
  /** «aviso»: el corte vigente aún no tiene alertas evaluadas (el resumen es de otro corte). */
  resumen: { tipos: ResumenTipo[]; aviso?: string };
}

export interface CatalogoAlertas {
  tipos: { codigo: string; nombre: string; unidad: string }[];
  niveles: string[];
  cohortes: { id: number; codigo: string; nombre: string }[];
  puedeValidar: boolean;
  puedeParametrizar: boolean;
  puedeEjecutar: boolean;
  puedeRastrear: boolean;
}

export interface Atipicos {
  tipos: ResumenTipo[];
  total: number;
  alertas?: Alerta[];
  soloConteos: boolean;
  puedeVerConsola: boolean;
  corte: number | null;
  corteTexto: string | null;
}

export interface ParametroAlerta {
  tipo: string;
  nombre: string;
  unidad: string;
  /** Niveles que el detector evalúa (otro se rechaza con 422). */
  nivelesAdmitidos: string[];
  version: number;
  parametros: {
    activo: boolean;
    niveles: string[];
    ventana: number;
    baja: string;
    media: string;
    alta: string | null;
    extras: Record<string, unknown>;
    exclusiones: string[];
    plantilla: string;
  };
  actualizado: string;
  actualizadoPor: string;
}

/** Filtros de la consola (lista blanca del servidor), en orden fijo. */
export const FILTROS_CONSOLA = ['tipo', 'severidad', 'estado', 'nivel', 'fuerza', 'esm', 'desde', 'hasta', 'cohorte', 'atc'] as const;
export type FiltroConsola = (typeof FILTROS_CONSOLA)[number];

/** Filtros de la consola leídos de la dirección (enlace desde el tablero 10: /analitica/alertas?cohorte=…); los demás parámetros se ignoran. */
export function filtrosDeUrl(p: URLSearchParams): Partial<Record<FiltroConsola, string>> {
  const r: Partial<Record<FiltroConsola, string>> = {};
  for (const k of FILTROS_CONSOLA) {
    const v = p.get(k)?.trim();
    if (v) r[k] = v;
  }
  return r;
}

/** Ejecuciones: se consultan cada 5 s solo mientras haya una en curso y la consulta no falle. */
export function intervaloEjecuciones(datos: { estado: string }[] | undefined, conError: boolean): number | false {
  return !conError && (datos ?? []).some((e) => e.estado === 'en_curso') ? 5000 : false;
}

/** Query string de la consola: solo los filtros conocidos y con valor, en orden fijo, más la página. */
export function consultaConsola(f: Partial<Record<FiltroConsola, string>>, pagina = 1): string {
  const p = new URLSearchParams();
  for (const k of FILTROS_CONSOLA) {
    const v = f[k]?.trim();
    if (v) p.append(k, v);
  }
  if (pagina > 1) p.append('pagina', String(pagina));
  return p.toString();
}

export const COLOR_SEVERIDAD: Record<Severidad, string> = { alta: 'red', media: 'orange', baja: 'yellow' };
export const NOMBRE_ESTADO: Record<EstadoAlerta, string> = {
  nueva: 'Nueva',
  en_revision: 'En revisión',
  validada: 'Validada',
  descartada: 'Descartada',
};

/** Transiciones de la validación humana (las mismas del servidor). */
export function siguientes(e: EstadoAlerta): EstadoAlerta[] {
  switch (e) {
    case 'nueva':
      return ['en_revision', 'validada', 'descartada'];
    case 'en_revision':
      return ['validada', 'descartada'];
    case 'descartada':
      return ['en_revision'];
    default:
      return [];
  }
}

export const NOTA_MINIMA = 10;

/** Validar o descartar exige nota de 10 caracteres o más. */
export function problemaNota(a: EstadoAlerta, nota: string): string | null {
  return (a === 'validada' || a === 'descartada') && nota.trim().length < NOTA_MINIMA ? `Escriba el motivo (mínimo ${NOTA_MINIMA} caracteres)` : null;
}

/** Ruta de la pantalla de trazabilidad con la selección de la alerta (sus registros de origen, 14.9). */
export function rutaTrazaAlerta(s: Seleccion): string {
  const filtros: Partial<Record<Variable, string[]>> = {};
  for (const v of ['fuerza', 'esm', 'cum', 'atc'] as const) {
    const l = s[v];
    if (l?.length) filtros[v] = l;
  }
  const e: EstadoAnalitica = { tablero: s.t || 'general', generales: { desde: s.desde, hasta: s.hasta, corte: String(s.corte) }, filtros };
  return `/analitica/trazabilidad?${busquedaDe(e)}`;
}

/** Totales por severidad del resumen por tipo (para los indicadores del tablero 11). */
export function totalesSeveridad(tipos: ResumenTipo[]): Record<Severidad, number> {
  return tipos.reduce((a, t) => ({ alta: a.alta + t.alta, media: a.media + t.media, baja: a.baja + t.baja }), {
    alta: 0,
    media: 0,
    baja: 0,
  } as Record<Severidad, number>);
}

/** Tipos que no se pudieron evaluar en la última ejecución, con su motivo. */
export function noEvaluados(tipos: ResumenTipo[]): { nombre: string; motivo: string }[] {
  return tipos
    .filter((t) => t.evaluacion === 'no_evaluado' || t.evaluacion === 'error')
    .map((t) => ({ nombre: t.nombre, motivo: t.motivo ?? (t.evaluacion === 'error' ? 'error en la evaluación' : 'no evaluado') }));
}
