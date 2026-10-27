import { AppShell, Box, Burger, Group, NavLink, ScrollArea, Stack, Text, UnstyledButton } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { IconKey } from '@tabler/icons-react';
import { useEffect, useState } from 'react';
import { Link, Outlet, useLocation } from 'react-router-dom';
import { AvisoInactividad } from './AvisoInactividad';
import { CambiarMiClave } from './CambiarMiClave';
import { fechaHora } from './formato';
import { mmss } from './inactividad';
import { InterruptorSonido } from './InterruptorSonido';
import { LimiteErrores } from './LimiteErrores';
import { agrupar, ICONO_INICIO, type EntradaMenu } from './menu';
import { useRestantesSesion } from './relojSesion';
import { useSesion } from './sesion';

/** Alto de la cabecera (dos líneas de marca en escritorio, una en celular). */
const ALTO_CABECERA = { base: 56, sm: 64 };

/** Fecha y hora militar de Bogotá (Anexo 5 num. 2.4), que avanza sola. */
function Reloj() {
  const [ahora, setAhora] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setAhora(Date.now()), 15_000);
    return () => clearInterval(t);
  }, []);
  return (
    <Text component="span" ff="monospace" size="sm" data-testid="reloj-cabecera">
      {fechaHora(ahora)} h
    </Text>
  );
}

/** Cuenta regresiva del cierre por inactividad (la lleva AvisoInactividad); el aviso con «Seguir conectado» sigue igual. */
function CuentaRegresiva() {
  const restantes = useRestantesSesion();
  if (restantes === null) return null;
  return (
    <Text component="span" size="sm" visibleFrom="md" data-testid="inactividad-cabecera" style={{ whiteSpace: 'nowrap' }}>
      Cierre por inactividad en{' '}
      <Text component="span" ff="monospace" inherit>
        {mmss(restantes)}
      </Text>
    </Text>
  );
}

function Entrada({ e, activa, alElegir }: { e: EntradaMenu; activa: boolean; alElegir: () => void }) {
  const Icono = e.icono;
  return (
    <NavLink
      component={Link}
      to={'/' + e.ruta}
      label={e.titulo}
      active={activa}
      onClick={alElegir}
      mih={44}
      leftSection={<Icono size={18} stroke={1.8} aria-hidden />}
      rightSection={
        e.numeral ? (
          <Text component="span" size="xs" c="dimmed" ff="monospace" aria-hidden>
            {e.numeral}
          </Text>
        ) : undefined
      }
    />
  );
}

function TituloGrupo({ children }: { children: string }) {
  return (
    <Text size="xs" c="dimmed" tt="uppercase" mt="sm" mb={4} px="sm" style={{ letterSpacing: '.06em' }}>
      {children}
    </Text>
  );
}

/**
 * Plantilla del mockup aprobado: cabecera verde con la marca (decisión UI-9), fecha y hora militar, cuenta regresiva de inactividad,
 * nombre (con los perfiles al pasar el puntero), sonido y «Salir»; menú lateral con los 4 grupos del mockup (UI-7) que en celular se
 * abre con «Menú» (sin desplazamiento horizontal). La marca no es un encabezado: el primero de cada pantalla es su h1 (UI-14).
 */
export function Plantilla({ alSalir }: { alSalir: () => void }) {
  const s = useSesion();
  const [abierto, { toggle, close }] = useDisclosure(false);
  const [claveAbierta, { open: abrirClave, close: cerrarClave }] = useDisclosure(false);
  const { pathname } = useLocation();
  const grupos = agrupar(s.menu);

  return (
    <AppShell header={{ height: ALTO_CABECERA }} navbar={{ width: 260, breakpoint: 'sm', collapsed: { mobile: !abierto } }} padding="md">
      <AppShell.Header bg="var(--digsa-marca)" c="var(--digsa-marca-tinta)" withBorder={false}>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap" gap="sm">
          <Group gap="sm" wrap="nowrap" miw={0}>
            <Burger opened={abierto} onClick={toggle} hiddenFrom="sm" size="sm" color="var(--digsa-marca-tinta)" aria-label="Menú" />
            <Box
              aria-hidden
              visibleFrom="xs"
              w={34}
              h={34}
              bg="var(--digsa-marca2)"
              style={{ borderRadius: 8, display: 'grid', placeItems: 'center', fontWeight: 700, flex: 'none' }}
            >
              ID
            </Box>
            <Box miw={0}>
              <Text fw={700} size="sm" lh={1.3} truncate>
                Interventoría Integral DIGSA
              </Text>
              <Text size="xs" c="#BFD0C5" lh={1.3} visibleFrom="sm" truncate>
                Sistema de información de interventoría · cuentas médicas
              </Text>
            </Box>
          </Group>
          <Group gap="md" wrap="nowrap">
            <Box visibleFrom="lg">
              <Reloj />
            </Box>
            <CuentaRegresiva />
            <Text
              size="sm"
              visibleFrom="sm"
              data-testid="nombre-usuario"
              title={s.perfiles.join(' · ')}
              style={{ border: '1px solid #5E8A72', borderRadius: 999, padding: '3px 10px', whiteSpace: 'nowrap' }}
            >
              {s.nombre}
            </Text>
            <InterruptorSonido color="var(--digsa-marca-tinta)" />
            <UnstyledButton onClick={alSalir} fz="sm" fw={600} px="sm" mih={44} c="var(--digsa-marca-tinta)">
              Salir
            </UnstyledButton>
          </Group>
        </Group>
      </AppShell.Header>
      <AppShell.Navbar p="sm" aria-label="Módulos">
        <AppShell.Section grow component={ScrollArea} type="auto">
          <Stack gap={2}>
            <TituloGrupo>General</TituloGrupo>
            <NavLink
              component={Link}
              to="/"
              label="Inicio"
              active={pathname === '/'}
              onClick={close}
              mih={44}
              leftSection={<ICONO_INICIO size={18} stroke={1.8} aria-hidden />}
            />
            {grupos.map(({ grupo, entradas }) => (
              <div key={grupo}>
                {grupo !== 'General' && <TituloGrupo>{grupo}</TituloGrupo>}
                {entradas.map((e) => (
                  <Entrada key={e.ruta} e={e} activa={pathname.startsWith('/' + e.ruta)} alElegir={close} />
                ))}
              </div>
            ))}
          </Stack>
        </AppShell.Section>
        <AppShell.Section>
          <Text size="xs" c="dimmed" px="sm" mb={4} hiddenFrom="sm">
            {s.nombre} · {s.perfiles.join(' · ')}
          </Text>
          <NavLink
            component="button"
            label="Mi contraseña"
            mih={44}
            leftSection={<IconKey size={18} stroke={1.8} aria-hidden />}
            onClick={() => {
              close();
              abrirClave();
            }}
          />
        </AppShell.Section>
      </AppShell.Navbar>
      <AppShell.Main>
        <LimiteErrores>
          <Outlet />
        </LimiteErrores>
      </AppShell.Main>
      <AvisoInactividad minutos={s.minutosInactividad} alSalir={alSalir} />
      <CambiarMiClave abierto={claveAbierta} alCerrar={cerrarClave} />
    </AppShell>
  );
}
