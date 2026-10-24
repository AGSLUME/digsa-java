package co.com.agsamericas.digsa.motorreportes;

import co.com.agsamericas.digsa.alertas.ServicioAlertas;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reportes.DefinicionReporte;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Tiempos de los reportes (Anexo 5, 2.7: 3 minutos para ver y 10 para exportar). Cada generación guarda sus milisegundos en
 * {@code reportes_generados}. Pasar el plazo no corta el reporte (el usuario lo recibe completo): si una generación pasa el 80 % de
 * su plazo, lo supera o llega al tope técnico, se dispara la alerta operativa «reporte lento» al Administrador (una por reporte y
 * día): 2.7 obliga a informar a la DIGSA y corregir en 8 días, y esta es la evidencia.
 */
@Component
public class MedicionReportes {

    static final String ALERTA = "reporte_lento";
    /** Fracción del plazo desde la que se avisa. */
    static final double UMBRAL = 0.8;

    private final ServicioAlertas alertas;
    private final Clock reloj;

    public MedicionReportes(ServicioAlertas alertas, Clock reloj) {
        this.alertas = alertas;
        this.reloj = reloj;
    }

    /** ¿Hay que avisar? Al pasar el umbral del plazo o al cortarse. */
    static boolean lento(long ms, long plazoMs, boolean cortado) {
        return cortado || ms >= UMBRAL * plazoMs;
    }

    /**
     * Revisa una generación y avisa si fue lenta.
     *
     * @param d       el reporte: su id va en la clave de la alerta (una por reporte y día) y su título en el detalle
     * @param que     «en pantalla» o «al exportar en xlsx»…
     * @param resumen reporte, código y filtros resumidos (sin datos personales: códigos, no nombres)
     */
    public void revisar(DefinicionReporte d, String que, long ms, long plazoMs, boolean cortado, String resumen) {
        if (!lento(ms, plazoMs, cortado)) {
            return;
        }
        LocalDate hoy = LocalDate.now(reloj.withZone(Fechas.BOGOTA));
        String como = cortado ? " se cortó en el tope técnico de " + plazoMs / 1000 + " s"
                : " tardó " + ms / 1000 + " s" + (ms >= plazoMs ? " y superó" : ", más del 80 % de") + " los " + plazoMs / 1000 + " s de 2.7";
        String detalle = "Reporte «" + d.titulo() + "» " + que + como + " · " + resumen;
        // El detalle de una alerta admite 500 caracteres: los filtros largos se recortan (la lista completa está en reportes_generados)
        alertas.dispararAparte(ALERTA, ALERTA + ":" + d.id() + ":" + hoy, detalle.length() <= 480 ? detalle : detalle.substring(0, 479) + "…");
    }
}
