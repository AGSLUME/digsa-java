package co.com.agsamericas.digsa.analitica;

import co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validador de arranque propio de la analítica (aprobación de A0: con DIGSA_ENTORNO=produccion y sin DIGSA_ANALITICA_CLAVE_SEUDONIMO la
 * aplicación no arranca). En producción la clave debe tener al menos 32 caracteres. Sin variable: en local se usa una clave fija de
 * desarrollo; en cualquier otro entorno («pruebas», la prueba en sitio) la aplicación arranca, pero todo cargue se rechaza con un
 * mensaje claro y la pantalla de cargues lo advierte: nunca una clave aleatoria, que cambiaría los seudónimos en cada reinicio. El
 * mensaje nombra la variable, nunca su valor.
 */
@Component
public class ValidacionArranqueAnalitica {

    private static final Logger LOG = LoggerFactory.getLogger(ValidacionArranqueAnalitica.class);
    static final int LARGO_MINIMO = 32;

    private final ConfiguracionDigsa cfg;
    private final PropiedadesAnalitica analitica;

    public ValidacionArranqueAnalitica(ConfiguracionDigsa cfg, PropiedadesAnalitica analitica) {
        this.cfg = cfg;
        this.analitica = analitica;
    }

    @PostConstruct
    void validar() {
        List<String> p = problemas(cfg.esProduccion(), analitica);
        if (!p.isEmpty()) {
            throw new IllegalStateException("Configuración no válida para arrancar: " + String.join(" ", p));
        }
        aviso(cfg.esLocal(), analitica).ifPresent(a -> LOG.warn("[analitica] {}", a));
    }

    static List<String> problemas(boolean produccion, PropiedadesAnalitica analitica) {
        List<String> p = new ArrayList<>();
        if (!produccion) {
            return p;
        }
        if (!analitica.tieneClave()) {
            p.add("Falta DIGSA_ANALITICA_CLAVE_SEUDONIMO (clave del seudónimo de los pacientes de la analítica).");
        } else if (analitica.claveSeudonimo().strip().length() < LARGO_MINIMO || PropiedadesAnalitica.CLAVE_LOCAL.equals(analitica.claveSeudonimo())) {
            p.add("DIGSA_ANALITICA_CLAVE_SEUDONIMO debe tener al menos " + LARGO_MINIMO + " caracteres y no ser la de desarrollo.");
        }
        return p;
    }

    /** Aviso de arranque sin clave fuera de producción (en local, la de desarrollo; en otro entorno, cargues deshabilitados). */
    static Optional<String> aviso(boolean local, PropiedadesAnalitica analitica) {
        if (analitica.tieneClave()) {
            return Optional.empty();
        }
        return Optional.of(local
                ? "Sin DIGSA_ANALITICA_CLAVE_SEUDONIMO: en local se usa la clave fija de desarrollo (solo datos sintéticos)."
                : "Sin DIGSA_ANALITICA_CLAVE_SEUDONIMO: la aplicación arranca, pero todo cargue del insumo se rechaza hasta configurarla.");
    }
}
