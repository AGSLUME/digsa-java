import {
  Accordion,
  ActionIcon,
  Alert,
  Anchor,
  Badge,
  Button,
  Card,
  Center,
  Group,
  List,
  Loader,
  MultiSelect,
  ScrollArea,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconLock, IconLockOpen } from '@tabler/icons-react';
import { useIsMutating, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api, ErrorApi } from '../shared/api';
import {
  botonesCiclo,
  cuerpoAjuste,
  edicionDe,
  etiquetaAnio,
  filaCambiada,
  filasProgramables,
  filasSinResultado,
  MAX_OTROS,
  NOMBRE_ESTADO,
  opcionesLider,
  opcionesOtros,
  periodoCiclo,
  type AuditorPropuesta,
  type CicloPropuesta,
  type DatosPropuesta,
  type EdicionFila,
  type ItemPropuesta,
  type Propuesta as DatosBorrador,
  type RespuestaConfirmar,
} from '../shared/propuesta';
import { puedeEntrar, useSesion } from '../shared/sesion';
import { EncabezadoPagina } from '../shared/EncabezadoPagina';
import { cifra, conteo, fecha } from '../shared/formato';
import { useValidacion } from '../shared/validacion';
import { campoDelAjuste } from '../shared/validaciones';

/** Dónde se registran el inicio del contrato y los parámetros de la propuesta. */
function EnlaceParametros() {
  const s = useSesion();
  return puedeEntrar(s, 'catalogos') ? (
    <Anchor component={Link} to="/catalogos" size="sm">
      Catálogos › Parámetros
    </Anchor>
  ) : (
    <Text span size="sm" fw={500}>
      Catálogos › Parámetros
    </Text>
  );
}

/**
 * «Propuesta de agenda» (decisiones 13 y 32): el sistema propone las visitas que faltan por ciclo y año de contrato, con auditor líder,
 * equipo y fecha; el coordinador ajusta, recalcula y confirma. Nada se programa ni se notifica hasta confirmar. Solo el coordinador.
 */
