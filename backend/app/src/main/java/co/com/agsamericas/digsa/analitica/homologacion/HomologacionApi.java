package co.com.agsamericas.digsa.analitica.homologacion;

import co.com.agsamericas.digsa.identidad.Permisos;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.Exportador;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.plataforma.Reporte;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
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

/**
 * Pantalla «Homologación» (Pantallas.HOMOLOGACION, grupo Información; analitica-homologacion): fuentes oficiales y sus versiones, tabla
 * maestra con su cadena y exportación, re-ejecución, revisión (aprobar o devolver), reglas y catálogos, tablero de calidad, devoluciones
 * al OPL y trazabilidad. Entran ADMINISTRADOR, ANALISTA y COORDINADOR; cargar fuentes, editar reglas, catálogos y listas, y re-ejecutar
 * solo ADMINISTRADOR y ANALISTA ({@code @permisosHomologacion.operar()}).
 */
@RestController
@PreAuthorize("@permisos.pantalla('HOMOLOGACION')")
public class HomologacionApi {

    private static final String OPERA = "@permisos.pantalla('HOMOLOGACION') and @permisosHomologacion.operar()";

    private final ServicioReferencias referencias;
    private final ServicioHomologacion homologacion;
    private final ServicioRevision revision;
    private final ServicioReglas reglas;
    private final ConsultasHomologacion consultas;
    private final PermisosHomologacion permisos;
    private final Exportador exportador;
    private final RegistroBitacora bitacora;
    private final Permisos permisosPantalla;

    HomologacionApi(ServicioReferencias referencias, ServicioHomologacion homologacion, ServicioRevision revision, ServicioReglas reglas,
                    ConsultasHomologacion consultas, PermisosHomologacion permisos, Exportador exportador, RegistroBitacora bitacora,
                    Permisos permisosPantalla) {
        this.permisosPantalla = permisosPantalla;
        this.referencias = referencias;
        this.homologacion = homologacion;
        this.revision = revision;
        this.reglas = reglas;
        this.consultas = consultas;
        this.permisos = permisos;
        this.exportador = exportador;
        this.bitacora = bitacora;
    }

    private static UsuarioSesion quien() {
        return UsuarioSesion.actual().orElseThrow();
    }

    // ---------- Encabezado y versiones ----------

    @GetMapping("/api/homologacion/resumen")
    @Accion(modulo = "homologacion", accion = "resumen", operacion = Declarada.CONSULTA)
    public Map<String, Object> resumen() {
        return consultas.resumen(permisos.operar());
    }

    @GetMapping("/api/homologacion/ejecuciones")
    @Accion(modulo = "homologacion", accion = "ejecuciones", operacion = Declarada.CONSULTA)
    public List<Map<String, Object>> ejecuciones() {
        return consultas.versiones();
    }

    /**
     * «Re-ejecutar homologación»: versión nueva sobre todos los datos originales, diferida (un hilo; el estado y el antes y el después se
     * consultan en GET …/ejecuciones/actual). 202; 409 si ya hay una en curso.
     */
    @PostMapping("/api/homologacion/ejecuciones")
    @PreAuthorize(OPERA)
    @Accion(modulo = "homologacion", accion = "reejecutar", operacion = Declarada.INSERCION)
    public ResponseEntity<Map<String, Object>> reejecutar() {
        UsuarioSesion u = quien();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(estado(homologacion.iniciarReejecucion(u.id(), u.usuario(), Responsable.actual())));
    }

    @GetMapping("/api/homologacion/ejecuciones/actual")
    @Accion(modulo = "homologacion", accion = "reejecucion_estado", operacion = Declarada.CONSULTA)
    public Map<String, Object> reejecucionActual() {
        return estado(homologacion.estadoReejecucion());
    }

