import { Alert, Badge, Button, Group, Loader, Paper, Progress, SimpleGrid, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent, type ReactNode } from 'react';
import { api, marcarActividad } from '../shared/api';
import { COLOR_ESTADO, NOMBRE_ESTADO, enCurso, tamanoLegible } from '../shared/exportacion';
import { conteo, fechaHora, hoyBogota } from '../shared/formato';
import { TablaAdaptable } from '../shared/TablaAdaptable';
import { useValidacion } from '../shared/validacion';
import {
  COLOR_ESTADO_MES,
  NOMBRE_ESTADO_MES,
  cuantosMeses,
  descargable,
  erroresPeriodo,
  estadoExportacion,
  intervaloSondeoHechos,
  mesDe,
  periodoTexto,
  progreso,
  rutaLeeme,
  rutaMes,
  solicitud,
  textoProgreso,
  valorMes,
  type ExportacionHechos,
} from './exportacionHechos';

const CLAVE = ['analitica', 'exportaciones-hechos'];

/**
 * Sección «Exportación de hechos por periodo» de «Cargues del OPL» (solo el Administrador; A7.2, duda AN.14): pide uno o varios meses,
 * muestra el progreso por mes mientras se generan en segundo plano y ofrece la descarga de cada .csv.gz y del LEEME (enlace directo).
 */
export function PanelExportacionHechos() {
  const qc = useQueryClient();
  const [desde, setDesde] = useState('');
  const [hasta, setHasta] = useState('');
  const v = useValidacion({ etiquetas: { desde: 'Mes inicial', hasta: 'Mes final' }, testid: 'error-exportacion-hechos' });
  const lista = useQuery({
    queryKey: CLAVE,
    queryFn: () => api<ExportacionHechos[]>('/api/analitica/exportaciones-hechos'),
    refetchInterval: (q) => intervaloSondeoHechos(q.state.data),
    refetchIntervalInBackground: true,
  });
  const pedir = useMutation({
    mutationFn: () => api<ExportacionHechos>('/api/analitica/exportaciones-hechos', { metodo: 'POST', cuerpo: solicitud(desde, hasta) }),
    onSuccess: () => v.reiniciar(),
    onError: (e) => v.errorServidor(e, 'No se pudo pedir la exportación de hechos.'),
    onSettled: () => qc.invalidateQueries({ queryKey: CLAVE }),
  });
  const pedidos = lista.data ?? [];
  const hayEnCurso = pedidos.some(enCurso);
  const meses = cuantosMeses(desde, hasta);

  function enviar(ev: FormEvent) {
    ev.preventDefault();
    if (v.validar(erroresPeriodo(desde, hasta, mesDe(hoyBogota())))) pedir.mutate();
  }

  return (
    <Stack gap="sm" data-testid="exportacion-hechos">
      <Title order={2} size="h4" mt="md">
        Exportación de hechos por periodo
      </Title>
      <Text size="sm">
        Descarga los hechos de dispensación de los cargues aplicados, un archivo por mes (CSV separado por «;», UTF-8 con BOM y coma decimal,
        comprimido en .csv.gz), con su huella SHA-256 y un LEEME con el periodo, el corte y las filas de cada mes. El paciente sale solo como
        seudónimo. Cada fila trae su cargue y su línea de origen.
      </Text>
      <Alert color="yellow" title="Datos de salud seudonimizados (Ley 1581 de 2012)">
        Guarde los archivos en un lugar seguro y no los envíe por correo. La solicitud y cada descarga quedan en la bitácora. Los archivos se borran
        cuando vencen.
      </Alert>
      <form ref={v.ref} noValidate onSubmit={enviar}>
        <Group align="end" gap="sm" wrap="wrap">
          <TextInput
            label="Mes inicial"
            name="desde"
            type="month"
            placeholder="aaaa-mm"
            value={desde}
            onChange={(e) => setDesde(e.currentTarget.value)}
            error={v.error('desde')}
            w={180}
          />
          <TextInput
            label="Mes final"
            name="hasta"
            type="month"
            placeholder="aaaa-mm (opcional)"
            description="Vacío: solo el mes inicial"
            value={hasta}
            onChange={(e) => setHasta(e.currentTarget.value)}
            error={v.error('hasta')}
            w={200}
          />
          <Button type="submit" loading={pedir.isPending} disabled={hayEnCurso}>
            Exportar hechos{meses > 1 ? ` (${conteo(meses)} meses)` : ''}
          </Button>
          {hayEnCurso && (
            <Text size="sm" c="dimmed">
              Hay una exportación de hechos en curso.
            </Text>
          )}
        </Group>
        {v.resumen}
      </form>

      <Title order={3} size="h5" mt="sm">
        Exportaciones de hechos
      </Title>
      {lista.error && <Alert color="red">{lista.error.message}</Alert>}
      {lista.isLoading && <Loader />}
      {lista.data && pedidos.length === 0 && <Text c="dimmed">Aún no ha exportado hechos.</Text>}
      {pedidos.map((p) => (
        <FilaExportacion key={p.id} p={p} />
      ))}
    </Stack>
  );
}

