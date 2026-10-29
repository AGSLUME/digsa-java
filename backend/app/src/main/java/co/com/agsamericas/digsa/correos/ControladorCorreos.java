package co.com.agsamericas.digsa.correos;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.plataforma.BandejaSalida;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Fechas;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Módulo Correos (pedido del usuario del 2026-10-29), fase C1: bitácora de envíos con filtros, vista previa y reintento manual de los
 * fallidos. La descarga en Excel o CSV es el reporte «correos_envios» del motor de reportes (huella y código de verificación, como
 * Logs). Solo el Administrador. En la tabla los destinatarios van enmascarados (Ley 1581); la vista previa los muestra completos y
 * esa consulta queda en la bitácora de auditoría.
 */
@RestController
@RequestMapping("/api/correos")
@PreAuthorize("@permisos.pantalla('CORREOS')")
public class ControladorCorreos {

    static final int POR_PAGINA = 100;
    static final int MOTIVO_MIN = 10;
    static final int MOTIVO_MAX = 300;
    /** Destinatarios enmascarados que se muestran en la tabla; el resto va como «y N más». */
    static final int DESTINATARIOS_EN_TABLA = 3;
    private static final DateTimeFormatter FH = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    record Reintento(String motivo) {
    }

    private final RepositorioEnvios envios;
    private final BandejaSalida bandeja;
    private final RegistroBitacora bitacora;
    private final Clock reloj;
    private final ObjectMapper json;

    public ControladorCorreos(RepositorioEnvios envios, BandejaSalida bandeja, RegistroBitacora bitacora, Clock reloj, ObjectMapper json) {
        this.envios = envios;
        this.bandeja = bandeja;
        this.bitacora = bitacora;
        this.reloj = reloj;
        this.json = json;
    }

    @GetMapping("/opciones")
    @Accion(modulo = "correos", accion = "opciones", operacion = Declarada.CONSULTA)
    public Map<String, Object> opciones() {
        return Map.of("tipos", tipos(), "estados", RepositorioEnvios.ESTADOS);
    }

    /** Tipos para el filtro: los conocidos y los que ya aparecen en la bitácora (p. ej. cada alerta:tipo). */
    static List<Map<String, String>> tipos(List<String> enBitacora) {
        Set<String> todos = new LinkedHashSet<>(TiposCorreo.NOMBRES.keySet());
        todos.addAll(enBitacora);
        return todos.stream().map(o -> Map.of("valor", o, "nombre", TiposCorreo.nombre(o))).sorted((a, b) -> a.get("nombre").compareTo(b.get("nombre")))
                .toList();
    }

    private List<Map<String, String>> tipos() {
        return tipos(envios.origenes());
    }

    @GetMapping("/envios")
    @Accion(modulo = "correos", accion = "envios", operacion = Declarada.CONSULTA)
    public Map<String, Object> lista(@RequestParam(required = false) String desde, @RequestParam(required = false) String hasta,
                                     @RequestParam(required = false) List<String> tipo, @RequestParam(required = false) List<String> estado,
                                     @RequestParam(required = false) String destinatario, @RequestParam(required = false) String despuesFecha,
                                     @RequestParam(required = false) Long despuesId) {
        RepositorioEnvios.Filtro f = filtro(desde, hasta, tipo, estado, destinatario, reloj);
        OffsetDateTime df = null;
        if (despuesFecha != null && !despuesFecha.isBlank()) {
            try {
                df = OffsetDateTime.parse(despuesFecha);
            } catch (DateTimeParseException e) {
                throw ErrorNegocio.validacion("La página pedida no es válida.");
            }
        }
        List<RepositorioEnvios.Fila> filas = envios.pagina(f, df, despuesId, POR_PAGINA + 1);
        boolean hayMas = filas.size() > POR_PAGINA;
        List<RepositorioEnvios.Fila> visibles = hayMas ? filas.subList(0, POR_PAGINA) : filas;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("filas", visibles.stream().map(ControladorCorreos::fila).toList());
        m.put("siguiente", hayMas ? Map.of("fecha", visibles.getLast().creado().toString(), "id", visibles.getLast().id()) : null);
        m.put("desde", f.desde().toString());
        m.put("hasta", f.hasta().toString());
        return m;
    }

    /** Vista previa: el correo como se guardó (con los secretos ocultos) y los destinatarios completos, que quedan en la bitácora. */
    @GetMapping("/envios/{id}")
    @Accion(modulo = "correos", accion = "ver_envio", operacion = Declarada.CONSULTA)
    public Map<String, Object> ver(@PathVariable long id) {
        RepositorioEnvios.Fila f = envios.porId(id).orElseThrow(() -> ErrorNegocio.noEncontrado("El envío no existe o ya se purgó."));
        Map<String, Object> m = fila(f);
        m.put("para", f.para());
        m.put("copia", f.copia());
        m.put("html", envios.html(id).orElse(""));
        m.put("adjuntos", adjuntos(f.adjuntos()));
        UsuarioSesion u = UsuarioSesion.actual().orElseThrow();
        bitacora.registrar("correo_destinatarios_vistos", "Envío " + id + " (" + f.origen() + "): vista previa con los "
                + (f.para().size() + f.copia().size()) + " destinatarios completos", u.id(), u.usuario());
        return m;
    }

