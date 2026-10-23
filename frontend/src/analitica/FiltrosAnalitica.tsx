import { Badge, CloseButton, Group, MultiSelect, Select, SegmentedControl, SimpleGrid, Stack, Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useEffect, useState, type ReactNode } from 'react';
import { api } from '../shared/api';
import {
  fechaCorta,
  problemaPeriodo,
  quitarFiltro,
  SIN_HOMOLOGAR,
  textoValor,
  type CatalogoAnalitica,
  type Corte,
  type EstadoAnalitica,
  type FiltroAplicado,
  type Opciones,
  type Variable,
} from './tableros';

/** Variables de la barra de filtros con su etiqueta (las opciones vienen del servidor, ya recortadas al alcance). */
const VISIBLES: { variable: Variable; etiqueta: string; sinHomologar?: boolean }[] = [
  { variable: 'fuerza', etiqueta: 'Fuerza' },
  { variable: 'disan', etiqueta: 'DISAN' },
  { variable: 'regional', etiqueta: 'Regional' },
  { variable: 'esm', etiqueta: 'ESM' },
  { variable: 'modalidad', etiqueta: 'Modalidad' },
  { variable: 'atc', etiqueta: 'ATC (cualquier nivel)', sinHomologar: true },
  { variable: 'principio', etiqueta: 'Principio activo', sinHomologar: true },
  { variable: 'medicamento', etiqueta: 'Medicamento', sinHomologar: true },
  { variable: 'cum', etiqueta: 'CUM' },
  { variable: 'ssfm', etiqueta: 'Código SSFM' },
  { variable: 'cie10', etiqueta: 'CIE-10 (capítulo, grupo o código)' },
  { variable: 'estado', etiqueta: 'Estado de homologación' },
];

/** Selección múltiple de una variable: las opciones se piden al abrir la lista (dentro del alcance y visibles al corte). */
function FiltroMultiple({
  variable,
  etiqueta,
  sinHomologar,
  estado,
  alCambiar,
}: {
  variable: Variable;
  etiqueta: string;
  sinHomologar?: boolean;
  estado: EstadoAnalitica;
  alCambiar: (e: EstadoAnalitica) => void;
}) {
  const elegidos = estado.filtros[variable] ?? [];
  const [abierto, setAbierto] = useState(elegidos.length > 0);
  const corte = estado.generales.corte ?? '';
  // La búsqueda va al servidor (con antirrebote): la lista trae hasta 300 opciones y con más hay que escribir para encontrar el resto
  const [buscar, setBuscar] = useState('');
  const [texto] = useDebouncedValue(buscar.trim(), 400);
  const ruta = `/api/analitica/opciones?${new URLSearchParams({ variable, ...(corte ? { corte } : {}), ...(texto ? { texto } : {}) }).toString()}`;
  const opciones = useQuery({
    queryKey: ['analitica', 'opciones', variable, corte, texto],
    placeholderData: keepPreviousData,
    queryFn: () => api<Opciones>(ruta),
    enabled: abierto,
    staleTime: 5 * 60_000,
  });
  const datos = [...(opciones.data?.opciones ?? [])];
  if (sinHomologar) datos.push({ valor: SIN_HOMOLOGAR, etiqueta: 'Sin homologar' });
  // Lo elegido siempre está en la lista (aunque las opciones aún no hayan llegado)
  for (const v of elegidos) if (!datos.some((d) => d.valor === v)) datos.push({ valor: v, etiqueta: textoValor(undefined, v) });
  return (
    <MultiSelect
      label={etiqueta}
      placeholder={elegidos.length ? undefined : 'Todas'}
      data={datos.map((d) => ({ value: d.valor, label: d.etiqueta }))}
      value={elegidos}
      onChange={(v) => {
        const filtros = { ...estado.filtros };
        if (v.length) filtros[variable] = v;
        else delete filtros[variable];
        alCambiar({ ...estado, filtros });
      }}
      onDropdownOpen={() => setAbierto(true)}
      searchValue={buscar}
      onSearchChange={setBuscar}
      searchable
      clearable
      limit={300}
      maxDropdownHeight={280}
      nothingFoundMessage={
        opciones.isFetching ? 'Cargando…' : opciones.isError ? 'No se pudieron cargar las opciones; reintente.' : 'Sin opciones en su alcance'
      }
      error={opciones.isError ? 'No se pudieron cargar las opciones; reintente.' : undefined}
      description={opciones.data?.truncado ? 'Más de 300 resultados: escriba más letras para acotar.' : undefined}
      data-testid={`filtro-${variable}`}
    />
  );
}

/**
 * Barra de filtros (A5 4.7.1, 14.5 y A4): periodo con granularidad, fecha de corte, comparación, vista según el alcance y las variables
 * con selección múltiple («Todas» por defecto). Todo vive en la URL: el mismo enlace abre el mismo tablero con los mismos filtros.
 */
