package co.com.agsamericas.digsa.reportes;

import co.com.agsamericas.digsa.agenda.ServicioConsultaAgenda;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.Reporte;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Checklist;
import co.com.agsamericas.digsa.reglas.EstadosVisita;
import co.com.agsamericas.digsa.reglas.Fechas;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reportes de visitas (Anexo 5, prueba 13 y 2.9 a 2.12): detalle filtrable por periodo y lugar, consolidado por lugar y consolidado
 * por proceso (componente del checklist). Encabezado con título, fecha, usuario y filtros; sin tope de filas; el alcance de cada perfil
 * se aplica en el SQL. Los resultados salen del resumen calculado en el servidor al enviar el acta.
 */
@Service
public class ServicioReportes {

    public static final Map<String, String> TIPOS = Map.of(
            "detalle", "Detalle de visitas",
            "lugar", "Consolidado de visitas por lugar",
            "proceso", "Consolidado de visitas por proceso (componente)",
            "distribucion", "Distribución auditor–punto");
    /** La distribución auditor–punto la ven la coordinación y la DIGSA (decisión 13.3): nombra a los auditores. */
    static final String DISTRIBUCION = "distribucion";
    static final int MAX_DIAS = 3 * 366;

    public record Filtros(String desde, String hasta, Long puntoId, String municipio, String fuerza, String unidad, String estado, String componente) {
    }

    private final JdbcClient jdbc;
    private final Clock reloj;

    public ServicioReportes(JdbcClient jdbc, Clock reloj) {
        this.jdbc = jdbc;
        this.reloj = reloj;
    }

    @Transactional(readOnly = true)
    /**
     * @param verEquipo el coordinador y el administrador ven el auditor líder; los perfiles de consulta (DIGSA, Sanidad, ESM) no
     *                  (Ley 1581), aunque la DIGSA tenga alcance sobre todo
     */
    public Reporte generar(String tipo, Filtros f, Alcance alcance, String usuario, boolean verEquipo) {
        String titulo = TIPOS.get(tipo);
        if (titulo == null || (DISTRIBUCION.equals(tipo) && !(alcance instanceof Alcance.Todo))) {
            throw ErrorNegocio.noEncontrado("El reporte no existe.");
        }
        LocalDate hoy = LocalDate.now(reloj.withZone(Fechas.BOGOTA));
        LocalDate desde = vacio(f.desde()) ? hoy.withDayOfYear(1) : Fechas.valida(f.desde()).orElseThrow(() -> ErrorNegocio.validacion("La fecha «desde» no es válida."));
        LocalDate hasta = vacio(f.hasta()) ? hoy : Fechas.valida(f.hasta()).orElseThrow(() -> ErrorNegocio.validacion("La fecha «hasta» no es válida."));
        if (hasta.isBefore(desde) || desde.plusDays(MAX_DIAS).isBefore(hasta)) {
            throw ErrorNegocio.validacion("El periodo debe ir de «desde» a «hasta» y ser de máximo tres años.");
        }
        ServicioConsultaAgenda.Condicion c = ServicioConsultaAgenda.condicion(alcance);
        Map<String, Object> params = new LinkedHashMap<>(c.params());
        params.put("desde", desde);
        params.put("hasta", hasta);
        StringBuilder where = new StringBuilder(" WHERE v.fecha BETWEEN :desde AND :hasta AND (" + c.sql() + ")");
        Map<String, String> filtros = new LinkedHashMap<>();
        filtros.put("Periodo", desde + " a " + hasta);
        if (f.puntoId() != null) {
            where.append(" AND v.punto_id = :punto");
            params.put("punto", f.puntoId());
            // El nombre del lugar solo si está dentro del alcance (un ESM no conoce los nombres de otros puntos por su id)
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> visibles = (List<Map<String, Object>>) opciones(alcance).get("puntos");
            String nombre = visibles.stream().filter(x -> f.puntoId().equals(((Number) x.get("id")).longValue())).map(x -> (String) x.get("nombre"))
                    .findFirst().orElseThrow(() -> ErrorNegocio.validacion("El lugar no existe o está fuera de su alcance."));
            filtros.put("Lugar", nombre);
        }
        if (!vacio(f.municipio())) {
            where.append(" AND p.municipio = :municipio");
            params.put("municipio", f.municipio());
            filtros.put("Municipio", jdbc.sql("SELECT nombre FROM par_municipios WHERE codigo = :c").param("c", f.municipio()).query(String.class)
                    .optional().orElse(f.municipio()));
        }
        if (!vacio(f.fuerza())) {
            where.append(" AND p.fuerza = :fuerza");
            params.put("fuerza", f.fuerza());
            filtros.put("Fuerza", f.fuerza());
        }
        if (!vacio(f.unidad())) {
            where.append(" AND p.unidad = :unidad");
            params.put("unidad", f.unidad());
            filtros.put("Unidad", f.unidad());
        }
        if (!vacio(f.estado()) && !EstadosVisita.NOMBRES.containsKey(f.estado())) {
            throw ErrorNegocio.validacion("El estado «" + f.estado() + "» no existe.");
        }
        if (!vacio(f.estado())) {
            where.append(" AND v.estado = :estado");
            params.put("estado", f.estado());
            filtros.put("Estado", EstadosVisita.NOMBRES.get(f.estado()));
        }
        boolean consulta = !verEquipo;
        ZonedDateTime generado = ZonedDateTime.now(reloj.withZone(Fechas.BOGOTA));
        return switch (tipo) {
            // La distribución solo filtra por periodo: el encabezado no muestra filtros que no aplica
            case DISTRIBUCION -> distribucion(titulo, desde, hasta, new LinkedHashMap<>(Map.of("Periodo", desde + " a " + hasta)), generado, usuario);
            case "detalle" -> detalle(titulo, where.toString(), params, filtros, consulta, generado, usuario);
            case "lugar" -> porLugar(titulo, where.toString(), params, filtros, generado, usuario);
            default -> porProceso(titulo, where.toString(), params, filtros, f.componente(), generado, usuario);
        };
    }

