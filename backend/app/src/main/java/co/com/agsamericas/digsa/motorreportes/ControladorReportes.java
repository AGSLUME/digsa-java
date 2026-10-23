package co.com.agsamericas.digsa.motorreportes;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa;
import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.PlantillaCorreo;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reglas.Periodos.Agrupacion;
import co.com.agsamericas.digsa.reportes.DefinicionReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.Columna;
import co.com.agsamericas.digsa.reportes.GrupoReporte;
import co.com.agsamericas.digsa.reportes.Opcion;
import co.com.agsamericas.digsa.reportes.ProveedorReporte;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Módulo de reportes (Anexo 5, 2.7 a 2.12): catálogo por perfil, opciones de los filtros dentro del alcance, presets del periodo, el
 * reporte en pantalla, la descarga en Excel, CSV, PDF o TXT y el envío por correo. Cada generación lleva un código de verificación y
 * la huella de los datos (2.10). Las rutas de los reportes de visitas de antes ({@code /api/reportes/{tipo}…}) siguen respondiendo: el
 * id del reporte es el tipo de antes.
 */
@RestController
@RequestMapping("/api/reportes")
@PreAuthorize("@permisosReportes.alguno()")
public class ControladorReportes {

    private static final Logger LOG = LoggerFactory.getLogger(ControladorReportes.class);
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static final List<String> FORMATOS = List.of("xlsx", "csv", "pdf", "txt");
    /** Nombres sueltos de la pantalla de antes ⇒ filtro del reporte. */
    private static final Map<String, String> ALIAS = Map.of("puntoId", "punto_id");
    private static final Set<String> FUERA_DE_FILTROS = Set.of("para", "formato", "filtros");
    /** Opciones por filtro que van con su código a la bitácora; con más, solo cuántas (la lista completa queda en el registro). */
    static final int MAX_EN_BITACORA = 20;

    private final MotorReportes motor;
    private final ServicioGeneracion generacion;
    private final Almacen almacen;
    private final Correo correo;
    private final RegistroBitacora bitacora;
    private final ConfiguracionDigsa cfg;
    private final ConfiguracionReportes cfgReportes;

    public ControladorReportes(MotorReportes motor, ServicioGeneracion generacion, Almacen almacen, Correo correo, RegistroBitacora bitacora,
                               ConfiguracionDigsa cfg, ConfiguracionReportes cfgReportes) {
        this.cfgReportes = cfgReportes;
        this.motor = motor;
        this.generacion = generacion;
        this.almacen = almacen;
        this.correo = correo;
        this.bitacora = bitacora;
        this.cfg = cfg;
    }

    static ServicioGeneracion.Quien quien() {
        UsuarioSesion u = UsuarioSesion.actual().orElseThrow();
        return new ServicioGeneracion.Quien(u.id(), u.nombre() + " (" + u.usuario() + ")");
    }

    /** Compatibilidad con la pantalla de antes: los reportes del catálogo por id y título, y los formatos. */
    @GetMapping
    @Accion(modulo = "reportes", accion = "tipos", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> tipos() {
        Map<String, String> tipos = new LinkedHashMap<>();
        motor.catalogo(UsuarioSesion.perfilesActuales(), UsuarioSesion.alcanceActual()).forEach(p -> tipos.put(p.definicion().id(), p.definicion().titulo()));
        return Map.of("tipos", tipos, "formatos", FORMATOS);
    }

    /** Catálogo (mockup): los grupos con los reportes que el perfil puede generar; un grupo sin reportes no aparece. */
    @GetMapping("/catalogo")
    @Accion(modulo = "reportes", accion = "catalogo", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> catalogo() {
        Set<Perfil> perfiles = UsuarioSesion.perfilesActuales();
        Map<GrupoReporte, List<Map<String, Object>>> grupos = new LinkedHashMap<>();
        for (ProveedorReporte p : motor.catalogo(perfiles, UsuarioSesion.alcanceActual())) {
            grupos.computeIfAbsent(p.definicion().grupo(), g -> new ArrayList<>()).add(definicion(p.definicion(), perfiles));
        }
        List<Map<String, Object>> lista = grupos.entrySet().stream().map(e -> Map.<String, Object>of("clave", e.getKey().name().toLowerCase(Locale.ROOT),
                "nombre", e.getKey().nombre(), "reportes", e.getValue())).toList();
        List<Map<String, Object>> presets = motor.presets().stream().map(x -> Map.<String, Object>of("clave", x.clave(), "nombre", x.nombre(), "desde",
                x.desde().toString(), "hasta", x.hasta().toString())).toList();
        return Map.of("grupos", lista, "formatos", FORMATOS, "presets", presets);
    }

    private static Map<String, Object> definicion(DefinicionReporte d, Set<Perfil> perfiles) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id());
        m.put("titulo", d.titulo());
        m.put("descripcion", d.descripcion());
        m.put("clase", d.clase().name().toLowerCase(Locale.ROOT));
        m.put("modulo", d.modulo());
        m.put("periodo", d.periodo().name().toLowerCase(Locale.ROOT));
        m.put("maxDias", d.maxDias());
        m.put("agrupaciones", d.agrupaciones().stream().sorted().map(a -> Map.of("clave", a.clave(), "nombre", a.nombre())).toList());
        m.put("filtros", d.filtros().stream().map(f -> Map.of("clave", f.clave(), "etiqueta", f.etiqueta(), "tipo", f.tipo().name().toLowerCase(Locale.ROOT),
                "establecimiento", f.establecimiento())).toList());
        m.put("columnas", d.columnasPara(perfiles).stream().map(Columna::nombre).toList());
        return m;
    }

