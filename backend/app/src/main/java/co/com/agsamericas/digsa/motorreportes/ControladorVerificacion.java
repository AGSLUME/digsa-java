package co.com.agsamericas.digsa.motorreportes;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import co.com.agsamericas.digsa.reglas.Fechas;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Verificar un reporte (Anexo 5, 2.10; RESPUESTAS l.555): por el archivo, que se compara por su huella con el registro, o por el
 * código de verificación impreso, que muestra lo que se generó y permite regenerarlo con los mismos parámetros para ver si los datos
 * siguen iguales. Cualquier usuario con sesión verifica, con un tope por minuto (los códigos no se pueden enumerar); los parámetros
 * solo los ve quien generó el reporte, y regenerar exige poder generarlo con el mismo alcance y las mismas columnas. El archivo subido
 * no se guarda.
 */
@RestController
@RequestMapping("/api/reportes/verificar")
@PreAuthorize("@permisos.pantalla('INICIO')")
public class ControladorVerificacion {

    static final String INTEGRO = "integro";
    static final String ALTERADO = "alterado";
    static final String SIN_CAMBIOS = "sin_cambios";
    static final String CAMBIARON = "cambiaron";
    static final String FALLIDA = "fallida";
    static final String EN_CURSO = "en_curso";

    private final RegistroGenerados registro;
    private final MotorReportes motor;
    private final RegistroBitacora bitacora;
    private final ConfiguracionReportes cfg;
    private final Clock reloj;
    /** Momentos de las últimas verificaciones de cada usuario (ventana de un minuto, en memoria de la instancia). */
    private final Map<Long, Deque<Long>> recientes = new ConcurrentHashMap<>();

    public ControladorVerificacion(RegistroGenerados registro, MotorReportes motor, RegistroBitacora bitacora, ConfiguracionReportes cfg, Clock reloj) {
        this.registro = registro;
        this.motor = motor;
        this.bitacora = bitacora;
        this.cfg = cfg;
        this.reloj = reloj;
    }

    /** Tope de verificaciones por usuario y minuto: más es 429 (y queda en la bitácora). */
    private void contar() {
        UsuarioSesion u = UsuarioSesion.actual().orElseThrow();
        long ahora = reloj.millis();
        Deque<Long> v = recientes.computeIfAbsent(u.id(), k -> new ArrayDeque<>());
        synchronized (v) {
            while (!v.isEmpty() && ahora - v.peekFirst() > 60_000) {
                v.pollFirst();
            }
            if (v.size() >= cfg.verificacionesPorMinuto()) {
                bitacora.registrar("reporte_verificado", "Verificación rechazada: más de " + cfg.verificacionesPorMinuto() + " por minuto", u.id(), u.usuario());
                throw new ErrorNegocio(HttpStatus.TOO_MANY_REQUESTS, "Hizo muchas verificaciones seguidas. Espere un minuto e intente de nuevo.");
            }
            v.addLast(ahora);
        }
    }

    private void anotar(String detalle) {
        UsuarioSesion u = UsuarioSesion.actual().orElseThrow();
        bitacora.registrar("reporte_verificado", detalle, u.id(), u.usuario());
    }

