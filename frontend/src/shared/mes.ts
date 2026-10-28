/** Cálculo puro de la cuadrícula de un mes (semanas de lunes a domingo) y del rango de consulta. Mes de 0 a 11, como Date. */

export interface DiaCuadricula {
  iso: string;
  dia: number;
  delMes: boolean;
}

export const DIAS_SEMANA = ['Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb', 'Dom'];

const MESES = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre'];

const dos = (n: number) => String(n).padStart(2, '0');

export function fechaIso(d: Date): string {
  return `${d.getFullYear()}-${dos(d.getMonth() + 1)}-${dos(d.getDate())}`;
}

export function diasDelMes(anio: number, mes: number): number {
  return new Date(anio, mes + 1, 0).getDate();
}

/** Semanas completas que cubren el mes; la primera empieza el lunes anterior (o igual) al día 1. */
export function semanasDelMes(anio: number, mes: number): DiaCuadricula[][] {
  const desfase = (new Date(anio, mes, 1).getDay() + 6) % 7; // lunes = 0
  const total = Math.ceil((desfase + diasDelMes(anio, mes)) / 7) * 7;
  const semanas: DiaCuadricula[][] = [];
  for (let i = 0; i < total; i++) {
    const d = new Date(anio, mes, 1 - desfase + i);
    if (i % 7 === 0) semanas.push([]);
    semanas[semanas.length - 1]!.push({ iso: fechaIso(d), dia: d.getDate(), delMes: d.getMonth() === mes });
  }
  return semanas;
}

/** Primer y último día del mes en YYYY-MM-DD (rango de la consulta). */
export function rangoMes(anio: number, mes: number): { desde: string; hasta: string } {
  return { desde: fechaIso(new Date(anio, mes, 1)), hasta: fechaIso(new Date(anio, mes, diasDelMes(anio, mes))) };
}

export function tituloMes(anio: number, mes: number): string {
  return `${MESES[mes]} de ${anio}`;
}

/** Año y mes (0 a 11) de una fecha «aaaa-mm-dd», p. ej. el mes inicial a partir de hoyBogota(). */
export function mesDeIso(iso: string): { anio: number; mes: number } {
  return { anio: Number(iso.slice(0, 4)), mes: Number(iso.slice(5, 7)) - 1 };
}

/** Mueve el mes visible n meses (negativo hacia atrás). */
export function moverMes(anio: number, mes: number, n: number): { anio: number; mes: number } {
  const d = new Date(anio, mes + n, 1);
  return { anio: d.getFullYear(), mes: d.getMonth() };
}
