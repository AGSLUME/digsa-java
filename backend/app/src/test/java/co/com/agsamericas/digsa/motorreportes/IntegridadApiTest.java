package co.com.agsamericas.digsa.motorreportes;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Integridad de los reportes (Anexo 5, 2.10; RESPUESTAS l.555): código y huella impresos, determinismo y verificación. */
class IntegridadApiTest extends PruebaIntegracion {

    private static final Pattern CODIGO = Pattern.compile("Código de verificación;(RPT-\\d{8}-[A-Z0-9]{8})");
    private static final Pattern HUELLA = Pattern.compile("Huella de los datos \\(SHA-256\\);([0-9a-f]{64})");

    private record Escenario(Navegador coord, String coordinador, long punto, String lugar, String filtro) {
    }

    private Escenario escenario() {
        long lider = idDe(crearUsuario("lider", Perfil.AUDITOR));
        String lugar = unico("Punto integridad ");
        long punto = jdbc.sql("""
                INSERT INTO puntos (nombre, unidad, fuerza, modalidad, municipio, creado_por) VALUES (:n, 'ESM', 'EJC', 'Dispensación', '05001', 'prueba')
                RETURNING id""").param("n", lugar).query(Long.class).single();
        visita(punto, lider, LocalDate.of(2026, 7, 7));
        String coordinador = crearUsuario("coord", Perfil.COORDINADOR);
        Navegador coord = navegador();
        coord.ingresar(coordinador, CLAVE);
        return new Escenario(coord, coordinador, punto, lugar, "desde=2026-07-01&hasta=2026-07-31&punto_id=" + punto);
    }

    private void visita(long punto, long lider, LocalDate fecha) {
        long id = jdbc.sql("""
                INSERT INTO visitas (numero, tipo, punto_id, fecha, auditor_id, alcance, estado, creado_por)
                VALUES (:n, 'anual', :p, :f, :a, 'Prueba de integridad.', 'programada', :a) RETURNING id""")
                .param("n", "VS-" + fecha.toString().replace("-", "") + "-" + (System.nanoTime() % 100_000_000)).param("p", punto).param("f", fecha)
                .param("a", lider).query(Long.class).single();
        jdbc.sql("INSERT INTO visitas_componentes (visita_id, componente) VALUES (:v, 'C08')").param("v", id).update();
    }

    private static String grupo(Pattern p, String texto) {
        Matcher m = p.matcher(texto);
        assertThat(m.find()).as("no está en el archivo: " + p).isTrue();
        return m.group(1);
    }

    private static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    @Test
    @DisplayName("el archivo trae código y huella de los datos; el registro guarda la huella del archivo; CSV y PDF comparten la de los datos")
    void codigoYHuellas() throws Exception {
        Escenario x = escenario();
        Respuesta csv = x.coord().get("/api/reportes/detalle/exportar?formato=csv&" + x.filtro());
        assertThat(csv.estado()).as(csv.texto()).isEqualTo(200);
        String texto = new String(csv.cuerpo(), StandardCharsets.UTF_8);
        String codigo = grupo(CODIGO, texto);
        String datos = grupo(HUELLA, texto);
        assertThat(csv.cabecera("X-Codigo-Verificacion")).isEqualTo(codigo);
        assertThat(texto).contains("Establecimiento;" + x.lugar(), "Registros;1", "Fuente;Suite DIGSA", "Página;1 de 1 (archivo continuo)");
        Map<String, Object> reg = jdbc.sql("SELECT estado, formato, huella_datos, huella_archivo, tamano, filas, vence, ms_total FROM reportes_generados WHERE codigo = :c")
                .param("c", codigo).query().singleRow();
        assertThat(reg.get("estado")).isEqualTo("lista");
        assertThat(reg.get("huella_datos")).isEqualTo(datos);
        assertThat(reg.get("huella_archivo")).isEqualTo(sha256(csv.cuerpo()));
        assertThat(((Number) reg.get("tamano")).longValue()).isEqualTo(csv.cuerpo().length);
        assertThat(((Number) reg.get("filas")).longValue()).isEqualTo(1);
        assertThat(reg.get("vence")).isNotNull();

        Respuesta pdf = x.coord().get("/api/reportes/detalle/exportar?formato=pdf&" + x.filtro());
        String codigoPdf = pdf.cabecera("X-Codigo-Verificacion");
        assertThat(codigoPdf).isNotEqualTo(codigo);
        Map<String, Object> regPdf = jdbc.sql("SELECT huella_datos, huella_archivo FROM reportes_generados WHERE codigo = :c").param("c", codigoPdf).query().singleRow();
        assertThat(regPdf.get("huella_datos")).as("mismos datos, distinto formato").isEqualTo(datos);
        assertThat(regPdf.get("huella_archivo")).isNotEqualTo(reg.get("huella_archivo"));

        JsonNode vista = x.coord().get("/api/reportes/detalle?" + x.filtro()).json();
        assertThat(vista.path("codigo").asText()).matches("RPT-\\d{8}-[A-Z0-9]{8}");
        assertThat(vista.path("huella").asText()).as("la pantalla con todas las filas tiene la misma huella").isEqualTo(datos);
    }

