import {
  Alert,
  Badge,
  Button,
  Card,
  Center,
  Checkbox,
  Grid,
  Group,
  Loader,
  ScrollArea,
  Select,
  SimpleGrid,
  Stack,
  Switch,
  Table,
  Tabs,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useMemo, useState, type ReactNode } from 'react';
import { api } from '../shared/api';
import { MapaColombia } from '../shared/MapaColombia';
import { EncabezadoPagina } from '../shared/EncabezadoPagina';
import {
  claseCoropleta,
  COLOR_COROPLETA,
  colorCoropleta,
  colorEstado,
  cortesCoropleta,
  ESTADOS_COBERTURA,
  ESTADOS_VISITA,
  MAPA_BASE,
  porcentajeCobertura,
  textoConteos,
  textoSinUbicacion,
  ubicar,
  type Capital,
  type DatosMapa,
  type Estado,
  type FilaDepartamento,
  type PuntoMapa,
  type Ubicado,
  type VisitaMapa,
} from '../shared/mapas';
import {
  conConsulta,
  consultaFiltros,
  FILTROS_VACIOS,
  filtrosConsultables,
  problemaFechas,
  textoFiltrosAplicados,
  type FiltrosDashboard,
  type OpcionesDashboard,
} from '../shared/tablero';

/** Las opciones del dashboard más las capitales departamentales del mapa político. */
type OpcionesMapas = OpcionesDashboard & { capitales?: Capital[] };

/** Cómo se pinta la coropleta: el valor de cada departamento, el color base y las clases. */
interface Coropleta {
  titulo: string;
  base: string;
  valor: (f: FilaDepartamento) => number;
  cortes: (max: number) => number[];
  minimo: number;
  sufijo: string;
}

const SIN_CAPITALES: Capital[] = [];

/** Texto de cada clase de la coropleta: «1–3», «4» o «21–40 %». */
function rangosCoropleta(cortes: number[], minimo: number, sufijo: string): string[] {
  return cortes.map((hasta, i) => {
    const desde = i === 0 ? minimo : (cortes[i - 1] ?? 0) + 1;
    return `${desde === hasta ? hasta : `${desde}–${hasta}`}${sufijo}`;
  });
}

const COROPLETA_VISITAS: Coropleta = {
  titulo: 'Visitas por departamento',
  base: COLOR_COROPLETA,
  valor: (f) => f.total,
  cortes: cortesCoropleta,
  minimo: 1,
  sufijo: '',
};
const COROPLETA_COBERTURA: Coropleta = {
  titulo: 'Puntos visitados en el periodo',
  base: COLOR_COROPLETA,
  valor: (f) => (f.total ? Math.round((100 * Number(f.visitado ?? 0)) / f.total) : 0),
  cortes: () => [20, 40, 60, 80, 100],
  minimo: 0,
  sufijo: ' %',
};

/**
 * Mapas georreferenciados (Anexo 4 §9 y §11): el mapa de visitas por departamento (un punto por visita, de color según su estado:
 * programada, en ejecución o ejecutada) y el mapa de control con la cobertura de los puntos activos en el periodo. Mismos filtros,
 * perfiles y alcance que el dashboard.
 */
