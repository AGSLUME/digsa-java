package co.com.agsamericas.digsa.analitica.homologacion;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.reglas.Perfil;
import java.util.Set;

/** Responsable legible de una acción de la homologación («jgarcia · analista»), sin datos personales. */
final class Responsable {

    private Responsable() {
    }

    static String de(UsuarioSesion quien, Set<Perfil> perfiles) {
        String perfil = perfiles.contains(Perfil.ANALISTA) ? "analista" : perfiles.contains(Perfil.ADMINISTRADOR) ? "administrador"
                : perfiles.contains(Perfil.COORDINADOR) ? "coordinador" : "usuario";
        return quien.usuario() + " · " + perfil;
    }

    static String actual() {
        return de(UsuarioSesion.actual().orElseThrow(), UsuarioSesion.perfilesActuales());
    }
}
