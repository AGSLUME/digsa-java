/**
 * Hora militar (decisión D4 del 2026-10-21): la aplicación muestra y acepta la hora siempre en 24 h, de 00:00 a 23:59, sin depender de
 * la configuración regional del equipo (el control nativo `type="time"` muestra a. m./p. m. en un Windows en 12 horas).
 */

/** Patrón HTML del campo (el navegador no deja enviar el formulario si no se cumple). */
export const PATRON_HORA = '([01][0-9]|2[0-3]):[0-5][0-9]';

export const MENSAJE_HORA = 'Escriba la hora en formato de 24 horas (HH:MM), de 00:00 a 23:59.';

/**
 * Texto escrito a «HH:MM», '' si está vacío o null si no es una hora válida. Acepta «9:30», «09:30», «930», «0930» y solo la hora
 * («14» es 14:00); rechaza a. m./p. m., segundos y valores fuera de rango («24:00», «12:60»).
 */
export function leerHora(texto: string | null | undefined): string | null {
  const t = (texto ?? '').trim();
  if (t === '') return '';
  let h: string;
  let m: string;
  const conPuntos = /^(\d{1,2}):(\d{2})$/.exec(t);
  const seguida = /^(\d{1,2})(\d{2})$/.exec(t);
  if (conPuntos) [, h = '', m = ''] = conPuntos;
  else if (seguida) [, h = '', m = ''] = seguida;
  else if (/^\d{1,2}$/.test(t)) [h, m] = [t, '00'];
  else return null;
  const hh = Number(h);
  const mm = Number(m);
  if (hh > 23 || mm > 59) return null;
  return `${String(hh).padStart(2, '0')}:${m}`;
}

/** Hora válida en 24 h (vacía no cuenta). */
export function horaValida(texto: string | null | undefined): boolean {
  const h = leerHora(texto);
  return h !== null && h !== '';
}

/** Fecha y hora del servidor («2026-11-03T08:30:00» o «2026-11-03 08:30») separadas para los campos de fecha y de hora. */
export function separarFechaHora(v: string | null | undefined): { fecha: string; hora: string } {
  const m = /^(\d{4}-\d{2}-\d{2})(?:[T ](\d{2}:\d{2}))?/.exec((v ?? '').trim());
  return m ? { fecha: m[1] ?? '', hora: m[2] ?? '' } : { fecha: '', hora: '' };
}

/**
 * Fecha y hora de dos campos al valor «yyyy-MM-ddTHH:mm» que espera el servidor: '' si los dos están vacíos; con `error` si falta una
 * de las dos o la hora no es válida.
 */
export function unirFechaHora(fecha: string, hora: string, nombre: string): { valor: string; error: string | null } {
  const f = fecha.trim();
  const h = leerHora(hora);
  if (f === '' && h === '') return { valor: '', error: null };
  if (h === null) return { valor: '', error: `${nombre}: ${MENSAJE_HORA.charAt(0).toLowerCase()}${MENSAJE_HORA.slice(1)}` };
  if (f === '') return { valor: '', error: `${nombre}: falta la fecha.` };
  if (h === '') return { valor: '', error: `${nombre}: falta la hora.` };
  return { valor: `${f}T${h}`, error: null };
}

/**
 * Lo mismo que unirFechaHora, con el error en el campo que lo tiene (Anexo 5 num. 2.4): `campos` dice el nombre de los campos de la
 * fecha y de la hora en el formulario. La hora inválida lleva MENSAJE_HORA (el que muestra CampoHora24 al salir del campo).
 */
export function unirFechaHoraPorCampo(
  fecha: string,
  hora: string,
  campos: { fecha: string; hora: string },
): { valor: string; errores: Record<string, string> } {
  const { valor, error } = unirFechaHora(fecha, hora, '');
  if (!error) return { valor, errores: {} };
  const h = leerHora(hora);
  if (h === null) return { valor: '', errores: { [campos.hora]: MENSAJE_HORA } };
  if (fecha.trim() === '') return { valor: '', errores: { [campos.fecha]: 'Falta la fecha: escriba la fecha y la hora, o deje las dos vacías.' } };
  return { valor: '', errores: { [campos.hora]: 'Falta la hora: escriba la fecha y la hora, o deje las dos vacías.' } };
}
