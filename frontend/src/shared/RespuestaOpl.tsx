import {
  Alert,
  Anchor,
  Badge,
  Box,
  Button,
  Card,
  Center,
  Group,
  List,
  Loader,
  Paper,
  Radio,
  SimpleGrid,
  Stack,
  Text,
  Textarea,
  TextInput,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { api, descargar, ErrorApi, subirArchivos } from './api';
import { tamanoLegible } from './checklist';
import { fecha, fechaHora } from './formato';
import {
  COLOR_ESTADO,
  conAvisoFallido,
  COLOR_FASE,
  DIAS_OBJECIONES,
  DIAS_PLAN,
  editaQuienComo,
  erroresFirmaPlan,
  erroresQuienComo,
  erroresRespuesta,
  faltanQuienComo,
  MAX_SOPORTES,
  NOMBRE_ESTADO,
  NOMBRE_FASE,
  NOMBRE_PLAN,
  objecionesPorDecidir,
  pideReabrir,
  pideRefrescar,
  pideReverificar,
  planFirmado,
  puedeResponder,
  puedeSubirSoportes,
  quienComoCambiados,
  valorQuienComo,
  vaAlPlan,
  type DetalleOpl,
  type HallazgoOpl,
  type QuienComo,
} from './opl';
import { PanelFirma } from './PanelFirma';
import { EncabezadoPagina } from './EncabezadoPagina';
import { useValidacion, type Validacion } from './validacion';

/** Aviso de un error de petición dentro de una sección (cada sección tiene su resumen de errores). */
type AvisarCon = (v: Validacion) => (e: unknown, texto: string) => void;

interface Mensaje {
  mensaje: string;
}

interface EdicionRespuesta {
  decision: 'acepta' | 'objeta' | null;
  justificacion: string;
}

/**
 * Respuesta del OPL a los hallazgos del acta, común al enlace del acta (base «/api/opl-publico») y al usuario OPL (base
 * «/api/opl/visitas/{id}»): plazos, aceptar u objetar cada hallazgo, soportes de la objeción, quién y cómo del plan, firma del plan y
 * descarga del acta. Lo escrito vive aquí: si el enlace pide verificar el código otra vez, el padre lo pide sin desmontar el componente.
 */
export function RespuestaOpl({
  base,
  rutaDetalle = base,
  alReverificar,
  orden = 1,
}: {
  base: string;
  rutaDetalle?: string;
  alReverificar?: () => void;
  /** Nivel del título de la visita: 2 en el portal público, que ya tiene su h1. */
  orden?: 1 | 2;
}) {
  const clave = ['opl', 'detalle', rutaDetalle];
  const detalle = useQuery({ queryKey: clave, queryFn: () => api<DetalleOpl>(rutaDetalle) });
  const reverificarDetalle = pideReverificar(detalle.error) || pideReabrir(detalle.error);
  // Por inactividad el enlace pide el código otra vez: se avisa al padre y, al verificar, la consulta se repite
  useEffect(() => {
    if (reverificarDetalle) alReverificar?.();
  }, [reverificarDetalle, alReverificar]);

  if (detalle.isLoading) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    );
  }
  if (!detalle.data) {
    return <Alert color={reverificarDetalle ? 'orange' : 'red'}>{detalle.error?.message ?? 'No se pudo cargar la visita.'}</Alert>;
  }
  return <Contenido base={base} clave={clave} d={detalle.data} alReverificar={alReverificar} orden={orden} />;
}

