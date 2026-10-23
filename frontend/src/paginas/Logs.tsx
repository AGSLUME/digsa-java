import { Alert, Badge, Button, Group, Modal, ScrollArea, Select, SimpleGrid, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { DatePickerInput } from '@mantine/dates';
import { notifications } from '@mantine/notifications';
import { useMediaQuery } from '@mantine/hooks';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useState } from 'react';
import { api, descargar, ErrorApi } from '../shared/api';

interface Fila {
  id: number;
  fecha: string;
  fechaLlave: string;
  usuario: string | null;
  operacion: string;
  tipo: string;
  descripcion: string | null;
  ip: string | null;
  modulo: string | null;
  /** Valores de antes y después de una modificación (ítem 15 b); los datos personales vienen ocultos (Ley 1581). */
  cambios: Cambio[] | null;
}

interface Cambio {
  campo: string;
  antes: string | null;
  despues: string | null;
  oculto: boolean;
}

interface Pagina {
  filas: Fila[];
  siguiente: { fecha: string; id: number } | null;
}

interface Opciones {
  operaciones: string[];
  usuarios: { id: number; nombre: string; usuario: string }[];
  modulos: string[];
  correoDefecto: string;
}

const NOMBRES: Record<string, string> = {
  insercion: 'Inserción',
  actualizacion: 'Actualización',
  eliminacion: 'Eliminación',
  consulta: 'Consulta',
  ingreso: 'Ingreso',
  salida: 'Salida',
};

