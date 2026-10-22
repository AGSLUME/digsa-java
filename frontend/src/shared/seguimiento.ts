/**
 * Tipos y utilidades del seguimiento al plan de mejoramiento (docs/especificaciones/seguimiento): «Prestadores con hallazgos» y
 * «Prórrogas del plan». La prórroga del 5W2H del checklist está en checklist.ts.
 */

import type { EstadoHallazgo, EstadoPlanOpl } from './opl';

// ---------- Prestadores con hallazgos ----------

export type SituacionPlan = 'programada' | 'plan_cerrado' | 'escalar' | 'sin_programar';

/** Respuesta del OPL al hallazgo; el ESM solo recibe «vigente» o «levantado» (decisión 12.2). */
export type EstadoOplHallazgo = EstadoHallazgo | 'vigente';

export interface HallazgoPrestador {
  clave: string;
  codigo: string;
  texto: string | null;
  hallazgo: string | null;
  fin: string | null;
  estadoPlan: string | null;
  estadoOpl: EstadoOplHallazgo;
  // Sin estos tres para el ESM (decisión 12.2)
  justificacion?: string | null;
  resolucionMotivo?: string | null;
  soportes?: number;
}

export interface VisitaConHallazgos {
  visitaId: number;
  numero: string;
  fecha: string;
  auditor: string;
  esLider: boolean;
  /** N° de seguimiento (null en la visita de origen). */
  seguimientoN: number | null;
  origenNumero: string | null;
  hallazgos: HallazgoPrestador[];
  abiertas: number;
  planEstado: EstadoPlanOpl | null;
  planFirmado: boolean;
  vencePlan: string | null;
  siguiente: { id: number; numero: string; fecha: string; estado: string } | null;
  situacion: SituacionPlan;
  abreChecklist: boolean;
  /** El servidor indica si este usuario descarga el ZIP de soportes del OPL (DIGSA, Sanidad y administrador; duda 22.12). */
  descargaSoportesOpl?: boolean;
}

export interface PrestadorConHallazgos {
  puntoId: number;
  punto: string;
  unidad: string;
  fuerza: string;
  visitas: VisitaConHallazgos[];
}

export const NOMBRE_SITUACION: Record<SituacionPlan, string> = {
  programada: 'Seguimiento programado',
  plan_cerrado: 'Plan cerrado',
  escalar: 'Escalar: máximo de seguimientos',
  sin_programar: 'Sin seguimiento programado',
};

export const COLOR_SITUACION: Record<SituacionPlan, string> = {
  programada: 'blue',
  plan_cerrado: 'green',
  escalar: 'red',
  sin_programar: 'orange',
};

export const NOMBRE_OPL: Record<EstadoOplHallazgo, string> = {
  sin_respuesta: 'Sin respuesta',
  aceptado: 'Aceptado',
  aceptado_auto: 'Aceptado automáticamente',
  objetado: 'Objetado · pendiente del auditor líder',
  ratificado: 'Ratificado por el auditor líder',
  levantado: 'Levantado por el auditor líder',
  vigente: 'Vigente',
};

export const COLOR_OPL: Record<EstadoOplHallazgo, string> = {
  sin_respuesta: 'gray',
  aceptado: 'blue',
  aceptado_auto: 'blue',
  objetado: 'orange',
  ratificado: 'red',
  levantado: 'green',
  vigente: 'gray',
};

/** «Seguimiento N° 2 de la visita V-0001» o «Visita de origen». */
export function textoCadena(v: Pick<VisitaConHallazgos, 'seguimientoN' | 'origenNumero'>): string {
  if (!v.seguimientoN) return 'Visita de origen';
  return `Seguimiento N° ${v.seguimientoN}` + (v.origenNumero ? ` de la visita ${v.origenNumero}` : '');
}

// ---------- Prórrogas del plan ----------

export type EstadoProrroga = 'solicitada' | 'aprobada' | 'rechazada';
export type DecisionProrroga = 'aprueba' | 'rechaza';
export type LadoProrroga = 'coord' | 'digsa';

export interface Prorroga {
  id: number;
  /** Lo calcula el servidor: perfil propio, su lado sin decidir, no es quien la pidió ni quien decidió el otro lado. */
  puedeDecidir: boolean;
  estado: EstadoProrroga;
  codigo: string;
  clave: string;
  finAnterior: string;
  finNuevo: string;
  justificacion: string;
  coordDecision: DecisionProrroga | null;
  coordNombre: string | null;
  coordFecha: string | number | null;
  digsaDecision: DecisionProrroga | null;
  digsaNombre: string | null;
  digsaFecha: string | number | null;
  motivoRechazo: string | null;
  visitaId: number;
  numero: string;
  origenNumero: string;
  punto: string;
  fechaSeguimiento: string;
  creado: string | number | null;
}

export const NOMBRE_PRORROGA: Record<EstadoProrroga, string> = { solicitada: 'Solicitada', aprobada: 'Aprobada', rechazada: 'Rechazada' };
export const COLOR_PRORROGA: Record<EstadoProrroga, string> = { solicitada: 'yellow', aprobada: 'green', rechazada: 'red' };
export const NOMBRE_DECISION: Record<DecisionProrroga, string> = { aprueba: 'Aprobó', rechaza: 'Rechazó' };

/** Límites del motivo del rechazo (ServicioProrrogas.decidir). */
export const MOTIVO_MIN = 10;
export const MOTIVO_MAX = 800;

/**
 * Lado de quien decide, como en el servidor: el Coordinador por la coordinación y la DIGSA por la DIGSA. Con los dos perfiles a la vez,
 * solo con Administrador u otro perfil no decide (el servidor responde 403): null y la pantalla queda de consulta.
 */


/** Error del motivo del rechazo antes de enviarlo; null si está bien. */
export function errorMotivo(motivo: string): string | null {
  const n = motivo.trim().length;
  return n < MOTIVO_MIN || n > MOTIVO_MAX ? `Escriba el motivo del rechazo (de ${MOTIVO_MIN} a ${MOTIVO_MAX} caracteres).` : null;
}

/**
 * Fecha y hora en hora militar de Bogotá («2026-10-21 14:05»). El servidor manda el instante como texto ISO con zona o como número
 * (milisegundos); null o vacío da «—».
 */
export function fechaHora(v: string | number | null | undefined): string {
  if (v === null || v === undefined || v === '') return '—';
  const d = typeof v === 'number' ? new Date(v) : /[zZ]|[+-]\d{2}:?\d{2}$/.test(v) ? new Date(v) : null;
  if (d === null) {
    // Sin zona: ya viene en la hora de Bogotá
    return v.toString().replace('T', ' ').substring(0, 16);
  }
  if (Number.isNaN(d.getTime())) return '—';
  const partes = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'America/Bogota',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(d);
  const p = (t: string) => partes.find((x) => x.type === t)?.value ?? '';
  return `${p('year')}-${p('month')}-${p('day')} ${p('hour')}:${p('minute')}`;
}
