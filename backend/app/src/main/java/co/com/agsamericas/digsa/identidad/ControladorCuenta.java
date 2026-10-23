package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** La cuenta propia de cualquier usuario con sesión: «Cambiar mi contraseña» sin salir de la aplicación (condición 12). */
@RestController
@RequestMapping("/api/cuenta")
public class ControladorCuenta {

    record CambioClave(@NotBlank(message = "Escriba su contraseña actual.") @Size(max = 200) String actual,
                       @NotBlank(message = "Escriba la contraseña nueva.") @Size(max = 200) String clave, String confirmacion) {
    }

    private final ServicioClaves claves;

    public ControladorCuenta(ServicioClaves claves) {
        this.claves = claves;
    }

    @PostMapping("/clave")
    @Accion(modulo = "cuenta", accion = "clave", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> cambiarClave(@RequestBody @Valid CambioClave c, HttpServletRequest req, HttpServletResponse res) {
        claves.cambiarPropia(UsuarioSesion.actual().orElseThrow().id(), c.actual(), c.clave(), c.confirmacion(), req, res);
        return Map.of("mensaje", "Su contraseña se cambió. La próxima vez ingrese con la contraseña nueva.");
    }
}
