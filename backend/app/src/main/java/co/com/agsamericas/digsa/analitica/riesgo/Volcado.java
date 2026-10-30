package co.com.agsamericas.digsa.analitica.riesgo;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Líneas COPY de la membresía de una versión de cohorte armadas en el recorrido (A7.1, AN.32): se acumulan en memoria por lotes de
 * {@value #LOTE} líneas y cada lote lleno se vuelca a un archivo temporal, así la memoria del recálculo no crece con la membresía (con GCF y
 * GRC, ~2 filas por paciente y mes). La escritura (otra transacción) lee el archivo y el resto del búfer con un solo COPY. Las líneas llevan
 * el número del paciente (ana_pacientes), nunca el seudónimo; el archivo se borra al cerrar.
 */
final class Volcado implements AutoCloseable {

    /** Líneas por lote en memoria antes de volcar al archivo temporal. */
    static final int LOTE = 50_000;

    private final StringBuilder bufer = new StringBuilder();
    private final int lote;
    private int lineas;
    private long total;
    private Path archivo;
    private Writer escritor;

    Volcado() {
        this(LOTE);
    }

    Volcado(int lote) {
        this.lote = lote;
    }

    /** Búfer donde se escribe la línea siguiente (terminada en salto de línea); después se llama {@link #fin()}. */
    StringBuilder linea() {
        return bufer;
    }

    /** Cierra la línea escrita: con el lote lleno, lo vuelca al archivo temporal. */
    void fin() {
        lineas++;
        total++;
        if (lineas >= lote) {
            volcar();
        }
    }

    long lineas() {
        return total;
    }

    /** ¿Se volcó algún lote al archivo? (pruebas) */
    boolean enArchivo() {
        return archivo != null;
    }

    private void volcar() {
        try {
            if (archivo == null) {
                archivo = Files.createTempFile("digsa-miembros-", ".tsv");
                // Red de seguridad si la lectura falla antes de cerrar (el recálculo lo cierra siempre al terminar la escritura)
                archivo.toFile().deleteOnExit();
                escritor = Files.newBufferedWriter(archivo, StandardCharsets.UTF_8);
            }
            escritor.append(bufer);
            bufer.setLength(0);
            lineas = 0;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo volcar la membresía a un archivo temporal", e);
        }
    }

    /** Lector de todas las líneas (archivo y búfer), para el COPY. Después de leer solo queda {@link #close()}. */
    Reader lector() {
        if (archivo == null) {
            return new LectorTexto(bufer);
        }
        try {
            if (!bufer.isEmpty()) {
                volcar();
            }
            escritor.close();
            return Files.newBufferedReader(archivo, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer la membresía volcada", e);
        }
    }

    @Override
    public void close() {
        bufer.setLength(0);
        try {
            if (escritor != null) {
                escritor.close();
            }
            if (archivo != null) {
                Files.deleteIfExists(archivo);
            }
        } catch (IOException e) {
            archivo.toFile().deleteOnExit();
        } finally {
            escritor = null;
            archivo = null;
        }
    }

    /** Lector sobre un CharSequence (no duplica el búfer como haría toString()). */
    static final class LectorTexto extends Reader {
        private final CharSequence s;
        private int pos;

        LectorTexto(CharSequence s) {
            this.s = s;
        }

        @Override
        public int read(char[] b, int off, int len) {
            if (pos >= s.length()) {
                return -1;
            }
            int n = Math.min(len, s.length() - pos);
            for (int i = 0; i < n; i++) {
                b[off + i] = s.charAt(pos + i);
            }
            pos += n;
            return n;
        }

        @Override
        public void close() {
        }
    }
}
