package co.com.agsamericas.digsa.agenda;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reglas.Reparto;
import java.sql.Date;
import java.sql.Time;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Acceso a las visitas, su equipo, componentes, reparto e historial. SQL explícito (duda 17.6). */
@Repository
public class RepositorioVisitas {

    /** Visita con lo necesario para validar y decidir (sin el equipo). */
    public record Visita(long id, String numero, String tipo, String aviso, String tipoUnidad, long puntoId, String puntoNombre, String unidad,
                         String fuerza, String modalidad, boolean exigeQf, LocalDate fecha, LocalTime hora, long auditorId, String alcance,
                         String estado, LocalDate fechaSolicitud, String checklistVersion, boolean equipoSinNotificar, int reaperturas,
                         OffsetDateTime iniciada) {
    }

    public record Miembro(long id, String nombre, String usuario, String correo, String documento, String rol, String funcion, int orden,
                          boolean activo) {
    }

    /** Tipo de visita del catálogo. */
    public record Tipo(String codigo, String nombre, String unidad, String modalidad, String aviso) {
    }

    /** Punto con lo que necesita la agenda. */
    public record Punto(long id, String nombre, String unidad, String fuerza, String modalidad, boolean exigeQf, boolean activo, Long regionalId) {
    }

    private static final String COLUMNAS = """
            v.id, v.numero, v.tipo, t.aviso, t.unidad AS tipo_unidad, v.punto_id, p.nombre AS punto_nombre, p.unidad, p.fuerza, p.modalidad,
            p.exige_qf, v.fecha, v.hora, v.auditor_id, v.alcance, v.estado, v.fecha_solicitud, v.checklist_version, v.equipo_sin_notificar,
            v.reaperturas, v.iniciada""";
    private static final String DESDE = " FROM visitas v JOIN par_tipos_visita t ON t.codigo = v.tipo JOIN puntos p ON p.id = v.punto_id";

    private final JdbcClient jdbc;
    private final Clock reloj;

    public RepositorioVisitas(JdbcClient jdbc, Clock reloj) {
        this.jdbc = jdbc;
        this.reloj = reloj;
    }

    public Optional<Visita> porId(long id) {
        return jdbc.sql("SELECT " + COLUMNAS + DESDE + " WHERE v.id = :id").param("id", id).query(RepositorioVisitas::fila).optional();
    }

    /** Bloquea la fila de la visita hasta el fin de la transacción (dos coordinadores a la vez: uno espera al otro). */
    public Optional<Visita> bloquear(long id) {
        return jdbc.sql("SELECT " + COLUMNAS + DESDE + " WHERE v.id = :id FOR UPDATE OF v").param("id", id).query(RepositorioVisitas::fila).optional();
    }

    static Visita fila(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Date fs = rs.getDate("fecha_solicitud");
        Time h = rs.getTime("hora");
        return new Visita(rs.getLong("id"), rs.getString("numero"), rs.getString("tipo"), rs.getString("aviso"), rs.getString("tipo_unidad"),
                rs.getLong("punto_id"), rs.getString("punto_nombre"), rs.getString("unidad"), rs.getString("fuerza"), rs.getString("modalidad"),
                rs.getBoolean("exige_qf"), rs.getDate("fecha").toLocalDate(), h == null ? null : h.toLocalTime(), rs.getLong("auditor_id"),
                rs.getString("alcance"), rs.getString("estado"), fs == null ? null : fs.toLocalDate(), rs.getString("checklist_version"),
                rs.getBoolean("equipo_sin_notificar"), rs.getInt("reaperturas"), rs.getObject("iniciada", OffsetDateTime.class));
    }

