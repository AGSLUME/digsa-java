package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.reglas.Perfil;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Acceso a usuarios y perfiles con SQL explícito y parámetros enlazados. */
@Repository
public class RepositorioUsuarios {

    private static final String COLUMNAS = """
            u.id, u.nombre, u.documento, u.telefono, u.usuario, u.correo, u.clave_hash, u.activo, u.clave_definida, u.clave_vence,
            u.debe_cambiar_clave, u.clave_actualizada, u.token_hash, u.token_vence, u.intentos, u.bloqueado_hasta, u.ultimo_ingreso,
            u.alcance_fuerza, u.alcance_punto, u.alcance_regional, u.sesion_huella, u.sesion_ultimo, u.clave_inicial,
            COALESCE((SELECT string_agg(up.perfil_id::text, ',') FROM usuarios_perfiles up JOIN perfiles p ON p.id = up.perfil_id
                      WHERE up.usuario_id = u.id AND p.activo), '') AS perfiles""";

    private final JdbcClient jdbc;

    public RepositorioUsuarios(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Usuario> porId(long id) {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM usuarios u WHERE u.id = :id").param("id", id).query(RepositorioUsuarios::fila).optional();
    }

    public Optional<Usuario> porUsuario(String usuario) {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM usuarios u WHERE u.usuario = :u").param("u", usuario).query(RepositorioUsuarios::fila).optional();
    }

    public Optional<Usuario> porUsuarioOCorreo(String valor) {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM usuarios u WHERE u.usuario = :v OR u.correo = :v ORDER BY u.id LIMIT 1")
                .param("v", valor).query(RepositorioUsuarios::fila).optional();
    }

    public Optional<Usuario> porTokenHash(String hash) {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM usuarios u WHERE u.token_hash = :h").param("h", hash).query(RepositorioUsuarios::fila).optional();
    }

    public List<Usuario> todos() {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM usuarios u ORDER BY u.nombre, u.id").query(RepositorioUsuarios::fila).list();
    }

    public boolean existeUsuario(String usuario, long excepto) {
        return jdbc.sql("SELECT count(*) FROM usuarios WHERE usuario = :u AND id <> :id").param("u", usuario).param("id", excepto)
                .query(Long.class).single() > 0;
    }

    public boolean existeCorreo(String correo, long excepto) {
        return jdbc.sql("SELECT count(*) FROM usuarios WHERE correo = :c AND id <> :id").param("c", correo).param("id", excepto)
                .query(Long.class).single() > 0;
    }

    /**
     * Suma un intento fallido de forma atómica: reinicia el contador si el bloqueo ya venció y bloquea al llegar a 5.
     *
     * @return true si este intento bloqueó la cuenta (cuenta para la alerta de bloqueo masivo)
     */
    public boolean registrarFallo(long id, Instant ahora, int maximo, int minutosBloqueo) {
        return jdbc.sql("""
                UPDATE usuarios SET
                  intentos = CASE WHEN bloqueado_hasta IS NOT NULL AND bloqueado_hasta <= :ahora THEN 1 ELSE intentos + 1 END,
                  bloqueado_hasta = CASE
                    WHEN (CASE WHEN bloqueado_hasta IS NOT NULL AND bloqueado_hasta <= :ahora THEN 1 ELSE intentos + 1 END) >= :maximo
                      THEN :hasta
                    WHEN bloqueado_hasta IS NOT NULL AND bloqueado_hasta <= :ahora THEN NULL
                    ELSE bloqueado_hasta END,
                  actualizado = :ahora
                WHERE id = :id AND (bloqueado_hasta IS NULL OR bloqueado_hasta <= :ahora)
                RETURNING bloqueado_hasta IS NOT NULL AND bloqueado_hasta > :ahora""")
                .param("ahora", odt(ahora)).param("maximo", maximo).param("hasta", odt(ahora.plusSeconds(minutosBloqueo * 60L)))
                .param("id", id).query(Boolean.class).optional().orElse(false);
    }

    public void reiniciarIntentos(long id, Instant ahora) {
        jdbc.sql("UPDATE usuarios SET intentos = 0, bloqueado_hasta = NULL, actualizado = :ahora WHERE id = :id")
                .param("ahora", odt(ahora)).param("id", id).update();
    }

    /**
     * Guarda la huella de la sesión nueva y la fecha del ingreso, solo si no hay otra sesión con actividad reciente (decisión 7.1):
     * dos ingresos casi simultáneos no pasan los dos. @return false si otra sesión sigue abierta
     */
    public boolean iniciarSesion(long id, String huella, Instant ahora, int minutosInactividad) {
        return jdbc.sql("""
                UPDATE usuarios SET sesion_huella = :h, sesion_ultimo = :ahora, ultimo_ingreso = :ahora, intentos = 0, bloqueado_hasta = NULL
                WHERE id = :id AND (sesion_huella IS NULL OR sesion_ultimo IS NULL OR sesion_ultimo <= :limite)""")
                .param("h", huella).param("ahora", odt(ahora)).param("limite", odt(ahora.minusSeconds(60L * minutosInactividad))).param("id", id)
                .update() == 1;
    }

    /** Borra la huella solo si es la de esta sesión (una sesión ya reemplazada no cierra la vigente). */
    public void cerrarSesion(long id, String huella) {
        jdbc.sql("UPDATE usuarios SET sesion_huella = NULL, sesion_ultimo = NULL WHERE id = :id AND sesion_huella = :h").param("id", id).param("h", huella).update();
    }

    /** Actividad de la sesión vigente (decisión 7.1: mientras haya actividad reciente se bloquea un ingreso nuevo). */
    public void tocarSesion(long id, String huella, Instant ahora) {
        jdbc.sql("UPDATE usuarios SET sesion_ultimo = :ahora WHERE id = :id AND sesion_huella = :h").param("ahora", odt(ahora)).param("id", id)
                .param("h", huella).update();
    }

    /** Cierra la sesión que tenga abierta (administrador, inactivación, cambio de contraseña). @return true si había una */
    public boolean cerrarCualquierSesion(long id) {
        return jdbc.sql("UPDATE usuarios SET sesion_huella = NULL, sesion_ultimo = NULL WHERE id = :id AND sesion_huella IS NOT NULL")
                .param("id", id).update() == 1;
    }

    /** Horas que vale la contraseña inicial sin usarse: la conoce el administrador y suele quedar en papel o en un chat. */
    public static final int HORAS_CLAVE_INICIAL = 72;

    /** Contraseña inicial asignada por el administrador: vale 72 horas para ingresar y obliga a cambiarla en la aplicación. */
    public void asignarClaveInicial(long id, String claveHash, Instant ahora) {
        jdbc.sql("""
                UPDATE usuarios SET clave_hash = :c, clave_definida = true, clave_inicial = true, debe_cambiar_clave = true, clave_vence = :vence,
                  clave_actualizada = :ahora, token_hash = NULL, token_vence = NULL, intentos = 0, bloqueado_hasta = NULL, actualizado = :ahora
                WHERE id = :id""").param("c", claveHash).param("vence", odt(ahora.plusSeconds(3600L * HORAS_CLAVE_INICIAL)))
                .param("ahora", odt(ahora)).param("id", id).update();
    }

    /** Cambio de la contraseña inicial dentro de la aplicación (sin enlace). @return false si ya se había cambiado (doble envío) */
    public boolean cambiarClaveInicial(long id, String claveHash, Instant vence, Instant ahora) {
        return jdbc.sql("""
                UPDATE usuarios SET clave_hash = :c, clave_inicial = false, debe_cambiar_clave = false, clave_vence = :vence,
                  clave_actualizada = :ahora, actualizado = :ahora WHERE id = :id AND clave_inicial""")
                .param("c", claveHash).param("vence", odt(vence)).param("ahora", odt(ahora)).param("id", id).update() == 1;
    }

    /** Nombres configurables de los perfiles (el catálogo final de perfiles llega por adenda). */
    public void renombrarPerfil(int id, String nombre, String descripcion) {
        jdbc.sql("UPDATE perfiles SET nombre = :n, descripcion = :d WHERE id = :id").param("n", nombre).param("d", descripcion).param("id", id).update();
    }

    public java.util.Map<Integer, String> nombresPerfiles() {
        java.util.Map<Integer, String> m = new java.util.HashMap<>();
        jdbc.sql("SELECT id, nombre FROM perfiles").query((rs, n) -> m.put(rs.getInt(1), rs.getString(2))).list();
        return m;
    }

    public void guardarToken(long id, String hash, Instant vence, boolean exigirCambio, Instant ahora) {
        jdbc.sql("""
                UPDATE usuarios SET token_hash = :h, token_vence = :v, actualizado = :ahora,
                  debe_cambiar_clave = CASE WHEN :exigir THEN true ELSE debe_cambiar_clave END,
                  clave_inicial = CASE WHEN :exigir THEN false ELSE clave_inicial END,
                  intentos = CASE WHEN :exigir THEN 0 ELSE intentos END,
                  bloqueado_hasta = CASE WHEN :exigir THEN NULL ELSE bloqueado_hasta END
                WHERE id = :id""")
                .param("h", hash).param("v", odt(vence)).param("exigir", exigirCambio).param("ahora", odt(ahora)).param("id", id).update();
    }

    /** Guarda la contraseña nueva y consume el enlace, solo si el token sigue siendo el mismo (un solo uso). */
    public boolean definirClave(long id, String tokenHash, String claveHash, Instant vence, Instant ahora) {
        return jdbc.sql("""
                UPDATE usuarios SET clave_hash = :c, clave_definida = true, debe_cambiar_clave = false, clave_vence = :vence,
                  clave_actualizada = :ahora, token_hash = NULL, token_vence = NULL, intentos = 0, bloqueado_hasta = NULL,
                  sesion_huella = NULL, sesion_ultimo = NULL, clave_inicial = false, actualizado = :ahora
                WHERE id = :id AND token_hash = :t""")
                .param("c", claveHash).param("vence", odt(vence)).param("ahora", odt(ahora)).param("id", id).param("t", tokenHash)
                .update() == 1;
    }

    public long crear(String nombre, String documento, String telefono, String usuario, String correo, String claveHash,
                      String fuerza, Long punto, Long regional, Instant ahora) {
        return jdbc.sql("""
                INSERT INTO usuarios (nombre, documento, telefono, usuario, correo, clave_hash, alcance_fuerza, alcance_punto,
                                      alcance_regional, creado, actualizado)
                VALUES (:nombre, :documento, :telefono, :usuario, :correo, :clave, :fuerza, :punto, :regional, :ahora, :ahora)
                RETURNING id""")
                .param("nombre", nombre).param("documento", documento).param("telefono", telefono).param("usuario", usuario)
                .param("correo", correo).param("clave", claveHash).param("fuerza", fuerza).param("punto", punto)
                .param("regional", regional).param("ahora", odt(ahora))
                .query(Long.class).single();
    }

    public void actualizar(long id, String nombre, String documento, String telefono, String usuario, String correo, String fuerza,
                           Long punto, Long regional, Instant ahora) {
        jdbc.sql("""
                UPDATE usuarios SET nombre = :nombre, documento = :documento, telefono = :telefono, usuario = :usuario, correo = :correo,
                  alcance_fuerza = :fuerza, alcance_punto = :punto, alcance_regional = :regional, actualizado = :ahora
                WHERE id = :id""")
                .param("nombre", nombre).param("documento", documento).param("telefono", telefono).param("usuario", usuario)
                .param("correo", correo).param("fuerza", fuerza).param("punto", punto).param("regional", regional)
                .param("ahora", odt(ahora)).param("id", id).update();
    }

    public void reemplazarPerfiles(long id, Collection<Perfil> perfiles) {
        jdbc.sql("DELETE FROM usuarios_perfiles WHERE usuario_id = :id").param("id", id).update();
        for (Perfil p : perfiles) {
            jdbc.sql("INSERT INTO usuarios_perfiles (usuario_id, perfil_id) VALUES (:u, :p) ON CONFLICT DO NOTHING").param("u", id).param("p", p.id()).update();
        }
    }

    public void agregarPerfil(long id, Perfil perfil) {
        jdbc.sql("INSERT INTO usuarios_perfiles (usuario_id, perfil_id) VALUES (:u, :p) ON CONFLICT DO NOTHING")
                .param("u", id).param("p", perfil.id()).update();
    }

    /** Bloquea la fila del usuario hasta el fin de la transacción (ediciones simultáneas). */
    public void bloquear(long id) {
        jdbc.sql("SELECT id FROM usuarios WHERE id = :id FOR UPDATE").param("id", id).query(Long.class).optional();
    }

    /** El enlace pendiente deja de servir. */
    public void anularEnlace(long id) {
        jdbc.sql("UPDATE usuarios SET token_hash = NULL, token_vence = NULL WHERE id = :id").param("id", id).update();
    }

    public void cambiarEstado(long id, boolean activo, Instant ahora) {
        jdbc.sql("UPDATE usuarios SET activo = :a, actualizado = :ahora WHERE id = :id").param("a", activo).param("ahora", odt(ahora))
                .param("id", id).update();
    }

    /** Perfiles activos que se pueden asignar (Consulta queda fuera). */
    public List<Map<String, Object>> perfilesActivos() {
        return jdbc.sql("SELECT id, nombre, descripcion FROM perfiles WHERE activo ORDER BY id").query().listOfRows();
    }

    static Usuario fila(ResultSet rs, int n) throws SQLException {
        Set<Perfil> perfiles = EnumSet.noneOf(Perfil.class);
        String ids = rs.getString("perfiles");
        if (ids != null && !ids.isBlank()) {
            perfiles.addAll(java.util.Arrays.stream(ids.split(",")).map(Integer::parseInt).map(Perfil::deId)
                    .flatMap(Optional::stream).collect(Collectors.toSet()));
        }
        return new Usuario(rs.getLong("id"), rs.getString("nombre"), rs.getString("documento"), rs.getString("telefono"),
                rs.getString("usuario"), rs.getString("correo"), rs.getString("clave_hash"), rs.getBoolean("activo"),
                rs.getBoolean("clave_definida"), instante(rs, "clave_vence"), rs.getBoolean("debe_cambiar_clave"),
                instante(rs, "clave_actualizada"), rs.getString("token_hash"), instante(rs, "token_vence"), rs.getInt("intentos"),
                instante(rs, "bloqueado_hasta"), instante(rs, "ultimo_ingreso"), rs.getString("alcance_fuerza"),
                (Long) rs.getObject("alcance_punto"), (Long) rs.getObject("alcance_regional"), rs.getString("sesion_huella"),
                instante(rs, "sesion_ultimo"), rs.getBoolean("clave_inicial"), Set.copyOf(perfiles));
    }

    static Instant instante(ResultSet rs, String columna) throws SQLException {
        Timestamp t = rs.getTimestamp(columna);
        return t == null ? null : t.toInstant();
    }

    static OffsetDateTime odt(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }
}
