import { Alert, Anchor, Badge, Box, Button, Card, Center, Group, Loader, Modal, ScrollArea, Select, SimpleGrid, Stack, Table, Text, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api, ErrorApi } from '../shared/api';
import {
  COLOR_PRORROGA,
  errorMotivo,
  fechaHora,
  MOTIVO_MAX,
  NOMBRE_DECISION,
  NOMBRE_PRORROGA,
  type DecisionProrroga,
  type Prorroga,
} from '../shared/seguimiento';
import { puedeEntrar, useSesion } from '../shared/sesion';
import { EncabezadoPagina } from '../shared/EncabezadoPagina';

const ESTADOS = [
  { value: 'solicitada', label: 'Solicitadas' },
  { value: 'aprobada', label: 'Aprobadas' },
  { value: 'rechazada', label: 'Rechazadas' },
  { value: 'todas', label: 'Todas' },
];

function avisarError(e: unknown, texto: string) {
  notifications.show({
    color: 'red',
    autoClose: 10000,
    message: e instanceof ErrorApi ? e.message : texto,
  });
}

/**
 * «Prórrogas del plan» (decisión 12.1, con la resolución recomendada): las prórrogas que pidió el auditor en el acta del seguimiento.
 * Pasan a aprobadas cuando las aprueban la coordinación y la DIGSA; cualquiera de las dos puede rechazarlas con motivo.
 */
