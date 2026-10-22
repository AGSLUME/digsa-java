package co.com.agsamericas.digsa.tablero;

import co.com.agsamericas.digsa.catalogos.ServicioCatalogos;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Riesgo;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tablero de riesgo por ESM de dispensación (docs/especificaciones/riesgo-tablero): datos crudos por punto en pocas consultas
 * agrupadas, puntaje con {@link Riesgo}, agregados por país y fuerza, pesos editables (14.1) e incidencias de la DIGSA.
 */
@Service
public class ServicioRiesgo {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioRiesgo.class);
    static final Set<String> CERRADAS_PLAN = Set.of("Completado", "Cancelado");

    public record Incidencia(Long puntoId, String fecha, String descripcion, String fuente) {
    }

    public record Cierre(String motivo) {
    }

    private final JdbcClient jdbc;
    private final ServicioCatalogos catalogos;
    private final RegistroBitacora bitacora;
    private final Clock reloj;

    public ServicioRiesgo(JdbcClient jdbc, ServicioCatalogos catalogos, RegistroBitacora bitacora, Clock reloj) {
        this.jdbc = jdbc;
        this.catalogos = catalogos;
        this.bitacora = bitacora;
        this.reloj = reloj;
    }

    LocalDate hoy() {
        return LocalDate.now(reloj.withZone(Fechas.BOGOTA));
    }

    /** Condición del alcance sobre los puntos «p»: Coordinador y DIGSA todo; Sanidad su fuerza; ESM su punto. El resto, nada. */
    static Map.Entry<String, Map<String, Object>> alcancePuntos(Alcance a) {
        return switch (a) {
            case Alcance.Todo t -> Map.entry("true", Map.of());
            case Alcance.Fuerza f -> Map.entry("p.fuerza = :alc", Map.<String, Object>of("alc", f.codigo()));
            case Alcance.Esm e -> Map.entry("p.id = :alc", Map.<String, Object>of("alc", e.puntoId()));
            default -> Map.entry("false", Map.of());
        };
    }

    static void exigirConsulta(Alcance a) {
        if (!(a instanceof Alcance.Todo || a instanceof Alcance.Fuerza || a instanceof Alcance.Esm)) {
            throw new ErrorNegocio(HttpStatus.FORBIDDEN, "El tablero de riesgo es de la coordinación y de los perfiles de consulta.");
        }
    }

    // ---------- Pesos ----------

    public Riesgo.Pesos pesos() {
        Map<String, Integer> p = new LinkedHashMap<>();
        for (String f : Riesgo.FACTORES) {
            p.put(f, entero("riesgo_peso_" + f, Riesgo.PESOS_INICIALES.peso(f)));
        }
        Riesgo.Pesos r = new Riesgo.Pesos(p, entero("riesgo_nivel_alto", 60), entero("riesgo_nivel_medio", 30));
        if (r.problema() != null) {
            LOG.error("[riesgo] Pesos guardados no válidos ({}): se usan los iniciales", r.problema());
            return Riesgo.PESOS_INICIALES;
        }
        return r;
    }

    private int entero(String clave, int defecto) {
        try {
            return Integer.parseInt(catalogos.textoParametro(clave, String.valueOf(defecto)).strip());
        } catch (NumberFormatException e) {
            return defecto;
        }
    }

    /** El coordinador cambia pesos y umbrales (14.1): deben sumar 100; la bitácora lleva el valor anterior y el nuevo. */
    @Transactional
    public String guardarPesos(Riesgo.Pesos nuevos, UsuarioSesion quien) {
        String problema = nuevos.problema();
        if (problema != null) {
            throw ErrorNegocio.validacion(problema);
        }
        Riesgo.Pesos antes = pesos();
        List<String> cambios = new ArrayList<>();
        Map<String, Integer> valores = new LinkedHashMap<>();
        Riesgo.FACTORES.forEach(f -> valores.put("riesgo_peso_" + f, nuevos.peso(f)));
        valores.put("riesgo_nivel_alto", nuevos.alto());
        valores.put("riesgo_nivel_medio", nuevos.medio());
        Map<String, Integer> anteriores = new LinkedHashMap<>();
        Riesgo.FACTORES.forEach(f -> anteriores.put("riesgo_peso_" + f, antes.peso(f)));
        anteriores.put("riesgo_nivel_alto", antes.alto());
        anteriores.put("riesgo_nivel_medio", antes.medio());
        valores.forEach((k, v) -> {
            if (!v.equals(anteriores.get(k))) {
                cambios.add(k + ": " + anteriores.get(k) + "→" + v);
                jdbc.sql("UPDATE par_parametros SET valor = :v, actualizado_por = :u, actualizado = :ahora WHERE clave = :k").param("v", String.valueOf(v))
                        .param("u", quien.usuario()).param("ahora", OffsetDateTime.now(reloj)).param("k", k).update();
            }
        });
        if (cambios.isEmpty()) {
            return "Sin cambios.";
        }
        bitacora.registrar("parametro_editado", "Pesos del riesgo: " + String.join(", ", cambios), quien.id(), quien.usuario());
        return "Pesos guardados: " + String.join(", ", cambios) + ".";
    }

    // ---------- Tablero ----------

    @Transactional(readOnly = true)
    public Map<String, Object> tablero(Alcance alcance, String fuerzaFiltro) {
        exigirConsulta(alcance);
        String fuerza = fuerzaFiltro != null && fuerzaFiltro.matches("^[A-Z]{2,6}$") ? fuerzaFiltro : null;
        Riesgo.Pesos pesos = pesos();
        Map.Entry<String, Map<String, Object>> c = alcancePuntos(alcance);
        List<Map<String, Object>> puntos = jdbc.sql("""
                SELECT p.id, p.nombre, p.fuerza, m.nombre AS municipio FROM puntos p LEFT JOIN par_municipios m ON m.codigo = p.municipio
                WHERE p.activo AND p.unidad = 'ESM' AND p.modalidad = 'Dispensación' AND (""" + c.getKey() + """
                ) AND (CAST(:f AS text) IS NULL OR p.fuerza = :f) ORDER BY p.id""").params(c.getValue()).param("f", fuerza).query().listOfRows();
        List<Long> ids = puntos.stream().map(x -> ((Number) x.get("id")).longValue()).toList();
        Map<Long, Riesgo.Datos> datos = new HashMap<>();
        Map<Long, Map<String, Object>> extra = new HashMap<>();
        if (!ids.isEmpty()) {
            calcularDatos(ids, datos, extra);
        }
        List<Map<String, Object>> esm = new ArrayList<>();
        Map<String, List<Riesgo.Puntaje>> porFuerza = new LinkedHashMap<>();
        List<Riesgo.Puntaje> todos = new ArrayList<>();
        for (Map<String, Object> p : puntos) {
            long id = ((Number) p.get("id")).longValue();
            Riesgo.Datos d = datos.getOrDefault(id, new Riesgo.Datos(0, 0, 0, 0, false, 0, 0));
            Riesgo.Puntaje pt = Riesgo.puntaje(d, pesos);
            todos.add(pt);
            if (!"NA".equals(p.get("fuerza"))) {
                porFuerza.computeIfAbsent((String) p.get("fuerza"), k -> new ArrayList<>()).add(pt);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("puntoId", id);
            m.put("nombre", p.get("nombre"));
            m.put("fuerza", p.get("fuerza"));
            m.put("municipio", p.get("municipio"));
            m.put("total", pt.total());
            m.put("nivel", pt.nivel());
            m.put("factores", pt.factores());
            m.put("datos", Map.of("c", d.c(), "nc", d.nc(), "recurrentes", d.recurrentes(), "inmediata", d.inmediata(), "planOplVencido", d.planOplVencido(),
                    "accionesVencidas", d.accionesVencidas(), "incidencias", d.incidencias()));
            // Sin datos calculados: sin visita cerrada (HashMap: Map.of no admite nulos)
            Map<String, Object> sinDatos = new HashMap<>();
            sinDatos.put("ultimaCerrada", null);
            sinDatos.put("diasSinVisita", null);
            sinDatos.put("sinResumen", false);
            m.putAll(extra.getOrDefault(id, sinDatos));
            esm.add(m);
        }
        esm.sort(Comparator.<Map<String, Object>>comparingInt(x -> -((Integer) x.get("total"))).thenComparing(x -> String.valueOf(x.get("nombre"))));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pais", Riesgo.agregar(todos));
        List<Map<String, Object>> fuerzas = new ArrayList<>();
        porFuerza.forEach((f, lista) -> {
            Riesgo.Agregado ag = Riesgo.agregar(lista);
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("fuerza", f);
            x.put("agregado", ag);
            x.put("principal", Riesgo.factorPrincipal(ag.factores()));
            fuerzas.add(x);
        });
        fuerzas.sort(Comparator.comparing(x -> String.valueOf(x.get("fuerza"))));
        r.put("porFuerza", fuerzas);
        r.put("esm", esm);
        r.put("pesos", pesos.pesos());
        r.put("nombres", Riesgo.NOMBRES);
        r.put("factores", Riesgo.FACTORES);
        r.put("umbrales", Map.of("alto", pesos.alto(), "medio", pesos.medio()));
        r.put("estadoPesos", catalogos.textoParametro("riesgo_estado_pesos", "Propuesta AGS, pendiente de aprobación DIGSA"));
        r.put("fuerzas", jdbc.sql("SELECT DISTINCT p.fuerza FROM puntos p WHERE p.activo AND p.unidad = 'ESM' AND p.modalidad = 'Dispensación' AND p.fuerza <> 'NA' AND ("
                + c.getKey() + ") ORDER BY 1").params(c.getValue()).query(String.class).list());
        r.put("filtroFuerza", fuerza);
        return r;
    }

    /** Datos crudos de cada ESM en consultas agrupadas (sin una consulta por punto). */
    private void calcularDatos(List<Long> ids, Map<Long, Riesgo.Datos> datos, Map<Long, Map<String, Object>> extra) {
        LocalDate hoy = hoy();
        Long[] arr = ids.toArray(Long[]::new);
        // Visitas cerradas de los puntos (más reciente primero), con su acta vigente y su resumen
        record Cerrada(long id, long punto, LocalDate fecha, Long origen, Long acta, Integer c, Integer nc) {
        }
        List<Cerrada> cerradas = jdbc.sql("""
                SELECT v.id, v.punto_id, v.fecha, v.origen_id,
                       (SELECT a.id FROM visitas_actas a WHERE a.visita_id = v.id AND a.estado IN ('enviada', 'parcial') ORDER BY a.ronda DESC LIMIT 1) AS acta,
                       r.c, r.nc
                FROM visitas v LEFT JOIN visitas_resumen r ON r.visita_id = v.id
                WHERE v.estado = 'cerrada' AND v.punto_id = ANY(:ids) ORDER BY v.punto_id, v.fecha DESC, v.id DESC""").param("ids", arr)
                .query((rs, n) -> new Cerrada(rs.getLong(1), rs.getLong(2), rs.getDate(3).toLocalDate(), (Long) rs.getObject(4), (Long) rs.getObject(5),
                        (Integer) rs.getObject(6), (Integer) rs.getObject(7))).list();
        Map<Long, List<Cerrada>> porPunto = new HashMap<>();
        cerradas.forEach(x -> porPunto.computeIfAbsent(x.punto(), k -> new ArrayList<>()).add(x));
        Set<Long> conSeguimientoCerrado = new HashSet<>();
        cerradas.stream().filter(x -> x.origen() != null).forEach(x -> conSeguimientoCerrado.add(x.origen()));
        List<Long> actas = cerradas.stream().map(Cerrada::acta).filter(java.util.Objects::nonNull).toList();
        // Hallazgos por acta (sin los que el líder levantó)
        Map<Long, List<String>> hallazgos = new HashMap<>();
        if (!actas.isEmpty()) {
            jdbc.sql("""
                    SELECT h.acta_id, h.clave FROM visitas_hallazgos h WHERE h.acta_id = ANY(:a)
                      AND NOT EXISTS (SELECT 1 FROM opl_respuestas r WHERE r.visita_id = h.visita_id AND r.clave = h.clave AND r.resolucion = 'levanta')""")
                    .param("a", actas.toArray(Long[]::new)).query((rs, n) -> {
                        hallazgos.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
                        return null;
                    }).list();
        }
        // Intervención inmediata de la última visita completa (respuestas y obligaciones)
        Map<Long, Integer> inmediatas = new HashMap<>();
        List<Long> completas = new ArrayList<>();
        porPunto.values().forEach(l -> l.stream().filter(x -> x.origen() == null).findFirst().ifPresent(x -> completas.add(x.id())));
        if (!completas.isEmpty()) {
            jdbc.sql("""
                    SELECT visita_id, count(*) FROM (SELECT visita_id FROM visitas_respuestas WHERE inmediata AND visita_id = ANY(:v)
                      UNION ALL SELECT visita_id FROM visitas_obligaciones WHERE inmediata AND visita_id = ANY(:v)) x GROUP BY visita_id""")
                    .param("v", completas.toArray(Long[]::new)).query((rs, n) -> inmediatas.put(rs.getLong(1), rs.getInt(2))).list();
        }
        // Visitas con plan abierto: cerradas, con hallazgos y sin un seguimiento cerrado que las reemplace
        Map<Long, List<Cerrada>> abiertasPorPunto = new HashMap<>();
        for (Cerrada x : cerradas) {
            if (x.acta() != null && !hallazgos.getOrDefault(x.acta(), List.of()).isEmpty() && !conSeguimientoCerrado.contains(x.id())) {
                abiertasPorPunto.computeIfAbsent(x.punto(), k -> new ArrayList<>()).add(x);
            }
        }
        List<Long> visitasAbiertas = abiertasPorPunto.values().stream().flatMap(List::stream).map(Cerrada::id).toList();
        Map<Long, Integer> accionesVencidas = new HashMap<>();
        Set<Long> planVencido = new HashSet<>();
        if (!visitasAbiertas.isEmpty()) {
            jdbc.sql("""
                    SELECT p.visita_id, count(*) FROM visitas_plan p WHERE p.visita_id = ANY(:v) AND p.fin < :hoy AND p.estado NOT IN ('Completado', 'Cancelado')
                    GROUP BY p.visita_id""").param("v", visitasAbiertas.toArray(Long[]::new)).param("hoy", hoy)
                    .query((rs, n) -> accionesVencidas.put(rs.getLong(1), rs.getInt(2))).list();
            List<Long> actasAbiertas = abiertasPorPunto.values().stream().flatMap(List::stream).map(Cerrada::acta).toList();
            planVencido.addAll(jdbc.sql("SELECT acta_id FROM opl_planes WHERE acta_id = ANY(:a) AND estado IN ('vencido', 'reportado_digsa')")
                    .param("a", actasAbiertas.toArray(Long[]::new)).query(Long.class).list());
        }
        Map<Long, Integer> incidencias = new HashMap<>();
        jdbc.sql("""
                SELECT punto_id, count(*) FROM riesgo_incidencias WHERE punto_id = ANY(:ids) AND estado = 'abierta' AND fecha >= :desde GROUP BY punto_id""")
                .param("ids", arr).param("desde", hoy.minusMonths(12)).query((rs, n) -> incidencias.put(rs.getLong(1), rs.getInt(2))).list();

        for (long id : ids) {
            List<Cerrada> l = porPunto.getOrDefault(id, List.of());
            Cerrada completa = l.stream().filter(x -> x.origen() == null).findFirst().orElse(null);
            boolean sinResumen = completa != null && completa.c() == null;
            if (sinResumen) {
                LOG.error("[riesgo] La visita {} está cerrada sin resumen: el incumplimiento del punto {} no se mide", completa.id(), id);
            }
            int recurrentes = l.size() < 2 ? 0 : Riesgo.recurrentes(l.get(0).acta() == null ? List.of() : hallazgos.getOrDefault(l.get(0).acta(), List.of()),
                    l.get(1).acta() == null ? List.of() : hallazgos.getOrDefault(l.get(1).acta(), List.of()));
            List<Cerrada> abiertas = abiertasPorPunto.getOrDefault(id, List.of());
            int vencidas = abiertas.stream().mapToInt(x -> accionesVencidas.getOrDefault(x.id(), 0)).sum();
            boolean plan = abiertas.stream().anyMatch(x -> planVencido.contains(x.acta()));
            datos.put(id, new Riesgo.Datos(completa == null || sinResumen ? 0 : completa.c(), completa == null || sinResumen ? 0 : completa.nc(), recurrentes,
                    completa == null ? 0 : inmediatas.getOrDefault(completa.id(), 0), plan, vencidas, incidencias.getOrDefault(id, 0)));
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("ultimaCerrada", l.isEmpty() ? null : l.get(0).fecha().toString());
            x.put("diasSinVisita", l.isEmpty() ? null : ChronoUnit.DAYS.between(l.get(0).fecha(), hoy));
            x.put("sinResumen", sinResumen);
            extra.put(id, x);
        }
    }

    // ---------- Incidencias de la DIGSA ----------

    /** Coordinador y DIGSA registran incidencias reportadas por la DIGSA sobre un ESM de dispensación (14.1). */
    @Transactional
    public long registrarIncidencia(Incidencia i, UsuarioSesion quien) {
        if (i.puntoId() == null) {
            throw ErrorNegocio.validacion("Elija el ESM.");
        }
        boolean esEsm = jdbc.sql("SELECT count(*) FROM puntos WHERE id = :p AND activo AND unidad = 'ESM' AND modalidad = 'Dispensación'").param("p", i.puntoId())
                .query(Long.class).single() > 0;
        if (!esEsm) {
            throw ErrorNegocio.validacion("La incidencia se registra sobre un ESM de dispensación activo.");
        }
        LocalDate fecha = Fechas.valida(i.fecha()).orElseThrow(() -> ErrorNegocio.validacion("La fecha no es válida."));
        if (fecha.isAfter(hoy())) {
            throw ErrorNegocio.validacion("La fecha de la incidencia no puede ser futura.");
        }
        String d = i.descripcion() == null ? "" : i.descripcion().strip();
        if (d.length() < 10 || d.length() > 2000) {
            throw ErrorNegocio.validacion("Describa la incidencia (de 10 a 2000 caracteres).");
        }
        String fuente = i.fuente() == null || i.fuente().isBlank() ? null : i.fuente().strip();
        if (fuente != null && fuente.length() > 200) {
            throw ErrorNegocio.validacion("La fuente tiene máximo 200 caracteres.");
        }
        long id = jdbc.sql("""
                INSERT INTO riesgo_incidencias (punto_id, fecha, descripcion, fuente, registrada_por, creado) VALUES (:p, :f, :d, :fu, :u, :ahora) RETURNING id""")
                .param("p", i.puntoId()).param("f", fecha).param("d", d).param("fu", fuente).param("u", quien.id()).param("ahora", OffsetDateTime.now(reloj))
                .query(Long.class).single();
        bitacora.registrar("incidencia_registrada", "Incidencia " + id + " de la DIGSA sobre el punto " + i.puntoId(), quien.id(), quien.usuario());
        return id;
    }

    @Transactional
    public String cerrarIncidencia(long id, Cierre c, UsuarioSesion quien) {
        String m = c == null || c.motivo() == null ? "" : c.motivo().strip();
        if (m.length() < 10 || m.length() > 800) {
            throw ErrorNegocio.validacion("Escriba el motivo del cierre (de 10 a 800 caracteres).");
        }
        int n = jdbc.sql("""
                UPDATE riesgo_incidencias SET estado = 'cerrada', cerrada_por = :u, cerrada_en = :ahora, motivo_cierre = :m WHERE id = :id AND estado = 'abierta'""")
                .param("u", quien.id()).param("ahora", OffsetDateTime.now(reloj)).param("m", m).param("id", id).update();
        if (n != 1) {
            throw ErrorNegocio.noEncontrado("La incidencia no existe o ya está cerrada.");
        }
        bitacora.registrar("incidencia_cerrada", "Incidencia " + id + " cerrada", quien.id(), quien.usuario());
        return "Incidencia cerrada.";
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> incidencias(Alcance alcance) {
        exigirConsulta(alcance);
        Map.Entry<String, Map<String, Object>> c = alcancePuntos(alcance);
        return jdbc.sql("""
                SELECT i.id, i.punto_id AS "puntoId", p.nombre AS punto, p.fuerza, i.fecha, i.descripcion, i.fuente, i.estado, i.creado, i.cerrada_en AS "cerradaEn",
                       i.motivo_cierre AS "motivoCierre"
                FROM riesgo_incidencias i JOIN puntos p ON p.id = i.punto_id WHERE (""" + c.getKey() + ") ORDER BY i.fecha DESC, i.id DESC LIMIT 500")
                .params(c.getValue()).query().listOfRows().stream().map(x -> {
                    Map<String, Object> m = new LinkedHashMap<>(x);
                    m.replaceAll((k, v) -> v instanceof java.sql.Date dd ? dd.toString() : v);
                    return (Map<String, Object>) m;
                }).toList();
    }
}
