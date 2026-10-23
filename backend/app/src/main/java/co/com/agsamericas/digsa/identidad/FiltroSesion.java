package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Sesion;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Revalida la sesión en cada petición a /api (equivalente de Auth::revalidar del PHP): inactividad (10 min por defecto), usuario
 * inactivado, contraseña cambiada después de iniciar, sesión reemplazada por otro ingreso (sesión única) y cambio de contraseña
 * pendiente. Si todo está bien, recalcula perfiles y alcance desde la base: un perfil quitado deja de servir de inmediato.
 */
@Component
public class FiltroSesion extends OncePerRequestFilter {

    private final RepositorioUsuarios usuarios;
    private final ServicioClaves claves;
    private final RegistroBitacora bitacora;
    /** Estado de la sesión sin renovarla (aviso de inactividad). */
    static final String RUTA_ESTADO = "/api/sesion/estado";
    /** Segundos que le quedan a la sesión, para {@link ControladorSesion#estado}. */
    static final String ATR_RESTANTES = "digsa.sesion.restantes";

    private final ParametrosSeguridad parametros;
    private final ObjectMapper json;
    private final Clock reloj;

    public FiltroSesion(RepositorioUsuarios usuarios, ServicioClaves claves, RegistroBitacora bitacora, ParametrosSeguridad parametros,
                        ObjectMapper json, Clock reloj) {
        this.usuarios = usuarios;
        this.claves = claves;
        this.bitacora = bitacora;
        this.parametros = parametros;
        this.json = json;
        this.reloj = reloj;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        Optional<UsuarioSesion> actual = UsuarioSesion.actual();
        HttpSession s = req.getSession(false);
        if (actual.isEmpty() || s == null) {
            chain.doFilter(req, res);
            return;
        }
        UsuarioSesion us = actual.get();
        Instant ahora = reloj.instant();
        String marca = (String) s.getAttribute(AtributosSesion.MARCA);
        Long inicio = (Long) s.getAttribute(AtributosSesion.INICIO);
        Long ultimo = (Long) s.getAttribute(AtributosSesion.ULTIMO);
        String ip = req.getRemoteAddr();

        int minutos = parametros.minutosSesion();
        if (ultimo == null || Sesion.vencida(Instant.ofEpochMilli(ultimo), ahora, minutos)) {
            if (marca != null) {
                usuarios.cerrarSesion(us.id(), Sesion.huella(marca));
            }
            bitacora.escribir(Bitacora.Operacion.SALIDA, "sesion_expirada", "sesion", "Sesión cerrada por inactividad", us.id(), us.usuario(), ip);
            cerrar(req, res, s, "La sesión se cerró por inactividad. Vuelva a iniciar sesión.", "inactividad");
            return;
        }
        Optional<Usuario> enBase = usuarios.porId(us.id());
        if (enBase.isEmpty() || !enBase.get().activo()) {
            cerrar(req, res, s, "Su usuario está inactivo.", "inactivo"); // la salida ya quedó en la bitácora al inactivar (decisión 7.3)
            return;
        }
        Usuario u = enBase.get();
        // Los cambios de contraseña hechos fuera de esta sesión (enlace, contraseña inicial) también borran la huella. Si la huella sigue
        // siendo la de esta sesión, el cambio lo hizo ella misma («Cambiar mi contraseña»): una petición simultánea que aún no ve el
        // INICIO nuevo (Spring Session guarda al final de la petición) no debe cerrarla.
        if (u.claveActualizada() != null && inicio != null && u.claveActualizada().isAfter(Instant.ofEpochMilli(inicio))
                && !Sesion.esLaVigente(u.sesionHuella(), marca)) {
            cerrar(req, res, s, "La contraseña cambió. Inicie sesión de nuevo.", "clave");
            return;
        }
        if (!Sesion.esLaVigente(u.sesionHuella(), marca)) {
            // Sin huella: la cerró el administrador, «Salir» en otra pestaña, la inactivación o un cambio de contraseña, y quien la cerró ya
            // dejó la salida en la bitácora (duda 18.17: no se repite). Con otra huella: venció por inactividad y hubo otro ingreso.
            boolean cerradaPorOtro = u.sesionHuella() == null;
            if (!cerradaPorOtro) {
                bitacora.escribir(Bitacora.Operacion.SALIDA, "sesion_reemplazada", "sesion", "Sesión reemplazada por un ingreso posterior",
                        u.id(), u.usuario(), ip);
            }
            cerrar(req, res, s, cerradaPorOtro ? "Su sesión fue cerrada. Vuelva a iniciar sesión."
                    : "Su sesión se cerró porque su usuario ingresó desde otro equipo o navegador. Solo se permite una sesión a la vez.",
                    cerradaPorOtro ? "cerrada" : "reemplazada");
            return;
        }
        if (u.cambioPendiente(ahora)) {
            usuarios.cerrarSesion(u.id(), Sesion.huella(marca));
            if (u.claveVencida(ahora)) {
                // La conoce: la cambia al volver a ingresar, dentro de la aplicación (sin correo)
                bitacora.escribir(Bitacora.Operacion.SALIDA, "sesion_cerrada", "sesion", "Sesión cerrada: la contraseña venció", u.id(), u.usuario(), ip);
                cerrar(req, res, s, "Su contraseña venció. Ingrese de nuevo para cambiarla.", "clave_vencida");
                return;
            }
            ServicioClaves.EnvioEnlace e = claves.enviarEnlace(u, "Defina su contraseña", "Debe actualizar su contraseña.", false);
            bitacora.escribir(Bitacora.Operacion.SALIDA, "sesion_cerrada", "sesion", "Sesión cerrada: debe actualizar la contraseña", u.id(),
                    u.usuario(), ip);
            cerrar(req, res, s, "Debe actualizar su contraseña." + (e.correoOk() ? " Le enviamos un enlace a su correo." : ""), "cambio_clave");
            return;
        }
        if (s.getMaxInactiveInterval() != (minutos + 5) * 60) {
            s.setMaxInactiveInterval((minutos + 5) * 60); // el administrador cambió el TIME OUT con la sesión abierta
        }

        if (req.getRequestURI().equals(req.getContextPath() + RUTA_ESTADO)) {
            // Consulta del estado de la sesión desde el aviso de inactividad: no es actividad del usuario, no renueva el plazo
            req.setAttribute(ATR_RESTANTES, Math.max(0, minutos * 60L - (ahora.toEpochMilli() - ultimo) / 1000));
        } else {
            renovar(s, u, ahora);
        }
        req.setAttribute(RegistroBitacora.ATR_USUARIO_ID, u.id());
        req.setAttribute(RegistroBitacora.ATR_USUARIO, u.usuario());
        AbstractAuthenticationToken auth = (AbstractAuthenticationToken) UsuarioSesion.autenticacion(
                new UsuarioSesion(u.id(), u.usuario(), u.nombre()), u.perfiles()); // solo los directos: un rol no da la identidad de su base
        // Solo para esta petición (no se guarda en la sesión): un perfil o rol quitado deja de servir de inmediato
        auth.setDetails(new UsuarioSesion.Acceso(u.alcance(), u.pantallas(), u.asignados()));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        chain.doFilter(req, res);
    }

