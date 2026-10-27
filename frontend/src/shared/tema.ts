import { Badge, createTheme, rem, Table, type CSSVariablesResolver, type MantineColorsTuple } from '@mantine/core';

/**
 * Tokens del mockup aprobado (MOCKUP/Mockup_Modulos_DIGSA.html, `:root`): fuente única de los colores de la interfaz. Llegan al CSS como
 * variables `--digsa-*` (ver `variablesDigsa`) y a Mantine como paletas.
 */
export const TOKENS = {
  fondo: '#F3F5F2',
  superficie: '#FFFFFF',
  tinta: '#17241D',
  apagado: '#56635B',
  linea: '#DCE2DD',
  suave: '#EEF2EE',
  marca: '#1C4632',
  marca2: '#2E6A4B',
  marcaTinta: '#F2F5F1',
  activo: '#E1ECE5',
  encabezadoTabla: '#3B4740',
  /** Borde de los campos: 3,5:1 sobre blanco (WCAG 1.4.11); el #C9D1CB del mockup no llega a 3:1. */
  bordeCampo: '#7F8C83',
  errorCampoFondo: '#FFF8F7',
  crit: '#A1281E',
  critFondo: '#FBEBE9',
  adv: '#8A520F',
  advFondo: '#FCF1E2',
  info: '#245683',
  infoFondo: '#E8F0F8',
  ok: '#226240',
  okFondo: '#E5F2EA',
} as const;

/**
 * Tono de cada paleta que usa Mantine para el texto, el relleno y los fondos suaves (`primaryShade`). Las paletas ponen ahí el token del
 * mockup: crit, adv, info y ok llegan a AA sobre blanco y sobre su fondo suave.
 */
export const TONO = 8;

// Índice 8 = token del mockup; 0 = fondo suave; los intermedios, escalones hacia el 8 (bordes, barras, gráficos).
export const PALETAS: Record<string, MantineColorsTuple> = {
  digsa: ['#EEF5F0', '#E1ECE5', '#C3D8CB', '#A2C2AE', '#7EAA8F', '#5E8A72', '#417A5B', '#2E6A4B', '#1C4632', '#12301F'],
  // crit
  red: ['#FBEBE9', '#F6D5D1', '#ECA9A1', '#E27C70', '#D85547', '#CC3B2C', '#B83124', '#A92C21', '#A1281E', '#7E1F17'],
  // adv
  orange: ['#FCF1E2', '#F7E0C2', '#EEC184', '#E4A14A', '#D7861F', '#C27A1F', '#A86812', '#985C10', '#8A520F', '#6B3F0B'],
  // ocre: distinto del naranja porque hay estados que solo se distinguen por él (reprogramada frente a en ejecución en el calendario)
  yellow: ['#FBF5DC', '#F5E9B3', '#EBD677', '#DFC23F', '#CFAE17', '#B89A00', '#9C8300', '#836E00', '#6F5A00', '#544400'],
  // ok
  green: ['#E5F2EA', '#CBE5D5', '#9BCBAE', '#6AB087', '#43986A', '#2F8456', '#287249', '#246944', '#226240', '#174A30'],
  // info
  blue: ['#E8F0F8', '#CFDFEF', '#A0BFDF', '#6E9DCD', '#4A82BE', '#3570AD', '#2B6398', '#275C8D', '#245683', '#1A4064'],
  // neutro verdoso: 0 fondo, 1 suave, 3 línea, 4 borde por omisión, 6 texto apagado (`dimmed`), 8 encabezado de tabla, 9 tinta
  gray: ['#F3F5F2', '#EEF2EE', '#E4E9E5', '#DCE2DD', '#C9D1CB', '#AEB9B1', '#56635B', '#4A564F', '#3B4740', '#17241D'],
};

/** Texto de ejemplo de los campos (placeholder). */
export const PLACEHOLDER = '#66726A';

const SANS = '"IBM Plex Sans", system-ui, "Segoe UI", Roboto, Helvetica, Arial, sans-serif';
const MONO = '"IBM Plex Mono", ui-monospace, Consolas, monospace';

export const tema = createTheme({
  primaryColor: 'digsa',
  primaryShade: { light: TONO, dark: TONO },
  colors: PALETAS,
  black: TOKENS.tinta,
  fontFamily: SANS,
  fontFamilyMonospace: MONO,
  defaultRadius: 'md',
  fontSizes: { xs: rem(12), sm: rem(14), md: rem(14), lg: rem(16), xl: rem(20) },
  headings: {
    fontFamily: SANS,
    fontWeight: '600',
    sizes: {
      h1: { fontSize: rem(24), lineHeight: '1.25' },
      h2: { fontSize: rem(18), lineHeight: '1.3' },
      h3: { fontSize: rem(16), lineHeight: '1.35' },
      h4: { fontSize: rem(15), lineHeight: '1.4' },
      h5: { fontSize: rem(14), lineHeight: '1.45' },
      h6: { fontSize: rem(13), lineHeight: '1.45' },
    },
  },
  components: {
    // Insignias suaves como las del mockup, sin mayúsculas forzadas y sin recortar el texto (en la agenda se leía «CERRA…»)
    Badge: Badge.extend({
      defaultProps: { variant: 'light', radius: 'xl' },
      styles: {
        root: { textTransform: 'none', fontWeight: 500, maxWidth: 'none', overflow: 'visible' },
        label: { overflow: 'visible', textOverflow: 'clip' },
      },
    }),
    Table: Table.extend({
      vars: () => ({
        table: {
          '--table-border-color': TOKENS.linea,
          '--table-striped-color': '#FAFBFA',
          '--table-highlight-on-hover-color': TOKENS.suave,
        },
      }),
      styles: { th: { backgroundColor: TOKENS.suave, color: TOKENS.encabezadoTabla, fontWeight: 600 } },
    }),
  },
});

/** Variables CSS `--digsa-*` con los tokens (para estilos.css y estilos en línea). */
export const variablesDigsa: CSSVariablesResolver = () => {
  const variables: Record<string, string> = {};
  for (const [nombre, valor] of Object.entries(TOKENS)) {
    variables[`--digsa-${nombre.replace(/[A-Z]/g, (l) => '-' + l.toLowerCase())}`] = valor;
  }
  // El texto de ejemplo dice a veces el filtro activo («Todas»): 5:1 en lugar del gris claro de Mantine (2:1)
  return { variables, light: { '--mantine-color-placeholder': PLACEHOLDER }, dark: {} };
};
