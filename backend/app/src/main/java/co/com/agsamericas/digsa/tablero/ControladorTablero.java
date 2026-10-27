package co.com.agsamericas.digsa.tablero;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import co.com.agsamericas.digsa.reglas.Riesgo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard de riesgo, dashboard de visitas y mapas: coordinación y perfiles de consulta en su alcance; pesos e incidencias de la coordinación. */
@RestController
public class ControladorTablero {

    record PesosEntrada(Map<String, Integer> pesos, int alto, int medio) {
    }

    private final ServicioRiesgo riesgo;
    private final ServicioDashboard dashboard;
    private final ServicioMapas mapas;
    private final ExportadorTablero exportador;
    private final RegistroBitacora bitacora;

    public ControladorTablero(ServicioRiesgo riesgo, ServicioDashboard dashboard, ServicioMapas mapas, ExportadorTablero exportador,
                              RegistroBitacora bitacora) {
        this.riesgo = riesgo;
        this.dashboard = dashboard;
        this.mapas = mapas;
        this.exportador = exportador;
        this.bitacora = bitacora;
    }

    private static UsuarioSesion quien() {
        return UsuarioSesion.actual().orElseThrow();
    }

    @GetMapping("/api/riesgo")
    @PreAuthorize("@permisos.pantalla('RIESGO')")
    @Accion(modulo = "riesgo", accion = "tablero", operacion = Declarada.CONSULTA)
    public Map<String, Object> tablero(@RequestParam(required = false) String fuerza) {
        return riesgo.tablero(UsuarioSesion.alcanceActual(), fuerza);
    }

    @GetMapping("/api/riesgo/exportar")
    @PreAuthorize("@permisos.pantalla('RIESGO')")
    @Accion(modulo = "riesgo", accion = "exportar", operacion = Declarada.CONSULTA)
    public ResponseEntity<byte[]> exportarRiesgo(@RequestParam(defaultValue = "xlsx") String formato, @RequestParam(required = false) String fuerza) {
        ExportadorTablero.Archivo a = exportador.riesgo(riesgo.tablero(UsuarioSesion.alcanceActual(), fuerza), formato, quien().usuario());
        bitacora.registrar("reporte_descargado", "Dashboard de riesgo descargado (" + a.nombre() + ")", quien().id(), quien().usuario());
        return descarga(a);
    }

