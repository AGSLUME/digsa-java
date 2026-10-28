import { describe, expect, it } from 'vitest';
import { esFalloDeCarga } from './LimiteErrores';

describe('esFalloDeCarga (división por rutas, U5)', () => {
  it('reconoce el archivo de una pantalla que no se pudo descargar en los tres motores', () => {
    expect(esFalloDeCarga(new TypeError('Failed to fetch dynamically imported module: /assets/Checklist-abc.js'))).toBe(true);
    expect(esFalloDeCarga(new TypeError('error loading dynamically imported module'))).toBe(true);
    expect(esFalloDeCarga(new TypeError('Importing a module script failed.'))).toBe(true);
  });

  it('un error de la pantalla no es un fallo de carga', () => {
    expect(esFalloDeCarga(new Error('Cannot read properties of undefined'))).toBe(false);
    expect(esFalloDeCarga(null)).toBe(false);
  });
});
