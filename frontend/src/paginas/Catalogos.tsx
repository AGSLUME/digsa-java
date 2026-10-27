import { Alert, Badge, Button, Center, Group, Loader, Modal, ScrollArea, Select, Stack, Switch, Table, Tabs, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { separarCorreos } from '../shared/agenda';
import { api } from '../shared/api';
import { EncabezadoPagina } from '../shared/EncabezadoPagina';
import { useValidacion } from '../shared/validacion';
import { errorCodigoFuerza, errorCodigoValor, errorCorreos, errorLargo } from '../shared/validaciones';

export interface Fuerza {
  codigo: string;
  nombre: string;
  sanidad: string;
  correos: string[];
  activo: boolean;
}

export interface ValorCatalogo {
  id: number;
  codigo: string;
  descripcion: string;
  activo: boolean;
}

export interface CatalogoGenerico {
  codigo: string;
  nombre: string;
  descripcion: string | null;
  patron: string | null;
  valores: ValorCatalogo[];
}

export interface Regional {
  id: number;
  nombre: string;
  activo: boolean;
}

export interface Parametro {
  clave: string;
  valor: string;
  descripcion: string | null;
  fuente: string | null;
  editable: boolean;
}

export interface CodigoNombre {
  codigo: string;
  nombre: string;
}

export interface DatosCatalogos {
  fuerzas: Fuerza[];
  catalogos: CatalogoGenerico[];
  regionales: Regional[];
  parametros: Parametro[];
  unidades: CodigoNombre[];
  modalidades: string[];
  tiposVisita: { codigo: string; nombre: string; unidad: string | null; modalidad: string | null; aviso: string }[];
  departamentos: CodigoNombre[];
}

interface Mensaje {
  mensaje: string;
}

interface Peticion {
  ruta: string;
  metodo: string;
  cuerpo?: unknown;
}

/** Mutación común: aviso verde con el mensaje del servidor y recarga de los catálogos. */
function useAccion() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ ruta, metodo, cuerpo }: Peticion) => api<Mensaje>(ruta, { metodo, cuerpo }),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      qc.invalidateQueries({ queryKey: ['catalogos'] });
    },
  });
}

const NO_SE_PUDO = 'No se pudo completar la acción.';

/** Catálogos y parámetros (coordinador y administrador): fuerzas, catálogos genéricos, regionales y parámetros del Anexo 3. */
export function Catalogos() {
  const datos = useQuery({ queryKey: ['catalogos'], queryFn: () => api<DatosCatalogos>('/api/catalogos') });

  return (
    <Stack>
      <EncabezadoPagina titulo="Catálogos" fuente="Anexo 5 · 1.14, 2.3" />
      {datos.error && <Alert color="red">{datos.error.message}</Alert>}
      {datos.isLoading && (
        <Center py="xl">
          <Loader />
        </Center>
      )}
      {datos.data && (
        <Tabs defaultValue="fuerzas" keepMounted={false}>
          <ScrollArea type="auto" scrollbarSize={4}>
            <Tabs.List style={{ flexWrap: 'nowrap' }}>
              <Tabs.Tab value="fuerzas">Fuerzas</Tabs.Tab>
              {datos.data.catalogos.map((c) => (
                <Tabs.Tab key={c.codigo} value={'cat-' + c.codigo}>
                  {c.nombre}
                </Tabs.Tab>
              ))}
              <Tabs.Tab value="regionales">Regionales</Tabs.Tab>
              <Tabs.Tab value="parametros">Parámetros</Tabs.Tab>
            </Tabs.List>
          </ScrollArea>
          <Tabs.Panel value="fuerzas" pt="md">
            <Fuerzas fuerzas={datos.data.fuerzas} />
          </Tabs.Panel>
          {datos.data.catalogos.map((c) => (
            <Tabs.Panel key={c.codigo} value={'cat-' + c.codigo} pt="md">
              <Generico catalogo={c} />
            </Tabs.Panel>
          ))}
          <Tabs.Panel value="regionales" pt="md">
            <Regionales regionales={datos.data.regionales} />
          </Tabs.Panel>
          <Tabs.Panel value="parametros" pt="md">
            <Parametros parametros={datos.data.parametros} />
          </Tabs.Panel>
        </Tabs>
      )}
    </Stack>
  );
}

interface FormFuerza {
  nuevo: boolean;
  codigo: string;
  nombre: string;
  sanidad: string;
  correos: string;
  activo: boolean;
}

