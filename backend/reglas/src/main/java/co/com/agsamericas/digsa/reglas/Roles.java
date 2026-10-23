package co.com.agsamericas.digsa.reglas;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Roles adicionales parametrizables (Anexo 5, 1.12: «Los roles adicionales que requiera la DIGSA se establecerán durante la ejecución
 * del contrato y deberán ser parametrizados por el contratista»). Decisión del usuario del 2026-10-22: un rol adicional tiene un
 * <b>perfil base</b>, que fija el alcance de los datos, y un <b>subconjunto de las pantallas</b> de ese perfil. Así la tabla
 * {@link Pantallas} no cambia y un rol nuevo nunca ve más de lo que vería su perfil base.
 *
 * <p>Falla cerrado (revisión de seguridad de la E2): el rol da pantallas y alcance, pero <b>no</b> la identidad de su perfil base en las
 * reglas de los demás módulos (las autoridades y {@code perfilesActuales()} llevan solo los perfiles directos). Así un rol con base
 * Coordinador y solo «Dashboard de riesgo» no registra incidencias ni decide prórrogas, aunque el coordinador sí lo haga. Además, un usuario
 * con roles adicionales no tiene perfiles directos y todos sus roles comparten base: el alcance nunca se amplía por mezcla.
 */
public final class Roles {

    /** Rol creado por el administrador. */
    public record Adicional(int id, Perfil base, Set<Pantallas> pantallas) {
        public Adicional {
            pantallas = pantallas.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(pantallas));
        }
    }

    /**
     * Perfiles que pueden ser base. No el Administrador (administra usuarios, logs y la exportación completa: un rol derivado sería una
     * puerta lateral a la administración), ni Consulta (retirado, decisión 20), ni el Auditor (los módulos de agenda y maestros lo
     * identifican por su perfil directo: un rol con esa base no recibiría visitas).
     */
    public static final Set<Perfil> BASES = Set.copyOf(EnumSet.of(Perfil.COORDINADOR, Perfil.DIGSA, Perfil.SANIDAD, Perfil.ESM,
            Perfil.OPL_NACIONAL, Perfil.OPL_REGIONAL, Perfil.ANALISTA));

    private Roles() {
    }

    /**
     * Pantallas que se pueden dar a un rol con esa base: las de su perfil base que van en el menú (sin Inicio, que tiene todo usuario con
     * sesión, ni las ocultas como el formulario de la visita, que se abren desde otra pantalla con las reglas del perfil directo).
     */
    public static Set<Pantallas> elegibles(Perfil base) {
        EnumSet<Pantallas> r = EnumSet.noneOf(Pantallas.class);
        if (BASES.contains(base)) {
            for (Pantallas p : Pantallas.values()) {
                if (p != Pantallas.INICIO && !p.oculta() && !p.perfiles().isEmpty() && p.perfiles().contains(base)) {
                    r.add(p);
                }
            }
        }
        return r;
    }

    /** El motivo si el rol no es válido: base permitida y al menos una pantalla, todas de su perfil base. */
    public static Optional<String> problema(Perfil base, Collection<Pantallas> pantallas) {
        if (base == null || !BASES.contains(base)) {
            return Optional.of("El perfil base no es válido: un rol adicional no deriva del Administrador ni del Auditor.");
        }
        if (pantallas == null || pantallas.isEmpty()) {
            return Optional.of("Marque al menos una pantalla para el rol.");
        }
        Set<Pantallas> elegibles = elegibles(base);
        for (Pantallas p : pantallas) {
            if (!elegibles.contains(p)) {
                return Optional.of("La pantalla «" + p.titulo() + "» no es del perfil base " + base.nombre() + ".");
            }
        }
        return Optional.empty();
    }

    /** ¿El rol sigue sirviendo? Base permitida y al menos una pantalla que siga siendo de su base. Si no, no da nada (falla cerrado). */
    public static boolean vigente(Adicional a) {
        return BASES.contains(a.base()) && a.pantallas().stream().anyMatch(elegibles(a.base())::contains);
    }

    /** El motivo si la asignación no es válida: con roles adicionales, ningún perfil directo y una sola base para todos los roles. */
    public static Optional<String> asignacionInvalida(Collection<Perfil> directos, Collection<Adicional> adicionales) {
        if (adicionales.isEmpty()) {
            return Optional.empty();
        }
        if (!directos.isEmpty()) {
            return Optional.of("Un usuario con un rol adicional no tiene además perfiles: el rol ya trae el alcance de su perfil base.");
        }
        if (adicionales.stream().map(Adicional::base).distinct().count() > 1) {
            return Optional.of("Los roles adicionales de un usuario deben tener el mismo perfil base.");
        }
        return Optional.empty();
    }

    /**
     * Perfiles con que se calcula el alcance de los datos: los directos más la base de cada rol vigente. No son las autoridades: esas
     * llevan solo los perfiles directos.
     */
    public static Set<Perfil> deAlcance(Collection<Perfil> directos, Collection<Adicional> adicionales) {
        EnumSet<Perfil> r = EnumSet.noneOf(Perfil.class);
        r.addAll(directos);
        adicionales.stream().filter(Roles::vigente).forEach(a -> r.add(a.base()));
        return r;
    }

    /**
     * Pantallas a las que entra el usuario: las de sus perfiles directos (con la regla de {@link Pantallas#puedeEntrar}) más, de cada
     * rol adicional, las marcadas que sigan siendo elegibles para su perfil base. Con sesión siempre entra al inicio.
     */
    public static Set<Pantallas> permitidas(Collection<Perfil> directos, Collection<Adicional> adicionales) {
        EnumSet<Pantallas> r = EnumSet.noneOf(Pantallas.class);
        for (Pantallas p : Pantallas.values()) {
            if (p.puedeEntrar(directos, true)) {
                r.add(p);
            }
        }
        for (Adicional a : adicionales) {
            Set<Pantallas> elegibles = elegibles(a.base());
            a.pantallas().stream().filter(elegibles::contains).forEach(r::add);
        }
        return r;
    }
}
