import {
  Alert,
  Badge,
  Button,
  Card,
  Checkbox,
  Drawer,
  FileInput,
  Group,
  Loader,
  Modal,
  Pagination,
  Progress,
  ScrollArea,
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
import { useEffect, useState, type ReactNode } from 'react';
import { cifra, fechaHora } from '../analitica/cargues';
import {
  colorEstado,
  consultaCalidad,
  consultaTabla,
  datoFinalTexto,
  escribirEntradas,
  esConflicto,
  estadoDevolucion,
  ESTADOS,
  fechaCorta,
  fuenteTexto,
  leerEntradas,
  MAX_MOTIVO,
  motivoVersion,
  NOMBRE_ESTADO,
  NOMBRE_PARAMETRO,
  porcentaje,
  problemaCargue,
  problemaMotivo,
  problemaParametro,
  problemasCarga,
  sumaPorcentajes,
  type Calidad,
  type Comparacion,
  type DetalleMaestra,
  type EntradaCatalogo,
  type EstadoReejecucion,
  type Evento,
  type FilaMaestra,
  type Fuente,
  type PaginaMaestra,
  type Regla,
  type Reglas,
  type Resumen,
  type VersionFuente,
} from '../analitica/homologacion';
import { api, descargar, ErrorApi, subirArchivos } from '../shared/api';

const CLAVE = ['homologacion'];

function mensajeError(e: unknown, defecto: string): string {
  return e instanceof ErrorApi ? e.message : defecto;
}

function hoy(): string {
  return new Date().toLocaleDateString('en-CA', { timeZone: 'America/Bogota' });
}

/**
 * «Homologación» (analitica-homologacion; mockup #homologacion): tablero de calidad con los 6 porcentajes que suman 100 y los
 * indicadores del A4 §7, tabla maestra normalizada (dato original → fuente con versión y fecha → regla → dato homologado) con buscador y
 * exportación, revisión OPL frente a INVIMA (aprobar o devolver; la sugerencia de la IA solo se muestra), reglas y catálogos editables con
 * traza, fuentes oficiales con su versión y fecha de corte, y trazabilidad del módulo. El Analista opera; el Coordinador consulta, revisa
 * y exporta.
 */
export function Homologacion() {
  const qc = useQueryClient();
  const resumen = useQuery({ queryKey: [...CLAVE, 'resumen'], queryFn: () => api<Resumen>('/api/homologacion/resumen') });
  const [pestana, setPestana] = useState<string | null>('calidad');
  const [resultado, setResultado] = useState<EstadoReejecucion['resultado']>(null);
  const [confirmar, setConfirmar] = useState(false);
  const [siguiendo, setSiguiendo] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Re-ejecución diferida: se lanza (202) y se sigue su avance hasta que termina
  const actual = useQuery({
    queryKey: [...CLAVE, 'reejecucion'],
    queryFn: () => api<EstadoReejecucion>('/api/homologacion/ejecuciones/actual'),
    refetchInterval: (q) => (q.state.data?.estado === 'en_curso' || siguiendo ? 1500 : false),
  });
  const enCurso = actual.data?.estado === 'en_curso';
  useEffect(() => {
    const a = actual.data;
    if (!siguiendo || !a || a.estado === 'en_curso') return;
    setSiguiendo(false);
    if (a.estado === 'terminada') {
      setResultado(a.resultado);
      qc.invalidateQueries({ queryKey: CLAVE });
    } else if (a.estado === 'fallida') {
      setError(a.error ?? 'La re-ejecución no terminó; la tabla maestra no cambió.');
    }
  }, [actual.data, siguiendo, qc]);
  const reejecutar = useMutation({
    mutationFn: () => api<EstadoReejecucion>('/api/homologacion/ejecuciones', { metodo: 'POST' }),
    onMutate: () => setError(null),
    onSuccess: () => {
      setConfirmar(false);
      setSiguiendo(true);
      qc.invalidateQueries({ queryKey: [...CLAVE, 'reejecucion'] });
    },
    onError: (e) => {
      setConfirmar(false);
      setError(mensajeError(e, 'No se pudo re-ejecutar la homologación.'));
    },
  });
  const r = resumen.data;
  return (
    <Stack>
      <Group justify="space-between" align="flex-start">
        <div>
          <Title order={3}>Homologación</Title>
          <Text size="sm" c="dimmed">
            Homologación del insumo del OPL contra las referencias oficiales (CUM del INVIMA, CIE-10 de SISPRO y listas normativas), con reglas
            deterministas versionadas y tabla maestra trazable. La IA solo sugiere: toda decisión es humana.
          </Text>
        </div>
        <Group>
          {r?.datosDeEjemplo && (
            <Badge color="orange" size="lg" data-testid="datos-ejemplo">
              Datos de ejemplo
            </Badge>
          )}
          {r?.vigente && (
            <Badge variant="light" size="lg" data-testid="version-vigente">
              Tabla maestra v{r.vigente.numero}
            </Badge>
          )}
          {r?.puedeOperar && (
            <Button onClick={() => setConfirmar(true)} loading={reejecutar.isPending} disabled={enCurso} data-testid="reejecutar">
              Re-ejecutar homologación
            </Button>
          )}
        </Group>
      </Group>
      {r?.desactualizada && (
        // Revisión A2: la homologación del cargue es incremental; los datos que ya estaban conservan su fila hasta re-ejecutar
        <Alert color="orange" title="La tabla maestra está desactualizada" data-testid="aviso-desactualizada">
          <Stack gap="xs">
            <Text size="sm">{r.desactualizada.motivo}</Text>
            {r.puedeOperar ? (
              <Group>
                <Button size="xs" onClick={() => setConfirmar(true)} disabled={enCurso} data-testid="reejecutar-desactualizada">
                  Re-ejecutar la homologación
                </Button>
              </Group>
            ) : (
              <Text size="sm" c="dimmed">
                Un administrador o analista debe re-ejecutar la homologación para aplicarlo.
              </Text>
            )}
          </Stack>
        </Alert>
      )}
      {enCurso && actual.data && (
        <Alert color="blue" data-testid="reejecucion-en-curso">
          <Stack gap={4}>
            <Text size="sm">
              Re-ejecución en curso ({actual.data.responsable}): {actual.data.fase ?? 'en cola'} · {actual.data.porcentaje} %
            </Text>
            <Progress value={actual.data.porcentaje} aria-label="Avance de la re-ejecución" />
          </Stack>
        </Alert>
      )}
      {resumen.error && <Alert color="red">{resumen.error.message}</Alert>}
      {error && (
        <Alert color="red" withCloseButton onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      {r && r.fuentesPublicadas === 0 && (
        <Alert color="yellow">No hay referencias oficiales publicadas: cargue y publique el CUM del INVIMA en «Fuentes oficiales».</Alert>
      )}
      <Tabs value={pestana} onChange={setPestana} keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="calidad">Calidad</Tabs.Tab>
          <Tabs.Tab value="tabla">Tabla maestra</Tabs.Tab>
          <Tabs.Tab value="revision">Revisión</Tabs.Tab>
          <Tabs.Tab value="reglas">Reglas y catálogos</Tabs.Tab>
          <Tabs.Tab value="fuentes">Fuentes oficiales</Tabs.Tab>
          <Tabs.Tab value="trazabilidad">Trazabilidad</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="calidad" pt="md">
          <PestanaCalidad resumen={r} />
        </Tabs.Panel>
        <Tabs.Panel value="tabla" pt="md">
          <PestanaTabla resumen={r} />
        </Tabs.Panel>
        <Tabs.Panel value="revision" pt="md">
          <PestanaRevision />
        </Tabs.Panel>
        <Tabs.Panel value="reglas" pt="md">
          <PestanaReglas puedeOperar={!!r?.puedeOperar} />
        </Tabs.Panel>
        <Tabs.Panel value="fuentes" pt="md">
          <PestanaFuentes puedeOperar={!!r?.puedeOperar} />
        </Tabs.Panel>
        <Tabs.Panel value="trazabilidad" pt="md">
          <PestanaTrazabilidad />
        </Tabs.Panel>
      </Tabs>
      <Modal opened={confirmar} onClose={() => setConfirmar(false)} title="Re-ejecutar la homologación">
        <Stack>
          <Text size="sm">
            Se homologan de nuevo todos los datos originales con las reglas, catálogos y fuentes publicadas de hoy y se crea una versión nueva de la
            tabla maestra (las anteriores y los hechos ya cargados no cambian; las decisiones de Revisión se conservan). Corre en segundo plano y
            puede tardar varios minutos.
          </Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setConfirmar(false)}>
              Cancelar
            </Button>
            <Button onClick={() => reejecutar.mutate()} loading={reejecutar.isPending} data-testid="confirmar-reejecucion">
              Re-ejecutar
            </Button>
          </Group>
        </Stack>
      </Modal>
      <Modal opened={resultado !== null} onClose={() => setResultado(null)} title="Homologación re-ejecutada">
        {resultado && (
          <Stack data-testid="resultado-reejecucion">
            <Text>
              Versión v{resultado.numero} creada. {resultado.cambios} datos originales cambiaron de estado ({resultado.filas} filas nuevas o
              cambiadas).
            </Text>
            <Table withTableBorder>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Estado</Table.Th>
                  <Table.Th ta="right">Antes</Table.Th>
                  <Table.Th ta="right">Después</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {ESTADOS.map((e) => (
                  <Table.Tr key={e.codigo}>
                    <Table.Td>{e.texto}</Table.Td>
                    <Table.Td ta="right">{cifra(resultado.antes[e.codigo])}</Table.Td>
                    <Table.Td ta="right">{cifra(resultado.despues[e.codigo])}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Stack>
        )}
      </Modal>
    </Stack>
  );
}

function opcionesVersion(r: Resumen | undefined) {
  return (r?.versiones ?? []).map((v) => ({
    value: String(v.id),
    label: `v${v.numero} · ${motivoVersion(v.motivo)} · ${fechaHora(v.creada)}`,
  }));
}

async function exportar(ruta: string, setError: (m: string | null) => void) {
  setError(null);
  try {
    await descargar(ruta);
  } catch (e) {
    setError(mensajeError(e, 'No se pudo exportar.'));
  }
}

// ---------- Calidad ----------

function PestanaCalidad({ resumen }: { resumen: Resumen | undefined }) {
  const [f, setF] = useState<{ version: string | null; cargue: string; fuente: string | null; desde: string; hasta: string }>({
    version: null,
    cargue: '',
    fuente: null,
    desde: '',
    hasta: '',
  });
  const [error, setError] = useState<string | null>(null);
  const filtro = { version: f.version ? Number(f.version) : null, cargue: f.cargue, fuente: f.fuente, desde: f.desde, hasta: f.hasta };
  const q = consultaCalidad(filtro);
  const cargueMal = problemaCargue(f.cargue);
  const calidad = useQuery({
    queryKey: [...CLAVE, 'calidad', q],
    queryFn: () => api<Calidad>('/api/homologacion/calidad' + q),
    placeholderData: keepPreviousData,
    enabled: cargueMal === null,
  });
  const c = calidad.data;
  return (
    <Stack>
      <Group align="end" wrap="wrap">
        <Select
          label="Versión de la tabla maestra"
          placeholder="Vigente"
          clearable
          data={opcionesVersion(resumen)}
          value={f.version}
          onChange={(v) => setF({ ...f, version: v })}
          w={300}
        />
        <TextInput
          label="Cargue"
          placeholder="Todos"
          value={f.cargue}
          error={cargueMal}
          onChange={(e) => setF({ ...f, cargue: e.currentTarget.value })}
          w={110}
        />
        <Select
          label="Fuente del insumo"
          placeholder="Todas"
          clearable
          value={f.fuente}
          onChange={(v) => setF({ ...f, fuente: v })}
          w={170}
          data={[
            { value: 'archivo', label: 'Archivo' },
            { value: 'ws_opl', label: 'API OPL' },
          ]}
        />
        <TextInput label="Desde (mes)" type="month" value={f.desde} onChange={(e) => setF({ ...f, desde: e.currentTarget.value })} w={160} />
        <TextInput label="Hasta (mes)" type="month" value={f.hasta} onChange={(e) => setF({ ...f, hasta: e.currentTarget.value })} w={160} />
        <Button
          variant="default"
          disabled={cargueMal !== null}
          onClick={() => exportar('/api/homologacion/calidad/exportar' + consultaCalidad({ ...filtro, formato: 'csv' }), setError)}
        >
          Exportar CSV
        </Button>
        <Button
          variant="default"
          disabled={cargueMal !== null}
          onClick={() => exportar('/api/homologacion/calidad/exportar' + consultaCalidad({ ...filtro, formato: 'xlsx' }), setError)}
        >
          Exportar Excel
        </Button>
      </Group>
      {error && <Alert color="red">{error}</Alert>}
      {calidad.error && <Alert color="red">{calidad.error.message}</Alert>}
      {calidad.isLoading && <Loader />}
      {c && (
        <>
          <SimpleGrid cols={{ base: 1, xs: 2, md: 3, lg: 6 }} data-testid="kpi-calidad">
            {c.kpis.map((k) => (
              <Card key={k.estado} withBorder padding="sm" data-testid={`kpi-${k.estado}`}>
                <Text size="xs" c="dimmed">
                  {ESTADOS.find((e) => e.codigo === k.estado)?.texto ?? k.texto}
                </Text>
                <Text fw={700} size="xl" c={colorEstado(k.estado)} data-testid="porcentaje">
                  {porcentaje(Number(k.porcentaje))}
                </Text>
                <Text size="xs">
                  {cifra(k.registros)} registros · {cifra(k.origenes)} datos originales
                </Text>
              </Card>
            ))}
          </SimpleGrid>
          <Text size="sm" c="dimmed" data-testid="suma-calidad">
            {cifra(c.registros)} registros de dispensación y {cifra(c.origenes)} datos originales distintos
            {c.version ? ` · tabla maestra v${c.version.numero}` : ''} · suma {porcentaje(sumaPorcentajes(c.kpis))}
          </Text>
          {(c.registrosFueraDeVersion ?? 0) > 0 && (
            <Alert color="yellow" data-testid="fuera-de-version">
              {cifra(c.registrosFueraDeVersion ?? 0)} registros tienen un dato original que no existía en la versión elegida (llegó después): no
              entran en los porcentajes. Elija una versión más reciente para verlos.
            </Alert>
          )}
          <Title order={5}>Calidad por cargue (válidos, rechazados, duplicados, consistencia y oportunidad)</Title>
          <ScrollArea>
            <Table withTableBorder striped miw={900} data-testid="indicadores-cargue">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Cargue</Table.Th>
                  <Table.Th>Aplicado</Table.Th>
                  <Table.Th>Fuente</Table.Th>
                  <Table.Th ta="right">Válidos</Table.Th>
                  <Table.Th ta="right">Rechazados</Table.Th>
                  <Table.Th ta="right">Duplicados</Table.Th>
                  <Table.Th ta="right">Consistencia</Table.Th>
                  <Table.Th ta="right">Oportunidad</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {c.cargues.map((x) => (
                  <Table.Tr key={x.cargue}>
                    <Table.Td>{x.cargue}</Table.Td>
                    <Table.Td>{fechaHora(x.aplicadoEn)}</Table.Td>
                    <Table.Td>{x.fuente}</Table.Td>
                    <Table.Td ta="right">{cifra(x.validos)}</Table.Td>
                    <Table.Td ta="right" c={x.rechazados > 0 ? 'red' : undefined}>
                      {cifra(x.rechazados)}
                    </Table.Td>
                    <Table.Td ta="right">{cifra(x.duplicados)}</Table.Td>
                    <Table.Td ta="right">{porcentaje(Number(x.consistencia))}</Table.Td>
                    <Table.Td ta="right" c={x.oportunidadCumple === false ? 'red' : undefined}>
                      {x.oportunidadHoras === null ? '—' : `${x.oportunidadHoras} h (máx. ${c.oportunidadMaximaHoras} h)`}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollArea>
          {c.cie10.length > 0 && (
            <Text size="sm">
              CIE-10 (códigos distintos):{' '}
              {c.cie10.map((x) => `${NOMBRE_ESTADO[x.estado as keyof typeof NOMBRE_ESTADO] ?? x.estado} ${x.codigos}`).join(' · ')}
            </Text>
          )}
        </>
      )}
      <SeguimientoOpl />
    </Stack>
  );
}

function SeguimientoOpl() {
  const q = useQuery({
    queryKey: [...CLAVE, 'devoluciones'],
    queryFn: () =>
      api<{
        filas: Array<{
          id: number;
          descripcion: string | null;
          cum: string | null;
          motivo: string;
          fecha: string;
          usuario: string;
          estado: string;
          fuente: string | null;
          periodo: string | null;
          corregidaEnCargue: number | null;
        }>;
        abiertas: Array<{ fuente: string; periodo: string; abiertas: number }>;
        correccionesPorMes: Array<{ mes: string; corregidas: number }>;
        total: number;
        mostradas: number;
      }>('/api/homologacion/devoluciones'),
  });
  const [error, setError] = useState<string | null>(null);
  const d = q.data;
  return (
    <Stack gap="xs" data-testid="seguimiento-opl">
      <Group justify="space-between">
        <Title order={5}>Seguimiento de las devoluciones al OPL</Title>
        <Button variant="default" size="xs" onClick={() => exportar('/api/homologacion/devoluciones/exportar?formato=csv', setError)}>
          Exportar para el OPL (todas)
        </Button>
      </Group>
      {error && <Alert color="red">{error}</Alert>}
      {q.error && <Alert color="red">{mensajeError(q.error, 'No se pudo leer el seguimiento de las devoluciones.')}</Alert>}
      {q.isLoading && <Loader size="sm" />}
      {d && d.filas.length === 0 && <Text c="dimmed">No hay devoluciones.</Text>}
      {d && d.total > d.filas.length && (
        <Text size="sm" c="dimmed" data-testid="devoluciones-parcial">
          Se muestran las {cifra(d.filas.length)} más recientes de {cifra(d.total)}; la exportación las trae todas.
        </Text>
      )}
      {d && d.abiertas.length > 0 && (
        <Text size="sm">Abiertas por fuente y periodo: {d.abiertas.map((a) => `${a.fuente} ${a.periodo}: ${a.abiertas}`).join(' · ')}</Text>
      )}
      {d && d.correccionesPorMes.length > 0 && (
        <Text size="sm">Corregidas por mes: {d.correccionesPorMes.map((a) => `${a.mes}: ${a.corregidas}`).join(' · ')}</Text>
      )}
      {d && d.filas.length > 0 && (
        <ScrollArea>
          <Table withTableBorder miw={800}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Dato original</Table.Th>
                <Table.Th>Motivo</Table.Th>
                <Table.Th>Fecha</Table.Th>
                <Table.Th>Usuario</Table.Th>
                <Table.Th>Periodo</Table.Th>
                <Table.Th>Estado</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {d.filas.map((x) => (
                <Table.Tr key={x.id}>
                  <Table.Td>{x.descripcion ?? x.cum ?? '—'}</Table.Td>
                  <Table.Td>{x.motivo}</Table.Td>
                  <Table.Td>{fechaHora(x.fecha)}</Table.Td>
                  <Table.Td>{x.usuario}</Table.Td>
                  <Table.Td>{x.periodo ?? '—'}</Table.Td>
                  <Table.Td>
                    <Badge color={estadoDevolucion(x.estado).color}>{estadoDevolucion(x.estado).texto}</Badge>
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

// ---------- Tabla maestra ----------

function PestanaTabla({ resumen }: { resumen: Resumen | undefined }) {
  const [f, setF] = useState<{ version: string | null; q: string; estado: string | null }>({ version: null, q: '', estado: null });
  const [buscar, setBuscar] = useState('');
  const [pagina, setPagina] = useState(1);
  const [ver, setVer] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const filtro = { version: f.version ? Number(f.version) : null, q: f.q, estado: f.estado };
  const q = consultaTabla({ ...filtro, pagina });
  const tabla = useQuery({
    queryKey: [...CLAVE, 'tabla', q],
    queryFn: () => api<PaginaMaestra>('/api/homologacion/tabla-maestra' + q),
    placeholderData: keepPreviousData,
  });
  const t = tabla.data;
  return (
    <Stack>
      <Group align="end" wrap="wrap">
        <TextInput
          label="Buscar"
          placeholder="Medicamento, CUM, DCI o ATC"
          value={buscar}
          onChange={(e) => setBuscar(e.currentTarget.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              setF({ ...f, q: buscar });
              setPagina(1);
            }
          }}
          w={280}
          data-testid="buscar-tabla"
        />
        <Button
          variant="default"
          onClick={() => {
            setF({ ...f, q: buscar });
            setPagina(1);
          }}
        >
          Buscar
        </Button>
        <Select
          label="Estado"
          placeholder="Todos"
          clearable
          value={f.estado}
          w={220}
          data={ESTADOS.map((e) => ({ value: e.codigo, label: NOMBRE_ESTADO[e.codigo] }))}
          onChange={(v) => {
            setF({ ...f, estado: v });
            setPagina(1);
          }}
        />
        <Select
          label="Versión"
          placeholder="Vigente"
          clearable
          data={opcionesVersion(resumen)}
          value={f.version}
          w={300}
          onChange={(v) => {
            setF({ ...f, version: v });
            setPagina(1);
          }}
        />
        <Button
          variant="default"
          onClick={() => exportar('/api/homologacion/tabla-maestra/exportar' + consultaTabla({ ...filtro, formato: 'csv' }), setError)}
          data-testid="exportar-csv"
        >
          Exportar CSV
        </Button>
        <Button
          variant="default"
          onClick={() => exportar('/api/homologacion/tabla-maestra/exportar' + consultaTabla({ ...filtro, formato: 'xlsx' }), setError)}
        >
          Exportar Excel
        </Button>
      </Group>
      {error && <Alert color="red">{error}</Alert>}
      {tabla.error && <Alert color="red">{tabla.error.message}</Alert>}
      {tabla.isLoading && <Loader />}
      {t && t.filas.length === 0 && <Text c="dimmed">No hay registros con estos filtros.</Text>}
      {t && t.filas.length > 0 && (
        <>
          <Text size="sm" c="dimmed">
            {cifra(t.total)} datos originales · tabla maestra v{t.version?.numero}
          </Text>
          <TablaMaestra filas={t.filas} alVer={setVer} />
          {t.paginas > 1 && <Pagination total={t.paginas} value={pagina} onChange={setPagina} />}
        </>
      )}
      <Drawer opened={ver !== null} onClose={() => setVer(null)} position="right" size="xl" title="Cadena del registro">
        {ver !== null && <DetalleRegistro origenId={ver} version={filtro.version} />}
      </Drawer>
    </Stack>
  );
}

function TablaMaestra({ filas, alVer, accion }: { filas: FilaMaestra[]; alVer: (id: number) => void; accion?: string }) {
  return (
    <ScrollArea>
      <Table striped withTableBorder miw={1100} data-testid="tabla-maestra">
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Dato original (OPL)</Table.Th>
            <Table.Th>Fuente consultada</Table.Th>
            <Table.Th>Regla aplicada</Table.Th>
            <Table.Th>Dato homologado</Table.Th>
            <Table.Th>ATC</Table.Th>
            <Table.Th>Estado</Table.Th>
            <Table.Th>Acción</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {filas.map((x) => (
            <Table.Tr key={x.origenId} data-testid={`fila-${x.origenId}`}>
              <Table.Td>
                <Text size="sm">{x.original.descripcion ?? '—'}</Text>
                {x.original.cum && (
                  <Text size="xs" c="dimmed">
                    CUM {x.original.cum}
                  </Text>
                )}
              </Table.Td>
              <Table.Td>
                <Text size="sm">{fuenteTexto(x.fuente)}</Text>
                {x.fuente?.ejemplo && (
                  <Badge size="xs" color="orange">
                    Datos de ejemplo
                  </Badge>
                )}
              </Table.Td>
              <Table.Td>
                <Text size="sm" fw={600}>
                  {x.regla}
                </Text>
                <Text size="xs" c="dimmed">
                  {x.causa}
                </Text>
              </Table.Td>
              <Table.Td>
                <Text size="sm">{datoFinalTexto(x.final)}</Text>
                {x.diferencias.length > 0 && (
                  <Text size="xs" c="orange">
                    {x.diferencias.join(' · ')}
                  </Text>
                )}
              </Table.Td>
              <Table.Td>{x.final?.atc ?? '—'}</Table.Td>
              <Table.Td>
                <Badge color={colorEstado(x.estado)} data-testid="estado-maestra">
                  {x.estadoTexto}
                </Badge>
                {x.devuelto && (
                  <Text size="xs" c="dimmed">
                    devuelto al OPL
                  </Text>
                )}
              </Table.Td>
              <Table.Td>
                <Button
                  variant="subtle"
                  size="compact-sm"
                  onClick={() => alVer(x.origenId)}
                  aria-label={`${accion ?? x.accion} ${x.original.descripcion ?? x.origenId}`}
                >
                  {accion ?? x.accion}
                </Button>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </ScrollArea>
  );
}

function Dato({ titulo, children }: { titulo: string; children: ReactNode }) {
  return (
    <div>
      <Text size="xs" c="dimmed">
        {titulo}
      </Text>
      <Text size="sm" style={{ overflowWrap: 'anywhere' }}>
        {children ?? '—'}
      </Text>
    </div>
  );
}

function DetalleRegistro({ origenId, version }: { origenId: number; version: number | null }) {
  const q = useQuery({
    queryKey: [...CLAVE, 'detalle', origenId, version],
    queryFn: () => api<DetalleMaestra>(`/api/homologacion/tabla-maestra/${origenId}${version ? `?version=${version}` : ''}`),
  });
  const d = q.data;
  if (q.error) return <Alert color="red">{q.error.message}</Alert>;
  if (!d) return <Loader />;
  return (
    <Stack data-testid="detalle-registro">
      <Group justify="space-between">
        <Text fw={700}>{d.original.descripcion ?? d.original.cum}</Text>
        <Badge color={colorEstado(d.estado)}>{d.estadoTexto}</Badge>
      </Group>
      <Title order={6}>Dato original (OPL)</Title>
      <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="xs">
        <Dato titulo="CUM">{d.original.cum}</Dato>
        <Dato titulo="Principio activo">{d.original.principioActivo}</Dato>
        <Dato titulo="Concentración">{d.original.concentracion}</Dato>
        <Dato titulo="Forma">{d.original.forma}</Dato>
        <Dato titulo="Presentación">{d.original.presentacion}</Dato>
        <Dato titulo="ATC">{d.original.atc}</Dato>
      </SimpleGrid>
      <Title order={6}>Fuente consultada</Title>
      <Text size="sm">{fuenteTexto(d.fuente)}</Text>
      <Title order={6}>Reglas aplicadas</Title>
      <Table withTableBorder data-testid="pasos">
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Regla</Table.Th>
            <Table.Th>Antes</Table.Th>
            <Table.Th>Después</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {d.reglasAplicadas.map((p, i) => (
            <Table.Tr key={i}>
              <Table.Td>{p.regla}</Table.Td>
              <Table.Td>{p.antes}</Table.Td>
              <Table.Td>{p.despues}</Table.Td>
            </Table.Tr>
          ))}
          <Table.Tr>
            <Table.Td fw={700}>{d.regla}</Table.Td>
            <Table.Td colSpan={2}>{d.causa ?? 'Decide el estado'}</Table.Td>
          </Table.Tr>
        </Table.Tbody>
      </Table>
      <Title order={6}>Dato homologado</Title>
      <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="xs">
        <Dato titulo="DCI">{d.final?.dci}</Dato>
        <Dato titulo="Nombre comercial">{d.final?.nombreComercial}</Dato>
        <Dato titulo="Concentración">{d.final?.concentracion}</Dato>
        <Dato titulo="Forma">{d.final?.forma}</Dato>
        <Dato titulo="Presentación">{d.final?.presentacion}</Dato>
        <Dato titulo="CUM">{d.final?.cum}</Dato>
        <Dato titulo="Código SSFM">{d.final?.codigoSsfm}</Dato>
        <Dato titulo="ATC · grupo">{d.final ? `${d.final.atc ?? '—'} · ${d.final.grupoFarmacologico ?? '—'}` : null}</Dato>
        <Dato titulo="Registro sanitario">{d.final?.registroSanitario}</Dato>
      </SimpleGrid>
      <Text size="sm">
        {cifra(d.registros)} registros de dispensación aplicados usan este dato · versión v{d.version.numero}
      </Text>
      {d.cargues.length > 0 && (
        <Table withTableBorder>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Cargue</Table.Th>
              <Table.Th>Fuente</Table.Th>
              <Table.Th>Aplicado</Table.Th>
              <Table.Th ta="right">Registros</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {d.cargues.map((c) => (
              <Table.Tr key={c.cargue}>
                <Table.Td>{c.cargue}</Table.Td>
                <Table.Td>{c.fuente}</Table.Td>
                <Table.Td>{fechaHora(c.aplicadoEn)}</Table.Td>
                <Table.Td ta="right">{cifra(c.registros)}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      {(d.carguesTotal ?? 0) > d.cargues.length && (
        <Text size="xs" c="dimmed">
          Se muestran los {d.cargues.length} cargues más recientes de {cifra(d.carguesTotal ?? 0)}.
        </Text>
      )}
      {d.revisiones.length > 0 && (
        <Text size="sm">
          Revisión:{' '}
          {d.revisiones
            .map((r) => `${r.accion === 'aprobar' ? 'aprobado' : 'devuelto'} por ${r.responsable} (${fechaHora(r.fecha)}, v${r.version})`)
            .join(' · ')}
        </Text>
      )}
    </Stack>
  );
}

// ---------- Revisión ----------

function PestanaRevision() {
  const [estado, setEstado] = useState<string | null>(null);
  const [pagina, setPagina] = useState(1);
  const [revisar, setRevisar] = useState<number | null>(null);
  const q = consultaTabla({ estado, pagina });
  const lista = useQuery({
    queryKey: [...CLAVE, 'revision', q],
    queryFn: () => api<PaginaMaestra>('/api/homologacion/revision' + q),
    placeholderData: keepPreviousData,
  });
  const t = lista.data;
  return (
    <Stack>
      <Group align="end">
        <Select
          label="Estado"
          placeholder="Todos los que requieren revisión"
          clearable
          value={estado}
          w={280}
          data={ESTADOS.filter((e) => e.codigo !== 'homologado').map((e) => ({ value: e.codigo, label: NOMBRE_ESTADO[e.codigo] }))}
          onChange={(v) => {
            setEstado(v);
            setPagina(1);
          }}
        />
      </Group>
      {lista.error && <Alert color="red">{lista.error.message}</Alert>}
      {t && t.filas.length === 0 && <Text c="dimmed">No hay registros por revisar.</Text>}
      {t && t.filas.length > 0 && (
        <>
          <Text size="sm" c="dimmed">
            {cifra(t.total)} datos originales por revisar
          </Text>
          <TablaMaestra filas={t.filas} alVer={setRevisar} accion="Revisar" />
          {t.paginas > 1 && <Pagination total={t.paginas} value={pagina} onChange={setPagina} />}
        </>
      )}
      <Modal opened={revisar !== null} onClose={() => setRevisar(null)} size="xl" title="Revisión">
        {revisar !== null && <Revision origenId={revisar} alTerminar={() => setRevisar(null)} />}
      </Modal>
    </Stack>
  );
}

function Revision({ origenId, alTerminar }: { origenId: number; alTerminar: () => void }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: [...CLAVE, 'comparacion', origenId], queryFn: () => api<Comparacion>(`/api/homologacion/revision/${origenId}`) });
  const [cum, setCum] = useState<string | null>(null);
  const [motivo, setMotivo] = useState('');
  const [devolviendo, setDevolviendo] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [cambio, setCambio] = useState(false);
  const [sugerencia, setSugerencia] = useState<{ mensaje: string; sugerencia?: { cum: string; justificacion: string } | null } | null>(null);
  const terminar = () => {
    qc.invalidateQueries({ queryKey: CLAVE });
    alTerminar();
  };
  // La decisión va con la versión revisada: si otra persona cambió la tabla maestra entretanto, 409 y se vuelve a abrir el registro
  const fallo = (e: unknown, defecto: string) => {
    if (esConflicto(e)) {
      setCambio(true);
      setError(null);
      return;
    }
    setError(mensajeError(e, defecto));
  };
  const version = q.data?.version;
  const aprobar = useMutation({
    mutationFn: () => api(`/api/homologacion/revision/${origenId}/aprobar`, { metodo: 'POST', cuerpo: cum ? { cum, version } : { version } }),
    onMutate: () => setError(null),
    onSuccess: terminar,
    onError: (e) => fallo(e, 'No se pudo aprobar.'),
  });
  const devolver = useMutation({
    mutationFn: () => api(`/api/homologacion/revision/${origenId}/devolver`, { metodo: 'POST', cuerpo: { motivo, version } }),
    onMutate: () => setError(null),
    onSuccess: terminar,
    onError: (e) => fallo(e, 'No se pudo devolver.'),
  });
  const pedirSugerencia = useMutation({
    mutationFn: () =>
      api<{ mensaje: string; sugerencia?: { cum: string; justificacion: string } | null }>(`/api/homologacion/revision/${origenId}/sugerencia`, {
        metodo: 'POST',
      }),
    onSuccess: setSugerencia,
    onError: (e) => setError(mensajeError(e, 'No se pudo pedir la sugerencia.')),
  });
  const c = q.data;
  if (q.error) return <Alert color="red">{mensajeError(q.error, 'No se pudo abrir el registro.')}</Alert>;
  if (!c) return <Loader />;
  const problema = problemaMotivo(motivo);
  const ocupado = aprobar.isPending || devolver.isPending || pedirSugerencia.isPending;
  const faltaCum = c.exigeCum === true && !cum;
  return (
    <Stack data-testid="revision">
      <Group justify="space-between">
        <Text fw={700}>{c.descripcion ?? `Dato ${c.origenId}`}</Text>
        <Badge color={colorEstado(c.estado)}>{NOMBRE_ESTADO[c.estado]}</Badge>
      </Group>
      <Text size="sm" c="dimmed">
        {c.regla} · {c.causa} · tabla maestra v{c.version}
      </Text>
      {cambio && (
        <Alert color="orange" data-testid="revision-cambio">
          <Group justify="space-between">
            <Text size="sm">La tabla maestra cambió desde que abrió el registro; vuelva a abrirlo y revise de nuevo.</Text>
            <Button
              size="xs"
              variant="light"
              onClick={() => {
                setCambio(false);
                setCum(null);
                qc.invalidateQueries({ queryKey: CLAVE });
              }}
            >
              Volver a abrir
            </Button>
          </Group>
        </Alert>
      )}
      <Table withTableBorder data-testid="comparacion">
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Campo</Table.Th>
            <Table.Th>OPL</Table.Th>
            <Table.Th>INVIMA</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {c.campos.map((x) => (
            <Table.Tr
              key={x.campo}
              style={x.distinto ? { background: 'var(--mantine-color-orange-light)' } : undefined}
              data-distinto={x.distinto}
              aria-label={x.distinto ? `${x.campo}: distinto entre el OPL y el INVIMA` : undefined}
            >
              <Table.Td>
                <Group gap={6} wrap="nowrap">
                  <span>{x.campo}</span>
                  {x.distinto && (
                    <Badge color="orange" variant="outline" size="sm" data-testid="campo-distinto">
                      ≠ Distinto
                    </Badge>
                  )}
                </Group>
              </Table.Td>
              <Table.Td>{x.opl ?? '—'}</Table.Td>
              <Table.Td>{x.invima ?? '—'}</Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      {c.candidatos.length > 0 && (
        <Select
          label={c.exigeCum ? 'CUM vigente del INVIMA para aprobar (obligatorio: registro no válido)' : 'CUM del INVIMA para aprobar (opcional)'}
          placeholder={c.exigeCum ? 'Elija un CUM vigente' : 'El del INVIMA de la comparación'}
          clearable
          value={cum}
          onChange={setCum}
          data={c.candidatos.map((x) => ({
            value: x.cum,
            label: `${x.cum} · ${x.producto ?? ''} · ${x.principios ?? ''} · ${x.forma ?? ''} (${x.conjunto})`,
          }))}
        />
      )}
      {faltaCum && (
        <Text size="sm" c="orange" data-testid="exige-cum">
          El registro tiene «CUM o registro no válido»: para aprobarlo elija un CUM vigente del INVIMA.
        </Text>
      )}
      {c.sugeridor.disponible ? (
        <Group>
          <Button variant="light" onClick={() => pedirSugerencia.mutate()} loading={pedirSugerencia.isPending} disabled={ocupado}>
            Pedir sugerencia (IA)
          </Button>
          {sugerencia && (
            <Text size="sm" data-testid="sugerencia">
              {sugerencia.sugerencia
                ? `Sugerencia (IA): CUM ${sugerencia.sugerencia.cum} · ${sugerencia.sugerencia.justificacion}`
                : sugerencia.mensaje}
            </Text>
          )}
        </Group>
      ) : (
        <Text size="sm" c="dimmed" data-testid="sin-sugerencia">
          Sin sugerencia automática
        </Text>
      )}
      {error && <Alert color="red">{error}</Alert>}
      {devolviendo && (
        <Textarea
          label="Motivo de la devolución"
          description="Describa el problema del dato del medicamento. No escriba datos de pacientes (nombres, documentos, teléfonos ni correos)."
          value={motivo}
          maxLength={MAX_MOTIVO}
          onChange={(e) => setMotivo(e.currentTarget.value)}
          error={motivo !== '' ? problema : null}
          autosize
          minRows={2}
          data-testid="motivo"
        />
      )}
      <Group justify="flex-end">
        {!devolviendo ? (
          <Button variant="default" color="red" onClick={() => setDevolviendo(true)} disabled={ocupado || cambio}>
            Devolver al OPL
          </Button>
        ) : (
          <Button color="red" disabled={problema !== null || ocupado || cambio} loading={devolver.isPending} onClick={() => devolver.mutate()}>
            Confirmar devolución
          </Button>
        )}
        <Button onClick={() => aprobar.mutate()} loading={aprobar.isPending} disabled={c.estado === 'homologado' || ocupado || cambio || faltaCum}>
          Aprobar homologación
        </Button>
      </Group>
    </Stack>
  );
}

// ---------- Reglas y catálogos ----------

function PestanaReglas({ puedeOperar }: { puedeOperar: boolean }) {
  const q = useQuery({ queryKey: [...CLAVE, 'reglas'], queryFn: () => api<Reglas>('/api/homologacion/reglas') });
  const [editar, setEditar] = useState<Regla | null>(null);
  const [catalogo, setCatalogo] = useState<string | null>(null);
  const d = q.data;
  if (q.error) return <Alert color="red">{q.error.message}</Alert>;
  if (!d) return <Loader />;
  return (
    <Stack>
      <Text size="sm" c="dimmed">
        Los cambios crean una versión nueva de la regla o del catálogo con su traza (usuario, fecha, valor anterior y nuevo). Lo ya homologado no
        cambia hasta «Re-ejecutar homologación».
      </Text>
      <ScrollArea>
        <Table withTableBorder striped miw={800} data-testid="reglas">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Regla</Table.Th>
              <Table.Th>Nombre</Table.Th>
              <Table.Th ta="right">Orden</Table.Th>
              <Table.Th>Activa</Table.Th>
              <Table.Th>Parámetros</Table.Th>
              <Table.Th ta="right">Versión</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {d.reglas.map((r) => (
              <Table.Tr key={r.id} data-testid={`regla-${r.id}`}>
                <Table.Td fw={600}>{r.id}</Table.Td>
                <Table.Td>{r.nombre}</Table.Td>
                <Table.Td ta="right">{r.orden}</Table.Td>
                <Table.Td>
                  {r.activa ? 'Sí' : 'No'}
                  {r.obligatoria ? ' (obligatoria)' : ''}
                </Table.Td>
                <Table.Td>
                  {Object.entries(r.parametros)
                    .map(([k, v]) => `${k} = ${v}`)
                    .join(' · ') || '—'}
                </Table.Td>
                <Table.Td ta="right">{r.version}</Table.Td>
                <Table.Td>
                  {puedeOperar && (
                    <Button size="compact-sm" variant="subtle" onClick={() => setEditar(r)} aria-label={`Modificar ${r.id}`}>
                      Modificar
                    </Button>
                  )}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </ScrollArea>
      <Title order={5}>Catálogos</Title>
      <Group>
        {d.catalogos.map((c) => (
          <Button key={c.tipo} variant="default" onClick={() => setCatalogo(c.tipo)} data-testid={`catalogo-${c.tipo}`}>
            {c.nombre} · v{c.version} · {c.entradas}
          </Button>
        ))}
      </Group>
      <Title order={5}>Historial de cambios</Title>
      <ScrollArea>
        <Table withTableBorder miw={800} data-testid="historial">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Fecha</Table.Th>
              <Table.Th>Cambio</Table.Th>
              <Table.Th>Responsable</Table.Th>
              <Table.Th>Antes</Table.Th>
              <Table.Th>Después</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {d.historial.map((h) => (
              <Table.Tr key={h.id}>
                <Table.Td>{fechaHora(h.fecha)}</Table.Td>
                <Table.Td>{h.descripcion}</Table.Td>
                <Table.Td>{h.responsable}</Table.Td>
                <Table.Td maw={260}>
                  <Text size="xs" lineClamp={3} style={{ overflowWrap: 'anywhere' }}>
                    {h.antes ?? '—'}
                  </Text>
                </Table.Td>
                <Table.Td maw={260}>
                  <Text size="xs" lineClamp={3} style={{ overflowWrap: 'anywhere' }}>
                    {h.despues}
                  </Text>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </ScrollArea>
      <Modal opened={editar !== null} onClose={() => setEditar(null)} title={editar ? `Modificar ${editar.id}` : ''}>
        {editar && <EditarRegla regla={editar} alTerminar={() => setEditar(null)} />}
      </Modal>
      <Modal opened={catalogo !== null} onClose={() => setCatalogo(null)} size="lg" title="Catálogo">
        {catalogo && <EditarCatalogo tipo={catalogo} puedeOperar={puedeOperar} alTerminar={() => setCatalogo(null)} />}
      </Modal>
    </Stack>
  );
}

function EditarRegla({ regla, alTerminar }: { regla: Regla; alTerminar: () => void }) {
  const qc = useQueryClient();
  const [activa, setActiva] = useState(regla.activa);
  const [orden, setOrden] = useState(String(regla.orden));
  const [parametros, setParametros] = useState<Record<string, string>>(Object.fromEntries(regla.admite.map((p) => [p, regla.parametros[p] ?? '0'])));
  const [error, setError] = useState<string | null>(null);
  const [conflicto, setConflicto] = useState(false);
  // Se envía la versión de la regla que se abrió: si otra persona la cambió entretanto, 409 y se ofrece recargar
  const guardar = useMutation({
    mutationFn: () =>
      api(`/api/homologacion/reglas/${regla.id}`, { metodo: 'PUT', cuerpo: { activa, orden: Number(orden), parametros, version: regla.version } }),
    onMutate: () => setError(null),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: CLAVE });
      alTerminar();
    },
    onError: (e) => {
      setConflicto(esConflicto(e));
      setError(mensajeError(e, 'No se pudo guardar la regla.'));
    },
  });
  const problemas = Object.entries(parametros)
    .map(([k, v]) => problemaParametro(k, v))
    .filter((x): x is string => x !== null);
  const ordenValido = /^\d+$/.test(orden) && Number(orden) >= 1 && Number(orden) <= 999;
  return (
    <Stack data-testid="editar-regla">
      <Text size="sm">{regla.nombre}</Text>
      <Switch label="Activa" checked={activa} disabled={regla.obligatoria} onChange={(e) => setActiva(e.currentTarget.checked)} />
      <TextInput label="Orden" value={orden} onChange={(e) => setOrden(e.currentTarget.value)} error={ordenValido ? null : 'Entre 1 y 999'} />
      {regla.admite.map((p) => (
        <TextInput
          key={p}
          label={NOMBRE_PARAMETRO[p] ?? p}
          value={parametros[p] ?? ''}
          onChange={(e) => setParametros({ ...parametros, [p]: e.currentTarget.value })}
          error={problemaParametro(p, parametros[p] ?? '')}
          data-testid={`parametro-${p}`}
        />
      ))}
      {error && (
        <Alert color={conflicto ? 'orange' : 'red'} data-testid={conflicto ? 'regla-conflicto' : undefined}>
          <Group justify="space-between">
            <Text size="sm">{error}</Text>
            {conflicto && (
              <Button
                size="xs"
                variant="light"
                onClick={() => {
                  qc.invalidateQueries({ queryKey: CLAVE });
                  alTerminar();
                }}
              >
                Recargar
              </Button>
            )}
          </Group>
        </Alert>
      )}
      <Group justify="flex-end">
        <Button onClick={() => guardar.mutate()} loading={guardar.isPending} disabled={problemas.length > 0 || !ordenValido || conflicto}>
          Guardar
        </Button>
      </Group>
    </Stack>
  );
}

function EditarCatalogo({ tipo, puedeOperar, alTerminar }: { tipo: string; puedeOperar: boolean; alTerminar: () => void }) {
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: [...CLAVE, 'catalogo', tipo],
    queryFn: () => api<{ tipo: string; nombre: string; version: number; entradas: EntradaCatalogo[] }>(`/api/homologacion/catalogos/${tipo}`),
  });
  const [texto, setTexto] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [conflicto, setConflicto] = useState(false);
  // Se envía la versión del catálogo que se abrió: si otra persona lo cambió entretanto, 409 y se ofrece recargar
  const guardar = useMutation({
    mutationFn: (entradas: EntradaCatalogo[]) =>
      api(`/api/homologacion/catalogos/${tipo}`, { metodo: 'PUT', cuerpo: { entradas, version: q.data?.version } }),
    onMutate: () => setError(null),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: CLAVE });
      alTerminar();
    },
    onError: (e) => {
      setConflicto(esConflicto(e));
      setError(mensajeError(e, 'No se pudo guardar el catálogo.'));
    },
  });
  const c = q.data;
  if (q.error) return <Alert color="red">{mensajeError(q.error, 'No se pudo leer el catálogo.')}</Alert>;
  if (!c) return <Loader />;
  const actual = texto ?? escribirEntradas(tipo, c.entradas);
  const leido = leerEntradas(tipo, actual);
  return (
    <Stack>
      <Text fw={600}>
        {c.nombre} · versión {c.version}
      </Text>
      <Text size="xs" c="dimmed">
        Una entrada por línea:{' '}
        {tipo === 'sal' ? 'la palabra de la sal' : tipo === 'forma' ? 'VARIANTE ⇒ FORMA CANÓNICA / FAMILIA' : 'ENTRADA ⇒ VALOR'}.
      </Text>
      <Textarea
        value={actual}
        onChange={(e) => setTexto(e.currentTarget.value)}
        autosize
        minRows={8}
        maxRows={20}
        readOnly={!puedeOperar}
        data-testid="catalogo-texto"
      />
      {leido.errores.length > 0 && <Alert color="yellow">{leido.errores.slice(0, 5).join(' · ')}</Alert>}
      {error && (
        <Alert color={conflicto ? 'orange' : 'red'} data-testid={conflicto ? 'catalogo-conflicto' : undefined}>
          <Group justify="space-between">
            <Text size="sm">{error}</Text>
            {conflicto && (
              <Button
                size="xs"
                variant="light"
                onClick={() => {
                  setConflicto(false);
                  setError(null);
                  setTexto(null);
                  qc.invalidateQueries({ queryKey: [...CLAVE, 'catalogo', tipo] });
                }}
              >
                Recargar
              </Button>
            )}
          </Group>
        </Alert>
      )}
      {puedeOperar && (
        <Group justify="flex-end">
          <Button
            disabled={leido.errores.length > 0 || texto === null || conflicto}
            loading={guardar.isPending}
            onClick={() => guardar.mutate(leido.entradas)}
          >
            Guardar versión nueva
          </Button>
        </Group>
      )}
    </Stack>
  );
}

// ---------- Fuentes oficiales ----------

function PestanaFuentes({ puedeOperar }: { puedeOperar: boolean }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: [...CLAVE, 'fuentes'], queryFn: () => api<Fuente[]>('/api/homologacion/fuentes') });
  const [cargar, setCargar] = useState<Fuente | null>(null);
  const [lista, setLista] = useState<Fuente | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [porPublicar, setPorPublicar] = useState<{ fuente: Fuente; version: VersionFuente } | null>(null);
  const publicar = useMutation({
    mutationFn: (id: number) => api<VersionFuente>(`/api/homologacion/versiones/${id}/publicar`, { metodo: 'POST' }),
    onMutate: () => {
      setError(null);
      setPorPublicar(null);
    },
    onSuccess: (v) => {
      // Los datos originales que ya estaban no se re-homologan solos (revisión A2): se avisa aquí y arriba
      notifications.show({
        color: 'green',
        message: `Versión v${v.numero} publicada. Los cargues siguientes ya la usan; re-ejecute la homologación para aplicarla a los datos existentes.`,
      });
      qc.invalidateQueries({ queryKey: CLAVE });
    },
    onError: (e) => setError(mensajeError(e, 'No se pudo publicar la versión.')),
  });
  if (q.error) return <Alert color="red">{q.error.message}</Alert>;
  if (!q.data) return <Loader />;
  return (
    <Stack>
      <Text size="sm" c="dimmed">
        Las referencias se cargan desde el archivo ya descargado (herramientas/descargar_referencias.py o la exportación de SISPRO); el sistema no
        consulta internet durante la homologación. Una versión validada solo entra en uso al publicarla.
      </Text>
      {error && <Alert color="red">{error}</Alert>}
      <ScrollArea>
        <Table withTableBorder striped miw={1000} data-testid="fuentes">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Fuente</Table.Th>
              <Table.Th>Conjunto · licencia</Table.Th>
              <Table.Th>Versión publicada</Table.Th>
              <Table.Th>Últimas versiones</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {q.data.map((f) => (
              <Table.Tr key={f.codigo} data-testid={`fuente-${f.codigo}`}>
                <Table.Td>
                  <Text size="sm" fw={600}>
                    {f.nombre}
                  </Text>
                  <Text size="xs" c="dimmed">
                    {f.codigo}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="xs">{f.dataset ?? '—'}</Text>
                  <Text size="xs" c="dimmed">
                    {f.licencia ?? 'Licencia no verificada'} · {f.atribucion ?? ''}
                  </Text>
                </Table.Td>
                <Table.Td>
                  {f.publicada ? (
                    <Stack gap={2}>
                      <Text size="sm">
                        v{f.publicada.numero} · corte {fechaCorta(f.publicada.fechaCorte)} · {cifra(f.publicada.filasValidas)} registros
                      </Text>
                      {f.publicada.ejemplo && (
                        <Badge size="xs" color="orange">
                          Datos de ejemplo
                        </Badge>
                      )}
                    </Stack>
                  ) : (
                    <Text size="sm" c="dimmed">
                      Sin versión publicada
                    </Text>
                  )}
                </Table.Td>
                <Table.Td>
                  <Stack gap={2}>
                    {f.versiones.slice(0, 4).map((v) => (
                      <Group key={v.id} gap={4} wrap="nowrap">
                        <Text size="xs">
                          v{v.numero} · {fechaCorta(v.fechaCorte)} · {v.estado}
                          {v.motivo ? ` · ${v.motivo}` : ''}
                        </Text>
                        {puedeOperar && v.estado === 'validada' && (
                          <Button
                            size="compact-xs"
                            onClick={() => setPorPublicar({ fuente: f, version: v })}
                            loading={publicar.isPending && publicar.variables === v.id}
                            disabled={publicar.isPending}
                            data-testid={`publicar-${v.id}`}
                          >
                            Publicar
                          </Button>
                        )}
                      </Group>
                    ))}
                  </Stack>
                </Table.Td>
                <Table.Td>
                  <Group gap={4}>
                    {puedeOperar && (
                      <Button size="compact-sm" variant="default" onClick={() => setCargar(f)} data-testid={`cargar-${f.codigo}`}>
                        Cargar versión
                      </Button>
                    )}
                    {f.tipo === 'lista' && (
                      <Button size="compact-sm" variant="subtle" onClick={() => setLista(f)}>
                        {puedeOperar ? 'Editar lista' : 'Ver lista'}
                      </Button>
                    )}
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </ScrollArea>
      <Modal opened={porPublicar !== null} onClose={() => setPorPublicar(null)} title="Publicar la versión">
        {porPublicar && (
          <Stack data-testid="confirmar-publicar">
            <Text size="sm">
              {porPublicar.fuente.nombre}: la versión v{porPublicar.version.numero} (corte {fechaCorta(porPublicar.version.fechaCorte)},{' '}
              {cifra(porPublicar.version.filasValidas)} registros) pasa a ser la vigente
              {porPublicar.fuente.publicada ? ` y la v${porPublicar.fuente.publicada.numero} queda «reemplazada»` : ''}. La usan los cargues y las
              re-ejecuciones siguientes.
            </Text>
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setPorPublicar(null)}>
                Cancelar
              </Button>
              <Button onClick={() => publicar.mutate(porPublicar.version.id)} data-testid="confirmar-publicar-boton">
                Publicar
              </Button>
            </Group>
          </Stack>
        )}
      </Modal>
      <Modal opened={cargar !== null} onClose={() => setCargar(null)} title={cargar ? `Cargar ${cargar.nombre}` : ''}>
        {cargar && <CargarFuente fuente={cargar} alTerminar={() => qc.invalidateQueries({ queryKey: CLAVE })} />}
      </Modal>
      <Modal opened={lista !== null} onClose={() => setLista(null)} size="lg" title={lista?.nombre ?? ''}>
        {lista && <EditarLista fuente={lista} puedeOperar={puedeOperar} alTerminar={() => setLista(null)} />}
      </Modal>
    </Stack>
  );
}

function CargarFuente({ fuente, alTerminar }: { fuente: Fuente; alTerminar: () => void }) {
  const [archivo, setArchivo] = useState<File | null>(null);
  const [fecha, setFecha] = useState('');
  const [ejemplo, setEjemplo] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [resultado, setResultado] = useState<VersionFuente | null>(null);
  const subir = useMutation({
    onMutate: () => {
      setResultado(null);
      setError(null);
    },
    mutationFn: () =>
      subirArchivos<VersionFuente>(
        `/api/homologacion/fuentes/${fuente.codigo}/versiones`,
        { fechaCorte: fecha, ejemplo: String(ejemplo) },
        archivo ? [archivo] : [],
        'archivo',
      ),
    onSuccess: (v) => {
      setResultado(v);
      setError(null);
      alTerminar();
    },
    onError: (e) => {
      setError(mensajeError(e, 'No se pudo cargar el archivo.'));
      alTerminar();
    },
  });
  const problemas = problemasCarga(archivo, fecha, hoy());
  return (
    <Stack data-testid="cargar-fuente">
      <FileInput label="Archivo descargado" placeholder="CSV, TXT, xlsx, .gz o .zip" value={archivo} onChange={setArchivo} clearable />
      <TextInput
        label="Fecha de actualización de la fuente"
        type="date"
        value={fecha}
        onChange={(e) => setFecha(e.currentTarget.value)}
        description="rowsUpdatedAt de datos.gov.co o la mayor Fecha_Actualizacion de SISPRO"
      />
      <Checkbox
        label="Datos de ejemplo (pruebas; no se publican en producción)"
        checked={ejemplo}
        onChange={(e) => setEjemplo(e.currentTarget.checked)}
      />
      {problemas.length > 0 && (archivo || fecha) && <Alert color="yellow">{problemas.join(' ')}</Alert>}
      {error && <Alert color="red">{error}</Alert>}
      {resultado && (
        <Alert color="green" title={`Versión ${resultado.numero} ${resultado.estado}`}>
          {cifra(resultado.filasLeidas)} filas leídas · {cifra(resultado.filasValidas)} registros. Revise el informe y publíquela en la tabla de
          fuentes.
          <Stack gap={0} mt={4} data-testid="informe-version">
            {Object.entries(resultado.informe).map(([k, v]) => (
              <Text key={k} size="xs" style={{ overflowWrap: 'anywhere' }}>
                {k}: {Array.isArray(v) ? (v.length === 0 ? '—' : v.join(' · ')) : String(v)}
              </Text>
            ))}
          </Stack>
        </Alert>
      )}
      <Group justify="flex-end">
        <Button onClick={() => subir.mutate()} disabled={problemas.length > 0} loading={subir.isPending}>
          Cargar y validar
        </Button>
      </Group>
    </Stack>
  );
}

function EditarLista({ fuente, puedeOperar, alTerminar }: { fuente: Fuente; puedeOperar: boolean; alTerminar: () => void }) {
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: [...CLAVE, 'lista', fuente.codigo],
    queryFn: () =>
      api<Array<{ principioActivo: string; norma: string; fechaNorma: string | null; observacion: string | null }>>(
        `/api/homologacion/fuentes/${fuente.codigo}/lista`,
      ),
  });
  const [filas, setFilas] = useState<Array<{ principioActivo: string; norma: string; fechaNorma: string; observacion: string }> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const guardar = useMutation({
    mutationFn: () =>
      api(`/api/homologacion/fuentes/${fuente.codigo}/lista`, {
        metodo: 'PUT',
        cuerpo: { filas: (filas ?? []).map((f) => ({ ...f, fechaNorma: f.fechaNorma || null, observacion: f.observacion || null })) },
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: CLAVE });
      alTerminar();
    },
    onError: (e) => setError(mensajeError(e, 'No se pudo guardar la lista.')),
  });
  if (q.error) return <Alert color="red">{mensajeError(q.error, 'No se pudo leer la lista.')}</Alert>;
  if (!q.data) return <Loader />;
  const actuales =
    filas ??
    q.data.map((f) => ({ principioActivo: f.principioActivo, norma: f.norma, fechaNorma: f.fechaNorma ?? '', observacion: f.observacion ?? '' }));
  const cambiar = (i: number, campo: string, valor: string) => setFilas(actuales.map((f, k) => (k === i ? { ...f, [campo]: valor } : f)));
  return (
    <Stack data-testid="editar-lista">
      {actuales.map((f, i) => (
        <Group key={i} align="end" wrap="wrap">
          <TextInput
            label="Principio activo"
            value={f.principioActivo}
            onChange={(e) => cambiar(i, 'principioActivo', e.currentTarget.value)}
            readOnly={!puedeOperar}
            w={180}
          />
          <TextInput label="Norma" value={f.norma} onChange={(e) => cambiar(i, 'norma', e.currentTarget.value)} readOnly={!puedeOperar} w={240} />
          <TextInput
            label="Fecha"
            type="date"
            value={f.fechaNorma}
            onChange={(e) => cambiar(i, 'fechaNorma', e.currentTarget.value)}
            readOnly={!puedeOperar}
            w={150}
          />
          {puedeOperar && (
            <Button variant="subtle" color="red" size="compact-sm" onClick={() => setFilas(actuales.filter((_, k) => k !== i))}>
              Quitar
            </Button>
          )}
        </Group>
      ))}
      {error && <Alert color="red">{error}</Alert>}
      {puedeOperar && (
        <Group justify="space-between">
          <Button variant="default" onClick={() => setFilas([...actuales, { principioActivo: '', norma: '', fechaNorma: '', observacion: '' }])}>
            Agregar principio activo
          </Button>
          <Button onClick={() => guardar.mutate()} loading={guardar.isPending} disabled={filas === null}>
            Guardar versión nueva
          </Button>
        </Group>
      )}
    </Stack>
  );
}

// ---------- Trazabilidad ----------

function PestanaTrazabilidad() {
  const q = useQuery({ queryKey: [...CLAVE, 'trazabilidad'], queryFn: () => api<Evento[]>('/api/homologacion/trazabilidad?limite=100') });
  if (q.error) return <Alert color="red">{q.error.message}</Alert>;
  if (!q.data) return <Loader />;
  return (
    <ScrollArea>
      <Table withTableBorder striped miw={900} data-testid="trazabilidad">
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Fecha y hora</Table.Th>
            <Table.Th>Usuario</Table.Th>
            <Table.Th>Acción</Table.Th>
            <Table.Th>Objeto</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {q.data.map((e, i) => (
            <Table.Tr key={i}>
              <Table.Td>{fechaHora(e.fecha)}</Table.Td>
              <Table.Td>
                {e.usuario}
                {e.perfil ? ` · ${e.perfil}` : ''}
              </Table.Td>
              <Table.Td>{e.tipo}</Table.Td>
              <Table.Td>{e.descripcion}</Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </ScrollArea>
  );
}
