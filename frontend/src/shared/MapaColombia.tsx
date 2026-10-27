import { ActionIcon, Paper, Stack, Text } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconMinus, IconPlus, IconZoomReset } from '@tabler/icons-react';
import {
  memo,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type FocusEvent,
  type KeyboardEvent,
  type MouseEvent,
  type PointerEvent as PointerReact,
  type RefObject,
} from 'react';
import {
  escala,
  fondoDepartamento,
  limitar,
  MAPA_BASE,
  nombreEnMapa,
  proyectar,
  VISTA_INICIAL,
  vistaCaja,
  zoomEn,
  type Capital,
  type VistaMapa,
} from './mapas';

export interface Marca {
  clave: string | number;
  departamento: string;
  x: number;
  y: number;
  color: string;
  titulo: string;
}

const b = MAPA_BASE;
// El mapa político se ve igual en tema claro y oscuro, como un mapa impreso
const MAR = '#EAF3FB';
const TIERRA = '#F1F3F5';
const TEXTO = '#334155';
const TEXTO_SUAVE = '#94A3B8';
const OSCURO = '#0F172A';
/** Con este aumento o más, los departamentos pequeños muestran el nombre en su sitio, sin línea guía. */
const ZOOM_SIN_GUIA = 3;
/** Píxeles de pantalla que debe moverse un puntero para que cuente como arrastre y no como clic. */
const UMBRAL_RATON = 4;
const UMBRAL_TACTIL = 10;
/** Borde nacional: todos los departamentos en un solo trazo, pintado debajo con un borde grueso. */
const CONTORNO = b.departamentos.map((d) => d.d).join('');
/** Capitales con el nombre a la izquierda del punto, para no tapar el nombre del departamento vecino. */
const CAPITAL_A_LA_IZQUIERDA = new Set(['23001', '47001', '17001', '85001']);
const PRIMERO = b.departamentos[0]?.codigo ?? '';

// Hover por CSS: sin estado de React, mover el ratón no vuelve a pintar el mapa. Los bordes no engordan con el zoom; las marcas
// toman el radio de la variable --r, así que el zoom no vuelve a renderizar miles de círculos.
const ESTILO = `
.mapa-dpto { stroke: #fff; stroke-width: 0.8; stroke-linejoin: round; vector-effect: non-scaling-stroke; outline: none; cursor: pointer; }
.mapa-dpto:hover { stroke: var(--lume-neutral-text, #475569); stroke-width: 1.2; }
.mapa-contorno { fill: none; stroke: #64748B; stroke-width: 1.6; stroke-linejoin: round; vector-effect: non-scaling-stroke; }
.mapa-pais { fill: ${TIERRA}; stroke: #CBD5E1; stroke-width: 0.6; vector-effect: non-scaling-stroke; }
.mapa-marca { r: var(--r); stroke: #fff; stroke-width: calc(var(--r) * 0.3); cursor: pointer; }
.mapa-texto { paint-order: stroke; stroke: #fff; stroke-linejoin: round; }
.mapa-anillo { fill: none; pointer-events: none; stroke-linejoin: round; vector-effect: non-scaling-stroke; }
.mapa-capa { transform-origin: 0 0; }
.mapa-capa[data-animar="true"] { transition: transform 300ms ease; }
@media (prefers-reduced-motion: reduce) { .mapa-capa[data-animar="true"] { transition: none; } }
`;

