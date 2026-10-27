import { ActionIcon, Tooltip } from '@mantine/core';
import { IconVolume, IconVolumeOff } from '@tabler/icons-react';
import { useSonido } from './alertaSonora';

/** Interruptor del aviso sonoro de validación (decisión UI-8): activado por omisión, el navegador recuerda la elección. */
export function InterruptorSonido({ color = 'lume' }: { color?: string }) {
  const [activo, activar] = useSonido();
  const texto = activo ? 'Sonido de las alertas activado' : 'Sonido de las alertas desactivado';
  return (
    <Tooltip label={texto} withArrow>
      <ActionIcon
        variant="subtle"
        color={color}
        size={44}
        aria-label="Sonido de las alertas"
        aria-pressed={activo}
        data-testid="interruptor-sonido"
        onClick={() => activar(!activo)}
      >
        {activo ? <IconVolume size={20} aria-hidden /> : <IconVolumeOff size={20} aria-hidden />}
      </ActionIcon>
    </Tooltip>
  );
}
