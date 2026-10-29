import { Alert, Badge, Button, Group, Modal, MultiSelect, SimpleGrid, Stack, Table, Tabs, Text, Textarea, TextInput } from '@mantine/core';
import { DatePickerInput } from '@mantine/dates';
import { useMediaQuery } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useState, type FormEvent } from 'react';
import { api } from '../shared/api';
import { BotonConMotivo } from '../shared/BotonConMotivo';
import { descargarReporte } from '../shared/descargaReporte';
import { EncabezadoPagina } from '../shared/EncabezadoPagina';
import { conteo, fechaHora, hoyBogota, sumarDias } from '../shared/formato';
import { TablaAdaptable } from '../shared/TablaAdaptable';
import { useValidacion } from '../shared/validacion';
import { PlantillasCorreo } from './CorreosPlantillas';
import { errorCorreo } from '../shared/validaciones';

interface Envio {
  id: number;
  creado: string;
  creadoLlave: string;
  origen: string;
  tipo: string;
  referencia: string | null;
  /** Hasta tres direcciones enmascaradas (Ley 1581) y «y N más». */
  destinatarios: string;
  totalDestinatarios: number;
  asunto: string;
  estado: Estado;
  intentos: number;
  error: string | null;
  enviado: string | null;
  directo: boolean;
  enmascarado: boolean;
  reintentoDe: number | null;
  motivoReintento: string | null;
  reintentoId: number | null;
  /** Por qué no se puede reintentar (null: se puede). */
  noReintentable: string | null;
}

interface DetalleEnvio extends Envio {
  para: string[];
  copia: string[];
  html: string;
  adjuntos: { nombre: string; tipo: string; tamano: number | null }[];
}

type Estado = 'pendiente' | 'enviando' | 'enviado' | 'parcial' | 'fallido';

interface Pagina {
  filas: Envio[];
  siguiente: { fecha: string; id: number } | null;
}

interface Opciones {
  tipos: { valor: string; nombre: string }[];
  estados: Estado[];
}

/** Igual que ControladorCorreos: el motivo del reintento va de 10 a 300 caracteres. */
const MOTIVO_MIN = 10;
const MOTIVO_MAX = 300;
/** Reporte del motor con la bitácora (ProveedorReporteCorreos.ID): la descarga lleva huella y código de verificación. */
const REPORTE = 'correos_envios';

const ESTADOS: Record<Estado, { nombre: string; color: string }> = {
  pendiente: { nombre: 'Pendiente', color: 'gray' },
  enviando: { nombre: 'Enviando', color: 'blue' },
  enviado: { nombre: 'Enviado', color: 'green' },
  parcial: { nombre: 'Parcial', color: 'yellow' },
  fallido: { nombre: 'Fallido', color: 'red' },
};

