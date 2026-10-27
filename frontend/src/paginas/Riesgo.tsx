import {
  Alert,
  Badge,
  Box,
  Button,
  Card,
  Center,
  Group,
  Loader,
  Modal,
  NumberInput,
  type NumberInputHandlers,
  type NumberInputProps,
  ScrollArea,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  Textarea,
  TextInput,
  Title,
  UnstyledButton,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useId, useRef, useState } from 'react';
import { api, descargar, ErrorApi } from '../shared/api';
import { BarraHorizontal } from '../shared/Barras';
import { cifra, fecha } from '../shared/formato';
import { useSesion } from '../shared/sesion';
import { useValidacion } from '../shared/validacion';
import {
  camposPesos,
  conConsulta,
  cuerpoPesos,
  decimal,
  erroresIncidencia,
  EXPLICACION_FACTOR,
  FORMATOS_DESCARGA,
  hoyIso,
  nivelRiesgo,
  problemaCierre,
  puedeEditarPesos,
  puedeRegistrarIncidencias,
  sumaPesos,
  textoNivel,
  tonoTexto,
  type FilaRiesgo,
  type FormIncidencia,
  type Incidencia,
  type TableroRiesgo,
} from '../shared/tablero';
import { EncabezadoPagina } from '../shared/EncabezadoPagina';
import { BotonConMotivo } from '../shared/BotonConMotivo';
import { Kpi } from '../shared/Kpi';


/**
 * «Dashboard de riesgo» (docs/especificaciones/riesgo-tablero): puntaje explicable por ESM de dispensación, agregados por país y
 * fuerza, pesos y umbrales (editables por la coordinación) e incidencias reportadas por la DIGSA.
 */
