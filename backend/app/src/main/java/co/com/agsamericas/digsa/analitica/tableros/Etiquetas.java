package co.com.agsamericas.digsa.analitica.tableros;

import co.com.agsamericas.digsa.reglas.AnaliticaConsulta;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Dimension;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Nombres legibles de las claves de cada dimensión (Fuerza, regional, ESM, municipio, ATC, CIE-10, estado), leídos de los catálogos en la
 * misma transacción de la consulta y solo para las claves que salieron. Una clave sin nombre se muestra tal cual.
 */
final class Etiquetas {

    private final JdbcClient jdbc;
    private final Map<Dimension, Map<String, String>> nombres = new EnumMap<>(Dimension.class);

    Etiquetas(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Carga los nombres de estas claves de la dimensión (una consulta por dimensión). */
    void cargar(Dimension d, Collection<String> claves) {
        List<String> faltan = claves.stream().filter(c -> c != null && !especial(c) && !nombres.getOrDefault(d, Map.of()).containsKey(c)).distinct()
                .toList();
        if (faltan.isEmpty()) {
            return;
        }
        String[] arr = faltan.toArray(String[]::new);
        String sql = switch (d) {
            case FUERZA -> "SELECT codigo, nombre FROM par_fuerzas WHERE codigo = ANY(:c)";
            case REGIONAL -> "SELECT id::text, nombre FROM par_regionales WHERE id::text = ANY(:c)";
            case ESM -> "SELECT DISTINCT ON (upper(sigla)) upper(sigla), nombre FROM puntos WHERE upper(sigla) = ANY(:c) ORDER BY upper(sigla), id";
            case DEPARTAMENTO -> "SELECT codigo::text, nombre FROM par_departamentos WHERE codigo::text = ANY(:c)";
            case MUNICIPIO -> "SELECT m.codigo::text, m.nombre || ' (' || d.nombre || ')' FROM par_municipios m JOIN par_departamentos d "
                    + "ON d.codigo = m.departamento WHERE m.codigo::text = ANY(:c)";
            case ATC1, ATC2, ATC3, ATC4 -> "SELECT codigo, nombre FROM ref_atc_nombre_nivel WHERE codigo = ANY(:c)";
            case ATC5 -> "SELECT atc, descripcion_atc FROM ref_atc_v WHERE atc = ANY(:c) AND descripcion_atc IS NOT NULL";
            case CIE10 -> "SELECT DISTINCT ON (codigo) codigo::text, nombre FROM ref_cie10 WHERE codigo::text = ANY(:c) ORDER BY codigo, version_id DESC";
            default -> null;
        };
        Map<String, String> m = nombres.computeIfAbsent(d, x -> new HashMap<>());
        if (sql != null) {
            jdbc.sql(sql).param("c", arr).query((rs, n) -> {
                m.put(rs.getString(1), rs.getString(2));
                return null;
            }).list();
        }
        for (String c : faltan) {
            m.putIfAbsent(c, null);
        }
    }

    private static boolean especial(String c) {
        return AnaliticaConsulta.SIN_HOMOLOGAR.equals(c) || AnaliticaConsulta.SIN_DATO.equals(c);
    }

    /** Etiqueta de una clave: «Código · Nombre», el nombre solo (Fuerza, regional, ESM, municipio) o la clave. */
    String de(Dimension d, String clave) {
        if (clave == null) {
            return "—";
        }
        if (AnaliticaConsulta.SIN_HOMOLOGAR.equals(clave)) {
            return "Sin homologar";
        }
        if (AnaliticaConsulta.SIN_DATO.equals(clave)) {
            return switch (d) {
                case CIE10, CIE10_GRUPO -> "Sin CIE-10";
                case CUM -> "Sin CUM";
                case REGIONAL -> "Sin regional";
                case DISAN -> "Sin DISAN";
                case DEPARTAMENTO, MUNICIPIO -> "ESM sin correspondencia";
                case TIPO_FORMULA -> "Sin tipo de fórmula";
                case ESPECIALIDAD -> "Sin especialidad";
                default -> "Sin dato";
            };
        }
        if (d == Dimension.ESTADO_HOMOLOGACION) {
            return AnaliticaHomologacion.Estado.deCodigo(clave).map(AnaliticaHomologacion.Estado::texto).orElse(clave);
        }
        String n = nombres.getOrDefault(d, Map.of()).get(clave);
        return switch (d) {
            case FUERZA -> n == null ? clave : clave + " · " + n;
            case REGIONAL, DEPARTAMENTO, MUNICIPIO -> n == null ? clave : n;
            case ESM -> n == null ? clave + " (sin correspondencia)" : clave + " · " + n;
            default -> n == null ? clave : clave + " · " + n;
        };
    }
}
