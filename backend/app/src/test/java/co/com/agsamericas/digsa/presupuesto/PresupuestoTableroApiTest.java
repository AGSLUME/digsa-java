package co.com.agsamericas.digsa.presupuesto;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Presupuesto F5: tablero (cajas, Fuerza × modalidad, ESM con mayor ejecución, detalle) en el alcance de cada perfil. */
class PresupuestoTableroApiTest extends PruebaIntegracion {

    private static final AtomicInteger ANIO = new AtomicInteger(2090);
    private static final ObjectMapper JSON = new ObjectMapper();

    record P(long id, String sigla) {
    }

    P a;
    P b;
    P c;

    @BeforeEach
    void puntos() {
        List<P> ejc = esm("EJC", 2);
        a = ejc.get(0);
        b = ejc.get(1);
        c = esm("ARC", 1).get(0);
    }

    private List<P> esm(String fuerza, int n) {
        return jdbc.sql("""
                SELECT p.id, p.sigla FROM puntos p WHERE p.unidad = 'ESM' AND p.activo AND p.sigla IS NOT NULL AND p.fuerza = :f
                  AND p.modalidad <> 'Satélite' AND NOT EXISTS (SELECT 1 FROM puntos o WHERE o.id <> p.id AND o.sigla = p.sigla) ORDER BY p.id LIMIT :n OFFSET 4""")
                .param("f", fuerza).param("n", n).query((rs, i) -> new P(rs.getLong(1), rs.getString(2))).list();
    }

    private Navegador coordinador() {
        Navegador n = navegador();
        assertThat(n.ingresar(crearUsuario("coordtab", Perfil.COORDINADOR), CLAVE).estado()).isEqualTo(200);
        return n;
    }

