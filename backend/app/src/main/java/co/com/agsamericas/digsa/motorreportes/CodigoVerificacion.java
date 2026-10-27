package co.com.agsamericas.digsa.motorreportes;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Código de verificación de un reporte: {@code RPT-aaaammdd-XXXXXXXX}, aleatorio (no sale de la huella), corto y fácil de dictar (sin
 * 0/O ni 1/I). 32^8 ≈ 10^12 combinaciones por día (no se adivina enumerando; además la verificación tiene tope por usuario);
 * la base exige que sea único.
 */
final class CodigoVerificacion {

    static final int LARGO = 8;
    static final String ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final Pattern FORMATO = Pattern.compile("^RPT-[0-9]{8}-[A-HJ-NP-Z2-9]{8}$");
    private static final SecureRandom AZAR = new SecureRandom();
    private static final DateTimeFormatter DIA = DateTimeFormatter.BASIC_ISO_DATE;

    private CodigoVerificacion() {
    }

    static String nuevo(LocalDate dia) {
        StringBuilder sb = new StringBuilder("RPT-").append(dia.format(DIA)).append('-');
        for (int i = 0; i < LARGO; i++) {
            sb.append(ALFABETO.charAt(AZAR.nextInt(ALFABETO.length())));
        }
        return sb.toString();
    }

    /** El código escrito por el usuario, en mayúsculas y sin espacios; vacío si no tiene la forma. */
    static String normalizar(String escrito) {
        String c = escrito == null ? "" : escrito.strip().toUpperCase(java.util.Locale.ROOT);
        return FORMATO.matcher(c).matches() ? c : "";
    }
}
