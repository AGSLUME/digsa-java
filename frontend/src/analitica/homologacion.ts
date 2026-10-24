/**
 * Homologación de medicamentos (docs/especificaciones/analitica-homologacion): tipos de la API, estados con su color y texto, formato
 * de los porcentajes de calidad (A5 2.4: dos decimales con coma, sin separador de miles), consultas de la tabla maestra y validaciones
 * de la pantalla (fecha de corte, motivo de la devolución, parámetros de las reglas y entradas de los catálogos).
 */

export type EstadoHomologacion = 'homologado' | 'inconsistente' | 'duplicado' | 'sin_correspondencia' | 'pendiente' | 'no_valido';

/** Orden y texto de los 6 KPI del mockup (suman 100 %). */
export const ESTADOS: Array<{ codigo: EstadoHomologacion; texto: string; color: string }> = [
  { codigo: 'homologado', texto: 'Homologados', color: 'green' },
  { codigo: 'inconsistente', texto: 'Inconsistentes', color: 'orange' },
  { codigo: 'duplicado', texto: 'Duplicados', color: 'grape' },
  { codigo: 'sin_correspondencia', texto: 'Sin correspondencia', color: 'red' },
  { codigo: 'pendiente', texto: 'Pendientes de revisión', color: 'yellow' },
  { codigo: 'no_valido', texto: 'CUM o registro no válido', color: 'dark' },
];

export const NOMBRE_ESTADO: Record<EstadoHomologacion, string> = {
  homologado: 'Homologado',
  inconsistente: 'Inconsistente',
  duplicado: 'Duplicado',
  sin_correspondencia: 'Sin correspondencia',
  pendiente: 'Pendiente de revisión',
  no_valido: 'CUM o registro no válido',
};

export function colorEstado(e: string): string {
  return ESTADOS.find((x) => x.codigo === e)?.color ?? 'gray';
}

/** Duda AN.16: los tableros leen el corte del último cargue visible, así que una decisión de Revisión no los cambia al instante. */
export const AVISO_TABLEROS = 'Se verá en los tableros con el próximo cargue';

/** Notificación de éxito de la Revisión, con el aviso de los tableros. */
export function mensajeRevision(accion: 'aprobar' | 'devolver'): string {
  return `${accion === 'aprobar' ? 'Homologación aprobada' : 'Registro devuelto al OPL'}. ${AVISO_TABLEROS}.`;
}

export interface Version {
  id: number;
  numero: number;
  creada: string;
  motivo: 'cargue' | 'reejecucion' | 'aprobacion' | 'devolucion';
  cargue: number | null;
  responsable: string;
  origenes: number;
  conteo: Record<string, number>;
  cambios: number;
  duracionMs: number | null;
  detalle: string | null;
  fuentes: Record<string, { id: number; numero: number; fechaCorte: string; ejemplo: boolean }>;
}

export interface Resumen {
  vigente: Version | null;
  versiones: Version[];
  fuentesPublicadas: number;
  datosDeEjemplo: boolean;
  puedeOperar: boolean;
  sugeridor: { disponible: boolean; proveedor: string };
  /** La tabla maestra vigente quedó atrás frente a fuentes, reglas o catálogos publicados después (nulo si está al día). */
  desactualizada: { desdeVersion: number; cambios: string[]; motivo: string } | null;
}

export interface Kpi {
  estado: EstadoHomologacion;
  texto: string;
  registros: number;
  origenes: number;
  porcentaje: number;
}

export interface IndicadorCargue {
  cargue: number;
  aplicadoEn: string | null;
  fuente: string;
  recibidos: number;
  validos: number;
  rechazados: number;
  duplicados: number;
  consistencia: number;
  oportunidadHoras: number | null;
  oportunidadCumple: boolean | null;
}

export interface Calidad {
  version: Version | null;
  kpis: Kpi[];
  registros: number;
  origenes: number;
  cargues: IndicadorCargue[];
  cie10: Array<{ estado: string; codigos: number }>;
  oportunidadMaximaHoras: number;
  /** Registros cuyo dato original no existía en la versión elegida (anterior al cargue): fuera de los porcentajes. */
  registrosFueraDeVersion?: number;
}

/** Re-ejecución diferida de la homologación (GET /api/homologacion/ejecuciones/actual). */
export interface EstadoReejecucion {
  estado: 'ninguna' | 'en_curso' | 'terminada' | 'fallida';
  porcentaje: number;
  fase: string | null;
  inicio: string | null;
  fin: string | null;
  responsable: string | null;
  error: string | null;
  resultado: {
    version: number;
    numero: number;
    origenes: number;
    antes: Record<string, number>;
    despues: Record<string, number>;
    cambios: number;
    filas: number;
    duracionMs: number;
  } | null;
}

