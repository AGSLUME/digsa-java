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
import { api, subirArchivos } from '../shared/api';
import { conteo, fechaHora } from '../shared/formato';
import { useValidacion } from '../shared/validacion';
import {
  botonQuitar,
  consultaPresupuesto,
  controlPagina,
  cuerpoEjecucion,
  erroresEjecucion,
  ESTADO_NOTIFICACION,
  etiquetaPunto,
  exigeFuerza,
  mesesDe,
  type FormEjecucion,
  type Notificacion,
  type OpcionesPresupuesto,
  type Pagina,
  type ResultadoCarga,
  type VersionEjecucion,
} from '../shared/presupuesto';
import { TablaAdaptable } from '../shared/TablaAdaptable';

const VACIO: FormEjecucion = { vigenciaId: '', puntoId: '', modalidad: '', fuerza: '', periodo: '', facturado: '', reconocido: '', referencia: '' };

/**
 * Ejecución presupuestal (spec «Ejecución por un puerto»): carga del archivo de la DIGSA o del OPL, registro manual y las versiones de
 * cada mes. Un registro nuevo del mismo mes reemplaza al anterior; el historial conserva las versiones.
 */
export function Ejecucion({ o }: { o: OpcionesPresupuesto }) {
  return (
    <Stack>
      {o.puedeRegistrar && (
        <CargaArchivo
          titulo="Carga de la ejecución"
          ruta="/api/presupuesto/ejecucion"
          prefijo="ejecucion"
          ayuda="CSV separado por «;» en UTF-8 o Excel (.xlsx) con vigencia;fuerza;esm;modalidad;periodo;facturado y, opcionales, reconocido;referencia. El periodo es el mes (AAAA-MM). La ejecución de un satélite se suma a su ESM centralizador. Un mes ya cargado se reemplaza por el nuevo."
        />
      )}
      {o.puedeRegistrar && <ManualEjecucion o={o} />}
      <Versiones o={o} />
    </Stack>
  );
}

