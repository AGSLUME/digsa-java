package co.com.agsamericas.digsa.analitica.homologacion;

import co.com.agsamericas.digsa.analitica.SugeridorHomologacion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.Exportador;
import co.com.agsamericas.digsa.plataforma.Reporte;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.Estado;
import co.com.agsamericas.digsa.reglas.Fechas;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consultas de la pantalla Homologación: versiones, tabla maestra paginada en el servidor (buscador y estado) con su cadena dato original
 * → fuente → regla → dato final, exportación en formatos abiertos (CSV y Excel con plataforma/Exportador), tablero de calidad sobre
 * registros de dispensación (los 6 porcentajes que suman 100 e indicadores del A4 §7 por cargue), seguimiento de las devoluciones al OPL
 * y trazabilidad del módulo en la bitácora.
 */
@Service
class ConsultasHomologacion {

    static final int POR_PAGINA = 50;
    /** Oportunidad del cargue (A4 §7; RESP L2217): horas máximas entre la dispensación más reciente y la aplicación. */
    static final int OPORTUNIDAD_HORAS = 48;

    /** Filtros de la tabla maestra. */
    record Filtro(Integer version, String q, String estado, boolean revision, Integer pagina) {
    }

    /** Filtros del tablero de calidad (periodo en meses aaaa-mm). */
    record FiltroCalidad(Integer version, Long cargue, String fuente, YearMonth desde, YearMonth hasta) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate plantilla;
    private final ObjectMapper json;
    private final ServicioHomologacion homologacion;
    private final SugeridorHomologacion sugeridor;
    private final Clock reloj;
    /** Transacción de solo lectura para leer las exportaciones con cursor. */
    private final TransactionTemplate lectura;

    ConsultasHomologacion(JdbcClient jdbc, JdbcTemplate plantilla, ObjectMapper json, ServicioHomologacion homologacion, SugeridorHomologacion sugeridor,
                          Clock reloj, PlatformTransactionManager transacciones) {
        this.jdbc = jdbc;
        this.plantilla = plantilla;
        this.json = json;
        this.homologacion = homologacion;
        this.sugeridor = sugeridor;
        this.reloj = reloj;
        this.lectura = new TransactionTemplate(transacciones);
        this.lectura.setReadOnly(true);
    }

    // ---------- Versiones ----------

