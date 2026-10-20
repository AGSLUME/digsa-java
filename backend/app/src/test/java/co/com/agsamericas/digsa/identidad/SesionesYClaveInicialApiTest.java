package co.com.agsamericas.digsa.identidad;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Decisiones del 2026-10-20: cierre de sesión por el administrador (7.1, 7.3), contraseña inicial, antes/después y nombres de perfiles. */
class SesionesYClaveInicialApiTest extends PruebaIntegracion {

    private static final String INICIAL = "Inicial.Asignada.2026";
    private static final String NUEVA = "Propia.Del.Usuario.2026";

    private Navegador admin() {
        Navegador n = navegador();
        assertThat(n.ingresar(crearUsuario("admin", Perfil.ADMINISTRADOR), CLAVE).estado()).isEqualTo(200);
        return n;
    }

    private static Map<String, Object> datos(String usuario, List<Integer> perfiles) {
        Map<String, Object> m = new HashMap<>();
        m.put("nombre", "Usuario de prueba " + usuario);
        m.put("usuario", usuario);
        m.put("correo", usuario + "@example.com");
        m.put("documento", "12345678");
        m.put("perfiles", perfiles);
        return m;
    }

    private List<String> bitacoraDeTipo(String tipo) {
        return jdbc.sql("SELECT descripcion FROM log_transacciones WHERE tipo = :t ORDER BY id DESC").param("t", tipo).query(String.class).list();
    }

    /** Los eventos de una petición se suman en una sola fila de la bitácora (la del tipo del primero). */
    private boolean enBitacora(String texto) {
        return jdbc.sql("SELECT count(*) FROM log_transacciones WHERE strpos(descripcion, :t) > 0").param("t", texto).query(Long.class).single() > 0;
    }

    @Test
    @DisplayName("el administrador cierra la sesión abierta: el usuario recibe 401 «cerrada», puede volver a entrar y queda sesion_cerrada")
    void administradorCierraSesion() {
        String u = crearUsuario("cerrar", Perfil.COORDINADOR);
        Navegador usuario = navegador();
        usuario.ingresar(u, CLAVE);
        Navegador adm = admin();
        Respuesta lista = adm.get("/api/usuarios");
        assertThat(lista.json().path("usuarios").toString()).contains("\"usuario\":\"" + u + "\"");
        boolean abierta = false;
        for (var f : lista.json().path("usuarios")) {
            if (f.path("usuario").asText().equals(u)) {
                abierta = f.path("sesionAbierta").asBoolean();
            }
        }
        assertThat(abierta).isTrue();

        Respuesta r = adm.post("/api/usuarios/" + idDe(u) + "/cerrar-sesion", null);
        assertThat(r.estado()).isEqualTo(200);
        assertThat(r.mensaje()).isEqualTo("Sesión cerrada.");
        assertThat(enBitacora("Sesión de " + u + " cerrada por el administrador")).isTrue();

        Respuesta s = usuario.get("/api/sesion");
        assertThat(s.estado()).isEqualTo(401);
        assertThat(s.json().path("motivo").asText()).isEqualTo("cerrada");
        assertThat(navegador().ingresar(u, CLAVE).estado()).as("ya no la bloquea").isEqualTo(200);
        assertThat(adm.post("/api/usuarios/" + idDe(crearUsuario("sinses", Perfil.AUDITOR)) + "/cerrar-sesion", null).mensaje())
                .isEqualTo("El usuario no tenía una sesión abierta.");
    }

    @Test
    @DisplayName("decisión 7.3: inactivar a un usuario con sesión abierta deja la salida en la bitácora")
    void inactivarDejaSalida() {
        String u = crearUsuario("inact", Perfil.COORDINADOR);
        Navegador usuario = navegador();
        usuario.ingresar(u, CLAVE);
        Navegador adm = admin();
        assertThat(adm.put("/api/usuarios/" + idDe(u) + "/estado", Map.of("activo", false)).estado()).isEqualTo(200);
        assertThat(enBitacora("Sesión de " + u + " cerrada por inactivación del usuario")).isTrue();
        Respuesta s = usuario.get("/api/sesion");
        assertThat(s.estado()).isEqualTo(401);
        assertThat(s.mensaje()).isEqualTo("Su usuario está inactivo.");
    }

