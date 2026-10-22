package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa;
import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.reglas.Mfa;
import co.com.agsamericas.digsa.reglas.Sesion;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingreso en dos pasos (Anexo 5, 3.1 y 3.2; MFA de la respuesta GMI P71 c): usuario y contraseña con captcha y bloqueo tras 5
 * fallos; luego el código de 6 dígitos que llega al correo. Solo al superar el código se crea la sesión. Si el
 * usuario ya tiene una sesión abierta y activa, el ingreso nuevo se rechaza (decisión 7.1 del 2026-10-20).
 */
@Service
public class ServicioIngreso {

    public static final String MENSAJE_GENERICO = "Usuario o contraseña incorrectos. Después de 5 intentos fallidos el usuario se bloquea 15 minutos.";
    static final int INTENTOS_MAXIMOS = 5;
    static final int MINUTOS_BLOQUEO = 15;

    /** Respuesta del primer paso. */
    public record Paso(String paso, String mensaje, String correo, String enlaceLocal) {
    }

    private record CodigoMfa(long id, String huella, Instant vence, int intentos, boolean usado) {
    }

    private final RepositorioUsuarios usuarios;
    private final ServicioClaves claves;
    private final Captcha captcha;
    private final Correo correo;
    private final PasswordEncoder codificador;
    private final RegistroBitacora bitacora;
    private final SecurityContextRepository contextos;
    private final ConfiguracionDigsa cfg;
    private final JdbcClient jdbc;
    private final Clock reloj;
    /** Hash de relleno: el tiempo de respuesta no revela si el usuario existe. */
    private final String hashRelleno;
    /** Códigos del segundo factor por hora y usuario (digsa.mfa.codigos-por-hora; 5 por defecto). */
    private final int codigosPorHora;
    /** Publica el bloqueo de una cuenta (alerta de bloqueo masivo). */
    private final org.springframework.context.ApplicationEventPublisher eventos;

    public ServicioIngreso(RepositorioUsuarios usuarios, ServicioClaves claves, Captcha captcha, Correo correo, PasswordEncoder codificador,
                           RegistroBitacora bitacora, SecurityContextRepository contextos, ConfiguracionDigsa cfg, JdbcClient jdbc, Clock reloj,
                           @org.springframework.beans.factory.annotation.Value("${digsa.mfa.codigos-por-hora:5}") int codigosPorHora,
                           org.springframework.context.ApplicationEventPublisher eventos) {
        this.eventos = eventos;
        this.codigosPorHora = codigosPorHora > 0 ? codigosPorHora : Mfa.CODIGOS_POR_HORA;
        this.usuarios = usuarios;
        this.claves = claves;
        this.captcha = captcha;
        this.correo = correo;
        this.codificador = codificador;
        this.bitacora = bitacora;
        this.contextos = contextos;
        this.cfg = cfg;
        this.jdbc = jdbc;
        this.reloj = reloj;
        this.hashRelleno = codificador.encode(Huellas.secreto(16));
    }

