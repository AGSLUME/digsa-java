import { memo } from 'react';
import { MAPA_BASE } from './mapas';

export interface Marca {
  clave: string | number;
  departamento: string;
  x: number;
  y: number;
  color: string;
  titulo: string;
}

// Hover y foco por CSS: sin estado de React, mover el ratón no vuelve a pintar las marcas
const ESTILO = `
.mapa-dpto { fill: var(--mantine-color-default); stroke: var(--mantine-color-gray-6); stroke-width: 0.6; stroke-linejoin: round; outline: none; }
.mapa-dpto[data-con-datos="true"] { cursor: pointer; }
.mapa-dpto[data-con-datos="true"]:hover { fill: var(--mantine-color-blue-light); }
.mapa-dpto:focus-visible { stroke: var(--mantine-color-blue-6); stroke-width: 2; }
.mapa-dpto[aria-pressed="true"] { fill: var(--mantine-color-blue-light-hover); stroke: var(--mantine-color-blue-7); stroke-width: 1.4; }
`;

/**
 * Mapa político de Colombia por departamentos (SVG, sin servicios externos) con una marca por elemento. Cada departamento muestra
 * su nombre y conteos al pasar el cursor; los que tienen datos se eligen con clic o teclado. San Andrés y Providencia van en un
 * recuadro.
 */
export function MapaColombia({
  marcas,
  departamentos,
  seleccionado,
  onSeleccionar,
  etiqueta,
}: {
  marcas: Marca[];
  /** código DANE → texto del departamento (nombre y conteos); solo los que tienen datos */
  departamentos: Record<string, string>;
  seleccionado: string | null;
  onSeleccionar: (codigo: string | null) => void;
  etiqueta: string;
}) {
  const b = MAPA_BASE;
  const [mx, my, mw, mh] = b.recuadro.marco as [number, number, number, number];
  const elegir = (codigo: string) => {
    if (departamentos[codigo]) onSeleccionar(seleccionado === codigo ? null : codigo);
  };
  return (
    <svg
      viewBox={`0 0 ${b.ancho} ${b.alto}`}
      role="group"
      aria-label={etiqueta}
      data-testid="mapa-colombia"
      style={{ width: '100%', maxWidth: 560, height: 'auto', display: 'block' }}
    >
      <style>{ESTILO}</style>
      <rect x={mx} y={my} width={mw} height={mh} fill="none" stroke="var(--mantine-color-gray-5)" strokeDasharray="3 2" />
      {b.departamentos.map((d) => {
        const texto = departamentos[d.codigo];
        return (
          <path
            key={d.codigo}
            d={d.d}
            className="mapa-dpto"
            data-departamento={d.codigo}
            data-con-datos={texto ? 'true' : 'false'}
            role={texto ? 'button' : undefined}
            tabIndex={texto ? 0 : undefined}
            aria-label={texto}
            aria-pressed={texto ? seleccionado === d.codigo : undefined}
            onClick={() => elegir(d.codigo)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                elegir(d.codigo);
              }
            }}
          >
            <title>{texto ?? 'Sin datos en el filtro'}</title>
          </path>
        );
      })}
      <Marcas marcas={marcas} elegir={elegir} />
    </svg>
  );
}

/** Las marcas solo se vuelven a pintar si cambian (o cambia la selección, que cambia `elegir`). */
const Marcas = memo(function Marcas({ marcas, elegir }: { marcas: Marca[]; elegir: (codigo: string) => void }) {
  return (
    <g data-testid="marcas-mapa" aria-hidden="true">
      {marcas.map((m) => (
        <circle
          key={m.clave}
          cx={m.x}
          cy={m.y}
          r={2.6}
          fill={m.color}
          stroke="var(--mantine-color-text)"
          strokeWidth={0.4}
          data-marca={m.clave}
          style={{ cursor: 'pointer' }}
          onClick={() => elegir(m.departamento)}
        >
          <title>{m.titulo}</title>
        </circle>
      ))}
    </g>
  );
});
