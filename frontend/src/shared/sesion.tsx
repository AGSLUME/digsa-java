import { createContext, useContext, type ReactNode } from 'react';

export interface ItemMenu {
  ruta: string;
  titulo: string;
  grupo: string;
}

export interface DatosSesion {
  id: number;
  usuario: string;
  nombre: string;
  perfiles: string[];
  menu: ItemMenu[];
  /** Todas las pantallas permitidas, también las que no salen en el menú. */
  pantallas?: string[];
  minutosInactividad: number;
}

const Contexto = createContext<DatosSesion | null>(null);

export function ProveedorSesion({ valor, children }: { valor: DatosSesion; children: ReactNode }) {
  return <Contexto.Provider value={valor}>{children}</Contexto.Provider>;
}

export function useSesion(): DatosSesion {
  const s = useContext(Contexto);
  if (!s) throw new Error('useSesion fuera de una sesión');
  return s;
}

export function puedeEntrar(s: DatosSesion, ruta: string): boolean {
  return ruta === 'inicio' || (s.pantallas ?? []).includes(ruta) || s.menu.some((m) => m.ruta === ruta);
}
