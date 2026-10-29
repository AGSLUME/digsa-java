package co.com.agsamericas.digsa.analitica.homologacion;

import co.com.agsamericas.digsa.analitica.PropiedadesAnalitica;
import co.com.agsamericas.digsa.analitica.ingesta.LectorTabular;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.GrupoCum;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.PresentacionCarga;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.PrincipioCarga;
import co.com.agsamericas.digsa.reglas.AnaliticaHomologacion.RolCarga;
import co.com.agsamericas.digsa.reglas.AnaliticaIngesta;
import co.com.agsamericas.digsa.reglas.Huellas;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.copy.PGCopyOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Referencias oficiales versionadas (analitica-homologacion, «Registro de fuentes oficiales», «Carga de una versión por archivo» y
 * «CUM INVIMA desde sus cuatro conjuntos»): la carga se hace desde la pantalla subiendo el archivo ya descargado (CSV, TXT, xlsx, .gz o
 * .zip con el formato público de cada fuente; nunca internet en caliente), se valida (encabezados, llave, fechas, números y conteos) y la
 * versión queda «validada» con su informe; solo al publicar pasa a vigente, en una transacción que marca la anterior «reemplazada». Las
 * listas normativas que solo existen en PDF se editan en la pantalla y cada cambio es una versión publicada con su traza.
 *
 * <p>Una carga a la vez (pg_try_advisory_lock). El CUM se copia a un staging UNLOGGED (COPY), se agrupa por CUM en Java con la regla pura
 * (una fila del INVIMA por principio activo y rol) y se guarda con COPY en presentación, principios y roles.
 */
