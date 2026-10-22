package co.com.agsamericas.digsa.hallazgos;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** ZIP de los soportes del OPL desde «Prestadores con hallazgos» (duda 22.12): DIGSA y Sanidad en su alcance; el ESM no (12.2). */
class SoportesOplApiTest extends PruebaIntegracion {

    @Autowired
    private Almacen almacen;

    record Visita(long id, long punto, String numero) {
    }

    private Visita visita(String fuerza) {
        long u = idDe(crearUsuario("audopl", Perfil.AUDITOR));
        long punto = jdbc.sql("""
                INSERT INTO puntos (nombre, unidad, fuerza, modalidad, municipio, creado_por)
                VALUES (:n, 'ESM', :f, 'Dispensación', '05001', 'prueba') RETURNING id""")
                .param("n", unico("Punto soportes ")).param("f", fuerza).query(Long.class).single();
        String numero = "VS-20980101-" + System.nanoTime(); // formato VS-AAAAMMDD-NNNN (ck_visitas_numero)
        long id = jdbc.sql("""
                INSERT INTO visitas (numero, tipo, punto_id, fecha, auditor_id, alcance, creado_por)
                VALUES (:num, 'anual', :p, current_date, :u, 'Seguimiento', :u) RETURNING id""")
                .param("num", numero).param("p", punto).param("u", u).query(Long.class).single();
        return new Visita(id, punto, numero);
    }

    private long soporte(long visitaId, String clave, String nombre, byte[] contenido, String sha) throws IOException {
        String ruta = "pruebas/opl/" + unico("s") + ".pdf";
        almacen.guardar(ruta, contenido);
        return jdbc.sql("""
                INSERT INTO opl_soportes (visita_id, clave, nombre_original, ruta, tipo, tamano, sha256, correo)
                VALUES (:v, :c, :n, :r, 'application/pdf', :t, :s, 'opl.soportes@example.com') RETURNING id""")
                .param("v", visitaId).param("c", clave).param("n", nombre).param("r", ruta).param("t", contenido.length).param("s", sha)
                .query(Long.class).single();
    }

    private Navegador entrar(String usuario) {
        Navegador n = navegador();
        assertThat(n.ingresar(usuario, CLAVE).estado()).isEqualTo(200);
        return n;
    }

    private static Map<String, byte[]> abrir(byte[] zip) throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                m.put(e.getName(), in.readAllBytes());
            }
        }
        return m;
    }

    @Test
    @DisplayName("Sanidad del EJC: 403 con una visita de la ARC, ZIP con opl/ e indice.csv con una del EJC; ESM y coordinador 403; DIGSA sí")
    void alcance() throws Exception {
        Visita ejc = visita("EJC");
        Visita arc = visita("ARC");
        byte[] pdf = "%PDF-1.4 soporte de prueba".getBytes(StandardCharsets.UTF_8);
        long bueno = soporte(ejc.id(), "it:HB-01", "Registro firmado.pdf", pdf, Huellas.sha256(pdf));
        long alterado = soporte(ejc.id(), "it:HB-02", "otro.pdf", pdf, "f".repeat(64));
        soporte(arc.id(), "it:HB-01", "de la armada.pdf", pdf, Huellas.sha256(pdf));

        String sanidad = crearUsuario("sanopl", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'EJC' WHERE usuario = :u").param("u", sanidad).update();
        Navegador s = entrar(sanidad);
        assertThat(s.get("/api/hallazgos/" + arc.id() + "/soportes-opl.zip").estado()).as("visita de otra fuerza").isEqualTo(403);
        assertThat(s.get("/api/hallazgos/99999999/soportes-opl.zip").estado()).isEqualTo(404);

        Respuesta r = s.get("/api/hallazgos/" + ejc.id() + "/soportes-opl.zip");
        assertThat(r.estado()).isEqualTo(200);
        assertThat(r.cabecera("Content-Type")).startsWith("application/zip");
        assertThat(r.cabecera("Content-Disposition")).isEqualTo("attachment; filename=\"soportes_opl_" + ejc.numero() + ".zip\"");
        assertThat(r.cabecera("Cache-Control")).contains("no-store");
        Map<String, byte[]> zip = abrir(r.cuerpo());
        assertThat(zip.get("opl/it_HB-01/" + bueno + "_Registro_firmado.pdf")).isEqualTo(pdf);
        assertThat(zip).containsKey("opl/it_HB-02/" + alterado + "_otro.pdf");
        String indice = new String(zip.get("indice.csv"), StandardCharsets.UTF_8);
        assertThat(indice).startsWith("﻿visita;").contains(ejc.numero()).contains(";coincide;").contains(";huella distinta;")
                .doesNotContain("de la armada");
        assertThat(bitacoraDe(idDe(sanidad))).anyMatch(b -> b.startsWith("consulta|soportes_opl_descargados|") && b.contains("2 soportes"));

        String esm = crearUsuario("esmopl", Perfil.ESM);
        jdbc.sql("UPDATE usuarios SET alcance_punto = :p WHERE usuario = :u").param("p", ejc.punto()).param("u", esm).update();
        assertThat(entrar(esm).get("/api/hallazgos/" + ejc.id() + "/soportes-opl.zip").estado()).as("el ESM no ve los soportes").isEqualTo(403);

        assertThat(entrar(crearUsuario("coordopl", Perfil.COORDINADOR)).get("/api/hallazgos/" + ejc.id() + "/soportes-opl.zip").estado()).isEqualTo(403);

        Respuesta digsa = entrar(crearUsuario("digsaopl", Perfil.DIGSA)).get("/api/hallazgos/" + arc.id() + "/soportes-opl.zip");
        assertThat(digsa.estado()).isEqualTo(200);
        assertThat(new String(abrir(digsa.cuerpo()).get("indice.csv"), StandardCharsets.UTF_8)).contains("de la armada");
    }
}