    /**
     * Paso 1: usuario, contraseña y captcha. Si todo está bien, envía el código al correo. Sin transacción a propósito: cada sentencia
     * es atómica y así el captcha y el correo (llamadas externas) no retienen una conexión ni bloquean la fila del usuario.
     */
    public Paso ingresar(String usuarioEscrito, String clave, String respuestaCaptcha, HttpServletRequest req) {
        String escrito = usuarioEscrito == null ? "" : usuarioEscrito.strip().toLowerCase();
        String ip = req.getRemoteAddr();
        if (!captcha.superado(respuestaCaptcha, ip)) {
            bitacora.escribir(Bitacora.Operacion.INGRESO, "captcha_fallido", "sesion", "Captcha no superado", null,
                    Bitacora.enmascararUsuario(escrito), ip);
            throw ErrorNegocio.validacion("Por favor complete la verificación de seguridad (captcha).");
        }
        Instant ahora = reloj.instant();
        Optional<Usuario> encontrado = usuarios.porUsuario(escrito);
        String motivo = null;
        if (encontrado.isEmpty()) {
            codificador.matches(clave == null ? "" : clave, hashRelleno);
            motivo = "usuario inexistente";
        } else if (!encontrado.get().activo()) {
            codificador.matches(clave == null ? "" : clave, hashRelleno);
            motivo = "usuario inactivo";
        } else if (encontrado.get().bloqueado(ahora)) {
            codificador.matches(clave == null ? "" : clave, hashRelleno);
            motivo = "Intento con el usuario bloqueado";
        } else if (clave == null || !codificador.matches(clave, encontrado.get().claveHash())) {
            if (usuarios.registrarFallo(encontrado.get().id(), ahora, INTENTOS_MAXIMOS, MINUTOS_BLOQUEO)) {
                eventos.publishEvent(new CuentaBloqueada(ahora, ahora.plusSeconds(MINUTOS_BLOQUEO * 60L)));
            }
            motivo = "contraseña incorrecta";
        } else if (encontrado.get().claveInicial() && encontrado.get().claveVence() != null && encontrado.get().claveVence().isBefore(ahora)) {
            motivo = "contraseña inicial vencida (" + RepositorioUsuarios.HORAS_CLAVE_INICIAL + " h): el administrador debe asignar otra";
        }
        if (motivo != null) {
            Long id = encontrado.map(Usuario::id).orElse(null);
            String quien = encontrado.map(Usuario::usuario).orElse(Bitacora.enmascararUsuario(escrito));
            bitacora.escribir(Bitacora.Operacion.INGRESO, "login_fallido", "sesion", "Ingreso fallido: " + motivo, id, quien, ip);
            throw new ErrorNegocio(HttpStatus.UNAUTHORIZED, MENSAJE_GENERICO);
        }
        Usuario u = encontrado.get();
        usuarios.reiniciarIntentos(u.id(), ahora);
        verificarSesionLibre(u, ahora, ip);
        if (u.claveInicial() && u.debeCambiarClave()) {
            // Contraseña inicial asignada por el administrador: se cambia aquí mismo, sin ir al correo (prueba 2 y condición 12)
            HttpSession s = req.getSession(true);
            s.setAttribute(AtributosSesion.CAMBIO_USUARIO, u.id());
            s.setAttribute(AtributosSesion.CAMBIO_DESDE, ahora.toEpochMilli());
            return new Paso("cambiar_clave", "Es su primer ingreso con la contraseña que le asignó el administrador: defina una contraseña nueva.",
                    Correos.enmascarar(u.correo()), null);
        }
        if (u.cambioPendiente(ahora)) {
            String m = !u.claveDefinida() ? "Es su primer ingreso: debe definir su contraseña." : "Debe actualizar su contraseña.";
            ServicioClaves.EnvioEnlace e = claves.enviarEnlace(u, "Defina su contraseña", m, false);
            return new Paso("definir_clave", m + (e.correoOk() ? " Le enviamos un enlace a su correo." : " No se pudo enviar el correo: avise al administrador."),
                    Correos.enmascarar(u.correo()), e.enlaceLocal());
        }
        enviarCodigo(u, req);
        HttpSession s = req.getSession(true);
        s.setAttribute(AtributosSesion.MFA_USUARIO, u.id());
        s.setAttribute(AtributosSesion.MFA_DESDE, ahora.toEpochMilli());
        return new Paso("codigo", "Le enviamos un código de 6 dígitos a su correo.", Correos.enmascarar(u.correo()), null);
    }

    /** Reenvía el código al usuario que ya superó la contraseña (con el tope por hora). */
    public Paso reenviar(HttpServletRequest req) {
        Usuario u = usuarioPendiente(req);
        enviarCodigo(u, req);
        return new Paso("codigo", "Le enviamos un código nuevo.", Correos.enmascarar(u.correo()), null);
    }

