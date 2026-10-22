package co.com.agsamericas.digsa.reglas;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Pantallas de la aplicación y quién entra a cada una (equivalente de Rutas.php). El Administrador entra a todas; sin perfiles
 * exigidos entra cualquier usuario con sesión. El menú del frontend se arma con esta tabla y el backend la usa para autorizar.
 */
public enum Pantallas {
    INICIO("inicio", "Inicio", null, Set.of()),
    AGENDA("agenda", "Agenda de visitas", "Visitas", Set.of(Perfil.COORDINADOR)),
    PROPUESTA("propuesta", "Propuesta de agenda", "Visitas", Set.of(Perfil.COORDINADOR)),
    MIS_VISITAS("mis-visitas", "Mis visitas", "Visitas", Set.of(Perfil.AUDITOR)),
    HALLAZGOS("hallazgos", "Prestadores con hallazgos", "Visitas",
            union(Set.of(Perfil.AUDITOR, Perfil.COORDINADOR), Alcance.PERFILES_CONSULTA)),
    CALENDARIO("calendario", "Calendario de visitas", "Visitas", Alcance.PERFILES_CONSULTA),
    SOLICITUDES("solicitudes", "Solicitudes de visita", "Visitas", Set.of(Perfil.COORDINADOR, Perfil.DIGSA, Perfil.SANIDAD)),
    /** Prórrogas del plan: las aprueban el coordinador y la DIGSA (decisión 12.1). */
    PRORROGAS("prorrogas", "Prórrogas del plan", "Visitas", Set.of(Perfil.COORDINADOR, Perfil.DIGSA)),
    OPL("opl", "Respuesta a hallazgos", "Visitas", Set.of(Perfil.OPL_NACIONAL, Perfil.OPL_REGIONAL)),
    RIESGO("riesgo", "Dashboard de riesgo", "Visitas", union(Set.of(Perfil.COORDINADOR), Alcance.PERFILES_CONSULTA)),
    DASHBOARD("dashboard", "Dashboard", "Visitas", union(Set.of(Perfil.COORDINADOR), Alcance.PERFILES_CONSULTA)),
    REPORTES("reportes", "Reportes de visitas", "Visitas", union(Set.of(Perfil.COORDINADOR), Alcance.PERFILES_CONSULTA)),
    MAESTROS("maestros", "Puntos y auditores", "Configuración", Set.of(Perfil.COORDINADOR)),
    CATALOGOS("catalogos", "Catálogos", "Configuración", Set.of(Perfil.COORDINADOR)),
    /** Catálogo de alertas (lo edita el administrador) y eventos con la latencia de la bandeja de salida (fase 10; GMI P75 y P77 b). */
    ALERTAS("alertas", "Alertas", "Configuración", Set.of(Perfil.COORDINADOR)),
    USUARIOS("usuarios", "Usuarios", "Configuración", Set.of(Perfil.ADMINISTRADOR)),
    EXPORTACION("exportacion", "Exportación de datos", "Configuración", Set.of(Perfil.ADMINISTRADOR)),
    LOGS("logs", "Logs del sistema", "Configuración", Set.of(Perfil.ADMINISTRADOR)),
    VISITA("visita", "Visita", null, Set.of(Perfil.AUDITOR, Perfil.COORDINADOR));

    private final String ruta;
    private final String titulo;
    private final String grupo;
    private final Set<Perfil> perfiles;

    Pantallas(String ruta, String titulo, String grupo, Set<Perfil> perfiles) {
        this.ruta = ruta;
        this.titulo = titulo;
        this.grupo = grupo;
        this.perfiles = perfiles;
    }

    public String ruta() {
        return ruta;
    }

    public String titulo() {
        return titulo;
    }

    public String grupo() {
        return grupo;
    }

    public Set<Perfil> perfiles() {
        return perfiles;
    }

    /** ¿Entra quien tiene estos perfiles? Sin sesión (sin perfiles ni la marca de sesión) no entra nadie. */
    public boolean puedeEntrar(Collection<Perfil> perfilesDelUsuario, boolean conSesion) {
        if (!conSesion) {
            return false;
        }
        if (perfilesDelUsuario.contains(Perfil.ADMINISTRADOR) || perfiles.isEmpty()) {
            return true;
        }
        return perfiles.stream().anyMatch(perfilesDelUsuario::contains);
    }

    /** Pantallas que no van en el menú. */
    public boolean oculta() {
        return this == VISITA;
    }

    private static Set<Perfil> union(Set<Perfil> a, Set<Perfil> b) {
        EnumSet<Perfil> u = EnumSet.noneOf(Perfil.class);
        u.addAll(a);
        u.addAll(b);
        return Set.copyOf(u);
    }
}
