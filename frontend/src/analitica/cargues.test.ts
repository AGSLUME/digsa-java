import { describe, expect, it } from 'vitest';
import {
  abierto,
  cifra,
  COLOR_ESTADO,
  consulta,
  fecha,
  fechaBitacora,
  fechaHora,
  formatoAceptado,
  INTERVALO_SONDEO_MS,
  intervaloSondeo,
  motivoTexto,
  NOMBRE_ESTADO,
  PARTE_BYTES,
  partes,
  periodo,
  periodoValido,
  problemasMapeo,
  problemasPrivacidad,
  rutaOriginal,
  rutaRechazos,
  rutaReemplazo,
  rutaVistaPrevia,
  segundos,
  terminado,
  type CampoModelo,
  type EstadoCargue,
} from './cargues';

const CAMPOS: CampoModelo[] = [
  { clave: 'paciente', etiqueta: 'Identificador del paciente', tipo: 'obligatorio', personal: true },
  { clave: 'especialidad', etiqueta: 'Especialidad', tipo: 'opcional' },
  { clave: 'fuerza', etiqueta: 'Fuerza', tipo: 'obligatorio' },
  { clave: 'esm', etiqueta: 'ESM', tipo: 'obligatorio' },
  { clave: 'modalidad', etiqueta: 'Modalidad', tipo: 'obligatorio' },
  { clave: 'fecha', etiqueta: 'Fecha de dispensación', tipo: 'obligatorio' },
  { clave: 'medicamento', etiqueta: 'Medicamento (descripción)', tipo: 'obligatorio' },
  { clave: 'cum', etiqueta: 'CUM', tipo: 'obligatorio' },
  { clave: 'cantidad', etiqueta: 'Cantidad', tipo: 'obligatorio' },
  { clave: 'valor', etiqueta: 'Valor total', enMensaje: 'valor', tipo: 'obligatorio' },
  { clave: 'descartar', etiqueta: 'Se descarta (dato personal)', tipo: 'descarte', personal: true },
  { clave: 'ignorar', etiqueta: 'No se usa', tipo: 'descarte' },
];

describe('partes de la subida', () => {
  it('parte el archivo en trozos de 50 MB y el último con el resto', () => {
    expect(partes(0)).toEqual([]);
    expect(partes(10)).toEqual([[0, 10]]);
    expect(partes(PARTE_BYTES)).toEqual([[0, PARTE_BYTES]]);
    const p = partes(700 * 1024 * 1024);
    expect(p).toHaveLength(14);
    expect(p[13]).toEqual([13 * PARTE_BYTES, 14 * PARTE_BYTES]);
    expect(partes(25, 10)).toEqual([
      [0, 10],
      [10, 20],
      [20, 25],
    ]);
  });

  it('formatos aceptados por extensión', () => {
    for (const n of ['insumo.csv', 'INSUMO.TXT', 'a.xlsx', 'a.csv.gz', 'a.zip']) expect(formatoAceptado(n)).toBe(true);
    for (const n of ['a.xls', 'a.xlsm', 'a.pdf', 'csv']) expect(formatoAceptado(n)).toBe(false);
  });
});

describe('estados', () => {
  it('cada estado tiene nombre y color; terminados y sondeo', () => {
    const estados: EstadoCargue[] = [
      'subiendo',
      'mapeo',
      'pendiente',
      'procesando',
      'aplicado',
      'rechazado',
      'fallido',
      'duplicado',
      'vencido',
      'descartado',
    ];
    for (const e of estados) {
      expect(NOMBRE_ESTADO[e]).toBeTruthy();
      expect(COLOR_ESTADO[e]).toBeTruthy();
    }
    expect(terminado({ estado: 'aplicado' })).toBe(true);
    expect(terminado({ estado: 'duplicado' })).toBe(true);
    expect(terminado({ estado: 'vencido' })).toBe(true);
    expect(terminado({ estado: 'procesando' })).toBe(false);
    expect(abierto({ estado: 'mapeo' })).toBe(true);
    expect(abierto({ estado: 'subiendo' })).toBe(true);
    expect(abierto({ estado: 'pendiente' })).toBe(false);
    expect(intervaloSondeo([{ estado: 'aplicado' }, { estado: 'pendiente' }])).toBe(INTERVALO_SONDEO_MS);
    expect(intervaloSondeo([{ estado: 'aplicado' }, { estado: 'mapeo' }])).toBe(false);
    expect(intervaloSondeo(undefined)).toBe(false);
  });
});

