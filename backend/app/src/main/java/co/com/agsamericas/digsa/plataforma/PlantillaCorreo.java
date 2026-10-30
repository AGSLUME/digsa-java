package co.com.agsamericas.digsa.plataforma;

import org.springframework.web.util.HtmlUtils;

/** Marco común de los correos de la Suite DIGSA, con todo valor escapado. */
public final class PlantillaCorreo {

    /** Lo que queda en la bitácora de envíos en lugar de un código de un solo uso (MFA, QR del OPL). */
    public static final String OCULTO = "••••••";
    /** Lo que queda en la bitácora de envíos en lugar de un enlace con token (contraseña, respuesta del OPL). */
    public static final String ENLACE_OCULTO = "[enlace oculto]";

    private PlantillaCorreo() {
    }

    public static String marco(String titulo, String cuerpoHtml) {
        return """
                <!doctype html><html lang="es"><body style="font-family:Arial,Helvetica,sans-serif;color:#1f2937;max-width:600px">
                <h2 style="color:#0b3d5c">%s</h2>%s
                <p style="color:#6b7280;font-size:12px">Suite DIGSA · Interventoría AGS Colombia. Este correo se generó automáticamente; no lo responda.</p>
                </body></html>""".formatted(e(titulo), cuerpoHtml);
    }

    /** Texto plano a HTML: escapado y con los saltos de línea como {@code <br>}. */
    public static String texto(String plano) {
        return e(plano).replace("\r\n", "\n").replace("\n", "<br>");
    }

    public static String e(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s, "UTF-8");
    }
}
