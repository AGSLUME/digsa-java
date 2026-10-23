package co.com.agsamericas.digsa.motorreportes;

import co.com.agsamericas.digsa.reglas.Fechas;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registro de los reportes generados ({@code reportes_generados}, V50): una fila por cada generación con su código de verificación,
 * los parámetros, el alcance y las huellas. Las escrituras no van en la transacción de la petición: el registro queda aunque la
 * respuesta falle después. Cada cambio de estado comprueba que la fila estaba en el estado esperado.
 */
@Component
class RegistroGenerados {

    /** Un reporte generado, como quedó registrado. */
    record Generado(long id, String codigo, String reporte, int version, String titulo, long usuarioId, String usuario, String alcance,
                    Map<String, Object> parametros, List<String> columnas, String formato, String estado, String ruta, Long tamano, Long filas,
                    boolean truncado, String huellaDatos, String huellaArchivo, Integer msTotal, OffsetDateTime creado, OffsetDateTime vence) {

        Generado {
            parametros = parametros == null ? Map.of() : java.util.Collections.unmodifiableMap(parametros);
            columnas = columnas == null ? List.of() : List.copyOf(columnas);
        }
    }

    /** Lo que se registra al empezar. */
    record Nuevo(String reporte, int version, String titulo, long usuarioId, String usuario, String alcance, Map<String, Object> parametros,
                 List<String> columnas, String formato) {
    }

    /** Clase del candado por usuario del diferido (pg_advisory_xact_lock de dos llaves: no choca con el de la limpieza). */
    static final int CANDADO_USUARIO = 0x52505447; // «RPTG»

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate propia;

