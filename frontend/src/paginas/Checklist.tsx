import {
  Accordion,
  Alert,
  Anchor,
  Badge,
  Box,
  Button,
  Card,
  Center,
  Checkbox,
  Group,
  List,
  Loader,
  Modal,
  Paper,
  Radio,
  ScrollArea,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  Textarea,
  TextInput,
  Title,
  VisuallyHidden,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';
import { colorEstado, horaCorta } from '../shared/agenda';
import { api, subirArchivos } from '../shared/api';
import { CampoHora24 } from '../shared/CampoHora24';
import {
  conCalificacion,
  conteo,
  cuerpoPlan,
  cumplimientoLegible,
  ENTIDADES,
  ESTADOS_ABIERTOS,
  ETIQUETA_CALIFICACION,
  erroresFechasPlan,
  faltantesPlan,
  faltantesPlanPorCampo,
  inmediataEfectiva,
  MIN_JUSTIFICACION,
  pideProrroga,
  planCambiado,
  respuestasCambiadas,
  ROLES_FIRMA,
  ROLES_FIRMA_PLAN,
  sinHallazgo,
  tamanoLegible,
  textoONulo,
  TEXTO_INMEDIATA,
  TEXTO_TRAMITE,
  valorFila,
  valorItem,
  type Archivo,
  type ArchivoAnterior,
  type Calificacion,
  type DatosFormulario,
  type Edicion,
  type EdicionPlan,
  type Ejecucion,
  type HallazgoAnterior,
  type ItemChecklist,
  type PersonaAuditada,
  type RolFirma,
} from '../shared/checklist';
import { fecha as fechaCorta, fechaHora } from '../shared/formato';
import { separarFechaHora, unirFechaHoraPorCampo } from '../shared/hora';
import { PanelFirma } from '../shared/PanelFirma';
import { puedeEntrar, useSesion } from '../shared/sesion';
import { EncabezadoPagina } from '../shared/EncabezadoPagina';
import { useValidacion } from '../shared/validacion';
import { errorCorreo } from '../shared/validaciones';
import { VisorDocumento } from '../shared/VisorDocumento';

interface Mensaje {
  mensaje: string;
}

interface Peticion {
  ruta: string;
  metodo: 'POST' | 'PUT' | 'DELETE';
  cuerpo?: unknown;
}

/** Inicio y fin real en fecha y hora militar separadas (decisión D4); al servidor van juntas como «yyyy-MM-ddTHH:mm». */
interface CamposEjecucion {
  inicioFecha: string;
  inicioHora: string;
  finFecha: string;
  finHora: string;
  objeto: string;
  recomendaciones: string;
  observacionesAuditor: string;
  observacionesEsm: string;
  observacionesOpl: string;
}

function camposIniciales(e: Ejecucion): CamposEjecucion {
  const inicio = separarFechaHora(e.inicioReal);
  const fin = separarFechaHora(e.finReal);
  return {
    inicioFecha: inicio.fecha,
    inicioHora: inicio.hora,
    finFecha: fin.fecha,
    finHora: fin.hora,
    objeto: e.objeto ?? '',
    recomendaciones: e.recomendaciones ?? '',
    observacionesAuditor: e.observacionesAuditor ?? '',
    observacionesEsm: e.observacionesEsm ?? '',
    observacionesOpl: e.observacionesOpl ?? '',
  };
}

/** Nombre visible de los campos de la ejecución (resumen de errores). */
const ETIQUETAS_EJECUCION = {
  inicioFecha: 'Fecha de inicio real',
  inicioHora: 'Hora de inicio real',
  finFecha: 'Fecha de fin real',
  finHora: 'Hora de fin real',
  objeto: 'Objeto de la visita',
};

/**
 * Cuerpo del PUT de la ejecución, o los errores por campo si el inicio o el fin real quedaron a medias, con una hora inválida o con el
 * fin antes del inicio (la misma regla del servidor).
 */
function cuerpoEjecucion(f: CamposEjecucion) {
  const inicio = unirFechaHoraPorCampo(f.inicioFecha, f.inicioHora, { fecha: 'inicioFecha', hora: 'inicioHora' });
  const fin = unirFechaHoraPorCampo(f.finFecha, f.finHora, { fecha: 'finFecha', hora: 'finHora' });
  const errores: Record<string, string> = { ...inicio.errores, ...fin.errores };
  // «yyyy-MM-ddTHH:mm» se compara como texto
  if (inicio.valor && fin.valor && fin.valor < inicio.valor) errores.finFecha = 'La fecha y hora de fin no puede ser anterior a la de inicio.';
  if (Object.keys(errores).length > 0) return { errores };
  return {
    inicioReal: inicio.valor || null,
    finReal: fin.valor || null,
    objeto: textoONulo(f.objeto),
    recomendaciones: textoONulo(f.recomendaciones),
    observacionesAuditor: textoONulo(f.observacionesAuditor),
    observacionesEsm: textoONulo(f.observacionesEsm),
    observacionesOpl: textoONulo(f.observacionesOpl),
  };
}

/**
 * Mutación con aviso del mensaje del servidor; refresca el formulario y la agenda. El error lo muestra quien llama (resumen persistente
 * de su sección, Anexo 5 num. 2.4).
 */
function usePeticion(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (p: Peticion) => api<Mensaje>(p.ruta, { metodo: p.metodo, cuerpo: p.cuerpo }),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      qc.invalidateQueries({ queryKey: ['formulario', id] });
      qc.invalidateQueries({ queryKey: ['agenda'] });
    },
  });
}

/** Formulario de ejecución de la visita: datos, personal auditado, checklist con evidencias, observaciones, firmas y acta. */
export function Checklist() {
  const { id = '' } = useParams();
  const s = useSesion();
  const formulario = useQuery({
    queryKey: ['formulario', id],
    queryFn: () => api<DatosFormulario>(`/api/visitas/${id}/formulario`),
    retry: false,
  });
  const volver = puedeEntrar(s, 'mis-visitas') ? `/mis-visitas/visita/${id}` : `/agenda/visita/${id}`;

  if (formulario.isLoading) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    );
  }
  if (formulario.error || !formulario.data) {
    return (
      <Stack>
        <Anchor component={Link} to={volver} size="sm">
          Volver a la visita
        </Anchor>
        <Alert color="red">{formulario.error?.message ?? 'No se encontró el formulario de la visita.'}</Alert>
      </Stack>
    );
  }
  return <Contenido key={id} id={id} d={formulario.data} volver={volver} />;
}

