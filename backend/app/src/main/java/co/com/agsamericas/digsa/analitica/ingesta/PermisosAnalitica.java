package co.com.agsamericas.digsa.analitica.ingesta;

import co.com.agsamericas.digsa.analitica.PerfilesOperacion;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.reglas.AnaliticaIngesta;
import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reglas.Perfil;
import org.springframework.stereotype.Component;

/**
 * Permisos de operación de los cargues, además de la pantalla ({@code @permisos.pantalla('ANALITICA_CARGUES')}): crear cargues y
 * confirmar el mapeo solo ADMINISTRADOR y ANALISTA (el COORDINADOR consulta); el original, solo ADMINISTRADOR; el servicio web, solo
 * OPL_NACIONAL. Se usa como {@code @PreAuthorize("@permisosAnalitica.cargar()")}.
 *
 * <p>A3: cargar exige también la pantalla y acepta el perfil directo o la base de un rol adicional que la da (PerfilesOperacion): un rol
 * con base Analista y «Cargues del OPL» carga; sin la pantalla, nadie.
 */
@Component("permisosAnalitica")
public class PermisosAnalitica {

    private final PerfilesOperacion perfiles;

    PermisosAnalitica(PerfilesOperacion perfiles) {
        this.perfiles = perfiles;
    }

    public boolean cargar() {
        return perfiles.opera(Pantallas.ANALITICA_CARGUES, AnaliticaIngesta.PERFILES_CARGA);
    }

    public boolean original() {
        return UsuarioSesion.actual().isPresent() && UsuarioSesion.perfilesActuales().contains(Perfil.ADMINISTRADOR);
    }

    public boolean servicioWeb() {
        return UsuarioSesion.actual().isPresent() && UsuarioSesion.perfilesActuales().contains(Perfil.OPL_NACIONAL);
    }
}
