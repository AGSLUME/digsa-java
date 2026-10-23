package co.com.agsamericas.digsa.presupuesto;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Presupuesto F4: proyección cargada o lineal y alarma de desviación por ESM, fuerza y nacional (caso M7-3). */
class PresupuestoDesviacionApiTest extends PruebaIntegracion {

    private static final AtomicInteger ANIO = new AtomicInteger(2080);
    private static final ObjectMapper JSON = new ObjectMapper();

    record P(long id, String sigla) {
    }

    P a;
    P b;
    String correoDigsa;

    @BeforeEach
    void preparar() {
        List<P> ejc = jdbc.sql("""
                SELECT p.id, p.sigla FROM puntos p WHERE p.unidad = 'ESM' AND p.activo AND p.sigla IS NOT NULL AND p.fuerza = 'EJC'
                  AND p.modalidad = 'Dispensación' AND NOT EXISTS (SELECT 1 FROM puntos o WHERE o.id <> p.id AND o.sigla = p.sigla) ORDER BY p.id LIMIT 2 OFFSET 2""")
                .query((rs, n) -> new P(rs.getLong(1), rs.getString(2))).list();
        a = ejc.get(0);
        b = ejc.get(1);
        correoDigsa = jdbc.sql("SELECT valor FROM par_parametros WHERE clave = 'correo_digsa'").query(String.class).optional().orElse("");
        jdbc.sql("UPDATE par_parametros SET valor = 'digsa.desviacion@example.com' WHERE clave = 'correo_digsa'").update();
    }

    @AfterEach
    void restaurar() {
        jdbc.sql("UPDATE par_parametros SET valor = :c WHERE clave = 'correo_digsa'").param("c", correoDigsa).update();
        jdbc.sql("UPDATE par_parametros SET valor = '10,00' WHERE clave = 'presupuesto_tolerancia_exceso'").update();
    }

    private Navegador coordinador() {
        Navegador n = navegador();
        assertThat(n.ingresar(crearUsuario("coorddes", Perfil.COORDINADOR), CLAVE).estado()).isEqualTo(200);
        return n;
    }

    private long vigencia(Navegador n, int anio, String... lineas) {
        long vig = n.post("/api/presupuesto/vigencias", Map.of("anio", anio)).json().path("id").asLong();
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < lineas.length; i += 2) {
            Map<String, Object> m = new HashMap<>();
            m.put("puntoId", Long.parseLong(lineas[i]));
            m.put("modalidad", "dispensacion");
            m.put("valor", new BigDecimal(lineas[i + 1]));
            items.add(m);
        }
        Map<String, Object> d = new HashMap<>(Map.of("vigenciaId", vig, "tipo", "asignacion_inicial", "soporte", "Oficio DIGSA de asignación"));
        d.put("items", items);
        try {
            assertThat(n.multipart("/api/presupuesto/novedades", Map.of("datos", JSON.writeValueAsString(d)), Map.of(), "archivo").estado()).isEqualTo(201);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return vig;
    }

    private Respuesta cargar(Navegador n, String ruta, String csv) {
        return n.multipart(ruta, Map.of(), Map.of(System.nanoTime() + ".csv", csv.getBytes(StandardCharsets.UTF_8)), "archivo");
    }

    private List<String> desviaciones(long vig) {
        return jdbc.sql("""
                SELECT d.nivel || ':' || coalesce(d.fuerza, CAST(d.linea_id AS text), 'pais') || ':' || to_char(d.corte, 'YYYY-MM') || ':' || d.sentido || ':'
                       || coalesce(CAST(d.desviacion AS text), '-') || ':' || CASE WHEN d.notificacion_id IS NULL THEN 'sin' ELSE 'notificada' END
                FROM presupuesto_desviaciones d WHERE d.vigencia_id = :v ORDER BY d.id""").param("v", vig).query(String.class).list();
    }

