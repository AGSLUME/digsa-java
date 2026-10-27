import { Alert, Box, Button, Card, Center, Group, Loader, ScrollArea, Select, SimpleGrid, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { api, descargar } from '../shared/api';
import { conteo, fecha } from '../shared/formato';
import { GraficaBarras } from '../shared/Barras';
import {
  conConsulta,
  consultaFiltros,
  etiquetaCategoria,
  FILTROS_VACIOS,
  filtrosConsultables,
  FORMATOS_DESCARGA,
  problemaFechas,
  textoFiltrosAplicados,
  textoPlanesVencidos,
  tonoTexto,
  type DatosDashboard,
  type FiltrosDashboard,
  type OpcionesDashboard,
} from '../shared/tablero';
import { EncabezadoPagina } from '../shared/EncabezadoPagina';
import { useValidacion } from '../shared/validacion';

function Tarjeta({
  titulo,
  valor,
  detalle,
  color,
  testid,
}: {
  titulo: string;
  valor: ReactNode;
  detalle?: ReactNode;
  color?: string;
  testid?: string;
}) {
  return (
    <Card withBorder padding="sm" data-testid={testid}>
      <Text size="xs" c="dimmed">
        {titulo}
      </Text>
      <Text size="xl" fw={700} c={tonoTexto(color)}>
        {valor}
      </Text>
      {detalle && (
        <Text size="xs" c="dimmed">
          {detalle}
        </Text>
      )}
    </Card>
  );
}

/**
 * «Dashboard» de visitas (docs/especificaciones/riesgo-tablero): indicadores, gráficas por estado, tipo, fuerza, unidad y auditor,
 * línea base de los ESM de dispensación y resultados de las visitas cerradas (por categoría con «Sin calificar» y las obligaciones,
 * por prestador y vacíos de mayor riesgo: duda 25.3), con los filtros del alcance y descarga con las mismas cifras.
 */
export function Dashboard() {
  const opciones = useQuery({ queryKey: ['dashboard', 'opciones'], queryFn: () => api<OpcionesDashboard>('/api/dashboard/opciones') });
  const [f, setF] = useState<FiltrosDashboard>(FILTROS_VACIOS);
  // Las fechas a medio escribir no se envían; con «Desde» posterior a «Hasta» no se consulta ni se descarga
  const enviados = filtrosConsultables(f);
  const consulta = consultaFiltros(enviados);
  const rangoInvalido = problemaFechas(f);
  // La clave incluye los filtros: una respuesta vieja nunca se pinta sobre la de los filtros actuales
  const datos = useQuery({
    queryKey: ['dashboard', 'datos', consulta],
    queryFn: () => api<DatosDashboard>(conConsulta('/api/dashboard', consulta)),
    placeholderData: keepPreviousData,
    enabled: !rangoInvalido,
  });
  const hayFiltros = consultaFiltros(f) !== '';
  // «Desde» queda marcado y remite al mensaje del rango, que va bajo «Hasta» (Mantine no pone aria-describedby si el error solo marca)
  const idHasta = useId();
  const desdeRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (rangoInvalido) desdeRef.current?.setAttribute('aria-describedby', idHasta + '-error');
    else desdeRef.current?.removeAttribute('aria-describedby');
  }, [rangoInvalido, idHasta]);

  // El error de una descarga queda fijo junto a los botones (Anexo 5 num. 2.4)
  const v = useValidacion({ testid: 'error-descarga-dashboard' });
  const bajar = async (formato: string) => {
    v.reiniciar();
    try {
      await descargar(`/api/dashboard/exportar?${consultaFiltros(enviados, { formato })}`);
    } catch (e) {
      v.errorServidor(e, 'No se pudo descargar el dashboard.');
    }
  };

  const o = opciones.data;
  return (
    <Stack>
      <EncabezadoPagina titulo="Dashboard" fuente="Anexo 5 · 4.5" />
      {opciones.error && <Alert color="red">{opciones.error.message}</Alert>}
      <Card withBorder data-testid="filtros-dashboard" ref={v.ref}>
        <SimpleGrid cols={{ base: 1, xs: 2, md: 3 }}>
          <TextInput
            label="Desde"
            type="date"
            ref={desdeRef}
            value={f.desde}
            error={!!rangoInvalido}
            onChange={(e) => setF({ ...f, desde: e.currentTarget.value })}
          />
          {/* El problema del rango va junto al campo (con su testid de siempre) y el resumen junto a las descargas */}
          <TextInput
            label="Hasta"
            id={idHasta}
            type="date"
            value={f.hasta}
            error={rangoInvalido}
            errorProps={{ 'data-testid': 'problema-fechas' }}
            onChange={(e) => setF({ ...f, hasta: e.currentTarget.value })}
          />
          <Select
            label="Fuerza"
            placeholder="Todas"
            clearable
            data={(o?.fuerzas ?? []).map((x) => ({ value: x.codigo, label: x.nombre }))}
            value={f.fuerza || null}
            onChange={(v) => setF({ ...f, fuerza: v ?? '' })}
          />
          <Select
            label="Tipo de visita"
            placeholder="Todos"
            clearable
            data={(o?.tipos ?? []).map((x) => ({ value: x.codigo, label: x.nombre }))}
            value={f.tipo || null}
            onChange={(v) => setF({ ...f, tipo: v ?? '' })}
          />
          {o?.auditores && (
            <Select
              label="Auditor"
              placeholder="Todos"
              clearable
              searchable
              data={o.auditores.map((x) => ({ value: String(x.id), label: x.nombre }))}
              value={f.auditor || null}
              onChange={(v) => setF({ ...f, auditor: v ?? '' })}
              nothingFoundMessage="Sin auditores con ese nombre"
            />
          )}
          <Select
            label="Unidad"
            placeholder="Todas"
            clearable
            data={o?.unidades ?? []}
            value={f.unidad || null}
            onChange={(v) => setF({ ...f, unidad: v ?? '' })}
          />
        </SimpleGrid>
        <Group mt="md" gap="xs" wrap="wrap">
          <Button variant="default" disabled={!hayFiltros} onClick={() => setF(FILTROS_VACIOS)}>
            Limpiar filtros
          </Button>
          {FORMATOS_DESCARGA.map((x) => (
            <Button key={x.valor} size="sm" variant="light" disabled={!!rangoInvalido} onClick={() => void bajar(x.valor)}>
              Descargar {x.etiqueta}
            </Button>
          ))}
          {datos.isFetching && <Loader size="sm" />}
        </Group>
        {rangoInvalido && (
          <Text size="sm" c="red" mt="sm">
            Revise el campo marcado: Hasta. Mientras «Desde» sea posterior a «Hasta» no se consulta ni se descarga.
          </Text>
        )}
        {v.resumen && <Box mt="sm">{v.resumen}</Box>}
      </Card>

      {datos.isLoading && (
        <Center py="xl">
          <Loader />
        </Center>
      )}
      {datos.error && <Alert color="red">{datos.error.message}</Alert>}
      {datos.data && (
        <Stack style={{ opacity: datos.isPlaceholderData ? 0.6 : 1 }}>
          <Contenido d={datos.data} o={o} />
        </Stack>
      )}
    </Stack>
  );
}

