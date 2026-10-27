/** Tipos y utilidades del formulario de ejecución de la visita (checklist, personal, firmas y acta). */
import { aNumero, cifra, porcentaje } from './formato';

export type Calificacion = 'C' | 'NC' | 'NA';

export interface Archivo {
  id: number;
  nombre: string;
  tipo: string;
  tamano: number;
  sha256: string;
  subidoPor?: string | null;
}

export interface ItemChecklist {
  id: number;
  codigo: string;
  parte: string;
  responsable: string | null;
  editable: boolean;
  punto: string;
  criterio: string | null;
  requisito: string | null;
  evidencia: string | null;
  condicion: string | null;
  admiteNoAplica: boolean;
  calificacion: Calificacion | null;
  hallazgo: string | null;
  /** Decisión D5: validación de habilitación (C00) que en «No cumple» puede marcarse «habilitación en trámite». */
  admiteTramite?: boolean;
  /** Marcado «habilitación en trámite» (solo en «No cumple»; sigue siendo hallazgo). */
  enTramite?: boolean;
  /** Fase 9: marcado «Requiere intervención inmediata del OPL» (solo en «No cumple»; factor del puntaje de riesgo). */
  inmediata?: boolean;
  /** «it:<código>» para un ítem, «ob:<código>» para una obligación del contrato (clave del hallazgo y de sus soportes). */
  clave?: string;
  /** Obligación de la minuta del prestador visitado (parte OBL), no un ítem del checklist. */
  obligacion?: boolean;
  archivos: Archivo[];
  /** Solo en un seguimiento: el hallazgo y la acción del plan de la visita de origen (solo lectura). */
  anterior?: HallazgoAnterior | null;
  /** Solo en un seguimiento: soportes vigentes de la visita de origen, que se descargan sin poder cambiarlos. */
  archivosAnteriores?: ArchivoAnterior[];
}

/** Hallazgo de la visita de origen que se verifica en el seguimiento, con su acción del plan. */
export interface HallazgoAnterior {
  codigo: string;
  texto: string | null;
  hallazgo: string | null;
  fin: string | null;
  estado: string;
  que: string | null;
}

export interface ArchivoAnterior {
  id: number;
  nombre: string;
  tipo: string;
  tamano: number;
}

/** Datos del seguimiento al plan de mejoramiento (null si la visita no es un seguimiento). */
export interface DatosSeguimiento {
  n: number;
  ordinal: string;
  origenId: number;
  origenNumero: string;
  origenFecha: string;
  aviso: string;
}

export interface ComponenteChecklist {
  codigo: string;
  nombre: string;
  escalaPropia: boolean;
  items: ItemChecklist[];
}

export interface Ejecucion {
  inicioReal?: string | null;
  finReal?: string | null;
  objeto?: string | null;
  recomendaciones?: string | null;
  observacionesAuditor?: string | null;
  observacionesEsm?: string | null;
  observacionesOpl?: string | null;
}

export interface PersonaAuditada {
  entidad: string;
  nombre: string;
  cargo: string | null;
  documento: string | null;
  correo: string | null;
}

export type RolFirma = 'auditor' | 'esm' | 'opl' | 'w5_auditor' | 'w5_auditado';

export interface Firma {
  rol: RolFirma;
  nombre: string | null;
  documento: string | null;
  cargo: string | null;
  firmado: string | null;
}

export interface Acta {
  ronda: number;
  estado: string;
  generado: string | null;
  archivoId: number | null;
  destinatarios: number | null;
  error: string | null;
}

export interface PermisosFormulario {
  escribible: boolean;
  motivo: string | null;
  lider: boolean;
  todo: boolean;
  correccion: boolean;
  escribeVisita: boolean;
  misPartes: string[];
  enviaActa: boolean;
}