    /**
     * Distribución auditor–punto (decisión 2026-10-20 13.3): por auditor activo, los puntos que tiene asignados o que visita en el
     * periodo, con la distancia en línea recta de su municipio base al municipio de acceso del punto y sus visitas agendadas y cerradas.
     * Justifica ante la DIGSA el reparto por cercanía y carga de la propuesta de agenda.
     */
    private Reporte distribucion(String titulo, LocalDate desde, LocalDate hasta, Map<String, String> filtros, ZonedDateTime generado, String usuario) {
        Map<String, double[]> coords = new java.util.HashMap<>();
        jdbc.sql("SELECT codigo, latitud, longitud FROM par_municipios WHERE latitud IS NOT NULL AND longitud IS NOT NULL").query((rs, n) -> {
            coords.put(rs.getString(1).strip(), new double[] {rs.getDouble(2), rs.getDouble(3)});
            return null;
        }).list();
        List<String> columnas = List.of("Auditor", "Rol", "Municipio base", "Punto", "Unidad", "Modalidad", "Municipio del punto", "Asignado al punto",
                "Distancia (km)", "Agendadas", "Cerradas");
        List<List<Object>> filas = jdbc.sql("""
                SELECT u.nombre, coalesce(a.rol, 'otro') AS rol, mb.nombre AS base, a.municipio_base, p.nombre AS punto, p.unidad, p.modalidad, mp.nombre AS mpunto,
                       coalesce(p.llega_desde, p.municipio) AS acceso, (p.auditor_asignado = u.id) AS asignado,
                       count(v.id) FILTER (WHERE v.estado IN ('programada', 'notificada', 'reprogramada', 'ejecucion')) AS agendadas,
                       count(v.id) FILTER (WHERE v.estado = 'cerrada') AS cerradas
                FROM usuarios u JOIN auditores a ON a.usuario_id = u.id LEFT JOIN par_municipios mb ON mb.codigo = a.municipio_base
                JOIN puntos p ON p.activo AND (p.auditor_asignado = u.id OR EXISTS (SELECT 1 FROM visitas v2 JOIN visitas_auditores x2 ON x2.visita_id = v2.id
                                                                     WHERE v2.punto_id = p.id AND x2.usuario_id = u.id AND v2.fecha BETWEEN :desde AND :hasta))
                LEFT JOIN par_municipios mp ON mp.codigo = p.municipio
                LEFT JOIN visitas v ON v.punto_id = p.id AND v.fecha BETWEEN :desde AND :hasta
                  AND EXISTS (SELECT 1 FROM visitas_auditores x WHERE x.visita_id = v.id AND x.usuario_id = u.id)
                WHERE u.activo
                GROUP BY u.id, u.nombre, a.rol, mb.nombre, a.municipio_base, p.id, p.nombre, p.unidad, p.modalidad, mp.nombre, p.llega_desde, p.municipio, p.auditor_asignado
                ORDER BY u.nombre, p.nombre""").param("desde", desde).param("hasta", hasta).query((rs, n) -> {
            String base = rs.getString("municipio_base");
            String acceso = rs.getString("acceso");
            double[] b = base == null ? null : coords.get(base.strip());
            double[] o = acceso == null ? null : coords.get(acceso.strip());
            List<Object> fila = new ArrayList<>();
            fila.add(rs.getString("nombre"));
            fila.add(co.com.agsamericas.digsa.reglas.Reparto.ROLES.getOrDefault(rs.getString("rol"), "Otro"));
            fila.add(rs.getString("base"));
            fila.add(rs.getString("punto"));
            fila.add(rs.getString("unidad"));
            fila.add(rs.getString("modalidad"));
            fila.add(rs.getString("mpunto"));
            fila.add(rs.getBoolean("asignado") ? "Sí" : "No");
            fila.add(b == null || o == null ? null : Math.round(co.com.agsamericas.digsa.reglas.PropuestaAgenda.distanciaKm(b, o)));
            fila.add(rs.getInt("agendadas"));
            fila.add(rs.getInt("cerradas"));
            return fila;
        }).list();
        return new Reporte(titulo, generado, usuario, filtros, columnas, filas);
    }

