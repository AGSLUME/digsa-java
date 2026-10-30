import { Alert, Anchor, Badge, Button, Group, Modal, Stack, Table, Text, Textarea, Title } from '@mantine/core';
import { useMediaQuery } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../shared/api';
import { conteo } from '../shared/formato';
import { TablaAdaptable } from '../shared/TablaAdaptable';
import { useValidacion } from '../shared/validacion';
import { errorCorreos } from '../shared/validaciones';

// Módulo Correos · C3: destinatarios (pedido del usuario del 2026-10-29). A quién le llega cada correo y de dónde sale el dato, sin
// duplicar la fuente de verdad: cada regla enlaza la pantalla donde se cambia. Aquí solo se editan, con motivo, el buzón de la DIGSA y
// los de la Sanidad de cada fuerza (mismo servicio que Catálogos).

type Regla = { tipo: string; regla: string; origen: string; pantalla: string; ruta: string };
type Fuerza = { codigo: string; nombre: string; sanidad: string; correos: string[]; activo: boolean };
type AlertaDest = { nombre: string; activa: boolean; critica: boolean; sms: boolean; perfiles: string[]; usuarios: number; destinatarios: number };
type Destinatarios = {
  reglas: Regla[];
  digsa: string[];
  fuerzas: Fuerza[];
  opl: string[];
  dominiosReportes: string[];
  alertas: AlertaDest[];
};

/** Qué buzón se edita: el de la DIGSA o el de una fuerza. */
type Edicion = { tipo: 'digsa' } | { tipo: 'fuerza'; fuerza: Fuerza };

const MOTIVO_MIN = 10;
const MOTIVO_MAX = 300;

