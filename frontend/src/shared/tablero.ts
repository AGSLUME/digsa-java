/**
 * Tipos y funciones puras de la fase 9 (docs/especificaciones/riesgo-tablero): dashboard de riesgo por ESM (puntaje, pesos,
 * incidencias de la DIGSA) y dashboard de visitas (indicadores y gráficas de barras).
 */
import { cifra, hoyBogota } from './formato';
import { puedeEntrar, type DatosSesion } from './sesion';

// ---------- Dashboard de riesgo ----------

export type Nivel = 'alto' | 'medio' | 'bajo';

export interface Agregado {
  esm: number;
  promedio: number;
  niveles: Record<Nivel, number>;
  factores: Record<string, number>;
}

export interface DatosEsm {
  c: number;
  nc: number;
  recurrentes: number;
  inmediata: number;
  planOplVencido: boolean;
  accionesVencidas: number;
  incidencias: number;
}

export interface FilaRiesgo {
  puntoId: number;
  nombre: string;
  fuerza: string;
  municipio: string | null;
  total: number;
  nivel: Nivel;
  factores: Record<string, number>;
  datos: DatosEsm;
  ultimaCerrada: string | null;
  diasSinVisita: number | null;
  sinResumen: boolean;
}

export interface TableroRiesgo {
  pais: Agregado;
  porFuerza: { fuerza: string; agregado: Agregado; principal: string }[];
  esm: FilaRiesgo[];
  pesos: Record<string, number>;
  nombres: Record<string, string>;
  factores: string[];
  umbrales: { alto: number; medio: number };
  estadoPesos: string;
  fuerzas: string[];
  filtroFuerza: string | null;
}

export interface Incidencia {
  id: number;
  puntoId: number;
  punto: string;
  fuerza: string;
  fecha: string;
  descripcion: string;
  fuente: string | null;
  estado: 'abierta' | 'cerrada';
  motivoCierre: string | null;
}

/** Formatos que acepta /api/riesgo/exportar y /api/dashboard/exportar (ExportadorTablero). */
export const FORMATOS_DESCARGA: { valor: string; etiqueta: string }[] = [
  { valor: 'xlsx', etiqueta: 'Excel' },
  { valor: 'csv', etiqueta: 'CSV' },
  { valor: 'pdf', etiqueta: 'PDF' },
  { valor: 'txt', etiqueta: 'TXT' },
];

/** Cómo se calcula cada factor (Riesgo.java), para la explicación de pesos. */
export const EXPLICACION_FACTOR: Record<string, string> = {
  incumplimiento: 'Peso × No cumple ÷ (Cumple + No cumple) de la última visita completa cerrada.',
  recurrencia: 'Hallazgos de la última visita cerrada que ya eran hallazgo en la anterior (tope 5).',
  inmediata: 'Hallazgos marcados «Requiere intervención inmediata del OPL» en la última visita completa (tope 3).',
  planes: 'La mitad del peso si hay plan del OPL vencido, más una décima del peso por cada acción vencida (tope 5).',
  incidencias: 'Incidencias abiertas reportadas por la DIGSA en los últimos 12 meses (tope 3).',
};

/**
 * Nivel de riesgo: `color` es el de la barra (decorativa) e `insignia` el tono de la insignia rellena, elegido para contraste AA del
 * texto (blanco sobre rojo y verde oscuros; negro sobre naranja claro, con autoContrast).
 */
const NIVELES: Record<Nivel, { color: string; insignia: string; etiqueta: string }> = {
  alto: { color: 'red', insignia: 'red.9', etiqueta: 'Alto' },
  medio: { color: 'orange', insignia: 'orange.4', etiqueta: 'Medio' },
  bajo: { color: 'green', insignia: 'green.9', etiqueta: 'Bajo' },
};

/** Colores de Mantine y etiqueta de un nivel de riesgo; un valor desconocido sale gris con su texto. */
export function nivelRiesgo(nivel: string): { color: string; insignia: string; etiqueta: string } {
  return NIVELES[nivel as Nivel] ?? { color: 'gray', insignia: 'gray.7', etiqueta: nivel || '—' };
}

/**
 * Tono de un texto de color sobre fondo claro con contraste suficiente (los tonos .6 de rojo, naranja y verde no llegan a AA):
 * rojo .8, naranja y verde .9. Sin color, undefined; un color con tono explícito se respeta.
 */
