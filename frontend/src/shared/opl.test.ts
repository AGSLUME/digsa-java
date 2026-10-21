import { describe, expect, it } from 'vitest';
import { ErrorApi } from './api';
import {
  conAvisoFallido,
  editaQuienComo,
  errorFirmaPlan,
  errorRespuesta,
  faltanQuienComo,
  pideReabrir,
  pideRefrescar,
  pideReverificar,
  planFirmado,
  puedeResponder,
  puedeSubirSoportes,
  quienComoCambiados,
  tokenDelEnlace,
  vaAlPlan,
  type DetalleOpl,
  type HallazgoOpl,
} from './opl';

function hallazgo(codigo: string, extra: Partial<HallazgoOpl> = {}): HallazgoOpl {
  return {
    clave: 'it:' + codigo,
    codigo,
    origen: 'item',
    texto: 'Punto',
    hallazgo: 'Sin registro',
    planAuditor: { causas: null, que: 'Control diario', porque: null, donde: null, inicio: null, fin: '2026-11-30', indicador: null, estado: 'Pendiente' },
    estado: 'sin_respuesta',
    decision: null,
    justificacion: null,
    quien: null,
    como: null,
    respondio: null,
    canal: null,
    resolucion: null,
    resolucionMotivo: null,
    soportes: [],
    ...extra,
  };
}

function detalle(extra: Partial<DetalleOpl> = {}): DetalleOpl {
  return {
    visitaId: 1,
    numero: 'VS-1',
    punto: 'ESM',
    fase: 'objeciones',
    enviado: '2026-10-08 16:30',
    venceObjeciones: '2026-10-14 23:59',
    vencePlan: '2026-10-16 23:59',
    venceObjecionesLargo: 'mié 14 de oct de 2026',
    vencePlanLargo: 'vie 16 de oct de 2026',
    abierta: true,
    hallazgos: [],
    faltanPlan: [],
    plan: { estado: 'pendiente' },
    actaArchivoId: 9,
    ...extra,
  };
}

