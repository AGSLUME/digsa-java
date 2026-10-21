import {
  Accordion,
  Alert,
  Anchor,
  Badge,
  Box,
  Button,
  Card,
  Center,
  Group,
  List,
  Loader,
  Modal,
  Paper,
  Radio,
  ScrollArea,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  Textarea,
  TextInput,
  Title,
  VisuallyHidden,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';
import { colorEstado, horaCorta } from '../shared/agenda';
import { api, ErrorApi, subirArchivos } from '../shared/api';
import {
  aFechaHoraLocal,
  conteo,
  cumplimientoLegible,
  ENTIDADES,
  ETIQUETA_CALIFICACION,
  respuestasCambiadas,
  ROLES_FIRMA,
  sinHallazgo,
  tamanoLegible,
  textoONulo,
  valorItem,
  type Archivo,
  type Calificacion,
  type DatosFormulario,
  type Edicion,
  type Ejecucion,
  type ItemChecklist,
  type PersonaAuditada,
  type RolFirma,
} from '../shared/checklist';
import { PanelFirma } from '../shared/PanelFirma';
import { puedeEntrar, useSesion } from '../shared/sesion';

interface Mensaje {
  mensaje: string;
}

interface Peticion {
  ruta: string;
  metodo: 'POST' | 'PUT' | 'DELETE';
  cuerpo?: unknown;
}

interface CamposEjecucion {
  inicioReal: string;
  finReal: string;
  objeto: string;
  recomendaciones: string;
  observacionesAuditor: string;
  observacionesEsm: string;
  observacionesOpl: string;
}

function camposIniciales(e: Ejecucion): CamposEjecucion {
  return {
    inicioReal: aFechaHoraLocal(e.inicioReal),
    finReal: aFechaHoraLocal(e.finReal),
    objeto: e.objeto ?? '',
    recomendaciones: e.recomendaciones ?? '',
    observacionesAuditor: e.observacionesAuditor ?? '',
    observacionesEsm: e.observacionesEsm ?? '',
    observacionesOpl: e.observacionesOpl ?? '',
  };
}

function cuerpoEjecucion(f: CamposEjecucion) {
  return {
    inicioReal: f.inicioReal || null,
    finReal: f.finReal || null,
    objeto: textoONulo(f.objeto),
    recomendaciones: textoONulo(f.recomendaciones),
    observacionesAuditor: textoONulo(f.observacionesAuditor),
    observacionesEsm: textoONulo(f.observacionesEsm),
    observacionesOpl: textoONulo(f.observacionesOpl),
  };
}

function avisarError(e: unknown, texto = 'No se pudo completar la acción.') {
  notifications.show({ color: 'red', autoClose: 10000, message: e instanceof ErrorApi ? e.message : texto });
}

/** Mutación con aviso del mensaje del servidor; refresca el formulario y la agenda. */
function usePeticion(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (p: Peticion) => api<Mensaje>(p.ruta, { metodo: p.metodo, cuerpo: p.cuerpo }),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      qc.invalidateQueries({ queryKey: ['formulario', id] });
      qc.invalidateQueries({ queryKey: ['agenda'] });
    },
    onError: (e) => avisarError(e),
  });
}

/** Formulario de ejecución de la visita: datos, personal auditado, checklist con evidencias, observaciones, firmas y acta. */
export function Checklist() {
  const { id = '' } = useParams();
  const s = useSesion();
  const formulario = useQuery({
    queryKey: ['formulario', id],
    queryFn: () => api<DatosFormulario>(`/api/visitas/${id}/formulario`),
    retry: false,
  });
  const volver = puedeEntrar(s, 'mis-visitas') ? `/mis-visitas/visita/${id}` : `/agenda/visita/${id}`;

  if (formulario.isLoading) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    );
  }
  if (formulario.error || !formulario.data) {
    return (
      <Stack>
        <Anchor component={Link} to={volver} size="sm">
          Volver a la visita
        </Anchor>
        <Alert color="red">{formulario.error?.message ?? 'No se encontró el formulario de la visita.'}</Alert>
      </Stack>
    );
  }
  return <Contenido key={id} id={id} d={formulario.data} volver={volver} />;
}

