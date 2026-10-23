package co.com.agsamericas.digsa.analitica.homologacion;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.reglas.Perfil;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Permisos de operación de la homologación, además de la pantalla ({@code @permisos.pantalla('HOMOLOGACION')}: ADMINISTRADOR, ANALISTA y
 * COORDINADOR). Cargar y publicar fuentes, editar reglas, catálogos y listas, y re-ejecutar: ADMINISTRADOR y ANALISTA. Revisar (aprobar o
 * devolver), consultar y exportar: los tres. Se usa como {@code @PreAuthorize("@permisosHomologacion.operar()")}.
 */
@Component("permisosHomologacion")
public class PermisosHomologacion {

    /** Perfiles que operan la homologación (el COORDINADOR consulta, aprueba, devuelve y exporta). */
    public static final Set<Perfil> OPERAN = Set.of(Perfil.ADMINISTRADOR, Perfil.ANALISTA);

    public boolean operar() {
        return UsuarioSesion.actual().isPresent() && UsuarioSesion.perfilesActuales().stream().anyMatch(OPERAN::contains);
    }
}
