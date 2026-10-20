package co.com.agsamericas.digsa.catalogos;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Anexo 5, prueba 4: catálogos (fuerzas, tipos de documento y de factura) creados, modificados y eliminados en vivo, con validaciones. */
class CatalogosApiTest extends PruebaIntegracion {

    private String ultimoCoordinador;

    private Navegador coordinador() {
        Navegador n = navegador();
        ultimoCoordinador = crearUsuario("coord", Perfil.COORDINADOR);
        assertThat(n.ingresar(ultimoCoordinador, CLAVE).estado()).isEqualTo(200);
        return n;
    }

    /** Fila más reciente de este coordinador que contiene el texto (no la de otra prueba). */
    private String filaDelCoordinador(String contiene) {
        return bitacoraDe(idDe(ultimoCoordinador)).stream().filter(f -> f.contains(contiene)).findFirst()
                .orElseThrow(() -> new AssertionError("Sin fila con «" + contiene + "»"));
    }

    /** Código de letras mayúsculas que no está en uso (fuerzas y tipos de documento solo admiten letras). */
    private String letras(int n) {
        java.util.Random r = new java.util.Random();
        while (true) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < n; i++) {
                b.append((char) ('A' + r.nextInt(26)));
            }
            String c = b.toString();
            long uso = jdbc.sql("SELECT (SELECT count(*) FROM par_fuerzas WHERE codigo = :c) + (SELECT count(*) FROM catalogo_valores WHERE codigo = :c)")
                    .param("c", c).query(Long.class).single();
            if (uso == 0) {
                return c;
            }
        }
    }

    private static Map<String, Object> fuerza(String codigo, String nombre) {
        Map<String, Object> m = new HashMap<>();
        m.put("codigo", codigo);
        m.put("nombre", nombre);
        m.put("sanidad", "Sanidad de prueba");
        m.put("correos", List.of("sanidad.prueba@example.com"));
        return m;
    }

    @Test
    @DisplayName("datos iniciales: 4 fuerzas, tipos de documento C/T/R/V, tipos de factura 1–4, 8 regionales y 185 puntos del anexo del OPL")
    void datosIniciales() {
        Respuesta r = coordinador().get("/api/catalogos");
        assertThat(r.estado()).isEqualTo(200);
        assertThat(r.json().path("fuerzas").toString()).contains("\"EJC\"", "\"ARC\"", "\"FAC\"", "\"NA\"");
        assertThat(r.json().path("catalogos").toString()).contains("Cédula de ciudadanía", "Visa", "Acuerdo", "Requisición");
        assertThat(r.json().path("regionales").toString()).contains("Bogotá", "Medellín", "Cali", "Barranquilla", "Bucaramanga", "Cartagena",
                "Ibagué", "Neiva");
        assertThat(jdbc.sql("SELECT count(*) FROM puntos WHERE creado_por = 'carga inicial'").query(Long.class).single()).isEqualTo(185);
        assertThat(jdbc.sql("SELECT count(*) FROM puntos WHERE creado_por = 'carga inicial' AND unidad = 'FA'").query(Long.class).single()).isEqualTo(10);
        assertThat(jdbc.sql("SELECT count(*) FROM puntos WHERE exige_qf").query(Long.class).single()).isGreaterThanOrEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM par_municipios").query(Long.class).single()).isEqualTo(1122);
    }

    @Test
    @DisplayName("prueba 4: crear, modificar y eliminar una fuerza en vivo; queda en la bitácora con antes y después")
    void fuerzaEnVivo() {
        Navegador n = coordinador();
        String codigo = letras(5);
        Respuesta c = n.post("/api/catalogos/fuerzas", fuerza(codigo.toLowerCase(), "Policía Nacional de prueba"));
        assertThat(c.estado()).as(c.texto()).isEqualTo(201);
        assertThat(n.get("/api/catalogos").json().path("fuerzas").toString()).contains("\"" + codigo + "\"");
        assertThat(n.post("/api/catalogos/fuerzas", fuerza(codigo, "Otra")).mensaje()).contains("Ya existe una fuerza");

        Map<String, Object> editada = fuerza(codigo, "Policía Nacional (editada)");
        Respuesta e = n.put("/api/catalogos/fuerzas/" + codigo, editada);
        assertThat(e.estado()).isEqualTo(200);
        assertThat(filaDelCoordinador("Fuerza " + codigo + ":")).contains("nombre: «Policía Nacional de prueba» → «Policía Nacional (editada)»");

        assertThat(n.delete("/api/catalogos/fuerzas/" + codigo).estado()).isEqualTo(200);
        assertThat(n.get("/api/catalogos").json().path("fuerzas").toString()).doesNotContain("\"" + codigo + "\"");
        assertThat(filaDelCoordinador("Fuerza " + codigo + " eliminada")).startsWith("eliminacion|");
    }

    @Test
    @DisplayName("validaciones de la fuerza: código de 2 a 5 letras, nombre, correos; una fuerza en uso no se elimina (se inactiva)")
    void validacionesFuerza() {
        Navegador n = coordinador();
        assertThat(n.post("/api/catalogos/fuerzas", fuerza("P1", "Con número")).estado()).isEqualTo(422);
        assertThat(n.post("/api/catalogos/fuerzas", fuerza("ABCDEF", "Muy largo")).estado()).isEqualTo(422);
        assertThat(n.post("/api/catalogos/fuerzas", fuerza("PQ", "X")).mensaje()).contains("de 2 a 120");
        Map<String, Object> malCorreo = fuerza("PQ", "Correo malo");
        malCorreo.put("correos", List.of("no-es-correo"));
        assertThat(n.post("/api/catalogos/fuerzas", malCorreo).mensaje()).contains("no es válido");
        Respuesta enUso = n.delete("/api/catalogos/fuerzas/EJC");
        assertThat(enUso.estado()).isEqualTo(422);
        assertThat(enUso.mensaje()).contains("está en uso").contains("Inactívela");
    }

    @Test
    @DisplayName("prueba 4: tipos de documento y de factura: crear, modificar y eliminar valores con el formato del catálogo")
    void valoresEnVivo() {
        Navegador n = coordinador();
        String codigo = letras(3);
        Respuesta c = n.post("/api/catalogos/tipo_documento/valores", Map.of("codigo", codigo.toLowerCase(), "descripcion", "Documento de prueba"));
        assertThat(c.estado()).as(c.texto()).isEqualTo(201);
        long id = c.json().path("id").asLong();
        assertThat(n.post("/api/catalogos/tipo_documento/valores", Map.of("codigo", codigo, "descripcion", "Repetido")).mensaje())
                .contains("Ya existe el código");
        assertThat(n.post("/api/catalogos/tipo_documento/valores", Map.of("codigo", "12", "descripcion", "Números")).mensaje())
                .contains("de 1 a 3 letras");
        assertThat(n.post("/api/catalogos/tipo_factura/valores", Map.of("codigo", "AB", "descripcion", "Letras")).mensaje())
                .contains("de 1 a 3 dígitos");
        assertThat(n.put("/api/catalogos/valores/" + id, Map.of("codigo", codigo, "descripcion", "Documento editado")).estado()).isEqualTo(200);
        assertThat(filaDelCoordinador("valor " + codigo + ":")).contains("«Documento de prueba» → «Documento editado»");
        assertThat(n.delete("/api/catalogos/valores/" + id).estado()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM catalogo_valores WHERE id = :id").param("id", id).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("regionales: crear y renombrar, sin repetir; parámetros con rango; los fijos del Anexo 3 no cambian")
    void regionalesYParametros() {
        Navegador n = coordinador();
        String nombre = unico("Regional prueba ");
        Respuesta r = n.post("/api/catalogos/regionales", Map.of("nombre", nombre));
        assertThat(r.estado()).isEqualTo(201);
        assertThat(n.post("/api/catalogos/regionales", Map.of("nombre", nombre)).mensaje()).contains("Ya existe la regional");
        assertThat(n.put("/api/catalogos/regionales/" + r.json().path("id").asLong(), Map.of("nombre", nombre + " B", "activo", false)).estado())
                .isEqualTo(200);

        Respuesta fuera = n.put("/api/catalogos/parametros/aviso_umbral_opl", Map.of("valor", "16"));
        assertThat(fuera.estado()).isEqualTo(422);
        assertThat(fuera.mensaje()).isEqualTo("El valor de «aviso_umbral_opl» debe ser un número entre 0 y 15.");
        assertThat(n.put("/api/catalogos/parametros/aviso_umbral_opl", Map.of("valor", "texto")).estado()).isEqualTo(422);
        assertThat(n.put("/api/catalogos/parametros/notif_dias_opl", Map.of("valor", "10")).mensaje()).contains("regla fija del Anexo 3");
        assertThat(n.put("/api/catalogos/parametros/aviso_umbral_opl", Map.of("valor", "6")).estado()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT valor FROM par_parametros WHERE clave = 'aviso_umbral_opl'").query(String.class).single()).isEqualTo("6");
        n.put("/api/catalogos/parametros/aviso_umbral_opl", Map.of("valor", "5"));
        assertThat(n.put("/api/catalogos/parametros/inicio_contrato", Map.of("valor", "2026-02-30")).estado()).isEqualTo(422);
    }

    @Test
    @DisplayName("solo el coordinador y el administrador: el auditor recibe 403")
    void soloCoordinador() {
        Navegador auditor = navegador();
        auditor.ingresar(crearUsuario("aud", Perfil.AUDITOR), CLAVE);
        assertThat(auditor.get("/api/catalogos").estado()).isEqualTo(403);
        assertThat(auditor.post("/api/catalogos/fuerzas", fuerza("ZZ", "No debe")).estado()).isEqualTo(403);
        assertThat(auditor.get("/api/catalogos/municipios?departamento=05").estado()).as("los municipios los usa cualquiera").isEqualTo(200);
    }
}