function Contenido({ id, d, volver }: { id: string; d: DatosFormulario; volver: string }) {
  const v = d.visita;
  const p = d.permisos;
  const [campos, setCampos] = useState<CamposEjecucion>(() => camposIniciales(d.ejecucion));
  const [ediciones, setEdiciones] = useState<Record<string, Edicion>>({});
  const guardarDatos = usePeticion(id);
  const guardarObservaciones = usePeticion(id);
  const qc = useQueryClient();

  const cambiadas = respuestasCambiadas(d.componentes, ediciones);
  const sinGuardar = cambiadas.length > 0 || JSON.stringify(campos) !== JSON.stringify(camposIniciales(d.ejecucion));
  // Aviso del navegador al cerrar o recargar con cambios sin guardar
  useEffect(() => {
    if (!sinGuardar) return;
    const avisar = (e: BeforeUnloadEvent) => {
      e.preventDefault();
    };
    window.addEventListener('beforeunload', avisar);
    return () => window.removeEventListener('beforeunload', avisar);
  }, [sinGuardar]);
  const guardarRespuestas = useMutation({
    mutationFn: (respuestas: typeof cambiadas) =>
      api<Mensaje & { cambiados?: string[] }>(`/api/visitas/${id}/respuestas`, { metodo: 'PUT', cuerpo: { respuestas } }),
    onSuccess: (r, enviadas) => {
      notifications.show({ color: 'green', message: r.mensaje });
      // Solo se descartan las ediciones que siguen iguales a lo enviado: lo escrito mientras se guardaba se conserva
      const enviadasPorItem = new Map(enviadas.map((x) => [x.item, x]));
      setEdiciones((ed) =>
        Object.fromEntries(
          Object.entries(ed).filter(([k, e]) => {
            const env = enviadasPorItem.get(k);
            return !env || (e.calificacion ?? null) !== (env.calificacion ?? null) || (e.hallazgo ?? '').trim() !== (env.hallazgo ?? '').trim();
          }),
        ),
      );
      qc.invalidateQueries({ queryKey: ['formulario', id] });
      qc.invalidateQueries({ queryKey: ['agenda'] });
    },
    onError: (e) => avisarError(e, 'No se pudieron guardar las calificaciones.'),
  });

  const enviarRespuestas = () => {
    if (cambiadas.length === 0) return;
    const faltan = sinHallazgo(cambiadas);
    if (faltan.length > 0) {
      notifications.show({ color: 'orange', autoClose: 10000, message: `Escriba el hallazgo de ${faltan.join(', ')}.` });
      return;
    }
    guardarRespuestas.mutate(cambiadas);
  };

  const editar = (it: ItemChecklist, cambio: Partial<Edicion>) =>
    setEdiciones((ed) => ({ ...ed, [it.codigo]: { ...valorItem(it, ed), ...cambio } }));

  const soloLectura = !p.escribeVisita;

  return (
    <Stack data-testid="formulario-visita" pb={cambiadas.length > 0 ? 'xl' : undefined}>
      <Anchor component={Link} to={volver} size="sm">
        Volver a la visita
      </Anchor>
      <Group gap="sm">
        <Title order={3}>Formulario de la visita {v.numero}</Title>
        <Badge color={colorEstado(v.estado)} size="lg" data-testid="estado-visita">
          {v.estadoNombre}
        </Badge>
        {v.ronda > 1 && (
          <Badge color="grape" size="lg" variant="light">
            Ronda {v.ronda} · reabierta
          </Badge>
        )}
      </Group>
      {!p.escribible && <Alert color="yellow">{p.motivo ?? 'El formulario está en solo lectura.'}</Alert>}
      {p.correccion && <Alert color="blue">Corrección del coordinador: los cambios quedan en el historial de la visita.</Alert>}

      <Card withBorder data-testid="datos-visita">
        <Title order={5} mb="xs">
          Datos de la visita
        </Title>
        <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="xs">
          <TextInput label="Número" readOnly value={v.numero} data-testid="campo-numero" />
          <Select
            label="Lugar"
            readOnly
            data={[{ value: 'punto', label: (v.sigla ? v.sigla + ' · ' : '') + v.lugar }]}
            value="punto"
            data-testid="campo-lugar"
          />
          <TextInput label="Municipio" readOnly value={[v.municipio, v.departamento].filter(Boolean).join(', ') || '—'} />
          <TextInput label="Unidad" readOnly value={[v.unidad, v.fuerza, v.modalidad].filter(Boolean).join(' · ')} />
          <TextInput label="Tipo" readOnly value={v.tipo} />
          <TextInput label="Auditor líder" readOnly value={v.lider ?? '—'} data-testid="campo-auditor" />
          <TextInput label="Equipo" readOnly value={v.equipo.map((m) => m.nombre).join(', ') || '—'} />
          <TextInput label="Fecha programada" readOnly value={v.fechaLarga + (v.hora ? ' · ' + horaCorta(v.hora) : '')} />
        </SimpleGrid>
        {v.direccion && (
          <Text size="xs" c="dimmed" mt="xs">
            Dirección: {v.direccion}
          </Text>
        )}
        <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="xs" mt="md">
          <TextInput
            label="Inicio real"
            type="datetime-local"
            readOnly={soloLectura}
            value={campos.inicioReal}
            onChange={(e) => setCampos({ ...campos, inicioReal: e.currentTarget.value })}
          />
          <TextInput
            label="Fin real"
            type="datetime-local"
            readOnly={soloLectura}
            value={campos.finReal}
            onChange={(e) => setCampos({ ...campos, finReal: e.currentTarget.value })}
          />
        </SimpleGrid>
        <Textarea
          label="Objeto de la visita"
          mt="xs"
          autosize
          minRows={2}
          maxLength={2000}
          readOnly={soloLectura}
          value={campos.objeto}
          onChange={(e) => setCampos({ ...campos, objeto: e.currentTarget.value })}
        />
        {!soloLectura && (
          <Group justify="flex-end" mt="sm">
            <Button
              loading={guardarDatos.isPending}
              onClick={() => guardarDatos.mutate({ ruta: `/api/visitas/${id}/ejecucion`, metodo: 'PUT', cuerpo: cuerpoEjecucion(campos) })}
            >
              Guardar datos de la visita
            </Button>
          </Group>
        )}
      </Card>

      <Personal id={id} inicial={d.personal} editable={p.escribeVisita} />

      <Stack gap="xs">
        <Title order={4}>Componentes</Title>
        {d.componentes.length === 0 && (
          <Text size="sm" c="dimmed">
            La visita no tiene componentes del checklist.
          </Text>
        )}
        <Accordion multiple variant="separated" defaultValue={d.componentes.map((c) => c.codigo)}>
          {d.componentes.map((c) => {
            const n = conteo(c.items, ediciones);
            return (
              <Accordion.Item key={c.codigo} value={c.codigo} data-testid={'componente-' + c.codigo}>
                <Accordion.Control>
                  <Group gap="xs" wrap="wrap">
                    <Text fw={600} size="sm">
                      {c.codigo} · {c.nombre}
                    </Text>
                    <Badge size="sm" variant="light" color={n.pendientes === 0 ? 'green' : 'gray'}>
                      C {n.c} · NC {n.nc} · NA {n.na} · pendientes {n.pendientes}
                    </Badge>
                  </Group>
                </Accordion.Control>
                <Accordion.Panel>
                  <Stack gap="sm">
                    {c.items.map((it) => (
                      <Item key={it.codigo} id={id} it={it} valor={valorItem(it, ediciones)} alEditar={(x) => editar(it, x)} />
                    ))}
                  </Stack>
                </Accordion.Panel>
              </Accordion.Item>
            );
          })}
        </Accordion>
        {d.componentes.some((c) => c.items.some((i) => i.editable)) && (
          <Paper
            withBorder
            shadow="sm"
            p="sm"
            style={{ position: 'sticky', bottom: 8, zIndex: 5 }}
          >
            <Group justify="space-between" gap="xs">
              <Text size="sm" c={cambiadas.length > 0 ? 'orange' : 'dimmed'}>
                {cambiadas.length === 0
                  ? 'Sin cambios por guardar.'
                  : cambiadas.length === 1
                    ? '1 calificación sin guardar.'
                    : `${cambiadas.length} calificaciones sin guardar.`}
              </Text>
              <Button
                data-testid="guardar-calificaciones"
                disabled={cambiadas.length === 0}
                loading={guardarRespuestas.isPending}
                onClick={enviarRespuestas}
              >
                Guardar calificaciones
              </Button>
            </Group>
          </Paper>
        )}
      </Stack>

      <Card withBorder data-testid="soportes-visita">
        <Title order={5} mb="xs">
          Soportes de la visita
        </Title>
        <Evidencias id={id} clave="visita" archivos={d.archivosVisita} editable={p.escribeVisita} etiqueta="Soportes de la visita" testid="visita" />
      </Card>

      <Card withBorder data-testid="observaciones-visita">
        <Title order={5} mb="xs">
          Observaciones y recomendaciones
        </Title>
        <Stack gap="xs">
          {(
            [
              ['recomendaciones', 'Recomendaciones'],
              ['observacionesAuditor', 'Observaciones del auditor'],
              ['observacionesEsm', 'Observaciones del ESM'],
              ['observacionesOpl', 'Observaciones del OPL'],
            ] as const
          ).map(([campo, etiqueta]) => (
            <Textarea
              key={campo}
              label={etiqueta}
              autosize
              minRows={2}
              maxLength={4000}
              readOnly={soloLectura}
              value={campos[campo]}
              onChange={(e) => setCampos({ ...campos, [campo]: e.currentTarget.value })}
            />
          ))}
          {!soloLectura && (
            <Group justify="flex-end">
              <Button
                loading={guardarObservaciones.isPending}
                onClick={() =>
                  guardarObservaciones.mutate({ ruta: `/api/visitas/${id}/ejecucion`, metodo: 'PUT', cuerpo: cuerpoEjecucion(campos) })
                }
              >
                Guardar observaciones
              </Button>
            </Group>
          )}
        </Stack>
      </Card>

      <Firmas id={id} d={d} />
      <Resumen d={d} />
      <Actas id={id} d={d} sinGuardar={sinGuardar} />
    </Stack>
  );
}

