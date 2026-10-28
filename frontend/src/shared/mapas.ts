import geo from './colombia-departamentos.json';

/**
 * Mapas georreferenciados (Anexo 4 §9 y §11): mapa de visitas por departamento y mapa de control con la cobertura de los puntos.
 * El mapa base es un mapa político ya proyectado (colombia-departamentos.json, generado con scripts/generar-mapa-base.mjs y sin
 * servicios externos de mapas): departamentos del DANE con un color pastel por coloreado de grafo, países vecinos de Natural Earth,
 * etiquetas en el polo de inaccesibilidad y el recuadro de San Andrés. Los puntos se ubican con la latitud y longitud DANE del
 * municipio del punto.
 */

export interface Proyeccion {
  lonMin: number;
  latMax: number;
  k: number;
  kx: number;
  dx: number;
  dy: number;
}

export interface DepartamentoBase {
  codigo: string;
  nombre: string;
  /** nombre en mayúsculas, en uno o dos renglones */
  renglones: string[];
  /** índice del fondo pastel (0 a 4); dos vecinos nunca comparten índice */
  color: number;
  vecinos: string[];
  /** [x0, y0, x1, y1] */
  caja: number[];
  /** polo de inaccesibilidad */
  etiqueta: number[];
  /** en los departamentos pequeños, dónde va el nombre (con una línea guía hasta el polo) */
  guia?: number[];
  d: string;
}

export interface MapaBase {
  fuente: string;
  ancho: number;
  alto: number;
  principal: Proyeccion;
  recuadro: Proyeccion & { lonCorte: number; marco: number[] };
  paises: { codigo: string; d: string; nombre?: string; etiqueta?: number[] }[];
  mares: { nombre: string; etiqueta: number[] }[];
  departamentos: DepartamentoBase[];
}

export const MAPA_BASE: MapaBase = geo;

/** Los cinco fondos de la paleta LUME, con el hex de respaldo mientras la interfaz adopta los tokens. */
const FONDOS = [
  'var(--lume-success-bg, #DCEFE1)',
  'var(--lume-warning-bg, #F5E8D3)',
  'var(--lume-info-bg, #DBEAFE)',
  'var(--lume-danger-bg, #F5DEDE)',
  'var(--lume-neutral-bg, #E2E8F0)',
];
const NEUTRO = 'var(--lume-neutral-bg, #E2E8F0)';
export const fondoDepartamento = (color: number): string => FONDOS[color] ?? NEUTRO;

const COLOR_EJECUTADA = 'var(--lume-success-text, #15803D)';
const COLOR_PROGRAMADA = 'var(--lume-info-text, #1D4ED8)';
/** Base de la coropleta: neutra, para que las marcas de color sigan viéndose encima. */
export const COLOR_COROPLETA = 'var(--lume-neutral-text, #475569)';
const COLOR_EJECUCION = 'var(--lume-warning-text, #92400E)';
const COLOR_NUNCA = 'var(--lume-danger, #B91C1C)';
const COLOR_DESCONOCIDO = 'var(--lume-neutral-text, #475569)';

export interface Estado {
  valor: string;
  etiqueta: string;
  /** color de Mantine para la insignia y la casilla */
  color: string;
  /** color semántico LUME de la marca en el mapa */
  marca: string;
}

/** Estados del mapa de visitas: programada (programada, notificada y reprogramada), en ejecución y ejecutada (cerrada). */
export const ESTADOS_VISITA: Estado[] = [
  {
    valor: 'programada',
    etiqueta: 'Programada',
    color: 'blue',
    marca: COLOR_PROGRAMADA,
  },
  {
    valor: 'ejecucion',
    etiqueta: 'En ejecución',
    color: 'orange',
    marca: COLOR_EJECUCION,
  },
  {
    valor: 'ejecutada',
    etiqueta: 'Ejecutada',
    color: 'green',
    marca: COLOR_EJECUTADA,
  },
];

/** Estados de cobertura de un punto en el periodo del filtro. */
export const ESTADOS_COBERTURA: Estado[] = [
  {
    valor: 'visitado',
    etiqueta: 'Visitado en el periodo',
    color: 'green',
    marca: COLOR_EJECUTADA,
  },
  {
    valor: 'programado',
    etiqueta: 'Con visita por ejecutar',
    color: 'blue',
    marca: COLOR_PROGRAMADA,
  },
  {
    valor: 'sin_visita',
    etiqueta: 'Sin visita en el periodo',
    color: 'orange',
    marca: COLOR_EJECUCION,
  },
  {
    valor: 'nunca',
    etiqueta: 'Nunca visitado',
    color: 'red',
    marca: COLOR_NUNCA,
  },
];

