package co.com.agsamericas.digsa.analitica.ingesta;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Lectura de archivos tabulares con el mismo lector de la ingesta (CSV o TXT con «;» o «,», UTF-8 con o sin BOM, .gz, .zip con un solo
 * archivo y xlsx por SAX), para otros usos del módulo como las referencias oficiales de la homologación. No seudonimiza ni mapea: entrega
 * el encabezado y las celdas de cada fila tal como vienen.
 */
public final class LectorTabular {

    /** Abre el archivo desde el principio (puede llamarse varias veces). */
    @FunctionalInterface
    public interface Abridor {
        InputStream abrir() throws IOException;
    }

    /** Recibe el encabezado una vez y luego cada fila. */
    public interface Receptor {
        void encabezado(List<String> columnas);

        void fila(long orden, String linea, List<String> celdas) throws IOException;
    }

    /** Archivo que no se pudo leer (formato, codificación, vacío o tope del lector), con un mensaje para el usuario. */
    public static final class ArchivoIlegible extends RuntimeException {
        ArchivoIlegible(String mensaje) {
            super(mensaje);
        }
    }

    private LectorTabular() {
    }

    /**
     * Lee todo el archivo.
     *
     * @param nombre   nombre original (su extensión fija el formato)
     * @param maxBytes tope del contenido (también descomprimido)
     * @throws ArchivoIlegible si el archivo no se puede leer
     */
    public static void leer(Abridor abridor, String nombre, long maxBytes, Receptor receptor) throws IOException {
        try (ArchivoDispensacion a = ArchivoDispensacion.abrir(abridor::abrir, nombre, maxBytes, null)) {
            receptor.encabezado(a.encabezado());
            a.recorrer(receptor::fila);
        } catch (ErrorEstructura e) {
            throw new ArchivoIlegible(e.getMessage());
        }
    }
}
