package co.com.agsamericas.digsa.reglas;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Plazos.Alerta;
import co.com.agsamericas.digsa.reglas.Plazos.Equipo;
import co.com.agsamericas.digsa.reglas.Plazos.Nivel;
import co.com.agsamericas.digsa.reglas.Plazos.Punto;
import co.com.agsamericas.digsa.reglas.Plazos.Tipo;
import co.com.agsamericas.digsa.reglas.Plazos.Visita;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Portado de tests/plazos_test.php (días hábiles, límites de notificación, alertas y visita a necesidad). */
class PlazosTest {

    private static final Plazos P = new Plazos();
    private static final Tipo OPL = new Tipo("inicial", "opl", "Dispensación", "Anexo 3 p.12; p.9");
    private static final Tipo REQ = new Tipo("requerimiento", "modulo", null, "Anexo 3 p.13-14");
    private static final Tipo NEC = new Tipo("necesidad", "no-opl", null, "Anexo 3 p.14");
    private static final Punto ESM = new Punto("ESM Demo", "ESM", "Dispensación", false);
    private static final Equipo AUD = new Equipo("Auditor", false);
    private static final LocalDate HOY = d("2026-10-14");

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    private static String textos(List<Alerta> a) {
        return a.stream().map(Alerta::texto).collect(Collectors.joining(" | "));
    }

    private static List<Alerta> alertas(Plazos p, String fecha, String estado, Tipo tipo, Punto punto, Equipo eq, LocalDate hoy) {
        return p.alertas(new Visita(d(fecha), estado, null), tipo, punto, eq, hoy);
    }

    @Test
    @DisplayName("días hábiles: saltan fines de semana y festivos (12-oct, 2-nov, Reyes 2028) y los ajustes de la tabla")
    void habiles() {
        assertThat(P.sumarHabiles(d("2026-10-09"), 5)).isEqualTo(d("2026-10-19"));
        assertThat(P.restarHabiles(d("2026-11-03"), 3)).isEqualTo(d("2026-10-28"));
        assertThat(P.sumarHabiles(d("2027-12-31"), 1)).isEqualTo(d("2028-01-03"));
        assertThat(P.sumarHabiles(d("2028-01-07"), 1)).isEqualTo(d("2028-01-11"));
        assertThat(new Plazos(Set.of(d("2026-10-15")), Map.of()).sumarHabiles(d("2026-10-14"), 1)).isEqualTo(d("2026-10-16"));
        assertThat(Plazos.fechaLarga(d("2026-10-19"))).contains("oct");
    }

    @Test
    @DisplayName("límite de 15 días: si cae en domingo o festivo se corre al hábil anterior; necesidad sin límite")
    void limiteNotificacion() {
        assertThat(P.limiteNotificacion("opl", d("2026-11-16"))).isEqualTo(d("2026-10-30"));
        assertThat(P.limiteNotificacion("opl", d("2026-10-27"))).isEqualTo(d("2026-10-09"));
        assertThat(P.limiteNotificacion("opl", d("2026-10-29"))).isEqualTo(d("2026-10-14"));
        assertThat(P.limiteNotificacion("no-opl", d("2026-10-29"))).isNull();
    }

    @Test
    @DisplayName("alertas de notificación al OPL: crítica, informativa, advertencia el día límite y nada si ya está notificada")
    void alertasOpl() {
        List<Alerta> a = alertas(P, "2026-10-20", "programada", OPL, ESM, AUD, HOY);
        assertThat(Plazos.nivel(a)).isEqualTo("bad");
        assertThat(textos(a)).contains("15 días");
        a = alertas(P, "2026-11-20", "programada", OPL, ESM, AUD, HOY);
        assertThat(Plazos.nivel(a)).isEmpty();
        assertThat(textos(a)).contains("Notificar a más tardar");
        a = alertas(P, "2026-10-29", "programada", OPL, ESM, AUD, HOY);
        assertThat(Plazos.nivel(a)).isEqualTo("warn");
        assertThat(textos(a)).contains("(hoy;");
        assertThat(textos(alertas(P, "2026-10-20", "notificada", OPL, ESM, AUD, HOY))).doesNotContain("otificar");
    }

    @Test
    @DisplayName("umbrales de advertencia como parámetros")
    void umbrales() {
        Plazos p10 = new Plazos(Set.of(), Map.of("aviso_umbral_opl", 10));
        assertThat(Plazos.nivel(alertas(p10, "2026-11-06", "programada", OPL, ESM, AUD, HOY))).isEqualTo("warn");
        assertThat(Plazos.nivel(alertas(P, "2026-11-06", "programada", OPL, ESM, AUD, HOY))).isEmpty();
    }

