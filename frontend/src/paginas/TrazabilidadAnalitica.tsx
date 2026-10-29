import { Alert, Button, Card, Center, Group, Loader, Select, Stack, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useId, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { FiltrosAnalitica } from '../analitica/FiltrosAnalitica';
import { PanelTraza } from '../analitica/PanelTraza';
import { busquedaDe, conTablero, leerEstado, problemaPeriodo, type CatalogoAnalitica, type EstadoAnalitica } from '../analitica/tableros';
import { api, ErrorApi } from '../shared/api';
import { BotonConMotivo } from '../shared/BotonConMotivo';
import { EncabezadoPagina } from '../shared/EncabezadoPagina';

const TITULO = 'Trazabilidad de cifras';
const FUENTE = 'Anexo 5 · 14.9';

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
  const idMotivo = useId();
  if (catalogo.isLoading || catalogo.error || !catalogo.data) {
    return (
      <Stack>
        <EncabezadoPagina titulo={TITULO} fuente={FUENTE} />
        {catalogo.isLoading ? (
          <Center mih={200}>
            <Loader />
          </Center>
        ) : (
          <Alert color="red">{catalogo.error instanceof ErrorApi ? catalogo.error.message : 'No se pudo abrir la trazabilidad.'}</Alert>
        )}
      </Stack>
    );
  }
  const c = catalogo.data;
  const problema = problemaPeriodo(estado.generales.desde, estado.generales.hasta);
  const clave = busquedaDe(estado);
  const motivo = !c.puedeRastrear ? 'Su perfil no tiene la trazabilidad de cifras.' : problema ? `Corrija el periodo: ${problema}` : null;
  return (
    <Stack>
      <EncabezadoPagina
        titulo={TITULO}
        descripcion="De una cifra al desglose y a los registros de origen: cargue, archivo, línea, SHA-256 y regla de la tabla maestra. El paciente va siempre como seudónimo."
        fuente={FUENTE}
      />
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
            <BotonConMotivo motivo={motivo} idMotivo={idMotivo} onClick={() => setRastreando(clave)} data-testid="rastrear-consulta">
              Rastrear la cifra de estos filtros
            </BotonConMotivo>
            <Button component={Link} to={`/analitica?${clave}`} variant="subtle">
              Ver el tablero
            </Button>
          </Group>
          {motivo && (
            <Text size="sm" c="dimmed" id={idMotivo}>
              {motivo}
            </Text>
          )}
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