export interface DatosFormulario {
  visita: {
    id: number;
    numero: string;
    tipo: string;
    lugar: string;
    sigla: string | null;
    direccion: string | null;
    municipio: string | null;
    departamento: string | null;
    unidad: string;
    fuerza: string;
    modalidad: string | null;
    fechaProgramada: string;
    fechaLarga: string;
    hora: string | null;
    alcance: string | null;
    estado: string;
    estadoNombre: string;
    checklistVersion: string | null;
    ronda: number;
    lider: string | null;
    equipo: { id: number; nombre: string; rol: string; lider: boolean }[];
  };
  permisos: PermisosFormulario;
  ejecucion: Ejecucion;
  personal: PersonaAuditada[];
  firmas: Firma[];
  componentes: ComponenteChecklist[];
  archivosVisita: Archivo[];
  resumen: { c: number; nc: number; na: number; pendientes: number; hallazgos: number; cumplimiento: string | null };
  faltantes: string[];
  actas: Acta[];
  plan: DatosPlan;
  seguimiento?: DatosSeguimiento | null;
}

/** Fila del plan de mejoramiento 5W2H como la guarda el servidor (parte del auditor; quién y cómo los pone el OPL). */
export interface FilaPlan {
  causas?: string | null;
  que?: string | null;
  porque?: string | null;
  donde?: string | null;
  inicio?: string | null;
  fin?: string | null;
  indicador?: string | null;
  estado?: string | null;
  avance?: number | null;
  /** Del OPL (remitidos con el plan firmado): en el seguimiento se ven en solo lectura. */
  quien?: string | null;
  como?: string | null;
  /** Justificación de la prórroga de esta visita (no pasa al siguiente seguimiento). */
  prorroga_justificacion?: string | null;
}

export interface HallazgoPlan {
  clave: string;
  codigo: string;
  origen: 'item' | 'obligacion';
  texto: string;
  hallazgo: string | null;
  fila: FilaPlan;
  /** Solo en un seguimiento: fecha fin de la acción en la visita anterior (para saber si hay prórroga). */
  finAnterior?: string | null;
  /** Solo en un seguimiento: hallazgo por verificar que aún no se recalifica (su fila igual se diligencia). */
  sinCalificar?: boolean;
}

export interface DatosPlan {
  fecha: string | null;
  hallazgos: HallazgoPlan[];
  estados: string[];
  ayudaIndicador: string;
}

/** Edición local de una fila del 5W2H (textos vacíos en lugar de null para los controles). */
export interface EdicionPlan {
  causas: string;
  que: string;
  porque: string;
  donde: string;
  inicio: string;
  fin: string;
  indicador: string;
  estado: string;
  /** Justificación de la prórroga (solo se pide y se envía cuando hay prórroga). */
  prorroga: string;
}

export const CAMPOS_PLAN = ['causas', 'que', 'porque', 'donde', 'inicio', 'fin', 'indicador', 'estado', 'prorroga'] as const;

/** Acciones abiertas: las que exigen la fecha fin para enviar el acta (como en el servidor). */
export const ESTADOS_ABIERTOS = ['Pendiente', 'En curso', 'Retrasado'];

/** Una acción del plan está cerrada solo en Completado o Cancelado (Seguimiento.ESTADOS_CERRADOS del servidor). */
export const ESTADOS_CERRADOS = ['Completado', 'Cancelado'];

/** Mínimo de la justificación de una prórroga, sin contar los espacios alrededor (Seguimiento.MIN_JUSTIFICACION). */
export const MIN_JUSTIFICACION = 10;

/** Edición local de un ítem (lo que aún no se ha guardado). */
export interface Edicion {
  calificacion: Calificacion | null;
  hallazgo: string;
  enTramite: boolean;
  /** «Requiere intervención inmediata del OPL» (fase 9); sin el campo, desmarcada. */
  inmediata?: boolean;
}

export interface RespuestaItem {
  item: string;
  calificacion: Calificacion | null;
  hallazgo: string | null;
  enTramite: boolean;
  /** Fase 9: solo verdadera en «No cumple» (el servidor rechaza la marca en otra calificación). */
  inmediata: boolean;
}

/** Texto de la casilla de intervención inmediata (fase 9, factor del puntaje de riesgo). */
export const TEXTO_INMEDIATA = 'Requiere intervención inmediata del OPL';

/** Lo que queda pendiente con la habilitación en trámite (decisión D5; el acta lo dice igual). */
export const TEXTO_TRAMITE = 'Queda pendiente aportar el distintivo de habilitación o las actas de visita del ente habilitador.';

export const ETIQUETA_CALIFICACION: Record<Calificacion, string> = { C: 'Cumple', NC: 'No cumple', NA: 'No aplica' };