export function tonoTexto(color: string | undefined): string | undefined {
  if (!color || color.includes('.')) return color;
  return ({ red: 'red.8', orange: 'orange.9', green: 'green.9' } as Record<string, string>)[color] ?? color;
}

/** Puntaje o promedio con 2 decimales y coma decimal, sin separador de miles (Anexo 5 num. 2.4): «72,00». Sin dato, «—». */
export function decimal(n: number | null | undefined): string {
  return cifra(n);
}

/** Nivel y puntaje en texto para lectores de pantalla y tarjetas: «Riesgo alto, 72,00 de 100». */
export function textoNivel(nivel: string, total: number): string {
  return `Riesgo ${nivelRiesgo(nivel).etiqueta.toLowerCase()}, ${decimal(total)} de 100`;
}

/** Ancho de una barra en % (0 a 100): valor sobre el máximo; sin máximo positivo, 0. */
export function porcentajeBarra(valor: number, maximo: number): number {
  if (!Number.isFinite(valor) || !Number.isFinite(maximo) || maximo <= 0 || valor <= 0) return 0;
  return Math.min(100, Math.round((valor / maximo) * 10000) / 100);
}

/** Suma de los pesos (los vacíos cuentan 0). */
export function sumaPesos(pesos: Record<string, number | string | null | undefined>, factores: string[]): number {
  return factores.reduce((s, f) => s + (Number(pesos[f]) || 0), 0);
}

/**
 * Validación en el cliente de pesos y umbrales (la misma de Riesgo.Pesos.problema()): cada peso entero de 0 a 100, suman 100 y
 * 100 ≥ alto > medio > 0. Devuelve el motivo o null si sirven.
 */
export function problemaPesos(
  pesos: Record<string, number | string | null | undefined>,
  factores: string[],
  nombres: Record<string, string>,
  alto: number | string | null | undefined,
  medio: number | string | null | undefined,
): string | null {
  return camposPesos(pesos, factores, nombres, alto, medio)?.mensaje ?? null;
}

/**
 * Lo mismo que problemaPesos, con los campos que hay que marcar: el factor cuyo peso falla, «alto» y/o «medio». La suma no es de un
 * solo campo (sin campos).
 */
export function camposPesos(
  pesos: Record<string, number | string | null | undefined>,
  factores: string[],
  nombres: Record<string, string>,
  alto: number | string | null | undefined,
  medio: number | string | null | undefined,
): { mensaje: string; campos: string[] } | null {
  for (const f of factores) {
    const n = numero(pesos[f]);
    if (!Number.isInteger(n) || n < 0 || n > 100) return { mensaje: `El peso de «${nombres[f] ?? f}» debe ser un entero entre 0 y 100.`, campos: [f] };
  }
  const suma = sumaPesos(pesos, factores);
  if (suma !== 100) return { mensaje: `Los pesos deben sumar 100 (suman ${suma}).`, campos: [] };
  const a = numero(alto);
  const m = numero(medio);
  if (!Number.isInteger(a) || !Number.isInteger(m)) {
    const campos = [...(Number.isInteger(a) ? [] : ['alto']), ...(Number.isInteger(m) ? [] : ['medio'])];
    return { mensaje: 'Los umbrales deben ser números enteros.', campos };
  }
  if (!(a <= 100 && a > m && m > 0)) return { mensaje: 'Los umbrales deben cumplir 100 ≥ alto > medio > 0.', campos: ['alto', 'medio'] };
  return null;
}

/** Número de un campo (NumberInput da número o texto); vacío o ausente es NaN, no 0. */
function numero(v: number | string | null | undefined): number {
  return typeof v === 'number' ? v : typeof v === 'string' && v.trim() !== '' ? Number(v) : NaN;
}

/** Cuerpo de PUT /api/riesgo/pesos. */
export function cuerpoPesos(pesos: Record<string, number | string>, factores: string[], alto: number, medio: number) {
  const p: Record<string, number> = {};
  for (const f of factores) p[f] = Number(pesos[f]);
  return { pesos: p, alto, medio };
}

/** Pesos y umbrales: la coordinación (el PUT exige la pantalla de la agenda: Coordinador y Administrador). */
export function puedeEditarPesos(s: DatosSesion): boolean {
  return puedeEntrar(s, 'agenda');
}

