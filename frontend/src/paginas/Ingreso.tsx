import { Alert, Anchor, Button, Card, Center, PasswordInput, PinInput, Stack, Text, TextInput, Title } from '@mantine/core';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api, CLAVE_MOTIVO, ErrorApi } from '../shared/api';
import { Turnstile } from '../shared/Turnstile';

interface Paso {
  paso: 'codigo' | 'definir_clave' | 'cambiar_clave';
  mensaje: string;
  correo: string;
  enlaceLocal: string | null;
}

/** Ingreso en dos pasos: usuario y contraseña (con captcha) y luego el código de 6 dígitos que llega al correo. */
export function Ingreso({ alIngresar }: { alIngresar: () => void }) {
  const [usuario, setUsuario] = useState('');
  const [clave, setClave] = useState('');
  const [codigo, setCodigo] = useState('');
  const [nueva, setNueva] = useState('');
  const [confirmacion, setConfirmacion] = useState('');
  const [captcha, setCaptcha] = useState<string | null>(null);
  const [paso, setPaso] = useState<Paso | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);
  const reiniciarCaptcha = useRef<(() => void) | null>(null);

  useEffect(() => {
    try {
      const m = sessionStorage.getItem(CLAVE_MOTIVO);
      if (m) {
        setAviso(m);
        sessionStorage.removeItem(CLAVE_MOTIVO);
      }
    } catch {
      // sin almacenamiento
    }
  }, []);

  const ingresar = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setEnviando(true);
    try {
      const p = await api<Paso>('/api/auth/ingreso', { cuerpo: { usuario, clave, captcha } });
      setPaso(p);
      setClave('');
    } catch (x) {
      setError(x instanceof ErrorApi ? x.message : 'No se pudo ingresar.');
      reiniciarCaptcha.current?.();
    } finally {
      setEnviando(false);
    }
  };

  const verificar = async (e?: FormEvent) => {
    e?.preventDefault();
    setError(null);
    setEnviando(true);
    try {
      await api('/api/auth/codigo', { cuerpo: { codigo } });
      alIngresar();
    } catch (x) {
      setError(x instanceof ErrorApi ? x.message : 'No se pudo verificar el código.');
      if (x instanceof ErrorApi && x.estado === 401) {
        setPaso(null);
        setCaptcha(null); // el captcha ya se usó: hay que resolverlo de nuevo
        reiniciarCaptcha.current?.();
      }
    } finally {
      setEnviando(false);
    }
  };

  /** Contraseña inicial asignada por el administrador: se cambia aquí, sin salir al correo; después sigue el código. */
  const cambiarClave = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setEnviando(true);
    try {
      const p = await api<Paso>('/api/auth/cambiar-clave', { cuerpo: { clave: nueva, confirmacion } });
      setPaso(p);
      setNueva('');
      setConfirmacion('');
    } catch (x) {
      setError(x instanceof ErrorApi ? x.message : 'No se pudo cambiar la contraseña.');
      if (x instanceof ErrorApi && x.estado === 401) {
        setPaso(null);
        setCaptcha(null);
        reiniciarCaptcha.current?.();
      }
    } finally {
      setEnviando(false);
    }
  };

  const reenviar = async () => {
    setError(null);
    try {
      const p = await api<Paso>('/api/auth/codigo/reenviar', { metodo: 'POST' });
      setPaso(p);
      setCodigo('');
    } catch (x) {
      setError(x instanceof ErrorApi ? x.message : 'No se pudo reenviar el código.');
    }
  };

  return (
    <Center mih="100vh" p="md">
      <Card withBorder shadow="sm" padding="lg" w="100%" maw={420}>
        <Stack>
          <Title order={1} size="h2" c="digsa.8">
            Suite DIGSA
          </Title>
          <Text c="dimmed" size="sm">
            Visitas de seguimiento farmacéutico · Interventoría AGS Colombia
          </Text>
          {aviso && (
            <Alert color="yellow" data-testid="motivo-cierre">
              {aviso}
            </Alert>
          )}
          {error && (
            <Alert color="red" data-testid="error-ingreso">
              {error}
            </Alert>
          )}
          {paso?.paso === 'definir_clave' && <Alert color="blue">{paso.mensaje}</Alert>}
          {paso?.paso === 'cambiar_clave' ? (
            <form onSubmit={cambiarClave}>
              <Stack>
                <Alert color="blue">{paso.mensaje}</Alert>
                <Text size="sm" c="dimmed">
                  Mínimo 12 caracteres, sin tildes ni ñ. Después le enviaremos un código a <strong>{paso.correo}</strong>.
                </Text>
                <PasswordInput
                  label="Contraseña nueva"
                  autoComplete="new-password"
                  value={nueva}
                  onChange={(e) => setNueva(e.currentTarget.value)}
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
                <Button type="submit" loading={enviando}>
                  Cambiar contraseña y continuar
                </Button>
              </Stack>
            </form>
          ) : paso?.paso === 'codigo' ? (
            <form onSubmit={verificar}>
              <Stack>
                <Text size="sm">
                  {paso.mensaje} Lo enviamos a <strong>{paso.correo}</strong>. Vence en 15 minutos.
                </Text>
                <PinInput
                  length={6}
                  type="number"
                  oneTimeCode
                  value={codigo}
                  onChange={setCodigo}
                  onComplete={() => undefined}
                  aria-label="Código de verificación"
                  data-testid="codigo"
                />
                <Button type="submit" loading={enviando} disabled={codigo.length !== 6}>
                  Verificar e ingresar
                </Button>
                <Anchor component="button" type="button" size="sm" onClick={reenviar}>
                  Enviar otro código
                </Anchor>
              </Stack>
            </form>
          ) : (
            <form onSubmit={ingresar}>
              <Stack>
                <TextInput
                  label="Usuario"
                  autoComplete="username"
                  value={usuario}
                  onChange={(e) => setUsuario(e.currentTarget.value)}
                  required
                  maxLength={50}
                />
                <PasswordInput
                  label="Contraseña"
                  autoComplete="current-password"
                  value={clave}
                  onChange={(e) => setClave(e.currentTarget.value)}
                  required
                  maxLength={200}
                />
                <Turnstile alResolver={setCaptcha} reiniciar={reiniciarCaptcha} />
                <Button type="submit" loading={enviando}>
                  Ingresar
                </Button>
                {/* Por ahora solo correo; el envío por SMS queda pendiente del proveedor y sus credenciales (decisión del 2026-10-22) */}
                <Text size="xs" c="dimmed" data-testid="aviso-codigo">
                  Para ingresar le enviaremos un código de verificación a su correo electrónico registrado. Más adelante también podrá recibirlo por
                  SMS.
                </Text>
                <Anchor component={Link} to="/olvide-clave" size="sm">
                  ¿Olvidó su contraseña?
                </Anchor>
              </Stack>
            </form>
          )}
        </Stack>
      </Card>
    </Center>
  );
}
