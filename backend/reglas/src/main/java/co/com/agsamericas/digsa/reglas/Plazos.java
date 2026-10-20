package co.com.agsamericas.digsa.reglas;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Días hábiles y alertas de la agenda (equivalente de app/Support/Plazos.php). Reglas (par_parametros; los valores por defecto
 * están en {@link #REGLAS}):
 * <ul>
 *   <li>notif_dias_opl: notificación a DISANES y OPL con 15 días calendario (Anexo 3 p.9); si el día no es hábil, el hábil anterior.</li>
 *   <li>notif_habiles_modulo: requerimiento y seguimiento a plan, N días hábiles (parámetro, 3).</li>
 *   <li>aviso_umbral_opl / aviso_umbral_modulo: desde cuántos días antes del límite la alerta es advertencia (definición interna).</li>
 *   <li>necesidad_habiles: la visita a necesidad se hace en máximo 5 días hábiles desde la solicitud (Anexo 3 p.14; decisión 12).</li>
 * </ul>
 */
public final class Plazos {

    public static final Map<String, Integer> REGLAS = Map.of("notif_dias_opl", 15, "notif_habiles_modulo", 3, "aviso_umbral_opl", 5,
            "aviso_umbral_modulo", 1, "necesidad_habiles", 5);

    /** Nivel de una alerta: info, warn (advertencia) o bad (crítica). */
    public enum Nivel {
        INFO, WARN, BAD;

        public String valor() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record Alerta(Nivel nivel, String texto) {
    }

    /** Datos de la visita, su tipo, el punto y el equipo que necesitan las alertas. */
    public record Visita(LocalDate fecha, String estado, LocalDate fechaSolicitud) {
    }

    public record Tipo(String codigo, String aviso, String modalidad, String fuente) {
    }

    public record Punto(String nombre, String unidad, String modalidad, boolean exigeQf) {
    }

    /** @param tieneQf algún integrante del equipo tiene el rol químico farmacéutico (decisión 2a) */
    public record Equipo(String nombre, boolean tieneQf) {
    }

    private static final DateTimeFormatter LARGA = DateTimeFormatter.ofPattern("EEE d 'de' MMM 'de' yyyy", Locale.forLanguageTag("es-CO"));

    private final Set<LocalDate> ajustes;
    private final Map<String, Integer> reglas;

    public Plazos() {
        this(Set.of(), Map.of());
    }

    /** @param ajustes festivos adicionales (par_festivos) @param reglas valores de los parámetros que sobreescriben los de defecto */
    public Plazos(Set<LocalDate> ajustes, Map<String, Integer> reglas) {
        this.ajustes = Set.copyOf(ajustes);
        Map<String, Integer> r = new HashMap<>(REGLAS);
        r.putAll(reglas);
        this.reglas = Map.copyOf(r);
    }

    public int regla(String clave) {
        return reglas.get(clave);
    }

    public boolean esHabil(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !Festivos.esFestivo(d) && !ajustes.contains(d);
    }

    /** N días hábiles después de la fecha (sin contarla). */
    public LocalDate sumarHabiles(LocalDate fecha, int n) {
        LocalDate d = fecha;
        for (int k = 0; k < n; ) {
            d = d.plusDays(1);
            if (esHabil(d)) {
                k++;
            }
        }
        return d;
    }

    /** N días hábiles antes de la fecha (sin contarla). */
    public LocalDate restarHabiles(LocalDate fecha, int n) {
        LocalDate d = fecha;
        for (int k = 0; k < n; ) {
            d = d.minusDays(1);
            if (esHabil(d)) {
                k++;
            }
        }
        return d;
    }

    public LocalDate habilAnterior(LocalDate d) {
        LocalDate x = d;
        while (!esHabil(x)) {
            x = x.minusDays(1);
        }
        return x;
    }

    public LocalDate habilSiguiente(LocalDate d) {
        LocalDate x = d;
        while (!esHabil(x)) {
            x = x.plusDays(1);
        }
        return x;
    }

    /** Fecha límite para notificar, o null si el tipo de visita no lleva aviso. */
    public LocalDate limiteNotificacion(String aviso, LocalDate fechaVisita) {
        return switch (aviso) {
            case "opl" -> habilAnterior(fechaVisita.minusDays(reglas.get("notif_dias_opl")));
            case "modulo" -> restarHabiles(fechaVisita, reglas.get("notif_habiles_modulo"));
            default -> null;
        };
    }

    /** Visita a necesidad: máximo N días hábiles después de la solicitud (Anexo 3 p.14; decisión 12). */
    public LocalDate limiteNecesidad(LocalDate fechaSolicitud) {
        return sumarHabiles(fechaSolicitud, reglas.get("necesidad_habiles"));
    }

    public static String fechaLarga(LocalDate d) {
        return d.format(LARGA);
    }

    /** Alertas de una visita abierta (no cerrada ni cancelada). */
    public List<Alerta> alertas(Visita v, Tipo tipo, Punto punto, Equipo equipo, LocalDate hoy) {
        List<Alerta> m = new ArrayList<>();
        if (v.fecha() == null || hoy == null || "cerrada".equals(v.estado()) || "cancelada".equals(v.estado())) {
            return m;
        }
        boolean porNotificar = "programada".equals(v.estado()) || "reprogramada".equals(v.estado());
        LocalDate lim = porNotificar ? limiteNotificacion(tipo.aviso(), v.fecha()) : null;
        if (lim != null) {
            boolean opl = "opl".equals(tipo.aviso());
            long dias = ChronoUnit.DAYS.between(hoy, lim);
            String regla = opl ? reglas.get("notif_dias_opl") + " días de antelación, día hábil; Anexo 3 p.9"
                    : reglas.get("notif_habiles_modulo") + " días hábiles de antelación; parámetro de la agenda";
            int umbral = opl ? reglas.get("aviso_umbral_opl") : reglas.get("aviso_umbral_modulo");
            if (dias < 0) {
                m.add(new Alerta(Nivel.BAD, "Sin notificar y ya no se cumple la antelación mínima: debía notificarse a más tardar el "
                        + fechaLarga(lim) + " (" + regla + ")."));
            } else {
                String cuando = dias == 0 ? "hoy" : dias == 1 ? "falta 1 día" : "faltan " + dias + " días";
                m.add(new Alerta(dias <= umbral ? Nivel.WARN : Nivel.INFO,
                        "Notificar a más tardar el " + fechaLarga(lim) + " (" + cuando + "; " + regla + ")."));
            }
        }
        if ("necesidad".equals(tipo.codigo()) && v.fechaSolicitud() != null) {
            LocalDate limNec = limiteNecesidad(v.fechaSolicitud());
            String regla = reglas.get("necesidad_habiles") + " días hábiles después de la solicitud del " + fechaLarga(v.fechaSolicitud())
                    + "; Anexo 3 p.14";
            if (v.fecha().isAfter(limNec)) {
                m.add(new Alerta(Nivel.BAD, "La visita a necesidad está programada después del límite: debía hacerse a más tardar el "
                        + fechaLarga(limNec) + " (" + regla + ")."));
            }
            if (!"ejecucion".equals(v.estado()) && hoy.isAfter(limNec)) {
                m.add(new Alerta(Nivel.BAD, "El plazo de la visita a necesidad venció el " + fechaLarga(limNec) + " y no se ha iniciado (" + regla + ")."));
            }
        }
        if (v.fecha().isBefore(hoy) && !"ejecucion".equals(v.estado())) {
            m.add(new Alerta(Nivel.WARN, "La fecha programada (" + fechaLarga(v.fecha()) + ") ya pasó y la visita no se ha iniciado."));
        }
        if (punto != null && equipo != null && punto.exigeQf() && !equipo.tieneQf()) {
            m.add(new Alerta(Nivel.WARN, "En " + punto.nombre() + " el equipo debe incluir un químico farmacéutico (Anexo 3 p.9). Ningún integrante ("
                    + equipo.nombre() + ") tiene ese rol."));
        }
        if (tipo.modalidad() != null && punto != null && "ESM".equals(punto.unidad()) && !tipo.modalidad().equals(punto.modalidad())) {
            m.add(new Alerta(Nivel.WARN, "Este tipo de visita aplica a ESM de modalidad " + tipo.modalidad().toLowerCase(Locale.ROOT)
                    + " y el punto es de modalidad " + (punto.modalidad() == null ? "sin dato" : punto.modalidad().toLowerCase(Locale.ROOT))
                    + " (" + tipo.fuente() + ")."));
        }
        return m;
    }

    /** Nivel más grave: bad, warn o vacío. */
    public static String nivel(List<Alerta> alertas) {
        if (alertas.stream().anyMatch(a -> a.nivel() == Nivel.BAD)) {
            return "bad";
        }
        return alertas.stream().anyMatch(a -> a.nivel() == Nivel.WARN) ? "warn" : "";
    }
}
