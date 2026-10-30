package co.com.agsamericas.digsa.correos;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.BandejaSalida;
import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.plataforma.DespachadorSalida;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Módulo Correos, fase C1: bitácora de envíos (encolados y directos), secretos ocultos, vista previa, reintento y descarga. */
class CorreosApiTest extends PruebaIntegracion {

    @Autowired
    BandejaSalida bandeja;
    @Autowired
    DespachadorSalida despachador;

    private Navegador admin(String prefijo) {
        String adm = crearUsuario(prefijo, Perfil.ADMINISTRADOR);
        Navegador n = navegador();
        assertThat(n.ingresar(adm, CLAVE).estado()).isEqualTo(200);
        return n;
    }

    @Test
    @DisplayName("el código MFA queda en la bitácora como envío directo, con el código oculto y sin reintento")
    void mfaEnmascarado() {
        String adm = crearUsuario("admcorm", Perfil.ADMINISTRADOR);
        Navegador n = navegador();
        n.ingresar(adm, CLAVE);
        String correoAdm = adm + "@example.com";
        Correo.Mensaje enviado = ultimoCorreoA(correoAdm);
        String codigo = enviado.html().replaceAll("(?s).*letter-spacing:6px;font-weight:bold\">(\\d{6})<.*", "$1");
        assertThat(codigo).matches("\\d{6}");

        Map<String, Object> fila = jdbc.sql("""
                SELECT id, html, sincrono, enmascarado, reintentable, estado, motivo_no_reintentable FROM correos_salida
                WHERE origen = 'mfa_codigo' AND :c = ANY (para) ORDER BY id DESC LIMIT 1""").param("c", correoAdm).query().singleRow();
        assertThat(fila.get("sincrono")).isEqualTo(true);
        assertThat(fila.get("enmascarado")).isEqualTo(true);
        assertThat(fila.get("reintentable")).isEqualTo(false);
        assertThat(fila.get("estado")).isEqualTo("enviado");
        assertThat((String) fila.get("html")).doesNotContain(codigo).contains("••••••");

        Respuesta lista = n.get("/api/correos/envios?tipo=mfa_codigo&destinatario=" + correoAdm);
        assertThat(lista.estado()).isEqualTo(200);
        JsonNode f = lista.json().path("filas").get(0);
        assertThat(f.path("tipo").asText()).isEqualTo("Código de verificación (MFA)");
        assertThat(f.path("destinatarios").asText()).doesNotContain(adm).contains("@example.com");
        assertThat(f.path("directo").asBoolean()).isTrue();
        assertThat(f.path("noReintentable").asText()).contains("un solo uso");

        long id = ((Number) fila.get("id")).longValue();
        Respuesta ver = n.get("/api/correos/envios/" + id);
        assertThat(ver.estado()).isEqualTo(200);
        assertThat(ver.json().path("para").get(0).asText()).isEqualTo(correoAdm);
        assertThat(ver.json().path("html").asText()).doesNotContain(codigo);
        assertThat(bitacoraDe(idDe(adm))).anyMatch(b -> b.startsWith("consulta|correo_destinatarios_vistos|") && b.contains("Envío " + id + " (mfa_codigo)"));

        Respuesta re = n.post("/api/correos/envios/" + id + "/reintento", Map.of("motivo", "Prueba de reintento del código"));
        assertThat(re.estado()).isEqualTo(409);
    }

    @Test
    @DisplayName("el enlace para definir la contraseña queda oculto en la bitácora")
    void enlaceClaveEnmascarado() {
        Navegador n = admin("admcorc");
        String otro = crearUsuario("usucorc", Perfil.COORDINADOR);
        Respuesta r = n.post("/api/usuarios/" + idDe(otro) + "/restablecer", Map.of());
        assertThat(r.estado()).isBetween(200, 299);
        Correo.Mensaje m = ultimoCorreoA(otro + "@example.com");
        assertThat(m.html()).contains("tkn=");
        String html = jdbc.sql("SELECT html FROM correos_salida WHERE origen = 'clave_enlace' AND :c = ANY (para) ORDER BY id DESC LIMIT 1")
                .param("c", otro + "@example.com").query(String.class).single();
        assertThat(html).doesNotContain("tkn=").contains("[enlace oculto]");
    }

    @Test
    @DisplayName("solo el administrador entra a la bitácora de envíos")
    void soloAdministrador() {
        String coord = crearUsuario("coocor", Perfil.COORDINADOR);
        Navegador c = navegador();
        c.ingresar(coord, CLAVE);
        assertThat(c.get("/api/correos/envios").estado()).isEqualTo(403);
        assertThat(c.get("/api/correos/opciones").estado()).isEqualTo(403);
    }

