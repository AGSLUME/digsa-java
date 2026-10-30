package co.com.agsamericas.digsa.motorreportes;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.Plantilla;
import co.com.agsamericas.digsa.plataforma.PlantillaCorreo;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Módulo Correos, fase C2 (precisión 3): la plantilla de fábrica del reporte por correo da el mismo correo que el código anterior. */
class PlantillasReportesFabricaTest {

    @Test
    void reporteIdentico() {
        String titulo = "Detalle <de> visitas & más";
        String archivo = "detalle_<1>.xlsx";
        String codigo = "RPT-20261103-AB12CD34";
        Plantilla.Renderizada r = PlantillasReportes.REPORTE.render(Map.of("titulo", titulo, "archivo", archivo, "codigo", codigo));
        assertThat(r.asunto()).isEqualTo(titulo + " · Suite DIGSA");
        assertThat(r.html()).isEqualTo(PlantillaCorreo.marco(titulo, "<p>Adjunto el reporte generado en la Suite DIGSA.</p><p>Archivo: " + PlantillaCorreo.e(archivo)
                + "</p><p>Código de verificación: <strong>" + PlantillaCorreo.e(codigo) + "</strong></p>"));
        assertThat(PlantillasReportes.REPORTE.validar(PlantillasReportes.REPORTE.asunto(), PlantillasReportes.REPORTE.cuerpo())).isEmpty();
    }
}
