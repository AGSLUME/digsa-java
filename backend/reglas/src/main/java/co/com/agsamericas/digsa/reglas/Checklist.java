package co.com.agsamericas.digsa.reglas;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reglas del formulario de la visita (docs/especificaciones/checklist): calificaciones válidas, «No aplica» en las escalas propias,
 * qué falta para enviar el acta y el resumen de resultados que calcula el servidor.
 */
public final class Checklist {

    public static final Set<String> CALIFICACIONES = Set.of("C", "NC", "NA");
    public static final Map<String, String> NOMBRES = Map.of("C", "Cumple", "NC", "No cumple", "NA", "No aplica");
    public static final int MAX_HALLAZGO = 2000;

    /** Ítem del formulario de la visita: su componente, la parte a la que pertenece y si su escala admite «No aplica». */
    public record Item(String codigo, String componente, String parte, boolean escalaPropia, boolean conCondicion) {
        /** En los componentes con escala propia (C00 habilitación), «No aplica» solo en los ítems con condición. */
        public boolean admiteNoAplica() {
            return !escalaPropia || conCondicion;
        }
    }

    public record Respuesta(String calificacion, String hallazgo) {
    }

    public record Resumen(int c, int nc, int na, int pendientes, int hallazgos, Map<String, int[]> porComponente) {
    }

    private Checklist() {
    }

    /** Problema de una respuesta, o vacío si es válida. */
    public static java.util.Optional<String> problema(Item item, Respuesta r) {
        String cal = r.calificacion();
        if (cal != null && !cal.isEmpty() && !CALIFICACIONES.contains(cal)) {
            return java.util.Optional.of(item.codigo() + ": la calificación debe ser C, NC o NA.");
        }
        if ("NA".equals(cal) && !item.admiteNoAplica()) {
            return java.util.Optional.of(item.codigo() + ": «No aplica» solo se admite en las validaciones de habilitación con condición.");
        }
        if (r.hallazgo() != null && r.hallazgo().length() > MAX_HALLAZGO) {
            return java.util.Optional.of(item.codigo() + ": el hallazgo tiene máximo " + MAX_HALLAZGO + " caracteres.");
        }
        return java.util.Optional.empty();
    }

    /**
     * Lo que impide enviar el acta: ítems sin calificar y «No cumple» sin hallazgo descrito (Anexo 5, prueba 12: hallazgos).
     *
     * @return mensajes, vacío si se puede enviar
     */
    public static List<String> faltantes(List<Item> items, Map<String, Respuesta> respuestas) {
        List<String> sinCalificar = new ArrayList<>();
        List<String> sinHallazgo = new ArrayList<>();
        for (Item i : items) {
            Respuesta r = respuestas.get(i.codigo());
            String cal = r == null ? null : r.calificacion();
            if (cal == null || cal.isEmpty()) {
                sinCalificar.add(i.codigo());
            } else if ("NC".equals(cal) && (r.hallazgo() == null || r.hallazgo().isBlank())) {
                sinHallazgo.add(i.codigo());
            }
        }
        List<String> m = new ArrayList<>();
        if (!sinCalificar.isEmpty()) {
            m.add("Calificar todo: quedan " + sinCalificar.size() + " ítems sin calificar (" + resumir(sinCalificar) + ").");
        }
        if (!sinHallazgo.isEmpty()) {
            m.add("Describa el hallazgo de los ítems que no cumplen: " + resumir(sinHallazgo) + ".");
        }
        return m;
    }

    private static String resumir(List<String> codigos) {
        return codigos.size() <= 10 ? String.join(", ", codigos) : String.join(", ", codigos.subList(0, 10)) + " y " + (codigos.size() - 10) + " más";
    }

    /** Resumen de resultados (total y por componente: c, nc, na, pendientes). Hallazgos = ítems que no cumplen. */
    public static Resumen resumen(List<Item> items, Map<String, Respuesta> respuestas) {
        int c = 0;
        int nc = 0;
        int na = 0;
        int pend = 0;
        Map<String, int[]> por = new LinkedHashMap<>();
        for (Item i : items) {
            int[] x = por.computeIfAbsent(i.componente(), k -> new int[4]);
            Respuesta r = respuestas.get(i.codigo());
            String cal = r == null ? "" : r.calificacion() == null ? "" : r.calificacion();
            switch (cal) {
                case "C" -> {
                    c++;
                    x[0]++;
                }
                case "NC" -> {
                    nc++;
                    x[1]++;
                }
                case "NA" -> {
                    na++;
                    x[2]++;
                }
                default -> {
                    pend++;
                    x[3]++;
                }
            }
        }
        return new Resumen(c, nc, na, pend, nc, por);
    }

    /** Porcentaje de cumplimiento: C / (C + NC); los «No aplica» no cuentan. Sin calificados = null. */
    public static Double cumplimiento(int c, int nc) {
        return c + nc == 0 ? null : Math.round(1000.0 * c / (c + nc)) / 10.0;
    }
}