function Contenido({ id, d, volver }: { id: string; d: DatosFormulario; volver: string }) {
  const v = d.visita;
  const p = d.permisos;
  const [campos, setCampos] = useState<CamposEjecucion>(() => camposIniciales(d.ejecucion));
  const [ediciones, setEdiciones] = useState<Record<string, Edicion>>({});
  // 5W2H: solo lo editado (por clave del hallazgo); null en la fecha es «sin cambio»
  const [fechaPlan, setFechaPlan] = useState<string | null>(null);
  const [edicionesPlan, setEdicionesPlan] = useState<Record<string, EdicionPlan>>({});
  // Lo que falta del plan para el acta se marca como error solo después de intentar guardar el plan o enviar el acta
  const [planIntentado, setPlanIntentado] = useState(false);
  const guardarDatos = usePeticion(id);
  const guardarObservaciones = usePeticion(id);
  const valDatos = useValidacion({ etiquetas: ETIQUETAS_EJECUCION, campos: { inicioReal: 'inicioFecha', finReal: 'finFecha' }, testid: 'error-datos-visita' });
  const valObservaciones = useValidacion({ testid: 'error-observaciones' });
  const valRespuestas = useValidacion({
    etiquetas: Object.fromEntries(d.componentes.flatMap((c) => c.items.map((it) => ['hallazgo-' + it.codigo, 'Hallazgo de ' + it.codigo]))),
    testid: 'error-calificaciones',
  });
  // Los datos y las observaciones van en el mismo PUT: la fecha y hora a medias se marcan siempre en «Datos de la visita»
  const guardarEjecucion = (peticion: typeof guardarDatos, val: typeof valDatos) => {
    const cuerpo = cuerpoEjecucion(campos);
    if ('errores' in cuerpo) {
      if (val !== valDatos) val.reiniciar();
      valDatos.validar(cuerpo.errores ?? {});
      return;
    }
    valDatos.reiniciar();
    val.reiniciar();
    peticion.mutate(
      { ruta: `/api/visitas/${id}/ejecucion`, metodo: 'PUT', cuerpo },
      { onError: (e) => val.errorServidor(e, 'No se pudieron guardar los datos de la visita.') },
    );
  };
  const editarCampo = (cambio: Partial<CamposEjecucion>) => {
    setCampos((c) => ({ ...c, ...cambio }));
    for (const k of Object.keys(cambio)) valDatos.limpiar(k);
  };
  const qc = useQueryClient();

  const cambiadas = respuestasCambiadas(d.componentes, ediciones);
  const planSinGuardar = planCambiado(d.plan, fechaPlan, edicionesPlan);
  const sinGuardar = cambiadas.length > 0 || planSinGuardar || JSON.stringify(campos) !== JSON.stringify(camposIniciales(d.ejecucion));
  // Aviso del navegador al cerrar o recargar con cambios sin guardar
  useEffect(() => {
    if (!sinGuardar) return;
    const avisar = (e: BeforeUnloadEvent) => {
      e.preventDefault();
    };
    window.addEventListener('beforeunload', avisar);
    return () => window.removeEventListener('beforeunload', avisar);
  }, [sinGuardar]);
  const guardarRespuestas = useMutation({
    mutationFn: (respuestas: typeof cambiadas) =>
      api<Mensaje & { cambiados?: string[] }>(`/api/visitas/${id}/respuestas`, { metodo: 'PUT', cuerpo: { respuestas } }),
    onSuccess: (r, enviadas) => {
      notifications.show({ color: 'green', message: r.mensaje });
      valRespuestas.reiniciar();
      // Solo se descartan las ediciones que siguen iguales a lo enviado: lo escrito mientras se guardaba se conserva
      const enviadasPorItem = new Map(enviadas.map((x) => [x.item, x]));
      setEdiciones((ed) =>
        Object.fromEntries(
          Object.entries(ed).filter(([k, e]) => {
            const env = enviadasPorItem.get(k);
            return (
              !env ||
              (e.calificacion ?? null) !== (env.calificacion ?? null) ||
              (e.hallazgo ?? '').trim() !== (env.hallazgo ?? '').trim() ||
              (e.calificacion === 'NC' && e.enTramite) !== env.enTramite ||
              inmediataEfectiva(e) !== env.inmediata
            );
          }),
        ),
      );
      qc.invalidateQueries({ queryKey: ['formulario', id] });
      qc.invalidateQueries({ queryKey: ['agenda'] });
    },
    onError: (e) => valRespuestas.errorServidor(e, 'No se pudieron guardar las calificaciones.'),
  });

  // Componentes desplegados: al guardar se abren los que tienen un hallazgo por escribir (un panel plegado no recibe el foco)
  const [abiertos, setAbiertos] = useState<string[]>(() => d.componentes.map((c) => c.codigo));
  const enviarRespuestas = () => {
    if (cambiadas.length === 0) return;
    // «No cumple» sin hallazgo: cada ítem ya lo dice junto a su campo; el resumen los nombra y el foco va al primero
    const faltan = sinHallazgo(cambiadas);
    const conFalta = d.componentes.filter((c) => c.items.some((it) => faltan.includes(it.codigo))).map((c) => c.codigo);
    if (conFalta.some((c) => !abiertos.includes(c))) setAbiertos((a) => [...a, ...conFalta.filter((c) => !a.includes(c))]);
    if (!valRespuestas.validar(Object.fromEntries(faltan.map((c) => ['hallazgo-' + c, 'Escriba el hallazgo.'])))) return;
    guardarRespuestas.mutate(cambiadas);
  };

  const editar = (it: ItemChecklist, cambio: (e: Edicion) => Edicion) => setEdiciones((ed) => ({ ...ed, [it.codigo]: cambio(valorItem(it, ed)) }));

  const soloLectura = !p.escribeVisita;

  return (
    <Stack data-testid="formulario-visita" pb={cambiadas.length > 0 ? 'xl' : undefined}>
      <Anchor component={Link} to={volver} size="sm">
        Volver a la visita
      </Anchor>
      <EncabezadoPagina titulo={`Formulario de la visita ${v.numero}`} fuente="Anexo 5 · 4.5 · Anexo 3">
        <Badge color={colorEstado(v.estado)} size="lg" data-testid="estado-visita">
          {v.estadoNombre}
        </Badge>
        {v.ronda > 1 && (
          <Badge color="grape" size="lg" variant="light">
            Ronda {v.ronda} · reabierta
          </Badge>
        )}
      </EncabezadoPagina>
      {d.seguimiento && (
        <Alert color="blue" title={`${d.seguimiento.ordinal} visita de seguimiento al plan de mejoramiento`} data-testid="aviso-seguimiento">
          <Text size="sm">{d.seguimiento.aviso}</Text>
          <Text size="xs" c="dimmed" mt={4}>
            Visita de origen: {d.seguimiento.origenNumero} del {fechaCorta(d.seguimiento.origenFecha)}.
          </Text>
        </Alert>
      )}
      {v.ronda > 1 && p.escribible && (
        // Decisión D12: cada ronda pide firmas nuevas; las de la ronda anterior quedan en su acta
        <Alert color="grape" title={`Ronda ${v.ronda}: firme de nuevo el acta`} data-testid="aviso-ronda">
          La visita se reabrió. Las firmas de la ronda anterior quedan en su acta; para enviar el acta de esta ronda se necesitan otra vez todas las
          firmas{d.plan.hallazgos.length > 0 ? ', también las del plan de mejoramiento' : ''}.
        </Alert>
      )}
      {!p.escribible && <Alert color="yellow">{p.motivo ?? 'El formulario está en solo lectura.'}</Alert>}
      {p.correccion && <Alert color="blue">Corrección del coordinador: los cambios quedan en el historial de la visita.</Alert>}

      <Card ref={valDatos.ref} withBorder data-testid="datos-visita">
        <Title order={5} mb="xs">
          Datos de la visita
        </Title>
        {valDatos.resumen}
        <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="xs">
          <TextInput label="Número" readOnly value={v.numero} data-testid="campo-numero" />
          <Select
            label="Lugar"
            readOnly
            data={[{ value: 'punto', label: (v.sigla ? v.sigla + ' · ' : '') + v.lugar }]}
            value="punto"
            data-testid="campo-lugar"
          />
          <TextInput label="Municipio" readOnly value={[v.municipio, v.departamento].filter(Boolean).join(', ') || '—'} />
          <TextInput label="Unidad" readOnly value={[v.unidad, v.fuerza, v.modalidad].filter(Boolean).join(' · ')} />
          <TextInput label="Tipo" readOnly value={v.tipo} />
          <TextInput label="Auditor líder" readOnly value={v.lider ?? '—'} data-testid="campo-auditor" />
          <TextInput label="Equipo" readOnly value={v.equipo.map((m) => m.nombre).join(', ') || '—'} />
          <TextInput label="Fecha programada" readOnly value={v.fechaLarga + (v.hora ? ' · ' + horaCorta(v.hora) : '')} />
        </SimpleGrid>
        {v.direccion && (
          <Text size="xs" c="dimmed" mt="xs">
            Dirección: {v.direccion}
          </Text>
        )}
        <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="xs" mt="md">
          <TextInput
            label="Fecha de inicio real"
            type="date"
            readOnly={soloLectura}
            value={campos.inicioFecha}
            error={valDatos.error('inicioFecha')}
            onChange={(e) => editarCampo({ inicioFecha: e.currentTarget.value })}
          />
          <CampoHora24
            label="Hora de inicio real"
            readOnly={soloLectura}
            value={campos.inicioHora}
            error={valDatos.error('inicioHora')}
            onChange={(inicioHora) => editarCampo({ inicioHora })}
          />
          <TextInput
            label="Fecha de fin real"
            type="date"
            readOnly={soloLectura}
            value={campos.finFecha}
            error={valDatos.error('finFecha')}
            onChange={(e) => editarCampo({ finFecha: e.currentTarget.value })}
          />
          <CampoHora24
            label="Hora de fin real"
            readOnly={soloLectura}
            value={campos.finHora}
            error={valDatos.error('finHora')}
            onChange={(finHora) => editarCampo({ finHora })}
          />
        </SimpleGrid>
        <Textarea
          label="Objeto de la visita"
          mt="xs"
          autosize
          minRows={2}
          maxLength={2000}
          readOnly={soloLectura}
          value={campos.objeto}
          error={valDatos.error('objeto')}
          onChange={(e) => editarCampo({ objeto: e.currentTarget.value })}
        />
        {!soloLectura && (
          <Group justify="flex-end" mt="sm">
            <Button loading={guardarDatos.isPending} onClick={() => guardarEjecucion(guardarDatos, valDatos)}>
              Guardar datos de la visita
            </Button>
          </Group>
        )}
      </Card>

      <Personal id={id} inicial={d.personal} editable={p.escribeVisita} />

      <Stack ref={valRespuestas.ref} gap="xs">
        <Title order={4}>Componentes</Title>
        {d.componentes.length === 0 && (
          <Text size="sm" c="dimmed">
            La visita no tiene componentes del checklist.
          </Text>
        )}
        <Accordion multiple variant="separated" value={abiertos} onChange={setAbiertos}>
          {d.componentes.map((c) => {
            const n = conteo(c.items, ediciones);
            return (
              <Accordion.Item key={c.codigo} value={c.codigo} data-testid={'componente-' + c.codigo}>
                <Accordion.Control>
                  <Group gap="xs" wrap="wrap">
                    <Text fw={600} size="sm">
                      {c.items.every((i) => i.obligacion) ? 'Obligaciones del contrato' : `${c.codigo} · ${c.nombre}`}
                    </Text>
                    <Badge size="sm" variant="light" color={n.pendientes === 0 ? 'green' : 'gray'}>
                      C {n.c} · NC {n.nc} · NA {n.na} · pendientes {n.pendientes}
                    </Badge>
                  </Group>
                </Accordion.Control>
                <Accordion.Panel>
                  <Stack gap="sm">
                    {c.items.every((i) => i.obligacion) && (
                      <Text size="sm" c="dimmed">
                        Obligaciones de la minuta del prestador visitado (operador logístico o farmacia alterna). Un «No cumple» es un hallazgo y va
                        al plan de mejoramiento.
                      </Text>
                    )}
                    {c.items.map((it) => (
                      <Item key={it.codigo} id={id} it={it} valor={valorItem(it, ediciones)} alEditar={(x) => editar(it, x)} />
                    ))}
                  </Stack>
                </Accordion.Panel>
              </Accordion.Item>
            );
          })}
        </Accordion>
        {valRespuestas.resumen}
        {d.componentes.some((c) => c.items.some((i) => i.editable)) && (
          <Paper withBorder shadow="sm" p="sm" style={{ position: 'sticky', bottom: 8, zIndex: 5 }}>
            <Group justify="space-between" gap="xs">
              <Text size="sm" c={cambiadas.length > 0 ? 'orange' : 'dimmed'}>
                {cambiadas.length === 0
                  ? 'Sin cambios por guardar.'
                  : cambiadas.length === 1
                    ? '1 calificación sin guardar.'
                    : `${cambiadas.length} calificaciones sin guardar.`}
              </Text>
              <Button
                data-testid="guardar-calificaciones"
                disabled={cambiadas.length === 0}
                loading={guardarRespuestas.isPending}
                onClick={enviarRespuestas}
              >
                Guardar calificaciones
              </Button>
            </Group>
          </Paper>
        )}
      </Stack>

      <Card withBorder data-testid="soportes-visita">
        <Title order={5} mb="xs">
          Soportes de la visita
        </Title>
        <Evidencias id={id} clave="visita" archivos={d.archivosVisita} editable={p.escribeVisita} etiqueta="Soportes de la visita" testid="visita" />
      </Card>

      <Card ref={valObservaciones.ref} withBorder data-testid="observaciones-visita">
        <Title order={5} mb="xs">
          Observaciones y recomendaciones
        </Title>
        <Stack gap="xs">
          {valObservaciones.resumen}
          {(
            [
              ['recomendaciones', 'Recomendaciones'],
              ['observacionesAuditor', 'Observaciones del auditor'],
              ['observacionesEsm', 'Observaciones del ESM'],
              ['observacionesOpl', 'Observaciones del OPL'],
            ] as const
          ).map(([campo, etiqueta]) => (
            <Textarea
              key={campo}
              label={etiqueta}
              autosize
              minRows={2}
              maxLength={4000}
              readOnly={soloLectura}
              value={campos[campo]}
              onChange={(e) => editarCampo({ [campo]: e.currentTarget.value })}
            />
          ))}
          {!soloLectura && (
            <Group justify="flex-end">
              <Button loading={guardarObservaciones.isPending} onClick={() => guardarEjecucion(guardarObservaciones, valObservaciones)}>
                Guardar observaciones
              </Button>
            </Group>
          )}
        </Stack>
      </Card>

      <Firmas id={id} d={d} />
      {d.plan.hallazgos.length > 0 && (
        <PlanMejoramiento
          id={id}
          d={d}
          fecha={fechaPlan}
          ediciones={edicionesPlan}
          sinGuardar={planSinGuardar}
          intentado={planIntentado}
          alIntentar={() => setPlanIntentado(true)}
          alCambiarFecha={setFechaPlan}
          alEditar={(clave, e) => setEdicionesPlan((x) => ({ ...x, [clave]: e }))}
          alGuardar={(fechaEnviada, enviadas) => {
            // Solo se descarta lo que sigue igual a lo enviado: lo escrito mientras se guardaba se conserva
            setFechaPlan((f) => (f === fechaEnviada ? null : f));
            setEdicionesPlan((x) => Object.fromEntries(Object.entries(x).filter(([k, e]) => JSON.stringify(e) !== JSON.stringify(enviadas[k]))));
          }}
        />
      )}
      <Resumen d={d} />
      <Actas id={id} d={d} sinGuardar={sinGuardar} alFallar={() => setPlanIntentado(true)} />
    </Stack>
  );
}

