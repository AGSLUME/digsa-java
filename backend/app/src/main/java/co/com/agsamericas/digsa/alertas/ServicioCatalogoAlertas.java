package co.com.agsamericas.digsa.alertas;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Alertas;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Fechas;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Administración de las alertas: el catálogo (activa, SMS y destinatarios de cada tipo; GMI P77 b deja los destinatarios y niveles al
 * Anexo Técnico, por eso es editable) y los eventos con su latencia evento → envío (GMI P75: «en tiempo real o cuasi real»).
 */
@Service
public class ServicioCatalogoAlertas {

    private static final DateTimeFormatter FH = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static final int MAX_USUARIOS = 50;

    /** Lo que edita el administrador de un tipo. Lo que no llega (null) se conserva. */
    public record Edicion(String tipo, String nombre, Boolean activa, Boolean sms, List<String> perfiles, List<Long> usuarios) {
    }

    private final JdbcClient jdbc;
    private final ServicioAlertas alertas;
    private final RegistroBitacora bitacora;
    private final TransactionTemplate tx;
    private final Clock reloj;

    public ServicioCatalogoAlertas(JdbcClient jdbc, ServicioAlertas alertas, RegistroBitacora bitacora, TransactionTemplate tx, Clock reloj) {
        this.jdbc = jdbc;
        this.alertas = alertas;
        this.bitacora = bitacora;
        this.tx = tx;
        this.reloj = reloj;
    }

    public Map<String, Object> catalogo() {
        List<Map<String, Object>> tipos = new ArrayList<>();
        for (ServicioAlertas.Tipo t : alertas.catalogo()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tipo", t.tipo());
            m.put("nombre", t.nombre());
            m.put("descripcion", t.descripcion());
            m.put("critica", t.critica());
            m.put("activa", t.activa());
            m.put("sms", t.sms());
            m.put("perfiles", t.perfiles());
            m.put("usuarios", t.usuarios());
            List<ServicioAlertas.Destinatario> dest = alertas.destinatarios(t);
            m.put("destinatarios", dest.size());
            m.put("sinTelefono", dest.stream().filter(d -> Alertas.normalizarTelefono(d.telefono()).isEmpty()).count());
            tipos.add(m);
        }
        List<Map<String, Object>> usuarios = jdbc.sql("""
                SELECT DISTINCT u.id, u.nombre, u.usuario FROM usuarios u JOIN usuarios_perfiles up ON up.usuario_id = u.id
                WHERE u.activo AND up.perfil_id = ANY(:p) ORDER BY u.nombre LIMIT 500""")
                .param("p", ServicioAlertas.PERFILES_DESTINO.stream().map(p -> p.id()).toArray(Integer[]::new)).query().listOfRows();
        return Map.of("tipos", tipos, "perfiles", ServicioAlertas.nombresPerfiles(), "usuarios", usuarios);
    }

