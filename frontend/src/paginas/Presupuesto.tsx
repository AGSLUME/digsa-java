import {
  ActionIcon,
  Alert,
  Badge,
  Box,
  Button,
  Card,
  Center,
  FileInput,
  Group,
  Loader,
  Pagination,
  ScrollArea,
  SegmentedControl,
  Select,
  SimpleGrid,
  Stack,
  Switch,
  Table,
  Tabs,
  Text,
  Textarea,
  TextInput,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { sonarError } from '../shared/alertaSonora';
import { api, descargar, ErrorApi, subirArchivos } from '../shared/api';
import { conteo, fecha, fechaHora } from '../shared/formato';
import { problemaRango } from '../shared/tablero';
import { useValidacion, type Validacion } from '../shared/validacion';
import { CargaArchivo, Ejecucion, Notificaciones } from './PresupuestoEjecucion';
import { ReportesPresupuesto } from './PresupuestoReportes';
import { TableroPresupuesto } from './PresupuestoTablero';
import {
  AYUDA_TIPO,
  botonQuitar,
  campoFila,
  colorSemaforo,
  consultaPresupuesto,
  controlPagina,
  cuerpoNovedad,
  erroresNovedad,
  etiquetaPunto,
  exigeFuerza,
  FILA_VACIA,
  FILTROS_PRESUPUESTO,
  formularioVacio,
  problemaNovedad,
  type FilaNovedad,
  type FiltrosPresupuesto,
  type FormNovedad,
  type LineaPresupuesto,
  type OpcionesPresupuesto,
  type PaginaMovimientos,
  type ResultadoCarga,
  type TipoNovedad,
} from '../shared/presupuesto';
import { EncabezadoPagina } from '../shared/EncabezadoPagina';

/** Errores por fila o por línea que devuelve el servidor (422 con «errores»). */
function erroresDe(e: unknown): string[] {
  if (!(e instanceof ErrorApi) || !Array.isArray(e.datos.errores)) return [];
  return (e.datos.errores as unknown[]).map((x) =>
    typeof x === 'string'
      ? x
      : typeof x === 'object' && x !== null
        ? `Fila ${(x as { fila: number }).fila}: ${(x as { motivo: string }).motivo}`
        : '',
  );
}

/**
 * «Presupuesto del contrato de medicamentos» (Anexo 5 §4.6; docs/especificaciones/presupuesto): líneas por Fuerza, ESM y modalidad,
 * novedades presupuestales, historial con el antes y el después, cargas y configuración. Cada perfil ve su alcance; registran la
 * coordinación y el administrador.
 */
export function Presupuesto() {
  const opciones = useQuery({ queryKey: ['presupuesto', 'opciones'], queryFn: () => api<OpcionesPresupuesto>('/api/presupuesto/opciones') });
  const [pestana, setPestana] = useState<string | null>('tablero');
  const [filtrosHistorial, setFiltrosHistorial] = useState<FiltrosPresupuesto>(FILTROS_PRESUPUESTO);

  if (opciones.isLoading) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    );
  }
  if (opciones.error || !opciones.data) return <Alert color="red">{opciones.error?.message ?? 'No se pudo cargar el presupuesto.'}</Alert>;
  const o = opciones.data;

  return (
    <Stack>
      <EncabezadoPagina
        titulo="Presupuesto del contrato de medicamentos"
        fuente="Anexo 5 · 4.6"
        descripcion={
          <>
            Seguimiento a la ejecución por ESM, Fuerza y modalidad frente al presupuesto asignado. Toda modificación queda como un movimiento con su
            soporte de la DIGSA, el usuario, la fecha y el asignado de antes y de después. Alcance: {o.ambito}.
          </>
        }
      />
      <Tabs value={pestana} onChange={setPestana} keepMounted={false}>
        <ScrollArea type="auto" offsetScrollbars>
          <Tabs.List style={{ flexWrap: 'nowrap' }}>
            <Tabs.Tab value="tablero">Tablero</Tabs.Tab>
            <Tabs.Tab value="lineas">Líneas</Tabs.Tab>
            {o.puedeRegistrar && <Tabs.Tab value="novedad">Novedad presupuestal</Tabs.Tab>}
            <Tabs.Tab value="ejecucion">Ejecución</Tabs.Tab>
            <Tabs.Tab value="historial">Historial</Tabs.Tab>
            <Tabs.Tab value="notificaciones">Notificaciones</Tabs.Tab>
            <Tabs.Tab value="reportes">Reportes</Tabs.Tab>
            {o.puedeRegistrar && <Tabs.Tab value="cargas">Cargas</Tabs.Tab>}
            {o.puedeRegistrar && <Tabs.Tab value="configuracion">Configuración</Tabs.Tab>}
          </Tabs.List>
        </ScrollArea>
        <Tabs.Panel value="tablero" pt="md">
          <TableroPresupuesto o={o} />
        </Tabs.Panel>
        <Tabs.Panel value="lineas" pt="md">
          <Lineas
            o={o}
            alVerHistorial={(f) => {
              setFiltrosHistorial({ ...FILTROS_PRESUPUESTO, ...f });
              setPestana('historial');
            }}
          />
        </Tabs.Panel>
        {o.puedeRegistrar && (
          <Tabs.Panel value="novedad" pt="md">
            <Novedad o={o} />
          </Tabs.Panel>
        )}
        <Tabs.Panel value="ejecucion" pt="md">
          <Ejecucion o={o} />
        </Tabs.Panel>
        <Tabs.Panel value="reportes" pt="md">
          <ReportesPresupuesto o={o} />
        </Tabs.Panel>
        <Tabs.Panel value="notificaciones" pt="md">
          <Notificaciones o={o} />
        </Tabs.Panel>
        <Tabs.Panel value="historial" pt="md">
          <Historial o={o} filtros={filtrosHistorial} setFiltros={setFiltrosHistorial} />
        </Tabs.Panel>
        {o.puedeRegistrar && (
          <Tabs.Panel value="cargas" pt="md">
            <Stack>
              <Cargas />
              <CargaArchivo
                titulo="Proyección de consumo mensual"
                ruta="/api/presupuesto/proyeccion"
                prefijo="proyeccion"
                ayuda="CSV separado por «;» en UTF-8 o Excel (.xlsx) con vigencia;fuerza;esm;modalidad;periodo;valor. Una línea con proyección cargada usa la cargada (los meses sin valor cuentan 0); las demás, la lineal: el asignado repartido en los meses de la vigencia. La alarma de desviación compara la ejecución acumulada con lo proyectado al corte."
              />
            </Stack>
          </Tabs.Panel>
        )}
        {o.puedeRegistrar && (
          <Tabs.Panel value="configuracion" pt="md">
            <Configuracion o={o} />
          </Tabs.Panel>
        )}
      </Tabs>
    </Stack>
  );
}

