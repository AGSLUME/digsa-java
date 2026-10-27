import { TextInput } from '@mantine/core';
import { useEffect, useRef, useState } from 'react';
import { leerHora, MENSAJE_HORA } from './hora';

/**
 * Campo de hora militar (decisión D4): siempre muestra y acepta 24 h (HH:MM), en lugar del `type="time"` nativo, que sigue la
 * configuración regional del equipo. Avisa «HH:MM» cuando lo escrito es una hora válida, '' si queda vacío y el texto tal cual si no
 * es válido (el padre lo revisa con `horaValida` y el navegador no deja enviar el formulario). Al salir del campo la hora queda con
 * dos dígitos («930» → «09:30»).
 */
export function CampoHora24({
  label,
  value,
  onChange,
  readOnly,
  required,
  description = '24 horas (00:00 a 23:59)',
  'aria-label': ariaLabel,
  'data-testid': testid,
  error,
}: {
  label: string;
  value: string;
  onChange: (hora: string) => void;
  readOnly?: boolean;
  required?: boolean;
  description?: string;
  'aria-label'?: string;
  'data-testid'?: string;
  /** Error que pone el formulario (falta la hora, error del servidor); el de formato lo pone el propio campo y tiene prioridad. */
  error?: string | true;
}) {
  const entrada = useRef<HTMLInputElement>(null);
  const [texto, setTexto] = useState(value);
  const [tocado, setTocado] = useState(false);
  const leida = leerHora(texto);
  // Un cambio que viene del padre (lo guardado, otra visita) reemplaza lo escrito; lo que este campo avisó no
  // (se comparan normalizados: un «9:30» que llega del padre no provoca un ciclo de renders contra el «09:30» leído)
  if ((leerHora(value) ?? value) !== (leida ?? texto)) {
    setTexto(value);
  }
  const invalida = leida === null;

  // El navegador no envía el formulario con una hora inválida (mensaje propio en lugar del del patrón)
  useEffect(() => {
    entrada.current?.setCustomValidity(invalida ? MENSAJE_HORA : '');
  }, [invalida]);

  return (
    <TextInput
      ref={entrada}
      label={label}
      aria-label={ariaLabel}
      description={readOnly ? undefined : description}
      placeholder="HH:MM"
      inputMode="numeric"
      autoComplete="off"
      maxLength={12}
      readOnly={readOnly}
      required={required}
      data-testid={testid}
      value={texto}
      error={tocado && invalida ? MENSAJE_HORA : error}
      onChange={(e) => {
        const t = e.currentTarget.value;
        setTexto(t);
        onChange(leerHora(t) ?? t);
      }}
      onBlur={() => {
        setTocado(true);
        if (leida !== null && leida !== texto) setTexto(leida);
      }}
    />
  );
}
