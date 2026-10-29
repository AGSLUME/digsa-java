package co.com.agsamericas.digsa.identidad;

import co.com.agsamericas.digsa.plataforma.Plantilla;
import co.com.agsamericas.digsa.plataforma.Plantilla.Variable;
import co.com.agsamericas.digsa.plataforma.PlantillaCorreo;
import co.com.agsamericas.digsa.plataforma.ProveedorPlantillas;
import co.com.agsamericas.digsa.reglas.Claves;
import co.com.agsamericas.digsa.reglas.Mfa;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Correos de la identidad, con todo valor escapado. Llevan un código o un enlace de un solo uso: se ven en la pantalla Correos pero no
 * se editan (módulo Correos, fase C2).
 */
@Component
class PlantillasCorreo implements ProveedorPlantillas {

    static final Plantilla ENLACE_CLAVE = new Plantilla("clave_enlace", "Enlace para definir la contraseña", "Identidad", Plantilla.Formato.HTML, false,
            "Suite DIGSA", "{{asunto}}", """
            <p>Hola {{nombre}}:</p><p>{{motivo}}</p>
            <p><a href="{{enlace}}" style="background:#0b3d5c;color:#fff;padding:10px 16px;text-decoration:none;border-radius:4px">Definir mi contraseña</a></p>
            <p>El enlace sirve una sola vez y vence en {{minutos}} minutos. Si usted no lo pidió, ignore este correo.</p>""",
            List.of(Variable.obligatoria("asunto", "Asunto según el caso (bienvenida, recuperación, cambio obligatorio)", "Recuperación de contraseña"),
                    Variable.obligatoria("nombre", "Nombre del usuario", "Ana Pérez (ficticia)"),
                    Variable.obligatoria("motivo", "Por qué recibe el enlace", "Usted pidió recuperar su contraseña."),
                    Variable.obligatoria("enlace", "Enlace de un solo uso (en la bitácora queda oculto)", PlantillaCorreo.ENLACE_OCULTO),
                    Variable.obligatoria("minutos", "Minutos de vigencia del enlace", String.valueOf(Claves.MINUTOS_ENLACE))));

    static final Plantilla CODIGO_MFA = new Plantilla("mfa_codigo", "Código de verificación (MFA)", "Identidad", Plantilla.Formato.HTML, false,
            "Código de verificación", "Código de verificación · Suite DIGSA", """
            <p>Hola {{nombre}}:</p><p>Su código para ingresar a la Suite DIGSA es:</p>
            <p style="font-size:28px;letter-spacing:6px;font-weight:bold">{{codigo}}</p>
            <p>Vence en {{minutos}} minutos. Si usted no está ingresando, cambie su contraseña y avise al administrador.</p>""",
            List.of(Variable.obligatoria("nombre", "Nombre del usuario", "Ana Pérez (ficticia)"),
                    Variable.obligatoria("codigo", "Código de 6 dígitos (en la bitácora queda oculto)", PlantillaCorreo.OCULTO),
                    Variable.obligatoria("minutos", "Minutos de vigencia del código", String.valueOf(Mfa.MINUTOS_VIGENCIA))));

    @Override
    public List<Plantilla> plantillas() {
        return List.of(ENLACE_CLAVE, CODIGO_MFA);
    }

    static String enlaceClave(String nombre, String motivo, String enlace, int minutos) {
        return ENLACE_CLAVE.render(Map.of("nombre", n(nombre), "motivo", n(motivo), "enlace", n(enlace), "minutos", String.valueOf(minutos))).html();
    }

    static String codigoMfa(String nombre, String codigo, int minutos) {
        return CODIGO_MFA.render(Map.of("nombre", n(nombre), "codigo", n(codigo), "minutos", String.valueOf(minutos))).html();
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }
}
