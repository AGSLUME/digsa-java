package co.com.agsamericas.digsa.motorreportes;

import co.com.agsamericas.digsa.agenda.ServicioConsultaAgenda;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.EstadosVisita;
import co.com.agsamericas.digsa.reportes.ConsultaReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte;
import co.com.agsamericas.digsa.reportes.DefinicionReporte.Filtro;
import co.com.agsamericas.digsa.reportes.Opcion;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Filtros comunes de los reportes de visitas (Anexo 5, 2.11 y prueba 13): lugar, municipio, fuerza, unidad, regional, estado y
 * componente, cada uno con una, varias o todas las opciones. Las opciones salen recortadas al alcance del perfil y el WHERE aplica el
 * alcance con la misma condición de la agenda ({@link ServicioConsultaAgenda#condicion}).
 */
@Component
class FiltrosVisitas {

    static final Filtro LUGAR = Filtro.establecimiento("punto_id", "Lugar");
    static final Filtro MUNICIPIO = Filtro.lista("municipio", "Municipio");
    static final Filtro FUERZA = Filtro.lista("fuerza", "Fuerza");
    static final Filtro UNIDAD = Filtro.lista("unidad", "Unidad");
    static final Filtro REGIONAL = Filtro.lista("regional", "Regional");
    static final Filtro ESTADO = Filtro.lista("estado", "Estado");
    static final Filtro COMPONENTE = Filtro.lista("componente", "Componente");
    static final String FUENTE = "Suite DIGSA · visitas y actas";

    /** Condición del WHERE y sus parámetros. */
    record Where(String sql, Map<String, Object> params) {
    }

    private final JdbcClient jdbc;

    FiltrosVisitas(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Puntos dentro del alcance (alias {@code p}); el OPL nacional ve todos los puntos. */
    private static String puntosDelAlcance(Alcance a, Map<String, Object> params) {
        return switch (a) {
            case Alcance.Todo t -> "true";
            case Alcance.Opl o -> "true";
            case Alcance.Fuerza f -> {
                params.put("op_fuerza", f.codigo());
                yield "p.fuerza = :op_fuerza";
            }
            case Alcance.Regional r -> {
                params.put("op_regional", r.id());
                yield "p.regional_id = :op_regional";
            }
            case Alcance.Esm e -> {
                params.put("op_punto", e.puntoId());
                yield "p.id = :op_punto";
            }
            case Alcance.Equipo e -> "false";
            case Alcance.Ninguno n -> "false";
        };
    }

    /** Opciones del filtro dentro del alcance, ordenadas por nombre. */
    List<Opcion> opciones(String filtro, Alcance alcance) {
        Map<String, Object> params = new LinkedHashMap<>();
        String en = puntosDelAlcance(alcance, params);
        String sql = switch (filtro) {
            case "punto_id" -> "SELECT p.id::text AS valor, coalesce(p.sigla || ' · ', '') || p.nombre AS etiqueta FROM puntos p WHERE " + en
                    + " ORDER BY p.nombre, p.id";
            case "municipio" -> "SELECT DISTINCT m.codigo AS valor, m.nombre || ' (' || m.codigo || ')' AS etiqueta FROM puntos p"
                    + " JOIN par_municipios m ON m.codigo = p.municipio WHERE " + en + " ORDER BY 2, 1";
            case "fuerza" -> "SELECT DISTINCT f.codigo AS valor, f.nombre AS etiqueta, f.orden FROM puntos p JOIN par_fuerzas f ON f.codigo = p.fuerza"
                    + " WHERE " + en + " ORDER BY f.orden, f.codigo";
            case "unidad" -> "SELECT DISTINCT u.codigo AS valor, u.nombre AS etiqueta, u.orden FROM puntos p JOIN par_unidades u ON u.codigo = p.unidad"
                    + " WHERE " + en + " ORDER BY u.orden, u.codigo";
            case "regional" -> "SELECT DISTINCT r.id::text AS valor, r.nombre AS etiqueta FROM puntos p JOIN par_regionales r ON r.id = p.regional_id"
                    + " WHERE " + en + " ORDER BY 2, 1";
            case "componente" -> """
                    SELECT c.codigo AS valor, c.codigo || ' · ' || c.nombre AS etiqueta FROM chk_componentes c
                    JOIN chk_versiones v ON v.version = c.version WHERE v.vigente ORDER BY c.orden, c.codigo""";
            case "estado" -> null;
            default -> throw new IllegalArgumentException("Filtro de visitas desconocido: " + filtro);
        };
        if (sql == null) {
            return EstadosVisita.NOMBRES.entrySet().stream().map(e -> new Opcion(e.getKey(), e.getValue()))
                    .sorted(java.util.Comparator.comparing(Opcion::valor)).toList();
        }
        return jdbc.sql(sql).params(params).query((rs, n) -> new Opcion(rs.getString("valor"), rs.getString("etiqueta"))).list();
    }

    /**
     * WHERE de las visitas ({@code v}) y sus puntos ({@code p}): periodo sobre la fecha de la visita, alcance y los filtros elegidos.
     * El componente no va aquí: cada reporte lo aplica sobre su propia tabla.
     */
    Where where(ConsultaReporte c) {
        ServicioConsultaAgenda.Condicion alc = ServicioConsultaAgenda.condicion(c.alcance());
        Map<String, Object> params = new LinkedHashMap<>(alc.params());
        StringBuilder w = new StringBuilder(" WHERE v.fecha BETWEEN :desde AND :hasta AND (" + alc.sql() + ")");
        params.put("desde", c.desde());
        params.put("hasta", c.hasta());
        if (c.filtra("punto_id")) {
            w.append(" AND v.punto_id IN (:f_punto)");
            params.put("f_punto", c.valores("punto_id").stream().map(Long::valueOf).toList());
        }
        if (c.filtra("municipio")) {
            w.append(" AND p.municipio IN (:f_municipio)");
            params.put("f_municipio", c.valores("municipio"));
        }
        if (c.filtra("fuerza")) {
            w.append(" AND p.fuerza IN (:f_fuerza)");
            params.put("f_fuerza", c.valores("fuerza"));
        }
        if (c.filtra("unidad")) {
            w.append(" AND p.unidad IN (:f_unidad)");
            params.put("f_unidad", c.valores("unidad"));
        }
        if (c.filtra("regional")) {
            w.append(" AND p.regional_id IN (:f_regional)");
            params.put("f_regional", c.valores("regional").stream().map(Long::valueOf).toList());
        }
        if (c.filtra("estado")) {
            w.append(" AND v.estado IN (:f_estado)");
            params.put("f_estado", c.valores("estado"));
        }
        return new Where(w.toString(), params);
    }
}
