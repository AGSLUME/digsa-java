package co.com.agsamericas.digsa.analitica.prediccion;

import co.com.agsamericas.digsa.analitica.PerfilesOperacion;
import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reglas.Perfil;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Permisos del tablero 12 «Predictivo» además de la pantalla ({@code @permisos.pantalla('ANALITICA')}): consultar lo hace todo perfil con
 * la pantalla, en su alcance; reentrenar bajo demanda, Administrador, Coordinador y Analista (analitica-cohortes-alertas, «Bitácora de
 * entrenamientos»), directos o como base de los roles adicionales con la pantalla (PerfilesOperacion).
 */
@Component("permisosPrediccion")
public class PermisosPrediccion {

    static final Set<Perfil> REENTRENAN = Set.of(Perfil.ADMINISTRADOR, Perfil.COORDINADOR, Perfil.ANALISTA);

    private final PerfilesOperacion perfiles;

    PermisosPrediccion(PerfilesOperacion perfiles) {
        this.perfiles = perfiles;
    }

    public boolean reentrenar() {
        return perfiles.opera(Pantallas.ANALITICA, REENTRENAN);
    }
}
