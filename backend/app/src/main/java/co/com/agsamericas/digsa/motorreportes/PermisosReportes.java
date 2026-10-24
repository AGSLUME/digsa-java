package co.com.agsamericas.digsa.motorreportes;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.reglas.Pantallas;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Guarda de /api/reportes: entra quien tiene sesión y puede abrir la pantalla de al menos un reporte registrado. Cada reporte declara
 * su pantalla, y el motor la vuelve a comprobar por reporte (404 si no la puede abrir). Así un reporte de otra pantalla (por ejemplo,
 * LOGS) no queda detrás de la de REPORTES, y quien no puede ver ninguno recibe 403 como antes.
 */
@Component("permisosReportes")
public class PermisosReportes {

    private final Set<Pantallas> pantallas;

    public PermisosReportes(RegistroReportes registro) {
        this.pantallas = registro.todos().stream().map(p -> p.definicion().pantalla()).collect(Collectors.toUnmodifiableSet());
    }

    public boolean alguno() {
        return UsuarioSesion.actual().isPresent() && pantallas.stream().anyMatch(p -> p.puedeEntrar(UsuarioSesion.perfilesActuales(), true));
    }
}
