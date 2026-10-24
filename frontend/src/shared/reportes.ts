/** Tipos y reglas de la pantalla de reportes (Anexo 5, 2.8 a 2.12): catálogo, parámetros de la consulta y presentación de las celdas. */

export interface FiltroDef {
  clave: string;
  etiqueta: string;
  tipo: 'lista' | 'texto';
  establecimiento: boolean;
  /** Provisional (duda RP-7): el reporte admite por ahora un valor a la vez. */
  unSoloValor?: boolean;
}

export interface DefReporte {
  id: string;
  titulo: string;
  descripcion: string;
  clase: 'general' | 'operativo';
  modulo: string;
  periodo: 'ninguno' | 'fecha' | 'fecha_hora';
  maxDias: number;
  agrupaciones: { clave: string; nombre: string }[];
  filtros: FiltroDef[];
  columnas: string[];
}

export interface GrupoCatalogo {
  clave: string;
  nombre: string;
  reportes: DefReporte[];
}

export interface Preset {
  clave: string;
  nombre: string;
  desde: string;
  hasta: string;
}

export interface Catalogo {
  grupos: GrupoCatalogo[];
  formatos: string[];
  presets: Preset[];
  /** El Administrador ve los tiempos de generación (2.7). */
  verTiempos?: boolean;
}

/** Tiempos de generación por reporte y por pantalla o formato (2.7). */
export interface Tiempos {
  dias: number;
  plazoVerMs: number;
  plazoExportarMs: number;
  tiempos: {
    reporte: string;
    titulo: string;
    tipo: string;
    generaciones: number;
    p50: number;
    p95: number;
    maximo: number;
    lentas: number;
    fallidas: number;
  }[];
}

/** Milisegundos legibles: «850 ms», «12,4 s», «3 min 5 s». */
export function segundos(ms: number): string {
  if (ms < 1000) return `${ms} ms`;
  if (ms < 59_950) return `${(ms / 1000).toFixed(1).replace('.', ',')} s`;
  const total = Math.round(ms / 1000);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return s === 0 ? `${m} min` : `${m} min ${s} s`;
}

export interface Opcion {
  valor: string;
  etiqueta: string;
}

export type Celda = string | number | null;

export interface Resultado {
  id: string;
  titulo: string;
  /** Código de verificación (2.10). */
  codigo: string;
  /** Huella de los datos; null si en pantalla no caben todas las filas. */
  huella: string | null;
  generado: string;
  usuario: string;
  periodo: string;
  agrupacion: string | null;
  establecimiento: string;
  filtros: Record<string, string>;
  fuente: string;
  clase: string;
  registros: number;
  truncado: boolean;
  ms: number;
  columnas: string[];
  filas: Celda[][];
}

/** Respuesta de «Verificar reporte». */
export interface ResultadoVerificacion {
  resultado: 'integro' | 'alterado' | 'sin_cambios' | 'cambiaron' | 'fallida' | 'en_curso';
  mensaje: string;
  /** Huella del archivo subido (verificación por archivo). */
  huellaArchivo?: string;
  /** Huella de los datos de hoy (regeneración). */
  huellaHoy?: string;
  filasHoy?: number;
  reporte?: {
    codigo: string;
    titulo: string;
    generado: string;
    usuario: string;
    parametros: { desde?: string; hasta?: string; agrupacion?: string; filtros?: Record<string, string[]> };
    /** Quien no generó el reporte no ve los valores de los filtros (Ley 1581). */
    filtrosOcultos?: boolean;
    formato: string;
    filas: number | null;
    huellaDatos: string | null;
    huellaArchivo: string | null;
    estado: string;
  };
}

/** Íntegro en verde, alterado o fallida en rojo; datos cambiados o generación en curso, en amarillo. */
export function colorVerificacion(r: ResultadoVerificacion['resultado']): string {
  if (r === 'alterado' || r === 'fallida') return 'red';
  if (r === 'cambiaron' || r === 'en_curso') return 'yellow';
  return 'green';
}

