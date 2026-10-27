import {
  Alert,
  Badge,
  Button,
  Card,
  createTheme,
  Drawer,
  FileInput,
  Input,
  InputWrapper,
  Modal,
  MultiSelect,
  Pagination,
  PasswordInput,
  rem,
  Select,
  Table,
  Tabs,
  type CSSVariablesResolver,
  type MantineColorsTuple,
} from '@mantine/core';

/**
 * Design system LUME (referencias_interfaz/LUME_design_system.md; fase U3.5): los mismos valores de `lume.css` (`:root`, variables
 * `--lume-*`), aquí para el tema de Mantine. Una prueba comprueba que coinciden con el CSS.
 */
export const LUME = {
  blue: '#1D4ED8',
  blueDark: '#1E293B',
  blueActive: '#2563EB',
  bg: '#F4F6F9',
  white: '#FFFFFF',
  border: '#E2E8F0',
  text: '#0F172A',
  textMuted: '#64748B',
  edit: '#2563EB',
  successBg: '#DCEFE1',
  successText: '#15803D',
  danger: '#B91C1C',
  dangerBg: '#F5DEDE',
  warningBg: '#F5E8D3',
  warningText: '#92400E',
  infoBg: '#DBEAFE',
  infoText: '#1D4ED8',
  neutralBg: '#E2E8F0',
  neutralText: '#475569',
  radius: '10px',
  shadow: '0 1px 3px rgba(0,0,0,0.06)',
} as const;

/** Colores que la misma especificación usa en sus componentes (no son tokens, pero tampoco se inventan). */
export const COMPLEMENTOS = {
  /** Texto del sidebar. */
  sidebarTexto: '#CBD5E1',
  /** Subtítulo del sidebar; también los títulos de sección del sidebar (el #64748B de la especificación no llega a AA sobre #1E293B). */
  sidebarApagado: '#94A3B8',
  encabezadoTabla: '#FAFBFC',
  filaHover: '#F8FAFC',
  primarioDeshabilitado: '#93C5FD',
  bordeAlertaWarning: '#E3CDA3',
  bordeAlertaInfo: '#BFDBFE',
  bordeAlertaSuccess: '#BEDBC6',
  bordeAlertaDanger: '#E3C3C3',
} as const;

/**
 * Ajustes de contraste AA con colores de la misma paleta (aprobados por la integradora el 2026-10-27):
 * - texto apagado (`dimmed`) en #475569 (--lume-neutral-text): el #64748B no llega a 4,5:1 sobre el fondo --lume-bg (4,40);
 * - borde de los campos en #64748B (--lume-text-muted): el --lume-border no llega a 3:1 (WCAG 1.4.11) y es lo único que marca el campo;
 * - texto de la insignia de éxito en --lume-text (el verde sobre su fondo da 4,17); el punto y el fondo siguen verdes.
 */
export const APAGADO = LUME.neutralText;
export const BORDE_CAMPO = LUME.textMuted;

/** Tono de cada paleta de Mantine que usan el texto (`c="red"`), el relleno y los bordes: el token de texto de la especificación. */
export const TONO = 6;

// Índice 0 = fondo suave de la variante; 6 = texto (TONO); 7 = hover del relleno. Los intermedios son escalones para bordes y gráficos.
export const PALETAS: Record<string, MantineColorsTuple> = {
  // Primario: #2563EB (--lume-blue-active) con hover #1D4ED8 (--lume-blue); deshabilitado #93C5FD
  lume: ['#EFF6FF', '#DBEAFE', '#BFDBFE', '#93C5FD', '#60A5FA', '#3B82F6', '#2563EB', '#1D4ED8', '#1E40AF', '#1E3A8A'],
  // danger
  red: ['#F5DEDE', '#F0D2D2', '#E3C3C3', '#DC9C9C', '#D06B6B', '#C43D3D', '#B91C1C', '#991B1B', '#7F1D1D', '#661717'],
  // warning (naranja y amarillo son la misma variante: LUME tiene una sola)
  orange: ['#F5E8D3', '#F0DEC2', '#E3CDA3', '#D6A866', '#C2852F', '#A86518', '#92400E', '#7C350C', '#66290A', '#4F2008'],
  yellow: ['#F5E8D3', '#F0DEC2', '#E3CDA3', '#D6A866', '#C2852F', '#A86518', '#92400E', '#7C350C', '#66290A', '#4F2008'],
  // success
  green: ['#DCEFE1', '#CDE6D4', '#BEDBC6', '#86C49A', '#4FA86B', '#2A8F4B', '#15803D', '#166534', '#14532D', '#0F3F22'],
  // info
  blue: ['#DBEAFE', '#C7DCFD', '#BFDBFE', '#93C5FD', '#60A5FA', '#3B82F6', '#1D4ED8', '#1E40AF', '#1E3A8A', '#172E6E'],
  // neutral (pizarra): 0 fondo de página, 2 borde, 6 texto apagado, 8 sidebar, 9 texto
  gray: ['#F4F6F9', '#F8FAFC', '#E2E8F0', '#CBD5E1', '#94A3B8', '#64748B', '#475569', '#334155', '#1E293B', '#0F172A'],
};

