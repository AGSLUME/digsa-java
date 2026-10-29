import {
  Accordion,
  Anchor,
  Alert,
  Badge,
  Button,
  Card,
  Center,
  Checkbox,
  Drawer,
  Group,
  Loader,
  Modal,
  NumberInput,
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
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useId, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, descargar, ErrorApi } from '../shared/api';
import { BotonConMotivo } from '../shared/BotonConMotivo';
import { conteo } from '../shared/formato';
import { TablaAdaptable } from '../shared/TablaAdaptable';
import { useValidacion } from '../shared/validacion';
import { TACTIL } from './GraficosAnalitica';
import {
  COMPOSICIONES,
  criterioAEditor,
  editorACriterio,
  filaVacia,
  filtrosIgnorados,
  problemasEditor,
  separarTarjetas,
  TIPOS_FILA,
  type Composicion,
  type DetalleCohorte,
  type Editor,
  type TarjetaCohorte,
  type Tarjetas,
  type TipoFila,
} from './riesgo';
import type { EstadoAnalitica } from './tableros';

/** Parámetros comunes de las consultas de cohortes: corte, periodo y (en nacional) la Fuerza elegida. */
function parametros(e: EstadoAnalitica, extra: Record<string, string> = {}): string {
  const p = new URLSearchParams();
  for (const k of ['corte', 'desde', 'hasta'] as const) {
    const v = e.generales[k];
    if (v) p.append(k, v);
  }
  const f = e.filtros.fuerza;
  const una = f?.[0];
  if (f?.length === 1 && una) p.append('fuerza', una);
  for (const [k, v] of Object.entries(extra)) if (v) p.append(k, v);
  return p.toString();
}

function TarjetaCohorteVista({ c, alAbrir }: { c: TarjetaCohorte; alAbrir: () => void }) {
  return (
    <Card withBorder padding="sm" data-testid={`cohorte-${c.codigo}`}>
      <Group justify="space-between" align="flex-start" wrap="nowrap">
        <Text fw={600} size="sm">
          {c.nombre}
        </Text>
        <Badge variant="light" color={c.estado === 'evaluada' ? 'blue' : 'gray'}>
          v{c.version}
        </Badge>
      </Group>
      {c.estado === 'evaluada' && !c.particion && (
        <Text size="xl" fw={700} style={{ fontVariantNumeric: 'tabular-nums' }} data-testid="conteo">
          {c.pacientesTexto}{' '}
          <Text span size="sm" c="dimmed">
            pacientes
          </Text>
        </Text>
      )}
      {c.estado === 'evaluada' && c.particion && (
        <Stack gap={2} mt={4}>
          {(c.grupos ?? []).slice(0, 6).map((g) => (
            <Group key={g.grupo} justify="space-between" wrap="nowrap">
              <Text size="xs">{g.etiqueta}</Text>
              <Text size="xs" fw={600} style={{ fontVariantNumeric: 'tabular-nums' }}>
                {g.pacientesTexto}
              </Text>
            </Group>
          ))}
          {(c.grupos?.length ?? 0) > 6 && (
            <Text size="xs" c="dimmed">
              y {(c.grupos?.length ?? 0) - 6} grupos más
            </Text>
          )}
        </Stack>
      )}
      {c.estado !== 'evaluada' && (
        <Text size="sm" c={c.estado === 'error_calculo' ? 'red' : 'dimmed'} data-testid="motivo">
          {c.motivo}
        </Text>
      )}
      {c.aviso && (
        <Text size="xs" c="orange">
          {c.aviso}
        </Text>
      )}
      <Text size="xs" mt={6} data-testid="criterio">
        Criterio: {c.criterio}
      </Text>
      {c.umbral && (
        <Text size="xs" c="dimmed">
          {c.umbral}
        </Text>
      )}
      {c.estado === 'evaluada' && (
        <Text size="xs" c="dimmed">
          {c.registros === null || c.registros === undefined
            ? 'Registros y gasto ocultos (celda pequeña)'
            : `${conteo(c.registros)} registros · gasto ${c.gasto}`}
        </Text>
      )}
      {c.nota && (
        <Text size="xs" c="dimmed">
          {c.nota}
        </Text>
      )}
      <Button size="compact-xs" mih={TACTIL} variant="subtle" mt={4} onClick={alAbrir} aria-label={`Ver detalle de ${c.nombre}`}>
        Ver serie y composición
      </Button>
    </Card>
  );
}

