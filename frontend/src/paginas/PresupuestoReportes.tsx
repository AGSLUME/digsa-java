import { Alert, Button, Card, Group, Loader, MultiSelect, ScrollArea, Select, SimpleGrid, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api, descargar } from '../shared/api';
import { conteo, fechaHora } from '../shared/formato';
import { problemaRango } from '../shared/tablero';
import { useValidacion } from '../shared/validacion';
import { errorCorreo } from '../shared/validaciones';
import {
  botonQuitar,
  celdaReporte,
  consultaReporte,
  fechasEnTexto,
  etiquetaPunto,
  FILTROS_REPORTE,
  type FiltrosReporte,
  type OpcionesPresupuesto,
  type ReportePresupuesto,
} from '../shared/presupuesto';

const FORMATOS = [
  { valor: 'xlsx', etiqueta: 'Excel' },
  { valor: 'csv', etiqueta: 'CSV' },
  { valor: 'pdf', etiqueta: 'PDF' },
  { valor: 'txt', etiqueta: 'TXT' },
];

const AGRUPACIONES = [
  { value: 'mes', label: 'Meses' },
  { value: 'bimestre', label: 'Bimestres' },
  { value: 'trimestre', label: 'Trimestres' },
  { value: 'semestre', label: 'Semestres' },
  { value: 'anio', label: 'Años' },
];

/**
 * Reportes de ejecución presupuestal (Anexo 5, 2.7 a 2.12 y §4.6: las modificaciones del presupuesto se ven en los reportes): ver en
 * pantalla, descargar en Excel, CSV, PDF o TXT con el encabezado (periodo, establecimiento y filtros) y enviar por correo.
 */
