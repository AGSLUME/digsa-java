import { Button, type ButtonProps } from '@mantine/core';
import type { ComponentPropsWithoutRef, MouseEvent } from 'react';
import { sonarError } from './alertaSonora';

type Props = ButtonProps &
  Omit<ComponentPropsWithoutRef<'button'>, keyof ButtonProps | 'disabled'> & {
    /** Por qué no se puede todavía (null: el botón funciona). */
    motivo: string | null;
    /** id del texto junto al botón que explica el motivo: recibe el foco al intentar. */
    idMotivo: string;
    /** Al pulsarlo bloqueado: p. ej. marcar los campos y llevar el foco al primero con error; si devuelve true, el foco ya se movió. */
    alBloquear?: () => boolean | void;
  };

/**
 * Botón que no se puede usar todavía y dice por qué (Anexo 5 num. 2.4; decisión UI-24): en lugar de `disabled`, que no deja pulsarlo ni
 * avisa, queda con `aria-disabled` y el aspecto de deshabilitado; al pulsarlo suena el aviso y el foco va al texto que explica qué falta
 * (unido con `aria-describedby`). Playwright lo ve deshabilitado igual (`toBeDisabled` respeta `aria-disabled`).
 */
export function BotonConMotivo({ motivo, idMotivo, alBloquear, onClick, ...props }: Props) {
  const bloqueado = motivo !== null;
  const alPulsar = (e: MouseEvent<HTMLButtonElement>) => {
    if (bloqueado) {
      e.preventDefault(); // tampoco envía el formulario si es de tipo submit
      sonarError();
      if (alBloquear?.() === true) return;
      const ayuda = document.getElementById(idMotivo);
      if (ayuda) {
        if (!ayuda.hasAttribute('tabindex')) ayuda.setAttribute('tabindex', '-1');
        ayuda.setAttribute('data-motivo', ''); // foco visible también tras un clic (estilos.css)
        ayuda.focus();
      }
      return;
    }
    onClick?.(e);
  };
  return (
    <Button
      {...props}
      aria-disabled={bloqueado || undefined}
      data-disabled={bloqueado || undefined}
      aria-describedby={bloqueado ? idMotivo : props['aria-describedby']}
      onClick={alPulsar}
    />
  );
}
