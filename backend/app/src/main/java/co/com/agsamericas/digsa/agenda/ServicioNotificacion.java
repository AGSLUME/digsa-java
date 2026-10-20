package co.com.agsamericas.digsa.agenda;

import co.com.agsamericas.digsa.catalogos.ServicioCatalogos;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.PlantillaCorreo;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Plazos;
import co.com.agsamericas.digsa.reglas.Reparto;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Notificación previa de la visita (Anexo 3 p.9: 15 días antes a las DISANES y al OPL). Se registra antes de enviar; un fallo del
 * correo no deshace nada de la agenda. Referencia: docs/especificaciones/agenda-notificacion.
 */
@Service
public class ServicioNotificacion {

    public static final String AL_PROGRAMAR = "Envío automático al programar";
    public static final String AL_REPROGRAMAR = "Envío automático al reprogramar";
    public static final String AL_REASIGNAR = "Envío automático al reasignar el equipo";
    public static final String MANUAL = "Envío manual del coordinador";
    static final int MAX_DESTINATARIOS = 15;
    /** Tope de la notificación calculada (fuerza + OPL + DIGSA + punto); la tabla admite hasta 50. */
    static final int MAX_AUTOMATICOS = 50;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ServicioNotificacion.class);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    /** Destinatario con su origen (DISAN EJC, OPL, DIGSA, contacto del punto) y si es obligatorio. */
    public record Destinatario(String correo, String origen, boolean obligatorio) {
    }

    public record Calculada(List<Destinatario> destinatarios, List<String> faltan, String asunto, String cuerpo) {
    }

    /** Lo que el coordinador cambió a mano (null = lo calculado). */
    public record Manual(List<String> destinatarios, String asunto, String cuerpo) {
    }

    public record Resultado(boolean ok, String mensaje) {
    }

    private final RepositorioVisitas visitas;
    private final JdbcClient jdbc;
    private final Correo correo;
    private final ServicioCatalogos catalogos;
    private final RegistroBitacora bitacora;
    private final TransactionTemplate tx;
    private final Clock reloj;

    public ServicioNotificacion(RepositorioVisitas visitas, JdbcClient jdbc, Correo correo, ServicioCatalogos catalogos, RegistroBitacora bitacora,
                                TransactionTemplate tx, Clock reloj) {
        this.visitas = visitas;
        this.jdbc = jdbc;
        this.correo = correo;
        this.catalogos = catalogos;
        this.bitacora = bitacora;
        this.tx = tx;
        this.reloj = reloj;
    }

    /** Destinatarios, asunto y cuerpo calculados (la pantalla los muestra antes de la notificación manual). */
    public Calculada calcular(RepositorioVisitas.Visita v, String sufijoAsunto) {
        List<Destinatario> d = new ArrayList<>();
        List<String> faltan = new ArrayList<>();
        Map<String, Object> fuerza = jdbc.sql("SELECT nombre, sanidad, correos FROM par_fuerzas WHERE codigo = :c").param("c", v.fuerza())
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sanidad", rs.getString(2));
                    m.put("correos", List.of((String[]) rs.getArray(3).getArray()));
                    return m;
                }).optional().orElse(Map.of("correos", List.of()));
        String sanidad = (String) fuerza.get("sanidad");
        if (sanidad != null && !sanidad.isBlank()) {
            String etiqueta = sanidad.contains(" · ") ? sanidad.substring(0, sanidad.indexOf(" · ")) : sanidad;
            agregar(d, faltan, correos(fuerza.get("correos")), etiqueta, true);
        } else if (!"NA".equals(v.fuerza())) {
            // Toda fuerza (salvo «No aplica» del nivel central) tiene su DISAN o JEFSA: sin ella la notificación no está completa
            faltan.add("DISAN/JEFSA de " + v.fuerza() + " (registre la sanidad de la fuerza en Catálogos)");
        }
        List<String> opl = jdbc.sql("SELECT correos FROM opl WHERE activo ORDER BY id LIMIT 1")
                .query((rs, n) -> List.of((String[]) rs.getArray(1).getArray())).optional().orElse(List.of());
        agregar(d, faltan, opl, "OPL", true);
        if ("NC".equals(v.unidad())) {
            // Nivel central: también a la DIGSA (octava ronda, duda 1.2)
            String digsa = catalogos.textoParametro("correo_digsa", "");
            agregar(d, faltan, List.of(digsa.split("[,;\\s]+")), "DIGSA", true);
        }
        List<String> punto = jdbc.sql("SELECT correos FROM puntos WHERE id = :id").param("id", v.puntoId())
                .query((rs, n) -> List.of((String[]) rs.getArray(1).getArray())).optional().orElse(List.of());
        agregar(d, faltan, punto, "Contacto del punto", false);

        String asunto = "Notificación de visita de seguimiento " + v.numero() + " · " + v.puntoNombre() + (sufijoAsunto == null ? "" : sufijoAsunto);
        return new Calculada(d, faltan, corto(asunto, 200), cuerpo(v));
    }

    private static List<String> correos(Object lista) {
        List<String> r = new ArrayList<>();
        if (lista instanceof List<?> l) {
            l.forEach(x -> r.add(String.valueOf(x)));
        }
        return r;
    }

    private static void agregar(List<Destinatario> d, List<String> faltan, List<String> correos, String origen, boolean obligatorio) {
        int validos = 0;
        for (String c : correos) {
            String n = Correos.normalizar(c == null ? "" : c.strip());
            if (n.isEmpty() || !Correos.valido(n)) {
                continue;
            }
            validos++;
            if (d.stream().noneMatch(x -> x.correo().equalsIgnoreCase(n))) {
                d.add(new Destinatario(n, origen, obligatorio));
            }
        }
        if (obligatorio && validos == 0) {
            faltan.add(origen);
        }
    }

    String cuerpo(RepositorioVisitas.Visita v) {
        String tipo = visitas.tipo(v.tipo()).map(RepositorioVisitas.Tipo::nombre).orElse(v.tipo());
        Map<String, Object> lugar = jdbc.sql("""
                SELECT m.nombre AS municipio, d.nombre AS departamento, p.direccion FROM puntos p
                LEFT JOIN par_municipios m ON m.codigo = p.municipio LEFT JOIN par_departamentos d ON d.codigo = m.departamento WHERE p.id = :id""")
                .param("id", v.puntoId()).query().singleRow();
        StringBuilder b = new StringBuilder();
        b.append("Número de la visita: ").append(v.numero()).append('\n');
        b.append("Fecha: ").append(Plazos.fechaLarga(v.fecha())).append(v.hora() == null ? "" : ", " + HORA.format(v.hora()) + " h").append('\n');
        b.append("Tipo de visita: ").append(tipo).append('\n');
        b.append("Unidad de intervención: ").append(v.unidad()).append(" · ").append(v.puntoNombre())
                .append("ESM".equals(v.unidad()) && v.modalidad() != null ? " (" + v.modalidad() + ")" : "").append('\n');
        b.append("Municipio: ").append(nulo(lugar.get("municipio"))).append(", ").append(nulo(lugar.get("departamento"))).append('\n');
        b.append("Dirección: ").append(nulo(lugar.get("direccion"))).append("\n\n");
        b.append("Alcance específico:\n").append(v.alcance()).append("\n\n");
        b.append("Equipo auditor:\n");
        for (RepositorioVisitas.Miembro m : visitas.equipo(v.id())) {
            b.append("- ").append(m.nombre()).append(" · ").append(Reparto.ROLES.getOrDefault(m.rol(), "Otro"))
                    .append("lider".equals(m.funcion()) ? " (auditor líder)" : "")
                    .append(" · ").append(m.correo())
                    .append(" · documento ").append(m.documento() == null || m.documento().isBlank() ? "sin registrar" : m.documento()).append('\n');
        }
        b.append("\nLos datos del equipo auditor se comparten solo para el control de ingreso al establecimiento (Ley 1581 de 2012); ")
                .append("no se pueden usar para otro fin.\n\nInterventoría DIGSA");
        return b.toString();
    }

    private static String nulo(Object o) {
        return o == null ? "sin registrar" : String.valueOf(o);
    }

    /**
     * Notifica la visita: valida y registra la notificación como pendiente (con la visita bloqueada), envía un solo correo y deja el
     * resultado. Si salió, la visita pasa a notificada. Nunca lanza por el correo: devuelve el resultado.
     *
     * @param manual null = destinatarios, asunto y cuerpo calculados
     */
    /**
     * Envío automático (al programar, reprogramar o reasignar): la operación de la agenda ya quedó confirmada, así que nunca lanza;
     * cualquier problema se devuelve como «no se notificó» para que el coordinador use «Notificar».
     */
    public Resultado notificarAutomatico(long visitaId, String origen, String sufijoAsunto, UsuarioSesion quien) {
        try {
            return notificar(visitaId, origen, sufijoAsunto, null, quien);
        } catch (ErrorNegocio e) {
            return new Resultado(false, "No se notificó: " + e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("[notificacion] Falla inesperada al notificar la visita {} ({})", visitaId, origen, e);
            return new Resultado(false, "No se notificó por un error del servidor. Use «Notificar» en la agenda.");
        }
    }

    /** Lo que había en la visita cuando se armó el correo: si cambia mientras sale, la visita no queda como notificada. */
    private record Foto(String estado, java.time.LocalDate fecha, java.time.LocalTime hora, List<Long> equipo) {
    }

    private Foto foto(RepositorioVisitas.Visita v) {
        return new Foto(v.estado(), v.fecha(), v.hora(), visitas.equipo(v.id()).stream().map(RepositorioVisitas.Miembro::id).toList());
    }

    public Resultado notificar(long visitaId, String origen, String sufijoAsunto, Manual manual, UsuarioSesion quien) {
        record Registro(long id, List<String> para, Set<String> obligatorios, String asunto, String cuerpo, String estadoPrevio,
                        boolean cambioEquipo, Foto foto, String numero) {
        }
        Registro reg = tx.execute(t -> {
            RepositorioVisitas.Visita v = visitas.bloquear(visitaId).orElseThrow(() -> ErrorNegocio.noEncontrado("La visita no existe."));
            // Una notificación que quedó «pendiente» más de 10 minutos (el servidor se cayó entre el registro y el resultado) no se
            // sabe si salió: se cierra como fallida para que no bloquee ni quede así para siempre
            int huerfanas = jdbc.sql("""
                    UPDATE visitas_notificaciones SET estado = 'fallida', error = 'Resultado desconocido: el envío no terminó', resultado = :ahora
                    WHERE visita_id = :v AND estado = 'pendiente' AND creado <= :h""")
                    .param("ahora", OffsetDateTime.now(reloj)).param("v", visitaId).param("h", OffsetDateTime.now(reloj).minus(Duration.ofMinutes(10)))
                    .update();
            if (huerfanas > 0) {
                visitas.historial(visitaId, quien.id(), quien.usuario(), "Notificación sin resultado",
                        "Una notificación anterior no terminó su envío; no se sabe si salió");
            }
            if ("no-opl".equals(v.aviso())) {
                throw ErrorNegocio.validacion("La visita a necesidad se hace sin previo aviso al OPL (Anexo 3 p.14).");
            }
            boolean cambioEquipo = v.equipoSinNotificar() && Set.of("notificada", "reprogramada").contains(v.estado());
            if (!Set.of("programada", "reprogramada").contains(v.estado()) && !cambioEquipo) {
                throw ErrorNegocio.validacion("La visita ya está notificada o no está por ejecutar.");
            }
            OffsetDateTime hace10 = OffsetDateTime.now(reloj).minus(Duration.ofMinutes(10));
            long pendientes = jdbc.sql("SELECT count(*) FROM visitas_notificaciones WHERE visita_id = :v AND estado = 'pendiente' AND creado > :h")
                    .param("v", visitaId).param("h", hace10).query(Long.class).single();
            if (pendientes > 0) {
                throw ErrorNegocio.validacion("Ya hay una notificación de esta visita en envío. Espere unos minutos y revise el historial.");
            }
            Calculada c = calcular(v, sufijoAsunto);
            List<String> para;
            String asunto;
            String cuerpo;
            if (manual == null) {
                if (!c.faltan().isEmpty()) {
                    visitas.historial(visitaId, quien.id(), quien.usuario(), "Notificación pendiente",
                            "Faltan los correos de: " + String.join(", ", c.faltan()));
                    return new Registro(0, List.of(), Set.of(), null, null, v.estado(), cambioEquipo, null, v.numero());
                }
                para = c.destinatarios().stream().map(Destinatario::correo).toList();
                if (para.size() > MAX_AUTOMATICOS) {
                    throw ErrorNegocio.validacion("La notificación tendría " + para.size() + " destinatarios (máximo " + MAX_AUTOMATICOS
                            + "): revise los correos de la fuerza, del OPL y del punto.");
                }
                asunto = c.asunto();
                cuerpo = c.cuerpo();
            } else {
                para = validarManual(manual, c, v);
                asunto = manual.asunto() == null || manual.asunto().isBlank() ? c.asunto() : sinSaltos(manual.asunto());
                cuerpo = manual.cuerpo() == null || manual.cuerpo().isBlank() ? c.cuerpo() : manual.cuerpo().strip();
                if (asunto.length() > 200 || !asunto.contains(v.numero())) {
                    throw ErrorNegocio.validacion("El asunto debe incluir el número de la visita (" + v.numero() + ") y tener máximo 200 caracteres.");
                }
                if (cuerpo.length() > 6000) {
                    throw ErrorNegocio.validacion("El cuerpo de la notificación tiene máximo 6000 caracteres.");
                }
            }
            long id = jdbc.sql("""
                    INSERT INTO visitas_notificaciones (visita_id, destinatarios, asunto, cuerpo, origen, usuario_id, creado)
                    VALUES (:v, :d, :a, :c, :o, :u, :ahora) RETURNING id""")
                    .param("v", visitaId).param("d", para.toArray(String[]::new)).param("a", asunto).param("c", cuerpo).param("o", origen)
                    .param("u", quien.id()).param("ahora", OffsetDateTime.now(reloj)).query(Long.class).single();
            Set<String> obligatorios = new java.util.HashSet<>();
            c.destinatarios().stream().filter(Destinatario::obligatorio).forEach(d -> obligatorios.add(d.correo().toLowerCase(java.util.Locale.ROOT)));
            return new Registro(id, para, obligatorios, asunto, cuerpo, v.estado(), cambioEquipo, foto(v), v.numero());
        });
        if (reg.id() == 0) {
            RepositorioVisitas.Visita v = visitas.porId(visitaId).orElseThrow();
            Calculada c = calcular(v, null);
            return new Resultado(false, "No se notificó: faltan los correos de " + String.join(", ", c.faltan())
                    + ". Regístrelos en Catálogos o en Puntos y auditores y use «Notificar» en la agenda.");
        }

        Correo.Resultado r = correo.enviar(new Correo.Mensaje(reg.para(), List.of(), reg.asunto(),
                PlantillaCorreo.marco("Notificación de visita de seguimiento", "<p>" + PlantillaCorreo.texto(reg.cuerpo()) + "</p>"), List.of()));
        // Entrega parcial: si rechazaron a un destinatario obligatorio (DISAN/JEFSA, OPL, DIGSA), la visita no queda notificada
        List<String> rechazados = r.ok() ? r.rechazados() : reg.para();
        long obligatoriosRechazados = rechazados.stream().filter(x -> reg.obligatorios().contains(x.toLowerCase(java.util.Locale.ROOT))).count();
        boolean completa = r.ok() && obligatoriosRechazados == 0;
        String estadoEnvio = !r.ok() ? "fallida" : rechazados.isEmpty() ? "enviada" : "parcial";
        String error = !r.ok() ? corto(Bitacora.sinCorreos(r.error() == null ? "error desconocido" : r.error()), 500)
                : rechazados.isEmpty() ? null : "Rechazados " + rechazados.size() + " de " + reg.para().size() + " destinatarios"
                        + (obligatoriosRechazados > 0 ? " (" + obligatoriosRechazados + " obligatorios)" : "");
        String resultado = tx.execute(t -> {
            jdbc.sql("UPDATE visitas_notificaciones SET estado = :e, error = :err, resultado = :ahora WHERE id = :id")
                    .param("e", estadoEnvio).param("err", error).param("ahora", OffsetDateTime.now(reloj)).param("id", reg.id()).update();
            if (!completa) {
                visitas.historial(visitaId, quien.id(), quien.usuario(), r.ok() ? "Notificación incompleta" : "Notificación fallida", origen + ". " + error);
                return "incompleta";
            }
            // La visita se bloquea de nuevo: si mientras salía el correo cambió la fecha, la hora, el equipo o el estado, el correo
            // describe una visita que ya no es esa y la visita no se marca como notificada
            RepositorioVisitas.Visita ahora = visitas.bloquear(visitaId).orElseThrow();
            if (!foto(ahora).equals(reg.foto())) {
                visitas.historial(visitaId, quien.id(), quien.usuario(), "Notificación desactualizada",
                        origen + ". La visita cambió mientras salía el correo: notifique de nuevo");
                return "desactualizada";
            }
            boolean cambio = visitas.cambiarEstado(visitaId, Set.of("programada", "reprogramada"), "notificada");
            if (!cambio && !reg.cambioEquipo()) {
                visitas.historial(visitaId, quien.id(), quien.usuario(), "Notificación desactualizada", origen + ". La visita ya no está por notificar");
                return "desactualizada";
            }
            visitas.marcarEquipoSinNotificar(visitaId, false);
            String evento = reg.cambioEquipo() ? "Notificado el cambio de equipo"
                    : "reprogramada".equals(reg.estadoPrevio()) ? "Notificada la nueva fecha" : "Notificada";
            visitas.historial(visitaId, quien.id(), quien.usuario(), evento, origen + ". Destinatarios: " + reg.para().size()
                    + (rechazados.isEmpty() ? "" : "; rechazados: " + rechazados.size()));
            return "ok";
        });
        switch (resultado) {
            case "ok" -> {
                bitacora.registrar("visita_notificada", "Visita " + reg.numero() + " notificada (" + origen + ") a " + reg.para().size() + " destinatarios",
                        quien.id(), quien.usuario());
                return new Resultado(true, "Notificación enviada a " + reg.para().size() + " destinatarios."
                        + (rechazados.isEmpty() ? "" : " " + rechazados.size() + " la rechazaron."));
            }
            case "desactualizada" -> {
                bitacora.registrar("visita_notificacion_fallida", "Visita " + reg.numero() + ": la notificación salió pero la visita cambió", quien.id(),
                        quien.usuario());
                return new Resultado(false, "El correo salió, pero la visita cambió mientras se enviaba: notifíquela de nuevo con los datos actuales.");
            }
            default -> {
                bitacora.registrar("visita_notificacion_fallida", "Visita " + reg.numero() + ": notificación " + estadoEnvio + " (" + origen + ")",
                        quien.id(), quien.usuario());
                return new Resultado(false, (r.ok() ? "La notificación no llegó a destinatarios obligatorios (" + error + ")"
                        : "No se pudo enviar la notificación (" + error + ")") + ". Use «Notificar» en la agenda cuando el correo funcione.");
            }
        }
    }

    /** Sin saltos de línea ni tabuladores (no inyectan cabeceras en el asunto del correo ni líneas en el registro). */
    static String sinSaltos(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n\\t]+", " ").strip();
    }

    /** Notificación manual: de 1 a 15 correos válidos, sin quitar a los obligatorios (DISAN/JEFSA, OPL y DIGSA del nivel central). */
    private static List<String> validarManual(Manual m, Calculada c, RepositorioVisitas.Visita v) {
        List<String> para = new ArrayList<>();
        for (String x : m.destinatarios() == null ? List.<String>of() : m.destinatarios()) {
            String n = Correos.normalizar(x == null ? "" : x.strip());
            if (n.isEmpty()) {
                continue;
            }
            if (!Correos.valido(n)) {
                throw ErrorNegocio.validacion("El correo «" + Bitacora.cortar(x, 60) + "» no es válido.");
            }
            if (para.stream().noneMatch(p -> p.equalsIgnoreCase(n))) {
                para.add(n);
            }
        }
        if (para.isEmpty() || para.size() > MAX_DESTINATARIOS) {
            throw ErrorNegocio.validacion("La notificación debe tener de 1 a " + MAX_DESTINATARIOS + " destinatarios.");
        }
        if (!c.faltan().isEmpty()) {
            throw ErrorNegocio.validacion("Faltan los correos de " + String.join(", ", c.faltan()) + ": regístrelos antes de notificar.");
        }
        List<String> quitados = c.destinatarios().stream().filter(Destinatario::obligatorio)
                .filter(d -> para.stream().noneMatch(p -> p.equalsIgnoreCase(d.correo()))).map(Destinatario::origen).distinct().toList();
        if (!quitados.isEmpty()) {
            throw ErrorNegocio.validacion("La notificación debe incluir a las DISANES y al OPL (Anexo 3 p.9)"
                    + ("NC".equals(v.unidad()) ? " y a la DIGSA" : "") + ". Faltan: " + String.join(", ", quitados) + ".");
        }
        return para;
    }

    public List<Map<String, Object>> deVisita(long visitaId) {
        return jdbc.sql("""
                SELECT n.creado, n.origen, n.estado, n.error, cardinality(n.destinatarios) AS destinatarios, n.asunto, u.usuario
                FROM visitas_notificaciones n LEFT JOIN usuarios u ON u.id = n.usuario_id WHERE n.visita_id = :v ORDER BY n.id""")
                .param("v", visitaId).query().listOfRows();
    }

    static String corto(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
