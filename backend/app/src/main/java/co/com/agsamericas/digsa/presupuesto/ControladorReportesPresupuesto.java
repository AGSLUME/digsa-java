package co.com.agsamericas.digsa.presupuesto;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.CatalogoPlantillas;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa;
import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.plataforma.EnvioRegistrado;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.Plantilla;
import co.com.agsamericas.digsa.plataforma.Exportador;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.plataforma.Reporte;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Presupuesto;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reportes de ejecución presupuestal: ver, descargar (xlsx, csv, pdf, txt) y enviar por correo (Anexo 5, 2.7 a 2.12), en el alcance de
 * cada perfil con la pantalla de presupuesto. Cada descarga y envío queda en la bitácora.
 */
@RestController
public class ControladorReportesPresupuesto {

    static final int MAX_VISTA = 2000;
    private static final DateTimeFormatter ARCHIVO = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");
    private static final Map<String, String> TIPOS_ARCHIVO = Map.of("xlsx", Exportador.TIPO_XLSX, "csv", Exportador.TIPO_CSV, "pdf", Exportador.TIPO_PDF, "txt",
            Exportador.TIPO_TXT);

    record Envio(String para, String formato, Long vigencia, String corte, String fuerza, Long punto, String modalidad, String tipoMovimiento, String desde,
                 String hasta, String agrupacion) {
    }

    private record Archivo(String nombre, String tipo, byte[] contenido) {
    }

    private final ServicioReportesPresupuesto servicio;
    private final CatalogoPlantillas plantillas;
    private final Exportador exportador;
    private final EnvioRegistrado correo;
    private final RegistroBitacora bitacora;
    private final ConfiguracionDigsa cfg;

    ControladorReportesPresupuesto(ServicioReportesPresupuesto servicio, Exportador exportador, EnvioRegistrado correo, RegistroBitacora bitacora, ConfiguracionDigsa cfg, CatalogoPlantillas plantillas) {
        this.plantillas = plantillas;
        this.servicio = servicio;
        this.exportador = exportador;
        this.correo = correo;
        this.bitacora = bitacora;
        this.cfg = cfg;
    }

    private static String usuario() {
        UsuarioSesion u = UsuarioSesion.actual().orElseThrow();
        return u.nombre() + " (" + u.usuario() + ")";
    }

    @GetMapping("/api/presupuesto/reportes")
    @PreAuthorize(ControladorPresupuesto.CONSULTAN)
    @Accion(modulo = "presupuesto", accion = "reportes_tipos", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> tipos() {
        return Map.of("tipos", ServicioReportesPresupuesto.TIPOS, "formatos", List.of("xlsx", "csv", "pdf", "txt"), "agrupaciones",
                ServicioReportesPresupuesto.AGRUPACIONES);
    }

    @GetMapping("/api/presupuesto/reportes/{tipo}")
    @PreAuthorize(ControladorPresupuesto.CONSULTAN)
    @Accion(modulo = "presupuesto", accion = "reporte_ver", operacion = Declarada.CONSULTA)
    public Map<String, Object> ver(@PathVariable String tipo, @RequestParam(required = false) Long vigencia, @RequestParam(required = false) String corte,
                                   @RequestParam(required = false) String fuerza, @RequestParam(required = false) Long punto,
                                   @RequestParam(required = false) String modalidad, @RequestParam(required = false) String tipoMovimiento,
                                   @RequestParam(required = false) String desde, @RequestParam(required = false) String hasta,
                                   @RequestParam(required = false) String agrupacion) {
        Reporte r = servicio.generar(tipo, new ServicioReportesPresupuesto.Filtros(vigencia, corte, fuerza, punto, modalidad, tipoMovimiento, desde, hasta,
                agrupacion), ControladorPresupuesto.ambito(), usuario());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("titulo", r.titulo());
        m.put("generado", r.generado().toLocalDateTime().withNano(0).toString().replace('T', ' '));
        m.put("usuario", r.usuario());
        m.put("filtros", r.filtros());
        m.put("columnas", r.columnas());
        m.put("total", r.filas().size());
        // En pantalla, hasta MAX_VISTA filas; la descarga trae todas
        m.put("filas", vista(r.filas()).stream().map(f -> f.stream().map(ControladorReportesPresupuesto::celda).toList()).toList());
        return m;
    }

    @GetMapping("/api/presupuesto/reportes/{tipo}/exportar")
    @PreAuthorize(ControladorPresupuesto.CONSULTAN)
    @Accion(modulo = "presupuesto", accion = "reporte_exportar", operacion = Declarada.CONSULTA)
    public ResponseEntity<byte[]> exportar(@PathVariable String tipo, @RequestParam(defaultValue = "xlsx") String formato,
                                           @RequestParam(required = false) Long vigencia, @RequestParam(required = false) String corte,
                                           @RequestParam(required = false) String fuerza, @RequestParam(required = false) Long punto,
                                           @RequestParam(required = false) String modalidad, @RequestParam(required = false) String tipoMovimiento,
                                           @RequestParam(required = false) String desde, @RequestParam(required = false) String hasta,
                                           @RequestParam(required = false) String agrupacion) {
        Archivo a = archivo(tipo, formato, new ServicioReportesPresupuesto.Filtros(vigencia, corte, fuerza, punto, modalidad, tipoMovimiento, desde, hasta,
                agrupacion));
        UsuarioSesion u = UsuarioSesion.actual().orElseThrow();
        bitacora.registrar("reporte_descargado", ServicioReportesPresupuesto.TIPOS.get(tipo) + " descargado (" + a.nombre() + ")", u.id(), u.usuario());
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + a.nombre() + "\"")
                .header(HttpHeaders.CONTENT_TYPE, a.tipo()).header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(a.contenido());
    }