/** Logs del sistema (Anexo 5, prueba 15): en el navegador, con descarga en Excel o CSV y envío por correo sin salir de la aplicación. */
export function Logs() {
  const [desde, setDesde] = useState<Date | null>(dayjs().subtract(7, 'day').toDate());
  const [hasta, setHasta] = useState<Date | null>(new Date());
  const [usuarioId, setUsuarioId] = useState<string | null>(null);
  const [operacion, setOperacion] = useState<string | null>(null);
  const [texto, setTexto] = useState('');
  const [modulo, setModulo] = useState<string | null>(null);
  const [tipo, setTipo] = useState('');
  const [detalle, setDetalle] = useState<Fila | null>(null);
  const celular = useMediaQuery('(max-width: 36em)');
  // Filtros aplicados con «Buscar»: la tabla, la descarga y el correo usan siempre estos (lo que se ve es lo que se exporta)
  const [aplicado, setAplicado] = useState('');
  const [vuelta, setVuelta] = useState(0);
  const [envio, setEnvio] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);

  const opciones = useQuery({ queryKey: ['logs-opciones'], queryFn: () => api<Opciones>('/api/logs/opciones') });

  const parametros = () => {
    const p = new URLSearchParams();
    if (desde) p.set('desde', dayjs(desde).format('YYYY-MM-DD'));
    if (hasta) p.set('hasta', dayjs(hasta).format('YYYY-MM-DD'));
    if (usuarioId) p.set('usuarioId', usuarioId);
    if (operacion) p.set('operacion', operacion);
    if (texto.trim()) p.set('texto', texto.trim());
    if (modulo) p.set('modulo', modulo);
    if (tipo.trim()) p.set('tipo', tipo.trim());
    return p;
  };

  const lista = useInfiniteQuery({
    queryKey: ['logs', aplicado, vuelta],
    initialPageParam: null as Pagina['siguiente'],
    queryFn: ({ pageParam }) => {
      const p = new URLSearchParams(aplicado);
      if (pageParam) {
        p.set('despuesFecha', pageParam.fecha);
        p.set('despuesId', String(pageParam.id));
      }
      return api<Pagina>('/api/logs?' + p.toString());
    },
    getNextPageParam: (ultima) => ultima.siguiente,
  });

  const buscar = () => {
    setAplicado(parametros().toString());
    setVuelta((v) => v + 1);
  };

  const enviarCorreo = async () => {
    if (!envio || enviando) return;
    setEnviando(true);
    try {
      const p = new URLSearchParams(aplicado);
      const r = await api<{ mensaje: string }>('/api/logs/enviar', {
        cuerpo: {
          desde: p.get('desde'),
          hasta: p.get('hasta'),
          usuarioId: p.get('usuarioId') ? Number(p.get('usuarioId')) : null,
          operacion: p.get('operacion'),
          texto: p.get('texto'),
          modulo: p.get('modulo'),
          tipo: p.get('tipo'),
          formato: 'xlsx',
          para: envio,
        },
      });
      notifications.show({ color: 'green', message: r.mensaje });
      setEnvio(null);
    } catch (e) {
      notifications.show({ color: 'red', message: e instanceof ErrorApi ? e.message : 'No se pudo enviar el correo.' });
    } finally {
      setEnviando(false);
    }
  };

  const filas = lista.data?.pages.flatMap((p) => p.filas) ?? [];

  return (
    <Stack>
      <Title order={3}>Logs del sistema</Title>
      <SimpleGrid cols={{ base: 1, sm: 2, md: 4 }}>
        <DatePickerInput label="Desde" value={desde} onChange={setDesde} valueFormat="YYYY-MM-DD" />
        <DatePickerInput label="Hasta" value={hasta} onChange={setHasta} valueFormat="YYYY-MM-DD" />
        <Select
          label="Usuario"
          clearable
          searchable
          data={(opciones.data?.usuarios ?? []).map((u) => ({ value: String(u.id), label: `${u.nombre} (${u.usuario})` }))}
          value={usuarioId}
          onChange={setUsuarioId}
        />
        <Select
          label="Operación"
          clearable
          data={(opciones.data?.operaciones ?? []).map((o) => ({ value: o, label: NOMBRES[o] ?? o }))}
          value={operacion}
          onChange={setOperacion}
        />
        <Select label="Módulo" clearable searchable data={opciones.data?.modulos ?? []} value={modulo} onChange={setModulo} />
        <TextInput
          label="Tipo"
          description="Exacto, p. ej. usuario_editado"
          value={tipo}
          onChange={(e) => setTipo(e.currentTarget.value)}
          maxLength={50}
        />
        <TextInput
          label="Texto"
          description="Con texto, el periodo es de máximo 92 días"
          value={texto}
          onChange={(e) => setTexto(e.currentTarget.value)}
          maxLength={80}
        />
      </SimpleGrid>
      <Group>
        <Button onClick={buscar}>Buscar</Button>
        <Button
          variant="light"
          onClick={() =>
            descargar('/api/logs/exportar?formato=xlsx&' + aplicado).catch((e: Error) => notifications.show({ color: 'red', message: e.message }))
          }
        >
          Descargar Excel
        </Button>
        <Button
          variant="light"
          onClick={() =>
            descargar('/api/logs/exportar?formato=csv&' + aplicado).catch((e: Error) => notifications.show({ color: 'red', message: e.message }))
          }
        >
          Descargar CSV
        </Button>
        <Button variant="light" onClick={() => setEnvio(opciones.data?.correoDefecto ?? '')}>
          Enviar por correo
        </Button>
      </Group>
      {lista.error && <Alert color="red">{lista.error.message}</Alert>}
      {opciones.error && <Alert color="red">No se pudieron cargar los filtros: {opciones.error.message}</Alert>}
      <ScrollArea type="auto">
        <Table striped miw={860} data-testid="tabla-logs">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Fecha y hora</Table.Th>
              <Table.Th>Usuario</Table.Th>
              <Table.Th>Operación</Table.Th>
              <Table.Th>Tipo</Table.Th>
              <Table.Th>Detalle</Table.Th>
              <Table.Th>IP</Table.Th>
              <Table.Th>Cambios</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {filas.map((f) => (
              <Table.Tr key={f.id}>
                <Table.Td style={{ whiteSpace: 'nowrap' }}>{f.fecha}</Table.Td>
                <Table.Td>{f.usuario ?? '—'}</Table.Td>
                <Table.Td>
                  <Badge variant="light">{NOMBRES[f.operacion] ?? f.operacion}</Badge>
                </Table.Td>
                <Table.Td>{f.tipo}</Table.Td>
                <Table.Td>{f.descripcion}</Table.Td>
                <Table.Td>{f.ip}</Table.Td>
                <Table.Td>
                  {f.cambios && f.cambios.length > 0 && (
                    <Button size="xs" variant="light" aria-label={'Ver los cambios del registro ' + f.id} onClick={() => setDetalle(f)}>
                      Ver ({f.cambios.length})
                    </Button>
                  )}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </ScrollArea>
      {filas.length === 0 && !lista.isLoading && <Text c="dimmed">No hay registros con estos filtros.</Text>}
      {lista.hasNextPage && (
        <Button variant="default" onClick={() => lista.fetchNextPage()} loading={lista.isFetchingNextPage}>
          Ver más
        </Button>
      )}
      <Modal opened={detalle !== null} onClose={() => setDetalle(null)} title="Antes y después" size="lg" fullScreen={celular}>
        {detalle && (
          <Stack>
            <Text size="sm">
              {detalle.fecha} · {detalle.usuario ?? '—'} · {detalle.tipo}
            </Text>
            <ScrollArea type="auto">
              <Table striped miw={480} data-testid="tabla-cambios">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>Campo</Table.Th>
                    <Table.Th>Antes</Table.Th>
                    <Table.Th>Después</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {(detalle.cambios ?? []).map((c, i) => (
                    <Table.Tr key={i + '-' + c.campo}>
                      <Table.Td>{c.campo}</Table.Td>
                      {c.oculto ? (
                        <Table.Td colSpan={2}>
                          <Text size="sm" c="dimmed">
                            Modificado (dato personal: no se guarda el valor, Ley 1581)
                          </Text>
                        </Table.Td>
                      ) : (
                        <>
                          <Table.Td style={{ wordBreak: 'break-word' }}>{c.antes ?? '—'}</Table.Td>
                          <Table.Td style={{ wordBreak: 'break-word' }}>{c.despues ?? '—'}</Table.Td>
                        </>
                      )}
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </ScrollArea>
          </Stack>
        )}
      </Modal>
      <Modal opened={envio !== null} onClose={() => setEnvio(null)} title="Enviar logs por correo">
        <Stack>
          <TextInput label="Correo de destino" type="email" value={envio ?? ''} onChange={(e) => setEnvio(e.currentTarget.value)} />
          <Text size="xs" c="dimmed">
            Se envía el reporte en Excel con los filtros actuales.
          </Text>
          <Group justify="flex-end">
            <Button onClick={enviarCorreo} loading={enviando}>
              Enviar
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}
