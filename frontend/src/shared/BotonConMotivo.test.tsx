// @vitest-environment jsdom
import { MantineProvider } from '@mantine/core';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { BotonConMotivo } from './BotonConMotivo';
import { tema } from './tema';

const sonar = vi.hoisted(() => vi.fn());
vi.mock('./alertaSonora', () => ({ sonarError: sonar }));
afterEach(() => {
  cleanup();
  sonar.mockClear();
});

function Prueba({ motivo, alPulsar }: { motivo: string | null; alPulsar: () => void }) {
  return (
    <MantineProvider theme={tema}>
      <BotonConMotivo motivo={motivo} idMotivo="ayuda" onClick={alPulsar}>
        Guardar
      </BotonConMotivo>
      <p id="ayuda">Cambie un valor para guardar.</p>
    </MantineProvider>
  );
}

describe('BotonConMotivo (UI-24)', () => {
  it('con motivo: queda aria-disabled, no actúa, suena y lleva el foco a la explicación', () => {
    const alPulsar = vi.fn();
    render(<Prueba motivo="Cambie un valor para guardar." alPulsar={alPulsar} />);
    const boton = screen.getByRole('button', { name: 'Guardar' });
    expect(boton.getAttribute('aria-disabled')).toBe('true');
    expect(boton.getAttribute('aria-describedby')).toBe('ayuda');
    expect(boton.hasAttribute('disabled')).toBe(false);
    fireEvent.click(boton);
    expect(alPulsar).not.toHaveBeenCalled();
    expect(sonar).toHaveBeenCalledOnce();
    expect(document.activeElement?.id).toBe('ayuda');
  });

  it('sin motivo: funciona como un botón normal', () => {
    const alPulsar = vi.fn();
    render(<Prueba motivo={null} alPulsar={alPulsar} />);
    const boton = screen.getByRole('button', { name: 'Guardar' });
    expect(boton.hasAttribute('aria-disabled')).toBe(false);
    fireEvent.click(boton);
    expect(alPulsar).toHaveBeenCalledOnce();
    expect(sonar).not.toHaveBeenCalled();
  });
});
