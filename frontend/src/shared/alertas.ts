import { cifra } from './formato';

/**
 * Alertas (docs/especificaciones/exportacion-alertas; GMI P75 «tiempo real o cuasi real» y P77 b «críticas por correo y SMS»): el
 * catálogo de tipos que edita el administrador (GET/PUT /api/alertas/catalogo) y los eventos con la latencia evento → envío que ven el
 * administrador y la coordinación (GET /api/alertas/eventos, POST /api/alertas/eventos/{id}/cerrar).
 */

export interface TipoAlerta {
  tipo: string;
  nombre: string;
  descripcion: string | null;
  critica: boolean;
  activa: boolean;
  sms: boolean;
  perfiles: string[];
  usuarios: number[];
  destinatarios: number;
  sinTelefono: number;
}

export interface UsuarioDestino {
  id: number;
  nombre: string;
  usuario: string;
}

export interface CatalogoAlertas {
  tipos: TipoAlerta[];
  perfiles: string[];
  usuarios: UsuarioDestino[];
}

export interface EventoAlerta {
  id: number;
  tipo: string;
  nombre: string;
  critica: boolean;
  detalle: string | null;
  abierta: boolean;
  veces: number | null;
  creado: string | null;
  ultimo: string | null;
  ultimoAviso: string | null;
  ultimoSms: string | null;
  cerradaEn: string | null;
  correos: number | null;
  correosEnviados: number | null;
  correosFallidos: number | null;
  latenciaCorreoMs: number | null;
  smsEnviados: number | null;
  smsPendientes: number | null;
  smsSinEnviar: number | null;
  latenciaSmsMs: number | null;
}

export interface Bandeja {
  pendientes: number | null;
  enviando: number | null;
  fallidos24h: number | null;
  latenciaP50Ms: number | null;
  latenciaP95Ms: number | null;
}

export interface EventosAlertas {
  eventos: EventoAlerta[];
  bandeja24h: Bandeja;
}

/** Refresco de la lista de eventos (cuasi real). */
export const REFRESCO_EVENTOS_MS = 30_000;

export const NOMBRE_PERFIL: Record<string, string> = {
  ADMINISTRADOR: 'Administrador',
  COORDINADOR: 'Coordinador',
  DIGSA: 'DIGSA',
  ANALISTA: 'Analista',
  AUDITOR: 'Auditor',
  SANIDAD: 'Sanidad',
};

export function nombrePerfil(p: string): string {
  return NOMBRE_PERFIL[p] ?? p;
}

/**
 * Latencia legible en español con 2 decimales (Anexo 5 num. 2.4): «—» sin dato, «40,00 ms» bajo 100 ms, «0,50 s» bajo 1 min, luego
 * «2 min 5 s» o «1 h 3 min».
 */
export function latenciaLegible(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || !Number.isFinite(ms) || ms < 0) return '—';
  if (ms < 100) return `${cifra(ms)} ms`;
  const s = ms / 1000;
  if (s < 59.995) return `${cifra(s)} s`;
  const total = Math.round(s);
  const min = Math.floor(total / 60);
  const resto = total % 60;
  if (min < 60) return resto ? `${min} min ${resto} s` : `${min} min`;
  const h = Math.floor(min / 60);
  const m = min % 60;
  return m ? `${h} h ${m} min` : `${h} h`;
}

/**
 * Igual que reglas/Alertas.normalizarTelefono del backend: móvil colombiano en E.164 («+57» y 10 dígitos que empiezan por 3). Admite
 * espacios, guiones, puntos, paréntesis y los prefijos +57, 0057 o 57 (este solo con 12 dígitos). @return null si no es un móvil
 */
export function normalizarTelefono(telefono: string | null | undefined): string | null {
  if (telefono === null || telefono === undefined) return null;
  let t = telefono.trim().replace(/[\s().-]+/g, '');
  if (t.startsWith('+57')) t = t.substring(3);
  else if (t.startsWith('0057')) t = t.substring(4);
  else if (t.startsWith('57') && t.length === 12) t = t.substring(2);
  return /^3[0-9]{9}$/.test(t) ? '+57' + t : null;
}

