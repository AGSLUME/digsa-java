import { Box, Button, Card, Group, ScrollArea, Stack, Table, Text, Title, UnstyledButton } from '@mantine/core';
import { useState, type ReactNode } from 'react';
import { BarraHorizontal } from '../shared/Barras';
import {
  colorCalor,
  compacto,
  descripcionSerie,
  filasGrafico,
  indiceColumna,
  intensidad,
  maximoEje,
  numero,
  puntosSerie,
  type Fila,
  type Tabla,
} from './tableros';

/** Acciones sobre una fila: bajar de vista (clic en la etiqueta) y rastrear la cifra (solo con la pantalla de trazabilidad). */
export interface AccionesFila {
  /** `metrica`: qué es la cifra (gasto, unidades, dispensaciones o pacientes), para mostrar en la traza la suma que corresponde. */
  alRastrear?: (rastreo: Record<string, string>, que: string, metrica?: string) => void;
  alBajar?: (rastreo: Record<string, string>) => boolean;
}

/** Alto mínimo de 44 px en pantallas pequeñas (táctiles); en escritorio, el tamaño compacto. */
export const TACTIL = { base: 44, sm: 0 };

/** Botón «Ver tabla / Ver gráfico» con aria-expanded (como shared/Barras). */
function Alternar({ tabla, setTabla }: { tabla: boolean; setTabla: (t: boolean) => void }) {
  return (
    <Button size="compact-xs" mih={TACTIL} variant="subtle" onClick={() => setTabla(!tabla)} aria-expanded={tabla}>
      {tabla ? 'Ver gráfico' : 'Ver tabla'}
    </Button>
  );
}

