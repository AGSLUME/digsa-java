import { CLAVE_MOTIVO, ErrorApi, marcarActividad } from './api';

/** Resultado de pedir la descarga de un reporte: el archivo ya bajó, o sigue en segundo plano (202) y queda en «Descargas del día». */
export type ResultadoDescarga = { diferido: false } | { diferido: true; codigo: string; mensaje: string };

/**
 * Descarga de un reporte (Anexo 5, 2.7). Como {@link descargar} de api.ts, pero entiende el 202 de la generación en segundo plano: en
 * ese caso no baja nada y devuelve el código para buscarlo en «Descargas del día». Una sesión cerrada (401) lleva al ingreso con el
 * motivo, igual que el resto de la API.
 */
export async function descargarReporte(ruta: string): Promise<ResultadoDescarga> {
  let r: Response;
  try {
    r = await fetch(ruta, { credentials: 'same-origin' });
  } catch {
    throw new ErrorApi(0, 'No hay conexión con el servidor. Revise su conexión e intente de nuevo.');
  }
  marcarActividad();
  if (r.status === 202) {
    const d = (await r.json().catch(() => ({}))) as { codigo?: string; mensaje?: string };
    return { diferido: true, codigo: d.codigo ?? '', mensaje: d.mensaje ?? 'El reporte se está generando. Lo encontrará en «Descargas del día».' };
  }
  if (!r.ok) {
    const d = (await r.json().catch(() => ({}))) as Record<string, unknown>;
    const mensaje = typeof d.mensaje === 'string' ? d.mensaje : `Error ${r.status}`;
    if (r.status === 401 && typeof d.motivo === 'string' && d.motivo !== 'sin_sesion') {
      try {
        sessionStorage.setItem(CLAVE_MOTIVO, mensaje);
      } catch {
        // sin almacenamiento
      }
      window.dispatchEvent(new CustomEvent('digsa:sesion-cerrada', { detail: mensaje }));
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
  return { diferido: false };
}
