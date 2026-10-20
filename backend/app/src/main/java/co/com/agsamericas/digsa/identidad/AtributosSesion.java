package co.com.agsamericas.digsa.identidad;

/** Atributos guardados en la sesión del servidor. Solo tipos simples (la sesión se serializa en la base). */
final class AtributosSesion {

    /** Marca aleatoria de esta sesión; su SHA-256 es usuarios.sesion_huella (sesión única). */
    static final String MARCA = "digsa.marca";
    /** Inicio de la sesión (epoch ms): una contraseña cambiada después la invalida. */
    static final String INICIO = "digsa.inicio";
    /** Último acceso (epoch ms): cierre por inactividad. */
    static final String ULTIMO = "digsa.ultimo";
    /** Usuario que superó la contraseña y espera el código del segundo factor. */
    static final String MFA_USUARIO = "digsa.mfa.usuario";
    static final String MFA_DESDE = "digsa.mfa.desde";
    /** Usuario que ingresó con la contraseña inicial y debe cambiarla antes del código. */
    static final String CAMBIO_USUARIO = "digsa.cambio.usuario";
    static final String CAMBIO_DESDE = "digsa.cambio.desde";
    /** Última vez que la actividad de esta sesión se guardó en la base (epoch ms). */
    static final String ULTIMO_EN_BASE = "digsa.ultimo.base";

    private AtributosSesion() {
    }
}
