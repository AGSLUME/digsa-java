package co.com.agsamericas.digsa.exportacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.plataforma.ArchivoIntegridadFallida;
import co.com.agsamericas.digsa.reglas.Exportacion;
import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;

/** Exportación completa (docs/especificaciones/exportacion-alertas): diferida, una sola foto, sin secretos, con soportes y huella. */
class ExportacionApiTest extends PruebaIntegracion {

    @Autowired
    private GeneradorExportacion generador;
    @Autowired
    private ServicioExportacion servicio;
    @Autowired
    private Almacen almacen;
    @Autowired
    private ApplicationEventMulticaster multicaster;

    @AfterEach
    void sinGancho() {
        generador.trasTabla = t -> { };
    }

    // ---------- Ayudantes ----------

    record Visita(long id, String numero) {
    }

    /** Visita mínima en un punto nuevo de la fuerza dada. */
    Visita visita(String fuerza, String alcance) {
        long u = idDe(crearUsuario("aud", Perfil.AUDITOR));
        long punto = jdbc.sql("""
                INSERT INTO puntos (nombre, unidad, fuerza, modalidad, municipio, creado_por)
                VALUES (:n, 'ESM', :f, 'Dispensación', '05001', 'prueba') RETURNING id""")
                .param("n", unico("Punto exportacion ")).param("f", fuerza).query(Long.class).single();
        String numero = "VS-20990101-" + System.nanoTime(); // formato VS-AAAAMMDD-NNNN (ck_visitas_numero), año fuera del rango real
        long id = jdbc.sql("""
                INSERT INTO visitas (numero, tipo, punto_id, fecha, auditor_id, alcance, creado_por)
                VALUES (:num, 'anual', :p, current_date, :u, :alc, :u) RETURNING id""")
                .param("num", numero).param("p", punto).param("u", u).param("alc", alcance).query(Long.class).single();
        return new Visita(id, numero);
    }

    /** Registra un archivo de la visita; si {@code contenido} no es null, lo guarda en el almacén. */
    long archivo(long visitaId, String nombre, byte[] contenido, String sha256) throws IOException {
        String ruta = "pruebas/exportacion/" + unico("f") + ".png";
        if (contenido != null) {
            almacen.guardar(ruta, contenido);
        }
        return jdbc.sql("""
                INSERT INTO archivos (visita_id, clave, nombre_original, ruta, tipo, tamano, sha256)
                VALUES (:v, 'visita', :n, :r, 'image/png', 10, :s) RETURNING id""")
                .param("v", visitaId).param("n", nombre).param("r", ruta).param("s", sha256).query(Long.class).single();
    }

    Navegador administrador(String usuario) {
        Navegador n = navegador();
        assertThat(n.ingresar(usuario, CLAVE).estado()).isEqualTo(200);
        return n;
    }

