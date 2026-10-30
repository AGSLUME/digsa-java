package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import co.com.agsamericas.digsa.reglas.AnaliticaPrediccion.Evaluado;
import co.com.agsamericas.digsa.reglas.AnaliticaPrediccion.MesProyectado;
import co.com.agsamericas.digsa.reglas.AnaliticaPrediccion.Metodo;
import co.com.agsamericas.digsa.reglas.AnaliticaPrediccion.Resultado;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Proyección de la analítica (docs/especificaciones/analitica-cohortes-alertas): historia, métodos, backtesting, IC y reproducibilidad. */
class AnaliticaPrediccionTest {

    private static final YearMonth ENE_2025 = YearMonth.of(2025, 1);

    /** Serie desde {@code inicio} con los valores dados, mes a mes. */
    private static Map<YearMonth, BigDecimal> serie(YearMonth inicio, double... v) {
        Map<YearMonth, BigDecimal> r = new LinkedHashMap<>();
        for (int i = 0; i < v.length; i++) {
            r.put(inicio.plusMonths(i), BigDecimal.valueOf(v[i]));
        }
        return r;
    }

    /** Corte en el último día del mes: todos los meses completos. */
    private static LocalDate cierre(YearMonth m) {
        return m.atEndOfMonth();
    }

    private static double d(BigDecimal b) {
        return b.doubleValue();
    }

    @Test
    @DisplayName("tendencia lineal 100, 110, …, 210 (12 meses) ⇒ regresión u Holt con WMAPE ≈ 0, h = 1 en 220 ± 1 e IC angosto")
    void tendenciaLineal() {
        double[] v = new double[12];
        for (int i = 0; i < 12; i++) {
            v[i] = 100 + 10 * i;
        }
        Resultado r = AnaliticaPrediccion.proyectar(serie(ENE_2025, v), cierre(YearMonth.of(2025, 12)));
        assertThat(r.ok()).isTrue();
        assertThat(r.metodo()).isIn(Metodo.REGRESION, Metodo.HOLT);
        assertThat(d(r.wmape())).isCloseTo(0, within(0.01));
        assertThat(r.mesesBacktest()).isEqualTo(3);
        assertThat(r.notas()).contains(AnaliticaPrediccion.NOTA_SIN_ESTACIONAL);
        assertThat(r.proyeccion()).hasSize(6);
        MesProyectado h1 = r.proyeccion().get(0);
        assertThat(h1.mes()).isEqualTo(YearMonth.of(2026, 1));
        assertThat(d(h1.valor())).isCloseTo(220, within(1.0));
        assertThat(d(h1.superior()) - d(h1.inferior())).isLessThan(2);
        assertThat(d(r.proyeccion().get(5).valor())).isCloseTo(270, within(1.0));
        assertThat(r.evaluados()).extracting(Evaluado::metodo).containsExactly(Metodo.REGRESION, Metodo.HOLT);
    }

