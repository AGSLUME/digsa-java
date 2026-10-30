import {
  Alert,
  Badge,
  Button,
  Checkbox,
  Drawer,
  FileInput,
  Group,
  Loader,
  Modal,
  Pagination,
  Progress,
  Radio,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  TextInput,
} from '@mantine/core';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useId, useMemo, useRef, useState, type ReactNode } from 'react';
import {
  abierto,
  COLOR_ESTADO,
  consulta,
  fechaBitacora,
  formatoAceptado,
  INTERVALO_SONDEO_MS,
  intervaloSondeo,
  motivoTexto,
  NOMBRE_ESTADO,
  NOMBRE_ETAPA,
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
  type Bitacora,
  type Cargue,
  type EstadoCargue,
  type Mapeo,
  type Reemplazo,
  type VistaPrevia,
} from '../analitica/cargues';
import { PanelExportacionHechos } from '../analitica/PanelExportacionHechos';
import { subirArchivo, type Avance } from '../analitica/subida';
import { api, descargar, ErrorApi, marcarActividad } from '../shared/api';
import { BotonConMotivo } from '../shared/BotonConMotivo';
import { EncabezadoPagina } from '../shared/EncabezadoPagina';
import { conteo, fecha, fechaHora } from '../shared/formato';
import { TablaAdaptable } from '../shared/TablaAdaptable';
import { useValidacion } from '../shared/validacion';

const CLAVE = ['analitica', 'cargues'];

function mensajeError(e: unknown, defecto: string): string {
  return e instanceof ErrorApi ? e.message : defecto;
}

/**
 * «Cargues del OPL» (analitica-ingesta; mockup #interop, bitácora de cargues): sube el insumo de dispensación por partes, asigna las
 * columnas a los campos del modelo con la vista previa enmascarada, muestra el avance del procesamiento y la bitácora con los ocho
 * atributos de 4.7.1 (fecha y hora, fuente, periodo, recibidos, procesados, rechazados, motivo y responsable), más el detalle con la
 * duración por etapa, el SHA-256 y la descarga de los rechazos (línea y causa, sin el contenido de las celdas). Un cargue en subida o
 * mapeo se retoma o se descarta desde el detalle; si nadie lo toca, vence.
 */