export interface Capital {
  codigo: string;
  nombre: string;
  departamento: string;
  latitud: number;
  longitud: number;
}

export interface FilaDepartamento {
  codigo: string;
  nombre: string;
  total: number;
  [estado: string]: string | number;
}

export interface Ubicado {
  id: number;
  municipio: string;
  departamento: string;
  latitud: number;
  longitud: number;
  estado: string;
}

export interface VisitaMapa extends Ubicado {
  numero: string | null;
  fecha: string;
  tipo: string;
  punto: string;
  unidad: string;
  fuerza: string;
}

export interface PuntoMapa extends Ubicado {
  nombre: string;
  unidad: string;
  fuerza: string;
  modalidad: string | null;
  ultimaCerrada: string | null;
}

export interface DatosMapa<T> {
  totales: Record<string, number>;
  porDepartamento: FilaDepartamento[];
  sinUbicacion: number;
  /** el mapa de visitas trae un máximo de visitas */
  truncado?: boolean;
  filtros: Record<string, string>;
  visitas?: T[];
  puntos?: T[];
}

/** Coordenadas SVG de una latitud y longitud; lo que queda al oeste del corte (San Andrés y Providencia) va al recuadro. */
export function proyectar(latitud: number, longitud: number, base: MapaBase = MAPA_BASE): [number, number] {
  const p = longitud < base.recuadro.lonCorte ? base.recuadro : base.principal;
  return [p.dx + (longitud - p.lonMin) * p.kx, p.dy + (p.latMax - latitud) * p.k];
}

/**
 * Posición de cada marca: las del mismo municipio comparten coordenadas, así que se reparten en espiral (girasol) alrededor del
 * municipio, en el orden recibido, para que todas se vean y se puedan señalar.
 */
export function ubicar<T extends Ubicado>(marcas: T[], separacion = 3.2, base: MapaBase = MAPA_BASE): (T & { x: number; y: number })[] {
  const vistos = new Map<string, number>();
  return marcas.map((m) => {
    const clave = `${m.latitud},${m.longitud}`;
    const i = vistos.get(clave) ?? 0;
    vistos.set(clave, i + 1);
    const [x, y] = proyectar(m.latitud, m.longitud, base);
    const r = separacion * Math.sqrt(i);
    const a = i * 2.399963; // ángulo áureo
    return { ...m, x: x + r * Math.cos(a), y: y + r * Math.sin(a) };
  });
}

/** Color de la marca de un estado (semántico LUME). */
export function colorEstado(estados: Estado[], valor: string): string {
  return estados.find((e) => e.valor === valor)?.marca ?? COLOR_DESCONOCIDO;
}

/** Texto de los conteos de un departamento: «Programada 3 · En ejecución 1 · Ejecutada 0». */
export function textoConteos(estados: Estado[], fila: Record<string, string | number> | undefined): string {
  return estados.map((e) => `${e.etiqueta} ${Number(fila?.[e.valor] ?? 0)}`).join(' · ');
}

/** Aviso de lo que no se pudo ubicar (municipio sin coordenadas o punto sin municipio). */
export function textoSinUbicacion(n: number, que: 'visitas' | 'puntos'): string | null {
  if (n <= 0) return null;
  const uno = que === 'visitas' ? 'visita' : 'punto';
  return n === 1 ? `1 ${uno} sin ubicación no aparece en el mapa.` : `${n} ${que} sin ubicación no aparecen en el mapa.`;
}

/** Cobertura del periodo: porcentaje de puntos visitados (entero) o null sin puntos. */
export function porcentajeCobertura(totales: Record<string, number> | undefined): number | null {
  const t = ESTADOS_COBERTURA.reduce((s, e) => s + Number(totales?.[e.valor] ?? 0), 0);
  return t === 0 ? null : Math.round((100 * Number(totales?.visitado ?? 0)) / t);
}

const MINUSCULAS = new Set(['de', 'del', 'la', 'las', 'los', 'y']);

/** Nombre corto de las capitales cuyo nombre oficial es largo para el mapa. */
const NOMBRES_CORTOS: Record<string, string> = { '13001': 'Cartagena', '54001': 'Cúcuta', '76001': 'Cali' };

/** Nombre de una capital en el mapa: el corto si lo hay, si no el DANE en tipo oración. */
export const nombreEnMapa = (c: { codigo: string; nombre: string }): string => NOMBRES_CORTOS[c.codigo] ?? nombreCapital(c.nombre);

