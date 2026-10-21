import { Alert, Anchor, Badge, Button, Card, Center, Checkbox, Group, List, Loader, Modal, MultiSelect, ScrollArea, Select, SimpleGrid, Stack, Table, Text, Textarea, TextInput, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { colorEstado, horaCorta, type DetalleVisita, type OpcionesAgenda } from '../shared/agenda';
import { api, ErrorApi } from '../shared/api';
import { FormularioVisita, MAX_OTROS } from '../shared/FormularioVisita';
import { COLOR_ESTADO, COLOR_FASE, conAvisoFallido, NOMBRE_ESTADO, NOMBRE_FASE, NOMBRE_PLAN, type HallazgoOpl, type OplDeVisita } from '../shared/opl';

export type OrigenVisita = 'agenda' | 'mis-visitas' | 'calendario';

interface Respuesta {
  mensaje: string;
  ok?: boolean;
  notificacionOk?: boolean | null;
  advertencias?: string[] | null;
}

interface Destinatario {
  correo: string;
  origen: string;
  obligatorio: boolean;
}

interface Borrador {
  destinatarios: Destinatario[];
  faltan: string[];
  asunto: string;
  cuerpo: string;
}

type Dialogo = 'editar' | 'reprogramar' | 'cancelar' | 'reasignar' | 'notificar' | 'reabrir' | null;

const TITULOS: Record<OrigenVisita, string> = { agenda: 'Agenda', 'mis-visitas': 'Mis visitas', calendario: 'Calendario' };

/** Detalle de una visita: el mismo componente sirve a la agenda del coordinador, a Mis visitas y al calendario de consulta. */
export function VisitaDetalle({ origen }: { origen: OrigenVisita }) {
  const { id } = useParams();
  const qc = useQueryClient();
  const base = `/api/${origen}/${id}`;
  const clave = ['visita', origen, id];
  const detalle = useQuery({ queryKey: clave, queryFn: () => api<DetalleVisita>(base) });
  const opciones = useQuery({
    queryKey: ['agenda', 'opciones'],
    queryFn: () => api<OpcionesAgenda>('/api/agenda/opciones'),
    enabled: origen === 'agenda',
  });
  const [dialogo, setDialogo] = useState<Dialogo>(null);
  const navegar = useNavigate();
  const [destinoParte, setDestinoParte] = useState<Record<string, string | null>>({});

  const refrescar = () => {
    qc.invalidateQueries({ queryKey: clave });
    qc.invalidateQueries({ queryKey: ['agenda'] });
    qc.invalidateQueries({ queryKey: ['formulario', id] });
  };

  const accion = useMutation({
    mutationFn: ({ ruta, cuerpo }: { ruta: string; cuerpo?: unknown }) => api<Respuesta>(ruta, { metodo: 'POST', cuerpo: cuerpo ?? {} }),
    onSuccess: (r) => {
      // Fallo del envío manual (ok) o de la notificación automática al reprogramar o reasignar (notificacionOk): aviso que no se cierra solo
      const fallo = r.ok === false || r.notificacionOk === false;
      notifications.show({ color: fallo ? 'orange' : 'green', autoClose: fallo ? false : 6000, message: r.mensaje });
      for (const a of r.advertencias ?? []) notifications.show({ color: 'orange', autoClose: 10000, message: a });
      if (r.ok !== false) setDialogo(null); // si el envío manual falló, el diálogo queda abierto con lo editado
      refrescar();
    },
    onError: (e) => notifications.show({ color: 'red', message: e instanceof ErrorApi ? e.message : 'No se pudo completar la acción.' }),
  });

  if (detalle.isLoading) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    );
  }
  if (detalle.error || !detalle.data) {
    return (
      <Stack>
        <Anchor component={Link} to={'/' + origen}>
          Volver a {TITULOS[origen]}
        </Anchor>
        <Alert color="red">{detalle.error?.message ?? 'No se encontró la visita.'}</Alert>
      </Stack>
    );
  }
  const v = detalle.data;
  const deAgenda = origen === 'agenda';
  const puede = (a: string) => deAgenda && v.acciones.includes(a);
  const equipo = v.equipo ?? [];

  return (
    <Stack data-testid="detalle-visita">
      <Anchor component={Link} to={'/' + origen} size="sm">
        Volver a {TITULOS[origen]}
      </Anchor>
      <Group justify="space-between">
        <Group gap="sm">
          <Title order={3}>Visita {v.numero}</Title>
          <Badge color={colorEstado(v.estado)} size="lg" data-testid="estado-visita">
            {v.estadoNombre}
          </Badge>
        </Group>
        <Group gap="xs">
          {puede('editar') && (
            <Button variant="light" onClick={() => setDialogo('editar')} disabled={!opciones.data}>
              Editar
            </Button>
          )}
          {puede('reasignar') && (
            <Button variant="light" onClick={() => setDialogo('reasignar')} disabled={!opciones.data}>
              Reasignar equipo
            </Button>
          )}
          {puede('reprogramar') && (
            <Button variant="light" onClick={() => setDialogo('reprogramar')}>
              Reprogramar
            </Button>
          )}
          {puede('notificar') && (
            <Button variant="light" onClick={() => setDialogo('notificar')}>
              Notificar
            </Button>
          )}
          {puede('cancelar') && (
            <Button variant="light" color="red" onClick={() => setDialogo('cancelar')}>
              Cancelar visita
            </Button>
          )}
          {puede('reabrir') && (
            <Button variant="light" color="grape" data-testid="reabrir" onClick={() => setDialogo('reabrir')} disabled={!opciones.data}>
              Reabrir
            </Button>
          )}
          {origen !== 'calendario' && (v.estado === 'ejecucion' || v.estado === 'cerrada') && !(origen === 'mis-visitas' && v.acciones.includes('iniciar')) && (
            <Button variant={v.estado === 'cerrada' ? 'light' : 'filled'} onClick={() => navegar(`/visita/${v.id}`)}>
              Abrir formulario
            </Button>
          )}
          {origen === 'mis-visitas' && v.acciones.includes('iniciar') && (
            <Button
              loading={accion.isPending}
              onClick={() =>
                accion.mutate({ ruta: `/api/mis-visitas/${v.id}/iniciar` }, { onSuccess: () => navegar(`/visita/${v.id}`) })
              }
            >
              {v.estado === 'ejecucion' ? 'Continuar' : 'Iniciar visita'}
            </Button>
          )}
        </Group>
      </Group>
      {opciones.error && <Alert color="red">No se pudieron cargar las opciones: {opciones.error.message}</Alert>}
      {v.equipoSinNotificar && deAgenda && (
        <Alert color="orange">El cambio de equipo aún no se ha notificado al OPL y a las DISANES.</Alert>
      )}
      {(v.advertencias ?? []).map((a, i) => (
        <Alert key={i} color="orange">
          {a}
        </Alert>
      ))}

      <Card withBorder>
        <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="xs">
          <Dato titulo="Fecha">
            {v.fechaLarga}
            {v.hora ? ` · ${horaCorta(v.hora)}` : ''}
          </Dato>
          <Dato titulo="Tipo">{v.tipoNombre}</Dato>
          <Dato titulo="Punto">{v.punto}</Dato>
          <Dato titulo="Unidad">
            {[v.unidad, v.fuerza, v.modalidad].filter(Boolean).join(' · ')}
          </Dato>
          {v.fechaSolicitud && <Dato titulo="Fecha de la solicitud">{v.fechaSolicitud}</Dato>}
          <Dato titulo="Versión del checklist">{v.checklistVersion ?? '—'}</Dato>
        </SimpleGrid>
        <Text size="xs" c="dimmed" mt="sm">
          Alcance específico
        </Text>
        <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
          {v.alcance || '—'}
        </Text>
      </Card>

      {v.equipo && (
        <Card withBorder>
          <Title order={5} mb="xs">
            Equipo
          </Title>
          <List spacing={4} size="sm">
            {equipo.map((m) => (
              <List.Item key={m.id}>
                <Group gap={6}>
                  <Text size="sm">
                    {m.nombre} · {m.rolNombre}
                  </Text>
                  {m.lider && <Badge size="sm">Líder</Badge>}
                  {!m.activo && (
                    <Badge size="sm" color="gray">
                      Inactivo
                    </Badge>
                  )}
                </Group>
              </List.Item>
            ))}
          </List>
        </Card>
      )}

      {v.componentes && (
        <Card withBorder>
          <Title order={5} mb="xs">
            Componentes
          </Title>
          {v.componentes.length === 0 ? (
            <Text size="sm" c="dimmed">
              Sin componentes.
            </Text>
          ) : (
            <List spacing={2} size="sm">
              {v.componentes.map((c) => (
                <List.Item key={c.codigo}>
                  {c.codigo} · {c.nombre}
                </List.Item>
              ))}
            </List>
          )}
        </Card>
      )}

      {v.reparto && (
        <Card withBorder data-testid={origen === 'mis-visitas' ? 'mis-partes' : 'asignacion'}>
          <Title order={5} mb="xs">
            {origen === 'mis-visitas' ? 'Mis partes del checklist' : 'Asignación'}
          </Title>
          <ScrollArea type="auto">
            <Table miw={puede('mover') ? 640 : 420}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Parte</Table.Th>
                  <Table.Th>Nombre</Table.Th>
                  <Table.Th>Auditor</Table.Th>
                  {puede('mover') && <Table.Th>Mover a</Table.Th>}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {v.reparto.map((p) => (
                  <Table.Tr key={p.parte}>
                    <Table.Td>{p.parte}</Table.Td>
                    <Table.Td>{p.nombre}</Table.Td>
                    <Table.Td>{p.auditor ?? '—'}</Table.Td>
                    {puede('mover') && (
                      <Table.Td>
                        <Group gap={4} wrap="nowrap">
                          <Select
                            size="xs"
                            aria-label={'Mover ' + p.parte + ' a'}
                            placeholder="Integrante"
                            data={equipo.filter((m) => m.id !== p.auditorId && m.activo).map((m) => ({ value: String(m.id), label: m.nombre }))}
                            value={destinoParte[p.parte] ?? null}
                            onChange={(x) => setDestinoParte({ ...destinoParte, [p.parte]: x })}
                          />
                          <Button
                            size="xs"
                            variant="light"
                            aria-label={'Mover ' + p.parte}
                            disabled={!destinoParte[p.parte] || accion.isPending}
                            onClick={() =>
                              accion.mutate(
                                { ruta: `/api/agenda/${v.id}/mover`, cuerpo: { parte: p.parte, usuarioId: Number(destinoParte[p.parte]) } },
                                { onSuccess: () => setDestinoParte({ ...destinoParte, [p.parte]: null }) },
                              )
                            }
                          >
                            Mover
                          </Button>
                        </Group>
                      </Table.Td>
                    )}
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollArea>
          {v.reparto.length === 0 && (
            <Text size="sm" c="dimmed">
              Sin partes asignadas.
            </Text>
          )}
        </Card>
      )}

      {origen !== 'calendario' && (v.estado === 'cerrada' || v.estado === 'ejecucion') && <RespuestaDelOpl id={v.id} />}

      {v.historial && (
        <Card withBorder>
          <Title order={5} mb="xs">
            Historial
          </Title>
          <ScrollArea type="auto">
            <Table miw={620} data-testid="historial-visita">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Fecha</Table.Th>
                  <Table.Th>Usuario</Table.Th>
                  <Table.Th>Evento</Table.Th>
                  <Table.Th>Detalle</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {v.historial.map((h, i) => (
                  <Table.Tr key={i}>
                    <Table.Td style={{ whiteSpace: 'nowrap' }}>{formatoFecha(h.fecha)}</Table.Td>
                    <Table.Td>{h.usuario ?? '—'}</Table.Td>
                    <Table.Td>{h.evento}</Table.Td>
                    <Table.Td>{h.detalle ?? ''}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollArea>
        </Card>
      )}

      {v.notificaciones && (
        <Card withBorder>
          <Title order={5} mb="xs">
            Notificaciones
          </Title>
          {v.notificaciones.length === 0 ? (
            <Text size="sm" c="dimmed">
              Aún no se ha enviado ninguna notificación.
            </Text>
          ) : (
            <ScrollArea type="auto">
              <Table miw={720} data-testid="notificaciones-visita">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>Fecha</Table.Th>
                    <Table.Th>Origen</Table.Th>
                    <Table.Th>Estado</Table.Th>
                    <Table.Th>Destinatarios</Table.Th>
                    <Table.Th>Asunto</Table.Th>
                    <Table.Th>Usuario</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {v.notificaciones.map((n, i) => (
                    <Table.Tr key={i}>
                      <Table.Td style={{ whiteSpace: 'nowrap' }}>{formatoFecha(n.creado)}</Table.Td>
                      <Table.Td>{n.origen}</Table.Td>
                      <Table.Td>
                        <Badge color={n.error ? 'red' : 'green'} variant="light" title={n.error ?? undefined}>
                          {n.estado}
                        </Badge>
                        {n.error && (
                          <Text size="xs" c="red">
                            {n.error}
                          </Text>
                        )}
                      </Table.Td>
                      <Table.Td>{n.destinatarios}</Table.Td>
                      <Table.Td>{n.asunto ?? ''}</Table.Td>
                      <Table.Td>{n.usuario ?? '—'}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </ScrollArea>
          )}
        </Card>
      )}

      {deAgenda && (
        <FormularioVisita
          abierto={dialogo === 'editar'}
          alCerrar={() => setDialogo(null)}
          opciones={opciones.data}
          visita={v}
          alGuardar={() => {
            setDialogo(null);
            refrescar();
          }}
        />
      )}
      <Modal opened={dialogo === 'reprogramar'} onClose={() => setDialogo(null)} title="Reprogramar visita">
        {dialogo === 'reprogramar' && (
          <Reprogramar
            fecha={v.fecha}
            hora={horaCorta(v.hora)}
            pendiente={accion.isPending}
            alCerrar={() => setDialogo(null)}
            alEnviar={(cuerpo) => accion.mutate({ ruta: `/api/agenda/${v.id}/reprogramar`, cuerpo })}
          />
        )}
      </Modal>
      <Modal opened={dialogo === 'cancelar'} onClose={() => setDialogo(null)} title="Cancelar visita">
        {dialogo === 'cancelar' && (
          <SoloMotivo
            texto={`La visita ${v.numero} quedará cancelada.`}
            boton="Cancelar visita"
            pendiente={accion.isPending}
            alCerrar={() => setDialogo(null)}
            alEnviar={(motivo) =>
              window.confirm(`¿Cancelar la visita ${v.numero}?`) && accion.mutate({ ruta: `/api/agenda/${v.id}/cancelar`, cuerpo: { motivo } })
            }
          />
        )}
      </Modal>
      <Modal opened={dialogo === 'reabrir'} onClose={() => setDialogo(null)} title="Reabrir visita">
        {dialogo === 'reabrir' && opciones.data && (
          <Reabrir
            numero={v.numero}
            motivos={opciones.data.motivosReapertura}
            pendiente={accion.isPending}
            alCerrar={() => setDialogo(null)}
            alEnviar={(cuerpo) => accion.mutate({ ruta: `/api/visitas/${v.id}/reabrir`, cuerpo })}
          />
        )}
      </Modal>
      <Modal opened={dialogo === 'reasignar'} onClose={() => setDialogo(null)} title="Reasignar equipo" size="lg">
        {dialogo === 'reasignar' && opciones.data && (
          <Reasignar
            opciones={opciones.data}
            liderInicial={equipo.find((m) => m.lider)?.id}
            otrosIniciales={equipo.filter((m) => !m.lider).map((m) => m.id)}
            pendiente={accion.isPending}
            alCerrar={() => setDialogo(null)}
            alEnviar={(cuerpo) => accion.mutate({ ruta: `/api/agenda/${v.id}/reasignar`, cuerpo })}
          />
        )}
      </Modal>
      <Modal opened={dialogo === 'notificar'} onClose={() => setDialogo(null)} title="Notificar la visita" size="lg">
        {dialogo === 'notificar' && (
          <Notificar
            id={v.id}
            pendiente={accion.isPending}
            alCerrar={() => setDialogo(null)}
            alEnviar={(cuerpo) => accion.mutate({ ruta: `/api/agenda/${v.id}/notificar`, cuerpo })}
          />
        )}
      </Modal>
    </Stack>
  );
}

/** Fecha del servidor (ISO con zona o "YYYY-MM-DD HH:MM") en formato corto. */
function formatoFecha(f: string): string {
  return f.replace('T', ' ').substring(0, 16);
}

function Dato({ titulo, children }: { titulo: string; children: ReactNode }) {
  return (
    <div>
      <Text size="xs" c="dimmed">
        {titulo}
      </Text>
      <Text size="sm">{children}</Text>
    </div>
  );
}

function Botones({ boton, pendiente, alCerrar, color }: { boton: string; pendiente: boolean; alCerrar: () => void; color?: string }) {
  return (
    <Group justify="flex-end">
      <Button variant="default" onClick={alCerrar}>
        Volver
      </Button>
      <Button type="submit" loading={pendiente} color={color}>
        {boton}
      </Button>
    </Group>
  );
}

function Reprogramar({
  fecha: f0,
  hora: h0,
  pendiente,
  alCerrar,
  alEnviar,
}: {
  fecha: string;
  hora: string;
  pendiente: boolean;
  alCerrar: () => void;
  alEnviar: (c: { fecha: string; hora: string | null; motivo: string; notificar: boolean }) => void;
}) {
  const [fecha, setFecha] = useState(f0);
  const [hora, setHora] = useState(h0);
  const [motivo, setMotivo] = useState('');
  const [notificar, setNotificar] = useState(true);
  const enviar = (e: FormEvent) => {
    e.preventDefault();
    alEnviar({ fecha, hora: hora || null, motivo, notificar });
  };
  return (
    <form onSubmit={enviar}>
      <Stack>
        <SimpleGrid cols={{ base: 1, xs: 2 }}>
          <TextInput label="Fecha" type="date" required value={fecha} onChange={(e) => setFecha(e.currentTarget.value)} />
          <TextInput label="Hora" type="time" value={hora} onChange={(e) => setHora(e.currentTarget.value)} />
        </SimpleGrid>
        <Textarea label="Motivo" required autosize minRows={2} maxLength={800} value={motivo} onChange={(e) => setMotivo(e.currentTarget.value)} />
        <Checkbox label="Notificar la nueva fecha" checked={notificar} onChange={(e) => setNotificar(e.currentTarget.checked)} />
        <Botones boton="Reprogramar" pendiente={pendiente} alCerrar={alCerrar} />
      </Stack>
    </form>
  );
}

function SoloMotivo({
  texto,
  boton,
  pendiente,
  alCerrar,
  alEnviar,
}: {
  texto: string;
  boton: string;
  pendiente: boolean;
  alCerrar: () => void;
  alEnviar: (motivo: string) => void;
}) {
  const [motivo, setMotivo] = useState('');
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        alEnviar(motivo);
      }}
    >
      <Stack>
        <Text size="sm">{texto}</Text>
        <Textarea label="Motivo" required autosize minRows={2} maxLength={800} value={motivo} onChange={(e) => setMotivo(e.currentTarget.value)} />
        <Botones boton={boton} pendiente={pendiente} alCerrar={alCerrar} color="red" />
      </Stack>
    </form>
  );
}

function Reabrir({
  numero,
  motivos,
  pendiente,
  alCerrar,
  alEnviar,
}: {
  numero: string;
  motivos: Record<string, string>;
  pendiente: boolean;
  alCerrar: () => void;
  alEnviar: (c: { motivo: string; descripcion: string }) => void;
}) {
  const [motivo, setMotivo] = useState<string | null>(null);
  const [descripcion, setDescripcion] = useState('');
  const corta = descripcion.trim().length < 10;
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        if (motivo && !corta) alEnviar({ motivo, descripcion: descripcion.trim() });
      }}
    >
      <Stack>
        <Text size="sm">La visita {numero} vuelve a ejecución en una nueva ronda; al enviar el acta otra vez se genera una nueva versión.</Text>
        <Select
          label="Motivo"
          required
          allowDeselect={false}
          data={Object.entries(motivos).map(([value, label]) => ({ value, label }))}
          value={motivo}
          onChange={setMotivo}
        />
        <Textarea
          label="Descripción"
          required
          autosize
          minRows={3}
          minLength={10}
          maxLength={800}
          description="Entre 10 y 800 caracteres."
          value={descripcion}
          onChange={(e) => setDescripcion(e.currentTarget.value)}
        />
        <Group justify="flex-end">
          <Button variant="default" onClick={alCerrar}>
            Volver
          </Button>
          <Button type="submit" loading={pendiente} disabled={!motivo || corta}>
            Reabrir visita
          </Button>
        </Group>
      </Stack>
    </form>
  );
}

function Reasignar({
  opciones,
  liderInicial,
  otrosIniciales,
  pendiente,
  alCerrar,
  alEnviar,
}: {
  opciones: OpcionesAgenda;
  liderInicial: number | undefined;
  otrosIniciales: number[];
  pendiente: boolean;
  alCerrar: () => void;
  alEnviar: (c: { liderId: number; otros: number[]; motivo: string }) => void;
}) {
  const [lider, setLider] = useState<string | null>(liderInicial ? String(liderInicial) : null);
  const [otros, setOtros] = useState<string[]>(otrosIniciales.map(String));
  const [motivo, setMotivo] = useState('');
  const datos = opciones.auditores.map((a) => ({ value: String(a.id), label: `${a.nombre} · ${opciones.roles[a.rol] ?? a.rol}` }));
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        if (lider) alEnviar({ liderId: Number(lider), otros: otros.filter((o) => o !== lider).map(Number), motivo });
      }}
    >
      <Stack>
        <Select label="Auditor líder" required searchable data={datos} value={lider} onChange={(x) => {
          setLider(x);
          setOtros(otros.filter((o) => o !== x));
        }} />
        <MultiSelect
          label="Otros auditores"
          searchable
          maxValues={MAX_OTROS}
          data={datos.filter((d) => d.value !== lider)}
          value={otros}
          onChange={setOtros}
        />
        <Textarea label="Motivo" required autosize minRows={2} maxLength={800} value={motivo} onChange={(e) => setMotivo(e.currentTarget.value)} />
        <Text size="xs" c="dimmed">
          La notificación del cambio de equipo se reenvía al OPL y a las DISANES (obligatorio).
        </Text>
        <Botones boton="Reasignar" pendiente={pendiente} alCerrar={alCerrar} />
      </Stack>
    </form>
  );
}