describe('respuesta del OPL', () => {
  it('van al plan los aceptados (también los automáticos) y los ratificados', () => {
    expect(vaAlPlan('aceptado')).toBe(true);
    expect(vaAlPlan('aceptado_auto')).toBe(true);
    expect(vaAlPlan('ratificado')).toBe(true);
    expect(vaAlPlan('objetado')).toBe(false);
    expect(vaAlPlan('levantado')).toBe(false);
    expect(vaAlPlan('sin_respuesta')).toBe(false);
  });

  it('responde solo en la fase de objeciones, sin plan firmado y sin decisión del líder', () => {
    const h = hallazgo('HB-01');
    expect(puedeResponder(detalle(), h)).toBe(true);
    expect(puedeResponder(detalle({ fase: 'plan' }), h)).toBe(false);
    expect(puedeResponder(detalle({ plan: { estado: 'firmado' } }), h)).toBe(false);
    expect(puedeResponder(detalle({ abierta: false }), h)).toBe(false);
    expect(puedeResponder(detalle(), hallazgo('HB-01', { estado: 'ratificado', resolucion: 'ratifica' }))).toBe(false);
  });

  it('los soportes acompañan una objeción pendiente, hasta 10', () => {
    const objetado = hallazgo('HB-01', { estado: 'objetado', decision: 'objeta' });
    expect(puedeSubirSoportes(detalle(), objetado)).toBe(true);
    expect(puedeSubirSoportes(detalle(), hallazgo('HB-01', { estado: 'aceptado' }))).toBe(false);
    const diez = Array.from({ length: 10 }, (_, i) => ({ id: i, nombre: 'a.pdf', tipo: 'application/pdf', tamano: 1, sha256: '', subido: null }));
    expect(puedeSubirSoportes(detalle(), { ...objetado, soportes: diez })).toBe(false);
  });

  it('quién y cómo: con el plan firmado solo se completa lo vacío; vencido no se edita', () => {
    const aceptado = hallazgo('HB-01', { estado: 'aceptado' });
    expect(editaQuienComo(detalle({ fase: 'plan' }), aceptado)).toBe(true);
    expect(editaQuienComo(detalle({ fase: 'vencido' }), aceptado)).toBe(false);
    expect(editaQuienComo(detalle(), hallazgo('HB-01', { estado: 'objetado' }))).toBe(false);
    const firmado = detalle({ plan: { estado: 'firmado', firmado: '2026-10-15T10:00:00' } });
    expect(editaQuienComo(firmado, aceptado)).toBe(true);
    expect(editaQuienComo(firmado, { ...aceptado, quien: 'Regente', como: 'Registro diario' })).toBe(false);
    expect(planFirmado({ estado: 'cerrado', firmado: null })).toBe(false);
    expect(planFirmado({ estado: 'cerrado', firmado: '2026-10-15T10:00:00' })).toBe(true);
  });

  it('valida la respuesta y la firma con los límites del servidor', () => {
    expect(errorRespuesta('acepta', '', 'Ana', 'Jefe')).toBeNull();
    expect(errorRespuesta('acepta', '', 'An', 'Jefe')).toMatch(/nombre/);
    expect(errorRespuesta(null, '', 'Ana', 'Jefe')).toMatch(/acepta u objeta/);
    expect(errorRespuesta('objeta', 'corta', 'Ana', 'Jefe')).toMatch(/justificación/);
    expect(errorRespuesta('objeta', 'El registro sí existe en el sistema.', 'Ana', 'Jefe')).toBeNull();
    expect(errorFirmaPlan('Ana Pérez', 'Gerente', '1020304050', 'data:image/png;base64,x')).toBeNull();
    expect(errorFirmaPlan('Ana Pérez', 'Gerente', '12', 'data:image/png;base64,x')).toMatch(/documento/);
    expect(errorFirmaPlan('Ana Pérez', 'Gerente', '1020304050', null)).toMatch(/firma/);
  });

  it('quién y cómo: envía solo lo cambiado y cuenta lo que falta con lo escrito', () => {
    const hs = [
      hallazgo('HB-01', { estado: 'aceptado' }),
      hallazgo('HB-02', { estado: 'ratificado', quien: 'Regente', como: 'Registro diario' }),
      hallazgo('HB-03', { estado: 'objetado' }),
    ];
    const ed = {
      'it:HB-01': { quien: ' Jefe de bodega ', como: 'Termohigrómetro' },
      'it:HB-02': { quien: 'Regente', como: 'Registro diario ' },
      'it:HB-03': { quien: 'No va', como: 'No va' },
    };
    expect(quienComoCambiados(hs, ed)).toEqual([{ clave: 'it:HB-01', quien: 'Jefe de bodega', como: 'Termohigrómetro' }]);
    expect(faltanQuienComo(hs, {})).toEqual(['HB-01']);
    expect(faltanQuienComo(hs, ed)).toEqual([]);
  });

  it('reconoce el 403 que pide el código otra vez y los avisos fallidos', () => {
    expect(pideReverificar(new ErrorApi(403, 'Pida un código nuevo', { reverificar: true }))).toBe(true);
    expect(pideReverificar(new ErrorApi(403, 'Sin permiso'))).toBe(false);
    expect(pideReverificar(new Error('x'))).toBe(false);
    // Sesión del QR vencida: se reabre el enlace y se verifica otra vez sin perder lo escrito
    expect(pideReabrir(new ErrorApi(401, 'Abra de nuevo el enlace del acta.', { motivo: 'enlace' }))).toBe(true);
    expect(pideReabrir(new ErrorApi(401, 'Sesión cerrada', { motivo: 'inactividad' }))).toBe(false);
    expect(pideRefrescar(new ErrorApi(422, 'Venció el plazo'))).toBe(true);
    expect(pideRefrescar(new ErrorApi(409, 'Ya firmado'))).toBe(true);
    expect(pideRefrescar(new ErrorApi(500, 'Error'))).toBe(false);
    expect(conAvisoFallido('Objeción registrada. Aviso al auditor líder fallido.')).toBe(true);
    expect(conAvisoFallido('Hallazgo aceptado.')).toBe(false);
  });

  it('el token del enlace sale de la dirección y queda en la pestaña para recargar sin él', () => {
    const datos = new Map<string, string>();
    const almacen = { getItem: (k: string) => datos.get(k) ?? null, setItem: (k: string, v: string) => void datos.set(k, v) };
    expect(tokenDelEnlace('a'.repeat(64), almacen, 'k')).toBe('a'.repeat(64));
    expect(tokenDelEnlace(null, almacen, 'k')).toBe('a'.repeat(64));
    expect(tokenDelEnlace(null, null, 'k')).toBe('');
    const roto = {
      getItem: () => {
        throw new Error('bloqueado');
      },
      setItem: () => {
        throw new Error('bloqueado');
      },
    };
    expect(tokenDelEnlace('b'.repeat(64), roto, 'k')).toBe('b'.repeat(64));
    expect(tokenDelEnlace(null, roto, 'k')).toBe('');
  });
});
