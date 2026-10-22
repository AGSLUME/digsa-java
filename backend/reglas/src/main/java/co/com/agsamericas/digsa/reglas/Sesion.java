package co.com.agsamericas.digsa.reglas;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Reglas de la sesión (Anexo 5, 3.2): sin sesiones simultáneas del mismo usuario (el segundo ingreso se rechaza, decisión 7.1) y
 * cierre por inactividad de 10 minutos, parametrizable (duda 7.4: de 1 a 480).
 */
public final class Sesion {

    public static final int MINUTOS_DEFECTO = 10;
    public static final int MINUTOS_MAXIMO = 480;
    private static final Pattern ENTERO = Pattern.compile("^\\d+$");

    private Sesion() {
    }

    /** Minutos de inactividad: entero entre 1 y 480; si el valor no sirve, 10. */
    public static int minutos(Object valor) {
        String texto = switch (valor) {
            case String s -> s.strip();
            case Integer i -> i.toString();
            case Long l -> l.toString();
            case null, default -> "";
        };
        if (!ENTERO.matcher(texto).matches()) {
            return MINUTOS_DEFECTO;
        }
        long n;
        try {
            n = Long.parseLong(texto);
        } catch (NumberFormatException e) {
            return MINUTOS_MAXIMO; // más dígitos de los que caben: es mayor que el tope
        }
        if (n <= 0) {
            return MINUTOS_DEFECTO;
        }
        return (int) Math.min(n, MINUTOS_MAXIMO);
    }

    /** ¿Ya venció? Al cumplirse el plazo exacto ya está vencida: en ese instante el navegador vuelve al ingreso. */
    public static boolean vencida(Instant ultimoAcceso, Instant ahora, int minutos) {
        return ultimoAcceso != null && !Duration.between(ultimoAcceso, ahora).minusMinutes(minutos).isNegative();
    }

    /**
     * ¿Se bloquea un ingreso nuevo? (decisión 7.1 del 2026-10-20; Anexo 5, ítem 3: "no permite sesiones simultáneas del mismo usuario").
     * Sí, mientras haya una sesión con huella y actividad dentro del plazo de inactividad. La sesión se libera con «Salir» (sin
     * huella), al vencer la inactividad o cuando el administrador la cierra (sin huella).
     */
    public static boolean bloqueaNuevoIngreso(String huellaGuardada, Instant ultimaActividad, Instant ahora, int minutos) {
        return huellaGuardada != null && !huellaGuardada.isEmpty() && ultimaActividad != null && !vencida(ultimaActividad, ahora, minutos);
    }

    /** Huella que se guarda en usuarios.sesion_huella (nunca el identificador en claro). */
    public static String huella(String idSesion) {
        return Huellas.sha256(idSesion);
    }

    /** ¿Esta sesión es la única vigente del usuario? Sin huella guardada, ninguna lo es. */
    public static boolean esLaVigente(String huellaGuardada, String idSesion) {
        return huellaGuardada != null && !huellaGuardada.isEmpty() && idSesion != null
                && Huellas.iguales(huellaGuardada, huella(idSesion));
    }
}
