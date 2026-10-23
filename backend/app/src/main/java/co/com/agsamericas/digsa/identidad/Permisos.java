package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.reglas.Pantallas;
import org.springframework.stereotype.Component;

/**
 * Autorización por pantalla para {@code @PreAuthorize("@permisos.pantalla('USUARIOS')")}: las mismas pantallas que arman el menú
 * (perfiles directos según {@link Pantallas} más las marcadas en los roles adicionales, Anexo 5 1.12), así el menú y el servidor no se
 * contradicen. El Administrador entra a todo.
 */
@Component("permisos")
public class Permisos {

    public boolean pantalla(String nombre) {
        return UsuarioSesion.actual().isPresent() && UsuarioSesion.pantallasActuales().contains(Pantallas.valueOf(nombre));
    }
}
