package co.com.agsamericas.digsa.correos;

import co.com.agsamericas.digsa.alertas.ServicioCatalogoAlertas;
import co.com.agsamericas.digsa.catalogos.ServicioCatalogos;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Perfil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Módulo Correos, fase C3: destinatarios. Vista consolidada de a quién le llega cada correo y de dónde sale ese dato, sin duplicar la
 * fuente de verdad: cada regla dice su origen y la pantalla donde se cambia. Aquí solo se editan, con el mismo servicio de siempre, el
 * buzón de la DIGSA (parámetro correo_digsa) y los buzones de la Sanidad de cada fuerza; los dos cambios piden motivo y quedan en los
 * logs. Las alertas se configuran en su pantalla (perfiles, usuarios, SMS y activación van juntos). La consulta queda en los logs:
 * muestra buzones completos.
 */
@RestController
@RequestMapping("/api/correos/destinatarios")
@PreAuthorize("@permisos.pantalla('CORREOS')")
public class ControladorDestinatarios {

    static final int MAX_CORREOS = 20;

    /** Una regla: a quién le llega un tipo de correo, de dónde sale y dónde se cambia. */
    record Regla(String tipo, String regla, String origen, String pantalla, String ruta) {
    }

    record Buzones(List<String> correos, String motivo) {
    }

    static final List<Regla> REGLAS = List.of(
            new Regla("Código de verificación (MFA) y enlace de contraseña", "El propio usuario.", "Correo del usuario", "Usuarios", "/usuarios"),
            new Regla("Notificación previa de visita", "Sanidad (DISAN o JEFSA) de la fuerza del punto, OPL y, en el nivel central, la DIGSA "
                    + "(obligatorios); también el contacto del punto. Quien notifica a mano puede ajustar la lista.",
                    "Buzones de la fuerza, correos del OPL, buzón de la DIGSA y correos del punto", "Catálogos y Puntos y auditores", "/catalogos"),
            new Regla("Acta de visita", "Los de la notificación previa, la DIGSA (todas las actas), el equipo auditor y el personal auditado con "
                    + "correo.", "Igual que la notificación, más el equipo y el personal de la visita", "Visita", "/agenda"),
            new Regla("Solicitud de visita a necesidad", "Todos los coordinadores activos.", "Usuarios con perfil Coordinador", "Usuarios", "/usuarios"),
            new Regla("Visita a necesidad agendada o cancelada", "Quien hizo la solicitud.", "Correo del solicitante", "Usuarios", "/usuarios"),
            new Regla("Aviso y recordatorio de visita de seguimiento", "El auditor líder del seguimiento.", "Correo del auditor", "Usuarios", "/usuarios"),
            new Regla("Plan de mejoramiento sin cerrar", "Los coordinadores activos con copia a la DIGSA (sin coordinadores, solo la DIGSA).",
                    "Usuarios con perfil Coordinador y buzón de la DIGSA", "Usuarios", "/usuarios"),
            new Regla("Avisos del flujo del OPL", "Al OPL (hallazgo ratificado o levantado, plan cerrado) o al auditor líder (objeción, plan "
                    + "firmado, aceptación automática).", "Correos del OPL y del auditor líder", "Puntos y auditores", "/maestros"),
            new Regla("Código de verificación del OPL (QR)", "El correo del OPL que elige quien responde el acta.", "Correos del OPL",
                    "Puntos y auditores", "/maestros"),
            new Regla("Reporte formal a la DIGSA", "La DIGSA y el auditor líder.", "Buzón de la DIGSA y correo del auditor", "Correos", "/correos"),
            new Regla("Notificación presupuestal", "La Sanidad de la fuerza (en el nivel nacional, todas) y la DIGSA.", "Buzones de la fuerza y de la DIGSA",
                    "Correos", "/correos"),
            new Regla("Alertas y avisos", "Los perfiles y usuarios que tiene cada alerta; las críticas con SMS, también por SMS.",
                    "Configuración de cada alerta", "Alertas", "/alertas"),
            new Regla("Reportes, logs y presupuesto enviados por correo (incluido el comité)", "Lo indica quien envía, solo a los dominios permitidos.",
                    "Lo escribe quien envía", "Reportes", "/reportes"));

    private final ServicioCatalogos catalogos;
    private final ServicioCatalogoAlertas alertas;
    private final ConfiguracionDigsa cfg;
    private final JdbcClient jdbc;
    private final RegistroBitacora bitacora;

    public ControladorDestinatarios(ServicioCatalogos catalogos, ServicioCatalogoAlertas alertas, ConfiguracionDigsa cfg, JdbcClient jdbc,
                                    RegistroBitacora bitacora) {
        this.catalogos = catalogos;
        this.alertas = alertas;
        this.cfg = cfg;
        this.jdbc = jdbc;
        this.bitacora = bitacora;
    }

