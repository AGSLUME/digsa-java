package co.com.agsamericas.digsa.agenda;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.EstadosVisita;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Reparto;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Agenda del coordinador: programar (eligiendo los componentes y a qué auditor va cada uno, Anexo 5 prueba 11), editar, reprogramar,
 * cancelar, reasignar el equipo, mover partes e iniciar. Todo cambio de estado es condicionado al estado leído; las notificaciones
 * salen después de confirmar la transacción y un fallo del correo no la deshace.
 */
@Service
public class ServicioAgenda {

    static final int MAX_ALCANCE = 5000;
    static final int MAX_MOTIVO = 800;
    /** Antelación de la notificación al OPL (Anexo 3 p.9): al reasignar con menos días se advierte (decisión 9.8). */
    static final int DIAS_AVISO_OPL = 15;

    public record Datos(String tipo, Long puntoId, String fecha, String hora, Long liderId, List<Long> otros, String alcance,
                        List<String> componentes, Map<String, Long> reparto, String fechaSolicitud, Boolean notificar, Long solicitudId) {
        /** La solicitud manda: visita a necesidad, a su punto y con la fecha de su escrito (decisión 31). */
        Datos desdeSolicitud(long puntoDeLaSolicitud, String fechaDeLaSolicitud) {
            return new Datos("necesidad", puntoDeLaSolicitud, fecha, hora, liderId, otros, alcance, componentes, reparto, fechaDeLaSolicitud, false, solicitudId);
        }
    }

    public record Resultado(long id, String numero, String mensaje, Boolean notificacionOk, List<String> advertencias) {
    }

    /** Datos ya validados. */
    record Valido(RepositorioVisitas.Tipo tipo, RepositorioVisitas.Punto punto, LocalDate fecha, LocalTime hora, long lider, List<Long> otros,
                  List<Reparto.Integrante> equipo, String alcance, List<String> componentes, LocalDate fechaSolicitud,
                  Map<String, Long> reparto, Map<String, String> asignadoPor) {
    }

    private final RepositorioVisitas visitas;
    private final ServicioNotificacion notificacion;
    private final ServicioSolicitudes solicitudes;
    private final RegistroBitacora bitacora;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock reloj;

    public ServicioAgenda(RepositorioVisitas visitas, ServicioNotificacion notificacion, ServicioSolicitudes solicitudes, RegistroBitacora bitacora,
                          JdbcClient jdbc, TransactionTemplate tx, Clock reloj) {
        this.visitas = visitas;
        this.notificacion = notificacion;
        this.solicitudes = solicitudes;
        this.bitacora = bitacora;
        this.jdbc = jdbc;
        this.tx = tx;
        this.reloj = reloj;
    }

    private LocalDate hoy() {
        return LocalDate.now(reloj.withZone(Fechas.BOGOTA));
    }

    // ---------- Programar y editar ----------

    public Resultado programar(Datos entrada, UsuarioSesion quien) {
        Datos d = entrada;
        if (entrada.solicitudId() != null) {
            Map<String, Object> s = solicitudes.pendiente(entrada.solicitudId())
                    .orElseThrow(() -> ErrorNegocio.validacion("La solicitud no existe o ya no está pendiente."));
            d = entrada.desdeSolicitud(((Number) s.get("punto_id")).longValue(), s.get("fecha_solicitud").toString());
        }
        Datos datos = d;
        Valido v = validar(datos, true, quien);
        long id = tx.execute(t -> {
            long nuevo = visitas.crear(v.tipo().codigo(), v.punto().id(), v.fecha(), v.hora(), v.lider(), v.alcance(), v.fechaSolicitud(), quien.id());
            visitas.reemplazarComponentes(nuevo, v.componentes());
            visitas.reemplazarEquipo(nuevo, v.lider(), v.otros());
            visitas.reemplazarReparto(nuevo, v.reparto(), v.asignadoPor());
            visitas.historial(nuevo, quien.id(), quien.usuario(), "Programada", resumen(v));
            if (datos.solicitudId() != null) {
                // Si otro coordinador la agendó o la anuló entre tanto, se deshace toda la programación
                solicitudes.marcarProgramada(datos.solicitudId(), nuevo, quien.id());
                visitas.historial(nuevo, quien.id(), quien.usuario(), "Solicitud agendada", "Solicitud " + datos.solicitudId());
            }
            return nuevo;
        });
        RepositorioVisitas.Visita creada = visitas.porId(id).orElseThrow();
        bitacora.registrar("visita_programada", "Visita " + creada.numero() + " programada: " + v.tipo().codigo() + " · punto " + v.punto().id()
                + " · " + v.fecha() + " · componentes " + String.join(",", v.componentes()), quien.id(), quien.usuario());
        List<String> advertencias = advertencias(v.punto(), v.equipo());
        Boolean ok = null;
        String mensaje = "Visita " + creada.numero() + " programada.";
        if (datos.solicitudId() != null) {
            mensaje += " " + solicitudes.avisarSolicitanteSeguro(datos.solicitudId(), creada, false, null, quien);
        }
        if (Boolean.TRUE.equals(datos.notificar()) && !"no-opl".equals(v.tipo().aviso())) {
            ServicioNotificacion.Resultado r = notificacion.notificarAutomatico(id, ServicioNotificacion.AL_PROGRAMAR, null, quien);
            ok = r.ok();
            mensaje += " " + r.mensaje();
        }
        return new Resultado(id, creada.numero(), mensaje, ok, advertencias);
    }

