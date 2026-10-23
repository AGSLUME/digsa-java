package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.com.agsamericas.digsa.reglas.Presupuesto.Sentido;
import co.com.agsamericas.digsa.reglas.PresupuestoCarga.Clase;
import co.com.agsamericas.digsa.reglas.PresupuestoCarga.Lectura;
import co.com.agsamericas.digsa.reglas.PresupuestoNovedad.Item;
import co.com.agsamericas.digsa.reglas.PresupuestoNovedad.Resultado;
import co.com.agsamericas.digsa.reglas.PresupuestoNovedad.Saldo;
import co.com.agsamericas.digsa.reglas.PresupuestoNovedad.Tipo;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Reglas del presupuesto: escenarios con cifras de docs/especificaciones/presupuesto. */
class PresupuestoTest {

    static BigDecimal m(String v) {
        return new BigDecimal(v);
    }

    @Nested
    @DisplayName("montos")
    class Montos {

        @Test
        @DisplayName("escenario «Valores»: coma o punto decimal; más de 2 decimales se rechaza")
        void valores() {
            assertThat(Presupuesto.monto("1.250.000,5")).contains(m("1250000.50"));
            assertThat(Presupuesto.monto("1250000.50")).contains(m("1250000.50"));
            assertThat(Presupuesto.monto("$ 1 250 000,505")).isEmpty();
            assertThat(Presupuesto.monto("1250000.505")).as("un punto con 3 decimales que no es de miles").isEmpty();
        }

        @Test
        @DisplayName("separadores de miles y signo")
        void milesYSigno() {
            assertThat(Presupuesto.monto("1.250")).contains(m("1250.00"));
            assertThat(Presupuesto.monto("1,250,000")).contains(m("1250000.00"));
            assertThat(Presupuesto.monto("1,5")).contains(m("1.50"));
            assertThat(Presupuesto.monto("1,250.75")).contains(m("1250.75"));
            assertThat(Presupuesto.monto("-5")).contains(m("-5.00"));
            assertThat(Presupuesto.monto("+300000000")).contains(m("300000000.00"));
            assertThat(Presupuesto.monto("$450.000.000,00")).contains(m("450000000.00"));
            assertThat(Presupuesto.monto("12.5,00")).as("miles mal agrupados").isEmpty();
            assertThat(Presupuesto.monto("1.25,00")).isEmpty();
            assertThat(Presupuesto.monto("1250000,50")).contains(m("1250000.50"));
        }

        @Test
        @DisplayName("no son montos: vacío, letras, dos signos, demasiado grande")
        void noMontos() {
            for (String s : new String[] {null, "", "  ", "abc", "1.2.3,4,5", "--5", "1e5", "12345678901234567"}) {
                assertThat(Presupuesto.monto(s)).as(String.valueOf(s)).isEmpty();
            }
            assertThat(Presupuesto.monto("9999999999999999,99")).contains(Presupuesto.MAXIMO);
        }

        @Test
        @DisplayName("formato VCO P27: 2 decimales, coma decimal y sin miles")
        void formato() {
            assertThat(Presupuesto.formato(m("38021000000"))).isEqualTo("38021000000,00");
            assertThat(Presupuesto.formato(m("-30000000"))).isEqualTo("-30000000,00");
            assertThat(Presupuesto.formato(null)).isEmpty();
        }

        @Test
        @DisplayName("escenario «Cifras del mockup»: 61,40 % ejecutado y el % sin asignado está vacío")
        void porcentaje() {
            assertThat(Presupuesto.porcentaje(m("60479000000"), m("98500000000"))).contains(m("61.40"));
            assertThat(Presupuesto.porcentaje(m("230000000"), m("200000000"))).as("exceso").contains(m("115.00"));
            assertThat(Presupuesto.porcentaje(m("1000000"), BigDecimal.ZERO)).isEmpty();
        }
    }

    @Nested
    @DisplayName("umbrales del 25, 50, 75 y 100 %")
    class Umbrales {

        @Test
        @DisplayName("escenario «Primer umbral»: 260 de 1.000 alcanza el 25 %")
        void primerUmbral() {
            assertThat(Presupuesto.umbralesAlcanzados(m("240000000"), m("1000000000"))).isEmpty();
            assertThat(Presupuesto.umbralesAlcanzados(m("260000000"), m("1000000000"))).containsExactly(25);
        }

