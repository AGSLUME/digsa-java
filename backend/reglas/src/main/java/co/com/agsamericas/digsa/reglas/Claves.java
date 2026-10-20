package co.com.agsamericas.digsa.reglas;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/** Reglas de las contraseñas y de los enlaces de un solo uso para definirlas. */
public final class Claves {

    /** Vigencia del enlace para definir la contraseña (como en el PHP de referencia). */
    public static final int MINUTOS_ENLACE = 60;
    /** Días de vigencia de la contraseña antes de exigir el cambio (CLAVE_VIGENCIA_DIAS). */
    public static final int VIGENCIA_DIAS_DEFECTO = 30;
    /** BCrypt solo usa los primeros 72 bytes: más largo no aporta y se rechaza. */
    public static final int MAXIMO = 64;

    private static final Pattern NI_N_NI_TILDES = Pattern.compile("[ñÑáéíóúÁÉÍÓÚüÜ]");
    private static final Pattern MINUSCULA = Pattern.compile("[a-z]");
    private static final Pattern MAYUSCULA = Pattern.compile("[A-Z]");
    private static final Pattern NUMERO = Pattern.compile("\\d");
    private static final Pattern ESPECIAL = Pattern.compile("[^A-Za-z0-9]");
    private static final Pattern TOKEN = Pattern.compile("^[a-f0-9]{64}$");

    private Claves() {
    }

    /** El problema de la contraseña, o vacío si es válida. Decisión del usuario (2026-10-14): sin ñ ni tildes. */
    public static Optional<String> problema(String clave) {
        if (clave == null) {
            return Optional.of("La contraseña debe tener mínimo 12 caracteres.");
        }
        if (NI_N_NI_TILDES.matcher(clave).find()) {
            return Optional.of("La contraseña no puede tener ñ ni tildes.");
        }
        int largo = clave.codePointCount(0, clave.length());
        if (largo < 12) {
            return Optional.of("La contraseña debe tener mínimo 12 caracteres.");
        }
        if (largo > MAXIMO || clave.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            return Optional.of("La contraseña debe tener máximo " + MAXIMO + " caracteres.");
        }
        if (!MINUSCULA.matcher(clave).find() || !MAYUSCULA.matcher(clave).find()) {
            return Optional.of("La contraseña debe tener mayúsculas y minúsculas.");
        }
        if (!NUMERO.matcher(clave).find()) {
            return Optional.of("La contraseña debe tener al menos un número.");
        }
        if (!ESPECIAL.matcher(clave).find()) {
            return Optional.of("La contraseña debe tener al menos un carácter especial.");
        }
        return Optional.empty();
    }

    /** Un enlace sirve si el token tiene la forma esperada, su huella coincide con la guardada y no ha vencido. */
    public static boolean enlaceVigente(String huellaGuardada, Instant expira, String tokenRecibido, Instant ahora) {
        return huellaGuardada != null && expira != null && tokenRecibido != null
                && TOKEN.matcher(tokenRecibido).matches()
                && Huellas.iguales(huellaGuardada, Huellas.sha256(tokenRecibido))
                && !expira.isBefore(ahora);
    }
}
