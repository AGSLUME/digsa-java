package co.com.agsamericas.digsa.opl;

import co.com.agsamericas.digsa.agenda.RepositorioVisitas;
import co.com.agsamericas.digsa.catalogos.ServicioCatalogos;
import co.com.agsamericas.digsa.ejecucion.EnlaceOpl;
import co.com.agsamericas.digsa.ejecucion.ServicioArchivos;
import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.plataforma.PlantillaCorreo;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Fechas;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tarea diaria del flujo del OPL (después de medianoche, sin dos corridas a la vez gracias a un candado de PostgreSQL):
 * <ol>
 * <li>vencida la fase de objeciones, los hallazgos sin respuesta quedan aceptados y se avisa al auditor líder (una vez por acta);</li>
 * <li>vencido el plazo del plan sin firma y con hallazgos vigentes, el plan queda «vencido» y sale el reporte formal a la DIGSA y al
 * líder con consecutivo RD-aaaa-nnnn; si salió, el plan queda «reportado a DIGSA». Si falla, sigue «vencido» y se reintenta al día
 * siguiente. La aplicación nunca inicia la multa.</li>
 * </ol>
 */
@Component
public class TareaDiariaOpl {

    private static final Logger LOG = LoggerFactory.getLogger(TareaDiariaOpl.class);
    /** Llave del candado de la tarea (pg_try_advisory_lock). */
    static final long CANDADO = 0x4f504c5441524541L;
    /** Llave del candado del consecutivo de los reportes a la DIGSA (pg_advisory_xact_lock). */
    static final long CANDADO_CONSECUTIVO = 0x4f504c52445f4e4fL;

    public record Resumen(boolean corrio, int aceptadosAutomaticamente, int planesVencidos, int reportados, int fallidos) {
    }

    private final ServicioOpl opl;
    private final JdbcClient jdbc;
    private final DataSource ds;
    private final RepositorioVisitas visitas;
    private final ServicioCatalogos catalogos;
    private final Correo correo;
    private final RegistroBitacora bitacora;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public TareaDiariaOpl(ServicioOpl opl, JdbcClient jdbc, DataSource ds, RepositorioVisitas visitas, ServicioCatalogos catalogos, Correo correo,
                          RegistroBitacora bitacora, TransactionTemplate tx, ObjectMapper json) {
        this.opl = opl;
        this.jdbc = jdbc;
        this.ds = ds;
        this.visitas = visitas;
        this.catalogos = catalogos;
        this.correo = correo;
        this.bitacora = bitacora;
        this.tx = tx;
        this.json = json;
    }

    @Scheduled(cron = "${digsa.opl.cron:0 15 0 * * *}", zone = "America/Bogota")
    public void programada() {
        Resumen r = correr();
        if (!r.corrio()) {
            LOG.warn("[opl] Tarea diaria no corrió: otra instancia tiene el candado");
        } else if (r.fallidos() > 0) {
            LOG.error("[opl] Tarea diaria con {} reporte(s) o aviso(s) sin enviar: se reintenta mañana", r.fallidos());
        }
    }

