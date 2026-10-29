package co.com.agsamericas.digsa.analitica.homologacion;

import co.com.agsamericas.digsa.analitica.PerfilesOperacion;
import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reglas.Perfil;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Permisos de operación de la homologación, además de la pantalla ({@code @permisos.pantalla('HOMOLOGACION')}: ADMINISTRADOR, ANALISTA y
 * COORDINADOR). Cargar y publicar fuentes, editar reglas, catálogos y listas, y re-ejecutar: ADMINISTRADOR y ANALISTA. Revisar (aprobar o
 * devolver), consultar y exportar: los tres. Se usa como {@code @PreAuthorize("@permisosHomologacion.operar()")}.
 *
 * <p>A3: operar exige también la pantalla y acepta el perfil directo o la base de un rol adicional que la da (PerfilesOperacion).
 */
@Component("permisosHomologacion")
public class PermisosHomologacion {

    /** Perfiles que operan la homologación (el COORDINADOR consulta, aprueba, devuelve y exporta). */
    public static final Set<Perfil> OPERAN = Set.of(Perfil.ADMINISTRADOR, Perfil.ANALISTA);

    private final PerfilesOperacion perfiles;

    PermisosHomologacion(PerfilesOperacion perfiles) {
        this.perfiles = perfiles;
    }

    public boolean operar() {
        return perfiles.opera(Pantallas.HOMOLOGACION, OPERAN);
    }
}