/** Estados del seguimiento de las devoluciones al OPL con su nombre y color (el texto acompaña siempre al color). */
export const ESTADOS_DEVOLUCION: Record<string, { texto: string; color: string }> = {
  devuelta: { texto: 'Devuelta', color: 'yellow' },
  persiste: { texto: 'Persiste', color: 'red' },
  corregida: { texto: 'Corregida por el OPL', color: 'green' },
  resuelta: { texto: 'Resuelta en revisión', color: 'blue' },
};

export function estadoDevolucion(e: string): { texto: string; color: string } {
  return ESTADOS_DEVOLUCION[e] ?? { texto: e, color: 'gray' };
}

/** El filtro «Cargue» solo acepta un número; con otro texto la pantalla avisa y no consulta. */
export function problemaCargue(texto: string): string | null {
  return texto.trim() === '' || /^\d+$/.test(texto.trim()) ? null : 'Escriba solo el número del cargue.';
}

/** Mensaje de un 409 por edición concurrente (la versión cambió): se muestra con la opción de recargar. */
export function esConflicto(e: unknown): boolean {
  return typeof e === 'object' && e !== null && 'estado' in e && (e as { estado: unknown }).estado === 409;
}

export interface Paso {
  regla: string;
  antes: string;
  despues: string;
}

export interface FilaMaestra {
  origenId: number;
  original: {
    cum: string | null;
    descripcion: string | null;
    descripcionNormalizada: string | null;
    principioActivo: string | null;
    concentracion: string | null;
    forma: string | null;
    presentacion: string | null;
    unidad: string | null;
    codigoSsfm: string | null;
    atc: string | null;
  };
  fuente: { codigo: string; nombre: string; version: number | null; fechaCorte: string | null; ejemplo: boolean | null } | null;
  regla: string;
  causa: string | null;
  reglasAplicadas: Paso[];
  final: {
    nombreComercial: string | null;
    dci: string | null;
    principioActivo: string | null;
    concentracion: string | null;
    forma: string | null;
    presentacion: string | null;
    unidad: string | null;
    cum: string | null;
    codigoSsfm: string | null;
    atc: string | null;
    grupoFarmacologico: string | null;
    registroSanitario: string | null;
  } | null;
  estado: EstadoHomologacion;
  estadoTexto: string;
  diferencias: string[];
  candidatos: string[];
  devuelto: boolean;
  accion: 'Ver' | 'Revisar';
}

export interface PaginaMaestra {
  version: Version | null;
  filas: FilaMaestra[];
  total: number;
  pagina: number;
  paginas: number;
}

export interface DetalleMaestra extends FilaMaestra {
  version: Version;
  cargues: Array<{ cargue: number; fuente: string; aplicadoEn: string | null; estado: string; registros: number; versionTablaMaestra: number }>;
  registros: number;
  /** Cargues aplicados que traen el dato (la lista muestra los 100 más recientes). */
  carguesTotal?: number;
  revisiones: Array<{ id: number; accion: string; cum: string | null; motivo: string | null; responsable: string; fecha: string; version: number }>;
}

export interface Comparacion {
  origenId: number;
  version: number;
  descripcion: string | null;
  estado: EstadoHomologacion;
  regla: string;
  causa: string | null;
  diferencias: string[];
  campos: Array<{ campo: string; opl: string | null; invima: string | null; distinto: boolean }>;
  candidatos: Array<{
    cum: string;
    conjunto: string;
    producto: string | null;
    forma: string | null;
    estadoCum: string | null;
    registro: string | null;
    atc: string | null;
    principios: string | null;
  }>;
  sugeridor: { disponible: boolean; proveedor: string };
  /** La tabla maestra vigente quedó atrás frente a fuentes, reglas o catálogos publicados después (nulo si está al día). */
  desactualizada: { desdeVersion: number; cambios: string[]; motivo: string } | null;
  /** «CUM o registro no válido»: solo se aprueba eligiendo un CUM vigente. */
  exigeCum?: boolean;
}

export interface VersionFuente {
  id: number;
  numero: number;
  estado: 'cargando' | 'validada' | 'publicada' | 'reemplazada' | 'rechazada' | 'fallida';
  fechaCorte: string;
  cargado: string;
  responsable: string;
  origen: 'archivo' | 'edicion';
  nombreArchivo: string | null;
  sha256: string;
  filasLeidas: number;
  filasValidas: number;
  ejemplo: boolean;
  informe: Record<string, unknown>;
  motivo: string | null;
  publicadaEn: string | null;
}

