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
        SpringApplication.run(DigsaApplication.class, argumentos(args));
    }

    /**
     * La herramienta de carga de maestros ({@code --digsa.carga.*}, maestros/carga/CargaMaestrosCli) corre sin abrir el puerto
     * (server.port=-1: el contexto web existe pero no escucha) y sin las tareas diarias (cron «-»), para poder ejecutarla con
     * {@code docker compose run} mientras la aplicación sigue en servicio.
     */
    public static String[] argumentos(String[] args) {
        if (java.util.Arrays.stream(args).noneMatch(a -> a.startsWith("--digsa.carga."))) {
            return args;
        }
        return java.util.stream.Stream.concat(java.util.Arrays.stream(args),
                java.util.stream.Stream.of("--server.port=-1", "--digsa.opl.cron=-", "--digsa.seguimiento.cron=-")).toArray(String[]::new);
    }
}
