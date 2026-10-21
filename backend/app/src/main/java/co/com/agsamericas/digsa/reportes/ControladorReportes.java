package co.com.agsamericas.digsa.reportes;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa;
import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.Exportador;
import co.com.agsamericas.digsa.plataforma.PlantillaCorreo;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.plataforma.Reporte;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Fechas;
import java.time.Clock;
import java.time.ZonedDateTime;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reportes de visitas (Anexo 5, prueba 13): en pantalla, descarga en Excel, CSV, PDF o TXT y envío por correo. */
@RestController
@RequestMapping("/api/reportes")
@PreAuthorize("@permisos.pantalla('REPORTES')")
public class ControladorReportes {

    private static final DateTimeFormatter ARCHIVO = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

    record Envio(String para, String formato, String desde, String hasta, Long puntoId, String municipio, String fuerza, String unidad, String estado,
                 String componente) {
    }

    private record Archivo(String nombre, String tipo, byte[] contenido) {
    }

    private final ServicioReportes servicio;
    private final Exportador exportador;
    private final Correo correo;
    private final RegistroBitacora bitacora;
    private final ConfiguracionDigsa cfg;
    private final Clock reloj;

    public ControladorReportes(ServicioReportes servicio, Exportador exportador, Correo correo, RegistroBitacora bitacora, ConfiguracionDigsa cfg,
                               Clock reloj) {
        this.servicio = servicio;
        this.exportador = exportador;
        this.correo = correo;
        this.bitacora = bitacora;
        this.cfg = cfg;
        this.reloj = reloj;
    }

    private static boolean verEquipo() {
        var p = UsuarioSesion.perfilesActuales();
        return p.contains(co.com.agsamericas.digsa.reglas.Perfil.COORDINADOR) || p.contains(co.com.agsamericas.digsa.reglas.Perfil.ADMINISTRADOR);
    }

    private static String usuario() {
        UsuarioSesion u = UsuarioSesion.actual().orElseThrow();
        return u.nombre() + " (" + u.usuario() + ")";
    }

    @GetMapping
    @Accion(modulo = "reportes", accion = "tipos", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> tipos() {
        return servicio.opciones(UsuarioSesion.alcanceActual());
    }

    @GetMapping("/{tipo}")
    @Accion(modulo = "reportes", accion = "ver", operacion = Declarada.CONSULTA)
    public Map<String, Object> ver(@PathVariable String tipo, @RequestParam(required = false) String desde, @RequestParam(required = false) String hasta,
                                   @RequestParam(name = "punto_id", required = false) Long puntoId, @RequestParam(required = false) String municipio,
                                   @RequestParam(required = false) String fuerza, @RequestParam(required = false) String unidad,
                                   @RequestParam(required = false) String estado, @RequestParam(required = false) String componente) {
        Reporte r = servicio.generar(tipo, new ServicioReportes.Filtros(desde, hasta, puntoId, municipio, fuerza, unidad, estado, componente),
                UsuarioSesion.alcanceActual(), usuario(), verEquipo());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("titulo", r.titulo());
        m.put("generado", r.generado().toLocalDateTime().withNano(0).toString().replace('T', ' '));
        m.put("usuario", r.usuario());
        m.put("filtros", r.filtros());
        m.put("columnas", r.columnas());
        m.put("filas", r.filas());
        return m;
    }

    @GetMapping("/{tipo}/exportar")
    @Accion(modulo = "reportes", accion = "exportar", operacion = Declarada.CONSULTA)
    public ResponseEntity<byte[]> exportar(@PathVariable String tipo, @RequestParam(defaultValue = "xlsx") String formato,
                                           @RequestParam(required = false) String desde, @RequestParam(required = false) String hasta,
                                           @RequestParam(name = "punto_id", required = false) Long puntoId, @RequestParam(required = false) String municipio,
                                           @RequestParam(required = false) String fuerza, @RequestParam(required = false) String unidad,
                                           @RequestParam(required = false) String estado, @RequestParam(required = false) String componente) {
        Archivo a = archivo(tipo, formato, new ServicioReportes.Filtros(desde, hasta, puntoId, municipio, fuerza, unidad, estado, componente));
        UsuarioSesion u = UsuarioSesion.actual().orElseThrow();
        bitacora.registrar("reporte_descargado", ServicioReportes.TIPOS.get(tipo) + " descargado (" + a.nombre() + ")", u.id(), u.usuario());
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + a.nombre() + "\"")
                .header(HttpHeaders.CONTENT_TYPE, a.tipo()).body(a.contenido());
    }