function Notificar({
  id,
  pendiente,
  alCerrar,
  alEnviar,
}: {
  id: number;
  pendiente: boolean;
  alCerrar: () => void;
  alEnviar: (c: { destinatarios: string[]; asunto: string; cuerpo: string }) => void;
}) {
  const borrador = useQuery({
    queryKey: ['visita', 'notificacion', id],
    queryFn: () => api<Borrador>(`/api/agenda/${id}/notificacion`),
    gcTime: 0,
  });
  if (borrador.isLoading) {
    return (
      <Center py="md">
        <Loader />
      </Center>
    );
  }
  if (borrador.error || !borrador.data) {
    return <Alert color="red">{borrador.error?.message ?? 'No se pudo preparar la notificación.'}</Alert>;
  }
  return <FormNotificar borrador={borrador.data} pendiente={pendiente} alCerrar={alCerrar} alEnviar={alEnviar} />;
}

function FormNotificar({
  borrador,
  pendiente,
  alCerrar,
  alEnviar,
}: {
  borrador: Borrador;
  pendiente: boolean;
  alCerrar: () => void;
  alEnviar: (c: { destinatarios: string[]; asunto: string; cuerpo: string }) => void;
}) {
  const [elegidos, setElegidos] = useState<string[]>(borrador.destinatarios.map((d) => d.correo));
  const [asunto, setAsunto] = useState(borrador.asunto);
  const [cuerpo, setCuerpo] = useState(borrador.cuerpo);
  const obligatorios = borrador.destinatarios.filter((d) => d.obligatorio).map((d) => d.correo);
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        alEnviar({ destinatarios: Array.from(new Set([...obligatorios, ...elegidos])), asunto, cuerpo });
      }}
    >
      <Stack>
        {borrador.faltan.length > 0 && (
          <Alert color="red" title="Faltan destinatarios">
            <List size="sm">
              {borrador.faltan.map((f) => (
                <List.Item key={f}>{f}</List.Item>
              ))}
            </List>
          </Alert>
        )}
        <Checkbox.Group label="Destinatarios" value={elegidos} onChange={setElegidos}>
          <Stack gap={6} mt={6}>
            {borrador.destinatarios.map((d) => (
              <Checkbox
                key={d.correo}
                value={d.correo}
                label={`${d.correo} (${d.origen})`}
                disabled={d.obligatorio}
                description={d.obligatorio ? 'Obligatorio' : undefined}
              />
            ))}
          </Stack>
        </Checkbox.Group>
        <TextInput label="Asunto" required value={asunto} onChange={(e) => setAsunto(e.currentTarget.value)} />
        <Textarea label="Cuerpo" required autosize minRows={6} maxLength={6000} value={cuerpo} onChange={(e) => setCuerpo(e.currentTarget.value)} />
        <Botones boton="Enviar" pendiente={pendiente} alCerrar={alCerrar} />
      </Stack>
    </form>
  );
}

