package co.com.agsamericas.digsa.identidad;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.jdbc.PostgreSqlJdbcIndexedSessionRepositoryCustomizer;

/**
 * Sesiones en PostgreSQL (decisión del 2026-10-20: sin Redis). Con este ajuste Spring Session guarda los atributos con
 * INSERT … ON CONFLICT: dos peticiones simultáneas de la misma sesión no chocan por llave duplicada.
 */
@Configuration
public class ConfiguracionSesiones {

    @Bean
    PostgreSqlJdbcIndexedSessionRepositoryCustomizer sesionesPostgres() {
        return new PostgreSqlJdbcIndexedSessionRepositoryCustomizer();
    }
}