describe('formato de la bitácora (A5 2.4)', () => {
  it('cifras sin separador de miles; segundos con coma', () => {
    expect(cifra(2000000)).toBe('2000000');
    expect(cifra(null)).toBe('—');
    expect(segundos(12345)).toBe('12,35 s');
    expect(segundos(undefined)).toBe('—');
  });

  it('fecha y hora militar en hora de Bogotá; periodo procesado', () => {
    expect(fechaHora('2026-10-22T19:35:00Z')).toBe('22/10/2026 14:35');
    expect(fechaHora('2026-10-22T23:05:00-05:00')).toBe('22/10/2026 23:05');
    expect(fechaHora(null)).toBe('—');
    expect(fecha('2025-01-01')).toBe('01/01/2025');
    expect(periodo({ periodoDesde: '2025-01-01', periodoHasta: '2025-12-31' })).toBe('01/01/2025 – 31/12/2025');
    expect(periodo({ periodoDesde: null, periodoHasta: null })).toBe('—');
    expect(fechaBitacora({ aplicadoEn: null, terminado: null, recibido: '2026-10-22T19:35:00Z', creado: '2026-10-22T19:00:00Z' })).toBe(
      '22/10/2026 14:35',
    );
  });

  it('motivo agrupado con conteo; rechazo de estructura con su texto', () => {
    const motivos = [
      { causa: 'cum_invalido', texto: 'CUM con formato inválido', cantidad: 241 },
      { causa: 'fecha_invalida', texto: 'Fecha inválida', cantidad: 71 },
    ];
    expect(motivoTexto({ motivos, motivo: null, estado: 'aplicado' })).toBe('CUM con formato inválido (241), Fecha inválida (71)');
    expect(motivoTexto({ motivos: [], motivo: 'Número de campos distinto del encabezado (3; primera línea 17)', estado: 'rechazado' })).toBe(
      'Número de campos distinto del encabezado (3; primera línea 17)',
    );
    expect(motivoTexto({ motivos: [], motivo: null, estado: 'aplicado' })).toBe('');
  });
});

describe('mapeo de columnas', () => {
  it('completo: sin problemas; medicamento o CUM basta uno; descartes pueden repetirse', () => {
    const m = ['paciente', 'fuerza', 'esm', 'modalidad', 'fecha', 'cum', 'cantidad', 'valor', 'descartar', 'descartar', 'ignorar'];
    expect(problemasMapeo(m, CAMPOS)).toEqual([]);
  });

  it('falta cantidad, falta medicamento o CUM y repetidos', () => {
    expect(problemasMapeo(['paciente', 'fuerza', 'esm', 'modalidad', 'fecha', 'cum', 'valor'], CAMPOS)).toEqual([
      'Falta asignar la columna de cantidad',
    ]);
    expect(problemasMapeo(['paciente', 'fuerza', 'esm', 'modalidad', 'fecha', 'cantidad', 'valor'], CAMPOS)).toEqual([
      'Falta asignar la columna de medicamento o CUM',
    ]);
    expect(problemasMapeo(['paciente', 'fuerza', 'esm', 'modalidad', 'fecha', 'cum', 'cantidad', 'valor', 'valor'], CAMPOS)).toEqual([
      'La columna de valor está asignada dos veces',
    ]);
  });

  it('privacidad: una columna personal solo va a descartar, no se usa o un campo seudonimizado (mismo mensaje del servidor)', () => {
    const enc = ['NOMBRE_PACIENTE', 'DOC_PACIENTE', 'FZA'];
    const personales = [true, true, false];
    expect(problemasPrivacidad(enc, personales, ['especialidad', 'paciente', 'fuerza'], CAMPOS)).toEqual([
      'La columna «NOMBRE_PACIENTE» tiene datos personales: asígnela a «Se descarta (dato personal)», «No se usa» o a un campo que se seudonimiza',
    ]);
    expect(problemasPrivacidad(enc, personales, ['descartar', 'paciente', 'fuerza'], CAMPOS)).toEqual([]);
    expect(problemasPrivacidad(enc, personales, ['ignorar', 'paciente', 'fuerza'], CAMPOS)).toEqual([]);
  });

  it('periodo declarado: las dos fechas y en orden', () => {
    expect(periodoValido('2025-09-01', '2025-09-30')).toBe(true);
    expect(periodoValido('2025-09-30', '2025-09-01')).toBe(false);
    expect(periodoValido('2025-09-01', '')).toBe(false);
  });
});

describe('consultas y rutas', () => {
  it('filtros sin los vacíos y página desde la 2', () => {
    expect(consulta({})).toBe('');
    expect(consulta({ fuente: 'archivo', estado: null, desde: '2026-10-01', hasta: '', pagina: 1 })).toBe('?fuente=archivo&desde=2026-10-01');
    expect(consulta({ estado: 'aplicado', pagina: 3 })).toBe('?estado=aplicado&pagina=3');
    expect(rutaRechazos(17)).toBe('/api/analitica/cargues/17/rechazos.csv');
    expect(rutaOriginal(17)).toBe('/api/analitica/cargues/17/original');
    expect(rutaVistaPrevia(17, ['paciente', 'fuerza'])).toBe('/api/analitica/cargues/17/vista-previa?columnas=paciente%2Cfuerza');
    expect(rutaReemplazo(17, '2025-09-01', '2025-09-30')).toBe('/api/analitica/cargues/17/reemplazo?desde=2025-09-01&hasta=2025-09-30');
  });
});