/** Módulo Correos (Administración, solo el Administrador): bitácora de envíos; plantillas y destinatarios en las fases C2 y C3. */
export function Correos() {
  return (
    <Stack>
      <EncabezadoPagina
        titulo="Correos"
        descripcion="Cada correo que envía la aplicación: estado, vista previa, reintento de los fallidos, descarga y los textos de las plantillas."
      />
      <Tabs defaultValue="envios" keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="envios">Envíos</Tabs.Tab>
          <Tabs.Tab value="plantillas">Plantillas</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="envios" pt="md">
          <BitacoraEnvios />
        </Tabs.Panel>
        <Tabs.Panel value="plantillas" pt="md">
          <PlantillasCorreo />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}

function BitacoraEnvios() {
  const [desde, setDesde] = useState<Date | null>(() => dayjs(sumarDias(hoyBogota(), -7)).toDate());
  const [hasta, setHasta] = useState<Date | null>(() => dayjs(hoyBogota()).toDate());
  const [tipos, setTipos] = useState<string[]>([]);
  const [estados, setEstados] = useState<string[]>([]);
  const [destinatario, setDestinatario] = useState('');
  // Filtros aplicados con «Buscar»: la tabla y la descarga usan siempre estos (lo que se ve es lo que se descarga)
  const [aplicado, setAplicado] = useState('');
  const [vuelta, setVuelta] = useState(0);
  const [verId, setVerId] = useState<number | null>(null);
  const [descargando, setDescargando] = useState<string | null>(null);
  const vFiltros = useValidacion({ etiquetas: { destinatario: 'Destinatario' }, testid: 'error-filtros-correos' });
  const vDescarga = useValidacion({ testid: 'error-descarga-correos' });

  const opciones = useQuery({ queryKey: ['correos-opciones'], queryFn: () => api<Opciones>('/api/correos/opciones') });

  const parametros = () => {
    const p = new URLSearchParams();
    if (desde) p.set('desde', dayjs(desde).format('YYYY-MM-DD'));
    if (hasta) p.set('hasta', dayjs(hasta).format('YYYY-MM-DD'));
    tipos.forEach((t) => p.append('tipo', t));
    estados.forEach((e) => p.append('estado', e));
    if (destinatario.trim()) p.set('destinatario', destinatario.trim());
    return p;
  };

  const lista = useInfiniteQuery({
    queryKey: ['correos-envios', aplicado, vuelta],
    initialPageParam: null as Pagina['siguiente'],
    queryFn: ({ pageParam }) => {
      const p = new URLSearchParams(aplicado);
      if (pageParam) {
        p.set('despuesFecha', pageParam.fecha);
        p.set('despuesId', String(pageParam.id));
      }
      return api<Pagina>('/api/correos/envios?' + p.toString());
    },
    getNextPageParam: (ultima) => ultima.siguiente,
  });

  const buscar = () => {
    const d = destinatario.trim();
    const ok = vFiltros.validar({
      destinatario: errorCorreo(d, 'Escriba el correo completo del destinatario (la búsqueda es exacta).'),
    });
    if (!ok) return;
    setAplicado(parametros().toString());
    setVuelta((v) => v + 1);
  };

  const bajar = async (formato: 'xlsx' | 'csv') => {
    if (descargando) return;
    setDescargando(formato);
    try {
      const res = await descargarReporte(`/api/reportes/${REPORTE}/exportar?formato=${formato}&${aplicado}`);
      vDescarga.reiniciar();
      if (res.diferido) notifications.show({ color: 'blue', message: res.mensaje });
    } catch (e) {
      vDescarga.errorServidor(e, 'No se pudo descargar el archivo.');
    } finally {
      setDescargando(null);
    }
  };

  const filas = lista.data?.pages.flatMap((p) => p.filas) ?? [];

  return (
    <Stack>
      <SimpleGrid ref={vFiltros.ref} cols={{ base: 1, sm: 2, md: 5 }}>
        <DatePickerInput label="Desde" value={desde} onChange={setDesde} valueFormat="DD/MM/YYYY" />
        <DatePickerInput label="Hasta" value={hasta} onChange={setHasta} valueFormat="DD/MM/YYYY" />
        <MultiSelect
          label="Tipo de correo"
          searchable
          clearable
          data={(opciones.data?.tipos ?? []).map((t) => ({ value: t.valor, label: t.nombre }))}
          value={tipos}
          onChange={setTipos}
        />
        <MultiSelect
          label="Estado"
          clearable
          data={(opciones.data?.estados ?? []).map((e) => ({ value: e, label: ESTADOS[e]?.nombre ?? e }))}
          value={estados}
          onChange={setEstados}
        />
        <TextInput
          label="Destinatario"
          description="Correo completo; la búsqueda es exacta"
          type="email"
          value={destinatario}
          error={vFiltros.error('destinatario')}
          onChange={(e) => {
            setDestinatario(e.currentTarget.value);
            vFiltros.limpiar('destinatario');
          }}
          maxLength={150}
        />
      </SimpleGrid>
      <Group>
        <Button onClick={buscar}>Buscar</Button>
        <Button variant="light" loading={descargando === 'xlsx'} onClick={() => void bajar('xlsx')}>
          Descargar Excel
        </Button>
        <Button variant="light" loading={descargando === 'csv'} onClick={() => void bajar('csv')}>
          Descargar CSV
        </Button>
      </Group>
      {vFiltros.resumen}
      {vDescarga.resumen}
      {lista.error && <Alert color="red">{lista.error.message}</Alert>}
      {opciones.error && <Alert color="red">No se pudieron cargar los filtros: {opciones.error.message}</Alert>}
      <TablaAdaptable>
        <Table striped miw={980} data-testid="tabla-correos">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Fecha y hora</Table.Th>
              <Table.Th>Tipo</Table.Th>
              <Table.Th>Referencia</Table.Th>
              <Table.Th>Destinatarios</Table.Th>
              <Table.Th>Asunto</Table.Th>
              <Table.Th>Estado</Table.Th>
              <Table.Th>Intentos</Table.Th>
              <Table.Th>Último error</Table.Th>
              <Table.Th>Correo</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {filas.map((f) => (
              <Table.Tr key={f.id} data-testid={'envio-' + f.id}>
                <Table.Td style={{ whiteSpace: 'nowrap' }}>{fechaHora(f.creado, { segundos: true })}</Table.Td>
                <Table.Td>
                  <Stack gap={4}>
                    <Text size="sm">{f.tipo}</Text>
                    {f.directo && (
                      <Badge variant="light" color="gray" size="sm">
                        Directo
                      </Badge>
                    )}
                  </Stack>
                </Table.Td>
                <Table.Td>{f.referencia ?? '—'}</Table.Td>
                <Table.Td style={{ wordBreak: 'break-word' }}>{f.destinatarios}</Table.Td>
                <Table.Td style={{ wordBreak: 'break-word' }}>{f.asunto}</Table.Td>
                <Table.Td>
                  <Badge variant="light" color={ESTADOS[f.estado]?.color ?? 'gray'}>
                    {ESTADOS[f.estado]?.nombre ?? f.estado}
                  </Badge>
                </Table.Td>
                <Table.Td>{conteo(f.intentos)}</Table.Td>
                <Table.Td style={{ wordBreak: 'break-word' }}>{f.error ?? '—'}</Table.Td>
                <Table.Td>
                  <Button size="xs" variant="light" aria-label={'Ver el envío ' + f.id} onClick={() => setVerId(f.id)}>
                    Ver
                  </Button>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </TablaAdaptable>
      {filas.length === 0 && !lista.isLoading && <Text c="dimmed">No hay envíos con estos filtros.</Text>}
      {lista.hasNextPage && (
        <Button variant="default" onClick={() => lista.fetchNextPage()} loading={lista.isFetchingNextPage}>
          Ver más
        </Button>
      )}
      <VistaPrevia id={verId} alCerrar={() => setVerId(null)} />
    </Stack>
  );
}

/** Vista previa de un envío: el correo como se guardó (los códigos y enlaces de un solo uso, ocultos) y el reintento si aplica. */
function VistaPrevia({ id, alCerrar }: { id: number | null; alCerrar: () => void }) {
  const celular = useMediaQuery('(max-width: 36em)');
  const clienteConsultas = useQueryClient();
  const [motivo, setMotivo] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);
  const vMotivo = useValidacion({ etiquetas: { motivo: 'Motivo del reintento' }, testid: 'error-reintento-correo' });
  const detalle = useQuery({
    queryKey: ['correos-envio', id],
    queryFn: () => api<DetalleEnvio>('/api/correos/envios/' + id),
    enabled: id !== null,
  });
  const d = detalle.data;

  const cerrar = () => {
    setMotivo(null);
    vMotivo.reiniciar();
    alCerrar();
  };

  const reintentar = async (ev: FormEvent) => {
    ev.preventDefault();
    if (d === undefined || motivo === null || enviando) return;
    const m = motivo.trim();
    const largo = [...m].length;
    if (
      !vMotivo.validar({
        motivo:
          largo < MOTIVO_MIN || largo > MOTIVO_MAX ? `Escriba el motivo del reintento (entre ${MOTIVO_MIN} y ${MOTIVO_MAX} caracteres).` : null,
      })
    )
      return;
    setEnviando(true);
    try {
      const r = await api<{ id: number; mensaje: string }>(`/api/correos/envios/${d.id}/reintento`, { cuerpo: { motivo: m } });
      notifications.show({ color: 'green', message: r.mensaje });
      void clienteConsultas.invalidateQueries({ queryKey: ['correos-envios'] });
      cerrar();
    } catch (e) {
      vMotivo.errorServidor(e, 'No se pudo reintentar el envío.');
    } finally {
      setEnviando(false);
    }
  };

  return (
    <Modal opened={id !== null} onClose={cerrar} title={d ? `Envío N.º ${d.id} · ${d.tipo}` : 'Envío'} size="xl" fullScreen={celular}>
      {detalle.error && <Alert color="red">{detalle.error.message}</Alert>}
      {d && (
        <Stack>
          <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="xs">
            <Text size="sm">
              <strong>Creado:</strong> {fechaHora(d.creado, { segundos: true })}
            </Text>
            <Text size="sm">
              <strong>Enviado:</strong> {d.enviado ? fechaHora(d.enviado, { segundos: true }) : '—'}
            </Text>
            <Text size="sm">
              <strong>Estado:</strong> {ESTADOS[d.estado]?.nombre ?? d.estado} · {conteo(d.intentos)} intento(s)
            </Text>
            <Text size="sm">
              <strong>Referencia:</strong> {d.referencia ?? '—'}
            </Text>
          </SimpleGrid>
          <Text size="sm" style={{ wordBreak: 'break-word' }} data-testid="para-completo">
            <strong>Para:</strong> {d.para.join(', ')}
            {d.copia.length > 0 && (
              <>
                {' · '}
                <strong>Copia:</strong> {d.copia.join(', ')}
              </>
            )}
          </Text>
          <Text size="sm" style={{ wordBreak: 'break-word' }}>
            <strong>Asunto:</strong> {d.asunto}
          </Text>
          {d.adjuntos.length > 0 && (
            <Text size="sm">
              <strong>Adjuntos:</strong> {d.adjuntos.map((a) => a.nombre).join(', ')}
            </Text>
          )}
          {d.enmascarado && (
            <Alert color="blue" data-testid="aviso-enmascarado">
              El código o el enlace de un solo uso no se guarda: en la vista previa aparece oculto.
            </Alert>
          )}
          {d.reintentoDe !== null && (
            <Text size="sm" c="dimmed">
              Reintento del envío N.º {d.reintentoDe}. Motivo: {d.motivoReintento}
            </Text>
          )}
          {d.error && <Alert color="red">Último error: {d.error}</Alert>}
          <iframe
            title="Vista previa del correo"
            sandbox=""
            srcDoc={d.html}
            style={{ width: '100%', minHeight: 360, border: '1px solid var(--lume-border)', borderRadius: 4, background: 'var(--lume-white)' }}
          />
          {motivo === null ? (
            <Group justify="flex-end" align="center">
              {d.noReintentable && (
                <Text size="sm" c="dimmed" id={'motivo-reintento-' + d.id}>
                  {d.noReintentable}
                </Text>
              )}
              <BotonConMotivo
                motivo={d.noReintentable}
                idMotivo={'motivo-reintento-' + d.id}
                onClick={() => {
                  vMotivo.reiniciar();
                  setMotivo('');
                }}
              >
                Reintentar envío
              </BotonConMotivo>
            </Group>
          ) : (
            <form ref={vMotivo.ref} onSubmit={reintentar} noValidate>
              <Stack>
                {vMotivo.resumen}
                <Textarea
                  label="Motivo del reintento"
                  description={`Entre ${MOTIVO_MIN} y ${MOTIVO_MAX} caracteres; queda en los logs.`}
                  value={motivo}
                  error={vMotivo.error('motivo')}
                  onChange={(e) => {
                    setMotivo(e.currentTarget.value);
                    vMotivo.limpiar('motivo');
                  }}
                  maxLength={MOTIVO_MAX}
                  autosize
                  minRows={2}
                />
                <Group justify="flex-end">
                  <Button variant="default" onClick={() => setMotivo(null)}>
                    Cancelar
                  </Button>
                  <Button type="submit" loading={enviando}>
                    Reintentar
                  </Button>
                </Group>
              </Stack>
            </form>
          )}
        </Stack>
      )}
    </Modal>
  );
}
