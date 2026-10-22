package co.com.agsamericas.digsa.reglas;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puntaje de riesgo por ESM de dispensación (docs/especificaciones/riesgo-tablero; decisión 14, 2026-10-20 14.1 y 14.3; Anexo 3
 * p.12). Una regla explicable, no un modelo: cada factor con su peso y su tope a la vista. Máximo 100 puntos:
 * <ul>
 * <li>Incumplimiento: % de No cumple sobre lo calificado (C + NC) en la última visita completa cerrada.</li>
 * <li>Recurrencia: hallazgos de la última visita que ya eran hallazgo en la anterior (hasta 5).</li>
 * <li>Intervención inmediata: hallazgos marcados de intervención inmediata (hasta 3).</li>
 * <li>Planes vencidos: la mitad del peso con el plan del OPL vencido, más cada acción vencida sin completar (hasta 5).</li>
 * <li>Incidencias de la DIGSA (nuevo, 14.1): incidencias vigentes del punto (hasta 3).</li>
 * </ul>
 * «Días sin visita» sale del puntaje (14.1): queda como dato y como criterio de la agenda. Los pesos y umbrales los edita el coordinador
 * (propuesta AGS pendiente de aprobación de la DIGSA).
 */
public final class Riesgo {

    public static final List<String> FACTORES = List.of("incumplimiento", "recurrencia", "inmediata", "planes", "incidencias");
    public static final Map<String, String> NOMBRES = Map.of("incumplimiento", "Incumplimiento", "recurrencia", "Hallazgos recurrentes", "inmediata",
            "Intervención inmediata", "planes", "Planes vencidos", "incidencias", "Incidencias de la DIGSA");
    /** Propuesta inicial: los 15 puntos de «días sin visita» pasan a incidencias (duda B1). */
    public static final Pesos PESOS_INICIALES = new Pesos(Map.of("incumplimiento", 30, "recurrencia", 20, "inmediata", 15, "planes", 20, "incidencias", 15), 60, 30);

    private Riesgo() {
    }

    /** Pesos por factor (suman 100) y umbrales de nivel (alto > medio > 0). */
    public record Pesos(Map<String, Integer> pesos, int alto, int medio) {
        public int peso(String factor) {
            return pesos.getOrDefault(factor, 0);
        }

        /** El motivo si no son válidos; null si sirven. */
        public String problema() {
            for (String f : FACTORES) {
                Integer p = pesos.get(f);
                if (p == null || p < 0 || p > 100) {
                    return "El peso de «" + NOMBRES.get(f) + "» debe estar entre 0 y 100.";
                }
            }
            int suma = FACTORES.stream().mapToInt(this::peso).sum();
            if (suma != 100) {
                return "Los pesos deben sumar 100 (suman " + suma + ").";
            }
            if (!(alto > medio && medio > 0 && alto <= 100)) {
                return "Los umbrales deben cumplir 100 ≥ alto > medio > 0.";
            }
            return null;
        }
    }

    /** Datos crudos de un ESM. */
    public record Datos(int c, int nc, int recurrentes, int inmediata, boolean planOplVencido, int accionesVencidas, int incidencias) {
    }

    public record Puntaje(int total, String nivel, Map<String, Double> factores) {
    }

    public static Puntaje puntaje(Datos d, Pesos p) {
        int calificado = d.c() + d.nc();
        Map<String, Double> f = new LinkedHashMap<>();
        f.put("incumplimiento", calificado == 0 ? 0.0 : (double) p.peso("incumplimiento") * d.nc() / calificado);
        f.put("recurrencia", Math.min(5, d.recurrentes()) * p.peso("recurrencia") / 5.0);
        f.put("inmediata", Math.min(3, d.inmediata()) * p.peso("inmediata") / 3.0);
        f.put("planes", (d.planOplVencido() ? p.peso("planes") / 2.0 : 0) + Math.min(5, d.accionesVencidas()) * p.peso("planes") / 10.0);
        f.put("incidencias", Math.min(3, d.incidencias()) * p.peso("incidencias") / 3.0);
        f.replaceAll((k, v) -> redondear(Math.min(v, p.peso(k))));
        int total = Math.min(100, (int) Math.round(f.values().stream().mapToDouble(Double::doubleValue).sum()));
        return new Puntaje(total, nivel(total, p), f);
    }

    public static String nivel(int total, Pesos p) {
        return total >= p.alto() ? "alto" : total >= p.medio() ? "medio" : "bajo";
    }

    /** Claves que se repiten entre la última visita cerrada y la anterior. */
    public static int recurrentes(java.util.Collection<String> ultima, java.util.Collection<String> anterior) {
        java.util.Set<String> a = new java.util.HashSet<>(ultima);
        a.retainAll(new java.util.HashSet<>(anterior));
        return a.size();
    }

    public record Agregado(int esm, double promedio, Map<String, Integer> niveles, Map<String, Double> factores) {
    }

    /** Agregado por grupo (país, fuerza): promedio, ESM por nivel y el promedio de cada factor, con 2 decimales (VCO P27). */
    public static Agregado agregar(List<Puntaje> puntajes) {
        int n = puntajes.size();
        Map<String, Integer> niveles = new LinkedHashMap<>(Map.of("alto", 0, "medio", 0, "bajo", 0));
        Map<String, Double> factores = new LinkedHashMap<>();
        FACTORES.forEach(f -> factores.put(f, 0.0));
        for (Puntaje p : puntajes) {
            niveles.merge(p.nivel(), 1, Integer::sum);
            p.factores().forEach((k, v) -> factores.merge(k, v, Double::sum));
        }
        double promedio = n == 0 ? 0.0 : redondear(puntajes.stream().mapToInt(Puntaje::total).sum() / (double) n);
        factores.replaceAll((k, v) -> n == 0 ? 0.0 : redondear(v / n));
        Map<String, Integer> ordenados = new LinkedHashMap<>();
        List.of("alto", "medio", "bajo").forEach(k -> ordenados.put(k, niveles.get(k)));
        return new Agregado(n, promedio, ordenados, factores);
    }

    /** El factor que más pesa en el agregado, o «—» si todos son 0. */
    public static String factorPrincipal(Map<String, Double> factores) {
        return factores.entrySet().stream().filter(e -> e.getValue() > 0).max(Map.Entry.comparingByValue()).map(e -> NOMBRES.get(e.getKey())).orElse("—");
    }

    static double redondear(double x) {
        return Math.round(x * 100.0) / 100.0;
    }
}
