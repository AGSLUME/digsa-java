import { ActionIcon, AppShell, Box, Burger, Center, Group, Loader, NavLink, ScrollArea, Stack, Text, Tooltip, UnstyledButton } from '@mantine/core';
import { useDisclosure, useMediaQuery } from '@mantine/hooks';
import { IconBell, IconKey, IconLayoutSidebarLeftCollapse, IconLayoutSidebarLeftExpand, type Icon } from '@tabler/icons-react';
import { Suspense, useEffect, useState } from 'react';
import { Link, Outlet, useLocation } from 'react-router-dom';
import { AvisoInactividad } from './AvisoInactividad';
import { CambiarMiClave } from './CambiarMiClave';
import { fechaHora } from './formato';
import { mmss } from './inactividad';
import { InterruptorSonido } from './InterruptorSonido';
import { LimiteErrores } from './LimiteErrores';
import { agrupar, ICONO_INICIO, type EntradaMenu } from './menu';
import { useRestantesSesion } from './relojSesion';
import { puedeEntrar, useSesion } from './sesion';

/** Medidas del shell LUME (referencias_interfaz/LUME_design_system.md). */
const ANCHO = 250;
const ANCHO_COLAPSADO = 68;
const ALTO_CABECERA = 64;
const CLAVE_COLAPSADO = 'lume.sidebar.colapsado';

function leerPreferencia(): boolean | null {
  try {
    const v = window.localStorage.getItem(CLAVE_COLAPSADO);
    return v === null ? null : v === 'si';
  } catch {
    return null;
  }
}

function guardarPreferencia(colapsado: boolean): void {
  try {
    window.localStorage.setItem(CLAVE_COLAPSADO, colapsado ? 'si' : 'no');
  } catch {
    // sin almacenamiento: vale para esta pestaña
  }
}

/** Iniciales para el avatar (dos letras: nombre y primer apellido). */
export function iniciales(nombre: string): string {
  const partes = nombre.trim().split(/\s+/).filter(Boolean);
  const letras = partes.length > 1 ? [partes[0]!, partes[1]!] : partes;
  return letras.map((p) => p.charAt(0).toUpperCase()).join('') || '?';
}

/** Fecha y hora militar de Bogotá (Anexo 5 num. 2.4), que avanza sola. */
function Reloj() {
  const [ahora, setAhora] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setAhora(Date.now()), 15_000);
    return () => clearInterval(t);
  }, []);
  return (
    <Text component="span" fz={12.5} c="dimmed" data-testid="reloj-cabecera" style={{ whiteSpace: 'nowrap' }}>
      {fechaHora(ahora)} h
    </Text>
  );
}

/** Cuenta regresiva del cierre por inactividad (la lleva AvisoInactividad); el aviso con «Seguir conectado» sigue igual. */
function CuentaRegresiva() {
  const restantes = useRestantesSesion();
  if (restantes === null) return null;
  return (
    <Text component="span" fz={12.5} c="dimmed" data-testid="inactividad-cabecera" style={{ whiteSpace: 'nowrap' }}>
      Cierre por inactividad en {mmss(restantes)}
    </Text>
  );
}

/** Entrada del menú: con el sidebar colapsado solo el ícono, con el nombre en el nombre accesible y en una ayuda al pasar. */
function Entrada({
  ruta,
  titulo,
  icono: Icono,
  numeral,
  activa,
  colapsado,
  alElegir,
}: {
  ruta: string;
  titulo: string;
  icono: Icon;
  numeral?: string;
  activa: boolean;
  colapsado: boolean;
  alElegir: () => void;
}) {
  const enlace = (
    <NavLink
      component={Link}
      to={ruta}
      className="lume-nav"
      variant="filled"
      label={colapsado ? undefined : titulo}
      aria-label={colapsado ? titulo : undefined}
      active={activa}
      aria-current={activa ? 'page' : undefined}
      onClick={alElegir}
      leftSection={<Icono size={16} stroke={1.6} aria-hidden />}
      rightSection={
        numeral && !colapsado ? (
          <Text component="span" fz={11} aria-hidden className="lume-nav-numeral">
            {numeral}
          </Text>
        ) : undefined
      }
    />
  );
  return colapsado ? (
    <Tooltip label={titulo} position="right" withArrow events={{ hover: true, focus: true, touch: false }}>
      {enlace}
    </Tooltip>
  ) : (
    enlace
  );
}

/**
 * Migas de la ruta actual: grupo del menú y pantalla; los detalles que no están en el menú (la visita, el seguimiento, el detalle desde
 * agenda, calendario, mis visitas u OPL) agregan su nivel para que `aria-current` quede solo en el último.
 */