    /**
     * Paso 2: el código. Si es correcto, la sesión queda iniciada (si no hay otra abierta: decisión 7.1). El código se consume
     * con un UPDATE condicionado (sin usar, con intentos y vigente): dos verificaciones a la vez no lo usan dos veces.
     */
    public UsuarioSesion verificar(String codigo, HttpServletRequest req, HttpServletResponse res) {
        Usuario u = usuarioPendiente(req);
        Instant ahora = reloj.instant();
        verificarSesionLibre(u, ahora, req.getRemoteAddr()); // dos navegadores pudieron pasar el primer paso a la vez
        CodigoMfa c = jdbc.sql("""
                SELECT id, codigo_hash, vence, intentos, usado FROM mfa_codigos
                WHERE usuario_id = :u ORDER BY creado DESC, id DESC LIMIT 1""")
                .param("u", u.id())
                .query((rs, n) -> new CodigoMfa(rs.getLong("id"), rs.getString("codigo_hash"), rs.getTimestamp("vence").toInstant(),
                        rs.getInt("intentos"), rs.getTimestamp("usado") != null))
                .optional()
                .orElseThrow(() -> ErrorNegocio.validacion("No hay un código vigente. Pida un código nuevo."));
        if (!Mfa.usable(c.vence(), c.intentos(), c.usado(), ahora) || !Mfa.codigoValido(codigo, c.huella())) {
            jdbc.sql("UPDATE mfa_codigos SET intentos = intentos + 1 WHERE id = :id").param("id", c.id()).update();
            bitacora.escribir(Bitacora.Operacion.INGRESO, "mfa_fallido", "sesion", "Código de verificación incorrecto o vencido", u.id(),
                    u.usuario(), req.getRemoteAddr());
            boolean agotado = c.intentos() + 1 >= Mfa.INTENTOS || ahora.isAfter(c.vence()) || c.usado();
            throw ErrorNegocio.validacion(agotado ? "El código venció o superó los intentos. Pida un código nuevo."
                    : "El código no es correcto. Revise el último correo que le enviamos.");
        }
        int consumido = jdbc.sql("UPDATE mfa_codigos SET usado = :ahora WHERE id = :id AND usado IS NULL AND intentos < :max AND vence >= :ahora")
                .param("ahora", ahora.atOffset(ZoneOffset.UTC)).param("id", c.id()).param("max", Mfa.INTENTOS).update();
        if (consumido != 1) {
            throw ErrorNegocio.validacion("El código venció o ya se usó. Pida un código nuevo.");
        }

        // Sesión nueva: identificador nuevo (fijación de sesión), marca propia y huella en la base (sesión única)
        HttpSession anterior = req.getSession(false);
        if (anterior != null) {
            anterior.invalidate();
        }
        HttpSession s = req.getSession(true);
        String marca = Huellas.secreto(32);
        s.setAttribute(AtributosSesion.MARCA, marca);
        s.setAttribute(AtributosSesion.INICIO, ahora.toEpochMilli());
        s.setAttribute(AtributosSesion.ULTIMO, ahora.toEpochMilli());
        s.setMaxInactiveInterval((cfg.minutosSesion() + 5) * 60);
        if (!usuarios.iniciarSesion(u.id(), Sesion.huella(marca), ahora, cfg.minutosSesion())) {
            s.invalidate(); // otra sesión del usuario se abrió mientras tanto
            verificarSesionLibre(usuarios.porId(u.id()).orElseThrow(), ahora, req.getRemoteAddr());
            throw new ErrorNegocio(HttpStatus.CONFLICT, "Su usuario ya tiene una sesión abierta en otro equipo o navegador.",
                    Map.of("motivo", "sesion_abierta"));
        }

        UsuarioSesion us = new UsuarioSesion(u.id(), u.usuario(), u.nombre());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(UsuarioSesion.autenticacion(us, u.perfiles()));
        SecurityContextHolder.setContext(ctx);
        contextos.saveContext(ctx, req, res);
        bitacora.escribir(Bitacora.Operacion.INGRESO, "login", "sesion", "Inicio de sesión", u.id(), u.usuario(), req.getRemoteAddr());
        return us;
    }

