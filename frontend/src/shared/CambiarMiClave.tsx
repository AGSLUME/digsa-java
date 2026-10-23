import { Alert, Button, Group, List, Modal, PasswordInput, Stack } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useState, type FormEvent } from 'react';
import { api, ErrorApi } from './api';

/** «Cambiar mi contraseña» desde la sesión, sin salir al correo (condición 12 de la prueba en sitio). */
export function CambiarMiClave({ abierto, alCerrar }: { abierto: boolean; alCerrar: () => void }) {
  const [actual, setActual] = useState('');
  const [clave, setClave] = useState('');
  const [confirmacion, setConfirmacion] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);

  const cerrar = () => {
    setActual('');
    setClave('');
    setConfirmacion('');
    setError(null);
    alCerrar();
  };

  const guardar = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setEnviando(true);
    try {
      const r = await api<{ mensaje: string }>('/api/cuenta/clave', { cuerpo: { actual, clave, confirmacion } });
      notifications.show({ color: 'green', message: r.mensaje });
      cerrar();
    } catch (x) {
      setError(x instanceof ErrorApi ? x.message : 'No se pudo cambiar la contraseña.');
    } finally {
      setEnviando(false);
    }
  };

  return (
    <Modal opened={abierto} onClose={cerrar} title="Cambiar mi contraseña">
      <form onSubmit={guardar}>
        <Stack>
          <List size="sm">
            <List.Item>Mínimo 12 caracteres, con mayúsculas y minúsculas.</List.Item>
            <List.Item>Al menos un número y un carácter especial.</List.Item>
            <List.Item>Sin ñ ni tildes.</List.Item>
          </List>
          {error && (
            <Alert color="red" data-testid="error-mi-clave">
              {error}
            </Alert>
          )}
          <PasswordInput
            label="Contraseña actual"
            autoComplete="current-password"
            value={actual}
            onChange={(e) => setActual(e.currentTarget.value)}
            required
            maxLength={200}
          />
          <PasswordInput
            label="Contraseña nueva"
            autoComplete="new-password"
            value={clave}
            onChange={(e) => setClave(e.currentTarget.value)}
            required
            maxLength={64}
          />
          <PasswordInput
            label="Confirme la contraseña nueva"
            autoComplete="new-password"
            value={confirmacion}
            onChange={(e) => setConfirmacion(e.currentTarget.value)}
            required
            maxLength={64}
          />
          <Group justify="flex-end">
            <Button variant="default" onClick={cerrar}>
              Cancelar
            </Button>
            <Button type="submit" loading={enviando}>
              Cambiar contraseña
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
