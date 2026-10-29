/**
 * SHA-256 incremental (FIPS 180-4) para la subida por partes de los cargues del OPL: el servidor compara la huella del archivo completo
 * con la que declara el navegador. crypto.subtle.digest necesita todo el archivo en memoria (cientos de MB); este cálculo recorre el
 * archivo por trozos.
 */

const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
  0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
  0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
  0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]);

export class Sha256 {
  private readonly h = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]);
  private readonly w = new Uint32Array(64);
  private readonly bloque = new Uint8Array(64);
  private enBloque = 0;
  private bytes = 0;
  private terminado = false;

  update(datos: Uint8Array): this {
    if (this.terminado) throw new Error('El SHA-256 ya se calculó');
    let i = 0;
    this.bytes += datos.length;
    if (this.enBloque > 0) {
      const n = Math.min(64 - this.enBloque, datos.length);
      this.bloque.set(datos.subarray(0, n), this.enBloque);
      this.enBloque += n;
      i = n;
      if (this.enBloque === 64) {
        this.procesar(this.bloque, 0);
        this.enBloque = 0;
      }
    }
    for (; i + 64 <= datos.length; i += 64) this.procesar(datos, i);
    if (i < datos.length) {
      this.bloque.set(datos.subarray(i), 0);
      this.enBloque = datos.length - i;
    }
    return this;
  }

  /** Huella en hexadecimal (64 caracteres, minúsculas). */
  hex(): string {
    if (!this.terminado) {
      const bits = this.bytes * 8;
      const relleno = new Uint8Array((this.enBloque < 56 ? 56 : 120) - this.enBloque + 8);
      relleno[0] = 0x80;
      const alto = Math.floor(bits / 0x100000000);
      const bajo = bits >>> 0;
      const v = new DataView(relleno.buffer);
      v.setUint32(relleno.length - 8, alto);
      v.setUint32(relleno.length - 4, bajo);
      const bytes = this.bytes;
      this.update(relleno);
      this.bytes = bytes;
      this.terminado = true;
    }
    return Array.from(this.h, (x) => x.toString(16).padStart(8, '0')).join('');
  }

  private procesar(d: Uint8Array, o: number): void {
    const w = this.w;
    for (let t = 0; t < 16; t++) {
      const j = o + t * 4;
      w[t] = ((d[j]! << 24) | (d[j + 1]! << 16) | (d[j + 2]! << 8) | d[j + 3]!) >>> 0;
    }
    for (let t = 16; t < 64; t++) {
      const a = w[t - 15]!;
      const b = w[t - 2]!;
      const s0 = ((a >>> 7) | (a << 25)) ^ ((a >>> 18) | (a << 14)) ^ (a >>> 3);
      const s1 = ((b >>> 17) | (b << 15)) ^ ((b >>> 19) | (b << 13)) ^ (b >>> 10);
      w[t] = (w[t - 16]! + s0 + w[t - 7]! + s1) >>> 0;
    }
    const h = this.h;
    let a = h[0]!;
    let b = h[1]!;
    let c = h[2]!;
    let e0 = h[3]!;
    let e = h[4]!;
    let f = h[5]!;
    let g = h[6]!;
    let hh = h[7]!;
    for (let t = 0; t < 64; t++) {
      const S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
      const ch = (e & f) ^ (~e & g);
      const t1 = (hh + S1 + ch + K[t]! + w[t]!) >>> 0;
      const S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const t2 = (S0 + maj) >>> 0;
      hh = g;
      g = f;
      f = e;
      e = (e0 + t1) >>> 0;
      e0 = c;
      c = b;
      b = a;
      a = (t1 + t2) >>> 0;
    }
    h[0] = (h[0]! + a) >>> 0;
    h[1] = (h[1]! + b) >>> 0;
    h[2] = (h[2]! + c) >>> 0;
    h[3] = (h[3]! + e0) >>> 0;
    h[4] = (h[4]! + e) >>> 0;
    h[5] = (h[5]! + f) >>> 0;
    h[6] = (h[6]! + g) >>> 0;
    h[7] = (h[7]! + hh) >>> 0;
  }
}

/** SHA-256 de un texto en UTF-8 (pruebas y comprobaciones). */
export function sha256Texto(texto: string): string {
  return new Sha256().update(new TextEncoder().encode(texto)).hex();
}

/** Bytes de un trozo del archivo (FileReader si el entorno no tiene Blob.arrayBuffer). */
function leer(trozo: Blob): Promise<ArrayBuffer> {
  if (typeof trozo.arrayBuffer === 'function') return trozo.arrayBuffer();
  return new Promise((resolver, rechazar) => {
    const r = new FileReader();
    r.onload = () => resolver(r.result as ArrayBuffer);
    r.onerror = () => rechazar(r.error);
    r.readAsArrayBuffer(trozo);
  });
}

/**
 * SHA-256 de un archivo en un Web Worker (sha256.worker.ts): no congela la interfaz con archivos de cientos de MB y se cancela con la
 * señal. Sin Worker (pruebas, navegadores antiguos) se calcula aquí mismo.
 */
export function sha256EnSegundoPlano(archivo: Blob, avance?: (fraccion: number) => void, senal?: AbortSignal): Promise<string> {
  if (typeof Worker === 'undefined') return sha256Archivo(archivo, avance);
  return new Promise((resolver, rechazar) => {
    const w = new Worker(new URL('./sha256.worker.ts', import.meta.url), { type: 'module' });
    const cancelar = () => {
      w.terminate();
      rechazar(new DOMException('Cancelado', 'AbortError'));
    };
    if (senal?.aborted) {
      cancelar();
      return;
    }
    senal?.addEventListener('abort', cancelar, { once: true });
    w.onmessage = (e: MessageEvent<{ tipo: 'avance' | 'fin' | 'error'; fraccion?: number; hex?: string; mensaje?: string }>) => {
      const m = e.data;
      if (m.tipo === 'avance') {
        avance?.(m.fraccion ?? 0);
        return;
      }
      senal?.removeEventListener('abort', cancelar);
      w.terminate();
      if (m.tipo === 'fin' && m.hex) resolver(m.hex);
      else rechazar(new Error(m.mensaje ?? 'No se pudo calcular la huella del archivo.'));
    };
    w.onerror = () => {
      senal?.removeEventListener('abort', cancelar);
      w.terminate();
      // Si el Worker no se pudo cargar, el cálculo se hace en el hilo principal
      sha256Archivo(archivo, avance).then(resolver, rechazar);
    };
    w.postMessage({ archivo });
  });
}

/** SHA-256 de un archivo por trozos de 8 MB; avisa el avance (0 a 1). */
export async function sha256Archivo(archivo: Blob, avance?: (fraccion: number) => void, trozo = 8 * 1024 * 1024): Promise<string> {
  const h = new Sha256();
  for (let i = 0; i < archivo.size; i += trozo) {
    const parte = new Uint8Array(await leer(archivo.slice(i, Math.min(archivo.size, i + trozo))));
    h.update(parte);
    avance?.(Math.min(1, (i + parte.length) / Math.max(1, archivo.size)));
  }
  return h.hex();
}
