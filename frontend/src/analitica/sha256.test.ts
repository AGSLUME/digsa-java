import { describe, expect, it } from 'vitest';
import { Sha256, sha256Archivo, sha256Texto } from './sha256';

describe('SHA-256 incremental', () => {
  it('vectores de prueba de FIPS 180-4', () => {
    expect(sha256Texto('')).toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
    expect(sha256Texto('abc')).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
    expect(sha256Texto('abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq')).toBe(
      '248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1',
    );
  });

  it('un millón de «a» en trozos de tamaños distintos da la huella oficial', () => {
    const h = new Sha256();
    const a = new Uint8Array(1_000_000).fill(0x61);
    let i = 0;
    for (const t of [1, 63, 64, 65, 1000, 4096, 777]) {
      h.update(a.subarray(i, i + t));
      i += t;
    }
    h.update(a.subarray(i));
    expect(h.hex()).toBe('cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0');
  });

  it('texto con tildes en UTF-8 y hex() repetido no cambia la huella', () => {
    const h = new Sha256().update(new TextEncoder().encode('Dispensación'));
    const x = h.hex();
    expect(x).toHaveLength(64);
    expect(h.hex()).toBe(x);
    expect(() => h.update(new Uint8Array(1))).toThrow();
  });

  it('archivo por trozos con avance', async () => {
    const avances: number[] = [];
    const b = new Blob(['a'.repeat(10_000)]);
    const h = await sha256Archivo(b, (f) => avances.push(f), 4096);
    expect(h).toBe(sha256Texto('a'.repeat(10_000)));
    expect(avances.at(-1)).toBe(1);
    expect(avances).toHaveLength(3);
  });
});
