package co.com.agsamericas.digsa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Suite DIGSA · Visitas de seguimiento farmacéutico. Monolito modular (Spring Modulith): cada subpaquete directo es un módulo
 * (plataforma, identidad, auditoria, ...). Las reglas de negocio puras viven en el módulo Maven digsa-reglas.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class DigsaApplication {

    public static void main(String[] args) {
        SpringApplication.run(DigsaApplication.class, args);
    }
}