/** Píxeles de pantalla por unidad del lienzo (el SVG se adapta al ancho de la tarjeta). */
function usePixelesPorUnidad(ref: RefObject<SVGSVGElement>): number {
  const [ppu, setPpu] = useState(1);
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(([e]) => {
      const w = e?.contentRect.width ?? 0;
      if (w > 0) setPpu(Math.round((w / b.ancho) * 100) / 100);
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, [ref]);
  return ppu;
}

/**
 * Mapa político de Colombia (SVG local, sin servicios externos ni teselas): departamentos en pastel (o en coropleta), países
 * vecinos, nombres, capitales, recuadro de San Andrés, escala y norte; encima, una marca por elemento. Se acerca con la rueda, el
 * pellizco o los botones y se desplaza arrastrando; un clic (o Enter) en un departamento lo elige y lo acerca, y las flechas pasan
 * de un departamento a otro.
 */
export function MapaColombia({
  marcas,
  departamentos,
  seleccionado,
  onSeleccionar,
  etiqueta,
  capitales,
  rellenos,
}: {
  marcas: Marca[];
  /** código DANE → texto del departamento (nombre y conteos); solo los que tienen datos */
  departamentos: Record<string, string>;
  seleccionado: string | null;
  onSeleccionar: (codigo: string | null) => void;
  etiqueta: string;
  capitales: Capital[];
  /** código DANE → relleno de la coropleta; sin él, el pastel del mapa político */
  rellenos?: Record<string, string> | null;
}) {
  const svg = useRef<SVGSVGElement>(null);
  const [vista, setVista] = useState<VistaMapa>(VISTA_INICIAL);
  const [animar, setAnimar] = useState(false);
  // Textos y marcas se ajustan al zoom cuando este se detiene (o termina la animación): durante el gesto solo cambia la transformación
  const [k] = useDebouncedValue(vista.k, animar ? 320 : 150);
  const ppu = usePixelesPorUnidad(svg);
  const [enfocado, setEnfocado] = useState<string | null>(null);
  const [pista, setPista] = useState<{ texto: string; x: number; y: number } | null>(null);
  const actual = useRef(vista);
  actual.current = vista;
  // Selección y callback por ref: `elegir` es estable y elegir un departamento no vuelve a renderizar las marcas
  const sel = useRef(seleccionado);
  sel.current = seleccionado;
  const alSeleccionar = useRef(onSeleccionar);
  alSeleccionar.current = onSeleccionar;
  const elegir = useCallback((codigo: string, alternar: boolean) => {
    alSeleccionar.current(alternar && sel.current === codigo ? null : codigo);
  }, []);

  const mover = useCallback((v: VistaMapa | ((v: VistaMapa) => VistaMapa), conAnimacion: boolean) => {
    setAnimar(conAnimacion);
    setVista(v);
  }, []);

  // Durante un gesto el rectángulo del SVG se guarda (leerlo tras cada cambio de transform obliga al navegador a recalcular estilos)
  // y la vista se aplica una vez por cuadro
  const rect = useRef<DOMRect | null>(null);
  const lienzo = useCallback((cx: number, cy: number): [number, number] => {
    const r = rect.current ?? svg.current!.getBoundingClientRect();
    return [((cx - r.left) / r.width) * b.ancho, ((cy - r.top) / r.height) * b.alto];
  }, []);
  const pendiente = useRef<VistaMapa | null>(null);
  const cuadro = useRef(0);
  const programar = useCallback(
    (v: VistaMapa) => {
      pendiente.current = v;
      if (cuadro.current) return;
      cuadro.current = requestAnimationFrame(() => {
        cuadro.current = 0;
        if (pendiente.current) mover(pendiente.current, false);
      });
    },
    [mover],
  );
  useEffect(() => () => cancelAnimationFrame(cuadro.current), []);

  // Rueda y pellizco del trackpad (llega como rueda con Ctrl; en Safari como gesture*). React registra la rueda como pasiva, así que
  // va con addEventListener para poder evitar que la página se desplace. Alejar con el mapa completo deja pasar la rueda a la página.
  useEffect(() => {
    const el = svg.current;
    if (!el) return;
    const rueda = (e: WheelEvent) => {
      if (!e.ctrlKey && e.deltaY > 0 && actual.current.k <= 1) return;
      e.preventDefault();
      const [px, py] = lienzo(e.clientX, e.clientY);
      const f = Math.exp(-e.deltaY * (e.deltaMode === 1 ? 0.05 : e.ctrlKey ? 0.01 : 0.002));
      mover((v) => zoomEn(v, f, px, py), false);
    };
    let kGesto = 1;
    const inicioGesto = (e: Event) => {
      e.preventDefault();
      kGesto = actual.current.k;
    };
    const cambioGesto = (e: Event) => {
      e.preventDefault();
      const g = e as Event & { scale: number; clientX: number; clientY: number };
      const [px, py] = lienzo(g.clientX, g.clientY);
      mover((v) => zoomEn(v, (kGesto * g.scale) / v.k, px, py), false);
    };
    el.addEventListener('wheel', rueda, { passive: false });
    el.addEventListener('gesturestart', inicioGesto);
    el.addEventListener('gesturechange', cambioGesto);
    return () => {
      el.removeEventListener('wheel', rueda);
      el.removeEventListener('gesturestart', inicioGesto);
      el.removeEventListener('gesturechange', cambioGesto);
    };
  }, [lienzo, mover]);

  // Arrastre con un puntero y pellizco con dos. Si hubo movimiento, el clic que sigue no elige departamento.
  const punteros = useRef(new Map<number, { lienzo: [number, number]; pantalla: [number, number]; umbral: number }>());
  const gesto = useRef<{ vista: VistaMapa; inicio: [number, number][]; pantalla: [number, number][]; movio: boolean } | null>(null);
  const reiniciarGesto = useCallback(() => {
    const ps = [...punteros.current.values()];
    gesto.current = {
      vista: actual.current,
      inicio: ps.map((p) => p.lienzo),
      pantalla: ps.map((p) => p.pantalla),
      movio: gesto.current?.movio ?? false,
    };
  }, []);
  const alPresionar = (e: PointerReact<SVGSVGElement>) => {
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    rect.current = svg.current!.getBoundingClientRect();
    punteros.current.set(e.pointerId, {
      lienzo: lienzo(e.clientX, e.clientY),
      pantalla: [e.clientX, e.clientY],
      umbral: e.pointerType === 'mouse' ? UMBRAL_RATON : UMBRAL_TACTIL,
    });
    reiniciarGesto();
  };
  useEffect(() => {
    const alMover = (e: PointerEvent) => {
      const g = gesto.current;
      const p = punteros.current.get(e.pointerId);
      if (!g || !p) return;
      p.lienzo = lienzo(e.clientX, e.clientY);
      const [a1, b1] = [...punteros.current.values()].map((x) => x.lienzo);
      const [a0, b0] = g.inicio;
      if (!a1 || !a0) return;
      if (!b1) {
        const s0 = g.pantalla[0];
        if (s0 && Math.hypot(e.clientX - s0[0], e.clientY - s0[1]) > p.umbral) g.movio = true;
        if (g.movio) programar(limitar({ k: g.vista.k, x: g.vista.x + a1[0] - a0[0], y: g.vista.y + a1[1] - a0[1] }));
      } else if (b0) {
        const factor = Math.hypot(b1[0] - a1[0], b1[1] - a1[1]) / Math.max(Math.hypot(b0[0] - a0[0], b0[1] - a0[1]), 1);
        const [m0x, m0y] = [(a0[0] + b0[0]) / 2, (a0[1] + b0[1]) / 2];
        const [m1x, m1y] = [(a1[0] + b1[0]) / 2, (a1[1] + b1[1]) / 2];
        const v = zoomEn(g.vista, factor, m0x, m0y);
        g.movio = true;
        programar(limitar({ k: v.k, x: v.x + m1x - m0x, y: v.y + m1y - m0y }));
      }
    };
    const alSoltar = (e: PointerEvent) => {
      if (!punteros.current.delete(e.pointerId)) return;
      if (punteros.current.size > 0) {
        reiniciarGesto();
        return;
      }
      rect.current = null;
      // El clic llega justo después de soltar: se borra el gesto en la siguiente vuelta
      setTimeout(() => {
        if (punteros.current.size === 0) gesto.current = null;
      }, 0);
    };
    window.addEventListener('pointermove', alMover);
    window.addEventListener('pointerup', alSoltar);
    window.addEventListener('pointercancel', alSoltar);
    return () => {
      window.removeEventListener('pointermove', alMover);
      window.removeEventListener('pointerup', alSoltar);
      window.removeEventListener('pointercancel', alSoltar);
    };
  }, [lienzo, programar, reiniciarGesto]);
  const trasArrastre = (e: MouseEvent) => {
    if (gesto.current?.movio) {
      e.stopPropagation();
      e.preventDefault();
    }
  };

  // Elegir un departamento (en el mapa o en la tabla) lo acerca; quitar la selección vuelve al país completo
  useEffect(() => {
    const d = seleccionado ? b.departamentos.find((x) => x.codigo === seleccionado) : undefined;
    mover(d ? vistaCaja(d.caja) : VISTA_INICIAL, true);
  }, [seleccionado, mover]);

  // Foco: solo el foco por teclado (focus-visible) pinta el anillo; las flechas pasan al departamento siguiente o anterior
  const alEnfocar = useCallback((codigo: string | null, e?: FocusEvent<Element>) => {
    setEnfocado(codigo && e?.currentTarget.matches(':focus-visible') ? codigo : null);
  }, []);
  const enfocarVecino = useCallback((codigo: string, paso: number) => {
    const i = b.departamentos.findIndex((d) => d.codigo === codigo);
    const sig = b.departamentos[(i + paso + b.departamentos.length) % b.departamentos.length];
    if (sig) svg.current?.querySelector<SVGPathElement>(`path[data-departamento="${sig.codigo}"]`)?.focus();
  }, []);

  // Una sola pista para todas las marcas (en vez de un <title> por marca)
  const titulos = useMemo(() => new Map(marcas.map((m) => [String(m.clave), m.titulo])), [marcas]);
  const marcaDe = (e: { target: EventTarget }) => (e.target as Element).closest?.('[data-marca]');
  const alClicMarca = useCallback(
    (e: MouseEvent<SVGGElement>) => {
      const d = marcaDe(e)?.getAttribute('data-departamento');
      if (d) elegir(d, false);
    },
    [elegir],
  );
  const alSobreMarca = (e: PointerReact<SVGGElement>) => {
    const m = marcaDe(e);
    const texto = m && titulos.get(m.getAttribute('data-marca') ?? '');
    const r = svg.current?.getBoundingClientRect();
    setPista(texto && r ? { texto, x: e.clientX - r.left, y: e.clientY - r.top } : null);
  };

  const centro = (f: number) => mover((v) => zoomEn(v, f, b.ancho / 2, b.alto / 2), true);
  const radio = Math.max(2.8, 3.2 / ppu) / Math.sqrt(k);

  return (
    <div style={{ position: 'relative' }}>
      <svg
        ref={svg}
        viewBox={`0 0 ${b.ancho} ${b.alto}`}
        role="group"
        aria-label={etiqueta}
        data-testid="mapa-colombia"
        data-zoom={vista.k.toFixed(2)}
        style={{
          width: '100%',
          height: 'auto',
          display: 'block',
          touchAction: vista.k > 1 ? 'none' : 'pan-y',
          userSelect: 'none',
          background: MAR,
          borderRadius: 6,
        }}
        onPointerDown={alPresionar}
        onClickCapture={trasArrastre}
      >
        <style>{ESTILO}</style>
        <g
          className="mapa-capa"
          data-animar={animar}
          style={{ transform: `translate(${vista.x}px, ${vista.y}px) scale(${vista.k})` }}
          onTransitionEnd={() => setAnimar(false)}
        >
          <Fondo />
          <Departamentos
            textos={departamentos}
            seleccionado={seleccionado}
            enfocable={seleccionado ?? enfocado ?? PRIMERO}
            rellenos={rellenos ?? null}
            elegir={elegir}
            alEnfocar={alEnfocar}
            enfocarVecino={enfocarVecino}
          />
          <Etiquetas k={k} ppu={ppu} capitales={capitales} />
          <g
            style={{ '--r': `${radio}px` } as CSSProperties}
            onClick={alClicMarca}
            onPointerOver={alSobreMarca}
            onPointerLeave={() => setPista(null)}
          >
            <Marcas marcas={marcas} />
          </g>
          <Anillo codigo={seleccionado} grosor={3} />
          {enfocado !== seleccionado && <Anillo codigo={enfocado} grosor={2.4} punteado />}
        </g>
        <Escala k={k} ppu={ppu} />
        <Norte ppu={ppu} />
      </svg>
      {pista && (
        <Paper
          shadow="sm"
          withBorder
          px={8}
          py={4}
          style={{ position: 'absolute', left: pista.x + 12, top: pista.y + 12, maxWidth: 260, pointerEvents: 'none', zIndex: 2 }}
          data-testid="pista-mapa"
        >
          <Text size="xs">{pista.texto}</Text>
        </Paper>
      )}
      <Stack gap={4} style={{ position: 'absolute', top: 8, right: 8 }}>
        <ActionIcon variant="default" aria-label="Acercar" onClick={() => centro(1.6)} disabled={vista.k >= 12}>
          <IconPlus size={16} />
        </ActionIcon>
        <ActionIcon variant="default" aria-label="Alejar" onClick={() => centro(1 / 1.6)} disabled={vista.k <= 1}>
          <IconMinus size={16} />
        </ActionIcon>
        <ActionIcon variant="default" aria-label="Restablecer la vista" onClick={() => mover(VISTA_INICIAL, true)} disabled={vista.k <= 1}>
          <IconZoomReset size={16} />
        </ActionIcon>
      </Stack>
    </div>
  );
}

/** Países vecinos (y la Colombia de Natural Earth como tierra de fondo), el marco del recuadro de San Andrés y el borde nacional. */
const Fondo = memo(function Fondo() {
  const [mx, my, mw, mh] = b.recuadro.marco;
  return (
    <g aria-hidden="true">
      {b.paises.map((p) => (
        <path key={p.codigo} d={p.d} className="mapa-pais" style={p.codigo === 'COL' ? { stroke: 'none' } : undefined} />
      ))}
      <rect x={mx} y={my} width={mw} height={mh} rx={2} fill={MAR} stroke="#94A3B8" strokeWidth={0.8} vectorEffect="non-scaling-stroke" />
      <path d={CONTORNO} className="mapa-contorno" />
    </g>
  );
});

const Departamentos = memo(function Departamentos({
  textos,
  seleccionado,
  enfocable,
  rellenos,
  elegir,
  alEnfocar,
  enfocarVecino,
}: {
  textos: Record<string, string>;
  seleccionado: string | null;
  /** el único departamento en el orden de tabulación (roving tabindex) */
  enfocable: string;
  rellenos: Record<string, string> | null;
  elegir: (codigo: string, alternar: boolean) => void;
  alEnfocar: (codigo: string | null, e?: FocusEvent<Element>) => void;
  enfocarVecino: (codigo: string, paso: number) => void;
}) {
  const tecla = (codigo: string) => (e: KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      elegir(codigo, true);
    } else if (e.key === 'ArrowRight' || e.key === 'ArrowDown' || e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
      e.preventDefault();
      enfocarVecino(codigo, e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 1 : -1);
    }
  };
  return (
    <g>
      {b.departamentos.map((d) => {
        const texto = textos[d.codigo] ?? `${d.nombre}: sin datos en el filtro`;
        return (
          <path
            key={d.codigo}
            d={d.d}
            className="mapa-dpto"
            style={{ fill: rellenos?.[d.codigo] ?? fondoDepartamento(d.color) }}
            data-departamento={d.codigo}
            data-con-datos={textos[d.codigo] ? 'true' : 'false'}
            role="button"
            tabIndex={d.codigo === enfocable ? 0 : -1}
            aria-label={texto}
            aria-pressed={seleccionado === d.codigo}
            onClick={() => elegir(d.codigo, true)}
            onKeyDown={tecla(d.codigo)}
            onFocus={(e) => alEnfocar(d.codigo, e)}
            onBlur={() => alEnfocar(null)}
          >
            <title>{texto}</title>
          </path>
        );
      })}
    </g>
  );
});

