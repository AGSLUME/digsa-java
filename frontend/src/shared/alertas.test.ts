import { describe, expect, it } from 'vitest';
import {
  bandejaCuasiReal,
  cuerpoEdicion,
  ERROR_TELEFONO,
  errorTelefono,
  estadoEvento,
  latenciaLegible,
  nombrePerfil,
  normalizarTelefono,
  resumenCorreos,
  resumenSms,
  telefonoLegible,
} from './alertas';

describe('latencia legible', () => {
  it('sin dato es una raya', () => {
    expect(latenciaLegible(null)).toBe('—');
    expect(latenciaLegible(undefined)).toBe('—');
    expect(latenciaLegible(-1)).toBe('—');
    expect(latenciaLegible(Number.NaN)).toBe('—');
  });
  it('milisegundos bajo 100 ms', () => {
    expect(latenciaLegible(0)).toBe('0,00 ms');
    expect(latenciaLegible(42.4)).toBe('42,40 ms');
  });
  it('segundos con 2 decimales y coma decimal', () => {
    expect(latenciaLegible(500)).toBe('0,50 s');
    expect(latenciaLegible(1250)).toBe('1,25 s');
    expect(latenciaLegible(1255)).toBe('1,26 s');
    expect(latenciaLegible(59_000)).toBe('59,00 s');
    expect(latenciaLegible(59_994)).toBe('59,99 s');
    expect(latenciaLegible(59_996)).toBe('1 min');
  });
  it('minutos y horas', () => {
    expect(latenciaLegible(60_000)).toBe('1 min');
    expect(latenciaLegible(125_000)).toBe('2 min 5 s');
    expect(latenciaLegible(119_600)).toBe('2 min');
    expect(latenciaLegible(3_600_000)).toBe('1 h');
    expect(latenciaLegible(3_780_000)).toBe('1 h 3 min');
  });
});

describe('teléfono móvil colombiano (igual que reglas/Alertas.normalizarTelefono)', () => {
  it('acepta los formatos con indicativo y separadores', () => {
    expect(normalizarTelefono('3000000000')).toBe('+573000000000');
    expect(normalizarTelefono('+57 300 000 0000')).toBe('+573000000000');
    expect(normalizarTelefono(' +57 (300) 000-00.00 ')).toBe('+573000000000');
    expect(normalizarTelefono('0057 3000000000')).toBe('+573000000000');
    expect(normalizarTelefono('573000000000')).toBe('+573000000000');
  });
  it('rechaza fijos, otros países y largos distintos', () => {
    expect(normalizarTelefono(null)).toBeNull();
    expect(normalizarTelefono('')).toBeNull();
    expect(normalizarTelefono('6011234567')).toBeNull();
    expect(normalizarTelefono('+1 3000000000')).toBeNull();
    expect(normalizarTelefono('300000000')).toBeNull();
    expect(normalizarTelefono('30000000000')).toBeNull();
    expect(normalizarTelefono('57300000000')).toBeNull();
    expect(normalizarTelefono('300abc0000')).toBeNull();
  });
  it('el error del formulario: vacío y el valor guardado se admiten', () => {
    expect(errorTelefono('')).toBeNull();
    expect(errorTelefono('   ')).toBeNull();
    expect(errorTelefono('+57 300 000 0000')).toBeNull();
    expect(errorTelefono('12345')).toBe(ERROR_TELEFONO);
    expect(errorTelefono('601 000 0000', '601 000 0000')).toBeNull();
    expect(errorTelefono('601 000 0001', '601 000 0000')).toBe(ERROR_TELEFONO);
  });
  it('se muestra agrupado', () => {
    expect(telefonoLegible('3000000000')).toBe('+57 300 000 0000');
    expect(telefonoLegible('abc')).toBe('abc');
  });
});

describe('etiquetas de los eventos', () => {
  it('estado', () => {
    expect(estadoEvento({ abierta: true }).etiqueta).toBe('Abierta');
    expect(estadoEvento({ abierta: false }).etiqueta).toBe('Cerrada');
  });
  it('perfiles', () => {
    expect(nombrePerfil('COORDINADOR')).toBe('Coordinador');
    expect(nombrePerfil('OTRO')).toBe('OTRO');
  });
  it('correos', () => {
    expect(resumenCorreos({ correos: 0, correosEnviados: 0, correosFallidos: 0 })).toBe('Sin correos');
    expect(resumenCorreos({ correos: 3, correosEnviados: 2, correosFallidos: 1 })).toBe('2/3 enviados · 1 fallido');
    expect(resumenCorreos({ correos: 2, correosEnviados: 2, correosFallidos: null })).toBe('2/2 enviados');
  });
  it('SMS', () => {
    expect(resumenSms({ critica: false, smsEnviados: 0, smsPendientes: 0, smsSinEnviar: 0 })).toBe('No aplica');
    expect(resumenSms({ critica: true, smsEnviados: null, smsPendientes: null, smsSinEnviar: null })).toBe('Sin SMS');
    expect(resumenSms({ critica: true, smsEnviados: 1, smsPendientes: 2, smsSinEnviar: 1 })).toBe('1 enviado · 2 pendientes · 1 sin enviar');
  });
  it('cuasi real según el p95', () => {
    expect(bandejaCuasiReal({ latenciaP95Ms: null })).toBeNull();
    expect(bandejaCuasiReal({ latenciaP95Ms: 2_000 })).toBe(true);
    expect(bandejaCuasiReal({ latenciaP95Ms: 600_000 })).toBe(false);
  });
});

describe('edición del catálogo', () => {
  it('no manda SMS en una operativa y recorta el nombre', () => {
    const c = cuerpoEdicion({ tipo: 'x', nombre: '  Plan  ', critica: false, activa: true, sms: true, perfiles: ['COORDINADOR'], usuarios: [] });
    expect(c).toEqual({ tipo: 'x', nombre: 'Plan', activa: true, sms: false, perfiles: ['COORDINADOR'], usuarios: [] });
    expect(cuerpoEdicion({ tipo: 'y', nombre: 'Crítica', critica: true, activa: false, sms: true, perfiles: [], usuarios: [1] }).sms).toBe(true);
  });
});
