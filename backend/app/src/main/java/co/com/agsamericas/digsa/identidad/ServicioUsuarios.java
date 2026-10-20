package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Perfil;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Usuarios del administrador (Anexo 5, prueba 2: se crean solo desde la interfaz, con contraseñas cifradas). */
@Service
public class ServicioUsuarios {

    private static final Pattern USUARIO = Pattern.compile("^[a-z0-9._-]{3,50}$");
    private static final Set<String> FUERZAS_CON_SANIDAD = Set.of("EJC", "ARC", "FAC");

    public record Datos(Long id, String nombre, String documento, String telefono, String usuario, String correo, List<Integer> perfiles,
                        String alcanceFuerza, Long alcancePunto, Long alcanceRegional) {
    }

    public record Guardado(long id, boolean creado, boolean correoOk, String mensaje, String enlaceLocal) {
    }

    private final RepositorioUsuarios usuarios;
    private final ServicioClaves claves;
    private final RegistroBitacora bitacora;
    private final JdbcClient jdbc;
    private final Clock reloj;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public ServicioUsuarios(RepositorioUsuarios usuarios, ServicioClaves claves, RegistroBitacora bitacora, JdbcClient jdbc, Clock reloj,
                            org.springframework.transaction.support.TransactionTemplate tx) {
        this.tx = tx;
        this.usuarios = usuarios;
        this.claves = claves;
        this.bitacora = bitacora;
        this.jdbc = jdbc;
        this.reloj = reloj;
    }

    @Transactional(readOnly = true)
    public List<Usuario> listar() {
        return usuarios.todos();
    }

    /** Validaciones fuera de transacción; las escrituras, en una transacción corta; el correo, después del commit. */
    public Guardado guardar(Datos d, UsuarioSesion quien) {
        boolean nuevo = d.id() == null || d.id() <= 0;
        String nombre = texto(d.nombre());
        String usuario = texto(d.usuario()).toLowerCase();
        String correo = Correos.normalizar(texto(d.correo()));
        String documento = vacioANulo(d.documento());
        String telefono = vacioANulo(d.telefono());
        if (nombre.codePointCount(0, nombre.length()) < 3 || nombre.length() > 150) {
            throw ErrorNegocio.validacion("Escriba el nombre completo (mínimo 3 caracteres).");
        }
        if (!USUARIO.matcher(usuario).matches()) {
            throw ErrorNegocio.validacion("El usuario debe tener de 3 a 50 caracteres: letras minúsculas sin tildes, números, punto, guion o guion bajo.");
        }
        if (!Correos.valido(correo)) {
            throw ErrorNegocio.validacion("Escriba un correo válido.");
        }
        if (documento != null && documento.length() > 30 || telefono != null && telefono.length() > 30) {
            throw ErrorNegocio.validacion("El documento y el teléfono tienen máximo 30 caracteres.");
        }
        Set<Perfil> perfiles = perfiles(d.perfiles());
        Alcance.combinacionInvalida(perfiles).ifPresent(m -> {
            throw ErrorNegocio.validacion(m);
        });
        if (perfiles.contains(Perfil.AUDITOR) && documento == null) {
            throw ErrorNegocio.validacion("El documento es obligatorio para los auditores: va en la notificación previa de la visita (decisión 26).");
        }
        String fuerza = perfiles.contains(Perfil.SANIDAD) ? texto(d.alcanceFuerza()).toUpperCase() : null;
        if (fuerza != null && !FUERZAS_CON_SANIDAD.contains(fuerza)) {
            throw ErrorNegocio.validacion("El perfil Sanidad necesita la fuerza (DISAN Ejército, DISAN Armada o JEFSA).");
        }
        Long punto = perfiles.contains(Perfil.ESM) ? d.alcancePunto() : null;
        if (perfiles.contains(Perfil.ESM) && !existeActivo("puntos", punto)) {
            throw ErrorNegocio.validacion("El perfil ESM necesita el establecimiento (ESM activo).");
        }
        Long regional = perfiles.contains(Perfil.OPL_REGIONAL) ? d.alcanceRegional() : null;
        if (perfiles.contains(Perfil.OPL_REGIONAL) && !existeActivo("regionales", regional)) {
            throw ErrorNegocio.validacion("El perfil OPL regional necesita la regional (regístrela en Puntos y auditores › Contrato).");
        }
        long excepto = nuevo ? 0 : d.id();
        if (usuarios.existeUsuario(usuario, excepto)) {
            throw ErrorNegocio.validacion("Ya existe un usuario con ese nombre de usuario.");
        }
        if (usuarios.existeCorreo(correo, excepto)) {
            throw ErrorNegocio.validacion("Ya existe un usuario con ese correo.");
        }
        Instant ahora = reloj.instant();
        try {
            if (nuevo) {
                // El usuario y sus perfiles se confirman en la base antes de que salga el correo (sin enlace a un usuario que no quedó)
                String claveAleatoria = claves.claveAleatoriaCodificada();
                long id = tx.execute(t -> {
                    long nuevoId = usuarios.crear(nombre, documento, telefono, usuario, correo, claveAleatoria, fuerza, punto, regional, ahora);
                    usuarios.reemplazarPerfiles(nuevoId, perfiles);
                    return nuevoId;
                });
                Usuario creado = usuarios.porId(id).orElseThrow();
                ServicioClaves.EnvioEnlace e = claves.enviarEnlace(creado, "Bienvenido a la Suite DIGSA",
                        "Se creó su usuario «" + usuario + "» en la Suite DIGSA. Defina su contraseña con el siguiente enlace.", false);
                bitacora.registrar("usuario_creado", "Usuario " + usuario + " creado; perfiles: " + ids(perfiles)
                        + (e.correoOk() ? "" : " (el correo de bienvenida NO salió)"), quien.id(), quien.usuario());
                String msg = e.correoOk() ? "Usuario creado. Se le envió el enlace para definir su contraseña."
                        : "Usuario creado, pero NO se pudo enviar el correo (" + e.error() + "). Use «Restablecer contraseña» cuando el correo funcione.";
                return new Guardado(id, true, e.correoOk(), msg, e.enlaceLocal());
            }
            Usuario actual = usuarios.porId(d.id()).orElseThrow(() -> ErrorNegocio.noEncontrado("El usuario no existe."));
            if (actual.id() == quien.id() && !perfiles.contains(Perfil.ADMINISTRADOR)) {
                throw ErrorNegocio.validacion("No puede quitarse a sí mismo el perfil Administrador.");
            }
            tx.executeWithoutResult(t -> {
                usuarios.bloquear(actual.id()); // dos administradores editando al mismo usuario: uno espera al otro
                usuarios.actualizar(actual.id(), nombre, documento, telefono, usuario, correo, fuerza, punto, regional, ahora);
                usuarios.reemplazarPerfiles(actual.id(), perfiles);
                if (!correo.equals(actual.correo())) {
                    usuarios.anularEnlace(actual.id()); // el enlace enviado al correo anterior deja de servir
                }
            });
            bitacora.registrar("usuario_editado", "Usuario " + usuario + " editado; perfiles: " + ids(perfiles), quien.id(), quien.usuario());
            return new Guardado(actual.id(), false, true, "Usuario actualizado.", null);
        } catch (DuplicateKeyException e) {
            // Dos administradores a la vez (prueba 2: "de forma simultánea en dos equipos"): la base decide quién gana
            throw ErrorNegocio.validacion("Ya existe un usuario con ese nombre de usuario o ese correo.");
        }
    }

