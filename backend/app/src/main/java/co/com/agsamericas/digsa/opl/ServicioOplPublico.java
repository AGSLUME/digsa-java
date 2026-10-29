package co.com.agsamericas.digsa.opl;

import co.com.agsamericas.digsa.plataforma.ConfiguracionDigsa;
import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.plataforma.EnvioRegistrado;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.PlantillaCorreo;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.reglas.Sesion;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Acceso sin usuario por el enlace del acta (decisión 25): la sesión queda limitada a la visita del token; el código de 6 dígitos va
 * solo a los correos del OPL (decisión 11.2), vence en 15 minutos, admite 5 intentos y como máximo salen 5 por hora por visita y 3 por
 * hora por IP. El token y el código se guardan como SHA-256 y se comparan en tiempo constante.
 */
@Service
public class ServicioOplPublico {

    static final int MINUTOS_CODIGO = 15;
    static final int CODIGOS_POR_HORA = 5;
    static final int CODIGOS_POR_HORA_IP = 3;
    static final int INTENTOS_CODIGO = 5;

    static final String S_TOKEN = "opl.token";
    static final String S_VISITA = "opl.visita";
    static final String S_PENDIENTE = "opl.correoPendiente";
    static final String S_CODIGO = "opl.codigoId";
    static final String S_CORREO = "opl.correo";
    static final String S_ULTIMO = "opl.ultimo";

    private static final SecureRandom AZAR = new SecureRandom();

    private final ServicioOpl opl;
    private final JdbcClient jdbc;
    private final EnvioRegistrado correo;
    private final RegistroBitacora bitacora;
    private final ConfiguracionDigsa cfg;
    private final TransactionTemplate tx;
    /** Códigos por hora desde una misma IP (digsa.opl.codigos-por-hora-ip; 3 por defecto). Las pruebas de punta a punta lo suben. */
    private final int codigosPorHoraIp;

    public ServicioOplPublico(ServicioOpl opl, JdbcClient jdbc, EnvioRegistrado correo, RegistroBitacora bitacora, ConfiguracionDigsa cfg, TransactionTemplate tx,
                              @org.springframework.beans.factory.annotation.Value("${digsa.opl.codigos-por-hora-ip:3}") int codigosPorHoraIp) {
        this.codigosPorHoraIp = codigosPorHoraIp > 0 ? codigosPorHoraIp : CODIGOS_POR_HORA_IP;
        this.opl = opl;
        this.jdbc = jdbc;
        this.correo = correo;
        this.bitacora = bitacora;
        this.cfg = cfg;
        this.tx = tx;
    }

    /** Abre el enlace: 404 si no existe; 410 si venció o la visita se reabrió. Otro enlace empieza la sesión del QR de cero. */
    public Map<String, Object> abrir(String token, HttpServletRequest req) {
        ServicioOpl.Acceso a = opl.accesoPorToken(token).orElseThrow(() -> ErrorNegocio.noEncontrado("El enlace no es válido."));
        opl.exigirAbierto(a);
        HttpSession s = req.getSession(true);
        if (!a.tokenHash().equals(s.getAttribute(S_TOKEN))) {
            limpiar(s);
            s.setAttribute(S_TOKEN, a.tokenHash());
            s.setAttribute(S_VISITA, a.visitaId());
            bitacora.escribir(Bitacora.Operacion.INGRESO, "opl_acceso", "opl", "Enlace del acta abierto · visita " + a.numero(), null, null, req.getRemoteAddr());
        }
        List<String> correos = opl.correosOpl();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("numero", a.numero());
        m.put("punto", a.punto());
        m.put("fase", a.fase(opl.ahora()));
        m.put("vencePlan", ServicioOpl.local(a.vencePlan()));
        m.put("correos", correos.stream().map(Correos::enmascarar).toList());
        m.put("verificado", verificado(s));
        return m;
    }

