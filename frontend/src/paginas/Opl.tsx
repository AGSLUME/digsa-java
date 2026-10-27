import { Alert, Anchor, Badge, Button, Center, Loader, Stack, Table, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../shared/api';
import { conteo, fechaHora } from '../shared/formato';
import { COLOR_FASE, NOMBRE_FASE, NOMBRE_PLAN, type VisitaOpl } from '../shared/opl';
import { RespuestaOpl } from '../shared/RespuestaOpl';
import { EncabezadoPagina } from '../shared/EncabezadoPagina';
import { TablaAdaptable } from '../shared/TablaAdaptable';

/**
 * «Respuesta a hallazgos» de los perfiles OPL nacional (todo el país) y OPL regional (su regional): visitas cerradas con el enlace del
 * acta vigente y, al abrir una, la misma respuesta que da el enlace del acta.
 */
export function Opl() {
  const navegar = useNavigate();
  const lista = useQuery({ queryKey: ['opl', 'lista'], queryFn: () => api<VisitaOpl[]>('/api/opl/visitas') });
  return (
    <Stack>
      <EncabezadoPagina
        titulo="Respuesta a hallazgos"
        fuente="Anexo 3"
        descripcion="Visitas con acta enviada y hallazgos por responder: acepte u objete cada hallazgo dentro de los 3 días hábiles y remita el plan de mejoramiento firmado dentro de los 5 días hábiles desde el envío del acta."
      />
      {lista.error && <Alert color="red">{lista.error.message}</Alert>}
      {lista.isLoading ? (
        <Center py="xl">
          <Loader />
        </Center>
      ) : (
        <TablaAdaptable>
          <Table striped highlightOnHover miw={1040} data-testid="tabla-opl">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Número</Table.Th>
                <Table.Th>Punto</Table.Th>
                <Table.Th>Enviado</Table.Th>
                <Table.Th>Hallazgos</Table.Th>
                <Table.Th>Sin respuesta</Table.Th>
                <Table.Th>Faltan en plan</Table.Th>
                <Table.Th>Plan</Table.Th>
                <Table.Th>Fase</Table.Th>
                <Table.Th>Vencimientos</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {(lista.data ?? []).length === 0 && (
                <Table.Tr>
                  <Table.Td colSpan={10}>No hay visitas con hallazgos por responder.</Table.Td>
                </Table.Tr>
              )}
              {(lista.data ?? []).map((v) => (
                <Table.Tr key={v.visitaId} data-testid={'fila-opl-' + v.visitaId}>
                  <Table.Td style={{ whiteSpace: 'nowrap' }}>{v.numero}</Table.Td>
                  <Table.Td>{v.punto}</Table.Td>
                  <Table.Td style={{ whiteSpace: 'nowrap' }}>{fechaHora(v.enviado)}</Table.Td>
                  <Table.Td>{conteo(v.hallazgos)}</Table.Td>
                  <Table.Td>{conteo(v.sinRespuesta)}</Table.Td>
                  <Table.Td>{conteo(v.faltanPlan)}</Table.Td>
                  <Table.Td>
                    <Badge variant="light" color={v.planEstado === 'firmado' ? 'green' : 'gray'}>
                      {NOMBRE_PLAN[v.planEstado]}
                    </Badge>
                  </Table.Td>
                  <Table.Td>
                    <Badge color={COLOR_FASE[v.fase]}>{NOMBRE_FASE[v.fase]}</Badge>
                  </Table.Td>
                  <Table.Td style={{ whiteSpace: 'nowrap' }}>
                    <Text size="xs">Objetar: {fechaHora(v.venceObjeciones)}</Text>
                    <Text size="xs" fw={700}>
                      Plan: {fechaHora(v.vencePlan)}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Button size="xs" aria-label={'Responder la visita ' + v.numero} onClick={() => navegar(`/opl/visita/${v.visitaId}`)}>
                      Responder
                    </Button>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </TablaAdaptable>
      )}
    </Stack>
  );
}

/** Detalle de una visita para responder, con el usuario OPL. */
export function OplVisita() {
  const { id = '' } = useParams();
  return (
    <Stack>
      <Anchor component={Link} to="/opl" size="sm">
        Volver a Respuesta a hallazgos
      </Anchor>
      <RespuestaOpl key={id} base={`/api/opl/visitas/${id}`} />
    </Stack>
  );
}
