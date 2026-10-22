/**
 * Presupuesto del contrato de medicamentos (docs/especificaciones/presupuesto): tipos de la API y reglas del formulario. El servidor
 * valida todo de nuevo; aquí solo se evita enviar lo que de seguro se rechaza.
 */
export interface Vigencia {
  id: number;
  anio: number;
  inicio: string;
  fin: string;
  estado: 'abierta' | 'cerrada';
}

export interface Modalidad {
  codigo: string;
  nombre: string;
  activa: boolean;
  orden: number;
}

export interface PuntoEsm {
  id: number;
  sigla: string | null;
  nombre: string;
  fuerza: string;
  modalidad: string;
  centralizadorId: number | null;
}

export interface Parametro {
  clave: string;
  descripcion: string;
  valor: string;
}

export interface OpcionesPresupuesto {
  vigencias: Vigencia[];
  modalidades: Modalidad[];
  fuerzas: { codigo: string; nombre: string }[];
  puntos: PuntoEsm[];
  ambito: string;
  puedeRegistrar: boolean;
  parametros: Parametro[];
  base: string;
  tipos: { codigo: string; nombre: string }[];
}

export interface LineaPresupuesto {
  id: number;
  vigencia: number;
  fuerza: string;
  puntoId: number;
  sigla: string | null;
  nombre: string;
  modalidad: string;
  modalidadNombre: string;
  asignado: string;
  facturado: string;
  reconocido: string;
  saldo: string;
  porcentaje: string | null;
  semaforo: 'verde' | 'ambar' | 'rojo';
}

export interface LineaMovimiento {
  lineaId: number;
  fuerza: string;
  esm: string;
  modalidad: string;
  valor: string;
  antes: string;
  despues: string;
  ejecutado: string;
}

export interface Movimiento {
  id: number;
  tipo: string;
  tipoNombre: string;
  vigencia: number;
  soporte: string;
  soporteId: number | null;
  loteId: number | null;
  porcentaje: string | null;
  observacion: string | null;
  neto: string;
  fecha: string;
  usuario: string;
  lineas: LineaMovimiento[];
}

export interface PaginaMovimientos {
  total: number;
  pagina: number;
  porPagina: number;
  items: Movimiento[];
}

export interface ErrorFila {
  fila: number;
  motivo: string;
}

export interface ResultadoCarga {
  mensaje: string;
  yaAplicado?: boolean;
  validas?: number;
  errores?: ErrorFila[];
  totalesPorFuerza?: Record<string, string>;
  totalNacional?: string;
  filas?: { fila: number; linea: string; fuerza: string; valor: string }[];
}

export type TipoNovedad = 'asignacion_inicial' | 'adicion' | 'disminucion' | 'reajuste' | 'rebalanceo' | 'recomposicion';

/** Una fila del formulario: ESM, modalidad, fuerza (solo en HOMIL) y el valor como se escribe («450.000.000,00»). */
export interface FilaNovedad {
  puntoId: string;
  modalidad: string;
  fuerza: string;
  valor: string;
}

export interface FormNovedad {
  vigenciaId: string;
  tipo: TipoNovedad;
  soporte: string;
  observacion: string;
  porPorcentaje: boolean;
  porcentaje: string;
  ambitoFuerza: string;
  ambitoModalidad: string;
  filas: FilaNovedad[];
}

export const FILA_VACIA: FilaNovedad = { puntoId: '', modalidad: '', fuerza: '', valor: '' };

export function formularioVacio(tipo: TipoNovedad = 'adicion', vigenciaId = ''): FormNovedad {
  return {
    vigenciaId,
    tipo,
    soporte: '',
    observacion: '',
    porPorcentaje: false,
    porcentaje: '',
    ambitoFuerza: '',
    ambitoModalidad: '',
    filas: tipo === 'rebalanceo' ? [{ ...FILA_VACIA }, { ...FILA_VACIA }] : [{ ...FILA_VACIA }],
  };
}

/** Qué pide cada tipo (Anexo 5 §4.6). */
export const AYUDA_TIPO: Record<TipoNovedad, string> = {
  asignacion_inicial: 'Presupuesto inicial de líneas que aún no tienen. Para cargar muchas, use la pestaña Cargas.',
  adicion: 'Suma a una o varias líneas. Cada valor mayor que 0.',
  disminucion: 'Resta a una o varias líneas. No puede dejar una línea negativa ni por debajo de lo ejecutado.',
  reajuste: 'Ajuste con signo por línea (+ o −), o un porcentaje sobre una fuerza o todo el país.',
  rebalanceo: 'Traslado de una línea de origen a una de destino, por el mismo valor. El total no cambia.',
  recomposicion: 'Redistribución de varias líneas con valores con signo que deben sumar exactamente 0,00.',
};