export const ERROR_TELEFONO = 'El teléfono debe ser un móvil colombiano: +57 y 10 dígitos que empiezan por 3 (p. ej. +57 300 000 0000).';

/**
 * Error del teléfono en el formulario de usuarios, o null si vale. Vacío vale; el valor ya guardado se conserva aunque sea antiguo
 * (igual que ServicioUsuarios.telefonoMovil, para no impedir editar a usuarios registrados antes de la validación).
 */
export function errorTelefono(escrito: string, guardado: string | null = null): string | null {
  const t = escrito.trim();
  if (!t || t === guardado) return null;
  return normalizarTelefono(t) ? null : ERROR_TELEFONO;
}

/** «+57 300 000 0000» para mostrar un E.164 colombiano; el texto tal cual si no lo es. */
export function telefonoLegible(telefono: string): string {
  const e = normalizarTelefono(telefono);
  return e ? `+57 ${e.substring(3, 6)} ${e.substring(6, 9)} ${e.substring(9)}` : telefono;
}

/** Estado legible de un evento: abierta o cerrada. */
export function estadoEvento(e: Pick<EventoAlerta, 'abierta'>): { etiqueta: string; color: string } {
  return e.abierta ? { etiqueta: 'Abierta', color: 'red' } : { etiqueta: 'Cerrada', color: 'gray' };
}

/** Resumen de los correos de un evento: «2/3 enviados · 1 fallido» o «sin correos». */
export function resumenCorreos(e: Pick<EventoAlerta, 'correos' | 'correosEnviados' | 'correosFallidos'>): string {
  const total = e.correos ?? 0;
  if (!total) return 'Sin correos';
  const fallidos = e.correosFallidos ?? 0;
  return `${e.correosEnviados ?? 0}/${total} enviados` + (fallidos ? ` · ${fallidos} fallido${fallidos === 1 ? '' : 's'}` : '');
}

/** Resumen de los SMS de un evento; las alertas operativas no llevan SMS. */
export function resumenSms(e: Pick<EventoAlerta, 'critica' | 'smsEnviados' | 'smsPendientes' | 'smsSinEnviar'>): string {
  const env = e.smsEnviados ?? 0;
  const pen = e.smsPendientes ?? 0;
  const sin = e.smsSinEnviar ?? 0;
  if (!env && !pen && !sin) return e.critica ? 'Sin SMS' : 'No aplica';
  const partes = [`${env} enviado${env === 1 ? '' : 's'}`];
  if (pen) partes.push(`${pen} pendiente${pen === 1 ? '' : 's'}`);
  if (sin) partes.push(`${sin} sin enviar`);
  return partes.join(' · ');
}

/**
 * ¿Cumple la bandeja el «cuasi real»? Se toma el p95 de las últimas 24 h frente a un umbral (por defecto 5 min, el primer reintento
 * más el ciclo de la tarea). Sin datos, null.
 */
export function bandejaCuasiReal(b: Pick<Bandeja, 'latenciaP95Ms'>, umbralMs = 5 * 60_000): boolean | null {
  return b.latenciaP95Ms === null || b.latenciaP95Ms === undefined ? null : b.latenciaP95Ms <= umbralMs;
}

/** Cuerpo del PUT del catálogo para un tipo. El SMS solo se envía activo en las críticas. */
export function cuerpoEdicion(t: Pick<TipoAlerta, 'tipo' | 'nombre' | 'critica' | 'activa' | 'sms' | 'perfiles' | 'usuarios'>) {
  return { tipo: t.tipo, nombre: t.nombre.trim(), activa: t.activa, sms: t.critica && t.sms, perfiles: t.perfiles, usuarios: t.usuarios };
}
