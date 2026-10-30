/**
 * Cargues del insumo del OPL (docs/especificaciones/analitica-ingesta): tipos de la API, partes de la subida, estados, mapeo de
 * columnas y formato de la bitácora (A5 2.4: cifras, fechas y horas con el formato único de shared/formato).
 */
import { cifra, fecha, fechaHora } from '../shared/formato';

export type EstadoCargue =
  'subiendo' | 'mapeo' | 'pendiente' | 'procesando' | 'aplicado' | 'rechazado' | 'fallido' | 'duplicado' | 'vencido' | 'descartado';
export type Fuente = 'archivo' | 'ws_opl';

export interface Motivo {
  causa: string;
  texto: string;
  cantidad: number;
}

export interface Cargue {
  id: number;
  estado: EstadoCargue;
  etapa: string | null;
  porcentaje: number;
  filasLeidas: number;
  fuente: Fuente;
  fuenteTexto: string;
  nombreArchivo: string | null;
  formato: string;
  tamano: number | null;
  sha256: string | null;
  modo: 'agregar' | 'reemplazar';
  creado: string | null;
  recibido: string | null;
  iniciado: string | null;
  aplicadoEn: string | null;
  terminado: string | null;
  periodoDesde: string | null;
  periodoHasta: string | null;
  recibidos: number | null;
  procesados: number | null;
  rechazados: number | null;
  esmSinCorrespondencia: number | null;
  motivos: Motivo[];
  motivo: string | null;
  avisos: string[];
  etapasMs: Record<string, number>;
  duracionMs: number | null;
  responsable: string;
  error: string | null;
  reemplazadoPor: number | null;
  duplicadoDe: number | null;
  propio: boolean;
  originalDisponible: boolean;
  conservarHasta: string | null;
  posicion: number | null;
  existente?: boolean;
  mensaje?: string;
}

export interface Bitacora {
  filas: Cargue[];
  total: number;
  pagina: number;
  tamanoPagina: number;
  puedeCargar: boolean;
  esAdministrador: boolean;
  /** A7.2: puede pedir la exportación de hechos (misma regla que el servidor, también con un rol de base Administrador). */
  exportaHechos?: boolean;
  /** Falta la clave del seudónimo: los cargues se rechazan hasta configurarla. */
  avisoConfiguracion: string | null;
  horasAbandono: number;
  parteMaxBytes: number;
  archivoMaxBytes: number;
}

export interface CampoModelo {
  clave: string;
  etiqueta: string;
  /** Nombre del campo en los mensajes del mapeo (el mismo del servidor). */
  enMensaje?: string;
  tipo: 'obligatorio' | 'opcional' | 'descarte';
  /** Se seudonimiza, se reduce al año o se descarta (paciente, profesional, fecha de nacimiento, descartar). */
  personal?: boolean;
}

export interface Mapeo {
  id: number;
  nombreArchivo: string;
  encabezados: string[];
  encabezadoReconocido: boolean;
  /** Por columna: ¿su encabezado es de un dato personal? */
  personales: boolean[];
  propuesta: string[];
  plantilla: { id: number; nombre: string } | null;
  campos: CampoModelo[];
  problemas: string[];
  avisos: string[];
  vistaPrevia: string[][];
  avisoVistaPrevia: string | null;
}

export interface VistaPrevia {
  vistaPrevia: string[][];
  avisoVistaPrevia: string | null;
}

/** Lo que ocultaría «Reemplazar el periodo» con el periodo declarado. */
export interface Reemplazo {
  desde: string;
  hasta: string;
  cargues: number[];
  registros: number;
}

/** Tamaño de cada parte de la subida si el servidor no lo informa (digsa.analitica.parte-max-mb, 50 MB por defecto). */
export const PARTE_BYTES = 50 * 1024 * 1024;
export const INTERVALO_SONDEO_MS = 2000;

