import {
  Alert,
  Badge,
  Button,
  Card,
  Center,
  Group,
  Loader,
  Modal,
  NumberInput,
  ScrollArea,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  Textarea,
  TextInput,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, descargar, ErrorApi } from '../shared/api';
import { BarraHorizontal } from '../shared/Barras';
import { useSesion } from '../shared/sesion';
import {
  conConsulta,
  cuerpoPesos,
  decimal,
  EXPLICACION_FACTOR,
  FORMATOS_DESCARGA,
  hoyIso,
  nivelRiesgo,
  problemaCierre,
  problemaIncidencia,
  problemaPesos,
  puedeEditarPesos,
  puedeRegistrarIncidencias,
  sumaPesos,
  tonoTexto,
  type FilaRiesgo,
  type FormIncidencia,
  type Incidencia,
  type TableroRiesgo,
} from '../shared/tablero';

function avisarError(e: unknown, defecto: string) {
  notifications.show({ color: 'red', autoClose: 10000, message: e instanceof ErrorApi ? e.message : defecto });
}

function Caja({ titulo, valor, color, testid }: { titulo: string; valor: string | number; color?: string; testid?: string }) {
  return (
    <Card withBorder padding="sm" data-testid={testid}>
      <Text size="xs" c="dimmed">
        {titulo}
      </Text>
      <Text size="xl" fw={700} c={tonoTexto(color)}>
        {valor}
      </Text>
    </Card>
  );
}

/**
 * «Dashboard de riesgo» (docs/especificaciones/riesgo-tablero): puntaje explicable por ESM de dispensación, agregados por país y
 * fuerza, pesos y umbrales (editables por la coordinación) e incidencias reportadas por la DIGSA.
 */
export function Riesgo() {
  const sesion = useSesion();
  const [fuerza, setFuerza] = useState('');
  const consulta = fuerza ? `fuerza=${encodeURIComponent(fuerza)}` : '';
  // La clave incluye el filtro: una respuesta vieja nunca se pinta sobre la del filtro actual
  const tablero = useQuery({
    queryKey: ['riesgo', 'tablero', fuerza],
    queryFn: () => api<TableroRiesgo>(conConsulta('/api/riesgo', consulta)),
    placeholderData: keepPreviousData,
  });

  const bajar = async (formato: string) => {
    try {
      await descargar(conConsulta('/api/riesgo/exportar', `formato=${formato}${consulta ? '&' + consulta : ''}`));
    } catch (e) {
      avisarError(e, 'No se pudo descargar el dashboard de riesgo.');
    }
  };

  if (tablero.isLoading) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    );
  }
  if (tablero.error && !tablero.data) return <Alert color="red">{tablero.error.message}</Alert>;
  const t = tablero.data!;
  const desactualizado = tablero.isPlaceholderData;

  return (
    <Stack>
      <Title order={3}>Dashboard de riesgo</Title>
      <Text size="sm" c="dimmed">
        Puntaje de 0 a 100 por ESM de dispensación, calculado con la última visita completa cerrada, los hallazgos recurrentes, la intervención
        inmediata, los planes vencidos y las incidencias reportadas por la DIGSA. Los días sin visita se muestran como dato: la prioridad por
        antigüedad la da la agenda.
      </Text>
      <Alert color="yellow" variant="light" data-testid="estado-pesos">
        Pesos y umbrales: {t.estadoPesos}
      </Alert>

      <Card withBorder>
        <Group align="flex-end" wrap="wrap">
          <Select
            label="Fuerza"
            placeholder="Todas"
            clearable
            data={t.fuerzas}
            value={fuerza || null}
            onChange={(v) => setFuerza(v ?? '')}
            maw={220}
            data-testid="filtro-fuerza-riesgo"
          />
          {fuerza && (
            <Button variant="subtle" onClick={() => setFuerza('')}>
              Limpiar filtros
            </Button>
          )}
          {(tablero.isFetching || desactualizado) && <Loader size="sm" />}
        </Group>
        <Group mt="sm" gap="xs" wrap="wrap">
          {FORMATOS_DESCARGA.map((x) => (
            <Button key={x.valor} size="xs" variant="light" onClick={() => void bajar(x.valor)}>
              Descargar {x.etiqueta}
            </Button>
          ))}
        </Group>
      </Card>
      {tablero.error && <Alert color="red">{tablero.error.message}</Alert>}

      <Stack style={{ opacity: desactualizado ? 0.6 : 1 }}>
        <Pais t={t} />
        <PorFuerza t={t} />
        <TablaEsm t={t} />
      </Stack>
      <PanelPesos key={JSON.stringify([t.pesos, t.umbrales])} t={t} editable={puedeEditarPesos(sesion)} />
      <Incidencias esm={t.esm} registra={puedeRegistrarIncidencias(sesion)} />
    </Stack>
  );
}

