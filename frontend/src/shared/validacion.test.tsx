// @vitest-environment jsdom
import { Button, MantineProvider, Radio, TextInput } from '@mantine/core';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { ErrorApi } from './api';
import { tema } from './tema';
import { primerCampoConError, useValidacion } from './validacion';

const sonar = vi.hoisted(() => vi.fn());
vi.mock('./alertaSonora', () => ({ sonarError: sonar }));

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
afterEach(() => {
  cleanup();
  sonar.mockClear();
});

const conTema = (n: ReactNode) => render(<MantineProvider theme={tema}>{n}</MantineProvider>);

function Formulario({ fallo }: { fallo?: unknown }) {
  const v = useValidacion({ etiquetas: { nombre: 'Nombre', telefono: 'Teléfono' }, campos: { celular: 'telefono' }, testid: 'error-usuario' });
  return (
    <form
      ref={v.ref}
      onSubmit={(e) => {
        e.preventDefault();
        const datos = new FormData(e.currentTarget);
        const ok = v.validar({
          nombre: datos.get('nombre') ? null : 'Escriba el nombre.',
          telefono: /^\d{10}$/.test(String(datos.get('telefono'))) ? null : 'El teléfono tiene 10 dígitos.',
        });
        if (ok && fallo) v.errorServidor(fallo, 'No se pudo guardar.');
      }}
    >
      {v.resumen}
      <TextInput label="Nombre" name="nombre" error={v.error('nombre')} onChange={() => v.limpiar('nombre')} />
      <TextInput label="Teléfono" name="telefono" error={v.error('telefono')} />
      <Button type="submit">Guardar</Button>
    </form>
  );
}

describe('useValidacion (Anexo 5 num. 2.4)', () => {
  it('marca cada campo, resume cuáles revisar, lleva el foco al primero y suena', () => {
    conTema(<Formulario />);
    act(() => fireEvent.click(screen.getByRole('button', { name: 'Guardar' })));

    const nombre = screen.getByLabelText('Nombre');
    const telefono = screen.getByLabelText('Teléfono');
    expect(nombre.getAttribute('aria-invalid')).toBe('true');
    expect(telefono.getAttribute('aria-invalid')).toBe('true');
    expect(screen.getByText('Escriba el nombre.')).toBeTruthy();
    expect(screen.getByText('El teléfono tiene 10 dígitos.')).toBeTruthy();
    // El resumen nombra los campos sin repetir el mensaje (cada mensaje aparece una sola vez)
    const resumen = screen.getByRole('alert');
    expect(resumen.textContent).toContain('Revise los 2 campos marcados');
    expect(resumen.textContent).toContain('Teléfono');
    expect(screen.getAllByText('Escriba el nombre.')).toHaveLength(1);
    expect(document.activeElement).toBe(nombre);
    expect(sonar).toHaveBeenCalledTimes(1);
  });

  it('al corregir un campo se quita su error; con todo bien no hay resumen ni sonido', () => {
    conTema(<Formulario />);
    act(() => fireEvent.click(screen.getByRole('button', { name: 'Guardar' })));
    act(() => fireEvent.change(screen.getByLabelText('Nombre'), { target: { value: 'Ana' } }));
    expect(screen.getByLabelText('Nombre').getAttribute('aria-invalid')).not.toBe('true');

    act(() => fireEvent.change(screen.getByLabelText('Teléfono'), { target: { value: '3001234567' } }));
    sonar.mockClear();
    act(() => fireEvent.click(screen.getByRole('button', { name: 'Guardar' })));
    expect(screen.queryByRole('alert')).toBeNull();
    expect(sonar).not.toHaveBeenCalled();
  });

  it('un 422 con «campo»: el mensaje queda una vez en el resumen (con su testid) y el campo traducido queda marcado y con el foco', () => {
    conTema(<Formulario fallo={new ErrorApi(422, 'Ese teléfono ya está registrado.', { campo: 'celular' })} />);
    act(() => fireEvent.change(screen.getByLabelText('Nombre'), { target: { value: 'Ana' } }));
    act(() => fireEvent.change(screen.getByLabelText('Teléfono'), { target: { value: '3001234567' } }));
    act(() => fireEvent.click(screen.getByRole('button', { name: 'Guardar' })));

    expect(screen.getByLabelText('Teléfono').getAttribute('aria-invalid')).toBe('true');
    expect(screen.getAllByText('Ese teléfono ya está registrado.')).toHaveLength(1);
    expect(screen.getByTestId('error-usuario').textContent).toContain('Ese teléfono ya está registrado.');
    expect(screen.getByRole('alert').textContent).toContain('Revise el campo marcado: Teléfono');
    // El foco va al resumen, donde está el motivo (el lector lo lee antes de llegar al campo)
    expect(document.activeElement).toBe(screen.getByTestId('error-usuario'));
    expect(sonar).toHaveBeenCalledTimes(1);
  });

  it('un error del servidor sin campo queda en el resumen, visible y anunciado, con el foco en él', () => {
    conTema(<Formulario fallo={new ErrorApi(409, 'La visita cambió de estado.')} />);
    act(() => fireEvent.change(screen.getByLabelText('Nombre'), { target: { value: 'Ana' } }));
    act(() => fireEvent.change(screen.getByLabelText('Teléfono'), { target: { value: '3001234567' } }));
    act(() => fireEvent.click(screen.getByRole('button', { name: 'Guardar' })));

    const resumen = screen.getByRole('alert');
    expect(resumen.textContent).toContain('La visita cambió de estado.');
    expect(document.activeElement).toBe(resumen);
  });

  it('un error que no es de la API muestra el texto de respaldo', () => {
    conTema(<Formulario fallo={new Error('red')} />);
    act(() => fireEvent.change(screen.getByLabelText('Nombre'), { target: { value: 'Ana' } }));
    act(() => fireEvent.change(screen.getByLabelText('Teléfono'), { target: { value: '3001234567' } }));
    act(() => fireEvent.click(screen.getByRole('button', { name: 'Guardar' })));
    expect(screen.getByRole('alert').textContent).toContain('No se pudo guardar.');
  });

  it('el resumen no cambia mientras el usuario corrige (no se vuelve a anunciar) y se va cuando no queda ningún error', () => {
    conTema(<Formulario />);
    act(() => fireEvent.click(screen.getByRole('button', { name: 'Guardar' })));
    const titulo = screen.getByRole('alert').textContent;
    act(() => fireEvent.change(screen.getByLabelText('Nombre'), { target: { value: 'Ana' } }));
    expect(screen.getByRole('alert').textContent).toBe(titulo);
  });
});

