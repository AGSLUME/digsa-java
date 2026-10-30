package co.com.agsamericas.digsa.analitica.tableros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Columna;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Kpi;
import co.com.agsamericas.digsa.analitica.tableros.ResultadoTablero.Tabla;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Tipo;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Presupuesto por petición (Plazo) y supresión de celdas pequeñas (Supresion), sin base de datos. */
class PlazoYSupresionTest {

    @Test
    @DisplayName("plazo: cada sentencia fija el statement_timeout con lo que queda; agotado el presupuesto, 422 «tardó demasiado» sin ejecutar")
    void plazo() {
        List<String> sentencias = new ArrayList<>();
        JdbcClient falso = sql -> {
            sentencias.add(sql);
            return org.mockito.Mockito.mock(JdbcClient.StatementSpec.class);
        };
        Plazo amplio = new Plazo(falso, 60_000);
        amplio.cliente().sql("SELECT 1");
        assertThat(sentencias.getFirst()).startsWith("SET LOCAL statement_timeout = ");
        assertThat(Long.parseLong(sentencias.getFirst().replaceAll("\\D", ""))).isBetween(1L, 60_000L);
        assertThat(sentencias.get(1)).isEqualTo("SELECT 1");

        sentencias.clear();
        Plazo agotado = new Plazo(falso, 0);
        assertThatThrownBy(() -> agotado.cliente().sql("SELECT 1")).isInstanceOf(ErrorNegocio.class)
                .hasMessage(ServicioConsultaAnalitica.TIEMPO_AGOTADO)
                .satisfies(e -> assertThat(((ErrorNegocio) e).datos()).containsEntry("tiempoAgotado", true));
        assertThat(sentencias).isEmpty();
    }

    private static Kpi kpi(String clave, BigDecimal v) {
        return new Kpi(clave, clave, Tipo.ENTERO, v, String.valueOf(v), null, null, null, null, null, null, Map.of());
    }

    @Test
    @DisplayName("supresión: con k = 5 se ocultan pacientes y promedio por paciente de las celdas de 1 a 4 pacientes; las sumas no cambian; k = 0 no toca nada")
    void supresion() {
        List<Columna> cols = List.of(new Columna("clave", "ESM", Tipo.TEXTO), new Columna("gasto", "Gasto", Tipo.MONEDA),
                new Columna("pacientes", "Pacientes", Tipo.ENTERO), new Columna("promedioPaciente", "Promedio", Tipo.MONEDA));
        Tabla t = new Tabla("t", "T", "barras", "gasto", null, cols, List.of(
                ResultadoTablero.fila("A", null, cols, Arrays.asList("A", new BigDecimal("100.00"), 3L, new BigDecimal("33.33")), "dato"),
                ResultadoTablero.fila("B", null, cols, Arrays.asList("B", new BigDecimal("900.00"), 9L, new BigDecimal("100.00")), "dato"),
                ResultadoTablero.fila("C", null, cols, Arrays.asList("C", new BigDecimal("0.00"), 0L, null), "dato")), null);
        List<Tabla> r = new Supresion(5).tablas(List.of(t));
        assertThat(r.getFirst().filas().get(0).textos()).containsExactly("A", r.getFirst().filas().get(0).textos().get(1), "<5", "<5");
        assertThat(r.getFirst().filas().get(0).valores().get(1)).isEqualTo(new BigDecimal("100.00"));
        assertThat(r.getFirst().filas().get(0).valores().get(2)).isNull();
        assertThat(r.getFirst().filas().get(1)).isEqualTo(t.filas().get(1));
        assertThat(r.getFirst().filas().get(2)).isEqualTo(t.filas().get(2));
        assertThat(new Supresion(0).tablas(List.of(t))).containsExactly(t);

        List<Kpi> k = new Supresion(5).kpis(List.of(kpi("pacientes", BigDecimal.valueOf(2)), kpi("promedioPaciente", BigDecimal.TEN),
                kpi("gasto", BigDecimal.TEN)));
        assertThat(k.get(0).texto()).isEqualTo("<5");
        assertThat(k.get(0).valor()).isNull();
        assertThat(k.get(1).texto()).isEqualTo("<5");
        assertThat(k.get(2).valor()).isEqualTo(BigDecimal.TEN);
    }

