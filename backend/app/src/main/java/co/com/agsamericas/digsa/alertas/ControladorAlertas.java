package co.com.agsamericas.digsa.alertas;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de administración de las alertas: el catálogo solo el Administrador; los eventos (con su latencia) y su cierre, también la
 * coordinación.
 */
@RestController
@RequestMapping("/api/alertas")
public class ControladorAlertas {

    private final ServicioCatalogoAlertas servicio;

    public ControladorAlertas(ServicioCatalogoAlertas servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/catalogo")
    @PreAuthorize("hasAuthority('PERFIL_ADMINISTRADOR')")
    @Accion(modulo = "alertas", accion = "catalogo", operacion = Declarada.CONSULTA)
    public Map<String, Object> catalogo() {
        return servicio.catalogo();
    }

    @PutMapping("/catalogo")
    @PreAuthorize("hasAuthority('PERFIL_ADMINISTRADOR')")
    @Accion(modulo = "alertas", accion = "catalogo_editar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> guardar(@RequestBody ServicioCatalogoAlertas.Edicion e) {
        return servicio.guardar(e, UsuarioSesion.actual().orElseThrow());
    }

    @GetMapping("/eventos")
    @PreAuthorize("hasAnyAuthority('PERFIL_ADMINISTRADOR', 'PERFIL_COORDINADOR')")
    @Accion(modulo = "alertas", accion = "eventos", operacion = Declarada.CONSULTA)
    public Map<String, Object> eventos(@RequestParam(defaultValue = "false") boolean abiertas, @RequestParam(defaultValue = "100") int limite) {
        return servicio.eventos(abiertas, limite);
    }

    @PostMapping("/eventos/{id}/cerrar")
    @PreAuthorize("hasAnyAuthority('PERFIL_ADMINISTRADOR', 'PERFIL_COORDINADOR')")
    @Accion(modulo = "alertas", accion = "cerrar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> cerrar(@PathVariable long id) {
        return servicio.cerrar(id, UsuarioSesion.actual().orElseThrow());
    }
}