/**
 * Incidencias de la DIGSA: Coordinador, Administrador y DIGSA. Se usa la pantalla de prórrogas, que tiene exactamente esos perfiles
 * (los nombres de los perfiles los puede cambiar el administrador); el servidor igual responde 403 a quien no.
 */
export function puedeRegistrarIncidencias(s: DatosSesion): boolean {
  return puedeEditarPesos(s) || puedeEntrar(s, 'prorrogas');
}

export interface FormIncidencia {
  puntoId: string;
  fecha: string;
  descripcion: string;
  fuente: string;
}

/** Validación de una incidencia nueva (como ServicioRiesgo.registrarIncidencia): ESM, fecha no futura, descripción 10–2000, fuente ≤ 200. */
export function problemaIncidencia(f: FormIncidencia, hoy: string): string | null {
  return Object.values(erroresIncidencia(f, hoy))[0] ?? null;
}

/** Las mismas reglas de problemaIncidencia, campo por campo (puntoId, fecha, descripcion, fuente), en el mismo orden, para marcar cada uno. */
export function erroresIncidencia(f: FormIncidencia, hoy: string): Partial<Record<keyof FormIncidencia, string>> {
  const e: Partial<Record<keyof FormIncidencia, string>> = {};
  if (!f.puntoId) e.puntoId = 'Elija el ESM.';
  if (!/^\d{4}-\d{2}-\d{2}$/.test(f.fecha) || Number.isNaN(Date.parse(f.fecha))) e.fecha = 'La fecha no es válida.';
  else if (f.fecha > hoy) e.fecha = 'La fecha de la incidencia no puede ser futura.';
  const d = f.descripcion.trim().length;
  if (d < 10 || d > 2000) e.descripcion = 'Describa la incidencia (de 10 a 2000 caracteres).';
  if (f.fuente.trim().length > 200) e.fuente = 'La fuente tiene máximo 200 caracteres.';
  return e;
}

/** Motivo del cierre de una incidencia: de 10 a 800 caracteres sin contar los espacios alrededor. */
export function problemaCierre(motivo: string): string | null {
  const n = motivo.trim().length;
  return n < 10 || n > 800 ? 'Escriba el motivo del cierre (de 10 a 800 caracteres).' : null;
}

/** Fecha de hoy en Bogotá (no la del equipo) en AAAA-MM-DD. */
export function hoyIso(d: Date = new Date()): string {
  return hoyBogota(d);
}

// ---------- Dashboard de visitas ----------

export interface Barra {
  etiqueta: string;
  valor: number;
}

export interface FiltrosDashboard {
  desde: string;
  hasta: string;
  fuerza: string;
  tipo: string;
  auditor: string;
  unidad: string;
}

export const FILTROS_VACIOS: FiltrosDashboard = { desde: '', hasta: '', fuerza: '', tipo: '', auditor: '', unidad: '' };

export interface OpcionesDashboard {
  fuerzas: { codigo: string; nombre: string }[];
  tipos: { codigo: string; nombre: string }[];
  auditores?: { id: number; nombre: string }[];
  unidades: string[];
  estados: Record<string, string>;
}

export interface FilaCategoria {
  codigo: string;
  nombre: string;
  c: number;
  nc: number;
  na: number;
  pendientes: number;
}

/** Código de la categoría de las obligaciones de la minuta en el resumen. */
export const CATEGORIA_OBLIGACIONES = 'OBL';

/** Etiqueta de una categoría como en la descarga: «C05 · Nombre»; las obligaciones (y un código sin nombre) solo con su nombre. */
export function etiquetaCategoria(x: Pick<FilaCategoria, 'codigo' | 'nombre'>): string {
  return x.codigo === CATEGORIA_OBLIGACIONES || !x.nombre || x.nombre === x.codigo ? x.nombre || x.codigo : `${x.codigo} · ${x.nombre}`;
}

