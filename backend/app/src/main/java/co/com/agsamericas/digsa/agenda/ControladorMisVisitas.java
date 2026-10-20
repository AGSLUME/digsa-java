package co.com.agsamericas.digsa.agenda;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.plataforma.ErrorNegocio;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Mis visitas del auditor: solo las de su equipo y, de cada una, solo sus partes (Anexo 5, prueba 11). */
@RestController
@RequestMapping("/api/mis-visitas")
@PreAuthorize("@permisos.pantalla('MIS_VISITAS')")
public class ControladorMisVisitas {

    private final ServicioConsultaAgenda consulta;
    private final ServicioAgenda agenda;
    private final RepositorioVisitas visitas;

    public ControladorMisVisitas(ServicioConsultaAgenda consulta, ServicioAgenda agenda, RepositorioVisitas visitas) {
        this.consulta = consulta;
        this.agenda = agenda;
        this.visitas = visitas;
    }

    @GetMapping
    @Accion(modulo = "mis_visitas", accion = "listar", operacion = Declarada.CONSULTA)
    public List<Map<String, Object>> listar() {
        return consulta.misVisitas(UsuarioSesion.actual().orElseThrow().id());
    }

    @GetMapping("/{id}")
    @Accion(modulo = "mis_visitas", accion = "detalle", operacion = Declarada.CONSULTA)
    public Map<String, Object> detalle(@PathVariable long id) {
        long yo = UsuarioSesion.actual().orElseThrow().id();
        if (!visitas.esDelEquipo(id, yo)) {
            throw ErrorNegocio.noEncontrado("La visita no existe o no está asignada a usted.");
        }
        // Como auditor (aunque además sea coordinador): solo sus partes
        return consulta.detalle(id, yo, java.util.Set.of(co.com.agsamericas.digsa.reglas.Perfil.AUDITOR), new co.com.agsamericas.digsa.reglas.Alcance.Equipo(yo));
    }

    @PostMapping("/{id}/iniciar")
    @Accion(modulo = "mis_visitas", accion = "iniciar", operacion = Declarada.ACTUALIZACION)
    public ServicioAgenda.Resultado iniciar(@PathVariable long id) {
        return agenda.iniciar(id, UsuarioSesion.actual().orElseThrow());
    }
}