    public Resultado editar(long id, Datos entrada, UsuarioSesion quien) {
        // Si la visita atiende una solicitud, la solicitud manda también al editar: visita a necesidad, a su punto y con su fecha
        Datos d = solicitudes.deVisita(id).map(s -> entrada.desdeSolicitud(((Number) s.get("punto_id")).longValue(), s.get("fecha_solicitud").toString()))
                .orElse(entrada);
        Valido v = validar(d, true, quien);
        String cambios = tx.execute(t -> {
            RepositorioVisitas.Visita antes = visitas.bloquear(id).orElseThrow(() -> ErrorNegocio.noEncontrado("La visita no existe."));
            if (!EstadosVisita.permite("editar", antes.estado())) {
                throw ErrorNegocio.validacion("Solo se edita una visita programada sin notificar. Use Reprogramar o Cancelar.");
            }
            if (antes.esSeguimiento()) {
                // El seguimiento mantiene el punto, el tipo y los componentes de su visita de origen (invariante de la spec)
                throw ErrorNegocio.validacion("Un seguimiento al plan no se edita: use Reprogramar, Reasignar o Cancelar.");
            }
            List<RepositorioVisitas.Miembro> equipoAntes = visitas.equipo(id);
            List<String> componentesAntes = visitas.componentesDe(id);
            visitas.actualizar(id, v.tipo().codigo(), v.punto().id(), v.fecha(), v.hora(), v.lider(), v.alcance(), v.fechaSolicitud());
            visitas.reemplazarComponentes(id, v.componentes());
            visitas.reemplazarEquipo(id, v.lider(), v.otros());
            visitas.reemplazarReparto(id, v.reparto(), v.asignadoPor());
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("tipo", antes.tipo());
            a.put("punto", antes.puntoId());
            a.put("fecha", antes.fecha());
            a.put("hora", antes.hora());
            a.put("equipo", equipoAntes.stream().map(m -> String.valueOf(m.id())).toList());
            a.put("componentes", componentesAntes);
            a.put("alcance", antes.alcance());
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("tipo", v.tipo().codigo());
            b.put("punto", v.punto().id());
            b.put("fecha", v.fecha());
            b.put("hora", v.hora());
            b.put("equipo", v.equipo().stream().map(m -> String.valueOf(m.id())).toList());
            b.put("componentes", v.componentes());
            b.put("alcance", v.alcance());
            String c = Bitacora.cambios(a, b, Set.of("alcance"));
            visitas.historial(id, quien.id(), quien.usuario(), "Editada", (c.isEmpty() ? "Sin cambios de datos. " : c + ". ") + resumen(v));
            return c;
        });
        String numero = visitas.porId(id).map(RepositorioVisitas.Visita::numero).orElse("");
        bitacora.registrar("visita_editada", "Visita " + numero + (cambios.isEmpty() ? " sin cambios" : ": " + cambios), quien.id(), quien.usuario());
        return new Resultado(id, numero, "Visita " + numero + " actualizada.", null, advertencias(v.punto(), v.equipo()));
    }