export interface Fuente {
  id: number;
  codigo: string;
  nombre: string;
  tipo: 'cum' | 'cie10' | 'precio' | 'vitales' | 'lista' | 'ssfm';
  dataset: string | null;
  url: string | null;
  licencia: string | null;
  atribucion: string | null;
  frecuencia: string | null;
  publicada: VersionFuente | null;
  versiones: VersionFuente[];
}

export interface Regla {
  id: string;
  nombre: string;
  orden: number;
  activa: boolean;
  obligatoria: boolean;
  parametros: Record<string, string>;
  version: number;
  actualizado: string;
  actualizadoPor: string | null;
  admite: string[];
}

export interface Historial {
  id: number;
  objeto: string;
  version: number;
  antes: string | null;
  despues: string;
  descripcion: string;
  responsable: string;
  fecha: string;
}

export interface Reglas {
  reglas: Regla[];
  catalogos: Array<{ tipo: string; nombre: string; version: number; entradas: number }>;
  historial: Historial[];
}

export interface EntradaCatalogo {
  clave: string;
  valor: string;
  extra: string | null;
}

export interface Evento {
  fecha: string;
  tipo: string;
  descripcion: string | null;
  usuario: string;
  perfil: string | null;
}

/** Porcentaje con dos decimales y coma («33,34 %»); sin dato, «—». */
export function porcentaje(v: number | null | undefined): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return '—';
  return `${v.toFixed(2).replace('.', ',')} %`;
}

/** Suma de los porcentajes (debe ser 100 con registros; la pantalla la muestra para que se vea que cuadra). */
export function sumaPorcentajes(kpis: Pick<Kpi, 'porcentaje'>[]): number {
  return Math.round(kpis.reduce((s, k) => s + Number(k.porcentaje), 0) * 100) / 100;
}

/** «Metformina · 850 mg · tableta · caja x 30» (el dato homologado como en el mockup). */
export function datoFinalTexto(f: FilaMaestra['final']): string {
  if (!f) return '—';
  return [f.dci ?? f.principioActivo, f.concentracion, f.forma, f.presentacion].filter((x) => x && x.trim() !== '').join(' · ') || '—';
}

/** «INVIMA_CUM_VIGENTES · v3 · corte 16/10/2026» o «Sin fuente». */
export function fuenteTexto(f: FilaMaestra['fuente']): string {
  if (!f) return 'Sin fuente';
  const corte = f.fechaCorte ? ` · corte ${fechaCorta(f.fechaCorte)}` : '';
  return `${f.nombre ?? f.codigo}${f.version ? ` · v${f.version}` : ''}${corte}`;
}

/** aaaa-mm-dd ⇒ dd/mm/aaaa. */
export function fechaCorta(iso: string | null | undefined): string {
  if (!iso) return '—';
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  return m ? `${m[3]}/${m[2]}/${m[1]}` : iso;
}

/** Consulta de la tabla maestra (y de su exportación) con los filtros de la pantalla. */
export function consultaTabla(f: { version?: number | null; q?: string; estado?: string | null; pagina?: number; formato?: string }): string {
  const p = new URLSearchParams();
  if (f.version) p.set('version', String(f.version));
  if (f.q && f.q.trim() !== '') p.set('q', f.q.trim());
  if (f.estado) p.set('estado', f.estado);
  if (f.pagina && f.pagina > 1) p.set('pagina', String(f.pagina));
  if (f.formato) p.set('formato', f.formato);
  const s = p.toString();
  return s ? `?${s}` : '';
}

/** Consulta del tablero de calidad (periodo en meses aaaa-mm). */
export function consultaCalidad(f: {
  version?: number | null;
  cargue?: string;
  fuente?: string | null;
  desde?: string;
  hasta?: string;
  formato?: string;
}): string {
  const p = new URLSearchParams();
  if (f.version) p.set('version', String(f.version));
  if (f.cargue && /^\d+$/.test(f.cargue.trim())) p.set('cargue', f.cargue.trim());
  if (f.fuente) p.set('fuente', f.fuente);
  if (f.desde) p.set('desde', f.desde);
  if (f.hasta) p.set('hasta', f.hasta);
  if (f.formato) p.set('formato', f.formato);
  const s = p.toString();
  return s ? `?${s}` : '';
}

