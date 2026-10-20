package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import co.com.agsamericas.digsa.reglas.Reparto.Componente;
import co.com.agsamericas.digsa.reglas.Reparto.Integrante;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Portado de tests/reglas_test.php (estados de visita) y tests/reparto_test.php (equipo y reparto del formulario). */
class EstadosYRepartoTest {

    @Test
    @DisplayName("estados: acciones del coordinador y del equipo; reasignar y reabrir")
    void estados() {
        assertThat(EstadosVisita.acciones("programada", "opl", true, false)).containsExactly("editar", "reprogramar", "cancelar", "notificar");
        assertThat(EstadosVisita.acciones("notificada", "opl", true, false)).containsExactly("reasignar", "reprogramar", "cancelar");
        assertThat(EstadosVisita.permite("reasignar", "notificada") && EstadosVisita.permite("reasignar", "reprogramada")).isTrue();
        assertThat(EstadosVisita.permite("reasignar", "programada") || EstadosVisita.permite("reasignar", "ejecucion")).isFalse();
        assertThat(EstadosVisita.acciones("programada", "no-opl", true, false)).doesNotContain("notificar");
        assertThat(EstadosVisita.acciones("notificada", "opl", false, true)).containsExactly("iniciar");
        assertThat(EstadosVisita.acciones("ejecucion", "opl", false, true)).containsExactly("iniciar");
        assertThat(EstadosVisita.acciones("notificada", "opl", false, false)).isEmpty();
        assertThat(EstadosVisita.acciones("cerrada", "opl", true, true)).containsExactly("reabrir");
        assertThat(EstadosVisita.acciones("cerrada", "opl", false, true)).isEmpty();
        assertThat(EstadosVisita.permite("reabrir", "cerrada")).isTrue();
        assertThat(EstadosVisita.permite("reabrir", "ejecucion") || EstadosVisita.permite("reabrir", "cancelada")).isFalse();
        assertThat(EstadosVisita.MOTIVOS_REAPERTURA.keySet()).containsExactly("soporte", "cliente");
    }

    @Test
    @DisplayName("roles, ítems de sistemas (SO-10 no) y constantes")
    void constantes() {
        assertThat(Reparto.ROLES.keySet()).containsExactly("quimico_farmaceutico", "regente", "auditor_salud", "ingeniero_sistemas", "otro");
        assertThat(Reparto.SISTEMAS).containsExactly("SO-01", "NC-04", "NC-05", "P04-16", "I05-16").doesNotContain("SO-10");
        assertThat(Reparto.MAX_OTROS).isEqualTo(5);
        assertThat(Reparto.PESO_OBLIGACIONES).isEqualTo(10);
        assertThat(Reparto.esAsistencial("otro")).isTrue();
        assertThat(Reparto.esAsistencial("ingeniero_sistemas")).isFalse();
    }

    private static final List<Componente> CATALOGO = List.of(
            new Componente("C01", Set.of("ESM", "FA"), List.of("P01-01", "P01-02", "P01-03", "SO-01", "SO-10")),
            new Componente("C02", Set.of("ESM"), List.of("P02-01", "P02-02", "NC-04", "NC-05")),
            new Componente("C03", Set.of("FA"), List.of("P03-01", "P03-02", "P03-03", "I05-16")),
            new Componente("C04", Set.of("ESM", "FA"), List.of("P04-16")),
            new Componente("C05", Set.of("ESM", "FA"), List.of("P05-01", "P05-02", "P05-03", "P05-04", "P05-05", "P05-06")));

    @Test
    @DisplayName("partes: componentes de la unidad + OBL; con ingeniero, SIS y los componentes vacíos desaparecen")
    void partes() {
        assertThat(Reparto.partes(CATALOGO, "ESM", false)).containsOnly(entry("C01", 5), entry("C02", 4), entry("C04", 1), entry("C05", 6), entry("OBL", 10));
        assertThat(Reparto.partes(CATALOGO, "ESM", true)).containsOnly(entry("C01", 4), entry("C02", 2), entry("C05", 6), entry("OBL", 10), entry("SIS", 4));
        assertThat(Reparto.partes(CATALOGO, "FA", true)).containsOnly(entry("C01", 4), entry("C03", 3), entry("C05", 6), entry("OBL", 10), entry("SIS", 3));
        assertThat(Reparto.partes(CATALOGO, "FA", false)).containsOnly(entry("C01", 5), entry("C03", 4), entry("C04", 1), entry("C05", 6), entry("OBL", 10));
        assertThat(Reparto.partes(List.of(new Componente("C09", Set.of("ESM"), List.of("P09-01", "P09-02"))), "ESM", true))
                .containsOnly(entry("C09", 2), entry("OBL", 10));
        assertThat(Reparto.partes(CATALOGO, "XYZ", true)).containsOnly(entry("OBL", 10));
        assertThat(Reparto.partes(List.of(), "ESM", false)).containsOnly(entry("OBL", 10));
    }

    private static Map<String, Integer> m(Object... kv) {
        Map<String, Integer> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            r.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return r;
    }

