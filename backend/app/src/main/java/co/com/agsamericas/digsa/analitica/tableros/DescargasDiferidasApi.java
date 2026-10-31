package co.com.agsamericas.digsa.analitica.tableros;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * «Mis descargas del día» de analítica (A7.3, duda AN.16): los CSV de registros de origen grandes que el usuario pidió desde la trazabilidad
 * y que se generan en segundo plano. Cada usuario ve y descarga solo las suyas (otro: 404), con la pantalla de analítica; las de registros
 * de origen exigen además el permiso de trazabilidad al descargarlas. Se piden desde las descargas de siempre (202, {@link TablerosApi}).
 */
@RestController
@PreAuthorize("@permisos.pantalla('ANALITICA')")
public class DescargasDiferidasApi {

    private final ServicioDescargaAnalitica servicio;

    DescargasDiferidasApi(ServicioDescargaAnalitica servicio) {
        this.servicio = servicio;
    }

    private static UsuarioSesion quien() {
        return UsuarioSesion.actual().orElseThrow();
    }

    /** Las descargas vigentes del usuario. La pantalla la consulta cada pocos segundos mientras hay una en curso: sin fila en la bitácora. */
    @GetMapping("/api/analitica/descargas-diferidas")
    @Accion(modulo = "analitica", accion = "descargas_diferidas", operacion = Declarada.SIN_REGISTRO)
    public List<Map<String, Object>> listar() {
        return servicio.listar(quien());
    }

    @GetMapping("/api/analitica/descargas-diferidas/{id}")
    @Accion(modulo = "analitica", accion = "descarga_diferida_estado", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> detalle(@PathVariable long id) {
        return servicio.detalle(id, quien());
    }

    /** El archivo, en flujo desde el almacén, con su SHA-256 y la huella de los datos; queda en la bitácora. */
    @GetMapping("/api/analitica/descargas-diferidas/{id}/archivo")
    @Accion(modulo = "analitica", accion = "descarga_diferida_archivo", operacion = Declarada.CONSULTA)
    public ResponseEntity<InputStreamResource> archivo(@PathVariable long id) {
        ServicioDescargaAnalitica.Archivo a = servicio.descargar(id, quien(), UsuarioSesion.alcanceActual());
        ResponseEntity.BodyBuilder r = ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).contentLength(a.tamano())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + a.nombre() + "\"").header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Sha256", a.sha256());
        if (a.huella() != null) {
            r = r.header("X-Huella-Datos", a.huella());
        }
        return r.body(new InputStreamResource(a.contenido()));
    }
}