function Personal({ id, inicial, editable }: { id: string; inicial: PersonaAuditada[]; editable: boolean }) {
  const [personas, setPersonas] = useState<PersonaAuditada[]>(inicial);
  const guardar = usePeticion(id);
  const cambiar = (i: number, cambio: Partial<PersonaAuditada>) => setPersonas(personas.map((x, j) => (j === i ? { ...x, ...cambio } : x)));

  return (
    <Card withBorder data-testid="personal-auditado">
      <Title order={5} mb="xs">
        Personal auditado
      </Title>
      {!editable ? (
        inicial.length === 0 ? (
          <Text size="sm" c="dimmed">
            Sin personal registrado.
          </Text>
        ) : (
          <ScrollArea type="auto">
            <Table miw={560}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Entidad</Table.Th>
                  <Table.Th>Nombre</Table.Th>
                  <Table.Th>Cargo</Table.Th>
                  <Table.Th>Documento</Table.Th>
                  <Table.Th>Correo</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {inicial.map((x, i) => (
                  <Table.Tr key={i}>
                    <Table.Td>{x.entidad}</Table.Td>
                    <Table.Td>{x.nombre}</Table.Td>
                    <Table.Td>{x.cargo ?? ''}</Table.Td>
                    <Table.Td>{x.documento ?? ''}</Table.Td>
                    <Table.Td>{x.correo ?? ''}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollArea>
        )
      ) : (
        <Stack gap="sm">
          {personas.length === 0 && (
            <Text size="sm" c="dimmed">
              Agregue a las personas que atendieron la visita.
            </Text>
          )}
          {personas.map((x, i) => (
            <Paper key={i} withBorder p="xs" data-testid={'persona-' + i}>
              <SimpleGrid cols={{ base: 1, xs: 2, md: 5 }} spacing="xs">
                <Select
                  label="Entidad"
                  data={ENTIDADES}
                  allowDeselect={false}
                  value={x.entidad}
                  onChange={(e) => cambiar(i, { entidad: e ?? 'ESM' })}
                />
                <TextInput label="Nombre" required maxLength={150} value={x.nombre} onChange={(e) => cambiar(i, { nombre: e.currentTarget.value })} />
                <TextInput label="Cargo" maxLength={120} value={x.cargo ?? ''} onChange={(e) => cambiar(i, { cargo: e.currentTarget.value })} />
                <TextInput label="Documento" maxLength={30} value={x.documento ?? ''} onChange={(e) => cambiar(i, { documento: e.currentTarget.value })} />
                <TextInput label="Correo" type="email" value={x.correo ?? ''} onChange={(e) => cambiar(i, { correo: e.currentTarget.value })} />
              </SimpleGrid>
              <Group justify="flex-end" mt={6}>
                <Button size="xs" variant="subtle" color="red" onClick={() => setPersonas(personas.filter((_, j) => j !== i))}>
                  Quitar
                </Button>
              </Group>
            </Paper>
          ))}
          <Group justify="space-between">
            <Button
              variant="light"
              disabled={personas.length >= 30}
              onClick={() => setPersonas([...personas, { entidad: 'ESM', nombre: '', cargo: '', documento: '', correo: '' }])}
            >
              Agregar persona
            </Button>
            <Button
              loading={guardar.isPending}
              onClick={() =>
                guardar.mutate({
                  ruta: `/api/visitas/${id}/personal`,
                  metodo: 'PUT',
                  cuerpo: {
                    personas: personas.map((x) => ({
                      entidad: x.entidad,
                      nombre: x.nombre.trim(),
                      cargo: textoONulo(x.cargo ?? ''),
                      documento: textoONulo(x.documento ?? ''),
                      correo: textoONulo(x.correo ?? ''),
                    })),
                  },
                })
              }
            >
              Guardar personal
            </Button>
          </Group>
        </Stack>
      )}
    </Card>
  );
}

function Item({ id, it, valor, alEditar }: { id: string; it: ItemChecklist; valor: Edicion; alEditar: (e: Partial<Edicion>) => void }) {
  const opciones: Calificacion[] = ['C', 'NC', 'NA'];
  const faltaHallazgo = valor.calificacion === 'NC' && valor.hallazgo.trim().length === 0;
  return (
    <Paper withBorder p="sm" data-testid={'item-' + it.codigo}>
      <Text size="sm">
        <Text span fw={700}>
          {it.codigo} · {it.punto}
        </Text>
      </Text>
      {it.criterio && (
        <Text size="sm" mt={2} style={{ whiteSpace: 'pre-wrap' }}>
          {it.criterio}
        </Text>
      )}
      {(it.requisito || it.evidencia || it.condicion) && (
        <Box component="details" mt={4}>
          <Text component="summary" size="sm" c="blue" style={{ cursor: 'pointer' }}>
            Requisito y evidencia
          </Text>
          <Stack gap={4} mt={4}>
            {it.requisito && (
              <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
                <Text span fw={500}>
                  Requisito:{' '}
                </Text>
                {it.requisito}
              </Text>
            )}
            {it.evidencia && (
              <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
                <Text span fw={500}>
                  Evidencia:{' '}
                </Text>
                {it.evidencia}
              </Text>
            )}
            {it.condicion && (
              <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
                <Text span fw={500}>
                  Condición:{' '}
                </Text>
                {it.condicion}
              </Text>
            )}
          </Stack>
        </Box>
      )}
      <Text size="xs" c="dimmed" mt={4}>
        Responsable: {it.responsable ?? '—'}
      </Text>
      <Radio.Group
        mt="xs"
        label={<VisuallyHidden>{'Calificación de ' + it.codigo}</VisuallyHidden>}
        data-testid={'calificacion-' + it.codigo}
        value={valor.calificacion ?? ''}
        onChange={(x) => alEditar({ calificacion: (x || null) as Calificacion | null })}
        readOnly={!it.editable}
      >
        <Group gap="md">
          {opciones.map((o) => (
            <Radio
              key={o}
              value={o}
              label={ETIQUETA_CALIFICACION[o]}
              disabled={!it.editable || (o === 'NA' && !it.admiteNoAplica)}
            />
          ))}
        </Group>
      </Radio.Group>
      {!it.admiteNoAplica && (
        <Text size="xs" c="dimmed" mt={2}>
          No aplica solo con condición.
        </Text>
      )}
      {it.editable && valor.calificacion && (
        <Button size="compact-xs" variant="subtle" mt={2} onClick={() => alEditar({ calificacion: null })}>
          Quitar calificación
        </Button>
      )}
      {valor.calificacion === 'NC' && (
        <Textarea
          label="Hallazgo"
          aria-label={'Hallazgo de ' + it.codigo}
          data-testid={'hallazgo-' + it.codigo}
          required
          mt="xs"
          autosize
          minRows={2}
          maxLength={2000}
          readOnly={!it.editable}
          error={it.editable && faltaHallazgo ? 'Escriba el hallazgo.' : undefined}
          value={valor.hallazgo}
          onChange={(e) => alEditar({ hallazgo: e.currentTarget.value })}
        />
      )}
      <Box mt="xs">
        <Evidencias
          id={id}
          clave={'it:' + it.codigo}
          archivos={it.archivos}
          editable={it.editable}
          etiqueta={'Evidencias de ' + it.codigo}
          testid={it.codigo}
        />
      </Box>
    </Paper>
  );
}

/** Lista de archivos con enlace, "Quitar" y carga múltiple en una sola petición. */
function Evidencias({
  id,
  clave,
  archivos,
  editable,
  etiqueta,
  testid,
}: {
  id: string;
  clave: string;
  archivos: Archivo[];
  editable: boolean;
  etiqueta: string;
  testid: string;
}) {
  const qc = useQueryClient();
  const entrada = useRef<HTMLInputElement>(null);
  const [elegidos, setElegidos] = useState<File[]>([]);
  const quitar = usePeticion(id);
  const subir = useMutation({
    mutationFn: (files: File[]) => subirArchivos<Mensaje>(`/api/visitas/${id}/archivos`, { clave }, files),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      setElegidos([]);
      if (entrada.current) entrada.current.value = '';
      qc.invalidateQueries({ queryKey: ['formulario', id] });
    },
    onError: (e) => avisarError(e, 'No se pudieron subir los archivos.'),
  });

  return (
    <Stack gap={4} data-testid={'evidencias-' + testid}>
      {archivos.length === 0 ? (
        <Text size="xs" c="dimmed">
          Sin archivos.
        </Text>
      ) : (
        <List size="sm" spacing={2}>
          {archivos.map((a) => (
            <List.Item key={a.id}>
              <Group gap={6} wrap="wrap">
                <Anchor href={`/api/archivos/${a.id}`} target="_blank" rel="noopener" size="sm" style={{ wordBreak: 'break-all' }}>
                  {a.nombre}
                </Anchor>
                <Text size="xs" c="dimmed">
                  {tamanoLegible(a.tamano)}
                  {a.subidoPor ? ' · ' + a.subidoPor : ''}
                </Text>
                {editable && (
                  <Button
                    size="compact-xs"
                    variant="subtle"
                    color="red"
                    aria-label={'Quitar ' + a.nombre}
                    disabled={quitar.isPending}
                    onClick={() => window.confirm(`¿Quitar el archivo ${a.nombre}?`) && quitar.mutate({ ruta: `/api/archivos/${a.id}`, metodo: 'DELETE' })}
                  >
                    Quitar
                  </Button>
                )}
              </Group>
            </List.Item>
          ))}
        </List>
      )}
      {editable && (
        <Group gap="xs" wrap="wrap" align="center">
          <input
            ref={entrada}
            type="file"
            multiple
            aria-label={etiqueta}
            style={{ maxWidth: '100%', fontSize: 'var(--mantine-font-size-xs)' }}
            onChange={(e) => setElegidos(Array.from(e.currentTarget.files ?? []))}
          />
          <Button
            size="xs"
            variant="light"
            data-testid={'subir-' + testid}
            disabled={elegidos.length === 0}
            loading={subir.isPending}
            onClick={() => subir.mutate(elegidos)}
          >
            Subir
          </Button>
        </Group>
      )}
      {editable && (
        <Text size="xs" c="dimmed">
          Hasta 20 archivos por carga; 25 MB cada uno (videos 100 MB).
        </Text>
      )}
    </Stack>
  );
}

function Firmas({ id, d }: { id: string; d: DatosFormulario }) {
  const [rol, setRol] = useState<RolFirma | null>(null);
  const puedeFirmar = d.permisos.escribeVisita && !d.permisos.correccion;
  const titulo = ROLES_FIRMA.find((r) => r.rol === rol)?.titulo ?? '';
  return (
    <Card withBorder data-testid="firmas">
      <Title order={5} mb="xs">
        Firmas
      </Title>
      <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="xs">
        {ROLES_FIRMA.map((r) => {
          const f = d.firmas.find((x) => x.rol === r.rol);
          return (
            <Paper key={r.rol} withBorder p="sm" data-testid={'firma-' + r.rol}>
              <Text size="sm" fw={600}>
                {r.titulo}
              </Text>
              {f?.firmado ? (
                <>
                  <Text size="sm">{f.nombre}</Text>
                  <Text size="xs" c="dimmed">
                    {[f.cargo, f.documento].filter(Boolean).join(' · ')}
                  </Text>
                  <Badge size="sm" color="green" variant="light" mt={4}>
                    Firmado {f.firmado.replace('T', ' ').substring(0, 16)}
                  </Badge>
                </>
              ) : (
                <Text size="sm" c="dimmed">
                  Sin firma.
                </Text>
              )}
              {puedeFirmar && (
                <Button size="xs" variant="light" mt="xs" onClick={() => setRol(r.rol)}>
                  Firmar
                </Button>
              )}
            </Paper>
          );
        })}
      </SimpleGrid>
      <Modal opened={rol !== null} onClose={() => setRol(null)} title={'Firma · ' + titulo}>
        {rol && (
          <FormFirma
            id={id}
            rol={rol}
            nombreInicial={d.firmas.find((x) => x.rol === rol)?.nombre ?? (rol === 'auditor' ? d.visita.lider ?? '' : '')}
            alCerrar={() => setRol(null)}
          />
        )}
      </Modal>
    </Card>
  );
}

function FormFirma({ id, rol, nombreInicial, alCerrar }: { id: string; rol: RolFirma; nombreInicial: string; alCerrar: () => void }) {
  const [nombre, setNombre] = useState(nombreInicial);
  const [documento, setDocumento] = useState('');
  const [cargo, setCargo] = useState('');
  const [imagen, setImagen] = useState<string | null>(null);
  const guardar = usePeticion(id);
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        if (!imagen) return;
        guardar.mutate(
          { ruta: `/api/visitas/${id}/firmas`, metodo: 'POST', cuerpo: { rol, nombre: nombre.trim(), documento: textoONulo(documento), cargo: textoONulo(cargo), imagen } },
          { onSuccess: alCerrar },
        );
      }}
    >
      <Stack gap="xs">
        <TextInput label="Nombre" required minLength={3} maxLength={150} value={nombre} onChange={(e) => setNombre(e.currentTarget.value)} />
        <TextInput label="Documento" maxLength={30} value={documento} onChange={(e) => setDocumento(e.currentTarget.value)} />
        <TextInput label="Cargo" maxLength={120} value={cargo} onChange={(e) => setCargo(e.currentTarget.value)} />
        <PanelFirma alCambiar={setImagen} />
        <Group justify="flex-end">
          <Button variant="default" onClick={alCerrar}>
            Volver
          </Button>
          <Button type="submit" disabled={!imagen} loading={guardar.isPending}>
            Guardar firma
          </Button>
        </Group>
      </Stack>
    </form>
  );
}

