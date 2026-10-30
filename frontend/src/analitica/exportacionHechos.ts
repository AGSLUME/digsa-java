/**
 * Exportación de los hechos de dispensación por periodo (A7.2, duda AN.14; docs/especificaciones/analitica-ingesta): el Administrador
 * pide uno o varios meses, el servidor genera un .csv.gz por mes en segundo plano y la sección de «Cargues del OPL» muestra el progreso
 * por mes y las descargas (enlace directo: el navegador escribe el archivo en disco en flujo).
 */
import { cifra, conteo } from '../shared/formato';
import { enCurso, estadoVisible, type EstadoExportacion } from '../shared/exportacion';

export type EstadoMes = 'pendiente' | 'generando' | 'lista' | 'fallida' | 'vencida';

export interface ArchivoMes {
  mes: string;
  nombre: string;
  estado: EstadoMes;
  filas: number | null;
  valorTotal: number | null;
  tamano: number | null;
  sha256: string | null;
  error: string | null;
  iniciado: string | null;
  terminado: string | null;
  duracionMs: number | null;
}

export interface ExportacionHechos {
  id: number;
  desde: string;
  hasta: string;
  corte: string;
  corteCargueId: number | null;
  estado: EstadoExportacion;
  meses: number;
  mesesListos: number;
  mesesFallidos: number;
  filas: number;
  tamano: number;
  leeme: boolean;
  nombreLeeme: string;
  error: string | null;
  creado: string | null;
  iniciado: string | null;
  terminado: string | null;
  vence: string | null;
  archivos: ArchivoMes[];
}

export const NOMBRE_ESTADO_MES: Record<EstadoMes, string> = {
  pendiente: 'En cola',
  generando: 'Generando',
  lista: 'Listo',
  fallida: 'Falló',
  vencida: 'Vencido',
};

export const COLOR_ESTADO_MES: Record<EstadoMes, string> = {
  pendiente: 'gray',
  generando: 'blue',
  lista: 'green',
  fallida: 'red',
  vencida: 'orange',
};

/** Meses por solicitud (digsa.analitica.exportacion-hechos.meses-max; el servidor manda). */
export const MESES_MAX = 60;

const RE_MES = /^(\d{4})-(\d{2})$/;

/** Número de mes absoluto (año · 12 + mes − 1) de «aaaa-mm», o null si no es un mes válido. */
export function indiceMes(texto: string): number | null {
  const m = RE_MES.exec(texto.trim());
  if (!m) return null;
  const anio = Number(m[1]);
  const mes = Number(m[2]);
  if (mes < 1 || mes > 12) return null;
  return anio * 12 + mes - 1;
}

/** «aaaa-mm» de la fecha ISO de hoy en Bogotá («2026-10-30» ⇒ «2026-10»). */
export function mesDe(hoyIso: string): string {
  return hoyIso.slice(0, 7);
}

/** Meses del periodo con ambos extremos (0 si no es válido). */
export function cuantosMeses(desde: string, hasta: string): number {
  const d = indiceMes(desde);
  const h = indiceMes(hasta || desde);
  return d === null || h === null || h < d ? 0 : h - d + 1;
}

/**
 * Errores del formulario por campo (los mismos que valida el servidor, que tiene la última palabra): mes con formato aaaa-mm, desde
 * 1990-01, no posterior al mes actual, «hasta» no anterior a «desde» y como máximo {@link MESES_MAX} meses.
 */
export function erroresPeriodo(desde: string, hasta: string, mesActual: string, maximo = MESES_MAX): Record<string, string> {
  const e: Record<string, string> = {};
  const d = indiceMes(desde);
  const h = hasta.trim() === '' ? d : indiceMes(hasta);
  const actual = indiceMes(mesActual);
  if (d === null) e.desde = 'Indique el mes inicial (aaaa-mm).';
  else if (d < 1990 * 12) e.desde = 'El mes inicial no puede ser anterior a 1990-01.';
  else if (actual !== null && d > actual) e.desde = 'El mes inicial no puede ser posterior al mes actual.';
  if (h === null) e.hasta = 'Indique el mes final (aaaa-mm).';
  else if (actual !== null && h > actual) e.hasta = 'El mes final no puede ser posterior al mes actual.';
  else if (d !== null && h < d) e.hasta = 'El mes final no puede ser anterior al inicial.';
  else if (d !== null && h - d + 1 > maximo) e.hasta = `Pida como máximo ${maximo} meses por exportación.`;
  return e;
}

/** Cuerpo de la solicitud: sin «hasta», un solo mes. */
export function solicitud(desde: string, hasta: string): { desde: string; hasta: string | null } {
  return { desde: desde.trim(), hasta: hasta.trim() === '' ? null : hasta.trim() };
}

/** Progreso de la exportación en porcentaje entero (meses terminados, listos o fallidos, sobre el total). */
export function progreso(e: Pick<ExportacionHechos, 'meses' | 'mesesListos' | 'mesesFallidos'>): number {
  if (e.meses <= 0) return 0;
  return Math.round(((e.mesesListos + e.mesesFallidos) * 100) / e.meses);
}

/** Texto del progreso: «3 de 12 meses listos» (y los fallidos si hay). */
export function textoProgreso(e: Pick<ExportacionHechos, 'meses' | 'mesesListos' | 'mesesFallidos'>): string {
  const base = `${conteo(e.mesesListos)} de ${conteo(e.meses)} meses listos`;
  return e.mesesFallidos > 0 ? `${base} · ${conteo(e.mesesFallidos)} con falla` : base;
}

/** Periodo legible: «1998-01» o «1998-01 a 1998-04». */
export function periodoTexto(e: Pick<ExportacionHechos, 'desde' | 'hasta'>): string {
  return e.desde === e.hasta ? e.desde : `${e.desde} a ${e.hasta}`;
}

/** Estado que se muestra de la exportación (una lista o fallida cuyo plazo pasó se ve vencida, como en la exportación completa). */
export function estadoExportacion(e: Pick<ExportacionHechos, 'estado' | 'vence'>, ahora: number = Date.now()): EstadoExportacion {
  if (e.estado === 'fallida' && e.vence) {
    const vence = Date.parse(e.vence);
    if (!Number.isNaN(vence) && vence <= ahora) return 'vencida';
  }
  return estadoVisible(e, ahora);
}

/** ¿Se puede descargar este mes? (mes listo y exportación no vencida). */
export function descargable(e: Pick<ExportacionHechos, 'estado' | 'vence'>, a: Pick<ArchivoMes, 'estado'>, ahora: number = Date.now()): boolean {
  const est = estadoExportacion(e, ahora);
  return a.estado === 'lista' && (est === 'lista' || est === 'fallida');
}

/** Intervalo de react-query: sondea cada 3 s mientras haya una exportación en curso; si no, se detiene. */
export function intervaloSondeoHechos(lista: Pick<ExportacionHechos, 'estado'>[] | undefined): number | false {
  return (lista ?? []).some(enCurso) ? 3000 : false;
}

export function rutaMes(id: number, mes: string): string {
  return `/api/analitica/exportaciones-hechos/${id}/meses/${encodeURIComponent(mes)}`;
}

export function rutaLeeme(id: number): string {
  return `/api/analitica/exportaciones-hechos/${id}/leeme`;
}

/** Valor del mes con dos decimales y coma decimal (Anexo 5 2.4), o «—». */
export function valorMes(a: Pick<ArchivoMes, 'valorTotal'>): string {
  return a.valorTotal === null || a.valorTotal === undefined ? '—' : cifra(a.valorTotal);
}
