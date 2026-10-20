package co.com.agsamericas.digsa.maestros;

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

/** Puntos, auditores y operador logístico (docs/especificaciones/maestros-carga-masiva). */
class MaestrosApiTest extends PruebaIntegracion {

    private String ultimoCoordinador;

    private Navegador coordinador() {
        Navegador n = navegador();
        ultimoCoordinador = crearUsuario("coord", Perfil.COORDINADOR);
        assertThat(n.ingresar(ultimoCoordinador, CLAVE).estado()).isEqualTo(200);
        return n;
    }

    private static Map<String, Object> punto(String nombre, String unidad) {
        Map<String, Object> m = new HashMap<>();
        m.put("nombre", nombre);
        m.put("unidad", unidad);
        m.put("fuerza", "EJC");
        m.put("modalidad", "Dispensación");
        m.put("municipio", "05001");
        m.put("direccion", "Calle 1 # 2-3");
        m.put("contacto", "Responsable del punto");
        m.put("correos", List.of("Punto.Uno@Example.com; punto.uno@example.com", "otro.punto@example.com"));
        return m;
    }

    @Test
    @DisplayName("los 185 puntos del anexo del OPL se listan con filtros (unidad, fuerza, texto)")
    void listaYFiltros() {
        Navegador n = coordinador();
        Respuesta fa = n.get("/api/maestros/puntos?unidad=FA");
        assertThat(fa.estado()).isEqualTo(200);
        assertThat(fa.json().size()).isGreaterThanOrEqualTo(10);
        assertThat(n.get("/api/maestros/puntos?texto=hospital%20naval").texto()).contains("AMBULATORIA", "HOSPITALARIA");
        assertThat(n.get("/api/maestros/puntos?fuerza=FAC&unidad=ESM").json().size()).isGreaterThanOrEqualTo(14);
    }

    @Test
    @DisplayName("crear y editar un punto: correos sin repetir, NIT solo en farmacia alterna, antes/después sin los datos de contacto")
    void crearYEditar() {
        Navegador n = coordinador();
        String nombre = unico("ESM de prueba ");
        Map<String, Object> p = punto(nombre, "ESM");
        p.put("nit", "900123456");
        Respuesta c = n.post("/api/maestros/puntos", p);
        assertThat(c.estado()).as(c.texto()).isEqualTo(201);
        long id = c.json().path("id").asLong();
        Map<String, Object> guardado = jdbc.sql("SELECT nit, correos::text AS correos FROM puntos WHERE id = :id").param("id", id).query().singleRow();
        assertThat(guardado.get("nit")).as("el NIT se ignora en un ESM").isNull();
        assertThat((String) guardado.get("correos")).isEqualTo("{punto.uno@example.com,otro.punto@example.com}");

        assertThat(n.post("/api/maestros/puntos", punto(nombre, "ESM")).mensaje()).contains("Ya existe un punto");
        Map<String, Object> e = punto(nombre, "ESM");
        e.put("modalidad", "Suministro");
        e.put("contacto", "Otra persona");
        e.put("exigeQf", true);
        assertThat(n.put("/api/maestros/puntos/" + id, e).estado()).isEqualTo(200);
        String log = bitacoraDe(idDe(ultimoCoordinador)).stream().filter(f -> f.contains("Punto " + id + ":")).findFirst().orElseThrow();
        assertThat(log).contains("modalidad: «Dispensación» → «Suministro»", "contacto: modificado", "exigeQf: «false» → «true»")
                .doesNotContain("Otra persona");
        assertThat(n.put("/api/maestros/puntos/" + id + "/estado", Map.of("activo", false)).estado()).isEqualTo(200);
    }

    @Test
    @DisplayName("validaciones del punto: modalidad obligatoria en ESM, unidad y municipio válidos, nivel central sin fuerza = NA")
    void validaciones() {
        Navegador n = coordinador();
        Map<String, Object> sinModalidad = punto(unico("Sin modalidad "), "ESM");
        sinModalidad.remove("modalidad");
        assertThat(n.post("/api/maestros/puntos", sinModalidad).mensaje()).contains("falta la modalidad del ESM");
        Map<String, Object> mala = punto(unico("Unidad mala "), "XYZ");
        mala.put("municipio", "99999");
        assertThat(n.post("/api/maestros/puntos", mala).mensaje()).contains("la unidad debe ser ESM, FA o NC", "el municipio no existe");
        Map<String, Object> nc = punto(unico("Nivel central "), "NC");
        nc.put("fuerza", "");
        Respuesta r = n.post("/api/maestros/puntos", nc);
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        assertThat(jdbc.sql("SELECT fuerza FROM puntos WHERE id = :id").param("id", r.json().path("id").asLong()).query(String.class).single())
                .isEqualTo("NA");
    }

    @Test
    @DisplayName("auditores: rol técnico y municipio base; operador logístico con al menos un correo")
    void auditoresYOpl() {
        Navegador n = coordinador();
        String aud = crearUsuario("aud", Perfil.AUDITOR);
        assertThat(n.get("/api/maestros/auditores").texto()).contains(aud);
        assertThat(n.put("/api/maestros/auditores/" + idDe(aud), Map.of("rol", "brujo")).estado()).isEqualTo(422);
        assertThat(n.put("/api/maestros/auditores/" + idDe(aud), Map.of("rol", "regente", "municipioBase", "11001")).estado()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT rol FROM auditores WHERE usuario_id = :id").param("id", idDe(aud)).query(String.class).single()).isEqualTo("regente");
        assertThat(n.put("/api/maestros/auditores/" + idDe(crearUsuario("coord2", Perfil.COORDINADOR)), Map.of("rol", "regente")).estado()).isEqualTo(404);

        assertThat(n.put("/api/maestros/opl", Map.of("nombre", "Operador", "correos", List.of())).mensaje()).contains("al menos un correo");
        List<String> antes = jdbc.sql("SELECT unnest(correos) FROM opl WHERE activo").query(String.class).list();
        assertThat(n.put("/api/maestros/opl", Map.of("nombre", "Operador logístico de prueba", "nit", "900123456-7",
                "correos", List.of("opl.notificaciones@example.com"))).estado()).isEqualTo(200);
        assertThat(n.get("/api/maestros/opl").texto()).contains("opl.notificaciones@example.com");
        if (!antes.isEmpty()) {
            jdbc.sql("UPDATE opl SET correos = :c WHERE activo").param("c", antes.toArray(String[]::new)).update();
        }
    }
}