    @PostMapping("/api/presupuesto/reportes/{tipo}/enviar")
    @PreAuthorize(ControladorPresupuesto.CONSULTAN)
    @Accion(modulo = "presupuesto", accion = "reporte_enviar", operacion = Declarada.CONSULTA)
    public Map<String, Object> enviar(@PathVariable String tipo, @RequestBody Envio e) {
        String para = Correos.normalizar(e == null || e.para() == null ? "" : e.para().strip());
        if (!Correos.valido(para)) {
            throw ErrorNegocio.validacion("Escriba un correo válido para enviar el reporte.");
        }
        List<String> dominios = cfg.dominiosReportes();
        String dominio = para.substring(para.indexOf('@') + 1);
        if (!dominios.isEmpty() && dominios.stream().noneMatch(d -> dominio.equals(d) || dominio.endsWith("." + d))) {
            throw ErrorNegocio.validacion("Los reportes solo se envían a correos de: " + String.join(", ", dominios) + ".");
        }
        Archivo a = archivo(tipo, e.formato() == null ? "xlsx" : e.formato(), new ServicioReportesPresupuesto.Filtros(e.vigencia(), e.corte(), e.fuerza(),
                e.punto(), e.modalidad(), e.tipoMovimiento(), e.desde(), e.hasta(), e.agrupacion()));
        String titulo = ServicioReportesPresupuesto.TIPOS.get(tipo);
        Plantilla.Renderizada p = plantillas.render(PlantillasPresupuesto.REPORTE, Map.of("titulo", titulo, "archivo", a.nombre()));
        Correo.Resultado r = correo.enviar(EnvioRegistrado.Registro.de("reporte_presupuesto", "reporte:" + tipo).reenvio("Se reenvía desde los reportes de presupuesto con «Enviar por correo»."), new Correo.Mensaje(List.of(para), List.of(), p.asunto(), p.html(),
                List.of(new Correo.Adjunto(a.nombre(), a.tipo(), a.contenido()))));
        UsuarioSesion u = UsuarioSesion.actual().orElseThrow();
        bitacora.registrar("reporte_enviado", titulo + " enviado a " + Correos.enmascarar(para) + " (" + a.nombre() + ")" + (r.ok() ? "" : " · NO salió"), u.id(),
                u.usuario());
        if (!r.ok()) {
            throw new ErrorNegocio(HttpStatus.BAD_GATEWAY, "No se pudo enviar el correo: " + r.error());
        }
        return Map.of("mensaje", "Reporte enviado a " + para + ".");
    }

    private Archivo archivo(String tipo, String formato, ServicioReportesPresupuesto.Filtros f) {
        String fmt = formato == null ? "" : formato.strip().toLowerCase(java.util.Locale.ROOT);
        String tipoArchivo = TIPOS_ARCHIVO.get(fmt);
        if (tipoArchivo == null) {
            throw ErrorNegocio.validacion("El formato debe ser xlsx, csv, pdf o txt.");
        }
        Reporte r = servicio.generar(tipo, f, ControladorPresupuesto.ambito(), usuario());
        byte[] contenido = switch (fmt) {
            case "csv" -> exportador.csv(r);
            case "pdf" -> exportador.pdf(r);
            case "txt" -> exportador.txt(r);
            default -> exportador.excel(r);
        };
        return new Archivo("presupuesto_" + tipo + "_" + ARCHIVO.format(r.generado()) + "." + fmt, tipoArchivo, contenido);
    }

    /** Hasta MAX_VISTA filas en pantalla, conservando siempre las de totales («Total …», al final del reporte de ejecución). */
    static List<List<Object>> vista(List<List<Object>> filas) {
        if (filas.size() <= MAX_VISTA) {
            return filas;
        }
        List<List<Object>> totales = filas.stream().filter(f -> f.size() > 1 && f.get(1) instanceof String s && s.startsWith("Total ")).toList();
        List<List<Object>> r = new java.util.ArrayList<>(filas.stream().filter(f -> !totales.contains(f)).limit(MAX_VISTA - totales.size()).toList());
        r.addAll(totales);
        return r;
    }

    /** Celda para la pantalla: montos con 2 decimales y coma, sin miles (como en la descarga). */
    static Object celda(Object v) {
        return switch (v) {
            case BigDecimal b -> Presupuesto.formato(b);
            case java.time.OffsetDateTime o -> Lineas.fechaHora(o);
            case null -> "";
            default -> v;
        };
    }
}
