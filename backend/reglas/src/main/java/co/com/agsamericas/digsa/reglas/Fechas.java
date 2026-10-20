package co.com.agsamericas.digsa.reglas;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;
import java.util.regex.Pattern;

/** Fechas y horas estrictas: nada de "30 de febrero" ni "+1 year". Hora de negocio: America/Bogota, formato de 24 h. */
public final class Fechas {

    public static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern HORA = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    private Fechas() {
    }

    /** Fecha AAAA-MM-DD válida, o vacío. */
    public static Optional<LocalDate> valida(String texto) {
        if (texto == null || texto.length() != 10) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(texto, FECHA));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** Hora HH:MM de 00:00 a 23:59, o vacío. */
    public static Optional<LocalTime> hora(String texto) {
        if (texto == null || !HORA.matcher(texto).matches()) {
            return Optional.empty();
        }
        return Optional.of(LocalTime.parse(texto));
    }
}
