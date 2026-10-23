import {
  Alert,
  Badge,
  Button,
  Card,
  FileInput,
  Group,
  Loader,
  Pagination,
  ScrollArea,
  SegmentedControl,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { api, ErrorApi, subirArchivos } from '../shared/api';
import {
  consultaPresupuesto,
  cuerpoEjecucion,
  ESTADO_NOTIFICACION,
  etiquetaPunto,
  exigeFuerza,
  mesesDe,
  problemaEjecucion,
  type FormEjecucion,
  type Notificacion,
  type OpcionesPresupuesto,
  type Pagina,
  type ResultadoCarga,
  type VersionEjecucion,
} from '../shared/presupuesto';

function avisarError(e: unknown, defecto: string) {
  notifications.show({ color: 'red', autoClose: 10000, message: e instanceof ErrorApi ? e.message : defecto });
}

const VACIO: FormEjecucion = { vigenciaId: '', puntoId: '', modalidad: '', fuerza: '', periodo: '', facturado: '', reconocido: '', referencia: '' };

/**
 * Ejecución presupuestal (spec «Ejecución por un puerto»): carga del archivo de la DIGSA o del OPL, registro manual y las versiones de
 * cada mes. Un registro nuevo del mismo mes reemplaza al anterior; el historial conserva las versiones.
 */
export function Ejecucion({ o }: { o: OpcionesPresupuesto }) {
  return (
    <Stack>
      {o.puedeRegistrar && <CargaEjecucion />}
      {o.puedeRegistrar && <ManualEjecucion o={o} />}
      <Versiones o={o} />
    </Stack>
  );
}

function CargaEjecucion() {
  const qc = useQueryClient();
  const [archivo, setArchivo] = useState<File | null>(null);
  const [fuente, setFuente] = useState('digsa');
  const [resultado, setResultado] = useState<ResultadoCarga | null>(null);
  const errores = (resultado?.errores ?? []).map((e) => `Fila ${e.fila}: ${e.motivo}`);
  const simular = useMutation({
    mutationFn: () => subirArchivos<ResultadoCarga>('/api/presupuesto/ejecucion/simular', {}, [archivo!], 'archivo'),
    onSuccess: setResultado,
    onError: (e) => avisarError(e, 'No se pudo revisar el archivo.'),
  });
  const aplicar = useMutation({
    mutationFn: () => subirArchivos<ResultadoCarga>('/api/presupuesto/ejecucion/aplicar', { fuente }, [archivo!], 'archivo'),
    onSuccess: (r) => {
      notifications.show({ color: r.yaAplicado ? 'yellow' : 'green', message: r.mensaje });
      setResultado({ ...r, errores: [] });
      void qc.invalidateQueries({ queryKey: ['presupuesto'] });
    },
    onError: (e) => avisarError(e, 'No se pudo aplicar la ejecución.'),
  });
  const listo = resultado !== null && !resultado.yaAplicado && errores.length === 0 && (resultado.validas ?? 0) > 0;
  return (
    <Card withBorder>
      <Stack>
        <Title order={5}>Carga de la ejecución</Title>
        <Text size="sm" c="dimmed">
          CSV separado por «;» en UTF-8 o Excel (.xlsx) con vigencia;fuerza;esm;modalidad;periodo;facturado y, opcionales, reconocido;referencia. El
          periodo es el mes (AAAA-MM). La ejecución de un satélite se suma a su ESM centralizador. Un mes ya cargado se reemplaza por el nuevo.
        </Text>
        <FileInput
          label="Archivo"
          accept=".csv,text/csv,.xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
          value={archivo}
          clearable
          onChange={(a) => {
            setArchivo(a);
            setResultado(null);
          }}
        />
        <Group wrap="wrap">
          <SegmentedControl
            data={[
              { value: 'digsa', label: 'DIGSA' },
              { value: 'opl', label: 'OPL' },
            ]}
            value={fuente}
            onChange={setFuente}
          />
          <Button variant="light" disabled={!archivo} loading={simular.isPending} onClick={() => simular.mutate()} data-testid="ejecucion-simular">
            Simular
          </Button>
          <Button disabled={!listo} loading={aplicar.isPending} onClick={() => aplicar.mutate()} data-testid="ejecucion-aplicar">
            Aplicar
          </Button>
        </Group>
        {resultado && (
          <Alert color={resultado.yaAplicado ? 'yellow' : errores.length ? 'red' : 'green'} data-testid="ejecucion-resultado">
            {resultado.mensaje}
          </Alert>
        )}
        {errores.length > 0 && (
          <ScrollArea.Autosize mah={240} type="auto">
            {errores.map((e, i) => (
              <Text key={i} size="sm" c="red">
                {e}
              </Text>
            ))}
          </ScrollArea.Autosize>
        )}
      </Stack>
    </Card>
  );
}

function ManualEjecucion({ o }: { o: OpcionesPresupuesto }) {
  const qc = useQueryClient();
  const abiertas = o.vigencias.filter((v) => v.estado === 'abierta');
  const [f, setF] = useState<FormEjecucion>({ ...VACIO, vigenciaId: abiertas.length ? String(abiertas.at(-1)!.id) : '' });
  const homil = useMemo(() => new Set(o.puntos.filter((p) => exigeFuerza(p)).map((p) => String(p.id))), [o.puntos]);
  const punto = o.puntos.find((p) => String(p.id) === f.puntoId);
  const problema = problemaEjecucion(f, homil);
  const vigencia = abiertas.find((v) => String(v.id) === f.vigenciaId);
  const registrar = useMutation({
    mutationFn: () => api<{ mensaje: string }>('/api/presupuesto/ejecucion/manual', { cuerpo: cuerpoEjecucion(f) }),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      setF((p) => ({ ...p, facturado: '', reconocido: '', referencia: '' }));
      void qc.invalidateQueries({ queryKey: ['presupuesto'] });
    },
    onError: (e) => avisarError(e, 'No se pudo registrar la ejecución.'),
  });
  return (
    <Card withBorder data-testid="ejecucion-manual">
      <Stack>
        <Title order={5}>Registro manual de la ejecución del mes</Title>
        <SimpleGrid cols={{ base: 1, sm: 2, md: 4 }}>
          <Select
            label="Vigencia"
            data={abiertas.map((v) => ({ value: String(v.id), label: String(v.anio) }))}
            value={f.vigenciaId || null}
            allowDeselect={false}
            onChange={(v) => setF({ ...f, vigenciaId: v ?? '', periodo: '' })}
          />
          <Select
            label="ESM"
            searchable
            data={o.puntos.map((p) => ({ value: String(p.id), label: etiquetaPunto(p) }))}
            value={f.puntoId || null}
            onChange={(v) => setF({ ...f, puntoId: v ?? '', fuerza: '' })}
            data-testid="ejecucion-esm"
          />
          <Select
            label="Modalidad"
            data={o.modalidades.filter((m) => m.activa).map((m) => ({ value: m.codigo, label: m.nombre }))}
            value={f.modalidad || null}
            onChange={(v) => setF({ ...f, modalidad: v ?? '' })}
            data-testid="ejecucion-modalidad"
          />
          {exigeFuerza(punto) && (
            <Select
              label="Fuerza de adscripción"
              data={o.fuerzas.map((x) => x.codigo)}
              value={f.fuerza || null}
              onChange={(v) => setF({ ...f, fuerza: v ?? '' })}
            />
          )}
          <Select
            label="Mes"
            placeholder="AAAA-MM"
            data={vigencia ? mesesDe(vigencia.inicio, vigencia.fin) : []}
            value={f.periodo || null}
            onChange={(v) => setF({ ...f, periodo: v ?? '' })}
            data-testid="ejecucion-periodo"
          />
          <TextInput
            label="Facturado"
            inputMode="decimal"
            value={f.facturado}
            onChange={(e) => setF({ ...f, facturado: e.currentTarget.value })}
            data-testid="ejecucion-facturado"
          />
          <TextInput
            label="Reconocido (si ya se concilió)"
            inputMode="decimal"
            value={f.reconocido}
            onChange={(e) => setF({ ...f, reconocido: e.currentTarget.value })}
          />
          <TextInput label="Referencia (opcional)" value={f.referencia} onChange={(e) => setF({ ...f, referencia: e.currentTarget.value })} />
        </SimpleGrid>
        {punto?.modalidad === 'Satélite' && <Text size="sm">Satélite: la ejecución se suma a la línea de su ESM centralizador.</Text>}
        <Group>
          <Button disabled={problema !== null} loading={registrar.isPending} onClick={() => registrar.mutate()} data-testid="ejecucion-registrar">
            Registrar ejecución
          </Button>
          <Text size="sm" c="dimmed">
            {problema ?? 'Si el mes ya tenía ejecución, la nueva la reemplaza y la anterior queda en el historial.'}
          </Text>
        </Group>
      </Stack>
    </Card>
  );
}