export function DestinatariosCorreo() {
  const [edicion, setEdicion] = useState<Edicion | null>(null);
  const datos = useQuery({ queryKey: ['correos-destinatarios'], queryFn: () => api<Destinatarios>('/api/correos/destinatarios') });
  const d = datos.data;
  return (
    <Stack>
      {datos.error && <Alert color="red">No se pudieron cargar los destinatarios: {datos.error.message}</Alert>}
      {d && (
        <>
          <TablaAdaptable>
            <Table striped miw={860} data-testid="tabla-reglas-destinatarios">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Correo</Table.Th>
                  <Table.Th>A quién le llega</Table.Th>
                  <Table.Th>De dónde sale</Table.Th>
                  <Table.Th>Dónde se cambia</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {d.reglas.map((r) => (
                  <Table.Tr key={r.tipo}>
                    <Table.Td>{r.tipo}</Table.Td>
                    <Table.Td>{r.regla}</Table.Td>
                    <Table.Td>{r.origen}</Table.Td>
                    <Table.Td>
                      <Anchor component={Link} to={r.ruta} size="sm">
                        {r.pantalla}
                      </Anchor>
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </TablaAdaptable>
          <Text size="sm" data-testid="dominios-reportes">
            <strong>Reportes por correo y comité:</strong> lo indica quien envía · dominios permitidos:{' '}
            {d.dominiosReportes.length > 0 ? d.dominiosReportes.join(', ') : 'ninguno configurado'}
          </Text>

          <Title order={3} size="h4">
            Buzón de la DIGSA
          </Title>
          <Group justify="space-between" align="flex-start" wrap="wrap">
            <Text size="sm" style={{ wordBreak: 'break-word' }} data-testid="buzon-digsa">
              {d.digsa.length > 0 ? d.digsa.join(', ') : 'Sin correos: las actas, los reportes y las notificaciones no le llegan a la DIGSA.'}
            </Text>
            <Button size="xs" variant="light" onClick={() => setEdicion({ tipo: 'digsa' })}>
              Editar el buzón de la DIGSA
            </Button>
          </Group>

          <Title order={3} size="h4">
            Sanidad de cada fuerza
          </Title>
          <TablaAdaptable>
            <Table striped miw={640} data-testid="tabla-buzones-fuerzas">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Fuerza</Table.Th>
                  <Table.Th>Sanidad</Table.Th>
                  <Table.Th>Correos</Table.Th>
                  <Table.Th>Editar</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {d.fuerzas.map((f) => (
                  <Table.Tr key={f.codigo} data-testid={'fuerza-' + f.codigo}>
                    <Table.Td>
                      {f.nombre}
                      {!f.activo && (
                        <Badge ml={6} size="xs" variant="light" color="gray">
                          Inactiva
                        </Badge>
                      )}
                    </Table.Td>
                    <Table.Td>{f.sanidad || '—'}</Table.Td>
                    <Table.Td style={{ wordBreak: 'break-word' }}>{f.correos.length > 0 ? f.correos.join(', ') : 'Sin correos'}</Table.Td>
                    <Table.Td>
                      <Button
                        size="xs"
                        variant="light"
                        aria-label={'Editar los buzones de ' + f.nombre}
                        onClick={() => setEdicion({ tipo: 'fuerza', fuerza: f })}
                      >
                        Editar
                      </Button>
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </TablaAdaptable>

          <Text size="sm" style={{ wordBreak: 'break-word' }}>
            <strong>Operador logístico:</strong> {d.opl.length > 0 ? d.opl.join(', ') : 'sin correos'} ·{' '}
            <Anchor component={Link} to="/maestros" size="sm">
              se cambian en Puntos y auditores
            </Anchor>
          </Text>

          <Title order={3} size="h4">
            Alertas
          </Title>
          <TablaAdaptable>
            <Table striped miw={720} data-testid="tabla-alertas-destinatarios">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Alerta</Table.Th>
                  <Table.Th>Perfiles</Table.Th>
                  <Table.Th>Usuarios elegidos</Table.Th>
                  <Table.Th>Destinatarios</Table.Th>
                  <Table.Th>Canal</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {d.alertas.map((a) => (
                  <Table.Tr key={a.nombre}>
                    <Table.Td>
                      {a.nombre}
                      {!a.activa && (
                        <Badge ml={6} size="xs" variant="light" color="gray">
                          Inactiva
                        </Badge>
                      )}
                    </Table.Td>
                    <Table.Td>{a.perfiles.length > 0 ? a.perfiles.join(', ') : '—'}</Table.Td>
                    <Table.Td>{conteo(a.usuarios)}</Table.Td>
                    <Table.Td>{conteo(a.destinatarios)}</Table.Td>
                    <Table.Td>{a.critica && a.sms ? 'Correo y SMS' : 'Correo'}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </TablaAdaptable>
          <Anchor component={Link} to="/alertas" size="sm">
            Las alertas se configuran en Alertas (perfiles, usuarios, SMS y activación)
          </Anchor>
        </>
      )}
      <EditorBuzones edicion={edicion} actuales={edicion?.tipo === 'fuerza' ? edicion.fuerza.correos : (d?.digsa ?? [])} alCerrar={() => setEdicion(null)} />
    </Stack>
  );
}

function EditorBuzones({ edicion, actuales, alCerrar }: { edicion: Edicion | null; actuales: string[]; alCerrar: () => void }) {
  const celular = useMediaQuery('(max-width: 36em)');
  const clienteConsultas = useQueryClient();
  const [texto, setTexto] = useState<string | null>(null);
  const [motivo, setMotivo] = useState('');
  const [enviando, setEnviando] = useState(false);
  const v = useValidacion({ etiquetas: { correos: 'Correos', motivo: 'Motivo' }, testid: 'error-buzones' });
  const valor = texto ?? actuales.join('\n');
  const titulo = edicion === null ? '' : edicion.tipo === 'digsa' ? 'Buzón de la DIGSA' : 'Buzones de ' + edicion.fuerza.nombre;

  const cerrar = () => {
    setTexto(null);
    setMotivo('');
    v.reiniciar();
    alCerrar();
  };

  const guardar = async (ev: FormEvent) => {
    ev.preventDefault();
    if (edicion === null || enviando) return;
    const correos = valor
      .split(/[\s,;]+/)
      .map((x) => x.trim())
      .filter((x) => x !== '');
    const malo = errorCorreos(valor, 20);
    const m = motivo.trim();
    const largo = [...m].length;
    if (
      !v.validar({
        correos: malo ?? (edicion.tipo === 'digsa' && correos.length === 0 ? 'Escriba al menos un correo de la DIGSA.' : null),
        motivo: largo < MOTIVO_MIN || largo > MOTIVO_MAX ? `Escriba el motivo (entre ${MOTIVO_MIN} y ${MOTIVO_MAX} caracteres).` : null,
      })
    )
      return;
    setEnviando(true);
    try {
      const ruta = edicion.tipo === 'digsa' ? '/api/correos/destinatarios/digsa' : '/api/correos/destinatarios/fuerzas/' + edicion.fuerza.codigo;
      const r = await api<{ mensaje: string }>(ruta, { metodo: 'PUT', cuerpo: { correos, motivo: m } });
      notifications.show({ color: 'green', message: r.mensaje });
      await clienteConsultas.invalidateQueries({ queryKey: ['correos-destinatarios'] });
      cerrar();
    } catch (e) {
      v.errorServidor(e, 'No se pudieron guardar los correos.');
    } finally {
      setEnviando(false);
    }
  };

  return (
    <Modal opened={edicion !== null} onClose={cerrar} title={titulo} fullScreen={celular}>
      <form ref={v.ref} onSubmit={guardar} noValidate>
        <Stack>
          {v.resumen}
          <Textarea
            label="Correos"
            description="Uno por línea (también separados por coma). Se guardan con el mismo servicio de Catálogos."
            value={valor}
            error={v.error('correos')}
            onChange={(e) => {
              setTexto(e.currentTarget.value);
              v.limpiar('correos');
            }}
            autosize
            minRows={3}
          />
          <Textarea
            label="Motivo"
            description={`Entre ${MOTIVO_MIN} y ${MOTIVO_MAX} caracteres; queda en los logs.`}
            value={motivo}
            error={v.error('motivo')}
            onChange={(e) => {
              setMotivo(e.currentTarget.value);
              v.limpiar('motivo');
            }}
            maxLength={MOTIVO_MAX}
            autosize
            minRows={2}
          />
          <Group justify="flex-end">
            <Button variant="default" onClick={cerrar}>
              Cancelar
            </Button>
            <Button type="submit" loading={enviando}>
              Guardar
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