/** Tabla de un resultado: textos con el formato 2.4, fila Total en negrita, «Rastrear» por fila si se puede. */
export function TablaResultado({ tabla, acciones, testid }: { tabla: Tabla; acciones?: AccionesFila; testid?: string }) {
  const rastrea = !!acciones?.alRastrear;
  return (
    <ScrollArea type="auto" data-testid={testid}>
      <Table striped withTableBorder fz="sm" miw={tabla.columnas.length * 110}>
        <Table.Thead>
          <Table.Tr>
            {tabla.columnas.map((c, i) => (
              <Table.Th key={c.clave} ta={i === 0 || c.tipo === 'TEXTO' ? 'left' : 'right'}>
                {c.etiqueta}
              </Table.Th>
            ))}
            {rastrea && <Table.Th>Rastrear</Table.Th>}
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {tabla.filas.map((f) => (
            <Table.Tr key={f.clave} fw={f.tipo === 'total' ? 700 : undefined}>
              {f.textos.map((t, i) => (
                <Table.Td
                  key={i}
                  ta={i === 0 || tabla.columnas[i]?.tipo === 'TEXTO' ? 'left' : 'right'}
                  style={{ whiteSpace: i === 0 ? 'normal' : 'nowrap' }}
                >
                  {t}
                </Table.Td>
              ))}
              {rastrea && (
                <Table.Td>
                  {f.rastreo && (
                    <Button
                      size="compact-xs"
                      mih={TACTIL}
                      variant="light"
                      onClick={() => acciones!.alRastrear!(f.rastreo!, `${tabla.titulo}: ${f.textos[0]}`, tabla.valorGrafico ?? 'gasto')}
                    >
                      Rastrear
                    </Button>
                  )}
                </Table.Td>
              )}
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </ScrollArea>
  );
}

function Encabezado({ titulo, derecha }: { titulo: string; derecha: ReactNode }) {
  return (
    <Group justify="space-between" mb="xs" wrap="wrap" gap="xs">
      <Title order={5}>{titulo}</Title>
      {derecha}
    </Group>
  );
}

/**
 * Barras horizontales propias de analítica: reusa BarraHorizontal (etiqueta y valor en texto, barra aria-hidden) sin tocar
 * shared/Barras.tsx. La etiqueta baja de vista cuando se puede (Fuerza ⇒ vista por Fuerza; ESM ⇒ vista por ESM).
 */
export function BarrasAnalitica({ tabla, acciones }: { tabla: Tabla; acciones?: AccionesFila }) {
  const [verTabla, setVerTabla] = useState(false);
  const col = indiceColumna(tabla, tabla.valorGrafico);
  const filas = filasGrafico(tabla);
  const max = Math.max(0, ...filas.map((f) => numero(f.valores[col])));
  return (
    <Card withBorder data-testid={`grafico-${tabla.id}`}>
      <Encabezado titulo={tabla.titulo} derecha={<Alternar tabla={verTabla} setTabla={setVerTabla} />} />
      {filas.length === 0 ? (
        <Text size="sm" c="dimmed">
          Sin dispensaciones con estos filtros.
        </Text>
      ) : verTabla || col < 0 ? (
        <TablaResultado tabla={tabla} acciones={acciones} />
      ) : (
        <Stack gap="xs">
          {filas.map((f) => (
            <FilaBarra key={f.clave} f={f} col={col} max={max} acciones={acciones} contexto={tabla.titulo} metrica={tabla.valorGrafico ?? 'gasto'} />
          ))}
        </Stack>
      )}
      {tabla.nota && (
        <Text size="xs" c="dimmed" mt="xs">
          {tabla.nota}
        </Text>
      )}
    </Card>
  );
}

function FilaBarra({
  f,
  col,
  max,
  acciones,
  contexto,
  metrica,
}: {
  f: Fila;
  col: number;
  max: number;
  acciones?: AccionesFila;
  contexto: string;
  metrica: string;
}) {
  const puedeBajar = !!acciones?.alBajar && !!f.rastreo && ('fuerza' in f.rastreo || 'esm' in f.rastreo);
  return (
    <Group gap="xs" wrap="nowrap" align="flex-end">
      <Box style={{ flex: 1, minWidth: 0 }}>
        <BarraHorizontal
          etiqueta={f.textos[0] ?? ''}
          valor={numero(f.valores[col])}
          maximo={max}
          texto={f.textos[col]}
          color={f.tipo === 'dato' ? 'blue' : 'gray'}
        />
      </Box>
      {puedeBajar && (
        <Button
          size="compact-xs"
          mih={TACTIL}
          variant="subtle"
          onClick={() => acciones!.alBajar!(f.rastreo!)}
          aria-label={`Ver ${f.textos[0]} en detalle`}
        >
          Detalle
        </Button>
      )}
      {acciones?.alRastrear && f.rastreo && (
        <Button
          size="compact-xs"
          mih={TACTIL}
          variant="subtle"
          onClick={() => acciones.alRastrear!(f.rastreo!, `${contexto}: ${f.textos[0]}`, metrica)}
          aria-label={`Rastrear ${f.textos[0]}`}
        >
          Rastrear
        </Button>
      )}
    </Group>
  );
}

/**
 * Serie de tiempo en SVG propio (sin librerías): role="img" con descripción (qué, periodo y unidad), línea continua del periodo y
 * discontinua del periodo de comparación, leyenda en texto y «Ver tabla» con la tabla equivalente.
 */
export function SerieTiempoSvg({ tabla, acciones, unidad = 'pesos' }: { tabla: Tabla; acciones?: AccionesFila; unidad?: string }) {
  const [verTabla, setVerTabla] = useState(false);
  const col = indiceColumna(tabla, tabla.valorGrafico);
  const comp = indiceColumna(tabla, tabla.comparado);
  const filas = tabla.filas.filter((f) => f.tipo === 'dato');
  const valores = filas.map((f) => numero(f.valores[col]));
  const comparados = comp >= 0 ? filas.map((f) => numero(f.valores[comp])) : [];
  const max = maximoEje(Math.max(0, ...valores, ...comparados));
  const ancho = 560;
  const alto = 180;
  const m = { x: 64, y: 12 };
  const anchoUtil = ancho - m.x - 12;
  const altoUtil = alto - m.y - 28;
  const marcas = [0, 0.25, 0.5, 0.75, 1];
  const paso = Math.max(1, Math.ceil(filas.length / 8));
  const xDe = (i: number) => m.x + (filas.length === 1 ? anchoUtil / 2 : (anchoUtil * i) / (filas.length - 1));
  return (
    <Card withBorder data-testid={`grafico-${tabla.id}`}>
      <Encabezado titulo={tabla.titulo} derecha={<Alternar tabla={verTabla} setTabla={setVerTabla} />} />
      {verTabla || col < 0 ? (
        <TablaResultado tabla={tabla} acciones={acciones} />
      ) : (
        <>
          <svg
            viewBox={`0 0 ${ancho} ${alto}`}
            role="img"
            aria-label={descripcionSerie(tabla.titulo, filas, unidad, comp >= 0)}
            style={{ width: '100%', height: 'auto' }}
          >
            <g stroke="#DCE2DD" strokeWidth={1}>
              {marcas.map((k) => (
                <line key={k} x1={m.x} x2={m.x + anchoUtil} y1={m.y + altoUtil - altoUtil * k} y2={m.y + altoUtil - altoUtil * k} />
              ))}
            </g>
            <g fontSize={10} fill="#56635B">
              {marcas.map((k) => (
                <text key={k} x={2} y={m.y + altoUtil - altoUtil * k + 3}>
                  {compacto(max * k)}
                </text>
              ))}
              {filas.map((f, i) =>
                i % paso === 0 ? (
                  <text key={f.clave} x={xDe(i)} y={alto - 8} textAnchor="middle">
                    {f.clave}
                  </text>
                ) : null,
              )}
            </g>
            {comp >= 0 && (
              <polyline
                points={puntosSerie(comparados, anchoUtil, altoUtil, max, m)}
                fill="none"
                stroke="#245683"
                strokeWidth={2}
                strokeDasharray="6 4"
              />
            )}
            <polyline points={puntosSerie(valores, anchoUtil, altoUtil, max, m)} fill="none" stroke="#1C4632" strokeWidth={2.5} />
          </svg>
          <Group gap="md">
            <Text size="xs" c="dimmed">
              Línea continua: periodo consultado
            </Text>
            {comp >= 0 && (
              <Text size="xs" c="dimmed">
                Línea discontinua: periodo de comparación
              </Text>
            )}
          </Group>
        </>
      )}
      {tabla.nota && (
        <Text size="xs" c="dimmed" mt="xs">
          {tabla.nota}
        </Text>
      )}
    </Card>
  );
}

/** Mapa de calor ESM × periodo: la tabla misma lleva el color (el valor siempre en texto), con «Rastrear» por celda si se puede. */
export function MapaCalor({ tabla, acciones }: { tabla: Tabla; acciones?: AccionesFila }) {
  const periodos = tabla.columnas.slice(1, -1);
  let max = 0;
  for (const f of tabla.filas) for (let i = 1; i <= periodos.length; i++) max = Math.max(max, numero(f.valores[i]));
  return (
    <Card withBorder data-testid={`grafico-${tabla.id}`}>
      <Encabezado
        titulo={tabla.titulo}
        derecha={
          <Text size="xs" c="dimmed">
            Más oscuro: mayor gasto
          </Text>
        }
      />
      <ScrollArea type="auto">
        <Table withTableBorder fz="xs" miw={160 + periodos.length * 96}>
          <Table.Thead>
            <Table.Tr>
              {tabla.columnas.map((c, i) => (
                <Table.Th key={c.clave} ta={i === 0 ? 'left' : 'right'}>
                  {c.etiqueta}
                </Table.Th>
              ))}
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {tabla.filas.map((f) => (
              <Table.Tr key={f.clave}>
                <Table.Td>{f.textos[0]}</Table.Td>
                {periodos.map((p, j) => {
                  const v = numero(f.valores[j + 1]);
                  const c = colorCalor(intensidad(v, max));
                  const texto = f.textos[j + 1];
                  return (
                    <Table.Td key={p.clave} ta="right" style={{ background: c.fondo, color: c.texto, whiteSpace: 'nowrap' }}>
                      {acciones?.alRastrear && f.rastreo && v > 0 ? (
                        <UnstyledButton
                          mih={TACTIL}
                          px={4}
                          style={{ color: 'inherit', fontSize: 'inherit' }}
                          onClick={() => acciones.alRastrear!({ ...f.rastreo, periodo: p.clave }, `${f.textos[0]} · ${p.etiqueta}`, 'gasto')}
                          aria-label={`Rastrear ${f.textos[0]} en ${p.etiqueta}: ${texto}`}
                        >
                          {texto}
                        </UnstyledButton>
                      ) : (
                        texto
                      )}
                    </Table.Td>
                  );
                })}
                <Table.Td ta="right" fw={600} style={{ whiteSpace: 'nowrap' }}>
                  {f.textos[f.textos.length - 1]}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </ScrollArea>
    </Card>
  );
}

/** Tabla sin gráfico (distribución, cruces). */
export function TablaSimple({ tabla, acciones }: { tabla: Tabla; acciones?: AccionesFila }) {
  return (
    <Card withBorder data-testid={`tabla-${tabla.id}`}>
      <Title order={5} mb="xs">
        {tabla.titulo}
      </Title>
      <TablaResultado tabla={tabla} acciones={acciones} />
      {tabla.nota && (
        <Text size="xs" c="dimmed" mt="xs">
          {tabla.nota}
        </Text>
      )}
    </Card>
  );
}

/** Elige el componente según el gráfico de la tabla. */
export function GraficoTabla({ tabla, acciones }: { tabla: Tabla; acciones?: AccionesFila }) {
  switch (tabla.grafico) {
    case 'barras':
      return <BarrasAnalitica tabla={tabla} acciones={acciones} />;
    case 'serie':
      return <SerieTiempoSvg tabla={tabla} acciones={acciones} unidad={tabla.valorGrafico === 'gasto' ? 'pesos' : 'pacientes'} />;
    case 'calor':
      return <MapaCalor tabla={tabla} acciones={acciones} />;
    default:
      return <TablaSimple tabla={tabla} acciones={acciones} />;
  }
}
