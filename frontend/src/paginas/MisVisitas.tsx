import { Alert, Badge, Button, Card, Center, Group, Loader, SimpleGrid, Stack, Text, UnstyledButton } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { colorEstado, horaCorta } from '../shared/agenda';
import { api } from '../shared/api';
import { fecha } from '../shared/formato';
import { EncabezadoPagina } from '../shared/EncabezadoPagina';

interface MiVisita {
  id: number;
  numero: string;
  fecha: string;
  hora: string | null;
  estado: string;
  estadoNombre: string;
  tipo: string;
  punto: string;
  unidad: string;
  municipio: string | null;
  lider: boolean;
  partes: string[];
}

/** Visitas del auditor (por ejecutar, en ejecución y cerradas recientes) con las partes del checklist que le tocan. */
export function MisVisitas() {
  const navegar = useNavigate();
  const lista = useQuery({ queryKey: ['agenda', 'mis-visitas'], queryFn: () => api<MiVisita[]>('/api/mis-visitas') });

  return (
    <Stack>
      <EncabezadoPagina titulo="Mis visitas" fuente="Anexo 5 · 4.5" />
      {lista.error && <Alert color="red">{lista.error.message}</Alert>}
      {lista.isLoading && (
        <Center py="xl">
          <Loader />
        </Center>
      )}
      {lista.data && lista.data.length === 0 && <Text c="dimmed">No tiene visitas asignadas.</Text>}
      <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }}>
        {(lista.data ?? []).map((v) => (
          <Card key={v.id} withBorder data-testid={'fila-visita-' + v.numero}>
            <UnstyledButton w="100%" onClick={() => navegar(`/mis-visitas/visita/${v.id}`)} aria-label={'Abrir la visita ' + v.numero}>
              <Group justify="space-between" wrap="nowrap" gap="xs">
                <Text fw={600}>{v.numero}</Text>
                <Group gap={4}>
                  {v.lider && <Badge variant="outline">Líder</Badge>}
                  <Badge color={colorEstado(v.estado)}>{v.estadoNombre}</Badge>
                </Group>
              </Group>
              <Text size="sm" mt={4}>
                {fecha(v.fecha)} {horaCorta(v.hora)} · {v.tipo}
              </Text>
              <Text size="sm">
                {v.punto} ({v.unidad})
              </Text>
              <Text size="sm" c="dimmed">
                {v.municipio ?? '—'}
              </Text>
              <Text size="sm" mt={4} data-testid="mis-partes">
                Mis partes: {v.partes.length > 0 ? v.partes.join(', ') : 'ninguna'}
              </Text>
            </UnstyledButton>
            {v.estado === 'ejecucion' && (
              <Group justify="flex-end" mt="xs">
                <Button size="xs" variant="light" onClick={() => navegar(`/visita/${v.id}`)} aria-label={'Formulario de la visita ' + v.numero}>
                  Formulario
                </Button>
              </Group>
            )}
          </Card>
        ))}
      </SimpleGrid>
    </Stack>
  );
}
