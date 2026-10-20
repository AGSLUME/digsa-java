package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa;
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
    private final ConfiguracionDigsa cfg;
    private final ObjectMapper json;
    private final Clock reloj;

    public FiltroSesion(RepositorioUsuarios usuarios, ServicioClaves claves, RegistroBitacora bitacora, ConfiguracionDigsa cfg,
                        ObjectMapper json, Clock reloj) {
        this.usuarios = usuarios;
        this.claves = claves;
        this.bitacora = bitacora;
        this.cfg = cfg;
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

        if (ultimo == null || Sesion.vencida(Instant.ofEpochMilli(ultimo), ahora, cfg.minutosSesion())) {
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
        if (u.claveActualizada() != null && inicio != null && u.claveActualizada().isAfter(Instant.ofEpochMilli(inicio))) {
            cerrar(req, res, s, "La contraseña cambió. Inicie sesión de nuevo.", "clave");
            return;
        }
        if (!Sesion.esLaVigente(u.sesionHuella(), marca)) {
            // Sin huella: la cerró el administrador (o «Salir» en otra pestaña); con otra huella: venció por inactividad y hubo otro ingreso
            boolean cerradaPorOtro = u.sesionHuella() == null;
            bitacora.escribir(Bitacora.Operacion.SALIDA, cerradaPorOtro ? "sesion_cerrada" : "sesion_reemplazada", "sesion",
                    cerradaPorOtro ? "Sesión cerrada desde otra pestaña o por el administrador" : "Sesión reemplazada por un ingreso posterior",
                    u.id(), u.usuario(), ip);
            cerrar(req, res, s, cerradaPorOtro ? "Su sesión fue cerrada. Vuelva a iniciar sesión."
                    : "Su sesión se cerró porque su usuario ingresó desde otro equipo o navegador. Solo se permite una sesión a la vez.",
                    cerradaPorOtro ? "cerrada" : "reemplazada");
            return;
        }
        if (u.cambioPendiente(ahora)) {
            usuarios.cerrarSesion(u.id(), Sesion.huella(marca));
            ServicioClaves.EnvioEnlace e = claves.enviarEnlace(u, "Defina su contraseña", "Debe actualizar su contraseña.", false);
            cerrar(req, res, s, "Debe actualizar su contraseña." + (e.correoOk() ? " Le enviamos un enlace a su correo." : ""), "cambio_clave");
            return;
        }

        s.setAttribute(AtributosSesion.ULTIMO, ahora.toEpochMilli());
        Long enBaseMs = (Long) s.getAttribute(AtributosSesion.ULTIMO_EN_BASE);
        if (enBaseMs == null || ahora.toEpochMilli() - enBaseMs >= 30_000) {
            // Actividad para el bloqueo del segundo ingreso (decisión 7.1); como máximo una escritura cada 30 s
            usuarios.tocarSesion(u.id(), u.sesionHuella(), ahora);
            s.setAttribute(AtributosSesion.ULTIMO_EN_BASE, ahora.toEpochMilli());
        }
        req.setAttribute(RegistroBitacora.ATR_USUARIO_ID, u.id());
        req.setAttribute(RegistroBitacora.ATR_USUARIO, u.usuario());
        AbstractAuthenticationToken auth = (AbstractAuthenticationToken) UsuarioSesion.autenticacion(
                new UsuarioSesion(u.id(), u.usuario(), u.nombre()), u.perfiles());
        auth.setDetails(u.alcance()); // solo para esta petición: no se guarda en la sesión
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        chain.doFilter(req, res);
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
