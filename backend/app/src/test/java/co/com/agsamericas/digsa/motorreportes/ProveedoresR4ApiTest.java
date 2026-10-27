package co.com.agsamericas.digsa.motorreportes;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R4: envío a varios destinatarios, y los tableros y los logs en el motor de reportes (catálogo, formatos, encabezado y bitácora). */
class ProveedoresR4ApiTest extends PruebaIntegracion {

    private static final String PERIODO = "desde=2026-01-01&hasta=2026-12-31";

    private Navegador coordinador() {
        Navegador n = navegador();
        n.ingresar(crearUsuario("coordr4", Perfil.COORDINADOR), CLAVE);
        return n;
    }

    private static List<String> ids(JsonNode catalogo) {
        List<String> ids = new ArrayList<>();
        catalogo.path("grupos").forEach(g -> g.path("reportes").forEach(r -> ids.add(r.path("id").asText())));
        return ids;
    }

    @Test
    @DisplayName("correo: a varios destinatarios (máximo 15, de los dominios permitidos), con la bitácora enmascarada; uno no válido rechaza el envío")
    void envioVariosDestinatarios() {
        String usuario = crearUsuario("coordenv", Perfil.COORDINADOR);
        Navegador c = navegador();
        c.ingresar(usuario, CLAVE);
        String a = unico("comite") + "@example.com";
        String b = unico("digsa") + "@example.com";
        Respuesta r = c.post("/api/reportes/lugar/enviar", Map.of("para", a + ", " + b + "; " + a, "formato", "csv", "desde", "2026-01-01", "hasta", "2026-12-31"));
        assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        assertThat(r.json().path("mensaje").asText()).contains(a, b);
        var m = ultimoCorreoA(a);
        assertThat(m.para()).containsExactly(a, b);
        assertThat(m.adjuntos()).hasSize(1);
        assertThat(m.html()).contains(r.json().path("codigo").asText());
        assertThat(bitacoraDe(idDe(usuario))).anyMatch(f -> f.contains("|reporte_enviado|") && !f.contains(a) && !f.contains(b));
        // Como lista también
        assertThat(c.post("/api/reportes/lugar/enviar", Map.of("para", List.of(a, b), "formato", "csv", "desde", "2026-01-01", "hasta", "2026-12-31"))
                .estado()).isEqualTo(200);

        Respuesta ajeno = c.post("/api/reportes/lugar/enviar", Map.of("para", a + ", alguien@otro-dominio.com"));
        assertThat(ajeno.estado()).isEqualTo(422);
        assertThat(ajeno.mensaje()).contains("solo se envían", "alguien@otro-dominio.com");
        Respuesta malo = c.post("/api/reportes/lugar/enviar", Map.of("para", a + ", no-es-correo"));
        assertThat(malo.mensaje()).contains("no es un correo válido");
        List<String> dieciseis = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            dieciseis.add("persona" + i + "@example.com");
        }
        Respuesta muchos = c.post("/api/reportes/lugar/enviar", Map.of("para", String.join(",", dieciseis)));
        assertThat(muchos.estado()).isEqualTo(422);
        assertThat(muchos.mensaje()).contains("máximo 15");
        assertThat(c.post("/api/reportes/lugar/enviar", Map.of("para", List.of(1, 2))).estado()).isEqualTo(422);
        assertThat(c.post("/api/reportes/lugar/enviar", Map.of("para", " , ; ")).mensaje()).contains("Escriba un correo");
        Respuesta quince = c.post("/api/reportes/lugar/enviar", Map.of("para", String.join(",", dieciseis.subList(0, 15)), "formato", "csv", "desde",
                "2026-01-01", "hasta", "2026-12-31"));
        assertThat(quince.estado()).as(quince.texto()).isEqualTo(200);
        assertThat(ultimoCorreoA("persona14@example.com").para()).hasSize(15);
    }

    @Test
    @DisplayName("correo: si el servidor rechaza a algunos destinatarios, la respuesta dice a quién no llegó")
    void envioParcial() {
        Map<String, Object> todo = ControladorReportes.resultadoEnvio(List.of("a@example.com", "b@example.com"), List.of(), "RPT-1");
        assertThat(todo.get("mensaje")).isEqualTo("Reporte enviado a a@example.com, b@example.com.");
        Map<String, Object> parcial = ControladorReportes.resultadoEnvio(List.of("a@example.com", "b@example.com"), List.of("b@example.com"), "RPT-1");
        assertThat((String) parcial.get("mensaje")).isEqualTo("Reporte enviado a a@example.com. NO llegó a b@example.com: el servidor de correo lo rechazó.");
        assertThat(parcial.get("rechazados")).isEqualTo(List.of("b@example.com"));
    }

    @Test
    @DisplayName("tableros en el motor: dashboard de visitas y de riesgo en el catálogo de la coordinación, en los cuatro formatos y con un solo valor por filtro")
    void tableros() {
        Navegador c = coordinador();
        List<String> ids = ids(c.get("/api/reportes/catalogo").json());
        assertThat(ids).contains("detalle", "lugar", "dashboard_visitas", "dashboard_riesgo").doesNotContain("logs_auditoria");
        JsonNode dash = c.get("/api/reportes/dashboard_visitas?" + PERIODO).json();
        assertThat(dash.path("columnas").toString()).contains("Tabla", "Concepto", "Valor", "Cumple");
        assertThat(dash.toString()).contains("Visitas en el periodo", "Línea base");
        // Mismas cifras que la pantalla del dashboard
        JsonNode pantalla = c.get("/api/dashboard?" + PERIODO).json();
        assertThat(dash.path("filas").get(0).get(2).asText()).isEqualTo(pantalla.path("indicadores").path("total").asText());
        assertThat(c.get("/api/reportes/dashboard_riesgo?fuerza=EJC&fuerza=ARC").mensaje()).contains("solo uno");
        JsonNode riesgo = c.get("/api/reportes/dashboard_riesgo").json();
        assertThat(riesgo.toString()).contains("Pesos y umbrales", "Peso de Incumplimiento", "Estado de los pesos");
        for (String f : List.of("xlsx", "csv", "pdf", "txt")) {
            Respuesta a = c.get("/api/reportes/dashboard_riesgo/exportar?formato=" + f);
            assertThat(a.estado()).as(f + ": " + a.texto()).isEqualTo(200);
            assertThat(a.cabecera("X-Codigo-Verificacion")).startsWith("RPT-");
        }
        String csv = new String(c.get("/api/reportes/dashboard_visitas/exportar?formato=csv&" + PERIODO).cuerpo(), StandardCharsets.UTF_8);
        assertThat(csv).contains("Dashboard de visitas", "Código de verificación", "Indicadores");
        assertThat(c.get("/api/reportes/dashboard_visitas?" + PERIODO + "&fuerza=EJC&fuerza=ARC").mensaje()).contains("solo uno");
        // Las rutas de siempre de los tableros siguen respondiendo
        assertThat(c.get("/api/dashboard?" + PERIODO).estado()).isEqualTo(200);
        assertThat(c.get("/api/riesgo/exportar?formato=csv").estado()).isEqualTo(200);
    }

    @Test
    @DisplayName("2.7: el Administrador ve p50, p95 y máximo por reporte y formato; la coordinación no ve los tiempos")
    void tiempos() {
        Navegador c = coordinador();
        assertThat(c.get("/api/reportes/catalogo").json().path("verTiempos").asBoolean()).isFalse();
        assertThat(c.get("/api/reportes/lugar?" + PERIODO).estado()).isEqualTo(200);
        assertThat(c.get("/api/reportes/lugar/exportar?formato=csv&" + PERIODO).estado()).isEqualTo(200);
        assertThat(c.get("/api/reportes/tiempos").estado()).isEqualTo(403);
        Navegador a = navegador();
        a.ingresar(crearUsuario("admtiempos", Perfil.ADMINISTRADOR), CLAVE);
        assertThat(a.get("/api/reportes/catalogo").json().path("verTiempos").asBoolean()).isTrue();
        JsonNode t = a.get("/api/reportes/tiempos?dias=7").json();
        assertThat(t.path("plazoVerMs").asLong()).isEqualTo(180_000);
        List<String> tipos = new ArrayList<>();
        JsonNode lugar = null;
        for (JsonNode x : t.path("tiempos")) {
            if ("lugar".equals(x.path("reporte").asText())) {
                tipos.add(x.path("tipo").asText());
                lugar = x;
            }
        }
        assertThat(tipos).as("los tiempos de «lugar»").contains("pantalla", "csv");

        // Valores conocidos en un reporte de prueba: 100 vistas de 1 a 100 ms, una exportación fallida de 700 s y una vista fuera del periodo
        String reporte = "tiempos_" + (System.nanoTime() % 1_000_000_000L);
        long u = idDe(crearUsuario("tiempos", Perfil.COORDINADOR));
        java.time.OffsetDateTime ahora = java.time.OffsetDateTime.now(reloj);
        java.time.LocalDate dia = ahora.atZoneSameInstant(co.com.agsamericas.digsa.reglas.Fechas.BOGOTA).toLocalDate();
        for (int ms = 1; ms <= 101; ms++) {
            jdbc.sql("""
                    INSERT INTO reportes_generados (codigo, reporte, version, titulo, usuario_id, usuario, alcance, parametros, columnas, estado, ms_total, creado)
                    VALUES (:c, :r, 1, 'Tiempos de prueba', :u, 'Persona', 'todo', '{}', '[]', 'vista', :ms, :creado)""")
                    .param("c", CodigoVerificacion.nuevo(dia)).param("r", reporte).param("u", u).param("ms", ms == 101 ? 999_999 : ms)
                    .param("creado", ms == 101 ? ahora.minusDays(40) : ahora).update();
        }
        jdbc.sql("""
                INSERT INTO reportes_generados (codigo, reporte, version, titulo, usuario_id, usuario, alcance, parametros, columnas, formato, estado, error,
                                                ms_total, creado, iniciado, terminado, vence)
                VALUES (:c, :r, 1, 'Tiempos de prueba', :u, 'Persona', 'todo', '{}', '[]', 'csv', 'fallida', 'Cortado.', 700000, :t, :t, :t, :t)""")
                .param("c", CodigoVerificacion.nuevo(dia)).param("r", reporte).param("u", u).param("t", ahora).update();
        JsonNode pantalla = null;
        JsonNode csv = null;
        for (JsonNode x : a.get("/api/reportes/tiempos?dias=30").json().path("tiempos")) {
            if (reporte.equals(x.path("reporte").asText())) {
                if ("pantalla".equals(x.path("tipo").asText())) {
                    pantalla = x;
                } else {
                    csv = x;
                }
            }
        }
        assertThat(pantalla).as("la fila en pantalla del reporte de prueba").isNotNull();
        assertThat(pantalla.path("generaciones").asLong()).as("sin la de hace 40 días").isEqualTo(100);
        assertThat(pantalla.path("p50").asLong()).isEqualTo(50);
        assertThat(pantalla.path("p95").asLong()).isEqualTo(95);
        assertThat(pantalla.path("maximo").asLong()).isEqualTo(100);
        assertThat(pantalla.path("lentas").asLong()).isZero();
        assertThat(csv).as("la fallida cuenta, con el tiempo que alcanzó a correr").isNotNull();
        assertThat(csv.path("fallidas").asLong()).isEqualTo(1);
        assertThat(csv.path("lentas").asLong()).isEqualTo(1);
        assertThat(csv.path("maximo").asLong()).isEqualTo(700_000);
        // El periodo se acota entre 1 y 366 días
        assertThat(a.get("/api/reportes/tiempos?dias=0").json().path("dias").asInt()).isEqualTo(1);
        assertThat(a.get("/api/reportes/tiempos?dias=9999").json().path("dias").asInt()).isEqualTo(366);
    }

    @Test
    @DisplayName("logs en el motor: solo el Administrador, en PDF y TXT con el encabezado completo, varios usuarios a la vez y «reporte_descargado» en la bitácora")
    void logs() {
        String admin = crearUsuario("admr4", Perfil.ADMINISTRADOR);
        Navegador a = navegador();
        a.ingresar(admin, CLAVE);
        assertThat(ids(a.get("/api/reportes/catalogo").json())).contains("logs_auditoria");
        assertThat(ids(coordinador().get("/api/reportes/catalogo").json())).doesNotContain("logs_auditoria");
        Navegador c = coordinador();
        assertThat(c.get("/api/reportes/logs_auditoria?" + PERIODO).estado()).isEqualTo(404);

        // La bitácora usa la hora de la base: el rango cubre hoy y los dos días anteriores
        java.time.LocalDate hoy = java.time.LocalDate.now(co.com.agsamericas.digsa.reglas.Fechas.BOGOTA);
        String rango = "desde=" + hoy.minusDays(2) + "&hasta=" + hoy;
        long yo = idDe(admin);
        JsonNode v = a.get("/api/reportes/logs_auditoria?" + rango + "&usuario_id=" + yo + "&usuario_id=" + idDe(crearUsuario("otro", Perfil.AUDITOR))).json();
        assertThat(v.path("columnas").toString()).contains("Fecha y hora", "Usuario", "IP", "Cambios");
        assertThat(v.path("filas").size()).isPositive();
        for (String f : List.of("pdf", "txt")) {
            Respuesta r = a.get("/api/reportes/logs_auditoria/exportar?formato=" + f + "&" + rango);
            assertThat(r.estado()).as(f + ": " + r.texto()).isEqualTo(200);
        }
        String txt = new String(a.get("/api/reportes/logs_auditoria/exportar?formato=txt&" + rango).cuerpo(), StandardCharsets.UTF_8);
        assertThat(txt).contains("Logs de auditoría", "Fuente: Bitácora de la Suite DIGSA", "Código de verificación: RPT-");
        String codigo = a.get("/api/reportes/logs_auditoria/exportar?formato=xlsx&" + rango).cabecera("X-Codigo-Verificacion");
        assertThat(bitacoraDe(yo)).anyMatch(f -> f.contains("|reporte_descargado|") && f.contains("Logs de auditoría") && f.contains(codigo)
                && f.contains("reporte_logs_auditoria_"));
        assertThat(a.get("/api/reportes/logs_auditoria/exportar?formato=csv&" + rango).estado()).isEqualTo(200);
        // Texto con más de 92 días: el mismo tope de la pantalla de logs
        assertThat(a.get("/api/reportes/logs_auditoria?desde=2026-01-01&hasta=2026-06-30&texto=clave").mensaje()).contains("92 días");
    }
}
