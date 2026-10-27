/**
 * Propuesta de agenda (docs/especificaciones/propuesta-agenda): tipos de la API `/api/propuesta` y reglas de pantalla puras
 * (qué botones mostrar por ciclo, opciones de líder y de otros, cuerpo del ajuste de una fila).
 */

import { fecha } from './formato';

/** Otros auditores además del líder (Reparto.MAX_OTROS). */
export const MAX_OTROS = 5;

/** Roles que pueden liderar una visita de la propuesta (PropuestaAgenda.ROLES_LIDER); el ingeniero y «otro» acompañan. */
export const ROLES_LIDER = ['quimico_farmaceutico', 'regente', 'auditor_salud'];

export const NOMBRE_ROL: Record<string, string> = {
  quimico_farmaceutico: 'Químico farmacéutico',
  regente: 'Regente de farmacia',
  auditor_salud: 'Auditor en salud',
  ingeniero_sistemas: 'Ingeniero de sistemas',
  otro: 'Otro',
};

export interface AnioContrato {
  anio: number;
  desde: string;
  hasta: string;
}

export interface CicloPropuesta {
  ciclo: string;
  nombre: string;
  tipo: string;
  desde?: string;
  limite?: string;
  anioContrato?: number | null;
  total?: number;
  cerradas?: number;
  agendadas?: number;
  faltan?: number;
  borradorId?: number | null;
  confirmadaIncompletaId?: number | null;
  error?: string;
}

export interface AuditorPropuesta {
  id: number;
  nombre: string;
  rol: string;
}

export interface DatosPropuesta {
  inicioContrato: string | null;
  anio: number;
  anios: AnioContrato[];
  ciclos: CicloPropuesta[];
  auditores: AuditorPropuesta[];
  visitasPorDia: number;
  kmMismoDia: number;
  avisos: string[];
}

export interface ResumenPropuesta {
  visitas?: number;
  auditores?: number;
  dias?: number;
  desde?: string | null;
  hasta?: string | null;
  cabe?: boolean;
  alertas?: string[];
  texto?: string;
}

export interface ItemPropuesta {
  id: number;
  puntoId: number;
  punto: string;
  unidad: string;
  municipio: string | null;
  auditorId: number | null;
  lider: string | null;
  otros: { id: number; nombre: string }[];
  fecha: string | null;
  fijo: boolean;
  km: number | null;
  motivo: string | null;
  alertas: string[];
  visitaId: number | null;
  visita: string | null;
  resultado: string | null;
}

export type EstadoPropuesta = 'borrador' | 'confirmada' | 'descartada';

export interface Propuesta {
  id: number;
  ciclo: string;
  nombre: string;
  anioContrato: number | null;
  desde: string;
  hasta: string;
  estado: EstadoPropuesta;
  resumen: ResumenPropuesta;
  items: ItemPropuesta[];
}

export interface RespuestaConfirmar {
  mensaje: string;
  programadas: number;
  notificadas: number;
  parcial: boolean;
}

export const NOMBRE_ESTADO: Record<EstadoPropuesta, string> = {
  borrador: 'Borrador',
  confirmada: 'Confirmada',
  descartada: 'Descartada',
};

export function etiquetaAnio(a: AnioContrato): string {
  return `Año de contrato ${a.anio} (${fecha(a.desde)} – ${fecha(a.hasta)})`;
}

export interface BotonesCiclo {
  /** «Proponer agenda» o, con borrador, «Proponer de nuevo». */
  proponer: string;
  /** Sin nada que proponer (faltan = 0) o sin periodo (falta el inicio del contrato). */
  proponerDeshabilitado: boolean;
  /** Proponer de nuevo descarta el borrador actual con sus ajustes: se advierte antes. */
  advertirDescarte: boolean;
  verBorrador: boolean;
  continuarConfirmacion: boolean;
}