function Contenido({
  base,
  clave,
  d,
  alReverificar,
  orden,
}: {
  base: string;
  clave: unknown[];
  d: DetalleOpl;
  alReverificar?: () => void;
  orden: 1 | 2;
}) {
  const qc = useQueryClient();
  const [nombre, setNombre] = useState('');
  const [cargo, setCargo] = useState('');
  const [respuestas, setRespuestas] = useState<Record<string, EdicionRespuesta>>({});
  const [quienComo, setQuienComo] = useState<Record<string, QuienComo>>({});

  const refrescar = () => {
    qc.invalidateQueries({ queryKey: clave });
    qc.invalidateQueries({ queryKey: ['opl', 'lista'] });
  };
  // Verificación vencida (403) o sesión del QR vencida (401): el padre reabre y pide el código sin desmontar lo escrito. Ese aviso
  // sigue en naranja (no es un error de lo diligenciado: el diálogo pide el código); los demás quedan fijos en el resumen de la sección.
  const avisarCon: AvisarCon = (v) => (e, texto) => {
    const otraVez = pideReverificar(e) || pideReabrir(e);
    if (otraVez) alReverificar?.();
    if (pideRefrescar(e)) refrescar();
    if (otraVez) notifications.show({ color: 'orange', autoClose: 10000, message: e instanceof ErrorApi ? e.message : texto });
    else v.errorServidor(e, texto);
  };
  const vActa = useValidacion({ testid: 'error-acta-opl' });
  const vResp = useValidacion({
    etiquetas: {
      nombre: 'Nombre de quien responde',
      cargo: 'Cargo de quien responde',
      ...Object.fromEntries(d.hallazgos.flatMap((h) => [['decision-' + h.codigo, 'Respuesta de ' + h.codigo], ['justificacion-' + h.codigo, 'Justificación de ' + h.codigo]])),
    },
    testid: 'error-respuesta-opl',
  });
  const vPlan = useValidacion({
    etiquetas: Object.fromEntries(d.hallazgos.flatMap((h) => [['quien-' + h.codigo, 'Quién de ' + h.codigo], ['como-' + h.codigo, 'Cómo de ' + h.codigo]])),
    testid: 'error-plan-opl',
  });

  const responder = useMutation({
    mutationFn: (c: { clave: string; decision: 'acepta' | 'objeta'; justificacion: string | null; nombre: string; cargo: string }) =>
      api<Mensaje>(`${base}/respuestas`, { metodo: 'POST', cuerpo: c }),
    onSuccess: (r, c) => {
      notifications.show({
        color: conAvisoFallido(r.mensaje) ? 'orange' : 'green',
        autoClose: conAvisoFallido(r.mensaje) ? false : 6000,
        message: r.mensaje,
      });
      setRespuestas((x) => Object.fromEntries(Object.entries(x).filter(([k]) => k !== c.clave)));
      vResp.reiniciar();
      refrescar();
    },
    onError: (e) => avisarCon(vResp)(e, 'No se pudo guardar la respuesta.'),
  });

  const cambiadosPlan = quienComoCambiados(d.hallazgos, quienComo);
  // Tras guardar con vacíos (el guardado parcial es válido), cada campo vacío lo dice en tono neutro: sin sonido, foco ni resumen
  const [mostrarVacios, setMostrarVacios] = useState(false);
  const vacios = mostrarVacios ? erroresQuienComo(d, quienComo) : {};
  const guardarPlan = useMutation({
    mutationFn: (filas: typeof cambiadosPlan) => api<Mensaje & { faltan: string[] }>(`${base}/plan`, { metodo: 'PUT', cuerpo: { filas } }),
    onSuccess: (r, filas) => {
      notifications.show({ color: r.faltan.length === 0 ? 'green' : 'orange', autoClose: 8000, message: r.mensaje });
      vPlan.reiniciar();
      setMostrarVacios(r.faltan.length > 0);
      // Solo se descarta lo que sigue igual a lo enviado: lo escrito mientras se guardaba se conserva
      const enviadas = new Map(filas.map((f) => [f.clave, f]));
      setQuienComo((x) =>
        Object.fromEntries(
          Object.entries(x).filter(([k, v]) => {
            const f = enviadas.get(k);
            return !f || f.quien !== v.quien.trim() || f.como !== v.como.trim();
          }),
        ),
      );
      refrescar();
    },
    onError: (e) => avisarCon(vPlan)(e, 'No se pudo guardar el plan.'),
  });

  const firmado = planFirmado(d.plan);
  const alPlan = d.hallazgos.filter((h) => vaAlPlan(h.estado));
  const respondibles = d.hallazgos.some((h) => puedeResponder(d, h));
  const faltan = faltanQuienComo(d.hallazgos, quienComo);

  // Aviso del navegador al cerrar o recargar con respuestas o quién y cómo sin guardar
  const sinGuardar = Object.keys(respuestas).length > 0 || cambiadosPlan.length > 0;
  useEffect(() => {
    if (!sinGuardar) return;
    const avisar = (e: BeforeUnloadEvent) => {
      e.preventDefault();
    };
    window.addEventListener('beforeunload', avisar);
    return () => window.removeEventListener('beforeunload', avisar);
  }, [sinGuardar]);

  return (
    <Stack data-testid="respuesta-opl">
      <EncabezadoPagina titulo={`Visita ${d.numero} · ${d.punto}`} orden={orden} fuente="Anexo 3">
        <Badge size="lg" color={COLOR_FASE[d.fase]} data-testid="fase-opl">
          {NOMBRE_FASE[d.fase]}
        </Badge>
      </EncabezadoPagina>
      <Plazos d={d} />
      {!d.abierta && <Alert color="yellow">La visita se reabrió: espere el acta nueva para responder.</Alert>}
      <Group gap="xs">
        <Button
          variant="light"
          data-testid="descargar-acta-opl"
          disabled={!d.actaArchivoId}
          onClick={() => {
            vActa.reiniciar();
            descargar(`${base}/acta`).catch((e: unknown) => avisarCon(vActa)(e, 'No se pudo descargar el acta.'));
          }}
        >
          Descargar el acta
        </Button>
        <Text size="sm" c="dimmed">
          {d.actaArchivoId ? `Acta enviada el ${fechaHora(d.enviado)}.` : `Acta enviada el ${fechaHora(d.enviado)}. El archivo del acta aún no está disponible para descargar.`}
        </Text>
      </Group>
      {vActa.resumen}

      <Stack ref={vResp.ref}>
        {vResp.resumen}
        {respondibles && (
          <Card withBorder data-testid="quien-responde">
            <Title order={5} mb="xs">
              Quién responde
            </Title>
            <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="xs">
              <TextInput
                label="Nombre de quien responde"
                required
                minLength={3}
                maxLength={150}
                value={nombre}
                error={vResp.error('nombre')}
                onChange={(e) => {
                  setNombre(e.currentTarget.value);
                  vResp.limpiar('nombre');
                }}
              />
              <TextInput
                label="Cargo de quien responde"
                required
                minLength={2}
                maxLength={150}
                value={cargo}
                error={vResp.error('cargo')}
                onChange={(e) => {
                  setCargo(e.currentTarget.value);
                  vResp.limpiar('cargo');
                }}
              />
            </SimpleGrid>
            <Text size="xs" c="dimmed" mt={4}>
              Queda con cada respuesta. Puede cambiar su respuesta hasta que el auditor líder decida la objeción.
            </Text>
          </Card>
        )}
  
        <Stack gap="sm">
          <Title order={4}>Hallazgos</Title>
          {d.hallazgos.length === 0 && (
            <Text size="sm" c="dimmed">
              El acta no tiene hallazgos.
            </Text>
          )}
          {d.hallazgos.map((h) => (
            <Hallazgo
              key={h.clave}
              base={base}
              d={d}
              h={h}
              edicion={respuestas[h.clave]}
              alEditar={(e) => setRespuestas((x) => ({ ...x, [h.clave]: e }))}
              pendiente={responder.isPending}
              v={vResp}
              alResponder={(e) => {
                const x = erroresRespuesta(e.decision, e.justificacion, nombre, cargo);
                const ok = vResp.validar({
                  nombre: x.nombre,
                  cargo: x.cargo,
                  ['decision-' + h.codigo]: x.decision,
                  ['justificacion-' + h.codigo]: x.justificacion,
                });
                if (!ok) return;
                responder.mutate({
                  clave: h.clave,
                  decision: e.decision!,
                  justificacion: e.decision === 'objeta' ? e.justificacion.trim() : null,
                  nombre: nombre.trim(),
                  cargo: cargo.trim(),
                });
              }}
              alSubir={refrescar}
              avisarCon={avisarCon}
            />
          ))}
        </Stack>
      </Stack>

      <Card withBorder data-testid="plan-opl">
        <Title order={5} mb={4}>
          Plan de mejoramiento: quién y cómo
        </Title>
        <Text size="sm" c="dimmed" mb="xs">
          Diligencie quién y cómo de los hallazgos aceptados (también los aceptados automáticamente) y de los ratificados por el auditor líder.
        </Text>
        {alPlan.length === 0 ? (
          <Text size="sm" c="dimmed">
            Aún no hay hallazgos que vayan al plan.
          </Text>
        ) : (
          <Stack gap="sm" ref={vPlan.ref}>
            {alPlan.map((h) => {
              const v = valorQuienComo(h, quienComo);
              const editable = editaQuienComo(d, h);
              return (
                <Paper key={h.clave} withBorder p="sm" data-testid={'quien-como-' + h.codigo}>
                  <Text size="sm" fw={600}>
                    {h.codigo} · {h.texto}
                  </Text>
                  <Text size="xs" c="dimmed">
                    Qué (auditor): {h.planAuditor.que ?? '—'}
                  </Text>
                  <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="xs" mt="xs">
                    <TextInput
                      label="Quién"
                      aria-label={'Quién de ' + h.codigo}
                      maxLength={200}
                      readOnly={!editable}
                      value={v.quien}
                      description={vPlan.error('quien-' + h.codigo) === undefined ? vacios['quien-' + h.codigo] : undefined}
                      error={vPlan.error('quien-' + h.codigo)}
                      onChange={(e) => {
                        const valor = e.currentTarget.value;
                        setQuienComo((x) => ({ ...x, [h.clave]: { ...v, quien: valor } }));
                        vPlan.limpiar('quien-' + h.codigo);
                      }}
                    />
                    <Textarea
                      label="Cómo"
                      aria-label={'Cómo de ' + h.codigo}
                      autosize
                      minRows={2}
                      maxLength={4000}
                      readOnly={!editable}
                      value={v.como}
                      description={vPlan.error('como-' + h.codigo) === undefined ? vacios['como-' + h.codigo] : undefined}
                      error={vPlan.error('como-' + h.codigo)}
                      onChange={(e) => {
                        const valor = e.currentTarget.value;
                        setQuienComo((x) => ({ ...x, [h.clave]: { ...v, como: valor } }));
                        vPlan.limpiar('como-' + h.codigo);
                      }}
                    />
                  </SimpleGrid>
                </Paper>
              );
            })}
            {/* Lo que falta es informativo: se puede guardar por partes; al guardar, los campos vacíos quedan marcados */}
            {faltan.length > 0 && !vPlan.hayErrores && (
              <Text size="sm" c="dimmed">
                Falta quién y cómo de: {faltan.join(', ')}.
              </Text>
            )}
            {vPlan.resumen}
            {alPlan.some((h) => editaQuienComo(d, h)) && (
              <Group justify="flex-end" gap="xs">
                {cambiadosPlan.length === 0 && (
                  <Text size="sm" c="dimmed">
                    Sin cambios por guardar.
                  </Text>
                )}
                <Button
                  data-testid="guardar-quien-como"
                  disabled={cambiadosPlan.length === 0}
                  loading={guardarPlan.isPending}
                  onClick={() => {
                    // Guardado parcial: el servidor lo admite y responde qué falta (se muestra como información, no como error)
                    vPlan.reiniciar();
                    guardarPlan.mutate(cambiadosPlan);
                  }}
                >
                  Guardar quién y cómo
                </Button>
              </Group>
            )}
          </Stack>
        )}
      </Card>

      <FirmaPlan
        base={base}
        d={d}
        firmado={firmado}
        sinGuardar={cambiadosPlan.length > 0 || Object.keys(respuestas).length > 0}
        alFirmar={refrescar}
        avisarCon={avisarCon}
      />
    </Stack>
  );
}