export function CarguesOpl() {
  const qc = useQueryClient();
  const [filtros, setFiltros] = useState<{ fuente: string | null; estado: string | null; desde: string; hasta: string }>({
    fuente: null,
    estado: null,
    desde: '',
    hasta: '',
  });
  const [pagina, setPagina] = useState(1);
  const [subiendo, setSubiendo] = useState(false);
  const [detalle, setDetalle] = useState<number | null>(null);
  const q = consulta({ ...filtros, pagina });
  const lista = useQuery({
    queryKey: [...CLAVE, q],
    queryFn: () => api<Bitacora>('/api/analitica/cargues' + q),
    refetchInterval: (x) => intervaloSondeo(x.state.data?.filas),
  });
  const [errorDescarga, setErrorDescarga] = useState<string | null>(null);
  const datos = lista.data;
  const idAviso = useId();

  /** Todo cambio de filtro vuelve a la página 1 (una página que ya no existe dejaría la tabla vacía y sin paginación). */
  function filtrar(cambio: Partial<typeof filtros>) {
    setFiltros((f) => ({ ...f, ...cambio }));
    setPagina(1);
  }

  async function exportar() {
    setErrorDescarga(null);
    try {
      await descargar('/api/analitica/cargues/bitacora.csv' + consulta(filtros));
    } catch (e) {
      setErrorDescarga(mensajeError(e, 'No se pudo descargar la bitácora.'));
    }
  }

  return (
    <Stack>
      <EncabezadoPagina
        titulo="Cargues del OPL"
        descripcion="Insumo de dispensación del operador logístico por archivo estructurado o por el servicio web. Cada cargue queda en la bitácora con sus registros recibidos, procesados y rechazados. El paciente se guarda solo como seudónimo; nombres, contactos y fecha de nacimiento se descartan."
        fuente="Anexo 5 · 4.7.1"
        acciones={
          <>
            {datos?.puedeCargar && (
              <BotonConMotivo motivo={datos.avisoConfiguracion || null} idMotivo={idAviso} onClick={() => setSubiendo(true)}>
                Cargar archivo estructurado
              </BotonConMotivo>
            )}
            <Button variant="default" onClick={exportar}>
              Exportar CSV
            </Button>
          </>
        }
      />
      {datos?.avisoConfiguracion && (
        <Alert color="red" title="Cargues deshabilitados" data-testid="aviso-configuracion" id={idAviso}>
          {datos.avisoConfiguracion}
        </Alert>
      )}
      {errorDescarga && <Alert color="red">{errorDescarga}</Alert>}
      <Group align="end" gap="sm" wrap="wrap">
        <Select
          label="Fuente"
          placeholder="Todas"
          clearable
          data={[
            { value: 'archivo', label: 'Archivo' },
            { value: 'ws_opl', label: 'API OPL' },
          ]}
          value={filtros.fuente}
          onChange={(v) => filtrar({ fuente: v })}
          w={160}
        />
        <Select
          label="Estado"
          placeholder="Todos"
          clearable
          data={(Object.keys(NOMBRE_ESTADO) as EstadoCargue[]).map((e) => ({ value: e, label: NOMBRE_ESTADO[e] }))}
          value={filtros.estado}
          onChange={(v) => filtrar({ estado: v })}
          w={180}
        />
        <TextInput label="Desde" type="date" value={filtros.desde} onChange={(e) => filtrar({ desde: e.currentTarget.value })} w={170} />
        <TextInput label="Hasta" type="date" value={filtros.hasta} onChange={(e) => filtrar({ hasta: e.currentTarget.value })} w={170} />
      </Group>
      {lista.error && <Alert color="red">{lista.error.message}</Alert>}
      {lista.isLoading && <Loader />}
      {datos && datos.filas.length === 0 && <Text c="dimmed">No hay cargues con estos filtros.</Text>}
      {datos && datos.filas.length > 0 && (
        <TablaAdaptable>
          <Table striped highlightOnHover withTableBorder data-testid="bitacora-cargues" miw={1180}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Fecha y hora</Table.Th>
                <Table.Th>Fuente</Table.Th>
                <Table.Th>Periodo procesado</Table.Th>
                <Table.Th ta="right">Recibidos</Table.Th>
                <Table.Th ta="right">Procesados</Table.Th>
                <Table.Th ta="right">Rechazados</Table.Th>
                <Table.Th>Motivo del rechazo</Table.Th>
                <Table.Th>Responsable</Table.Th>
                <Table.Th>Estado</Table.Th>
                <Table.Th>Detalle</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {datos.filas.map((c) => (
                <Table.Tr key={c.id} data-testid={`cargue-${c.id}`} style={{ cursor: 'pointer' }} onClick={() => setDetalle(c.id)}>
                  <Table.Td>{fechaBitacora(c)}</Table.Td>
                  <Table.Td>{c.fuenteTexto}</Table.Td>
                  <Table.Td>{periodo(c)}</Table.Td>
                  <Table.Td ta="right">{conteo(c.recibidos)}</Table.Td>
                  <Table.Td ta="right">{conteo(c.procesados)}</Table.Td>
                  <Table.Td ta="right" c={(c.rechazados ?? 0) > 0 ? 'red' : undefined} data-testid="rechazados">
                    {conteo(c.rechazados)}
                  </Table.Td>
                  <Table.Td maw={320}>
                    <Text size="sm" lineClamp={2}>
                      {motivoTexto(c) || '—'}
                    </Text>
                  </Table.Td>
                  <Table.Td>{c.responsable}</Table.Td>
                  <Table.Td>
                    <Group gap={4} wrap="nowrap">
                      {(c.estado === 'procesando' || c.estado === 'pendiente') && <Loader size="xs" />}
                      <Badge color={COLOR_ESTADO[c.estado]} data-testid="estado">
                        {NOMBRE_ESTADO[c.estado]}
                      </Badge>
                    </Group>
                  </Table.Td>
                  <Table.Td>
                    {/* Botón: el detalle se abre también con el teclado (Tab y Enter) y lo anuncia el lector de pantalla */}
                    <Button
                      variant="subtle"
                      size="compact-sm"
                      aria-label={`Ver el detalle del cargue ${c.id}`}
                      onClick={(e) => {
                        e.stopPropagation();
                        setDetalle(c.id);
                      }}
                    >
                      Ver detalle
                    </Button>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </TablaAdaptable>
      )}
      {datos && datos.total > datos.tamanoPagina && (
        <Pagination total={Math.ceil(datos.total / datos.tamanoPagina)} value={pagina} onChange={setPagina} />
      )}
      {/* A7.2 (duda AN.14): los hechos por periodo, un archivo por mes; solo el Administrador (el servidor responde 403 a los demás) */}
      {datos?.exportaHechos && <PanelExportacionHechos />}
      {subiendo && (
        <AsistenteCargue
          parteBytes={datos?.parteMaxBytes}
          archivoMaxBytes={datos?.archivoMaxBytes}
          horasAbandono={datos?.horasAbandono}
          alCerrar={() => {
            setSubiendo(false);
            qc.invalidateQueries({ queryKey: CLAVE });
          }}
          alVer={(id) => {
            setSubiendo(false);
            setDetalle(id);
            qc.invalidateQueries({ queryKey: CLAVE });
          }}
        />
      )}
      <Drawer opened={detalle !== null} onClose={() => setDetalle(null)} position="right" size="lg" title="Detalle del cargue">
        {detalle !== null && (
          <DetalleCargue
            id={detalle}
            esAdministrador={!!datos?.esAdministrador}
            puedeCargar={!!datos?.puedeCargar}
            horasAbandono={datos?.horasAbandono}
          />
        )}
      </Drawer>
    </Stack>
  );
}

function Dato({ titulo, children }: { titulo: string; children: ReactNode }) {
  return (
    <div>
      <Text size="xs" c="dimmed">
        {titulo}
      </Text>
      <Text size="sm" style={{ overflowWrap: 'anywhere' }}>
        {children}
      </Text>
    </div>
  );
}

/** Estado, avance, duración por etapa, SHA-256, motivos con su conteo y descargas de un cargue; retomar o descartar uno abierto. */
function DetalleCargue({
  id,
  esAdministrador,
  puedeCargar,
  horasAbandono,
}: {
  id: number;
  esAdministrador: boolean;
  puedeCargar: boolean;
  horasAbandono?: number;
}) {
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: [...CLAVE, 'detalle', id],
    queryFn: () => api<Cargue>(`/api/analitica/cargues/${id}`),
    // Solo mientras avanza: un cargue en subida o mapeo espera al usuario y no cambia solo
    refetchInterval: (x) => (x.state.data && !terminado(x.state.data) && !abierto(x.state.data) ? INTERVALO_SONDEO_MS : false),
  });
  const [error, setError] = useState<string | null>(null);
  const [retomar, setRetomar] = useState(false);
  const [confirmarDescarte, setConfirmarDescarte] = useState(false);
  const descartar = useMutation({
    mutationFn: () => api<Cargue>(`/api/analitica/cargues/${id}`, { metodo: 'DELETE' }),
    onSuccess: () => {
      setConfirmarDescarte(false);
      qc.invalidateQueries({ queryKey: CLAVE });
    },
    onError: (e) => setError(mensajeError(e, 'No se pudo descartar el cargue.')),
  });
  const c = q.data;
  if (q.error) return <Alert color="red">{q.error.message}</Alert>;
  if (!c) return <Loader />;
  const puedeTocar = puedeCargar && (c.propio || esAdministrador);

  async function rechazos() {
    setError(null);
    try {
      await descargar(rutaRechazos(id));
    } catch (e) {
      setError(mensajeError(e, 'No se pudieron descargar los rechazos.'));
    }
  }

  /**
   * El original puede pesar varios GB: no se carga en memoria. Primero se confirma (con la sesión y sin salir de la aplicación) que
   * sigue disponible; luego el navegador lo descarga con un enlace «download», que nunca reemplaza la página por un error.
   */
  async function original() {
    setError(null);
    try {
      const actual = await api<Cargue>(`/api/analitica/cargues/${id}`);
      if (!actual.originalDisponible) {
        setError('El archivo original ya no se conserva.');
        return;
      }
      marcarActividad();
      const a = document.createElement('a');
      a.href = rutaOriginal(id);
      a.download = '';
      document.body.appendChild(a);
      a.click();
      a.remove();
    } catch (e) {
      setError(mensajeError(e, 'No se pudo descargar el original.'));
    }
  }

  return (
    <Stack data-testid="detalle-cargue">
      <Group justify="space-between">
        <Text fw={700}>
          Cargue {c.id} · {c.nombreArchivo ?? c.fuenteTexto}
        </Text>
        <Badge color={COLOR_ESTADO[c.estado]} data-testid="estado-detalle">
          {NOMBRE_ESTADO[c.estado]}
        </Badge>
      </Group>
      {(c.estado === 'procesando' || c.estado === 'pendiente') && <AvanceCargue c={c} />}
      {abierto(c) && (
        <Alert color="yellow" data-testid="cargue-abierto">
          {c.estado === 'mapeo' ? 'Falta confirmar el mapeo de columnas.' : 'La subida no terminó.'}
          {horasAbandono ? ` Si nadie lo retoma, vence a las ${horasAbandono} h sin actividad y su archivo se borra.` : ''}
        </Alert>
      )}
      {c.error && <Alert color="red">{c.error}</Alert>}
      {c.avisos.length > 0 && (
        <Alert color="yellow" title="Avisos" data-testid="avisos-cargue">
          {c.avisos.map((a) => (
            <Text key={a} size="sm">
              {a}
            </Text>
          ))}
        </Alert>
      )}
      <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="xs">
        <Dato titulo="Fuente">{c.fuenteTexto}</Dato>
        <Dato titulo="Responsable">{c.responsable}</Dato>
        <Dato titulo="Recepción">{fechaHora(c.recibido ?? c.creado)}</Dato>
        <Dato titulo="Aplicación (fecha de corte)">{fechaHora(c.aplicadoEn)}</Dato>
        <Dato titulo="Periodo procesado">{periodo(c)}</Dato>
        <Dato titulo="Modo">{c.modo === 'reemplazar' ? 'Reemplazar el periodo' : 'Agregar'}</Dato>
        <Dato titulo="Recibidos · procesados · rechazados">
          {conteo(c.recibidos)} · {conteo(c.procesados)} · {conteo(c.rechazados)}
        </Dato>
        <Dato titulo="ESM sin correspondencia">{conteo(c.esmSinCorrespondencia)}</Dato>
        <Dato titulo="SHA-256">{c.sha256 ?? '—'}</Dato>
        <Dato titulo="Duración total">{segundos(c.duracionMs)}</Dato>
      </SimpleGrid>
      {Object.keys(c.etapasMs).length > 0 && (
        <TablaAdaptable>
          <Table withTableBorder data-testid="etapas">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Etapa</Table.Th>
                <Table.Th ta="right">Duración</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {Object.entries(c.etapasMs).map(([e, ms]) => (
                <Table.Tr key={e}>
                  <Table.Td>{NOMBRE_ETAPA[e] ?? e}</Table.Td>
                  <Table.Td ta="right">{segundos(ms)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </TablaAdaptable>
      )}
      {(c.estado === 'rechazado' || c.estado === 'duplicado' || c.estado === 'vencido' || c.estado === 'descartado') && c.motivo && (
        <Alert
          color={c.estado === 'rechazado' ? 'red' : 'gray'}
          title={c.estado === 'rechazado' ? 'Rechazo del archivo completo' : NOMBRE_ESTADO[c.estado]}
        >
          {c.motivo}
        </Alert>
      )}
      {c.motivos.length > 0 && (
        <TablaAdaptable>
          <Table withTableBorder data-testid="motivos">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Motivo del rechazo</Table.Th>
                <Table.Th ta="right">Registros</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {c.motivos.map((m) => (
                <Table.Tr key={m.causa}>
                  <Table.Td>{m.texto}</Table.Td>
                  <Table.Td ta="right">{conteo(m.cantidad)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </TablaAdaptable>
      )}
      {error && <Alert color="red">{error}</Alert>}
      <Group>
        {c.estado === 'mapeo' && puedeTocar && c.fuente === 'archivo' && <Button onClick={() => setRetomar(true)}>Continuar con el mapeo</Button>}
        {abierto(c) && puedeTocar && (
          <Button color="red" variant="light" onClick={() => setConfirmarDescarte(true)}>
            Descartar cargue
          </Button>
        )}
        {(c.rechazados ?? 0) > 0 && (
          <Button variant="light" onClick={rechazos}>
            Descargar rechazos
          </Button>
        )}
        {esAdministrador && c.originalDisponible && (
          <Button variant="subtle" onClick={original}>
            Descargar el original (contiene datos personales)
          </Button>
        )}
      </Group>
      <Modal opened={confirmarDescarte} onClose={() => setConfirmarDescarte(false)} title="Descartar el cargue">
        <Stack>
          <Text size="sm">
            Se borran el archivo subido y sus partes (tienen datos personales en claro). El cargue queda en la bitácora como «Descartado».
          </Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setConfirmarDescarte(false)}>
              Volver
            </Button>
            <Button color="red" loading={descartar.isPending} onClick={() => descartar.mutate()}>
              Descartar
            </Button>
          </Group>
        </Stack>
      </Modal>
      <Modal opened={retomar} onClose={() => setRetomar(false)} title="Mapeo de columnas" size="xl">
        {retomar && (
          <PasoMapeo
            id={c.id}
            alConfirmar={() => {
              setRetomar(false);
              qc.invalidateQueries({ queryKey: CLAVE });
            }}
            alDescartar={() => {
              setRetomar(false);
              qc.invalidateQueries({ queryKey: CLAVE });
            }}
          />
        )}
      </Modal>
    </Stack>
  );
}

function AvanceCargue({ c }: { c: Cargue }) {
  return (
    <Stack gap={4}>
      <Text size="sm">
        {c.estado === 'pendiente'
          ? `En cola${c.posicion ? ` (posición ${c.posicion})` : ''}: empieza cuando termine el cargue anterior.`
          : `${NOMBRE_ETAPA[c.etapa ?? ''] ?? 'Procesando'} · ${conteo(c.filasLeidas)} registros leídos`}
      </Text>
      <Progress value={c.porcentaje} animated aria-label="Avance del cargue" />
    </Stack>
  );
}

/** Asistente: archivo → subida por partes con avance → mapeo con vista previa enmascarada → cola y avance. */
function AsistenteCargue({
  alCerrar,
  alVer,
  parteBytes,
  archivoMaxBytes,
  horasAbandono,
}: {
  alCerrar: () => void;
  alVer: (id: number) => void;
  parteBytes?: number;
  archivoMaxBytes?: number;
  horasAbandono?: number;
}) {
  const [archivo, setArchivo] = useState<File | null>(null);
  const [avance, setAvance] = useState<Avance | null>(null);
  const [cargue, setCargue] = useState<Cargue | null>(null);
  const v = useValidacion({ etiquetas: { archivo: 'Archivo del insumo' }, testid: 'error-subida' });
  const idMotivo = useId();
  const cancelador = useRef<AbortController | null>(null);
  const creado = useRef<number | null>(null);
  const subir = useMutation({
    mutationFn: (f: File) => {
      cancelador.current = new AbortController();
      creado.current = null;
      return subirArchivo(
        f,
        (a) => {
          if (a.cargue) creado.current = a.cargue;
          setAvance(a);
        },
        { parteBytes, senal: cancelador.current.signal },
      );
    },
    onSuccess: (c) => setCargue(c),
    onError: (e) => {
      if (e instanceof DOMException && e.name === 'AbortError') return;
      v.errorServidor(e, 'No se pudo subir el archivo.');
    },
  });
  const grande = !!archivo && !!archivoMaxBytes && archivo.size > archivoMaxBytes;
  const problemaArchivo =
    archivo && !formatoAceptado(archivo.name)
      ? 'Formato no aceptado: use CSV, TXT, xlsx, .gz o .zip.'
      : grande
        ? `El archivo supera el tamaño máximo (${Math.round((archivoMaxBytes ?? 0) / (1024 * 1024))} MB).`
        : null;
  const motivoSubir = !archivo ? 'Seleccione el archivo del insumo.' : problemaArchivo ? 'Corrija el archivo marcado.' : null;

  /** Cancelar a mitad de la subida: se abortan las partes en curso y se descarta el cargue creado (sus partes tienen datos personales). */
  async function cancelar() {
    if (subir.isPending) {
      cancelador.current?.abort();
      const id = creado.current;
      if (id !== null) {
        try {
          await api(`/api/analitica/cargues/${id}`, { metodo: 'DELETE' });
        } catch {
          // si no se pudo, vence solo
        }
      }
    }
    alCerrar();
  }

  return (
    <Modal
      opened
      onClose={cancelar}
      closeOnEscape={!subir.isPending}
      closeOnClickOutside={!subir.isPending}
      withCloseButton={!subir.isPending}
      title="Cargar archivo estructurado"
      size="xl"
    >
      {!cargue && (
        <Stack ref={v.ref}>
          <Text size="sm">
            CSV o TXT en UTF-8 separado por «;» (coma decimal) o «,», libro de Excel (.xlsx, varias hojas con el mismo encabezado), o esos mismos
            comprimidos en .gz o .zip. El archivo se sube por partes y se verifica su huella SHA-256.
          </Text>
          <FileInput
            label="Archivo del insumo"
            placeholder="Seleccione el archivo"
            accept=".csv,.txt,.xlsx,.gz,.zip"
            value={archivo}
            onChange={(f) => {
              setArchivo(f);
              v.reiniciar();
            }}
            disabled={subir.isPending}
            error={problemaArchivo ?? v.error('archivo')}
            data-testid="archivo-cargue"
          />
          {avance && subir.isPending && (
            <Stack gap={4}>
              <Text size="sm">
                {avance.etapa === 'huella' ? 'Calculando la huella SHA-256…' : avance.etapa === 'subida' ? 'Subiendo…' : 'Verificando el archivo…'}
              </Text>
              <Progress value={Math.round(avance.fraccion * 100)} aria-label="Avance de la subida" />
            </Stack>
          )}
          {v.resumen}
          <Group justify="flex-end">
            {motivoSubir && (
              <Text size="sm" c="dimmed" id={idMotivo}>
                {motivoSubir}
              </Text>
            )}
            <Button variant="default" onClick={cancelar}>
              {subir.isPending ? 'Cancelar la subida' : 'Cancelar'}
            </Button>
            <BotonConMotivo motivo={motivoSubir} idMotivo={idMotivo} loading={subir.isPending} onClick={() => archivo && subir.mutate(archivo)}>
              Subir
            </BotonConMotivo>
          </Group>
        </Stack>
      )}
      {cargue && cargue.existente && (
        <Stack>
          <Alert color="yellow">{cargue.mensaje}</Alert>
          <Group justify="flex-end">
            <Button onClick={() => alVer(cargue.id)}>Ver el cargue</Button>
          </Group>
        </Stack>
      )}
      {cargue && !cargue.existente && cargue.estado === 'mapeo' && (
        <Stack>
          {horasAbandono && (
            <Text size="xs" c="dimmed">
              Si cierra esta ventana, el cargue queda en «Mapeo pendiente» y puede retomarlo desde su detalle; sin actividad en {horasAbandono} h
              vence y su archivo se borra.
            </Text>
          )}
          <PasoMapeo id={cargue.id} alConfirmar={setCargue} alDescartar={alCerrar} />
        </Stack>
      )}
      {cargue && !cargue.existente && cargue.estado !== 'mapeo' && <PasoAvance id={cargue.id} alVer={alVer} />}
    </Modal>
  );
}

function PasoMapeo({ id, alConfirmar, alDescartar }: { id: number; alConfirmar: (c: Cargue) => void; alDescartar: () => void }) {
  const q = useQuery({ queryKey: [...CLAVE, 'mapeo', id], queryFn: () => api<Mapeo>(`/api/analitica/cargues/${id}/mapeo`) });
  const [elegidas, setElegidas] = useState<string[] | null>(null);
  const [modo, setModo] = useState<'agregar' | 'reemplazar'>('agregar');
  const [desde, setDesde] = useState('');
  const [hasta, setHasta] = useState('');
  const [plantilla, setPlantilla] = useState(false);
  const [nombrePlantilla, setNombrePlantilla] = useState('');
  const [aReemplazar, setAReemplazar] = useState<Reemplazo | null>(null);
  const v = useValidacion({
    etiquetas: { periodoDesde: 'Periodo declarado: desde', periodoHasta: 'Hasta', nombrePlantilla: 'Nombre de la plantilla' },
    testid: 'error-mapeo',
  });
  const idMotivo = useId();
  const m = q.data;
  // Lo elegido por el usuario o, mientras no cambie nada, la propuesta del servidor
  const columnas = elegidas ?? m?.propuesta ?? null;
  // La vista previa se pide con el mapeo de la pantalla: el servidor enmascara cada columna según su asignación
  const vista = useQuery({
    queryKey: [...CLAVE, 'vista', id, columnas?.join(',') ?? ''],
    queryFn: () => api<VistaPrevia>(rutaVistaPrevia(id, columnas ?? [])),
    enabled: !!columnas && elegidas !== null,
    placeholderData: keepPreviousData,
  });
  const problemas = useMemo(
    () => (m && columnas ? [...problemasMapeo(columnas, m.campos), ...problemasPrivacidad(m.encabezados, m.personales, columnas, m.campos)] : []),
    [m, columnas],
  );
  const conPeriodo = desde !== '' || hasta !== '';
  const periodoOk = !conPeriodo || periodoValido(desde, hasta);
  const faltaPeriodo = modo === 'reemplazar' && !periodoValido(desde, hasta);
  const confirmar = useMutation({
    mutationFn: () =>
      api<Cargue>(`/api/analitica/cargues/${id}/mapeo`, {
        cuerpo: {
          columnas,
          modo,
          guardarPlantilla: plantilla,
          nombrePlantilla: plantilla ? nombrePlantilla : null,
          periodoDesde: conPeriodo ? desde : null,
          periodoHasta: conPeriodo ? hasta : null,
        },
      }),
    onSuccess: alConfirmar,
    onError: (e) => v.errorServidor(e, 'No se pudo confirmar el mapeo.'),
  });
  const descartar = useMutation({
    mutationFn: () => api<Cargue>(`/api/analitica/cargues/${id}`, { metodo: 'DELETE' }),
    onSuccess: alDescartar,
    onError: (e) => v.errorServidor(e, 'No se pudo descartar el cargue.'),
  });

  /** Con «Reemplazar el periodo» se muestra antes qué se deja de ver (cargues y registros del periodo declarado). */
  async function procesar() {
    if (modo !== 'reemplazar') {
      confirmar.mutate();
      return;
    }
    v.reiniciar();
    try {
      setAReemplazar(await api<Reemplazo>(rutaReemplazo(id, desde, hasta)));
    } catch (e) {
      v.errorServidor(e, 'No se pudo consultar qué se reemplaza.');
    }
  }

  if (q.error) return <Alert color="red">{q.error.message}</Alert>;
  if (!m || !columnas) return <Loader />;
  const filasVista = elegidas === null ? m.vistaPrevia : (vista.data?.vistaPrevia ?? m.vistaPrevia);
  const avisoVista = elegidas === null ? m.avisoVistaPrevia : (vista.data?.avisoVistaPrevia ?? null);
  const motivoConfirmar =
    problemas.length > 0
      ? 'Corrija el mapeo de columnas indicado arriba.'
      : !periodoOk
        ? 'Corrija el periodo declarado.'
        : faltaPeriodo
          ? 'Para reemplazar, indique el periodo declarado (desde y hasta).'
          : null;
  const opciones = m.campos.map((c) => ({
    value: c.clave,
    label: c.tipo === 'obligatorio' ? `${c.etiqueta} *` : c.etiqueta,
  }));

  return (
    <Stack ref={v.ref}>
      <Text size="sm">
        Asigne cada columna de «{m.nombreArchivo}» a un campo del modelo (* obligatorio; medicamento o CUM: basta uno). Las columnas con datos
        personales se descartan o se seudonimizan.{m.plantilla ? ` Se propuso la plantilla «${m.plantilla.nombre}».` : ''}
      </Text>
      {m.avisos.length > 0 && (
        <Alert color="yellow" data-testid="avisos-mapeo">
          {m.avisos.join('. ')}.
        </Alert>
      )}
      <TablaAdaptable>
        <Table withTableBorder data-testid="mapeo">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Columna del archivo</Table.Th>
              <Table.Th>Campo</Table.Th>
              <Table.Th>Ejemplo (enmascarado)</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {m.encabezados.map((e, i) => (
              <Table.Tr key={`${e}-${i}`}>
                <Table.Td>
                  {e}
                  {m.personales[i] && (
                    <Badge ml={6} size="xs" color="orange" variant="light">
                      dato personal
                    </Badge>
                  )}
                </Table.Td>
                <Table.Td>
                  <Select
                    aria-label={`Campo de la columna ${e}`}
                    data={opciones}
                    value={columnas[i] ?? 'ignorar'}
                    onChange={(v) => setElegidas(columnas.map((x, k) => (k === i ? (v ?? 'ignorar') : x)))}
                    searchable
                    size="xs"
                  />
                </Table.Td>
                <Table.Td>
                  <Text size="xs" c="dimmed" lineClamp={1}>
                    {filasVista[0]?.[i] ?? ''}
                  </Text>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </TablaAdaptable>
      <Text size="sm" fw={500}>
        Vista previa ({filasVista.length} filas; en claro solo las columnas asignadas a un campo que no es personal)
      </Text>
      {avisoVista && <Alert color="yellow">{avisoVista}</Alert>}
      <TablaAdaptable>
        <Table striped withTableBorder fz="xs" data-testid="vista-previa">
          <Table.Thead>
            <Table.Tr>
              {m.encabezados.map((e, i) => (
                <Table.Th key={`${e}-${i}`}>{e}</Table.Th>
              ))}
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {filasVista.map((f, i) => (
              <Table.Tr key={i}>
                {f.map((v, k) => (
                  <Table.Td key={k}>{v}</Table.Td>
                ))}
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </TablaAdaptable>
      <Radio.Group label="Modo" value={modo} onChange={(v) => setModo(v as 'agregar' | 'reemplazar')}>
        <Group mt={4}>
          <Radio value="agregar" label="Agregar (los registros ya cargados se rechazan como «Ya cargado»)" />
          <Radio value="reemplazar" label="Reemplazar el periodo declarado (sustituye cargues anteriores de la misma fuente en ese periodo)" />
        </Group>
      </Radio.Group>
      <Group align="end" gap="sm" wrap="wrap">
        <TextInput
          label={modo === 'reemplazar' ? 'Periodo declarado: desde' : 'Periodo declarado (opcional): desde'}
          type="date"
          value={desde}
          onChange={(e) => {
            setDesde(e.currentTarget.value);
            v.limpiar('periodoDesde');
          }}
          required={modo === 'reemplazar'}
          error={v.error('periodoDesde')}
          w={230}
        />
        <TextInput
          label="Hasta"
          type="date"
          value={hasta}
          onChange={(e) => {
            setHasta(e.currentTarget.value);
            v.limpiar('periodoHasta');
          }}
          required={modo === 'reemplazar'}
          error={v.error('periodoHasta')}
          w={170}
        />
      </Group>
      <Text size="xs" c="dimmed">
        Las filas con fecha fuera del periodo declarado se rechazan («Fecha fuera del periodo declarado o futura»).
        {modo === 'reemplazar' ? ' Solo se reemplazan los cargues cuyo periodo queda dentro de este rango.' : ''}
      </Text>
      {!periodoOk && <Alert color="yellow">El periodo declarado debe tener las dos fechas y «desde» no puede ser posterior a «hasta».</Alert>}
      <Group align="end">
        <Checkbox label="Guardar como plantilla para este encabezado" checked={plantilla} onChange={(e) => setPlantilla(e.currentTarget.checked)} />
        {plantilla && (
          <TextInput
            label="Nombre de la plantilla"
            placeholder="Ej.: Insumo mensual del OPL"
            value={nombrePlantilla}
            onChange={(e) => {
              setNombrePlantilla(e.currentTarget.value);
              v.limpiar('nombrePlantilla');
            }}
            error={v.error('nombrePlantilla')}
          />
        )}
      </Group>
      {problemas.length > 0 && (
        <Alert color="yellow" data-testid="problemas-mapeo">
          {problemas.join('. ')}.
        </Alert>
      )}
      {v.resumen}
      <Group justify="space-between">
        <Button color="red" variant="subtle" loading={descartar.isPending} onClick={() => descartar.mutate()}>
          Descartar cargue
        </Button>
        <Group gap="sm">
          {motivoConfirmar && (
            <Text size="sm" c="dimmed" id={idMotivo}>
              {motivoConfirmar}
            </Text>
          )}
          <BotonConMotivo motivo={motivoConfirmar} idMotivo={idMotivo} loading={confirmar.isPending} onClick={procesar} data-testid="confirmar-mapeo">
            Confirmar y procesar
          </BotonConMotivo>
        </Group>
      </Group>
      <Modal opened={aReemplazar !== null} onClose={() => setAReemplazar(null)} title="Reemplazar el periodo" data-testid="confirmar-reemplazo">
        {aReemplazar && (
          <Stack>
            <Text size="sm">
              Periodo declarado: {fecha(aReemplazar.desde)} – {fecha(aReemplazar.hasta)}.
            </Text>
            <Text size="sm" fw={500}>
              {aReemplazar.cargues.length === 0
                ? 'Ningún cargue aplicado de esta fuente queda dentro del periodo: no se oculta nada.'
                : `Dejarán de verse en los cortes posteriores ${aReemplazar.cargues.length} cargue(s) aplicado(s) (${aReemplazar.cargues
                    .map((x) => `#${x}`)
                    .join(', ')}) con ${conteo(aReemplazar.registros)} registros. En los cortes anteriores se siguen viendo.`}
            </Text>
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setAReemplazar(null)}>
                Volver
              </Button>
              <Button
                color="orange"
                loading={confirmar.isPending}
                onClick={() => {
                  setAReemplazar(null);
                  confirmar.mutate();
                }}
              >
                Reemplazar y procesar
              </Button>
            </Group>
          </Stack>
        )}
      </Modal>
    </Stack>
  );
}

function PasoAvance({ id, alVer }: { id: number; alVer: (id: number) => void }) {
  const q = useQuery({
    queryKey: [...CLAVE, 'detalle', id],
    queryFn: () => api<Cargue>(`/api/analitica/cargues/${id}`),
    refetchInterval: (x) => (x.state.data && terminado(x.state.data) ? false : INTERVALO_SONDEO_MS),
  });
  const c = q.data;
  if (q.error) return <Alert color="red">{q.error.message}</Alert>;
  if (!c) return <Loader />;
  return (
    <Stack>
      <Group justify="space-between">
        <Text>Cargue {c.id}</Text>
        <Badge color={COLOR_ESTADO[c.estado]} data-testid="estado-asistente">
          {NOMBRE_ESTADO[c.estado]}
        </Badge>
      </Group>
      {!terminado(c) && <AvanceCargue c={c} />}
      {terminado(c) && (
        <Text size="sm">
          Recibidos {conteo(c.recibidos)} · procesados {conteo(c.procesados)} · rechazados {conteo(c.rechazados)}
        </Text>
      )}
      <Group justify="flex-end">
        <Button onClick={() => alVer(c.id)}>{terminado(c) ? 'Ver el detalle' : 'Seguir en segundo plano'}</Button>
      </Group>
    </Stack>
  );
}
