import {
  Alert,
  Badge,
  Button,
  Checkbox,
  Group,
  Modal,
  PasswordInput,
  ScrollArea,
  Select,
  SimpleGrid,
  Stack,
  Switch,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState, type FormEvent } from 'react';
import { errorTelefono } from '../shared/alertas';
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
  sesionAbierta: boolean;
  claveInicial: boolean;
}

interface Datos {
  usuarios: Usuario[];
  perfiles: Perfil[];
  fuerzas: { codigo: string; nombre: string }[];
  puntos: { id: number; nombre: string; sigla: string | null; fuerza: string }[];
  regionales: { id: number; nombre: string }[];
}

const SANIDAD = 6;
/** ESM (7) necesita su establecimiento y OPL regional (9) su regional. */
const ESM = 7;
const OPL_REGIONAL = 9;

const VACIO = {
  id: 0,
  nombre: '',
  documento: '',
  telefono: '',
  /** El teléfono ya guardado: se conserva aunque no sea un móvil (usuarios anteriores a la validación). */
  telefonoGuardado: null as string | null,
  telefonoTocado: false,
  usuario: '',
  correo: '',
  perfiles: [] as number[],
  alcanceFuerza: '',
  alcancePunto: '',
  alcanceRegional: '',
  conClaveInicial: false,
  claveInicial: '',
};
type Formulario = typeof VACIO;