    /** Sube el archivo descargado: «Íntegro» si su huella es la de un reporte generado; si no, «Alterado o no generado por la Suite DIGSA». */
    @PostMapping
    @Accion(modulo = "reportes", accion = "verificar", operacion = Declarada.CONSULTA)
    public Map<String, Object> porArchivo(@RequestParam("archivo") MultipartFile archivo) throws IOException {
        if (archivo == null || archivo.isEmpty()) {
            throw ErrorNegocio.validacion("Suba el archivo del reporte que quiere verificar.");
        }
        contar();
        String huella;
        try (InputStream in = archivo.getInputStream()) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] b = new byte[64 * 1024];
            for (int n; (n = in.read(b)) > 0; ) {
                sha.update(b, 0, n);
            }
            huella = HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        var g = registro.porHuellaArchivo(huella);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("huellaArchivo", huella);
        if (g.isEmpty()) {
            anotar("Verificación de un archivo: alterado o no generado por la Suite DIGSA (huella " + huella.substring(0, 16) + "…)");
            m.put("resultado", ALTERADO);
            m.put("mensaje", "Alterado o no generado por la Suite DIGSA: ningún reporte generado tiene esa huella.");
            return m;
        }
        anotar("Verificación del archivo del reporte " + g.get().codigo() + ": íntegro");
        m.put("resultado", INTEGRO);
        m.put("mensaje", "Íntegro: el archivo es el que generó la Suite DIGSA con el código " + g.get().codigo() + ".");
        m.put("reporte", vista(g.get()));
        return m;
    }

    /** Lo que se registró con ese código (sin las filas); el resultado depende del estado de la generación. */
    @GetMapping("/{codigo}")
    @Accion(modulo = "reportes", accion = "verificar", operacion = Declarada.CONSULTA)
    public Map<String, Object> porCodigo(@PathVariable String codigo) {
        contar();
        RegistroGenerados.Generado g = buscar(codigo);
        Map<String, Object> m = new LinkedHashMap<>();
        switch (g.estado()) {
            case "fallida" -> {
                m.put("resultado", FALLIDA);
                m.put("mensaje", "La generación con el código " + g.codigo() + " falló: ningún archivo válido lleva ese código.");
            }
            case "pendiente", "generando" -> {
                m.put("resultado", EN_CURSO);
                m.put("mensaje", "El reporte con el código " + g.codigo() + " todavía se está generando.");
            }
            default -> {
                m.put("resultado", INTEGRO);
                m.put("mensaje", "El código " + g.codigo() + " corresponde a un reporte generado por la Suite DIGSA.");
            }
        }
        anotar("Consulta del código " + g.codigo() + ": " + m.get("resultado"));
        m.put("reporte", vista(g));
        return m;
    }

    /**
     * Regenera el reporte con los mismos parámetros y compara la huella de los datos: «Íntegro; los datos no han cambiado» o «Íntegro;
     * los datos cambiaron desde la generación». Exige el mismo reporte (versión), el mismo alcance y la misma variante de columnas.
     */
    @PostMapping("/{codigo}/regenerar")
    @Accion(modulo = "reportes", accion = "regenerar", operacion = Declarada.CONSULTA)
    public Map<String, Object> regenerar(@PathVariable String codigo) {
        contar();
        RegistroGenerados.Generado g = buscar(codigo);
        if (List.of("fallida", "pendiente", "generando").contains(g.estado())) {
            throw ErrorNegocio.validacion("Ese reporte no terminó de generarse («" + g.estado() + "»): no hay nada que comparar.");
        }
        Alcance alcance = UsuarioSesion.alcanceActual();
        var p = motor.proveedor(g.reporte(), UsuarioSesion.perfilesActuales(), alcance); // 404 si no lo puede generar
        if (p.definicion().version() != g.version()) {
            throw ErrorNegocio.validacion("El reporte cambió de versión (" + g.version() + " → " + p.definicion().version() + "): no se puede regenerar igual.");
        }
        if (!HuellaDatos.alcance(alcance).equals(g.alcance())) {
            throw ErrorNegocio.validacion("Su alcance es distinto del de quien generó el reporte: las cifras no son comparables (2.10).");
        }
        if (g.huellaDatos() == null) {
            throw ErrorNegocio.validacion("Ese reporte se vio en pantalla sin todas sus filas: no tiene huella de los datos para comparar.");
        }
        MotorReportes.Solicitud s = motor.resolver(g.reporte(), parametros(g), UsuarioSesion.perfilesActuales(), alcance);
        if (!MotorReportes.columnas(s).equals(g.columnas())) {
            throw ErrorNegocio.validacion("Su perfil ve otras columnas de este reporte: la huella no se puede comparar. Pida la verificación a quien lo generó.");
        }
        HuellaDatos h = new HuellaDatos(s.definicion(), s.consulta());
        long filas = motor.volcar(s, 0, null, h::fila);
        String hoy = h.hex();
        boolean igual = hoy.equals(g.huellaDatos());
        anotar("Regeneración del reporte " + g.codigo() + ": " + (igual ? "los datos no han cambiado" : "los datos cambiaron"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resultado", igual ? SIN_CAMBIOS : CAMBIARON);
        m.put("mensaje", igual ? "Íntegro: los datos no han cambiado desde la generación (misma huella)."
                : "Íntegro; los datos cambiaron desde la generación: hoy salen " + filas + " filas con otra huella.");
        m.put("huellaHoy", hoy);
        m.put("filasHoy", filas);
        m.put("reporte", vista(g));
        return m;
    }

    private RegistroGenerados.Generado buscar(String escrito) {
        String codigo = CodigoVerificacion.normalizar(escrito);
        if (codigo.isEmpty()) {
            throw ErrorNegocio.validacion("El código de verificación tiene la forma RPT-aaaammdd-XXXXXXXX.");
        }
        return registro.porCodigo(codigo).orElseThrow(() -> ErrorNegocio.noEncontrado("No hay ningún reporte con el código " + codigo + "."));
    }

    @SuppressWarnings("unchecked")
    static Map<String, List<String>> parametros(RegistroGenerados.Generado g) {
        Map<String, List<String>> p = new LinkedHashMap<>();
        Object desde = g.parametros().get("desde");
        if (desde != null) {
            p.put("desde", List.of(String.valueOf(desde)));
            p.put("hasta", List.of(String.valueOf(g.parametros().get("hasta"))));
        }
        if (g.parametros().get("agrupacion") != null) {
            p.put("agrupacion", List.of(String.valueOf(g.parametros().get("agrupacion"))));
        }
        if (g.parametros().get("filtros") instanceof Map<?, ?> f) {
            f.forEach((k, v) -> p.put(String.valueOf(k), new ArrayList<>((List<String>) v)));
        }
        return p;
    }

    /**
     * Lo que se muestra del registro. A quien no lo generó: sin los valores de los filtros (pueden ser búsquedas con datos personales o
     * de otro alcance, Ley 1581) y con el usuario reducido a iniciales.
     */
    private static Map<String, Object> vista(RegistroGenerados.Generado g) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean propio = UsuarioSesion.actual().map(u -> u.id() == g.usuarioId()).orElse(false);
        m.put("codigo", g.codigo());
        m.put("titulo", g.titulo());
        m.put("generado", g.creado().atZoneSameInstant(Fechas.BOGOTA).format(ServicioGeneracion.FECHA_HORA));
        m.put("usuario", propio ? g.usuario() : iniciales(g.usuario()));
        Map<String, Object> p = new LinkedHashMap<>(g.parametros());
        if (!propio) {
            p.remove("filtros");
        }
        m.put("parametros", p);
        m.put("filtrosOcultos", !propio);
        m.put("formato", g.formato() == null ? "pantalla" : g.formato());
        m.put("filas", g.filas());
        m.put("huellaDatos", g.huellaDatos());
        m.put("huellaArchivo", g.huellaArchivo());
        m.put("estado", g.estado());
        return m;
    }

    /** «Ana María Pérez (aperez)» ⇒ «A. M. P.» (sin el usuario de acceso ni el largo de cada palabra). */
    static String iniciales(String s) {
        String nombre = s.contains(" (") ? s.substring(0, s.lastIndexOf(" (")) : s;
        StringBuilder sb = new StringBuilder();
        for (String parte : nombre.trim().split("\\s+")) {
            if (!parte.isEmpty()) {
                sb.append(sb.isEmpty() ? "" : " ").append(Character.toUpperCase(parte.charAt(0))).append('.');
            }
        }
        return sb.isEmpty() ? "Otro usuario" : sb.toString();
    }
}
