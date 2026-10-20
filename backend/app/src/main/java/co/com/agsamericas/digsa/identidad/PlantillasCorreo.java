package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.plataforma.PlantillaCorreo;

/** Correos de la identidad, con todo valor escapado. */
final class PlantillasCorreo {

    private PlantillasCorreo() {
    }

    static String marco(String titulo, String cuerpoHtml) {
        return PlantillaCorreo.marco(titulo, cuerpoHtml);
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
        return PlantillaCorreo.e(s);
    }
}
