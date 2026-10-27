import { Group, Pagination, Text } from '@mantine/core';
import { useEffect, useMemo, useState } from 'react';
import { conteo } from './formato';

/** Filas por página de las tablas que se paginan en el navegador (las listas completas de cientos de filas hacen lenta la pantalla). */
export const POR_PAGINA = 25;

/** Nombre accesible de los botones de la paginación de Mantine (getControlProps). */
export function controlPagina(control: 'first' | 'previous' | 'last' | 'next'): { 'aria-label': string } {
  return { 'aria-label': { first: 'Primera página', previous: 'Página anterior', next: 'Página siguiente', last: 'Última página' }[control] };
}

/** Página (desde 1) en la que queda la fila `i` (desde 0). */
export function paginaDe(i: number, porPagina = POR_PAGINA): number {
  return Math.floor(i / porPagina) + 1;
}

/**
 * Paginación en el navegador de una lista ya filtrada. Si la lista se achica (otro filtro, una fila menos), la página se ajusta a la
 * última que existe. `irA(i)` lleva a la página de la fila `i` (p. ej. la que se acaba de crear o editar).
 */
export function usePaginacion<T>(lista: T[], porPagina = POR_PAGINA) {
  const [pagina, setPagina] = useState(1);
  const paginas = Math.max(1, Math.ceil(lista.length / porPagina));
  const actual = Math.min(pagina, paginas);
  const visibles = useMemo(() => lista.slice((actual - 1) * porPagina, actual * porPagina), [lista, actual, porPagina]);
  return { pagina: actual, paginas, visibles, setPagina, irA: (i: number) => setPagina(paginaDe(i, porPagina)) };
}

/**
 * Lleva a la página de la fila que cumple `buscar` cuando aparece en la lista (después de guardar, la lista se vuelve a pedir).
 * `objetivo` null: no hay nada pendiente.
 */
export function useIrAFila<T>(lista: T[], objetivo: string | null, clave: (fila: T) => string, irA: (i: number) => void, alLlegar: () => void) {
  useEffect(() => {
    if (objetivo === null) return;
    const i = lista.findIndex((f) => clave(f) === objetivo);
    if (i < 0) return;
    irA(i);
    alLlegar();
    // clave, irA y alLlegar pueden ser funciones nuevas en cada render: lo que dispara el salto es la lista o el objetivo
  }, [lista, objetivo]);
}

/** Paginación con el total de filas, debajo de una tabla. No se muestra con una sola página. */
export function Paginacion({
  total,
  pagina,
  paginas,
  alCambiar,
  nombre,
  porPagina = POR_PAGINA,
}: {
  total: number;
  pagina: number;
  paginas: number;
  alCambiar: (p: number) => void;
  /** «usuarios», «registros»…: para el texto del total y el nombre accesible. */
  nombre: string;
  porPagina?: number;
}) {
  // Como la paginación LUME: «Mostrando X–Y de Z» a la izquierda y los botones a la derecha
  const desde = total === 0 ? 0 : (pagina - 1) * porPagina + 1;
  const hasta = Math.min(total, pagina * porPagina);
  return (
    <Group justify="space-between" gap="sm">
      <Text size="sm" c="dimmed" data-testid={`total-${nombre}`}>
        {total === 0 ? `0 ${nombre}` : `Mostrando ${conteo(desde)}–${conteo(hasta)} de ${conteo(total)} ${nombre}`}
      </Text>
      {paginas > 1 && (
        <Pagination
          total={paginas}
          value={pagina}
          onChange={alCambiar}
          size="sm"
          siblings={1}
          getControlProps={controlPagina}
          aria-label={`Páginas de ${nombre}`}
        />
      )}
    </Group>
  );
}