export const ROLES_FIRMA: { rol: RolFirma; titulo: string }[] = [
  { rol: 'auditor', titulo: 'Auditor líder' },
  { rol: 'esm', titulo: 'Establecimiento' },
  { rol: 'opl', titulo: 'Operador logístico' },
];

/** Firmas del plan de mejoramiento, obligatorias para enviar un acta con hallazgos. */
export const ROLES_FIRMA_PLAN: { rol: RolFirma; titulo: string }[] = [
  { rol: 'w5_auditor', titulo: 'Auditor' },
  { rol: 'w5_auditado', titulo: 'Recibí los hallazgos' },
];

export const ENTIDADES = [
  { value: 'ESM', label: 'ESM' },
  { value: 'OPL', label: 'OPL' },
  { value: 'FA', label: 'FA' },
  { value: 'otro', label: 'Otro' },
];

/** Valor efectivo de un ítem: la edición local si la hay, si no lo guardado. */
export function valorItem(item: ItemChecklist, ediciones: Record<string, Edicion>): Edicion {
  return (
    ediciones[item.codigo] ?? {
      calificacion: item.calificacion,
      hallazgo: item.hallazgo ?? '',
      enTramite: item.enTramite ?? false,
      inmediata: item.inmediata ?? false,
    }
  );
}

/** «Habilitación en trámite» solo cuenta en un ítem que la admite y está en «No cumple». */
export function tramiteEfectivo(item: ItemChecklist, e: Edicion): boolean {
  return !!item.admiteTramite && e.calificacion === 'NC' && e.enTramite;
}

/** «Requiere intervención inmediata del OPL» solo cuenta en «No cumple» (ítem del checklist u obligación del contrato). */
export function inmediataEfectiva(e: Edicion): boolean {
  return e.calificacion === 'NC' && !!e.inmediata;
}

/** Cambio de calificación: fuera de «No cumple» se desmarcan la habilitación en trámite y la intervención inmediata. */
export function conCalificacion(e: Edicion, calificacion: Calificacion | null): Edicion {
  const nc = calificacion === 'NC';
  return { ...e, calificacion, enTramite: nc ? e.enTramite : false, inmediata: nc ? !!e.inmediata : false };
}

/** El hallazgo solo se conserva en un "No cumple"; vacío cuenta como sin hallazgo. */
export function normalizar(e: Edicion): RespuestaItem['hallazgo'] {
  const h = e.hallazgo.trim();
  return e.calificacion === 'NC' && h.length > 0 ? h : null;
}

/** Respuestas que cambiaron respecto de lo guardado, solo de ítems editables. */
export function respuestasCambiadas(componentes: ComponenteChecklist[], ediciones: Record<string, Edicion>): RespuestaItem[] {
  const salida: RespuestaItem[] = [];
  for (const c of componentes) {
    for (const it of c.items) {
      const e = ediciones[it.codigo];
      if (!e || !it.editable) continue;
      const hallazgo = normalizar(e);
      const guardadoHallazgo = it.calificacion === 'NC' && it.hallazgo && it.hallazgo.trim() ? it.hallazgo.trim() : null;
      const enTramite = tramiteEfectivo(it, e);
      const inmediata = inmediataEfectiva(e);
      if (
        e.calificacion !== it.calificacion ||
        hallazgo !== guardadoHallazgo ||
        enTramite !== (it.enTramite ?? false) ||
        inmediata !== (it.inmediata ?? false)
      ) {
        salida.push({ item: it.codigo, calificacion: e.calificacion, hallazgo, enTramite, inmediata });
      }
    }
  }
  return salida;
}

/** Ítems marcados "No cumple" sin hallazgo escrito (para avisar antes de guardar). */
export function sinHallazgo(respuestas: RespuestaItem[]): string[] {
  return respuestas.filter((r) => r.calificacion === 'NC' && !r.hallazgo).map((r) => r.item);
}

export interface Conteo {
  c: number;
  nc: number;
  na: number;
  pendientes: number;
}