    @Test
    @DisplayName("M7-3: ejecución por encima de la proyección: alarma por ESM, por fuerza y nacional; la recarga del mismo corte no la repite")
    void excesoEnTresNiveles() {
        Navegador n = coordinador();
        int anio = ANIO.incrementAndGet();
        long vig = vigencia(n, anio, String.valueOf(a.id()), "1200000000", String.valueOf(b.id()), "1200000000"); // 100 millones al mes cada una
        String csv = "vigencia;fuerza;esm;modalidad;periodo;facturado\n"
                + anio + ";;" + a.sigla() + ";Dispensación;" + anio + "-01;100000000\n"
                + anio + ";;" + a.sigla() + ";Dispensación;" + anio + "-02;100000000\n"
                + anio + ";;" + a.sigla() + ";Dispensación;" + anio + "-03;160000000\n"
                + anio + ";;" + b.sigla() + ";Dispensación;" + anio + "-03;310000000\n";
        Respuesta r = cargar(n, "/api/presupuesto/ejecucion/aplicar", csv);
        assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        long lineaA = jdbc.sql("SELECT id FROM presupuesto_lineas WHERE vigencia_id = :v AND punto_id = :p").param("v", vig).param("p", a.id())
                .query(Long.class).single();
        // A: 360 de 300 (+20 %); B: 310 de 300 (+3,33 %, sin alarma); EJC y país: 670 de 600 (+11,67 %)
        assertThat(desviaciones(vig)).containsExactly(
                "esm:" + lineaA + ":" + anio + "-03:exceso:20.00:notificada",
                "fuerza:EJC:" + anio + "-03:exceso:11.67:notificada",
                "nacional:pais:" + anio + "-03:exceso:11.67:notificada");
        assertThat(jdbc.sql("SELECT count(*) FROM alertas_eventos WHERE tipo = 'presupuesto_desviacion' AND clave LIKE :c").param("c", "pd:" + anio + ":%")
                .query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT para::text FROM presupuesto_notificaciones WHERE vigencia_id = :v AND nivel = 'nacional'").param("v", vig)
                .query(String.class).single()).contains("digsa.desviacion@example.com");

        // Corrección de marzo de A (sigue en exceso): no se repite la alarma de marzo
        String correccion = "vigencia;fuerza;esm;modalidad;periodo;facturado\n" + anio + ";;" + a.sigla() + ";Dispensación;" + anio + "-03;170000000\n";
        assertThat(cargar(n, "/api/presupuesto/ejecucion/aplicar", correccion).estado()).isEqualTo(200);
        assertThat(desviaciones(vig)).hasSize(3);
        assertThat(n.get("/api/presupuesto/notificaciones?tipo=desviacion&vigencia=" + vig).json().path("total").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("subejecución del ESM a junio (−30 %); dentro de la tolerancia no alarma; un ESM que no reportó el mes no cuenta")
    void subejecucionYTolerancia() {
        Navegador n = coordinador();
        int anio = ANIO.incrementAndGet();
        long vig = vigencia(n, anio, String.valueOf(a.id()), "1200000000", String.valueOf(b.id()), "1200000000");
        // A a junio: 600 proyectado; 420 ejecutado (−30 %) en un solo corte de junio
        Respuesta r = cargar(n, "/api/presupuesto/ejecucion/aplicar",
                "vigencia;fuerza;esm;modalidad;periodo;facturado\n" + anio + ";;" + a.sigla() + ";Dispensación;" + anio + "-06;420000000\n");
        assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        assertThat(desviaciones(vig)).as("B no reportó junio: la fuerza y el país solo suman a A").allMatch(x -> x.contains(":subejecucion:-30.00:"))
                .hasSize(3);
        // Dentro de la tolerancia (+8 %): sin alarma
        int otro = ANIO.incrementAndGet();
        long v2 = vigencia(n, otro, String.valueOf(a.id()), "1200000000");
        assertThat(cargar(n, "/api/presupuesto/ejecucion/aplicar",
                "vigencia;fuerza;esm;modalidad;periodo;facturado\n" + otro + ";;" + a.sigla() + ";Dispensación;" + otro + "-01;108000000\n").estado())
                .isEqualTo(200);
        assertThat(desviaciones(v2)).isEmpty();
    }

    @Test
    @DisplayName("proyección cargada: reemplaza la lineal, advierte si no suma el asignado y cambia la alarma")
    void proyeccionCargada() {
        Navegador n = coordinador();
        int anio = ANIO.incrementAndGet();
        long vig = vigencia(n, anio, String.valueOf(a.id()), "1200000000");
        String proy = "vigencia;fuerza;esm;modalidad;periodo;valor\n"
                + anio + ";;" + a.sigla() + ";Dispensación;" + anio + "-01;200000000\n"
                + anio + ";;" + a.sigla() + ";Dispensación;" + anio + "-02;200000000\n";
        Respuesta s = cargar(n, "/api/presupuesto/proyeccion/simular", proy);
        assertThat(s.estado()).as(s.texto()).isEqualTo(200);
        assertThat(s.json().path("advertencias").get(0).asText()).contains("la proyección suma 400000000,00 y el asignado es 1200000000,00");
        assertThat(cargar(n, "/api/presupuesto/proyeccion/aplicar", proy).estado()).isEqualTo(200);
        // Con la lineal (100 al mes) 150 en enero sería +50 %; con la cargada (200) es −25 %: subejecución
        assertThat(cargar(n, "/api/presupuesto/ejecucion/aplicar",
                "vigencia;fuerza;esm;modalidad;periodo;facturado\n" + anio + ";;" + a.sigla() + ";Dispensación;" + anio + "-01;150000000\n").estado())
                .isEqualTo(200);
        assertThat(desviaciones(vig)).first().asString().contains(":subejecucion:-25.00:");
        String malo = "vigencia;fuerza;esm;modalidad;periodo;valor\n" + anio + ";;" + a.sigla() + ";Dispensación;" + (anio + 1) + "-01;5\n";
        assertThat(cargar(n, "/api/presupuesto/proyeccion/simular", malo).json().path("errores").get(0).path("motivo").asText())
                .contains("fuera de la vigencia");
    }

    @Test
    @DisplayName("concurrencia: dos cargas a la vez que alarman la misma fuerza y el país terminan bien y dejan una alarma por nivel")
    void concurrencia() throws Exception {
        Navegador n = coordinador();
        Navegador otro = coordinador();
        int anio = ANIO.incrementAndGet();
        long vig = vigencia(n, anio, String.valueOf(a.id()), "1200000000", String.valueOf(b.id()), "1200000000");
        java.util.concurrent.CountDownLatch salida = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(2);
        List<java.util.concurrent.Future<Integer>> f = new ArrayList<>();
        for (Object[] x : List.of(new Object[] {n, a}, new Object[] {otro, b})) {
            f.add(ex.submit(() -> {
                salida.await();
                return cargar((Navegador) x[0], "/api/presupuesto/ejecucion/aplicar", "vigencia;fuerza;esm;modalidad;periodo;facturado\n" + anio + ";;"
                        + ((P) x[1]).sigla() + ";Dispensación;" + anio + "-01;300000000\n").estado();
            }));
        }
        salida.countDown();
        for (java.util.concurrent.Future<Integer> x : f) {
            assertThat(x.get()).isEqualTo(200);
        }
        ex.shutdown();
        List<String> d = desviaciones(vig);
        assertThat(d.stream().filter(x -> x.startsWith("fuerza:EJC:")).count()).isEqualTo(1);
        assertThat(d.stream().filter(x -> x.startsWith("nacional:")).count()).isEqualTo(1);
        assertThat(d.stream().filter(x -> x.startsWith("esm:")).count()).isEqualTo(2);
        assertThat(d).allMatch(x -> x.endsWith(":notificada"));
    }

    @Test
    @DisplayName("proyección: la DIGSA no la carga (403) y la fuente debe ser DIGSA u OPL")
    void proyeccionPermisos() {
        Navegador d = navegador();
        d.ingresar(crearUsuario("digpro", Perfil.DIGSA), CLAVE);
        assertThat(cargar(d, "/api/presupuesto/proyeccion/simular", "vigencia;fuerza;esm;modalidad;periodo;valor\n").estado()).isEqualTo(403);
        Navegador n = coordinador();
        assertThat(n.multipart("/api/presupuesto/proyeccion/aplicar", Map.of("fuente", "otra"),
                Map.of("p.csv", "vigencia;fuerza;esm;modalidad;periodo;valor\n".getBytes(StandardCharsets.UTF_8)), "archivo").mensaje())
                .isEqualTo("La fuente debe ser la DIGSA o el OPL.");
    }
}
