import { Alert, Badge, Box, Card, Center, Group, Loader, ScrollArea, Select, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../shared/api';
import { conteo } from '../shared/formato';
import {
  anchoBarra,
  botonQuitar,
  colorSemaforo,
  consultaTablero,
  etiquetaUmbral,
  type FiltrosTablero,
  type OpcionesPresupuesto,
  type TableroPresupuesto,
} from '../shared/presupuesto';

function Caja({ titulo, valor, sub, testid, color }: { titulo: string; valor: string; sub?: string; testid: string; color?: string }) {
  return (
    <Card withBorder padding="sm" data-testid={testid}>
      <Text size="xs" c="dimmed">
        {titulo}
      </Text>
      <Text size="lg" fw={700} ff="monospace" c={color} style={{ overflowWrap: 'anywhere' }}>
        {valor}
      </Text>
      {sub && (
        <Text size="xs" c="dimmed">
          {sub}
        </Text>
      )}
    </Card>
  );
}

/** Barra de % de ejecución con marcas en 25, 50, 75 y 100 % (mockup «ESM con mayor ejecución»); pasa del 100 % sin desbordar. */
function BarraUmbrales({ porcentaje, semaforo, etiqueta }: { porcentaje: string | null; semaforo: string; etiqueta: string }) {
  const ancho = anchoBarra(porcentaje);
  return (
    <Box
      role="meter"
      aria-label={etiqueta}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.min(ancho, 100)}
      style={{ position: 'relative', height: 14, borderRadius: 7, background: 'var(--mantine-color-gray-2)', overflow: 'hidden' }}
    >
      <Box style={{ width: `${Math.min(ancho, 100)}%`, height: '100%', background: `var(--mantine-color-${colorSemaforo(semaforo)}-6)` }} />
      {[25, 50, 75].map((m) => (
        <Box key={m} style={{ position: 'absolute', top: 0, bottom: 0, left: `${m}%`, width: 2, background: 'var(--mantine-color-dark-3)' }} />
      ))}
    </Box>
  );
}

/**
 * Tablero del presupuesto (mockup «Presupuesto del contrato de medicamentos»): presupuesto vigente, ejecutado, saldo y ESM sobre la
 * proyección; ejecución por Fuerza y modalidad; ESM con mayor ejecución; detalle por línea con lo proyectado al corte.
 */
export function TableroPresupuesto({ o }: { o: OpcionesPresupuesto }) {
  const abierta = o.vigencias.filter((v) => v.estado === 'abierta').at(-1) ?? o.vigencias.at(-1);
  const [f, setF] = useState<FiltrosTablero>({ vigencia: abierta ? String(abierta.id) : '', corte: '', fuerza: '', modalidad: '' });
  const consulta = consultaTablero(f);
  const t = useQuery({
    queryKey: ['presupuesto', 'tablero', consulta],
    queryFn: () => api<TableroPresupuesto>('/api/presupuesto/tablero' + (consulta ? '?' + consulta : '')),
    placeholderData: keepPreviousData,
  });
  if (t.isLoading) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    );
  }
  const d = t.data;
  const contrato = d?.vigencia === null;
  // Columnas: las modalidades con ejecución en la tabla (también una inactiva que la tenga), en el orden del catálogo
  const conDatos = new Set((d?.porFuerza ?? []).flatMap((x) => Object.keys(x.porModalidad)));
  const modalidades = o.modalidades.filter((m) => m.activa || conDatos.has(m.nombre)).map((m) => m.nombre);
  const nombreFuerza = (c: string) => o.fuerzas.find((x) => x.codigo === c)?.nombre ?? c;
  return (
    <Stack style={{ opacity: t.isPlaceholderData ? 0.6 : 1 }}>
      <Card withBorder>
        <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }}>
          <Select
            label="Vigencia"
            data={[{ value: '', label: 'Contrato (todas)' }, ...o.vigencias.map((v) => ({ value: String(v.id), label: String(v.anio) }))]}
            value={f.vigencia}
            allowDeselect={false}
            onChange={(v) => setF({ ...f, vigencia: v ?? '', corte: '' })}
            data-testid="tablero-vigencia"
          />
          <Select
            label="Corte"
            placeholder={contrato ? 'Solo por vigencia' : 'Mes actual'}
            disabled={contrato}
            clearable
            clearButtonProps={botonQuitar('el corte')}
            data={d?.cortes ?? []}
            value={f.corte || d?.corte || null}
            onChange={(v) => setF({ ...f, corte: v ?? '' })}
            data-testid="tablero-corte"
          />
          <Select
            label="Fuerza"
            placeholder="Todas"
            clearable
            clearButtonProps={botonQuitar('la fuerza')}
            data={o.fuerzas.map((x) => ({ value: x.codigo, label: x.nombre }))}
            value={f.fuerza || null}
            onChange={(v) => setF({ ...f, fuerza: v ?? '' })}
          />
          <Select
            label="Modalidad"
            placeholder="Todas"
            clearable
            clearButtonProps={botonQuitar('la modalidad')}
            data={o.modalidades.map((m) => ({ value: m.codigo, label: m.nombre }))}
            value={f.modalidad || null}
            onChange={(v) => setF({ ...f, modalidad: v ?? '' })}
          />
        </SimpleGrid>
        {t.error && (
          <Alert color="red" mt="xs">
            {t.error.message}
            {d ? ' Las cifras de abajo son de la consulta anterior.' : ''}
          </Alert>
        )}
      </Card>
      {d && <TableroDatos d={d} contrato={contrato} modalidades={modalidades} nombreFuerza={nombreFuerza} />}
    </Stack>
  );
}

