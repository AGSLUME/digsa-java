package co.com.agsamericas.digsa.auditoria;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Lectura de la bitácora: siempre por rango de fechas (condición principal sobre la partición) y con paginación por llave
 * (fecha, id) de lo más reciente a lo más antiguo, nunca por desplazamiento.
 */
@Repository
public class RepositorioLogs {

    public record Fila(long id, OffsetDateTime fecha, String operacion, String tipo, String modulo, Long usuarioId, String usuario,
                       String descripcion, String ip) {
    }

    public record Filtro(LocalDate desde, LocalDate hasta, Long usuarioId, String operacion, String texto) {
    }

    private final JdbcClient jdbc;
    /** Mismo origen de datos, con lectura por lotes para las exportaciones grandes. */
    private final JdbcClient porLotes;
    private final ZoneId zona;

    public RepositorioLogs(JdbcClient jdbc, javax.sql.DataSource fuente) {
        this.jdbc = jdbc;
        org.springframework.jdbc.core.JdbcTemplate t = new org.springframework.jdbc.core.JdbcTemplate(fuente);
        t.setFetchSize(1000);
        this.porLotes = JdbcClient.create(t);
        this.zona = co.com.agsamericas.digsa.reglas.Fechas.BOGOTA;
    }

    public List<Fila> pagina(Filtro f, OffsetDateTime despuesFecha, Long despuesId, int limite) {
        StringBuilder sql = new StringBuilder(BASE);
        if (despuesFecha != null && despuesId != null) {
            sql.append(" AND (fecha, id) < (:df, :di)");
        }
        sql.append(" ORDER BY fecha DESC, id DESC LIMIT :limite");
        var q = parametros(jdbc.sql(sql.toString()), f).param("limite", limite);
        if (despuesFecha != null && despuesId != null) {
            q = q.param("df", despuesFecha).param("di", despuesId);
        }
        return q.query(Fila.class).list();
    }

    /**
     * Recorre las filas del filtro por lotes (fetchSize) dentro de una transacción de solo lectura, que es lo que permite al driver de
     * PostgreSQL no traerlas todas a la vez. El stream se cierra siempre: así la conexión vuelve al pool.
     *
     * @return true si había más de {@code maximo} filas (la exportación se corta y se avisa)
     */
    @Transactional(readOnly = true)
    public boolean recorrer(Filtro f, int maximo, Consumer<Fila> consumidor) {
        int[] n = {0};
        try (Stream<Fila> s = parametros(porLotes.sql(BASE + " ORDER BY fecha DESC, id DESC LIMIT :limite"), f).param("limite", maximo + 1)
                .query(Fila.class).stream()) {
            s.forEach(x -> {
                if (++n[0] <= maximo) {
                    consumidor.accept(x);
                }
            });
        }
        return n[0] > maximo;
    }

    public List<java.util.Map<String, Object>> usuarios() {
        return jdbc.sql("SELECT id, nombre, usuario FROM usuarios ORDER BY nombre").query().listOfRows();
    }

    private static final String BASE = """
            SELECT id, fecha, operacion, tipo, modulo, usuario_id, usuario, descripcion, ip FROM log_transacciones
            WHERE fecha >= :desde AND fecha < :hasta
              AND (CAST(:usuarioId AS bigint) IS NULL OR usuario_id = :usuarioId)
              AND (CAST(:operacion AS text) IS NULL OR operacion = :operacion)
              AND (CAST(:texto AS text) IS NULL OR tipo ILIKE :texto ESCAPE '\\' OR descripcion ILIKE :texto ESCAPE '\\' OR usuario ILIKE :texto ESCAPE '\\')""";

    private JdbcClient.StatementSpec parametros(JdbcClient.StatementSpec q, Filtro f) {
        String texto = f.texto() == null || f.texto().isBlank() ? null
                : "%" + f.texto().strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return q.param("desde", f.desde().atStartOfDay(zona).toOffsetDateTime())
                .param("hasta", f.hasta().plusDays(1).atStartOfDay(zona).toOffsetDateTime())
                .param("usuarioId", f.usuarioId())
                .param("operacion", f.operacion())
                .param("texto", texto);
    }
}
