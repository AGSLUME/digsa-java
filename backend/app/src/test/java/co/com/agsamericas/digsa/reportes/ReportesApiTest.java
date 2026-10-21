package co.com.agsamericas.digsa.reportes;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Anexo 5, prueba 13: tres reportes con encabezado y filtros, descarga en Excel, CSV, PDF y TXT, envío por correo y alcance por perfil. */
class ReportesApiTest extends PruebaIntegracion {

    private record Datos(Navegador coord, String lugar, String numero, String desde, String hasta) {
    }

    /** Una visita cerrada con resultados (1 NC en C08) en un punto propio del EJC. */
    private Datos datos() {
        String qf = crearUsuario("qf", Perfil.AUDITOR);
        jdbc.sql("INSERT INTO auditores (usuario_id, rol) VALUES (:id, 'quimico_farmaceutico')").param("id", idDe(qf)).update();
        String lugar = unico("Punto reportes ");
        long punto = jdbc.sql("""
                INSERT INTO puntos (nombre, unidad, fuerza, modalidad, municipio, creado_por) VALUES (:n, 'ESM', 'EJC', 'Dispensación', '05001', 'prueba')
                RETURNING id""").param("n", lugar).query(Long.class).single();
        Navegador coord = navegador();
        coord.ingresar(crearUsuario("coord", Perfil.COORDINADOR), CLAVE);
        LocalDate hoy = LocalDate.now(reloj.withZone(Fechas.BOGOTA));
        Map<String, Object> d = new HashMap<>();
        d.put("tipo", "anual");
        d.put("puntoId", punto);
        d.put("fecha", hoy.toString());
        d.put("liderId", idDe(qf));
        d.put("alcance", "Almacenamiento.");
        d.put("componentes", List.of("C08"));
        Respuesta r = coord.post("/api/agenda", d);
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        long id = r.json().path("id").asLong();
        jdbc.sql("UPDATE visitas SET estado = 'cerrada', checklist_version = 'v6' WHERE id = :id").param("id", id).update();
        List<Long> items = jdbc.sql("""
                SELECT i.id FROM chk_items i JOIN chk_componentes c ON c.id = i.componente_id WHERE c.version = 'v6' AND c.codigo = 'C08' ORDER BY i.orden LIMIT 3""")
                .query(Long.class).list();
        for (int k = 0; k < items.size(); k++) {
            jdbc.sql("INSERT INTO visitas_respuestas (visita_id, item_id, parte, calificacion, hallazgo, usuario_id) VALUES (:v, :i, 'C08', :c, :h, :u)")
                    .param("v", id).param("i", items.get(k)).param("c", k == 0 ? "NC" : "C").param("h", k == 0 ? "Hallazgo de prueba" : null)
                    .param("u", idDe(qf)).update();
        }
        jdbc.sql("INSERT INTO visitas_resumen (visita_id, c, nc, na, pendientes, hallazgos) VALUES (:v, 2, 1, 0, 0, 1)").param("v", id).update();
        return new Datos(coord, lugar, r.json().path("numero").asText(), hoy.minusDays(1).toString(), hoy.plusDays(1).toString());
    }

    @Test
    @DisplayName("prueba 13: detalle por periodo y lugar, consolidado por lugar y por proceso, con encabezado (título, fecha, usuario, filtros)")
    void tresReportes() {
        Datos x = datos();
        String filtro = "?desde=" + x.desde() + "&hasta=" + x.hasta();
        JsonNode det = x.coord().get("/api/reportes/detalle" + filtro).json();
        assertThat(det.path("titulo").asText()).isEqualTo("Detalle de visitas");
        assertThat(det.path("usuario").asText()).contains("coord");
        assertThat(det.path("filtros").path("Periodo").asText()).isEqualTo(x.desde() + " a " + x.hasta());
        assertThat(det.path("columnas").toString()).contains("Lugar", "Auditor líder", "% cumplimiento");
        assertThat(det.path("filas").toString()).contains(x.numero(), x.lugar(), "66.7");

        long punto = jdbc.sql("SELECT id FROM puntos WHERE nombre = :n").param("n", x.lugar()).query(Long.class).single();
        JsonNode lugar = x.coord().get("/api/reportes/lugar" + filtro + "&punto_id=" + punto).json();
        assertThat(lugar.path("filtros").path("Lugar").asText()).isEqualTo(x.lugar());
        assertThat(lugar.path("filas").size()).isEqualTo(1);
        assertThat(lugar.path("filas").get(0).toString()).contains(x.lugar());

        JsonNode proceso = x.coord().get("/api/reportes/proceso" + filtro + "&componente=C08").json();
        assertThat(proceso.path("filas").toString()).contains("C08");
        assertThat(x.coord().get("/api/reportes/otro").estado()).isEqualTo(404);
        assertThat(x.coord().get("/api/reportes/detalle?desde=2026-13-01").estado()).isEqualTo(422);
    }

