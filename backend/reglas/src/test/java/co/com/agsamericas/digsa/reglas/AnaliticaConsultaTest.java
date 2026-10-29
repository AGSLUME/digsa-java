package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.CampoOpcional;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Comparacion;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.ConsultaAnalitica;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Dimension;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Distribucion;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Granularidad;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Intencion;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Metrica;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Tablero;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Tipo;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Variable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Capa semántica y reglas puras de los tableros de analítica (docs/especificaciones/analitica-tableros). */
class AnaliticaConsultaTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    @DisplayName("los 12 tableros en el orden del mockup; cohortes, atípicos y predictivo quedan para la siguiente fase")
    void doceTableros() {
        assertThat(Tablero.values()).hasSize(12);
        assertThat(Tablero.values()[0].titulo()).isEqualTo("General de dispensación");
        assertThat(Tablero.values()[1].codigo()).isEqualTo("gasto");
        assertThat(List.of(Tablero.values()).stream().filter(t -> !t.disponible()).toList())
                .containsExactly(Tablero.COHORTES, Tablero.ATIPICOS, Tablero.PREDICTIVO);
        assertThat(Tablero.deCodigo("alto-costo")).contains(Tablero.ALTO_COSTO);
        assertThat(Tablero.deCodigo("otro")).isEmpty();
    }

    @Test
    @DisplayName("tablero de gasto: 4 dispensaciones de 3 pacientes por $ 100,00 ⇒ 400,00; 100,00 por dispensación; 133,33 por paciente")
    void gasto() {
        BigDecimal gasto = d("400.00");
        assertThat(AnaliticaConsulta.promedio(gasto, 4)).isEqualByComparingTo("100.00");
        assertThat(AnaliticaConsulta.promedio(gasto, 3)).isEqualByComparingTo("133.33");
        assertThat(AnaliticaConsulta.moneda(AnaliticaConsulta.promedio(gasto, 3))).isEqualTo("$ 133,33");
        assertThat(AnaliticaConsulta.promedio(gasto, 0)).isNull();
    }

    @Test
    @DisplayName("variación: 106,20 frente a 100,00 ⇒ 6,20 y 6,20 %; con anterior 0 el porcentaje es «—», no infinito")
    void variacion() {
        AnaliticaConsulta.Variacion v = AnaliticaConsulta.variacion(d("106.20"), d("100.00"));
        assertThat(v.absoluta()).isEqualByComparingTo("6.20");
        assertThat(v.porcentaje()).isEqualByComparingTo("6.20");
        assertThat(AnaliticaConsulta.porcentaje(v.porcentaje())).isEqualTo("6,20 %");
        assertThat(AnaliticaConsulta.conSigno(AnaliticaConsulta.porcentaje(v.porcentaje()), v.porcentaje())).isEqualTo("+6,20 %");
        AnaliticaConsulta.Variacion cero = AnaliticaConsulta.variacion(d("50"), BigDecimal.ZERO);
        assertThat(cero.porcentaje()).isNull();
        assertThat(AnaliticaConsulta.porcentaje(cero.porcentaje())).isEqualTo("—");
        assertThat(AnaliticaConsulta.variacion(d("90"), d("100")).porcentaje()).isEqualByComparingTo("-10.00");
    }

    @Test
    @DisplayName("participación con 2 decimales HALF_UP; total 0 ⇒ nula")
    void participacion() {
        assertThat(AnaliticaConsulta.participacion(d("1"), d("3"))).isEqualByComparingTo("33.33");
        assertThat(AnaliticaConsulta.participacion(d("2"), d("3"))).isEqualByComparingTo("66.67");
        assertThat(AnaliticaConsulta.participacion(d("2"), BigDecimal.ZERO)).isNull();
    }

    @Test
    @DisplayName("percentiles como percentile_cont: mediana de 1..4 = 2,5; P25 = 1,75; rango intercuartílico = 1,5")
    void mediana() {
        Distribucion x = Distribucion.de(List.of(d("4"), d("1"), d("3"), d("2")));
        assertThat(x.minimo()).isEqualByComparingTo("1");
        assertThat(x.mediana()).isEqualByComparingTo("2.5");
        assertThat(x.p25()).isEqualByComparingTo("1.75");
        assertThat(x.p75()).isEqualByComparingTo("3.25");
        assertThat(x.maximo()).isEqualByComparingTo("4");
        assertThat(x.rangoIntercuartilico()).isEqualByComparingTo("1.5");
        assertThat(Distribucion.de(List.of()).mediana()).isNull();
        assertThat(AnaliticaConsulta.percentil(List.of(d("7")), 0.99)).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("etiquetas de periodo del contrato de reportes: día, semana ISO, mes, bimestre, trimestre, semestre y año")
    void etiquetasPeriodo() {
        LocalDate d = LocalDate.of(2026, 7, 14);
        assertThat(AnaliticaConsulta.etiquetaPeriodo(d, Granularidad.DIA)).isEqualTo("2026-07-14");
        assertThat(AnaliticaConsulta.etiquetaPeriodo(d, Granularidad.SEMANA)).isEqualTo("2026-W29");
        assertThat(AnaliticaConsulta.etiquetaPeriodo(d, Granularidad.MES)).isEqualTo("2026-07");
        assertThat(AnaliticaConsulta.etiquetaPeriodo(d, Granularidad.BIMESTRE)).isEqualTo("2026-B4");
        assertThat(AnaliticaConsulta.etiquetaPeriodo(d, Granularidad.TRIMESTRE)).isEqualTo("2026-T3");
        assertThat(AnaliticaConsulta.etiquetaPeriodo(d, Granularidad.SEMESTRE)).isEqualTo("2026-S2");
        assertThat(AnaliticaConsulta.etiquetaPeriodo(d, Granularidad.ANIO)).isEqualTo("2026");
        // Semana ISO del año siguiente o anterior
        assertThat(AnaliticaConsulta.etiquetaPeriodo(LocalDate.of(2024, 12, 30), Granularidad.SEMANA)).isEqualTo("2025-W01");
        assertThat(AnaliticaConsulta.etiquetaPeriodo(LocalDate.of(2021, 1, 3), Granularidad.SEMANA)).isEqualTo("2020-W53");
    }

    @Test
    @DisplayName("rango de una etiqueta (rastrear un punto de la serie): mes, bimestre, semana y año; una etiqueta inválida no da rango")
    void rangoPeriodo() {
        assertThat(AnaliticaConsulta.rangoPeriodo("2025-09").orElseThrow()).containsExactly(LocalDate.of(2025, 9, 1), LocalDate.of(2025, 9, 30));
        assertThat(AnaliticaConsulta.rangoPeriodo("2025-B6").orElseThrow()).containsExactly(LocalDate.of(2025, 11, 1), LocalDate.of(2025, 12, 31));
        assertThat(AnaliticaConsulta.rangoPeriodo("2026-W29").orElseThrow()).containsExactly(LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 19));
        assertThat(AnaliticaConsulta.rangoPeriodo("2024-S1").orElseThrow()).containsExactly(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 6, 30));
        assertThat(AnaliticaConsulta.rangoPeriodo("2025").orElseThrow()).containsExactly(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        assertThat(AnaliticaConsulta.rangoPeriodo("2025-T5")).isEmpty();
        assertThat(AnaliticaConsulta.rangoPeriodo("2025-13")).isEmpty();
        assertThat(AnaliticaConsulta.rangoPeriodo("x")).isEmpty();
        // Ida y vuelta: la etiqueta del inicio del rango es la misma
        for (Granularidad g : List.of(Granularidad.DIA, Granularidad.SEMANA, Granularidad.MES, Granularidad.BIMESTRE, Granularidad.TRIMESTRE,
                Granularidad.SEMESTRE, Granularidad.ANIO)) {
            String e = AnaliticaConsulta.etiquetaPeriodo(LocalDate.of(2025, 8, 20), g);
            LocalDate[] r = AnaliticaConsulta.rangoPeriodo(e).orElseThrow();
            assertThat(AnaliticaConsulta.etiquetaPeriodo(r[0], g)).as(g.name()).isEqualTo(e);
            assertThat(AnaliticaConsulta.etiquetaPeriodo(r[1], g)).as(g.name()).isEqualTo(e);
        }
    }

    @Test
    @DisplayName("bimestres de 2025: 6 puntos de ene–feb a nov–dic; semanas y meses completos")
    void bimestres() {
        assertThat(AnaliticaConsulta.periodos(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), Granularidad.BIMESTRE))
                .containsExactly("2025-B1", "2025-B2", "2025-B3", "2025-B4", "2025-B5", "2025-B6");
        assertThat(AnaliticaConsulta.periodos(LocalDate.of(2025, 1, 15), LocalDate.of(2025, 3, 2), Granularidad.MES))
                .containsExactly("2025-01", "2025-02", "2025-03");
        assertThat(AnaliticaConsulta.periodos(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 14), Granularidad.SEMANA))
                .containsExactly("2025-W01", "2025-W02", "2025-W03");
        assertThat(AnaliticaConsulta.mesesCompletos(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 28))).isTrue();
        assertThat(AnaliticaConsulta.mesesCompletos(LocalDate.of(2025, 1, 2), LocalDate.of(2025, 2, 28))).isFalse();
    }

    @Test
    @DisplayName("periodo de comparación: el mismo del año anterior y el anterior de igual duración")
    void comparacion() {
        LocalDate[] a = AnaliticaConsulta.periodoComparacion(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 30), Comparacion.ANIO_ANTERIOR);
        assertThat(a).containsExactly(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 9, 30));
        LocalDate[] b = AnaliticaConsulta.periodoComparacion(LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 31), Comparacion.ANTERIOR);
        assertThat(b).containsExactly(LocalDate.of(2025, 1, 29), LocalDate.of(2025, 2, 28));
        assertThat(AnaliticaConsulta.periodoComparacion(LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 31), Comparacion.NINGUNA)).isNull();
    }

    @Test
    @DisplayName("Top 20 más «Resto (15)» con 35 filas cuadra con el total; con 20 o menos no hay resto")
    void topMasResto() {
        List<BigDecimal> filas = new ArrayList<>();
        for (int i = 35; i >= 1; i--) {
            filas.add(BigDecimal.valueOf(i));
        }
        List<BigDecimal> r = AnaliticaConsulta.topMasResto(filas, 20, resto -> resto.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        assertThat(r).hasSize(21);
        assertThat(r.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(BigDecimal.valueOf(35 * 36 / 2));
        assertThat(r.getLast()).isEqualByComparingTo(BigDecimal.valueOf(15 * 16 / 2));
        assertThat(AnaliticaConsulta.etiquetaResto(15)).isEqualTo("Resto (15)");
        assertThat(AnaliticaConsulta.topMasResto(filas.subList(0, 20), 20, x -> null)).hasSize(20);
    }

    @Test
    @DisplayName("orden del ranking: valor descendente y, en empate, código ascendente (orden total y estable)")
    void ordenRanking() {
        record F(String clave, BigDecimal valor) {
        }
        List<F> l = new ArrayList<>(List.of(new F("B", d("10")), new F("A", d("10")), new F("C", d("20")), new F("D", null)));
        l.sort(AnaliticaConsulta.ordenRanking(F::valor, F::clave));
        assertThat(l).extracting(F::clave).containsExactly("C", "A", "B", "D");
    }

    @Test
    @DisplayName("formato 2.4: dos decimales, coma, sin miles; conteos enteros; unidades enteras o con 2 decimales; fechas dd/mm/aaaa y 24 h")
    void formato() {
        assertThat(AnaliticaConsulta.moneda(d("64310220400"))).isEqualTo("$ 64310220400,00");
        assertThat(AnaliticaConsulta.decimal(d("152400.305"))).isEqualTo("152400,31");
        assertThat(AnaliticaConsulta.entero(105218)).isEqualTo("105218");
        assertThat(AnaliticaConsulta.unidades(d("30.000"))).isEqualTo("30");
        assertThat(AnaliticaConsulta.unidades(d("2.5"))).isEqualTo("2,50");
        assertThat(AnaliticaConsulta.formato(null, Tipo.MONEDA)).isEqualTo("—");
        assertThat(AnaliticaConsulta.formato(12L, Tipo.ENTERO)).isEqualTo("12");
        assertThat(AnaliticaConsulta.formato(d("6.2"), Tipo.PORCENTAJE)).isEqualTo("6,20 %");
        assertThat(AnaliticaConsulta.paraExportar(d("400"), Tipo.MONEDA)).isEqualTo(d("400.00"));
        assertThat(AnaliticaConsulta.paraExportar(d("30.000"), Tipo.UNIDADES)).isEqualTo(30L);
        assertThat(AnaliticaConsulta.fecha(LocalDate.of(2026, 10, 21))).isEqualTo("21/10/2026");
        assertThat(AnaliticaConsulta.fechaHora(OffsetDateTime.of(2026, 10, 21, 20, 5, 0, 0, ZoneOffset.UTC))).isEqualTo("21/10/2026 15:05");
    }

    @Test
    @DisplayName("consulta estructurada: filtros normalizados y ordenados (la huella no depende del orden); valores con forma inválida")
    void consultaValidable() {
        ConsultaAnalitica a = new ConsultaAnalitica(Intencion.METRICAS, null, List.of(Metrica.GASTO), List.of(Dimension.FUERZA),
                Map.of(Variable.FUERZA, List.of("ejc", "ARC", "EJC"), Variable.CIE10, List.of("e11.9")), null, null, null, null, null, null, null,
                null);
        assertThat(a.valores(Variable.FUERZA)).containsExactly("ARC", "EJC");
        assertThat(a.valores(Variable.CIE10)).containsExactly("E119");
        assertThat(a.problemas()).isEmpty();
        assertThat(a.granularidad()).isEqualTo(Granularidad.MES);

        ConsultaAnalitica mala = new ConsultaAnalitica(Intencion.METRICAS, null, List.of(), List.of(Dimension.FUERZA, Dimension.ESM,
                Dimension.ATC1, Dimension.CUM), Map.of(Variable.CUM, List.of("99999999-99", "abc"), Variable.ATC, List.of("L0")),
                LocalDate.of(2025, 2, 1), LocalDate.of(2025, 1, 1), Granularidad.HORA, null, null, null, 7, Metrica.PARTICIPACION);
        assertThat(mala.problemas()).contains("Indique al menos una métrica.", "Máximo 3 dimensiones por consulta.",
                "La fecha «Desde» es posterior a «Hasta».", "El nivel ATC va de 1 a 5.",
                "No disponible en el insumo: la granularidad por horas requiere la hora de dispensación.",
                "CUM «abc» no existe o está fuera de su alcance.", "ATC «L0» no existe o está fuera de su alcance.",
                "El ranking se ordena por gasto, unidades, dispensaciones o pacientes.");
        // Forma válida aunque no exista: eso lo resuelve el servicio contra los catálogos
        assertThat(mala.problemas()).doesNotContain("CUM «99999999-99» no existe o está fuera de su alcance.");
        assertThat(ConsultaAnalitica.tablero(Tablero.GASTO).problemas()).isEmpty();
        assertThat(new ConsultaAnalitica(Intencion.TABLERO, null, null, null, null, null, null, null, null, null, null, null, null).problemas())
                .contains("Indique el tablero.");
    }

    @Test
    @DisplayName("valores especiales «Sin homologar» y «sin dato» solo donde aplican; inyección en un valor no tiene forma válida")
    void especiales() {
        assertThat(Variable.ATC.formaValida(AnaliticaConsulta.SIN_HOMOLOGAR)).isTrue();
        assertThat(Variable.CIE10.formaValida(AnaliticaConsulta.SIN_DATO)).isTrue();
        assertThat(Variable.FUERZA.formaValida(AnaliticaConsulta.SIN_DATO)).isFalse();
        assertThat(Variable.ESM.formaValida("BAS02' OR '1'='1")).isFalse();
        assertThat(Variable.ATC.formaValida("L04%")).isFalse();
        assertThat(Variable.CIE10.formaValida("E11")).isTrue();
        assertThat(Variable.CIE10.formaValida("E")).isTrue();
        assertThat(Variable.ATC.formaValida("L04AB04")).isTrue();
    }

    @Test
    @DisplayName("rangos de poliformulación 1, 2–4, 5–9 y ≥ 10")
    void rangosPoliformulacion() {
        assertThat(AnaliticaConsulta.rangoPoliformulacion(1)).isEqualTo("1");
        assertThat(AnaliticaConsulta.rangoPoliformulacion(4)).isEqualTo("2–4");
        assertThat(AnaliticaConsulta.rangoPoliformulacion(5)).isEqualTo("5–9");
        assertThat(AnaliticaConsulta.rangoPoliformulacion(12)).isEqualTo("≥ 10");
        assertThat(AnaliticaConsulta.rangoPoliformulacion(0)).isNull();
    }

    @Test
    @DisplayName("huella canónica: el mismo contenido da la misma huella sin importar la escala del decimal")
    void huella() {
        String a = AnaliticaConsulta.huella(List.of("gasto|" + AnaliticaConsulta.canonico(d("400.00"))));
        String b = AnaliticaConsulta.huella(List.of("gasto|" + AnaliticaConsulta.canonico(d("400"))));
        assertThat(a).isEqualTo(b).hasSize(64);
        assertThat(AnaliticaConsulta.huella(List.of("gasto|401"))).isNotEqualTo(a);
    }

    @Test
    @DisplayName("cobertura del A4: 21 reportes; 11.1.15 «No cubierto en analítica (módulo de reportes)»; faltantes de un insumo sin tipo de fórmula")
    void cobertura() {
        assertThat(AnaliticaConsulta.REPORTES_A4).hasSize(21);
        AnaliticaConsulta.ReporteA4 r15 = AnaliticaConsulta.REPORTES_A4.stream().filter(r -> r.codigo().equals("11.1.15")).findFirst().orElseThrow();
        assertThat(r15.cubierto()).isFalse();
        assertThat(r15.nota()).isEqualTo("No cubierto en analítica (módulo de reportes)");
        AnaliticaConsulta.ReporteA4 r20 = AnaliticaConsulta.REPORTES_A4.stream().filter(r -> r.codigo().equals("11.1.20")).findFirst().orElseThrow();
        List<CampoOpcional> faltan = AnaliticaConsulta.faltantes(r20, Set.of(CampoOpcional.NUMERO_FORMULA));
        assertThat(AnaliticaConsulta.noDisponible(faltan)).isEqualTo("No disponible en el insumo: tipo de fórmula, profesional");
    }

    @Test
    @DisplayName("cuartiles desde una tabla de frecuencias = percentile_cont sobre la lista expandida (1, 2, 2, 3, 3, 3, 4: P25 = 2, mediana = 3, P75 = 3)")
    void cuartilesPorFrecuencias() {
        BigDecimal[] c = AnaliticaConsulta.cuartiles(List.of(new BigDecimal[] {d("3"), d("3")}, new BigDecimal[] {d("1"), d("1")},
                new BigDecimal[] {d("4"), d("1")}, new BigDecimal[] {d("2"), d("2")}));
        Distribucion x = Distribucion.de(List.of(d("1"), d("2"), d("2"), d("3"), d("3"), d("3"), d("4")));
        assertThat(c[0]).isEqualByComparingTo(x.minimo());
        assertThat(c[1]).isEqualByComparingTo(x.p25());
        assertThat(c[2]).isEqualByComparingTo(x.mediana());
        assertThat(c[3]).isEqualByComparingTo(x.p75());
        assertThat(c[4]).isEqualByComparingTo(x.maximo());
        assertThat(c[1]).isEqualByComparingTo("2");
        assertThat(AnaliticaConsulta.cuartiles(List.of())).containsOnlyNulls();
    }

    @Test
    @DisplayName("periodo por defecto: los últimos 12 meses hasta el último día con datos, sin pasar del primero")
    void periodoPorDefecto() {
        LocalDate[] p = AnaliticaConsulta.periodoPorDefecto(LocalDate.of(2023, 1, 5), LocalDate.of(2026, 10, 20));
        assertThat(p).containsExactly(LocalDate.of(2025, 11, 1), LocalDate.of(2026, 10, 20));
        assertThat(AnaliticaConsulta.periodoPorDefecto(LocalDate.of(2026, 3, 5), LocalDate.of(2026, 10, 20))[0]).isEqualTo(LocalDate.of(2026, 3, 5));
        assertThat(AnaliticaConsulta.periodoPorDefecto(null, null)).isNull();
    }

    @Test
    @DisplayName("DISAN admite «sin dato» (la fila «Sin DISAN» se rastrea)")
    void disanSinDato() {
        assertThat(Variable.DISAN.formaValida(AnaliticaConsulta.SIN_DATO)).isTrue();
    }
}
