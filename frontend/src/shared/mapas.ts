import geo from './colombia-departamentos.json';

/**
 * Mapas georreferenciados (Anexo 4 §9 y §11): mapa de visitas por departamento y mapa de control con la cobertura de los puntos.
 * El mapa base son los límites departamentales del DANE ya proyectados (colombia-departamentos.json, sin servicios externos de
 * mapas); los puntos se ubican con la latitud y longitud DANE del municipio del punto.
 */

export interface Proyeccion {
  lonMin: number;
  latMax: number;
  k: number;
  kx: number;
  dx: number;
  dy: number;
}

export interface MapaBase {
  fuente: string;
  ancho: number;
  alto: number;
  principal: Proyeccion;
  recuadro: Proyeccion & { lonCorte: number; marco: number[] };
  departamentos: { codigo: string; d: string }[];
}

export const MAPA_BASE: MapaBase = geo;

export interface Estado {
  valor: string;
  etiqueta: string;
  color: string;
}

/** Estados del mapa de visitas: programada (programada, notificada y reprogramada), en ejecución y ejecutada (cerrada). */
export const ESTADOS_VISITA: Estado[] = [
  { valor: 'programada', etiqueta: 'Programada', color: 'blue' },
  { valor: 'ejecucion', etiqueta: 'En ejecución', color: 'orange' },
  { valor: 'ejecutada', etiqueta: 'Ejecutada', color: 'green' },
];

/** Estados de cobertura de un punto en el periodo del filtro. */
export const ESTADOS_COBERTURA: Estado[] = [
  { valor: 'visitado', etiqueta: 'Visitado en el periodo', color: 'green' },
  { valor: 'programado', etiqueta: 'Con visita por ejecutar', color: 'blue' },
  { valor: 'sin_visita', etiqueta: 'Sin visita en el periodo', color: 'yellow' },
  { valor: 'nunca', etiqueta: 'Nunca visitado', color: 'red' },
];

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

/** Color CSS de Mantine para un estado (el tono 6 en claro y oscuro). */
export function colorEstado(estados: Estado[], valor: string): string {
  const c = estados.find((e) => e.valor === valor)?.color ?? 'gray';
  return `var(--mantine-color-${c}-6)`;
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
