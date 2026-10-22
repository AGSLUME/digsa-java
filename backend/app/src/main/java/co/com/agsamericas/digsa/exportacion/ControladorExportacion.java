package co.com.agsamericas.digsa.exportacion;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** «Exportación de datos» (grupo Configuración): solo el Administrador (Pantallas.EXPORTACION); a cualquier otro perfil, 403. */
@RestController
public class ControladorExportacion {

    private final ServicioExportacion servicio;

    public ControladorExportacion(ServicioExportacion servicio) {
        this.servicio = servicio;
    }

    /** Pide la exportación completa: se genera en segundo plano (202) y se consulta su estado con GET. */
    @PostMapping("/api/exportacion")
    @PreAuthorize("@permisos.pantalla('EXPORTACION')")
    @Accion(modulo = "exportacion", accion = "solicitar", operacion = Declarada.INSERCION)
    public ResponseEntity<Map<String, Object>> solicitar() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(servicio.solicitar(UsuarioSesion.actual().orElseThrow()));
    }

    /** Estado de las exportaciones del usuario. La pantalla lo consulta cada pocos segundos: como el latido, no deja fila en la bitácora. */
    @GetMapping("/api/exportacion")
    @PreAuthorize("@permisos.pantalla('EXPORTACION')")
    @Accion(modulo = "exportacion", accion = "estado", operacion = Declarada.SIN_REGISTRO)
    public List<Map<String, Object>> estado() {
        return servicio.listar(UsuarioSesion.actual().orElseThrow());
    }

    /** Descarga en flujo desde el almacén; queda en la bitácora (exportacion_completa). */
    @GetMapping("/api/exportacion/{id}/descarga")
    @PreAuthorize("@permisos.pantalla('EXPORTACION')")
    @Accion(modulo = "exportacion", accion = "descarga", operacion = Declarada.CONSULTA)
    public ResponseEntity<InputStreamResource> descargar(@PathVariable long id) {
        ServicioExportacion.Descarga d = servicio.descargar(id, UsuarioSesion.actual().orElseThrow());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).contentLength(d.tamano())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.nombre() + "\"")
                .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new InputStreamResource(d.contenido()));
    }
}
