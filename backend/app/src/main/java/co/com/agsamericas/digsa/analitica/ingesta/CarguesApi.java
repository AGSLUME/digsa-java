package co.com.agsamericas.digsa.analitica.ingesta;

import co.com.agsamericas.digsa.analitica.exportacion.PermisosExportacionHechos;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.InputStreamResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * «Cargues del OPL» (Pantallas.ANALITICA_CARGUES, grupo Información): subida por partes, mapeo, bitácora de cargues, rechazos y
 * original. Crear y mapear: ADMINISTRADOR y ANALISTA; consultar: también COORDINADOR; el original: solo ADMINISTRADOR.
 */
@RestController
@PreAuthorize("@permisos.pantalla('ANALITICA_CARGUES')")
public class CarguesApi {

    private static final String CSV = "text/csv;charset=UTF-8";
    private final ServicioCargues servicio;
    private final PermisosExportacionHechos permisosHechos;

    CarguesApi(ServicioCargues servicio, PermisosExportacionHechos permisosHechos) {
        this.servicio = servicio;
        this.permisosHechos = permisosHechos;
    }

    private static UsuarioSesion quien() {
        return UsuarioSesion.actual().orElseThrow();
    }

    /** Crea el cargue con nombre, tamaño, SHA-256 y número de partes (201), o devuelve el ya aplicado con el mismo SHA-256 (200). */
    @PostMapping("/api/analitica/cargues")
    @PreAuthorize("@permisos.pantalla('ANALITICA_CARGUES') and @permisosAnalitica.cargar()")
    @Accion(modulo = "analitica", accion = "cargue_crear", operacion = Declarada.INSERCION)
    public ResponseEntity<Map<String, Object>> crear(@RequestBody ServicioCargues.Solicitud s) {
        Map<String, Object> r = servicio.crear(s, quien());
        return ResponseEntity.status(Boolean.TRUE.equals(r.get("existente")) ? HttpStatus.OK : HttpStatus.CREATED).body(r);
    }