    /** Versión pedida (que exista) o la vigente; nula si aún no hay ninguna. */
    Integer version(Integer pedida) {
        if (pedida == null) {
            return homologacion.vigente();
        }
        return jdbc.sql("SELECT id FROM hom_version WHERE id = :v AND estado = 'publicada'").param("v", pedida).query(Integer.class).optional()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("La versión de la tabla maestra no existe."));
    }

    List<Map<String, Object>> versiones() {
        return jdbc.sql("""
                SELECT id, numero, creada, motivo, cargue_id, responsable_texto, origenes, conteo::text, cambios, duracion_ms, detalle, fuentes::text
                FROM hom_version WHERE estado = 'publicada' ORDER BY numero DESC LIMIT 200""").query((rs, n) -> versionJson(rs)).list();
    }

    private Map<String, Object> versionJson(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getInt("id"));
        m.put("numero", rs.getInt("numero"));
        m.put("creada", rs.getObject("creada", OffsetDateTime.class));
        m.put("motivo", rs.getString("motivo"));
        m.put("cargue", rs.getObject("cargue_id"));
        m.put("responsable", rs.getString("responsable_texto"));
        m.put("origenes", rs.getInt("origenes"));
        Map<String, Long> conteo = new TreeMap<>();
        Instantanea.leer(json, rs.getString("conteo")).forEach((k, v) -> conteo.put(k, ((Number) v).longValue()));
        m.put("conteo", ServicioHomologacion.ordenado(conteo));
        m.put("cambios", rs.getInt("cambios"));
        m.put("duracionMs", rs.getObject("duracion_ms"));
        m.put("detalle", rs.getString("detalle"));
        m.put("fuentes", Instantanea.leer(json, rs.getString("fuentes")));
        return m;
    }

    /** Encabezado de la pantalla: versión vigente, versiones, fuentes publicadas, si opera y si hay sugeridor. */
    Map<String, Object> resumen(boolean puedeOperar) {
        Map<String, Object> r = new LinkedHashMap<>();
        List<Map<String, Object>> vs = versiones();
        r.put("vigente", vs.isEmpty() ? null : vs.getFirst());
        r.put("versiones", vs);
        Map<String, Instantanea.Fuente> pub = Instantanea.publicadas(jdbc);
        r.put("fuentesPublicadas", pub.size());
        r.put("datosDeEjemplo", pub.values().stream().anyMatch(Instantanea.Fuente::ejemplo));
        r.put("puedeOperar", puedeOperar);
        r.put("sugeridor", Map.of("disponible", sugeridor.disponible(), "proveedor", sugeridor.proveedor()));
        r.put("estados", estados());
        // Revisión A2: la tabla maestra quedó atrás frente a fuentes, reglas o catálogos (nulo si está al día)
        r.put("desactualizada", homologacion.desactualizada().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("desdeVersion", d.desdeVersion());
            m.put("cambios", d.cambios());
            m.put("motivo", d.motivo());
            return m;
        }).orElse(null));
        return r;
    }

    static List<Map<String, String>> estados() {
        List<Map<String, String>> l = new ArrayList<>();
        for (Estado e : Estado.values()) {
            l.add(Map.of("codigo", e.codigo(), "texto", e.texto()));
        }
        return l;
    }

    // ---------- Tabla maestra ----------

    private static final String DESDE = """
            FROM hom_tabla_maestra_version(:v) t
            JOIN hom_medicamento_origen o ON o.id = t.origen_id
            LEFT JOIN ref_version rv ON rv.id = t.fuente_version_id
            LEFT JOIN ref_fuente rf ON rf.codigo = t.fuente_codigo
            WHERE true
              AND (CAST(:estado AS text) IS NULL OR t.estado = :estado)
              AND (NOT :revision OR t.estado <> 'homologado')
              AND (CAST(:q AS text) IS NULL OR t.orig_descripcion ILIKE :q OR o.descripcion ILIKE :q OR t.orig_cum ILIKE :q OR t.fin_cum ILIKE :q
                   OR t.fin_dci ILIKE :q OR t.orig_principio ILIKE :q OR t.fin_atc ILIKE :q)""";

    private static String patron(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String s = q.strip();
        if (s.length() > 100) {
            throw ErrorNegocio.validacion("La búsqueda no puede pasar de 100 caracteres.");
        }
        return "%" + s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static void validarEstado(String estado) {
        if (estado != null && !estado.isBlank() && Estado.deCodigo(estado).isEmpty()) {
            throw ErrorNegocio.validacion("Estado no válido.");
        }
    }

    /** Página de la tabla maestra (50 por página) con los mismos filtros de la exportación. */
    Map<String, Object> tablaMaestra(Filtro f) {
        validarEstado(f.estado());
        Integer v = version(f.version());
        int pagina = f.pagina() == null || f.pagina() < 1 ? 1 : f.pagina();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("version", v == null ? null : versionDe(v));
        if (v == null) {
            r.put("filas", List.of());
            r.put("total", 0);
            r.put("pagina", 1);
            r.put("paginas", 0);
            return r;
        }
        String estado = f.estado() == null || f.estado().isBlank() ? null : f.estado();
        String q = patron(f.q());
        long total = jdbc.sql("SELECT count(*) " + DESDE).param("v", v).param("estado", estado).param("revision", f.revision()).param("q", q)
                .query(Long.class).single();
        List<Map<String, Object>> filas = jdbc.sql("SELECT t.*, o.descripcion AS descripcion_original, rf.nombre AS fuente_nombre, rv.numero AS fuente_numero, "
                        + "rv.ejemplo AS fuente_ejemplo " + DESDE + " ORDER BY CASE t.estado WHEN 'homologado' THEN 1 ELSE 0 END, t.orig_descripcion, "
                        + "t.origen_id LIMIT :l OFFSET :o")
                .param("v", v).param("estado", estado).param("revision", f.revision()).param("q", q).param("l", POR_PAGINA)
                .param("o", (long) (pagina - 1) * POR_PAGINA).query((rs, n) -> fila(rs)).list();
        r.put("filas", filas);
        r.put("total", total);
        r.put("pagina", pagina);
        r.put("paginas", (total + POR_PAGINA - 1) / POR_PAGINA);
        return r;
    }

    private Map<String, Object> versionDe(int v) {
        return jdbc.sql("""
                SELECT id, numero, creada, motivo, cargue_id, responsable_texto, origenes, conteo::text, cambios, duracion_ms, detalle, fuentes::text
                FROM hom_version WHERE id = :v""").param("v", v).query((rs, n) -> versionJson(rs)).single();
    }

    private Map<String, Object> fila(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("origenId", rs.getLong("origen_id"));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("cum", rs.getString("orig_cum"));
        o.put("descripcion", rs.getString("descripcion_original"));
        o.put("descripcionNormalizada", rs.getString("orig_descripcion"));
        o.put("principioActivo", rs.getString("orig_principio"));
        o.put("concentracion", rs.getString("orig_concentracion"));
        o.put("forma", rs.getString("orig_forma"));
        o.put("presentacion", rs.getString("orig_presentacion"));
        o.put("unidad", rs.getString("orig_unidad"));
        o.put("codigoSsfm", rs.getString("orig_ssfm"));
        o.put("atc", rs.getString("orig_atc"));
        m.put("original", o);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("codigo", rs.getString("fuente_codigo"));
        f.put("nombre", rs.getString("fuente_nombre"));
        f.put("version", rs.getObject("fuente_numero"));
        Date corte = rs.getDate("fuente_fecha_corte");
        f.put("fechaCorte", corte == null ? null : corte.toLocalDate().toString());
        f.put("ejemplo", rs.getObject("fuente_ejemplo"));
        m.put("fuente", rs.getString("fuente_codigo") == null ? null : f);
        m.put("regla", rs.getString("regla"));
        m.put("causa", rs.getString("causa"));
        m.put("reglasAplicadas", Instantanea.leerLista(json, rs.getString("reglas_aplicadas")));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("nombreComercial", rs.getString("fin_nombre_comercial"));
        d.put("dci", rs.getString("fin_dci"));
        d.put("principioActivo", rs.getString("fin_principio_activo"));
        d.put("concentracion", rs.getString("fin_concentracion"));
        d.put("forma", rs.getString("fin_forma"));
        d.put("presentacion", rs.getString("fin_presentacion"));
        d.put("unidad", rs.getString("fin_unidad"));
        d.put("cum", rs.getString("fin_cum"));
        d.put("codigoSsfm", rs.getString("fin_codigo_ssfm"));
        d.put("atc", rs.getString("fin_atc"));
        d.put("grupoFarmacologico", rs.getString("fin_grupo_farmacologico"));
        d.put("registroSanitario", rs.getString("fin_registro_sanitario"));
        m.put("final", rs.getString("fin_dci") == null && rs.getString("fin_cum") == null ? null : d);
        m.put("estado", rs.getString("estado"));
        m.put("estadoTexto", Estado.deCodigo(rs.getString("estado")).map(Estado::texto).orElse(rs.getString("estado")));
        m.put("diferencias", arreglo(rs.getArray("diferencias")));
        m.put("candidatos", arreglo(rs.getArray("candidatos")));
        m.put("devuelto", rs.getBoolean("devuelto"));
        m.put("accion", "homologado".equals(rs.getString("estado")) ? "Ver" : "Revisar");
        return m;
    }

    private static List<String> arreglo(Array a) throws SQLException {
        return a == null ? List.of() : List.of((String[]) a.getArray());
    }

    /** Detalle de un registro: la cadena completa, los cargues y registros de dispensación que lo usan y sus decisiones humanas. */
    Map<String, Object> detalle(long origenId, Integer version) {
        Integer v = version(version);
        if (v == null) {
            throw ErrorNegocio.noEncontrado("Aún no hay tabla maestra.");
        }
        Map<String, Object> r = jdbc.sql("SELECT t.*, o.descripcion AS descripcion_original, rf.nombre AS fuente_nombre, rv.numero AS fuente_numero, "
                        + "rv.ejemplo AS fuente_ejemplo FROM hom_tabla_maestra_version(:v) t JOIN hom_medicamento_origen o ON o.id = t.origen_id "
                        + "LEFT JOIN ref_version rv ON rv.id = t.fuente_version_id LEFT JOIN ref_fuente rf ON rf.codigo = t.fuente_codigo "
                        + "WHERE t.origen_id = :o")
                .param("v", v).param("o", origenId).query((rs, n) -> fila(rs)).optional()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("El dato no está en esa versión de la tabla maestra."));
        r.put("version", versionDe(v));
        r.put("cargues", jdbc.sql("""
                SELECT c.cargue_id, k.fuente_texto, k.aplicado_en, k.estado, sum(c.registros) AS registros, min(hv.numero) AS version_numero
                FROM hom_cargue_origen c JOIN ana_cargues k ON k.id = c.cargue_id AND k.estado = 'aplicado' JOIN hom_version hv ON hv.id = c.version_id
                WHERE c.medicamento_id = :o GROUP BY c.cargue_id, k.fuente_texto, k.aplicado_en, k.estado ORDER BY c.cargue_id DESC LIMIT 100""")
                .param("o", origenId).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("cargue", rs.getLong(1));
                    m.put("fuente", rs.getString(2));
                    m.put("aplicadoEn", rs.getObject(3, OffsetDateTime.class));
                    m.put("estado", rs.getString(4));
                    m.put("registros", rs.getLong(5));
                    m.put("versionTablaMaestra", rs.getInt(6));
                    return m;
                }).list());
        // La lista muestra los 100 cargues más recientes: el total se informa para que no parezca completa si hay más
        r.put("carguesTotal", jdbc.sql("""
                SELECT count(DISTINCT c.cargue_id) FROM hom_cargue_origen c JOIN ana_cargues k ON k.id = c.cargue_id AND k.estado = 'aplicado'
                WHERE c.medicamento_id = :o""").param("o", origenId).query(Long.class).single());
        r.put("registros", jdbc.sql("""
                SELECT coalesce(sum(c.registros), 0) FROM hom_cargue_origen c JOIN ana_cargues k ON k.id = c.cargue_id
                WHERE c.medicamento_id = :o AND k.estado = 'aplicado'""").param("o", origenId).query(Long.class).single());
        r.put("revisiones", jdbc.sql("""
                SELECT r.id, r.accion, r.cum_elegido, r.motivo, r.responsable_texto, r.fecha, hv.numero FROM hom_revision r
                JOIN hom_version hv ON hv.id = r.version_id WHERE r.origen_id = :o ORDER BY r.id DESC""").param("o", origenId).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong(1));
                    m.put("accion", rs.getString(2));
                    m.put("cum", rs.getString(3));
                    m.put("motivo", rs.getString(4));
                    m.put("responsable", rs.getString(5));
                    m.put("fecha", rs.getObject(6, OffsetDateTime.class));
                    m.put("version", rs.getInt(7));
                    return m;
                }).list());
        return r;
    }

    /** Columnas de la exportación de la tabla maestra (todas las del requisito: original, fuente, regla y las 13 finales). */
    static final List<String> COLUMNAS_EXPORTACION = List.of("Id del dato original", "CUM original", "Descripción original", "Principio activo original",
            "Concentración original", "Forma original", "Presentación original", "Unidad original", "Código SSFM original", "ATC original",
            "Fuente consultada", "Versión de la fuente", "Fecha de corte de la fuente", "Regla aplicada", "Causa", "Reglas aplicadas",
            "Nombre comercial", "DCI", "Principio activo", "Concentración", "Forma farmacéutica", "Presentación", "Unidad", "CUM", "Código SSFM",
            "ATC", "CIE-10", "Grupo farmacológico", "Registro sanitario", "Estado", "Diferencias", "Devuelto al OPL");

    /** Reporte de la tabla maestra filtrada (todas las filas) con la versión, su fecha y las versiones de las fuentes en el encabezado. */
    Reporte reporteTablaMaestra(Filtro f, String usuario) {
        validarEstado(f.estado());
        Integer v = version(f.version());
        if (v == null) {
            throw ErrorNegocio.noEncontrado("Aún no hay tabla maestra para exportar.");
        }
        Map<String, Object> ver = versionDe(v);
        String estado = f.estado() == null || f.estado().isBlank() ? null : f.estado();
        List<List<Object>> filas = jdbc.sql("SELECT t.*, o.descripcion AS descripcion_original, rf.nombre AS fuente_nombre, rv.numero AS fuente_numero, "
                        + "rv.ejemplo AS fuente_ejemplo " + DESDE + " ORDER BY t.origen_id")
                .param("v", v).param("estado", estado).param("revision", f.revision()).param("q", patron(f.q())).query((rs, n) -> {
                    List<Object> x = new ArrayList<>();
                    x.add(rs.getLong("origen_id"));
                    x.add(rs.getString("orig_cum"));
                    x.add(rs.getString("descripcion_original"));
                    x.add(rs.getString("orig_principio"));
                    x.add(rs.getString("orig_concentracion"));
                    x.add(rs.getString("orig_forma"));
                    x.add(rs.getString("orig_presentacion"));
                    x.add(rs.getString("orig_unidad"));
                    x.add(rs.getString("orig_ssfm"));
                    x.add(rs.getString("orig_atc"));
                    x.add(rs.getString("fuente_codigo") == null ? "Sin fuente" : rs.getString("fuente_nombre"));
                    x.add(rs.getObject("fuente_numero") == null ? null : "v" + rs.getObject("fuente_numero"));
                    Date c = rs.getDate("fuente_fecha_corte");
                    x.add(c == null ? null : c.toLocalDate());
                    x.add(rs.getString("regla"));
                    x.add(rs.getString("causa"));
                    x.add(pasos(rs.getString("reglas_aplicadas")));
                    x.add(rs.getString("fin_nombre_comercial"));
                    x.add(rs.getString("fin_dci"));
                    x.add(rs.getString("fin_principio_activo"));
                    x.add(rs.getString("fin_concentracion"));
                    x.add(rs.getString("fin_forma"));
                    x.add(rs.getString("fin_presentacion"));
                    x.add(rs.getString("fin_unidad"));
                    x.add(rs.getString("fin_cum"));
                    x.add(rs.getString("fin_codigo_ssfm"));
                    x.add(rs.getString("fin_atc"));
                    x.add(null); // CIE-10: es por registro de dispensación, no por medicamento (tabla de la CIE-10 aparte)
                    x.add(rs.getString("fin_grupo_farmacologico"));
                    x.add(rs.getString("fin_registro_sanitario"));
                    x.add(Estado.deCodigo(rs.getString("estado")).map(Estado::texto).orElse(rs.getString("estado")));
                    x.add(String.join(" | ", arreglo(rs.getArray("diferencias"))));
                    x.add(rs.getBoolean("devuelto") ? "Sí" : "No");
                    return x;
                }).list();
        Map<String, String> filtros = new LinkedHashMap<>();
        filtros.put("Versión de la tabla maestra", "v" + ver.get("numero"));
        filtros.put("Fecha de la versión", String.valueOf(ver.get("creada")));
        filtros.put("Fuentes", fuentesTexto(ver.get("fuentes")));
        if (f.q() != null && !f.q().isBlank()) {
            filtros.put("Búsqueda", f.q().strip());
        }
        if (estado != null) {
            filtros.put("Estado", Estado.deCodigo(estado).map(Estado::texto).orElse(estado));
        }
        filtros.put("Registros", Integer.toString(filas.size()));
        return new Reporte("Tabla maestra normalizada v" + ver.get("numero"), ZonedDateTime.now(reloj.withZone(Fechas.BOGOTA)), usuario, filtros,
                COLUMNAS_EXPORTACION, filas);
    }

    @SuppressWarnings("unchecked")
    private static String fuentesTexto(Object fuentes) {
        if (!(fuentes instanceof Map<?, ?> m) || m.isEmpty()) {
            return "Sin fuentes publicadas";
        }
        List<String> l = new ArrayList<>();
        ((Map<String, Object>) m).forEach((k, v) -> {
            Map<String, Object> x = (Map<String, Object>) v;
            l.add(k + " v" + x.get("numero") + " (corte " + x.get("fechaCorte") + (Boolean.TRUE.equals(x.get("ejemplo")) ? ", datos de ejemplo" : "") + ")");
        });
        return String.join("; ", l);
    }

    private String pasos(String jsonArreglo) {
        List<?> l = Instantanea.leerLista(json, jsonArreglo);
        List<String> partes = new ArrayList<>();
        for (Object o : l) {
            if (o instanceof Map<?, ?> p) {
                partes.add(p.get("regla") + ": " + p.get("antes") + " → " + p.get("despues"));
            }
        }
        return String.join(" | ", partes);
    }

    // ---------- Tablero de calidad ----------

    /**
     * Los 6 porcentajes del mockup sobre registros de dispensación (y los datos originales distintos), con dos decimales que suman 100;
     * filtros por versión, cargue, fuente y periodo; indicadores del A4 §7 por cargue y la CIE-10 de la versión.
     */
    Map<String, Object> calidad(FiltroCalidad f) {
        Integer v = version(f.version());
        if (f.fuente() != null && !List.of("archivo", "ws_opl").contains(f.fuente())) {
            throw ErrorNegocio.validacion("Fuente no válida.");
        }
        if (f.desde() != null && f.hasta() != null && f.desde().isAfter(f.hasta())) {
            throw ErrorNegocio.validacion("El periodo «desde» es posterior a «hasta».");
        }
        Map<Estado, Long> registros = new EnumMap<>(Estado.class);
        Map<Estado, Long> origenes = new EnumMap<>(Estado.class);
        long[] fuera = {0, 0};
        if (v != null) {
            // Los registros cuyo dato original no existía en la versión elegida (anterior al cargue) van aparte, no como «pendientes»
            jdbc.sql("""
                    SELECT coalesce(t.estado, '-') AS estado, sum(m.registros), count(DISTINCT m.medicamento_id)
                    FROM hom_cargue_mes m
                    JOIN ana_cargues k ON k.id = m.cargue_id
                    LEFT JOIN hom_tabla_maestra_version(:v) t ON t.origen_id = m.medicamento_id
                    WHERE (CASE WHEN CAST(:cargue AS bigint) IS NULL THEN m.cargue_id IN (SELECT cargue_id FROM ana_cargues_visibles(:corte))
                                ELSE m.cargue_id = :cargue AND k.estado = 'aplicado' END)
                      AND (CAST(:fuente AS text) IS NULL OR k.fuente = :fuente)
                      AND (CAST(:desde AS date) IS NULL OR m.mes >= :desde)
                      AND (CAST(:hasta AS date) IS NULL OR m.mes <= :hasta)
                    GROUP BY 1""")
                    .param("v", v).param("cargue", f.cargue()).param("corte", OffsetDateTime.now(reloj)).param("fuente", f.fuente())
                    .param("desde", f.desde() == null ? null : f.desde().atDay(1)).param("hasta", f.hasta() == null ? null : f.hasta().atDay(1))
                    .query(rs -> {
                        Estado e = Estado.deCodigo(rs.getString(1)).orElse(null);
                        if (e == null) {
                            fuera[0] += rs.getLong(2);
                            fuera[1] += rs.getLong(3);
                            return;
                        }
                        registros.merge(e, rs.getLong(2), Long::sum);
                        origenes.merge(e, rs.getLong(3), Long::sum);
                    });
        }
        Map<Estado, BigDecimal> pct = AnaliticaHomologacion.porcentajes(registros);
        List<Map<String, Object>> kpis = new ArrayList<>();
        for (Estado e : Estado.values()) {
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("estado", e.codigo());
            k.put("texto", e.texto());
            k.put("registros", registros.getOrDefault(e, 0L));
            k.put("origenes", origenes.getOrDefault(e, 0L));
            k.put("porcentaje", pct.get(e));
            kpis.add(k);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("version", v == null ? null : versionDe(v));
        r.put("kpis", kpis);
        r.put("registros", registros.values().stream().mapToLong(Long::longValue).sum());
        r.put("origenes", origenes.values().stream().mapToLong(Long::longValue).sum());
        r.put("registrosFueraDeVersion", fuera[0]);
        r.put("origenesFueraDeVersion", fuera[1]);
        r.put("cargues", indicadores(v, f));
        r.put("cie10", v == null ? List.of() : jdbc.sql("""
                SELECT estado, count(*) FROM hom_tabla_cie10_version(:v) GROUP BY estado ORDER BY estado""").param("v", v)
                .query((rs, n) -> Map.of("estado", rs.getString(1), "codigos", rs.getLong(2))).list());
        r.put("oportunidadMaximaHoras", OPORTUNIDAD_HORAS);
        return r;
    }

    /** Indicadores del A4 §7 de los últimos cargues aplicados: válidos, rechazados, duplicados, consistencia y oportunidad. */
    private List<Map<String, Object>> indicadores(Integer v, FiltroCalidad f) {
        return jdbc.sql("""
                WITH h AS MATERIALIZED (SELECT t.origen_id FROM hom_tabla_maestra_version(:v) t WHERE t.estado = 'homologado'),
                     k AS (SELECT * FROM ana_cargues k
                           WHERE k.estado = 'aplicado' AND (CAST(:cargue AS bigint) IS NULL OR k.id = :cargue)
                             AND (CAST(:fuente AS text) IS NULL OR k.fuente = :fuente)
                           ORDER BY k.aplicado_en DESC LIMIT 20)
                SELECT k.id, k.aplicado_en, k.fuente_texto, k.fuente, k.recibidos, k.procesados, k.rechazados,
                       coalesce((k.motivos ->> 'duplicado_exacto')::bigint, 0) + coalesce((k.motivos ->> 'duplicado_llave')::bigint, 0) AS duplicados,
                       k.periodo_hasta,
                       (SELECT coalesce(sum(m.registros), 0) FROM hom_cargue_mes m JOIN h ON h.origen_id = m.medicamento_id
                        WHERE m.cargue_id = k.id) AS homologados
                FROM k ORDER BY k.aplicado_en DESC""")
                .param("v", v == null ? -1 : v).param("cargue", f.cargue()).param("fuente", f.fuente()).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("cargue", rs.getLong(1));
                    OffsetDateTime aplicado = rs.getObject(2, OffsetDateTime.class);
                    m.put("aplicadoEn", aplicado);
                    m.put("fuente", rs.getString(3));
                    m.put("recibidos", rs.getLong(5));
                    long procesados = rs.getLong(6);
                    m.put("validos", procesados);
                    m.put("rechazados", rs.getLong(7));
                    m.put("duplicados", rs.getLong(8));
                    long homologados = rs.getLong(10);
                    m.put("consistencia", procesados == 0 ? BigDecimal.ZERO.setScale(2)
                            : BigDecimal.valueOf(homologados * 100).divide(BigDecimal.valueOf(procesados), 2, RoundingMode.HALF_UP));
                    Date hasta = rs.getDate(9);
                    if (hasta != null && aplicado != null) {
                        // Horas desde el final del día de la dispensación más reciente (hora de Colombia) hasta la aplicación
                        long horas = Duration.between(hasta.toLocalDate().plusDays(1).atStartOfDay(Fechas.BOGOTA).toOffsetDateTime(), aplicado).toHours();
                        m.put("oportunidadHoras", Math.max(0, horas));
                        m.put("oportunidadCumple", horas <= OPORTUNIDAD_HORAS);
                    } else {
                        m.put("oportunidadHoras", null);
                        m.put("oportunidadCumple", null);
                    }
                    return m;
                }).list();
    }

    /** Reporte del tablero de calidad (KPI e indicadores por cargue) con los filtros en el encabezado. */
    @SuppressWarnings("unchecked")
    Reporte reporteCalidad(FiltroCalidad f, String usuario) {
        Map<String, Object> c = calidad(f);
        List<List<Object>> filas = new ArrayList<>();
        for (Map<String, Object> k : (List<Map<String, Object>>) c.get("kpis")) {
            filas.add(List.of("Estado", k.get("texto"), k.get("registros"), k.get("origenes"), k.get("porcentaje"), ""));
        }
        for (Map<String, Object> k : (List<Map<String, Object>>) c.get("cargues")) {
            filas.add(List.of("Cargue " + k.get("cargue"), String.valueOf(k.get("fuente")), k.get("validos"), k.get("rechazados"), k.get("consistencia"),
                    "duplicados " + k.get("duplicados") + " · oportunidad " + (k.get("oportunidadHoras") == null ? "—" : k.get("oportunidadHoras") + " h")));
        }
        Map<String, String> filtros = new LinkedHashMap<>();
        Map<String, Object> ver = (Map<String, Object>) c.get("version");
        filtros.put("Versión de la tabla maestra", ver == null ? "—" : "v" + ver.get("numero"));
        filtros.put("Fuentes", ver == null ? "—" : fuentesTexto(ver.get("fuentes")));
        filtros.put("Cargue", f.cargue() == null ? "Todos los visibles" : String.valueOf(f.cargue()));
        filtros.put("Fuente del insumo", f.fuente() == null ? "Todas" : f.fuente());
        filtros.put("Periodo", (f.desde() == null ? "inicio" : f.desde().toString()) + " – " + (f.hasta() == null ? "fin" : f.hasta().toString()));
        filtros.put("Registros procesados", String.valueOf(c.get("registros")));
        return new Reporte("Tablero de calidad de la homologación", ZonedDateTime.now(reloj.withZone(Fechas.BOGOTA)), usuario, filtros,
                List.of("Indicador", "Detalle", "Registros / válidos", "Datos originales / rechazados", "% / consistencia", "Observación"), filas);
    }

    // ---------- Devoluciones al OPL ----------

    /** Filas de la pantalla del seguimiento (las más recientes); la exportación las lleva todas ({@link #reporteDevoluciones}). */
    static final int DEVOLUCIONES_EN_PANTALLA = 500;

    /** Nombre legible de cada estado de una devolución. */
    static final Map<String, String> ESTADOS_DEVOLUCION = Map.of("devuelta", "Devuelta", "persiste", "Persiste", "corregida", "Corregida por el OPL",
            "resuelta", "Resuelta en revisión");

    private static final String DEVOLUCIONES = """
            SELECT d.id, d.origen_id, o.descripcion, o.cum_original, d.motivo, d.fecha, u.usuario, d.estado, d.fuente, d.periodo, d.cargue_id,
                   d.corregida_cargue_id, d.actualizado
            FROM hom_devolucion d JOIN hom_medicamento_origen o ON o.id = d.origen_id JOIN usuarios u ON u.id = d.usuario_id
            ORDER BY d.id DESC""";

    private static Map<String, Object> devolucion(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong(1));
        m.put("origenId", rs.getLong(2));
        m.put("descripcion", rs.getString(3));
        m.put("cum", rs.getString(4));
        m.put("motivo", rs.getString(5));
        m.put("fecha", rs.getObject(6, OffsetDateTime.class));
        m.put("usuario", rs.getString(7));
        m.put("estado", rs.getString(8));
        m.put("estadoTexto", ESTADOS_DEVOLUCION.getOrDefault(rs.getString(8), rs.getString(8)));
        m.put("fuente", rs.getString(9));
        Date p = rs.getDate(10);
        m.put("periodo", p == null ? null : YearMonth.from(p.toLocalDate()).toString());
        m.put("cargue", rs.getObject(11));
        m.put("corregidaEnCargue", rs.getObject(12));
        m.put("actualizado", rs.getObject(13, OffsetDateTime.class));
        return m;
    }

    Map<String, Object> devoluciones() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("filas", jdbc.sql(DEVOLUCIONES + " LIMIT " + DEVOLUCIONES_EN_PANTALLA).query((rs, n) -> devolucion(rs)).list());
        r.put("total", jdbc.sql("SELECT count(*) FROM hom_devolucion").query(Long.class).single());
        r.put("mostradas", DEVOLUCIONES_EN_PANTALLA);
        r.put("abiertas", jdbc.sql("""
                SELECT coalesce(fuente, '—'), coalesce(to_char(periodo, 'YYYY-MM'), '—'), count(*) FROM hom_devolucion
                WHERE estado IN ('devuelta', 'persiste') GROUP BY 1, 2 ORDER BY 2 DESC, 1""")
                .query((rs, n) -> Map.of("fuente", rs.getString(1), "periodo", rs.getString(2), "abiertas", rs.getLong(3))).list());
        r.put("correccionesPorMes", jdbc.sql("""
                SELECT to_char(actualizado AT TIME ZONE 'America/Bogota', 'YYYY-MM'), count(*) FROM hom_devolucion WHERE estado = 'corregida'
                GROUP BY 1 ORDER BY 1""").query((rs, n) -> Map.of("mes", rs.getString(1), "corregidas", rs.getLong(2))).list());
        return r;
    }

    /**
     * Todas las devoluciones para enviarlas al OPL (sin tope: la pantalla muestra las últimas, el archivo las lleva completas), leídas
     * con cursor (fetchSize) en una transacción de solo lectura.
     */
    Reporte reporteDevoluciones(String usuario) {
        List<List<Object>> filas = new ArrayList<>();
        lectura.executeWithoutResult(t -> plantilla.query(con -> {
            var ps = con.prepareStatement(DEVOLUCIONES);
            ps.setFetchSize(1000);
            return ps;
        }, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            Map<String, Object> d = devolucion(rs);
            filas.add(java.util.Arrays.asList(d.get("id"), d.get("descripcion"), d.get("cum"), d.get("motivo"), d.get("fecha"), d.get("usuario"),
                    d.get("estadoTexto"), d.get("fuente"), d.get("periodo"), d.get("cargue"), d.get("corregidaEnCargue")));
        }));
        return new Reporte("Devoluciones al OPL", ZonedDateTime.now(reloj.withZone(Fechas.BOGOTA)), usuario, Map.of("Registros", Integer.toString(filas.size())),
                List.of("Id", "Dato original", "CUM", "Motivo", "Fecha", "Usuario", "Estado", "Fuente", "Periodo", "Cargue", "Corregida en el cargue"),
                filas);
    }

    // ---------- Trazabilidad del módulo ----------

    /** Últimos eventos de la homologación y de los cargues del OPL en la bitácora, con el usuario y su perfil (o «Proceso automático»). */
    List<Map<String, Object>> trazabilidad(int limite, boolean incluirCargues) {
        int l = Math.max(1, Math.min(limite, 200));
        return jdbc.sql("""
                SELECT b.fecha, b.tipo, b.descripcion, coalesce(b.usuario, 'Proceso automático'),
                       (SELECT string_agg(p.nombre, ', ' ORDER BY p.id) FROM usuarios_perfiles up JOIN perfiles p ON p.id = up.perfil_id
                        WHERE up.usuario_id = b.usuario_id)
                FROM log_transacciones b
                WHERE b.fecha >= :desde AND (b.tipo LIKE 'homologacion\\_%' OR :cargues AND b.tipo LIKE 'analitica\\_cargue\\_%')
                ORDER BY b.fecha DESC, b.id DESC LIMIT :l""")
                .param("desde", OffsetDateTime.now(reloj).minusDays(400)).param("cargues", incluirCargues).param("l", l).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("fecha", rs.getObject(1, OffsetDateTime.class));
                    m.put("tipo", rs.getString(2));
                    m.put("descripcion", rs.getString(3));
                    m.put("usuario", rs.getString(4));
                    m.put("perfil", rs.getString(5));
                    return m;
                }).list();
    }

    static LocalDate primerDia(YearMonth m) {
        return m == null ? null : m.atDay(1);
    }
}
