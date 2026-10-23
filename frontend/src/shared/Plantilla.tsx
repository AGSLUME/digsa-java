import { AppShell, Burger, Group, NavLink, ScrollArea, Stack, Text, Title, UnstyledButton } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { Link, Outlet, useLocation } from 'react-router-dom';
import { AvisoInactividad } from './AvisoInactividad';
import { CambiarMiClave } from './CambiarMiClave';
import { LimiteErrores } from './LimiteErrores';
import { useSesion } from './sesion';

/** Plantilla con menú lateral que en celular se vuelve un menú desplegable (sin desplazamiento horizontal). */
export function Plantilla({ alSalir }: { alSalir: () => void }) {
  const s = useSesion();
  const [abierto, { toggle, close }] = useDisclosure(false);
  const [claveAbierta, { open: abrirClave, close: cerrarClave }] = useDisclosure(false);
  const { pathname } = useLocation();
  const grupos = Array.from(new Set(s.menu.map((m) => m.grupo)));

  return (
    <AppShell header={{ height: 56 }} navbar={{ width: 260, breakpoint: 'sm', collapsed: { mobile: !abierto } }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group gap="sm" wrap="nowrap">
            <Burger opened={abierto} onClick={toggle} hiddenFrom="sm" size="sm" aria-label="Menú" />
            <Title order={4} c="digsa.8">
              Suite DIGSA
            </Title>
          </Group>
          <Group gap="sm" wrap="nowrap">
            <Text size="sm" visibleFrom="sm" data-testid="nombre-usuario">
              {s.nombre}
            </Text>
            <UnstyledButton onClick={alSalir} fz="sm" c="digsa.8" fw={600} px="sm" py={10}>
              Salir
            </UnstyledButton>
          </Group>
        </Group>
      </AppShell.Header>
      <AppShell.Navbar p="sm">
        <AppShell.Section grow component={ScrollArea} type="auto">
          <Stack gap={4}>
            <NavLink component={Link} to="/" label="Inicio" active={pathname === '/'} onClick={close} />
            {grupos.map((g) => (
              <div key={g || 'general'}>
                {g && (
                  <Text size="xs" c="dimmed" tt="uppercase" mt="sm" mb={4} px="sm">
                    {g}
                  </Text>
                )}
                {s.menu
                  .filter((m) => m.grupo === g && m.ruta !== 'inicio')
                  .map((m) => (
                    <NavLink
                      key={m.ruta}
                      component={Link}
                      to={'/' + m.ruta}
                      label={m.titulo}
                      active={pathname.startsWith('/' + m.ruta)}
                      onClick={close}
                    />
                  ))}
              </div>
            ))}
          </Stack>
        </AppShell.Section>
        <AppShell.Section>
          <NavLink
            component="button"
            label="Mi contraseña"
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
