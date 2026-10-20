import { Alert, Button, Stack } from '@mantine/core';
import { Component, type ErrorInfo, type ReactNode } from 'react';

interface Estado {
  error: Error | null;
}

/** Un error al dibujar una pantalla no deja la página en blanco: muestra el mensaje y la forma de volver. */
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
