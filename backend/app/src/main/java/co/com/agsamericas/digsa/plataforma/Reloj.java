package co.com.agsamericas.digsa.plataforma;

import co.com.agsamericas.digsa.reglas.Fechas;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reloj único de la aplicación en America/Bogota. Las pruebas lo reemplazan por uno fijo. */
@Configuration
public class Reloj {

    @Bean
    public Clock clock() {
        return Clock.system(Fechas.BOGOTA);
    }
}
