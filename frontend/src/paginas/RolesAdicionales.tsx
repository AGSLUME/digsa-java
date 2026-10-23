import { Alert, Badge, Button, Checkbox, Group, Modal, ScrollArea, Select, Stack, Table, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { api, ErrorApi } from '../shared/api';

interface PantallaElegible {
  codigo: string;
  titulo: string;
}

interface Base {
  id: number;
  nombre: string;
  pantallas: PantallaElegible[];
}

interface Rol {
  id: number;
  nombre: string;
  descripcion: string | null;
  baseId: number;
  activo: boolean;
  pantallas: string[];
  usuarios: number;
}

interface Catalogo {
  bases: Base[];
  roles: Rol[];
}

interface Formulario {
  id: number;
  nombre: string;
  descripcion: string;
  baseId: string;
  pantallas: string[];
}

const VACIO: Formulario = { id: 0, nombre: '', descripcion: '', baseId: '', pantallas: [] };

/**
 * Roles adicionales parametrizables (Anexo 5, 1.12): el administrador crea un rol con un perfil base (fija el alcance de los datos y las
 * reglas) y escoge cuáles pantallas de ese perfil ve. El perfil base no se cambia después: cambiaría el alcance de quienes ya lo tienen.
 */
export function RolesAdicionales({ abierto, alCerrar }: { abierto: boolean; alCerrar: () => void }) {
  const qc = useQueryClient();
  const catalogo = useQuery({ queryKey: ['roles-adicionales'], queryFn: () => api<Catalogo>('/api/usuarios/roles'), enabled: abierto });
  const [form, setForm] = useState<Formulario | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refrescar = () => {
    qc.invalidateQueries({ queryKey: ['roles-adicionales'] });
    qc.invalidateQueries({ queryKey: ['usuarios'] });
  };

  const guardar = useMutation({
    mutationFn: (f: Formulario) =>
      api<{ mensaje: string }>(f.id ? `/api/usuarios/roles/${f.id}` : '/api/usuarios/roles', {
        metodo: f.id ? 'PUT' : 'POST',
        cuerpo: { nombre: f.nombre, descripcion: f.descripcion || null, baseId: Number(f.baseId), pantallas: f.pantallas },
      }),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      setForm(null);
      refrescar();
    },
    onError: (e) => setError(e instanceof ErrorApi ? e.message : 'No se pudo guardar el rol.'),
  });

  const estado = useMutation({
    mutationFn: (r: Rol) => api<{ mensaje: string }>(`/api/usuarios/roles/${r.id}/estado`, { metodo: 'PUT', cuerpo: { activo: !r.activo } }),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      refrescar();
    },
    onError: (e) => notifications.show({ color: 'red', message: e instanceof ErrorApi ? e.message : 'No se pudo cambiar el estado.' }),
  });

  const bases = catalogo.data?.bases ?? [];
  const nombreBase = (id: number) => bases.find((b) => b.id === id)?.nombre ?? String(id);
  const elegibles = bases.find((b) => String(b.id) === form?.baseId)?.pantallas ?? [];
  const tituloPantalla = (baseId: number, codigo: string) =>
    bases.find((b) => b.id === baseId)?.pantallas.find((p) => p.codigo === codigo)?.titulo ?? codigo;

  const cerrar = () => {
    setForm(null);
    setError(null);
    alCerrar();
  };

  const enviar = (e: FormEvent) => {
    e.preventDefault();
    if (!form) return;
    setError(null);
    guardar.mutate(form);
  };

  return (
    <Modal opened={abierto} onClose={cerrar} title="Roles adicionales" size="xl">
      <Stack>
        <Text size="sm" c="dimmed">
          Anexo 5, 1.12: roles que pida la DIGSA durante el contrato. Cada rol toma el alcance y las reglas de su perfil base y ve solo las pantallas
          marcadas. Se asignan en el formulario de cada usuario.
        </Text>
        {catalogo.error && <Alert color="red">{catalogo.error.message}</Alert>}
        <Group justify="flex-end">
          <Button
            onClick={() => {
              setError(null);
              setForm({ ...VACIO });
            }}
          >
            Nuevo rol
          </Button>
        </Group>
        <ScrollArea type="auto">
          <Table striped miw={640} data-testid="tabla-roles">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Rol</Table.Th>
                <Table.Th>Perfil base</Table.Th>
                <Table.Th>Pantallas</Table.Th>
                <Table.Th>Usuarios</Table.Th>
                <Table.Th>Acciones</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {catalogo.data?.roles.length === 0 && (
                <Table.Tr>
                  <Table.Td colSpan={5}>
                    <Text size="sm" c="dimmed">
                      Aún no hay roles adicionales.
                    </Text>
                  </Table.Td>
                </Table.Tr>
              )}
              {catalogo.data?.roles.map((r) => (
                <Table.Tr key={r.id} data-testid={'rol-' + r.nombre}>
                  <Table.Td>
                    <Text size="sm" fw={600}>
                      {r.nombre}
                    </Text>
                    {r.descripcion && (
                      <Text size="xs" c="dimmed">
                        {r.descripcion}
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td>{nombreBase(r.baseId)}</Table.Td>
                  <Table.Td>
                    <Group gap={4}>
                      {r.pantallas.map((p) => (
                        <Badge key={p} variant="light">
                          {tituloPantalla(r.baseId, p)}
                        </Badge>
                      ))}
                    </Group>
                  </Table.Td>
                  <Table.Td>{r.usuarios}</Table.Td>
                  <Table.Td>
                    <Group gap={4} wrap="nowrap">
                      <Button
                        size="xs"
                        variant="light"
                        aria-label={'Editar el rol ' + r.nombre}
                        onClick={() => {
                          setError(null);
                          setForm({ id: r.id, nombre: r.nombre, descripcion: r.descripcion ?? '', baseId: String(r.baseId), pantallas: r.pantallas });
                        }}
                      >
                        Editar
                      </Button>
                      <Button
                        size="xs"
                        variant="light"
                        color={r.activo ? 'gray' : 'green'}
                        disabled={estado.isPending}
                        aria-label={(r.activo ? 'Inactivar el rol ' : 'Activar el rol ') + r.nombre}
                        onClick={() =>
                          (r.activo
                            ? window.confirm(
                                `¿Inactivar el rol ${r.nombre}?` +
                                  (r.usuarios > 0 ? ` Sus ${r.usuarios} usuarios dejarán de ver sus pantallas.` : ''),
                              )
                            : true) && estado.mutate(r)
                        }
                      >
                        {r.activo ? 'Inactivar' : 'Activar'}
                      </Button>
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>

        {form && (
          <form onSubmit={enviar} data-testid="formulario-rol">
            <Stack>
              <Text fw={600}>{form.id ? 'Editar rol' : 'Nuevo rol'}</Text>
              {error && (
                <Alert color="red" data-testid="error-rol">
                  {error}
                </Alert>
              )}
              <TextInput
                label="Nombre del rol"
                required
                maxLength={60}
                value={form.nombre}
                onChange={(e) => setForm({ ...form, nombre: e.currentTarget.value })}
              />
              <TextInput
                label="Descripción"
                maxLength={200}
                value={form.descripcion}
                onChange={(e) => setForm({ ...form, descripcion: e.currentTarget.value })}
              />
              <Select
                label="Perfil base"
                description="Define el alcance de los datos y las reglas del rol. No se cambia después de crearlo."
                required
                disabled={form.id > 0}
                data={bases.map((b) => ({ value: String(b.id), label: b.nombre }))}
                value={form.baseId || null}
                onChange={(v) => setForm({ ...form, baseId: v ?? '', pantallas: [] })}
              />
              {form.baseId && (
                <Checkbox.Group label="Pantallas del rol" required value={form.pantallas} onChange={(v) => setForm({ ...form, pantallas: v })}>
                  <Stack gap={6} mt={6}>
                    {elegibles.map((p) => (
                      <Checkbox key={p.codigo} value={p.codigo} label={p.titulo} />
                    ))}
                  </Stack>
                </Checkbox.Group>
              )}
              <Group justify="flex-end">
                <Button variant="default" onClick={() => setForm(null)}>
                  Cancelar
                </Button>
                <Button type="submit" loading={guardar.isPending} disabled={form.pantallas.length === 0}>
                  Guardar rol
                </Button>
              </Group>
            </Stack>
          </form>
        )}
      </Stack>
    </Modal>
  );
}