    private Reporte detalle(String titulo, String where, Map<String, Object> params, Map<String, String> filtros, boolean consulta,
                            ZonedDateTime generado, String usuario) {
        List<String> columnas = new ArrayList<>(List.of("Número", "Fecha", "Estado", "Tipo de visita", "Lugar", "Municipio", "Fuerza", "Unidad"));
        if (!consulta) {
            columnas.add("Auditor líder"); // los perfiles de consulta no ven el equipo (Ley 1581)
        }
        columnas.addAll(List.of("Cumple", "No cumple", "No aplica", "% cumplimiento", "Hallazgos"));
        List<List<Object>> filas = jdbc.sql("""
                SELECT v.numero, v.fecha, v.estado, t.nombre AS tipo, p.nombre AS lugar, m.nombre AS municipio, p.fuerza, p.unidad, u.nombre AS lider,
                       r.c, r.nc, r.na, r.hallazgos
                FROM visitas v JOIN puntos p ON p.id = v.punto_id JOIN par_tipos_visita t ON t.codigo = v.tipo JOIN usuarios u ON u.id = v.auditor_id
                LEFT JOIN par_municipios m ON m.codigo = p.municipio LEFT JOIN visitas_resumen r ON r.visita_id = v.id""" + where
                + " ORDER BY v.fecha, v.id").params(params).query((rs, n) -> {
            List<Object> fila = new ArrayList<>();
            fila.add(rs.getString("numero"));
            fila.add(rs.getDate("fecha").toLocalDate());
            fila.add(EstadosVisita.NOMBRES.get(rs.getString("estado")));
            fila.add(rs.getString("tipo"));
            fila.add(rs.getString("lugar"));
            fila.add(rs.getString("municipio"));
            fila.add(rs.getString("fuerza"));
            fila.add(rs.getString("unidad"));
            if (!consulta) {
                fila.add(rs.getString("lider"));
            }
            Integer cu = (Integer) rs.getObject("c");
            Integer nc = (Integer) rs.getObject("nc");
            fila.add(cu);
            fila.add(nc);
            fila.add(rs.getObject("na"));
            fila.add(cu == null || nc == null ? null : Checklist.cumplimiento(cu, nc));
            fila.add(rs.getObject("hallazgos"));
            return fila;
        }).list();
        return new Reporte(titulo, generado, usuario, filtros, columnas, filas);
    }

    private Reporte porLugar(String titulo, String where, Map<String, Object> params, Map<String, String> filtros, ZonedDateTime generado,
                             String usuario) {
        List<String> columnas = List.of("Lugar", "Sigla", "Municipio", "Fuerza", "Unidad", "Visitas", "Cerradas", "En ejecución", "Por ejecutar",
                "Canceladas", "Cumple", "No cumple", "% cumplimiento", "Hallazgos");
        List<List<Object>> filas = jdbc.sql("""
                SELECT p.nombre AS lugar, p.sigla, m.nombre AS municipio, p.fuerza, p.unidad, count(*) AS visitas,
                       count(*) FILTER (WHERE v.estado = 'cerrada') AS cerradas, count(*) FILTER (WHERE v.estado = 'ejecucion') AS ejecucion,
                       count(*) FILTER (WHERE v.estado IN ('programada', 'notificada', 'reprogramada')) AS por_ejecutar,
                       count(*) FILTER (WHERE v.estado = 'cancelada') AS canceladas,
                       coalesce(sum(r.c), 0) AS c, coalesce(sum(r.nc), 0) AS nc, coalesce(sum(r.hallazgos), 0) AS hallazgos
                FROM visitas v JOIN puntos p ON p.id = v.punto_id LEFT JOIN par_municipios m ON m.codigo = p.municipio
                LEFT JOIN visitas_resumen r ON r.visita_id = v.id""" + where
                + " GROUP BY p.id, p.nombre, p.sigla, m.nombre, p.fuerza, p.unidad ORDER BY p.nombre").params(params).query((rs, n) -> {
            int c = rs.getInt("c");
            int nc = rs.getInt("nc");
            List<Object> fila = new ArrayList<>(List.of(rs.getString("lugar")));
            fila.add(rs.getString("sigla"));
            fila.add(rs.getString("municipio"));
            fila.add(rs.getString("fuerza"));
            fila.add(rs.getString("unidad"));
            fila.add(rs.getInt("visitas"));
            fila.add(rs.getInt("cerradas"));
            fila.add(rs.getInt("ejecucion"));
            fila.add(rs.getInt("por_ejecutar"));
            fila.add(rs.getInt("canceladas"));
            fila.add(c);
            fila.add(nc);
            fila.add(Checklist.cumplimiento(c, nc));
            fila.add(rs.getInt("hallazgos"));
            return fila;
        }).list();
        return new Reporte(titulo, generado, usuario, filtros, columnas, filas);
    }

