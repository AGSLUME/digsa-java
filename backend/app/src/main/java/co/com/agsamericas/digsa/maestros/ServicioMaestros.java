package co.com.agsamericas.digsa.maestros;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reglas.Reparto;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maestros del coordinador: puntos (ESM del anexo del OPL, farmacias alternas y nivel central), datos propios de los auditores (rol
 * técnico y municipio base) y el operador logístico. Referencia: docs/especificaciones/maestros-carga-masiva.
 */
@Service
public class ServicioMaestros {

    static final Set<String> UNIDADES = Set.of("ESM", "FA", "NC");
    /** Datos de contacto del punto (Ley 1581): la bitácora dice que cambiaron, sin los valores. */
    private static final Set<String> CONTACTO = Set.of("contacto", "correos");

    public record Punto(String sigla, String nombre, String unidad, String fuerza, String modalidad, String nit, String municipio,
                        String direccion, String nivel, String horario, String contacto, List<String> correos, Long regionalId,
                        String llegaDesde, Long auditorAsignado, Boolean exigeQf) {
    }

    public record Auditor(String rol, String municipioBase) {
    }

    public record Opl(String nombre, String nit, List<String> correos) {
    }

    private static final String COLUMNAS_PUNTO = """
            p.id, p.sigla, p.nombre, p.unidad, p.fuerza, p.modalidad, p.nit, p.municipio, m.nombre AS municipio_nombre,
            d.nombre AS departamento, p.direccion, p.nivel, p.horario, p.contacto, p.correos, p.regional_id, r.nombre AS regional,
            p.regional_opl, p.llega_desde, p.auditor_asignado, p.exige_qf, p.activo, p.fuente""";

    private final JdbcClient jdbc;
    private final RegistroBitacora bitacora;
    private final Clock reloj;

    public ServicioMaestros(JdbcClient jdbc, RegistroBitacora bitacora, Clock reloj) {
        this.jdbc = jdbc;
        this.bitacora = bitacora;
        this.reloj = reloj;
    }

