package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.Criterio;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.MesPaciente;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.Resultado;
import co.com.agsamericas.digsa.reglas.AnaliticaIncremental.Plan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Recálculo incremental (A7.4, AN.23): ventanas de cada criterio y meses a recalcular y a leer. */
class AnaliticaIncrementalTest {

    private static int mes(int anio, int m) {
        return AnaliticaCohortes.ordinal(anio, m);
    }

    private static TreeSet<Integer> meses(int... m) {
        TreeSet<Integer> s = new TreeSet<>();
        for (int x : m) {
            s.add(x);
        }
        return s;
    }

    @Test
    @DisplayName("ventana hacia atrás de las doce cohortes predefinidas: 11 meses las de 12, 5 polimedicados, 0 biológicos, poliformulados, listas de 1 mes y GCF")
    void ventanasDeLasPredefinidas() {
        Map<String, Integer> esperado = Map.ofEntries(Map.entry("alto_costo", 11), Map.entry("polimedicados", 5), Map.entry("renal", 11),
                Map.entry("vih", 11), Map.entry("biologicos", 0), Map.entry("huerfanas", 11), Map.entry("poliformulados", 0),
                Map.entry("pluripatologicos", 11), Map.entry("control_especial", 0), Map.entry("estrecho_margen", 0), Map.entry("gcf", 0),
                Map.entry("grc", 11));
        for (AnaliticaCohortes.Predefinida p : AnaliticaCohortes.PREDEFINIDAS) {
            assertThat(AnaliticaIncremental.mesesAtras(AnaliticaCohortes.leer(p.criterio()))).as(p.codigo()).isEqualTo(esperado.get(p.codigo()));
        }
        assertThat(AnaliticaIncremental.mesesDeDias(30)).isEqualTo(2);
        assertThat(AnaliticaIncremental.mesesDeDias(28)).isEqualTo(1);
        assertThat(AnaliticaIncremental.mesesDeDias(0)).isZero();
    }

    @Test
    @DisplayName("mes nuevo al final (2026-01 tras un corte hasta 2025-12, gracia 2, ventana 11, días 30): recalcula 2025-11 a 2026-01 y lee desde 2024-09")
    void mesNuevoAlFinal() {
        Plan p = AnaliticaIncremental.plan(meses(mes(2026, 1)), 11, 2, 2, mes(2025, 12), mes(2026, 1));
        assertThat(p.texto()).isEqualTo("2025-11 a 2026-01");
        assertThat(p.desde()).isEqualTo(mes(2025, 11));
        assertThat(p.hasta()).isEqualTo(mes(2026, 1));
        assertThat(p.leerDesde()).isEqualTo(mes(2025, 11) - 14);
        assertThat(p.leerHasta()).isEqualTo(mes(2026, 3));
        assertThat(p.contiene(mes(2025, 10))).isFalse();
        assertThat(p.contiene(mes(2025, 11))).isTrue();
    }

    @Test
    @DisplayName("agregar a un mes intermedio (2025-06, corte hasta 2026-01): recalcula desde 2025-04 hasta el último mes (la ventana de 12 meses lo alcanza)")
    void mesIntermedio() {
        Plan p = AnaliticaIncremental.plan(meses(mes(2025, 6)), 11, 2, 2, mes(2026, 1), mes(2026, 1));
        assertThat(p.texto()).isEqualTo("2025-04 a 2026-01");
        // Sin ventanas largas (solo biológicos, GCF): solo los meses del ingreso y la salida alrededor del cambio
        Plan corto = AnaliticaIncremental.plan(meses(mes(2025, 6)), 0, 2, 0, mes(2026, 1), mes(2026, 1));
        assertThat(corto.texto()).isEqualTo("2025-04 a 2025-09");
        assertThat(corto.leerDesde()).isEqualTo(mes(2025, 1));
        assertThat(corto.leerHasta()).isEqualTo(mes(2025, 11));
    }

    @Test
    @DisplayName("con historia larga (2023-01 a 2025-12) un mes nuevo lee 19 meses (2024-09 a 2026-03), no los 37 de la historia")
    void historiaLarga() {
        Plan p = AnaliticaIncremental.plan(meses(mes(2026, 1)), 11, 2, 2, mes(2025, 12), mes(2026, 1));
        assertThat(p.leerHasta() - p.leerDesde() + 1).isEqualTo(19);
        assertThat(p.leerDesde()).isGreaterThan(mes(2023, 1));
    }

