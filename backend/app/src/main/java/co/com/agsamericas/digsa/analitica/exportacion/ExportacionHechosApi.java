package co.com.agsamericas.digsa.analitica.exportacion;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * «Exportación de hechos por periodo» (sección de «Cargues del OPL», Pantallas.ANALITICA_CARGUES): solo ADMINISTRADOR; cualquier otro
 * perfil, 403. Se pide un periodo (202), se consulta su progreso y se descarga cada mes (.csv.gz) y el LEEME.
 */
@RestController
@PreAuthorize("@permisos.pantalla('ANALITICA_CARGUES') and @permisosHechos.exportar()")
public class ExportacionHechosApi {

    /** Periodo pedido: meses aaaa-mm; sin «hasta», un solo mes. */
    public record Solicitud(String desde, String hasta) {
    }

    private final ServicioExportacionHechos servicio;

    ExportacionHechosApi(ServicioExportacionHechos servicio) {
        this.servicio = servicio;
    }

    private static UsuarioSesion quien() {
        return UsuarioSesion.actual().orElseThrow();
    }

    @PostMapping("/api/analitica/exportaciones-hechos")
    @Accion(modulo = "analitica", accion = "hechos_exportacion_solicitar", operacion = Declarada.INSERCION)
    public ResponseEntity<Map<String, Object>> solicitar(@RequestBody Solicitud s) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(servicio.solicitar(s == null ? null : s.desde(), s == null ? null : s.hasta(), quien()));
    }

    /** Exportaciones de hechos del usuario con sus meses. La pantalla la consulta cada pocos segundos: como el latido, sin fila en la bitácora. */
    @GetMapping("/api/analitica/exportaciones-hechos")
    @Accion(modulo = "analitica", accion = "hechos_exportacion_estado", operacion = Declarada.SIN_REGISTRO)
    public List<Map<String, Object>> listar() {
        return servicio.listar(quien());
    }

    @GetMapping("/api/analitica/exportaciones-hechos/{id}")
    @Accion(modulo = "analitica", accion = "hechos_exportacion_detalle", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> detalle(@PathVariable long id) {
        return servicio.detalle(id, quien());
    }

    /** El .csv.gz de un mes, en flujo desde el almacén; queda en la bitácora. */
    @GetMapping("/api/analitica/exportaciones-hechos/{id}/meses/{mes}")
    @Accion(modulo = "analitica", accion = "hechos_exportacion_descarga", operacion = Declarada.CONSULTA)
    public ResponseEntity<InputStreamResource> mes(@PathVariable long id, @PathVariable String mes) {
        ServicioExportacionHechos.Descarga d = servicio.descargarMes(id, mes, quien());
        return archivo(d, MediaType.parseMediaType("application/gzip"));
    }

    @GetMapping("/api/analitica/exportaciones-hechos/{id}/leeme")
    @Accion(modulo = "analitica", accion = "hechos_exportacion_leeme", operacion = Declarada.CONSULTA)
    public ResponseEntity<InputStreamResource> leeme(@PathVariable long id) {
        return archivo(servicio.leeme(id, quien()), MediaType.parseMediaType("text/plain;charset=UTF-8"));
    }

    private static ResponseEntity<InputStreamResource> archivo(ServicioExportacionHechos.Descarga d, MediaType tipo) {
        return ResponseEntity.ok().contentType(tipo).contentLength(d.tamano())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.nombre() + "\"")
                .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new InputStreamResource(d.contenido()));
    }
}
