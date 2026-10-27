import { Alert, Anchor, Badge, Button, Card, FileInput, Group, Loader, Modal, ScrollArea, Select, Stack, Table, Text, TextInput, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api, subirArchivos } from '../shared/api';
import type { OpcionesAgenda, ResultadoVisita } from '../shared/agenda';
import { FormularioVisita, type SolicitudParaAgendar } from '../shared/FormularioVisita';
import { EncabezadoPagina } from '../shared/EncabezadoPagina';
import { fecha as fechaPantalla, hoyBogota, sumarDias } from '../shared/formato';
import { puedeEntrar, useSesion } from '../shared/sesion';
import { useValidacion } from '../shared/validacion';
import { DIAS_ATRAS_SOLICITUD, erroresSolicitud } from '../shared/validaciones';

interface Solicitud {
  id: number;
  puntoId: number;
  punto: string;
  unidad: string;
  fuerza: string;
  entidad: string;
  fechaSolicitud: string;
  limite: string;
  vencida: boolean;
  motivo: string;
  estado: 'pendiente' | 'programada' | 'anulada';
  visitaId: number | null;
  visitaNumero: string | null;
  registrado: string | null;
  motivoAnulacion: string | null;
  tieneArchivo: boolean;
  solicitante: string | null;
}

interface DatosSolicitudes {
  solicitudes: Solicitud[];
  puedeSolicitar: boolean;
  entidad: string | null;
  puntos: { id: number; nombre: string; sigla: string | null; unidad: string; fuerza: string }[];
  coordinador: boolean;
}

const COLOR: Record<Solicitud['estado'], string> = { pendiente: 'yellow', programada: 'blue', anulada: 'gray' };
const NOMBRE: Record<Solicitud['estado'], string> = { pendiente: 'Pendiente', programada: 'Programada', anulada: 'Anulada' };

/**
 * Solicitudes de visita a necesidad (Anexo 3 p.14: 5 días hábiles desde el escrito). La DIGSA y la Sanidad las registran y ven solo las
 * suyas (decisión 9.4); el coordinador las ve todas, las agenda como visita a necesidad o las anula.
 */
