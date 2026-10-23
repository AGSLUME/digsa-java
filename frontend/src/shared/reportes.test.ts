import { describe, expect, it } from 'vitest';
import {
  celda,
  colorVerificacion,
  conservarValores,
  cuerpoEnvio,
  type DefReporte,
  filasVerificacion,
  filtrarCatalogo,
  type GrupoCatalogo,
  parametros,
  sePuedeRegenerar,
  totalPaginas,
} from './reportes';

const lista = (clave: string, etiqueta: string, establecimiento = false) => ({ clave, etiqueta, tipo: 'lista' as const, establecimiento });

const detalle: DefReporte = {
  id: 'detalle',
  titulo: 'Detalle de visitas',
  descripcion: 'Una fila por visita',
  clase: 'operativo',
  modulo: 'visitas',
  periodo: 'fecha',
  maxDias: 1098,
  agrupaciones: [],
  filtros: [lista('punto_id', 'Lugar', true), lista('fuerza', 'Fuerza'), lista('estado', 'Estado')],
  columnas: ['Número'],
};
const proceso: DefReporte = {
  ...detalle,
  id: 'proceso',
  titulo: 'Consolidado por proceso',
  descripcion: 'Por componente',
  clase: 'general',
  agrupaciones: [{ clave: 'mes', nombre: 'Meses' }],
  filtros: [lista('fuerza', 'Fuerza'), lista('componente', 'Componente')],
};
const distribucion: DefReporte = { ...detalle, id: 'distribucion', titulo: 'Distribución auditor–punto', descripcion: 'Por auditor', filtros: [] };
const vacio = { desde: '2026-01-01', hasta: '2026-11-30', agrupacion: '', valores: {} };

describe('parámetros del reporte', () => {
  it('envía solo el periodo y los filtros con valor, una vez por opción elegida (2.11)', () => {
    expect(parametros(detalle, vacio).toString()).toBe('desde=2026-01-01&hasta=2026-11-30');
    expect(parametros(detalle, { ...vacio, valores: { punto_id: ['7', '9'], fuerza: ['EJC'], estado: [] } }).toString()).toBe(
      'desde=2026-01-01&hasta=2026-11-30&punto_id=7&punto_id=9&fuerza=EJC',
    );
  });

  it('cada reporte manda solo los filtros que declara: el componente solo en el consolidado por proceso', () => {
    const e = { ...vacio, valores: { componente: ['C08'], estado: ['cerrada'] } };
    expect(parametros(detalle, e).has('componente')).toBe(false);
    expect(parametros(proceso, e).getAll('componente')).toEqual(['C08']);
    expect(parametros(proceso, e).has('estado')).toBe(false);
  });

  it('la distribución auditor–punto solo lleva el periodo; la agrupación solo si el reporte la admite', () => {
    const e = { ...vacio, agrupacion: 'mes', valores: { punto_id: ['7'], fuerza: ['EJC'] } };
    expect(parametros(distribucion, e).toString()).toBe('desde=2026-01-01&hasta=2026-11-30');
    expect(parametros(proceso, e).get('agrupacion')).toBe('mes');
    expect(parametros(proceso, { ...e, agrupacion: 'hora' }).has('agrupacion')).toBe(false);
  });

  it('el filtro de texto va una sola vez y sin espacios; el preset no se envía (van las fechas resueltas)', () => {
    const logs: DefReporte = { ...distribucion, id: 'logs', filtros: [{ clave: 'texto', etiqueta: 'Texto', tipo: 'texto', establecimiento: false }] };
    expect(parametros(logs, { ...vacio, preset: 'hoy', valores: { texto: ['  clave  ', 'otra'] } }).toString()).toBe(
      'desde=2026-01-01&hasta=2026-11-30&texto=clave',
    );
    expect(parametros(logs, { ...vacio, valores: { texto: ['   '] } }).has('texto')).toBe(false);
  });

  it('el envío por correo lleva los filtros como listas', () => {
    expect(cuerpoEnvio(proceso, { ...vacio, agrupacion: 'mes', valores: { fuerza: ['EJC', 'ARC'] } }, 'a@example.com', 'pdf')).toEqual({
      para: 'a@example.com',
      formato: 'pdf',
      desde: '2026-01-01',
      hasta: '2026-11-30',
      agrupacion: 'mes',
      filtros: { fuerza: ['EJC', 'ARC'] },
    });
    expect(cuerpoEnvio(distribucion, vacio, 'a@example.com', 'xlsx')).not.toHaveProperty('filtros');
  });
});

