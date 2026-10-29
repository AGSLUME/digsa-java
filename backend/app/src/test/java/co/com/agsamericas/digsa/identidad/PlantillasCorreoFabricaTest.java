package co.com.agsamericas.digsa.identidad;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.PlantillaCorreo;
import org.junit.jupiter.api.Test;

/**
 * Módulo Correos, fase C2 (precisión 3 de la integradora): el texto de fábrica de cada plantilla produce el mismo html que se enviaba
 * antes, byte a byte. Lo «de antes» es la copia literal del código anterior.
 */
class PlantillasCorreoFabricaTest {

    // Valores con caracteres que se escapan, para que también se compare el escape
    private static final String NOMBRE = "Ana <Pérez> & \"Cía\"";

    @Test
    void enlaceClaveIdentico() {
        assertThat(PlantillasCorreo.enlaceClave(NOMBRE, "Usted pidió <recuperar> su contraseña.", "https://x.example.com/definir-clave?tkn=a&b", 30))
                .isEqualTo(antesEnlaceClave(NOMBRE, "Usted pidió <recuperar> su contraseña.", "https://x.example.com/definir-clave?tkn=a&b", 30));
    }

    @Test
    void codigoMfaIdentico() {
        assertThat(PlantillasCorreo.codigoMfa(NOMBRE, "123456", 10)).isEqualTo(antesCodigoMfa(NOMBRE, "123456", 10));
        assertThat(PlantillasCorreo.codigoMfa(NOMBRE, PlantillaCorreo.OCULTO, 10)).isEqualTo(antesCodigoMfa(NOMBRE, PlantillaCorreo.OCULTO, 10));
    }

    @Test
    void noSonEditables() {
        assertThat(new PlantillasCorreo().plantillas()).allMatch(p -> !p.editable());
    }

    private static String antesEnlaceClave(String nombre, String motivo, String enlace, int minutos) {
        return PlantillaCorreo.marco("Suite DIGSA", """
                <p>Hola %s:</p><p>%s</p>
                <p><a href="%s" style="background:#0b3d5c;color:#fff;padding:10px 16px;text-decoration:none;border-radius:4px">Definir mi contraseña</a></p>
                <p>El enlace sirve una sola vez y vence en %d minutos. Si usted no lo pidió, ignore este correo.</p>"""
                .formatted(PlantillaCorreo.e(nombre), PlantillaCorreo.e(motivo), PlantillaCorreo.e(enlace), minutos));
    }

    private static String antesCodigoMfa(String nombre, String codigo, int minutos) {
        return PlantillaCorreo.marco("Código de verificación", """
                <p>Hola %s:</p><p>Su código para ingresar a la Suite DIGSA es:</p>
                <p style="font-size:28px;letter-spacing:6px;font-weight:bold">%s</p>
                <p>Vence en %d minutos. Si usted no está ingresando, cambie su contraseña y avise al administrador.</p>"""
                .formatted(PlantillaCorreo.e(nombre), PlantillaCorreo.e(codigo), minutos));
    }
}
