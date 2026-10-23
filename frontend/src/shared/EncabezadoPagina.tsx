import { Group, Stack, Text, Title, VisuallyHidden } from '@mantine/core';
import { useEffect, type ReactNode } from 'react';

/** Título de la pestaña del navegador (decisión UI-9): «Suite DIGSA · <pantalla>». */
export function tituloDocumento(pantalla?: string): string {
  return pantalla ? `Suite DIGSA · ${pantalla}` : 'Suite DIGSA';
}

/**
 * Encabezado de cada página, como el del mockup: título (h1), descripción opcional en gris y, a la derecha, la fuente normativa
 * («Anexo 5 · 4.5») y las acciones de la página. `children` va junto al título (insignias de estado).
 */
export function EncabezadoPagina({
  titulo,
  documento,
  descripcion,
  fuente,
  acciones,
  orden = 1,
  children,
}: {
  titulo: ReactNode;
  /** Nombre para la pestaña del navegador cuando el título no es texto; por omisión, el título. */
  documento?: string;
  descripcion?: ReactNode;
  fuente?: string;
  acciones?: ReactNode;
  /** 2 cuando la página ya tiene su propio h1 (portal público del OPL). */
  orden?: 1 | 2;
  children?: ReactNode;
}) {
  const nombre = documento ?? (typeof titulo === 'string' ? titulo : undefined);
  useEffect(() => {
    document.title = tituloDocumento(nombre);
    // Al salir de la pantalla la pestaña no conserva el nombre de la anterior
    return () => {
      document.title = tituloDocumento();
    };
  }, [nombre]);

  return (
    <Group justify="space-between" align="flex-end" gap="sm">
      <Stack gap={4} style={{ flex: '1 1 20rem', minWidth: 0 }}>
        <Group gap="sm">
          <Title order={orden} style={{ overflowWrap: 'anywhere' }}>
            {titulo}
          </Title>
          {children}
        </Group>
        {descripcion && (
          <Text size="sm" c="dimmed" maw={820}>
            {descripcion}
          </Text>
        )}
      </Stack>
      {(fuente || acciones) && (
        <Group gap="xs">
          {fuente && (
            <span className="digsa-fuente">
              <VisuallyHidden>Fuente normativa: </VisuallyHidden>
              {fuente}
            </span>
          )}
          {acciones}
        </Group>
      )}
    </Group>
  );
}
