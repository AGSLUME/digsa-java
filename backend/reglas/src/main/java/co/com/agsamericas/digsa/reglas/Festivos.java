package co.com.agsamericas.digsa.reglas;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Festivos de Colombia para cualquier año (decisión del usuario, 2026-10-14: la aplicación siempre tiene los festivos al día).
 *
 * <p>Ley 51 de 1983 (ley Emiliani):
 * <ul>
 *   <li>Fijos: 1 ene, 1 may, 20 jul, 7 ago, 8 dic, 25 dic; Jueves y Viernes Santo.</li>
 *   <li>Se trasladan al lunes siguiente si no caen en lunes: 6 ene, 19 mar, 29 jun, 15 ago, 12 oct, 1 nov, 11 nov;
 *       Ascensión (Pascua + 39), Corpus Christi (Pascua + 60) y Sagrado Corazón (Pascua + 68).</li>
 * </ul>
 * La tabla par_festivos queda solo para ajustes (por ejemplo, un festivo decretado por una ley nueva).
 */
public final class Festivos {

    private static final Map<Integer, Set<LocalDate>> CACHE = new ConcurrentHashMap<>();
    private static final int[][] FIJOS = {{1, 1}, {5, 1}, {7, 20}, {8, 7}, {12, 8}, {12, 25}};
    private static final int[][] TRASLADABLES = {{1, 6}, {3, 19}, {6, 29}, {8, 15}, {10, 12}, {11, 1}, {11, 11}};
    private static final int[] DIAS_DESDE_PASCUA_TRASLADABLES = {39, 60, 68};

    private Festivos() {
    }

    /** Fechas festivas del año, ordenadas y sin repetir (dos festivos pueden caer el mismo lunes). */
    public static List<LocalDate> delAnio(int anio) {
        return List.copyOf(conjunto(anio));
    }

    public static boolean esFestivo(LocalDate fecha) {
        return conjunto(fecha.getYear()).contains(fecha);
    }

    /** Domingo de Pascua (algoritmo de Meeus/Jones/Butcher, calendario gregoriano). */
    public static LocalDate pascua(int anio) {
        int a = anio % 19;
        int b = anio / 100;
        int c = anio % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int mes = (h + l - 7 * m + 114) / 31;
        int dia = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(anio, mes, dia);
    }

    private static Set<LocalDate> conjunto(int anio) {
        return CACHE.computeIfAbsent(anio, Festivos::calcular);
    }

    private static Set<LocalDate> calcular(int anio) {
        TreeSet<LocalDate> f = new TreeSet<>();
        for (int[] md : FIJOS) {
            f.add(LocalDate.of(anio, md[0], md[1]));
        }
        for (int[] md : TRASLADABLES) {
            f.add(alLunes(LocalDate.of(anio, md[0], md[1])));
        }
        LocalDate pascua = pascua(anio);
        f.add(pascua.minusDays(3)); // Jueves Santo
        f.add(pascua.minusDays(2)); // Viernes Santo
        for (int dias : DIAS_DESDE_PASCUA_TRASLADABLES) {
            f.add(alLunes(pascua.plusDays(dias)));
        }
        return java.util.Collections.unmodifiableSortedSet(f);
    }

    private static LocalDate alLunes(LocalDate d) {
        return d.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }
}