    @Transactional
    public void cambiarEstado(long id, boolean activo, UsuarioSesion quien) {
        if (id == quien.id() && !activo) {
            throw ErrorNegocio.validacion("No puede inactivar su propio usuario.");
        }
        Usuario u = usuarios.porId(id).orElseThrow(() -> ErrorNegocio.noEncontrado("El usuario no existe."));
        usuarios.cambiarEstado(id, activo, reloj.instant());
        if (!activo) {
            usuarios.anularEnlace(id); // un enlace pendiente de un usuario inactivado no debe servir
        }
        bitacora.registrar("usuario_estado", "Usuario " + u.usuario() + (activo ? " activado" : " inactivado"), quien.id(), quien.usuario());
    }

    /** Exige el cambio de contraseña y envía un enlace nuevo de 60 minutos (sin transacción: el correo no retiene la conexión). */
    public ServicioClaves.EnvioEnlace restablecer(long id, UsuarioSesion quien) {
        Usuario u = usuarios.porId(id).orElseThrow(() -> ErrorNegocio.noEncontrado("El usuario no existe."));
        ServicioClaves.EnvioEnlace e = claves.enviarEnlace(u, "Defina una contraseña nueva",
                "El administrador de la Suite DIGSA pidió que defina una contraseña nueva.", true);
        if (!e.correoOk()) {
            throw new ErrorNegocio(HttpStatus.BAD_GATEWAY, "No se pudo enviar el correo: " + e.error());
        }
        bitacora.registrar("usuario_restablecer", "Restablecer contraseña de " + u.usuario(), quien.id(), quien.usuario());
        return e;
    }

    public List<Map<String, Object>> perfilesDisponibles() {
        return usuarios.perfilesActivos();
    }

    private Set<Perfil> perfiles(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            throw ErrorNegocio.validacion("Marque al menos un perfil.");
        }
        Set<Integer> activos = Set.copyOf(jdbc.sql("SELECT id FROM perfiles WHERE activo").query(Integer.class).list());
        Set<Perfil> r = EnumSet.noneOf(Perfil.class);
        for (Integer id : ids) {
            if (id == null || !activos.contains(id)) {
                throw ErrorNegocio.validacion("Hay un perfil no válido.");
            }
            r.add(Perfil.deId(id).orElseThrow(() -> ErrorNegocio.validacion("Hay un perfil no válido.")));
        }
        return r;
    }

    /** ¿Existe y está activo? Si la tabla aún no existe (catálogos del sprint 2), no hay a quién asignar. */
    private boolean existeActivo(String tabla, Long id) {
        if (id == null || id <= 0) {
            return false;
        }
        Boolean hay = jdbc.sql("SELECT to_regclass(:t) IS NOT NULL").param("t", tabla).query(Boolean.class).single();
        if (!Boolean.TRUE.equals(hay)) {
            return false;
        }
        return jdbc.sql("SELECT count(*) FROM " + tabla + " WHERE id = :id AND activo").param("id", id).query(Long.class).single() > 0;
    }

    private static String ids(Set<Perfil> perfiles) {
        return String.join(",", perfiles.stream().map(p -> String.valueOf(p.id())).sorted().toList());
    }

    private static String texto(String s) {
        return s == null ? "" : s.strip();
    }

    private static String vacioANulo(String s) {
        String t = texto(s);
        return t.isEmpty() ? null : t;
    }
}