    @Test
    @DisplayName("2.10: dos coordinadores con los mismos filtros dan la misma huella; la DIGSA, que no ve el auditor, es otra variante")
    void determinismo() throws Exception {
        Escenario x = escenario();
        String h1 = grupo(HUELLA, new String(x.coord().get("/api/reportes/detalle/exportar?formato=csv&" + x.filtro()).cuerpo(), StandardCharsets.UTF_8));
        Navegador otro = navegador();
        otro.ingresar(crearUsuario("coord", Perfil.COORDINADOR), CLAVE);
        String h2 = grupo(HUELLA, new String(otro.get("/api/reportes/detalle/exportar?formato=csv&" + x.filtro()).cuerpo(), StandardCharsets.UTF_8));
        assertThat(h2).isEqualTo(h1);
        // El mismo reporte con los filtros en otro orden y repetidos: misma huella
        String h3 = grupo(HUELLA, new String(otro.get("/api/reportes/detalle/exportar?formato=csv&punto_id=" + x.punto() + "&hasta=2026-07-31&punto_id="
                + x.punto() + "&desde=2026-07-01").cuerpo(), StandardCharsets.UTF_8));
        assertThat(h3).isEqualTo(h1);
        Navegador digsa = navegador();
        digsa.ingresar(crearUsuario("digsa", Perfil.DIGSA), CLAVE);
        String h4 = grupo(HUELLA, new String(digsa.get("/api/reportes/detalle/exportar?formato=csv&" + x.filtro()).cuerpo(), StandardCharsets.UTF_8));
        assertThat(h4).isNotEqualTo(h1);
    }