export function Riesgo() {
  const sesion = useSesion();
  const descarga = useValidacion({ testid: 'error-descarga-riesgo' });
  const [fuerza, setFuerza] = useState('');
  const consulta = fuerza ? `fuerza=${encodeURIComponent(fuerza)}` : '';
  // La clave incluye el filtro: una respuesta vieja nunca se pinta sobre la del filtro actual
  const tablero = useQuery({
    queryKey: ['riesgo', 'tablero', fuerza],
    queryFn: () => api<TableroRiesgo>(conConsulta('/api/riesgo', consulta)),
    placeholderData: keepPreviousData,
  });

  const bajar = async (formato: string) => {
    descarga.reiniciar();
    try {
      await descargar(conConsulta('/api/riesgo/exportar', `formato=${formato}${consulta ? '&' + consulta : ''}`));
    } catch (e) {
      descarga.errorServidor(e, 'No se pudo descargar el dashboard de riesgo.');
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
      <EncabezadoPagina
        titulo="Dashboard de riesgo"
        fuente="Anexo 3 · Anexo 5 · 4.5"
        descripcion="Puntaje de 0 a 100 por ESM de dispensación, calculado con la última visita completa cerrada, los hallazgos recurrentes, la intervención inmediata, los planes vencidos y las incidencias reportadas por la DIGSA. Los días sin visita se muestran como dato: la prioridad por antigüedad la da la agenda."
      />
      <Alert color="yellow" variant="light" data-testid="estado-pesos">
        Pesos y umbrales: {t.estadoPesos}
      </Alert>

      <Card withBorder>
        <Group align="flex-end" wrap="wrap">
          <Select
            label="Fuerza"
            placeholder="Todas"
            clearable
            clearButtonProps={{ 'aria-label': 'Quitar el filtro de fuerza' }}
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
        {descarga.resumen && <Box mt="sm">{descarga.resumen}</Box>}
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
      <Title order={2} size="h4">País</Title>
      <SimpleGrid cols={{ base: 2, sm: 4 }} data-testid="riesgo-pais">
        <Kpi titulo="ESM evaluados" valor={p.esm} />
        <Kpi titulo="Puntaje promedio" valor={decimal(p.promedio)} />
        <Kpi titulo={`Riesgo alto (≥ ${t.umbrales.alto})`} valor={p.niveles.alto} color="red" />
        <Kpi titulo={`Riesgo medio (≥ ${t.umbrales.medio}) / bajo`} valor={`${p.niveles.medio} / ${p.niveles.bajo}`} color="orange" />
      </SimpleGrid>
      <Card withBorder>
        <Title order={3} size="h5" mb="xs">
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
      <Title order={2} size="h4" mb="xs">
        Por fuerza
      </Title>
      {t.porFuerza.length === 0 ? (
        <Text size="sm" c="dimmed">
          Sin ESM en su alcance.
        </Text>
      ) : (
        <>
          <Stack hiddenFrom="sm" gap="xs" data-testid="tarjetas-riesgo-fuerza">
            {t.porFuerza.map((f) => (
              <Card key={f.fuerza} withBorder padding="sm" data-testid={'tarjeta-fuerza-' + f.fuerza}>
                <Group justify="space-between" gap="xs" wrap="wrap">
                  <Title order={3} size="h5">{f.fuerza}</Title>
                  <Text size="sm" fw={600}>
                    Promedio {decimal(f.agregado.promedio)}
                  </Text>
                </Group>
                <Text size="sm">
                  {f.agregado.esm} ESM · alto {f.agregado.niveles.alto} · medio {f.agregado.niveles.medio} · bajo {f.agregado.niveles.bajo}
                </Text>
                <Text size="xs" c="dimmed" style={{ overflowWrap: 'anywhere' }}>
                  Factor principal: {f.principal}
                </Text>
              </Card>
            ))}
          </Stack>
          <ScrollArea type="auto" visibleFrom="sm">
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
        </>
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
      <Title order={2} size="h4" mb="xs">
        Puntaje por ESM
      </Title>
      <TarjetasEsm t={t} />
      <ScrollArea type="auto" visibleFrom="sm">
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
                      <Text size="xs" c="red" fw={600} data-testid={'sin-resumen-' + e.puntoId}>
                        Visita sin resumen: incumplimiento no medido
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td>{e.fuerza}</Table.Td>
                  <Table.Td ta="right">
                    <Text fw={700}>{cifra(e.total)}</Text>
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
                  <Table.Td>{e.ultimaCerrada ? fecha(e.ultimaCerrada) : 'Sin visita cerrada'}</Table.Td>
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

/**
 * Celular (menos de 768 px, duda 25.4): una tarjeta por ESM en el orden de la tabla, con el nombre como encabezado, el nivel en texto
 * («Riesgo alto, 72 de 100»), una barra por factor con «valor de peso», la última visita cerrada y los avisos. Sin desplazamiento
 * horizontal a 360 px.
 */
function TarjetasEsm({ t }: { t: TableroRiesgo }) {
  return (
    <Stack hiddenFrom="sm" gap="sm" data-testid="tarjetas-riesgo">
      {t.esm.length === 0 && (
        <Text size="sm" c="dimmed">
          Sin ESM en su alcance.
        </Text>
      )}
      {t.esm.map((e) => {
        const n = nivelRiesgo(e.nivel);
        return (
          <Card key={e.puntoId} withBorder padding="sm" data-testid={'tarjeta-riesgo-' + e.puntoId} style={{ minWidth: 0 }}>
            <Title order={3} size="h5" style={{ overflowWrap: 'anywhere' }}>
              {e.nombre}
            </Title>
            <Text size="xs" c="dimmed" style={{ overflowWrap: 'anywhere' }}>
              {e.fuerza}
              {e.municipio ? ` · ${e.municipio}` : ''}
            </Text>
            <Text fw={700} c={tonoTexto(n.color)} mt={4} data-testid={'nivel-riesgo-' + e.puntoId}>
              {textoNivel(e.nivel, e.total)}
            </Text>
            {e.sinResumen && (
              <Text size="xs" c="red" fw={600}>
                Visita sin resumen: incumplimiento no medido
              </Text>
            )}
            <Stack gap={4} mt="xs">
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
            <Text size="sm" mt="xs">
              {e.ultimaCerrada
                ? `Última visita cerrada: ${fecha(e.ultimaCerrada)}${e.diasSinVisita !== null ? ` · ${e.diasSinVisita} días` : ''}`
                : 'Sin visita cerrada'}
            </Text>
            <Text size="xs" c="dimmed">
              {textoDatos(e.datos).join(' · ')}
            </Text>
          </Card>
        );
      })}
    </Stack>
  );
}

/**
 * Campo entero con botones de subir y bajar solo para el ratón: quedan fuera del orden de tabulación y del árbol de accesibilidad (los de
 * NumberInput no tienen nombre y la auditoría U0 los contó). Con el teclado, las flechas arriba y abajo suben y bajan el valor.
 */
function Entero({
  etiqueta,
  describidoPor,
  ...props
}: Omit<NumberInputProps, 'label' | 'handlersRef' | 'rightSection'> & { etiqueta: string; describidoPor?: string }) {
  const pasos = useRef<NumberInputHandlers>(undefined);
  const campo = useRef<HTMLInputElement>(null);
  // Mantine pone su propio aria-describedby (vacío si el campo no tiene descripción ni texto de error, como aquí, donde el error solo
  // marca): el enlace con el aviso de abajo se pone directo en el campo
  useEffect(() => {
    const el = campo.current;
    if (!el) return;
    if (describidoPor) el.setAttribute('aria-describedby', describidoPor);
    else el.removeAttribute('aria-describedby');
  }, [describidoPor, props.error]);
  const boton = (direccion: 'Subir' | 'Bajar', simbolo: string) => (
    <UnstyledButton
      aria-hidden
      tabIndex={-1}
      disabled={props.disabled}
      onMouseDown={(e) => e.preventDefault()}
      onClick={() => (direccion === 'Subir' ? pasos.current?.increment() : pasos.current?.decrement())}
      fz={10}
      lh={1}
      px={6}
      py={2}
      c="dimmed"
    >
      {simbolo}
    </UnstyledButton>
  );
  return (
    <NumberInput
      {...props}
      ref={campo}
      label={etiqueta}
      handlersRef={pasos}
      // Por omisión la sección derecha deja pasar el puntero: los botones propios deben recibir el clic
      rightSectionPointerEvents={props.disabled ? 'none' : 'all'}
      rightSection={
        <Stack gap={0}>
          {boton('Subir', '▲')}
          {boton('Bajar', '▼')}
        </Stack>
      }
    />
  );
}

/** Pesos por factor y umbrales de nivel: la coordinación los edita (14.1, con bitácora); los perfiles de consulta solo los ven. */
function PanelPesos({ t, editable }: { t: TableroRiesgo; editable: boolean }) {
  const qc = useQueryClient();
  const v = useValidacion({ testid: 'error-pesos' });
  const [pesos, setPesos] = useState<Record<string, number | string>>(() => ({ ...t.pesos }));
  const [alto, setAlto] = useState<number | string>(t.umbrales.alto);
  const [medio, setMedio] = useState<number | string>(t.umbrales.medio);
  const revision = camposPesos(pesos, t.factores, t.nombres, alto, medio);
  const problema = revision?.mensaje ?? null;
  // El campo que falla queda marcado; el mensaje va una sola vez, en el texto de abajo (data-testid «problema-pesos»)
  const marcado = (campo: string) => revision?.campos.includes(campo) || undefined;
  // El aviso vive en un contenedor fijo que anuncia sus cambios sin interrumpir; los campos marcados lo referencian
  const idProblema = useId();
  const idAyuda = useId();
  const describido = (campo: string) => (marcado(campo) ? idProblema : undefined);
  const cambiado = t.factores.some((f) => Number(pesos[f]) !== t.pesos[f]) || Number(alto) !== t.umbrales.alto || Number(medio) !== t.umbrales.medio;
  const guardar = useMutation({
    mutationFn: () =>
      api<{ mensaje: string }>('/api/riesgo/pesos', { metodo: 'PUT', cuerpo: cuerpoPesos(pesos, t.factores, Number(alto), Number(medio)) }),
    onSuccess: (r) => {
      v.reiniciar();
      notifications.show({ color: 'green', message: r.mensaje });
      void qc.invalidateQueries({ queryKey: ['riesgo'] });
    },
    onError: (e) => v.errorServidor(e, 'No se pudieron guardar los pesos.'),
  });

  return (
    <Card withBorder data-testid="panel-pesos" ref={v.ref}>
      <Title order={2} size="h4">Pesos y umbrales</Title>
      <Text size="sm" c="dimmed" mb="sm">
        {t.estadoPesos}. Cada factor suma hasta su peso; los pesos suman 100. Nivel alto con puntaje ≥ {t.umbrales.alto}, medio con ≥{' '}
        {t.umbrales.medio} y bajo por debajo.
        {!editable && ' Solo la coordinación cambia los pesos.'}
      </Text>
      <Stack gap="sm">
        {t.factores.map((f) => (
          <Group key={f} align="flex-start" wrap="wrap" gap="sm">
            {editable ? (
              <Entero
                etiqueta={t.nombres[f] ?? f}
                min={0}
                max={100}
                step={1}
                allowDecimal={false}
                allowNegative={false}
                w={170}
                value={pesos[f] ?? ''}
                onChange={(x) => setPesos((p) => ({ ...p, [f]: x }))}
                error={marcado(f)}
                describidoPor={describido(f)}
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
            <Text size="sm" c={sumaPesos(pesos, t.factores) === 100 ? 'dimmed' : 'red'}>
              Suma de los pesos: {sumaPesos(pesos, t.factores)} de 100
            </Text>
            <Group wrap="wrap">
              <Entero
                etiqueta="Umbral alto"
                min={1}
                max={100}
                allowDecimal={false}
                allowNegative={false}
                w={140}
                value={alto}
                onChange={setAlto}
                error={marcado('alto')}
                describidoPor={describido('alto')}
              />
              <Entero
                etiqueta="Umbral medio"
                min={1}
                max={99}
                allowDecimal={false}
                allowNegative={false}
                w={140}
                value={medio}
                onChange={setMedio}
                error={marcado('medio')}
                describidoPor={describido('medio')}
              />
            </Group>
            <div id={idProblema} aria-live="polite">
              {problema && (
                <Text size="sm" c="red" data-testid="problema-pesos">
                  {problema}
                </Text>
              )}
            </div>
            {v.resumen}
            <Group>
              <BotonConMotivo
                data-testid="guardar-pesos"
                motivo={problema ? problema : !cambiado ? 'Cambie un peso o un umbral para guardar.' : null}
                idMotivo={problema ? idProblema : idAyuda}
                loading={guardar.isPending}
                onClick={() => guardar.mutate()}
              >
                Guardar pesos
              </BotonConMotivo>
              {!problema && !cambiado && (
                <Text size="sm" c="dimmed" id={idAyuda}>
                  Cambie un peso o un umbral para guardar.
                </Text>
              )}
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
  const [cerrando, setCerrando] = useState<Incidencia | null>(null);
  // Si el cierre falla con 404, el diálogo se cierra y el foco queda en el aviso del error, no en el botón de la fila (UI-28)
  const [sinRetorno, setSinRetorno] = useState(false);
  const [motivo, setMotivo] = useState('');
  const hoy = hoyIso();
  const problemaMotivo = problemaCierre(motivo);
  const v = useValidacion({
    etiquetas: { puntoId: 'ESM', fecha: 'Fecha', descripcion: 'Descripción', fuente: 'Fuente' },
    testid: 'error-incidencia',
  });
  const vCierre = useValidacion({ etiquetas: { motivo: 'Motivo del cierre' }, testid: 'error-cierre-incidencia' });
  const idMotivo = useId();
  const vLista = useValidacion({ testid: 'error-incidencias' });
  const cambiar = (cambio: Partial<FormIncidencia>) => {
    setForm({ ...form, ...cambio });
    for (const k of Object.keys(cambio)) v.limpiar(k);
  };
  const abrirCierre = (i: Incidencia) => {
    setMotivo('');
    vCierre.reiniciar();
    vLista.reiniciar();
    setSinRetorno(false);
    setCerrando(i);
  };

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
      v.reiniciar();
      void qc.invalidateQueries({ queryKey: ['riesgo'] });
    },
    onError: (e) => {
      v.errorServidor(e, 'No se pudo registrar la incidencia.');
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
      // Cerrada por otro o inexistente (404): se cierra el diálogo, el aviso queda sobre la lista y se recarga la lista y el tablero
      if (e instanceof ErrorApi && e.estado === 404) {
        setSinRetorno(true);
        vLista.errorServidor(e, 'No se pudo cerrar la incidencia.');
        setCerrando(null);
        setMotivo('');
      } else {
        vCierre.errorServidor(e, 'No se pudo cerrar la incidencia.');
      }
      void qc.invalidateQueries({ queryKey: ['riesgo'] });
    },
  });

  return (
    <Card withBorder data-testid="incidencias">
      <Title order={2} size="h4">Incidencias reportadas por la DIGSA</Title>
      <Text size="sm" c="dimmed" mb="sm">
        Cada incidencia abierta de los últimos 12 meses suma al factor «Incidencias de la DIGSA» del ESM. Al cerrarla deja de sumar.
      </Text>
      {registra && (
        <Card withBorder mb="md" data-testid="form-incidencia" ref={v.ref}>
          <Title order={3} size="h5" mb="xs">
            Registrar incidencia
          </Title>
          {v.resumen && <Box mb="sm">{v.resumen}</Box>}
          <SimpleGrid cols={{ base: 1, sm: 2 }}>
            <Select
              label="ESM"
              required
              searchable
              placeholder="Elija el ESM"
              data={esm.map((e) => ({ value: String(e.puntoId), label: `${e.nombre} (${e.fuerza})` }))}
              value={form.puntoId || null}
              onChange={(x) => cambiar({ puntoId: x ?? '' })}
              nothingFoundMessage="Sin ESM con ese nombre"
              error={v.error('puntoId')}
            />
            <TextInput
              label="Fecha"
              type="date"
              required
              max={hoy}
              value={form.fecha}
              onChange={(e) => cambiar({ fecha: e.currentTarget.value })}
              error={v.error('fecha')}
            />
          </SimpleGrid>
          <Textarea
            mt="sm"
            label="Descripción"
            required
            rows={4}
            resize="vertical"
            maxLength={2000}
            description={`${form.descripcion.trim().length} de 2000 caracteres (mínimo 10)`}
            value={form.descripcion}
            onChange={(e) => cambiar({ descripcion: e.currentTarget.value })}
            error={v.error('descripcion')}
          />
          <TextInput
            mt="sm"
            label="Fuente"
            placeholder="Oficio, correo o reunión en que la DIGSA la reportó"
            maxLength={200}
            value={form.fuente}
            onChange={(e) => cambiar({ fuente: e.currentTarget.value })}
            error={v.error('fuente')}
          />
          <Group mt="sm">
            <Button
              loading={registrar.isPending}
              onClick={() => {
                if (v.validar(erroresIncidencia(form, hoy))) registrar.mutate();
              }}
            >
              Registrar incidencia
            </Button>
          </Group>
        </Card>
      )}
      {vLista.resumen && <Box mb="sm">{vLista.resumen}</Box>}
      {lista.isLoading && <Loader size="sm" />}
      {lista.error && <Alert color="red">{lista.error.message}</Alert>}
      {lista.data && (
        <Stack hiddenFrom="sm" gap="xs" data-testid="lista-incidencias">
          {lista.data.length === 0 && <Text size="sm">No hay incidencias registradas.</Text>}
          {lista.data.map((i) => (
            <Card key={i.id} withBorder padding="sm" data-testid={'incidencia-movil-' + i.id} style={{ minWidth: 0 }}>
              <Group justify="space-between" gap="xs" wrap="wrap">
                <Text size="sm" fw={600} style={{ overflowWrap: 'anywhere', minWidth: 0 }}>
                  {i.punto} ({i.fuerza})
                </Text>
                <Badge color={i.estado === 'abierta' ? 'orange.4' : 'gray.7'} variant="filled" autoContrast>
                  {i.estado === 'abierta' ? 'Abierta' : 'Cerrada'}
                </Badge>
              </Group>
              <Text size="xs" c="dimmed">
                {fecha(i.fecha)}
                {i.fuente ? ` · ${i.fuente}` : ''}
              </Text>
              <Text size="sm" mt={4} style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                {i.descripcion}
              </Text>
              {i.motivoCierre && (
                <Text size="xs" c="dimmed" mt={2} style={{ overflowWrap: 'anywhere' }}>
                  Cierre: {i.motivoCierre}
                </Text>
              )}
              {registra && i.estado === 'abierta' && (
                <Group mt="xs">
                  <Button
                    size="compact-sm"
                    variant="light"
                    onClick={() => abrirCierre(i)}
                  >
                    Cerrar
                  </Button>
                </Group>
              )}
            </Card>
          ))}
        </Stack>
      )}
      {lista.data && (
        <ScrollArea type="auto" visibleFrom="sm">
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
                  <Table.Td style={{ whiteSpace: 'nowrap' }}>{fecha(i.fecha)}</Table.Td>
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
                          onClick={() => abrirCierre(i)}
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
      <Modal opened={cerrando !== null} onClose={() => setCerrando(null)} title="Cerrar incidencia" returnFocus={!sinRetorno}>
        {cerrando && (
          <Stack ref={vCierre.ref}>
            <Text size="sm">
              {cerrando.punto} · {fecha(cerrando.fecha)}
            </Text>
            {vCierre.resumen}
            <Textarea
              label="Motivo del cierre"
              required
              rows={4}
              resize="vertical"
              maxLength={800}
              description={`${motivo.trim().length} de 800 caracteres (mínimo 10)`}
              value={motivo}
              onChange={(e) => {
                setMotivo(e.currentTarget.value);
                vCierre.limpiar('motivo');
              }}
              error={vCierre.error('motivo')}
              data-testid="motivo-cierre-incidencia"
            />
            {problemaMotivo && (
              <Text size="sm" c="dimmed" id={idMotivo}>
                {problemaMotivo}
              </Text>
            )}
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setCerrando(null)}>
                Cancelar
              </Button>
              <BotonConMotivo
                color="red"
                motivo={problemaMotivo || null}
                idMotivo={idMotivo}
                loading={cerrar.isPending}
                onClick={() => cerrar.mutate(cerrando)}
              >
                Cerrar incidencia
              </BotonConMotivo>
            </Group>
          </Stack>
        )}
      </Modal>
    </Card>
  );
}