    /** Corre la tarea si nadie más la está corriendo. */
    public Resumen correr() {
        try (Connection c = ds.getConnection()) {
            if (!candado(c, "SELECT pg_try_advisory_lock(?)")) {
                return new Resumen(false, 0, 0, 0, 0);
            }
            try {
                return pasos();
            } finally {
                candado(c, "SELECT pg_advisory_unlock(?)");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo tomar el candado de la tarea diaria del OPL", e);
        }
    }

    private static boolean candado(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, CANDADO);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private Resumen pasos() {
        int aceptados = 0;
        int vencidos = 0;
        int reportados = 0;
        int fallidos = 0;
        // Solo lo que aún tiene trabajo: el aviso de aceptación sin enviar o el plan pendiente o vencido
        List<Long> abiertas = jdbc.sql("""
                SELECT a.visita_id FROM opl_accesos a JOIN visitas v ON v.id = a.visita_id LEFT JOIN opl_planes p ON p.acta_id = a.acta_id
                WHERE v.estado = 'cerrada' AND a.vence_objeciones < :ahora
                  AND (p.estado IN ('pendiente', 'vencido')
                       OR NOT EXISTS (SELECT 1 FROM opl_avisos x WHERE x.acta_id = a.acta_id AND x.tipo = 'aceptacion_automatica' AND x.resultado = 'enviado'))
                ORDER BY a.visita_id""").param("ahora", opl.ahora()).query(Long.class).list();
        for (long visitaId : abiertas) {
            ServicioOpl.Acceso a = opl.accesoDeVisita(visitaId).orElse(null);
            if (a == null) {
                continue;
            }
            try {
                int[] ac = aceptacion(a);
                aceptados += ac[0];
                fallidos += ac[1];
                if (opl.ahora().isAfter(a.vencePlan())) {
                    String r = vencimiento(a);
                    if (r != null) {
                        vencidos++;
                        if ("enviado".equals(r)) {
                            reportados++;
                        } else if ("fallido".equals(r)) {
                            fallidos++;
                        }
                    }
                }
            } catch (RuntimeException e) {
                fallidos++;
                LOG.error("[opl] Tarea diaria: visita {}", visitaId, e);
            }
        }
        // Plan incumplido: el máximo de seguimientos quedó en el historial y el reporte no existe o no salió (el evento es en memoria)
        for (Map<String, Object> f : jdbc.sql("""
                SELECT a.visita_id, a.acta_id FROM opl_accesos a JOIN visitas v ON v.id = a.visita_id
                WHERE v.estado = 'cerrada'
                  AND EXISTS (SELECT 1 FROM visitas_historial h WHERE h.visita_id = v.id AND h.evento = 'Máximo de seguimientos' AND h.fecha >= a.enviado)
                  AND NOT EXISTS (SELECT 1 FROM reportes_digsa r WHERE r.acta_id = a.acta_id AND r.motivo = 'plan_incumplido' AND r.estado = 'enviado')
                ORDER BY a.visita_id""").query().listOfRows()) {
            try {
                String r = reportarIncumplido(((Number) f.get("visita_id")).longValue(), ((Number) f.get("acta_id")).longValue());
                if ("enviado".equals(r)) {
                    reportados++;
                } else if (r != null) {
                    fallidos++;
                }
            } catch (RuntimeException e) {
                fallidos++;
                LOG.error("[opl] Tarea diaria: plan incumplido de la visita {}", f.get("visita_id"), e);
            }
        }
        // Avisos de cierre del plan al OPL que no salieron
        for (Map<String, Object> f : jdbc.sql("SELECT visita_id, acta_id FROM opl_avisos WHERE tipo = 'plan_cerrado' AND coalesce(resultado, '') <> 'enviado' ORDER BY visita_id")
                .query().listOfRows()) {
            try {
                ServicioOpl.Acceso a = opl.accesoDeVisita(((Number) f.get("visita_id")).longValue()).orElse(null);
                if (a == null) {
                    continue;
                }
                String r = opl.avisarOpl(a, "Plan de mejoramiento cerrado · visita " + a.numero(), "El plan de mejoramiento de la visita " + a.numero() + " quedó cerrado.");
                jdbc.sql("UPDATE opl_avisos SET resultado = :r, fecha = :ahora WHERE visita_id = :v AND acta_id = :a AND tipo = 'plan_cerrado'")
                        .param("r", r == null ? "enviado" : Bitacora.cortar(r, 500)).param("ahora", opl.ahora()).param("v", f.get("visita_id"))
                        .param("a", f.get("acta_id")).update();
                if (r != null) {
                    fallidos++;
                }
            } catch (RuntimeException e) {
                fallidos++;
                LOG.error("[opl] Tarea diaria: aviso de cierre de la visita {}", f.get("visita_id"), e);
            }
        }
        return new Resumen(true, aceptados, vencidos, reportados, fallidos);
    }

    /**
     * Plan incumplido (Reunión 5 del 2026-10-20): al llegar al máximo de seguimientos con acciones abiertas, reporte formal a la DIGSA y
     * al auditor con consecutivo; si salió, el plan del acta queda «reportado a DIGSA». Una sola vez por acta.
     */
    @EventListener
    public void planIncumplido(co.com.agsamericas.digsa.ejecucion.ServicioSeguimiento.PlanIncumplido e) {
        try {
            reportarIncumplido(e.visitaId(), e.actaId());
        } catch (RuntimeException ex) {
            // La tarea diaria lo retoma (máximo en el historial sin reporte enviado)
            LOG.error("[opl] Reporte de plan incumplido de la visita {}: se reintenta en la tarea diaria", e.visitaId(), ex);
        }
    }

    /** @return «enviado», «fallido» o null si no aplica (acta reemplazada o todo levantado) */
    String reportarIncumplido(long visitaId, long actaId) {
        ServicioOpl.Acceso a = opl.accesoDeVisita(visitaId).orElse(null);
        if (a == null || a.actaId() != actaId) {
            return null;
        }
        List<Map<String, Object>> vigentes = opl.hallazgos(a).stream().filter(h -> !"levantado".equals(h.get("estado"))).toList();
        if (vigentes.isEmpty()) {
            return null;
        }
        Long id = tx.execute(t -> jdbc.sql("SELECT id FROM reportes_digsa WHERE acta_id = :a AND motivo = 'plan_incumplido'").param("a", a.actaId())
                .query(Long.class).optional().orElseGet(() -> crearReporte(a, vigentes, "plan_incumplido")));
        return enviarReporte(a, id);
    }

    /**
     * Paso 1: aceptación automática y aviso al líder, una vez por acta; si el aviso falla, se reintenta al día siguiente. Sin pendientes
     * queda registrado «sin pendientes». @return {aceptados hoy, avisos fallidos}
     */
    private int[] aceptacion(ServicioOpl.Acceso a) {
        Integer n = tx.execute(t -> {
            visitas.bloquear(a.visitaId());
            ServicioOpl.Acceso vigente = opl.accesoDeVisita(a.visitaId()).orElse(null);
            if (vigente == null || vigente.actaId() != a.actaId() || !"cerrada".equals(vigente.estadoVisita())) {
                return null; // reabierta o reenviada entre tanto
            }
            return opl.aplicarAceptacionAutomatica(a);
        });
        if (n == null) {
            return new int[] {0, 0};
        }
        String previo = jdbc.sql("SELECT coalesce(resultado, '') FROM opl_avisos WHERE acta_id = :a AND tipo = 'aceptacion_automatica'").param("a", a.actaId())
                .query(String.class).optional().orElse(null);
        if (previo != null && (previo.equals("enviado") || previo.equals("sin pendientes"))) {
            return new int[] {n, 0};
        }
        jdbc.sql("INSERT INTO opl_avisos (visita_id, acta_id, tipo, resultado) VALUES (:v, :a, 'aceptacion_automatica', 'pendiente') ON CONFLICT DO NOTHING")
                .param("v", a.visitaId()).param("a", a.actaId()).update();
        // Solo las aceptadas automáticamente en esta acta (las de una ronda anterior ya se avisaron con su acta)
        long automaticos = jdbc.sql("""
                SELECT count(*) FROM opl_respuestas r JOIN visitas_hallazgos h ON h.visita_id = r.visita_id AND h.clave = r.clave
                WHERE h.acta_id = :a AND r.automatica AND r.fecha >= :enviado""").param("a", a.actaId()).param("enviado", a.enviado()).query(Long.class).single();
        String r;
        if (automaticos == 0) {
            r = "sin pendientes";
        } else {
            String aviso = opl.avisarLider(a, "Aceptación automática de hallazgos · visita " + a.numero(), automaticos
                    + " hallazgo(s) de la visita " + a.numero() + " quedaron aceptados automáticamente: el operador logístico no los objetó en los "
                    + EnlaceOpl.DIAS_OBJECIONES + " días hábiles.");
            r = aviso == null ? "enviado" : Bitacora.cortar(aviso, 500);
            bitacora.registrar("opl_aceptacion_automatica", "Visita " + a.numero() + ": " + automaticos + " hallazgo(s) aceptados automáticamente", null, "sistema");
        }
        jdbc.sql("UPDATE opl_avisos SET resultado = :r, fecha = :ahora WHERE acta_id = :a AND tipo = 'aceptacion_automatica'").param("r", r)
                .param("ahora", opl.ahora()).param("a", a.actaId()).update();
        return new int[] {n, "enviado".equals(r) || "sin pendientes".equals(r) ? 0 : 1};
    }

    /**
     * Paso 2: plan vencido y reporte a la DIGSA. @return null si no aplica (firmado, cerrado, ya reportado o todo levantado); si no, el
     * estado del reporte («enviado» o «fallido»)
     */
    private String vencimiento(ServicioOpl.Acceso a) {
        List<Map<String, Object>> hs = opl.hallazgos(a);
        List<Map<String, Object>> vigentes = hs.stream().filter(h -> !"levantado".equals(h.get("estado"))).toList();
        if (vigentes.isEmpty()) {
            return null;
        }
        Long reporteId = tx.execute(t -> {
            visitas.bloquear(a.visitaId());
            ServicioOpl.Acceso vigente = opl.accesoDeVisita(a.visitaId()).orElse(null);
            if (vigente == null || vigente.actaId() != a.actaId() || !"cerrada".equals(vigente.estadoVisita())) {
                return null; // reabierta o reenviada entre tanto
            }
            String estado = jdbc.sql("SELECT estado FROM opl_planes WHERE acta_id = :a FOR UPDATE").param("a", a.actaId()).query(String.class).optional()
                    .orElse(null);
            if (estado == null || !("pendiente".equals(estado) || "vencido".equals(estado))) {
                return null;
            }
            if ("pendiente".equals(estado)) {
                jdbc.sql("UPDATE opl_planes SET estado = 'vencido', vencido_en = :ahora WHERE acta_id = :a").param("ahora", opl.ahora()).param("a", a.actaId())
                        .update();
                visitas.historial(a.visitaId(), null, "sistema", "Plan de mejoramiento vencido", "No se firmó en los " + EnlaceOpl.DIAS_PLAN + " días hábiles");
                bitacora.registrar("plan_vencido", "Visita " + a.numero() + ": plan de mejoramiento vencido", null, "sistema");
            }
            return jdbc.sql("SELECT id FROM reportes_digsa WHERE acta_id = :a AND motivo = 'plan_no_presentado'").param("a", a.actaId()).query(Long.class)
                    .optional().orElseGet(() -> crearReporte(a, vigentes, "plan_no_presentado"));
        });
        if (reporteId == null) {
            return null;
        }
        return enviarReporte(a, reporteId);
    }

    /** Motivos del reporte formal y su texto (asunto del correo e historial). */
    static String textoMotivo(String motivo) {
        return "plan_incumplido".equals(motivo) ? "plan de mejoramiento incumplido" : "plan de mejoramiento no presentado";
    }

    /** Dentro de una transacción. El consecutivo se toma con candado propio (la tarea diaria y el plan incumplido no se cruzan) y UNIQUE. */
    private long crearReporte(ServicioOpl.Acceso a, List<Map<String, Object>> vigentes, String motivo) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:k)").param("k", CANDADO_CONSECUTIVO).query().listOfRows();
        int anio = opl.ahora().atZoneSameInstant(Fechas.BOGOTA).getYear();
        int numero = jdbc.sql("SELECT coalesce(max(numero), 0) + 1 FROM reportes_digsa WHERE anio = :y").param("y", anio).query(Integer.class).single();
        String consecutivo = String.format("RD-%04d-%04d", anio, numero);
        ServicioArchivos.Archivo acta = opl.acta(a);
        Map<String, Object> contenido = new LinkedHashMap<>();
        contenido.put("visita", a.numero());
        contenido.put("punto", a.punto());
        contenido.put("actaEnviada", ServicioOpl.local(a.enviado()));
        contenido.put("venceObjeciones", ServicioOpl.local(a.venceObjeciones()));
        contenido.put("vencePlan", ServicioOpl.local(a.vencePlan()));
        contenido.put("actaSha256", acta.sha256());
        List<Map<String, Object>> hs = new ArrayList<>();
        for (Map<String, Object> h : vigentes) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("codigo", h.get("codigo"));
            x.put("texto", h.get("texto"));
            x.put("hallazgo", h.get("hallazgo"));
            x.put("estado", h.get("estado"));
            x.put("justificacion", h.get("justificacion"));
            x.put("soportes", ((List<?>) h.get("soportes")).size());
            x.put("faltaQuienComo", ServicioOpl.vaAlPlan((String) h.get("estado")) && (h.get("quien") == null || h.get("como") == null));
            hs.add(x);
        }
        contenido.put("hallazgos", hs);
        contenido.put("motivo", motivo);
        contenido.put("falto", "plan_incumplido".equals(motivo)
                ? "Se alcanzó el máximo de visitas de seguimiento con acciones del plan de mejoramiento abiertas."
                : "El plan de mejoramiento no se firmó dentro de los " + EnlaceOpl.DIAS_PLAN + " días hábiles.");
        String texto;
        try {
            texto = json.writeValueAsString(contenido);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        long id = jdbc.sql("""
                INSERT INTO reportes_digsa (consecutivo, anio, numero, visita_id, acta_id, motivo, contenido, creado)
                VALUES (:c, :y, :n, :v, :a, :m, CAST(:j AS jsonb), :ahora) RETURNING id""")
                .param("m", motivo).param("c", consecutivo).param("y", anio).param("n", numero).param("v", a.visitaId()).param("a", a.actaId()).param("j", texto)
                .param("ahora", opl.ahora()).query(Long.class).single();
        jdbc.sql("UPDATE opl_planes SET reporte_id = :r WHERE acta_id = :a").param("r", id).param("a", a.actaId()).update();
        return id;
    }

    private String enviarReporte(ServicioOpl.Acceso a, long reporteId) {
        Map<String, Object> r = jdbc.sql("SELECT consecutivo, contenido::text AS contenido, estado, motivo FROM reportes_digsa WHERE id = :id")
                .param("id", reporteId).query().singleRow();
        String consecutivo = (String) r.get("consecutivo");
        String motivo = (String) r.get("motivo");
        // Ya salió (falló lo que vino después): solo se completa el estado del plan, sin reenviarlo
        boolean yaEnviado = "enviado".equals(r.get("estado"));
        List<String> digsa = new ArrayList<>();
        for (String c : catalogos.textoParametro("correo_digsa", "").split("[,;\\s]+")) {
            String n = Correos.normalizar(c);
            if (Correos.valido(n) && !digsa.contains(n)) {
                digsa.add(n);
            }
        }
        LinkedHashSet<String> para = new LinkedHashSet<>(digsa);
        jdbc.sql("SELECT correo FROM usuarios WHERE id = :id AND activo").param("id", a.liderId()).query(String.class).list().stream().map(Correos::normalizar)
                .filter(Correos::valido).forEach(para::add);
        String error;
        if (yaEnviado) {
            error = null;
        } else if (digsa.isEmpty()) {
            // El reporte es a la DIGSA: sin su correo no se da por reportado aunque el líder sí tenga correo
            error = "No hay correo de la DIGSA configurado (parámetro correo_digsa)";
        } else {
            error = enviar(a, consecutivo, motivo, (String) r.get("contenido"), List.copyOf(para), digsa);
        }
        if (error == null) {
            tx.executeWithoutResult(t -> {
                if (!yaEnviado) {
                    jdbc.sql("UPDATE reportes_digsa SET estado = 'enviado', enviado = :ahora, destinatarios = :d, error = NULL WHERE id = :id")
                            .param("ahora", opl.ahora()).param("d", para.toArray(String[]::new)).param("id", reporteId).update();
                }
                jdbc.sql("UPDATE opl_planes SET estado = 'reportado_digsa', reportado_en = :ahora, reporte_id = :r WHERE acta_id = :a AND estado IN (:e)")
                        .param("ahora", opl.ahora()).param("r", reporteId).param("a", a.actaId())
                        .param("e", "plan_incumplido".equals(motivo) ? List.of("firmado", "vencido") : List.of("vencido")).update();
                jdbc.sql("INSERT INTO opl_avisos (visita_id, acta_id, tipo, resultado) VALUES (:v, :a, 'reporte_digsa', :c) ON CONFLICT DO NOTHING")
                        .param("v", a.visitaId()).param("a", a.actaId()).param("c", consecutivo).update();
                visitas.historial(a.visitaId(), null, "sistema", "Reporte a la DIGSA", "Reporte N° " + consecutivo + ": " + textoMotivo(motivo));
            });
            bitacora.registrar("reporte_digsa", "Visita " + a.numero() + ": reporte " + consecutivo + " enviado", null, "sistema");
            return "enviado";
        }
        String e = Bitacora.cortar(Bitacora.sinCorreos(error), 500);
        jdbc.sql("UPDATE reportes_digsa SET estado = 'fallido', error = :e WHERE id = :id").param("e", e).param("id", reporteId).update();
        visitas.historial(a.visitaId(), null, "sistema", "Reporte a la DIGSA fallido", consecutivo + ": " + Bitacora.cortar(e, 250) + ". Se reintenta mañana.");
        bitacora.registrar("reporte_digsa_fallido", "Visita " + a.numero() + ": reporte " + consecutivo + " no salió", null, "sistema");
        return "fallido";
    }

    /** @return el error, o null si salió y lo recibió al menos un correo de la DIGSA (ninguno de ellos rechazado) */
    private String enviar(ServicioOpl.Acceso a, String consecutivo, String motivo, String contenidoJson, List<String> para, List<String> digsa) {
        try {
            Map<?, ?> c = json.readValue(contenidoJson, Map.class);
            StringBuilder filas = new StringBuilder();
            for (Object o : (List<?>) c.get("hallazgos")) {
                Map<?, ?> h = (Map<?, ?>) o;
                filas.append("<tr><td>").append(PlantillaCorreo.e(String.valueOf(h.get("codigo")))).append("</td><td>")
                        .append(PlantillaCorreo.e(String.valueOf(h.get("texto")))).append("</td><td>").append(PlantillaCorreo.e(String.valueOf(h.get("estado"))))
                        .append("</td><td>").append(h.get("soportes")).append("</td></tr>");
            }
            String html = PlantillaCorreo.marco("Reporte N° " + consecutivo + " · " + textoMotivo(motivo),
                    "<p>" + PlantillaCorreo.e(String.valueOf(c.get("falto"))) + " Visita <strong>" + PlantillaCorreo.e(a.numero()) + "</strong> ("
                            + PlantillaCorreo.e(a.punto()) + ").</p>"
                            + "<p>Acta enviada: " + PlantillaCorreo.e(String.valueOf(c.get("actaEnviada"))) + " · plazo para objetar: "
                            + PlantillaCorreo.e(String.valueOf(c.get("venceObjeciones"))) + " · plazo del plan: " + PlantillaCorreo.e(String.valueOf(c.get("vencePlan")))
                            + "</p><table border=\"1\" cellpadding=\"4\" style=\"border-collapse:collapse\"><tr><th>Hallazgo</th><th>Descripción</th><th>Estado</th>"
                            + "<th>Soportes del OPL</th></tr>" + filas + "</table>"
                            + "<p style=\"color:#6b7280;font-size:11px\">Huella SHA-256 del acta adjunta: " + PlantillaCorreo.e(String.valueOf(c.get("actaSha256")))
                            + "</p><p>Este reporte es informativo para la DIGSA; la aplicación no inicia ninguna multa.</p>");
            ServicioArchivos.Archivo acta = opl.acta(a);
            byte[] pdf;
            try (InputStream in = opl.abrir(acta)) {
                pdf = in.readAllBytes();
            }
            Correo.Resultado r = correo.enviar(new Correo.Mensaje(para, List.of(), "Reporte N° " + consecutivo + " · " + textoMotivo(motivo) + " · visita "
                    + a.numero(), html, List.of(new Correo.Adjunto(acta.nombre(), "application/pdf", pdf))));
            if (!r.ok()) {
                return String.valueOf(r.error());
            }
            long rechazadosDigsa = digsa.stream().filter(d -> r.rechazados() != null && r.rechazados().contains(d)).count();
            return rechazadosDigsa > 0 ? rechazadosDigsa + " correo(s) de la DIGSA rechazado(s)" : null;
        } catch (Exception e) {
            LOG.error("[opl] Reporte {} de la visita {}", consecutivo, a.visitaId(), e);
            return "No se pudo armar o enviar el reporte";
        }
    }
}
