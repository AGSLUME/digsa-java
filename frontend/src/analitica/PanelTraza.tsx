import { Alert, Button, Group, Loader, Stack, Table, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api, descargar, ErrorApi } from '../shared/api';
import { cifra as cifraTexto, conteo, fecha, monto } from '../shared/formato';
import { TablaAdaptable } from '../shared/TablaAdaptable';
import { useValidacion } from '../shared/validacion';
import { rutaTraza, rutaTrazaCsv, seudonimoCorto, type EstadoAnalitica, type Traza } from './tableros';

/** Cómo se muestra «la cifra» en el desglose según lo que se rastreó (gasto, unidades, dispensaciones o pacientes). */
function cifraDe(metrica: string, d: Record<string, unknown>): { etiqueta: string; valor: string } {
  switch (metrica) {
    case 'unidades':
      return { etiqueta: 'Suma de las unidades (la cifra)', valor: String(d.unidadesTexto ?? '—') };
    case 'dispensaciones':
      return { etiqueta: 'Registros de origen (la cifra)', valor: conteo((d.registros as number | undefined) ?? 0) };
    case 'pacientes':
      return { etiqueta: 'Pacientes distintos de los registros (la cifra)', valor: conteo((d.pacientes as number | undefined) ?? 0) };
    case 'gasto':
      return { etiqueta: 'Suma del valor (la cifra)', valor: String(d.gastoTexto ?? '—') };
    default:
      return { etiqueta: 'Gasto de los registros', valor: String(d.gastoTexto ?? '—') };
  }
}

/**
 * Trazabilidad de una cifra (A5 14.9): (1) el desglose (consulta estructurada, corte, filtros y n.º de registros); (2) los registros de
 * origen seudonimizados con su cargue, archivo, línea, SHA-256 y la regla de la tabla maestra (dato original → fuente → regla → dato
 * final); (3) la suma de la lista, igual a la cifra (en la métrica rastreada). La lista se pagina por llave y completa se descarga en CSV.
 */
