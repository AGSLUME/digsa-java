package co.com.agsamericas.digsa.alertas;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.plataforma.ArchivoIntegridadFallida;
import co.com.agsamericas.digsa.plataforma.Sms;
import co.com.agsamericas.digsa.plataforma.SmsArchivo;
import co.com.agsamericas.digsa.reglas.Alertas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.soporte.Navegador;
import co.com.agsamericas.digsa.soporte.Navegador.Respuesta;
import co.com.agsamericas.digsa.soporte.PruebaIntegracion;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Alertas críticas y operativas (spec exportacion-alertas): correo + SMS a los destinatarios del catálogo, «sin teléfono», SMS no más
 * de una vez por hora con la alerta abierta, monitor de salud que abre y cierra, bloqueo masivo, catálogo solo del administrador y
 * teléfono móvil en E.164 al guardar un usuario.
 */
class AlertasApiTest extends PruebaIntegracion {

    @Autowired
    ServicioAlertas alertas;
    @Autowired
    Almacen almacen;
    @Autowired
    Sms sms;
    @Autowired
    DataSource ds;
    @Autowired
    ApplicationEventPublisher eventos;

    /** Catálogo original de los tipos que la prueba restringe a sus propios destinatarios. */
    private final Map<String, Map<String, Object>> originales = new HashMap<>();

    /** Deja el tipo solo para los usuarios de la prueba (no molesta a los administradores de las demás pruebas). */
    private void soloPara(String tipo, long... usuarios) {
        originales.computeIfAbsent(tipo, t -> jdbc.sql("SELECT perfiles, usuarios, activa, sms FROM alertas_criticas WHERE tipo = :t").param("t", t)
                .query((rs, n) -> Map.<String, Object>of("perfiles", rs.getArray("perfiles").getArray(), "usuarios", rs.getArray("usuarios").getArray(),
                        "activa", rs.getBoolean("activa"), "sms", rs.getBoolean("sms")))
                .single());
        Long[] ids = java.util.Arrays.stream(usuarios).boxed().toArray(Long[]::new);
        jdbc.sql("UPDATE alertas_criticas SET perfiles = '{}', usuarios = :u, activa = true WHERE tipo = :t").param("u", ids).param("t", tipo).update();
    }

    @AfterEach
    void restaurarCatalogo() {
        originales.forEach((t, f) -> jdbc.sql("UPDATE alertas_criticas SET perfiles = :p, usuarios = :u, activa = :a, sms = :s WHERE tipo = :t")
                .param("p", f.get("perfiles")).param("u", f.get("usuarios")).param("a", f.get("activa")).param("s", f.get("sms")).param("t", t).update());
        originales.clear();
        jdbc.sql("UPDATE alertas_eventos SET abierta = false, cerrada_en = now() WHERE abierta AND tipo IN ('almacen_no_disponible', 'bloqueo_masivo')").update();
    }

    private long conTelefono(String usuario, String telefono) {
        long id = idDe(usuario);
        jdbc.sql("UPDATE usuarios SET telefono = :t WHERE id = :id").param("t", telefono).param("id", id).update();
        return id;
    }

    private long smsDe(long eventoId, String estado) {
        return jdbc.sql("SELECT count(*) FROM sms_salida WHERE alerta_id = :a AND estado = :e").param("a", eventoId).param("e", estado).query(Long.class).single();
    }

