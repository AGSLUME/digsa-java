package co.com.agsamericas.digsa.plataforma;

import co.com.agsamericas.digsa.reglas.Bitacora;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bandeja de salida (duda 20.6; spec exportacion-alertas, «Bandeja de salida con reintentos»): los avisos que no esperan respuesta en
 * pantalla se encolan en la MISMA transacción del cambio que los origina (si el cambio se revierte, el aviso no sale) y el
 * {@link DespachadorSalida} los envía en segundos después de confirmar, con reintentos. Sin transacción, la fila queda confirmada
 * al insertarla y el despachador se despierta de inmediato.
 *
 * <p>Siguen síncronos los envíos cuyo resultado se muestra o cambia un estado: código MFA, enlaces de contraseña, código del QR del
 * OPL, notificación previa, acta y reporte a la DIGSA.
 */
@Service
public class BandejaSalida {

    /** Adjunto ya guardado en el almacén: la bandeja guarda la ruta, no los bytes. */
    public record Adjunto(String nombre, String tipo, String ruta) {
    }

    private final JdbcClient jdbc;
    private final Clock reloj;
    private final ApplicationEventPublisher eventos;
    private final ObjectMapper json;

    public BandejaSalida(JdbcClient jdbc, Clock reloj, ApplicationEventPublisher eventos, ObjectMapper json) {
        this.jdbc = jdbc;
        this.reloj = reloj;
        this.eventos = eventos;
        this.json = json;
    }

    /** Destinatarios máximos de una fila de correos_salida (CHECK de la columna para, V17). */
    public static final int MAX_DESTINATARIOS = 100;

    /** Aviso informativo. @return el id en la bandeja */
    @Transactional
    public long encolar(String origen, String referencia, Correo.Mensaje m) {
        return encolar(origen, referencia, m, List.of(), null, false);
    }

    /**
     * Encola un correo. Los adjuntos con bytes del mensaje no se admiten (la bandeja guarda rutas del almacén). Con más de
     * {@link #MAX_DESTINATARIOS} destinatarios queda en varias filas, todas en una transacción (o todas o ninguna).
     *
     * @return el id de la (primera) fila en la bandeja
     * @param alertaId evento de alerta que lo origina, o null
     * @param critico  aviso de una alerta crítica: si agota los intentos se publica {@link CorreoAgotado}
     */
    @Transactional
    public long encolar(String origen, String referencia, Correo.Mensaje m, List<Adjunto> adjuntos, Long alertaId, boolean critico) {
        if (!m.adjuntos().isEmpty()) {
            throw new IllegalArgumentException("La bandeja de salida guarda los adjuntos como rutas del almacén, no como bytes");
        }
        if (m.para().isEmpty()) {
            throw new IllegalArgumentException("El correo no tiene destinatarios");
        }
        String adj;
        try {
            adj = json.writeValueAsString(adjuntos == null ? List.of() : adjuntos);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        List<String> para = m.para().stream().distinct().toList();
        // Una fila admite hasta MAX_DESTINATARIOS (CHECK de correos_salida.para): más destinatarios van en varias filas, las copias en la
        // primera. Sin esto, con más de 100 coordinadores activos el aviso de una solicitud tumbaba el registro con un 500 (fase 10).
        List<Long> ids = new ArrayList<>();
        for (int desde = 0; desde < para.size(); desde += MAX_DESTINATARIOS) {
            List<String> lote = para.subList(desde, Math.min(para.size(), desde + MAX_DESTINATARIOS));
            List<String> copia = desde == 0 ? m.copia() : List.of();
            ids.add(jdbc.sql("""
                    INSERT INTO correos_salida (origen, referencia, alerta_id, critico, para, copia, asunto, html, adjuntos, proximo, creado)
                    VALUES (:o, :r, :a, :c, :para, :copia, :asunto, :html, CAST(:adj AS jsonb), :ahora, :ahora) RETURNING id""")
                    .param("o", Bitacora.cortar(origen, 60)).param("r", referencia == null ? null : Bitacora.cortar(referencia, 120)).param("a", alertaId)
                    .param("c", critico).param("para", lote.toArray(String[]::new)).param("copia", copia.toArray(String[]::new))
                    .param("asunto", Bitacora.cortar(m.asunto(), 300)).param("html", m.html() == null ? "" : m.html()).param("adj", adj).param("ahora", ahora)
                    .query(Long.class).single());
        }
        ids.forEach(id -> eventos.publishEvent(new SalidaEncolada("correo", id)));
        return ids.get(0);
    }

    /**
     * Reintento manual de un correo fallido (módulo Correos, C1): una fila NUEVA con el mismo contenido, que apunta a la original y
     * lleva el motivo; la original queda como estaba (historial). Solo lo encolado y reintentable; cada fallido se reintenta una vez
     * (índice único de reintento_de): si el reintento también falla, se reintenta ese.
     *
     * @return el id del reintento, o vacío si el correo no existe, no está fallido, no es reintentable o ya se reintentó
     */
    @Transactional
    public Optional<Long> reintentar(long id, String motivo, long usuarioId) {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        Optional<Long> nuevo = jdbc.sql("""
                INSERT INTO correos_salida (origen, referencia, alerta_id, critico, para, copia, asunto, html, adjuntos, proximo, creado, reintento_de,
                  motivo_reintento, reintentado_por)
                SELECT c.origen, c.referencia, c.alerta_id, c.critico, c.para, c.copia, c.asunto, c.html, c.adjuntos, :ahora, :ahora, c.id, :m, :u
                FROM correos_salida c
                WHERE c.id = :id AND c.estado = 'fallido' AND c.reintentable AND NOT c.sincrono
                ON CONFLICT (reintento_de) WHERE reintento_de IS NOT NULL DO NOTHING
                RETURNING id""")
                .param("id", id).param("m", Bitacora.cortar(motivo, 300)).param("u", usuarioId).param("ahora", ahora).query(Long.class).optional();
        nuevo.ifPresent(n -> eventos.publishEvent(new SalidaEncolada("correo", n)));
        return nuevo;
    }

    /** Encola un SMS de una alerta. El teléfono va enmascarado: el despachador toma el número vigente del usuario al enviar. */
    public long encolarSms(long alertaId, long usuarioId, String telefonoEnmascarado, String texto) {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        long id = jdbc.sql("""
                INSERT INTO sms_salida (alerta_id, usuario_id, telefono, texto, proximo, creado) VALUES (:a, :u, :t, :x, :ahora, :ahora) RETURNING id""")
                .param("a", alertaId).param("u", usuarioId).param("t", Bitacora.cortar(telefonoEnmascarado, 20)).param("x", Bitacora.cortar(texto, 160))
                .param("ahora", ahora).query(Long.class).single();
        eventos.publishEvent(new SalidaEncolada("sms", id));
        return id;
    }

    /** Deja constancia de un SMS que no sale (p. ej. «Sin teléfono móvil válido»): la alerta le llega solo por correo. */
    public void smsOmitido(long alertaId, long usuarioId, String telefonoEnmascarado, String texto, String motivo) {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        jdbc.sql("""
                INSERT INTO sms_salida (alerta_id, usuario_id, telefono, texto, estado, proximo, error, creado)
                VALUES (:a, :u, :t, :x, 'omitido', :ahora, :e, :ahora)""")
                .param("a", alertaId).param("u", usuarioId).param("t", Bitacora.cortar(telefonoEnmascarado, 20)).param("x", Bitacora.cortar(texto, 160))
                .param("e", Bitacora.cortar(motivo, 500)).param("ahora", ahora).update();
    }
}
