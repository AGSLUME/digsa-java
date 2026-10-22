package co.com.agsamericas.digsa.alertas;

import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.reglas.Alertas;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Verificación de salud cada minuto (alerta «almacen_no_disponible», GMI P77 b: disponibilidad e integridad): la base responde a un
 * {@code SELECT 1} y el almacén escribe, lee y borra un archivo de prueba. Si algo falla se dispara la alerta crítica; al recuperarse,
 * se cierra sola. Si lo que falla es la base, no hay dónde encolar: se avisa directo por los puertos de correo y SMS a los últimos
 * destinatarios conocidos y, al volver, queda la constancia en los eventos. También cierra el bloqueo masivo cuando cede.
 */
@Component
public class MonitorSalud {

    private static final Logger LOG = LoggerFactory.getLogger(MonitorSalud.class);
    static final String BASE = "base";
    static final String ALMACEN = "almacen";

    public record Estado(boolean base, boolean almacen) {
    }

    private final JdbcClient jdbc;
    private final Almacen almacen;
    private final ServicioAlertas alertas;
    private final Clock reloj;
    /** Desde cuándo la base no responde en esta réplica (null = responde). */
    private volatile Instant baseCaidaDesde;

    public MonitorSalud(JdbcClient jdbc, Almacen almacen, ServicioAlertas alertas, Clock reloj) {
        this.jdbc = jdbc;
        this.almacen = almacen;
        this.alertas = alertas;
        this.reloj = reloj;
    }

    @Scheduled(fixedDelayString = "${digsa.salud.intervalo-ms:60000}", initialDelayString = "${digsa.salud.inicio-ms:30000}")
    public void programada() {
        try {
            verificar();
        } catch (RuntimeException e) {
            LOG.error("[salud] La verificación de salud falló", e);
        }
    }

    public Estado verificar() {
        boolean base = baseResponde();
        boolean alm = almacenResponde();
        if (!base) {
            if (baseCaidaDesde == null) {
                baseCaidaDesde = reloj.instant();
            }
            alertas.avisoSinBase(Alertas.ALMACEN_NO_DISPONIBLE, BASE, "La base de datos no responde a la verificación de salud"
                    + (alm ? "." : "; el almacén de archivos tampoco."));
            return new Estado(false, alm);
        }
        if (baseCaidaDesde != null) {
            alertas.registrarResuelta(Alertas.ALMACEN_NO_DISPONIBLE, BASE, "La base de datos no respondió a la verificación de salud desde " + baseCaidaDesde
                    + "; se recuperó.", baseCaidaDesde);
            baseCaidaDesde = null;
        }
        alertas.recordarDestinatarios(Alertas.ALMACEN_NO_DISPONIBLE);
        if (alm) {
            alertas.cerrar(Alertas.ALMACEN_NO_DISPONIBLE, ALMACEN, "El almacén de archivos volvió a responder");
        } else {
            alertas.disparar(Alertas.ALMACEN_NO_DISPONIBLE, ALMACEN, "El almacén de archivos no responde a la verificación de salud (escribir, leer y borrar).");
        }
        alertas.revisarBloqueoMasivo();
        return new Estado(true, alm);
    }

    private boolean baseResponde() {
        try {
            return Integer.valueOf(1).equals(jdbc.sql("SELECT 1").query(Integer.class).single());
        } catch (RuntimeException e) {
            LOG.error("[salud] La base de datos no responde: {}", e.getMessage());
            return false;
        }
    }

    /** Escribe, lee y borra un archivo de prueba en el almacén. */
    private boolean almacenResponde() {
        String ruta = "salud/" + UUID.randomUUID() + ".txt";
        byte[] prueba = ("salud " + reloj.instant()).getBytes(StandardCharsets.UTF_8);
        try {
            almacen.guardar(ruta, prueba);
            try (InputStream in = almacen.leer(ruta)) {
                return Arrays.equals(prueba, in.readAllBytes());
            } finally {
                almacen.descartar(ruta);
            }
        } catch (Exception e) {
            LOG.error("[salud] El almacén de archivos no responde: {}", e.getMessage());
            return false;
        }
    }
}
