import { describe, expect, it } from 'vitest';
import {
  AVISO_TABLEROS,
  colorEstado,
  consultaCalidad,
  consultaTabla,
  datoFinalTexto,
  escribirEntradas,
  esConflicto,
  estadoDevolucion,
  ESTADOS,
  fechaCorta,
  fuenteTexto,
  leerEntradas,
  mensajeRevision,
  porcentaje,
  problemaCargue,
  problemaMotivo,
  problemaParametro,
  problemasCarga,
  sumaPorcentajes,
} from './homologacion';

describe('homologación: formato de los KPI de calidad (A5 2.4)', () => {
  it('porcentaje con dos decimales y coma; sin dato, guion', () => {
    expect(porcentaje(33.34)).toBe('33,34 %');
    expect(porcentaje(100)).toBe('100,00 %');
    expect(porcentaje(0)).toBe('0,00 %');
    expect(porcentaje(null)).toBe('—');
  });

  it('los seis estados del mockup en su orden, y la suma de los porcentajes cuadra en 100', () => {
    expect(ESTADOS.map((e) => e.codigo)).toEqual(['homologado', 'inconsistente', 'duplicado', 'sin_correspondencia', 'pendiente', 'no_valido']);
    expect(sumaPorcentajes([{ porcentaje: 33.34 }, { porcentaje: 33.33 }, { porcentaje: 33.33 }])).toBe(100);
    expect(colorEstado('homologado')).toBe('green');
    expect(colorEstado('otro')).toBe('gray');
  });
});

describe('homologación: aviso de la Revisión (duda AN.16)', () => {
  it('aprobar o devolver avisa que se verá en los tableros con el próximo cargue', () => {
    expect(AVISO_TABLEROS).toBe('Se verá en los tableros con el próximo cargue');
    expect(mensajeRevision('aprobar')).toBe('Homologación aprobada. Se verá en los tableros con el próximo cargue.');
    expect(mensajeRevision('devolver')).toBe('Registro devuelto al OPL. Se verá en los tableros con el próximo cargue.');
  });
});

describe('homologación: textos de la tabla maestra', () => {
  it('dato homologado como en el mockup y fuente con versión y fecha de corte', () => {
    expect(
      datoFinalTexto({
        nombreComercial: null,
        dci: 'Metformina',
        principioActivo: 'METFORMINA CLORHIDRATO',
        concentracion: '850 mg',
        forma: 'tableta',
        presentacion: 'caja x 30',
        unidad: null,
        cum: null,
        codigoSsfm: null,
        atc: 'A10BA02',
        grupoFarmacologico: 'A10B',
        registroSanitario: null,
      }),
    ).toBe('Metformina · 850 mg · tableta · caja x 30');
    expect(datoFinalTexto(null)).toBe('—');
    expect(
      fuenteTexto({ codigo: 'INVIMA_CUM_VIGENTES', nombre: 'CUM del INVIMA · vigentes', version: 3, fechaCorte: '2026-10-16', ejemplo: false }),
    ).toBe('CUM del INVIMA · vigentes · v3 · corte 16/10/2026');
    expect(fuenteTexto(null)).toBe('Sin fuente');
    expect(fechaCorta('2026-10-16')).toBe('16/10/2026');
  });

  it('consultas con solo los filtros puestos (y la página desde la 2)', () => {
    expect(consultaTabla({})).toBe('');
    expect(consultaTabla({ q: ' 850 ', estado: 'homologado', pagina: 1 })).toBe('?q=850&estado=homologado');
    expect(consultaTabla({ version: 4, pagina: 2, formato: 'csv' })).toBe('?version=4&pagina=2&formato=csv');
    expect(consultaCalidad({ cargue: '12', fuente: 'archivo', desde: '2025-01', hasta: '2025-03' })).toBe(
      '?cargue=12&fuente=archivo&desde=2025-01&hasta=2025-03',
    );
    expect(consultaCalidad({ cargue: 'abc' })).toBe('');
  });
});

