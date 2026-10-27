import { Alert, Button, Checkbox, Group, Modal, MultiSelect, Select, SimpleGrid, Stack, Text, Textarea, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { horaCorta, type ComponenteOpcion, type DetalleVisita, type OpcionesAgenda, type ResultadoVisita } from './agenda';
import { api } from './api';
import { CampoHora24 } from './CampoHora24';
import { horaValida, MENSAJE_HORA } from './hora';
import { useValidacion } from './validacion';

export const MAX_OTROS = 5;
const ROL_QF = 'quimico_farmaceutico';
const ROL_SISTEMAS = 'ingeniero_sistemas';

interface Formulario {
  tipo: string | null;
  puntoId: string | null;
  fecha: string;
  hora: string;
  fechaSolicitud: string;
  liderId: string | null;
  otros: string[];
  alcance: string;
  componentes: string[];
  reparto: Record<string, string>;
  notificar: boolean;
}

/** Componentes del checklist que aplican a una unidad (ESM, FA, NC...). */
export function componentesDe(componentes: ComponenteOpcion[], unidad: string | undefined): ComponenteOpcion[] {
  return unidad ? componentes.filter((c) => c.unidades.includes(unidad)) : [];
}

/** Preselección al elegir el punto: todos los que aplican menos los del plan de mejoramiento, salvo en esa visita. */
export function preseleccion(componentes: ComponenteOpcion[], unidad: string | undefined, tipo: string | null): string[] {
  return componentesDe(componentes, unidad)
    .filter((c) => c.especial !== 'plan' || tipo === 'plan')
    .map((c) => c.codigo);
}

/** Partes que se reparten: los componentes elegidos, siempre las obligaciones y los ítems de sistemas si hay ingeniero y aplican. */
export function partesDelReparto(componentes: ComponenteOpcion[], elegidos: string[], rolesEquipo: string[]): { parte: string; nombre: string }[] {
  const lista = componentes.filter((c) => elegidos.includes(c.codigo)).map((c) => ({ parte: c.codigo, nombre: c.nombre }));
  lista.push({ parte: 'OBL', nombre: 'Obligaciones de la minuta' });
  const sistemas = componentes.some((c) => elegidos.includes(c.codigo) && c.sistemas > 0);
  if (sistemas && rolesEquipo.includes(ROL_SISTEMAS)) lista.push({ parte: 'SIS', nombre: 'Ítems de sistemas' });
  return lista;
}

function inicial(visita: DetalleVisita | undefined): Formulario {
  if (!visita) {
    return {
      tipo: null,
      puntoId: null,
      fecha: '',
      hora: '',
      fechaSolicitud: '',
      liderId: null,
      otros: [],
      alcance: '',
      componentes: [],
      reparto: {},
      notificar: true,
    };
  }
  const equipo = visita.equipo ?? [];
  return {
    tipo: visita.tipo,
    puntoId: String(visita.puntoId),
    fecha: visita.fecha,
    hora: horaCorta(visita.hora),
    fechaSolicitud: visita.fechaSolicitud ?? '',
    liderId: equipo.find((m) => m.lider) ? String(equipo.find((m) => m.lider)!.id) : null,
    otros: equipo.filter((m) => !m.lider).map((m) => String(m.id)),
    alcance: visita.alcance ?? '',
    componentes: (visita.componentes ?? []).map((c) => c.codigo),
    reparto: Object.fromEntries((visita.reparto ?? []).map((p) => [p.parte, String(p.auditorId)])),
    notificar: true,
  };
}

/** Solicitud de visita a necesidad que se agenda: el tipo, el punto y la fecha del escrito los pone la solicitud. */
export interface SolicitudParaAgendar {
  id: number;
  puntoId: number;
  fechaSolicitud: string;
}

function Cuerpo({
  opciones,
  visita,
  solicitud,
  alCerrar,
  alGuardar,
}: {
  opciones: OpcionesAgenda;
  visita?: DetalleVisita;
  solicitud?: SolicitudParaAgendar;
  alCerrar: () => void;
  alGuardar: (r: ResultadoVisita) => void;
}) {
  const qc = useQueryClient();
  const [f, setF] = useState<Formulario>(() => {
    const base = inicial(visita);
    if (!solicitud) return base;
    const p = opciones.puntos.find((x) => x.id === solicitud.puntoId);
    return {
      ...base,
      tipo: 'necesidad',
      puntoId: String(solicitud.puntoId),
      fechaSolicitud: solicitud.fechaSolicitud,
      componentes: preseleccion(opciones.componentes, p?.unidad, 'necesidad'),
      notificar: false,
    };
  });
  const val = useValidacion({
    etiquetas: {
      tipo: 'Tipo de visita',
      puntoId: 'Punto',
      fecha: 'Fecha',
      hora: 'Hora',
      fechaSolicitud: 'Fecha de la solicitud',
      liderId: 'Auditor líder',
      alcance: 'Alcance específico',
    },
    testid: 'error-visita',
  });

  const tipo = opciones.tipos.find((t) => t.codigo === f.tipo);
  const punto = opciones.puntos.find((p) => String(p.id) === f.puntoId);
  const puntos = opciones.puntos.filter((p) => !tipo?.unidad || p.unidad === tipo.unidad || String(p.id) === f.puntoId);
  const nombreRol = (rol: string) => opciones.roles[rol] ?? rol;
  const auditor = (id: string) => opciones.auditores.find((a) => String(a.id) === id);
  const equipoIds = [f.liderId, ...f.otros].filter((x): x is string => !!x);
  const rolesEquipo = equipoIds.map((id) => auditor(id)?.rol ?? 'otro');
  const aplicables = componentesDe(opciones.componentes, punto?.unidad);
  const partes = partesDelReparto(opciones.componentes, f.componentes, rolesEquipo);
  const faltaQf = !!punto?.exigeQf && !rolesEquipo.includes(ROL_QF);

  const guardar = useMutation({
    mutationFn: (cuerpo: Record<string, unknown>) =>
      api<ResultadoVisita>(visita ? `/api/agenda/${visita.id}` : '/api/agenda', { metodo: visita ? 'PUT' : 'POST', cuerpo }),
    onSuccess: (r) => {
      const fallo = r.notificacionOk === false;
      notifications.show({ color: fallo ? 'orange' : 'green', autoClose: fallo ? false : 6000, message: r.mensaje });
      for (const a of r.advertencias ?? []) notifications.show({ color: 'orange', autoClose: 10000, message: a });
      qc.invalidateQueries({ queryKey: ['agenda'] });
      alGuardar(r);
    },
    onError: (e) => {
      val.errorServidor(e, 'No se pudo guardar la visita.');
      if (solicitud) qc.invalidateQueries({ queryKey: ['solicitudes'] }); // otro coordinador pudo agendarla o anularla
    },
  });

  const elegirTipo = (codigo: string | null) => {
    const t = opciones.tipos.find((x) => x.codigo === codigo);
    const quedaPunto = punto && (!t?.unidad || t.unidad === punto.unidad) ? f.puntoId : null;
    let comps = quedaPunto ? f.componentes : [];
    if (quedaPunto) {
      // El componente del plan de mejoramiento solo va preseleccionado en la visita de plan
      const plan = aplicables.filter((c) => c.especial === 'plan').map((c) => c.codigo);
      comps = codigo === 'plan' ? Array.from(new Set([...comps, ...plan])) : comps.filter((c) => !plan.includes(c));
    }
    setF({ ...f, tipo: codigo, puntoId: quedaPunto, componentes: comps });
    val.limpiar('tipo');
  };

  const elegirPunto = (id: string | null) => {
    const p = opciones.puntos.find((x) => String(x.id) === id);
    setF({ ...f, puntoId: id, componentes: preseleccion(opciones.componentes, p?.unidad, f.tipo) });
    val.limpiar('puntoId');
  };

  const enviar = (e: FormEvent) => {
    e.preventDefault();
    // Cada mensaje junto a su campo (el de la hora lo pinta CampoHora24 al salir del campo; el resumen nombra el campo)
    const ok = val.validar({
      tipo: f.tipo ? null : 'Elija el tipo de visita.',
      puntoId: f.puntoId ? null : 'Elija el punto.',
      fecha: f.fecha ? null : 'Escriba la fecha completa (día, mes y año).',
      hora: f.hora && !horaValida(f.hora) ? MENSAJE_HORA : null,
      liderId: f.liderId ? null : 'Elija el auditor líder.',
      alcance: f.alcance.trim() ? null : 'Escriba el alcance específico.',
    });
    if (!ok || !f.tipo || !f.puntoId || !f.liderId) return;
    const validas = new Set(partes.map((p) => p.parte));
    const reparto = Object.fromEntries(
      Object.entries(f.reparto)
        .filter(([parte, id]) => validas.has(parte) && equipoIds.includes(id))
        .map(([parte, id]) => [parte, Number(id)]),
    );
    guardar.mutate({
      tipo: f.tipo,
      puntoId: Number(f.puntoId),
      fecha: f.fecha,
      hora: f.hora || null,
      liderId: Number(f.liderId),
      otros: f.otros.filter((o) => o !== f.liderId).map(Number),
      alcance: f.alcance,
      componentes: f.componentes,
      reparto,
      fechaSolicitud: f.tipo === 'necesidad' ? f.fechaSolicitud || null : null,
      notificar: tipo?.aviso === 'no-opl' ? false : f.notificar,
      solicitudId: solicitud?.id ?? null,
    });
  };

  const datosAuditores = opciones.auditores.map((a) => ({ value: String(a.id), label: `${a.nombre} · ${nombreRol(a.rol)}` }));

  return (
    <form ref={val.ref} onSubmit={enviar} noValidate data-testid="form-visita">
      <Stack>
        {val.resumen}
        <Select
          label="Tipo de visita"
          disabled={!!solicitud}
          required
          data={opciones.tipos.map((t) => ({ value: t.codigo, label: t.nombre }))}
          value={f.tipo}
          error={val.error('tipo')}
          onChange={elegirTipo}
        />
        <Select
          label="Punto"
          disabled={!!solicitud}
          required
          searchable
          nothingFoundMessage="Sin puntos"
          data={puntos.map((p) => ({ value: String(p.id), label: `${p.sigla ? p.sigla + ' · ' : ''}${p.nombre} (${p.unidad}, ${p.fuerza})` }))}
          value={f.puntoId}
          error={val.error('puntoId')}
          onChange={elegirPunto}
        />
        <SimpleGrid cols={{ base: 1, xs: 2 }}>
          <TextInput
            label="Fecha"
            type="date"
            required
            value={f.fecha}
            error={val.error('fecha')}
            onChange={(e) => {
              setF({ ...f, fecha: e.currentTarget.value });
              val.limpiar('fecha');
            }}
          />
          <CampoHora24
            label="Hora"
            value={f.hora}
            error={val.error('hora')}
            onChange={(hora) => {
              setF({ ...f, hora });
              val.limpiar('hora');
            }}
          />
        </SimpleGrid>
        {f.tipo === 'necesidad' && (
          <TextInput
            label="Fecha de la solicitud"
            disabled={!!solicitud}
            type="date"
            value={f.fechaSolicitud}
            error={val.error('fechaSolicitud')}
            onChange={(e) => {
              setF({ ...f, fechaSolicitud: e.currentTarget.value });
              val.limpiar('fechaSolicitud');
            }}
          />
        )}
        <Select
          label="Auditor líder"
          required
          searchable
          data={datosAuditores}
          value={f.liderId}
          error={val.error('liderId')}
          onChange={(v) => {
            setF({ ...f, liderId: v, otros: f.otros.filter((o) => o !== v) });
            val.limpiar('liderId');
          }}
        />
        <MultiSelect
          label="Otros auditores"
          description={`Máximo ${MAX_OTROS}.`}
          searchable
          maxValues={MAX_OTROS}
          data={datosAuditores.filter((a) => a.value !== f.liderId)}
          value={f.otros}
          onChange={(v) => setF({ ...f, otros: v })}
        />
        {faltaQf && punto && (
          <Alert color="orange" data-testid="alerta-qf">
            En {punto.nombre} el equipo debe incluir un químico farmacéutico (Anexo 3 p.9).
          </Alert>
        )}
        <Textarea
          label="Alcance específico"
          required
          autosize
          minRows={3}
          maxLength={5000}
          value={f.alcance}
          error={val.error('alcance')}
          onChange={(e) => {
            setF({ ...f, alcance: e.currentTarget.value });
            val.limpiar('alcance');
          }}
        />
        <Checkbox.Group
          label="Componentes del checklist"
          description={opciones.version ? `Checklist ${opciones.version}` : undefined}
          value={f.componentes}
          onChange={(v) => setF({ ...f, componentes: v })}
        >
          <Stack gap={6} mt={6}>
            {!punto && (
              <Text size="sm" c="dimmed">
                Elija el punto para ver los componentes que aplican.
              </Text>
            )}
            {aplicables.map((c) => (
              <Checkbox
                key={c.codigo}
                value={c.codigo}
                label={`${c.codigo} · ${c.nombre} (${c.items} ítems)`}
                data-testid={'componente-' + c.codigo}
              />
            ))}
          </Stack>
        </Checkbox.Group>
        {punto && equipoIds.length > 0 && (
          <Stack gap={6}>
            <Text size="sm" fw={500}>
              Asignación de componentes
            </Text>
            <Text size="xs" c="dimmed">
              Lo que no elija se reparte de forma automática entre el equipo.
            </Text>
            {partes.map((p) => (
              <Select
                key={p.parte}
                label={`${p.parte} · ${p.nombre}`}
                placeholder="Automático (sugerido)"
                clearable
                data-testid={'asignar-' + p.parte}
                data={equipoIds.map((id) => ({ value: id, label: auditor(id)?.nombre ?? id }))}
                value={f.reparto[p.parte] && equipoIds.includes(f.reparto[p.parte]!) ? f.reparto[p.parte]! : null}
                onChange={(v) => {
                  const r = { ...f.reparto };
                  if (v) r[p.parte] = v;
                  else delete r[p.parte];
                  setF({ ...f, reparto: r });
                }}
              />
            ))}
          </Stack>
        )}
        {tipo?.aviso !== 'no-opl' && (
          <Checkbox
            label="Enviar la notificación previa al guardar"
            checked={f.notificar}
            onChange={(e) => setF({ ...f, notificar: e.currentTarget.checked })}
          />
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={alCerrar}>
            Cancelar
          </Button>
          <Button type="submit" loading={guardar.isPending}>
            {visita ? 'Guardar cambios' : 'Programar'}
          </Button>
        </Group>
      </Stack>
    </form>
  );
}

/** Formulario para programar una visita o editar una programada (mismo cuerpo para POST y PUT). */
export function FormularioVisita({
  abierto,
  alCerrar,
  opciones,
  visita,
  solicitud,
  errorOpciones,
  alGuardar,
}: {
  abierto: boolean;
  errorOpciones?: string;
  alCerrar: () => void;
  opciones: OpcionesAgenda | undefined;
  visita?: DetalleVisita;
  solicitud?: SolicitudParaAgendar;
  alGuardar: (r: ResultadoVisita) => void;
}) {
  return (
    <Modal
      opened={abierto}
      onClose={alCerrar}
      title={visita ? `Editar visita ${visita.numero}` : solicitud ? `Agendar la solicitud N.º ${solicitud.id}` : 'Programar visita'}
      size="xl"
    >
      {abierto && opciones && <Cuerpo opciones={opciones} visita={visita} solicitud={solicitud} alCerrar={alCerrar} alGuardar={alGuardar} />}
      {abierto &&
        !opciones &&
        (errorOpciones ? <Text c="red">No se pudieron cargar las opciones: {errorOpciones}</Text> : <Text c="dimmed">Cargando las opciones...</Text>)}
    </Modal>
  );
}
