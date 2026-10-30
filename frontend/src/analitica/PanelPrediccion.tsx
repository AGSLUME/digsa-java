import { Alert, Badge, Button, Card, Center, Group, Loader, Select, SimpleGrid, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { useId, useState, type FormEvent } from 'react';
import { api, descargar, ErrorApi } from '../shared/api';
import { BotonConMotivo } from '../shared/BotonConMotivo';
import { compacto, type EstadoAnalitica, type Opciones } from './tableros';
import { cifra, conteo, fecha, fechaHora } from '../shared/formato';
import { Kpi } from '../shared/Kpi';
import { TablaAdaptable } from '../shared/TablaAdaptable';
import { useValidacion } from '../shared/validacion';
import { TACTIL } from './GraficosAnalitica';
import {
  ajusteApi,
  descripcionGrafico,
  DIMENSIONES_PREDICCION,
  filasSerie,
  geometria,
  HORIZONTE_DEFECTO,
  HORIZONTES,
  METRICAS_PREDICCION,
  parametrosSerie,
  problemaAjuste,
  seleccionInicial,
  textoError,
  textoEstado,
  textoMes,
  textoOrigen,
  textoParametros,
  valorTexto,
  type DimensionPrediccion,
  type MetricaPrediccion,
  type PaginaEntrenamientos,
  type Prediccion,
  type PuntoObservado,
  type PuntoProyectado,
  type Seleccion,
} from './prediccion';

/** Botón «Ver tabla / Ver gráfico» con aria-expanded (como los gráficos de los demás tableros). */
function Alternar({ tabla, setTabla }: { tabla: boolean; setTabla: (t: boolean) => void }) {
  return (
    <Button size="compact-xs" mih={TACTIL} variant="subtle" onClick={() => setTabla(!tabla)} aria-expanded={tabla}>
      {tabla ? 'Ver gráfico' : 'Ver tabla'}
    </Button>
  );
}

/**
 * Gráfico de la proyección en SVG propio (sin librerías, como SerieTiempoSvg): línea continua observada, punto del mes parcial, línea
 * discontinua proyectada, banda del IC 95 % y línea vertical del corte; role="img" con su descripción y «Ver tabla» con la tabla
 * equivalente.
 */
function GraficoProyeccion({
  titulo,
  metrica,
  observado,
  proyeccion,
}: {
  titulo: string;
  metrica: MetricaPrediccion;
  observado: PuntoObservado[];
  proyeccion: PuntoProyectado[];
}) {
  const [verTabla, setVerTabla] = useState(false);
  const ancho = 560;
  const alto = 200;
  const m = { x: 64, y: 12 };
  const anchoUtil = ancho - m.x - 12;
  const altoUtil = alto - m.y - 28;
  const g = geometria(observado, proyeccion, anchoUtil, altoUtil, m);
  const marcas = [0, 0.25, 0.5, 0.75, 1];
  const paso = Math.max(1, Math.ceil(g.meses.length / 8));
  const xDe = (i: number) => m.x + (g.meses.length <= 1 ? anchoUtil / 2 : (anchoUtil * i) / (g.meses.length - 1));
  const filas = filasSerie(observado, proyeccion);
  return (
    <Card withBorder data-testid="grafico-prediccion">
      <Group justify="space-between" mb="xs" wrap="wrap" gap="xs">
        <Title order={3} size="h5">
          Observado y proyección mensual
        </Title>
        <Alternar tabla={verTabla} setTabla={setVerTabla} />
      </Group>
      {verTabla ? (
        <TablaAdaptable>
          <Table striped withTableBorder fz="sm" miw={560} aria-label={`${titulo}: observado y proyección`} data-testid="tabla-serie-prediccion">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Mes</Table.Th>
                <Table.Th>Tipo</Table.Th>
                <Table.Th ta="right">Valor</Table.Th>
                <Table.Th ta="right">IC 95 % inferior</Table.Th>
                <Table.Th ta="right">IC 95 % superior</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {filas.map((f) => (
                <Table.Tr key={`${f.tipo}-${f.mes}`}>
                  <Table.Td>{textoMes(f.mes)}</Table.Td>
                  <Table.Td>{f.tipo}</Table.Td>
                  <Table.Td ta="right">{valorTexto(metrica, f.valor, f.tipo === 'Proyectado')}</Table.Td>
                  <Table.Td ta="right">{f.inferior === null ? '—' : valorTexto(metrica, f.inferior, true)}</Table.Td>
                  <Table.Td ta="right">{f.superior === null ? '—' : valorTexto(metrica, f.superior, true)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </TablaAdaptable>
      ) : (
        <>
          <svg
            viewBox={`0 0 ${ancho} ${alto}`}
            role="img"
            aria-label={descripcionGrafico(titulo, observado, proyeccion, metrica)}
            style={{ width: '100%', height: 'auto' }}
          >
            <g style={{ stroke: 'var(--lume-border)' }} strokeWidth={1}>
              {marcas.map((k) => (
                <line key={k} x1={m.x} x2={m.x + anchoUtil} y1={m.y + altoUtil - altoUtil * k} y2={m.y + altoUtil - altoUtil * k} />
              ))}
            </g>
            <g fontSize={10} style={{ fill: 'var(--lume-neutral-text)' }}>
              {marcas.map((k) => (
                <text key={k} x={2} y={m.y + altoUtil - altoUtil * k + 3}>
                  {compacto(g.maximo * k)}
                </text>
              ))}
              {g.meses.map((mes, i) =>
                i % paso === 0 ? (
                  <text key={mes} x={xDe(i)} y={alto - 8} textAnchor="middle">
                    {textoMes(mes)}
                  </text>
                ) : null,
              )}
            </g>
            {g.banda && <polygon points={g.banda} style={{ fill: 'var(--lume-blue)' }} fillOpacity={0.15} stroke="none" />}
            {g.corteX !== null && (
              <g>
                <line
                  x1={g.corteX}
                  x2={g.corteX}
                  y1={m.y}
                  y2={m.y + altoUtil}
                  style={{ stroke: 'var(--lume-neutral-text)' }}
                  strokeWidth={1}
                  strokeDasharray="2 3"
                />
                <text x={g.corteX + 3} y={m.y + 9} fontSize={10} style={{ fill: 'var(--lume-neutral-text)' }}>
                  Corte
                </text>
              </g>
            )}
            {g.observado && <polyline points={g.observado} fill="none" style={{ stroke: 'var(--lume-blue)' }} strokeWidth={2.5} />}
            {g.parcial && <circle cx={g.parcial.x} cy={g.parcial.y} r={3.5} style={{ fill: 'var(--lume-blue)' }} />}
            {g.proyectado && (
              <polyline points={g.proyectado} fill="none" style={{ stroke: 'var(--lume-blue)' }} strokeWidth={2} strokeDasharray="6 4" />
            )}
          </svg>
          <Group gap="md">
            <Text size="xs" c="dimmed">
              Línea continua: observado
            </Text>
            {g.parcial && (
              <Text size="xs" c="dimmed">
                Punto: mes del corte incompleto (no entra al entrenamiento)
              </Text>
            )}
            {proyeccion.length > 0 && (
              <Text size="xs" c="dimmed">
                Línea discontinua: proyectado · banda: IC 95 %
              </Text>
            )}
          </Group>
        </>
      )}
    </Card>
  );
}

/**
 * Tablero 12 «Predictivo» (analitica-cohortes-alertas, A5): proyección mensual con IC 95 % de gasto, dispensaciones o pacientes, a nivel
 * nacional, Fuerza o ESM (según el alcance) y opcionalmente por ATC, principio activo o CIE-10 (Top 20 por gasto); metadatos del modelo
 * (método real y parámetros, evaluados con su error, periodo histórico, fecha de entrenamiento, corte, MAPE/WMAPE y notas), escenarios
 * con ajuste por %, proyectado vs observado, bitácora de entrenamientos y «Reentrenar» (Administrador, Coordinador, Analista). Sin IA.
 */
export function PanelPrediccion({ estado, niveles }: { estado: EstadoAnalitica; niveles: { codigo: string; etiqueta: string }[] }) {
  const [sel, setSel] = useState<Seleccion>(() => seleccionInicial(estado));
  const [ajusteTexto, setAjusteTexto] = useState('');
  const [ajuste, setAjuste] = useState('');
  const [pagina, setPagina] = useState(1);
  const [bajando, setBajando] = useState<string | null>(null);
  const [entrenando, setEntrenando] = useState(false);
  const cliente = useQueryClient();
  const v = useValidacion({ etiquetas: { ajuste: 'Ajuste del escenario (%)' }, testid: 'error-prediccion' });
  const idMotivo = useId();
  const nivel = sel.nivel ?? niveles[0]?.codigo ?? 'nacional';
  const elegirValor = nivel !== 'nacional' && niveles[0]?.codigo !== nivel;
  const falta = elegirValor && !sel.valor ? (nivel === 'fuerza' ? 'Elija una Fuerza.' : 'Elija un ESM.') : null;
  const ruta = parametrosSerie(estado, { ...sel, nivel }, { ajuste: ajuste ? ajusteApi(ajuste) : undefined });
  const serieSinAjuste = parametrosSerie(estado, { ...sel, nivel });
  const datos = useQuery({
    queryKey: ['analitica', 'prediccion', ruta],
    queryFn: () => api<Prediccion>(`/api/analitica/prediccion?${ruta}`),
    placeholderData: keepPreviousData,
    enabled: !falta,
    retry: false,
  });
  const entrenamientos = useQuery({
    queryKey: ['analitica', 'prediccion', 'entrenamientos', serieSinAjuste, pagina],
    queryFn: () => api<PaginaEntrenamientos>(`/api/analitica/prediccion/entrenamientos?${serieSinAjuste}&pagina=${pagina}`),
    enabled: !falta,
    retry: false,
  });
  const variableNivel = nivel === 'fuerza' ? 'fuerza' : 'esm';
  const opcionesNivel = useQuery({
    queryKey: ['analitica', 'opciones', variableNivel, estado.generales.corte ?? ''],
    queryFn: () =>
      api<Opciones>(`/api/analitica/opciones?variable=${variableNivel}${estado.generales.corte ? `&corte=${estado.generales.corte}` : ''}`),
    enabled: elegirValor,
    staleTime: 60_000,
  });
  const paramTop = new URLSearchParams();
  if (sel.dimension) paramTop.append('dimension', sel.dimension);
  if (sel.dimension === 'atc') paramTop.append('nivelAtc', sel.nivelAtc ?? '1');
  paramTop.append('nivel', nivel);
  if (sel.valor && nivel !== 'nacional') paramTop.append('valor', sel.valor);
  if (estado.generales.corte) paramTop.append('corte', estado.generales.corte);
  const top = useQuery({
    queryKey: ['analitica', 'prediccion', 'top', paramTop.toString()],
    queryFn: () => api<{ valor: string; etiqueta: string }[]>(`/api/analitica/prediccion/top?${paramTop.toString()}`),
    enabled: !!sel.dimension && !falta,
    staleTime: 60_000,
    retry: false,
  });

  const cambiar = (s: Partial<Seleccion>) => {
    setSel((x) => ({ ...x, ...s }));
    setPagina(1);
  };
  const aplicarAjuste = (e: FormEvent) => {
    e.preventDefault();
    if (!v.validar({ ajuste: problemaAjuste(ajusteTexto) })) return;
    setAjuste(ajusteTexto.trim());
  };
  const reentrenar = async () => {
    setEntrenando(true);
    v.reiniciar();
    try {
      const cuerpo: Record<string, string | number> = { metrica: sel.metrica, nivel };
      if (sel.valor && nivel !== 'nacional') cuerpo.valor = sel.valor;
      if (sel.dimension && sel.valorDimension) {
        cuerpo.dimension = sel.dimension;
        cuerpo.valorDimension = sel.valorDimension;
      }
      cuerpo.horizonte = Number(sel.horizonte ?? HORIZONTE_DEFECTO);
      if (estado.generales.corte) cuerpo.corte = Number(estado.generales.corte);
      const r = await api<{ id: number; estado: string }>('/api/analitica/prediccion/reentrenar', { metodo: 'POST', cuerpo });
      notifications.show({ color: 'green', autoClose: 5000, message: `Entrenamiento ${r.id} registrado.` });
      await cliente.invalidateQueries({ queryKey: ['analitica', 'prediccion'] });
    } catch (e) {
      v.errorServidor(e, 'No se pudo reentrenar.');
    } finally {
      setEntrenando(false);
    }
  };
  const bajar = async (formato: string) => {
    setBajando(formato);
    v.reiniciar();
    try {
      const corte = datos.data?.corte?.cargueId;
      const conCorte = estado.generales.corte || !corte ? ruta : `${ruta}&corte=${corte}`;
      await descargar(`/api/analitica/prediccion/descarga?formato=${formato}&${conCorte}`);
    } catch (e) {
      v.errorServidor(e, 'No se pudo descargar la proyección.');
    } finally {
      setBajando(null);
    }
  };

  const d = datos.data;
  const mod = d?.modelo ?? null;
  const metrica = sel.metrica;
  const motivoDescarga = !d || !mod ? 'No hay proyección para descargar.' : null;
  const primera = d?.proyeccion?.[0];

  return (
    <Stack data-testid="panel-prediccion" ref={v.ref}>
      <Group justify="space-between" wrap="wrap">
        <Title order={2}>Predictivo</Title>
        <Group gap="xs">
          {motivoDescarga && (
            <Text size="xs" c="dimmed" id={idMotivo}>
              {motivoDescarga}
            </Text>
          )}
          {['xlsx', 'csv', 'pdf'].map((f) => (
            <BotonConMotivo
              key={f}
              size="xs"
              mih={TACTIL}
              variant="default"
              loading={bajando === f}
              motivo={motivoDescarga}
              idMotivo={idMotivo}
              disabled={datos.isFetching || (bajando !== null && bajando !== f)}
              onClick={() => bajar(f)}
            >
              Descargar {f === 'xlsx' ? 'Excel' : f.toUpperCase()}
            </BotonConMotivo>
          ))}
        </Group>
      </Group>
      {d?.corte && (
        <Text size="xs" c="dimmed" data-testid="corte-prediccion">
          {d.corte.texto} · último día con datos {fecha(d.corte.fechaCorte)} · alcance {d.alcance}
        </Text>
      )}
      {v.resumen}

      <Card withBorder>
        <Group gap="sm" align="flex-end" wrap="wrap">
          <Select
            label="Serie"
            w={190}
            data={METRICAS_PREDICCION}
            value={sel.metrica}
            onChange={(x) => x && cambiar({ metrica: x as MetricaPrediccion })}
            allowDeselect={false}
            data-testid="prediccion-metrica"
          />
          <Select
            label="Vista"
            w={150}
            data={niveles.map((n) => ({ value: n.codigo, label: n.etiqueta }))}
            value={nivel}
            onChange={(x) => x && cambiar({ nivel: x, valor: undefined })}
            allowDeselect={false}
            data-testid="prediccion-nivel"
          />
          {elegirValor && (
            <Select
              label={nivel === 'fuerza' ? 'Fuerza' : 'ESM'}
              w={220}
              searchable
              data={(opcionesNivel.data?.opciones ?? []).map((o) => ({ value: o.valor, label: o.etiqueta }))}
              value={sel.valor ?? null}
              onChange={(x) => cambiar({ valor: x ?? undefined })}
              data-testid="prediccion-valor"
            />
          )}
          <Select
            label="Dimensión"
            w={190}
            data={[{ value: '', label: 'Sin dimensión' }, ...DIMENSIONES_PREDICCION]}
            value={sel.dimension ?? ''}
            onChange={(x) => cambiar({ dimension: (x || undefined) as DimensionPrediccion | undefined, valorDimension: undefined })}
            allowDeselect={false}
          />
          {sel.dimension === 'atc' && (
            <Select
              label="Nivel ATC"
              w={130}
              data={['1', '2', '3', '4', '5'].map((n) => ({ value: n, label: `Nivel ${n}` }))}
              value={sel.nivelAtc ?? '1'}
              onChange={(x) => cambiar({ nivelAtc: x ?? '1', valorDimension: undefined })}
              allowDeselect={false}
            />
          )}
          {sel.dimension && (
            <Select
              label="Valor (Top 20 por gasto)"
              w={260}
              searchable
              nothingFoundMessage={top.isLoading ? 'Cargando…' : 'Sin valores homologados en el alcance'}
              data={(top.data ?? []).map((o) => ({ value: o.valor, label: `${o.valor} · ${o.etiqueta}` }))}
              value={sel.valorDimension ?? null}
              onChange={(x) => cambiar({ valorDimension: x ?? undefined })}
            />
          )}
          <Select
            label="Horizonte"
            w={130}
            data={HORIZONTES.map((h) => ({ value: h, label: `${h} meses` }))}
            value={sel.horizonte ?? HORIZONTE_DEFECTO}
            onChange={(x) => cambiar({ horizonte: x ?? HORIZONTE_DEFECTO })}
            allowDeselect={false}
          />
        </Group>
        {sel.dimension && !sel.valorDimension && (
          <Text size="xs" c="dimmed" mt="xs">
            Elija un valor de la dimensión; mientras tanto se proyecta la serie completa de la vista.
          </Text>
        )}
      </Card>

      {falta && (
        <Alert color="blue" data-testid="prediccion-falta">
          {falta}
        </Alert>
      )}
      {datos.isLoading && (
        <Center mih={160}>
          <Loader />
        </Center>
      )}
      {datos.error && (
        <Alert color="red" data-testid="error-consulta-prediccion">
          {datos.error instanceof ErrorApi ? datos.error.message : 'No se pudo consultar la proyección.'}
        </Alert>
      )}
      {d && !d.hayCargues && (
        <Alert color="blue" title="Aún no hay cargues aplicados">
          Cuando se aplique el primer cargue del insumo del OPL aparecerá aquí la proyección.
        </Alert>
      )}

      {d && d.hayCargues && (
        <Stack aria-busy={datos.isFetching} style={{ opacity: datos.isPlaceholderData ? 0.55 : 1 }}>
          {(d.advertencias ?? []).map((a) => (
            <Alert key={a} color="yellow" role="status">
              {a}
            </Alert>
          ))}
          {mod?.estado === 'historia_insuficiente' && (
            <Alert color="yellow" title="No se proyecta" data-testid="historia-insuficiente">
              {mod.mensaje}
            </Alert>
          )}
          {mod && !mod.registrado && (
            <Text size="xs" c="dimmed" data-testid="prediccion-al-vuelo">
              Proyección calculada al consultar con el mismo algoritmo; no está registrada como entrenamiento
              {d.puedeReentrenar ? ' (use «Reentrenar» para registrarla).' : '.'}
            </Text>
          )}

          <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="sm">
            <Kpi titulo="Método elegido" valor={mod?.metodoNombre ?? '—'} detalle={textoParametros(mod?.parametros)} testid="kpi-metodo" />
            <Kpi titulo="WMAPE del backtesting" valor={textoError(mod?.wmape)} detalle={`MAPE ${textoError(mod?.mape)}`} testid="kpi-wmape" />
            <Kpi
              titulo={primera ? `Proyección ${textoMes(primera.mes)}` : 'Proyección'}
              valor={primera ? valorTexto(metrica, primera.valor, true) : '—'}
              detalle={
                primera ? `IC 95 %: ${valorTexto(metrica, primera.inferior, true)} a ${valorTexto(metrica, primera.superior, true)}` : undefined
              }
              testid="kpi-proyeccion"
            />
            <Kpi
              titulo={`Total proyectado (${mod?.horizonte ?? 6} meses)`}
              valor={d.escenarios ? valorTexto(metrica, d.escenarios.base, true) : '—'}
              detalle={d.escenarios ? undefined : 'Los pacientes distintos no se suman entre meses'}
              testid="kpi-total"
            />
          </SimpleGrid>

          <GraficoProyeccion titulo={d.serieTexto} metrica={metrica} observado={d.observado ?? []} proyeccion={d.proyeccion ?? []} />

          {mod && (
            <Card withBorder data-testid="metadatos-prediccion">
              <Title order={3} size="h5" mb="xs">
                Metadatos del modelo
              </Title>
              <TablaAdaptable>
                <Table withTableBorder fz="sm" miw={480}>
                  <Table.Tbody>
                    <Table.Tr>
                      <Table.Th scope="row">Serie</Table.Th>
                      <Table.Td>{d.serieTexto}</Table.Td>
                    </Table.Tr>
                    <Table.Tr>
                      <Table.Th scope="row">Estado</Table.Th>
                      <Table.Td>{textoEstado(mod.estado, mod.mensaje)}</Table.Td>
                    </Table.Tr>
                    <Table.Tr>
                      <Table.Th scope="row">Método y parámetros</Table.Th>
                      <Table.Td>
                        {mod.metodoNombre ?? '—'} · {textoParametros(mod.parametros)}
                      </Table.Td>
                    </Table.Tr>
                    <Table.Tr>
                      <Table.Th scope="row">Periodo histórico</Table.Th>
                      <Table.Td>
                        {mod.historiaDesde && mod.historiaHasta
                          ? `${textoMes(mod.historiaDesde)} a ${textoMes(mod.historiaHasta)} (${conteo(mod.historiaMeses)} meses)`
                          : `${conteo(mod.historiaMeses)} meses`}
                      </Table.Td>
                    </Table.Tr>
                    <Table.Tr>
                      <Table.Th scope="row">Backtesting</Table.Th>
                      <Table.Td>
                        {conteo(mod.backtestMeses)} {mod.backtestMeses === 1 ? 'mes' : 'meses'} · MAPE {textoError(mod.mape)} · WMAPE{' '}
                        {textoError(mod.wmape)}
                      </Table.Td>
                    </Table.Tr>
                    <Table.Tr>
                      <Table.Th scope="row">Fecha de entrenamiento</Table.Th>
                      <Table.Td data-testid="fecha-entrenamiento">
                        {mod.fechaEntrenamiento ? fechaHora(mod.fechaEntrenamiento) : 'Calculada al consultar'} ·{' '}
                        {textoOrigen(mod.origen, mod.usuario)}
                      </Table.Td>
                    </Table.Tr>
                    <Table.Tr>
                      <Table.Th scope="row">Corte</Table.Th>
                      <Table.Td>{d.corte?.texto}</Table.Td>
                    </Table.Tr>
                    <Table.Tr>
                      <Table.Th scope="row">Versión y huella de la serie</Table.Th>
                      <Table.Td style={{ overflowWrap: 'anywhere' }}>
                        {mod.version} · {mod.sha256 ? `${mod.sha256.slice(0, 16)}…` : '—'}
                      </Table.Td>
                    </Table.Tr>
                  </Table.Tbody>
                </Table>
              </TablaAdaptable>
              {mod.notas.length > 0 && (
                <Stack gap={2} mt="xs" data-testid="notas-prediccion">
                  {mod.notas.map((n) => (
                    <Text key={n} size="sm">
                      Nota: {n}
                    </Text>
                  ))}
                </Stack>
              )}
              {mod.evaluados.length > 0 && (
                <TablaAdaptable>
                  <Table striped withTableBorder fz="sm" miw={480} mt="sm" aria-label="Métodos evaluados en el backtesting">
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>Método evaluado</Table.Th>
                        <Table.Th>Parámetros</Table.Th>
                        <Table.Th ta="right">WMAPE</Table.Th>
                        <Table.Th ta="right">MAPE</Table.Th>
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {mod.evaluados.map((e) => (
                        <Table.Tr key={e.metodo} fw={e.elegido ? 700 : undefined}>
                          <Table.Td>
                            {e.nombre}
                            {e.elegido ? ' (elegido)' : ''}
                          </Table.Td>
                          <Table.Td>{textoParametros(e.parametros)}</Table.Td>
                          <Table.Td ta="right">{textoError(e.wmape)}</Table.Td>
                          <Table.Td ta="right">{textoError(e.mape)}</Table.Td>
                        </Table.Tr>
                      ))}
                    </Table.Tbody>
                  </Table>
                </TablaAdaptable>
              )}
            </Card>
          )}

          {(d.proyeccion ?? []).length > 0 && (
            <Card withBorder data-testid="escenarios-prediccion">
              <Title order={3} size="h5" mb="xs">
                Escenarios
              </Title>
              <form noValidate onSubmit={aplicarAjuste}>
                <Group align="flex-end" gap="xs" wrap="wrap">
                  <TextInput
                    label="Ajuste del escenario (%)"
                    description="Multiplica la proyección y su IC por (1 + %/100). No se guarda."
                    w={260}
                    value={ajusteTexto}
                    onChange={(e) => {
                      setAjusteTexto(e.currentTarget.value);
                      v.limpiar('ajuste');
                    }}
                    error={v.error('ajuste')}
                    inputMode="decimal"
                  />
                  <Button type="submit" size="sm" mih={TACTIL} variant="light">
                    Aplicar ajuste
                  </Button>
                  {ajuste && (
                    <Button
                      size="sm"
                      mih={TACTIL}
                      variant="subtle"
                      onClick={() => {
                        setAjuste('');
                        setAjusteTexto('');
                      }}
                    >
                      Quitar ajuste
                    </Button>
                  )}
                </Group>
              </form>
              <TablaAdaptable>
                <Table striped withTableBorder fz="sm" miw={560} mt="sm" aria-label="Escenarios por mes">
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>Mes</Table.Th>
                      <Table.Th ta="right">Base</Table.Th>
                      <Table.Th ta="right">Inferior (IC 95 %)</Table.Th>
                      <Table.Th ta="right">Superior (IC 95 %)</Table.Th>
                      {d.ajuste !== null && d.ajuste !== undefined && <Table.Th ta="right">Ajustado ({cifra(d.ajuste)} %)</Table.Th>}
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {(d.proyeccion ?? []).map((p) => (
                      <Table.Tr key={p.mes}>
                        <Table.Td>{textoMes(p.mes)}</Table.Td>
                        <Table.Td ta="right">{valorTexto(metrica, p.valor, true)}</Table.Td>
                        <Table.Td ta="right">{valorTexto(metrica, p.inferior, true)}</Table.Td>
                        <Table.Td ta="right">{valorTexto(metrica, p.superior, true)}</Table.Td>
                        {d.ajuste !== null && d.ajuste !== undefined && <Table.Td ta="right">{valorTexto(metrica, p.ajustado, true)}</Table.Td>}
                      </Table.Tr>
                    ))}
                    {d.escenarios && (
                      <Table.Tr fw={700}>
                        <Table.Td>Total del horizonte</Table.Td>
                        <Table.Td ta="right">{valorTexto(metrica, d.escenarios.base, true)}</Table.Td>
                        <Table.Td ta="right">{valorTexto(metrica, d.escenarios.inferior, true)}</Table.Td>
                        <Table.Td ta="right">{valorTexto(metrica, d.escenarios.superior, true)}</Table.Td>
                        {d.ajuste !== null && d.ajuste !== undefined && (
                          <Table.Td ta="right">{valorTexto(metrica, d.escenarios.ajustado, true)}</Table.Td>
                        )}
                      </Table.Tr>
                    )}
                  </Table.Tbody>
                </Table>
              </TablaAdaptable>
            </Card>
          )}

          <Card withBorder data-testid="comparacion-prediccion">
            <Title order={3} size="h5" mb="xs">
              Proyectado vs observado
            </Title>
            {(d.comparacion ?? []).length === 0 ? (
              <Text size="sm" c="dimmed">
                Aún no hay meses observados que un entrenamiento anterior haya proyectado.
              </Text>
            ) : (
              <TablaAdaptable>
                <Table striped withTableBorder fz="sm" miw={640} aria-label="Proyectado frente a observado">
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>Mes</Table.Th>
                      <Table.Th ta="right">Proyectado</Table.Th>
                      <Table.Th ta="right">Observado</Table.Th>
                      <Table.Th ta="right">Error real</Table.Th>
                      <Table.Th>Dentro del IC 95 %</Table.Th>
                      <Table.Th>Entrenamiento</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {(d.comparacion ?? []).map((x) => (
                      <Table.Tr key={x.mes}>
                        <Table.Td>{textoMes(x.mes)}</Table.Td>
                        <Table.Td ta="right">{valorTexto(metrica, x.proyectado, true)}</Table.Td>
                        <Table.Td ta="right">{valorTexto(metrica, x.observado, true)}</Table.Td>
                        <Table.Td ta="right">{textoError(x.error)}</Table.Td>
                        <Table.Td>{x.dentroIc ? 'Sí' : 'No'}</Table.Td>
                        <Table.Td>
                          N.º {x.entrenamiento} · {fechaHora(x.fechaEntrenamiento)} · {conteo(x.h)} {x.h === 1 ? 'mes' : 'meses'} adelante
                        </Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              </TablaAdaptable>
            )}
          </Card>

          <Card withBorder data-testid="entrenamientos-prediccion">
            <Group justify="space-between" mb="xs" wrap="wrap">
              <Title order={3} size="h5">
                Bitácora de entrenamientos
              </Title>
              {d.puedeReentrenar && (
                <Button size="xs" mih={TACTIL} loading={entrenando} onClick={reentrenar} data-testid="reentrenar">
                  Reentrenar
                </Button>
              )}
            </Group>
            {entrenamientos.data && entrenamientos.data.entrenamientos.length === 0 ? (
              <Text size="sm" c="dimmed">
                Esta serie aún no tiene entrenamientos registrados.
              </Text>
            ) : (
              <TablaAdaptable>
                <Table striped withTableBorder fz="xs" miw={760} aria-label="Entrenamientos de la serie">
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>N.º</Table.Th>
                      <Table.Th>Fecha</Table.Th>
                      <Table.Th>Origen</Table.Th>
                      <Table.Th>Corte</Table.Th>
                      <Table.Th>Método</Table.Th>
                      <Table.Th>Historia</Table.Th>
                      <Table.Th ta="right">WMAPE</Table.Th>
                      <Table.Th>Estado</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {(entrenamientos.data?.entrenamientos ?? []).map((x) => (
                      <Table.Tr key={x.id}>
                        <Table.Td>{x.id}</Table.Td>
                        <Table.Td>{fechaHora(x.fecha)}</Table.Td>
                        <Table.Td>{textoOrigen(x.origen, x.usuario)}</Table.Td>
                        <Table.Td>
                          Cargue {x.corte}
                          {x.fechaCorte ? ` · ${fecha(x.fechaCorte)}` : ''}
                        </Table.Td>
                        <Table.Td>{x.metodoNombre ?? '—'}</Table.Td>
                        <Table.Td>{conteo(x.historiaMeses)} meses</Table.Td>
                        <Table.Td ta="right">{textoError(x.wmape)}</Table.Td>
                        <Table.Td>
                          <Badge color={x.estado === 'ok' ? 'green' : x.estado === 'error' ? 'red' : 'yellow'} variant="light">
                            {textoEstado(x.estado, x.mensaje)}
                          </Badge>
                        </Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              </TablaAdaptable>
            )}
            {entrenamientos.data && entrenamientos.data.total > entrenamientos.data.porPagina && (
              <Group justify="space-between" mt="xs">
                <Text size="xs" c="dimmed">
                  Página {pagina} de {Math.ceil(entrenamientos.data.total / entrenamientos.data.porPagina)} · {conteo(entrenamientos.data.total)}{' '}
                  entrenamientos
                </Text>
                <Group gap="xs">
                  <Button size="compact-xs" mih={TACTIL} variant="default" disabled={pagina <= 1} onClick={() => setPagina(pagina - 1)}>
                    Anterior
                  </Button>
                  <Button
                    size="compact-xs"
                    mih={TACTIL}
                    variant="default"
                    disabled={pagina * entrenamientos.data.porPagina >= entrenamientos.data.total}
                    onClick={() => setPagina(pagina + 1)}
                  >
                    Siguiente
                  </Button>
                </Group>
              </Group>
            )}
          </Card>

          {d.supresion && (
            <Text size="xs" c="dimmed">
              Solo cifras agregadas, sin datos que identifiquen pacientes. Supresión de celdas pequeñas: {d.supresion}
            </Text>
          )}
        </Stack>
      )}
    </Stack>
  );
}
