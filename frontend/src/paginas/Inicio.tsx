import { Button, Card, SimpleGrid, Stack, Text, Title } from '@mantine/core';
import { useNavigate } from 'react-router-dom';
import { EncabezadoPagina } from '../shared/EncabezadoPagina';
import { agrupar } from '../shared/menu';
import { puedeEntrar, useSesion } from '../shared/sesion';
// --- Presupuesto (rama presupuesto; mockup: «Ejecución presupuestal nacional» en el inicio) ---
import { ResumenPresupuestoInicio } from './PresupuestoTablero';
// --- fin presupuesto ---

/**
 * Inicio como el «Panel de interventoría» del mockup: una tarjeta por grupo del menú con las pantallas que el perfil puede abrir (las
 * mismas del menú, que filtra el servidor: Anexo 5 num. 3.7) y el resumen de presupuesto para quien lo ve. No pide datos nuevos. Son
 * botones y no enlaces: el menú lateral conserva los únicos enlaces con el nombre de cada pantalla.
 */
export function Inicio() {
  const s = useSesion();
  const navegar = useNavigate();
  const grupos = agrupar(s.menu);
  return (
    <Stack>
      <EncabezadoPagina
        titulo="Panel de interventoría"
        descripcion={
          <>
            Bienvenido, {s.nombre}. Perfiles: {s.perfiles.join(', ')}.
          </>
        }
      />
      {puedeEntrar(s, 'presupuesto') && <ResumenPresupuestoInicio />}
      {grupos.length > 0 && (
        <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} data-testid="modulos-inicio">
          {grupos.map(({ grupo, entradas }) => (
            <Card key={grupo} withBorder padding="md">
              <Title order={2} size="h5" mb="xs">
                {grupo}
              </Title>
              <Stack gap={4}>
                {entradas.map((e) => {
                  const Icono = e.icono;
                  return (
                    <Button
                      key={e.ruta}
                      variant="subtle"
                      justify="flex-start"
                      mih={44}
                      leftSection={<Icono size={18} stroke={1.8} aria-hidden />}
                      rightSection={
                        e.numeral ? (
                          <Text component="span" size="xs" c="dimmed" ff="monospace" aria-hidden>
                            {e.numeral}
                          </Text>
                        ) : undefined
                      }
                      styles={{ inner: { justifyContent: 'space-between' }, label: { flex: 1, whiteSpace: 'normal', textAlign: 'left' } }}
                      onClick={() => navegar('/' + e.ruta)}
                    >
                      {e.titulo}
                    </Button>
                  );
                })}
              </Stack>
            </Card>
          ))}
        </SimpleGrid>
      )}
    </Stack>
  );
}