function TableroDatos({
  d,
  contrato,
  modalidades,
  nombreFuerza,
}: {
  d: TableroPresupuesto;
  contrato: boolean;
  modalidades: string[];
  nombreFuerza: (c: string) => string;
}) {
  return (
    <>
      <Card withBorder padding="xs">
        <Text size="xs" c="dimmed">
          Alcance: {d.ambito}. Ejecutado = {d.base}.{' '}
          {contrato
            ? 'La vista «Contrato» suma todas las vigencias y no compara con la proyección.'
            : `Corte: ${d.corte}: lo ejecutado se cuenta hasta ese mes, igual que lo proyectado.`}
        </Text>
      </Card>

      <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }}>
        <Caja
          titulo="Presupuesto vigente"
          valor={`$ ${d.cajas.presupuestoVigente}`}
          sub={d.cajas.adiciones === 1 ? 'Incluye 1 adición' : `Incluye ${conteo(d.cajas.adiciones)} adiciones`}
          testid="caja-vigente"
        />
        <Caja
          titulo="Ejecutado"
          valor={`$ ${d.cajas.ejecutado}`}
          sub={d.cajas.porcentaje ? `${d.cajas.porcentaje} %` : 'Sin presupuesto'}
          testid="caja-ejecutado"
        />
        <Caja titulo="Saldo" valor={`$ ${d.cajas.saldo}`} color={d.cajas.saldo.startsWith('-') ? 'red' : undefined} testid="caja-saldo" />
        <Caja
          titulo="ESM sobre la proyección"
          valor={contrato ? '—' : conteo(d.cajas.esmSobreProyeccion ?? 0)}
          sub={contrato ? 'Elija una vigencia' : `de ${conteo(d.cajas.esmTotal ?? 0)} ESM · proyección al corte ${d.cajas.porcentajeProyectado ?? '0,00'} %`}
          color={(d.cajas.esmSobreProyeccion ?? 0) > 0 ? 'orange' : undefined}
          testid="caja-proyeccion"
        />
      </SimpleGrid>

      <Card withBorder>
        <Title order={5}>Ejecución por Fuerza y modalidad</Title>
        <ScrollArea type="auto" mt="xs">
          <Table striped withTableBorder miw={760} fz="sm" data-testid="tabla-fuerzas">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Fuerza</Table.Th>
                {modalidades.map((m) => (
                  <Table.Th key={m} ta="right">
                    {m}
                  </Table.Th>
                ))}
                <Table.Th ta="right">Total ejecutado</Table.Th>
                <Table.Th ta="right">% ejecución</Table.Th>
                <Table.Th ta="right">Proyección</Table.Th>
                <Table.Th>Umbral</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {d.porFuerza.map((x) => (
                <Table.Tr key={x.fuerza}>
                  <Table.Td>{nombreFuerza(x.fuerza)}</Table.Td>
                  {modalidades.map((m) => (
                    <Table.Td key={m} ta="right" ff="monospace">
                      {x.porModalidad[m] ?? '0,00'}
                    </Table.Td>
                  ))}
                  <Table.Td ta="right" ff="monospace">
                    {x.ejecutado}
                  </Table.Td>
                  <Table.Td ta="right">{x.porcentaje ?? '—'}</Table.Td>
                  <Table.Td ta="right">{x.porcentajeProyectado ?? '—'}</Table.Td>
                  <Table.Td>
                    {x.umbral !== null ? (
                      <Badge color={colorSemaforo(x.semaforo)} variant="light">
                        {etiquetaUmbral(x.umbral, 'alcanzado')}
                      </Badge>
                    ) : (
                      '—'
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      </Card>

      <Card withBorder data-testid="mayor-ejecucion">
        <Title order={5}>ESM con mayor ejecución</Title>
        <Text size="xs" c="dimmed" mb="xs">
          Marcas en 25, 50, 75 y 100 %. Verde bajo el 75 %, ámbar del 75 al 99,99 % y rojo desde el 100 %.
        </Text>
        {d.mayorEjecucion.length === 0 && <Text size="sm">Aún no hay ejecución registrada.</Text>}
        <Stack gap="xs">
          {d.mayorEjecucion.map((e) => (
            <Box key={e.puntoId}>
              <Group justify="space-between" wrap="nowrap" gap="xs">
                <Text size="sm" truncate="end" style={{ minWidth: 0 }}>
                  {e.esm}
                </Text>
                <Text size="sm" ff="monospace" fw={600} style={{ flexShrink: 0 }}>
                  {e.porcentaje === null ? 'sin presupuesto' : `${e.porcentaje} %`}
                </Text>
              </Group>
              <BarraUmbrales porcentaje={e.porcentaje} semaforo={e.semaforo} etiqueta={`Ejecución de ${e.esm}`} />
              {e.umbralNotificado !== null && (
                <Text size="xs" c="dimmed">
                  {etiquetaUmbral(e.umbralNotificado, 'notificado')}
                </Text>
              )}
            </Box>
          ))}
        </Stack>
      </Card>

      <Card withBorder>
        <Title order={5}>Detalle por ESM y modalidad</Title>
        <ScrollArea type="auto" mt="xs">
          <Table striped withTableBorder miw={1000} fz="sm" data-testid="tabla-detalle">
            <Table.Thead>
              <Table.Tr>
                {contrato && <Table.Th>Vigencia</Table.Th>}
                <Table.Th>ESM</Table.Th>
                <Table.Th>Modalidad</Table.Th>
                <Table.Th ta="right">Asignado</Table.Th>
                <Table.Th ta="right">Facturado</Table.Th>
                <Table.Th ta="right">Reconocido</Table.Th>
                <Table.Th ta="right">Saldo</Table.Th>
                <Table.Th ta="right">%</Table.Th>
                {!contrato && <Table.Th ta="right">Proyectado al corte</Table.Th>}
                {!contrato && <Table.Th ta="right">Desviación</Table.Th>}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {d.detalle.map((x) => (
                <Table.Tr key={x.lineaId}>
                  {contrato && <Table.Td>{x.vigencia}</Table.Td>}
                  <Table.Td>
                    {x.esm} ({x.fuerza})
                  </Table.Td>
                  <Table.Td>{x.modalidadNombre}</Table.Td>
                  <Table.Td ta="right" ff="monospace">
                    {x.asignado}
                  </Table.Td>
                  <Table.Td ta="right" ff="monospace">
                    {x.facturado}
                  </Table.Td>
                  <Table.Td ta="right" ff="monospace">
                    {x.reconocido}
                  </Table.Td>
                  <Table.Td ta="right" ff="monospace" c={x.saldo.startsWith('-') ? 'red' : undefined}>
                    {x.saldo}
                  </Table.Td>
                  <Table.Td ta="right">
                    <Badge color={colorSemaforo(x.semaforo)} variant="light">
                      {x.porcentaje ?? 'sin asignado'}
                    </Badge>
                  </Table.Td>
                  {!contrato && (
                    <Table.Td ta="right" ff="monospace">
                      {x.proyectado ?? '—'}
                    </Table.Td>
                  )}
                  {!contrato && (
                    <Table.Td ta="right" c={x.sentido === 'exceso' ? 'red' : x.sentido === 'subejecucion' ? 'orange' : undefined}>
                      {x.desviacion === null || x.desviacion === undefined ? '—' : `${x.desviacion.startsWith('-') ? '' : '+'}${x.desviacion} %`}
                    </Table.Td>
                  )}
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      </Card>
    </>
  );
}

/** Caja del inicio (mockup L192): ejecución presupuestal nacional de la última vigencia abierta, con la proyección al corte. */
export function ResumenPresupuestoInicio() {
  const o = useQuery({ queryKey: ['presupuesto', 'opciones'], queryFn: () => api<OpcionesPresupuesto>('/api/presupuesto/opciones'), retry: false });
  const vigencia = o.data?.vigencias.filter((v) => v.estado === 'abierta').at(-1);
  const t = useQuery({
    queryKey: ['presupuesto', 'tablero', `vigencia=${vigencia?.id}`],
    queryFn: () => api<TableroPresupuesto>(`/api/presupuesto/tablero?vigencia=${vigencia!.id}`),
    enabled: vigencia !== undefined,
    retry: false,
  });
  if (!t.data) return null;
  return (
    <Box maw={360}>
      <Caja
        titulo={`Ejecución presupuestal ${t.data.ambito === 'Nacional' ? 'nacional' : `· ${t.data.ambito}`} · vigencia ${t.data.vigencia}`}
        valor={t.data.cajas.porcentaje ? `${t.data.cajas.porcentaje} %` : 'Sin presupuesto'}
        sub={t.data.cajas.porcentajeProyectado ? `Proyección al corte: ${t.data.cajas.porcentajeProyectado} %` : undefined}
        testid="inicio-presupuesto"
      />
    </Box>
  );
}