function Personal({ id, inicial, editable }: { id: string; inicial: PersonaAuditada[]; editable: boolean }) {
  const [personas, setPersonas] = useState<PersonaAuditada[]>(inicial);
  const guardar = usePeticion(id);
  const val = useValidacion({
    etiquetas: Object.fromEntries(personas.flatMap((_, i) => [[`nombre-${i}`, `Nombre (persona ${i + 1})`], [`correo-${i}`, `Correo (persona ${i + 1})`]])),
    testid: 'error-personal',
  });
  const cambiar = (i: number, cambio: Partial<PersonaAuditada>) => {
    setPersonas(personas.map((x, j) => (j === i ? { ...x, ...cambio } : x)));
    for (const k of Object.keys(cambio)) val.limpiar(`${k}-${i}`);
  };
  // Las mismas reglas del servidor: nombre de al menos 3 caracteres y correo con formato válido (si se escribe)
  const guardarPersonal = () => {
    const errores: Record<string, string | null> = {};
    personas.forEach((x, i) => {
      const nombre = x.nombre.trim();
      errores[`nombre-${i}`] = nombre.length === 0 ? 'Escriba el nombre.' : nombre.length < 3 ? 'Escriba el nombre completo (mínimo 3 caracteres).' : null;
      const correo = (x.correo ?? '').trim();
      errores[`correo-${i}`] = errorCorreo(correo, 'Escriba un correo válido, p. ej. nombre@dominio.co.');
    });
    if (!val.validar(errores)) return;
    guardar.mutate(
      {
        ruta: `/api/visitas/${id}/personal`,
        metodo: 'PUT',
        cuerpo: {
          personas: personas.map((x) => ({
            entidad: x.entidad,
            nombre: x.nombre.trim(),
            cargo: textoONulo(x.cargo ?? ''),
            documento: textoONulo(x.documento ?? ''),
            correo: textoONulo(x.correo ?? ''),
          })),
        },
      },
      { onError: (e) => val.errorServidor(e, 'No se pudo guardar el personal auditado.') },
    );
  };

  return (
    <Card withBorder data-testid="personal-auditado">
      <Title order={5} mb="xs">
        Personal auditado
      </Title>
      {!editable ? (
        inicial.length === 0 ? (
          <Text size="sm" c="dimmed">
            Sin personal registrado.
          </Text>
        ) : (
          <ScrollArea type="auto">
            <Table miw={560}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Entidad</Table.Th>
                  <Table.Th>Nombre</Table.Th>
                  <Table.Th>Cargo</Table.Th>
                  <Table.Th>Documento</Table.Th>
                  <Table.Th>Correo</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {inicial.map((x, i) => (
                  <Table.Tr key={i}>
                    <Table.Td>{x.entidad}</Table.Td>
                    <Table.Td>{x.nombre}</Table.Td>
                    <Table.Td>{x.cargo ?? ''}</Table.Td>
                    <Table.Td>{x.documento ?? ''}</Table.Td>
                    <Table.Td>{x.correo ?? ''}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollArea>
        )
      ) : (
        <Stack ref={val.ref} gap="sm">
          {val.resumen}
          {personas.length === 0 && (
            <Text size="sm" c="dimmed">
              Agregue a las personas que atendieron la visita.
            </Text>
          )}
          {personas.map((x, i) => (
            <Paper key={i} withBorder p="xs" data-testid={'persona-' + i}>
              <SimpleGrid cols={{ base: 1, xs: 2, md: 5 }} spacing="xs">
                <Select
                  label="Entidad"
                  data={ENTIDADES}
                  allowDeselect={false}
                  value={x.entidad}
                  onChange={(e) => cambiar(i, { entidad: e ?? 'ESM' })}
                />
                <TextInput
                  label="Nombre"
                  required
                  maxLength={150}
                  value={x.nombre}
                  error={val.error(`nombre-${i}`)}
                  onChange={(e) => cambiar(i, { nombre: e.currentTarget.value })}
                />
                <TextInput label="Cargo" maxLength={120} value={x.cargo ?? ''} onChange={(e) => cambiar(i, { cargo: e.currentTarget.value })} />
                <TextInput
                  label="Documento"
                  maxLength={30}
                  value={x.documento ?? ''}
                  onChange={(e) => cambiar(i, { documento: e.currentTarget.value })}
                />
                <TextInput
                  label="Correo"
                  type="email"
                  value={x.correo ?? ''}
                  error={val.error(`correo-${i}`)}
                  onChange={(e) => cambiar(i, { correo: e.currentTarget.value })}
                />
              </SimpleGrid>
              <Group justify="flex-end" mt={6}>
                <Button
                  size="xs"
                  variant="subtle"
                  color="red"
                  onClick={() => {
                    setPersonas(personas.filter((_, j) => j !== i));
                    val.reiniciar(); // los errores van por posición: al quitar una persona se corren
                  }}
                >
                  Quitar
                </Button>
              </Group>
            </Paper>
          ))}
          <Group justify="space-between">
            <Button
              variant="light"
              disabled={personas.length >= 30}
              onClick={() => setPersonas([...personas, { entidad: 'ESM', nombre: '', cargo: '', documento: '', correo: '' }])}
            >
              Agregar persona
            </Button>
            <Button loading={guardar.isPending} onClick={guardarPersonal}>
              Guardar personal
            </Button>
          </Group>
        </Stack>
      )}
    </Card>
  );
}

function Item({ id, it, valor, alEditar }: { id: string; it: ItemChecklist; valor: Edicion; alEditar: (cambio: (e: Edicion) => Edicion) => void }) {
  const opciones: Calificacion[] = ['C', 'NC', 'NA'];
  const faltaHallazgo = valor.calificacion === 'NC' && valor.hallazgo.trim().length === 0;
  return (
    <Paper withBorder p="sm" data-testid={'item-' + it.codigo}>
      <Group gap="xs" wrap="wrap">
        <Text size="sm" fw={700}>
          {it.codigo}
          {it.obligacion ? '' : ' · ' + it.punto}
        </Text>
        {it.obligacion && (
          <Badge size="sm" variant="light" color="grape">
            Obligación del contrato
          </Badge>
        )}
      </Group>
      {it.obligacion && (
        <Text size="sm" mt={2} style={{ whiteSpace: 'pre-wrap' }}>
          {it.punto}
        </Text>
      )}
      {it.criterio && !it.obligacion && (
        <Text size="sm" mt={2} style={{ whiteSpace: 'pre-wrap' }}>
          {it.criterio}
        </Text>
      )}
      {it.obligacion && it.evidencia && (
        <Text size="xs" c="dimmed" mt={2}>
          Fuente: {it.evidencia}
        </Text>
      )}
      {!it.obligacion && (it.requisito || it.evidencia || it.condicion) && (
        <Box component="details" mt={4}>
          <Text component="summary" size="sm" c="blue" style={{ cursor: 'pointer' }}>
            Requisito y evidencia
          </Text>
          <Stack gap={4} mt={4}>
            {it.requisito && (
              <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
                <Text span fw={500}>
                  Requisito:{' '}
                </Text>
                {it.requisito}
              </Text>
            )}
            {it.evidencia && (
              <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
                <Text span fw={500}>
                  Evidencia:{' '}
                </Text>
                {it.evidencia}
              </Text>
            )}
            {it.condicion && (
              <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
                <Text span fw={500}>
                  Condición:{' '}
                </Text>
                {it.condicion}
              </Text>
            )}
          </Stack>
        </Box>
      )}
      <Text size="xs" c="dimmed" mt={4}>
        Responsable: {it.responsable ?? '—'}
      </Text>
      {it.anterior && <HallazgoDeAntes id={id} codigo={it.codigo} anterior={it.anterior} archivos={it.archivosAnteriores ?? []} />}
      <Radio.Group
        mt="xs"
        label={<VisuallyHidden>{'Calificación de ' + it.codigo}</VisuallyHidden>}
        data-testid={'calificacion-' + it.codigo}
        value={valor.calificacion ?? ''}
        onChange={(x) => alEditar((e) => conCalificacion(e, (x || null) as Calificacion | null))}
        readOnly={!it.editable}
      >
        <Group gap="md">
          {opciones.map((o) => (
            <Radio key={o} value={o} label={ETIQUETA_CALIFICACION[o]} disabled={!it.editable || (o === 'NA' && !it.admiteNoAplica)} />
          ))}
        </Group>
      </Radio.Group>
      {!it.admiteNoAplica && (
        <Text size="xs" c="dimmed" mt={2}>
          No aplica solo con condición.
        </Text>
      )}
      {it.editable && valor.calificacion && (
        <Button size="compact-xs" variant="subtle" mt={2} onClick={() => alEditar((e) => conCalificacion(e, null))}>
          Quitar calificación
        </Button>
      )}
      {valor.calificacion === 'NC' && (
        <Textarea
          label="Hallazgo"
          aria-label={'Hallazgo de ' + it.codigo}
          data-testid={'hallazgo-' + it.codigo}
          required
          mt="xs"
          autosize
          minRows={2}
          maxLength={2000}
          readOnly={!it.editable}
          error={it.editable && faltaHallazgo ? 'Escriba el hallazgo.' : undefined}
          value={valor.hallazgo}
          onChange={(e) => {
            const hallazgo = e.currentTarget.value;
            alEditar((x) => ({ ...x, hallazgo }));
          }}
        />
      )}
      {it.admiteTramite && valor.calificacion === 'NC' && (
        // Decisión D5: sigue siendo hallazgo; la casilla solo deja constancia de que la habilitación está en trámite
        <Box mt="xs" data-testid={'tramite-' + it.codigo}>
          <Checkbox
            label="Habilitación en trámite"
            description={TEXTO_TRAMITE}
            disabled={!it.editable}
            checked={valor.enTramite}
            onChange={(e) => {
              const enTramite = e.currentTarget.checked;
              alEditar((x) => ({ ...x, enTramite }));
            }}
          />
        </Box>
      )}
      {valor.calificacion === 'NC' &&
        (it.editable ? (
          // Fase 9: factor «intervención inmediata» del puntaje de riesgo; el servidor la quita si la calificación deja de ser «No cumple»
          <Box mt="xs" data-testid={'marca-inmediata-' + it.codigo}>
            <Checkbox
              label={TEXTO_INMEDIATA}
              color="red"
              checked={!!valor.inmediata}
              onChange={(e) => {
                const inmediata = e.currentTarget.checked;
                alEditar((x) => ({ ...x, inmediata }));
              }}
            />
          </Box>
        ) : (
          valor.inmediata && (
            <Badge mt="xs" color="red" variant="light" data-testid={'marca-inmediata-' + it.codigo} style={{ whiteSpace: 'normal', height: 'auto' }}>
              {TEXTO_INMEDIATA}
            </Badge>
          )
        ))}
      <Box mt="xs">
        <Evidencias
          id={id}
          clave={it.clave ?? 'it:' + it.codigo}
          archivos={it.archivos}
          editable={it.editable}
          etiqueta={'Evidencias de ' + it.codigo}
          testid={it.codigo}
        />
      </Box>
    </Paper>
  );
}

/**
 * En el seguimiento: el hallazgo de la visita de origen y su acción del plan, en solo lectura, con los soportes de entonces para
 * descargar (decisiones 6.5 y 6.6). Se califica de nuevo desde cero.
 */
function HallazgoDeAntes({
  id,
  codigo,
  anterior,
  archivos,
}: {
  id: string;
  codigo: string;
  anterior: HallazgoAnterior;
  archivos: ArchivoAnterior[];
}) {
  return (
    <Paper withBorder p="xs" mt="xs" bg="var(--mantine-color-gray-0)" data-testid={'anterior-' + codigo}>
      <Text size="sm" fw={600}>
        Hallazgo de la visita anterior
      </Text>
      <Text size="sm" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
        {anterior.hallazgo ?? '—'}
      </Text>
      <Text size="xs" c="dimmed" mt={4} style={{ overflowWrap: 'anywhere' }}>
        Acción del plan: {anterior.que ?? '—'} · Fin: {fechaCorta(anterior.fin)} · Estado: {anterior.estado}
      </Text>
      <Text size="xs" fw={500} mt={6}>
        Soportes de la visita anterior
      </Text>
      {archivos.length === 0 ? (
        <Text size="xs" c="dimmed">
          Sin soportes.
        </Text>
      ) : (
        <List size="sm" spacing={2} data-testid={'anteriores-' + codigo}>
          {archivos.map((a) => (
            <List.Item key={a.id}>
              <Group gap={6} wrap="wrap">
                <VisorDocumento url={`/api/visitas/${id}/anteriores/${a.id}`} nombre={a.nombre}>
                  {a.nombre}
                </VisorDocumento>
                <Text size="xs" c="dimmed">
                  {tamanoLegible(a.tamano)}
                </Text>
              </Group>
            </List.Item>
          ))}
        </List>
      )}
    </Paper>
  );
}

/** Lista de archivos con enlace, "Quitar" y carga múltiple en una sola petición. */
function Evidencias({
  id,
  clave,
  archivos,
  editable,
  etiqueta,
  testid,
}: {
  id: string;
  clave: string;
  archivos: Archivo[];
  editable: boolean;
  etiqueta: string;
  testid: string;
}) {
  const qc = useQueryClient();
  const entrada = useRef<HTMLInputElement>(null);
  const [elegidos, setElegidos] = useState<File[]>([]);
  const quitar = usePeticion(id);
  const val = useValidacion({ testid: 'error-evidencias-' + testid });
  const subir = useMutation({
    mutationFn: (files: File[]) => subirArchivos<Mensaje>(`/api/visitas/${id}/archivos`, { clave }, files),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      val.reiniciar();
      setElegidos([]);
      if (entrada.current) entrada.current.value = '';
      qc.invalidateQueries({ queryKey: ['formulario', id] });
    },
    onError: (e) => val.errorServidor(e, 'No se pudieron subir los archivos.'),
  });

  return (
    <Stack ref={val.ref} gap={4} data-testid={'evidencias-' + testid}>
      {val.resumen}
      {archivos.length === 0 ? (
        <Text size="xs" c="dimmed">
          Sin archivos.
        </Text>
      ) : (
        <List size="sm" spacing={2}>
          {archivos.map((a) => (
            <List.Item key={a.id}>
              <Group gap={6} wrap="wrap">
                <VisorDocumento url={`/api/archivos/${a.id}`} nombre={a.nombre}>
                  {a.nombre}
                </VisorDocumento>
                <Text size="xs" c="dimmed">
                  {tamanoLegible(a.tamano)}
                  {a.subidoPor ? ' · ' + a.subidoPor : ''}
                </Text>
                {editable && (
                  <Button
                    size="compact-xs"
                    variant="subtle"
                    color="red"
                    aria-label={'Quitar ' + a.nombre}
                    disabled={quitar.isPending}
                    onClick={() =>
                      window.confirm(`¿Quitar el archivo ${a.nombre}?`) &&
                      quitar.mutate(
                        { ruta: `/api/archivos/${a.id}`, metodo: 'DELETE' },
                        { onSuccess: () => val.reiniciar(), onError: (e) => val.errorServidor(e, 'No se pudo quitar el archivo.') },
                      )
                    }
                  >
                    Quitar
                  </Button>
                )}
              </Group>
            </List.Item>
          ))}
        </List>
      )}
      {editable && (
        <Group gap="xs" wrap="wrap" align="center">
          <input
            ref={entrada}
            type="file"
            multiple
            aria-label={etiqueta}
            style={{ maxWidth: '100%', fontSize: 'var(--mantine-font-size-xs)' }}
            onChange={(e) => {
              setElegidos(Array.from(e.currentTarget.files ?? []));
              val.reiniciar();
            }}
          />
          <Button
            size="xs"
            variant="light"
            data-testid={'subir-' + testid}
            loading={subir.isPending}
            onClick={() => {
              if (elegidos.length === 0) {
                val.alerta('Elija uno o más archivos para subir.');
                return;
              }
              subir.mutate(elegidos);
            }}
          >
            Subir
          </Button>
        </Group>
      )}
      {editable && (
        <Text size="xs" c="dimmed">
          Hasta 20 archivos por carga; 25 MB cada uno (videos 100 MB).
        </Text>
      )}
    </Stack>
  );
}

function Firmas({ id, d }: { id: string; d: DatosFormulario }) {
  return (
    <Card withBorder data-testid="firmas">
      <Title order={5} mb="xs">
        Firmas{d.visita.ronda > 1 ? ` · ronda ${d.visita.ronda}` : ''}
      </Title>
      <GrupoFirmas id={id} d={d} roles={ROLES_FIRMA} />
    </Card>
  );
}

/** Recuadros de firma de unos roles (las del acta o las del plan de mejoramiento), con el diálogo para firmar. */
function GrupoFirmas({ id, d, roles }: { id: string; d: DatosFormulario; roles: { rol: RolFirma; titulo: string }[] }) {
  const [rol, setRol] = useState<RolFirma | null>(null);
  const puedeFirmar = d.permisos.escribeVisita && !d.permisos.correccion;
  const titulo = roles.find((r) => r.rol === rol)?.titulo ?? '';
  return (
    <>
      <SimpleGrid cols={{ base: 1, sm: roles.length }} spacing="xs">
        {roles.map((r) => {
          const f = d.firmas.find((x) => x.rol === r.rol);
          return (
            <Paper key={r.rol} withBorder p="sm" data-testid={'firma-' + r.rol}>
              <Text size="sm" fw={600}>
                {r.titulo}
              </Text>
              {f?.firmado ? (
                <>
                  <Text size="sm">{f.nombre}</Text>
                  <Text size="xs" c="dimmed">
                    {[f.cargo, f.documento].filter(Boolean).join(' · ')}
                  </Text>
                  <Badge size="sm" color="green" variant="light" mt={4}>
                    Firmado {fechaHora(f.firmado)}
                  </Badge>
                </>
              ) : (
                <Text size="sm" c="dimmed">
                  Sin firma.
                </Text>
              )}
              {puedeFirmar && (
                <Button size="xs" variant="light" mt="xs" onClick={() => setRol(r.rol)}>
                  Firmar
                </Button>
              )}
            </Paper>
          );
        })}
      </SimpleGrid>
      <Modal opened={rol !== null} onClose={() => setRol(null)} title={'Firma · ' + titulo}>
        {rol && (
          <FormFirma
            id={id}
            rol={rol}
            nombreInicial={d.firmas.find((x) => x.rol === rol)?.nombre ?? (rol === 'auditor' || rol === 'w5_auditor' ? (d.visita.lider ?? '') : '')}
            alCerrar={() => setRol(null)}
          />
        )}
      </Modal>
    </>
  );
}

/**
 * Plan de mejoramiento 5W2H, parte del auditor (Anexo 3 p.11): fecha del plan y una fila por hallazgo (causas, qué, por qué, dónde,
 * inicio, fin, indicador y estado), con sus dos firmas. Quién y cómo los diligencia el OPL después, por el enlace del acta.
 */
function PlanMejoramiento({
  id,
  d,
  fecha,
  ediciones,
  sinGuardar,
  intentado,
  alIntentar,
  alCambiarFecha,
  alEditar,
  alGuardar,
}: {
  id: string;
  d: DatosFormulario;
  fecha: string | null;
  ediciones: Record<string, EdicionPlan>;
  sinGuardar: boolean;
  intentado: boolean;
  alIntentar: () => void;
  alCambiarFecha: (f: string) => void;
  alEditar: (clave: string, e: EdicionPlan) => void;
  alGuardar: (fecha: string | null, enviadas: Record<string, EdicionPlan>) => void;
}) {
  const qc = useQueryClient();
  const plan = d.plan;
  const editable = d.permisos.escribeVisita;
  const val = useValidacion({
    etiquetas: {
      fecha: 'Fecha del plan',
      ...Object.fromEntries(
        plan.hallazgos.flatMap((h) => [
          ['que:' + h.clave, `Qué de ${h.codigo}`],
          ['fin:' + h.clave, `Fin de ${h.codigo}`],
          ['prorroga:' + h.clave, `Justificación de la prórroga de ${h.codigo}`],
        ]),
      ),
    },
    testid: 'error-plan',
  });
  const guardar = useMutation({
    mutationFn: (x: { fecha: string | null; enviadas: Record<string, EdicionPlan> }) =>
      api<Mensaje>(`/api/visitas/${id}/plan`, { metodo: 'PUT', cuerpo: cuerpoPlan(plan, x.fecha, x.enviadas) }),
    onSuccess: (r, x) => {
      notifications.show({ color: 'green', message: r.mensaje });
      val.reiniciar();
      alIntentar();
      alGuardar(x.fecha, x.enviadas);
      qc.invalidateQueries({ queryKey: ['formulario', id] });
    },
    onError: (e) => {
      alIntentar();
      val.errorServidor(e, 'No se pudo guardar el plan de mejoramiento.');
    },
  });
  const faltan = faltantesPlan(plan, fecha, ediciones);
  // Lo que falta para enviar el acta, junto a cada campo (no impide guardar el plan): en tono neutro (descripción) hasta el primer intento
  // de guardar el plan o de enviar el acta, y como error después. Un error de la validación tiene prioridad (y se lleva el foco).
  const porCampo = faltantesPlanPorCampo(plan, fecha, ediciones);
  const errorDe = (campo: string) => val.error(campo) ?? (editable && intentado ? porCampo[campo] : undefined);
  const ayuda = (campo: string, base?: string): ReactNode => {
    const falta = editable && !intentado && val.error(campo) === undefined ? porCampo[campo] : undefined;
    if (!falta) return base;
    return base ? (
      <>
        {base}
        <br />
        {falta}
      </>
    ) : (
      falta
    );
  };
  const estados = plan.estados.map((e) => ({ value: e, label: e }));

  return (
    <Card ref={val.ref} withBorder data-testid="plan-mejoramiento">
      <Title order={5} mb={4}>
        Plan de mejoramiento (5W2H)
      </Title>
      <Text size="sm" c="dimmed" mb="xs">
        Una fila por hallazgo. Quién y cómo los diligencia el operador logístico con el enlace del acta. Para enviar el acta se necesitan la fecha del
        plan, el qué de cada fila, la fecha fin de las acciones abiertas y las dos firmas.
        {d.seguimiento &&
          ' En el seguimiento el plan parte del anterior: si una acción sigue abierta y su fecha fin pasa a ser posterior a la anterior, es una prórroga y hay que justificarla (la aprueban la coordinación y la DIGSA).'}
      </Text>
      <TextInput
        label="Fecha del plan"
        type="date"
        maw={220}
        required
        readOnly={!editable}
        value={fecha ?? plan.fecha ?? ''}
        description={ayuda('fecha')}
        error={errorDe('fecha')}
        onChange={(e) => {
          alCambiarFecha(e.currentTarget.value);
          val.limpiar('fecha');
        }}
      />
      <Stack gap="sm" mt="sm">
        {plan.hallazgos.map((h) => {
          const v = valorFila(h, ediciones);
          const cambiar = (c: Partial<EdicionPlan>) => {
            alEditar(h.clave, { ...v, ...c });
            for (const k of Object.keys(c)) val.limpiar(`${k === 'inicio' ? 'fin' : k}:${h.clave}`);
          };
          const prorroga = pideProrroga(h, ediciones);
          return (
            <Paper key={h.clave} withBorder p="sm" data-testid={'plan-' + h.codigo}>
              <Group gap="xs" wrap="wrap">
                <Text size="sm" fw={700}>
                  {h.codigo}
                </Text>
                {h.origen === 'obligacion' && (
                  <Badge size="sm" variant="light" color="grape">
                    Obligación del contrato
                  </Badge>
                )}
                {h.sinCalificar && (
                  <Badge size="sm" variant="light" color="gray">
                    Por recalificar
                  </Badge>
                )}
              </Group>
              <Text size="sm" lineClamp={3}>
                {h.texto}
              </Text>
              <Text size="xs" c="dimmed" mb="xs" style={{ whiteSpace: 'pre-wrap' }}>
                Hallazgo: {h.hallazgo ?? (h.sinCalificar ? 'aún sin recalificar en este seguimiento' : '—')}
              </Text>
              {d.seguimiento && (
                // Quién y cómo los remitió el OPL: el auditor no los cambia (solo lectura)
                <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="xs" mb="xs" data-testid={'del-opl-' + h.codigo}>
                  <TextInput label="Quién (del OPL)" readOnly value={h.fila.quien ?? '—'} />
                  <TextInput label="Cómo (del OPL)" readOnly value={h.fila.como ?? '—'} />
                  <TextInput label="Fin anterior" readOnly value={h.finAnterior ?? '—'} />
                </SimpleGrid>
              )}
              <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="xs">
                <Textarea
                  label="Causas"
                  autosize
                  minRows={2}
                  maxLength={2000}
                  readOnly={!editable}
                  value={v.causas}
                  onChange={(e) => cambiar({ causas: e.currentTarget.value })}
                />
                <Textarea
                  label="Qué"
                  description={ayuda('que:' + h.clave, 'Acción de mejora')}
                  required
                  autosize
                  minRows={2}
                  maxLength={2000}
                  readOnly={!editable}
                  error={errorDe('que:' + h.clave)}
                  value={v.que}
                  onChange={(e) => cambiar({ que: e.currentTarget.value })}
                />
                <Textarea
                  label="Por qué"
                  autosize
                  minRows={2}
                  maxLength={2000}
                  readOnly={!editable}
                  value={v.porque}
                  onChange={(e) => cambiar({ porque: e.currentTarget.value })}
                />
                <TextInput
                  label="Dónde"
                  maxLength={500}
                  readOnly={!editable}
                  value={v.donde}
                  onChange={(e) => cambiar({ donde: e.currentTarget.value })}
                />
                <TextInput
                  label="Inicio"
                  type="date"
                  readOnly={!editable}
                  value={v.inicio}
                  onChange={(e) => cambiar({ inicio: e.currentTarget.value })}
                />
                <TextInput
                  label="Fin"
                  type="date"
                  required={ESTADOS_ABIERTOS.includes(v.estado)}
                  min={v.inicio || undefined}
                  readOnly={!editable}
                  description={ayuda('fin:' + h.clave)}
                  error={errorDe('fin:' + h.clave)}
                  value={v.fin}
                  onChange={(e) => cambiar({ fin: e.currentTarget.value })}
                />
                <Textarea
                  label="Indicador"
                  description={plan.ayudaIndicador}
                  autosize
                  minRows={2}
                  maxLength={1000}
                  readOnly={!editable}
                  value={v.indicador}
                  onChange={(e) => cambiar({ indicador: e.currentTarget.value })}
                />
                <Select
                  label="Estado"
                  data={estados}
                  allowDeselect={false}
                  readOnly={!editable}
                  value={v.estado}
                  onChange={(x) => cambiar({ estado: x ?? 'Pendiente' })}
                />
              </SimpleGrid>
              {prorroga && (
                <Textarea
                  label="Justificación de la prórroga"
                  description={ayuda(
                    'prorroga:' + h.clave,
                    `La fecha fin pasa del ${fechaCorta(h.finAnterior)} al ${fechaCorta(v.fin)}. Mínimo ${MIN_JUSTIFICACION} caracteres; queda solicitada hasta que la aprueben la coordinación y la DIGSA.`,
                  )}
                  data-testid={'prorroga-' + h.codigo}
                  required
                  mt="xs"
                  autosize
                  minRows={2}
                  maxLength={2000}
                  readOnly={!editable}
                  error={errorDe('prorroga:' + h.clave)}
                  value={v.prorroga}
                  onChange={(e) => cambiar({ prorroga: e.currentTarget.value })}
                />
              )}
            </Paper>
          );
        })}
      </Stack>
      {faltan.length > 0 && (
        // Resumen de lo que falta para enviar el acta (cada campo lo dice junto a él); el plan se puede guardar igual
        <Text size="sm" c="dimmed" mt="xs" data-testid="faltantes-plan">
          Para enviar el acta falta {faltan.join('; ')}.
        </Text>
      )}
      {editable && <Box mt="sm">{val.resumen}</Box>}
      {editable && (
        <Group justify="space-between" mt="sm">
          <Text size="sm" c={sinGuardar ? 'orange' : 'dimmed'}>
            {sinGuardar ? 'El plan tiene cambios sin guardar.' : 'Sin cambios por guardar en el plan.'}
          </Text>
          <Button
            data-testid="guardar-plan"
            disabled={!sinGuardar}
            loading={guardar.isPending}
            onClick={() => {
              // Solo bloquea lo que el servidor rechaza (fin antes del inicio); lo que falta para el acta no impide guardar
              if (!val.validar(erroresFechasPlan(plan, ediciones))) return;
              guardar.mutate({ fecha, enviadas: ediciones });
            }}
          >
            Guardar plan
          </Button>
        </Group>
      )}
      <Title order={6} mt="md" mb="xs">
        Firmas del plan de mejoramiento
      </Title>
      <GrupoFirmas id={id} d={d} roles={ROLES_FIRMA_PLAN} />
    </Card>
  );
}

function FormFirma({ id, rol, nombreInicial, alCerrar }: { id: string; rol: RolFirma; nombreInicial: string; alCerrar: () => void }) {
  const [nombre, setNombre] = useState(nombreInicial);
  const [documento, setDocumento] = useState('');
  const [cargo, setCargo] = useState('');
  const [imagen, setImagen] = useState<string | null>(null);
  const guardar = usePeticion(id);
  const val = useValidacion({ etiquetas: { nombre: 'Nombre', imagen: 'Firma' }, testid: 'error-firma' });
  return (
    <form
      ref={val.ref}
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        const n = nombre.trim();
        // Las mismas reglas del servidor: nombre de al menos 3 caracteres y la firma dibujada
        const ok = val.validar({
          nombre: n.length === 0 ? 'Escriba el nombre de quien firma.' : n.length < 3 ? 'Escriba el nombre completo (mínimo 3 caracteres).' : null,
          imagen: imagen ? null : 'Falta la firma: dibújela dentro del recuadro.',
        });
        if (!ok || !imagen) return;
        guardar.mutate(
          {
            ruta: `/api/visitas/${id}/firmas`,
            metodo: 'POST',
            cuerpo: { rol, nombre: n, documento: textoONulo(documento), cargo: textoONulo(cargo), imagen },
          },
          { onSuccess: alCerrar, onError: (x) => val.errorServidor(x, 'No se pudo guardar la firma.') },
        );
      }}
    >
      <Stack gap="xs">
        {val.resumen}
        <TextInput
          label="Nombre"
          required
          minLength={3}
          maxLength={150}
          value={nombre}
          error={val.error('nombre')}
          onChange={(e) => {
            setNombre(e.currentTarget.value);
            val.limpiar('nombre');
          }}
        />
        <TextInput label="Documento" maxLength={30} value={documento} onChange={(e) => setDocumento(e.currentTarget.value)} />
        <TextInput label="Cargo" maxLength={120} value={cargo} onChange={(e) => setCargo(e.currentTarget.value)} />
        <PanelFirma
          error={typeof val.error('imagen') === 'string' ? (val.error('imagen') as string) : undefined}
          alCambiar={(png) => {
            setImagen(png);
            if (png) val.limpiar('imagen');
          }}
        />
        <Group justify="flex-end">
          <Button variant="default" onClick={alCerrar}>
            Volver
          </Button>
          <Button type="submit" loading={guardar.isPending}>
            Guardar firma
          </Button>
        </Group>
      </Stack>
    </form>
  );
}