export function ReportesPresupuesto({ o }: { o: OpcionesPresupuesto }) {
  const tipos = useQuery({
    queryKey: ['presupuesto', 'reportes', 'tipos'],
    queryFn: () => api<{ tipos: Record<string, string> }>('/api/presupuesto/reportes'),
  });
  const abierta = o.vigencias.filter((v) => v.estado === 'abierta').at(-1);
  const [tipo, setTipo] = useState('ejecucion');
  const [f, setF] = useState<FiltrosReporte>({ ...FILTROS_REPORTE, vigencia: abierta ? String(abierta.id) : '' });
  const [consultada, setConsultada] = useState<string | null>(null);
  const [para, setPara] = useState('');
  const v = useValidacion({ etiquetas: { hasta: 'Hasta', para: 'Enviar por correo a' }, testid: 'reporte-error' });
  const consulta = consultaReporte(tipo, f);
  const reporte = useQuery({
    queryKey: ['presupuesto', 'reporte', tipo, consultada],
    queryFn: () => api<ReportePresupuesto>(`/api/presupuesto/reportes/${tipo}?${consultada}`),
    enabled: consultada !== null,
  });
  const enviar = useMutation({
    mutationFn: (formato: string) =>
      api<{ mensaje: string }>(`/api/presupuesto/reportes/${tipo}/enviar`, {
        cuerpo: {
          para,
          formato,
          ...Object.fromEntries(new URLSearchParams(consultada ?? consulta)),
          vigencia: f.vigencia ? Number(f.vigencia) : null,
          punto: f.punto ? Number(f.punto) : null,
        },
      }),
    onSuccess: (r) => {
      v.reiniciar();
      notifications.show({ color: 'green', message: r.mensaje });
    },
    onError: (e) => v.errorServidor(e, 'No se pudo enviar el reporte.'),
  });
  const vigencia = o.vigencias.find((x) => String(x.id) === f.vigencia);
  const conCorte = tipo === 'ejecucion' && vigencia !== undefined;
  const conFechas = tipo === 'movimientos' || tipo === 'alertas';
  // «Desde» posterior a «Hasta» (solo en los reportes con fechas): se marca y no se consulta hasta corregirlo
  const rango = conFechas ? problemaRango(f.desde, f.hasta) : null;
  const bajar = async (formato: string) => {
    // Se descarga lo consultado (sus filtros ya eran válidos); el rango solo se revisa si aún no se ha consultado
    if (!consultada && !v.validar({ hasta: rango })) return;
    try {
      const filtros = consultada ?? consulta;
      await descargar(`/api/presupuesto/reportes/${tipo}/exportar?formato=${formato}${filtros ? '&' + filtros : ''}`);
    } catch (e) {
      v.errorServidor(e, 'No se pudo descargar el reporte.');
    }
  };
  const enviarPor = (formato: string) => {
    const correo = para.trim();
    if (v.validar({ hasta: rango, para: errorCorreo(correo, 'Escriba un correo válido (nombre@dominio.co).', 'Escriba el correo del destinatario.') })) enviar.mutate(formato);
  };

  return (
    <Stack>
      <Card withBorder ref={v.ref}>
        <Stack>
          <Select
            label="Reporte"
            data={Object.entries(tipos.data?.tipos ?? {}).map(([value, label]) => ({ value, label }))}
            value={tipo}
            allowDeselect={false}
            onChange={(v) => {
              setTipo(v ?? 'ejecucion');
              setConsultada(null);
            }}
            data-testid="reporte-tipo"
          />
          <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }}>
            <Select
              label="Vigencia"
              placeholder={tipo === 'mensual' ? 'Elija la vigencia' : 'Todo el contrato'}
              clearable={tipo !== 'mensual'}
              clearButtonProps={botonQuitar('la vigencia')}
              data={o.vigencias.map((x) => ({ value: String(x.id), label: String(x.anio) }))}
              value={f.vigencia || null}
              onChange={(v) => setF({ ...f, vigencia: v ?? '', corte: '' })}
              data-testid="reporte-vigencia"
            />
            {conCorte && (
              <Select
                label="Corte"
                placeholder="Mes actual"
                clearable
                clearButtonProps={botonQuitar('el corte')}
                data={o.vigencias.length && vigencia ? mesesVigencia(vigencia.inicio, vigencia.fin) : []}
                value={f.corte || null}
                onChange={(v) => setF({ ...f, corte: v ?? '' })}
              />
            )}
            {tipo === 'mensual' && (
              <Select
                label="Agrupar por"
                data={AGRUPACIONES}
                value={f.agrupacion}
                allowDeselect={false}
                onChange={(v) => setF({ ...f, agrupacion: v ?? 'mes' })}
              />
            )}
            <MultiSelect
              label="Fuerzas"
              placeholder={f.fuerzas.length ? '' : 'Todas'}
              clearable
              clearButtonProps={botonQuitar('las fuerzas')}
              data={o.fuerzas.map((x) => ({ value: x.codigo, label: x.nombre }))}
              value={f.fuerzas}
              onChange={(v) => setF({ ...f, fuerzas: v })}
            />
            <Select
              label="Establecimiento"
              placeholder="Todos"
              clearable
              clearButtonProps={botonQuitar('el establecimiento')}
              searchable
              data={o.puntos
                .filter((p) => !f.fuerzas.length || f.fuerzas.includes(p.fuerza) || p.fuerza === 'NA')
                .map((p) => ({ value: String(p.id), label: etiquetaPunto(p) }))}
              value={f.punto || null}
              onChange={(v) => setF({ ...f, punto: v ?? '' })}
            />
            {tipo !== 'alertas' && (
              <MultiSelect
                label="Modalidades"
                placeholder={f.modalidades.length ? '' : 'Todas'}
                clearable
                clearButtonProps={botonQuitar('las modalidades')}
                data={o.modalidades.map((m) => ({ value: m.codigo, label: m.nombre }))}
                value={f.modalidades}
                onChange={(v) => setF({ ...f, modalidades: v })}
              />
            )}
            {tipo === 'movimientos' && (
              <Select
                label="Tipo de movimiento"
                placeholder="Todos"
                clearable
                clearButtonProps={botonQuitar('el tipo de movimiento')}
                data={o.tipos.map((t) => ({ value: t.codigo, label: t.nombre }))}
                value={f.tipoMovimiento || null}
                onChange={(v) => setF({ ...f, tipoMovimiento: v ?? '' })}
              />
            )}
            {conFechas && <TextInput label="Desde" type="date" value={f.desde} onChange={(e) => setF({ ...f, desde: e.currentTarget.value })} />}
            {conFechas && (
              <TextInput
                label="Hasta"
                type="date"
                value={f.hasta}
                onChange={(e) => {
                  setF({ ...f, hasta: e.currentTarget.value });
                  v.limpiar('hasta');
                }}
                error={v.error('hasta') ?? rango ?? undefined}
              />
            )}
          </SimpleGrid>
          <Group wrap="wrap" gap="xs">
            <Button
              onClick={() => {
                if (v.validar({ hasta: rango })) setConsultada(consulta);
              }}
              loading={reporte.isFetching}
              data-testid="reporte-ver"
            >
              Ver reporte
            </Button>
            {FORMATOS.map((x) => (
              <Button key={x.valor} variant="light" onClick={() => void bajar(x.valor)} data-testid={`reporte-descargar-${x.valor}`}>
                Descargar {x.etiqueta}
              </Button>
            ))}
          </Group>
          <Group wrap="wrap" align="flex-end" gap="xs">
            <TextInput
              label="Enviar por correo a"
              placeholder="nombre@dominio"
              type="email"
              value={para}
              onChange={(e) => {
                setPara(e.currentTarget.value);
                v.limpiar('para');
              }}
              error={v.error('para')}
              style={{ flex: '1 1 220px' }}
            />
            <Button variant="light" loading={enviar.isPending} onClick={() => enviarPor('xlsx')}>
              Enviar Excel
            </Button>
            <Button variant="light" loading={enviar.isPending} onClick={() => enviarPor('pdf')}>
              Enviar PDF
            </Button>
          </Group>
          {v.resumen}
        </Stack>
      </Card>
      {reporte.isLoading && <Loader />}
      {reporte.error && <Alert color="red">{reporte.error.message}</Alert>}
      {reporte.data && (
        <Card withBorder data-testid="reporte-resultado">
          <Title order={5}>{reporte.data.titulo}</Title>
          <Text size="xs" c="dimmed">
            Generado {fechaHora(reporte.data.generado)} por {reporte.data.usuario} ·{' '}
            {Object.entries(reporte.data.filtros)
              .map(([k, x]) => `${k}: ${fechasEnTexto(x ?? '')}`)
              .join(' · ')}
          </Text>
          <Text size="xs" c="dimmed" mb="xs">
            {conteo(reporte.data.total)} registro(s)
            {reporte.data.total > reporte.data.filas.length ? `; en pantalla los primeros ${conteo(reporte.data.filas.length)}, la descarga trae todos` : ''}
          </Text>
          <ScrollArea type="auto">
            <Table striped withTableBorder fz="xs" miw={Math.max(600, reporte.data.columnas.length * 110)} data-testid="reporte-tabla">
              <Table.Thead>
                <Table.Tr>
                  {reporte.data.columnas.map((c) => (
                    <Table.Th key={c}>{c}</Table.Th>
                  ))}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {reporte.data.filas.map((fila, i) => (
                  <Table.Tr key={i}>
                    {fila.map((c, j) => (
                      <Table.Td key={j} style={{ whiteSpace: 'nowrap' }}>
                        {celdaReporte(c)}
                      </Table.Td>
                    ))}
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollArea>
        </Card>
      )}
    </Stack>
  );
}

function mesesVigencia(inicio: string, fin: string): string[] {
  const r: string[] = [];
  let [a, m] = [Number(inicio.substring(0, 4)), Number(inicio.substring(5, 7))];
  const [af, mf] = [Number(fin.substring(0, 4)), Number(fin.substring(5, 7))];
  while (a < af || (a === af && m <= mf)) {
    r.push(`${a}-${String(m).padStart(2, '0')}`);
    m = m === 12 ? 1 : m + 1;
    if (m === 1) a++;
  }
  return r;
}
