package co.com.agsamericas.digsa.agenda;

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

/** Propuesta de agenda: solo el coordinador (y el administrador). Nada se programa ni se notifica hasta confirmar. */
@RestController
@RequestMapping("/api/propuesta")
@PreAuthorize("@permisos.pantalla('PROPUESTA')")
public class ControladorPropuesta {

    private final ServicioPropuesta propuesta;

    public ControladorPropuesta(ServicioPropuesta propuesta) {
        this.propuesta = propuesta;
    }

    private static UsuarioSesion quien() {
        return UsuarioSesion.actual().orElseThrow();
    }

    @GetMapping
    @Accion(modulo = "propuesta", accion = "datos", operacion = Declarada.CONSULTA)
    public Map<String, Object> datos(@RequestParam(required = false) Integer anio) {
        return propuesta.datos(anio);
    }

    @GetMapping("/{id}")
    @Accion(modulo = "propuesta", accion = "ver", operacion = Declarada.CONSULTA)
    public Map<String, Object> ver(@PathVariable long id) {
        return propuesta.ver(id);
    }

    @PostMapping("/generar")
    @Accion(modulo = "propuesta", accion = "generar", operacion = Declarada.INSERCION)
    public Map<String, Object> generar(@RequestBody ServicioPropuesta.Generar g) {
        return propuesta.generar(g, quien());
    }

    @PutMapping("/items/{id}")
    @Accion(modulo = "propuesta", accion = "ajustar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> ajustar(@PathVariable long id, @RequestBody ServicioPropuesta.Ajuste a) {
        return Map.of("mensaje", propuesta.ajustar(id, a, quien()));
    }

    @PostMapping("/items/{id}/liberar")
    @Accion(modulo = "propuesta", accion = "liberar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> liberar(@PathVariable long id) {
        return Map.of("mensaje", propuesta.liberar(id));
    }

    @PostMapping("/{id}/recalcular")
    @Accion(modulo = "propuesta", accion = "recalcular", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> recalcular(@PathVariable long id) {
        return propuesta.recalcular(id, quien());
    }

    @PostMapping("/{id}/confirmar")
    @Accion(modulo = "propuesta", accion = "confirmar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> confirmar(@PathVariable long id) {
        return propuesta.confirmar(id, quien());
    }

    @PostMapping("/{id}/descartar")
    @Accion(modulo = "propuesta", accion = "descartar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> descartar(@PathVariable long id) {
        return Map.of("mensaje", propuesta.descartar(id, quien()));
    }
}
