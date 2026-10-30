package co.com.agsamericas.digsa.analitica.exportacion;

import co.com.agsamericas.digsa.reglas.AnaliticaExportacionHechos;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de la exportación de los hechos por periodo (prefijo {@code digsa.analitica.exportacion-hechos}; clase propia del
 * módulo). Valores por defecto en application.yml con {@code ${DIGSA_*:defecto}}; fuera de rango la aplicación no arranca.
 *
 * @param vigenciaHoras    horas que los archivos quedan para descargar; luego se borran (24, como la exportación completa)
 * @param limiteMinutosMes tope de la generación de CADA mes (su propia transacción; AGS-75: 10 minutos). No hay tope del periodo entero
 * @param mesesMax         meses por solicitud (60)
 * @param lote             filas por lote del cursor (5000)
 * @param revisionMs       cada cuánto se borran las vencidas y se retoman las pendientes (60000)
 */
@ConfigurationProperties(prefix = "digsa.analitica.exportacion-hechos")
public record PropiedadesExportacionHechos(Integer vigenciaHoras, Integer limiteMinutosMes, Integer mesesMax, Integer lote, Long revisionMs) {

    public PropiedadesExportacionHechos {
        rango("vigencia-horas", vigenciaHoras, 1, 24 * 30);
        rango("limite-minutos-mes", limiteMinutosMes, 1, 120);
        rango("meses-max", mesesMax, 1, 600);
        rango("lote", lote, 100, 100_000);
    }

    private static void rango(String nombre, Integer v, int min, int max) {
        if (v != null && (v < min || v > max)) {
            throw new IllegalArgumentException("digsa.analitica.exportacion-hechos." + nombre + " = " + v + " está fuera de rango (" + min + " a " + max + ")");
        }
    }

    public Duration vigencia() {
        return Duration.ofHours(vigenciaHoras == null ? 24 : vigenciaHoras);
    }

    public Duration limiteMes() {
        return Duration.ofMinutes(limiteMinutosMes == null ? 10 : limiteMinutosMes);
    }

    public int maximoMeses() {
        return mesesMax == null ? AnaliticaExportacionHechos.MESES_MAX : mesesMax;
    }

    public int filasPorLote() {
        return lote == null ? 5000 : lote;
    }
}
