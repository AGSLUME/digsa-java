import { Alert, Badge, Button, Loader, Stack, Table, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { api, ErrorApi } from '../shared/api';
import { descargarReporte } from '../shared/descargaReporte';
import { type Descarga, estadoDescarga, hayEnCurso, recienTerminadas, tamano } from '../shared/reportes';
import { TablaAdaptable } from '../shared/TablaAdaptable';

export const CLAVE_DESCARGAS = ['reportes', 'descargas'];

/** Códigos de las descargas que siguieron en segundo plano (202) en esta sesión: se avisa cuando terminen. */
const seguidas = new Set<string>();

/** Sigue una descarga diferida para avisar cuando quede lista o falle. */
export function seguirDescarga(codigo: string) {
  if (codigo) seguidas.add(codigo);
}

/**
 * «Descargas del día» (Anexo 5, 2.7; mockup): lo que el usuario pidió hoy, disponible hasta las 23:59 para volver a bajarlo sin
 * recargar el sistema. Mientras algo se genera en segundo plano consulta cada 5 segundos y avisa cuando queda listo.
 */
export function DescargasDelDia() {
  const anteriores = useRef<Descarga[] | null>(null);
  const [bajando, setBajando] = useState<ReadonlySet<string>>(new Set());
  const clienteConsultas = useQueryClient();
  const descargas = useQuery({
    queryKey: CLAVE_DESCARGAS,
    queryFn: () => api<Descarga[]>('/api/reportes/descargas'),
    refetchInterval: (q) => (hayEnCurso(q.state.data ?? []) ? 5000 : false),
  });

  useEffect(() => {
    const ahora = descargas.data;
    if (!ahora) return;
    for (const d of recienTerminadas(anteriores.current ?? [], ahora, seguidas)) {
      seguidas.delete(d.codigo);
      if (d.estado === 'lista') {
        notifications.show({ color: 'green', message: `Listo para descargar: ${d.titulo} (${d.formato.toUpperCase()}).` });
      } else {
        notifications.show({
          color: 'red',
          autoClose: false,
          message: `No se pudo generar ${d.titulo} (${d.formato.toUpperCase()}): ${d.error ?? 'pídalo de nuevo.'}`,
        });
      }
    }
    anteriores.current = ahora;
  }, [descargas.data]);

  const bajar = async (d: Descarga) => {
    setBajando((b) => new Set(b).add(d.codigo));
    try {
      await descargarReporte(`/api/reportes/descargas/${encodeURIComponent(d.codigo)}`);
    } catch (e) {
      notifications.show({ color: 'red', message: e instanceof ErrorApi ? e.message : 'No se pudo descargar el reporte.' });
      // Ya no está (venció o se borró): la lista se pone al día
      if (e instanceof ErrorApi && e.estado === 404) void clienteConsultas.invalidateQueries({ queryKey: CLAVE_DESCARGAS });
    } finally {
      setBajando((b) => {
        const n = new Set(b);
        n.delete(d.codigo);
        return n;
      });
    }
  };

  if (descargas.isLoading) return <Loader />;
  // Sin datos, el error ocupa el lugar de la tabla; con datos, la tabla se conserva y el aviso permite reintentar
  if (descargas.error && !descargas.data) return <ErrorConReintento mensaje={descargas.error.message} reintentar={() => void descargas.refetch()} />;
  const lista = descargas.data ?? [];

  return (
    <Stack data-testid="descargas-del-dia">
      {descargas.error && (
        <ErrorConReintento mensaje={`No se pudo actualizar la lista: ${descargas.error.message}`} reintentar={() => void descargas.refetch()} />
      )}
      <Text size="sm" c="dimmed">
        Los archivos que pidió hoy quedan disponibles hasta las 23:59 para volver a bajarlos sin generarlos de nuevo. Al terminar el día se borran; su
        código de verificación se sigue pudiendo verificar.
      </Text>
      {lista.length === 0 ? (
        <Text size="sm">Hoy no ha pedido reportes.</Text>
      ) : (
        <TablaAdaptable>
          <Table striped miw={760} data-testid="tabla-descargas">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Reporte</Table.Th>
                <Table.Th>Formato</Table.Th>
                <Table.Th>Solicitado</Table.Th>
                <Table.Th>Estado</Table.Th>
                <Table.Th>Registros</Table.Th>
                <Table.Th>Tamaño</Table.Th>
                <Table.Th>Disponible hasta</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {lista.map((d) => (
                <Table.Tr key={d.codigo} data-testid={`descarga-${d.codigo}`}>
                  <Table.Td>
                    <Text size="sm">{d.titulo}</Text>
                    <Text size="xs" c="dimmed" ff="monospace">
                      {d.codigo}
                    </Text>
                  </Table.Td>
                  <Table.Td>{d.formato.toUpperCase()}</Table.Td>
                  <Table.Td>{d.solicitado}</Table.Td>
                  <Table.Td>
                    <Badge color={estadoDescarga(d.estado).color} variant="light">
                      {estadoDescarga(d.estado).texto}
                    </Badge>
                    {d.error && (
                      <Text size="xs" c="red">
                        {d.error}
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td>{d.filas ?? ''}</Table.Td>
                  <Table.Td>{tamano(d.tamano)}</Table.Td>
                  <Table.Td>{d.disponibleHasta}</Table.Td>
                  <Table.Td>
                    {d.estado === 'lista' && (
                      <Button
                        size="sm"
                        variant="light"
                        loading={bajando.has(d.codigo)}
                        aria-label={`Descargar ${d.titulo} en ${d.formato.toUpperCase()} (${d.codigo})`}
                        onClick={() => void bajar(d)}
                      >
                        Descargar
                      </Button>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </TablaAdaptable>
      )}
    </Stack>
  );
}

function ErrorConReintento({ mensaje, reintentar }: { mensaje: string; reintentar: () => void }) {
  return (
    <Alert color="red">
      <Stack gap="xs" align="flex-start">
        <Text size="sm">{mensaje}</Text>
        <Button size="xs" variant="light" color="red" onClick={reintentar}>
          Reintentar
        </Button>
      </Stack>
    </Alert>
  );
}