    /** Opciones de los filtros de lista del reporte, recortadas al alcance del perfil. */
    @GetMapping("/{id}/opciones")
    @Accion(modulo = "reportes", accion = "opciones", operacion = Declarada.SIN_REGISTRO)
    public Map<String, List<Opcion>> opciones(@PathVariable String id) {
        Alcance alcance = UsuarioSesion.alcanceActual();
        return motor.opciones(motor.proveedor(id, UsuarioSesion.perfilesActuales(), alcance), alcance);
    }

    @GetMapping("/{id}")
    @Accion(modulo = "reportes", accion = "ver", operacion = Declarada.CONSULTA)
    public Map<String, Object> ver(@PathVariable String id, @RequestParam MultiValueMap<String, String> parametros) {
        ServicioGeneracion.Quien q = quien();
        ServicioGeneracion.Vista v = generacion.ver(resolver(id, parametros), q);
        MotorReportes.Resultado r = v.resultado();
        bitacora.registrar("reporte_generado", r.solicitud().definicion().titulo() + " generado en pantalla (" + v.codigo() + ") · "
                + resumen(r.solicitud(), r.filas().size() + (r.truncado() ? "+" : ""), r.ms()), q.id(), UsuarioSesion.actual().orElseThrow().usuario());
        DefinicionReporte d = r.solicitud().definicion();
        Agrupacion ag = r.solicitud().consulta().agrupacion();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id());
        m.put("titulo", d.titulo());
        m.put("codigo", v.codigo());
        m.put("huella", v.huella());
        m.put("generado", r.generado().format(FECHA_HORA));
        m.put("usuario", r.usuario());
        m.put("periodo", r.solicitud().periodo());
        m.put("agrupacion", ag == null ? null : ag.nombre().toLowerCase(Locale.ROOT));
        m.put("establecimiento", r.solicitud().establecimiento());
        m.put("filtros", r.solicitud().filtrosEncabezado());
        m.put("fuente", d.fuente());
        m.put("clase", d.clase().name().toLowerCase(Locale.ROOT));
        m.put("registros", r.filas().size());
        m.put("truncado", r.truncado());
        m.put("ms", r.ms());
        m.put("columnas", r.columnas());
        m.put("filas", r.filas().stream().map(f -> f.stream().map(ControladorReportes::valorPantalla).toList()).toList());
        return m;
    }

    /** Fechas y horas en hora de Bogotá (24 h), como en los archivos; los números van como número y la pantalla los formatea. */
    static Object valorPantalla(Object v) {
        return switch (v) {
            case null -> null;
            case OffsetDateTime o -> o.atZoneSameInstant(Fechas.BOGOTA).format(FECHA_HORA);
            case ZonedDateTime z -> z.withZoneSameInstant(Fechas.BOGOTA).format(FECHA_HORA);
            case LocalDate d -> d.toString();
            default -> v;
        };
    }

    /** El archivo, en flujo desde el almacén, con el código de verificación en una cabecera. */
    static ResponseEntity<Resource> descarga(ServicioGeneracion.Archivo a, InputStream contenido) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + a.nombre() + "\"")
                .header(HttpHeaders.CONTENT_TYPE, a.tipo()).contentLength(a.tamano()).header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header("Content-Security-Policy", "sandbox").header("X-Codigo-Verificacion", a.codigo())
                .body(new InputStreamResource(contenido));
    }

    @GetMapping("/{id}/exportar")
    @Accion(modulo = "reportes", accion = "exportar", operacion = Declarada.CONSULTA)
    public ResponseEntity<Resource> exportar(@PathVariable String id, @RequestParam MultiValueMap<String, String> parametros) throws IOException {
        Map<String, List<String>> p = new LinkedHashMap<>(parametros);
        List<String> f = p.remove("formato");
        if (f != null && f.size() > 1) {
            throw ErrorNegocio.validacion("El parámetro «formato» admite un solo valor.");
        }
        String formato = formato(f == null || f.isEmpty() ? null : f.get(0));
        ServicioGeneracion.Quien q = quien();
        MotorReportes.Solicitud s = resolver(id, p);
        ServicioGeneracion.Archivo a = generacion.exportar(s, formato, q);
        InputStream contenido = almacen.leer(a.ruta()); // primero se abre: si falla, la bitácora no dice «descargado»
        bitacora.registrar("reporte_descargado", s.definicion().titulo() + " descargado (" + a.nombre() + ", " + a.codigo() + ") · "
                + resumen(s, String.valueOf(a.filas()), a.ms()), q.id(), UsuarioSesion.actual().orElseThrow().usuario());
        return descarga(a, contenido);
    }

    /**
     * Envía el reporte por correo con el archivo adjunto (Anexo 5, condiciones 12 y 17; simulacro 5 a 9, 13 y 15). El cuerpo lleva
     * {@code para}, {@code formato}, el periodo y los filtros: en {@code filtros} como listas, o sueltos como en la pantalla de antes
     * ({@code puntoId}, {@code fuerza}…). El adjunto es el mismo archivo del repositorio del día, con la misma huella.
     */
    @PostMapping("/{id}/enviar")
    @Accion(modulo = "reportes", accion = "enviar", operacion = Declarada.CONSULTA)
    public Map<String, Object> enviar(@PathVariable String id, @RequestBody Map<String, Object> cuerpo) {
        String para = Correos.normalizar(cuerpo.get("para") instanceof String s ? s.strip() : "");
        if (!Correos.valido(para)) {
            throw ErrorNegocio.validacion("Escriba un correo válido para enviar el reporte.");
        }
        List<String> dominios = cfg.dominiosReportes();
        String dominio = para.substring(para.indexOf('@') + 1);
        if (!dominios.isEmpty() && dominios.stream().noneMatch(d -> dominio.equals(d) || dominio.endsWith("." + d))) {
            throw ErrorNegocio.validacion("Los reportes solo se envían a correos de: " + String.join(", ", dominios) + ".");
        }
        Object pedido = cuerpo.get("formato");
        if (pedido != null && !(pedido instanceof String)) {
            throw ErrorNegocio.validacion("El formato debe ser xlsx, csv, pdf o txt.");
        }
        String formato = formato((String) pedido);
        ServicioGeneracion.Quien q = quien();
        MotorReportes.Solicitud s = resolver(id, parametrosDelCuerpo(cuerpo));
        ServicioGeneracion.Archivo a = generacion.exportar(s, formato, q);
        if (a.tamano() > cfgReportes.adjuntoMaxBytes()) {
            bitacora.registrar("reporte_enviado", s.definicion().titulo() + " NO enviado a " + Correos.enmascarar(para) + ": el archivo (" + a.codigo() + ") pesa "
                    + megas(a.tamano()) + " y el tope del adjunto es " + cfgReportes.adjuntoMaxMb() + " MB", q.id(), UsuarioSesion.actual().orElseThrow().usuario());
            throw ErrorNegocio.validacion("El archivo pesa " + megas(a.tamano()) + " y el correo admite hasta " + cfgReportes.adjuntoMaxMb()
                    + " MB. Descárguelo (código " + a.codigo() + "), elija otro formato o acote los filtros.");
        }
        byte[] contenido;
        try (InputStream in = almacen.leer(a.ruta())) {
            contenido = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el reporte " + a.codigo() + " del almacén", e);
        }
        String titulo = s.definicion().titulo();
        Correo.Resultado res;
        try {
            res = correo.enviar(new Correo.Mensaje(List.of(para), List.of(), titulo + " · Suite DIGSA",
                    PlantillaCorreo.marco(titulo, "<p>Adjunto el reporte generado en la Suite DIGSA.</p><p>Archivo: " + PlantillaCorreo.e(a.nombre())
                            + "</p><p>Código de verificación: <strong>" + PlantillaCorreo.e(a.codigo()) + "</strong></p>"),
                    List.of(new Correo.Adjunto(a.nombre(), a.tipo(), contenido))));
        } catch (RuntimeException e) {
            // Un error inesperado del canal también queda en la bitácora como «NO salió» y se responde 502, no 500
            LOG.error("[reportes] Falló el envío de {} a {}", titulo, Correos.enmascarar(para), e);
            res = new Correo.Resultado(false, List.of(), List.of(para), "error inesperado del servidor de correo");
        }
        bitacora.registrar("reporte_enviado", titulo + " enviado a " + Correos.enmascarar(para) + " (" + a.nombre() + ", " + a.codigo() + ")"
                + (res.ok() ? "" : " · NO salió") + " · " + resumen(s, String.valueOf(a.filas()), a.ms()), q.id(), UsuarioSesion.actual().orElseThrow().usuario());
        if (!res.ok()) {
            throw new ErrorNegocio(HttpStatus.BAD_GATEWAY, "No se pudo enviar el correo: " + res.error());
        }
        return Map.of("mensaje", "Reporte enviado a " + para + ".", "codigo", a.codigo());
    }

    static String megas(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0).replace('.', ',');
    }

    /** Formato pedido, validado antes de generar el reporte: xlsx por defecto. */
    static String formato(String pedido) {
        String f = pedido == null || pedido.isBlank() ? "xlsx" : pedido.strip().toLowerCase(Locale.ROOT);
        if (!FORMATOS.contains(f)) {
            throw ErrorNegocio.validacion("El formato debe ser xlsx, csv, pdf o txt.");
        }
        return f;
    }

    /** Periodo y filtros del cuerpo del envío, con la misma forma que los parámetros de la URL. */
    static Map<String, List<String>> parametrosDelCuerpo(Map<String, Object> cuerpo) {
        Map<String, List<String>> p = new LinkedHashMap<>();
        cuerpo.forEach((k, v) -> {
            if (!FUERA_DE_FILTROS.contains(k) && v != null) {
                p.put(ALIAS.getOrDefault(k, k), valores(v, k));
            }
        });
        if (cuerpo.get("filtros") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                String clave = String.valueOf(k);
                if (v != null && p.putIfAbsent(clave, valores(v, clave)) != null) {
                    throw ErrorNegocio.validacion("El filtro «" + clave + "» viene dos veces: suelto y en «filtros».");
                }
            });
        } else if (cuerpo.get("filtros") != null) {
            throw ErrorNegocio.validacion("«filtros» debe ser un objeto con la lista de valores de cada filtro.");
        }
        return p;
    }

    private static List<String> valores(Object v, String clave) {
        if (v instanceof Collection<?> c) {
            return c.stream().map(x -> valor(x, clave)).toList();
        }
        return List.of(valor(v, clave));
    }

    private static String valor(Object x, String clave) {
        if (x instanceof String || x instanceof Number || x instanceof Boolean) {
            return String.valueOf(x);
        }
        throw ErrorNegocio.validacion("El valor de «" + clave + "» no es válido.");
    }

    private MotorReportes.Solicitud resolver(String id, Map<String, List<String>> parametros) {
        return motor.resolver(id, parametros, UsuarioSesion.perfilesActuales(), UsuarioSesion.alcanceActual());
    }

    /**
     * Lo que va a la bitácora: primero las filas y el tiempo (nunca se pierden si la fila se corta), luego el periodo y los códigos de
     * los valores elegidos de cada filtro. El texto libre de un filtro de búsqueda no se registra, solo que se usó.
     */
    static String resumen(MotorReportes.Solicitud s, String filas, long ms) {
        DefinicionReporte d = s.definicion();
        List<String> partes = new ArrayList<>();
        partes.add(filas + " filas · " + ms + " ms");
        if (!s.periodo().isEmpty()) {
            partes.add("Periodo: " + s.periodo());
        }
        s.consulta().filtros().forEach((clave, valores) -> {
            DefinicionReporte.Filtro f = d.filtro(clave);
            String texto = f.tipo() == DefinicionReporte.TipoFiltro.TEXTO ? "(búsqueda por texto)"
                    : valores.size() > MAX_EN_BITACORA ? valores.size() + " opciones" : String.join(",", valores);
            partes.add(f.etiqueta() + ": " + texto);
        });
        if (s.consulta().agrupacion() != null) {
            partes.add("Agrupado por " + s.consulta().agrupacion().clave());
        }
        return String.join(" · ", partes);
    }
}