export function Propuesta() {
  const qc = useQueryClient();
  const enCurso = useIsMutating({ mutationKey: ['propuesta'] });
  const [anio, setAnio] = useState<number | null>(null);
  const [abierta, setAbierta] = useState<number | null>(null);
  const datos = useQuery({
    queryKey: ['propuesta', 'datos', anio],
    queryFn: () => api<DatosPropuesta>('/api/propuesta' + (anio == null ? '' : `?anio=${anio}`)),
  });
  // Errores al proponer o continuar una confirmación: persistentes encima de los ciclos (antes, aviso de 10 s)
  const v = useValidacion({ testid: 'error-ciclos' });

  const generar = useMutation({
    mutationKey: ['propuesta'],
    mutationFn: (c: CicloPropuesta) =>
      api<{ id?: number; mensaje: string }>('/api/propuesta/generar', { cuerpo: { ciclo: c.ciclo, anio: c.anioContrato ?? null } }),
    onSuccess: (r) => {
      notifications.show({ color: r.id ? 'green' : 'blue', message: r.mensaje });
      v.reiniciar();
      if (r.id) setAbierta(r.id);
      void qc.invalidateQueries({ queryKey: ['propuesta'] });
    },
    onError: (e) => {
      v.errorServidor(e, 'No se pudo generar la propuesta.');
      // Otro usuario pudo generarla al mismo tiempo (409): recargar muestra su borrador
      void qc.invalidateQueries({ queryKey: ['propuesta'] });
    },
  });

  const continuar = useMutation({
    mutationKey: ['propuesta'],
    mutationFn: (id: number) => api<RespuestaConfirmar>(`/api/propuesta/${id}/confirmar`, { metodo: 'POST' }),
    onSuccess: (r, id) => {
      notifications.show({ color: r.parcial ? 'yellow' : 'green', autoClose: 15000, message: r.mensaje });
      v.reiniciar();
      setAbierta(id);
      void qc.invalidateQueries({ queryKey: ['propuesta'] });
    },
    onError: (e) => {
      v.errorServidor(e, 'No se pudo continuar la confirmación.');
      void qc.invalidateQueries({ queryKey: ['propuesta'] });
    },
  });

  const d = datos.data;
  const proponer = (c: CicloPropuesta) => {
    const b = botonesCiclo(c);
    if (
      b.advertirDescarte &&
      !window.confirm('Este ciclo ya tiene un borrador: al proponer de nuevo, el borrador actual y sus ajustes se descartan. ¿Continuar?')
    ) {
      return;
    }
    generar.mutate(c);
  };

  return (
    <Stack>
      <EncabezadoPagina titulo="Propuesta de agenda" fuente="Anexo 3 · Anexo 5 · 4.5" />
      {datos.error && <Alert color="red">{datos.error.message}</Alert>}
      <div ref={v.ref}>{v.resumen}</div>
      {datos.isLoading && (
        <Center py="xl">
          <Loader />
        </Center>
      )}
      {d && (
        <>
          <Explicacion visitasPorDia={d.visitasPorDia} kmMismoDia={d.kmMismoDia} />

          {d.inicioContrato ? (
            <Select
              label="Periodo"
              maw={420}
              allowDeselect={false}
              data={d.anios.map((a) => ({ value: String(a.anio), label: etiquetaAnio(a) }))}
              value={String(d.anio)}
              onChange={(x) => {
                if (x) {
                  setAnio(Number(x));
                  setAbierta(null);
                }
              }}
              description={`Inicio del contrato: ${fecha(d.inicioContrato)}. La línea base vence a los 3 meses del inicio; los demás ciclos se cuentan por año de contrato.`}
              data-testid="anio-contrato"
            />
          ) : (
            <Alert color="orange" title="Falta la fecha de inicio del contrato" data-testid="sin-inicio-contrato">
              <Text size="sm">
                Los plazos de los ciclos se cuentan desde el inicio del contrato. Regístrelo en <EnlaceParametros /> (parámetro{' '}
                <code>inicio_contrato</code>, AAAA-MM-DD).
              </Text>
            </Alert>
          )}

          {d.avisos.length > 0 && (
            <Alert color="yellow" title="Avisos de la cobertura" data-testid="avisos-cobertura">
              <List size="sm" spacing={2}>
                {d.avisos.map((a) => (
                  <List.Item key={a}>{a}</List.Item>
                ))}
              </List>
              <Text size="xs" mt={6}>
                Son avisos: no impiden proponer.
              </Text>
            </Alert>
          )}

          <SimpleGrid cols={{ base: 1, md: 2 }} data-testid="ciclos">
            {d.ciclos.map((c) => (
              <TarjetaCiclo
                key={c.ciclo}
                ciclo={c}
                ocupado={enCurso > 0}
                generando={generar.isPending && generar.variables?.ciclo === c.ciclo}
                continuando={continuar.isPending && continuar.variables === c.confirmadaIncompletaId}
                alProponer={() => proponer(c)}
                alVer={(id) => setAbierta(id)}
                alContinuar={(id) => {
                  if (window.confirm('Se programarán y notificarán las filas que quedaron sin resultado. ¿Continuar la confirmación?'))
                    continuar.mutate(id);
                }}
              />
            ))}
          </SimpleGrid>

          {abierta != null && <VistaPropuesta key={abierta} id={abierta} auditores={d.auditores} alCerrar={() => setAbierta(null)} />}
        </>
      )}
    </Stack>
  );
}

