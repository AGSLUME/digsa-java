import { Alert, Badge, Button, Checkbox, Group, Modal, ScrollArea, Select, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { api, ErrorApi } from '../shared/api';

interface Perfil {
  id: number;
  nombre: string;
  descripcion: string;
}

interface Usuario {
  id: number;
  nombre: string;
  documento: string | null;
  telefono: string | null;
  usuario: string;
  correo: string;
  activo: boolean;
  ultimoIngreso: string | null;
  bloqueado: boolean;
  perfiles: number[];
  alcanceFuerza: string | null;
  alcancePunto: number | null;
  alcanceRegional: number | null;
}

interface Datos {
  usuarios: Usuario[];
  perfiles: Perfil[];
  fuerzas: { codigo: string; nombre: string }[];
}

/** ESM (7) y OPL regional (9) necesitan su punto o su regional, que llegan con los maestros. */
const PENDIENTES_S2 = [7, 9];
const SANIDAD = 6;

const VACIO = { id: 0, nombre: '', documento: '', telefono: '', usuario: '', correo: '', perfiles: [] as number[], alcanceFuerza: '' };
type Formulario = typeof VACIO;

/** Usuarios (Administrador). Anexo 5, prueba 2: se crean solo desde aquí; la contraseña la define cada quien con su enlace. */
export function Usuarios() {
  const qc = useQueryClient();
  const datos = useQuery({ queryKey: ['usuarios'], queryFn: () => api<Datos>('/api/usuarios') });
  const [form, setForm] = useState<Formulario | null>(null);
  const [error, setError] = useState<string | null>(null);

  const guardar = useMutation({
    mutationFn: (f: Formulario) =>
      api<{ mensaje: string; enlaceLocal?: string; correoOk?: boolean }>(f.id ? `/api/usuarios/${f.id}` : '/api/usuarios', {
        metodo: f.id ? 'PUT' : 'POST',
        cuerpo: { ...f, alcanceFuerza: f.alcanceFuerza || null, documento: f.documento || null, telefono: f.telefono || null },
      }),
    onSuccess: (r) => {
      // Si el correo de bienvenida no salió, el aviso no se cierra solo: el usuario no podrá entrar hasta «Restablecer contraseña»
      const sinCorreo = r.correoOk === false;
      notifications.show({
        color: sinCorreo ? 'orange' : 'green',
        autoClose: sinCorreo ? false : 6000,
        message: r.mensaje + (r.enlaceLocal ? ` (local: ${r.enlaceLocal})` : ''),
      });
      setForm(null);
      qc.invalidateQueries({ queryKey: ['usuarios'] });
    },
    onError: (e) => setError(e instanceof ErrorApi ? e.message : 'No se pudo guardar.'),
  });

  const accion = useMutation({
    mutationFn: ({ ruta, metodo, cuerpo }: { ruta: string; metodo: string; cuerpo?: unknown }) =>
      api<{ mensaje: string }>(ruta, { metodo, cuerpo }),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      qc.invalidateQueries({ queryKey: ['usuarios'] });
    },
    onError: (e) => notifications.show({ color: 'red', message: e instanceof ErrorApi ? e.message : 'No se pudo completar la acción.' }),
  });

  const enviar = (e: FormEvent) => {
    e.preventDefault();
    if (!form) return;
    setError(null);
    guardar.mutate(form);
  };

  const perfiles = datos.data?.perfiles ?? [];
  const nombrePerfil = (id: number) => perfiles.find((p) => p.id === id)?.nombre ?? String(id);
  const esSanidad = form?.perfiles.includes(SANIDAD);

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={3}>Usuarios</Title>
        <Button
          onClick={() => {
            setError(null);
            setForm({ ...VACIO });
          }}
        >
          Nuevo usuario
        </Button>
      </Group>
      {datos.error && <Alert color="red">{(datos.error as Error).message}</Alert>}
      <ScrollArea type="auto">
        <Table striped highlightOnHover miw={760}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Nombre</Table.Th>
              <Table.Th>Usuario</Table.Th>
              <Table.Th>Correo</Table.Th>
              <Table.Th>Perfiles</Table.Th>
              <Table.Th>Último ingreso</Table.Th>
              <Table.Th>Estado</Table.Th>
              <Table.Th>Acciones</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {datos.data?.usuarios.map((u) => (
              <Table.Tr key={u.id} data-testid={'usuario-' + u.usuario}>
                <Table.Td>{u.nombre}</Table.Td>
                <Table.Td>{u.usuario}</Table.Td>
                <Table.Td>{u.correo}</Table.Td>
                <Table.Td>
                  <Group gap={4}>
                    {u.perfiles.map((p) => (
                      <Badge key={p} variant="light">
                        {nombrePerfil(p)}
                      </Badge>
                    ))}
                  </Group>
                </Table.Td>
                <Table.Td>{u.ultimoIngreso ?? '—'}</Table.Td>
                <Table.Td>
                  <Badge color={u.activo ? (u.bloqueado ? 'orange' : 'green') : 'gray'}>{u.activo ? (u.bloqueado ? 'Bloqueado' : 'Activo') : 'Inactivo'}</Badge>
                </Table.Td>
                <Table.Td>
                  <Group gap={4} wrap="nowrap">
                    <Button
                      size="xs"
                      variant="light"
                      onClick={() => {
                        setError(null);
                        setForm({
                          id: u.id,
                          nombre: u.nombre,
                          documento: u.documento ?? '',
                          telefono: u.telefono ?? '',
                          usuario: u.usuario,
                          correo: u.correo,
                          perfiles: u.perfiles,
                          alcanceFuerza: u.alcanceFuerza ?? '',
                        });
                      }}
                    >
                      Editar
                    </Button>
                    <Button size="xs" variant="light" color={u.activo ? 'gray' : 'green'} disabled={accion.isPending}
                      aria-label={(u.activo ? 'Inactivar a ' : 'Activar a ') + u.nombre}
                      onClick={() => window.confirm(`¿${u.activo ? 'Inactivar' : 'Activar'} a ${u.nombre}?`) && accion.mutate({ ruta: `/api/usuarios/${u.id}/estado`, metodo: 'PUT', cuerpo: { activo: !u.activo } })}>
                      {u.activo ? 'Inactivar' : 'Activar'}
                    </Button>
                    <Button size="xs" variant="light" color="orange" disabled={accion.isPending} aria-label={'Restablecer la contraseña de ' + u.nombre}
                      onClick={() => window.confirm(`¿Enviar a ${u.nombre} un enlace para definir una contraseña nueva?`) && accion.mutate({ ruta: `/api/usuarios/${u.id}/restablecer`, metodo: 'POST' })}>
                      Restablecer contraseña
                    </Button>
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </ScrollArea>

      <Modal opened={form !== null} onClose={() => setForm(null)} title={form?.id ? 'Editar usuario' : 'Nuevo usuario'} size="lg">
        {form && (
          <form onSubmit={enviar}>
            <Stack>
              {error && (
                <Alert color="red" data-testid="error-usuario">
                  {error}
                </Alert>
              )}
              <TextInput label="Nombre completo" required value={form.nombre} onChange={(e) => setForm({ ...form, nombre: e.currentTarget.value })} />
              <TextInput
                label="Usuario"
                description="Minúsculas sin tildes, números, punto, guion o guion bajo (3 a 50)."
                required
                value={form.usuario}
                onChange={(e) => setForm({ ...form, usuario: e.currentTarget.value.toLowerCase() })}
              />
              <TextInput label="Correo" type="email" required value={form.correo} onChange={(e) => setForm({ ...form, correo: e.currentTarget.value })} />
              <Group grow>
                <TextInput label="Documento" value={form.documento} onChange={(e) => setForm({ ...form, documento: e.currentTarget.value })} />
                <TextInput label="Teléfono" value={form.telefono} onChange={(e) => setForm({ ...form, telefono: e.currentTarget.value })} />
              </Group>
              <Checkbox.Group label="Perfiles" value={form.perfiles.map(String)} onChange={(v) => setForm({ ...form, perfiles: v.map(Number) })}>
                <Stack gap={6} mt={6}>
                  {perfiles.map((p) => (
                    <Checkbox
                      key={p.id}
                      value={String(p.id)}
                      label={p.nombre}
                      disabled={PENDIENTES_S2.includes(p.id)}
                      description={PENDIENTES_S2.includes(p.id) ? 'Disponible cuando se carguen los puntos y las regionales.' : p.descripcion}
                    />
                  ))}
                </Stack>
              </Checkbox.Group>
              {esSanidad && (
                <Select
                  label="Fuerza (Sanidad)"
                  required
                  data={(datos.data?.fuerzas ?? []).map((f) => ({ value: f.codigo, label: f.nombre }))}
                  value={form.alcanceFuerza || null}
                  onChange={(v) => setForm({ ...form, alcanceFuerza: v ?? '' })}
                />
              )}
              <Text size="xs" c="dimmed">
                Al crear el usuario se le envía un enlace de un solo uso (60 minutos) para definir su contraseña. Nadie más la conoce.
              </Text>
              <Group justify="flex-end">
                <Button variant="default" onClick={() => setForm(null)}>
                  Cancelar
                </Button>
                <Button type="submit" loading={guardar.isPending}>
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
