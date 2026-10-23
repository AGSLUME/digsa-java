import { Alert, Button, Card, Center, Group, Loader, Select, Stack, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { FiltrosAnalitica } from '../analitica/FiltrosAnalitica';
import { PanelTraza } from '../analitica/PanelTraza';
import { busquedaDe, conTablero, leerEstado, problemaPeriodo, type CatalogoAnalitica, type EstadoAnalitica } from '../analitica/tableros';
import { api, ErrorApi } from '../shared/api';

/**
 * «Trazabilidad de cifras» (Pantallas.ANALITICA_TRAZABILIDAD; A5 14.9): de cualquier cifra a sus registros de origen seudonimizados. Desde
 * los tableros se rastrea con «Rastrear» en cada KPI, barra o celda; aquí se rastrea una consulta armada con los mismos filtros (por
 * ejemplo, todo el gasto de un ATC en una Fuerza y un mes) y se descarga la lista completa en CSV.
 */
export function TrazabilidadAnalitica() {
  const [busqueda, setBusqueda] = useSearchParams();
  const estado = leerEstado(busqueda.toString());
  const cambiar = (e: EstadoAnalitica, reemplazar = false) => setBusqueda(busquedaDe(e), { replace: reemplazar });
  const catalogo = useQuery({
    queryKey: ['analitica', 'catalogo'],
    queryFn: () => api<CatalogoAnalitica>('/api/analitica/catalogo'),
    staleTime: 60_000,
  });
  const [rastreando, setRastreando] = useState<string | null>(null);
  if (catalogo.isLoading) {
    return (
      <Center mih={200}>
        <Loader />
      </Center>
    );
  }
  if (catalogo.error || !catalogo.data) {
    return <Alert color="red">{catalogo.error instanceof ErrorApi ? catalogo.error.message : 'No se pudo abrir la trazabilidad.'}</Alert>;
  }
  const c = catalogo.data;
  const problema = problemaPeriodo(estado.generales.desde, estado.generales.hasta);
  const clave = busquedaDe(estado);
  return (
    <Stack>
      <div>
        <Title order={2}>Trazabilidad de cifras</Title>
        <Text c="dimmed" size="sm">
          De una cifra al desglose y a los registros de origen: cargue, archivo, línea, SHA-256 y regla de la tabla maestra. El paciente va siempre
          como seudónimo.
        </Text>
      </div>
      {!c.puedeRastrear && <Alert color="red">Su perfil no tiene la trazabilidad de cifras.</Alert>}
      <Card withBorder>
        <Stack>
          <Group align="flex-end" wrap="wrap">
            <Select
              label="Tablero de la cifra"
              w={260}
              data={c.tableros.filter((t) => t.disponible).map((t) => ({ value: t.codigo, label: t.titulo }))}
              value={estado.tablero}
              onChange={(v) => v && cambiar(conTablero(estado, v))}
              allowDeselect={false}
            />
            <Button disabled={!!problema || !c.puedeRastrear} onClick={() => setRastreando(clave)} data-testid="rastrear-consulta">
              Rastrear la cifra de estos filtros
            </Button>
            <Button component={Link} to={`/analitica?${clave}`} variant="subtle">
              Ver el tablero
            </Button>
          </Group>
          <FiltrosAnalitica estado={estado} catalogo={c} alCambiar={cambiar} mostrarVista={false} />
        </Stack>
      </Card>
      {rastreando === clave && (
        <Card withBorder>
          <PanelTraza key={busquedaDe(estado)} estado={estado} rastreo={{}} que="Gasto total de la consulta" />
        </Card>
      )}
    </Stack>
  );
}