describe('homologación: validaciones de la pantalla (los mismos mensajes del servidor)', () => {
  it('carga de una fuente: fecha de corte obligatoria y no futura, archivo con formato aceptado', () => {
    const csv = new File(['a'], 'cum.csv');
    expect(problemasCarga(csv, '', '2026-10-23')).toEqual(['Indique la fecha de actualización de la fuente.']);
    expect(problemasCarga(csv, '2026-10-24', '2026-10-23')).toEqual(['La fecha de actualización de la fuente no puede ser futura.']);
    expect(problemasCarga(null, '2026-10-16', '2026-10-23')).toEqual(['Adjunte el archivo de la fuente.']);
    expect(problemasCarga(new File(['a'], 'cum.pdf'), '2026-10-16', '2026-10-23')).toEqual(['Formato no aceptado: use CSV, TXT, xlsx, .gz o .zip.']);
    expect(problemasCarga(csv, '2026-10-16', '2026-10-23')).toEqual([]);
  });

  it('motivo de la devolución: obligatorio y de máximo 500 caracteres', () => {
    expect(problemaMotivo('  ')).toBe('Escriba el motivo de la devolución.');
    expect(problemaMotivo('x'.repeat(501))).toContain('500');
    expect(problemaMotivo('Concentración distinta')).toBeNull();
  });

  it('parámetros de las reglas: enteros en su rango', () => {
    expect(problemaParametro('tolerancia', '1')).toBeNull();
    expect(problemaParametro('tolerancia', '3')).toBe('Debe estar entre 0 y 2.');
    expect(problemaParametro('tolerancia', 'uno')).toContain('número entero');
    expect(problemaParametro('toleranciaPorcentaje', '50')).toBeNull();
    expect(problemaParametro('otro', '1')).toContain('desconocido');
  });

  it('catálogos: «ENTRADA ⇒ VALOR» por línea, forma con familia, sal sola, repetidos y vacíos', () => {
    const s = leerEntradas('sinonimo_dci', 'ACETAMINOFEN ⇒ PARACETAMOL\n\nALBUTEROL => SALBUTAMOL\nasa = ACIDO ACETILSALICILICO');
    expect(s.errores).toEqual([]);
    expect(s.entradas.map((e) => [e.clave, e.valor])).toEqual([
      ['ACETAMINOFEN', 'PARACETAMOL'],
      ['ALBUTEROL', 'SALBUTAMOL'],
      ['asa', 'ACIDO ACETILSALICILICO'],
    ]);
    const f = leerEntradas('forma', 'CAPSULA DURA ⇒ CAPSULA / SOLIDA ORAL');
    expect(f.entradas[0]).toEqual({ clave: 'CAPSULA DURA', valor: 'CAPSULA', extra: 'SOLIDA ORAL' });
    expect(escribirEntradas('forma', f.entradas)).toBe('CAPSULA DURA ⇒ CAPSULA / SOLIDA ORAL');
    expect(leerEntradas('sal', 'POTASICO\nSODICO').entradas.map((e) => e.clave)).toEqual(['POTASICO', 'SODICO']);
    expect(leerEntradas('sinonimo_dci', 'SIN FLECHA').errores[0]).toContain('Línea 1');
    expect(leerEntradas('sinonimo_dci', 'A ⇒ B\na ⇒ C').errores[0]).toContain('repetido');
    expect(leerEntradas('sal', '').errores).toEqual(['El catálogo debe tener al menos una entrada.']);
  });
});

describe('homologación: revisión A2 (seguimiento, filtros y conflictos)', () => {
  it('estados de las devoluciones con nombre legible y color (el texto acompaña al color)', () => {
    expect(estadoDevolucion('corregida')).toEqual({ texto: 'Corregida por el OPL', color: 'green' });
    expect(estadoDevolucion('resuelta').texto).toBe('Resuelta en revisión');
    expect(estadoDevolucion('persiste').color).toBe('red');
    expect(estadoDevolucion('raro')).toEqual({ texto: 'raro', color: 'gray' });
  });

  it('el filtro de cargue solo acepta números (con otro texto avisa en vez de mostrar todos)', () => {
    expect(problemaCargue('')).toBeNull();
    expect(problemaCargue(' 12 ')).toBeNull();
    expect(problemaCargue('12a')).toBe('Escriba solo el número del cargue.');
    expect(problemaCargue('#12')).not.toBeNull();
  });

  it('un 409 se reconoce como conflicto de edición concurrente', () => {
    expect(esConflicto({ estado: 409 })).toBe(true);
    expect(esConflicto({ estado: 422 })).toBe(false);
    expect(esConflicto(new Error('x'))).toBe(false);
    expect(esConflicto(null)).toBe(false);
  });
});