export function migasDe(pathname: string, grupos: ReturnType<typeof agrupar>): string[] {
  if (pathname === '/') return ['Inicio'];
  const entradas = grupos.flatMap((g) => g.entradas.map((e) => ({ grupo: g.grupo, e })));
  const de = (ruta: string) => entradas.find(({ e }) => e.ruta === ruta);
  if (/^\/visita\/[^/]+/.test(pathname)) return ['Auditoría', 'Visita'];
  if (pathname.startsWith('/seguimiento')) {
    const agenda = de('agenda');
    return agenda ? [agenda.grupo, agenda.e.titulo, 'Seguimiento'] : ['Auditoría', 'Seguimiento'];
  }
  const ruta = pathname.split('/')[1] ?? '';
  const actual = de(ruta);
  if (!actual) return [];
  return /^\/[^/]+\/visita\/[^/]+/.test(pathname) ? [actual.grupo, actual.e.titulo, 'Detalle de la visita'] : [actual.grupo, actual.e.titulo];
}

/** Ruta de migas (la pantalla actual en negrita, `aria-current`). */
function Migas({ partes }: { partes: string[] }) {
  if (partes.length === 0) return null;
  return (
    <Box component="nav" aria-label="Ruta" visibleFrom="sm" miw={0}>
      <Box component="ol" className="lume-migas">
        {partes.map((p, i) => (
          <li key={i} aria-current={i === partes.length - 1 ? 'page' : undefined}>
            {p}
          </li>
        ))}
      </Box>
    </Box>
  );
}

/**
 * Shell del design system LUME (fase U3.5): sidebar de 250 px en --lume-blue-dark, colapsable a 68 px solo con íconos (por debajo de
 * 900 px colapsa solo; en celular es un panel que se abre con «Menú»), cabecera blanca de 64 px con la ruta de migas, la campana (solo
 * si el perfil tiene Alertas), el sonido y el usuario (avatar con iniciales, nombre y perfil). El menú sigue saliendo del servidor
 * (Pantallas.java, Anexo 5 num. 3.7), agrupado como el mockup (UI-7). La marca no es un encabezado: el primero es el h1 de la página.
 */
