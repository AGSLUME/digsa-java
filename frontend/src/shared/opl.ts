/** Tipos y utilidades de la respuesta del OPL a los hallazgos del acta (docs/especificaciones/flujo-opl). */

import { ErrorApi } from './api';

export type EstadoHallazgo = 'sin_respuesta' | 'aceptado' | 'aceptado_auto' | 'objetado' | 'ratificado' | 'levantado';
export type FaseOpl = 'objeciones' | 'plan' | 'vencido';
export type EstadoPlanOpl = 'pendiente' | 'firmado' | 'vencido' | 'reportado_digsa' | 'cerrado';

export interface SoporteOpl {
  id: number;
  nombre: string;
  tipo: string;
  tamano: number;
  sha256: string;
  subido: string | null;
}

export interface HallazgoOpl {
  clave: string;
  codigo: string;
  origen: 'item' | 'obligacion';
  texto: string;
  hallazgo: string | null;
  /** Parte del 5W2H que diligenció el auditor en la visita (solo lectura para el OPL). */
  planAuditor: {
    causas: string | null;
    que: string | null;
    porque: string | null;
    donde: string | null;
    inicio: string | null;
    fin: string | null;
    indicador: string | null;
    estado: string;
  };
  estado: EstadoHallazgo;
  decision: 'acepta' | 'objeta' | null;
  justificacion: string | null;
  quien: string | null;
  como: string | null;
  respondio: string | null;
  canal: string | null;
  resolucion: 'ratifica' | 'levanta' | null;
  resolucionMotivo: string | null;
  soportes: SoporteOpl[];
}

export interface PlanOpl {
  estado: EstadoPlanOpl;
  nombre?: string | null;
  cargo?: string | null;
  // Sin el número de documento: queda en la base y en el acta, no en la respuesta (minimización, Ley 1581)
  canal?: string | null;
  firmado?: string | null;
}

export interface DetalleOpl {
  visitaId: number;
  numero: string;
  punto: string;
  fase: FaseOpl;
  enviado: string;
  venceObjeciones: string;
  vencePlan: string;
  venceObjecionesLargo: string;
  vencePlanLargo: string;
  abierta: boolean;
  hallazgos: HallazgoOpl[];
  faltanPlan: string[];
  plan: PlanOpl;
  actaArchivoId: number | null;
}

/** Respuestas del OPL vistas desde la visita por el equipo y la coordinación. */
export type OplDeVisita = { tieneEnlace: false } | (DetalleOpl & { tieneEnlace: true; esLider: boolean });

/** Fila de «Respuesta a hallazgos» del usuario OPL. */
export interface VisitaOpl {
  visitaId: number;
  numero: string;
  punto: string;
  enviado: string;
  hallazgos: number;
  sinRespuesta: number;
  faltanPlan: number;
  planEstado: EstadoPlanOpl;
  fase: FaseOpl;
  venceObjeciones: string;
  vencePlan: string;
}

/** Lo que devuelve el enlace del acta al abrirlo (sin usuario). */
export interface EnlaceOpl {
  numero: string;
  punto: string;
  fase: FaseOpl;
  vencePlan: string;
  /** Correos del OPL enmascarados; el código se pide por su posición. */
  correos: string[];
  verificado: boolean;
}

export const MAX_SOPORTES = 10;
export const DIAS_OBJECIONES = 3;
export const DIAS_PLAN = 5;

export const NOMBRE_ESTADO: Record<EstadoHallazgo, string> = {
  sin_respuesta: 'Sin respuesta',
  aceptado: 'Aceptado',
  aceptado_auto: 'Aceptado automáticamente',
  objetado: 'Objetado · pendiente del auditor líder',
  ratificado: 'Ratificado por el auditor líder',
  levantado: 'Levantado por el auditor líder',
};

export const COLOR_ESTADO: Record<EstadoHallazgo, string> = {
  sin_respuesta: 'gray',
  aceptado: 'blue',
  aceptado_auto: 'blue',
  objetado: 'orange',
  ratificado: 'red',
  levantado: 'green',
};

export const NOMBRE_FASE: Record<FaseOpl, string> = {
  objeciones: 'Plazo para aceptar u objetar',
  plan: 'Plazo para el plan de mejoramiento',
  vencido: 'Plazo vencido',
};

export const COLOR_FASE: Record<FaseOpl, string> = { objeciones: 'blue', plan: 'orange', vencido: 'red' };

export const NOMBRE_PLAN: Record<EstadoPlanOpl, string> = {
  pendiente: 'Pendiente',
  firmado: 'Firmado',
  vencido: 'Vencido',
  reportado_digsa: 'Reportado a la DIGSA',
  cerrado: 'Cerrado',
};

/** Aceptados, aceptados automáticamente o ratificados: los que van al plan (necesitan quién y cómo). */
export function vaAlPlan(estado: EstadoHallazgo): boolean {
  return estado === 'aceptado' || estado === 'aceptado_auto' || estado === 'ratificado';
}

/** El plan ya tiene la firma del OPL (también si luego se cerró). */
export function planFirmado(p: PlanOpl): boolean {
  return p.estado === 'firmado' || (p.estado === 'cerrado' && !!p.firmado);
}

/**
 * Hallazgos objetados que el auditor líder aún no decide: mientras haya alguno el plan no se firma (decisión D11 del 2026-10-21; el
 * servidor lo rechaza con el mismo texto).
 */
export function objecionesPorDecidir(hallazgos: HallazgoOpl[]): string[] {
  return hallazgos.filter((h) => h.estado === 'objetado').map((h) => h.codigo);
}