/** Variante LUME de cada color de Mantine que usan las pantallas (insignias y alertas). */
type Variante = 'success' | 'warning' | 'info' | 'neutral' | 'danger';
const VARIANTE: Record<string, Variante> = {
  green: 'success',
  orange: 'warning',
  yellow: 'warning',
  blue: 'info',
  lume: 'info',
  gray: 'neutral',
  red: 'danger',
};
export const VARIANTES: Record<Variante, { fondo: string; texto: string; punto: string; borde: string }> = {
  success: { fondo: LUME.successBg, texto: LUME.text, punto: LUME.successText, borde: COMPLEMENTOS.bordeAlertaSuccess },
  warning: { fondo: LUME.warningBg, texto: LUME.warningText, punto: LUME.warningText, borde: COMPLEMENTOS.bordeAlertaWarning },
  info: { fondo: LUME.infoBg, texto: LUME.infoText, punto: LUME.infoText, borde: COMPLEMENTOS.bordeAlertaInfo },
  neutral: { fondo: LUME.neutralBg, texto: LUME.neutralText, punto: LUME.neutralText, borde: LUME.border },
  danger: { fondo: LUME.dangerBg, texto: LUME.danger, punto: LUME.danger, borde: COMPLEMENTOS.bordeAlertaDanger },
};
export function variante(color: string | undefined): Variante {
  return VARIANTE[(color ?? 'lume').split('.')[0]!] ?? 'info';
}

/** Texto de ejemplo de los campos (placeholder): el apagado, que llega a 4,5:1 sobre blanco y sobre --lume-bg. */
export const PLACEHOLDER = APAGADO;

const SANS = '"Inter", "Segoe UI", system-ui, sans-serif';

