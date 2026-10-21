package co.com.agsamericas.digsa.plataforma;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.tika.Tika;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Valida un archivo por su contenido (nunca por la extensión ni por lo que diga el navegador), aplica el tope de tamaño y corrige la
 * orientación EXIF de las fotos (decisión 10.7). Referencia: docs/especificaciones/archivos.
 */
@Component
public class ValidadorArchivos {

    public static final long MAX_ARCHIVO = 25L * 1024 * 1024;
    public static final long MAX_VIDEO = 100L * 1024 * 1024;
    public static final int MAX_POR_PETICION = 20;

    /** Tipo real admitido → extensión con que se guarda. */
    static final Map<String, String> TIPOS = Map.ofEntries(
            Map.entry("image/jpeg", "jpg"), Map.entry("image/png", "png"), Map.entry("image/webp", "webp"), Map.entry("image/gif", "gif"),
            Map.entry("image/heic", "heic"), Map.entry("image/heif", "heif"), Map.entry("application/pdf", "pdf"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
            Map.entry("application/msword", "doc"), Map.entry("application/vnd.ms-excel", "xls"),
            Map.entry("application/x-tika-msoffice", "doc"), Map.entry("application/x-tika-ooxml", "docx"),
            Map.entry("video/mp4", "mp4"), Map.entry("video/quicktime", "mov"), Map.entry("video/webm", "webm"), Map.entry("video/3gpp", "3gp"),
            Map.entry("text/plain", "txt"), Map.entry("text/markdown", "md"));

    /** Archivo validado, listo para guardar. */
    public record Valido(byte[] contenido, String tipo, String extension, String sha256) {
    }

    private final Tika tika = new Tika();

    public Valido validar(String nombre, byte[] contenido) {
        if (contenido == null || contenido.length == 0) {
            throw ErrorNegocio.validacion("El archivo «" + nombre + "» está vacío.");
        }
        String tipo = tipo(contenido, nombre);
        String extension = TIPOS.get(tipo);
        if (extension == null) {
            throw ErrorNegocio.validacion("El archivo «" + nombre + "» no es de un tipo admitido (fotos, PDF, Word, Excel, video o texto).");
        }
        long tope = tipo.startsWith("video/") ? MAX_VIDEO : MAX_ARCHIVO;
        if (contenido.length > tope) {
            throw new ErrorNegocio(HttpStatus.PAYLOAD_TOO_LARGE, "El archivo «" + nombre + "» pesa más de " + tope / (1024 * 1024) + " MB.");
        }
        byte[] guardar = "image/jpeg".equals(tipo) ? enderezar(contenido) : contenido;
        return new Valido(guardar, tipo, extension, co.com.agsamericas.digsa.reglas.Huellas.sha256(guardar));
    }

    /**
     * Tipo real por el contenido. El nombre solo afina dentro de la familia que dicen los bytes (un ZIP que es .docx o .xlsx, un OLE
     * que es .doc o .xls, un texto que es .md); nunca convierte en PDF o foto algo cuyos bytes no lo son.
     */
    String tipo(byte[] contenido, String nombre) {
        String porBytes = tika.detect(contenido);
        String n = nombre == null ? "" : nombre;
        return switch (porBytes) {
            case "application/zip", "application/x-tika-ooxml" -> {
                String t = tika.detect(contenido, n);
                yield t.startsWith("application/vnd.openxmlformats-officedocument.") ? t : porBytes;
            }
            case "application/x-tika-msoffice" -> {
                String t = tika.detect(contenido, n);
                yield "application/msword".equals(t) || "application/vnd.ms-excel".equals(t) ? t : porBytes;
            }
            case "text/plain" -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".md") ? "text/markdown" : porBytes;
            default -> porBytes;
        };
    }

    /** Ancho y alto declarados de una imagen sin decodificarla (null si no es una imagen legible). */
    public static int[] dimensiones(byte[] imagen) {
        try (javax.imageio.stream.ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(imagen))) {
            java.util.Iterator<javax.imageio.ImageReader> lectores = ImageIO.getImageReaders(in);
            if (!lectores.hasNext()) {
                return null;
            }
            javax.imageio.ImageReader r = lectores.next();
            try {
                r.setInput(in, true, true);
                return new int[] {r.getWidth(0), r.getHeight(0)};
            } finally {
                r.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Más píxeles que esto no se decodifican para enderezar (evita agotar la memoria con una imagen que declara dimensiones enormes). */
    static final long MAX_PIXELES = 40_000_000L;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ValidadorArchivos.class);

    /** Aplica la orientación EXIF a la foto (las de celular vienen giradas). Si no se puede, la deja como llegó. */
    static byte[] enderezar(byte[] jpeg) {
        int[] dim = dimensiones(jpeg);
        if (dim == null || (long) dim[0] * dim[1] > MAX_PIXELES) {
            LOG.info("[archivos] Foto sin enderezar: dimensiones {} fuera del tope", dim == null ? "ilegibles" : dim[0] + "x" + dim[1]);
            return jpeg;
        }
        try {
            Metadata md = ImageMetadataReader.readMetadata(new ByteArrayInputStream(jpeg));
            ExifIFD0Directory d = md.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (d == null || !d.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return jpeg;
            }
            int o = d.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            if (o <= 1 || o > 8) {
                return jpeg;
            }
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (img == null) {
                return jpeg;
            }
            int w = img.getWidth();
            int h = img.getHeight();
            boolean gira = o >= 5;
            // x' = m00 x + m01 y + m02; y' = m10 x + m11 y + m12 (orientaciones EXIF 2 a 8; de la 5 a la 8 se cruzan ancho y alto)
            AffineTransform t = switch (o) {
                case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
                case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
                case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
                case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
                case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
                case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
                default -> new AffineTransform(0, -1, 1, 0, 0, w);
            };
            BufferedImage out = new BufferedImage(gira ? h : w, gira ? w : h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.drawImage(img, t, null);
            g.dispose();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ImageIO.write(out, "jpg", b);
            return b.toByteArray(); // la imagen nueva ya no lleva la etiqueta de orientación
        } catch (IOException | RuntimeException | com.drew.imaging.ImageProcessingException | com.drew.metadata.MetadataException e) {
            LOG.info("[archivos] Foto sin enderezar ({}): se guarda como llegó", e.getClass().getSimpleName());
            return jpeg;
        }
    }
}
