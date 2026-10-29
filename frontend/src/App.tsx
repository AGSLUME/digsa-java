import { Center, Loader, Text } from '@mantine/core';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { lazy, Suspense, useCallback, useEffect } from 'react';
import { Navigate, Route, Routes, useNavigate } from 'react-router-dom';
import { Inicio } from './paginas/Inicio';
import { Ingreso } from './paginas/Ingreso';
import { alCerrarseLaSesion, api, ErrorApi } from './shared/api';
import { Plantilla } from './shared/Plantilla';
import { ProveedorSesion, puedeEntrar, useSesion, type DatosSesion } from './shared/sesion';

/**
 * División por rutas (U5): cada pantalla se descarga al abrirla. Inicio e ingreso van en el paquete principal (son lo primero que se
 * ve). La espera la muestra el Suspense de la Plantilla (y el de las rutas públicas).
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function perezosa<M extends Record<string, any>>(cargar: () => Promise<M>, nombre: keyof M & string) {
  return lazy(() => cargar().then((m) => ({ default: m[nombre] })));
}
const Agenda = perezosa(() => import('./paginas/Agenda'), 'Agenda');
const Alertas = perezosa(() => import('./paginas/Alertas'), 'Alertas');
const Calendario = perezosa(() => import('./paginas/Calendario'), 'Calendario');
const Catalogos = perezosa(() => import('./paginas/Catalogos'), 'Catalogos');
const Checklist = perezosa(() => import('./paginas/Checklist'), 'Checklist');
const Dashboard = perezosa(() => import('./paginas/Dashboard'), 'Dashboard');
const Exportacion = perezosa(() => import('./paginas/Exportacion'), 'Exportacion');
const Hallazgos = perezosa(() => import('./paginas/Hallazgos'), 'Hallazgos');
const Logs = perezosa(() => import('./paginas/Logs'), 'Logs');
const Reportes = perezosa(() => import('./paginas/Reportes'), 'Reportes');
const Riesgo = perezosa(() => import('./paginas/Riesgo'), 'Riesgo');
const Solicitudes = perezosa(() => import('./paginas/Solicitudes'), 'Solicitudes');
const Maestros = perezosa(() => import('./paginas/Maestros'), 'Maestros');
const MisVisitas = perezosa(() => import('./paginas/MisVisitas'), 'MisVisitas');
const Prorrogas = perezosa(() => import('./paginas/Prorrogas'), 'Prorrogas');
const Propuesta = perezosa(() => import('./paginas/Propuesta'), 'Propuesta');
const Seguimiento = perezosa(() => import('./paginas/Seguimiento'), 'Seguimiento');
const Usuarios = perezosa(() => import('./paginas/Usuarios'), 'Usuarios');
const VisitaDetalle = perezosa(() => import('./paginas/VisitaDetalle'), 'VisitaDetalle');
const OplResponder = perezosa(() => import('./paginas/OplResponder'), 'OplResponder');
const DefinirClave = perezosa(() => import('./paginas/Claves'), 'DefinirClave');
const OlvideClave = perezosa(() => import('./paginas/Claves'), 'OlvideClave');
const Opl = perezosa(() => import('./paginas/Opl'), 'Opl');
const OplVisita = perezosa(() => import('./paginas/Opl'), 'OplVisita');
// --- Presupuesto (rama presupuesto) ---
const Presupuesto = perezosa(() => import('./paginas/Presupuesto'), 'Presupuesto');
// --- fin presupuesto ---
// --- Analítica (rama analitica) ---
const CarguesOpl = perezosa(() => import('./paginas/CarguesOpl'), 'CarguesOpl');
const Homologacion = perezosa(() => import('./paginas/Homologacion'), 'Homologacion');
const Analitica = perezosa(() => import('./paginas/Analitica'), 'Analitica');
const TrazabilidadAnalitica = perezosa(() => import('./paginas/TrazabilidadAnalitica'), 'TrazabilidadAnalitica');
const AlertasAnaliticas = perezosa(() => import('./paginas/AlertasAnaliticas'), 'AlertasAnaliticas');
// --- fin analítica ---

// Los mapas (límites departamentales, ~60 kB) se cargan al abrir la pantalla
const Mapas = lazy(() => import('./paginas/Mapas').then((m) => ({ default: m.Mapas })));

/** Espera de una pantalla pública que todavía se descarga (las de la aplicación la muestran dentro de la Plantilla). */
const cargando = (
  <Center mih="100vh">
    <Loader role="status" aria-label="Cargando" />
  </Center>
);

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
      {/* Enlace del acta (QR) para el OPL: sin usuario ni menú, con o sin sesión abierta en el navegador */}
      <Route path="/opl-responder" element={<OplResponder />} />
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
      <Suspense fallback={cargando}>
        <Routes>
          {publicas}
          <Route path="*" element={<Ingreso alIngresar={() => qc.invalidateQueries({ queryKey: ['sesion'] })} />} />
        </Routes>
      </Suspense>
    );
  }
  return (
    <ProveedorSesion valor={datos}>
      <Suspense fallback={cargando}>
        <Routes>
          {publicas}
          <Route element={<Plantilla alSalir={salir} />}>
            <Route path="/" element={<Inicio />} />
            <Route path="/usuarios" element={<Protegida ruta="usuarios"><Usuarios /></Protegida>} />
            <Route path="/alertas" element={<Protegida ruta="alertas"><Alertas /></Protegida>} />
            <Route path="/logs" element={<Protegida ruta="logs"><Logs /></Protegida>} />
            <Route path="/exportacion" element={<Protegida ruta="exportacion"><Exportacion /></Protegida>} />
            <Route path="/reportes" element={<Protegida ruta="reportes"><Reportes /></Protegida>} />
            <Route path="/riesgo" element={<Protegida ruta="riesgo"><Riesgo /></Protegida>} />
            <Route path="/dashboard" element={<Protegida ruta="dashboard"><Dashboard /></Protegida>} />
            <Route path="/mapas" element={<Protegida ruta="mapas"><Suspense fallback={<Center py="xl"><Loader /></Center>}><Mapas /></Suspense></Protegida>} />
            <Route path="/solicitudes" element={<Protegida ruta="solicitudes"><Solicitudes /></Protegida>} />
            <Route path="/prorrogas" element={<Protegida ruta="prorrogas"><Prorrogas /></Protegida>} />
            <Route path="/hallazgos" element={<Protegida ruta="hallazgos"><Hallazgos /></Protegida>} />
            <Route path="/catalogos" element={<Protegida ruta="catalogos"><Catalogos /></Protegida>} />
            <Route path="/maestros" element={<Protegida ruta="maestros"><Maestros /></Protegida>} />
            <Route path="/agenda" element={<Protegida ruta="agenda"><Agenda /></Protegida>} />
            <Route path="/propuesta" element={<Protegida ruta="propuesta"><Propuesta /></Protegida>} />
            <Route path="/agenda/visita/:id" element={<Protegida ruta="agenda"><VisitaDetalle key="agenda" origen="agenda" /></Protegida>} />
            <Route path="/seguimiento" element={<Protegida ruta="agenda"><Seguimiento /></Protegida>} />
            <Route path="/mis-visitas" element={<Protegida ruta="mis-visitas"><MisVisitas /></Protegida>} />
            <Route path="/mis-visitas/visita/:id" element={<Protegida ruta="mis-visitas"><VisitaDetalle key="mis-visitas" origen="mis-visitas" /></Protegida>} />
            <Route path="/visita/:id" element={<Protegida ruta="visita"><Checklist /></Protegida>} />
            <Route path="/calendario" element={<Protegida ruta="calendario"><Calendario /></Protegida>} />
            <Route path="/opl" element={<Protegida ruta="opl"><Opl /></Protegida>} />
            <Route path="/opl/visita/:id" element={<Protegida ruta="opl"><OplVisita /></Protegida>} />
            <Route path="/calendario/visita/:id" element={<Protegida ruta="calendario"><VisitaDetalle key="calendario" origen="calendario" /></Protegida>} />
            {/* --- Presupuesto (rama presupuesto; Anexo 5 §4.6) --- */}
            <Route path="/presupuesto" element={<Protegida ruta="presupuesto"><Presupuesto /></Protegida>} />
            {/* --- fin presupuesto --- */}
            {/* --- Analítica (rama analitica) --- */}
            <Route path="/analitica/cargues" element={<Protegida ruta="analitica/cargues"><CarguesOpl /></Protegida>} />
            <Route path="/homologacion" element={<Protegida ruta="homologacion"><Homologacion /></Protegida>} />
            <Route path="/analitica" element={<Protegida ruta="analitica"><Analitica /></Protegida>} />
            <Route path="/analitica/trazabilidad" element={<Protegida ruta="analitica/trazabilidad"><TrazabilidadAnalitica /></Protegida>} />
            <Route path="/analitica/alertas" element={<Protegida ruta="analitica/alertas"><AlertasAnaliticas /></Protegida>} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Route>
        </Routes>
      </Suspense>
    </ProveedorSesion>
  );
}