    public String enviarCodigo(int indice, HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        ServicioOpl.Acceso a = accesoDeSesion(s);
        List<String> correos = opl.correosOpl();
        if (indice < 0 || indice >= correos.size()) {
            throw ErrorNegocio.validacion("Elija uno de los correos del operador logístico.");
        }
        String para = correos.get(indice);
        String ip = req.getRemoteAddr();
        OffsetDateTime ahora = opl.ahora();
        String codigo = String.format("%06d", AZAR.nextInt(1_000_000));
        String enmascarado = Correos.enmascarar(para);
        // Topes contados y reservados con candado por visita y por IP: pedidos simultáneos no pasan del tope mientras sale el correo
        Long reserva = tx.execute(t -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('opl-codigo-visita'), :v)").param("v", (int) a.visitaId()).query().listOfRows();
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('opl-codigo-ip'), hashtext(:ip))").param("ip", ip).query().listOfRows();
            long deVisita = jdbc.sql("SELECT count(*) FROM opl_codigos WHERE visita_id = :v AND creado > :desde AND estado <> 'fallido'")
                    .param("v", a.visitaId()).param("desde", ahora.minusHours(1)).query(Long.class).single();
            long deIp = jdbc.sql("SELECT count(*) FROM opl_codigos WHERE ip = :ip AND creado > :desde AND estado <> 'fallido'").param("ip", ip)
                    .param("desde", ahora.minusHours(1)).query(Long.class).single();
            if (deVisita >= CODIGOS_POR_HORA || deIp >= codigosPorHoraIp) {
                return null;
            }
            return jdbc.sql("""
                    INSERT INTO opl_codigos (visita_id, correo, codigo_hash, creado, vence, ip, estado) VALUES (:v, :c, :h, :ahora, :vence, :ip, 'enviando')
                    RETURNING id""").param("v", a.visitaId()).param("c", para).param("h", Huellas.sha256(codigo)).param("ahora", ahora)
                    .param("vence", ahora.plusMinutes(MINUTOS_CODIGO)).param("ip", ip).query(Long.class).single();
        });
        if (reserva == null) {
            bitacora.escribir(Bitacora.Operacion.INGRESO, "opl_codigo_fallido", "opl", "Tope de códigos · visita " + a.numero(), null, enmascarado, ip);
            throw new ErrorNegocio(HttpStatus.TOO_MANY_REQUESTS, "Ya se enviaron varios códigos en la última hora. Espere un momento e intente de nuevo.");
        }
        Correo.Resultado r;
        try {
            // En la bitácora de envíos el código queda oculto (módulo Correos; duda CO-3)
            r = correo.enviar(EnvioRegistrado.Registro.de("opl_codigo", "visita:" + a.visitaId()).secreto(htmlCodigo(a.numero(), PlantillaCorreo.OCULTO)),
                    Correo.Mensaje.simple(para, "Código de verificación · visita " + a.numero(), htmlCodigo(a.numero(), codigo)));
        } catch (RuntimeException e) {
            r = null;
        }
        boolean salio = r != null && r.ok();
        // Si el correo falla, la reserva no consume el tope ni sirve para verificar
        jdbc.sql("UPDATE opl_codigos SET estado = :e WHERE id = :id").param("e", salio ? "enviado" : "fallido").param("id", reserva).update();
        if (!salio) {
            bitacora.escribir(Bitacora.Operacion.INGRESO, "opl_codigo_fallido", "opl", "No salió el código · visita " + a.numero(), null, enmascarado, ip);
            throw new ErrorNegocio(HttpStatus.BAD_GATEWAY, "No se pudo enviar el código. Intente de nuevo en unos minutos.");
        }
        // El código queda atado a esta sesión: otra sesión con el mismo QR no puede gastarle los intentos
        s.setAttribute(S_PENDIENTE, para);
        s.setAttribute(S_CODIGO, reserva);
        bitacora.escribir(Bitacora.Operacion.INGRESO, "opl_codigo", "opl", "Código enviado · visita " + a.numero(), null, enmascarado, ip);
        return "Le enviamos un código de 6 dígitos a " + enmascarado + ".";
    }

    public String verificar(String codigo, HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        ServicioOpl.Acceso a = accesoDeSesion(s);
        String para = (String) s.getAttribute(S_PENDIENTE);
        Long codigoId = (Long) s.getAttribute(S_CODIGO);
        if (para == null || codigoId == null) {
            throw ErrorNegocio.validacion("Primero pida el código.");
        }
        String c = codigo == null ? "" : codigo.strip();
        String ip = req.getRemoteAddr();
        String enmascarado = Correos.enmascarar(para);
        String resultado = tx.execute(t -> {
            Map<String, Object> fila = jdbc.sql("""
                    SELECT id, codigo_hash, vence, intentos, usado FROM opl_codigos WHERE id = :id AND visita_id = :v AND correo = :c AND estado = 'enviado'
                    FOR UPDATE""").param("id", codigoId).param("v", a.visitaId()).param("c", para).query().listOfRows().stream().findFirst().orElse(null);
            if (fila == null) {
                return "vencido";
            }
            long id = ((Number) fila.get("id")).longValue();
            int intentos = ((Number) fila.get("intentos")).intValue() + 1;
            jdbc.sql("UPDATE opl_codigos SET intentos = :n WHERE id = :id").param("n", intentos).param("id", id).update();
            if (intentos > INTENTOS_CODIGO) {
                return "intentos";
            }
            OffsetDateTime vence = toOffset(fila.get("vence"));
            if (fila.get("usado") != null || opl.ahora().isAfter(vence)) {
                return "vencido";
            }
            if (!c.matches("^[0-9]{6}$") || !Huellas.iguales((String) fila.get("codigo_hash"), Huellas.sha256(c))) {
                return "errado";
            }
            jdbc.sql("UPDATE opl_codigos SET usado = :ahora WHERE id = :id").param("ahora", opl.ahora()).param("id", id).update();
            return "ok";
        });
        switch (resultado) {
            case "intentos" -> {
                bitacora.escribir(Bitacora.Operacion.INGRESO, "opl_codigo_fallido", "opl", "Intentos agotados · visita " + a.numero(), null, enmascarado, ip);
                throw new ErrorNegocio(HttpStatus.TOO_MANY_REQUESTS, "Agotó los intentos de este código. Pida uno nuevo.");
            }
            case "vencido" -> throw ErrorNegocio.validacion("El código venció o ya se usó. Pida uno nuevo.");
            case "errado" -> {
                bitacora.escribir(Bitacora.Operacion.INGRESO, "opl_codigo_fallido", "opl", "Código errado · visita " + a.numero(), null, enmascarado, ip);
                throw ErrorNegocio.validacion("El código no es correcto.");
            }
            default -> {
            }
        }
        // Sesión nueva al verificar (fijación de sesión), con el mismo enlace
        req.changeSessionId();
        HttpSession nueva = req.getSession(false);
        nueva.removeAttribute(S_PENDIENTE);
        nueva.removeAttribute(S_CODIGO);
        nueva.setAttribute(S_CORREO, para);
        nueva.setAttribute(S_ULTIMO, opl.ahora().toInstant().toEpochMilli());
        bitacora.escribir(Bitacora.Operacion.INGRESO, "opl_acceso", "opl", "Código verificado · visita " + a.numero(), null, enmascarado, ip);
        return "Código verificado.";
    }

    /** Acceso de la sesión del QR: el enlace sigue abierto, con el mismo token y en plazo; si no, 401 y se borra la sesión del QR. */
    ServicioOpl.Acceso accesoDeSesion(HttpSession s) {
        String token = s == null ? null : (String) s.getAttribute(S_TOKEN);
        Long visita = s == null ? null : (Long) s.getAttribute(S_VISITA);
        ServicioOpl.Acceso a = visita == null ? null : opl.accesoDeVisita(visita).orElse(null);
        if (a == null || token == null || !Huellas.iguales(a.tokenHash(), token)) {
            if (s != null) {
                limpiar(s);
            }
            throw new ErrorNegocio(HttpStatus.UNAUTHORIZED, "Abra de nuevo el enlace del acta.", Map.of("motivo", "enlace"));
        }
        try {
            opl.exigirAbierto(a);
        } catch (ErrorNegocio e) {
            limpiar(s);
            throw new ErrorNegocio(HttpStatus.UNAUTHORIZED, e.getMessage(), Map.of("motivo", "enlace"));
        }
        return a;
    }

    /** Además del enlace, el código verificado y no vencido por inactividad (mismos minutos que los usuarios). */
    public Verificado verificadoDe(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        ServicioOpl.Acceso a = accesoDeSesion(s);
        String c = (String) s.getAttribute(S_CORREO);
        Long ultimo = (Long) s.getAttribute(S_ULTIMO);
        if (c == null || ultimo == null) {
            throw new ErrorNegocio(HttpStatus.FORBIDDEN, "Verifique el código enviado al correo del operador logístico.", Map.of("reverificar", true));
        }
        Instant ahora = opl.ahora().toInstant();
        if (Sesion.vencida(Instant.ofEpochMilli(ultimo), ahora, cfg.minutosSesion())) {
            s.removeAttribute(S_CORREO);
            throw new ErrorNegocio(HttpStatus.FORBIDDEN, "Por inactividad, pida un código nuevo; lo que escribió no se pierde.", Map.of("reverificar", true));
        }
        s.setAttribute(S_ULTIMO, ahora.toEpochMilli());
        return new Verificado(a, new ServicioOpl.Actor("qr", null, null, c, req.getRemoteAddr()));
    }

    public record Verificado(ServicioOpl.Acceso acceso, ServicioOpl.Actor actor) {
    }

    private static boolean verificado(HttpSession s) {
        return s.getAttribute(S_CORREO) != null;
    }

    private static void limpiar(HttpSession s) {
        for (String k : List.of(S_TOKEN, S_VISITA, S_PENDIENTE, S_CODIGO, S_CORREO, S_ULTIMO)) {
            s.removeAttribute(k);
        }
    }

    private static OffsetDateTime toOffset(Object o) {
        if (o instanceof OffsetDateTime t) {
            return t;
        }
        if (o instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        }
        return OffsetDateTime.parse(String.valueOf(o));
    }

    private static String htmlCodigo(String numero, String codigo) {
        return PlantillaCorreo.marco("Código de verificación", "<p>Su código para responder el acta de la visita " + PlantillaCorreo.e(numero)
                + " es:</p><p style=\"font-size:24px;letter-spacing:4px\"><strong>" + codigo + "</strong></p><p>Vence en " + MINUTOS_CODIGO
                + " minutos. Si usted no lo pidió, ignore este correo.</p>");
    }
}
