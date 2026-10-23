package co.com.agsamericas.digsa.reglas;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Periodos de los reportes (Anexo 5, 2.11): agrupación por horas, días, semanas, meses, bimestres, trimestres, semestres y años, y
 * los presets del rango. Todo en hora de Bogotá; el corte de cada periodo es la medianoche. Las etiquetas son estables (la huella
 * de los datos las incluye) y coinciden con las que arma el SQL de los reportes agregados.
 */
public final class Periodos {

    private Periodos() {
    }

    /** Agrupación del periodo. La semana es ISO (lunes a domingo) y lleva «W» para no confundirse con el semestre («S»). */
    public enum Agrupacion {
        HORA("hora", "Horas"),
        DIA("dia", "Días"),
        SEMANA("semana", "Semanas"),
        MES("mes", "Meses"),
        BIMESTRE("bimestre", "Bimestres"),
        TRIMESTRE("trimestre", "Trimestres"),
        SEMESTRE("semestre", "Semestres"),
        ANIO("anio", "Años");

        private final String clave;
        private final String nombre;

        Agrupacion(String clave, String nombre) {
            this.clave = clave;
            this.nombre = nombre;
        }

        public String clave() {
            return clave;
        }

        public String nombre() {
            return nombre;
        }

        /** La agrupación por horas exige un campo de fecha y hora. */
        public boolean requiereHora() {
            return this == HORA;
        }

        public static Optional<Agrupacion> deClave(String clave) {
            return Arrays.stream(values()).filter(a -> a.clave.equals(clave)).findFirst();
        }

        /** Etiqueta del periodo al que pertenece el instante (ya en hora de Bogotá). */
        public String etiqueta(LocalDateTime t) {
            int anio = t.getYear();
            int mes = t.getMonthValue();
            return switch (this) {
                case HORA -> String.format("%s %02dh", t.toLocalDate(), t.getHour());
                case DIA -> t.toLocalDate().toString();
                case SEMANA -> String.format("%d-W%02d", t.get(IsoFields.WEEK_BASED_YEAR), t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
                case MES -> String.format("%d-%02d", anio, mes);
                case BIMESTRE -> anio + "-B" + ((mes - 1) / 2 + 1);
                case TRIMESTRE -> anio + "-T" + ((mes - 1) / 3 + 1);
                case SEMESTRE -> anio + "-S" + (mes <= 6 ? 1 : 2);
                case ANIO -> String.valueOf(anio);
            };
        }

        public String etiqueta(LocalDate d) {
            return etiqueta(d.atStartOfDay());
        }
    }

    /** Rango resuelto, con los dos extremos incluidos. */
    public record Rango(LocalDate desde, LocalDate hasta) {

        public long dias() {
            return hasta.toEpochDay() - desde.toEpochDay() + 1;
        }
    }

    /** Preset del rango: el encabezado muestra siempre las fechas resueltas, no el nombre. */
    public record Preset(String clave, String nombre, LocalDate desde, LocalDate hasta) {
    }

    /**
     * Presets del rango para hoy. El año de contrato va de la fecha de inicio a la víspera de su aniversario; antes del inicio no se
     * ofrece.
     */
    public static List<Preset> presets(LocalDate hoy, LocalDate inicioContrato) {
        List<Preset> l = new ArrayList<>();
        l.add(new Preset("hoy", "Hoy", hoy, hoy));
        l.add(new Preset("ayer", "Ayer", hoy.minusDays(1), hoy.minusDays(1)));
        l.add(new Preset("ultimos_7", "Últimos 7 días", hoy.minusDays(6), hoy));
        l.add(new Preset("semana_actual", "Semana actual", hoy.with(DayOfWeek.MONDAY), hoy));
        l.add(new Preset("mes_actual", "Mes actual", hoy.withDayOfMonth(1), hoy));
        LocalDate mesAnterior = hoy.withDayOfMonth(1).minusMonths(1);
        l.add(new Preset("mes_anterior", "Mes anterior", mesAnterior, mesAnterior.plusMonths(1).minusDays(1)));
        l.add(new Preset("trimestre_actual", "Trimestre actual", hoy.withMonth((hoy.getMonthValue() - 1) / 3 * 3 + 1).withDayOfMonth(1), hoy));
        l.add(new Preset("semestre_actual", "Semestre actual", hoy.withMonth(hoy.getMonthValue() <= 6 ? 1 : 7).withDayOfMonth(1), hoy));
        l.add(new Preset("anio_actual", "Año actual", hoy.withDayOfYear(1), hoy));
        if (inicioContrato != null && !hoy.isBefore(inicioContrato)) {
            // Aniversario n contado siempre desde el inicio: un 29 de febrero vuelve a serlo en los años bisiestos
            int n = 0;
            while (!inicioContrato.plusYears(n + 1L).isAfter(hoy)) {
                n++;
            }
            LocalDate inicio = inicioContrato.plusYears(n);
            l.add(new Preset("anio_contrato", "Año de contrato vigente", inicio, hoy));
        }
        return l;
    }
}
