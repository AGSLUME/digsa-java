package co.com.agsamericas.digsa.motorreportes;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Parámetros del motor de reportes (prefijo {@code digsa.reportes}, el mismo de los dominios de correo de
 * {@code ConfiguracionDigsa}; cada uno lee sus campos).
 *
 * @param filasPantalla            filas que se muestran en pantalla; el archivo trae todas
 * @param adjuntoMaxMb             tope del adjunto de un envío por correo (duda R.4: 10 MB); más es 422 antes de leer el archivo
 * @param verificacionesPorMinuto  verificaciones por usuario y minuto (archivo, código o regenerar): más es 429
 * @param plazoVerMs               plazo para ver un reporte en pantalla (Anexo 5, 2.7: 3 minutos): pasarlo dispara la alerta, no corta
 * @param plazoExportarMs          plazo para exportar un reporte (Anexo 5, 2.7: 10 minutos): pasarlo dispara la alerta, no corta
 * @param topeTecnicoMs            tope técnico de una generación de archivo colgada (60 minutos): solo ahí se corta, con un mensaje claro
 * @param topeTecnicoVerMs         tope técnico de un reporte en pantalla (10 minutos): la petición no queda colgada una hora
 * @param esperaSincronaMs         lo que la descarga espera antes de seguir en segundo plano y responder 202
 * @param concurrencia             exportaciones a la vez en la instancia; las demás esperan en la cola
 * @param enCursoPorUsuario        exportaciones en cola o generándose por usuario; más es 429
 */
@ConfigurationProperties(prefix = "digsa.reportes")
public record ConfiguracionReportes(Integer filasPantalla, Integer adjuntoMaxMb, Integer verificacionesPorMinuto, Integer plazoVerMs,
                                    Integer plazoExportarMs, Integer esperaSincronaMs, Integer concurrencia, Integer enCursoPorUsuario, Integer topeTecnicoMs,
                                    Integer topeTecnicoVerMs) {

    @ConstructorBinding
    public ConfiguracionReportes {
        filasPantalla = positivo(filasPantalla, 5000, "filas-pantalla");
        adjuntoMaxMb = positivo(adjuntoMaxMb, 10, "adjunto-max-mb");
        verificacionesPorMinuto = positivo(verificacionesPorMinuto, 30, "verificaciones-por-minuto");
        plazoVerMs = positivo(plazoVerMs, 180_000, "plazo-ver-ms");
        plazoExportarMs = positivo(plazoExportarMs, 600_000, "plazo-exportar-ms");
        esperaSincronaMs = esperaSincronaMs == null ? 15_000 : Math.max(0, esperaSincronaMs);
        concurrencia = positivo(concurrencia, 4, "concurrencia");
        enCursoPorUsuario = positivo(enCursoPorUsuario, 2, "en-curso-por-usuario");
        topeTecnicoMs = positivo(topeTecnicoMs, 3_600_000, "tope-tecnico-ms");
        topeTecnicoVerMs = positivo(topeTecnicoVerMs, 600_000, "tope-tecnico-ver-ms");
    }

    /** Solo con las filas en pantalla (pruebas); lo demás con sus valores de omisión. */
    public ConfiguracionReportes(Integer filasPantalla) {
        this(filasPantalla, null, null, null, null, null, null, null, null, null);
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
