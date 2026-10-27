import { Alert, Badge, Button, Card, Center, Container, Group, Loader, Modal, PinInput, Radio, Stack, Text, Title } from '@mantine/core';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useId, useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { api, ErrorApi } from '../shared/api';
import { COLOR_FASE, NOMBRE_FASE, tokenDelEnlace, type EnlaceOpl } from '../shared/opl';
import { InterruptorSonido } from '../shared/InterruptorSonido';
import { RespuestaOpl } from '../shared/RespuestaOpl';
import { useValidacion } from '../shared/validacion';

const BASE = '/api/opl-publico';
const CLAVE_TOKEN = 'digsa.opl.enlace';

function almacenDeLaPestana(): Storage | null {
  try {
    return sessionStorage;
  } catch {
    return null;
  }
}

/**
 * Enlace del acta (código QR) para el OPL, sin usuario ni menú: abre el enlace, envía un código de 6 dígitos a uno de los correos
 * del OPL (enmascarados) y, verificado, muestra la misma respuesta que ve el usuario OPL. Si por inactividad el servidor pide el
 * código otra vez, se pide en un diálogo sin perder lo escrito. El token sale de la barra de direcciones apenas abre el enlace (no queda
 * en el historial ni en la telemetría) y queda solo en la pestaña, para reabrir al recargar mientras viva la sesión del servidor.
 */
export function OplResponder() {
  const [params] = useSearchParams();
  const navegar = useNavigate();
  const deLaDireccion = params.get('t');
  const [t] = useState(() => tokenDelEnlace(deLaDireccion, almacenDeLaPestana(), CLAVE_TOKEN));
  const qc = useQueryClient();
  const enlace = useQuery({
    queryKey: ['opl', 'enlace', t],
    queryFn: () => api<EnlaceOpl>(`${BASE}/enlace?t=${encodeURIComponent(t)}`),
    enabled: t !== '',
    staleTime: Infinity,
  });
  const respondio = enlace.isSuccess || enlace.isError;
  // Respondido el enlace, el token sale de la dirección (reemplazando la entrada del historial)
  useEffect(() => {
    if (respondio && deLaDireccion !== null) navegar('/opl-responder', { replace: true });
  }, [respondio, deLaDireccion, navegar]);
  // Un enlace inválido o vencido no se vuelve a intentar al recargar
  useEffect(() => {
    if (enlace.error instanceof ErrorApi && (enlace.error.estado === 404 || enlace.error.estado === 410)) {
      try {
        sessionStorage.removeItem(CLAVE_TOKEN);
      } catch {
        // sin almacenamiento
      }
    }
  }, [enlace.error]);
  const [verificado, setVerificado] = useState(false);
  const [reverificar, setReverificar] = useState(false);
  const [sinVigencia, setSinVigencia] = useState<string | null>(null);
  // Verificación vencida (403) o sesión del QR vencida (401): se reabre el enlace con el mismo token (abre una sesión nueva) y se pide
  // el código en el diálogo; lo escrito sigue en la pantalla. Si el enlace ya no sirve (404/410), se dice y se olvida el token.
  const alReverificar = useCallback(() => {
    api<EnlaceOpl>(`${BASE}/enlace?t=${encodeURIComponent(t)}`)
      .then(() => setReverificar(true))
      .catch((x: unknown) => {
        if (x instanceof ErrorApi && (x.estado === 404 || x.estado === 410)) {
          try {
            sessionStorage.removeItem(CLAVE_TOKEN);
          } catch {
            // sin almacenamiento
          }
          setSinVigencia(x.message);
        } else {
          setReverificar(true);
        }
      });
  }, [t]);

  let contenido;
  if (t === '') {
    contenido = (
      <Alert color="yellow" title="Abra el enlace del acta" data-testid="sin-enlace">
        Para responder los hallazgos, abra el enlace o escanee el código QR que viene en el acta de la visita.
      </Alert>
    );
  } else if (enlace.isLoading) {
    contenido = (
      <Center py="xl">
        <Loader />
      </Center>
    );
  } else if (!enlace.data) {
    const e = enlace.error;
    contenido = (
      <Alert color="red" title={e instanceof ErrorApi && e.estado === 410 ? 'Enlace sin vigencia' : 'Enlace no válido'} data-testid="error-enlace">
        {e?.message ?? 'El enlace no es válido.'}
      </Alert>
    );
  } else if (!verificado && !enlace.data.verificado) {
    contenido = (
      <Card withBorder maw={520}>
        <Stack>
          <Group gap="sm">
            <Title order={4}>
              Visita {enlace.data.numero} · {enlace.data.punto}
            </Title>
            <Badge color={COLOR_FASE[enlace.data.fase]}>{NOMBRE_FASE[enlace.data.fase]}</Badge>
          </Group>
          <Text size="sm">
            Para responder los hallazgos del acta, pida un código de verificación. Llega a uno de los correos del operador logístico
            registrados en el contrato.
          </Text>
          <PedirCodigo correos={enlace.data.correos} alVerificar={() => setVerificado(true)} />
        </Stack>
      </Card>
    );
  } else {
    contenido = <RespuestaOpl base={BASE} rutaDetalle={`${BASE}/detalle`} alReverificar={alReverificar} orden={2} />;
  }

  return (
    <Container size="lg" py="md" px="md" data-testid="opl-responder">
      <Stack>
        {/* Pantalla pública (sin la barra de la aplicación): el interruptor del sonido de las alertas va arriba a la derecha */}
        <Group justify="space-between" align="flex-start" wrap="nowrap" gap="xs">
          <Title order={1} c="digsa.8" style={{ minWidth: 0 }}>
            Respuesta a los hallazgos del acta
          </Title>
          <InterruptorSonido />
        </Group>
        {sinVigencia && (
          <Alert color="red" title="Enlace sin vigencia" data-testid="enlace-sin-vigencia">
            {sinVigencia}
          </Alert>
        )}
        {contenido}
      </Stack>
      <Modal opened={reverificar} onClose={() => setReverificar(false)} title="Verifique el código otra vez" closeOnClickOutside={false}>
        {reverificar && enlace.data && (
          <Stack>
            <Text size="sm">Por seguridad, pida un código nuevo. Lo que escribió en la pantalla no se pierde.</Text>
            <PedirCodigo
              correos={enlace.data.correos}
              alVerificar={() => {
                setReverificar(false);
                qc.invalidateQueries({ queryKey: ['opl', 'detalle'] });
              }}
            />
          </Stack>
        )}
      </Modal>
    </Container>
  );
}