/** Editor sencillo de criterios (Y / O de criterios con ventana). */
function EditorCriterios({ valor, alCambiar }: { valor: Editor; alCambiar: (e: Editor) => void }) {
  const cambiarFila = (i: number, f: Partial<Editor['filas'][number]>) =>
    alCambiar({ ...valor, filas: valor.filas.map((x, k) => (k === i ? { ...x, ...f } : x)) });
  return (
    <Stack gap="xs">
      <SegmentedControl
        aria-label="Combinar los criterios con"
        data={[
          { value: 'Y', label: 'Todos (Y)' },
          { value: 'O', label: 'Alguno (O)' },
        ]}
        value={valor.op}
        onChange={(v) => alCambiar({ ...valor, op: v === 'O' ? 'O' : 'Y' })}
      />
      {valor.filas.map((f, i) => {
        const codigos = TIPOS_FILA.find((t) => t.valor === f.tipo)?.codigos;
        return (
          <Card key={i} withBorder padding="xs">
            <Group align="flex-end" wrap="wrap" gap="xs">
              <Select
                label={`Criterio ${i + 1}`}
                w={240}
                data={TIPOS_FILA.map((t) => ({ value: t.valor, label: t.etiqueta }))}
                value={f.tipo}
                onChange={(v) => v && cambiarFila(i, { ...filaVacia(v as TipoFila) })}
                allowDeselect={false}
              />
              {codigos && (
                <TextInput
                  label="Códigos (separados por coma)"
                  w={260}
                  value={f.valores}
                  onChange={(ev) => cambiarFila(i, { valores: ev.currentTarget.value })}
                  placeholder={f.tipo === 'lista' ? 'estrecho_margen, control_especial o huerfanas' : 'p. ej. E10, E11'}
                />
              )}
              {(codigos || f.tipo === 'cronicos') && (
                <NumberInput
                  label="Ventana (meses)"
                  w={130}
                  min={1}
                  max={60}
                  value={f.ventanaMeses}
                  onChange={(v) => cambiarFila(i, { ventanaMeses: Number(v) || 1 })}
                />
              )}
              {!codigos && (
                <NumberInput
                  label={f.tipo === 'gastoMovil' ? 'Percentil' : 'Mínimo'}
                  w={110}
                  min={1}
                  max={100}
                  value={f.minimo}
                  onChange={(v) => cambiarFila(i, { minimo: Number(v) || 1 })}
                />
              )}
              {f.tipo === 'principiosConsecutivos' && (
                <NumberInput
                  label="Meses consecutivos"
                  w={150}
                  min={2}
                  max={60}
                  value={f.meses}
                  onChange={(v) => cambiarFila(i, { meses: Number(v) || 2 })}
                />
              )}
              <Checkbox label="Negar" checked={f.negar} onChange={(ev) => cambiarFila(i, { negar: ev.currentTarget.checked })} />
              <Button size="xs" variant="subtle" color="red" onClick={() => alCambiar({ ...valor, filas: valor.filas.filter((_, k) => k !== i) })}>
                Quitar
              </Button>
            </Group>
          </Card>
        );
      })}
      <Button size="xs" variant="default" onClick={() => alCambiar({ ...valor, filas: [...valor.filas, filaVacia()] })}>
        Agregar criterio
      </Button>
    </Stack>
  );
}

