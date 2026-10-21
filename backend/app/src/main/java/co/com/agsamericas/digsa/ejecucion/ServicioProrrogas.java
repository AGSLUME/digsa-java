package co.com.agsamericas.digsa.ejecucion;

import co.com.agsamericas.digsa.agenda.RepositorioVisitas;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Perfil;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Prórrogas de las acciones del plan (decisión 12.1 del 2026-10-20, con la resolución recomendada): al enviar el acta del seguimiento
 * quedan «solicitadas»; pasan a «aprobadas» cuando las aprueban el coordinador y la DIGSA, cada uno con su registro en la bitácora;
 * cualquiera de los dos puede rechazarlas con motivo. Es la única escritura del perfil DIGSA (decisión 20: «solo ven»), duda 22.3.
 */
@Service
public class ServicioProrrogas {

    public record Decision(String decision, String motivo) {
    }

    private final JdbcClient jdbc;
    private final RepositorioVisitas visitas;
    private final RegistroBitacora bitacora;
    private final TransactionTemplate tx;
    private final Clock reloj;

    public ServicioProrrogas(JdbcClient jdbc, RepositorioVisitas visitas, RegistroBitacora bitacora, TransactionTemplate tx, Clock reloj) {
        this.jdbc = jdbc;
        this.visitas = visitas;
        this.bitacora = bitacora;
        this.tx = tx;
        this.reloj = reloj;
    }

    /** Lado de quien decide: el coordinador o la DIGSA, por su perfil propio (el administrador no decide a nombre de ninguno). */
    static String lado(Set<Perfil> perfiles) {
        boolean coord = perfiles.contains(Perfil.COORDINADOR);
        boolean digsa = perfiles.contains(Perfil.DIGSA);
        if (coord && digsa) {
            throw new ErrorNegocio(HttpStatus.FORBIDDEN, "Con los perfiles Coordinador y DIGSA a la vez no se deciden prórrogas: cada lado lo decide una persona distinta.");
        }
        if (coord) {
            return "coord";
        }
        if (digsa) {
            return "digsa";
        }
        throw new ErrorNegocio(HttpStatus.FORBIDDEN, "Solo el coordinador y la DIGSA deciden las prórrogas.");
    }

    /** Las dos sentencias fijas (sin armar SQL con el lado). */
    private static final Map<String, String> ACTUALIZAR = Map.of(
            "coord", """
                    UPDATE prorrogas SET coord_decision = :d, coord_usuario_id = :u, coord_fecha = :ahora,
                      estado = CASE WHEN :d = 'rechaza' THEN 'rechazada' WHEN digsa_decision = 'aprueba' THEN 'aprobada' ELSE 'solicitada' END,
                      motivo_rechazo = coalesce(:m, motivo_rechazo)
                    WHERE id = :id AND estado = 'solicitada' AND coord_decision IS NULL AND (digsa_usuario_id IS NULL OR digsa_usuario_id <> :u)
                    RETURNING estado""",
            "digsa", """
                    UPDATE prorrogas SET digsa_decision = :d, digsa_usuario_id = :u, digsa_fecha = :ahora,
                      estado = CASE WHEN :d = 'rechaza' THEN 'rechazada' WHEN coord_decision = 'aprueba' THEN 'aprobada' ELSE 'solicitada' END,
                      motivo_rechazo = coalesce(:m, motivo_rechazo)
                    WHERE id = :id AND estado = 'solicitada' AND digsa_decision IS NULL AND (coord_usuario_id IS NULL OR coord_usuario_id <> :u)
                    RETURNING estado""");

    /** La consultan el coordinador, la DIGSA y el administrador (este solo consulta: no decide a nombre de ninguno). */
    public List<Map<String, Object>> listar(String estado, Set<Perfil> perfiles, UsuarioSesion quien) {
        String lado = null;
        if (!perfiles.contains(Perfil.ADMINISTRADOR) || perfiles.contains(Perfil.COORDINADOR) || perfiles.contains(Perfil.DIGSA)) {
            try {
                lado = lado(perfiles);
            } catch (ErrorNegocio e) {
                if (!perfiles.contains(Perfil.ADMINISTRADOR)) {
                    throw e;
                }
            }
        }
        String miLado = lado;
        String e = estado == null || estado.isBlank() ? "solicitada" : estado.strip();
        if (!Set.of("solicitada", "aprobada", "rechazada", "todas").contains(e)) {
            throw ErrorNegocio.validacion("El estado debe ser solicitada, aprobada, rechazada o todas.");
        }
        return jdbc.sql("""
                SELECT r.id, r.estado, r.codigo, r.clave, r.fin_anterior AS "finAnterior", r.fin_nuevo AS "finNuevo", r.justificacion,
                       r.coord_decision AS "coordDecision", uc.nombre AS "coordNombre", r.coord_fecha AS "coordFecha",
                       r.digsa_decision AS "digsaDecision", ud.nombre AS "digsaNombre", r.digsa_fecha AS "digsaFecha", r.motivo_rechazo AS "motivoRechazo",
                       v.id AS "visitaId", v.numero, o.numero AS "origenNumero", p.nombre AS punto, v.fecha AS "fechaSeguimiento", r.creado,
                       v.auditor_id, r.coord_usuario_id, r.digsa_usuario_id
                FROM prorrogas r JOIN visitas v ON v.id = r.visita_id JOIN visitas o ON o.id = r.origen_visita_id JOIN puntos p ON p.id = v.punto_id
                LEFT JOIN usuarios uc ON uc.id = r.coord_usuario_id LEFT JOIN usuarios ud ON ud.id = r.digsa_usuario_id
                WHERE (:e = 'todas' OR r.estado = :e) ORDER BY r.creado DESC, r.id DESC LIMIT 500""").param("e", e).query().listOfRows().stream()
                .map(m -> {
                    Map<String, Object> x = new java.util.LinkedHashMap<>(m);
                    x.replaceAll((k, val) -> val instanceof java.sql.Date d ? d.toString() : val);
                    // El servidor dice si este usuario puede decidir (perfil propio, su lado sin decidir, no es quien la pidió ni decidió el otro lado)
                    x.put("puedeDecidir", puede(miLado, x, quien.id()));
                    x.remove("auditor_id");
                    x.remove("coord_usuario_id");
                    x.remove("digsa_usuario_id");
                    return (Map<String, Object>) x;
                }).toList();
    }

