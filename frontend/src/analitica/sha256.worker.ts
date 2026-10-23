import { sha256Archivo } from './sha256';

/**
 * Web Worker del SHA-256 de la subida por partes: el cálculo de un archivo de cientos de MB corre fuera del hilo de la interfaz (no la
 * congela) y avisa el avance. Recibe { archivo } y responde { tipo: 'avance', fraccion }, { tipo: 'fin', hex } o { tipo: 'error' }.
 */
const ambito = self as unknown as { onmessage: ((e: MessageEvent<{ archivo: Blob }>) => void) | null; postMessage: (m: unknown) => void };

ambito.onmessage = (e) => {
  sha256Archivo(e.data.archivo, (fraccion) => ambito.postMessage({ tipo: 'avance', fraccion }))
    .then((hex) => ambito.postMessage({ tipo: 'fin', hex }))
    .catch((err: unknown) => ambito.postMessage({ tipo: 'error', mensaje: err instanceof Error ? err.message : String(err) }));
};
