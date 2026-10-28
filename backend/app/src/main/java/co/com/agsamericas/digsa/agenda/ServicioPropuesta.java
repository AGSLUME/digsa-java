package co.com.agsamericas.digsa.agenda;

import co.com.agsamericas.digsa.catalogos.ServicioCatalogos;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Plazos;
import co.com.agsamericas.digsa.reglas.PropuestaAgenda;
import co.com.agsamericas.digsa.reglas.Reparto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Propuesta de agenda y cobertura (docs/especificaciones/propuesta-agenda; decisiones 13, 32 y 13.4 del 2026-10-20). Arma un
 * borrador por ciclo (qué se visita, auditor líder, fecha) con {@link PropuestaAgenda}; el coordinador lo ajusta, lo recalcula y al
 * confirmar se programa y notifica cada visita por el camino de siempre (camino A). Nada se programa ni se notifica hasta confirmar.
 */
@Service
public class ServicioPropuesta {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioPropuesta.class);
    static final Set<String> ABIERTAS = Set.of("programada", "notificada", "reprogramada", "ejecucion");
    /** Ciudades principales del equipo (Anexo 3 p.9 y p.13; respuestas de la DIGSA): código DANE → nombre. */
    static final Map<String, String> CIUDADES = ciudades();

    private static Map<String, String> ciudades() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("11001", "Bogotá");
        m.put("05001", "Medellín");
        m.put("76001", "Cali");
        m.put("08001", "Barranquilla");
        m.put("68001", "Bucaramanga");
        m.put("13001", "Cartagena");
        m.put("73001", "Ibagué");
        m.put("41001", "Neiva");
        return java.util.Collections.unmodifiableMap(m);
    }

    public record Generar(String ciclo, Integer anio) {
    }

    public record Ajuste(Long auditorId, List<Long> otros, String fecha) {
    }

    /** Periodo de un ciclo: desde, hasta (límite) y año de contrato (nulo en la línea base). */
    record Periodo(LocalDate desde, LocalDate hasta, Integer anio) {
    }

    private final JdbcClient jdbc;
    private final RepositorioVisitas visitas;
    private final ServicioAgenda agenda;
    private final ServicioConsultaAgenda consulta;
    private final ServicioCatalogos catalogos;
    private final RegistroBitacora bitacora;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock reloj;

    public ServicioPropuesta(JdbcClient jdbc, RepositorioVisitas visitas, ServicioAgenda agenda, ServicioConsultaAgenda consulta, ServicioCatalogos catalogos,
                             RegistroBitacora bitacora, TransactionTemplate tx, ObjectMapper json, Clock reloj) {
        this.jdbc = jdbc;
        this.visitas = visitas;
        this.agenda = agenda;
        this.consulta = consulta;
        this.catalogos = catalogos;
        this.bitacora = bitacora;
        this.tx = tx;
        this.json = json;
        this.reloj = reloj;
    }

    LocalDate hoy() {
        return LocalDate.now(reloj.withZone(Fechas.BOGOTA));
    }

    int parametro(String clave, int defecto, int minimo) {
        try {
            return Math.max(minimo, Integer.parseInt(catalogos.textoParametro(clave, String.valueOf(defecto)).strip()));
        } catch (NumberFormatException e) {
            return defecto;
        }
    }

    LocalDate inicioContrato() {
        String s = catalogos.textoParametro("inicio_contrato", "");
        return s.isBlank() ? null : Fechas.valida(s.strip()).orElse(null);
    }

    static PropuestaAgenda.Ciclo ciclo(String codigo) {
        PropuestaAgenda.Ciclo c = codigo == null ? null : PropuestaAgenda.CICLOS.get(codigo);
        if (c == null) {
            throw ErrorNegocio.validacion("El ciclo no es válido.");
        }
        return c;
    }

    /** Línea base: inicio + `inicial_meses`; anuales: el año de contrato N (decisión 13.4). Falta el inicio del contrato: 422. */
    Periodo periodo(PropuestaAgenda.Ciclo c, Integer anio) {
        LocalDate inicio = inicioContrato();
        if (inicio == null) {
            throw ErrorNegocio.validacion("Registre la fecha de inicio del contrato (Catálogos › Parámetros, «inicio_contrato»): define los plazos de los ciclos.");
        }
        if (!c.anual()) {
            return new Periodo(inicio, inicio.plusMonths(parametro("inicial_meses", 3, 1)), null);
        }
        int n = anio == null ? PropuestaAgenda.anioDe(inicio, hoy()) : anio;
        if (n < 1 || n > 20) {
            throw ErrorNegocio.validacion("El año de contrato no es válido.");
        }
        LocalDate[] p = PropuestaAgenda.anioDeContrato(inicio, n);
        return new Periodo(p[0], p[1], n);
    }

    // ---------- Datos y cobertura ----------

    /** Cobertura por ciclo del año de contrato elegido, auditores, parámetros y avisos de la cobertura. */
    public Map<String, Object> datos(Integer anio) {
        LocalDate inicio = inicioContrato();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("inicioContrato", inicio == null ? null : inicio.toString());
        int actual = inicio == null ? 1 : PropuestaAgenda.anioDe(inicio, hoy());
        int elegido = anio == null ? actual : anio;
        if (elegido < 1 || elegido > Math.max(20, actual + 1)) {
            throw ErrorNegocio.validacion("El año de contrato no es válido.");
        }
        m.put("anio", elegido);
        List<Map<String, Object>> anios = new ArrayList<>();
        if (inicio != null) {
            for (int n = 1; n <= Math.max(actual + 1, elegido); n++) {
                LocalDate[] p = PropuestaAgenda.anioDeContrato(inicio, n);
                anios.add(Map.of("anio", n, "desde", p[0].toString(), "hasta", p[1].toString()));
            }
        }
        m.put("anios", anios);
        List<Map<String, Object>> ciclos = new ArrayList<>();
        for (PropuestaAgenda.Ciclo c : PropuestaAgenda.CICLOS.values()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("ciclo", c.codigo());
            x.put("nombre", c.nombre());
            x.put("tipo", c.tipo());
            if (inicio == null) {
                x.put("error", "Falta la fecha de inicio del contrato.");
                ciclos.add(x);
                continue;
            }
            Periodo p = periodo(c, elegido);
            x.put("desde", p.desde().toString());
            x.put("limite", p.hasta().toString());
            x.put("anioContrato", p.anio());
            x.putAll(cobertura(c, p));
            x.put("borradorId", jdbc.sql("SELECT id FROM propuestas_agenda WHERE ciclo = :c AND coalesce(anio_contrato, 0) = :a AND estado = 'borrador'")
                    .param("c", c.codigo()).param("a", p.anio() == null ? 0 : p.anio()).query(Long.class).optional().orElse(null));
            x.put("confirmadaIncompletaId", jdbc.sql("""
                    SELECT p.id FROM propuestas_agenda p WHERE p.ciclo = :c AND coalesce(p.anio_contrato, 0) = :a AND p.estado = 'confirmada'
                      AND EXISTS (SELECT 1 FROM propuestas_agenda_items i WHERE i.propuesta_id = p.id AND i.resultado IS NULL)
                    ORDER BY p.id DESC LIMIT 1""").param("c", c.codigo()).param("a", p.anio() == null ? 0 : p.anio()).query(Long.class).optional().orElse(null));
            ciclos.add(x);
        }
        m.put("ciclos", ciclos);
        m.put("auditores", auditoresBasicos());
        m.put("visitasPorDia", parametro("visitas_por_dia", 2, 1));
        m.put("kmMismoDia", parametro("km_mismo_dia", 40, 1));
        m.put("avisos", avisos());
        return m;
    }

    private Map<String, Object> cobertura(PropuestaAgenda.Ciclo c, Periodo p) {
        String filtroFecha = c.anual() ? " AND v.fecha BETWEEN :desde AND :hasta" : "";
        Map<String, Object> r = jdbc.sql("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM visitas v WHERE v.punto_id = p.id AND v.tipo = ANY(:tipos) AND v.estado = 'cerrada'""" + filtroFecha + """
                       )) AS cerradas,
                       count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM visitas v WHERE v.punto_id = p.id AND v.tipo = ANY(:tipos) AND v.estado = 'cerrada'""" + filtroFecha + """
                       ) AND EXISTS (SELECT 1 FROM visitas v WHERE v.punto_id = p.id AND v.tipo = ANY(:tipos) AND v.estado = ANY(:abiertas)""" + filtroFecha + """
                       )) AS agendadas
                FROM puntos p WHERE p.activo AND p.unidad = :u AND (CAST(:mod AS text) IS NULL OR p.modalidad = :mod)""")
                .param("tipos", c.cubren().toArray(String[]::new)).param("abiertas", ABIERTAS.toArray(String[]::new)).param("u", c.unidad())
                .param("mod", c.modalidad()).param("desde", p.desde()).param("hasta", p.hasta()).query().singleRow();
        int total = ((Number) r.get("total")).intValue();
        int cerradas = ((Number) r.get("cerradas")).intValue();
        int agendadas = ((Number) r.get("agendadas")).intValue();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("cerradas", cerradas);
        m.put("agendadas", agendadas);
        m.put("faltan", Math.max(0, total - cerradas - agendadas));
        return m;
    }

    /** Ciudades principales sin auditor con base en ellas y ESM de dispensación sin auditor asignado (Anexo 3 p.9 y p.13). */
    List<String> avisos() {
        List<String> r = new ArrayList<>();
        Set<String> conBase = new LinkedHashSet<>(jdbc.sql("""
                SELECT DISTINCT a.municipio_base FROM auditores a JOIN usuarios u ON u.id = a.usuario_id WHERE u.activo AND a.municipio_base IS NOT NULL""")
                .query(String.class).list());
        CIUDADES.forEach((codigo, nombre) -> {
            if (!conBase.contains(codigo)) {
                r.add(nombre + ": sin auditor con base en la ciudad.");
            }
        });
        long sinAsignado = jdbc.sql("SELECT count(*) FROM puntos WHERE activo AND unidad = 'ESM' AND modalidad = 'Dispensación' AND auditor_asignado IS NULL")
                .query(Long.class).single();
        if (sinAsignado > 0) {
            r.add(sinAsignado + " ESM de dispensación sin auditor asignado (Anexo 3 p.9).");
        }
        return r;
    }

    private List<Map<String, Object>> auditoresBasicos() {
        return jdbc.sql("""
                SELECT u.id, u.nombre, coalesce(a.rol, 'otro') AS rol FROM usuarios u
                JOIN usuarios_perfiles up ON up.usuario_id = u.id AND up.perfil_id = :p LEFT JOIN auditores a ON a.usuario_id = u.id
                WHERE u.activo ORDER BY u.nombre""").param("p", co.com.agsamericas.digsa.reglas.Perfil.AUDITOR.id()).query().listOfRows();
    }

    // ---------- Cálculo ----------

    /** Puntos activos del ciclo sin visita no cancelada de los tipos que lo cubren (en la línea base, en cualquier fecha). */
    List<PropuestaAgenda.Punto> pendientes(PropuestaAgenda.Ciclo c, Periodo p) {
        String filtroFecha = c.anual() ? " AND v.fecha BETWEEN :desde AND :hasta" : "";
        return jdbc.sql("""
                SELECT p.id, p.nombre, p.unidad, p.municipio, p.llega_desde, p.auditor_asignado, p.exige_qf FROM puntos p
                WHERE p.activo AND p.unidad = :u AND (CAST(:mod AS text) IS NULL OR p.modalidad = :mod)
                  AND NOT EXISTS (SELECT 1 FROM visitas v WHERE v.punto_id = p.id AND v.tipo = ANY(:tipos) AND v.estado <> 'cancelada'""" + filtroFecha + """
                ) ORDER BY p.id""").param("u", c.unidad()).param("mod", c.modalidad()).param("tipos", c.cubren().toArray(String[]::new))
                .param("desde", p.desde()).param("hasta", p.hasta())
                .query((rs, n) -> new PropuestaAgenda.Punto(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        (Long) rs.getObject(6), rs.getBoolean(7))).list();
    }

    /** Auditores activos con perfil Auditor, su rol, municipio base y carga (visitas no canceladas como líder o apoyo en el rango). */
    List<PropuestaAgenda.Auditor> auditores(LocalDate desde, LocalDate hasta) {
        return jdbc.sql("""
                SELECT u.id, u.nombre, coalesce(a.rol, 'otro') AS rol, a.municipio_base,
                       (SELECT count(*) FROM visitas_auditores x JOIN visitas v ON v.id = x.visita_id
                        WHERE x.usuario_id = u.id AND v.estado <> 'cancelada' AND v.fecha BETWEEN :desde AND :hasta) AS carga
                FROM usuarios u JOIN usuarios_perfiles up ON up.usuario_id = u.id AND up.perfil_id = :p LEFT JOIN auditores a ON a.usuario_id = u.id
                WHERE u.activo ORDER BY u.id""").param("p", co.com.agsamericas.digsa.reglas.Perfil.AUDITOR.id()).param("desde", desde).param("hasta", hasta)
                .query((rs, n) -> new PropuestaAgenda.Auditor(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5))).list();
    }

    Map<String, double[]> coordenadas() {
        Map<String, double[]> r = new HashMap<>();
        jdbc.sql("SELECT codigo, latitud, longitud FROM par_municipios WHERE latitud IS NOT NULL AND longitud IS NOT NULL").query((rs, n) -> {
            r.put(rs.getString(1).strip(), new double[] {rs.getDouble(2), rs.getDouble(3)});
            return null;
        }).list();
        return r;
    }

    /** Visitas ya agendadas por auditor y día, con su municipio de acceso. */
    Map<Long, Map<LocalDate, List<String>>> ocupados(LocalDate desde, LocalDate hasta) {
        Map<Long, Map<LocalDate, List<String>>> r = new HashMap<>();
        jdbc.sql("""
                SELECT x.usuario_id, v.fecha, coalesce(p.llega_desde, p.municipio) FROM visitas v JOIN visitas_auditores x ON x.visita_id = v.id
                JOIN puntos p ON p.id = v.punto_id WHERE v.estado <> 'cancelada' AND v.fecha BETWEEN :desde AND :hasta""")
                .param("desde", desde).param("hasta", hasta).query((rs, n) -> {
                    String m = rs.getString(3);
                    r.computeIfAbsent(rs.getLong(1), k -> new HashMap<>()).computeIfAbsent(rs.getDate(2).toLocalDate(), k -> new ArrayList<>())
                            .add(m == null ? null : m.strip());
                    return null;
                }).list();
        return r;
    }

    /** Días posibles del ciclo: hábiles, con la notificación previa, dentro del periodo y hasta el límite. */
    List<LocalDate> dias(Periodo p) {
        return PropuestaAgenda.dias(consulta.plazos(), hoy(), p.desde(), p.hasta());
    }

    private PropuestaAgenda.Resultado calcular(PropuestaAgenda.Ciclo c, Periodo p, Map<Long, PropuestaAgenda.Fijo> fijos) {
        return calcular(c, p, fijos, null);
    }

    private PropuestaAgenda.Resultado calcular(PropuestaAgenda.Ciclo c, Periodo p, Map<Long, PropuestaAgenda.Fijo> fijos, Set<Long> soloPuntos) {
        List<LocalDate> dias = dias(p);
        LocalDate primero = dias.isEmpty() ? hoy() : dias.get(0);
        List<PropuestaAgenda.Punto> pend = pendientes(c, p);
        if (soloPuntos != null) {
            pend = pend.stream().filter(x -> soloPuntos.contains(x.id())).toList();
        }
        return PropuestaAgenda.generar(pend, auditores(primero, p.hasta()), coordenadas(), dias, parametro("visitas_por_dia", 2, 1),
                parametro("km_mismo_dia", 40, 1), ocupados(primero, p.hasta()), fijos);
    }

    // ---------- Borrador ----------

    /** Descarta el borrador anterior del ciclo y crea uno nuevo. Nada se programa ni se notifica. */
    public Map<String, Object> generar(Generar g, UsuarioSesion quien) {
        PropuestaAgenda.Ciclo c = ciclo(g.ciclo());
        Periodo p = periodo(c, g.anio());
        if (p.hasta().isBefore(hoy())) {
            throw ErrorNegocio.validacion("El plazo de este ciclo ya venció (" + p.hasta() + "). Programe lo que falte desde la agenda.");
        }
        if (dias(p).isEmpty()) {
            throw ErrorNegocio.validacion("No queda ningún día hábil antes del límite del ciclo (" + p.hasta() + ") que deje los 15 días de notificación previa.");
        }
        PropuestaAgenda.Resultado r = calcular(c, p, Map.of());
        if (r.items().isEmpty()) {
            return Map.of("mensaje", "No hay puntos pendientes en este ciclo: la cobertura está completa o ya está agendada.");
        }
        long id;
        try {
            id = generarBorrador(c, p, r, quien);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "Otro usuario generó la propuesta de este ciclo al mismo tiempo. Recargue.");
        }
        bitacora.registrar("propuesta_generada", "Propuesta " + id + " · " + c.codigo() + (p.anio() == null ? "" : " · año " + p.anio()) + " · "
                + r.items().size() + " visita(s)", quien.id(), quien.usuario());
        return Map.of("id", id, "mensaje", textoResumen(r));
    }

    private long generarBorrador(PropuestaAgenda.Ciclo c, Periodo p, PropuestaAgenda.Resultado r, UsuarioSesion quien) {
        return tx.execute(t -> {
            jdbc.sql("UPDATE propuestas_agenda SET estado = 'descartada' WHERE ciclo = :c AND coalesce(anio_contrato, 0) = :a AND estado = 'borrador'")
                    .param("c", c.codigo()).param("a", p.anio() == null ? 0 : p.anio()).update();
            long nuevo = jdbc.sql("""
                    INSERT INTO propuestas_agenda (ciclo, anio_contrato, periodo_desde, hasta, resumen, creado_por, creado)
                    VALUES (:c, :a, :d, :h, CAST(:r AS jsonb), :u, :ahora) RETURNING id""")
                    .param("c", c.codigo()).param("a", p.anio()).param("d", p.desde()).param("h", p.hasta()).param("r", resumenJson(r)).param("u", quien.id())
                    .param("ahora", OffsetDateTime.now(reloj)).query(Long.class).single();
            guardarItems(nuevo, r);
            return nuevo;
        });
    }

    static String textoResumen(PropuestaAgenda.Resultado r) {
        return r.resumen().visitas() + " visita(s) · " + r.resumen().auditores() + " auditor(es) · " + r.resumen().dias() + " día(s) hábil(es) · "
                + (r.resumen().cabe() ? "cabe en el plazo." : "no todo cabe: revise las alertas.");
    }

    private String resumenJson(PropuestaAgenda.Resultado r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("visitas", r.resumen().visitas());
        m.put("auditores", r.resumen().auditores());
        m.put("dias", r.resumen().dias());
        m.put("desde", r.resumen().desde() == null ? null : r.resumen().desde().toString());
        m.put("hasta", r.resumen().hasta() == null ? null : r.resumen().hasta().toString());
        m.put("cabe", r.resumen().cabe());
        m.put("alertas", r.alertas());
        m.put("texto", textoResumen(r));
        try {
            return json.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void guardarItems(long propuestaId, PropuestaAgenda.Resultado r) {
        for (PropuestaAgenda.Item it : r.items()) {
            String alertas;
            try {
                alertas = json.writeValueAsString(it.alertas());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            long itemId = jdbc.sql("""
                    INSERT INTO propuestas_agenda_items (propuesta_id, punto_id, auditor_id, fecha, fijo, km, motivo, alertas)
                    VALUES (:p, :punto, :a, :f, :fijo, :km, :m, CAST(:al AS jsonb)) RETURNING id""")
                    .param("p", propuestaId).param("punto", it.puntoId()).param("a", it.auditorId()).param("f", it.fecha()).param("fijo", it.fijo())
                    .param("km", it.km() == null ? null : it.km().intValue()).param("m", it.motivo()).param("al", alertas).query(Long.class).single();
            guardarOtros(itemId, it.otros());
        }
    }

    private void guardarOtros(long itemId, List<Long> otros) {
        jdbc.sql("DELETE FROM propuestas_agenda_items_otros WHERE item_id = :i").param("i", itemId).update();
        int orden = 1;
        for (Long o : otros) {
            jdbc.sql("INSERT INTO propuestas_agenda_items_otros (item_id, usuario_id, orden) VALUES (:i, :u, :o)").param("i", itemId).param("u", o)
                    .param("o", orden++).update();
        }
    }

    /** La propuesta con sus filas (punto, líder, otros, fecha, km, fija, motivo, alertas y resultado). */
    public Map<String, Object> ver(long id) {
        Map<String, Object> p = jdbc.sql("""
                SELECT id, ciclo, anio_contrato AS "anioContrato", periodo_desde AS desde, hasta, estado, resumen::text AS resumen, creado, confirmado
                FROM propuestas_agenda WHERE id = :id""").param("id", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("La propuesta no existe."));
        Map<String, Object> m = new LinkedHashMap<>(p);
        m.replaceAll((k, v) -> v instanceof java.sql.Date d ? d.toString() : v);
        try {
            m.put("resumen", json.readValue((String) p.get("resumen"), Map.class));
        } catch (JsonProcessingException e) {
            m.put("resumen", Map.of());
        }
        m.put("nombre", PropuestaAgenda.CICLOS.get((String) p.get("ciclo")).nombre());
        Map<Long, List<Map<String, Object>>> otros = new HashMap<>();
        jdbc.sql("""
                SELECT o.item_id, o.usuario_id, u.nombre FROM propuestas_agenda_items_otros o JOIN propuestas_agenda_items i ON i.id = o.item_id
                JOIN usuarios u ON u.id = o.usuario_id WHERE i.propuesta_id = :p ORDER BY o.item_id, o.orden""").param("p", id).query((rs, n) -> {
            otros.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(Map.of("id", rs.getLong(2), "nombre", rs.getString(3)));
            return null;
        }).list();
        m.put("items", jdbc.sql("""
                SELECT i.id, i.punto_id, pt.nombre AS punto, pt.unidad, mu.nombre AS municipio, i.auditor_id, u.nombre AS lider, i.fecha, i.fijo, i.km, i.motivo,
                       i.alertas::text AS alertas, i.visita_id, v.numero AS visita, i.resultado
                FROM propuestas_agenda_items i JOIN puntos pt ON pt.id = i.punto_id LEFT JOIN par_municipios mu ON mu.codigo = pt.municipio
                LEFT JOIN usuarios u ON u.id = i.auditor_id LEFT JOIN visitas v ON v.id = i.visita_id
                WHERE i.propuesta_id = :p ORDER BY i.fecha NULLS LAST, pt.nombre""").param("p", id).query((rs, n) -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", rs.getLong("id"));
            x.put("puntoId", rs.getLong("punto_id"));
            x.put("punto", rs.getString("punto"));
            x.put("unidad", rs.getString("unidad"));
            x.put("municipio", rs.getString("municipio"));
            x.put("auditorId", rs.getObject("auditor_id"));
            x.put("lider", rs.getString("lider"));
            x.put("otros", otros.getOrDefault(rs.getLong("id"), List.of()));
            x.put("fecha", rs.getDate("fecha") == null ? null : rs.getDate("fecha").toString());
            x.put("fijo", rs.getBoolean("fijo"));
            x.put("km", rs.getObject("km"));
            x.put("motivo", rs.getString("motivo"));
            try {
                x.put("alertas", json.readValue(rs.getString("alertas"), List.class));
            } catch (JsonProcessingException e) {
                x.put("alertas", List.of());
            }
            x.put("visitaId", rs.getObject("visita_id"));
            x.put("visita", rs.getString("visita"));
            x.put("resultado", rs.getString("resultado"));
            return x;
        }).list());
        return m;
    }

    /** Ajuste del coordinador: la fila queda fija (decisión 13). La fecha puede pasar del límite del ciclo (duda 13.6). */
    public String ajustar(long itemId, Ajuste a, UsuarioSesion quien) {
        Map<String, Object> it = itemDeBorrador(itemId);
        long puntoId = ((Number) it.get("punto_id")).longValue();
        RepositorioVisitas.Punto punto = visitas.punto(puntoId).orElseThrow();
        List<Long> otros = new ArrayList<>(new LinkedHashSet<>(a.otros() == null ? List.<Long>of()
                : a.otros().stream().filter(x -> x != null && !x.equals(a.auditorId())).toList()));
        if (otros.size() > Reparto.MAX_OTROS) {
            throw ErrorNegocio.validacion("Máximo " + Reparto.MAX_OTROS + " auditores además del líder.", "otros");
        }
        Map<Long, String> roles = visitas.auditoresActivos(otros);
        if (roles.size() != otros.size()) {
            throw ErrorNegocio.validacion("Todos los auditores del equipo deben estar activos y tener el perfil Auditor.", "otros");
        }
        if (a.auditorId() != null) {
            Map<Long, String> rolLider = visitas.auditoresActivos(List.of(a.auditorId()));
            if (!rolLider.containsKey(a.auditorId())) {
                throw ErrorNegocio.validacion("El auditor líder debe estar activo con el perfil Auditor.", "auditorId");
            }
            PropuestaAgenda.Punto p = new PropuestaAgenda.Punto(puntoId, punto.nombre(), punto.unidad(), null, null, null, punto.exigeQf());
            if (!PropuestaAgenda.puedeLiderar(p, new PropuestaAgenda.Auditor(a.auditorId(), "", rolLider.get(a.auditorId()), null, 0))) {
                throw ErrorNegocio.validacion(punto.exigeQf() ? "Este punto exige un químico farmacéutico como auditor líder (Anexo 3 p.9)."
                        : "El auditor líder debe ser químico farmacéutico, regente o auditor en salud.", "auditorId");
            }
        }
        LocalDate fecha = null;
        if (a.fecha() != null && !a.fecha().isBlank()) {
            fecha = Fechas.valida(a.fecha()).orElseThrow(() -> ErrorNegocio.validacion("La fecha no es válida.", "fecha"));
            try {
                validarDia(fecha);
            } catch (ErrorNegocio e) {
                throw e.conCampo("fecha");
            }
        }
        LocalDate f = fecha;
        tx.executeWithoutResult(t -> {
            // Bloquear la propuesta: un ajuste espera a un recálculo o una confirmación en curso (no se pierde en silencio)
            Map<String, Object> prop = bloquearBorrador(((Number) it.get("propuesta_id")).longValue());
            if (f != null && Boolean.TRUE.equals(PropuestaAgenda.CICLOS.get((String) prop.get("ciclo")).anual())
                    && f.isBefore(((java.sql.Date) prop.get("periodo_desde")).toLocalDate())) {
                throw ErrorNegocio.validacion("La fecha queda antes del año de contrato de la propuesta (" + prop.get("periodo_desde") + "): no lo cubriría.");
            }
            int n = jdbc.sql("""
                    UPDATE propuestas_agenda_items i SET auditor_id = :a, fecha = :f, fijo = true, motivo = 'Ajustado por el coordinador', alertas = '[]'
                    FROM propuestas_agenda p WHERE i.id = :id AND p.id = i.propuesta_id AND p.estado = 'borrador'""")
                    .param("a", a.auditorId()).param("f", f).param("id", itemId).update();
            if (n != 1) {
                throw new ErrorNegocio(HttpStatus.CONFLICT, "La propuesta dejó de ser borrador mientras tanto. Recargue.");
            }
            guardarOtros(itemId, otros);
        });
        return "Fila ajustada: queda fija al recalcular.";
    }

    public String liberar(long itemId) {
        Map<String, Object> it = itemDeBorrador(itemId);
        tx.executeWithoutResult(t -> {
            bloquearBorrador(((Number) it.get("propuesta_id")).longValue());
            if (jdbc.sql("UPDATE propuestas_agenda_items SET fijo = false WHERE id = :id").param("id", itemId).update() != 1) {
                throw ErrorNegocio.noEncontrado("La fila no es de un borrador.");
            }
        });
        return "La fila vuelve a calcularse.";
    }

    /** La propuesta bloqueada si sigue en borrador; si no, 409. */
    private Map<String, Object> bloquearBorrador(long propuestaId) {
        return jdbc.sql("SELECT ciclo, periodo_desde FROM propuestas_agenda WHERE id = :p AND estado = 'borrador' FOR UPDATE").param("p", propuestaId).query()
                .listOfRows().stream().findFirst()
                .orElseThrow(() -> new ErrorNegocio(HttpStatus.CONFLICT, "La propuesta dejó de ser borrador mientras tanto. Recargue."));
    }

    private Map<String, Object> itemDeBorrador(long itemId) {
        return jdbc.sql("""
                SELECT i.punto_id, i.propuesta_id FROM propuestas_agenda_items i JOIN propuestas_agenda p ON p.id = i.propuesta_id
                WHERE i.id = :id AND p.estado = 'borrador'""").param("id", itemId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("La fila no es de un borrador."));
    }

    /** El día es hábil y deja los 15 días de notificación previa (la misma regla de la agenda). */
    void validarDia(LocalDate fecha) {
        Plazos plazos = consulta.plazos();
        if (!plazos.esHabil(fecha)) {
            throw ErrorNegocio.validacion("La fecha debe ser un día hábil (sin fines de semana ni festivos).");
        }
        if (plazos.limiteNotificacion("opl", fecha).isBefore(hoy())) {
            List<LocalDate> d = PropuestaAgenda.dias(plazos, hoy(), hoy(), hoy().plusDays(60));
            throw ErrorNegocio.validacion("La fecha no deja los 15 días de notificación previa al OPL (Anexo 3 p.9)"
                    + (d.isEmpty() ? "." : ": el primer día posible es el " + d.get(0) + "."));
        }
    }

    /** Recalcula con la propuesta bloqueada: lo fijado se respeta; los puntos programados por otro lado salen solos. */
    public Map<String, Object> recalcular(long id, UsuarioSesion quien) {
        String texto = tx.execute(t -> {
            Map<String, Object> p = jdbc.sql("SELECT ciclo, anio_contrato, periodo_desde, hasta FROM propuestas_agenda WHERE id = :id AND estado = 'borrador' FOR UPDATE")
                    .param("id", id).query().listOfRows().stream().findFirst().orElseThrow(() -> ErrorNegocio.noEncontrado("La propuesta no es un borrador."));
            PropuestaAgenda.Ciclo c = ciclo((String) p.get("ciclo"));
            Periodo per = new Periodo(((java.sql.Date) p.get("periodo_desde")).toLocalDate(), ((java.sql.Date) p.get("hasta")).toLocalDate(),
                    p.get("anio_contrato") == null ? null : ((Number) p.get("anio_contrato")).intValue());
            Map<Long, PropuestaAgenda.Fijo> fijos = new HashMap<>();
            jdbc.sql("""
                    SELECT i.punto_id, i.auditor_id, i.fecha, coalesce(array_agg(o.usuario_id ORDER BY o.orden) FILTER (WHERE o.usuario_id IS NOT NULL), '{}') AS otros
                    FROM propuestas_agenda_items i LEFT JOIN propuestas_agenda_items_otros o ON o.item_id = i.id
                    WHERE i.propuesta_id = :p AND i.fijo GROUP BY i.id, i.punto_id, i.auditor_id, i.fecha""").param("p", id).query((rs, n) -> {
                fijos.put(rs.getLong(1), new PropuestaAgenda.Fijo((Long) rs.getObject(2), List.of((Long[]) rs.getArray(4).getArray()),
                        rs.getDate(3) == null ? null : rs.getDate(3).toLocalDate()));
                return null;
            }).list();
            // Solo los puntos del borrador que siguen pendientes (los programados por otro lado salen solos; no entran puntos nuevos)
            Set<Long> delBorrador = new java.util.HashSet<>(jdbc.sql("SELECT punto_id FROM propuestas_agenda_items WHERE propuesta_id = :p").param("p", id)
                    .query(Long.class).list());
            PropuestaAgenda.Resultado r = calcular(c, per, fijos, delBorrador);
            jdbc.sql("DELETE FROM propuestas_agenda_items_otros WHERE item_id IN (SELECT id FROM propuestas_agenda_items WHERE propuesta_id = :p)").param("p", id).update();
            jdbc.sql("DELETE FROM propuestas_agenda_items WHERE propuesta_id = :p").param("p", id).update();
            guardarItems(id, r);
            jdbc.sql("UPDATE propuestas_agenda SET resumen = CAST(:r AS jsonb) WHERE id = :id").param("r", resumenJson(r)).param("id", id).update();
            return textoResumen(r);
        });
        return Map.of("mensaje", texto);
    }

    public String descartar(long id, UsuarioSesion quien) {
        int n = jdbc.sql("UPDATE propuestas_agenda SET estado = 'descartada' WHERE id = :id AND estado = 'borrador'").param("id", id).update();
        if (n != 1) {
            throw ErrorNegocio.noEncontrado("La propuesta no es un borrador.");
        }
        bitacora.registrar("propuesta_descartada", "Propuesta " + id + " descartada", quien.id(), quien.usuario());
        return "Borrador descartado.";
    }

    // ---------- Confirmar ----------

    /**
     * Confirma y programa: marca la propuesta de forma atómica, toma cada fila sin resultado una sola vez, la vuelve a comprobar y la
     * programa por el camino de siempre (camino A, con notificación). Una confirmación cortada se continúa con la misma llamada.
     */
    public Map<String, Object> confirmar(long id, UsuarioSesion quien) {
        Map<String, Object> p = jdbc.sql("SELECT ciclo, estado FROM propuestas_agenda WHERE id = :id").param("id", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("La propuesta no existe."));
        PropuestaAgenda.Ciclo c = ciclo((String) p.get("ciclo"));
        if ("borrador".equals(p.get("estado"))) {
            long conDatos = jdbc.sql("SELECT count(*) FROM propuestas_agenda_items WHERE propuesta_id = :p AND auditor_id IS NOT NULL AND fecha IS NOT NULL")
                    .param("p", id).query(Long.class).single();
            if (conDatos == 0) {
                throw ErrorNegocio.validacion("Ninguna visita tiene auditor y fecha: no hay nada que programar.");
            }
            int n = jdbc.sql("UPDATE propuestas_agenda SET estado = 'confirmada', confirmado_por = :u, confirmado = :ahora WHERE id = :id AND estado = 'borrador'")
                    .param("u", quien.id()).param("ahora", OffsetDateTime.now(reloj)).param("id", id).update();
            if (n != 1) {
                throw new ErrorNegocio(HttpStatus.CONFLICT, "Otro usuario confirmó o descartó la propuesta al mismo tiempo.");
            }
        } else if (!"confirmada".equals(p.get("estado"))) {
            throw ErrorNegocio.noEncontrado("La propuesta no es un borrador ni una confirmación por continuar.");
        }
        List<Long> pendientes = jdbc.sql("SELECT id FROM propuestas_agenda_items WHERE propuesta_id = :p AND resultado IS NULL ORDER BY fecha NULLS LAST, id")
                .param("p", id).query(Long.class).list();
        if (pendientes.isEmpty() && !"borrador".equals(p.get("estado"))) {
            throw ErrorNegocio.noEncontrado("La propuesta ya se confirmó por completo.");
        }
        // Fuera del bucle: versión, componentes por unidad (sin el de seguimiento a plan, especial «plan») y alcance del tipo
        String version = visitas.versionVigente().orElse(null);
        List<RepositorioVisitas.ComponenteChecklist> catalogo = version == null ? List.of() : visitas.componentes(version);
        String alcance = jdbc.sql("SELECT coalesce(finalidad, nombre) FROM par_tipos_visita WHERE codigo = :c").param("c", c.tipo()).query(String.class)
                .optional().orElse(c.tipo()) + " (propuesta de agenda N° " + id + ")";
        int programadas = 0;
        int notificadas = 0;
        List<String> sinProgramar = new ArrayList<>();
        for (long itemId : pendientes) {
            String[] punto = new String[1];
            Long visita;
            try {
                // Una transacción por fila: candado del punto, tomar la fila, volver a comprobar y programar sin notificar. Si algo falla,
                // todo se deshace y la fila sigue sin resultado (se puede continuar); al confirmar, la fila queda enlazada a su visita
                visita = tx.execute(t -> programarFila(id, itemId, c, catalogo, alcance, punto, quien));
            } catch (ErrorNegocio e) {
                resultado(itemId, null, recortar("Sin programar: " + e.getMessage(), 500));
                sinProgramar.add(punto[0] == null ? "fila " + itemId : punto[0]);
                continue;
            } catch (RuntimeException e) {
                LOG.error("[propuesta] Fila {} de la propuesta {}", itemId, id, e);
                resultado(itemId, null, "Error interno al programar.");
                sinProgramar.add(punto[0] == null ? "fila " + itemId : punto[0]);
                continue;
            }
            if (visita == null) {
                continue; // la tomó otra confirmación
            }
            programadas++;
            // La notificación sale fuera de la transacción; si falla, la visita queda programada con la alerta y el botón «Notificar»
            try {
                ServicioNotificacion.Resultado n = agenda.notificarAlProgramar(visita, quien);
                if (n.ok()) {
                    notificadas++;
                }
                resultado(itemId, visita, recortar("Programada. " + n.mensaje(), 500));
            } catch (RuntimeException e) {
                LOG.error("[propuesta] Notificación de la visita {} (fila {})", visita, itemId, e);
                resultado(itemId, visita, "Programada; la notificación falló: notifíquela desde la agenda.");
            }
        }
        // Fila propia en la bitácora (no se pierde entre las anotaciones de cada visita programada)
        bitacora.escribir(co.com.agsamericas.digsa.reglas.Bitacora.Operacion.INSERCION, "propuesta_confirmada", "propuesta",
                "Propuesta " + id + " · " + programadas + " programada(s), " + notificadas + " notificada(s)", quien.id(), quien.usuario(), null);
        StringBuilder m = new StringBuilder("Se programaron " + programadas + " visita(s) y se notificaron " + notificadas + ".");
        if (notificadas < programadas) {
            m.append(" Las no notificadas quedan con la alerta y el botón «Notificar» en la agenda.");
        }
        if (!sinProgramar.isEmpty()) {
            m.append(" Sin programar: ").append(String.join(", ", sinProgramar.subList(0, Math.min(8, sinProgramar.size()))))
                    .append(sinProgramar.size() > 8 ? " y " + (sinProgramar.size() - 8) + " más." : ".");
        }
        return Map.of("mensaje", m.toString(), "programadas", programadas, "notificadas", notificadas, "parcial", notificadas < programadas || !sinProgramar.isEmpty());
    }

    /** Dentro de la transacción de la fila. @return la visita creada, o null si otra confirmación ya tomó la fila */
    private Long programarFila(long propuestaId, long itemId, PropuestaAgenda.Ciclo c, List<RepositorioVisitas.ComponenteChecklist> catalogo, String alcance,
                               String[] punto, UsuarioSesion quien) {
        Map<String, Object> it = jdbc.sql("""
                SELECT i.punto_id, i.auditor_id, i.fecha, pt.nombre, pt.unidad FROM propuestas_agenda_items i JOIN puntos pt ON pt.id = i.punto_id
                WHERE i.id = :id AND i.resultado IS NULL FOR UPDATE OF i""").param("id", itemId).query().listOfRows().stream().findFirst().orElse(null);
        if (it == null) {
            return null;
        }
        punto[0] = (String) it.get("nombre");
        long puntoId = ((Number) it.get("punto_id")).longValue();
        // Candado por punto: dos confirmaciones (o la agenda) no programan el mismo punto a la vez
        jdbc.sql("SELECT pg_advisory_xact_lock(:k)").param("k", 0x50524f50L << 32 | puntoId).query().listOfRows();
        LocalDate fecha = it.get("fecha") == null ? null : ((java.sql.Date) it.get("fecha")).toLocalDate();
        if (it.get("auditor_id") == null || fecha == null) {
            throw ErrorNegocio.validacion("le falta el auditor líder o la fecha");
        }
        if (jdbc.sql("""
                SELECT count(*) FROM visitas v JOIN propuestas_agenda pa ON pa.id = :p WHERE v.punto_id = :punto AND v.tipo = ANY(:tipos) AND v.estado <> 'cancelada'
                  AND (NOT :anual OR v.fecha BETWEEN pa.periodo_desde AND pa.hasta)""")
                .param("p", propuestaId).param("punto", puntoId).param("tipos", c.cubren().toArray(String[]::new)).param("anual", c.anual())
                .query(Long.class).single() > 0) {
            throw ErrorNegocio.validacion("ya tiene visita del ciclo");
        }
        try {
            validarDia(fecha);
        } catch (ErrorNegocio e) {
            throw ErrorNegocio.validacion("la fecha ya no sirve (" + e.getMessage() + ")");
        }
        List<Long> otros = jdbc.sql("SELECT usuario_id FROM propuestas_agenda_items_otros WHERE item_id = :i ORDER BY orden").param("i", itemId).query(Long.class).list();
        String unidad = (String) it.get("unidad");
        List<String> componentes = catalogo.stream().filter(x -> x.unidades().contains(unidad)).filter(x -> !"plan".equals(x.especial()))
                .map(RepositorioVisitas.ComponenteChecklist::codigo).toList();
        ServicioAgenda.Resultado r = agenda.programar(new ServicioAgenda.Datos(c.tipo(), puntoId, fecha.toString(), null, ((Number) it.get("auditor_id")).longValue(),
                otros, alcance, componentes, null, null, false, null), quien);
        resultado(itemId, r.id(), "Programada; notificando…");
        return r.id();
    }

    private void resultado(long itemId, Long visitaId, String texto) {
        jdbc.sql("UPDATE propuestas_agenda_items SET visita_id = :v, resultado = :r WHERE id = :id").param("v", visitaId).param("r", texto).param("id", itemId).update();
    }

    /** Recorte para las columnas de texto. */
    static String recortar(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