export const NOMBRE_ESTADO: Record<EstadoCargue, string> = {
  subiendo: 'Subiendo',
  mapeo: 'Mapeo pendiente',
  pendiente: 'En cola',
  procesando: 'Procesando',
  aplicado: 'Aplicado',
  rechazado: 'Rechazado',
  fallido: 'Fallido',
  duplicado: 'Duplicado',
  vencido: 'Vencido',
  descartado: 'Descartado',
};

export const COLOR_ESTADO: Record<EstadoCargue, string> = {
  subiendo: 'gray',
  mapeo: 'yellow',
  pendiente: 'gray',
  procesando: 'blue',
  aplicado: 'green',
  rechazado: 'red',
  fallido: 'red',
  duplicado: 'orange',
  vencido: 'gray',
  descartado: 'gray',
};

export const NOMBRE_ETAPA: Record<string, string> = {
  subida: 'Subida',
  lectura: 'Lectura y copia',
  validacion: 'Validación',
  homologacion: 'Homologación',
  hechos: 'Carga de hechos',
  agregados: 'Agregados',
};

/** Partes de un archivo: [desde, hasta) de cada una, de máximo {@link PARTE_BYTES}. Un archivo vacío no tiene partes. */
export function partes(tamano: number, parte = PARTE_BYTES): Array<[number, number]> {
  const r: Array<[number, number]> = [];
  for (let i = 0; i < tamano; i += parte) r.push([i, Math.min(tamano, i + parte)]);
  return r;
}

/** Formatos que acepta la ingesta (por la extensión del nombre). */
export function formatoAceptado(nombre: string): boolean {
  return /\.(csv|txt|xlsx|gz|zip)$/i.test(nombre.trim());
}

export function enCurso(c: Pick<Cargue, 'estado'>): boolean {
  return c.estado === 'pendiente' || c.estado === 'procesando' || c.estado === 'subiendo';
}

const TERMINADOS: EstadoCargue[] = ['aplicado', 'rechazado', 'fallido', 'duplicado', 'vencido', 'descartado'];

export function terminado(c: Pick<Cargue, 'estado'>): boolean {
  return TERMINADOS.includes(c.estado);
}

/** Cargue abierto (subida o mapeo): se puede retomar o descartar; si nadie lo toca, vence. */
export function abierto(c: Pick<Cargue, 'estado'>): boolean {
  return c.estado === 'subiendo' || c.estado === 'mapeo';
}

/** Sondeo de la bitácora: cada 2 s mientras algún cargue esté en cola o en proceso. */
export function intervaloSondeo(filas: Pick<Cargue, 'estado'>[] | undefined): number | false {
  return (filas ?? []).some((c) => c.estado === 'pendiente' || c.estado === 'procesando') ? INTERVALO_SONDEO_MS : false;
}

/** Segundos con dos decimales y coma: 12345 ms ⇒ «12,35 s» (cifra de shared/formato con la unidad). */
export function segundos(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || !Number.isFinite(ms)) return '—';
  return `${cifra(ms / 1000)} s`;
}

/** Periodo procesado: «01/01/2025 – 31/12/2025». */
export function periodo(c: Pick<Cargue, 'periodoDesde' | 'periodoHasta'>): string {
  return c.periodoDesde ? `${fecha(c.periodoDesde)} – ${fecha(c.periodoHasta)}` : '—';
}

/** Fecha y hora que muestra la bitácora: la de aplicación o, si no, la de recepción o creación. */
export function fechaBitacora(c: Pick<Cargue, 'aplicadoEn' | 'terminado' | 'recibido' | 'creado'>): string {
  return fechaHora(c.aplicadoEn ?? c.terminado ?? c.recibido ?? c.creado);
}

/** Motivo agrupado: «CUM con formato inválido (241), Fecha inválida (71)». */
export function motivoTexto(c: Pick<Cargue, 'motivos' | 'motivo' | 'estado'>): string {
  if (c.estado === 'rechazado' && c.motivo) return c.motivo;
  if (c.motivos.length === 0) return c.motivo ?? '';
  return c.motivos.map((m) => `${m.texto} (${m.cantidad})`).join(', ');
}

