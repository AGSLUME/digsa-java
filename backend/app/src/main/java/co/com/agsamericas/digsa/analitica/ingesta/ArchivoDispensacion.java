package co.com.agsamericas.digsa.analitica.ingesta;

import co.com.agsamericas.digsa.reglas.AnaliticaIngesta;
import co.com.agsamericas.digsa.reglas.AnaliticaIngesta.Causa;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.function.LongConsumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Adaptador de archivo del puerto {@link FuenteDispensacion}: CSV y TXT con «;» o «,» detectado en el encabezado, UTF-8 con o sin BOM
 * (coma decimal con «;»), los mismos en .gz o .zip (un solo archivo de datos dentro) y xlsx con SAX y varias hojas. Lee en flujo; el
 * .zip y el xlsx se copian a un temporal que se borra al cerrar. Lo descomprimido no pasa del tamaño máximo del archivo (bomba de zip).
 */
final class ArchivoDispensacion implements FuenteDispensacion {

    /** Abre el archivo original (del almacén) desde el principio. */
    @FunctionalInterface
    interface Abridor {
        InputStream abrir() throws IOException;
    }

    /**
     * Topes de lectura que protegen la memoria: celda y registro del CSV (en caracteres) y tabla de textos compartidos de un xlsx sin
     * comprimir (en bytes; POI la lee completa en memoria).
     */
    record Limites(int celda, int registro, long textosXlsx) {
        static final Limites DEFECTO = new Limites(LectorCsv.CELDA_MAX, LectorCsv.REGISTRO_MAX, 256L * 1024 * 1024);
    }

    private final Abridor abridor;
    private final String formatoDatos;
    private final String formatoArchivo;
    private final long maxBytes;
    private final Limites limites;
    private final LongConsumer avance;
    private final List<Path> temporales = new ArrayList<>();
    private Path xlsx;
    private Path zip;
    private char delimitador = ';';
    private List<String> encabezado;

    private ArchivoDispensacion(Abridor abridor, String formatoArchivo, String formatoDatos, long maxBytes, Limites limites,
                                LongConsumer avance) {
        this.abridor = abridor;
        this.formatoArchivo = formatoArchivo;
        this.formatoDatos = formatoDatos;
        this.maxBytes = maxBytes;
        this.limites = limites;
        this.avance = avance == null ? b -> { } : avance;
    }

    /** {@link #abrir(Abridor, String, long, Limites, LongConsumer)} con los topes por defecto. */
    static ArchivoDispensacion abrir(Abridor abridor, String nombre, long maxBytes, LongConsumer avance) throws IOException {
        return abrir(abridor, nombre, maxBytes, Limites.DEFECTO, avance);
    }

    /**
     * Abre el archivo y lee su encabezado.
     *
     * @param nombre   nombre original (su extensión fija el formato)
     * @param maxBytes tope de lo descomprimido
     * @param limites  topes de celda, registro y textos del xlsx
     * @param avance   recibe los bytes del archivo original leídos (para el porcentaje); puede ser null
     * @throws ErrorEstructura si está vacío, no es UTF-8, trae varios archivos en el .zip o no es un formato aceptado
     */
    static ArchivoDispensacion abrir(Abridor abridor, String nombre, long maxBytes, Limites limites, LongConsumer avance) throws IOException {
        String formato = AnaliticaIngesta.formatoDe(nombre)
                .orElseThrow(() -> new ErrorEstructura(Causa.FORMATO, "Formato no aceptado: use CSV, TXT, xlsx, .gz o .zip", "1", 1));
        String datos = formato;
        if ("gz".equals(formato)) {
            String interno = nombre.substring(0, nombre.length() - 3);
            datos = AnaliticaIngesta.formatoDe(interno).filter(f -> !"gz".equals(f) && !"zip".equals(f)).orElse("csv");
        }
        ArchivoDispensacion a = new ArchivoDispensacion(abridor, formato, datos, maxBytes, limites, avance);
        try {
            a.preparar();
            return a;
        } catch (IOException | RuntimeException e) {
            a.close();
            throw e;
        }
    }

