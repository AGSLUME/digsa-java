import {
  Alert,
  Badge,
  Button,
  Card,
  Grid,
  Group,
  Loader,
  MultiSelect,
  NavLink,
  Pagination,
  ScrollArea,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Tabs,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useMemo, useState } from 'react';
import { api, ErrorApi } from '../shared/api';
import { descargarReporte } from '../shared/descargaReporte';
import { CLAVE_DESCARGAS, DescargasDelDia, seguirDescarga } from './DescargasDelDia';
import { TiemposReportes } from './TiemposReportes';
import { VerificarReporte } from './VerificarReporte';
import {
  type Catalogo,
  celda,
  conservarValores,
  cuerpoEnvio,
  type DefReporte,
  type Eleccion,
  FILAS_POR_PAGINA,
  filtrarCatalogo,
  FORMATOS,
  type Opcion,
  parametros,
  type Resultado,
  totalPaginas,
} from '../shared/reportes';

function hoy(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** Línea del encabezado 2.9: etiqueta y valor; las largas (código, huella) ocupan la fila entera y se parten donde haga falta. */
function Dato({ etiqueta, valor, largo = false }: { etiqueta: string; valor: string; largo?: boolean }) {
  return (
    <Text size="sm" style={largo ? { gridColumn: '1 / -1', overflowWrap: 'anywhere' } : undefined}>
      <Text span c="dimmed">
        {etiqueta}:
      </Text>{' '}
      <Text span ff={largo ? 'monospace' : undefined}>
        {valor}
      </Text>
    </Text>
  );
}

/**
 * Módulo de reportes (Anexo 5, 2.7 a 2.12): catálogo por perfil con buscador, filtros de una, varias o todas las opciones, periodo con
 * presets y agrupación, encabezado con título, periodo, fecha, usuario, establecimiento, filtros, fuente y página, descarga en Excel,
 * CSV, PDF o TXT y envío por correo.
 */
export function Reportes() {
  const catalogo = useQuery({ queryKey: ['reportes', 'catalogo'], queryFn: () => api<Catalogo>('/api/reportes/catalogo') });
  const [buscar, setBuscar] = useState('');
  const [elegido, setElegido] = useState<string | null>(null);
  const [e, setE] = useState<Eleccion>({ desde: `${new Date().getFullYear()}-01-01`, hasta: hoy(), agrupacion: '', valores: {} });
  const [aplicado, setAplicado] = useState<{ def: DefReporte; e: Eleccion } | null>(null);
  const [pagina, setPagina] = useState(1);
  const [para, setPara] = useState('');
  const [errorEnvio, setErrorEnvio] = useState<string | null>(null);
  const [formatoEnvio, setFormatoEnvio] = useState('xlsx');
  const [descargando, setDescargando] = useState<string | null>(null);
  const [pestana, setPestana] = useState('generar');

  const todos = useMemo(() => (catalogo.data?.grupos ?? []).flatMap((g) => g.reportes), [catalogo.data]);
  const def = todos.find((r) => r.id === elegido) ?? todos[0] ?? null;
  const grupos = useMemo(() => filtrarCatalogo(catalogo.data?.grupos ?? [], buscar), [catalogo.data, buscar]);

  const opciones = useQuery({
    queryKey: ['reportes', 'opciones', def?.id],
    queryFn: () => api<Record<string, Opcion[]>>(`/api/reportes/${def!.id}/opciones`),
    enabled: def !== null && def.filtros.some((f) => f.tipo === 'lista'),
  });
  const datosFiltros = useMemo(() => {
    const m: Record<string, { value: string; label: string }[]> = {};
    for (const [k, l] of Object.entries(opciones.data ?? {})) m[k] = l.map((o) => ({ value: o.valor, label: o.etiqueta }));
    return m;
  }, [opciones.data]);

  const consulta = aplicado ? `/api/reportes/${aplicado.def.id}?${parametros(aplicado.def, aplicado.e).toString()}` : null;
  const reporte = useQuery({
    queryKey: ['reportes', consulta],
    queryFn: () => api<Resultado>(consulta!),
    enabled: consulta !== null,
  });

  // Descarga y envío usan siempre los filtros del reporte que se está viendo (aplicado), no los que se están editando
  const enviar = useMutation({
    mutationFn: (a: { def: DefReporte; e: Eleccion }) =>
      api<{ mensaje: string; rechazados?: string[] }>(`/api/reportes/${a.def.id}/enviar`, { cuerpo: cuerpoEnvio(a.def, a.e, para, formatoEnvio) }),
    onMutate: () => setErrorEnvio(null),
    // Si el servidor de correo rechazó a algunos destinatarios, el aviso es amarillo y no se cierra solo
    onSuccess: (r) =>
      notifications.show({
        color: r.rechazados?.length ? 'yellow' : 'green',
        autoClose: r.rechazados?.length ? false : undefined,
        message: r.mensaje,
      }),
    onError: (err) => {
      const mensaje = err instanceof ErrorApi ? err.message : 'No se pudo enviar el reporte.';
      setErrorEnvio(mensaje);
      notifications.show({ color: 'red', message: mensaje });
    },
  });

  const clienteConsultas = useQueryClient();
  const bajar = async (formato: string) => {
    if (!aplicado || descargando) return;
    setDescargando(formato);
    try {
      const res = await descargarReporte(
        `/api/reportes/${aplicado.def.id}/exportar?formato=${formato}&${parametros(aplicado.def, aplicado.e).toString()}`,
      );
      if (res.diferido) {
        // Sigue en segundo plano (2.7): aparece en «Descargas del día», que avisa cuando esté listo o si falla
        seguirDescarga(res.codigo);
        notifications.show({ color: 'blue', message: res.mensaje });
      }
    } catch (err) {
      notifications.show({ color: 'red', message: err instanceof ErrorApi ? err.message : 'No se pudo descargar el reporte.' });
    } finally {
      setDescargando(null);
      // También si falló: la solicitud quedó registrada (fallida) en las descargas del día
      void clienteConsultas.invalidateQueries({ queryKey: CLAVE_DESCARGAS });
    }
  };

  const elegir = (id: string) => {
    const nuevo = todos.find((r) => r.id === id);
    if (!nuevo) return;
    setElegido(id);
    setE((x) => ({
      ...x,
      agrupacion: nuevo.agrupaciones.some((a) => a.clave === x.agrupacion) ? x.agrupacion : '',
      valores: conservarValores(nuevo, x.valores),
    }));
  };

  const generar = () => {
    if (!def) return;
    setPagina(1);
    const nuevo = { def, e: { ...e, valores: { ...e.valores } } };
    // Con los mismos filtros la clave de la consulta no cambia: se vuelve a pedir para traer las cifras de ahora
    if (aplicado && `/api/reportes/${def.id}?${parametros(def, nuevo.e).toString()}` === consulta) void reporte.refetch();
    setAplicado(nuevo);
  };

  if (catalogo.isLoading) return <Loader />;
  if (catalogo.error) return <Alert color="red">{catalogo.error.message}</Alert>;
  const cat = catalogo.data!;
  if (!def) return <Alert color="yellow">No hay reportes disponibles para su perfil.</Alert>;

  const r = reporte.data;
  const paginas = r ? totalPaginas(r.filas.length) : 1;
  const filasPagina = r ? r.filas.slice((pagina - 1) * FILAS_POR_PAGINA, pagina * FILAS_POR_PAGINA) : [];
  const otrosFiltros = r ? Object.entries(r.filtros).filter(([k]) => k !== 'Periodo' && k !== 'Agrupado por') : [];
  const faltaFecha = def.periodo !== 'ninguno' && (!e.desde || !e.hasta);

  return (
    <Stack>
      <Title order={3}>Reportes</Title>
      <Tabs value={pestana} onChange={(v) => setPestana(v ?? 'generar')}>
        <Tabs.List>
          <Tabs.Tab value="generar">Generar</Tabs.Tab>
          <Tabs.Tab value="descargas">Descargas del día</Tabs.Tab>
          <Tabs.Tab value="verificar">Verificar reporte</Tabs.Tab>
          {catalogo.data?.verTiempos && <Tabs.Tab value="tiempos">Tiempos</Tabs.Tab>}
        </Tabs.List>
        {catalogo.data?.verTiempos && (
          <Tabs.Panel value="tiempos" pt="md">
            {pestana === 'tiempos' && <TiemposReportes />}
          </Tabs.Panel>
        )}
        {/* Las pestañas quedan montadas: «Descargas del día» sigue consultando y avisa aunque se esté en otra */}
        <Tabs.Panel value="descargas" pt="md">
          <DescargasDelDia />
        </Tabs.Panel>
        <Tabs.Panel value="verificar" pt="md">
          <VerificarReporte />
        </Tabs.Panel>
        <Tabs.Panel value="generar" pt="md">
          <Grid>
            <Grid.Col span={{ base: 12, md: 3 }}>
              <Card withBorder data-testid="catalogo-reportes">
                <Title order={5} mb="xs">
                  Catálogo
                </Title>
                <TextInput
                  placeholder="Buscar reporte"
                  aria-label="Buscar reporte"
                  value={buscar}
                  onChange={(x) => setBuscar(x.currentTarget.value)}
                />
                {grupos.length === 0 && (
                  <Text size="sm" c="dimmed" mt="sm">
                    Ningún reporte coincide con la búsqueda.
                  </Text>
                )}
                {grupos.map((g) => (
                  <Stack key={g.clave} gap={0} mt="sm">
                    <Text size="xs" fw={700} c="dimmed" tt="uppercase">
                      {g.nombre}
                    </Text>
                    {g.reportes.map((x) => (
                      <NavLink
                        key={x.id}
                        component="button"
                        type="button"
                        label={x.titulo}
                        active={x.id === def.id}
                        aria-current={x.id === def.id ? 'true' : undefined}
                        onClick={() => elegir(x.id)}
                        data-testid={`reporte-${x.id}`}
                      />
                    ))}
                  </Stack>
                ))}
              </Card>
            </Grid.Col>
            <Grid.Col span={{ base: 12, md: 9 }}>
              <Stack>
                <Card
                  withBorder
                  data-testid="filtros-reporte"
                  component="form"
                  onSubmit={(ev: FormEvent) => {
                    ev.preventDefault();
                    if (!faltaFecha) generar();
                  }}
                >
                  <Group justify="space-between" align="flex-start">
                    <div>
                      <Title order={4}>{def.titulo}</Title>
                      <Text size="sm" c="dimmed" data-testid="descripcion-reporte">
                        {def.descripcion}
                      </Text>
                    </div>
                    <Badge variant="light" color={def.clase === 'general' ? 'blue' : 'grape'}>
                      {def.clase === 'general' ? 'General (agregado)' : 'Operativo (detalle)'}
                    </Badge>
                  </Group>
                  <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} mt="sm">
                    {def.periodo !== 'ninguno' && (
                      <>
                        <Select
                          label="Periodo"
                          placeholder="Personalizado"
                          data={cat.presets.map((p) => ({ value: p.clave, label: p.nombre }))}
                          value={
                            e.preset && cat.presets.some((p) => p.clave === e.preset && p.desde === e.desde && p.hasta === e.hasta) ? e.preset : null
                          }
                          onChange={(v) => {
                            const p = cat.presets.find((x) => x.clave === v);
                            if (p) setE({ ...e, preset: p.clave, desde: p.desde, hasta: p.hasta });
                          }}
                        />
                        <TextInput
                          label="Desde"
                          type="date"
                          value={e.desde}
                          onChange={(x) => setE({ ...e, preset: undefined, desde: x.currentTarget.value })}
                        />
                        <TextInput
                          label="Hasta"
                          type="date"
                          value={e.hasta}
                          onChange={(x) => setE({ ...e, preset: undefined, hasta: x.currentTarget.value })}
                        />
                      </>
                    )}
                    {def.agrupaciones.length > 0 && (
                      <Select
                        label="Agrupar por"
                        placeholder="Sin agrupar"
                        clearable
                        data={def.agrupaciones.map((a) => ({ value: a.clave, label: a.nombre }))}
                        value={e.agrupacion || null}
                        onChange={(v) => setE({ ...e, agrupacion: v ?? '' })}
                      />
                    )}
                    {def.filtros.map((f) =>
                      f.tipo === 'texto' ? (
                        <TextInput
                          key={f.clave}
                          label={f.etiqueta}
                          value={e.valores[f.clave]?.[0] ?? ''}
                          onChange={(x) => setE({ ...e, valores: { ...e.valores, [f.clave]: [x.currentTarget.value] } })}
                        />
                      ) : (
                        <MultiSelect
                          key={f.clave}
                          label={f.etiqueta}
                          description={f.unSoloValor ? 'Una o todas (por ahora, una a la vez)' : 'Una, varias o todas'}
                          maxValues={f.unSoloValor ? 1 : undefined}
                          placeholder={(e.valores[f.clave]?.length ?? 0) === 0 ? 'Todas' : undefined}
                          searchable
                          clearable
                          hidePickedOptions
                          maxDropdownHeight={260}
                          limit={100}
                          disabled={opciones.isLoading}
                          rightSection={opciones.isLoading ? <Loader size="xs" /> : undefined}
                          nothingFoundMessage="Sin opciones dentro de su alcance"
                          data={datosFiltros[f.clave] ?? []}
                          value={e.valores[f.clave] ?? []}
                          onChange={(v) => setE({ ...e, valores: { ...e.valores, [f.clave]: v } })}
                          data-testid={`filtro-${f.clave}`}
                        />
                      ),
                    )}
                  </SimpleGrid>
                  {opciones.error && (
                    <Alert color="red" mt="sm">
                      No se pudieron cargar las opciones de los filtros: {opciones.error.message}
                    </Alert>
                  )}
                  <Group mt="md">
                    <Button type="submit" disabled={faltaFecha}>
                      Generar reporte
                    </Button>
                    {faltaFecha && (
                      <Text size="sm" c="red">
                        Escriba las fechas «Desde» y «Hasta» o elija un periodo.
                      </Text>
                    )}
                  </Group>
                </Card>

                {reporte.isFetching && <Loader />}
                {reporte.error && <Alert color="red">{reporte.error.message}</Alert>}
                {r && !reporte.isFetching && !reporte.isError && (
                  <Card withBorder data-testid="reporte">
                    {aplicado && aplicado.def.id !== def.id && (
                      <Alert color="blue" mb="sm" data-testid="aviso-otro-reporte">
                        Este es el resultado de «{aplicado.def.titulo}». Pulse «Generar reporte» para ver «{def.titulo}».
                      </Alert>
                    )}
                    <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="xs" data-testid="encabezado-reporte">
                      <Dato etiqueta="Título" valor={r.titulo} />
                      <Dato etiqueta="Periodo" valor={(r.periodo || 'Sin periodo') + (r.agrupacion ? ` · agrupado por ${r.agrupacion}` : '')} />
                      <Dato etiqueta="Generado" valor={r.generado} />
                      <Dato etiqueta="Usuario" valor={r.usuario} />
                      <Dato etiqueta="Establecimiento" valor={r.establecimiento} />
                      <Dato
                        etiqueta="Filtros"
                        valor={otrosFiltros.length === 0 ? 'Ninguno' : otrosFiltros.map(([k, v]) => `${k}: ${v}`).join(' · ')}
                      />
                      <Dato etiqueta="Fuente" valor={r.fuente} />
                      <Dato
                        etiqueta="Registros"
                        valor={`${r.registros}${r.truncado ? '+' : ''} · generado en ${(r.ms / 1000).toFixed(1).replace('.', ',')} s`}
                      />
                      <Dato etiqueta="Página" valor={`${pagina} de ${paginas}`} />
                      <Dato etiqueta="Código de verificación" valor={r.codigo} largo />
                      <Dato etiqueta="Huella de los datos" valor={r.huella ?? 'Sin huella: descargue el archivo para verlas todas'} largo />
                    </SimpleGrid>
                    {r.truncado && (
                      <Alert color="yellow" mt="sm" data-testid="aviso-truncado">
                        En pantalla se muestran las primeras {r.registros} filas. Descargue el archivo para verlas todas.
                      </Alert>
                    )}
                    <Group mt="sm" gap="xs">
                      {FORMATOS.map((x) => (
                        <Button
                          key={x.valor}
                          size="xs"
                          variant="light"
                          loading={descargando === x.valor}
                          disabled={descargando !== null && descargando !== x.valor}
                          onClick={() => void bajar(x.valor)}
                        >
                          Descargar {x.etiqueta}
                        </Button>
                      ))}
                    </Group>
                    <ScrollArea type="auto" mt="sm">
                      <Table striped miw={900} data-testid="tabla-reporte">
                        <Table.Thead>
                          <Table.Tr>
                            {r.columnas.map((c) => (
                              <Table.Th key={c}>{c}</Table.Th>
                            ))}
                          </Table.Tr>
                        </Table.Thead>
                        <Table.Tbody>
                          {r.filas.length === 0 && (
                            <Table.Tr>
                              <Table.Td colSpan={r.columnas.length}>Sin registros con estos filtros.</Table.Td>
                            </Table.Tr>
                          )}
                          {filasPagina.map((fila, i) => (
                            <Table.Tr key={(pagina - 1) * FILAS_POR_PAGINA + i}>
                              {fila.map((v, j) => (
                                <Table.Td key={j}>{celda(v)}</Table.Td>
                              ))}
                            </Table.Tr>
                          ))}
                        </Table.Tbody>
                      </Table>
                    </ScrollArea>
                    {paginas > 1 && (
                      <nav aria-label="Páginas del reporte">
                        <Pagination mt="sm" total={paginas} value={pagina} onChange={setPagina} />
                      </nav>
                    )}
                    <Text size="xs" c="dimmed" mt="sm">
                      Las cifras no cambian al generar el mismo reporte con los mismos filtros, sea cual sea el usuario.
                    </Text>
                    <Group mt="md" align="flex-end" wrap="wrap">
                      <TextInput
                        label="Enviar a"
                        description="Hasta 15 correos, separados por coma"
                        error={errorEnvio}
                        inputMode="email"
                        miw={280}
                        placeholder="correo@sanidad.mil.co, otro@sanidad.mil.co"
                        value={para}
                        onChange={(x) => {
                          setPara(x.currentTarget.value);
                          setErrorEnvio(null);
                        }}
                      />
                      <Select
                        label="Formato"
                        data={FORMATOS.map((x) => ({ value: x.valor, label: x.etiqueta }))}
                        value={formatoEnvio}
                        onChange={(v) => setFormatoEnvio(v ?? 'xlsx')}
                      />
                      <Button loading={enviar.isPending} disabled={!para.trim() || !aplicado} onClick={() => aplicado && enviar.mutate(aplicado)}>
                        Enviar por correo
                      </Button>
                    </Group>
                  </Card>
                )}
              </Stack>
            </Grid.Col>
          </Grid>
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