/** Campos obligatorios: el insumo debe traerlos todos (medicamento o CUM: basta uno). */
export const OBLIGATORIOS = ['paciente', 'fuerza', 'esm', 'modalidad', 'fecha', 'cantidad', 'valor'];
const NOMBRE_OBLIGATORIO: Record<string, string> = {
  paciente: 'identificador del paciente',
  fuerza: 'Fuerza',
  esm: 'ESM',
  modalidad: 'modalidad',
  fecha: 'fecha de dispensación',
  cantidad: 'cantidad',
  valor: 'valor',
};

/** Problemas del mapeo (los mismos mensajes del servidor): repetidos y obligatorios faltantes. Vacío = se puede confirmar. */
export function problemasMapeo(columnas: string[], campos: CampoModelo[]): string[] {
  const p: string[] = [];
  const vistos = new Set<string>();
  const descarte = new Set(campos.filter((c) => c.tipo === 'descarte').map((c) => c.clave));
  for (const c of columnas) {
    if (!c || descarte.has(c)) continue;
    if (vistos.has(c)) {
      const campo = campos.find((x) => x.clave === c);
      p.push(`La columna de ${campo?.enMensaje ?? campo?.etiqueta.toLowerCase() ?? c} está asignada dos veces`);
    }
    vistos.add(c);
  }
  for (const o of OBLIGATORIOS) if (!vistos.has(o)) p.push(`Falta asignar la columna de ${NOMBRE_OBLIGATORIO[o]}`);
  if (!vistos.has('medicamento') && !vistos.has('cum')) p.push('Falta asignar la columna de medicamento o CUM');
  return p;
}

/**
 * Columnas de datos personales asignadas a un campo que se guarda en claro (el mismo mensaje del servidor, que también lo rechaza):
 * solo pueden ir a «Se descarta», «No se usa» o a un campo que se seudonimiza.
 */
export function problemasPrivacidad(encabezados: string[], personales: boolean[], columnas: string[], campos: CampoModelo[]): string[] {
  const permitidos = new Set(campos.filter((c) => c.personal).map((c) => c.clave));
  permitidos.add('ignorar');
  return encabezados.flatMap((e, i) =>
    personales[i] && columnas[i] && !permitidos.has(columnas[i]!)
      ? [`La columna «${e}» tiene datos personales: asígnela a «Se descarta (dato personal)», «No se usa» o a un campo que se seudonimiza`]
      : [],
  );
}

/** Filtros de la bitácora como parámetros de consulta (sin los vacíos). */
export function consulta(f: {
  fuente?: string | null;
  estado?: string | null;
  desde?: string | null;
  hasta?: string | null;
  pagina?: number;
}): string {
  const p = new URLSearchParams();
  if (f.fuente) p.set('fuente', f.fuente);
  if (f.estado) p.set('estado', f.estado);
  if (f.desde) p.set('desde', f.desde);
  if (f.hasta) p.set('hasta', f.hasta);
  if (f.pagina && f.pagina > 1) p.set('pagina', String(f.pagina));
  const s = p.toString();
  return s ? `?${s}` : '';
}

export function rutaRechazos(id: number): string {
  return `/api/analitica/cargues/${id}/rechazos.csv`;
}

export function rutaOriginal(id: number): string {
  return `/api/analitica/cargues/${id}/original`;
}

/** Vista previa enmascarada con el mapeo de la pantalla. */
export function rutaVistaPrevia(id: number, columnas: string[]): string {
  return `/api/analitica/cargues/${id}/vista-previa?columnas=${encodeURIComponent(columnas.join(','))}`;
}

export function rutaReemplazo(id: number, desde: string, hasta: string): string {
  return `/api/analitica/cargues/${id}/reemplazo?desde=${desde}&hasta=${hasta}`;
}

/** Periodo declarado completo y en orden (aaaa-mm-dd de los campos de fecha). */
export function periodoValido(desde: string, hasta: string): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(desde) && /^\d{4}-\d{2}-\d{2}$/.test(hasta) && desde <= hasta;
}