    /** Valida y guarda un tipo del catálogo. @return mensaje, con la advertencia de los destinatarios sin móvil si va por SMS */
    public Map<String, Object> guardar(Edicion e, UsuarioSesion quien) {
        ServicioAlertas.Tipo actual = e == null || e.tipo() == null ? null : alertas.tipo(e.tipo()).orElse(null);
        if (actual == null) {
            throw ErrorNegocio.noEncontrado("El tipo de alerta no existe.");
        }
        String nombre = e.nombre() == null ? actual.nombre() : e.nombre().strip();
        if (nombre.codePointCount(0, nombre.length()) < 3 || nombre.codePointCount(0, nombre.length()) > 80) {
            throw ErrorNegocio.validacion("El nombre de la alerta debe tener de 3 a 80 caracteres.");
        }
        boolean activa = e.activa() == null ? actual.activa() : e.activa();
        boolean sms = e.sms() == null ? actual.sms() : e.sms();
        if (sms && !actual.critica()) {
            throw ErrorNegocio.validacion("Las alertas operativas van solo por correo: el SMS es para las críticas.");
        }
        List<String> perfiles = e.perfiles() == null ? actual.perfiles()
                : new ArrayList<>(new LinkedHashSet<>(e.perfiles().stream().map(p -> p == null ? "" : p.strip().toUpperCase()).toList()));
        List<String> validos = ServicioAlertas.nombresPerfiles();
        for (String p : perfiles) {
            if (!validos.contains(p)) {
                throw ErrorNegocio.validacion("Perfil no válido para recibir alertas: «" + Bitacora.cortar(p, 30) + "». Se admiten: " + String.join(", ", validos) + ".");
            }
        }
        List<Long> usuarios = e.usuarios() == null ? actual.usuarios() : new ArrayList<>(new LinkedHashSet<>(e.usuarios()));
        if (usuarios.size() > MAX_USUARIOS) {
            throw ErrorNegocio.validacion("Máximo " + MAX_USUARIOS + " usuarios por alerta: use los perfiles.");
        }
        if (!usuarios.isEmpty()) {
            long activos = jdbc.sql("SELECT count(*) FROM usuarios WHERE id = ANY(:u) AND activo").param("u", usuarios.toArray(Long[]::new)).query(Long.class).single();
            if (activos != usuarios.size()) {
                throw ErrorNegocio.validacion("Algún usuario elegido no existe o está inactivo.");
            }
            // Como con los perfiles: solo usuarios internos (con algún perfil que puede recibir alertas), nunca uno solo del OPL o del ESM
            long internos = jdbc.sql("""
                    SELECT count(*) FROM usuarios u WHERE u.id = ANY(:u)
                      AND EXISTS (SELECT 1 FROM usuarios_perfiles up WHERE up.usuario_id = u.id AND up.perfil_id = ANY(:p))""")
                    .param("u", usuarios.toArray(Long[]::new))
                    .param("p", ServicioAlertas.PERFILES_DESTINO.stream().map(p -> p.id()).toArray(Integer[]::new)).query(Long.class).single();
            if (internos != usuarios.size()) {
                throw ErrorNegocio.validacion("Algún usuario elegido no tiene un perfil interno que pueda recibir alertas ("
                        + String.join(", ", validos) + ").");
            }
        }
        if (activa && perfiles.isEmpty() && usuarios.isEmpty()) {
            throw ErrorNegocio.validacion("Una alerta activa necesita al menos un destinatario (perfil o usuario).");
        }
        ServicioAlertas.Tipo nuevo = new ServicioAlertas.Tipo(actual.tipo(), nombre, actual.descripcion(), actual.critica(), activa, sms, perfiles, usuarios);
        tx.executeWithoutResult(t -> jdbc.sql("""
                UPDATE alertas_criticas SET nombre = :n, activa = :a, sms = :s, perfiles = :p, usuarios = :u, actualizado = :ahora, actualizado_por = :q
                WHERE tipo = :t""")
                .param("n", nombre).param("a", activa).param("s", sms).param("p", perfiles.toArray(String[]::new)).param("u", usuarios.toArray(Long[]::new))
                .param("ahora", OffsetDateTime.now(reloj)).param("q", quien.id()).param("t", actual.tipo()).update());
        String cambios = Bitacora.cambios(foto(actual), foto(nuevo), Set.of());
        bitacora.registrar("alerta_catalogo_editado", "Alerta " + actual.tipo() + (cambios.isEmpty() ? " (sin cambios)" : ": " + cambios), quien.id(), quien.usuario());
        List<ServicioAlertas.Destinatario> dest = alertas.destinatarios(nuevo);
        long sinMovil = dest.stream().filter(d -> Alertas.normalizarTelefono(d.telefono()).isEmpty()).count();
        String mensaje = "Alerta actualizada: " + dest.size() + " destinatario(s) activos."
                + (activa && sms && sinMovil > 0 ? " " + sinMovil + " no tienen un móvil válido y solo recibirán el correo." : "");
        return Map.of("mensaje", mensaje, "destinatarios", dest.size(), "sinTelefono", sinMovil);
    }

    private static Map<String, Object> foto(ServicioAlertas.Tipo t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nombre", t.nombre());
        m.put("activa", t.activa());
        m.put("sms", t.sms());
        m.put("perfiles", t.perfiles());
        m.put("usuarios", t.usuarios());
        return m;
    }

