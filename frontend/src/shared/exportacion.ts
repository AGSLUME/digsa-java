import { cifra, conteo } from './formato';

/**
 * Exportación completa de los datos (docs/especificaciones/exportacion-alertas): se pide con POST /api/exportacion, se genera en
 * segundo plano y la pantalla consulta su estado hasta que queda lista; la descarga es un enlace directo (el navegador la escribe
 * en disco en flujo, sin cargar el ZIP en memoria).
 */

export type EstadoExportacion = 'pendiente' | 'generando' | 'lista' | 'fallida' | 'vencida';

export interface Exportacion {
  id: number;
  estado: EstadoExportacion;
  tamano: number | null;
  tablas: number | null;
  filas: number | null;
  soportes: number | null;
  huellasDistintas: number | null;
  noEncontrados: number | null;
  sha256: string | null;
  error: string | null;
  creado: string | null;
  iniciado: string | null;
  terminado: string | null;
  vence: string | null;
}

export const NOMBRE_ESTADO: Record<EstadoExportacion, string> = {
  pendiente: 'En cola',
  generando: 'Generando',
  lista: 'Lista',
  fallida: 'Fallida',
  vencida: 'Vencida',
};

export const COLOR_ESTADO: Record<EstadoExportacion, string> = {
  pendiente: 'gray',
  generando: 'blue',
  lista: 'green',
  fallida: 'red',
  vencida: 'orange',
};

/** Cada cuánto se consulta el estado mientras hay una exportación en curso. */
export const INTERVALO_SONDEO_MS = 3000;

export function enCurso(e: Pick<Exportacion, 'estado'>): boolean {
  return e.estado === 'pendiente' || e.estado === 'generando';
}

/** Intervalo de react-query: sondea solo mientras haya una pendiente o generando; si no, se detiene (false). */
export function intervaloSondeo(lista: Pick<Exportacion, 'estado'>[] | undefined): number | false {
  return (lista ?? []).some(enCurso) ? INTERVALO_SONDEO_MS : false;
}

/**
 * Estado que se muestra: una «lista» cuyo plazo ya pasó se ve como vencida aunque la tarea de limpieza aún no la haya marcado (el
 * servidor respondería 410 a la descarga).
 */
export function estadoVisible(e: Pick<Exportacion, 'estado' | 'vence'>, ahora: number = Date.now()): EstadoExportacion {
  if (e.estado === 'lista' && e.vence) {
    const vence = Date.parse(e.vence);
    if (!Number.isNaN(vence) && vence <= ahora) return 'vencida';
  }
  return e.estado;
}

/** Tamaño legible en unidades de 1024 con 2 decimales y coma decimal (Anexo 5 num. 2.4): «0 B», «512 B», «1,50 KB», «1,00 MB», «2,50 GB». */
export function tamanoLegible(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || !Number.isFinite(bytes) || bytes < 0) return '—';
  const unidades = ['B', 'KB', 'MB', 'GB', 'TB'];
  let valor = bytes;
  let i = 0;
  while (valor >= 1024 && i < unidades.length - 1) {
    valor /= 1024;
    i++;
  }
  const texto = i === 0 ? conteo(valor) : cifra(valor);
  return `${texto} ${unidades[i]}`;
}

export function rutaDescarga(id: number): string {
  return `/api/exportacion/${id}/descarga`;
}

export function rutaSoportesOpl(visitaId: number): string {
  return `/api/hallazgos/${visitaId}/soportes-opl.zip`;
}

/** Soportes del OPL de una visita (suma de los de sus hallazgos; al ESM no le llega el dato y queda en 0). */
export function soportesOplDeVisita(v: { hallazgos: { soportes?: number }[] }): number {
  return v.hallazgos.reduce((s, h) => s + (h.soportes ?? 0), 0);
}

/**
 * Quién ve el enlace de los soportes del OPL (duda 22.12): la DIGSA, Sanidad y el administrador. Lo decide el servidor por perfil
 * (`descargaSoportesOpl` de cada visita) y además la visita debe tener soportes.
 */
export function veSoportesOpl(permitido: boolean | undefined, soportes: number): boolean {
  return !!permitido && soportes > 0;
}
