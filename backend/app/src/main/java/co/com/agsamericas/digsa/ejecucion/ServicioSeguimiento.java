package co.com.agsamericas.digsa.ejecucion;

import co.com.agsamericas.digsa.agenda.RepositorioVisitas;
import co.com.agsamericas.digsa.agenda.ServicioAgenda;
import co.com.agsamericas.digsa.agenda.ServicioConsultaAgenda;
import co.com.agsamericas.digsa.catalogos.ServicioCatalogos;
import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.CatalogoPlantillas;
import co.com.agsamericas.digsa.plataforma.Correo;
import co.com.agsamericas.digsa.plataforma.EnvioRegistrado;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.plataforma.Plantilla;
import co.com.agsamericas.digsa.plataforma.PlantillaCorreo;
import co.com.agsamericas.digsa.plataforma.RegistroBitacora;
import co.com.agsamericas.digsa.reglas.Bitacora;
import co.com.agsamericas.digsa.reglas.Correos;
import co.com.agsamericas.digsa.reglas.Fechas;
import co.com.agsamericas.digsa.reglas.Perfil;
import co.com.agsamericas.digsa.reglas.Plazos;
import co.com.agsamericas.digsa.reglas.Seguimiento;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Seguimiento al plan de mejoramiento (docs/especificaciones/seguimiento): qué se verifica, la programación automática al cerrar
 * (decisión 30), el máximo de seguimientos con escalamiento y reporte de plan incumplido (decisión 24 y Reunión 5), el estado inicial
 * (plan anterior con quién y cómo del OPL) y las prórrogas (decisión 12.1). El reporte formal y el cierre del plan del OPL los atiende
 * el módulo opl con los eventos {@link PlanIncumplido} y {@link PlanCerradoSinAbiertas}.
 */
