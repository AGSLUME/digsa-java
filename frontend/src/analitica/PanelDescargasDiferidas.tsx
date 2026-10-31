import { Alert, Badge, Button, Card, Group, Loader, Progress, Table, Text, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api, descargar } from '../shared/api';
import { COLOR_ESTADO, NOMBRE_ESTADO, enCurso, estadoVisible, tamanoLegible } from '../shared/exportacion';
import { conteo, fechaHora } from '../shared/formato';
import { TablaAdaptable } from '../shared/TablaAdaptable';
import {
  CLAVE_DESCARGAS_DIFERIDAS,
  RUTA_DESCARGAS_DIFERIDAS,
  descargableDiferida,
  intervaloSondeoDiferidas,
  mensajeErrorDescarga,
  msHastaProximoVencimiento,
  queDescarga,
  refrescarTrasError,
  rutaArchivoDiferido,
  textoAvance,
  type DescargaDiferida,
} from './descargasDiferidas';

/**
 * «Mis descargas del día» de analítica (A7.3, duda AN.16): los CSV de registros de origen grandes que el usuario pidió y que se generan
 * en segundo plano, con su avance (en cola, generando, lista) y la descarga cuando quedan listos; vencen a las 24 h. Se muestra solo si
 * hay alguna. Mientras una está en curso se consulta cada 3 s; una lista se repinta como vencida al llegar su vencimiento, sin mantener
 * el sondeo. La descarga usa el helper de la aplicación (errores 403, 409 y 410 con su mensaje). Duda AN.39: unificar con «Descargas del
 * día» de reportes.
 */
export function PanelDescargasDiferidas() {
  const qc = useQueryClient();
  const lista = useQuery({
    queryKey: CLAVE_DESCARGAS_DIFERIDAS,
    queryFn: () => api<DescargaDiferida[]>(RUTA_DESCARGAS_DIFERIDAS),
    refetchInterval: (q) => intervaloSondeoDiferidas(q.state.data),
    refetchIntervalInBackground: true,
  });
  const [, repintar] = useState(0);
  const [bajando, setBajando] = useState<number | null>(null);
  const descargas = lista.data ?? [];

  // Un solo temporizador hasta el próximo vencimiento: al llegar, la fila se ve «Vencida» y pierde el botón
  useEffect(() => {
    const ms = msHastaProximoVencimiento(lista.data);
    if (ms === null) return undefined;
    const t = setTimeout(() => repintar((x) => x + 1), Math.min(ms + 500, 2_147_000_000));
    return () => clearTimeout(t);
  }, [lista.data]);

  const bajar = async (d: DescargaDiferida) => {
    setBajando(d.id);
    try {
      await descargar(rutaArchivoDiferido(d.id));
    } catch (e) {
      notifications.show({ color: 'red', autoClose: 10000, message: mensajeErrorDescarga(e) });
      if (refrescarTrasError(e)) await qc.invalidateQueries({ queryKey: CLAVE_DESCARGAS_DIFERIDAS });
    } finally {
      setBajando(null);
    }
  };

  if (!lista.error && descargas.length === 0) return null;
  return (
    <Card withBorder data-testid="descargas-diferidas">
      <Group justify="space-between" gap="xs" wrap="wrap">
        <Title order={2} size="h4">
          Mis descargas del día
        </Title>
        {descargas.some(enCurso) && <Loader size="xs" aria-label="Generando descargas" />}
      </Group>
      <Text size="sm" c="dimmed" mb="xs">
        Los CSV de registros de origen con muchos registros se generan en segundo plano con el mismo corte, filtros y alcance de cuando los pidió.
        Quedan aquí 24 horas y luego se borran.
      </Text>
      {lista.error && <Alert color="red">{lista.error.message}</Alert>}
      <TablaAdaptable>
        <Table striped withTableBorder miw={720}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Descarga</Table.Th>
              <Table.Th>Avance</Table.Th>
              <Table.Th ta="right">Registros</Table.Th>
              <Table.Th>Solicitada</Table.Th>
              <Table.Th>Vence</Table.Th>
              <Table.Th>Archivo</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {descargas.map((d) => {
              const estado = estadoVisible(d);
              return (
                <Table.Tr key={d.id} data-testid={`descarga-diferida-${d.id}`}>
                  <Table.Td>
                    <Text size="sm" fw={600}>
                      {queDescarga(d)}
                    </Text>
                    <Text size="xs" c="dimmed">
                      Corte: cargue {d.corteCargueId}
                    </Text>
                  </Table.Td>
                  <Table.Td miw={200}>
                    <Group gap="xs" wrap="nowrap">
                      <Badge color={COLOR_ESTADO[estado]} variant="light" data-testid="estado">
                        {NOMBRE_ESTADO[estado]}
                      </Badge>
                      <Text size="xs">{textoAvance(d)}</Text>
                    </Group>
                    {enCurso(d) && (
                      <Progress value={d.estado === 'generando' ? 100 : 15} striped animated mt={4} aria-label={`Avance de la descarga ${d.id}`} />
                    )}
                    {estado === 'fallida' && d.error && (
                      <Text size="xs" c="red" mt={4}>
                        {d.error}
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td ta="right">{conteo(d.registros)}</Table.Td>
                  <Table.Td>{fechaHora(d.creado)}</Table.Td>
                  <Table.Td>{d.vence ? fechaHora(d.vence) : '—'}</Table.Td>
                  <Table.Td>
                    {descargableDiferida(d) ? (
                      <>
                        <Button
                          size="compact-sm"
                          variant="light"
                          loading={bajando === d.id}
                          disabled={bajando !== null && bajando !== d.id}
                          onClick={() => void bajar(d)}
                          aria-label={`Descargar ${queDescarga(d)}`}
                        >
                          Descargar ({tamanoLegible(d.tamano)})
                        </Button>
                        {d.sha256 && (
                          <Text size="xs" ff="monospace" c="dimmed" style={{ overflowWrap: 'anywhere' }} title="SHA-256 del archivo">
                            SHA-256 {d.sha256.slice(0, 16)}…
                          </Text>
                        )}
                      </>
                    ) : (
                      '—'
                    )}
                  </Table.Td>
                </Table.Tr>
              );
            })}
          </Table.Tbody>
        </Table>
      </TablaAdaptable>
    </Card>
  );
}