/** Borde del departamento elegido o enfocado, encima de todo: oscuro con halo blanco, visible sobre cualquier relleno. */
function Anillo({ codigo, grosor, punteado }: { codigo: string | null; grosor: number; punteado?: boolean }) {
  const d = codigo ? b.departamentos.find((x) => x.codigo === codigo)?.d : undefined;
  if (!d) return null;
  return (
    <g aria-hidden="true" data-anillo={codigo}>
      <path d={d} className="mapa-anillo" stroke="#fff" strokeWidth={grosor + 2.5} />
      <path d={d} className="mapa-anillo" stroke={OSCURO} strokeWidth={grosor} strokeDasharray={punteado ? '5 3' : undefined} />
    </g>
  );
}

/**
 * Nombres de departamentos, países y mares, y las capitales, con tamaño fijo en pantalla. Un nombre que no cabe en su departamento
 * no se pinta (aparece al acercar); los de línea guía y los de las capitales, desde que el mapa se ve a escala suficiente.
 */
const Etiquetas = memo(function Etiquetas({ k, ppu, capitales }: { k: number; ppu: number; capitales: Capital[] }) {
  const escalaPantalla = k * ppu;
  const s = (px: number) => px / escalaPantalla;
  const fuente = s(8.5);
  const conDetalle = escalaPantalla >= 1;
  return (
    <g aria-hidden="true" style={{ pointerEvents: 'none' }}>
      {b.mares.map((m) => (
        <text
          key={m.nombre}
          x={m.etiqueta[0]}
          y={m.etiqueta[1]}
          fontSize={s(8.5)}
          fontStyle="italic"
          fill="#6B9AC4"
          textAnchor="middle"
          letterSpacing={s(0.8)}
        >
          {m.nombre}
        </text>
      ))}
      {b.paises
        .filter((p) => p.nombre && p.etiqueta)
        .map((p) => (
          <text
            key={p.codigo}
            x={p.etiqueta![0]}
            y={p.etiqueta![1]}
            fontSize={s(9)}
            fontWeight={600}
            fill={TEXTO_SUAVE}
            textAnchor="middle"
            letterSpacing={s(1.5)}
          >
            {p.nombre!.toUpperCase()}
          </text>
        ))}
      {b.departamentos.map((d) => {
        const conGuia = d.guia && k < ZOOM_SIN_GUIA;
        const [x0 = 0, y0 = 0, x1 = 0, y1 = 0] = d.caja;
        const largo = Math.max(...d.renglones.map((r) => r.length)) * 0.62 * fuente;
        const cabe = d.codigo === '88' ? conDetalle : largo <= (x1 - x0) * 1.05 && d.renglones.length * fuente * 1.1 <= y1 - y0;
        if (conGuia ? !conDetalle : !cabe) return null;
        const [x = 0, y = 0] = conGuia ? d.guia! : d.etiqueta;
        const n = d.renglones.length;
        return (
          <g key={d.codigo} data-etiqueta={d.codigo}>
            {conGuia && (
              <>
                <line x1={d.etiqueta[0]} y1={d.etiqueta[1]} x2={x} y2={y} stroke="#64748B" strokeWidth={s(0.6)} />
                <circle cx={d.etiqueta[0]} cy={d.etiqueta[1]} r={s(1.2)} fill="#64748B" />
              </>
            )}
            <text
              className="mapa-texto"
              x={x}
              y={y}
              fontSize={fuente}
              fontWeight={600}
              fill={TEXTO}
              textAnchor="middle"
              letterSpacing={s(0.5)}
              strokeWidth={s(2.2)}
            >
              {d.renglones.map((r, i) => (
                <tspan key={i} x={x} y={y + fuente * 0.35 + (i - (n - 1) / 2) * fuente * 1.1}>
                  {r}
                </tspan>
              ))}
            </text>
          </g>
        );
      })}
      {capitales.map((c) => {
        const [x, y] = proyectar(c.latitud, c.longitud);
        return (
          <g key={c.codigo} data-capital={c.codigo}>
            <circle cx={x} cy={y} r={s(2.2)} fill="#fff" stroke={OSCURO} strokeWidth={s(1)} />
            {/* El nombre de Bogotá ya es el del departamento */}
            {conDetalle && c.departamento !== '11' && (
              <text
                className="mapa-texto"
                x={CAPITAL_A_LA_IZQUIERDA.has(c.codigo) ? x - s(3.5) : x + s(3.5)}
                y={y + s(2.4)}
                textAnchor={CAPITAL_A_LA_IZQUIERDA.has(c.codigo) ? 'end' : 'start'}
                fontSize={s(7)}
                fill={OSCURO}
                strokeWidth={s(1.8)}
              >
                {nombreEnMapa(c)}
              </text>
            )}
          </g>
        );
      })}
    </g>
  );
});

