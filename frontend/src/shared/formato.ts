/**
 * Formato único de cifras, fechas y horas de la interfaz (Anexo 5 num. 2.4):
 * - cifras con 2 decimales, redondeadas a 2 decimales, SIN separador de miles y con coma decimal;
 * - hora oficial colombiana (America/Bogota) en horario militar (24 h);
 * - fechas en pantalla como dd/mm/aaaa (decisión UI-3); los archivos exportados siguen en ISO.
 * No depende de la configuración regional del equipo: los formatos numéricos del navegador ponen separador de miles y están prohibidos
 * (lo vigila formato.test.ts).
 */

const ZONA = 'America/Bogota';

/**
 * Decisión UI-2 (pendiente del usuario): los conteos (visitas, hallazgos, registros) van como enteros. Si se decide que también
 * lleven 2 decimales, basta poner true aquí.
 */
export const CONTEOS_CON_DECIMALES = false;

const SIN_DATO = '—';

/** Número desde un número o un texto con punto o coma decimal (sin separador de miles). null si no es un número. */
export function aNumero(v: number | string | null | undefined): number | null {
  if (v === null || v === undefined) return null;
  if (typeof v === 'number') return Number.isFinite(v) ? v : null;
  const t = v.trim().replace(',', '.');
  if (t === '' || !/^-?\d+(\.\d+)?$/.test(t)) return null;
  return Number(t);
}

/** Redondeo a 2 decimales, mitad hacia afuera de cero y sin el error binario de 1.005 * 100. */
export function redondear2(n: number): number {
  const signo = n < 0 ? -1 : 1;
  const a = Math.abs(n);
  // Restos de punto flotante (0,1 + 0,2 - 0,3 = 5,55e-17) son cero; «1e21» y similares ya no tienen decimales
  if (a < 0.005) return 0;
  if (String(a).includes('e')) return signo * a;
  return (signo * Math.round(Number(`${a}e2`))) / 100;
}

/** Cifra: «1450000,50», «72,00», «-3,10». Sin dato, «—». */
export function cifra(v: number | string | null | undefined): string {
  const n = aNumero(v);
  if (n === null) return SIN_DATO;
  const r = redondear2(n);
  const centavos = Math.round(Math.abs(r) * 100);
  const texto = `${Math.floor(centavos / 100)},${String(centavos % 100).padStart(2, '0')}`;
  return r < 0 && centavos > 0 ? '-' + texto : texto;
}

/** Porcentaje con 2 decimales: «75,00 %». */
export function porcentaje(v: number | string | null | undefined): string {
  const c = cifra(v);
  return c === SIN_DATO ? c : `${c} %`;
}

/** Monto en pesos: «$ 1450000,00». */
export function monto(v: number | string | null | undefined): string {
  const c = cifra(v);
  return c === SIN_DATO ? c : `$ ${c}`;
}

/** Conteo: entero sin separador de miles («1203»), o con 2 decimales si así se decide (UI-2). */
export function conteo(v: number | string | null | undefined): string {
  if (CONTEOS_CON_DECIMALES) return cifra(v);
  const n = aNumero(v);
  return n === null ? SIN_DATO : String(Math.round(n));
}

function partesBogota(d: Date): Record<string, string> {
  const partes = new Intl.DateTimeFormat('en-CA', {
    timeZone: ZONA,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(d);
  return Object.fromEntries(partes.map((p) => [p.type, p.value]));
}

/** Fecha de hoy en Bogotá como «aaaa-mm-dd» (para valores de campos y filtros), sin depender de la zona del equipo. */
export function hoyBogota(ahora: Date = new Date()): string {
  const p = partesBogota(ahora);
  return `${p.year}-${p.month}-${p.day}`;
}

/** «aaaa-mm-dd» desplazada n días (calendario, sin horas). */
export function sumarDias(iso: string, n: number): string {
  const [a, m, d] = iso.split('-').map(Number);
  const f = new Date(Date.UTC(a!, m! - 1, d! + n));
  return f.toISOString().slice(0, 10);
}

/** Fecha para pantalla: «2026-10-23» (o el inicio de un texto ISO) → «23/10/2026». Lo que no es fecha se devuelve igual. */
export function fecha(v: string | null | undefined): string {
  if (!v) return SIN_DATO;
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(v.trim());
  return m ? `${m[3]}/${m[2]}/${m[1]}` : v;
}

/**
 * Fecha y hora para pantalla, en hora militar de Bogotá: «23/10/2026 14:05» (con segundos si el valor los trae y `segundos`).
 * Acepta un instante ISO con zona o un número (milisegundos), que se convierten a Bogotá, o un texto «aaaa-mm-dd[T ]HH:mm[:ss]» sin
 * zona, que ya viene en hora de Bogotá.
 */
export function fechaHora(v: string | number | null | undefined, { segundos = false }: { segundos?: boolean } = {}): string {
  if (v === null || v === undefined || v === '') return SIN_DATO;
  const conZona = typeof v === 'number' || /([zZ]|[+-]\d{2}:?\d{2})$/.test(v.trim());
  if (conZona) {
    const d = new Date(v);
    if (Number.isNaN(d.getTime())) return SIN_DATO;
    const p = partesBogota(d);
    return `${p.day}/${p.month}/${p.year} ${p.hour}:${p.minute}${segundos ? ':' + p.second : ''}`;
  }
  const m = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?)?/.exec(v.trim());
  if (!m) return v;
  const base = `${m[3]}/${m[2]}/${m[1]}`;
  if (!m[4]) return base;
  return `${base} ${m[4]}:${m[5]}${segundos && m[6] ? ':' + m[6] : ''}`;
}

/** Hora militar «HH:mm» de un texto «HH:mm[:ss]» o de un instante (un instante con zona se convierte a Bogotá). */
export function hora(v: string | null | undefined): string {
  if (!v) return SIN_DATO;
  if (/^\d{4}-\d{2}-\d{2}[T ].*([zZ]|[+-]\d{2}:?\d{2})$/.test(v.trim())) {
    const d = new Date(v);
    if (!Number.isNaN(d.getTime())) {
      const p = partesBogota(d);
      return `${p.hour}:${p.minute}`;
    }
  }
  const m = /(?:^|[T ])(\d{2}):(\d{2})/.exec(v.trim()) ?? /^(\d{2}):(\d{2})/.exec(v.trim());
  return m ? `${m[1]}:${m[2]}` : v;
}
