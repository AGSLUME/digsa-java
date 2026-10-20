package co.com.agsamericas.digsa.auditoria;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa;
import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.Exportador;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.plataforma.Reporte;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Fechas;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Logs del sistema (Anexo 5, prueba 15): en pantalla, en el navegador, con usuario, fecha, operación y detalle; descarga en Excel o
 * CSV y envío por correo desde la misma aplicación (condición 12: no se permite saltar a otra herramienta; condición 17: reportes en
 * Excel enviados por correo). Solo el Administrador.
 */
@RestController
@RequestMapping("/api/logs")
@PreAuthorize("@permisos.pantalla('LOGS')")
public class ControladorLogs {

    static final int POR_PAGINA = 100;
    static final int MAX_DIAS_TEXTO = 92;
    static final int MAX_EXPORTAR = 200_000;
    private static final DateTimeFormatter ARCHIVO = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

    record Envio(String desde, String hasta, Long usuarioId, String operacion, String texto, String formato, String para) {
    }

    private final RepositorioLogs logs;
    private final Exportador exportador;
    private final Correo correo;
    private final RegistroBitacora bitacora;
    private final ConfiguracionDigsa cfg;
    private final Clock reloj;

    public ControladorLogs(RepositorioLogs logs, Exportador exportador, Correo correo, RegistroBitacora bitacora, ConfiguracionDigsa cfg, Clock reloj) {
        this.logs = logs;
        this.exportador = exportador;
        this.correo = correo;
        this.bitacora = bitacora;
        this.cfg = cfg;
        this.reloj = reloj;
    }

    @GetMapping("/opciones")
    @Accion(modulo = "logs", accion = "opciones", operacion = Declarada.CONSULTA)
    public Map<String, Object> opciones() {
        return Map.of("operaciones", Arrays.stream(Bitacora.Operacion.values()).map(Bitacora.Operacion::valor).toList(),
                "usuarios", logs.usuarios(), "correoDefecto", cfg.reportes() == null || cfg.reportes().correoDefecto() == null ? ""
                        : cfg.reportes().correoDefecto());
    }

    @GetMapping
    @Accion(modulo = "logs", accion = "lista", operacion = Declarada.CONSULTA)
    public Map<String, Object> lista(@RequestParam(required = false) String desde, @RequestParam(required = false) String hasta,
                                     @RequestParam(required = false) Long usuarioId, @RequestParam(required = false) String operacion,
                                     @RequestParam(required = false) String texto, @RequestParam(required = false) String despuesFecha,
                                     @RequestParam(required = false) Long despuesId) {
        RepositorioLogs.Filtro f = filtro(desde, hasta, usuarioId, operacion, texto);
        OffsetDateTime df = null;
        if (despuesFecha != null && !despuesFecha.isBlank()) {
            try {
                df = OffsetDateTime.parse(despuesFecha);
            } catch (java.time.format.DateTimeParseException e) {
                throw ErrorNegocio.validacion("La página pedida no es válida.");
            }
        }
        List<RepositorioLogs.Fila> filas = logs.pagina(f, df, despuesId, POR_PAGINA + 1);
        boolean hayMas = filas.size() > POR_PAGINA;
        List<RepositorioLogs.Fila> visibles = hayMas ? filas.subList(0, POR_PAGINA) : filas;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("filas", visibles.stream().map(ControladorLogs::fila).toList());
        m.put("siguiente", hayMas ? Map.of("fecha", visibles.getLast().fecha().toString(), "id", visibles.getLast().id()) : null);
        m.put("desde", f.desde().toString());
        m.put("hasta", f.hasta().toString());
        return m;
    }

