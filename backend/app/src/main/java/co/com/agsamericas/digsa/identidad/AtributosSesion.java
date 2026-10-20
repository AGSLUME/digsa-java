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

    private AtributosSesion() {
    }
}
