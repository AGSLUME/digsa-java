package co.com.agsamericas.digsa.presupuesto;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
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

/** Presupuesto F6: reportes de ejecución presupuestal (ver, descargar en 4 formatos, enviar) y rendimiento de las cargas grandes. */
class PresupuestoReportesApiTest extends PruebaIntegracion {

    private static final AtomicInteger ANIO = new AtomicInteger(2095);
    private static final ObjectMapper JSON = new ObjectMapper();

    record P(long id, String sigla, String fuerza) {
    }

    P a;
    P c;

    @BeforeEach
    void puntos() {
        a = esm("EJC").get(0);
        c = esm("ARC").get(0);
    }

    private List<P> esm(String fuerza) {
        return jdbc.sql("""
                SELECT p.id, p.sigla, p.fuerza FROM puntos p WHERE p.unidad = 'ESM' AND p.activo AND p.sigla IS NOT NULL AND p.fuerza = :f
                  AND p.modalidad <> 'Satélite' AND NOT EXISTS (SELECT 1 FROM puntos o WHERE o.id <> p.id AND o.sigla = p.sigla) ORDER BY p.id LIMIT 1 OFFSET 6""")
                .param("f", fuerza).query((rs, i) -> new P(rs.getLong(1), rs.getString(2), rs.getString(3))).list();
    }

    private String ultimo;

    private Navegador coordinador() {
        Navegador n = navegador();
        ultimo = crearUsuario("coordrep", Perfil.COORDINADOR);
        assertThat(n.ingresar(ultimo, CLAVE).estado()).isEqualTo(200);
        return n;
    }

