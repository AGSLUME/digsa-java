package co.com.agsamericas.digsa.correos;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Módulo Correos, fase C3: vista consolidada de destinatarios y edición de los buzones de la DIGSA y de las fuerzas con motivo. */
class DestinatariosApiTest extends PruebaIntegracion {

    @Test
    @DisplayName("la vista dice a quién le llega cada correo y de dónde sale; la consulta queda en los logs; solo el Administrador")
    void vista() {
        String adm = crearUsuario("admdes", Perfil.ADMINISTRADOR);
        Navegador n = navegador();
        n.ingresar(adm, CLAVE);
        Respuesta r = n.get("/api/correos/destinatarios");
        assertThat(r.estado()).isEqualTo(200);
        JsonNode j = r.json();
        assertThat(j.path("reglas").size()).isGreaterThanOrEqualTo(10);
        assertThat(j.path("reglas").toString()).contains("Lo indica quien envía, solo a los dominios permitidos.").contains("Todos los coordinadores activos.");
        assertThat(j.path("dominiosReportes").isArray()).isTrue();
        assertThat(j.path("fuerzas").size()).isPositive();
        assertThat(j.path("alertas").size()).isPositive();
        assertThat(bitacoraDe(idDe(adm))).anyMatch(b -> b.startsWith("consulta|correo_destinatarios_vistos|"));

        String coord = crearUsuario("coodes", Perfil.COORDINADOR);
        Navegador c = navegador();
        c.ingresar(coord, CLAVE);
        assertThat(c.get("/api/correos/destinatarios").estado()).isEqualTo(403);
        assertThat(c.put("/api/correos/destinatarios/digsa", Map.of("correos", List.of("x@example.com"), "motivo", "Intento sin permiso")).estado())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("el buzón de la DIGSA y los de una fuerza se cambian con motivo por el mismo servicio de Catálogos, y se validan")
    void editarBuzones() {
        String adm = crearUsuario("admdee", Perfil.ADMINISTRADOR);
        Navegador n = navegador();
        n.ingresar(adm, CLAVE);
        JsonNode antes = n.get("/api/correos/destinatarios").json();
        List<String> digsaAntes = lista(antes.path("digsa"));
        JsonNode fuerza = antes.path("fuerzas").get(0);
        String codigo = fuerza.path("codigo").asText();
        List<String> fuerzaAntes = lista(fuerza.path("correos"));
        try {
            assertThat(n.put("/api/correos/destinatarios/digsa", Map.of("correos", List.of("no-es-correo"), "motivo", "Cambio del buzón de la DIGSA"))
                    .estado()).isEqualTo(422);
            assertThat(n.put("/api/correos/destinatarios/digsa", Map.of("correos", List.of(), "motivo", "Cambio del buzón de la DIGSA")).estado())
                    .isEqualTo(422);
            assertThat(n.put("/api/correos/destinatarios/digsa", Map.of("correos", List.of("digsa.nueva@example.com"), "motivo", "corto")).estado())
                    .isEqualTo(422);

            Respuesta ok = n.put("/api/correos/destinatarios/digsa", Map.of("correos", List.of("digsa.nueva@example.com", "DIGSA.NUEVA@example.com",
                    "otra.digsa@example.com"), "motivo", "La DIGSA cambió su buzón institucional"));
            assertThat(ok.estado()).isEqualTo(200);
            assertThat(lista(ok.json().path("correos"))).containsExactly("digsa.nueva@example.com", "otra.digsa@example.com");
            assertThat(jdbc.sql("SELECT valor FROM par_parametros WHERE clave = 'correo_digsa'").query(String.class).single())
                    .isEqualTo("digsa.nueva@example.com, otra.digsa@example.com");
            assertThat(bitacoraDe(idDe(adm))).anyMatch(b -> b.startsWith("actualizacion|correo_destinatarios_editados|") && b.contains("Buzón de la DIGSA")
                    && b.contains("La DIGSA cambió su buzón institucional") && b.contains("Parámetro correo_digsa"));

            Respuesta f = n.put("/api/correos/destinatarios/fuerzas/" + codigo, Map.of("correos", List.of("sanidad.prueba@example.com"),
                    "motivo", "Nuevo buzón de la Sanidad de la fuerza"));
            assertThat(f.estado()).isEqualTo(200);
            JsonNode despues = n.get("/api/correos/destinatarios").json().path("fuerzas");
            for (JsonNode x : despues) {
                if (x.path("codigo").asText().equals(codigo)) {
                    assertThat(lista(x.path("correos"))).containsExactly("sanidad.prueba@example.com");
                    assertThat(x.path("nombre").asText()).isEqualTo(fuerza.path("nombre").asText());
                    assertThat(x.path("activo").asBoolean()).isEqualTo(fuerza.path("activo").asBoolean());
                }
            }
            assertThat(n.put("/api/correos/destinatarios/fuerzas/NO_EXISTE", Map.of("correos", List.of(), "motivo", "Fuerza que no existe"))
                    .estado()).isEqualTo(404);
        } finally {
            jdbc.sql("UPDATE par_parametros SET valor = :v WHERE clave = 'correo_digsa'").param("v", String.join(", ", digsaAntes)).update();
            jdbc.sql("UPDATE par_fuerzas SET correos = :c WHERE codigo = :f").param("c", fuerzaAntes.toArray(String[]::new)).param("f", codigo).update();
        }
    }

    private static List<String> lista(JsonNode arreglo) {
        List<String> r = new ArrayList<>();
        arreglo.forEach(x -> r.add(x.asText()));
        return r;
    }
}
