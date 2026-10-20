package co.com.agsamericas.digsa.reglas;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Alcance de cada perfil (séptima ronda del 2026-10-15): qué datos ve el usuario. DIGSA, Administrador y Coordinador: todo; OPL
 * nacional: el país sin las visitas a necesidad pendientes; Sanidad (DISAN o JEFSA): su fuerza; OPL regional: su regional; ESM: su
 * ESM; Auditor: las visitas de su equipo. Un perfil sin su dato no da acceso: falla cerrado.
 */
public sealed interface Alcance {

    record Todo() implements Alcance {
    }

    /** OPL nacional: todo el país, menos las visitas a necesidad que aún no se hacen. */
    record Opl() implements Alcance {
    }

    record Fuerza(String codigo) implements Alcance {
    }

    record Regional(long id) implements Alcance {
    }

    record Esm(long puntoId) implements Alcance {
    }

    /** Auditor: las visitas en que es líder u otro auditor. */
    record Equipo(long usuarioId) implements Alcance {
    }

    record Ninguno() implements Alcance {
    }

    /** Perfiles de consulta de la DIGSA y las fuerzas (reemplazan a Consulta, decisión 20). */
    Set<Perfil> PERFILES_CONSULTA = EnumSet.of(Perfil.DIGSA, Perfil.SANIDAD, Perfil.ESM);
    /** Solo ellos solicitan visitas a necesidad (decisiones 33 y 35). */
    Set<Perfil> PERFILES_SOLICITAN = EnumSet.of(Perfil.DIGSA, Perfil.SANIDAD);
    /** Un usuario tiene uno solo de estos: cada uno ve un alcance distinto. */
    Set<Perfil> PERFILES_CON_ALCANCE = EnumSet.of(Perfil.DIGSA, Perfil.SANIDAD, Perfil.ESM, Perfil.OPL_NACIONAL, Perfil.OPL_REGIONAL);
    /** La entidad auditada no combina sus perfiles con los de la interventoría. */
    Set<Perfil> PERFILES_INTERVENTORIA = EnumSet.of(Perfil.AUDITOR, Perfil.COORDINADOR, Perfil.ADMINISTRADOR, Perfil.ANALISTA);

    /** Datos de alcance registrados en el usuario. */
    record DatosUsuario(long id, String fuerza, Long puntoId, Long regionalId) {
    }

    /** Alcance del usuario según sus perfiles, en el orden de la especificación. */
    static Alcance de(Collection<Perfil> perfiles, DatosUsuario u) {
        if (perfiles.contains(Perfil.ADMINISTRADOR) || perfiles.contains(Perfil.COORDINADOR) || perfiles.contains(Perfil.DIGSA)) {
            return new Todo();
        }
        if (perfiles.contains(Perfil.OPL_NACIONAL)) {
            return new Opl();
        }
        if (perfiles.contains(Perfil.SANIDAD) && u.fuerza() != null && !u.fuerza().isBlank()) {
            return new Fuerza(u.fuerza());
        }
        if (perfiles.contains(Perfil.OPL_REGIONAL) && u.regionalId() != null && u.regionalId() > 0) {
            return new Regional(u.regionalId());
        }
        if (perfiles.contains(Perfil.ESM) && u.puntoId() != null && u.puntoId() > 0) {
            return new Esm(u.puntoId());
        }
        if (perfiles.contains(Perfil.AUDITOR) && u.id() > 0) {
            return new Equipo(u.id());
        }
        return new Ninguno();
    }

    /** El motivo si la combinación de perfiles no es válida (fase 8 del PHP). */
    static Optional<String> combinacionInvalida(Collection<Perfil> perfiles) {
        List<String> conAlcance = PERFILES_CON_ALCANCE.stream().filter(perfiles::contains).map(Perfil::nombre).toList();
        if (conAlcance.size() > 1) {
            return Optional.of("Un usuario tiene un solo perfil de consulta u OPL (" + String.join(", ", conAlcance)
                    + "): cada uno ve un alcance distinto.");
        }
        boolean opl = perfiles.stream().anyMatch(Perfil::esOpl);
        if (opl && perfiles.stream().anyMatch(PERFILES_INTERVENTORIA::contains)) {
            return Optional.of("Los perfiles del OPL (entidad auditada) no se combinan con los de la interventoría.");
        }
        return Optional.empty();
    }

    /** Punto visto desde el alcance. */
    record Punto(long id, String fuerza, Long regionalId) {
    }

    /** ¿El punto está dentro del alcance? (p. ej. para solicitar una visita a ese punto). */
    static boolean puedeVerPunto(Alcance alcance, Punto p) {
        return switch (alcance) {
            case Todo t -> true;
            case Opl o -> true;
            case Fuerza f -> f.codigo().equals(p.fuerza());
            case Regional r -> p.regionalId() != null && p.regionalId() == r.id();
            case Esm e -> p.id() == e.puntoId();
            case Equipo e -> false;
            case Ninguno n -> false;
        };
    }
}
