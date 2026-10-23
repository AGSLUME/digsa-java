package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Perfil;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Quién está en la sesión. Los perfiles se recalculan desde la base en cada petición (un perfil quitado deja de servir de
 * inmediato), así que aquí solo van id, usuario y nombre.
 */
public record UsuarioSesion(long id, String usuario, String nombre) implements Serializable, co.com.agsamericas.digsa.plataforma.Identificable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String PREFIJO = "PERFIL_";

    public static Authentication autenticacion(UsuarioSesion u, Collection<Perfil> perfiles) {
        List<GrantedAuthority> autoridades = perfiles.stream().map(p -> (GrantedAuthority) new SimpleGrantedAuthority(PREFIJO + p.name())).toList();
        return UsernamePasswordAuthenticationToken.authenticated(u, null, autoridades);
    }

    /** Usuario de la petición en curso, si hay sesión válida. */
    public static Optional<UsuarioSesion> actual() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof UsuarioSesion u ? Optional.of(u) : Optional.empty();
    }

    /** Perfiles vigentes de la petición en curso (los que dejó el filtro de la sesión al revalidar). */
    public static Set<Perfil> perfilesActuales() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        Set<Perfil> s = EnumSet.noneOf(Perfil.class);
        if (a != null && a.getPrincipal() instanceof UsuarioSesion) {
            for (GrantedAuthority g : a.getAuthorities()) {
                String n = g.getAuthority();
                if (n != null && n.startsWith(PREFIJO)) {
                    s.add(Perfil.valueOf(n.substring(PREFIJO.length())));
                }
            }
        }
        return s;
    }

    /** Lo que el filtro de la sesión calcula en cada petición (no se guarda en la sesión): alcance de los datos y pantallas permitidas. */
    public record Acceso(Alcance alcance, Set<co.com.agsamericas.digsa.reglas.Pantallas> pantallas, Set<Integer> asignados) {
        public Acceso {
            pantallas = Set.copyOf(pantallas);
            asignados = Set.copyOf(asignados);
        }
    }

    /** Perfiles y roles asignados (ids) de la petición en curso, para mostrarlos con su nombre. */
    public static Set<Integer> asignadosActuales() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getDetails() instanceof Acceso ac ? ac.asignados() : Set.of();
    }

    /** Alcance de la petición en curso; lo calcula el filtro de la sesión. Sin él, ninguno (falla cerrado). */
    public static Alcance alcanceActual() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getDetails() instanceof Acceso ac ? ac.alcance() : new Alcance.Ninguno();
    }

    /**
     * Pantallas de la petición en curso: las de los perfiles directos más las de los roles adicionales (Anexo 5, 1.12). Sin el cálculo del
     * filtro de la sesión, ninguna (falla cerrado).
     */
    public static Set<co.com.agsamericas.digsa.reglas.Pantallas> pantallasActuales() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof UsuarioSesion && a.getDetails() instanceof Acceso ac ? ac.pantallas() : Set.of();
    }
}