    /** Eventos recientes con lo que salió y la latencia evento → envío del primer correo y del primer SMS; y el estado de la bandeja. */
    public Map<String, Object> eventos(boolean soloAbiertas, int limite) {
        int n = Math.max(1, Math.min(limite, 500));
        List<Map<String, Object>> eventos = jdbc.sql("""
                SELECT e.id, e.tipo, a.nombre, a.critica, e.clave, e.detalle, e.abierta, e.veces, e.creado, e.ultimo, e.ultimo_aviso, e.ultimo_sms, e.cerrada_en,
                  (SELECT count(*) FROM correos_salida c WHERE c.alerta_id = e.id) AS correos,
                  (SELECT count(*) FROM correos_salida c WHERE c.alerta_id = e.id AND c.estado IN ('enviado', 'parcial')) AS correos_enviados,
                  (SELECT count(*) FROM correos_salida c WHERE c.alerta_id = e.id AND c.estado = 'fallido') AS correos_fallidos,
                  (SELECT round(extract(epoch FROM min(c.enviado) - e.creado) * 1000) FROM correos_salida c WHERE c.alerta_id = e.id) AS latencia_correo_ms,
                  (SELECT count(*) FROM sms_salida s WHERE s.alerta_id = e.id AND s.estado = 'enviado') AS sms_enviados,
                  (SELECT count(*) FROM sms_salida s WHERE s.alerta_id = e.id AND s.estado IN ('pendiente', 'enviando')) AS sms_pendientes,
                  (SELECT count(*) FROM sms_salida s WHERE s.alerta_id = e.id AND s.estado IN ('omitido', 'fallido')) AS sms_sin_enviar,
                  (SELECT round(extract(epoch FROM min(s.enviado) - e.creado) * 1000) FROM sms_salida s WHERE s.alerta_id = e.id) AS latencia_sms_ms
                FROM alertas_eventos e JOIN alertas_criticas a ON a.tipo = e.tipo
                WHERE (NOT :abiertas OR e.abierta) ORDER BY e.creado DESC, e.id DESC LIMIT :n""")
                .param("abiertas", soloAbiertas).param("n", n).query().listOfRows().stream().map(ServicioCatalogoAlertas::legible).toList();
        Map<String, Object> bandeja = jdbc.sql("""
                SELECT count(*) FILTER (WHERE estado = 'pendiente') AS pendientes, count(*) FILTER (WHERE estado = 'enviando') AS enviando,
                  count(*) FILTER (WHERE estado = 'fallido' AND creado >= :desde) AS fallidos_24h,
                  round(percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM enviado - creado) * 1000) FILTER (WHERE enviado IS NOT NULL AND creado >= :desde)) AS latencia_p50_ms,
                  round(percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM enviado - creado) * 1000) FILTER (WHERE enviado IS NOT NULL AND creado >= :desde)) AS latencia_p95_ms
                FROM correos_salida WHERE estado IN ('pendiente', 'enviando') OR creado >= :desde""")
                .param("desde", OffsetDateTime.now(reloj).minusHours(24)).query().singleRow();
        Map<String, Object> bandejaSms = jdbc.sql("""
                SELECT count(*) FILTER (WHERE estado = 'pendiente') AS pendientes, count(*) FILTER (WHERE estado = 'enviando') AS enviando,
                  count(*) FILTER (WHERE estado = 'fallido' AND creado >= :desde) AS fallidos_24h,
                  count(*) FILTER (WHERE estado = 'omitido' AND creado >= :desde) AS omitidos_24h,
                  round(percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM enviado - creado) * 1000) FILTER (WHERE enviado IS NOT NULL AND creado >= :desde)) AS latencia_p50_ms,
                  round(percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM enviado - creado) * 1000) FILTER (WHERE enviado IS NOT NULL AND creado >= :desde)) AS latencia_p95_ms
                FROM sms_salida WHERE estado IN ('pendiente', 'enviando') OR creado >= :desde""")
                .param("desde", OffsetDateTime.now(reloj).minusHours(24)).query().singleRow();
        // bandeja24h son los correos (sin cambiar); bandejaSms24h, lo mismo para los SMS
        return Map.of("eventos", eventos, "bandeja24h", legible(bandeja), "bandejaSms24h", legible(bandejaSms));
    }

    private static Map<String, Object> legible(Map<String, Object> fila) {
        Map<String, Object> m = new LinkedHashMap<>();
        fila.forEach((k, v) -> m.put(camello(k), v instanceof Timestamp t ? t.toInstant().atZone(Fechas.BOGOTA).format(FH)
                : v instanceof OffsetDateTime o ? o.atZoneSameInstant(Fechas.BOGOTA).format(FH) : v instanceof Number x && !(v instanceof Integer) ? x.longValue() : v));
        return m;
    }

    private static String camello(String s) {
        StringBuilder b = new StringBuilder();
        boolean mayus = false;
        for (char c : s.toCharArray()) {
            if (c == '_') {
                mayus = true;
            } else {
                b.append(mayus ? Character.toUpperCase(c) : c);
                mayus = false;
            }
        }
        return b.toString();
    }

    /** Un usuario cierra una alerta abierta. */
    public Map<String, Object> cerrar(long id, UsuarioSesion quien) {
        alertas.cerrarEvento(id, quien);
        return Map.of("mensaje", "Alerta cerrada.");
    }
}
