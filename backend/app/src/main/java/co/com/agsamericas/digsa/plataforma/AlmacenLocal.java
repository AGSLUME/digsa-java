package co.com.agsamericas.digsa.plataforma;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Archivos en una carpeta del servidor, fuera de la carpeta web (digsa.archivos.carpeta). */
@Component
public class AlmacenLocal implements Almacen {

    private static final Logger LOG = LoggerFactory.getLogger(AlmacenLocal.class);
    /** Ruta relativa segura: sin «..», sin rutas absolutas y con extensión (spec archivos). */
    static final Pattern RUTA = Pattern.compile("^[A-Za-z0-9_/-]+\\.[a-z0-9]+$");

    private final Path raiz;

    public AlmacenLocal(ConfiguracionDigsa cfg) {
        this.raiz = Path.of(cfg.archivos() == null || cfg.archivos().carpeta() == null ? "almacen/archivos" : cfg.archivos().carpeta())
                .toAbsolutePath().normalize();
    }

    private Path resolver(String ruta) {
        if (ruta == null || !RUTA.matcher(ruta).matches() || ruta.contains("..") || ruta.startsWith("/")) {
            throw new IllegalArgumentException("Ruta de archivo no válida");
        }
        Path p = raiz.resolve(ruta).normalize();
        if (!p.startsWith(raiz)) {
            throw new IllegalArgumentException("Ruta de archivo fuera del almacén");
        }
        return p;
    }

    @Override
    public void guardar(String ruta, byte[] contenido) throws IOException {
        Path p = resolver(ruta);
        Files.createDirectories(p.getParent());
        Files.write(p, contenido, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    @Override
    public InputStream leer(String ruta) throws IOException {
        return Files.newInputStream(resolver(ruta));
    }

    @Override
    public void descartar(String ruta) {
        try {
            Files.deleteIfExists(resolver(ruta));
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn("[almacen] No se pudo descartar {}: {}", ruta, e.getMessage());
        }
    }
}