        @Test
        @DisplayName("comparación exacta en el borde, sin redondear")
        void borde() {
            assertThat(Presupuesto.umbralesAlcanzados(m("250.00"), m("1000.00"))).containsExactly(25);
            assertThat(Presupuesto.umbralesAlcanzados(m("249.99"), m("1000.00"))).isEmpty();
            assertThat(Presupuesto.umbralesAlcanzados(m("33.33"), m("133.33"))).as("24,9981 % no es 25").isEmpty();
        }

        @Test
        @DisplayName("escenario «Varios umbrales de una vez»: de 10 % a 80 % se registran 25, 50 y 75 y solo se notifica el 75")
        void variosDeUnaVez() {
            TreeSet<Integer> antes = new TreeSet<>(Presupuesto.umbralesAlcanzados(m("10"), m("100")));
            var ahora = Presupuesto.umbralesAlcanzados(m("80"), m("100"));
            var nuevos = Presupuesto.nuevosUmbrales(ahora, antes);
            assertThat(nuevos).containsExactly(25, 50, 75);
            assertThat(Presupuesto.umbralANotificar(nuevos)).contains(75);
        }

        @Test
        @DisplayName("escenario «Adición y nuevo cruce»: el 75 ya registrado no se vuelve a notificar")
        void adicionYNuevoCruce() {
            var registrados = Set.of(25, 50, 75);
            assertThat(Presupuesto.nuevosUmbrales(Presupuesto.umbralesAlcanzados(m("113"), m("150")), registrados)).isEmpty();
            assertThat(Presupuesto.umbralANotificar(new TreeSet<>())).isEmpty();
        }

        @Test
        @DisplayName("escenario «Línea sin asignado»: algo ejecutado sin presupuesto alcanza los cuatro")
        void sinAsignado() {
            assertThat(Presupuesto.umbralesAlcanzados(m("1000000"), BigDecimal.ZERO)).containsExactly(25, 50, 75, 100);
            assertThat(Presupuesto.umbralesAlcanzados(BigDecimal.ZERO, BigDecimal.ZERO)).isEmpty();
            assertThat(Presupuesto.umbralesAlcanzados(null, m("1000"))).as("reconocido sin conciliar").isEmpty();
        }

        @Test
        @DisplayName("escenario «Base reconocido»: 240 de 1.000 no alcanza el 25 %")
        void baseReconocido() {
            assertThat(Presupuesto.base("reconocido")).isEqualTo(Presupuesto.BASE_RECONOCIDO);
            assertThat(Presupuesto.base(" Reconocido ")).isEqualTo(Presupuesto.BASE_RECONOCIDO);
            assertThat(Presupuesto.base("otra cosa")).isEqualTo(Presupuesto.BASE_FACTURADO);
            assertThat(Presupuesto.base(null)).isEqualTo(Presupuesto.BASE_FACTURADO);
            assertThat(Presupuesto.umbralesAlcanzados(m("240"), m("1000"))).isEmpty();
        }

        @Test
        @DisplayName("semáforo: verde bajo 75, ámbar de 75 a 99,99, rojo desde 100")
        void semaforo() {
            assertThat(Presupuesto.semaforo(m("74.99"), m("100"))).isEqualTo("verde");
            assertThat(Presupuesto.semaforo(m("75"), m("100"))).isEqualTo("ambar");
            assertThat(Presupuesto.semaforo(m("99.99"), m("100"))).isEqualTo("ambar");
            assertThat(Presupuesto.semaforo(m("100"), m("100"))).isEqualTo("rojo");
            assertThat(Presupuesto.semaforo(m("1"), BigDecimal.ZERO)).isEqualTo("rojo");
        }
    }

    @Nested
    @DisplayName("vigencia y proyección")
    class Proyeccion {

        @Test
        @DisplayName("escenario «Vigencia parcial»: del 1 de marzo al 31 de diciembre son 10 meses")
        void vigenciaParcial() {
            List<YearMonth> m = Presupuesto.meses(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 12, 31));
            assertThat(m).hasSize(10).startsWith(YearMonth.of(2027, 3)).endsWith(YearMonth.of(2027, 12));
            assertThat(Presupuesto.meses(LocalDate.of(2026, 12, 29), LocalDate.of(2026, 12, 31))).containsExactly(YearMonth.of(2026, 12));
        }