    @PostMapping("/{tipo}/enviar")
    @Accion(modulo = "reportes", accion = "enviar", operacion = Declarada.CONSULTA)
    public Map<String, Object> enviar(@PathVariable String tipo, @RequestBody Envio e) {
        String para = Correos.normalizar(e.para() == null ? "" : e.para().strip());
        if (!Correos.valido(para)) {
            throw ErrorNegocio.validacion("Escriba un correo válido para enviar el reporte.");
        }
        List<String> dominios = cfg.dominiosReportes();
        String dominio = para.substring(para.indexOf('@') + 1);
        if (!dominios.isEmpty() && dominios.stream().noneMatch(d -> dominio.equals(d) || dominio.endsWith("." + d))) {
            throw ErrorNegocio.validacion("Los reportes solo se envían a correos de: " + String.join(", ", dominios) + ".");
        }
        Archivo a = archivo(tipo, e.formato() == null ? "xlsx" : e.formato(),
                new ServicioReportes.Filtros(e.desde(), e.hasta(), e.puntoId(), e.municipio(), e.fuerza(), e.unidad(), e.estado(), e.componente()));
        Correo.Resultado r = correo.enviar(new Correo.Mensaje(List.of(para), List.of(), ServicioReportes.TIPOS.get(tipo) + " · Suite DIGSA",
                PlantillaCorreo.marco(ServicioReportes.TIPOS.get(tipo), "<p>Adjunto el reporte generado en la Suite DIGSA.</p><p>Archivo: "
                        + PlantillaCorreo.e(a.nombre()) + "</p>"),
                List.of(new Correo.Adjunto(a.nombre(), a.tipo(), a.contenido()))));
        UsuarioSesion u = UsuarioSesion.actual().orElseThrow();
        bitacora.registrar("reporte_enviado", ServicioReportes.TIPOS.get(tipo) + " enviado a " + Correos.enmascarar(para) + " (" + a.nombre() + ")"
                + (r.ok() ? "" : " · NO salió"), u.id(), u.usuario());
        if (!r.ok()) {
            throw new ErrorNegocio(HttpStatus.BAD_GATEWAY, "No se pudo enviar el correo: " + r.error());
        }
        return Map.of("mensaje", "Reporte enviado a " + para + ".");
    }

    private Archivo archivo(String tipo, String formato, ServicioReportes.Filtros f) {
        Reporte r = servicio.generar(tipo, f, UsuarioSesion.alcanceActual(), usuario(), verEquipo());
        String base = "reporte_" + tipo + "_" + ZonedDateTime.now(reloj.withZone(Fechas.BOGOTA)).format(ARCHIVO);
        return switch (formato == null ? "xlsx" : formato.toLowerCase()) {
            case "csv" -> new Archivo(base + ".csv", Exportador.TIPO_CSV, exportador.csv(r));
            case "pdf" -> new Archivo(base + ".pdf", Exportador.TIPO_PDF, exportador.pdf(r));
            case "txt" -> new Archivo(base + ".txt", Exportador.TIPO_TXT, exportador.txt(r));
            case "xlsx" -> new Archivo(base + ".xlsx", Exportador.TIPO_XLSX, exportador.excel(r));
            default -> throw ErrorNegocio.validacion("El formato debe ser xlsx, csv, pdf o txt.");
        };
    }
}
