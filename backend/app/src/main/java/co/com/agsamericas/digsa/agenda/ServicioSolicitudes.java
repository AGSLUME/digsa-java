package co.com.agsamericas.digsa.agenda;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Almacen;
import co.com.agsamericas.digsa.plataforma.BandejaSalida;
import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.PlantillaCorreo;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.plataforma.ValidadorArchivos;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Huellas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reglas.Plazos;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
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
 * Solicitudes de visita a necesidad (decisiones 12, 31 y 33; 9.3, 9.4 y 9.6 del 2026-10-20). La DIGSA y la Sanidad de cada fuerza las
 * registran; el coordinador las agenda como visita a necesidad (sin previo aviso al OPL, Anexo 3 p.14) o las anula. Referencia:
 * controladores/solicitudes.controlador.php del PHP.
 */
@Service
public class ServicioSolicitudes {

    static final int MAX_MOTIVO = 4000;
    static final int DIAS_ATRAS = 60;
    private static final DateTimeFormatter MARCA = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public record Escrito(String nombre, String ruta, long tamano) {
    }

    private final JdbcClient jdbc;
    private final RepositorioVisitas visitas;
    private final ServicioConsultaAgenda consulta;
    private final ValidadorArchivos validador;
    private final Almacen almacen;
    private final BandejaSalida bandeja;
    private final RegistroBitacora bitacora;
    private final TransactionTemplate tx;
    private final Clock reloj;

    public ServicioSolicitudes(JdbcClient jdbc, RepositorioVisitas visitas, ServicioConsultaAgenda consulta, ValidadorArchivos validador, Almacen almacen,
                               BandejaSalida bandeja, RegistroBitacora bitacora, TransactionTemplate tx, Clock reloj) {
        this.jdbc = jdbc;
        this.visitas = visitas;
        this.consulta = consulta;
        this.validador = validador;
        this.almacen = almacen;
        this.bandeja = bandeja;
        this.bitacora = bitacora;
        this.tx = tx;
        this.reloj = reloj;
    }

    private LocalDate hoy() {
        return LocalDate.now(reloj.withZone(Fechas.BOGOTA));
    }

    static boolean esCoordinador(Set<Perfil> perfiles) {
        return perfiles.contains(Perfil.COORDINADOR) || perfiles.contains(Perfil.ADMINISTRADOR);
    }

    /** Entidad que solicita: la DIGSA, o la DISAN o JEFSA de la fuerza del usuario de Sanidad. */
    Optional<String> entidad(Set<Perfil> perfiles, Alcance alcance) {
        if (perfiles.contains(Perfil.DIGSA)) {
            return Optional.of("DIGSA");
        }
        if (perfiles.contains(Perfil.SANIDAD) && alcance instanceof Alcance.Fuerza f) {
            return jdbc.sql("SELECT sanidad FROM par_fuerzas WHERE codigo = :c").param("c", f.codigo()).query(String.class).optional()
                    .filter(s -> !s.isBlank()).map(s -> (s.contains(" · ") ? s.substring(0, s.indexOf(" · ")) : s)).map(s -> s.contains(f.codigo()) ? s : s + " " + f.codigo())
                    .map(s -> s.length() > 60 ? s.substring(0, 60) : s);
        }
        return Optional.empty();
    }