        @Test
        @DisplayName("escenario «Proyección lineal»: 83.333.333,33 por mes, diciembre 83.333.333,37 y a junio 499.999.999,98")
        void lineal() {
            List<BigDecimal> p = Presupuesto.proyeccionLineal(m("1000000000.00"), 12);
            assertThat(p).hasSize(12);
            assertThat(p.get(0)).isEqualByComparingTo("83333333.33");
            assertThat(p.get(11)).isEqualByComparingTo("83333333.37");
            assertThat(p.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("1000000000.00");
            assertThat(Presupuesto.acumuladoLineal(m("1000000000.00"), LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31), YearMonth.of(2027, 6)))
                    .isEqualByComparingTo("499999999.98");
        }

        @Test
        @DisplayName("vigencia parcial: 1.000 millones de marzo a diciembre son 100 millones al mes y 400 millones a junio")
        void parcial() {
            assertThat(Presupuesto.acumuladoLineal(m("1000000000.00"), LocalDate.of(2027, 3, 1), LocalDate.of(2027, 12, 31), YearMonth.of(2027, 6)))
                    .isEqualByComparingTo("400000000.00");
            assertThat(Presupuesto.proyeccionLineal(m("0.10"), 12)).as("ningún mes negativo").allMatch(v -> v.signum() >= 0)
                    .last().isEqualTo(m("0.10"));
            assertThat(Presupuesto.meses(LocalDate.of(2027, 5, 1), LocalDate.of(2027, 4, 1))).isEmpty();
            assertThat(Presupuesto.acumuladoLineal(m("1000"), LocalDate.of(2027, 5, 1), LocalDate.of(2027, 4, 1), YearMonth.of(2027, 6)))
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("escenario «Proyección tras una adición»: 1.200 millones dan 100 millones al mes y 600 millones a junio")
        void trasAdicion() {
            assertThat(Presupuesto.acumuladoLineal(m("1200000000.00"), LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31), YearMonth.of(2027, 6)))
                    .isEqualByComparingTo("600000000.00");
        }

        @Test
        @DisplayName("corte antes del inicio o después del fin de la vigencia")
        void cortesFuera() {
            LocalDate ini = LocalDate.of(2027, 3, 1);
            LocalDate fin = LocalDate.of(2027, 12, 31);
            assertThat(Presupuesto.acumuladoLineal(m("1000"), ini, fin, YearMonth.of(2027, 2))).isEqualByComparingTo("0");
            assertThat(Presupuesto.acumuladoLineal(m("1000"), ini, fin, YearMonth.of(2028, 1))).isEqualByComparingTo("1000");
            assertThat(Presupuesto.proyeccionLineal(m("1000"), 0)).isEmpty();
            assertThat(Presupuesto.proyeccionLineal(m("0.05"), 3)).as("residuo de centavos").containsExactly(m("0.01"), m("0.01"), m("0.03"));
        }

