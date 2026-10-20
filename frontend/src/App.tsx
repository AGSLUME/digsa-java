import { Center, Loader, Stack, Text, Title } from '@mantine/core';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect } from 'react';
import { Navigate, Route, Routes, useNavigate } from 'react-router-dom';
import { Agenda } from './paginas/Agenda';
import { Calendario } from './paginas/Calendario';
import { Catalogos } from './paginas/Catalogos';
import { DefinirClave, OlvideClave } from './paginas/Claves';
import { Ingreso } from './paginas/Ingreso';
import { Logs } from './paginas/Logs';
import { Maestros } from './paginas/Maestros';
import { MisVisitas } from './paginas/MisVisitas';
import { Seguimiento } from './paginas/Seguimiento';
import { Usuarios } from './paginas/Usuarios';
import { VisitaDetalle } from './paginas/VisitaDetalle';
import { alCerrarseLaSesion, api, ErrorApi } from './shared/api';
import { Plantilla } from './shared/Plantilla';
import { ProveedorSesion, puedeEntrar, useSesion, type DatosSesion } from './shared/sesion';

function Inicio() {
  const s = useSesion();
  return (
    <Stack>
      <Title order={3}>Bienvenido, {s.nombre}</Title>
      <Text c="dimmed">Perfiles: {s.perfiles.join(', ')}</Text>
    </Stack>
  );
}

function Protegida({ ruta, children }: { ruta: string; children: JSX.Element }) {
  const s = useSesion();
  return puedeEntrar(s, ruta) ? children : <Text c="red">Acceso restringido.</Text>;
}

export function App() {
  const qc = useQueryClient();
  const navegar = useNavigate();
  const sesion = useQuery({
    queryKey: ['sesion'],
    queryFn: () => api<DatosSesion>('/api/sesion'),
    retry: false,
    staleTime: Infinity,
  });

  useEffect(
    () =>
      alCerrarseLaSesion(() => {
        qc.setQueryData(['sesion'], null);
        qc.removeQueries({ predicate: (q) => q.queryKey[0] !== 'sesion' });
        navegar('/');
      }),
    [qc, navegar],
  );

  const salir = useCallback(async () => {
    try {
      await api('/api/auth/salir', { metodo: 'POST' });
    } catch {
      // sin red o sesión ya cerrada: igual se vuelve al ingreso
    } finally {
      qc.clear();
      navegar('/');
      window.location.reload();
    }
  }, [qc, navegar]);

  const publicas = (
    <>
      <Route path="/definir-clave" element={<DefinirClave />} />
      <Route path="/olvide-clave" element={<OlvideClave />} />
    </>
  );

  if (sesion.isLoading) {
    return (
      <Center mih="100vh">
        <Loader />
      </Center>
    );
  }
  const datos = sesion.data;
  if (!datos || (sesion.error instanceof ErrorApi && sesion.error.estado === 401)) {
    return (
      <Routes>
        {publicas}
        <Route path="*" element={<Ingreso alIngresar={() => qc.invalidateQueries({ queryKey: ['sesion'] })} />} />
      </Routes>
    );
  }
  return (
    <ProveedorSesion valor={datos}>
      <Routes>
        {publicas}
        <Route element={<Plantilla alSalir={salir} />}>
          <Route path="/" element={<Inicio />} />
          <Route path="/usuarios" element={<Protegida ruta="usuarios"><Usuarios /></Protegida>} />
          <Route path="/logs" element={<Protegida ruta="logs"><Logs /></Protegida>} />
          <Route path="/catalogos" element={<Protegida ruta="catalogos"><Catalogos /></Protegida>} />
          <Route path="/maestros" element={<Protegida ruta="maestros"><Maestros /></Protegida>} />
          <Route path="/agenda" element={<Protegida ruta="agenda"><Agenda /></Protegida>} />
          <Route path="/agenda/visita/:id" element={<Protegida ruta="agenda"><VisitaDetalle key="agenda" origen="agenda" /></Protegida>} />
          <Route path="/seguimiento" element={<Protegida ruta="agenda"><Seguimiento /></Protegida>} />
          <Route path="/mis-visitas" element={<Protegida ruta="mis-visitas"><MisVisitas /></Protegida>} />
          <Route path="/mis-visitas/visita/:id" element={<Protegida ruta="mis-visitas"><VisitaDetalle key="mis-visitas" origen="mis-visitas" /></Protegida>} />
          <Route path="/calendario" element={<Protegida ruta="calendario"><Calendario /></Protegida>} />
          <Route path="/calendario/visita/:id" element={<Protegida ruta="calendario"><VisitaDetalle key="calendario" origen="calendario" /></Protegida>} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </ProveedorSesion>
  );
}
