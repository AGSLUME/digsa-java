package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Pantallas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reglas.Roles;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Usuario tal como está en la base, con sus perfiles activos: {@code perfiles} son los perfiles base asignados directamente y
 * {@code rolesAdicionales} los roles parametrizables (Anexo 5, 1.12), cada uno con su perfil base y sus pantallas.
 */
public record Usuario(long id, String nombre, String documento, String telefono, String usuario, String correo, String claveHash,
                      boolean activo, boolean claveDefinida, Instant claveVence, boolean debeCambiarClave, Instant claveActualizada,
                      String tokenHash, Instant tokenVence, int intentos, Instant bloqueadoHasta, Instant ultimoIngreso,
                      String alcanceFuerza, Long alcancePunto, Long alcanceRegional, String sesionHuella, Instant sesionUltimo,
                      boolean claveInicial, Set<Perfil> perfiles, List<Roles.Adicional> rolesAdicionales) {

    public Usuario {
        perfiles = Set.copyOf(perfiles);
        rolesAdicionales = rolesAdicionales == null ? List.of() : List.copyOf(rolesAdicionales);
    }

    public boolean bloqueado(Instant ahora) {
        return bloqueadoHasta != null && bloqueadoHasta.isAfter(ahora);
    }

    /** ¿Debe definir o cambiar la contraseña antes de seguir? (primer ingreso, exigido por el administrador o vencida) */
    public boolean cambioPendiente(Instant ahora) {
        return !claveDefinida || debeCambiarClave || (claveVence != null && claveVence.isBefore(ahora));
    }

    /**
     * ¿Solo venció la vigencia (30 días) de una contraseña que el usuario sí conoce? Se cambia dentro de la aplicación, sin ir al correo
     * (condición 12 de la prueba en sitio). El cambio exigido por el administrador y la contraseña nunca definida siguen con el enlace.
     */
    public boolean claveVencida(Instant ahora) {
        return claveDefinida && !debeCambiarClave && !claveInicial && claveVence != null && claveVence.isBefore(ahora);
    }

    /**
     * Perfiles con que se calcula el alcance de los datos: los directos más la base de cada rol vigente. Las autoridades (y las reglas de
     * los demás módulos) usan solo {@code perfiles}: un rol no da la identidad de su base (falla cerrado).
     */
    public Set<Perfil> perfilesDeAlcance() {
        return Roles.deAlcance(perfiles, rolesValidos());
    }

    /** Pantallas a las que entra: las de sus perfiles directos más las marcadas en cada rol adicional. */
    public Set<Pantallas> pantallas() {
        return Roles.permitidas(perfiles, rolesValidos());
    }

    /**
     * Roles que cuentan: si la asignación quedó en una mezcla no válida (p. ej. se reactivó un rol de un usuario que ya tenía perfiles
     * directos), los roles no suman nada (falla cerrado) y el usuario conserva solo sus perfiles directos.
     */
    private List<Roles.Adicional> rolesValidos() {
        return Roles.asignacionInvalida(perfiles, rolesAdicionales).isEmpty() ? rolesAdicionales : List.of();
    }

    /** Ids asignados en usuarios_perfiles (perfiles base y roles adicionales), ordenados: los que edita el administrador. */
    public Set<Integer> asignados() {
        Set<Integer> r = new TreeSet<>();
        perfiles.forEach(p -> r.add(p.id()));
        rolesAdicionales.forEach(a -> r.add(a.id()));
        return r;
    }

    public Alcance alcance() {
        return Alcance.de(perfilesDeAlcance(), new Alcance.DatosUsuario(id, alcanceFuerza, alcancePunto, alcanceRegional));
    }
}
