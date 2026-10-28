import { Alert, Badge, Button, Checkbox, Group, Modal, NumberInput, PasswordInput, Select, SimpleGrid, Stack, Switch, Table, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useRef, useState, type FormEvent } from 'react';
import { errorTelefono } from '../shared/alertas';
import { api, ErrorApi } from '../shared/api';
import { conTexto, errorClave, errorCorreo } from '../shared/validaciones';
import { RolesAdicionales } from './RolesAdicionales';
import { EncabezadoPagina } from '../shared/EncabezadoPagina';
import { fechaHora } from '../shared/formato';
import { Paginacion, useIrAFila, usePaginacion } from '../shared/paginacion';
import { useValidacion } from '../shared/validacion';
import { TablaAdaptable } from '../shared/TablaAdaptable';

interface Perfil {
  id: number;
  nombre: string;
  descripcion: string;
  /** Rol adicional (Anexo 5, 1.12): su perfil base; nulo en los perfiles base. */
  baseId: number | null;
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

const ETIQUETAS_USUARIO = {
  nombre: 'Nombre completo',
  usuario: 'Usuario',
  correo: 'Correo',
  documento: 'Documento',
  telefono: 'Teléfono móvil',
  perfiles: 'Perfiles',
  alcancePunto: 'Establecimiento (ESM)',
  alcanceRegional: 'Regional (OPL regional)',
  alcanceFuerza: 'Fuerza (Sanidad)',
  claveInicial: 'Contraseña inicial',
};
/** Las mismas reglas del servidor (ServicioUsuarios), que ya dicen la descripción del campo y su `type="email"`. */
const USUARIO = /^[a-z0-9._-]{3,50}$/;

/** El error con el campo que el formulario debe marcar cuando el servidor no lo dice en su 422 (p. ej. el teléfono). */
function conCampo(e: unknown, campo: string, si: (e: ErrorApi) => boolean): unknown {
  return e instanceof ErrorApi && si(e) ? new ErrorApi(e.estado, e.message, { ...e.datos, campo }) : e;
}

/** Usuarios (Administrador). Anexo 5, prueba 2: se crean solo desde aquí; la contraseña la define cada quien con su enlace. */
export function Usuarios() {
  const qc = useQueryClient();
  const datos = useQuery({ queryKey: ['usuarios'], queryFn: () => api<Datos>('/api/usuarios') });
  const [form, setForm] = useState<Formulario | null>(null);
  const v = useValidacion({ etiquetas: ETIQUETAS_USUARIO, testid: 'error-usuario' });
  /** Errores de las acciones de la tabla (activar, restablecer, desbloquear, cerrar sesión): quedan a la vista hasta la siguiente. */
  const vAccion = useValidacion({ testid: 'error-accion-usuario' });
  /** Hay un botón del puntero presionado dentro del formulario (ver el onBlur del teléfono). */
  const punteroPresionado = useRef(false);
  const marcarTelefonoTocado = () => setForm((f) => (f ? { ...f, telefonoTocado: true } : f));
  const [claveDe, setClaveDe] = useState<Usuario | null>(null);
  const [claveNueva, setClaveNueva] = useState('');
  const [verPerfiles, setVerPerfiles] = useState(false);
  const [verRoles, setVerRoles] = useState(false);
  const [verSeguridad, setVerSeguridad] = useState(false);
  /** Lo escrito en el TIME OUT; sin tocar (undefined) se muestra el valor guardado. */
  const [minutos, setMinutos] = useState<number | string | undefined>(undefined);
  const [nombresPerfiles, setNombresPerfiles] = useState<Record<number, string>>({});
  const vClave = useValidacion({ etiquetas: { clave: 'Contraseña inicial' }, testid: 'error-clave-inicial' });
  const vSeguridad = useValidacion({
    etiquetas: { minutos: 'Tiempo de inactividad (TIME OUT)' },
    campos: { minutosSesion: 'minutos' },
    testid: 'error-seguridad',
  });

  // Con cientos de usuarios la tabla completa hace lenta la pantalla: búsqueda y 25 por página en el navegador (el servidor manda todos)
  const [buscar, setBuscar] = useState('');
  const filtrados = useMemo(() => {
    const t = buscar.trim().toLocaleLowerCase('es');
    const todos = datos.data?.usuarios ?? [];
    return t ? todos.filter((u) => [u.nombre, u.usuario, u.correo].some((x) => (x ?? '').toLocaleLowerCase('es').includes(t))) : todos;
  }, [datos.data, buscar]);
  const pag = usePaginacion(filtrados);
  // Después de crear o editar, la tabla va a la página del usuario (si la búsqueda lo oculta, se quita la búsqueda)
  const [irAUsuario, setIrAUsuario] = useState<string | null>(null);
  useIrAFila(filtrados, irAUsuario, (u) => u.usuario, pag.irA, () => setIrAUsuario(null));
  if (irAUsuario !== null && buscar && !filtrados.some((u) => u.usuario === irAUsuario) && datos.data?.usuarios.some((u) => u.usuario === irAUsuario)) {
    setBuscar('');
  }

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
    onSuccess: (r, f) => {
      setIrAUsuario(f.usuario.trim());
      // Si el correo de bienvenida no salió, el aviso no se cierra solo: el usuario no podrá entrar hasta «Restablecer contraseña»
      const sinCorreo = r.correoOk === false;
      notifications.show({
        color: sinCorreo ? 'orange' : 'green',
        autoClose: sinCorreo ? false : 6000,
        message: r.mensaje + (r.enlaceLocal ? ` (local: ${r.enlaceLocal})` : ''),
      });
      v.reiniciar();
      setForm(null);
      qc.invalidateQueries({ queryKey: ['usuarios'] });
    },
    // El 422 del teléfono trae «campo» (UI-21): el resumen lo nombra y el campo queda marcado
    onError: (e) => v.errorServidor(e, 'No se pudo guardar.'),
  });

  // Cada llamada dice dónde va su error (tabla, contraseña inicial o nombres de perfiles): queda a la vista, no en un aviso que se va solo
  const accion = useMutation({
    mutationFn: ({ ruta, metodo, cuerpo }: { ruta: string; metodo: string; cuerpo?: unknown }) => api<{ mensaje: string }>(ruta, { metodo, cuerpo }),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      qc.invalidateQueries({ queryKey: ['usuarios'] });
    },
  });
  const accionTabla = (ruta: string, metodo: string, cuerpo?: unknown) =>
    accion.mutate({ ruta, metodo, cuerpo }, { onSuccess: () => vAccion.reiniciar(), onError: (e) => vAccion.errorServidor(e, 'No se pudo completar la acción.') });

  const seguridad = useQuery({
    queryKey: ['usuarios-seguridad'],
    queryFn: () => api<{ minutosSesion: number; minutosMaximo: number }>('/api/usuarios/seguridad'),
    enabled: verSeguridad,
  });
  const guardarSeguridad = useMutation({
    mutationFn: (m: number) => api<{ mensaje: string }>('/api/usuarios/seguridad', { metodo: 'PUT', cuerpo: { minutosSesion: m } }),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      setVerSeguridad(false);
      qc.invalidateQueries({ queryKey: ['usuarios-seguridad'] });
      qc.invalidateQueries({ queryKey: ['sesion'] });
    },
    onError: (e) => vSeguridad.errorServidor(e, 'No se pudo guardar.'),
  });

  const enviar = (e: FormEvent) => {
    e.preventDefault();
    if (!form) return;
    const usuario = form.usuario.trim();
    const correo = form.correo.trim();
    const ok = v.validar({
      nombre: form.nombre.trim() ? null : 'Escriba el nombre completo.',
      usuario: !usuario
        ? 'Escriba el usuario.'
        : USUARIO.test(usuario)
          ? null
          : 'El usuario debe tener de 3 a 50 caracteres: letras minúsculas sin tildes, números, punto, guion o guion bajo.',
      correo: errorCorreo(correo, 'Escriba un correo válido.', 'Escriba el correo.'),
      alcancePunto: esEsm && !form.alcancePunto ? 'Elija el establecimiento.' : null,
      alcanceRegional: esOplRegional && !form.alcanceRegional ? 'Elija la regional.' : null,
      alcanceFuerza: esSanidad && !form.alcanceFuerza ? 'Elija la fuerza.' : null,
      claveInicial: !form.id && form.conClaveInicial ? errorClave(form.claveInicial) : null,
      // El teléfono no detiene el envío: su error ya se ve al salir del campo y el servidor responde con su propio mensaje
    });
    if (ok) guardar.mutate(form);
  };

  const cerrarFormulario = () => {
    v.reiniciar();
    setForm(null);
  };
  const cerrarClave = () => {
    vClave.reiniciar();
    setClaveDe(null);
  };
  const cerrarSeguridad = () => {
    vSeguridad.reiniciar();
    setVerSeguridad(false);
  };

  const perfiles = datos.data?.perfiles ?? [];
  const nombrePerfil = (id: number) => perfiles.find((p) => p.id === id)?.nombre ?? String(id);
  // Un rol adicional pide el mismo dato de alcance que su perfil base
  const conBase = (base: number) => !!form?.perfiles.some((id) => id === base || perfiles.find((p) => p.id === id)?.baseId === base);
  const esSanidad = conBase(SANIDAD);
  const esEsm = conBase(ESM);
  const esOplRegional = conBase(OPL_REGIONAL);
  const perfilesBase = perfiles.filter((p) => p.baseId === null);
  // Error del teléfono en el campo: la misma regla del backend (reglas/Alertas) al salir del campo; si no, la marca del error del servidor
  // (su mensaje queda en el resumen, una sola vez)
  const errorDelTelefono = (form?.telefonoTocado ? errorTelefono(form.telefono, form.telefonoGuardado) : null) ?? v.error('telefono');
  const etiquetasPerfiles = Object.fromEntries(perfilesBase.map((p) => [`perfil${p.id}`, p.nombre]));
  const vPerfiles = useValidacion({ etiquetas: etiquetasPerfiles, testid: 'error-nombres-perfiles' });

  return (
    <Stack>
      <EncabezadoPagina
        titulo="Usuarios"
        fuente="Anexo 5 · 1.10–1.12, 3.1–3.7"
        acciones={
          <>
            <Button
              variant="default"
              onClick={() => {
                setMinutos(undefined);
                vSeguridad.reiniciar();
                setVerSeguridad(true);
              }}
            >
              Seguridad
            </Button>
            <Button
              variant="default"
              onClick={() => {
                setNombresPerfiles(Object.fromEntries(perfilesBase.map((p) => [p.id, p.nombre])));
                vPerfiles.reiniciar();
                setVerPerfiles(true);
              }}
            >
              Nombres de perfiles
            </Button>
            <Button variant="default" onClick={() => setVerRoles(true)}>
              Roles adicionales
            </Button>
            <Button
              onClick={() => {
                v.reiniciar();
                setForm({ ...VACIO });
              }}
            >
              Nuevo usuario
            </Button>
          </>
        }
      />
      {datos.error && <Alert color="red">{(datos.error as Error).message}</Alert>}
      {vAccion.resumen}
      <TextInput
        label="Buscar"
        placeholder="Nombre, usuario o correo"
        maw={420}
        value={buscar}
        onChange={(e) => {
          setBuscar(e.currentTarget.value);
          pag.setPagina(1);
        }}
      />
      <TablaAdaptable>
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
            {pag.visibles.map((u) => (
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
                <Table.Td>{fechaHora(u.ultimoIngreso)}</Table.Td>
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
                        v.reiniciar();
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
                        accionTabla(`/api/usuarios/${u.id}/estado`, 'PUT', { activo: !u.activo })
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
                        accionTabla(`/api/usuarios/${u.id}/restablecer`, 'POST')
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
                        vClave.reiniciar();
                        setClaveDe(u);
                      }}
                    >
                      Contraseña inicial
                    </Button>
                    {u.bloqueado && (
                      <Button
                        size="xs"
                        variant="light"
                        color="orange"
                        disabled={accion.isPending}
                        aria-label={'Desbloquear a ' + u.nombre}
                        onClick={() => accionTabla(`/api/usuarios/${u.id}/desbloquear`, 'POST')}
                      >
                        Desbloquear
                      </Button>
                    )}
                    {u.sesionAbierta && (
                      <Button
                        size="xs"
                        variant="light"
                        color="red"
                        disabled={accion.isPending}
                        aria-label={'Cerrar la sesión de ' + u.nombre}
                        onClick={() =>
                          window.confirm(`¿Cerrar la sesión abierta de ${u.nombre}?`) &&
                          accionTabla(`/api/usuarios/${u.id}/cerrar-sesion`, 'POST')
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
      </TablaAdaptable>
      <Paginacion total={filtrados.length} pagina={pag.pagina} paginas={pag.paginas} alCambiar={pag.setPagina} nombre="usuarios" />

      <Modal opened={form !== null} onClose={cerrarFormulario} title={form?.id ? 'Editar usuario' : 'Nuevo usuario'} size="lg">
        {form && (
          <form
            ref={v.ref}
            noValidate
            onSubmit={enviar}
            onPointerDownCapture={() => {
              punteroPresionado.current = true;
              window.addEventListener('pointerup', () => (punteroPresionado.current = false), { once: true, capture: true });
            }}
          >
            <Stack>
              {v.resumen}
              <TextInput
                label="Nombre completo"
                required
                value={form.nombre}
                error={v.error('nombre')}
                onChange={(e) => {
                  setForm({ ...form, nombre: e.currentTarget.value });
                  v.limpiar('nombre');
                }}
              />
              <TextInput
                label="Usuario"
                description="Minúsculas sin tildes, números, punto, guion o guion bajo (3 a 50)."
                required
                value={form.usuario}
                error={v.error('usuario')}
                onChange={(e) => {
                  setForm({ ...form, usuario: e.currentTarget.value.toLowerCase() });
                  v.limpiar('usuario');
                }}
              />
              <TextInput
                label="Correo"
                type="email"
                required
                value={form.correo}
                error={v.error('correo')}
                onChange={(e) => {
                  setForm({ ...form, correo: e.currentTarget.value });
                  v.limpiar('correo');
                }}
              />
              <SimpleGrid cols={{ base: 1, sm: 2 }}>
                <TextInput
                  label="Documento"
                  value={form.documento}
                  error={v.error('documento')}
                  onChange={(e) => {
                    setForm({ ...form, documento: e.currentTarget.value });
                    v.limpiar('documento');
                  }}
                />
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
                    v.limpiar('telefono');
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
              <Checkbox.Group
                label="Perfiles"
                value={form.perfiles.map(String)}
                error={conTexto(v.error('perfiles'))}
                onChange={(x) => {
                  setForm({ ...form, perfiles: x.map(Number) });
                  v.limpiar('perfiles');
                }}
              >
                <Stack gap={6} mt={6}>
                  {perfiles.map((p) => (
                    <Checkbox
                      key={p.id}
                      value={String(p.id)}
                      label={p.baseId === null ? p.nombre : `${p.nombre} (rol adicional · base ${nombrePerfil(p.baseId)})`}
                      description={p.descripcion}
                    />
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
                  error={v.error('alcancePunto')}
                  onChange={(x) => {
                    setForm({ ...form, alcancePunto: x ?? '' });
                    v.limpiar('alcancePunto');
                  }}
                />
              )}
              {esOplRegional && (
                <Select
                  label="Regional (OPL regional)"
                  required
                  data={(datos.data?.regionales ?? []).map((r) => ({ value: String(r.id), label: r.nombre }))}
                  value={form.alcanceRegional || null}
                  error={v.error('alcanceRegional')}
                  onChange={(x) => {
                    setForm({ ...form, alcanceRegional: x ?? '' });
                    v.limpiar('alcanceRegional');
                  }}
                />
              )}
              {esSanidad && (
                <Select
                  label="Fuerza (Sanidad)"
                  required
                  data={(datos.data?.fuerzas ?? []).map((f) => ({ value: f.codigo, label: f.nombre }))}
                  value={form.alcanceFuerza || null}
                  error={v.error('alcanceFuerza')}
                  onChange={(x) => {
                    setForm({ ...form, alcanceFuerza: x ?? '' });
                    v.limpiar('alcanceFuerza');
                  }}
                />
              )}
              {!form.id && (
                <Switch
                  label="Asignar contraseña inicial (se cambia al primer ingreso)"
                  checked={form.conClaveInicial}
                  onChange={(e) => {
                    setForm({ ...form, conClaveInicial: e.currentTarget.checked });
                    v.limpiar('claveInicial');
                  }}
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
                  error={v.error('claveInicial')}
                  onChange={(e) => {
                    setForm({ ...form, claveInicial: e.currentTarget.value });
                    v.limpiar('claveInicial');
                  }}
                />
              )}
              <Text size="xs" c="dimmed">
                {!form.id && form.conClaveInicial
                  ? 'Entregue la contraseña inicial al usuario por un canal seguro. No se envía correo.'
                  : 'Al crear el usuario se le envía un enlace de un solo uso (60 minutos) para definir su contraseña. Nadie más la conoce.'}
              </Text>
              <Group justify="flex-end">
                <Button variant="default" onClick={cerrarFormulario}>
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

      <Modal opened={claveDe !== null} onClose={cerrarClave} title="Asignar contraseña inicial">
        {claveDe && (
          <form
            ref={vClave.ref}
            noValidate
            onSubmit={(e) => {
              e.preventDefault();
              if (!vClave.validar({ clave: errorClave(claveNueva) })) return;
              accion.mutate(
                { ruta: `/api/usuarios/${claveDe.id}/clave-inicial`, metodo: 'POST', cuerpo: { clave: claveNueva } },
                { onSuccess: cerrarClave, onError: (x) => vClave.errorServidor(x, 'No se pudo asignar la contraseña inicial.') },
              );
            }}
          >
            <Stack>
              <Text size="sm">{claveDe.nombre} deberá cambiarla en su próximo ingreso. Si tiene una sesión abierta, se cierra.</Text>
              {vClave.resumen}
              <PasswordInput
                label="Contraseña inicial"
                description="Mínimo 12 caracteres, sin tildes ni ñ."
                required
                autoComplete="new-password"
                maxLength={64}
                value={claveNueva}
                error={vClave.error('clave')}
                onChange={(e) => {
                  setClaveNueva(e.currentTarget.value);
                  vClave.limpiar('clave');
                }}
              />
              <Group justify="flex-end">
                <Button variant="default" onClick={cerrarClave}>
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

      <Modal opened={verSeguridad} onClose={cerrarSeguridad} title="Seguridad">
        <form
          ref={vSeguridad.ref}
          noValidate
          onSubmit={(e) => {
            e.preventDefault();
            const m = minutos ?? seguridad.data?.minutosSesion;
            const maximo = seguridad.data?.minutosMaximo ?? 480;
            const valido = typeof m === 'number' && Number.isInteger(m) && m >= 1 && m <= maximo;
            if (!vSeguridad.validar({ minutos: valido ? null : `Escriba un número entero de minutos, de 1 a ${maximo}.` })) return;
            if (typeof m === 'number') guardarSeguridad.mutate(m);
          }}
        >
          <Stack>
            {seguridad.error && <Alert color="red">{(seguridad.error as Error).message}</Alert>}
            {vSeguridad.resumen}
            <NumberInput
              label="Tiempo de inactividad (TIME OUT), en minutos"
              description="Anexo 5, 3.2: la sesión se cierra sola tras este tiempo sin actividad. Aplica desde la siguiente acción de cada usuario."
              min={1}
              max={seguridad.data?.minutosMaximo ?? 480}
              allowDecimal={false}
              allowNegative={false}
              required
              value={minutos ?? seguridad.data?.minutosSesion ?? ''}
              error={vSeguridad.error('minutos')}
              onChange={(x) => {
                setMinutos(x);
                vSeguridad.limpiar('minutos');
              }}
              data-testid="minutos-sesion"
            />
            <Group justify="flex-end">
              <Button variant="default" onClick={cerrarSeguridad}>
                Cancelar
              </Button>
              <Button type="submit" loading={guardarSeguridad.isPending} disabled={!seguridad.data}>
                Guardar
              </Button>
            </Group>
          </Stack>
        </form>
      </Modal>

      <Modal
        opened={verPerfiles}
        onClose={() => {
          vPerfiles.reiniciar();
          setVerPerfiles(false);
        }}
        title="Nombres de perfiles"
        size="lg"
      >
        <Stack ref={vPerfiles.ref}>
          <Text size="xs" c="dimmed">
            Los nombres son configurables mientras llega la adenda con el catálogo final de perfiles. Los permisos no cambian.
          </Text>
          {vPerfiles.resumen}
          {perfilesBase.map((p) => (
            <Group key={p.id} wrap="nowrap" align="flex-end">
              <TextInput
                label={p.nombre}
                style={{ flex: 1 }}
                maxLength={60}
                value={nombresPerfiles[p.id] ?? ''}
                error={vPerfiles.error(`perfil${p.id}`)}
                onChange={(e) => {
                  const valor = e.currentTarget.value;
                  setNombresPerfiles((m) => ({ ...m, [p.id]: valor }));
                  vPerfiles.limpiar(`perfil${p.id}`);
                }}
              />
              <Button
                variant="light"
                disabled={accion.isPending || (nombresPerfiles[p.id] ?? '') === p.nombre}
                onClick={() => {
                  const nombre = nombresPerfiles[p.id] ?? '';
                  if (!vPerfiles.validar({ [`perfil${p.id}`]: nombre.trim() ? null : 'Escriba el nombre del perfil.' })) return;
                  accion.mutate(
                    { ruta: `/api/usuarios/perfiles/${p.id}`, metodo: 'PUT', cuerpo: { nombre: nombresPerfiles[p.id], descripcion: p.descripcion } },
                    {
                      onSuccess: () => vPerfiles.reiniciar(),
                      // Un 422 sobre «nombre» marca el campo de este perfil
                      onError: (x) =>
                        vPerfiles.errorServidor(conCampo(x, `perfil${p.id}`, (y) => y.datos.campo === 'nombre'), 'No se pudo guardar el nombre.'),
                    },
                  );
                }}
              >
                Guardar
              </Button>
            </Group>
          ))}
        </Stack>
      </Modal>

      <RolesAdicionales abierto={verRoles} alCerrar={() => setVerRoles(false)} />
    </Stack>
  );
}
