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
}
