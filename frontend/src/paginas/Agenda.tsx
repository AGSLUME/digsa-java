import { Alert, Button, Center, Loader, SegmentedControl, Select, SimpleGrid, Stack, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { FilaVisita, OpcionesAgenda } from '../shared/agenda';
import { api } from '../shared/api';
import { CalendarioMes, moverMes, NavegadorMes, rangoMes } from '../shared/CalendarioMes';
import { FormularioVisita } from '../shared/FormularioVisita';
import { TablaVisitas } from '../shared/TablaVisitas';
import { EncabezadoPagina } from '../shared/EncabezadoPagina';

/** Agenda del coordinador: visitas del mes en lista o calendario, con alertas de plazos y el formulario para programar. */
export function Agenda() {
  const navegar = useNavigate();
  const hoy = new Date();
  const [mes, setMes] = useState({ anio: hoy.getFullYear(), mes: hoy.getMonth() });
  const [vista, setVista] = useState<'lista' | 'calendario'>('lista');
  const [estado, setEstado] = useState<string | null>(null);
  const [tipo, setTipo] = useState<string | null>(null);
  const [programar, setProgramar] = useState(false);

  const opciones = useQuery({ queryKey: ['agenda', 'opciones'], queryFn: () => api<OpcionesAgenda>('/api/agenda/opciones') });
  const { desde, hasta } = rangoMes(mes.anio, mes.mes);
  const lista = useQuery({
    queryKey: ['agenda', 'lista', desde, hasta, estado, tipo],
    queryFn: () => {
      const p = new URLSearchParams({ desde, hasta });
      if (estado) p.set('estado', estado);
      if (tipo) p.set('tipo', tipo);
      return api<FilaVisita[]>('/api/agenda?' + p.toString());
    },
  });

  const abrir = (id: number) => navegar(`/agenda/visita/${id}`);
  const filas = lista.data ?? [];

  return (
    <Stack>
      <EncabezadoPagina
        titulo="Agenda"
        fuente="Anexo 5 · 4.5 · Anexo 3"
        acciones={
          <>
            <Button variant="default" onClick={() => navegar('/seguimiento')}>
              Seguimiento en tiempo real
            </Button>
            <Button onClick={() => setProgramar(true)} disabled={!opciones.data}>
              Programar visita
            </Button>
          </>
        }
      />
      {opciones.error && <Alert color="red">No se pudieron cargar las opciones: {opciones.error.message}</Alert>}
      <SimpleGrid cols={{ base: 1, sm: 3 }}>
        <Select
          label="Estado"
          clearable
          data={Object.entries(opciones.data?.estados ?? {}).map(([value, label]) => ({ value, label }))}
          value={estado}
          onChange={setEstado}
        />
        <Select
          label="Tipo"
          clearable
          data={(opciones.data?.tipos ?? []).map((t) => ({ value: t.codigo, label: t.nombre }))}
          value={tipo}
          onChange={setTipo}
        />
        <SegmentedControl
          style={{ alignSelf: 'end' }}
          data={[
            { value: 'lista', label: 'Lista' },
            { value: 'calendario', label: 'Calendario' },
          ]}
          value={vista}
          onChange={(v) => setVista(v === 'calendario' ? 'calendario' : 'lista')}
        />
      </SimpleGrid>
      <NavegadorMes anio={mes.anio} mes={mes.mes} alMover={(n) => setMes(moverMes(mes.anio, mes.mes, n))} />
      {lista.error && <Alert color="red">{lista.error.message}</Alert>}
      {lista.isLoading ? (
        <Center py="xl">
          <Loader />
        </Center>
      ) : vista === 'lista' ? (
        <>
          <TablaVisitas filas={filas} alAbrir={abrir} conAlertas testid="tabla-agenda" />
          {filas.length === 0 && !lista.error && <Text c="dimmed">No hay visitas en este mes con estos filtros.</Text>}
        </>
      ) : (
        <CalendarioMes anio={mes.anio} mes={mes.mes} visitas={filas} alAbrir={abrir} />
      )}
      <FormularioVisita
        abierto={programar}
        alCerrar={() => setProgramar(false)}
        opciones={opciones.data}
        alGuardar={(r) => {
          setProgramar(false);
          navegar(`/agenda/visita/${r.id}`);
        }}
      />
    </Stack>
  );
}