    /** Una parte del archivo (application/octet-stream, ≤ 50 MB), en flujo al almacén. */
    @PutMapping(value = "/api/analitica/cargues/{id}/partes/{numero}", consumes = MediaType.ALL_VALUE)
    @PreAuthorize("@permisos.pantalla('ANALITICA_CARGUES') and @permisosAnalitica.cargar()")
    @Accion(modulo = "analitica", accion = "cargue_parte", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> parte(@PathVariable long id, @PathVariable int numero, HttpServletRequest peticion) throws IOException {
        return servicio.recibirParte(id, numero, peticion.getInputStream(), quien());
    }

    /** Ensambla, verifica el SHA-256 y propone el mapeo (estado «mapeo»). */
    @PostMapping("/api/analitica/cargues/{id}/cerrar")
    @PreAuthorize("@permisos.pantalla('ANALITICA_CARGUES') and @permisosAnalitica.cargar()")
    @Accion(modulo = "analitica", accion = "cargue_cerrar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> cerrar(@PathVariable long id) {
        return servicio.cerrar(id, quien());
    }

    @GetMapping("/api/analitica/cargues/{id}/mapeo")
    @PreAuthorize("@permisos.pantalla('ANALITICA_CARGUES') and @permisosAnalitica.cargar()")
    @Accion(modulo = "analitica", accion = "cargue_mapeo_ver", operacion = Declarada.CONSULTA)
    public Map<String, Object> mapeo(@PathVariable long id) {
        return servicio.mapeo(id, quien());
    }

    /**
     * Vista previa enmascarada con el mapeo que el usuario tiene en pantalla ({@code columnas}: clave del campo por columna, separadas
     * por coma). Se pide en cada cambio del mapeo y no trae datos personales: como el estado, no deja fila en la bitácora.
     */
    @GetMapping("/api/analitica/cargues/{id}/vista-previa")
    @PreAuthorize("@permisos.pantalla('ANALITICA_CARGUES') and @permisosAnalitica.cargar()")
    @Accion(modulo = "analitica", accion = "cargue_vista_previa", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> vistaPrevia(@PathVariable long id, @RequestParam(required = false) List<String> columnas) {
        return servicio.vistaPrevia(id, columnas, quien());
    }

    /** Cargues y registros que ocultaría «Reemplazar el periodo» con el periodo declarado (para la confirmación de la pantalla). */
    @GetMapping("/api/analitica/cargues/{id}/reemplazo")
    @PreAuthorize("@permisos.pantalla('ANALITICA_CARGUES') and @permisosAnalitica.cargar()")
    @Accion(modulo = "analitica", accion = "cargue_reemplazo_ver", operacion = Declarada.CONSULTA)
    public Map<String, Object> reemplazo(@PathVariable long id,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return servicio.reemplazo(id, desde, hasta, quien());
    }

    /** Descarta un cargue en subida o en mapeo (el dueño o el ADMINISTRADOR): borra el archivo y sus partes; el cargue queda «descartado». */
    @DeleteMapping("/api/analitica/cargues/{id}")
    @PreAuthorize("@permisos.pantalla('ANALITICA_CARGUES') and @permisosAnalitica.cargar()")
    @Accion(modulo = "analitica", accion = "cargue_descartar", operacion = Declarada.ELIMINACION)
    public Map<String, Object> descartar(@PathVariable long id) {
        return servicio.descartar(id, quien());
    }

    /** Confirma el mapeo, el modo (agregar o reemplazar el periodo) y la plantilla; el cargue entra a la cola (202). */
    @PostMapping("/api/analitica/cargues/{id}/mapeo")
    @PreAuthorize("@permisos.pantalla('ANALITICA_CARGUES') and @permisosAnalitica.cargar()")
    @Accion(modulo = "analitica", accion = "cargue_mapeo", operacion = Declarada.ACTUALIZACION)
    public ResponseEntity<Map<String, Object>> confirmar(@PathVariable long id, @RequestBody ServicioCargues.Confirmacion c) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(servicio.confirmar(id, c, quien()));
    }

    /** Bitácora de cargues (paginada, con filtros). */
    @GetMapping("/api/analitica/cargues")
    @Accion(modulo = "analitica", accion = "cargues_bitacora", operacion = Declarada.CONSULTA)
    public Map<String, Object> listar(@RequestParam(required = false) String fuente, @RequestParam(required = false) String estado,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                      @RequestParam(required = false) Integer pagina) {
        Map<String, Object> m = servicio.listar(new ServicioCargues.Filtros(fuente, estado, desde, hasta, pagina));
        // A7.2: la sección de la exportación de hechos se muestra con la misma regla que la autoriza (@permisosHechos.exportar())
        m.put("exportaHechos", permisosHechos.exportar());
        return m;
    }

    /** Estado y progreso de un cargue. La pantalla lo consulta cada 2 s: como el latido, no deja fila en la bitácora. */
    @GetMapping("/api/analitica/cargues/{id}")
    @Accion(modulo = "analitica", accion = "cargue_estado", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> detalle(@PathVariable long id) {
        return servicio.detalle(id);
    }

    /** CSV de rechazos (línea; causa; columna), sin el contenido de las celdas; se escribe en flujo en la respuesta. */
    @GetMapping("/api/analitica/cargues/{id}/rechazos.csv")
    @Accion(modulo = "analitica", accion = "cargue_rechazos", operacion = Declarada.CONSULTA)
    public void rechazos(@PathVariable long id, HttpServletResponse respuesta) throws IOException {
        servicio.detalle(id); // 404 antes de empezar a escribir
        cabecerasCsv(respuesta, "rechazos_cargue_" + id + ".csv");
        servicio.rechazosCsv(id, respuesta.getOutputStream(), quien());
    }

    /** CSV de la bitácora de cargues con los mismos filtros de la pantalla. */
    @GetMapping("/api/analitica/cargues/bitacora.csv")
    @Accion(modulo = "analitica", accion = "cargues_bitacora_csv", operacion = Declarada.CONSULTA)
    public void bitacora(@RequestParam(required = false) String fuente, @RequestParam(required = false) String estado,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                         HttpServletResponse respuesta) throws IOException {
        ServicioCargues.Filtros f = new ServicioCargues.Filtros(fuente, estado, desde, hasta, null);
        servicio.validarFiltros(f);
        cabecerasCsv(respuesta, "bitacora_cargues_opl.csv");
        servicio.bitacoraCsv(f, respuesta.getOutputStream(), quien());
    }

    /** El archivo original (datos personales en claro): solo ADMINISTRADOR. */
    @GetMapping("/api/analitica/cargues/{id}/original")
    @PreAuthorize("@permisos.pantalla('ANALITICA_CARGUES') and @permisosAnalitica.original()")
    @Accion(modulo = "analitica", accion = "cargue_original", operacion = Declarada.CONSULTA)
    public ResponseEntity<InputStreamResource> original(@PathVariable long id) {
        ServicioCargues.Descarga d = servicio.original(id, quien());
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.nombre() + "\"")
                .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (d.tamano() >= 0) {
            b.contentLength(d.tamano());
        }
        return b.body(new InputStreamResource(d.contenido()));
    }

    private static void cabecerasCsv(HttpServletResponse r, String nombre) {
        r.setContentType(CSV);
        r.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nombre + "\"");
        r.setHeader("X-Content-Type-Options", "nosniff");
        r.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}