export function Mapas() {
  const opciones = useQuery({
    queryKey: ['mapas', 'opciones'],
    queryFn: () => api<OpcionesMapas>('/api/mapas/opciones'),
  });
  const [f, setF] = useState<FiltrosDashboard>(FILTROS_VACIOS);
  const [pestana, setPestana] = useState<string | null>('visitas');
  const consulta = consultaFiltros(filtrosConsultables(f));
  const rangoInvalido = problemaFechas(f);
  const o = opciones.data;
  return (
    <Stack>
      <EncabezadoPagina titulo="Mapas de visitas" fuente="Anexo 4 · 9 y 11" />
      {opciones.error && <Alert color="red">{opciones.error.message}</Alert>}
      <Card withBorder data-testid="filtros-mapas">
        <SimpleGrid cols={{ base: 1, xs: 2, md: 3 }}>
          <TextInput label="Desde" type="date" value={f.desde} onChange={(e) => setF({ ...f, desde: e.currentTarget.value })} />
          <TextInput label="Hasta" type="date" value={f.hasta} onChange={(e) => setF({ ...f, hasta: e.currentTarget.value })} />
          <Select
            label="Fuerza"
            placeholder="Todas"
            clearable
            data={(o?.fuerzas ?? []).map((x) => ({
              value: x.codigo,
              label: x.nombre,
            }))}
            value={f.fuerza || null}
            onChange={(v) => setF({ ...f, fuerza: v ?? '' })}
          />
          <Select
            label="Tipo de visita"
            placeholder="Todos"
            clearable
            data={(o?.tipos ?? []).map((x) => ({
              value: x.codigo,
              label: x.nombre,
            }))}
            value={f.tipo || null}
            onChange={(v) => setF({ ...f, tipo: v ?? '' })}
          />
          {o?.auditores && (
            <Select
              label="Auditor"
              placeholder="Todos"
              clearable
              searchable
              data={o.auditores.map((x) => ({
                value: String(x.id),
                label: x.nombre,
              }))}
              value={f.auditor || null}
              onChange={(v) => setF({ ...f, auditor: v ?? '' })}
              nothingFoundMessage="Sin auditores con ese nombre"
            />
          )}
          <Select label="Unidad" placeholder="Todas" clearable data={o?.unidades ?? []} value={f.unidad || null} onChange={(v) => setF({ ...f, unidad: v ?? '' })} />
        </SimpleGrid>
        <Group mt="md">
          <Button variant="default" disabled={consultaFiltros(f) === ''} onClick={() => setF(FILTROS_VACIOS)}>
            Limpiar filtros
          </Button>
        </Group>
        {rangoInvalido && (
          <Alert color="yellow" mt="sm" data-testid="problema-fechas">
            {rangoInvalido}
          </Alert>
        )}
      </Card>

      <Tabs value={pestana} onChange={setPestana} keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="visitas">Visitas por departamento</Tabs.Tab>
          <Tabs.Tab value="control">Mapa de control: cobertura de puntos</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="visitas" pt="md">
          <MapaVisitas consulta={consulta} habilitado={!rangoInvalido} o={o} />
        </Tabs.Panel>
        <Tabs.Panel value="control" pt="md">
          <MapaControl consulta={consulta} habilitado={!rangoInvalido} o={o} />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}

function useMapa<T>(tipo: 'visitas' | 'cobertura', consulta: string, habilitado: boolean) {
  // La clave incluye los filtros: una respuesta vieja nunca se pinta sobre la de los filtros actuales
  return useQuery({
    queryKey: ['mapas', tipo, consulta],
    queryFn: () => api<DatosMapa<T>>(conConsulta(`/api/mapas/${tipo}`, consulta)),
    placeholderData: keepPreviousData,
    enabled: habilitado,
  });
}

interface PropsMapa {
  consulta: string;
  habilitado: boolean;
  o: OpcionesMapas | undefined;
}

// Fuera de los componentes: funciones estables, para que las marcas no se recalculen en cada render
const tituloVisita = (v: VisitaMapa) => `${v.punto} · ${v.municipio} · ${v.fecha} · ${v.tipo}`;
const celdasVisita = (v: VisitaMapa) => [v.fecha, v.numero ?? '—', v.punto, v.municipio, v.tipo];
const tituloPunto = (p: PuntoMapa) => `${p.nombre} · ${p.unidad}${p.modalidad ? ` (${p.modalidad})` : ''} · ${p.municipio}`;
const celdasPunto = (p: PuntoMapa) => [p.nombre, p.modalidad ? `${p.unidad} · ${p.modalidad}` : p.unidad, p.fuerza, p.municipio, p.ultimaCerrada ?? '—'];

// La clave por consulta reinicia el departamento elegido y las convenciones ocultas al cambiar los filtros
function MapaVisitas({ consulta, habilitado, o }: PropsMapa) {
  const datos = useMapa<VisitaMapa>('visitas', consulta, habilitado);
  return (
    <Vista
      key={consulta}
      datos={datos}
      o={o}
      estados={ESTADOS_VISITA}
      elementos={datos.data?.visitas ?? []}
      que="visitas"
      etiquetaMapa="Mapa de Colombia con las visitas por departamento"
      titulo={tituloVisita}
      columnas={['Fecha', 'Número', 'Punto', 'Municipio', 'Tipo', 'Estado']}
      celdas={celdasVisita}
      coropleta={COROPLETA_VISITAS}
      nota={datos.data?.truncado ? 'Hay más visitas de las que caben en el mapa: acote el periodo con «Desde» y «Hasta».' : undefined}
    />
  );
}

function MapaControl({ consulta, habilitado, o }: PropsMapa) {
  const datos = useMapa<PuntoMapa>('cobertura', consulta, habilitado);
  const pct = porcentajeCobertura(datos.data?.totales);
  return (
    <Vista
      key={consulta}
      datos={datos}
      o={o}
      estados={ESTADOS_COBERTURA}
      elementos={datos.data?.puntos ?? []}
      que="puntos"
      etiquetaMapa="Mapa de control con la cobertura de los puntos por departamento"
      titulo={tituloPunto}
      columnas={['Punto', 'Unidad', 'Fuerza', 'Municipio', 'Última visita cerrada', 'Estado']}
      celdas={celdasPunto}
      coropleta={COROPLETA_COBERTURA}
      resumen={
        <Text size="sm" data-testid="cobertura-porcentaje">
          {pct === null ? 'Sin puntos en el filtro.' : `Cobertura del periodo: ${pct} % de los puntos visitados.`}
        </Text>
      }
      nota="Puntos activos del alcance, sin los de suministro ni satélite, que se vigilan de forma remota. Sin fechas, el periodo es todo el contrato."
    />
  );
}

function Vista<T extends Ubicado>({
  datos,
  o,
  estados,
  elementos,
  que,
  etiquetaMapa,
  titulo,
  columnas,
  celdas,
  coropleta,
  resumen,
  nota,
}: {
  datos: ReturnType<typeof useMapa<T>>;
  o: OpcionesMapas | undefined;
  estados: Estado[];
  elementos: T[];
  que: 'visitas' | 'puntos';
  etiquetaMapa: string;
  titulo: (x: T) => string;
  columnas: string[];
  celdas: (x: T) => string[];
  coropleta: Coropleta;
  resumen?: ReactNode;
  nota?: string;
}) {
  const [ocultos, setOcultos] = useState<string[]>([]);
  const [departamento, setDepartamento] = useState<string | null>(null);
  const [verCoropleta, setVerCoropleta] = useState(false);
  const d = datos.data;
  const etiquetas = useMemo(() => Object.fromEntries(estados.map((e) => [e.valor, e.etiqueta])), [estados]);
  const visibles = useMemo(() => elementos.filter((x) => !ocultos.includes(x.estado)), [elementos, ocultos]);
  const marcas = useMemo(
    () =>
      ubicar(visibles).map((x) => ({
        clave: x.id,
        departamento: x.departamento,
        x: x.x,
        y: x.y,
        color: colorEstado(estados, x.estado),
        titulo: `${titulo(x)} · ${etiquetas[x.estado] ?? x.estado}`,
      })),
    [visibles, estados, etiquetas, titulo],
  );
  // Memorizado: un `[]` nuevo en cada pintada recalcularía textos y clases aunque no hubiera datos (react-hooks/exhaustive-deps)
  const filas = useMemo(() => d?.porDepartamento ?? [], [d]);
  const textos = useMemo(() => {
    const t: Record<string, string> = {};
    for (const x of filas) t[x.codigo] = `${x.nombre}: ${x.total} · ${textoConteos(estados, x)}`;
    return t;
  }, [filas, estados]);
  const clases = useMemo(() => {
    if (!verCoropleta) return null;
    const valores: Record<string, number> = Object.fromEntries(filas.map((x) => [x.codigo, coropleta.valor(x)]));
    const cortes = coropleta.cortes(Math.max(0, ...Object.values(valores)));
    const rellenos = Object.fromEntries(
      MAPA_BASE.departamentos.map((x) => [x.codigo, colorCoropleta(coropleta.base, claseCoropleta(valores[x.codigo], cortes), cortes.length)]),
    );
    return { cortes, rellenos };
  }, [verCoropleta, filas, coropleta]);
  const delDepartamento = departamento ? visibles.filter((x) => x.departamento === departamento) : [];
  const nombreDepartamento =
    filas.find((x) => x.codigo === departamento)?.nombre ?? MAPA_BASE.departamentos.find((x) => x.codigo === departamento)?.nombre ?? departamento;
  const filtros = textoFiltrosAplicados(d?.filtros, o);
  const sinUbicacion = textoSinUbicacion(d?.sinUbicacion ?? 0, que);

  if (datos.isLoading) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    );
  }
  if (datos.error) return <Alert color="red">{datos.error.message}</Alert>;
  if (!d) return null;
  return (
    <Stack style={{ opacity: datos.isPlaceholderData ? 0.6 : 1 }}>
      {filtros && (
        <Text size="sm" c="dimmed" data-testid="filtros-aplicados">
          Filtros aplicados: {filtros}
        </Text>
      )}
      <Grid>
        <Grid.Col span={{ base: 12, md: 7 }}>
          <Card withBorder padding="xs">
            <MapaColombia
              marcas={marcas}
              departamentos={textos}
              seleccionado={departamento}
              onSeleccionar={setDepartamento}
              etiqueta={etiquetaMapa}
              capitales={o?.capitales ?? SIN_CAPITALES}
              rellenos={clases?.rellenos}
            />
          </Card>
        </Grid.Col>
        <Grid.Col span={{ base: 12, md: 5 }}>
          <Stack>
            <Card withBorder data-testid="leyenda-mapa">
              <Title order={2} size="h5" mb="xs">
                Convenciones
              </Title>
              <Stack gap="xs">
                {estados.map((e) => (
                  <Checkbox
                    key={e.valor}
                    color={e.color}
                    checked={!ocultos.includes(e.valor)}
                    onChange={(ev) => {
                      const ver = ev.currentTarget.checked;
                      setOcultos((s) => (ver ? s.filter((x) => x !== e.valor) : [...s, e.valor]));
                    }}
                    label={
                      <Group gap={6}>
                        <span
                          style={{
                            width: 10,
                            height: 10,
                            borderRadius: 5,
                            background: colorEstado(estados, e.valor),
                            display: 'inline-block',
                          }}
                        />
                        <span>
                          {e.etiqueta}: <b data-testid={`total-${e.valor}`}>{d.totales[e.valor] ?? 0}</b>
                        </span>
                      </Group>
                    }
                  />
                ))}
              </Stack>
              <Group gap={6} mt="sm">
                <svg width={10} height={10} aria-hidden="true">
                  <circle cx={5} cy={5} r={3.5} fill="#fff" stroke="#0F172A" strokeWidth={1.4} />
                </svg>
                <Text size="sm">Capital departamental</Text>
              </Group>
              <Switch mt="sm" label="Ver como coropleta" checked={verCoropleta} onChange={(e) => setVerCoropleta(e.currentTarget.checked)} data-testid="ver-coropleta" />
              {clases && (
                <Stack gap={4} mt="xs" data-testid="leyenda-coropleta">
                  <Text size="xs" fw={600}>
                    {coropleta.titulo}
                  </Text>
                  {clases.cortes.length === 0 ? (
                    <Text size="xs" c="dimmed">
                      Sin {que} en el filtro.
                    </Text>
                  ) : (
                    rangosCoropleta(clases.cortes, coropleta.minimo, coropleta.sufijo).map((r, i) => (
                      <Group key={r} gap={6}>
                        <span
                          style={{
                            width: 16,
                            height: 10,
                            borderRadius: 2,
                            background: colorCoropleta(coropleta.base, i + 1, clases.cortes.length),
                            display: 'inline-block',
                          }}
                        />
                        <Text size="xs">{r}</Text>
                      </Group>
                    ))
                  )}
                  <Group gap={6}>
                    <span
                      style={{
                        width: 16,
                        height: 10,
                        borderRadius: 2,
                        background: colorCoropleta(coropleta.base, 0),
                        display: 'inline-block',
                      }}
                    />
                    <Text size="xs">Sin {que}</Text>
                  </Group>
                </Stack>
              )}
              {resumen && <div style={{ marginTop: 8 }}>{resumen}</div>}
              {sinUbicacion && (
                <Text size="xs" c="dimmed" mt="xs" data-testid="sin-ubicacion">
                  {sinUbicacion}
                </Text>
              )}
              {nota && (
                <Text size="xs" c="dimmed" mt="xs">
                  {nota}
                </Text>
              )}
            </Card>
            <Card withBorder data-testid="tabla-departamentos">
              <Title order={2} size="h5" mb="xs">
                Por departamento
              </Title>
              {filas.length === 0 ? (
                <Text size="sm" c="dimmed">
                  Sin {que} en el filtro.
                </Text>
              ) : (
                <ScrollArea.Autosize mah={320}>
                  <Table striped highlightOnHover>
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>Departamento</Table.Th>
                        {estados.map((e) => (
                          <Table.Th key={e.valor} ta="right">
                            {e.etiqueta}
                          </Table.Th>
                        ))}
                        <Table.Th ta="right">Total</Table.Th>
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {filas.map((x) => (
                        <Table.Tr
                          key={x.codigo}
                          data-departamento={x.codigo}
                          style={{ cursor: 'pointer' }}
                          bg={departamento === x.codigo ? 'var(--mantine-color-blue-light)' : undefined}
                          tabIndex={0}
                          aria-selected={departamento === x.codigo}
                          onClick={() => setDepartamento(departamento === x.codigo ? null : x.codigo)}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter' || e.key === ' ') {
                              e.preventDefault();
                              setDepartamento(departamento === x.codigo ? null : x.codigo);
                            }
                          }}
                        >
                          <Table.Td>{x.nombre}</Table.Td>
                          {estados.map((e) => (
                            <Table.Td key={e.valor} ta="right">
                              {Number(x[e.valor] ?? 0)}
                            </Table.Td>
                          ))}
                          <Table.Td ta="right">{x.total}</Table.Td>
                        </Table.Tr>
                      ))}
                    </Table.Tbody>
                  </Table>
                </ScrollArea.Autosize>
              )}
            </Card>
          </Stack>
        </Grid.Col>
      </Grid>

      {departamento && (
        <Card withBorder data-testid="detalle-departamento">
          <Group justify="space-between" mb="xs">
            <Title order={2} size="h5">
              {nombreDepartamento}: {delDepartamento.length} {que}
            </Title>
            <Button variant="subtle" size="xs" onClick={() => setDepartamento(null)}>
              Quitar selección
            </Button>
          </Group>
          <ScrollArea.Autosize mah={420}>
            <Table striped>
              <Table.Thead>
                <Table.Tr>
                  {columnas.map((c) => (
                    <Table.Th key={c}>{c}</Table.Th>
                  ))}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {delDepartamento.map((x) => (
                  <Table.Tr key={x.id}>
                    {celdas(x).map((c, i) => (
                      <Table.Td key={i}>{c}</Table.Td>
                    ))}
                    <Table.Td>
                      <Badge color={estados.find((e) => e.valor === x.estado)?.color ?? 'gray'} variant="light">
                        {etiquetas[x.estado] ?? x.estado}
                      </Badge>
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollArea.Autosize>
        </Card>
      )}
    </Stack>
  );
}
