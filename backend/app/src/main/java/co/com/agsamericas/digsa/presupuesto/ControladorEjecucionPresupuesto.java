package co.com.agsamericas.digsa.presupuesto;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Ejecución presupuestal (carga, registro manual y versiones), proyección cargada y notificaciones formales de umbrales y desviaciones. */
@RestController
public class ControladorEjecucionPresupuesto {

    private final ServicioEjecucion ejecucion;
    private final ServicioProyeccion proyeccion;
    private final Notificaciones notificaciones;

    ControladorEjecucionPresupuesto(ServicioEjecucion ejecucion, ServicioProyeccion proyeccion, Notificaciones notificaciones) {
        this.ejecucion = ejecucion;
        this.proyeccion = proyeccion;
        this.notificaciones = notificaciones;
    }

    private static UsuarioSesion quien() {
        return UsuarioSesion.actual().orElseThrow();
    }

    @PostMapping("/api/presupuesto/ejecucion/simular")
    @PreAuthorize(ControladorPresupuesto.REGISTRAN)
    @Accion(modulo = "presupuesto", accion = "ejecucion_simular", operacion = Declarada.CONSULTA)
    public Map<String, Object> simular(@RequestPart("archivo") MultipartFile archivo) {
        return ejecucion.simular(archivo, quien());
    }

    @PostMapping("/api/presupuesto/ejecucion/aplicar")
    @PreAuthorize(ControladorPresupuesto.REGISTRAN)
    @Accion(modulo = "presupuesto", accion = "ejecucion_aplicar", operacion = Declarada.INSERCION)
    public Map<String, Object> aplicar(@RequestPart("archivo") MultipartFile archivo, @RequestParam(name = "fuente", required = false) String fuente) {
        return ejecucion.aplicar(archivo, fuente, quien());
    }

    @PostMapping("/api/presupuesto/ejecucion/manual")
    @PreAuthorize(ControladorPresupuesto.REGISTRAN)
    @Accion(modulo = "presupuesto", accion = "ejecucion_manual", operacion = Declarada.INSERCION)
    public ResponseEntity<Map<String, Object>> manual(@RequestBody ServicioEjecucion.Manual m) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ejecucion.manual(m, quien()));
    }

    @PostMapping("/api/presupuesto/proyeccion/simular")
    @PreAuthorize(ControladorPresupuesto.REGISTRAN)
    @Accion(modulo = "presupuesto", accion = "proyeccion_simular", operacion = Declarada.CONSULTA)
    public Map<String, Object> simularProyeccion(@RequestPart("archivo") MultipartFile archivo) {
        return proyeccion.simular(archivo, quien());
    }

    @PostMapping("/api/presupuesto/proyeccion/aplicar")
    @PreAuthorize(ControladorPresupuesto.REGISTRAN)
    @Accion(modulo = "presupuesto", accion = "proyeccion_aplicar", operacion = Declarada.INSERCION)
    public Map<String, Object> aplicarProyeccion(@RequestPart("archivo") MultipartFile archivo, @RequestParam(name = "fuente", required = false) String fuente) {
        return proyeccion.aplicar(archivo, fuente, quien());
    }

    @GetMapping("/api/presupuesto/ejecucion")
    @PreAuthorize(ControladorPresupuesto.CONSULTAN)
    @Accion(modulo = "presupuesto", accion = "ejecucion", operacion = Declarada.CONSULTA)
    public Map<String, Object> versiones(@RequestParam(required = false) Long vigencia, @RequestParam(required = false) String fuerza,
                                         @RequestParam(required = false) Long punto, @RequestParam(required = false) String modalidad,
                                         @RequestParam(required = false) String periodo, @RequestParam(required = false) Integer pagina) {
        return ejecucion.versiones(new ServicioEjecucion.Filtros(vigencia, fuerza, punto, modalidad, periodo, pagina), ControladorPresupuesto.ambito());
    }

    @GetMapping("/api/presupuesto/notificaciones")
    @PreAuthorize(ControladorPresupuesto.CONSULTAN)
    @Accion(modulo = "presupuesto", accion = "notificaciones", operacion = Declarada.CONSULTA)
    public Map<String, Object> notificaciones(@RequestParam(required = false) Long vigencia, @RequestParam(required = false) String tipo,
                                              @RequestParam(required = false) Integer pagina) {
        return notificaciones.lista(ControladorPresupuesto.ambito(), vigencia, tipo, pagina);
    }

    @PostMapping("/api/presupuesto/notificaciones/{id}/reenviar")
    @PreAuthorize(ControladorPresupuesto.REGISTRAN)
    @Accion(modulo = "presupuesto", accion = "notificacion_reenviar", operacion = Declarada.INSERCION)
    public Map<String, Object> reenviar(@PathVariable long id) {
        Notificaciones.Resultado r = notificaciones.reenviar(id, quien().id(), quien().usuario());
        return Map.of("mensaje", "Notificación " + r.consecutivo() + " reenviada a " + r.para().size() + " buzón(es).");
    }
}
