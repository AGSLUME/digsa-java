import { Button, Group, Modal, Text } from '@mantine/core';
import { useEffect, useRef, useState } from 'react';
import { api, CLAVE_ACTIVIDAD, CLAVE_MOTIVO, ErrorApi, marcarActividad } from './api';
import { estadoAviso, mmss } from './inactividad';

/**
 * Aviso de cierre por inactividad con cuenta regresiva, «Salir» y «Seguir conectado». La última actividad se comparte entre
 * pestañas (localStorage): si otra pestaña tuvo respuesta del servidor, este aviso se cierra. Al vencer, el servidor confirma el
 * cierre; si no responde (sin red, error) o la sesión ya no existe, se vuelve al ingreso: el aviso nunca se queda en 00:00.
 */
export function AvisoInactividad({ minutos, alSalir }: { minutos: number; alSalir: () => void }) {
  const [restantes, setRestantes] = useState<number | null>(null);
  const local = useRef(Date.now());
  const verificando = useRef(false);

  useEffect(() => {
    marcarActividad();
    const t = setInterval(() => {
      let ultima = local.current;
      try {
        ultima = Math.max(ultima, Number(localStorage.getItem(CLAVE_ACTIVIDAD) ?? 0));
      } catch {
        // sin almacenamiento compartido
      }
      const e = estadoAviso(ultima, Date.now(), minutos);
      if (e.vencida) {
        if (verificando.current) return;
        verificando.current = true;
        api('/api/sesion')
          .then(() => {
            // El servidor aún la tiene viva (p. ej. relojes distintos): se sigue contando desde ahora
            local.current = Date.now();
            setRestantes(null);
          })
          .catch((x: unknown) => {
            // Un 401 con motivo ya llevó al ingreso con el mensaje del servidor (api.ts); lo demás (sin red, error, sesión que
            // ya no existe en el servidor) también vuelve al ingreso, con el motivo de inactividad
            if (x instanceof ErrorApi && x.estado === 401 && typeof x.datos.motivo === 'string' && x.datos.motivo !== 'sin_sesion') return;
            try {
              sessionStorage.setItem(CLAVE_MOTIVO, 'La sesión se cerró por inactividad. Vuelva a iniciar sesión.');
            } catch {
              // sin almacenamiento
            }
            alSalir();
          })
          .finally(() => {
            verificando.current = false;
          });
        return;
      }
      setRestantes(e.mostrar ? e.restantes : null);
    }, 1000);
    return () => clearInterval(t);
  }, [minutos, alSalir]);

  const seguir = async () => {
    try {
      await api('/api/sesion/mantener');
      local.current = Date.now();
      setRestantes(null);
    } catch (e) {
      if (e instanceof ErrorApi && e.estado === 401) {
        alSalir();
      } else {
        setRestantes(null);
      }
    }
  };

  return (
    <Modal opened={restantes !== null} onClose={seguir} title="Su sesión está por cerrarse" centered withCloseButton={false}>
      <Text mb="md">
        Por inactividad, la sesión se cerrará en <strong data-testid="cuenta-regresiva">{mmss(restantes ?? 0)}</strong>.
      </Text>
      <Group justify="flex-end">
        <Button variant="default" onClick={alSalir}>
          Salir
        </Button>
        <Button onClick={seguir}>Seguir conectado</Button>
      </Group>
    </Modal>
  );
}