    /** Pesos y umbrales: solo la coordinación (14.1). */
    @PutMapping("/api/riesgo/pesos")
    @PreAuthorize("@permisos.pantalla('AGENDA')")
    @Accion(modulo = "riesgo", accion = "pesos", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> pesos(@RequestBody PesosEntrada p) {
        return Map.of("mensaje", riesgo.guardarPesos(new Riesgo.Pesos(p.pesos() == null ? Map.of() : p.pesos(), p.alto(), p.medio()), quien()));
    }

    @GetMapping("/api/riesgo/incidencias")
    @PreAuthorize("@permisos.pantalla('RIESGO')")
    @Accion(modulo = "riesgo", accion = "incidencias", operacion = Declarada.CONSULTA)
    public List<Map<String, Object>> incidencias() {
        return riesgo.incidencias(UsuarioSesion.alcanceActual());
    }

    /** Las incidencias de la DIGSA las registran y cierran la coordinación y la DIGSA. */
    @PostMapping("/api/riesgo/incidencias")
    @PreAuthorize("hasAnyAuthority('PERFIL_COORDINADOR', 'PERFIL_ADMINISTRADOR', 'PERFIL_DIGSA')")
    @Accion(modulo = "riesgo", accion = "incidencia_registrar", operacion = Declarada.INSERCION)
    public Map<String, Object> registrar(@RequestBody ServicioRiesgo.Incidencia i) {
        return Map.of("id", riesgo.registrarIncidencia(i, quien()), "mensaje", "Incidencia registrada.");
    }

    @PostMapping("/api/riesgo/incidencias/{id}/cerrar")
    @PreAuthorize("hasAnyAuthority('PERFIL_COORDINADOR', 'PERFIL_ADMINISTRADOR', 'PERFIL_DIGSA')")
    @Accion(modulo = "riesgo", accion = "incidencia_cerrar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> cerrar(@PathVariable long id, @RequestBody ServicioRiesgo.Cierre c) {
        return Map.of("mensaje", riesgo.cerrarIncidencia(id, c, quien()));
    }

    @GetMapping("/api/dashboard/opciones")
    @PreAuthorize("@permisos.pantalla('DASHBOARD')")
    @Accion(modulo = "dashboard", accion = "opciones", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> opciones() {
        return dashboard.opciones(UsuarioSesion.alcanceActual());
    }

    @GetMapping("/api/dashboard")
    @PreAuthorize("@permisos.pantalla('DASHBOARD')")
    @Accion(modulo = "dashboard", accion = "datos", operacion = Declarada.CONSULTA)
    public Map<String, Object> datos(@RequestParam(required = false) String desde, @RequestParam(required = false) String hasta,
                                     @RequestParam(required = false) String fuerza, @RequestParam(required = false) String tipo,
                                     @RequestParam(required = false) Long auditor, @RequestParam(required = false) String unidad) {
        return dashboard.datos(new ServicioDashboard.Filtros(desde, hasta, fuerza, tipo, auditor, unidad), UsuarioSesion.alcanceActual());
    }

    @GetMapping("/api/dashboard/exportar")
    @PreAuthorize("@permisos.pantalla('DASHBOARD')")
    @Accion(modulo = "dashboard", accion = "exportar", operacion = Declarada.CONSULTA)
    public ResponseEntity<byte[]> exportarDashboard(@RequestParam(defaultValue = "xlsx") String formato, @RequestParam(required = false) String desde,
                                                    @RequestParam(required = false) String hasta, @RequestParam(required = false) String fuerza,
                                                    @RequestParam(required = false) String tipo, @RequestParam(required = false) Long auditor,
                                                    @RequestParam(required = false) String unidad) {
        Map<String, Object> d = dashboard.datos(new ServicioDashboard.Filtros(desde, hasta, fuerza, tipo, auditor, unidad), UsuarioSesion.alcanceActual());
        ExportadorTablero.Archivo a = exportador.dashboard(d, formato, quien().usuario());
        bitacora.registrar("reporte_descargado", "Dashboard de visitas descargado (" + a.nombre() + ")", quien().id(), quien().usuario());
        return descarga(a);
    }

    /** Las mismas opciones de filtro del dashboard (un rol puede tener los mapas sin el dashboard) y las capitales del mapa político. */
    @GetMapping("/api/mapas/opciones")
    @PreAuthorize("@permisos.pantalla('MAPAS')")
    @Accion(modulo = "mapas", accion = "opciones", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> opcionesMapas() {
        Map<String, Object> r = new LinkedHashMap<>(dashboard.opciones(UsuarioSesion.alcanceActual()));
        r.put("capitales", mapas.capitales());
        return r;
    }

    @GetMapping("/api/mapas/visitas")
    @PreAuthorize("@permisos.pantalla('MAPAS')")
    @Accion(modulo = "mapas", accion = "visitas", operacion = Declarada.CONSULTA)
    public Map<String, Object> mapaVisitas(@RequestParam(required = false) String desde, @RequestParam(required = false) String hasta,
                                           @RequestParam(required = false) String fuerza, @RequestParam(required = false) String tipo,
                                           @RequestParam(required = false) Long auditor, @RequestParam(required = false) String unidad) {
        return mapas.visitas(new ServicioDashboard.Filtros(desde, hasta, fuerza, tipo, auditor, unidad), UsuarioSesion.alcanceActual());
    }

    @GetMapping("/api/mapas/cobertura")
    @PreAuthorize("@permisos.pantalla('MAPAS')")
    @Accion(modulo = "mapas", accion = "cobertura", operacion = Declarada.CONSULTA)
    public Map<String, Object> mapaCobertura(@RequestParam(required = false) String desde, @RequestParam(required = false) String hasta,
                                             @RequestParam(required = false) String fuerza, @RequestParam(required = false) String tipo,
                                             @RequestParam(required = false) Long auditor, @RequestParam(required = false) String unidad) {
        return mapas.cobertura(new ServicioDashboard.Filtros(desde, hasta, fuerza, tipo, auditor, unidad), UsuarioSesion.alcanceActual());
    }

    private static ResponseEntity<byte[]> descarga(ExportadorTablero.Archivo a) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + a.nombre() + "\"").header(HttpHeaders.CONTENT_TYPE, a.tipo())
                .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "no-store").body(a.contenido());
    }
}
