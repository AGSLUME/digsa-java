import { separarCorreos } from './agenda';
import { sumarDias } from './formato';

// Validaciones del cliente con las mismas reglas del servidor (Anexo 5 num. 2.4): avisan junto al campo antes de enviar. El servidor
// sigue decidiendo; aquí solo se repite lo que él valida.

/** Correo válido: el mismo patrón del servidor (reglas.Correos.VALIDO). */
export const CORREO = /^[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;

/** Como reglas.Correos.valido: máximo 150 caracteres y el patrón, sin contar los espacios de los extremos. */
export function correoValido(correo: string): boolean {
  const t = correo.trim();
  return t.length <= 150 && CORREO.test(t);
}

/** Un correo: `vacio` si no se escribió (null si es opcional), `invalido` si no tiene el formato del servidor; null si está bien. */
export function errorCorreo(correo: string, invalido: string, vacio: string | null = null): string | null {
  if (!correo.trim()) return vacio;
  return correoValido(correo) ? null : invalido;
}

/** Correos separados por coma: cada uno con formato válido y como máximo `maximo` distintos. null si están bien (o vacíos). */
export function errorCorreos(texto: string, maximo = 10): string | null {
  const distintos = new Set<string>();
  for (const c of separarCorreos(texto)) {
    if (!correoValido(c)) return `El correo «${c.slice(0, 60)}» no es válido.`;
    distintos.add(c.toLowerCase());
  }
  return distintos.size > maximo ? `Máximo ${maximo} correos.` : null;
}

/** Código de una fuerza nueva: de 2 a 5 letras mayúsculas (ServicioCatalogos). */
export function errorCodigoFuerza(codigo: string): string | null {
  if (!codigo.trim()) return 'Escriba el código.';
  return /^[A-Z]{2,5}$/.test(codigo.trim().toUpperCase()) ? null : 'El código de la fuerza debe tener de 2 a 5 letras mayúsculas (p. ej. EJC).';
}

/** Texto obligatorio de `min` a `max` caracteres (sin contar los espacios de los extremos). */
export function errorLargo(texto: string, min: number, max: number, vacio: string, largo: string): string | null {
  const t = texto.trim();
  if (!t) return vacio;
  return t.length < min || t.length > max ? largo : null;
}

/**
 * Código de un valor de catálogo con el `patron` del catálogo, como lo valida el servidor: en mayúsculas, máximo 10 caracteres y el
 * patrón debe cubrir todo el código. Si el navegador no puede leer el patrón (sintaxis solo de Java), decide el servidor.
 */
export function errorCodigoValor(catalogo: { codigo: string; nombre: string; patron: string | null }, entrada: string): string | null {
  const codigo = entrada.trim().toUpperCase();
  if (!codigo) return 'Escriba el código.';
  let patron: RegExp | null;
  try {
    patron = new RegExp(`^(?:${catalogo.patron ?? '[A-Z0-9]{1,10}'})$`);
  } catch {
    patron = null;
  }
  if (codigo.length <= 10 && (patron === null || patron.test(codigo))) return null;
  const ejemplo =
    catalogo.codigo === 'tipo_documento'
      ? ' (de 1 a 3 letras mayúsculas, p. ej. C).'
      : catalogo.codigo === 'tipo_factura'
        ? ' (de 1 a 3 dígitos, p. ej. 1).'
        : '.';
  return `El código «${codigo.slice(0, 12)}» no tiene el formato de ${catalogo.nombre}${ejemplo}`;
}

/** NIT del operador logístico como lo exige el servidor: de 6 a 12 dígitos y, si acaso, el dígito de verificación. Vacío es válido. */
export function errorNitOpl(nit: string): string | null {
  const t = nit.trim();
  if (t === '' || /^[0-9]{6,12}(-[0-9])?$/.test(t)) return null;
  return 'El NIT debe tener de 6 a 12 dígitos y, si acaso, el dígito de verificación (900123456-7).';
}

/** Código de una obligación como lo exige el servidor: OB- y de 1 a 20 letras, dígitos o guiones. */
export function errorCodigoObligacion(codigo: string): string | null {
  const t = codigo.trim().toUpperCase();
  if (t === '' || t === 'OB-') return 'Escriba el código (p. ej. OB-OPL-01).';
  return /^OB-[A-Z0-9-]{1,20}$/.test(t) ? null : 'El código de la obligación empieza por OB- (p. ej. OB-OPL-01).';
}

/** Días hacia atrás que acepta el servidor para la fecha del escrito (ServicioSolicitudes.DIAS_ATRAS). */
export const DIAS_ATRAS_SOLICITUD = 60;

/** Errores del formulario de nueva solicitud, con las mismas reglas del servidor (ServicioSolicitudes.registrar). */
export function erroresSolicitud(punto: string | null, fecha: string, motivo: string, hoy: string): Record<string, string | null> {
  const largo = [...motivo.trim()].length; // caracteres como los cuenta el servidor
  let errorFecha: string | null = null;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(fecha) || fecha > hoy) errorFecha = 'La fecha de la solicitud es obligatoria y no puede ser futura.';
  else if (fecha < sumarDias(hoy, -DIAS_ATRAS_SOLICITUD)) errorFecha = `La fecha de la solicitud no puede tener más de ${DIAS_ATRAS_SOLICITUD} días.`;
  return {
    punto: punto ? null : 'Elija el punto.',
    fecha: errorFecha,
    motivo: largo < 10 || largo > 4000 ? 'Describa el motivo de la solicitud (de 10 a 4000 caracteres).' : null,
  };
}