    /** Validación común de programar y editar; arma el equipo y el reparto (automático, con lo que el coordinador asignó a mano). */
    Valido validar(Datos d, boolean exigirFuturo, UsuarioSesion quien) {
        List<String> faltan = new ArrayList<>();
        Optional<RepositorioVisitas.Tipo> tipo = Optional.ofNullable(d.tipo()).flatMap(visitas::tipo);
        if (tipo.isEmpty()) {
            faltan.add("tipo de visita");
        }
        Optional<RepositorioVisitas.Punto> punto = Optional.ofNullable(d.puntoId()).flatMap(visitas::punto).filter(RepositorioVisitas.Punto::activo);
        if (punto.isEmpty()) {
            faltan.add("punto (activo)");
        }
        Optional<LocalDate> fecha = Fechas.valida(d.fecha());
        if (fecha.isEmpty()) {
            faltan.add("fecha");
        }
        LocalTime hora = null;
        if (d.hora() != null && !d.hora().isBlank()) {
            Optional<LocalTime> h = Fechas.hora(d.hora());
            if (h.isEmpty()) {
                faltan.add("hora (HH:MM)");
            } else {
                hora = h.get();
            }
        }
        String alcance = d.alcance() == null ? "" : d.alcance().strip();
        if (alcance.isEmpty()) {
            faltan.add("alcance específico");
        }
        Long lider = d.liderId();
        Map<Long, String> roles = visitas.auditoresActivos(lider == null ? List.of() : List.of(lider));
        if (lider == null || !roles.containsKey(lider)) {
            faltan.add("auditor líder (activo, con perfil Auditor)");
        }
        if (!faltan.isEmpty()) {
            throw ErrorNegocio.validacion("Falta o no es válido: " + String.join(", ", faltan) + ".");
        }
        if (alcance.length() > MAX_ALCANCE) {
            throw ErrorNegocio.validacion("El alcance tiene máximo " + MAX_ALCANCE + " caracteres.");
        }
        RepositorioVisitas.Tipo t = tipo.get();
        RepositorioVisitas.Punto p = punto.get();
        if (t.unidad() != null && !t.unidad().equals(p.unidad())) {
            throw ErrorNegocio.validacion("El tipo de visita «" + t.nombre() + "» corresponde a otra unidad de intervención.");
        }
        if (exigirFuturo && fecha.get().isBefore(hoy())) {
            throw ErrorNegocio.validacion("La fecha de la visita no puede ser anterior a hoy.");
        }
        LocalDate fechaSolicitud = null;
        if ("necesidad".equals(t.codigo())) {
            fechaSolicitud = Fechas.valida(d.fechaSolicitud()).orElseThrow(() -> ErrorNegocio.validacion(
                    "La visita a necesidad necesita la fecha de la solicitud (Anexo 3 p.14: 5 días hábiles desde la solicitud)."));
            if (fechaSolicitud.isAfter(hoy()) || fechaSolicitud.isAfter(fecha.get())) {
                throw ErrorNegocio.validacion("La fecha de la solicitud no puede ser futura ni posterior a la fecha de la visita.");
            }
        }
        // Otros auditores: sin repetir y sin el líder (se descartan sin error), máximo 5, todos activos con perfil Auditor
        List<Long> otros = new ArrayList<>(new LinkedHashSet<>(d.otros() == null ? List.<Long>of() : d.otros().stream()
                .filter(x -> x != null && !x.equals(lider)).toList()));
        if (otros.size() > Reparto.MAX_OTROS) {
            throw ErrorNegocio.validacion("Máximo " + Reparto.MAX_OTROS + " auditores además del líder.");
        }
        Map<Long, String> rolesOtros = visitas.auditoresActivos(otros);
        if (rolesOtros.size() != otros.size()) {
            throw ErrorNegocio.validacion("Todos los auditores del equipo deben estar activos y tener el perfil Auditor.");
        }
        List<Reparto.Integrante> equipo = new ArrayList<>();
        equipo.add(new Reparto.Integrante(lider, roles.get(lider), true));
        otros.forEach(o -> equipo.add(new Reparto.Integrante(o, rolesOtros.get(o), false)));

        // Componentes: los elige el coordinador entre los de la versión vigente que aplican a la unidad del punto
        String version = visitas.versionVigente().orElseThrow(() -> ErrorNegocio.validacion(
                "No hay una versión vigente del checklist: publíquela antes de programar."));
        List<RepositorioVisitas.ComponenteChecklist> catalogo = visitas.componentes(version).stream()
                .filter(c -> c.unidades().contains(p.unidad())).toList();
        Set<String> disponibles = catalogo.stream().map(RepositorioVisitas.ComponenteChecklist::codigo).collect(Collectors.toSet());
        List<String> elegidos = d.componentes() == null ? List.of() : d.componentes().stream().filter(c -> c != null && !c.isBlank())
                .map(String::strip).distinct().sorted().toList();
        if (elegidos.isEmpty()) {
            throw ErrorNegocio.validacion("Elija al menos un componente del checklist para la visita.");
        }
        List<String> noAplican = elegidos.stream().filter(c -> !disponibles.contains(c)).toList();
        if (!noAplican.isEmpty()) {
            throw ErrorNegocio.validacion("Estos componentes no aplican a la unidad " + p.unidad() + ": " + String.join(", ", noAplican) + ".");
        }
        Map<String, Long> reparto = repartir(catalogo, elegidos, p.unidad(), equipo, d.reparto());
        Map<String, String> asignadoPor = new LinkedHashMap<>();
        if (d.reparto() != null) {
            d.reparto().forEach((parte, u) -> {
                if (u != null && reparto.containsKey(parte)) {
                    asignadoPor.put(parte, quien == null ? "coordinador" : quien.usuario());
                }
            });
        }
        return new Valido(t, p, fecha.get(), hora, lider, otros, equipo, alcance, elegidos, fechaSolicitud, reparto, asignadoPor);
    }

