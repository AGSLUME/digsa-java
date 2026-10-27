import { Group, Input, NativeSelect, TextInput } from '@mantine/core';
import { useId, useState } from 'react';
import { leerHora } from './hora';

const HORAS = Array.from({ length: 24 }, (_, i) => String(i).padStart(2, '0'));
const MINUTOS = Array.from({ length: 60 }, (_, i) => String(i).padStart(2, '0'));
const VACIO = { value: '', label: '--' };

/**
 * Campo de hora militar (decisión D4; ítem 12 de la prueba en sitio): dos listas, hora 00 a 23 y minutos 00 a 59, en lugar de un campo
 * de texto, así que no se puede escribir una hora inválida ni depende de la configuración regional del equipo (el `type="time"` nativo
 * muestra a. m./p. m.). Avisa «HH:MM» cuando están las dos partes y '' si falta la hora (el padre lo marca como «falta la hora»). Al
 * elegir la hora sin minutos, los minutos quedan en 00.
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
  /** Error que pone el formulario (falta la hora, error del servidor). */
  error?: string | true;
}) {
  const id = useId();
  const nombre = ariaLabel ?? label;
  const leida = leerHora(value) ?? '';
  const [hh = '', mm = ''] = leida ? leida.split(':') : [];
  // Minutos elegidos antes que la hora: se recuerdan aquí (el padre solo recibe una hora completa)
  const [minutosSueltos, setMinutosSueltos] = useState('');
  const minutos = hh ? mm : minutosSueltos;

  if (readOnly) {
    return <TextInput label={label} aria-label={ariaLabel} readOnly value={leida} data-testid={testid} />;
  }

  const invalido = !!error;
  const avisar = (h: string, m: string) => {
    if (!h) {
      setMinutosSueltos(m);
      onChange('');
      return;
    }
    setMinutosSueltos('');
    onChange(`${h}:${m || '00'}`);
  };

  // Las dos listas anuncian la ayuda («24 horas…») y, si lo hay, el mensaje de error del campo
  const describe =
    [description && `${id}-description`, invalido && typeof error === 'string' && `${id}-error`].filter(Boolean).join(' ') || undefined;

  return (
    <Input.Wrapper id={id} label={label} description={description} required={required} error={error} data-testid={testid}>
      <Group gap="xs" wrap="nowrap" align="center">
        <NativeSelect
          id={id}
          aria-label={`${nombre}: hora`}
          data={[VACIO, ...HORAS]}
          value={hh}
          required={required}
          error={invalido}
          aria-describedby={describe}
          onChange={(e) => avisar(e.currentTarget.value, minutos)}
          style={{ flex: 1 }}
        />
        <span aria-hidden="true">:</span>
        <NativeSelect
          aria-label={`${nombre}: minutos`}
          data={[VACIO, ...MINUTOS]}
          value={minutos}
          error={invalido}
          aria-describedby={describe}
          onChange={(e) => avisar(hh, e.currentTarget.value)}
          style={{ flex: 1 }}
        />
      </Group>
    </Input.Wrapper>
  );
}
