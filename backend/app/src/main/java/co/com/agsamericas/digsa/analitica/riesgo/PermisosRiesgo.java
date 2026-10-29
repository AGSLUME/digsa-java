package co.com.agsamericas.digsa.analitica.riesgo;

import co.com.agsamericas.digsa.analitica.PerfilesOperacion;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reglas.Perfil;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Permisos de cohortes y alertas además de la pantalla (analitica-cohortes-alertas): la consola exige la pantalla ANALITICA_ALERTAS y un
 * alcance nacional o de Fuerza (un rol con base ESM nunca la abre, aunque se le diera la pantalla); validar, descartar y ejecutar el motor
 * lo hacen Administrador, Coordinador y Analista; editar parámetros, referencias y criterios de cohortes, Administrador y Analista (el
 * Coordinador consulta). Perfil directo o base de los roles adicionales (PerfilesOperacion).
 */
@Component("permisosRiesgo")
public class PermisosRiesgo {

    static final Set<Perfil> VALIDAN = Set.of(Perfil.ADMINISTRADOR, Perfil.COORDINADOR, Perfil.ANALISTA);
    static final Set<Perfil> EDITAN = Set.of(Perfil.ADMINISTRADOR, Perfil.ANALISTA);

    private final PerfilesOperacion perfiles;

    PermisosRiesgo(PerfilesOperacion perfiles) {
        this.perfiles = perfiles;
    }

    /** Consola: la pantalla y un alcance nacional o de Fuerza (el ESM: 403). */
    public boolean consola() {
        Alcance a = UsuarioSesion.alcanceActual();
        return UsuarioSesion.pantallasActuales().contains(Pantallas.ANALITICA_ALERTAS) && (a instanceof Alcance.Todo || a instanceof Alcance.Fuerza);
    }

    public boolean validar() {
        return consola() && perfiles.opera(Pantallas.ANALITICA_ALERTAS, VALIDAN);
    }

    public boolean ejecutar() {
        return consola() && perfiles.opera(Pantallas.ANALITICA_ALERTAS, VALIDAN);
    }

    public boolean parametrizar() {
        return consola() && perfiles.opera(Pantallas.ANALITICA_ALERTAS, EDITAN);
    }

    /** Recalcular una cohorte bajo demanda: Administrador, Coordinador y Analista. */
    public boolean recalcularCohortes() {
        return perfiles.opera(Pantallas.ANALITICA, VALIDAN);
    }

    /** Crear y editar cohortes (criterios versionados). */
    public boolean editarCohortes() {
        return perfiles.opera(Pantallas.ANALITICA, EDITAN);
    }
}
