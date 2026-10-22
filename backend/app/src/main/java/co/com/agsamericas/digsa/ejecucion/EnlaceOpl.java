package co.com.agsamericas.digsa.ejecucion;

import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.reglas.Plazos;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Enlace de respuesta del OPL que va en el acta con hallazgos (decisión 25): token aleatorio (solo su huella se guarda), plazos de 3 y 5
 * días hábiles desde el envío (decisión 8; hasta las 23:59:59 del último día hábil) y el QR del enlace.
 */
public record EnlaceOpl(String token, String url, OffsetDateTime enviado, OffsetDateTime venceObjeciones, OffsetDateTime vencePlan, byte[] qr) {

    public static final int DIAS_OBJECIONES = 3;
    public static final int DIAS_PLAN = 5;

    public String huella() {
        return Huellas.sha256(token);
    }

    /** Fin del último día hábil (23:59:59 en Bogotá) a n días hábiles del envío, sin contar el día del envío. */
    public static OffsetDateTime vence(Plazos plazos, OffsetDateTime envio, int dias) {
        LocalDate d = plazos.sumarHabiles(envio.atZoneSameInstant(Fechas.BOGOTA).toLocalDate(), dias);
        return ZonedDateTime.of(d.atTime(23, 59, 59), Fechas.BOGOTA).toOffsetDateTime();
    }

    static EnlaceOpl nuevo(Plazos plazos, OffsetDateTime envio, java.util.function.Function<String, String> enlace) {
        String token = Huellas.secreto(32);
        String url = enlace.apply("opl-responder?t=" + token);
        return new EnlaceOpl(token, url, envio, vence(plazos, envio, DIAS_OBJECIONES), vence(plazos, envio, DIAS_PLAN), qr(url));
    }

    /** Fase del flujo: «objeciones» hasta el primer vencimiento, «plan» hasta el segundo y «vencido» después. */
    public static String fase(OffsetDateTime venceObjeciones, OffsetDateTime vencePlan, OffsetDateTime ahora) {
        return !ahora.isAfter(venceObjeciones) ? "objeciones" : !ahora.isAfter(vencePlan) ? "plan" : "vencido";
    }

    static byte[] qr(String texto) {
        try {
            BitMatrix m = new QRCodeWriter().encode(texto, BarcodeFormat.QR_CODE, 300, 300, Map.of(EncodeHintType.MARGIN, 1));
            BufferedImage img = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
            for (int x = 0; x < m.getWidth(); x++) {
                for (int y = 0; y < m.getHeight(); y++) {
                    img.setRGB(x, y, m.get(x, y) ? 0x000000 : 0xFFFFFF);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("No se pudo generar el código QR del acta", e);
        }
    }

    /** Texto de los plazos, destacado en el acta y en el correo (decisión 11.5: sin firma tardía). */
    String textoPlazos() {
        return "Plazo para aceptar u objetar cada hallazgo: hasta el " + Plazos.fechaLarga(venceObjeciones.atZoneSameInstant(Fechas.BOGOTA).toLocalDate())
                + " (" + DIAS_OBJECIONES + " días hábiles). PLAZO MÁXIMO PARA REMITIR EL PLAN DE MEJORAMIENTO FIRMADO: " + DIAS_PLAN
                + " DÍAS HÁBILES, HASTA EL " + Plazos.fechaLarga(vencePlan.atZoneSameInstant(Fechas.BOGOTA).toLocalDate()).toUpperCase()
                + ". Vencido el plazo no se recibe: el plan queda vencido y se reporta a la DIGSA. El código de verificación de 6 dígitos se envía "
                + "al correo del operador logístico.";
    }
}