/** Nombre DANE en mayúsculas → «San José del Guaviare». */
export function nombreCapital(nombre: string): string {
  return nombre
    .toLocaleLowerCase('es')
    .split(' ')
    .map((p, i) =>
      i > 0 && MINUSCULAS.has(p) ? p : p.replace(/(^|\.)(\p{L})/gu, (_, a: string, l: string) => a + l.toLocaleUpperCase('es')),
    )
    .join(' ');
}

/** Límites superiores de las 5 clases (intervalos iguales de 1 al máximo), sin repetir. */
export function cortesCoropleta(max: number): number[] {
  if (max <= 0) return [];
  return [...new Set([1, 2, 3, 4, 5].map((i) => Math.ceil((max * i) / 5)))];
}

/** Clase de un valor: 0 sin dato; de 1 en adelante, el primer corte que lo contiene. */
export function claseCoropleta(valor: number | undefined, cortes: number[]): number {
  if (valor === undefined) return 0;
  const i = cortes.findIndex((c) => valor <= c);
  return i < 0 ? cortes.length : i + 1;
}

// Hasta el 70 %: en la clase más alta el nombre del departamento se sigue leyendo
const MEZCLAS = [14, 28, 42, 56, 70];

/**
 * Relleno de la coropleta: el fondo neutro sin dato y mezclas crecientes del color base con blanco. Las `clases` se reparten entre
 * la mezcla más clara y la más intensa, así que la última siempre es la más intensa aunque haya menos de 5.
 */
export function colorCoropleta(base: string, clase: number, clases = 5): string {
  if (clase <= 0) return NEUTRO;
  const i = clases <= 1 ? MEZCLAS.length - 1 : Math.round(((Math.min(clase, clases) - 1) * (MEZCLAS.length - 1)) / (clases - 1));
  return `color-mix(in srgb, ${base} ${MEZCLAS[i]}%, white)`;
}

/** Vista del mapa: escala y desplazamiento en unidades del SVG. */
export interface VistaMapa {
  k: number;
  x: number;
  y: number;
}

export const VISTA_INICIAL: VistaMapa = { k: 1, x: 0, y: 0 };
export const ZOOM_MAX = 12;

/** Ajusta la escala a [1, ZOOM_MAX] y el desplazamiento para que el mapa siempre cubra el lienzo. */
export function limitar(v: VistaMapa, base: MapaBase = MAPA_BASE): VistaMapa {
  const k = Math.min(ZOOM_MAX, Math.max(1, v.k));
  const entre = (n: number, min: number) => Math.min(0, Math.max(min, n)) + 0; // + 0 quita el -0
  return {
    k,
    x: entre(v.x, base.ancho * (1 - k)),
    y: entre(v.y, base.alto * (1 - k)),
  };
}

/** Multiplica la escala dejando quieto el punto (px, py) del lienzo. */
export function zoomEn(v: VistaMapa, factor: number, px: number, py: number, base: MapaBase = MAPA_BASE): VistaMapa {
  const k = Math.min(ZOOM_MAX, Math.max(1, v.k * factor));
  return limitar({ k, x: px - ((px - v.x) / v.k) * k, y: py - ((py - v.y) / v.k) * k }, base);
}

/** Vista que centra y agranda una caja [x0, y0, x1, y1] (un departamento), con margen y a lo sumo 8 aumentos. */
export function vistaCaja(caja: number[], base: MapaBase = MAPA_BASE): VistaMapa {
  const [x0 = 0, y0 = 0, x1 = 0, y1 = 0] = caja;
  const k = Math.min(8, Math.max(1, 0.8 * Math.min(base.ancho / Math.max(x1 - x0, 1), base.alto / Math.max(y1 - y0, 1))));
  return limitar(
    {
      k,
      x: base.ancho / 2 - ((x0 + x1) / 2) * k,
      y: base.alto / 2 - ((y0 + y1) / 2) * k,
    },
    base,
  );
}

const KM_POR_GRADO = 111.32;
const KM_REDONDOS = [1000, 500, 300, 200, 100, 50, 20, 10, 5, 2, 1];

/** Barra de escala para el zoom k: los km redondos más largos que caben en 90 unidades del lienzo, y su largo. */
export function escala(k: number, base: MapaBase = MAPA_BASE): { km: number; largo: number } {
  const porKm = (base.principal.k * k) / KM_POR_GRADO;
  const km = KM_REDONDOS.find((n) => n * porKm <= 90) ?? 1;
  return { km, largo: km * porKm };
}
