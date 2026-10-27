import { describe, expect, it } from 'vitest';
import { agrupar } from './menu';
import { iniciales, migasDe } from './Plantilla';

const grupos = agrupar([
  { ruta: 'agenda', titulo: 'Agenda de visitas', grupo: 'Visitas' },
  { ruta: 'calendario', titulo: 'Calendario de visitas', grupo: 'Visitas' },
  { ruta: 'usuarios', titulo: 'Usuarios', grupo: 'Configuración' },
]);

describe('migasDe (cabecera LUME)', () => {
  it('pantalla del menú: grupo y pantalla', () => {
    expect(migasDe('/usuarios', grupos)).toEqual(['Administración', 'Usuarios']);
    expect(migasDe('/', grupos)).toEqual(['Inicio']);
  });

  it('detalles que no están en el menú agregan su nivel', () => {
    expect(migasDe('/agenda/visita/15', grupos)).toEqual(['Auditoría', 'Agenda de visitas', 'Detalle de la visita']);
    expect(migasDe('/calendario/visita/3', grupos)).toEqual(['Auditoría', 'Calendario de visitas', 'Detalle de la visita']);
    expect(migasDe('/visita/15', grupos)).toEqual(['Auditoría', 'Visita']);
    expect(migasDe('/seguimiento', grupos)).toEqual(['Auditoría', 'Agenda de visitas', 'Seguimiento']);
  });

  it('una ruta desconocida no inventa migas', () => {
    expect(migasDe('/otra', grupos)).toEqual([]);
  });
});

describe('iniciales del avatar', () => {
  it('nombre y primer apellido, en mayúscula', () => {
    expect(iniciales('auditoria coordinador')).toBe('AC');
    expect(iniciales('María')).toBe('M');
    expect(iniciales('  ')).toBe('?');
  });
});