function Cifra({ titulo, children }: { titulo: string; children: ReactNode }) {
  return (
    <div>
      <Text size="xs" c="dimmed">
        {titulo}
      </Text>
      <Text fw={600}>{children}</Text>
    </div>
  );
}

function Resumen({ d }: { d: DatosFormulario }) {
  const r = d.resumen;
  const cumplimiento = cumplimientoLegible(r.cumplimiento);
  return (
    <Card withBorder data-testid="resumen-visita">
      <Title order={5} mb="xs">
        Resumen
      </Title>
      <SimpleGrid cols={{ base: 3, sm: 6 }} spacing="xs">
        <Cifra titulo="Cumple">{r.c}</Cifra>
        <Cifra titulo="No cumple">{r.nc}</Cifra>
        <Cifra titulo="No aplica">{r.na}</Cifra>
        <Cifra titulo="Pendientes">{r.pendientes}</Cifra>
        <Cifra titulo="Hallazgos">{r.hallazgos}</Cifra>
        <Cifra titulo="Cumplimiento">{cumplimiento ?? '—'}</Cifra>
      </SimpleGrid>
      {d.faltantes.length > 0 && (
        <Alert color="orange" title="Antes de enviar el acta" mt="sm" data-testid="faltantes">
          <List size="sm" spacing={2}>
            {d.faltantes.map((f, i) => (
              <List.Item key={i}>{f}</List.Item>
            ))}
          </List>
        </Alert>
      )}
    </Card>
  );
}

