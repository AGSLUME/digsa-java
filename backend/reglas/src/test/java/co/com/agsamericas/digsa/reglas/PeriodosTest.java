package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Periodos.Agrupacion;
import co.com.agsamericas.digsa.reglas.Periodos.Preset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Anexo 5, 2.11: periodos de horas a años con etiquetas estables y presets del rango. */
class PeriodosTest {

    @Test
    @DisplayName("etiquetas de horas a años; semana ISO con W, bimestre B, trimestre T y semestre S")
    void etiquetas() {
        LocalDateTime t = LocalDateTime.of(2026, 7, 14, 9, 35);
        assertThat(Agrupacion.HORA.etiqueta(t)).isEqualTo("2026-07-14 09h");
        assertThat(Agrupacion.DIA.etiqueta(t)).isEqualTo("2026-07-14");
        assertThat(Agrupacion.SEMANA.etiqueta(t)).isEqualTo("2026-W29");
        assertThat(Agrupacion.MES.etiqueta(t)).isEqualTo("2026-07");
        assertThat(Agrupacion.BIMESTRE.etiqueta(t)).isEqualTo("2026-B4");
        assertThat(Agrupacion.TRIMESTRE.etiqueta(t)).isEqualTo("2026-T3");
        assertThat(Agrupacion.SEMESTRE.etiqueta(t)).isEqualTo("2026-S2");
        assertThat(Agrupacion.ANIO.etiqueta(t)).isEqualTo("2026");
    }

    @Test
    @DisplayName("los bordes: enero y febrero son el bimestre 1, junio cierra el semestre 1 y la semana ISO cruza el año")
    void bordes() {
        assertThat(Agrupacion.BIMESTRE.etiqueta(LocalDate.of(2026, 2, 28))).isEqualTo("2026-B1");
        assertThat(Agrupacion.BIMESTRE.etiqueta(LocalDate.of(2026, 12, 31))).isEqualTo("2026-B6");
        assertThat(Agrupacion.SEMESTRE.etiqueta(LocalDate.of(2026, 6, 30))).isEqualTo("2026-S1");
        assertThat(Agrupacion.TRIMESTRE.etiqueta(LocalDate.of(2026, 10, 1))).isEqualTo("2026-T4");
        // 2027-01-01 es viernes: pertenece a la semana 53 de 2026
        assertThat(Agrupacion.SEMANA.etiqueta(LocalDate.of(2027, 1, 1))).isEqualTo("2026-W53");
        assertThat(Agrupacion.HORA.etiqueta(LocalDateTime.of(2026, 1, 1, 0, 0))).isEqualTo("2026-01-01 00h");
    }

    @Test
    @DisplayName("claves de la API y solo la agrupación por horas exige hora")
    void claves() {
        assertThat(Agrupacion.deClave("bimestre")).contains(Agrupacion.BIMESTRE);
        assertThat(Agrupacion.deClave("anio")).contains(Agrupacion.ANIO);
        assertThat(Agrupacion.deClave("quincena")).isEmpty();
        assertThat(Agrupacion.HORA.requiereHora()).isTrue();
        assertThat(Agrupacion.DIA.requiereHora()).isFalse();
    }

    @Test
    @DisplayName("presets resueltos a fechas; el año de contrato solo desde su inicio y se renueva en el aniversario")
    void presets() {
        LocalDate hoy = LocalDate.of(2026, 10, 22); // jueves
        Map<String, Preset> p = Periodos.presets(hoy, LocalDate.of(2026, 12, 29)).stream().collect(Collectors.toMap(Preset::clave, x -> x));
        assertThat(p).doesNotContainKey("anio_contrato");
        assertThat(p.get("hoy").desde()).isEqualTo(hoy);
        assertThat(p.get("ayer").hasta()).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(p.get("ultimos_7").desde()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(p.get("semana_actual").desde()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(p.get("mes_anterior").desde()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(p.get("mes_anterior").hasta()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(p.get("trimestre_actual").desde()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(p.get("semestre_actual").desde()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(p.get("anio_actual").desde()).isEqualTo(LocalDate.of(2026, 1, 1));

        List<Preset> segundoAnio = Periodos.presets(LocalDate.of(2028, 3, 5), LocalDate.of(2026, 12, 29));
        Preset contrato = segundoAnio.stream().filter(x -> x.clave().equals("anio_contrato")).findFirst().orElseThrow();
        assertThat(contrato.desde()).isEqualTo(LocalDate.of(2027, 12, 29));
        assertThat(contrato.hasta()).isEqualTo(LocalDate.of(2028, 3, 5));
        assertThat(Periodos.presets(LocalDate.of(2026, 12, 29), LocalDate.of(2026, 12, 29)).stream().filter(x -> x.clave().equals("anio_contrato"))
                .findFirst().orElseThrow().desde()).isEqualTo(LocalDate.of(2026, 12, 29));
    }

    @Test
    @DisplayName("bordes de los presets: el mes anterior de enero es diciembre; un contrato del 29 de febrero vuelve a ese día en bisiesto")
    void bordesPresets() {
        Map<String, Preset> enero = Periodos.presets(LocalDate.of(2027, 1, 15), null).stream().collect(Collectors.toMap(Preset::clave, x -> x));
        assertThat(enero.get("mes_anterior").desde()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(enero.get("mes_anterior").hasta()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(enero).doesNotContainKey("anio_contrato");
        Preset bisiesto = Periodos.presets(LocalDate.of(2032, 3, 1), LocalDate.of(2028, 2, 29)).stream().filter(x -> x.clave().equals("anio_contrato"))
                .findFirst().orElseThrow();
        assertThat(bisiesto.desde()).isEqualTo(LocalDate.of(2032, 2, 29));
        Preset normal = Periodos.presets(LocalDate.of(2029, 3, 1), LocalDate.of(2028, 2, 29)).stream().filter(x -> x.clave().equals("anio_contrato"))
                .findFirst().orElseThrow();
        assertThat(normal.desde()).isEqualTo(LocalDate.of(2029, 2, 28));
    }

    @Test
    @DisplayName("el rango cuenta los dos extremos")
    void rango() {
        assertThat(new Periodos.Rango(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)).dias()).isEqualTo(1);
        assertThat(new Periodos.Rango(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)).dias()).isEqualTo(90);
    }
}
