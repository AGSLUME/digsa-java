/** Tipos y utilidades de la agenda de visitas (coordinador, auditores y perfiles de consulta). */

export interface AlertaVisita {
  nivel: string;
  texto: string;
}

/** Fila de /api/agenda y /api/calendario (este último sin alertas ni nivel). */
export interface FilaVisita {
  id: number;
  numero: string;
  fecha: string;
  hora: string | null;
  estado: string;
  estadoNombre: string;
  tipo: string;
  tipoNombre: string;
  puntoId: number;
  punto: string;
  unidad: string;
  fuerza: string;
  municipio: string | null;
  lider: string;
  integrantes: number;
  alertas?: AlertaVisita[];
  nivel?: string;
  equipoSinNotificar?: boolean;
}

export interface TipoVisita {
  codigo: string;
  nombre: string;
  unidad: string | null;
  modalidad: string | null;
  aviso: string;
}

export interface PuntoOpcion {
  id: number;
  nombre: string;
  sigla: string | null;
  unidad: string;
  fuerza: string;
  modalidad: string | null;
  exigeQf: boolean;
  municipio: string | null;
}

export interface AuditorOpcion {
  id: number;
  nombre: string;
  rol: string;
}

export interface ComponenteOpcion {
  codigo: string;
  nombre: string;
  unidades: string[];
  items: number;
  especial: string | null;
  sistemas: number;
}

export interface OpcionesAgenda {
  tipos: TipoVisita[];
  puntos: PuntoOpcion[];
  auditores: AuditorOpcion[];
  roles: Record<string, string>;
  version: string | null;
  componentes: ComponenteOpcion[];
  estados: Record<string, string>;
  motivosReapertura: Record<string, string>;
}

export interface MiembroEquipo {
  id: number;
  nombre: string;
  rol: string;
  rolNombre: string;
  lider: boolean;
  activo: boolean;
}

export interface ParteReparto {
  parte: string;
  nombre: string;
  auditorId: number;
  auditor: string | null;
}

export interface EventoHistorial {
  fecha: string;
  usuario: string | null;
  evento: string;
  detalle: string | null;
}

export interface NotificacionEnviada {
  creado: string;
  origen: string;
  estado: string;
  error: string | null;
  destinatarios: number;
  asunto: string | null;
  usuario: string | null;
}

export interface DetalleVisita {
  id: number;
  numero: string;
  tipo: string;
  tipoNombre: string;
  aviso: string;
  puntoId: number;
  punto: string;
  unidad: string;
  fuerza: string;
  modalidad: string | null;
  fecha: string;
  fechaLarga: string;
  hora: string | null;
  estado: string;
  estadoNombre: string;
  alcance: string | null;
  fechaSolicitud: string | null;
  checklistVersion: string | null;
  equipo?: MiembroEquipo[];
  componentes?: { codigo: string; nombre: string }[];
  reparto?: ParteReparto[];
  acciones: string[];
  historial?: EventoHistorial[];
  notificaciones?: NotificacionEnviada[];
  equipoSinNotificar?: boolean;
  advertencias?: string[];
}

export interface ResultadoVisita {
  id: number;
  numero: string;
  mensaje: string;
  notificacionOk: boolean | null;
  advertencias: string[] | null;
}

export const COLOR_ESTADO: Record<string, string> = {
  programada: 'gray',
  notificada: 'blue',
  reprogramada: 'yellow',
  ejecucion: 'orange',
  cerrada: 'green',
  cancelada: 'red',
};

const COLOR_NIVEL: Record<string, string> = { ok: 'green', info: 'blue', advertencia: 'orange', peligro: 'red' };

export function colorEstado(estado: string): string {
  return COLOR_ESTADO[estado] ?? 'gray';
}

export function colorNivel(nivel: string | undefined): string {
  return (nivel && COLOR_NIVEL[nivel]) || 'gray';
}

/** Hora del servidor ("08:00" o "08:00:00") a HH:MM para mostrar y para el control de hora. */
export function horaCorta(hora: string | null | undefined): string {
  return hora ? hora.substring(0, 5) : '';
}

/** "a@x.co, b@x.co" → ["a@x.co", "b@x.co"] sin vacíos. */
export function separarCorreos(texto: string): string[] {
  return texto
    .split(/[,;\s]+/)
    .map((c) => c.trim())
    .filter((c) => c.length > 0);
}