    @GetMapping("/exportar")
    @Accion(modulo = "logs", accion = "exportar", operacion = Declarada.CONSULTA)
    public ResponseEntity<byte[]> exportar(@RequestParam(required = false) String desde, @RequestParam(required = false) String hasta,
                                           @RequestParam(required = false) Long usuarioId, @RequestParam(required = false) String operacion,
                                           @RequestParam(required = false) String texto, @RequestParam(defaultValue = "xlsx") String formato) {
        Archivo a = archivo(filtro(desde, hasta, usuarioId, operacion, texto), formato);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + a.nombre() + "\"")
                .header(HttpHeaders.CONTENT_TYPE, a.tipo())
                .body(a.contenido());
    }

    @PostMapping("/enviar")
    @Accion(modulo = "logs", accion = "enviar", operacion = Declarada.CONSULTA)
    public Map<String, Object> enviar(@RequestBody Envio e) {
        String para = Correos.normalizar(e.para());
        if (!Correos.valido(para)) {
            throw ErrorNegocio.validacion("Escriba un correo válido para enviar el reporte.");
        }
        List<String> dominios = cfg.dominiosReportes();
        String dominio = para.substring(para.indexOf('@') + 1);
        if (!dominios.isEmpty() && dominios.stream().noneMatch(d -> dominio.equals(d) || dominio.endsWith("." + d))) {
            throw ErrorNegocio.validacion("Los reportes solo se envían a correos de: " + String.join(", ", dominios) + ".");
        }
        Archivo a = archivo(filtro(e.desde(), e.hasta(), e.usuarioId(), e.operacion(), e.texto()), e.formato() == null ? "xlsx" : e.formato());
        Correo.Resultado r = correo.enviar(new Correo.Mensaje(List.of(para), List.of(), "Reporte de logs de auditoría · Suite DIGSA",
                "<p>Adjunto el reporte de logs de auditoría generado en la Suite DIGSA.</p><p>Archivo: "
                        + org.springframework.web.util.HtmlUtils.htmlEscape(a.nombre()) + "</p>",
                List.of(new Correo.Adjunto(a.nombre(), a.tipo(), a.contenido()))));
        UsuarioSesion u = UsuarioSesion.actual().orElseThrow();
        bitacora.registrar("reporte_enviado", "Logs de auditoría enviados a " + Correos.enmascarar(para) + " (" + a.nombre() + ")"
                + (r.ok() ? "" : " · NO salió"), u.id(), u.usuario());
        if (!r.ok()) {
            throw new ErrorNegocio(org.springframework.http.HttpStatus.BAD_GATEWAY, "No se pudo enviar el correo: " + r.error());
        }
        return Map.of("mensaje", "Reporte enviado a " + para + ".");
    }

    private record Archivo(String nombre, String tipo, byte[] contenido) {
    }

    private Archivo archivo(RepositorioLogs.Filtro f, String formato) {
        UsuarioSesion u = UsuarioSesion.actual().orElseThrow();
        List<List<Object>> filas = new ArrayList<>();
        boolean truncada = logs.recorrer(f, MAX_EXPORTAR,
                x -> filas.add(List.of(x.fecha(), texto(x.usuario()), x.operacion(), x.tipo(), texto(x.descripcion()), texto(x.ip()))));
        if (truncada) {
            // Un reporte incompleto no se entrega como si fuera completo (respuesta TAS P12: reportes completos, sin truncar)
            throw ErrorNegocio.validacion("El periodo tiene más de " + MAX_EXPORTAR + " registros. Acótelo (fechas, usuario u operación) y exporte de nuevo.");
        }
        Map<String, String> filtros = new LinkedHashMap<>();
        filtros.put("Periodo", f.desde() + " a " + f.hasta());
        if (f.usuarioId() != null) {
            filtros.put("Usuario (id)", String.valueOf(f.usuarioId()));
        }
        if (f.operacion() != null) {
            filtros.put("Operación", f.operacion());
        }
        if (f.texto() != null && !f.texto().isBlank()) {
            filtros.put("Texto", f.texto());
        }
        Reporte r = new Reporte("Logs de auditoría", ZonedDateTime.now(reloj), u.nombre() + " (" + u.usuario() + ")", filtros,
                List.of("Fecha y hora", "Usuario", "Operación", "Tipo", "Detalle", "IP"), filas);
        String base = "logs_auditoria_" + ZonedDateTime.now(reloj).format(ARCHIVO);
        return "csv".equalsIgnoreCase(formato) ? new Archivo(base + ".csv", Exportador.TIPO_CSV, exportador.csv(r))
                : new Archivo(base + ".xlsx", Exportador.TIPO_XLSX, exportador.excel(r));
    }

    /** Siempre por rango de fechas (por defecto los últimos 7 días); fechas invertidas se intercambian; texto solo con ≤ 92 días. */
    private RepositorioLogs.Filtro filtro(String desde, String hasta, Long usuarioId, String operacion, String texto) {
        LocalDate hoy = LocalDate.now(reloj);
        LocalDate d = Fechas.valida(desde).orElse(hoy.minusDays(7));
        LocalDate h = Fechas.valida(hasta).orElse(hoy);
        if (d.isAfter(h)) {
            LocalDate t = d;
            d = h;
            h = t;
        }
        String op = Arrays.stream(Bitacora.Operacion.values()).map(Bitacora.Operacion::valor).filter(v -> v.equals(operacion)).findFirst().orElse(null);
        String txt = texto == null || texto.isBlank() ? null : Bitacora.cortar(texto.strip(), 80);
        if (txt != null && ChronoUnit.DAYS.between(d, h) > MAX_DIAS_TEXTO) {
            throw ErrorNegocio.validacion("Para buscar por texto, use un periodo de máximo " + MAX_DIAS_TEXTO + " días.");
        }
        return new RepositorioLogs.Filtro(d, h, usuarioId, op, txt);
    }

    private static Map<String, Object> fila(RepositorioLogs.Fila x) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", x.id());
        m.put("fecha", x.fecha().atZoneSameInstant(Fechas.BOGOTA).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        m.put("fechaLlave", x.fecha().toString());
        m.put("usuario", x.usuario());
        m.put("operacion", x.operacion());
        m.put("tipo", x.tipo());
        m.put("modulo", x.modulo());
        m.put("descripcion", x.descripcion());
        m.put("ip", x.ip());
        return m;
    }

    private static String texto(String s) {
        return s == null ? "" : s;
    }
}
