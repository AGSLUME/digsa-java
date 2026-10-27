package co.com.agsamericas.digsa.tablero;

import co.com.agsamericas.digsa.reglas.Alcance;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mapas georreferenciados (Anexo 4 §9 y §11): el mapa de visitas por departamento (un punto por visita del filtro del dashboard,
 * con el estado agrupado en programada, en ejecución y ejecutada; las canceladas no salen) y el mapa de control con la cobertura de
 * los puntos activos del alcance. La ubicación es la latitud y longitud DANE del municipio del punto; lo que no tiene municipio con
 * coordenadas se cuenta en «sinUbicacion» y no en «totales» (los totales son las marcas del mapa). No se envían datos personales
 * (ni líder ni equipo). El mapa de visitas trae a lo sumo {@link #TOPE_VISITAS} visitas, con «truncado» si hay más.
 */
@Service
public class ServicioMapas {

    /** Estado de la visita → estado del mapa. */
    static final Map<String, String> ESTADO_MAPA = Map.of("programada", "programada", "notificada", "programada", "reprogramada", "programada",
            "ejecucion", "ejecucion", "cerrada", "ejecutada");
    static final List<String> ESTADOS_MAPA = List.of("programada", "ejecucion", "ejecutada");
    static final List<String> ESTADOS_COBERTURA = List.of("visitado", "programado", "sin_visita", "nunca");
    /** Visitas como máximo en el mapa (un año del contrato son unos cientos; sin fechas se puede pedir todo el contrato). */
    static final int TOPE_VISITAS = 20_000;
    /** La cobertura en sitio excluye los puntos de suministro y satélite (decisión 2026-10-20 16.1); los nombres son los de par_modalidades. */
    static final Set<String> MODALIDADES_REMOTAS = Set.of("Suministro", "Satélite");

    private static final String DESDE = " FROM visitas v JOIN puntos p ON p.id = v.punto_id ";

    private final JdbcClient jdbc;
    private final ServicioDashboard dashboard;

    public ServicioMapas(JdbcClient jdbc, ServicioDashboard dashboard) {
        this.jdbc = jdbc;
        this.dashboard = dashboard;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> visitas(ServicioDashboard.Filtros f, Alcance alcance) {
        ServicioRiesgo.exigirConsulta(alcance);
        ServicioDashboard.Where w = dashboard.where(f, alcance);
        List<Map<String, Object>> filas = jdbc.sql("""
                SELECT v.id, v.numero, v.fecha, v.estado AS estado_visita, t.nombre AS tipo, p.nombre AS punto, p.unidad, p.fuerza,
                       m.nombre AS municipio, m.departamento, m.latitud, m.longitud""" + DESDE
                + " JOIN par_tipos_visita t ON t.codigo = v.tipo LEFT JOIN par_municipios m ON m.codigo = p.municipio" + w.sql()
                + " AND v.estado <> 'cancelada' ORDER BY v.fecha, v.id LIMIT :tope").params(w.params()).param("tope", TOPE_VISITAS + 1)
                .query().listOfRows();
        boolean truncado = filas.size() > TOPE_VISITAS;
        Map<String, Long> totales = conteos(ESTADOS_MAPA);
        Map<String, Map<String, Long>> porDepartamento = new LinkedHashMap<>();
        List<Map<String, Object>> visitas = new ArrayList<>();
        long sinUbicacion = 0;
        for (Map<String, Object> x : truncado ? filas.subList(0, TOPE_VISITAS) : filas) {
            String estado = ESTADO_MAPA.get((String) x.remove("estado_visita"));
            if (x.get("latitud") == null || x.get("longitud") == null) {
                sinUbicacion++;
                continue;
            }
            x.put("estado", estado);
            x.put("fecha", x.get("fecha").toString());
            visitas.add(x);
            totales.merge(estado, 1L, Long::sum);
            porDepartamento.computeIfAbsent((String) x.get("departamento"), k -> conteos(ESTADOS_MAPA)).merge(estado, 1L, Long::sum);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("visitas", visitas);
        r.put("truncado", truncado);
        r.put("totales", totales);
        r.put("porDepartamento", departamentos(porDepartamento));
        r.put("sinUbicacion", sinUbicacion);
        r.put("filtros", w.aplicados());
        return r;
    }

    /**
     * Cobertura de los puntos activos del alcance (con los filtros de fuerza y unidad), sin suministro ni satélite. Estado por punto:
     * «visitado» con una visita cerrada del filtro; si no, «programado» con una abierta del filtro; si no, «sin_visita» si alguna vez
     * tuvo una cerrada hasta el fin del periodo y «nunca» si no. Sin fechas, el periodo es todo el contrato.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> cobertura(ServicioDashboard.Filtros f, Alcance alcance) {
        ServicioRiesgo.exigirConsulta(alcance);
        ServicioDashboard.Where w = dashboard.where(f, alcance);
        Set<Long> cerradas = Set.copyOf(jdbc.sql("SELECT DISTINCT v.punto_id" + DESDE + w.sql() + " AND v.estado = 'cerrada'").params(w.params())
                .query(Long.class).list());
        Set<Long> abiertas = Set.copyOf(jdbc.sql("SELECT DISTINCT v.punto_id" + DESDE + w.sql() + " AND v.estado = ANY(:abiertas)").params(w.params())
                .param("abiertas", ServicioDashboard.ABIERTAS.toArray(String[]::new)).query(Long.class).list());
        Map.Entry<String, Map<String, Object>> c = ServicioRiesgo.alcancePuntos(alcance);
        String fuerza = w.aplicados().get("Fuerza");
        String unidad = w.aplicados().get("Unidad");
        List<Map<String, Object>> filas = jdbc.sql("""
                SELECT p.id, p.nombre, p.unidad, p.fuerza, p.modalidad, m.nombre AS municipio, m.departamento, m.latitud, m.longitud,
                       (SELECT max(v.fecha) FROM visitas v WHERE v.punto_id = p.id AND v.estado = 'cerrada'
                          AND (CAST(:hasta AS date) IS NULL OR v.fecha <= :hasta)) AS ultima_cerrada
                FROM puntos p LEFT JOIN par_municipios m ON m.codigo = p.municipio
                WHERE p.activo AND (p.modalidad IS NULL OR NOT p.modalidad = ANY(:remotas))
                  AND (CAST(:fuerza AS text) IS NULL OR p.fuerza = :fuerza) AND (CAST(:unidad AS text) IS NULL OR p.unidad = :unidad)
                  AND (""" + c.getKey() + ") ORDER BY p.nombre, p.id").params(c.getValue()).param("remotas", MODALIDADES_REMOTAS.toArray(String[]::new))
                .param("fuerza", fuerza).param("unidad", unidad).param("hasta", w.params().get("hasta")).query().listOfRows();
        Map<String, Long> totales = conteos(ESTADOS_COBERTURA);
        Map<String, Map<String, Long>> porDepartamento = new LinkedHashMap<>();
        List<Map<String, Object>> puntos = new ArrayList<>();
        long sinUbicacion = 0;
        for (Map<String, Object> x : filas) {
            if (x.get("latitud") == null || x.get("longitud") == null) {
                sinUbicacion++;
                continue;
            }
            long id = ((Number) x.get("id")).longValue();
            Object ultima = x.remove("ultima_cerrada");
            String estado = cerradas.contains(id) ? "visitado" : abiertas.contains(id) ? "programado" : ultima != null ? "sin_visita" : "nunca";
            x.put("estado", estado);
            x.put("ultimaCerrada", ultima == null ? null : ultima.toString());
            puntos.add(x);
            totales.merge(estado, 1L, Long::sum);
            porDepartamento.computeIfAbsent((String) x.get("departamento"), k -> conteos(ESTADOS_COBERTURA)).merge(estado, 1L, Long::sum);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("puntos", puntos);
        r.put("totales", totales);
        r.put("porDepartamento", departamentos(porDepartamento));
        r.put("sinUbicacion", sinUbicacion);
        r.put("filtros", w.aplicados());
        return r;
    }

    private static Map<String, Long> conteos(List<String> estados) {
        Map<String, Long> m = new LinkedHashMap<>();
        estados.forEach(e -> m.put(e, 0L));
        return m;
    }

    /** Una fila por departamento con datos: código, nombre, conteo por estado y total, en el orden del código DANE. */
    private List<Map<String, Object>> departamentos(Map<String, Map<String, Long>> conteos) {
        Map<String, String> nombres = new LinkedHashMap<>();
        jdbc.sql("SELECT codigo, nombre FROM par_departamentos ORDER BY codigo").query((rs, n) -> nombres.put(rs.getString(1), rs.getString(2))).list();
        return nombres.entrySet().stream().filter(e -> conteos.containsKey(e.getKey())).map(e -> {
            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("codigo", e.getKey());
            fila.put("nombre", e.getValue());
            Map<String, Long> c = conteos.get(e.getKey());
            fila.putAll(c);
            fila.put("total", c.values().stream().mapToLong(Long::longValue).sum());
            return fila;
        }).toList();
    }
}