    // ---------- Puntos ----------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> puntos(String unidad, String fuerza, String texto, Boolean activo) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNAS_PUNTO + " " + """
                 FROM puntos p
                 LEFT JOIN par_municipios m ON m.codigo = p.municipio
                 LEFT JOIN par_departamentos d ON d.codigo = m.departamento
                 LEFT JOIN par_regionales r ON r.id = p.regional_id
                 WHERE true""");
        Map<String, Object> params = new LinkedHashMap<>();
        if (unidad != null && !unidad.isBlank()) {
            sql.append(" AND p.unidad = :unidad");
            params.put("unidad", unidad);
        }
        if (fuerza != null && !fuerza.isBlank()) {
            sql.append(" AND p.fuerza = :fuerza");
            params.put("fuerza", fuerza);
        }
        if (activo != null) {
            sql.append(" AND p.activo = :activo");
            params.put("activo", activo);
        }
        if (texto != null && !texto.isBlank()) {
            sql.append(" AND (p.nombre ILIKE :t OR p.sigla ILIKE :t OR m.nombre ILIKE :t)");
            params.put("t", "%" + texto.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        sql.append(" ORDER BY p.unidad, p.fuerza, p.nombre LIMIT 2000");
        JdbcClient.StatementSpec q = jdbc.sql(sql.toString());
        for (var e : params.entrySet()) {
            q = q.param(e.getKey(), e.getValue());
        }
        return q.query(ServicioMaestros::filaPunto).list();
    }

    static Map<String, Object> filaPunto(ResultSet rs, int n) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("sigla", rs.getString("sigla"));
        m.put("nombre", rs.getString("nombre"));
        m.put("unidad", rs.getString("unidad"));
        m.put("fuerza", rs.getString("fuerza"));
        m.put("modalidad", rs.getString("modalidad"));
        m.put("nit", rs.getString("nit"));
        m.put("municipio", rs.getString("municipio"));
        m.put("municipioNombre", rs.getString("municipio_nombre"));
        m.put("departamento", rs.getString("departamento"));
        m.put("direccion", rs.getString("direccion"));
        m.put("nivel", rs.getString("nivel"));
        m.put("horario", rs.getString("horario"));
        m.put("contacto", rs.getString("contacto"));
        m.put("correos", arreglo(rs.getArray("correos")));
        m.put("regionalId", rs.getObject("regional_id"));
        m.put("regional", rs.getString("regional"));
        m.put("regionalOpl", rs.getString("regional_opl"));
        m.put("llegaDesde", rs.getString("llega_desde"));
        m.put("auditorAsignado", rs.getObject("auditor_asignado"));
        m.put("exigeQf", rs.getBoolean("exige_qf"));
        m.put("activo", rs.getBoolean("activo"));
        m.put("fuente", rs.getString("fuente"));
        return m;
    }

    private static List<String> arreglo(Array a) throws SQLException {
        return a == null ? List.of() : List.of((String[]) a.getArray());
    }

    @Transactional
    public long crearPunto(Punto p, UsuarioSesion quien) {
        Punto v = validar(p, 0);
        try {
            long id = jdbc.sql("""
                    INSERT INTO puntos (sigla, nombre, unidad, fuerza, modalidad, nit, municipio, direccion, nivel, horario, contacto, correos,
                      regional_id, llega_desde, auditor_asignado, exige_qf, creado_por, fuente)
                    VALUES (:sigla, :nombre, :unidad, :fuerza, :modalidad, :nit, :municipio, :direccion, :nivel, :horario, :contacto, :correos,
                      :regional, :llega, :auditor, :qf, :quien, 'Creado en la aplicación')
                    RETURNING id""")
                    .params(parametros(v)).param("quien", quien.usuario()).query(Long.class).single();
            bitacora.registrar("punto_creado", "Punto " + id + " creado: «" + Bitacora.cortar(v.nombre(), 80) + "» (" + v.unidad() + ", " + v.fuerza() + ")",
                    quien.id(), quien.usuario());
            return id;
        } catch (DuplicateKeyException e) {
            throw duplicado();
        }
    }

    @Transactional
    public void editarPunto(long id, Punto p, UsuarioSesion quien) {
        Map<String, Object> antes = jdbc.sql("SELECT " + COLUMNAS_PUNTO + " " + """
                 FROM puntos p LEFT JOIN par_municipios m ON m.codigo = p.municipio LEFT JOIN par_departamentos d ON d.codigo = m.departamento
                 LEFT JOIN par_regionales r ON r.id = p.regional_id WHERE p.id = :id FOR UPDATE OF p""")
                .param("id", id).query(ServicioMaestros::filaPunto).optional().orElseThrow(() -> ErrorNegocio.noEncontrado("El punto no existe."));
        Punto v = validar(p, id);
        try {
            jdbc.sql("""
                    UPDATE puntos SET sigla = :sigla, nombre = :nombre, unidad = :unidad, fuerza = :fuerza, modalidad = :modalidad, nit = :nit,
                      municipio = :municipio, direccion = :direccion, nivel = :nivel, horario = :horario, contacto = :contacto, correos = :correos,
                      regional_id = :regional, llega_desde = :llega, auditor_asignado = :auditor, exige_qf = :qf, actualizado = :ahora
                    WHERE id = :id""")
                    .params(parametros(v)).param("ahora", OffsetDateTime.now(reloj)).param("id", id).update();
        } catch (DuplicateKeyException e) {
            throw duplicado();
        }
        String cambios = Bitacora.cambios(fotoPunto(antes), fotoPunto(v), CONTACTO);
        bitacora.registrar("punto_editado", "Punto " + id + (cambios.isEmpty() ? " sin cambios" : ": " + cambios), quien.id(), quien.usuario());
    }

    @Transactional
    public void estadoPunto(long id, boolean activo, UsuarioSesion quien) {
        String nombre = jdbc.sql("SELECT nombre FROM puntos WHERE id = :id FOR UPDATE").param("id", id).query(String.class).optional()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("El punto no existe."));
        if (!activo) {
            long usuarios = jdbc.sql("SELECT count(*) FROM usuarios WHERE alcance_punto = :id AND activo").param("id", id).query(Long.class).single();
            if (usuarios > 0) {
                throw ErrorNegocio.validacion("El punto es el alcance de " + usuarios + " usuarios ESM activos: cámbielos antes de inactivarlo.");
            }
        }
        jdbc.sql("UPDATE puntos SET activo = :a, actualizado = :ahora WHERE id = :id").param("a", activo).param("ahora", OffsetDateTime.now(reloj))
                .param("id", id).update();
        bitacora.registrar("punto_editado", "Punto " + id + " «" + Bitacora.cortar(nombre, 80) + "» " + (activo ? "activado" : "inactivado"),
                quien.id(), quien.usuario());
    }

    private static ErrorNegocio duplicado() {
        return ErrorNegocio.validacion("Ya existe un punto con ese nombre en esa unidad, o con esa sigla.");
    }

    private Punto validar(Punto p, long id) {
        List<String> errores = new ArrayList<>();
        String nombre = texto(p.nombre());
        if (nombre.length() < 3 || nombre.length() > 200) {
            errores.add("el nombre debe tener de 3 a 200 caracteres");
        }
        String unidad = texto(p.unidad()).toUpperCase();
        if (!UNIDADES.contains(unidad)) {
            errores.add("la unidad debe ser ESM, FA o NC");
        }
        String fuerza = texto(p.fuerza()).toUpperCase();
        if (fuerza.isEmpty() && "NC".equals(unidad)) {
            fuerza = "NA";
        }
        if (!existe("SELECT count(*) FROM par_fuerzas WHERE codigo = :v AND activo", fuerza)) {
            errores.add("la fuerza no existe o está inactiva");
        }
        String modalidad = "ESM".equals(unidad) ? vacioANulo(p.modalidad()) : null;
        if ("ESM".equals(unidad) && (modalidad == null || !existe("SELECT count(*) FROM par_modalidades WHERE nombre = :v", modalidad))) {
            errores.add("falta la modalidad del ESM (Dispensación, Suministro, Consignación o Satélite)");
        }
        String nit = "FA".equals(unidad) ? vacioANulo(p.nit()) : null; // el NIT solo aplica a la farmacia alterna
        String municipio = vacioANulo(p.municipio());
        if (municipio != null && !existe("SELECT count(*) FROM par_municipios WHERE codigo = :v", municipio)) {
            errores.add("el municipio no existe");
        }
        String llega = vacioANulo(p.llegaDesde());
        if (llega != null && !existe("SELECT count(*) FROM par_municipios WHERE codigo = :v", llega)) {
            errores.add("el municipio «se llega desde» no existe");
        }
        if (p.regionalId() != null && !existe("SELECT count(*) FROM par_regionales WHERE id = :v AND activo", p.regionalId())) {
            errores.add("la regional no existe o está inactiva");
        }
        if (p.auditorAsignado() != null && !esAuditorActivo(p.auditorAsignado())) {
            errores.add("el auditor asignado no es un auditor activo");
        }
        String sigla = vacioANulo(p.sigla());
        largo(errores, sigla, 30, "la sigla");
        largo(errores, nit, 20, "el NIT");
        String direccion = vacioANulo(p.direccion());
        largo(errores, direccion, 200, "la dirección");
        String contacto = vacioANulo(p.contacto());
        largo(errores, contacto, 150, "el contacto");
        String nivel = vacioANulo(p.nivel());
        largo(errores, nivel, 40, "el nivel");
        String horario = vacioANulo(p.horario());
        largo(errores, horario, 200, "el horario");
        List<String> correos;
        try {
            correos = correos(p.correos());
        } catch (ErrorNegocio e) {
            errores.add(e.getMessage());
            correos = List.of();
        }
        if (!errores.isEmpty()) {
            throw ErrorNegocio.validacion("Revise el punto: " + String.join("; ", errores) + ".");
        }
        return new Punto(sigla, nombre, unidad, fuerza, modalidad, nit, municipio, direccion, nivel, horario, contacto, correos,
                p.regionalId(), llega, p.auditorAsignado(), Boolean.TRUE.equals(p.exigeQf()));
    }

    private static void largo(List<String> errores, String v, int max, String campo) {
        if (v != null && v.length() > max) {
            errores.add(campo + " tiene máximo " + max + " caracteres");
        }
    }

    private static Map<String, Object> parametros(Punto v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sigla", v.sigla());
        m.put("nombre", v.nombre());
        m.put("unidad", v.unidad());
        m.put("fuerza", v.fuerza());
        m.put("modalidad", v.modalidad());
        m.put("nit", v.nit());
        m.put("municipio", v.municipio());
        m.put("direccion", v.direccion());
        m.put("nivel", v.nivel());
        m.put("horario", v.horario());
        m.put("contacto", v.contacto());
        m.put("correos", v.correos().toArray(String[]::new));
        m.put("regional", v.regionalId());
        m.put("llega", v.llegaDesde());
        m.put("auditor", v.auditorAsignado());
        m.put("qf", v.exigeQf());
        return m;
    }

    private static Map<String, Object> fotoPunto(Map<String, Object> f) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of("sigla", "nombre", "unidad", "fuerza", "modalidad", "nit", "municipio", "direccion", "nivel", "horario",
                "contacto", "correos", "regionalId", "llegaDesde", "auditorAsignado", "exigeQf")) {
            m.put(k, f.get(k));
        }
        return m;
    }

    private static Map<String, Object> fotoPunto(Punto v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sigla", v.sigla());
        m.put("nombre", v.nombre());
        m.put("unidad", v.unidad());
        m.put("fuerza", v.fuerza());
        m.put("modalidad", v.modalidad());
        m.put("nit", v.nit());
        m.put("municipio", v.municipio());
        m.put("direccion", v.direccion());
        m.put("nivel", v.nivel());
        m.put("horario", v.horario());
        m.put("contacto", v.contacto());
        m.put("correos", v.correos());
        m.put("regionalId", v.regionalId());
        m.put("llegaDesde", v.llegaDesde());
        m.put("auditorAsignado", v.auditorAsignado());
        m.put("exigeQf", v.exigeQf());
        return m;
    }

    // ---------- Auditores ----------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> auditores() {
        return jdbc.sql("""
                SELECT u.id, u.nombre, u.usuario, u.documento, u.activo, coalesce(a.rol, 'otro') AS rol, a.municipio_base,
                       m.nombre AS municipio_nombre
                FROM usuarios u
                JOIN usuarios_perfiles up ON up.usuario_id = u.id AND up.perfil_id = :auditor
                LEFT JOIN auditores a ON a.usuario_id = u.id
                LEFT JOIN par_municipios m ON m.codigo = a.municipio_base
                ORDER BY u.activo DESC, u.nombre""").param("auditor", Perfil.AUDITOR.id()).query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("nombre", rs.getString("nombre"));
            m.put("usuario", rs.getString("usuario"));
            m.put("documento", rs.getString("documento"));
            m.put("activo", rs.getBoolean("activo"));
            m.put("rol", rs.getString("rol"));
            m.put("rolNombre", Reparto.ROLES.getOrDefault(rs.getString("rol"), "Otro"));
            m.put("municipioBase", rs.getString("municipio_base"));
            m.put("municipioNombre", rs.getString("municipio_nombre"));
            return m;
        }).list();
    }

    /** Rol técnico (decisión 2a) y municipio base. Cambiar el rol no recalcula el reparto de visitas ya programadas. */
    @Transactional
    public void editarAuditor(long usuarioId, Auditor a, UsuarioSesion quien) {
        if (!esAuditor(usuarioId)) {
            throw ErrorNegocio.noEncontrado("El usuario no es auditor.");
        }
        String rol = texto(a.rol());
        if (!Reparto.ROLES.containsKey(rol)) {
            throw ErrorNegocio.validacion("Elija el rol: " + String.join(", ", Reparto.ROLES.values()) + ".");
        }
        String municipio = vacioANulo(a.municipioBase());
        if (municipio != null && !existe("SELECT count(*) FROM par_municipios WHERE codigo = :v", municipio)) {
            throw ErrorNegocio.validacion("El municipio base no existe.");
        }
        Map<String, Object> antes = jdbc.sql("SELECT rol, municipio_base FROM auditores WHERE usuario_id = :id FOR UPDATE").param("id", usuarioId)
                .query().listOfRows().stream().findFirst().orElse(Map.of("rol", "otro"));
        jdbc.sql("""
                INSERT INTO auditores (usuario_id, rol, municipio_base, actualizado) VALUES (:id, :rol, :m, :ahora)
                ON CONFLICT (usuario_id) DO UPDATE SET rol = EXCLUDED.rol, municipio_base = EXCLUDED.municipio_base, actualizado = EXCLUDED.actualizado""")
                .param("id", usuarioId).param("rol", rol).param("m", municipio).param("ahora", OffsetDateTime.now(reloj)).update();
        Map<String, Object> despues = new LinkedHashMap<>();
        despues.put("rol", rol);
        despues.put("municipio_base", municipio);
        String cambios = Bitacora.cambios(antes, despues, Set.of());
        bitacora.registrar("auditor_editado", "Auditor " + usuarioId + (cambios.isEmpty() ? " sin cambios" : ": " + cambios), quien.id(), quien.usuario());
    }

    private boolean esAuditor(long id) {
        return jdbc.sql("SELECT count(*) FROM usuarios_perfiles WHERE usuario_id = :id AND perfil_id = :p").param("id", id)
                .param("p", Perfil.AUDITOR.id()).query(Long.class).single() > 0;
    }

    private boolean esAuditorActivo(long id) {
        return jdbc.sql("""
                SELECT count(*) FROM usuarios u JOIN usuarios_perfiles up ON up.usuario_id = u.id AND up.perfil_id = :p
                WHERE u.id = :id AND u.activo""").param("id", id).param("p", Perfil.AUDITOR.id()).query(Long.class).single() > 0;
    }

    // ---------- Operador logístico ----------

    @Transactional(readOnly = true)
    public Map<String, Object> opl() {
        return jdbc.sql("SELECT id, nombre, nit, correos FROM opl WHERE activo ORDER BY id LIMIT 1").query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("nombre", rs.getString("nombre"));
            m.put("nit", rs.getString("nit"));
            m.put("correos", arreglo(rs.getArray("correos")));
            return m;
        }).optional().orElseGet(() -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", null);
            m.put("nombre", "");
            m.put("nit", "");
            m.put("correos", List.of());
            return m;
        });
    }

    /** Un solo operador logístico activo (el contrato vigente); sus correos reciben la notificación previa y el acta. */
    @Transactional
    public void guardarOpl(Opl o, UsuarioSesion quien) {
        String nombre = texto(o.nombre());
        if (nombre.length() < 3 || nombre.length() > 200) {
            throw ErrorNegocio.validacion("La razón social del operador debe tener de 3 a 200 caracteres.");
        }
        String nit = vacioANulo(o.nit());
        if (nit != null && !nit.matches("^[0-9]{6,12}(-[0-9])?$")) {
            throw ErrorNegocio.validacion("El NIT debe tener de 6 a 12 dígitos y, si acaso, el dígito de verificación (900123456-7).");
        }
        List<String> correos = correos(o.correos());
        if (correos.isEmpty()) {
            throw ErrorNegocio.validacion("Registre al menos un correo del operador: recibe la notificación previa (Anexo 3 p.9).");
        }
        Long id = jdbc.sql("SELECT id FROM opl WHERE activo ORDER BY id LIMIT 1 FOR UPDATE").query(Long.class).optional().orElse(null);
        if (id == null) {
            jdbc.sql("INSERT INTO opl (nombre, nit, correos) VALUES (:n, :nit, :c)").param("n", nombre).param("nit", nit)
                    .param("c", correos.toArray(String[]::new)).update();
        } else {
            jdbc.sql("UPDATE opl SET nombre = :n, nit = :nit, correos = :c, actualizado = :ahora WHERE id = :id").param("n", nombre).param("nit", nit)
                    .param("c", correos.toArray(String[]::new)).param("ahora", OffsetDateTime.now(reloj)).param("id", id).update();
        }
        bitacora.registrar("catalogo_editado", "Operador logístico actualizado: «" + Bitacora.cortar(nombre, 80) + "»", quien.id(), quien.usuario());
    }

    // ---------- Obligaciones del contrato (parte OBL del formulario) ----------

    public record Obligacion(String prestador, String codigo, String texto, String fuente, Boolean activo) {
    }

    public List<Map<String, Object>> obligaciones() {
        return jdbc.sql("SELECT id, prestador, codigo, texto, fuente, activo FROM obligaciones_contrato ORDER BY prestador, orden, codigo").query().listOfRows();
    }

    /** El coordinador registra las obligaciones de la minuta del OPL y de la farmacia alterna (mientras llega la lectura de la minuta). */
    @Transactional
    public long guardarObligacion(Long id, Obligacion o, UsuarioSesion quien) {
        String prestador = texto(o.prestador()).toLowerCase();
        if (!Set.of("opl", "fa").contains(prestador)) {
            throw ErrorNegocio.validacion("El prestador debe ser el OPL o la farmacia alterna.");
        }
        String codigo = texto(o.codigo()).toUpperCase();
        if (!codigo.matches("^OB-[A-Z0-9-]{1,20}$")) {
            throw ErrorNegocio.validacion("El código de la obligación empieza por OB- (p. ej. OB-OPL-01).");
        }
        String t = texto(o.texto());
        if (t.length() < 10 || t.length() > 2000) {
            throw ErrorNegocio.validacion("El texto de la obligación debe tener de 10 a 2000 caracteres.");
        }
        String fuente = vacioANulo(o.fuente());
        boolean activo = o.activo() == null || o.activo();
        try {
            if (id == null) {
                long nuevo = jdbc.sql("""
                        INSERT INTO obligaciones_contrato (prestador, codigo, texto, fuente, activo, orden)
                        VALUES (:p, :c, :t, :f, :a, (SELECT coalesce(max(orden), 0) + 1 FROM obligaciones_contrato)) RETURNING id""")
                        .param("p", prestador).param("c", codigo).param("t", t).param("f", fuente).param("a", activo).query(Long.class).single();
                bitacora.registrar("catalogo_creado", "Obligación " + codigo + " creada", quien.id(), quien.usuario());
                return nuevo;
            }
            // Una obligación ya calificada conserva su código y su prestador: la clave «ob:<código>» une el 5W2H, las respuestas y los soportes del OPL
            boolean cambiaClave = jdbc.sql("""
                    SELECT count(*) FROM obligaciones_contrato o WHERE o.id = :id AND (o.codigo <> :c OR o.prestador <> :p)
                      AND EXISTS (SELECT 1 FROM visitas_obligaciones x WHERE x.obligacion_id = o.id)""")
                    .param("id", id).param("c", codigo).param("p", prestador).query(Long.class).single() > 0;
            if (cambiaClave) {
                throw ErrorNegocio.validacion("La obligación ya se calificó en visitas: no cambie su código ni su prestador; inactívela y cree otra.");
            }
            int n = jdbc.sql("UPDATE obligaciones_contrato SET prestador = :p, codigo = :c, texto = :t, fuente = :f, activo = :a WHERE id = :id")
                    .param("p", prestador).param("c", codigo).param("t", t).param("f", fuente).param("a", activo).param("id", id).update();
            if (n != 1) {
                throw ErrorNegocio.noEncontrado("La obligación no existe.");
            }
            bitacora.registrar("catalogo_editado", "Obligación " + codigo + " editada", quien.id(), quien.usuario());
            return id;
        } catch (DuplicateKeyException e) {
            throw ErrorNegocio.validacion("Ya existe la obligación " + codigo + ".");
        }
    }

    // ---------- Apoyo ----------

    private boolean existe(String sql, Object valor) {
        return jdbc.sql(sql).param("v", valor).query(Long.class).single() > 0;
    }

    static List<String> correos(List<String> entrada) {
        Set<String> r = new LinkedHashSet<>();
        if (entrada != null) {
            for (String c : entrada) {
                for (String parte : texto(c).split("[,;\\s]+")) {
                    String n = Correos.normalizar(parte);
                    if (n.isEmpty()) {
                        continue;
                    }
                    if (!Correos.valido(n)) {
                        throw ErrorNegocio.validacion("el correo «" + Bitacora.cortar(parte, 60) + "» no es válido");
                    }
                    r.add(n);
                }
            }
        }
        if (r.size() > 10) {
            throw ErrorNegocio.validacion("máximo 10 correos");
        }
        return List.copyOf(r);
    }

    private static String texto(String s) {
        return s == null ? "" : s.strip();
    }

    private static String vacioANulo(String s) {
        String t = texto(s);
        return t.isEmpty() ? null : t;
    }
}