// ---------- Filtros comunes ----------

function FiltrosLinea({
  o,
  f,
  set,
  conTipo,
}: {
  o: OpcionesPresupuesto;
  f: FiltrosPresupuesto;
  set: (f: FiltrosPresupuesto) => void;
  conTipo?: boolean;
}) {
  const puntos = useMemo(
    () =>
      o.puntos.filter((p) => !f.fuerza || p.fuerza === f.fuerza || p.fuerza === 'NA').map((p) => ({ value: String(p.id), label: etiquetaPunto(p) })),
    [o.puntos, f.fuerza],
  );
  return (
    <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }}>
      <Select
        label="Vigencia"
        placeholder="Todas (contrato)"
        clearable
        clearButtonProps={botonQuitar('la vigencia')}
        data={o.vigencias.map((v) => ({ value: String(v.id), label: `${v.anio}${v.estado === 'cerrada' ? ' (cerrada)' : ''}` }))}
        value={f.vigencia || null}
        onChange={(v) => set({ ...f, vigencia: v ?? '' })}
        data-testid="filtro-vigencia"
      />
      <Select
        label="Fuerza"
        placeholder="Todas"
        clearable
        clearButtonProps={botonQuitar('la fuerza')}
        data={o.fuerzas.map((x) => ({ value: x.codigo, label: x.nombre }))}
        value={f.fuerza || null}
        onChange={(v) => set({ ...f, fuerza: v ?? '', punto: '' })}
      />
      <Select
        label="ESM"
        placeholder="Todos"
        clearable
        clearButtonProps={botonQuitar('el ESM')}
        searchable
        data={puntos}
        value={f.punto || null}
        onChange={(v) => set({ ...f, punto: v ?? '' })}
        data-testid="filtro-esm"
      />
      <Select
        label="Modalidad"
        placeholder="Todas"
        clearable
        clearButtonProps={botonQuitar('la modalidad')}
        data={o.modalidades.map((m) => ({ value: m.codigo, label: m.nombre }))}
        value={f.modalidad || null}
        onChange={(v) => set({ ...f, modalidad: v ?? '' })}
      />
      {conTipo && (
        <>
          <Select
            label="Tipo de movimiento"
            placeholder="Todos"
            clearable
            clearButtonProps={botonQuitar('el tipo de movimiento')}
            data={o.tipos.map((t) => ({ value: t.codigo, label: t.nombre }))}
            value={f.tipo || null}
            onChange={(v) => set({ ...f, tipo: v ?? '' })}
            data-testid="filtro-tipo"
          />
          <TextInput label="Desde" type="date" value={f.desde} onChange={(e) => set({ ...f, desde: e.currentTarget.value })} />
          <TextInput
            label="Hasta"
            type="date"
            value={f.hasta}
            onChange={(e) => set({ ...f, hasta: e.currentTarget.value })}
            error={problemaRango(f.desde, f.hasta) ?? undefined}
          />
        </>
      )}
    </SimpleGrid>
  );
}

// ---------- Líneas ----------

