import { Button, Group, Text } from '@mantine/core';
import { useEffect, useId, useRef, type PointerEvent } from 'react';

const ANCHO = 320;
const ALTO = 140;

/**
 * Panel de firma: lienzo dibujado con el dedo, el lápiz o el ratón (eventos pointer, sin desplazar la página al firmar).
 * Avisa con la imagen PNG en data URL cada vez que se termina un trazo, o null al borrar. Con `error` (p. ej. «falta la firma») el
 * recuadro queda marcado (aria-invalid, recibe el foco de la validación) y el mensaje va debajo (Anexo 5 num. 2.4).
 */
export function PanelFirma({ alCambiar, error }: { alCambiar: (png: string | null) => void; error?: string }) {
  const idError = useId();
  const lienzo = useRef<HTMLCanvasElement>(null);
  const dibujando = useRef(false);
  const ultimo = useRef<{ x: number; y: number } | null>(null);

  const limpiar = () => {
    const c = lienzo.current;
    const ctx = c?.getContext('2d');
    if (!c || !ctx) return;
    const escala = window.devicePixelRatio || 1;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, c.width, c.height);
    ctx.setTransform(escala, 0, 0, escala, 0, 0);
    ctx.lineWidth = 2.2;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.strokeStyle = '#111111';
  };

  useEffect(() => {
    const c = lienzo.current;
    if (!c) return;
    const escala = window.devicePixelRatio || 1;
    c.width = Math.round(ANCHO * escala);
    c.height = Math.round(ALTO * escala);
    limpiar();
  }, []);

  const punto = (e: PointerEvent<HTMLCanvasElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    return { x: ((e.clientX - r.left) * ANCHO) / (r.width || ANCHO), y: ((e.clientY - r.top) * ALTO) / (r.height || ALTO) };
  };

  const empezar = (e: PointerEvent<HTMLCanvasElement>) => {
    e.preventDefault();
    try {
      e.currentTarget.setPointerCapture(e.pointerId);
    } catch {
      // algunos navegadores no capturan punteros sintéticos
    }
    dibujando.current = true;
    const p = punto(e);
    ultimo.current = p;
    const ctx = e.currentTarget.getContext('2d');
    if (ctx) {
      ctx.beginPath();
      ctx.arc(p.x, p.y, 1.1, 0, Math.PI * 2);
      ctx.fillStyle = '#111111';
      ctx.fill();
    }
  };

  const mover = (e: PointerEvent<HTMLCanvasElement>) => {
    if (!dibujando.current) return;
    e.preventDefault();
    const ctx = e.currentTarget.getContext('2d');
    const p = punto(e);
    const a = ultimo.current ?? p;
    if (ctx) {
      ctx.beginPath();
      ctx.moveTo(a.x, a.y);
      ctx.lineTo(p.x, p.y);
      ctx.stroke();
    }
    ultimo.current = p;
  };

  const terminar = (e: PointerEvent<HTMLCanvasElement>) => {
    if (!dibujando.current) return;
    dibujando.current = false;
    ultimo.current = null;
    alCambiar(e.currentTarget.toDataURL('image/png'));
  };

  return (
    <div>
      <Text size="sm" fw={500} mb={4}>
        Firma
      </Text>
      {/* aria-invalid sobre role="img": el foco al primer error (validacion.tsx) lo busca; el mensaje va con aria-describedby */}
      {/* eslint-disable-next-line jsx-a11y/role-supports-aria-props */}
      <canvas
        ref={lienzo}
        role="img"
        aria-label="Panel de firma"
        data-testid="panel-firma"
        tabIndex={error ? -1 : undefined}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? idError : undefined}
        onPointerDown={empezar}
        onPointerMove={mover}
        onPointerUp={terminar}
        onPointerCancel={terminar}
        onPointerLeave={terminar}
        style={{
          display: 'block',
          width: '100%',
          maxWidth: ANCHO,
          aspectRatio: `${ANCHO} / ${ALTO}`,
          touchAction: 'none',
          border: error ? '1px solid var(--mantine-color-error)' : '1px solid var(--mantine-color-gray-5)',
          borderRadius: 4,
          background: '#ffffff',
          cursor: 'crosshair',
        }}
      />
      {error && (
        <Text id={idError} size="xs" c="var(--mantine-color-error)" mt={4}>
          {error}
        </Text>
      )}
      <Group justify="space-between" mt={4}>
        <Text size="xs" c="dimmed">
          Firme dentro del recuadro.
        </Text>
        <Button
          size="xs"
          variant="default"
          onClick={() => {
            limpiar();
            alCambiar(null);
          }}
        >
          Borrar
        </Button>
      </Group>
    </div>
  );
}
