import { Button, Group, Modal, Text } from '@mantine/core';
import { useEffect, useRef, useState } from 'react';
import { api, CLAVE_ACTIVIDAD, CLAVE_MOTIVO, ErrorApi, marcarActividad } from './api';
import { estadoAviso, mmss, ultimaActividadDelServidor } from './inactividad';
import { publicarRestantes } from './relojSesion';

/**
 * Aviso de cierre por inactividad con cuenta regresiva, «Salir» y «Seguir conectado». La última actividad se comparte entre
 * pestañas (localStorage): si otra pestaña tuvo respuesta del servidor, este aviso se cierra. Al vencer, el servidor confirma el
 * cierre; si no responde (sin red, error) o la sesión ya no existe, se vuelve al ingreso: el aviso nunca se queda en 00:00.
 *
 * El administrador puede cambiar el TIME OUT con la sesión abierta (Anexo 5, 3.2): cada minuto, y al vencer el plazo local, se consulta
 * /api/sesion/estado, que NO renueva la sesión, y se adoptan los minutos y los segundos restantes del servidor.
 */
export function AvisoInactividad({ minutos, alSalir }: { minutos: number; alSalir: () => void }) {
  const [restantes, setRestantes] = useState<number | null>(null);
  const [vigentes, setVigentes] = useState(minutos);
  const local = useRef(Date.now());
  const verificando = useRef(false);
  const sincronizado = useRef(Date.now());

  useEffect(() => setVigentes(minutos), [minutos]);

  // Quien está escribiendo o haciendo clic (p. ej. diligenciando el formulario de la visita sin guardar) no está inactivo: se
  // renueva la sesión en el servidor como máximo cada 20 s. Sin interacción, el aviso y el cierre siguen igual.
  const renovado = useRef(0);
  useEffect(() => {
    const alInteractuar = (ev: Event) => {
      // Un clic en un botón o enlace ya hace su propia petición (y «Salir» no debe competir con una renovación)
      if (ev.type === 'pointerdown' && ev.target instanceof Element && ev.target.closest('button, a, [role="button"]')) return;
      const ahora = Date.now();
      if (ahora - renovado.current < 20_000 || verificando.current) return;
      renovado.current = ahora;
      api<{ minutos: number }>('/api/sesion/mantener')
        .then((r) => {
          local.current = Date.now();
          setVigentes(r.minutos);
          setRestantes(null);
        })
        .catch(() => {
          // si la sesión ya se cerró, el manejo del 401 en api.ts lleva al ingreso
        });
    };
    const eventos = ['keydown', 'pointerdown', 'input'] as const;
    eventos.forEach((e) => document.addEventListener(e, alInteractuar, { passive: true, capture: true }));
    return () => eventos.forEach((e) => document.removeEventListener(e, alInteractuar, { capture: true }));
  }, []);

  useEffect(() => {
    marcarActividad();
    const t = setInterval(() => {
      let ultima = local.current;
      try {
        ultima = Math.max(ultima, Number(localStorage.getItem(CLAVE_ACTIVIDAD) ?? 0));
      } catch {
        // sin almacenamiento compartido
      }
      const e = estadoAviso(ultima, Date.now(), vigentes);
      publicarRestantes(e.restantes); // cuenta regresiva de la cabecera
      if (e.vencida || Date.now() - sincronizado.current >= 60_000) {
        if (verificando.current) return;
        verificando.current = true;
        sincronizado.current = Date.now();
        api<{ minutos: number; restantes: number }>('/api/sesion/estado')
          .then((r) => {
            // El servidor manda: su plazo vigente y lo que le queda (otra pestaña, relojes distintos o un TIME OUT nuevo)
            local.current = ultimaActividadDelServidor(Date.now(), r.minutos, r.restantes);
            setVigentes(r.minutos);
            if (e.vencida) setRestantes(null);
          })
          .catch((x: unknown) => {
            // Un 401 con motivo ya llevó al ingreso con el mensaje del servidor (api.ts); lo demás (sin red, error, sesión que
            // ya no existe en el servidor) también vuelve al ingreso, con el motivo de inactividad
            if (x instanceof ErrorApi && x.estado === 401 && typeof x.datos.motivo === 'string' && x.datos.motivo !== 'sin_sesion') return;
            if (!e.vencida) return; // la sincronización periódica no cierra por un error pasajero
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
    return () => {
      clearInterval(t);
      publicarRestantes(null);
    };
  }, [vigentes, alSalir]);

  const seguir = async () => {
    try {
      const r = await api<{ minutos: number }>('/api/sesion/mantener');
      local.current = Date.now();
      setVigentes(r.minutos);
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
