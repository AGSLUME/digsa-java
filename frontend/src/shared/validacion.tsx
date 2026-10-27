import { Alert, List } from '@mantine/core';
import { useCallback, useEffect, useRef, useState, type ReactNode, type RefObject } from 'react';
import { sonarError } from './alertaSonora';
import { ErrorApi } from './api';

/**
 * Aviso estándar ante el mal diligenciamiento de campos (Anexo 5 num. 2.4), igual en todos los formularios:
 * 1. la validación del formulario pone el mensaje junto al campo (prop `error` de Mantine: borde y fondo de error, `aria-invalid` y
 *    `aria-describedby`);
 * 2. un resumen con `role="alert"` (lo anuncia el lector de pantalla) dice cuáles campos revisar, o trae el error del servidor;
 * 3. el foco va al primer campo con error (o al resumen si el error no es de un campo);
 * 4. suena el aviso corto (si el usuario no lo apagó).
 * Cada mensaje se muestra una sola vez: el de la validación del formulario junto al campo (el resumen solo nombra los campos) y el del
 * servidor en el resumen (el campo que el servidor señala queda marcado, sin repetir el texto).
 */

/** Mensaje por campo; un valor vacío o null es «sin error». */
export type Errores = Record<string, string | null | undefined>;

export interface Validacion {
  /** Contenedor del formulario: el foco busca dentro de él el primer campo con error. */
  ref: RefObject<HTMLFormElement & HTMLDivElement>;
  /** Valor para la prop `error` del campo: el mensaje, true si solo se marca (error del servidor) o undefined. */
  error: (campo: string) => string | true | undefined;
  /** Revisa los errores: si hay alguno los muestra, suena y lleva el foco; devuelve true si no hay errores. */
  validar: (errores: Errores) => boolean;
  /** Error de una petición: el mensaje va al resumen y, si el servidor dice el campo (`campo` del 422), el campo queda marcado. */
  errorServidor: (e: unknown, texto: string) => void;
  /** Aviso que no es de un campo (p. ej. «falta la firma»): va al resumen, suena y lleva el foco al resumen. */
  alerta: (mensaje: string) => void;
  /** Quita el error de un campo (al corregirlo). */
  limpiar: (campo: string) => void;
  /** Quita todos los errores (al cerrar o reiniciar el formulario o al guardar bien). */
  reiniciar: () => void;
  /** Hay errores a la vista. */
  hayErrores: boolean;
  /** Resumen para poner al inicio del formulario (o junto al botón de enviar). */
  resumen: ReactNode;
}

export interface OpcionesValidacion {
  /** Nombre visible de cada campo para el resumen («Teléfono»); sin etiqueta se usa el nombre del campo. */
  etiquetas?: Record<string, string>;
  /** Campo del servidor (nombre en el cuerpo de la petición) → campo del formulario, cuando difieren. */
  campos?: Record<string, string>;
  /** data-testid del resumen; cada pantalla conserva el que ya usan sus pruebas (p. ej. «error-usuario»). */
  testid?: string;
}

const ENFOCABLE =
  'input:not([type="hidden"]):not(:disabled), textarea:not(:disabled), select:not(:disabled), button:not(:disabled), [tabindex]:not([tabindex="-1"])';

function aLaVista(el: HTMLElement): boolean {
  // Un campo dentro de un panel plegado (inert) o de la vista que no se muestra (tabla o tarjetas) no puede recibir el foco
  if (el.closest('[inert], [aria-hidden="true"]')) return false;
  return (el as HTMLElement & { checkVisibility?: () => boolean }).checkVisibility?.() !== false;
}

/**
 * Primer campo con error que se puede enfocar, en el orden de la pantalla: un campo marcado (`aria-invalid`) o el primer control del
 * grupo cuyo mensaje de error se muestra (Mantine no pone `aria-invalid` en Radio.Group ni Checkbox.Group; su error tiene id «…-error»).
 */
export function primerCampoConError(raiz: HTMLElement): HTMLElement | null {
  for (const marca of raiz.querySelectorAll<HTMLElement>('[aria-invalid="true"], [id$="-error"]')) {
    // Un campo marcado con tabindex=-1 (el lienzo de la firma) se enfoca él mismo, no el botón vecino
    const directo = marca.matches(ENFOCABLE) || (marca.getAttribute('aria-invalid') === 'true' && marca.getAttribute('tabindex') === '-1');
    const objetivo = directo ? marca : (marca.parentElement?.querySelector<HTMLElement>(ENFOCABLE) ?? null);
    if (objetivo && aLaVista(objetivo)) return objetivo;
  }
  return null;
}

