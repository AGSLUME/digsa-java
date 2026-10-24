package co.com.agsamericas.digsa.plataforma;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Carpeta propia de los temporales de los reportes (filas volcadas, cuerpo del PDF): tienen datos personales (Ley 1581), así que
 * no se mezclan con los del sistema y se vacía al arrancar, por si el proceso murió con alguno a medias.
 */
@Component
public class ReporteTemporales {

    private static final Logger LOG = LoggerFactory.getLogger(ReporteTemporales.class);
    private static final Path CARPETA = Path.of(System.getProperty("java.io.tmpdir"), "digsa-reportes");

    /** Un temporal nuevo en la carpeta de los reportes. */
    public static Path nuevo(String sufijo) throws IOException {
        Files.createDirectories(CARPETA);
        return Files.createTempFile(CARPETA, "reporte-", sufijo);
    }

    /** Borra un temporal; si no puede, lo deja en el log con su ruta para borrarlo a mano. Nunca lanza. */
    public static void borrar(Path archivo) {
        try {
            Files.deleteIfExists(archivo);
        } catch (IOException e) {
            LOG.error("[reportes] No se pudo borrar el temporal {} (datos personales: bórrelo a mano)", archivo, e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void vaciarAlArrancar() {
        if (!Files.isDirectory(CARPETA)) {
            return;
        }
        try (Stream<Path> l = Files.list(CARPETA)) {
            long n = l.filter(Files::isRegularFile).peek(ReporteTemporales::borrar).count();
            if (n > 0) {
                LOG.warn("[reportes] Se borraron {} temporales de reportes que quedaron de una ejecución anterior", n);
            }
        } catch (IOException | UncheckedIOException e) {
            LOG.error("[reportes] No se pudo vaciar la carpeta de temporales {}", CARPETA, e);
        }
    }
}