/** Crear o editar una cohorte (editar los criterios crea la versión siguiente). */
function ModalCohorte({ abierto, alCerrar, cohorte }: { abierto: boolean; alCerrar: () => void; cohorte: DetalleCohorte | null }) {
  const cliente = useQueryClient();
  const vigente = cohorte?.versiones.find((v) => v.vigente);
  const inicial = vigente ? criterioAEditor(vigente.criterios) : { op: 'Y' as const, filas: [filaVacia()] };
  const [codigo, setCodigo] = useState('');
  const [nombre, setNombre] = useState(cohorte?.nombre ?? '');
  const [editor, setEditor] = useState<Editor | null>(inicial);
  const [enviando, setEnviando] = useState(false);
  // Los criterios no son un solo campo: su error (y el de la descripción) va en el resumen
  const v = useValidacion({ etiquetas: { codigo: 'Código', nombre: 'Nombre' }, testid: 'error-cohorte' });
  const idMotivo = useId();
  const problemas = editor ? problemasEditor(editor) : [];
  const motivo =
    problemas.length > 0 ? 'Corrija los criterios indicados.' : nombre.trim().length < 3 ? 'Escriba el nombre (mínimo 3 caracteres).' : null;
  const guardar = async () => {
    if (!editor && !cohorte) return;
    setEnviando(true);
    v.reiniciar();
    try {
      // Sin editor (partición o grupos anidados) solo cambia el nombre: el servidor conserva los criterios
      const cuerpo = editor ? { nombre, criterios: editorACriterio(editor), ...(cohorte ? {} : { codigo }) } : { nombre };
      const r = await api<{ version?: number }>(cohorte ? `/api/analitica/cohortes/${cohorte.id}` : '/api/analitica/cohortes', {
        metodo: cohorte ? 'PUT' : 'POST',
        cuerpo,
      });
      notifications.show({
        color: 'green',
        message: r.version
          ? `Versión ${r.version} guardada; se calcula sobre el corte vigente en segundo plano.`
          : editor
            ? 'Sin cambios en los criterios.'
            : 'Nombre guardado.',
      });
      await cliente.invalidateQueries({ queryKey: ['analitica', 'cohortes'] });
      alCerrar();
    } catch (e) {
      v.errorServidor(e, 'No se pudo guardar la cohorte.');
    } finally {
      setEnviando(false);
    }
  };
  return (
    <Modal opened={abierto} onClose={alCerrar} size="xl" title={cohorte ? `Editar «${cohorte.nombre}»` : 'Nueva cohorte'}>
      <Stack ref={v.ref}>
        {!cohorte && (
          <TextInput
            label="Código"
            description="Minúsculas y guion bajo (3 a 40)"
            value={codigo}
            onChange={(e) => {
              setCodigo(e.currentTarget.value);
              v.limpiar('codigo');
            }}
            error={v.error('codigo')}
            required
          />
        )}
        <TextInput
          label="Nombre"
          value={nombre}
          onChange={(e) => {
            setNombre(e.currentTarget.value);
            v.limpiar('nombre');
          }}
          error={v.error('nombre')}
          required
        />
        {editor ? (
          <EditorCriterios valor={editor} alCambiar={setEditor} />
        ) : (
          <Alert color="blue">Los criterios de esta cohorte (partición o grupos anidados) se editan por la API; aquí puede cambiar el nombre.</Alert>
        )}
        {problemas.map((p) => (
          <Text key={p} size="sm" c="red">
            {p}
          </Text>
        ))}
        {v.resumen}
        <Group justify="flex-end">
          {motivo && (
            <Text size="sm" c="dimmed" id={idMotivo}>
              {motivo}
            </Text>
          )}
          <Button variant="default" onClick={alCerrar}>
            Cancelar
          </Button>
          <BotonConMotivo loading={enviando} motivo={motivo} idMotivo={idMotivo} onClick={guardar} data-testid="guardar-cohorte">
            Guardar
          </BotonConMotivo>
        </Group>
      </Stack>
    </Modal>
  );
}

