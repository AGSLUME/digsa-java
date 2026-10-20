import { ActionIcon, Badge, Box, Group, Paper, Stack, Text, UnstyledButton } from '@mantine/core';
import { useMediaQuery } from '@mantine/hooks';
import { useState } from 'react';
import { colorEstado, horaCorta, type FilaVisita } from './agenda';
import { DIAS_SEMANA, fechaIso, semanasDelMes, tituloMes } from './mes';

export { moverMes, rangoMes, semanasDelMes, tituloMes } from './mes';

/** Título del mes con flechas ‹ › para cambiarlo. */
export function NavegadorMes({ anio, mes, alMover }: { anio: number; mes: number; alMover: (n: number) => void }) {
  return (
    <Group gap="xs" wrap="nowrap">
      <ActionIcon variant="default" aria-label="Mes anterior" onClick={() => alMover(-1)}>
        ‹
      </ActionIcon>
      <Text fw={600} miw={150} ta="center" data-testid="titulo-mes">
        {tituloMes(anio, mes)}
      </Text>
      <ActionIcon variant="default" aria-label="Mes siguiente" onClick={() => alMover(1)}>
        ›
      </ActionIcon>
    </Group>
  );
}

type VisitaMes = Pick<FilaVisita, 'id' | 'numero' | 'fecha' | 'hora' | 'estado' | 'punto'>;

/**
 * Cuadrícula de un mes (lunes primero). En pantallas angostas cada día muestra solo cuántas visitas tiene y al tocarlo se listan
 * debajo; así no hay desplazamiento horizontal en 360 px.
 */
export function CalendarioMes({ anio, mes, visitas, alAbrir }: { anio: number; mes: number; visitas: VisitaMes[]; alAbrir: (id: number) => void }) {
  const angosta = useMediaQuery('(max-width: 48em)');
  const [elegido, setElegido] = useState<string | null>(null);
  const porDia = new Map<string, VisitaMes[]>();
  for (const v of visitas) {
    const lista = porDia.get(v.fecha) ?? [];
    lista.push(v);
    porDia.set(v.fecha, lista);
  }
  const hoy = fechaIso(new Date());
  const delDia = elegido ? (porDia.get(elegido) ?? []) : [];

  return (
    <Stack gap="xs">
      <Box
        data-testid="calendario-mes"
        style={{ display: 'grid', gridTemplateColumns: 'repeat(7, minmax(0, 1fr))', gap: 2, width: '100%' }}
      >
        {DIAS_SEMANA.map((d) => (
          <Text key={d} size="xs" fw={600} c="dimmed" ta="center">
            {d}
          </Text>
        ))}
        {semanasDelMes(anio, mes)
          .flat()
          .map((d) => {
            const lista = porDia.get(d.iso) ?? [];
            return (
              <Paper
                key={d.iso}
                withBorder
                p={4}
                radius="xs"
                data-testid={'dia-' + d.iso}
                bg={d.iso === elegido ? 'digsa.0' : undefined}
                style={{ minWidth: 0, overflow: 'hidden', minHeight: angosta ? 52 : 92, opacity: d.delMes ? 1 : 0.45 }}
              >
                <Text size="xs" fw={d.iso === hoy ? 700 : 400} c={d.iso === hoy ? 'digsa.7' : undefined}>
                  {d.dia}
                </Text>
                {angosta
                  ? lista.length > 0 && (
                      <Badge
                        component="button"
                        size="sm"
                        circle
                        style={{ cursor: 'pointer' }}
                        aria-label={`${lista.length} visitas el ${d.iso}`}
                        onClick={() => setElegido(d.iso === elegido ? null : d.iso)}
                      >
                        {lista.length}
                      </Badge>
                    )
                  : (
                    <Stack gap={2}>
                      {lista.map((v) => (
                        <Badge
                          key={v.id}
                          component="button"
                          size="xs"
                          radius="sm"
                          variant="light"
                          color={colorEstado(v.estado)}
                          fullWidth
                          title={`${v.numero} · ${v.punto}${v.hora ? ' · ' + horaCorta(v.hora) : ''}`}
                          aria-label={`Abrir la visita ${v.numero}`}
                          onClick={() => alAbrir(v.id)}
                          styles={{ root: { cursor: 'pointer', justifyContent: 'flex-start', textTransform: 'none' }, label: { overflow: 'hidden', textOverflow: 'ellipsis' } }}
                        >
                          {v.numero} · {v.punto}
                        </Badge>
                      ))}
                    </Stack>
                  )}
              </Paper>
            );
          })}
      </Box>
      {angosta && elegido && (
        <Stack gap={4}>
          <Text size="sm" fw={600}>
            Visitas del {elegido}
          </Text>
          {delDia.map((v) => (
            <UnstyledButton key={v.id} onClick={() => alAbrir(v.id)} aria-label={`Abrir la visita ${v.numero}`}>
              <Group gap={6} wrap="nowrap" style={{ minWidth: 0 }}>
                <Badge size="sm" variant="light" color={colorEstado(v.estado)}>
                  {horaCorta(v.hora) || 'Sin hora'}
                </Badge>
                <Text size="sm" truncate>
                  {v.numero} · {v.punto}
                </Text>
              </Group>
            </UnstyledButton>
          ))}
        </Stack>
      )}
    </Stack>
  );
}
