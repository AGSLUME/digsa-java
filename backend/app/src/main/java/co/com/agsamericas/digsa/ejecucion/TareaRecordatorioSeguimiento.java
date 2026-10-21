package co.com.agsamericas.digsa.ejecucion;

import co.com.agsamericas.digsa.agenda.RepositorioVisitas;
import co.com.agsamericas.digsa.agenda.ServicioConsultaAgenda;
import co.com.agsamericas.digsa.catalogos.ServicioCatalogos;
import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.plataforma.PlantillaCorreo;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.EstadosVisita;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Plazos;
import co.com.agsamericas.digsa.reglas.Seguimiento;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recordatorio diario al auditor líder de cada seguimiento por ejecutar cuya fecha esté entre hoy y hoy + {@code
 * recordatorio_seguimiento_habiles} días hábiles (duda 6.9), una sola vez por visita: la marca es «Recordatorio al auditor» en el
 * historial. Si falla queda «Recordatorio al auditor fallido» y se reintenta al día siguiente.
 */
@Component
public class TareaRecordatorioSeguimiento {

    private static final Logger LOG = LoggerFactory.getLogger(TareaRecordatorioSeguimiento.class);
    static final int HABILES_DEFECTO = 5;
    static final String MARCA = "Recordatorio al auditor";

    public record Resumen(int enviados, int fallidos) {
    }

    private final JdbcClient jdbc;
    private final RepositorioVisitas visitas;
    private final ServicioConsultaAgenda consulta;
    private final ServicioCatalogos catalogos;
    private final Correo correo;
    private final Clock reloj;
    private final javax.sql.DataSource ds;

    public TareaRecordatorioSeguimiento(JdbcClient jdbc, RepositorioVisitas visitas, ServicioConsultaAgenda consulta, ServicioCatalogos catalogos,
                                        Correo correo, Clock reloj, javax.sql.DataSource ds) {
        this.ds = ds;
        this.jdbc = jdbc;
        this.visitas = visitas;
        this.consulta = consulta;
        this.catalogos = catalogos;
        this.correo = correo;
        this.reloj = reloj;
    }

    @Scheduled(cron = "${digsa.seguimiento.cron:0 30 6 * * *}", zone = "America/Bogota")
    public void programada() {
        Resumen r = correr();
        if (r.fallidos() > 0) {
            LOG.error("[seguimiento] {} recordatorio(s) al auditor sin enviar: se reintentan mañana", r.fallidos());
        }
    }

    int habiles() {
        try {
            int n = Integer.parseInt(catalogos.textoParametro("recordatorio_seguimiento_habiles", String.valueOf(HABILES_DEFECTO)).strip());
            return n >= 0 ? n : HABILES_DEFECTO;
        } catch (NumberFormatException e) {
            return HABILES_DEFECTO;
        }
    }

    /** Llave del candado del recordatorio (pg_try_advisory_lock): dos réplicas o una corrida a mano no lo envían dos veces. */
    static final long CANDADO = 0x5345475245434f52L;

