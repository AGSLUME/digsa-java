package co.com.agsamericas.digsa.reglas;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Segundo factor del ingreso (respuesta de la DIGSA GMI P71 c, 19-oct-2026: autenticación multifactor). Decisión del usuario
 * (2026-10-20): código de 6 dígitos al correo para todos los perfiles. Topes iguales a los del código del QR del OPL.
 */
public final class Mfa {

    public static final int MINUTOS_VIGENCIA = 15;
    public static final int INTENTOS = 5;
    public static final int CODIGOS_POR_HORA = 5;
    private static final Pattern SEIS_DIGITOS = Pattern.compile("^\\d{6}$");

    private Mfa() {
    }

    public static Instant vence(Instant creado) {
        return creado.plus(Duration.ofMinutes(MINUTOS_VIGENCIA));
    }

    /** ¿El código escrito coincide con la huella guardada? Comparación en tiempo constante. */
    public static boolean codigoValido(String escrito, String huellaGuardada) {
        return escrito != null && SEIS_DIGITOS.matcher(escrito).matches() && Huellas.iguales(huellaGuardada, Huellas.sha256(escrito));
    }

    /** ¿El código sigue sirviendo? (sin usar, sin vencer y con intentos disponibles). */
    public static boolean usable(Instant vence, int intentos, boolean usado, Instant ahora) {
        return !usado && intentos < INTENTOS && !ahora.isAfter(vence);
    }

    /** ¿Se puede enviar otro código? Tope por hora para no inundar el correo (parámetro; por defecto {@link #CODIGOS_POR_HORA}). */
    public static boolean puedeEnviarOtro(int enviadosUltimaHora, int tope) {
        return enviadosUltimaHora < (tope > 0 ? tope : CODIGOS_POR_HORA);
    }
}
