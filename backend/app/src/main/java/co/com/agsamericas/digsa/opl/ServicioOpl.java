package co.com.agsamericas.digsa.opl;

import co.com.agsamericas.digsa.agenda.RepositorioVisitas;
import co.com.agsamericas.digsa.ejecucion.EnlaceOpl;
import co.com.agsamericas.digsa.ejecucion.ServicioArchivos;
import co.com.agsamericas.digsa.ejecucion.ServicioFormulario;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.PlantillaCorreo;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.plataforma.ValidadorArchivos;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.reglas.Plazos;
import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Flujo del OPL después del acta (docs/especificaciones/flujo-opl), común al enlace del acta (QR con código de 6 dígitos) y al usuario OPL:
 * aceptar u objetar cada hallazgo, soportes de la objeción, quién y cómo del plan, firma del plan (sin firma tardía, decisión 11.5) y la
 * decisión del auditor líder sobre cada objeción. Los avisos salen después de confirmar y un fallo no deshace la acción.
 */
@Service
public class ServicioOpl {

    static final int MAX_SOPORTES = 10;
    private static final DateTimeFormatter MARCA = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Quién actúa: por el QR (correo verificado) o con usuario OPL; «sistema» para la tarea diaria. */
    public record Actor(String canal, Long usuarioId, String usuario, String correo, String ip) {
        static Actor de(UsuarioSesion u, String ip) {
            return new Actor("usuario", u.id(), u.usuario(), null, ip);
        }

        String quien() {
            return usuarioId != null ? usuario : "QR " + Correos.enmascarar(correo);
        }
    }

    /** Enlace vigente de la visita (el del acta enviada más reciente). */
    public record Acceso(long visitaId, long actaId, String tokenHash, OffsetDateTime enviado, OffsetDateTime venceObjeciones, OffsetDateTime vencePlan,
                         String estadoVisita, String numero, String punto, long liderId, Long regionalId) {
        String fase(OffsetDateTime ahora) {
            return EnlaceOpl.fase(venceObjeciones, vencePlan, ahora);
        }
    }

    public record Respuesta(String clave, String decision, String justificacion, String nombre, String cargo) {
    }

    public record FilaOpl(String clave, String quien, String como) {
    }

    public record FirmaPlan(String nombre, String cargo, String documento, String imagen) {
    }

    private final JdbcClient jdbc;
    private final RepositorioVisitas visitas;
    private final ValidadorArchivos validador;
    private final Almacen almacen;
    private final Correo correo;
    private final RegistroBitacora bitacora;
    private final TransactionTemplate tx;
    private final Clock reloj;
    private final co.com.agsamericas.digsa.agenda.ServicioAgenda agenda;

    public ServicioOpl(JdbcClient jdbc, RepositorioVisitas visitas, ValidadorArchivos validador, Almacen almacen, Correo correo, RegistroBitacora bitacora,
                       TransactionTemplate tx, Clock reloj, co.com.agsamericas.digsa.agenda.ServicioAgenda agenda) {
        this.agenda = agenda;
        this.jdbc = jdbc;
        this.visitas = visitas;
        this.validador = validador;
        this.almacen = almacen;
        this.correo = correo;
        this.bitacora = bitacora;
        this.tx = tx;
        this.reloj = reloj;
    }

    OffsetDateTime ahora() {
        return OffsetDateTime.now(reloj);
    }

    private static final String ACCESO = """
            SELECT a.visita_id, a.acta_id, a.token_hash, a.enviado, a.vence_objeciones, a.vence_plan, v.estado, v.numero, p.nombre AS punto,
                   v.auditor_id, p.regional_id
            FROM opl_accesos a JOIN visitas v ON v.id = a.visita_id JOIN puntos p ON p.id = v.punto_id""";

    static Acceso fila(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Acceso(rs.getLong("visita_id"), rs.getLong("acta_id"), rs.getString("token_hash"), rs.getObject("enviado", OffsetDateTime.class),
                rs.getObject("vence_objeciones", OffsetDateTime.class), rs.getObject("vence_plan", OffsetDateTime.class), rs.getString("estado"),
                rs.getString("numero"), rs.getString("punto"), rs.getLong("auditor_id"), (Long) rs.getObject("regional_id"));
    }

    public Optional<Acceso> accesoDeVisita(long visitaId) {
        return jdbc.sql(ACCESO + " WHERE a.visita_id = :v").param("v", visitaId).query(ServicioOpl::fila).optional();
    }

    public Optional<Acceso> accesoPorToken(String token) {
        if (token == null || !token.matches("^[0-9a-f]{64}$")) {
            return Optional.empty();
        }
        return jdbc.sql(ACCESO + " WHERE a.token_hash = :h").param("h", Huellas.sha256(token)).query(ServicioOpl::fila).optional()
                .filter(a -> Huellas.iguales(a.tokenHash(), Huellas.sha256(token)));
    }

    /** Bloquea la visita y comprueba que el enlace leído antes siga siendo el vigente (nadie reabrió ni reenvió el acta entre tanto). */
    private void bloquearVigente(Acceso a) {
        visitas.bloquear(a.visitaId());
        Acceso ahoraMismo = accesoDeVisita(a.visitaId()).orElseThrow(() -> new ErrorNegocio(HttpStatus.GONE, "El enlace ya no está vigente."));
        if (ahoraMismo.actaId() != a.actaId()) {
            throw new ErrorNegocio(HttpStatus.GONE, "Se envió un acta nueva: use el código QR del acta nueva.");
        }
        exigirAbierto(ahoraMismo);
    }

    /** El enlace admite respuestas: la visita está cerrada (no reabierta) y no pasó el plazo del plan. */
    void exigirAbierto(Acceso a) {
        if (!"cerrada".equals(a.estadoVisita())) {
            throw new ErrorNegocio(HttpStatus.GONE, "La visita se reabrió: use el código QR del acta nueva cuando la reciba.");
        }
        if (ahora().isAfter(a.vencePlan())) {
            throw new ErrorNegocio(HttpStatus.GONE, "Venció el plazo de " + EnlaceOpl.DIAS_PLAN
                    + " días hábiles para el plan de mejoramiento: el enlace ya no recibe respuestas. Comuníquese con la interventoría.");
        }
    }