export const tema = createTheme({
  primaryColor: 'lume',
  primaryShade: { light: TONO, dark: TONO },
  colors: PALETAS,
  black: LUME.text,
  fontFamily: SANS,
  // Las cifras usan números tabulares (estilos.css): sin una segunda familia
  fontFamilyMonospace: SANS,
  defaultRadius: 'md',
  radius: { xs: rem(4), sm: rem(6), md: rem(8), lg: rem(10), xl: rem(999) },
  shadows: { xs: LUME.shadow, sm: LUME.shadow, md: LUME.shadow, lg: LUME.shadow, xl: LUME.shadow },
  fontSizes: { xs: rem(12), sm: rem(13.5), md: rem(14), lg: rem(16), xl: rem(20) },
  headings: {
    fontFamily: SANS,
    fontWeight: '700',
    sizes: {
      h1: { fontSize: rem(22), lineHeight: '1.3' },
      h2: { fontSize: rem(17), lineHeight: '1.35' },
      h3: { fontSize: rem(15), lineHeight: '1.4' },
      h4: { fontSize: rem(14), lineHeight: '1.4' },
      h5: { fontSize: rem(13), lineHeight: '1.45' },
      h6: { fontSize: rem(12), lineHeight: '1.45' },
    },
  },
  components: {
    Card: Card.extend({ defaultProps: { radius: 'lg', withBorder: true, shadow: 'xs', padding: 'lg' } }),
    // Botones: primario (filled), secundario (default), outline, peligro (color red no relleno) y pequeño (size xs); estilos.css pone
    // los estados hover y deshabilitado
    Button: Button.extend({
      defaultProps: { radius: 'md' },
      vars: (_t, p) => {
        // Sin variante, Mantine dibuja el botón relleno (p. ej. «Eliminar» de un diálogo): ese se queda rojo relleno
        const peligro = p.color === 'red' && ['light', 'outline', 'subtle', 'default'].includes(p.variant ?? 'filled');
        const pequeno = p.size === 'xs' || p.size === 'compact-sm' || p.size === 'compact-xs';
        return {
          root: {
            '--button-fz': pequeno ? rem(12.5) : rem(13.5),
            '--button-padding-x': pequeno ? rem(14) : rem(18),
            ...(peligro
              ? { '--button-bg': LUME.white, '--button-color': LUME.danger, '--button-bd': `1px solid ${LUME.dangerBg}`, '--button-hover': LUME.dangerBg }
              : {}),
          },
        };
      },
      styles: { root: { fontWeight: 600 } },
    }),
    InputWrapper: InputWrapper.extend({
      styles: {
        // Apagado AA (#475569): hay filtros fuera de las cards, sobre --lume-bg, donde el #64748B no llega a 4,5:1
        label: { fontSize: rem(11), fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.04em', color: APAGADO, marginBottom: rem(4) },
        required: { color: LUME.danger },
        error: { fontSize: rem(11.5) },
        description: { color: APAGADO },
      },
    }),
    Input: Input.extend({
      defaultProps: { radius: 'md' },
      styles: { input: { fontSize: rem(13.5) } },
    }),
    Table: Table.extend({
      defaultProps: { verticalSpacing: rem(14), horizontalSpacing: rem(20) },
      vars: () => ({
        table: {
          '--table-border-color': LUME.border,
          '--table-striped-color': COMPLEMENTOS.encabezadoTabla,
          '--table-highlight-on-hover-color': COMPLEMENTOS.filaHover,
        },
      }),
      styles: {
        table: { fontSize: rem(13.5) },
        th: {
          backgroundColor: COMPLEMENTOS.encabezadoTabla,
          color: LUME.textMuted,
          fontSize: rem(11),
          fontWeight: 700,
          textTransform: 'uppercase',
          letterSpacing: '0.05em',
          paddingTop: rem(12),
          paddingBottom: rem(12),
        },
      },
    }),
    // Insignias en píldora con punto (estilos.css, .lume-insignia), en las 5 variantes de la especificación. No se recorta el texto
    Badge: Badge.extend({
      defaultProps: { variant: 'light', radius: 'xl' },
      classNames: { root: 'lume-insignia' },
      vars: (_t, p) => {
        if (p.variant && p.variant !== 'light') return { root: {} };
        const v = VARIANTES[variante(p.color)];
        return { root: { '--badge-bg': v.fondo, '--badge-color': v.texto, '--badge-dot-color': v.punto, '--badge-fz': rem(11.5) } as never };
      },
      styles: {
        root: { textTransform: 'none', fontWeight: 700, maxWidth: 'none', overflow: 'visible', paddingInline: rem(10) },
        label: { overflow: 'visible', textOverflow: 'clip' },
      },
    }),
    Alert: Alert.extend({
      defaultProps: { variant: 'light', radius: 'lg' },
      vars: (_t, p) => {
        if (p.variant && p.variant !== 'light') return { root: {} };
        const v = VARIANTES[variante(p.color)];
        return { root: { '--alert-bg': v.fondo, '--alert-color': v.texto, '--alert-bd': `1px solid ${v.borde}` } };
      },
      styles: {
        root: { padding: `${rem(14)} ${rem(16)}`, fontSize: rem(13), border: 'var(--alert-bd)' },
        title: { fontWeight: 700, fontSize: rem(13) },
        message: { fontSize: rem(13), color: LUME.text },
      },
    }),
    // Nombre accesible de los botones internos (U4: el recorrido los encontró sin nombre en Ingreso y Solicitudes)
    PasswordInput: PasswordInput.extend({ defaultProps: { visibilityToggleButtonProps: { 'aria-label': 'Mostrar u ocultar lo escrito' } } }),
    Select: Select.extend({ defaultProps: { clearButtonProps: { 'aria-label': 'Borrar la selección' } } }),
    MultiSelect: MultiSelect.extend({ defaultProps: { clearButtonProps: { 'aria-label': 'Borrar la selección' } } }),
    FileInput: FileInput.extend({ defaultProps: { clearButtonProps: { 'aria-label': 'Quitar el archivo' } } }),
    Tabs: Tabs.extend({ styles: { tab: { fontSize: rem(13.5), fontWeight: 600 } } }),
    Modal: Modal.extend({ defaultProps: { radius: 'lg', shadow: 'xs', overlayProps: { backgroundOpacity: 0.4 } } }),
    Drawer: Drawer.extend({ defaultProps: { position: 'right', overlayProps: { backgroundOpacity: 0.4 } } }),
    Pagination: Pagination.extend({ vars: () => ({ root: { '--pagination-control-size': rem(30), '--pagination-control-fz': rem(13) } }) }),
  },
});

/** Variables de Mantine que dependen de la paleta: texto apagado y de ejemplo en AA (ver APAGADO). */
export const variablesLume: CSSVariablesResolver = () => ({
  variables: {},
  light: {
    '--mantine-color-placeholder': PLACEHOLDER,
    '--mantine-color-dimmed': APAGADO,
    '--mantine-color-body': LUME.bg,
    '--mantine-color-text': LUME.text,
    '--mantine-color-default-border': LUME.border,
  },
  dark: {},
});
