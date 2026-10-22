package co.com.agsamericas.digsa.tablero;

import co.com.agsamericas.digsa.agenda.ServicioConsultaAgenda;
import co.com.agsamericas.digsa.catalogos.ServicioCatalogos;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.EstadosVisita;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Plazos;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dashboard de visitas (docs/especificaciones/riesgo-tablero): indicadores y gráficas contados en SQL sobre las visitas del filtro y del
 * alcance, alertas críticas (plazos de la agenda y planes del OPL vencidos, novena ronda 3.2), línea base de los ESM de dispensación y
 * resultados de las visitas cerradas. Un filtro con formato inválido se ignora.
 */
@Service
public class ServicioDashboard {

    static final List<String> UNIDADES = List.of("ESM", "FA", "NC");
    static final Set<String> ABIERTAS = Set.of("programada", "notificada", "reprogramada", "ejecucion");

    public record Filtros(String desde, String hasta, String fuerza, String tipo, Long auditorId, String unidad) {
    }

    private final JdbcClient jdbc;
    private final ServicioConsultaAgenda consulta;
    private final ServicioCatalogos catalogos;
    private final Clock reloj;

    public ServicioDashboard(JdbcClient jdbc, ServicioConsultaAgenda consulta, ServicioCatalogos catalogos, Clock reloj) {
        this.jdbc = jdbc;
        this.consulta = consulta;
        this.catalogos = catalogos;
        this.reloj = reloj;
    }

    LocalDate hoy() {
        return LocalDate.now(reloj.withZone(Fechas.BOGOTA));
    }

    /** WHERE de las visitas del filtro dentro del alcance; los valores inválidos se ignoran. */
    record Where(String sql, Map<String, Object> params, Map<String, String> aplicados) {
    }

    Where where(Filtros f, Alcance alcance) {
        ServicioConsultaAgenda.Condicion c = ServicioConsultaAgenda.condicion(alcance);
        StringBuilder w = new StringBuilder(" WHERE (" + c.sql() + ")");
        Map<String, Object> p = new LinkedHashMap<>(c.params());
        Map<String, String> aplicados = new LinkedHashMap<>();
        Fechas.valida(f.desde()).ifPresent(d -> {
            w.append(" AND v.fecha >= :desde");
            p.put("desde", d);
            aplicados.put("Desde", d.toString());
        });
        Fechas.valida(f.hasta()).ifPresent(d -> {
            w.append(" AND v.fecha <= :hasta");
            p.put("hasta", d);
            aplicados.put("Hasta", d.toString());
        });
        if (f.fuerza() != null && f.fuerza().matches("^[A-Z]{2,6}$") && !"NA".equals(f.fuerza())) {
            w.append(" AND p.fuerza = :fuerza");
            p.put("fuerza", f.fuerza());
            aplicados.put("Fuerza", f.fuerza());
        }
        if (f.tipo() != null && f.tipo().matches("^[a-z_]{2,30}$")) {
            w.append(" AND v.tipo = :tipo");
            p.put("tipo", f.tipo());
            aplicados.put("Tipo", f.tipo());
        }
        // El filtro por auditor es de la coordinación: los perfiles de consulta no cuentan visitas por auditor
        if (f.auditorId() != null && f.auditorId() > 0 && alcance instanceof Alcance.Todo) {
            w.append(" AND EXISTS (SELECT 1 FROM visitas_auditores x WHERE x.visita_id = v.id AND x.usuario_id = :auditor)");
            p.put("auditor", f.auditorId());
            aplicados.put("Auditor", String.valueOf(f.auditorId()));
        }
        if (f.unidad() != null && UNIDADES.contains(f.unidad())) {
            w.append(" AND p.unidad = :unidad");
            p.put("unidad", f.unidad());
            aplicados.put("Unidad", f.unidad());
        }
        return new Where(w.toString(), p, aplicados);
    }

    private static final String DESDE = " FROM visitas v JOIN puntos p ON p.id = v.punto_id ";

