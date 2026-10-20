package co.com.agsamericas.digsa.reglas;

import java.util.Locale;
import java.util.regex.Pattern;

/** Direcciones de correo: validación simple y enmascarado para mostrarlas sin revelarlas (Ley 1581). */
public final class Correos {

    private static final Pattern VALIDO = Pattern.compile("^[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private Correos() {
    }

    public static boolean valido(String correo) {
        return correo != null && correo.length() <= 150 && VALIDO.matcher(correo.strip()).matches();
    }

    public static String normalizar(String correo) {
        return correo == null ? null : correo.strip().toLowerCase(Locale.ROOT);
    }

    /** a*******z@dominio: la primera y la última letra del usuario; con 2 o menos, solo la primera. */
    public static String enmascarar(String correo) {
        if (correo == null) {
            return "";
        }
        int arroba = correo.indexOf('@');
        String u = arroba < 0 ? correo : correo.substring(0, arroba);
        String d = arroba < 0 ? "" : correo.substring(arroba + 1);
        int n = u.codePointCount(0, u.length());
        if (n <= 2) {
            return u.substring(0, Math.min(u.length(), u.offsetByCodePoints(0, Math.min(1, n)))) + "*".repeat(Math.max(0, n - 1)) + "@" + d;
        }
        String primera = u.substring(0, u.offsetByCodePoints(0, 1));
        String ultima = u.substring(u.offsetByCodePoints(0, n - 1));
        return primera + "*".repeat(n - 2) + ultima + "@" + d;
    }
}
