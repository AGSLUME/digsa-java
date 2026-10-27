import { describe, expect, it } from 'vitest';
import {
  CORREO,
  campoDelAjuste,
  errorClave,
  errorCodigoFuerza,
  errorCodigoObligacion,
  errorCodigoValor,
  errorCorreo,
  errorCorreos,
  errorLargo,
  errorNitOpl,
  erroresClaveNueva,
  erroresSolicitud,
  errorTexto10a800,
} from './validaciones';

// Validaciones del cliente: las mismas reglas del servidor (Anexo 5 num. 2.4)

describe('Correo', () => {
  it('el mismo patrón y largo de reglas.Correos.valido', () => {
    // Copia literal de Correos.VALIDO (backend/reglas)
    expect(CORREO.source).toBe(String.raw`^[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$`);
    expect(errorCorreo(' a.b@sanidad.mil.co ', 'malo')).toBeNull();
    expect(errorCorreo("o'neil+x@x.co", 'malo')).toBeNull();
    expect(errorCorreo('a@b.c', 'malo')).toBe('malo');
    expect(errorCorreo('a b@x.co', 'malo')).toBe('malo');
    expect(errorCorreo('ñandú@x.co', 'malo')).toBe('malo');
    expect(errorCorreo('a'.repeat(145) + '@x.co', 'malo')).toBeNull();
    expect(errorCorreo('a'.repeat(146) + '@x.co', 'malo')).toBe('malo');
    expect(errorCorreo('  ', 'malo')).toBeNull();
    expect(errorCorreo('', 'malo', 'vacío')).toBe('vacío');
  });
});

describe('Catálogos', () => {
  it('correos separados por coma: formato y máximo 10 distintos', () => {
    expect(errorCorreos('')).toBeNull();
    expect(errorCorreos('a@x.co, b.c@sanidad.mil.co;d@x.co')).toBeNull();
    expect(errorCorreos('a@x.co, malo@x')).toBe('El correo «malo@x» no es válido.');
    expect(errorCorreos('sin-arroba')).toBe('El correo «sin-arroba» no es válido.');
    const once = Array.from({ length: 11 }, (_, i) => `c${i}@x.co`).join(', ');
    expect(errorCorreos(once)).toBe('Máximo 10 correos.');
    // Repetidos (sin importar mayúsculas) cuentan una vez, como en el servidor
    expect(errorCorreos(Array.from({ length: 11 }, () => 'A@x.co').join(', '))).toBeNull();
  });

  it('código de fuerza: de 2 a 5 letras mayúsculas', () => {
    expect(errorCodigoFuerza('EJC')).toBeNull();
    expect(errorCodigoFuerza('')).toBe('Escriba el código.');
    expect(errorCodigoFuerza('E')).toMatch(/de 2 a 5 letras/);
    expect(errorCodigoFuerza('ABCDEF')).toMatch(/de 2 a 5 letras/);
  });

  it('código de un valor con el patrón del catálogo (todo el código, en mayúsculas, máximo 10)', () => {
    const doc = { codigo: 'tipo_documento', nombre: 'Tipos de documento', patron: '^[A-Z]{1,3}$' };
    expect(errorCodigoValor(doc, 'cc')).toBeNull();
    expect(errorCodigoValor(doc, '')).toBe('Escriba el código.');
    expect(errorCodigoValor(doc, 'ABCD')).toBe('El código «ABCD» no tiene el formato de Tipos de documento (de 1 a 3 letras mayúsculas, p. ej. C).');
    const fac = { codigo: 'tipo_factura', nombre: 'Tipos de factura', patron: '^[0-9]{1,3}$' };
    expect(errorCodigoValor(fac, 'A1')).toMatch(/\(de 1 a 3 dígitos, p\. ej\. 1\)\.$/);
    // Un patrón que el navegador no entiende lo decide el servidor
    expect(errorCodigoValor({ codigo: 'x', nombre: 'X', patron: '^\\p{Lu(' }, 'A')).toBeNull();
    expect(errorCodigoValor({ codigo: 'x', nombre: 'X', patron: '^.*$' }, 'ABCDEFGHIJK')).toBe('El código «ABCDEFGHIJK» no tiene el formato de X.');
  });

  it('largo de un texto obligatorio', () => {
    expect(errorLargo('  ', 2, 5, 'vacío', 'largo')).toBe('vacío');
    expect(errorLargo('a', 2, 5, 'vacío', 'largo')).toBe('largo');
    expect(errorLargo(' ab ', 2, 5, 'vacío', 'largo')).toBeNull();
  });
});

