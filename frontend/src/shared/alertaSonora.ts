import { useSyncExternalStore } from 'react';

/**
 * Aviso sonoro ante el mal diligenciamiento de campos (Anexo 5 num. 2.4; decisión UI-8): un tono corto y suave generado con Web Audio
 * (sin archivos). Activado por omisión; el interruptor de la cabecera lo apaga y el navegador lo recuerda. Siempre acompaña a un aviso
 * visual: nunca es el único.
 */

const CLAVE = 'digsa.sonido';
const oyentes = new Set<() => void>();

function leer(): boolean {
  try {
    return window.localStorage.getItem(CLAVE) !== 'no';
  } catch {
    return true; // navegación privada o almacenamiento bloqueado: se queda el valor por omisión
  }
}

let activo = leer();

export function sonidoActivo(): boolean {
  return activo;
}

export function activarSonido(valor: boolean): void {
  activo = valor;
  try {
    window.localStorage.setItem(CLAVE, valor ? 'si' : 'no');
  } catch {
    // sin almacenamiento: vale para esta pestaña
  }
  oyentes.forEach((f) => f());
}

// Otra pestaña cambió el interruptor: esta lo sigue sin recargar
function alCambiarAlmacen(e: StorageEvent): void {
  if (e.key !== CLAVE && e.key !== null) return;
  activo = leer();
  oyentes.forEach((f) => f());
}

function suscribir(f: () => void): () => void {
  if (oyentes.size === 0) window.addEventListener('storage', alCambiarAlmacen);
  oyentes.add(f);
  return () => {
    oyentes.delete(f);
    if (oyentes.size === 0) window.removeEventListener('storage', alCambiarAlmacen);
  };
}

/** Estado del interruptor para la cabecera. */
export function useSonido(): [boolean, (valor: boolean) => void] {
  const valor = useSyncExternalStore(suscribir, sonidoActivo, () => true);
  return [valor, activarSonido];
}

type ConstructorAudio = typeof AudioContext;
let contexto: AudioContext | null = null;
let ultimo = 0;

/** El contexto de audio del navegador (se crea una vez), reanudado; null si el equipo no tiene Web Audio. */
function contextoAudio(): AudioContext | null {
  const Constructor = (window.AudioContext ?? (window as unknown as { webkitAudioContext?: ConstructorAudio }).webkitAudioContext) as
    ConstructorAudio | undefined;
  if (!Constructor) return null;
  contexto ??= new Constructor();
  if (contexto.state === 'suspended') void contexto.resume().catch(() => undefined);
  return contexto;
}

/** Dos notas descendentes de 110 ms a volumen bajo. No suena dos veces seguidas en menos de 400 ms (varios campos inválidos a la vez). */
export function sonarError(): void {
  if (!activo) return;
  const ahora = Date.now();
  if (ahora - ultimo < 400) return;
  ultimo = ahora;
  try {
    const ctx = contextoAudio();
    if (!ctx) return;
    const t = ctx.currentTime;
    [523.25, 392].forEach((frecuencia, i) => {
      const oscilador = ctx.createOscillator();
      const volumen = ctx.createGain();
      oscilador.type = 'sine';
      oscilador.frequency.value = frecuencia;
      const inicio = t + i * 0.12;
      volumen.gain.setValueAtTime(0.0001, inicio);
      volumen.gain.exponentialRampToValueAtTime(0.08, inicio + 0.02);
      volumen.gain.exponentialRampToValueAtTime(0.0001, inicio + 0.11);
      oscilador.connect(volumen).connect(ctx.destination);
      oscilador.start(inicio);
      oscilador.stop(inicio + 0.12);
    });
  } catch {
    // sin audio en el equipo: el aviso visual basta
  }
}

/**
 * La validación nativa del navegador (`required`, `min`, `type=email`, patrón de la hora) también suena: el evento `invalid` no
 * burbujea, así que se escucha en captura sobre el documento. Devuelve la función para dejar de escuchar.
 */
export function sonarEnValidacionNativa(): () => void {
  const alInvalido = () => sonarError();
  document.addEventListener('invalid', alInvalido, true);
  return () => document.removeEventListener('invalid', alInvalido, true);
}

/**
 * Los navegadores solo dejan sonar un AudioContext creado o reanudado durante un gesto del usuario. Un error del servidor llega
 * después (asíncrono): el contexto se prepara en los gestos de la sesión hasta que quede sonando. En pantallas táctiles el navegador
 * solo cuenta `touchend` y `click` como gesto, no `pointerdown`.
 */
export function prepararAudioEnPrimerGesto(): () => void {
  const GESTOS = ['pointerdown', 'keydown', 'touchend', 'click'] as const;
  const preparar = () => {
    try {
      const ctx = contextoAudio();
      if (ctx && ctx.state !== 'running') return;
    } catch {
      // sin audio en el equipo
    }
    quitar();
  };
  const quitar = () => GESTOS.forEach((g) => document.removeEventListener(g, preparar, true));
  GESTOS.forEach((g) => document.addEventListener(g, preparar, true));
  return quitar;
}
