package co.com.agsamericas.digsa.analitica.prediccion;

import co.com.agsamericas.digsa.analitica.CargueAplicado;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Entrenamiento automático de la predicción (analitica-cohortes-alertas, «Bitácora de entrenamientos»: «al aplicar un cargue que completa
 * un mes»). Escucha {@link CargueAplicado} (se publica después de confirmar el cargue, como para cohortes y alertas) y entrena en un único
 * hilo de fondo: el cargue no espera ni falla por esto. Qué se entrena lo decide {@link ServicioPrediccion#entrenarAlCorte}.
 */
@Component
class EntrenamientoAutomatico implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(EntrenamientoAutomatico.class);

    private final ServicioPrediccion servicio;
    private final PropiedadesPrediccion propiedades;
    private final ExecutorService hilo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "digsa-analitica-prediccion");
        t.setDaemon(true);
        return t;
    });

    EntrenamientoAutomatico(ServicioPrediccion servicio, PropiedadesPrediccion propiedades) {
        this.servicio = servicio;
        this.propiedades = propiedades;
    }

    @EventListener
    public void alAplicarCargue(CargueAplicado e) {
        if (propiedades.entrenar()) {
            hilo.execute(() -> correr(e.cargueId()));
        }
    }

    /**
     * Al arrancar (revisión A5): la cola es de memoria y un reinicio pierde el entrenamiento pendiente; se encola el del corte vigente en el
     * mismo hilo de fondo (como el «reinicio» de RecalculoRiesgo). La condición «completa un mes nuevo» evita duplicarlo si ya se hizo.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        if (propiedades.entrenar()) {
            hilo.execute(() -> {
                try {
                    servicio.corte(null).ifPresent(c -> correr(c.cargueId()));
                } catch (RuntimeException e) {
                    LOG.error("[analitica] No se pudo revisar el entrenamiento pendiente de la predicción al arrancar", e);
                }
            });
        }
    }

    /** Espera a que el hilo de fondo termine lo encolado hasta ahora (pruebas). */
    void esperarCola() throws Exception {
        hilo.submit(() -> { }).get(120, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void correr(long cargue) {
        try {
            int n = servicio.entrenarAlCorte(cargue);
            if (n > 0) {
                LOG.info("[analitica] Predicción: {} entrenamientos automáticos al corte {}", n, cargue);
            }
        } catch (RuntimeException e) {
            LOG.error("[analitica] Falló el entrenamiento automático de la predicción al corte {}", cargue, e);
        }
    }

    @Override
    public void destroy() {
        hilo.shutdownNow();
    }
}