function Plazos({ d }: { d: DetalleOpl }) {
  return (
    <Alert color={COLOR_FASE[d.fase]} title="Plazos" data-testid="plazos-opl">
      <Stack gap={4}>
        <Text size="sm">
          Para aceptar u objetar cada hallazgo: {DIAS_OBJECIONES} días hábiles, hasta el {d.venceObjecionesLargo} a las 23:59. Lo que no se responda
          en ese plazo queda aceptado.
        </Text>
        <Text size="md" fw={700}>
          Plazo máximo para remitir el plan de mejoramiento firmado: {DIAS_PLAN} días hábiles, hasta el {d.vencePlanLargo} a las 23:59. Vencido el
          plazo no se recibe.
        </Text>
        <Text size="sm">Estado del plan: {NOMBRE_PLAN[d.plan.estado]}</Text>
      </Stack>
    </Alert>
  );
}

function Dato({ titulo, children }: { titulo: string; children: ReactNode }) {
  return (
    <div>
      <Text size="xs" c="dimmed">
        {titulo}
      </Text>
      <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
        {children}
      </Text>
    </div>
  );
}

function Hallazgo({
  base,
  d,
  h,
  edicion,
  alEditar,
  pendiente,
  v,
  alResponder,
  alSubir,
  avisarCon,
}: {
  base: string;
  d: DetalleOpl;
  h: HallazgoOpl;
  edicion: EdicionRespuesta | undefined;
  alEditar: (e: EdicionRespuesta) => void;
  pendiente: boolean;
  v: Validacion;
  alResponder: (e: EdicionRespuesta) => void;
  alSubir: () => void;
  avisarCon: AvisarCon;
}) {
  const valor = edicion ?? { decision: h.decision, justificacion: h.justificacion ?? '' };
  // Sin cambios sobre una respuesta ya guardada no hay qué enviar; sin respuesta, el botón queda habilitado y al pulsarlo dice qué falta
  const sinCambios = !edicion && !!valor.decision;
  const responde = puedeResponder(d, h);
  const p = h.planAuditor;
  return (
    <Paper withBorder p="sm" data-testid={'hallazgo-opl-' + h.codigo}>
      <Group justify="space-between" gap="xs" wrap="wrap">
        <Text size="sm" fw={700}>
          {h.codigo} · {h.texto}
        </Text>
        <Badge color={COLOR_ESTADO[h.estado]} variant="light" data-testid={'estado-' + h.codigo}>
          {NOMBRE_ESTADO[h.estado]}
        </Badge>
      </Group>
      {h.origen === 'obligacion' && (
        <Text size="xs" c="dimmed">
          Obligación del contrato
        </Text>
      )}
      <Text size="sm" mt={4} style={{ whiteSpace: 'pre-wrap' }}>
        <Text span fw={500}>
          Hallazgo:{' '}
        </Text>
        {h.hallazgo ?? '—'}
      </Text>
      <Box component="details" mt={4}>
        <Text component="summary" size="sm" c="blue" style={{ cursor: 'pointer' }}>
          Plan del auditor (5W2H)
        </Text>
        <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="xs" mt={4}>
          <Dato titulo="Causas">{p.causas ?? '—'}</Dato>
          <Dato titulo="Qué">{p.que ?? '—'}</Dato>
          <Dato titulo="Por qué">{p.porque ?? '—'}</Dato>
          <Dato titulo="Dónde">{p.donde ?? '—'}</Dato>
          <Dato titulo="Inicio">{fecha(p.inicio)}</Dato>
          <Dato titulo="Fin">{fecha(p.fin)}</Dato>
          <Dato titulo="Indicador">{p.indicador ?? '—'}</Dato>
          <Dato titulo="Estado">{p.estado}</Dato>
        </SimpleGrid>
      </Box>

      {responde ? (
        <Stack gap="xs" mt="xs">
          <Radio.Group
            label="Respuesta"
            data-testid={'respuesta-' + h.codigo}
            value={valor.decision ?? ''}
            error={v.error('decision-' + h.codigo)}
            onChange={(x) => {
              alEditar({ ...valor, decision: (x || null) as EdicionRespuesta['decision'] });
              v.limpiar('decision-' + h.codigo);
            }}
          >
            <Group gap="md" mt={4}>
              <Radio value="acepta" label="Acepto el hallazgo" />
              <Radio value="objeta" label="Objeto el hallazgo" />
            </Group>
          </Radio.Group>
          {valor.decision === 'objeta' && (
            <Textarea
              label="Justificación de la objeción"
              aria-label={'Justificación de la objeción de ' + h.codigo}
              description="De 10 a 4000 caracteres. Después de guardarla puede subir soportes."
              required
              autosize
              minRows={3}
              maxLength={4000}
              value={valor.justificacion}
              error={v.error('justificacion-' + h.codigo)}
              onChange={(e) => {
                alEditar({ ...valor, justificacion: e.currentTarget.value });
                v.limpiar('justificacion-' + h.codigo);
              }}
            />
          )}
          <Group justify="flex-end" gap="xs">
            {sinCambios && (
              <Text size="sm" c="dimmed">
                Sin cambios por guardar.
              </Text>
            )}
            <Button
              size="sm"
              data-testid={'guardar-respuesta-' + h.codigo}
              disabled={sinCambios}
              loading={pendiente}
              onClick={() => alResponder(valor)}
            >
              Guardar respuesta
            </Button>
          </Group>
        </Stack>
      ) : (
        h.decision && (
          <Text size="sm" mt="xs">
            <Text span fw={500}>
              Respuesta del OPL:{' '}
            </Text>
            {h.decision === 'objeta'
              ? 'Objetado'
              : h.estado === 'aceptado_auto'
                ? 'Aceptado automáticamente (sin respuesta en el plazo)'
                : 'Aceptado'}
            {h.respondio ? ' · ' + h.respondio : ''}
          </Text>
        )
      )}
      {h.decision === 'objeta' && h.justificacion && !responde && (
        <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
          {h.justificacion}
        </Text>
      )}
      {h.resolucion && (
        <Alert mt="xs" color={h.resolucion === 'levanta' ? 'green' : 'red'} data-testid={'decision-' + h.codigo}>
          Decisión del auditor líder: {h.resolucion === 'levanta' ? 'levantó el hallazgo' : 'ratificó el hallazgo'}. {h.resolucionMotivo}
        </Alert>
      )}
      {(h.decision === 'objeta' || h.soportes.length > 0) && <Soportes base={base} d={d} h={h} alSubir={alSubir} avisarCon={avisarCon} />}
    </Paper>
  );
}