    private void novedad(Navegador n, long vig, String tipo, Object... lineas) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < lineas.length; i += 3) {
            Map<String, Object> m = new HashMap<>();
            m.put("puntoId", ((P) lineas[i]).id());
            m.put("modalidad", lineas[i + 1]);
            m.put("valor", new BigDecimal((String) lineas[i + 2]));
            items.add(m);
        }
        Map<String, Object> d = new HashMap<>(Map.of("vigenciaId", vig, "tipo", tipo, "soporte", "Oficio DIGSA del tablero"));
        d.put("items", items);
        assertThat(n.multipart("/api/presupuesto/novedades", Map.of("datos", JSON.writeValueAsString(d)), Map.of(), "archivo").estado()).isEqualTo(201);
    }

    @Test
    @DisplayName("tablero: cajas, Fuerza × modalidad, ESM con mayor ejecución y detalle con lo proyectado al corte; alcance de Sanidad")
    void tablero() throws Exception {
        Navegador n = coordinador();
        int anio = ANIO.incrementAndGet();
        long vig = n.post("/api/presupuesto/vigencias", Map.of("anio", anio)).json().path("id").asLong();
        novedad(n, vig, "asignacion_inicial", a, "dispensacion", "1200000000", a, "suministro", "300000000", b, "dispensacion", "600000000", c, "dispensacion",
                "1200000000");
        novedad(n, vig, "adicion", b, "dispensacion", "600000000");
        String csv = "vigencia;fuerza;esm;modalidad;periodo;facturado\n"
                + anio + ";;" + a.sigla() + ";Dispensación;" + anio + "-01;900000000\n"
                + anio + ";;" + a.sigla() + ";Suministro;" + anio + "-01;100000000\n"
                + anio + ";;" + b.sigla() + ";Dispensación;" + anio + "-01;300000000\n"
                + anio + ";;" + c.sigla() + ";Dispensación;" + anio + "-01;100000000\n"
                // Febrero no cuenta con corte a enero (todo se mide al corte)
                + anio + ";;" + c.sigla() + ";Dispensación;" + anio + "-02;500000000\n";
        assertThat(n.multipart("/api/presupuesto/ejecucion/aplicar", Map.of(), Map.of("e.csv", csv.getBytes(StandardCharsets.UTF_8)), "archivo").estado())
                .isEqualTo(200);

        JsonNode t = n.get("/api/presupuesto/tablero?vigencia=" + vig + "&corte=" + anio + "-01").json();
        assertThat(t.path("corte").asText()).isEqualTo(anio + "-01");
        JsonNode cajas = t.path("cajas");
        assertThat(cajas.path("presupuestoVigente").asText()).isEqualTo("3900000000,00");
        assertThat(cajas.path("adiciones").asInt()).isEqualTo(1);
        assertThat(cajas.path("ejecutado").asText()).isEqualTo("1400000000,00");
        assertThat(cajas.path("porcentaje").asText()).isEqualTo("35,90");
        assertThat(cajas.path("saldo").asText()).isEqualTo("2500000000,00");
        // Enero: A 1.000 de 125 proyectados, B 300 de 100, C 100 de 100 → 2 ESM sobre la proyección de 3
        assertThat(cajas.path("esmSobreProyeccion").asInt()).isEqualTo(2);
        assertThat(cajas.path("esmTotal").asInt()).isEqualTo(3);

        JsonNode ejc = stream(t.path("porFuerza")).filter(x -> "EJC".equals(x.path("fuerza").asText())).findFirst().orElseThrow();
        JsonNode arcF = stream(t.path("porFuerza")).filter(x -> "ARC".equals(x.path("fuerza").asText())).findFirst().orElseThrow();
        assertThat(arcF.path("ejecutado").asText()).as("ARC al corte de enero, sin febrero").isEqualTo("100000000,00");
        assertThat(ejc.path("porModalidad").path("Dispensación").asText()).isEqualTo("1200000000,00");
        assertThat(ejc.path("porModalidad").path("Suministro").asText()).isEqualTo("100000000,00");
        assertThat(ejc.path("ejecutado").asText()).isEqualTo("1300000000,00");
        assertThat(ejc.path("porcentaje").asText()).isEqualTo("48,15");
        assertThat(ejc.path("umbral").asInt()).isEqualTo(25);

        JsonNode mayor = t.path("mayorEjecucion");
        assertThat(mayor.get(0).path("puntoId").asLong()).as("A: 1.000 de 1.500 = 66,67 %").isEqualTo(a.id());
        assertThat(mayor.get(0).path("porcentaje").asText()).isEqualTo("66,67");
        assertThat(mayor.get(0).path("umbralNotificado").asInt()).isEqualTo(75);
        assertThat(mayor.get(2).path("puntoId").asLong()).isEqualTo(c.id());

        JsonNode detalleA = stream(t.path("detalle")).filter(x -> x.path("puntoId").asLong() == a.id() && "dispensacion".equals(x.path("modalidad").asText()))
                .findFirst().orElseThrow();
        assertThat(detalleA.path("proyectado").asText()).isEqualTo("100000000,00");
        assertThat(detalleA.path("desviacion").asText()).isEqualTo("800,00");
        assertThat(detalleA.path("sentido").asText()).isEqualTo("exceso");

        // Sanidad ARC: solo su fuerza
        String sanidad = crearUsuario("santab", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'ARC' WHERE usuario = :u").param("u", sanidad).update();
        Navegador s = navegador();
        assertThat(s.ingresar(sanidad, CLAVE).estado()).isEqualTo(200);
        JsonNode ts = s.get("/api/presupuesto/tablero?vigencia=" + vig + "&corte=" + anio + "-02").json();
        assertThat(ts.path("cajas").path("presupuestoVigente").asText()).isEqualTo("1200000000,00");
        assertThat(ts.path("porFuerza").size()).isEqualTo(1);
        assertThat(ts.path("ambito").asText()).isEqualTo("Fuerza ARC");
        assertThat(ts.path("cajas").path("ejecutado").asText()).as("a febrero sí cuenta febrero").isEqualTo("600000000,00");
        // ESM: solo su punto; Analista: nacional de consulta; Auditor: 403
        String esmU = crearUsuario("esmtab", Perfil.ESM);
        jdbc.sql("UPDATE usuarios SET alcance_punto = :p WHERE usuario = :u").param("p", b.id()).param("u", esmU).update();
        Navegador e = navegador();
        assertThat(e.ingresar(esmU, CLAVE).estado()).isEqualTo(200);
        JsonNode te = e.get("/api/presupuesto/tablero?vigencia=" + vig + "&corte=" + anio + "-01").json();
        assertThat(te.path("cajas").path("presupuestoVigente").asText()).isEqualTo("1200000000,00");
        assertThat(te.path("detalle").size()).isEqualTo(1);
        Navegador an = navegador();
        assertThat(an.ingresar(crearUsuario("anatab", Perfil.ANALISTA), CLAVE).estado()).isEqualTo(200);
        assertThat(an.get("/api/presupuesto/tablero?vigencia=" + vig + "&corte=" + anio + "-01").json().path("cajas").path("presupuestoVigente").asText())
                .isEqualTo("3900000000,00");
        Navegador au = navegador();
        assertThat(au.ingresar(crearUsuario("audtab", Perfil.AUDITOR), CLAVE).estado()).isEqualTo(200);
        assertThat(au.get("/api/presupuesto/tablero").estado()).isEqualTo(403);

        // Vista «Contrato»: sin corte ni proyección; corte inválido 422
        JsonNode contrato = n.get("/api/presupuesto/tablero?punto=" + c.id()).json();
        assertThat(contrato.path("vigencia").isNull()).isTrue();
        // La vista «Contrato» suma todas las vigencias del punto (otras pruebas de la clase comparten la base): igual a la suma de sus saldos
        BigDecimal todo = jdbc.sql("SELECT coalesce(sum(s.facturado), 0) FROM presupuesto_saldos s JOIN presupuesto_lineas l ON l.id = s.linea_id WHERE l.punto_id = :p")
                .param("p", c.id()).query(BigDecimal.class).single();
        assertThat(new BigDecimal(contrato.path("cajas").path("ejecutado").asText().replace(',', '.'))).as("la vista «Contrato» cuenta todo lo ejecutado a la fecha")
                .isEqualByComparingTo(todo).isGreaterThanOrEqualTo(new BigDecimal("600000000"));
        assertThat(contrato.path("cajas").has("esmSobreProyeccion")).isFalse();
        assertThat(n.get("/api/presupuesto/tablero?vigencia=" + vig + "&corte=marzo").estado()).isEqualTo(422);
        assertThat(n.get("/api/presupuesto/tablero?vigencia=999999").estado()).isEqualTo(422);
    }

    private static java.util.stream.Stream<JsonNode> stream(JsonNode arreglo) {
        return java.util.stream.StreamSupport.stream(arreglo.spliterator(), false);
    }
}