    private static Map<String, Object> estado(ServicioHomologacion.EstadoReejecucion s) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("estado", s.estado());
        r.put("porcentaje", s.porcentaje());
        r.put("fase", s.fase());
        r.put("inicio", s.inicio());
        r.put("fin", s.fin());
        r.put("responsable", s.responsable());
        r.put("error", s.error());
        ServicioHomologacion.Ejecucion e = s.resultado();
        Map<String, Object> x = null;
        if (e != null) {
            x = new LinkedHashMap<>();
            x.put("version", e.versionId());
            x.put("numero", e.numero());
            x.put("origenes", e.origenes());
            x.put("antes", ServicioHomologacion.ordenado(e.antes()));
            x.put("despues", ServicioHomologacion.ordenado(e.conteo()));
            x.put("cambios", e.cambios());
            x.put("filas", e.filas());
            x.put("duracionMs", e.duracionMs());
        }
        r.put("resultado", x);
        return r;
    }

    // ---------- Fuentes oficiales ----------

    @GetMapping("/api/homologacion/fuentes")
    @Accion(modulo = "homologacion", accion = "fuentes", operacion = Declarada.CONSULTA)
    public List<Map<String, Object>> fuentes() {
        return referencias.fuentes();
    }

    /** Sube el archivo de una fuente (ya descargado) con la fecha de actualización de la fuente; queda «validada» o «rechazada» (422). */
    @PostMapping(value = "/api/homologacion/fuentes/{codigo}/versiones", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(OPERA)
    @Accion(modulo = "homologacion", accion = "fuente_cargar", operacion = Declarada.INSERCION)
    public ResponseEntity<Map<String, Object>> cargarFuente(@PathVariable String codigo, @RequestPart(name = "archivo", required = false) MultipartFile archivo,
                                                            @RequestParam(required = false) String fechaCorte,
                                                            @RequestParam(defaultValue = "false") boolean ejemplo) throws IOException {
        LocalDate corte = null;
        if (fechaCorte != null && !fechaCorte.isBlank()) {
            try {
                corte = LocalDate.parse(fechaCorte.strip());
            } catch (DateTimeParseException e) {
                throw ErrorNegocio.validacion("Fecha de actualización de la fuente no válida (aaaa-mm-dd).", "fechaCorte");
            }
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(referencias.cargar(codigo, archivo, corte, ejemplo, quien(), Responsable.actual()));
    }

    /** Publica una versión validada (la anterior queda «reemplazada» en la misma transacción). */
    @PostMapping("/api/homologacion/versiones/{id}/publicar")
    @PreAuthorize(OPERA)
    @Accion(modulo = "homologacion", accion = "fuente_publicar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> publicar(@PathVariable int id) {
        return referencias.publicar(id, quien());
    }

    @GetMapping("/api/homologacion/fuentes/{codigo}/lista")
    @Accion(modulo = "homologacion", accion = "lista", operacion = Declarada.CONSULTA)
    public List<Map<String, Object>> lista(@PathVariable String codigo) {
        return referencias.lista(codigo);
    }

    /** Cuerpo de la edición de una lista normativa. */
    public record EdicionLista(List<ServicioReferencias.FilaLista> filas) {
    }

    @PutMapping("/api/homologacion/fuentes/{codigo}/lista")
    @PreAuthorize(OPERA)
    @Accion(modulo = "homologacion", accion = "lista_editar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> editarLista(@PathVariable String codigo, @RequestBody EdicionLista e) {
        return referencias.editarLista(codigo, e == null ? null : e.filas(), quien(), Responsable.actual());
    }

    // ---------- Tabla maestra ----------

    @GetMapping("/api/homologacion/tabla-maestra")
    @Accion(modulo = "homologacion", accion = "tabla_maestra", operacion = Declarada.CONSULTA)
    public Map<String, Object> tablaMaestra(@RequestParam(required = false) Integer version, @RequestParam(required = false) String q,
                                            @RequestParam(required = false) String estado, @RequestParam(required = false) Integer pagina) {
        Map<String, Object> r = consultas.tablaMaestra(new ConsultasHomologacion.Filtro(version, q, estado, false, pagina));
        UsuarioSesion u = quien();
        bitacora.registrar("homologacion_consulta", "Tabla maestra" + versionTexto(r) + (q == null || q.isBlank() ? "" : " · búsqueda")
                + (estado == null || estado.isBlank() ? "" : " · estado " + estado) + " · " + r.get("total") + " registros", u.id(), u.usuario());
        return r;
    }

    @SuppressWarnings("unchecked")
    private static String versionTexto(Map<String, Object> r) {
        Object v = r.get("version");
        return v instanceof Map<?, ?> m ? " v" + ((Map<String, Object>) m).get("numero") : "";
    }

    @GetMapping("/api/homologacion/tabla-maestra/{origenId}")
    @Accion(modulo = "homologacion", accion = "tabla_maestra_detalle", operacion = Declarada.CONSULTA)
    public Map<String, Object> detalle(@PathVariable long origenId, @RequestParam(required = false) Integer version) {
        return consultas.detalle(origenId, version);
    }

    /** Tabla maestra (versión elegida o la búsqueda filtrada) en CSV («;», UTF-8 con BOM, coma decimal) o Excel. */
    @GetMapping("/api/homologacion/tabla-maestra/exportar")
    @Accion(modulo = "homologacion", accion = "tabla_maestra_exportar", operacion = Declarada.CONSULTA)
    public ResponseEntity<byte[]> exportarTablaMaestra(@RequestParam(required = false) Integer version, @RequestParam(required = false) String q,
                                                       @RequestParam(required = false) String estado, @RequestParam(defaultValue = "csv") String formato) {
        UsuarioSesion u = quien();
        Reporte r = consultas.reporteTablaMaestra(new ConsultasHomologacion.Filtro(version, q, estado, false, null), u.usuario());
        bitacora.registrar("homologacion_exportada", r.titulo() + " · " + formato(formato) + " · " + r.filas().size() + " filas", u.id(), u.usuario());
        return archivo(r, formato, "tabla_maestra_homologacion");
    }

    // ---------- Revisión ----------

    @GetMapping("/api/homologacion/revision")
    @Accion(modulo = "homologacion", accion = "revision", operacion = Declarada.CONSULTA)
    public Map<String, Object> revisionLista(@RequestParam(required = false) String q, @RequestParam(required = false) String estado,
                                             @RequestParam(required = false) Integer pagina) {
        return consultas.tablaMaestra(new ConsultasHomologacion.Filtro(null, q, estado, true, pagina));
    }

    @GetMapping("/api/homologacion/revision/{origenId}")
    @Accion(modulo = "homologacion", accion = "revision_comparar", operacion = Declarada.CONSULTA)
    public Map<String, Object> comparacion(@PathVariable long origenId) {
        return revision.comparacion(origenId);
    }

    /**
     * Cuerpo de «Aprobar homologación»: CUM elegido (opcional; sin él, el dato del INVIMA de la fila; obligatorio si el registro es «CUM o
     * registro no válido») y versión de la tabla maestra con que se abrió la comparación (409 si cambió).
     */
    public record Aprobacion(String cum, Integer version) {
    }

    @PostMapping("/api/homologacion/revision/{origenId}/aprobar")
    @Accion(modulo = "homologacion", accion = "revision_aprobar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> aprobar(@PathVariable long origenId, @RequestBody(required = false) Aprobacion a) {
        return revision.aprobar(origenId, a == null ? null : a.cum(), a == null ? null : a.version(), quien(), Responsable.actual());
    }

    /** Cuerpo de «Devolver al OPL»: motivo y versión revisada (409 si cambió). */
    public record Devolucion(String motivo, Integer version) {
    }

    @PostMapping("/api/homologacion/revision/{origenId}/devolver")
    @Accion(modulo = "homologacion", accion = "revision_devolver", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> devolver(@PathVariable long origenId, @RequestBody(required = false) Devolucion d) {
        return revision.devolver(origenId, d == null ? null : d.motivo(), d == null ? null : d.version(), quien(), Responsable.actual());
    }

    /** Sugerencia de la IA (si hay proveedor): solo se muestra; la decisión es humana. */
    @PostMapping("/api/homologacion/revision/{origenId}/sugerencia")
    @Accion(modulo = "homologacion", accion = "revision_sugerencia", operacion = Declarada.CONSULTA)
    public Map<String, Object> sugerencia(@PathVariable long origenId) {
        return revision.sugerencia(origenId);
    }

    // ---------- Reglas y catálogos ----------

    @GetMapping("/api/homologacion/reglas")
    @Accion(modulo = "homologacion", accion = "reglas", operacion = Declarada.CONSULTA)
    public Map<String, Object> reglas() {
        return reglas.reglas();
    }

    @PutMapping("/api/homologacion/reglas/{id}")
    @PreAuthorize(OPERA)
    @Accion(modulo = "homologacion", accion = "regla_modificar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> actualizarRegla(@PathVariable String id, @RequestBody(required = false) ServicioReglas.Cambio c) {
        return reglas.actualizar(id, c, quien(), Responsable.actual());
    }

    @GetMapping("/api/homologacion/catalogos/{tipo}")
    @Accion(modulo = "homologacion", accion = "catalogo", operacion = Declarada.CONSULTA)
    public Map<String, Object> catalogo(@PathVariable String tipo) {
        return reglas.catalogo(tipo);
    }

    /** Cuerpo de la edición de un catálogo: entradas y versión del catálogo que se editó (409 si otra persona lo cambió entretanto). */
    public record EdicionCatalogo(List<ServicioReglas.Entrada> entradas, Integer version) {
    }

    @PutMapping("/api/homologacion/catalogos/{tipo}")
    @PreAuthorize(OPERA)
    @Accion(modulo = "homologacion", accion = "catalogo_editar", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> editarCatalogo(@PathVariable String tipo, @RequestBody(required = false) EdicionCatalogo e) {
        return reglas.editarCatalogo(tipo, e == null ? null : e.entradas(), e == null ? null : e.version(), quien(), Responsable.actual());
    }

    // ---------- Calidad, devoluciones y trazabilidad ----------

    private static ConsultasHomologacion.FiltroCalidad filtroCalidad(Integer version, Long cargue, String fuente, String desde, String hasta) {
        return new ConsultasHomologacion.FiltroCalidad(version, cargue, fuente == null || fuente.isBlank() ? null : fuente, mes(desde), mes(hasta));
    }

    private static YearMonth mes(String t) {
        if (t == null || t.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(t.strip());
        } catch (DateTimeParseException e) {
            throw ErrorNegocio.validacion("Periodo no válido (aaaa-mm).");
        }
    }

    @GetMapping("/api/homologacion/calidad")
    @Accion(modulo = "homologacion", accion = "calidad", operacion = Declarada.CONSULTA)
    public Map<String, Object> calidad(@RequestParam(required = false) Integer version, @RequestParam(required = false) Long cargue,
                                       @RequestParam(required = false) String fuente, @RequestParam(required = false) String desde,
                                       @RequestParam(required = false) String hasta) {
        Map<String, Object> r = consultas.calidad(filtroCalidad(version, cargue, fuente, desde, hasta));
        UsuarioSesion u = quien();
        bitacora.registrar("homologacion_consulta", "Tablero de calidad" + versionTexto(r) + (cargue == null ? "" : " · cargue " + cargue) + " · "
                + r.get("registros") + " registros", u.id(), u.usuario());
        return r;
    }

    @GetMapping("/api/homologacion/calidad/exportar")
    @Accion(modulo = "homologacion", accion = "calidad_exportar", operacion = Declarada.CONSULTA)
    public ResponseEntity<byte[]> exportarCalidad(@RequestParam(required = false) Integer version, @RequestParam(required = false) Long cargue,
                                                  @RequestParam(required = false) String fuente, @RequestParam(required = false) String desde,
                                                  @RequestParam(required = false) String hasta, @RequestParam(defaultValue = "csv") String formato) {
        UsuarioSesion u = quien();
        Reporte r = consultas.reporteCalidad(filtroCalidad(version, cargue, fuente, desde, hasta), u.usuario());
        bitacora.registrar("homologacion_exportada", r.titulo() + " · " + formato(formato), u.id(), u.usuario());
        return archivo(r, formato, "calidad_homologacion");
    }

    @GetMapping("/api/homologacion/devoluciones")
    @Accion(modulo = "homologacion", accion = "devoluciones", operacion = Declarada.CONSULTA)
    public Map<String, Object> devoluciones() {
        return consultas.devoluciones();
    }

    @GetMapping("/api/homologacion/devoluciones/exportar")
    @Accion(modulo = "homologacion", accion = "devoluciones_exportar", operacion = Declarada.CONSULTA)
    public ResponseEntity<byte[]> exportarDevoluciones(@RequestParam(defaultValue = "csv") String formato) {
        UsuarioSesion u = quien();
        Reporte r = consultas.reporteDevoluciones(u.usuario());
        bitacora.registrar("homologacion_exportada", r.titulo() + " · " + formato(formato) + " · " + r.filas().size() + " filas", u.id(), u.usuario());
        return archivo(r, formato, "devoluciones_opl");
    }

    @GetMapping("/api/homologacion/trazabilidad")
    @Accion(modulo = "homologacion", accion = "trazabilidad", operacion = Declarada.CONSULTA)
    public List<Map<String, Object>> trazabilidad(@RequestParam(defaultValue = "50") int limite) {
        // Los eventos de los cargues del OPL solo para quien también tiene la pantalla de cargues (Ley 1581 art. 4 lit. f)
        return consultas.trazabilidad(limite, permisosPantalla.pantalla("ANALITICA_CARGUES"));
    }

    // ---------- Apoyo ----------

    private static String formato(String f) {
        if (!"csv".equalsIgnoreCase(f) && !"xlsx".equalsIgnoreCase(f)) {
            throw ErrorNegocio.validacion("Formato no válido: csv o xlsx.");
        }
        return f.toLowerCase(java.util.Locale.ROOT);
    }

    private ResponseEntity<byte[]> archivo(Reporte r, String formato, String base) {
        boolean csv = "csv".equals(formato(formato));
        byte[] cuerpo = csv ? exportador.csv(r) : exportador.excel(r);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, csv ? Exportador.TIPO_CSV : Exportador.TIPO_XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + base + (csv ? ".csv" : ".xlsx") + "\"")
                .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "no-store").body(cuerpo);
    }
}