function Lineas({ o, alVerHistorial }: { o: OpcionesPresupuesto; alVerHistorial: (f: Partial<FiltrosPresupuesto>) => void }) {
  const abierta = o.vigencias.filter((v) => v.estado === 'abierta').at(-1);
  const [f, setF] = useState<FiltrosPresupuesto>({ ...FILTROS_PRESUPUESTO, vigencia: abierta ? String(abierta.id) : '' });
  const consulta = consultaPresupuesto({ vigencia: f.vigencia, fuerza: f.fuerza, punto: f.punto, modalidad: f.modalidad });
  const lineas = useQuery({
    queryKey: ['presupuesto', 'lineas', consulta],
    queryFn: () => api<LineaPresupuesto[]>('/api/presupuesto/lineas' + (consulta ? '?' + consulta : '')),
    placeholderData: keepPreviousData,
  });
  return (
    <Stack>
      <Card withBorder>
        <FiltrosLinea o={o} f={f} set={setF} />
      </Card>
      {lineas.error && <Alert color="red">{lineas.error.message}</Alert>}
      {lineas.isLoading && <Loader />}
      {lineas.data && lineas.data.length === 0 && <Text c="dimmed">No hay líneas con presupuesto para estos filtros.</Text>}
      {lineas.data && lineas.data.length > 0 && (
        <ScrollArea type="auto" style={{ opacity: lineas.isPlaceholderData ? 0.6 : 1 }}>
          <Table striped withTableBorder miw={900} data-testid="tabla-lineas">
            <Table.Thead>
              <Table.Tr>
                {!f.vigencia && <Table.Th>Vigencia</Table.Th>}
                <Table.Th>ESM</Table.Th>
                <Table.Th>Modalidad</Table.Th>
                <Table.Th>Fuerza</Table.Th>
                <Table.Th ta="right">Asignado</Table.Th>
                <Table.Th ta="right">Facturado</Table.Th>
                <Table.Th ta="right">Reconocido</Table.Th>
                <Table.Th ta="right">Saldo</Table.Th>
                <Table.Th ta="right">% ejecución</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {lineas.data.map((l) => (
                <Table.Tr key={l.id} data-testid={`linea-${l.id}`}>
                  {!f.vigencia && <Table.Td>{l.vigencia}</Table.Td>}
                  <Table.Td>{etiquetaPunto(l)}</Table.Td>
                  <Table.Td>{l.modalidadNombre}</Table.Td>
                  <Table.Td>{l.fuerza}</Table.Td>
                  <Table.Td ta="right" ff="monospace" data-testid="asignado">
                    {l.asignado}
                  </Table.Td>
                  <Table.Td ta="right" ff="monospace">
                    {l.facturado}
                  </Table.Td>
                  <Table.Td ta="right" ff="monospace">
                    {l.reconocido}
                  </Table.Td>
                  <Table.Td ta="right" ff="monospace" c={l.saldo.startsWith('-') ? 'red' : undefined}>
                    {l.saldo}
                  </Table.Td>
                  <Table.Td ta="right">
                    <Badge color={colorSemaforo(l.semaforo)} variant="light">
                      {l.porcentaje === null ? 'sin asignado' : `${l.porcentaje} %`}
                    </Badge>
                  </Table.Td>
                  <Table.Td>
                    <Button
                      size="compact-xs"
                      variant="subtle"
                      onClick={() => alVerHistorial({ vigencia: f.vigencia, punto: String(l.puntoId), modalidad: l.modalidad })}
                    >
                      Historial
                    </Button>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      )}
    </Stack>
  );
}

// ---------- Novedad presupuestal ----------

function Novedad({ o }: { o: OpcionesPresupuesto }) {
  const qc = useQueryClient();
  const abiertas = o.vigencias.filter((v) => v.estado === 'abierta');
  const [f, setF] = useState<FormNovedad>(formularioVacio('rebalanceo', abiertas.length ? String(abiertas.at(-1)!.id) : ''));
  const [archivo, setArchivo] = useState<File | null>(null);
  const [errores, setErrores] = useState<string[]>([]);
  const erroresRef = useRef<HTMLDivElement>(null);
  // El foco va a la lista de motivos cuando ya está en pantalla (después del render que la muestra)
  const [focoErrores, setFocoErrores] = useState(0);
  const idAyuda = useId();
  useEffect(() => {
    if (focoErrores > 0) erroresRef.current?.focus();
  }, [focoErrores]);
  const v = useValidacion({
    etiquetas: { vigenciaId: 'Vigencia', soporte: 'Soporte de la DIGSA', archivo: 'Archivo del soporte', observacion: 'Observación', porcentaje: 'Porcentaje' },
    testid: 'novedad-error',
  });
  const modalidades = o.modalidades.filter((m) => m.activa).map((m) => ({ value: m.codigo, label: m.nombre }));
  const puntos = o.puntos.filter((p) => p.modalidad !== 'Satélite').map((p) => ({ value: String(p.id), label: etiquetaPunto(p) }));
  const homil = useMemo(() => new Set(o.puntos.filter((p) => exigeFuerza(p)).map((p) => String(p.id))), [o.puntos]);
  const problema = problemaNovedad(f, homil);
  // Mismas reglas, campo por campo. El mensaje va junto al campo cuando el campo tiene algo escrito o elegido (un monto con letras, un
  // soporte corto, una línea repetida); lo que falta por diligenciar lo dice el texto junto al botón, que sigue deshabilitado mientras
  // haya un problema (lo comprueba presupuesto.spec.ts). Cada mensaje sale una sola vez.
  const revision = erroresNovedad(f, homil);
  const valorDe = (campo: string): string => {
    const m = /^filas\.(\d+)\.(\w+)$/.exec(campo);
    if (m) return f.filas[Number(m[1])]?.[m[2] as keyof FilaNovedad] ?? '';
    return campo === 'filas' ? '' : String(f[campo as keyof FormNovedad] ?? '');
  };
  const aLaVista = (campo: string) => (revision[campo] && valorDe(campo).trim() ? revision[campo] : undefined);
  const error = (campo: string) => aLaVista(campo) ?? v.error(campo);
  const marcados = Object.keys(revision).filter((c) => aLaVista(c)).length;
  const pendiente = Object.entries(revision).find(([c]) => !aLaVista(c))?.[1];
  const ayuda =
    problema === null ? null : (pendiente ?? (marcados === 1 ? 'Revise el campo marcado.' : `Revise los ${marcados} campos marcados.`));
  const cambiar = (cambio: Partial<FormNovedad>) => {
    setF({ ...f, ...cambio });
    for (const k of Object.keys(cambio)) v.limpiar(k);
  };

  const enviar = useMutation({
    mutationFn: () =>
      subirArchivos<{ id: number; mensaje: string }>(
        '/api/presupuesto/novedades',
        { datos: JSON.stringify(cuerpoNovedad(f)) },
        archivo ? [archivo] : [],
        'archivo',
      ),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      setErrores([]);
      v.reiniciar();
      setF(formularioVacio(f.tipo, f.vigenciaId));
      setArchivo(null);
      void qc.invalidateQueries({ queryKey: ['presupuesto'] });
    },
    onError: (e) => {
      // Los motivos por línea del 422 quedan en su aviso persistente (y el mensaje del servidor es su misma lista: no se repite)
      const lista = erroresDe(e);
      setErrores(lista);
      if (lista.length > 0) {
        v.reiniciar();
        sonarError();
        setFocoErrores((n) => n + 1);
      } else {
        v.errorServidor(e, 'No se pudo registrar la novedad.');
      }
    },
  });

  const cambiarTipo = (t: TipoNovedad) => setF({ ...formularioVacio(t, f.vigenciaId), soporte: f.soporte, observacion: f.observacion });
  const fila = (i: number, cambio: Partial<FilaNovedad>) => setF({ ...f, filas: f.filas.map((x, j) => (j === i ? { ...x, ...cambio } : x)) });
  const etiquetaFila = (i: number) => (f.tipo === 'rebalanceo' ? (i === 0 ? 'Origen' : 'Destino') : `Línea ${i + 1}`);
  const conSigno = f.tipo === 'reajuste' || f.tipo === 'recomposicion';

  return (
    <Card withBorder ref={v.ref}>
      <Stack>
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <Select
            label="Vigencia"
            data={abiertas.map((x) => ({ value: String(x.id), label: String(x.anio) }))}
            value={f.vigenciaId || null}
            onChange={(x) => cambiar({ vigenciaId: x ?? '' })}
            allowDeselect={false}
            required
            error={error('vigenciaId')}
            data-testid="novedad-vigencia"
          />
          <Select
            label="Tipo"
            data={o.tipos.map((t) => ({ value: t.codigo, label: t.nombre }))}
            value={f.tipo}
            onChange={(v) => v && cambiarTipo(v as TipoNovedad)}
            allowDeselect={false}
            data-testid="novedad-tipo"
          />
        </SimpleGrid>
        <Text size="sm" c="dimmed">
          {AYUDA_TIPO[f.tipo]}
        </Text>
        {abiertas.length === 0 && <Alert color="yellow">No hay vigencias abiertas: créela en Configuración.</Alert>}
        {f.tipo === 'reajuste' && (
          <Switch
            label="Reajuste por porcentaje sobre una fuerza o todo el país"
            checked={f.porPorcentaje}
            onChange={(e) => setF({ ...f, porPorcentaje: e.currentTarget.checked })}
          />
        )}
        {f.tipo === 'reajuste' && f.porPorcentaje ? (
          <SimpleGrid cols={{ base: 1, sm: 3 }}>
            <TextInput
              label="Porcentaje"
              placeholder="5,00 o -3,50"
              inputMode="decimal"
              value={f.porcentaje}
              onChange={(e) => cambiar({ porcentaje: e.currentTarget.value })}
              error={error('porcentaje')}
            />
            <Select
              label="Fuerza"
              placeholder="Todo el país"
              clearable
              clearButtonProps={botonQuitar('la fuerza')}
              data={o.fuerzas.map((x) => ({ value: x.codigo, label: x.nombre }))}
              value={f.ambitoFuerza || null}
              onChange={(v) => setF({ ...f, ambitoFuerza: v ?? '' })}
            />
            <Select
              label="Modalidad"
              placeholder="Todas"
              clearable
              clearButtonProps={botonQuitar('la modalidad')}
              data={modalidades}
              value={f.ambitoModalidad || null}
              onChange={(v) => setF({ ...f, ambitoModalidad: v ?? '' })}
            />
          </SimpleGrid>
        ) : (
          f.filas.map((x, i) => {
            const punto = o.puntos.find((p) => String(p.id) === x.puntoId);
            return (
              <Card key={i} withBorder padding="sm" data-testid={`novedad-fila-${i}`}>
                <Group justify="space-between" mb={4}>
                  <Text fw={600} size="sm">
                    {etiquetaFila(i)}
                  </Text>
                  {f.tipo !== 'rebalanceo' && f.filas.length > 1 && (
                    <ActionIcon
                      variant="subtle"
                      color="red"
                      aria-label={`Quitar ${etiquetaFila(i)}`}
                      onClick={() => setF({ ...f, filas: f.filas.filter((_, j) => j !== i) })}
                    >
                      ×
                    </ActionIcon>
                  )}
                </Group>
                <SimpleGrid cols={{ base: 1, sm: exigeFuerza(punto) ? 4 : 3 }}>
                  <Select
                    label="ESM"
                    searchable
                    data={puntos}
                    value={x.puntoId || null}
                    onChange={(v) => fila(i, { puntoId: v ?? '', fuerza: '' })}
                    error={error(campoFila(i, 'puntoId'))}
                    data-testid="novedad-esm"
                  />
                  <Select
                    label="Modalidad"
                    data={modalidades}
                    value={x.modalidad || null}
                    onChange={(v) => fila(i, { modalidad: v ?? '' })}
                    error={error(campoFila(i, 'modalidad'))}
                    data-testid="novedad-modalidad"
                  />
                  {exigeFuerza(punto) && (
                    <Select
                      label="Fuerza de adscripción"
                      data={o.fuerzas.map((y) => ({ value: y.codigo, label: y.codigo }))}
                      value={x.fuerza || null}
                      onChange={(v) => fila(i, { fuerza: v ?? '' })}
                      error={error(campoFila(i, 'fuerza'))}
                    />
                  )}
                  <TextInput
                    label={conSigno ? 'Valor (con signo)' : 'Valor'}
                    placeholder={conSigno ? '-200000000,00' : '450000000,00'}
                    inputMode="decimal"
                    value={x.valor}
                    onChange={(e) => fila(i, { valor: e.currentTarget.value })}
                    error={error(campoFila(i, 'valor'))}
                    data-testid="novedad-valor"
                  />
                </SimpleGrid>
              </Card>
            );
          })
        )}
        {f.tipo !== 'rebalanceo' && !(f.tipo === 'reajuste' && f.porPorcentaje) && (
          <Button variant="light" size="xs" w="fit-content" onClick={() => setF({ ...f, filas: [...f.filas, { ...FILA_VACIA }] })}>
            Agregar línea
          </Button>
        )}
        <TextInput
          label="Soporte de la DIGSA"
          placeholder="Oficio [número] del [fecha]"
          required
          value={f.soporte}
          onChange={(e) => cambiar({ soporte: e.currentTarget.value })}
          error={error('soporte')}
          data-testid="novedad-soporte"
        />
        <FileInput
          label="Archivo del soporte (PDF o imagen, opcional)"
          accept="application/pdf,image/jpeg,image/png"
          clearable
          clearButtonProps={botonQuitar('el archivo del soporte')}
          value={archivo}
          onChange={(a) => {
            setArchivo(a);
            v.limpiar('archivo');
          }}
          error={v.error('archivo')}
        />
        <Textarea
          label="Observación (opcional)"
          rows={2}
          resize="vertical"
          value={f.observacion}
          onChange={(e) => cambiar({ observacion: e.currentTarget.value })}
          error={error('observacion')}
        />
        {errores.length > 0 && (
          <Alert ref={erroresRef} tabIndex={-1} color="red" title="La novedad no se registró" data-testid="novedad-errores">
            {errores.map((e) => (
              <div key={e}>{e}</div>
            ))}
          </Alert>
        )}
        {v.resumen}
        <Group>
          <Button
            onClick={() => {
              setErrores([]);
              v.reiniciar();
              enviar.mutate();
            }}
            disabled={problema !== null}
            aria-describedby={idAyuda}
            loading={enviar.isPending}
            data-testid="novedad-registrar"
          >
            Registrar novedad
          </Button>
          <Text size="sm" c={problema ? 'dimmed' : 'green'} id={idAyuda}>
            {ayuda ?? 'Queda reflejada en el historial y en los reportes.'}
          </Text>
        </Group>
      </Stack>
    </Card>
  );
}

// ---------- Historial ----------

function Historial({ o, filtros, setFiltros }: { o: OpcionesPresupuesto; filtros: FiltrosPresupuesto; setFiltros: (f: FiltrosPresupuesto) => void }) {
  const [pagina, setPagina] = useState(1);
  const v = useValidacion({ testid: 'historial-error' });
  const consulta = consultaPresupuesto(filtros, { pagina });
  const movs = useQuery({
    queryKey: ['presupuesto', 'movimientos', consulta],
    queryFn: () => api<PaginaMovimientos>('/api/presupuesto/movimientos?' + consulta),
    placeholderData: keepPreviousData,
  });
  const bajarSoporte = async (id: number) => {
    v.reiniciar();
    try {
      await descargar(`/api/presupuesto/soportes/${id}`);
    } catch (e) {
      v.errorServidor(e, 'No se pudo descargar el soporte.');
    }
  };
  return (
    <Stack>
      <Card withBorder>
        <FiltrosLinea
          o={o}
          f={filtros}
          conTipo
          set={(f) => {
            setPagina(1);
            setFiltros(f);
          }}
        />
      </Card>
      {v.resumen}
      {movs.error && <Alert color="red">{movs.error.message}</Alert>}
      {movs.isLoading && <Loader />}
      {movs.data && (
        <Text size="sm" c="dimmed" data-testid="historial-total">
          {conteo(movs.data.total)} movimiento(s)
        </Text>
      )}
      {movs.data?.items.map((m) => (
        <Card key={m.id} withBorder data-testid={`movimiento-${m.id}`} style={{ opacity: movs.isPlaceholderData ? 0.6 : 1 }}>
          <Group justify="space-between" wrap="wrap" gap="xs">
            <Group gap="xs">
              <Badge>{m.tipoNombre}</Badge>
              <Text size="sm">Vigencia {m.vigencia}</Text>
              {m.porcentaje && <Text size="sm">({m.porcentaje} %)</Text>}
            </Group>
            <Text size="xs" c="dimmed">
              {fechaHora(m.fecha)} · {m.usuario} · movimiento {m.id}
              {m.loteId ? ` · lote ${m.loteId}` : ''}
            </Text>
          </Group>
          <Text size="sm" mt={4}>
            Soporte: {m.soporte}{' '}
            {m.soporteId && (
              <Button size="compact-xs" variant="subtle" onClick={() => void bajarSoporte(m.soporteId!)}>
                Descargar archivo
              </Button>
            )}
          </Text>
          {m.observacion && (
            <Text size="sm" c="dimmed">
              {m.observacion}
            </Text>
          )}
          <ScrollArea type="auto" mt="xs">
            <Table withTableBorder miw={640} fz="sm">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>ESM</Table.Th>
                  <Table.Th>Modalidad</Table.Th>
                  <Table.Th ta="right">Valor</Table.Th>
                  <Table.Th ta="right">Asignado antes</Table.Th>
                  <Table.Th ta="right">Asignado después</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {m.lineas.map((l) => (
                  <Table.Tr key={l.lineaId}>
                    <Table.Td>
                      {l.esm} ({l.fuerza})
                    </Table.Td>
                    <Table.Td>{l.modalidad}</Table.Td>
                    <Table.Td ta="right" ff="monospace">
                      {l.valor}
                    </Table.Td>
                    <Table.Td ta="right" ff="monospace">
                      {l.antes}
                    </Table.Td>
                    <Table.Td ta="right" ff="monospace">
                      {l.despues}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollArea>
          <Text size="xs" c="dimmed" mt={4}>
            Neto: {m.neto}
          </Text>
        </Card>
      ))}
      {movs.data && movs.data.total > movs.data.porPagina && (
        <Pagination
          total={Math.ceil(movs.data.total / movs.data.porPagina)}
          value={pagina}
          onChange={setPagina}
          size="sm"
          getControlProps={controlPagina}
        />
      )}
    </Stack>
  );
}

// ---------- Cargas ----------

function Cargas() {
  const qc = useQueryClient();
  const [archivo, setArchivo] = useState<File | null>(null);
  const [soporte, setSoporte] = useState('');
  const [fuente, setFuente] = useState('digsa');
  const [resultado, setResultado] = useState<ResultadoCarga | null>(null);
  const [errores, setErrores] = useState<string[]>([]);
  const v = useValidacion({ etiquetas: { archivo: 'Archivo', soporte: 'Soporte de la DIGSA' }, testid: 'carga-error' });
  const idEspera = useId();

  const simular = useMutation({
    mutationFn: () => subirArchivos<ResultadoCarga>('/api/presupuesto/asignacion/simular', {}, [archivo!], 'archivo'),
    onSuccess: (r) => {
      v.reiniciar();
      setResultado(r);
      setErrores((r.errores ?? []).map((e) => `Fila ${e.fila}: ${e.motivo}`));
    },
    onError: (e) => v.errorServidor(e, 'No se pudo revisar el archivo.'),
  });
  const aplicar = useMutation({
    mutationFn: () => subirArchivos<ResultadoCarga>('/api/presupuesto/asignacion/aplicar', { soporte, fuente }, [archivo!], 'archivo'),
    onSuccess: (r) => {
      notifications.show({ color: r.yaAplicado ? 'yellow' : 'green', message: r.mensaje });
      v.reiniciar();
      setResultado(r);
      setErrores([]);
      void qc.invalidateQueries({ queryKey: ['presupuesto'] });
    },
    onError: (e) => {
      setErrores(erroresDe(e));
      v.errorServidor(e, 'No se pudo aplicar la carga.');
    },
  });
  const listo = resultado !== null && !resultado.yaAplicado && (resultado.errores ?? []).length === 0 && (resultado.validas ?? 0) > 0;

  return (
    <Card withBorder ref={v.ref}>
      <Stack>
        <Title order={5}>Asignación inicial por archivo</Title>
        <Text size="sm" c="dimmed">
          CSV separado por «;» en UTF-8 o Excel (.xlsx) con las columnas vigencia;fuerza;esm;modalidad;valor. El ESM va por sigla o por nombre; la
          fuerza se puede dejar vacía salvo en HOMIL (fuerza de adscripción). Primero se simula: si una sola fila tiene error, no se aplica nada.
        </Text>
        <FileInput
          label="Archivo"
          accept=".csv,text/csv,.xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
          value={archivo}
          onChange={(a) => {
            setArchivo(a);
            setResultado(null);
            setErrores([]);
            v.reiniciar();
          }}
          clearable
          clearButtonProps={botonQuitar('el archivo')}
          error={v.error('archivo')}
          data-testid="carga-archivo"
        />
        <Group>
          <Button variant="light" disabled={!archivo} loading={simular.isPending} onClick={() => simular.mutate()} data-testid="carga-simular">
            Simular
          </Button>
        </Group>
        {resultado && (
          <Alert color={resultado.yaAplicado ? 'yellow' : errores.length ? 'red' : 'green'} data-testid="carga-resultado">
            <Text size="sm">{resultado.mensaje}</Text>
            {resultado.totalesPorFuerza && (
              <Text size="sm">
                {Object.entries(resultado.totalesPorFuerza)
                  .map(([k, v]) => `${k}: ${v}`)
                  .join(' · ')}
                {resultado.totalNacional ? ` · Nacional: ${resultado.totalNacional}` : ''}
              </Text>
            )}
          </Alert>
        )}
        {errores.length > 0 && (
          <ScrollArea.Autosize mah={240} type="auto">
            <Stack gap={2} data-testid="carga-errores">
              {errores.map((e) => (
                <Text key={e} size="sm" c="red">
                  {e}
                </Text>
              ))}
            </Stack>
          </ScrollArea.Autosize>
        )}
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <TextInput
            label="Soporte de la DIGSA"
            placeholder="Oficio DIGSA [número] del [fecha]"
            value={soporte}
            onChange={(e) => {
              setSoporte(e.currentTarget.value);
              v.limpiar('soporte');
            }}
            error={v.error('soporte')}
            data-testid="carga-soporte"
          />
          <Stack gap={4}>
            <Text size="sm" fw={500}>
              Fuente de la información
            </Text>
            <SegmentedControl
              data={[
                { value: 'digsa', label: 'DIGSA' },
                { value: 'opl', label: 'OPL' },
              ]}
              value={fuente}
              onChange={setFuente}
            />
          </Stack>
        </SimpleGrid>
        {v.resumen}
        <Group>
          <Button
            disabled={!listo}
            aria-describedby={listo ? undefined : idEspera}
            loading={aplicar.isPending}
            onClick={() => {
              if (v.validar({ soporte: soporte.trim().length < 5 ? 'Escriba el soporte de la DIGSA (mínimo 5 caracteres).' : null })) aplicar.mutate();
            }}
            data-testid="carga-aplicar"
          >
            Aplicar carga
          </Button>
          {!listo && (
            <Text size="sm" c="dimmed" id={idEspera}>
              {resultado?.yaAplicado ? 'Este archivo ya se aplicó.' : 'Para aplicar, simule primero un archivo sin errores.'}
            </Text>
          )}
        </Group>
      </Stack>
    </Card>
  );
}

// ---------- Configuración ----------

function Configuracion({ o }: { o: OpcionesPresupuesto }) {
  const qc = useQueryClient();
  const refrescar = () => void qc.invalidateQueries({ queryKey: ['presupuesto'] });
  const [anio, setAnio] = useState('');
  const [inicio, setInicio] = useState('');
  const [fin, setFin] = useState('');
  const [valores, setValores] = useState<Record<string, string>>(Object.fromEntries(o.parametros.map((p) => [p.clave, p.valor])));
  const vVigencia = useValidacion({ etiquetas: { anio: 'Año', inicio: 'Inicio', fin: 'Fin' }, testid: 'vigencia-error' });
  const vModalidad = useValidacion({ testid: 'modalidad-error' });
  const vParametro = useValidacion({ etiquetas: Object.fromEntries(o.parametros.map((p) => [p.clave, p.descripcion])), testid: 'parametro-error' });
  const vSatelite = useValidacion({ testid: 'centralizador-error' });

  /** Ejecuta un cambio: el aviso de éxito es pasajero; el error queda persistente en el resumen de la sección (v). */
  const ejecutar = async (promesa: () => Promise<{ mensaje: string }>, v: Validacion, defecto: string): Promise<boolean> => {
    v.reiniciar();
    try {
      const r = await promesa();
      notifications.show({ color: 'green', message: r.mensaje });
      refrescar();
      return true;
    } catch (e) {
      v.errorServidor(e, defecto);
      return false;
    }
  };
  // Al recargar las opciones, los parámetros muestran el valor que guardó el servidor (normalizado: «12,5 %» queda «12,50»)
  useEffect(() => setValores(Object.fromEntries(o.parametros.map((p) => [p.clave, p.valor]))), [o.parametros]);
  const satelites = o.puntos.filter((p) => p.modalidad === 'Satélite');
  const centralizadores = o.puntos.filter((p) => p.modalidad !== 'Satélite').map((p) => ({ value: String(p.id), label: etiquetaPunto(p) }));

  return (
    <Stack>
      <Card withBorder ref={vVigencia.ref}>
        <Title order={5}>Vigencias</Title>
        <Text size="sm" c="dimmed">
          Una vigencia por año fiscal; puede empezar o terminar a mitad de año. Una vigencia cerrada no admite novedades ni ejecución.
        </Text>
        <Stack gap="xs" mt="sm">
          {o.vigencias.map((v) => (
            <Group key={v.id} justify="space-between" wrap="wrap">
              <Text size="sm">
                {v.anio}: {fecha(v.inicio)} a {fecha(v.fin)}
              </Text>
              <Button
                size="compact-xs"
                variant="light"
                color={v.estado === 'abierta' ? 'red' : 'green'}
                onClick={() =>
                  void ejecutar(
                    () => api(`/api/presupuesto/vigencias/${v.id}`, { metodo: 'PUT', cuerpo: { estado: v.estado === 'abierta' ? 'cerrada' : 'abierta' } }),
                    vVigencia,
                    'No se pudo cambiar la vigencia.',
                  )
                }
              >
                {v.estado === 'abierta' ? 'Cerrar' : 'Reabrir'}
              </Button>
            </Group>
          ))}
        </Stack>
        {vVigencia.resumen && <Box mt="sm">{vVigencia.resumen}</Box>}
        <SimpleGrid cols={{ base: 1, sm: 4 }} mt="sm">
          <TextInput
            label="Año"
            inputMode="numeric"
            value={anio}
            onChange={(e) => {
              setAnio(e.currentTarget.value);
              vVigencia.limpiar('anio');
            }}
            error={vVigencia.error('anio')}
            data-testid="vigencia-anio"
          />
          <TextInput
            label="Inicio (opcional)"
            type="date"
            value={inicio}
            onChange={(e) => {
              setInicio(e.currentTarget.value);
              vVigencia.limpiar('inicio');
              vVigencia.limpiar('fin');
            }}
            error={vVigencia.error('inicio')}
          />
          <TextInput
            label="Fin (opcional)"
            type="date"
            value={fin}
            onChange={(e) => {
              setFin(e.currentTarget.value);
              vVigencia.limpiar('fin');
            }}
            error={vVigencia.error('fin')}
          />
          <Button
            mt={{ base: 0, sm: 24 }}
            onClick={() => {
              const errores = {
                anio: /^\d{4}$/.test(anio) ? null : 'Escriba el año con 4 dígitos.',
                fin: inicio && fin && inicio > fin ? 'El fin no puede ser anterior al inicio.' : null,
              };
              if (!vVigencia.validar(errores)) return;
              void ejecutar(
                () => api('/api/presupuesto/vigencias', { cuerpo: { anio: Number(anio), inicio: inicio || null, fin: fin || null } }),
                vVigencia,
                'No se pudo crear la vigencia.',
              ).then((ok) => ok && setAnio(''));
            }}
            data-testid="vigencia-crear"
          >
            Crear vigencia
          </Button>
        </SimpleGrid>
      </Card>

      <Card withBorder>
        <Title order={5}>Modalidades de facturación</Title>
        <Text size="sm" c="dimmed">
          Las tres del Anexo 5 §4.6 están activas; las demás del anexo del OPL se activan si la DIGSA las presupuesta aparte.
        </Text>
        {vModalidad.resumen && <Box mt="sm">{vModalidad.resumen}</Box>}
        <Stack gap={4} mt="sm">
          {o.modalidades.map((m) => (
            <Switch
              key={m.codigo}
              label={m.nombre}
              checked={m.activa}
              onChange={(e) => {
                const activa = e.currentTarget.checked;
                void ejecutar(
                  () => api(`/api/presupuesto/modalidades/${m.codigo}`, { metodo: 'PUT', cuerpo: { activa } }),
                  vModalidad,
                  'No se pudo cambiar la modalidad.',
                );
              }}
            />
          ))}
        </Stack>
      </Card>

      <Card withBorder ref={vParametro.ref}>
        <Title order={5}>Parámetros</Title>
        {vParametro.resumen && <Box mt="sm">{vParametro.resumen}</Box>}
        <Stack gap="xs" mt="sm">
          {o.parametros.map((p) => (
            <Group key={p.clave} align="flex-end" wrap="wrap">
              <TextInput
                label={p.descripcion}
                value={valores[p.clave] ?? ''}
                onChange={(e) => {
                  setValores({ ...valores, [p.clave]: e.currentTarget.value });
                  vParametro.limpiar(p.clave);
                }}
                error={vParametro.error(p.clave)}
                style={{ flex: '1 1 260px' }}
              />
              <Button
                variant="light"
                disabled={(valores[p.clave] ?? '') === p.valor}
                onClick={async () => {
                  vParametro.reiniciar();
                  try {
                    const r = await api<{ mensaje: string }>(`/api/presupuesto/parametros/${p.clave}`, {
                      metodo: 'PUT',
                      cuerpo: { valor: valores[p.clave] },
                    });
                    notifications.show({ color: 'green', message: r.mensaje });
                    refrescar();
                  } catch (e) {
                    // El servidor rechaza el valor de este parámetro: el mensaje va junto a su campo
                    vParametro.validar({ [p.clave]: e instanceof ErrorApi ? e.message : 'No se pudo guardar el parámetro.' });
                  }
                }}
              >
                Guardar
              </Button>
            </Group>
          ))}
        </Stack>
      </Card>

      <Card withBorder>
        <Title order={5}>Satélites y su ESM centralizador</Title>
        <Text size="sm" c="dimmed">
          Los satélites dependen presupuestalmente de un ESM centralizador (anexo del OPL §27.2): su ejecución se suma a la línea del centralizador.
        </Text>
        {vSatelite.resumen && <Box mt="sm">{vSatelite.resumen}</Box>}
        <Stack gap="xs" mt="sm">
          {satelites.length === 0 && <Text size="sm">No hay satélites en su alcance.</Text>}
          {satelites.map((s) => (
            <Select
              key={s.id}
              label={etiquetaPunto(s)}
              placeholder="Sin centralizador"
              clearable
              clearButtonProps={botonQuitar(`el centralizador de ${etiquetaPunto(s)}`)}
              searchable
              data={centralizadores}
              value={s.centralizadorId ? String(s.centralizadorId) : null}
              onChange={(v) =>
                void ejecutar(
                  () => api(`/api/presupuesto/puntos/${s.id}/centralizador`, { metodo: 'PUT', cuerpo: { centralizadorId: v ? Number(v) : null } }),
                  vSatelite,
                  'No se pudo guardar el centralizador.',
                )
              }
            />
          ))}
        </Stack>
      </Card>
    </Stack>
  );
}
