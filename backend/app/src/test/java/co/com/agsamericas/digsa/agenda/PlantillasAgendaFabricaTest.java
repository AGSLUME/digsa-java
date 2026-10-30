package co.com.agsamericas.digsa.agenda;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.Plantilla;
import co.com.agsamericas.digsa.plataforma.PlantillaCorreo;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Módulo Correos, fase C2 (precisión 3): el texto de fábrica de las plantillas de la agenda produce el mismo asunto y el mismo html
 * que el código anterior, byte a byte (lo «de antes» es la copia literal de ese código). Los valores llevan caracteres que se escapan.
 */
class PlantillasAgendaFabricaTest {

    @Test
    void notificacionPrevia() {
        String equipo = "- Ana <Pérez> · Químico farmacéutico (auditor líder) · ana@example.com · documento 123\n- Luis & Cía · Otro · luis@example.com · documento sin registrar\n";
        for (String eq : new String[] {equipo, ""}) {
            Map<String, String> v = new HashMap<>(Map.of("numero", "VS-2026-0001", "punto", "ESM <Uno>", "sufijo", " · reprogramada",
                    "fecha", "lunes 3 de noviembre de 2026, 08:00 h", "tipo", "Seguimiento", "unidad", "ESM · ESM <Uno> (Dispensación)",
                    "municipio", "Bogotá, Bogotá D.C.", "direccion", "Calle 1 # 2-3", "alcance", "Línea 1\nLínea 2 & <b>", "equipo", eq));
            Plantilla.Renderizada r = PlantillasAgenda.NOTIFICACION_PREVIA.render(v);
            String antes = "Número de la visita: " + v.get("numero") + "\nFecha: " + v.get("fecha") + "\nTipo de visita: " + v.get("tipo")
                    + "\nUnidad de intervención: " + v.get("unidad") + "\nMunicipio: " + v.get("municipio") + "\nDirección: " + v.get("direccion")
                    + "\n\nAlcance específico:\n" + v.get("alcance") + "\n\nEquipo auditor:\n" + eq
                    + "\nLos datos del equipo auditor se comparten solo para el control de ingreso al establecimiento (Ley 1581 de 2012); "
                    + "no se pueden usar para otro fin.\n\nInterventoría DIGSA";
            assertThat(r.texto()).isEqualTo(antes);
            assertThat(r.asunto()).isEqualTo("Notificación de visita de seguimiento VS-2026-0001 · ESM <Uno> · reprogramada");
            // El envío arma el html con el cuerpo (que el coordinador puede ajustar a mano) igual que antes
            assertThat(r.html()).isEqualTo(PlantillaCorreo.marco("Notificación de visita de seguimiento", "<p>" + PlantillaCorreo.texto(antes) + "</p>"));
        }
    }

    @Test
    void solicitudNueva() {
        Map<String, String> v = Map.of("id", "15", "entidad", "Sanidad <Ejército>", "punto", "ESM & Uno", "fecha", "lunes 3 de noviembre de 2026",
                "limite", "lunes 10 de noviembre de 2026", "motivo", "Quejas\nrepetidas <graves>");
        Plantilla.Renderizada r = PlantillasAgenda.SOLICITUD_NUEVA.render(v);
        String cuerpo = "Nueva solicitud de visita a necesidad N.º " + v.get("id") + "\n\nEntidad: " + v.get("entidad") + "\nPunto: " + v.get("punto")
                + "\nFecha del escrito: " + v.get("fecha") + "\nPlazo máximo para la visita: " + v.get("limite") + " (5 días hábiles, Anexo 3 p.14)\n\nMotivo:\n"
                + v.get("motivo") + "\n\nAgéndela en la Suite DIGSA › Solicitudes de visita.";
        assertThat(r.asunto()).isEqualTo("Solicitud de visita a necesidad N.º 15 · ESM & Uno");
        assertThat(r.html()).isEqualTo(PlantillaCorreo.marco("Solicitud de visita a necesidad", "<p>" + PlantillaCorreo.texto(cuerpo) + "</p>"));
    }

    @Test
    void solicitudAgendadaYCancelada() {
        Map<String, String> v = Map.of("nombre", "Ana <P>", "entidad", "Sanidad & Cía", "fecha_escrito", "lunes 3 de noviembre de 2026",
                "numero", "VN-2026-0001", "punto", "ESM <Uno>", "fecha", "jueves 6 de noviembre de 2026, 08:00 h", "motivo", "Cierre <inventario>");
        String inicio = "Cordial saludo, " + v.get("nombre") + ":\n\nLa solicitud de visita a necesidad de " + v.get("entidad") + " del " + v.get("fecha_escrito");
        String agendada = inicio + " quedó agendada:\n\n- Visita N.º: " + v.get("numero") + "\n- Punto: " + v.get("punto") + "\n- Fecha: " + v.get("fecha")
                + "\n\nLa visita a necesidad se hace sin previo aviso al operador logístico (Anexo 3 p.14): por favor no comparta esta "
                + "información con el OPL ni con el punto." + "\n\nInterventoría DIGSA";
        String cancelada = inicio + " tenía agendada la visita " + v.get("numero") + ", que fue cancelada.\nMotivo: " + v.get("motivo")
                + "\n\nLa solicitud vuelve a quedar pendiente para que el coordinador la reprograme; su plazo se sigue contando desde la fecha del escrito."
                + "\n\nInterventoría DIGSA";
        Plantilla.Renderizada a = PlantillasAgenda.SOLICITUD_AGENDADA.render(v);
        assertThat(a.asunto()).isEqualTo("Visita a necesidad agendada VN-2026-0001 · ESM <Uno>");
        assertThat(a.html()).isEqualTo(PlantillaCorreo.marco("Visita a necesidad agendada", "<p>" + PlantillaCorreo.texto(agendada) + "</p>"));
        Plantilla.Renderizada c = PlantillasAgenda.SOLICITUD_CANCELADA.render(v);
        assertThat(c.asunto()).isEqualTo("Visita a necesidad cancelada VN-2026-0001 · ESM <Uno>");
        assertThat(c.html()).isEqualTo(PlantillaCorreo.marco("Visita a necesidad cancelada", "<p>" + PlantillaCorreo.texto(cancelada) + "</p>"));
    }

    @Test
    void elTextoDeFabricaPasaSuPropiaValidacion() {
        for (Plantilla p : new PlantillasAgenda().plantillas()) {
            assertThat(p.editable()).isTrue();
            assertThat(p.validar(p.asunto(), p.cuerpo())).as(p.clave()).isEmpty();
        }
    }
}