    @Test
    @DisplayName("repartir: ejemplos del PHP (ingeniero, empates, varios ingenieros, uno solo, solo ingenieros)")
    void repartir() {
        List<Integrante> equipo = List.of(new Integrante(7, "regente", true), new Integrante(8, "quimico_farmaceutico", false),
                new Integrante(9, "ingeniero_sistemas", false));
        Map<String, Long> r = Reparto.repartir(m("C01", 4, "C02", 2, "C05", 6, "OBL", 10, "SIS", 4), equipo);
        assertThat(r).containsOnly(entry("OBL", 7L), entry("C05", 8L), entry("C01", 8L), entry("C02", 7L), entry("SIS", 9L));
        assertThat(Reparto.repartir(m("C01", 4, "C02", 2, "C05", 6, "OBL", 10, "SIS", 4), equipo)).isEqualTo(r);
        assertThat(Reparto.repartir(m("C02", 3, "C01", 3), List.of(new Integrante(1, "quimico_farmaceutico", true), new Integrante(2, "regente", false))))
                .containsOnly(entry("C01", 1L), entry("C02", 2L));
        assertThat(Reparto.repartir(m("C01", 1, "C02", 1, "C03", 1, "C04", 1), List.of(new Integrante(3, "auditor_salud", false), new Integrante(1, "regente", true))))
                .containsOnly(entry("C01", 3L), entry("C02", 1L), entry("C03", 3L), entry("C04", 1L));
        r = Reparto.repartir(m("C01", 4, "C02", 2, "OBL", 10, "SIS", 4), List.of(new Integrante(1, "regente", true),
                new Integrante(2, "ingeniero_sistemas", false), new Integrante(3, "ingeniero_sistemas", false)));
        assertThat(r.get("SIS")).isEqualTo(2L);
        assertThat(List.of(r.get("C01"), r.get("C02"), r.get("OBL"))).containsOnly(1L);
        assertThat(Reparto.repartir(m("C01", 4, "OBL", 10), List.of(new Integrante(5, "ingeniero_sistemas", false), new Integrante(6, "otro", true))))
                .containsOnly(entry("C01", 6L), entry("OBL", 6L));
        r = Reparto.repartir(m("C01", 4, "OBL", 10, "SIS", 3), List.of(new Integrante(5, "ingeniero_sistemas", true), new Integrante(6, "ingeniero_sistemas", false)));
        assertThat(r.get("SIS")).isIn(5L, 6L);
        assertThat(r.values()).contains(5L, 6L).hasSize(3);
        assertThat(Reparto.repartir(m("C01", 5, "C02", 4, "OBL", 10), List.of(new Integrante(4, "auditor_salud", true))))
                .containsOnly(entry("C01", 4L), entry("C02", 4L), entry("OBL", 4L));
        assertThat(Reparto.repartir(m("C01", 5, "OBL", 10, "SIS", 2), List.of(new Integrante(9, "ingeniero_sistemas", true))))
                .containsOnly(entry("C01", 9L), entry("OBL", 9L), entry("SIS", 9L));
    }

    @Test
    @DisplayName("repartir: equidad con 6 auditores (todos reciben y la diferencia no pasa de la parte más pesada)")
    void equidad() {
        Map<String, Integer> grandes = new LinkedHashMap<>();
        grandes.put("OBL", 10);
        for (int i = 1; i <= 12; i++) {
            grandes.put(String.format("C%02d", i), (i % 5) + 2);
        }
        List<Integrante> seis = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            seis.add(new Integrante(100 + i, i % 2 == 1 ? "regente" : "quimico_farmaceutico", i == 1));
        }
        Map<String, Long> r = Reparto.repartir(grandes, seis);
        Map<Long, Integer> cargas = new HashMap<>();
        seis.forEach(s -> cargas.put(s.id(), 0));
        r.forEach((parte, id) -> cargas.merge(id, grandes.get(parte), Integer::sum));
        assertThat(cargas.values()).allMatch(c -> c > 0);
        assertThat(cargas.values().stream().mapToInt(Integer::intValue).max().orElseThrow()
                - cargas.values().stream().mapToInt(Integer::intValue).min().orElseThrow()).isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("puedeRecibir: SIS solo al ingeniero; con asistenciales, lo demás no al ingeniero")
    void puedeRecibir() {
        List<Integrante> mixto = List.of(new Integrante(7, "regente", true), new Integrante(8, "quimico_farmaceutico", false),
                new Integrante(9, "ingeniero_sistemas", false));
        assertThat(Reparto.puedeRecibir("SIS", "ingeniero_sistemas", mixto)).isTrue();
        assertThat(Reparto.puedeRecibir("SIS", "regente", mixto)).isFalse();
        assertThat(Reparto.puedeRecibir("C01", "regente", mixto)).isTrue();
        assertThat(Reparto.puedeRecibir("OBL", "quimico_farmaceutico", mixto)).isTrue();
        assertThat(Reparto.puedeRecibir("C01", "ingeniero_sistemas", mixto)).isFalse();
        assertThat(Reparto.puedeRecibir("C01", "otro", mixto)).isTrue();
        List<Integrante> soloIng = List.of(new Integrante(5, "ingeniero_sistemas", true), new Integrante(6, "ingeniero_sistemas", false));
        assertThat(Reparto.puedeRecibir("C01", "ingeniero_sistemas", soloIng) && Reparto.puedeRecibir("OBL", "ingeniero_sistemas", soloIng)
                && Reparto.puedeRecibir("SIS", "ingeniero_sistemas", soloIng)).isTrue();
        assertThat(Reparto.puedeRecibir("SIS", "auditor_salud", List.of(new Integrante(1, "regente", true), new Integrante(2, "auditor_salud", false))))
                .isFalse();
        assertThat(Reparto.parteDeItem("SO-01", "C01", true)).isEqualTo("SIS");
        assertThat(Reparto.parteDeItem("SO-01", "C01", false)).isEqualTo("C01");
        assertThat(Reparto.parteDeItem("SO-10", "C01", true)).isEqualTo("C01");
    }
}