export function FiltrosAnalitica({
  estado,
  catalogo,
  alCambiar,
  mostrarVista = true,
  extra,
  aplicados: textos,
}: {
  estado: EstadoAnalitica;
  catalogo: CatalogoAnalitica;
  alCambiar: (e: EstadoAnalitica, reemplazar?: boolean) => void;
  mostrarVista?: boolean;
  extra?: ReactNode;
  /** Filtros del último resultado con sus nombres (las pastillas muestran el nombre, no el código). */
  aplicados?: FiltroAplicado[];
}) {
  const cortes = useQuery({ queryKey: ['analitica', 'cortes'], queryFn: () => api<Corte[]>('/api/analitica/cortes'), staleTime: 60_000 });
  const g = estado.generales;
  const general = (k: keyof EstadoAnalitica['generales'], v: string | null) => {
    const generales = { ...g };
    if (v) generales[k] = v;
    else delete generales[k];
    alCambiar({ ...estado, generales });
  };
  // Las fechas se aplican al salir del campo o con Enter (no con cada dígito: al escribir el año pasan fechas intermedias válidas como
  // 0202-01-01 y cada una lanzaría y registraría una consulta completa) y reemplazan la entrada del historial
  const [desde, setDesde] = useState(g.desde ?? '');
  const [hasta, setHasta] = useState(g.hasta ?? '');
  useEffect(() => setDesde(g.desde ?? ''), [g.desde]);
  useEffect(() => setHasta(g.hasta ?? ''), [g.hasta]);
  const aplicarFecha = (k: 'desde' | 'hasta', v: string) => {
    if ((g[k] ?? '') === v) return;
    const generales = { ...g };
    if (v) generales[k] = v;
    else delete generales[k];
    alCambiar({ ...estado, generales }, true);
  };
  const problema = problemaPeriodo(desde || undefined, hasta || undefined);
  const aplicados = Object.entries(estado.filtros).flatMap(([k, vs]) => (vs ?? []).map((v) => ({ variable: k as Variable, valor: v })));
  return (
    <Stack gap="sm">
      <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="sm">
        <TextInput
          type="date"
          label="Desde"
          value={desde}
          onChange={(e) => setDesde(e.currentTarget.value)}
          onBlur={() => aplicarFecha('desde', desde)}
          onKeyDown={(e) => e.key === 'Enter' && aplicarFecha('desde', desde)}
          data-testid="filtro-desde"
        />
        <TextInput
          type="date"
          label="Hasta"
          value={hasta}
          onChange={(e) => setHasta(e.currentTarget.value)}
          onBlur={() => aplicarFecha('hasta', hasta)}
          onKeyDown={(e) => e.key === 'Enter' && aplicarFecha('hasta', hasta)}
          data-testid="filtro-hasta"
        />
        <Select
          label="Granularidad"
          data={catalogo.granularidades.map((x) => ({
            value: x.codigo,
            label: x.disponible ? x.etiqueta : `${x.etiqueta} (no disponible en el insumo)`,
            disabled: !x.disponible,
          }))}
          value={g.granularidad ?? 'mes'}
          onChange={(v) => general('granularidad', v === 'mes' ? null : v)}
          allowDeselect={false}
          data-testid="filtro-granularidad"
        />
        <Select
          label="Comparar con"
          data={catalogo.comparaciones.map((x) => ({ value: x.codigo, label: x.etiqueta }))}
          value={g.comparacion ?? 'ninguna'}
          onChange={(v) => general('comparacion', v === 'ninguna' ? null : v)}
          allowDeselect={false}
          data-testid="filtro-comparacion"
        />
        <Select
          label="Fecha de corte"
          placeholder="Último cargue aplicado"
          data={(cortes.data ?? []).map((c) => ({ value: String(c.id), label: c.texto + (c.reemplazado ? ' (reemplazado)' : '') }))}
          value={g.corte ?? null}
          onChange={(v) => general('corte', v)}
          clearable
          data-testid="filtro-corte"
        />
        {VISIBLES.map((v) => (
          <FiltroMultiple key={v.variable} {...v} estado={estado} alCambiar={alCambiar} />
        ))}
      </SimpleGrid>
      {problema && (
        <Text size="sm" c="red.8" role="alert">
          {problema}
        </Text>
      )}
      <Group gap="sm" wrap="wrap">
        {mostrarVista && catalogo.niveles.length > 1 && (
          <SegmentedControl
            aria-label="Vista"
            data={catalogo.niveles.map((n) => ({ value: n.codigo, label: n.etiqueta }))}
            value={g.nivel ?? catalogo.niveles[0]?.codigo ?? 'nacional'}
            onChange={(v) => general('nivel', v === catalogo.niveles[0]?.codigo ? null : v)}
          />
        )}
        {extra}
      </Group>
      {aplicados.length > 0 && (
        <Group gap={6} wrap="wrap" aria-label="Filtros aplicados">
          <Text size="xs" c="dimmed">
            Filtros aplicados:
          </Text>
          {aplicados.map((a) => (
            <Badge
              key={a.variable + a.valor}
              variant="light"
              rightSection={
                <CloseButton
                  size="sm"
                  mih={{ base: 44, sm: 0 }}
                  miw={{ base: 44, sm: 0 }}
                  aria-label={`Quitar ${a.variable} ${a.valor}`}
                  onClick={() => alCambiar(quitarFiltro(estado, a.variable, a.valor))}
                />
              }
            >
              {VISIBLES.find((x) => x.variable === a.variable)?.etiqueta.split(' (')[0]}: {nombreFiltro(textos, a.variable, a.valor)}
            </Badge>
          ))}
        </Group>
      )}
      {g.desde || g.hasta ? (
        <Text size="xs" c="dimmed">
          Periodo elegido: {fechaCorta(g.desde) ?? '—'} – {fechaCorta(g.hasta) ?? '—'}
        </Text>
      ) : null}
    </Stack>
  );
}

/** Nombre de un valor de filtro para su pastilla: el del último resultado (catálogo), si no el texto genérico del valor. */
function nombreFiltro(aplicados: FiltroAplicado[] | undefined, variable: string, valor: string): string {
  const f = aplicados?.find((x) => x.variable === variable);
  const i = f ? f.valores.indexOf(valor) : -1;
  return f && i >= 0 && f.textos[i] ? f.textos[i] : textoValor(undefined, valor);
}
