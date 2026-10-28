import { Alert, Anchor, Badge, Center, Loader, Stack, Table, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { colorEstado, horaCorta } from '../shared/agenda';
import { api } from '../shared/api';
import { conteo, fecha, fechaHora, hora } from '../shared/formato';
import { EncabezadoPagina } from '../shared/EncabezadoPagina';
import { TablaAdaptable } from '../shared/TablaAdaptable';

interface VisitaSeguimiento {
  id: number;
  numero: string;
  fecha: string;
  hora: string | null;
  estado: string;
  estadoNombre: string;
  punto: string;
  lider: string;
  integrantes: number;
  iniciada: string | null;
  /** Fecha y hora de inicio en Bogotá (UI-20); en servidores anteriores no viene. */
  iniciadaEl?: string | null;
  ultimo: string | null;
}

interface Seguimiento {
  actualizado: string;
  visitas: VisitaSeguimiento[];
}

/** Seguimiento en tiempo real: visitas de hoy y en ejecución, actualizado cada 15 segundos. */
export function Seguimiento() {
  const datos = useQuery({
    queryKey: ['agenda', 'seguimiento'],
    queryFn: () => api<Seguimiento>('/api/agenda/seguimiento'),
    refetchInterval: 15000,
  });
  const visitas = datos.data?.visitas ?? [];

  return (
    <Stack>
      <EncabezadoPagina
        titulo="Seguimiento en tiempo real"
        fuente="Anexo 5 · 4.5"
        descripcion={datos.data && <>Actualizado a las {hora(datos.data.actualizado)}</>}
        acciones={
          <Anchor component={Link} to="/agenda">
            Volver a la agenda
          </Anchor>
        }
      />
      {datos.error && <Alert color="red">{datos.error.message}</Alert>}
      {datos.isLoading && (
        <Center py="xl">
          <Loader />
        </Center>
      )}
      {datos.data && visitas.length === 0 && <Text c="dimmed">No hay visitas hoy ni en ejecución.</Text>}
      {visitas.length > 0 && (
        <TablaAdaptable>
          <Table miw={820} data-testid="tabla-seguimiento">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Número</Table.Th>
                <Table.Th>Fecha y hora</Table.Th>
                <Table.Th>Punto</Table.Th>
                <Table.Th>Líder</Table.Th>
                <Table.Th>Estado</Table.Th>
                <Table.Th>Iniciada</Table.Th>
                <Table.Th>Último evento</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {visitas.map((v) => (
                <Table.Tr key={v.id} bg={v.estado === 'ejecucion' ? 'var(--mantine-color-orange-0)' : undefined} data-testid={'fila-visita-' + v.numero}>
                  <Table.Td>
                    <Anchor component={Link} to={`/agenda/visita/${v.id}`} fz="sm">
                      {v.numero}
                    </Anchor>
                  </Table.Td>
                  <Table.Td style={{ whiteSpace: 'nowrap' }}>
                    {fecha(v.fecha)} {horaCorta(v.hora)}
                  </Table.Td>
                  <Table.Td>{v.punto}</Table.Td>
                  <Table.Td>
                    {v.lider}
                    {v.integrantes > 1 && (
                      <Text size="xs" c="dimmed">
                        {conteo(v.integrantes)} integrantes
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td>
                    <Badge color={colorEstado(v.estado)}>{v.estadoNombre}</Badge>
                  </Table.Td>
                  <Table.Td>{v.iniciadaEl ? fechaHora(v.iniciadaEl) : hora(v.iniciada)}</Table.Td>
                  <Table.Td>{v.ultimo ?? '—'}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </TablaAdaptable>
      )}
    </Stack>
  );
}
