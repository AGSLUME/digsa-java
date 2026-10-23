package co.com.agsamericas.digsa.auditoria;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Logs del sistema (Anexo 5, prueba 15; portado de tests/e2e_local.php, sección de logs). */
class LogsApiTest extends PruebaIntegracion {

    @Test
    @DisplayName("lista del día con usuario, fecha, operación y detalle; filtro por operación; solo el administrador")
    void lista() {
        String adm = crearUsuario("admlog", Perfil.ADMINISTRADOR);
        Navegador n = navegador();
        n.ingresar(adm, CLAVE);
        n.get("/api/usuarios");
        Respuesta r = n.get("/api/logs?usuarioId=" + idDe(adm));
        assertThat(r.estado()).isEqualTo(200);
        JsonNode filas = r.json().path("filas");
        assertThat(filas.size()).isGreaterThan(0);
        JsonNode f = filas.get(0);
        assertThat(f.has("usuario") && f.has("fecha") && f.has("operacion") && f.has("descripcion")).isTrue();
        assertThat(f.path("fecha").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
        assertThat(filas.toString()).contains("usuarios.listar").contains("\"login\"");
        Respuesta soloIngresos = n.get("/api/logs?usuarioId=" + idDe(adm) + "&operacion=ingreso");
        assertThat(soloIngresos.json().path("filas").size()).isGreaterThan(0);
        soloIngresos.json().path("filas").forEach(x -> assertThat(x.path("operacion").asText()).isEqualTo("ingreso"));

        String coord = crearUsuario("coordlog", Perfil.COORDINADOR);
        Navegador c = navegador();
        c.ingresar(coord, CLAVE);
        assertThat(c.get("/api/logs").estado()).isEqualTo(403);
    }

    @Test
    @DisplayName("paginación por llave: la página siguiente trae filas anteriores sin repetir ninguna")
    void paginacion() {
        String adm = crearUsuario("admpag", Perfil.ADMINISTRADOR);
        Navegador n = navegador();
        n.ingresar(adm, CLAVE);
        for (int i = 0; i < 105; i++) {
            n.get("/api/usuarios");
        }
        Respuesta p1 = n.get("/api/logs?usuarioId=" + idDe(adm));
        JsonNode siguiente = p1.json().path("siguiente");
        assertThat(siguiente.isNull()).isFalse();
        assertThat(p1.json().path("filas").size()).isEqualTo(100);
        Respuesta p2 = n.get("/api/logs?usuarioId=" + idDe(adm) + "&despuesFecha="
                + java.net.URLEncoder.encode(siguiente.path("fecha").asText(), StandardCharsets.UTF_8) + "&despuesId=" + siguiente.path("id").asLong());
        Set<Long> ids = new HashSet<>();
        p1.json().path("filas").forEach(x -> ids.add(x.path("id").asLong()));
        p2.json().path("filas").forEach(x -> assertThat(ids.add(x.path("id").asLong())).as("fila repetida").isTrue());
        assertThat(p2.json().path("filas").size()).isGreaterThan(0);
    }

    @Test
    @DisplayName("búsqueda por texto con más de 92 días: 422")
    void textoConPeriodoLargo() {
        String adm = crearUsuario("admtxt", Perfil.ADMINISTRADOR);
        Navegador n = navegador();
        n.ingresar(adm, CLAVE);
        Respuesta r = n.get("/api/logs?desde=2026-01-01&hasta=2026-10-01&texto=login");
        assertThat(r.estado()).isEqualTo(422);
        assertThat(r.mensaje()).contains("92 días");
    }

    @Test
    @DisplayName("descarga en Excel con encabezado (título, fecha, usuario, filtros) y envío por correo desde la aplicación")
    void exportarYEnviar() throws Exception {
        String adm = crearUsuario("admexp", Perfil.ADMINISTRADOR);
        Navegador n = navegador();
        n.ingresar(adm, CLAVE);
        Respuesta x = n.get("/api/logs/exportar?usuarioId=" + idDe(adm));
        assertThat(x.estado()).isEqualTo(200);
        assertThat(x.cabecera("Content-Disposition")).contains(".xlsx");
        try (XSSFWorkbook libro = new XSSFWorkbook(new ByteArrayInputStream(x.cuerpo()))) {
            Sheet h = libro.getSheetAt(0);
            assertThat(h.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Logs de auditoría");
            assertThat(h.getRow(2).getCell(1).getStringCellValue()).contains(adm);
            assertThat(h.getRow(3).getCell(0).getStringCellValue()).startsWith("Filtro:");
        }
        Respuesta csv = n.get("/api/logs/exportar?formato=csv&usuarioId=" + idDe(adm));
        assertThat(csv.texto()).startsWith("﻿Reporte;Logs de auditoría").contains("Fecha y hora;Usuario;Operación");

        Respuesta e = n.post("/api/logs/enviar", Map.of("para", "evaluador@example.com", "usuarioId", idDe(adm)));
        assertThat(e.estado()).as(e.texto()).isEqualTo(200);
        Correo.Mensaje m = ultimoCorreoA("evaluador@example.com");
        assertThat(m.adjuntos()).hasSize(1);
        assertThat(m.adjuntos().getFirst().nombre()).endsWith(".xlsx");
        assertThat(bitacoraDe(idDe(adm)).getFirst()).startsWith("consulta|reporte_enviado|").contains("enviados a [correo]")
                .doesNotContain("evaluador@");
        assertThat(n.post("/api/logs/enviar", Map.of("para", "no-es-correo")).estado()).isEqualTo(422);
        Respuesta fuera = n.post("/api/logs/enviar", Map.of("para", "alguien@otrodominio.com"));
        assertThat(fuera.estado()).isEqualTo(422);
        assertThat(fuera.mensaje()).contains("solo se envían a correos de: example.com");
    }

    @Test
    @DisplayName("exportar muchas veces no agota el pool de conexiones (la conexión vuelve siempre al pool)")
    void exportarNoFugaConexiones() {
        String adm = crearUsuario("admfuga", Perfil.ADMINISTRADOR);
        Navegador n = navegador();
        n.ingresar(adm, CLAVE);
        for (int i = 0; i < 30; i++) {
            assertThat(n.get("/api/logs/exportar?formato=csv&usuarioId=" + idDe(adm)).estado()).as("exportación %d", i).isEqualTo(200);
        }
        assertThat(n.get("/api/sesion").estado()).isEqualTo(200);
    }

    @Autowired
    private co.com.agsamericas.digsa.plataforma.RegistroBitacora registro;

    @Test
    @DisplayName("cambios que no caben en 20.000 bytes: la fila se escribe igual, con los valores acortados con «…» y dentro del CHECK de V32")
    void cambiosQueNoCabenSeAcortan() {
        String marca = unico("recorte");
        java.util.List<co.com.agsamericas.digsa.reglas.Bitacora.Cambio> muchos = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            muchos.add(new co.com.agsamericas.digsa.reglas.Bitacora.Cambio("campo" + i, "á".repeat(1000), "é".repeat(1000), false));
        }
        registro.escribir(co.com.agsamericas.digsa.reglas.Bitacora.Operacion.ACTUALIZACION, "prueba_recorte", "pruebas", marca, null, "prueba", null, muchos);
        Map<String, Object> f = jdbc.sql("SELECT octet_length(cambios::text) AS bytes, cambios->0->>'antes' AS antes, jsonb_array_length(cambios) AS n "
                + "FROM log_transacciones WHERE descripcion = :d").param("d", marca).query().singleRow();
        assertThat(((Number) f.get("n")).intValue()).as("no se pierde ningún campo").isEqualTo(60);
        assertThat(((Number) f.get("bytes")).intValue()).isLessThanOrEqualTo(20_000);
        assertThat((String) f.get("antes")).endsWith("…");
    }

    @Test
    @DisplayName("ítem 15 b: editar un usuario deja el antes y el después completos (datos personales ocultos), filtrables por tipo y módulo y en el CSV")
    void antesYDespues() {
        String adm = crearUsuario("admcam", Perfil.ADMINISTRADOR);
        Navegador n = navegador();
        n.ingresar(adm, CLAVE);
        String u = crearUsuario("editado", Perfil.COORDINADOR);
        String nuevo = (u + "x").substring(0, Math.min(50, u.length() + 1));
        Map<String, Object> d = new java.util.HashMap<>();
        d.put("nombre", "Persona Renombrada de Prueba");
        d.put("usuario", nuevo);
        d.put("correo", "otro." + u + "@example.com");
        d.put("documento", "52111222");
        d.put("perfiles", java.util.List.of(Perfil.COORDINADOR.id(), Perfil.AUDITOR.id()));
        assertThat(n.put("/api/usuarios/" + idDe(u), d).estado()).isEqualTo(200);

        Respuesta r = n.get("/api/logs?tipo=usuario_editado&modulo=usuarios&usuarioId=" + idDe(adm));
        JsonNode filas = r.json().path("filas");
        assertThat(filas.size()).as(r.texto()).isEqualTo(1);
        JsonNode cambios = filas.get(0).path("cambios");
        Map<String, JsonNode> porCampo = new java.util.HashMap<>();
        cambios.forEach(c -> porCampo.put(c.path("campo").asText(), c));
        assertThat(porCampo.get("usuario").path("antes").asText()).isEqualTo(u);
        assertThat(porCampo.get("usuario").path("despues").asText()).isEqualTo(nuevo);
        assertThat(porCampo.get("perfiles").path("despues").asText()).isEqualTo("2,3");
        assertThat(porCampo.get("nombre").path("oculto").asBoolean()).as("dato personal, Ley 1581").isTrue();
        assertThat(porCampo.get("nombre").path("antes").isNull()).isTrue();
        assertThat(porCampo.get("correo").path("oculto").asBoolean()).as("el correo cambiado también va oculto").isTrue();
        assertThat(cambios.toString()).doesNotContain("Renombrada").doesNotContain("52111222").doesNotContain("@");

        assertThat(n.get("/api/logs?tipo=no_existe&usuarioId=" + idDe(adm)).json().path("filas").size()).isZero();
        assertThat(n.get("/api/logs?tipo=usuario_editado&modulo=logs&usuarioId=" + idDe(adm)).json().path("filas").size()).isZero();
        assertThat(n.get("/api/logs/opciones").json().path("modulos").toString()).contains("\"usuarios\"");
        String csv = n.get("/api/logs/exportar?formato=csv&tipo=usuario_editado&usuarioId=" + idDe(adm)).texto();
        assertThat(csv).contains("Cambios (antes → después)").contains("usuario: «" + u + "» → «" + nuevo + "»").contains("nombre: modificado")
                .doesNotContain("Renombrada");
    }
}
