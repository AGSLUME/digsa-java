import {
  Alert,
  Anchor,
  Badge,
  Box,
  Button,
  Card,
  Center,
  Group,
  Loader,
  Modal,
  Paper,
  SimpleGrid,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { api, ErrorApi } from '../shared/api';
import { NOMBRE_PLAN } from '../shared/opl';
import {
  COLOR_OPL,
  COLOR_SITUACION,
  NOMBRE_OPL,
  NOMBRE_SITUACION,
  textoCadena,
  type HallazgoPrestador,
  type PrestadorConHallazgos,
  type VisitaConHallazgos,
} from '../shared/seguimiento';
import { puedeEntrar, useSesion } from '../shared/sesion';

/**
 * «Prestadores con hallazgos» (decisiones 6.8 y 36): por prestador, la última visita cerrada con hallazgos de cada cadena de
 * seguimientos, con el plan, la respuesta del OPL, la siguiente visita y la situación. En celular los hallazgos van en tarjetas.
 */
export function Hallazgos() {
  const [buscar, setBuscar] = useState('');
  const lista = useQuery({
    queryKey: ['hallazgos'],
    queryFn: () => api<PrestadorConHallazgos[]>('/api/hallazgos'),
  });
  const texto = buscar.trim().toLocaleLowerCase('es');
  const prestadores = (lista.data ?? []).filter(
    (p) => !texto || [p.punto, p.unidad, p.fuerza, ...p.visitas.map((v) => v.numero)].some((x) => (x ?? '').toLocaleLowerCase('es').includes(texto)),
  );

  return (
    <Stack>
      <Title order={3}>Prestadores con hallazgos</Title>
      <Text size="sm" c="dimmed">
        Última visita cerrada con hallazgos de cada prestador, con el estado del plan de mejoramiento, la respuesta del operador logístico y la visita
        de seguimiento.
      </Text>
      <TextInput
        label="Buscar"
        placeholder="Prestador, unidad, fuerza o número de visita"
        maw={360}
        value={buscar}
        onChange={(e) => setBuscar(e.currentTarget.value)}
      />
      {lista.error && <Alert color="red">{lista.error.message}</Alert>}
      {lista.isLoading && (
        <Center py="xl">
          <Loader />
        </Center>
      )}
      {lista.data && prestadores.length === 0 && (
        <Text c="dimmed">{texto ? 'Ningún prestador coincide con la búsqueda.' : 'No hay prestadores con hallazgos.'}</Text>
      )}
      {prestadores.map((p) => (
        <Card key={p.puntoId} withBorder data-testid={'prestador-' + p.puntoId}>
          <Title order={5} style={{ overflowWrap: 'anywhere' }}>
            {p.punto}
          </Title>
          <Text size="sm" c="dimmed" mb="xs">
            {p.unidad} · {p.fuerza}
          </Text>
          <Stack gap="sm">
            {p.visitas.map((v) => (
              <Visita key={v.visitaId} v={v} />
            ))}
          </Stack>
        </Card>
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

function Visita({ v }: { v: VisitaConHallazgos }) {
  const s = useSesion();
  // El servidor marca abreChecklist por alcance; además hace falta la pantalla del formulario (Auditor y Coordinador)
  const abreChecklist = v.abreChecklist && puedeEntrar(s, 'visita');
  const detalleLider = v.esLider && puedeEntrar(s, 'mis-visitas');
  const siguienteEnAgenda = v.siguiente && puedeEntrar(s, 'agenda');
  // Programar a mano el seguimiento que falta (acciones abiertas sin seguimiento): solo el coordinador, con la pantalla de la agenda
  const programa = v.situacion === 'sin_programar' && puedeEntrar(s, 'agenda');
  const [programar, setProgramar] = useState(false);
  return (
    <Paper withBorder p="sm" data-testid={'visita-hallazgos-' + v.numero}>
      <Group justify="space-between" gap="xs" wrap="wrap">
        <Group gap="xs" wrap="wrap">
          <Text fw={700}>{v.numero}</Text>
          <Badge variant="light" color={v.seguimientoN ? 'grape' : 'gray'} data-testid="cadena">
            {textoCadena(v)}
          </Badge>
          {v.esLider && <Badge variant="outline">Líder</Badge>}
        </Group>
        <Badge color={COLOR_SITUACION[v.situacion]} data-testid="situacion">
          {NOMBRE_SITUACION[v.situacion]}
        </Badge>
      </Group>
      <SimpleGrid cols={{ base: 2, sm: 4 }} spacing="xs" mt="xs">
        <Dato titulo="Fecha">{v.fecha}</Dato>
        <Dato titulo="Auditor líder">{v.auditor}</Dato>
        <Dato titulo="Acciones abiertas">{v.abiertas}</Dato>
        <Dato titulo="Plan del OPL">
          {v.planEstado ? NOMBRE_PLAN[v.planEstado] : '—'}
          {v.planFirmado ? ' · firmado' : ''}
          {v.vencePlan ? ` · vence ${v.vencePlan}` : ''}
        </Dato>
      </SimpleGrid>
      <Box mt="xs" data-testid="siguiente">
        <Text size="sm">
          <Text span fw={500}>
            Siguiente visita:{' '}
          </Text>
          {v.siguiente ? (
            <>
              {siguienteEnAgenda ? (
                <Anchor component={Link} to={`/agenda/visita/${v.siguiente.id}`} size="sm">
                  {v.siguiente.numero}
                </Anchor>
              ) : (
                v.siguiente.numero
              )}{' '}
              el {v.siguiente.fecha} ({v.siguiente.estado})
            </>
          ) : (
            'ninguna programada'
          )}
        </Text>
      </Box>

      {/* Escritorio y tableta: tabla; celular: tarjetas (sin desplazamiento horizontal) */}
      <Box visibleFrom="sm" mt="xs">
        <Table striped data-testid="tabla-hallazgos">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Hallazgo</Table.Th>
              <Table.Th>Fin del plan</Table.Th>
              <Table.Th>Estado del plan</Table.Th>
              <Table.Th>Respuesta del OPL</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {v.hallazgos.map((h) => (
              <Table.Tr key={h.clave}>
                <Table.Td>
                  <Text size="sm" fw={600}>
                    {h.codigo}
                  </Text>
                  <TextoHallazgo h={h} />
                </Table.Td>
                <Table.Td style={{ whiteSpace: 'nowrap' }}>{h.fin ?? '—'}</Table.Td>
                <Table.Td>{h.estadoPlan ?? '—'}</Table.Td>
                <Table.Td>
                  <RespuestaDelOpl h={h} />
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Box>
      <Stack hiddenFrom="sm" gap="xs" mt="xs" data-testid="tarjetas-hallazgos">
        {v.hallazgos.map((h) => (
          <Paper key={h.clave} withBorder p="xs">
            <Text size="sm" fw={600}>
              {h.codigo}
            </Text>
            <TextoHallazgo h={h} />
            <SimpleGrid cols={2} spacing="xs" mt={4}>
              <Dato titulo="Fin del plan">{h.fin ?? '—'}</Dato>
              <Dato titulo="Estado del plan">{h.estadoPlan ?? '—'}</Dato>
            </SimpleGrid>
            <Text size="xs" c="dimmed" mt={4}>
              Respuesta del OPL
            </Text>
            <RespuestaDelOpl h={h} />
          </Paper>
        ))}
      </Stack>

      {(abreChecklist || detalleLider || programa) && (
        <Group gap="md" mt="sm" wrap="wrap">
          {programa && (
            <Button size="xs" onClick={() => setProgramar(true)} aria-label={'Programar el seguimiento de la visita ' + v.numero}>
              Programar seguimiento
            </Button>
          )}
          {abreChecklist && (
            <Anchor component={Link} to={`/visita/${v.visitaId}`} size="sm" py={6}>
              Abrir checklist
            </Anchor>
          )}
          {detalleLider && (
            <Anchor component={Link} to={`/mis-visitas/visita/${v.visitaId}`} size="sm" py={6}>
              Detalle de la visita
            </Anchor>
          )}
        </Group>
      )}
      <Modal opened={programar} onClose={() => setProgramar(false)} title={'Programar el seguimiento de la visita ' + v.numero}>
        {programar && <ProgramarSeguimiento v={v} alTerminar={() => setProgramar(false)} />}
      </Modal>
    </Paper>
  );
}

/** Hoy en Bogotá (el servidor valida con esa fecha), no en la zona del equipo. */
function hoy(): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'America/Bogota',
  }).format(new Date());
}

/** El coordinador programa el seguimiento de una visita cerrada con acciones abiertas que no lo tiene (situación «sin programar»). */
function ProgramarSeguimiento({ v, alTerminar }: { v: VisitaConHallazgos; alTerminar: () => void }) {
  const qc = useQueryClient();
  // Como el servidor: solo las acciones abiertas (ni levantadas ni Completado o Cancelado)
  const proxima = v.hallazgos
    .filter((h) => h.estadoOpl !== 'levantado' && !['Completado', 'Cancelado'].includes(h.estadoPlan ?? 'Pendiente'))
    .map((h) => h.fin)
    .filter((f): f is string => !!f && f >= hoy())
    .sort()[0];
  const [fecha, setFecha] = useState(proxima ?? hoy());
  const [error, setError] = useState<string | null>(null);
  const guardar = useMutation({
    mutationFn: () =>
      api<{ mensaje: string }>(`/api/visitas/${v.visitaId}/seguimiento`, {
        cuerpo: { fecha },
      }),
    onSuccess: (r) => {
      notifications.show({
        color: 'green',
        autoClose: 10000,
        message: r.mensaje || 'Seguimiento programado.',
      });
      qc.invalidateQueries({ queryKey: ['hallazgos'] });
      qc.invalidateQueries({ queryKey: ['agenda'] });
      alTerminar();
    },
    onError: (e) => {
      setError(e instanceof ErrorApi ? e.message : 'No se pudo programar el seguimiento.');
      qc.invalidateQueries({ queryKey: ['hallazgos'] });
    },
  });
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        setError(null);
        if (fecha) guardar.mutate();
      }}
    >
      <Stack>
        <Text size="sm">
          La visita {v.numero} tiene {v.abiertas} acción(es) abierta(s) del plan y no tiene visita de seguimiento. Se programa con el mismo punto,
          hora y equipo, y se notifica como las demás.
        </Text>
        {error && (
          <Alert color="red" data-testid="error-programar">
            {error}
          </Alert>
        )}
        <TextInput
          label="Fecha del seguimiento"
          name="fecha"
          type="date"
          required
          min={hoy()}
          description={proxima ? 'Propuesta: la fecha fin más próxima de las acciones abiertas.' : undefined}
          value={fecha}
          onChange={(e) => setFecha(e.currentTarget.value)}
        />
        <Group justify="flex-end">
          <Button variant="default" onClick={alTerminar}>
            Volver
          </Button>
          <Button type="submit" loading={guardar.isPending} disabled={!fecha}>
            Programar seguimiento
          </Button>
        </Group>
      </Stack>
    </form>
  );
}

function TextoHallazgo({ h }: { h: HallazgoPrestador }) {
  return (
    <>
      {h.texto && (
        <Text size="xs" c="dimmed" lineClamp={2} style={{ overflowWrap: 'anywhere' }}>
          {h.texto}
        </Text>
      )}
      {h.hallazgo && (
        <Text size="sm" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
          {h.hallazgo}
        </Text>
      )}
    </>
  );
}

/** Estado de la respuesta del OPL con su texto (no solo el color); la objeción, el motivo y los soportes no le llegan al ESM. */
function RespuestaDelOpl({ h }: { h: HallazgoPrestador }) {
  return (
    <Stack gap={2}>
      <Badge size="sm" variant="light" color={COLOR_OPL[h.estadoOpl]} style={{ maxWidth: '100%' }}>
        {NOMBRE_OPL[h.estadoOpl]}
      </Badge>
      {h.justificacion && (
        <Text size="xs" style={{ overflowWrap: 'anywhere' }}>
          Objeción: {h.justificacion}
        </Text>
      )}
      {h.resolucionMotivo && (
        <Text size="xs" c="dimmed" style={{ overflowWrap: 'anywhere' }}>
          Decisión del líder: {h.resolucionMotivo}
        </Text>
      )}
      {(h.soportes ?? 0) > 0 && (
        <Text size="xs" c="dimmed">
          {h.soportes} soporte(s) del OPL
        </Text>
      )}
    </Stack>
  );
}