function Contenido({ d, o }: { d: DatosDashboard; o: OpcionesDashboard | undefined }) {
  const i = d.indicadores;
  const r = d.resultados;
  const lb = d.lineaBase;
  const filtros = textoFiltrosAplicados(d.filtros, o);
  return (
    <>
      {filtros && (
        <Text size="sm" c="dimmed" data-testid="filtros-aplicados">
          Filtros aplicados: {filtros}
        </Text>
      )}
      <SimpleGrid cols={{ base: 2, sm: 3, lg: 6 }} data-testid="indicadores-dashboard">
        <Tarjeta titulo="Visitas en el periodo" valor={conteo(i.total)} testid="ind-total" />
        <Tarjeta
          titulo="Por ejecutar"
          valor={conteo(i.porEjecutar)}
          detalle={`Programadas ${conteo(i.programadas)} · notificadas ${conteo(i.notificadas)} · reprogramadas ${conteo(i.reprogramadas)}`}
          testid="ind-por-ejecutar"
        />
        <Tarjeta titulo="En ejecución" valor={conteo(i.ejecucion)} testid="ind-ejecucion" />
        <Tarjeta titulo="Cerradas" valor={conteo(i.cerradas)} color="green" testid="ind-cerradas" />
        <Tarjeta titulo="Canceladas" valor={conteo(i.canceladas)} color="gray" testid="ind-canceladas" />
        <Tarjeta
          titulo="Con alertas críticas"
          valor={conteo(i.criticas)}
          color={i.criticas > 0 ? 'red' : undefined}
          detalle={textoPlanesVencidos(i.planesOplVencidos)}
          testid="ind-criticas"
        />
      </SimpleGrid>

      {lb && lb.total > 0 && (
        <Card withBorder data-testid="linea-base">
          <Title order={5}>Línea base de los ESM de dispensación</Title>
          <Text size="xs" c="dimmed" mb="xs">
            Sin aplicar los filtros del dashboard.
          </Text>
          <SimpleGrid cols={{ base: 2, sm: 4 }}>
            <Tarjeta titulo="Con visita inicial cerrada" valor={`${conteo(lb.cerradas)} de ${conteo(lb.total)}`} color="green" />
            <Tarjeta titulo="En curso o programadas" valor={conteo(lb.enCurso)} />
            <Tarjeta titulo="Sin programar" valor={conteo(lb.sinProgramar)} color={lb.sinProgramar > 0 ? 'orange' : undefined} />
            <Tarjeta titulo="Plazo" valor={fecha(lb.plazo)} />
          </SimpleGrid>
        </Card>
      )}

      <SimpleGrid cols={{ base: 1, md: 2 }}>
        <GraficaBarras titulo="Visitas por estado" barras={d.porEstado} testid="grafica-estado" />
        <GraficaBarras titulo="Visitas por tipo" barras={d.porTipo} testid="grafica-tipo" />
        <GraficaBarras titulo="Visitas por fuerza" barras={d.porFuerza} testid="grafica-fuerza" />
        <GraficaBarras titulo="Visitas por unidad" barras={d.porUnidad} testid="grafica-unidad" />
        {d.porAuditor && <GraficaBarras titulo="Visitas por auditor líder" barras={d.porAuditor} testid="grafica-auditor" />}
      </SimpleGrid>

      {r && (
        <Card withBorder data-testid="resultados-dashboard">
          <Title order={4}>Resultados de las visitas cerradas</Title>
          <Text size="xs" c="dimmed" mb="xs">
            Del resumen guardado al cerrar cada visita; una visita reabierta sale hasta que se cierre de nuevo.
          </Text>
          <SimpleGrid cols={{ base: 2, sm: 3, lg: 6 }}>
            <Tarjeta titulo="Cumple" valor={conteo(r.c)} color="green" />
            <Tarjeta titulo="No cumple" valor={conteo(r.nc)} color="red" />
            <Tarjeta titulo="No aplica" valor={conteo(r.na)} />
            <Tarjeta titulo="Hallazgos" valor={conteo(r.hallazgos)} />
            <Tarjeta titulo="Requieren intervención inmediata del OPL" valor={conteo(r.inmediata)} color={r.inmediata > 0 ? 'red' : undefined} />
            <Tarjeta titulo="Acciones del plan 5W2H abiertas" valor={conteo(r.planesAbiertos)} />
          </SimpleGrid>
          {r.habilitacion && (
            <>
              <Title order={5} mt="md" mb="xs">
                Habilitación
              </Title>
              <SimpleGrid cols={{ base: 1, xs: 3 }}>
                <Tarjeta titulo="Habilitado" valor={conteo(r.habilitacion.habilitado)} color="green" />
                <Tarjeta titulo="No habilitado · en trámite" valor={conteo(r.habilitacion.tramite)} color="orange" />
                <Tarjeta titulo="No habilitado sin soporte" valor={conteo(r.habilitacion.no_habilitado)} color="red" />
              </SimpleGrid>
            </>
          )}
          <TablaResultados
            titulo="Resultados por categoría"
            columna="Categoría"
            filas={(r.porCategoria ?? []).map((x) => ({
              clave: x.codigo,
              etiqueta: etiquetaCategoria(x),
              c: x.c,
              nc: x.nc,
              na: x.na,
              sc: x.pendientes,
            }))}
            testid="tabla-categorias"
          />
          <TablaResultados
            titulo="Resultados por prestador"
            columna="Prestador"
            filas={(r.porPrestador ?? []).map((x) => ({
              clave: x.prestador,
              etiqueta: x.prestador || 'Sin prestador',
              c: x.c,
              nc: x.nc,
              na: x.na,
              sc: x.pendientes,
            }))}
            testid="tabla-prestadores"
          />
          <Title order={5} mt="md" mb="xs">
            Vacíos de mayor riesgo con No cumple
          </Title>
          <Table striped data-testid="tabla-vacios">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Vacío de mayor riesgo</Table.Th>
                <Table.Th ta="right">No cumple</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {(r.vacios ?? []).length === 0 && (
                <Table.Tr>
                  <Table.Td colSpan={2}>La versión vigente del checklist no tiene vacíos de mayor riesgo.</Table.Td>
                </Table.Tr>
              )}
              {(r.vacios ?? []).map((x) => (
                <Table.Tr key={x.codigo} data-testid={'vacio-' + x.codigo}>
                  <Table.Td style={{ overflowWrap: 'anywhere' }}>{x.nombre}</Table.Td>
                  <Table.Td ta="right">{conteo(x.nc)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Card>
      )}
    </>
  );
}

function TablaResultados({
  titulo,
  columna,
  filas,
  testid,
}: {
  titulo: string;
  columna: string;
  filas: { clave: string; etiqueta: string; c: number; nc: number; na: number; sc: number }[];
  testid: string;
}) {
  return (
    <>
      <Title order={5} mt="md" mb="xs">
        {titulo}
      </Title>
      <ScrollArea type="auto">
        <Table striped miw={520} data-testid={testid}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{columna}</Table.Th>
              <Table.Th ta="right">Cumple</Table.Th>
              <Table.Th ta="right">No cumple</Table.Th>
              <Table.Th ta="right">No aplica</Table.Th>
              <Table.Th ta="right">Sin calificar</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {filas.length === 0 && (
              <Table.Tr>
                <Table.Td colSpan={5}>Sin visitas cerradas con estos filtros.</Table.Td>
              </Table.Tr>
            )}
            {filas.map((x) => (
              <Table.Tr key={x.clave} data-clave={x.clave}>
                <Table.Td>{x.etiqueta}</Table.Td>
                <Table.Td ta="right">{conteo(x.c)}</Table.Td>
                <Table.Td ta="right">{conteo(x.nc)}</Table.Td>
                <Table.Td ta="right">{conteo(x.na)}</Table.Td>
                <Table.Td ta="right">{conteo(x.sc)}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </ScrollArea>
    </>
  );
}
