package co.com.agsamericas.digsa.reglas;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Huellas SHA-256 y secretos aleatorios: de los tokens, enlaces, códigos y sesiones solo se guarda la huella. */
public final class Huellas {

    private static final SecureRandom AZAR = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private Huellas() {
    }

    /** SHA-256 en hexadecimal (64 caracteres). */
    public static String sha256(String texto) {
        return sha256(texto.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] datos) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(datos));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("La JVM no tiene SHA-256", e);
        }
    }

    /** Comparación en tiempo constante. */
    public static boolean iguales(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** Secreto aleatorio en hexadecimal de {@code bytes} bytes (32 bytes = 64 caracteres). */
    public static String secreto(int bytes) {
        byte[] b = new byte[bytes];
        AZAR.nextBytes(b);
        return HEX.formatHex(b);
    }

    /** Código numérico de 6 dígitos, con ceros a la izquierda. */
    public static String codigoSeisDigitos() {
        return String.format("%06d", AZAR.nextInt(1_000_000));
    }
}
