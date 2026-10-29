package co.com.agsamericas.digsa.alertas;

import co.com.agsamericas.digsa.plataforma.Plantilla;
import co.com.agsamericas.digsa.plataforma.Plantilla.Variable;
import co.com.agsamericas.digsa.plataforma.ProveedorPlantillas;
import java.util.List;
import org.springframework.stereotype.Component;

/** Plantilla del correo de las alertas y avisos (módulo Correos, fase C2). El SMS de las críticas no cambia. */
@Component
class PlantillasAlertas implements ProveedorPlantillas {

    static final Plantilla ALERTA = new Plantilla("alerta", "Alerta o aviso", "Alertas", Plantilla.Formato.HTML, true, "{{titulo}}",
            "{{titulo}} · {{hora}}",
            "<p><strong>{{alerta}}</strong> ({{hora}})</p><p>{{detalle}}</p>{{aviso_sms}}<p>Revise la Suite DIGSA: <a href=\"{{enlace}}\">{{enlace}}</a></p>",
            List.of(Variable.obligatoria("titulo", "«Alerta crítica · » o «Aviso · » y el nombre de la alerta", "Alerta crítica · Correo sin salir"),
                    Variable.obligatoria("hora", "Fecha y hora del disparo", "2026-11-03 08:15"),
                    Variable.obligatoria("alerta", "Nombre de la alerta", "Correo sin salir"),
                    Variable.bloque("detalle", "Detalle del evento", "Hay 3 correos fallidos en la bandeja de salida."),
                    Variable.bloque("aviso_sms", "Párrafo que dice que también salió un SMS (solo en las críticas con SMS)",
                            "<p>Es una alerta crítica: también se envió un SMS a los destinatarios con móvil registrado.</p>"),
                    Variable.obligatoria("enlace", "Enlace a la Suite DIGSA", "https://suite.example.com/")));

    static final String AVISO_SMS = "<p>Es una alerta crítica: también se envió un SMS a los destinatarios con móvil registrado.</p>";

    @Override
    public List<Plantilla> plantillas() {
        return List.of(ALERTA);
    }
}