    @Test
    @DisplayName("contraseña inicial: el administrador crea el usuario sin correo; el usuario la cambia al ingresar y sigue con el código")
    void claveInicialAlCrear() {
        Navegador adm = admin();
        String u = unico("inicial").toLowerCase();
        Map<String, Object> d = datos(u, List.of(3));
        d.put("claveInicial", INICIAL);
        int correosAntes = correos().size();
        Respuesta r = adm.post("/api/usuarios", d);
        assertThat(r.estado()).as(r.texto()).isEqualTo(201);
        assertThat(r.json().path("mensaje").asText()).contains("contraseña inicial");
        assertThat(correos()).as("sin correo de bienvenida").hasSize(correosAntes);
        assertThat(bitacoraDeTipo("usuario_creado").getFirst()).contains("con contraseña inicial").doesNotContain(INICIAL);

        Navegador n = navegador();
        Respuesta p1 = n.post("/api/auth/ingreso", Map.of("usuario", u, "clave", INICIAL));
        assertThat(p1.json().path("paso").asText()).isEqualTo("cambiar_clave");
        assertThat(n.get("/api/sesion").estado()).isEqualTo(401);
        assertThat(n.post("/api/auth/cambiar-clave", Map.of("clave", INICIAL, "confirmacion", INICIAL)).mensaje())
                .contains("diferente");
        assertThat(n.post("/api/auth/cambiar-clave", Map.of("clave", NUEVA, "confirmacion", "Otra.Cosa.2026")).mensaje())
                .contains("no coinciden");
        Respuesta p2 = n.post("/api/auth/cambiar-clave", Map.of("clave", NUEVA, "confirmacion", NUEVA));
        assertThat(p2.estado()).as(p2.texto()).isEqualTo(200);
        assertThat(p2.json().path("paso").asText()).isEqualTo("codigo");
        assertThat(n.post("/api/auth/codigo", Map.of("codigo", Navegador.codigo(p2))).estado()).isEqualTo(200);
        assertThat(n.get("/api/sesion").estado()).isEqualTo(200);

        Usuario despues = usuarios.porUsuario(u).orElseThrow();
        assertThat(despues.claveInicial()).isFalse();
        assertThat(despues.debeCambiarClave()).isFalse();
        assertThat(codificador.matches(NUEVA, despues.claveHash())).isTrue();
        assertThat(bitacoraDe(despues.id())).anyMatch(f -> f.startsWith("actualizacion|clave_cambio|Contraseña inicial cambiada"));
        n.post("/api/auth/salir", null);
        assertThat(navegador().post("/api/auth/ingreso", Map.of("usuario", u, "clave", INICIAL)).estado()).as("la inicial ya no sirve").isEqualTo(401);
    }

    @Test
    @DisplayName("cambiar-clave sin haber pasado por la contraseña inicial responde 401; una contraseña inicial débil se rechaza")
    void claveInicialBordes() {
        Navegador n = navegador();
        n.get("/api/auth/inicio");
        assertThat(n.post("/api/auth/cambiar-clave", Map.of("clave", NUEVA, "confirmacion", NUEVA)).estado()).isEqualTo(401);
        Navegador adm = admin();
        Map<String, Object> d = datos(unico("debil").toLowerCase(), List.of(3));
        d.put("claveInicial", "corta");
        Respuesta r = adm.post("/api/usuarios", d);
        assertThat(r.estado()).isEqualTo(422);
        assertThat(r.mensaje()).startsWith("Contraseña inicial:");
    }

