import { Alert, Button, Card, Group, Loader, ScrollArea, Select, SimpleGrid, Stack, Table, Tabs, Text, TextInput, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { api, descargar, ErrorApi } from '../shared/api';

interface Opciones {
  tipos: Record<string, string>;
  formatos: string[];
  puntos: { id: number; nombre: string; sigla: string | null; unidad: string; fuerza: string }[];
  fuerzas: { codigo: string; nombre: string }[];
  componentes: { codigo: string; nombre: string }[];
  estados: Record<string, string>;
}

interface ReporteVisitas {
  titulo: string;
  generado: string;
  usuario: string;
  filtros: Record<string, string>;
  columnas: string[];
  filas: (string | number | null)[][];
}

interface Filtros {
  desde: string;
  hasta: string;
  puntoId: string;
  fuerza: string;
  unidad: string;
  estado: string;
  componente: string;
}

const FORMATOS: { valor: string; etiqueta: string }[] = [
  { valor: 'xlsx', etiqueta: 'Excel' },
  { valor: 'csv', etiqueta: 'CSV' },
  { valor: 'pdf', etiqueta: 'PDF' },
  { valor: 'txt', etiqueta: 'TXT' },
];

function hoy(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** Orden de las pestañas: la distribución auditor–punto (solo coordinación y DIGSA) va al final. */
const ORDEN_TIPOS = ['detalle', 'lugar', 'proceso', 'distribucion'];

export function ordenTipos(tipos: Record<string, string>): [string, string][] {
  const pos = (k: string) => (ORDEN_TIPOS.includes(k) ? ORDEN_TIPOS.indexOf(k) : ORDEN_TIPOS.length);
  return Object.entries(tipos).sort(([a], [b]) => pos(a) - pos(b));
}

/** La distribución auditor–punto solo se filtra por el periodo: el lugar, la fuerza, la unidad y el estado no aplican. */
export function soloPeriodo(tipo: string): boolean {
  return tipo === 'distribucion';
}

/** Parámetros de la consulta del reporte (solo los filtros con valor que aplican al tipo). */
export function parametros(f: Filtros, tipo: string): URLSearchParams {
  const p = new URLSearchParams();
  if (f.desde) p.set('desde', f.desde);
  if (f.hasta) p.set('hasta', f.hasta);
  if (soloPeriodo(tipo)) return p;
  if (f.puntoId) p.set('punto_id', f.puntoId);
  if (f.fuerza) p.set('fuerza', f.fuerza);
  if (f.unidad) p.set('unidad', f.unidad);
  if (f.estado) p.set('estado', f.estado);
  if (tipo === 'proceso' && f.componente) p.set('componente', f.componente);
  return p;
}

/** Celda: números con coma decimal (como en la exportación). */
function celda(v: string | number | null): string {
  if (v === null || v === undefined) return '';
  if (typeof v === 'number') return Number.isInteger(v) ? String(v) : v.toFixed(1).replace('.', ',');
  return v;
}

/** Reportes de visitas (Anexo 5, prueba 13): detalle, consolidado por lugar y por proceso, con descarga y envío por correo. */
export function Reportes() {
  const opciones = useQuery({ queryKey: ['reportes', 'opciones'], queryFn: () => api<Opciones>('/api/reportes') });
  const [tipo, setTipo] = useState('detalle');
  const [f, setF] = useState<Filtros>({
    desde: `${new Date().getFullYear()}-01-01`,
    hasta: hoy(),
    puntoId: '',
    fuerza: '',
    unidad: '',
    estado: '',
    componente: '',
  });
  const [aplicado, setAplicado] = useState<{ tipo: string; f: Filtros } | null>(null);
  const [para, setPara] = useState('');
  const [formatoEnvio, setFormatoEnvio] = useState('xlsx');

  const consulta = aplicado ? `/api/reportes/${aplicado.tipo}?${parametros(aplicado.f, aplicado.tipo).toString()}` : null;
  const reporte = useQuery({
    queryKey: ['reportes', consulta],
    queryFn: () => api<ReporteVisitas>(consulta!),
    enabled: consulta !== null,
  });

  const enviar = useMutation({
    mutationFn: () => {
      const a = aplicado ?? { tipo, f };
      const cuerpo: Record<string, unknown> = { para, formato: formatoEnvio };
      parametros(a.f, a.tipo).forEach((v, k) => {
        cuerpo[k === 'punto_id' ? 'puntoId' : k] = k === 'punto_id' ? Number(v) : v;
      });
      return api<{ mensaje: string }>(`/api/reportes/${a.tipo}/enviar`, { cuerpo });
    },
    onSuccess: (r) => notifications.show({ color: 'green', message: r.mensaje }),
    onError: (e) => notifications.show({ color: 'red', message: e instanceof ErrorApi ? e.message : 'No se pudo enviar el reporte.' }),
  });

  const puntos = useMemo(
    () => (opciones.data?.puntos ?? []).map((p) => ({ value: String(p.id), label: `${p.sigla ? p.sigla + ' · ' : ''}${p.nombre}` })),
    [opciones.data],
  );

  const bajar = async (formato: string) => {
    const a = aplicado ?? { tipo, f };
    try {
      await descargar(`/api/reportes/${a.tipo}/exportar?formato=${formato}&${parametros(a.f, a.tipo).toString()}`);
    } catch (e) {
      notifications.show({ color: 'red', message: e instanceof ErrorApi ? e.message : 'No se pudo descargar el reporte.' });
    }
  };

  if (opciones.isLoading) return <Loader />;
  if (opciones.error) return <Alert color="red">{(opciones.error as Error).message}</Alert>;
  const o = opciones.data!;

  return (
    <Stack>
      <Title order={3}>Reportes de visitas</Title>
      <Tabs value={tipo} onChange={(v) => setTipo(v ?? 'detalle')}>
        <Tabs.List>
          {ordenTipos(o.tipos).map(([k, v]) => (
            <Tabs.Tab key={k} value={k}>
              {v}
            </Tabs.Tab>
          ))}
        </Tabs.List>
      </Tabs>
      <Card withBorder data-testid="filtros-reporte">
        <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }}>
          <TextInput label="Desde" type="date" value={f.desde} onChange={(e) => setF({ ...f, desde: e.currentTarget.value })} />
          <TextInput label="Hasta" type="date" value={f.hasta} onChange={(e) => setF({ ...f, hasta: e.currentTarget.value })} />
          {!soloPeriodo(tipo) && (
            <>
              <Select label="Lugar" searchable clearable data={puntos} value={f.puntoId || null} onChange={(v) => setF({ ...f, puntoId: v ?? '' })} />
              <Select
                label="Fuerza"
                clearable
                data={o.fuerzas.map((x) => ({ value: x.codigo, label: x.nombre }))}
                value={f.fuerza || null}
                onChange={(v) => setF({ ...f, fuerza: v ?? '' })}
              />
              <Select
                label="Unidad"
                clearable
                data={['ESM', 'FA', 'NC']}
                value={f.unidad || null}
                onChange={(v) => setF({ ...f, unidad: v ?? '' })}
              />
              <Select
                label="Estado"
                clearable
                data={Object.entries(o.estados).map(([value, label]) => ({ value, label }))}
                value={f.estado || null}
                onChange={(v) => setF({ ...f, estado: v ?? '' })}
              />
            </>
          )}
          {tipo === 'proceso' && (
            <Select
              label="Componente"
              clearable
              data={o.componentes.map((c) => ({ value: c.codigo, label: `${c.codigo} · ${c.nombre}` }))}
              value={f.componente || null}
              onChange={(v) => setF({ ...f, componente: v ?? '' })}
            />
          )}
        </SimpleGrid>
        {soloPeriodo(tipo) && (
          <Text size="sm" c="dimmed" mt="sm" data-testid="ayuda-distribucion">
            Por auditor activo: los puntos que tiene asignados o que visita en el periodo, la distancia en línea recta de su municipio base al
            municipio de acceso del punto (coordenadas del DANE) y sus visitas agendadas y cerradas. Justifica el reparto de la propuesta de agenda
            por cercanía y carga.
          </Text>
        )}
        <Group mt="md">
          <Button onClick={() => setAplicado({ tipo, f: { ...f } })}>Generar reporte</Button>
        </Group>
      </Card>

      {reporte.isFetching && <Loader />}
      {reporte.error && <Alert color="red">{(reporte.error as Error).message}</Alert>}
      {reporte.data && (
        <Card withBorder data-testid="reporte">
          <Stack gap={4} data-testid="encabezado-reporte">
            <Title order={4}>{reporte.data.titulo}</Title>
            <Text size="sm">Generado: {reporte.data.generado}</Text>
            <Text size="sm">Usuario: {reporte.data.usuario}</Text>
            {Object.entries(reporte.data.filtros).map(([k, v]) => (
              <Text size="sm" key={k}>
                {k}: {v}
              </Text>
            ))}
            <Text size="sm">Registros: {reporte.data.filas.length}</Text>
          </Stack>
          <Group mt="sm" gap="xs">
            {FORMATOS.map((x) => (
              <Button key={x.valor} size="xs" variant="light" onClick={() => void bajar(x.valor)}>
                Descargar {x.etiqueta}
              </Button>
            ))}
          </Group>
          <ScrollArea type="auto" mt="sm">
            <Table striped miw={900} data-testid="tabla-reporte">
              <Table.Thead>
                <Table.Tr>
                  {reporte.data.columnas.map((c) => (
                    <Table.Th key={c}>{c}</Table.Th>
                  ))}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {reporte.data.filas.length === 0 && (
                  <Table.Tr>
                    <Table.Td colSpan={reporte.data.columnas.length}>Sin registros con estos filtros.</Table.Td>
                  </Table.Tr>
                )}
                {reporte.data.filas.map((fila, i) => (
                  <Table.Tr key={i}>
                    {fila.map((v, j) => (
                      <Table.Td key={j}>{celda(v)}</Table.Td>
                    ))}
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollArea>
          <Group mt="md" align="flex-end" wrap="wrap">
            <TextInput
              label="Enviar a"
              type="email"
              placeholder="correo@sanidad.mil.co"
              value={para}
              onChange={(e) => setPara(e.currentTarget.value)}
            />
            <Select
              label="Formato"
              data={FORMATOS.map((x) => ({ value: x.valor, label: x.etiqueta }))}
              value={formatoEnvio}
              onChange={(v) => setFormatoEnvio(v ?? 'xlsx')}
            />
            <Button loading={enviar.isPending} disabled={!para} onClick={() => enviar.mutate()}>
              Enviar por correo
            </Button>
          </Group>
        </Card>
      )}
    </Stack>
  );
}