/** Conteo de un componente con las ediciones locales aplicadas. */
export function conteo(items: ItemChecklist[], ediciones: Record<string, Edicion>): Conteo {
  const r: Conteo = { c: 0, nc: 0, na: 0, pendientes: 0 };
  for (const it of items) {
    const cal = valorItem(it, ediciones).calificacion;
    if (cal === 'C') r.c++;
    else if (cal === 'NC') r.nc++;
    else if (cal === 'NA') r.na++;
    else r.pendientes++;
  }
  return r;
}

/** Texto vacío a null para enviar al servidor. */
export function textoONulo(v: string): string | null {
  const t = v.trim();
  return t.length > 0 ? t : null;
}

/** Tamaño legible en KB o MB con 2 decimales (Anexo 5 num. 2.4): «2,00 KB», «5,25 MB». */
export function tamanoLegible(bytes: number): string {
  if (bytes < 1024 * 1024) return `${cifra(bytes / 1024)} KB`;
  return `${cifra(bytes / (1024 * 1024))} MB`;
}

/** "75.0" → "75,00 %"; "null" o vacío → null. */
export function cumplimientoLegible(v: string | null | undefined): string | null {
  if (!v || v === 'null' || aNumero(v) === null) return null;
  return porcentaje(v);
}

/** Valor efectivo de una fila del 5W2H: la edición local si la hay, si no lo guardado. */
export function valorFila(h: HallazgoPlan, ediciones: Record<string, EdicionPlan>): EdicionPlan {
  const f = h.fila;
  return (
    ediciones[h.clave] ?? {
      causas: f.causas ?? '',
      que: f.que ?? '',
      porque: f.porque ?? '',
      donde: f.donde ?? '',
      inicio: f.inicio ?? '',
      fin: f.fin ?? '',
      indicador: f.indicador ?? '',
      estado: f.estado || 'Pendiente',
      prorroga: f.prorroga_justificacion ?? '',
    }
  );
}

/** Fecha AAAA-MM-DD que existe en el calendario (2026-13-40 no lo es). */
export function fechaValida(v: string | null | undefined): boolean {
  if (!v || !/^\d{4}-\d{2}-\d{2}$/.test(v)) return false;
  const d = new Date(v + 'T00:00:00Z');
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === v;
}

/**
 * Prórroga en el seguimiento (Seguimiento.esProrroga del servidor): la acción sigue abierta (ni Completado ni Cancelado) y su fecha fin
 * válida pasa a ser posterior a la de la visita anterior. Adelantarla, fijarla donde no había o una fecha inválida no es prórroga.
 */
export function esProrroga(estado: string | null | undefined, fin: string | null | undefined, finAnterior: string | null | undefined): boolean {
  if (ESTADOS_CERRADOS.includes(estado || 'Pendiente')) return false;
  if (!fechaValida(fin) || !fechaValida(finAnterior)) return false;
  return fin! > finAnterior!; // AAAA-MM-DD se compara como texto
}

/** Justificación suficiente: al menos 10 caracteres sin los espacios alrededor; una letra con tilde cuenta como una. */
export function justificacionValida(texto: string | null | undefined): boolean {
  return Array.from((texto ?? '').trim()).length >= MIN_JUSTIFICACION;
}

/**
 * La fila pide la justificación de la prórroga, con las ediciones locales aplicadas: solo si el ítem sigue en «No cumple» (lo guardado;
 * una fila del seguimiento aún sin recalificar no la pide) y la fecha fin es una prórroga.
 */
export function pideProrroga(h: HallazgoPlan, ediciones: Record<string, EdicionPlan>): boolean {
  if (h.sinCalificar) return false;
  const v = valorFila(h, ediciones);
  return esProrroga(v.estado, v.fin, h.finAnterior);
}

/** Hay cambios del 5W2H sin guardar: la fecha o alguna fila distinta de lo guardado (comparando sin espacios sobrantes). */
export function planCambiado(plan: DatosPlan, fecha: string | null, ediciones: Record<string, EdicionPlan>): boolean {
  if (fecha !== null && fecha !== (plan.fecha ?? '')) return true;
  return plan.hallazgos.some((h) => {
    const e = ediciones[h.clave];
    if (!e) return false;
    const g = valorFila(h, {});
    return CAMPOS_PLAN.some((c) => e[c].trim() !== g[c].trim());
  });
}