    @Test
    @DisplayName("supresión reforzada (cohorte sensible, A7.1): la celda pequeña pierde también sus cifras, la siguiente menor se oculta (complementaria) "
            + "y en el nivel ESM la serie sin pacientes por celda y el mapa de calor se ocultan")
    void supresionReforzada() {
        List<Columna> cols = List.of(new Columna("clave", "ESM", Tipo.TEXTO), new Columna("gasto", "Gasto", Tipo.MONEDA),
                new Columna("pacientes", "Pacientes", Tipo.ENTERO));
        Tabla t = new Tabla("esm", "T", "barras", "gasto", null, cols, List.of(
                ResultadoTablero.fila("A", null, cols, Arrays.asList("A", new BigDecimal("100.00"), 3L), "dato"),
                ResultadoTablero.fila("B", null, cols, Arrays.asList("B", new BigDecimal("900.00"), 9L), "dato"),
                ResultadoTablero.fila("C", null, cols, Arrays.asList("C", new BigDecimal("500.00"), 6L), "dato"),
                ResultadoTablero.fila("T", null, cols, Arrays.asList("Total", new BigDecimal("1500.00"), 18L), "total")), null);
        Supresion s = new Supresion(5, true, false);
        assertThat(s.kpis(List.of(kpi("pacientes", BigDecimal.valueOf(18)), kpi("gasto", BigDecimal.TEN))).get(1).valor()).isEqualTo(BigDecimal.TEN);
        assertThat(s.ocultoTodo()).isFalse();
        List<ResultadoTablero.Fila> f = s.tablas(List.of(t)).getFirst().filas();
        assertThat(f.get(0).valores()).containsExactly("A", null, null);
        assertThat(f.get(0).textos()).containsExactly("A", "<5", "<5");
        // Complementaria: C (6 pacientes, la menor visible) también, para que A no salga por diferencia del total
        assertThat(f.get(2).valores()).containsExactly("C", null, null);
        assertThat(f.get(1)).isEqualTo(t.filas().get(1));
        assertThat(f.get(3)).isEqualTo(t.filas().get(3));

        // Total bajo k: todo oculto (KPI y tablas)
        Supresion pocos = new Supresion(5, true, false);
        List<Kpi> k = pocos.kpis(List.of(kpi("pacientes", BigDecimal.valueOf(3)), kpi("gasto", BigDecimal.TEN)));
        assertThat(pocos.ocultoTodo()).isTrue();
        assertThat(k).allSatisfy(x -> assertThat(x.valor()).isNull());
        assertThat(pocos.tablas(List.of(t)).getFirst().filas()).allSatisfy(x -> assertThat(x.valores().subList(1, 3)).containsOnlyNulls());

        // Sin pacientes por celda: en el nivel ESM se oculta; el mapa de calor en cualquier vista; fuera del ESM, la serie se deja
        List<Columna> cs = List.of(new Columna("periodo", "Periodo", Tipo.TEXTO), new Columna("gasto", "Gasto", Tipo.MONEDA));
        Tabla serie = new Tabla("serie", "S", "serie", "gasto", null, cs, List.of(ResultadoTablero.fila("2025-01", null, cs,
                Arrays.asList("2025-01", new BigDecimal("10.00")), "dato")), null);
        Tabla calor = new Tabla("calor", "C", "calor", null, null, cs, serie.filas(), null);
        Supresion esm = new Supresion(5, true, true);
        esm.kpis(List.of(kpi("pacientes", BigDecimal.valueOf(40))));
        assertThat(esm.tablas(List.of(serie)).getFirst().filas().getFirst().valores().get(1)).isNull();
        Supresion nacional = new Supresion(5, true, false);
        nacional.kpis(List.of(kpi("pacientes", BigDecimal.valueOf(40))));
        List<Tabla> rn = nacional.tablas(List.of(serie, calor));
        assertThat(rn.get(0)).isEqualTo(serie);
        assertThat(rn.get(1).filas().getFirst().valores().get(1)).isNull();
        // Sin la reforzada (k = 0 de las sensibles), nada cambia
        assertThat(new Supresion(0, true, true).tablas(List.of(t, serie))).containsExactly(t, serie);
    }

    private static Kpi kpiComparado(String clave, BigDecimal v, BigDecimal comparado) {
        return new Kpi(clave, clave, Tipo.MONEDA, v, String.valueOf(v), comparado, String.valueOf(comparado), v.subtract(comparado), BigDecimal.ONE,
                "1 %", null, Map.of());
    }

