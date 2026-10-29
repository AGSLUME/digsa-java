package co.com.agsamericas.digsa.analitica.tableros;

import co.com.agsamericas.digsa.analitica.PerfilesOperacion;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.ConsultaAnalitica;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bitácora de analítica (A5 4.7.4 y 14.9; analitica-tableros «Bitácora de consultas, filtros, descargas y rastreos»): cada consulta de
 * tablero, descarga y rastreo deja una fila en {@code ana_consultas} (usuario, perfil, alcance, consulta estructurada, corte, registros,
 * huella y duración) y un evento {@code analitica_*} en {@code log_transacciones}.
 *
 * <p>Sin datos personales (revisión A3 2026-10-23): de los filtros solo se guardan los valores que pasan la lista blanca de su variable
 * ({@code formaValida}), recortados a 60 caracteres, y solo si la consulta se resolvió («ok»: los valores existen en los catálogos); en
 * una consulta rechazada o fallida se guarda cuántos valores tenía cada variable, nunca el texto, y las advertencias van sin el valor.
 *
 * <p>Las consultas en pantalla nunca se caen por la bitácora (si no se puede escribir, queda en el registro de errores). El rastreo y el
 * CSV de registros de origen, que entregan detalle seudonimizado, se registran ANTES de entregar y fallan cerrado ({@link #registrarAntes}).
 */
@Component
class BitacoraAnalitica {

    private static final Logger LOG = LoggerFactory.getLogger(BitacoraAnalitica.class);
    static final int MAX_VALOR = 60;
    static final int MAX_FILTROS_JSON = 4000;
    static final String NO_REGISTRADA = "No se pudo registrar la consulta en la bitácora; intente de nuevo.";

    /** Qué se registra de una operación. */
    record Entrada(String accion, ConsultaAnalitica consulta, String objeto, Long corte, String formato, Long registros, String huella,
                   long duracionMs, String resultado, List<String> advertencias, String alcance) {
    }

    private final JdbcClient jdbc;
    private final RegistroBitacora bitacora;
    private final PerfilesOperacion perfiles;
    private final ObjectMapper json;
    private final Clock reloj;
    private final TransactionTemplate propia;

    BitacoraAnalitica(JdbcClient jdbc, RegistroBitacora bitacora, PerfilesOperacion perfiles, ObjectMapper json, Clock reloj,
                      PlatformTransactionManager transacciones) {
        this.jdbc = jdbc;
        this.bitacora = bitacora;
        this.perfiles = perfiles;
        this.json = json;
        this.reloj = reloj;
        // Transacción propia: se puede registrar desde dentro de la transacción de solo lectura de la consulta (CSV en flujo)
        this.propia = new TransactionTemplate(transacciones);
        this.propia.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Registra sin tumbar la consulta (consultas en pantalla y descargas de tableros agregados). */
    void registrar(Entrada e) {
        try {
            insertar(e);
        } catch (RuntimeException ex) {
            LOG.error("[analitica] No se pudo registrar la {} en ana_consultas: {}", e.accion(), e.objeto(), ex);
        }
    }

    /**
     * Registra ANTES de entregar detalle seudonimizado (rastreo y CSV de registros de origen): si no se puede escribir, la operación no
     * entrega nada (503). Corre en su propia transacción.
     */
    void registrarAntes(Entrada e) {
        try {
            propia.executeWithoutResult(s -> insertar(e));
        } catch (RuntimeException ex) {
            LOG.error("[analitica] No se pudo registrar la {} en ana_consultas: no se entregan los registros", e.accion(), ex);
            throw new ErrorNegocio(HttpStatus.SERVICE_UNAVAILABLE, NO_REGISTRADA);
        }
    }

    private void insertar(Entrada e) {
        UsuarioSesion u = UsuarioSesion.actual().orElse(null);
        String tipo = switch (e.accion()) {
            case "descarga" -> "analitica_descarga";
            case "traza" -> "analitica_traza";
            case "traza_descarga" -> "analitica_traza_descarga";
            default -> "analitica_consulta";
        };
        String accion = "traza_descarga".equals(e.accion()) ? "traza" : e.accion();
        boolean resuelta = "ok".equals(e.resultado());
        jdbc.sql("""
                INSERT INTO ana_consultas (usuario_id, usuario, perfil, alcance, accion, tablero, objeto, filtros, corte_cargue_id, formato, registros,
                                           huella, duracion_ms, resultado, advertencias, creado)
                VALUES (:u, :usuario, :perfil, :alcance, :accion, :tablero, :objeto, CAST(:filtros AS jsonb), :corte, :formato, :registros,
                        :huella, :duracion, :resultado, CAST(:advertencias AS text[]), :creado)""")
                .param("u", u == null ? null : u.id()).param("usuario", u == null ? "sistema" : cortar(u.usuario(), 60))
                .param("perfil", cortar(perfiles.nombres(), 200)).param("alcance", cortar(e.alcance() == null ? "—" : e.alcance(), 120))
                .param("accion", accion).param("tablero", e.consulta() == null || e.consulta().tablero() == null ? null : e.consulta().tablero().codigo())
                .param("objeto", e.objeto() == null ? null : cortar(e.objeto(), 300)).param("filtros", filtros(e.consulta(), resuelta))
                .param("corte", e.corte()).param("formato", e.formato()).param("registros", e.registros()).param("huella", e.huella())
                .param("duracion", e.duracionMs()).param("resultado", e.resultado())
                .param("advertencias", e.advertencias() == null ? new String[0] : e.advertencias().stream().map(x -> cortar(x, 300)).toArray(String[]::new))
                .param("creado", OffsetDateTime.now(reloj)).update();
        String desc = (e.objeto() == null ? "" : e.objeto()) + " · " + resumenFiltros(e.consulta(), resuelta)
                + (e.corte() == null ? "" : " · corte cargue " + e.corte()) + (e.formato() == null ? "" : " · " + e.formato())
                + (e.registros() == null ? "" : " · " + e.registros() + " registros") + (e.huella() == null ? "" : " · huella " + e.huella().substring(0, 12))
                + " · " + e.duracionMs() + " ms" + (resuelta ? "" : " · " + e.resultado());
        bitacora.registrar(tipo, cortar(desc, 500), u == null ? null : u.id(), u == null ? null : u.usuario());
    }

    /**
     * Página de la bitácora (50 filas, de la más reciente a la más antigua), paginada por llave {@code (creado, id)} con el índice
     * ix_ana_consultas_creado (nunca OFFSET). El usuario se resuelve a su id (índice por usuario_id); las fechas son días de Bogotá. El total
     * se cuenta con tope (10000: «más de 10000»). {@code antes} es el cursor (id de la última fila) que devolvió la página anterior.
     */
    Map<String, Object> listar(String usuario, String accion, java.time.LocalDate desde, java.time.LocalDate hasta, String antes) {
        Long usuarioId = null;
        if (usuario != null && !usuario.isBlank()) {
            usuarioId = jdbc.sql("SELECT id FROM usuarios WHERE usuario = :u").param("u", usuario.strip()).query(Long.class).optional().orElse(-1L);
        }
        Long cId = null;
        if (antes != null && !antes.isBlank()) {
            try {
                cId = Long.parseLong(antes.strip());
            } catch (NumberFormatException e) {
                throw ErrorNegocio.validacion("La página no es válida.");
            }
        }
        java.time.ZoneId bogota = co.com.agsamericas.digsa.reglas.Fechas.BOGOTA;
        OffsetDateTime d = desde == null ? null : desde.atStartOfDay(bogota).toOffsetDateTime();
        OffsetDateTime h = hasta == null ? null : hasta.plusDays(1).atStartOfDay(bogota).toOffsetDateTime();
        String where = " WHERE (CAST(:u AS bigint) IS NULL OR usuario_id = :u) AND (CAST(:a AS text) IS NULL OR accion = :a)"
                + " AND (CAST(:d AS timestamptz) IS NULL OR creado >= :d) AND (CAST(:h AS timestamptz) IS NULL OR creado < :h)";
        long total = jdbc.sql("SELECT count(*) FROM (SELECT 1 FROM ana_consultas" + where + " LIMIT 10001) s").param("u", usuarioId)
                .param("a", accion).param("d", d).param("h", h).query(Long.class).single();
        List<Map<String, Object>> filas = jdbc.sql("""
                SELECT id, usuario, perfil, alcance, accion, tablero, objeto, filtros::text AS filtros, corte_cargue_id, formato, registros, huella,
                       duracion_ms, resultado, creado FROM ana_consultas""" + where
                + " AND (CAST(:ci AS bigint) IS NULL OR (creado, id) < (SELECT c.creado, c.id FROM ana_consultas c WHERE c.id = :ci)) ORDER BY creado DESC, id DESC LIMIT 51")
                .param("u", usuarioId).param("a", accion).param("d", d).param("h", h).param("ci", cId).query().listOfRows();
        boolean hayMas = filas.size() > 50;
        List<Map<String, Object>> pagina = new java.util.ArrayList<>(filas.subList(0, Math.min(50, filas.size())));
        String siguiente = null;
        if (hayMas) {
            Map<String, Object> u = pagina.getLast();
            siguiente = String.valueOf(u.get("id"));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("filas", pagina);
        r.put("total", Math.min(total, 10000));
        r.put("totalTope", total > 10000);
        r.put("siguiente", siguiente);
        return r;
    }

    private static String cortar(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Valores de una variable que se pueden escribir: los de la lista blanca, recortados; los demás, un marcador sin el texto. */
    static List<String> valoresSeguros(co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Variable v, List<String> valores) {
        return valores.stream().map(x -> v.formaValida(x) ? cortar(x, MAX_VALOR) : "(valor no válido)").toList();
    }

    /**
     * Filtros legibles para la bitácora general y los metadatos del CSV: «fuerza=EJC,ARC; atc=L04». Con {@code resuelta} falso (consulta
     * rechazada o fallida) solo el número de valores de cada variable.
     */
    static String resumenFiltros(ConsultaAnalitica q, boolean resuelta) {
        if (q == null || q.filtros().isEmpty()) {
            return "sin filtros";
        }
        StringBuilder sb = new StringBuilder();
        q.filtros().forEach((k, v) -> sb.append(sb.isEmpty() ? "" : "; ").append(k.parametro()).append('=')
                .append(resuelta ? String.join(",", valoresSeguros(k, v)) : v.size() + (v.size() == 1 ? " valor" : " valores")));
        return cortar(sb.toString(), MAX_FILTROS_JSON);
    }

    private String filtros(ConsultaAnalitica q, boolean resuelta) {
        if (q == null) {
            return "{}";
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intencion", q.intencion().name());
        Map<String, Object> f = new LinkedHashMap<>();
        q.filtros().forEach((k, v) -> f.put(k.parametro(), resuelta ? valoresSeguros(k, v) : Map.of("valores", v.size())));
        m.put("filtros", f);
        m.put("desde", q.desde() == null ? null : q.desde().toString());
        m.put("hasta", q.hasta() == null ? null : q.hasta().toString());
        m.put("granularidad", q.granularidad().codigo());
        m.put("comparacion", q.comparacion().codigo());
        m.put("nivel", q.nivel() == null ? null : q.nivel().codigo());
        m.put("nivelAtc", q.nivelAtc());
        m.put("orden", q.orden().codigo());
        m.put("metricas", q.metricas().stream().map(x -> x.codigo()).toList());
        m.put("dimensiones", q.dimensiones().stream().map(x -> x.codigo()).toList());
        try {
            String s = json.writeValueAsString(m);
            if (s.length() > MAX_FILTROS_JSON) {
                // Muy largo (50 valores por variable): solo los conteos
                q.filtros().forEach((k, v) -> f.put(k.parametro(), Map.of("valores", v.size())));
                s = json.writeValueAsString(m);
            }
            return s;
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }
}
