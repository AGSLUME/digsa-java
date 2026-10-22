package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.PropuestaAgenda.Auditor;
import co.com.agsamericas.digsa.reglas.PropuestaAgenda.Fijo;
import co.com.agsamericas.digsa.reglas.PropuestaAgenda.Item;
import co.com.agsamericas.digsa.reglas.PropuestaAgenda.Punto;
import co.com.agsamericas.digsa.reglas.PropuestaAgenda.Resultado;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** Propuesta de agenda: las 26 pruebas de tests/propuesta_agenda_test.php del PHP, más el año de contrato (decisión 13.4). */
class PropuestaAgendaTest {

    // Coordenadas del DANE (redondeadas): Bogotá, Medellín, Ibagué, Neiva, Chaparral (Tolima), Soacha, Turbo (Urabá)
    static final Map<String, double[]> C = Map.of("11001", new double[] {4.649, -74.107}, "05001", new double[] {6.247, -75.582}, "73001",
            new double[] {4.439, -75.232}, "41001", new double[] {2.926, -75.289}, "73168", new double[] {3.724, -75.484}, "25754",
            new double[] {4.579, -74.214}, "05837", new double[] {8.093, -76.728});
    static final List<Auditor> AUDITORES = List.of(new Auditor(1, "QF Bogotá", "quimico_farmaceutico", "11001", 0),
            new Auditor(2, "Regente Medellín", "regente", "05001", 0), new Auditor(3, "Auditor Neiva", "auditor_salud", "41001", 0),
            new Auditor(4, "Ingeniero Bogotá", "ingeniero_sistemas", "11001", 0));
    static final List<LocalDate> DIAS = List.of(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6));
    static final LocalDate D3 = LocalDate.of(2026, 11, 3);
    static final LocalDate D4 = LocalDate.of(2026, 11, 4);

    static Punto pt(long id, String mun) {
        return new Punto(id, "Punto " + id, "ESM", mun, null, null, false);
    }

    static Resultado gen(List<Punto> p, List<Auditor> a, int porDia) {
        return PropuestaAgenda.generar(p, a, C, DIAS, porDia, 40, Map.of(), Map.of());
    }

    static Map<Long, Item> porPunto(Resultado r) {
        return r.items().stream().collect(Collectors.toMap(Item::puntoId, Function.identity()));
    }

    @Test
    void distanciaHaversine() {
        double km = PropuestaAgenda.distanciaKm(C.get("11001"), C.get("05001"));
        assertThat(km).isBetween(230.0, 250.0);
    }

    @Test
    void cercaniaRequisitoYSeLlegaDesde() {
        Map<Long, Item> r = porPunto(gen(List.of(pt(10, "05001"), pt(11, "25754"), new Punto(12, "Punto 12", "ESM", "73168", "41001", null, false)), AUDITORES, 2));
        assertThat(r.get(10L).auditorId()).as("Medellín: el auditor de Medellín").isEqualTo(2);
        assertThat(r.get(11L).auditorId()).as("Soacha: el de Bogotá; el ingeniero no lidera").isEqualTo(1);
        assertThat(r.get(12L).auditorId()).as("Chaparral con acceso desde Neiva").isEqualTo(3);
        Map<Long, Item> sin = porPunto(gen(List.of(pt(12, "73168")), AUDITORES.subList(0, 3), 2));
        assertThat(sin.get(12L).km()).as("sin «se llega desde» se mide desde Chaparral").isNotNull().isLessThan(r.get(12L).km() + 200);
    }

    @Test
    void exigeQuimicoFarmaceutico() {
        assertThat(porPunto(gen(List.of(new Punto(20, "P", "ESM", "05001", null, null, true)), AUDITORES, 2)).get(20L).auditorId()).isEqualTo(1);
        Resultado sinQf = gen(List.of(new Punto(21, "P", "ESM", "05001", null, null, true)), AUDITORES.subList(1, 4), 2);
        assertThat(sinQf.items().get(0).auditorId()).isNull();
        assertThat(sinQf.resumen().cabe()).isFalse();
        assertThat(String.join(" ", sinQf.alertas())).contains("sin auditor");
        assertThat(sinQf.items().get(0).alertas()).contains("No hay químico farmacéutico activo para este punto.");
    }

    @Test
    void auditorAsignado() {
        Item a = porPunto(gen(List.of(new Punto(30, "P", "ESM", "05001", null, 1L, false)), AUDITORES, 2)).get(30L);
        assertThat(a.auditorId()).isEqualTo(1);
        assertThat(a.motivo()).isEqualTo("Auditor asignado al punto");
        Item mal = porPunto(gen(List.of(new Punto(31, "P", "ESM", "05001", null, 4L, false)), AUDITORES, 2)).get(31L);
        assertThat(mal.auditorId()).as("el ingeniero asignado no lidera: se busca otro").isEqualTo(2);
        assertThat(mal.alertas()).isNotEmpty();
    }

    @Test
    void repartoDeLaCarga() {
        List<Auditor> dos = List.of(new Auditor(1, "A", "quimico_farmaceutico", "11001", 0), new Auditor(5, "B", "regente", "11001", 0));
        Resultado bog = gen(LongStream.rangeClosed(40, 45).mapToObj(i -> pt(i, "11001")).toList(), dos, 2);
        Map<Long, Long> cuenta = bog.items().stream().collect(Collectors.groupingBy(Item::auditorId, Collectors.counting()));
        assertThat(cuenta).containsEntry(1L, 3L).containsEntry(5L, 3L);
    }

    @Test
    void maximoPorDiaMunicipiosLejanosYLoQueNoCabe() {
        List<Auditor> uno = List.of(AUDITORES.get(1));
        Map<LocalDate, Long> porDia = gen(List.of(pt(50, "05001"), pt(51, "05001"), pt(52, "05001")), uno, 2).items().stream()
                .collect(Collectors.groupingBy(Item::fecha, Collectors.counting()));
        assertThat(porDia.values()).containsExactlyInAnyOrder(2L, 1L);
        Map<Long, Item> u = porPunto(gen(List.of(pt(53, "05001"), pt(54, "05837")), uno, 2));
        assertThat(u.get(53L).fecha()).as("Medellín y Urabá nunca el mismo día").isNotEqualTo(u.get(54L).fecha());
        Resultado lleno = gen(LongStream.rangeClosed(60, 69).mapToObj(i -> pt(i, "05001")).toList(), uno, 2);
        assertThat(lleno.items().stream().filter(x -> x.fecha() != null).count()).isEqualTo(8);
        assertThat(lleno.resumen().cabe()).isFalse();
        Resultado ocup = PropuestaAgenda.generar(List.of(pt(70, "05001")), uno, C, DIAS, 1, 40, Map.of(2L, Map.of(D3, List.of("05001"))), Map.of());
        assertThat(ocup.items().get(0).fecha()).as("respeta lo ya agendado").isEqualTo(D4);
        Resultado lejos = PropuestaAgenda.generar(List.of(pt(98, "05001")), uno, C, DIAS, 2, 40, Map.of(2L, Map.of(D3, List.of("05837"))), Map.of());
        assertThat(lejos.items().get(0).fecha()).as("visita ya agendada lejos: ese día no se usa").isEqualTo(D4);
    }

    @Test
    void nivelCentralSumaElIngeniero() {
        Item nc = porPunto(gen(List.of(new Punto(80, "P", "NC", "11001", null, null, false)), AUDITORES, 2)).get(80L);
        assertThat(nc.otros()).containsExactly(4L);
        assertThat(nc.auditorId()).isEqualTo(1);
        Resultado ocupado = PropuestaAgenda.generar(List.of(new Punto(99, "P", "NC", "11001", null, null, false)), AUDITORES, C, DIAS, 1, 40,
                Map.of(4L, Map.of(D3, List.of("11001"))), Map.of());
        assertThat(ocupado.items().get(0).fecha()).as("el ingeniero ocupado corre la fecha").isEqualTo(D4);
    }

    @Test
    void loFijadoSeRespeta() {
        List<Auditor> uno = List.of(AUDITORES.get(1));
        Map<Long, Item> f = porPunto(PropuestaAgenda.generar(List.of(pt(90, "05001"), pt(91, "05001")), uno, C, DIAS, 1, 40, Map.of(),
                Map.of(90L, new Fijo(2L, List.of(), D3))));
        assertThat(f.get(90L).fijo()).isTrue();
        assertThat(f.get(90L).fecha()).isEqualTo(D3);
        assertThat(f.get(91L).fecha()).as("lo demás se acomoda alrededor").isEqualTo(D4);
        Resultado ya = PropuestaAgenda.generar(List.of(pt(100, "05001")), uno, C, DIAS, 2, 40, Map.of(), Map.of(555L, new Fijo(2L, List.of(), D3)));
        assertThat(ya.items()).extracting(Item::puntoId).as("fijo de un punto que ya no está pendiente").containsExactly(100L);
    }

    @Test
    void sinCoordenadasNiMunicipioBaseNiDias() {
        List<Auditor> uno = List.of(AUDITORES.get(1));
        Item sinC = gen(List.of(pt(95, "99999")), uno, 2).items().get(0);
        assertThat(sinC.auditorId()).isEqualTo(2);
        assertThat(String.join(" ", sinC.alertas())).contains("coordenadas");
        List<Auditor> sinBase = List.of(new Auditor(7, "Sin base", "regente", null, 0), AUDITORES.get(1));
        assertThat(porPunto(gen(List.of(pt(96, "05001")), sinBase, 2)).get(96L).auditorId()).isEqualTo(2);
        Item soloSinBase = gen(List.of(pt(97, "05001")), List.of(sinBase.get(0)), 2).items().get(0);
        assertThat(soloSinBase.auditorId()).isEqualTo(7);
        assertThat(String.join(" ", soloSinBase.alertas())).contains("municipio base");
        Resultado sinDias = PropuestaAgenda.generar(List.of(pt(101, "05001")), uno, C, List.of(), 2, 40, Map.of(), Map.of());
        assertThat(sinDias.items().get(0).fecha()).isNull();
        assertThat(sinDias.alertas()).contains("No hay días hábiles entre la primera fecha posible y el límite del ciclo.");
    }

    @Test
    void diasPosiblesRespetanLaNotificacionYLosFestivos() {
        Plazos p = new Plazos();
        LocalDate hoy = LocalDate.of(2026, 10, 16);
        List<LocalDate> d = PropuestaAgenda.dias(p, hoy, hoy, LocalDate.of(2026, 11, 20));
        assertThat(d).isNotEmpty();
        assertThat(p.limiteNotificacion("opl", d.get(0))).as("el primer día deja los 15 días de notificación").isAfterOrEqualTo(hoy);
        assertThat(d).doesNotContain(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1));
    }

    @Test
    void anioDeContrato() {
        LocalDate inicio = LocalDate.of(2027, 1, 15);
        assertThat(PropuestaAgenda.anioDeContrato(inicio, 2)).containsExactly(LocalDate.of(2028, 1, 15), LocalDate.of(2029, 1, 14));
        assertThat(PropuestaAgenda.anioDe(inicio, LocalDate.of(2028, 1, 14))).isEqualTo(1);
        assertThat(PropuestaAgenda.anioDe(inicio, LocalDate.of(2028, 1, 15))).isEqualTo(2);
    }
}