    /** Reparto automático de las partes; lo asignado a mano lo reemplaza si respeta el rol (decisión 2c). */
    static Map<String, Long> repartir(List<RepositorioVisitas.ComponenteChecklist> catalogo, List<String> elegidos, String unidad,
                                      List<Reparto.Integrante> equipo, Map<String, Long> manual) {
        boolean hayIngeniero = equipo.stream().anyMatch(m -> !Reparto.esAsistencial(m.rol()));
        List<Reparto.Componente> comps = catalogo.stream().filter(c -> elegidos.contains(c.codigo()))
                .map(RepositorioVisitas.ComponenteChecklist::paraReparto).toList();
        Map<String, Integer> partes = Reparto.partes(comps, unidad, hayIngeniero);
        Map<String, Long> r = Reparto.repartir(partes, equipo);
        if (manual != null) {
            for (var e : manual.entrySet()) {
                if (e.getValue() == null) {
                    continue;
                }
                if (!partes.containsKey(e.getKey())) {
                    throw ErrorNegocio.validacion("La parte " + e.getKey() + " no es de esta visita.");
                }
                Reparto.Integrante m = equipo.stream().filter(x -> x.id() == e.getValue()).findFirst().orElseThrow(
                        () -> ErrorNegocio.validacion("La parte " + e.getKey() + " se asignó a alguien que no es del equipo."));
                if (!Reparto.puedeRecibir(e.getKey(), m.rol(), equipo)) {
                    throw ErrorNegocio.validacion(Reparto.PARTE_SISTEMAS.equals(e.getKey())
                            ? "Los ítems de sistemas solo se asignan a un ingeniero de sistemas."
                            : "La parte " + e.getKey() + " va a un integrante del talento asistencial (no al ingeniero de sistemas).");
                }
                r.put(e.getKey(), e.getValue());
            }
        }
        return r;
    }

    private String resumen(Valido v) {
        Map<Long, String> nombres = nombres(v.equipo().stream().map(Reparto.Integrante::id).toList());
        String otros = v.otros().stream().map(nombres::get).collect(Collectors.joining(", "));
        String reparto = v.reparto().entrySet().stream().map(e -> e.getKey() + "→" + nombres.get(e.getValue())).collect(Collectors.joining(", "));
        return "Líder: " + nombres.get(v.lider()) + (otros.isEmpty() ? "" : "; otros: " + otros) + "; componentes: "
                + String.join(", ", v.componentes()) + "; reparto: " + reparto;
    }