/** Los cuatro pasos, plegados, con los parámetros vigentes (decisión 13: se explica antes de usarla). */
function Explicacion({ visitasPorDia, kmMismoDia }: { visitasPorDia: number; kmMismoDia: number }) {
  return (
    <Accordion variant="contained" data-testid="como-arma">
      <Accordion.Item value="como">
        <Accordion.Control>Cómo arma la propuesta</Accordion.Control>
        <Accordion.Panel>
          <Stack gap="xs">
            <Text size="sm" fw={600}>
              Nada se programa ni se notifica hasta confirmar.
            </Text>
            <List type="ordered" size="sm" spacing={6}>
              <List.Item>
                <b>Qué se visita.</b> Los puntos activos del ciclo sin una visita (no cancelada) de los tipos que lo cubren: en la línea base, en
                cualquier fecha; en los ciclos anuales, dentro del año de contrato. Los ESM de suministro y satélite se vigilan de forma remota y no
                entran.
              </List.Item>
              <List.Item>
                <b>Auditor líder.</b> El auditor asignado al punto, si puede liderarlo; si no, el más cercano con el requisito (químico farmacéutico
                donde se exige; si no, químico farmacéutico, regente o auditor en salud), repartiendo la carga. En el nivel central se suma el
                ingeniero de sistemas con menos carga.
              </List.Item>
              <List.Item>
                <b>Fecha.</b> Días hábiles (sin fines de semana ni festivos) que dejan los 15 días de notificación previa al OPL, hasta el límite del
                ciclo. Cada auditor tiene máximo <b data-testid="visitas-por-dia">{conteo(visitasPorDia)}</b> visita(s) por día y, el mismo día, solo
                municipios a no más de <b data-testid="km-mismo-dia">{cifra(kmMismoDia)}</b> km entre sí.
              </List.Item>
              <List.Item>
                <b>Borrador.</b> Revise las filas, ajuste el líder, el equipo o la fecha (la fila queda fija), recalcule y confirme. Al confirmar,
                cada visita se programa y se notifica como desde la agenda.
              </List.Item>
            </List>
            <Text size="sm">
              La distancia es en línea recta entre el municipio base del auditor y el municipio por el que se llega al punto, con las coordenadas del
              DANE. Las visitas por día y los km del mismo día se cambian en <EnlaceParametros /> (<code>visitas_por_dia</code> y{' '}
              <code>km_mismo_dia</code>).
            </Text>
          </Stack>
        </Accordion.Panel>
      </Accordion.Item>
    </Accordion>
  );
}

function Dato({ etiqueta, valor, color }: { etiqueta: string; valor: number | undefined; color?: string }) {
  return (
    <Stack gap={0} align="center">
      <Text size="lg" fw={700} c={color}>
        {conteo(valor)}
      </Text>
      <Text size="xs" c="dimmed">
        {etiqueta}
      </Text>
    </Stack>
  );
}

function TarjetaCiclo({
  ciclo: c,
  ocupado,
  generando,
  continuando,
  alProponer,
  alVer,
  alContinuar,
}: {
  ciclo: CicloPropuesta;
  ocupado: boolean;
  generando: boolean;
  continuando: boolean;
  alProponer: () => void;
  alVer: (id: number) => void;
  alContinuar: (id: number) => void;
}) {
  const b = botonesCiclo(c);
  return (
    <Card withBorder padding="sm" data-testid={'ciclo-' + c.ciclo}>
      <Stack gap="xs">
        <Text fw={600}>{c.nombre}</Text>
        {c.error ? (
          <Text size="sm" c="red">
            {c.error}
          </Text>
        ) : (
          <>
            <Text size="xs" c="dimmed">
              Periodo: {periodoCiclo(c)}
              {c.anioContrato != null ? ` · año de contrato ${c.anioContrato}` : ''}
            </Text>
            <SimpleGrid cols={4} spacing={4} data-testid="cobertura">
              <Dato etiqueta="Puntos" valor={c.total} />
              <Dato etiqueta="Cerradas" valor={c.cerradas} />
              <Dato etiqueta="Agendadas" valor={c.agendadas} />
              <Dato etiqueta="Faltan" valor={c.faltan} color={(c.faltan ?? 0) > 0 ? 'orange' : undefined} />
            </SimpleGrid>
          </>
        )}
        <Group gap="xs">
          <Button mih={44} disabled={b.proponerDeshabilitado || ocupado} loading={generando} onClick={alProponer}>
            {b.proponer}
          </Button>
          {b.verBorrador && c.borradorId != null && (
            <Button mih={44} variant="light" onClick={() => alVer(c.borradorId!)}>
              Ver borrador
            </Button>
          )}
          {b.continuarConfirmacion && c.confirmadaIncompletaId != null && (
            <Button
              mih={44}
              variant="light"
              color="orange"
              disabled={ocupado}
              loading={continuando}
              onClick={() => alContinuar(c.confirmadaIncompletaId!)}
            >
              Continuar confirmación
            </Button>
          )}
        </Group>
        {b.advertirDescarte && (
          <Text size="xs" c="dimmed">
            Hay un borrador: «Proponer de nuevo» lo descarta con sus ajustes.
          </Text>
        )}
      </Stack>
    </Card>
  );
}

