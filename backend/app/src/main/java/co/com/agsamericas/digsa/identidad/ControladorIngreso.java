package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Rutas públicas de la identidad: ingreso en dos pasos, salir, "olvidé mi contraseña" y definir la contraseña con el enlace. */
@RestController
@RequestMapping("/api/auth")
public class ControladorIngreso {

    record Ingreso(@NotBlank(message = "Escriba su usuario.") @Size(max = 50) String usuario,
                   @NotBlank(message = "Escriba su contraseña.") @Size(max = 200) String clave, String captcha) {
    }

    record Codigo(@NotBlank(message = "Escriba el código.") @Size(max = 10) String codigo) {
    }

    record Olvide(@NotBlank(message = "Escriba su usuario o su correo.") @Size(max = 150) String valor, String captcha) {
    }

    record NuevaClave(@NotBlank @Size(max = 64) String tkn, @NotBlank(message = "Escriba la contraseña.") @Size(max = 200) String clave,
                      String confirmacion) {
    }

    record CambioInicial(@NotBlank(message = "Escriba la contraseña.") @Size(max = 200) String clave, String confirmacion) {
    }

    private final ServicioIngreso ingreso;
    private final ServicioClaves claves;
    private final Captcha captcha;

    public ControladorIngreso(ServicioIngreso ingreso, ServicioClaves claves, Captcha captcha) {
        this.ingreso = ingreso;
        this.claves = claves;
        this.captcha = captcha;
    }

    /** Configuración pública del ingreso y el token CSRF (la cookie XSRF-TOKEN queda en la respuesta). */
    @GetMapping("/inicio")
    @Accion(modulo = "sesion", accion = "inicio", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> inicio(CsrfToken csrf) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("captchaSitio", captcha.claveSitio());
        m.put("captchaDesactivado", captcha.desactivado());
        m.put("csrf", csrf.getHeaderName());
        return m;
    }

    @PostMapping("/ingreso")
    @Accion(modulo = "sesion", accion = "ingreso", operacion = Declarada.SIN_REGISTRO)
    public ServicioIngreso.Paso ingresar(@RequestBody @jakarta.validation.Valid Ingreso i, HttpServletRequest req) {
        return conCodigoLocal(ingreso.ingresar(i.usuario(), i.clave(), i.captcha(), req), req);
    }

    @PostMapping("/codigo")
    @Accion(modulo = "sesion", accion = "codigo", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> verificar(@RequestBody @jakarta.validation.Valid Codigo c, HttpServletRequest req, HttpServletResponse res) {
        UsuarioSesion u = ingreso.verificar(c.codigo().strip(), req, res);
        return Map.of("usuario", u.usuario(), "nombre", u.nombre());
    }

    /** Contraseña inicial asignada por el administrador: se cambia antes del código, sin salir al correo. */
    @PostMapping("/cambiar-clave")
    @Accion(modulo = "sesion", accion = "clave_inicial", operacion = Declarada.SIN_REGISTRO)
    public ServicioIngreso.Paso cambiarClave(@RequestBody @jakarta.validation.Valid CambioInicial c, HttpServletRequest req) {
        return conCodigoLocal(ingreso.cambiarClaveInicial(c.clave(), c.confirmacion(), req), req);
    }

    @PostMapping("/codigo/reenviar")
    @Accion(modulo = "sesion", accion = "codigo_reenviar", operacion = Declarada.SIN_REGISTRO)
    public ServicioIngreso.Paso reenviar(HttpServletRequest req) {
        return conCodigoLocal(ingreso.reenviar(req), req);
    }

    @PostMapping("/salir")
    @Accion(modulo = "sesion", accion = "salir", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> salir(HttpServletRequest req) {
        ingreso.salir(req);
        return Map.of("mensaje", "Sesión cerrada.");
    }

    @PostMapping("/olvide")
    @Accion(modulo = "sesion", accion = "olvide", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> olvide(@RequestBody @jakarta.validation.Valid Olvide o, HttpServletRequest req) {
        if (!captcha.superado(o.captcha(), req.getRemoteAddr())) {
            throw ErrorNegocio.validacion("Por favor complete la verificación de seguridad (captcha).");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mensaje", "Si el usuario o el correo están registrados y activos, le enviamos un enlace para definir una contraseña nueva.");
        claves.olvide(o.valor());
        return m;
    }

    @GetMapping("/clave")
    @Accion(modulo = "sesion", accion = "clave_enlace", operacion = Declarada.CONSULTA)
    public Map<String, Object> enlace(@RequestParam String tkn) {
        return claves.usuarioDelEnlace(tkn).<Map<String, Object>>map(u -> Map.of("valido", true, "nombre", u.nombre()))
                .orElse(Map.of("valido", false, "mensaje", "El enlace no es válido o ya venció. Pida uno nuevo con «¿Olvidó su contraseña?»."));
    }

    @PostMapping("/clave")
    @Accion(modulo = "sesion", accion = "clave_definir", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> definir(@RequestBody @jakarta.validation.Valid NuevaClave n) {
        claves.definir(n.tkn(), n.clave(), n.confirmacion());
        return Map.of("mensaje", "La contraseña se cambió correctamente. Inicie sesión con su nueva contraseña.");
    }

    /** Solo en local con correo a archivo: el código se devuelve para poder probar sin buzón real. */
    private static ServicioIngreso.Paso conCodigoLocal(ServicioIngreso.Paso p, HttpServletRequest req) {
        Object codigo = req.getAttribute("digsa.mfa.codigoLocal");
        return codigo == null ? p : new ServicioIngreso.Paso(p.paso(), p.mensaje() + " (local: " + codigo + ")", p.correo(), p.enlaceLocal());
    }
}
