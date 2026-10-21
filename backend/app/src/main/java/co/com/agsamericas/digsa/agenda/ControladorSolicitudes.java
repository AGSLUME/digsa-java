package co.com.agsamericas.digsa.agenda;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Solicitudes de visita a necesidad: las registran la DIGSA y la Sanidad de cada fuerza; el coordinador las ve todas y las agenda
 * (POST /api/agenda con solicitudId) o las anula. Cada solicitante ve solo las suyas (decisión 9.4).
 */
@RestController
@RequestMapping("/api/solicitudes")
@PreAuthorize("@permisos.pantalla('SOLICITUDES')")
public class ControladorSolicitudes {

    record Anulacion(String motivo) {
    }

    private final ServicioSolicitudes servicio;

    public ControladorSolicitudes(ServicioSolicitudes servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    @Accion(modulo = "solicitudes", accion = "listar", operacion = Declarada.CONSULTA)
    public Map<String, Object> listar(@RequestParam(required = false) String estado) {
        UsuarioSesion u = UsuarioSesion.actual().orElseThrow();
        Set<co.com.agsamericas.digsa.reglas.Perfil> perfiles = UsuarioSesion.perfilesActuales();
        var alcance = UsuarioSesion.alcanceActual();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("solicitudes", servicio.listar(estado, u, perfiles));
        boolean solicita = servicio.entidad(perfiles, alcance).isPresent();
        m.put("puedeSolicitar", solicita);
        m.put("entidad", servicio.entidad(perfiles, alcance).orElse(null));
        m.put("puntos", solicita ? servicio.puntosSolicitables(alcance) : java.util.List.of());
        m.put("coordinador", ServicioSolicitudes.esCoordinador(perfiles));
        return m;
    }

    /** Registrar con el escrito opcional en PDF (campo «escrito»). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Accion(modulo = "solicitudes", accion = "registrar", operacion = Declarada.INSERCION)
    public ResponseEntity<Map<String, Object>> registrar(@RequestParam(name = "punto_id", required = false) Long puntoId,
                                                         @RequestParam(name = "fecha_solicitud", required = false) String fecha,
                                                         @RequestParam(required = false) String motivo,
                                                         @RequestPart(name = "escrito", required = false) MultipartFile escrito) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicio.registrar(puntoId, fecha, motivo, escrito, UsuarioSesion.actual().orElseThrow(),
                UsuarioSesion.perfilesActuales(), UsuarioSesion.alcanceActual()));
    }

    @PostMapping("/{id}/anular")
    @PreAuthorize("@permisos.pantalla('AGENDA')")
    @Accion(modulo = "solicitudes", accion = "anular", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> anular(@PathVariable long id, @RequestBody Anulacion a) {
        servicio.anular(id, a.motivo(), UsuarioSesion.actual().orElseThrow());
        return Map.of("mensaje", "Solicitud anulada.");
    }

    @GetMapping("/{id}/escrito")
    @Accion(modulo = "solicitudes", accion = "escrito", operacion = Declarada.CONSULTA)
    public ResponseEntity<InputStreamResource> escrito(@PathVariable long id) {
        ServicioSolicitudes.Escrito e = servicio.escrito(id, UsuarioSesion.actual().orElseThrow(), UsuarioSesion.perfilesActuales());
        try {
            String ascii = e.nombre().replaceAll("[^A-Za-z0-9._-]", "_");
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).contentLength(e.tamano())
                    .header("Content-Disposition", "inline; filename=\"" + ascii + "\"").header("X-Content-Type-Options", "nosniff")
                    .header("Cache-Control", "no-store").body(new InputStreamResource(servicio.abrir(e)));
        } catch (IOException ex) {
            throw new ErrorNegocio(HttpStatus.NOT_FOUND, "El escrito no está en el almacén.");
        }
    }
}
