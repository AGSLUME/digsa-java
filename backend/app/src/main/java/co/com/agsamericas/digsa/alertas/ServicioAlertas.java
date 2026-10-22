package co.com.agsamericas.digsa.alertas;

import co.com.agsamericas.digsa.catalogos.ServicioCatalogos;
import co.com.agsamericas.digsa.identidad.CuentaBloqueada;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.ArchivoIntegridadFallida;
import co.com.agsamericas.digsa.plataforma.BandejaSalida;
import co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa;
import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.plataforma.CorreoAgotado;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.PlantillaCorreo;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.plataforma.Sms;
import co.com.agsamericas.digsa.reglas.Alertas;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.reglas.Perfil;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Alertas (spec exportacion-alertas, «Alertas críticas por correo y además por SMS»; GMI P75 y P77 b). {@link #disparar} crea o
 * actualiza el evento y encola en la bandeja de salida, en la MISMA transacción de quien la dispara, el correo a los destinatarios del
 * tipo (perfiles y usuarios activos) y, si el tipo es crítico con SMS, un SMS a cada uno con móvil válido (el que no tiene queda
 * registrado «sin teléfono»). El {@code DespachadorSalida} los envía en segundos después de confirmar.
 *
 * <p>Una alerta crítica queda abierta hasta que se resuelve; mientras tanto un nuevo disparo con la misma clave no repite el correo ni
 * el SMS antes de una hora. Una operativa (solo correo) avisa una vez por clave. Disparar nunca tumba la operación de quien la dispara:
 * va en un punto de guardado (NESTED) y un error queda en el registro.
 */
@Service
public class ServicioAlertas {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioAlertas.class);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** Perfiles internos que pueden recibir alertas (el OPL y el ESM no: las alertas son de la operación de la interventoría). */
    static final List<Perfil> PERFILES_DESTINO = List.of(Perfil.ADMINISTRADOR, Perfil.COORDINADOR, Perfil.DIGSA, Perfil.ANALISTA, Perfil.AUDITOR,
            Perfil.SANIDAD);
    static final int UMBRAL_BLOQUEO_DEFECTO = 5;

    /** Resultado de un disparo: el evento, si se avisó (o lo frenó la deduplicación) y cuántos correos, SMS y «sin teléfono». */
    public record Disparo(Long eventoId, boolean avisado, int correos, int sms, int sinTelefono) {
        static Disparo nada() {
            return new Disparo(null, false, 0, 0, 0);
        }
    }

    /** Un tipo del catálogo. */
    public record Tipo(String tipo, String nombre, String descripcion, boolean critica, boolean activa, boolean sms, List<String> perfiles,
                       List<Long> usuarios) {
    }

    record Destinatario(long id, String correo, String telefono) {
    }

    private final JdbcClient jdbc;
    private final BandejaSalida bandeja;
    private final RegistroBitacora bitacora;
    private final ServicioCatalogos catalogos;
    private final ConfiguracionDigsa cfg;
    private final Correo correo;
    private final Sms sms;
    private final Clock reloj;
    /** Punto de guardado dentro de la transacción de quien dispara (o transacción nueva si no hay): un error no la tumba. */
    private final TransactionTemplate anidada;
    /** Transacción propia: para los disparos desde eventos (una exportación de solo lectura, el despachador). */
    private final TransactionTemplate aparte;
    /** Último aviso enviado sin base de datos, por tipo y clave (solo en memoria: la base no responde). */
    private final Map<String, Instant> avisosSinBase = new ConcurrentHashMap<>();
    /** Destinatarios conocidos de cada tipo, para avisar cuando la base no responde. */
    private final Map<String, List<Destinatario>> destinatariosConocidos = new ConcurrentHashMap<>();
    private final Map<String, Tipo> tiposConocidos = new ConcurrentHashMap<>();
    /** Duración del bloqueo por intentos (la informa el evento; 15 min en el Anexo 5). */
    private volatile Duration duracionBloqueo = Duration.ofMinutes(15);

    public ServicioAlertas(JdbcClient jdbc, BandejaSalida bandeja, RegistroBitacora bitacora, ServicioCatalogos catalogos, ConfiguracionDigsa cfg,
                           Correo correo, Sms sms, Clock reloj, PlatformTransactionManager transacciones) {
        this.jdbc = jdbc;
        this.bandeja = bandeja;
        this.bitacora = bitacora;
        this.catalogos = catalogos;
        this.cfg = cfg;
        this.correo = correo;
        this.sms = sms;
        this.reloj = reloj;
        this.anidada = new TransactionTemplate(transacciones);
        this.anidada.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.aparte = new TransactionTemplate(transacciones);
        this.aparte.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Dispara una alerta dentro de la transacción de quien llama (si la hay): si esa transacción se revierte, no sale nada.
     *
     * @param clave   deduplicación (p. ej. «acta:15», «opl:2026-10-22»)
     * @param detalle sin datos personales: identificadores, números de visita, nombres de puntos
     */
    public Disparo disparar(String tipo, String clave, String detalle) {
        return ejecutar(anidada, false, tipo, clave, detalle);
    }

    /** Dispara en una transacción propia, independiente de la de quien llama. */
    public Disparo dispararAparte(String tipo, String clave, String detalle) {
        return ejecutar(aparte, true, tipo, clave, detalle);
    }

    /** Integridad de un archivo (huella distinta o faltante): la exportación o el acta la avisan por aquí o con el evento. */
    public Disparo integridadArchivo(String detalle) {
        String d = detalle == null || detalle.isBlank() ? "Archivo sin integridad" : detalle;
        return dispararAparte(Alertas.INTEGRIDAD_ARCHIVO, "huella:" + Huellas.sha256(d).substring(0, 16), d);
    }

    @EventListener
    public void alFallarIntegridad(ArchivoIntegridadFallida e) {
        integridadArchivo(e.detalle());
    }

    /** Un aviso crítico agotó sus intentos. Sin bucle: el correo de una alerta «correo_agotado» que se agota no dispara otra. */
    @EventListener
    public void alAgotarseCorreo(CorreoAgotado e) {
        if (("alerta:" + Alertas.CORREO_AGOTADO).equals(e.origen())) {
            LOG.error("[alertas] El aviso de «correo agotado» (correo {}) tampoco salió: revise el servidor de correo", e.correoId());
            return;
        }
        dispararAparte(Alertas.CORREO_AGOTADO, "correo:" + e.correoId(),
                "El aviso «" + e.origen() + "» (correo " + e.correoId() + " de la bandeja) no se entregó tras " + Alertas.MAX_INTENTOS + " intentos");
    }

    /** Bloqueo masivo: N cuentas (parámetro alertas_bloqueo_cuentas, 5) bloqueadas por intentos en la última hora. */
    @EventListener
    public void alBloquearseCuenta(CuentaBloqueada e) {
        if (e.bloqueadaHasta().isAfter(e.cuando())) {
            duracionBloqueo = Duration.between(e.cuando(), e.bloqueadaHasta());
        }
        int umbral = umbralBloqueo();
        long n = bloqueosEnLaHora(e.cuando());
        if (Alertas.bloqueoMasivo(n, umbral)) {
            dispararAparte(Alertas.BLOQUEO_MASIVO, "cuentas", n + " cuentas bloqueadas por intentos fallidos en la última hora (umbral " + umbral + ")");
        }
    }

    int umbralBloqueo() {
        return catalogos.entero("alertas_bloqueo_cuentas", UMBRAL_BLOQUEO_DEFECTO);
    }

    /** Cuentas cuyo bloqueo empezó en la última hora (el bloqueo dura {@link #duracionBloqueo} desde que empieza). */
    long bloqueosEnLaHora(Instant ahora) {
        return jdbc.sql("SELECT count(*) FROM usuarios WHERE bloqueado_hasta >= :desde")
                .param("desde", OffsetDateTime.ofInstant(ahora.minus(Alertas.REPETICION).plus(duracionBloqueo), Fechas.BOGOTA)).query(Long.class).single();
    }

    /** El bloqueo masivo abierto se cierra cuando ya no hay N cuentas bloqueadas en la última hora (lo revisa el monitor). */
    void revisarBloqueoMasivo() {
        int umbral = umbralBloqueo();
        if (!Alertas.bloqueoMasivo(bloqueosEnLaHora(reloj.instant()), umbral)) {
            cerrar(Alertas.BLOQUEO_MASIVO, "cuentas", "Menos de " + Math.max(umbral, 1) + " cuentas bloqueadas en la última hora");
        }
    }

    private Disparo ejecutar(TransactionTemplate t, boolean propia, String tipo, String clave, String detalle) {
        try {
            Disparo d = t.execute(s -> enTransaccion(tipo, clave, detalle));
            if (d != null && d.eventoId() != null && d.avisado()) {
                Tipo ti = tiposConocidos.get(tipo);
                boolean critica = ti != null && ti.critica();
                String texto = "Alerta " + tipo + " · evento " + d.eventoId() + " · " + Bitacora.cortar(Bitacora.sinCorreos(detalle), 300) + " · correo a "
                        + d.correos() + " destinatario(s)" + (critica ? " · SMS " + d.sms() + (d.sinTelefono() > 0 ? " · sin teléfono " + d.sinTelefono() : "") : "");
                despuesDeConfirmar(propia, () -> bitacora.escribir(Bitacora.operacionDeTipo("alerta_critica"), critica ? "alerta_critica" : "alerta_operativa",
                        "alertas", texto, null, "sistema", null));
            }
            return d == null ? Disparo.nada() : d;
        } catch (RuntimeException e) {
            LOG.error("[alertas] No se pudo disparar la alerta {} ({})", tipo, clave, e);
            return Disparo.nada();
        }
    }

    /** Con transacción de quien llama, después de que confirme (si se revierte, la bitácora no dice que se avisó). */
    private static void despuesDeConfirmar(boolean propia, Runnable r) {
        if (!propia && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }

    private Disparo enTransaccion(String tipo, String clave, String detalle) {
        Tipo t = tipo(tipo).orElse(null);
        if (t == null) {
            LOG.warn("[alertas] Tipo de alerta desconocido: {}", tipo);
            return Disparo.nada();
        }
        tiposConocidos.put(tipo, t);
        if (!t.activa()) {
            return Disparo.nada();
        }
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        String det = Bitacora.cortar(Bitacora.sinCorreos(detalle == null || detalle.isBlank() ? t.nombre() : detalle.strip()), 500);
        String cl = Bitacora.cortar(clave == null || clave.isBlank() ? "-" : clave.strip(), 120);
        long id;
        Instant ultimoAviso = null;
        Instant ultimoSms = null;
        if (t.critica()) {
            // Abierta y única por (tipo, clave): un nuevo disparo solo suma; la fila queda bloqueada hasta confirmar (dos disparos a la vez no avisan dos veces)
            Map<String, Object> f = jdbc.sql("""
                    INSERT INTO alertas_eventos (tipo, clave, detalle, creado, ultimo) VALUES (:t, :c, :d, :ahora, :ahora)
                    ON CONFLICT (tipo, clave) WHERE abierta DO UPDATE SET veces = alertas_eventos.veces + 1, ultimo = EXCLUDED.ultimo, detalle = EXCLUDED.detalle
                    RETURNING id, ultimo_aviso, ultimo_sms""")
                    .param("t", tipo).param("c", cl).param("d", det).param("ahora", ahora).query().singleRow();
            id = ((Number) f.get("id")).longValue();
            ultimoAviso = instante(f.get("ultimo_aviso"));
            ultimoSms = instante(f.get("ultimo_sms"));
        } else {
            // Operativa: un aviso por clave; nace cerrada
            Optional<Long> nuevo = jdbc.sql("""
                    INSERT INTO alertas_eventos (tipo, clave, detalle, abierta, creado, ultimo, cerrada_en)
                    SELECT :t, :c, :d, false, :ahora, :ahora, :ahora WHERE NOT EXISTS (SELECT 1 FROM alertas_eventos WHERE tipo = :t AND clave = :c)
                    RETURNING id""")
                    .param("t", tipo).param("c", cl).param("d", det).param("ahora", ahora).query(Long.class).optional();
            if (nuevo.isEmpty()) {
                return new Disparo(null, false, 0, 0, 0);
            }
            id = nuevo.get();
        }
        boolean correoAhora = Alertas.avisarDeNuevo(ultimoAviso, ahora.toInstant());
        boolean smsAhora = t.critica() && t.sms() && Alertas.avisarDeNuevo(ultimoSms, ahora.toInstant());
        if (!correoAhora && !smsAhora) {
            return new Disparo(id, false, 0, 0, 0); // abierta y ya avisada en la última hora
        }
        List<Destinatario> dest = destinatarios(t);
        destinatariosConocidos.put(tipo, dest);
        int correos = 0;
        if (correoAhora) {
            List<String> para = dest.stream().map(d -> Correos.normalizar(d.correo())).filter(Correos::valido).distinct().toList();
            if (para.isEmpty()) {
                LOG.error("[alertas] La alerta {} no tiene destinatarios con correo: revise el catálogo de alertas", tipo);
            } else {
                bandeja.encolar("alerta:" + tipo, "alerta:" + id, mensaje(t, det, ahora, para), List.of(), id, t.critica());
                correos = para.size();
            }
        }
        int enviados = 0;
        int sinTelefono = 0;
        if (smsAhora) {
            String texto = Alertas.textoSms(t.nombre(), ahora.atZoneSameInstant(Fechas.BOGOTA).toLocalDateTime());
            for (Destinatario d : dest) {
                Optional<String> tel = Alertas.normalizarTelefono(d.telefono());
                if (tel.isPresent()) {
                    bandeja.encolarSms(id, d.id(), Alertas.enmascararTelefono(tel.get()), texto);
                    enviados++;
                } else {
                    bandeja.smsOmitido(id, d.id(), "***", texto, "Sin teléfono móvil válido");
                    sinTelefono++;
                }
            }
        }
        jdbc.sql("""
                UPDATE alertas_eventos SET ultimo_aviso = CASE WHEN :c THEN :ahora ELSE ultimo_aviso END,
                  ultimo_sms = CASE WHEN :s THEN :ahora ELSE ultimo_sms END WHERE id = :id""")
                .param("c", correoAhora).param("s", smsAhora).param("ahora", ahora).param("id", id).update();
        return new Disparo(id, true, correos, enviados, sinTelefono);
    }

    private Correo.Mensaje mensaje(Tipo t, String detalle, OffsetDateTime ahora, List<String> para) {
        String hora = ahora.atZoneSameInstant(Fechas.BOGOTA).format(HORA);
        String titulo = (t.critica() ? "Alerta crítica · " : "Aviso · ") + t.nombre();
        String cuerpo = "<p><strong>" + PlantillaCorreo.e(t.nombre()) + "</strong> (" + PlantillaCorreo.e(hora) + ")</p>"
                + "<p>" + PlantillaCorreo.texto(detalle) + "</p>"
                + (t.critica() && t.sms() ? "<p>Es una alerta crítica: también se envió un SMS a los destinatarios con móvil registrado.</p>" : "")
                + "<p>Revise la Suite DIGSA: <a href=\"" + PlantillaCorreo.e(cfg.enlace("")) + "\">" + PlantillaCorreo.e(cfg.enlace("")) + "</a></p>";
        return new Correo.Mensaje(para, List.of(), titulo + " · " + hora, PlantillaCorreo.marco(titulo, cuerpo), List.of());
    }

    /** Destinatarios activos del tipo: los usuarios elegidos y los de los perfiles elegidos. */
    List<Destinatario> destinatarios(Tipo t) {
        Integer[] perfiles = t.perfiles().stream().map(p -> {
            try {
                return Perfil.valueOf(p).id();
            } catch (IllegalArgumentException e) {
                return -1;
            }
        }).toArray(Integer[]::new);
        return jdbc.sql("""
                SELECT u.id, u.correo, u.telefono FROM usuarios u
                WHERE u.activo AND (u.id = ANY(:u) OR EXISTS (SELECT 1 FROM usuarios_perfiles up WHERE up.usuario_id = u.id AND up.perfil_id = ANY(:p)))
                ORDER BY u.id LIMIT 200""")
                .param("u", t.usuarios().toArray(Long[]::new)).param("p", perfiles)
                .query((rs, n) -> new Destinatario(rs.getLong("id"), rs.getString("correo"), rs.getString("telefono"))).list();
    }

    /** El sistema cierra la alerta abierta del tipo y la clave (p. ej. el monitor al recuperarse). @return si había una abierta */
    public boolean cerrar(String tipo, String clave, String motivo) {
        Optional<Long> id = jdbc.sql("""
                UPDATE alertas_eventos SET abierta = false, cerrada_en = :ahora WHERE tipo = :t AND clave = :c AND abierta RETURNING id""")
                .param("ahora", OffsetDateTime.now(reloj)).param("t", tipo).param("c", clave).query(Long.class).optional();
        id.ifPresent(i -> bitacora.escribir(Bitacora.operacionDeTipo("alerta_cerrada"), "alerta_cerrada", "alertas",
                "Alerta " + tipo + " · evento " + i + " cerrada" + (motivo == null ? "" : ": " + Bitacora.cortar(motivo, 200)), null, "sistema", null));
        return id.isPresent();
    }

    /** Un usuario cierra una alerta abierta (la revisó y la resolvió). */
    public void cerrarEvento(long id, UsuarioSesion quien) {
        Optional<String> tipo = jdbc.sql("""
                UPDATE alertas_eventos SET abierta = false, cerrada_en = :ahora, cerrada_por = :u WHERE id = :id AND abierta RETURNING tipo""")
                .param("ahora", OffsetDateTime.now(reloj)).param("u", quien.id()).param("id", id).query(String.class).optional();
        if (tipo.isEmpty()) {
            throw ErrorNegocio.validacion("La alerta no existe o ya está cerrada.");
        }
        bitacora.registrar("alerta_cerrada", "Alerta " + tipo.get() + " · evento " + id + " cerrada por el usuario", quien.id(), quien.usuario());
    }

    /**
     * La base no responde: no se puede encolar. Se avisa directo por los puertos (correo y SMS) a los últimos destinatarios conocidos, como
     * mucho una vez por hora en esta réplica.
     */
    void avisoSinBase(String tipo, String clave, String detalle) {
        Instant ahora = reloj.instant();
        String llave = tipo + "|" + clave;
        if (!Alertas.avisarDeNuevo(avisosSinBase.get(llave), ahora)) {
            return;
        }
        avisosSinBase.put(llave, ahora);
        Tipo t = tiposConocidos.get(tipo);
        List<Destinatario> dest = destinatariosConocidos.getOrDefault(tipo, List.of());
        LOG.error("[alertas] {}: {} (aviso directo a {} destinatario(s) conocidos)", tipo, detalle, dest.size());
        if (t == null || !t.activa() || dest.isEmpty()) {
            return;
        }
        OffsetDateTime hora = OffsetDateTime.now(reloj);
        List<String> para = dest.stream().map(d -> Correos.normalizar(d.correo())).filter(Correos::valido).distinct().toList();
        if (!para.isEmpty()) {
            Correo.Resultado r = correo.enviar(mensaje(t, detalle, hora, para));
            if (!r.ok()) {
                LOG.error("[alertas] El aviso directo de {} no salió: {}", tipo, r.error());
            }
        }
        if (t.critica() && t.sms()) {
            String texto = Alertas.textoSms(t.nombre(), hora.atZoneSameInstant(Fechas.BOGOTA).toLocalDateTime());
            for (Destinatario d : dest) {
                Alertas.normalizarTelefono(d.telefono()).ifPresent(tel -> {
                    Sms.Resultado r = sms.enviar(tel, texto);
                    if (!r.ok()) {
                        LOG.error("[alertas] SMS directo de {} a {} no salió: {}", tipo, Alertas.enmascararTelefono(tel), r.error());
                    }
                });
            }
        }
    }

    /** Guarda en memoria el tipo y sus destinatarios vigentes, para poder avisar si la base deja de responder. */
    void recordarDestinatarios(String tipo) {
        tipo(tipo).ifPresent(t -> {
            tiposConocidos.put(tipo, t);
            destinatariosConocidos.put(tipo, destinatarios(t));
        });
    }

    /** Deja constancia, ya cerrada y sin avisar de nuevo, de una caída que no se pudo registrar mientras ocurría (la base no respondía). */
    void registrarResuelta(String tipo, String clave, String detalle, Instant desde) {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        jdbc.sql("""
                INSERT INTO alertas_eventos (tipo, clave, detalle, abierta, creado, ultimo, ultimo_aviso, cerrada_en) VALUES (:t, :c, :d, false, :desde, :ahora, :desde, :ahora)""")
                .param("t", tipo).param("c", clave).param("d", Bitacora.cortar(detalle, 500)).param("desde", OffsetDateTime.ofInstant(desde, Fechas.BOGOTA))
                .param("ahora", ahora).update();
        bitacora.escribir(Bitacora.operacionDeTipo("alerta_cerrada"), "alerta_cerrada", "alertas", "Alerta " + tipo + " resuelta: " + Bitacora.cortar(detalle, 300),
                null, "sistema", null);
    }

    // ---------- Catálogo ----------

    public Optional<Tipo> tipo(String tipo) {
        return jdbc.sql("SELECT tipo, nombre, descripcion, critica, activa, sms, perfiles, usuarios FROM alertas_criticas WHERE tipo = :t").param("t", tipo)
                .query(ServicioAlertas::tipoDe).optional();
    }

    public List<Tipo> catalogo() {
        return jdbc.sql("SELECT tipo, nombre, descripcion, critica, activa, sms, perfiles, usuarios FROM alertas_criticas ORDER BY critica DESC, tipo")
                .query(ServicioAlertas::tipoDe).list();
    }

    static Tipo tipoDe(ResultSet rs, int n) throws SQLException {
        Array p = rs.getArray("perfiles");
        Array u = rs.getArray("usuarios");
        return new Tipo(rs.getString("tipo"), rs.getString("nombre"), rs.getString("descripcion"), rs.getBoolean("critica"), rs.getBoolean("activa"),
                rs.getBoolean("sms"), p == null ? List.of() : List.of((String[]) p.getArray()),
                u == null ? List.of() : Arrays.stream((Object[]) u.getArray()).map(x -> ((Number) x).longValue()).toList());
    }

    private static Instant instante(Object v) {
        if (v instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        if (v instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        return null;
    }

    static List<String> nombresPerfiles() {
        List<String> r = new ArrayList<>();
        PERFILES_DESTINO.forEach(p -> r.add(p.name()));
        return r;
    }
}
