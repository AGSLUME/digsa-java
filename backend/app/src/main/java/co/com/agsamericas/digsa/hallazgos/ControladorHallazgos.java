package co.com.agsamericas.digsa.hallazgos;

import co.com.agsamericas.digsa.identidad.UsuarioSesion;
import co.com.agsamericas.digsa.plataforma.Accion;
import co.com.agsamericas.digsa.reglas.Bitacora.Declarada;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** «Prestadores con hallazgos»: Auditor (su equipo), Coordinador y DIGSA (todo), Sanidad (su fuerza) y ESM (su establecimiento). */
@RestController
public class ControladorHallazgos {

    private final ServicioHallazgos hallazgos;

    public ControladorHallazgos(ServicioHallazgos hallazgos) {
        this.hallazgos = hallazgos;
    }

    @GetMapping("/api/hallazgos")
    @PreAuthorize("@permisos.pantalla('HALLAZGOS')")
    @Accion(modulo = "hallazgos", accion = "lista", operacion = Declarada.CONSULTA)
    public List<Map<String, Object>> lista() {
        return hallazgos.lista(UsuarioSesion.alcanceActual(), UsuarioSesion.actual().orElseThrow(), UsuarioSesion.perfilesActuales());
    }

    /**
     * ZIP con los soportes del OPL de una visita (duda 22.12): DIGSA y Sanidad de su fuerza; el ESM no. Se escribe en flujo a la respuesta,
     * después de verificar el alcance (el 403 sale antes de escribir nada).
     */
    @GetMapping("/api/hallazgos/{visitaId}/soportes-opl.zip")
    @PreAuthorize("@permisos.pantalla('HALLAZGOS')")
    @Accion(modulo = "hallazgos", accion = "soportes_opl", operacion = Declarada.CONSULTA)
    public void soportesOpl(@PathVariable long visitaId, HttpServletResponse respuesta) throws IOException {
        ServicioHallazgos.SoportesOpl s = hallazgos.soportesOpl(visitaId, UsuarioSesion.alcanceActual(), UsuarioSesion.perfilesActuales(),
                UsuarioSesion.actual().orElseThrow());
        respuesta.setContentType("application/zip");
        respuesta.setHeader("Content-Disposition", "attachment; filename=\"" + s.nombreZip() + "\"");
        respuesta.setHeader("X-Content-Type-Options", "nosniff");
        respuesta.setHeader("Cache-Control", "no-store");
        hallazgos.escribirSoportesOpl(s, respuesta.getOutputStream());
    }
}