export function PanelTraza({
  estado,
  rastreo,
  que,
  metrica = 'gasto',
}: {
  estado: EstadoAnalitica;
  rastreo: Record<string, string>;
  que: string;
  metrica?: string;
}) {
  // Cursores de las páginas visitadas: el de la página n es el id del último registro de la n − 1 (null en la primera)
  const [cursores, setCursores] = useState<(number | null)[]>([null]);
  const pagina = cursores.length;
  const ruta = rutaTraza(estado, rastreo, pagina, cursores[pagina - 1]);
  const traza = useQuery({ queryKey: ['analitica', 'traza', ruta], queryFn: () => api<Traza>(ruta), placeholderData: keepPreviousData });
  const [bajando, setBajando] = useState(false);
  // El error de la descarga queda fijo junto al botón (Anexo 5 num. 2.4)
  const v = useValidacion({ testid: 'error-descarga-traza' });
  const bajar = async () => {
    setBajando(true);
    v.reiniciar();
    const id = notifications.show({ loading: true, autoClose: false, message: 'Generando el CSV de los registros…' });
    try {
      await descargar(rutaTrazaCsv(estado, rastreo));
      notifications.update({ id, loading: false, autoClose: 4000, color: 'green', message: 'CSV de los registros descargado.' });
    } catch (e) {
      notifications.hide(id);
      v.errorServidor(e, 'No se pudo descargar la lista.');
    } finally {
      setBajando(false);
    }
  };
  if (traza.isLoading) return <Loader />;
  if (traza.error) return <Alert color="red">{traza.error instanceof ErrorApi ? traza.error.message : 'No se pudo rastrear la cifra.'}</Alert>;
  const t = traza.data;
  if (!t) return null;
  const d = t.desglose;
  const cifra = cifraDe(metrica, d);
  return (
    <Stack gap="sm" data-testid="panel-traza" ref={v.ref}>
      <Text fw={600}>{que}</Text>
      <TablaAdaptable>
        <Table withTableBorder fz="sm">
          <Table.Tbody>
            <Table.Tr>
              <Table.Th>Fecha de corte</Table.Th>
              <Table.Td>{String(d.corte ?? '—')}</Table.Td>
            </Table.Tr>
            <Table.Tr>
              <Table.Th>Tabla maestra</Table.Th>
              <Table.Td>{d.tablaMaestra ? `v${String(d.tablaMaestra)}` : 'Sin versión publicada'}</Table.Td>
            </Table.Tr>
            <Table.Tr>
              <Table.Th>Alcance</Table.Th>
              <Table.Td>{String(d.alcance ?? '—')}</Table.Td>
            </Table.Tr>
            <Table.Tr>
              <Table.Th>Periodo</Table.Th>
              <Table.Td>{String(d.periodo ?? '—')}</Table.Td>
            </Table.Tr>
            <Table.Tr>
              <Table.Th>Consulta estructurada</Table.Th>
              <Table.Td style={{ fontFamily: 'monospace', fontSize: 12, overflowWrap: 'anywhere' }}>{JSON.stringify(d.consulta)}</Table.Td>
            </Table.Tr>
            <Table.Tr>
              <Table.Th>Registros de origen</Table.Th>
              <Table.Td data-testid="traza-registros">{conteo((d.registros as number | undefined) ?? 0)}</Table.Td>
            </Table.Tr>
            <Table.Tr>
              <Table.Th>{cifra.etiqueta}</Table.Th>
              <Table.Td data-testid="traza-suma">{cifra.valor}</Table.Td>
            </Table.Tr>
            {metrica !== 'gasto' && (
              <Table.Tr>
                <Table.Th>Suma del valor</Table.Th>
                <Table.Td>{String(d.gastoTexto ?? '—')}</Table.Td>
              </Table.Tr>
            )}
          </Table.Tbody>
        </Table>
      </TablaAdaptable>
      <Group justify="space-between">
        <Text size="xs" c="dimmed">
          Paciente como seudónimo (nunca el documento). Página {pagina} de {Math.max(1, t.paginas)}.
        </Text>
        <Button size="xs" mih={{ base: 44, sm: 30 }} variant="light" onClick={bajar} loading={bajando}>
          Descargar CSV de los registros
        </Button>
      </Group>
      {v.resumen}
      <TablaAdaptable>
        <Table striped withTableBorder fz="xs" miw={1200}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Fecha</Table.Th>
              <Table.Th>Seudónimo</Table.Th>
              <Table.Th>ESM</Table.Th>
              <Table.Th>CUM y medicamento originales</Table.Th>
              <Table.Th ta="right">Cantidad</Table.Th>
              <Table.Th ta="right">Valor</Table.Th>
              <Table.Th>Cargue · archivo · línea</Table.Th>
              <Table.Th>SHA-256 del archivo</Table.Th>
              <Table.Th>Original → fuente → regla → final</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {t.registros.map((r) => (
              <Table.Tr key={r.id}>
                <Table.Td>{fecha(r.fecha)}</Table.Td>
                <Table.Td style={{ fontFamily: 'monospace' }}>{seudonimoCorto(r.paciente)}</Table.Td>
                <Table.Td>{r.esm}</Table.Td>
                <Table.Td>{[r.cumOriginal, r.medicamentoOriginal].filter(Boolean).join(' · ')}</Table.Td>
                <Table.Td ta="right">{cifraTexto(r.cantidad)}</Table.Td>
                <Table.Td ta="right">{monto(r.valor)}</Table.Td>
                <Table.Td>
                  {r.cargue}
                  {r.cargueAplicado ? ` (${r.cargueAplicado})` : ''} · {r.archivo ?? r.fuente} · línea {r.linea}
                </Table.Td>
                <Table.Td style={{ fontFamily: 'monospace', overflowWrap: 'anywhere' }}>{r.sha256 ?? '—'}</Table.Td>
                <Table.Td>
                  {r.datoOriginal ?? '—'} → {r.fuenteReferencia ?? 'sin fuente'} → {r.regla}
                  {r.tablaMaestra ? ` (v${r.tablaMaestra})` : ''} → {r.estado === 'homologado' ? (r.datoFinal ?? '—') : 'Sin homologar'}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </TablaAdaptable>
      {(pagina > 1 || t.siguiente !== null) && (
        <Group gap="xs">
          <Button
            size="xs"
            mih={{ base: 44, sm: 30 }}
            variant="default"
            disabled={pagina <= 1 || traza.isFetching}
            onClick={() => setCursores(cursores.slice(0, -1))}
          >
            Página anterior
          </Button>
          <Button
            size="xs"
            mih={{ base: 44, sm: 30 }}
            variant="default"
            disabled={t.siguiente === null || traza.isFetching}
            onClick={() => setCursores([...cursores, t.siguiente])}
          >
            Página siguiente
          </Button>
        </Group>
      )}
    </Stack>
  );
}