    /** Un recordatorio que ya salió no se repite: la marca vive en el historial de la visita. */
    public Resumen correr() {
        try (java.sql.Connection c = ds.getConnection()) {
            if (!candado(c, "SELECT pg_try_advisory_lock(?)")) {
                LOG.warn("[seguimiento] Recordatorio no corrió: otra instancia tiene el candado");
                return new Resumen(0, 0);
            }
            try {
                return pasos();
            } finally {
                candado(c, "SELECT pg_advisory_unlock(?)");
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("No se pudo tomar el candado del recordatorio", e);
        }
    }

    private static boolean candado(java.sql.Connection c, String sql) throws java.sql.SQLException {
        try (java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, CANDADO);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private Resumen pasos() {
        LocalDate hoy = LocalDate.now(reloj.withZone(Fechas.BOGOTA));
        LocalDate hasta = consulta.plazos().sumarHabiles(hoy, habiles());
        List<Long> ids = jdbc.sql("""
                SELECT v.id FROM visitas v WHERE v.origen_id IS NOT NULL AND v.estado = ANY(:e) AND v.fecha BETWEEN :hoy AND :hasta
                  AND NOT EXISTS (SELECT 1 FROM visitas_historial h WHERE h.visita_id = v.id AND h.evento = :marca)
                ORDER BY v.fecha, v.id""").param("e", EstadosVisita.POR_EJECUTAR.toArray(String[]::new)).param("hoy", hoy).param("hasta", hasta)
                .param("marca", MARCA).query(Long.class).list();
        int enviados = 0;
        int fallidos = 0;
        for (long id : ids) {
            if (recordar(id)) {
                enviados++;
            } else {
                fallidos++;
            }
        }
        return new Resumen(enviados, fallidos);
    }

    private boolean recordar(long id) {
        RepositorioVisitas.Visita v = visitas.porId(id).orElse(null);
        if (v == null) {
            return true;
        }
        RepositorioVisitas.Visita o = visitas.porId(v.origenId()).orElseThrow();
        List<String> para = jdbc.sql("SELECT correo FROM usuarios WHERE id = :id AND activo").param("id", v.auditorId()).query(String.class).list().stream()
                .map(Correos::normalizar).filter(Correos::valido).toList();
        if (para.isEmpty()) {
            visitas.historial(id, null, "sistema", MARCA + " fallido", "El auditor líder no tiene correo");
            return false;
        }
        StringBuilder filas = new StringBuilder();
        for (Map<String, Object> a : abiertas(v)) {
            filas.append("<li>").append(PlantillaCorreo.e(String.valueOf(a.get("codigo")))).append(": ").append(PlantillaCorreo.e(String.valueOf(a.get("texto"))))
                    .append(a.get("fin") == null ? "" : " (fin del plan " + PlantillaCorreo.e(String.valueOf(a.get("fin"))) + ")").append("</li>");
        }
        String titulo = Seguimiento.ordinal(v.seguimientoN()) + " visita de seguimiento · " + v.numero();
        String html = PlantillaCorreo.marco(titulo, "<p>Le recordamos la " + PlantillaCorreo.e(Seguimiento.ordinal(v.seguimientoN()).toLowerCase())
                + " visita de seguimiento al plan de mejoramiento de la visita " + PlantillaCorreo.e(o.numero()) + ": <strong>" + PlantillaCorreo.e(v.numero())
                + "</strong>, el " + PlantillaCorreo.e(Plazos.fechaLarga(v.fecha())) + (v.hora() == null ? "" : " a las " + v.hora()) + " en "
                + PlantillaCorreo.e(v.puntoNombre()) + ".</p><p>Acciones abiertas por verificar:</p><ul>" + filas + "</ul>");
        try {
            Correo.Resultado r = correo.enviar(new Correo.Mensaje(para, List.of(), "Recordatorio: " + titulo, html, List.of()));
            if (r.ok()) {
                visitas.historial(id, null, "sistema", MARCA, "Seguimiento del " + v.fecha());
                return true;
            }
            visitas.historial(id, null, "sistema", MARCA + " fallido", Bitacora.cortar(Bitacora.sinCorreos(String.valueOf(r.error())), 300));
        } catch (RuntimeException e) {
            LOG.error("[seguimiento] Recordatorio de la visita {}", id, e);
            visitas.historial(id, null, "sistema", MARCA + " fallido", "Error al enviar el correo");
        }
        return false;
    }

    /** Acciones abiertas del plan del seguimiento (las que verifica). */
    private List<Map<String, Object>> abiertas(RepositorioVisitas.Visita v) {
        return jdbc.sql("""
                SELECT p.clave, coalesce(h.codigo, substr(p.clave, 4)) AS codigo, coalesce(h.texto, p.que) AS texto, p.fin FROM visitas_plan p
                LEFT JOIN visitas_hallazgos h ON h.visita_id = :o AND h.clave = p.clave
                  AND h.acta_id = (SELECT id FROM visitas_actas WHERE visita_id = :o AND estado IN ('enviada', 'parcial') ORDER BY ronda DESC LIMIT 1)
                WHERE p.visita_id = :v AND p.estado NOT IN ('Completado', 'Cancelado') ORDER BY p.clave""")
                .param("v", v.id()).param("o", v.origenId()).query().listOfRows();
    }
}
