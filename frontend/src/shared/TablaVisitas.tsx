import { Anchor, Badge, Group, Stack, Table, Text, Tooltip } from '@mantine/core';
import { colorEstado, colorNivel, horaCorta, type FilaVisita } from './agenda';
import { conteo, fecha } from './formato';
import { TablaAdaptable } from './TablaAdaptable';

const TEXTO_NIVEL: Record<string, string> = { ok: 'En plazo', info: 'Información', advertencia: 'Advertencia', peligro: 'Atención' };

/** Lista de visitas de la agenda o del calendario; con «conAlertas» agrega la columna de alertas del coordinador. */
export function TablaVisitas({
  filas,
  alAbrir,
  conAlertas,
  testid,
}: {
  filas: FilaVisita[];
  alAbrir: (id: number) => void;
  conAlertas?: boolean;
  testid: string;
}) {
  return (
    <TablaAdaptable>
      <Table striped highlightOnHover miw={conAlertas ? 960 : 840} data-testid={testid}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Número</Table.Th>
            <Table.Th>Fecha y hora</Table.Th>
            <Table.Th>Tipo</Table.Th>
            <Table.Th>Punto</Table.Th>
            <Table.Th>Municipio</Table.Th>
            <Table.Th>Líder</Table.Th>
            <Table.Th>Estado</Table.Th>
            {conAlertas && <Table.Th>Alertas</Table.Th>}
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {filas.map((v) => (
            <Table.Tr key={v.id} data-testid={'fila-visita-' + v.numero} style={{ cursor: 'pointer' }} onClick={() => alAbrir(v.id)}>
              <Table.Td style={{ whiteSpace: 'nowrap' }}>
                <Anchor
                  component="button"
                  type="button"
                  fz="sm"
                  aria-label={'Abrir la visita ' + v.numero}
                  onClick={(e) => {
                    e.stopPropagation();
                    alAbrir(v.id);
                  }}
                >
                  {v.numero}
                </Anchor>
              </Table.Td>
              <Table.Td style={{ whiteSpace: 'nowrap' }}>
                {fecha(v.fecha)} {horaCorta(v.hora)}
              </Table.Td>
              <Table.Td>{v.tipoNombre}</Table.Td>
              <Table.Td>
                {v.punto}
                <Text size="xs" c="dimmed">
                  {v.unidad} · {v.fuerza}
                </Text>
              </Table.Td>
              <Table.Td>{v.municipio ?? '—'}</Table.Td>
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
              {conAlertas && (
                <Table.Td>
                  <Group gap={4}>
                    <Tooltip
                      multiline
                      w={280}
                      disabled={!v.alertas || v.alertas.length === 0}
                      label={
                        <Stack gap={2}>
                          {(v.alertas ?? []).map((a, i) => (
                            <Text key={i} size="xs">
                              {a.texto}
                            </Text>
                          ))}
                        </Stack>
                      }
                    >
                      <Badge color={colorNivel(v.nivel)} variant="light">
                        {v.alertas && v.alertas.length > 0 ? `${conteo(v.alertas.length)} ${v.alertas.length === 1 ? 'alerta' : 'alertas'}` : (TEXTO_NIVEL[v.nivel ?? ''] ?? 'Sin alertas')}
                      </Badge>
                    </Tooltip>
                    {v.equipoSinNotificar && (
                      <Badge color="orange" variant="outline">
                        Equipo sin notificar
                      </Badge>
                    )}
                  </Group>
                </Table.Td>
              )}
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </TablaAdaptable>
  );
}