    @Test
    @DisplayName("verificar: el archivo íntegro se reconoce y uno alterado no; el código muestra el registro y regenerar compara los datos")
    void verificar() throws Exception {
        Escenario x = escenario();
        Respuesta csv = x.coord().get("/api/reportes/detalle/exportar?formato=csv&" + x.filtro());
        String codigo = csv.cabecera("X-Codigo-Verificacion");
        JsonNode ok = x.coord().multipart("/api/reportes/verificar", Map.of(), Map.of("reporte.csv", csv.cuerpo()), "archivo").json();
        assertThat(ok.path("resultado").asText()).isEqualTo("integro");
        assertThat(ok.path("reporte").path("codigo").asText()).isEqualTo(codigo);
        byte[] alterado = csv.cuerpo().clone();
        alterado[alterado.length - 3] ^= 1;
        JsonNode malo = x.coord().multipart("/api/reportes/verificar", Map.of(), Map.of("reporte.csv", alterado), "archivo").json();
        assertThat(malo.path("resultado").asText()).isEqualTo("alterado");
        assertThat(malo.path("mensaje").asText()).contains("Alterado o no generado por la Suite DIGSA");

        // Otro usuario consulta el código: ve el registro con el usuario enmascarado
        Navegador otro = navegador();
        otro.ingresar(crearUsuario("coord", Perfil.COORDINADOR), CLAVE);
        JsonNode porCodigo = otro.get("/api/reportes/verificar/" + codigo.toLowerCase()).json();
        assertThat(porCodigo.path("reporte").path("titulo").asText()).isEqualTo("Detalle de visitas");
        // A otro usuario: iniciales y sin los valores de los filtros (Ley 1581)
        assertThat(porCodigo.path("reporte").path("usuario").asText()).matches("[A-ZÁÉÍÓÚÑ]\\.( [A-ZÁÉÍÓÚÑ]\\.)*").doesNotContain(x.coordinador());
        assertThat(porCodigo.path("reporte").path("parametros").has("filtros")).isFalse();
        assertThat(porCodigo.path("reporte").path("parametros").path("desde").asText()).isEqualTo("2026-07-01");
        JsonNode propio = x.coord().get("/api/reportes/verificar/" + codigo).json().path("reporte");
        assertThat(propio.path("usuario").asText()).contains(x.coordinador());
        assertThat(propio.path("parametros").path("filtros").path("punto_id").get(0).asText()).isEqualTo(String.valueOf(x.punto()));
        assertThat(otro.get("/api/reportes/verificar/RPT-20990101-AAAAAAAA").estado()).isEqualTo(404);
        assertThat(otro.get("/api/reportes/verificar/no-es-un-codigo").estado()).isEqualTo(422);

        JsonNode igual = otro.post("/api/reportes/verificar/" + codigo + "/regenerar", Map.of()).json();
        assertThat(igual.path("resultado").asText()).isEqualTo("sin_cambios");
        visita(x.punto(), idDe(crearUsuario("lider", Perfil.AUDITOR)), LocalDate.of(2026, 7, 9));
        JsonNode cambio = otro.post("/api/reportes/verificar/" + codigo + "/regenerar", Map.of()).json();
        assertThat(cambio.path("resultado").asText()).isEqualTo("cambiaron");
        assertThat(cambio.path("filasHoy").asInt()).isEqualTo(2);

        // La DIGSA no ve las mismas columnas (variante): no puede regenerar ese reporte, pero sí verificar el archivo
        Navegador digsa = navegador();
        digsa.ingresar(crearUsuario("digsa", Perfil.DIGSA), CLAVE);
        assertThat(digsa.post("/api/reportes/verificar/" + codigo + "/regenerar", Map.of()).mensaje()).contains("otras columnas");
        assertThat(digsa.multipart("/api/reportes/verificar", Map.of(), Map.of("r.csv", csv.cuerpo()), "archivo").json().path("resultado").asText())
                .isEqualTo("integro");
        // Sanidad (otro alcance)
        String sanidad = crearUsuario("sanidad", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'EJC' WHERE usuario = :u").param("u", sanidad).update();
        Navegador s = navegador();
        s.ingresar(sanidad, CLAVE);
        assertThat(s.post("/api/reportes/verificar/" + codigo + "/regenerar", Map.of()).mensaje()).contains("alcance");
        assertThat(bitacoraDe(idDe(x.coordinador())).stream().anyMatch(f -> f.contains("|reporte_verificado|"))).isTrue();
    }

    @Test
    @DisplayName("PDF descargado: cifrado y sin permiso de modificar")
    void pdfProtegido() throws Exception {
        Escenario x = escenario();
        Respuesta pdf = x.coord().get("/api/reportes/lugar/exportar?formato=pdf&" + x.filtro());
        assertThat(pdf.estado()).isEqualTo(200);
        com.lowagie.text.pdf.PdfReader r = new com.lowagie.text.pdf.PdfReader(pdf.cuerpo());
        try {
            assertThat(r.isEncrypted()).isTrue();
            assertThat(r.getPermissions() & com.lowagie.text.pdf.PdfWriter.ALLOW_MODIFY_CONTENTS).isZero();
            assertThat(new com.lowagie.text.pdf.parser.PdfTextExtractor(r).getTextFromPage(1)).contains("Código de verificación", "Página 1 de 1",
                    pdf.cabecera("X-Codigo-Verificacion"));
        } finally {
            r.close();
        }
    }

    @Test
    @DisplayName("2.9 en pantalla: generado en hora de Bogotá, establecimiento, filtros sin repetirlo, fuente y registros")
    void encabezadoEnPantalla() {
        Escenario x = escenario();
        JsonNode r = x.coord().get("/api/reportes/detalle?" + x.filtro() + "&fuerza=EJC").json();
        assertThat(r.path("generado").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")
                .startsWith(LocalDate.now(reloj.withZone(co.com.agsamericas.digsa.reglas.Fechas.BOGOTA)).toString());
        assertThat(r.path("periodo").asText()).isEqualTo("2026-07-01 a 2026-07-31");
        assertThat(r.path("establecimiento").asText()).isEqualTo(x.lugar());
        assertThat(r.path("fuente").asText()).startsWith("Suite DIGSA");
        assertThat(r.path("registros").asInt()).isEqualTo(r.path("filas").size()).isEqualTo(1);
        assertThat(r.path("filtros").has("Fuerza")).isTrue();
        assertThat(x.coord().get("/api/reportes/detalle?desde=2026-07-01&hasta=2026-07-31").json().path("establecimiento").asText()).isEqualTo("Todos");
    }

    @Test
    @DisplayName("nada se trunca: «Registros» del encabezado = filas del archivo en los 4 formatos; el Excel trae código y huella")
    void registrosEnLosCuatroFormatos() throws Exception {
        Escenario x = escenario();
        long lider = idDe(crearUsuario("lider", Perfil.AUDITOR));
        visita(x.punto(), lider, LocalDate.of(2026, 7, 8));
        visita(x.punto(), lider, LocalDate.of(2026, 7, 9));
        String csv = new String(x.coord().get("/api/reportes/detalle/exportar?formato=csv&" + x.filtro()).cuerpo(), StandardCharsets.UTF_8);
        assertThat(csv).contains("Registros;3\r\n");
        assertThat(csv.substring(csv.indexOf("Número;")).split("\r\n")).hasSize(4);
        String txt = new String(x.coord().get("/api/reportes/detalle/exportar?formato=txt&" + x.filtro()).cuerpo(), StandardCharsets.UTF_8);
        assertThat(txt).contains("Registros: 3\r\n");
        assertThat(txt.substring(txt.indexOf("-+-")).split("\r\n")).hasSize(4);
        Respuesta xlsx = x.coord().get("/api/reportes/detalle/exportar?formato=xlsx&" + x.filtro());
        try (var libro = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx.cuerpo()))) {
            var hoja = libro.getSheetAt(0);
            Map<String, String> enc = new java.util.HashMap<>();
            int titulos = -1;
            for (int i = 0; i <= hoja.getLastRowNum(); i++) {
                var fila = hoja.getRow(i);
                if (fila == null || fila.getCell(0) == null) {
                    continue;
                }
                String k = fila.getCell(0).getStringCellValue();
                if ("Número".equals(k)) {
                    titulos = i;
                    break;
                }
                enc.put(k, fila.getCell(1) == null ? "" : fila.getCell(1).getStringCellValue());
            }
            String codigo = xlsx.cabecera("X-Codigo-Verificacion");
            assertThat(enc.get("Código de verificación")).isEqualTo(codigo);
            assertThat(enc.get("Huella de los datos (SHA-256)")).isEqualTo(
                    jdbc.sql("SELECT huella_datos FROM reportes_generados WHERE codigo = :c").param("c", codigo).query(String.class).single());
            assertThat(enc.get("Registros")).isEqualTo("3");
            assertThat(hoja.getLastRowNum() - titulos).isEqualTo(3);
            assertThat(hoja.getRepeatingRows()).isNotNull();
        }
        Respuesta pdf = x.coord().get("/api/reportes/detalle/exportar?formato=pdf&" + x.filtro());
        var r = new com.lowagie.text.pdf.PdfReader(pdf.cuerpo());
        try {
            String texto = new com.lowagie.text.pdf.parser.PdfTextExtractor(r).getTextFromPage(1);
            assertThat(texto).contains("Registros: 3");
            // En la columna angosta el número se parte en dos líneas: se compara sin espacios ni saltos
            String compacto = texto.replaceAll("\\s+", "");
            for (String numero : jdbc.sql("SELECT numero FROM visitas WHERE punto_id = :p").param("p", x.punto()).query(String.class).list()) {
                assertThat(compacto).contains(numero);
            }
        } finally {
            r.close();
        }
    }

