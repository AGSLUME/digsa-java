package co.com.agsamericas.digsa.motorreportes;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parámetros del motor de reportes (prefijo {@code digsa.reportes}, el mismo de los dominios de correo de
 * {@code ConfiguracionDigsa}; cada uno lee sus campos).
 *
 * @param filasPantalla            filas que se muestran en pantalla; el archivo trae todas
 * @param maxFilasPdf              filas que admite un PDF: más es 422 con la sugerencia de Excel o CSV (un PDF de cientos de miles
 *                                 de filas no se puede leer y tarda más de los 10 minutos de 2.7)
 * @param adjuntoMaxMb             tope del adjunto de un envío por correo (duda R.4: 10 MB); más es 422 antes de leer el archivo
 * @param verificacionesPorMinuto  verificaciones por usuario y minuto (archivo, código o regenerar): más es 429
 */
@ConfigurationProperties(prefix = "digsa.reportes")
public record ConfiguracionReportes(Integer filasPantalla, Integer maxFilasPdf, Integer adjuntoMaxMb, Integer verificacionesPorMinuto) {

    public ConfiguracionReportes {
        filasPantalla = positivo(filasPantalla, 5000, "filas-pantalla");
        maxFilasPdf = positivo(maxFilasPdf, 5000, "max-filas-pdf");
        adjuntoMaxMb = positivo(adjuntoMaxMb, 10, "adjunto-max-mb");
        verificacionesPorMinuto = positivo(verificacionesPorMinuto, 30, "verificaciones-por-minuto");
    }

    /** Sin valor, el de omisión; un valor menor que 1 es un error de configuración y no arranca. */
    private static Integer positivo(Integer v, int defecto, String nombre) {
        if (v == null) {
            return defecto;
        }
        if (v < 1) {
            throw new IllegalArgumentException("digsa.reportes." + nombre + " debe ser mayor que 0 (vino " + v + ")");
        }
        return v;
    }

    public long adjuntoMaxBytes() {
        return adjuntoMaxMb * 1024L * 1024L;
    }
}
