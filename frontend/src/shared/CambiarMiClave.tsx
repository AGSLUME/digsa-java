import { Button, Group, List, Modal, PasswordInput, Stack } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useState, type FormEvent } from 'react';
import { api } from './api';
import { useValidacion } from './validacion';
import { erroresClaveNueva } from './validaciones';

/** «Cambiar mi contraseña» desde la sesión, sin salir al correo (condición 12 de la prueba en sitio). */
export function CambiarMiClave({ abierto, alCerrar }: { abierto: boolean; alCerrar: () => void }) {
  const [actual, setActual] = useState('');
  const [clave, setClave] = useState('');
  const [confirmacion, setConfirmacion] = useState('');
  const [enviando, setEnviando] = useState(false);
  const v = useValidacion({
    etiquetas: { actual: 'Contraseña actual', clave: 'Contraseña nueva', confirmacion: 'Confirme la contraseña nueva' },
    testid: 'error-mi-clave',
  });

  const cerrar = () => {
    setActual('');
    setClave('');
    setConfirmacion('');
    v.reiniciar();
    alCerrar();
  };

  const guardar = async (e: FormEvent) => {
    e.preventDefault();
    if (!v.validar({ actual: actual ? null : 'Escriba la contraseña actual.', ...erroresClaveNueva(clave, confirmacion) })) return;
    setEnviando(true);
    try {
      const r = await api<{ mensaje: string }>('/api/cuenta/clave', { cuerpo: { actual, clave, confirmacion } });
      notifications.show({ color: 'green', message: r.mensaje });
      cerrar();
    } catch (x) {
      v.errorServidor(x, 'No se pudo cambiar la contraseña.');
    } finally {
      setEnviando(false);
    }
  };

  return (
    <Modal opened={abierto} onClose={cerrar} title="Cambiar mi contraseña">
      <form ref={v.ref} onSubmit={guardar} noValidate>
        <Stack>
          <List size="sm">
            <List.Item>Mínimo 12 caracteres, con mayúsculas y minúsculas.</List.Item>
            <List.Item>Al menos un número y un carácter especial.</List.Item>
            <List.Item>Sin ñ ni tildes.</List.Item>
          </List>
          {v.resumen}
          <PasswordInput
            label="Contraseña actual"
            autoComplete="current-password"
            value={actual}
            onChange={(e) => {
              setActual(e.currentTarget.value);
              v.limpiar('actual');
            }}
            error={v.error('actual')}
            required
            maxLength={200}
          />
          <PasswordInput
            label="Contraseña nueva"
            autoComplete="new-password"
            value={clave}
            onChange={(e) => {
              setClave(e.currentTarget.value);
              v.limpiar('clave');
            }}
            error={v.error('clave')}
            required
            maxLength={64}
          />
          <PasswordInput
            label="Confirme la contraseña nueva"
            autoComplete="new-password"
            value={confirmacion}
            onChange={(e) => {
              setConfirmacion(e.currentTarget.value);
              v.limpiar('confirmacion');
            }}
            error={v.error('confirmacion')}
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