function Versiones({ o }: { o: OpcionesPresupuesto }) {
  const [vigencia, setVigencia] = useState('');
  const [periodo, setPeriodo] = useState('');
  const [pagina, setPagina] = useState(1);
  const vig = o.vigencias.find((v) => String(v.id) === vigencia);
  const meses = vig ? mesesDe(vig.inicio, vig.fin) : [];
  const consulta = consultaPresupuesto({ vigencia }, { ...(periodo ? { periodo } : {}), pagina });
  const versiones = useQuery({
    queryKey: ['presupuesto', 'ejecucion', consulta],
    queryFn: () => api<Pagina<VersionEjecucion>>('/api/presupuesto/ejecucion?' + consulta),
    placeholderData: keepPreviousData,
  });
  return (
    <Card withBorder>
      <Stack>
        <Title order={5}>Ejecución registrada, mes a mes</Title>
        <Group wrap="wrap" align="flex-end">
          <Select
            label="Vigencia"
            placeholder="Todas"
            clearable
            data={o.vigencias.map((v) => ({ value: String(v.id), label: String(v.anio) }))}
            value={vigencia || null}
            onChange={(v) => {
              setPagina(1);
              setVigencia(v ?? '');
              setPeriodo('');
            }}
          />
          <Select
            label="Mes"
            placeholder={vigencia ? 'Todos' : 'Elija la vigencia'}
            clearable
            disabled={!vigencia}
            data={meses}
            value={periodo || null}
            onChange={(v) => {
              setPagina(1);
              setPeriodo(v ?? '');
            }}
          />
        </Group>
        {versiones.error && <Alert color="red">{versiones.error.message}</Alert>}
        {versiones.isLoading && <Loader />}
        {versiones.data && (
          <>
            <Text size="sm" c="dimmed" data-testid="ejecucion-total">
              {versiones.data.total} registro(s)
            </Text>
            <ScrollArea type="auto">
              <Table striped withTableBorder miw={860} fz="sm" data-testid="tabla-ejecucion">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>Mes</Table.Th>
                    <Table.Th>ESM</Table.Th>
                    <Table.Th>Modalidad</Table.Th>
                    <Table.Th ta="right">Facturado</Table.Th>
                    <Table.Th ta="right">Reconocido</Table.Th>
                    <Table.Th>Fuente</Table.Th>
                    <Table.Th>Estado</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {versiones.data.items.map((x) => (
                    <Table.Tr key={x.id} style={{ opacity: x.vigente ? 1 : 0.6 }}>
                      <Table.Td>{x.periodo}</Table.Td>
                      <Table.Td>
                        {x.esm} ({x.fuerza}){x.satelite ? ` · de ${x.satelite}` : ''}
                      </Table.Td>
                      <Table.Td>{x.modalidad}</Table.Td>
                      <Table.Td ta="right" ff="monospace">
                        {x.facturado}
                      </Table.Td>
                      <Table.Td ta="right" ff="monospace">
                        {x.reconocido ?? 'sin conciliar'}
                      </Table.Td>
                      <Table.Td>
                        {x.fuente}
                        {x.lote ? ` · lote ${x.lote}` : ''} · {x.usuario}
                      </Table.Td>
                      <Table.Td>
                        {x.vigente ? <Badge color="green">Vigente</Badge> : <Badge color="gray">Reemplazada {x.reemplazada}</Badge>}
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </ScrollArea>
            {versiones.data.total > versiones.data.porPagina && (
              <Pagination total={Math.ceil(versiones.data.total / versiones.data.porPagina)} value={pagina} onChange={setPagina} size="sm" />
            )}
          </>
        )}
      </Stack>
    </Card>
  );
}

/** Notificaciones formales de umbrales y desviaciones (Anexo 2 L741), con su consecutivo y su estado; la coordinación reenvía las incompletas. */
export function Notificaciones({ o }: { o: OpcionesPresupuesto }) {
  const qc = useQueryClient();
  const [tipo, setTipo] = useState('');
  const [pagina, setPagina] = useState(1);
  const consulta = new URLSearchParams({ ...(tipo ? { tipo } : {}), pagina: String(pagina) }).toString();
  const lista = useQuery({
    queryKey: ['presupuesto', 'notificaciones', consulta],
    queryFn: () => api<Pagina<Notificacion>>('/api/presupuesto/notificaciones?' + consulta),
    placeholderData: keepPreviousData,
  });
  const reenviar = useMutation({
    mutationFn: (id: number) => api<{ mensaje: string }>(`/api/presupuesto/notificaciones/${id}/reenviar`, { metodo: 'POST' }),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      void qc.invalidateQueries({ queryKey: ['presupuesto', 'notificaciones'] });
    },
    onError: (e) => avisarError(e, 'No se pudo reenviar la notificación.'),
  });
  return (
    <Stack>
      <Text size="sm" c="dimmed">
        Notificación formal y electrónica a la DIGSA y a la Dirección de Sanidad o Jefatura de Salud de la fuerza cuando un ESM alcanza el 25, 50, 75
        o 100 % de su presupuesto, y cuando la ejecución se aparta de la proyección. Va por correo, con consecutivo.
      </Text>
      <SegmentedControl
        w="fit-content"
        data={[
          { value: '', label: 'Todas' },
          { value: 'umbral', label: 'Umbrales' },
          { value: 'desviacion', label: 'Desviaciones' },
        ]}
        value={tipo}
        onChange={(v) => {
          setPagina(1);
          setTipo(v);
        }}
      />
      {lista.error && <Alert color="red">{lista.error.message}</Alert>}
      {lista.isLoading && <Loader />}
      {lista.data && lista.data.items.length === 0 && <Text c="dimmed">No hay notificaciones.</Text>}
      {lista.data?.items.map((n) => (
        <Card key={n.id} withBorder data-testid={`notificacion-${n.consecutivo}`}>
          <Group justify="space-between" wrap="wrap" gap="xs">
            <Group gap="xs">
              <Badge>{n.consecutivo}</Badge>
              <Badge variant="light">{n.tipo === 'umbral' ? 'Umbral' : 'Desviación'}</Badge>
              <Text size="xs" c="dimmed">
                {n.fecha}
              </Text>
            </Group>
            <Badge color={ESTADO_NOTIFICACION[n.estado]?.color ?? 'gray'} variant="light">
              {ESTADO_NOTIFICACION[n.estado]?.texto ?? n.estado}
            </Badge>
          </Group>
          <Text size="sm" mt={4}>
            {n.asunto}
          </Text>
          {o.puedeRegistrar && n.estado !== 'encolada' && (
            <Button
              size="xs"
              variant="light"
              mt="xs"
              loading={reenviar.isPending && reenviar.variables === n.id}
              onClick={() => reenviar.mutate(n.id)}
            >
              Reenviar
            </Button>
          )}
        </Card>
      ))}
      {lista.data && lista.data.total > lista.data.porPagina && (
        <Pagination total={Math.ceil(lista.data.total / lista.data.porPagina)} value={pagina} onChange={setPagina} size="sm" />
      )}
    </Stack>
  );
}