/** Botones de la tarjeta de un ciclo según su cobertura y sus propuestas. */
export function botonesCiclo(c: CicloPropuesta): BotonesCiclo {
  const conBorrador = c.borradorId != null;
  return {
    proponer: conBorrador ? 'Proponer de nuevo' : 'Proponer agenda',
    proponerDeshabilitado: Boolean(c.error) || (c.faltan ?? 0) <= 0,
    advertirDescarte: conBorrador,
    verBorrador: conBorrador,
    continuarConfirmacion: c.confirmadaIncompletaId != null,
  };
}

/** Periodo del ciclo para la tarjeta. */
export function periodoCiclo(c: CicloPropuesta): string {
  if (!c.desde || !c.limite) return '—';
  return `${fecha(c.desde)} a ${fecha(c.limite)}`;
}

/** Edición de una fila en la pantalla (los ids van como texto, como los usan los Select de Mantine). */
export interface EdicionFila {
  fecha: string;
  lider: string | null;
  otros: string[];
}

export function edicionDe(i: ItemPropuesta): EdicionFila {
  return { fecha: i.fecha ?? '', lider: i.auditorId == null ? null : String(i.auditorId), otros: i.otros.map((o) => String(o.id)) };
}

/** ¿La edición cambia algo de la fila? */
export function filaCambiada(i: ItemPropuesta, e: EdicionFila): boolean {
  const o = edicionDe(i);
  return o.fecha !== e.fecha || o.lider !== e.lider || o.otros.join(',') !== e.otros.join(',');
}

/** Cuerpo de `PUT /api/propuesta/items/{id}`: sin repetir, sin el líder entre los otros y máximo 5. */
export function cuerpoAjuste(e: EdicionFila): { auditorId: number | null; otros: number[]; fecha: string } {
  const lider = e.lider ? Number(e.lider) : null;
  const otros = [...new Set(e.otros.map(Number))].filter((x) => Number.isFinite(x) && x !== lider).slice(0, MAX_OTROS);
  return { auditorId: lider, otros, fecha: e.fecha };
}

function etiquetaAuditor(a: AuditorPropuesta): string {
  return `${a.nombre} · ${NOMBRE_ROL[a.rol] ?? a.rol}`;
}

/**
 * Opciones de auditor líder: los que pueden liderar (químico farmacéutico, regente, auditor en salud). Si el punto exige químico
 * farmacéutico lo valida el servidor. El líder actual sale siempre, aunque ya no pueda liderar, para no perderlo de vista.
 */
export function opcionesLider(
  auditores: AuditorPropuesta[],
  actual: string | null,
  guardado?: { id: number | null; nombre: string | null },
): { value: string; label: string }[] {
  const r = auditores
    .filter((a) => ROLES_LIDER.includes(a.rol) || String(a.id) === actual)
    .map((a) => ({ value: String(a.id), label: etiquetaAuditor(a) }));
  // El líder guardado que ya no está entre los auditores activos (inactivado) sigue visible con su nombre
  if (guardado?.id != null && String(guardado.id) === actual && !r.some((x) => x.value === actual)) {
    r.push({ value: actual, label: (guardado.nombre ?? 'Auditor ' + actual) + ' (inactivo)' });
  }
  return r;
}

/** Opciones de otros auditores: todos los activos menos el líder; los que ya están en la fila salen aunque falten en la lista. */
export function opcionesOtros(
  auditores: AuditorPropuesta[],
  lider: string | null,
  actuales: { id: number; nombre: string }[] = [],
): { value: string; label: string }[] {
  const r = auditores.filter((a) => String(a.id) !== lider).map((a) => ({ value: String(a.id), label: etiquetaAuditor(a) }));
  for (const o of actuales) {
    if (String(o.id) !== lider && !r.some((x) => x.value === String(o.id))) r.push({ value: String(o.id), label: o.nombre });
  }
  return r;
}

/** Filas con líder y fecha: las que se programarían al confirmar. */
export function filasProgramables(p: Propuesta): number {
  return p.items.filter((i) => i.auditorId != null && i.fecha).length;
}

/** Filas de una propuesta confirmada que aún no tienen resultado (confirmación cortada). */
export function filasSinResultado(p: Propuesta): number {
  return p.items.filter((i) => !i.resultado).length;
}