    @Test
    @DisplayName("un PDF de más filas que su tope es 422 con la sugerencia; el registro queda «fallida» sin archivo y su código lo dice")
    void pdfSobreElTope() {
        Escenario x = escenario();
        long lider = idDe(crearUsuario("lider", Perfil.AUDITOR));
        // 5.001 visitas en un punto nuevo (el tope del PDF es 5.000)
        jdbc.sql("""
                INSERT INTO visitas (numero, tipo, punto_id, fecha, auditor_id, alcance, estado, creado_por)
                SELECT 'VS-20260715-' || lpad((90000000 + (:p % 1000000) * 10000 + g)::text, 8, '0'), 'anual', :p, DATE '2026-07-15', :a, 'Volumen.', 'programada', :a
                FROM generate_series(1, 5001) g""").param("p", x.punto()).param("a", lider).update();
        Respuesta pdf = x.coord().get("/api/reportes/detalle/exportar?formato=pdf&" + x.filtro());
        assertThat(pdf.estado()).isEqualTo(422);
        assertThat(pdf.mensaje()).contains("El PDF admite hasta 5000 filas", "Excel o CSV");
        Map<String, Object> reg = jdbc.sql("""
                SELECT codigo, estado, ruta, error FROM reportes_generados WHERE usuario_id = :u AND formato = 'pdf' ORDER BY id DESC LIMIT 1""")
                .param("u", idDe(x.coordinador())).query().singleRow();
        assertThat(reg.get("estado")).isEqualTo("fallida");
        assertThat(reg.get("ruta")).isNull();
        assertThat((String) reg.get("error")).contains("El PDF admite hasta 5000 filas");
        JsonNode v = x.coord().get("/api/reportes/verificar/" + reg.get("codigo")).json();
        assertThat(v.path("resultado").asText()).isEqualTo("fallida");
        assertThat(x.coord().post("/api/reportes/verificar/" + reg.get("codigo") + "/regenerar", Map.of()).estado()).isEqualTo(422);
        // El mismo reporte en CSV sí sale completo (5.002 filas)
        Respuesta csv = x.coord().get("/api/reportes/detalle/exportar?formato=csv&" + x.filtro());
        assertThat(csv.estado()).isEqualTo(200);
        assertThat(new String(csv.cuerpo(), StandardCharsets.UTF_8)).contains("Registros;5002");
    }

    @Test
    @DisplayName("verificar: un archivo vacío es 422 y más de 30 verificaciones por minuto de un usuario es 429")
    void topesDeVerificacion() {
        Navegador u = navegador();
        u.ingresar(crearUsuario("coord", Perfil.COORDINADOR), CLAVE);
        assertThat(u.multipart("/api/reportes/verificar", Map.of(), Map.of("vacio.csv", new byte[0]), "archivo").estado()).isEqualTo(422);
        int ultimo = 0;
        for (int i = 0; i < 31; i++) {
            ultimo = u.get("/api/reportes/verificar/RPT-20990101-AAAAAAAA").estado();
        }
        assertThat(ultimo).isEqualTo(429);
        // Otro usuario no se ve afectado
        Navegador otro = navegador();
        otro.ingresar(crearUsuario("coord", Perfil.COORDINADOR), CLAVE);
        assertThat(otro.get("/api/reportes/verificar/RPT-20990101-AAAAAAAA").estado()).isEqualTo(404);
    }
}
