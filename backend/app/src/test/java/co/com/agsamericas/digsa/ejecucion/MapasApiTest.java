package co.com.agsamericas.digsa.ejecucion;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mapas georreferenciados (Anexo 4 §9 y §11: «mapas georreferenciados» con consulta nacional, por Fuerza y por establecimiento):
 * mapa de visitas por departamento (un punto por visita, color por estado agrupado) y mapa de control con la cobertura de los
 * puntos. Los datos se insertan en un año sin visitas reales (2001) y se buscan por id.
 */
class MapasApiTest extends EscenariosVisita {

    private static final String FILTRO = "desde=2001-01-01&hasta=2001-12-31&fuerza=EJC";

    private long punto(String prefijo, String unidad, String modalidad, String municipio) {
        return jdbc.sql("""
                INSERT INTO puntos (nombre, unidad, fuerza, modalidad, municipio, correos, creado_por)
                VALUES (:n, :u, 'EJC', :m, :mu, ARRAY['contacto.punto@example.com'], 'prueba') RETURNING id""")
                .param("n", unico(prefijo)).param("u", unidad).param("m", modalidad).param("mu", municipio).query(Long.class).single();
    }

    private long visita(long punto, long usuario, String fecha, String estado) {
        return jdbc.sql("""
                INSERT INTO visitas (tipo, punto_id, fecha, auditor_id, alcance, estado, checklist_version, creado_por)
                VALUES ('anual', :p, :f, :u, 'Mapa de prueba.', :e, (SELECT version FROM chk_versiones WHERE vigente), :u) RETURNING id""")
                .param("p", punto).param("f", LocalDate.parse(fecha)).param("u", usuario).param("e", estado).query(Long.class).single();
    }

    private static Map<Long, JsonNode> porId(JsonNode lista) {
        Map<Long, JsonNode> m = new HashMap<>();
        lista.forEach(x -> m.put(x.path("id").asLong(), x));
        return m;
    }