/** Problemas de la carga de una fuente antes de enviarla (los mismos mensajes del servidor). */
export function problemasCarga(archivo: File | null, fechaCorte: string, hoy: string): string[] {
  const l: string[] = [];
  if (!fechaCorte) l.push('Indique la fecha de actualización de la fuente.');
  else if (fechaCorte > hoy) l.push('La fecha de actualización de la fuente no puede ser futura.');
  if (!archivo) l.push('Adjunte el archivo de la fuente.');
  else if (!/\.(csv|txt|xlsx|gz|zip)$/i.test(archivo.name)) l.push('Formato no aceptado: use CSV, TXT, xlsx, .gz o .zip.');
  return l;
}

export const MAX_MOTIVO = 500;

/** Motivo de «Devolver al OPL»: obligatorio y de máximo 500 caracteres. */
export function problemaMotivo(motivo: string): string | null {
  const m = motivo.trim();
  if (m === '') return 'Escriba el motivo de la devolución.';
  if (m.length > MAX_MOTIVO) return `El motivo no puede pasar de ${MAX_MOTIVO} caracteres.`;
  return null;
}

/** Rango admitido de cada parámetro de regla (el mismo del servidor). */
export const RANGO_PARAMETRO: Record<string, [number, number]> = { tolerancia: [0, 2], toleranciaPorcentaje: [0, 50] };

export const NOMBRE_PARAMETRO: Record<string, string> = {
  tolerancia: 'Tolerancia de forma (0 exacta, 1 misma familia, 2 no compara)',
  toleranciaPorcentaje: 'Tolerancia de concentración (%)',
};

/** Problema de un parámetro de regla, o null si es válido. */
export function problemaParametro(nombre: string, valor: string): string | null {
  const r = RANGO_PARAMETRO[nombre];
  if (!r) return `Parámetro desconocido: ${nombre}`;
  if (!/^\d+$/.test(valor.trim())) return `Debe ser un número entero entre ${r[0]} y ${r[1]}.`;
  const n = Number(valor.trim());
  if (n < r[0] || n > r[1]) return `Debe estar entre ${r[0]} y ${r[1]}.`;
  return null;
}

/**
 * Lee las entradas de un catálogo escritas una por línea como «CLAVE ⇒ VALOR» (o «CLAVE = VALOR»; la forma lleva «⇒ CANÓNICA / FAMILIA»;
 * la sal, solo la palabra). Devuelve las entradas y los errores con su línea.
 */
export function leerEntradas(tipo: string, texto: string): { entradas: EntradaCatalogo[]; errores: string[] } {
  const entradas: EntradaCatalogo[] = [];
  const errores: string[] = [];
  const vistas = new Set<string>();
  texto.split(/\r?\n/).forEach((linea, i) => {
    const t = linea.trim();
    if (t === '') return;
    let clave = t;
    let valor = t;
    let extra: string | null = null;
    if (tipo !== 'sal') {
      const partes = t.split(/\s*(?:⇒|=>|=)\s*/);
      if (partes.length !== 2 || partes[0] === '' || partes[1] === '') {
        errores.push(`Línea ${i + 1}: use «ENTRADA ⇒ VALOR»`);
        return;
      }
      clave = partes[0] ?? '';
      valor = partes[1] ?? '';
      if (tipo === 'forma' && valor.includes('/')) {
        const [v, f] = valor.split('/').map((x) => x.trim());
        valor = v ?? '';
        extra = f || null;
      }
    }
    const k = clave.toUpperCase();
    if (vistas.has(k)) {
      errores.push(`Línea ${i + 1}: «${clave}» está repetido`);
      return;
    }
    vistas.add(k);
    entradas.push({ clave, valor, extra });
  });
  if (entradas.length === 0 && errores.length === 0) errores.push('El catálogo debe tener al menos una entrada.');
  return { entradas, errores };
}

/** Las entradas en el formato de edición de {@link leerEntradas}. */
export function escribirEntradas(tipo: string, entradas: EntradaCatalogo[]): string {
  return entradas.map((e) => (tipo === 'sal' ? e.clave : `${e.clave} ⇒ ${e.valor}${tipo === 'forma' && e.extra ? ` / ${e.extra}` : ''}`)).join('\n');
}

/** Nombre legible del motivo de una versión de la tabla maestra. */
export function motivoVersion(m: Version['motivo']): string {
  return { cargue: 'Cargue', reejecucion: 'Re-ejecución', aprobacion: 'Aprobación', devolucion: 'Devolución' }[m] ?? m;
}