function Dato({ titulo, children }: { titulo: string; children: ReactNode }) {
  return (
    <div>
      <Text size="xs" c="dimmed">
        {titulo}
      </Text>
      <Text size="sm" style={{ overflowWrap: 'anywhere' }}>
        {children}
      </Text>
    </div>
  );
}

function FilaExportacion({ p }: { p: ExportacionHechos }) {
  const estado = estadoExportacion(p);
  return (
    <Paper withBorder p="sm" data-testid={'exportacion-hechos-' + p.id}>
      <Group justify="space-between" gap="xs" wrap="wrap">
        <Text fw={700}>
          {periodoTexto(p)} · solicitada el {fechaHora(p.creado)}
        </Text>
        <Group gap="xs">
          {enCurso(p) && <Loader size="xs" />}
          <Badge color={COLOR_ESTADO[estado]} data-testid="estado">
            {NOMBRE_ESTADO[estado]}
          </Badge>
        </Group>
      </Group>
      <SimpleGrid cols={{ base: 2, sm: 4 }} spacing="xs" mt="xs">
        <Dato titulo="Corte">
          {fechaHora(p.corte)}
          {p.corteCargueId ? ` · cargue ${p.corteCargueId}` : ''}
        </Dato>
        <Dato titulo="Progreso">{textoProgreso(p)}</Dato>
        <Dato titulo="Filas · tamaño">
          {conteo(p.filas)} · {tamanoLegible(p.tamano)}
        </Dato>
        <Dato titulo="Vence">{p.vence ? fechaHora(p.vence) : '—'}</Dato>
      </SimpleGrid>
      {enCurso(p) && <Progress value={progreso(p)} mt="xs" aria-label={`Progreso de la exportación ${p.id}`} />}
      {p.error && estado !== 'vencida' && (
        <Alert color="red" mt="xs" data-testid="error-exportacion-hechos-meses">
          {p.error}
        </Alert>
      )}
      {estado === 'vencida' && (
        <Text size="sm" c="dimmed" mt="xs">
          Venció{p.vence ? ` el ${fechaHora(p.vence)}` : ''} y sus archivos se borraron. Pida una nueva si la necesita.
        </Text>
      )}
      <TablaAdaptable>
        <Table striped withTableBorder mt="xs" miw={760} data-testid="meses-exportacion-hechos">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Mes</Table.Th>
              <Table.Th>Estado</Table.Th>
              <Table.Th ta="right">Filas</Table.Th>
              <Table.Th ta="right">Valor total</Table.Th>
              <Table.Th ta="right">Tamaño</Table.Th>
              <Table.Th>SHA-256</Table.Th>
              <Table.Th>Archivo</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {p.archivos.map((a) => (
              <Table.Tr key={a.mes} data-testid={`mes-${a.mes}`}>
                <Table.Td>{a.mes}</Table.Td>
                <Table.Td>
                  <Badge color={COLOR_ESTADO_MES[a.estado]} variant="light">
                    {NOMBRE_ESTADO_MES[a.estado]}
                  </Badge>
                </Table.Td>
                <Table.Td ta="right">{a.filas === null ? '—' : conteo(a.filas)}</Table.Td>
                <Table.Td ta="right">{valorMes(a)}</Table.Td>
                <Table.Td ta="right">{tamanoLegible(a.tamano)}</Table.Td>
                <Table.Td maw={220}>
                  <Text size="xs" ff="monospace" style={{ overflowWrap: 'anywhere' }}>
                    {a.sha256 ?? '—'}
                  </Text>
                </Table.Td>
                <Table.Td>
                  {descargable(p, a) ? (
                    <Button
                      component="a"
                      href={rutaMes(p.id, a.mes)}
                      download
                      size="compact-sm"
                      variant="light"
                      onClick={() => marcarActividad()}
                      aria-label={`Descargar los hechos de ${a.mes}`}
                    >
                      Descargar
                    </Button>
                  ) : (
                    '—'
                  )}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </TablaAdaptable>
      {p.leeme && estado !== 'vencida' && (
        <Group mt="xs">
          <Button
            component="a"
            href={rutaLeeme(p.id)}
            download
            variant="default"
            size="compact-sm"
            onClick={() => marcarActividad()}
            aria-label={`Descargar el LEEME de la exportación ${p.id}`}
          >
            Descargar LEEME
          </Button>
        </Group>
      )}
    </Paper>
  );
}
