package co.com.agsamericas.digsa.analitica.tableros;

import co.com.agsamericas.digsa.analitica.PerfilesOperacion;
import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reglas.Perfil;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Permisos de la analítica además de la pantalla ({@code @permisos.pantalla('ANALITICA')}): la bitácora de analítica la consultan el
 * Administrador y el Coordinador (directos o como base de los roles adicionales con la pantalla). La trazabilidad exige su pantalla
 * (ANALITICA_TRAZABILIDAD, asignable por rol) y además un perfil autorizado por la spec (Administrador, Coordinador, Analista o DIGSA,
 * directo o como base): un rol con base Sanidad o ESM nunca rastrea, aunque se le diera la pantalla.
 */
@Component("permisosTableros")
public class PermisosTableros {

    static final Set<Perfil> RASTREAN = Set.of(Perfil.ADMINISTRADOR, Perfil.COORDINADOR, Perfil.ANALISTA, Perfil.DIGSA);

    private final PerfilesOperacion perfiles;

    PermisosTableros(PerfilesOperacion perfiles) {
        this.perfiles = perfiles;
    }

    public boolean bitacora() {
        return perfiles.opera(Pantallas.ANALITICA, Set.of(Perfil.ADMINISTRADOR, Perfil.COORDINADOR));
    }

    public boolean traza() {
        return perfiles.opera(Pantallas.ANALITICA_TRAZABILIDAD, RASTREAN);
    }
}
