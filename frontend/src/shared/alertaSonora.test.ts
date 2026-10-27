// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/** AudioContext de mentira: cuenta los osciladores que se crean. */
function audioFalso() {
  const osciladores: unknown[] = [];
  class Contexto {
    state = 'running';
    currentTime = 0;
    destination = {};
    resume = () => Promise.resolve();
    createOscillator() {
      const o = { type: '', frequency: { value: 0 }, connect: (g: unknown) => g, start: vi.fn(), stop: vi.fn() };
      osciladores.push(o);
      return o;
    }
    createGain() {
      return { gain: { setValueAtTime: vi.fn(), exponentialRampToValueAtTime: vi.fn() }, connect: (d: unknown) => d };
    }
  }
  return { Contexto, osciladores };
}

describe('alerta sonora (Anexo 5 num. 2.4, decisión UI-8)', () => {
  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('activada por omisión: suena dos notas cortas', async () => {
    const { Contexto, osciladores } = audioFalso();
    vi.stubGlobal('AudioContext', Contexto);
    const { sonarError, sonidoActivo } = await import('./alertaSonora');
    expect(sonidoActivo()).toBe(true);
    sonarError();
    expect(osciladores).toHaveLength(2);
  });

  it('no repite el sonido en menos de 400 ms (varios campos a la vez)', async () => {
    const { Contexto, osciladores } = audioFalso();
    vi.stubGlobal('AudioContext', Contexto);
    const { sonarError } = await import('./alertaSonora');
    sonarError();
    sonarError();
    expect(osciladores).toHaveLength(2);
    vi.advanceTimersByTime(500);
    sonarError();
    expect(osciladores).toHaveLength(4);
  });

  it('el interruptor lo apaga y el navegador lo recuerda', async () => {
    const { Contexto, osciladores } = audioFalso();
    vi.stubGlobal('AudioContext', Contexto);
    const m1 = await import('./alertaSonora');
    m1.activarSonido(false);
    m1.sonarError();
    expect(osciladores).toHaveLength(0);
    expect(localStorage.getItem('digsa.sonido')).toBe('no');

    vi.resetModules();
    const m2 = await import('./alertaSonora');
    expect(m2.sonidoActivo()).toBe(false);
  });

  it('sin almacenamiento ni audio no falla (navegación privada, equipo sin sonido)', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('bloqueado');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('bloqueado');
    });
    vi.stubGlobal('AudioContext', undefined);
    const { activarSonido, sonarError, sonidoActivo } = await import('./alertaSonora');
    expect(sonidoActivo()).toBe(true);
    expect(() => activarSonido(false)).not.toThrow();
    expect(sonidoActivo()).toBe(false);
    activarSonido(true);
    expect(() => sonarError()).not.toThrow();
    vi.restoreAllMocks();
  });

  it('la validación nativa del navegador (evento invalid) también suena', async () => {
    const { Contexto, osciladores } = audioFalso();
    vi.stubGlobal('AudioContext', Contexto);
    const { sonarEnValidacionNativa } = await import('./alertaSonora');
    const dejar = sonarEnValidacionNativa();
    const campo = document.createElement('input');
    campo.required = true;
    document.body.appendChild(campo);
    campo.checkValidity();
    expect(osciladores).toHaveLength(2);
    dejar();
    campo.remove();
  });
});