    @Transactional(readOnly = true)
    public Map<String, Object> datos(Filtros f, Alcance alcance) {
        ServicioRiesgo.exigirConsulta(alcance);
        Where w = where(f, alcance);
        Map<String, Object> r = new LinkedHashMap<>();
        // Totales por estado (las canceladas cuentan)
        Map<String, Long> porEstado = new LinkedHashMap<>();
        EstadosVisita.NOMBRES.keySet().forEach(e -> porEstado.put(e, 0L));
        jdbc.sql("SELECT v.estado, count(*)" + DESDE + w.sql() + " GROUP BY v.estado").params(w.params())
                .query((rs, n) -> porEstado.put(rs.getString(1), rs.getLong(2))).list();
        long total = porEstado.values().stream().mapToLong(Long::longValue).sum();
        long porEjecutar = porEstado.getOrDefault("programada", 0L) + porEstado.getOrDefault("notificada", 0L) + porEstado.getOrDefault("reprogramada", 0L);
        long planesVencidos = jdbc.sql("""
                SELECT count(*)""" + DESDE + w.sql() + " " + """
                 AND v.estado = 'cerrada' AND EXISTS (SELECT 1 FROM opl_accesos a JOIN opl_planes pl ON pl.acta_id = a.acta_id
                   WHERE a.visita_id = v.id AND pl.estado IN ('vencido', 'reportado_digsa'))
                 AND NOT EXISTS (SELECT 1 FROM visitas s WHERE s.origen_id = v.id AND s.estado = 'cerrada')""").params(w.params()).query(Long.class).single();
        long criticasPlazo = criticasDePlazo(w);
        Map<String, Object> ind = new LinkedHashMap<>();
        ind.put("total", total);
        ind.put("porEjecutar", porEjecutar);
        ind.put("programadas", porEstado.getOrDefault("programada", 0L));
        ind.put("notificadas", porEstado.getOrDefault("notificada", 0L));
        ind.put("reprogramadas", porEstado.getOrDefault("reprogramada", 0L));
        ind.put("ejecucion", porEstado.getOrDefault("ejecucion", 0L));
        ind.put("cerradas", porEstado.getOrDefault("cerrada", 0L));
        ind.put("canceladas", porEstado.getOrDefault("cancelada", 0L));
        ind.put("criticas", criticasPlazo + planesVencidos);
        ind.put("planesOplVencidos", planesVencidos);
        r.put("indicadores", ind);
        r.put("porEstado", grafica(porEstado, EstadosVisita.NOMBRES));
        r.put("porTipo", jdbc.sql("SELECT t.nombre AS etiqueta, count(*) AS valor" + DESDE + " JOIN par_tipos_visita t ON t.codigo = v.tipo" + w.sql()
                + " GROUP BY t.nombre, t.orden ORDER BY t.orden").params(w.params()).query().listOfRows());
        r.put("porFuerza", jdbc.sql("SELECT p.fuerza AS etiqueta, count(*) AS valor" + DESDE + w.sql() + " AND p.fuerza <> 'NA' GROUP BY p.fuerza ORDER BY p.fuerza")
                .params(w.params()).query().listOfRows());
        Map<String, Long> unidades = new LinkedHashMap<>();
        UNIDADES.forEach(u -> unidades.put(u, 0L));
        jdbc.sql("SELECT p.unidad, count(*)" + DESDE + w.sql() + " GROUP BY p.unidad").params(w.params()).query((rs, n) -> unidades.put(rs.getString(1), rs.getLong(2)))
                .list();
        r.put("porUnidad", grafica(unidades, Map.of()));
        // Visitas por auditor (solo el líder): la DIGSA y la coordinación ven los auditores (Agentes 5); Sanidad y ESM no (Ley 1581)
        if (alcance instanceof Alcance.Todo) {
            r.put("porAuditor", jdbc.sql("SELECT u.nombre AS etiqueta, count(*) AS valor" + DESDE + " JOIN usuarios u ON u.id = v.auditor_id" + w.sql()
                    + " GROUP BY u.nombre ORDER BY count(*) DESC, u.nombre LIMIT 50").params(w.params()).query().listOfRows());
        }
        r.put("lineaBase", lineaBase(alcance));
        r.put("resultados", resultados(w));
        r.put("filtros", w.aplicados());
        return r;
    }

    private static List<Map<String, Object>> grafica(Map<String, Long> valores, Map<String, String> nombres) {
        List<Map<String, Object>> l = new ArrayList<>();
        valores.forEach((k, v) -> l.add(Map.of("etiqueta", nombres.getOrDefault(k, k), "valor", v)));
        return l;
    }