    private long preparar(Navegador n, int anio) throws Exception {
        long vig = n.post("/api/presupuesto/vigencias", Map.of("anio", anio)).json().path("id").asLong();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object[] x : List.of(new Object[] {a, "1200000000"}, new Object[] {c, "600000000"})) {
            items.add(Map.of("puntoId", ((P) x[0]).id(), "modalidad", "dispensacion", "valor", new BigDecimal((String) x[1])));
        }
        Map<String, Object> d = new HashMap<>(Map.of("vigenciaId", vig, "tipo", "asignacion_inicial", "soporte", "Oficio DIGSA de asignación"));
        d.put("items", items);
        assertThat(n.multipart("/api/presupuesto/novedades", Map.of("datos", JSON.writeValueAsString(d)), Map.of(), "archivo").estado()).isEqualTo(201);
        Map<String, Object> reb = new HashMap<>(Map.of("vigenciaId", vig, "tipo", "rebalanceo", "soporte", "Oficio de rebalanceo"));
        reb.put("items", List.of(Map.of("puntoId", a.id(), "modalidad", "dispensacion", "valor", new BigDecimal("100000000")),
                Map.of("puntoId", c.id(), "modalidad", "dispensacion", "valor", new BigDecimal("100000000"))));
        assertThat(n.multipart("/api/presupuesto/novedades", Map.of("datos", JSON.writeValueAsString(reb)), Map.of(), "archivo").estado()).isEqualTo(201);
        StringBuilder csv = new StringBuilder("vigencia;fuerza;esm;modalidad;periodo;facturado\n");
        for (int m = 1; m <= 6; m++) {
            csv.append(anio).append(";;").append(a.sigla()).append(";Dispensación;").append(anio).append("-0").append(m).append(";100000000\n");
        }
        csv.append(anio).append(";;").append(c.sigla()).append(";Dispensación;").append(anio).append("-01;50000000\n");
        assertThat(n.multipart("/api/presupuesto/ejecucion/aplicar", Map.of(), Map.of("e.csv", csv.toString().getBytes(StandardCharsets.UTF_8)), "archivo")
                .estado()).isEqualTo(200);
        return vig;
    }

    @Test
    @DisplayName("ejecución por Fuerza, ESM y modalidad: líneas, totales por fuerza y nacional, encabezado con periodo y establecimiento")
    void ejecucion() throws Exception {
        Navegador n = coordinador();
        int anio = ANIO.incrementAndGet();
        long vig = preparar(n, anio);
        assertThat(n.get("/api/presupuesto/reportes").json().path("tipos").size()).isEqualTo(4);
        JsonNode r = n.get("/api/presupuesto/reportes/ejecucion?vigencia=" + vig + "&corte=" + anio + "-06").json();
        assertThat(r.path("filtros").path("Periodo").asText()).isEqualTo(anio + "-01-01 a " + anio + "-12-31");
        assertThat(r.path("filtros").path("Establecimiento").asText()).isEqualTo("Todos");
        assertThat(r.path("filtros").path("Corte").asText()).isEqualTo(anio + "-06");
        List<String> filas = new ArrayList<>();
        r.path("filas").forEach(f -> filas.add(f.get(1).asText() + "|" + f.get(4).asText() + "|" + f.get(7).asText() + "|" + f.get(9).asText()));
        // A: 1.100 asignado (tras el rebalanceo) y 600 ejecutado a junio; C: 700 y 50
        assertThat(filas).contains("EJC|1100000000,00|600000000,00|54,55", "ARC|700000000,00|50000000,00|7,14", "Total EJC|1100000000,00|600000000,00|54,55",
                "Total nacional|1800000000,00|650000000,00|36,11");
        // Filtro de lista con dos valores (parámetro repetido): las dos fuerzas; con una sola, solo esa
        assertThat(n.get("/api/presupuesto/reportes/ejecucion?vigencia=" + vig + "&fuerza=EJC&fuerza=ARC").json().path("filtros").path("Fuerza").asText())
                .isEqualTo("ARC, EJC");
        JsonNode soloArc = n.get("/api/presupuesto/reportes/ejecucion?vigencia=" + vig + "&fuerza=ARC").json();
        soloArc.path("filas").forEach(f -> assertThat(f.get(1).asText()).isIn("ARC", "Total ARC", "Total nacional"));
        JsonNode solo = n.get("/api/presupuesto/reportes/ejecucion?vigencia=" + vig + "&punto=" + a.id()).json();
        assertThat(solo.path("filtros").path("Establecimiento").asText()).startsWith(a.sigla());
    }

    @Test
    @DisplayName("mensual: agrupación trimestral (dos filas por línea de enero a junio) y agrupación inválida")
    void mensual() throws Exception {
        Navegador n = coordinador();
        int anio = ANIO.incrementAndGet();
        long vig = preparar(n, anio);
        JsonNode r = n.get("/api/presupuesto/reportes/mensual?vigencia=" + vig + "&punto=" + a.id() + "&agrupacion=trimestre").json();
        assertThat(r.path("total").asInt()).isEqualTo(4);
        assertThat(r.path("filas").get(0).get(0).asText()).isEqualTo(anio + "-T1");
        assertThat(r.path("filas").get(0).get(4).asText()).as("ejecutado T1").isEqualTo("300000000,00");
        assertThat(r.path("filas").get(0).get(5).asText()).as("proyectado T1 lineal: 1.100 / 12 × 3").isEqualTo("274999999,98");
        assertThat(n.get("/api/presupuesto/reportes/mensual?vigencia=" + vig + "&agrupacion=semana").estado()).isEqualTo(422);
        assertThat(n.get("/api/presupuesto/reportes/mensual").mensaje()).contains("Elija la vigencia");
    }

    @Test
    @DisplayName("movimientos y notificaciones: el rebalanceo con antes y después; descargas en los 4 formatos con bitácora; formato inválido 422")
    void movimientosYFormatos() throws Exception {
        Navegador n = coordinador();
        int anio = ANIO.incrementAndGet();
        long vig = preparar(n, anio);
        JsonNode mov = n.get("/api/presupuesto/reportes/movimientos?vigencia=" + vig + "&tipoMovimiento=rebalanceo").json();
        assertThat(mov.path("total").asInt()).isEqualTo(2);
        assertThat(mov.path("filas").get(0).get(2).asText()).isEqualTo("Rebalanceo");
        assertThat(mov.path("filas").get(0).get(4).asText()).isEqualTo("Oficio de rebalanceo");
        assertThat(mov.path("filas").get(0).get(10).asText() + "→" + mov.path("filas").get(0).get(11).asText()).isEqualTo("1200000000,00→1100000000,00");
        assertThat(n.get("/api/presupuesto/reportes/alertas?vigencia=" + vig).json().path("total").asInt()).as("umbrales de A").isGreaterThanOrEqualTo(1);

        Map<String, String> tipos = Map.of("xlsx", "spreadsheetml", "csv", "text/csv", "pdf", "application/pdf", "txt", "text/plain");
        for (Map.Entry<String, String> t : tipos.entrySet()) {
            Respuesta d = n.get("/api/presupuesto/reportes/movimientos/exportar?formato=" + t.getKey() + "&vigencia=" + vig);
            assertThat(d.estado()).as(t.getKey()).isEqualTo(200);
            assertThat(d.cabecera("Content-Type")).contains(t.getValue());
            assertThat(d.cabecera("Content-Disposition")).contains("presupuesto_movimientos_").contains("." + t.getKey());
        }
        String csv = new String(n.get("/api/presupuesto/reportes/movimientos/exportar?formato=csv&vigencia=" + vig).cuerpo(), StandardCharsets.UTF_8);
        assertThat(csv).contains("Periodo", "Establecimiento", "Oficio de rebalanceo", "1100000000,00");
        String txt = new String(n.get("/api/presupuesto/reportes/ejecucion/exportar?formato=txt&vigencia=" + vig).cuerpo(), StandardCharsets.UTF_8);
        assertThat(txt).contains("Total nacional");
        byte[] pdf = n.get("/api/presupuesto/reportes/ejecucion/exportar?formato=pdf&vigencia=" + vig).cuerpo();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(bitacoraDe(idDe(ultimo))).anyMatch(f -> f.contains("reporte_descargado") && f.contains("presupuesto_ejecucion_"));
        assertThat(n.get("/api/presupuesto/reportes/movimientos/exportar?formato=docx").estado()).isEqualTo(422);
        assertThat(n.get("/api/presupuesto/reportes/otro").estado()).isEqualTo(404);

        assertThat(n.post("/api/presupuesto/reportes/ejecucion/enviar", Map.of("para", "comite@example.com", "formato", "pdf", "vigencia", vig)).estado())
                .isEqualTo(200);
        esperarCorreo(m -> m.para().contains("comite@example.com") && m.asunto().startsWith("Ejecución presupuestal"));
        assertThat(n.post("/api/presupuesto/reportes/ejecucion/enviar", Map.of("para", "alguien@otro-dominio.com")).mensaje()).contains("solo se envían");
    }

    @Test
    @DisplayName("alcance: Sanidad ARC solo ve sus movimientos y su ejecución; Auditor 403")
    void alcance() throws Exception {
        Navegador n = coordinador();
        int anio = ANIO.incrementAndGet();
        long vig = preparar(n, anio);
        String sanidad = crearUsuario("sanrep", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'ARC' WHERE usuario = :u").param("u", sanidad).update();
        Navegador s = navegador();
        assertThat(s.ingresar(sanidad, CLAVE).estado()).isEqualTo(200);
        JsonNode mov = s.get("/api/presupuesto/reportes/movimientos?vigencia=" + vig).json();
        mov.path("filas").forEach(f -> assertThat(f.get(6).asText()).isEqualTo("ARC"));
        JsonNode ej = s.get("/api/presupuesto/reportes/ejecucion?vigencia=" + vig).json();
        assertThat(ej.path("filtros").path("Alcance").asText()).isEqualTo("Fuerza ARC");
        ej.path("filas").forEach(f -> assertThat(f.get(1).asText()).isIn("ARC", "Total ARC", "Total del alcance"));
        Navegador au = navegador();
        assertThat(au.ingresar(crearUsuario("audrep", Perfil.AUDITOR), CLAVE).estado()).isEqualTo(200);
        assertThat(au.get("/api/presupuesto/reportes/ejecucion").estado()).isEqualTo(403);
    }

    @Test
    @DisplayName("rendimiento: una carga de ejecución de 5.000 filas y su reporte salen en segundos (Anexo 5: ver en menos de 3 min, exportar en menos de 10)")
    void rendimiento() throws Exception {
        Navegador n = coordinador();
        int anio = ANIO.incrementAndGet();
        long vig = n.post("/api/presupuesto/vigencias", Map.of("anio", anio)).json().path("id").asLong();
        List<String> siglas = jdbc.sql("""
                SELECT p.sigla FROM puntos p WHERE p.unidad = 'ESM' AND p.activo AND p.sigla IS NOT NULL AND p.fuerza <> 'NA' AND p.modalidad <> 'Satélite'
                  AND NOT EXISTS (SELECT 1 FROM puntos o WHERE o.id <> p.id AND o.sigla = p.sigla) ORDER BY p.id""").query(String.class).list();
        String[] modalidades = {"Dispensación", "Órdenes de requisición", "Suministro"};
        StringBuilder csv = new StringBuilder("vigencia;fuerza;esm;modalidad;periodo;facturado\n");
        int filas = 0;
        for (int m = 1; m <= 12 && filas < 5000; m++) {
            for (String s : siglas) {
                for (String mod : modalidades) {
                    if (filas < 5000) {
                        csv.append(anio).append(";;").append(s).append(';').append(mod).append(';').append(anio).append('-').append(String.format("%02d", m))
                                .append(";1000000\n");
                        filas++;
                    }
                }
            }
        }
        long t0 = System.nanoTime();
        Respuesta r = n.multipart("/api/presupuesto/ejecucion/aplicar", Map.of(), Map.of("grande.csv", csv.toString().getBytes(StandardCharsets.UTF_8)), "archivo");
        long carga = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        assertThat(r.json().path("cortes").asInt()).isEqualTo(filas);
        t0 = System.nanoTime();
        Respuesta x = n.get("/api/presupuesto/reportes/mensual/exportar?formato=xlsx&vigencia=" + vig);
        long reporte = (System.nanoTime() - t0) / 1_000_000;
        assertThat(x.estado()).isEqualTo(200);
        System.out.println("[rendimiento presupuesto] carga de " + filas + " filas: " + carga + " ms; reporte mensual xlsx: " + reporte + " ms");
        assertThat(carga).as("carga de 5.000 filas").isLessThan(60_000);
        assertThat(reporte).as("reporte mensual").isLessThan(60_000);

        // Consolidación: una notificación de umbral (y a lo más una de desviación por sentido) por fuerza, no una por línea
        long fuerzas = jdbc.sql("SELECT count(DISTINCT fuerza) FROM presupuesto_lineas WHERE vigencia_id = :v").param("v", vig).query(Long.class).single();
        assertThat(jdbc.sql("SELECT count(*) FROM presupuesto_notificaciones WHERE vigencia_id = :v AND tipo = 'umbral'").param("v", vig).query(Long.class)
                .single()).isEqualTo(fuerzas);
        assertThat(jdbc.sql("SELECT count(*) FROM presupuesto_notificaciones WHERE vigencia_id = :v AND tipo = 'desviacion' AND nivel = 'esm'").param("v", vig)
                .query(Long.class).single()).isLessThanOrEqualTo(fuerzas * 2);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM presupuesto_umbrales u JOIN presupuesto_lineas l ON l.id = u.linea_id
                WHERE l.vigencia_id = :v AND u.notificada AND u.notificacion_id IS NULL""").param("v", vig).query(Long.class).single())
                .as("todo umbral notificado queda enlazado a su notificación").isZero();
        // Otra carga del mismo año (todas las líneas ya pasaron el 100 %): ningún aviso nuevo de umbral
        String otra = csv.toString().replace(";1000000\n", ";2000000\n");
        assertThat(n.multipart("/api/presupuesto/ejecucion/aplicar", Map.of(), Map.of("otra.csv", otra.getBytes(StandardCharsets.UTF_8)), "archivo").estado())
                .isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM presupuesto_notificaciones WHERE vigencia_id = :v AND tipo = 'umbral'").param("v", vig).query(Long.class)
                .single()).isEqualTo(fuerzas);

        // ESM: ve la consolidada de su fuerza sin el detalle de los demás ESM; el reporte de alertas filtrado por su punto la incluye
        long punto = jdbc.sql("SELECT id FROM puntos WHERE sigla = :s").param("s", siglas.get(0)).query(Long.class).single();
        String esm = crearUsuario("esmrend", Perfil.ESM);
        jdbc.sql("UPDATE usuarios SET alcance_punto = :p WHERE usuario = :u").param("p", punto).param("u", esm).update();
        Navegador e = navegador();
        assertThat(e.ingresar(esm, CLAVE).estado()).isEqualTo(200);
        JsonNode lista = e.get("/api/presupuesto/notificaciones?tipo=umbral&vigencia=" + vig).json();
        assertThat(lista.path("total").asInt()).isEqualTo(1);
        assertThat(lista.path("items").get(0).path("texto").asText()).startsWith("Notificación consolidada de la fuerza").doesNotContain("asignado");
        assertThat(n.get("/api/presupuesto/reportes/alertas?vigencia=" + vig + "&punto=" + punto).json().path("total").asInt()).isGreaterThanOrEqualTo(1);
    }
}