/** Usuarios (Administrador). Anexo 5, prueba 2: se crean solo desde aquí; la contraseña la define cada quien con su enlace. */
export function Usuarios() {
  const qc = useQueryClient();
  const datos = useQuery({ queryKey: ['usuarios'], queryFn: () => api<Datos>('/api/usuarios') });
  const [form, setForm] = useState<Formulario | null>(null);
  const [error, setError] = useState<string | null>(null);
  /** Hay un botón del puntero presionado dentro del formulario (ver el onBlur del teléfono). */
  const punteroPresionado = useRef(false);
  const marcarTelefonoTocado = () => setForm((f) => (f ? { ...f, telefonoTocado: true } : f));
  const [claveDe, setClaveDe] = useState<Usuario | null>(null);
  const [claveNueva, setClaveNueva] = useState('');
  const [verPerfiles, setVerPerfiles] = useState(false);
  const [nombresPerfiles, setNombresPerfiles] = useState<Record<number, string>>({});

  const guardar = useMutation({
    mutationFn: (f: Formulario) =>
      api<{ mensaje: string; enlaceLocal?: string; correoOk?: boolean }>(f.id ? `/api/usuarios/${f.id}` : '/api/usuarios', {
        metodo: f.id ? 'PUT' : 'POST',
        cuerpo: {
          id: f.id,
          nombre: f.nombre,
          usuario: f.usuario,
          correo: f.correo,
          perfiles: f.perfiles,
          alcanceFuerza: f.alcanceFuerza || null,
          alcancePunto: f.alcancePunto ? Number(f.alcancePunto) : null,
          alcanceRegional: f.alcanceRegional ? Number(f.alcanceRegional) : null,
          documento: f.documento || null,
          telefono: f.telefono || null,
          claveInicial: !f.id && f.conClaveInicial ? f.claveInicial : null,
        },
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
    mutationFn: ({ ruta, metodo, cuerpo }: { ruta: string; metodo: string; cuerpo?: unknown }) => api<{ mensaje: string }>(ruta, { metodo, cuerpo }),
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
  const esEsm = form?.perfiles.includes(ESM);
  const esOplRegional = form?.perfiles.includes(OPL_REGIONAL);
  // Error del teléfono en el campo: el del servidor si lo menciona; si no, la misma regla del backend (reglas/Alertas) al salir del campo
  const errorDelTelefono =
    (error && /tel[eé]fono/i.test(error) ? error : null) ?? (form?.telefonoTocado ? errorTelefono(form.telefono, form.telefonoGuardado) : null);

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={3}>Usuarios</Title>
        <Group gap="xs">
          <Button
            variant="default"
            onClick={() => {
              setNombresPerfiles(Object.fromEntries(perfiles.map((p) => [p.id, p.nombre])));
              setVerPerfiles(true);
            }}
          >
            Nombres de perfiles
          </Button>
          <Button
            onClick={() => {
              setError(null);
              setForm({ ...VACIO });
            }}
          >
            Nuevo usuario
          </Button>
        </Group>
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
                  <Group gap={4}>
                    <Badge color={u.activo ? (u.bloqueado ? 'orange' : 'green') : 'gray'}>
                      {u.activo ? (u.bloqueado ? 'Bloqueado' : 'Activo') : 'Inactivo'}
                    </Badge>
                    {u.sesionAbierta && (
                      <Badge color="blue" variant="outline">
                        Sesión abierta
                      </Badge>
                    )}
                    {u.claveInicial && (
                      <Badge color="grape" variant="outline">
                        Contraseña inicial
                      </Badge>
                    )}
                  </Group>
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
                          telefonoGuardado: u.telefono,
                          telefonoTocado: false,
                          usuario: u.usuario,
                          correo: u.correo,
                          perfiles: u.perfiles,
                          alcanceFuerza: u.alcanceFuerza ?? '',
                          alcancePunto: u.alcancePunto ? String(u.alcancePunto) : '',
                          alcanceRegional: u.alcanceRegional ? String(u.alcanceRegional) : '',
                          conClaveInicial: false,
                          claveInicial: '',
                        });
                      }}
                    >
                      Editar
                    </Button>
                    <Button
                      size="xs"
                      variant="light"
                      color={u.activo ? 'gray' : 'green'}
                      disabled={accion.isPending}
                      aria-label={(u.activo ? 'Inactivar a ' : 'Activar a ') + u.nombre}
                      onClick={() =>
                        window.confirm(`¿${u.activo ? 'Inactivar' : 'Activar'} a ${u.nombre}?`) &&
                        accion.mutate({ ruta: `/api/usuarios/${u.id}/estado`, metodo: 'PUT', cuerpo: { activo: !u.activo } })
                      }
                    >
                      {u.activo ? 'Inactivar' : 'Activar'}
                    </Button>
                    <Button
                      size="xs"
                      variant="light"
                      color="orange"
                      disabled={accion.isPending}
                      aria-label={'Restablecer la contraseña de ' + u.nombre}
                      onClick={() =>
                        window.confirm(`¿Enviar a ${u.nombre} un enlace para definir una contraseña nueva?`) &&
                        accion.mutate({ ruta: `/api/usuarios/${u.id}/restablecer`, metodo: 'POST' })
                      }
                    >
                      Restablecer contraseña
                    </Button>
                    <Button
                      size="xs"
                      variant="light"
                      color="grape"
                      disabled={accion.isPending}
                      aria-label={'Asignar contraseña inicial a ' + u.nombre}
                      onClick={() => {
                        setClaveNueva('');
                        setClaveDe(u);
                      }}
                    >
                      Contraseña inicial
                    </Button>
                    {u.sesionAbierta && (
                      <Button
                        size="xs"
                        variant="light"
                        color="red"
                        disabled={accion.isPending}
                        aria-label={'Cerrar la sesión de ' + u.nombre}
                        onClick={() =>
                          window.confirm(`¿Cerrar la sesión abierta de ${u.nombre}?`) &&
                          accion.mutate({ ruta: `/api/usuarios/${u.id}/cerrar-sesion`, metodo: 'POST' })
                        }
                      >
                        Cerrar sesión
                      </Button>
                    )}
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </ScrollArea>

      <Modal opened={form !== null} onClose={() => setForm(null)} title={form?.id ? 'Editar usuario' : 'Nuevo usuario'} size="lg">
        {form && (
          <form
            onSubmit={enviar}
            onPointerDownCapture={() => {
              punteroPresionado.current = true;
              window.addEventListener('pointerup', () => (punteroPresionado.current = false), { once: true, capture: true });
            }}
          >
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
              <TextInput
                label="Correo"
                type="email"
                required
                value={form.correo}
                onChange={(e) => setForm({ ...form, correo: e.currentTarget.value })}
              />
              <SimpleGrid cols={{ base: 1, sm: 2 }}>
                <TextInput label="Documento" value={form.documento} onChange={(e) => setForm({ ...form, documento: e.currentTarget.value })} />
                <TextInput
                  label="Teléfono móvil"
                  type="tel"
                  inputMode="tel"
                  autoComplete="off"
                  placeholder="+57 3xx xxx xxxx"
                  description="Móvil colombiano (+57 y 10 dígitos que empiezan por 3). Recibe por SMS las alertas críticas si su perfil es destinatario."
                  value={form.telefono}
                  error={errorDelTelefono}
                  data-testid="campo-telefono"
                  onChange={(e) => {
                    if (error && /tel[eé]fono/i.test(error)) setError(null);
                    setForm({ ...form, telefono: e.currentTarget.value });
                  }}
                  onBlur={() => {
                    // Si el foco sale con un clic (p. ej. en un perfil), el error se muestra al soltar: mostrarlo en el mousedown desplazaba
                    // el formulario y el clic caía en otro sitio, así que la casilla no cambiaba
                    if (punteroPresionado.current) window.addEventListener('pointerup', () => setTimeout(marcarTelefonoTocado, 0), { once: true });
                    else marcarTelefonoTocado();
                  }}
                />
              </SimpleGrid>
              <Checkbox.Group label="Perfiles" value={form.perfiles.map(String)} onChange={(v) => setForm({ ...form, perfiles: v.map(Number) })}>
                <Stack gap={6} mt={6}>
                  {perfiles.map((p) => (
                    <Checkbox key={p.id} value={String(p.id)} label={p.nombre} description={p.descripcion} />
                  ))}
                </Stack>
              </Checkbox.Group>
              {esEsm && (
                <Select
                  label="Establecimiento (ESM)"
                  required
                  searchable
                  data={(datos.data?.puntos ?? []).map((p) => ({
                    value: String(p.id),
                    label: `${p.sigla ? p.sigla + ' · ' : ''}${p.nombre} (${p.fuerza})`,
                  }))}
                  value={form.alcancePunto || null}
                  onChange={(v) => setForm({ ...form, alcancePunto: v ?? '' })}
                />
              )}
              {esOplRegional && (
                <Select
                  label="Regional (OPL regional)"
                  required
                  data={(datos.data?.regionales ?? []).map((r) => ({ value: String(r.id), label: r.nombre }))}
                  value={form.alcanceRegional || null}
                  onChange={(v) => setForm({ ...form, alcanceRegional: v ?? '' })}
                />
              )}
              {esSanidad && (
                <Select
                  label="Fuerza (Sanidad)"
                  required
                  data={(datos.data?.fuerzas ?? []).map((f) => ({ value: f.codigo, label: f.nombre }))}
                  value={form.alcanceFuerza || null}
                  onChange={(v) => setForm({ ...form, alcanceFuerza: v ?? '' })}
                />
              )}
              {!form.id && (
                <Switch
                  label="Asignar contraseña inicial (se cambia al primer ingreso)"
                  checked={form.conClaveInicial}
                  onChange={(e) => setForm({ ...form, conClaveInicial: e.currentTarget.checked })}
                />
              )}
              {!form.id && form.conClaveInicial && (
                <PasswordInput
                  label="Contraseña inicial"
                  description="Mínimo 12 caracteres, sin tildes ni ñ. El usuario deberá cambiarla en su primer ingreso."
                  required
                  autoComplete="new-password"
                  maxLength={64}
                  value={form.claveInicial}
                  onChange={(e) => setForm({ ...form, claveInicial: e.currentTarget.value })}
                />
              )}
              <Text size="xs" c="dimmed">
                {!form.id && form.conClaveInicial
                  ? 'Entregue la contraseña inicial al usuario por un canal seguro. No se envía correo.'
                  : 'Al crear el usuario se le envía un enlace de un solo uso (60 minutos) para definir su contraseña. Nadie más la conoce.'}
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

      <Modal opened={claveDe !== null} onClose={() => setClaveDe(null)} title="Asignar contraseña inicial">
        {claveDe && (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              accion.mutate(
                { ruta: `/api/usuarios/${claveDe.id}/clave-inicial`, metodo: 'POST', cuerpo: { clave: claveNueva } },
                { onSuccess: () => setClaveDe(null) },
              );
            }}
          >
            <Stack>
              <Text size="sm">{claveDe.nombre} deberá cambiarla en su próximo ingreso. Si tiene una sesión abierta, se cierra.</Text>
              <PasswordInput
                label="Contraseña inicial"
                required
                autoComplete="new-password"
                maxLength={64}
                value={claveNueva}
                onChange={(e) => setClaveNueva(e.currentTarget.value)}
              />
              <Group justify="flex-end">
                <Button variant="default" onClick={() => setClaveDe(null)}>
                  Cancelar
                </Button>
                <Button type="submit" loading={accion.isPending}>
                  Asignar
                </Button>
              </Group>
            </Stack>
          </form>
        )}
      </Modal>

      <Modal opened={verPerfiles} onClose={() => setVerPerfiles(false)} title="Nombres de perfiles" size="lg">
        <Stack>
          <Text size="xs" c="dimmed">
            Los nombres son configurables mientras llega la adenda con el catálogo final de perfiles. Los permisos no cambian.
          </Text>
          {perfiles.map((p) => (
            <Group key={p.id} wrap="nowrap" align="flex-end">
              <TextInput
                label={`Perfil ${p.id}`}
                style={{ flex: 1 }}
                maxLength={60}
                value={nombresPerfiles[p.id] ?? ''}
                onChange={(e) => {
                  const v = e.currentTarget.value;
                  setNombresPerfiles((m) => ({ ...m, [p.id]: v }));
                }}
              />
              <Button
                variant="light"
                disabled={accion.isPending || (nombresPerfiles[p.id] ?? '') === p.nombre}
                onClick={() =>
                  accion.mutate({
                    ruta: `/api/usuarios/perfiles/${p.id}`,
                    metodo: 'PUT',
                    cuerpo: { nombre: nombresPerfiles[p.id], descripcion: p.descripcion },
                  })
                }
              >
                Guardar
              </Button>
            </Group>
          ))}
        </Stack>
      </Modal>
    </Stack>
  );
}
