import { Alert, Badge, Button, Center, Checkbox, Group, Loader, Modal, ScrollArea, Select, SimpleGrid, Stack, Table, Tabs, Text, Textarea, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { separarCorreos } from '../shared/agenda';
import { api } from '../shared/api';
import type { CodigoNombre, DatosCatalogos } from './Catalogos';
import { EncabezadoPagina } from '../shared/EncabezadoPagina';
import { useValidacion, type Validacion } from '../shared/validacion';
import { errorCodigoObligacion, errorCorreos, errorLargo, errorNitOpl } from '../shared/validaciones';

interface Punto {
  id: number;
  sigla: string | null;
  nombre: string;
  unidad: string;
  fuerza: string;
  modalidad: string | null;
  nit: string | null;
  municipio: string | null;
  municipioNombre: string | null;
  departamento: string | null;
  direccion: string | null;
  nivel: string | null;
  horario: string | null;
  contacto: string | null;
  correos: string[];
  regionalId: number | null;
  regional: string | null;
  regionalOpl: string | null;
  llegaDesde: string | null;
  auditorAsignado: number | null;
  exigeQf: boolean;
  activo: boolean;
  fuente: string | null;
}

interface Auditor {
  id: number;
  nombre: string;
  usuario: string;
  documento: string | null;
  activo: boolean;
  rol: string | null;
  rolNombre: string | null;
  municipioBase: string | null;
  municipioNombre: string | null;
}

interface DatosAuditores {
  auditores: Auditor[];
  roles: Record<string, string>;
}

interface Opl {
  id: number;
  nombre: string;
  nit: string | null;
  correos: string[];
}

interface Obligacion {
  id: number;
  prestador: 'opl' | 'fa';
  codigo: string;
  texto: string;
  fuente: string | null;
  activo: boolean;
}

interface Mensaje {
  mensaje: string;
}

const PRESTADORES = [
  { value: 'opl', label: 'Operador logístico' },
  { value: 'fa', label: 'Farmacia alterna' },
];

const UNIDADES = ['ESM', 'FA', 'NC'];

/** Municipios de un departamento (se cargan al elegirlo). */
function useMunicipios(departamento: string | null) {
  return useQuery({
    queryKey: ['catalogos', 'municipios', departamento],
    queryFn: () => api<CodigoNombre[]>('/api/catalogos/municipios?departamento=' + encodeURIComponent(departamento ?? '')),
    enabled: !!departamento,
    staleTime: 600_000,
  });
}

/** Maestros (coordinador): puntos de atención, auditores, operador logístico y obligaciones del contrato. */
export function Maestros() {
  const catalogos = useQuery({ queryKey: ['catalogos'], queryFn: () => api<DatosCatalogos>('/api/catalogos') });
  return (
    <Stack>
      <EncabezadoPagina titulo="Maestros" fuente="Anexo 5 · 2.13" />
      {catalogos.error && <Alert color="red">No se pudieron cargar los catálogos: {catalogos.error.message}</Alert>}
      <Tabs defaultValue="puntos" keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="puntos">Puntos</Tabs.Tab>
          <Tabs.Tab value="auditores">Auditores</Tabs.Tab>
          <Tabs.Tab value="opl">Operador logístico</Tabs.Tab>
          <Tabs.Tab value="obligaciones">Obligaciones del contrato</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="puntos" pt="md">
          <Puntos catalogos={catalogos.data} />
        </Tabs.Panel>
        <Tabs.Panel value="auditores" pt="md">
          <Auditores departamentos={catalogos.data?.departamentos ?? []} />
        </Tabs.Panel>
        <Tabs.Panel value="opl" pt="md">
          <OperadorLogistico />
        </Tabs.Panel>
        <Tabs.Panel value="obligaciones" pt="md">
          <Obligaciones />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}

interface FormPunto {
  id: number;
  sigla: string;
  nombre: string;
  unidad: string | null;
  fuerza: string | null;
  modalidad: string | null;
  nit: string;
  departamento: string | null;
  municipio: string | null;
  direccion: string;
  nivel: string;
  horario: string;
  contacto: string;
  correos: string;
  regionalId: string | null;
  llegaDesde: string | null;
  auditorAsignado: number | null;
  exigeQf: boolean;
  regionalOpl: string | null;
}

const PUNTO_VACIO: FormPunto = {
  id: 0,
  sigla: '',
  nombre: '',
  unidad: null,
  fuerza: null,
  modalidad: null,
  nit: '',
  departamento: null,
  municipio: null,
  direccion: '',
  nivel: '',
  horario: '',
  contacto: '',
  correos: '',
  regionalId: null,
  llegaDesde: null,
  auditorAsignado: null,
  exigeQf: false,
  regionalOpl: null,
};

function aFormulario(p: Punto): FormPunto {
  return {
    id: p.id,
    sigla: p.sigla ?? '',
    nombre: p.nombre,
    unidad: p.unidad,
    fuerza: p.fuerza,
    modalidad: p.modalidad,
    nit: p.nit ?? '',
    departamento: p.municipio ? p.municipio.substring(0, 2) : null,
    municipio: p.municipio,
    direccion: p.direccion ?? '',
    nivel: p.nivel ?? '',
    horario: p.horario ?? '',
    contacto: p.contacto ?? '',
    correos: p.correos.join(', '),
    regionalId: p.regionalId ? String(p.regionalId) : null,
    llegaDesde: p.llegaDesde,
    auditorAsignado: p.auditorAsignado,
    exigeQf: p.exigeQf,
    regionalOpl: p.regionalOpl,
  };
}

function Puntos({ catalogos }: { catalogos: DatosCatalogos | undefined }) {
  const qc = useQueryClient();
  const [unidad, setUnidad] = useState<string | null>(null);
  const [fuerza, setFuerza] = useState<string | null>(null);
  const [activo, setActivo] = useState<string | null>(null);
  const [texto, setTexto] = useState('');
  const [aplicado, setAplicado] = useState('');
  const [form, setForm] = useState<FormPunto | null>(null);
  const v = useValidacion({
    etiquetas: { nombre: 'Nombre', unidad: 'Unidad', fuerza: 'Fuerza', modalidad: 'Modalidad', correos: 'Correos' },
    testid: 'error-punto',
  });
  // Errores al activar o inactivar desde la tabla: persistentes encima de la tabla
  const lista = useValidacion({ testid: 'error-puntos' });

  const filtros = (t: string) => {
    const p = new URLSearchParams();
    if (unidad) p.set('unidad', unidad);
    if (fuerza) p.set('fuerza', fuerza);
    if (activo) p.set('activo', activo);
    if (t.trim()) p.set('texto', t.trim());
    return p.toString();
  };
  const consulta = filtros(aplicado);
  const puntos = useQuery({ queryKey: ['maestros', 'puntos', consulta], queryFn: () => api<Punto[]>('/api/maestros/puntos?' + consulta) });
  const municipios = useMunicipios(form?.departamento ?? null);

  const guardar = useMutation({
    mutationFn: (f: FormPunto) =>
      api<Mensaje>(f.id ? `/api/maestros/puntos/${f.id}` : '/api/maestros/puntos', {
        metodo: f.id ? 'PUT' : 'POST',
        cuerpo: {
          sigla: f.sigla || null,
          nombre: f.nombre,
          unidad: f.unidad,
          fuerza: f.fuerza,
          modalidad: f.unidad === 'ESM' ? f.modalidad : null,
          nit: f.unidad === 'FA' ? f.nit || null : null,
          municipio: f.municipio,
          direccion: f.direccion || null,
          nivel: f.nivel || null,
          horario: f.horario || null,
          contacto: f.contacto || null,
          correos: separarCorreos(f.correos),
          regionalId: f.regionalId ? Number(f.regionalId) : null,
          llegaDesde: f.llegaDesde,
          auditorAsignado: f.auditorAsignado,
          exigeQf: f.exigeQf,
        },
      }),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      cerrar();
      qc.invalidateQueries({ queryKey: ['maestros'] });
      qc.invalidateQueries({ queryKey: ['agenda', 'opciones'] });
    },
    onError: (e) => v.errorServidor(e, 'No se pudo guardar el punto.'),
  });

  const estado = useMutation({
    mutationFn: (p: Punto) => api<Mensaje>(`/api/maestros/puntos/${p.id}/estado`, { metodo: 'PUT', cuerpo: { activo: !p.activo } }),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      lista.reiniciar();
      qc.invalidateQueries({ queryKey: ['maestros'] });
      qc.invalidateQueries({ queryKey: ['agenda', 'opciones'] });
    },
    onError: (e) => lista.errorServidor(e, 'No se pudo cambiar el estado.'),
  });

  const fuerzas = (catalogos?.fuerzas ?? []).map((f) => ({ value: f.codigo, label: `${f.codigo} · ${f.nombre}` }));
  function abrir(f: FormPunto) {
    v.reiniciar();
    setForm(f);
  }
  function cerrar() {
    v.reiniciar();
    setForm(null);
  }
  /** Cambia el formulario y quita el error del campo corregido. */
  const cambiar = (campo: keyof FormPunto, cambio: Partial<FormPunto>) => {
    if (form) setForm({ ...form, ...cambio });
    v.limpiar(campo);
  };
  const enviar = (e: FormEvent) => {
    e.preventDefault();
    if (!form) return;
    // Las mismas reglas del servidor (ServicioMaestros.validar)
    const ok = v.validar({
      nombre: errorLargo(form.nombre, 3, 200, 'Escriba el nombre.', 'El nombre debe tener de 3 a 200 caracteres.'),
      unidad: form.unidad ? null : 'Elija la unidad.',
      fuerza: form.fuerza || form.unidad === 'NC' ? null : 'Elija la fuerza.',
      modalidad:
        form.unidad === 'ESM' && !form.modalidad ? 'Elija la modalidad del ESM (Dispensación, Suministro, Consignación o Satélite).' : null,
      correos: errorCorreos(form.correos),
    });
    if (ok) guardar.mutate(form);
  };

  return (
    <Stack ref={lista.ref}>
      <SimpleGrid cols={{ base: 1, xs: 2, md: 5 }}>
        <Select label="Unidad" clearable data={UNIDADES} value={unidad} onChange={setUnidad} />
        <Select label="Fuerza" clearable data={fuerzas} value={fuerza} onChange={setFuerza} />
        <Select
          label="Estado"
          clearable
          data={[
            { value: 'true', label: 'Activos' },
            { value: 'false', label: 'Inactivos' },
          ]}
          value={activo}
          onChange={setActivo}
        />
        <TextInput
          label="Buscar"
          placeholder="Sigla o nombre"
          value={texto}
          onChange={(e) => setTexto(e.currentTarget.value)}
          onKeyDown={(e) => e.key === 'Enter' && setAplicado(texto)}
        />
        <Group align="flex-end" gap="xs">
          <Button variant="light" onClick={() => setAplicado(texto)}>
            Buscar
          </Button>
          <Button onClick={() => abrir({ ...PUNTO_VACIO })}>Nuevo punto</Button>
        </Group>
      </SimpleGrid>
      {lista.resumen}
      {puntos.error && <Alert color="red">{puntos.error.message}</Alert>}
      {puntos.isLoading ? (
        <Center py="xl">
          <Loader />
        </Center>
      ) : (
        <ScrollArea type="auto">
          <Table striped highlightOnHover miw={980} data-testid="tabla-puntos">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Sigla</Table.Th>
                <Table.Th>Nombre</Table.Th>
                <Table.Th>Unidad</Table.Th>
                <Table.Th>Fuerza</Table.Th>
                <Table.Th>Modalidad</Table.Th>
                <Table.Th>Municipio</Table.Th>
                <Table.Th>Exige QF</Table.Th>
                <Table.Th>Estado</Table.Th>
                <Table.Th>Acciones</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {(puntos.data ?? []).map((p) => (
                <Table.Tr key={p.id} data-testid={'fila-punto-' + p.id}>
                  <Table.Td>{p.sigla ?? '—'}</Table.Td>
                  <Table.Td>{p.nombre}</Table.Td>
                  <Table.Td>{p.unidad}</Table.Td>
                  <Table.Td>{p.fuerza}</Table.Td>
                  <Table.Td>{p.modalidad ?? '—'}</Table.Td>
                  <Table.Td>{p.municipioNombre ? `${p.municipioNombre}${p.departamento ? ' (' + p.departamento + ')' : ''}` : '—'}</Table.Td>
                  <Table.Td>{p.exigeQf ? <Badge color="grape">Sí</Badge> : <Text size="sm" c="dimmed">No</Text>}</Table.Td>
                  <Table.Td>
                    <Badge color={p.activo ? 'green' : 'gray'}>{p.activo ? 'Activo' : 'Inactivo'}</Badge>
                  </Table.Td>
                  <Table.Td>
                    <Group gap={4} wrap="nowrap">
                      <Button
                        size="xs"
                        variant="light"
                        aria-label={'Editar el punto ' + p.nombre}
                        onClick={() => abrir(aFormulario(p))}
                      >
                        Editar
                      </Button>
                      <Button
                        size="xs"
                        variant="light"
                        color={p.activo ? 'gray' : 'green'}
                        disabled={estado.isPending}
                        aria-label={(p.activo ? 'Inactivar el punto ' : 'Activar el punto ') + p.nombre}
                        onClick={() => window.confirm(`¿${p.activo ? 'Inactivar' : 'Activar'} ${p.nombre}?`) && estado.mutate(p)}
                      >
                        {p.activo ? 'Inactivar' : 'Activar'}
                      </Button>
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      )}
      {puntos.data && puntos.data.length === 0 && <Text c="dimmed">No hay puntos con estos filtros.</Text>}

      <Modal opened={form !== null} onClose={cerrar} title={form?.id ? 'Editar punto' : 'Nuevo punto'} size="lg">
        {form && (
          <form ref={v.ref} onSubmit={enviar} noValidate>
            <Stack>
              {v.resumen}
              <SimpleGrid cols={{ base: 1, xs: 2 }}>
                <TextInput label="Sigla" value={form.sigla} onChange={(e) => setForm({ ...form, sigla: e.currentTarget.value.toUpperCase() })} />
                <TextInput
                  label="Nombre"
                  required
                  value={form.nombre}
                  error={v.error('nombre')}
                  onChange={(e) => cambiar('nombre', { nombre: e.currentTarget.value })}
                />
                <Select
                  label="Unidad"
                  required
                  data={UNIDADES}
                  value={form.unidad}
                  error={v.error('unidad')}
                  onChange={(x) => {
                    cambiar('unidad', { unidad: x });
                    v.limpiar('modalidad');
                  }}
                />
                <Select
                  label="Fuerza"
                  required={form.unidad !== 'NC'}
                  data={fuerzas}
                  value={form.fuerza}
                  error={v.error('fuerza')}
                  onChange={(x) => cambiar('fuerza', { fuerza: x })}
                />
                {form.unidad === 'ESM' && (
                  <Select
                    label="Modalidad"
                    clearable
                    data={catalogos?.modalidades ?? []}
                    value={form.modalidad}
                    error={v.error('modalidad')}
                    onChange={(x) => cambiar('modalidad', { modalidad: x })}
                  />
                )}
                {form.unidad === 'FA' && <TextInput label="NIT" value={form.nit} onChange={(e) => setForm({ ...form, nit: e.currentTarget.value })} />}
                <Select
                  label="Departamento"
                  searchable
                  clearable
                  data={(catalogos?.departamentos ?? []).map((d) => ({ value: d.codigo, label: d.nombre }))}
                  value={form.departamento}
                  onChange={(x) => setForm({ ...form, departamento: x, municipio: null })}
                />
                <Select
                  label="Municipio"
                  searchable
                  clearable
                  disabled={!form.departamento}
                  placeholder={municipios.isLoading ? 'Cargando...' : undefined}
                  data={(municipios.data ?? []).map((m) => ({ value: m.codigo, label: m.nombre }))}
                  value={form.municipio}
                  onChange={(x) => setForm({ ...form, municipio: x })}
                />
              </SimpleGrid>
              {municipios.error && <Alert color="red">{municipios.error.message}</Alert>}
              <TextInput label="Dirección" value={form.direccion} onChange={(e) => setForm({ ...form, direccion: e.currentTarget.value })} />
              <SimpleGrid cols={{ base: 1, xs: 2 }}>
                <TextInput label="Nivel" value={form.nivel} onChange={(e) => setForm({ ...form, nivel: e.currentTarget.value })} />
                <TextInput label="Horario" value={form.horario} onChange={(e) => setForm({ ...form, horario: e.currentTarget.value })} />
              </SimpleGrid>
              <TextInput label="Contacto" value={form.contacto} onChange={(e) => setForm({ ...form, contacto: e.currentTarget.value })} />
              <TextInput
                label="Correos"
                description="Separados por coma."
                value={form.correos}
                error={v.error('correos')}
                onChange={(e) => cambiar('correos', { correos: e.currentTarget.value })}
              />
              <Select
                label="Regional"
                clearable
                data={(catalogos?.regionales ?? [])
                  .filter((r) => r.activo || String(r.id) === form.regionalId)
                  .map((r) => ({ value: String(r.id), label: r.nombre }))}
                value={form.regionalId}
                onChange={(x) => setForm({ ...form, regionalId: x })}
              />
              {form.regionalOpl && (
                <Text size="sm" c="dimmed">
                  Regional del OPL: {form.regionalOpl}
                </Text>
              )}
              <Checkbox
                label="Exige químico farmacéutico (Anexo 3 p.9)"
                checked={form.exigeQf}
                onChange={(e) => setForm({ ...form, exigeQf: e.currentTarget.checked })}
              />
              <Group justify="flex-end">
                <Button variant="default" onClick={cerrar}>
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

function Auditores({ departamentos }: { departamentos: CodigoNombre[] }) {
  const datos = useQuery({ queryKey: ['maestros', 'auditores'], queryFn: () => api<DatosAuditores>('/api/maestros/auditores') });
  // Un aviso para toda la tabla; el campo de cada fila es «rol-<id>»
  const v = useValidacion({
    etiquetas: Object.fromEntries((datos.data?.auditores ?? []).map((a) => ['rol-' + a.id, 'Rol de ' + a.nombre])),
    testid: 'error-auditores',
  });
  return (
    <Stack ref={v.ref}>
      <Text size="sm" c="dimmed">
        Los auditores se crean en Usuarios con el perfil Auditor.
      </Text>
      {datos.error && <Alert color="red">{datos.error.message}</Alert>}
      {v.resumen}
      {datos.isLoading && (
        <Center py="xl">
          <Loader />
        </Center>
      )}
      {datos.data && (
        <ScrollArea type="auto">
          <Table striped miw={940} data-testid="tabla-auditores">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Nombre</Table.Th>
                <Table.Th>Rol</Table.Th>
                <Table.Th>Departamento base</Table.Th>
                <Table.Th>Municipio base</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {datos.data.auditores.map((a) => (
                <FilaAuditor key={a.id} auditor={a} roles={datos.data.roles} departamentos={departamentos} v={v} />
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      )}
      {datos.data && datos.data.auditores.length === 0 && <Text c="dimmed">No hay usuarios con el perfil Auditor.</Text>}
    </Stack>
  );
}

function FilaAuditor({
  auditor: a,
  roles,
  departamentos,
  v,
}: {
  auditor: Auditor;
  roles: Record<string, string>;
  departamentos: CodigoNombre[];
  v: Validacion;
}) {
  const qc = useQueryClient();
  const [rol, setRol] = useState<string | null>(a.rol);
  const [departamento, setDepartamento] = useState<string | null>(a.municipioBase ? a.municipioBase.substring(0, 2) : null);
  const [municipio, setMunicipio] = useState<string | null>(a.municipioBase);
  const municipios = useMunicipios(departamento);
  const guardar = useMutation({
    mutationFn: () => api<Mensaje>(`/api/maestros/auditores/${a.id}`, { metodo: 'PUT', cuerpo: { rol, municipioBase: municipio } }),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      v.reiniciar();
      qc.invalidateQueries({ queryKey: ['maestros', 'auditores'] });
      qc.invalidateQueries({ queryKey: ['agenda', 'opciones'] });
    },
    onError: (e) => v.errorServidor(e, 'No se pudo guardar el auditor.'),
  });
  const campo = 'rol-' + a.id;
  const cambio = rol !== a.rol || municipio !== a.municipioBase;
  return (
    <Table.Tr data-testid={'fila-auditor-' + a.id}>
      <Table.Td>
        {a.nombre}
        <Text size="xs" c="dimmed">
          {a.usuario}
          {a.activo ? '' : ' · inactivo'}
        </Text>
      </Table.Td>
      <Table.Td>
        <Select
          size="xs"
          aria-label={'Rol de ' + a.nombre}
          data={Object.entries(roles).map(([value, label]) => ({ value, label }))}
          value={rol}
          error={v.error(campo)}
          onChange={(x) => {
            setRol(x);
            v.limpiar(campo);
          }}
        />
      </Table.Td>
      <Table.Td>
        <Select
          size="xs"
          searchable
          clearable
          aria-label={'Departamento base de ' + a.nombre}
          data={departamentos.map((d) => ({ value: d.codigo, label: d.nombre }))}
          value={departamento}
          onChange={(x) => {
            setDepartamento(x);
            setMunicipio(null);
          }}
        />
      </Table.Td>
      <Table.Td>
        <Select
          size="xs"
          searchable
          clearable
          disabled={!departamento}
          aria-label={'Municipio base de ' + a.nombre}
          data={(municipios.data ?? []).map((m) => ({ value: m.codigo, label: m.nombre }))}
          value={municipio}
          onChange={setMunicipio}
        />
      </Table.Td>
      <Table.Td>
        <Button
          size="xs"
          variant="light"
          aria-label={'Guardar a ' + a.nombre}
          disabled={!cambio || guardar.isPending}
          loading={guardar.isPending}
          onClick={() => v.validar({ [campo]: rol ? null : 'Elija el rol.' }) && guardar.mutate()}
        >
          Guardar
        </Button>
      </Table.Td>
    </Table.Tr>
  );
}

function OperadorLogistico() {
  const datos = useQuery({ queryKey: ['maestros', 'opl'], queryFn: () => api<Opl>('/api/maestros/opl') });
  if (datos.isLoading) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    );
  }
  if (datos.error || !datos.data) return <Alert color="red">{datos.error?.message ?? 'No se pudo cargar el operador logístico.'}</Alert>;
  return <FormOpl opl={datos.data} />;
}

function FormOpl({ opl }: { opl: Opl }) {
  const qc = useQueryClient();
  const [nombre, setNombre] = useState(opl.nombre);
  const [nit, setNit] = useState(opl.nit ?? '');
  const [correos, setCorreos] = useState(opl.correos.join(', '));
  const v = useValidacion({ etiquetas: { nombre: 'Razón social', nit: 'NIT', correos: 'Correos' }, testid: 'error-opl' });
  const guardar = useMutation({
    mutationFn: () => api<Mensaje>('/api/maestros/opl', { metodo: 'PUT', cuerpo: { nombre, nit: nit || null, correos: separarCorreos(correos) } }),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      v.reiniciar();
      qc.invalidateQueries({ queryKey: ['maestros', 'opl'] });
    },
    onError: (e) => v.errorServidor(e, 'No se pudo guardar.'),
  });
  return (
    <form
      ref={v.ref}
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        // Las mismas reglas del servidor (ServicioMaestros.guardarOpl)
        const ok = v.validar({
          nombre: errorLargo(nombre, 3, 200, 'Escriba la razón social.', 'La razón social del operador debe tener de 3 a 200 caracteres.'),
          nit: errorNitOpl(nit),
          correos:
            errorCorreos(correos) ??
            (separarCorreos(correos).length === 0 ? 'Registre al menos un correo del operador: recibe la notificación previa (Anexo 3 p.9).' : null),
        });
        if (ok) guardar.mutate();
      }}
    >
      <Stack maw={640}>
        {v.resumen}
        <TextInput
          label="Razón social"
          required
          value={nombre}
          error={v.error('nombre')}
          onChange={(e) => {
            setNombre(e.currentTarget.value);
            v.limpiar('nombre');
          }}
        />
        <TextInput
          label="NIT"
          description="De 6 a 12 dígitos y, si acaso, el dígito de verificación (900123456-7)."
          value={nit}
          error={v.error('nit')}
          onChange={(e) => {
            setNit(e.currentTarget.value);
            v.limpiar('nit');
          }}
        />
        <TextInput
          label="Correos"
          description="Separados por coma. Sus correos reciben la notificación previa (Anexo 3 p.9)."
          value={correos}
          error={v.error('correos')}
          onChange={(e) => {
            setCorreos(e.currentTarget.value);
            v.limpiar('correos');
          }}
        />
        <Group justify="flex-end">
          <Button type="submit" loading={guardar.isPending}>
            Guardar
          </Button>
        </Group>
      </Stack>
    </form>
  );
}

interface FormObligacion {
  id: number;
  prestador: string | null;
  codigo: string;
  texto: string;
  fuente: string;
  activo: boolean;
}

const OBLIGACION_VACIA: FormObligacion = { id: 0, prestador: 'opl', codigo: 'OB-', texto: '', fuente: '', activo: true };

/**
 * Obligaciones de la minuta del OPL y de la farmacia alterna (parte OBL del formulario): el coordinador las registra mientras llega la
 * lectura de la minuta. Las inactivas dejan de salir en las visitas nuevas; las ya calificadas se conservan.
 */
function Obligaciones() {
  const qc = useQueryClient();
  const lista = useQuery({ queryKey: ['maestros', 'obligaciones'], queryFn: () => api<Obligacion[]>('/api/maestros/obligaciones') });
  const [form, setForm] = useState<FormObligacion | null>(null);
  const v = useValidacion({ etiquetas: { prestador: 'Prestador', codigo: 'Código', texto: 'Texto de la obligación' }, testid: 'error-obligacion' });
  const guardar = useMutation({
    mutationFn: (f: FormObligacion) =>
      api<Mensaje>(f.id ? `/api/maestros/obligaciones/${f.id}` : '/api/maestros/obligaciones', {
        metodo: f.id ? 'PUT' : 'POST',
        cuerpo: { prestador: f.prestador, codigo: f.codigo.trim().toUpperCase(), texto: f.texto.trim(), fuente: f.fuente.trim() || null, activo: f.activo },
      }),
    onSuccess: (r) => {
      notifications.show({ color: 'green', message: r.mensaje });
      cerrar();
      qc.invalidateQueries({ queryKey: ['maestros', 'obligaciones'] });
      qc.invalidateQueries({ queryKey: ['formulario'] });
    },
    onError: (e) => v.errorServidor(e, 'No se pudo guardar la obligación.'),
  });
  function abrir(f: FormObligacion) {
    v.reiniciar();
    setForm(f);
  }
  function cerrar() {
    v.reiniciar();
    setForm(null);
  }
  const cambiar = (campo: keyof FormObligacion, cambio: Partial<FormObligacion>) => {
    if (form) setForm({ ...form, ...cambio });
    v.limpiar(campo);
  };

  return (
    <Stack>
      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          Se califican en la parte «Obligaciones de la minuta» del formulario: las del OPL en los ESM y las de la farmacia alterna en las FA.
        </Text>
        <Button onClick={() => abrir({ ...OBLIGACION_VACIA })}>Nueva obligación</Button>
      </Group>
      {lista.error && <Alert color="red">{lista.error.message}</Alert>}
      {lista.isLoading ? (
        <Center py="xl">
          <Loader />
        </Center>
      ) : (
        <ScrollArea type="auto">
          <Table striped miw={820} data-testid="tabla-obligaciones">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Prestador</Table.Th>
                <Table.Th>Código</Table.Th>
                <Table.Th>Obligación</Table.Th>
                <Table.Th>Fuente</Table.Th>
                <Table.Th>Estado</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {(lista.data ?? []).map((o) => (
                <Table.Tr key={o.id} data-testid={'fila-obligacion-' + o.codigo}>
                  <Table.Td>{PRESTADORES.find((p) => p.value === o.prestador)?.label ?? o.prestador}</Table.Td>
                  <Table.Td style={{ whiteSpace: 'nowrap' }}>{o.codigo}</Table.Td>
                  <Table.Td maw={420}>
                    <Text size="sm" lineClamp={3}>
                      {o.texto}
                    </Text>
                  </Table.Td>
                  <Table.Td>{o.fuente ?? '—'}</Table.Td>
                  <Table.Td>
                    <Badge color={o.activo ? 'green' : 'gray'}>{o.activo ? 'Activa' : 'Inactiva'}</Badge>
                  </Table.Td>
                  <Table.Td>
                    <Button
                      size="xs"
                      variant="light"
                      aria-label={'Editar la obligación ' + o.codigo}
                      onClick={() => abrir({ id: o.id, prestador: o.prestador, codigo: o.codigo, texto: o.texto, fuente: o.fuente ?? '', activo: o.activo })}
                    >
                      Editar
                    </Button>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      )}
      {lista.data && lista.data.length === 0 && <Text c="dimmed">No hay obligaciones registradas.</Text>}

      <Modal opened={form !== null} onClose={cerrar} title={form?.id ? 'Editar obligación' : 'Nueva obligación'} size="lg">
        {form && (
          <form
            ref={v.ref}
            noValidate
            onSubmit={(e) => {
              e.preventDefault();
              // Las mismas reglas del servidor (ServicioMaestros.guardarObligacion)
              const ok = v.validar({
                prestador: form.prestador ? null : 'Elija el prestador.',
                codigo: errorCodigoObligacion(form.codigo),
                texto: errorLargo(
                  form.texto,
                  10,
                  2000,
                  'Escriba el texto de la obligación.',
                  'El texto de la obligación debe tener de 10 a 2000 caracteres.',
                ),
              });
              if (ok) guardar.mutate(form);
            }}
          >
            <Stack>
              {v.resumen}
              <SimpleGrid cols={{ base: 1, xs: 2 }}>
                <Select
                  label="Prestador"
                  required
                  allowDeselect={false}
                  data={PRESTADORES}
                  value={form.prestador}
                  error={v.error('prestador')}
                  onChange={(x) => cambiar('prestador', { prestador: x })}
                />
                <TextInput
                  label="Código"
                  required
                  description="Empieza por OB- (p. ej. OB-OPL-01)."
                  maxLength={23}
                  value={form.codigo}
                  error={v.error('codigo')}
                  onChange={(e) => cambiar('codigo', { codigo: e.currentTarget.value.toUpperCase() })}
                />
              </SimpleGrid>
              <Textarea
                label="Texto de la obligación"
                required
                autosize
                minRows={3}
                minLength={10}
                maxLength={2000}
                value={form.texto}
                error={v.error('texto')}
                onChange={(e) => cambiar('texto', { texto: e.currentTarget.value })}
              />
              <TextInput
                label="Fuente"
                description="Cláusula o página de la minuta."
                maxLength={200}
                value={form.fuente}
                onChange={(e) => setForm({ ...form, fuente: e.currentTarget.value })}
              />
              <Checkbox label="Activa" checked={form.activo} onChange={(e) => setForm({ ...form, activo: e.currentTarget.checked })} />
              <Group justify="flex-end">
                <Button variant="default" onClick={cerrar}>
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