/** Soportes de la objeción: lista con descarga y carga múltiple (máximo 10 por hallazgo, solo objetado y en plazo). */
function Soportes({
  base,
  d,
  h,
  alSubir,
  avisarCon,
}: {
  base: string;
  d: DetalleOpl;
  h: HallazgoOpl;
  alSubir: () => void;
  avisarCon: AvisarCon;
}) {
  const entrada = useRef<HTMLInputElement>(null);
  const idError = useId();
  const [elegidos, setElegidos] = useState<File[]>([]);
  const quedan = MAX_SOPORTES - h.soportes.length;
  const v = useValidacion({ etiquetas: { archivos: 'Soportes de la objeción de ' + h.codigo }, testid: 'error-soportes-' + h.codigo });
  const avisarError = avisarCon(v);
  const subir = useMutation({
    mutationFn: (files: File[]) => subirArchivos<Mensaje>(`${base}/soportes`, { clave: h.clave }, files),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      setElegidos([]);
      if (entrada.current) entrada.current.value = '';
      v.reiniciar();
      alSubir();
    },
    onError: (e) => avisarError(e, 'No se pudieron subir los soportes.'),
  });
  const errorArchivos = v.error('archivos');
  return (
    <Stack gap={4} mt="xs" data-testid={'soportes-' + h.codigo} ref={v.ref}>
      <Text size="sm" fw={500}>
        Soportes de la objeción
      </Text>
      {h.soportes.length === 0 ? (
        <Text size="xs" c="dimmed">
          Sin soportes.
        </Text>
      ) : (
        <List size="sm" spacing={2}>
          {h.soportes.map((s) => (
            <List.Item key={s.id}>
              <Group gap={6} wrap="wrap">
                <Anchor
                  component="button"
                  type="button"
                  size="sm"
                  style={{ wordBreak: 'break-all' }}
                  onClick={() => descargar(`${base}/soportes/${s.id}`).catch((e: unknown) => avisarError(e, 'No se pudo descargar el soporte.'))}
                >
                  {s.nombre}
                </Anchor>
                <Text size="xs" c="dimmed">
                  {tamanoLegible(s.tamano)}
                </Text>
              </Group>
            </List.Item>
          ))}
        </List>
      )}
      {puedeSubirSoportes(d, h) && (
        <>
          <Group gap="xs" wrap="wrap" align="center">
            <input
              ref={entrada}
              type="file"
              multiple
              aria-label={'Soportes de la objeción de ' + h.codigo}
              aria-invalid={errorArchivos ? true : undefined}
              aria-describedby={typeof errorArchivos === 'string' ? idError : undefined}
              style={{ maxWidth: '100%', fontSize: 'var(--mantine-font-size-xs)' }}
              onChange={(e) => {
                setElegidos(Array.from(e.currentTarget.files ?? []));
                v.limpiar('archivos');
              }}
            />
            <Button
              size="xs"
              variant="light"
              data-testid={'subir-soportes-' + h.codigo}
              loading={subir.isPending}
              onClick={() => {
                const error =
                  elegidos.length === 0
                    ? 'Elija uno o más archivos.'
                    : elegidos.length > quedan
                      ? `Eligió ${elegidos.length} archivos y quedan ${quedan} soportes por subir en este hallazgo.`
                      : null;
                if (!v.validar({ archivos: error })) return;
                subir.mutate(elegidos);
              }}
            >
              Subir
            </Button>
          </Group>
          {typeof errorArchivos === 'string' && (
            <Text id={idError} size="xs" c="red">
              {errorArchivos}
            </Text>
          )}
          <Text size="xs" c="dimmed">
            Máximo {MAX_SOPORTES} soportes por hallazgo (quedan {quedan}); 25 MB cada uno.
          </Text>
        </>
      )}
      {v.resumen}
    </Stack>
  );
}