function soloConMensaje(e: Errores): Record<string, string> {
  return Object.fromEntries(Object.entries(e).filter((x): x is [string, string] => typeof x[1] === 'string' && x[1].trim() !== ''));
}

export function useValidacion({ etiquetas = {}, campos = {}, testid = 'resumen-errores' }: OpcionesValidacion = {}): Validacion {
  const ref = useRef<HTMLFormElement & HTMLDivElement>(null);
  // Las pantallas pasan objetos nuevos en cada render: se leen de una ref para que validar y errorServidor sean estables
  const opciones = useRef({ etiquetas, campos });
  opciones.current = { etiquetas, campos };
  const resumenRef = useRef<HTMLDivElement>(null);
  const [errores, setErrores] = useState<Record<string, string>>({});
  const [marcado, setMarcado] = useState<string | null>(null);
  const [general, setGeneral] = useState<string | null>(null);
  // Título y campos del resumen al momento del aviso: no cambian mientras el usuario corrige (el lector de pantalla no vuelve a
  // anunciar el resumen con cada tecla). El resumen se va cuando ya no queda ningún error.
  const [fijo, setFijo] = useState<{ titulo: string; nombres: string[] }>({ titulo: '', nombres: [] });
  // Adónde va el foco: al primer campo con error (validación del formulario) o al resumen (error del servidor o aviso general:
  // así el lector lee el motivo antes de llegar al campo)
  const [intento, setIntento] = useState<{ n: number; destino: 'campo' | 'resumen' }>({ n: 0, destino: 'campo' });

  useEffect(() => {
    if (intento.n === 0) return;
    const campo = intento.destino === 'campo' && ref.current ? primerCampoConError(ref.current) : null;
    (campo ?? resumenRef.current)?.focus();
  }, [intento]);

  const avisar = useCallback((destino: 'campo' | 'resumen') => {
    sonarError();
    setIntento((i) => ({ n: i.n + 1, destino }));
  }, []);

  const validar = useCallback(
    (e: Errores) => {
      const conMensaje = soloConMensaje(e);
      setErrores(conMensaje);
      setMarcado(null);
      setGeneral(null);
      const nombres = Object.keys(conMensaje).map((c) => opciones.current.etiquetas[c] ?? c);
      if (nombres.length === 0) return true;
      setFijo({ titulo: nombres.length === 1 ? `Revise el campo marcado: ${nombres[0]}` : `Revise los ${nombres.length} campos marcados`, nombres });
      avisar('campo');
      return false;
    },
    [avisar],
  );

  const errorServidor = useCallback(
    (e: unknown, texto: string) => {
      const delServidor = e instanceof ErrorApi && typeof e.datos.campo === 'string' ? e.datos.campo : null;
      const { etiquetas, campos } = opciones.current;
      const campo = delServidor === null ? null : (campos[delServidor] ?? delServidor);
      setErrores({});
      // Solo se marca un campo que el formulario conoce
      const conocido = campo !== null && campo in etiquetas ? campo : null;
      setMarcado(conocido);
      setGeneral(e instanceof ErrorApi ? e.message : texto);
      setFijo({ titulo: conocido !== null ? `Revise el campo marcado: ${etiquetas[conocido]}` : 'No se pudo completar la acción', nombres: [] });
      avisar('resumen');
    },
    [avisar],
  );

  const alerta = useCallback(
    (mensaje: string) => {
      setErrores({});
      setMarcado(null);
      setGeneral(mensaje);
      setFijo({ titulo: 'No se pudo completar la acción', nombres: [] });
      avisar('resumen');
    },
    [avisar],
  );

  const limpiar = useCallback((campo: string) => {
    setErrores((x) => {
      if (!(campo in x)) return x;
      const { [campo]: _quitado, ...resto } = x;
      return resto;
    });
    setMarcado((m) => (m === campo ? null : m));
  }, []);

  const reiniciar = useCallback(() => {
    setErrores({});
    setMarcado(null);
    setGeneral(null);
  }, []);

  const hayErrores = Object.keys(errores).length > 0 || general !== null;

  const resumen = hayErrores ? (
    <Alert ref={resumenRef} tabIndex={-1} color="red" data-testid={testid} title={fijo.titulo}>
      {general ??
        (fijo.nombres.length > 1 && (
          <List size="sm">
            {fijo.nombres.map((n) => (
              <List.Item key={n}>{n}</List.Item>
            ))}
          </List>
        ))}
    </Alert>
  ) : null;

  return {
    ref,
    error: (campo) => errores[campo] ?? (marcado === campo ? true : undefined),
    validar,
    errorServidor,
    alerta,
    limpiar,
    reiniciar,
    hayErrores,
    resumen,
  };
}