    @PostMapping("/envios/{id}/reintento")
    @Accion(modulo = "correos", accion = "reintentar", operacion = Declarada.INSERCION)
    public Map<String, Object> reintentar(@PathVariable long id, @RequestBody Reintento r) {
        String motivo = r == null || r.motivo() == null ? "" : r.motivo().strip();
        int largo = motivo.codePointCount(0, motivo.length());
        if (largo < MOTIVO_MIN || largo > MOTIVO_MAX) {
            throw ErrorNegocio.validacion("Escriba el motivo del reintento (entre " + MOTIVO_MIN + " y " + MOTIVO_MAX + " caracteres).", "motivo");
        }
        RepositorioEnvios.Fila f = envios.porId(id).orElseThrow(() -> ErrorNegocio.noEncontrado("El envío no existe o ya se purgó."));
        String noSe = porQueNoSeReintenta(f);
        if (noSe != null) {
            throw ErrorNegocio.conflicto(noSe);
        }
        UsuarioSesion u = UsuarioSesion.actual().orElseThrow();
        long nuevo = bandeja.reintentar(id, motivo, u.id())
                .orElseThrow(() -> ErrorNegocio.conflicto("El envío ya se reintentó o cambió de estado. Actualice la lista."));
        bitacora.registrar("correo_reintentado", "Envío " + id + " (" + f.origen() + ") reintentado como envío " + nuevo + ". Motivo: " + motivo,
                u.id(), u.usuario());
        return Map.of("id", nuevo, "mensaje", "El correo quedó en la bandeja de salida como envío N.º " + nuevo + ".");
    }

    /** Por qué un envío no se puede reintentar desde aquí, o null si se puede (lo usa también la pantalla, con BotonConMotivo). */
    static String porQueNoSeReintenta(RepositorioEnvios.Fila f) {
        if (!f.reintentable() || f.sincrono()) {
            return f.motivoNoReintentable() == null ? "Este envío no se reintenta desde la bitácora." : f.motivoNoReintentable();
        }
        if (!"fallido".equals(f.estado())) {
            return "Solo se reintentan los envíos fallidos.";
        }
        if (f.reintentoId() != null) {
            return "Ya se reintentó como envío N.º " + f.reintentoId() + ".";
        }
        return null;
    }

    static RepositorioEnvios.Filtro filtro(String desde, String hasta, List<String> tipos, List<String> estados, String destinatario, Clock reloj) {
        LocalDate hoy = LocalDate.now(reloj.withZone(Fechas.BOGOTA));
        LocalDate d = Fechas.valida(desde).orElse(hoy.minusDays(7));
        LocalDate h = Fechas.valida(hasta).orElse(hoy);
        if (d.isAfter(h)) {
            LocalDate t = d;
            d = h;
            h = t;
        }
        List<String> ts = limpiar(tipos);
        if (ts.stream().anyMatch(t -> !t.matches("^[a-z0-9_:.-]{1,60}$"))) {
            throw ErrorNegocio.validacion("El tipo de correo no es válido.");
        }
        List<String> es = limpiar(estados);
        if (!RepositorioEnvios.ESTADOS.containsAll(es)) {
            throw ErrorNegocio.validacion("El estado no es válido: use " + String.join(", ", RepositorioEnvios.ESTADOS) + ".");
        }
        String dest = null;
        if (destinatario != null && !destinatario.isBlank()) {
            dest = Correos.normalizar(destinatario);
            if (!Correos.valido(dest)) {
                throw ErrorNegocio.validacion("Escriba el correo completo del destinatario (la búsqueda es exacta).", "destinatario");
            }
        }
        return new RepositorioEnvios.Filtro(d, h, ts, es, dest);
    }

    private static List<String> limpiar(List<String> valores) {
        if (valores == null) {
            return List.of();
        }
        List<String> r = new ArrayList<>();
        for (String v : valores) {
            if (v != null && !v.isBlank()) {
                r.add(v.strip());
            }
        }
        return r;
    }

    static Map<String, Object> fila(RepositorioEnvios.Fila f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.id());
        m.put("creado", f.creado().atZoneSameInstant(Fechas.BOGOTA).format(FH));
        m.put("creadoLlave", f.creado().toString());
        m.put("origen", f.origen());
        m.put("tipo", TiposCorreo.nombre(f.origen()));
        m.put("referencia", f.referencia());
        m.put("destinatarios", destinatarios(f.para(), f.copia()));
        m.put("totalDestinatarios", f.para().size() + f.copia().size());
        m.put("asunto", f.asunto());
        m.put("estado", f.estado());
        m.put("intentos", f.intentos());
        m.put("error", f.error());
        m.put("enviado", f.enviado() == null ? null : f.enviado().atZoneSameInstant(Fechas.BOGOTA).format(FH));
        m.put("directo", f.sincrono());
        m.put("enmascarado", f.enmascarado());
        m.put("reintentoDe", f.reintentoDe());
        m.put("motivoReintento", f.motivoReintento());
        m.put("reintentoId", f.reintentoId());
        m.put("noReintentable", porQueNoSeReintenta(f));
        return m;
    }

    /** Hasta tres direcciones enmascaradas y «y N más». */
    static String destinatarios(List<String> para, List<String> copia) {
        List<String> todos = new ArrayList<>(para);
        todos.addAll(copia);
        String vistos = String.join(", ", todos.stream().limit(DESTINATARIOS_EN_TABLA).map(Correos::enmascarar).toList());
        return todos.size() > DESTINATARIOS_EN_TABLA ? vistos + " y " + (todos.size() - DESTINATARIOS_EN_TABLA) + " más" : vistos;
    }

    private List<Map<String, Object>> adjuntos(String crudo) {
        if (crudo == null || crudo.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> lista = json.readValue(crudo, new TypeReference<List<Map<String, Object>>>() {
            });
            // Solo nombre, tipo y tamaño: la ruta del almacén no sale de la aplicación
            return lista.stream().map(a -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("nombre", a.get("nombre"));
                x.put("tipo", a.get("tipo"));
                x.put("tamano", a.get("tamano"));
                return x;
            }).toList();
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
