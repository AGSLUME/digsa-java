import { Alert, Anchor, Box, Center, Group, Loader, Modal, Stack, Text } from '@mantine/core';
import { useEffect, useState, type ReactNode } from 'react';
import { ErrorApi, obtenerArchivo } from './api';

type Estado = { tipo: 'cargando' } | { tipo: 'listo'; url: string; vista: Vista; nombre: string } | { tipo: 'error'; mensaje: string };
type Vista = 'imagen' | 'pdf' | 'ninguna';

/**
 * Lista blanca del visor (UI-31): solo estos tipos se muestran, y el blob se crea con el tipo de la lista, no con el que diga la
 * respuesta. Un HTML o un SVG subido como evidencia nunca se pinta: dentro de un `blob:` heredaría el origen de la aplicación y su
 * script correría con la sesión del usuario. HEIC, video y lo demás solo se descargan.
 */
const VISTAS: Record<string, Exclude<Vista, 'ninguna'>> = { 'application/pdf': 'pdf', 'image/png': 'imagen', 'image/jpeg': 'imagen' };

export function vistaDe(mime: string, visorPdf = navigator.pdfViewerEnabled !== false): Vista {
  const tipo = mime.split(';')[0]!.trim().toLowerCase();
  const vista = VISTAS[tipo] ?? 'ninguna';
  // Chrome de Android y algunos navegadores de tableta no dibujan un PDF dentro de un iframe: ahí solo se ofrece la descarga
  return vista === 'pdf' && !visorPdf ? 'ninguna' : vista;
}

/** Tipo con el que se crea el blob: el de la lista blanca, o binario (se descarga, nunca se interpreta). */
export function tipoSeguro(mime: string): string {
  const tipo = mime.split(';')[0]!.trim().toLowerCase();
  return tipo in VISTAS ? tipo : 'application/octet-stream';
}

/**
 * Documento (evidencia, acta anterior, escrito de la solicitud) que se ve dentro de la aplicación, en un modal, en lugar de abrir otra
 * pestaña (condición 12 de la prueba en sitio, L1008; decisión UI-5). PDF e imágenes se muestran; lo demás se ofrece para descargar.
 * El archivo se pide con la sesión de la página y se muestra desde un `blob:` (la política de seguridad no deja enmarcar la API; UI-31).
 */
export function VisorDocumento({ url, nombre, children }: { url: string; nombre: string; children: ReactNode }) {
  const [abierto, setAbierto] = useState(false);
  const [estado, setEstado] = useState<Estado>({ tipo: 'cargando' });
  const [sinVista, setSinVista] = useState(false);

  useEffect(() => {
    if (!abierto) return;
    const control = new AbortController();
    let objeto: string | null = null;
    setEstado({ tipo: 'cargando' });
    setSinVista(false);
    obtenerArchivo(url, control.signal)
      .then(({ blob, nombre: delServidor }) => {
        const vista = vistaDe(blob.type);
        // El blob lleva el tipo de la lista blanca (o binario), nunca el de la respuesta: un HTML o SVG no se interpreta ni abierto aparte
        objeto = URL.createObjectURL(new Blob([blob], { type: vista === 'ninguna' ? 'application/octet-stream' : tipoSeguro(blob.type) }));
        if (!control.signal.aborted) setEstado({ tipo: 'listo', url: objeto, vista, nombre: delServidor });
      })
      .catch((e: unknown) => {
        if (!control.signal.aborted) setEstado({ tipo: 'error', mensaje: e instanceof ErrorApi ? e.message : 'No se pudo abrir el documento.' });
      });
    return () => {
      control.abort(); // cerrar el modal corta la descarga
      if (objeto) URL.revokeObjectURL(objeto);
    };
  }, [abierto, url]);

  const cerrar = () => {
    setAbierto(false);
    setEstado({ tipo: 'cargando' }); // al reabrir no se pinta el blob ya revocado
  };

  const vista = (e: Extract<Estado, { tipo: 'listo' }>) => {
    if (e.vista === 'imagen' && !sinVista) {
      return (
        <img
          src={e.url}
          alt={nombre}
          onError={() => setSinVista(true)}
          style={{ maxWidth: '100%', maxHeight: '70dvh', display: 'block', margin: '0 auto' }}
        />
      );
    }
    if (e.vista === 'pdf') {
      // Sin `sandbox`: Chrome y Edge no dibujan un PDF en un iframe con sandbox, con ningún permiso suelto (probado; solo con
      // allow-scripts + allow-same-origin, que anula el sandbox). Lo que protege es el tipo: el blob es siempre application/pdf (lista
      // blanca del cliente), así que el navegador lo abre con su visor de PDF y nunca como HTML (UI-31)
      return <iframe src={e.url} title={nombre} style={{ width: '100%', height: '70dvh', border: 0 }} />;
    }
    return <Text size="sm">Este archivo no tiene vista previa en este navegador. Descárguelo para abrirlo.</Text>;
  };

  return (
    <>
      <Anchor component="button" type="button" size="sm" ta="left" style={{ wordBreak: 'break-all' }} onClick={() => setAbierto(true)}>
        {children}
      </Anchor>
      <Modal opened={abierto} onClose={cerrar} title={nombre} size="xl" data-testid="visor-documento">
        <Stack>
          {estado.tipo === 'cargando' && (
            <Center mih={120}>
              <Loader aria-label="Cargando el documento" />
            </Center>
          )}
          {estado.tipo === 'error' && (
            <Alert color="red" role="alert">
              {estado.mensaje}
            </Alert>
          )}
          {estado.tipo === 'listo' && (
            <>
              <Box>{vista(estado)}</Box>
              <Group justify="flex-end">
                <Anchor href={estado.url} download={estado.nombre} size="sm">
                  Descargar
                </Anchor>
              </Group>
            </>
          )}
        </Stack>
      </Modal>
    </>
  );
}