export interface DatosDashboard {
  indicadores: {
    total: number;
    porEjecutar: number;
    programadas: number;
    notificadas: number;
    reprogramadas: number;
    ejecucion: number;
    cerradas: number;
    canceladas: number;
    criticas: number;
    planesOplVencidos: number;
  };
  porEstado: Barra[];
  porTipo: Barra[];
  porFuerza: Barra[];
  porUnidad: Barra[];
  porAuditor?: Barra[];
  lineaBase: { total: number; cerradas: number; enCurso: number; sinProgramar: number; plazo: string | null };
  resultados: {
    c: number;
    nc: number;
    na: number;
    hallazgos: number;
    inmediata: number;
    planesAbiertos: number;
    habilitacion: { habilitado: number; tramite: number; no_habilitado: number };
    /** Componentes de la versión vigente y «Obligaciones del contrato» (código OBL), también en 0; NC descendente (25.3). */
    porCategoria: FilaCategoria[];
    /** «Operador logístico» (ESM y nivel central), «Farmacia alterna» y «Sin prestador» si lo hay (25.3). */
    porPrestador: { prestador: string; c: number; nc: number; na: number; pendientes: number }[];
    /** Todos los vacíos de mayor riesgo de la versión vigente con sus ítems en No cumple (también en 0). */
    vacios: { codigo: number; nombre: string; nc: number }[];
  };
  filtros: Record<string, string>;
}

/** Query string de los filtros (solo los que tienen valor, sin espacios alrededor), en orden fijo. Sin filtros, cadena vacía. */
export function consultaFiltros(f: Partial<FiltrosDashboard>, extra: Record<string, string> = {}): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(extra)) if (v) p.set(k, v);
  for (const k of ['desde', 'hasta', 'fuerza', 'tipo', 'auditor', 'unidad'] as const) {
    const v = (f[k] ?? '').trim();
    if (v) p.set(k, v);
  }
  return p.toString();
}

/** Fecha completa y válida AAAA-MM-DD (un campo de fecha a medio escribir no dispara la consulta). */
export function fechaCompleta(v: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(v)) return false;
  const d = new Date(v + 'T12:00:00Z');
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === v;
}

/** Filtros que se envían: las fechas solo cuando están completas y son válidas. */
export function filtrosConsultables(f: FiltrosDashboard): FiltrosDashboard {
  return { ...f, desde: fechaCompleta(f.desde) ? f.desde : '', hasta: fechaCompleta(f.hasta) ? f.hasta : '' };
}

/** Problema del rango de fechas: «Desde» posterior a «Hasta» (ambas completas). */
export function problemaFechas(f: FiltrosDashboard): string | null {
  return problemaRango(f.desde, f.hasta);
}

/** Rango de un filtro de fechas: «Desde» posterior a «Hasta» (ambas completas). El mensaje va junto al campo «Hasta». */
export function problemaRango(desde: string, hasta: string): string | null {
  return fechaCompleta(desde) && fechaCompleta(hasta) && desde > hasta ? 'La fecha «Desde» es posterior a «Hasta».' : null;
}

/**
 * Texto de «Filtros aplicados» (lo que devolvió el servidor) con los nombres de las opciones: fuerza y tipo por su nombre y el auditor
 * por su nombre en lugar del id. Lo que no está en las opciones sale tal cual.
 */
export function textoFiltrosAplicados(filtros: Record<string, string> | null | undefined, o: OpcionesDashboard | undefined): string {
  const nombre = (k: string, v: string): string => {
    if (!o) return v;
    if (k === 'Fuerza') return o.fuerzas.find((x) => x.codigo === v)?.nombre ?? v;
    if (k === 'Tipo') return o.tipos.find((x) => x.codigo === v)?.nombre ?? v;
    if (k === 'Auditor') return o.auditores?.find((x) => String(x.id) === v)?.nombre ?? v;
    return v;
  };
  return Object.entries(filtros ?? {})
    .map(([k, v]) => `${k}: ${nombre(k, v)}`)
    .join(' · ');
}

/** Ruta con su query string (sin «?» cuando no hay parámetros). */
export function conConsulta(ruta: string, consulta: string): string {
  return consulta ? `${ruta}?${consulta}` : ruta;
}

/** Máximo de una serie de barras (0 si está vacía). */
export function maximo(barras: Barra[]): number {
  return barras.reduce((m, b) => Math.max(m, Number(b.valor) || 0), 0);
}

/** Texto de la tarjeta de alertas críticas con los planes del OPL vencidos (novena ronda 3.2). */
export function textoPlanesVencidos(n: number): string {
  return n === 1 ? '1 plan del OPL vencido' : `${n} planes del OPL vencidos`;
}