    Map<Long, String> nombres(List<Long> ids) {
        Map<Long, String> r = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return r;
        }
        jdbc.sql("SELECT id, nombre FROM usuarios WHERE id = ANY(:ids)").param("ids", ids.toArray(Long[]::new))
                .query((rs, n) -> r.put(rs.getLong(1), rs.getString(2))).list();
        return r;
    }

    /** Advertencia (no impide programar): el punto exige químico farmacéutico y nadie del equipo lo es (Anexo 3 p.9). */
    static List<String> advertencias(RepositorioVisitas.Punto p, List<Reparto.Integrante> equipo) {
        List<String> a = new ArrayList<>();
        if (p.exigeQf() && equipo.stream().noneMatch(m -> "quimico_farmaceutico".equals(m.rol()))) {
            a.add("En " + p.nombre() + " el equipo debe incluir un químico farmacéutico (Anexo 3 p.9).");
        }
        return a;
    }

    // ---------- Seguimiento al plan de mejoramiento ----------

    /**
     * Visita de seguimiento creada al cerrar la de origen (decisión 30), dentro de la transacción del cierre: tipo «plan», mismo punto,
     * hora, líder y otros auditores activos, los mismos componentes y la misma versión del checklist; el reparto se hace de nuevo.
     * Valida todo antes de escribir: si algo no sirve (p. ej. el líder ya no está activo) lanza el error sin haber escrito nada.
     * @return el id de la visita creada
     */
    public long programarSeguimiento(RepositorioVisitas.Visita origen, LocalDate fecha, int n, String alcance, UsuarioSesion quien) {
        Map<Long, String> rolLider = visitas.auditoresActivos(List.of(origen.auditorId()));
        if (!rolLider.containsKey(origen.auditorId())) {
            throw ErrorNegocio.validacion("El auditor líder de la visita ya no está activo con el perfil Auditor: el coordinador debe programar el seguimiento.");
        }
        List<Long> otros = visitas.equipo(origen.id()).stream().map(RepositorioVisitas.Miembro::id).filter(id -> id != origen.auditorId()).toList();
        Map<Long, String> rolesOtros = visitas.auditoresActivos(otros);
        List<Long> otrosActivos = otros.stream().filter(rolesOtros::containsKey).toList();
        List<Reparto.Integrante> equipo = new ArrayList<>();
        equipo.add(new Reparto.Integrante(origen.auditorId(), rolLider.get(origen.auditorId()), true));
        otrosActivos.forEach(o -> equipo.add(new Reparto.Integrante(o, rolesOtros.get(o), false)));
        String version = origen.checklistVersion() != null ? origen.checklistVersion()
                : visitas.versionVigente().orElseThrow(() -> ErrorNegocio.validacion("No hay una versión vigente del checklist."));
        List<String> elegidos = visitas.componentesDe(origen.id());
        List<RepositorioVisitas.ComponenteChecklist> catalogo = visitas.componentes(version).stream()
                .filter(c -> c.unidades().contains(origen.unidad())).toList();
        Map<String, Long> reparto = repartir(catalogo, elegidos, origen.unidad(), equipo, null);
        // Decisión D6: cada parte vuelve al auditor que la calificó en la visita de origen, si sigue en el equipo y puede recibirla
        visitas.reparto(origen.id()).forEach((parte, usuario) -> equipo.stream().filter(m -> m.id() == usuario).findFirst()
                .filter(m -> reparto.containsKey(parte) && Reparto.puedeRecibir(parte, m.rol(), equipo)).ifPresent(m -> reparto.put(parte, usuario)));
        String textoAlcance = alcance.length() > MAX_ALCANCE ? alcance.substring(0, MAX_ALCANCE) : alcance;

        long nuevo = visitas.crear("plan", origen.puntoId(), fecha, origen.hora(), origen.auditorId(), textoAlcance, null, quien.id());
        visitas.marcarSeguimiento(nuevo, origen.id(), n, version);
        visitas.reemplazarComponentes(nuevo, elegidos);
        visitas.reemplazarEquipo(nuevo, origen.auditorId(), otrosActivos);
        visitas.reemplazarReparto(nuevo, reparto, Map.of());
        visitas.historial(nuevo, null, "sistema", "Programada", "Seguimiento N° " + n + " de la visita " + origen.numero() + " al " + fecha);
        visitas.historial(origen.id(), null, "sistema", "Seguimiento programado", "Visita " + visitas.porId(nuevo).map(RepositorioVisitas.Visita::numero)
                .orElse("") + " para el " + fecha);
        return nuevo;
    }

    /** Notificación inmediata del seguimiento recién creado (camino A, como la notificación previa); fuera de la transacción del cierre. */
    public ServicioNotificacion.Resultado notificarSeguimiento(long id, UsuarioSesion quien) {
        return notificacion.notificarAutomatico(id, ServicioNotificacion.AL_PROGRAMAR, null, quien);
    }

    /** Al levantar el último hallazgo, el seguimiento pendiente de la visita ya no tiene qué verificar (decisión 11.7). @return si se canceló */
    public boolean cancelarSeguimientoPendiente(long origenId, String motivo) {
        return visitas.seguimientoDe(origenId).filter(s -> EstadosVisita.POR_EJECUTAR.contains(s.estado())).map(s -> {
            boolean ok = visitas.cambiarEstado(s.id(), EstadosVisita.POR_EJECUTAR, "cancelada");
            if (ok) {
                visitas.historial(s.id(), null, "sistema", "Cancelada", motivo);
                visitas.historial(origenId, null, "sistema", "Seguimiento cancelado", motivo);
            }
            return ok;
        }).orElse(false);
    }

    // ---------- Reprogramar, cancelar ----------

    public Resultado reprogramar(long id, String fechaTexto, String horaTexto, String motivo, boolean notificar, UsuarioSesion quien) {
        LocalDate fecha = Fechas.valida(fechaTexto).orElseThrow(() -> ErrorNegocio.validacion("La nueva fecha no es válida."));
        LocalTime hora = horaTexto == null || horaTexto.isBlank() ? null
                : Fechas.hora(horaTexto).orElseThrow(() -> ErrorNegocio.validacion("La hora no es válida (HH:MM)."));
        String m = motivo(motivo);
        if (fecha.isBefore(hoy())) {
            throw ErrorNegocio.validacion("La nueva fecha no puede ser anterior a hoy.");
        }
        record Cambio(String destino, String aviso, String numero) {
        }
        Cambio c = tx.execute(t -> {
            RepositorioVisitas.Visita v = visitas.bloquear(id).orElseThrow(() -> ErrorNegocio.noEncontrado("La visita no existe."));
            if (!EstadosVisita.permite("reprogramar", v.estado())) {
                throw ErrorNegocio.validacion("Solo se reprograma una visita que no ha iniciado.");
            }
            if (fecha.equals(v.fecha()) && java.util.Objects.equals(hora, v.hora())) {
                throw ErrorNegocio.validacion("La fecha y la hora son las mismas: no hay nada que reprogramar.");
            }
            String destino = "programada".equals(v.estado()) ? "programada" : "reprogramada";
            if (!visitas.reprogramar(id, Set.of(v.estado()), destino, fecha, hora)) {
                throw ErrorNegocio.conflicto("Otro usuario cambió la visita al mismo tiempo. Recargue y vuelva a intentarlo.");
            }
            visitas.historial(id, quien.id(), quien.usuario(), "Reprogramada", "De " + v.fecha() + (v.hora() == null ? "" : " " + v.hora())
                    + " a " + fecha + (hora == null ? "" : " " + hora) + ". Motivo: " + m);
            return new Cambio(destino, v.aviso(), v.numero());
        });
        bitacora.registrar("visita_reprogramada", "Visita " + c.numero() + " reprogramada al " + fecha, quien.id(), quien.usuario());
        Boolean ok = null;
        String mensaje = "Visita " + c.numero() + " reprogramada.";
        if ("reprogramada".equals(c.destino()) && notificar && !"no-opl".equals(c.aviso())) {
            ServicioNotificacion.Resultado r = notificacion.notificarAutomatico(id, ServicioNotificacion.AL_REPROGRAMAR, " · nueva fecha", quien);
            ok = r.ok();
            mensaje += " " + r.mensaje();
        }
        return new Resultado(id, c.numero(), mensaje, ok, List.of());
    }

    public Resultado cancelar(long id, String motivo, UsuarioSesion quien) {
        String m = motivo(motivo);
        record Cancelacion(RepositorioVisitas.Visita visita, Long solicitud) {
        }
        Cancelacion c = tx.execute(t -> {
            RepositorioVisitas.Visita v = visitas.bloquear(id).orElseThrow(() -> ErrorNegocio.noEncontrado("La visita no existe."));
            if (!EstadosVisita.permite("cancelar", v.estado()) || !visitas.cambiarEstado(id, EstadosVisita.ORIGEN.get("cancelar"), "cancelada")) {
                throw ErrorNegocio.validacion("La visita no existe o ya está cerrada o cancelada.");
            }
            visitas.historial(id, quien.id(), quien.usuario(), "Cancelada", "Motivo: " + m);
            // La solicitud que atendía vuelve a pendiente para reprogramarla (su plazo sigue corriendo desde la fecha del escrito)
            Long solicitud = solicitudes.liberar(id, quien.id()).orElse(null);
            if (solicitud != null) {
                visitas.historial(id, quien.id(), quien.usuario(), "Solicitud liberada", "Solicitud " + solicitud + " vuelve a pendiente");
            }
            return new Cancelacion(v, solicitud);
        });
        String numero = c.visita().numero();
        bitacora.registrar("visita_cancelada", "Visita " + numero + " cancelada", quien.id(), quien.usuario());
        String mensaje = "Visita " + numero + " cancelada.";
        if (c.solicitud() != null) {
            // Decisión 9.3: se avisa al solicitante y la solicitud queda pendiente
            mensaje += " La solicitud " + c.solicitud() + " volvió a pendiente. "
                    + solicitudes.avisarSolicitanteSeguro(c.solicitud(), c.visita(), true, m, quien);
        }
        return new Resultado(id, numero, mensaje, null, List.of());
    }

    private static String motivo(String motivo) {
        String m = motivo == null ? "" : motivo.strip();
        if (m.isEmpty() || m.length() > MAX_MOTIVO) {
            throw ErrorNegocio.validacion("Escriba el motivo (máximo " + MAX_MOTIVO + " caracteres).");
        }
        return m;
    }

    // ---------- Reasignar, mover partes ----------

    /** Cambia el equipo de una visita notificada que no ha iniciado; el reenvío de la notificación es obligatorio (decisión 9.8). */
    public Resultado reasignar(long id, Long liderId, List<Long> otrosEntrada, String motivo, UsuarioSesion quien) {
        String m = motivo(motivo);
        record Cambio(String numero, String aviso, LocalDate fecha, RepositorioVisitas.Punto punto, List<Reparto.Integrante> equipo) {
        }
        Cambio c = tx.execute(t -> {
            RepositorioVisitas.Visita v = visitas.bloquear(id).orElseThrow(() -> ErrorNegocio.noEncontrado("La visita no existe."));
            if (!EstadosVisita.permite("reasignar", v.estado())) {
                throw ErrorNegocio.validacion("Solo se reasigna el equipo de una visita notificada que no ha iniciado. Si está programada, use Editar.");
            }
            Map<Long, String> roles = visitas.auditoresActivos(liderId == null ? List.of() : List.of(liderId));
            if (liderId == null || !roles.containsKey(liderId)) {
                throw ErrorNegocio.validacion("Falta o no es válido: auditor líder (activo, con perfil Auditor).");
            }
            List<Long> otros = new ArrayList<>(new LinkedHashSet<>(otrosEntrada == null ? List.<Long>of()
                    : otrosEntrada.stream().filter(x -> x != null && !x.equals(liderId)).toList()));
            if (otros.size() > Reparto.MAX_OTROS) {
                throw ErrorNegocio.validacion("Máximo " + Reparto.MAX_OTROS + " auditores además del líder.");
            }
            Map<Long, String> rolesOtros = visitas.auditoresActivos(otros);
            if (rolesOtros.size() != otros.size()) {
                throw ErrorNegocio.validacion("Todos los auditores del equipo deben estar activos y tener el perfil Auditor.");
            }
            List<RepositorioVisitas.Miembro> antes = visitas.equipo(id);
            List<Long> idsAntes = antes.stream().map(RepositorioVisitas.Miembro::id).toList();
            List<Long> idsDespues = new ArrayList<>();
            idsDespues.add(liderId);
            idsDespues.addAll(otros);
            if (idsAntes.equals(idsDespues)) {
                throw ErrorNegocio.validacion("El equipo no cambió.");
            }
            List<Reparto.Integrante> equipo = new ArrayList<>();
            equipo.add(new Reparto.Integrante(liderId, roles.get(liderId), true));
            otros.forEach(o -> equipo.add(new Reparto.Integrante(o, rolesOtros.get(o), false)));
            String version = visitas.versionVigente().orElseThrow(() -> ErrorNegocio.validacion("No hay una versión vigente del checklist."));
            Map<String, Long> reparto = repartir(visitas.componentes(version), visitas.componentesDe(id), v.unidad(), equipo, null);
            visitas.cambiarLider(id, liderId);
            visitas.reemplazarEquipo(id, liderId, otros);
            visitas.reemplazarReparto(id, reparto, Map.of());
            visitas.marcarEquipoSinNotificar(id, true);
            Map<Long, String> nombres = nombres(new ArrayList<>(new LinkedHashSet<>(java.util.stream.Stream.concat(idsAntes.stream(), idsDespues.stream()).toList())));
            visitas.historial(id, quien.id(), quien.usuario(), "Equipo reasignado",
                    idsAntes.stream().map(nombres::get).collect(Collectors.joining(", ")) + " → "
                            + idsDespues.stream().map(nombres::get).collect(Collectors.joining(", ")) + ". Motivo: " + m);
            RepositorioVisitas.Punto p = visitas.punto(v.puntoId()).orElseThrow();
            return new Cambio(v.numero(), v.aviso(), v.fecha(), p, equipo);
        });
        bitacora.registrar("visita_reasignada", "Visita " + c.numero() + ": equipo reasignado", quien.id(), quien.usuario());
        List<String> advertencias = new ArrayList<>(advertencias(c.punto(), c.equipo()));
        if (ChronoUnit.DAYS.between(hoy(), c.fecha()) < DIAS_AVISO_OPL) {
            advertencias.add("Faltan menos de " + DIAS_AVISO_OPL + " días para la visita: el cambio de equipo llega con menos antelación de la que pide el Anexo 3 p.9.");
        }
        Boolean ok = null;
        String mensaje = "Equipo de la visita " + c.numero() + " reasignado.";
        if (!"no-opl".equals(c.aviso())) {
            ServicioNotificacion.Resultado r = notificacion.notificarAutomatico(id, ServicioNotificacion.AL_REASIGNAR, " · cambio de equipo", quien);
            ok = r.ok();
            mensaje += " " + r.mensaje();
        }
        return new Resultado(id, c.numero(), mensaje, ok, advertencias);
    }

    /** Pasa una parte a otro integrante; solo antes de iniciar la visita (decisión 8.3). */
    public String moverParte(long id, String parte, long usuarioId, UsuarioSesion quien) {
        return tx.execute(t -> {
            RepositorioVisitas.Visita v = visitas.bloquear(id).orElseThrow(() -> ErrorNegocio.noEncontrado("La visita no existe."));
            if (!EstadosVisita.POR_EJECUTAR.contains(v.estado())) {
                throw ErrorNegocio.validacion("Las partes del checklist solo se mueven antes de iniciar la visita (decisión 8.3).");
            }
            Map<String, Long> reparto = visitas.reparto(id);
            Long actual = reparto.get(parte);
            if (actual == null) {
                throw ErrorNegocio.validacion("La parte " + parte + " no es de esta visita.");
            }
            List<RepositorioVisitas.Miembro> equipo = visitas.equipo(id);
            RepositorioVisitas.Miembro destino = equipo.stream().filter(m -> m.id() == usuarioId && m.activo()).findFirst()
                    .orElseThrow(() -> ErrorNegocio.validacion("El auditor no es un integrante activo del equipo."));
            List<Reparto.Integrante> integrantes = equipo.stream().map(m -> new Reparto.Integrante(m.id(), m.rol(), "lider".equals(m.funcion()))).toList();
            if (!Reparto.puedeRecibir(parte, destino.rol(), integrantes)) {
                throw ErrorNegocio.validacion(Reparto.PARTE_SISTEMAS.equals(parte) ? "Los ítems de sistemas solo se asignan a un ingeniero de sistemas."
                        : "La parte " + parte + " va a un integrante del talento asistencial (no al ingeniero de sistemas).");
            }
            if (actual == usuarioId) {
                return "Sin cambios.";
            }
            visitas.moverParte(id, parte, usuarioId, quien.usuario());
            Map<Long, String> nombres = nombres(List.of(actual, usuarioId));
            visitas.historial(id, quien.id(), quien.usuario(), "Reparto cambiado", parte + ": " + nombres.get(actual) + " → " + nombres.get(usuarioId));
            bitacora.registrar("reparto_movido", "Visita " + v.numero() + ": parte " + parte + " movida", quien.id(), quien.usuario());
            return "Parte " + parte + " asignada a " + nombres.get(usuarioId) + ".";
        });
    }

    // ---------- Iniciar ----------

    /** Cualquier integrante del equipo la inicia; se fija la versión vigente del checklist y, si hace falta, se rehace el reparto. */
    public Resultado iniciar(long id, UsuarioSesion quien) {
        record Inicio(String numero, boolean yaEstaba, String version) {
        }
        Inicio r = tx.execute(t -> {
            RepositorioVisitas.Visita v = visitas.bloquear(id).orElseThrow(() -> ErrorNegocio.noEncontrado("La visita no existe."));
            if (!visitas.esDelEquipo(id, quien.id())) {
                throw ErrorNegocio.noEncontrado("La visita no existe o no está asignada a usted.");
            }
            if ("ejecucion".equals(v.estado())) {
                return new Inicio(v.numero(), true, v.checklistVersion());
            }
            if (!EstadosVisita.permite("iniciar", v.estado())) {
                throw ErrorNegocio.validacion("La visita no está por ejecutar.");
            }
            // El seguimiento conserva la versión del checklist de su visita de origen (los componentes no cambian)
            String version = v.checklistVersion() != null && v.esSeguimiento() ? v.checklistVersion()
                    : visitas.versionVigente().orElseThrow(() -> ErrorNegocio.validacion("No hay una versión vigente del checklist: avise al coordinador."));
            if (!visitas.iniciar(id, EstadosVisita.POR_EJECUTAR, version)) {
                throw ErrorNegocio.conflicto("Otro usuario cambió la visita al mismo tiempo. Recargue y vuelva a intentarlo.");
            }
            List<Reparto.Integrante> equipo = visitas.equipo(id).stream().filter(RepositorioVisitas.Miembro::activo) // un inactivo no recibe partes
                    .map(m -> new Reparto.Integrante(m.id(), m.rol(), "lider".equals(m.funcion()))).toList();
            List<RepositorioVisitas.ComponenteChecklist> catalogo = visitas.componentes(version);
            List<String> elegidos = visitas.componentesDe(id);
            boolean hayIngeniero = equipo.stream().anyMatch(m -> !Reparto.esAsistencial(m.rol()));
            Set<String> partes = Reparto.partes(catalogo.stream().filter(c -> elegidos.contains(c.codigo()))
                    .map(RepositorioVisitas.ComponenteChecklist::paraReparto).toList(), v.unidad(), hayIngeniero).keySet();
            Map<String, Long> actual = visitas.reparto(id);
            if (!actual.keySet().equals(partes)) {
                visitas.reemplazarReparto(id, repartir(catalogo, elegidos, v.unidad(), equipo, null), Map.of());
                if (!actual.isEmpty()) {
                    visitas.historial(id, quien.id(), quien.usuario(), "Reparto rehecho", "Las partes no coincidían con la versión " + version);
                }
            }
            visitas.historial(id, quien.id(), quien.usuario(), "En ejecución", "Iniciada por " + quien.nombre() + " con el checklist " + version);
            return new Inicio(v.numero(), false, version);
        });
        if (!r.yaEstaba()) {
            bitacora.registrar("visita_iniciada", "Visita " + r.numero() + " iniciada con el checklist " + r.version(), quien.id(), quien.usuario());
        }
        return new Resultado(id, r.numero(), r.yaEstaba() ? "La visita ya estaba en ejecución." : "Visita " + r.numero() + " en ejecución.", null,
                List.of());
    }
}
