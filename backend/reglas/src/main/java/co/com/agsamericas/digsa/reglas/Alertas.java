package co.com.agsamericas.digsa.reglas;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Alertas y bandeja de salida (spec exportacion-alertas; GMI P75 «tiempo real o cuasi real» y P77 b «críticas por correo y SMS»):
 * teléfono móvil colombiano en E.164, su máscara para los registros, el texto del SMS (GSM-7, sin datos personales), la espera entre
 * reintentos de la bandeja y la regla de no repetir el aviso de una alerta abierta más de una vez por hora.
 */
public final class Alertas {

    /** Tipos del catálogo inicial (V17). Las críticas van por correo y SMS; las operativas, solo por correo. */
    public static final String TAREA_DIARIA_FALLIDA = "tarea_diaria_fallida";
    public static final String CORREO_AGOTADO = "correo_agotado";
    public static final String INTEGRIDAD_ARCHIVO = "integridad_archivo";
    public static final String ALMACEN_NO_DISPONIBLE = "almacen_no_disponible";
    public static final String BLOQUEO_MASIVO = "bloqueo_masivo";
    public static final String INTERVENCION_INMEDIATA = "intervencion_inmediata";
    public static final String PLAN_OPL_VENCIDO = "plan_opl_vencido";

    /** Intentos de envío de la bandeja antes de darlo por fallido (spec: máximo 5). */
    public static final int MAX_INTENTOS = 5;
    /** Una alerta abierta no repite su aviso (correo y SMS) antes de esta espera. */
    public static final Duration REPETICION = Duration.ofHours(1);
    /** Largo de un SMS de una sola parte en GSM-7. */
    public static final int MAX_SMS = 160;

    private static final Duration[] ESPERAS = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2)};
    private static final Pattern SEPARADORES = Pattern.compile("[\\s().-]+");
    private static final Pattern MOVIL = Pattern.compile("^3[0-9]{9}$");
    private static final Pattern MARCAS = Pattern.compile("\\p{M}+");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    /** Alfabeto básico GSM 03.38 que se usa (sin tildes ni símbolos de la extensión, que cuentan doble o se pierden). */
    private static final String GSM7 = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

    private Alertas() {
    }

    /**
     * Teléfono móvil colombiano en E.164: «+57» y 10 dígitos que empiezan por 3. Acepta espacios, guiones, puntos, paréntesis y los
     * prefijos +57, 0057 o 57. @return vacío si no es un móvil colombiano (un fijo o un número de otro país no recibe SMS)
     */
    public static Optional<String> normalizarTelefono(String telefono) {
        if (telefono == null) {
            return Optional.empty();
        }
        String t = SEPARADORES.matcher(telefono.strip()).replaceAll("");
        if (t.startsWith("+57")) {
            t = t.substring(3);
        } else if (t.startsWith("0057")) {
            t = t.substring(4);
        } else if (t.startsWith("57") && t.length() == 12) {
            t = t.substring(2);
        }
        return MOVIL.matcher(t).matches() ? Optional.of("+57" + t) : Optional.empty();
    }

    /** «+573*******67»: el indicativo, el primer dígito y los 2 últimos (Ley 1581: los registros no guardan el número). */
    public static String enmascararTelefono(String telefono) {
        String e164 = normalizarTelefono(telefono).orElse(null);
        if (e164 == null) {
            return "***";
        }
        return e164.substring(0, 4) + "*".repeat(e164.length() - 6) + e164.substring(e164.length() - 2);
    }

    /**
     * Texto del SMS de una alerta crítica: solo el nombre del tipo, la hora y «Revise la Suite DIGSA». Sin datos personales, sin
     * enlaces y sin el contenido del correo (Ley 1581). En GSM-7 sin tildes y de una sola parte (≤ 160).
     */
    public static String textoSms(String nombreTipo, LocalDateTime hora) {
        String nombre = gsm7(nombreTipo == null ? "" : nombreTipo).strip();
        String cola = " (" + hora.format(HORA) + "). Revise la Suite DIGSA";
        String inicio = "Alerta critica DIGSA: ";
        int espacio = MAX_SMS - inicio.length() - cola.length();
        if (nombre.length() > espacio) {
            nombre = nombre.substring(0, Math.max(0, espacio)).strip();
        }
        return inicio + nombre + cola;
    }

    /** Pasa un texto al alfabeto GSM-7 básico: quita las tildes y cambia por espacio lo que no está en el alfabeto. */
    public static String gsm7(String texto) {
        String sinTildes = MARCAS.matcher(Normalizer.normalize(texto, Normalizer.Form.NFD)).replaceAll("");
        StringBuilder b = new StringBuilder(sinTildes.length());
        sinTildes.codePoints().forEach(c -> b.append(c < 0x10000 && GSM7.indexOf(c) >= 0 && c != '\n' && c != '\r' ? (char) c : ' '));
        return b.toString().replaceAll(" {2,}", " ");
    }

    public static boolean esGsm7(String texto) {
        return texto != null && texto.codePoints().allMatch(c -> c < 0x10000 && GSM7.indexOf(c) >= 0);
    }

    /** Espera antes del siguiente intento: 1 min, 5 min, 30 min y 2 h (después del intento 1, 2, 3 y 4). */
    public static Duration esperaReintento(int intentosHechos) {
        if (intentosHechos < 1) {
            return Duration.ZERO;
        }
        return ESPERAS[Math.min(intentosHechos, ESPERAS.length) - 1];
    }

    /** ¿Se agotaron los intentos? Con 5 intentos fallidos el envío queda «fallido». */
    public static boolean agotado(int intentosHechos) {
        return intentosHechos >= MAX_INTENTOS;
    }

    /** ¿Se vuelve a avisar una alerta abierta? Sí la primera vez y luego como mucho una vez por hora. */
    public static boolean avisarDeNuevo(Instant ultimoAviso, Instant ahora) {
        return ultimoAviso == null || !ahora.isBefore(ultimoAviso.plus(REPETICION));
    }

    /** ¿Hay un bloqueo masivo? N o más cuentas bloqueadas por intentos en la última hora (posible ataque). */
    public static boolean bloqueoMasivo(long cuentasBloqueadasEnLaHora, int umbral) {
        return umbral > 0 && cuentasBloqueadasEnLaHora >= umbral;
    }
}