    /** Puntos activos que el usuario puede pedir que se visiten (su alcance). */
    public List<Map<String, Object>> puntosSolicitables(Alcance alcance) {
        return jdbc.sql("SELECT id, nombre, sigla, unidad, fuerza, regional_id FROM puntos WHERE activo ORDER BY nombre").query().listOfRows().stream()
                .filter(p -> Alcance.puedeVerPunto(alcance, new Alcance.Punto(((Number) p.get("id")).longValue(), (String) p.get("fuerza"),
                        p.get("regional_id") == null ? null : ((Number) p.get("regional_id")).longValue())))
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>(p);
                    m.remove("regional_id");
                    return m;
                }).toList();
    }

    public Map<String, Object> registrar(Long puntoId, String fecha, String motivoTexto, MultipartFile archivo, UsuarioSesion quien, Set<Perfil> perfiles,
                                         Alcance alcance) {
        String entidad = entidad(perfiles, alcance).orElseThrow(() -> new ErrorNegocio(HttpStatus.FORBIDDEN,
                "Su usuario no tiene la fuerza asignada: pida al administrador que la registre."));
        // Fuera del alcance responde igual que si el punto no existiera
        RepositorioVisitas.Punto punto = Optional.ofNullable(puntoId).flatMap(visitas::punto).filter(RepositorioVisitas.Punto::activo)
                .filter(p -> Alcance.puedeVerPunto(alcance, new Alcance.Punto(p.id(), p.fuerza(), p.regionalId())))
                .orElseThrow(() -> ErrorNegocio.validacion("Seleccione un punto activo de su alcance."));
        LocalDate f = Fechas.valida(fecha).filter(x -> !x.isAfter(hoy()))
                .orElseThrow(() -> ErrorNegocio.validacion("La fecha de la solicitud es obligatoria y no puede ser futura."));
        if (f.isBefore(hoy().minusDays(DIAS_ATRAS))) {
            throw ErrorNegocio.validacion("La fecha de la solicitud no puede tener más de " + DIAS_ATRAS + " días.");
        }
        String motivo = motivoTexto == null ? "" : motivoTexto.strip();
        int largo = motivo.codePointCount(0, motivo.length()); // igual que char_length de la base
        if (largo < 10 || largo > MAX_MOTIVO) {
            throw ErrorNegocio.validacion("Describa el motivo de la solicitud (de 10 a " + MAX_MOTIVO + " caracteres).");
        }
        String ruta = null;
        String nombre = null;
        ValidadorArchivos.Valido escrito = null;
        if (archivo != null && !archivo.isEmpty()) {
            nombre = nombreArchivo(archivo.getOriginalFilename());
            if (archivo.getSize() > ValidadorArchivos.MAX_ARCHIVO) {
                throw new ErrorNegocio(HttpStatus.PAYLOAD_TOO_LARGE, "El escrito pesa más de " + ValidadorArchivos.MAX_ARCHIVO / (1024 * 1024) + " MB.");
            }
            try {
                escrito = validador.validar(nombre, archivo.getBytes());
            } catch (IOException e) {
                throw ErrorNegocio.validacion("No se pudo leer el escrito.");
            }
            if (!"application/pdf".equals(escrito.tipo())) {
                throw ErrorNegocio.validacion("El escrito debe ser un PDF.");
            }
            ruta = "solicitudes/" + MARCA.format(OffsetDateTime.now(reloj).atZoneSameInstant(Fechas.BOGOTA)) + "_" + Huellas.secreto(8) + ".pdf";
            try {
                almacen.guardar(ruta, escrito.contenido());
            } catch (IOException e) {
                throw new ErrorNegocio(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo guardar el escrito. Intente de nuevo.");
            }
        }
        LocalDate limite = consulta.plazos().limiteNecesidad(f);
        // La solicitud y su aviso al coordinador en la bandeja de salida: o quedan los dos o ninguno (y el escrito se descarta). Antes iban
        // sin transacción: si el aviso fallaba, la solicitud quedaba registrada detrás de un 500 y el usuario la repetía.
        final String rutaEscrito = ruta;
        final String nombreEscrito = nombre;
        final ValidadorArchivos.Valido valido = escrito;
        long[] registro;
        try {
            registro = tx.execute(t -> {
                long nuevo = jdbc.sql("""
                        INSERT INTO visitas_solicitudes (punto_id, solicitante_id, entidad, fecha_solicitud, motivo, archivo_ruta, archivo_nombre,
                          archivo_tamano, archivo_sha256, registrado, actualizado)
                        VALUES (:p, :u, :e, :f, :m, :r, :n, :t, :s, :ahora, :ahora) RETURNING id""")
                        .param("p", punto.id()).param("u", quien.id()).param("e", entidad).param("f", f).param("m", motivo).param("r", rutaEscrito)
                        .param("n", nombreEscrito).param("t", valido == null ? null : (long) valido.contenido().length)
                        .param("s", valido == null ? null : valido.sha256()).param("ahora", OffsetDateTime.now(reloj)).query(Long.class).single();
                return new long[] {nuevo, avisarCoordinadores(nuevo, entidad, punto, f, limite, motivo) ? 1 : 0};
            });
        } catch (RuntimeException e) {
            if (ruta != null) {
                almacen.descartar(ruta);
            }
            throw e;
        }
        long id = registro[0];
        boolean avisado = registro[1] == 1;
        bitacora.registrar("solicitud_registrada", "Solicitud " + id + " · " + entidad + " · punto " + punto.id(), quien.id(), quien.usuario());
        if (!avisado) {
            // El plazo de 5 días hábiles corre: que quede rastro para revisar la bandeja aunque el correo no haya salido
            bitacora.registrar("solicitud_aviso_fallido", "Solicitud " + id + ": el aviso a los coordinadores no salió", quien.id(), quien.usuario());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("limite", limite.toString());
        m.put("mensaje", "Solicitud registrada. El coordinador la agenda como visita a necesidad; plazo: " + Plazos.fechaLarga(limite)
                + " (5 días hábiles, Anexo 3 p.14). Le llegará un correo cuando quede agendada." + (avisado ? "" : " (El aviso al coordinador no salió.)"));
        return m;
    }

    /**
     * Decisión 9.6: correo al coordinador por cada solicitud nueva (a todos los coordinadores activos). Aviso informativo: va por la bandeja
     * de salida (sale en segundos y se reintenta). @return false si no hay a quién avisar
     */
    private boolean avisarCoordinadores(long id, String entidad, RepositorioVisitas.Punto punto, LocalDate fecha, LocalDate limite, String motivo) {
        List<String> para = jdbc.sql("""
                SELECT u.correo FROM usuarios u JOIN usuarios_perfiles up ON up.usuario_id = u.id AND up.perfil_id = :p WHERE u.activo""")
                .param("p", Perfil.COORDINADOR.id()).query(String.class).list().stream().filter(Correos::valido).distinct().toList();
        if (para.isEmpty()) {
            return false;
        }
        String cuerpo = "Nueva solicitud de visita a necesidad N.º " + id + "\n\nEntidad: " + entidad + "\nPunto: " + punto.nombre() + "\nFecha del escrito: "
                + Plazos.fechaLarga(fecha) + "\nPlazo máximo para la visita: " + Plazos.fechaLarga(limite) + " (5 días hábiles, Anexo 3 p.14)\n\nMotivo:\n"
                + (motivo.length() > 600 ? motivo.substring(0, 600) + "…" : motivo) + "\n\nAgéndela en la Suite DIGSA › Solicitudes de visita.";
        bandeja.encolar("solicitud_nueva", "solicitud:" + id, new Correo.Mensaje(para, List.of(), "Solicitud de visita a necesidad N.º " + id + " · " + punto.nombre(),
                PlantillaCorreo.marco("Solicitud de visita a necesidad", "<p>" + PlantillaCorreo.texto(cuerpo) + "</p>"), List.of()));
        return true;
    }

    /** Decisión 9.4: cada solicitante ve solo sus solicitudes; el coordinador (y el administrador) ve todas. */
    public List<Map<String, Object>> listar(String estado, UsuarioSesion quien, Set<Perfil> perfiles) {
        boolean todas = esCoordinador(perfiles);
        String e = estado != null && Set.of("pendiente", "programada", "anulada").contains(estado) ? estado : null;
        Plazos plazos = consulta.plazos();
        LocalDate hoy = hoy();
        var q = jdbc.sql("""
                SELECT s.id, s.punto_id, p.nombre AS punto, p.unidad, p.fuerza, s.entidad, s.fecha_solicitud, s.motivo, s.estado, s.visita_id,
                       v.numero AS visita_numero, s.registrado, s.motivo_anulacion, (s.archivo_ruta IS NOT NULL) AS tiene_archivo, u.nombre AS solicitante
                FROM visitas_solicitudes s JOIN puntos p ON p.id = s.punto_id JOIN usuarios u ON u.id = s.solicitante_id
                LEFT JOIN visitas v ON v.id = s.visita_id
                WHERE (:todas OR s.solicitante_id = :yo) AND (CAST(:estado AS text) IS NULL OR s.estado = :estado)
                ORDER BY (s.estado = 'pendiente') DESC, s.fecha_solicitud, s.id LIMIT 1000""")
                .param("todas", todas).param("yo", quien.id()).param("estado", e);
        return q.query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            LocalDate f = rs.getDate("fecha_solicitud").toLocalDate();
            LocalDate limite = plazos.limiteNecesidad(f);
            m.put("id", rs.getLong("id"));
            m.put("puntoId", rs.getLong("punto_id"));
            m.put("punto", rs.getString("punto"));
            m.put("unidad", rs.getString("unidad"));
            m.put("fuerza", rs.getString("fuerza"));
            m.put("entidad", rs.getString("entidad"));
            m.put("fechaSolicitud", f.toString());
            m.put("limite", limite.toString());
            m.put("vencida", "pendiente".equals(rs.getString("estado")) && hoy.isAfter(limite));
            m.put("motivo", rs.getString("motivo"));
            m.put("estado", rs.getString("estado"));
            m.put("visitaId", rs.getObject("visita_id"));
            m.put("visitaNumero", rs.getString("visita_numero"));
            m.put("registrado", ServicioConsultaAgendaTexto.local(rs.getObject("registrado", OffsetDateTime.class)));
            m.put("motivoAnulacion", rs.getString("motivo_anulacion"));
            m.put("tieneArchivo", rs.getBoolean("tiene_archivo"));
            m.put("solicitante", todas ? rs.getString("solicitante") : null);
            return m;
        }).list();
    }

    public void anular(long id, String motivo, UsuarioSesion quien) {
        String m = motivo == null ? "" : motivo.strip();
        if (m.isEmpty() || m.codePointCount(0, m.length()) > 800) {
            throw ErrorNegocio.validacion("Registre el motivo de la anulación (máximo 800 caracteres).");
        }
        int n = jdbc.sql("""
                UPDATE visitas_solicitudes SET estado = 'anulada', motivo_anulacion = :m, actualizado_por = :u, actualizado = :ahora
                WHERE id = :id AND estado = 'pendiente'""").param("m", m).param("u", quien.id()).param("ahora", OffsetDateTime.now(reloj)).param("id", id)
                .update();
        if (n != 1) {
            throw ErrorNegocio.validacion("La solicitud no existe o ya no está pendiente.");
        }
        bitacora.registrar("solicitud_anulada", "Solicitud " + id + " anulada", quien.id(), quien.usuario());
    }

    /** El escrito: lo ven el coordinador y quien lo registró (fuera de eso, como si no existiera). */
    public Escrito escrito(long id, UsuarioSesion quien, Set<Perfil> perfiles) {
        return jdbc.sql("SELECT archivo_nombre, archivo_ruta, archivo_tamano, solicitante_id FROM visitas_solicitudes WHERE id = :id AND archivo_ruta IS NOT NULL")
                .param("id", id).query((rs, n) -> rs.getLong(4) == quien.id() || esCoordinador(perfiles)
                        ? new Escrito(rs.getString(1), rs.getString(2), rs.getLong(3)) : null)
                .optional().filter(java.util.Objects::nonNull).orElseThrow(() -> ErrorNegocio.noEncontrado("El escrito no existe."));
    }

    public InputStream abrir(Escrito e) throws IOException {
        return almacen.leer(e.ruta());
    }

    // ---------- Integración con la agenda ----------

    /** Solicitud que atiende la visita (programada), si la hay: su punto y su fecha mandan al editar la visita. */
    Optional<Map<String, Object>> deVisita(long visitaId) {
        return jdbc.sql("SELECT punto_id, fecha_solicitud FROM visitas_solicitudes WHERE visita_id = :v").param("v", visitaId).query().listOfRows().stream()
                .findFirst();
    }

    /** El aviso sale después de confirmar: cualquier falla queda en el historial y no convierte en error una operación ya hecha. */
    String avisarSolicitanteSeguro(long solicitudId, RepositorioVisitas.Visita v, boolean cancelada, String motivo, UsuarioSesion quien) {
        try {
            return avisarSolicitante(solicitudId, v, cancelada, motivo, quien);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(ServicioSolicitudes.class).error("[solicitudes] Falló el aviso al solicitante de la solicitud {}", solicitudId, e);
            return "No se pudo avisar al solicitante; avísele por otro medio.";
        }
    }

    /** Solicitud pendiente para agendar (sin bloquear): punto y fecha que mandan sobre lo que envíe el formulario. */
    Optional<Map<String, Object>> pendiente(long id) {
        return jdbc.sql("SELECT punto_id, fecha_solicitud FROM visitas_solicitudes WHERE id = :id AND estado = 'pendiente'").param("id", id).query()
                .listOfRows().stream().findFirst();
    }

    /** Dentro de la transacción de programar: la solicitud pasa a programada con UPDATE condicionado (si otro la agendó, se deshace todo). */
    void marcarProgramada(long solicitudId, long visitaId, long usuarioId) {
        int n = jdbc.sql("""
                UPDATE visitas_solicitudes SET estado = 'programada', visita_id = :v, actualizado_por = :u, actualizado = :ahora
                WHERE id = :id AND estado = 'pendiente'""").param("v", visitaId).param("u", usuarioId).param("ahora", OffsetDateTime.now(reloj))
                .param("id", solicitudId).update();
        if (n != 1) {
            throw ErrorNegocio.validacion("La solicitud no existe o ya no está pendiente.");
        }
    }

    /** Dentro de la transacción de cancelar: la solicitud vuelve a pendiente sin visita (su plazo sigue corriendo). @return su id */
    Optional<Long> liberar(long visitaId, long usuarioId) {
        return jdbc.sql("""
                UPDATE visitas_solicitudes SET estado = 'pendiente', visita_id = NULL, actualizado_por = :u, actualizado = :ahora
                WHERE visita_id = :v RETURNING id""").param("u", usuarioId).param("ahora", OffsetDateTime.now(reloj)).param("v", visitaId)
                .query(Long.class).optional();
    }

    /**
     * Aviso a quien solicitó (nunca al OPL): al agendar la visita (decisión 31) o al cancelarla (decisión 9.3). Después de confirmar la
     * transacción. Aviso informativo: va por la bandeja de salida, que lo envía en segundos y lo reintenta; el historial lo registra.
     */
    String avisarSolicitante(long solicitudId, RepositorioVisitas.Visita v, boolean cancelada, String motivo, UsuarioSesion quien) {
        Map<String, Object> s = jdbc.sql("""
                SELECT s.entidad, s.fecha_solicitud, u.nombre, u.correo, u.activo FROM visitas_solicitudes s JOIN usuarios u ON u.id = s.solicitante_id
                WHERE s.id = :id""").param("id", solicitudId).query().listOfRows().stream().findFirst().orElse(null);
        if (s == null || !Boolean.TRUE.equals(s.get("activo")) || !Correos.valido(String.valueOf(s.get("correo")))) {
            visitas.historial(v.id(), quien.id(), quien.usuario(), "Aviso al solicitante no enviado", "El solicitante está inactivo o no tiene correo válido.");
            return "No se avisó al solicitante: está inactivo o no tiene correo válido. Avísele por otro medio.";
        }
        LocalDate fecha = ((java.sql.Date) s.get("fecha_solicitud")).toLocalDate();
        String asunto = (cancelada ? "Visita a necesidad cancelada " : "Visita a necesidad agendada ") + v.numero() + " · " + v.puntoNombre();
        String cuerpo = "Cordial saludo, " + s.get("nombre") + ":\n\nLa solicitud de visita a necesidad de " + s.get("entidad") + " del " + Plazos.fechaLarga(fecha)
                + (cancelada
                        ? " tenía agendada la visita " + v.numero() + ", que fue cancelada.\nMotivo: " + motivo
                                + "\n\nLa solicitud vuelve a quedar pendiente para que el coordinador la reprograme; su plazo se sigue contando desde la fecha del escrito."
                        : " quedó agendada:\n\n- Visita N.º: " + v.numero() + "\n- Punto: " + v.puntoNombre() + "\n- Fecha: " + Plazos.fechaLarga(v.fecha())
                                + (v.hora() == null ? "" : ", " + v.hora() + " h")
                                + "\n\nLa visita a necesidad se hace sin previo aviso al operador logístico (Anexo 3 p.14): por favor no comparta esta "
                                + "información con el OPL ni con el punto.")
                + "\n\nInterventoría DIGSA";
        tx.executeWithoutResult(t -> {
            long cid = bandeja.encolar(cancelada ? "solicitud_cancelada" : "solicitud_agendada", "solicitud:" + solicitudId,
                    new Correo.Mensaje(List.of(String.valueOf(s.get("correo"))), List.of(), asunto,
                            PlantillaCorreo.marco(cancelada ? "Visita a necesidad cancelada" : "Visita a necesidad agendada", "<p>" + PlantillaCorreo.texto(cuerpo) + "</p>"),
                            List.of()));
            visitas.historial(v.id(), quien.id(), quien.usuario(), "Aviso al solicitante", "Solicitud " + solicitudId + " · correo " + cid + " en la bandeja de salida");
        });
        return "Se avisó al solicitante (el correo sale en unos segundos).";
    }

    static String nombreArchivo(String original) {
        String n = original == null ? "escrito.pdf" : original.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"<>|*?:]", "_").strip();
        if (n.isEmpty()) {
            n = "escrito.pdf";
        }
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }

    /** Formato de fecha y hora local para las respuestas. */
    static final class ServicioConsultaAgendaTexto {
        private ServicioConsultaAgendaTexto() {
        }

        static String local(OffsetDateTime t) {
            return t == null ? null : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(t.atZoneSameInstant(Fechas.BOGOTA));
        }
    }
}