    @Test
    @DisplayName("asignar contraseña inicial a un usuario existente cierra su sesión y lo obliga a cambiarla")
    void asignarClaveInicialExistente() {
        String u = crearUsuario("reasig", Perfil.AUDITOR);
        Navegador usuario = navegador();
        usuario.ingresar(u, CLAVE);
        Navegador adm = admin();
        Respuesta r = adm.post("/api/usuarios/" + idDe(u) + "/clave-inicial", Map.of("clave", INICIAL));
        assertThat(r.estado()).as(r.texto()).isEqualTo(200);
        assertThat(usuario.get("/api/sesion").estado()).isEqualTo(401);
        assertThat(enBitacora("Sesión de " + u + " cerrada por asignación de contraseña")).isTrue();
        assertThat(navegador().post("/api/auth/ingreso", Map.of("usuario", u, "clave", INICIAL)).json().path("paso").asText())
                .isEqualTo("cambiar_clave");
    }

    @Test
    @DisplayName("antes/después: la edición deja los valores de usuario y perfiles, y de los datos personales solo «modificado»")
    void antesYDespues() {
        Navegador adm = admin();
        String u = unico("edit").toLowerCase();
        Respuesta c = adm.post("/api/usuarios", datos(u, List.of(3)));
        long id = c.json().path("id").asLong();
        Map<String, Object> d = datos(u, List.of(2));
        d.put("nombre", "Nombre Cambiado De Prueba");
        d.put("telefono", "3000000000");
        assertThat(adm.put("/api/usuarios/" + id, d).estado()).isEqualTo(200);
        String fila = bitacoraDeTipo("usuario_editado").getFirst();
        assertThat(fila).contains("nombre: modificado").contains("telefono: modificado").contains("perfiles: «3» → «2»")
                .doesNotContain("Nombre Cambiado").doesNotContain("3000000000");
    }

    @Test
    @DisplayName("nombres de perfiles configurables: el administrador renombra y la sesión muestra el nombre nuevo; duplicado 422")
    void renombrarPerfil() {
        Navegador adm = admin();
        String antes = usuarios.nombresPerfiles().get(10);
        String descripcion = jdbc.sql("SELECT descripcion FROM perfiles WHERE id = 10").query(String.class).single();
        try {
            assertThat(adm.put("/api/usuarios/perfiles/10", Map.of("nombre", "Analista de cuentas", "descripcion", "Prueba")).estado())
                    .isEqualTo(200);
            assertThat(bitacoraDeTipo("perfil_editado").getFirst()).contains("→ «Analista de cuentas»");
            String u = crearUsuario("analista", Perfil.ANALISTA);
            Navegador n = navegador();
            n.ingresar(u, CLAVE);
            assertThat(n.get("/api/sesion").json().path("perfiles").toString()).contains("Analista de cuentas");
            assertThat(adm.put("/api/usuarios/perfiles/10", Map.of("nombre", "administrador")).estado()).isEqualTo(422);
            assertThat(adm.put("/api/usuarios/perfiles/99", Map.of("nombre", "No existe")).estado()).isEqualTo(404);
        } finally {
            usuarios.renombrarPerfil(10, antes, descripcion);
        }
    }

    @Test
    @DisplayName("la actividad se guarda en la base como máximo cada 30 s (bloqueo del segundo ingreso sin una escritura por petición)")
    void actividadEspaciada() {
        String u = crearUsuario("toque", Perfil.COORDINADOR);
        Navegador n = navegador();
        n.ingresar(u, CLAVE);
        n.get("/api/sesion");
        var primera = usuarios.porUsuario(u).orElseThrow().sesionUltimo();
        reloj.adelantar(Duration.ofSeconds(10));
        n.get("/api/sesion");
        assertThat(usuarios.porUsuario(u).orElseThrow().sesionUltimo()).isEqualTo(primera);
        reloj.adelantar(Duration.ofSeconds(25));
        n.get("/api/sesion");
        assertThat(usuarios.porUsuario(u).orElseThrow().sesionUltimo()).isAfter(primera);
    }
}
