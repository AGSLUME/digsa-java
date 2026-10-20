import { Text } from '@mantine/core';
import { useEffect, useRef, useState, type MutableRefObject } from 'react';

interface ConfigIngreso {
  captchaSitio: string | null;
  captchaDesactivado: boolean;
}

declare global {
  interface Window {
    turnstile?: {
      render: (el: HTMLElement, o: { sitekey: string; callback: (t: string) => void; 'expired-callback': () => void; language: string }) => string;
      reset: (id?: string) => void;
    };
  }
}

const SCRIPT = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';

/** Captcha Cloudflare Turnstile. En local sin claves el servidor lo desactiva y aquí solo se avisa. */
export function Turnstile({ alResolver, reiniciar }: { alResolver: (t: string | null) => void; reiniciar: MutableRefObject<(() => void) | null> }) {
  const [cfg, setCfg] = useState<ConfigIngreso | null>(null);
  const caja = useRef<HTMLDivElement>(null);

  useEffect(() => {
    fetch('/api/auth/inicio', { credentials: 'same-origin' })
      .then((r) => r.json())
      .then(setCfg)
      .catch(() => setCfg({ captchaSitio: null, captchaDesactivado: false }));
  }, []);

  useEffect(() => {
    if (!cfg?.captchaSitio || !caja.current) return;
    const dibujar = () => {
      if (!window.turnstile || !caja.current || !cfg.captchaSitio) return;
      const id = window.turnstile.render(caja.current, {
        sitekey: cfg.captchaSitio,
        language: 'es',
        callback: (t) => alResolver(t),
        'expired-callback': () => alResolver(null),
      });
      reiniciar.current = () => {
        window.turnstile?.reset(id);
        alResolver(null);
      };
    };
    if (window.turnstile) {
      dibujar();
      return;
    }
    const s = document.createElement('script');
    s.src = SCRIPT;
    s.async = true;
    s.onload = dibujar;
    document.head.appendChild(s);
  }, [cfg, alResolver, reiniciar]);

  if (cfg?.captchaDesactivado) {
    return (
      <Text size="xs" c="dimmed">
        Captcha desactivado en local
      </Text>
    );
  }
  return <div ref={caja} />;
}