    /**
     * Cambio de la contraseña inicial (asignada por el administrador) dentro de la aplicación. Después sigue el código al correo.
     */
    public Paso cambiarClaveInicial(String nueva, String confirmacion, HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        Object id = s == null ? null : s.getAttribute(AtributosSesion.CAMBIO_USUARIO);
        Object desde = s == null ? null : s.getAttribute(AtributosSesion.CAMBIO_DESDE);
        Instant ahora = reloj.instant();
        if (!(id instanceof Long uid) || !(desde instanceof Long d) || Instant.ofEpochMilli(d).plusSeconds(Mfa.MINUTOS_VIGENCIA * 60L).isBefore(ahora)) {
            throw new ErrorNegocio(HttpStatus.UNAUTHORIZED, "La verificación venció. Ingrese de nuevo con su usuario y contraseña.",
                    Map.of("motivo", "mfa_vencido"));
        }
        Usuario u = usuarios.porId(uid).filter(Usuario::activo).filter(x -> x.claveInicial()).orElseThrow(
                () -> new ErrorNegocio(HttpStatus.UNAUTHORIZED, MENSAJE_GENERICO));
        co.com.agsamericas.digsa.reglas.Claves.problema(nueva).ifPresent(p -> {
            throw ErrorNegocio.validacion(p);
        });
        if (!nueva.equals(confirmacion)) {
            throw ErrorNegocio.validacion("Las contraseñas no coinciden.");
        }
        if (codificador.matches(nueva, u.claveHash())) {
            throw ErrorNegocio.validacion("La nueva contraseña debe ser diferente a la que le asignó el administrador.");
        }
        if (!usuarios.cambiarClaveInicial(u.id(), codificador.encode(nueva), ahora.plus(java.time.Duration.ofDays(cfg.diasVigenciaClave())), ahora)) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "La contraseña ya se cambió (¿envió el formulario dos veces?). Ingrese con la contraseña nueva.");
        }
        bitacora.escribir(Bitacora.Operacion.ACTUALIZACION, "clave_cambio", "sesion", "Contraseña inicial cambiada por el usuario " + u.usuario(),
                u.id(), u.usuario(), req.getRemoteAddr());
        s.removeAttribute(AtributosSesion.CAMBIO_USUARIO);
        s.removeAttribute(AtributosSesion.CAMBIO_DESDE);
        enviarCodigo(u, req);
        s.setAttribute(AtributosSesion.MFA_USUARIO, u.id());
        s.setAttribute(AtributosSesion.MFA_DESDE, ahora.toEpochMilli());
        return new Paso("codigo", "Contraseña actualizada. Le enviamos un código de 6 dígitos a su correo.", Correos.enmascarar(u.correo()), null);
    }

    /** Decisión 7.1: con una sesión abierta y activa, el segundo ingreso se rechaza (no reemplaza a la anterior). */
    private void verificarSesionLibre(Usuario u, Instant ahora, String ip) {
        if (Sesion.bloqueaNuevoIngreso(u.sesionHuella(), u.sesionUltimo(), ahora, cfg.minutosSesion())) {
            bitacora.escribir(Bitacora.Operacion.INGRESO, "login_fallido", "sesion", "Ingreso rechazado: el usuario ya tiene una sesión abierta",
                    u.id(), u.usuario(), ip);
            throw new ErrorNegocio(HttpStatus.CONFLICT, "Su usuario ya tiene una sesión abierta en otro equipo o navegador. Ciérrela con «Salir», "
                    + "espere " + cfg.minutosSesion() + " minutos sin actividad o pida al administrador que la cierre. Solo se permite una sesión a la vez.",
                    Map.of("motivo", "sesion_abierta"));
        }
    }

    /** «Salir»: borra la huella solo si era la de esta sesión y destruye la sesión. */
    public void salir(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        Optional<UsuarioSesion> u = UsuarioSesion.actual();
        if (s != null && u.isPresent()) {
            Object marca = s.getAttribute(AtributosSesion.MARCA);
            if (marca instanceof String m) {
                usuarios.cerrarSesion(u.get().id(), Sesion.huella(m));
            }
            bitacora.escribir(Bitacora.Operacion.SALIDA, "logout", "sesion", "Cierre de sesión", u.get().id(), u.get().usuario(), req.getRemoteAddr());
        }
        if (s != null) {
            s.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    private Usuario usuarioPendiente(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        Object id = s == null ? null : s.getAttribute(AtributosSesion.MFA_USUARIO);
        Object desde = s == null ? null : s.getAttribute(AtributosSesion.MFA_DESDE);
        Instant ahora = reloj.instant();
        if (!(id instanceof Long uid) || !(desde instanceof Long d) || Instant.ofEpochMilli(d).plusSeconds(Mfa.MINUTOS_VIGENCIA * 60L).isBefore(ahora)) {
            throw new ErrorNegocio(HttpStatus.UNAUTHORIZED, "La verificación venció. Ingrese de nuevo con su usuario y contraseña.",
                    Map.of("motivo", "mfa_vencido"));
        }
        return usuarios.porId(uid).filter(Usuario::activo).filter(u -> !u.bloqueado(ahora))
                .orElseThrow(() -> new ErrorNegocio(HttpStatus.UNAUTHORIZED, MENSAJE_GENERICO));
    }

    private void enviarCodigo(Usuario u, HttpServletRequest req) {
        Instant ahora = reloj.instant();
        long ultimaHora = jdbc.sql("SELECT count(*) FROM mfa_codigos WHERE usuario_id = :u AND creado > :desde")
                .param("u", u.id()).param("desde", ahora.minusSeconds(3600).atOffset(ZoneOffset.UTC)).query(Long.class).single();
        if (!Mfa.puedeEnviarOtro((int) ultimaHora, codigosPorHora)) {
            throw new ErrorNegocio(HttpStatus.TOO_MANY_REQUESTS, "Ya se enviaron " + codigosPorHora
                    + " códigos en la última hora. Espere un momento antes de pedir otro.");
        }
        String codigo = Huellas.codigoSeisDigitos();
        // Primero se registra (cuenta para el tope por hora) y después sale el correo: nunca un código enviado sin registro
        long id = jdbc.sql("""
                INSERT INTO mfa_codigos (usuario_id, codigo_hash, creado, vence, ip) VALUES (:u, :h, :creado, :vence, :ip) RETURNING id""")
                .param("u", u.id()).param("h", Huellas.sha256(codigo)).param("creado", ahora.atOffset(ZoneOffset.UTC))
                .param("vence", Mfa.vence(ahora).atOffset(ZoneOffset.UTC)).param("ip", req.getRemoteAddr()).query(Long.class).single();
        Correo.Resultado r = correo.enviar(Correo.Mensaje.simple(u.correo(), "Código de verificación · Suite DIGSA",
                PlantillasCorreo.codigoMfa(u.nombre(), codigo, Mfa.MINUTOS_VIGENCIA)));
        if (!r.ok()) {
            jdbc.sql("UPDATE mfa_codigos SET usado = :ahora WHERE id = :id").param("ahora", ahora.atOffset(ZoneOffset.UTC)).param("id", id).update();
            bitacora.escribir(Bitacora.Operacion.INGRESO, "mfa_enviado", "sesion", "El código de verificación NO salió: " + r.error(), u.id(),
                    u.usuario(), req.getRemoteAddr());
            throw new ErrorNegocio(HttpStatus.BAD_GATEWAY, "No se pudo enviar el código a su correo. Intente de nuevo o avise al administrador.");
        }
        bitacora.escribir(Bitacora.Operacion.INGRESO, "mfa_enviado", "sesion", "Código de verificación enviado", u.id(), u.usuario(),
                req.getRemoteAddr());
        if (!correo.real() && cfg.esLocal()) {
            req.setAttribute("digsa.mfa.codigoLocal", codigo);
        }
    }
}
