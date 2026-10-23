// @vitest-environment jsdom
import { MantineProvider } from '@mantine/core';
import { cleanup, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { EncabezadoPagina, tituloDocumento } from './EncabezadoPagina';
import { tema } from './tema';

beforeAll(() => {
  // jsdom no trae matchMedia, que Mantine consulta para el esquema de color
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
});
afterEach(cleanup);

const conTema = (n: ReactNode) => render(<MantineProvider theme={tema}>{n}</MantineProvider>);

describe('EncabezadoPagina', () => {
  it('pone el título como h1, la descripción, la fuente normativa y las acciones', () => {
    conTema(<EncabezadoPagina titulo="Agenda" descripcion="Visitas del mes" fuente="Anexo 5 · 4.5" acciones={<button>Programar visita</button>} />);
    expect(screen.getByRole('heading', { level: 1, name: 'Agenda' })).toBeTruthy();
    expect(screen.getByText('Visitas del mes')).toBeTruthy();
    // El lector de pantalla oye qué es la etiqueta; en pantalla solo se ve la referencia
    expect(screen.getByText(/Anexo 5 · 4\.5/).textContent).toBe('Fuente normativa: Anexo 5 · 4.5');
    expect(screen.getByRole('button', { name: 'Programar visita' })).toBeTruthy();
  });

  it('la fuente y las insignias no cambian el nombre accesible del título (las e2e lo buscan exacto)', () => {
    conTema(
      <EncabezadoPagina titulo="Visita VS-1" fuente="Anexo 5 · 4.5">
        <span>Cerrada</span>
      </EncabezadoPagina>,
    );
    expect(screen.getByRole('heading', { name: 'Visita VS-1' }).textContent).toBe('Visita VS-1');
  });

  it('pone «Suite DIGSA · <pantalla>» en la pestaña del navegador', () => {
    conTema(<EncabezadoPagina titulo="Logs del sistema" />);
    expect(document.title).toBe('Suite DIGSA · Logs del sistema');
    expect(tituloDocumento()).toBe('Suite DIGSA');
  });

  it('al salir de la pantalla la pestaña vuelve a «Suite DIGSA»', () => {
    const { unmount } = conTema(<EncabezadoPagina titulo="Usuarios" />);
    expect(document.title).toBe('Suite DIGSA · Usuarios');
    unmount();
    expect(document.title).toBe('Suite DIGSA');
  });

  it('con orden 2 el título es h2 (portal del OPL, que ya tiene su h1)', () => {
    conTema(<EncabezadoPagina titulo="Visita VS-2 · ESM" orden={2} />);
    expect(screen.getByRole('heading', { level: 2, name: 'Visita VS-2 · ESM' })).toBeTruthy();
  });
});
