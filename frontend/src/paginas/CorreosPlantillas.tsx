import { Alert, Badge, Button, Group, List, Modal, Stack, Table, Text, Textarea, TextInput } from '@mantine/core';
import { useMediaQuery } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type FormEvent } from 'react';
import { api } from '../shared/api';
import { BotonConMotivo } from '../shared/BotonConMotivo';
import { fechaHora } from '../shared/formato';
import { TablaAdaptable } from '../shared/TablaAdaptable';
import { useValidacion } from '../shared/validacion';

// Módulo Correos · C2: catálogo de plantillas (pedido del usuario del 2026-10-29). Las editables cambian asunto y texto con variables
// protegidas, con vista previa de datos ficticios y «Restaurar original»; las de un solo uso (MFA, contraseña, código del OPL) se ven
// pero no se editan. Guardar y restaurar piden motivo y quedan en los logs.

type FilaPlantilla = {
  clave: string;
  nombre: string;
  modulo: string;
  editable: boolean;
  editada: boolean;
  editadaPor: string | null;
  editadaEl: string | null;
};

type Variable = { nombre: string; descripcion: string; ejemplo: string; obligatoria: boolean; bloque: boolean };

type DetallePlantilla = {
  clave: string;
  nombre: string;
  modulo: string;
  formato: 'html' | 'texto' | 'sin_marco';
  editable: boolean;
  motivoNoEditable: string | null;
  asunto: string;
  cuerpo: string;
  asuntoFabrica: string;
  cuerpoFabrica: string;
  editada: boolean;
  variables: Variable[];
  historial: { id: number; accion: string; motivo: string; usuario: string; creado: string }[];
};

type VistaPrevia = { asunto: string; html: string; errores: string[] };

const MOTIVO_MIN = 10;
const MOTIVO_MAX = 300;

