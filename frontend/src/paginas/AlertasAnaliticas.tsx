import {
  Alert,
  Anchor,
  Badge,
  Button,
  Card,
  Center,
  Checkbox,
  Drawer,
  FileInput,
  Group,
  Loader,
  NumberInput,
  Pagination,
  Select,
  Stack,
  Table,
  Tabs,
  Text,
  Textarea,
  TextInput,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useId, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { TACTIL } from '../analitica/GraficosAnalitica';
import {
  COLOR_SEVERIDAD,
  consultaConsola,
  filtrosDeUrl,
  intervaloEjecuciones,
  NOMBRE_ESTADO,
  noEvaluados,
  problemaNota,
  rutaTrazaAlerta,
  siguientes,
  type CatalogoAlertas,
  type DetalleAlerta,
  type EstadoAlerta,
  type FiltroConsola,
  type ListaAlertas,
  type ParametroAlerta,
} from '../analitica/riesgo';
import { api, descargar, ErrorApi, subirArchivos } from '../shared/api';
import { BotonConMotivo } from '../shared/BotonConMotivo';
import { EncabezadoPagina } from '../shared/EncabezadoPagina';
import { conteo } from '../shared/formato';
import { TablaAdaptable } from '../shared/TablaAdaptable';
import { useValidacion } from '../shared/validacion';

const TITULO = 'Alertas analíticas';
const FUENTE = 'Anexo 5 · 14.7';

/** Detalle de una alerta: contexto, análisis técnico (P4), registros de origen y validación humana. */
function DetalleAlertaVista({ id }: { id: number }) {
  const cliente = useQueryClient();
  const d = useQuery({ queryKey: ['analitica', 'alertas', 'detalle', id], queryFn: () => api<DetalleAlerta>(`/api/analitica/alertas/${id}`) });
  const [a, setA] = useState<EstadoAlerta | null>(null);
  const [nota, setNota] = useState('');
  const [enviando, setEnviando] = useState(false);
  const v = useValidacion({ etiquetas: { estado: 'Pasar a', nota: 'Nota' }, testid: 'error-estado-alerta' });
  const idMotivo = useId();
  if (d.isLoading) return <Loader />;
  if (d.error || !d.data) return <Alert color="red">{d.error instanceof ErrorApi ? d.error.message : 'No se pudo abrir la alerta.'}</Alert>;
  const x = d.data;
  const problema = a ? problemaNota(a, nota) : null;
  const motivo = !a ? 'Elija el nuevo estado en «Pasar a».' : problema;
  const cambiar = async () => {
    if (!a) return;
    setEnviando(true);
    v.reiniciar();
    try {
      await api(`/api/analitica/alertas/${id}/estado`, { cuerpo: { estado: a, nota } });
      notifications.show({ color: 'green', message: `Alerta ${id}: ${NOMBRE_ESTADO[a]}.` });
      setA(null);
      setNota('');
      await cliente.invalidateQueries({ queryKey: ['analitica', 'alertas'] });
    } catch (e) {
      v.errorServidor(e, 'No se pudo cambiar el estado.');
    } finally {
      setEnviando(false);
    }
  };
  return (
    <Stack data-testid="detalle-alerta">
      <Group gap="xs">
        <Badge color={COLOR_SEVERIDAD[x.severidad]}>Severidad {x.severidad}</Badge>
        <Badge variant="light">{NOMBRE_ESTADO[x.estado]}</Badge>
        <Badge variant="outline">Parámetros v{x.parametrosVersion}</Badge>
      </Group>
      <TablaAdaptable>
        <Table fz="sm" withTableBorder>
          <Table.Tbody>
            {[
              ['Tipo', x.tipoNombre],
              ['Nivel', x.nivelTexto],
              ['Dimensión', `${x.dimensionTexto} · ${x.valorTexto}`],
              ['Periodo', x.periodo],
              ['Umbral', x.umbralTexto],
              ['Valor observado', x.observadoTexto],
              ['Referencia', x.referenciaTexto],
              ['Registros', x.registrosTexto],
              ['Pacientes (agregado)', x.pacientesTexto],
              ['Gasto involucrado', x.gastoTexto],
              ['Corte', `nació en el cargue ${x.corte}; cifras del cargue ${x.corteCifras} (su última evaluación)`],
              ['Generada', x.generada],
              [
                'Aviso por correo',
                x.aviso === 'pendiente'
                  ? 'Pendiente: el aviso falló y se reintenta'
                  : x.aviso === 'enviado'
                    ? 'Enviado'
                    : x.aviso === 'agrupado'
                      ? 'En el resumen de la ejecución (periodo anterior)'
                      : 'No aplica',
              ],
            ].map(([k, v]) => (
              <Table.Tr key={k}>
                <Table.Th w={180}>{k}</Table.Th>
                <Table.Td>{v}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </TablaAdaptable>
      <Card withBorder padding="sm">
        <Title order={3} size="h5">
          Análisis técnico
        </Title>
        <Text size="sm" mt={4}>
          <b>Causa:</b> {x.causa}
        </Text>
        <Text size="sm" mt={4}>
          <b>Valoración del riesgo o impacto:</b> {x.valoracion}
        </Text>
        <Text size="sm" mt={4}>
          <b>Recomendación:</b> {x.recomendacion}
        </Text>
        {x.fuenteReferencia && (
          <Text size="xs" c="dimmed" mt={4}>
            Fuente de referencia: {x.fuenteReferencia}
          </Text>
        )}
      </Card>
      {x.puedeRastrear ? (
        <Anchor component={Link} to={rutaTrazaAlerta(x.seleccion)} data-testid="registros-origen">
          Ver los registros de origen en la trazabilidad
        </Anchor>
      ) : (
        <Text size="xs" c="dimmed">
          Los registros de origen se consultan en «Trazabilidad de cifras» (perfiles autorizados).
        </Text>
      )}
      {x.puedeValidar && siguientes(x.estado).length > 0 && (
        <Card withBorder padding="sm" ref={v.ref}>
          <Title order={3} size="h5">
            Validación humana
          </Title>
          <Group align="flex-end" mt="xs">
            <Select
              label="Pasar a"
              w={200}
              data={siguientes(x.estado).map((s) => ({ value: s, label: NOMBRE_ESTADO[s] }))}
              value={a}
              onChange={(x) => {
                setA(x as EstadoAlerta | null);
                v.limpiar('estado');
              }}
              error={v.error('estado')}
              data-testid="nuevo-estado"
            />
          </Group>
          <Textarea
            label="Nota"
            description="Obligatoria para validar o descartar (mínimo 10 caracteres). No escriba nombres, documentos ni otros datos personales."
            value={nota}
            onChange={(e) => {
              setNota(e.currentTarget.value);
              v.limpiar('nota');
            }}
            autosize
            minRows={2}
            mt="xs"
            error={(a && nota ? problema : null) ?? v.error('nota')}
            data-testid="nota-estado"
          />
          {v.resumen}
          <Group mt="xs" gap="sm">
            <BotonConMotivo motivo={motivo} idMotivo={idMotivo} loading={enviando} onClick={cambiar} data-testid="guardar-estado">
              Guardar
            </BotonConMotivo>
            {motivo && (
              <Text size="sm" c="dimmed" id={idMotivo}>
                {motivo}
              </Text>
            )}
          </Group>
        </Card>
      )}
      {x.historial.length > 0 && (
        <Stack gap={2}>
          <Title order={3} size="h6">
            Historial
          </Title>
          {x.historial.map((h, i) => (
            <Text key={i} size="xs">
              {h.fecha} · {h.responsable}: {NOMBRE_ESTADO[h.de as EstadoAlerta] ?? h.de} → {NOMBRE_ESTADO[h.a as EstadoAlerta] ?? h.a}
              {h.nota ? ` · ${h.nota}` : ''}
            </Text>
          ))}
        </Stack>
      )}
    </Stack>
  );
}

/**
 * Filtro de texto que se aplica al salir del campo o con Enter (no en cada tecla: cada consulta va a la API y a la bitácora).
 */
function FiltroTexto({ etiqueta, ancho, valor, alAplicar }: { etiqueta: string; ancho: number; valor: string; alAplicar: (v: string) => void }) {
  const [texto, setTexto] = useState(valor);
  const aplicar = () => {
    if (texto !== valor) alAplicar(texto);
  };
  return (
    <TextInput
      label={etiqueta}
      w={ancho}
      value={texto}
      onChange={(e) => setTexto(e.currentTarget.value.toUpperCase())}
      onBlur={aplicar}
      onKeyDown={(e) => {
        if (e.key === 'Enter') aplicar();
      }}
    />
  );
}

/** Consola: filtros (también desde la dirección, p. ej. la cohorte), lista por prioridad con contexto, resumen por tipo y descarga. */
function Consola({ catalogo }: { catalogo: CatalogoAlertas }) {
  const [busqueda, setBusqueda] = useSearchParams();
  const [filtros, setFiltros] = useState<Partial<Record<FiltroConsola, string>>>(() => filtrosDeUrl(busqueda));
  const [pagina, setPagina] = useState(1);
  const [abierta, setAbierta] = useState<number | null>(null);
  const q = consultaConsola(filtros, pagina);
  const lista = useQuery({ queryKey: ['analitica', 'alertas', 'lista', q], queryFn: () => api<ListaAlertas>(`/api/analitica/alertas?${q}`) });
  // El error de una descarga queda fijo junto a los filtros (Anexo 5 num. 2.4)
  const v = useValidacion({ testid: 'error-descarga-alertas' });
  const poner = (k: FiltroConsola, v: string | null) => {
    const f = { ...filtros, [k]: v ?? '' };
    setFiltros(f);
    setPagina(1);
    setBusqueda(new URLSearchParams(consultaConsola(f)), { replace: true });
  };
  const bajar = async (formato: string) => {
    v.reiniciar();
    try {
      await descargar(`/api/analitica/alertas/descarga?${consultaConsola(filtros)}&formato=${formato}`);
    } catch (e) {
      v.errorServidor(e, 'No se pudo descargar.');
    }
  };
  const l = lista.data;
  return (
    <Stack>
      <Card withBorder ref={v.ref}>
        <Group align="flex-end" wrap="wrap" gap="xs">
          <Select
            label="Tipo"
            w={240}
            clearable
            data={catalogo.tipos.map((t) => ({ value: t.codigo, label: t.nombre }))}
            value={filtros.tipo ?? null}
            onChange={(v) => poner('tipo', v)}
            data-testid="filtro-tipo"
          />
          <Select
            label="Severidad"
            w={130}
            clearable
            data={['alta', 'media', 'baja']}
            value={filtros.severidad ?? null}
            onChange={(v) => poner('severidad', v)}
          />
          <Select
            label="Estado"
            w={150}
            clearable
            data={Object.entries(NOMBRE_ESTADO).map(([value, label]) => ({ value, label }))}
            value={filtros.estado ?? null}
            onChange={(v) => poner('estado', v)}
          />
          <Select label="Nivel" w={130} clearable data={catalogo.niveles} value={filtros.nivel ?? null} onChange={(v) => poner('nivel', v)} />
          <Select
            label="Cohorte"
            w={200}
            clearable
            data={catalogo.cohortes.map((c) => ({ value: String(c.id), label: c.nombre }))}
            value={filtros.cohorte ?? null}
            onChange={(v) => poner('cohorte', v)}
            data-testid="filtro-cohorte"
          />
          <FiltroTexto etiqueta="Fuerza" ancho={90} valor={filtros.fuerza ?? ''} alAplicar={(v) => poner('fuerza', v)} />
          <FiltroTexto etiqueta="ESM" ancho={110} valor={filtros.esm ?? ''} alAplicar={(v) => poner('esm', v)} />
          <FiltroTexto etiqueta="ATC" ancho={90} valor={filtros.atc ?? ''} alAplicar={(v) => poner('atc', v)} />
          <TextInput label="Desde" type="date" w={150} value={filtros.desde ?? ''} onChange={(e) => poner('desde', e.currentTarget.value)} />
          <TextInput label="Hasta" type="date" w={150} value={filtros.hasta ?? ''} onChange={(e) => poner('hasta', e.currentTarget.value)} />
          {['xlsx', 'csv', 'pdf'].map((f) => (
            <Button key={f} size="xs" mih={TACTIL} variant="default" onClick={() => bajar(f)}>
              {f === 'xlsx' ? 'Excel' : f.toUpperCase()}
            </Button>
          ))}
        </Group>
        {v.resumen}
      </Card>
      {lista.error && <Alert color="red">{lista.error instanceof ErrorApi ? lista.error.message : 'No se pudo consultar la consola.'}</Alert>}
      {lista.isLoading && <Loader />}
      {l && (
        <>
          <Text size="sm" c="dimmed">
            {conteo(l.total)} alertas · ordenadas por prioridad (severidad y gasto involucrado)
          </Text>
          <TablaAdaptable>
            <Table striped withTableBorder fz="xs" miw={980} aria-label="Alertas tempranas" data-testid="tabla-alertas">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Severidad</Table.Th>
                  <Table.Th>Alerta</Table.Th>
                  <Table.Th>Nivel</Table.Th>
                  <Table.Th>Dimensión</Table.Th>
                  <Table.Th>Periodo</Table.Th>
                  <Table.Th>Umbral</Table.Th>
                  <Table.Th>Observado</Table.Th>
                  <Table.Th>Registros</Table.Th>
                  <Table.Th>Pacientes</Table.Th>
                  <Table.Th>Estado</Table.Th>
                  <Table.Th />
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {l.alertas.map((a) => (
                  <Table.Tr key={a.id} data-testid={`alerta-${a.tipo}`}>
                    <Table.Td>
                      <Badge color={COLOR_SEVERIDAD[a.severidad]}>{a.severidad}</Badge>
                    </Table.Td>
                    <Table.Td>{a.tipoNombre}</Table.Td>
                    <Table.Td>{a.nivelTexto}</Table.Td>
                    <Table.Td>
                      {a.dimensionTexto} · {a.valorTexto}
                    </Table.Td>
                    <Table.Td>{a.periodo}</Table.Td>
                    <Table.Td>{a.umbralTexto}</Table.Td>
                    <Table.Td>{a.observadoTexto}</Table.Td>
                    <Table.Td>{a.registrosTexto}</Table.Td>
                    <Table.Td>{a.pacientesTexto}</Table.Td>
                    <Table.Td>{NOMBRE_ESTADO[a.estado]}</Table.Td>
                    <Table.Td>
                      <Button size="compact-xs" mih={TACTIL} variant="subtle" onClick={() => setAbierta(a.id)} aria-label={`Abrir la alerta ${a.id}`}>
                        Abrir
                      </Button>
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </TablaAdaptable>
          {l.paginas > 1 && <Pagination total={l.paginas} value={l.pagina} onChange={setPagina} />}
          <Card withBorder>
            <Title order={2} size="h5" mb="xs">
              Resumen por tipo
            </Title>
            {l.resumen.aviso && (
              <Alert color="yellow" mb="xs">
                {l.resumen.aviso}
              </Alert>
            )}
            <TablaAdaptable>
              <Table fz="xs" aria-label="Resumen por tipo">
                <Table.Tbody>
                  {l.resumen.tipos.map((t) => (
                    <Table.Tr key={t.tipo}>
                      <Table.Td>{t.nombre}</Table.Td>
                      <Table.Td>
                        {t.evaluacion === 'evaluado'
                          ? `${conteo(t.alta)} alta · ${conteo(t.media)} media · ${conteo(t.baja)} baja`
                          : (t.motivo ?? t.evaluacion.replace('_', ' '))}
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </TablaAdaptable>
            {noEvaluados(l.resumen.tipos).length > 0 && (
              <Text size="xs" c="dimmed" mt="xs">
                Un tipo sin su fuente o su campo no calla: se informa como «no evaluado» con el motivo.
              </Text>
            )}
          </Card>
        </>
      )}
      <Drawer opened={abierta !== null} onClose={() => setAbierta(null)} position="right" size="xl" title={abierta ? `Alerta ${abierta}` : ''}>
        {abierta !== null && <DetalleAlertaVista id={abierta} />}
      </Drawer>
    </Stack>
  );
}

/** Ejecuciones del motor y «Ejecutar ahora». */
function Ejecuciones({ catalogo }: { catalogo: CatalogoAlertas }) {
  const cliente = useQueryClient();
  const e = useQuery({
    queryKey: ['analitica', 'alertas', 'ejecuciones'],
    queryFn: () =>
      api<
        {
          id: number;
          origen: string;
          corte: number;
          inicio: string;
          estado: string;
          nuevas: number;
          actualizadas: number;
          avisos: number;
          duracionMs: number | null;
          usuario: string;
          error: string | null;
          porTipo: Record<string, { estado: string; motivo?: string }>;
        }[]
      >('/api/analitica/alertas/ejecuciones'),
    // Solo mientras haya una corrida en curso, y nunca en bucle sobre un error
    refetchInterval: (q) => intervaloEjecuciones(q.state.data, !!q.state.error),
  });
  const v = useValidacion({ testid: 'error-ejecutar' });
  const ejecutar = async () => {
    v.reiniciar();
    try {
      await api('/api/analitica/alertas/ejecutar', { metodo: 'POST', cuerpo: {} });
      notifications.show({ color: 'green', message: 'Motor en ejecución sobre el corte vigente.' });
      await cliente.invalidateQueries({ queryKey: ['analitica', 'alertas'] });
    } catch (x) {
      v.errorServidor(x, 'No se pudo ejecutar el motor.');
    }
  };
  return (
    <Stack ref={v.ref}>
      {catalogo.puedeEjecutar && (
        <Group>
          <Button onClick={ejecutar} data-testid="ejecutar-motor">
            Ejecutar ahora
          </Button>
          <Text size="xs" c="dimmed">
            El motor corre solo al aplicar cada cargue; aquí se reejecuta con los parámetros vigentes (no duplica alertas).
          </Text>
        </Group>
      )}
      {v.resumen}
      {e.error && <Alert color="red">{e.error instanceof ErrorApi ? e.error.message : 'No se pudieron consultar las ejecuciones.'}</Alert>}
      {e.isLoading && <Loader />}
      <TablaAdaptable>
        <Table fz="xs" withTableBorder miw={820} aria-label="Ejecuciones del motor">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>N.º</Table.Th>
              <Table.Th>Origen</Table.Th>
              <Table.Th>Corte</Table.Th>
              <Table.Th>Inicio</Table.Th>
              <Table.Th>Estado</Table.Th>
              <Table.Th>Nuevas</Table.Th>
              <Table.Th>Reevaluadas</Table.Th>
              <Table.Th>Avisos</Table.Th>
              <Table.Th>Duración</Table.Th>
              <Table.Th>Fallos</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(e.data ?? []).map((x) => {
              const fallidos = Object.entries(x.porTipo ?? {})
                .filter(([, v]) => v.estado === 'error')
                .map(([k, v]) => `${k}: ${v.motivo ?? 'error'}`);
              return (
                <Table.Tr key={x.id}>
                  <Table.Td>{x.id}</Table.Td>
                  <Table.Td>
                    {x.origen} · {x.usuario}
                  </Table.Td>
                  <Table.Td>{x.corte}</Table.Td>
                  <Table.Td>{x.inicio}</Table.Td>
                  <Table.Td>{x.estado}</Table.Td>
                  <Table.Td>{conteo(x.nuevas)}</Table.Td>
                  <Table.Td>{conteo(x.actualizadas)}</Table.Td>
                  <Table.Td>{conteo(x.avisos)}</Table.Td>
                  <Table.Td>{x.duracionMs === null ? '—' : `${conteo(x.duracionMs)} ms`}</Table.Td>
                  <Table.Td>{[x.error, ...fallidos].filter((s) => s).join(' · ') || '—'}</Table.Td>
                </Table.Tr>
              );
            })}
          </Table.Tbody>
        </Table>
      </TablaAdaptable>
    </Stack>
  );
}

/** Parámetros de un tipo: umbrales de las bandas, niveles, ventana, activo y plantilla (versión nueva con traza). */
function EditorParametro({ p, puede }: { p: ParametroAlerta; puede: boolean }) {
  const cliente = useQueryClient();
  const [v, setV] = useState(p.parametros);
  const [historial, setHistorial] = useState(false);
  const h = useQuery({
    queryKey: ['analitica', 'alertas', 'historial', p.tipo],
    queryFn: () =>
      api<{ version: number; antes: Record<string, unknown> | null; despues: Record<string, unknown>; responsable: string; fecha: string }[]>(
        `/api/analitica/alertas/parametros/${p.tipo}/historial`,
      ),
    enabled: historial,
  });
  const val = useValidacion({
    etiquetas: {
      baja: `Baja (${p.unidad})`,
      media: 'Media',
      alta: 'Alta',
      ventana: 'Ventana (meses)',
      niveles: 'Niveles',
      plantilla: 'Plantilla de recomendación',
    },
    testid: `error-parametro-${p.tipo}`,
  });
  const guardar = async () => {
    val.reiniciar();
    try {
      const r = await api<{ version: number; cambios: boolean }>(`/api/analitica/alertas/parametros/${p.tipo}`, { metodo: 'PUT', cuerpo: v });
      notifications.show({
        color: 'green',
        message: r.cambios ? `Versión ${r.version} guardada. «Ejecutar ahora» la aplica al corte vigente.` : 'Sin cambios.',
      });
      await cliente.invalidateQueries({ queryKey: ['analitica', 'alertas'] });
    } catch (e) {
      val.errorServidor(e, 'No se pudieron guardar los parámetros.');
    }
  };
  /** Cambia un parámetro y quita la marca del servidor sobre ese campo. */
  const poner = (campo: string, cambio: Partial<typeof v>) => {
    setV({ ...v, ...cambio });
    val.limpiar(campo);
  };
  return (
    <Card withBorder padding="sm" data-testid={`parametro-${p.tipo}`} ref={val.ref}>
      <Group justify="space-between">
        <Text fw={600}>{p.nombre}</Text>
        <Text size="xs" c="dimmed">
          v{p.version} · {p.actualizado} · {p.actualizadoPor}
        </Text>
      </Group>
      <Group align="flex-end" wrap="wrap" gap="xs" mt="xs">
        <Checkbox label="Activo" checked={v.activo} disabled={!puede} onChange={(e) => setV({ ...v, activo: e.currentTarget.checked })} />
        <TextInput
          label={`Baja (${p.unidad})`}
          w={110}
          value={v.baja}
          disabled={!puede}
          onChange={(e) => poner('baja', { baja: e.currentTarget.value })}
          error={val.error('baja')}
          data-testid={`umbral-baja-${p.tipo}`}
        />
        <TextInput
          label="Media"
          w={100}
          value={v.media}
          disabled={!puede}
          onChange={(e) => poner('media', { media: e.currentTarget.value })}
          error={val.error('media')}
        />
        <TextInput
          label="Alta"
          w={100}
          value={v.alta ?? ''}
          disabled={!puede}
          onChange={(e) => poner('alta', { alta: e.currentTarget.value || null })}
          error={val.error('alta')}
        />
        <NumberInput
          label="Ventana (meses)"
          w={120}
          min={1}
          max={24}
          value={v.ventana}
          disabled={!puede}
          onChange={(x) => poner('ventana', { ventana: Number(x) || 1 })}
          error={val.error('ventana')}
        />
        <TextInput
          label="Niveles"
          description={`Admitidos: ${p.nivelesAdmitidos.join(', ')}`}
          w={220}
          value={v.niveles.join(', ')}
          disabled={!puede}
          onChange={(e) =>
            poner('niveles', {
              niveles: e.currentTarget.value
                .split(',')
                .map((s) => s.trim())
                .filter((s) => s),
            })
          }
          error={val.error('niveles')}
        />
      </Group>
      <Textarea
        label="Plantilla de recomendación"
        value={v.plantilla}
        disabled={!puede}
        autosize
        minRows={2}
        mt="xs"
        onChange={(e) => poner('plantilla', { plantilla: e.currentTarget.value })}
        error={val.error('plantilla')}
      />
      {val.resumen}
      <Group mt="xs">
        {puede && (
          <Button size="xs" onClick={guardar} data-testid={`guardar-parametro-${p.tipo}`}>
            Guardar versión
          </Button>
        )}
        <Button size="xs" variant="subtle" onClick={() => setHistorial(!historial)}>
          {historial ? 'Ocultar historial' : 'Historial'}
        </Button>
      </Group>
      {historial &&
        (h.data ?? []).map((x) => (
          <Text key={x.version} size="xs" data-testid="historial-parametro">
            v{x.version} · {x.fecha} · {x.responsable}: baja {String(x.antes?.baja ?? '—')} → {String(x.despues.baja)}, media{' '}
            {String(x.antes?.media ?? '—')} → {String(x.despues.media)}, alta {String(x.antes?.alta ?? '—')} → {String(x.despues.alta)}
          </Text>
        ))}
    </Card>
  );
}

function Parametros({ catalogo }: { catalogo: CatalogoAlertas }) {
  const p = useQuery({
    queryKey: ['analitica', 'alertas', 'parametros'],
    queryFn: () => api<ParametroAlerta[]>('/api/analitica/alertas/parametros'),
  });
  if (p.isLoading) return <Loader />;
  if (p.error) {
    // Un 403 o un 500 no es «no hay parámetros»: se dice
    return <Alert color="red">{p.error instanceof ErrorApi ? p.error.message : 'No se pudieron consultar los parámetros.'}</Alert>;
  }
  return (
    <Stack>
      {!catalogo.puedeParametrizar && <Alert color="blue">Su perfil consulta los parámetros; los editan el Administrador y el Analista.</Alert>}
      {(p.data ?? []).map((x) => (
        <EditorParametro key={`${x.tipo}-${x.version}`} p={x} puede={catalogo.puedeParametrizar} />
      ))}
    </Stack>
  );
}

/** Referencias cargables (interacciones, indicaciones, huérfanas): fuente, versión, filas y si son datos de ejemplo. */
function Referencias({ catalogo }: { catalogo: CatalogoAlertas }) {
  const cliente = useQueryClient();
  const r = useQuery({
    queryKey: ['analitica', 'alertas', 'referencias'],
    queryFn: () =>
      api<{ id: number; lista: string; version: number; fuente: string; filas: number; ficticio: boolean; vigente: boolean; cargada: string }[]>(
        '/api/analitica/alertas/referencias',
      ),
  });
  const [lista, setLista] = useState<string | null>('interacciones');
  const [fuente, setFuente] = useState('');
  const [archivo, setArchivo] = useState<File | null>(null);
  const [ficticio, setFicticio] = useState(false);
  const v = useValidacion({ etiquetas: { fuente: 'Fuente y edición', archivo: 'Archivo CSV' }, testid: 'error-referencia' });
  const idMotivo = useId();
  const motivo = !archivo ? 'Seleccione el archivo CSV.' : fuente.trim().length < 3 ? 'Escriba la fuente y su edición (mínimo 3 caracteres).' : null;
  const cargar = async () => {
    if (!archivo || !lista) return;
    v.reiniciar();
    try {
      await subirArchivos(`/api/analitica/alertas/referencias/${lista}`, { fuente, ficticio: String(ficticio) }, [archivo], 'archivo');
      notifications.show({ color: 'green', message: 'Referencia cargada; se usa en la próxima ejecución del motor.' });
      await cliente.invalidateQueries({ queryKey: ['analitica', 'alertas', 'referencias'] });
    } catch (e) {
      v.errorServidor(e, 'No se pudo cargar la referencia.');
    }
  };
  return (
    <Stack>
      {catalogo.puedeParametrizar && (
        <Card withBorder ref={v.ref}>
          <Group align="flex-end" wrap="wrap">
            <Select
              label="Lista"
              w={180}
              data={['interacciones', 'indicaciones', 'huerfanas']}
              value={lista}
              onChange={setLista}
              allowDeselect={false}
            />
            <TextInput
              label="Fuente y edición"
              w={320}
              value={fuente}
              onChange={(e) => {
                setFuente(e.currentTarget.value);
                v.limpiar('fuente');
              }}
              error={v.error('fuente')}
            />
            <FileInput
              label="Archivo CSV"
              w={240}
              value={archivo}
              onChange={(f) => {
                setArchivo(f);
                v.limpiar('archivo');
              }}
              error={v.error('archivo')}
              accept=".csv,text/csv"
            />
            <Checkbox label="Datos de ejemplo (ficticios)" checked={ficticio} onChange={(e) => setFicticio(e.currentTarget.checked)} />
            <BotonConMotivo motivo={motivo} idMotivo={idMotivo} onClick={cargar}>
              Cargar versión
            </BotonConMotivo>
          </Group>
          {motivo && (
            <Text size="sm" c="dimmed" mt="xs" id={idMotivo}>
              {motivo}
            </Text>
          )}
          {v.resumen}
          <Text size="xs" c="dimmed" mt="xs">
            interacciones: principio1;principio2;nota · indicaciones: ATC;CIE-10;nota · huerfanas: CIE-10;nota. Sin fuente, el tipo queda «no
            evaluado».
          </Text>
        </Card>
      )}
      {r.error && <Alert color="red">{r.error instanceof ErrorApi ? r.error.message : 'No se pudieron consultar las referencias.'}</Alert>}
      <TablaAdaptable>
        <Table fz="xs" withTableBorder aria-label="Referencias cargadas">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Lista</Table.Th>
              <Table.Th>Versión</Table.Th>
              <Table.Th>Fuente</Table.Th>
              <Table.Th>Filas</Table.Th>
              <Table.Th>Ejemplo</Table.Th>
              <Table.Th>Vigente</Table.Th>
              <Table.Th>Cargada</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(r.data ?? []).map((x) => (
              <Table.Tr key={x.id}>
                <Table.Td>{x.lista}</Table.Td>
                <Table.Td>v{x.version}</Table.Td>
                <Table.Td>{x.fuente}</Table.Td>
                <Table.Td>{conteo(x.filas)} filas</Table.Td>
                <Table.Td>{x.ficticio ? 'Datos de ejemplo' : ''}</Table.Td>
                <Table.Td>{x.vigente ? 'Vigente' : ''}</Table.Td>
                <Table.Td>{x.cargada}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </TablaAdaptable>
    </Stack>
  );
}

/**
 * «Alertas analíticas» (Pantallas.ANALITICA_ALERTAS; docs/especificaciones/analitica-cohortes-alertas; A5 14.7): consola de alertas tempranas
 * con su contexto obligatorio, análisis técnico y validación humana; ejecuciones del motor; parámetros con traza; referencias cargables.
 * Solo perfiles autorizados (el ESM no entra). Siempre agregado.
 */
export function AlertasAnaliticas() {
  const catalogo = useQuery({
    queryKey: ['analitica', 'alertas', 'catalogo'],
    queryFn: () => api<CatalogoAlertas>('/api/analitica/alertas/catalogo'),
  });
  if (catalogo.isLoading || catalogo.error || !catalogo.data) {
    return (
      <Stack>
        <EncabezadoPagina titulo={TITULO} fuente={FUENTE} />
        {catalogo.isLoading ? (
          <Center mih={200}>
            <Loader />
          </Center>
        ) : (
          <Alert color="red">{catalogo.error instanceof ErrorApi ? catalogo.error.message : 'No se pudo abrir la consola de alertas.'}</Alert>
        )}
      </Stack>
    );
  }
  const c = catalogo.data;
  return (
    <Stack>
      <EncabezadoPagina
        titulo={TITULO}
        descripcion="Alertas tempranas del motor determinista: contexto, análisis técnico y validación humana. Información agregada, sin datos que identifiquen pacientes."
        fuente={FUENTE}
      />
      <Tabs defaultValue="consola" keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="consola">Consola</Tabs.Tab>
          <Tabs.Tab value="ejecuciones">Ejecuciones</Tabs.Tab>
          <Tabs.Tab value="parametros">Parámetros</Tabs.Tab>
          <Tabs.Tab value="referencias">Referencias</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="consola" pt="md">
          <Consola catalogo={c} />
        </Tabs.Panel>
        <Tabs.Panel value="ejecuciones" pt="md">
          <Ejecuciones catalogo={c} />
        </Tabs.Panel>
        <Tabs.Panel value="parametros" pt="md">
          <Parametros catalogo={c} />
        </Tabs.Panel>
        <Tabs.Panel value="referencias" pt="md">
          <Referencias catalogo={c} />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
