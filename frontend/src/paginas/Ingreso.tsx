import { Alert, Anchor, Button, Card, Center, Group, PasswordInput, PinInput, Stack, Text, TextInput, Title } from '@mantine/core';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api, CLAVE_MOTIVO, ErrorApi } from '../shared/api';
import { erroresClaveNueva } from '../shared/validaciones';
import { InterruptorSonido } from '../shared/InterruptorSonido';
import { Turnstile } from '../shared/Turnstile';
import { useValidacion } from '../shared/validacion';

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
  const [aviso, setAviso] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);
  const reiniciarCaptcha = useRef<(() => void) | null>(null);
  const v = useValidacion({
    etiquetas: {
      usuario: 'Usuario',
      clave: 'Contraseña',
      nueva: 'Contraseña nueva',
      confirmacion: 'Confirme la contraseña nueva',
      codigo: 'Código de verificación',
    },
    // En el cambio de la contraseña inicial el cuerpo lleva «clave» para la contraseña nueva
    campos: paso?.paso === 'cambiar_clave' ? { clave: 'nueva' } : {},
    testid: 'error-ingreso',
  });
  const errorCodigo = v.error('codigo');

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
    if (!v.validar({ usuario: usuario.trim() ? null : 'Escriba el usuario.', clave: clave ? null : 'Escriba la contraseña.' })) return;
    setEnviando(true);
    try {
      const p = await api<Paso>('/api/auth/ingreso', { cuerpo: { usuario, clave, captcha } });
      setPaso(p);
      setClave('');
    } catch (x) {
      v.errorServidor(x, 'No se pudo ingresar.');
      reiniciarCaptcha.current?.();
    } finally {
      setEnviando(false);
    }
  };

  const verificar = async (e?: FormEvent) => {
    e?.preventDefault();
    if (!v.validar({ codigo: /^\d{6}$/.test(codigo) ? null : 'Escriba los 6 dígitos del código.' })) return;
    setEnviando(true);
    try {
      await api('/api/auth/codigo', { cuerpo: { codigo } });
      alIngresar();
    } catch (x) {
      v.errorServidor(x, 'No se pudo verificar el código.');
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
    const errores = erroresClaveNueva(nueva, confirmacion);
    if (!v.validar({ nueva: errores.clave, confirmacion: errores.confirmacion })) return;
    setEnviando(true);
    try {
      const p = await api<Paso>('/api/auth/cambiar-clave', { cuerpo: { clave: nueva, confirmacion } });
      setPaso(p);
      setNueva('');
      setConfirmacion('');
    } catch (x) {
      v.errorServidor(x, 'No se pudo cambiar la contraseña.');
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
    v.reiniciar();
    try {
      const p = await api<Paso>('/api/auth/codigo/reenviar', { metodo: 'POST' });
      setPaso(p);
      setCodigo('');
    } catch (x) {
      v.errorServidor(x, 'No se pudo reenviar el código.');
    }
  };

  return (
    <Center mih="100vh" p="md">
      <Card withBorder shadow="sm" padding="lg" w="100%" maw={420}>
        <Stack>
          {/* Pantalla pública (sin la barra de la aplicación): el interruptor del sonido de las alertas va arriba a la derecha */}
          <Group justify="space-between" align="flex-start" wrap="nowrap" gap="xs">
            <Title order={1} size="h2" c="digsa.8" style={{ minWidth: 0 }}>
              Interventoría Integral DIGSA
            </Title>
            <InterruptorSonido />
          </Group>
          <Text c="dimmed" size="sm">
            Sistema de información de interventoría · cuentas médicas
          </Text>
          {aviso && (
            <Alert color="yellow" data-testid="motivo-cierre">
              {aviso}
            </Alert>
          )}
          {v.resumen}
          {paso?.paso === 'definir_clave' && <Alert color="blue">{paso.mensaje}</Alert>}
          {paso?.paso === 'cambiar_clave' ? (
            <form ref={v.ref} onSubmit={cambiarClave} noValidate>
              <Stack>
                <Alert color="blue">{paso.mensaje}</Alert>
                <Text size="sm" c="dimmed">
                  Mínimo 12 caracteres, sin tildes ni ñ. Después le enviaremos un código a <strong>{paso.correo}</strong>.
                </Text>
                <PasswordInput
                  label="Contraseña nueva"
                  autoComplete="new-password"
                  value={nueva}
                  onChange={(e) => {
                    setNueva(e.currentTarget.value);
                    v.limpiar('nueva');
                  }}
                  error={v.error('nueva')}
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
                <Button type="submit" loading={enviando}>
                  Cambiar contraseña y continuar
                </Button>
              </Stack>
            </form>
          ) : paso?.paso === 'codigo' ? (
            <form ref={v.ref} onSubmit={verificar} noValidate>
              <Stack>
                {/* En local el mensaje trae el código: sigue a la vista aunque el código escrito no sea correcto */}
                <Text size="sm">
                  {paso.mensaje} Lo enviamos a <strong>{paso.correo}</strong>. Vence en 15 minutos.
                </Text>
                <PinInput
                  length={6}
                  type="number"
                  oneTimeCode
                  value={codigo}
                  onChange={(c) => {
                    setCodigo(c);
                    v.limpiar('codigo');
                  }}
                  onComplete={() => undefined}
                  aria-label="Código de verificación"
                  data-testid="codigo"
                  error={errorCodigo !== undefined}
                  getInputProps={(i) => ({
                    'aria-label': `Dígito ${i + 1} de 6 del código`,
                    ...(typeof errorCodigo === 'string' ? { 'aria-describedby': 'codigo-error' } : {}),
                  })}
                />
                {/* PinInput solo marca las casillas: el mensaje va debajo, unido a ellas con aria-describedby */}
                {typeof errorCodigo === 'string' && (
                  <Text id="codigo-error" size="sm" c="red" mt={-8}>
                    {errorCodigo}
                  </Text>
                )}
                <Button type="submit" loading={enviando}>
                  Verificar e ingresar
                </Button>
                <Anchor component="button" type="button" size="sm" onClick={reenviar}>
                  Enviar otro código
                </Anchor>
              </Stack>
            </form>
          ) : (
            <form ref={v.ref} onSubmit={ingresar} noValidate>
              <Stack>
                <TextInput
                  label="Usuario"
                  autoComplete="username"
                  value={usuario}
                  onChange={(e) => {
                    setUsuario(e.currentTarget.value);
                    v.limpiar('usuario');
                  }}
                  error={v.error('usuario')}
                  required
                  maxLength={50}
                />
                <PasswordInput
                  label="Contraseña"
                  autoComplete="current-password"
                  value={clave}
                  onChange={(e) => {
                    setClave(e.currentTarget.value);
                    v.limpiar('clave');
                  }}
                  error={v.error('clave')}
                  required
                  maxLength={200}
                />
                <Turnstile alResolver={setCaptcha} reiniciar={reiniciarCaptcha} />
                <Button type="submit" loading={enviando}>
                  Ingresar
                </Button>
                {/* El código de verificación va solo por correo: el anexo no pide SMS para el ingreso (regla del usuario, 2026-10-23) */}
                <Text size="xs" c="dimmed" data-testid="aviso-codigo">
                  Para ingresar le enviaremos un código de verificación a su correo electrónico registrado.
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
