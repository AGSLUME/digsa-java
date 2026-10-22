/**
 * Cliente HTTP de la SPA: mismo origen, cookie de sesión HttpOnly y token CSRF en la cabecera X-XSRF-TOKEN (lo lee de la cookie
 * XSRF-TOKEN). Toda respuesta del servidor cuenta como actividad (se comparte entre pestañas). Un 401 lleva al ingreso con el motivo.
 */

export class ErrorApi extends Error {
  constructor(
    public readonly estado: number,
    mensaje: string,
    public readonly datos: Record<string, unknown> = {},
  ) {
    super(mensaje);
  }
}

export const CLAVE_ACTIVIDAD = 'digsa.actividad';
export const CLAVE_MOTIVO = 'digsa.motivo';
const EVENTO_SESION_CERRADA = 'digsa:sesion-cerrada';

function leerCookie(nombre: string): string | undefined {
  return document.cookie
    .split(';')
    .map((c) => c.trim())
    .find((c) => c.startsWith(nombre + '='))
    ?.substring(nombre.length + 1);
}

export function marcarActividad(): void {
  try {
    localStorage.setItem(CLAVE_ACTIVIDAD, String(Date.now()));
  } catch {
    // sin almacenamiento (ventana privada): el aviso de esta pestaña sigue funcionando
  }
}

export function alCerrarseLaSesion(fn: (mensaje: string) => void): () => void {
  const h = (e: Event) => fn((e as CustomEvent<string>).detail);
  window.addEventListener(EVENTO_SESION_CERRADA, h);
  return () => window.removeEventListener(EVENTO_SESION_CERRADA, h);
}

async function asegurarCsrf(): Promise<void> {
  if (!leerCookie('XSRF-TOKEN')) {
    await fetch('/api/auth/inicio', { credentials: 'same-origin' });
  }
}

export async function api<T>(ruta: string, opciones: { metodo?: string; cuerpo?: unknown } = {}): Promise<T> {
  const metodo = opciones.metodo ?? (opciones.cuerpo === undefined ? 'GET' : 'POST');
  const cabeceras: Record<string, string> = { Accept: 'application/json' };
  if (metodo !== 'GET') {
    await asegurarCsrf();
    const token = leerCookie('XSRF-TOKEN');
    if (token) cabeceras['X-XSRF-TOKEN'] = decodeURIComponent(token);
    cabeceras['Content-Type'] = 'application/json';
  }
  let r: Response;
  try {
    r = await fetch(ruta, {
      method: metodo,
      headers: cabeceras,
      credentials: 'same-origin',
      body: opciones.cuerpo === undefined ? undefined : JSON.stringify(opciones.cuerpo),
    });
  } catch {
    throw new ErrorApi(0, 'No hay conexión con el servidor. Revise su conexión e intente de nuevo.');
  }
  return leerRespuesta<T>(r, ruta);
}

/**
 * Sube archivos en una sola petición multipart (el servidor los guarda todos o ninguno). No fija Content-Type: el navegador pone
 * el boundary. Mismo token CSRF y mismo manejo de errores (401 incluido) que {@link api}.
 */
export async function subirArchivos<T>(ruta: string, campos: Record<string, string>, archivos: File[], campoArchivos = 'archivos'): Promise<T> {
  await asegurarCsrf();
  const cabeceras: Record<string, string> = { Accept: 'application/json' };
  const token = leerCookie('XSRF-TOKEN');
  if (token) cabeceras['X-XSRF-TOKEN'] = decodeURIComponent(token);
  const cuerpo = new FormData();
  for (const [k, v] of Object.entries(campos)) cuerpo.append(k, v);
  for (const a of archivos) cuerpo.append(campoArchivos, a, a.name);
  let r: Response;
  try {
    r = await fetch(ruta, { method: 'POST', headers: cabeceras, credentials: 'same-origin', body: cuerpo });
  } catch {
    throw new ErrorApi(0, 'No hay conexión con el servidor. Revise su conexión e intente de nuevo.');
  }
  return leerRespuesta<T>(r, ruta);
}

/** Rutas que no usan la sesión del usuario: su 401 no cierra la sesión (el enlace del acta responde 401 cuando hay que abrirlo de nuevo). */
function sinSesionDeUsuario(ruta: string): boolean {
  return ruta.startsWith('/api/auth/') || ruta.startsWith('/api/opl-publico/');
}

async function leerRespuesta<T>(r: Response, ruta: string): Promise<T> {
  // Consultar el estado de la sesión no es actividad: el servidor tampoco la renueva
  if (ruta !== '/api/sesion/estado') marcarActividad();
  const tipo = r.headers.get('Content-Type') ?? '';
  const datos: Record<string, unknown> = tipo.includes('application/json') ? await r.json().catch(() => ({})) : {};
  if (!r.ok) {
    const mensaje =
      typeof datos.mensaje === 'string'
        ? datos.mensaje
        : r.status === 413
          ? 'El envío supera el tamaño máximo permitido (25 MB por archivo, 100 MB los videos). Envíe menos archivos o más livianos.'
          : `Error ${r.status}`;
    // Solo una sesión que existía y se cerró (inactividad, otro equipo, usuario inactivo...) lleva al ingreso con el motivo;
    // "sin_sesion" es quien aún no ha ingresado (p. ej. el enlace para definir la contraseña) y no se interrumpe.
    if (r.status === 401 && typeof datos.motivo === 'string' && datos.motivo !== 'sin_sesion' && !sinSesionDeUsuario(ruta)) {
      try {
        sessionStorage.setItem(CLAVE_MOTIVO, mensaje);
      } catch {
        // sin almacenamiento
      }
      window.dispatchEvent(new CustomEvent(EVENTO_SESION_CERRADA, { detail: mensaje }));
    }
    throw new ErrorApi(r.status, mensaje, datos);
  }
  return datos as T;
}

/** Descarga un archivo de la API (Excel, CSV, PDF) con el nombre que da el servidor. */
export async function descargar(ruta: string): Promise<void> {
  let r: Response;
  try {
    r = await fetch(ruta, { credentials: 'same-origin' });
  } catch {
    throw new ErrorApi(0, 'No hay conexión con el servidor. Revise su conexión e intente de nuevo.');
  }
  marcarActividad();
  if (!r.ok) {
    const d = await r.json().catch(() => ({}) as Record<string, unknown>);
    const mensaje = typeof d.mensaje === 'string' ? d.mensaje : `Error ${r.status}`;
    if (r.status === 401 && typeof d.motivo === 'string' && d.motivo !== 'sin_sesion' && !sinSesionDeUsuario(ruta)) {
      window.dispatchEvent(new CustomEvent(EVENTO_SESION_CERRADA, { detail: mensaje }));
    }
    throw new ErrorApi(r.status, mensaje, d);
  }
  const nombre = /filename="([^"]+)"/.exec(r.headers.get('Content-Disposition') ?? '')?.[1] ?? 'reporte';
  const url = URL.createObjectURL(await r.blob());
  const a = document.createElement('a');
  a.href = url;
  a.download = nombre;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