function FirmaPlan({
  base,
  d,
  firmado,
  sinGuardar,
  alFirmar,
  avisarCon,
}: {
  base: string;
  d: DetalleOpl;
  firmado: boolean;
  sinGuardar: boolean;
  alFirmar: () => void;
  avisarCon: AvisarCon;
}) {
  const [nombre, setNombre] = useState('');
  const [cargo, setCargo] = useState('');
  const [documento, setDocumento] = useState('');
  const [imagen, setImagen] = useState<string | null>(null);
  const v = useValidacion({
    etiquetas: { nombre: 'Nombre de quien firma', cargo: 'Cargo de quien firma', documento: 'Número de documento', imagen: 'Firma' },
    testid: 'error-firma-plan',
  });
  const firmar = useMutation({
    mutationFn: () =>
      api<Mensaje>(`${base}/firma`, { metodo: 'POST', cuerpo: { nombre: nombre.trim(), cargo: cargo.trim(), documento: documento.trim(), imagen } }),
    onSuccess: (r) => {
      notifications.show({
        color: conAvisoFallido(r.mensaje) ? 'orange' : 'green',
        autoClose: conAvisoFallido(r.mensaje) ? false : 6000,
        message: r.mensaje,
      });
      v.reiniciar();
      alFirmar();
    },
    onError: (e) => avisarCon(v)(e, 'No se pudo firmar el plan.'),
  });
  const sinRespuesta = d.hallazgos.filter((h) => h.estado === 'sin_respuesta').map((h) => h.codigo);
  const porDecidir = objecionesPorDecidir(d.hallazgos);
  const idEspera = useId();

  return (
    <Card withBorder data-testid="firma-plan-opl">
      <Title order={5} mb="xs">
        Firma del plan de mejoramiento
      </Title>
      {firmado ? (
        <Stack gap={2}>
          <Text size="sm">
            {d.plan.nombre} · {d.plan.cargo}
          </Text>
          <Badge color="green" variant="light">
            Plan firmado {d.plan.firmado ? fechaHora(d.plan.firmado) : ''}
          </Badge>
        </Stack>
      ) : d.plan.estado === 'cerrado' ? (
        <Text size="sm" c="green">
          El plan quedó cerrado: el auditor líder levantó todos los hallazgos. No se necesita la firma.
        </Text>
      ) : d.fase === 'vencido' || !d.abierta || d.plan.estado === 'vencido' || d.plan.estado === 'reportado_digsa' ? (
        <Text size="sm" c="red">
          {d.abierta ? 'Venció el plazo para remitir el plan firmado: ya no se recibe la firma.' : 'La visita se reabrió: no se recibe la firma.'}
        </Text>
      ) : (
        <form
          ref={v.ref}
          noValidate
          onSubmit={(e) => {
            e.preventDefault();
            if (sinGuardar) {
              v.alerta('Guarde las respuestas y el quién y cómo antes de firmar el plan.');
              return;
            }
            if (!v.validar(erroresFirmaPlan(nombre, cargo, documento, imagen))) return;
            firmar.mutate();
          }}
        >
          <Stack gap="xs">
            {sinRespuesta.length > 0 && (
              <Text size="sm" c="orange">
                Antes de firmar, acepte u objete: {sinRespuesta.join(', ')}.
              </Text>
            )}
            {porDecidir.length > 0 && (
              // Decisión D11: no se firma con objeciones sin decidir
              <Text size="sm" c="orange" data-testid="objeciones-por-decidir">
                El plan se firma cuando el auditor líder decida las objeciones de: {porDecidir.join(', ')}.
              </Text>
            )}
            <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="xs">
              <TextInput
                label="Nombre de quien firma"
                required
                minLength={3}
                maxLength={150}
                value={nombre}
                error={v.error('nombre')}
                onChange={(e) => {
                  setNombre(e.currentTarget.value);
                  v.limpiar('nombre');
                }}
              />
              <TextInput
                label="Cargo de quien firma"
                required
                minLength={2}
                maxLength={150}
                value={cargo}
                error={v.error('cargo')}
                onChange={(e) => {
                  setCargo(e.currentTarget.value);
                  v.limpiar('cargo');
                }}
              />
              <TextInput
                label="Número de documento"
                required
                maxLength={30}
                value={documento}
                error={v.error('documento')}
                onChange={(e) => {
                  setDocumento(e.currentTarget.value);
                  v.limpiar('documento');
                }}
              />
            </SimpleGrid>
            <PanelFirma
              error={typeof v.error('imagen') === 'string' ? (v.error('imagen') as string) : undefined}
              alCambiar={(x) => {
                setImagen(x);
                if (x) v.limpiar('imagen');
              }}
            />
            {v.resumen}
            <Group justify="flex-end" gap="xs">
              {(sinRespuesta.length > 0 || porDecidir.length > 0) && (
                <Text size="sm" c="dimmed" id={idEspera}>
                  Se habilita cuando se cumpla lo indicado arriba.
                </Text>
              )}
              <Button
                type="submit"
                color="green"
                data-testid="firmar-plan-opl"
                disabled={sinRespuesta.length > 0 || porDecidir.length > 0}
                aria-describedby={sinRespuesta.length > 0 || porDecidir.length > 0 ? idEspera : undefined}
                loading={firmar.isPending}
              >
                Firmar el plan
              </Button>
            </Group>
          </Stack>
        </form>
      )}
    </Card>
  );
}