/**
 * Respuesta del OPL a los hallazgos del acta vista por el equipo y la coordinación: estado de cada hallazgo y, solo para el auditor
 * líder, la decisión sobre cada objeción (ratificar o levantar con motivo, una sola vez). Sin enlace del acta no se muestra.
 */
function RespuestaDelOpl({ id }: { id: number }) {
  const qc = useQueryClient();
  const clave = ['visita', 'opl', id];
  const datos = useQuery({ queryKey: clave, queryFn: () => api<OplDeVisita>(`/api/visitas/${id}/opl`) });
  const [decidir, setDecidir] = useState<{ h: HallazgoOpl; resolucion: 'ratifica' | 'levanta' } | null>(null);
  const resolver = useMutation({
    mutationFn: (c: { clave: string; resolucion: 'ratifica' | 'levanta'; motivo: string }) =>
      api<Respuesta>(`/api/visitas/${id}/opl/resolucion`, { metodo: 'POST', cuerpo: c }),
    onSuccess: (r) => {
      notifications.show({ color: conAvisoFallido(r.mensaje) ? 'orange' : 'green', autoClose: conAvisoFallido(r.mensaje) ? false : 6000, message: r.mensaje });
      setDecidir(null);
      qc.invalidateQueries({ queryKey: clave });
      qc.invalidateQueries({ queryKey: ['visita'] });
    },
    onError: (e) => notifications.show({ color: 'red', message: e instanceof ErrorApi ? e.message : 'No se pudo registrar la decisión.' }),
  });

  // Sin permiso o sin acta con hallazgos: la sección no aplica
  if (!datos.data || !datos.data.tieneEnlace) return null;
  const d = datos.data;
  return (
    <Card withBorder data-testid="respuesta-del-opl">
      <Group gap="sm" mb="xs">
        <Title order={5}>Respuesta del OPL</Title>
        <Badge color={COLOR_FASE[d.fase]}>{NOMBRE_FASE[d.fase]}</Badge>
        <Badge variant="light" color={d.plan.estado === 'firmado' ? 'green' : 'gray'}>
          Plan: {NOMBRE_PLAN[d.plan.estado]}
        </Badge>
      </Group>
      <Text size="sm" c="dimmed">
        Acta enviada el {d.enviado}. Aceptar u objetar hasta el {d.venceObjecionesLargo}; plan firmado hasta el {d.vencePlanLargo}.
      </Text>
      <Stack gap="xs" mt="sm">
        {d.hallazgos.map((h) => (
          <Card key={h.clave} withBorder p="sm" data-testid={'opl-' + h.codigo}>
            <Group justify="space-between" gap="xs" wrap="wrap">
              <Text size="sm" fw={600}>
                {h.codigo} · {h.texto}
              </Text>
              <Badge color={COLOR_ESTADO[h.estado]} variant="light">
                {NOMBRE_ESTADO[h.estado]}
              </Badge>
            </Group>
            {h.respondio && (
              <Text size="xs" c="dimmed">
                Respondió: {h.respondio}
              </Text>
            )}
            {h.decision === 'objeta' && h.justificacion && (
              <Text size="sm" mt={4} style={{ whiteSpace: 'pre-wrap' }}>
                <Text span fw={500}>
                  Objeción:{' '}
                </Text>
                {h.justificacion}
              </Text>
            )}
            {h.soportes.length > 0 && (
              <Text size="xs" c="dimmed">
                Soportes: {h.soportes.map((x) => x.nombre).join(', ')}
              </Text>
            )}
            {(h.quien || h.como) && (
              <Text size="xs" mt={4}>
                Quién: {h.quien ?? '—'} · Cómo: {h.como ?? '—'}
              </Text>
            )}
            {h.resolucion && (
              <Text size="sm" mt={4}>
                Decisión del líder: {h.resolucion === 'ratifica' ? 'ratificado' : 'levantado'} · {h.resolucionMotivo}
              </Text>
            )}
            {d.esLider && h.estado === 'objetado' && (
              <Group gap="xs" mt="xs">
                <Button size="xs" color="red" variant="light" onClick={() => setDecidir({ h, resolucion: 'ratifica' })}>
                  Ratificar
                </Button>
                <Button size="xs" color="green" variant="light" onClick={() => setDecidir({ h, resolucion: 'levanta' })}>
                  Levantar
                </Button>
              </Group>
            )}
          </Card>
        ))}
      </Stack>
      <Modal
        opened={decidir !== null}
        onClose={() => setDecidir(null)}
        title={decidir ? `${decidir.resolucion === 'ratifica' ? 'Ratificar' : 'Levantar'} el hallazgo ${decidir.h.codigo}` : ''}
      >
        {decidir && (
          <Decidir
            texto={
              decidir.resolucion === 'ratifica'
                ? 'El hallazgo sigue en el plan y se le pide al OPL diligenciar quién y cómo.'
                : 'El hallazgo sale del plan y del seguimiento. Si es el último vigente, el plan se cierra.'
            }
            boton={decidir.resolucion === 'ratifica' ? 'Ratificar' : 'Levantar'}
            pendiente={resolver.isPending}
            alCerrar={() => setDecidir(null)}
            alEnviar={(motivo) => resolver.mutate({ clave: decidir.h.clave, resolucion: decidir.resolucion, motivo })}
          />
        )}
      </Modal>
    </Card>
  );
}

function Decidir({
  texto,
  boton,
  pendiente,
  alCerrar,
  alEnviar,
}: {
  texto: string;
  boton: string;
  pendiente: boolean;
  alCerrar: () => void;
  alEnviar: (motivo: string) => void;
}) {
  const [motivo, setMotivo] = useState('');
  const corto = motivo.trim().length < 10;
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        if (!corto) alEnviar(motivo.trim());
      }}
    >
      <Stack>
        <Text size="sm">{texto} La decisión es una sola vez.</Text>
        <Textarea
          label="Motivo"
          required
          autosize
          minRows={3}
          minLength={10}
          maxLength={800}
          description="Entre 10 y 800 caracteres."
          value={motivo}
          onChange={(e) => setMotivo(e.currentTarget.value)}
        />
        <Group justify="flex-end">
          <Button variant="default" onClick={alCerrar}>
            Volver
          </Button>
          <Button type="submit" loading={pendiente} disabled={corto}>
            {boton}
          </Button>
        </Group>
      </Stack>
    </form>
  );
}