    @Test
    @DisplayName("reintento manual de un fallido encolado: motivo obligatorio, fila nueva enlazada, una sola vez y en la auditoría")
    void reintento() {
        String adm = crearUsuario("admcorr", Perfil.ADMINISTRADOR);
        Navegador n = navegador();
        n.ingresar(adm, CLAVE);
        String para = unico("dest").toLowerCase() + "@example.com";
        long id = bandeja.encolar("solicitud_nueva", "solicitud:1", new Correo.Mensaje(List.of(para), List.of(), "Prueba de reintento", "<p>Hola</p>", List.of()));
        esperar(() -> "enviado".equals(estado(id)), "el correo encolado sale");
        jdbc.sql("UPDATE correos_salida SET estado = 'fallido', enviado = NULL, error = 'El servidor de correo rechazó el envío.' WHERE id = :id")
                .param("id", id).update();

        assertThat(n.post("/api/correos/envios/" + id + "/reintento", Map.of("motivo", "corto")).estado()).isEqualTo(422);
        Respuesta ok = n.post("/api/correos/envios/" + id + "/reintento", Map.of("motivo", "El buzón ya quedó corregido por TI"));
        assertThat(ok.estado()).isEqualTo(200);
        long nuevo = ok.json().path("id").asLong();
        assertThat(nuevo).isGreaterThan(id);
        Map<String, Object> fila = jdbc.sql("SELECT reintento_de, motivo_reintento, reintentado_por FROM correos_salida WHERE id = :id").param("id", nuevo)
                .query().singleRow();
        assertThat(((Number) fila.get("reintento_de")).longValue()).isEqualTo(id);
        assertThat(fila.get("motivo_reintento")).isEqualTo("El buzón ya quedó corregido por TI");
        assertThat(((Number) fila.get("reintentado_por")).longValue()).isEqualTo(idDe(adm));
        esperar(() -> "enviado".equals(estado(nuevo)), "el reintento sale");
        assertThat(estado(id)).isEqualTo("fallido");

        Respuesta otraVez = n.post("/api/correos/envios/" + id + "/reintento", Map.of("motivo", "Segundo intento sobre el mismo"));
        assertThat(otraVez.estado()).isEqualTo(409);
        assertThat(otraVez.mensaje()).contains("Ya se reintentó como envío N.º " + nuevo);
        assertThat(bitacoraDe(idDe(adm))).anyMatch(b -> b.startsWith("insercion|correo_reintentado|") && b.contains("Envío " + id + " (solicitud_nueva) reintentado"));

        JsonNode original = n.get("/api/correos/envios/" + id).json();
        assertThat(original.path("reintentoId").asLong()).isEqualTo(nuevo);
    }

    @Test
    @DisplayName("filtros: estado y destinatario exacto; un destinatario incompleto es 422")
    void filtros() {
        Navegador n = admin("admcorf");
        String para = unico("filt").toLowerCase() + "@example.com";
        long id = bandeja.encolar("solicitud_nueva", "solicitud:2", new Correo.Mensaje(List.of(para), List.of(), "Prueba de filtro", "<p>x</p>", List.of()));
        esperar(() -> "enviado".equals(estado(id)), "el correo sale");
        JsonNode filas = n.get("/api/correos/envios?estado=enviado&destinatario=" + para.toUpperCase()).json().path("filas");
        assertThat(filas.size()).isEqualTo(1);
        assertThat(filas.get(0).path("id").asLong()).isEqualTo(id);
        assertThat(n.get("/api/correos/envios?estado=fallido&destinatario=" + para).json().path("filas").size()).isZero();
        assertThat(n.get("/api/correos/envios?destinatario=filt").estado()).isEqualTo(422);
        assertThat(n.get("/api/correos/envios?estado=perdido").estado()).isEqualTo(422);
    }

    @Test
    @DisplayName("un envío directo que quedó en «enviando» (la aplicación cayó) se cierra como fallido y no vuelve a la cola")
    void directoColgado() {
        long id = jdbc.sql("""
                INSERT INTO correos_salida (origen, para, asunto, html, estado, intentos, proximo, creado, sincrono, reintentable, motivo_no_reintentable)
                VALUES ('acta', '{colgado@example.com}', 'Acta colgada', '', 'enviando', 1, :c, :c, true, false, 'Se reenvía desde la visita')
                RETURNING id""").param("c", OffsetDateTime.now().minusHours(1)).query(Long.class).single();
        despachador.recuperarColgados();
        assertThat(estado(id)).isEqualTo("fallido");
        assertThat(jdbc.sql("SELECT error FROM correos_salida WHERE id = :id").param("id", id).query(String.class).single()).contains("envío directo");
    }

    @Test
    @DisplayName("descarga de la bitácora por el motor de reportes, con destinatarios enmascarados")
    void descarga() {
        Navegador n = admin("admcord");
        String para = unico("desc").toLowerCase() + "@example.com";
        long id = bandeja.encolar("solicitud_nueva", "solicitud:3", new Correo.Mensaje(List.of(para), List.of(), "Prueba de descarga", "<p>x</p>", List.of()));
        esperar(() -> "enviado".equals(estado(id)), "el correo sale");
        Respuesta x = n.get("/api/reportes/" + ProveedorReporteCorreos.ID + "/exportar?formato=csv&destinatario=" + para);
        assertThat(x.estado()).isEqualTo(200);
        String csv = new String(x.cuerpo(), StandardCharsets.UTF_8);
        // La fila de datos lleva el destinatario enmascarado (el encabezado repite el filtro que escribió el propio administrador)
        String fila = csv.lines().filter(l -> l.contains("Prueba de descarga")).findFirst().orElseThrow();
        assertThat(fila).doesNotContain(para).contains("@example.com");
    }

    private String estado(long id) {
        return jdbc.sql("SELECT estado FROM correos_salida WHERE id = :id").param("id", id).query(String.class).single();
    }
}