/** Elegir el correo, pedir el código y verificarlo (15 minutos de vigencia, 5 intentos). */
function PedirCodigo({ correos, alVerificar }: { correos: string[]; alVerificar: () => void }) {
  const [indice, setIndice] = useState<string>(correos.length === 1 ? '0' : '');
  const [enviado, setEnviado] = useState<string | null>(null);
  const [codigo, setCodigo] = useState('');
  const [enviando, setEnviando] = useState(false);
  const v = useValidacion({ etiquetas: { indice: 'Enviar el código a', codigo: 'Código de verificación' }, testid: 'error-codigo' });
  const idElegir = useId();
  const idDigitos = useId();

  const pedir = async () => {
    v.reiniciar();
    setEnviando(true);
    try {
      const r = await api<{ mensaje: string }>(`${BASE}/codigo`, { cuerpo: { indice: Number(indice) } });
      setEnviado(r.mensaje);
      setCodigo('');
    } catch (x) {
      v.errorServidor(x, 'No se pudo enviar el código.');
    } finally {
      setEnviando(false);
    }
  };

  const verificar = async (e: FormEvent) => {
    e.preventDefault();
    v.reiniciar();
    setEnviando(true);
    try {
      await api(`${BASE}/verificar`, { cuerpo: { codigo } });
      alVerificar();
    } catch (x) {
      v.errorServidor(x, 'No se pudo verificar el código.');
    } finally {
      setEnviando(false);
    }
  };

  if (correos.length === 0) {
    return <Alert color="red">El operador logístico no tiene correos registrados. Comuníquese con la interventoría.</Alert>;
  }
  return (
    <Stack ref={v.ref}>
      {v.resumen}
      <Radio.Group
        label="Enviar el código a"
        value={indice}
        error={v.error('indice')}
        onChange={(x) => {
          setIndice(x);
          v.limpiar('indice');
        }}
        data-testid="correos-opl"
      >
        <Stack gap={6} mt={6}>
          {correos.map((c, i) => (
            <Radio key={i} value={String(i)} label={c} />
          ))}
        </Stack>
      </Radio.Group>
      <Group gap="xs">
        <Button
          variant={enviado ? 'light' : 'filled'}
          disabled={indice === ''}
          aria-describedby={indice === '' ? idElegir : undefined}
          loading={enviando}
          onClick={pedir}
        >
          {enviado ? 'Enviar otro código' : 'Enviar código'}
        </Button>
        {indice === '' && (
          <Text size="sm" c="dimmed" id={idElegir}>
            Elija el correo al que se envía el código.
          </Text>
        )}
      </Group>
      {enviado && (
        <form onSubmit={verificar}>
          <Stack>
            <Alert color="green" data-testid="codigo-enviado">
              {enviado} Vence en 15 minutos.
            </Alert>
            <PinInput
              length={6}
              type="number"
              oneTimeCode
              value={codigo}
              error={!!v.error('codigo')}
              onChange={(x) => {
                setCodigo(x);
                v.limpiar('codigo');
              }}
              aria-label="Código de verificación"
              getInputProps={(i) => ({ 'aria-label': `Dígito ${i + 1} de 6 del código` })}
              data-testid="codigo-opl"
            />
            <Group gap="xs">
              <Button type="submit" loading={enviando} disabled={codigo.length !== 6} aria-describedby={codigo.length !== 6 ? idDigitos : undefined}>
                Verificar código
              </Button>
              {codigo.length !== 6 && (
                <Text size="sm" c="dimmed" id={idDigitos}>
                  Escriba los 6 dígitos del código.
                </Text>
              )}
            </Group>
          </Stack>
        </form>
      )}
    </Stack>
  );
}
