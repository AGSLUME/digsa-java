import { Alert, Badge, Button, Center, Group, Loader, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api, ErrorApi } from '../shared/api';
import { conteo } from '../shared/formato';
import { Kpi } from '../shared/Kpi';
import { TablaAdaptable } from '../shared/TablaAdaptable';
import { COLOR_SEVERIDAD, filtrosIgnorados, noEvaluados, totalesSeveridad, type Atipicos } from './riesgo';
import type { EstadoAnalitica } from './tableros';

/** Filtros de la barra que este tablero aplica (con un solo valor). */
const ADMITIDOS = ['fuerza', 'esm', 'atc'] as const;

/** Filtros del tablero que aplican a los atípicos: corte, periodo, Fuerza, ESM y ATC (con un solo valor cada uno). */
function consulta(e: EstadoAnalitica): string {
  const p = new URLSearchParams();
  for (const k of ['corte', 'desde', 'hasta'] as const) {
    const v = e.generales[k];
    if (v) p.append(k, v);
  }
  for (const k of ADMITIDOS) {
    const v = e.filtros[k];
    const x = v?.[0];
    if (v?.length === 1 && x) p.append(k, x);
  }
  return p.toString();
}

/**
 * Tablero 11 «Comportamientos atípicos» (analitica-cohortes-alertas): alertas por severidad y tipo en el alcance y con los filtros de la
 * barra, los tipos no evaluados con su motivo y, para quien tiene la consola, las diez de mayor prioridad con umbral, periodo y dimensión.
 * El ESM ve solo los conteos de su establecimiento (sin lista).
 */
export function PanelAtipicos({ estado }: { estado: EstadoAnalitica }) {
  const datos = useQuery({
    queryKey: ['analitica', 'atipicos', consulta(estado)],
    queryFn: () => api<Atipicos>(`/api/analitica/atipicos?${consulta(estado)}`),
  });
  if (datos.isLoading) {
    return (
      <Center mih={160}>
        <Loader />
      </Center>
    );
  }
  if (datos.error || !datos.data) {
    return <Alert color="red">{datos.error instanceof ErrorApi ? datos.error.message : 'No se pudieron consultar los atípicos.'}</Alert>;
  }
  const a = datos.data;
  const t = totalesSeveridad(a.tipos);
  const sinEvaluar = noEvaluados(a.tipos);
  const ignorados = filtrosIgnorados(estado, ADMITIDOS);
  return (
    <Stack data-testid="panel-atipicos">
      <Group justify="space-between">
        <Title order={2}>Comportamientos atípicos</Title>
        {a.puedeVerConsola && (
          <Button size="xs" variant="subtle" component={Link} to="/analitica/alertas">
            Abrir la consola de alertas
          </Button>
        )}
      </Group>
      {a.corteTexto && (
        <Text size="xs" c="dimmed">
          {a.corteTexto}
        </Text>
      )}
      {ignorados.length > 0 && (
        <Alert color="yellow" data-testid="filtros-ignorados">
          Este tablero aplica el corte, el periodo y una Fuerza, un ESM o un ATC; no aplica: {ignorados.join(', ')}.
        </Alert>
      )}
      <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="sm">
        <Kpi titulo="Alertas en el alcance" valor={<span data-testid="atipicos-total">{conteo(a.total)}</span>} />
        {(['alta', 'media', 'baja'] as const).map((s) => (
          <Kpi key={s} titulo={`Severidad ${s}`} valor={conteo(t[s])} color={COLOR_SEVERIDAD[s] === 'yellow' ? undefined : COLOR_SEVERIDAD[s]} />
        ))}
      </SimpleGrid>
      <TablaAdaptable>
        <Table striped withTableBorder fz="sm" miw={520} aria-label="Alertas por tipo y severidad">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Tipo</Table.Th>
              <Table.Th>Alta</Table.Th>
              <Table.Th>Media</Table.Th>
              <Table.Th>Baja</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {a.tipos.map((x) => (
              <Table.Tr key={x.tipo}>
                <Table.Td>{x.nombre}</Table.Td>
                <Table.Td>{conteo(x.alta)}</Table.Td>
                <Table.Td>{conteo(x.media)}</Table.Td>
                <Table.Td>{conteo(x.baja)}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </TablaAdaptable>
      {sinEvaluar.length > 0 && (
        <Alert color="gray" title="Tipos no evaluados en la última ejecución">
          {sinEvaluar.map((x) => (
            <Text key={x.nombre} size="sm">
              {x.nombre}: {x.motivo}
            </Text>
          ))}
        </Alert>
      )}
      {a.soloConteos && (
        <Text size="xs" c="dimmed">
          Su perfil ve las cifras agregadas de su establecimiento; la consola de alertas es de los perfiles autorizados.
        </Text>
      )}
      {a.alertas && a.alertas.length > 0 && (
        <TablaAdaptable>
          <Table withTableBorder fz="xs" miw={720} aria-label="Alertas de mayor prioridad">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Severidad</Table.Th>
                <Table.Th>Alerta</Table.Th>
                <Table.Th>Alcance</Table.Th>
                <Table.Th>Dimensión</Table.Th>
                <Table.Th>Periodo</Table.Th>
                <Table.Th>Umbral</Table.Th>
                <Table.Th>Observado</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {a.alertas.map((x) => (
                <Table.Tr key={x.id}>
                  <Table.Td>
                    <Badge color={COLOR_SEVERIDAD[x.severidad]}>{x.severidad}</Badge>
                  </Table.Td>
                  <Table.Td>{x.tipoNombre}</Table.Td>
                  <Table.Td>{x.nivelTexto}</Table.Td>
                  <Table.Td>
                    {x.dimensionTexto} {x.valorTexto}
                  </Table.Td>
                  <Table.Td>{x.periodo}</Table.Td>
                  <Table.Td>{x.umbralTexto}</Table.Td>
                  <Table.Td>{x.observadoTexto}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </TablaAdaptable>
      )}
    </Stack>
  );
}
