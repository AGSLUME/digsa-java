import { Alert, Anchor, Button, Card, Center, List, PasswordInput, Stack, Text, TextInput, Title } from '@mantine/core';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api, ErrorApi } from '../shared/api';
import { Turnstile } from '../shared/Turnstile';

function Marco({ titulo, children }: { titulo: string; children: React.ReactNode }) {
  return (
    <Center mih="100vh" p="md">
      <Card withBorder shadow="sm" padding="lg" w="100%" maw={460}>
        <Stack>
          <Title order={1} size="h3" c="digsa.8">
            {titulo}
          </Title>
          {children}
          <Anchor component={Link} to="/" size="sm">
            Volver al ingreso
          </Anchor>
        </Stack>
      </Card>
    </Center>
  );
}

/** Definir la contraseña con el enlace de un solo uso (vence en 60 minutos). */
export function DefinirClave() {
  const [params] = useSearchParams();
  const tkn = params.get('tkn') ?? '';
  const [valido, setValido] = useState<boolean | null>(null);
  const [nombre, setNombre] = useState('');
  const [clave, setClave] = useState('');
  const [confirmacion, setConfirmacion] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [listo, setListo] = useState<string | null>(null);

  useEffect(() => {
    api<{ valido: boolean; nombre?: string }>('/api/auth/clave?tkn=' + encodeURIComponent(tkn))
      .then((r) => {
        setValido(r.valido);
        setNombre(r.nombre ?? '');
      })
      .catch(() => setValido(false));
  }, [tkn]);

  const guardar = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      const r = await api<{ mensaje: string }>('/api/auth/clave', { cuerpo: { tkn, clave, confirmacion } });
      setListo(r.mensaje);
    } catch (x) {
      setError(x instanceof ErrorApi ? x.message : 'No se pudo guardar la contraseña.');
    }
  };

  if (listo) {
    return (
      <Marco titulo="Contraseña definida">
        <Alert color="green" data-testid="clave-ok">
          {listo}
        </Alert>
      </Marco>
    );
  }
  return (
    <Marco titulo="Definir contraseña">
      {valido === false && <Alert color="red">El enlace no es válido o ya venció. Pida uno nuevo con «¿Olvidó su contraseña?».</Alert>}
      {valido && (
        <form onSubmit={guardar}>
          <Stack>
            <Text size="sm">Hola {nombre}. Defina su contraseña:</Text>
            <List size="sm">
              <List.Item>Mínimo 12 caracteres, con mayúsculas y minúsculas.</List.Item>
              <List.Item>Al menos un número y un carácter especial.</List.Item>
              <List.Item>Sin ñ ni tildes.</List.Item>
            </List>
            {error && <Alert color="red">{error}</Alert>}
            <PasswordInput label="Contraseña nueva" autoComplete="new-password" value={clave} onChange={(e) => setClave(e.currentTarget.value)} required />
            <PasswordInput
              label="Confirme la contraseña"
              autoComplete="new-password"
              value={confirmacion}
              onChange={(e) => setConfirmacion(e.currentTarget.value)}
              required
            />
            <Button type="submit">Guardar contraseña</Button>
          </Stack>
        </form>
      )}
    </Marco>
  );
}

/** "Olvidé mi contraseña": responde siempre lo mismo para no revelar quién está registrado. */
export function OlvideClave() {
  const [valor, setValor] = useState('');
  const [captcha, setCaptcha] = useState<string | null>(null);
  const [mensaje, setMensaje] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const reiniciar = useRef<(() => void) | null>(null);

  const enviar = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      const r = await api<{ mensaje: string }>('/api/auth/olvide', { cuerpo: { valor, captcha } });
      setMensaje(r.mensaje);
    } catch (x) {
      setError(x instanceof ErrorApi ? x.message : 'No se pudo enviar la solicitud.');
      reiniciar.current?.();
    }
  };

  return (
    <Marco titulo="¿Olvidó su contraseña?">
      {mensaje ? (
        <Alert color="green">{mensaje}</Alert>
      ) : (
        <form onSubmit={enviar}>
          <Stack>
            {error && <Alert color="red">{error}</Alert>}
            <TextInput label="Usuario o correo" value={valor} onChange={(e) => setValor(e.currentTarget.value)} required maxLength={150} />
            <Turnstile alResolver={setCaptcha} reiniciar={reiniciar} />
            <Button type="submit">Enviar enlace</Button>
          </Stack>
        </form>
      )}
    </Marco>
  );
}
