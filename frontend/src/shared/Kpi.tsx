import { Card, Text } from '@mantine/core';
import type { ReactNode } from 'react';

/**
 * Tarjeta de indicador (U4): etiqueta de 12 px apagada, cifra de 22 px/700 con números tabulares y, si hay, un detalle. Una sola en
 * toda la aplicación (antes: Tarjeta del Dashboard, Caja de Riesgo y Caja del tablero de presupuesto). El color de la cifra es el de
 * la variante LUME (`red`, `orange`, `green`…), que llega a AA sobre blanco.
 */
export function Kpi({
  titulo,
  valor,
  detalle,
  color,
  testid,
}: {
  titulo: string;
  valor: ReactNode;
  detalle?: ReactNode;
  color?: string;
  testid?: string;
}) {
  return (
    <Card padding="md" data-testid={testid}>
      <Text fz={12} c="dimmed">
        {titulo}
      </Text>
      <Text fz={22} fw={700} lh={1.3} mt={4} c={color} style={{ overflowWrap: 'anywhere', fontVariantNumeric: 'tabular-nums' }}>
        {valor}
      </Text>
      {detalle && (
        <Text fz={12} c="dimmed" mt={2}>
          {detalle}
        </Text>
      )}
    </Card>
  );
}