/** Lo que se muestra del registro verificado, en orden, y las huellas calculadas en la verificación. */
export function filasVerificacion(r: ResultadoVerificacion): [string, string][] {
  const g = r.reporte;
  const calculadas: [string, string][] = [];
  if (r.huellaArchivo && (!g || g.huellaArchivo !== r.huellaArchivo)) calculadas.push(['Huella del archivo subido (SHA-256)', r.huellaArchivo]);
  if (r.huellaHoy) calculadas.push(['Huella de los datos hoy (SHA-256)', r.huellaHoy]);
  if (!g) return calculadas;
  const p = g.parametros;
  const filtros = g.filtrosOcultos
    ? 'Solo los ve quien generó el reporte'
    : Object.entries(p.filtros ?? {})
        .map(([k, v]) => `${k}: ${v.join(', ')}`)
        .join(' · ') || 'Ninguno';
  return [
    ['Código de verificación', g.codigo],
    ['Reporte', g.titulo],
    ['Generado', g.generado],
    ['Usuario', g.usuario],
    ['Periodo', p.desde ? `${p.desde} a ${p.hasta}${p.agrupacion ? ` · agrupado por ${p.agrupacion}` : ''}` : 'Sin periodo'],
    ['Filtros', filtros],
    ['Formato', g.formato === 'pantalla' ? 'En pantalla' : g.formato.toUpperCase()],
    ['Estado', ESTADOS[g.estado] ?? g.estado],
    ['Registros', g.filas === null ? '' : String(g.filas)],
    ['Huella de los datos (SHA-256)', g.huellaDatos ?? 'Sin huella (en pantalla no cabían todas las filas)'],
    ['Huella del archivo (SHA-256)', g.huellaArchivo ?? '—'],
    ...calculadas,
  ];
}

const ESTADOS: Record<string, string> = {
  vista: 'Visto en pantalla',
  pendiente: 'En cola',
  generando: 'Generando',
  lista: 'Listo',
  fallida: 'Falló',
  vencida: 'Vencido (el archivo ya se borró del repositorio)',
};

/** ¿Se puede regenerar? Solo si se terminó de generar y tiene huella de los datos. */
export function sePuedeRegenerar(r: ResultadoVerificacion | undefined): boolean {
  const g = r?.reporte;
  return !g || (g.huellaDatos !== null && !['fallida', 'pendiente', 'generando'].includes(g.estado));
}

/** Lo que el usuario eligió: el periodo, la agrupación y los valores de cada filtro (vacío = todas las opciones). */
export interface Eleccion {
  desde: string;
  hasta: string;
  agrupacion: string;
  /** Periodo predefinido elegido; se borra al escribir las fechas a mano (no se envía: van las fechas resueltas). */
  preset?: string;
  valores: Record<string, string[]>;
}

export const FORMATOS: { valor: string; etiqueta: string }[] = [
  { valor: 'xlsx', etiqueta: 'Excel' },
  { valor: 'csv', etiqueta: 'CSV' },
  { valor: 'pdf', etiqueta: 'PDF' },
  { valor: 'txt', etiqueta: 'TXT' },
];

export const FILAS_POR_PAGINA = 100;

/**
 * Parámetros de la consulta: solo el periodo si el reporte lo tiene, la agrupación si la admite y los filtros que declara, con un
 * parámetro repetido por cada opción elegida (2.11).
 */
export function parametros(def: DefReporte, e: Eleccion): URLSearchParams {
  const p = new URLSearchParams();
  if (def.periodo !== 'ninguno') {
    if (e.desde) p.set('desde', e.desde);
    if (e.hasta) p.set('hasta', e.hasta);
    if (e.agrupacion && def.agrupaciones.some((a) => a.clave === e.agrupacion)) p.set('agrupacion', e.agrupacion);
  }
  for (const f of def.filtros) {
    const valores = (e.valores[f.clave] ?? []).map((v) => v.trim()).filter((v) => v !== '');
    if (f.tipo === 'texto') {
      if (valores[0]) p.set(f.clave, valores[0]);
    } else {
      for (const v of valores) p.append(f.clave, v);
    }
  }
  return p;
}

