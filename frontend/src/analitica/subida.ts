import { api, CLAVE_MOTIVO, ErrorApi, marcarActividad } from '../shared/api';
import { PARTE_BYTES, partes, type Cargue } from './cargues';
import { sha256EnSegundoPlano } from './sha256';

/**
 * Subida por partes de un archivo de cargue (analitica-ingesta): SHA-256 del archivo completo en un Web Worker, creación del cargue,
 * cada parte del tamaño que fija el servidor (la última con el resto) con PUT (application/octet-stream, con el avance de
 * XMLHttpRequest) y cierre, que verifica la huella y deja el cargue en «mapeo». Si el archivo ya se aplicó, el servidor devuelve ese
 * cargue sin subir nada. Se cancela con la señal (las partes en curso se abortan); un 401 lleva al ingreso como en shared/api.ts.
 */

export interface Avance {
  etapa: 'huella' | 'subida' | 'cierre';
  fraccion: number;
  /** Cargue creado (para descartarlo si el usuario cancela). */
  cargue?: number;
}

export interface OpcionesSubida {
  /** Tamaño de cada parte que informa el servidor (digsa.analitica.parte-max-mb). */
  parteBytes?: number;
  senal?: AbortSignal;
}

/** El mismo evento que shared/api.ts dispara cuando la sesión se cerró (lleva al ingreso con el motivo). */
const EVENTO_SESION_CERRADA = 'digsa:sesion-cerrada';

function token(): string | undefined {
  const t = /(?:^|; )XSRF-TOKEN=([^;]+)/.exec(document.cookie)?.[1];
  return t ? decodeURIComponent(t) : undefined;
}

/** Igual que leerRespuesta de shared/api.ts: solo una sesión que existía y se cerró lleva al ingreso con el motivo. */
function sesionCerrada(estado: number, motivo: unknown, mensaje: string): void {
  if (estado !== 401 || typeof motivo !== 'string' || motivo === 'sin_sesion') return;
  try {
    sessionStorage.setItem(CLAVE_MOTIVO, mensaje);
  } catch {
    // sin almacenamiento
  }
  window.dispatchEvent(new CustomEvent(EVENTO_SESION_CERRADA, { detail: mensaje }));
}

/** PUT de una parte con su avance (bytes enviados). */
function enviarParte(ruta: string, cuerpo: Blob, avance: (enviados: number) => void, senal?: AbortSignal): Promise<void> {
  return new Promise((resolver, rechazar) => {
    const x = new XMLHttpRequest();
    x.open('PUT', ruta);
    x.setRequestHeader('Content-Type', 'application/octet-stream');
    x.setRequestHeader('Accept', 'application/json');
    const t = token();
    if (t) x.setRequestHeader('X-XSRF-TOKEN', t);
    x.withCredentials = true;
    const abortar = () => x.abort();
    senal?.addEventListener('abort', abortar, { once: true });
    x.upload.onprogress = (e) => avance(e.loaded);
    x.onload = () => {
      senal?.removeEventListener('abort', abortar);
      marcarActividad();
      if (x.status >= 200 && x.status < 300) {
        resolver();
        return;
      }
      let mensaje = `Error ${x.status}`;
      let motivo: unknown;
      try {
        const d = JSON.parse(x.responseText) as { mensaje?: string; motivo?: string };
        if (d.mensaje) mensaje = d.mensaje;
        motivo = d.motivo;
      } catch {
        // respuesta sin JSON
      }
      sesionCerrada(x.status, motivo, mensaje);
      rechazar(new ErrorApi(x.status, mensaje));
    };
    x.onerror = () => {
      senal?.removeEventListener('abort', abortar);
      rechazar(new ErrorApi(0, 'No hay conexión con el servidor. Revise su conexión e intente de nuevo.'));
    };
    x.onabort = () => rechazar(new DOMException('Cancelado', 'AbortError'));
    x.send(cuerpo);
  });
}

/** Sube el archivo completo y devuelve el cargue cerrado (en «mapeo») o el ya aplicado con el mismo SHA-256. */
export async function subirArchivo(archivo: File, avance: (a: Avance) => void, opciones: OpcionesSubida = {}): Promise<Cargue> {
  const { senal } = opciones;
  const sha256 = await sha256EnSegundoPlano(archivo, (f) => avance({ etapa: 'huella', fraccion: f }), senal);
  const lista = partes(archivo.size, opciones.parteBytes ?? PARTE_BYTES);
  senal?.throwIfAborted();
  const creado = await api<Cargue>('/api/analitica/cargues', {
    cuerpo: { nombre: archivo.name, tamano: archivo.size, sha256, partes: lista.length },
  });
  if (creado.existente) return creado;
  avance({ etapa: 'subida', fraccion: 0, cargue: creado.id });
  let enviados = 0;
  for (let i = 0; i < lista.length; i++) {
    const [desde, hasta] = lista[i]!;
    await enviarParte(
      `/api/analitica/cargues/${creado.id}/partes/${i + 1}`,
      archivo.slice(desde, hasta),
      (n) => avance({ etapa: 'subida', fraccion: Math.min(1, (enviados + n) / Math.max(1, archivo.size)), cargue: creado.id }),
      senal,
    );
    enviados += hasta - desde;
  }
  senal?.throwIfAborted();
  avance({ etapa: 'cierre', fraccion: 1, cargue: creado.id });
  return api<Cargue>(`/api/analitica/cargues/${creado.id}/cerrar`, { metodo: 'POST' });
}
