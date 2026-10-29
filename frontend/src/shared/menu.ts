import {
  IconAlertTriangle,
  IconBell,
  IconBuildingHospital,
  IconCalendarEvent,
  IconCalendarTime,
  IconChartBar,
  IconChartLine,
  IconClipboardList,
  IconDatabaseExport,
  IconFileAnalytics,
  IconFileSearch,
  IconHome,
  IconListDetails,
  IconMap,
  IconMapPin,
  IconMessageReply,
  IconNotes,
  IconReportMoney,
  IconSettings,
  IconShieldCheck,
  IconUsers,
  type Icon,
} from '@tabler/icons-react';
import type { ItemMenu } from './sesion';

/**
 * Menú agrupado por módulos (fase U7, pedido del usuario; reemplaza los 4 grupos del mockup de UI-7), con ícono por pantalla y numeral
 * del Anexo 5, asignados aquí por ruta sin tocar `Pantallas.java`. El servidor sigue decidiendo QUÉ pantallas ve cada perfil (3.7); esto
 * solo dice DÓNDE y CÓMO se muestran. Un módulo sin pantallas para el perfil no se muestra. Una ruta que no esté aquí (una pantalla nueva
 * de otra sesión) sale en el módulo que corresponda al grupo del servidor (`GRUPO_DEL_SERVIDOR`), al final de él y con un ícono genérico.
 */
export type GrupoMenu = 'Visitas' | 'Interventoría y seguimiento presupuestal' | 'Reportes' | 'Seguridad' | 'Administración' | 'Mi cuenta';

export const ORDEN_GRUPOS: string[] = ['Visitas', 'Interventoría y seguimiento presupuestal', 'Reportes', 'Seguridad', 'Administración', 'Mi cuenta'];

/** «Mi cuenta» no viene del servidor: la arma la Plantilla con «Mi contraseña» al final del menú. */
export const GRUPO_CUENTA: GrupoMenu = 'Mi cuenta';

/**
 * Grupo del servidor (`Pantallas.java`) → módulo del menú, para las pantallas que aún no están en `PRESENTACION`: la analítica y las
 * cuentas médicas, si llegan con el grupo «Presupuesto», caen en «Interventoría y seguimiento presupuestal» sin cambiar este archivo.
 */
const GRUPO_DEL_SERVIDOR: Record<string, GrupoMenu> = {
  Visitas: 'Visitas',
  Presupuesto: 'Interventoría y seguimiento presupuestal',
  Configuración: 'Administración',
};

interface Presentacion {
  grupo: GrupoMenu;
  icono: Icon;
  /** Numeral del Anexo 5 (se muestra a la derecha, oculto para lectores de pantalla). */
  numeral?: string;
}

/** En el orden en que se muestran dentro de cada módulo. */
const PRESENTACION: Record<string, Presentacion> = {
  agenda: { grupo: 'Visitas', icono: IconCalendarEvent, numeral: '4.5' },
  propuesta: { grupo: 'Visitas', icono: IconListDetails, numeral: '4.5' },
  'mis-visitas': { grupo: 'Visitas', icono: IconMapPin, numeral: '4.5' },
  calendario: { grupo: 'Visitas', icono: IconCalendarTime, numeral: '4.5' },
  solicitudes: { grupo: 'Visitas', icono: IconNotes, numeral: '4.5' },
  hallazgos: { grupo: 'Visitas', icono: IconFileSearch, numeral: '4.5' },
  prorrogas: { grupo: 'Visitas', icono: IconClipboardList, numeral: '4.5' },
  opl: { grupo: 'Visitas', icono: IconMessageReply, numeral: '4.5' },
  presupuesto: { grupo: 'Interventoría y seguimiento presupuestal', icono: IconReportMoney, numeral: '4.6' },
  dashboard: { grupo: 'Reportes', icono: IconChartLine, numeral: '2.8' },
  riesgo: { grupo: 'Reportes', icono: IconAlertTriangle, numeral: '2.8' },
  mapas: { grupo: 'Reportes', icono: IconMap, numeral: '2.8' },
  reportes: { grupo: 'Reportes', icono: IconChartBar, numeral: '2.8' },
  usuarios: { grupo: 'Seguridad', icono: IconUsers, numeral: '3' },
  logs: { grupo: 'Seguridad', icono: IconShieldCheck, numeral: '3' },
  maestros: { grupo: 'Administración', icono: IconBuildingHospital },
  catalogos: { grupo: 'Administración', icono: IconSettings },
  alertas: { grupo: 'Administración', icono: IconBell },
  exportacion: { grupo: 'Administración', icono: IconDatabaseExport },
};

const ORDEN_RUTAS = Object.keys(PRESENTACION);

export interface EntradaMenu extends ItemMenu {
  grupoVisible: string;
  icono: Icon;
  numeral?: string;
}

export function presentar(m: ItemMenu): EntradaMenu {
  const p = PRESENTACION[m.ruta];
  const grupo = p?.grupo ?? (m.grupo ? (GRUPO_DEL_SERVIDOR[m.grupo] ?? m.grupo) : 'Administración');
  return { ...m, grupoVisible: grupo, icono: p?.icono ?? IconFileAnalytics, numeral: p?.numeral };
}

/**
 * Módulos en el orden de U7 (los grupos que no conoce, al final) con sus entradas en el orden de `PRESENTACION` (las que no conoce, al
 * final de su módulo y en el orden del servidor).
 */
export function agrupar(menu: ItemMenu[]): { grupo: string; entradas: EntradaMenu[] }[] {
  const entradas = menu
    .filter((m) => m.ruta !== 'inicio')
    .map((m, i) => ({ e: presentar(m), i }))
    .sort((a, b) => rango(ORDEN_RUTAS, a.e.ruta) - rango(ORDEN_RUTAS, b.e.ruta) || a.i - b.i)
    .map(({ e }) => e);
  const grupos = Array.from(new Set(entradas.map((e) => e.grupoVisible)));
  grupos.sort((a, b) => rango(ORDEN_GRUPOS, a) - rango(ORDEN_GRUPOS, b));
  return grupos.map((grupo) => ({ grupo, entradas: entradas.filter((e) => e.grupoVisible === grupo) }));
}

function rango(orden: string[], valor: string): number {
  const i = orden.indexOf(valor);
  return i < 0 ? orden.length : i;
}

export const ICONO_INICIO = IconHome;
