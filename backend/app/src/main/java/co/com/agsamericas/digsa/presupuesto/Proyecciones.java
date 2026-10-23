package co.com.agsamericas.digsa.presupuesto;

import co.com.agsamericas.digsa.reglas.Presupuesto;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Proyección de consumo y ejecución acumuladas al corte, por línea (spec «Proyección de consumo mensual»). Si la línea tiene proyección
 * cargada, se suma la cargada hasta el corte (los meses sin valor cuentan 0); si no, la lineal: el asignado vigente repartido en los
 * meses de la vigencia, que sigue los cambios del asignado sin guardarse. Dos consultas por vigencia, sin recorrer línea por línea.
 */
@Component
class Proyecciones {

    /** reporto: la línea tiene ejecución registrada del mes del corte (sin ella, su consumo del mes aún no se conoce). */
    record AlCorte(long lineaId, String fuerza, BigDecimal asignado, BigDecimal proyectado, BigDecimal ejecutado, boolean cargada, boolean reporto) {
    }

    private final JdbcClient jdbc;

    Proyecciones(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Todas las líneas de la vigencia al corte (inclusive), con lo ejecutado según la base de los umbrales. */
    Map<Long, AlCorte> alCorte(Lineas.Vigencia v, YearMonth corte, String base) {
        Map<Long, BigDecimal[]> cargadas = new HashMap<>();
        jdbc.sql("""
                SELECT x.linea_id, coalesce(sum(x.valor) FILTER (WHERE x.periodo <= :corte), 0) AS acumulado
                FROM presupuesto_proyeccion x JOIN presupuesto_lineas l ON l.id = x.linea_id
                WHERE x.vigente AND l.vigencia_id = :v GROUP BY x.linea_id""")
                .param("v", v.id()).param("corte", corte.atDay(1))
                .query((rs, n) -> Map.entry(rs.getLong(1), rs.getBigDecimal(2))).list().forEach(e -> cargadas.put(e.getKey(), new BigDecimal[] {e.getValue()}));
        String ejecutado = Presupuesto.BASE_RECONOCIDO.equals(base) ? "coalesce(e.reconocido, 0)" : "e.facturado";
        Map<Long, AlCorte> r = new HashMap<>();
        jdbc.sql("""
                SELECT l.id, l.fuerza, s.asignado, coalesce(sum(""" + ejecutado + """
                ) FILTER (WHERE e.periodo <= :corte), 0) AS ejecutado,
                       coalesce(bool_or(e.periodo = :corte""" + (Presupuesto.BASE_RECONOCIDO.equals(base) ? " AND e.reconocido IS NOT NULL" : "") + """
                ), false) AS reporto
                FROM presupuesto_lineas l JOIN presupuesto_saldos s ON s.linea_id = l.id
                LEFT JOIN presupuesto_ejecucion e ON e.linea_id = l.id AND e.vigente
                WHERE l.vigencia_id = :v GROUP BY l.id, l.fuerza, s.asignado""")
                .param("v", v.id()).param("corte", corte.atDay(1))
                .query((rs, n) -> {
                    long id = rs.getLong(1);
                    BigDecimal asignado = rs.getBigDecimal(3);
                    BigDecimal[] c = cargadas.get(id);
                    BigDecimal proyectado = c != null ? c[0].setScale(2) : Presupuesto.acumuladoLineal(asignado, v.inicio(), v.fin(), corte);
                    return new AlCorte(id, rs.getString(2), asignado, proyectado, rs.getBigDecimal(4).setScale(2), c != null, rs.getBoolean(5));
                }).list().forEach(a -> r.put(a.lineaId(), a));
        return r;
    }
}
