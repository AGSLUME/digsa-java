package co.com.agsamericas.digsa.soporte;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** Reloj de las pruebas: arranca en la hora real y se puede adelantar (inactividad, vencimientos, bloqueos). */
public class RelojMovible extends Clock {

    private volatile Duration desfase = Duration.ZERO;
    private final ZoneId zona;

    public RelojMovible(ZoneId zona) {
        this.zona = zona;
    }

    public void adelantar(Duration d) {
        desfase = desfase.plus(d);
    }

    public void reiniciar() {
        desfase = Duration.ZERO;
    }

    @Override
    public ZoneId getZone() {
        return zona;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        RelojMovible r = new RelojMovible(zone);
        r.desfase = desfase;
        return r;
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(desfase);
    }
}
