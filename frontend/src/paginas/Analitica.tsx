import { Accordion, Alert, Anchor, Badge, Button, Card, Center, Drawer, Group, Loader, Select, SimpleGrid, Stack, Text, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { FiltrosAnalitica } from '../analitica/FiltrosAnalitica';
import { GraficoTabla, TACTIL, type AccionesFila } from '../analitica/GraficosAnalitica';
import { PanelAtipicos } from '../analitica/PanelAtipicos';
import { PanelCohortes } from '../analitica/PanelCohortes';
import { PanelTraza } from '../analitica/PanelTraza';
import {
  abrirReporte,
  bajarNivel,
  busquedaDe,
  conCorte,
  conTablero,
  leerEstado,
  problemaPeriodo,
  rutaDescarga,
  rutaTablero,
  type CatalogoAnalitica,
  type EstadoAnalitica,
  type Kpi,
  type ResultadoTablero,
} from '../analitica/tableros';
import { api, descargar, ErrorApi } from '../shared/api';

/** Tableros con ranking Top 20 (se puede alternar la métrica del orden). */
const CON_RANKING = ['atc', 'principio', 'medicamento', 'diagnostico'];

function TarjetaKpi({ k, alRastrear }: { k: Kpi; alRastrear?: (r: Record<string, string>, que: string, metrica?: string) => void }) {
  return (
    <Card withBorder padding="sm" data-testid={`kpi-${k.clave}`}>
      <Text size="xs" c="dimmed">
        {k.etiqueta}
      </Text>
      <Text size="xl" fw={700} style={{ fontVariantNumeric: 'tabular-nums' }}>
        {k.texto}
      </Text>
      {k.textoVariacion && (
        <Text size="xs" c={k.variacionPct === null ? 'dimmed' : k.variacionPct >= 0 ? 'green.9' : 'red.8'}>
          {k.textoVariacion}
        </Text>
      )}
      {k.nota && (
        <Text size="xs" c="dimmed">
          {k.nota}
        </Text>
      )}
      {alRastrear && k.rastreo && (
        <Button
          size="compact-xs"
          mih={TACTIL}
          variant="subtle"
          mt={4}
          onClick={() => alRastrear(k.rastreo!, k.etiqueta, k.clave)}
          aria-label={`Rastrear ${k.etiqueta}`}
        >
          Rastrear
        </Button>
      )}
    </Card>
  );
}

/**
 * «Analítica farmacéutica» (docs/especificaciones/analitica-tableros; mockup #analitica): los 12 tableros como chips, la barra de filtros
 * con selección múltiple, KPI, barras y series propias accesibles con «Ver tabla», vistas nacional/Fuerza/ESM según el alcance,
 * comparación entre periodos, descargas Excel/CSV/PDF con todos los metadatos y «Rastrear» hasta el registro de origen (solo con la
 * pantalla de trazabilidad). Información siempre agregada.
 */
export function Analitica() {
  const [busqueda, setBusqueda] = useSearchParams();
  const estado = leerEstado(busqueda.toString());
  const cambiar = (e: EstadoAnalitica, reemplazar = false) => setBusqueda(busquedaDe(e), { replace: reemplazar });
  const catalogo = useQuery({
    queryKey: ['analitica', 'catalogo'],
    queryFn: () => api<CatalogoAnalitica>('/api/analitica/catalogo'),
    staleTime: 60_000,
  });
  const problema = problemaPeriodo(estado.generales.desde, estado.generales.hasta);
  // Cohortes y atípicos (A4) tienen su propio panel con la misma barra de filtros, corte y alcance
  const panelA4 = estado.tablero === 'cohortes' || estado.tablero === 'atipicos';
  const ruta = rutaTablero(estado);
  const datos = useQuery({
    queryKey: ['analitica', 'tablero', ruta],
    queryFn: () => api<ResultadoTablero>(ruta),
    placeholderData: keepPreviousData,
    enabled: !problema && !!catalogo.data && !panelA4,
    retry: false,
  });
  const [traza, setTraza] = useState<{ rastreo: Record<string, string>; que: string; metrica?: string } | null>(null);
  // Formato en descarga (una a la vez: la exportación puede tardar minutos y cada clic repetiría la consulta)
  const [bajando, setBajando] = useState<string | null>(null);

  if (catalogo.isLoading) {
    return (
      <Center mih={200}>
        <Loader />
      </Center>
    );
  }
  if (catalogo.error || !catalogo.data) {
    return <Alert color="red">{catalogo.error instanceof ErrorApi ? catalogo.error.message : 'No se pudo abrir la analítica.'}</Alert>;
  }
  const c = catalogo.data;
  const r = datos.data;
  const acciones: AccionesFila = {
    alRastrear: c.puedeRastrear ? (rastreo, que, metrica) => setTraza({ rastreo, que, metrica }) : undefined,
    alBajar: (rastreo) => {
      const [variable, valor] = Object.entries(rastreo).find(([k]) => k === 'fuerza' || k === 'esm') ?? [];
      if (!variable || !valor) return false;
      const e = bajarNivel(estado, variable, valor, r?.niveles ?? c.niveles.map((n) => n.codigo));
      if (e) cambiar(e);
      return !!e;
    },
  };
  // La descarga y el rastreo usan el corte de lo que se ve (aunque se aplique otro cargue entretanto)
  const visto = conCorte(estado, r?.metadatos?.corteCargueId);
  const bajar = async (formato: string) => {
    setBajando(formato);
    const id = notifications.show({
      loading: true,
      autoClose: false,
      message: `Generando la descarga ${formato === 'xlsx' ? 'Excel' : formato.toUpperCase()}…`,
    });
    try {
      await descargar(rutaDescarga(visto, formato));
      notifications.update({ id, loading: false, color: 'green', autoClose: 4000, message: 'Descarga lista.' });
    } catch (e) {
      notifications.update({
        id,
        loading: false,
        color: 'red',
        autoClose: 10000,
        message: e instanceof ErrorApi ? e.message : 'No se pudo descargar el tablero.',
      });
    } finally {
      setBajando(null);
    }
  };
  const copiarEnlace = async () => {
    const enlace = `${window.location.origin}${window.location.pathname}?${busquedaDe(visto)}`;
    try {
      await navigator.clipboard.writeText(enlace);
      notifications.show({ color: 'green', autoClose: 4000, message: 'Enlace copiado con la fecha de corte fijada.' });
    } catch {
      notifications.show({ color: 'yellow', autoClose: 10000, message: enlace });
    }
  };
  const ranking = CON_RANKING.includes(estado.tablero);
  const extra = (
    <>
      {estado.tablero === 'atc' && (
        <Select
          aria-label="Nivel ATC"
          w={150}
          data={['1', '2', '3', '4', '5'].map((n) => ({ value: n, label: `ATC nivel ${n}` }))}
          value={estado.generales.nivelAtc ?? '1'}
          onChange={(v) => cambiar({ ...estado, generales: { ...estado.generales, nivelAtc: v === '1' || !v ? undefined : v } })}
          allowDeselect={false}
          data-testid="filtro-nivel-atc"
        />
      )}
      {ranking && (
        <Select
          aria-label="Ordenar el Top 20 por"
          w={210}
          data={[
            { value: 'gasto', label: 'Top 20 por gasto' },
            { value: 'unidades', label: 'Top 20 por unidades' },
            { value: 'dispensaciones', label: 'Top 20 por dispensaciones' },
            { value: 'pacientes', label: 'Top 20 por pacientes' },
          ]}
          value={estado.generales.orden ?? 'gasto'}
          onChange={(v) => cambiar({ ...estado, generales: { ...estado.generales, orden: v === 'gasto' || !v ? undefined : v } })}
          allowDeselect={false}
        />
      )}
    </>
  );

  return (
    <Stack>
      <Group justify="space-between" align="flex-start" wrap="wrap">
        <div>
          <Title order={2}>Analítica farmacéutica</Title>
          <Text c="dimmed" size="sm">
            Riesgo farmacéutico, cohortes y caracterización del gasto. Información siempre agregada, sin datos que identifiquen pacientes.
          </Text>
        </div>
        <Group gap="xs">
          {r?.metadatos && (
            <Badge variant="light" color="gray" size="lg" data-testid="corte">
              {r.metadatos.corteTexto}
            </Badge>
          )}
          {r?.metadatos && (
            <Badge variant="light" color="gray" size="lg" data-testid="tabla-maestra">
              {r.metadatos.tablaMaestra ? `Tabla maestra v${r.metadatos.tablaMaestra}` : 'Sin tabla maestra publicada'}
            </Badge>
          )}
          {['xlsx', 'csv', 'pdf'].map((f) => (
            <Button
              key={f}
              size="xs"
              mih={TACTIL}
              variant="default"
              loading={bajando === f}
              disabled={!r?.disponible || datos.isFetching || datos.isPlaceholderData || !!problema || (bajando !== null && bajando !== f)}
              onClick={() => bajar(f)}
            >
              Descargar {f === 'xlsx' ? 'Excel' : f.toUpperCase()}
            </Button>
          ))}
          <Button size="xs" mih={TACTIL} variant="subtle" disabled={!r?.metadatos} onClick={copiarEnlace}>
            Copiar enlace
          </Button>
        </Group>
      </Group>

      <Card withBorder>
        <Group gap={6} wrap="wrap" role="group" aria-label="Tableros">
          {c.tableros.map((t) => (
            <Button
              key={t.codigo}
              size="xs"
              radius="xl"
              variant={t.codigo === estado.tablero ? 'filled' : 'default'}
              aria-pressed={t.codigo === estado.tablero}
              onClick={() => cambiar(conTablero(estado, t.codigo))}
              data-testid={`chip-${t.codigo}`}
            >
              {t.titulo}
            </Button>
          ))}
        </Group>
        <Text size="xs" c="dimmed" mt="xs">
          Alcance: {c.alcance}
        </Text>
      </Card>

      <Card withBorder>
        <FiltrosAnalitica estado={estado} catalogo={c} alCambiar={cambiar} extra={extra} aplicados={r?.metadatos?.filtros} />
      </Card>

      {!c.hayCargues && (
        <Alert color="blue" title="Aún no hay cargues aplicados">
          Cuando se aplique el primer cargue del insumo del OPL aparecerán aquí los tableros.{' '}
          {c.puedeVerCargues && (
            <Anchor component={Link} to="/analitica/cargues">
              Ir a «Cargues del OPL»
            </Anchor>
          )}
        </Alert>
      )}
      {datos.error && <Alert color="red">{datos.error instanceof ErrorApi ? datos.error.message : 'No se pudo consultar el tablero.'}</Alert>}
      {datos.isLoading && (
        <Center mih={160}>
          <Loader />
        </Center>
      )}
      {panelA4 && !problema && (estado.tablero === 'cohortes' ? <PanelCohortes estado={estado} /> : <PanelAtipicos estado={estado} />)}
      {r && !panelA4 && (
        <Stack data-testid="tablero" aria-busy={datos.isFetching} style={{ opacity: problema || datos.isPlaceholderData ? 0.55 : 1 }}>
          <Title order={3}>{r.titulo}</Title>
          {r.advertencias.map((a) => (
            <Alert key={a} color="yellow" role="status">
              {a}
            </Alert>
          ))}
          {!r.disponible && r.mensaje && (
            <Alert color="blue" title={r.mensaje} data-testid="siguiente-fase">
              {r.mensaje === 'Disponible en la siguiente fase'
                ? 'Este tablero llega con las cohortes, alertas y predicción. Comparte esta misma barra de filtros, el corte y las descargas.'
                : 'No hay cifras para mostrar.'}
            </Alert>
          )}
          {r.kpis.length > 0 && (
            <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="sm">
              {r.kpis.map((k) => (
                <TarjetaKpi key={k.clave} k={k} alRastrear={acciones.alRastrear} />
              ))}
            </SimpleGrid>
          )}
          <SimpleGrid cols={{ base: 1, lg: 2 }} spacing="md">
            {r.tablas.map((t) => (
              <div key={t.id} style={{ gridColumn: t.grafico === 'calor' || t.columnas.length > 7 ? '1 / -1' : undefined, minWidth: 0 }}>
                <GraficoTabla tabla={t} acciones={acciones} />
              </div>
            ))}
          </SimpleGrid>
          {(r.observaciones.length > 0 || r.noDisponible.length > 0) && (
            <Card withBorder>
              <Title order={5} mb="xs">
                Observaciones
              </Title>
              <Stack gap={4}>
                {[...r.observaciones, ...r.noDisponible].map((o) => (
                  <Text key={o} size="sm">
                    {o}
                  </Text>
                ))}
              </Stack>
            </Card>
          )}
          {r.metadatos && (
            <Text size="xs" c="dimmed" data-testid="fuente">
              Fuente: {r.metadatos.fuente}. {r.metadatos.corteTexto}. {r.metadatos.registros} registros procesados. Periodo {r.metadatos.periodoTexto}{' '}
              · {r.metadatos.granularidad}. Huella del resultado: {r.huella?.slice(0, 16)}…
            </Text>
          )}
        </Stack>
      )}

      <Accordion variant="contained">
        <Accordion.Item value="a4">
          <Accordion.Control>Reportes del Anexo 4 (11.1.1 a 11.1.21)</Accordion.Control>
          <Accordion.Panel>
            <Stack gap={4}>
              {c.reportesA4.map((x) => (
                <Group key={x.codigo} gap="xs" wrap="nowrap" align="flex-start">
                  <Text size="sm" fw={600} w={64}>
                    {x.codigo}
                  </Text>
                  <Text size="sm" style={{ flex: 1 }}>
                    {x.nombre}
                    {x.nota ? ` · ${x.nota}` : ''}
                  </Text>
                  {x.tablero && (
                    <Button
                      size="compact-xs"
                      mih={TACTIL}
                      variant="subtle"
                      disabled={!x.disponible}
                      title={x.disponible ? undefined : (x.nota ?? 'No disponible')}
                      onClick={() => cambiar(abrirReporte(estado, x.tablero!, x.parametros))}
                    >
                      {x.disponible ? 'Abrir' : 'No disponible'}
                    </Button>
                  )}
                </Group>
              ))}
            </Stack>
          </Accordion.Panel>
        </Accordion.Item>
      </Accordion>

      <Drawer opened={!!traza} onClose={() => setTraza(null)} position="right" size="xl" title="Trazabilidad de la cifra">
        {traza && <PanelTraza key={JSON.stringify(traza.rastreo)} estado={visto} rastreo={traza.rastreo} que={traza.que} metrica={traza.metrica} />}
      </Drawer>
    </Stack>
  );
}
