package co.com.agsamericas.digsa.reportes;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Acceso a la base para los proveedores de reportes: lee con cursor (fetchSize), así las filas llegan al destino por lotes y no se
 * cargan completas en memoria. El cursor de PostgreSQL solo funciona dentro de una transacción, y {@code filas} siempre corre en la
 * de solo lectura que abre el motor (el JdbcTemplate toma la conexión de esa transacción).
 */
@Component
public class JdbcReportes {

    static final int LOTE = 1000;

    private final JdbcClient jdbc;

    public JdbcReportes(DataSource origen) {
        JdbcTemplate t = new JdbcTemplate(origen);
        t.setFetchSize(LOTE);
        this.jdbc = JdbcClient.create(t);
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    /** {@code " LIMIT n"} si la consulta trae un límite; el número sale del motor, nunca del cliente. */
    public static String limite(ConsultaReporte c) {
        return c.limite() > 0 ? " LIMIT " + c.limite() : "";
    }
}
