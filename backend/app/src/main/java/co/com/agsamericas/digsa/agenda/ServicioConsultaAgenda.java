package co.com.agsamericas.digsa.agenda;

import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.EstadosVisita;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reglas.Plazos;
import co.com.agsamericas.digsa.reglas.Reparto;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consultas de la agenda: lista y calendario con filtros (el alcance de cada perfil se aplica en el SQL), detalle con las acciones
 * permitidas, Mis visitas del auditor (solo sus partes) y el seguimiento en tiempo real de las visitas del día.
 */
@Service
public class ServicioConsultaAgenda {

    /** Rango máximo de una consulta de la agenda (un año). */
    static final int MAX_DIAS = 366;
    /** La visita a necesidad es sin previo aviso (Anexo 3 p.14): ni el OPL ni el ESM la ven mientras no se haga. */
    static final String SIN_NECESIDAD_PENDIENTE = "NOT (v.tipo = 'necesidad' AND v.estado IN ('programada', 'notificada', 'reprogramada'))";

    public record Filtros(String desde, String hasta, String estado, Long puntoId, Long auditorId, String fuerza, String unidad, String tipo) {
    }

    private final JdbcClient jdbc;
    private final RepositorioVisitas visitas;
    private final ServicioNotificacion notificacion;
    private final Clock reloj;

    public ServicioConsultaAgenda(JdbcClient jdbc, RepositorioVisitas visitas, ServicioNotificacion notificacion, Clock reloj) {
        this.jdbc = jdbc;
        this.visitas = visitas;
        this.notificacion = notificacion;
        this.reloj = reloj;
    }

    private LocalDate hoy() {
        return LocalDate.now(reloj.withZone(Fechas.BOGOTA));
    }

    /** Condición SQL del alcance sobre «v» (visitas) y «p» (puntos), con sus parámetros. */
    public record Condicion(String sql, Map<String, Object> params) {
    }