/**
 * Lee un monto como lo hace el servidor (Presupuesto.monto): «1.250.000,5», «1250000.50», «$ 1 250 000,50». Devuelve el texto con punto
 * decimal y 2 decimales para enviar («1250000.50»), o null si no es un monto válido. Conserva el signo.
 */
export function leerMonto(texto: string): string | null {
  let t = (texto ?? '').replace(/[$\s' ]/g, '');
  let negativo = false;
  if (t.startsWith('-') || t.startsWith('+')) {
    negativo = t.startsWith('-');
    t = t.substring(1);
  }
  if (!t) return null;
  let entero: string;
  let decimales = '';
  const punto = t.lastIndexOf('.');
  const coma = t.lastIndexOf(',');
  if (punto >= 0 && coma >= 0) {
    const dec = punto > coma ? '.' : ',';
    const miles = dec === '.' ? ',' : '.';
    const i = t.lastIndexOf(dec);
    entero = t.substring(0, i);
    const grupos = new RegExp('^\\d{1,3}(' + (miles === '.' ? '\\.' : ',') + '\\d{3})+$');
    if (!/^\d+$/.test(entero) && !grupos.test(entero)) return null;
    entero = entero.split(miles).join('');
    decimales = t.substring(i + 1);
  } else if (coma >= 0) {
    if (t.indexOf(',') !== coma && /^\d{1,3}(,\d{3})+$/.test(t)) {
      entero = t.split(',').join('');
    } else {
      entero = t.substring(0, coma);
      decimales = t.substring(coma + 1);
    }
  } else if (punto >= 0) {
    if (/^\d{1,3}(\.\d{3})+$/.test(t)) {
      entero = t.split('.').join('');
    } else {
      entero = t.substring(0, punto);
      decimales = t.substring(punto + 1);
    }
  } else {
    entero = t;
  }
  if (!entero) entero = '0';
  if (!/^\d+$/.test(entero) || (decimales && !/^\d+$/.test(decimales)) || decimales.length > 2 || entero.length > 16) return null;
  const normal = entero.replace(/^0+(?=\d)/, '') + '.' + (decimales + '00').substring(0, 2);
  return (negativo && /[1-9]/.test(normal) ? '-' : '') + normal;
}

/** Monto para mostrar como en el resto del sistema (VCO P27): coma decimal y sin separador de miles. */
export function mostrarMonto(enviado: string | null): string {
  return enviado === null ? '' : enviado.replace('.', ',');
}

function centavos(m: string): bigint {
  const negativo = m.startsWith('-');
  const [e, d] = m.replace('-', '').split('.');
  const v = BigInt(e ?? '0') * 100n + BigInt(d ?? '0');
  return negativo ? -v : v;
}

/** Suma de montos ya leídos, exacta en centavos («0.01» + «0.02» = «0,03»). */
export function sumaMontos(montos: string[]): string {
  const total = montos.reduce((a, m) => a + centavos(m), 0n);
  const negativo = total < 0n;
  const abs = negativo ? -total : total;
  const texto = `${abs / 100n},${String(abs % 100n).padStart(2, '0')}`;
  return negativo ? '-' + texto : texto;
}

/** El motivo por el que la novedad no se puede enviar, o null si está lista. */
export function problemaNovedad(f: FormNovedad, exigenFuerza: ReadonlySet<string> = new Set()): string | null {
  if (!f.vigenciaId) return 'Seleccione la vigencia.';
  const soporte = f.soporte.trim();
  if (!soporte) return 'El soporte de la DIGSA es obligatorio.';
  if (soporte.length < 5 || soporte.length > 300) return 'El soporte de la DIGSA debe tener entre 5 y 300 caracteres.';
  if (f.observacion.trim().length > 1000) return 'La observación tiene máximo 1000 caracteres.';
  if (f.tipo === 'reajuste' && f.porPorcentaje) {
    const p = leerMonto(f.porcentaje);
    if (p === null || centavos(p) === 0n) return 'Escriba el porcentaje del reajuste (distinto de 0, con máximo 2 decimales).';
    if (centavos(p) < -10000n || centavos(p) > 100000n) return 'El porcentaje del reajuste debe estar entre -100 y 1000.';
    return null;
  }
  if (f.filas.length === 0) return 'Agregue al menos una línea.';
  const claves = new Set<string>();
  const montos: string[] = [];
  for (const [i, fila] of f.filas.entries()) {
    const n = i + 1;
    if (!fila.puntoId) return `Línea ${n}: seleccione el ESM.`;
    if (!fila.modalidad) return `Línea ${n}: seleccione la modalidad.`;
    if (exigenFuerza.has(fila.puntoId) && !fila.fuerza) return `Línea ${n}: seleccione la fuerza de adscripción.`;
    const m = leerMonto(fila.valor);
    if (m === null) return `Línea ${n}: el valor no es un monto en pesos con máximo 2 decimales.`;
    const c = centavos(m);
    const conSigno = f.tipo === 'reajuste' || f.tipo === 'recomposicion';
    if (conSigno ? c === 0n : c <= 0n) return `Línea ${n}: el valor debe ser ${conSigno ? 'distinto de 0' : 'mayor que 0'}.`;
    const clave = `${fila.puntoId}|${fila.modalidad}|${fila.fuerza}`;
    if (claves.has(clave))
      return f.tipo === 'rebalanceo' ? 'El origen y el destino deben ser líneas distintas.' : `Línea ${n}: la línea está repetida.`;
    claves.add(clave);
    montos.push(m);
  }
  if (f.tipo === 'rebalanceo') {
    if (f.filas.length !== 2) return 'El rebalanceo va de una línea de origen a una de destino.';
    if (montos[0] !== montos[1]) return 'El valor del origen y el del destino deben ser iguales.';
  }
  if (f.tipo === 'recomposicion') {
    if (f.filas.length < 2) return 'La recomposición redistribuye al menos dos líneas.';
    const suma = sumaMontos(montos);
    if (suma !== '0,00') return `La recomposición debe sumar 0,00 (suma ${suma}).`;
  }
  return null;
}

/** Cuerpo de «datos» para POST /api/presupuesto/novedades (los montos van como texto con punto decimal: el servidor los lee como BigDecimal). */
export function cuerpoNovedad(f: FormNovedad) {
  const porPorcentaje = f.tipo === 'reajuste' && f.porPorcentaje;
  return {
    vigenciaId: Number(f.vigenciaId),
    tipo: f.tipo,
    soporte: f.soporte.trim(),
    observacion: f.observacion.trim() || null,
    porcentaje: porPorcentaje ? leerMonto(f.porcentaje) : null,
    ambito: porPorcentaje ? { fuerza: f.ambitoFuerza || null, modalidad: f.ambitoModalidad || null } : null,
    items: porPorcentaje
      ? []
      : f.filas.map((x) => ({ puntoId: Number(x.puntoId), modalidad: x.modalidad, fuerza: x.fuerza || null, valor: leerMonto(x.valor) })),
  };
}

export function etiquetaPunto(p: { sigla: string | null; nombre: string }): string {
  return p.sigla ? `${p.sigla} · ${p.nombre}` : p.nombre;
}

/** El ESM es HOMIL (fuerza «NA»): la línea exige la fuerza de adscripción. */
export function exigeFuerza(p: PuntoEsm | undefined): boolean {
  return p?.fuerza === 'NA';
}

export function colorSemaforo(s: string): string {
  return s === 'rojo' ? 'red' : s === 'ambar' ? 'yellow' : 'green';
}

export interface FiltrosPresupuesto {
  vigencia: string;
  fuerza: string;
  punto: string;
  modalidad: string;
  tipo: string;
  desde: string;
  hasta: string;
}

export const FILTROS_PRESUPUESTO: FiltrosPresupuesto = { vigencia: '', fuerza: '', punto: '', modalidad: '', tipo: '', desde: '', hasta: '' };

/** Consulta con solo los filtros diligenciados, en un orden fijo (la clave de react-query no cambia por el orden). */
export function consultaPresupuesto(f: Partial<FiltrosPresupuesto>, extra: Record<string, string | number> = {}): string {
  const p = new URLSearchParams();
  for (const k of Object.keys(FILTROS_PRESUPUESTO) as (keyof FiltrosPresupuesto)[]) {
    const v = f[k];
    if (v) p.set(k, v);
  }
  for (const [k, v] of Object.entries(extra)) p.set(k, String(v));
  return p.toString();
}