/** Cuerpo del envío por correo con los mismos parámetros de la consulta (los filtros como listas). */
export function cuerpoEnvio(def: DefReporte, e: Eleccion, para: string, formato: string): Record<string, unknown> {
  const p = parametros(def, e);
  const cuerpo: Record<string, unknown> = { para, formato };
  const filtros: Record<string, string[]> = {};
  p.forEach((v, k) => {
    if (k === 'desde' || k === 'hasta' || k === 'agrupacion') cuerpo[k] = v;
    else filtros[k] = [...(filtros[k] ?? []), v];
  });
  if (Object.keys(filtros).length > 0) cuerpo.filtros = filtros;
  return cuerpo;
}

function sinTildes(s: string): string {
  return s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
}

/** Buscador del catálogo: por título o descripción, sin tildes ni mayúsculas; los grupos que quedan vacíos no se muestran. */
export function filtrarCatalogo(grupos: GrupoCatalogo[], texto: string): GrupoCatalogo[] {
  const t = sinTildes(texto.trim());
  if (!t) return grupos;
  return grupos
    .map((g) => ({ ...g, reportes: g.reportes.filter((r) => sinTildes(`${r.titulo} ${r.descripcion} ${g.nombre}`).includes(t)) }))
    .filter((g) => g.reportes.length > 0);
}

/** Al cambiar de reporte se conservan los valores de los filtros que el nuevo también tiene. */
export function conservarValores(def: DefReporte, valores: Record<string, string[]>): Record<string, string[]> {
  const r: Record<string, string[]> = {};
  for (const f of def.filtros) {
    const v = valores[f.clave];
    // Un filtro de un valor (provisional, RP-7) no hereda varios del reporte anterior
    if (v?.length) r[f.clave] = f.unSoloValor ? v.slice(0, 1) : v;
  }
  return r;
}

/** Celda en pantalla: números con coma decimal y sin separador de miles, como en los archivos. */
export function celda(v: Celda): string {
  if (v === null || v === undefined) return '';
  if (typeof v === 'number') return String(v).replace('.', ',');
  return v;
}

export function totalPaginas(filas: number, porPagina = FILAS_POR_PAGINA): number {
  return Math.max(1, Math.ceil(filas / porPagina));
}

/** Una fila de «Descargas del día» (Anexo 5, 2.7). */
export interface Descarga {
  codigo: string;
  titulo: string;
  formato: string;
  estado: 'pendiente' | 'generando' | 'lista' | 'fallida';
  solicitado: string;
  filas: number | null;
  tamano: number | null;
  ms: number | null;
  disponibleHasta: string;
  error: string | null;
  nombre: string;
}

/** ¿Hay alguna descarga en cola o generándose? Mientras tanto la pantalla consulta cada pocos segundos. */
export function hayEnCurso(d: Descarga[]): boolean {
  return d.some((x) => x.estado === 'pendiente' || x.estado === 'generando');
}

/**
 * Las descargas que terminaron (lista o fallida) desde la consulta anterior, para avisar. {@code seguidas} son los códigos de los 202
 * de esta sesión: se avisan aunque hayan terminado antes de que la lista los viera en curso.
 */
export function recienTerminadas(antes: Descarga[], ahora: Descarga[], seguidas: ReadonlySet<string> = new Set()): Descarga[] {
  const enCurso = new Set(antes.filter((x) => x.estado === 'pendiente' || x.estado === 'generando').map((x) => x.codigo));
  return ahora.filter((x) => (x.estado === 'lista' || x.estado === 'fallida') && (enCurso.has(x.codigo) || seguidas.has(x.codigo)));
}

/** Tamaño legible: «1,5 MB», «820 KB». */
export function tamano(bytes: number | null): string {
  if (bytes === null || bytes === undefined) return '';
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1).replace('.', ',')} MB`;
}

export const ESTADO_DESCARGA: Record<Descarga['estado'], { texto: string; color: string }> = {
  pendiente: { texto: 'En cola', color: 'gray' },
  generando: { texto: 'Generando', color: 'blue' },
  lista: { texto: 'Listo', color: 'green' },
  fallida: { texto: 'Falló', color: 'red' },
};

/** Texto y color del estado; un estado que el servidor agregue después se muestra tal cual, sin romper la tabla. */
export function estadoDescarga(estado: string): { texto: string; color: string } {
  return Object.prototype.hasOwnProperty.call(ESTADO_DESCARGA, estado)
    ? ESTADO_DESCARGA[estado as Descarga['estado']]
    : { texto: estado, color: 'gray' };
}
