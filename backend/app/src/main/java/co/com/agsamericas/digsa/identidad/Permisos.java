package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.reglas.Pantallas;
import org.springframework.stereotype.Component;

/**
 * Autorización por pantalla para {@code @PreAuthorize("@permisos.pantalla('USUARIOS')")}: la misma tabla que arma el menú
 * ({@link Pantallas}), así el menú y el servidor no se contradicen. El Administrador entra a todo.
 */
@Component("permisos")
public class Permisos {

    public boolean pantalla(String nombre) {
        return UsuarioSesion.actual().isPresent() && Pantallas.valueOf(nombre).puedeEntrar(UsuarioSesion.perfilesActuales(), true);
    }
}