/** El borrador (o la propuesta confirmada con sus resultados): resumen, filas editables, recalcular, confirmar y descartar. */
function VistaPropuesta({ id, auditores, alCerrar }: { id: number; auditores: AuditorPropuesta[]; alCerrar: () => void }) {
  const s = useSesion();
  const qc = useQueryClient();
  const enCurso = useIsMutating({ mutationKey: ['propuesta'] });
  const [ediciones, setEdiciones] = useState<Record<number, EdicionFila>>({});
  const [resultado, setResultado] = useState<RespuestaConfirmar | null>(null);
  const propuesta = useQuery({
    queryKey: ['propuesta', 'ver', id],
    queryFn: () => api<DatosBorrador>(`/api/propuesta/${id}`),
  });
  const refrescar = () => void qc.invalidateQueries({ queryKey: ['propuesta'] });
  // Errores del borrador: persistentes en un resumen de la pantalla (antes, aviso de 10 s). Campos por fila: «fecha-<id>», «lider-<id>»
  // y «otros-<id>»
  const v = useValidacion({
    etiquetas: Object.fromEntries(
      (propuesta.data?.items ?? []).flatMap((i) => [
        ['fecha-' + i.id, 'Fecha de ' + i.punto],
        ['lider-' + i.id, 'Auditor líder de ' + i.punto],
        ['otros-' + i.id, 'Otros auditores de ' + i.punto],
      ]),
    ),
    testid: 'error-propuesta',
  });
  const conflicto = (e: unknown, texto: string) => {
    v.errorServidor(e, texto);
    refrescar();
  };
  const exito = (mensaje: string) => {
    notifications.show({ color: 'green', message: mensaje });
    v.reiniciar();
  };

  const guardar = useMutation({
    mutationKey: ['propuesta'],
    mutationFn: (x: { item: ItemPropuesta; edicion: EdicionFila }) =>
      api<{ mensaje: string }>(`/api/propuesta/items/${x.item.id}`, { metodo: 'PUT', cuerpo: cuerpoAjuste(x.edicion) }),
    onSuccess: (r, x) => {
      exito(r.mensaje);
      setEdiciones(({ [x.item.id]: _, ...resto }) => resto);
      refrescar();
    },
    onError: (e, x) => {
      // El rechazo se marca en el campo de la fila (si el mensaje lo deja ver) o dice de qué punto es
      const campo = e instanceof ErrorApi ? campoDelAjuste(e.message) : null;
      const error =
        e instanceof ErrorApi
          ? campo
            ? new ErrorApi(e.estado, e.message, { ...e.datos, campo: `${campo}-${x.item.id}` })
            : new ErrorApi(e.estado, `${x.item.punto}: ${e.message}`, e.datos)
          : e;
      v.errorServidor(error, 'No se pudo guardar el ajuste de ' + x.item.punto + '.');
      if (e instanceof ErrorApi && (e.estado === 404 || e.estado === 409)) refrescar();
    },
  });
  const liberar = useMutation({
    mutationKey: ['propuesta'],
    mutationFn: (i: ItemPropuesta) => api<{ mensaje: string }>(`/api/propuesta/items/${i.id}/liberar`, { metodo: 'POST' }),
    onSuccess: (r) => {
      exito(r.mensaje);
      refrescar();
    },
    onError: (e) => conflicto(e, 'No se pudo liberar la fila.'),
  });
  const recalcular = useMutation({
    mutationKey: ['propuesta'],
    mutationFn: () => api<{ mensaje: string }>(`/api/propuesta/${id}/recalcular`, { metodo: 'POST' }),
    onSuccess: (r) => {
      exito(r.mensaje);
      setEdiciones({});
      refrescar();
    },
    onError: (e) => conflicto(e, 'No se pudo recalcular.'),
  });
  const confirmar = useMutation({
    mutationKey: ['propuesta'],
    mutationFn: () => api<RespuestaConfirmar>(`/api/propuesta/${id}/confirmar`, { metodo: 'POST' }),
    onSuccess: (r) => {
      setResultado(r);
      setEdiciones({});
      v.reiniciar();
      notifications.show({ color: r.parcial ? 'yellow' : 'green', autoClose: 15000, message: r.mensaje });
      refrescar();
    },
    onError: (e) => conflicto(e, 'No se pudo confirmar la propuesta.'),
  });
  const descartar = useMutation({
    mutationKey: ['propuesta'],
    mutationFn: () => api<{ mensaje: string }>(`/api/propuesta/${id}/descartar`, { metodo: 'POST' }),
    onSuccess: (r) => {
      exito(r.mensaje);
      refrescar();
      alCerrar();
    },
    onError: (e) => conflicto(e, 'No se pudo descartar el borrador.'),
  });

  if (propuesta.isLoading) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    );
  }
  if (propuesta.error || !propuesta.data) {
    return <Alert color="red">{propuesta.error?.message ?? 'No se pudo abrir la propuesta.'}</Alert>;
  }
  const p = propuesta.data;
  const borrador = p.estado === 'borrador';
  const ocupado = enCurso > 0;
  const sinResultado = filasSinResultado(p);
  const verVisita = puedeEntrar(s, 'agenda');
  const editar = (i: ItemPropuesta) => ediciones[i.id] ?? edicionDe(i);
  const cambiar = (i: ItemPropuesta, cambio: Partial<EdicionFila>) => setEdiciones((m) => ({ ...m, [i.id]: { ...editar(i), ...cambio } }));
  const alertas = p.resumen.alertas ?? [];

  const pedirConfirmacion = () => {
    const n = filasProgramables(p);
    const pendientes = Object.keys(ediciones).length > 0 ? ' Hay ajustes sin guardar: se perderán.' : '';
    if (
      window.confirm(
        `Se programarán ${n} visita(s) con auditor y fecha y se notificará al OPL y a la DIGSA. Las filas sin auditor o sin fecha quedan sin programar.${pendientes} ¿Confirmar la propuesta?`,
      )
    ) {
      confirmar.mutate();
    }
  };

  const filaProps = (i: ItemPropuesta) => ({
    item: i,
    edicion: editar(i),
    auditores,
    borrador,
    verVisita,
    ocupado,
    guardando: guardar.isPending && guardar.variables?.item.id === i.id,
    liberando: liberar.isPending && liberar.variables?.id === i.id,
    errores: { fecha: v.error('fecha-' + i.id), lider: v.error('lider-' + i.id), otros: v.error('otros-' + i.id) },
    alCambiar: (c: Partial<EdicionFila>) => {
      cambiar(i, c);
      for (const campo of ['fecha', 'lider', 'otros'] as const) if (campo in c) v.limpiar(`${campo}-${i.id}`);
    },
    alGuardar: () => guardar.mutate({ item: i, edicion: editar(i) }),
    alLiberar: () => liberar.mutate(i),
  });

  return (
    <Card withBorder padding="sm" data-testid="propuesta">
      <Stack>
        <Group justify="space-between" align="flex-start">
          <Stack gap={2}>
            <Title order={2} size="h4">
              Propuesta N° {p.id} · {p.nombre}
            </Title>
            <Text size="xs" c="dimmed">
              Periodo {fecha(p.desde)} a {fecha(p.hasta)}
              {p.anioContrato != null ? ` · año de contrato ${p.anioContrato}` : ''}
            </Text>
          </Stack>
          <Group gap="xs">
            <Badge color={borrador ? 'blue' : p.estado === 'confirmada' ? 'green' : 'gray'} data-testid="estado-propuesta">
              {NOMBRE_ESTADO[p.estado]}
            </Badge>
            <Button variant="subtle" size="xs" onClick={alCerrar}>
              Cerrar
            </Button>
          </Group>
        </Group>

        <Alert color={p.resumen.cabe ? 'green' : 'orange'} data-testid="resumen-propuesta" title={p.resumen.texto ?? 'Resumen'}>
          <Text size="sm">
            {conteo(p.resumen.visitas ?? 0)} visita(s) · {conteo(p.resumen.auditores ?? 0)} auditor(es) · {conteo(p.resumen.dias ?? 0)} día(s)
            {p.resumen.desde ? ` · del ${fecha(p.resumen.desde)} al ${fecha(p.resumen.hasta ?? p.resumen.desde)}` : ''} ·{' '}
            {p.resumen.cabe ? 'cabe en el plazo' : 'no todo cabe'}
          </Text>
          {alertas.length > 0 && (
            <List size="sm" mt={4} spacing={2} data-testid="alertas-propuesta">
              {alertas.map((a) => (
                <List.Item key={a}>{a}</List.Item>
              ))}
            </List>
          )}
        </Alert>

        {borrador && (
          <Text size="sm" c="dimmed">
            Nada se programa ni se notifica hasta confirmar. Una fila ajustada queda fija (candado cerrado) y se respeta al recalcular; el candado la
            libera para que se calcule de nuevo.
          </Text>
        )}

        {resultado && (
          <Alert color={resultado.parcial ? 'yellow' : 'green'} data-testid="resultado-confirmacion">
            {resultado.mensaje}
          </Alert>
        )}

        {/* Solo el resumen: los campos de la fila salen dos veces (tabla y tarjetas, una oculta), el foco va al resumen que los nombra */}
        <div ref={v.ref}>{v.resumen}</div>

        {/* En el celular, tarjetas: sin desplazamiento horizontal */}
        <Stack hiddenFrom="md" gap="sm" data-testid="tarjetas-propuesta">
          {p.items.length === 0 && <Text size="sm">La propuesta no tiene filas.</Text>}
          {p.items.map((i) => (
            <TarjetaFila key={i.id} {...filaProps(i)} />
          ))}
        </Stack>
        <ScrollArea type="auto" visibleFrom="md">
          <Table striped miw={1240} verticalSpacing="xs" data-testid="tabla-propuesta">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Punto</Table.Th>
                <Table.Th>Fecha</Table.Th>
                <Table.Th>Auditor líder</Table.Th>
                <Table.Th>Otros auditores</Table.Th>
                <Table.Th>Km</Table.Th>
                <Table.Th>Motivo y alertas</Table.Th>
                <Table.Th>{borrador ? 'Fija' : 'Resultado'}</Table.Th>
                {borrador && <Table.Th>Acciones</Table.Th>}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {p.items.length === 0 && (
                <Table.Tr>
                  <Table.Td colSpan={8}>La propuesta no tiene filas.</Table.Td>
                </Table.Tr>
              )}
              {p.items.map((i) => (
                <FilaTabla key={i.id} {...filaProps(i)} />
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>

        <Group gap="xs">
          {borrador && (
            <>
              <Button
                mih={44}
                variant="light"
                loading={recalcular.isPending}
                disabled={ocupado}
                onClick={() =>
                  (Object.keys(ediciones).length === 0 ||
                    window.confirm(
                      'Hay ajustes sin guardar: recalcular los descarta (solo se respetan las filas guardadas como fijas). ¿Recalcular?',
                    )) &&
                  recalcular.mutate()
                }
              >
                Recalcular
              </Button>
              <Button mih={44} color="green" loading={confirmar.isPending} disabled={ocupado} onClick={pedirConfirmacion}>
                Confirmar y programar
              </Button>
              <Button
                mih={44}
                variant="light"
                color="red"
                loading={descartar.isPending}
                disabled={ocupado}
                onClick={() => window.confirm('¿Descartar el borrador con sus ajustes? No se programa nada.') && descartar.mutate()}
              >
                Descartar borrador
              </Button>
            </>
          )}
          {p.estado === 'confirmada' && sinResultado > 0 && (
            <Button
              mih={44}
              color="orange"
              loading={confirmar.isPending}
              disabled={ocupado}
              onClick={() =>
                window.confirm(`Quedan ${sinResultado} fila(s) sin resultado: se programarán y notificarán. ¿Continuar la confirmación?`) &&
                confirmar.mutate()
              }
            >
              Continuar confirmación
            </Button>
          )}
        </Group>
      </Stack>
    </Card>
  );
}

interface PropsFila {
  item: ItemPropuesta;
  edicion: EdicionFila;
  auditores: AuditorPropuesta[];
  borrador: boolean;
  verVisita: boolean;
  ocupado: boolean;
  guardando: boolean;
  liberando: boolean;
  /** Marca de error de cada campo de la fila (rechazo del servidor). */
  errores: { fecha?: string | true; lider?: string | true; otros?: string | true };
  alCambiar: (c: Partial<EdicionFila>) => void;
  alGuardar: () => void;
  alLiberar: () => void;
}

function CampoFecha({
  item,
  edicion,
  errores,
  alCambiar,
  tarjeta,
}: Pick<PropsFila, 'item' | 'edicion' | 'errores' | 'alCambiar'> & { tarjeta?: boolean }) {
  return (
    <TextInput
      type="date"
      size="xs"
      label={tarjeta ? 'Fecha' : undefined}
      aria-label={'Fecha de ' + item.punto}
      value={edicion.fecha}
      error={errores.fecha}
      onChange={(e) => alCambiar({ fecha: e.currentTarget.value })}
    />
  );
}

function CampoLider({
  item,
  edicion,
  auditores,
  errores,
  alCambiar,
  tarjeta,
}: Pick<PropsFila, 'item' | 'edicion' | 'auditores' | 'errores' | 'alCambiar'> & { tarjeta?: boolean }) {
  return (
    <Select
      error={errores.lider}
      size="xs"
      label={tarjeta ? 'Auditor líder' : undefined}
      aria-label={'Auditor líder de ' + item.punto}
      searchable
      clearable
      placeholder="Sin auditor"
      data={opcionesLider(auditores, edicion.lider, { id: item.auditorId, nombre: item.lider })}
      value={edicion.lider}
      onChange={(v) => alCambiar({ lider: v, otros: edicion.otros.filter((o) => o !== v) })}
      comboboxProps={{ withinPortal: true }}
    />
  );
}

function CampoOtros({
  item,
  edicion,
  auditores,
  errores,
  alCambiar,
  tarjeta,
}: Pick<PropsFila, 'item' | 'edicion' | 'auditores' | 'errores' | 'alCambiar'> & { tarjeta?: boolean }) {
  return (
    <MultiSelect
      error={errores.otros}
      size="xs"
      label={tarjeta ? 'Otros auditores' : undefined}
      aria-label={'Otros auditores de ' + item.punto}
      searchable
      clearable
      maxValues={MAX_OTROS}
      description={`Máximo ${MAX_OTROS}.`}
      data={opcionesOtros(auditores, edicion.lider, item.otros)}
      value={edicion.otros}
      onChange={(v) => alCambiar({ otros: v })}
      comboboxProps={{ withinPortal: true }}
    />
  );
}

/** Candado: cerrado y pulsable si la fila es fija (la libera); abierto si se calcula de nuevo al recalcular. */
function Candado({ item, borrador, ocupado, liberando, alLiberar }: Pick<PropsFila, 'item' | 'borrador' | 'ocupado' | 'liberando' | 'alLiberar'>) {
  if (!item.fijo) {
    return (
      <Group gap={4} wrap="nowrap" data-testid="calculada">
        <IconLockOpen size={18} aria-hidden />
        <Text size="xs" c="dimmed">
          Calculada
        </Text>
      </Group>
    );
  }
  return (
    <Group gap={4} wrap="nowrap" data-testid="fija">
      {borrador ? (
        <ActionIcon
          variant="light"
          size={44}
          aria-label={'Liberar la fila de ' + item.punto}
          title="Liberar: vuelve a calcularse al recalcular"
          loading={liberando}
          disabled={ocupado}
          onClick={alLiberar}
        >
          <IconLock size={18} />
        </ActionIcon>
      ) : (
        <IconLock size={18} aria-hidden />
      )}
      <Text size="xs">Fija</Text>
    </Group>
  );
}

function MotivoYAlertas({ item }: { item: ItemPropuesta }) {
  return (
    <Stack gap={2}>
      {item.motivo && <Text size="xs">{item.motivo}</Text>}
      {item.alertas.map((a) => (
        <Text key={a} size="xs" c="orange" style={{ overflowWrap: 'anywhere' }}>
          Alerta: {a}
        </Text>
      ))}
    </Stack>
  );
}

function Resultado({ item, verVisita }: { item: ItemPropuesta; verVisita: boolean }) {
  return (
    <Stack gap={2} data-testid="resultado-fila">
      {item.visita && item.visitaId != null && (
        <Text size="sm" fw={600}>
          {verVisita ? (
            <Anchor component={Link} to={`/agenda/visita/${item.visitaId}`} size="sm">
              {item.visita}
            </Anchor>
          ) : (
            item.visita
          )}
        </Text>
      )}
      <Text size="xs" c={item.visitaId != null ? undefined : 'orange'} style={{ overflowWrap: 'anywhere' }}>
        {item.resultado ?? 'Sin resultado: falta continuar la confirmación.'}
      </Text>
    </Stack>
  );
}

function TextoEquipo({ item }: { item: ItemPropuesta }) {
  return (
    <>
      <Text size="sm">{item.lider ?? 'Sin auditor'}</Text>
      {item.otros.length > 0 && (
        <Text size="xs" c="dimmed">
          Con: {item.otros.map((o) => o.nombre).join(', ')}
        </Text>
      )}
    </>
  );
}

function FilaTabla(f: PropsFila) {
  const i = f.item;
  return (
    <Table.Tr data-testid={'fila-propuesta-' + i.puntoId}>
      <Table.Td maw={220}>
        <Text size="sm" fw={500} style={{ overflowWrap: 'anywhere' }}>
          {i.punto}
        </Text>
        <Text size="xs" c="dimmed">
          {i.unidad}
          {i.municipio ? ' · ' + i.municipio : ''}
        </Text>
      </Table.Td>
      {f.borrador ? (
        <>
          <Table.Td w={150}>
            <CampoFecha {...f} />
          </Table.Td>
          <Table.Td w={220}>
            <CampoLider {...f} />
          </Table.Td>
          <Table.Td w={260}>
            <CampoOtros {...f} />
          </Table.Td>
        </>
      ) : (
        <>
          <Table.Td style={{ whiteSpace: 'nowrap' }}>{i.fecha ? fecha(i.fecha) : 'Sin fecha'}</Table.Td>
          <Table.Td colSpan={2}>
            <TextoEquipo item={i} />
          </Table.Td>
        </>
      )}
      <Table.Td>{cifra(i.km)}</Table.Td>
      <Table.Td maw={260}>
        <MotivoYAlertas item={i} />
      </Table.Td>
      <Table.Td>{f.borrador ? <Candado {...f} /> : <Resultado item={i} verVisita={f.verVisita} />}</Table.Td>
      {f.borrador && (
        <Table.Td>
          <Button
            size="xs"
            aria-label={'Guardar el ajuste de ' + i.punto}
            disabled={f.ocupado || !filaCambiada(i, f.edicion)}
            loading={f.guardando}
            onClick={f.alGuardar}
          >
            Guardar ajuste
          </Button>
        </Table.Td>
      )}
    </Table.Tr>
  );
}

function TarjetaFila(f: PropsFila) {
  const i = f.item;
  return (
    <Card withBorder padding="sm" data-testid={'tarjeta-propuesta-' + i.puntoId}>
      <Stack gap={6}>
        <Group justify="space-between" wrap="nowrap" align="flex-start">
          <Stack gap={0} style={{ minWidth: 0 }}>
            <Text fw={600} size="sm" style={{ overflowWrap: 'anywhere' }}>
              {i.punto}
            </Text>
            <Text size="xs" c="dimmed">
              {i.unidad}
              {i.municipio ? ' · ' + i.municipio : ''}
              {i.km != null ? ` · ${cifra(i.km)} km` : ''}
            </Text>
          </Stack>
          {f.borrador && <Candado {...f} />}
        </Group>
        {f.borrador ? (
          <>
            <CampoFecha {...f} tarjeta />
            <CampoLider {...f} tarjeta />
            <CampoOtros {...f} tarjeta />
          </>
        ) : (
          <>
            <Text size="sm">Fecha: {i.fecha ? fecha(i.fecha) : 'Sin fecha'}</Text>
            <TextoEquipo item={i} />
          </>
        )}
        <MotivoYAlertas item={i} />
        {f.borrador ? (
          <Button mih={44} disabled={f.ocupado || !filaCambiada(i, f.edicion)} loading={f.guardando} onClick={f.alGuardar}>
            Guardar ajuste
          </Button>
        ) : (
          <Resultado item={i} verVisita={f.verVisita} />
        )}
      </Stack>
    </Card>
  );
}