    public static Condicion condicion(Alcance a) {
        return switch (a) {
            case Alcance.Todo t -> new Condicion("true", Map.of());
            // OPL nacional: no ve las visitas a necesidad que aún no se hacen (son sin previo aviso, Anexo 3 p.14)
            case Alcance.Opl o -> new Condicion(SIN_NECESIDAD_PENDIENTE, Map.of());
            case Alcance.Fuerza f -> new Condicion("p.fuerza = :alc_fuerza", Map.of("alc_fuerza", f.codigo()));
            case Alcance.Regional r -> new Condicion("p.regional_id = :alc_regional AND " + SIN_NECESIDAD_PENDIENTE, Map.of("alc_regional", r.id()));
            case Alcance.Esm e -> new Condicion("v.punto_id = :alc_punto AND " + SIN_NECESIDAD_PENDIENTE, Map.of("alc_punto", e.puntoId()));
            case Alcance.Equipo e -> new Condicion("EXISTS (SELECT 1 FROM visitas_auditores x WHERE x.visita_id = v.id AND x.usuario_id = :alc_usuario)",
                    Map.of("alc_usuario", e.usuarioId()));
            case Alcance.Ninguno n -> new Condicion("false", Map.of());
        };
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listar(Filtros f, Alcance alcance) {
        LocalDate desde = f.desde() == null || f.desde().isBlank() ? hoy().withDayOfMonth(1)
                : Fechas.valida(f.desde()).orElseThrow(() -> ErrorNegocio.validacion("La fecha «desde» no es válida."));
        LocalDate hasta = f.hasta() == null || f.hasta().isBlank() ? desde.plusMonths(2)
                : Fechas.valida(f.hasta()).orElseThrow(() -> ErrorNegocio.validacion("La fecha «hasta» no es válida."));
        if (hasta.isBefore(desde) || desde.plusDays(MAX_DIAS).isBefore(hasta)) {
            throw ErrorNegocio.validacion("El rango de fechas debe ir de «desde» a «hasta» y ser de máximo un año.");
        }
        Condicion c = condicion(alcance);
        StringBuilder sql = new StringBuilder("""
                SELECT v.id, v.numero, v.fecha, v.hora, v.estado, v.tipo, t.nombre AS tipo_nombre, t.aviso, v.punto_id, p.nombre AS punto,
                       p.unidad, p.fuerza, p.modalidad, p.exige_qf, m.nombre AS municipio, v.auditor_id, u.nombre AS lider, v.fecha_solicitud,
                       v.equipo_sin_notificar,
                       (SELECT count(*) FROM visitas_auditores x WHERE x.visita_id = v.id) AS integrantes,
                       EXISTS (SELECT 1 FROM visitas_auditores x JOIN auditores a ON a.usuario_id = x.usuario_id
                               WHERE x.visita_id = v.id AND a.rol = 'quimico_farmaceutico') AS tiene_qf
                FROM visitas v JOIN par_tipos_visita t ON t.codigo = v.tipo JOIN puntos p ON p.id = v.punto_id
                LEFT JOIN par_municipios m ON m.codigo = p.municipio JOIN usuarios u ON u.id = v.auditor_id
                WHERE v.fecha BETWEEN :desde AND :hasta AND (""").append(c.sql()).append(")");
        Map<String, Object> params = new LinkedHashMap<>(c.params());
        params.put("desde", desde);
        params.put("hasta", hasta);
        if (f.estado() != null && EstadosVisita.NOMBRES.containsKey(f.estado())) {
            sql.append(" AND v.estado = :estado");
            params.put("estado", f.estado());
        }
        if (f.puntoId() != null) {
            sql.append(" AND v.punto_id = :punto");
            params.put("punto", f.puntoId());
        }
        if (f.auditorId() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM visitas_auditores x WHERE x.visita_id = v.id AND x.usuario_id = :auditor)");
            params.put("auditor", f.auditorId());
        }
        if (f.fuerza() != null && !f.fuerza().isBlank()) {
            sql.append(" AND p.fuerza = :fuerza");
            params.put("fuerza", f.fuerza());
        }
        if (f.unidad() != null && !f.unidad().isBlank()) {
            sql.append(" AND p.unidad = :unidad");
            params.put("unidad", f.unidad());
        }
        if (f.tipo() != null && !f.tipo().isBlank()) {
            sql.append(" AND v.tipo = :tipo");
            params.put("tipo", f.tipo());
        }
        sql.append(" ORDER BY v.fecha, v.hora NULLS LAST, v.id LIMIT 2000");
        Plazos plazos = plazos();
        LocalDate hoy = hoy();
        boolean consulta = alcance instanceof Alcance.Fuerza || alcance instanceof Alcance.Regional || alcance instanceof Alcance.Esm
                || alcance instanceof Alcance.Opl;
        return jdbc.sql(sql.toString()).params(params).query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("numero", rs.getString("numero"));
            LocalDate fecha = rs.getDate("fecha").toLocalDate();
            m.put("fecha", fecha.toString());
            m.put("hora", rs.getTime("hora") == null ? null : rs.getTime("hora").toLocalTime().toString());
            m.put("estado", rs.getString("estado"));
            m.put("estadoNombre", EstadosVisita.NOMBRES.get(rs.getString("estado")));
            m.put("tipo", rs.getString("tipo"));
            m.put("tipoNombre", rs.getString("tipo_nombre"));
            m.put("puntoId", rs.getLong("punto_id"));
            m.put("punto", rs.getString("punto"));
            m.put("unidad", rs.getString("unidad"));
            m.put("fuerza", rs.getString("fuerza"));
            m.put("municipio", rs.getString("municipio"));
            if (!consulta) {
                m.put("lider", rs.getString("lider")); // el equipo no se muestra a los perfiles de consulta (Ley 1581)
                m.put("integrantes", rs.getInt("integrantes"));
                java.sql.Date fs = rs.getDate("fecha_solicitud");
                List<Plazos.Alerta> alertas = plazos.alertas(new Plazos.Visita(fecha, rs.getString("estado"), fs == null ? null : fs.toLocalDate()),
                        new Plazos.Tipo(rs.getString("tipo"), rs.getString("aviso"), null, null),
                        new Plazos.Punto(rs.getString("punto"), rs.getString("unidad"), rs.getString("modalidad"), rs.getBoolean("exige_qf")),
                        new Plazos.Equipo(rs.getString("lider"), rs.getBoolean("tiene_qf")), hoy);
                m.put("alertas", alertas.stream().map(a -> Map.of("nivel", a.nivel().valor(), "texto", a.texto())).toList());
                m.put("nivel", Plazos.nivel(alertas));
                m.put("equipoSinNotificar", rs.getBoolean("equipo_sin_notificar"));
            }
            return m;
        }).list();
    }

    /** Reglas de plazos con los parámetros y los festivos de ajuste de la base. */
    public Plazos plazos() {
        Map<String, Integer> reglas = new LinkedHashMap<>(Plazos.REGLAS);
        jdbc.sql("SELECT clave, valor FROM par_parametros WHERE clave = ANY(:c)").param("c", Plazos.REGLAS.keySet().toArray(String[]::new))
                .query((rs, n) -> {
                    String v = rs.getString(2);
                    if (v != null && v.matches("^[0-9]{1,4}$")) {
                        reglas.put(rs.getString(1), Integer.parseInt(v));
                    }
                    return null;
                }).list();
        Set<LocalDate> ajustes = Set.copyOf(jdbc.sql("SELECT fecha FROM par_festivos").query((rs, n) -> rs.getDate(1).toLocalDate()).list());
        return new Plazos(ajustes, reglas);
    }

    /**
     * Detalle de una visita. El coordinador ve todo; el auditor, solo si es del equipo, y de las partes solo las suyas (Anexo 5,
     * prueba 11: cada uno ve solo lo suyo); los perfiles de consulta, dentro de su alcance y sin datos del equipo.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> detalle(long id, long usuarioId, Set<Perfil> perfiles, Alcance alcance) {
        RepositorioVisitas.Visita v = visitas.porId(id).orElseThrow(() -> ErrorNegocio.noEncontrado("La visita no existe."));
        boolean coordinador = perfiles.contains(Perfil.COORDINADOR) || perfiles.contains(Perfil.ADMINISTRADOR);
        List<RepositorioVisitas.Miembro> equipo = visitas.equipo(id);
        boolean delEquipo = equipo.stream().anyMatch(m -> m.id() == usuarioId);
        if (!coordinador && !delEquipo) {
            Condicion c = condicion(alcance);
            boolean visible = jdbc.sql("SELECT count(*) FROM visitas v JOIN puntos p ON p.id = v.punto_id WHERE v.id = :id AND (" + c.sql() + ")")
                    .params(c.params()).param("id", id).query(Long.class).single() > 0;
            if (!visible || alcance instanceof Alcance.Equipo) {
                throw ErrorNegocio.noEncontrado("La visita no existe.");
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id());
        m.put("numero", v.numero());
        m.put("tipo", v.tipo());
        m.put("tipoNombre", visitas.tipo(v.tipo()).map(RepositorioVisitas.Tipo::nombre).orElse(v.tipo()));
        m.put("aviso", v.aviso());
        m.put("puntoId", v.puntoId());
        m.put("punto", v.puntoNombre());
        m.put("unidad", v.unidad());
        m.put("fuerza", v.fuerza());
        m.put("modalidad", v.modalidad());
        m.put("fecha", v.fecha().toString());
        m.put("fechaLarga", Plazos.fechaLarga(v.fecha()));
        m.put("hora", v.hora() == null ? null : v.hora().toString());
        m.put("estado", v.estado());
        m.put("estadoNombre", EstadosVisita.NOMBRES.get(v.estado()));
        m.put("alcance", v.alcance());
        m.put("fechaSolicitud", v.fechaSolicitud() == null ? null : v.fechaSolicitud().toString());
        m.put("checklistVersion", v.checklistVersion());
        if (!coordinador && !delEquipo) {
            m.put("acciones", List.of());
            return m; // perfiles de consulta: sin equipo, reparto ni historial
        }
        String version = v.checklistVersion() != null ? v.checklistVersion() : visitas.versionVigente().orElse(null);
        Map<String, String> nombresComponentes = version == null ? Map.of() : visitas.componentes(version).stream()
                .collect(Collectors.toMap(RepositorioVisitas.ComponenteChecklist::codigo, RepositorioVisitas.ComponenteChecklist::nombre));
        Map<Long, String> nombres = equipo.stream().collect(Collectors.toMap(RepositorioVisitas.Miembro::id, RepositorioVisitas.Miembro::nombre));
        List<Map<String, Object>> eq = new ArrayList<>();
        for (RepositorioVisitas.Miembro x : equipo) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", x.id());
            e.put("nombre", x.nombre());
            e.put("rol", x.rol());
            e.put("rolNombre", Reparto.ROLES.getOrDefault(x.rol(), "Otro"));
            e.put("lider", "lider".equals(x.funcion()));
            e.put("activo", x.activo());
            eq.add(e);
        }
        m.put("equipo", eq);
        m.put("componentes", visitas.componentesDe(id).stream().map(c -> Map.of("codigo", c, "nombre", nombresComponentes.getOrDefault(c, c))).toList());
        Map<String, Long> reparto = visitas.reparto(id);
        List<Map<String, Object>> partes = new ArrayList<>();
        for (var e : reparto.entrySet()) {
            if (!coordinador && e.getValue() != usuarioId) {
                continue; // el auditor solo ve sus partes
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("parte", e.getKey());
            p.put("nombre", nombreParte(e.getKey(), nombresComponentes));
            p.put("auditorId", e.getValue());
            p.put("auditor", nombres.get(e.getValue()));
            partes.add(p);
        }
        m.put("reparto", partes);
        m.put("acciones", acciones(v, coordinador, delEquipo));
        if (coordinador) {
            m.put("historial", visitas.historialDe(id));
            m.put("notificaciones", notificacion.deVisita(id));
            m.put("equipoSinNotificar", v.equipoSinNotificar());
            RepositorioVisitas.Punto punto = visitas.punto(v.puntoId()).orElseThrow();
            m.put("advertencias", ServicioAgenda.advertencias(punto,
                    equipo.stream().map(x -> new Reparto.Integrante(x.id(), x.rol(), "lider".equals(x.funcion()))).toList()));
        }
        return m;
    }

    static String nombreParte(String parte, Map<String, String> componentes) {
        return switch (parte) {
            case Reparto.OBLIGACIONES -> "Obligaciones de la minuta";
            case Reparto.PARTE_SISTEMAS -> "Ítems de sistemas";
            default -> parte + " · " + componentes.getOrDefault(parte, "");
        };
    }

    static List<String> acciones(RepositorioVisitas.Visita v, boolean coordinador, boolean delEquipo) {
        List<String> a = new ArrayList<>(EstadosVisita.acciones(v.estado(), v.aviso(), coordinador, delEquipo));
        // Cambio de equipo sin notificar en una visita ya notificada: se puede notificar de nuevo
        if (coordinador && v.equipoSinNotificar() && Set.of("notificada", "reprogramada").contains(v.estado()) && !"no-opl".equals(v.aviso())
                && !a.contains("notificar")) {
            a.add("notificar");
        }
        if (coordinador && EstadosVisita.POR_EJECUTAR.contains(v.estado())) {
            a.add("mover"); // decisión 8.3: las partes solo se mueven antes de iniciar
        }
        return a;
    }

    /** Mis visitas: las del auditor por ejecutar o en ejecución (y las cerradas de los últimos 30 días), con solo sus partes. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> misVisitas(long usuarioId) {
        LocalDate desde = hoy().minusDays(30);
        return jdbc.sql("""
                SELECT v.id, v.numero, v.fecha, v.hora, v.estado, t.nombre AS tipo, p.nombre AS punto, p.unidad, m.nombre AS municipio,
                       (v.auditor_id = :u) AS lider,
                       coalesce((SELECT string_agg(r.parte, ',' ORDER BY r.parte) FROM visitas_reparto r WHERE r.visita_id = v.id AND r.usuario_id = :u), '') AS partes
                FROM visitas v JOIN visitas_auditores va ON va.visita_id = v.id AND va.usuario_id = :u
                JOIN par_tipos_visita t ON t.codigo = v.tipo JOIN puntos p ON p.id = v.punto_id LEFT JOIN par_municipios m ON m.codigo = p.municipio
                WHERE v.estado IN ('programada', 'notificada', 'reprogramada', 'ejecucion') OR (v.estado = 'cerrada' AND v.fecha >= :desde)
                ORDER BY v.fecha, v.hora NULLS LAST, v.id""").param("u", usuarioId).param("desde", desde).query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("numero", rs.getString("numero"));
            m.put("fecha", rs.getDate("fecha").toLocalDate().toString());
            m.put("hora", rs.getTime("hora") == null ? null : rs.getTime("hora").toLocalTime().toString());
            m.put("estado", rs.getString("estado"));
            m.put("estadoNombre", EstadosVisita.NOMBRES.get(rs.getString("estado")));
            m.put("tipo", rs.getString("tipo"));
            m.put("punto", rs.getString("punto"));
            m.put("unidad", rs.getString("unidad"));
            m.put("municipio", rs.getString("municipio"));
            m.put("lider", rs.getBoolean("lider"));
            String partes = rs.getString("partes");
            m.put("partes", partes.isEmpty() ? List.of() : List.of(partes.split(",")));
            return m;
        }).list();
    }

    /** Seguimiento en tiempo real (coordinador y administrador): visitas de hoy y en ejecución, con quién las inició y cuándo. */
    @Transactional(readOnly = true)
    public Map<String, Object> seguimiento() {
        List<Map<String, Object>> filas = jdbc.sql("""
                SELECT v.id, v.numero, v.fecha, v.hora, v.estado, p.nombre AS punto, u.nombre AS lider, v.iniciada,
                       (SELECT count(*) FROM visitas_auditores x WHERE x.visita_id = v.id) AS integrantes,
                       (SELECT h.evento || ' · ' || to_char(h.fecha AT TIME ZONE 'America/Bogota', 'HH24:MI') FROM visitas_historial h
                        WHERE h.visita_id = v.id ORDER BY h.id DESC LIMIT 1) AS ultimo
                FROM visitas v JOIN puntos p ON p.id = v.punto_id JOIN usuarios u ON u.id = v.auditor_id
                WHERE v.estado = 'ejecucion' OR v.fecha = :hoy
                ORDER BY (v.estado = 'ejecucion') DESC, v.fecha, v.hora NULLS LAST, v.id LIMIT 500""").param("hoy", hoy()).query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("numero", rs.getString("numero"));
            m.put("fecha", rs.getDate("fecha").toLocalDate().toString());
            m.put("hora", rs.getTime("hora") == null ? null : rs.getTime("hora").toLocalTime().toString());
            m.put("estado", rs.getString("estado"));
            m.put("estadoNombre", EstadosVisita.NOMBRES.get(rs.getString("estado")));
            m.put("punto", rs.getString("punto"));
            m.put("lider", rs.getString("lider"));
            m.put("integrantes", rs.getInt("integrantes"));
            java.time.OffsetDateTime ini = rs.getObject("iniciada", java.time.OffsetDateTime.class);
            m.put("iniciada", ini == null ? null : ini.atZoneSameInstant(Fechas.BOGOTA).toLocalTime().withNano(0).toString());
            m.put("ultimo", rs.getString("ultimo"));
            return m;
        }).list();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("actualizado", java.time.ZonedDateTime.now(reloj.withZone(Fechas.BOGOTA)).toLocalTime().withNano(0).toString());
        r.put("visitas", filas);
        return r;
    }

    /** Opciones del formulario de programación. */
    @Transactional(readOnly = true)
    public Map<String, Object> opciones() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipos", jdbc.sql("SELECT codigo, nombre, unidad, modalidad, aviso FROM par_tipos_visita ORDER BY orden").query().listOfRows());
        m.put("puntos", jdbc.sql("""
                SELECT p.id, p.nombre, p.sigla, p.unidad, p.fuerza, p.modalidad, p.exige_qf AS "exigeQf", m.nombre AS municipio
                FROM puntos p LEFT JOIN par_municipios m ON m.codigo = p.municipio WHERE p.activo ORDER BY p.unidad, p.nombre""").query().listOfRows());
        m.put("auditores", jdbc.sql("""
                SELECT u.id, u.nombre, coalesce(a.rol, 'otro') AS rol FROM usuarios u
                JOIN usuarios_perfiles up ON up.usuario_id = u.id AND up.perfil_id = :p LEFT JOIN auditores a ON a.usuario_id = u.id
                WHERE u.activo ORDER BY u.nombre""").param("p", Perfil.AUDITOR.id()).query().listOfRows());
        m.put("roles", Reparto.ROLES);
        String version = visitas.versionVigente().orElse(null);
        m.put("version", version);
        m.put("componentes", version == null ? List.of() : visitas.componentes(version).stream().map(c -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("codigo", c.codigo());
            x.put("nombre", c.nombre());
            x.put("unidades", c.unidades().stream().sorted().toList());
            x.put("items", c.items().size());
            x.put("especial", c.especial());
            x.put("sistemas", c.items().stream().filter(Reparto.SISTEMAS::contains).count());
            return x;
        }).toList());
        m.put("estados", EstadosVisita.NOMBRES);
        m.put("motivosReapertura", EstadosVisita.MOTIVOS_REAPERTURA);
        return m;
    }
}