/** Cuerpo del PUT del plan: la fecha efectiva y todas las filas (el servidor reemplaza la fecha con lo que llega). */
export function cuerpoPlan(plan: DatosPlan, fecha: string | null, ediciones: Record<string, EdicionPlan>) {
  const f = fecha ?? plan.fecha ?? '';
  return {
    fecha: f || null,
    filas: plan.hallazgos.map((h) => {
      const e = valorFila(h, ediciones);
      return {
        clave: h.clave,
        causas: textoONulo(e.causas),
        que: textoONulo(e.que),
        porque: textoONulo(e.porque),
        donde: textoONulo(e.donde),
        inicio: e.inicio || null,
        fin: e.fin || null,
        indicador: textoONulo(e.indicador),
        estado: e.estado || 'Pendiente',
        avance: h.fila.avance ?? 0,
        // La justificación solo va cuando hay prórroga: si la fecha vuelve atrás, no queda una justificación suelta
        prorroga: pideProrroga(h, ediciones) ? textoONulo(e.prorroga) : null,
      };
    }),
  };
}

/** Lo que falta del 5W2H para enviar el acta (mismas reglas que el servidor), con las ediciones locales aplicadas. */
export function faltantesPlan(plan: DatosPlan, fecha: string | null, ediciones: Record<string, EdicionPlan>): string[] {
  if (plan.hallazgos.length === 0) return [];
  const r: string[] = [];
  if (!(fecha ?? plan.fecha)) r.push('la fecha del plan');
  const sinQue = plan.hallazgos.filter((h) => !valorFila(h, ediciones).que.trim()).map((h) => h.codigo);
  const sinFin = plan.hallazgos
    .filter((h) => {
      const e = valorFila(h, ediciones);
      return ESTADOS_ABIERTOS.includes(e.estado) && !e.fin;
    })
    .map((h) => h.codigo);
  const sinJustificar = plan.hallazgos
    .filter((h) => pideProrroga(h, ediciones) && !justificacionValida(valorFila(h, ediciones).prorroga))
    .map((h) => h.codigo);
  if (sinQue.length > 0) r.push('el qué de ' + sinQue.join(', '));
  if (sinFin.length > 0) r.push('la fecha fin de ' + sinFin.join(', '));
  if (sinJustificar.length > 0) r.push(`la justificación de la prórroga (mínimo ${MIN_JUSTIFICACION} caracteres) de ` + sinJustificar.join(', '));
  return r;
}

/**
 * Lo mismo que faltantesPlan, por campo, para ponerlo junto a cada uno (Anexo 5 num. 2.4): «fecha» y «que:<clave>», «fin:<clave>» y
 * «prorroga:<clave>» por fila. Solo informa lo que falta para enviar el acta: el plan se guarda igual.
 */
export function faltantesPlanPorCampo(plan: DatosPlan, fecha: string | null, ediciones: Record<string, EdicionPlan>): Record<string, string> {
  if (plan.hallazgos.length === 0) return {};
  const r: Record<string, string> = {};
  if (!(fecha ?? plan.fecha)) r.fecha = 'Falta la fecha del plan para enviar el acta.';
  for (const h of plan.hallazgos) {
    const e = valorFila(h, ediciones);
    if (!e.que.trim()) r['que:' + h.clave] = 'Falta el qué (acción de mejora) para enviar el acta.';
    if (ESTADOS_ABIERTOS.includes(e.estado) && !e.fin) r['fin:' + h.clave] = 'Falta la fecha fin de la acción abierta para enviar el acta.';
    if (pideProrroga(h, ediciones) && !justificacionValida(e.prorroga)) {
      r['prorroga:' + h.clave] = `Justifique la prórroga (mínimo ${MIN_JUSTIFICACION} caracteres).`;
    }
  }
  return r;
}

/** Fecha fin anterior al inicio en una fila del 5W2H (el servidor no guarda el plan así): mensaje por «fin:<clave>». */
export function erroresFechasPlan(plan: DatosPlan, ediciones: Record<string, EdicionPlan>): Record<string, string> {
  const r: Record<string, string> = {};
  for (const h of plan.hallazgos) {
    const e = valorFila(h, ediciones);
    if (fechaValida(e.inicio) && fechaValida(e.fin) && e.fin < e.inicio) r['fin:' + h.clave] = 'La fecha fin no puede ser anterior al inicio.';
  }
  return r;
}
