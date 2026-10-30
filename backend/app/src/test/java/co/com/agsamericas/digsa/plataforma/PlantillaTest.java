package co.com.agsamericas.digsa.plataforma;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.Plantilla.Variable;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Módulo Correos, fase C2: reemplazo de variables, escape y validación del texto editado. */
class PlantillaTest {

    private static final Plantilla HTML = new Plantilla("prueba_html", "Prueba", "Pruebas", Plantilla.Formato.HTML, true, "Visita {{numero}}",
            "Aviso {{numero}}", "<p>Hola {{nombre}}: <a href=\"{{enlace}}\">entrar</a></p>{{tabla}}",
            List.of(Variable.obligatoria("numero", "Número", "V-1"), Variable.de("nombre", "Nombre", "Ana"), Variable.obligatoria("enlace", "Enlace",
                    "https://x.example.com"), Variable.bloque("tabla", "Tabla", "<table><tr><td>1</td></tr></table>")));

    private static final Plantilla TEXTO = new Plantilla("prueba_texto", "Prueba", "Pruebas", Plantilla.Formato.TEXTO, true, "Título",
            "Asunto {{numero}}", "Hola {{nombre}}\nVisita {{numero}}", List.of(Variable.obligatoria("numero", "Número", "V-1"),
                    Variable.de("nombre", "Nombre", "Ana")));

    @Test
    void escapaLosValoresMenosLosBloquesYNoReexpande() {
        Plantilla.Renderizada r = HTML.render(Map.of("numero", "V-<1>", "nombre", "{{numero}} & <b>", "enlace", "https://x.example.com/?a=1&b=2",
                "tabla", "<table></table>"));
        assertThat(r.asunto()).isEqualTo("Aviso V-<1>");
        assertThat(r.html()).contains("<h2 style=\"color:#0b3d5c\">Visita V-&lt;1&gt;</h2>")
                .contains("<p>Hola {{numero}} &amp; &lt;b&gt;: <a href=\"https://x.example.com/?a=1&amp;b=2\">entrar</a></p><table></table>");
    }

    @Test
    void textoPlanoEscapadoConSaltos() {
        Plantilla.Renderizada r = TEXTO.render(Map.of("numero", "V-1", "nombre", "<Ana>"));
        assertThat(r.texto()).isEqualTo("Hola <Ana>\nVisita V-1");
        assertThat(r.html()).contains("<p>Hola &lt;Ana&gt;<br>Visita V-1</p>");
    }

    @Test
    void elAsuntoNoLlevaSaltos() {
        assertThat(TEXTO.render(Map.of("numero", "V-1\r\nBcc: x@example.com")).asunto()).isEqualTo("Asunto V-1 Bcc: x@example.com");
    }

    @Test
    void validaVariablesProtegidasYDesconocidas() {
        assertThat(HTML.validar("Aviso {{numero}}", "<p>Hola {{nombre}}: <a href=\"{{enlace}}\">entrar</a></p>{{tabla}}")).isEmpty();
        assertThat(HTML.validar("Aviso", "<p>Hola</p>")).anyMatch(e -> e.contains("{{numero}}") && e.contains("{{enlace}}") && e.contains("{{tabla}}"));
        assertThat(HTML.validar("Aviso {{numero}} {{otra}}", "<p>{{enlace}}{{tabla}}</p>")).anyMatch(e -> e.contains("{{otra}}"));
        assertThat(TEXTO.validar("Asunto {{numero}}", "Sin número")).anyMatch(e -> e.contains("{{numero}}"));
        assertThat(TEXTO.validar("", "Hola {{numero}}")).anyMatch(e -> e.contains("asunto"));
    }

    @Test
    void htmlRestringido() {
        String base = "{{tabla}}<a href=\"{{enlace}}\">x</a>";
        assertThat(HTML.validar("{{numero}}", "<p style=\"color:#961414;font-size:12px\"><strong>Hola</strong> &amp; adiós</p>" + base)).isEmpty();
        assertThat(HTML.validar("{{numero}}", "<script>alert(1)</script>" + base)).anyMatch(e -> e.contains("<script>"));
        assertThat(HTML.validar("{{numero}}", "<p onclick=\"x()\">a</p>" + base)).anyMatch(e -> e.contains("onclick"));
        assertThat(HTML.validar("{{numero}}", "<p style=\"background:url(http://x)\">a</p>" + base)).anyMatch(e -> e.contains("estilo"));
        assertThat(HTML.validar("{{numero}}", "<a href=\"https://malo.example.com\">a</a>" + base)).anyMatch(e -> e.contains("enlaces"));
        assertThat(HTML.validar("{{numero}}", "<a href=\"{{tabla}}\">a</a>" + base)).anyMatch(e -> e.contains("enlaces"));
        assertThat(HTML.validar("{{numero}}", "<p>1 < 2</p>" + base)).anyMatch(e -> e.contains("«<»"));
        assertThat(HTML.validar("{{numero}}", "<p>A & B</p>" + base)).anyMatch(e -> e.contains("&amp;"));
        assertThat(HTML.validar("{{numero}}", "<img src=\"x\">" + base)).anyMatch(e -> e.contains("<img>"));
    }

    @Test
    void elTextoDeFabricaEditableEsValido() {
        assertThat(HTML.validar(HTML.asunto(), HTML.cuerpo())).isEmpty();
        assertThat(TEXTO.validar(TEXTO.asunto(), TEXTO.cuerpo())).isEmpty();
    }
}
