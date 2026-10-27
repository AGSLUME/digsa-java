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
 * Menú del mockup aprobado (decisión UI-7): 4 grupos con ícono por módulo y numeral del Anexo 5, asignados aquí por ruta sin tocar
 * `Pantallas.java`. El servidor sigue decidiendo QUÉ pantallas ve cada perfil (3.7); esto solo dice DÓNDE y CÓMO se muestran. Una
 * ruta que no esté aquí (una pantalla nueva de otra sesión) sale en el grupo que mande el servidor, con un ícono genérico.
 */
export type GrupoMenu = 'General' | 'Auditoría' | 'Información' | 'Administración';

export const ORDEN_GRUPOS: string[] = ['General', 'Auditoría', 'Información', 'Administración'];

interface Presentacion {
  grupo: GrupoMenu;
  icono: Icon;
  /** Numeral del Anexo 5 (se muestra a la derecha, oculto para lectores de pantalla). */
  numeral?: string;
}

const PRESENTACION: Record<string, Presentacion> = {
  inicio: { grupo: 'General', icono: IconHome },
  agenda: { grupo: 'Auditoría', icono: IconCalendarEvent, numeral: '4.5' },
  propuesta: { grupo: 'Auditoría', icono: IconListDetails, numeral: '4.5' },
  'mis-visitas': { grupo: 'Auditoría', icono: IconMapPin, numeral: '4.5' },
  calendario: { grupo: 'Auditoría', icono: IconCalendarTime, numeral: '4.5' },
  solicitudes: { grupo: 'Auditoría', icono: IconNotes, numeral: '4.5' },
  prorrogas: { grupo: 'Auditoría', icono: IconClipboardList, numeral: '4.5' },
  hallazgos: { grupo: 'Auditoría', icono: IconFileSearch, numeral: '4.5' },
  opl: { grupo: 'Auditoría', icono: IconMessageReply, numeral: '4.5' },
  presupuesto: { grupo: 'Auditoría', icono: IconReportMoney, numeral: '4.6' },
  riesgo: { grupo: 'Información', icono: IconAlertTriangle, numeral: '2.8' },
  dashboard: { grupo: 'Información', icono: IconChartLine, numeral: '2.8' },
  reportes: { grupo: 'Información', icono: IconChartBar, numeral: '2.8' },
  maestros: { grupo: 'Administración', icono: IconBuildingHospital },
  catalogos: { grupo: 'Administración', icono: IconSettings },
  alertas: { grupo: 'Administración', icono: IconBell },
  usuarios: { grupo: 'Administración', icono: IconUsers, numeral: '3' },
  exportacion: { grupo: 'Administración', icono: IconDatabaseExport },
  logs: { grupo: 'Administración', icono: IconShieldCheck, numeral: '3' },
};

export interface EntradaMenu extends ItemMenu {
  grupoVisible: string;
  icono: Icon;
  numeral?: string;
}

export function presentar(m: ItemMenu): EntradaMenu {
  const p = PRESENTACION[m.ruta];
  return { ...m, grupoVisible: p?.grupo ?? m.grupo ?? 'General', icono: p?.icono ?? IconFileAnalytics, numeral: p?.numeral };
}

/** Grupos en el orden del mockup (los grupos que no conoce, al final) con sus entradas en el orden del servidor. */
export function agrupar(menu: ItemMenu[]): { grupo: string; entradas: EntradaMenu[] }[] {
  const entradas = menu.filter((m) => m.ruta !== 'inicio').map(presentar);
  const grupos = Array.from(new Set(entradas.map((e) => e.grupoVisible)));
  grupos.sort((a, b) => rango(a) - rango(b));
  return grupos.map((grupo) => ({ grupo, entradas: entradas.filter((e) => e.grupoVisible === grupo) }));
}

function rango(g: string): number {
  const i = ORDEN_GRUPOS.indexOf(g);
  return i < 0 ? ORDEN_GRUPOS.length : i;
}

export const ICONO_INICIO = IconHome;