    // ---------- Archivos: acta enviada y soportes ----------

    public ServicioArchivos.Archivo acta(Acceso a) {
        return jdbc.sql("""
                SELECT f.id, f.visita_id, f.clave, f.nombre_original, f.ruta, f.tipo, f.tamano, f.sha256 FROM visitas_actas va JOIN archivos f ON f.id = va.archivo_id
                WHERE va.id = :a""").param("a", a.actaId()).query(ServicioOpl::archivo).optional()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("El acta no está disponible."));
    }

    /** Soporte del OPL de la visita; uno de otra visita responde 404 como si no existiera. */
    public ServicioArchivos.Archivo soporte(Acceso a, long id) {
        return jdbc.sql("SELECT id, visita_id, clave, nombre_original, ruta, tipo, tamano, sha256 FROM opl_soportes WHERE id = :id AND visita_id = :v")
                .param("id", id).param("v", a.visitaId()).query(ServicioOpl::archivo).optional()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("No existe el soporte."));
    }

    private static ServicioArchivos.Archivo archivo(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new ServicioArchivos.Archivo(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7),
                rs.getString(8));
    }

    public java.io.InputStream abrir(ServicioArchivos.Archivo f) throws IOException {
        return almacen.leer(f.ruta());
    }

    // ---------- Usuario OPL: lista y acceso por alcance ----------

    /** Visitas cerradas con enlace vigente del alcance del OPL (nacional: país; regional: su regional). */
    public List<Map<String, Object>> lista(co.com.agsamericas.digsa.reglas.Alcance alcance) {
        co.com.agsamericas.digsa.agenda.ServicioConsultaAgenda.Condicion c = condicionOpl(alcance);
        return jdbc.sql("""
                SELECT a.visita_id FROM opl_accesos a JOIN visitas v ON v.id = a.visita_id JOIN puntos p ON p.id = v.punto_id
                WHERE v.estado = 'cerrada' AND a.vence_plan >= :ahora AND (""" + c.sql() + ") ORDER BY a.enviado DESC LIMIT 500")
                .params(c.params()).param("ahora", ahora()).query(Long.class).list().stream()
                .map(id -> accesoDeVisita(id).orElseThrow()).map(a -> {
                    aceptarEnTransaccionPropia(a);
                    List<Map<String, Object>> hs = hallazgos(a);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("visitaId", a.visitaId());
                    m.put("numero", a.numero());
                    m.put("punto", a.punto());
                    m.put("enviado", local(a.enviado()));
                    m.put("hallazgos", hs.size());
                    m.put("sinRespuesta", hs.stream().filter(h -> "sin_respuesta".equals(h.get("estado"))).count());
                    m.put("faltanPlan", faltanPlan(hs).size());
                    m.put("planEstado", plan(a).get("estado"));
                    m.put("fase", a.fase(ahora()));
                    m.put("venceObjeciones", local(a.venceObjeciones()));
                    m.put("vencePlan", local(a.vencePlan()));
                    return m;
                }).toList();
    }

    /** El acceso de la visita si está en el alcance del OPL y abierta; si no, 404 como si no existiera. */
    public Acceso accesoDeUsuario(long visitaId, co.com.agsamericas.digsa.reglas.Alcance alcance) {
        co.com.agsamericas.digsa.agenda.ServicioConsultaAgenda.Condicion c = condicionOpl(alcance);
        boolean ve = jdbc.sql("SELECT count(*) FROM visitas v JOIN puntos p ON p.id = v.punto_id WHERE v.id = :id AND (" + c.sql() + ")")
                .params(c.params()).param("id", visitaId).query(Long.class).single() > 0;
        Acceso a = ve ? accesoDeVisita(visitaId).orElse(null) : null;
        if (a == null) {
            throw ErrorNegocio.noEncontrado("No existe la visita.");
        }
        exigirAbierto(a);
        return a;
    }

    private static co.com.agsamericas.digsa.agenda.ServicioConsultaAgenda.Condicion condicionOpl(co.com.agsamericas.digsa.reglas.Alcance alcance) {
        if (!(alcance instanceof co.com.agsamericas.digsa.reglas.Alcance.Opl || alcance instanceof co.com.agsamericas.digsa.reglas.Alcance.Regional)) {
            return new co.com.agsamericas.digsa.agenda.ServicioConsultaAgenda.Condicion("false", Map.of());
        }
        return co.com.agsamericas.digsa.agenda.ServicioConsultaAgenda.condicion(alcance);
    }

    // ---------- Hallazgos y su estado ----------

    /**
     * Hallazgos del acta vigente con la parte del auditor del 5W2H, la respuesta del OPL, su estado derivado (sin_respuesta, aceptado,
     * aceptado_auto, objetado, ratificado, levantado) y los soportes.
     */
    public List<Map<String, Object>> hallazgos(Acceso a) {
        return jdbc.sql("""
                SELECT h.clave, h.codigo, h.origen, h.texto, h.hallazgo, h.fin, p.causas, p.que, p.porque, p.donde, p.inicio, p.fin AS plan_fin, p.indicador,
                       p.estado AS plan_estado, r.decision, r.automatica, r.justificacion, r.quien, r.como, r.nombre, r.cargo, r.canal, r.fecha,
                       r.resolucion, r.resolucion_motivo, r.resuelto_fecha
                FROM visitas_hallazgos h LEFT JOIN visitas_plan p ON p.visita_id = h.visita_id AND p.clave = h.clave
                LEFT JOIN opl_respuestas r ON r.visita_id = h.visita_id AND r.clave = h.clave
                WHERE h.acta_id = :a ORDER BY h.id""").param("a", a.actaId()).query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("clave", rs.getString("clave"));
            m.put("codigo", rs.getString("codigo"));
            m.put("origen", rs.getString("origen"));
            m.put("texto", rs.getString("texto"));
            m.put("hallazgo", rs.getString("hallazgo"));
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("causas", rs.getString("causas"));
            plan.put("que", rs.getString("que"));
            plan.put("porque", rs.getString("porque"));
            plan.put("donde", rs.getString("donde"));
            plan.put("inicio", rs.getDate("inicio") == null ? null : rs.getDate("inicio").toString());
            plan.put("fin", rs.getDate("plan_fin") == null ? null : rs.getDate("plan_fin").toString());
            plan.put("indicador", rs.getString("indicador"));
            plan.put("estado", rs.getString("plan_estado") == null ? "Pendiente" : rs.getString("plan_estado"));
            m.put("planAuditor", plan);
            String decision = rs.getString("decision");
            String resolucion = rs.getString("resolucion");
            m.put("estado", estado(decision, rs.getBoolean("automatica"), resolucion));
            m.put("decision", decision);
            m.put("justificacion", rs.getString("justificacion"));
            m.put("quien", rs.getString("quien"));
            m.put("como", rs.getString("como"));
            m.put("respondio", rs.getString("nombre") == null ? null : rs.getString("nombre") + " · " + rs.getString("cargo"));
            m.put("canal", rs.getString("canal"));
            m.put("resolucion", resolucion);
            m.put("resolucionMotivo", rs.getString("resolucion_motivo"));
            return m;
        }).list().stream().peek(h -> h.put("soportes", soportes(a.visitaId(), (String) h.get("clave")))).toList();
    }

    /** Estados de un hallazgo frente al OPL (invariante de la spec). */
    static String estado(String decision, boolean automatica, String resolucion) {
        if (decision == null) {
            return "sin_respuesta";
        }
        if ("acepta".equals(decision)) {
            return automatica ? "aceptado_auto" : "aceptado";
        }
        return resolucion == null ? "objetado" : "ratifica".equals(resolucion) ? "ratificado" : "levantado";
    }

    /** Aceptados, aceptados automáticamente o ratificados: los que van al plan (necesitan quién y cómo). */
    static boolean vaAlPlan(String estado) {
        return Set.of("aceptado", "aceptado_auto", "ratificado").contains(estado);
    }

    List<Map<String, Object>> soportes(long visitaId, String clave) {
        return jdbc.sql("SELECT id, nombre_original AS nombre, tipo, tamano, sha256, subido FROM opl_soportes WHERE visita_id = :v AND clave = :c ORDER BY id")
                .param("v", visitaId).param("c", clave).query().listOfRows();
    }

    Map<String, Object> plan(Acceso a) {
        // Sin el número de documento: queda en la base y en el acta, no en la respuesta (minimización, Ley 1581)
        return jdbc.sql("SELECT estado, nombre, cargo, canal, firmado, vencido_en, reportado_en, cerrado_en, motivo_cierre FROM opl_planes WHERE acta_id = :a")
                .param("a", a.actaId()).query().listOfRows().stream().findFirst().orElse(Map.of("estado", "pendiente"));
    }

    boolean planFirmado(Acceso a) {
        Object e = plan(a).get("estado");
        return "firmado".equals(e) || "cerrado".equals(e) && plan(a).get("firmado") != null;
    }

    /** Vencida la fase de objeciones, todo hallazgo sin respuesta queda aceptado automáticamente (decisión 8). @return cuántos */
    public int aplicarAceptacionAutomatica(Acceso a) {
        if (!ahora().isAfter(a.venceObjeciones())) {
            return 0;
        }
        int n = jdbc.sql("""
                INSERT INTO opl_respuestas (visita_id, clave, decision, automatica, canal, fecha)
                SELECT h.visita_id, h.clave, 'acepta', true, 'sistema', :ahora FROM visitas_hallazgos h
                WHERE h.acta_id = :a AND NOT EXISTS (SELECT 1 FROM opl_respuestas r WHERE r.visita_id = h.visita_id AND r.clave = h.clave)
                ON CONFLICT (visita_id, clave) DO NOTHING""").param("ahora", ahora()).param("a", a.actaId()).update();
        if (n > 0) {
            visitas.historial(a.visitaId(), null, "sistema", "Aceptación automática", n + " hallazgo(s) sin respuesta a los " + EnlaceOpl.DIAS_OBJECIONES + " días hábiles");
        }
        return n;
    }

    /** Aceptación automática confirmada aparte: sobrevive al error que la anuncia (p. ej. «venció el plazo para objetar»). */
    void aceptarEnTransaccionPropia(Acceso a) {
        if (ahora().isAfter(a.venceObjeciones())) {
            tx.executeWithoutResult(t -> {
                visitas.bloquear(a.visitaId());
                aplicarAceptacionAutomatica(a);
            });
        }
    }

    /** Detalle para responder (con la aceptación automática aplicada si venció la fase de objeciones). */
    public Map<String, Object> detalle(Acceso a) {
        if ("cerrada".equals(a.estadoVisita())) {
            aceptarEnTransaccionPropia(a);
        }
        List<Map<String, Object>> hs = hallazgos(a);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("visitaId", a.visitaId());
        m.put("numero", a.numero());
        m.put("punto", a.punto());
        m.put("fase", a.fase(ahora()));
        m.put("enviado", local(a.enviado()));
        m.put("venceObjeciones", local(a.venceObjeciones()));
        m.put("vencePlan", local(a.vencePlan()));
        m.put("venceObjecionesLargo", Plazos.fechaLarga(a.venceObjeciones().atZoneSameInstant(Fechas.BOGOTA).toLocalDate()));
        m.put("vencePlanLargo", Plazos.fechaLarga(a.vencePlan().atZoneSameInstant(Fechas.BOGOTA).toLocalDate()));
        m.put("abierta", "cerrada".equals(a.estadoVisita()));
        m.put("hallazgos", hs);
        m.put("faltanPlan", faltanPlan(hs));
        m.put("plan", plan(a));
        m.put("actaArchivoId", jdbc.sql("SELECT archivo_id FROM visitas_actas WHERE id = :a").param("a", a.actaId()).query(Long.class).single());
        return m;
    }

    static List<String> faltanPlan(List<Map<String, Object>> hs) {
        List<String> r = new ArrayList<>();
        for (Map<String, Object> h : hs) {
            if (vaAlPlan((String) h.get("estado")) && (blanco(h.get("quien")) || blanco(h.get("como")))) {
                r.add((String) h.get("codigo"));
            }
        }
        return r;
    }

    /** Con el plan firmado: intenta cambiar un valor que ya existía (llenar lo vacío o repetirlo igual no cuenta). */
    private static boolean cambia(Object actual, String nuevo) {
        return !blanco(actual) && !String.valueOf(actual).strip().equals(texto(nuevo));
    }

    private static boolean blanco(Object o) {
        return o == null || String.valueOf(o).isBlank();
    }

    static String local(OffsetDateTime t) {
        return t == null ? null : FECHA_HORA.format(t.atZoneSameInstant(Fechas.BOGOTA));
    }

    // ---------- Respuestas del OPL ----------

    public String responder(Acceso a, Respuesta r, Actor actor) {
        exigirAbierto(a);
        String decision = r.decision() == null ? "" : r.decision().strip();
        if (!Set.of("acepta", "objeta").contains(decision)) {
            throw ErrorNegocio.validacion("La respuesta debe ser aceptar u objetar.");
        }
        String nombre = texto(r.nombre());
        String cargo = texto(r.cargo());
        if (nombre.length() < 3 || nombre.length() > 150 || cargo.length() < 2 || cargo.length() > 150) {
            throw ErrorNegocio.validacion("Escriba el nombre (3 a 150 caracteres) y el cargo (2 a 150) de quien responde.");
        }
        String just = texto(r.justificacion());
        if ("objeta".equals(decision) && (just.length() < 10 || just.length() > 4000)) {
            throw ErrorNegocio.validacion("La objeción necesita la justificación (de 10 a 4000 caracteres).");
        }
        if (!"objeciones".equals(a.fase(ahora()))) {
            aceptarEnTransaccionPropia(a);
            throw ErrorNegocio.validacion("Venció el plazo de " + EnlaceOpl.DIAS_OBJECIONES + " días hábiles para aceptar u objetar: los hallazgos sin respuesta quedaron aceptados.");
        }
        // Aviso al líder solo cuando el hallazgo pasa a objetado (cambiar la respuesta una y otra vez no inunda su correo)
        boolean objeto = tx.execute(t -> {
            bloquearVigente(a);
            if (planFirmado(a)) {
                throw new ErrorNegocio(HttpStatus.CONFLICT, "El plan ya está firmado: las respuestas no cambian.");
            }
            if (!"objeciones".equals(a.fase(ahora()))) {
                throw ErrorNegocio.validacion("Venció el plazo de " + EnlaceOpl.DIAS_OBJECIONES + " días hábiles para aceptar u objetar.");
            }
            Map<String, Object> h = hallazgos(a).stream().filter(x -> x.get("clave").equals(r.clave())).findFirst()
                    .orElseThrow(() -> ErrorNegocio.validacion("El hallazgo no es de esta acta."));
            if (h.get("resolucion") != null) {
                throw ErrorNegocio.validacion("El auditor líder ya decidió la objeción de " + h.get("codigo") + ".");
            }
            boolean yaObjetado = "objeta".equals(h.get("decision"));
            jdbc.sql("""
                    INSERT INTO opl_respuestas (visita_id, clave, decision, automatica, justificacion, nombre, cargo, canal, usuario_id, correo, ip, fecha)
                    VALUES (:v, :c, :d, false, :j, :n, :cargo, :canal, :u, :correo, :ip, :ahora)
                    ON CONFLICT (visita_id, clave) DO UPDATE SET decision = EXCLUDED.decision, automatica = false, justificacion = EXCLUDED.justificacion,
                      nombre = EXCLUDED.nombre, cargo = EXCLUDED.cargo, canal = EXCLUDED.canal, usuario_id = EXCLUDED.usuario_id, correo = EXCLUDED.correo,
                      ip = EXCLUDED.ip, fecha = EXCLUDED.fecha""")
                    .param("v", a.visitaId()).param("c", r.clave()).param("d", decision).param("j", "objeta".equals(decision) ? just : null).param("n", nombre)
                    .param("cargo", cargo).param("canal", actor.canal()).param("u", actor.usuarioId()).param("correo", actor.correo()).param("ip", actor.ip())
                    .param("ahora", ahora()).update();
            visitas.historial(a.visitaId(), actor.usuarioId(), actor.quien(), "objeta".equals(decision) ? "Hallazgo objetado por el OPL" : "Hallazgo aceptado por el OPL",
                    h.get("codigo") + (actor.correo() == null ? "" : " (QR)"));
            return "objeta".equals(decision) && !yaObjetado;
        });
        bitacora.escribir(Bitacora.Operacion.ACTUALIZACION, "opl_respuesta", "opl", "Visita " + a.numero() + ": " + r.clave() + " " + decision + " por " + actor.quien(),
                actor.usuarioId(), actor.usuarioId() == null ? Correos.enmascarar(actor.correo()) : actor.usuario(), actor.ip());
        String aviso = null;
        if (objeto) {
            aviso = avisarLider(a, "Objeción del OPL en la visita " + a.numero(), "El operador logístico objetó el hallazgo " + r.clave().substring(3)
                    + " de la visita " + a.numero() + ". Revíselo en la Suite DIGSA y ratifíquelo o levántelo.");
        }
        return ("objeta".equals(decision) ? "Objeción registrada. El auditor líder la revisará." : "Hallazgo aceptado.") + (aviso == null ? "" : " " + aviso);
    }

    public Map<String, Object> subirSoportes(Acceso a, String clave, List<MultipartFile> archivos, Actor actor) {
        exigirAbierto(a);
        if (archivos == null || archivos.isEmpty()) {
            throw ErrorNegocio.validacion("Elija al menos un archivo.");
        }
        // Antes de leer los bytes: tope por petición, estado del hallazgo y soportes ya cargados (se repite dentro de la transacción)
        if (archivos.size() > MAX_SOPORTES) {
            throw ErrorNegocio.validacion("Máximo " + MAX_SOPORTES + " soportes por hallazgo.");
        }
        Map<String, Object> previo = hallazgos(a).stream().filter(x -> x.get("clave").equals(clave)).findFirst()
                .orElseThrow(() -> ErrorNegocio.validacion("El hallazgo no es de esta acta."));
        if (!"objetado".equals(previo.get("estado"))) {
            throw ErrorNegocio.validacion("Primero registre la objeción del hallazgo; los soportes acompañan la objeción.");
        }
        if (((List<?>) previo.get("soportes")).size() + archivos.size() > MAX_SOPORTES) {
            throw ErrorNegocio.validacion("Máximo " + MAX_SOPORTES + " soportes por hallazgo.");
        }
        List<ValidadorArchivos.Valido> validos = new ArrayList<>();
        List<String> nombres = new ArrayList<>();
        for (MultipartFile f : archivos) {
            String nombre = f.getOriginalFilename() == null ? "soporte" : f.getOriginalFilename().replaceAll("[\\\\/\\p{Cntrl}\"<>|*?:]", "_");
            if (f.getSize() > ValidadorArchivos.MAX_VIDEO) {
                throw new ErrorNegocio(HttpStatus.PAYLOAD_TOO_LARGE, "El archivo «" + nombre + "» es muy grande.");
            }
            try {
                validos.add(validador.validar(nombre, f.getBytes()));
            } catch (IOException e) {
                throw ErrorNegocio.validacion("No se pudo leer el archivo «" + nombre + "».");
            }
            nombres.add(nombre.length() > 200 ? nombre.substring(nombre.length() - 200) : nombre);
        }
        List<String> rutas = new ArrayList<>();
        try {
            for (ValidadorArchivos.Valido v : validos) {
                String ruta = "visita_" + a.visitaId() + "/opl/" + MARCA.format(ahora().atZoneSameInstant(Fechas.BOGOTA)) + "_" + Huellas.secreto(8) + "." + v.extension();
                almacen.guardar(ruta, v.contenido());
                rutas.add(ruta);
            }
            tx.executeWithoutResult(t -> {
                bloquearVigente(a);
                if (planFirmado(a) || !"objeciones".equals(a.fase(ahora()))) {
                    throw ErrorNegocio.validacion("Los soportes se suben solo durante el plazo para objetar y antes de firmar el plan.");
                }
                Map<String, Object> h = hallazgos(a).stream().filter(x -> x.get("clave").equals(clave)).findFirst()
                        .orElseThrow(() -> ErrorNegocio.validacion("El hallazgo no es de esta acta."));
                if (!"objetado".equals(h.get("estado"))) {
                    throw ErrorNegocio.validacion("Primero registre la objeción del hallazgo; los soportes acompañan la objeción.");
                }
                long ya = jdbc.sql("SELECT count(*) FROM opl_soportes WHERE visita_id = :v AND clave = :c").param("v", a.visitaId()).param("c", clave)
                        .query(Long.class).single();
                if (ya + validos.size() > MAX_SOPORTES) {
                    throw ErrorNegocio.validacion("Máximo " + MAX_SOPORTES + " soportes por hallazgo.");
                }
                for (int i = 0; i < validos.size(); i++) {
                    ValidadorArchivos.Valido v = validos.get(i);
                    jdbc.sql("""
                            INSERT INTO opl_soportes (visita_id, clave, nombre_original, ruta, tipo, tamano, sha256, usuario_id, correo, subido)
                            VALUES (:v, :c, :n, :r, :t, :tam, :s, :u, :correo, :ahora)""")
                            .param("v", a.visitaId()).param("c", clave).param("n", nombres.get(i)).param("r", rutas.get(i)).param("t", v.tipo())
                            .param("tam", (long) v.contenido().length).param("s", v.sha256()).param("u", actor.usuarioId()).param("correo", actor.correo())
                            .param("ahora", ahora()).update();
                }
            });
        } catch (IOException e) {
            rutas.forEach(almacen::descartar);
            throw new ErrorNegocio(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudieron guardar los soportes.");
        } catch (RuntimeException e) {
            rutas.forEach(almacen::descartar);
            throw e;
        }
        bitacora.escribir(Bitacora.Operacion.INSERCION, "opl_soporte", "opl", "Visita " + a.numero() + ": " + validos.size() + " soporte(s) de " + clave,
                actor.usuarioId(), actor.usuarioId() == null ? Correos.enmascarar(actor.correo()) : actor.usuario(), actor.ip());
        return Map.of("mensaje", validos.size() + " soporte(s) cargado(s).");
    }

    /**
     * Quién y cómo del plan, solo de lo que va al plan. Todo o nada. Con el plan firmado solo se llenan los campos vacíos (lo ratificado
     * después de firmar, duda 21.4): un valor que ya existía no cambia.
     */
    public Map<String, Object> guardarPlan(Acceso a, List<FilaOpl> filas, Actor actor) {
        exigirAbierto(a);
        aceptarEnTransaccionPropia(a);
        List<FilaOpl> lista = filas == null ? List.of() : filas.stream().filter(java.util.Objects::nonNull).toList();
        List<String> faltan = tx.execute(t -> {
            bloquearVigente(a);
            boolean firmado = planFirmado(a);
            Map<String, Map<String, Object>> hs = new LinkedHashMap<>();
            hallazgos(a).forEach(h -> hs.put((String) h.get("clave"), h));
            List<String> errores = new ArrayList<>();
            for (FilaOpl f : lista) {
                Map<String, Object> h = hs.get(f.clave());
                if (h == null || !vaAlPlan((String) h.get("estado"))) {
                    continue; // lo que no va al plan se ignora
                }
                if (texto(f.quien()).length() > 200 || texto(f.como()).length() > 4000) {
                    errores.add(h.get("codigo") + ": quién hasta 200 caracteres y cómo hasta 4000");
                }
                if (firmado && (cambia(h.get("quien"), f.quien()) || cambia(h.get("como"), f.como()))) {
                    errores.add(h.get("codigo") + ": con el plan firmado solo se completa lo vacío");
                }
            }
            if (!errores.isEmpty()) {
                throw ErrorNegocio.validacion("No se guardó el plan: " + String.join("; ", errores) + ".");
            }
            for (FilaOpl f : lista) {
                Map<String, Object> h = hs.get(f.clave());
                if (h == null || !vaAlPlan((String) h.get("estado"))) {
                    continue;
                }
                jdbc.sql(firmado ? "UPDATE opl_respuestas SET quien = coalesce(quien, :q), como = coalesce(como, :c) WHERE visita_id = :v AND clave = :k"
                        : "UPDATE opl_respuestas SET quien = :q, como = :c WHERE visita_id = :v AND clave = :k")
                        .param("q", vacioANulo(f.quien())).param("c", vacioANulo(f.como())).param("v", a.visitaId()).param("k", f.clave()).update();
            }
            return faltanPlan(hallazgos(a));
        });
        bitacora.escribir(Bitacora.Operacion.ACTUALIZACION, "opl_plan", "opl", "Visita " + a.numero() + ": quién y cómo del plan", actor.usuarioId(),
                actor.usuarioId() == null ? Correos.enmascarar(actor.correo()) : actor.usuario(), actor.ip());
        return Map.of("mensaje", faltan.isEmpty() ? "Plan guardado: está completo para firmar." : "Plan guardado. Falta quién y cómo de: " + String.join(", ", faltan) + ".",
                "faltan", faltan);
    }

    /** Firma del plan, una vez por acta y solo antes del plazo de 5 días hábiles (decisión 11.5); con el documento (decisión 11.8). */
    public String firmar(Acceso a, FirmaPlan f, Actor actor) {
        exigirAbierto(a);
        String nombre = texto(f.nombre());
        String cargo = texto(f.cargo());
        String documento = texto(f.documento());
        if (nombre.length() < 3 || nombre.length() > 150 || cargo.length() < 2 || cargo.length() > 150) {
            throw ErrorNegocio.validacion("Escriba el nombre (3 a 150 caracteres) y el cargo (2 a 150) de quien firma.");
        }
        if (!documento.matches("^[0-9A-Za-z.-]{4,30}$")) {
            throw ErrorNegocio.validacion("Escriba el número de documento de quien firma.");
        }
        byte[] png = ServicioFormulario.png(f.imagen());
        aceptarEnTransaccionPropia(a);
        tx.executeWithoutResult(t -> {
            bloquearVigente(a);
            String estado = jdbc.sql("SELECT estado FROM opl_planes WHERE acta_id = :a FOR UPDATE").param("a", a.actaId()).query(String.class).optional()
                    .orElse("pendiente");
            if (!"pendiente".equals(estado)) {
                throw new ErrorNegocio(HttpStatus.CONFLICT, "firmado".equals(estado) ? "El plan de esta acta ya está firmado."
                        : "El plan ya no admite firma (estado: " + estado + ").");
            }
            List<Map<String, Object>> hs = hallazgos(a);
            List<String> sinRespuesta = hs.stream().filter(h -> "sin_respuesta".equals(h.get("estado"))).map(h -> (String) h.get("codigo")).toList();
            if (!sinRespuesta.isEmpty()) {
                throw ErrorNegocio.validacion("Responda (acepte u objete) los hallazgos: " + String.join(", ", sinRespuesta) + ".");
            }
            List<String> faltan = faltanPlan(hs);
            if (!faltan.isEmpty()) {
                throw ErrorNegocio.validacion("Falta quién y cómo de: " + String.join(", ", faltan) + ".");
            }
            int n = jdbc.sql("""
                    INSERT INTO opl_planes (visita_id, acta_id, estado, nombre, cargo, documento, firma, canal, usuario_id, correo, ip, firmado)
                    VALUES (:v, :a, 'firmado', :n, :c, :d, :f, :canal, :u, :correo, :ip, :ahora)
                    ON CONFLICT (acta_id) DO UPDATE SET estado = 'firmado', nombre = EXCLUDED.nombre, cargo = EXCLUDED.cargo, documento = EXCLUDED.documento,
                      firma = EXCLUDED.firma, canal = EXCLUDED.canal, usuario_id = EXCLUDED.usuario_id, correo = EXCLUDED.correo, ip = EXCLUDED.ip,
                      firmado = EXCLUDED.firmado WHERE opl_planes.estado = 'pendiente'""")
                    .param("v", a.visitaId()).param("a", a.actaId()).param("n", nombre).param("c", cargo).param("d", documento).param("f", png)
                    .param("canal", actor.canal()).param("u", actor.usuarioId()).param("correo", actor.correo()).param("ip", actor.ip()).param("ahora", ahora())
                    .update();
            if (n != 1) {
                throw new ErrorNegocio(HttpStatus.CONFLICT, "El plan ya no admite firma.");
            }
            visitas.historial(a.visitaId(), actor.usuarioId(), actor.quien(), "Plan del OPL firmado", "Firmó " + nombre + " · " + cargo);
        });
        bitacora.escribir(Bitacora.Operacion.ACTUALIZACION, "opl_firma", "opl", "Visita " + a.numero() + ": plan de mejoramiento firmado", actor.usuarioId(),
                actor.usuarioId() == null ? Correos.enmascarar(actor.correo()) : actor.usuario(), actor.ip());
        String aviso = avisarLider(a, "Plan de mejoramiento firmado · visita " + a.numero(), "El operador logístico firmó el plan de mejoramiento de la visita "
                + a.numero() + ".");
        return "Plan de mejoramiento firmado." + (aviso == null ? "" : " " + aviso);
    }

    // ---------- Decisión del auditor líder ----------

    /** Solo el auditor líder decide cada objeción, una vez y sin plazo (decisión 17); al levantar el último hallazgo se cierra el plan (11.7). */
    public String resolver(long visitaId, String clave, String resolucion, String motivo, UsuarioSesion quien) {
        Acceso a = accesoDeVisita(visitaId).orElseThrow(() -> ErrorNegocio.noEncontrado("La visita no tiene un acta con hallazgos."));
        if (a.liderId() != quien.id()) {
            throw new ErrorNegocio(HttpStatus.FORBIDDEN, "Solo el auditor líder de la visita decide las objeciones.");
        }
        if (!Set.of("ratifica", "levanta").contains(resolucion)) {
            throw ErrorNegocio.validacion("La decisión debe ser ratificar o levantar el hallazgo.");
        }
        String m = texto(motivo);
        if (m.length() < 10 || m.length() > 800) {
            throw ErrorNegocio.validacion("Escriba el motivo de la decisión (de 10 a 800 caracteres).");
        }
        boolean cerrado = Boolean.TRUE.equals(tx.execute(t -> {
            // Sin plazo (decisión 17), pero sobre la visita cerrada y el acta vigente
            visitas.bloquear(visitaId);
            Acceso vigente = accesoDeVisita(visitaId).orElseThrow(() -> ErrorNegocio.noEncontrado("La visita no tiene un acta con hallazgos."));
            if (!"cerrada".equals(vigente.estadoVisita()) || vigente.actaId() != a.actaId()) {
                throw ErrorNegocio.conflicto("La visita se reabrió o tiene un acta nueva: decida sobre las respuestas al acta vigente.");
            }
            if (hallazgos(a).stream().noneMatch(h -> clave.equals(h.get("clave")))) {
                throw ErrorNegocio.validacion("El hallazgo no es del acta vigente.");
            }
            int n = jdbc.sql("""
                    UPDATE opl_respuestas SET resolucion = :r, resolucion_motivo = :m, resuelto_por = :u, resuelto_fecha = :ahora
                    WHERE visita_id = :v AND clave = :c AND decision = 'objeta' AND resolucion IS NULL""")
                    .param("r", resolucion).param("m", m).param("u", quien.id()).param("ahora", ahora()).param("v", visitaId).param("c", clave).update();
            if (n != 1) {
                throw ErrorNegocio.validacion("El hallazgo no tiene una objeción pendiente.");
            }
            visitas.historial(visitaId, quien.id(), quien.usuario(), "ratifica".equals(resolucion) ? "Hallazgo ratificado" : "Hallazgo levantado",
                    clave.substring(3) + ": " + m);
            boolean quedanVigentes = hallazgos(a).stream().anyMatch(h -> !"levantado".equals(h.get("estado")));
            if (!quedanVigentes) {
                jdbc.sql("""
                        UPDATE opl_planes SET estado = 'cerrado', cerrado_en = :ahora, cerrado_por = :u, motivo_cierre = 'Se levantaron todos los hallazgos'
                        WHERE acta_id = :a AND estado <> 'cerrado'""").param("ahora", ahora()).param("u", quien.id()).param("a", a.actaId()).update();
                visitas.historial(visitaId, quien.id(), quien.usuario(), "Plan cerrado", "Se levantaron todos los hallazgos");
                // El seguimiento pendiente ya no tiene qué verificar (spec seguimiento, «Cierre del plan al levantar el último hallazgo»)
                agenda.cancelarSeguimientoPendiente(visitaId, "Todos los hallazgos de la visita anterior fueron levantados por el auditor líder");
                jdbc.sql("INSERT INTO opl_avisos (visita_id, acta_id, tipo, resultado) VALUES (:v, :a, 'plan_cerrado', 'pendiente') ON CONFLICT DO NOTHING")
                        .param("v", visitaId).param("a", a.actaId()).update();
                return true;
            }
            return false;
        }));
        bitacora.registrar("opl_resolucion", "Visita " + a.numero() + ": " + clave + " " + resolucion, quien.id(), quien.usuario());
        String aviso = avisarOpl(a, "ratifica".equals(resolucion) ? "Hallazgo ratificado · visita " + a.numero() : "Hallazgo levantado · visita " + a.numero(),
                "El auditor líder " + ("ratifica".equals(resolucion) ? "ratificó" : "levantó") + " el hallazgo " + clave.substring(3) + " de la visita " + a.numero()
                        + ". Motivo: " + m + ("ratifica".equals(resolucion) ? "\n\nDiligencie quién y cómo de este hallazgo en el plan de mejoramiento." : ""));
        if (cerrado) {
            // Decisión 11.7: al levantar el último hallazgo el plan se cierra y se notifica solo al OPL
            String r = avisarOpl(a, "Plan de mejoramiento cerrado · visita " + a.numero(), "Se levantaron todos los hallazgos de la visita " + a.numero()
                    + ": el plan de mejoramiento queda cerrado.");
            jdbc.sql("UPDATE opl_avisos SET resultado = :r WHERE visita_id = :v AND acta_id = :a AND tipo = 'plan_cerrado'")
                    .param("r", r == null ? "enviado" : Bitacora.cortar(r, 500)).param("v", visitaId).param("a", a.actaId()).update();
            if (r != null) {
                aviso = aviso == null ? r : aviso + " " + r;
            }
        }
        return ("ratifica".equals(resolucion) ? "Hallazgo ratificado." : "Hallazgo levantado.") + (cerrado ? " Se levantaron todos: el plan quedó cerrado." : "")
                + (aviso == null ? "" : " " + aviso);
    }

    /**
     * Respuestas del OPL vistas desde la visita: el equipo (alcance de su equipo) y la coordinación (todo). Sin enlace (sin hallazgos o
     * sin acta) devuelve {@code tieneEnlace = false}. Solo el líder ve el botón de decidir.
     */
    public Map<String, Object> paraEquipo(long visitaId, UsuarioSesion quien, co.com.agsamericas.digsa.reglas.Alcance alcance) {
        boolean ve = (alcance instanceof co.com.agsamericas.digsa.reglas.Alcance.Todo || alcance instanceof co.com.agsamericas.digsa.reglas.Alcance.Equipo)
                && jdbc.sql("SELECT count(*) FROM visitas v JOIN puntos p ON p.id = v.punto_id WHERE v.id = :id AND ("
                        + co.com.agsamericas.digsa.agenda.ServicioConsultaAgenda.condicion(alcance).sql() + ")")
                .params(co.com.agsamericas.digsa.agenda.ServicioConsultaAgenda.condicion(alcance).params()).param("id", visitaId).query(Long.class).single() > 0;
        if (!ve) {
            throw ErrorNegocio.noEncontrado("No existe la visita.");
        }
        Optional<Acceso> a = accesoDeVisita(visitaId);
        if (a.isEmpty()) {
            return Map.of("tieneEnlace", false);
        }
        Map<String, Object> m = new LinkedHashMap<>(detalle(a.get()));
        m.put("tieneEnlace", true);
        m.put("esLider", a.get().liderId() == quien.id());
        return m;
    }

    /**
     * El seguimiento terminó sin acciones abiertas: el plan de la visita de origen queda cerrado con su registro y se avisa solo al OPL
     * (recomendación de la spec: el mismo cierre que al levantar el último hallazgo, duda 22.4).
     */
    @org.springframework.context.event.EventListener
    public void planCerradoSinAbiertas(co.com.agsamericas.digsa.ejecucion.ServicioSeguimiento.PlanCerradoSinAbiertas e) {
        try {
            Optional<Acceso> a = accesoDeVisita(e.visitaId());
            if (a.isEmpty()) {
                return;
            }
            int n = tx.execute(t -> {
                visitas.bloquear(e.visitaId());
                int c = jdbc.sql("""
                        UPDATE opl_planes SET estado = 'cerrado', cerrado_en = :ahora, motivo_cierre = :m
                        WHERE acta_id = :a AND estado IN ('pendiente', 'firmado', 'vencido')""")
                        .param("ahora", ahora()).param("m", "Seguimiento " + e.numero() + " sin acciones abiertas").param("a", a.get().actaId()).update();
                if (c > 0) {
                    visitas.historial(e.visitaId(), null, "sistema", "Plan cerrado", "El seguimiento " + e.numero() + " terminó sin acciones abiertas");
                    jdbc.sql("INSERT INTO opl_avisos (visita_id, acta_id, tipo, resultado) VALUES (:v, :a, 'plan_cerrado', 'pendiente') ON CONFLICT DO NOTHING")
                            .param("v", e.visitaId()).param("a", a.get().actaId()).update();
                }
                return c;
            });
            if (n > 0) {
                String r = avisarOpl(a.get(), "Plan de mejoramiento cerrado · visita " + a.get().numero(), "El seguimiento " + e.numero()
                        + " verificó que todas las acciones del plan de mejoramiento de la visita " + a.get().numero() + " quedaron cerradas: el plan queda cerrado.");
                jdbc.sql("UPDATE opl_avisos SET resultado = :r WHERE acta_id = :a AND tipo = 'plan_cerrado'").param("r", r == null ? "enviado" : Bitacora.cortar(r, 500))
                        .param("a", a.get().actaId()).update();
            }
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(ServicioOpl.class).error("[opl] Cierre del plan de la visita {}", e.visitaId(), ex);
            try {
                visitas.historial(e.visitaId(), null, "sistema", "Cierre del plan fallido", "El seguimiento " + e.numero() + " terminó sin acciones abiertas; cierre el plan a mano");
            } catch (RuntimeException ignorada) {
                // ya quedó en el registro de errores
            }
        }
    }

    // ---------- Avisos ----------

    List<String> correosOpl() {
        return jdbc.sql("SELECT correos FROM opl WHERE activo ORDER BY id LIMIT 1").query((rs, n) -> List.of((String[]) rs.getArray(1).getArray())).optional()
                .orElse(List.of()).stream().map(c -> Correos.normalizar(c)).filter(Correos::valido).distinct().toList();
    }

    /** Aviso al OPL (nunca al punto: decisiones 11.2 y 12.2). @return advertencia si no salió, o null */
    String avisarOpl(Acceso a, String asunto, String cuerpo) {
        return avisar(a, correosOpl(), asunto, cuerpo, "Aviso al OPL");
    }

    String avisarLider(Acceso a, String asunto, String cuerpo) {
        List<String> para = jdbc.sql("SELECT correo FROM usuarios WHERE id = :id AND activo").param("id", a.liderId()).query(String.class).list();
        return avisar(a, para.stream().filter(Correos::valido).toList(), asunto, cuerpo, "Aviso al auditor líder");
    }

    private String avisar(Acceso a, List<String> para, String asunto, String cuerpo, String evento) {
        try {
            if (para.isEmpty()) {
                visitas.historial(a.visitaId(), null, "sistema", evento + " no enviado", "No hay correos registrados");
                return evento + " no enviado: no hay correos registrados.";
            }
            Correo.Resultado r = correo.enviar(new Correo.Mensaje(para, List.of(), asunto,
                    PlantillaCorreo.marco("Visita " + a.numero() + " · " + a.punto(), "<p>" + PlantillaCorreo.texto(cuerpo) + "</p>"), List.of()));
            if (!r.ok()) {
                visitas.historial(a.visitaId(), null, "sistema", evento + " fallido", Bitacora.cortar(Bitacora.sinCorreos(String.valueOf(r.error())), 300));
                return evento + " fallido.";
            }
            return null;
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(ServicioOpl.class).error("[opl] {} de la visita {}", evento, a.visitaId(), e);
            try {
                visitas.historial(a.visitaId(), null, "sistema", evento + " fallido", "Error al enviar el correo");
            } catch (RuntimeException ignorada) {
                // ya quedó en el registro de errores
            }
            return evento + " fallido.";
        }
    }

    static String texto(String s) {
        return s == null ? "" : s.strip();
    }

    static String vacioANulo(String s) {
        String t = texto(s);
        return t.isEmpty() ? null : t;
    }
}
