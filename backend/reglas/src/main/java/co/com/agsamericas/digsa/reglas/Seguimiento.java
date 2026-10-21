package co.com.agsamericas.digsa.reglas;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Reglas del seguimiento al plan de mejoramiento (docs/especificaciones/seguimiento; app/Support/Seguimiento.php): acciones abiertas,
 * fecha del seguimiento, ordinales y prórrogas.
 */
public final class Seguimiento {

    /** Una acción del plan está cerrada solo en Completado o Cancelado. */
    public static final Set<String> ESTADOS_CERRADOS = Set.of("Completado", "Cancelado");
    /** Mínimo de la justificación de una prórroga, sin contar los espacios alrededor; las tildes cuentan como una letra. */
    public static final int MIN_JUSTIFICACION = 10;

    private static final String[] ORDINALES = {"Primera", "Segunda", "Tercera", "Cuarta", "Quinta"};

    private Seguimiento() {
    }

    /** Acción del plan: su clave, estado y fecha fin. */
    public record Accion(String clave, String estado, LocalDate fin) {
        public boolean abierta() {
            return !ESTADOS_CERRADOS.contains(estado == null ? "Pendiente" : estado);
        }
    }

    public static List<Accion> abiertas(Collection<Accion> acciones) {
        return acciones.stream().filter(Objects::nonNull).filter(Accion::abierta).toList();
    }

    /** Abiertas sin fecha fin: impiden enviar el acta. */
    public static List<String> sinFecha(Collection<Accion> acciones) {
        return abiertas(acciones).stream().filter(a -> a.fin() == null).map(Accion::clave).toList();
    }

    /**
     * Fecha del seguimiento: la fecha fin más próxima de las acciones abiertas; si ya pasó (o es hoy), el siguiente día hábil.
     * Vacío si no hay acciones abiertas con fecha.
     */
    public static Optional<LocalDate> fecha(Collection<Accion> acciones, LocalDate hoy, Plazos plazos) {
        return abiertas(acciones).stream().map(Accion::fin).filter(Objects::nonNull).min(LocalDate::compareTo)
                .map(f -> f.isAfter(hoy) ? f : plazos.habilSiguiente(hoy));
    }

    /** «Primera» … «Quinta»; después «6.ª», «7.ª»… */
    public static String ordinal(int n) {
        return n >= 1 && n <= ORDINALES.length ? ORDINALES[n - 1] : n + ".ª";
    }

    /** Es prórroga si la acción sigue abierta y su fecha fin válida pasa a ser posterior a la de la visita anterior. */
    public static boolean esProrroga(Accion ahora, LocalDate finAnterior) {
        return ahora != null && ahora.abierta() && finAnterior != null && ahora.fin() != null && ahora.fin().isAfter(finAnterior);
    }

    public static boolean justificacionValida(String texto) {
        return texto != null && texto.strip().codePointCount(0, texto.strip().length()) >= MIN_JUSTIFICACION;
    }
}