    @Test
    @DisplayName("reforzada (revisión A7.1): total explícito sin KPI de pacientes, «Resto» oculto y complementaria, comparación por fila nunca, "
            + "comparación de KPI solo con ≥ k pacientes, tabla sin pacientes oculta en nacional y distribución sin extremos")
    void reforzadaRevision() {
        // Total explícito pequeño aunque el tablero no tenga KPI de pacientes (tablero 8): todo oculto
        Supresion chica = new Supresion(5, true, false, 2L, null);
        assertThat(chica.kpis(List.of(kpi("gasto", BigDecimal.TEN), kpi("gastoAltoCosto", BigDecimal.ONE)))).allSatisfy(x -> assertThat(x.valor()).isNull());
        assertThat(chica.ocultoTodo()).isTrue();

        // Comparación de los KPI: con el periodo comparado de 2 pacientes no se publica; con 40, sí
        List<Kpi> kc = new Supresion(5, true, false, 40L, 2L).kpis(List.of(kpiComparado("gasto", BigDecimal.TEN, BigDecimal.ONE)));
        assertThat(kc.getFirst().valor()).isEqualTo(BigDecimal.TEN);
        assertThat(kc.getFirst().comparado()).isNull();
        assertThat(kc.getFirst().variacionPct()).isNull();
        assertThat(new Supresion(5, true, false, 40L, 40L).kpis(List.of(kpiComparado("gasto", BigDecimal.TEN, BigDecimal.ONE))).getFirst().comparado())
                .isEqualTo(BigDecimal.ONE);
        // KPI que dependen de otro oculto (gasto de la cohorte de alto costo con 2 pacientes)
        List<Kpi> dep = new Supresion(5, true, false, 40L, null).kpis(List.of(kpi("pacientesAltoCosto", BigDecimal.valueOf(2)),
                kpi("gastoAltoCosto", BigDecimal.TEN)));
        assertThat(dep.get(1).valor()).isNull();

        // «Resto» sin pacientes: oculto, y como es la única oculta, la menor visible también; la columna «comparado» nunca
        List<Columna> cols = List.of(new Columna("clave", "ATC", Tipo.TEXTO), new Columna("gasto", "Gasto", Tipo.MONEDA),
                new Columna("comparado", "Comparado", Tipo.MONEDA), new Columna("pacientes", "Pacientes", Tipo.ENTERO));
        Tabla t = new Tabla("atc", "T", "barras", "gasto", "comparado", cols, List.of(
                ResultadoTablero.fila("A", null, cols, Arrays.asList("A", new BigDecimal("900.00"), new BigDecimal("800.00"), 30L), "dato"),
                ResultadoTablero.fila("B", null, cols, Arrays.asList("B", new BigDecimal("100.00"), new BigDecimal("90.00"), 8L), "dato"),
                ResultadoTablero.fila("(resto)", null, cols, Arrays.asList("Resto (3)", new BigDecimal("20.00"), null, null), "resto"),
                ResultadoTablero.fila("(total)", Map.of(), cols, Arrays.asList("Total", new BigDecimal("1020.00"), null, 40L), "total")), null);
        Supresion s = new Supresion(5, true, false, 40L, 40L);
        s.kpis(List.of());
        List<ResultadoTablero.Fila> f = s.tablas(List.of(t)).getFirst().filas();
        assertThat(f.get(2).valores().get(1)).as("resto").isNull();
        assertThat(f.get(1).valores().get(1)).as("complementaria: B, la menor").isNull();
        assertThat(f.get(0).valores()).containsExactly("A", new BigDecimal("900.00"), null, 30L);
        assertThat(f.get(3).valores().get(1)).isEqualTo(new BigDecimal("1020.00"));

        // Tabla sin pacientes por celda y no temporal (p. ej. la consulta por ESM sin pacientes): oculta también en la vista nacional
        List<Columna> cg = List.of(new Columna("esm", "ESM", Tipo.TEXTO), new Columna("gasto", "Gasto", Tipo.MONEDA));
        Tabla barras = new Tabla("consulta", "C", "barras", "gasto", null, cg, List.of(ResultadoTablero.fila("DMCOV", null, cg,
                Arrays.asList("DMCOV", new BigDecimal("50.00")), "dato")), null);
        assertThat(s.tablas(List.of(barras)).getFirst().filas().getFirst().valores().get(1)).isNull();

        // Distribución: sin mínimo ni máximo; con menos de 2k pacientes, nada
        List<Columna> cd = List.of(new Columna("estadistico", "E", Tipo.TEXTO), new Columna("paciente", "Gasto por paciente", Tipo.MONEDA));
        Tabla dist = new Tabla("distribucion", "D", "ninguno", null, null, cd, List.of(
                ResultadoTablero.fila("Mínimo", null, cd, Arrays.asList("Mínimo", BigDecimal.ONE), "dato"),
                ResultadoTablero.fila("Mediana", null, cd, Arrays.asList("Mediana", BigDecimal.TEN), "dato")), null);
        List<ResultadoTablero.Fila> d1 = s.tablas(List.of(dist)).getFirst().filas();
        assertThat(d1.get(0).valores().get(1)).isNull();
        assertThat(d1.get(1).valores().get(1)).isEqualTo(BigDecimal.TEN);
        Supresion pocos = new Supresion(5, true, false, 7L, null);
        pocos.kpis(List.of());
        assertThat(pocos.tablas(List.of(dist)).getFirst().filas().get(1).valores().get(1)).isNull();
    }
}
