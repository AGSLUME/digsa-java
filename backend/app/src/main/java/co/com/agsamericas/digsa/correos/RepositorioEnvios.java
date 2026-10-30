package co.com.agsamericas.digsa.correos;

import co.com.agsamericas.digsa.reglas.Fechas;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Lectura de la bitácora de envíos (correos_salida): lo encolado y los envíos directos. */
@Repository
class RepositorioEnvios {

    static final List<String> ESTADOS = List.of("pendiente", "enviando", "enviado", "parcial", "fallido");

    /** Periodo en fechas de Bogotá (ambas incluidas); tipos (orígenes) y estado opcionales; destinatario exacto (normalizado) o null. */
    record Filtro(LocalDate desde, LocalDate hasta, List<String> origenes, List<String> estados, String destinatario) {
    }

    record Fila(long id, OffsetDateTime creado, String origen, String referencia, List<String> para, List<String> copia, String asunto, String estado,
                int intentos, String error, OffsetDateTime enviado, boolean sincrono, boolean enmascarado, boolean reintentable,
                String motivoNoReintentable, Long reintentoDe, String motivoReintento, Long reintentoId, String adjuntos) {
    }

    private static final String COLUMNAS = """
            c.id, c.creado, c.origen, c.referencia, c.para, c.copia, c.asunto, c.estado, c.intentos, c.error, c.enviado, c.sincrono, c.enmascarado,
            c.reintentable, c.motivo_no_reintentable, c.reintento_de, c.motivo_reintento, c.adjuntos::text AS adjuntos,
            (SELECT r.id FROM correos_salida r WHERE r.reintento_de = c.id) AS reintento_id""";

    private final JdbcClient jdbc;

    RepositorioEnvios(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Una página, de la más reciente a la más antigua, después de (fecha, id) si se pide. */
    List<Fila> pagina(Filtro f, OffsetDateTime despuesFecha, Long despuesId, int n) {
        Map<String, Object> p = new HashMap<>();
        StringBuilder w = where(f, p);
        if (despuesFecha != null && despuesId != null) {
            w.append(" AND (c.creado, c.id) < (:df, :di)");
            p.put("df", despuesFecha);
            p.put("di", despuesId);
        }
        p.put("n", n);
        return jdbc.sql("SELECT " + COLUMNAS + " FROM correos_salida c" + w + " ORDER BY c.creado DESC, c.id DESC LIMIT :n").params(p)
                .query((rs, i) -> fila(rs)).list();
    }

    /** Recorre todo el filtro (para el reporte); el motor pone el tope. */
    void recorrer(Filtro f, String limite, Consumer<Fila> destino) {
        Map<String, Object> p = new HashMap<>();
        StringBuilder w = where(f, p);
        jdbc.sql("SELECT " + COLUMNAS + " FROM correos_salida c" + w + " ORDER BY c.creado DESC, c.id DESC" + limite).params(p)
                .query(rs -> {
                    destino.accept(fila(rs));
                });
    }

    Optional<Fila> porId(long id) {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM correos_salida c WHERE c.id = :id").param("id", id).query((rs, i) -> fila(rs)).optional();
    }

    Optional<String> html(long id) {
        return jdbc.sql("SELECT html FROM correos_salida WHERE id = :id").param("id", id).query(String.class).optional();
    }

    /** Orígenes que ya aparecen en la bitácora (para el filtro de tipo). */
    List<String> origenes() {
        return jdbc.sql("SELECT DISTINCT origen FROM correos_salida ORDER BY origen").query(String.class).list();
    }

    private static StringBuilder where(Filtro f, Map<String, Object> p) {
        p.put("desde", f.desde().atStartOfDay(Fechas.BOGOTA).toOffsetDateTime());
        p.put("hasta", f.hasta().plusDays(1).atStartOfDay(Fechas.BOGOTA).toOffsetDateTime());
        StringBuilder w = new StringBuilder(" WHERE c.creado >= :desde AND c.creado < :hasta");
        if (f.origenes() != null && !f.origenes().isEmpty()) {
            w.append(" AND c.origen IN (:origenes)");
            p.put("origenes", f.origenes());
        }
        if (f.estados() != null && !f.estados().isEmpty()) {
            w.append(" AND c.estado IN (:estados)");
            p.put("estados", f.estados());
        }
        if (f.destinatario() != null) {
            // Las direcciones se guardan normalizadas (minúsculas); el filtro es exacto: no se adivinan correos por pedazos
            w.append(" AND (:dest = ANY (c.para) OR :dest = ANY (c.copia))");
            p.put("dest", f.destinatario());
        }
        return w;
    }

    private static Fila fila(ResultSet rs) throws SQLException {
        long reintentoDe = rs.getLong("reintento_de");
        Long deId = rs.wasNull() ? null : reintentoDe;
        long reintento = rs.getLong("reintento_id");
        Long reintentoId = rs.wasNull() ? null : reintento;
        return new Fila(rs.getLong("id"), rs.getObject("creado", OffsetDateTime.class), rs.getString("origen"), rs.getString("referencia"), lista(rs, "para"),
                lista(rs, "copia"), rs.getString("asunto"), rs.getString("estado"), rs.getInt("intentos"), rs.getString("error"),
                rs.getObject("enviado", OffsetDateTime.class), rs.getBoolean("sincrono"), rs.getBoolean("enmascarado"), rs.getBoolean("reintentable"),
                rs.getString("motivo_no_reintentable"), deId, rs.getString("motivo_reintento"), reintentoId, rs.getString("adjuntos"));
    }

    private static List<String> lista(ResultSet rs, String columna) throws SQLException {
        Array a = rs.getArray(columna);
        return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
    }
}
