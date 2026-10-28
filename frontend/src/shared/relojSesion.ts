import { useSyncExternalStore } from 'react';

/**
 * Segundos que le quedan a la sesión antes del cierre por inactividad, para la cuenta regresiva de la cabecera (mockup). Los publica
 * `AvisoInactividad` en cada tic (es quien sabe la última actividad y el plazo del servidor); la cabecera solo los lee. Va en un almacén
 * aparte para que el tic de cada segundo vuelva a pintar solo la cabecera y no la pantalla abierta.
 */
let restantes: number | null = null;
const oyentes = new Set<() => void>();

export function publicarRestantes(segundos: number | null): void {
  if (segundos === restantes) return;
  restantes = segundos;
  oyentes.forEach((f) => f());
}

function suscribir(f: () => void): () => void {
  oyentes.add(f);
  return () => oyentes.delete(f);
}

function leer(): number | null {
  return restantes;
}

export function useRestantesSesion(): number | null {
  return useSyncExternalStore(suscribir, leer, () => null);
}
