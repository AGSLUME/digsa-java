package co.com.agsamericas.digsa.agenda;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Alcance;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Agenda de visitas: solo el Coordinador (el Administrador pasa todas las comprobaciones de perfil). */
@RestController
@RequestMapping("/api/agenda")
@PreAuthorize("@permisos.pantalla('AGENDA')")
public class ControladorAgenda {

    record Reprogramacion(String fecha, String hora, String motivo, Boolean notificar) {
    }

    record Motivo(String motivo) {
    }

    record Reasignacion(Long liderId, List<Long> otros, String motivo) {
    }

    record Movimiento(String parte, Long usuarioId) {
    }

    private final ServicioAgenda agenda;
    private final ServicioConsultaAgenda consulta;
    private final ServicioNotificacion notificacion;
    private final RepositorioVisitas visitas;

    public ControladorAgenda(ServicioAgenda agenda, ServicioConsultaAgenda consulta, ServicioNotificacion notificacion, RepositorioVisitas visitas) {
        this.agenda = agenda;
        this.consulta = consulta;
        this.notificacion = notificacion;
        this.visitas = visitas;
    }

    private static UsuarioSesion quien() {
        return UsuarioSesion.actual().orElseThrow();
    }

    @GetMapping("/opciones")
    @Accion(modulo = "agenda", accion = "opciones", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> opciones() {
        return consulta.opciones();
    }

    @GetMapping
    @Accion(modulo = "agenda", accion = "listar", operacion = Declarada.CONSULTA)
    public List<Map<String, Object>> listar(@RequestParam(required = false) String desde, @RequestParam(required = false) String hasta,
                                            @RequestParam(required = false) String estado, @RequestParam(name = "punto_id", required = false) Long puntoId,
                                            @RequestParam(name = "auditor_id", required = false) Long auditorId,
                                            @RequestParam(required = false) String fuerza, @RequestParam(required = false) String unidad,
                                            @RequestParam(required = false) String tipo) {
        return consulta.listar(new ServicioConsultaAgenda.Filtros(desde, hasta, estado, puntoId, auditorId, fuerza, unidad, tipo), new Alcance.Todo());
    }

    @GetMapping("/seguimiento")
    @Accion(modulo = "agenda", accion = "seguimiento", operacion = Declarada.SIN_REGISTRO)
    public Map<String, Object> seguimiento() {
        return consulta.seguimiento();
    }

    @GetMapping("/{id}")
    @Accion(modulo = "agenda", accion = "detalle", operacion = Declarada.CONSULTA)
    public Map<String, Object> detalle(@PathVariable long id) {
        return consulta.detalle(id, quien().id(), UsuarioSesion.perfilesActuales(), new Alcance.Todo());
    }

    @PostMapping
    @Accion(modulo = "agenda", accion = "programar", operacion = Declarada.INSERCION)
    public ResponseEntity<ServicioAgenda.Resultado> programar(@RequestBody ServicioAgenda.Datos d) {
        return ResponseEntity.status(HttpStatus.CREATED).body(agenda.programar(d, quien()));
    }

    @PutMapping("/{id}")
    @Accion(modulo = "agenda", accion = "editar", operacion = Declarada.ACTUALIZACION)
    public ServicioAgenda.Resultado editar(@PathVariable long id, @RequestBody ServicioAgenda.Datos d) {
        return agenda.editar(id, d, quien());
    }

    @PostMapping("/{id}/reprogramar")
    @Accion(modulo = "agenda", accion = "reprogramar", operacion = Declarada.ACTUALIZACION)
    public ServicioAgenda.Resultado reprogramar(@PathVariable long id, @RequestBody Reprogramacion r) {
        return agenda.reprogramar(id, r.fecha(), r.hora(), r.motivo(), r.notificar() == null || r.notificar(), quien());
    }

    @PostMapping("/{id}/cancelar")
    @Accion(modulo = "agenda", accion = "cancelar", operacion = Declarada.ACTUALIZACION)
    public ServicioAgenda.Resultado cancelar(@PathVariable long id, @RequestBody Motivo m) {
        return agenda.cancelar(id, m.motivo(), quien());
    }

    @PostMapping("/{id}/reasignar")
    @Accion(modulo = "agenda", accion = "reasignar", operacion = Declarada.ACTUALIZACION)
    public ServicioAgenda.Resultado reasignar(@PathVariable long id, @RequestBody Reasignacion r) {
        return agenda.reasignar(id, r.liderId(), r.otros(), r.motivo(), quien());
    }

    @PostMapping("/{id}/mover")
    @Accion(modulo = "agenda", accion = "mover_parte", operacion = Declarada.ACTUALIZACION)
    public Map<String, Object> mover(@PathVariable long id, @RequestBody Movimiento m) {
        if (m.parte() == null || m.usuarioId() == null) {
            throw ErrorNegocio.validacion("Elija la parte y el auditor.");
        }
        return Map.of("mensaje", agenda.moverParte(id, m.parte(), m.usuarioId(), quien()));
    }

    /** Destinatarios, asunto y cuerpo calculados, para revisarlos antes de notificar a mano. */
    @GetMapping("/{id}/notificacion")
    @Accion(modulo = "agenda", accion = "notificacion_ver", operacion = Declarada.CONSULTA)
    public Map<String, Object> verNotificacion(@PathVariable long id) {
        RepositorioVisitas.Visita v = visitas.porId(id).orElseThrow(() -> ErrorNegocio.noEncontrado("La visita no existe."));
        ServicioNotificacion.Calculada c = notificacion.calcular(v, null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("destinatarios", c.destinatarios());
        m.put("faltan", c.faltan());
        m.put("asunto", c.asunto());
        m.put("cuerpo", c.cuerpo());
        return m;
    }

    @PostMapping("/{id}/notificar")
    @Accion(modulo = "agenda", accion = "notificar", operacion = Declarada.ACTUALIZACION)
    public ServicioNotificacion.Resultado notificar(@PathVariable long id, @RequestBody(required = false) ServicioNotificacion.Manual m) {
        boolean manual = m != null && (m.destinatarios() != null || m.asunto() != null || m.cuerpo() != null);
        return notificacion.notificar(id, ServicioNotificacion.MANUAL, null, manual ? m : null, quien());
    }
}
