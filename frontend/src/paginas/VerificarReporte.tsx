import { Alert, Button, Card, FileInput, Group, SimpleGrid, Stack, Text, TextInput, Title } from '@mantine/core';
import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { api, ErrorApi, subirArchivos } from '../shared/api';
import { type ResultadoVerificacion, colorVerificacion, filasVerificacion, sePuedeRegenerar } from '../shared/reportes';

/** Resultado de una verificación: el mensaje, lo que quedó registrado del reporte y las huellas calculadas (etiqueta sobre valor). */
function Resultado({ r }: { r: ResultadoVerificacion }) {
  const filas = filasVerificacion(r);
  return (
    <Stack gap="xs" data-testid="resultado-verificacion">
      <Alert color={colorVerificacion(r.resultado)} title={r.mensaje} />
      {filas.length > 0 && (
        <Stack gap={6} data-testid="registro-verificado">
          {filas.map(([k, v]) => (
            <div key={k}>
              <Text size="xs" c="dimmed">
                {k}
              </Text>
              <Text size="sm" ff={k.startsWith('Huella') ? 'monospace' : undefined} style={{ overflowWrap: 'anywhere' }}>
                {v}
              </Text>
            </div>
          ))}
        </Stack>
      )}
    </Stack>
  );
}

function mensajeDe(e: unknown): string {
  return e instanceof ErrorApi ? e.message : 'No se pudo verificar el reporte.';
}

/**
 * Verificar un reporte (Anexo 5, 2.10): subiendo el archivo descargado, que se compara por su huella con lo que generó la Suite
 * DIGSA, o con el código impreso en el encabezado, que además permite regenerarlo con los mismos parámetros y comparar los datos.
 */
export function VerificarReporte() {
  const [archivo, setArchivo] = useState<File | null>(null);
  const [codigo, setCodigo] = useState('');

  const porArchivo = useMutation({
    mutationFn: (f: File) => subirArchivos<ResultadoVerificacion>('/api/reportes/verificar', {}, [f], 'archivo'),
  });
  const porCodigo = useMutation({
    mutationFn: (c: string) => api<ResultadoVerificacion>(`/api/reportes/verificar/${encodeURIComponent(c.trim())}`),
  });
  const regenerar = useMutation({
    mutationFn: (c: string) => api<ResultadoVerificacion>(`/api/reportes/verificar/${encodeURIComponent(c.trim())}/regenerar`, { cuerpo: {} }),
  });
  const deCodigo = regenerar.data ?? porCodigo.data;
  const errorCodigo = regenerar.error ?? porCodigo.error;
  // Un resultado de otro archivo u otro código no puede quedar a la vista junto al nuevo
  const elegirArchivo = (f: File | null) => {
    setArchivo(f);
    porArchivo.reset();
  };
  const escribirCodigo = (c: string) => {
    setCodigo(c);
    porCodigo.reset();
    regenerar.reset();
  };
  const regenerable = sePuedeRegenerar(porCodigo.data);

  return (
    <Stack>
      <Text size="sm" c="dimmed">
        Cada reporte lleva impreso un código de verificación y la huella de sus datos. Un archivo modificado después de descargado no coincide con
        ningún reporte generado.
      </Text>
      <SimpleGrid cols={{ base: 1, md: 2 }}>
        <Card withBorder>
          <Title order={2} size="h5" mb="xs">
            Por archivo
          </Title>
          <form
            onSubmit={(ev) => {
              ev.preventDefault();
              if (archivo) porArchivo.mutate(archivo);
            }}
          >
            <Group align="flex-end" wrap="wrap">
              <FileInput
                label="Archivo del reporte"
                placeholder="Excel, CSV, PDF o TXT"
                accept=".xlsx,.csv,.pdf,.txt"
                value={archivo}
                onChange={elegirArchivo}
                clearable
                style={{ flex: 1, minWidth: 200 }}
                data-testid="archivo-verificar"
              />
              <Button type="submit" disabled={!archivo} loading={porArchivo.isPending}>
                Verificar archivo
              </Button>
            </Group>
          </form>
          {porArchivo.error && (
            <Alert color="red" mt="sm">
              {mensajeDe(porArchivo.error)}
            </Alert>
          )}
          {porArchivo.data && (
            <Stack mt="sm">
              <Resultado r={porArchivo.data} />
            </Stack>
          )}
        </Card>
        <Card withBorder>
          <Title order={2} size="h5" mb="xs">
            Por código de verificación
          </Title>
          <form
            onSubmit={(ev) => {
              ev.preventDefault();
              if (codigo.trim()) {
                regenerar.reset();
                porCodigo.mutate(codigo);
              }
            }}
          >
            <Group align="flex-end" wrap="wrap">
              <TextInput
                label="Código"
                placeholder="RPT-aaaammdd-XXXXXXXX"
                value={codigo}
                onChange={(e) => escribirCodigo(e.currentTarget.value)}
                style={{ flex: 1, minWidth: 200 }}
              />
              <Button type="submit" disabled={!codigo.trim()} loading={porCodigo.isPending}>
                Consultar
              </Button>
              <Button
                variant="light"
                disabled={!codigo.trim() || !regenerable}
                loading={regenerar.isPending}
                onClick={() => {
                  porCodigo.reset();
                  regenerar.mutate(codigo);
                }}
              >
                Regenerar y comparar
              </Button>
            </Group>
          </form>
          {!regenerable && (
            <Text size="xs" c="dimmed" mt="xs">
              Este reporte no se puede regenerar: no terminó de generarse o se vio en pantalla sin todas sus filas.
            </Text>
          )}
          {errorCodigo && (
            <Alert color="red" mt="sm">
              {mensajeDe(errorCodigo)}
            </Alert>
          )}
          {deCodigo && (
            <Stack mt="sm">
              <Resultado r={deCodigo} />
            </Stack>
          )}
        </Card>
      </SimpleGrid>
    </Stack>
  );
}