    private void preparar() throws IOException {
        if ("zip".equals(formatoArchivo)) {
            zip = temporal(".zip");
            try (InputStream in = new Limitado(abridor.abrir(), maxBytes)) {
                Files.copy(in, zip, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            ZipEntry e = entradaUnica();
            String n = e.getName().toLowerCase(Locale.ROOT);
            formatoDatosZip = n.endsWith(".xlsx") ? "xlsx" : "csv";
        }
        if ("xlsx".equals(datos())) {
            xlsx = temporal(".xlsx");
            try (InputStream in = new Limitado(datosCrudos(), maxBytes)) {
                Files.copy(in, xlsx, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            LectorXlsx.revisarTextos(xlsx, limites.textosXlsx());
            encabezado = LectorXlsx.encabezado(xlsx);
        } else {
            try (BufferedReader r = lector(datosCrudos())) {
                encabezadoCsv(r);
            }
        }
        if (encabezado.isEmpty() || encabezado.stream().allMatch(String::isBlank)) {
            throw new ErrorEstructura(Causa.ARCHIVO_VACIO, Causa.ARCHIVO_VACIO.texto(), "1", 1);
        }
    }

    private String formatoDatosZip;

    private String datos() {
        return formatoDatosZip != null ? formatoDatosZip : formatoDatos;
    }

    /** Formato del archivo tal como se subió (csv, txt, xlsx, gz o zip). */
    String formato() {
        return formatoArchivo;
    }

    char delimitador() {
        return delimitador;
    }

    @Override
    public List<String> encabezado() {
        return encabezado;
    }

    @Override
    public String fuenteTexto() {
        String d = datos();
        return AnaliticaIngesta.fuenteTexto("txt".equals(d) || "txt".equals(formatoDatos) ? "txt" : d, delimitador);
    }

    @Override
    public boolean comaDecimal() {
        return !"xlsx".equals(datos()) && delimitador == ';';
    }

    private ZipEntry entradaUnica() throws IOException {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            ZipEntry unica = null;
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                String base = n.substring(n.lastIndexOf('/') + 1);
                if (e.isDirectory() || n.startsWith("__MACOSX/") || base.startsWith(".") || base.isEmpty()) {
                    continue;
                }
                if (unica != null) {
                    throw new ErrorEstructura(Causa.FORMATO, "El .zip debe traer un solo archivo de datos", "1", 1);
                }
                unica = e;
            }
            if (unica == null) {
                throw new ErrorEstructura(Causa.ARCHIVO_VACIO, "El .zip no trae ningún archivo de datos", "1", 1);
            }
            return unica;
        }
    }

    /** Flujo de los datos (descomprimidos) desde el principio. */
    private InputStream datosCrudos() throws IOException {
        if (zip != null) {
            ZipFile z = new ZipFile(zip.toFile());
            ZipEntry e = entradaUnica();
            InputStream in = z.getInputStream(e);
            return new Limitado(new FilterInputStream(in) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        z.close();
                    }
                }
            }, maxBytes);
        }
        InputStream crudo = new Contador(abridor.abrir(), avance);
        if ("gz".equals(formatoArchivo)) {
            return new Limitado(new GZIPInputStream(new BufferedInputStream(crudo, 1 << 16), 1 << 16), maxBytes);
        }
        return crudo;
    }

    private static BufferedReader lector(InputStream in) {
        var dec = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        return new BufferedReader(new InputStreamReader(new BufferedInputStream(in, 1 << 16), dec), 1 << 16);
    }

    /**
     * Primera línea con datos, leída con tope (sin readLine: un archivo sin saltos de línea la volvería del tamaño del archivo) para
     * detectar el delimitador; luego el lector vuelve al principio.
     */
    private void encabezadoCsv(BufferedReader r) throws IOException {
        String primera;
        try {
            r.mark(limites.registro() + 2);
            primera = primeraLinea(r, limites.registro());
            r.reset();
        } catch (CharacterCodingException e) {
            throw new ErrorEstructura(Causa.CODIFICACION, Causa.CODIFICACION.texto() + " (línea 1)", "1", 1);
        }
        if (primera == null) {
            encabezado = List.of();
            return;
        }
        delimitador = AnaliticaIngesta.detectarDelimitador(AnaliticaIngesta.sinBom(primera));
        LectorCsv csv = new LectorCsv(r, delimitador, limites.celda(), limites.registro());
        List<String> h = csv.siguiente();
        while (h != null && h.isEmpty()) {
            h = csv.siguiente();
        }
        encabezado = h == null ? List.of() : quitarBom(h);
    }

    /** Primera línea que no está en blanco (sin el salto), o null si no hay; rechazo de estructura si pasa del tope antes de terminar. */
    private static String primeraLinea(BufferedReader r, int tope) throws IOException {
        StringBuilder sb = new StringBuilder();
        int leidos = 0;
        for (int c = r.read(); c >= 0; c = r.read()) {
            if (++leidos > tope) {
                throw new ErrorEstructura(Causa.FORMATO, "La primera línea del archivo pasa de " + tope / 1024 + " KB (¿no es un CSV o le faltan los "
                        + "saltos de línea?)", "1", 1);
            }
            if (c == '\n' || c == '\r') {
                if (!sb.toString().isBlank()) {
                    return sb.toString();
                }
                sb.setLength(0);
            } else {
                sb.append((char) c);
            }
        }
        return sb.toString().isBlank() ? null : sb.toString();
    }

    private static List<String> quitarBom(List<String> h) {
        List<String> l = new ArrayList<>(h);
        if (!l.isEmpty()) {
            l.set(0, AnaliticaIngesta.sinBom(l.getFirst()));
        }
        return List.copyOf(l);
    }

    @Override
    public void recorrer(Consumidor consumidor) throws IOException {
        if (xlsx != null) {
            LectorXlsx.recorrer(xlsx, encabezado, consumidor, false);
            return;
        }
        long linea = 0;
        try (BufferedReader r = lector(datosCrudos())) {
            LectorCsv csv = new LectorCsv(r, delimitador, limites.celda(), limites.registro());
            boolean conEncabezado = false;
            for (List<String> celdas = csv.siguiente(); celdas != null; celdas = csv.siguiente()) {
                linea = csv.linea();
                if (celdas.isEmpty() || celdas.size() == 1 && celdas.getFirst().isBlank()) {
                    continue; // línea en blanco
                }
                if (!conEncabezado) {
                    conEncabezado = true;
                    continue;
                }
                consumidor.registro(linea, Long.toString(linea), celdas);
            }
        } catch (CharacterCodingException e) {
            String l = Long.toString(linea + 1);
            throw new ErrorEstructura(Causa.CODIFICACION, Causa.CODIFICACION.texto() + " (cerca de la línea " + l + ")", l, 1);
        }
    }

    private Path temporal(String extension) throws IOException {
        Path p = Files.createTempFile("digsa-analitica-", extension);
        temporales.add(p);
        return p;
    }

    /** Borra los temporales (copias del .zip y del xlsx, con datos personales en claro); intenta todos aunque uno falle. */
    @Override
    public void close() throws IOException {
        IOException primero = null;
        for (Path p : temporales) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                p.toFile().deleteOnExit();
                primero = primero == null ? e : primero;
            }
        }
        temporales.clear();
        if (primero != null) {
            throw primero;
        }
    }

    /** Corta la lectura si lo descomprimido pasa del máximo (bomba de zip). */
    private static final class Limitado extends FilterInputStream {
        private final long max;
        private long leidos;

        Limitado(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        private void sumar(long n) throws IOException {
            if (n > 0 && (leidos += n) > max) {
                throw new ErrorEstructura(Causa.FORMATO, "El contenido descomprimido supera el tamaño máximo permitido", "1", 1);
            }
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                sumar(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            sumar(n);
            return n;
        }
    }

    /** Cuenta los bytes leídos del archivo original (avance de la lectura). */
    private static final class Contador extends FilterInputStream {
        private final LongConsumer avance;
        private long leidos;
        private long avisado;

        Contador(InputStream in, LongConsumer avance) {
            super(in);
            this.avance = avance;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                sumar(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                sumar(n);
            }
            return n;
        }

        private void sumar(long n) {
            leidos += n;
            if (leidos - avisado >= (1 << 22)) {
                avisado = leidos;
                avance.accept(leidos);
            }
        }
    }
}
