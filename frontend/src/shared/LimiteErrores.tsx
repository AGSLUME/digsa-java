import { Alert, Button, Stack } from '@mantine/core';
import { Component, type ErrorInfo, type ReactNode } from 'react';

interface Estado {
  error: Error | null;
}

/**
 * Falla al descargar el archivo de una pantalla (división por rutas, U5): casi siempre porque se publicó una versión nueva y el
 * navegador pide un archivo que ya no existe. Se arregla recargando la misma dirección.
 */
export function esFalloDeCarga(error: Error | null): boolean {
  return /dynamically imported module|Importing a module script failed|ChunkLoadError|Failed to fetch dynamically/i.test(
    `${error?.name ?? ''} ${error?.message ?? ''}`,
  );
}

/**
 * Un error al dibujar una pantalla no deja la página en blanco: muestra el mensaje y la forma de seguir. Si fue la descarga de la
 * pantalla, ofrece recargar la misma dirección (no se pierde, p. ej., el enlace con el token para definir la contraseña).
 */
export class LimiteErrores extends Component<{ children: ReactNode }, Estado> {
  state: Estado = { error: null };

  static getDerivedStateFromError(error: Error): Estado {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('Error en la pantalla', error, info.componentStack);
  }

  render() {
    if (!this.state.error) return this.props.children;
    if (esFalloDeCarga(this.state.error)) {
      return (
        <Stack p="md" maw={560}>
          <Alert color="yellow" title="Hay una versión nueva de la aplicación">
            No se pudo abrir esta pantalla porque la aplicación se actualizó. Recargue para seguir donde estaba.
          </Alert>
          <Button onClick={() => window.location.reload()}>Recargar</Button>
        </Stack>
      );
    }
    return (
      <Stack p="md" maw={560}>
        <Alert color="red" title="Ocurrió un error en esta pantalla">
          Intente de nuevo. Si se repite, avise al administrador.
        </Alert>
        <Button
          onClick={() => {
            this.setState({ error: null });
            window.location.assign('/');
          }}
        >
          Volver al inicio
        </Button>
      </Stack>
    );
  }
}
