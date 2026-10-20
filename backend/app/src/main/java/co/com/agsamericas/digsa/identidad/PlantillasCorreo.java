package co.com.agsamericas.digsa.identidad;

import org.springframework.web.util.HtmlUtils;

/** Correos de la identidad, con todo valor escapado. */
final class PlantillasCorreo {

    private PlantillasCorreo() {
    }

    static String marco(String titulo, String cuerpoHtml) {
        return """
                <!doctype html><html lang="es"><body style="font-family:Arial,Helvetica,sans-serif;color:#1f2937;max-width:600px">
                <h2 style="color:#0b3d5c">%s</h2>%s
                <p style="color:#6b7280;font-size:12px">Suite DIGSA · Interventoría AGS Colombia. Este correo se generó automáticamente; no lo responda.</p>
                </body></html>""".formatted(e(titulo), cuerpoHtml);
    }

    static String enlaceClave(String nombre, String motivo, String enlace, int minutos) {
        return marco("Suite DIGSA", """
                <p>Hola %s:</p><p>%s</p>
                <p><a href="%s" style="background:#0b3d5c;color:#fff;padding:10px 16px;text-decoration:none;border-radius:4px">Definir mi contraseña</a></p>
                <p>El enlace sirve una sola vez y vence en %d minutos. Si usted no lo pidió, ignore este correo.</p>"""
                .formatted(e(nombre), e(motivo), e(enlace), minutos));
    }

    static String codigoMfa(String nombre, String codigo, int minutos) {
        return marco("Código de verificación", """
                <p>Hola %s:</p><p>Su código para ingresar a la Suite DIGSA es:</p>
                <p style="font-size:28px;letter-spacing:6px;font-weight:bold">%s</p>
                <p>Vence en %d minutos. Si usted no está ingresando, cambie su contraseña y avise al administrador.</p>"""
                .formatted(e(nombre), e(codigo), minutos));
    }

    static String e(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s, "UTF-8");
    }
}
