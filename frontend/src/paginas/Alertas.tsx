import { Alert, Badge, Box, Button, Card, Center, Group, Loader, Modal, MultiSelect, Paper, ScrollArea, SegmentedControl, SimpleGrid, Stack, Switch, Table, Tabs, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import {
  bandejaCuasiReal,
  cuerpoEdicion,
  estadoEvento,
  latenciaLegible,
  nombrePerfil,
  REFRESCO_EVENTOS_MS,
  resumenCorreos,
  resumenSms,
  type CatalogoAlertas as DatosCatalogo,
  type EventoAlerta,
  type EventosAlertas as DatosEventos,
  type TipoAlerta,
} from '../shared/alertas';
import { api, ErrorApi } from '../shared/api';
import { puedeEntrar, useSesion } from '../shared/sesion';
import { EncabezadoPagina } from '../shared/EncabezadoPagina';
import { conteo, fechaHora } from '../shared/formato';
import { useValidacion } from '../shared/validacion';
import { conTexto } from '../shared/validaciones';

/**
 * Pantalla Alertas (Pantallas.ALERTAS: coordinación y, por regla, el administrador). El administrador (el único que entra a Usuarios)
 * ve además el catálogo; la coordinación solo los eventos.
 */
export function Alertas() {
  const esAdministrador = puedeEntrar(useSesion(), 'usuarios');
  return (
    <Stack>
      <EncabezadoPagina titulo="Alertas" />
      {esAdministrador ? (
        <Tabs defaultValue="eventos" keepMounted={false}>
          <Tabs.List>
            <Tabs.Tab value="eventos">Eventos</Tabs.Tab>
            <Tabs.Tab value="catalogo">Catálogo</Tabs.Tab>
          </Tabs.List>
          <Tabs.Panel value="eventos" pt="md">
            <EventosAlertas />
          </Tabs.Panel>
          <Tabs.Panel value="catalogo" pt="md">
            <CatalogoAlertas />
          </Tabs.Panel>
        </Tabs>
      ) : (
        <EventosAlertas />
      )}
    </Stack>
  );
}

const errorDe = (e: unknown, defecto: string) => (e instanceof ErrorApi ? e.message : defecto);

interface RespuestaGuardar {
  mensaje: string;
  destinatarios: number;
  sinTelefono: number;
}

/**
 * Catálogo de alertas (solo el administrador; GMI P77 b deja los destinatarios al Anexo Técnico, por eso es editable): cada tipo con
 * su criticidad, si está activo, si va además por SMS (solo las críticas) y sus destinatarios por perfil y por usuario.
 */
export function CatalogoAlertas() {
  const datos = useQuery({ queryKey: ['alertas', 'catalogo'], queryFn: () => api<DatosCatalogo>('/api/alertas/catalogo') });
  const [editando, setEditando] = useState<TipoAlerta | null>(null);

  if (datos.isLoading) {
    return (
      <Center py="xl">
        <Loader aria-label="Cargando el catálogo de alertas" />
      </Center>
    );
  }
  if (datos.error || !datos.data) return <Alert color="red">{errorDe(datos.error, 'No se pudo cargar el catálogo de alertas.')}</Alert>;
  const { tipos } = datos.data;

  const destinatarios = (t: TipoAlerta) => (
    <Stack gap={2}>
      <Group gap={4}>
        {t.perfiles.map((p) => (
          <Badge key={p} variant="light" size="sm">
            {nombrePerfil(p)}
          </Badge>
        ))}
        {t.usuarios.length > 0 && (
          <Badge variant="outline" size="sm">
            {conteo(t.usuarios.length)} usuario{t.usuarios.length === 1 ? '' : 's'}
          </Badge>
        )}
        {t.perfiles.length === 0 && t.usuarios.length === 0 && (
          <Text size="sm" c="dimmed">
            Sin destinatarios
          </Text>
        )}
      </Group>
      <Text size="xs" c="dimmed">
        {conteo(t.destinatarios)} activo{t.destinatarios === 1 ? '' : 's'}
        {t.critica && t.sms && t.sinTelefono > 0 ? ` · ${conteo(t.sinTelefono)} sin móvil válido (solo correo)` : ''}
      </Text>
    </Stack>
  );
  const insignias = (t: TipoAlerta) => (
    <>
      <Badge color={t.critica ? 'red' : 'blue'} variant="light">
        {t.critica ? 'Crítica' : 'Operativa'}
      </Badge>
      <Badge color={t.activa ? 'green' : 'gray'} data-testid="estado-alerta">
        {t.activa ? 'Activa' : 'Inactiva'}
      </Badge>
      <Badge color={t.critica && t.sms ? 'orange' : 'gray'} variant="outline">
        {t.critica ? (t.sms ? 'Correo y SMS' : 'Solo correo') : 'Solo correo'}
      </Badge>
    </>
  );
  const editar = (t: TipoAlerta) => (
    <Button size="xs" variant="light" aria-label={'Editar la alerta ' + t.nombre} onClick={() => setEditando(t)}>
      Editar
    </Button>
  );

  return (
    <Stack>
      <Text size="sm" c="dimmed">
        Las alertas críticas (continuidad, seguridad, disponibilidad o integridad de la información) salen por correo y, si se marca, también por SMS
        al móvil registrado en Usuarios. Las operativas van solo por correo. Una alerta abierta no repite el aviso más de una vez por hora.
      </Text>

      <Stack hiddenFrom="sm" gap="sm" data-testid="tarjetas-alertas">
        {tipos.map((t) => (
          <Card key={t.tipo} withBorder padding="sm" data-testid={'tarjeta-alerta-' + t.tipo}>
            <Stack gap={6}>
              <Text fw={600}>{t.nombre}</Text>
              {t.descripcion && (
                <Text size="xs" c="dimmed">
                  {t.descripcion}
                </Text>
              )}
              <Group gap={4}>{insignias(t)}</Group>
              {destinatarios(t)}
              <Group justify="flex-end">{editar(t)}</Group>
            </Stack>
          </Card>
        ))}
      </Stack>

      <ScrollArea type="auto" visibleFrom="sm">
        <Table striped miw={760} data-testid="tabla-alertas">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Alerta</Table.Th>
              <Table.Th>Tipo</Table.Th>
              <Table.Th>Estado</Table.Th>
              <Table.Th>Canal</Table.Th>
              <Table.Th>Destinatarios</Table.Th>
              <Table.Th>Acciones</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {tipos.map((t) => (
              <Table.Tr key={t.tipo} data-testid={'fila-alerta-' + t.tipo}>
                <Table.Td>
                  <Text size="sm" fw={500}>
                    {t.nombre}
                  </Text>
                  {t.descripcion && (
                    <Text size="xs" c="dimmed">
                      {t.descripcion}
                    </Text>
                  )}
                </Table.Td>
                <Table.Td>
                  <Badge color={t.critica ? 'red' : 'blue'} variant="light">
                    {t.critica ? 'Crítica' : 'Operativa'}
                  </Badge>
                </Table.Td>
                <Table.Td>
                  <Badge color={t.activa ? 'green' : 'gray'} data-testid="estado-alerta">
                    {t.activa ? 'Activa' : 'Inactiva'}
                  </Badge>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{t.critica && t.sms ? 'Correo y SMS' : 'Solo correo'}</Text>
                </Table.Td>
                <Table.Td>{destinatarios(t)}</Table.Td>
                <Table.Td>{editar(t)}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </ScrollArea>

      <Modal opened={editando !== null} onClose={() => setEditando(null)} title={editando ? 'Editar alerta: ' + editando.nombre : ''} size="lg">
        {editando && <FormularioTipo key={editando.tipo} tipo={editando} datos={datos.data} alCerrar={() => setEditando(null)} />}
      </Modal>
    </Stack>
  );
}

function FormularioTipo({ tipo, datos, alCerrar }: { tipo: TipoAlerta; datos: DatosCatalogo; alCerrar: () => void }) {
  const qc = useQueryClient();
  const [form, setForm] = useState<TipoAlerta>(tipo);
  const v = useValidacion({
    etiquetas: { nombre: 'Nombre', activa: 'Activa', sms: 'Enviar también por SMS', perfiles: 'Destinatarios por perfil', usuarios: 'Destinatarios por usuario' },
    testid: 'error-alerta',
  });
  const guardar = useMutation({
    mutationFn: () => api<RespuestaGuardar>('/api/alertas/catalogo', { metodo: 'PUT', cuerpo: cuerpoEdicion(form) }),
    onSuccess: (r) => {
      notifications.show({ color: r.sinTelefono > 0 && form.critica && form.sms ? 'yellow' : 'green', message: r.mensaje });
      qc.invalidateQueries({ queryKey: ['alertas'] });
      alCerrar();
    },
    onError: (e) => v.errorServidor(e, 'No se pudo guardar la alerta.'),
  });
  const enviar = (e: FormEvent) => {
    e.preventDefault();
    // Solo lo obligatorio; el largo del nombre (3 a 80) lo revisa el servidor y su mensaje queda en el resumen
    if (!v.validar({ nombre: form.nombre.trim() ? null : 'Escriba el nombre de la alerta.' })) return;
    guardar.mutate();
  };
  const opcionesUsuarios = datos.usuarios.map((u) => ({ value: String(u.id), label: `${u.nombre} (${u.usuario})` }));
  // Un usuario elegido que ya no está en la lista (inactivo o sin perfil destino) se muestra por su número para poder quitarlo
  for (const id of form.usuarios) {
    if (!opcionesUsuarios.some((o) => o.value === String(id)))
      opcionesUsuarios.push({ value: String(id), label: `Usuario n.º ${id} (no disponible)` });
  }

  return (
    <form ref={v.ref} onSubmit={enviar} noValidate>
      <Stack>
        {v.resumen}
        {tipo.descripcion && (
          <Text size="sm" c="dimmed">
            {tipo.descripcion}
          </Text>
        )}
        <TextInput
          label="Nombre"
          required
          maxLength={80}
          value={form.nombre}
          error={v.error('nombre')}
          onChange={(e) => {
            setForm({ ...form, nombre: e.currentTarget.value });
            v.limpiar('nombre');
          }}
        />
        <Switch
          label="Activa"
          checked={form.activa}
          error={conTexto(v.error('activa'))}
          onChange={(e) => {
            setForm({ ...form, activa: e.currentTarget.checked });
            v.limpiar('activa');
          }}
        />
        <Switch
          label="Enviar también por SMS"
          description={
            form.critica
              ? 'Va al móvil colombiano registrado en Usuarios (+57 3xx xxx xxxx). El SMS no lleva datos personales: solo el tipo, la hora y «Revise la Suite DIGSA».'
              : 'Las alertas operativas van solo por correo: el SMS es para las críticas.'
          }
          disabled={!form.critica}
          checked={form.critica && form.sms}
          error={conTexto(v.error('sms'))}
          onChange={(e) => {
            setForm({ ...form, sms: e.currentTarget.checked });
            v.limpiar('sms');
          }}
        />
        <MultiSelect
          label="Destinatarios por perfil"
          description="Todos los usuarios activos con el perfil reciben la alerta."
          data={datos.perfiles.map((p) => ({ value: p, label: nombrePerfil(p) }))}
          value={form.perfiles}
          error={v.error('perfiles')}
          onChange={(x) => {
            setForm({ ...form, perfiles: x });
            v.limpiar('perfiles');
          }}
          clearable
          comboboxProps={{ withinPortal: true }}
        />
        <MultiSelect
          label="Destinatarios por usuario"
          description="Además de los perfiles (máximo 50)."
          placeholder={form.usuarios.length ? undefined : 'Buscar por nombre o usuario'}
          data={opcionesUsuarios}
          value={form.usuarios.map(String)}
          error={v.error('usuarios')}
          onChange={(x) => {
            setForm({ ...form, usuarios: x.map(Number) });
            v.limpiar('usuarios');
          }}
          searchable
          clearable
          nothingFoundMessage="Sin coincidencias"
          maxValues={50}
          comboboxProps={{ withinPortal: true }}
        />
        <Group justify="flex-end">
          <Button variant="default" onClick={alCerrar}>
            Cancelar
          </Button>
          <Button type="submit" loading={guardar.isPending}>
            Guardar
          </Button>
        </Group>
      </Stack>
    </form>
  );
}

/**
 * Eventos de alerta (administrador y coordinación): qué se disparó, cuándo, cuánto tardó el primer correo y el primer SMS (GMI P75,
 * «tiempo real o cuasi real») y su estado; la bandeja de salida con su p50/p95 de las últimas 24 h. Se refresca cada 30 s.
 */
export function EventosAlertas() {
  const qc = useQueryClient();
  const [vista, setVista] = useState<'abiertas' | 'recientes'>('abiertas');
  const [cerrando, setCerrando] = useState<EventoAlerta | null>(null);
  // Si el cierre falla, el diálogo se cierra y el foco queda en el aviso del error: el Modal no lo devuelve al botón de la fila (UI-28)
  const [sinRetorno, setSinRetorno] = useState(false);
  /** Error al cerrar una alerta: queda a la vista sobre la lista (el diálogo se cierra igual que antes). */
  const vCerrar = useValidacion({ testid: 'error-cerrar-alerta' });
  const datos = useQuery({
    queryKey: ['alertas', 'eventos', vista],
    queryFn: () => api<DatosEventos>(`/api/alertas/eventos?abiertas=${vista === 'abiertas'}&limite=100`),
    refetchInterval: REFRESCO_EVENTOS_MS,
  });
  const cerrar = useMutation({
    mutationFn: (id: number) => api<{ mensaje: string }>(`/api/alertas/eventos/${id}/cerrar`, { metodo: 'POST' }),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      vCerrar.reiniciar();
      setCerrando(null);
      qc.invalidateQueries({ queryKey: ['alertas', 'eventos'] });
    },
    onError: (e) => {
      setSinRetorno(true);
      vCerrar.errorServidor(e, 'No se pudo cerrar la alerta.');
      setCerrando(null);
      qc.invalidateQueries({ queryKey: ['alertas', 'eventos'] });
    },
  });

  const b = datos.data?.bandeja24h;
  const cuasi = b ? bandejaCuasiReal(b) : null;
  const eventos = datos.data?.eventos ?? [];

  const botonCerrar = (e: EventoAlerta) =>
    e.abierta ? (
      <Button size="xs" variant="light" color="red" aria-label={`Cerrar la alerta ${e.nombre} del ${e.creado ? fechaHora(e.creado) : ''}`} onClick={() => {
        setSinRetorno(false);
        setCerrando(e);
      }}>
        Cerrar
      </Button>
    ) : (
      <Text size="xs" c="dimmed">
        {e.cerradaEn ? 'Cerrada ' + fechaHora(e.cerradaEn) : '—'}
      </Text>
    );
  const insigniaEstado = (e: EventoAlerta) => {
    const s = estadoEvento(e);
    return (
      <Badge color={s.color} data-testid="estado-evento">
        {s.etiqueta}
      </Badge>
    );
  };

  return (
    <Stack>
      <Paper withBorder p="sm" data-testid="bandeja-alertas">
        <Stack gap="xs">
          <Group justify="space-between" wrap="wrap" gap="xs">
            <Text fw={600}>Bandeja de salida · últimas 24 h</Text>
            {cuasi !== null && (
              <Badge color={cuasi ? 'green' : 'orange'} variant="light">
                {cuasi ? 'Envío cuasi real' : 'Envío con demora'}
              </Badge>
            )}
          </Group>
          <SimpleGrid cols={{ base: 2, sm: 5 }} spacing="xs">
            <Indicador etiqueta="Latencia p50" valor={latenciaLegible(b?.latenciaP50Ms)} prueba="p50" />
            <Indicador etiqueta="Latencia p95" valor={latenciaLegible(b?.latenciaP95Ms)} prueba="p95" />
            <Indicador etiqueta="Pendientes" valor={conteo(b?.pendientes)} />
            <Indicador etiqueta="Enviando" valor={conteo(b?.enviando)} />
            <Indicador etiqueta="Fallidos" valor={conteo(b?.fallidos24h)} />
          </SimpleGrid>
          <Text size="xs" c="dimmed">
            Latencia: tiempo entre el evento y el envío del correo. La mitad de los envíos tarda menos que el p50 y el 95 % menos que el p95
            (evidencia del aviso en tiempo real o cuasi real). Se actualiza cada 30 segundos.
          </Text>
        </Stack>
      </Paper>

      <Group justify="space-between" wrap="wrap">
        <SegmentedControl
          aria-label="Eventos que se muestran"
          value={vista}
          onChange={(v) => setVista(v as 'abiertas' | 'recientes')}
          data={[
            { value: 'abiertas', label: 'Abiertas' },
            { value: 'recientes', label: 'Recientes' },
          ]}
        />
        <Button variant="subtle" size="xs" onClick={() => datos.refetch()} loading={datos.isFetching}>
          Actualizar
        </Button>
      </Group>

      {vCerrar.resumen}
      {datos.error && <Alert color="red">{errorDe(datos.error, 'No se pudieron cargar los eventos.')}</Alert>}
      {datos.isLoading && (
        <Center py="xl">
          <Loader aria-label="Cargando los eventos de alerta" />
        </Center>
      )}
      {datos.data && eventos.length === 0 && (
        <Text c="dimmed" data-testid="sin-eventos">
          {vista === 'abiertas' ? 'No hay alertas abiertas.' : 'No hay alertas recientes.'}
        </Text>
      )}

      {eventos.length > 0 && (
        <>
          <Stack hiddenFrom="sm" gap="sm" data-testid="tarjetas-eventos">
            {eventos.map((e) => (
              <Card key={e.id} withBorder padding="sm" data-testid={'evento-' + e.id}>
                <Stack gap={4}>
                  <Group justify="space-between" wrap="nowrap" align="flex-start">
                    <Text fw={600} size="sm">
                      {e.nombre}
                    </Text>
                    {insigniaEstado(e)}
                  </Group>
                  {e.detalle && (
                    <Text size="sm" style={{ overflowWrap: 'anywhere' }}>
                      {e.detalle}
                    </Text>
                  )}
                  <Text size="xs" c="dimmed">
                    Creada {fechaHora(e.creado)}
                    {(e.veces ?? 1) > 1 ? ` · ${conteo(e.veces)} veces, la última ${fechaHora(e.ultimo)}` : ''}
                  </Text>
                  <Text size="xs">
                    Correo: {latenciaLegible(e.latenciaCorreoMs)} · {resumenCorreos(e)}
                  </Text>
                  <Text size="xs">
                    SMS: {latenciaLegible(e.latenciaSmsMs)} · {resumenSms(e)}
                  </Text>
                  <Group justify="flex-end">{botonCerrar(e)}</Group>
                </Stack>
              </Card>
            ))}
          </Stack>

          <ScrollArea type="auto" visibleFrom="sm">
            <Table striped miw={900} data-testid="tabla-eventos">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Alerta</Table.Th>
                  <Table.Th>Detalle</Table.Th>
                  <Table.Th>Creada</Table.Th>
                  <Table.Th>Correo</Table.Th>
                  <Table.Th>SMS</Table.Th>
                  <Table.Th>Estado</Table.Th>
                  <Table.Th>Acciones</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {eventos.map((e) => (
                  <Table.Tr key={e.id} data-testid={'evento-' + e.id}>
                    <Table.Td>
                      <Text size="sm" fw={500}>
                        {e.nombre}
                      </Text>
                      <Badge size="xs" color={e.critica ? 'red' : 'blue'} variant="light">
                        {e.critica ? 'Crítica' : 'Operativa'}
                      </Badge>
                    </Table.Td>
                    <Table.Td style={{ maxWidth: 280, overflowWrap: 'anywhere' }}>
                      <Text size="sm">{e.detalle || '—'}</Text>
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm">{fechaHora(e.creado)}</Text>
                      {(e.veces ?? 1) > 1 && (
                        <Text size="xs" c="dimmed">
                          {conteo(e.veces)} veces; última {fechaHora(e.ultimo)}
                        </Text>
                      )}
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm">{latenciaLegible(e.latenciaCorreoMs)}</Text>
                      <Text size="xs" c="dimmed">
                        {resumenCorreos(e)}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm">{latenciaLegible(e.latenciaSmsMs)}</Text>
                      <Text size="xs" c="dimmed">
                        {resumenSms(e)}
                      </Text>
                    </Table.Td>
                    <Table.Td>{insigniaEstado(e)}</Table.Td>
                    <Table.Td>{botonCerrar(e)}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollArea>
        </>
      )}

      <Modal opened={cerrando !== null} onClose={() => setCerrando(null)} title="Cerrar la alerta" returnFocus={!sinRetorno}>
        {cerrando && (
          <Stack>
            <Text size="sm">
              ¿Cerrar la alerta «{cerrando.nombre}» creada el {fechaHora(cerrando.creado)}? Si el problema vuelve a ocurrir se abrirá una nueva y se
              avisará de nuevo.
            </Text>
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setCerrando(null)}>
                Cancelar
              </Button>
              <Button color="red" loading={cerrar.isPending} onClick={() => cerrar.mutate(cerrando.id)}>
                Sí, cerrar
              </Button>
            </Group>
          </Stack>
        )}
      </Modal>
    </Stack>
  );
}

function Indicador({ etiqueta, valor, prueba }: { etiqueta: string; valor: string; prueba?: string }) {
  return (
    <Box>
      <Text size="xs" c="dimmed">
        {etiqueta}
      </Text>
      <Text fw={700} size="lg" data-testid={prueba ? 'indicador-' + prueba : undefined}>
        {valor}
      </Text>
    </Box>
  );
}
