import { ScrollArea } from '@mantine/core';
import { useLayoutEffect, useRef, type ReactNode } from 'react';

/**
 * Pone a cada celda del cuerpo el nombre de su columna (`data-etiqueta`, del encabezado) y deja explícitos los roles de tabla, fila y
 * celda: en celular la tabla se apila como tarjetas por CSS (`display: block`) y algunos navegadores pierden la semántica de tabla con
 * ese cambio. Se repite cuando cambian las filas (paginación, filtros, datos nuevos). Los títulos salen de la última fila del
 * encabezado: una tabla con encabezado de varias filas o con `rowSpan` necesitaría calcular la rejilla.
 */
export function etiquetarCeldas(raiz: HTMLElement): void {
  for (const tabla of raiz.querySelectorAll('table')) {
    if (!tabla.hasAttribute('role')) tabla.setAttribute('role', 'table');
    const titulos = Array.from(tabla.querySelectorAll(':scope > thead > tr:last-child > th')).flatMap((th) =>
      Array<string>((th as HTMLTableCellElement).colSpan || 1).fill((th.textContent ?? '').trim()),
    );
    for (const tr of tabla.querySelectorAll(':scope > thead > tr, :scope > tbody > tr, :scope > tfoot > tr')) {
      if (!tr.hasAttribute('role')) tr.setAttribute('role', 'row');
    }
    for (const th of tabla.querySelectorAll(':scope > thead > tr > th')) {
      if (!th.hasAttribute('role')) th.setAttribute('role', 'columnheader');
    }
    for (const tr of tabla.querySelectorAll(':scope > tbody > tr, :scope > tfoot > tr')) {
      let columna = 0;
      for (const celda of Array.from(tr.children) as HTMLTableCellElement[]) {
        if (!celda.hasAttribute('role')) celda.setAttribute('role', celda.tagName === 'TH' ? 'rowheader' : 'cell');
        const span = celda.colSpan || 1;
        // Una celda que ocupa varias columnas lleva sus títulos unidos («Antes / Después»); la que ocupa todas (el «Sin datos» de la
        // tabla vacía), ninguno
        const etiqueta =
          span > 1 && span >= titulos.length ? '' : Array.from(new Set(titulos.slice(columna, columna + span).filter(Boolean))).join(' / ');
        if (celda.getAttribute('data-etiqueta') !== etiqueta) celda.setAttribute('data-etiqueta', etiqueta);
        columna += span;
      }
    }
  }
}

/**
 * Tabla que en escritorio se desplaza a lo ancho dentro de su caja (como antes) y en celular y tableta vertical se muestra como una
 * tarjeta por fila, con el nombre de cada columna sobre su valor (Anexo 5 num. 1.18, L318; decisión UI-10). Envuelve una `Table` de
 * Mantine sin cambiar su marcado: filas, celdas y `data-testid` siguen iguales para las pruebas.
 */
export function TablaAdaptable({ children }: { children: ReactNode }) {
  const caja = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const el = caja.current;
    if (!el) return;
    etiquetarCeldas(el);
    const observador = new MutationObserver(() => etiquetarCeldas(el));
    observador.observe(el, { childList: true, subtree: true, characterData: true });
    return () => observador.disconnect();
  }, []);
  return (
    <ScrollArea type="auto" className="tabla-adaptable" viewportRef={caja}>
      {children}
    </ScrollArea>
  );
}
