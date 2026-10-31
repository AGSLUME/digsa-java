package co.com.agsamericas.digsa.analitica.tableros;

import co.com.agsamericas.digsa.reglas.AnaliticaDescargaDiferida;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de la descarga diferida del CSV de registros de origen (prefijo {@code digsa.analitica.descargas-diferidas}; A7.3, duda
 * AN.16). Las descargas de los tableros no se difieren (son agregadas). Valores por defecto en application.yml con
 * {@code ${DIGSA_*:defecto}}; fuera de rango la aplicación no arranca.
 *
 * @param umbralFilas        registros de salida desde los que el CSV de registros de origen va a diferido (100 000). En la petición sale
 *                           hasta el menor entre este umbral y {@code tableros.max-registros-traza}
 * @param maxRegistrosTraza  tope de registros del CSV de registros de origen en diferido (2 000 000; más: 422, nunca un archivo truncado)
 * @param limiteMinutos      presupuesto de la generación de UNA descarga diferida (A5 2.7: 10 minutos para exportar)
 * @param vigenciaHoras      horas que el archivo queda en «Mis descargas del día»; luego se borra (24)
 * @param edadMaximaMinutos  una descarga pendiente o generándose desde hace más que esto queda «fallida» (la cola no bloquea al usuario
 *                           para siempre; 60)
 * @param revisionMs         cada cuánto se borran las vencidas y se retoman las pendientes (60000)
 */
@ConfigurationProperties(prefix = "digsa.analitica.descargas-diferidas")
public record PropiedadesDescargasDiferidas(Integer umbralFilas, Integer maxRegistrosTraza, Integer limiteMinutos, Integer vigenciaHoras,
                                            Integer edadMaximaMinutos, Long revisionMs) {

    public PropiedadesDescargasDiferidas {
        rango("umbral-filas", umbralFilas, 0, 50_000_000);
        rango("max-registros-traza", maxRegistrosTraza, 1, 50_000_000);
        rango("limite-minutos", limiteMinutos, 1, 120);
        rango("vigencia-horas", vigenciaHoras, 1, 24 * 30);
        rango("edad-maxima-minutos", edadMaximaMinutos, 2, 24 * 60);
        if ((edadMaximaMinutos == null ? 60 : edadMaximaMinutos) <= (limiteMinutos == null ? 10 : limiteMinutos)) {
            throw new IllegalArgumentException("digsa.analitica.descargas-diferidas.edad-maxima-minutos debe ser mayor que limite-minutos");
        }
    }

    private static void rango(String nombre, Integer v, int min, int max) {
        if (v != null && (v < min || v > max)) {
            throw new IllegalArgumentException("digsa.analitica.descargas-diferidas." + nombre + " = " + v + " está fuera de rango (" + min + " a " + max + ")");
        }
    }

    public int umbral() {
        return umbralFilas == null ? AnaliticaDescargaDiferida.UMBRAL_FILAS : umbralFilas;
    }

    public int maxTraza() {
        return maxRegistrosTraza == null ? AnaliticaDescargaDiferida.MAX_REGISTROS_TRAZA : maxRegistrosTraza;
    }

    public int limiteSegundos() {
        return (limiteMinutos == null ? 10 : limiteMinutos) * 60;
    }

    public Duration vigencia() {
        return Duration.ofHours(vigenciaHoras == null ? 24 : vigenciaHoras);
    }

    public Duration edadMaxima() {
        return Duration.ofMinutes(edadMaximaMinutos == null ? 60 : edadMaximaMinutos);
    }
}
