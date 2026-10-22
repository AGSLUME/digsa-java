import { Box, Button, Card, Group, Progress, Stack, Table, Text, Title } from '@mantine/core';
import { useState } from 'react';
import { maximo, porcentajeBarra, type Barra } from './tablero';

/**
 * Barra horizontal accesible (sin librería de gráficas): la etiqueta y el valor van en texto; la barra es decorativa
 * (aria-hidden), así un lector de pantalla lee «Etiqueta: valor».
 */
export function BarraHorizontal({
  etiqueta,
  valor,
  maximo: max,
  texto,
  color = 'blue',
}: {
  etiqueta: string;
  valor: number;
  maximo: number;
  /** Texto del valor; por omisión el número. */
  texto?: string;
  color?: string;
}) {
  return (
    <Box>
      <Group justify="space-between" gap="xs" wrap="nowrap">
        <Text size="sm" style={{ minWidth: 0, overflowWrap: 'anywhere' }}>
          {etiqueta}
        </Text>
        <Text size="sm" fw={600} style={{ whiteSpace: 'nowrap' }}>
          {texto ?? String(valor)}
        </Text>
      </Group>
      <Progress value={porcentajeBarra(valor, max)} color={color} size="md" aria-hidden />
    </Box>
  );
}

/** Gráfica de barras con su tabla («Ver tabla»), como en el dashboard del PHP. */
export function GraficaBarras({ titulo, barras, testid, color }: { titulo: string; barras: Barra[]; testid: string; color?: string }) {
  const [tabla, setTabla] = useState(false);
  const max = maximo(barras);
  return (
    <Card withBorder data-testid={testid}>
      <Group justify="space-between" mb="xs" wrap="wrap" gap="xs">
        <Title order={5}>{titulo}</Title>
        <Button size="compact-xs" variant="subtle" onClick={() => setTabla((t) => !t)} aria-expanded={tabla}>
          {tabla ? 'Ver barras' : 'Ver tabla'}
        </Button>
      </Group>
      {barras.length === 0 ? (
        <Text size="sm" c="dimmed">
          Sin visitas con estos filtros.
        </Text>
      ) : tabla ? (
        <Table striped>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{titulo.replace(/^Visitas por /, '').replace(/^./, (c) => c.toUpperCase())}</Table.Th>
              <Table.Th ta="right">Visitas</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {barras.map((b, i) => (
              <Table.Tr key={i + ':' + b.etiqueta}>
                <Table.Td>{b.etiqueta}</Table.Td>
                <Table.Td ta="right">{b.valor}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      ) : (
        <Stack gap="xs">
          {barras.map((b, i) => (
            <BarraHorizontal key={i + ':' + b.etiqueta} etiqueta={b.etiqueta} valor={Number(b.valor) || 0} maximo={max} color={color} />
          ))}
        </Stack>
      )}
    </Card>
  );
}
