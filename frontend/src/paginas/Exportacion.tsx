import { Alert, Badge, Button, Center, Group, List, Loader, Paper, SimpleGrid, Stack, Text, Title } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api, marcarActividad } from '../shared/api';
import {
  COLOR_ESTADO,
  enCurso,
  estadoVisible,
  intervaloSondeo,
  NOMBRE_ESTADO,
  rutaDescarga,
  tamanoLegible,
  type Exportacion as Pedido,
} from '../shared/exportacion';
import { conteo, fechaHora } from '../shared/formato';
import { useValidacion } from '../shared/validacion';
import { EncabezadoPagina } from '../shared/EncabezadoPagina';

const CLAVE = ['exportacion'];

/**
 * «Exportación de datos» (solo el Administrador): pide la exportación completa, que se genera en segundo plano; la pantalla consulta
 * el estado cada pocos segundos solo mientras haya una en curso y ofrece la descarga cuando está lista (enlace directo: el navegador
 * escribe el ZIP en disco en flujo).
 */
export function Exportacion() {
  const qc = useQueryClient();
  const v = useValidacion({ testid: 'error-generar-exportacion' });
  const lista = useQuery({
    queryKey: CLAVE,
    queryFn: () => api<Pedido[]>('/api/exportacion'),
    refetchInterval: (q) => intervaloSondeo(q.state.data),
    refetchIntervalInBackground: true,
  });
  const generar = useMutation({
    mutationFn: () => api<Pedido>('/api/exportacion', { metodo: 'POST' }),
    onSuccess: () => v.reiniciar(),
    onError: (e) => v.errorServidor(e, 'No se pudo pedir la exportación.'),
    onSettled: () => qc.invalidateQueries({ queryKey: CLAVE }),
  });
  const pedidos = lista.data ?? [];
  const hayEnCurso = pedidos.some(enCurso);

  return (
    <Stack ref={v.ref}>
      <EncabezadoPagina titulo="Exportación de datos" fuente="Anexo 5 · 1.15" />
      <Text size="sm">Descarga todos los datos de la aplicación en un archivo ZIP con:</Text>
      <List size="sm" spacing={4}>
        <List.Item>
          Un archivo CSV por tabla (usuarios, puntos, auditores, visitas, checklist, actas, hallazgos, respuestas del OPL, propuestas de agenda,
          bitácora y catálogos), en UTF-8 con BOM, separado por punto y coma («;») y con coma decimal.
        </List.Item>
        <List.Item>Un archivo LEEME.txt con el formato, las columnas excluidas y el número de filas y la huella (SHA-256) de cada tabla.</List.Item>
        <List.Item>
          La carpeta de soportes de cada visita (evidencias, actas, firmas y soportes del OPL) con un índice que trae la huella de cada archivo.
        </List.Item>
      </List>
      <Text size="sm">No incluye contraseñas ni las huellas de los enlaces y códigos de acceso.</Text>
      <Alert color="yellow" title="Datos personales (Ley 1581 de 2012)" data-testid="aviso-ley-1581">
        El archivo contiene datos personales. Guárdelo en un lugar seguro y no lo envíe por correo. La generación y la descarga quedan registradas en
        la bitácora.
      </Alert>
      <Text size="sm" c="dimmed">
        La exportación se genera en segundo plano y puede tardar varios minutos; puede seguir trabajando y volver a esta pantalla. Queda disponible
        para descargar por un tiempo limitado y después se borra.
      </Text>
      <Group>
        <Button onClick={() => generar.mutate()} loading={generar.isPending} disabled={hayEnCurso}>
          Generar exportación completa
        </Button>
        {hayEnCurso && (
          <Text size="sm" c="dimmed">
            Hay una exportación en curso.
          </Text>
        )}
      </Group>
      {v.resumen}

      <Title order={4} mt="md">
        Sus exportaciones
      </Title>
      {lista.error && <Alert color="red">{lista.error.message}</Alert>}
      {lista.isLoading && (
        <Center py="md">
          <Loader />
        </Center>
      )}
      {lista.data && pedidos.length === 0 && <Text c="dimmed">Aún no ha generado exportaciones.</Text>}
      {pedidos.map((p) => (
        <Fila key={p.id} p={p} />
      ))}
    </Stack>
  );
}

function Dato({ titulo, children }: { titulo: string; children: ReactNode }) {
  return (
    <div>
      <Text size="xs" c="dimmed">
        {titulo}
      </Text>
      <Text size="sm" style={{ overflowWrap: 'anywhere' }}>
        {children}
      </Text>
    </div>
  );
}

function Fila({ p }: { p: Pedido }) {
  const estado = estadoVisible(p);
  return (
    <Paper withBorder p="sm" data-testid={'exportacion-' + p.id}>
      <Group justify="space-between" gap="xs" wrap="wrap">
        <Text fw={700}>Solicitada el {fechaHora(p.creado)}</Text>
        <Group gap="xs">
          {enCurso(p) && <Loader size="xs" />}
          <Badge color={COLOR_ESTADO[estado]} data-testid="estado">
            {NOMBRE_ESTADO[estado]}
          </Badge>
        </Group>
      </Group>
      {estado === 'lista' && (
        <SimpleGrid cols={{ base: 2, sm: 4 }} spacing="xs" mt="xs">
          <Dato titulo="Terminada">{fechaHora(p.terminado)}</Dato>
          <Dato titulo="Tamaño">{tamanoLegible(p.tamano)}</Dato>
          <Dato titulo="Tablas · filas">
            {conteo(p.tablas)} · {conteo(p.filas)}
          </Dato>
          <Dato titulo="Soportes">{conteo(p.soportes)}</Dato>
          <Dato titulo="Vence">{fechaHora(p.vence)}</Dato>
        </SimpleGrid>
      )}
      {estado === 'lista' && ((p.huellasDistintas ?? 0) > 0 || (p.noEncontrados ?? 0) > 0) && (
        <Text size="sm" c="orange" mt="xs">
          {conteo(p.huellasDistintas ?? 0)} soporte(s) con huella distinta y {conteo(p.noEncontrados ?? 0)} no encontrado(s) en el almacén: vea el
          índice y el LEEME del ZIP.
        </Text>
      )}
      {estado === 'lista' && (
        <Group mt="sm">
          {/* Enlace directo: el navegador descarga en flujo con el nombre que da el servidor (no se carga el ZIP en memoria) */}
          <Button component="a" href={rutaDescarga(p.id)} onClick={() => marcarActividad()} aria-label="Descargar la exportación">
            Descargar
          </Button>
        </Group>
      )}
      {enCurso(p) && (
        <Text size="sm" c="dimmed" mt="xs">
          {p.estado === 'pendiente' ? 'En cola: empieza en cuanto termine la anterior.' : `Generando desde ${fechaHora(p.iniciado)}.`}
        </Text>
      )}
      {estado === 'fallida' && (
        <Alert color="red" mt="xs" data-testid="error-exportacion">
          {p.error || 'No se pudo generar la exportación.'} Puede generar una nueva.
        </Alert>
      )}
      {estado === 'vencida' && (
        <Text size="sm" c="dimmed" mt="xs">
          Venció{p.vence ? ` el ${fechaHora(p.vence)}` : ''} y se borró. Genere una nueva si la necesita.
        </Text>
      )}
    </Paper>
  );
}