    /** Visitas abiertas del filtro con alerta crítica de plazos, calculada hoy con la regla de la agenda. */
    private long criticasDePlazo(Where w) {
        Plazos plazos = consulta.plazos();
        LocalDate hoy = hoy();
        return jdbc.sql("""
                SELECT v.fecha, v.estado, v.fecha_solicitud, v.tipo, t.aviso, p.nombre, p.unidad, p.modalidad, p.exige_qf, u.nombre AS lider, v.equipo_sin_notificar,
                       EXISTS (SELECT 1 FROM visitas_auditores x JOIN auditores a ON a.usuario_id = x.usuario_id
                               WHERE x.visita_id = v.id AND a.rol = 'quimico_farmaceutico') AS tiene_qf""" + DESDE
                + " JOIN par_tipos_visita t ON t.codigo = v.tipo JOIN usuarios u ON u.id = v.auditor_id" + w.sql() + " AND v.estado = ANY(:abiertas)")
                .params(w.params()).param("abiertas", ABIERTAS.toArray(String[]::new)).query((rs, n) -> {
                    java.sql.Date fs = rs.getDate("fecha_solicitud");
                    List<Plazos.Alerta> a = plazos.alertas(new Plazos.Visita(rs.getDate("fecha").toLocalDate(), rs.getString("estado"), fs == null ? null : fs.toLocalDate()),
                            new Plazos.Tipo(rs.getString("tipo"), rs.getString("aviso"), null, null),
                            new Plazos.Punto(rs.getString("nombre"), rs.getString("unidad"), rs.getString("modalidad"), rs.getBoolean("exige_qf")),
                            new Plazos.Equipo(rs.getString("lider"), rs.getBoolean("tiene_qf")), hoy);
                    return "bad".equals(Plazos.nivel(a)) || rs.getBoolean("equipo_sin_notificar");
                }).list().stream().filter(Boolean::booleanValue).count();
    }