function Pais({ t }: { t: TableroRiesgo }) {
  const p = t.pais;
  return (
    <Stack gap="sm">
      <Title order={4}>País</Title>
      <SimpleGrid cols={{ base: 2, sm: 4 }} data-testid="riesgo-pais">
        <Caja titulo="ESM evaluados" valor={p.esm} />
        <Caja titulo="Puntaje promedio" valor={decimal(p.promedio)} />
        <Caja titulo={`Riesgo alto (≥ ${t.umbrales.alto})`} valor={p.niveles.alto} color="red" />
        <Caja titulo={`Riesgo medio (≥ ${t.umbrales.medio}) / bajo`} valor={`${p.niveles.medio} / ${p.niveles.bajo}`} color="orange" />
      </SimpleGrid>
      <Card withBorder>
        <Title order={5} mb="xs">
          Promedio de cada factor
        </Title>
        <Stack gap="xs">
          {t.factores.map((f) => (
            <BarraHorizontal
              key={f}
              etiqueta={t.nombres[f] ?? f}
              valor={p.factores[f] ?? 0}
              maximo={t.pesos[f] ?? 0}
              texto={`${decimal(p.factores[f] ?? 0)} de ${t.pesos[f] ?? 0}`}
            />
          ))}
        </Stack>
      </Card>
    </Stack>
  );
}