    private Reporte porProceso(String titulo, String where, Map<String, Object> params, Map<String, String> filtros, String componente,
                               ZonedDateTime generado, String usuario) {
        String extra = "";
        if (!vacio(componente)) {
            extra = " AND c.codigo = :componente";
            params.put("componente", componente);
            filtros.put("Componente", componente);
        }
        List<String> columnas = List.of("Componente", "Proceso", "Visitas", "Ítems calificados", "Cumple", "No cumple", "No aplica", "% cumplimiento");
        List<List<Object>> filas = jdbc.sql("""
                SELECT c.codigo, max(c.nombre) AS nombre, count(DISTINCT v.id) AS visitas, count(*) FILTER (WHERE vr.calificacion IS NOT NULL) AS calificados,
                       count(*) FILTER (WHERE vr.calificacion = 'C') AS c, count(*) FILTER (WHERE vr.calificacion = 'NC') AS nc,
                       count(*) FILTER (WHERE vr.calificacion = 'NA') AS na
                FROM visitas v JOIN puntos p ON p.id = v.punto_id JOIN visitas_respuestas vr ON vr.visita_id = v.id
                JOIN chk_items i ON i.id = vr.item_id JOIN chk_componentes c ON c.id = i.componente_id""" + where + extra
                // Solo visitas cerradas (con acta): las calificaciones de una visita en curso o reabierta son borradores
                + " AND v.estado = 'cerrada' GROUP BY c.codigo ORDER BY c.codigo").params(params).query((rs, n) -> {
            int c = rs.getInt("c");
            int nc = rs.getInt("nc");
            List<Object> fila = new ArrayList<>(List.of(rs.getString("codigo")));
            fila.add(rs.getString("nombre"));
            fila.add(rs.getInt("visitas"));
            fila.add(rs.getInt("calificados"));
            fila.add(c);
            fila.add(nc);
            fila.add(rs.getInt("na"));
            fila.add(Checklist.cumplimiento(c, nc));
            return fila;
        }).list();
        return new Reporte(titulo, generado, usuario, filtros, columnas, filas);
    }

    /** Opciones de los filtros: los puntos dentro del alcance del perfil, las fuerzas y los componentes del checklist vigente. */
    @Transactional(readOnly = true)
    public Map<String, Object> opciones(Alcance alcance) {
        String filtro = switch (alcance) {
            case Alcance.Todo t -> "true";
            case Alcance.Opl o -> "true";
            case Alcance.Fuerza f -> "p.fuerza = :x";
            case Alcance.Regional r -> "p.regional_id = :x";
            case Alcance.Esm e -> "p.id = :x";
            default -> "false";
        };
        Object valor = switch (alcance) {
            case Alcance.Fuerza f -> f.codigo();
            case Alcance.Regional r -> r.id();
            case Alcance.Esm e -> e.puntoId();
            default -> null;
        };
        var q = jdbc.sql("SELECT p.id, p.nombre, p.sigla, p.unidad, p.fuerza FROM puntos p WHERE " + filtro + " ORDER BY p.nombre");
        if (valor != null) {
            q = q.param("x", valor);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, String> tipos = new java.util.TreeMap<>(TIPOS);
        if (!(alcance instanceof Alcance.Todo)) {
            tipos.remove(DISTRIBUCION);
        }
        m.put("tipos", tipos);
        m.put("formatos", List.of("xlsx", "csv", "pdf", "txt"));
        m.put("puntos", q.query().listOfRows());
        m.put("fuerzas", jdbc.sql("SELECT codigo, nombre FROM par_fuerzas WHERE activo ORDER BY orden").query().listOfRows());
        m.put("componentes", jdbc.sql("""
                SELECT c.codigo, c.nombre FROM chk_componentes c JOIN chk_versiones v ON v.version = c.version WHERE v.vigente ORDER BY c.orden""")
                .query().listOfRows());
        m.put("estados", EstadosVisita.NOMBRES);
        return m;
    }

    private static boolean vacio(String s) {
        return s == null || s.isBlank();
    }
}