    /** Línea base de los ESM de dispensación del alcance, sin los filtros del dashboard (Anexo 3 p.12). */
    private Map<String, Object> lineaBase(Alcance alcance) {
        Map.Entry<String, Map<String, Object>> c = ServicioRiesgo.alcancePuntos(alcance);
        Map<String, Object> r = new LinkedHashMap<>(jdbc.sql("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM visitas v WHERE v.punto_id = p.id AND v.tipo = 'inicial' AND v.estado = 'cerrada')) AS cerradas,
                       count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM visitas v WHERE v.punto_id = p.id AND v.tipo = 'inicial' AND v.estado = 'cerrada')
                         AND EXISTS (SELECT 1 FROM visitas v WHERE v.punto_id = p.id AND v.tipo = 'inicial' AND v.estado = ANY(:abiertas))) AS en_curso
                FROM puntos p WHERE p.activo AND p.unidad = 'ESM' AND p.modalidad = 'Dispensación' AND (""" + c.getKey() + ")").params(c.getValue())
                .param("abiertas", ABIERTAS.toArray(String[]::new)).query().singleRow());
        long total = ((Number) r.get("total")).longValue();
        long cerradas = ((Number) r.get("cerradas")).longValue();
        long enCurso = ((Number) r.get("en_curso")).longValue();
        r.remove("en_curso");
        r.put("enCurso", enCurso);
        r.put("sinProgramar", Math.max(0, total - cerradas - enCurso));
        String inicio = catalogos.textoParametro("inicio_contrato", "");
        r.put("plazo", Fechas.valida(inicio.strip()).map(d -> d.plusMonths(entero(catalogos.textoParametro("inicial_meses", "3"), 3)).toString()).orElse(null));
        return r;
    }

    private static int entero(String s, int defecto) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return defecto;
        }
    }

    /** Resultados del resumen guardado de las visitas cerradas del filtro (una reabierta sale hasta cerrarla de nuevo). */
    private Map<String, Object> resultados(Where w) {
        Map<String, Object> r = new LinkedHashMap<>(jdbc.sql("""
                SELECT coalesce(sum(rs.c), 0) AS c, coalesce(sum(rs.nc), 0) AS nc, coalesce(sum(rs.na), 0) AS na, coalesce(sum(rs.hallazgos), 0) AS hallazgos""" + DESDE
                + " LEFT JOIN visitas_resumen rs ON rs.visita_id = v.id" + w.sql() + " AND v.estado = 'cerrada'").params(w.params()).query().singleRow());
        r.put("inmediata", jdbc.sql("SELECT count(*)" + DESDE + " JOIN visitas_hallazgos h ON h.visita_id = v.id" + w.sql()
                + " AND v.estado = 'cerrada' AND h.inmediata AND h.acta_id = (SELECT max(a.id) FROM visitas_actas a WHERE a.visita_id = v.id AND a.estado IN ('enviada', 'parcial'))")
                .params(w.params()).query(Long.class).single());
        r.put("planesAbiertos", jdbc.sql("SELECT count(*)" + DESDE + " JOIN visitas_plan pl ON pl.visita_id = v.id" + w.sql()
                + " AND v.estado = 'cerrada' AND pl.estado NOT IN ('Completado', 'Cancelado')").params(w.params()).query(Long.class).single());
        // Habilitación (componente C00): habilitado, no habilitado en trámite (D5: es hallazgo) y no habilitado sin soporte
        r.put("habilitacion", jdbc.sql("""
                SELECT count(*) FILTER (WHERE vr.calificacion = 'C') AS habilitado,
                       count(*) FILTER (WHERE vr.calificacion = 'NC' AND vr.en_tramite) AS tramite,
                       count(*) FILTER (WHERE vr.calificacion = 'NC' AND NOT vr.en_tramite) AS no_habilitado""" + DESDE
                + " JOIN visitas_respuestas vr ON vr.visita_id = v.id JOIN chk_items i ON i.id = vr.item_id JOIN chk_componentes cc ON cc.id = i.componente_id"
                + w.sql() + " AND v.estado = 'cerrada' AND cc.codigo = 'C00'").params(w.params()).query().singleRow());
        r.put("porComponente", jdbc.sql("""
                SELECT cc.codigo, max(cc.nombre) AS nombre, count(*) FILTER (WHERE vr.calificacion = 'C') AS c, count(*) FILTER (WHERE vr.calificacion = 'NC') AS nc,
                       count(*) FILTER (WHERE vr.calificacion = 'NA') AS na""" + DESDE
                + " JOIN visitas_respuestas vr ON vr.visita_id = v.id JOIN chk_items i ON i.id = vr.item_id JOIN chk_componentes cc ON cc.id = i.componente_id"
                + w.sql() + " AND v.estado = 'cerrada' GROUP BY cc.codigo ORDER BY count(*) FILTER (WHERE vr.calificacion = 'NC') DESC, cc.codigo")
                .params(w.params()).query().listOfRows());
        r.put("porPrestador", jdbc.sql("""
                SELECT CASE WHEN p.unidad = 'FA' THEN 'Farmacia alterna' ELSE 'Operador logístico' END AS prestador, coalesce(sum(rs.c), 0) AS c,
                       coalesce(sum(rs.nc), 0) AS nc, coalesce(sum(rs.na), 0) AS na""" + DESDE + " LEFT JOIN visitas_resumen rs ON rs.visita_id = v.id" + w.sql()
                + " AND v.estado = 'cerrada' GROUP BY 1 ORDER BY 3 DESC").params(w.params()).query().listOfRows());
        return r;
    }

    /** Opciones de los filtros: solo código y nombre; auditores activos sin correo; fuerzas del alcance sin «No aplica». */
    @Transactional(readOnly = true)
    public Map<String, Object> opciones(Alcance alcance) {
        ServicioRiesgo.exigirConsulta(alcance);
        Map.Entry<String, Map<String, Object>> c = ServicioRiesgo.alcancePuntos(alcance);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fuerzas", jdbc.sql("SELECT DISTINCT f.codigo, f.nombre FROM par_fuerzas f JOIN puntos p ON p.fuerza = f.codigo WHERE f.codigo <> 'NA' AND ("
                + c.getKey() + ") ORDER BY f.codigo").params(c.getValue()).query().listOfRows());
        m.put("tipos", jdbc.sql("SELECT codigo, nombre FROM par_tipos_visita ORDER BY orden").query().listOfRows());
        if (alcance instanceof Alcance.Todo) {
            m.put("auditores", jdbc.sql("""
                    SELECT u.id, u.nombre FROM usuarios u JOIN usuarios_perfiles up ON up.usuario_id = u.id AND up.perfil_id = :p WHERE u.activo ORDER BY u.nombre""")
                    .param("p", co.com.agsamericas.digsa.reglas.Perfil.AUDITOR.id()).query().listOfRows());
        }
        m.put("unidades", UNIDADES);
        m.put("estados", EstadosVisita.NOMBRES);
        return m;
    }
}