function Cifra({ titulo, children }: { titulo: string; children: ReactNode }) {
  return (
    <div>
      <Text size="xs" c="dimmed">
        {titulo}
      </Text>
      <Text fw={600}>{children}</Text>
    </div>
  );
}

function Resumen({ d }: { d: DatosFormulario }) {
  const r = d.resumen;
  const cumplimiento = cumplimientoLegible(r.cumplimiento);
  return (
    <Card withBorder data-testid="resumen-visita">
      <Title order={5} mb="xs">
        Resumen
      </Title>
      <SimpleGrid cols={{ base: 3, sm: 6 }} spacing="xs">
        <Cifra titulo="Cumple">{r.c}</Cifra>
        <Cifra titulo="No cumple">{r.nc}</Cifra>
        <Cifra titulo="No aplica">{r.na}</Cifra>
        <Cifra titulo="Pendientes">{r.pendientes}</Cifra>
        <Cifra titulo="Hallazgos">{r.hallazgos}</Cifra>
        <Cifra titulo="Cumplimiento">{cumplimiento ?? '—'}</Cifra>
      </SimpleGrid>
      {d.faltantes.length > 0 && (
        <Alert color="orange" title="Antes de enviar el acta" mt="sm" data-testid="faltantes">
          <List size="sm" spacing={2}>
            {d.faltantes.map((f, i) => (
              <List.Item key={i}>{f}</List.Item>
            ))}
          </List>
        </Alert>
      )}
    </Card>
  );
}

