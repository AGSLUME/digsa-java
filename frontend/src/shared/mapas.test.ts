import { describe, expect, it } from 'vitest';
import {
  claseCoropleta,
  colorCoropleta,
  colorEstado,
  cortesCoropleta,
  escala,
  ESTADOS_COBERTURA,
  ESTADOS_VISITA,
  fondoDepartamento,
  limitar,
  MAPA_BASE,
  nombreCapital,
  nombreEnMapa,
  porcentajeCobertura,
  proyectar,
  textoConteos,
  textoSinUbicacion,
  ubicar,
  VISTA_INICIAL,
  vistaCaja,
  zoomEn,
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
  const base = {
    municipio: 'MEDELLÍN',
    departamento: '05',
    estado: 'programada',
    ...MEDELLIN,
  };

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
  it('color semántico LUME por estado, gris si no se conoce', () => {
    expect(colorEstado(ESTADOS_VISITA, 'ejecutada')).toBe('var(--lume-success-text, #15803D)');
    expect(colorEstado(ESTADOS_VISITA, 'programada')).toBe('var(--lume-info-text, #1D4ED8)');
    expect(colorEstado(ESTADOS_VISITA, 'ejecucion')).toBe('var(--lume-warning-text, #92400E)');
    expect(colorEstado(ESTADOS_COBERTURA, 'nunca')).toBe('var(--lume-danger, #B91C1C)');
    expect(colorEstado(ESTADOS_VISITA, 'otro')).toBe('var(--lume-neutral-text, #475569)');
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
    expect(
      porcentajeCobertura({
        visitado: 1,
        programado: 1,
        sin_visita: 0,
        nunca: 1,
      }),
    ).toBe(33);
    expect(
      porcentajeCobertura({
        visitado: 0,
        programado: 0,
        sin_visita: 0,
        nunca: 0,
      }),
    ).toBeNull();
    expect(porcentajeCobertura(undefined)).toBeNull();
  });
});

describe('mapa político', () => {
  const porCodigo = Object.fromEntries(MAPA_BASE.departamentos.map((d) => [d.codigo, d]));

  it('dos departamentos vecinos nunca comparten color y solo se usan los 5 fondos LUME', () => {
    for (const d of MAPA_BASE.departamentos) {
      expect(d.color).toBeGreaterThanOrEqual(0);
      expect(d.color).toBeLessThan(5);
      for (const v of d.vecinos) {
        expect(porCodigo[v]!.vecinos, `${v} vecino de ${d.codigo}`).toContain(d.codigo);
        expect(porCodigo[v]!.color, `${d.codigo} y ${v}`).not.toBe(d.color);
      }
    }
    // Todos menos San Andrés tienen vecinos; Antioquia limita con Chocó, Córdoba, Bolívar, Santander, Boyacá y Caldas, entre otros
    expect(MAPA_BASE.departamentos.filter((d) => d.vecinos.length === 0).map((d) => d.codigo)).toEqual(['88']);
    expect(porCodigo['05']!.vecinos).toEqual(expect.arrayContaining(['27', '23', '13', '68', '15', '17', '66']));
    expect(new Set([0, 1, 2, 3, 4].map(fondoDepartamento)).size).toBe(5);
    expect(fondoDepartamento(0)).toBe('var(--lume-success-bg, #DCEFE1)');
    expect(fondoDepartamento(4)).toBe('var(--lume-neutral-bg, #E2E8F0)');
  });

  it('nombre en mayúsculas dentro de la caja del departamento; los pequeños con línea guía', () => {
    for (const d of MAPA_BASE.departamentos) {
      expect(d.renglones.join(' ')).toBe(d.renglones.join(' ').toUpperCase());
      if (d.codigo === '88') continue; // la etiqueta del recuadro va debajo del marco
      const [x0 = 0, y0 = 0, x1 = 0, y1 = 0] = d.caja;
      expect(d.etiqueta[0], d.codigo).toBeGreaterThanOrEqual(x0);
      expect(d.etiqueta[0], d.codigo).toBeLessThanOrEqual(x1);
      expect(d.etiqueta[1], d.codigo).toBeGreaterThanOrEqual(y0);
      expect(d.etiqueta[1], d.codigo).toBeLessThanOrEqual(y1);
    }
    expect(['08', '11', '63', '66'].every((c) => porCodigo[c]!.guia)).toBe(true);
    expect(porCodigo['54']!.renglones).toEqual(['NORTE DE', 'SANTANDER']);
  });

  it('países vecinos con nombre, fuentes citadas y el JSON por debajo de 200 KB', () => {
    expect(
      MAPA_BASE.paises
        .filter((p) => p.nombre)
        .map((p) => p.nombre)
        .sort(),
    ).toEqual(['Brasil', 'Ecuador', 'Panamá', 'Perú', 'Venezuela']);
    expect(MAPA_BASE.fuente).toContain('DANE');
    expect(MAPA_BASE.fuente).toContain('Natural Earth');
    expect(JSON.stringify(MAPA_BASE).length).toBeLessThan(200_000);
  });

  it('las capitales del interior caen dentro de su departamento (la simplificación no las deja fuera)', () => {
    // Punto dentro de un trazo «Mx yLx y…Z» (regla par-impar sobre todos los anillos)
    const dentro = (d: string, [px, py]: [number, number]) =>
      d
        .split('Z')
        .filter(Boolean)
        .map((anillo) =>
          anillo
            .slice(1)
            .split('L')
            .map((p) => p.split(' ').map(Number) as [number, number]),
        )
        .reduce(
          (n, a) =>
            n +
            a.filter(([x, y], i) => {
              const [x2, y2] = a[(i + 1) % a.length]!;
              return y > py !== y2 > py && px < ((x2 - x) * (py - y)) / (y2 - y) + x;
            }).length,
          0,
        ) %
        2 ===
      1;
    const capitales: [string, number, number][] = [
      ['66', 4.804985, -75.719711], // Pereira
      ['63', 4.53598, -75.680786], // Armenia
      ['17', 5.057657, -75.491025], // Manizales
      ['05', 6.246631, -75.581775], // Medellín
      ['11', 4.649251, -74.106992], // Bogotá
      ['76', 3.413686, -76.52133], // Cali
      ['68', 7.11647, -73.132562], // Bucaramanga
    ];
    for (const [codigo, lat, lon] of capitales) expect(dentro(porCodigo[codigo]!.d, proyectar(lat, lon)), codigo).toBe(true);
  });

  it('nombre de la capital en tipo oración, con «de» y «del» en minúscula; corto si el oficial es largo', () => {
    expect(nombreEnMapa({ codigo: '76001', nombre: 'SANTIAGO DE CALI' })).toBe('Cali');
    expect(nombreEnMapa({ codigo: '05001', nombre: 'MEDELLÍN' })).toBe('Medellín');
    expect(nombreCapital('SAN JOSÉ DEL GUAVIARE')).toBe('San José del Guaviare');
    expect(nombreCapital('CARTAGENA DE INDIAS')).toBe('Cartagena de Indias');
    expect(nombreCapital('BOGOTÁ, D.C.')).toBe('Bogotá, D.C.');
  });
});

