package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Perfil;
import java.time.Instant;
import java.util.Set;

/** Usuario tal como está en la base, con sus perfiles activos. */
public record Usuario(long id, String nombre, String documento, String telefono, String usuario, String correo, String claveHash,
                      boolean activo, boolean claveDefinida, Instant claveVence, boolean debeCambiarClave, Instant claveActualizada,
                      String tokenHash, Instant tokenVence, int intentos, Instant bloqueadoHasta, Instant ultimoIngreso,
                      String alcanceFuerza, Long alcancePunto, Long alcanceRegional, String sesionHuella, Instant sesionUltimo,
                      boolean claveInicial, Set<Perfil> perfiles) {

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

    public Alcance alcance() {
        return Alcance.de(perfiles, new Alcance.DatosUsuario(id, alcanceFuerza, alcancePunto, alcanceRegional));
    }
}