    /** Pide la exportación y espera a que termine (lista o fallida). */
    JsonNode exportar(Navegador n) throws InterruptedException {
        Respuesta r = n.post("/api/exportacion", null);
        assertThat(r.estado()).as(r.texto()).isEqualTo(202);
        long id = r.json().path("id").asLong();
        long hasta = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < hasta) {
            for (JsonNode e : n.get("/api/exportacion").json()) {
                if (e.path("id").asLong() == id && Set.of("lista", "fallida").contains(e.path("estado").asText())) {
                    return e;
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("La exportación " + id + " no terminó");
    }

    static Map<String, byte[]> abrir(byte[] zip) throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                m.put(e.getName(), in.readAllBytes());
            }
        }
        return m;
    }

    static String texto(Map<String, byte[]> zip, String entrada) {
        assertThat(zip).as("el ZIP trae " + entrada).containsKey(entrada);
        return new String(zip.get(entrada), StandardCharsets.UTF_8);
    }

    /** Tablas de la aplicación en la base: el esquema public sin particiones ni las técnicas. */
    Set<String> tablasDeLaBase() {
        Set<String> t = new HashSet<>(jdbc.sql("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND NOT c.relispartition""").query(String.class).list());
        t.removeAll(Exportacion.TECNICAS);
        return t;
    }

    // ---------- Pruebas ----------

    @Test
    @DisplayName("invariante: la lista de tablas es la de information_schema, ni una más ni una menos")
    void tablasContraElEsquema() {
        Set<String> base = new HashSet<>(jdbc.sql("""
                SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'""")
                .query(String.class).list());
        base.removeAll(Exportacion.TECNICAS);
        base.removeIf(t -> t.startsWith("log_transacciones_")); // particiones mensuales de la bitácora: salen en log_transacciones.csv
        assertThat(base).isEqualTo(tablasDeLaBase());
        Set<String> sobran = new HashSet<>(base);
        Exportacion.TABLAS.forEach(sobran::remove);
        assertThat(sobran).as("tablas de la base que no están en Exportacion.TABLAS").isEmpty();
        Set<String> faltan = new HashSet<>(Exportacion.TABLAS);
        faltan.removeAll(base);
        assertThat(faltan).as("tablas de Exportacion.TABLAS que no existen en la base").isEmpty();

        // Ninguna columna exportada es binaria ni huella, token o contraseña
        List<String> sospechosas = new ArrayList<>();
        jdbc.sql("""
                SELECT table_name, column_name, udt_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ANY(:t)
                ORDER BY table_name, ordinal_position""").param("t", Exportacion.TABLAS.toArray(String[]::new)).query(rs -> {
            String tabla = rs.getString(1);
            String col = rs.getString(2);
            boolean secreta = "bytea".equals(rs.getString(3)) || col.endsWith("_hash") || col.endsWith("_huella") || col.startsWith("token")
                    || col.contains("secret") || col.contains("contrasena");
            if (secreta && Exportacion.columnas(tabla, List.of(col)).contains(col)) {
                sospechosas.add(tabla + "." + col);
            }
        });
        assertThat(sospechosas).as("columnas secretas o binarias que saldrían en la exportación").isEmpty();
    }

    @Test
    @DisplayName("solo el administrador: el coordinador recibe 403 al pedir, consultar y descargar")
    void soloAdministrador() {
        Navegador c = navegador();
        assertThat(c.ingresar(crearUsuario("coordexp", Perfil.COORDINADOR), CLAVE).estado()).isEqualTo(200);
        assertThat(c.post("/api/exportacion", null).estado()).isEqualTo(403);
        assertThat(c.get("/api/exportacion").estado()).isEqualTo(403);
        assertThat(c.get("/api/exportacion/1/descarga").estado()).isEqualTo(403);
    }

    @Test
    @DisplayName("solicitar → lista → descargar: un CSV por tabla, LEEME, soportes con huella, sin secretos ni fórmulas, coma decimal")
    void flujoCompleto() throws Exception {
        String adm = crearUsuario("admexp", Perfil.ADMINISTRADOR);
        long admId = idDe(adm);
        String token = Huellas.secreto(32);
        String codigo = Huellas.secreto(32);
        jdbc.sql("UPDATE usuarios SET token_hash = :t, sesion_huella = :t WHERE id = :id").param("t", token).param("id", admId).update();
        jdbc.sql("INSERT INTO mfa_codigos (usuario_id, codigo_hash, creado, vence) VALUES (:u, :c, now(), now())").param("u", admId).param("c", codigo).update();
        String claveHash = jdbc.sql("SELECT clave_hash FROM usuarios WHERE id = :id").param("id", admId).query(String.class).single();

        Visita v = visita("EJC", "=SUMA(A1) alcance de prueba");
        byte[] foto = "contenido de la foto de prueba".getBytes(StandardCharsets.UTF_8);
        long buena = archivo(v.id(), "Foto bodega.png", foto, Huellas.sha256(foto));
        long alterada = archivo(v.id(), "alterada.png", foto, "0".repeat(64));
        long falta = archivo(v.id(), "falta.png", null, "1".repeat(64));

        List<Object> alertas = new CopyOnWriteArrayList<>();
        ApplicationListener<PayloadApplicationEvent<?>> oyente = e -> {
            if (e.getPayload() instanceof ArchivoIntegridadFallida a) {
                alertas.add(a);
            }
        };
        multicaster.addApplicationListener(oyente);
        try {
            Navegador n = administrador(adm);
            JsonNode e = exportar(n);
            assertThat(e.path("estado").asText()).as(e.toString()).isEqualTo("lista");
            assertThat(e.path("sha256").asText()).hasSize(64);
            assertThat(e.path("huellasDistintas").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(e.path("noEncontrados").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(e.path("vence").isNull()).isFalse();

            Respuesta d = n.get("/api/exportacion/" + e.path("id").asLong() + "/descarga");
            assertThat(d.estado()).isEqualTo(200);
            assertThat(d.cabecera("Content-Type")).startsWith("application/zip");
            assertThat(d.cabecera("Content-Disposition")).matches("attachment; filename=\"digsa_exportacion_\\d{8}_\\d{4}\\.zip\"");
            assertThat(d.cabecera("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(d.cabecera("Cache-Control")).contains("no-store");
            assertThat(Huellas.sha256(d.cuerpo())).isEqualTo(e.path("sha256").asText());
            Map<String, byte[]> zip = abrir(d.cuerpo());

            // Un CSV por tabla de la lista que existe, con BOM y la cabecera en la primera fila
            Set<String> base = tablasDeLaBase();
            for (String t : Exportacion.TABLAS) {
                if (base.contains(t)) {
                    assertThat(texto(zip, t + ".csv")).startsWith("﻿");
                } else {
                    assertThat(zip).doesNotContainKey(t + ".csv");
                }
            }
            String usuarios = texto(zip, "usuarios.csv");
            String cabecera = usuarios.substring(1, usuarios.indexOf("\r\n"));
            assertThat(List.of(cabecera.split(";"))).contains("usuario", "correo").doesNotContain("clave_hash", "token_hash", "token_vence", "sesion_huella");
            assertThat(texto(zip, "opl_accesos.csv")).contains("visita_id").doesNotContain("token_hash");
            assertThat(texto(zip, "visitas_firmas.csv")).doesNotContain("imagen");
            for (Map.Entry<String, byte[]> x : zip.entrySet()) {
                String s = new String(x.getValue(), StandardCharsets.UTF_8);
                assertThat(s).as("sin secretos en " + x.getKey()).doesNotContain(token).doesNotContain(codigo).doesNotContain(claveHash);
            }
            // Coma decimal y números con signo sin apóstrofo; texto sin fórmulas; la visita recién creada
            assertThat(texto(zip, "par_municipios.csv")).contains(";-75,581775").doesNotContain("-75.581775").doesNotContain("'-75");
            String visitas = texto(zip, "visitas.csv");
            assertThat(visitas).contains(v.numero()).contains(";'=SUMA(A1) alcance de prueba;").doesNotContain(";=SUMA");
            assertThat(texto(zip, "log_transacciones.csv")).contains("operacion");

            // LEEME y soportes con índice y huella
            String leeme = texto(zip, "LEEME.txt");
            assertThat(leeme).contains("Tablas y filas").contains("(" + adm + ")").contains("visitas ").contains("clave_hash").contains("Ley 1581");
            assertThat(zip).containsKey("soportes/");
            String carpeta = "soportes/" + v.numero() + "/evidencias/";
            assertThat(zip.get(carpeta + buena + "_Foto_bodega.png")).isEqualTo(foto);
            assertThat(zip).containsKey(carpeta + alterada + "_alterada.png").doesNotContainKey(carpeta + falta + "_falta.png");
            String indice = texto(zip, "soportes/indice.csv");
            assertThat(indice).startsWith("﻿visita;punto;carpeta;tabla;id;");
            assertThat(fila(indice, ";" + buena + ";")).contains(";coincide;").contains(Huellas.sha256(foto));
            assertThat(fila(indice, ";" + alterada + ";")).contains(";huella distinta;");
            assertThat(fila(indice, ";" + falta + ";")).contains(";no encontrado;");
            assertThat(alertas).as("la huella distinta y el faltante disparan la alerta de integridad").isNotEmpty();

            assertThat(bitacoraDe(admId)).anyMatch(b -> b.startsWith("consulta|exportacion_completa|") && b.matches(".*\\d+ tablas · \\d+ filas · \\d+ soportes.*"));
            assertThat(bitacoraDe(admId)).anyMatch(b -> b.startsWith("insercion|exportacion_solicitada|"));

            // Vence: la limpieza la marca y borra el ZIP (datos personales)
            String ruta = jdbc.sql("SELECT ruta FROM exportaciones WHERE id = :id").param("id", e.path("id").asLong()).query(String.class).single();
            reloj.adelantar(Duration.ofHours(25));
            assertThat(servicio.limpiar()).isGreaterThanOrEqualTo(1);
            assertThat(administrador(adm).get("/api/exportacion/" + e.path("id").asLong() + "/descarga").estado()).isEqualTo(410); // la sesión anterior ya expiró
            assertThatThrownBy(() -> almacen.leer(ruta).close()).isInstanceOf(IOException.class);
        } finally {
            multicaster.removeApplicationListener(oyente);
        }
    }

    private static String fila(String csv, String contiene) {
        return csv.lines().filter(l -> l.contains(contiene)).findFirst().orElseThrow(() -> new AssertionError("sin fila con " + contiene));
    }

    @Test
    @DisplayName("una sola foto: lo que se escribe mientras se genera no aparece en el ZIP")
    void consistencia() throws Exception {
        String adm = crearUsuario("admfoto", Perfil.ADMINISTRADOR);
        Visita antes = visita("EJC", "Visita anterior a la exportación");
        List<Visita> durante = new CopyOnWriteArrayList<>();
        String detalle = unico("evento durante la exportación ");
        generador.trasTabla = t -> {
            if ("usuarios".equals(t)) {
                Visita x = visita("EJC", "Creada mientras se exporta");
                jdbc.sql("INSERT INTO visitas_historial (visita_id, evento, detalle) VALUES (:v, 'prueba', :d)").param("v", x.id()).param("d", detalle).update();
                durante.add(x);
            }
        };
        Navegador n = administrador(adm);
        JsonNode e = exportar(n);
        assertThat(e.path("estado").asText()).isEqualTo("lista");
        assertThat(durante).hasSize(1);
        Map<String, byte[]> zip = abrir(n.get("/api/exportacion/" + e.path("id").asLong() + "/descarga").cuerpo());
        assertThat(texto(zip, "visitas.csv")).contains(antes.numero()).doesNotContain(durante.getFirst().numero());
        assertThat(texto(zip, "visitas_historial.csv")).doesNotContain(detalle);
    }

    @Test
    @DisplayName("falla: queda fallida con el mensaje genérico, sin ZIP a medias en el almacén y sin descarga")
    void falla() throws Exception {
        String adm = crearUsuario("admfalla", Perfil.ADMINISTRADOR);
        generador.trasTabla = t -> {
            if ("visitas".equals(t)) {
                throw new IllegalStateException("disco lleno (simulado)");
            }
        };
        Navegador n = administrador(adm);
        JsonNode e = exportar(n);
        assertThat(e.path("estado").asText()).isEqualTo("fallida");
        assertThat(e.path("error").asText()).isEqualTo(Exportacion.MENSAJE_FALLA);
        String ruta = jdbc.sql("SELECT ruta FROM exportaciones WHERE id = :id").param("id", e.path("id").asLong()).query(String.class).single();
        assertThatThrownBy(() -> almacen.leer(ruta).close()).isInstanceOf(IOException.class);
        assertThat(n.get("/api/exportacion/" + e.path("id").asLong() + "/descarga").estado()).isEqualTo(409);
        assertThat(bitacoraDe(idDe(adm))).anyMatch(b -> b.contains("|exportacion_fallida|")).noneMatch(b -> b.contains("|exportacion_completa|"));
    }
}
