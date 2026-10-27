import { Alert, Badge, Button, Group, Loader, ScrollArea, Select, Stack, Table, Text } from '@mantine/core';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../shared/api';
import { segundos, type Tiempos } from '../shared/reportes';

/**
 * Tiempos de generación por reporte (Anexo 5, 2.7), para el Administrador: p50, p95 y máximo en pantalla y por formato, y cuántas
 * generaciones pasaron el 80 % del plazo (las que dispararon la alerta «reporte lento»).
 */
export function TiemposReportes() {
  const [dias, setDias] = useState('30');
  // Al cambiar el periodo se conserva la tabla anterior mientras llega la nueva: el selector no se desmonta ni pierde el foco
  const tiempos = useQuery({
    queryKey: ['reportes', 'tiempos', dias],
    queryFn: () => api<Tiempos>(`/api/reportes/tiempos?dias=${dias}`),
    placeholderData: keepPreviousData,
  });

  if (tiempos.isPending) return <Loader aria-label="Cargando los tiempos" />;
  if (!tiempos.data)
    return (
      <Alert color="red">
        <Group justify="space-between">
          <Text size="sm">{tiempos.error?.message ?? 'No se pudieron cargar los tiempos.'}</Text>
          <Button size="xs" variant="light" color="red" onClick={() => void tiempos.refetch()}>
            Reintentar
          </Button>
        </Group>
      </Alert>
    );
  const t = tiempos.data;

  return (
    <Stack data-testid="tiempos-reportes">
      <Text size="sm" c="dimmed">
        Plazos de 2.7: {segundos(t.plazoVerMs)} para ver en pantalla y {segundos(t.plazoExportarMs)} para exportar. Pasar el 80 % dispara la alerta
        «reporte lento» al Administrador sin cortar el reporte; solo se corta al llegar al tope técnico (una consulta colgada). Las fallidas cuentan
        con el tiempo que alcanzaron a correr.
      </Text>
      <Select
        label="Periodo"
        w={220}
        data={[
          { value: '1', label: 'Hoy' },
          { value: '7', label: 'Últimos 7 días' },
          { value: '30', label: 'Últimos 30 días' },
          { value: '90', label: 'Últimos 90 días' },
        ]}
        value={dias}
        onChange={(v) => setDias(v ?? '30')}
        allowDeselect={false}
        rightSection={tiempos.isFetching ? <Loader size="xs" aria-label="Actualizando" /> : undefined}
      />
      {tiempos.error && <Alert color="red">No se pudo actualizar: {tiempos.error.message}</Alert>}
      {t.tiempos.length === 0 ? (
        <Text size="sm">No hay generaciones en el periodo.</Text>
      ) : (
        <ScrollArea type="auto">
          <Table striped miw={760} data-testid="tabla-tiempos">
            <Table.Caption>Tiempos de generación de los reportes en el periodo elegido</Table.Caption>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Reporte</Table.Th>
                <Table.Th>En pantalla o formato</Table.Th>
                <Table.Th>Generaciones</Table.Th>
                <Table.Th>p50</Table.Th>
                <Table.Th>p95</Table.Th>
                <Table.Th>Máximo</Table.Th>
                <Table.Th>Sobre el 80 % del plazo</Table.Th>
                <Table.Th>Fallidas o cortadas</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {t.tiempos.map((x) => (
                <Table.Tr key={`${x.reporte}-${x.tipo}`}>
                  <Table.Td>{x.titulo}</Table.Td>
                  <Table.Td>{x.tipo === 'pantalla' ? 'En pantalla' : x.tipo.toUpperCase()}</Table.Td>
                  <Table.Td>{x.generaciones}</Table.Td>
                  <Table.Td>{segundos(x.p50)}</Table.Td>
                  <Table.Td>{segundos(x.p95)}</Table.Td>
                  <Table.Td>{segundos(x.maximo)}</Table.Td>
                  <Table.Td>
                    {x.lentas > 0 ? (
                      <Badge color="orange" variant="light">
                        {x.lentas}
                      </Badge>
                    ) : (
                      0
                    )}
                  </Table.Td>
                  <Table.Td>{x.fallidas}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      )}
    </Stack>
  );
}