    @Test
    @DisplayName("requerimiento: N días hábiles (parámetro); reprogramada también exige notificar")
    void requerimiento() {
        List<Alerta> a = alertas(P, "2026-10-16", "programada", REQ, ESM, AUD, HOY);
        assertThat(Plazos.nivel(a)).isEqualTo("bad");
        assertThat(textos(a)).contains("3 días hábiles");
        a = alertas(P, "2026-10-19", "programada", REQ, ESM, AUD, HOY);
        assertThat(Plazos.nivel(a)).isEqualTo("warn");
        assertThat(textos(a)).contains("hoy");
        a = alertas(P, "2026-10-20", "programada", REQ, ESM, AUD, HOY);
        assertThat(textos(a)).contains("falta 1 día;");
        assertThat(textos(alertas(new Plazos(Set.of(), Map.of("notif_habiles_modulo", 5)), "2026-10-20", "programada", REQ, ESM, AUD, HOY)))
                .contains("5 días hábiles");
        assertThat(textos(alertas(P, "2026-10-20", "reprogramada", REQ, ESM, AUD, HOY))).contains("Notificar");
    }

    @Test
    @DisplayName("necesidad sin solicitud, fecha pasada sin iniciar, cerrada, químico farmacéutico y modalidad")
    void otras() {
        assertThat(alertas(P, "2026-10-22", "programada", NEC, ESM, AUD, HOY)).isEmpty();
        assertThat(textos(alertas(P, "2026-10-10", "notificada", OPL, ESM, AUD, HOY))).contains("ya pasó");
        assertThat(alertas(P, "2026-10-10", "cerrada", OPL, ESM, AUD, HOY)).isEmpty();
        Punto hmc = new Punto("HMC", "ESM", "Dispensación", true);
        assertThat(textos(alertas(P, "2026-12-01", "notificada", OPL, hmc, AUD, HOY))).contains("químico farmacéutico");
        assertThat(textos(alertas(P, "2026-12-01", "notificada", OPL, hmc, new Equipo("QF", true), HOY))).doesNotContain("químico");
        Punto porNombre = new Punto("Hospital Militar Central", "ESM", "Dispensación", false);
        assertThat(textos(alertas(P, "2026-12-01", "notificada", OPL, porNombre, AUD, HOY))).as("el nombre no cuenta").doesNotContain("químico");
        Punto sum = new Punto("ESM Sum", "ESM", "Suministro", false);
        assertThat(textos(alertas(P, "2026-12-01", "notificada", OPL, sum, AUD, HOY))).contains("modalidad suministro");
    }

    @Test
    @DisplayName("visita a necesidad: límite de 5 días hábiles desde la solicitud (decisión 12)")
    void necesidad() {
        assertThat(Plazos.REGLAS.get("necesidad_habiles")).isEqualTo(5);
        assertThat(P.limiteNecesidad(d("2026-10-15"))).isEqualTo(d("2026-10-22"));
        assertThat(P.limiteNecesidad(d("2026-10-16"))).isEqualTo(d("2026-10-23"));
        assertThat(P.limiteNecesidad(d("2026-11-13"))).isEqualTo(d("2026-11-23"));

        assertThat(textos(nec("2026-10-20", "programada", "2026-10-15", "2026-10-15"))).doesNotContain("5 días hábiles", "venció");
        assertThat(textos(nec("2026-10-22", "programada", "2026-10-15", "2026-10-15"))).doesNotContain("5 días hábiles");
        assertThat(nec("2026-10-26", "programada", "2026-10-15", "2026-10-15")).anyMatch(x -> x.nivel() == Nivel.BAD
                && x.texto().contains("5 días hábiles") && x.texto().contains("Anexo 3 p.14"));
        assertThat(nec("2026-10-23", "programada", "2026-10-15", "2026-10-15")).anyMatch(x -> x.nivel() == Nivel.BAD);
        assertThat(nec("2026-10-07", "programada", "2026-10-01", "2026-10-14")).anyMatch(x -> x.nivel() == Nivel.BAD && x.texto().contains("venció"));
        assertThat(nec("2026-10-07", "reprogramada", "2026-10-01", "2026-10-14")).anyMatch(x -> x.texto().contains("venció"));
        assertThat(textos(nec("2026-10-07", "programada", "2026-10-01", "2026-10-08"))).doesNotContain("venció");
        assertThat(textos(nec("2026-10-07", "ejecucion", "2026-10-01", "2026-10-14"))).doesNotContain("venció");
        assertThat(nec("2026-10-07", "cerrada", "2026-10-01", "2026-10-14")).isEmpty();
        assertThat(textos(P.alertas(new Visita(d("2026-10-07"), "programada", null), NEC, ESM, AUD, HOY))).doesNotContain("5 días hábiles", "venció");
        assertThat(textos(P.alertas(new Visita(d("2026-12-01"), "programada", d("2026-10-01")), OPL, ESM, AUD, HOY))).doesNotContain("venció");
        assertThat(textos(nec("2026-10-26", "programada", "2026-10-15", "2026-10-15"))).as("nunca aviso al OPL (decisión 31)").doesNotContain("otificar");
    }

    private static List<Alerta> nec(String fecha, String estado, String solicitud, String hoy) {
        return P.alertas(new Visita(d(fecha), estado, d(solicitud)), NEC, ESM, AUD, d(hoy));
    }
}