describe('coropleta', () => {
  it('cortes iguales hasta el máximo, sin repetir', () => {
    expect(cortesCoropleta(0)).toEqual([]);
    expect(cortesCoropleta(3)).toEqual([1, 2, 3]);
    expect(cortesCoropleta(100)).toEqual([20, 40, 60, 80, 100]);
    expect(cortesCoropleta(7)).toEqual([2, 3, 5, 6, 7]);
  });

  it('clase 0 sin dato; 1 a 5 según el corte', () => {
    const c = [20, 40, 60, 80, 100];
    expect(claseCoropleta(undefined, c)).toBe(0);
    expect(claseCoropleta(0, c)).toBe(1);
    expect(claseCoropleta(20, c)).toBe(1);
    expect(claseCoropleta(21, c)).toBe(2);
    expect(claseCoropleta(100, c)).toBe(5);
  });

  it('colores: el fondo neutro sin dato y mezclas crecientes del color base', () => {
    const base = 'var(--lume-info-text, #1D4ED8)';
    expect(colorCoropleta(base, 0)).toBe('var(--lume-neutral-bg, #E2E8F0)');
    expect(colorCoropleta(base, 1)).toBe('color-mix(in srgb, var(--lume-info-text, #1D4ED8) 14%, white)');
    expect(colorCoropleta(base, 5)).toBe('color-mix(in srgb, var(--lume-info-text, #1D4ED8) 70%, white)');
    // Con pocas clases la última sigue siendo la más intensa
    expect(colorCoropleta(base, 1, 1)).toBe('color-mix(in srgb, var(--lume-info-text, #1D4ED8) 70%, white)');
    expect(colorCoropleta(base, 1, 3)).toBe('color-mix(in srgb, var(--lume-info-text, #1D4ED8) 14%, white)');
    expect(colorCoropleta(base, 2, 3)).toBe('color-mix(in srgb, var(--lume-info-text, #1D4ED8) 42%, white)');
    expect(colorCoropleta(base, 3, 3)).toBe('color-mix(in srgb, var(--lume-info-text, #1D4ED8) 70%, white)');
  });
});

describe('escala y zoom', () => {
  it('barra de escala en km redondos que cabe en 90 unidades', () => {
    const e1 = escala(1);
    expect(e1.km).toBe(200);
    expect(e1.largo).toBeCloseTo((200 * MAPA_BASE.principal.k) / 111.32, 1);
    expect(escala(4).km).toBe(50);
    expect(escala(12).largo).toBeLessThanOrEqual(90);
  });

  it('el zoom deja quieto el punto señalado y no sale del mapa', () => {
    const v = zoomEn(VISTA_INICIAL, 2, 200, 300);
    expect(v.k).toBe(2);
    expect((200 - v.x) / v.k).toBeCloseTo(200);
    expect((300 - v.y) / v.k).toBeCloseTo(300);
    expect(zoomEn(VISTA_INICIAL, 0.5, 10, 10)).toEqual(VISTA_INICIAL);
    expect(zoomEn({ k: 10, x: 0, y: 0 }, 5, 0, 0).k).toBe(12);
    expect(limitar({ k: 1, x: 10, y: -5 })).toEqual(VISTA_INICIAL);
    const lejos = limitar({ k: 2, x: -5000, y: 50 });
    expect(lejos.x).toBe(-MAPA_BASE.ancho);
    expect(lejos.y).toBe(0);
  });

  it('acercar a un departamento lo centra y lo agranda', () => {
    const meta = MAPA_BASE.departamentos.find((d) => d.codigo === '50')!;
    const v = vistaCaja(meta.caja);
    expect(v.k).toBeGreaterThan(1.5);
    const [x0 = 0, y0 = 0, x1 = 0, y1 = 0] = meta.caja;
    expect(((x0 + x1) / 2) * v.k + v.x).toBeCloseTo(MAPA_BASE.ancho / 2, 0);
    expect(((y0 + y1) / 2) * v.k + v.y).toBeCloseTo(MAPA_BASE.alto / 2, 0);
  });
});
