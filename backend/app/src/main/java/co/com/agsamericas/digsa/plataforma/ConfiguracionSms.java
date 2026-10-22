package co.com.agsamericas.digsa.plataforma;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elige el adaptador de SMS según digsa.sms.modo. El SMS a archivo fuera de local no arranca (los avisos parecerían enviados sin
 * salir); «ninguno» en producción tampoco (lo dice {@link ValidacionArranque}). El adaptador del proveedor se agrega aquí cuando se
 * contrate (spec exportacion-alertas, duda del proveedor de SMS).
 */
@Configuration
public class ConfiguracionSms {

    @Bean
    public Sms sms(ConfiguracionDigsa cfg, Almacen almacen, Clock reloj) {
        String modo = cfg.modoSms();
        return switch (modo) {
            case "archivo" -> {
                if (!cfg.esLocal()) {
                    throw new IllegalStateException("digsa.sms.modo=archivo solo se permite con digsa.entorno=local: en este entorno los SMS no saldrían.");
                }
                yield new SmsArchivo(almacen, reloj);
            }
            case "ninguno" -> new SmsNinguno();
            default -> throw new IllegalStateException("digsa.sms.modo=" + modo + " no tiene adaptador: los modos son archivo (local) y ninguno "
                    + "mientras no se contrate el proveedor de SMS.");
        };
    }
}
