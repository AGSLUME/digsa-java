package co.com.agsamericas.digsa.maestros;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import co.com.agsamericas.digsa.reglas.Reparto;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Puntos, auditores y operador logístico: el Coordinador (y el Administrador). */
@RestController
@RequestMapping("/api/maestros")
@PreAuthorize("@permisos.pantalla('MAESTROS')")
public class ControladorMaestros {

    record Estado(boolean activo) {
    }

    private final ServicioMaestros servicio;

    public ControladorMaestros(ServicioMaestros servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/puntos")
    @Accion(modulo = "maestros", accion = "puntos", operacion = Declarada.CONSULTA)
    public List<Map<String, Object>> puntos(@RequestParam(required = false) String unidad, @RequestParam(required = false) String fuerza,
                                            @RequestParam(required = false) String texto, @RequestParam(required = false) Boolean activo) {
        return servicio.puntos(unidad, fuerza, texto, activo);
    }

    @PostMapping("/puntos")
    @Accion(modulo = "maestros", accion = "punto_crear", operacion = Declarada.INSERCION)
    public ResponseEntity<Map<String, Object>> crearPunto(@RequestBody ServicioMaestros.Punto p) {
        long id = servicio.crearPunto(p, UsuarioSesion.actual().orElseThrow());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "mensaje", "Punto creado."));
    }

    @PutMapping("/puntos/{id}")
    @Accion(modulo = "maestros", accion = "punto_editar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> editarPunto(@PathVariable long id, @RequestBody ServicioMaestros.Punto p) {
        servicio.editarPunto(id, p, UsuarioSesion.actual().orElseThrow());
        return Map.of("mensaje", "Punto actualizado.");
    }

    @PutMapping("/puntos/{id}/estado")
    @Accion(modulo = "maestros", accion = "punto_estado", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> estadoPunto(@PathVariable long id, @RequestBody Estado e) {
        servicio.estadoPunto(id, e.activo(), UsuarioSesion.actual().orElseThrow());
        return Map.of("mensaje", e.activo() ? "Punto activado." : "Punto inactivado.");
    }

    @GetMapping("/auditores")
    @Accion(modulo = "maestros", accion = "auditores", operacion = Declarada.CONSULTA)
    public Map<String, Object> auditores() {
        return Map.of("auditores", servicio.auditores(), "roles", Reparto.ROLES);
    }

    @PutMapping("/auditores/{id}")
    @Accion(modulo = "maestros", accion = "auditor_editar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> editarAuditor(@PathVariable long id, @RequestBody ServicioMaestros.Auditor a) {
        servicio.editarAuditor(id, a, UsuarioSesion.actual().orElseThrow());
        return Map.of("mensaje", "Auditor actualizado.");
    }

    @GetMapping("/opl")
    @Accion(modulo = "maestros", accion = "opl", operacion = Declarada.CONSULTA)
    public Map<String, Object> opl() {
        return servicio.opl();
    }

    @PutMapping("/opl")
    @Accion(modulo = "maestros", accion = "opl_editar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> guardarOpl(@RequestBody ServicioMaestros.Opl o) {
        servicio.guardarOpl(o, UsuarioSesion.actual().orElseThrow());
        return Map.of("mensaje", "Operador logístico actualizado.");
    }
}
