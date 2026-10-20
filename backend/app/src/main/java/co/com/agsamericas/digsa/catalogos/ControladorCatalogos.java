package co.com.agsamericas.digsa.catalogos;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Catálogos: el Coordinador (y el Administrador) los edita en vivo (Anexo 5, prueba 4). */
@RestController
@RequestMapping("/api/catalogos")
@PreAuthorize("@permisos.pantalla('CATALOGOS')")
public class ControladorCatalogos {

    record Regional(String nombre, Boolean activo) {
    }

    record Parametro(String valor) {
    }

    private final ServicioCatalogos servicio;

    public ControladorCatalogos(ServicioCatalogos servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    @Accion(modulo = "catalogos", accion = "listar", operacion = Declarada.CONSULTA)
    public Map<String, Object> todo() {
        return servicio.todo();
    }

    /** Municipios de un departamento: también los usan los maestros y la agenda. */
    @GetMapping("/municipios")
    @PreAuthorize("isAuthenticated()")
    @Accion(modulo = "catalogos", accion = "municipios", operacion = Declarada.SIN_REGISTRO)
    public List<Map<String, Object>> municipios(@RequestParam String departamento) {
        return servicio.municipios(departamento);
    }

    @PostMapping("/fuerzas")
    @Accion(modulo = "catalogos", accion = "fuerza_crear", operacion = Declarada.INSERCION)
    public ResponseEntity<Map<String, Object>> crearFuerza(@RequestBody ServicioCatalogos.Fuerza f) {
        servicio.crearFuerza(f, UsuarioSesion.actual().orElseThrow());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("mensaje", "Fuerza creada."));
    }

    @PutMapping("/fuerzas/{codigo}")
    @Accion(modulo = "catalogos", accion = "fuerza_editar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> editarFuerza(@PathVariable String codigo, @RequestBody ServicioCatalogos.Fuerza f) {
        servicio.editarFuerza(codigo, f, UsuarioSesion.actual().orElseThrow());
        return Map.of("mensaje", "Fuerza actualizada.");
    }

    @DeleteMapping("/fuerzas/{codigo}")
    @Accion(modulo = "catalogos", accion = "fuerza_eliminar", operacion = Declarada.ELIMINACION)
    public Map<String, Object> eliminarFuerza(@PathVariable String codigo) {
        servicio.eliminarFuerza(codigo, UsuarioSesion.actual().orElseThrow());
        return Map.of("mensaje", "Fuerza eliminada.");
    }

    @PostMapping("/{catalogo}/valores")
    @Accion(modulo = "catalogos", accion = "valor_crear", operacion = Declarada.INSERCION)
    public ResponseEntity<Map<String, Object>> crearValor(@PathVariable String catalogo, @RequestBody ServicioCatalogos.Valor v) {
        long id = servicio.crearValor(catalogo, v, UsuarioSesion.actual().orElseThrow());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "mensaje", "Valor creado."));
    }

    @PutMapping("/valores/{id}")
    @Accion(modulo = "catalogos", accion = "valor_editar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> editarValor(@PathVariable long id, @RequestBody ServicioCatalogos.Valor v) {
        servicio.editarValor(id, v, UsuarioSesion.actual().orElseThrow());
        return Map.of("mensaje", "Valor actualizado.");
    }

    @DeleteMapping("/valores/{id}")
    @Accion(modulo = "catalogos", accion = "valor_eliminar", operacion = Declarada.ELIMINACION)
    public Map<String, Object> eliminarValor(@PathVariable long id) {
        servicio.eliminarValor(id, UsuarioSesion.actual().orElseThrow());
        return Map.of("mensaje", "Valor eliminado.");
    }

    @PostMapping("/regionales")
    @Accion(modulo = "catalogos", accion = "regional_crear", operacion = Declarada.INSERCION)
    public ResponseEntity<Map<String, Object>> crearRegional(@RequestBody Regional r) {
        long id = servicio.crearRegional(r.nombre(), UsuarioSesion.actual().orElseThrow());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "mensaje", "Regional creada."));
    }

    @PutMapping("/regionales/{id}")
    @Accion(modulo = "catalogos", accion = "regional_editar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> editarRegional(@PathVariable long id, @RequestBody Regional r) {
        servicio.editarRegional(id, r.nombre(), r.activo() == null || r.activo(), UsuarioSesion.actual().orElseThrow());
        return Map.of("mensaje", "Regional actualizada.");
    }

    @PutMapping("/parametros/{clave}")
    @Accion(modulo = "catalogos", accion = "parametro_editar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> editarParametro(@PathVariable String clave, @RequestBody Parametro p) {
        servicio.editarParametro(clave, p.valor(), UsuarioSesion.actual().orElseThrow());
        return Map.of("mensaje", "Parámetro actualizado.");
    }
}
