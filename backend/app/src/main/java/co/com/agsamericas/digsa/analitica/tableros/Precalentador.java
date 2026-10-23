package co.com.agsamericas.digsa.analitica.tableros;

import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.ConsultaAnalitica;
import co.com.agsamericas.digsa.reglas.AnaliticaConsulta.Tablero;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Precálculo de los tableros por defecto de un corte nuevo (revisión A3 2026-10-23, rendimiento): apenas se confirma la aplicación de un
 * cargue, en un hilo de fondo, calcula cada tablero disponible sin filtros (periodo por defecto, vista por defecto) para el alcance
 * nacional y para cada Fuerza del corte, y los deja en la caché de resultados. La primera apertura de la pantalla (la consulta más
 * frecuente) no espera los segundos del cálculo en frío. No cambia ninguna cifra: es el mismo cálculo de la petición.
 */
@Component
class Precalentador implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(Precalentador.class);

    private final ServicioConsultaAnalitica servicio;
    private final JdbcClient jdbc;
    private final PropiedadesTableros propiedades;
    private final ExecutorService hilo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "digsa-analitica-precalentar");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    Precalentador(ServicioConsultaAnalitica servicio, JdbcClient jdbc, PropiedadesTableros propiedades) {
        this.servicio = servicio;
        this.jdbc = jdbc;
        this.propiedades = propiedades;
    }

    /** Programa el precálculo del corte del cargue (después de confirmada su aplicación). */
    void programar(long cargueId) {
        if (!propiedades.precalentarTableros()) {
            return;
        }
        hilo.submit(() -> correr(cargueId));
    }

    private void correr(long cargueId) {
        long t0 = System.nanoTime();
        List<Alcance> alcances = new ArrayList<>();
        alcances.add(new Alcance.Todo());
        try {
            jdbc.sql("SELECT DISTINCT valor FROM ana_cargue_valores WHERE cargue_id = :c AND variable = 'fuerza' ORDER BY 1").param("c", cargueId)
                    .query(String.class).list().forEach(f -> alcances.add(new Alcance.Fuerza(f)));
            int n = 0;
            for (Alcance a : alcances) {
                for (Tablero t : Tablero.values()) {
                    if (t.disponible()) {
                        // Con el corte explícito: la llave de la caché es la del corte resuelto, la misma de una consulta sin corte
                        servicio.tablero(ConsultaAnalitica.tablero(t).conCorte(cargueId), a);
                        n++;
                    }
                }
            }
            LOG.info("[analitica] Tableros del corte {} precalculados: {} en {} ms", cargueId, n, (System.nanoTime() - t0) / 1_000_000);
        } catch (RuntimeException e) {
            // Solo una optimización: si falla, la pantalla calcula en la petición como siempre
            LOG.warn("[analitica] No se pudieron precalcular los tableros del corte {}", cargueId, e);
        }
    }

    @Override
    public void destroy() {
        hilo.shutdownNow();
    }
}