describe('catálogo', () => {
  const grupos: GrupoCatalogo[] = [
    { clave: 'visitas', nombre: 'Visitas y hallazgos', reportes: [detalle, proceso] },
    {
      clave: 'seguridad',
      nombre: 'Usuarios y seguridad',
      reportes: [{ ...detalle, id: 'logs', titulo: 'Logs del sistema', descripcion: 'Bitácora' }],
    },
  ];

  it('busca sin tildes ni mayúsculas y oculta los grupos sin coincidencias', () => {
    expect(filtrarCatalogo(grupos, '')).toBe(grupos);
    const r = filtrarCatalogo(grupos, 'BITACORA');
    expect(r.map((g) => g.clave)).toEqual(['seguridad']);
    expect(filtrarCatalogo(grupos, 'componente')[0]?.reportes.map((x) => x.id)).toEqual(['proceso']);
    expect(filtrarCatalogo(grupos, 'hallazgos')[0]?.reportes).toHaveLength(2);
    expect(filtrarCatalogo(grupos, 'presupuesto')).toEqual([]);
  });

  it('al cambiar de reporte conserva los filtros que el nuevo también tiene', () => {
    expect(conservarValores(proceso, { punto_id: ['7'], fuerza: ['EJC'], componente: [] })).toEqual({ fuerza: ['EJC'] });
  });
});

describe('presentación', () => {
  it('números con coma decimal, vacíos en blanco y páginas de 100 filas', () => {
    expect(celda(66.7)).toBe('66,7');
    expect(celda(1200)).toBe('1200');
    expect(celda(null)).toBe('');
    expect(celda('Bogotá')).toBe('Bogotá');
    expect(totalPaginas(0)).toBe(1);
    expect(totalPaginas(100)).toBe(1);
    expect(totalPaginas(101)).toBe(2);
  });
});

describe('verificación', () => {
  const base = {
    codigo: 'RPT-20261022-ABCDEF',
    titulo: 'Detalle de visitas',
    generado: '2026-10-22 10:00:00',
    usuario: 'P****** (c****)',
    parametros: { desde: '2026-07-01', hasta: '2026-07-31', agrupacion: 'mes', filtros: { fuerza: ['ARC', 'EJC'] } },
    formato: 'csv',
    filas: 3,
    huellaDatos: 'a'.repeat(64),
    huellaArchivo: null,
    estado: 'lista',
  };

  it('colores: íntegro en verde, alterado en rojo, datos cambiados en amarillo', () => {
    expect(colorVerificacion('integro')).toBe('green');
    expect(colorVerificacion('sin_cambios')).toBe('green');
    expect(colorVerificacion('alterado')).toBe('red');
    expect(colorVerificacion('cambiaron')).toBe('yellow');
  });

  it('muestra el registro con el periodo, los filtros y las huellas', () => {
    const f = new Map(filasVerificacion({ resultado: 'integro', mensaje: 'ok', reporte: base }));
    expect(f.get('Periodo')).toBe('2026-07-01 a 2026-07-31 · agrupado por mes');
    expect(f.get('Filtros')).toBe('fuerza: ARC, EJC');
    expect(f.get('Formato')).toBe('CSV');
    expect(f.get('Huella del archivo (SHA-256)')).toBe('—');
    expect(
      new Map(filasVerificacion({ resultado: 'integro', mensaje: 'ok', reporte: { ...base, formato: 'pantalla', parametros: {} } })).get('Periodo'),
    ).toBe('Sin periodo');
    expect(filasVerificacion({ resultado: 'alterado', mensaje: 'x' })).toEqual([]);
  });
});

describe('verificación por estado', () => {
  it('fallida en rojo y en curso en amarillo; no se regenera sin huella ni sin terminar', () => {
    expect(colorVerificacion('fallida')).toBe('red');
    expect(colorVerificacion('en_curso')).toBe('yellow');
    const base = { codigo: 'c', titulo: 't', generado: 'g', usuario: 'u', parametros: {}, formato: 'csv', filas: 1, huellaArchivo: null };
    expect(sePuedeRegenerar(undefined)).toBe(true);
    expect(sePuedeRegenerar({ resultado: 'integro', mensaje: '', reporte: { ...base, huellaDatos: 'a', estado: 'lista' } })).toBe(true);
    expect(sePuedeRegenerar({ resultado: 'integro', mensaje: '', reporte: { ...base, huellaDatos: null, estado: 'vista' } })).toBe(false);
    expect(sePuedeRegenerar({ resultado: 'fallida', mensaje: '', reporte: { ...base, huellaDatos: null, estado: 'fallida' } })).toBe(false);
  });

  it('a otro usuario los filtros salen ocultos; la huella calculada del archivo subido se muestra', () => {
    const f = new Map(
      filasVerificacion({
        resultado: 'alterado',
        mensaje: 'x',
        huellaArchivo: 'b'.repeat(64),
      }),
    );
    expect(f.get('Huella del archivo subido (SHA-256)')).toBe('b'.repeat(64));
    const g = new Map(
      filasVerificacion({
        resultado: 'integro',
        mensaje: 'ok',
        reporte: {
          codigo: 'c',
          titulo: 't',
          generado: 'g',
          usuario: 'A. B.',
          parametros: { desde: '2026-01-01', hasta: '2026-01-31' },
          filtrosOcultos: true,
          formato: 'pdf',
          filas: 2,
          huellaDatos: 'a',
          huellaArchivo: 'b',
          estado: 'vencida',
        },
      }),
    );
    expect(g.get('Filtros')).toBe('Solo los ve quien generó el reporte');
    expect(g.get('Estado')).toContain('Vencido');
  });
});
