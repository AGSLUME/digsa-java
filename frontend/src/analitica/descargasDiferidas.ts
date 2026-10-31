/**
 * Descarga diferida de analítica (A7.3, duda AN.16; docs/especificaciones/analitica-tableros «Descarga diferida cuando no cabe en la
 * petición»): el CSV de registros de origen de una cifra con más registros que el umbral responde 202 con la descarga encolada; se genera
 * en segundo plano y queda en «Mis descargas del día» (24 h) para descargarla cuando está lista. Las descargas de los tableros son
 * agregadas y siempre salen en la petición.
 */
import { ErrorApi, obtenerArchivo } from '../shared/api';
import { estadoVisible, intervaloSondeo, type EstadoExportacion } from '../shared/exportacion';
import { conteo } from '../shared/formato';

export interface DescargaDiferida {
  id: number;
  tablero: string;
  titulo: string;
  nombre: string;
  corteCargueId: number;
  registros: number;
  estado: EstadoExportacion;
  tamano: number | null;
  sha256: string | null;
  huella: string | null;
  error: string | null;
  creado: string | null;
  iniciado: string | null;
  terminado: string | null;
  vence: string | null;
  duracionMs: number | null;
  /** Solo en la respuesta 202: qué pasó y dónde queda. */
  mensaje?: string;
}

/** Llave de react-query de «Mis descargas del día» (la invalida quien pide una descarga que quedó diferida). */
export const CLAVE_DESCARGAS_DIFERIDAS = ['analitica', 'descargas-diferidas'];

export const RUTA_DESCARGAS_DIFERIDAS = '/api/analitica/descargas-diferidas';

export function rutaArchivoDiferido(id: number): string {
  return `${RUTA_DESCARGAS_DIFERIDAS}/${id}/archivo`;
}

/** Intervalo de sondeo: cada 3 s mientras haya una en cola o generándose; si no, se detiene. */
export function intervaloSondeoDiferidas(lista: Pick<DescargaDiferida, 'estado'>[] | undefined): number | false {
  return intervaloSondeo(lista);
}

/** ¿Se puede descargar? (lista y sin vencer: una lista cuyo plazo pasó se ve vencida). */
export function descargableDiferida(d: Pick<DescargaDiferida, 'estado' | 'vence'>, ahora: number = Date.now()): boolean {
  return estadoVisible(d, ahora) === 'lista';
}

/**
 * Milisegundos hasta el próximo vencimiento de una descarga lista (para repintarla como vencida con la pantalla abierta, sin mantener el
 * sondeo), o null si no hay ninguna por vencer.
 */
export function msHastaProximoVencimiento(
  lista: Pick<DescargaDiferida, 'estado' | 'vence'>[] | undefined,
  ahora: number = Date.now(),
): number | null {
  let menor: number | null = null;
  for (const d of lista ?? []) {
    if (d.estado !== 'lista' || !d.vence) continue;
    const v = Date.parse(d.vence);
    if (Number.isNaN(v) || v <= ahora) continue;
    menor = menor === null ? v - ahora : Math.min(menor, v - ahora);
  }
  return menor;
}

/** Qué se descarga: «Registros de origen · General de dispensación». */
export function queDescarga(d: Pick<DescargaDiferida, 'titulo'>): string {
  return `Registros de origen · ${d.titulo}`;
}

/** Texto del avance: en cola, generándose (con los registros) o el resultado. */
export function textoAvance(d: Pick<DescargaDiferida, 'estado' | 'registros' | 'vence'>, ahora: number = Date.now()): string {
  switch (estadoVisible(d, ahora)) {
    case 'pendiente':
      return `En cola · ${conteo(d.registros)} registros`;
    case 'generando':
      return `Generando ${conteo(d.registros)} registros…`;
    case 'lista':
      return 'Lista para descargar';
    case 'fallida':
      return 'No se pudo generar';
    default:
      return 'Venció y se borró';
  }
}

/** Mensaje de la notificación cuando la descarga quedó diferida (el del servidor, o uno genérico). */
export function mensajeDiferida(d: Pick<DescargaDiferida, 'mensaje' | 'registros'>): string {
  return (
    d.mensaje ??
    `La descarga tiene ${conteo(d.registros)} registros: se está generando en segundo plano. Quedará en «Mis descargas del día» con su enlace.`
  );
}

/** Mensaje de una descarga que no se pudo bajar desde «Mis descargas del día» (el del servidor si lo trae). */
export function mensajeErrorDescarga(e: unknown): string {
  if (e instanceof ErrorApi) {
    if (e.message && !/^Error \d+$/.test(e.message)) return e.message;
    if (e.estado === 410) return 'La descarga venció y su archivo se borró. Pídala de nuevo.';
    if (e.estado === 409) return 'La descarga aún no está lista o no se pudo generar.';
    if (e.estado === 403) return 'Ya no tiene permiso para descargar este archivo.';
  }
  return 'No se pudo descargar el archivo.';
}

/** ¿El error cambia el estado que se ve? (vencida, no lista o sin permiso: hay que volver a consultar la lista). */
export function refrescarTrasError(e: unknown): boolean {
  return e instanceof ErrorApi && [403, 404, 409, 410].includes(e.estado);
}

/** Texto de un blob (FileReader: también en los navegadores sin Blob.text). */
function textoDe(blob: Blob): Promise<string> {
  return new Promise((resolver, rechazar) => {
    const r = new FileReader();
    r.onload = () => resolver(String(r.result));
    r.onerror = () => rechazar(r.error ?? new Error('No se pudo leer la respuesta.'));
    r.readAsText(blob);
  });
}

/**
 * Pide el CSV de registros de origen: si cabe en la petición guarda el archivo (como `descargar`) y devuelve null; si el servidor lo
 * difirió (202, cuerpo JSON) devuelve la descarga encolada sin guardar nada. El CSV en la petición nunca es JSON.
 */
export async function descargarODiferir(ruta: string): Promise<DescargaDiferida | null> {
  const { blob, nombre } = await obtenerArchivo(ruta);
  if (blob.type.includes('application/json')) {
    return JSON.parse(await textoDe(blob)) as DescargaDiferida;
  }
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = nombre;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
  return null;
}