/** Se puede aceptar u objetar: enlace abierto, fase de objeciones, plan sin firmar y sin decisión del líder. */
export function puedeResponder(d: DetalleOpl, h: HallazgoOpl): boolean {
  return d.abierta && d.fase === 'objeciones' && !planFirmado(d.plan) && h.resolucion === null;
}

/** Se suben soportes solo a un hallazgo objetado (sin decisión del líder), en la fase de objeciones y sin plan firmado. */
export function puedeSubirSoportes(d: DetalleOpl, h: HallazgoOpl): boolean {
  return d.abierta && d.fase === 'objeciones' && !planFirmado(d.plan) && h.estado === 'objetado' && h.soportes.length < MAX_SOPORTES;
}

/** Quién y cómo se editan si el hallazgo va al plan y el plan no venció; con el plan firmado solo se completa lo vacío. */
export function editaQuienComo(d: DetalleOpl, h: HallazgoOpl): boolean {
  if (!d.abierta || !vaAlPlan(h.estado)) return false;
  if (planFirmado(d.plan)) return !(h.quien ?? '').trim() && !(h.como ?? '').trim();
  return d.fase !== 'vencido';
}

/** Error de la respuesta (nombre, cargo y justificación) antes de enviarla; null si está bien. Mismos límites que el servidor. */
export function errorRespuesta(decision: 'acepta' | 'objeta' | null, justificacion: string, nombre: string, cargo: string): string | null {
  const n = nombre.trim();
  const c = cargo.trim();
  if (n.length < 3 || n.length > 150 || c.length < 2 || c.length > 150) {
    return 'Escriba el nombre (3 a 150 caracteres) y el cargo (2 a 150) de quien responde.';
  }
  if (!decision) return 'Elija si acepta u objeta el hallazgo.';
  const j = justificacion.trim();
  if (decision === 'objeta' && (j.length < 10 || j.length > 4000)) return 'La objeción necesita la justificación (de 10 a 4000 caracteres).';
  return null;
}

/** Error de la firma del plan antes de enviarla; null si está bien. */
export function errorFirmaPlan(nombre: string, cargo: string, documento: string, imagen: string | null): string | null {
  const n = nombre.trim();
  const c = cargo.trim();
  if (n.length < 3 || n.length > 150 || c.length < 2 || c.length > 150)
    return 'Escriba el nombre (3 a 150 caracteres) y el cargo (2 a 150) de quien firma.';
  if (!/^[0-9A-Za-z.-]{4,30}$/.test(documento.trim())) return 'Escriba el número de documento de quien firma.';
  if (!imagen) return 'Dibuje la firma.';
  return null;
}

export interface QuienComo {
  quien: string;
  como: string;
}

/** Quién y cómo efectivos de un hallazgo: la edición local si la hay, si no lo guardado. */
export function valorQuienComo(h: HallazgoOpl, ediciones: Record<string, QuienComo>): QuienComo {
  return ediciones[h.clave] ?? { quien: h.quien ?? '', como: h.como ?? '' };
}

/** Filas de quién y cómo que cambiaron, solo de los hallazgos que van al plan. */
export function quienComoCambiados(hallazgos: HallazgoOpl[], ediciones: Record<string, QuienComo>): { clave: string; quien: string; como: string }[] {
  const r: { clave: string; quien: string; como: string }[] = [];
  for (const h of hallazgos) {
    const e = ediciones[h.clave];
    if (!e || !vaAlPlan(h.estado)) continue;
    const quien = e.quien.trim();
    const como = e.como.trim();
    if (quien !== (h.quien ?? '').trim() || como !== (h.como ?? '').trim()) r.push({ clave: h.clave, quien, como });
  }
  return r;
}

/** Códigos que van al plan y aún no tienen quién y cómo, con las ediciones locales aplicadas. */
export function faltanQuienComo(hallazgos: HallazgoOpl[], ediciones: Record<string, QuienComo>): string[] {
  return hallazgos
    .filter((h) => {
      if (!vaAlPlan(h.estado)) return false;
      const v = valorQuienComo(h, ediciones);
      return !v.quien.trim() || !v.como.trim();
    })
    .map((h) => h.codigo);
}

/** El mensaje del servidor trae un aviso que no salió («Aviso al auditor líder fallido.»): se muestra tal cual, pero en naranja. */
export function conAvisoFallido(mensaje: string): boolean {
  return /fallido|no enviado/i.test(mensaje);
}

/** Token del enlace del acta: el de la dirección (y se guarda en la pestaña) o, al recargar sin él, el guardado; '' si no hay. */
export function tokenDelEnlace(deLaDireccion: string | null, almacen: Pick<Storage, 'getItem' | 'setItem'> | null, clave: string): string {
  const t = (deLaDireccion ?? '').trim();
  try {
    if (t) {
      almacen?.setItem(clave, t);
      return t;
    }
    return almacen?.getItem(clave) ?? '';
  } catch {
    // sin almacenamiento (ventana privada): solo sirve el de la dirección
    return t;
  }
}

/** El enlace del acta pide verificar el código otra vez (403 con `reverificar`): lo escrito en la pantalla se conserva. */
export function pideReverificar(e: unknown): boolean {
  return e instanceof ErrorApi && e.estado === 403 && e.datos.reverificar === true;
}

/** La sesión del QR venció (401 con `motivo: 'enlace'`): se reabre el enlace con el mismo token y se verifica otra vez, sin perder lo escrito. */
export function pideReabrir(e: unknown): boolean {
  return e instanceof ErrorApi && e.estado === 401 && e.datos.motivo === 'enlace';
}

/** Errores de negocio tras los que la pantalla quedó vieja (venció la fase, otro firmó, acta nueva): se vuelve a cargar el detalle. */
export function pideRefrescar(e: unknown): boolean {
  return e instanceof ErrorApi && (e.estado === 409 || e.estado === 410 || e.estado === 422);
}
