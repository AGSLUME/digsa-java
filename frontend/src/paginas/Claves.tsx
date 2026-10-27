import { Alert, Anchor, Button, Card, Center, List, PasswordInput, Stack, Text, TextInput, Title } from '@mantine/core';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../shared/api';
import { erroresClaveNueva } from '../shared/validaciones';
import { Turnstile } from '../shared/Turnstile';
import { useValidacion } from '../shared/validacion';

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
  const [listo, setListo] = useState<string | null>(null);
  const v = useValidacion({ etiquetas: { clave: 'Contraseña nueva', confirmacion: 'Confirme la contraseña' }, testid: 'error-definir-clave' });

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
    if (!v.validar(erroresClaveNueva(clave, confirmacion))) return;
    try {
      const r = await api<{ mensaje: string }>('/api/auth/clave', { cuerpo: { tkn, clave, confirmacion } });
      setListo(r.mensaje);
    } catch (x) {
      v.errorServidor(x, 'No se pudo guardar la contraseña.');
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
        <form ref={v.ref} onSubmit={guardar} noValidate>
          <Stack>
            <Text size="sm">Hola {nombre}. Defina su contraseña:</Text>
            <List size="sm">
              <List.Item>Mínimo 12 caracteres, con mayúsculas y minúsculas.</List.Item>
              <List.Item>Al menos un número y un carácter especial.</List.Item>
              <List.Item>Sin ñ ni tildes.</List.Item>
            </List>
            {v.resumen}
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
            />
            <PasswordInput
              label="Confirme la contraseña"
              autoComplete="new-password"
              value={confirmacion}
              onChange={(e) => {
                setConfirmacion(e.currentTarget.value);
                v.limpiar('confirmacion');
              }}
              error={v.error('confirmacion')}
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
  const reiniciar = useRef<(() => void) | null>(null);
  const v = useValidacion({ etiquetas: { valor: 'Usuario o correo' }, testid: 'error-olvide-clave' });

  const enviar = async (e: FormEvent) => {
    e.preventDefault();
    if (!v.validar({ valor: valor.trim() ? null : 'Escriba su usuario o su correo.' })) return;
    try {
      const r = await api<{ mensaje: string }>('/api/auth/olvide', { cuerpo: { valor, captcha } });
      setMensaje(r.mensaje);
    } catch (x) {
      v.errorServidor(x, 'No se pudo enviar la solicitud.');
      reiniciar.current?.();
    }
  };

  return (
    <Marco titulo="¿Olvidó su contraseña?">
      {mensaje ? (
        <Alert color="green">{mensaje}</Alert>
      ) : (
        <form ref={v.ref} onSubmit={enviar} noValidate>
          <Stack>
            {v.resumen}
            <TextInput
              label="Usuario o correo"
              value={valor}
              onChange={(e) => {
                setValor(e.currentTarget.value);
                v.limpiar('valor');
              }}
              error={v.error('valor')}
              required
              maxLength={150}
            />
            <Turnstile alResolver={setCaptcha} reiniciar={reiniciar} />
            <Button type="submit">Enviar enlace</Button>
          </Stack>
        </form>
      )}
    </Marco>
  );
}
