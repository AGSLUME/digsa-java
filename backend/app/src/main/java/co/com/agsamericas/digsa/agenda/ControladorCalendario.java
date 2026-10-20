package co.com.agsamericas.digsa.agenda;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Calendario de consulta de la DIGSA, Sanidad y ESM: cada uno ve solo su alcance (el filtro va en el SQL). */
@RestController
@RequestMapping("/api/calendario")
@PreAuthorize("@permisos.pantalla('CALENDARIO')")
public class ControladorCalendario {

    private final ServicioConsultaAgenda consulta;

    public ControladorCalendario(ServicioConsultaAgenda consulta) {
        this.consulta = consulta;
    }

    @GetMapping
    @Accion(modulo = "calendario", accion = "listar", operacion = Declarada.CONSULTA)
    public List<Map<String, Object>> listar(@RequestParam(required = false) String desde, @RequestParam(required = false) String hasta,
                                            @RequestParam(required = false) String estado, @RequestParam(required = false) String tipo) {
        return consulta.listar(new ServicioConsultaAgenda.Filtros(desde, hasta, estado, null, null, null, null, tipo), UsuarioSesion.alcanceActual());
    }

    @GetMapping("/{id}")
    @Accion(modulo = "calendario", accion = "detalle", operacion = Declarada.CONSULTA)
    public Map<String, Object> detalle(@PathVariable long id) {
        return consulta.detalle(id, UsuarioSesion.actual().orElseThrow().id(), java.util.Set.of(), UsuarioSesion.alcanceActual());
    }
}
