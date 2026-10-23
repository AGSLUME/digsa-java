/**
 * Regla del aviso de inactividad (Anexo 5, 3.2; igual que el PHP): un minuto antes del cierre, o la mitad del plazo si es menor,
 * se muestra el aviso con la cuenta regresiva; a los 2 segundos del plazo se vuelve al ingreso.
 */
export interface EstadoAviso {
  restantes: number;
  mostrar: boolean;
  vencida: boolean;
}

export function estadoAviso(ultimaActividadMs: number, ahoraMs: number, minutos: number): EstadoAviso {
  const plazo = minutos * 60;
  const transcurridos = Math.floor((ahoraMs - ultimaActividadMs) / 1000);
  const restantes = Math.max(0, plazo - transcurridos);
  const umbral = Math.min(60, Math.floor(plazo / 2));
  return { restantes, mostrar: restantes <= umbral, vencida: transcurridos >= plazo + 2 };
}

/** mm:ss para la cuenta regresiva. */
export function mmss(segundos: number): string {
  const m = Math.floor(segundos / 60);
  const s = segundos % 60;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

/**
 * Última actividad según el servidor (GET /api/sesion/estado): con su plazo vigente y los segundos que le quedan, la actividad fue hace
 * (plazo - restantes). El administrador puede haber cambiado el TIME OUT con la sesión abierta.
 */
export function ultimaActividadDelServidor(ahoraMs: number, minutos: number, restantes: number): number {
  const transcurridos = Math.max(0, minutos * 60 - Math.max(0, restantes));
  return ahoraMs - transcurridos * 1000;
}