    @Test
    @DisplayName("corte 21/10/2026 ⇒ entrena hasta septiembre, octubre queda como observado parcial y se proyecta de octubre 2026 a marzo 2027")
    void mesIncompletoFuera() {
        double[] v = new double[22];
        for (int i = 0; i < 22; i++) {
            v[i] = 1000 + 20 * i;
        }
        Map<YearMonth, BigDecimal> s = serie(YearMonth.of(2025, 1), v); // ene-2025 .. oct-2026
        s.put(YearMonth.of(2026, 11), new BigDecimal("999999")); // posterior al corte: se ignora
        Resultado r = AnaliticaPrediccion.proyectar(s, LocalDate.of(2026, 10, 21));
        assertThat(r.desde()).isEqualTo(YearMonth.of(2025, 1));
        assertThat(r.hasta()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(r.mesesHistoria()).isEqualTo(21);
        assertThat(r.parcial().mes()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(r.parcial().valor()).isEqualByComparingTo("1420");
        assertThat(r.proyeccion()).extracting(MesProyectado::mes).containsExactly(YearMonth.of(2026, 10), YearMonth.of(2026, 11),
                YearMonth.of(2026, 12), YearMonth.of(2027, 1), YearMonth.of(2027, 2), YearMonth.of(2027, 3));
        assertThat(d(r.proyeccion().get(0).valor())).isCloseTo(1420, within(1.0));

        Resultado cerrado = AnaliticaPrediccion.proyectar(s, LocalDate.of(2026, 10, 31));
        assertThat(cerrado.hasta()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(cerrado.parcial()).isNull();
    }

    @Test
    @DisplayName("5 meses completos ⇒ historia_insuficiente con «Historia insuficiente: 5 meses (mínimo 6)» y sin proyección")
    void historiaInsuficiente() {
        Resultado r = AnaliticaPrediccion.proyectar(serie(ENE_2025, 10, 20, 30, 40, 50), cierre(YearMonth.of(2025, 5)));
        assertThat(r.estado()).isEqualTo(AnaliticaPrediccion.HISTORIA_INSUFICIENTE);
        assertThat(r.ok()).isFalse();
        assertThat(r.mensaje()).isEqualTo("Historia insuficiente: 5 meses (mínimo 6)");
        assertThat(r.proyeccion()).isEmpty();
        assertThat(r.metodo()).isNull();
        assertThat(r.mesesHistoria()).isEqualTo(5);
        assertThat(r.sha256()).hasSize(64);

        Resultado vacia = AnaliticaPrediccion.proyectar(Map.of(), LocalDate.of(2026, 10, 21));
        assertThat(vacia.mensaje()).isEqualTo("Historia insuficiente: 0 meses (mínimo 6)");
        assertThat(vacia.desde()).isNull();
        assertThat(vacia.parcial().valor()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("7 meses ⇒ backtesting de 1 mes, con la nota visible")
    void backtestCorto() {
        Resultado r = AnaliticaPrediccion.proyectar(serie(ENE_2025, 50, 52, 55, 53, 58, 60, 61), cierre(YearMonth.of(2025, 7)));
        assertThat(r.ok()).isTrue();
        assertThat(r.mesesBacktest()).isEqualTo(1);
        assertThat(r.notas()).contains(AnaliticaPrediccion.NOTA_BACKTEST_CORTO, AnaliticaPrediccion.NOTA_SIN_ESTACIONAL);

        double[] nueve = {50, 52, 55, 53, 58, 60, 61, 63, 64};
        assertThat(AnaliticaPrediccion.proyectar(serie(ENE_2025, nueve), cierre(YearMonth.of(2025, 9))).mesesBacktest()).isEqualTo(3);
    }

    @Test
    @DisplayName("36 meses con patrón estacional claro ⇒ elige Holt-Winters y la proyección sigue el patrón")
    void estacional() {
        double[] v = new double[36];
        for (int t = 0; t < 36; t++) {
            v[t] = patron(t);
        }
        Resultado r = AnaliticaPrediccion.proyectar(serie(ENE_2025, v), cierre(YearMonth.of(2027, 12)), 12);
        assertThat(r.metodo()).isEqualTo(Metodo.HOLT_WINTERS);
        assertThat(r.parametros()).containsKeys("alfa", "beta", "gamma");
        assertThat(r.notas()).doesNotContain(AnaliticaPrediccion.NOTA_SIN_ESTACIONAL);
        assertThat(r.evaluados()).extracting(Evaluado::metodo).containsExactly(Metodo.REGRESION, Metodo.HOLT, Metodo.HOLT_WINTERS);
        Evaluado reg = r.evaluados().get(0);
        assertThat(d(reg.wmape())).isGreaterThan(d(r.wmape()) + 1);
        assertThat(r.proyeccion()).hasSize(12);
        for (MesProyectado m : r.proyeccion()) {
            assertThat(d(m.valor())).as("mes %s", m.mes()).isCloseTo(patron(35 + m.h()), within(1.0));
        }
        // el pico de abril (seno máximo) queda por encima del valle de octubre
        assertThat(r.proyeccion().get(3).valor()).isGreaterThan(r.proyeccion().get(9).valor());
    }

    private static double patron(int t) {
        return 1000 + 5 * t + 200 * Math.sin(2 * Math.PI * t / 12);
    }

    @Test
    @DisplayName("misma serie (en cualquier orden de mapa) y misma versión ⇒ mismo resultado y misma huella; otro valor ⇒ otra huella")
    void mismaEntradaMismaSalida() {
        double[] v = {120, 135, 128, 150, 149, 160, 171, 165, 180, 176, 190, 205, 199, 215};
        Map<YearMonth, BigDecimal> a = new TreeMap<>(serie(ENE_2025, v));
        Map<YearMonth, BigDecimal> b = new HashMap<>();
        List<Map.Entry<YearMonth, BigDecimal>> alReves = new ArrayList<>(a.entrySet());
        java.util.Collections.reverse(alReves);
        alReves.forEach(e -> b.put(e.getKey(), new BigDecimal(e.getValue().toPlainString() + "00")));
        LocalDate corte = LocalDate.of(2026, 3, 15);
        Resultado r1 = AnaliticaPrediccion.proyectar(a, corte);
        Resultado r2 = AnaliticaPrediccion.proyectar(b, corte);
        assertThat(r2).isEqualTo(r1);
        assertThat(r1.version()).isEqualTo(AnaliticaPrediccion.VERSION);
        assertThat(r1.sha256()).matches("[0-9a-f]{64}");

        b.put(ENE_2025, new BigDecimal("121"));
        assertThat(AnaliticaPrediccion.proyectar(b, corte).sha256()).isNotEqualTo(r1.sha256());
    }

    @Test
    @DisplayName("serie que cae hacia 0 ⇒ proyección y límite inferior del IC acotados en 0")
    void icLimiteInferiorEnCero() {
        Resultado r = AnaliticaPrediccion.proyectar(serie(ENE_2025, 90, 84, 70, 66, 50, 47, 30, 28, 15, 12), cierre(YearMonth.of(2025, 10)));
        assertThat(r.ok()).isTrue();
        assertThat(d(r.sigma())).isGreaterThan(0);
        for (MesProyectado m : r.proyeccion()) {
            assertThat(m.valor().signum()).isGreaterThanOrEqualTo(0);
            assertThat(m.inferior().signum()).isGreaterThanOrEqualTo(0);
            assertThat(m.superior()).isGreaterThanOrEqualTo(m.valor());
            assertThat(m.valor()).isGreaterThanOrEqualTo(m.inferior());
        }
        assertThat(r.proyeccion()).extracting(MesProyectado::inferior).contains(new BigDecimal("0.00"));
    }

    @Test
    @DisplayName("serie constante ⇒ σ = 0, IC de ancho cero y empate resuelto por la regresión")
    void serieConstante() {
        double[] v = new double[12];
        java.util.Arrays.fill(v, 500);
        Resultado r = AnaliticaPrediccion.proyectar(serie(ENE_2025, v), cierre(YearMonth.of(2025, 12)));
        assertThat(r.sigma()).isEqualByComparingTo("0");
        assertThat(r.metodo()).isEqualTo(Metodo.REGRESION);
        assertThat(r.wmape()).isEqualByComparingTo("0");
        assertThat(r.mape()).isEqualByComparingTo("0");
        for (MesProyectado m : r.proyeccion()) {
            assertThat(m.valor()).isEqualByComparingTo("500");
            assertThat(m.inferior()).isEqualByComparingTo("500");
            assertThat(m.superior()).isEqualByComparingTo("500");
        }
    }

    @Test
    @DisplayName("error real: proyectado 7,9 frente a observado 8,1 ⇒ 2,47 %; observado 0 ⇒ sin error calculable")
    void errorReal() {
        assertThat(AnaliticaPrediccion.errorReal(new BigDecimal("7.9"), new BigDecimal("8.1"))).contains(new BigDecimal("2.47"));
        assertThat(AnaliticaPrediccion.errorReal(new BigDecimal("8.1"), new BigDecimal("8.1"))).contains(new BigDecimal("0.00"));
        assertThat(AnaliticaPrediccion.errorReal(BigDecimal.ONE, BigDecimal.ZERO)).isEmpty();
    }

    @Test
    @DisplayName("escenario ajustado: base × (1 + p/100) en valor e IC, acotado en 0 y con p entre −100 y 1000")
    void escenarioAjustado() {
        assertThat(AnaliticaPrediccion.ajustar(new BigDecimal("200"), new BigDecimal("10"))).isEqualTo(new BigDecimal("220.00"));
        assertThat(AnaliticaPrediccion.ajustar(new BigDecimal("200"), new BigDecimal("-12.5"))).isEqualTo(new BigDecimal("175.00"));
        List<MesProyectado> base = List.of(new MesProyectado(YearMonth.of(2026, 10), 1, new BigDecimal("100.00"), new BigDecimal("80.00"),
                new BigDecimal("120.00")));
        assertThat(AnaliticaPrediccion.ajustar(base, new BigDecimal("5"))).containsExactly(new MesProyectado(YearMonth.of(2026, 10), 1,
                new BigDecimal("105.00"), new BigDecimal("84.00"), new BigDecimal("126.00")));
        assertThatThrownBy(() -> AnaliticaPrediccion.ajustar(BigDecimal.TEN, new BigDecimal("-101"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("empate dentro de la tolerancia ⇒ el más simple (regresión < Holt < Holt-Winters); fuera de ella, el de menor WMAPE")
    void empateElMasSimple() {
        Map<Metodo, Double> e = new EnumMap<>(Metodo.class);
        e.put(Metodo.REGRESION, 5.03);
        e.put(Metodo.HOLT, 5.00);
        e.put(Metodo.HOLT_WINTERS, 4.99);
        assertThat(AnaliticaPrediccion.elegir(e)).isEqualTo(Metodo.REGRESION);
        e.put(Metodo.REGRESION, 5.20);
        assertThat(AnaliticaPrediccion.elegir(e)).isEqualTo(Metodo.HOLT);
        e.put(Metodo.HOLT, 5.10);
        assertThat(AnaliticaPrediccion.elegir(e)).isEqualTo(Metodo.HOLT_WINTERS);
    }

    @Test
    @DisplayName("huecos dentro de la serie valen 0 y quedan en las notas; horizonte fuera de 6..24 se rechaza")
    void huecosYHorizonte() {
        Map<YearMonth, BigDecimal> s = serie(ENE_2025, 10, 12, 14, 16, 18, 20, 22, 24);
        s.remove(YearMonth.of(2025, 4));
        Resultado r = AnaliticaPrediccion.proyectar(s, cierre(YearMonth.of(2025, 8)));
        assertThat(r.mesesHistoria()).isEqualTo(8);
        assertThat(r.notas()).contains("1 mes sin datos se tomó como 0");
        assertThatThrownBy(() -> AnaliticaPrediccion.proyectar(s, cierre(YearMonth.of(2025, 8)), 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnaliticaPrediccion.proyectar(s, cierre(YearMonth.of(2025, 8)), 25)).isInstanceOf(IllegalArgumentException.class);
        assertThat(AnaliticaPrediccion.proyectar(s, cierre(YearMonth.of(2025, 8)), 9).proyeccion()).hasSize(9);
    }
}
