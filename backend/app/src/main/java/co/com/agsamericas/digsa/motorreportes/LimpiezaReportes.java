package co.com.agsamericas.digsa.motorreportes;

import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Limpieza de las descargas del día (Anexo 5, 2.7; Ley 1581): cada minuto, con candado entre réplicas, pasa a «vencida» lo que
 * terminó su día y borra el archivo del almacén (el registro queda para verificar), y repone como «fallida» las generaciones que se
 * interrumpieron (reinicio o caída). Si el servidor estuvo apagado a medianoche, lo hace en la primera vuelta al arrancar. Los cambios
 * de estado van con el candado; el disco se toca después, fuera de la transacción, y «archivo borrado» se anota solo si el archivo ya
 * no está (si no se pudo borrar, se reintenta en la siguiente vuelta).
 */
@Component
public class LimpiezaReportes {

    private static final Logger LOG = LoggerFactory.getLogger(LimpiezaReportes.class);
    /** Llave del candado de la limpieza (pg_try_advisory_xact_lock): una réplica a la vez. */
    static final long CANDADO = 0x5245504F52544553L; // «REPORTES»
    /** Margen sobre el tope técnico antes de dar por interrumpida una generación. */
    static final Duration MARGEN = Duration.ofMinutes(5);

    /** Resultado de una vuelta. */
    public record Vuelta(boolean candado, int vencidas, int huerfanas) {
    }

    private final RegistroGenerados registro;
    private final Almacen almacen;
    private final RegistroBitacora bitacora;
    private final ConfiguracionReportes cfg;
    private final JdbcClient jdbc;
    private final Clock reloj;
    private final TransactionTemplate tx;

    public LimpiezaReportes(RegistroGenerados registro, Almacen almacen, RegistroBitacora bitacora, ConfiguracionReportes cfg, JdbcClient jdbc, Clock reloj,
                            PlatformTransactionManager transacciones) {
        this.registro = registro;
        this.almacen = almacen;
        this.bitacora = bitacora;
        this.cfg = cfg;
        this.jdbc = jdbc;
        this.reloj = reloj;
        this.tx = new TransactionTemplate(transacciones);
    }

    @Scheduled(fixedDelayString = "${digsa.reportes.limpieza-ms:60000}", initialDelayString = "${digsa.reportes.limpieza-inicial-ms:30000}")
    public void programada() {
        try {
            limpiar();
        } catch (RuntimeException e) {
            LOG.error("[reportes] Falló la limpieza de las descargas del día", e);
        }
    }

    /** Una vuelta de limpieza (también la llaman las pruebas). */
    public Vuelta limpiar() {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        Duration tope = Duration.ofMillis(cfg.topeTecnicoMs());
        record Cambios(List<Map.Entry<Long, String>> porBorrar, List<RegistroGenerados.Huerfana> huerfanas) {
        }
        Cambios c = tx.execute(t -> {
            if (!Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(:k)").param("k", CANDADO).query(Boolean.class).single())) {
                return null; // otra réplica está limpiando
            }
            // Una generación (lectura y archivo) dura a lo sumo el tope técnico: pasado el tope y el margen, se interrumpió. Una
            // «pendiente» espera a lo sumo que pase la cola entera delante de ella
            return new Cambios(registro.vencer(ahora), registro.reponerHuerfanas(ahora.minus(tope.plus(MARGEN)), ahora.minus(pendienteMaxima(tope)), ahora));
        });
        if (c == null) {
            return new Vuelta(false, 0, 0);
        }
        int borrados = 0;
        for (Map.Entry<Long, String> v : c.porBorrar()) {
            if (borrar(v.getValue())) {
                registro.archivoBorrado(v.getKey(), ahora);
                borrados++;
            } else {
                LOG.error("[reportes] No se pudo borrar el archivo vencido {} (Ley 1581): se reintenta en la siguiente vuelta", v.getValue());
            }
        }
        if (borrados > 0) {
            bitacora.registrar("reporte_vencido", borrados + " archivos de las descargas del día borrados al terminar su día", null, "sistema");
        }
        for (RegistroGenerados.Huerfana h : c.huerfanas()) {
            borrar(ServicioGeneracion.ruta(h.codigo(), h.creado(), h.formato())); // lo que haya quedado a medias
            LOG.warn("[reportes] Generación {} interrumpida: queda «fallida»", h.codigo());
        }
        return new Vuelta(true, c.porBorrar().size(), c.huerfanas().size());
    }

    /** Lo más que espera una «pendiente» legítima: la cola entera delante, a la concurrencia de la instancia, más una vuelta. */
    Duration pendienteMaxima(Duration tope) {
        return tope.multipliedBy(ServicioGeneracion.COLA / cfg.concurrencia() + 1L).plus(MARGEN);
    }

    /** Borra y comprueba: true si el archivo ya no está (el almacén no avisa si el borrado falló). */
    private boolean borrar(String ruta) {
        almacen.descartar(ruta);
        try (InputStream sigue = almacen.leer(ruta)) {
            return false;
        } catch (IOException e) {
            return true; // ya no existe
        } catch (IllegalArgumentException e) {
            LOG.error("[reportes] Ruta no válida en el registro de reportes: {}", ruta);
            return false;
        }
    }
}