function Actas({ id, d, sinGuardar, alFallar }: { id: string; d: DatosFormulario; sinGuardar: boolean; alFallar: () => void }) {
  const qc = useQueryClient();
  const idAyuda = useId();
  const val = useValidacion({ testid: 'error-acta' });
  const enviar = useMutation({
    mutationFn: () => api<{ ok: boolean; mensaje: string; ronda: number }>(`/api/visitas/${id}/acta`, { metodo: 'POST', cuerpo: {} }),
    onSuccess: (r) => {
      val.reiniciar();
      notifications.show({ color: r.ok ? 'green' : 'orange', autoClose: r.ok ? 6000 : false, message: r.mensaje });
      qc.invalidateQueries({ queryKey: ['formulario', id] });
      qc.invalidateQueries({ queryKey: ['agenda'] });
      qc.invalidateQueries({ queryKey: ['visita'] });
    },
    onError: (e) => {
      alFallar();
      val.errorServidor(e, 'No se pudo enviar el acta.');
      qc.invalidateQueries({ queryKey: ['formulario', id] });
    },
  });
  // El acta se genera con lo guardado: con cambios pendientes no se envía (se perderían al cerrar la visita)
  const bloqueado = d.faltantes.length > 0 || sinGuardar;
  return (
    <Card ref={val.ref} withBorder data-testid="actas">
      <Title order={5} mb="xs">
        Acta
      </Title>
      {val.resumen}
      {d.actas.length === 0 ? (
        <Text size="sm" c="dimmed">
          Aún no se ha generado el acta.
        </Text>
      ) : (
        <ScrollArea type="auto">
          <Table miw={520}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Ronda</Table.Th>
                <Table.Th>Estado</Table.Th>
                <Table.Th>Generada</Table.Th>
                <Table.Th>Destinatarios</Table.Th>
                <Table.Th>Archivo</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {d.actas.map((a) => (
                <Table.Tr key={a.ronda}>
                  <Table.Td>{a.ronda}</Table.Td>
                  <Table.Td>
                    <Badge size="sm" variant="light" color={a.error ? 'orange' : 'green'}>
                      {a.estado}
                    </Badge>
                    {a.error && (
                      <Text size="xs" c="orange">
                        {a.error}
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td style={{ whiteSpace: 'nowrap' }}>{fechaHora(a.generado)}</Table.Td>
                  <Table.Td>{a.destinatarios ?? '—'}</Table.Td>
                  <Table.Td>
                    {a.archivoId ? (
                      <VisorDocumento url={`/api/archivos/${a.archivoId}`} nombre="Acta">
                        Ver acta
                      </VisorDocumento>
                    ) : (
                      '—'
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      )}
      {d.permisos.enviaActa && (
        <Stack gap={4} mt="sm" align="flex-start">
          <Button
            data-testid="enviar-acta"
            color="green"
            disabled={bloqueado}
            aria-describedby={idAyuda}
            loading={enviar.isPending}
            onClick={() =>
              window.confirm('Se generará el acta, se enviará a los destinatarios y la visita quedará cerrada. ¿Continuar?') && enviar.mutate()
            }
          >
            Enviar acta y cerrar la visita
          </Button>
          <Text size="xs" c="dimmed" id={idAyuda}>
            {sinGuardar
              ? 'Hay cambios sin guardar: guárdelos antes de enviar el acta.'
              : bloqueado
                ? 'Complete lo que falta (ver el resumen) para poder enviar el acta.'
                : 'El acta se genera con lo guardado.'}
          </Text>
        </Stack>
      )}
    </Card>
  );
}
