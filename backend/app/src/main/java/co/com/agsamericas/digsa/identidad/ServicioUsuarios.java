package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Claves;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Perfil;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
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
                        String alcanceFuerza, Long alcancePunto, Long alcanceRegional, String claveInicial) {
    }

    /** Datos personales (Ley 1581): en la bitácora se dice que cambiaron, sin los valores. */
    private static final Set<String> PERSONALES = Set.of("nombre", "documento", "telefono", "correo");

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
        // Móvil en E.164 para los SMS de las alertas críticas; el que no cambió se conserva (usuarios anteriores a la validación)
        String telefono = telefonoMovil(vacioANulo(d.telefono()), nuevo ? null : usuarios.porId(d.id()).map(Usuario::telefono).orElse(null));
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
        if (perfiles.contains(Perfil.OPL_REGIONAL) && !existeActivo("par_regionales", regional)) {
            throw ErrorNegocio.validacion("El perfil OPL regional necesita la regional (regístrela en Puntos y auditores › Contrato).");
        }
        String claveInicial = nuevo ? vacioANulo(d.claveInicial()) : null;
        if (claveInicial != null) {
            Claves.problema(claveInicial).ifPresent(p -> {
                throw ErrorNegocio.validacion("Contraseña inicial: " + p);
            });
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
                String inicialCodificada = claveInicial == null ? null : claves.codificar(claveInicial);
                long id = tx.execute(t -> {
                    long nuevoId = usuarios.crear(nombre, documento, telefono, usuario, correo, claveAleatoria, fuerza, punto, regional, ahora);
                    usuarios.reemplazarPerfiles(nuevoId, perfiles);
                    if (inicialCodificada != null) {
                        usuarios.asignarClaveInicial(nuevoId, inicialCodificada, ahora);
                    }
                    return nuevoId;
                });
                if (inicialCodificada != null) {
                    // Prueba 2 y condición 12: contraseña inicial del administrador, cambio obligatorio al primer ingreso, sin correo
                    bitacora.registrar("usuario_creado", "Usuario " + usuario + " creado con contraseña inicial; perfiles: " + ids(perfiles),
                            quien.id(), quien.usuario());
                    return new Guardado(id, true, true, "Usuario creado con contraseña inicial. Entréguela al usuario por un canal seguro: "
                            + "deberá cambiarla en su primer ingreso.", null);
                }
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
            String cambios = Bitacora.cambios(foto(actual.nombre(), actual.documento(), actual.telefono(), actual.usuario(), actual.correo(),
                    actual.perfiles(), actual.alcanceFuerza(), actual.alcancePunto(), actual.alcanceRegional()),
                    foto(nombre, documento, telefono, usuario, correo, perfiles, fuerza, punto, regional), PERSONALES);
            bitacora.registrar("usuario_editado", "Usuario " + usuario + " editado" + (cambios.isEmpty() ? " (sin cambios)" : ": " + cambios),
                    quien.id(), quien.usuario());
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
        boolean cerro = false;
        if (!activo) {
            usuarios.anularEnlace(id); // un enlace pendiente de un usuario inactivado no debe servir
            cerro = usuarios.cerrarCualquierSesion(id);
        }
        bitacora.registrar("usuario_estado", "Usuario " + u.usuario() + (activo ? " activado" : " inactivado"), quien.id(), quien.usuario());
        if (cerro) {
            // Decisión 7.3: la salida forzada por la inactivación queda en la bitácora
            bitacora.registrar("sesion_cerrada", "Sesión de " + u.usuario() + " cerrada por inactivación del usuario", quien.id(), quien.usuario());
        }
    }

    /** Quita el bloqueo por intentos fallidos antes de los 15 minutos (el usuario lo pide al administrador). @return si estaba bloqueado */
    public boolean desbloquear(long id, UsuarioSesion quien) {
        Usuario u = usuarios.porId(id).orElseThrow(() -> ErrorNegocio.noEncontrado("El usuario no existe."));
        boolean estaba = usuarios.desbloquear(id, reloj.instant());
        if (estaba) {
            bitacora.registrar("usuario_desbloqueado", "Usuario " + u.usuario() + " desbloqueado por el administrador", quien.id(), quien.usuario());
        }
        return estaba;
    }

    /** Decisión 7.1: el administrador cierra la sesión abierta de un usuario (p. ej. la dejó abierta en otro equipo). */
    public boolean cerrarSesion(long id, UsuarioSesion quien) {
        Usuario u = usuarios.porId(id).orElseThrow(() -> ErrorNegocio.noEncontrado("El usuario no existe."));
        boolean cerro = usuarios.cerrarCualquierSesion(id);
        if (cerro) {
            bitacora.registrar("sesion_cerrada", "Sesión de " + u.usuario() + " cerrada por el administrador", quien.id(), quien.usuario());
        }
        return cerro;
    }

    /** Contraseña inicial nueva asignada por el administrador (sin correo); cierra la sesión abierta del usuario. */
    public void asignarClaveInicial(long id, String clave, UsuarioSesion quien) {
        Usuario u = usuarios.porId(id).orElseThrow(() -> ErrorNegocio.noEncontrado("El usuario no existe."));
        Claves.problema(clave == null ? "" : clave).ifPresent(p -> {
            throw ErrorNegocio.validacion("Contraseña inicial: " + p);
        });
        String codificada = claves.codificar(clave);
        Instant ahora = reloj.instant();
        boolean cerro = Boolean.TRUE.equals(tx.execute(t -> {
            usuarios.asignarClaveInicial(id, codificada, ahora);
            return usuarios.cerrarCualquierSesion(id);
        }));
        bitacora.registrar("usuario_restablecer", "Contraseña inicial asignada a " + u.usuario(), quien.id(), quien.usuario());
        if (cerro) {
            bitacora.registrar("sesion_cerrada", "Sesión de " + u.usuario() + " cerrada por asignación de contraseña", quien.id(), quien.usuario());
        }
    }

    /** Nombres configurables de los perfiles: la lista final llega por adenda (decisión del 2026-10-20). */
    @Transactional
    public void renombrarPerfil(int id, String nombre, String descripcion, UsuarioSesion quien) {
        String n = texto(nombre);
        String d = vacioANulo(descripcion);
        if (n.length() < 3 || n.length() > 60) {
            throw ErrorNegocio.validacion("El nombre del perfil debe tener de 3 a 60 caracteres.");
        }
        if (d != null && d.length() > 200) {
            throw ErrorNegocio.validacion("La descripción tiene máximo 200 caracteres.");
        }
        Perfil.deId(id).orElseThrow(() -> ErrorNegocio.noEncontrado("El perfil no existe."));
        Map<Integer, String> antes = usuarios.nombresPerfiles();
        if (antes.entrySet().stream().anyMatch(e -> e.getKey() != id && e.getValue().equalsIgnoreCase(n))) {
            throw ErrorNegocio.validacion("Ya existe otro perfil con ese nombre.");
        }
        usuarios.renombrarPerfil(id, n, d);
        bitacora.registrar("perfil_editado", "Perfil " + id + ": nombre «" + Bitacora.cortar(antes.getOrDefault(id, ""), 60) + "» → «" + n + "»",
                quien.id(), quien.usuario());
    }

    /**
     * Teléfono móvil colombiano en E.164 (+57 y 10 dígitos que empiezan por 3): lo usan los SMS de las alertas críticas (spec
     * exportacion-alertas, punto 10). Vacío se admite; un valor igual al guardado se conserva tal cual para no impedir editar a los
     * usuarios registrados antes de la validación.
     */
    static String telefonoMovil(String escrito, String actual) {
        if (escrito == null || escrito.equals(actual)) {
            return escrito;
        }
        return co.com.agsamericas.digsa.reglas.Alertas.normalizarTelefono(escrito).orElseThrow(() -> ErrorNegocio.validacion(
                "El teléfono debe ser un móvil colombiano: +57 y 10 dígitos que empiezan por 3 (p. ej. +57 300 000 0000). Se usa para los SMS de las alertas críticas."));
    }

    private static Map<String, Object> foto(String nombre, String documento, String telefono, String usuario, String correo,
                                            Set<Perfil> perfiles, String fuerza, Long punto, Long regional) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nombre", nombre);
        m.put("documento", documento);
        m.put("telefono", telefono);
        m.put("usuario", usuario);
        m.put("correo", correo);
        m.put("perfiles", perfiles.stream().map(p -> String.valueOf(p.id())).toList());
        m.put("fuerza", fuerza);
        m.put("punto", punto);
        m.put("regional", regional);
        return m;
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

    /** Establecimientos (ESM activos) y regionales activas para el alcance de los perfiles ESM y OPL regional. */
    public Map<String, Object> opcionesAlcance() {
        return Map.of(
                "puntos", jdbc.sql("SELECT id, nombre, sigla, fuerza FROM puntos WHERE activo AND unidad = 'ESM' ORDER BY fuerza, nombre").query().listOfRows(),
                "regionales", jdbc.sql("SELECT id, nombre FROM par_regionales WHERE activo ORDER BY nombre").query().listOfRows());
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
