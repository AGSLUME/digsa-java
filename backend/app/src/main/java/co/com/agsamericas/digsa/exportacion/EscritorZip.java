package co.com.agsamericas.digsa.exportacion;

import co.com.agsamericas.digsa.plataforma.Almacen;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * ZIP escrito en flujo: cada entrada pasa por un búfer fijo (64 KB) y nunca se tiene entera en memoria. Calcula el SHA-256 de cada
 * entrada mientras la escribe (huella de los CSV en el LEEME y de los soportes en el índice). Las fotos, videos y PDF van sin
 * comprimir (ya lo están: comprimirlos solo gasta tiempo); el texto, con compresión rápida.
 */
public final class EscritorZip implements Closeable {

    private static final int BUFER = 1 << 16;
    /** Cada cuántos bytes copiados se revisa el límite de tiempo. */
    private static final long REVISION_BYTES = 8L << 20;

    private final ZipOutputStream zip;
    private final Runnable revisarPlazo;

    /**
     * @param destino      el archivo del almacén o la respuesta HTTP; se cierra al cerrar el escritor
     * @param revisarPlazo lanza si se pasó el límite de tiempo (se llama cada pocos MB copiados)
     */
    public EscritorZip(OutputStream destino, Runnable revisarPlazo) {
        this.zip = new ZipOutputStream(destino, StandardCharsets.UTF_8);
        this.revisarPlazo = revisarPlazo == null ? () -> { } : revisarPlazo;
    }

    /** Entrada de carpeta (p. ej. «soportes/»): el ZIP la muestra aunque esté vacía. */
    public void carpeta(String nombre) throws IOException {
        zip.putNextEntry(new ZipEntry(nombre.endsWith("/") ? nombre : nombre + "/"));
        zip.closeEntry();
    }

    /** Entrada de texto en UTF-8. @return su SHA-256 */
    public String texto(String nombre, String contenido) throws IOException {
        try (Entrada e = abrir(nombre, true)) {
            e.escritor().write(contenido);
            return e.terminar();
        }
    }

    /** Abre una entrada para escribirla como texto (los CSV fila por fila). Se cierra con {@link Entrada#terminar()}. */
    public Entrada abrir(String nombre, boolean comprimir) throws IOException {
        zip.setLevel(comprimir ? Deflater.BEST_SPEED : Deflater.NO_COMPRESSION);
        zip.putNextEntry(new ZipEntry(nombre));
        DigestOutputStream huella = new DigestOutputStream(new SinCerrar(zip), sha256());
        return new Entrada(huella, new BufferedWriter(new OutputStreamWriter(huella, StandardCharsets.UTF_8), BUFER));
    }

    /**
     * Copia un archivo del almacén a una entrada del ZIP calculando su SHA-256.
     *
     * @return la huella calculada, o null si el almacén no tiene el archivo (no se crea la entrada)
     */
    public String copiarDelAlmacen(Almacen almacen, String ruta, String nombre, String tipo) throws IOException {
        InputStream origen;
        try {
            origen = almacen.leer(ruta);
        } catch (NoSuchFileException | FileNotFoundException | IllegalArgumentException e) {
            return null;
        }
        try (InputStream in = origen) {
            return copiar(in, nombre, comprimible(tipo));
        }
    }

    /** Copia un flujo a una entrada. @return su SHA-256 */
    public String copiar(InputStream origen, String nombre, boolean comprimir) throws IOException {
        zip.setLevel(comprimir ? Deflater.BEST_SPEED : Deflater.NO_COMPRESSION);
        zip.putNextEntry(new ZipEntry(nombre));
        MessageDigest md = sha256();
        byte[] b = new byte[BUFER];
        long desdeRevision = 0;
        int n;
        while ((n = origen.read(b)) > 0) {
            md.update(b, 0, n);
            zip.write(b, 0, n);
            desdeRevision += n;
            if (desdeRevision >= REVISION_BYTES) {
                revisarPlazo.run();
                desdeRevision = 0;
            }
        }
        zip.closeEntry();
        return HexFormat.of().formatHex(md.digest());
    }

    /** Copia bytes que ya están en memoria (la imagen de una firma, ≤ 300 KB). @return su SHA-256 */
    public String copiar(byte[] contenido, String nombre, boolean comprimir) throws IOException {
        return copiar(new java.io.ByteArrayInputStream(contenido), nombre, comprimir);
    }

    /** ¿Vale la pena comprimir este tipo? Las imágenes, videos, audios, PDF y ZIP ya vienen comprimidos. */
    static boolean comprimible(String tipo) {
        String t = tipo == null ? "" : tipo.toLowerCase(java.util.Locale.ROOT);
        return !(t.startsWith("image/") || t.startsWith("video/") || t.startsWith("audio/") || t.equals("application/pdf") || t.contains("zip"));
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("La JVM no tiene SHA-256", e);
        }
    }

    /** Entrada de texto abierta. */
    public final class Entrada implements Closeable {
        private final DigestOutputStream huella;
        private final Writer escritor;
        private String sha;

        private Entrada(DigestOutputStream huella, Writer escritor) {
            this.huella = huella;
            this.escritor = escritor;
        }

        public Writer escritor() {
            return escritor;
        }

        /** Vacía el búfer y cierra la entrada. @return el SHA-256 de lo escrito */
        public String terminar() throws IOException {
            if (sha == null) {
                escritor.flush();
                zip.closeEntry();
                sha = HexFormat.of().formatHex(huella.getMessageDigest().digest());
            }
            return sha;
        }

        @Override
        public void close() throws IOException {
            terminar();
        }
    }

    /** El escritor de una entrada no cierra el ZIP. */
    private static final class SinCerrar extends FilterOutputStream {
        SinCerrar(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
