package co.com.agsamericas.digsa.analitica.tableros;

import co.com.agsamericas.digsa.analitica.PerfilesOperacion;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.ConsultaAnalitica;
import co.com.agsamericas.digsa.reglas.AnaliticaDescargaDiferida;
import co.com.agsamericas.digsa.reglas.Huellas;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.FileNotFoundException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.NoSuchFileException;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Descarga diferida del CSV de registros de origen (A7.3, duda AN.16; analitica-tableros «Descarga diferida cuando no cabe en la
 * petición»), con el patrón de la exportación de hechos (A7.2). Las descargas de los tableros no pasan por aquí: son agregadas y van en la
 * petición. Cuando la cifra rastreada tiene más registros de origen que el umbral, no se escribe en la petición: se congela lo que define
 * el archivo en ese instante (parámetros de la URL por lista blanca, corte resuelto, alcance, usuario y perfil) y se responde 202 con el
 * id. Un ejecutor en segundo plano, uno a la vez en todo el despliegue (candado de PostgreSQL), lo genera con el MISMO CSV de la descarga
 * en la petición (misma consulta, corte, filtros y alcance ⇒ misma huella), dentro de una transacción READ ONLY REPEATABLE READ con su
 * presupuesto ({@code limite-minutos}), lo guarda en el almacén con su SHA-256 y lo deja en «Mis descargas del día» del usuario hasta que
 * vence ({@code vigencia-horas}). Una en curso por usuario (índice único parcial: 409).
 *
 * <p>Antes de generar se revalida que el usuario siga activo, con el mismo alcance y con el permiso de trazabilidad; si no, queda
 * «fallida» sin generar. La fila «ok» de {@code ana_consultas} se escribe solo cuando el archivo quedó completo (falla cerrado: sin ella no
 * queda disponible); una generación que falla o se descarta deja su fila «error» o «tiempo_agotado».
 *
 * <p>La cola no bloquea para siempre: cada toma cuenta un intento (una caída a mitad la devuelve a la cola con su archivo a medias
 * borrado) y al pasar de {@link AnaliticaDescargaDiferida#MAX_INTENTOS} queda «fallida»; una pendiente o generándose desde hace más que
 * {@code edad-maxima-minutos} también. Borrado (datos de salud seudonimizados, Ley 1581): la ruta solo se anula cuando se comprobó que el
 * archivo ya no está en el almacén; si el borrado falla, la revisión periódica lo reintenta.
 */
@Service
public class ServicioDescargaAnalitica {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioDescargaAnalitica.class);
    /** Llave del candado del ejecutor (pg_try_advisory_lock): distinta de la exportación completa y de la de hechos. */
    static final long CANDADO = 0x414e4144455346L;
    /** Tope del JSON de parámetros guardados (la columna admite 8000). */
    static final int MAX_PARAMETROS = 6000;

    /** Lo que se entrega al descargar. */
    public record Archivo(String nombre, long tamano, String sha256, String huella, InputStream contenido) {
    }

    private record Trabajo(long id, long usuarioId, String usuarioTexto, String perfil, String alcance, String tablero, String parametros,
                           long corte, String ruta) {
    }

    /**
     * Solo para pruebas: se llama al empezar a generar cada descarga (la prueba de falla la hace fallar; la de la cola la detiene). Es el
     * mismo gancho que usa la exportación de hechos (A7.2); no se reemplazó por un bean porque no aporta para un solo punto de prueba.
     */
    volatile java.util.function.LongConsumer alEmpezar = id -> { };

    private final JdbcClient jdbc;
    private final DataSource ds;
    private final Almacen almacen;
    private final ServicioTrazaAnalitica traza;
    private final BitacoraAnalitica bitacoraAnalitica;
    private final RegistroBitacora bitacora;
    private final PerfilesOperacion perfiles;
    private final PermisosTableros permisos;
    private final PropiedadesDescargasDiferidas propiedades;
    private final ObjectMapper json;
    private final TransactionTemplate transaccion;
    private final Clock reloj;
    private final ExecutorService ejecutor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("digsa-analitica-descargas").daemon(true).factory());
    /** Hay una corrida en la cola del ejecutor que aún no empezó: no se encola otra. */
    private final AtomicBoolean encolado = new AtomicBoolean();

    ServicioDescargaAnalitica(JdbcClient jdbc, DataSource ds, Almacen almacen, ServicioTrazaAnalitica traza, BitacoraAnalitica bitacoraAnalitica,
                              RegistroBitacora bitacora, PerfilesOperacion perfiles, PermisosTableros permisos, PropiedadesDescargasDiferidas propiedades,
                              ObjectMapper json, PlatformTransactionManager transacciones, Clock reloj) {
        this.jdbc = jdbc;
        this.ds = ds;
        this.almacen = almacen;
        this.traza = traza;
        this.bitacoraAnalitica = bitacoraAnalitica;
        this.bitacora = bitacora;
        this.perfiles = perfiles;
        this.permisos = permisos;
        this.propiedades = propiedades;
        this.json = json;
        this.transaccion = new TransactionTemplate(transacciones);
        this.reloj = reloj;
    }

    @PreDestroy
    void detener() {
        ejecutor.shutdownNow();
    }

    // ---------- Solicitud (desde la petición del CSV de registros de origen) ----------

    /**
     * Congela y encola el CSV de registros de origen de una cifra grande. {@code g} trae los registros contados y el corte resuelto en la
     * petición; de los parámetros de la URL se guardan solo los de la lista blanca (el corte va aparte). Una en curso por usuario (409). La
     * fila y su bitácora van en la misma transacción: si la bitácora falla, no queda nada encolado.
     */
    Map<String, Object> solicitar(String tablero, MultiValueMap<String, String> parametros, DescargaGrande g, Alcance alcance, UsuarioSesion quien) {
        AnaliticaDescargaDiferida.excedeTraza(g.registros(), propiedades.maxTraza()).ifPresent(m -> {
            throw ErrorNegocio.validacion(m);
        });
        String codigoAlcance = AnaliticaDescargaDiferida.codigo(alcance)
                .orElseThrow(() -> new ErrorNegocio(HttpStatus.FORBIDDEN, AnaliticaDescargaDiferida.SIN_ALCANCE));
        Map<String, List<String>> guardados = new LinkedHashMap<>();
        parametros.forEach((k, v) -> {
            if (AnaliticaDescargaDiferida.parametroGuardado(k)) {
                guardados.put(k, v == null ? List.of() : List.copyOf(v));
            }
        });
        if (!AnaliticaDescargaDiferida.parametrosCaben(guardados, MAX_PARAMETROS)) {
            throw ErrorNegocio.validacion("La consulta tiene demasiados filtros para generarla en diferido; acote los filtros.");
        }
        if (enCurso(quien.id())) {
            throw ErrorNegocio.conflicto(AnaliticaDescargaDiferida.EN_CURSO);
        }
        String perfil = cortar(perfiles.nombres(), 200);
        long id;
        try {
            id = transaccion.execute(s -> {
                long nuevo = jdbc.sql("""
                        INSERT INTO ana_descargas_diferidas (solicitada_por, usuario_texto, perfil, alcance, tablero, parametros, corte_cargue_id,
                                                             registros, creado)
                        VALUES (:u, :ut, :perfil, :alcance, :tablero, CAST(:p AS jsonb), :corte, :registros, :ahora)
                        RETURNING id""")
                        .param("u", quien.id()).param("ut", cortar(quien.nombre() + " (" + quien.usuario() + ")", 200)).param("perfil", perfil)
                        .param("alcance", codigoAlcance).param("tablero", tablero).param("p", aJson(guardados)).param("corte", g.corteCargueId())
                        .param("registros", g.registros()).param("ahora", OffsetDateTime.now(reloj)).query(Long.class).single();
                bitacora.registrar("analitica_descarga_diferida_solicitada", AnaliticaDescargaDiferida.resumen(nuevo, tablero) + " · " + g.registros()
                        + " registros · corte cargue " + g.corteCargueId(), quien.id(), quien.usuario());
                return nuevo;
            });
        } catch (DuplicateKeyException e) {
            throw ErrorNegocio.conflicto(AnaliticaDescargaDiferida.EN_CURSO); // otra pestaña la pidió al mismo tiempo
        }
        despertar();
        Map<String, Object> m = detalle(id, quien);
        m.put("mensaje", AnaliticaDescargaDiferida.mensajeDiferida(g.registros(), propiedades.umbral()));
        return m;
    }

    private boolean enCurso(long usuarioId) {
        return jdbc.sql("SELECT count(*) FROM ana_descargas_diferidas WHERE solicitada_por = :u AND estado IN ('pendiente', 'generando')")
                .param("u", usuarioId).query(Long.class).single() > 0;
    }

    private String aJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudieron serializar los parámetros de la descarga", e);
        }
    }

    private static String cortar(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max);
    }

    // ---------- «Mis descargas del día» ----------

    private static final String SELECT = """
            SELECT id, tablero, corte_cargue_id, registros, estado, tamano, sha256, huella, error, creado, iniciado, terminado, vence, duracion_ms
            FROM ana_descargas_diferidas""";

    /** Las descargas diferidas vigentes del usuario (en curso, listas o fallidas sin vencer), de la más reciente a la más antigua. */
    public List<Map<String, Object>> listar(UsuarioSesion quien) {
        return jdbc.sql(SELECT + " WHERE solicitada_por = :u AND estado <> 'vencida' AND (vence IS NULL OR vence > :ahora) ORDER BY id DESC LIMIT 50")
                .param("u", quien.id()).param("ahora", OffsetDateTime.now(reloj)).query(ServicioDescargaAnalitica::fila).list();
    }

    /** Una descarga del usuario (404 si no existe o es de otro). */
    public Map<String, Object> detalle(long id, UsuarioSesion quien) {
        return jdbc.sql(SELECT + " WHERE id = :id AND solicitada_por = :u").param("id", id).param("u", quien.id())
                .query(ServicioDescargaAnalitica::fila).optional().orElseThrow(() -> ErrorNegocio.noEncontrado("La descarga no está en sus descargas del día."));
    }

    private static Map<String, Object> fila(ResultSet rs, int n) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        String tablero = rs.getString("tablero");
        OffsetDateTime terminado = rs.getObject("terminado", OffsetDateTime.class);
        m.put("id", rs.getLong("id"));
        m.put("tablero", tablero);
        m.put("titulo", AnaliticaConsulta.Tablero.deCodigo(tablero).map(AnaliticaConsulta.Tablero::titulo).orElse(tablero));
        m.put("nombre", AnaliticaDescargaDiferida.nombreArchivo(tablero, terminado));
        m.put("corteCargueId", rs.getLong("corte_cargue_id"));
        m.put("registros", rs.getLong("registros"));
        m.put("estado", rs.getString("estado"));
        m.put("tamano", rs.getObject("tamano"));
        m.put("sha256", rs.getString("sha256"));
        m.put("huella", rs.getString("huella"));
        m.put("error", rs.getString("error"));
        m.put("creado", rs.getObject("creado", OffsetDateTime.class));
        m.put("iniciado", rs.getObject("iniciado", OffsetDateTime.class));
        m.put("terminado", terminado);
        m.put("vence", rs.getObject("vence", OffsetDateTime.class));
        m.put("duracionMs", rs.getObject("duracion_ms"));
        return m;
    }

    /**
     * Abre el archivo de una descarga lista del usuario y lo deja en la bitácora. Vencida: 410. El alcance del usuario debe seguir siendo
     * el congelado (si cambió, 403: el archivo es de otro alcance) y debe conservar el permiso de trazabilidad (403).
     */
    public Archivo descargar(long id, UsuarioSesion quien, Alcance alcanceActual) {
        record Fila(String estado, String tablero, String alcance, String ruta, Long tamano, String sha256, String huella, Long registros,
                    OffsetDateTime terminado, OffsetDateTime vence) {
        }
        Fila f = jdbc.sql("""
                SELECT estado, tablero, alcance, ruta, tamano, sha256, huella, registros, terminado, vence
                FROM ana_descargas_diferidas WHERE id = :id AND solicitada_por = :u""").param("id", id).param("u", quien.id()).query(Fila.class)
                .optional().orElseThrow(() -> ErrorNegocio.noEncontrado("La descarga no está en sus descargas del día."));
        if ("vencida".equals(f.estado()) || f.vence() != null && !f.vence().toInstant().isAfter(reloj.instant())) {
            throw new ErrorNegocio(HttpStatus.GONE, AnaliticaDescargaDiferida.VENCIDA);
        }
        if (!"lista".equals(f.estado())) {
            throw "fallida".equals(f.estado()) ? ErrorNegocio.conflicto(AnaliticaDescargaDiferida.MENSAJE_FALLA)
                    : ErrorNegocio.conflicto("La descarga aún no está lista.");
        }
        if (!AnaliticaDescargaDiferida.codigo(alcanceActual).map(f.alcance()::equals).orElse(false)) {
            throw new ErrorNegocio(HttpStatus.FORBIDDEN, AnaliticaDescargaDiferida.ALCANCE_CAMBIO);
        }
        if (!permisos.traza()) {
            throw new ErrorNegocio(HttpStatus.FORBIDDEN, "Su perfil ya no puede descargar los registros de origen de la trazabilidad.");
        }
        InputStream in;
        try {
            in = almacen.leer(f.ruta());
        } catch (IOException | IllegalArgumentException e) {
            LOG.error("[analitica] El archivo de la descarga diferida {} no está en el almacén", id, e);
            throw new ErrorNegocio(HttpStatus.INTERNAL_SERVER_ERROR, AnaliticaDescargaDiferida.MENSAJE_FALLA);
        }
        try {
            bitacora.registrar("analitica_descarga_diferida_descarga", AnaliticaDescargaDiferida.resumen(id, f.tablero()) + " · " + f.registros()
                    + " registros · archivo " + f.sha256().substring(0, 12), quien.id(), quien.usuario());
        } catch (RuntimeException e) {
            cerrar(in); // sin bitácora no hay descarga, y el archivo no queda abierto
            throw e;
        }
        return new Archivo(AnaliticaDescargaDiferida.nombreArchivo(f.tablero(), f.terminado()), f.tamano(), f.sha256(), f.huella(), in);
    }

    private static void cerrar(java.io.Closeable c) {
        try {
            c.close();
        } catch (IOException e) {
            LOG.warn("[analitica] No se pudo cerrar un archivo de la descarga diferida: {}", e.getMessage());
        }
    }

    // ---------- Borrado verificado ----------

    /** Borra un archivo y comprueba que ya no está (el puerto no informa si falló): solo «no existe» cuenta como borrado. */
    boolean borrar(String ruta) {
        if (ruta == null) {
            return true;
        }
        almacen.descartar(ruta);
        try (InputStream in = almacen.leer(ruta)) {
            LOG.error("[analitica] No se pudo borrar {} de la descarga diferida: sigue en el almacén; se reintenta en la próxima revisión", ruta);
            return false;
        } catch (NoSuchFileException | FileNotFoundException e) {
            return true;
        } catch (IOException e) {
            LOG.error("[analitica] No se pudo comprobar el borrado de {}: {}; se reintenta en la próxima revisión", ruta, e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            return true; // ruta que el almacén no acepta: no puede haber nada en ella
        }
    }

    // ---------- Ejecutor en segundo plano ----------

    /** Revisa si hay descargas pendientes (sin esperar). Como mucho una corrida en cola. */
    public void despertar() {
        if (!encolado.compareAndSet(false, true)) {
            return;
        }
        try {
            ejecutor.execute(() -> {
                encolado.set(false);
                correr();
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            encolado.set(false);
            LOG.warn("[analitica] El ejecutor de las descargas diferidas está detenido");
        }
    }

    /** Genera las pendientes, una tras otra, si esta instancia toma el candado (en su propia conexión). */
    void correr() {
        Connection c = null;
        boolean soltado = false;
        try {
            c = ds.getConnection();
            if (!candado(c, "SELECT pg_try_advisory_lock(?)")) {
                soltado = true;
                return; // otra réplica está generando: al terminar toma las pendientes
            }
            try {
                Connection candado = c;
                huerfanas();
                for (Optional<Trabajo> t = siguiente(); t.isPresent(); t = siguiente()) {
                    generar(t.get(), () -> vivo(candado));
                    if (!vivo(candado)) {
                        break;
                    }
                }
            } finally {
                soltado = vivo(c) && candado(c, "SELECT pg_advisory_unlock(?)");
            }
        } catch (SQLException | RuntimeException e) {
            LOG.error("[analitica] El ejecutor de las descargas diferidas falló", e);
        } finally {
            if (c != null) {
                if (!soltado) {
                    try {
                        c.abort(Runnable::run); // al cerrarla PostgreSQL libera el candado
                    } catch (SQLException | RuntimeException e) {
                        LOG.error("[analitica] No se pudo abortar la conexión del candado de las descargas diferidas", e);
                    }
                }
                try {
                    c.close();
                } catch (SQLException e) {
                    LOG.warn("[analitica] No se pudo cerrar la conexión del candado: {}", e.getMessage());
                }
            }
        }
    }

    private static boolean vivo(Connection c) {
        try {
            return c.isValid(5);
        } catch (SQLException e) {
            return false;
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

    /**
     * Con el candado tomado, una descarga «generando» es de un proceso que se cayó: su archivo a medias se borra y vuelve a pendiente (se
     * rehace con lo congelado), salvo que ya agotó sus intentos (queda «fallida»). Si el archivo no se pudo borrar, queda «fallida» con la
     * ruta (la revisión reintenta el borrado).
     */
    void huerfanas() {
        record Media(long id, String ruta, int intentos) {
        }
        for (Media m : jdbc.sql("SELECT id, ruta, intentos FROM ana_descargas_diferidas WHERE estado = 'generando'").query(Media.class).list()) {
            if (!borrar(m.ruta())) {
                marcarFallida(m.id(), m.ruta(), true, AnaliticaDescargaDiferida.MENSAJE_FALLA);
            } else if (m.intentos() >= AnaliticaDescargaDiferida.MAX_INTENTOS) {
                marcarFallida(m.id(), m.ruta(), false, AnaliticaDescargaDiferida.MENSAJE_INTENTOS);
            } else {
                jdbc.sql("""
                        UPDATE ana_descargas_diferidas SET estado = 'pendiente', ruta = NULL, iniciado = NULL
                        WHERE id = :id AND estado = 'generando' AND ruta IS NOT DISTINCT FROM :ruta""").param("id", m.id()).param("ruta", m.ruta()).update();
            }
        }
    }

    private Optional<Trabajo> siguiente() {
        return jdbc.sql("""
                UPDATE ana_descargas_diferidas d SET estado = 'generando', iniciado = :ahora, intentos = d.intentos + 1,
                       ruta = 'analitica/descargas/' || d.id || '_' || :azar || '.csv'
                WHERE d.id = (SELECT id FROM ana_descargas_diferidas WHERE estado = 'pendiente' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING d.id, d.solicitada_por, d.usuario_texto, d.perfil, d.alcance, d.tablero, d.parametros::text, d.corte_cargue_id, d.ruta""")
                .param("ahora", OffsetDateTime.now(reloj)).param("azar", Huellas.secreto(12))
                .query((rs, n) -> new Trabajo(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getLong(8), rs.getString(9))).optional();
    }

    /** Resultado de una generación. */
    private record Generado(long tamano, String sha256, Map<String, Object> desglose) {
    }

    private void generar(Trabajo t, BooleanSupplier candadoVivo) {
        long inicio = System.nanoTime();
        String usuario = null;
        BitacoraAnalitica.Quien quien = null;
        ConsultaAnalitica q = null;
        try {
            usuario = jdbc.sql("SELECT usuario FROM usuarios WHERE id = :id").param("id", t.usuarioId()).query(String.class).optional()
                    .orElseThrow(() -> new IllegalStateException("El usuario de la descarga diferida " + t.id() + " no existe"));
            quien = new BitacoraAnalitica.Quien(t.usuarioId(), usuario, t.perfil());
            // Revalidación: activo, mismo alcance y aún con el permiso de trazabilidad; si no, no se genera nada
            boolean acceso = perfiles.alcanceVigente(t.usuarioId()).flatMap(AnaliticaDescargaDiferida::codigo).map(t.alcance()::equals).orElse(false)
                    && permisos.trazaUsuario(t.usuarioId());
            if (!acceso) {
                marcarFallida(t.id(), t.ruta(), false, AnaliticaDescargaDiferida.MENSAJE_SIN_ACCESO);
                registrarFallida(t, usuario, " · sin acceso");
                return;
            }
            Alcance alcance = AnaliticaDescargaDiferida.alcance(t.alcance())
                    .orElseThrow(() -> new IllegalStateException("Alcance congelado no válido en la descarga diferida " + t.id()));
            q = consulta(t);
            alEmpezar.accept(t.id());
            Generado g = generarTraza(t, q, alcance);
            Map<String, Object> d = g.desglose();
            Object corte = d.get("corteCargueId");
            // La fila «ok» de ana_consultas solo con el archivo completo, antes de dejarlo disponible (falla cerrado)
            bitacoraAnalitica.registrarPor(new BitacoraAnalitica.Entrada("traza_descarga", q, ParametrosConsulta.objeto(q, "registros de origen csv · diferida "
                    + t.id() + " · archivo " + g.sha256().substring(0, 12)), corte instanceof Long l ? l : null, "csv", (Long) d.get("registros"),
                    (String) d.get("huella"), ms(inicio), "ok", List.of(), (String) d.get("alcance")), quien);
            OffsetDateTime fin = OffsetDateTime.now(reloj);
            // Solo si sigue siendo nuestra: con el candado perdido o vencida por edad, otra réplica o la revisión pudo cambiarla
            int n = candadoVivo.getAsBoolean() ? jdbc.sql("""
                    UPDATE ana_descargas_diferidas SET estado = 'lista', tamano = :t, sha256 = :s, huella = :h, terminado = :fin, vence = :vence,
                           duracion_ms = :ms, error = NULL
                    WHERE id = :id AND estado = 'generando' AND ruta = :ruta""")
                    .param("t", g.tamano()).param("s", g.sha256()).param("h", d.get("huella")).param("fin", fin).param("vence", fin.plus(propiedades.vigencia()))
                    .param("ms", ms(inicio)).param("id", t.id()).param("ruta", t.ruta()).update() : 0;
            if (n != 1) {
                LOG.warn("[analitica] Descarga diferida {}: dejó de ser de este proceso; se descarta su archivo", t.id());
                borrar(t.ruta());
                bitacoraAnalitica.registrarFallaPor(new BitacoraAnalitica.Entrada("traza_descarga", q, ParametrosConsulta.objeto(q, "registros de origen csv · diferida "
                        + t.id() + " · descartada"), corte instanceof Long l ? l : null, "csv", null, null, ms(inicio), "error",
                        List.of("Archivo descartado: la descarga dejó de estar en generación"), null), quien);
                return;
            }
            bitacora.registrar("analitica_descarga_diferida_generada", AnaliticaDescargaDiferida.resumen(t.id(), t.tablero()) + " · " + d.get("registros")
                    + " registros · " + g.tamano() + " B · " + ms(inicio) + " ms", t.usuarioId(), usuario);
            LOG.info("[analitica] Descarga diferida {} lista: {} registros, {} bytes en {} ms", t.id(), d.get("registros"), g.tamano(), ms(inicio));
        } catch (Exception e) {
            LOG.error("[analitica] No se pudo generar la descarga diferida {}", t.id(), e);
            try {
                // Nunca queda un archivo a medias que parezca completo; si no se pudo borrar, la ruta queda para reintentarlo
                marcarFallida(t.id(), t.ruta(), !borrar(t.ruta()), AnaliticaDescargaDiferida.MENSAJE_FALLA);
                if (quien != null) {
                    bitacoraAnalitica.registrarFallaPor(BitacoraAnalitica.falla("traza_descarga", q, "csv", ms(inicio), e), quien);
                }
                registrarFallida(t, usuario, "");
            } catch (RuntimeException x) {
                LOG.error("[analitica] No se pudo dejar fallida la descarga diferida {}; la revisión la vence por edad", t.id(), x);
            }
        }
    }

    private static long ms(long inicio) {
        return Duration.ofNanos(System.nanoTime() - inicio).toMillis();
    }

    private void registrarFallida(Trabajo t, String usuario, String motivo) {
        try {
            bitacora.registrar("analitica_descarga_diferida_fallida", AnaliticaDescargaDiferida.resumen(t.id(), t.tablero()) + motivo, t.usuarioId(), usuario);
        } catch (RuntimeException e) {
            LOG.error("[analitica] No se pudo registrar la falla de la descarga diferida {} en la bitácora", t.id(), e);
        }
    }

    /** La consulta congelada: los parámetros guardados más el corte (el de la petición, no «el último»). */
    private ConsultaAnalitica consulta(Trabajo t) throws IOException {
        Map<String, List<String>> p = parametros(t);
        MultiValueMap<String, String> m = new LinkedMultiValueMap<>();
        p.forEach((k, v) -> {
            if (!"periodo".equals(k)) {
                m.put(k, new ArrayList<>(v));
            }
        });
        return ParametrosConsulta.leer(t.tablero(), m).conCorte(t.corte());
    }

    private Map<String, List<String>> parametros(Trabajo t) throws IOException {
        return json.readValue(t.parametros(), new TypeReference<Map<String, List<String>>>() { });
    }

    private Generado generarTraza(Trabajo t, ConsultaAnalitica q, Alcance alcance) throws IOException {
        List<String> per = parametros(t).get("periodo");
        String periodo = per == null || per.isEmpty() ? null : per.getFirst();
        List<String[]> enc = new ArrayList<>();
        enc.add(new String[] {"Título", "Registros de origen · " + q.tablero().titulo()});
        enc.add(new String[] {"Fecha y hora de generación", AnaliticaConsulta.fechaHora(OffsetDateTime.now(reloj))});
        enc.add(new String[] {"Usuario", t.usuarioTexto()});
        enc.add(new String[] {"Perfil", t.perfil()});
        enc.add(new String[] {"Filtros", BitacoraAnalitica.resumenFiltros(q, true) + (periodo == null ? "" : "; periodo=" + ParametrosConsulta.periodoSeguro(periodo))});
        enc.add(new String[] {"Generación", "Descarga diferida " + t.id() + " (más de " + propiedades.umbral() + " registros)"});
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        long[] bytes = {0};
        Map<String, Object> d;
        try (OutputStream almacenado = almacen.escribir(t.ruta());
             OutputStream salida = new DigestOutputStream(new FilterOutputStream(almacenado) {
                 @Override
                 public void write(int b) throws IOException {
                     out.write(b);
                     bytes[0]++;
                 }

                 @Override
                 public void write(byte[] b, int off, int len) throws IOException {
                     out.write(b, off, len);
                     bytes[0] += len;
                 }
             }, sha)) {
            // Sin bitácora antes del primer byte: la fila «ok» se escribe al terminar (el archivo aún no se entrega a nadie)
            d = traza.csvDiferido(q, periodo, alcance, enc, () -> salida, x -> { }, propiedades.limiteSegundos());
            salida.flush();
        }
        return new Generado(bytes[0], HexFormat.of().formatHex(sha.digest()), d);
    }

    private void marcarFallida(long id, String ruta, boolean conservarRuta, String mensaje) {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        jdbc.sql("""
                UPDATE ana_descargas_diferidas SET estado = 'fallida', ruta = CASE WHEN :conservar THEN ruta END, error = :e, terminado = :ahora,
                       vence = :vence
                WHERE id = :id AND estado = 'generando' AND ruta IS NOT DISTINCT FROM :ruta""")
                .param("conservar", conservarRuta).param("e", mensaje).param("ahora", ahora).param("vence", ahora.plus(propiedades.vigencia()))
                .param("id", id).param("ruta", ruta).update();
    }

    // ---------- Revisión periódica ----------

    /** Cada minuto: vence por edad las que no avanzan, borra las vencidas y despierta al ejecutor si quedó algo pendiente (tras un reinicio). */
    @Scheduled(fixedDelayString = "${digsa.analitica.descargas-diferidas.revision-ms:60000}",
            initialDelayString = "${digsa.analitica.descargas-diferidas.revision-ms:60000}")
    public void revisar() {
        vencerDemoradas();
        limpiar();
        if (jdbc.sql("SELECT count(*) FROM ana_descargas_diferidas WHERE estado IN ('pendiente', 'generando')").query(Long.class).single() > 0) {
            despertar();
        }
    }

    /**
     * Una descarga pendiente o generándose desde hace más que {@code edad-maxima-minutos} queda «fallida» (si la generación aún corre, al
     * terminar ve que dejó de ser suya y descarta su archivo; si dejó un archivo, la limpieza lo borra). Así nadie queda bloqueado por el
     * índice de «una en curso».
     *
     * @return cuántas vencieron por edad
     */
    int vencerDemoradas() {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        record Demorada(long id, long usuarioId, String tablero) {
        }
        List<Demorada> l = jdbc.sql("""
                UPDATE ana_descargas_diferidas SET estado = 'fallida', error = :e, terminado = :ahora, vence = :vence
                WHERE estado IN ('pendiente', 'generando') AND creado < :limite
                RETURNING id, solicitada_por AS usuario_id, tablero""")
                .param("e", AnaliticaDescargaDiferida.MENSAJE_DEMORA).param("ahora", ahora).param("vence", ahora.plus(propiedades.vigencia()))
                .param("limite", ahora.minus(propiedades.edadMaxima())).query(Demorada.class).list();
        for (Demorada d : l) {
            try {
                bitacora.registrar("analitica_descarga_diferida_fallida", AnaliticaDescargaDiferida.resumen(d.id(), d.tablero()) + " · demorada",
                        d.usuarioId(), null);
            } catch (RuntimeException e) {
                LOG.error("[analitica] No se pudo registrar la descarga diferida demorada {}", d.id(), e);
            }
        }
        return l.size();
    }

    /**
     * Vencimiento y borrado, idempotente: (1) las listas o fallidas cuyo plazo pasó quedan «vencidas» (bitácora); (2) se borra el archivo
     * de cada vencida o fallida que aún lo referencia, y la ruta se anula SOLO si se comprobó que ya no está en el almacén.
     *
     * @return cuántas descargas vencieron en esta pasada
     */
    public int limpiar() {
        record Vencida(long id, long usuarioId, String tablero) {
        }
        List<Vencida> vencidas = jdbc.sql("""
                UPDATE ana_descargas_diferidas SET estado = 'vencida'
                WHERE estado IN ('lista', 'fallida') AND vence <= :ahora
                RETURNING id, solicitada_por AS usuario_id, tablero""")
                .param("ahora", OffsetDateTime.now(reloj)).query(Vencida.class).list();
        for (Vencida v : vencidas) {
            bitacora.registrar("analitica_descarga_diferida_vencida", AnaliticaDescargaDiferida.resumen(v.id(), v.tablero()), v.usuarioId(), null);
        }
        record PorBorrar(long id, String ruta) {
        }
        for (PorBorrar p : jdbc.sql("SELECT id, ruta FROM ana_descargas_diferidas WHERE ruta IS NOT NULL AND estado IN ('vencida', 'fallida')")
                .query(PorBorrar.class).list()) {
            if (borrar(p.ruta())) {
                jdbc.sql("UPDATE ana_descargas_diferidas SET ruta = NULL WHERE id = :id AND ruta = :ruta").param("id", p.id()).param("ruta", p.ruta()).update();
            }
        }
        return vencidas.size();
    }
}