    @Test
    @DisplayName("sin meses cambiados ni cambio del último mes: nada que recalcular; solo cambia el último mes: los meses de la censura de salidas")
    void sinCambios() {
        assertThat(AnaliticaIncremental.plan(meses(), 11, 2, 2, mes(2025, 12), mes(2025, 12)).vacio()).isTrue();
        assertThat(AnaliticaIncremental.plan(meses(), 11, 2, 2, mes(2025, 12), mes(2025, 12)).texto()).isEqualTo("ninguno");
        Plan p = AnaliticaIncremental.plan(meses(), 11, 2, 2, mes(2025, 12), mes(2026, 2));
        assertThat(p.texto()).isEqualTo("2025-11 a 2025-12");
    }

    @Test
    @DisplayName("reemplazar un periodo antiguo (2025-02 y 2025-03): recalcula desde 2024-12")
    void reemplazo() {
        Plan p = AnaliticaIncremental.plan(meses(mes(2025, 2), mes(2025, 3)), 5, 2, 2, mes(2025, 12), mes(2025, 12));
        assertThat(p.texto()).isEqualTo("2024-12 a 2025-11");
    }

    /**
     * La regla de las ventanas: evaluar la cohorte con la historia recortada (desde el mes s) da la misma membresía y el mismo grupo que con
     * toda la historia en los meses ≥ s + mesesAtras. Con historias y criterios al azar (las doce predefinidas).
     */
    @Test
    @DisplayName("recortar la historia antes de la ventana no cambia la membresía ni el grupo de los meses que la ventana cubre (12 cohortes × 300 pacientes)")
    void recorteDeLaHistoria() {
        Random azar = new Random(20261031L);
        List<String> atc2 = List.of("A10", "C09", "L04", "N03");
        for (AnaliticaCohortes.Predefinida p : AnaliticaCohortes.PREDEFINIDAS) {
            Criterio c = AnaliticaCohortes.leer(p.criterio());
            int l = AnaliticaIncremental.mesesAtras(c);
            int atomos = AnaliticaCohortes.atomos(c).size();
            for (int paciente = 0; paciente < 300; paciente++) {
                List<MesPaciente> todos = new ArrayList<>();
                for (int m = mes(2023, 1); m <= mes(2025, 12); m++) {
                    if (azar.nextInt(10) < 6) {
                        long mascara = atomos == 0 ? 0 : azar.nextLong() & ((1L << atomos) - 1) & (azar.nextBoolean() ? -1L : 0L);
                        Set<String> cron = new HashSet<>();
                        for (String k : List.of("I10", "E11", "N18", "J44")) {
                            if (azar.nextInt(6) == 0) {
                                cron.add(k);
                            }
                        }
                        Map<String, Long> g = new HashMap<>();
                        g.put(atc2.get(azar.nextInt(atc2.size())), (long) azar.nextInt(50_000));
                        todos.add(new MesPaciente(m, mascara, azar.nextInt(8), azar.nextInt(3) == 0 ? 0 : azar.nextInt(400_000), cron, g));
                    }
                }
                Resultado completo = AnaliticaCohortes.evaluar(c, todos, mes -> 900_000L);
                for (int s : new int[] {mes(2023, 7), mes(2024, 2), mes(2024, 11)}) {
                    int desde = s;
                    List<MesPaciente> recortado = todos.stream().filter(x -> x.mes() >= desde).toList();
                    Resultado parcial = AnaliticaCohortes.evaluar(c, recortado, mes -> 900_000L);
                    int off = todos.size() - recortado.size();
                    for (int i = 0; i < recortado.size(); i++) {
                        if (recortado.get(i).mes() >= s + l) {
                            assertThat(parcial.miembro()[i]).as(p.codigo() + " mes " + recortado.get(i).mes()).isEqualTo(completo.miembro()[i + off]);
                            assertThat(parcial.grupo()[i]).as(p.codigo() + " grupo").isEqualTo(completo.grupo()[i + off]);
                        }
                    }
                }
            }
        }
    }
}