@Service
class ServicioReferencias {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioReferencias.class);
    /** Candado de las cargas de referencias (pg_try_advisory_lock de sesión). */
    static final long CANDADO = 0x5245465F43415247L;
    /** Filas rechazadas que se listan en el informe (el resto solo se cuenta). */
    private static final int MAX_LISTADAS = 50;

    /** Columnas de cada formato (comparables con {@link AnaliticaHomologacion#columna}). */
    static final List<String> CIE10_OBLIGATORIAS = List.of("codigo", "nombre");
    static final List<String> PRECIO_OBLIGATORIAS = cols("cum", "circular_cnpmdm",
            "precio_maximo_de_venta_transaccion_primaria_secundaria_y_final_institucional");
    static final List<String> VITALES_OBLIGATORIAS = cols("nombre_comercial_", "principio_activo1");
    static final List<String> LISTA_OBLIGATORIAS = cols("principio_activo", "norma", "fecha_norma");
    static final List<String> SSFM_OBLIGATORIAS = cols("codigo_ssfm");

    private static List<String> cols(String... s) {
        return java.util.Arrays.stream(s).map(AnaliticaHomologacion::columna).toList();
    }

    /** Rechazo de la versión completa (encabezados, archivo ilegible o sin filas válidas). */
    private static final class Rechazo extends RuntimeException {
        Rechazo(String mensaje) {
            super(mensaje, null, false, false);
        }
    }

    /** Fuente sembrada en V61. */
    record Fuente(int id, String codigo, String nombre, String tipo, String conjunto, String lista) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate plantilla;
    private final DataSource ds;
    private final Almacen almacen;
    private final RegistroBitacora bitacora;
    private final ObjectMapper json;
    private final Clock reloj;
    private final ConfiguracionDigsa cfg;
    private final PropiedadesAnalitica propiedades;
    private final TransactionTemplate tx;

    ServicioReferencias(JdbcClient jdbc, JdbcTemplate plantilla, DataSource ds, Almacen almacen, RegistroBitacora bitacora, ObjectMapper json,
                        Clock reloj, ConfiguracionDigsa cfg, PropiedadesAnalitica propiedades, PlatformTransactionManager transacciones) {
        this.jdbc = jdbc;
        this.plantilla = plantilla;
        this.ds = ds;
        this.almacen = almacen;
        this.bitacora = bitacora;
        this.json = json;
        this.reloj = reloj;
        this.cfg = cfg;
        this.propiedades = propiedades;
        this.tx = new TransactionTemplate(transacciones);
    }

    // ---------- Consulta ----------

    Fuente fuente(String codigo) {
        return jdbc.sql("SELECT id, codigo, nombre, tipo, conjunto, lista FROM ref_fuente WHERE codigo = :c").param("c", codigo)
                .query((rs, n) -> new Fuente(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)))
                .optional().orElseThrow(() -> ErrorNegocio.noEncontrado("La fuente no existe."));
    }

    /** Fuentes con su versión publicada y las últimas versiones cargadas. */
    List<Map<String, Object>> fuentes() {
        List<Map<String, Object>> l = jdbc.sql("""
                SELECT id, codigo, nombre, tipo, conjunto, lista, dataset, url, licencia, atribucion, frecuencia FROM ref_fuente ORDER BY orden""")
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt(1));
                    m.put("codigo", rs.getString(2));
                    m.put("nombre", rs.getString(3));
                    m.put("tipo", rs.getString(4));
                    m.put("conjunto", rs.getString(5));
                    m.put("lista", rs.getString(6));
                    m.put("dataset", rs.getString(7));
                    m.put("url", rs.getString(8));
                    m.put("licencia", rs.getString(9));
                    m.put("atribucion", rs.getString(10));
                    m.put("frecuencia", rs.getString(11));
                    return m;
                }).list();
        Map<Integer, List<Map<String, Object>>> versiones = new HashMap<>();
        jdbc.sql("""
                SELECT x.* FROM (SELECT v.*, row_number() OVER (PARTITION BY v.fuente_id ORDER BY v.numero DESC) AS r FROM ref_version v) x
                WHERE x.r <= 6 OR x.estado = 'publicada' ORDER BY x.fuente_id, x.numero DESC""")
                .query(rs -> {
                    versiones.computeIfAbsent(rs.getInt("fuente_id"), k -> new ArrayList<>()).add(version(rs));
                });
        for (Map<String, Object> f : l) {
            List<Map<String, Object>> vs = versiones.getOrDefault((Integer) f.get("id"), List.of());
            f.put("versiones", vs);
            f.put("publicada", vs.stream().filter(v -> "publicada".equals(v.get("estado"))).findFirst().orElse(null));
        }
        return l;
    }

    private Map<String, Object> version(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getInt("id"));
        m.put("numero", rs.getInt("numero"));
        m.put("estado", rs.getString("estado"));
        m.put("fechaCorte", rs.getDate("fecha_corte").toLocalDate().toString());
        m.put("cargado", rs.getObject("cargado", OffsetDateTime.class));
        m.put("responsable", rs.getString("responsable_texto"));
        m.put("origen", rs.getString("origen"));
        m.put("nombreArchivo", rs.getString("nombre_archivo"));
        m.put("sha256", rs.getString("sha256"));
        m.put("filasLeidas", rs.getInt("filas_leidas"));
        m.put("filasValidas", rs.getInt("filas_validas"));
        m.put("ejemplo", rs.getBoolean("ejemplo"));
        m.put("informe", Instantanea.leer(json, rs.getString("informe")));
        m.put("motivo", rs.getString("motivo"));
        m.put("publicadaEn", rs.getObject("publicada_en", OffsetDateTime.class));
        m.put("reemplazadaEn", rs.getObject("reemplazada_en", OffsetDateTime.class));
        return m;
    }

    Map<String, Object> version(int id) {
        return jdbc.sql("SELECT * FROM ref_version WHERE id = :id").param("id", id).query((rs, n) -> version(rs)).optional()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("La versión no existe."));
    }

    // ---------- Carga por archivo ----------

    /**
     * Sube el archivo de una fuente, lo valida y deja la versión «validada» (o «rechazada» con el motivo, 422).
     *
     * @param fechaCorte fecha de actualización de la fuente (obligatoria)
     * @param ejemplo    datos de ejemplo ficticios (pruebas)
     */
    Map<String, Object> cargar(String codigo, MultipartFile archivo, LocalDate fechaCorte, boolean ejemplo, UsuarioSesion quien, String responsable)
            throws IOException {
        Fuente f = fuente(codigo);
        if (fechaCorte == null) {
            throw ErrorNegocio.validacion("Indique la fecha de actualización de la fuente.", "fechaCorte");
        }
        if (fechaCorte.isAfter(LocalDate.now(reloj))) {
            throw ErrorNegocio.validacion("La fecha de actualización de la fuente no puede ser futura.", "fechaCorte");
        }
        if (archivo == null || archivo.isEmpty()) {
            throw ErrorNegocio.validacion("Adjunte el archivo de la fuente.", "archivo");
        }
        String nombre = archivo.getOriginalFilename() == null ? "referencia.csv" : Path.of(archivo.getOriginalFilename()).getFileName().toString();
        String formato = AnaliticaIngesta.formatoDe(nombre)
                .orElseThrow(() -> ErrorNegocio.validacion("Formato no aceptado: use CSV, TXT, xlsx, .gz o .zip.", "archivo"));
        String ruta = "homologacion/referencias/" + codigo.toLowerCase(Locale.ROOT) + "/" + Huellas.secreto(12) + "." + formato;
        String sha;
        try (InputStream in = archivo.getInputStream(); OutputStream out = almacen.escribir(ruta)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (DigestOutputStream d = new DigestOutputStream(out, md)) {
                in.transferTo(d);
            }
            sha = HexFormat.of().formatHex(md.digest());
        } catch (IOException | RuntimeException | NoSuchAlgorithmException e) {
            almacen.descartar(ruta);
            throw e instanceof IOException io ? io : new IOException(e);
        }
        Integer repetida = jdbc.sql("""
                SELECT numero FROM ref_version WHERE fuente_id = :f AND sha256 = :sha AND estado IN ('validada', 'publicada') ORDER BY numero DESC LIMIT 1""")
                .param("f", f.id()).param("sha", sha).query(Integer.class).optional().orElse(null);
        if (repetida != null) {
            almacen.descartar(ruta);
            throw ErrorNegocio.conflicto("Este archivo ya está cargado como versión " + repetida + ".");
        }
        try (Connection candado = ds.getConnection()) {
            if (!candado(candado, "SELECT pg_try_advisory_lock(?)")) {
                almacen.descartar(ruta);
                throw ErrorNegocio.conflicto("Hay otra carga de referencias en curso; intente de nuevo en unos minutos.");
            }
            try {
                return cargarConCandado(f, nombre, ruta, sha, fechaCorte, ejemplo, quien, responsable);
            } finally {
                candado(candado, "SELECT pg_advisory_unlock(?)");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean candado(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, CANDADO);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private Map<String, Object> cargarConCandado(Fuente f, String nombre, String ruta, String sha, LocalDate fechaCorte, boolean ejemplo,
                                                 UsuarioSesion quien, String responsable) throws IOException {
        int versionId;
        try {
            versionId = insertarVersion(f, nombre, ruta, sha, fechaCorte, ejemplo, quien, responsable);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            almacen.descartar(ruta);
            throw ErrorNegocio.conflicto("Otra persona creó una versión de esta fuente al mismo tiempo; intente de nuevo.");
        }
        return cargarVersion(f, versionId, nombre, ruta, fechaCorte, ejemplo, quien);
    }

    private int insertarVersion(Fuente f, String nombre, String ruta, String sha, LocalDate fechaCorte, boolean ejemplo, UsuarioSesion quien,
                                String responsable) {
        return jdbc.sql("""
                INSERT INTO ref_version (fuente_id, numero, fecha_corte, cargado, usuario_id, responsable_texto, origen, nombre_archivo, almacen_ruta,
                                         sha256, ejemplo)
                VALUES (:f, (SELECT coalesce(max(numero), 0) + 1 FROM ref_version WHERE fuente_id = :f), :corte, :ahora, :u, :resp, 'archivo',
                        :nombre, :ruta, :sha, :ejemplo)
                RETURNING id""")
                .param("f", f.id()).param("corte", fechaCorte).param("ahora", OffsetDateTime.now(reloj)).param("u", quien.id())
                .param("resp", responsable).param("nombre", ServicioHomologacion.cortar(nombre, 255)).param("ruta", ruta).param("sha", sha)
                .param("ejemplo", ejemplo).query(Integer.class).single();
    }

    private Map<String, Object> cargarVersion(Fuente f, int versionId, String nombre, String ruta, LocalDate fechaCorte, boolean ejemplo,
                                              UsuarioSesion quien) throws IOException {
        Map<String, Object> informe = new LinkedHashMap<>();
        int[] filas;
        try {
            filas = switch (f.tipo()) {
                case "cum" -> cargarCum(f, versionId, nombre, ruta, informe);
                default -> cargarTabla(f, versionId, nombre, ruta, informe);
            };
            // Vitales no disponibles: el archivo original trae el solicitante (la tabla no lo guarda); se conserva solo el SHA-256
            boolean conservar = !"vitales".equals(f.tipo());
            jdbc.sql("""
                    UPDATE ref_version SET estado = 'validada', filas_leidas = :l, filas_validas = :v, informe = CAST(:i AS jsonb),
                           almacen_ruta = CASE WHEN :conservar THEN almacen_ruta END
                    WHERE id = :id AND estado = 'cargando'""")
                    .param("l", filas[0]).param("v", filas[1]).param("i", aJson(informe)).param("conservar", conservar).param("id", versionId).update();
            if (!conservar) {
                almacen.descartar(ruta);
            }
        } catch (Rechazo | LectorTabular.ArchivoIlegible e) {
            cerrarSinExito(versionId, "rechazada", e.getMessage(), informe, ruta);
            registrar("homologacion_fuente_cargada", "Fuente " + f.codigo() + " · versión rechazada: " + ServicioHomologacion.cortar(e.getMessage(), 200),
                    quien);
            throw new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), Map.of("version", version(versionId)));
        } catch (IOException | RuntimeException e) {
            LOG.error("[homologacion] Falló la carga de la versión {} de {}", versionId, f.codigo(), e);
            cerrarSinExito(versionId, "fallida", "Error técnico al leer el archivo; no se cargó la versión.", informe, ruta);
            throw e;
        } finally {
            jdbc.sql("DELETE FROM ref_cum_staging WHERE version_id = :v").param("v", versionId).update();
        }
        registrar("homologacion_fuente_cargada", "Fuente " + f.codigo() + " v" + version(versionId).get("numero") + " validada · corte " + fechaCorte
                + " · " + filas[0] + " filas leídas · " + filas[1] + " registros" + (ejemplo ? " · datos de ejemplo" : ""), quien);
        return version(versionId);
    }

    /** Versión rechazada o fallida: sin archivo en el almacén (se borra; puede traer datos que no debían subirse) y con el motivo. */
    private void cerrarSinExito(int versionId, String estado, String motivo, Map<String, Object> informe, String ruta) {
        try {
            jdbc.sql("""
                    UPDATE ref_version SET estado = :e, motivo = :m, informe = CAST(:i AS jsonb), almacen_ruta = NULL
                    WHERE id = :id AND estado = 'cargando'""")
                    .param("e", estado).param("m", ServicioHomologacion.cortar(motivo, 2000)).param("i", aJson(informe)).param("id", versionId).update();
        } finally {
            if (ruta != null) {
                almacen.descartar(ruta);
            }
        }
    }

    /** La bitácora va después del commit: si falla, lo hecho ya quedó y se registra el error (no un 500 que lo deshaga a medias). */
    private void registrar(String tipo, String descripcion, UsuarioSesion quien) {
        try {
            bitacora.registrar(tipo, descripcion, quien.id(), quien.usuario());
        } catch (RuntimeException e) {
            LOG.error("[homologacion] No se pudo registrar en la bitácora: {}", descripcion, e);
        }
    }

    /**
     * Al arrancar: una versión que quedó «cargando» es de una carga que se interrumpió (reinicio, caída). Si ninguna carga está en curso
     * (el candado de sesión está libre), pasa a «fallida» con el motivo, se borra su archivo y su staging, y queda en la bitácora.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    void recuperarInterrumpidas() {
        try (Connection c = ds.getConnection()) {
            if (!candado(c, "SELECT pg_try_advisory_lock(?)")) {
                return; // otra instancia está cargando: sus versiones «cargando» siguen vivas
            }
            try {
                List<Map<String, Object>> huerfanas = jdbc.sql("SELECT id, almacen_ruta FROM ref_version WHERE estado = 'cargando'").query().listOfRows();
                for (Map<String, Object> h : huerfanas) {
                    int id = ((Number) h.get("id")).intValue();
                    jdbc.sql("DELETE FROM ref_cum_staging WHERE version_id = :v").param("v", id).update();
                    cerrarSinExito(id, "fallida", INTERRUMPIDA, Map.of(), (String) h.get("almacen_ruta"));
                    try {
                        bitacora.registrar("homologacion_fuente_cargada", "Versión " + id + " de referencia · " + INTERRUMPIDA, null, null);
                    } catch (RuntimeException e) {
                        LOG.error("[homologacion] No se pudo registrar la versión interrumpida {} en la bitácora", id, e);
                    }
                }
                if (!huerfanas.isEmpty()) {
                    LOG.warn("[homologacion] {} versiones de referencia interrumpidas pasaron a «fallida»", huerfanas.size());
                }
            } finally {
                candado(c, "SELECT pg_advisory_unlock(?)");
            }
        } catch (SQLException | RuntimeException e) {
            LOG.error("[homologacion] No se pudieron revisar las versiones de referencia interrumpidas", e);
        }
    }

    static final String INTERRUMPIDA = "Carga interrumpida (la aplicación se reinició); vuelva a cargar el archivo.";

    /** Índice de cada columna comparable del encabezado; rechazo si falta alguna obligatoria. */
    private static Map<String, Integer> indices(List<String> encabezado, List<String> obligatorias) {
        List<String> faltan = AnaliticaHomologacion.faltantes(encabezado, obligatorias);
        if (!faltan.isEmpty()) {
            throw new Rechazo("Faltan las columnas: " + String.join(", ", faltan));
        }
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < encabezado.size(); i++) {
            m.putIfAbsent(AnaliticaHomologacion.columna(encabezado.get(i)), i);
        }
        return m;
    }

    private static String celda(List<String> celdas, Map<String, Integer> idx, String col) {
        Integer i = idx.get(col);
        if (i == null || i >= celdas.size()) {
            return null;
        }
        String v = celdas.get(i);
        return v == null || v.isBlank() ? null : v.strip();
    }

    // ---------- CUM ----------

    private int[] cargarCum(Fuente f, int versionId, String nombre, String ruta, Map<String, Object> informe) throws IOException {
        long[] leidas = {0};
        long[] sinLlave = {0};
        List<String> lineasSinLlave = new ArrayList<>();
        try (Connection c = ds.getConnection()) {
            PGCopyOutputStream copia = new PGCopyOutputStream(c.unwrap(PGConnection.class), "COPY ref_cum_staging (version_id, linea, cum, "
                    + String.join(", ", AnaliticaHomologacion.COLUMNAS_CUM) + ") FROM STDIN");
            Writer w = new BufferedWriter(new OutputStreamWriter(copia, StandardCharsets.UTF_8), 1 << 16);
            try {
                Map<String, Integer>[] idx = new Map[1];
                LectorTabular.leer(() -> almacen.leer(ruta), nombre, propiedades.archivoMaxBytes(), new LectorTabular.Receptor() {
                    @Override
                    public void encabezado(List<String> columnas) {
                        idx[0] = indices(columnas, AnaliticaHomologacion.COLUMNAS_CUM_OBLIGATORIAS);
                    }

                    @Override
                    public void fila(long orden, String linea, List<String> celdas) throws IOException {
                        leidas[0]++;
                        String exp = celda(celdas, idx[0], "expediente");
                        String cons = celda(celdas, idx[0], "consecutivocum");
                        String cum = AnaliticaHomologacion.cumDe(exp, cons).orElse(null);
                        if (cum == null) {
                            sinLlave[0]++;
                            if (lineasSinLlave.size() < MAX_LISTADAS) {
                                lineasSinLlave.add(linea);
                            }
                            return;
                        }
                        List<String> v = new ArrayList<>(3 + AnaliticaHomologacion.COLUMNAS_CUM.size());
                        v.add(Integer.toString(versionId));
                        v.add(Long.toString(orden));
                        v.add(cum);
                        for (String col : AnaliticaHomologacion.COLUMNAS_CUM) {
                            v.add(celda(celdas, idx[0], col));
                        }
                        copiar(w, v);
                    }
                });
                w.close();
            } catch (IOException | RuntimeException e) {
                try {
                    if (copia.isActive()) {
                        copia.cancelCopy();
                    }
                } catch (SQLException x) {
                    // la conexión se descarta igual
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        informe.put("filasLeidas", leidas[0]);
        informe.put("filasSinLlave", sinLlave[0]);
        informe.put("lineasSinLlave", lineasSinLlave);
        if (leidas[0] == 0) {
            throw new Rechazo("El archivo no trae filas de datos.");
        }
        // Agrupación por CUM (una fila del INVIMA por principio activo y rol) a tres temporales, luego COPY en una transacción
        Set<String> sales = new HashSet<>(Instantanea.cargar(jdbc, json).configuracion().sales());
        Path pres = Files.createTempFile("digsa-cum-pres-", ".tsv");
        Path prin = Files.createTempFile("digsa-cum-pa-", ".tsv");
        Path roles = Files.createTempFile("digsa-cum-rol-", ".tsv");
        int[] conteos = new int[5]; // presentaciones, principios, roles, fechas no convertibles, ATC no válidos
        try {
            try (Writer wp = Files.newBufferedWriter(pres, StandardCharsets.UTF_8); Writer wa = Files.newBufferedWriter(prin, StandardCharsets.UTF_8);
                 Writer wr = Files.newBufferedWriter(roles, StandardCharsets.UTF_8)) {
                Agrupador agrupador = new Agrupador(sales, versionId, f.conjunto(), wp, wa, wr, conteos);
                tx.executeWithoutResult(t -> plantilla.query(con -> {
                    PreparedStatement ps = con.prepareStatement("SELECT cum, " + String.join(", ", AnaliticaHomologacion.COLUMNAS_CUM)
                            + " FROM ref_cum_staging WHERE version_id = ? AND cum IS NOT NULL ORDER BY cum, linea");
                    ps.setInt(1, versionId);
                    ps.setFetchSize(5000);
                    return ps;
                }, agrupador));
                agrupador.volcar();
            }
            if (conteos[0] == 0) {
                throw new Rechazo("Ninguna fila trae un CUM válido (expediente y consecutivo).");
            }
            tx.executeWithoutResult(t -> {
                Connection con = DataSourceUtils.getConnection(ds);
                try {
                    var api = con.unwrap(PGConnection.class).getCopyAPI();
                    try (Reader r = Files.newBufferedReader(pres, StandardCharsets.UTF_8)) {
                        api.copyIn("COPY ref_cum_presentacion (version_id, cum, conjunto, expediente, consecutivo, producto, producto_norm, titular, "
                                + "registro_sanitario, fecha_expedicion, fecha_vencimiento, estado_registro, cantidad_cum, descripcion_comercial, estado_cum, "
                                + "fecha_activo, fecha_inactivo, muestra_medica, unidad, atc, descripcion_atc, via_administracion, forma_farmaceutica, ium) "
                                + "FROM STDIN", r);
                    }
                    try (Reader r = Files.newBufferedReader(prin, StandardCharsets.UTF_8)) {
                        api.copyIn("COPY ref_cum_principio_activo (version_id, cum, principio_activo, principio_norm, principio_base, cantidad, "
                                + "unidad_medida, concentracion, unidad_referencia) FROM STDIN", r);
                    }
                    try (Reader r = Files.newBufferedReader(roles, StandardCharsets.UTF_8)) {
                        api.copyIn("COPY ref_cum_rol (version_id, cum, orden, nombre_rol, tipo_rol, modalidad) FROM STDIN", r);
                    }
                } catch (SQLException | IOException e) {
                    throw new IllegalStateException("No se pudo guardar el CUM", e);
                }
            });
        } finally {
            Files.deleteIfExists(pres);
            Files.deleteIfExists(prin);
            Files.deleteIfExists(roles);
        }
        informe.put("presentaciones", conteos[0]);
        informe.put("principiosActivos", conteos[1]);
        informe.put("roles", conteos[2]);
        informe.put("fechasNoConvertibles", conteos[3]);
        informe.put("atcNoValidos", conteos[4]);
        // CUM que también están en otro conjunto publicado (prevalece vigentes, luego en trámite, vencidos y otros)
        List<String> conflictos = jdbc.sql("""
                SELECT p.cum || ' (' || o.conjunto || ')' FROM ref_cum_presentacion p
                JOIN ref_version v ON v.estado = 'publicada' AND v.id <> p.version_id
                JOIN ref_fuente f ON f.id = v.fuente_id AND f.tipo = 'cum'
                JOIN ref_cum_presentacion o ON o.version_id = v.id AND o.cum = p.cum
                WHERE p.version_id = :v ORDER BY p.cum LIMIT 51""").param("v", versionId).query(String.class).list();
        long nConflictos = jdbc.sql("""
                SELECT count(*) FROM ref_cum_presentacion p
                JOIN ref_version v ON v.estado = 'publicada' AND v.id <> p.version_id
                JOIN ref_fuente f ON f.id = v.fuente_id AND f.tipo = 'cum'
                JOIN ref_cum_presentacion o ON o.version_id = v.id AND o.cum = p.cum
                WHERE p.version_id = :v""").param("v", versionId).query(Long.class).single();
        informe.put("enOtrosConjuntos", nConflictos);
        informe.put("ejemplosEnOtrosConjuntos", conflictos.subList(0, Math.min(MAX_LISTADAS, conflictos.size())));
        return new int[] {(int) leidas[0], conteos[0]};
    }

    /** Recorre el staging ordenado por CUM y escribe cada grupo de filas (una presentación) en los temporales del COPY. */
    private static final class Agrupador implements org.springframework.jdbc.core.RowCallbackHandler {
        private final Set<String> sales;
        private final int versionId;
        private final String conjunto;
        private final Writer wp;
        private final Writer wa;
        private final Writer wr;
        private final int[] conteos;
        private final List<Map<String, String>> grupo = new ArrayList<>();
        private String actual;

        Agrupador(Set<String> sales, int versionId, String conjunto, Writer wp, Writer wa, Writer wr, int[] conteos) {
            this.sales = sales;
            this.versionId = versionId;
            this.conjunto = conjunto;
            this.wp = wp;
            this.wa = wa;
            this.wr = wr;
            this.conteos = conteos;
        }

        @Override
        public void processRow(ResultSet rs) throws SQLException {
            String cum = rs.getString(1);
            if (actual != null && !actual.equals(cum)) {
                volcar();
            }
            actual = cum;
            Map<String, String> fila = new HashMap<>();
            for (int i = 0; i < AnaliticaHomologacion.COLUMNAS_CUM.size(); i++) {
                fila.put(AnaliticaHomologacion.COLUMNAS_CUM.get(i), rs.getString(i + 2));
            }
            grupo.add(fila);
        }

        void volcar() {
            if (grupo.isEmpty()) {
                return;
            }
            try {
                Optional<GrupoCum> g = AnaliticaHomologacion.agruparCum(grupo, sales);
                if (g.isPresent()) {
                    escribirGrupo(g.get(), versionId, conjunto, wp, wa, wr, conteos);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            grupo.clear();
        }
    }

    private static void escribirGrupo(GrupoCum g, int versionId, String conjunto, Writer wp, Writer wa, Writer wr, int[] conteos) throws IOException {
        PresentacionCarga p = g.presentacion();
        copiar(wp, java.util.Arrays.asList(Integer.toString(versionId), p.cum(), conjunto, Long.toString(p.expediente()), Integer.toString(p.consecutivo()),
                p.producto(), p.productoNorm(), p.titular(), p.registro(), fecha(p.fechaExpedicion()), fecha(p.fechaVencimiento()), p.estadoRegistro(),
                numero(p.cantidadCum()), p.descripcionComercial(), p.estadoCum(), fecha(p.fechaActivo()), fecha(p.fechaInactivo()), p.muestraMedica(),
                p.unidad(), p.atc(), p.descripcionAtc(), p.via(), p.forma(), p.ium()));
        conteos[0]++;
        for (PrincipioCarga a : g.principios()) {
            copiar(wa, java.util.Arrays.asList(Integer.toString(versionId), a.cum(), a.principio(), a.principioNorm(), a.principioBase(),
                    numero(a.cantidad()), a.unidadMedida(), a.concentracion(), a.unidadReferencia()));
            conteos[1]++;
        }
        int orden = 0;
        for (RolCarga r : g.roles()) {
            copiar(wr, java.util.Arrays.asList(Integer.toString(versionId), r.cum(), Integer.toString(++orden), r.nombre(), r.tipo(), r.modalidad()));
            conteos[2]++;
        }
        conteos[3] += g.fechasInvalidas();
        conteos[4] += g.atcInvalido() ? 1 : 0;
    }

    private static String fecha(LocalDate d) {
        return d == null ? null : d.toString();
    }

    private static String numero(BigDecimal b) {
        return b == null ? null : b.toPlainString();
    }

    /** Una fila en el formato de texto de COPY (tabulador, \N para nulo, barras escapadas). */
    static void copiar(Writer w, List<String> valores) throws IOException {
        for (int i = 0; i < valores.size(); i++) {
            if (i > 0) {
                w.write('\t');
            }
            String v = valores.get(i);
            if (v == null) {
                w.write("\\N");
                continue;
            }
            for (int k = 0; k < v.length(); k++) {
                char ch = v.charAt(k);
                switch (ch) {
                    case '\\' -> w.write("\\\\");
                    case '\t' -> w.write("\\t");
                    case '\n' -> w.write("\\n");
                    case '\r' -> w.write("\\r");
                    default -> w.write(ch);
                }
            }
        }
        w.write('\n');
    }

    // ---------- CIE-10, precio regulado, vitales, listas y códigos SSFM ----------

    /** Tope del contenido (descomprimido) de una referencia distinta del CUM: muy por encima de las fuentes reales (CIE-10 ~13 MB). */
    static final long TABLA_MAX_BYTES = 512L * 1024 * 1024;
    /** Tope de filas de una referencia distinta del CUM (las llaves para detectar repetidos se guardan en memoria). */
    static final long TABLA_MAX_FILAS = 1_000_000;
    /** Filas por lote de escritura (no se retiene el archivo en memoria). */
    private static final int LOTE_TABLA = 2000;

    /**
     * Lee y escribe por lotes de {@link #LOTE_TABLA} filas en una sola transacción (todo o nada), con tope propio de tamaño y de filas.
     * Solo quedan en memoria las llaves para detectar los repetidos.
     */
    private int[] cargarTabla(Fuente f, int versionId, String nombre, String ruta, Map<String, Object> informe) throws IOException {
        List<String> obligatorias = switch (f.tipo()) {
            case "cie10" -> CIE10_OBLIGATORIAS;
            case "precio" -> PRECIO_OBLIGATORIAS;
            case "vitales" -> VITALES_OBLIGATORIAS;
            case "lista" -> LISTA_OBLIGATORIAS;
            default -> SSFM_OBLIGATORIAS;
        };
        String sql = switch (f.tipo()) {
            case "cie10" -> """
                    INSERT INTO ref_cie10 (version_id, codigo, nombre, grupo, capitulo, sexo, edad_minima, edad_maxima, habilitado)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";
            case "precio" -> """
                    INSERT INTO ref_precio_regulado (version_id, linea, cum, medicamento, mercado_relevante, cantidad_unidad, unidad_medida,
                                                     precio_institucional, margen_ips, precio_comercial, precio_final_comercial, circular, fecha_inicio)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
            case "vitales" -> """
                    INSERT INTO ref_vital_no_disponible (version_id, linea, nombre_comercial, principio_activo1, principio_norm1, concentracion1, unidad1,
                                                         principio_activo2, principio_norm2, concentracion2, unidad2, forma_farmaceutica, presentacion,
                                                         cantidad_solicitada, tipo_solicitud, fecha_autorizacion, cie10, ium)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
            case "lista" -> """
                    INSERT INTO ref_lista_normativa (version_id, lista, principio_activo, principio_norm, norma, fecha_norma, observacion)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""";
            default -> """
                    INSERT INTO ref_codigo_ssfm (version_id, codigo, cum, principio_activo, principio_norm, concentracion, forma, descripcion)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";
        };
        List<Object[]> lote = new ArrayList<>(LOTE_TABLA);
        List<String> rechazos = new ArrayList<>();
        long[] leidas = {0};
        long[] rechazadas = {0};
        long[] validas = {0};
        long[] fechasNoConvertibles = {0};
        Set<String> llaves = new HashSet<>();
        Map<String, Integer>[] idx = new Map[1];
        try {
            tx.executeWithoutResult(t -> {
                try {
                    LectorTabular.leer(() -> almacen.leer(ruta), nombre, Math.min(propiedades.archivoMaxBytes(), TABLA_MAX_BYTES), new LectorTabular.Receptor() {
                        @Override
                        public void encabezado(List<String> columnas) {
                            idx[0] = indices(columnas, obligatorias);
                        }

                        @Override
                        public void fila(long orden, String linea, List<String> celdas) {
                            if (++leidas[0] > TABLA_MAX_FILAS) {
                                throw new Rechazo("El archivo pasa de " + TABLA_MAX_FILAS + " filas: no corresponde a esta fuente.");
                            }
                            int antes = lote.size();
                            String error = switch (f.tipo()) {
                                case "cie10" -> filaCie10(versionId, celdas, idx[0], lote, llaves);
                                case "precio" -> filaPrecio(versionId, (int) orden, celdas, idx[0], lote, llaves);
                                case "vitales" -> filaVital(versionId, (int) orden, celdas, idx[0], lote, fechasNoConvertibles);
                                case "lista" -> filaLista(versionId, f.lista(), celdas, idx[0], lote, llaves);
                                default -> filaSsfm(versionId, celdas, idx[0], lote, llaves);
                            };
                            if (error != null) {
                                rechazadas[0]++;
                                if (rechazos.size() < MAX_LISTADAS) {
                                    rechazos.add("Línea " + linea + ": " + error);
                                }
                            }
                            validas[0] += lote.size() - antes;
                            if (lote.size() >= LOTE_TABLA) {
                                plantilla.batchUpdate(sql, lote);
                                lote.clear();
                            }
                        }
                    });
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                informe.put("filasLeidas", leidas[0]);
                informe.put("filasRechazadas", rechazadas[0]);
                informe.put("rechazos", rechazos);
                if ("vitales".equals(f.tipo())) {
                    informe.put("fechasNoConvertibles", fechasNoConvertibles[0]);
                }
                if (validas[0] == 0) {
                    throw new Rechazo(leidas[0] == 0 ? "El archivo no trae filas de datos." : "Ninguna fila es válida: " + String.join("; ",
                            rechazos.subList(0, Math.min(3, rechazos.size()))));
                }
                if (!lote.isEmpty()) {
                    plantilla.batchUpdate(sql, lote);
                    lote.clear();
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return new int[] {(int) leidas[0], (int) validas[0]};
    }

    private static String filaCie10(int v, List<String> c, Map<String, Integer> idx, List<Object[]> filas, Set<String> llaves) {
        String codigo = AnaliticaHomologacion.normalizarCie10(celda(c, idx, "codigo"));
        String nombre = celda(c, idx, "nombre");
        if (!codigo.matches("^[A-Z]\\d{2}[0-9A-Z]$")) {
            return "código CIE-10 no válido";
        }
        if (nombre == null) {
            return "falta el nombre";
        }
        if (!llaves.add(codigo)) {
            return "código repetido";
        }
        String grupo = AnaliticaHomologacion.normalizarCie10(celda(c, idx, "extravii"));
        grupo = grupo.matches("^[A-Z]\\d{2}$") ? grupo : codigo.substring(0, 3);
        String habilitado = celda(c, idx, "habilitado");
        boolean hab = habilitado == null || !habilitado.toUpperCase(Locale.ROOT).matches("^(NO|N|FALSE|0|FALSO)$");
        filas.add(new Object[] {v, codigo, ServicioHomologacion.cortar(nombre, 500), grupo, celda(c, idx, "extravi"), celda(c, idx, "extrai"),
            celda(c, idx, "extraii"), celda(c, idx, "extraiii"), hab});
        return null;
    }

    private static final String COL_PRECIO_INST = AnaliticaHomologacion.columna("precio_maximo_de_venta_transaccion_primaria_secundaria_y_final_institucional");
    private static final String COL_PRECIO_COM = AnaliticaHomologacion.columna("precio_maximo_de_venta_transaccion_primaria_y_secundaria_comercial");
    private static final String COL_PRECIO_FIN = AnaliticaHomologacion.columna("precio_maximo_de_venta_transaccion_final_comercial");
    private static final String COL_FECHA_INICIO = AnaliticaHomologacion.columna("fecha_de_inicio_vigencia_precio_maximo_de_venta");

    private static String filaPrecio(int v, int linea, List<String> c, Map<String, Integer> idx, List<Object[]> filas, Set<String> llaves) {
        Optional<String> cum = AnaliticaHomologacion.normalizarCum(celda(c, idx, "cum"));
        if (cum.isEmpty()) {
            return "CUM no válido";
        }
        String circular = celda(c, idx, AnaliticaHomologacion.columna("circular_cnpmdm"));
        if (circular == null) {
            return "falta la circular";
        }
        Optional<BigDecimal> inst = AnaliticaHomologacion.precio(celda(c, idx, COL_PRECIO_INST));
        if (inst.isEmpty()) {
            return "precio institucional no numérico";
        }
        String fechaTexto = celda(c, idx, COL_FECHA_INICIO);
        LocalDate fecha = fechaTexto == null ? null : AnaliticaIngesta.fecha(fechaTexto).or(() -> AnaliticaHomologacion.fechaInvima(fechaTexto)).orElse(null);
        if (fechaTexto != null && fecha == null) {
            return "fecha de inicio de vigencia no convertible";
        }
        if (!llaves.add(cum.get() + "|" + circular + "|" + fecha)) {
            return "precio repetido (CUM, circular y fecha de inicio)";
        }
        filas.add(new Object[] {v, linea, cum.get(), celda(c, idx, "medicamento"), celda(c, idx, AnaliticaHomologacion.columna("mercado_relevante")),
            AnaliticaHomologacion.numero(celda(c, idx, AnaliticaHomologacion.columna("cantidad_por_unidad_de_medida"))).orElse(null),
            celda(c, idx, AnaliticaHomologacion.columna("unidad_de_medida")), inst.get(),
            AnaliticaHomologacion.numero(celda(c, idx, AnaliticaHomologacion.columna("margen_para_ips"))).orElse(null),
            AnaliticaHomologacion.precio(celda(c, idx, COL_PRECIO_COM)).orElse(null), AnaliticaHomologacion.precio(celda(c, idx, COL_PRECIO_FIN)).orElse(null),
            circular, fecha == null ? null : Date.valueOf(fecha)});
        return null;
    }

    private static String filaVital(int v, int linea, List<String> c, Map<String, Integer> idx, List<Object[]> filas, long[] fechasNoConvertibles) {
        String nombre = celda(c, idx, AnaliticaHomologacion.columna("nombre_comercial_"));
        String p1 = celda(c, idx, AnaliticaHomologacion.columna("principio_activo1"));
        if (p1 == null && nombre == null) {
            return "sin nombre comercial ni principio activo";
        }
        String p2 = celda(c, idx, AnaliticaHomologacion.columna("principio_activo2"));
        String fecha = celda(c, idx, AnaliticaHomologacion.columna("fecha_de_autorizaci_n"));
        LocalDate f = fecha == null ? null : AnaliticaIngesta.fecha(fecha).or(() -> AnaliticaHomologacion.fechaInvima(fecha)).orElse(null);
        if (fecha != null && f == null) {
            fechasNoConvertibles[0]++; // la fila se carga sin fecha y el informe lo cuenta
        }
        filas.add(new Object[] {v, linea, nombre, p1, p1 == null ? null : AnaliticaHomologacion.normalizarTexto(p1),
            celda(c, idx, AnaliticaHomologacion.columna("concentraci_n_delmedicamento1")), celda(c, idx, AnaliticaHomologacion.columna("unidad_medida1")),
            p2, p2 == null ? null : AnaliticaHomologacion.normalizarTexto(p2), celda(c, idx, AnaliticaHomologacion.columna("concentraci_n_del_medicamento2")),
            celda(c, idx, AnaliticaHomologacion.columna("unidad_medida2")), celda(c, idx, AnaliticaHomologacion.columna("forma_farmac_utica")),
            celda(c, idx, AnaliticaHomologacion.columna("presentaci_n_comercial")), celda(c, idx, AnaliticaHomologacion.columna("cantidad_solicitada")),
            celda(c, idx, AnaliticaHomologacion.columna("tipo_de_solicitud")), f == null ? null : Date.valueOf(f),
            celda(c, idx, AnaliticaHomologacion.columna("c_digo_diagnostico_cie_10")), celda(c, idx, "ium")});
        return null;
    }

    private static String filaLista(int v, String lista, List<String> c, Map<String, Integer> idx, List<Object[]> filas, Set<String> llaves) {
        String p = celda(c, idx, AnaliticaHomologacion.columna("principio_activo"));
        String norma = celda(c, idx, "norma");
        if (p == null || norma == null) {
            return "falta el principio activo o la norma";
        }
        String n = AnaliticaHomologacion.normalizarTexto(p);
        if (!llaves.add(n)) {
            return "principio activo repetido";
        }
        String fecha = celda(c, idx, AnaliticaHomologacion.columna("fecha_norma"));
        LocalDate f = fecha == null ? null : AnaliticaIngesta.fecha(fecha).orElse(null);
        if (fecha != null && f == null) {
            return "fecha de la norma no convertible";
        }
        filas.add(new Object[] {v, lista, ServicioHomologacion.cortar(p, 200), n, ServicioHomologacion.cortar(norma, 300), f == null ? null : Date.valueOf(f),
            ServicioHomologacion.cortar(celda(c, idx, "observacion"), 500)});
        return null;
    }

    private static String filaSsfm(int v, List<String> c, Map<String, Integer> idx, List<Object[]> filas, Set<String> llaves) {
        String codigo = celda(c, idx, AnaliticaHomologacion.columna("codigo_ssfm"));
        if (codigo == null) {
            return "falta el código SSFM";
        }
        codigo = codigo.toUpperCase(Locale.ROOT);
        String cumTexto = celda(c, idx, "cum");
        String cum = cumTexto == null ? null : AnaliticaHomologacion.normalizarCum(cumTexto).orElse(null);
        if (cumTexto != null && cum == null) {
            return "CUM no válido";
        }
        String p = celda(c, idx, AnaliticaHomologacion.columna("principio_activo"));
        if (cum == null && p == null) {
            return "sin CUM ni principio activo";
        }
        if (!llaves.add(codigo)) {
            return "código SSFM repetido";
        }
        filas.add(new Object[] {v, ServicioHomologacion.cortar(codigo, 40), cum, p, p == null ? null : AnaliticaHomologacion.normalizarTexto(p),
            celda(c, idx, "concentracion"), celda(c, idx, AnaliticaHomologacion.columna("forma_farmaceutica")), celda(c, idx, "descripcion")});
        return null;
    }

    // ---------- Publicación atómica ----------

    /** Publica una versión validada y marca la anterior «reemplazada», en una transacción. */
    Map<String, Object> publicar(int versionId, UsuarioSesion quien) {
        Map<String, Object> v = version(versionId);
        if (!"validada".equals(v.get("estado"))) {
            throw ErrorNegocio.conflicto("Solo se publica una versión validada (esta está " + v.get("estado") + ").");
        }
        if (Boolean.TRUE.equals(v.get("ejemplo")) && cfg.esProduccion()) {
            throw ErrorNegocio.validacion("Las referencias de ejemplo no se pueden publicar en producción.");
        }
        String codigo = tx.execute(t -> {
            int fuenteId = jdbc.sql("SELECT fuente_id FROM ref_version WHERE id = :id").param("id", versionId).query(Integer.class).single();
            String c = jdbc.sql("SELECT codigo FROM ref_fuente WHERE id = :f FOR UPDATE").param("f", fuenteId).query(String.class).single();
            // Una validada vieja no hace retroceder la fuente: si ya se publicó una versión más nueva, 409
            int posterior = jdbc.sql("""
                    SELECT coalesce(max(numero), 0) FROM ref_version WHERE fuente_id = :f AND estado IN ('publicada', 'reemplazada') AND numero > :n""")
                    .param("f", fuenteId).param("n", v.get("numero")).query(Integer.class).single();
            if (posterior > 0) {
                throw ErrorNegocio.conflicto("Ya se publicó la versión " + posterior + ", más reciente que esta; cargue de nuevo el archivo si quiere "
                        + "volver a estos datos.");
            }
            OffsetDateTime ahora = OffsetDateTime.now(reloj);
            jdbc.sql("UPDATE ref_version SET estado = 'reemplazada', reemplazada_en = :a WHERE fuente_id = :f AND estado = 'publicada'")
                    .param("a", ahora).param("f", fuenteId).update();
            int n = jdbc.sql("""
                    UPDATE ref_version SET estado = 'publicada', publicada_en = :a, publicada_por = :u WHERE id = :id AND estado = 'validada'""")
                    .param("a", ahora).param("u", quien.id()).param("id", versionId).update();
            if (n != 1) {
                throw ErrorNegocio.conflicto("La versión cambió mientras se publicaba; recargue la pantalla.");
            }
            return c;
        });
        registrar("homologacion_fuente_publicada", "Fuente " + codigo + " v" + v.get("numero") + " publicada · corte " + v.get("fechaCorte")
                + (Boolean.TRUE.equals(v.get("ejemplo")) ? " · datos de ejemplo" : ""), quien);
        return version(versionId);
    }

    // ---------- Listas normativas editables ----------

    /** Fila de una lista normativa (estrecho margen, control especial). */
    record FilaLista(String principioActivo, String norma, LocalDate fechaNorma, String observacion) {
    }

    /** Filas de la versión publicada de una lista normativa. */
    List<Map<String, Object>> lista(String codigo) {
        Fuente f = fuente(codigo);
        if (!"lista".equals(f.tipo())) {
            throw ErrorNegocio.validacion("La fuente no es una lista normativa.");
        }
        return filasLista(f.id());
    }

    private List<Map<String, Object>> filasLista(int fuenteId) {
        return jdbc.sql("""
                SELECT l.principio_activo, l.norma, l.fecha_norma, l.observacion FROM ref_lista_normativa l
                JOIN ref_version v ON v.id = l.version_id AND v.estado = 'publicada' AND v.fuente_id = :f ORDER BY l.principio_norm""")
                .param("f", fuenteId).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("principioActivo", rs.getString(1));
                    m.put("norma", rs.getString(2));
                    Date d = rs.getDate(3);
                    m.put("fechaNorma", d == null ? null : d.toLocalDate().toString());
                    m.put("observacion", rs.getString(4));
                    return m;
                }).list();
    }

    /**
     * Reemplaza la lista con las filas dadas: versión nueva publicada (origen «edicion») en una transacción, con la traza del valor anterior
     * y el nuevo en hom_regla_historial.
     */
    Map<String, Object> editarLista(String codigo, List<FilaLista> filas, UsuarioSesion quien, String responsable) {
        Fuente f = fuente(codigo);
        if (!"lista".equals(f.tipo())) {
            throw ErrorNegocio.validacion("La fuente no es una lista normativa.");
        }
        if (filas == null) {
            throw ErrorNegocio.validacion("Envíe las filas de la lista.");
        }
        Map<String, FilaLista> porPrincipio = new TreeMap<>();
        for (FilaLista x : filas) {
            if (x == null || x.principioActivo() == null || x.principioActivo().isBlank() || x.norma() == null || x.norma().isBlank()) {
                throw ErrorNegocio.validacion("Cada fila necesita el principio activo y la norma que lo incluye.");
            }
            if (x.principioActivo().length() > 200 || x.norma().length() > 300 || x.observacion() != null && x.observacion().length() > 500) {
                throw ErrorNegocio.validacion("Texto demasiado largo (principio 200, norma 300 y observación 500 caracteres).");
            }
            if (porPrincipio.put(AnaliticaHomologacion.normalizarTexto(x.principioActivo()), x) != null) {
                throw ErrorNegocio.validacion("El principio activo «" + x.principioActivo().strip() + "» está repetido.");
            }
        }
        List<Map<String, Object>> despues = new ArrayList<>();
        porPrincipio.values().forEach(x -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("principioActivo", x.principioActivo().strip());
            m.put("norma", x.norma().strip());
            m.put("fechaNorma", x.fechaNorma() == null ? null : x.fechaNorma().toString());
            m.put("observacion", x.observacion() == null || x.observacion().isBlank() ? null : x.observacion().strip());
            despues.add(m);
        });
        String contenido = aJson(despues);
        String sha = Huellas.sha256(contenido);
        Object[] r = tx.execute(t -> {
            // El mismo candado de la carga por archivo: una carga y una edición de la misma lista no calculan el mismo número (409 claro)
            if (!jdbc.sql("SELECT pg_try_advisory_xact_lock(:k)").param("k", CANDADO).query(Boolean.class).single()) {
                throw ErrorNegocio.conflicto("Hay una carga de referencias en curso; intente de nuevo en unos minutos.");
            }
            jdbc.sql("SELECT id FROM ref_fuente WHERE id = :f FOR UPDATE").param("f", f.id()).query(Integer.class).single();
            List<Map<String, Object>> antes = filasLista(f.id());
            if (aJson(antes).equals(contenido)) {
                throw ErrorNegocio.validacion("La lista no cambió.");
            }
            OffsetDateTime ahora = OffsetDateTime.now(reloj);
            int numero = jdbc.sql("SELECT coalesce(max(numero), 0) + 1 FROM ref_version WHERE fuente_id = :f").param("f", f.id()).query(Integer.class)
                    .single();
            jdbc.sql("UPDATE ref_version SET estado = 'reemplazada', reemplazada_en = :a WHERE fuente_id = :f AND estado = 'publicada'")
                    .param("a", ahora).param("f", f.id()).update();
            int vid = jdbc.sql("""
                    INSERT INTO ref_version (fuente_id, numero, fecha_corte, cargado, usuario_id, responsable_texto, origen, sha256, filas_leidas,
                                             filas_validas, estado, informe, publicada_en, publicada_por)
                    VALUES (:f, :n, :hoy, :a, :u, :resp, 'edicion', :sha, :filas, :filas, 'publicada', '{}', :a, :u) RETURNING id""")
                    .param("f", f.id()).param("n", numero).param("hoy", LocalDate.now(reloj)).param("a", ahora).param("u", quien.id())
                    .param("resp", responsable).param("sha", sha).param("filas", despues.size()).query(Integer.class).single();
            List<Object[]> lote = new ArrayList<>();
            for (FilaLista x : porPrincipio.values()) {
                lote.add(new Object[] {vid, f.lista(), x.principioActivo().strip(), AnaliticaHomologacion.normalizarTexto(x.principioActivo()),
                    x.norma().strip(), x.fechaNorma() == null ? null : Date.valueOf(x.fechaNorma()),
                    x.observacion() == null || x.observacion().isBlank() ? null : x.observacion().strip()});
            }
            if (!lote.isEmpty()) {
                plantilla.batchUpdate("""
                        INSERT INTO ref_lista_normativa (version_id, lista, principio_activo, principio_norm, norma, fecha_norma, observacion)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""", lote);
            }
            String descripcion = ServicioHomologacion.cortar("Lista " + codigo + " v" + numero + " modificada: " + resumenCambio(antes, despues), 500);
            jdbc.sql("""
                    INSERT INTO hom_regla_historial (objeto, version, antes, despues, descripcion, usuario_id, responsable_texto, fecha)
                    VALUES (:o, :v, CAST(:antes AS jsonb), CAST(:despues AS jsonb), :d, :u, :resp, :a)""")
                    .param("o", "lista:" + codigo).param("v", numero).param("antes", aJson(antes)).param("despues", contenido).param("d", descripcion)
                    .param("u", quien.id()).param("resp", responsable).param("a", ahora).update();
            return new Object[] {vid, descripcion};
        });
        registrar("homologacion_lista_modificada", (String) r[1], quien);
        return version((Integer) r[0]);
    }

    /** «+WARFARINA; −LITIO» con los principios agregados y quitados (o «normas o fechas actualizadas»). */
    static String resumenCambio(List<Map<String, Object>> antes, List<Map<String, Object>> despues) {
        Set<String> a = new java.util.TreeSet<>();
        antes.forEach(m -> a.add(AnaliticaHomologacion.normalizarTexto(String.valueOf(m.get("principioActivo")))));
        Set<String> d = new java.util.TreeSet<>();
        despues.forEach(m -> d.add(AnaliticaHomologacion.normalizarTexto(String.valueOf(m.get("principioActivo")))));
        List<String> partes = new ArrayList<>();
        d.stream().filter(x -> !a.contains(x)).forEach(x -> partes.add("+" + x));
        a.stream().filter(x -> !d.contains(x)).forEach(x -> partes.add("−" + x));
        return partes.isEmpty() ? "normas, fechas u observaciones actualizadas" : String.join("; ", partes);
    }

    private String aJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