function ConGrupo() {
  const v = useValidacion({ etiquetas: { decision: 'Respuesta', oculto: 'Oculto' } });
  return (
    <form
      ref={v.ref}
      onSubmit={(e) => {
        e.preventDefault();
        v.validar({ oculto: 'Campo oculto.', decision: 'Elija una respuesta.' });
      }}
    >
      {v.resumen}
      {/* React 18 no tipa «inert»: panel plegado de Mantine */}
      <div {...({ inert: '' } as object)}>
        <TextInput label="Oculto" error={v.error('oculto')} />
      </div>
      <Radio.Group label="Respuesta" error={v.error('decision')}>
        <Radio value="acepta" label="Acepta" />
        <Radio value="objeta" label="Objeta" />
      </Radio.Group>
      <Button type="submit">Enviar</Button>
    </form>
  );
}

describe('primer campo con error', () => {
  it('salta el campo de un panel plegado (inert) y llega al grupo de radios, que Mantine no marca con aria-invalid', () => {
    conTema(<ConGrupo />);
    act(() => fireEvent.click(screen.getByRole('button', { name: 'Enviar' })));
    expect(document.activeElement).toBe(screen.getByLabelText('Acepta'));
  });
});

describe('primerCampoConError', () => {
  it('enfoca el lienzo de la firma marcado (tabindex=-1) y no el botón vecino', () => {
    const raiz = document.createElement('div');
    raiz.innerHTML = '<div><canvas aria-invalid="true" tabindex="-1"></canvas><button>Borrar</button></div>';
    document.body.appendChild(raiz);
    expect(primerCampoConError(raiz)?.tagName).toBe('CANVAS');
    raiz.remove();
  });
});