/**
 * Campo de la fila de la propuesta al que se refiere el rechazo de un ajuste: el servidor lo dice en `campo` (nombre en el cuerpo de
 * la petición; decisión UI-21) y aquí se traduce al de la fila. null si el rechazo no es de un campo.
 */
export function campoDelAjuste(campo: unknown): 'fecha' | 'lider' | 'otros' | null {
  return ({ auditorId: 'lider', otros: 'otros', fecha: 'fecha' } as const)[String(campo) as 'auditorId' | 'otros' | 'fecha'] ?? null;
}

const NI_N_NI_TILDES = /[ñÑáéíóúÁÉÍÓÚüÜ]/;

/**
 * Revisión en el cliente de una contraseña nueva: solo lo que dicen los textos de ayuda (mínimo 12 caracteres, sin ñ ni tildes), con los
 * mismos mensajes del servidor (reglas/Claves.java). El servidor sigue decidiendo el resto (mayúsculas, número, carácter especial…).
 */
export function errorClave(clave: string): string | null {
  if (NI_N_NI_TILDES.test(clave)) return 'La contraseña no puede tener ñ ni tildes.';
  if ([...clave].length < 12) return 'La contraseña debe tener mínimo 12 caracteres.';
  return null;
}

/** La contraseña nueva y su confirmación (vacía: «Confirme…»; distinta: «no coinciden»). */
export function erroresClaveNueva(clave: string, confirmacion: string): { clave: string | null; confirmacion: string | null } {
  let errorConfirmacion: string | null = null;
  if (!confirmacion) errorConfirmacion = 'Confirme la contraseña nueva.';
  else if (confirmacion !== clave) errorConfirmacion = 'Las contraseñas no coinciden.';
  return { clave: errorClave(clave), confirmacion: errorConfirmacion };
}

/** Motivo o descripción de 10 a 800 caracteres (la misma regla del servidor): mensaje o null si cumple. */
export function errorTexto10a800(texto: string, nombre: string): string | null {
  const n = texto.trim().length;
  if (n === 0) return `Escriba ${nombre}.`;
  if (n < 10) return `${nombre.charAt(0).toUpperCase()}${nombre.slice(1)} debe tener entre 10 y 800 caracteres (lleva ${n}).`;
  return null;
}

/**
 * Error para un control que solo se colorea con `error={true}` (Switch, Checkbox, grupos): el campo que marca el servidor lleva un texto
 * que remite al aviso, para que el error no dependa solo del color.
 */
export function conTexto(error: string | true | undefined): string | undefined {
  return error === true ? 'Revise este campo: el motivo está en el aviso de arriba.' : error;
}