    RegistroGenerados(JdbcClient jdbc, ObjectMapper json, PlatformTransactionManager transacciones) {
        this.jdbc = jdbc;
        this.json = json;
        this.propia = new TransactionTemplate(transacciones);
        this.propia.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private String aJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el registro del reporte", e);
        }
    }

    /**
     * Inserta con un código nuevo y devuelve id y código. Si el código choca con uno existente (ON CONFLICT, sin abortar una
     * transacción que hubiera alrededor), prueba otro.
     *
     * @param vence fin del día en que se pidió el archivo; null si es en pantalla
     */
    Map.Entry<Long, String> insertar(Nuevo n, String estado, OffsetDateTime ahora, OffsetDateTime vence) {
        for (int intento = 0; intento < 5; intento++) {
            String codigo = CodigoVerificacion.nuevo(ahora.atZoneSameInstant(Fechas.BOGOTA).toLocalDate());
            Optional<Long> id = jdbc.sql("""
                    INSERT INTO reportes_generados (codigo, reporte, version, titulo, usuario_id, usuario, alcance, parametros, columnas, formato, estado,
                                                    creado, iniciado, vence)
                    VALUES (:codigo, :reporte, :version, :titulo, :uid, :usuario, :alcance, CAST(:parametros AS jsonb), CAST(:columnas AS jsonb), :formato,
                            :estado, :creado, :iniciado, :vence)
                    ON CONFLICT (codigo) DO NOTHING RETURNING id""").param("codigo", codigo).param("reporte", n.reporte()).param("version", n.version())
                    .param("titulo", n.titulo()).param("uid", n.usuarioId()).param("usuario", n.usuario()).param("alcance", n.alcance())
                    .param("parametros", aJson(n.parametros())).param("columnas", aJson(n.columnas())).param("formato", n.formato()).param("estado", estado)
                    .param("creado", ahora).param("iniciado", "generando".equals(estado) ? ahora : null).param("vence", vence).query(Long.class).optional();
            if (id.isPresent()) {
                return Map.entry(id.get(), codigo);
            }
        }
        throw new IllegalStateException("No se obtuvo un código de verificación libre en 5 intentos");
    }

    /**
     * Inserta la solicitud «pendiente» solo si el usuario tiene menos de {@code tope} en cola o en curso. El conteo y la inserción van
     * en una transacción con un candado por usuario: dos peticiones a la vez (o en dos réplicas) no pasan las dos el tope.
     *
     * @return id y código; vacío si ya llegó al tope
     */
    Optional<Map.Entry<Long, String>> pedir(Nuevo n, int tope, OffsetDateTime ahora, OffsetDateTime vence) {
        return propia.execute(t -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(:k, :u)").param("k", CANDADO_USUARIO).param("u", (int) n.usuarioId()).query().singleRow();
            if (enCurso(n.usuarioId()) >= tope) {
                return Optional.<Map.Entry<Long, String>>empty();
            }
            return Optional.of(insertar(n, "pendiente", ahora, vence));
        });
    }

    private static int ms(long v) {
        return (int) Math.max(0, Math.min(v, Integer.MAX_VALUE));
    }

    void vista(long id, long filas, boolean truncado, String huella, long ms, OffsetDateTime ahora) {
        cambio(jdbc.sql("""
                UPDATE reportes_generados SET filas = :filas, truncado = :truncado, huella_datos = :huella, ms_consulta = :ms, ms_total = :ms, terminado = :t
                WHERE id = :id AND estado = 'vista'""").param("filas", filas).param("truncado", truncado).param("huella", huella).param("ms", ms(ms))
                .param("t", ahora).param("id", id).update(), id, "vista");
    }

    /**
     * Archivo listo. El vencimiento no puede quedar antes del fin del día en que terminó (un diferido que pasa la medianoche sigue
     * disponible ese día).
     */
    void lista(long id, String ruta, long tamano, long filas, String huellaDatos, String huellaArchivo, long msConsulta, long msArchivo,
               OffsetDateTime terminado, OffsetDateTime venceMinimo) {
        cambio(jdbc.sql("""
                UPDATE reportes_generados SET estado = 'lista', ruta = :ruta, tamano = :tamano, filas = :filas, huella_datos = :hd, huella_archivo = :ha,
                       ms_consulta = :mc, ms_archivo = :ma, ms_total = :mt, terminado = :t, vence = greatest(vence, :vence)
                WHERE id = :id AND estado = 'generando'""").param("ruta", ruta).param("tamano", tamano).param("filas", filas).param("hd", huellaDatos)
                .param("ha", huellaArchivo).param("mc", ms(msConsulta)).param("ma", ms(msArchivo)).param("mt", ms(msConsulta + msArchivo))
                .param("t", terminado).param("vence", venceMinimo).param("id", id).update(), id, "generando");
    }

    /** Falló en la cola (la cola la rechazó) o generándose. */
    void fallida(long id, String error, OffsetDateTime ahora) {
        cambio(jdbc.sql("""
                UPDATE reportes_generados SET estado = 'fallida', ruta = NULL, error = :e, terminado = :t
                WHERE id = :id AND estado IN ('pendiente', 'generando')""")
                .param("e", error == null || error.isBlank() ? "No se pudo generar el archivo." : error).param("t", ahora).param("id", id).update(), id,
                "pendiente» o «generando");
    }

    /** Al apagar la instancia: lo que tenía en cola o en curso queda «fallida» sin esperar a la limpieza y libera el cupo del usuario. */
    int interrumpidas(Collection<Long> ids, OffsetDateTime ahora) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                UPDATE reportes_generados SET estado = 'fallida', ruta = NULL, terminado = :t,
                       error = 'La generación se interrumpió porque el servidor se reinició. Pídalo de nuevo.'
                WHERE id IN (:ids) AND estado IN ('pendiente', 'generando')""").param("t", ahora).param("ids", ids).update();
    }

    /** La tarea en segundo plano toma la generación diferida: pendiente → generando. */
    void iniciar(long id, OffsetDateTime ahora) {
        cambio(jdbc.sql("UPDATE reportes_generados SET estado = 'generando', iniciado = :t, intentos = intentos + 1 WHERE id = :id AND estado = 'pendiente'")
                .param("t", ahora).param("id", id).update(), id, "pendiente");
    }

    /** Generaciones del usuario en cola o en curso (tope por usuario del diferido). */
    int enCurso(long usuarioId) {
        return jdbc.sql("SELECT count(*) FROM reportes_generados WHERE usuario_id = :u AND estado IN ('pendiente', 'generando')").param("u", usuarioId)
                .query(Integer.class).single();
    }

    /** Una fila de «Descargas del día». */
    record Descarga(String codigo, String reporte, String titulo, String formato, String estado, OffsetDateTime creado, Long filas, Long tamano,
                    Integer msTotal, OffsetDateTime vence, String error) {
    }

    /**
     * Lo que el usuario pidió para descargar desde el inicio del día (Bogotá) y aún no vence, del más reciente al más antiguo. También lo
     * pedido ayer que terminó hoy (sigue disponible hasta el fin de este día) y lo que sigue en cola o en curso.
     */
    List<Descarga> descargasDelDia(long usuarioId, OffsetDateTime inicioDelDia, OffsetDateTime ahora) {
        return jdbc.sql("""
                SELECT codigo, reporte, titulo, formato, estado, creado, filas, tamano, ms_total, vence, error FROM reportes_generados
                WHERE usuario_id = :u AND formato IS NOT NULL AND estado <> 'vencida'
                  AND (estado IN ('pendiente', 'generando') OR (vence > :ahora AND (creado >= :desde OR terminado >= :desde)))
                ORDER BY creado DESC, id DESC LIMIT 200""").param("u", usuarioId).param("desde", inicioDelDia).param("ahora", ahora)
                .query((rs, n) -> new Descarga(rs.getString("codigo"), rs.getString("reporte"), rs.getString("titulo"), rs.getString("formato"),
                        rs.getString("estado"), rs.getObject("creado", OffsetDateTime.class), (Long) rs.getObject("filas"), (Long) rs.getObject("tamano"),
                        (Integer) rs.getObject("ms_total"), rs.getObject("vence", OffsetDateTime.class), rs.getString("error"))).list();
    }

    /** Pasa a «vencida» lo que terminó su día y devuelve id y ruta de cada archivo por borrar. */
    List<Map.Entry<Long, String>> vencer(OffsetDateTime ahora) {
        jdbc.sql("UPDATE reportes_generados SET estado = 'vencida' WHERE estado = 'lista' AND vence <= :ahora").param("ahora", ahora).update();
        return jdbc.sql("""
                SELECT id, ruta FROM reportes_generados WHERE estado = 'vencida' AND ruta IS NOT NULL AND archivo_borrado IS NULL ORDER BY id LIMIT 500""")
                .query((rs, n) -> Map.entry(rs.getLong("id"), rs.getString("ruta"))).list();
    }

    void archivoBorrado(long id, OffsetDateTime ahora) {
        jdbc.sql("UPDATE reportes_generados SET archivo_borrado = :t WHERE id = :id AND archivo_borrado IS NULL").param("t", ahora).param("id", id).update();
    }

    /** Una generación interrumpida (el servidor se reinició o la tarea murió). */
    record Huerfana(long id, String codigo, String formato, OffsetDateTime creado) {
    }

    /**
     * Marca «fallida» lo que quedó «generando» desde antes de {@code generandoDesde} o «pendiente» desde antes de
     * {@code pendienteDesde} (la cola vive en la memoria de la instancia: si se reinició, se perdió) y lo devuelve para borrar lo que
     * haya quedado a medias en el almacén.
     */
    List<Huerfana> reponerHuerfanas(OffsetDateTime generandoDesde, OffsetDateTime pendienteDesde, OffsetDateTime ahora) {
        return jdbc.sql("""
                UPDATE reportes_generados SET estado = 'fallida', ruta = NULL, terminado = :ahora,
                       error = 'La generación se interrumpió (reinicio o falla del servidor). Pídalo de nuevo.'
                WHERE (estado = 'generando' AND iniciado < :g) OR (estado = 'pendiente' AND creado < :p)
                RETURNING id, codigo, formato, creado""").param("g", generandoDesde).param("p", pendienteDesde).param("ahora", ahora)
                .query((rs, n) -> new Huerfana(rs.getLong("id"), rs.getString("codigo"), rs.getString("formato"), rs.getObject("creado", OffsetDateTime.class)))
                .list();
    }

    /** El cambio debe tocar exactamente una fila: si no, otra tarea cambió el estado mientras tanto y eso no se ignora. */
    private static void cambio(int filas, long id, String esperado) {
        if (filas != 1) {
            throw new IllegalStateException("El registro de reporte " + id + " ya no estaba en «" + esperado + "»");
        }
    }

    private static final String COLUMNAS = """
            id, codigo, reporte, version, titulo, usuario_id, usuario, alcance, parametros::text AS parametros, columnas::text AS columnas, formato, estado,
            ruta, tamano, filas, truncado, huella_datos, huella_archivo, ms_total, creado, vence""";

    Optional<Generado> porCodigo(String codigo) {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM reportes_generados WHERE codigo = :c").param("c", codigo).query(this::mapear).optional();
    }

    /** El registro del archivo con esa huella (el más reciente si se generó dos veces idéntico). */
    Optional<Generado> porHuellaArchivo(String huella) {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM reportes_generados WHERE huella_archivo = :h ORDER BY id DESC LIMIT 1").param("h", huella)
                .query(this::mapear).optional();
    }

    private Generado mapear(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        try {
            return new Generado(rs.getLong("id"), rs.getString("codigo"), rs.getString("reporte"), rs.getInt("version"), rs.getString("titulo"),
                    rs.getLong("usuario_id"), rs.getString("usuario"), rs.getString("alcance"),
                    json.readValue(rs.getString("parametros"), new TypeReference<Map<String, Object>>() {
                    }), json.readValue(rs.getString("columnas"), new TypeReference<List<String>>() {
                    }), rs.getString("formato"), rs.getString("estado"), rs.getString("ruta"), (Long) rs.getObject("tamano"), (Long) rs.getObject("filas"),
                    rs.getBoolean("truncado"), rs.getString("huella_datos"), rs.getString("huella_archivo"), (Integer) rs.getObject("ms_total"),
                    rs.getObject("creado", OffsetDateTime.class), rs.getObject("vence", OffsetDateTime.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Registro de reporte ilegible: " + rs.getString("codigo"), e);
        }
    }
}