        @Test
        @DisplayName("periodos: AAAA-MM, AAAA-MM-01 y MM/AAAA")
        void periodos() {
            assertThat(Presupuesto.periodo("2027-03")).contains(YearMonth.of(2027, 3));
            assertThat(Presupuesto.periodo("2027-3")).contains(YearMonth.of(2027, 3));
            assertThat(Presupuesto.periodo("2027-03-01")).contains(YearMonth.of(2027, 3));
            assertThat(Presupuesto.periodo("03/2027")).contains(YearMonth.of(2027, 3));
            for (String s : new String[] {null, "", "2027-13", "2027-03-15", "marzo", "1999-01"}) {
                assertThat(Presupuesto.periodo(s)).as(String.valueOf(s)).isEmpty();
            }
            assertThat(Presupuesto.periodoTexto(YearMonth.of(2027, 3))).isEqualTo("2027-03");
        }
    }

    @Nested
    @DisplayName("desviación frente a la proyección")
    class Desviacion {

        static final BigDecimal EX = Presupuesto.TOLERANCIA_EXCESO;
        static final BigDecimal SUB = Presupuesto.TOLERANCIA_SUBEJECUCION;

        @Test
        @DisplayName("escenario «Simulación de exceso» (M7-3): +20 % es exceso")
        void exceso() {
            assertThat(Presupuesto.desviacion(m("360000000"), m("300000000"))).contains(m("20.00"));
            assertThat(Presupuesto.sentido(m("360000000"), m("300000000"), EX, SUB)).isEqualTo(Sentido.EXCESO);
            assertThat(Presupuesto.sentido(m("112.50"), m("100"), EX, SUB)).as("fuerza EJC +12,50 %").isEqualTo(Sentido.EXCESO);
            assertThat(Presupuesto.sentido(m("110.40"), m("100"), EX, SUB)).as("nacional +10,40 %").isEqualTo(Sentido.EXCESO);
            assertThat(Presupuesto.sentido(m("114.99"), m("100"), m("15"), SUB)).as("tolerancia de 15").isEqualTo(Sentido.NINGUNO);
            assertThat(Presupuesto.sentido(m("5"), null, EX, SUB)).isEqualTo(Sentido.EXCESO);
        }

        @Test
        @DisplayName("escenario «Dentro de la tolerancia»: +8 % no alarma; el borde exacto del 10 % tampoco")
        void dentro() {
            assertThat(Presupuesto.sentido(m("108"), m("100"), EX, SUB)).isEqualTo(Sentido.NINGUNO);
            assertThat(Presupuesto.sentido(m("110"), m("100"), EX, SUB)).isEqualTo(Sentido.NINGUNO);
            assertThat(Presupuesto.sentido(m("110.01"), m("100"), EX, SUB)).isEqualTo(Sentido.EXCESO);
        }

        @Test
        @DisplayName("escenario «Subejecución»: −30 % es subejecución; −20 % exacto no")
        void subejecucion() {
            assertThat(Presupuesto.desviacion(m("350000000"), m("500000000"))).contains(m("-30.00"));
            assertThat(Presupuesto.sentido(m("350000000"), m("500000000"), EX, SUB)).isEqualTo(Sentido.SUBEJECUCION);
            assertThat(Presupuesto.sentido(m("80"), m("100"), EX, SUB)).isEqualTo(Sentido.NINGUNO);
        }

        @Test
        @DisplayName("proyectado 0: con algo ejecutado es exceso; sin nada, ninguno")
        void proyectadoCero() {
            assertThat(Presupuesto.desviacion(m("5"), BigDecimal.ZERO)).isEmpty();
            assertThat(Presupuesto.sentido(m("5"), BigDecimal.ZERO, EX, SUB)).isEqualTo(Sentido.EXCESO);
            assertThat(Presupuesto.sentido(BigDecimal.ZERO, BigDecimal.ZERO, EX, SUB)).isEqualTo(Sentido.NINGUNO);
        }

        @Test
        @DisplayName("tolerancias del parámetro: «10,00», «15» y valores inválidos")
        void tolerancias() {
            assertThat(Presupuesto.tolerancia("10,00", SUB)).isEqualByComparingTo("10");
            assertThat(Presupuesto.tolerancia("15", SUB)).isEqualByComparingTo("15");
            assertThat(Presupuesto.tolerancia("-1", SUB)).isEqualByComparingTo(SUB);
            assertThat(Presupuesto.tolerancia("abc", EX)).isEqualByComparingTo(EX);
            assertThat(Presupuesto.tolerancia("1000,01", EX)).isEqualByComparingTo(EX);
            assertThat(Presupuesto.toleranciaValida("5 %")).contains(m("5.00"));
            assertThat(Presupuesto.toleranciaValida("cinco")).isEmpty();
            assertThat(Presupuesto.baseValida("Reconocido")).contains("reconocido");
            assertThat(Presupuesto.baseValida("reconocida")).as("errata: no cae a facturado en silencio").isEmpty();
        }
    }

    @Nested
    @DisplayName("consecutivo y claves")
    class Claves {

        @Test
        void consecutivoYClaves() {
            assertThat(Presupuesto.consecutivo("PRE", 2027, 7)).isEqualTo("PRE-2027-0007");
            assertThat(Presupuesto.consecutivo("pre", 2027, 12345)).isEqualTo("PRE-2027-12345");
            assertThat(Presupuesto.consecutivo("<x>", 2027, 1)).isEqualTo("PRE-2027-0001");
            assertThat(Presupuesto.claveUmbral(2027, 15, 75)).isEqualTo("pu:2027:15:75");
            // «Recarga del mismo corte»: marzo dos veces da la misma clave; abril, otra
            assertThat(Presupuesto.claveDesviacion(2027, YearMonth.of(2027, 3), "esm", "15", Sentido.EXCESO))
                    .isEqualTo(Presupuesto.claveDesviacion(2027, YearMonth.of(2027, 3), "esm", "15", Sentido.EXCESO))
                    .isNotEqualTo(Presupuesto.claveDesviacion(2027, YearMonth.of(2027, 4), "esm", "15", Sentido.EXCESO));
            assertThat(Presupuesto.claveDesviacion(2027, YearMonth.of(2027, 3), "fuerza", "EJC", Sentido.EXCESO)).isEqualTo("pd:2027:2027-03:fuerza:EJC:exceso");
        }
    }

    @Nested
    @DisplayName("novedades presupuestales (M7-2)")
    class Novedades {

        @Test
        @DisplayName("escenario «Rebalanceo del mockup»: −450 millones al origen y +450 al destino, neto 0")
        void rebalanceo() {
            Resultado r = PresupuestoNovedad.cambios(Tipo.REBALANCEO, List.of(new Item(11, m("450000000")), new Item(7, m("450000000"))));
            assertThat(r.valido()).isTrue();
            assertThat(r.cambios()).containsExactly(Map.entry(11L, m("-450000000.00")), Map.entry(7L, m("450000000.00")));
            assertThat(r.neto()).isEqualByComparingTo("0");
            assertThat(PresupuestoNovedad.cambios(Tipo.REBALANCEO, List.of(new Item(1, m("5")), new Item(1, m("5")))).problema()).contains("distintas");
            assertThat(PresupuestoNovedad.cambios(Tipo.REBALANCEO, List.of(new Item(1, m("5")), new Item(2, m("6")))).problema()).contains("igual");
            assertThat(PresupuestoNovedad.cambios(Tipo.REBALANCEO, List.of(new Item(1, m("5")))).problema()).contains("origen");
        }

        @Test
        @DisplayName("escenario «Recomposición que no cuadra»: suma 0,01 y se rechaza")
        void recomposicion() {
            Resultado r = PresupuestoNovedad.cambios(Tipo.RECOMPOSICION,
                    List.of(new Item(1, m("300000000")), new Item(2, m("-200000000")), new Item(3, m("-99999999.99"))));
            assertThat(r.problema()).isEqualTo("La recomposición debe sumar 0,00 (suma 0,01).");
            assertThat(PresupuestoNovedad.cambios(Tipo.RECOMPOSICION,
                    List.of(new Item(1, m("300000000")), new Item(2, m("-200000000")), new Item(3, m("-100000000")))).valido()).isTrue();
        }

        @Test
        @DisplayName("adición, disminución, reajuste y asignación: signos y valores")
        void signos() {
            assertThat(PresupuestoNovedad.cambios(Tipo.ADICION, List.of(new Item(1, m("10")))).cambios()).containsEntry(1L, m("10.00"));
            assertThat(PresupuestoNovedad.cambios(Tipo.DISMINUCION, List.of(new Item(1, m("10")))).cambios()).containsEntry(1L, m("-10.00"));
            assertThat(PresupuestoNovedad.cambios(Tipo.DISMINUCION, List.of(new Item(1, m("-10")))).problema()).contains("mayor que 0");
            assertThat(PresupuestoNovedad.cambios(Tipo.ASIGNACION_INICIAL, List.of(new Item(1, BigDecimal.ZERO))).problema()).contains("mayor que 0");
            assertThat(PresupuestoNovedad.cambios(Tipo.REAJUSTE, List.of(new Item(1, m("-3")), new Item(2, m("4")))).neto()).isEqualByComparingTo("1");
            assertThat(PresupuestoNovedad.cambios(Tipo.REAJUSTE, List.of(new Item(1, BigDecimal.ZERO))).problema()).contains("distinto de 0");
            assertThat(PresupuestoNovedad.cambios(Tipo.ADICION, List.of(new Item(1, m("1.234")))).problema()).contains("2 decimales");
            assertThat(PresupuestoNovedad.cambios(Tipo.ADICION, List.of(new Item(1, m("1.200")))).valido()).as("ceros a la derecha").isTrue();
            assertThat(PresupuestoNovedad.cambios(Tipo.ADICION, List.of()).problema()).contains("no tiene líneas");
            assertThat(PresupuestoNovedad.cambios(Tipo.ADICION, List.of(new Item(1, m("1")), new Item(1, m("2")))).problema()).contains("dos veces");
            assertThat(PresupuestoNovedad.cambios(Tipo.ADICION, List.of(new Item(1, m("99999999999999999")))).problema()).contains("máximo");
        }

        @Test
        @DisplayName("escenario «Sin soporte»: el soporte de la DIGSA es obligatorio, de 5 a 300 caracteres")
        void soporte() {
            assertThat(PresupuestoNovedad.problemaSoporte("  ")).contains("El soporte de la DIGSA es obligatorio.");
            assertThat(PresupuestoNovedad.problemaSoporte("Of.1")).isPresent();
            assertThat(PresupuestoNovedad.problemaSoporte("x".repeat(301))).isPresent();
            assertThat(PresupuestoNovedad.problemaSoporte("Oficio 456 del 2027-05-02")).isEmpty();
            assertThat(PresupuestoNovedad.Tipo.deCodigo("rebalanceo")).contains(Tipo.REBALANCEO);
            assertThat(PresupuestoNovedad.Tipo.deCodigo("otra")).isEmpty();
        }

        @Test
        @DisplayName("reajuste por %: el centavo del redondeo va a la línea de mayor asignado")
        void reajustePorcentaje() {
            Map<Long, BigDecimal> a = new LinkedHashMap<>();
            a.put(1L, m("100.01"));
            a.put(2L, m("200.01"));
            a.put(3L, m("100.01"));
            Resultado r = PresupuestoNovedad.reajustePorcentaje(a, m("5"));
            // total 400,03 × 5 % = 20,0015 → 20,00; por línea 5,0005 → 5,00, 10,0005 → 10,00, 5,00: suma 20,00, sin residuo
            assertThat(r.neto()).isEqualByComparingTo("20.00");
            Map<Long, BigDecimal> b = Map.of(1L, m("0.10"), 2L, m("0.10"), 3L, m("0.30"));
            Resultado s = PresupuestoNovedad.reajustePorcentaje(new LinkedHashMap<>(b), m("15"));
            // 0,50 × 15 % = 0,075 → 0,08; por línea 0,015 → 0,02, 0,02, 0,045 → 0,04: suma 0,08
            assertThat(s.neto()).isEqualByComparingTo("0.08");
            assertThat(PresupuestoNovedad.reajustePorcentaje(Map.of(1L, m("33.33"), 2L, m("33.33"), 3L, m("33.34")), m("10")).cambios())
                    .as("10 % de 100,00: el centavo a la de mayor asignado").containsOnly(Map.entry(1L, m("3.33")), Map.entry(2L, m("3.33")),
                            Map.entry(3L, m("3.34")));
            assertThat(PresupuestoNovedad.reajustePorcentaje(Map.of(7L, m("33.33"), 2L, m("33.33"), 3L, m("33.33")), m("10")).cambios())
                    .as("empate: el residuo va a la de menor id, sin importar el orden").containsOnly(Map.entry(2L, m("3.34")),
                            Map.entry(3L, m("3.33")), Map.entry(7L, m("3.33")));
            assertThat(PresupuestoNovedad.reajustePorcentaje(Map.of(1L, m("33.33"), 2L, m("66.67")), m("-100")).cambios())
                    .as("−100 % deja todo en 0").containsOnly(Map.entry(1L, m("-33.33")), Map.entry(2L, m("-66.67")));
            assertThat(PresupuestoNovedad.reajustePorcentaje(Map.of(1L, m("100")), BigDecimal.ZERO).problema()).contains("distinto de 0");
            assertThat(PresupuestoNovedad.reajustePorcentaje(Map.of(1L, m("100")), m("-101")).problema()).contains("entre -100");
            assertThat(PresupuestoNovedad.reajustePorcentaje(Map.of(), m("5")).problema()).contains("no tiene líneas");
        }

        @Test
        @DisplayName("escenario «Disminución bajo lo ejecutado» y asignado negativo; la adición nunca se bloquea")
        void saldos() {
            Map<Long, Saldo> s = Map.of(5L, new Saldo("ESM 05 B/quilla · Suministro", m("500000000"), m("420000000")),
                    6L, new Saldo("ESM 06 · Dispensación", m("100000000"), BigDecimal.ZERO));
            assertThat(PresupuestoNovedad.problemasDeSaldo(Map.of(5L, m("-100000000")), s))
                    .containsExactly("ESM 05 B/quilla · Suministro quedaría con 400000000,00, menos que lo ejecutado (420000000,00).");
            assertThat(PresupuestoNovedad.problemasDeSaldo(Map.of(6L, m("-120000000")), s)).singleElement().asString().contains("negativo");
            assertThat(PresupuestoNovedad.problemasDeSaldo(Map.of(6L, m("-60000000"), 5L, m("10")), s)).isEmpty();
            assertThat(PresupuestoNovedad.problemasDeSaldo(Map.of(5L, m("-80000000")), s)).as("queda igual a lo ejecutado").isEmpty();
            assertThat(PresupuestoNovedad.problemasDeSaldo(Map.of(5L, m("-100000000"), 6L, m("-120000000")), s)).hasSize(2);
            assertThat(PresupuestoNovedad.problemasDeSaldo(Map.of(9L, m("-1")), s)).singleElement().asString().contains("no tiene saldo");
        }
    }

    @Nested
    @DisplayName("cargas (M7-1)")
    class Cargas {

        static List<List<String>> csv(String texto) {
            return PresupuestoCarga.leerCsv(texto.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("CSV con BOM, comillas, «;» dentro de comillas y fin de línea de Windows")
        void leerCsv() {
            List<List<String>> t = csv("﻿vigencia;esm\r\n2027;\"ESM 07; Cali\"\r\n2027;\"dice \"\"hola\"\"\"\n");
            assertThat(t).containsExactly(List.of("vigencia", "esm"), List.of("2027", "ESM 07; Cali"), List.of("2027", "dice \"hola\""));
        }

        @Test
        @DisplayName("no UTF-8 o separado por comas: mensaje claro")
        void formatoInvalido() {
            assertThatThrownBy(() -> PresupuestoCarga.leerCsv(new byte[] {(byte) 0xC1, 'a'})).hasMessageContaining("UTF-8");
            assertThatThrownBy(() -> csv("vigencia,fuerza,esm,modalidad,valor\n2027,EJC,ESM 07,Dispensación,1\n")).hasMessageContaining("punto y coma");
            assertThatThrownBy(() -> csv("vigencia;esm\n2027;\"ESM 07\n")).hasMessageContaining("comillas sin cerrar");
        }

        @Test
        @DisplayName("escenario «Simular»: las filas válidas y los errores con su número de fila")
        void asignacion() {
            Lectura l = PresupuestoCarga.leer(Clase.ASIGNACION, csv("""
                    Año;Fuerza;ESM;Modalidad;Valor
                    2027;ejc;ESM 07 Cali;Dispensación;1.000.000.000,00
                    2027;EJC;ESM 11 Neiva;Dispensación;-5

                    27;EJC;;Dispensación;abc
                    """));
            assertThat(l.filas()).singleElement().satisfies(f -> {
                assertThat(f.numero()).isEqualTo(2);
                assertThat(f.vigencia()).isEqualTo(2027);
                assertThat(f.fuerza()).isEqualTo("EJC");
                assertThat(f.valor()).isEqualByComparingTo("1000000000");
            });
            assertThat(l.errores()).extracting(PresupuestoCarga.ErrorFila::fila).containsExactly(3, 5);
            assertThat(l.errores().get(0).motivo()).isEqualTo("Valor debe ser mayor que 0.");
            assertThat(l.errores().get(1).motivo()).contains("Vigencia «27» no es un año", "falta el ESM", "«abc» no es un valor");
        }

        @Test
        @DisplayName("ejecución: periodo, reconocido opcional y escenario «Reconocido mayor que facturado»")
        void ejecucion() {
            Lectura l = PresupuestoCarga.leer(Clase.EJECUCION, csv("""
                    vigencia;fuerza;esm;modalidad;periodo;facturado;reconocido;referencia
                    2027;EJC;ESM 07;Dispensación;2027-03;260000000,00;;Oficio 9
                    2027;EJC;ESM 07;Dispensación;2027-04;10000000,00;12000000,00;
                    2027;EJC;ESM 07;Dispensación;abril;0;;
                    2027;EJC;ESM 07;Dispensación;2027-05;0;;
                    2027;EJC;ESM 07;Dispensación;2027-06;5;abc;
                    2027;EJC;ESM 07;Dispensación;2027-07;5;-1;
                    2027;EJC;ESM 07;Dispensación;2027-08;5;;%s
                    """.formatted("x".repeat(121))));
            assertThat(l.filas()).hasSize(2);
            assertThat(l.errores()).hasSize(5);
            assertThat(l.filas().get(0).periodo()).isEqualTo(YearMonth.of(2027, 3));
            assertThat(l.filas().get(0).reconocido()).isNull();
            assertThat(l.filas().get(0).referencia()).isEqualTo("Oficio 9");
            assertThat(l.filas().get(1).valor()).as("facturado 0 es válido (corrige un mes)").isEqualByComparingTo("0");
            assertThat(l.errores()).extracting(PresupuestoCarga.ErrorFila::motivo)
                    .startsWith("El reconocido (12000000,00) no puede superar el facturado (10000000,00).", "Periodo «abril» no es un mes (AAAA-MM).");
            assertThat(l.errores().get(4).motivo()).contains("más de 120");
            Lectura p = PresupuestoCarga.leer(Clase.PROYECCION, csv("vigencia;fuerza;esm;modalidad;periodo;valor\n2027;EJC;X;Y;2027-01;0\n"));
            assertThat(p.filas()).as("proyección 0 se acepta").hasSize(1);
            assertThat(PresupuestoCarga.leer(Clase.ASIGNACION, csv("vigencia;valor;esm;modalidad;fuerza;Valor\n2027;1;X;Y;EJC;2\n")).errores())
                    .singleElement().satisfies(e -> assertThat(e.motivo()).contains("dos veces"));
        }

        @Test
        @DisplayName("columnas que faltan, archivo vacío y sin filas")
        void columnas() {
            assertThat(PresupuestoCarga.leer(Clase.PROYECCION, csv("vigencia;fuerza;esm;modalidad;valor\n2027;EJC;X;Y;1\n")).errores())
                    .singleElement().satisfies(e -> assertThat(e.motivo()).startsWith("Faltan las columnas: periodo."));
            assertThat(PresupuestoCarga.leer(Clase.ASIGNACION, List.of()).errores()).singleElement().satisfies(e -> assertThat(e.motivo()).contains("vacío"));
            assertThat(PresupuestoCarga.leer(Clase.ASIGNACION, csv("vigencia;fuerza;esm;modalidad;valor\n")).errores())
                    .singleElement().satisfies(e -> assertThat(e.motivo()).contains("no tiene filas"));
        }

        @Test
        @DisplayName("más de 5000 filas: se pide dividir el archivo")
        void maximo() {
            StringBuilder s = new StringBuilder("vigencia;fuerza;esm;modalidad;valor\n");
            for (int i = 0; i <= PresupuestoCarga.MAX_FILAS; i++) {
                s.append("2027;EJC;E").append(i).append(";Dispensación;1\n");
            }
            assertThatThrownBy(() -> csv(s.toString())).as("el CSV corta la lectura").hasMessageContaining("más de 5000");
            List<List<String>> tabla = new java.util.ArrayList<>();
            tabla.add(List.of("vigencia", "fuerza", "esm", "modalidad", "valor"));
            for (int i = 0; i <= PresupuestoCarga.MAX_FILAS; i++) {
                tabla.add(List.of("2027", "EJC", "E" + i, "Dispensación", "1"));
            }
            assertThat(PresupuestoCarga.leer(Clase.ASIGNACION, tabla).errores()).as("la tabla del Excel también").singleElement()
                    .satisfies(e -> assertThat(e.motivo()).contains("más de 5000"));
        }

        @Test
        @DisplayName("topes de memoria del CSV y encabezados desconocidos")
        void topesYEncabezados() {
            assertThatThrownBy(() -> csv(";".repeat(60) + "\n")).hasMessageContaining("más de 50 columnas");
            assertThatThrownBy(() -> csv("vigencia;esm\n2027;" + "x".repeat(1001) + "\n")).hasMessageContaining("más de 1000 caracteres");
            assertThat(PresupuestoCarga.leer(Clase.EJECUCION, csv("vigencia;fuerza;esm;modalidad;periodo;facturado;reconocida\n2027;EJC;X;Y;2027-01;1;1\n"))
                    .errores()).singleElement().satisfies(e -> assertThat(e.motivo()).contains("«reconocida» no es de esta carga"));
            assertThat(PresupuestoCarga.leer(Clase.ASIGNACION, csv("vigencia;fuerza;esm;modalidad;valor;\n2027;EJC;X;Y;1;\n")).filas())
                    .as("una columna sin nombre al final se ignora").hasSize(1);
        }

        @Test
        @DisplayName("normalizar: sin tildes, minúsculas y un solo espacio")
        void normalizar() {
            assertThat(PresupuestoCarga.normalizar("  Órdenes   de  Requisición ")).isEqualTo("ordenes de requisicion");
            assertThat(PresupuestoCarga.normalizar(null)).isEmpty();
        }
    }
}
