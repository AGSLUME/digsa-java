package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.Criterio;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.MesPaciente;
import co.com.agsamericas.digsa.reglas.AnaliticaCohortes.Resultado;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Cohortes de la analítica (docs/especificaciones/analitica-cohortes-alertas): criterios, texto, evaluación por mes, GCF y GRC. */
class AnaliticaCohortesTest {

    private static final int ENE = AnaliticaCohortes.ordinal(2025, 1);

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            r.put((String) kv[i], kv[i + 1]);
        }
        return r;
    }

    private static MesPaciente mes(int mes, int principios) {
        return new MesPaciente(mes, 0, principios, 1000, Set.of(), Map.of());
    }

    private static Resultado evaluar(Criterio c, List<MesPaciente> meses) {
        return AnaliticaCohortes.evaluar(c, meses, m -> -1);
    }

    @Test
    @DisplayName("polimedicado: 6 principios de enero a mayo y 3 en junio ⇒ poliformulado en esos 5 meses, nunca polimedicado; con 6 meses sí desde el sexto")
    void polimedicadoExigeSeisMeses() {
        Criterio poli = AnaliticaCohortes.leer(m("tipo", "principiosConsecutivos", "minimo", 5, "meses", 6));
        Criterio form = AnaliticaCohortes.leer(m("tipo", "principiosMes", "minimo", 5));
        List<MesPaciente> meses = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            meses.add(mes(ENE + i, 6));
        }
        meses.add(mes(ENE + 5, 3));
        assertThat(evaluar(form, meses).miembro()).containsExactly(true, true, true, true, true, false);
        assertThat(evaluar(poli, meses).miembro()).containsOnly(false);
        List<MesPaciente> seis = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            seis.add(mes(ENE + i, 5));
        }
        assertThat(evaluar(poli, seis).miembro()).containsExactly(false, false, false, false, false, true, true);
        // Un mes sin dispensaciones corta la racha
        List<MesPaciente> hueco = new ArrayList<>(seis.subList(0, 3));
        hueco.addAll(List.of(mes(ENE + 4, 5), mes(ENE + 5, 5), mes(ENE + 6, 5), mes(ENE + 7, 5)));
        assertThat(evaluar(poli, hueco).miembro()).containsOnly(false);
    }

    @Test
    @DisplayName("losartán de dos CUM y otros 3 principios cuentan 4 principios activos (no 5): no es poliformulado con el mínimo de 5")
    void mismoPrincipioDosCumCuentaUno() {
        int n = AnaliticaCohortes.principiosDistintos(List.of("LOSARTAN", "LOSARTAN", "METFORMINA", "ATORVASTATINA", "OMEPRAZOL"));
        assertThat(n).isEqualTo(4);
        Criterio form = AnaliticaCohortes.leer(m("tipo", "principiosMes", "minimo", 5));
        assertThat(evaluar(form, List.of(mes(ENE, n))).miembro()).containsExactly(false);
    }

    @Test
    @DisplayName("GCF: gasto repartido por igual entre C09 y A10 ⇒ A10 (el menor), siempre igual; sin gasto ⇒ SIN_GASTO; sin ATC ⇒ SIN_ATC")
    void gcfEmpateDeterminista() {
        Criterio gcf = AnaliticaCohortes.leer(m("tipo", "particion", "metodo", "gcf"));
        MesPaciente mp = new MesPaciente(ENE, 0, 2, 200, Set.of(), Map.of("C09", 100L, "A10", 100L));
        for (int i = 0; i < 3; i++) {
            assertThat(evaluar(gcf, List.of(mp)).grupo()).containsExactly("A10");
        }
        assertThat(AnaliticaCohortes.grupoGcf(new MesPaciente(ENE, 0, 1, 0, Set.of(), Map.of()))).isEqualTo("SIN_GASTO");
        assertThat(AnaliticaCohortes.grupoGcf(new MesPaciente(ENE, 0, 1, 50, Set.of(), Map.of()))).isEqualTo("SIN_ATC");
    }

    @Test
    @DisplayName("GRC: 0 crónicos ⇒ GRC1, 1 ⇒ GRC2, 2 o 3 ⇒ GRC3, 4 ⇒ GRC4; alto costo ⇒ GRC4; los crónicos cuentan en la ventana de 12 meses")
    void grcBandas() {
        Criterio grc = AnaliticaCohortes.leer(m("tipo", "particion", "metodo", "grc", "ventanaMeses", 12, "altoCosto",
                m("meses", 12, "modo", "absoluto", "valor", "1000000")));
        assertThat(grupo(grc, Set.of())).isEqualTo("GRC1");
        assertThat(grupo(grc, Set.of("I10"))).isEqualTo("GRC2");
        assertThat(grupo(grc, Set.of("I10", "E11", "J45"))).isEqualTo("GRC3");
        assertThat(grupo(grc, Set.of("I10", "E11", "J45", "N18"))).isEqualTo("GRC4");
        MesPaciente caro = new MesPaciente(ENE, 0, 1, 100_000_000L, Set.of(), Map.of());
        assertThat(evaluar(grc, List.of(caro)).grupo()).containsExactly("GRC4");
        // Un crónico de hace 13 meses ya no cuenta
        List<MesPaciente> l = List.of(new MesPaciente(ENE, 0, 1, 10, Set.of("I10"), Map.of()), new MesPaciente(ENE + 13, 0, 1, 10, Set.of(), Map.of()));
        assertThat(evaluar(grc, l).grupo()).containsExactly("GRC2", "GRC1");
        assertThat(AnaliticaCohortes.categoriaCronica("E119", AnaliticaCohortes.CRONICOS_DEFECTO)).isEqualTo("E11");
        assertThat(AnaliticaCohortes.categoriaCronica("J029", AnaliticaCohortes.CRONICOS_DEFECTO)).isNull();
    }

    private static String grupo(Criterio grc, Set<String> cronicos) {
        return evaluar(grc, List.of(new MesPaciente(ENE, 0, 1, 10, cronicos, Map.of()))).grupo()[0];
    }

    @Test
    @DisplayName("criterio del comité: CIE-10 E10–E14 Y ATC A10A en 12 meses; texto legible; la ventana se cuenta desde el mes evaluado")
    void criterioCombinado() {
        Criterio c = AnaliticaCohortes.leer(m("tipo", "grupo", "op", "Y", "criterios", List.of(m("tipo", "cie10", "valores", List.of("E10", "E11", "E12", "E13",
                "E14"), "ventanaMeses", 12), m("tipo", "atc", "valores", List.of("a10a"), "ventanaMeses", 12))));
        assertThat(AnaliticaCohortes.texto(c)).isEqualTo("CIE-10 E10, E11, E12, E13 o E14 en 12 meses y ATC A10A en 12 meses");
        assertThat(AnaliticaCohortes.atomos(c)).hasSize(2);
        // Diagnóstico en enero (átomo 0), insulina en marzo (átomo 1): miembro en marzo; en enero aún no
        List<MesPaciente> l = List.of(new MesPaciente(ENE, 0b01, 1, 10, Set.of(), Map.of()), new MesPaciente(ENE + 2, 0b10, 1, 10, Set.of(), Map.of()),
                new MesPaciente(ENE + 14, 0b10, 1, 10, Set.of(), Map.of()));
        assertThat(evaluar(c, l).miembro()).containsExactly(false, true, false);
        // Negación y O
        Criterio no = AnaliticaCohortes.leer(m("tipo", "cie10", "valores", List.of("E11"), "ventanaMeses", 1, "negar", true));
        assertThat(AnaliticaCohortes.texto(no)).startsWith("no (CIE-10 E11 en el mes");
        assertThat(evaluar(no, List.of(new MesPaciente(ENE, 1, 1, 1, Set.of(), Map.of()))).miembro()).containsExactly(false);
        // JSON canónico: leer(aMapa(c)) da el mismo criterio
        assertThat(AnaliticaCohortes.aMapa(AnaliticaCohortes.leer(AnaliticaCohortes.aMapa(c)))).isEqualTo(AnaliticaCohortes.aMapa(c));
    }

    @Test
    @DisplayName("validación: tipo desconocido, valores inválidos, ventana fuera de rango y partición combinada se rechazan con mensaje")
    void validacion() {
        assertThatThrownBy(() -> AnaliticaCohortes.leer(m("tipo", "otro"))).hasMessageContaining("desconocido");
        assertThatThrownBy(() -> AnaliticaCohortes.leer(m("tipo", "cie10", "valores", List.of("ZZZZZ")))).hasMessageContaining("no es válido");
        assertThatThrownBy(() -> AnaliticaCohortes.leer(m("tipo", "atc", "valores", List.of("L04"), "ventanaMeses", 0))).hasMessageContaining("va de 1");
        assertThatThrownBy(() -> AnaliticaCohortes.leer(m("tipo", "grupo", "op", "Y", "criterios", List.of(m("tipo", "particion", "metodo", "gcf")))))
                .hasMessageContaining("partición");
        assertThatThrownBy(() -> AnaliticaCohortes.leer(m("tipo", "lista", "valores", List.of("inventada")))).hasMessageContaining("no es válido");
        assertThatThrownBy(() -> AnaliticaCohortes.leer(m("tipo", "gastoMovil", "modo", "absoluto", "valor", "-5"))).hasMessageContaining("mayor que cero");
        // Tope del umbral absoluto (desbordaría al pasar a centavos y tumbaría el recálculo)
        assertThatThrownBy(() -> AnaliticaCohortes.leer(m("tipo", "gastoMovil", "modo", "absoluto", "valor", "1000000000000.01"))).hasMessageContaining("va hasta");
        // Dos umbrales de gasto o dos listas de crónicos distintos en la misma cohorte: el motor usaría solo uno
        assertThatThrownBy(() -> AnaliticaCohortes.leer(m("tipo", "grupo", "op", "O", "criterios", List.of(m("tipo", "gastoMovil", "meses", 12),
                m("tipo", "gastoMovil", "meses", 3))))).hasMessageContaining("un solo umbral");
        assertThatThrownBy(() -> AnaliticaCohortes.leer(m("tipo", "grupo", "op", "O", "criterios", List.of(m("tipo", "cronicos", "minimo", 2, "prefijos",
                List.of("I10")), m("tipo", "cronicos", "minimo", 1, "prefijos", List.of("E11")))))).hasMessageContaining("una sola lista");
        assertThat(AnaliticaCohortes.leer(m("tipo", "grupo", "op", "O", "criterios", List.of(m("tipo", "gastoMovil", "meses", 12), m("tipo", "gastoMovil",
                "meses", 12, "negar", true))))).isNotNull();
        assertThat(AnaliticaCohortes.normalizarValor(AnaliticaCohortes.TipoCodigo.CIE10, "z99.2")).isEqualTo("Z992");
        assertThat(AnaliticaCohortes.normalizarValor(AnaliticaCohortes.TipoCodigo.PRINCIPIO, "Ácido valproico")).isEqualTo("ACIDO VALPROICO");
    }

    @Test
    @DisplayName("alto costo: gasto en 12 meses móviles ≥ umbral del mes (percentil discreto como percentile_disc); sin umbral no cumple")
    void altoCosto() {
        Criterio ac = AnaliticaCohortes.leer(m("tipo", "gastoMovil", "meses", 12, "modo", "percentil", "percentil", 99));
        List<MesPaciente> l = List.of(new MesPaciente(ENE, 0, 1, 600, Set.of(), Map.of()), new MesPaciente(ENE + 1, 0, 1, 500, Set.of(), Map.of()),
                new MesPaciente(ENE + 13, 0, 1, 100, Set.of(), Map.of()));
        // Umbral 1000 en febrero (600 + 500 = 1100 ⇒ miembro); en el mes 14 ya no suma enero ni febrero
        assertThat(AnaliticaCohortes.evaluar(ac, l, mes -> mes == ENE ? -1 : 1000).miembro()).containsExactly(false, true, false);
        long[] valores = new long[100];
        for (int i = 0; i < 100; i++) {
            valores[i] = i + 1;
        }
        assertThat(AnaliticaCohortes.percentilDiscreto(valores, 99)).isEqualTo(99);
        assertThat(AnaliticaCohortes.percentilDiscreto(new long[] {5, 7, 9}, 99)).isEqualTo(9);
        assertThat(AnaliticaCohortes.percentilDiscreto(new long[0], 99)).isEqualTo(-1);
        assertThat(AnaliticaCohortes.texto(ac)).isEqualTo("gasto en 12 meses móviles ≥ percentil 99 del gasto por paciente");
    }

    @Test
    @DisplayName("calentamiento: polimedicados no puede tener ingresos en sus 6 primeros meses de historia; las demás, en el primero")
    void calentamiento() {
        assertThat(AnaliticaCohortes.mesesCalentamiento(AnaliticaCohortes.leer(m("tipo", "principiosConsecutivos", "minimo", 5, "meses", 6)))).isEqualTo(6);
        assertThat(AnaliticaCohortes.mesesCalentamiento(AnaliticaCohortes.leer(m("tipo", "gastoMovil", "meses", 12)))).isEqualTo(1);
        assertThat(AnaliticaCohortes.mesesCalentamiento(AnaliticaCohortes.leer(m("tipo", "grupo", "op", "Y", "criterios", List.of(m("tipo", "cie10",
                "valores", List.of("N18")), m("tipo", "principiosConsecutivos", "minimo", 3, "meses", 4)))))).isEqualTo(4);
    }

    @Test
    @DisplayName("las 12 predefinidas del A4 son válidas, con código, texto y las seis del mockup primero")
    void predefinidas() {
        assertThat(AnaliticaCohortes.PREDEFINIDAS).hasSize(12);
        for (AnaliticaCohortes.Predefinida p : AnaliticaCohortes.PREDEFINIDAS) {
            assertThat(p.codigo()).matches(AnaliticaCohortes.CODIGO);
            Criterio c = AnaliticaCohortes.leer(p.criterio());
            assertThat(AnaliticaCohortes.texto(c)).isNotBlank();
        }
        assertThat(AnaliticaCohortes.PREDEFINIDAS.subList(0, 6).stream().map(AnaliticaCohortes.Predefinida::codigo).toList())
                .containsExactlyInAnyOrderElementsOf(AnaliticaCohortes.DEL_MOCKUP);
        assertThat(AnaliticaCohortes.listas(AnaliticaCohortes.leer(AnaliticaCohortes.PREDEFINIDAS.get(5).criterio()))).containsExactly(
                AnaliticaCohortes.Lista.HUERFANAS);
        assertThat(AnaliticaCohortes.texto(AnaliticaCohortes.leer(AnaliticaCohortes.PREDEFINIDAS.get(1).criterio())))
                .isEqualTo("≥ 5 principios activos distintos en cada mes durante ≥ 6 meses consecutivos");
    }

    @Test
    @DisplayName("A7.1 (AN.31): sensible por criterio (CIE-10 o ATC de VIH, lista de huérfanas) en cualquier código; crónicos y particiones no")
    void sensiblesPorCriterio() {
        Map<String, Criterio> pred = new LinkedHashMap<>();
        for (AnaliticaCohortes.Predefinida p : AnaliticaCohortes.PREDEFINIDAS) {
            pred.put(p.codigo(), AnaliticaCohortes.leer(p.criterio()));
        }
        AnaliticaCohortes.Sensibles s = AnaliticaCohortes.Sensibles.de(List.of(pred.get("vih"), pred.get("huerfanas")), List.of("E752"));
        assertThat(s.cie10("B20")).isTrue();
        assertThat(s.cie10("B2")).as("prefijo que cubre B20").isTrue();
        assertThat(s.cie10("B201")).isTrue();
        assertThat(s.cie10("E752")).as("lista de huérfanas cargada").isTrue();
        assertThat(s.cie10("I10")).isFalse();
        assertThat(s.atc("J05")).as("grupo ATC2 de los antirretrovirales").isTrue();
        assertThat(s.atc("J01")).isFalse();
        assertThat(s.listas()).contains("huerfanas");
        // Otra cohorte con B20 (otro código) sí; renal, pluripatológicos (lista de crónicos con B20–B24) y GRC no
        Criterio b20 = AnaliticaCohortes.leer(Map.of("tipo", "cie10", "valores", List.of("B20"), "ventanaMeses", 12));
        assertThat(s.criterio(b20)).isTrue();
        assertThat(s.criterio(pred.get("renal"))).isFalse();
        assertThat(s.criterio(pred.get("pluripatologicos"))).isFalse();
        assertThat(s.criterio(pred.get("grc"))).isFalse();
        assertThat(s.criterio(pred.get("huerfanas"))).isTrue();
        assertThat(AnaliticaCohortes.Sensibles.NINGUNO.criterio(b20)).isFalse();
    }
}