    private static boolean puede(String lado, Map<String, Object> r, long usuario) {
        if (lado == null || !"solicitada".equals(r.get("estado")) || r.get(lado + "Decision") != null) {
            return false;
        }
        if ("coord".equals(lado) && ((Number) r.get("auditor_id")).longValue() == usuario) {
            return false;
        }
        Object otro = r.get(("coord".equals(lado) ? "digsa" : "coord") + "_usuario_id");
        return otro == null || ((Number) otro).longValue() != usuario;
    }

    public String decidir(long id, Decision d, UsuarioSesion quien, Set<Perfil> perfiles) {
        String lado = lado(perfiles);
        String decision = d.decision() == null ? "" : d.decision().strip();
        if (!Set.of("aprueba", "rechaza").contains(decision)) {
            throw ErrorNegocio.validacion("La decisión debe ser aprobar o rechazar la prórroga.");
        }
        String motivo = d.motivo() == null ? "" : d.motivo().strip();
        if ("rechaza".equals(decision) && (motivo.length() < 10 || motivo.length() > 800)) {
            throw ErrorNegocio.validacion("Escriba el motivo del rechazo (de 10 a 800 caracteres).");
        }
        String[] resultado = tx.execute(t -> {
            long visitaId = jdbc.sql("SELECT visita_id FROM prorrogas WHERE id = :id").param("id", id).query(Long.class).optional()
                    .orElseThrow(() -> ErrorNegocio.noEncontrado("La prórroga no existe."));
            RepositorioVisitas.Visita seg = visitas.bloquear(visitaId).orElseThrow();
            // Con la visita bloqueada se lee la fila (y se bloquea): dos decisiones a la vez no dejan la prórroga a medias
            Map<String, Object> r = jdbc.sql("SELECT codigo, estado, coord_decision, digsa_decision, coord_usuario_id, digsa_usuario_id FROM prorrogas WHERE id = :id FOR UPDATE")
                    .param("id", id).query().singleRow();
            if ("coord".equals(lado) && seg.auditorId() == quien.id()) {
                throw ErrorNegocio.validacion("Quien pidió la prórroga (el auditor líder del seguimiento) no la aprueba a nombre de la coordinación.");
            }
            Object otroUsuario = r.get(("coord".equals(lado) ? "digsa" : "coord") + "_usuario_id");
            if (otroUsuario != null && ((Number) otroUsuario).longValue() == quien.id()) {
                throw ErrorNegocio.validacion("Ya decidió esta prórroga por el otro lado: la decide otra persona.");
            }
            if (!"solicitada".equals(r.get("estado"))) {
                throw ErrorNegocio.validacion("La prórroga ya está " + r.get("estado") + ".");
            }
            if (r.get(lado + "_decision") != null) {
                throw ErrorNegocio.validacion(("coord".equals(lado) ? "La coordinación" : "La DIGSA") + " ya decidió esta prórroga.");
            }
            String nuevoEstado = jdbc.sql(ACTUALIZAR.get(lado)).param("d", decision).param("u", quien.id()).param("ahora", OffsetDateTime.now(reloj))
                    .param("m", "rechaza".equals(decision) ? motivo : null).param("id", id).query(String.class).optional()
                    .orElseThrow(() -> ErrorNegocio.conflicto("Otro usuario decidió la prórroga al mismo tiempo. Recargue."));
            String quienDecide = "coord".equals(lado) ? "la coordinación" : "la DIGSA";
            // El motivo del rechazo queda en la prórroga, no se copia al historial (Ley 1581)
            visitas.historial(visitaId, quien.id(), quien.usuario(), "rechaza".equals(decision) ? "Prórroga rechazada por " + quienDecide : "Prórroga aprobada por " + quienDecide,
                    r.get("codigo") + ("aprobada".equals(nuevoEstado) ? " (aprobada por la coordinación y la DIGSA)" : ""));
            return new String[] {(String) r.get("codigo"), nuevoEstado};
        });
        String tipo = "rechaza".equals(decision) ? "prorroga_rechazada" : "coord".equals(lado) ? "prorroga_aprobada_coordinador" : "prorroga_aprobada_digsa";
        bitacora.registrar(tipo, "Prórroga " + id + " de " + resultado[0] + ": " + decision + " (" + lado + ")", quien.id(), quien.usuario());
        return switch (resultado[1]) {
            case "rechazada" -> "Prórroga rechazada. Si cambia la fecha del seguimiento, el coordinador lo reprograma.";
            case "aprobada" -> "Prórroga aprobada por la coordinación y la DIGSA.";
            default -> "Aprobación registrada: falta la de " + ("coord".equals(lado) ? "la DIGSA" : "la coordinación") + ".";
        };
    }
}