function Fuerzas({ fuerzas }: { fuerzas: Fuerza[] }) {
  const accion = useAccion();
  const [form, setForm] = useState<FormFuerza | null>(null);
  const v = useValidacion({ etiquetas: { codigo: 'Código', nombre: 'Nombre', sanidad: 'Sanidad', correos: 'Correos' }, testid: 'error-catalogo' });
  // Errores de las acciones de la tabla (eliminar): persistentes encima de la tabla
  const lista = useValidacion({ testid: 'error-fuerzas' });

  const abrir = (f: FormFuerza) => {
    v.reiniciar();
    setForm(f);
  };
  const cerrar = () => {
    v.reiniciar();
    setForm(null);
  };
  const cambiar = (campo: keyof FormFuerza, cambio: Partial<FormFuerza>) => {
    if (form) setForm({ ...form, ...cambio });
    v.limpiar(campo);
  };
  const enviar = (e: FormEvent) => {
    e.preventDefault();
    if (!form) return;
    const ok = v.validar({
      codigo: form.nuevo ? errorCodigoFuerza(form.codigo) : null,
      nombre: errorLargo(form.nombre, 2, 120, 'Escriba el nombre.', 'El nombre de la fuerza debe tener de 2 a 120 caracteres.'),
      sanidad: form.sanidad ? null : 'Elija la sanidad.',
      correos: errorCorreos(form.correos),
    });
    if (!ok) return;
    const cuerpo = { nombre: form.nombre, sanidad: form.sanidad, correos: separarCorreos(form.correos), activo: form.activo };
    accion.mutate(
      form.nuevo
        ? { ruta: '/api/catalogos/fuerzas', metodo: 'POST', cuerpo: { codigo: form.codigo, ...cuerpo } }
        : { ruta: `/api/catalogos/fuerzas/${encodeURIComponent(form.codigo)}`, metodo: 'PUT', cuerpo },
      { onSuccess: cerrar, onError: (x) => v.errorServidor(x, 'No se pudo guardar.') },
    );
  };

  return (
    <Stack ref={lista.ref}>
      {lista.resumen}
      <Group justify="flex-end">
        <Button onClick={() => abrir({ nuevo: true, codigo: '', nombre: '', sanidad: 'DISAN', correos: '', activo: true })}>Nueva fuerza</Button>
      </Group>
      <ScrollArea type="auto">
        <Table striped miw={720} data-testid="tabla-fuerzas">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Código</Table.Th>
              <Table.Th>Nombre</Table.Th>
              <Table.Th>Sanidad</Table.Th>
              <Table.Th>Correos</Table.Th>
              <Table.Th>Estado</Table.Th>
              <Table.Th>Acciones</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {fuerzas.map((f) => (
              <Table.Tr key={f.codigo} data-testid={'fila-fuerza-' + f.codigo}>
                <Table.Td>{f.codigo}</Table.Td>
                <Table.Td>{f.nombre}</Table.Td>
                <Table.Td>{f.sanidad}</Table.Td>
                <Table.Td style={{ wordBreak: 'break-all' }}>{f.correos.join(', ') || '—'}</Table.Td>
                <Table.Td>
                  <Badge color={f.activo ? 'green' : 'gray'}>{f.activo ? 'Activa' : 'Inactiva'}</Badge>
                </Table.Td>
                <Table.Td>
                  <Group gap={4} wrap="nowrap">
                    <Button
                      size="xs"
                      variant="light"
                      aria-label={'Editar la fuerza ' + f.codigo}
                      onClick={() => abrir({ nuevo: false, codigo: f.codigo, nombre: f.nombre, sanidad: f.sanidad, correos: f.correos.join(', '), activo: f.activo })}
                    >
                      Editar
                    </Button>
                    <Button
                      size="xs"
                      variant="light"
                      color="red"
                      disabled={accion.isPending}
                      aria-label={'Eliminar la fuerza ' + f.codigo}
                      onClick={() =>
                        window.confirm(`¿Eliminar la fuerza ${f.nombre}?`) &&
                        accion.mutate(
                          { ruta: `/api/catalogos/fuerzas/${encodeURIComponent(f.codigo)}`, metodo: 'DELETE' },
                          { onSuccess: lista.reiniciar, onError: (x) => lista.errorServidor(x, NO_SE_PUDO) },
                        )
                      }
                    >
                      Eliminar
                    </Button>
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </ScrollArea>
      <Modal opened={form !== null} onClose={cerrar} title={form?.nuevo ? 'Nueva fuerza' : 'Editar fuerza'}>
        {form && (
          <form ref={v.ref} onSubmit={enviar} noValidate>
            <Stack>
              {v.resumen}
              <TextInput
                label="Código"
                description="De 2 a 5 letras mayúsculas."
                required
                readOnly={!form.nuevo}
                maxLength={5}
                value={form.codigo}
                error={v.error('codigo')}
                onChange={(e) => cambiar('codigo', { codigo: e.currentTarget.value.toUpperCase().replace(/[^A-Z]/g, '') })}
              />
              <TextInput
                label="Nombre"
                required
                value={form.nombre}
                error={v.error('nombre')}
                onChange={(e) => cambiar('nombre', { nombre: e.currentTarget.value })}
              />
              <Select
                label="Sanidad"
                required
                allowDeselect={false}
                data={['DISAN', 'JEFSA']}
                value={form.sanidad}
                error={v.error('sanidad')}
                onChange={(x) => cambiar('sanidad', { sanidad: x ?? 'DISAN' })}
              />
              <TextInput
                label="Correos"
                description="Separados por coma."
                value={form.correos}
                error={v.error('correos')}
                onChange={(e) => cambiar('correos', { correos: e.currentTarget.value })}
              />
              {!form.nuevo && <Switch label="Activa" checked={form.activo} onChange={(e) => setForm({ ...form, activo: e.currentTarget.checked })} />}
              <Group justify="flex-end">
                <Button variant="default" onClick={cerrar}>
                  Cancelar
                </Button>
                <Button type="submit" loading={accion.isPending}>
                  Guardar
                </Button>
              </Group>
            </Stack>
          </form>
        )}
      </Modal>
    </Stack>
  );
}

interface FormValor {
  id: number;
  codigo: string;
  descripcion: string;
  activo: boolean;
}

function Generico({ catalogo }: { catalogo: CatalogoGenerico }) {
  const accion = useAccion();
  const [form, setForm] = useState<FormValor | null>(null);
  const v = useValidacion({ etiquetas: { codigo: 'Código', descripcion: 'Descripción' }, testid: 'error-catalogo' });
  const lista = useValidacion({ testid: 'error-valores' });

  const abrir = (f: FormValor) => {
    v.reiniciar();
    setForm(f);
  };
  const cerrar = () => {
    v.reiniciar();
    setForm(null);
  };
  const enviar = (e: FormEvent) => {
    e.preventDefault();
    if (!form) return;
    const ok = v.validar({
      codigo: errorCodigoValor(catalogo, form.codigo),
      descripcion: errorLargo(form.descripcion, 2, 120, 'Escriba la descripción.', 'La descripción debe tener de 2 a 120 caracteres.'),
    });
    if (!ok) return;
    accion.mutate(
      form.id
        ? { ruta: `/api/catalogos/valores/${form.id}`, metodo: 'PUT', cuerpo: { codigo: form.codigo, descripcion: form.descripcion, activo: form.activo } }
        : { ruta: `/api/catalogos/${encodeURIComponent(catalogo.codigo)}/valores`, metodo: 'POST', cuerpo: { codigo: form.codigo, descripcion: form.descripcion } },
      { onSuccess: cerrar, onError: (x) => v.errorServidor(x, 'No se pudo guardar.') },
    );
  };

  return (
    <Stack ref={lista.ref}>
      {lista.resumen}
      <Group justify="space-between">
        <div>
          {catalogo.descripcion && <Text size="sm">{catalogo.descripcion}</Text>}
          {catalogo.patron && (
            <Text size="xs" c="dimmed">
              Formato del código: {catalogo.patron}
            </Text>
          )}
        </div>
        <Button onClick={() => abrir({ id: 0, codigo: '', descripcion: '', activo: true })}>
          Nuevo valor
        </Button>
      </Group>
      <ScrollArea type="auto">
        <Table striped miw={560} data-testid={'tabla-catalogo-' + catalogo.codigo}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Código</Table.Th>
              <Table.Th>Descripción</Table.Th>
              <Table.Th>Estado</Table.Th>
              <Table.Th>Acciones</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {catalogo.valores.map((x) => (
              <Table.Tr key={x.id}>
                <Table.Td>{x.codigo}</Table.Td>
                <Table.Td>{x.descripcion}</Table.Td>
                <Table.Td>
                  <Badge color={x.activo ? 'green' : 'gray'}>{x.activo ? 'Activo' : 'Inactivo'}</Badge>
                </Table.Td>
                <Table.Td>
                  <Group gap={4} wrap="nowrap">
                    <Button
                      size="xs"
                      variant="light"
                      aria-label={'Editar ' + x.codigo}
                      onClick={() => abrir({ id: x.id, codigo: x.codigo, descripcion: x.descripcion, activo: x.activo })}
                    >
                      Editar
                    </Button>
                    <Button
                      size="xs"
                      variant="light"
                      color="red"
                      disabled={accion.isPending}
                      aria-label={'Eliminar ' + x.codigo}
                      onClick={() =>
                        window.confirm(`¿Eliminar ${x.codigo} · ${x.descripcion}?`) &&
                        accion.mutate(
                          { ruta: `/api/catalogos/valores/${x.id}`, metodo: 'DELETE' },
                          { onSuccess: lista.reiniciar, onError: (e) => lista.errorServidor(e, NO_SE_PUDO) },
                        )
                      }
                    >
                      Eliminar
                    </Button>
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </ScrollArea>
      {catalogo.valores.length === 0 && <Text c="dimmed">Sin valores.</Text>}
      <Modal opened={form !== null} onClose={cerrar} title={form?.id ? 'Editar valor' : 'Nuevo valor'}>
        {form && (
          <form ref={v.ref} onSubmit={enviar} noValidate>
            <Stack>
              {v.resumen}
              <TextInput
                label="Código"
                required
                description={catalogo.patron ? `Formato: ${catalogo.patron}` : undefined}
                value={form.codigo}
                error={v.error('codigo')}
                onChange={(e) => {
                  setForm({ ...form, codigo: e.currentTarget.value });
                  v.limpiar('codigo');
                }}
              />
              <TextInput
                label="Descripción"
                required
                value={form.descripcion}
                error={v.error('descripcion')}
                onChange={(e) => {
                  setForm({ ...form, descripcion: e.currentTarget.value });
                  v.limpiar('descripcion');
                }}
              />
              {form.id > 0 && <Switch label="Activo" checked={form.activo} onChange={(e) => setForm({ ...form, activo: e.currentTarget.checked })} />}
              <Group justify="flex-end">
                <Button variant="default" onClick={cerrar}>
                  Cancelar
                </Button>
                <Button type="submit" loading={accion.isPending}>
                  Guardar
                </Button>
              </Group>
            </Stack>
          </form>
        )}
      </Modal>
    </Stack>
  );
}

const errorRegional = (nombre: string) =>
  errorLargo(nombre, 2, 80, 'Escriba el nombre de la regional.', 'El nombre de la regional debe tener de 2 a 80 caracteres.');

function Regionales({ regionales }: { regionales: Regional[] }) {
  const accion = useAccion();
  const [nueva, setNueva] = useState('');
  const [nombres, setNombres] = useState<Record<number, string>>({});
  // Un aviso para la sección: la nueva regional y el nombre de cada una (campo «r<id>»)
  const v = useValidacion({
    etiquetas: { nueva: 'Nueva regional', ...Object.fromEntries(regionales.map((r) => ['r' + r.id, 'Nombre de la regional ' + r.nombre])) },
    testid: 'error-regionales',
  });

  return (
    <Stack ref={v.ref}>
      <Text size="sm" c="dimmed">
        Las regionales no se borran: se inactivan.
      </Text>
      {v.resumen}
      <form
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          if (!v.validar({ nueva: errorRegional(nueva) })) return;
          accion.mutate(
            { ruta: '/api/catalogos/regionales', metodo: 'POST', cuerpo: { nombre: nueva } },
            {
              onSuccess: () => {
                setNueva('');
                v.reiniciar();
              },
              onError: (x) => v.errorServidor(x, NO_SE_PUDO),
            },
          );
        }}
      >
        <Group align="flex-end" wrap="nowrap">
          <TextInput
            label="Nueva regional"
            required
            style={{ flex: 1 }}
            value={nueva}
            error={v.error('nueva')}
            onChange={(e) => {
              setNueva(e.currentTarget.value);
              v.limpiar('nueva');
            }}
          />
          <Button type="submit" loading={accion.isPending}>
            Agregar
          </Button>
        </Group>
      </form>
      {regionales.map((r) => {
        const nombre = nombres[r.id] ?? r.nombre;
        return (
          <Group key={r.id} align="center" wrap="wrap" gap="xs" data-testid={'regional-' + r.id}>
            <TextInput
              aria-label={'Nombre de la regional ' + r.nombre}
              style={{ flex: 1, minWidth: 160 }}
              value={nombre}
              error={v.error('r' + r.id)}
              onChange={(e) => {
                const x = e.currentTarget.value;
                setNombres((m) => ({ ...m, [r.id]: x }));
                v.limpiar('r' + r.id);
              }}
            />
            <Switch
              label="Activa"
              checked={r.activo}
              disabled={accion.isPending}
              onChange={(e) =>
                accion.mutate(
                  { ruta: `/api/catalogos/regionales/${r.id}`, metodo: 'PUT', cuerpo: { nombre: r.nombre, activo: e.currentTarget.checked } },
                  { onSuccess: v.reiniciar, onError: (x) => v.errorServidor(x, NO_SE_PUDO) },
                )
              }
            />
            <Button
              size="xs"
              variant="light"
              aria-label={'Guardar la regional ' + r.nombre}
              disabled={accion.isPending || nombre === r.nombre}
              onClick={() => {
                if (!v.validar({ ['r' + r.id]: errorRegional(nombre) })) return;
                accion.mutate(
                  { ruta: `/api/catalogos/regionales/${r.id}`, metodo: 'PUT', cuerpo: { nombre, activo: r.activo } },
                  {
                    onSuccess: () => {
                      v.reiniciar();
                      setNombres((m) => {
                        const c = { ...m };
                        delete c[r.id];
                        return c;
                      });
                    },
                    onError: (x) => v.errorServidor(x, NO_SE_PUDO),
                  },
                );
              }}
            >
              Guardar
            </Button>
          </Group>
        );
      })}
    </Stack>
  );
}

function Parametros({ parametros }: { parametros: Parametro[] }) {
  const accion = useAccion();
  const [valores, setValores] = useState<Record<string, string>>({});
  const v = useValidacion({ testid: 'error-parametros' });
  // Parámetro cuyo último guardado falló: queda marcado hasta corregirlo (el servidor no dice el campo)
  const [fallo, setFallo] = useState<string | null>(null);

  return (
    <Stack ref={v.ref}>
      {v.resumen}
      <ScrollArea type="auto">
        <Table striped miw={720} data-testid="tabla-parametros">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Parámetro</Table.Th>
              <Table.Th>Valor</Table.Th>
              <Table.Th>Fuente</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {parametros.map((p) => {
              const valor = valores[p.clave] ?? p.valor;
              return (
                <Table.Tr key={p.clave}>
                  <Table.Td>
                    <Text size="sm" fw={500}>
                      {p.clave}
                    </Text>
                    <Text size="xs" c="dimmed">
                      {p.descripcion}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    {p.editable ? (
                      <Group gap={4} wrap="nowrap">
                        <TextInput
                          size="xs"
                          aria-label={'Valor de ' + p.clave}
                          value={valor}
                          error={fallo === p.clave && v.hayErrores}
                          onChange={(e) => {
                            const x = e.currentTarget.value;
                            setValores((m) => ({ ...m, [p.clave]: x }));
                            if (fallo === p.clave) setFallo(null);
                          }}
                        />
                        <Button
                          size="xs"
                          variant="light"
                          aria-label={'Guardar ' + p.clave}
                          disabled={accion.isPending || valor === p.valor}
                          onClick={() =>
                            accion.mutate(
                              { ruta: `/api/catalogos/parametros/${encodeURIComponent(p.clave)}`, metodo: 'PUT', cuerpo: { valor } },
                              {
                                onSuccess: () => {
                                  setFallo(null);
                                  v.reiniciar();
                                },
                                onError: (x) => {
                                  setFallo(p.clave);
                                  v.errorServidor(x, NO_SE_PUDO);
                                },
                              },
                            )
                          }
                        >
                          Guardar
                        </Button>
                      </Group>
                    ) : (
                      <Group gap={6}>
                        <Text size="sm">{p.valor}</Text>
                        <Badge variant="outline" color="gray">
                          Fijo (Anexo 3)
                        </Badge>
                      </Group>
                    )}
                  </Table.Td>
                  <Table.Td>{p.fuente ?? '—'}</Table.Td>
                </Table.Tr>
              );
            })}
          </Table.Tbody>
        </Table>
      </ScrollArea>
    </Stack>
  );
}