/** Las marcas solo se vuelven a pintar si cambian: el radio va por CSS y el clic y la pista se atienden en el grupo. */
const Marcas = memo(function Marcas({ marcas }: { marcas: Marca[] }) {
  return (
    <g data-testid="marcas-mapa" aria-hidden="true">
      {marcas.map((m) => (
        <circle
          key={m.clave}
          cx={m.x}
          cy={m.y}
          r={2.8}
          style={{ fill: m.color }}
          className="mapa-marca"
          data-marca={m.clave}
          data-departamento={m.departamento}
        />
      ))}
    </g>
  );
});

/** Barra de escala en km (abajo a la izquierda), según el zoom. */
const Escala = memo(function Escala({ k, ppu }: { k: number; ppu: number }) {
  const { km, largo } = escala(k);
  const t = 1 / Math.min(ppu, 1);
  const x = 12;
  const y = b.alto - 12;
  return (
    <g aria-hidden="true" data-testid="escala-mapa" style={{ pointerEvents: 'none' }}>
      <rect x={x - 5} y={y - 16 * t} width={largo + 10} height={20 * t} rx={3} fill="#fff" fillOpacity={0.85} />
      <path d={`M${x} ${y - 3 * t}V${y}H${x + largo}V${y - 3 * t}`} fill="none" stroke={TEXTO} strokeWidth={t} />
      <text x={x} y={y - 5 * t} fontSize={8 * t} fill={TEXTO}>
        0
      </text>
      <text x={x + largo} y={y - 5 * t} fontSize={8 * t} fill={TEXTO} textAnchor="end">
        {km} km
      </text>
    </g>
  );
});

/** Flecha de norte discreta, abajo a la derecha (arriba van los botones de zoom). */
const Norte = memo(function Norte({ ppu }: { ppu: number }) {
  const t = 1 / Math.min(ppu, 1);
  const x = b.ancho - 18 * t;
  const y = b.alto - 18 * t;
  return (
    <g aria-hidden="true" style={{ pointerEvents: 'none' }}>
      <path d={`M${x} ${y - 12 * t}L${x + 5 * t} ${y + 4 * t}L${x} ${y}L${x - 5 * t} ${y + 4 * t}Z`} fill={TEXTO} fillOpacity={0.75} />
      <text x={x} y={y - 15 * t} fontSize={8 * t} fontWeight={700} fill={TEXTO} textAnchor="middle">
        N
      </text>
    </g>
  );
});
