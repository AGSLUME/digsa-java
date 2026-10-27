// @vitest-environment jsdom
import { MantineProvider } from '@mantine/core';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { tema } from './tema';
import { tipoSeguro, VisorDocumento, vistaDe } from './VisorDocumento';

const archivo = vi.hoisted(() => ({ blob: new Blob(['x'], { type: 'text/html' }) }));
vi.mock('./api', async (original) => ({
  ...(await original<typeof import('./api')>()),
  obtenerArchivo: vi.fn(async () => ({ blob: archivo.blob, nombre: 'evidencia' })),
}));

beforeAll(() => {
  window.matchMedia ??= ((q: string) => ({
    matches: false,
    media: q,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
  URL.createObjectURL ??= () => 'blob:prueba';
  URL.revokeObjectURL ??= () => undefined;
});
afterEach(cleanup);

describe('VisorDocumento: lista blanca (UI-31)', () => {
  it('solo muestra PDF, PNG y JPEG; HTML, SVG y lo demás se descargan', () => {
    expect(vistaDe('application/pdf', true)).toBe('pdf');
    expect(vistaDe('image/png', true)).toBe('imagen');
    expect(vistaDe('image/jpeg; charset=binary', true)).toBe('imagen');
    for (const t of ['text/html', 'image/svg+xml', 'application/xhtml+xml', 'image/heic', 'video/mp4', 'image/gif', '']) {
      expect(vistaDe(t, true)).toBe('ninguna');
      expect(tipoSeguro(t)).toBe('application/octet-stream');
    }
  });

  it('sin visor de PDF en el navegador, el PDF solo se descarga', () => {
    expect(vistaDe('application/pdf', false)).toBe('ninguna');
  });

  it.each(['text/html', 'image/svg+xml'])('un archivo %s no se pinta: ni iframe ni imagen, solo «Descargar»', async (tipo) => {
    archivo.blob = new Blob(['<svg onload="alert(1)"></svg>'], { type: tipo });
    render(
      <MantineProvider theme={tema}>
        <VisorDocumento url="/api/archivos/1" nombre="Evidencia">
          Ver
        </VisorDocumento>
      </MantineProvider>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Ver' }));
    expect(await screen.findByRole('link', { name: 'Descargar' })).toBeTruthy();
    expect(document.querySelector('iframe')).toBeNull();
    expect(document.querySelector('img')).toBeNull();
    expect(screen.getByText(/no tiene vista previa/)).toBeTruthy();
  });
});