    @Test
    @DisplayName("mapa de visitas: estado agrupado, coordenadas del municipio, sin canceladas, sin ubicación contada aparte y alcance por fuerza")
    void visitas() {
        String coordU = crearUsuario("coord", Perfil.COORDINADOR);
        long coordId = idDe(coordU);
        Navegador coord = entrar(coordU);
        JsonNode antes = coord.get("/api/mapas/visitas?" + FILTRO).json();

        long medellin = punto("Mapa ESM ", "ESM", "Dispensación", "05001");
        long sinMunicipio = punto("Mapa NC ", "NC", null, null);
        long programada = visita(medellin, coordId, "2001-03-01", "programada");
        long notificada = visita(medellin, coordId, "2001-03-02", "notificada");
        long reprogramada = visita(medellin, coordId, "2001-03-03", "reprogramada");
        long ejecucion = visita(medellin, coordId, "2001-03-04", "ejecucion");
        long cerrada = visita(medellin, coordId, "2001-03-05", "cerrada");
        long cancelada = visita(medellin, coordId, "2001-03-06", "cancelada");
        visita(sinMunicipio, coordId, "2001-03-07", "programada");
        long fueraDelPeriodo = visita(medellin, coordId, "2000-12-31", "cerrada");

        Respuesta r = coord.get("/api/mapas/visitas?" + FILTRO);
        assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        JsonNode d = r.json();
        Map<Long, JsonNode> v = porId(d.path("visitas"));
        assertThat(v.get(programada).path("estado").asText()).isEqualTo("programada");
        assertThat(v.get(notificada).path("estado").asText()).isEqualTo("programada");
        assertThat(v.get(reprogramada).path("estado").asText()).isEqualTo("programada");
        assertThat(v.get(ejecucion).path("estado").asText()).isEqualTo("ejecucion");
        assertThat(v.get(cerrada).path("estado").asText()).isEqualTo("ejecutada");
        assertThat(v).doesNotContainKeys(cancelada, fueraDelPeriodo);
        JsonNode x = v.get(cerrada);
        assertThat(x.path("departamento").asText()).isEqualTo("05");
        assertThat(x.path("municipio").asText()).isEqualTo("MEDELLÍN");
        assertThat(x.path("latitud").asDouble()).isBetween(6.0, 6.5);
        assertThat(x.path("longitud").asDouble()).isBetween(-75.8, -75.4);
        assertThat(x.path("punto").asText()).startsWith("Mapa ESM ");
        // Sin datos personales: ni líder ni equipo
        assertThat(x.has("auditor")).isFalse();
        assertThat(x.has("lider")).isFalse();

        // Totales, por departamento (Antioquia) y sin ubicación
        JsonNode ta = antes.path("totales");
        JsonNode td = d.path("totales");
        assertThat(td.path("programada").asLong() - ta.path("programada").asLong()).isEqualTo(3);
        assertThat(td.path("ejecucion").asLong() - ta.path("ejecucion").asLong()).isEqualTo(1);
        assertThat(td.path("ejecutada").asLong() - ta.path("ejecutada").asLong()).isEqualTo(1);
        assertThat(d.path("sinUbicacion").asLong() - antes.path("sinUbicacion").asLong()).isEqualTo(1);
        // Los totales son las marcas del mapa (lo que no tiene ubicación va aparte)
        assertThat(td.path("programada").asLong() + td.path("ejecucion").asLong() + td.path("ejecutada").asLong()).isEqualTo(d.path("visitas").size());
        assertThat(d.path("truncado").asBoolean()).isFalse();
        JsonNode antA = departamento(antes, "05");
        JsonNode antD = departamento(d, "05");
        assertThat(antD.path("nombre").asText()).isNotBlank();
        assertThat(antD.path("programada").asLong() - antA.path("programada").asLong()).isEqualTo(3);
        assertThat(antD.path("total").asLong() - antA.path("total").asLong()).isEqualTo(5);

        // Alcance: Sanidad del Ejército las ve; Sanidad de la Armada, no; el auditor no entra
        String ejc = crearUsuario("sanejc", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'EJC' WHERE usuario = :u").param("u", ejc).update();
        assertThat(porId(entrar(ejc).get("/api/mapas/visitas?" + FILTRO).json().path("visitas"))).containsKeys(programada, cerrada);
        String arc = crearUsuario("sanarc", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'ARC' WHERE usuario = :u").param("u", arc).update();
        assertThat(porId(entrar(arc).get("/api/mapas/visitas?desde=2001-01-01&hasta=2001-12-31").json().path("visitas")))
                .doesNotContainKeys(programada, cerrada);
        Navegador aud = entrar(crearUsuario("aud", Perfil.AUDITOR));
        for (String ruta : List.of("/api/mapas/opciones", "/api/mapas/visitas", "/api/mapas/cobertura")) {
            assertThat(aud.get(ruta).estado()).as(ruta).isEqualTo(403);
        }
        assertThat(coord.get("/api/mapas/opciones").json().path("fuerzas").isArray()).isTrue();
    }

    private static JsonNode departamento(JsonNode d, String codigo) {
        for (JsonNode x : d.path("porDepartamento")) {
            if (codigo.equals(x.path("codigo").asText())) {
                return x;
            }
        }
        return MissingNode.getInstance();
    }

    @Test
    @DisplayName("mapa de control: cobertura por punto (visitado, programado, sin visita en el periodo, nunca) sin suministro, satélite ni inactivos")
    void cobertura() {
        String coordU = crearUsuario("coord", Perfil.COORDINADOR);
        long coordId = idDe(coordU);
        Navegador coord = entrar(coordU);
        long sinUbicacionAntes = coord.get("/api/mapas/cobertura?" + FILTRO).json().path("sinUbicacion").asLong();

        long visitado = punto("Cob visitado ", "ESM", "Dispensación", "05001");
        visita(visitado, coordId, "2001-04-01", "cerrada");
        visita(visitado, coordId, "2001-05-01", "programada");
        long programado = punto("Cob programado ", "ESM", "Dispensación", "05001");
        visita(programado, coordId, "2001-04-01", "notificada");
        visita(programado, coordId, "2001-04-02", "cancelada");
        long antes = punto("Cob antes ", "FA", null, "05001");
        visita(antes, coordId, "2000-06-01", "cerrada");
        long nunca = punto("Cob nunca ", "ESM", "Dispensación", "11001");
        visita(nunca, coordId, "2001-04-03", "cancelada");
        visita(nunca, coordId, "2002-02-01", "cerrada"); // después del periodo: al cierre de 2001 nunca se había visitado
        long suministro = punto("Cob suministro ", "ESM", "Suministro", "05001");
        long satelite = punto("Cob satelite ", "ESM", "Satélite", "05001");
        long inactivo = punto("Cob inactivo ", "ESM", "Dispensación", "05001");
        jdbc.sql("UPDATE puntos SET activo = false WHERE id = :id").param("id", inactivo).update();
        long sinMunicipio = punto("Cob sin municipio ", "NC", null, null);

        Respuesta r = coord.get("/api/mapas/cobertura?" + FILTRO);
        assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        JsonNode d = r.json();
        Map<Long, JsonNode> p = porId(d.path("puntos"));
        assertThat(p.get(visitado).path("estado").asText()).isEqualTo("visitado");
        assertThat(p.get(visitado).path("ultimaCerrada").asText()).isEqualTo("2001-04-01");
        assertThat(p.get(programado).path("estado").asText()).isEqualTo("programado");
        assertThat(p.get(antes).path("estado").asText()).isEqualTo("sin_visita");
        assertThat(p.get(antes).path("ultimaCerrada").asText()).isEqualTo("2000-06-01");
        assertThat(p.get(nunca).path("estado").asText()).isEqualTo("nunca");
        assertThat(p.get(nunca).path("departamento").asText()).isEqualTo("11");
        assertThat(p.get(nunca).path("ultimaCerrada").isNull()).isTrue();
        assertThat(p).doesNotContainKeys(suministro, satelite, inactivo, sinMunicipio);
        assertThat(d.path("sinUbicacion").asLong() - sinUbicacionAntes).isEqualTo(1);
        JsonNode t = d.path("totales");
        assertThat(t.path("visitado").asLong() + t.path("programado").asLong() + t.path("sin_visita").asLong() + t.path("nunca").asLong())
                .isEqualTo(d.path("puntos").size());

        // El filtro por unidad deja solo esa unidad; sin fechas, «visitado» es cualquier cerrada
        Map<Long, JsonNode> fa = porId(coord.get("/api/mapas/cobertura?fuerza=EJC&unidad=FA").json().path("puntos"));
        assertThat(fa).containsKey(antes).doesNotContainKeys(visitado, nunca);
        assertThat(fa.get(antes).path("estado").asText()).isEqualTo("visitado");

        // Alcance: Sanidad de la Armada no ve los puntos del Ejército
        String arc = crearUsuario("sanarc", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'ARC' WHERE usuario = :u").param("u", arc).update();
        Navegador deArc = entrar(arc);
        assertThat(porId(deArc.get("/api/mapas/cobertura").json().path("puntos"))).doesNotContainKeys(visitado, programado, antes, nunca);
        // Pedir otra fuerza no amplía el alcance
        assertThat(porId(deArc.get("/api/mapas/cobertura?fuerza=EJC").json().path("puntos"))).isEmpty();
    }

    @Test
    @DisplayName("ESM: solo su punto, sin delatar la visita a necesidad pendiente; el OPL no entra")
    void alcanceEsmYOpl() {
        String coordU = crearUsuario("coord", Perfil.COORDINADOR);
        long coordId = idDe(coordU);
        long propio = punto("Mapa propio ", "ESM", "Dispensación", "05001");
        long ajeno = punto("Mapa ajeno ", "ESM", "Dispensación", "05001");
        long necesidad = jdbc.sql("""
                INSERT INTO visitas (tipo, punto_id, fecha, auditor_id, alcance, estado, fecha_solicitud, checklist_version, creado_por)
                VALUES ('necesidad', :p, DATE '2001-07-01', :u, 'Sin previo aviso.', 'programada', DATE '2001-06-20',
                        (SELECT version FROM chk_versiones WHERE vigente), :u) RETURNING id""")
                .param("p", propio).param("u", coordId).query(Long.class).single();
        long ajena = visita(ajeno, coordId, "2001-07-02", "programada");
        String esm = crearUsuario("esm", Perfil.ESM);
        jdbc.sql("UPDATE usuarios SET alcance_punto = :p WHERE usuario = :u").param("p", propio).param("u", esm).update();
        Navegador deEsm = entrar(esm);

        assertThat(porId(deEsm.get("/api/mapas/visitas?" + FILTRO).json().path("visitas"))).doesNotContainKeys(necesidad, ajena);
        Map<Long, JsonNode> cob = porId(deEsm.get("/api/mapas/cobertura?" + FILTRO).json().path("puntos"));
        assertThat(cob).containsOnlyKeys(propio);
        assertThat(cob.get(propio).path("estado").asText()).as("la visita a necesidad pendiente no se anuncia").isEqualTo("nunca");
        // El coordinador sí la ve
        Navegador coord = entrar(coordU);
        assertThat(porId(coord.get("/api/mapas/visitas?" + FILTRO).json().path("visitas"))).containsKeys(necesidad, ajena);

        Navegador opl = entrar(crearUsuario("opl", Perfil.OPL_NACIONAL));
        for (String ruta : List.of("/api/mapas/opciones", "/api/mapas/visitas", "/api/mapas/cobertura")) {
            assertThat(opl.get(ruta).estado()).as(ruta).isEqualTo(403);
        }
    }
}
