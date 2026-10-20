package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Perfil;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Primer administrador, solo por línea de comandos (no hay contraseñas en el repositorio):
 * {@code java -jar digsa.jar --digsa.crear-admin.correo=correo@empresa --digsa.crear-admin.nombre="Nombre Completo" [--digsa.crear-admin.usuario=usuario]}.
 * Crea el usuario con una contraseña aleatoria que nadie conoce, le envía el enlace de un solo uso y termina (código 0; 2 si el correo
 * no salió; 1 si los datos no son válidos). Si el correo ya existe, le agrega el perfil Administrador.
 */
@Component
@ConditionalOnProperty(prefix = "digsa.crear-admin", name = "correo")
public class CrearAdministrador implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(CrearAdministrador.class);

    private final RepositorioUsuarios usuarios;
    private final ServicioClaves claves;
    private final RegistroBitacora bitacora;
    private final TransactionTemplate tx;
    private final ConfigurableApplicationContext contexto;
    private final Clock reloj;

    public CrearAdministrador(RepositorioUsuarios usuarios, ServicioClaves claves, RegistroBitacora bitacora, TransactionTemplate tx,
                              ConfigurableApplicationContext contexto, Clock reloj) {
        this.usuarios = usuarios;
        this.claves = claves;
        this.bitacora = bitacora;
        this.tx = tx;
        this.contexto = contexto;
        this.reloj = reloj;
    }

    /** Último código de salida (las pruebas lo leen con digsa.crear-admin.salir=false). */
    private volatile int codigo = -1;

    public int codigo() {
        return codigo;
    }

    @Override
    public void run(ApplicationArguments args) {
        var env = contexto.getEnvironment();
        codigo = ejecutar(env.getProperty("digsa.crear-admin.correo"), env.getProperty("digsa.crear-admin.nombre", ""),
                env.getProperty("digsa.crear-admin.usuario", ""));
        if (contexto.getEnvironment().getProperty("digsa.crear-admin.salir", Boolean.class, true)) {
            terminar(codigo);
        }
    }

    /** Crea o completa el administrador. @return 0 listo; 1 datos no válidos; 2 el correo no salió */
    int ejecutar(String correoEscrito, String nombreEscrito, String usuarioEscrito) {
        String correo = Correos.normalizar(correoEscrito);
        String nombre = nombreEscrito == null ? "" : nombreEscrito.strip();
        String usuario = usuarioEscrito == null ? "" : usuarioEscrito.strip().toLowerCase();
        if (!Correos.valido(correo) || nombre.length() < 3 || (!usuario.isEmpty() && !usuario.matches("^[a-z0-9._-]{3,50}$"))) {
            LOG.error("Uso: --digsa.crear-admin.correo=correo --digsa.crear-admin.nombre=\"Nombre Completo\" [--digsa.crear-admin.usuario=usuario]");
            return 1;
        }
        String elegido = usuario.isEmpty() ? usuarioLibre(correo) : usuario;
        // Primero se confirma el usuario con su perfil; el correo sale después del commit
        Long id = tx.execute(t -> {
            long uid = usuarios.porUsuarioOCorreo(correo).filter(u -> u.correo().equals(correo)).map(Usuario::id)
                    .orElseGet(() -> usuarios.crear(nombre, null, null, elegido, correo, claves.claveAleatoriaCodificada(), null, null, null, reloj.instant()));
            usuarios.agregarPerfil(uid, Perfil.ADMINISTRADOR);
            usuarios.cambiarEstado(uid, true, reloj.instant()); // un administrador inactivo no podría ingresar
            return uid;
        });
        Usuario u = usuarios.porId(id).orElseThrow();
        bitacora.registrar("admin_creado", "Administrador " + u.usuario() + " creado por línea de comandos", null, "cli");
        ServicioClaves.EnvioEnlace e = claves.enviarEnlace(u, "Bienvenido a la Suite DIGSA",
                "Se creó su usuario administrador en la Suite DIGSA. Defina su contraseña.", false);
        if (e.enlaceLocal() != null) {
            LOG.warn("Correo de desarrollo (no sale). Enlace para definir la contraseña: {}", e.enlaceLocal());
        }
        if (!e.correoOk()) {
            LOG.error("Administrador {} listo, pero el correo NO salió: {}. Use «Restablecer contraseña» cuando el correo funcione.", u.usuario(), e.error());
        } else {
            LOG.info("Administrador listo ({}). Correo {}.", u.usuario(), e.enlaceLocal() != null ? "guardado en archivo (no sale: entorno local)" : "enviado");
        }
        return e.correoOk() ? 0 : 2;
    }

    /** Usuario libre a partir del correo: solo a-z0-9._-, máximo 45, mínimo 3 (relleno con ceros) y sufijo 2, 3… si existe. */
    String usuarioLibre(String correo) {
        String base = correo.substring(0, correo.indexOf('@')).toLowerCase().replaceAll("[^a-z0-9._-]", "");
        base = base.length() > 45 ? base.substring(0, 45) : base;
        base = (base + "000").substring(0, Math.max(3, base.length()));
        String candidato = base;
        for (int i = 2; usuarios.existeUsuario(candidato, 0); i++) {
            candidato = base + i;
        }
        return candidato;
    }

    private void terminar(int codigo) {
        System.exit(SpringApplication.exit(contexto, () -> codigo));
    }
}
