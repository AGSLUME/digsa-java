package co.com.agsamericas.digsa.analitica.ingesta;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.reglas.AnaliticaIngesta;
import co.com.agsamericas.digsa.reglas.Perfil;
import org.springframework.stereotype.Component;

/**
 * Permisos de operación de los cargues, además de la pantalla ({@code @permisos.pantalla('ANALITICA_CARGUES')}): crear cargues y
 * confirmar el mapeo solo ADMINISTRADOR y ANALISTA (el COORDINADOR consulta); el original, solo ADMINISTRADOR; el servicio web, solo
 * OPL_NACIONAL. Se usa como {@code @PreAuthorize("@permisosAnalitica.cargar()")}.
 */
@Component("permisosAnalitica")
public class PermisosAnalitica {

    public boolean cargar() {
        return UsuarioSesion.actual().isPresent() && UsuarioSesion.perfilesActuales().stream().anyMatch(AnaliticaIngesta.PERFILES_CARGA::contains);
    }

    public boolean original() {
        return UsuarioSesion.actual().isPresent() && UsuarioSesion.perfilesActuales().contains(Perfil.ADMINISTRADOR);
    }

    public boolean servicioWeb() {
        return UsuarioSesion.actual().isPresent() && UsuarioSesion.perfilesActuales().contains(Perfil.OPL_NACIONAL);
    }
}