export function Plantilla({ alSalir }: { alSalir: () => void }) {
  const s = useSesion();
  const [abierto, { toggle, close }] = useDisclosure(false);
  const [claveAbierta, { open: abrirClave, close: cerrarClave }] = useDisclosure(false);
  const { pathname } = useLocation();
  // Valor inicial en el primer render (sin esperar al efecto): el sidebar no se pinta a 250 px para pasar enseguida a 68 px
  const celular = useMediaQuery('(max-width: 47.99em)', undefined, { getInitialValueInEffect: false }) ?? false;
  const estrecho = useMediaQuery('(max-width: 900px)', undefined, { getInitialValueInEffect: false }) ?? false;
  const [preferencia, setPreferencia] = useState<boolean | null>(leerPreferencia);
  // En celular el panel siempre va completo; en tableta (< 900 px) colapsa solo salvo que la persona lo abra
  const colapsado = !celular && (preferencia ?? estrecho);
  const grupos = agrupar(s.menu);
  const migas = migasDe(pathname, grupos);

  const alternar = () => {
    const nuevo = !colapsado;
    setPreferencia(nuevo);
    guardarPreferencia(nuevo);
  };
  const entrada = (e: Pick<EntradaMenu, 'ruta' | 'titulo' | 'icono' | 'numeral'>, activa: boolean) => (
    <Entrada
      key={e.ruta}
      ruta={e.ruta === 'inicio' ? '/' : '/' + e.ruta}
      titulo={e.titulo}
      icono={e.icono}
      numeral={e.numeral}
      activa={activa}
      colapsado={colapsado}
      alElegir={close}
    />
  );

  return (
    <AppShell
      header={{ height: ALTO_CABECERA }}
      navbar={{ width: { base: '100%', sm: colapsado ? ANCHO_COLAPSADO : ANCHO }, breakpoint: 'sm', collapsed: { mobile: !abierto } }}
      padding={{ base: 16, sm: 32 }}
    >
      <AppShell.Header className="lume-cabecera">
        <Group h="100%" px={{ base: 'md', sm: 24 }} justify="space-between" wrap="nowrap" gap="md">
          <Group gap="md" wrap="nowrap" miw={0}>
            <Burger opened={abierto} onClick={toggle} hiddenFrom="sm" size="sm" aria-label="Menú" />
            <Group gap={10} wrap="nowrap" style={{ flex: 'none' }}>
              <Box aria-hidden className="lume-logo">
                ID
              </Box>
              <Text fz={14.5} fw={700} visibleFrom="xs" style={{ whiteSpace: 'nowrap' }}>
                Interventoría Integral DIGSA
              </Text>
            </Group>
            <Migas partes={migas} />
          </Group>
          <Group gap="sm" wrap="nowrap">
            <Box visibleFrom="lg">
              <Reloj />
            </Box>
            <Box visibleFrom="md">
              <CuentaRegresiva />
            </Box>
            {puedeEntrar(s, 'alertas') && (
              <Tooltip label="Alertas" withArrow>
                <ActionIcon component={Link} to="/alertas" variant="subtle" color="gray" size={44} aria-label="Ir a Alertas">
                  <IconBell size={16} stroke={1.6} aria-hidden />
                </ActionIcon>
              </Tooltip>
            )}
            <InterruptorSonido color="gray" />
            <Group gap={10} wrap="nowrap" visibleFrom="sm">
              <Box aria-hidden className="lume-avatar">
                {iniciales(s.nombre)}
              </Box>
              <Box miw={0} maw={180}>
                <Text fz={13} fw={600} lh={1.25} truncate data-testid="nombre-usuario">
                  {s.nombre}
                </Text>
                <Text fz={11.5} c="dimmed" lh={1.25} truncate title={s.perfiles.join(' · ')}>
                  {s.perfiles.join(' · ')}
                </Text>
              </Box>
            </Group>
            <UnstyledButton onClick={alSalir} fz={13.5} fw={600} px="sm" mih={44} c="lume.6">
              Salir
            </UnstyledButton>
          </Group>
        </Group>
      </AppShell.Header>
      <AppShell.Navbar id="menu-lateral" className="lume-sidebar" aria-label="Módulos" withBorder={false}>
        <Group justify="space-between" wrap="nowrap" px={colapsado ? 0 : 'md'} pt="md" pb="sm" gap={6} style={colapsado ? { justifyContent: 'center' } : undefined}>
          {!colapsado && (
            <Box miw={0}>
              <Text fz={11} fw={700} tt="uppercase" c="white" style={{ letterSpacing: '.06em' }}>
                Panel operativo
              </Text>
              <Text fz={12} className="lume-sidebar-apagado" truncate>
                Interventoría Integral DIGSA
              </Text>
            </Box>
          )}
          <ActionIcon
            visibleFrom="sm"
            variant="subtle"
            size={26}
            className="lume-sidebar-boton"
            aria-label="Menú lateral"
            aria-expanded={!colapsado}
            aria-controls="menu-lateral"
            onClick={alternar}
          >
            {colapsado ? <IconLayoutSidebarLeftExpand size={16} stroke={1.6} aria-hidden /> : <IconLayoutSidebarLeftCollapse size={16} stroke={1.6} aria-hidden />}
          </ActionIcon>
        </Group>
        <AppShell.Section grow component={ScrollArea} type="auto" px={colapsado ? 10 : 'sm'}>
          <Stack gap={2} pb="sm">
            {entrada({ ruta: 'inicio', titulo: 'Inicio', icono: ICONO_INICIO }, pathname === '/')}
            {grupos.map(({ grupo, entradas }) => (
              <div key={grupo}>
                {!colapsado && <Text className="lume-seccion">{grupo}</Text>}
                {colapsado && <Box className="lume-separador" aria-hidden />}
                {entradas.map((e) => entrada(e, pathname.startsWith('/' + e.ruta)))}
              </div>
            ))}
          </Stack>
        </AppShell.Section>
        <AppShell.Section px={colapsado ? 10 : 'sm'} pb="sm">
          {!colapsado && (
            <Text fz={11.5} className="lume-sidebar-apagado" px="sm" mb={4} hiddenFrom="sm">
              {s.nombre} · {s.perfiles.join(' · ')}
            </Text>
          )}
          <Tooltip label="Mi contraseña" position="right" withArrow disabled={!colapsado}>
            <NavLink
              component="button"
              className="lume-nav"
              label={colapsado ? undefined : 'Mi contraseña'}
              aria-label={colapsado ? 'Mi contraseña' : undefined}
              leftSection={<IconKey size={16} stroke={1.6} aria-hidden />}
              onClick={() => {
                close();
                abrirClave();
              }}
            />
          </Tooltip>
        </AppShell.Section>
      </AppShell.Navbar>
      <AppShell.Main className="lume-contenido">
        {/* key: un error en una pantalla no bloquea las demás: al navegar el límite empieza de cero */}
        <LimiteErrores key={pathname}>
          {/* Cada pantalla se descarga al abrirla (división por rutas, U5): el menú y la cabecera siguen a la vista */}
          <Suspense
            fallback={
              <Center py="xl">
                <Loader role="status" aria-label="Cargando la pantalla" />
              </Center>
            }
          >
            <Outlet />
          </Suspense>
        </LimiteErrores>
      </AppShell.Main>
      <AvisoInactividad minutos={s.minutosInactividad} alSalir={alSalir} />
      <CambiarMiClave abierto={claveAbierta} alCerrar={cerrarClave} />
    </AppShell>
  );
}