/** Detalle de una cohorte: serie mensual, comparación entre periodos y niveles, composición y descargas. */
function DetalleCohorteVista({
  id,
  estado,
  puedeEditar,
  puedeRecalcular,
  puedeVerConsola,
}: {
  id: number;
  estado: EstadoAnalitica;
  puedeEditar: boolean;
  puedeRecalcular: boolean;
  puedeVerConsola: boolean;
}) {
  const [por, setPor] = useState('atc3');
  const [editando, setEditando] = useState(false);
  const cliente = useQueryClient();
  const detalle = useQuery({
    queryKey: ['analitica', 'cohortes', 'detalle', id, parametros(estado)],
    queryFn: () => api<DetalleCohorte>(`/api/analitica/cohortes/${id}?${parametros(estado)}`),
  });
  const composicion = useQuery({
    queryKey: ['analitica', 'cohortes', 'composicion', id, por, parametros(estado)],
    queryFn: () => api<Composicion>(`/api/analitica/cohortes/${id}/composicion?${parametros(estado, { por })}`),
    enabled: detalle.data?.estado === 'evaluada' && !(detalle.data?.grupos?.length ?? 0),
    retry: false,
  });
  // El error de una descarga o del recálculo queda fijo en el detalle (Anexo 5 num. 2.4)
  const v = useValidacion({ testid: 'error-detalle-cohorte' });
  if (detalle.isLoading) return <Loader />;
  if (detalle.error || !detalle.data) {
    return <Alert color="red">{detalle.error instanceof ErrorApi ? detalle.error.message : 'No se pudo abrir la cohorte.'}</Alert>;
  }
  const d = detalle.data;
  const bajar = async (formato: string, vista: string) => {
    v.reiniciar();
    try {
      await descargar(`/api/analitica/cohortes/${id}/descarga?${parametros(estado, { formato, vista, por: vista === 'composicion' ? por : '' })}`);
    } catch (e) {
      v.errorServidor(e, 'No se pudo descargar.');
    }
  };
  const recalcular = async () => {
    v.reiniciar();
    try {
      await api(`/api/analitica/cohortes/${id}/recalcular`, { metodo: 'POST', cuerpo: {} });
      notifications.show({ color: 'green', message: 'Recálculo en curso; actualice en unos segundos.' });
      await cliente.invalidateQueries({ queryKey: ['analitica', 'cohortes'] });
    } catch (e) {
      v.errorServidor(e, 'No se pudo recalcular.');
    }
  };
  return (
    <Stack ref={v.ref}>
      <Text size="sm">{d.descripcion}</Text>
      <Text size="sm" data-testid="detalle-criterio">
        <b>Criterio:</b> {d.criterio}
      </Text>
      <Text size="xs" c="dimmed">
        {d.corteTexto} · periodo {d.periodo} · {d.nivel}
        {d.fuentes ? ` · ${d.fuentes}` : ''}
      </Text>
      <Group gap="xs">
        {puedeEditar && (
          <Button size="xs" variant="default" onClick={() => setEditando(true)}>
            Editar criterios
          </Button>
        )}
        {puedeRecalcular && (
          <Button size="xs" variant="default" onClick={recalcular}>
            Recalcular
          </Button>
        )}
        {['xlsx', 'csv', 'pdf'].map((f) => (
          <Button key={f} size="xs" variant="subtle" onClick={() => bajar(f, 'serie')}>
            Serie {f === 'xlsx' ? 'Excel' : f.toUpperCase()}
          </Button>
        ))}
      </Group>
      {v.resumen}
      {d.estado !== 'evaluada' && <Alert color="gray">{d.motivo}</Alert>}
      {d.avisoSupresion && <Alert color="blue">{d.avisoSupresion}</Alert>}
      {puedeVerConsola && d.estado === 'evaluada' && (
        <Anchor component={Link} to={`/analitica/alertas?cohorte=${id}`} size="sm" data-testid="alertas-de-la-cohorte">
          Ver las alertas asociadas a esta cohorte en la consola
        </Anchor>
      )}
      {d.serie && d.serie.length > 0 && (
        <TablaAdaptable>
          <Table striped withTableBorder fz="xs" miw={560} aria-label="Serie mensual de la cohorte">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Mes</Table.Th>
                {d.grupos?.length ? <Table.Th>Grupo</Table.Th> : null}
                <Table.Th>Pacientes</Table.Th>
                <Table.Th>Ingresos</Table.Th>
                <Table.Th>Salidas</Table.Th>
                <Table.Th>Dispensaciones</Table.Th>
                <Table.Th>Gasto</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {d.serie.map((f) => (
                <Table.Tr key={f.mes + f.grupo}>
                  <Table.Td>{f.mes}</Table.Td>
                  {d.grupos?.length ? <Table.Td>{f.grupo}</Table.Td> : null}
                  <Table.Td>{f.pacientesTexto}</Table.Td>
                  <Table.Td>{conteo(f.ingresos)}</Table.Td>
                  <Table.Td>{conteo(f.salidas)}</Table.Td>
                  <Table.Td>{conteo(f.dispensaciones)}</Table.Td>
                  <Table.Td>{f.gastoTexto}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </TablaAdaptable>
      )}
      {d.comparacion && (
        <Card withBorder padding="xs">
          <Text size="sm" fw={600}>
            Comparación con el periodo anterior ({d.comparacion.periodoAnterior})
          </Text>
          <TablaAdaptable>
            <Table fz="xs" miw={480} aria-label="Comparación entre periodos">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Indicador</Table.Th>
                  <Table.Th>Actual</Table.Th>
                  <Table.Th>Anterior</Table.Th>
                  <Table.Th>Variación</Table.Th>
                  <Table.Th>Variación %</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {d.comparacion.filas.map((f) => (
                  <Table.Tr key={f.indicador}>
                    <Table.Td>{f.indicador}</Table.Td>
                    <Table.Td>{f.actual}</Table.Td>
                    <Table.Td>{f.anterior}</Table.Td>
                    <Table.Td>{f.variacion ?? '—'}</Table.Td>
                    <Table.Td>{f.variacionPct ?? '—'}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </TablaAdaptable>
        </Card>
      )}
      {d.niveles && d.niveles.length > 0 && (
        <Card withBorder padding="xs">
          <Text size="sm" fw={600}>
            Comparación entre niveles
          </Text>
          <TablaAdaptable>
            <Table fz="xs" miw={420} aria-label="Comparación entre niveles">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{d.niveles[0]?.nivel === 'esm' ? 'ESM' : 'Fuerza'}</Table.Th>
                  <Table.Th>Pacientes</Table.Th>
                  <Table.Th>Dispensaciones</Table.Th>
                  <Table.Th>Gasto</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {d.niveles.map((n) => (
                  <Table.Tr key={n.codigo}>
                    <Table.Td>{n.codigo}</Table.Td>
                    <Table.Td>{n.pacientesTexto}</Table.Td>
                    <Table.Td>{conteo(n.dispensaciones)}</Table.Td>
                    <Table.Td>{n.gasto}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </TablaAdaptable>
        </Card>
      )}
      {d.estado === 'evaluada' && !d.grupos?.length && (
        <Card withBorder padding="xs">
          <Group justify="space-between">
            <Select
              aria-label="Composición por"
              w={200}
              data={COMPOSICIONES.map((c) => ({ value: c.valor, label: c.etiqueta }))}
              value={por}
              onChange={(v) => v && setPor(v)}
              allowDeselect={false}
            />
            <Group gap={4}>
              {['xlsx', 'csv', 'pdf'].map((f) => (
                <Button key={f} size="xs" variant="subtle" onClick={() => bajar(f, 'composicion')}>
                  {f === 'xlsx' ? 'Excel' : f.toUpperCase()}
                </Button>
              ))}
            </Group>
          </Group>
          {composicion.error && (
            <Alert color="yellow">{composicion.error instanceof ErrorApi ? composicion.error.message : 'Sin composición.'}</Alert>
          )}
          {composicion.data && (
            <TablaAdaptable>
              <Table fz="xs" miw={480} aria-label={`Composición por ${composicion.data.porTexto}`}>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{composicion.data.porTexto}</Table.Th>
                    <Table.Th>Pacientes</Table.Th>
                    <Table.Th>Dispensaciones</Table.Th>
                    <Table.Th>Gasto</Table.Th>
                    <Table.Th>Participación</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {composicion.data.filas.map((f) => (
                    <Table.Tr key={f.clave}>
                      <Table.Td>{f.clave}</Table.Td>
                      <Table.Td>{f.pacientesTexto}</Table.Td>
                      <Table.Td>{conteo(f.dispensaciones)}</Table.Td>
                      <Table.Td>{f.gastoTexto}</Table.Td>
                      <Table.Td>{f.participacion ?? '—'}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </TablaAdaptable>
          )}
        </Card>
      )}
      <Accordion variant="contained">
        <Accordion.Item value="versiones">
          <Accordion.Control>Versiones ({d.versiones.length})</Accordion.Control>
          <Accordion.Panel>
            {d.versiones.map((v) => (
              <Text key={v.version} size="xs">
                v{v.version}
                {v.vigente ? ' (vigente)' : ''} · {v.creada} · {v.creadaPor}: {v.criterio}
              </Text>
            ))}
          </Accordion.Panel>
        </Accordion.Item>
      </Accordion>
      {editando && <ModalCohorte abierto={editando} alCerrar={() => setEditando(false)} cohorte={d} />}
    </Stack>
  );
}

/**
 * Tablero 10 «Cohortes y riesgo» (analitica-cohortes-alertas; mockup «Cohortes (conteos agregados)»): una tarjeta por cohorte con el conteo
 * de pacientes, el criterio, la versión, el periodo y el corte; las particiones GCF y GRC con sus grupos; el detalle con la serie, la
 * comparación y la composición; y la gestión de cohortes para Administrador y Analista. Siempre agregado.
 */
export function PanelCohortes({ estado }: { estado: EstadoAnalitica }) {
  const [abierta, setAbierta] = useState<TarjetaCohorte | null>(null);
  const [nueva, setNueva] = useState(false);
  const datos = useQuery({
    queryKey: ['analitica', 'cohortes', parametros(estado)],
    queryFn: () => api<Tarjetas>(`/api/analitica/cohortes?${parametros(estado)}`),
  });
  if (datos.isLoading) {
    return (
      <Center mih={160}>
        <Loader />
      </Center>
    );
  }
  if (datos.error || !datos.data) {
    return <Alert color="red">{datos.error instanceof ErrorApi ? datos.error.message : 'No se pudieron consultar las cohortes.'}</Alert>;
  }
  const t = datos.data;
  const { principales, otras } = separarTarjetas(t.cohortes);
  return (
    <Stack data-testid="panel-cohortes">
      <Group justify="space-between">
        <Title order={2}>Cohortes y riesgo · {t.nivel ?? 'Nacional'}</Title>
        <Group gap="xs">
          {t.puedeEditar && (
            <Button size="xs" onClick={() => setNueva(true)} data-testid="nueva-cohorte">
              Nueva cohorte
            </Button>
          )}
          {t.puedeVerConsola && (
            <Button size="xs" variant="subtle" component={Link} to="/analitica/alertas">
              Consola de alertas
            </Button>
          )}
        </Group>
      </Group>
      {t.mensaje && <Alert color="blue">{t.mensaje}</Alert>}
      {filtrosIgnorados(estado, ['fuerza']).length > 0 && (
        <Alert color="yellow" data-testid="filtros-ignorados">
          Las cohortes aplican el corte, el periodo y una Fuerza; no aplican: {filtrosIgnorados(estado, ['fuerza']).join(', ')}.
        </Alert>
      )}
      {t.corteTexto && (
        <Text size="xs" c="dimmed">
          Cohortes (conteos agregados) · {t.corteTexto} · periodo {t.periodo}. Pacientes distintos que cumplen el criterio en algún mes del periodo.
        </Text>
      )}
      <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="sm">
        {principales.map((c) => (
          <TarjetaCohorteVista key={c.id} c={c} alAbrir={() => setAbierta(c)} />
        ))}
      </SimpleGrid>
      {otras.length > 0 && (
        <>
          <Title order={3} size="h5">
            Otras cohortes y particiones
          </Title>
          <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="sm">
            {otras.map((c) => (
              <TarjetaCohorteVista key={c.id} c={c} alAbrir={() => setAbierta(c)} />
            ))}
          </SimpleGrid>
        </>
      )}
      <Accordion variant="contained">
        <Accordion.Item value="metodologia">
          <Accordion.Control>Metodología de GCF y GRC</Accordion.Control>
          <Accordion.Panel>
            <Text size="sm">{t.metodologia}</Text>
            <Text size="xs" c="dimmed" mt="xs">
              Supresión de celdas pequeñas: {t.supresion}
            </Text>
          </Accordion.Panel>
        </Accordion.Item>
      </Accordion>
      <Drawer opened={!!abierta} onClose={() => setAbierta(null)} position="right" size="xl" title={abierta ? `Cohorte · ${abierta.nombre}` : ''}>
        {abierta && (
          <DetalleCohorteVista
            id={abierta.id}
            estado={estado}
            puedeEditar={t.puedeEditar}
            puedeRecalcular={t.puedeRecalcular}
            puedeVerConsola={t.puedeVerConsola}
          />
        )}
      </Drawer>
      {nueva && <ModalCohorte abierto={nueva} alCerrar={() => setNueva(false)} cohorte={null} />}
    </Stack>
  );
}
