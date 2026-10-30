package co.com.agsamericas.digsa.auditoria;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.Plantilla;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.HtmlUtils;

/** Módulo Correos, fase C2 (precisión 3): la plantilla de fábrica de los logs por correo da el mismo correo que el código anterior. */
class PlantillasAuditoriaFabricaTest {

    @Test
    void logsIdentico() {
        String archivo = "logs_auditoría_<1>&.xlsx";
        Plantilla.Renderizada r = PlantillasAuditoria.LOGS.render(Map.of("archivo", HtmlUtils.htmlEscape(archivo)));
        assertThat(r.asunto()).isEqualTo("Reporte de logs de auditoría · Suite DIGSA");
        assertThat(r.html()).isEqualTo("<p>Adjunto el reporte de logs de auditoría generado en la Suite DIGSA.</p><p>Archivo: "
                + HtmlUtils.htmlEscape(archivo) + "</p>");
        assertThat(PlantillasAuditoria.LOGS.validar(PlantillasAuditoria.LOGS.asunto(), PlantillasAuditoria.LOGS.cuerpo())).isEmpty();
    }
}
