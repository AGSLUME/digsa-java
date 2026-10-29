package co.com.agsamericas.digsa.presupuesto;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.Plantilla;
import co.com.agsamericas.digsa.plataforma.PlantillaCorreo;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Módulo Correos, fase C2 (precisión 3): las plantillas de fábrica del presupuesto dan el mismo correo que el código anterior. */
class PlantillasPresupuestoFabricaTest {

    @Test
    void reporteIdentico() {
        String titulo = "Ejecución <por> línea & fuerza";
        String archivo = "ejecucion_<1>.xlsx";
        Plantilla.Renderizada r = PlantillasPresupuesto.REPORTE.render(Map.of("titulo", titulo, "archivo", archivo));
        assertThat(r.asunto()).isEqualTo(titulo + " · Suite DIGSA");
        assertThat(r.html()).isEqualTo(PlantillaCorreo.marco(titulo, "<p>Adjunto el reporte generado en la Suite DIGSA.</p><p>Archivo: " + PlantillaCorreo.e(archivo) + "</p>"));
        assertThat(PlantillasPresupuesto.REPORTE.validar(PlantillasPresupuesto.REPORTE.asunto(), PlantillasPresupuesto.REPORTE.cuerpo())).isEmpty();
    }

    @Test
    void notificacionIdentica() {
        String consecutivo = "PRE-2026-0001";
        String filas = "<tr><th style=\"text-align:left;padding:2px 8px\">L&amp;nea</th><td style=\"padding:2px 8px\">&lt;1&gt;</td></tr>";
        String enlace = "https://suite.example.com/presupuesto?a=1&b=2";
        Plantilla.Renderizada r = PlantillasPresupuesto.NOTIFICACION.render(Map.of("consecutivo", consecutivo, "asunto", "Línea <al> 90 % & más",
                "datos", filas, "enlace", enlace));
        assertThat(r.asunto()).isEqualTo(consecutivo + " · " + "Línea <al> 90 % & más");
        String cuerpo = "<p>La interventoría informa a la DIGSA y a la Dirección de Sanidad o Jefatura de Salud correspondiente, en cumplimiento del Anexo 2 "
                + "(auditoría de facturación), la siguiente novedad de la ejecución presupuestal del contrato de medicamentos:</p>"
                + "<table style=\"border-collapse:collapse\">" + filas + "</table>"
                + "<p>Consulte el detalle en la Suite DIGSA: <a href=\"" + PlantillaCorreo.e(enlace) + "\">" + PlantillaCorreo.e(enlace)
                + "</a></p><p>Generado por la interventoría · Suite DIGSA.</p>";
        assertThat(r.html()).isEqualTo(PlantillaCorreo.marco("Notificación presupuestal " + consecutivo, cuerpo));
        assertThat(PlantillasPresupuesto.NOTIFICACION.validar(PlantillasPresupuesto.NOTIFICACION.asunto(), PlantillasPresupuesto.NOTIFICACION.cuerpo()))
                .isEmpty();
    }
}
