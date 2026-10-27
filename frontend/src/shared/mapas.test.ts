import { describe, expect, it } from 'vitest';
import {
  colorEstado,
  ESTADOS_COBERTURA,
  ESTADOS_VISITA,
  MAPA_BASE,
  porcentajeCobertura,
  proyectar,
  textoConteos,
  textoSinUbicacion,
  ubicar,
} from './mapas';

const MEDELLIN = { latitud: 6.246631, longitud: -75.581775 };
const SAN_ANDRES = { latitud: 12.5847, longitud: -81.7006 };

describe('mapa base', () => {
  it('trae los 33 departamentos con código DANE de 2 dígitos', () => {
    expect(MAPA_BASE.departamentos).toHaveLength(33);
    expect(MAPA_BASE.departamentos.every((d) => /^\d{2}$/.test(d.codigo) && d.d.startsWith('M'))).toBe(true);
  });
});

describe('proyectar', () => {
  it('ubica el continente dentro del lienzo y San Andrés en el recuadro', () => {
    const [x, y] = proyectar(MEDELLIN.latitud, MEDELLIN.longitud);
    expect(x).toBeGreaterThan(0);
    expect(x).toBeLessThan(MAPA_BASE.ancho);
    expect(y).toBeGreaterThan(0);
    expect(y).toBeLessThan(MAPA_BASE.alto);
    const [sx, sy] = proyectar(SAN_ANDRES.latitud, SAN_ANDRES.longitud);
    const [mx, my, mw, mh] = MAPA_BASE.recuadro.marco as [number, number, number, number];
    expect(sx).toBeGreaterThan(mx);
    expect(sx).toBeLessThan(mx + mw);
    expect(sy).toBeGreaterThan(my);
    expect(sy).toBeLessThan(my + mh);
  });

  it('el norte queda arriba y el oriente a la derecha', () => {
    const [x1, y1] = proyectar(4, -75);
    const [x2, y2] = proyectar(5, -74);
    expect(y2).toBeLessThan(y1);
    expect(x2).toBeGreaterThan(x1);
  });
});

describe('ubicar', () => {
  const base = { municipio: 'MEDELLÍN', departamento: '05', estado: 'programada', ...MEDELLIN };

  it('la primera marca de un municipio va en su punto y las siguientes se reparten sin encimarse', () => {
    const [a, b, c] = ubicar([
      { ...base, id: 1 },
      { ...base, id: 2 },
      { ...base, id: 3 },
    ]);
    const [x, y] = proyectar(MEDELLIN.latitud, MEDELLIN.longitud);
    expect(a!.x).toBeCloseTo(x);
    expect(a!.y).toBeCloseTo(y);
    const distancia = (p: { x: number; y: number }, q: { x: number; y: number }) => Math.hypot(p.x - q.x, p.y - q.y);
    expect(distancia(a!, b!)).toBeGreaterThan(2);
    expect(distancia(a!, c!)).toBeGreaterThan(2);
    expect(distancia(b!, c!)).toBeGreaterThan(2);
  });

  it('municipios distintos no se desplazan entre sí', () => {
    const [a, b] = ubicar([
      { ...base, id: 1 },
      { ...base, id: 2, latitud: 4.649251, longitud: -74.106992 },
    ]);
    expect(b!.x).toBeCloseTo(proyectar(4.649251, -74.106992)[0]);
    expect(a!.x).toBeCloseTo(proyectar(MEDELLIN.latitud, MEDELLIN.longitud)[0]);
  });
});

describe('textos y colores', () => {
  it('color por estado, gris si no se conoce', () => {
    expect(colorEstado(ESTADOS_VISITA, 'ejecutada')).toBe('var(--mantine-color-green-6)');
    expect(colorEstado(ESTADOS_COBERTURA, 'nunca')).toBe('var(--mantine-color-red-6)');
    expect(colorEstado(ESTADOS_VISITA, 'otro')).toBe('var(--mantine-color-gray-6)');
  });

  it('conteos por departamento, en 0 lo que falta', () => {
    expect(textoConteos(ESTADOS_VISITA, { programada: 3, ejecutada: 1 })).toBe('Programada 3 · En ejecución 0 · Ejecutada 1');
    expect(textoConteos(ESTADOS_VISITA, undefined)).toBe('Programada 0 · En ejecución 0 · Ejecutada 0');
  });

  it('aviso de lo que no se ubica', () => {
    expect(textoSinUbicacion(0, 'visitas')).toBeNull();
    expect(textoSinUbicacion(1, 'visitas')).toBe('1 visita sin ubicación no aparece en el mapa.');
    expect(textoSinUbicacion(4, 'puntos')).toBe('4 puntos sin ubicación no aparecen en el mapa.');
  });

  it('porcentaje de cobertura', () => {
    expect(porcentajeCobertura({ visitado: 1, programado: 1, sin_visita: 0, nunca: 1 })).toBe(33);
    expect(porcentajeCobertura({ visitado: 0, programado: 0, sin_visita: 0, nunca: 0 })).toBeNull();
    expect(porcentajeCobertura(undefined)).toBeNull();
  });
});
