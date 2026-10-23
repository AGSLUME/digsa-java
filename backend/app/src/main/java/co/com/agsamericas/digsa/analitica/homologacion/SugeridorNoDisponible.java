package co.com.agsamericas.digsa.analitica.homologacion;

import co.com.agsamericas.digsa.analitica.SugeridorHomologacion;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Adaptador «no disponible» del {@link SugeridorHomologacion} (por defecto, sin proveedor de IA): la revisión funciona igual, sin caja de
 * sugerencia y sin error en pantalla («Sin sugerencia automática»). A6 registra los adaptadores con IA y este deja de usarse.
 */
final class SugeridorNoDisponible implements SugeridorHomologacion {

    @Override
    public boolean disponible() {
        return false;
    }

    @Override
    public String proveedor() {
        return "ninguno";
    }

    @Override
    public Optional<Sugerencia> sugerir(String textoOriginal, List<Candidato> candidatos) {
        return Optional.empty();
    }

    /** Registra el adaptador solo si ningún otro módulo trae uno. */
    @Configuration(proxyBeanMethods = false)
    static class Registro {
        @Bean
        @ConditionalOnMissingBean(SugeridorHomologacion.class)
        SugeridorHomologacion sugeridorHomologacion() {
            return new SugeridorNoDisponible();
        }
    }
}