    @GetMapping
    @Accion(modulo = "correos", accion = "destinatarios", operacion = Declarada.CONSULTA)
    public Map<String, Object> ver() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reglas", REGLAS);
        m.put("digsa", buzonDigsa());
        m.put("fuerzas", catalogos.fuerzas().stream().map(f -> Map.of("codigo", f.get("codigo"), "nombre", f.get("nombre"),
                "sanidad", f.get("sanidad") == null ? "" : f.get("sanidad"), "correos", f.get("correos"), "activo", f.get("activo"))).toList());
        m.put("opl", jdbc.sql("SELECT correos FROM opl WHERE activo ORDER BY id LIMIT 1").query((rs, n) -> List.of((String[]) rs.getArray(1).getArray()))
                .optional().orElse(List.of()));
        m.put("dominiosReportes", cfg.dominiosReportes());
        Map<String, Object> cat = alertas.catalogo();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tipos = (List<Map<String, Object>>) cat.get("tipos");
        m.put("alertas", tipos.stream().map(t -> {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("nombre", t.get("nombre"));
            a.put("activa", t.get("activa"));
            a.put("critica", t.get("critica"));
            a.put("sms", t.get("sms"));
            @SuppressWarnings("unchecked")
            List<String> codigos = (List<String>) t.get("perfiles");
            a.put("perfiles", codigos.stream().map(c -> {
                try {
                    return Perfil.valueOf(c).nombre();
                } catch (IllegalArgumentException e) {
                    return c;
                }
            }).toList());
            a.put("usuarios", ((List<?>) t.get("usuarios")).size());
            a.put("destinatarios", t.get("destinatarios"));
            return a;
        }).toList());
        UsuarioSesion u = UsuarioSesion.actual().orElseThrow();
        bitacora.registrar("correo_destinatarios_vistos", "Destinatarios: consulta de los buzones de la DIGSA, de las fuerzas y del OPL", u.id(), u.usuario());
        return m;
    }

    @PutMapping("/digsa")
    @Accion(modulo = "correos", accion = "editar_buzon_digsa", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> editarDigsa(@RequestBody Buzones b) {
        String motivo = motivo(b);
        List<String> correos = validar(b.correos(), true);
        UsuarioSesion u = UsuarioSesion.actual().orElseThrow();
        // Primero el registro propio: la bitácora junta los de la petición en una fila con el tipo del primero
        bitacora.registrar("correo_destinatarios_editados", "Buzón de la DIGSA: " + correos.size() + " correo(s). Motivo: " + motivo, u.id(), u.usuario());
        catalogos.editarParametro("correo_digsa", String.join(", ", correos), u);
        return Map.of("mensaje", "Buzón de la DIGSA guardado: " + correos.size() + " correo(s).", "correos", buzonDigsa());
    }

    @PutMapping("/fuerzas/{codigo}")
    @Accion(modulo = "correos", accion = "editar_buzon_fuerza", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> editarFuerza(@PathVariable String codigo, @RequestBody Buzones b) {
        String motivo = motivo(b);
        List<String> correos = validar(b.correos(), false);
        Map<String, Object> f = catalogos.fuerzas().stream().filter(x -> codigo.equals(x.get("codigo"))).findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("La fuerza no existe."));
        UsuarioSesion u = UsuarioSesion.actual().orElseThrow();
        bitacora.registrar("correo_destinatarios_editados", "Buzones de la fuerza " + codigo + ": " + correos.size() + " correo(s). Motivo: " + motivo,
                u.id(), u.usuario());
        // Mismo servicio que Catálogos: nombre, sanidad y estado se conservan; solo cambian los correos
        catalogos.editarFuerza(codigo, new ServicioCatalogos.Fuerza(codigo, (String) f.get("nombre"), (String) f.get("sanidad"), correos,
                (Boolean) f.get("activo")), u);
        return Map.of("mensaje", "Buzones de " + f.get("nombre") + " guardados: " + correos.size() + " correo(s).");
    }

    private List<String> buzonDigsa() {
        List<String> r = new ArrayList<>();
        for (String c : catalogos.textoParametro("correo_digsa", "").split("[,;\\s]+")) {
            String n = Correos.normalizar(c);
            if (Correos.valido(n) && !r.contains(n)) {
                r.add(n);
            }
        }
        return r;
    }

    /** Correos válidos, sin repetir. La DIGSA necesita al menos uno (recibe actas, reportes y notificaciones). */
    static List<String> validar(List<String> correos, boolean alMenosUno) {
        List<String> r = new ArrayList<>();
        List<String> malos = new ArrayList<>();
        for (String c : correos == null ? List.<String>of() : correos) {
            String n = Correos.normalizar(c == null ? "" : c.strip());
            if (n.isEmpty()) {
                continue;
            }
            if (!Correos.valido(n)) {
                malos.add(c.strip());
            } else if (!r.contains(n)) {
                r.add(n);
            }
        }
        if (!malos.isEmpty()) {
            throw ErrorNegocio.validacion("Estos correos no son válidos: " + String.join(", ", malos) + ".", "correos");
        }
        if (alMenosUno && r.isEmpty()) {
            throw ErrorNegocio.validacion("Escriba al menos un correo de la DIGSA: recibe todas las actas, los reportes y las notificaciones.", "correos");
        }
        if (r.size() > MAX_CORREOS) {
            throw ErrorNegocio.validacion("Máximo " + MAX_CORREOS + " correos.", "correos");
        }
        return r;
    }

    private static String motivo(Buzones b) {
        String m = b == null || b.motivo() == null ? "" : b.motivo().strip();
        int largo = m.codePointCount(0, m.length());
        if (largo < ControladorCorreos.MOTIVO_MIN || largo > ControladorCorreos.MOTIVO_MAX) {
            throw ErrorNegocio.validacion("Escriba el motivo (entre " + ControladorCorreos.MOTIVO_MIN + " y " + ControladorCorreos.MOTIVO_MAX
                    + " caracteres).", "motivo");
        }
        return m;
    }
}
