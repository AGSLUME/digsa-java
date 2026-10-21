package co.com.agsamericas.digsa.opl;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** «Respuesta a hallazgos» del usuario OPL (nacional: país; regional: su regional) y la decisión del auditor líder sobre las objeciones. */
@RestController
public class ControladorOpl {

    record Plan(List<ServicioOpl.FilaOpl> filas) {
    }

    record Resolucion(String clave, String resolucion, String motivo) {
    }

    private final ServicioOpl opl;
    private final TareaDiariaOpl tarea;

    public ControladorOpl(ServicioOpl opl, TareaDiariaOpl tarea) {
        this.opl = opl;
        this.tarea = tarea;
    }

    private static UsuarioSesion quien() {
        return UsuarioSesion.actual().orElseThrow();
    }

    private ServicioOpl.Acceso acceso(long id) {
        return opl.accesoDeUsuario(id, UsuarioSesion.alcanceActual());
    }

    private static ServicioOpl.Actor actor(HttpServletRequest req) {
        return ServicioOpl.Actor.de(quien(), req.getRemoteAddr());
    }

    @GetMapping("/api/opl/visitas")
    @PreAuthorize("@permisos.pantalla('OPL')")
    @Accion(modulo = "opl", accion = "lista", operacion = Declarada.CONSULTA)
    public List<Map<String, Object>> lista() {
        return opl.lista(UsuarioSesion.alcanceActual());
    }

    @GetMapping("/api/opl/visitas/{id}")
    @PreAuthorize("@permisos.pantalla('OPL')")
    @Accion(modulo = "opl", accion = "detalle", operacion = Declarada.CONSULTA)
    public Map<String, Object> detalle(@PathVariable long id) {
        return opl.detalle(acceso(id));
    }

    @PostMapping("/api/opl/visitas/{id}/respuestas")
    @PreAuthorize("@permisos.pantalla('OPL')")
    @Accion(modulo = "opl", accion = "responder", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> responder(@PathVariable long id, @RequestBody ServicioOpl.Respuesta r, HttpServletRequest req) {
        return Map.of("mensaje", opl.responder(acceso(id), r, actor(req)));
    }

    @PostMapping(value = "/api/opl/visitas/{id}/soportes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@permisos.pantalla('OPL')")
    @Accion(modulo = "opl", accion = "soportes", operacion = Declarada.SIN_REGISTRO)
    public ResponseEntity<Map<String, Object>> soportes(@PathVariable long id, @RequestParam String clave, @RequestPart("archivos") List<MultipartFile> archivos,
                                                        HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(opl.subirSoportes(acceso(id), clave, archivos, actor(req)));
    }

    @PutMapping("/api/opl/visitas/{id}/plan")
    @PreAuthorize("@permisos.pantalla('OPL')")
    @Accion(modulo = "opl", accion = "plan", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> plan(@PathVariable long id, @RequestBody Plan p, HttpServletRequest req) {
        return opl.guardarPlan(acceso(id), p.filas(), actor(req));
    }

    @PostMapping("/api/opl/visitas/{id}/firma")
    @PreAuthorize("@permisos.pantalla('OPL')")
    @Accion(modulo = "opl", accion = "firma", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> firma(@PathVariable long id, @RequestBody ServicioOpl.FirmaPlan f, HttpServletRequest req) {
        return Map.of("mensaje", opl.firmar(acceso(id), f, actor(req)));
    }

    @GetMapping("/api/opl/visitas/{id}/acta")
    @PreAuthorize("@permisos.pantalla('OPL')")
    @Accion(modulo = "opl", accion = "acta", operacion = Declarada.CONSULTA)
    public ResponseEntity<InputStreamResource> acta(@PathVariable long id) {
        ServicioOpl.Acceso a = acceso(id);
        return ControladorOplPublico.descarga(opl, opl.acta(a));
    }

    @GetMapping("/api/opl/visitas/{id}/soportes/{soporteId}")
    @PreAuthorize("@permisos.pantalla('OPL')")
    @Accion(modulo = "opl", accion = "soporte", operacion = Declarada.CONSULTA)
    public ResponseEntity<InputStreamResource> soporte(@PathVariable long id, @PathVariable long soporteId) {
        ServicioOpl.Acceso a = acceso(id);
        return ControladorOplPublico.descarga(opl, opl.soporte(a, soporteId));
    }

    // ---------- Equipo de la visita: respuestas del OPL y decisión del líder ----------

    /** Respuestas del OPL vistas desde la visita (equipo y coordinación). */
    @GetMapping("/api/visitas/{id}/opl")
    @PreAuthorize("@permisos.pantalla('VISITA')")
    @Accion(modulo = "opl", accion = "respuestas", operacion = Declarada.CONSULTA)
    public Map<String, Object> respuestasDeVisita(@PathVariable long id) {
        return opl.paraEquipo(id, quien(), UsuarioSesion.alcanceActual());
    }

    @PostMapping("/api/visitas/{id}/opl/resolucion")
    @PreAuthorize("@permisos.pantalla('VISITA')")
    @Accion(modulo = "opl", accion = "resolver", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> resolver(@PathVariable long id, @RequestBody Resolucion r) {
        return Map.of("mensaje", opl.resolver(id, r.clave(), r.resolucion(), r.motivo(), quien()));
    }

    /** Corre la tarea diaria a mano (administrador): aceptación automática, plan vencido y reporte a la DIGSA. */
    @PostMapping("/api/opl/tarea-diaria")
    @PreAuthorize("hasAuthority('PERFIL_ADMINISTRADOR')")
    @Accion(modulo = "opl", accion = "tarea_diaria", operacion = Declarada.ACTUALIZACION)
    public TareaDiariaOpl.Resumen tareaDiaria() {
        return tarea.correr();
    }
}
