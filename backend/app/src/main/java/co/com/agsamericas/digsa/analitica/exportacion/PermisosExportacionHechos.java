package co.com.agsamericas.digsa.analitica.exportacion;

import co.com.agsamericas.digsa.analitica.PerfilesOperacion;
import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reglas.Perfil;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Quién exporta los hechos por periodo (A7.2, duda AN.14): solo el ADMINISTRADOR, como la exportación completa (exportacion-alertas «Solo
 * el administrador exporta todos los datos») y el original de un cargue. Exige además la pantalla «Cargues del OPL» (la sección vive en
 * ella); el Analista y el Coordinador, que la tienen, reciben 403. Acepta el perfil directo o la base de un rol adicional que da la
 * pantalla (PerfilesOperacion, igual que cargar). Se usa como {@code @PreAuthorize("@permisosHechos.exportar()")}.
 */
@Component("permisosHechos")
public class PermisosExportacionHechos {

    static final Set<Perfil> PERFILES = Set.of(Perfil.ADMINISTRADOR);

    private final PerfilesOperacion perfiles;

    PermisosExportacionHechos(PerfilesOperacion perfiles) {
        this.perfiles = perfiles;
    }

    public boolean exportar() {
        return perfiles.opera(Pantallas.ANALITICA_CARGUES, PERFILES);
    }
}
