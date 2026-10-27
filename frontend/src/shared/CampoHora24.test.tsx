// @vitest-environment jsdom
import { MantineProvider } from '@mantine/core';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { CampoHora24 } from './CampoHora24';
import { tema } from './tema';

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
});
afterEach(cleanup);

function Prueba({ inicial = '' }: { inicial?: string }) {
  const [hora, setHora] = useState(inicial);
  return (
    <MantineProvider theme={tema}>
      <CampoHora24 label="Hora" value={hora} onChange={setHora} />
      <output data-testid="valor">{hora}</output>
    </MantineProvider>
  );
}

const hora = () => screen.getByRole('combobox', { name: 'Hora: hora' }) as HTMLSelectElement;
const minutos = () => screen.getByRole('combobox', { name: 'Hora: minutos' }) as HTMLSelectElement;
const valor = () => screen.getByTestId('valor').textContent;

describe('CampoHora24 (hora militar en dos listas; ítem 12)', () => {
  it('ofrece solo 00 a 23 y 00 a 59, más la opción vacía', () => {
    render(<Prueba />);
    const horas = [...hora().options].map((o) => o.value);
    const mins = [...minutos().options].map((o) => o.value);
    expect(horas).toHaveLength(25);
    expect(horas.slice(1, 3)).toEqual(['00', '01']);
    expect(horas.at(-1)).toBe('23');
    expect(mins).toHaveLength(61);
    expect(mins.at(-1)).toBe('59');
  });

  it('al elegir la hora los minutos quedan en 00 y luego se eligen', () => {
    render(<Prueba />);
    fireEvent.change(hora(), { target: { value: '15' } });
    expect(valor()).toBe('15:00');
    fireEvent.change(minutos(), { target: { value: '45' } });
    expect(valor()).toBe('15:45');
  });

  it('muestra la hora que llega del padre y la borra al quitar la hora', () => {
    render(<Prueba inicial="9:30" />);
    expect(hora().value).toBe('09');
    expect(minutos().value).toBe('30');
    fireEvent.change(hora(), { target: { value: '' } });
    expect(valor()).toBe('');
  });

  it('minutos elegidos antes que la hora se conservan sin avisar una hora incompleta', () => {
    render(<Prueba />);
    fireEvent.change(minutos(), { target: { value: '20' } });
    expect(valor()).toBe('');
    expect(minutos().value).toBe('20');
    fireEvent.change(hora(), { target: { value: '07' } });
    expect(valor()).toBe('07:20');
  });
});