    @Test
    @DisplayName("descarga en Excel, CSV (con ; y BOM), PDF y TXT con el encabezado; envío por correo con el archivo adjunto")
    void exportarYEnviar() {
        Datos x = datos();
        String filtro = "&desde=" + x.desde() + "&hasta=" + x.hasta();
        Respuesta xlsx = x.coord().get("/api/reportes/detalle/exportar?formato=xlsx" + filtro);
        assertThat(xlsx.estado()).isEqualTo(200);
        assertThat(new String(xlsx.cuerpo(), 0, 2, StandardCharsets.ISO_8859_1)).isEqualTo("PK");
        Respuesta csv = x.coord().get("/api/reportes/lugar/exportar?formato=csv" + filtro);
        String textoCsv = new String(csv.cuerpo(), StandardCharsets.UTF_8);
        assertThat(textoCsv).startsWith("﻿Reporte;Consolidado de visitas por lugar").contains("Filtro: Periodo;").contains(x.lugar());
        Respuesta pdf = x.coord().get("/api/reportes/proceso/exportar?formato=pdf" + filtro);
        assertThat(new String(pdf.cuerpo(), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        Respuesta txt = x.coord().get("/api/reportes/detalle/exportar?formato=txt" + filtro);
        assertThat(new String(txt.cuerpo(), StandardCharsets.UTF_8)).startsWith("Reporte: Detalle de visitas").contains("Usuario:", x.numero());
        assertThat(x.coord().get("/api/reportes/detalle/exportar?formato=doc" + filtro).estado()).isEqualTo(422);

        Respuesta env = x.coord().post("/api/reportes/lugar/enviar", Map.of("para", "comite@example.com", "formato", "pdf", "desde", x.desde(), "hasta", x.hasta()));
        assertThat(env.estado()).as(env.texto()).isEqualTo(200);
        var m = ultimoCorreoA("comite@example.com");
        assertThat(m.adjuntos()).hasSize(1);
        assertThat(m.adjuntos().get(0).nombre()).endsWith(".pdf");
        assertThat(x.coord().post("/api/reportes/lugar/enviar", Map.of("para", "alguien@otro-dominio.com")).mensaje()).contains("solo se envían");
    }

    @Test
    @DisplayName("TXT sin recortar los nombres largos; la DIGSA (alcance total) no ve el auditor líder; un estado inválido es 422")
    void txtCompletoYDigsa() {
        Datos x = datos();
        String largo = x.lugar() + " " + "con un nombre muy largo para comprobar que el reporte en texto no se recorta";
        jdbc.sql("UPDATE puntos SET nombre = :n WHERE nombre = :l").param("n", largo).param("l", x.lugar()).update();
        String filtro = "&desde=" + x.desde() + "&hasta=" + x.hasta();
        String txt = new String(x.coord().get("/api/reportes/lugar/exportar?formato=txt" + filtro).cuerpo(), StandardCharsets.UTF_8);
        assertThat(txt).contains(largo);
        Navegador digsa = navegador();
        digsa.ingresar(crearUsuario("digsa", Perfil.DIGSA), CLAVE);
        JsonNode det = digsa.get("/api/reportes/detalle?desde=" + x.desde() + "&hasta=" + x.hasta()).json();
        assertThat(det.path("filas").toString()).contains(x.numero());
        assertThat(det.path("columnas").toString()).doesNotContain("Auditor líder");
        assertThat(x.coord().get("/api/reportes/detalle?estado=inventado").estado()).isEqualTo(422);
    }

    @Test
    @DisplayName("alcance: Sanidad ARC no ve las visitas del EJC en los reportes ni el líder; el auditor no entra (403)")
    void alcance() {
        Datos x = datos();
        String sanidad = crearUsuario("sanidad", Perfil.SANIDAD);
        jdbc.sql("UPDATE usuarios SET alcance_fuerza = 'ARC' WHERE usuario = :u").param("u", sanidad).update();
        Navegador s = navegador();
        s.ingresar(sanidad, CLAVE);
        JsonNode det = s.get("/api/reportes/detalle?desde=" + x.desde() + "&hasta=" + x.hasta()).json();
        assertThat(det.path("filas").toString()).doesNotContain(x.numero());
        assertThat(det.path("columnas").toString()).doesNotContain("Auditor líder");
        Navegador aud = navegador();
        aud.ingresar(crearUsuario("aud", Perfil.AUDITOR), CLAVE);
        assertThat(aud.get("/api/reportes/detalle").estado()).isEqualTo(403);
    }
}