export function Prorrogas() {
  const s = useSesion();
  const qc = useQueryClient();
  const [estado, setEstado] = useState<string>('solicitada');
  const [rechazar, setRechazar] = useState<Prorroga | null>(null);
  const lista = useQuery({
    queryKey: ['prorrogas', estado],
    queryFn: () => api<Prorroga[]>(`/api/prorrogas?estado=${encodeURIComponent(estado)}`),
  });
  const verVisita = puedeEntrar(s, 'agenda');
  const aprobar = useMutation({
    mutationFn: (p: Prorroga) =>
      api<{ mensaje: string }>(`/api/prorrogas/${p.id}/decision`, {
        cuerpo: { decision: 'aprueba' },
      }),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      qc.invalidateQueries({ queryKey: ['prorrogas'] });
    },
    onError: (e) => {
      avisarError(e, 'No se pudo aprobar la prórroga.');
      qc.invalidateQueries({ queryKey: ['prorrogas'] });
    },
  });
  const filas = lista.data ?? [];

  return (
    <Stack>
      <EncabezadoPagina
        titulo="Prórrogas del plan"
        fuente="Anexo 5 · 4.5"
        descripcion={
          <>
            En el seguimiento, una acción que sigue abierta y cuya fecha fin pasa a ser posterior a la anterior es una prórroga: el auditor la justifica
            en el acta y queda solicitada. Pasa a aprobada cuando la aprueban la coordinación y la DIGSA; cualquiera de las dos puede rechazarla con el
            motivo.
          </>
        }
      />
      <Select
        label="Estado"
        w={220}
        allowDeselect={false}
        data={ESTADOS}
        value={estado}
        onChange={(x) => setEstado(x ?? 'solicitada')}
        data-testid="filtro-prorrogas"
      />
      {lista.error && <Alert color="red">{lista.error.message}</Alert>}
      {lista.isLoading ? (
        <Center py="xl">
          <Loader />
        </Center>
      ) : (
        <>
          {/* En el celular, tarjetas: los botones quedan a la vista sin desplazar la tabla */}
          <Stack hiddenFrom="sm" gap="sm" data-testid="tarjetas-prorrogas">
            {filas.length === 0 && <Text size="sm">No hay prórrogas con este filtro.</Text>}
            {filas.map((p) => (
              <Card key={p.id} withBorder padding="sm">
                <Stack gap={6}>
                  <Group justify="space-between" wrap="nowrap">
                    <Text fw={600} size="sm">
                      {p.numero} · {p.codigo}
                    </Text>
                    <Badge color={COLOR_PRORROGA[p.estado]}>{NOMBRE_PRORROGA[p.estado]}</Badge>
                  </Group>
                  <Text size="xs" c="dimmed">
                    {p.punto} · origen {p.origenNumero}
                  </Text>
                  <Text size="sm">
                    Fin: {p.finAnterior} → {p.finNuevo}
                  </Text>
                  <Text size="sm" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                    {p.justificacion}
                  </Text>
                  {p.motivoRechazo && (
                    <Text size="xs" c="red">
                      Motivo del rechazo: {p.motivoRechazo}
                    </Text>
                  )}
                  <SimpleGrid cols={2} spacing="xs">
                    <Box>
                      <Text size="xs" c="dimmed">
                        Coordinación
                      </Text>
                      <Decision decision={p.coordDecision} nombre={p.coordNombre} fecha={p.coordFecha} />
                    </Box>
                    <Box>
                      <Text size="xs" c="dimmed">
                        DIGSA
                      </Text>
                      <Decision decision={p.digsaDecision} nombre={p.digsaNombre} fecha={p.digsaFecha} />
                    </Box>
                  </SimpleGrid>
                  {p.puedeDecidir && (
                    <Group grow>
                      <Button
                        color="green"
                        mih={44}
                        disabled={aprobar.isPending}
                        loading={aprobar.isPending && aprobar.variables?.id === p.id}
                        onClick={() => window.confirm(`¿Aprobar la prórroga de ${p.codigo} hasta el ${p.finNuevo}?`) && aprobar.mutate(p)}
                      >
                        Aprobar
                      </Button>
                      <Button variant="light" color="red" mih={44} disabled={aprobar.isPending} onClick={() => setRechazar(p)}>
                        Rechazar
                      </Button>
                    </Group>
                  )}
                </Stack>
              </Card>
            ))}
          </Stack>
          <ScrollArea type="auto" visibleFrom="sm">
            <Table striped miw={1180} data-testid="tabla-prorrogas">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Visita</Table.Th>
                  <Table.Th>Punto</Table.Th>
                  <Table.Th>Hallazgo</Table.Th>
                  <Table.Th>Fin anterior</Table.Th>
                  <Table.Th>Fin nuevo</Table.Th>
                  <Table.Th>Justificación</Table.Th>
                  <Table.Th>Coordinación</Table.Th>
                  <Table.Th>DIGSA</Table.Th>
                  <Table.Th>Estado</Table.Th>
                  <Table.Th>Acciones</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {filas.length === 0 && (
                  <Table.Tr>
                    <Table.Td colSpan={10}>No hay prórrogas con este filtro.</Table.Td>
                  </Table.Tr>
                )}
                {filas.map((p) => (
                  <Table.Tr key={p.id} data-testid={'prorroga-' + p.id}>
                    <Table.Td style={{ whiteSpace: 'nowrap' }}>
                      {verVisita ? (
                        <Anchor component={Link} to={`/agenda/visita/${p.visitaId}`} size="sm">
                          {p.numero}
                        </Anchor>
                      ) : (
                        p.numero
                      )}
                      <Text size="xs" c="dimmed">
                        Origen: {p.origenNumero}
                      </Text>
                      <Text size="xs" c="dimmed">
                        Seguimiento del {p.fechaSeguimiento}
                      </Text>
                    </Table.Td>
                    <Table.Td>{p.punto}</Table.Td>
                    <Table.Td style={{ whiteSpace: 'nowrap' }}>{p.codigo}</Table.Td>
                    <Table.Td style={{ whiteSpace: 'nowrap' }}>{p.finAnterior}</Table.Td>
                    <Table.Td style={{ whiteSpace: 'nowrap' }}>{p.finNuevo}</Table.Td>
                    <Table.Td maw={320}>
                      <Text
                        size="sm"
                        style={{
                          whiteSpace: 'pre-wrap',
                          overflowWrap: 'anywhere',
                        }}
                      >
                        {p.justificacion}
                      </Text>
                      {p.motivoRechazo && (
                        <Text size="xs" c="red" mt={4}>
                          Motivo del rechazo: {p.motivoRechazo}
                        </Text>
                      )}
                    </Table.Td>
                    <Table.Td data-testid="decision-coord">
                      <Decision decision={p.coordDecision} nombre={p.coordNombre} fecha={p.coordFecha} />
                    </Table.Td>
                    <Table.Td data-testid="decision-digsa">
                      <Decision decision={p.digsaDecision} nombre={p.digsaNombre} fecha={p.digsaFecha} />
                    </Table.Td>
                    <Table.Td>
                      <Badge color={COLOR_PRORROGA[p.estado]}>{NOMBRE_PRORROGA[p.estado]}</Badge>
                    </Table.Td>
                    <Table.Td>
                      {p.puedeDecidir && (
                        <Group gap={4} wrap="nowrap">
                          <Button
                            size="xs"
                            color="green"
                            aria-label={`Aprobar la prórroga de ${p.codigo} en la visita ${p.numero}`}
                            loading={aprobar.isPending && aprobar.variables?.id === p.id}
                            disabled={aprobar.isPending}
                            onClick={() => window.confirm(`¿Aprobar la prórroga de ${p.codigo} hasta el ${p.finNuevo}?`) && aprobar.mutate(p)}
                          >
                            Aprobar
                          </Button>
                          <Button
                            size="xs"
                            variant="light"
                            color="red"
                            aria-label={`Rechazar la prórroga de ${p.codigo} en la visita ${p.numero}`}
                            disabled={aprobar.isPending}
                            onClick={() => setRechazar(p)}
                          >
                            Rechazar
                          </Button>
                        </Group>
                      )}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollArea>
        </>
      )}

      <Modal
        opened={rechazar !== null}
        onClose={() => setRechazar(null)}
        closeOnClickOutside={false}
        title={`Rechazar la prórroga de ${rechazar?.codigo ?? ''}`}
      >
        {rechazar && <Rechazar prorroga={rechazar} alTerminar={() => setRechazar(null)} />}
      </Modal>
    </Stack>
  );
}

/** Quién decidió por un lado y cuándo (hora militar). */
function Decision({ decision, nombre, fecha }: { decision: DecisionProrroga | null; nombre: string | null; fecha: string | number | null }) {
  if (!decision) {
    return (
      <Text size="sm" c="dimmed">
        Pendiente
      </Text>
    );
  }
  return (
    <>
      <Badge size="sm" variant="light" color={decision === 'aprueba' ? 'green' : 'red'}>
        {NOMBRE_DECISION[decision]}
      </Badge>
      <Text size="xs">{nombre ?? '—'}</Text>
      <Text size="xs" c="dimmed" style={{ whiteSpace: 'nowrap' }}>
        {fechaHora(fecha)}
      </Text>
    </>
  );
}

function Rechazar({ prorroga, alTerminar }: { prorroga: Prorroga; alTerminar: () => void }) {
  const qc = useQueryClient();
  const [motivo, setMotivo] = useState('');
  const [error, setError] = useState<string | null>(null);
  const rechazar = useMutation({
    mutationFn: () =>
      api<{ mensaje: string }>(`/api/prorrogas/${prorroga.id}/decision`, {
        cuerpo: { decision: 'rechaza', motivo: motivo.trim() },
      }),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      qc.invalidateQueries({ queryKey: ['prorrogas'] });
      alTerminar();
    },
    onError: (e) => {
      setError(e instanceof ErrorApi ? e.message : 'No se pudo rechazar la prórroga.');
      qc.invalidateQueries({ queryKey: ['prorrogas'] });
    },
  });
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        const falta = errorMotivo(motivo);
        setError(falta);
        if (!falta) rechazar.mutate();
      }}
    >
      <Stack>
        <Text size="sm">
          Visita {prorroga.numero} · {prorroga.punto}: la fecha fin pasaría del {prorroga.finAnterior} al {prorroga.finNuevo}. Si se rechaza y cambia
          la fecha del seguimiento, el coordinador lo reprograma.
        </Text>
        {error && (
          <Alert color="red" data-testid="error-rechazo">
            {error}
          </Alert>
        )}
        <Textarea
          label="Motivo del rechazo"
          name="motivo"
          required
          autosize
          minRows={3}
          maxLength={MOTIVO_MAX}
          description="De 10 a 800 caracteres."
          value={motivo}
          onChange={(e) => setMotivo(e.currentTarget.value)}
        />
        <Group justify="flex-end">
          <Button variant="default" onClick={alTerminar}>
            Volver
          </Button>
          <Button type="submit" color="red" loading={rechazar.isPending}>
            Rechazar prórroga
          </Button>
        </Group>
      </Stack>
    </form>
  );
}
