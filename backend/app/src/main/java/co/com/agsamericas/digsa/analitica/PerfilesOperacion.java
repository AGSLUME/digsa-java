package co.com.agsamericas.digsa.analitica;

import co.com.agsamericas.digsa.identidad.RepositorioUsuarios;
import co.com.agsamericas.digsa.identidad.Usuario;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reglas.Perfil;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Permisos de operación de la analítica que funcionan también con roles adicionales (Anexo 5 1.12; decisión del 2026-10-22: perfil base
 * + pantallas). Una operación (cargar un insumo, operar la homologación, ver la bitácora de analítica) exige SIEMPRE la pantalla y,
 * además, que el perfil que opera sea directo o la base de los roles adicionales del usuario. Así un rol «Analista de datos» (base
 * Analista con «Cargues del OPL») carga insumos, y un rol con la misma base sin esa pantalla no (falla cerrado: sin la pantalla, nada).
 *
 * <p>La base sale de la misma fuente y con las mismas reglas que identidad ({@link Usuario#perfilesDeAlcance()}: solo roles válidos y
 * vigentes; un perfil directo más un rol, o roles con bases distintas, anulan los roles). Las autoridades de la sesión llevan solo los
 * perfiles directos; la base de los roles se lee del usuario en la base solo cuando no basta el perfil directo. Revisión A3 2026-10-23:
 * antes se leía {@code perfiles.base_id} de todos los ids asignados y un rol anulado por identidad seguía dando su base (escalada).
 */
@Component
public class PerfilesOperacion {

    private final JdbcClient jdbc;
    private final RepositorioUsuarios usuarios;

    PerfilesOperacion(JdbcClient jdbc, RepositorioUsuarios usuarios) {
        this.jdbc = jdbc;
        this.usuarios = usuarios;
    }

    /** ¿El usuario de la petición tiene la pantalla y opera con alguno de los perfiles (directo o base de sus roles adicionales)? */
    public boolean opera(Pantallas pantalla, Set<Perfil> perfiles) {
        if (UsuarioSesion.actual().isEmpty() || !UsuarioSesion.pantallasActuales().contains(pantalla)) {
            return false;
        }
        if (UsuarioSesion.perfilesActuales().stream().anyMatch(perfiles::contains)) {
            return true;
        }
        return basesDeRoles(pantalla).stream().anyMatch(perfiles::contains);
    }

    /**
     * Perfil base de los roles adicionales que cuentan para identidad y dan la pantalla. Vacío (falla cerrado) si el usuario tiene perfiles
     * directos (la mezcla con roles no es válida), si sus roles no son válidos o vigentes, o si la pantalla no le llega por ellos.
     */
    Set<Perfil> basesDeRoles(Pantallas pantalla) {
        Optional<Usuario> u = UsuarioSesion.actual().flatMap(s -> usuarios.porId(s.id()));
        if (u.isEmpty() || !u.get().activo() || !u.get().perfiles().isEmpty() || !u.get().pantallas().contains(pantalla)) {
            return Set.of();
        }
        // Sin perfiles directos, perfilesDeAlcance() son exactamente las bases de los roles válidos y vigentes (una sola base)
        EnumSet<Perfil> r = EnumSet.noneOf(Perfil.class);
        r.addAll(u.get().perfilesDeAlcance());
        return r;
    }

    /** Nombres de los perfiles y roles asignados (metadatos de las descargas y bitácora: «mismo perfil»). */
    public String nombres() {
        List<Integer> ids = UsuarioSesion.asignadosActuales().stream().sorted().toList();
        if (ids.isEmpty()) {
            return String.join(", ", UsuarioSesion.perfilesActuales().stream().map(Perfil::nombre).sorted().toList());
        }
        return String.join(", ", jdbc.sql("SELECT nombre FROM perfiles WHERE id = ANY(:ids) ORDER BY id").param("ids", ids.toArray(Integer[]::new))
                .query(String.class).list());
    }
}
