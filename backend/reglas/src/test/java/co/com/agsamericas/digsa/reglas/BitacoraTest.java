package co.com.agsamericas.digsa.reglas;

import static co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import static co.com.agsamericas.digsa.reglas.Bitacora.Operacion;
import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Bitacora.Anotacion;
import co.com.agsamericas.digsa.reglas.Bitacora.Fila;
import co.com.agsamericas.digsa.reglas.Bitacora.Peticion;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Portado de tests/bitacora_test.php (decisión 5 y Anexo 5, prueba 15). */
class BitacoraTest {

    private static Map<String, Object> datos(Object... claveValor) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < claveValor.length; i += 2) {
            m.put((String) claveValor[i], claveValor[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("OPERACIONES: las 6 del Anexo 5, con su valor en la base")
    void operaciones() {
        assertThat(Arrays.stream(Operacion.values()).map(Operacion::valor))
                .containsExactly("insercion", "actualizacion", "eliminacion", "consulta", "ingreso", "salida");
    }

    @Test
    @DisplayName("operación: GET es consulta; lo declarado manda en los POST; lo no declarado es actualización")
    void operacion() {
        assertThat(Bitacora.operacion("GET", Declarada.INSERCION, Map.of())).contains(Operacion.CONSULTA);
        assertThat(Bitacora.operacion("POST", Declarada.INSERCION, Map.of())).contains(Operacion.INSERCION);
        assertThat(Bitacora.operacion("DELETE", Declarada.ELIMINACION, Map.of())).contains(Operacion.ELIMINACION);
        assertThat(Bitacora.operacion("POST", null, Map.of())).contains(Operacion.ACTUALIZACION);
        assertThat(Bitacora.operacion("GET", Declarada.SIN_REGISTRO, Map.of())).as("latido de la sesión").isEmpty();
    }

    @Test
    @DisplayName("según el id: sin id, '0' o vacío ⇒ inserción; '7' ⇒ actualización")
    void segunId() {
        assertThat(Bitacora.operacion("POST", Declarada.SEGUN_ID, datos("id", "0"))).contains(Operacion.INSERCION);
        assertThat(Bitacora.operacion("POST", Declarada.SEGUN_ID, Map.of())).contains(Operacion.INSERCION);
        assertThat(Bitacora.operacion("POST", Declarada.SEGUN_ID, datos("id", ""))).contains(Operacion.INSERCION);
        assertThat(Bitacora.operacion("POST", Declarada.SEGUN_ID, datos("id", "7"))).contains(Operacion.ACTUALIZACION);
        assertThat(Bitacora.operacion("PUT", Declarada.SEGUN_ID, datos("id", 12))).contains(Operacion.ACTUALIZACION);
    }

    @Test
    @DisplayName("según confirmar: vista previa de una carga masiva es consulta; confirmada, inserción")
    void segunConfirmar() {
        assertThat(Bitacora.operacion("POST", Declarada.SEGUN_CONFIRMAR, datos("confirmar", "1"))).contains(Operacion.INSERCION);
        assertThat(Bitacora.operacion("POST", Declarada.SEGUN_CONFIRMAR, datos("confirmar", true))).contains(Operacion.INSERCION);
        assertThat(Bitacora.operacion("POST", Declarada.SEGUN_CONFIRMAR, datos("confirmar", "0"))).contains(Operacion.CONSULTA);
        assertThat(Bitacora.operacion("POST", Declarada.SEGUN_CONFIRMAR, Map.of())).contains(Operacion.CONSULTA);
    }

    @Test
    @DisplayName("tipos de los eventos: ingreso, salida y los demás; un tipo desconocido es actualización")
    void tipos() {
        for (String t : List.of("login", "login_fallido", "captcha_fallido", "mfa_enviado", "mfa_fallido")) {
            assertThat(Bitacora.operacionDeTipo(t)).as(t).isEqualTo(Operacion.INGRESO);
        }
        for (String t : List.of("logout", "sesion_expirada", "sesion_reemplazada")) {
            assertThat(Bitacora.operacionDeTipo(t)).as(t).isEqualTo(Operacion.SALIDA);
        }
        assertThat(Bitacora.operacionDeTipo("usuario_creado")).isEqualTo(Operacion.INSERCION);
        assertThat(Bitacora.operacionDeTipo("carga_maestros")).as("carga aplicada").isEqualTo(Operacion.INSERCION);
        assertThat(Bitacora.operacionDeTipo("carga_maestros_simulada")).as("simulación: no escribe").isEqualTo(Operacion.CONSULTA);
        assertThat(Bitacora.operacionDeTipo("auditor_creado")).isEqualTo(Operacion.INSERCION);
        assertThat(Bitacora.operacionDeTipo("catalogo_eliminado")).isEqualTo(Operacion.ELIMINACION);
        assertThat(Bitacora.operacionDeTipo("acceso_denegado")).isEqualTo(Operacion.CONSULTA);
        // Fase 10: alertas y bandeja de salida
        assertThat(Bitacora.operacionDeTipo("alerta_critica")).isEqualTo(Operacion.INSERCION);
        assertThat(Bitacora.operacionDeTipo("correo_fallido")).isEqualTo(Operacion.INSERCION);
        assertThat(Bitacora.operacionDeTipo("alerta_catalogo_editado")).isEqualTo(Operacion.ACTUALIZACION);
        assertThat(Bitacora.operacionDeTipo("tipo_que_no_existe")).isEqualTo(Operacion.ACTUALIZACION);
    }

    @Test
    @DisplayName("detalle del ejemplo y sin datos")
    void detalleEjemplo() {
        assertThat(Bitacora.detalle("agenda", "programar",
                datos("accion", "programar", "tipo", "inicial", "punto_id", "4", "fecha", "2026-11-20", "csrf", "x", "alcance", "texto")))
                .isEqualTo("agenda.programar · tipo=inicial, punto_id=4, fecha=2026-11-20");
        assertThat(Bitacora.detalle("agenda", "programar", Map.of())).isEqualTo("agenda.programar");
        assertThat(Bitacora.detalle("agenda", "programar", datos("csrf", "x", "alcance", "texto"))).isEqualTo("agenda.programar");
    }

    @Test
    @DisplayName("detalle omite datos personales, contraseñas, para/asunto/mensaje, filas y motivo")
    void detalleSinDatosPersonales() {
        assertThat(Bitacora.detalle("usuarios", "guardar",
                datos("password", "Secreta1!", "mail", "a@b.co", "nombre", "Ana", "documento", "123", "telefono", "300", "id", "7")))
                .isEqualTo("usuarios.guardar · id=7");
        assertThat(Bitacora.detalle("checklist", "enviar", datos("id", "5", "acta", "9", "para", "x@y.co", "asunto", "Acta", "mensaje", "Hola")))
                .isEqualTo("checklist.enviar · id=5, acta=9");
        assertThat(Bitacora.detalle("maestros", "puntos_importar", datos("filas", "[{\"nombre\":\"x\"}]", "confirmar", "1", "motivo", "m")))
                .isEqualTo("maestros.puntos_importar · confirmar=1");
    }

    @Test
    @DisplayName("secretos: contraseña, confirmación, código, token y captcha nunca van al detalle (aunque estén en la lista blanca)")
    void secretos() {
        assertThat(Bitacora.detalle("sesion", "clave_definir", datos("tkn", "a".repeat(64), "clave", "Nueva.Clave.2026", "confirmacion", "Nueva.Clave.2026")))
                .isEqualTo("sesion.clave_definir");
        assertThat(Bitacora.detalle("sesion", "ingreso", datos("usuario", "ana", "clave", "Secreta.2026", "captcha", "x", "codigo", "123456")))
                .isEqualTo("sesion.ingreso");
        assertThat(Bitacora.CLAVES_DETALLE).doesNotContainAnyElementsOf(Bitacora.SECRETOS);
    }

    @Test
    @DisplayName("detalle: orden de llegada, estado largo omitido (20 sí, 21 no), no escalares fuera, enteros y saltos de línea")
    void detalleForma() {
        assertThat(Bitacora.detalle("agenda", "lista", datos("hasta", "2026-12-31", "desde", "2026-01-01", "estado", "programada")))
                .isEqualTo("agenda.lista · hasta=2026-12-31, desde=2026-01-01, estado=programada");
        assertThat(Bitacora.detalle("checklist", "guardar", datos("id", "5", "revision", "3", "estado", "{\"C01\":{\"r\":\"si\",\"obs\":\"todo bien\"}}")))
                .isEqualTo("checklist.guardar · id=5, revision=3");
        assertThat(Bitacora.detalle("agenda", "lista", datos("estado", "a".repeat(20)))).isEqualTo("agenda.lista · estado=" + "a".repeat(20));
        assertThat(Bitacora.detalle("agenda", "lista", datos("estado", "a".repeat(21)))).isEqualTo("agenda.lista");
        assertThat(Bitacora.detalle("agenda", "lista", datos("id", List.of("1", "2"), "tipo", null, "fecha", "2026-11-20")))
                .isEqualTo("agenda.lista · fecha=2026-11-20");
        assertThat(Bitacora.detalle("checklist", "subir", datos("id", 3, "item", "C01", "reemplazar", 0)))
                .isEqualTo("checklist.subir · id=3, item=C01, reemplazar=0");
        assertThat(Bitacora.detalle("checklist", "subir", datos("archivo", "acta\nfinal\tv2.pdf"))).isEqualTo("checklist.subir · archivo=acta final v2.pdf");
        assertThat(Bitacora.detalle("checklist", "subir", datos("archivo", "Inspección_Señal_Bogotá.pdf")))
                .isEqualTo("checklist.subir · archivo=Inspección_Señal_Bogotá.pdf");
    }

    @Test
    @DisplayName("corte a 60 caracteres contando caracteres, sin romper UTF-8; tope total de 500")
    void cortes() {
        String prefijo = "checklist.subir · archivo=";
        assertThat(Bitacora.detalle("checklist", "subir", datos("archivo", "ñ".repeat(60)))).isEqualTo(prefijo + "ñ".repeat(60));
        String d = Bitacora.detalle("checklist", "subir", datos("archivo", "ñ".repeat(61)));
        String valor = d.substring(prefijo.length());
        assertThat(valor).startsWith("ñ".repeat(59)).endsWith("…");
        assertThat(valor.codePointCount(0, valor.length())).isEqualTo(60);
        assertThat(new String(d.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).isEqualTo(d);
        d = Bitacora.detalle("checklist", "subir", datos("archivo", "x".repeat(300)));
        assertThat(d).hasSize(prefijo.length() + 60).endsWith("…");

        List<String> claves = List.of("id", "archivo", "acta", "revision", "item", "tipo", "punto_id", "auditor_id", "fecha", "hora", "desde",
                "hasta", "fuerza", "unidad", "confirmar", "prefijo", "reemplazar", "dep");
        Map<String, Object> largos = new LinkedHashMap<>();
        claves.forEach(c -> largos.put(c, "á".repeat(80)));
        d = Bitacora.detalle("checklist", "subir", largos);
        assertThat(d.codePointCount(0, d.length())).isLessThanOrEqualTo(500);
        assertThat(d).startsWith("checklist.subir · id=");
        Map<String, Object> cortos = new LinkedHashMap<>();
        claves.forEach(c -> cortos.put(c, "v"));
        assertThat(Bitacora.detalle("checklist", "subir", cortos).split("=v", -1)).hasSize(19);
    }

    @Test
    @DisplayName("acción inventada por el cliente: sin saltos ni <, máximo 40 caracteres")
    void accionLimpia() {
        String d = Bitacora.detalle("usuarios", "x\ny<script>" + "z".repeat(100), Map.of());
        assertThat(d).doesNotContain("\n", "<", ">");
        assertThat(d.length()).isLessThanOrEqualTo("usuarios.".length() + 40);
    }

    private static Peticion pet(String modulo, String metodo, String accion, Declarada declarada, Map<String, ?> datos, List<Anotacion> anot) {
        return new Peticion(modulo, metodo, accion, declarada, datos, anot);
    }

    @Test
    @DisplayName("fila: qué se registra al cerrar una petición")
    void fila() {
        Fila f = Bitacora.fila(pet("agenda", "POST", "editar", Declarada.ACTUALIZACION, datos("id", "4"), null), 200, false).orElseThrow();
        assertThat(f).isEqualTo(new Fila(Operacion.ACTUALIZACION, "agenda.editar", "agenda.editar · id=4"));
        assertThat(Bitacora.fila(pet("agenda", "POST", "editar", Declarada.ACTUALIZACION, null, null), 422, false)).isEmpty();
        assertThat(Bitacora.fila(pet("agenda", "GET", "detalle", Declarada.CONSULTA, null, null), 404, false)).isEmpty();
        assertThat(Bitacora.fila(pet("agenda", "GET", "lista", Declarada.CONSULTA, null, null), 500, false)).isEmpty();
        f = Bitacora.fila(pet("agenda", "POST", "reprogramar", Declarada.ACTUALIZACION, datos("id", "9"), null), 500, false).orElseThrow();
        assertThat(f.operacion()).isEqualTo(Operacion.ACTUALIZACION);
        assertThat(f.descripcion()).endsWith("HTTP 500");
        f = Bitacora.fila(pet("usuarios", "GET", "listar", Declarada.CONSULTA, null, null), 403, false).orElseThrow();
        assertThat(f.tipo()).isEqualTo("acceso_denegado");
        assertThat(f.operacion()).isEqualTo(Operacion.CONSULTA);
        assertThat(f.descripcion()).contains("HTTP 403");
        f = Bitacora.fila(pet("usuarios", "POST", "guardar", Declarada.SEGUN_ID, null, null), 419, false).orElseThrow();
        assertThat(f.tipo()).isEqualTo("acceso_denegado");
        assertThat(f.descripcion()).contains("HTTP 419");
        List<Anotacion> anot = List.of(new Anotacion("usuario_creado", "Usuario x creado"));
        f = Bitacora.fila(pet("usuarios", "POST", "guardar", Declarada.SEGUN_ID, null, anot), 422, false).orElseThrow();
        assertThat(f.tipo()).isEqualTo("usuario_creado");
        assertThat(f.descripcion()).contains("Usuario x creado").contains("HTTP 422");
        f = Bitacora.fila(pet("usuarios", "POST", "guardar", Declarada.SEGUN_ID, null, anot), 200, true).orElseThrow();
        assertThat(f.descripcion()).contains("transacción sin confirmar");
        assertThat(Bitacora.fila(pet("sesion", "GET", "mantener", Declarada.SIN_REGISTRO, null, null), 200, false)).isEmpty();
        f = Bitacora.fila(pet("maestros", "POST", "puntos_importar", Declarada.SEGUN_CONFIRMAR, datos("confirmar", "0"), null), 200, false).orElseThrow();
        assertThat(f.operacion()).isEqualTo(Operacion.CONSULTA);
    }

    @Test
    @DisplayName("fila: con un detalle largo, la anotación y el código caben en 500")
    void filaLarga() {
        Map<String, Object> largos = new LinkedHashMap<>();
        for (String c : List.of("id", "archivo", "acta", "revision", "item", "tipo", "punto_id", "auditor_id", "fecha", "hora", "desde",
                "hasta", "fuerza", "unidad", "confirmar", "prefijo", "reemplazar", "dep")) {
            largos.put(c, "a".repeat(80));
        }
        Fila f = Bitacora.fila(pet("checklist", "POST", "subir", Declarada.INSERCION, largos, List.of(new Anotacion("x", "Evento anotado"))), 422, false)
                .orElseThrow();
        assertThat(f.descripcion().codePointCount(0, f.descripcion().length())).isLessThanOrEqualTo(500);
        assertThat(f.descripcion()).contains("Evento anotado").endsWith("HTTP 422");
    }

    @Test
    @DisplayName("cambios: antes y después de lo que cambió; los datos personales dicen solo «modificado» (prueba 15 y Ley 1581)")
    void cambios() {
        Map<String, Object> antes = datos("estado", "programada", "nombre", "Ana Pérez", "perfiles", List.of(3, 2), "fecha", "2026-11-20");
        Map<String, Object> despues = datos("estado", "cancelada", "nombre", "Ana María Pérez", "perfiles", List.of(2, 3), "fecha", "2026-11-20");
        assertThat(Bitacora.cambios(antes, despues, java.util.Set.of("nombre")))
                .isEqualTo("estado: «programada» → «cancelada»; nombre: modificado");
        assertThat(Bitacora.cambios(antes, antes, java.util.Set.of())).isEmpty();
        assertThat(Bitacora.cambios(datos("correos", "a@b.co"), datos("correos", "c@d.co"), java.util.Set.of())).doesNotContain("@");
        assertThat(Bitacora.cambios(datos(), datos("descripcion", "Nueva"), java.util.Set.of())).isEqualTo("descripcion: «» → «Nueva»");
    }

    @Test
    @DisplayName("sinCorreos: quita las direcciones (Ley 1581); sin correos no cambia nada")
    void sinCorreos() {
        String t = Bitacora.sinCorreos("el correo falló: SMTP Error: The following recipients failed: ana.perez@agsamericas.com.co: 550 no existe; <x_y@dominio.org>");
        assertThat(t).doesNotContain("@");
        assertThat(t.split("\\[correo]", -1)).hasSize(3);
        assertThat(Bitacora.sinCorreos("Usuario coord123 editado; perfiles: 2,3")).isEqualTo("Usuario coord123 editado; perfiles: 2,3");
    }

    @Test
    @DisplayName("usuario de un ingreso fallido: solo los 3 primeros caracteres")
    void enmascarar() {
        assertThat(Bitacora.enmascararUsuario("coordinador")).isEqualTo("coo***");
        assertThat(Bitacora.enmascararUsuario("ab")).isEqualTo("ab***");
        assertThat(Bitacora.enmascararUsuario(null)).isEqualTo("***");
    }

    @Test
    @DisplayName("TIPOS de la exportación: la descarga es consulta, la solicitud inserción y la limpieza eliminación")
    void tiposExportacion() {
        assertThat(Bitacora.operacionDeTipo("exportacion_completa")).isEqualTo(Operacion.CONSULTA);
        assertThat(Bitacora.operacionDeTipo("soportes_opl_descargados")).isEqualTo(Operacion.CONSULTA);
        assertThat(Bitacora.operacionDeTipo("exportacion_solicitada")).isEqualTo(Operacion.INSERCION);
        assertThat(Bitacora.operacionDeTipo("exportacion_vencida")).isEqualTo(Operacion.ELIMINACION);
        assertThat(Bitacora.TIPOS).containsKeys("exportacion_generada", "exportacion_fallida");
    }
}
