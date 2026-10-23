import { Alert, Center, Group, Loader, SegmentedControl, Select, SimpleGrid, Stack, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { COLOR_ESTADO, type FilaVisita } from '../shared/agenda';
import { api } from '../shared/api';
import { CalendarioMes, moverMes, NavegadorMes, rangoMes } from '../shared/CalendarioMes';
import { TablaVisitas } from '../shared/TablaVisitas';
import { EncabezadoPagina } from '../shared/EncabezadoPagina';

const ESTADOS: Record<string, string> = {
  programada: 'Programada',
  notificada: 'Notificada',
  reprogramada: 'Reprogramada',
  ejecucion: 'En ejecución',
  cerrada: 'Cerrada',
  cancelada: 'Cancelada',
};

/** Calendario de visitas para los perfiles de consulta (DIGSA, Sanidad, ESM): solo lectura, sin alertas de plazos. */
export function Calendario() {
  const navegar = useNavigate();
  const hoy = new Date();
  const [mes, setMes] = useState({ anio: hoy.getFullYear(), mes: hoy.getMonth() });
  const [vista, setVista] = useState<'lista' | 'calendario'>('calendario');
  const [estado, setEstado] = useState<string | null>(null);
  const { desde, hasta } = rangoMes(mes.anio, mes.mes);
  const lista = useQuery({
    queryKey: ['calendario', desde, hasta, estado],
    queryFn: () => {
      const p = new URLSearchParams({ desde, hasta });
      if (estado) p.set('estado', estado);
      return api<FilaVisita[]>('/api/calendario?' + p.toString());
    },
  });
  const abrir = (id: number) => navegar(`/calendario/visita/${id}`);
  const filas = lista.data ?? [];

  return (
    <Stack>
      <EncabezadoPagina titulo="Calendario de visitas" fuente="Anexo 5 · 4.5" />
      <SimpleGrid cols={{ base: 1, sm: 3 }}>
        <Select
          label="Estado"
          clearable
          data={Object.keys(COLOR_ESTADO).map((value) => ({ value, label: ESTADOS[value] ?? value }))}
          value={estado}
          onChange={setEstado}
        />
        <SegmentedControl
          style={{ alignSelf: 'end' }}
          data={[
            { value: 'lista', label: 'Lista' },
            { value: 'calendario', label: 'Calendario' },
          ]}
          value={vista}
          onChange={(v) => setVista(v === 'lista' ? 'lista' : 'calendario')}
        />
      </SimpleGrid>
      <Group>
        <NavegadorMes anio={mes.anio} mes={mes.mes} alMover={(n) => setMes(moverMes(mes.anio, mes.mes, n))} />
      </Group>
      {lista.error && <Alert color="red">{lista.error.message}</Alert>}
      {lista.isLoading ? (
        <Center py="xl">
          <Loader />
        </Center>
      ) : vista === 'lista' ? (
        <>
          <TablaVisitas filas={filas} alAbrir={abrir} testid="tabla-calendario" />
          {filas.length === 0 && !lista.error && <Text c="dimmed">No hay visitas en este mes.</Text>}
        </>
      ) : (
        <CalendarioMes anio={mes.anio} mes={mes.mes} visitas={filas} alAbrir={abrir} />
      )}
    </Stack>
  );
}