/** Carga de un archivo con simular y aplicar (todo o nada): ejecución o proyección. */
export function CargaArchivo({ titulo, ruta, ayuda, prefijo }: { titulo: string; ruta: string; ayuda: string; prefijo: string }) {
  const qc = useQueryClient();
  const [archivo, setArchivo] = useState<File | null>(null);
  const [fuente, setFuente] = useState('digsa');
  const [resultado, setResultado] = useState<ResultadoCarga | null>(null);
  const errores = (resultado?.errores ?? []).map((e) => `Fila ${e.fila}: ${e.motivo}`);
  const v = useValidacion({ etiquetas: { archivo: 'Archivo' }, testid: `${prefijo}-error` });
  const simular = useMutation({
    mutationFn: () => subirArchivos<ResultadoCarga>(`${ruta}/simular`, {}, [archivo!], 'archivo'),
    onSuccess: (r) => {
      v.reiniciar();
      setResultado(r);
    },
    onError: (e) => v.errorServidor(e, 'No se pudo revisar el archivo.'),
  });
  const aplicar = useMutation({
    mutationFn: () => subirArchivos<ResultadoCarga>(`${ruta}/aplicar`, { fuente }, [archivo!], 'archivo'),
    onSuccess: (r) => {
      notifications.show({ color: r.yaAplicado ? 'yellow' : 'green', message: r.mensaje });
      v.reiniciar();
      setResultado({ ...r, errores: [] });
      void qc.invalidateQueries({ queryKey: ['presupuesto'] });
    },
    onError: (e) => v.errorServidor(e, 'No se pudo aplicar la carga.'),
  });
  const listo = resultado !== null && !resultado.yaAplicado && errores.length === 0 && (resultado.validas ?? 0) > 0;
  return (
    <Card withBorder ref={v.ref}>
      <Stack>
        <Title order={5}>{titulo}</Title>
        <Text size="sm" c="dimmed">
          {ayuda}
        </Text>
        <FileInput
          label="Archivo"
          accept=".csv,text/csv,.xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
          value={archivo}
          clearable
          clearButtonProps={botonQuitar('el archivo')}
          onChange={(a) => {
            setArchivo(a);
            setResultado(null);
            v.reiniciar();
          }}
          error={v.error('archivo')}
          data-testid={`${prefijo}-archivo`}
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
          <Button variant="light" disabled={!archivo} loading={simular.isPending} onClick={() => simular.mutate()} data-testid={`${prefijo}-simular`}>
            Simular
          </Button>
          <Button disabled={!listo} loading={aplicar.isPending} onClick={() => aplicar.mutate()} data-testid={`${prefijo}-aplicar`}>
            Aplicar
          </Button>
          {!listo && (
            <Text size="sm" c="dimmed">
              {resultado?.yaAplicado ? 'Este archivo ya se aplicó.' : 'Para aplicar, simule primero un archivo sin errores.'}
            </Text>
          )}
        </Group>
        {v.resumen}
        {resultado && (
          <Alert color={resultado.yaAplicado ? 'yellow' : errores.length ? 'red' : 'green'} data-testid={`${prefijo}-resultado`}>
            {resultado.mensaje}
          </Alert>
        )}
        {(resultado?.advertencias ?? []).length > 0 && (
          <Alert color="yellow" title="Advertencias (no impiden aplicar)" data-testid={`${prefijo}-advertencias`}>
            {resultado!.advertencias!.slice(0, 20).map((a, i) => (
              <Text key={i} size="sm">
                {a}
              </Text>
            ))}
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
  const vigencia = abiertas.find((x) => String(x.id) === f.vigenciaId);
  const v = useValidacion({
    etiquetas: {
      vigenciaId: 'Vigencia',
      puntoId: 'ESM',
      modalidad: 'Modalidad',
      fuerza: 'Fuerza de adscripción',
      periodo: 'Mes',
      facturado: 'Facturado',
      reconocido: 'Reconocido',
      referencia: 'Referencia',
    },
    testid: 'ejecucion-error',
  });
  const cambiar = (cambio: Partial<FormEjecucion>) => {
    setF({ ...f, ...cambio });
    for (const k of Object.keys(cambio)) v.limpiar(k);
  };
  const registrar = useMutation({
    mutationFn: () => api<{ mensaje: string }>('/api/presupuesto/ejecucion/manual', { cuerpo: cuerpoEjecucion(f) }),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      v.reiniciar();
      setF((p) => ({ ...p, facturado: '', reconocido: '', referencia: '' }));
      void qc.invalidateQueries({ queryKey: ['presupuesto'] });
    },
    onError: (e) => v.errorServidor(e, 'No se pudo registrar la ejecución.'),
  });
  return (
    <Card withBorder data-testid="ejecucion-manual" ref={v.ref}>
      <Stack>
        <Title order={5}>Registro manual de la ejecución del mes</Title>
        <SimpleGrid cols={{ base: 1, sm: 2, md: 4 }}>
          <Select
            label="Vigencia"
            data={abiertas.map((x) => ({ value: String(x.id), label: String(x.anio) }))}
            value={f.vigenciaId || null}
            allowDeselect={false}
            onChange={(x) => cambiar({ vigenciaId: x ?? '', periodo: '' })}
            error={v.error('vigenciaId')}
          />
          <Select
            label="ESM"
            searchable
            data={o.puntos.map((p) => ({ value: String(p.id), label: etiquetaPunto(p) }))}
            value={f.puntoId || null}
            onChange={(x) => cambiar({ puntoId: x ?? '', fuerza: '' })}
            error={v.error('puntoId')}
            data-testid="ejecucion-esm"
          />
          <Select
            label="Modalidad"
            data={o.modalidades.filter((m) => m.activa).map((m) => ({ value: m.codigo, label: m.nombre }))}
            value={f.modalidad || null}
            onChange={(x) => cambiar({ modalidad: x ?? '' })}
            error={v.error('modalidad')}
            data-testid="ejecucion-modalidad"
          />
          {exigeFuerza(punto) && (
            <Select
              label="Fuerza de adscripción"
              data={o.fuerzas.map((x) => x.codigo)}
              value={f.fuerza || null}
              onChange={(x) => cambiar({ fuerza: x ?? '' })}
              error={v.error('fuerza')}
            />
          )}
          <Select
            label="Mes"
            placeholder="AAAA-MM"
            data={vigencia ? mesesDe(vigencia.inicio, vigencia.fin) : []}
            value={f.periodo || null}
            onChange={(x) => cambiar({ periodo: x ?? '' })}
            error={v.error('periodo')}
            data-testid="ejecucion-periodo"
          />
          <TextInput
            label="Facturado"
            placeholder="260000000,00"
            inputMode="decimal"
            value={f.facturado}
            onChange={(e) => cambiar({ facturado: e.currentTarget.value })}
            error={v.error('facturado')}
            data-testid="ejecucion-facturado"
          />
          <TextInput
            label="Reconocido (si ya se concilió)"
            inputMode="decimal"
            value={f.reconocido}
            onChange={(e) => cambiar({ reconocido: e.currentTarget.value })}
            error={v.error('reconocido')}
          />
          <TextInput
            label="Referencia (opcional)"
            value={f.referencia}
            onChange={(e) => cambiar({ referencia: e.currentTarget.value })}
            error={v.error('referencia')}
          />
        </SimpleGrid>
        {punto?.modalidad === 'Satélite' && <Text size="sm">Satélite: la ejecución se suma a la línea de su ESM centralizador.</Text>}
        {v.resumen}
        <Group>
          <Button
            loading={registrar.isPending}
            onClick={() => {
              if (v.validar(erroresEjecucion(f, homil))) registrar.mutate();
            }}
            data-testid="ejecucion-registrar"
          >
            Registrar ejecución
          </Button>
          <Text size="sm" c="dimmed">
            Si el mes ya tenía ejecución, la nueva la reemplaza y la anterior queda en el historial.
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
            clearButtonProps={botonQuitar('la vigencia')}
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
            clearButtonProps={botonQuitar('el mes')}
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
              {conteo(versiones.data.total)} registro(s)
            </Text>
            <TablaAdaptable>
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
                        {x.vigente ? <Badge color="green">Vigente</Badge> : <Badge color="gray">Reemplazada {fechaHora(x.reemplazada)}</Badge>}
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </TablaAdaptable>
            {versiones.data.total > versiones.data.porPagina && (
              <Pagination
                total={Math.ceil(versiones.data.total / versiones.data.porPagina)}
                value={pagina}
                onChange={setPagina}
                size="sm"
                getControlProps={controlPagina}
              />
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
  const v = useValidacion({ testid: 'notificaciones-error' });
  const consulta = new URLSearchParams({ ...(tipo ? { tipo } : {}), pagina: String(pagina) }).toString();
  const lista = useQuery({
    queryKey: ['presupuesto', 'notificaciones', consulta],
    queryFn: () => api<Pagina<Notificacion>>('/api/presupuesto/notificaciones?' + consulta),
    placeholderData: keepPreviousData,
  });
  const reenviar = useMutation({
    mutationFn: (id: number) => api<{ mensaje: string }>(`/api/presupuesto/notificaciones/${id}/reenviar`, { metodo: 'POST' }),
    onSuccess: (r) => {
      v.reiniciar();
      notifications.show({ color: 'green', message: r.mensaje });
      void qc.invalidateQueries({ queryKey: ['presupuesto', 'notificaciones'] });
    },
    onError: (e) => v.errorServidor(e, 'No se pudo reenviar la notificación.'),
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
      {v.resumen}
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
                {fechaHora(n.fecha)}
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
        <Pagination
          total={Math.ceil(lista.data.total / lista.data.porPagina)}
          value={pagina}
          onChange={setPagina}
          size="sm"
          getControlProps={controlPagina}
        />
      )}
    </Stack>
  );
}