function PorFuerza({ t }: { t: TableroRiesgo }) {
  return (
    <Card withBorder>
      <Title order={4} mb="xs">
        Por fuerza
      </Title>
      {t.porFuerza.length === 0 ? (
        <Text size="sm" c="dimmed">
          Sin ESM en su alcance.
        </Text>
      ) : (
        <ScrollArea type="auto">
          <Table striped miw={620} data-testid="tabla-riesgo-fuerza">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Fuerza</Table.Th>
                <Table.Th ta="right">ESM</Table.Th>
                <Table.Th ta="right">Promedio</Table.Th>
                <Table.Th ta="right">Alto</Table.Th>
                <Table.Th ta="right">Medio</Table.Th>
                <Table.Th ta="right">Bajo</Table.Th>
                <Table.Th>Factor principal</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {t.porFuerza.map((f) => (
                <Table.Tr key={f.fuerza}>
                  <Table.Td>{f.fuerza}</Table.Td>
                  <Table.Td ta="right">{f.agregado.esm}</Table.Td>
                  <Table.Td ta="right">{decimal(f.agregado.promedio)}</Table.Td>
                  <Table.Td ta="right">{f.agregado.niveles.alto}</Table.Td>
                  <Table.Td ta="right">{f.agregado.niveles.medio}</Table.Td>
                  <Table.Td ta="right">{f.agregado.niveles.bajo}</Table.Td>
                  <Table.Td>{f.principal}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      )}
    </Card>
  );
}

function textoDatos(d: FilaRiesgo['datos']): string[] {
  return [
    `Cumple ${d.c} · No cumple ${d.nc}`,
    `Recurrentes ${d.recurrentes}`,
    `Intervención inmediata ${d.inmediata}`,
    `Plan del OPL vencido: ${d.planOplVencido ? 'sí' : 'no'}`,
    `Acciones vencidas ${d.accionesVencidas}`,
    `Incidencias de la DIGSA ${d.incidencias}`,
  ];
}

function TablaEsm({ t }: { t: TableroRiesgo }) {
  return (
    <Card withBorder>
      <Title order={4} mb="xs">
        Puntaje por ESM
      </Title>
      <ScrollArea type="auto">
        <Table striped miw={1100} verticalSpacing="sm" data-testid="tabla-riesgo">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>ESM</Table.Th>
              <Table.Th>Fuerza</Table.Th>
              <Table.Th ta="right">Puntaje</Table.Th>
              <Table.Th>Nivel</Table.Th>
              <Table.Th miw={240}>Factores</Table.Th>
              <Table.Th miw={200}>Datos</Table.Th>
              <Table.Th>Última visita cerrada</Table.Th>
              <Table.Th ta="right">Días sin visita</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {t.esm.length === 0 && (
              <Table.Tr>
                <Table.Td colSpan={8}>Sin ESM en su alcance.</Table.Td>
              </Table.Tr>
            )}
            {t.esm.map((e) => {
              const n = nivelRiesgo(e.nivel);
              return (
                <Table.Tr key={e.puntoId} data-testid={'fila-riesgo-' + e.puntoId} style={{ verticalAlign: 'top' }}>
                  <Table.Td>
                    <Text size="sm" fw={600}>
                      {e.nombre}
                    </Text>
                    {e.municipio && (
                      <Text size="xs" c="dimmed">
                        {e.municipio}
                      </Text>
                    )}
                    {e.sinResumen && (
                      <Text size="xs" c="red.8" fw={600} data-testid={'sin-resumen-' + e.puntoId}>
                        Visita sin resumen: incumplimiento no medido
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td>{e.fuerza}</Table.Td>
                  <Table.Td ta="right">
                    <Text fw={700}>{e.total}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Badge color={n.insignia} variant="filled" autoContrast>
                      {n.etiqueta}
                    </Badge>
                  </Table.Td>
                  <Table.Td>
                    <Stack gap={4}>
                      {t.factores.map((f) => (
                        <BarraHorizontal
                          key={f}
                          etiqueta={t.nombres[f] ?? f}
                          valor={e.factores[f] ?? 0}
                          maximo={t.pesos[f] ?? 0}
                          texto={`${decimal(e.factores[f] ?? 0)} de ${t.pesos[f] ?? 0}`}
                          color={n.color}
                        />
                      ))}
                    </Stack>
                  </Table.Td>
                  <Table.Td>
                    {textoDatos(e.datos).map((x) => (
                      <Text size="xs" key={x}>
                        {x}
                      </Text>
                    ))}
                  </Table.Td>
                  <Table.Td>{e.ultimaCerrada ?? 'Sin visita cerrada'}</Table.Td>
                  <Table.Td ta="right">{e.diasSinVisita ?? '—'}</Table.Td>
                </Table.Tr>
              );
            })}
          </Table.Tbody>
        </Table>
      </ScrollArea>
    </Card>
  );
}

/** Pesos por factor y umbrales de nivel: la coordinación los edita (14.1, con bitácora); los perfiles de consulta solo los ven. */
function PanelPesos({ t, editable }: { t: TableroRiesgo; editable: boolean }) {
  const qc = useQueryClient();
  const [pesos, setPesos] = useState<Record<string, number | string>>(() => ({ ...t.pesos }));
  const [alto, setAlto] = useState<number | string>(t.umbrales.alto);
  const [medio, setMedio] = useState<number | string>(t.umbrales.medio);
  const problema = problemaPesos(pesos, t.factores, t.nombres, alto, medio);
  const cambiado = t.factores.some((f) => Number(pesos[f]) !== t.pesos[f]) || Number(alto) !== t.umbrales.alto || Number(medio) !== t.umbrales.medio;
  const guardar = useMutation({
    mutationFn: () =>
      api<{ mensaje: string }>('/api/riesgo/pesos', { metodo: 'PUT', cuerpo: cuerpoPesos(pesos, t.factores, Number(alto), Number(medio)) }),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      void qc.invalidateQueries({ queryKey: ['riesgo'] });
    },
    onError: (e) => avisarError(e, 'No se pudieron guardar los pesos.'),
  });

  return (
    <Card withBorder data-testid="panel-pesos">
      <Title order={4}>Pesos y umbrales</Title>
      <Text size="sm" c="dimmed" mb="sm">
        {t.estadoPesos}. Cada factor suma hasta su peso; los pesos suman 100. Nivel alto con puntaje ≥ {t.umbrales.alto}, medio con ≥{' '}
        {t.umbrales.medio} y bajo por debajo.
        {!editable && ' Solo la coordinación cambia los pesos.'}
      </Text>
      <Stack gap="sm">
        {t.factores.map((f) => (
          <Group key={f} align="flex-start" wrap="wrap" gap="sm">
            {editable ? (
              <NumberInput
                label={t.nombres[f] ?? f}
                min={0}
                max={100}
                step={1}
                allowDecimal={false}
                allowNegative={false}
                w={170}
                value={pesos[f] ?? ''}
                onChange={(v) => setPesos((p) => ({ ...p, [f]: v }))}
                data-testid={'peso-' + f}
              />
            ) : (
              <Text size="sm" fw={600} w={170}>
                {t.nombres[f] ?? f}: {t.pesos[f]}
              </Text>
            )}
            <Text size="xs" c="dimmed" style={{ flex: '1 1 200px' }} pt={editable ? 26 : 2}>
              {EXPLICACION_FACTOR[f] ?? ''}
            </Text>
          </Group>
        ))}
        {editable && (
          <>
            <Text size="sm" c={sumaPesos(pesos, t.factores) === 100 ? 'dimmed' : 'red.8'}>
              Suma de los pesos: {sumaPesos(pesos, t.factores)} de 100
            </Text>
            <Group wrap="wrap">
              <NumberInput label="Umbral alto" min={1} max={100} allowDecimal={false} allowNegative={false} w={140} value={alto} onChange={setAlto} />
              <NumberInput
                label="Umbral medio"
                min={1}
                max={99}
                allowDecimal={false}
                allowNegative={false}
                w={140}
                value={medio}
                onChange={setMedio}
              />
            </Group>
            {problema && (
              <Text size="sm" c="red.8" data-testid="problema-pesos">
                {problema}
              </Text>
            )}
            <Group>
              <Button data-testid="guardar-pesos" disabled={!!problema || !cambiado} loading={guardar.isPending} onClick={() => guardar.mutate()}>
                Guardar pesos
              </Button>
            </Group>
          </>
        )}
      </Stack>
    </Card>
  );
}

const FORM_VACIO: FormIncidencia = { puntoId: '', fecha: '', descripcion: '', fuente: '' };

/** Incidencias reportadas por la DIGSA sobre un ESM (14.1): suman al puntaje mientras estén abiertas (últimos 12 meses). */
function Incidencias({ esm, registra }: { esm: FilaRiesgo[]; registra: boolean }) {
  const qc = useQueryClient();
  const lista = useQuery({ queryKey: ['riesgo', 'incidencias'], queryFn: () => api<Incidencia[]>('/api/riesgo/incidencias') });
  const [elegido, setForm] = useState<FormIncidencia>({ ...FORM_VACIO, fecha: hoyIso() });
  // El ESM elegido debe seguir en la lista (filtrada por fuerza): si el filtro lo oculta, el formulario queda sin ESM
  const form = esm.some((e) => String(e.puntoId) === elegido.puntoId) ? elegido : { ...elegido, puntoId: '' };
  const [intento, setIntento] = useState(false);
  const [cerrando, setCerrando] = useState<Incidencia | null>(null);
  const [motivo, setMotivo] = useState('');
  const hoy = hoyIso();
  const problema = problemaIncidencia(form, hoy);
  const problemaMotivo = problemaCierre(motivo);

  const registrar = useMutation({
    mutationFn: () =>
      api<{ id: number; mensaje: string }>('/api/riesgo/incidencias', {
        cuerpo: {
          puntoId: Number(form.puntoId),
          fecha: form.fecha,
          descripcion: form.descripcion.trim(),
          fuente: form.fuente.trim() || null,
        },
      }),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      setForm({ ...FORM_VACIO, fecha: hoyIso() });
      setIntento(false);
      void qc.invalidateQueries({ queryKey: ['riesgo'] });
    },
    onError: (e) => {
      avisarError(e, 'No se pudo registrar la incidencia.');
      void qc.invalidateQueries({ queryKey: ['riesgo'] });
    },
  });
  const cerrar = useMutation({
    mutationFn: (i: Incidencia) => api<{ mensaje: string }>(`/api/riesgo/incidencias/${i.id}/cerrar`, { cuerpo: { motivo: motivo.trim() } }),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      setCerrando(null);
      setMotivo('');
      void qc.invalidateQueries({ queryKey: ['riesgo'] });
    },
    onError: (e) => {
      avisarError(e, 'No se pudo cerrar la incidencia.');
      // Cerrada por otro o inexistente (404): se cierra el diálogo y se recarga la lista y el tablero
      if (e instanceof ErrorApi && e.estado === 404) {
        setCerrando(null);
        setMotivo('');
      }
      void qc.invalidateQueries({ queryKey: ['riesgo'] });
    },
  });

  return (
    <Card withBorder data-testid="incidencias">
      <Title order={4}>Incidencias reportadas por la DIGSA</Title>
      <Text size="sm" c="dimmed" mb="sm">
        Cada incidencia abierta de los últimos 12 meses suma al factor «Incidencias de la DIGSA» del ESM. Al cerrarla deja de sumar.
      </Text>
      {registra && (
        <Card withBorder mb="md" data-testid="form-incidencia">
          <Title order={5} mb="xs">
            Registrar incidencia
          </Title>
          <SimpleGrid cols={{ base: 1, sm: 2 }}>
            <Select
              label="ESM"
              required
              searchable
              placeholder="Elija el ESM"
              data={esm.map((e) => ({ value: String(e.puntoId), label: `${e.nombre} (${e.fuerza})` }))}
              value={form.puntoId || null}
              onChange={(v) => setForm({ ...form, puntoId: v ?? '' })}
              nothingFoundMessage="Sin ESM con ese nombre"
            />
            <TextInput
              label="Fecha"
              type="date"
              required
              max={hoy}
              value={form.fecha}
              onChange={(e) => setForm({ ...form, fecha: e.currentTarget.value })}
            />
          </SimpleGrid>
          <Textarea
            mt="sm"
            label="Descripción"
            required
            autosize
            minRows={3}
            maxLength={2000}
            description={`${form.descripcion.trim().length} de 2000 caracteres (mínimo 10)`}
            value={form.descripcion}
            onChange={(e) => setForm({ ...form, descripcion: e.currentTarget.value })}
          />
          <TextInput
            mt="sm"
            label="Fuente"
            placeholder="Oficio, correo o reunión en que la DIGSA la reportó"
            maxLength={200}
            value={form.fuente}
            onChange={(e) => setForm({ ...form, fuente: e.currentTarget.value })}
          />
          {intento && problema && (
            <Text size="sm" c="red.8" mt="xs">
              {problema}
            </Text>
          )}
          <Group mt="sm">
            <Button
              loading={registrar.isPending}
              onClick={() => {
                setIntento(true);
                if (!problema) registrar.mutate();
              }}
            >
              Registrar incidencia
            </Button>
          </Group>
        </Card>
      )}
      {lista.isLoading && <Loader size="sm" />}
      {lista.error && <Alert color="red">{lista.error.message}</Alert>}
      {lista.data && (
        <ScrollArea type="auto">
          <Table striped miw={820} data-testid="tabla-incidencias">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Fecha</Table.Th>
                <Table.Th>ESM</Table.Th>
                <Table.Th>Descripción</Table.Th>
                <Table.Th>Fuente</Table.Th>
                <Table.Th>Estado</Table.Th>
                {registra && <Table.Th />}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {lista.data.length === 0 && (
                <Table.Tr>
                  <Table.Td colSpan={registra ? 6 : 5}>No hay incidencias registradas.</Table.Td>
                </Table.Tr>
              )}
              {lista.data.map((i) => (
                <Table.Tr key={i.id} data-testid={'incidencia-' + i.id} style={{ verticalAlign: 'top' }}>
                  <Table.Td style={{ whiteSpace: 'nowrap' }}>{i.fecha}</Table.Td>
                  <Table.Td>
                    {i.punto} ({i.fuerza})
                  </Table.Td>
                  <Table.Td style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{i.descripcion}</Table.Td>
                  <Table.Td>{i.fuente ?? '—'}</Table.Td>
                  <Table.Td>
                    <Badge color={i.estado === 'abierta' ? 'orange.4' : 'gray.7'} variant="filled" autoContrast>
                      {i.estado === 'abierta' ? 'Abierta' : 'Cerrada'}
                    </Badge>
                    {i.motivoCierre && (
                      <Text size="xs" c="dimmed" mt={2}>
                        {i.motivoCierre}
                      </Text>
                    )}
                  </Table.Td>
                  {registra && (
                    <Table.Td>
                      {i.estado === 'abierta' && (
                        <Button
                          size="compact-xs"
                          variant="light"
                          onClick={() => {
                            setMotivo('');
                            setCerrando(i);
                          }}
                        >
                          Cerrar
                        </Button>
                      )}
                    </Table.Td>
                  )}
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      )}
      <Modal opened={cerrando !== null} onClose={() => setCerrando(null)} title="Cerrar incidencia">
        {cerrando && (
          <Stack>
            <Text size="sm">
              {cerrando.punto} · {cerrando.fecha}
            </Text>
            <Textarea
              label="Motivo del cierre"
              required
              autosize
              minRows={3}
              maxLength={800}
              description={`${motivo.trim().length} de 800 caracteres (mínimo 10)`}
              value={motivo}
              onChange={(e) => setMotivo(e.currentTarget.value)}
              data-testid="motivo-cierre-incidencia"
            />
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setCerrando(null)}>
                Cancelar
              </Button>
              <Button color="red" disabled={!!problemaMotivo} loading={cerrar.isPending} onClick={() => cerrar.mutate(cerrando)}>
                Cerrar incidencia
              </Button>
            </Group>
          </Stack>
        )}
      </Modal>
    </Card>
  );
}