    @Test
    @DisplayName("crítica: correo y SMS en segundos; sin teléfono queda registrado; abierta no repite el SMS antes de una hora")
    void criticaConSmsYDeduplicacion() {
        String conMovil = crearUsuario("alertaconmovil", Perfil.ADMINISTRADOR);
        String sinMovil = crearUsuario("alertasinmovil", Perfil.ADMINISTRADOR);
        long a = conTelefono(conMovil, "+573000000001");
        long b = conTelefono(sinMovil, "6015550000"); // fijo: no recibe SMS
        soloPara(Alertas.TAREA_DIARIA_FALLIDA, a, b);
        String clave = unico("prueba:");

        long t0 = System.nanoTime();
        ServicioAlertas.Disparo d = alertas.disparar(Alertas.TAREA_DIARIA_FALLIDA, clave, "La tarea diaria del OPL terminó con 2 reportes sin enviar");
        assertThat(d.avisado()).isTrue();
        assertThat(d.correos()).isEqualTo(2);
        assertThat(d.sms()).isEqualTo(1);
        assertThat(d.sinTelefono()).isEqualTo(1);
        esperarCorreo(m -> m.asunto().startsWith("Alerta crítica · Tarea diaria con fallos") && m.para().contains(conMovil + "@example.com"));
        esperar(() -> smsDe(d.eventoId(), "enviado") == 1, "SMS enviado");
        long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        System.out.println("[latencia] alerta crítica: disparo → correo y SMS entregados a los adaptadores en " + ms + " ms");
        assertThat(ms).isLessThan(3000);

        Map<String, Object> fila = jdbc.sql("SELECT telefono, texto FROM sms_salida WHERE alerta_id = :a AND estado = 'enviado'").param("a", d.eventoId()).query()
                .singleRow();
        assertThat(fila.get("telefono")).as("solo enmascarado").isEqualTo("+573*******01");
        String texto = (String) fila.get("texto");
        assertThat(texto).startsWith("Alerta critica DIGSA: Tarea diaria con fallos").endsWith("Revise la Suite DIGSA").hasSizeLessThanOrEqualTo(160)
                .doesNotContain("OPL").doesNotContain("http");
        assertThat(Alertas.esGsm7(texto)).isTrue();
        assertThat(((SmsArchivo) sms).ultimos()).anyMatch(x -> x.telefono().equals("+573000000001") && x.texto().equals(texto));
        assertThat(jdbc.sql("SELECT error FROM sms_salida WHERE alerta_id = :a AND estado = 'omitido'").param("a", d.eventoId()).query(String.class).single())
                .isEqualTo("Sin teléfono móvil válido");
        assertThat(jdbc.sql("SELECT count(*) FROM log_transacciones WHERE tipo = 'alerta_critica' AND descripcion LIKE :d")
                .param("d", "Alerta tarea_diaria_fallida · evento " + d.eventoId() + " ·%").query(Long.class).single()).isEqualTo(1);

        // Misma alerta abierta a los 59 minutos: solo cuenta, sin correo ni SMS
        reloj.adelantar(Duration.ofMinutes(59));
        ServicioAlertas.Disparo otra = alertas.disparar(Alertas.TAREA_DIARIA_FALLIDA, clave, "Sigue fallando");
        assertThat(otra.eventoId()).isEqualTo(d.eventoId());
        assertThat(otra.avisado()).isFalse();
        assertThat(smsDe(d.eventoId(), "enviado") + smsDe(d.eventoId(), "pendiente")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT veces FROM alertas_eventos WHERE id = :id").param("id", d.eventoId()).query(Integer.class).single()).isEqualTo(2);

        // Pasada la hora, vuelve a avisar
        reloj.adelantar(Duration.ofMinutes(2));
        ServicioAlertas.Disparo tercera = alertas.disparar(Alertas.TAREA_DIARIA_FALLIDA, clave, "Sigue fallando");
        assertThat(tercera.avisado()).isTrue();
        esperar(() -> smsDe(d.eventoId(), "enviado") == 2, "segundo SMS después de la hora");

        // Cerrada, un disparo nuevo con la misma clave abre otra
        assertThat(alertas.cerrar(Alertas.TAREA_DIARIA_FALLIDA, clave, "resuelta en la prueba")).isTrue();
        assertThat(alertas.disparar(Alertas.TAREA_DIARIA_FALLIDA, clave, "Otra vez").eventoId()).isNotEqualTo(d.eventoId());
        alertas.cerrar(Alertas.TAREA_DIARIA_FALLIDA, clave, null);
    }

    @Test
    @DisplayName("integridad de archivo por el evento de plataforma; un tipo inactivo no avisa")
    void integridadPorEventoEInactiva() {
        long a = conTelefono(crearUsuario("alertaintegridad", Perfil.ADMINISTRADOR), "3000000002");
        soloPara(Alertas.INTEGRIDAD_ARCHIVO, a);
        String detalle = unico("archivos id 77 de la visita V-PRUEBA-");
        eventos.publishEvent(new ArchivoIntegridadFallida(detalle));
        Long id = jdbc.sql("SELECT id FROM alertas_eventos WHERE tipo = 'integridad_archivo' AND detalle = :d").param("d", detalle).query(Long.class).single();
        esperar(() -> smsDe(id, "enviado") == 1, "SMS de integridad");
        alertas.cerrar(Alertas.INTEGRIDAD_ARCHIVO, jdbc.sql("SELECT clave FROM alertas_eventos WHERE id = :id").param("id", id).query(String.class).single(), null);

        jdbc.sql("UPDATE alertas_criticas SET activa = false WHERE tipo = 'integridad_archivo'").update();
        assertThat(alertas.integridadArchivo(unico("inactiva ")).eventoId()).isNull();
    }

    /** Almacén que no responde. */
    private static final Almacen CAIDO = new Almacen() {
        @Override
        public void guardar(String ruta, byte[] contenido) throws IOException {
            throw new IOException("disco no disponible");
        }

        @Override
        public InputStream leer(String ruta) throws IOException {
            throw new IOException("disco no disponible");
        }

        @Override
        public java.io.OutputStream escribir(String ruta) throws IOException {
            throw new IOException("disco no disponible");
        }

        @Override
        public void descartar(String ruta) {
        }
    };

    private boolean abierta(String clave) {
        return jdbc.sql("SELECT count(*) FROM alertas_eventos WHERE tipo = 'almacen_no_disponible' AND clave = :c AND abierta").param("c", clave)
                .query(Long.class).single() == 1;
    }

    @Test
    @DisplayName("monitor de salud: el almacén cae → alerta abierta con correo y SMS; vuelve → se cierra sola")
    void monitorAlmacen() {
        String adm = crearUsuario("alertasalud", Perfil.ADMINISTRADOR);
        long a = conTelefono(adm, "300 000 0003");
        soloPara(Alertas.ALMACEN_NO_DISPONIBLE, a);
        MonitorSalud caido = new MonitorSalud(jdbc, CAIDO, alertas, reloj);
        assertThat(caido.verificar()).isEqualTo(new MonitorSalud.Estado(true, false));
        assertThat(abierta(MonitorSalud.ALMACEN)).isTrue();
        esperarCorreo(m -> m.para().contains(adm + "@example.com") && m.asunto().startsWith("Alerta crítica · Base o almacén sin respuesta"));
        Long id = jdbc.sql("SELECT id FROM alertas_eventos WHERE tipo = 'almacen_no_disponible' AND clave = 'almacen' AND abierta").query(Long.class).single();
        esperar(() -> smsDe(id, "enviado") == 1, "SMS del almacén");
        caido.verificar();
        assertThat(smsDe(id, "enviado") + smsDe(id, "pendiente")).as("cada minuto sigue caído, pero no repite el SMS").isEqualTo(1);

        MonitorSalud sano = new MonitorSalud(jdbc, almacen, alertas, reloj);
        assertThat(sano.verificar()).isEqualTo(new MonitorSalud.Estado(true, true));
        assertThat(abierta(MonitorSalud.ALMACEN)).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM log_transacciones WHERE tipo = 'alerta_cerrada' AND descripcion LIKE :d")
                .param("d", "Alerta almacen_no_disponible · evento " + id + " cerrada%").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("monitor de salud: la base no responde → aviso directo (sin bandeja) y, al volver, constancia cerrada en los eventos")
    void monitorBase() {
        String adm = crearUsuario("alertabase", Perfil.ADMINISTRADOR);
        long a = conTelefono(adm, "3000000004");
        soloPara(Alertas.ALMACEN_NO_DISPONIBLE, a);
        AtomicBoolean caida = new AtomicBoolean(false);
        DataSource intermitente = new DelegatingDataSource(ds) {
            @Override
            public Connection getConnection() throws SQLException {
                if (caida.get()) {
                    throw new SQLException("conexión rechazada (simulada)");
                }
                return super.getConnection();
            }
        };
        MonitorSalud m = new MonitorSalud(JdbcClient.create(intermitente), almacen, alertas, reloj);
        m.verificar(); // conoce a los destinatarios
        int smsAntes = ((SmsArchivo) sms).ultimos().size();
        caida.set(true);
        assertThat(m.verificar().base()).isFalse();
        assertThat(correos()).anyMatch(x -> x.para().contains(adm + "@example.com") && x.html().contains("La base de datos no responde"));
        assertThat(((SmsArchivo) sms).ultimos().subList(smsAntes, ((SmsArchivo) sms).ultimos().size())).anyMatch(x -> x.telefono().equals("+573000000004"));
        caida.set(false);
        assertThat(m.verificar().base()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM alertas_eventos WHERE tipo = 'almacen_no_disponible' AND clave = 'base' AND NOT abierta AND detalle LIKE '%se recuperó%'")
                .query(Long.class).single()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("bloqueo masivo: N cuentas bloqueadas por intentos en una hora disparan la alerta crítica")
    void bloqueoMasivo() {
        long a = conTelefono(crearUsuario("alertabloqueo", Perfil.ADMINISTRADOR), "3000000005");
        soloPara(Alertas.BLOQUEO_MASIVO, a);
        String antes = jdbc.sql("SELECT valor FROM par_parametros WHERE clave = 'alertas_bloqueo_cuentas'").query(String.class).single();
        jdbc.sql("UPDATE par_parametros SET valor = '2' WHERE clave = 'alertas_bloqueo_cuentas'").update();
        try {
            for (String u : List.of(crearUsuario("victima", Perfil.AUDITOR), crearUsuario("victima", Perfil.AUDITOR))) {
                Navegador n = navegador();
                for (int i = 0; i < 5; i++) {
                    assertThat(n.ingresar(u, "Clave.Equivocada.1").estado()).isEqualTo(401);
                }
            }
            assertThat(jdbc.sql("SELECT count(*) FROM alertas_eventos WHERE tipo = 'bloqueo_masivo' AND abierta").query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT detalle FROM alertas_eventos WHERE tipo = 'bloqueo_masivo' AND abierta").query(String.class).single())
                    .contains("cuentas bloqueadas").doesNotContain("victima");
        } finally {
            jdbc.sql("UPDATE par_parametros SET valor = :v WHERE clave = 'alertas_bloqueo_cuentas'").param("v", antes).update();
        }
    }

    private Navegador entrar(String usuario) {
        Navegador n = navegador();
        assertThat(n.ingresar(usuario, CLAVE).estado()).isEqualTo(200);
        return n;
    }

    @Test
    @DisplayName("API: catálogo solo del administrador (403 a la coordinación) y validado; eventos con latencia para administrador y coordinación")
    void apiCatalogoYEventos() {
        Navegador coord = entrar(crearUsuario("alertacoord", Perfil.COORDINADOR));
        assertThat(coord.get("/api/alertas/catalogo").estado()).isEqualTo(403);
        assertThat(coord.put("/api/alertas/catalogo", Map.of("tipo", "bloqueo_masivo", "activa", false)).estado()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT activa FROM alertas_criticas WHERE tipo = 'bloqueo_masivo'").query(Boolean.class).single()).isTrue();

        String admUsuario = crearUsuario("alertaadmin", Perfil.ADMINISTRADOR);
        Navegador adm = entrar(admUsuario);
        JsonNode cat = adm.get("/api/alertas/catalogo").json();
        assertThat(cat.path("tipos").size()).isGreaterThanOrEqualTo(7);
        assertThat(cat.path("tipos").toString()).contains("tarea_diaria_fallida", "intervencion_inmediata", "plan_opl_vencido");

        soloPara(Alertas.PLAN_OPL_VENCIDO, idDe(admUsuario)); // para restaurarlo al final
        Map<String, Object> mal = new HashMap<>(Map.of("tipo", "plan_opl_vencido", "perfiles", List.of("OPL_NACIONAL")));
        assertThat(adm.put("/api/alertas/catalogo", mal).mensaje()).contains("Perfil no válido");
        assertThat(adm.put("/api/alertas/catalogo", Map.of("tipo", "plan_opl_vencido", "sms", true)).mensaje()).contains("solo por correo");
        assertThat(adm.put("/api/alertas/catalogo", Map.of("tipo", "plan_opl_vencido", "perfiles", List.of(), "usuarios", List.of())).mensaje())
                .contains("al menos un destinatario");
        assertThat(adm.put("/api/alertas/catalogo", Map.of("tipo", "plan_opl_vencido", "usuarios", List.of(999_999_999L))).mensaje()).contains("no existe");
        long opl = idDe(crearUsuario("alertaopl", Perfil.OPL_NACIONAL));
        assertThat(adm.put("/api/alertas/catalogo", Map.of("tipo", "plan_opl_vencido", "usuarios", List.of(opl))).mensaje())
                .as("un usuario solo del OPL no recibe alertas").contains("perfil interno");
        assertThat(adm.put("/api/alertas/catalogo", Map.of("tipo", "no_existe")).estado()).isEqualTo(404);
        Respuesta ok = adm.put("/api/alertas/catalogo", Map.of("tipo", "plan_opl_vencido", "perfiles", List.of("COORDINADOR", "DIGSA"), "usuarios", List.of()));
        assertThat(ok.estado()).as(ok.texto()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT array_to_string(perfiles, ',') FROM alertas_criticas WHERE tipo = 'plan_opl_vencido'").query(String.class).single())
                .isEqualTo("COORDINADOR,DIGSA");
        assertThat(jdbc.sql("SELECT descripcion FROM log_transacciones WHERE tipo = 'alerta_catalogo_editado' ORDER BY id DESC LIMIT 1").query(String.class).single())
                .contains("plan_opl_vencido");

        // Eventos con su latencia
        long a = conTelefono(admUsuario, "3000000006");
        soloPara(Alertas.TAREA_DIARIA_FALLIDA, a);
        String clave = unico("api:");
        long ev = alertas.disparar(Alertas.TAREA_DIARIA_FALLIDA, clave, "Prueba de la API de eventos").eventoId();
        esperar(() -> jdbc.sql("SELECT count(*) FROM correos_salida WHERE alerta_id = :a AND estado = 'enviado'").param("a", ev).query(Long.class).single() == 1,
                "correo de la alerta enviado");
        esperar(() -> smsDe(ev, "enviado") == 1, "SMS enviado");
        JsonNode lista = coord.get("/api/alertas/eventos?abiertas=true").json();
        JsonNode e = null;
        for (JsonNode x : lista.path("eventos")) {
            if (x.path("id").asLong() == ev) {
                e = x;
            }
        }
        assertThat(e).as("el evento está en la lista de abiertas").isNotNull();
        assertThat(e.path("correosEnviados").asInt()).isEqualTo(1);
        assertThat(e.path("smsEnviados").asInt()).isEqualTo(1);
        assertThat(e.path("latenciaCorreoMs").asLong()).isBetween(0L, 3000L);
        System.out.println("[latencia] API de eventos: correo " + e.path("latenciaCorreoMs").asLong() + " ms, SMS " + e.path("latenciaSmsMs").asLong() + " ms");
        assertThat(lista.path("bandeja24h").has("latenciaP95Ms")).isTrue();
        assertThat(lista.path("bandejaSms24h").has("latenciaP50Ms")).isTrue();
        assertThat(lista.path("bandejaSms24h").path("latenciaP95Ms").isNull()).as("hay SMS enviados en las últimas 24 h").isFalse();
        assertThat(coord.post("/api/alertas/eventos/" + ev + "/cerrar", null).estado()).isEqualTo(200);
        assertThat(coord.post("/api/alertas/eventos/" + ev + "/cerrar", null).estado()).as("ya cerrada").isEqualTo(422);
        assertThat(entrar(crearUsuario("alertaaud", Perfil.AUDITOR)).get("/api/alertas/eventos").estado()).isEqualTo(403);
    }

    @Test
    @DisplayName("operativa: dos disparos a la vez con la misma clave → un solo evento y un solo correo")
    void operativaConcurrenteUnSoloAviso() throws Exception {
        long a = idDe(crearUsuario("alertaconc", Perfil.COORDINADOR));
        soloPara(Alertas.PLAN_OPL_VENCIDO, a);
        String clave = unico("acta:conc-");
        java.util.concurrent.CountDownLatch salida = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService dos = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<ServicioAlertas.Disparo>> r = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                r.add(dos.submit(() -> {
                    salida.await();
                    return alertas.disparar(Alertas.PLAN_OPL_VENCIDO, clave, "Visita de prueba: plan vencido");
                }));
            }
            salida.countDown();
            long avisados = 0;
            for (var f : r) {
                avisados += f.get().avisado() ? 1 : 0;
            }
            assertThat(avisados).isEqualTo(1);
        } finally {
            dos.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM alertas_eventos WHERE tipo = 'plan_opl_vencido' AND clave = :c").param("c", clave).query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM correos_salida c JOIN alertas_eventos e ON e.id = c.alerta_id WHERE e.tipo = 'plan_opl_vencido' AND e.clave = :c""")
                .param("c", clave).query(Long.class).single()).isEqualTo(1);
        assertThat(alertas.disparar(Alertas.PLAN_OPL_VENCIDO, clave, "otra vez").avisado()).as("ni después").isFalse();
    }

    @Test
    @DisplayName("teléfono del usuario: móvil colombiano se guarda en E.164; otro valor 422; vacío y el valor anterior sin cambio se admiten")
    void telefonoDelUsuario() {
        Navegador adm = entrar(crearUsuario("alertatel", Perfil.ADMINISTRADOR));
        String u = unico("tel").toLowerCase();
        Map<String, Object> d = new HashMap<>();
        d.put("nombre", "Usuario de prueba " + u);
        d.put("usuario", u);
        d.put("correo", u + "@example.com");
        d.put("documento", "12345678");
        d.put("perfiles", List.of(2));
        d.put("telefono", "601 555 0000");
        Respuesta malo = adm.post("/api/usuarios", d);
        assertThat(malo.mensaje()).contains("móvil colombiano");
        assertThat(malo.json().path("campo").asText()).isEqualTo("telefono"); // UI-21: la interfaz marca el campo sin leer el mensaje
        d.put("telefono", "");
        Respuesta c = adm.post("/api/usuarios", d);
        assertThat(c.estado()).as(c.texto()).isIn(200, 201);
        long id = c.json().path("id").asLong();
        d.put("telefono", "300 000 0007");
        assertThat(adm.put("/api/usuarios/" + id, d).estado()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT telefono FROM usuarios WHERE id = :id").param("id", id).query(String.class).single()).isEqualTo("+573000000007");
        // Un usuario anterior a la validación con un fijo se puede seguir editando sin tocar el teléfono
        jdbc.sql("UPDATE usuarios SET telefono = 'Ext. 1234' WHERE id = :id").param("id", id).update();
        d.put("telefono", "Ext. 1234");
        d.put("nombre", "Usuario de prueba editado " + u);
        Respuesta e = adm.put("/api/usuarios/" + id, d);
        assertThat(e.estado()).as(e.texto()).isEqualTo(200);
    }
}