export function PlantillasCorreo() {
  const [clave, setClave] = useState<string | null>(null);
  const lista = useQuery({ queryKey: ['correos-plantillas'], queryFn: () => api<FilaPlantilla[]>('/api/correos/plantillas') });
  const filas = lista.data ?? [];
  return (
    <Stack>
      <Text size="sm" c="dimmed">
        Textos de los correos que envía la aplicación. Las variables entre llaves dobles se llenan al enviar; las protegidas no se pueden
        quitar. Cada cambio queda guardado con su motivo.
      </Text>
      {lista.error && <Alert color="red">No se pudieron cargar las plantillas: {lista.error.message}</Alert>}
      <TablaAdaptable>
        <Table striped miw={720} data-testid="tabla-plantillas">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Módulo</Table.Th>
              <Table.Th>Plantilla</Table.Th>
              <Table.Th>Texto</Table.Th>
              <Table.Th>Abrir</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {filas.map((p) => (
              <Table.Tr key={p.clave} data-testid={'plantilla-' + p.clave}>
                <Table.Td>{p.modulo}</Table.Td>
                <Table.Td>{p.nombre}</Table.Td>
                <Table.Td>
                  {!p.editable ? (
                    <Badge variant="light" color="gray">
                      Solo lectura
                    </Badge>
                  ) : p.editada ? (
                    <Stack gap={2}>
                      <Badge variant="light" color="orange">
                        Editada
                      </Badge>
                      <Text size="xs" c="dimmed">
                        {p.editadaPor} · {p.editadaEl ? fechaHora(p.editadaEl) : ''}
                      </Text>
                    </Stack>
                  ) : (
                    <Badge variant="light" color="teal">
                      Original
                    </Badge>
                  )}
                </Table.Td>
                <Table.Td>
                  <Button size="xs" variant="light" aria-label={'Abrir la plantilla ' + p.nombre} onClick={() => setClave(p.clave)}>
                    {p.editable ? 'Ver y editar' : 'Ver'}
                  </Button>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </TablaAdaptable>
      <EditorPlantilla clave={clave} alCerrar={() => setClave(null)} />
    </Stack>
  );
}

function EditorPlantilla({ clave, alCerrar }: { clave: string | null; alCerrar: () => void }) {
  const celular = useMediaQuery('(max-width: 36em)');
  const clienteConsultas = useQueryClient();
  const detalle = useQuery({
    queryKey: ['correos-plantilla', clave],
    queryFn: () => api<DetallePlantilla>('/api/correos/plantillas/' + clave),
    enabled: clave !== null,
  });
  const d = detalle.data;
  const [asunto, setAsunto] = useState('');
  const [cuerpo, setCuerpo] = useState('');
  const [previa, setPrevia] = useState<VistaPrevia | null>(null);
  const [accion, setAccion] = useState<'guardar' | 'restaurar' | null>(null);
  const [motivo, setMotivo] = useState('');
  const [enviando, setEnviando] = useState(false);
  const v = useValidacion({
    etiquetas: { asunto: 'Asunto', cuerpo: 'Texto del correo', motivo: 'Motivo' },
    testid: 'error-plantilla',
  });

  useEffect(() => {
    if (d) {
      setAsunto(d.asunto);
      setCuerpo(d.cuerpo);
      setPrevia(null);
    }
  }, [d]);

  const cerrar = () => {
    setAccion(null);
    setMotivo('');
    setPrevia(null);
    v.reiniciar();
    alCerrar();
  };

  const verPrevia = async () => {
    if (!d) return;
    try {
      const r = await api<VistaPrevia>(`/api/correos/plantillas/${d.clave}/vista-previa`, { cuerpo: d.editable ? { asunto, cuerpo } : {} });
      setPrevia(r);
    } catch (e) {
      v.errorServidor(e, 'No se pudo armar la vista previa.');
    }
  };

  const confirmar = async (ev: FormEvent) => {
    ev.preventDefault();
    if (!d || accion === null || enviando) return;
    const m = motivo.trim();
    const largo = [...m].length;
    if (
      !v.validar({
        asunto: accion === 'guardar' && asunto.trim() === '' ? 'Escriba el asunto.' : null,
        cuerpo: accion === 'guardar' && cuerpo.trim() === '' ? 'Escriba el texto del correo.' : null,
        motivo: largo < MOTIVO_MIN || largo > MOTIVO_MAX ? `Escriba el motivo (entre ${MOTIVO_MIN} y ${MOTIVO_MAX} caracteres).` : null,
      })
    )
      return;
    setEnviando(true);
    try {
      const r =
        accion === 'guardar'
          ? await api<{ mensaje: string }>('/api/correos/plantillas/' + d.clave, { metodo: 'PUT', cuerpo: { asunto, cuerpo, motivo: m } })
          : await api<{ mensaje: string }>(`/api/correos/plantillas/${d.clave}/restaurar`, { cuerpo: { motivo: m } });
      notifications.show({ color: 'green', message: r.mensaje });
      setAccion(null);
      setMotivo('');
      v.reiniciar();
      await clienteConsultas.invalidateQueries({ queryKey: ['correos-plantillas'] });
      await clienteConsultas.invalidateQueries({ queryKey: ['correos-plantilla', d.clave] });
    } catch (e) {
      v.errorServidor(e, accion === 'guardar' ? 'No se pudo guardar la plantilla.' : 'No se pudo restaurar la plantilla.');
    } finally {
      setEnviando(false);
    }
  };

  const sinCambios = d !== undefined && asunto === d.asunto && cuerpo === d.cuerpo;
  const idSinEditar = 'motivo-sin-editar-' + (d?.clave ?? '');
  const idSinCambios = 'motivo-sin-cambios-' + (d?.clave ?? '');

  return (
    <Modal opened={clave !== null} onClose={cerrar} title={d ? `${d.nombre} · ${d.modulo}` : 'Plantilla'} size="xl" fullScreen={celular}>
      {detalle.error && <Alert color="red">{detalle.error.message}</Alert>}
      {d && (
        <form ref={v.ref} onSubmit={confirmar} noValidate>
          <Stack>
            {v.resumen}
            {!d.editable && (
              <Alert color="blue" data-testid="aviso-no-editable">
                {d.motivoNoEditable}
              </Alert>
            )}
            <TextInput
              label="Asunto"
              value={asunto}
              readOnly={!d.editable}
              error={v.error('asunto')}
              onChange={(e) => {
                setAsunto(e.currentTarget.value);
                v.limpiar('asunto');
              }}
              maxLength={200}
            />
            <Textarea
              label="Texto del correo"
              description={
                d.formato === 'texto'
                  ? 'Texto plano: los saltos de línea se respetan.'
                  : 'Admite párrafos, negrillas, listas y tablas; los enlaces solo pueden ir a una variable.'
              }
              value={cuerpo}
              readOnly={!d.editable}
              error={v.error('cuerpo')}
              onChange={(e) => {
                setCuerpo(e.currentTarget.value);
                v.limpiar('cuerpo');
              }}
              autosize
              minRows={6}
              maxRows={16}
              styles={{ input: { fontFamily: 'monospace', fontSize: 13 } }}
              data-testid="cuerpo-plantilla"
            />
            <div>
              <Text size="sm" fw={600}>
                Variables
              </Text>
              <List size="sm" spacing={2} data-testid="variables-plantilla">
                {d.variables.map((x) => (
                  <List.Item key={x.nombre}>
                    <code>{'{{' + x.nombre + '}}'}</code> · {x.descripcion}
                    {x.obligatoria && (
                      <Badge ml={6} size="xs" variant="light" color="gray">
                        Protegida
                      </Badge>
                    )}
                  </List.Item>
                ))}
              </List>
            </div>
            <Group justify="flex-end" wrap="wrap">
              <Button variant="default" onClick={() => void verPrevia()}>
                Vista previa
              </Button>
              {d.editable && (
                <>
                  {(!d.editada || sinCambios) && (
                    <Text size="sm" c="dimmed">
                      {!d.editada && <span id={idSinEditar}>Tiene el texto original. </span>}
                      {sinCambios && <span id={idSinCambios}>No hay cambios que guardar.</span>}
                    </Text>
                  )}
                  <BotonConMotivo
                    variant="default"
                    motivo={d.editada ? null : 'Tiene el texto original.'}
                    idMotivo={idSinEditar}
                    onClick={() => {
                      v.reiniciar();
                      setAccion('restaurar');
                    }}
                  >
                    Restaurar original
                  </BotonConMotivo>
                  <BotonConMotivo
                    motivo={sinCambios ? 'No hay cambios que guardar.' : null}
                    idMotivo={idSinCambios}
                    onClick={() => {
                      v.reiniciar();
                      setAccion('guardar');
                    }}
                  >
                    Guardar cambios
                  </BotonConMotivo>
                </>
              )}
            </Group>
            {accion !== null && (
              <Stack gap="xs">
                <Textarea
                  label="Motivo"
                  description={`${accion === 'guardar' ? 'Por qué cambia el texto' : 'Por qué vuelve al texto original'}: entre ${MOTIVO_MIN} y ${MOTIVO_MAX} caracteres; queda en los logs.`}
                  value={motivo}
                  error={v.error('motivo')}
                  onChange={(e) => {
                    setMotivo(e.currentTarget.value);
                    v.limpiar('motivo');
                  }}
                  maxLength={MOTIVO_MAX}
                  autosize
                  minRows={2}
                  data-autofocus
                />
                <Group justify="flex-end">
                  <Button variant="default" onClick={() => setAccion(null)}>
                    Cancelar
                  </Button>
                  <Button type="submit" loading={enviando}>
                    {accion === 'guardar' ? 'Guardar' : 'Restaurar'}
                  </Button>
                </Group>
              </Stack>
            )}
            {previa && (
              <Stack gap="xs" data-testid="vista-previa-plantilla">
                {previa.errores.length > 0 ? (
                  <Alert color="red" title="El texto no se puede guardar así">
                    <List size="sm">
                      {previa.errores.map((e) => (
                        <List.Item key={e}>{e}</List.Item>
                      ))}
                    </List>
                  </Alert>
                ) : (
                  <Text size="sm" style={{ wordBreak: 'break-word' }}>
                    <strong>Asunto:</strong> {previa.asunto}
                  </Text>
                )}
                <Text size="xs" c="dimmed">
                  Vista previa con datos ficticios{previa.errores.length > 0 ? ' (del texto vigente)' : ''}.
                </Text>
                <iframe
                  title="Vista previa de la plantilla"
                  sandbox=""
                  srcDoc={previa.html}
                  style={{ width: '100%', minHeight: 320, border: '1px solid var(--lume-border)', borderRadius: 4, background: 'var(--lume-white)' }}
                />
              </Stack>
            )}
            {d.historial.length > 0 && (
              <div>
                <Text size="sm" fw={600}>
                  Cambios
                </Text>
                <List size="sm" spacing={2} data-testid="historial-plantilla">
                  {d.historial.map((h) => (
                    <List.Item key={h.id}>
                      {fechaHora(h.creado)} · {h.usuario} · {h.accion}: {h.motivo}
                    </List.Item>
                  ))}
                </List>
              </div>
            )}
          </Stack>
        </form>
      )}
    </Modal>
  );
}