    public Optional<Tipo> tipo(String codigo) {
        return jdbc.sql("SELECT codigo, nombre, unidad, modalidad, aviso FROM par_tipos_visita WHERE codigo = :c").param("c", codigo)
                .query((rs, n) -> new Tipo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))).optional();
    }

    public Optional<Punto> punto(long id) {
        return jdbc.sql("SELECT id, nombre, unidad, fuerza, modalidad, exige_qf, activo, regional_id FROM puntos WHERE id = :id").param("id", id)
                .query((rs, n) -> new Punto(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6),
                        rs.getBoolean(7), (Long) rs.getObject(8))).optional();
    }

    /** Auditores activos con perfil Auditor entre los ids dados, con su rol técnico (sin rol = otro). */
    public Map<Long, String> auditoresActivos(Collection<Long> ids) {
        Map<Long, String> r = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return r;
        }
        jdbc.sql("""
                SELECT u.id, coalesce(a.rol, 'otro') FROM usuarios u
                JOIN usuarios_perfiles up ON up.usuario_id = u.id AND up.perfil_id = :p
                LEFT JOIN auditores a ON a.usuario_id = u.id
                WHERE u.activo AND u.id = ANY(:ids)""").param("p", Perfil.AUDITOR.id()).param("ids", ids.toArray(Long[]::new))
                .query((rs, n) -> r.put(rs.getLong(1), rs.getString(2))).list();
        return r;
    }

    // ---------- Checklist vigente ----------

    public Optional<String> versionVigente() {
        return jdbc.sql("SELECT version FROM chk_versiones WHERE vigente").query(String.class).optional();
    }

    /** Componentes de una versión con sus unidades y los códigos de sus ítems, en orden. */
    public List<ComponenteChecklist> componentes(String version) {
        return jdbc.sql("""
                SELECT c.codigo, c.nombre, c.especial, c.unidades, array_agg(i.codigo ORDER BY i.orden, i.id) AS items
                FROM chk_componentes c JOIN chk_items i ON i.componente_id = c.id
                WHERE c.version = :v GROUP BY c.id ORDER BY c.orden, c.codigo""").param("v", version)
                .query((rs, n) -> new ComponenteChecklist(rs.getString(1), rs.getString(2), rs.getString(3),
                        Set.of((String[]) rs.getArray(4).getArray()), List.of((String[]) rs.getArray(5).getArray()))).list();
    }

    public record ComponenteChecklist(String codigo, String nombre, String especial, Set<String> unidades, List<String> items) {
        Reparto.Componente paraReparto() {
            return new Reparto.Componente(codigo, unidades, items);
        }
    }

    // ---------- Escrituras ----------

    public long crear(String tipo, long puntoId, LocalDate fecha, LocalTime hora, long liderId, String alcance, LocalDate fechaSolicitud,
                      long creadoPor) {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        long id = jdbc.sql("""
                INSERT INTO visitas (tipo, punto_id, fecha, hora, auditor_id, alcance, fecha_solicitud, creado_por, creado, actualizado)
                VALUES (:tipo, :punto, :fecha, :hora, :lider, :alcance, :fs, :quien, :ahora, :ahora) RETURNING id""")
                .param("tipo", tipo).param("punto", puntoId).param("fecha", fecha).param("hora", hora).param("lider", liderId)
                .param("alcance", alcance).param("fs", fechaSolicitud).param("quien", creadoPor).param("ahora", ahora).query(Long.class).single();
        // VS-AAAAMMDD-NNNN con la fecha programada al crearla (no cambia al reprogramar) y el id con al menos 4 dígitos
        jdbc.sql("UPDATE visitas SET numero = :n WHERE id = :id").param("n", numero(fecha, id)).param("id", id).update();
        return id;
    }

    static String numero(LocalDate fecha, long id) {
        return "VS-" + fecha.toString().replace("-", "") + "-" + String.format("%04d", id);
    }

    public void actualizar(long id, String tipo, long puntoId, LocalDate fecha, LocalTime hora, long liderId, String alcance, LocalDate fechaSolicitud) {
        jdbc.sql("""
                UPDATE visitas SET tipo = :tipo, punto_id = :punto, fecha = :fecha, hora = :hora, auditor_id = :lider, alcance = :alcance,
                  fecha_solicitud = :fs, actualizado = :ahora WHERE id = :id""")
                .param("tipo", tipo).param("punto", puntoId).param("fecha", fecha).param("hora", hora).param("lider", liderId)
                .param("alcance", alcance).param("fs", fechaSolicitud).param("ahora", OffsetDateTime.now(reloj)).param("id", id).update();
    }

    /** Cambio de estado condicionado: solo si sigue en uno de los estados de origen. @return true si cambió */
    public boolean cambiarEstado(long id, Set<String> origen, String destino) {
        return jdbc.sql("UPDATE visitas SET estado = :d, actualizado = :ahora WHERE id = :id AND estado = ANY(:o)")
                .param("d", destino).param("ahora", OffsetDateTime.now(reloj)).param("id", id).param("o", origen.toArray(String[]::new)).update() == 1;
    }

    public boolean reprogramar(long id, Set<String> origen, String destino, LocalDate fecha, LocalTime hora) {
        return jdbc.sql("UPDATE visitas SET estado = :d, fecha = :f, hora = :h, actualizado = :ahora WHERE id = :id AND estado = ANY(:o)")
                .param("d", destino).param("f", fecha).param("h", hora).param("ahora", OffsetDateTime.now(reloj)).param("id", id)
                .param("o", origen.toArray(String[]::new)).update() == 1;
    }

    public boolean iniciar(long id, Set<String> origen, String version) {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        return jdbc.sql("""
                UPDATE visitas SET estado = 'ejecucion', checklist_version = :v, iniciada = :ahora, actualizado = :ahora
                WHERE id = :id AND estado = ANY(:o)""")
                .param("v", version).param("ahora", ahora).param("id", id).param("o", origen.toArray(String[]::new)).update() == 1;
    }

    public void marcarEquipoSinNotificar(long id, boolean valor) {
        jdbc.sql("UPDATE visitas SET equipo_sin_notificar = :v WHERE id = :id").param("v", valor).param("id", id).update();
    }

    public void cambiarLider(long id, long liderId) {
        jdbc.sql("UPDATE visitas SET auditor_id = :l, actualizado = :ahora WHERE id = :id").param("l", liderId)
                .param("ahora", OffsetDateTime.now(reloj)).param("id", id).update();
    }

    // ---------- Componentes, equipo y reparto ----------

    public List<String> componentesDe(long visitaId) {
        return jdbc.sql("SELECT componente FROM visitas_componentes WHERE visita_id = :id ORDER BY componente").param("id", visitaId)
                .query(String.class).list();
    }

    public void reemplazarComponentes(long visitaId, Collection<String> componentes) {
        jdbc.sql("DELETE FROM visitas_componentes WHERE visita_id = :id").param("id", visitaId).update();
        for (String c : componentes) {
            jdbc.sql("INSERT INTO visitas_componentes (visita_id, componente) VALUES (:id, :c)").param("id", visitaId).param("c", c).update();
        }
    }

    public List<Miembro> equipo(long visitaId) {
        return jdbc.sql("""
                SELECT u.id, u.nombre, u.usuario, u.correo, u.documento, coalesce(a.rol, 'otro') AS rol, va.funcion, va.orden, u.activo
                FROM visitas_auditores va JOIN usuarios u ON u.id = va.usuario_id LEFT JOIN auditores a ON a.usuario_id = u.id
                WHERE va.visita_id = :id ORDER BY va.orden""").param("id", visitaId)
                .query((rs, n) -> new Miembro(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getInt(8), rs.getBoolean(9))).list();
    }

    /** Reemplaza el equipo (borra antes el reparto: cada parte es de un integrante). Líder con orden 0, los demás 1…N. */
    public void reemplazarEquipo(long visitaId, long liderId, List<Long> otros) {
        jdbc.sql("DELETE FROM visitas_reparto WHERE visita_id = :id").param("id", visitaId).update();
        jdbc.sql("DELETE FROM visitas_auditores WHERE visita_id = :id").param("id", visitaId).update();
        jdbc.sql("INSERT INTO visitas_auditores (visita_id, usuario_id, funcion, orden) VALUES (:v, :u, 'lider', 0)").param("v", visitaId)
                .param("u", liderId).update();
        int orden = 1;
        for (Long o : otros) {
            jdbc.sql("INSERT INTO visitas_auditores (visita_id, usuario_id, funcion, orden) VALUES (:v, :u, 'apoyo', :o)").param("v", visitaId)
                    .param("u", o).param("o", orden++).update();
        }
    }

    public Map<String, Long> reparto(long visitaId) {
        Map<String, Long> r = new LinkedHashMap<>();
        jdbc.sql("SELECT parte, usuario_id FROM visitas_reparto WHERE visita_id = :id ORDER BY parte").param("id", visitaId)
                .query((rs, n) -> r.put(rs.getString(1), rs.getLong(2))).list();
        return r;
    }

    public void reemplazarReparto(long visitaId, Map<String, Long> reparto, Map<String, String> asignadoPor) {
        jdbc.sql("DELETE FROM visitas_reparto WHERE visita_id = :id").param("id", visitaId).update();
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        for (var e : reparto.entrySet()) {
            jdbc.sql("""
                    INSERT INTO visitas_reparto (visita_id, parte, usuario_id, asignado_por, asignado) VALUES (:v, :p, :u, :por, :ahora)""")
                    .param("v", visitaId).param("p", e.getKey()).param("u", e.getValue())
                    .param("por", asignadoPor.getOrDefault(e.getKey(), "sistema")).param("ahora", ahora).update();
        }
    }

    public boolean moverParte(long visitaId, String parte, long usuarioId, String por) {
        return jdbc.sql("""
                UPDATE visitas_reparto SET usuario_id = :u, asignado_por = :por, asignado = :ahora WHERE visita_id = :v AND parte = :p""")
                .param("u", usuarioId).param("por", por).param("ahora", OffsetDateTime.now(reloj)).param("v", visitaId).param("p", parte)
                .update() == 1;
    }

    // ---------- Historial ----------

    public void historial(long visitaId, Long usuarioId, String usuario, String evento, String detalle) {
        jdbc.sql("""
                INSERT INTO visitas_historial (visita_id, fecha, usuario_id, usuario, evento, detalle) VALUES (:v, :f, :uid, :u, :e, :d)""")
                .param("v", visitaId).param("f", OffsetDateTime.now(reloj)).param("uid", usuarioId).param("u", usuario)
                .param("e", corto(evento, 60)).param("d", detalle == null ? null : corto(detalle, 1000)).update();
    }

    public List<Map<String, Object>> historialDe(long visitaId) {
        return jdbc.sql("SELECT fecha, usuario, evento, detalle FROM visitas_historial WHERE visita_id = :id ORDER BY id").param("id", visitaId)
                .query().listOfRows();
    }

    static String corto(String s, int max) {
        return s.codePointCount(0, s.length()) <= max ? s : new String(s.codePoints().limit(max - 1).toArray(), 0, max - 1) + "…";
    }

    /** Ids de visitas por número de visita para el auditor: en cuáles es del equipo. */
    public boolean esDelEquipo(long visitaId, long usuarioId) {
        return jdbc.sql("SELECT count(*) FROM visitas_auditores WHERE visita_id = :v AND usuario_id = :u").param("v", visitaId).param("u", usuarioId)
                .query(Long.class).single() > 0;
    }

    public List<String> partesDe(long visitaId, long usuarioId) {
        return new ArrayList<>(jdbc.sql("SELECT parte FROM visitas_reparto WHERE visita_id = :v AND usuario_id = :u ORDER BY parte")
                .param("v", visitaId).param("u", usuarioId).query(String.class).list());
    }
}