describe('Maestros', () => {
  it('NIT del OPL: de 6 a 12 dígitos y dígito de verificación opcional', () => {
    expect(errorNitOpl('')).toBeNull();
    expect(errorNitOpl('900123456')).toBeNull();
    expect(errorNitOpl('900123456-7')).toBeNull();
    expect(errorNitOpl('90012')).toMatch(/de 6 a 12 dígitos/);
    expect(errorNitOpl('900.123.456')).toMatch(/de 6 a 12 dígitos/);
  });

  it('código de obligación: OB- y de 1 a 20 letras, dígitos o guiones', () => {
    expect(errorCodigoObligacion('ob-opl-01')).toBeNull();
    expect(errorCodigoObligacion('OB-')).toBe('Escriba el código (p. ej. OB-OPL-01).');
    expect(errorCodigoObligacion('OPL-01')).toBe('El código de la obligación empieza por OB- (p. ej. OB-OPL-01).');
    expect(errorCodigoObligacion('OB-' + 'A'.repeat(21))).toMatch(/empieza por OB-/);
  });
});

describe('Solicitudes', () => {
  const hoy = '2026-10-23';
  it('punto, fecha del escrito (no futura y máximo 60 días atrás) y motivo de 10 a 4000 caracteres', () => {
    expect(erroresSolicitud('5', hoy, 'Quejas de usuarios.', hoy)).toEqual({ punto: null, fecha: null, motivo: null });
    const e = erroresSolicitud(null, '2026-10-24', 'corto', hoy);
    expect(e.punto).toBe('Elija el punto.');
    expect(e.fecha).toBe('La fecha de la solicitud es obligatoria y no puede ser futura.');
    expect(e.motivo).toBe('Describa el motivo de la solicitud (de 10 a 4000 caracteres).');
    expect(erroresSolicitud('5', '', 'Quejas de usuarios.', hoy).fecha).toMatch(/obligatoria/);
    expect(erroresSolicitud('5', '2026-08-24', 'Quejas de usuarios.', hoy).fecha).toBeNull();
    expect(erroresSolicitud('5', '2026-08-23', 'Quejas de usuarios.', hoy).fecha).toBe('La fecha de la solicitud no puede tener más de 60 días.');
  });
});

describe('Propuesta', () => {
  it('ubica el campo de la fila según el rechazo del servidor', () => {
    expect(campoDelAjuste('La fecha debe ser un día hábil (sin fines de semana ni festivos).')).toBe('fecha');
    expect(campoDelAjuste('Este punto exige un químico farmacéutico como auditor líder (Anexo 3 p.9).')).toBe('lider');
    expect(campoDelAjuste('El auditor líder debe estar activo con el perfil Auditor.')).toBe('lider');
    expect(campoDelAjuste('Máximo 3 auditores además del líder.')).toBe('otros');
    expect(campoDelAjuste('Todos los auditores del equipo deben estar activos y tener el perfil Auditor.')).toBe('otros');
    expect(campoDelAjuste('La propuesta dejó de ser borrador mientras tanto. Recargue.')).toBeNull();
  });
});

describe('Contraseña nueva (lo que dicen los textos de ayuda; mismos mensajes que reglas/Claves.java)', () => {
  it('mínimo 12 caracteres y sin ñ ni tildes; el resto lo decide el servidor', () => {
    expect(errorClave('')).toBe('La contraseña debe tener mínimo 12 caracteres.');
    expect(errorClave('Corta.2026')).toBe('La contraseña debe tener mínimo 12 caracteres.');
    expect(errorClave('Contraseña.Larga.2026')).toBe('La contraseña no puede tener ñ ni tildes.');
    expect(errorClave('Canción.Larga.2026')).toBe('La contraseña no puede tener ñ ni tildes.');
    expect(errorClave('Propia.Del.Usuario.2026')).toBeNull();
    // Sin número ni especial: pasa en el cliente (lo rechaza el servidor con su mensaje)
    expect(errorClave('abcdefghijklmn')).toBeNull();
  });

  it('la confirmación debe estar y coincidir', () => {
    expect(erroresClaveNueva('Propia.Del.Usuario.2026', '')).toEqual({ clave: null, confirmacion: 'Confirme la contraseña nueva.' });
    expect(erroresClaveNueva('Propia.Del.Usuario.2026', 'Otra.Del.Usuario.2026')).toEqual({
      clave: null,
      confirmacion: 'Las contraseñas no coinciden.',
    });
    expect(erroresClaveNueva('Propia.Del.Usuario.2026', 'Propia.Del.Usuario.2026')).toEqual({ clave: null, confirmacion: null });
  });
});

describe('Visita', () => {
  it('motivo o descripción de 10 a 800 caracteres', () => {
    expect(errorTexto10a800('  ', 'el motivo')).toBe('Escriba el motivo.');
    expect(errorTexto10a800('corto', 'la descripción')).toBe('La descripción debe tener entre 10 y 800 caracteres (lleva 5).');
    expect(errorTexto10a800('Motivo suficiente', 'el motivo')).toBeNull();
  });
});