function Actas({ id, d, sinGuardar }: { id: string; d: DatosFormulario; sinGuardar: boolean }) {
  const qc = useQueryClient();
  const enviar = useMutation({
    mutationFn: () => api<{ ok: boolean; mensaje: string; ronda: number }>(`/api/visitas/${id}/acta`, { metodo: 'POST', cuerpo: {} }),
    onSuccess: (r) => {
      notifications.show({ color: r.ok ? 'green' : 'orange', autoClose: r.ok ? 6000 : false, message: r.mensaje });
      qc.invalidateQueries({ queryKey: ['formulario', id] });
      qc.invalidateQueries({ queryKey: ['agenda'] });
      qc.invalidateQueries({ queryKey: ['visita'] });
    },
    onError: (e) => {
      avisarError(e, 'No se pudo enviar el acta.');
      qc.invalidateQueries({ queryKey: ['formulario', id] });
    },
  });
  // El acta se genera con lo guardado: con cambios pendientes no se envía (se perderían al cerrar la visita)
  const bloqueado = d.faltantes.length > 0 || sinGuardar;
  return (
    <Card withBorder data-testid="actas">
      <Title order={5} mb="xs">
        Acta
      </Title>
      {d.actas.length === 0 ? (
        <Text size="sm" c="dimmed">
          Aún no se ha generado el acta.
        </Text>
      ) : (
        <ScrollArea type="auto">
          <Table miw={520}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Ronda</Table.Th>
                <Table.Th>Estado</Table.Th>
                <Table.Th>Generada</Table.Th>
                <Table.Th>Destinatarios</Table.Th>
                <Table.Th>Archivo</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {d.actas.map((a) => (
                <Table.Tr key={a.ronda}>
                  <Table.Td>{a.ronda}</Table.Td>
                  <Table.Td>
                    <Badge size="sm" variant="light" color={a.error ? 'orange' : 'green'}>
                      {a.estado}
                    </Badge>
                    {a.error && (
                      <Text size="xs" c="orange">
                        {a.error}
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td style={{ whiteSpace: 'nowrap' }}>{a.generado ? a.generado.replace('T', ' ').substring(0, 16) : '—'}</Table.Td>
                  <Table.Td>{a.destinatarios ?? '—'}</Table.Td>
                  <Table.Td>
                    {a.archivoId ? (
                      <Anchor href={`/api/archivos/${a.archivoId}`} target="_blank" rel="noopener" size="sm">
                        Ver acta
                      </Anchor>
                    ) : (
                      '—'
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      )}
      {d.permisos.enviaActa && (
        <Stack gap={4} mt="sm" align="flex-start">
          <Button
            data-testid="enviar-acta"
            color="green"
            disabled={bloqueado}
            loading={enviar.isPending}
            onClick={() =>
              window.confirm('Se generará el acta, se enviará a los destinatarios y la visita quedará cerrada. ¿Continuar?') && enviar.mutate()
            }
          >
            Enviar acta y cerrar la visita
          </Button>
          <Text size="xs" c="dimmed">
            {sinGuardar
              ? 'Hay cambios sin guardar: guárdelos antes de enviar el acta.'
              : bloqueado
                ? 'Complete lo que falta (ver el resumen) para poder enviar el acta.'
                : 'El acta se genera con lo guardado.'}
          </Text>
        </Stack>
      )}
    </Card>
  );
}
