import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  descargableDiferida,
  descargarODiferir,
  intervaloSondeoDiferidas,
  mensajeDiferida,
  mensajeErrorDescarga,
  msHastaProximoVencimiento,
  queDescarga,
  refrescarTrasError,
  rutaArchivoDiferido,
  textoAvance,
} from './descargasDiferidas';
import { ErrorApi } from '../shared/api';
import { conteo } from '../shared/formato';

const AHORA = Date.parse('2026-10-30T15:00:00Z');

describe('descarga diferida de analítica', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('sondea solo mientras haya una en cola o generándose', () => {
    expect(intervaloSondeoDiferidas([{ estado: 'pendiente' }])).toBe(3000);
    expect(intervaloSondeoDiferidas([{ estado: 'lista' }, { estado: 'generando' }])).toBe(3000);
    expect(intervaloSondeoDiferidas([{ estado: 'lista' }, { estado: 'fallida' }])).toBe(false);
    expect(intervaloSondeoDiferidas(undefined)).toBe(false);
  });

  it('solo se descarga la lista y sin vencer', () => {
    expect(descargableDiferida({ estado: 'lista', vence: '2026-10-31T15:00:00Z' }, AHORA)).toBe(true);
    expect(descargableDiferida({ estado: 'lista', vence: '2026-10-30T14:59:59Z' }, AHORA)).toBe(false);
    expect(descargableDiferida({ estado: 'generando', vence: null }, AHORA)).toBe(false);
    expect(descargableDiferida({ estado: 'fallida', vence: '2026-10-31T15:00:00Z' }, AHORA)).toBe(false);
  });

  it('calcula cuándo repintar la próxima lista que vence (sin sondeo)', () => {
    expect(
      msHastaProximoVencimiento(
        [
          { estado: 'lista', vence: '2026-10-30T16:00:00Z' },
          { estado: 'lista', vence: '2026-10-30T15:10:00Z' },
          { estado: 'lista', vence: '2026-10-30T14:00:00Z' },
          { estado: 'fallida', vence: '2026-10-30T15:01:00Z' },
        ],
        AHORA,
      ),
    ).toBe(10 * 60_000);
    expect(msHastaProximoVencimiento([{ estado: 'generando', vence: null }], AHORA)).toBeNull();
    expect(msHastaProximoVencimiento(undefined, AHORA)).toBeNull();
  });

  it('describe qué se descarga y el avance', () => {
    expect(queDescarga({ titulo: 'General de dispensación' })).toBe('Registros de origen · General de dispensación');
    expect(textoAvance({ estado: 'pendiente', registros: 150000, vence: null }, AHORA)).toBe(`En cola · ${conteo(150000)} registros`);
    expect(textoAvance({ estado: 'generando', registros: 150000, vence: null }, AHORA)).toBe(`Generando ${conteo(150000)} registros…`);
    expect(textoAvance({ estado: 'lista', registros: 1, vence: '2026-10-31T15:00:00Z' }, AHORA)).toBe('Lista para descargar');
    expect(textoAvance({ estado: 'lista', registros: 1, vence: '2026-10-29T15:00:00Z' }, AHORA)).toBe('Venció y se borró');
    expect(textoAvance({ estado: 'fallida', registros: 1, vence: null }, AHORA)).toBe('No se pudo generar');
    expect(rutaArchivoDiferido(15)).toBe('/api/analitica/descargas-diferidas/15/archivo');
    expect(mensajeDiferida({ mensaje: 'Del servidor', registros: 3 })).toBe('Del servidor');
    expect(mensajeDiferida({ registros: 120000 })).toContain('Mis descargas del día');
  });

  it('los errores de la descarga dan su mensaje (el del servidor si lo hay) y piden refrescar la lista', () => {
    expect(mensajeErrorDescarga(new ErrorApi(410, 'La descarga venció y su archivo se borró. Pídala de nuevo.'))).toContain('venció');
    expect(mensajeErrorDescarga(new ErrorApi(410, 'Error 410'))).toContain('venció');
    expect(mensajeErrorDescarga(new ErrorApi(409, 'Error 409'))).toContain('no está lista');
    expect(mensajeErrorDescarga(new ErrorApi(403, 'Error 403'))).toContain('permiso');
    expect(mensajeErrorDescarga(new Error('x'))).toBe('No se pudo descargar el archivo.');
    expect(refrescarTrasError(new ErrorApi(410, 'x'))).toBe(true);
    expect(refrescarTrasError(new ErrorApi(500, 'x'))).toBe(false);
    expect(refrescarTrasError(new Error('x'))).toBe(false);
  });

  it('una respuesta 202 (JSON) no se guarda como archivo: devuelve la descarga encolada', async () => {
    const cuerpo = { id: 7, estado: 'pendiente', registros: 150000, mensaje: 'En segundo plano' };
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(JSON.stringify(cuerpo), { status: 202, headers: { 'Content-Type': 'application/json' } })),
    );
    const crear = vi.fn(() => 'blob:prueba');
    URL.createObjectURL = crear;
    const d = await descargarODiferir('/api/analitica/traza/general/registros.csv?periodo=2025-01');
    expect(d?.id).toBe(7);
    expect(d?.mensaje).toBe('En segundo plano');
    expect(crear).not.toHaveBeenCalled();
  });

  it('un CSV en la petición se guarda con el nombre del servidor y devuelve null', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response('a;b\r\n', {
            status: 200,
            headers: { 'Content-Type': 'text/csv;charset=UTF-8', 'Content-Disposition': 'attachment; filename="registros_origen_general.csv"' },
          }),
      ),
    );
    URL.createObjectURL = vi.fn(() => 'blob:prueba');
    URL.revokeObjectURL = vi.fn();
    const clic = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    expect(await descargarODiferir('/api/analitica/traza/general/registros.csv?periodo=2025-02')).toBeNull();
    expect(clic).toHaveBeenCalledTimes(1);
  });
});