    /** Petición del usuario: renueva el último acceso (inactividad) y, cada 30 s como máximo, la actividad guardada en la base. */
    private void renovar(HttpSession s, Usuario u, Instant ahora) {
        s.setAttribute(AtributosSesion.ULTIMO, ahora.toEpochMilli());
        Long enBaseMs = (Long) s.getAttribute(AtributosSesion.ULTIMO_EN_BASE);
        if (enBaseMs == null || ahora.toEpochMilli() - enBaseMs >= 30_000) {
            // Actividad para el bloqueo del segundo ingreso (decisión 7.1); como máximo una escritura cada 30 s
            usuarios.tocarSesion(u.id(), u.sesionHuella(), ahora);
            s.setAttribute(AtributosSesion.ULTIMO_EN_BASE, ahora.toEpochMilli());
        }
    }

    private void cerrar(HttpServletRequest req, HttpServletResponse res, HttpSession s, String mensaje, String motivo) throws IOException {
        try {
            s.invalidate();
        } catch (IllegalStateException ya) {
            // ya estaba invalidada
        }
        SecurityContextHolder.clearContext();
        ConfiguracionSeguridad.responder(res, json, 401, mensaje, Map.of("motivo", motivo));
    }

    /** Solo corre dentro de la cadena de Spring Security, no como filtro suelto del servidor. */
    @Configuration
    static class Registro {
        @Bean
        FilterRegistrationBean<FiltroSesion> noRegistrarFiltroSesion(FiltroSesion f) {
            FilterRegistrationBean<FiltroSesion> r = new FilterRegistrationBean<>(f);
            r.setEnabled(false);
            return r;
        }
    }
}