@Service
public class ServicioSeguimiento {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioSeguimiento.class);
    static final int MAX_SEGUIMIENTOS_DEFECTO = 3;

    /** Qué pasa con el seguimiento al cerrar esta visita (va en el acta y decide qué se crea). */
    /** @param vencidas D9: acciones del plan anterior vencidas que siguen en «No cumple» (sin prórroga): plan incumplido */
    public record Programacion(String situacion, LocalDate fecha, int n, List<Map<String, Object>> abiertas, List<String> vencidas) {
        Programacion(String situacion, LocalDate fecha, int n, List<Map<String, Object>> abiertas) {
            this(situacion, fecha, n, abiertas, List.of());
        }

        Programacion conVencidas(List<String> v) {
            return new Programacion(situacion, fecha, n, abiertas, v);
        }

        boolean incumplido() {
            return MAXIMO.equals(situacion) || !vencidas.isEmpty();
        }

        static final String PROGRAMADO = "programado";
        static final String SIN_ABIERTAS = "sin_abiertas";
        static final String MAXIMO = "maximo";
        static final String SIN_HALLAZGOS = "sin_hallazgos";

        boolean crea() {
            return PROGRAMADO.equals(situacion);
        }
    }

    /** Se llegó al máximo de seguimientos con acciones abiertas: reporte formal «plan incumplido» a la DIGSA (lo atiende el módulo opl). */
    public record PlanIncumplido(long visitaId, long actaId, String numero) {
    }

    /** El seguimiento terminó sin acciones abiertas: el plan queda cerrado y se avisa solo al OPL (recomendación de la spec, duda 22.4). */
    public record PlanCerradoSinAbiertas(long visitaId, String numero) {
    }

    private final RepositorioVisitas visitas;
    private final CatalogoPlantillas plantillas;
    private final ServicioAgenda agenda;
    private final ServicioConsultaAgenda consulta;
    private final ServicioCatalogos catalogos;
    private final EnvioRegistrado correo;
    private final RegistroBitacora bitacora;
    private final ApplicationEventPublisher eventos;
    private final JdbcClient jdbc;
    private final Clock reloj;
    /** Punto de guardado dentro del cierre: si la programación falla, el cierre (cuyo correo ya salió) sigue en pie. */
    private final org.springframework.transaction.support.TransactionTemplate anidada;

    public ServicioSeguimiento(RepositorioVisitas visitas, ServicioAgenda agenda, ServicioConsultaAgenda consulta, ServicioCatalogos catalogos, EnvioRegistrado correo,
                               RegistroBitacora bitacora, ApplicationEventPublisher eventos, JdbcClient jdbc, Clock reloj,
                               org.springframework.transaction.PlatformTransactionManager transacciones, CatalogoPlantillas plantillas) {
        this.plantillas = plantillas;
        this.anidada = new org.springframework.transaction.support.TransactionTemplate(transacciones);
        this.anidada.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        this.visitas = visitas;
        this.agenda = agenda;
        this.consulta = consulta;
        this.catalogos = catalogos;
        this.correo = correo;
        this.bitacora = bitacora;
        this.eventos = eventos;
        this.jdbc = jdbc;
        this.reloj = reloj;
    }

    LocalDate hoy() {
        return LocalDate.now(reloj.withZone(Fechas.BOGOTA));
    }

    int maximo() {
        try {
            int n = Integer.parseInt(catalogos.textoParametro("max_seguimientos", String.valueOf(MAX_SEGUIMIENTOS_DEFECTO)).strip());
            return n >= 1 ? n : MAX_SEGUIMIENTOS_DEFECTO;
        } catch (NumberFormatException e) {
            return MAX_SEGUIMIENTOS_DEFECTO;
        }
    }

    // ---------- Qué se verifica ----------

    /** Acta vigente (la última enviada) de una visita. */
    Optional<Long> actaVigente(long visitaId) {
        return jdbc.sql("SELECT id FROM visitas_actas WHERE visita_id = :v AND estado IN ('enviada', 'parcial') ORDER BY ronda DESC LIMIT 1")
                .param("v", visitaId).query(Long.class).optional();
    }

    /**
     * Claves que se recalifican en el seguimiento: los hallazgos del acta vigente de la visita de origen menos los que el auditor líder
     * levantó (decisión 17). Vacío si la visita no es un seguimiento.
     */
    public Set<String> porVerificar(RepositorioVisitas.Visita v) {
        if (!v.esSeguimiento()) {
            return Set.of();
        }
        return actaVigente(v.origenId()).map(acta -> new LinkedHashSet<>(jdbc.sql("""
                SELECT h.clave FROM visitas_hallazgos h
                WHERE h.acta_id = :a AND NOT EXISTS (SELECT 1 FROM opl_respuestas r WHERE r.visita_id = h.visita_id AND r.clave = h.clave AND r.resolucion = 'levanta')
                ORDER BY h.id""").param("a", acta).query(String.class).list())).map(s -> (Set<String>) s).orElse(Set.of());
    }

    /** Hallazgo y fila del plan de la visita de origen por clave: lo que el auditor ve al recalificar y la fecha fin anterior (prórroga). */
    Map<String, Map<String, Object>> anteriores(RepositorioVisitas.Visita v) {
        Map<String, Map<String, Object>> r = new LinkedHashMap<>();
        if (!v.esSeguimiento()) {
            return r;
        }
        actaVigente(v.origenId()).ifPresent(acta -> jdbc.sql("""
                SELECT h.clave, h.codigo, h.texto, h.hallazgo, p.fin, p.estado, p.que FROM visitas_hallazgos h
                LEFT JOIN visitas_plan p ON p.visita_id = h.visita_id AND p.clave = h.clave WHERE h.acta_id = :a ORDER BY h.id""")
                .param("a", acta).query().listOfRows().forEach(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("codigo", f.get("codigo"));
                    m.put("texto", f.get("texto"));
                    m.put("hallazgo", f.get("hallazgo"));
                    m.put("fin", f.get("fin") == null ? null : f.get("fin").toString());
                    m.put("estado", f.get("estado") == null ? "Pendiente" : f.get("estado"));
                    m.put("que", f.get("que"));
                    r.put((String) f.get("clave"), m);
                }));
        return r;
    }

    /** Soportes vigentes de la visita de origen, solo de lo que se verifica (decisión 6.6), por clave. */
    Map<String, List<Map<String, Object>>> archivosAnteriores(RepositorioVisitas.Visita v, Set<String> claves) {
        Map<String, List<Map<String, Object>>> r = new LinkedHashMap<>();
        if (!v.esSeguimiento() || claves.isEmpty()) {
            return r;
        }
        jdbc.sql("""
                SELECT id, clave, nombre_original, tipo, tamano FROM archivos WHERE visita_id = :o AND eliminado IS NULL AND clave = ANY(:c) ORDER BY id""")
                .param("o", v.origenId()).param("c", claves.toArray(String[]::new)).query().listOfRows().forEach(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", f.get("id"));
                    m.put("nombre", f.get("nombre_original"));
                    m.put("tipo", f.get("tipo"));
                    m.put("tamano", f.get("tamano"));
                    r.computeIfAbsent((String) f.get("clave"), k -> new ArrayList<>()).add(m);
                });
        return r;
    }

    /** Datos del seguimiento para el formulario: N°, ordinal, visita de origen y el aviso de la pantalla. */
    Map<String, Object> datos(RepositorioVisitas.Visita v) {
        if (!v.esSeguimiento()) {
            return null;
        }
        RepositorioVisitas.Visita o = visitas.porId(v.origenId()).orElseThrow();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", v.seguimientoN());
        m.put("ordinal", Seguimiento.ordinal(v.seguimientoN()));
        m.put("origenId", o.id());
        m.put("origenNumero", o.numero());
        m.put("origenFecha", o.fecha().toString());
        m.put("aviso", Seguimiento.ordinal(v.seguimientoN()) + " visita de seguimiento al plan de mejoramiento de la visita " + o.numero()
                + ": solo se califican de nuevo los hallazgos por verificar; lo demás se ve como quedó en la visita anterior.");
        return m;
    }

    // ---------- Programación al cerrar ----------

    /** Acciones del plan de esta visita (una por hallazgo) para decidir el seguimiento. */
    List<Seguimiento.Accion> acciones(Map<String, Map<String, Object>> filas, List<Map<String, Object>> hallazgos) {
        List<Seguimiento.Accion> r = new ArrayList<>();
        for (Map<String, Object> h : hallazgos) {
            Map<String, Object> f = filas.get((String) h.get("clave"));
            String estado = f == null ? "Pendiente" : (String) f.get("estado");
            LocalDate fin = f == null || f.get("fin") == null ? null : LocalDate.parse(String.valueOf(f.get("fin")));
            r.add(new Seguimiento.Accion((String) h.get("clave"), estado, fin));
        }
        return r;
    }

    /** Qué pasará con el seguimiento al cerrar la visita (se calcula antes del acta, que lo dice), con las acciones vencidas (D9). */
    Programacion programacion(RepositorioVisitas.Visita v, Map<String, Map<String, Object>> filas, List<Map<String, Object>> hallazgos) {
        Programacion p = programacionSinVencidas(v, filas, hallazgos);
        return v.esSeguimiento() ? p.conVencidas(vencidas(v, filas, hallazgos)) : p;
    }

    /**
     * Decisión D9 del 2026-10-21: en cada seguimiento, la acción cuya fecha fin del plan anterior ya pasó y sigue en «No cumple» es un
     * plan incumplido (se reporta a la DIGSA). La que pidió prórroga no cuenta: la decide la coordinación con la DIGSA.
     */
    List<String> vencidas(RepositorioVisitas.Visita v, Map<String, Map<String, Object>> filas, List<Map<String, Object>> hallazgos) {
        Set<String> claves = new LinkedHashSet<>();
        hallazgos.forEach(h -> claves.add((String) h.get("clave")));
        Set<String> prorrogadas = new LinkedHashSet<>();
        prorrogas(v, filas, claves).forEach(x -> prorrogadas.add(x.clave()));
        Map<String, Map<String, Object>> antes = anteriores(v);
        LocalDate diaVisita = diaDeEjecucion(v);
        List<String> r = new ArrayList<>();
        for (Map<String, Object> h : hallazgos) {
            Map<String, Object> a = antes.get((String) h.get("clave"));
            if (a == null || a.get("fin") == null || prorrogadas.contains((String) h.get("clave"))) {
                continue;
            }
            if (LocalDate.parse(String.valueOf(a.get("fin"))).isBefore(diaVisita)) {
                r.add((String) h.get("codigo"));
            }
        }
        return r;
    }

    /** Día en que se ejecutó la visita (inicio real) o, si falta, la fecha programada: un reenvío del acta no vuelve vencida una acción. */
    LocalDate diaDeEjecucion(RepositorioVisitas.Visita v) {
        return jdbc.sql("SELECT inicio_real FROM visitas_ejecucion WHERE visita_id = :v").param("v", v.id()).query(OffsetDateTime.class).optional()
                .filter(java.util.Objects::nonNull).map(t -> t.atZoneSameInstant(Fechas.BOGOTA).toLocalDate()).orElse(v.fecha());
    }

    private Programacion programacionSinVencidas(RepositorioVisitas.Visita v, Map<String, Map<String, Object>> filas, List<Map<String, Object>> hallazgos) {
        int n = (v.seguimientoN() == null ? 0 : v.seguimientoN()) + 1;
        if (hallazgos.isEmpty()) {
            return new Programacion(v.esSeguimiento() ? Programacion.SIN_ABIERTAS : Programacion.SIN_HALLAZGOS, null, n, List.of());
        }
        List<Seguimiento.Accion> acciones = acciones(filas, hallazgos);
        List<Map<String, Object>> abiertas = new ArrayList<>();
        for (Seguimiento.Accion a : Seguimiento.abiertas(acciones)) {
            Map<String, Object> h = hallazgos.stream().filter(x -> a.clave().equals(x.get("clave"))).findFirst().orElse(Map.of());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("clave", a.clave());
            m.put("codigo", h.get("codigo"));
            m.put("texto", h.get("texto"));
            m.put("fin", a.fin() == null ? null : a.fin().toString());
            abiertas.add(m);
        }
        Optional<LocalDate> fecha = Seguimiento.fecha(acciones, hoy(), consulta.plazos());
        if (fecha.isEmpty()) {
            return new Programacion(Programacion.SIN_ABIERTAS, null, n, abiertas);
        }
        if (n > maximo()) {
            return new Programacion(Programacion.MAXIMO, null, n, abiertas);
        }
        return new Programacion(Programacion.PROGRAMADO, fecha.get(), n, abiertas);
    }

    /** Texto bajo el plan 5W2H del acta (decisión 6.10). */
    static String textoActa(Programacion p, int maximo) {
        String base = textoSituacion(p, maximo);
        if (p.vencidas().isEmpty()) {
            return base;
        }
        String v = "Acciones del plan vencidas en incumplimiento: " + String.join(", ", p.vencidas()) + ". Se reportan a la DIGSA como plan incumplido.";
        return base == null ? v : base + " " + v;
    }

    private static String textoSituacion(Programacion p, int maximo) {
        return switch (p.situacion()) {
            case Programacion.PROGRAMADO -> "Visita de seguimiento: " + Plazos.fechaLarga(p.fecha()) + ". Es la fecha fin más próxima de las acciones abiertas del plan; la "
                    + Seguimiento.ordinal(p.n()).toLowerCase() + " visita de seguimiento se programa sola.";
            case Programacion.MAXIMO -> "Se alcanzó el máximo de " + maximo + " visitas de seguimiento con acciones abiertas: no se programa otra; el plan se escala "
                    + "a la coordinación y se reporta a la DIGSA como plan incumplido.";
            case Programacion.SIN_ABIERTAS -> "No habrá visita de seguimiento: todas las acciones del plan quedaron completadas o canceladas.";
            default -> null;
        };
    }

    /** Alcance de la visita de seguimiento. */
    static String alcance(Programacion p, String origen) {
        return Seguimiento.ordinal(p.n()) + " visita de seguimiento al plan de mejoramiento de la visita " + origen + ": verificar " + p.abiertas().size()
                + " acción(es) del plan 5W2H.";
    }

    /** Lo que se hizo en el cierre para avisar después de la transacción. */
    public record Resultado(Programacion programacion, Long seguimientoId, String advertencia) {
    }

    /**
     * En la transacción del cierre: crea el seguimiento (si no hay uno vigente) o deja el historial del máximo. Si el seguimiento no se
     * puede crear (p. ej. el líder ya no está activo), el cierre sigue y queda la advertencia en el historial.
     */
    Resultado alCerrar(RepositorioVisitas.Visita v, Programacion p, UsuarioSesion quien) {
        Optional<RepositorioVisitas.Visita> ya = visitas.seguimientoDe(v.id());
        if (ya.isPresent()) {
            // Reabierta y cerrada otra vez: no se duplica; el seguimiento por ejecutar se ajusta al acta nueva
            marcarIncumplido(v, p);
            RepositorioVisitas.Visita s = ya.get();
            if (co.com.agsamericas.digsa.reglas.EstadosVisita.POR_EJECUTAR.contains(s.estado())) {
                if (p.crea()) {
                    if (!s.fecha().equals(p.fecha())) {
                        jdbc.sql("UPDATE visitas SET fecha = :f, actualizado = :ahora WHERE id = :id").param("f", p.fecha()).param("ahora", OffsetDateTime.now(reloj))
                                .param("id", s.id()).update();
                        visitas.historial(s.id(), null, "sistema", "Fecha ajustada", "La visita de origen se cerró de nuevo: seguimiento al " + p.fecha());
                    }
                    inicializar(s.id(), v);
                } else {
                    agenda.cancelarSeguimientoPendiente(v.id(), "La visita de origen se cerró de nuevo sin acciones abiertas por verificar");
                }
            }
            return new Resultado(p, null, null);
        }
        if (p.crea()) {
            marcarIncumplido(v, p);
            try {
                long id = anidada.execute(t -> {
                    long nuevo = agenda.programarSeguimiento(v, p.fecha(), p.n(), alcance(p, v.numero()), quien);
                    inicializar(nuevo, v);
                    return nuevo;
                });
                return new Resultado(p, id, null);
            } catch (RuntimeException e) {
                // El acta ya salió: el cierre sigue y el coordinador programa el seguimiento a mano
                String motivo = e instanceof ErrorNegocio ? e.getMessage() : "error al crearlo";
                if (!(e instanceof ErrorNegocio)) {
                    LOG.error("[seguimiento] No se programó el seguimiento de la visita {}", v.id(), e);
                }
                visitas.historial(v.id(), null, "sistema", "Seguimiento no programado", Bitacora.cortar(motivo, 300));
                return new Resultado(p, null, "El seguimiento no se programó (" + motivo + "): el coordinador lo programa desde «Prestadores con hallazgos».");
            }
        }
        if (Programacion.MAXIMO.equals(p.situacion())) {
            visitas.historial(v.id(), null, "sistema", "Máximo de seguimientos", "Se alcanzó el máximo de " + maximo() + " con " + p.abiertas().size()
                    + " acción(es) abiertas: no se programa otro seguimiento");
        }
        marcarIncumplido(v, p);
        return new Resultado(p, null, null);
    }

    /** Marca en el historial el plan incumplido: la tarea diaria lo retoma si el reporte no salió (el evento es en memoria). */
    private void marcarIncumplido(RepositorioVisitas.Visita v, Programacion p) {
        if (p.incumplido()) {
            visitas.historial(v.id(), null, "sistema", "Plan incumplido", Programacion.MAXIMO.equals(p.situacion())
                    ? "Máximo de seguimientos con acciones abiertas" : "Acciones vencidas en incumplimiento: " + String.join(", ", p.vencidas()));
        }
    }

    /**
     * Estado inicial del seguimiento (decisiones 6.5 y 6.11): las filas del plan de lo que se verifica parten del plan anterior, con el
     * quién y el cómo que remitió el OPL, sin justificación de prórroga y sin fecha del plan; los hallazgos quedan sin calificar.
     */
    void inicializar(long seguimientoId, RepositorioVisitas.Visita origen) {
        jdbc.sql("""
                INSERT INTO visitas_plan (visita_id, clave, causas, que, porque, donde, inicio, fin, indicador, estado, avance, quien, como, usuario_id, actualizado)
                SELECT :s, p.clave, p.causas, p.que, p.porque, p.donde, p.inicio, p.fin, p.indicador, p.estado, p.avance,
                       coalesce(r.quien, p.quien), coalesce(r.como, p.como), p.usuario_id, :ahora
                FROM visitas_plan p JOIN visitas_hallazgos h ON h.visita_id = p.visita_id AND h.clave = p.clave
                LEFT JOIN opl_respuestas r ON r.visita_id = p.visita_id AND r.clave = p.clave
                WHERE p.visita_id = :o AND h.acta_id = (SELECT id FROM visitas_actas WHERE visita_id = :o AND estado IN ('enviada', 'parcial') ORDER BY ronda DESC LIMIT 1)
                  AND NOT EXISTS (SELECT 1 FROM opl_respuestas x WHERE x.visita_id = p.visita_id AND x.clave = p.clave AND x.resolucion = 'levanta')
                ON CONFLICT (visita_id, clave) DO NOTHING""")
                .param("s", seguimientoId).param("o", origen.id()).param("ahora", OffsetDateTime.now(reloj)).update();
    }

    /** Después de la transacción del cierre: notificación del seguimiento, aviso al líder, escalamiento y eventos para el módulo opl. */
    List<String> despuesDelCierre(RepositorioVisitas.Visita v, long actaId, Resultado r, UsuarioSesion quien) {
        List<String> mensajes = new ArrayList<>();
        if (r.advertencia() != null) {
            mensajes.add(r.advertencia());
        }
        Programacion p = r.programacion();
        if (r.seguimientoId() != null) {
            try {
                mensajes.add(agenda.notificarSeguimiento(r.seguimientoId(), quien).mensaje());
            } catch (RuntimeException e) {
                LOG.error("[seguimiento] Notificación del seguimiento {}", r.seguimientoId(), e);
                mensajes.add("La notificación del seguimiento falló.");
            }
            mensajes.add(avisarLider(v, r.seguimientoId(), p));
            bitacora.registrar("visita_programada", "Seguimiento de la visita " + v.numero() + " programado para el " + p.fecha(), quien.id(), quien.usuario());
        } else if (Programacion.MAXIMO.equals(p.situacion())) {
            mensajes.add(escalar(v, p));
        } else if (Programacion.SIN_ABIERTAS.equals(p.situacion()) && v.esSeguimiento()) {
            eventos.publishEvent(new PlanCerradoSinAbiertas(v.origenId(), v.numero()));
        }
        // Un mismo incumplimiento no se reporta dos veces: una ronda nueva del mismo seguimiento no genera otro consecutivo
        boolean yaReportado = jdbc.sql("SELECT count(*) FROM reportes_digsa WHERE visita_id = :v AND motivo = 'plan_incumplido' AND estado = 'enviado'")
                .param("v", v.id()).query(Long.class).single() > 0;
        if (p.incumplido() && actaId > 0 && !yaReportado) {
            eventos.publishEvent(new PlanIncumplido(v.id(), actaId, v.numero()));
            if (!p.vencidas().isEmpty()) {
                mensajes.add("Acciones vencidas en incumplimiento (" + String.join(", ", p.vencidas()) + "): se reportan a la DIGSA como plan incumplido.");
            }
        }
        return mensajes.stream().filter(m -> m != null && !m.isBlank()).toList();
    }

    private String avisarLider(RepositorioVisitas.Visita origen, long seguimientoId, Programacion p) {
        RepositorioVisitas.Visita s = visitas.porId(seguimientoId).orElseThrow();
        List<String> para = jdbc.sql("SELECT correo FROM usuarios WHERE id = :id AND activo").param("id", s.auditorId()).query(String.class).list().stream()
                .map(Correos::normalizar).filter(Correos::valido).toList();
        if (para.isEmpty()) {
            visitas.historial(seguimientoId, null, "sistema", "Aviso al auditor no enviado", "El auditor líder no tiene correo");
            return "El auditor líder no tiene correo para el aviso del seguimiento.";
        }
        StringBuilder filas = new StringBuilder();
        for (Map<String, Object> a : p.abiertas()) {
            filas.append("<tr><td>").append(PlantillaCorreo.e(String.valueOf(a.get("codigo")))).append("</td><td>").append(PlantillaCorreo.e(String.valueOf(a.get("texto"))))
                    .append("</td><td>").append(a.get("fin") == null ? "" : PlantillaCorreo.e(String.valueOf(a.get("fin")))).append("</td></tr>");
        }
        Plantilla.Renderizada aviso = plantillas.render(PlantillasEjecucion.SEGUIMIENTO_AVISO, Map.of("ordinal", Seguimiento.ordinal(p.n()),
                "numero", s.numero(), "punto", s.puntoNombre(), "origen", origen.numero(), "fecha", Plazos.fechaLarga(s.fecha()), "acciones", filas.toString()));
        try {
            Correo.Resultado r = correo.enviar(EnvioRegistrado.Registro.de("seguimiento_aviso", "visita:" + seguimientoId), new Correo.Mensaje(para, List.of(), aviso.asunto(), aviso.html(), List.of()));
            visitas.historial(seguimientoId, null, "sistema", r.ok() ? "Aviso al auditor" : "Aviso al auditor fallido",
                    r.ok() ? "Acciones por verificar: " + p.abiertas().size() : Bitacora.cortar(Bitacora.sinCorreos(String.valueOf(r.error())), 300));
            return r.ok() ? "Seguimiento " + s.numero() + " programado para el " + Plazos.fechaLarga(s.fecha()) + "." : "El aviso al auditor del seguimiento falló.";
        } catch (RuntimeException e) {
            LOG.error("[seguimiento] Aviso al líder del seguimiento {}", seguimientoId, e);
            visitas.historial(seguimientoId, null, "sistema", "Aviso al auditor fallido", "Error al enviar el correo");
            return "El aviso al auditor del seguimiento falló.";
        }
    }

    /** «Plan de mejoramiento sin cerrar»: a los coordinadores activos con copia a la DIGSA (sin coordinador, a la DIGSA). */
    private String escalar(RepositorioVisitas.Visita v, Programacion p) {
        List<String> coordinadores = jdbc.sql("""
                SELECT DISTINCT u.correo FROM usuarios u JOIN usuarios_perfiles up ON up.usuario_id = u.id AND up.perfil_id = :p WHERE u.activo""")
                .param("p", Perfil.COORDINADOR.id()).query(String.class).list().stream().map(Correos::normalizar).filter(Correos::valido).toList();
        List<String> digsa = new ArrayList<>();
        for (String c : catalogos.textoParametro("correo_digsa", "").split("[,;\\s]+")) {
            String n = Correos.normalizar(c);
            if (Correos.valido(n) && !digsa.contains(n)) {
                digsa.add(n);
            }
        }
        List<String> para = coordinadores.isEmpty() ? digsa : coordinadores;
        List<String> copia = coordinadores.isEmpty() ? List.of() : digsa;
        if (para.isEmpty()) {
            visitas.historial(v.id(), null, "sistema", "Escalamiento no enviado", "No hay coordinadores activos ni correos de la DIGSA");
            return "El escalamiento no salió: no hay coordinadores ni correos de la DIGSA.";
        }
        StringBuilder filas = new StringBuilder();
        for (Map<String, Object> a : p.abiertas()) {
            filas.append("<li>").append(PlantillaCorreo.e(String.valueOf(a.get("codigo")))).append(": ").append(PlantillaCorreo.e(String.valueOf(a.get("texto"))))
                    .append("</li>");
        }
        Plantilla.Renderizada aviso = plantillas.render(PlantillasEjecucion.SEGUIMIENTO_ESCALAMIENTO, Map.of("numero", v.numero(), "punto", v.puntoNombre(),
                "maximo", String.valueOf(maximo()), "acciones", filas.toString()));
        try {
            Correo.Resultado r = correo.enviar(EnvioRegistrado.Registro.de("seguimiento_escalamiento", "visita:" + v.id()), new Correo.Mensaje(para, copia, aviso.asunto(), aviso.html(), List.of()));
            visitas.historial(v.id(), null, "sistema", r.ok() ? "Escalamiento" : "Escalamiento fallido",
                    r.ok() ? "Enviado a " + para.size() + " destinatario(s)" + (copia.isEmpty() ? (coordinadores.isEmpty() ? " de la DIGSA (no hay coordinadores)" : "; sin correos de la DIGSA") : " con copia a la DIGSA")
                            : Bitacora.cortar(Bitacora.sinCorreos(String.valueOf(r.error())), 300));
            return r.ok() ? "Se alcanzó el máximo de seguimientos: el plan se escaló a la coordinación." : "El escalamiento del plan falló.";
        } catch (RuntimeException e) {
            LOG.error("[seguimiento] Escalamiento de la visita {}", v.id(), e);
            visitas.historial(v.id(), null, "sistema", "Escalamiento fallido", "Error al enviar el correo");
            return "El escalamiento del plan falló.";
        }
    }

    // ---------- Prórrogas ----------

    /** Prórroga detectada en el plan de un seguimiento: la fecha fin de una acción abierta pasa a ser posterior a la anterior. */
    public record Prorroga(String clave, String codigo, LocalDate finAnterior, LocalDate finNuevo, String justificacion) {
    }

    /** Solo de lo que sigue en incumplimiento (las claves que son hallazgo en este seguimiento). */
    List<Prorroga> prorrogas(RepositorioVisitas.Visita v, Map<String, Map<String, Object>> filas, Set<String> hallazgos) {
        List<Prorroga> r = new ArrayList<>();
        if (!v.esSeguimiento()) {
            return r;
        }
        Map<String, Map<String, Object>> antes = anteriores(v);
        for (String clave : hallazgos) {
            Map<String, Object> f = filas.get(clave);
            Map<String, Object> a = antes.get(clave);
            if (f == null || a == null || a.get("fin") == null) {
                continue;
            }
            LocalDate finAnterior = LocalDate.parse(String.valueOf(a.get("fin")));
            LocalDate finNuevo = f.get("fin") == null ? null : LocalDate.parse(String.valueOf(f.get("fin")));
            if (Seguimiento.esProrroga(new Seguimiento.Accion(clave, (String) f.get("estado"), finNuevo), finAnterior)) {
                r.add(new Prorroga(clave, String.valueOf(a.get("codigo")), finAnterior, finNuevo, (String) f.get("prorroga_justificacion")));
            }
        }
        return r;
    }

    /**
     * En la transacción del cierre (el acta salió): cada prórroga queda «solicitada» (decisión 12.1). Si en una ronda nueva cambia la
     * fecha o la justificación, vuelve a «solicitada» sin las decisiones anteriores (nadie aprobó lo nuevo); la que ya no está en el
     * acta queda retirada (rechazada con ese motivo).
     */
    void registrarProrrogas(RepositorioVisitas.Visita v, long actaId, List<Prorroga> lista) {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        for (Prorroga p : lista) {
            jdbc.sql("""
                    INSERT INTO prorrogas (visita_id, origen_visita_id, acta_id, clave, codigo, fin_anterior, fin_nuevo, justificacion, creado)
                    VALUES (:v, :o, :a, :c, :cod, :fa, :fn, :j, :ahora)
                    ON CONFLICT (visita_id, clave) DO UPDATE SET acta_id = EXCLUDED.acta_id, fin_anterior = EXCLUDED.fin_anterior, fin_nuevo = EXCLUDED.fin_nuevo,
                      justificacion = EXCLUDED.justificacion, estado = 'solicitada', coord_usuario_id = NULL, coord_decision = NULL, coord_fecha = NULL,
                      digsa_usuario_id = NULL, digsa_decision = NULL, digsa_fecha = NULL, motivo_rechazo = NULL, creado = EXCLUDED.creado
                    WHERE (prorrogas.fin_nuevo, prorrogas.justificacion, prorrogas.fin_anterior) IS DISTINCT FROM (EXCLUDED.fin_nuevo, EXCLUDED.justificacion, EXCLUDED.fin_anterior)
                       OR prorrogas.estado = 'rechazada'""")
                    .param("v", v.id()).param("o", v.origenId()).param("a", actaId).param("c", p.clave()).param("cod", p.codigo()).param("fa", p.finAnterior())
                    .param("fn", p.finNuevo()).param("j", p.justificacion() == null ? "" : p.justificacion().strip()).param("ahora", ahora).update();
        }
        jdbc.sql("""
                UPDATE prorrogas SET estado = 'rechazada', motivo_rechazo = 'Retirada: ya no está en el acta vigente'
                WHERE visita_id = :v AND estado = 'solicitada' AND NOT (clave = ANY(:c))""")
                .param("v", v.id()).param("c", lista.stream().map(Prorroga::clave).toArray(String[]::new)).update();
        if (!lista.isEmpty()) {
            visitas.historial(v.id(), null, "sistema", "Prórroga solicitada", lista.size() + " acción(es): queda(n) en espera de la aprobación del coordinador y de la DIGSA");
        }
    }

    /** Quién y cómo que remitió el OPL a la visita de origen, por clave (se leen en vivo: el OPL responde después del cierre). */
    Map<String, Map<String, Object>> quienComoOpl(RepositorioVisitas.Visita v) {
        Map<String, Map<String, Object>> r = new LinkedHashMap<>();
        if (!v.esSeguimiento()) {
            return r;
        }
        jdbc.sql("SELECT clave, quien, como FROM opl_respuestas WHERE visita_id = :o AND (quien IS NOT NULL OR como IS NOT NULL)").param("o", v.origenId())
                .query().listOfRows().forEach(f -> r.put((String) f.get("clave"), f));
        return r;
    }

    /** Al enviar el acta del seguimiento: el quién y el cómo del OPL quedan en su plan (lo vacío). */
    void fijarQuienComo(RepositorioVisitas.Visita v) {
        if (!v.esSeguimiento()) {
            return;
        }
        jdbc.sql("""
                UPDATE visitas_plan p SET quien = coalesce(p.quien, r.quien), como = coalesce(p.como, r.como)
                FROM opl_respuestas r WHERE p.visita_id = :s AND r.visita_id = :o AND r.clave = p.clave""")
                .param("s", v.id()).param("o", v.origenId()).update();
    }

    // ---------- Programación manual del coordinador ----------

    /**
     * El coordinador programa el seguimiento de una visita cerrada con acciones abiertas que no lo tiene (situación «sin programar» de
     * «Prestadores con hallazgos», p. ej. porque el líder ya no estaba activo al cerrar).
     */
    public Map<String, Object> programarManual(long visitaId, String fechaTexto, UsuarioSesion quien,
                                               org.springframework.transaction.support.TransactionTemplate tx) {
        LocalDate fecha = Fechas.valida(fechaTexto).orElseThrow(() -> ErrorNegocio.validacion("La fecha no es válida."));
        if (fecha.isBefore(hoy())) {
            throw ErrorNegocio.validacion("La fecha del seguimiento no puede ser anterior a hoy.");
        }
        Long[] creado = new Long[1];
        Programacion[] prog = new Programacion[1];
        tx.executeWithoutResult(t -> {
            RepositorioVisitas.Visita v = visitas.bloquear(visitaId).orElseThrow(() -> ErrorNegocio.noEncontrado("La visita no existe."));
            if (!"cerrada".equals(v.estado())) {
                throw ErrorNegocio.validacion("Solo se programa el seguimiento de una visita cerrada.");
            }
            if (visitas.seguimientoDe(visitaId).isPresent()) {
                throw ErrorNegocio.validacion("La visita ya tiene un seguimiento vigente.");
            }
            long acta = actaVigente(visitaId).orElseThrow(() -> ErrorNegocio.validacion("La visita no tiene un acta enviada."));
            List<Map<String, Object>> hallazgos = jdbc.sql("""
                    SELECT h.clave, h.codigo, h.texto FROM visitas_hallazgos h WHERE h.acta_id = :a
                      AND NOT EXISTS (SELECT 1 FROM opl_respuestas r WHERE r.visita_id = h.visita_id AND r.clave = h.clave AND r.resolucion = 'levanta')
                    ORDER BY h.id""").param("a", acta).query().listOfRows();
            Map<String, Map<String, Object>> filas = new LinkedHashMap<>();
            jdbc.sql("SELECT clave, estado, fin FROM visitas_plan WHERE visita_id = :v").param("v", visitaId).query().listOfRows()
                    .forEach(f -> filas.put((String) f.get("clave"), Map.of("estado", f.get("estado"), "fin", f.get("fin") == null ? "" : f.get("fin").toString())));
            filas.replaceAll((k, f) -> "".equals(f.get("fin")) ? Map.of("estado", f.get("estado")) : f);
            Programacion p = programacion(v, filas, hallazgos);
            if (Programacion.MAXIMO.equals(p.situacion())) {
                throw ErrorNegocio.validacion("La visita llegó al máximo de " + maximo() + " seguimientos: el plan se escala, no se programa otro.");
            }
            if (p.abiertas().isEmpty()) {
                throw ErrorNegocio.validacion("La visita no tiene acciones abiertas por verificar.");
            }
            Programacion manual = new Programacion(Programacion.PROGRAMADO, fecha, p.n(), p.abiertas());
            long id = agenda.programarSeguimiento(v, fecha, manual.n(), alcance(manual, v.numero()), quien);
            inicializar(id, v);
            visitas.historial(id, quien.id(), quien.usuario(), "Programada a mano", "Seguimiento programado por la coordinación");
            creado[0] = id;
            prog[0] = manual;
        });
        RepositorioVisitas.Visita origen = visitas.porId(visitaId).orElseThrow();
        List<String> mensajes = despuesDelCierre(origen, 0, new Resultado(prog[0], creado[0], null), quien);
        return Map.of("id", creado[0], "mensaje", String.join(" ", mensajes));
    }
}