export function Solicitudes() {
  const qc = useQueryClient();
  const navegar = useNavigate();
  const [estado, setEstado] = useState<string | null>('pendiente');
  const datos = useQuery({
    queryKey: ['solicitudes', estado],
    queryFn: () => api<DatosSolicitudes>(`/api/solicitudes${estado ? `?estado=${estado}` : ''}`),
  });
  const coordinador = datos.data?.coordinador ?? false;
  // Decisión UI-12 (Anexo 5 3.7): agendar y anular las autoriza el servidor con la pantalla «agenda»; sin ella no se ofrecen
  const sesion = useSesion();
  const gestiona = coordinador && puedeEntrar(sesion, 'agenda');
  const opciones = useQuery({
    queryKey: ['agenda', 'opciones'],
    queryFn: () => api<OpcionesAgenda>('/api/agenda/opciones'),
    enabled: coordinador,
  });
  const [nueva, setNueva] = useState(false);
  const [agendar, setAgendar] = useState<SolicitudParaAgendar | null>(null);
  const [anular, setAnular] = useState<Solicitud | null>(null);

  const refrescar = () => qc.invalidateQueries({ queryKey: ['solicitudes'] });

  if (datos.isLoading) return <Loader />;
  if (datos.error) return <Alert color="red">{datos.error.message}</Alert>;
  const d = datos.data;
  if (!d) return null;

  return (
    <Stack>
      <EncabezadoPagina
        titulo="Solicitudes de visita a necesidad"
        descripcion={
          <>
            La visita a necesidad se hace sin previo aviso al operador logístico, a más tardar 5 días hábiles después de la fecha del escrito
            (Anexo 3 p.14).
            {!coordinador && ' Aquí ve solo las solicitudes que usted registró.'}
          </>
        }
        acciones={d.puedeSolicitar && <Button onClick={() => setNueva(true)}>Nueva solicitud</Button>}
      />
      <Select
        label="Estado"
        w={220}
        clearable
        data={[
          { value: 'pendiente', label: 'Pendientes' },
          { value: 'programada', label: 'Programadas' },
          { value: 'anulada', label: 'Anuladas' },
        ]}
        value={estado}
        onChange={setEstado}
      />
      <ScrollArea type="auto">
        <Table striped miw={900} data-testid="tabla-solicitudes">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>N.º</Table.Th>
              <Table.Th>Punto</Table.Th>
              <Table.Th>Entidad</Table.Th>
              <Table.Th>Fecha del escrito</Table.Th>
              <Table.Th>Plazo</Table.Th>
              <Table.Th>Estado</Table.Th>
              <Table.Th>Motivo</Table.Th>
              {coordinador && <Table.Th>Solicitante</Table.Th>}
              <Table.Th>Acciones</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {d.solicitudes.length === 0 && (
              <Table.Tr>
                <Table.Td colSpan={coordinador ? 9 : 8}>No hay solicitudes con este filtro.</Table.Td>
              </Table.Tr>
            )}
            {d.solicitudes.map((s) => (
              <Table.Tr key={s.id} data-testid={'solicitud-' + s.id}>
                <Table.Td>{s.id}</Table.Td>
                <Table.Td>{s.punto}</Table.Td>
                <Table.Td>{s.entidad}</Table.Td>
                <Table.Td>{fechaPantalla(s.fechaSolicitud)}</Table.Td>
                <Table.Td>
                  <Group gap={4} wrap="nowrap">
                    {fechaPantalla(s.limite)}
                    {s.vencida && (
                      <Badge color="red" size="sm">
                        Vencida
                      </Badge>
                    )}
                  </Group>
                </Table.Td>
                <Table.Td>
                  <Badge color={COLOR[s.estado]}>{NOMBRE[s.estado]}</Badge>
                  {s.visitaNumero && (
                    <Text size="xs">
                      {coordinador && s.visitaId ? (
                        <Anchor size="xs" component={Link} to={`/agenda/visita/${s.visitaId}`}>
                          {s.visitaNumero}
                        </Anchor>
                      ) : (
                        s.visitaNumero
                      )}
                    </Text>
                  )}
                </Table.Td>
                <Table.Td maw={320}>
                  <Text size="sm" lineClamp={3}>
                    {s.motivo}
                  </Text>
                  {s.motivoAnulacion && (
                    <Text size="xs" c="dimmed">
                      Anulada: {s.motivoAnulacion}
                    </Text>
                  )}
                </Table.Td>
                {coordinador && <Table.Td>{s.solicitante}</Table.Td>}
                <Table.Td>
                  <Group gap={4} wrap="nowrap">
                    {s.tieneArchivo && (
                      <Anchor href={`/api/solicitudes/${s.id}/escrito`} target="_blank" rel="noopener noreferrer" size="sm">
                        Escrito
                      </Anchor>
                    )}
                    {gestiona && s.estado === 'pendiente' && (
                      <>
                        <Button size="xs" onClick={() => setAgendar({ id: s.id, puntoId: s.puntoId, fechaSolicitud: s.fechaSolicitud })}>
                          Agendar
                        </Button>
                        <Button size="xs" variant="light" color="red" onClick={() => setAnular(s)}>
                          Anular
                        </Button>
                      </>
                    )}
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </ScrollArea>

      <Modal opened={nueva} onClose={() => setNueva(false)} title="Nueva solicitud de visita a necesidad" size="lg">
        {nueva && (
          <NuevaSolicitud
            datos={d}
            alTerminar={() => {
              setNueva(false);
              refrescar();
            }}
          />
        )}
      </Modal>

      <FormularioVisita
        abierto={agendar !== null}
        alCerrar={() => setAgendar(null)}
        opciones={opciones.data}
        errorOpciones={opciones.error?.message}
        solicitud={agendar ?? undefined}
        alGuardar={(r: ResultadoVisita) => {
          setAgendar(null);
          refrescar();
          navegar(`/agenda/visita/${r.id}`);
        }}
      />

      <Modal opened={anular !== null} onClose={() => setAnular(null)} title={`Anular la solicitud N.º ${anular?.id ?? ''}`}>
        {anular && (
          <Anular
            solicitud={anular}
            alTerminar={() => {
              setAnular(null);
              refrescar();
            }}
          />
        )}
      </Modal>
    </Stack>
  );
}

function NuevaSolicitud({ datos, alTerminar }: { datos: DatosSolicitudes; alTerminar: () => void }) {
  const [punto, setPunto] = useState<string | null>(null);
  const [fecha, setFecha] = useState(hoyBogota());
  const [motivo, setMotivo] = useState('');
  const [escrito, setEscrito] = useState<File | null>(null);
  const v = useValidacion({
    etiquetas: { punto: 'Punto', fecha: 'Fecha del escrito', motivo: 'Motivo de la solicitud' },
    campos: { punto_id: 'punto', fecha_solicitud: 'fecha' },
    testid: 'error-solicitud',
  });
  const registrar = useMutation({
    mutationFn: () =>
      subirArchivos<{ mensaje: string }>('/api/solicitudes', { punto_id: punto ?? '', fecha_solicitud: fecha, motivo }, escrito ? [escrito] : [], 'escrito'),
    onSuccess: (r) => {
      notifications.show({ color: 'green', autoClose: 10000, message: r.mensaje });
      v.reiniciar();
      alTerminar();
    },
    onError: (e) => v.errorServidor(e, 'No se pudo registrar la solicitud.'),
  });
  const enviar = (e: FormEvent) => {
    e.preventDefault();
    if (v.validar(erroresSolicitud(punto, fecha, motivo, hoyBogota()))) registrar.mutate();
  };
  const hoy = hoyBogota();
  return (
    <form ref={v.ref} onSubmit={enviar} noValidate>
      <Stack>
        {v.resumen}
        <TextInput label="Entidad" value={datos.entidad ?? ''} readOnly />
        <Select
          label="Punto"
          required
          searchable
          data={datos.puntos.map((p) => ({ value: String(p.id), label: `${p.sigla ? p.sigla + ' · ' : ''}${p.nombre} (${p.unidad}, ${p.fuerza})` }))}
          value={punto}
          error={v.error('punto')}
          onChange={(x) => {
            setPunto(x);
            v.limpiar('punto');
          }}
        />
        <TextInput
          label="Fecha del escrito"
          type="date"
          required
          min={sumarDias(hoy, -DIAS_ATRAS_SOLICITUD)}
          max={hoy}
          description={`Máximo ${DIAS_ATRAS_SOLICITUD} días atrás.`}
          value={fecha}
          error={v.error('fecha')}
          onChange={(e) => {
            setFecha(e.currentTarget.value);
            v.limpiar('fecha');
          }}
        />
        <Textarea
          label="Motivo de la solicitud"
          required
          autosize
          minRows={3}
          maxLength={4000}
          value={motivo}
          error={v.error('motivo')}
          onChange={(e) => {
            setMotivo(e.currentTarget.value);
            v.limpiar('motivo');
          }}
        />
        <FileInput label="Escrito (PDF, opcional)" accept="application/pdf" clearable value={escrito} onChange={setEscrito} />
        <Card withBorder p="xs">
          <Text size="xs" c="dimmed">
            El coordinador recibe un correo con la solicitud. Cuando quede agendada le llega un correo; si la visita se cancela, también se le avisa y
            la solicitud vuelve a pendiente.
          </Text>
        </Card>
        <Group justify="flex-end">
          <Button type="submit" loading={registrar.isPending}>
            Registrar solicitud
          </Button>
        </Group>
      </Stack>
    </form>
  );
}

function Anular({ solicitud, alTerminar }: { solicitud: Solicitud; alTerminar: () => void }) {
  const [motivo, setMotivo] = useState('');
  const v = useValidacion({ etiquetas: { motivo: 'Motivo de la anulación' }, testid: 'error-anular' });
  const anular = useMutation({
    mutationFn: () => api<{ mensaje: string }>(`/api/solicitudes/${solicitud.id}/anular`, { cuerpo: { motivo } }),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      v.reiniciar();
      alTerminar();
    },
    onError: (e) => v.errorServidor(e, 'No se pudo anular.'),
  });
  return (
    <form
      ref={v.ref}
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        // La regla del servidor (ServicioSolicitudes.anular): motivo obligatorio, máximo 800 caracteres
        if (v.validar({ motivo: motivo.trim() ? null : 'Registre el motivo de la anulación (máximo 800 caracteres).' })) anular.mutate();
      }}
    >
      <Stack>
        {v.resumen}
        <Text size="sm">
          {solicitud.punto} · {solicitud.entidad} · escrito del {fechaPantalla(solicitud.fechaSolicitud)}
        </Text>
        <Textarea
          label="Motivo de la anulación"
          required
          maxLength={800}
          value={motivo}
          error={v.error('motivo')}
          onChange={(e) => {
            setMotivo(e.currentTarget.value);
            v.limpiar('motivo');
          }}
        />
        <Group justify="flex-end">
          <Button type="submit" color="red" loading={anular.isPending}>
            Anular solicitud
          </Button>
        </Group>
      </Stack>
    </form>
  );
}
