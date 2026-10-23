package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa;
import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Claves;
import co.com.agsamericas.digsa.reglas.Huellas;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contraseñas: enlace de un solo uso (60 minutos; en la base solo su SHA-256), definir la contraseña, "olvidé mi contraseña" y
 * restablecer por el administrador. Mismo comportamiento que el PHP de referencia (decisión del usuario, 2026-10-19).
 */
@Service
public class ServicioClaves {

    /** Resultado de enviar un enlace: si el correo salió y, solo en local sin correo real, el enlace para mostrarlo. */
    public record EnvioEnlace(boolean correoOk, String error, String enlaceLocal) {
    }

    /** Enlaces de recuperación de un mismo usuario: como máximo uno cada 5 minutos (duda 17.16). */
    static final int MINUTOS_ENTRE_ENLACES = 5;

    private static final Logger LOG = LoggerFactory.getLogger(ServicioClaves.class);

    private final RepositorioUsuarios usuarios;
    private final Correo correo;
    private final PasswordEncoder codificador;
    private final RegistroBitacora bitacora;
    private final ConfiguracionDigsa cfg;
    private final Clock reloj;
    private final ApplicationEventPublisher eventos;
    private final CsrfTokenRepository csrf;
    /** "Olvidé mi contraseña" fuera de la petición; cola acotada: una avalancha de solicitudes no agota la memoria ni el SMTP. */
    private final ThreadPoolExecutor hilosOlvido = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100),
            Thread.ofPlatform().name("digsa-olvido").daemon(true).factory());

    public ServicioClaves(RepositorioUsuarios usuarios, Correo correo, PasswordEncoder codificador, RegistroBitacora bitacora,
                          ConfiguracionDigsa cfg, Clock reloj, ApplicationEventPublisher eventos, CsrfTokenRepository csrf) {
        this.eventos = eventos;
        this.csrf = csrf;
        this.usuarios = usuarios;
        this.correo = correo;
        this.codificador = codificador;
        this.bitacora = bitacora;
        this.cfg = cfg;
        this.reloj = reloj;
    }

    /** Contraseña aleatoria que nadie conoce, para los usuarios recién creados (48 caracteres hexadecimales). */
    public String claveAleatoriaCodificada() {
        return codificador.encode(Huellas.secreto(24));
    }

    public String codificar(String clave) {
        return codificador.encode(clave);
    }

    /**
     * Genera un enlace nuevo (reemplaza al anterior) y lo envía. {@code exigirCambio} marca el cambio obligatorio (restablecer del
     * administrador); "olvidé mi contraseña" no lo marca ni cierra sesiones. Sin transacción: el token queda guardado (una sentencia)
     * antes de que salga el correo, y el envío no retiene una conexión.
     */
    public EnvioEnlace enviarEnlace(Usuario u, String asunto, String motivo, boolean exigirCambio) {
        Instant ahora = reloj.instant();
        String token = Huellas.secreto(32);
        usuarios.guardarToken(u.id(), Huellas.sha256(token), ahora.plus(Duration.ofMinutes(Claves.MINUTOS_ENLACE)), exigirCambio, ahora);
        String enlace = cfg.enlace("definir-clave?tkn=" + token);
        Correo.Resultado r = correo.enviar(Correo.Mensaje.simple(u.correo(), asunto,
                PlantillasCorreo.enlaceClave(u.nombre(), motivo, enlace, Claves.MINUTOS_ENLACE)));
        return new EnvioEnlace(r.ok(), r.error(), !correo.real() && cfg.esLocal() ? enlace : null);
    }

    /** Usuario del enlace, si sigue vigente. */
    public Optional<Usuario> usuarioDelEnlace(String token) {
        if (token == null || !token.matches("^[a-f0-9]{64}$")) {
            return Optional.empty();
        }
        Instant ahora = reloj.instant();
        return usuarios.porTokenHash(Huellas.sha256(token))
                .filter(Usuario::activo)
                .filter(u -> Claves.enlaceVigente(u.tokenHash(), u.tokenVence(), token, ahora));
    }

    /** Define la contraseña con el enlace. Consume el enlace y cierra la sesión que hubiera abierta (sesión única). */
    @Transactional
    public void definir(String token, String clave, String confirmacion) {
        Usuario u = usuarioDelEnlace(token).orElseThrow(() -> ErrorNegocio.validacion(
                "El enlace no es válido o ya venció. Pida uno nuevo con «¿Olvidó su contraseña?»."));
        Claves.problema(clave).ifPresent(p -> {
            throw ErrorNegocio.validacion(p);
        });
        if (!clave.equals(confirmacion)) {
            throw ErrorNegocio.validacion("Las contraseñas no coinciden.");
        }
        if (u.claveDefinida() && codificador.matches(clave, u.claveHash())) {
            throw ErrorNegocio.validacion("La nueva contraseña debe ser diferente a la actual.");
        }
        Instant ahora = reloj.instant();
        boolean teniaSesion = u.sesionHuella() != null;
        boolean ok = usuarios.definirClave(u.id(), u.tokenHash(), codificador.encode(clave),
                ahora.plus(Duration.ofDays(cfg.diasVigenciaClave())), ahora);
        if (!ok) {
            throw ErrorNegocio.validacion("El enlace ya se usó. Pida uno nuevo con «¿Olvidó su contraseña?».");
        }
        bitacora.registrar("clave_cambio", "Contraseña definida por enlace del usuario " + u.usuario(), u.id(), u.usuario());
        if (teniaSesion) {
            // Decisión 7.3: la sesión abierta se cierra al cambiar la contraseña, y esa salida queda en la bitácora
            bitacora.escribir(co.com.agsamericas.digsa.reglas.Bitacora.Operacion.SALIDA, "sesion_cerrada", "sesion",
                    "Sesión cerrada por cambio de contraseña", u.id(), u.usuario(), null);
        }
    }

    /**
     * "Olvidé mi contraseña": responde siempre lo mismo y en el mismo tiempo para no revelar quién está registrado (duda 17.16): la
     * búsqueda y el correo van en un hilo aparte. Nunca devuelve el enlace (ni en local): solo llega al correo. Un mismo usuario recibe
     * como máximo un enlace cada {@link #MINUTOS_ENTRE_ENLACES} minutos. No pasa por la bandeja de salida: allí el enlace quedaría en claro.
     */
    public void olvide(String valor) {
        String v = valor == null ? "" : valor.strip().toLowerCase();
        try {
            hilosOlvido.execute(() -> enviarRecuperacion(v));
        } catch (RejectedExecutionException e) {
            // Muchas solicitudes a la vez: se descarta (la respuesta es la misma) y queda en la bitácora, sin lo escrito (puede ser un correo)
            LOG.warn("[claves] Solicitud de recuperación descartada: cola llena ({} en espera)", hilosOlvido.getQueue().size());
            bitacora.escribir(Bitacora.Operacion.ACTUALIZACION, "clave_olvido", "sesion", "Solicitud de recuperación descartada: demasiadas a la vez",
                    null, null, null);
        }
    }

    private void enviarRecuperacion(String valor) {
        try {
            Optional<Usuario> u = usuarios.porUsuarioOCorreo(valor).filter(Usuario::activo);
            if (u.isEmpty()) {
                return;
            }
            Instant ahora = reloj.instant();
            Instant enviado = u.get().tokenVence() == null ? null : u.get().tokenVence().minus(Duration.ofMinutes(Claves.MINUTOS_ENLACE));
            if (enviado != null && enviado.plus(Duration.ofMinutes(MINUTOS_ENTRE_ENLACES)).isAfter(ahora)) {
                bitacora.escribir(Bitacora.Operacion.ACTUALIZACION, "clave_olvido", "sesion", "Recuperación pedida de nuevo para " + u.get().usuario()
                        + ": no se envía otro enlace antes de " + MINUTOS_ENTRE_ENLACES + " minutos", u.get().id(), u.get().usuario(), null);
                return;
            }
            EnvioEnlace e = enviarEnlace(u.get(), "Recuperación de contraseña",
                    "Recibimos una solicitud para definir una contraseña nueva en la Suite DIGSA.", false);
            if (!e.correoOk()) {
                usuarios.anularEnlace(u.get().id()); // un enlace que no salió no cuenta para el tope: el siguiente pedido lo intenta de nuevo
            }
            bitacora.escribir(Bitacora.Operacion.ACTUALIZACION, "clave_olvido", "sesion", "Enlace de recuperación para " + u.get().usuario()
                    + (e.correoOk() ? "" : " (el correo NO salió)"), u.get().id(), u.get().usuario(), null);
        } catch (RuntimeException e) {
            LOG.error("[claves] No se pudo atender la recuperación de contraseña", e);
        }
    }

    /**
     * El usuario con sesión cambia su contraseña («Cambiar mi contraseña»), sin correo. Exige la actual: una actual errada cuenta como
     * intento fallido y, al quinto, el usuario se bloquea y la sesión se cierra. La sesión en curso sigue abierta, con identificador y
     * token CSRF nuevos (si alguien tenía la cookie anterior, deja de servirle).
     */
    public void cambiarPropia(long usuarioId, String actual, String nueva, String confirmacion, HttpServletRequest req, HttpServletResponse res) {
        Instant ahora = reloj.instant();
        Usuario u = usuarios.porId(usuarioId).filter(Usuario::activo).orElseThrow(() -> ErrorNegocio.noEncontrado("El usuario no existe."));
        if (u.bloqueado(ahora)) {
            // Bloqueado por intentos (también desde el ingreso): sin intentos ilimitados aquí ni desbloqueo por acertar
            cerrarPorBloqueo(u, "Sesión cerrada: el usuario está bloqueado por intentos fallidos", req);
        }
        if (actual == null || !codificador.matches(actual, u.claveHash())) {
            boolean bloqueo = usuarios.registrarFallo(u.id(), ahora, ServicioIngreso.INTENTOS_MAXIMOS, ServicioIngreso.MINUTOS_BLOQUEO);
            if (bloqueo) {
                eventos.publishEvent(new CuentaBloqueada(ahora, ahora.plusSeconds(ServicioIngreso.MINUTOS_BLOQUEO * 60L)));
                cerrarPorBloqueo(u, "Sesión cerrada: " + ServicioIngreso.INTENTOS_MAXIMOS + " contraseñas actuales erradas al cambiarla", req);
            }
            bitacora.registrar("clave_cambio_fallido", "Contraseña actual errada al cambiarla", u.id(), u.usuario());
            throw ErrorNegocio.validacion("La contraseña actual no es correcta.");
        }
        Claves.problema(nueva).ifPresent(p -> {
            throw ErrorNegocio.validacion(p);
        });
        if (!nueva.equals(confirmacion)) {
            throw ErrorNegocio.validacion("Las contraseñas no coinciden.");
        }
        if (codificador.matches(nueva, u.claveHash())) {
            throw ErrorNegocio.validacion("La nueva contraseña debe ser diferente a la actual.");
        }
        if (!usuarios.cambiarClave(u.id(), u.claveHash(), codificador.encode(nueva), ahora.plus(Duration.ofDays(cfg.diasVigenciaClave())), ahora)) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "La contraseña cambió mientras tanto. Recargue e intente de nuevo.");
        }
        HttpSession s = req.getSession(false);
        if (s != null) {
            // La sesión en curso empezó antes del cambio: sin esto, el filtro la cerraría como "contraseña cambiada en otro lado"
            s.setAttribute(AtributosSesion.INICIO, ahora.toEpochMilli() + 1);
            req.changeSessionId();
            csrf.saveToken(csrf.generateToken(req), req, res);
        }
        bitacora.registrar("clave_cambio", "Contraseña cambiada por el usuario " + u.usuario() + " desde su sesión", u.id(), u.usuario());
    }

    private void cerrarPorBloqueo(Usuario u, String motivo, HttpServletRequest req) {
        usuarios.cerrarCualquierSesion(u.id());
        bitacora.escribir(Bitacora.Operacion.SALIDA, "sesion_cerrada", "sesion", motivo, u.id(), u.usuario(), req.getRemoteAddr());
        HttpSession s = req.getSession(false);
        if (s != null) {
            s.invalidate();
        }
        throw new ErrorNegocio(HttpStatus.UNAUTHORIZED, "Su usuario está bloqueado " + ServicioIngreso.MINUTOS_BLOQUEO
                + " minutos por intentos fallidos. Vuelva a ingresar más tarde.", Map.of("motivo", "bloqueado"));
    }

    /** Al apagar, espera unos segundos a que salgan las recuperaciones en cola; las que queden se registran como perdidas. */
    @PreDestroy
    void detener() throws InterruptedException {
        hilosOlvido.shutdown();
        if (!hilosOlvido.awaitTermination(10, TimeUnit.SECONDS)) {
            LOG.error("[claves] Al apagar quedaron {} recuperaciones de contraseña sin atender", hilosOlvido.getQueue().size());
        }
    }
}
