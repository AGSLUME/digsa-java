package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa;
import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Claves;
import co.com.agsamericas.digsa.reglas.Huellas;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
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

    private final RepositorioUsuarios usuarios;
    private final Correo correo;
    private final PasswordEncoder codificador;
    private final RegistroBitacora bitacora;
    private final ConfiguracionDigsa cfg;
    private final Clock reloj;

    public ServicioClaves(RepositorioUsuarios usuarios, Correo correo, PasswordEncoder codificador, RegistroBitacora bitacora,
                          ConfiguracionDigsa cfg, Clock reloj) {
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
     * "Olvidé mi contraseña": responde siempre lo mismo para no revelar quién está registrado. Nunca devuelve el enlace (ni en local):
     * solo llega al correo.
     */
    public void olvide(String valor) {
        String v = valor == null ? "" : valor.strip().toLowerCase();
        Optional<Usuario> u = usuarios.porUsuarioOCorreo(v).filter(Usuario::activo);
        if (u.isEmpty()) {
            return;
        }
        EnvioEnlace e = enviarEnlace(u.get(), "Recuperación de contraseña",
                "Recibimos una solicitud para definir una contraseña nueva en la Suite DIGSA.", false);
        bitacora.registrar("clave_olvido", "Enlace de recuperación para " + u.get().usuario() + (e.correoOk() ? "" : " (el correo NO salió)"),
                u.get().id(), u.get().usuario());
    }
}
